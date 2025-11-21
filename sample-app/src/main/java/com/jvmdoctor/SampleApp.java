package com.jvmdoctor;

public class SampleApp {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting sample app — press Ctrl+C to stop");
        while (true) {
            busyWork();
            Thread.sleep(1000);
        }
    }

    private static void busyWork() {
        double x = 0;
        for (int i = 0; i < 100_000; i++) {
            x += Math.sin(i);
        }
        if (x > 1e9) { // a no-op conditional for demo
            System.out.println("impossible");
        }
    }
}
