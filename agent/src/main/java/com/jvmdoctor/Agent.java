package com.jvmdoctor;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.hotspot.DefaultExports;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.Gauge;
import java.lang.instrument.Instrumentation;
import java.io.IOException;

public class Agent {
    private static HTTPServer server;
    private static final Gauge sampleGauge = Gauge.build()
        .name("jvmdoctor_sample_metric")
        .help("A sample metric from JVMDoctor collector.")
        .register();
    private static final Gauge threadCountGauge = Gauge.build()
        .name("jvmdoctor_thread_count")
        .help("Number of live threads in the JVM.")
        .register();

    public static void premain(String agentArgs, Instrumentation inst) {
        start(agentArgs);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        start(agentArgs);
    }

    private static void start(String agentArgs) {
        try {
            // start default hotspot, memory, GC, thread metrics
            DefaultExports.register(CollectorRegistry.defaultRegistry);
            // register control MBean
            try {
                javax.management.ObjectName name = new javax.management.ObjectName("com.jvmdoctor:type=AgentControl");
                AgentControl control = new AgentControl();
                java.lang.management.ManagementFactory.getPlatformMBeanServer().registerMBean(control, name);
            } catch (Exception e) {
                System.err.println("Failed to register AgentControl MBean: " + e.getMessage());
            }

            int port = 9404; // default Prometheus metrics port
            if (agentArgs != null && agentArgs.length() > 0) {
                try {
                    port = Integer.parseInt(agentArgs);
                } catch (NumberFormatException e) {
                    // ignore and use default
                }
            }

            server = new HTTPServer(port);

            Thread t = new Thread(() -> {
                while (true) {
                    // A very simple sample collector to demonstrate custom metrics
                    double value = Math.random();
                    if (AgentControl.getSampleEnabled()) {
                        sampleGauge.set(value);
                    }
                    int threads = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
                    threadCountGauge.set(threads);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "jvmdoctor-metrics-thread");
            t.setDaemon(true);
            t.start();

            System.out.println("JVMDoctor agent started, metrics available at http://localhost:" + port + "/metrics");
        } catch (IOException e) {
            System.err.println("Failed to start JVMDoctor metrics server: " + e.getMessage());
        }
    }
}
