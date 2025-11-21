#!/usr/bin/env zsh
set -euo pipefail

BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
RUN_DIR="$BASE_DIR/run"

check_pid() {
  local pidfile=$1
  if [ -f "$pidfile" ]; then
    pid=$(cat "$pidfile")
    if kill -0 "$pid" 2>/dev/null; then
      echo "Running: $pidfile (pid $pid)"
    else
      echo "Stale pidfile: $pidfile (pid $pid not running)"
    fi
  else
    echo "No pidfile: $pidfile"
  fi
}

check_pid "$RUN_DIR/backend.pid"
check_pid "$RUN_DIR/sample.pid"
