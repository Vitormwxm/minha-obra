#!/usr/bin/env python3
"""Exercise accounts and financing through HTTPS against the isolated CI stack."""
import argparse
import base64
import datetime
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
from zoneinfo import ZoneInfo


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
            assert "no-store" in response.headers.get("Cache-Control", ""), "Private API responses must not be cached."
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


def assert_finance(person, admin):
    empty = {"financing": None, "months": [], "missingMonths": []}
    assert person.request("GET", "/api/finance") == empty
    now = datetime.datetime.now(ZoneInfo("America/Fortaleza")).date()
    delivery = (now.replace(day=28) + datetime.timedelta(days=4)).replace(day=1)
    person.request("PUT", "/api/finance/financing", {
        "version": None, "financedCents": 19_200_000, "releasedCents": 8_400_000,
        "annualNominalRate": "7.5", "expectedDeliveryDate": delivery.isoformat(),
        "trackingStartMonth": "2025-01", "subsidyCents": 1_000_000,
    }, key=str(uuid.uuid4()))
    month = {
        "version": None, "caixaDueDate": "2025-03-10", "builderDueDate": None,
        "caixaStatus": "PAID", "builderStatus": "NO_PAYMENT",
        "releasedCents": 8_000_000, "insuranceCents": 3_500, "feesCents": 2_500,
        "trSettings": {"periodStart": None, "periodEnd": None, "manualRate": "0.3"},
        "payments": [{"kind": "CAIXA", "paidOn": "2025-03-12", "paidCents": 50_000,
                      "lateChargeCents": 700}],
    }
    key = str(uuid.uuid4())
    path = "/api/finance/months/2025-03"
    saved = person.request("PUT", path, month, key=key)
    assert person.request("PUT", path, month, key=key) == saved
    ledger = person.request("GET", "/api/finance")
    assert ledger["financing"]["financedCents"] == 19_200_000
    assert ledger["financing"]["subsidyCents"] == 1_000_000
    assert ledger["missingMonths"] == ["2025-01", "2025-02"]
    assert len(ledger["months"]) == 1
    row = ledger["months"][0]
    assert row["monthlyTotalCents"] == row["accumulatedPaidCents"] == 50_000
    assert row["builderPaidCents"] == 0 and row["closed"]
    assert row["tr"] == {"origin": "MANUAL", "rate": "0.3"}
    assumptions = {"monthlyTr": "0.2", "insuranceCents": 3500, "feesCents": 2500}
    projection = person.request("POST", "/api/finance/projection", assumptions)
    assert projection["rows"][-1]["month"] == delivery.isoformat()[:7]
    assert projection["rows"][-1]["releasedCents"] == 19_200_000
    assert sum(item["releaseCents"] for item in projection["rows"]) == 10_800_000
    assert projection["totalCents"] == projection["knownSubtotalCents"] > 0
    assumptions["monthlyTr"] = None
    partial = person.request("POST", "/api/finance/projection", assumptions)
    assert partial["totalCents"] is None and partial["knownSubtotalCents"] > 0
    admin.request("POST", "/api/finance/projection", assumptions, expected=404)
    assert admin.request("GET", "/api/finance") == empty


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


def assert_deletion(person, admin):
    path = "/api/account/deletion"
    assert person.request("GET", path) == {"request": None}
    admin.request("GET", path, expected=403)
    person.request("GET", "/api/admin/deletions", expected=403)

    request_key = str(uuid.uuid4())
    first = person.request("POST", path, key=request_key)
    assert person.request("POST", path, key=request_key) == first
    old_id = first["request"]["id"]
    ledger = person.request("GET", "/api/finance")
    assert len(ledger["months"]) == 1
    denied = person.request("DELETE", "/api/finance/months/2025-03",
                            {"version": ledger["months"][0]["version"]}, key=str(uuid.uuid4()), expected=409)
    assert denied["code"] == "ACCOUNT_DELETION_PENDING"
    canceled = person.request("POST", path + "/cancel", {"requestId": old_id}, key=str(uuid.uuid4()))
    assert canceled == {"request": None}

    request_id = person.request("POST", path, key=str(uuid.uuid4()))["request"]["id"]
    assert request_id != old_id
    admin.request("POST", f"/api/admin/deletions/{old_id}/approve", key=str(uuid.uuid4()), expected=404)
    pending = admin.request("GET", "/api/admin/deletions")
    assert pending["total"] == 1 and pending["items"][0]["id"] == request_id
    assert pending["items"][0]["username"] == "ci_person"
    approval_key = str(uuid.uuid4())
    target = f"/api/admin/deletions/{request_id}/approve"
    admin.request("POST", target, key=approval_key, expected=204)
    admin.request("POST", target, key=approval_key, expected=204)
    person.request("GET", "/api/auth/me", expected=401)
    assert admin.request("GET", "/api/admin/deletions")["items"] == []
    assert admin.request("GET", "/api/admin/users?search=ci_person")["items"] == []


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
    provisional, permanent, person_password, replacement, new_password = (
        f"{value:08d}" for value in secrets.SystemRandom().sample(range(100_000_000), 5)
    )
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
    registration = {"username": "ci_person", "displayName": "Pessoa de teste CI",
                    "email": "ci-person@example.com", "password": person_password}
    key = str(uuid.uuid4())
    person.request("POST", "/api/auth/register", registration, expected=403, key=key, csrf=False)
    invalid = person.request("POST", "/api/auth/register", {**registration, "password": "0123456x"},
                             expected=400, key=key)
    assert invalid["code"] == "PASSWORD_POLICY"
    pending = person.request("POST", "/api/auth/register", registration, expected=201, key=key)
    assert pending == {"status": "PENDING_APPROVAL"}
    assert person.request("POST", "/api/auth/register", registration, expected=201, key=key) == pending
    login = {"username": "ci_person", "password": person_password}
    assert person.request("POST", "/api/auth/login", login, expected=403)["code"] == "ACCOUNT_PENDING_APPROVAL"
    person.request("GET", "/api/auth/me", expected=401)
    person.request("GET", "/api/finance", expected=401)
    user = latest_user(admin)
    assert user["status"] == "PENDING_APPROVAL" and not user["mustChangePassword"]
    assert user["email"] == registration["email"]
    approved = admin.request("POST", "/api/admin/users/" + user["id"] + "/approve",
                             {"version": user["version"]}, key=str(uuid.uuid4()))
    assert approved["status"] == "ACTIVE" and not approved["mustChangePassword"]
    assert person.request("POST", "/api/auth/login", login)["stage"] == "AUTHENTICATED"
    person.request("GET", "/api/account")
    assert_finance(person, admin)
    user = latest_user(admin)
    blocked = admin.request("POST", "/api/admin/users/" + user["id"] + "/block",
                            {"version": user["version"]}, key=str(uuid.uuid4()))
    person.request("GET", "/api/auth/me", expected=401)
    active = admin.request("POST", "/api/admin/users/" + user["id"] + "/unblock",
                           {"version": blocked["version"]}, key=str(uuid.uuid4()))
    assert person.request("POST", "/api/auth/login", {"username": "ci_person", "password": person_password})["stage"] == "AUTHENTICATED"
    admin.request("POST", "/api/admin/users/" + user["id"] + "/temporary-password",
                   {"version": active["version"], "temporaryPassword": replacement}, key=str(uuid.uuid4()))
    person.request("GET", "/api/auth/me", expected=401)
    assert person.request("POST", "/api/auth/login", {"username": "ci_person", "password": replacement})["stage"] == "CHANGE_PASSWORD"
    person.request("GET", "/api/account", expected=403)
    person.request("GET", "/api/finance", expected=403)
    assert person.request("POST", "/api/auth/change-password", {"currentPassword": replacement,
        "newPassword": new_password})["stage"] == "AUTHENTICATED"
    assert_events(compose)
    assert_deletion(person, admin)
    print("HTTPS account lifecycle, password reset, CSRF, MFA, financing, deletion and Kafka passed.")


if __name__ == "__main__":
    main()
