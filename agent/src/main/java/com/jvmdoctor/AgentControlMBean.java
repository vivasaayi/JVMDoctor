package com.jvmdoctor;

public interface AgentControlMBean {
    boolean isSampleEnabled();
    void setSampleEnabled(boolean enabled);
    // JFR control
    void startJfr(String name, long maxAgeMillis);
    String stopAndDumpJfr(String filename);
    // Heap dump
    String takeHeapDump(String filename, boolean live);
    // GC logging
    void enableGcLogging(boolean on, String filename);
    // Load native profiler library into the VM (optional)
    boolean loadNativeAgent(String path);
}
