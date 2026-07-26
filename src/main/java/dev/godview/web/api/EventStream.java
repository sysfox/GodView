package dev.godview.web.api;

import dev.godview.util.Json;
import dev.godview.web.WebExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

/**
 * Pushes live updates to every open panel over Server-Sent Events.
 *
 * <p>SSE rather than WebSockets: the traffic is entirely one-way, it is plain HTTP so it
 * survives any reverse proxy without extra configuration, and browsers reconnect on their
 * own. A WebSocket implementation would mean writing a frame codec and a handshake for no
 * capability we need.
 *
 * <p>The threading is the part that matters. Snapshots are published from the main thread,
 * so publishing must never block — one panel open on a phone with a bad connection would
 * otherwise stall the tick loop. Instead each subscriber has a small bounded queue and a
 * single shared thread does the writing. That is also what makes the promise in
 * {@code config.yml} true: a hundred connected panels cost one thread, not a hundred.
 *
 * <p>When a subscriber's queue fills, the oldest frame is dropped rather than the newest.
 * A client too slow to keep up wants the current state of the server, not a backlog of
 * where it was thirty seconds ago.
 *
 * <p>A disconnected client is discovered by writing to it, because that is the only way
 * TCP reports the loss — and often not on the first attempt, since a write into a socket
 * the peer has already closed usually lands in the local buffer and only fails on the one
 * after. With the pusher sending a frame a second that resolves in a couple of seconds;
 * on a completely idle stream the heartbeat bounds it to a little over
 * {@link #HEARTBEAT_MS}. So {@link #subscriberCount()} can briefly overcount after
 * somebody closes their tab, which delays {@code idle-suspend} by the same margin. The
 * alternative would be a liveness probe costing more than the collection it is trying to
 * avoid.
 */
public final class EventStream {

    /** Frames a slow client may fall behind by before we start dropping. */
    private static final int QUEUE_CAPACITY = 16;
    /**
     * Comment frames keep proxies from closing an idle connection, and double as the
     * liveness probe that eventually notices a client which went away silently.
     */
    static final long HEARTBEAT_MS = 20_000L;
    /** How often the writer wakes to flush. Fast enough to feel live. */
    private static final long FLUSH_INTERVAL_MS = 50L;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Logger log;
    private final IntConsumer viewerCountListener;

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private Thread writer;
    private volatile boolean running;

    /**
     * @param viewerCountListener notified whenever the subscriber count changes, so the
     *                            metrics layer can suspend collection when nobody is watching
     */
    public EventStream(Logger log, IntConsumer viewerCountListener) {
        this.log = log;
        this.viewerCountListener = viewerCountListener;
    }

    private static final class Subscriber {
        final OutputStream out;
        final WebExchange exchange;
        final String user;
        final Deque<byte[]> queue = new ArrayDeque<>(QUEUE_CAPACITY);
        volatile long lastWrite = System.currentTimeMillis();
        volatile boolean dead;

        Subscriber(OutputStream out, WebExchange exchange, String user) {
            this.out = out;
            this.exchange = exchange;
            this.user = user;
        }
    }

    public void start() {
        if (running) return;
        running = true;
        writer = new Thread(this::writeLoop, "GodView-EventStream");
        writer.setDaemon(true);
        writer.start();
    }

    public void stop() {
        running = false;
        if (writer != null) {
            writer.interrupt();
            writer = null;
        }
        closeAll();
    }

    /**
     * Attaches a client. The HTTP worker is released as soon as this returns — the
     * exchange stays open and is written to by the shared thread.
     */
    public void subscribe(WebExchange exchange) throws IOException {
        OutputStream out = exchange.startStream("text/event-stream; charset=utf-8");
        String user = exchange.session() == null ? "?" : exchange.session().user();
        Subscriber subscriber = new Subscriber(out, exchange, user);

        // Tell the browser to wait a moment before reconnecting, and confirm we are live
        // before the first snapshot arrives.
        try {
            out.write("retry: 3000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            exchange.close();
            throw e;
        }

        subscribers.add(subscriber);
        notifyViewers();
    }

    /**
     * Queues a frame for every subscriber. Safe to call from the main thread; never blocks
     * and never throws.
     *
     * @param event SSE event name the panel dispatches on, e.g. {@code snapshot}
     * @param json  already-serialized payload
     */
    public void publish(String event, String json) {
        if (subscribers.isEmpty()) return;
        byte[] frame = frame(event, json);
        published.incrementAndGet();

        for (Subscriber subscriber : subscribers) {
            synchronized (subscriber.queue) {
                if (subscriber.queue.size() >= QUEUE_CAPACITY) {
                    // Drop the oldest: a client behind by sixteen frames wants the current
                    // state of the server, not the backlog.
                    subscriber.queue.pollFirst();
                    dropped.incrementAndGet();
                }
                subscriber.queue.addLast(frame);
            }
        }
    }

    /** Convenience for publishing a value that still needs serializing. */
    public void publishValue(String event, Object value) {
        publish(event, Json.write(value));
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    public long framesPublished() {
        return published.get();
    }

    /** Frames discarded because a client could not keep up. */
    public long framesDropped() {
        return dropped.get();
    }

    public void closeAll() {
        for (Subscriber subscriber : subscribers) close(subscriber);
        subscribers.clear();
        notifyViewers();
    }

    // ---------------------------------------------------------------- internals

    private void writeLoop() {
        while (running) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
                flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.warning("GodView event stream flush failed: " + t);
            }
        }
    }

    private void flush() {
        if (subscribers.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean removed = false;

        for (Subscriber subscriber : subscribers) {
            if (subscriber.dead) {
                subscribers.remove(subscriber);
                removed = true;
                continue;
            }

            byte[][] pending;
            synchronized (subscriber.queue) {
                if (subscriber.queue.isEmpty()) {
                    pending = null;
                } else {
                    pending = subscriber.queue.toArray(new byte[0][]);
                    subscriber.queue.clear();
                }
            }

            try {
                if (pending != null) {
                    for (byte[] frame : pending) subscriber.out.write(frame);
                    subscriber.out.flush();
                    subscriber.lastWrite = now;
                } else if (now - subscriber.lastWrite >= HEARTBEAT_MS) {
                    // A comment line: valid SSE, ignored by the client, enough to keep an
                    // idle connection from being reaped by a proxy.
                    subscriber.out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    subscriber.out.flush();
                    subscriber.lastWrite = now;
                }
            } catch (Throwable t) {
                // The client went away. That is the normal way a stream ends.
                close(subscriber);
                subscribers.remove(subscriber);
                removed = true;
            }
        }

        if (removed) notifyViewers();
    }

    private void close(Subscriber subscriber) {
        subscriber.dead = true;
        try {
            subscriber.out.close();
        } catch (Throwable ignored) {
            // Already gone.
        }
        try {
            subscriber.exchange.close();
        } catch (Throwable ignored) {
            // Already gone.
        }
    }

    private void notifyViewers() {
        try {
            viewerCountListener.accept(subscribers.size());
        } catch (Throwable ignored) {
            // A listener failure must not take the stream down.
        }
    }

    /**
     * Builds one SSE frame.
     *
     * <p>Payloads are single-line JSON, and {@link Json} escapes every newline including
     * U+2028 and U+2029, so no data line can ever contain a raw newline and split the
     * frame — which would silently truncate the event on the client.
     */
    static byte[] frame(String event, String json) {
        StringBuilder sb = new StringBuilder(json.length() + 32);
        sb.append("event: ").append(event).append('\n');
        sb.append("data: ").append(json).append('\n').append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
