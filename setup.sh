#!/usr/bin/env bash
# Create a virtualenv and install dependencies.
set -euo pipefail
cd "$(dirname "$0")"
PY="${PYTHON:-python3}"
"$PY" - <<'PYCHK'
import sys
if sys.version_info < (3, 10):
    sys.exit("Python 3.10+ is required (found %s)" % sys.version.split()[0])
PYCHK
[ -d .venv ] || "$PY" -m venv .venv
# shellcheck disable=SC1091
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements-dev.txt
[ -f .env ] || { cp .env.example .env; echo "Created .env from .env.example (edit it if needed)."; }
echo "Setup complete. Start with: ./run.sh"
