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

    private static void pushMetricsToCentral(String centralUrl, int localPort) throws Exception {
        // Fetch local metrics
        java.net.URL url = java.net.URI.create("http://localhost:" + localPort + "/metrics").toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder metrics = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                metrics.append(line).append("\n");
            }
            reader.close();

            // Send to central backend
            java.net.URL central = java.net.URI.create(centralUrl + "/api/metrics/push").toURL();
            java.net.HttpURLConnection centralConn = (java.net.HttpURLConnection) central.openConnection();
            centralConn.setRequestMethod("POST");
            centralConn.setRequestProperty("Content-Type", "text/plain");
            centralConn.setDoOutput(true);
            java.io.OutputStream os = centralConn.getOutputStream();
            os.write(metrics.toString().getBytes());
            os.flush();
            os.close();
            int centralResponse = centralConn.getResponseCode();
            if (centralResponse != 200) {
                throw new Exception("Central backend returned " + centralResponse);
            }
        } else {
            throw new Exception("Failed to fetch local metrics: " + responseCode);
        }
    }

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

            // Start metrics collection thread
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

            // Start metrics push thread to central backend
            String centralUrl = System.getenv("CENTRAL_BACKEND_URL");
            final int metricsPort = port; // Make it final for lambda
            if (centralUrl != null && !centralUrl.isEmpty()) {
                Thread pushThread = new Thread(() -> {
                    while (true) {
                        try {
                            pushMetricsToCentral(centralUrl, metricsPort);
                            Thread.sleep(5000); // Push every 5 seconds
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            System.err.println("Failed to push metrics: " + e.getMessage());
                            try {
                                Thread.sleep(10000); // Retry after 10 seconds on error
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }, "jvmdoctor-push-thread");
                pushThread.setDaemon(true);
                pushThread.start();
            }

            System.out.println("JVMDoctor agent started, metrics available at http://localhost:" + port + "/metrics");
        } catch (IOException e) {
            System.err.println("Failed to start JVMDoctor metrics server: " + e.getMessage());
        }
    }
}
