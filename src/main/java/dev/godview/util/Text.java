package dev.godview.util;

/**
 * Small string helpers shared by the panel, the event capture and the alert engine.
 */
public final class Text {

    /**
     * The legacy formatting prefix, U+00A7.
     *
     * <p>Written as a numeric literal rather than the character itself: it is invisible in
     * most editors and survives a careless re-encode of the file far less reliably than a
     * number does. {@code ChatColor.COLOR_CHAR} would be the obvious alternative, but that
     * class is deprecated on newer Paper.
     */
    public static final char COLOR_CHAR = (char) 0xA7;

    private Text() {
    }

    /**
     * Removes legacy colour and formatting codes.
     *
     * <p>Panels, alerts and log lines all want readable text, and a raw display name is
     * full of {@code §a} sequences that render as mojibake outside the game.
     */
    public static String stripColors(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf(COLOR_CHAR) < 0) return text;

        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == COLOR_CHAR && i + 1 < text.length()) {
                i++;   // drop the code character too
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Shortens text to {@code max} characters, marking that it was cut.
     *
     * <p>Used wherever player-supplied text reaches storage or an alert body — a chat
     * message pasted from a book should not become a 30 KB Discord payload.
     */
    public static String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Null and blank both become null, so optional query parameters read the same way. */
    public static String orNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** Milliseconds as {@code 3d 4h 5m}, for uptimes and play times. */
    public static String duration(long millis) {
        if (millis < 1000) return millis + "ms";
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;

        StringBuilder sb = new StringBuilder(16);
        if (days > 0) sb.append(days).append("d ");
        if (days > 0 || hours > 0) sb.append(hours).append("h ");
        if (days == 0) sb.append(minutes).append('m');
        return sb.toString().trim();
    }
}
