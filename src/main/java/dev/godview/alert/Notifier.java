package dev.godview.alert;

/**
 * One place an alert can be delivered.
 *
 * <p>Implementations are called from the dispatcher's own thread and are allowed — expected,
 * even — to block on network I/O. Nothing here may be called from the main thread: a
 * Discord webhook that takes three seconds to answer would otherwise be three seconds of
 * frozen server, triggered by an alert about the server being slow.
 *
 * <p>Failure is reported by throwing. The dispatcher records which channels succeeded so
 * the panel can show that an alert fired but nobody was told, which is a materially
 * different situation from an alert that never fired.
 */
public interface Notifier {

    /** Channel id as written in {@code alerts.rules[].channels}. */
    String id();

    /** False when the channel is switched off or missing its configuration. */
    boolean enabled();

    /**
     * Delivers one alert.
     *
     * @throws Exception when delivery failed; the message is recorded, not retried
     */
    void send(Alert alert) throws Exception;

    /** Why {@link #enabled()} is false, for the panel's diagnostics. Empty when enabled. */
    default String problem() {
        return "";
    }
}
