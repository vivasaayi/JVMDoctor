package com.jvmdoctor.backend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Deque;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class ProcessManager {
    private static final Map<Long, ManagedProcess> processes = new ConcurrentHashMap<>();
    private static final int MAX_PROCESSES = Integer.parseInt(System.getenv().getOrDefault("MAX_PROCESSES", "20"));
    // simple in-memory log tail storage for each process
    private static final Map<Long, Deque<String>> logBuffers = new ConcurrentHashMap<>();
    private static final Map<Long, CopyOnWriteArrayList<SseEmitter>> logEmitters = new ConcurrentHashMap<>();
    private static final Map<Long, ProcessHistory> history = new ConcurrentHashMap<>();
    private static final AtomicLong idGen = new AtomicLong(1);

    public static class ManagedProcess {
        public final long id;
        public final Process process;
        public final long pid;
        public final int port;
        public final String jarPath;
        public final List<String> args = new ArrayList<>();

        ManagedProcess(long id, Process process, int port, String jarPath, List<String> args) {
            this.id = id;
            this.process = process;
            this.pid = process.pid();
            this.port = port;
            this.jarPath = jarPath;
            this.args.addAll(args);
        }
    }

    public static ManagedProcess startProcess(String jarPath, int agentPort, List<String> extraArgs, String agentJar) throws IOException {
        if (processes.size() >= MAX_PROCESSES) {
            throw new IOException("max processes reached");
        }
        long id = idGen.getAndIncrement();
        List<String> cmd = new ArrayList<>();
        cmd.add("java");

        if (agentJar != null && !agentJar.isEmpty()) {
            cmd.add("-javaagent:" + agentJar + "=" + agentPort);
        }

        // enforce maximum heap size (Xmx) to protect the host. If not specified use default.
        for (String a : extraArgs) {
            if (a.startsWith("-Xmx") || a.startsWith("-Xms")) {
                try {
                    String val = a.substring(4).toLowerCase();
                    // parse number - support m/g suffix
                    int factor = 1;
                    if (val.endsWith("m")) { factor = 1; val = val.substring(0, val.length()-1); }
                    if (val.endsWith("g")) { factor = 1024; val = val.substring(0, val.length()-1); }
                    int size = Integer.parseInt(val);
                    int maxAllowed = Integer.parseInt(System.getenv().getOrDefault("MAX_XMX_MB", "1024"));
                    if (size * factor > maxAllowed) {
                        throw new IOException("Xmx too large: " + a);
                    }
                } catch (NumberFormatException e) {
                    // ignore - we only enforce numeric values
                }
            }
        }

        if (extraArgs != null) cmd.addAll(extraArgs);

        cmd.add("-jar");
        cmd.add(jarPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        ManagedProcess mp = new ManagedProcess(id, p, agentPort, jarPath, cmd);
        processes.put(id, mp);
        history.put(id, new ProcessHistory(id, jarPath, mp.pid, System.currentTimeMillis(), mp.args));
        logBuffers.put(id, new ConcurrentLinkedDeque<>());
        logEmitters.put(id, new CopyOnWriteArrayList<>());

        // consume output so child doesn't block
        new Thread(() -> {
            try (InputStream is = p.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[proc-" + id + "] " + line);
                    Deque<String> buffer = logBuffers.get(id);
                    if (buffer != null) {
                        buffer.addLast(line);
                        if (buffer.size() > 500) buffer.removeFirst();
                    }
                    // dispatch to emitters
                    CopyOnWriteArrayList<SseEmitter> emitters = logEmitters.get(id);
                    if (emitters != null) {
                        for (SseEmitter e : emitters) {
                            try { e.send(SseEmitter.event().data(line)); } catch (Exception ignore) {}
                        }
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }, "proc-output-" + id).start();

        return mp;
    }

    public static List<ManagedProcess> listProcesses() {
        return Collections.unmodifiableList(new ArrayList<>(processes.values()));
    }

    public static boolean stopProcess(long id) {
        ManagedProcess mp = processes.remove(id);
        if (mp == null) return false;
        mp.process.destroy();
        var h = history.get(id);
        if (h != null) h.setStopTime(System.currentTimeMillis());
        logBuffers.remove(id);
        CopyOnWriteArrayList<SseEmitter> emitters = logEmitters.remove(id);
        if (emitters != null) {
            for (SseEmitter e : emitters) {
                try { e.complete(); } catch (Exception ignore) {}
            }
        }
        return true;
    }

    public static Deque<String> getLogBuffer(long id) {
        return logBuffers.get(id);
    }

    public static ProcessHistory getProcessHistory(long id) { return history.get(id); }

    public static List<String> queryLogs(long id, String contains, int limit) {
        Deque<String> buf = logBuffers.get(id);
        if (buf == null) return List.of();
        List<String> filtered = buf.stream().filter(s -> contains == null || s.contains(contains)).collect(Collectors.toList());
        int start = Math.max(0, filtered.size() - limit);
        return filtered.subList(start, filtered.size());
    }
    
    public static List<String> queryLogsRegex(long id, String regex, boolean ignoreCase, int limit) {
        Deque<String> buf = logBuffers.get(id);
        if (buf == null) return List.of();
        int flags = ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE : 0;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex, flags);
        List<String> filtered = buf.stream().filter(s -> p.matcher(s).find()).collect(Collectors.toList());
        int start = Math.max(0, filtered.size() - limit);
        return filtered.subList(start, filtered.size());
    }

    public static SseEmitter registerLogEmitter(long id) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        var emitters = logEmitters.get(id);
        if (emitters == null) return null;
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }
}
