#!/usr/bin/env bash
# Start the application. Runs setup automatically on first use.
set -euo pipefail
cd "$(dirname "$0")"
[ -d .venv ] || ./setup.sh
# shellcheck disable=SC1091
source .venv/bin/activate
exec streamlit run app.py --server.port "${PORT:-8501}" "$@"
