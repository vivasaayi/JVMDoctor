package com.jvmdoctor.backend;

import java.util.List;

public class ProcessHistory {
    public final long id;
    public final String jar;
    public final long pid;
    public final long startTime;
    public long stopTime;
    public final List<String> cmd;

    public ProcessHistory(long id, String jar, long pid, long startTime, List<String> cmd) {
        this.id = id;
        this.jar = jar;
        this.pid = pid;
        this.startTime = startTime;
        this.cmd = cmd;
        this.stopTime = -1L;
    }

    public void setStopTime(long t) { this.stopTime = t; }
}
