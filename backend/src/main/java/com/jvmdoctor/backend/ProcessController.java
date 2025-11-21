package com.jvmdoctor.backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import javax.management.*;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;

@RestController
@RequestMapping("/api/processes")
public class ProcessController {
    private static final Logger logger = LoggerFactory.getLogger(ProcessController.class);
    private final HttpClient client = HttpClient.newHttpClient();
    private static final Pattern HISTOGRAM_LINE = Pattern.compile("^(\\d+):\\s+(\\d+)\\s+(\\d+)\\s+(.+)$");

    @CrossOrigin(origins = "*")
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> cfg) {
        logger.info("Starting new managed process");
        String jarPath = (String) cfg.get("jarPath");
        int agentPort = 9404;
        if (cfg.containsKey("agentPort")) {
            Object portObj = cfg.get("agentPort");
            if (portObj instanceof Number) {
                agentPort = ((Number) portObj).intValue();
            } else {
                try {
                    agentPort = Integer.parseInt(portObj.toString());
                } catch (Exception ignore) {}
            }
        }
        String agentJar = (String) cfg.get("agentJar");
        List<String> args = cfg.containsKey("args") ? (List<String>) cfg.get("args") : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> envVarsList = cfg.containsKey("envVars") ? (List<Map<String, String>>) cfg.get("envVars") : List.of();
        Map<String, String> envVars = envVarsList.stream().collect(Collectors.toMap(m -> m.get("key"), m -> m.get("value")));

        if (jarPath == null) {
            logger.warn("jarPath not provided in start request");
            return ResponseEntity.badRequest().body(Map.of("error", "jarPath is required"));
        }
        Path jarFile = Paths.get(jarPath);
        if (!Files.exists(jarFile)) {
            logger.error("JAR file does not exist: {}", jarPath);
            return ResponseEntity.badRequest().body(Map.of("error", "JAR file not found: " + jarPath));
        }
        jarPath = jarFile.toAbsolutePath().toString(); // Use absolute path
        logger.info("Resolved JAR path to: {}", jarPath);
        logger.info("Starting process: jar={}, agentPort={}, agentJar={}, args={}, envVars={}", jarPath, agentPort, agentJar, args, envVars.keySet());

        try {
            
            var mp = ProcessManager.startProcess(jarPath, agentPort, args, agentJar, envVars);
            logger.info("Process started successfully: id={}, pid={}", mp.id, mp.pid);
            return ResponseEntity.created(URI.create("/api/processes/" + mp.id)).body(Map.of("id", mp.id));
        } catch (IOException e) {
            logger.error("Failed to start process: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return ProcessManager.listProcesses().stream().map(mp -> Map.of(
            "id", mp.id,
            "jar", mp.jarPath,
            "pid", mp.pid,
            "port", mp.port,
            "cmd", mp.args
        )).collect(Collectors.toList());
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable("id") long id) {
        boolean ok = ProcessManager.stopProcess(id);
        return ok ? ResponseEntity.ok(Map.of()) : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/metrics")
    public ResponseEntity<?> metrics(@PathVariable("id") long id) {
        logger.debug("Metrics requested for process id: {}", id);
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) {
            logger.warn("Process with id {} not found for metrics", id);
            return ResponseEntity.notFound().build();
        }
        var mp = list.get(0);

        String url = "http://localhost:" + mp.port + "/metrics";
        logger.debug("Fetching metrics from agent at: {}", url);
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> resp = client.send(r, HttpResponse.BodyHandlers.ofString());
            logger.debug("Metrics response status: {}, length: {}", resp.statusCode(), resp.body().length());
            return ResponseEntity.ok(resp.body());
        } catch (Exception e) {
            logger.error("Failed to fetch metrics for process {} from {}: {}", id, url, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<?> logs(@PathVariable("id") long id,
                                  @RequestParam(value = "q", required = false) String q,
                                  @RequestParam(value = "regex", required = false) Boolean regex,
                                  @RequestParam(value = "ignoreCase", required = false) Boolean ignoreCase,
                                  @RequestParam(value = "limit", required = false) Integer limit) {
        int max = limit == null ? 200 : limit;
        if (regex != null && regex.booleanValue() && q != null) {
            boolean ic = ignoreCase != null && ignoreCase.booleanValue();
            var lines = ProcessManager.queryLogsRegex(id, q, ic, max);
            return ResponseEntity.ok(Map.of("lines", lines));
        } else {
            var lines = ProcessManager.queryLogs(id, q, max);
            return ResponseEntity.ok(Map.of("lines", lines));
        }
    }
    

    @GetMapping("/{id}/logs/stream")
    public SseEmitter streamLogs(@PathVariable("id") long id) {
        SseEmitter emitter = ProcessManager.registerLogEmitter(id);
        if (emitter == null) return null;
        // send existing buffer first
        var buf = ProcessManager.getLogBuffer(id);
        if (buf != null) {
            try { emitter.send(SseEmitter.event().data(String.join("\n", buf))); } catch (Exception ignore) {}
        }
        return emitter;
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<?> toggleFeature(@PathVariable("id") long id, @RequestBody Map<String,Object> cfg) {
        boolean enable = cfg.containsKey("enable") && Boolean.parseBoolean(cfg.get("enable").toString());

        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        var mp = list.get(0);
        // try to connect via JMX by using Attach API
        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(mp.pid));
            // start local management agent if necessary
            vm.startLocalManagementAgent();
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            JMXConnector conn = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();
            ObjectName name = new ObjectName("com.jvmdoctor:type=AgentControl");
            mbsc.setAttribute(name, new Attribute("SampleEnabled", enable));
            conn.close();
            vm.detach();
            return ResponseEntity.ok(Map.of("enabled", enable));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/jfr/start")
    public ResponseEntity<?> startJfr(@PathVariable("id") long id, @RequestBody Map<String,Object> cfg) {
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        var mp = list.get(0);
        boolean ok = false;
        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(mp.pid));
            vm.startLocalManagementAgent();
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            JMXConnector conn = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();
            ObjectName name = new ObjectName("com.jvmdoctor:type=AgentControl");
            String filename = cfg.containsKey("name") ? (String)cfg.get("name") : "recording";
            long maxAge = cfg.containsKey("maxAgeMillis") ? ((Number)cfg.get("maxAgeMillis")).longValue() : 0L;
            mbsc.invoke(name, "startJfr", new Object[]{filename, maxAge}, new String[]{"java.lang.String","long"});
            conn.close();
            vm.detach();
            ok = true;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
        return ok ? ResponseEntity.ok(Map.of("started", true)) : ResponseEntity.internalServerError().build();
    }

    @PostMapping("/{id}/jfr/stop")
    public ResponseEntity<?> stopJfrAndFetch(@PathVariable("id") long id, @RequestBody Map<String,Object> cfg) {
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        var mp = list.get(0);
        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(mp.pid));
            vm.startLocalManagementAgent();
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            JMXConnector conn = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();
            ObjectName name = new ObjectName("com.jvmdoctor:type=AgentControl");
            String target = cfg.containsKey("filename") ? (String) cfg.get("filename") : "dump.jfr";
            String returned = (String) mbsc.invoke(name, "stopAndDumpJfr", new Object[]{target}, new String[]{"java.lang.String"});
            conn.close();
            vm.detach();
            // return the path to the recording
            return ResponseEntity.ok(Map.of("path", returned));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/heapdump")
    public ResponseEntity<?> heapDump(@PathVariable("id") long id, @RequestBody Map<String,Object> cfg) {
        logger.info("Heap dump requested for process id: {}", id);
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) {
            logger.warn("Process with id {} not found", id);
            return ResponseEntity.notFound().build();
        }
        var mp = list.get(0);
        String target = cfg.containsKey("filename") ? (String) cfg.get("filename") : "/tmp/heapdump-"+mp.pid+".hprof";
        logger.info("Target heap dump file: {}", target);
        try {
            logger.debug("Attempting to attach to JVM with PID: {}", mp.pid);
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(mp.pid));
            vm.startLocalManagementAgent();
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            logger.debug("JMX connector address: {}", connectorAddress);
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            JMXConnector conn = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();
            ObjectName name = new ObjectName("com.jvmdoctor:type=AgentControl");
            logger.debug("Invoking takeHeapDump on agent");
            String returned = (String) mbsc.invoke(name, "takeHeapDump", new Object[]{target, Boolean.TRUE}, new String[]{"java.lang.String","boolean"});
            logger.info("Agent returned heap dump path: {}", returned);
            conn.close();
            vm.detach();
            Path path = Paths.get(returned);
            if (Files.exists(path)) {
                logger.info("Heap dump file verified to exist: {}", returned);
                return ResponseEntity.ok(Map.of("status", "success", "path", returned));
            } else {
                logger.error("Heap dump file not found after creation: {}", returned);
                return ResponseEntity.status(500).body(Map.of("status", "error", "message", "Heap dump file was not created"));
            }
        } catch (com.sun.tools.attach.AttachNotSupportedException e) {
            logger.error("Attach not supported for PID {}: {}", mp.pid, e.getMessage());
            // Attach is not supported for this pid — explain why and give guidance.
            String msg = e.getMessage() == null ? "attach not supported" : e.getMessage();
            String suggestion = "The target JVM doesn't allow attach from this process. Ensure you run JVMDoctor as the same user as the target process, or restart the target process with a management agent or debug options (e.g. enable JMX or run with tools that allow dynamic attach).";
            return ResponseEntity.status(412).body(Map.of("error", msg, "hint", suggestion, "pid", mp.pid));
        } catch (IllegalStateException e) {
            logger.error("Attach handshake failed for PID {}: {}", mp.pid, e.getMessage());
            // common attach-handshake errors like "state is not ready to participate..."
            String msg = e.getMessage();
            String suggestion = "Attach handshake failed — the target JVM may be starting up or disallowing attach. Try again after the target process is fully started, or launch the process under JVMDoctor as a managed process so attach works.";
            return ResponseEntity.status(409).body(Map.of("error", msg, "hint", suggestion, "pid", mp.pid));
        } catch (Exception e) {
            logger.error("Unexpected error during heap dump for PID {}: {}", mp.pid, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/heap/download")
    public ResponseEntity<?> downloadHeapDump(@PathVariable("id") long id, @RequestParam("path") String path) {
        logger.info("Heap dump download requested for process id: {}, path: {}", id, path);
        try {
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath)) {
                logger.warn("Heap dump file not found: {}", path);
                return ResponseEntity.notFound().build();
            }
            logger.info("Serving heap dump file: {}", path);
            Resource resource = new FileSystemResource(filePath);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filePath.getFileName() + "\"")
                .body(resource);
        } catch (Exception e) {
            logger.error("Error serving heap dump file {}: {}", path, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/heap/histogram")
    public ResponseEntity<?> heapHistogram(@PathVariable("id") long id,
                                           @RequestParam(value = "limit", required = false, defaultValue = "25") int limit) {
        logger.info("Heap histogram requested for process id: {}, limit: {}", id, limit);
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) {
            logger.warn("Process with id {} not found", id);
            return ResponseEntity.notFound().build();
        }
        var mp = list.get(0);
        int maxRows = Math.max(1, Math.min(200, limit));
        logger.debug("Max rows for histogram: {}", maxRows);
        try {
            List<Map<String,Object>> rows = collectHeapHistogram(mp.pid, maxRows);
            logger.info("Heap histogram collected {} entries for PID {}", rows.size(), mp.pid);
            return ResponseEntity.ok(Map.of("entries", rows));
        } catch (com.sun.tools.attach.AttachNotSupportedException e) {
            logger.error("Attach not supported for PID {}: {}", mp.pid, e.getMessage());
            String msg = e.getMessage() == null ? "attach not supported" : e.getMessage();
            String hint = "Attach not supported; run JVMDoctor as same user as target process, or use managed launch.";
            return ResponseEntity.status(412).body(Map.of("error", msg, "hint", hint, "pid", mp.pid));
        } catch (IllegalStateException e) {
            logger.error("Attach handshake failed for PID {}: {}", mp.pid, e.getMessage());
            String msg = e.getMessage();
            String hint = "Target is not ready for attach; retry after it fully starts or use managed launch.";
            return ResponseEntity.status(409).body(Map.of("error", msg, "hint", hint, "pid", mp.pid));
        } catch (Exception e) {
            logger.error("Unexpected error during heap histogram for PID {}: {}", mp.pid, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/gc/log")
    public ResponseEntity<?> gcLog(@PathVariable("id") long id, @RequestBody Map<String,Object> cfg) {
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        var mp = list.get(0);
        boolean on = cfg.containsKey("on") ? Boolean.parseBoolean(cfg.get("on").toString()) : true;
        String filename = cfg.containsKey("filename") ? (String) cfg.get("filename") : "/tmp/gc-"+mp.pid+".log";
        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(mp.pid));
            vm.startLocalManagementAgent();
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            JMXConnector conn = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();
            ObjectName name = new ObjectName("com.jvmdoctor:type=AgentControl");
            mbsc.invoke(name, "enableGcLogging", new Object[]{on, filename}, new String[]{"boolean","java.lang.String"});
            conn.close();
            vm.detach();
            return ResponseEntity.ok(Map.of("gcLogging", on, "path", filename));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(@PathVariable("id") long id) {
        var h = ProcessManager.getProcessHistory(id);
        if (h == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", h.id, "pid", h.pid, "jar", h.jar, "startTime", h.startTime, "stopTime", h.stopTime, "cmd", h.cmd));
    }

    @GetMapping("/history")
    public ResponseEntity<?> allHistory() {
        return ResponseEntity.ok(ProcessManager.listProcesses().stream().map(mp -> ProcessManager.getProcessHistory(mp.id)).collect(Collectors.toList()));
    }

    @GetMapping("/jvms")
    public List<Map<String,String>> listLocalJvms() {
        // VirtualMachineDescriptor id() and displayName() are strings — return Map<String,String>
        return VirtualMachine.list().stream().map(d -> Map.of("id", d.id(), "displayName", d.displayName())).collect(Collectors.toList());
    }

    @PostMapping("/jvms/{pid}/attach")
    public ResponseEntity<?> attachAgentToJvm(@PathVariable("pid") String pid, @RequestBody Map<String,Object> cfg) {
        logger.info("Attaching agent to JVM PID: {}", pid);
        String agentJar = (String) cfg.get("agentJar");
        String agentArgs = cfg.containsKey("agentArgs") ? (String) cfg.get("agentArgs") : null;
        if (agentJar == null) {
            logger.warn("Agent jar not provided for PID {}", pid);
            return ResponseEntity.badRequest().body(Map.of("error","agentJar required"));
        }
        logger.debug("Agent jar: {}, args: {}", agentJar, agentArgs);
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.loadAgent(agentJar, agentArgs == null ? "" : agentArgs);
            vm.detach();
            logger.info("Agent attached successfully to PID {}", pid);
            return ResponseEntity.ok(Map.of("attached", true));
        } catch (Exception e) {
            logger.error("Failed to attach agent to PID {}: {}", pid, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/native/load")
    public ResponseEntity<?> loadNative(@PathVariable("id") long id, @RequestBody Map<String,Object> cfg) {
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        var mp = list.get(0);
        String path = (String) cfg.get("path");
        if (path == null) return ResponseEntity.badRequest().body(Map.of("error","path required"));
        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(mp.pid));
            vm.startLocalManagementAgent();
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            JMXConnector conn = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();
            ObjectName name = new ObjectName("com.jvmdoctor:type=AgentControl");
            Boolean ok = (Boolean) mbsc.invoke(name, "loadNativeAgent", new Object[]{path}, new String[]{"java.lang.String"});
            conn.close();
            vm.detach();
            return ResponseEntity.ok(Map.of("loaded", ok));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/profiler/run")
    public ResponseEntity<?> runAsyncProfiler(@PathVariable("id") long id, @RequestBody Map<String,Object> cfg) {
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        var mp = list.get(0);

        // find profiler script
        String asyncHome = System.getenv("ASYNC_PROFILER_HOME");
        String script = null;
        if (asyncHome != null) {
            script = asyncHome + "/profiler.sh";
        }
        if (script == null) return ResponseEntity.status(500).body(Map.of("error","async-profiler not found; set ASYNC_PROFILER_HOME"));

        int duration = cfg.containsKey("duration") ? ((Number)cfg.get("duration")).intValue() : 10;
        String event = cfg.containsKey("event") ? (String) cfg.get("event") : "cpu";
        String output = cfg.containsKey("output") ? (String) cfg.get("output") : "svg";
        String filename = cfg.containsKey("filename") ? (String) cfg.get("filename") : "/tmp/profile-"+mp.pid+"."+output;

        List<String> cmd = List.of(script, "-d", String.valueOf(duration), "-e", event, "-o", output, "-f", filename, String.valueOf(mp.pid));
        try {
            long tid = TaskManager.submit(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    Process proc = pb.start();
                    proc.waitFor();
                } catch (Exception ignore){}
            });
            return ResponseEntity.accepted().body(Map.of("taskId", tid, "path", filename));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private List<Map<String,Object>> collectHeapHistogram(long pid, int maxRows) throws Exception {
        logger.debug("Collecting heap histogram for PID: {}, maxRows: {}", pid, maxRows);
        VirtualMachine vm = null;
        JMXConnector conn = null;
        try {
            vm = VirtualMachine.attach(String.valueOf(pid));
            vm.startLocalManagementAgent();
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            if (connectorAddress == null) {
                logger.error("Management agent unavailable for PID {}", pid);
                throw new IOException("Management agent unavailable for pid " + pid);
            }
            logger.debug("JMX connector address for histogram: {}", connectorAddress);
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            conn = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();
            ObjectName diag = new ObjectName("com.sun.management:type=DiagnosticCommand");
            String[] signature = new String[]{String[].class.getName()};
            Object[] params = new Object[]{new String[0]};
            logger.debug("Invoking gcClassHistogram for PID {}", pid);
            String histogram = (String) mbsc.invoke(diag, "gcClassHistogram", params, signature);
            logger.debug("Histogram data length: {}", histogram != null ? histogram.length() : 0);
            return parseHistogramText(histogram, maxRows);
        } finally {
            if (conn != null) {
                try { conn.close(); logger.debug("JMX connection closed for PID {}", pid); } catch (Exception ignore) {}
            }
            if (vm != null) {
                try { vm.detach(); logger.debug("Detached from VM PID {}", pid); } catch (Exception ignore) {}
            }
        }
    }

    private List<Map<String,Object>> parseHistogramText(String histogram, int maxRows) {
        List<Map<String,Object>> rows = new ArrayList<>();
        if (histogram == null) {
            return rows;
        }
        String[] lines = histogram.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            Matcher matcher = HISTOGRAM_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            rows.add(Map.of(
                "rank", Integer.parseInt(matcher.group(1)),
                "instances", Long.parseLong(matcher.group(2)),
                "bytes", Long.parseLong(matcher.group(3)),
                "className", matcher.group(4)
            ));
            if (rows.size() >= maxRows) {
                break;
            }
        }
        return rows;
    }
}


