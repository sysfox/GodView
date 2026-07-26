package dev.godview.listener;

import dev.godview.util.JsonWriter;
import dev.godview.util.LongRing;
import dev.godview.util.Text;
import dev.godview.web.api.EventStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * Mirrors the server console into the panel.
 *
 * <p>Attaches as a Log4j appender on the root logger, which is the only way to see
 * everything: Bukkit's own logger is one of many, and half the interesting output —
 * Netty, the world loader, other plugins' raw Log4j calls — never passes through it.
 *
 * <p>{@link #append} runs on whatever thread happened to log, including the main thread
 * inside a tick, so it does the least possible work: format, strip, push into a ring, and
 * return. Nothing here allocates a collection, touches disk or blocks.
 *
 * <p>It also must never log. An appender that logs from inside {@code append} recurses
 * until the stack ends, so failures are swallowed silently — the one place in this
 * codebase where that is the right call.
 *
 * <p>Errors are turned into stored events, rate-limited: a plugin stuck in an exception
 * loop can emit thousands of identical lines a second, and the useful signal is "this
 * started happening", not ten thousand rows of it.
 */
public final class ConsoleCapture extends AbstractAppender {

    /** Error events written to storage per minute, at most. */
    private static final int MAX_ERROR_EVENTS_PER_MINUTE = 60;

    private final Deque<Line> buffer = new ArrayDeque<>();
    private final int capacity;
    private final boolean captureErrors;
    private final Supplier<EventStream> stream;
    private final EventCapture events;
    private final LongRing errorTimes = new LongRing(MAX_ERROR_EVENTS_PER_MINUTE * 2);

    private volatile boolean attached;
    private volatile long totalLines;
    private volatile long totalErrors;
    private volatile long suppressedErrors;

    /**
     * One console line.
     *
     * @param level Log4j level name — {@code INFO}, {@code WARN}, {@code ERROR}
     */
    public record Line(long at, String level, String logger, String thread, String message) {

        public boolean isError() {
            return "ERROR".equals(level) || "FATAL".equals(level);
        }

        public void writeJson(JsonWriter w) {
            w.obj()
                    .field("at", at)
                    .field("level", level)
                    .field("logger", logger)
                    .field("thread", thread)
                    .field("message", message)
                    .end();
        }
    }

    public ConsoleCapture(int capacity, boolean captureErrors, Supplier<EventStream> stream,
                          EventCapture events) {
        super("GodViewConsole", (Filter) null, null, true, Property.EMPTY_ARRAY);
        this.capacity = Math.max(50, capacity);
        this.captureErrors = captureErrors;
        this.stream = stream;
        this.events = events;
    }

    // --------------------------------------------------------------- lifecycle

    public void attach() {
        if (attached) return;
        try {
            start();
            rootLogger().addAppender(this);
            attached = true;
        } catch (Throwable t) {
            // Not fatal: the panel simply has no console view.
            stop();
        }
    }

    public void detach() {
        if (!attached) return;
        try {
            rootLogger().removeAppender(this);
        } catch (Throwable ignored) {
            // Shutting down anyway.
        }
        attached = false;
        stop();
    }

    public boolean isAttached() {
        return attached;
    }

    private static Logger rootLogger() {
        return (Logger) LogManager.getRootLogger();
    }

    // ----------------------------------------------------------------- capture

    @Override
    public void append(LogEvent event) {
        try {
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            String level = event.getLevel() == null ? "INFO" : event.getLevel().name();

            Line line = new Line(
                    event.getTimeMillis(),
                    level,
                    shorten(event.getLoggerName()),
                    event.getThreadName() == null ? "" : event.getThreadName(),
                    Text.truncate(Text.stripAnsi(Text.stripColors(message)), 2000));

            synchronized (buffer) {
                buffer.addLast(line);
                while (buffer.size() > capacity) buffer.removeFirst();
            }
            totalLines++;

            // A thrown exception is the part an operator actually needs; the message alone
            // often says nothing useful.
            if (event.getThrown() != null) {
                appendThrowable(event.getThrown(), line.at());
            }

            publish(line);
            if (captureErrors && line.isError()) recordError(line, event);
        } catch (Throwable ignored) {
            // Never log from inside an appender — see the class note.
        }
    }

    /** Adds the exception type and its first frames as extra lines. */
    private void appendThrowable(Throwable thrown, long at) {
        List<Line> extra = new ArrayList<>(6);
        extra.add(new Line(at, "ERROR", "exception", "",
                thrown.getClass().getName() + ": " + thrown.getMessage()));
        StackTraceElement[] frames = thrown.getStackTrace();
        for (int i = 0; i < Math.min(5, frames.length); i++) {
            extra.add(new Line(at, "ERROR", "exception", "", "    at " + frames[i]));
        }

        synchronized (buffer) {
            for (Line line : extra) {
                buffer.addLast(line);
                while (buffer.size() > capacity) buffer.removeFirst();
            }
        }
        for (Line line : extra) publish(line);
    }

    private void publish(Line line) {
        EventStream live = stream.get();
        if (live == null || live.subscriberCount() == 0) return;
        JsonWriter w = new JsonWriter(256);
        line.writeJson(w);
        live.publish("console", w.done());
    }

    private void recordError(Line line, LogEvent event) {
        long now = System.currentTimeMillis();
        synchronized (errorTimes) {
            if (errorTimes.countSince(now - 60_000L) >= MAX_ERROR_EVENTS_PER_MINUTE) {
                suppressedErrors++;
                return;
            }
            errorTimes.add(now);
        }
        totalErrors++;

        String detail = line.message();
        if (event.getThrown() != null) {
            detail = event.getThrown().getClass().getSimpleName() + ": " + detail;
        }
        events.record("console_error", "server", null, null, Text.truncate(detail, 512),
                new JsonWriter(128).obj()
                        .field("logger", line.logger())
                        .field("level", line.level())
                        .end().done());
    }

    // -------------------------------------------------------------------- read

    /** The most recent lines, oldest first. */
    public List<Line> recent(int limit) {
        synchronized (buffer) {
            int size = buffer.size();
            int skip = Math.max(0, size - Math.max(1, limit));
            List<Line> out = new ArrayList<>(Math.min(size, limit));
            int index = 0;
            for (Line line : buffer) {
                if (index++ < skip) continue;
                out.add(line);
            }
            return List.copyOf(out);
        }
    }

    /** Lines at or above a level, newest last. */
    public List<Line> recent(int limit, String minLevel) {
        if (minLevel == null || minLevel.isBlank()) return recent(limit);
        int wanted = severity(minLevel);
        List<Line> out = new ArrayList<>();
        synchronized (buffer) {
            for (Line line : buffer) {
                if (severity(line.level()) >= wanted) out.add(line);
            }
        }
        int from = Math.max(0, out.size() - Math.max(1, limit));
        return List.copyOf(out.subList(from, out.size()));
    }

    public long totalLines() {
        return totalLines;
    }

    public long totalErrors() {
        return totalErrors;
    }

    /** Errors seen while over the per-minute ceiling, so the panel can say so. */
    public long suppressedErrors() {
        return suppressedErrors;
    }

    public int buffered() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    private static int severity(String level) {
        return switch (level == null ? "" : level.toUpperCase(java.util.Locale.ROOT)) {
            case "FATAL" -> 5;
            case "ERROR" -> 4;
            case "WARN" -> 3;
            case "INFO" -> 2;
            case "DEBUG" -> 1;
            default -> 0;
        };
    }

    /** {@code net.minecraft.server.MinecraftServer} is noise; {@code MinecraftServer} is not. */
    private static String shorten(String loggerName) {
        if (loggerName == null || loggerName.isEmpty()) return "";
        int dot = loggerName.lastIndexOf('.');
        return dot < 0 ? loggerName : loggerName.substring(dot + 1);
    }
}
