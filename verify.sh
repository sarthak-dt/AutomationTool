#!/usr/bin/env bash
# Run the full test suite (and report which tests were skipped and why).
set -euo pipefail
cd "$(dirname "$0")"
[ -d .venv ] || ./setup.sh
# shellcheck disable=SC1091
source .venv/bin/activate
python -m pytest tests -rs "$@"
