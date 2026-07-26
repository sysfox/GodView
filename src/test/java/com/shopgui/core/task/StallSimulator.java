package com.shopgui.core.task;

/**
 * Stands in for a badly behaved plugin. Lives in a fake vendor package on purpose:
 * the attributor identifies plugins by package prefix, so blaming this correctly is
 * only meaningful if the class really sits under com.shopgui.
 */
public final class StallSimulator {

    private StallSimulator() {
    }

    /** Occupies the calling thread for {@code millis}, visibly, in a named frame. */
    public static void shopGuiWorkThatTakesTooLong(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
