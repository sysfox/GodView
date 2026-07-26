package dev.godview.metrics;

import dev.godview.util.JsonWriter;

import java.util.List;

/**
 * One tick that took too long, with whatever evidence we managed to collect.
 *
 * @param at         epoch millis the slow tick ended
 * @param durationMs measured wall time of the tick
 * @param tick       server tick counter, so repeated spikes can be told apart
 * @param blame      the plugin held responsible, or {@code "Minecraft"} / {@code "unknown"}
 * @param blameFrame the exact stack frame that produced {@code blame}, empty if none
 * @param stack      main-thread frames captured while the tick was still running, innermost
 *                   first and already trimmed; empty when capture was off or rate-limited
 */
public record Spike(long at, double durationMs, long tick, String blame, String blameFrame,
                    List<String> stack) {

    /** Frames beyond this are almost always scheduler and server-loop plumbing. */
    public static final int MAX_FRAMES = 32;

    public boolean hasStack() {
        return !stack.isEmpty();
    }

    public void writeJson(JsonWriter w) {
        w.obj()
                .field("at", at)
                .field("durationMs", durationMs)
                .field("tick", tick)
                .field("blame", blame)
                .field("blameFrame", blameFrame)
                .name("stack").arr();
        for (String frame : stack) w.value(frame);
        w.end().end();
    }
}
