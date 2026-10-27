#!/usr/bin/env bash
# One QA screenshot: waits for the panels to catch up (the emulator idles near 1 fps), captures twice, keeps the second.
#   tools/shot.sh NAME [WAIT_S]   -> .scratch/shots/NAME.png
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
sleep "${2:-8}"
"$ROOT/tools/cap.sh" "$1" >/dev/null
sleep 2
"$ROOT/tools/cap.sh" "$1"
