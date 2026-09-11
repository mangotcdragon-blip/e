#!/usr/bin/env bash
# Start the WiFi Mouse server on macOS or Linux.
set -euo pipefail
cd "$(dirname "$0")"

if ! python3 -c "import pynput" >/dev/null 2>&1; then
  echo "Installing pynput..."
  python3 -m pip install --user -r requirements.txt
fi

exec python3 wifimouse_server.py "$@"
