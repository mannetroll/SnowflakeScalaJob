#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "$0")"
export EAD_LOCAL_DEV=1
exec .venv/bin/streamlit run streamlit_app.py
