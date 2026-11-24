package io.github.izakyl.jpi.ai.internal;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

public final class Workers {

    private static final AtomicLong NEXT_ID = new AtomicLong();

    private static final ExecutorService IO =
            Executors.newCachedThreadPool(task -> {
                var thread = new Thread(task, "jpi-mini-worker-" + NEXT_ID.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            });

    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    public static Executor executor() {
        return IO::execute;
    }

    public static ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return WATCHDOG.schedule(task, delay, unit);
    }

    private static ScheduledThreadPoolExecutor watchdog() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "jpi-mini-watchdog-" + NEXT_ID.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private Workers() {
    }
}
