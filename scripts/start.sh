#!/usr/bin/env zsh
set -euo pipefail

# JVMDoctor start script (zsh)
# - Builds with Maven
# - Builds React UI and copies to backend static folder
# - Starts backend and sample app with agent
# - Writes pid files to ./run

BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$BASE_DIR"

RUN_DIR="$BASE_DIR/run"
mkdir -p "$RUN_DIR"

# Convenience env overrides
: ${JAVA_HOME:=""}
: ${MAX_CONCURRENT_PROFILER_TASKS:=2}
: ${MAX_PROCESSES:=20}
: ${MAX_XMX_MB:=1024}
: ${ASYNC_PROFILER_HOME:=""}

# Check Java
echo "Checking Java version..."
java_version=$(java -version 2>&1 | head -n 1)
echo "$java_version"
if ! java -version 2>&1 | grep -q 'version \"25' ; then
  echo "WARNING: Java 25 or JDK 25 is recommended. Set JAVA_HOME to a Java 25 installation if needed." >&2
fi

# Build backend and all modules with Maven
echo "Building Maven modules..."
mvn -DskipTests package

# Build UI
echo "Building UI (npm)..."
if [ -d ui ]; then
  (cd ui && npm ci && npm run build)
else
  echo "No ui/ directory found — skipping npm build" >&2
fi

# Start backend (detached)
BACKEND_LOG="$BASE_DIR/run/backend.log"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
if [ -f "$BACKEND_PID_FILE" ] && kill -0 "$(cat $BACKEND_PID_FILE)" 2>/dev/null; then
  echo "Backend appears to be already running (pid $(cat $BACKEND_PID_FILE))." 
else
  echo "Starting backend (Spring Boot) — logs: $BACKEND_LOG"
  nohup mvn -pl backend spring-boot:run >"$BACKEND_LOG" 2>&1 &
  echo $! > "$BACKEND_PID_FILE"
  echo "Backend started, pid: $(cat $BACKEND_PID_FILE)"
fi

# Build sample jar is already created by Maven; start sample app with agent
AGENT_JAR=$(ls agent/target/*.jar 2>/dev/null | head -n1 || true)
SAMPLE_JAR=$(ls sample-app/target/*jar-with-dependencies.jar 2>/dev/null | head -n1 || true)

if [ -z "$AGENT_JAR" ] || [ -z "$SAMPLE_JAR" ]; then
  echo "Agent or Sample JAR not found — ensure 'mvn -DskipTests package' completed without errors" >&2
  exit 1
fi

SAMPLE_LOG="$BASE_DIR/run/sample.log"
SAMPLE_PID_FILE="$RUN_DIR/sample.pid"

if [ -f "$SAMPLE_PID_FILE" ] && kill -0 "$(cat $SAMPLE_PID_FILE)" 2>/dev/null; then
  echo "Sample app appears to be already running (pid $(cat $SAMPLE_PID_FILE))."
else
  echo "Starting sample app with JVMDoctor agent (AGENT=$AGENT_JAR) — logs: $SAMPLE_LOG"
  nohup java -javaagent:"$AGENT_JAR"=9404 -jar "$SAMPLE_JAR" >"$SAMPLE_LOG" 2>&1 &
  echo $! > "$SAMPLE_PID_FILE"
  echo "Sample started, pid: $(cat $SAMPLE_PID_FILE)"
fi

# Final status
echo "Startup complete"
echo "Backend UI -> http://localhost:8080"
echo "Sample metrics -> http://localhost:9404/metrics"

echo "To stop: ./scripts/stop.sh"
