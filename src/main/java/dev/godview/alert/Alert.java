package dev.godview.alert;

import dev.godview.util.JsonWriter;

import java.util.List;
import java.util.Locale;

/**
 * One alert, at the moment it fired.
 *
 * @param ruleId    the {@code id} from {@code config.yml}, so an operator can find the rule
 * @param severity  {@code info}, {@code warn} or {@code critical}
 * @param title     one line, suitable for a phone notification
 * @param message   the detail — what the value was, what the threshold was, what to look at
 * @param value     the number that triggered it, or 0 for rules with no number
 * @param channels  the channel ids this alert was addressed to
 */
public record Alert(long at, String ruleId, String severity, String title, String message,
                    double value, List<String> channels) {

    /** Ordered least to most urgent, so severities can be compared. */
    public static int rank(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 3;
            case "warn" -> 2;
            case "info" -> 1;
            default -> 0;
        };
    }

    public boolean isCritical() {
        return "critical".equalsIgnoreCase(severity);
    }

    /** A short prefix for text-only channels that cannot show colour. */
    public String marker() {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> "[CRITICAL]";
            case "warn" -> "[WARNING]";
            default -> "[INFO]";
        };
    }

    /** Colour for channels that accept one, as a 24-bit integer. */
    public int color() {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 0xE5484D;
            case "warn" -> 0xF5A524;
            default -> 0x3E9EFF;
        };
    }

    public void writeJson(JsonWriter w) {
        w.obj()
                .field("at", at)
                .field("ruleId", ruleId)
                .field("severity", severity)
                .field("title", title)
                .field("message", message)
                .field("value", value)
                .field("channels", channels)
                .end();
    }
}
