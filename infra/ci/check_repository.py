#!/usr/bin/env python3
"""Reject accidental publication of signing material, credentials and build output."""
import pathlib
import re
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[2]
listed = subprocess.check_output(
    ["git", "-c", f"safe.directory={root.as_posix()}", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
)
paths = sorted(set(path.decode("utf-8") for path in listed.split(b"\0") if path))
forbidden_dirs = {".signing", ".build-tools", "node_modules", "backups", ".gradle", "push-secrets"}
forbidden_suffixes = {".jks", ".keystore", ".p12", ".pem", ".key", ".apk", ".aab", ".dump"}
key_pattern = re.compile(
    rb"(?m)^-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----\s*$"
)
json_private_key = re.compile(rb'"private_key"\s*:\s*"-----BEGIN (?:RSA |EC )?PRIVATE KEY-----')
violations = []
for name in paths:
    path = pathlib.PurePosixPath(name)
    if (
        any(part in forbidden_dirs for part in path.parts)
        or path.suffix.lower() in forbidden_suffixes
        or path.name in {"signing.properties", "local.properties", "google-services.json", "firebase-push.json"}
        or (path.suffix.lower() == ".json" and "firebase-adminsdk" in path.name)
        or (path.name.startswith(".env") and path.name != ".env.example")
    ):
        violations.append(name)
        continue
    local = root / name
    if local.is_file() and local.stat().st_size < 2_000_000:
        content = local.read_bytes()
        if key_pattern.search(content) or json_private_key.search(content):
            violations.append(name)
if violations:
    print("Forbidden private files or private key material:", file=sys.stderr)
    for name in violations:
        print(name, file=sys.stderr)
    raise SystemExit(1)
print(f"Repository protection passed ({len(paths)} files checked).")
