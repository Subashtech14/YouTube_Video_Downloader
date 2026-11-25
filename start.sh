#!/usr/bin/env sh
set -e
cd "$(dirname "$0")"

# Pick the newest available Python 3.10+ (yt-dlp needs 3.10 or later).
PY=""
for cand in python3.15 python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
  if command -v "$cand" >/dev/null 2>&1 &&
     "$cand" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
    PY="$cand"
    break
  fi
done
if [ -z "$PY" ]; then
  echo "Python 3.10+ is required. Install it with: brew install python" >&2
  exit 1
fi

# Use a local virtual environment so pip works on Homebrew/system Pythons (PEP 668).
# Rebuild it if it is missing or its base interpreter has gone away (e.g. after a brew upgrade).
if ! .venv/bin/python -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
  echo "Creating virtual environment with $("$PY" --version)..."
  rm -rf .venv
  "$PY" -m venv .venv
fi

.venv/bin/python -m pip install -q --upgrade pip
.venv/bin/python -m pip install -q -r requirements.txt
exec .venv/bin/python app.py
