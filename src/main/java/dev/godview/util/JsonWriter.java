package dev.godview.util;

/**
 * Streaming JSON builder for the hot paths — snapshot pushes, SSE frames, list endpoints.
 *
 * <p>{@link Json#write(Object)} has to walk maps and boxes every number; this writes
 * straight into one {@link StringBuilder} with no intermediate objects. Commas and the
 * {@code :} after names are inserted automatically:
 *
 * <pre>{@code
 * String body = new JsonWriter()
 *         .obj()
 *           .field("tps", 19.87)
 *           .name("players").arr()
 *             .obj().field("name", "Steve").field("ping", 42).end()
 *           .end()
 *         .end()
 *         .done();
 * }</pre>
 *
 * <p>Not thread-safe — build on one thread, publish the resulting string.
 */
public final class JsonWriter {

    private final StringBuilder sb;
    private final char[] stack = new char[Json.MAX_DEPTH];
    private final boolean[] empty = new boolean[Json.MAX_DEPTH];
    private int depth;
    private boolean rootWritten;

    public JsonWriter() {
        this(256);
    }

    public JsonWriter(int initialCapacity) {
        this.sb = new StringBuilder(initialCapacity);
    }

    // -------------------------------------------------------------- structure

    public JsonWriter obj() {
        sep();
        push('{');
        sb.append('{');
        return this;
    }

    public JsonWriter arr() {
        sep();
        push('[');
        sb.append('[');
        return this;
    }

    /** Closes the innermost object or array. */
    public JsonWriter end() {
        if (depth == 0) throw new IllegalStateException("end() with nothing open");
        sb.append(stack[--depth] == '{' ? '}' : ']');
        return this;
    }

    /** Writes an object key. Must be followed by exactly one value. */
    public JsonWriter name(String key) {
        if (depth == 0 || stack[depth - 1] != '{') {
            throw new IllegalStateException("name() outside an object");
        }
        if (!empty[depth - 1]) sb.append(',');
        empty[depth - 1] = false;
        Json.writeString(sb, key);
        sb.append(':');
        return this;
    }

    // ----------------------------------------------------------------- values

    public JsonWriter value(String v) {
        sep();
        if (v == null) sb.append("null");
        else Json.writeString(sb, v);
        return this;
    }

    public JsonWriter value(long v) {
        sep();
        sb.append(v);
        return this;
    }

    public JsonWriter value(int v) {
        sep();
        sb.append(v);
        return this;
    }

    public JsonWriter value(double v) {
        sep();
        Json.writeDouble(sb, v);
        return this;
    }

    public JsonWriter value(boolean v) {
        sep();
        sb.append(v ? "true" : "false");
        return this;
    }

    /** Falls back to {@link Json}'s general writer — handles maps, lists and arrays. */
    public JsonWriter value(Object v) {
        sep();
        Json.writeTo(sb, v);
        return this;
    }

    public JsonWriter nul() {
        sep();
        sb.append("null");
        return this;
    }

    /**
     * Appends an already-serialized fragment verbatim. The caller owns its validity —
     * this is the escape hatch for cached sub-documents.
     */
    public JsonWriter raw(String jsonFragment) {
        sep();
        sb.append(jsonFragment);
        return this;
    }

    // ------------------------------------------------------- field shorthands

    public JsonWriter field(String key, String v) {
        return name(key).value(v);
    }

    public JsonWriter field(String key, long v) {
        return name(key).value(v);
    }

    public JsonWriter field(String key, int v) {
        return name(key).value(v);
    }

    public JsonWriter field(String key, double v) {
        return name(key).value(v);
    }

    public JsonWriter field(String key, boolean v) {
        return name(key).value(v);
    }

    public JsonWriter field(String key, Object v) {
        return name(key).value(v);
    }

    public JsonWriter rawField(String key, String jsonFragment) {
        return name(key).raw(jsonFragment);
    }

    /** Writes {@code key: [v0, v1, ...]} without boxing. */
    public JsonWriter field(String key, double[] values) {
        name(key).sep();
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            Json.writeDouble(sb, values[i]);
        }
        sb.append(']');
        return this;
    }

    public JsonWriter field(String key, long[] values) {
        name(key).sep();
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        sb.append(']');
        return this;
    }

    public JsonWriter field(String key, int[] values) {
        name(key).sep();
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        sb.append(']');
        return this;
    }

    // ------------------------------------------------------------------ close

    /** @throws IllegalStateException if a container was left open */
    public String done() {
        if (depth != 0) throw new IllegalStateException(depth + " container(s) left open");
        return sb.toString();
    }

    /** Serialized length so far — handy for Content-Length without materializing the string. */
    public int length() {
        return sb.length();
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    // -------------------------------------------------------------- internals

    /** Comma before an array element or a bare root value; object members go through {@link #name}. */
    private void sep() {
        if (depth == 0) {
            if (rootWritten) throw new IllegalStateException("more than one root value");
            rootWritten = true;
            return;
        }
        if (stack[depth - 1] == '[') {
            if (!empty[depth - 1]) sb.append(',');
            empty[depth - 1] = false;
        }
    }

    private void push(char kind) {
        if (depth == stack.length) {
            throw new IllegalStateException("nesting deeper than " + Json.MAX_DEPTH);
        }
        stack[depth] = kind;
        empty[depth] = true;
        depth++;
    }
}
