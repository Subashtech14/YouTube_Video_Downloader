#!/usr/bin/env sh
set -e
cd "$(dirname "$0")"

# Use a local virtual environment so pip works on Homebrew/system Pythons (PEP 668).
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv
fi

.venv/bin/python -m pip install -q --upgrade pip
.venv/bin/python -m pip install -q -r requirements.txt
exec .venv/bin/python app.py
