package com.jvmdoctor.backend;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskManager {
    private static final ExecutorService executor = new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50), new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicLong idGen = new AtomicLong(1);
    private static final Map<Long, Future<?>> tasks = new ConcurrentHashMap<>();

    public static long submit(Runnable task) {
        long id = idGen.getAndIncrement();
        Future<?> f = executor.submit(task);
        tasks.put(id, f);
        return id;
    }

    public static int getActiveCount() {
        int c = 0;
        for (Future<?> f : tasks.values()) if (!f.isDone()) c++;
        return c;
    }

    public static Map<Long, Future<?>> list() {
        return tasks;
    }

    public static boolean cancel(long id) {
        Future<?> f = tasks.remove(id);
        if (f == null) return false;
        return f.cancel(true);
    }
}
