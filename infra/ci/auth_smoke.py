#!/usr/bin/env python3
"""Exercise account lifecycle through HTTPS against the isolated CI stack."""
import argparse
import base64
import hashlib
import hmac
import http.cookiejar
import json
import os
import pty
import secrets
import select
import ssl
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("Unexpected redirect in the account API smoke test.")


class Client:
    def __init__(self, origin, context):
        self.origin = origin
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context),
            urllib.request.HTTPCookieProcessor(self.cookies), NoRedirect())

    def request(self, method, path, body=None, expected=200, key=None, csrf=True):
        headers = {"Accept": "application/json"}
        if method != "GET" and csrf:
            self.request("GET", "/api/auth/csrf")
            token = next((c.value for c in self.cookies if c.name == "XSRF-TOKEN"), None)
            assert token, "Missing CSRF cookie."
            headers["X-XSRF-TOKEN"] = urllib.parse.unquote(token)
        if key:
            headers["Idempotency-Key"] = key
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        request = urllib.request.Request(self.origin + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == expected, f"{method} {path}: expected {expected}, got {response.status}."
            assert "no-store" in response.headers.get("Cache-Control", ""), "Account responses must not be cached."
            raw = response.read()
            return json.loads(raw) if raw else None


def bootstrap(compose, password):
    """Console.readPassword must work in a real terminal and must not echo."""
    master, slave = pty.openpty()
    command = compose + ["exec", "backend", "java", "-Xmx128m", "-jar", "/app/application.jar",
                         "--bootstrap-admin", "ci_admin"]
    process = subprocess.Popen(command, stdin=slave, stdout=slave, stderr=slave)
    os.close(slave)
    output = bytearray()
    stage = 0
    deadline = time.monotonic() + 60
    try:
        while time.monotonic() < deadline:
            readable, _, _ = select.select([master], [], [], 0.2)
            if readable:
                try:
                    chunk = os.read(master, 8192)
                except OSError:
                    break
                if not chunk:
                    break
                output.extend(chunk)
                # Only send after Console has disabled echo and displayed its prompt.
                if stage == 0 and b"administrador: " in output:
                    os.write(master, password.encode() + b"\n")
                    stage = 1
                if stage == 1 and b"Repita a senha" in output:
                    os.write(master, password.encode() + b"\n")
                    stage = 2
            if process.poll() is not None and not readable:
                break
        if process.poll() is None:
            process.terminate()
        code = process.wait(timeout=10)
        assert code == 0 and stage == 2, "Interactive bootstrap failed; output suppressed to protect credentials."
        assert password.encode() not in output, "Bootstrap must not echo the provisional password."
    finally:
        os.close(master)
        if process.poll() is None:
            process.kill()
            process.wait()


def totp(secret):
    key = base64.b32decode(secret + "=" * ((-len(secret)) % 8))
    digest = hmac.new(key, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
    offset = digest[-1] & 15
    value = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return f"{value % 1_000_000:06d}"


def latest_user(admin):
    page = admin.request("GET", "/api/admin/users?search=ci_person&page=0&size=20")
    return next(user for user in page["items"] if user["username"] == "ci_person")


def assert_events(compose):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        result = subprocess.run(compose + ["exec", "-T", "postgres", "psql", "-U", "postgres", "-d",
            "minhaobra", "-Atc", "SELECT COUNT(*) FROM minhaobra.account_outbox WHERE published_at IS NULL"],
            check=True, capture_output=True, text=True)
        if result.stdout.strip() == "0":
            break
        time.sleep(2)
    else:
        raise AssertionError("Account outbox did not drain to Kafka.")
    result = subprocess.run(compose + ["exec", "-T", "-e", "KAFKA_HEAP_OPTS=-Xms16m -Xmx64m", "kafka",
        "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "localhost:9092", "--topic",
        "minhaobra.account-events.v1", "--from-beginning", "--max-messages", "1", "--timeout-ms", "10000"],
        check=True, capture_output=True, text=True, timeout=25)
    event = json.loads(result.stdout.strip())
    assert isinstance(event, dict) and event, "Kafka event is missing."
    assert not any(any(word in key.lower() for word in ("password", "secret", "recovery")) for key in event), \
        "Account events must not contain credentials."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--ca", required=True)
    parser.add_argument("--compose", required=True)
    parser.add_argument("--file", required=True)
    args = parser.parse_args()
    if not args.domain.endswith(".example.com"):
        parser.error("This harness is limited to the isolated example.com CI environment.")
    compose = [args.compose, "-p", "minha-obra-ci", "--env-file", "/dev/null", "-f", args.file]
    context = ssl.create_default_context(cafile=args.ca)
    origin = "https://" + args.domain
    provisional = secrets.token_urlsafe(18)
    permanent = secrets.token_urlsafe(18)
    bootstrap(compose, provisional)
    admin = Client(origin, context)
    admin.request("POST", "/api/auth/login", {"username": "ci_admin", "password": provisional},
                  expected=403, csrf=False)
    session = admin.request("POST", "/api/auth/login", {"username": "ci_admin", "password": provisional})
    assert session["stage"] == "CHANGE_PASSWORD"
    cookie = next(c for c in admin.cookies if c.name == "MINHAOBRA_SESSION")
    assert cookie.secure and cookie.path == "/" and cookie.has_nonstandard_attr("HttpOnly")
    admin.request("GET", "/api/admin/users", expected=403)
    session = admin.request("POST", "/api/auth/change-password",
                            {"currentPassword": provisional, "newPassword": permanent})
    assert session["stage"] == "MFA_SETUP"
    setup = admin.request("POST", "/api/auth/mfa/setup", {})
    confirmed = admin.request("POST", "/api/auth/mfa/confirm", {"code": totp(setup["secret"])})
    assert confirmed["session"]["stage"] == "AUTHENTICATED" and len(confirmed["recoveryCodes"]) >= 8
    person = Client(origin, context)
    person_password = secrets.token_urlsafe(18)
    registration = {"username": "ci_person", "displayName": "Pessoa de teste CI", "password": person_password}
    key = str(uuid.uuid4())
    person.request("POST", "/api/auth/register", registration, expected=403, key=key, csrf=False)
    pending = person.request("POST", "/api/auth/register", registration, expected=201, key=key)
    assert pending == {"status": "PENDING_APPROVAL"}
    assert person.request("POST", "/api/auth/register", registration, expected=201, key=key) == pending
    login = {"username": "ci_person", "password": person_password}
    assert person.request("POST", "/api/auth/login", login, expected=403)["code"] == "ACCOUNT_PENDING_APPROVAL"
    person.request("GET", "/api/auth/me", expected=401)
    user = latest_user(admin)
    assert user["status"] == "PENDING_APPROVAL" and not user["mustChangePassword"]
    approved = admin.request("POST", "/api/admin/users/" + user["id"] + "/approve",
                             {"version": user["version"]}, key=str(uuid.uuid4()))
    assert approved["status"] == "ACTIVE" and not approved["mustChangePassword"]
    assert person.request("POST", "/api/auth/login", login)["stage"] == "AUTHENTICATED"
    person.request("GET", "/api/account")
    user = latest_user(admin)
    blocked = admin.request("POST", "/api/admin/users/" + user["id"] + "/block",
                            {"version": user["version"]}, key=str(uuid.uuid4()))
    person.request("GET", "/api/auth/me", expected=401)
    active = admin.request("POST", "/api/admin/users/" + user["id"] + "/unblock",
                           {"version": blocked["version"]}, key=str(uuid.uuid4()))
    assert person.request("POST", "/api/auth/login", {"username": "ci_person", "password": person_password})["stage"] == "AUTHENTICATED"
    replacement = secrets.token_urlsafe(18)
    admin.request("POST", "/api/admin/users/" + user["id"] + "/temporary-password",
                   {"version": active["version"], "temporaryPassword": replacement}, key=str(uuid.uuid4()))
    person.request("GET", "/api/auth/me", expected=401)
    assert person.request("POST", "/api/auth/login", {"username": "ci_person", "password": replacement})["stage"] == "CHANGE_PASSWORD"
    person.request("GET", "/api/account", expected=403)
    assert person.request("POST", "/api/auth/change-password", {"currentPassword": replacement,
        "newPassword": secrets.token_urlsafe(18)})["stage"] == "AUTHENTICATED"
    assert_events(compose)
    print("HTTPS registration, approval, password reset, CSRF, MFA, revocation, idempotency and Kafka passed.")


if __name__ == "__main__":
    main()
