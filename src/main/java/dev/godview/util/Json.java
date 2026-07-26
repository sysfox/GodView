package dev.godview.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-dependency JSON reader/writer.
 *
 * <p>Writing accepts {@code null}, {@link CharSequence}, {@link Number}, {@link Boolean},
 * {@link Map} (keys are stringified), {@link Iterable}, and object/primitive arrays.
 * Anything else falls back to {@link String#valueOf} written as a JSON string, which
 * keeps enums and UUIDs usable without registration. Non-finite doubles become
 * {@code null} (JSON has no NaN/Infinity).
 *
 * <p>Parsing is strict RFC 8259 apart from a small lenience in number formats
 * (whatever {@link Double#parseDouble} accepts after the scanner's charset filter).
 * Objects become {@link LinkedHashMap}, arrays {@link ArrayList}; numbers become
 * {@link Long} when integral and fitting, otherwise {@link Double}. Nesting depth
 * is capped so hostile request bodies cannot overflow the stack.
 *
 * <p>For hot paths that build large documents piece by piece, use {@link JsonWriter}.
 */
public final class Json {

    /** Maximum nesting for both reading and writing. */
    static final int MAX_DEPTH = 64;

    private Json() {
    }

    // ------------------------------------------------------------------ write

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeTo(sb, value, 0);
        return sb.toString();
    }

    public static void writeTo(StringBuilder sb, Object value) {
        writeTo(sb, value, 0);
    }

    private static void writeTo(StringBuilder sb, Object v, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting deeper than " + MAX_DEPTH + " (cycle?)");
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof CharSequence s) {
            writeString(sb, s.toString());
        } else if (v instanceof Double d) {
            writeDouble(sb, d);
        } else if (v instanceof Float f) {
            writeDouble(sb, f.doubleValue());
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean firstEntry = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!firstEntry) sb.append(',');
                firstEntry = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean firstItem = true;
            for (Object o : it) {
                if (!firstItem) sb.append(',');
                firstItem = false;
                writeTo(sb, o, depth + 1);
            }
            sb.append(']');
        } else if (v instanceof Object[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                writeTo(sb, a[i], depth + 1);
            }
            sb.append(']');
        } else if (v instanceof double[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                writeDouble(sb, a[i]);
            }
            sb.append(']');
        } else if (v instanceof long[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else if (v instanceof int[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    /** Non-finite → {@code null}; integral values are printed without the trailing {@code .0}. */
    static void writeDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // U+2028/U+2029 are legal in JSON but terminate a line in JavaScript
                    // and in the SSE wire format, so they get escaped too. Compared as
                    // numbers because the literal characters are invisible in source.
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ parse

    /** @throws ParseException on malformed input */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != p.len) throw p.err("unexpected trailing characters");
        return v;
    }

    public static final class ParseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        final String s;
        final int len;
        int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw err("nesting deeper than " + MAX_DEPTH);
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        Map<String, Object> object(int depth) {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw err("expected string key");
                String key = string();
                skipWs();
                if (peek() != ':') throw err("expected ':'");
                pos++;
                map.put(key, value(depth + 1));
                skipWs();
                char c = peek();
                pos++;
                if (c == ',') continue;
                if (c == '}') return map;
                throw err("expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value(depth + 1));
                skipWs();
                char c = peek();
                pos++;
                if (c == ',') continue;
                if (c == ']') return list;
                throw err("expected ',' or ']'");
            }
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= len) throw err("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= len) throw err("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > len) throw err("truncated \\u escape");
                            int cp;
                            try {
                                cp = Integer.parseInt(s, pos, pos + 4, 16);
                            } catch (NumberFormatException ex) {
                                throw err("invalid \\u escape");
                            }
                            sb.append((char) cp);
                            pos += 4;
                        }
                        default -> throw err("invalid escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw err("raw control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            boolean fractional = false;
            while (pos < len) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fractional = true;
                    pos++;
                } else {
                    break;
                }
            }
            String raw = s.substring(start, pos);
            try {
                return fractional ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                if (!fractional) {
                    // Integral but beyond long range — keep it as a double.
                    try {
                        return Double.parseDouble(raw);
                    } catch (NumberFormatException ignored) {
                        // fall through to the error below
                    }
                }
                throw err("malformed number '" + raw + "'");
            }
        }

        char peek() {
            if (pos >= len) throw err("unexpected end of input");
            return s.charAt(pos);
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) throw err("invalid literal");
            pos += word.length();
        }

        void skipWs() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        ParseException err(String message) {
            return new ParseException(message + " at offset " + pos);
        }
    }

    // ------------------------------------------------------- typed accessors

    /** The parse result viewed as an object; an immutable empty map when it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Collections.emptyMap();
    }

    /** The parse result viewed as an array; an immutable empty list when it is not one. */
    @SuppressWarnings("unchecked")
    public static List<Object> asArr(Object v) {
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }

    public static String str(Map<String, ?> obj, String key, String def) {
        Object v = obj.get(key);
        return v instanceof String s ? s : def;
    }

    public static boolean flag(Map<String, ?> obj, String key, boolean def) {
        Object v = obj.get(key);
        return v instanceof Boolean b ? b : def;
    }

    public static double num(Map<String, ?> obj, String key, double def) {
        Object v = obj.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public static long lng(Map<String, ?> obj, String key, long def) {
        Object v = obj.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    public static int i32(Map<String, ?> obj, String key, int def) {
        Object v = obj.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }
}
