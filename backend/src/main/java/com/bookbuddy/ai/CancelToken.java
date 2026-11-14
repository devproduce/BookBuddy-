package com.bookbuddy.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation handle shared between a request (client disconnect, stop button) and an in-flight LLM
 * stream. Cancelling runs the registered callbacks (e.g. disposing the upstream HTTP subscription).
 */
public final class CancelToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Idempotent. Callbacks run on the cancelling thread. */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable r : callbacks) {
                run(r);
            }
        }
    }

    /** Runs {@code callback} on cancellation, or immediately if already cancelled. */
    public void onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get()) {
            run(callback);
        }
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // cancellation must never throw
        }
    }
}
