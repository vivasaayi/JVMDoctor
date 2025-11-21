#!/usr/bin/env zsh
set -euo pipefail

BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
RUN_DIR="$BASE_DIR/run"

stop_if_running() {
  local pidfile=$1
  if [ -f "$pidfile" ]; then
    pid=$(cat "$pidfile")
    if kill -0 "$pid" 2>/dev/null; then
      echo "Stopping pid $pid"
      kill "$pid"
      sleep 1
      if kill -0 "$pid" 2>/dev/null; then
        echo "PID $pid didn't stop — killing"
        kill -9 "$pid" || true
      fi
    fi
    rm -f "$pidfile"
  fi
}

stop_if_running "$RUN_DIR/backend.pid"
stop_if_running "$RUN_DIR/sample.pid"

echo "All processes stopped (if they were running)."