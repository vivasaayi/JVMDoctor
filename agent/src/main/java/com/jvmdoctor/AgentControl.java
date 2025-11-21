package com.jvmdoctor;

import jdk.jfr.Recording;
import java.time.Duration;

public class AgentControl implements AgentControlMBean {
    private static volatile boolean sampleEnabled = true;

    @Override
    public boolean isSampleEnabled() {
        return sampleEnabled;
    }

    @Override
    public void setSampleEnabled(boolean enabled) {
        sampleEnabled = enabled;
    }

    // JFR support
    private static Recording recording;

    @Override
    public void startJfr(String name, long maxAgeMillis) {
        if (recording != null) {
            recording.stop();
        }
        recording = new Recording();
        if (name != null) recording.setName(name);
        if (maxAgeMillis > 0) {
            recording.setToDisk(true);
            recording.setMaxAge(Duration.ofMillis(maxAgeMillis));
        }
        recording.start();
    }

    @Override
    public String stopAndDumpJfr(String filename) {
        if (recording == null) return null;
        try {
            recording.stop();
            var path = java.nio.file.Paths.get(filename);
            recording.dump(path);
            recording.close();
            recording = null;
            return path.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String takeHeapDump(String filename, boolean live) {
        try {
            com.sun.management.HotSpotDiagnosticMXBean mx = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            mx.dumpHeap(filename, live);
            return filename;
        } catch (Exception e) {
            return null;
        }
    }

    // GC logging
    private static java.io.PrintWriter gcWriter;
    private static final java.util.List<javax.management.NotificationEmitter> gcEmitters = new java.util.ArrayList<>();

    @Override
    public void enableGcLogging(boolean on, String filename) {
        try {
            if (on) {
                if (gcWriter == null) gcWriter = new java.io.PrintWriter(new java.io.FileWriter(filename, true));
                java.lang.management.GarbageCollectorMXBean gc = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().get(0);
                // add listener to all collectors
                for (java.lang.management.GarbageCollectorMXBean g : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                    if (g instanceof javax.management.NotificationEmitter) {
                        javax.management.NotificationEmitter emitter = (javax.management.NotificationEmitter) g;
                        javax.management.NotificationListener listener = (notification, handback) -> {
                            gcWriter.println(notification.getType() + " " + notification.getMessage());
                            gcWriter.flush();
                        };
                        emitter.addNotificationListener(listener, null, null);
                        gcEmitters.add(emitter);
                    }
                }
            } else {
                if (gcWriter != null) gcWriter.close();
                // can't easily remove anonymous listeners here; for demo we close the writer and clear emitters
                gcEmitters.clear();
                gcWriter = null;
            }
        } catch (Exception e) {
            // ignore for PoC
        }
    }

    @Override
    public boolean loadNativeAgent(String path) {
        try {
            System.load(path);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // for static access from the agent loop
    public static boolean getSampleEnabled() {
        return sampleEnabled;
    }
}
