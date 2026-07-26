package dev.godview.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bridges HTTP worker threads onto the server's main thread.
 *
 * <p>Almost nothing in the Bukkit API is safe to touch from another thread. An HTTP
 * handler that wants to kick a player, read an inventory or run a command has to hand the
 * work to the main thread and wait for it — that is what this does.
 *
 * <p>Every call carries a timeout, and the timeout matters more than it looks. If the
 * server is frozen — which is exactly when an operator is hammering the panel — a
 * scheduled task may never run. Without a bound, each stuck request would hold an HTTP
 * worker forever, and a panel that hangs during an incident is worse than one that says
 * "the server is not responding".
 *
 * <p>Calls made from the main thread run inline. That keeps handlers usable from the
 * in-game command path without deadlocking on a task that cannot be scheduled until the
 * current one returns.
 */
public final class MainThread {

    /** Long enough to survive a slow tick, short enough that a frozen server is reported. */
    public static final long DEFAULT_TIMEOUT_MS = 3_000L;

    private MainThread() {
    }

    /** Thrown when the main thread did not run the work in time. */
    public static final class NotRespondingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotRespondingException(long timeoutMs) {
            super("the server's main thread did not respond within " + timeoutMs + "ms");
        }
    }

    /**
     * Runs {@code work} on the main thread and returns its result.
     *
     * @throws NotRespondingException when the tick loop does not get to it in time
     */
    public static <T> T call(Plugin plugin, Supplier<T> work, long timeoutMs) {
        if (Bukkit.isPrimaryThread()) return work.get();

        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The task may still run later; nothing here depends on it having been cancelled.
            throw new NotRespondingException(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotRespondingException(timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    public static <T> T call(Plugin plugin, Supplier<T> work) {
        return call(plugin, work, DEFAULT_TIMEOUT_MS);
    }

    /** Runs {@code work} on the main thread and waits for it to finish. */
    public static void run(Plugin plugin, Runnable work, long timeoutMs) {
        call(plugin, () -> {
            work.run();
            return null;
        }, timeoutMs);
    }

    public static void run(Plugin plugin, Runnable work) {
        run(plugin, work, DEFAULT_TIMEOUT_MS);
    }

    /** Schedules {@code work} without waiting. For fire-and-forget side effects. */
    public static void submit(Plugin plugin, Runnable work) {
        if (Bukkit.isPrimaryThread()) {
            work.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, work);
    }
}
