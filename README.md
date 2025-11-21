# JVMDoctor — Java monitoring PoC

This repository holds a Proof-of-Concept for a Java monitoring app (agent + sample app) with Prometheus-compatible metrics.

## What you get in this scaffold
- `agent/` — a Java agent that uses Prometheus `simpleclient` to export JMX and custom metrics on a small HTTP server.
- `sample-app/` — a tiny app you can run to exercise the agent.

## Goals
- Collect JVM metrics (GC, memory, threads) via `simpleclient_hotspot` and expose via `/metrics` in Prometheus format.
- Provide an agent that attaches at JVM startup (premain) or at runtime (agentmain) and starts a metrics HTTP server.
- Make it easy to integrate with Prometheus and Grafana for visualization.

## Quick start
### Backend UI

You can run a small backend that serves a simple UI for launching jars and scraping metrics:

```bash
# Build
mvn -DskipTests package

# Run backend (Spring Boot)
mvn -pl backend spring-boot:run
```

Open http://localhost:8080 in your browser to see a demo UI that can start jars with agent args and fetch `/metrics` from the agent.

### React UI (Chart & logs)

There is a React-based UI in `ui/` which provides metrics charts (Chart.js), log viewer (SSE), and the ability to attach to running JVMs.

Build and copy the UI into the backend static folder:

```bash
cd ui
npm install
npm run build
Notes about the UI:
- UI uses CoreUI React for layout and Chart.js for time series charts. CoreUI provides a flexible dashboard layout and theme-ready components. You can customize pages using CoreUI components: https://coreui.io/react/.
-- The log viewer now supports regex filters and case-insensitive searches. Use the Checkboxes in the UI to toggle regex and case sensitivity.
 
Front-end features
- React UI uses Material-UI for layout and a responsive dashboard.
- Improved log viewer with regex option and case-sensitivity toggle (server-side regex support).
- Process history is available via the UI per process (start/stop timestamps and command).
- When a JFR or profiler run finishes the backend returns a file path that is downloadable using the UI's 'Download' link; SVG profiler output (async-profiler) is displayed inline.
```

Now open http://localhost:8080 to use the React UI. The UI also supports attaching the agent to local JVMs and toggling the sample metric via JMX.

JFR & Profiler
-------------
The UI now has controls to start/stop a JFR recording for a process (uses the Agent MBean) and to run async-profiler (optional) if you set `ASYNC_PROFILER_HOME` to a local async-profiler checkout.

Usage notes:
- Start JFR from UI -> Stop JFR to dump to a JFR file (saved to the path you specify on the server).
- Run async-profiler from the UI if `ASYNC_PROFILER_HOME` is present on the machine where the backend runs. The backend will call `$ASYNC_PROFILER_HOME/profiler.sh -d <sec> -e <event> -o <fmt> -f <file> <pid>` and return the file path.

Security & warnings: JFR controls and profiler invocation will run on the server and may expose sensitive data; do not enable in a multi-tenant environment without proper controls.
Security note: The backend can execute arbitrary commands — do not expose it to untrusted networks without authentication or sandboxing.

Sandbox & quotas
- The backend enforces the following default limits and sandboxing options (configurable by environment variables):
  - `MAX_CONCURRENT_PROFILER_TASKS` — maximum number of concurrent profiler jobs (defaults to 2)
  - `MAX_PROCESSES` — maximum spawned processes tracked by the backend (defaults to 20)
  - `MAX_XMX_MB` — maximum allowed -Xmx for spawned processes (defaults to 1024 MB)
  - File downloads are restricted to `/tmp` by default (change `FileController.ALLOWED_PREFIX` in code if needed)

These are intentionally conservative defaults; you can change them via environment variables when starting the backend.

Build the whole project with Maven (requires Java 25):

```bash
mvn -DskipTests package

Verify you are using Java 25 (you need a JDK 25 installed and set in JAVA_HOME):

```bash
java -version
mvn -v
```
```

Run `sample-app` with the agent attached and scrape metrics at http://localhost:9404/metrics (packaged by Maven):

```bash
# Build
mvn -DskipTests package

# Example (attach at startup with premain)
AGENT_JAR=$(ls agent/target/*.jar | head -n 1)
SAMPLE_JAR=$(ls sample-app/target/*jar-with-dependencies.jar | head -n 1)

java -javaagent:$AGENT_JAR=9404 -jar $SAMPLE_JAR
```

If you use Prometheus, add this to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'jvmdoctor'
    static_configs:
      - targets: ['localhost:9404']
```

## Next steps (MVP->1.0)
1. Add JMX-based targeted collectors per framework (Tomcat, Netty, Spring Boot metrics mapping)
2. Build a lightweight backend for log/trace correlation and long-term metric storage
3. Provide a customizable dashboard and alerting presets
4. Demo: Start a JAR from a UI and monitor it (see `backend/` module)
5. K8s/Scale out: use `k8s-daemonset.yaml` and `Dockerfile.agent` to deploy the agent to each node. Agents can be configured with the `CENTRAL_BACKEND_URL` environment variable — the backend exposes `/api/metrics/push` to accept plain Prometheus metric text.

## Files to look at
- `agent/src/main/java/com/jvmdoctor/Agent.java` — Java agent and sample collector
- `sample-app/src/main/java/com/jvmdoctor/SampleApp.java` — tiny app to exercise the agent
 - `Dockerfile.agent` — container recipe for the agent
 - `k8s-daemonset.yaml` — example DaemonSet to run an agent on every node and push metrics to the central backend

License: MIT
# JVMDoctor
