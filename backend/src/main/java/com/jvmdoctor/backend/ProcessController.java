package com.jvmdoctor.backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

@RestController
@RequestMapping("/api/processes")
public class ProcessController {
    private final HttpClient client = HttpClient.newHttpClient();

    @CrossOrigin(origins = "*")
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> cfg) {
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

        if (jarPath == null) return ResponseEntity.badRequest().body(Map.of("error", "jarPath is required"));

        try {
            
            var mp = ProcessManager.startProcess(jarPath, agentPort, args, agentJar);
            return ResponseEntity.created(URI.create("/api/processes/" + mp.id)).body(Map.of("id", mp.id));
        } catch (IOException e) {
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
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        var mp = list.get(0);

        String url = "http://localhost:" + mp.port + "/metrics";
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> resp = client.send(r, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.ok(resp.body());
        } catch (Exception e) {
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
        var list = ProcessManager.listProcesses().stream().filter(mp -> mp.id == id).collect(Collectors.toList());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        var mp = list.get(0);
        String target = cfg.containsKey("filename") ? (String) cfg.get("filename") : "/tmp/heapdump-"+mp.pid+".hprof";
        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(mp.pid));
            vm.startLocalManagementAgent();
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            JMXConnector conn = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();
            ObjectName name = new ObjectName("com.jvmdoctor:type=AgentControl");
            String returned = (String) mbsc.invoke(name, "takeHeapDump", new Object[]{target, Boolean.TRUE}, new String[]{"java.lang.String","boolean"});
            conn.close();
            vm.detach();
            return ResponseEntity.ok(Map.of("path", returned));
        } catch (Exception e) {
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
        String agentJar = (String) cfg.get("agentJar");
        String agentArgs = cfg.containsKey("agentArgs") ? (String) cfg.get("agentArgs") : null;
        if (agentJar == null) return ResponseEntity.badRequest().body(Map.of("error","agentJar required"));
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.loadAgent(agentJar, agentArgs == null ? "" : agentArgs);
            vm.detach();
            return ResponseEntity.ok(Map.of("attached", true));
        } catch (Exception e) {
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


}


