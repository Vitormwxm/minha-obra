#!/usr/bin/env bash
# Optional second phase: only unconsumed application images with proven tags.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 "$script_dir/retire_focuslab_images.py" "$@"
