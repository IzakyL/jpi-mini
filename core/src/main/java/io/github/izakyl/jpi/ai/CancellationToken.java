package io.github.izakyl.jpi.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Cooperative cancellation; callbacks also close/cancel the active HTTP exchange. */
public final class CancellationToken {

    private boolean cancelled;
    private final List<Runnable> callbacks = new ArrayList<>();

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pending = List.copyOf(callbacks);
            callbacks.clear();
        }
        pending.forEach(CancellationToken::invoke);
    }

    public Subscription onCancel(Runnable callback) {
        synchronized (this) {
            if (!cancelled) {
                callbacks.add(callback);
                return () -> {
                    synchronized (this) {
                        callbacks.remove(callback);
                    }
                };
            }
        }
        invoke(callback);
        return () -> {};
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Request was aborted");
        }
    }

    private static void invoke(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Cancellation must reach every subscriber.
        }
    }
}
