package io.github.izakyl.jpi.ai.internal;

import io.github.izakyl.jpi.ai.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Serial dispatch with replay, so subscribing after stream creation cannot miss events. */
public abstract class ListenerStream<E, R> {

    private final List<E> history = new ArrayList<>();
    private final List<Consumer<E>> listeners = new ArrayList<>();
    private final CompletableFuture<R> result = new CompletableFuture<>();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private boolean ended;

    public final Subscription subscribe(Consumer<E> listener) {
        Objects.requireNonNull(listener);
        var active = new AtomicBoolean(true);
        Consumer<E> guarded =
                event -> {
                    if (active.get()) {
                        listener.accept(event);
                    }
                };
        enqueue(
                () -> {
                    history.forEach(event -> notify(guarded, event));
                    if (!result.isDone() && active.get()) {
                        listeners.add(guarded);
                    }
                });
        return () -> {
            active.set(false);
            enqueue(() -> listeners.remove(guarded));
        };
    }

    public final CompletableFuture<R> result() {
        return result.copy();
    }

    protected final synchronized void emit(E event) {
        if (!ended) {
            enqueue(() -> dispatch(event));
        }
    }

    protected final synchronized CompletableFuture<Void> emitAndWait(E event) {
        if (!ended) {
            enqueue(() -> dispatch(event));
        }
        return tail.copy();
    }

    protected final synchronized void finish(E terminalEvent, R value) {
        if (ended) {
            return;
        }
        ended = true;
        enqueue(
                () -> {
                    dispatch(terminalEvent);
                    listeners.clear();
                    result.complete(value);
                });
    }

    private synchronized void enqueue(Runnable action) {
        tail = tail.thenRunAsync(action, Workers.executor());
    }

    private void dispatch(E event) {
        history.add(event);
        List.copyOf(listeners).forEach(listener -> notify(listener, event));
    }

    private static <E> void notify(Consumer<E> listener, E event) {
        try {
            listener.accept(event);
        } catch (RuntimeException ignored) {
            // Observers cannot fail a generation.
        }
    }
}
