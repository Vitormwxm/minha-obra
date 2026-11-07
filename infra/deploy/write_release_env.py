#!/usr/bin/env python3
"""Validate CI provenance and immutable image references without reading secrets."""
import argparse
import json
import os
import pathlib
import re
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("manifest", type=pathlib.Path)
parser.add_argument("output", type=pathlib.Path)
parser.add_argument("--sha", required=True)
parser.add_argument("--repository", required=True)
args = parser.parse_args()
if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
    parser.error("Expected a complete lowercase commit SHA.")
if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
    parser.error("Invalid repository.")
manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
if manifest.get("source_sha") != args.sha:
    parser.error("Manifest does not belong to the requested commit.")
if not re.fullmatch(r"[0-9]+", str(manifest.get("source_run_id", ""))):
    parser.error("Missing CI run provenance.")
lines = []
for key, suffix in (("backend_image", "backend"), ("frontend_image", "frontend")):
    prefix = f"ghcr.io/{args.repository.lower()}-{suffix}"
    value = manifest.get(key, "")
    if not isinstance(value, str) or not re.fullmatch(re.escape(prefix) + r"@sha256:[0-9a-f]{64}", value):
        parser.error(f"Invalid immutable image for {suffix}.")
    lines.append(f"{key.upper()}={value}")
if args.output.is_symlink():
    parser.error("Refusing to replace a symlink.")
args.output.parent.mkdir(parents=True, exist_ok=True)
fd, temporary = tempfile.mkstemp(prefix=".release-", dir=args.output.parent)
try:
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
        stream.write("\n".join(lines) + "\n")
    os.replace(temporary, args.output)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
