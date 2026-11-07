#!/usr/bin/env bash
# Run on the VPS itself. The default is a read-only inventory and plan.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 "$script_dir/retire_focuslab.py" "$@"
