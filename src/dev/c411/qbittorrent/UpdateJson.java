package dev.c411.qbittorrent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read the answer of GitHub and to write the state of the update: objects, arrays, strings, numbers, booleans and null, with a ceiling
 * on the nesting. It does not use org.json, so the real answer of GitHub can be checked here without a device.
 */
final class UpdateJson {
    static final int MAX_DEPTH = 32;
    private static final int MAX_NUMBER = 64;

    private final String text;
    private int pos;

    private UpdateJson(String text) {
        this.text = text;
    }

    /**
     * The value in {@code text}: a Map, a List, a String, a Long (an integer that fits), a Double, a Boolean or null. A raw control character in a
     * string is kept as it is. IllegalArgumentException when the text is not JSON; the message never contains the text itself.
     */
    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON: nothing to read");
        UpdateJson reader = new UpdateJson(text);
        reader.pos = text.startsWith("\uFEFF") ? 1 : 0;
        reader.skipSpace();
        Object value = reader.value(0);
        reader.skipSpace();
        if (reader.pos != text.length()) throw reader.fail("data after the value");
        return value;
    }

    /** {@code s} as a JSON string literal that is also safe to paste into a script: quotes, backslashes, control characters, U+2028, U+2029 and lone surrogates are escaped. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        escape(sb, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            sb.append(c).append(s.charAt(++i));
                        } else {
                            escape(sb, c);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        escape(sb, c);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** {@link #quote} of a string, or the JSON null for no string. */
    static String quoteOrNull(String s) {
        return s == null ? "null" : quote(s);
    }

    private static void escape(StringBuilder sb, char c) {
        String hex = Integer.toHexString(c);
        sb.append("\\u");
        for (int i = hex.length(); i < 4; i++) sb.append('0');
        sb.append(hex);
    }

    private IllegalArgumentException fail(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + pos);
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= text.length()) throw fail("unexpected end");
        return text.charAt(pos++);
    }

    private void skipSpace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            pos++;
        }
    }

    private Object value(int depth) {
        if (pos >= text.length()) throw fail("unexpected end");
        char c = text.charAt(pos);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw fail("unexpected character");
        }
    }

    private void literal(String word) {
        if (!text.startsWith(word, pos)) throw fail("unexpected word");
        pos += word.length();
    }

    private Map<String, Object> object(int depth) {
        if (depth >= MAX_DEPTH) throw fail("nested too deep");
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        skipSpace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') throw fail("a key was expected");
            String key = string();
            skipSpace();
            if (next() != ':') throw fail("a colon was expected");
            skipSpace();
            map.put(key, value(depth + 1));
            skipSpace();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw fail("a comma was expected");
        }
    }

    private List<Object> array(int depth) {
        if (depth >= MAX_DEPTH) throw fail("nested too deep");
        pos++;
        List<Object> list = new ArrayList<>();
        skipSpace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value(depth + 1));
            skipSpace();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw fail("a comma was expected");
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = null;
        int start = pos;
        while (true) {
            if (pos >= text.length()) throw fail("unterminated string");
            char c = text.charAt(pos);
            if (c == '"') {
                String s = sb == null ? text.substring(start, pos) : sb.append(text, start, pos).toString();
                pos++;
                return s;
            }
            if (c != '\\') {
                pos++;
                continue;
            }
            if (sb == null) sb = new StringBuilder();
            sb.append(text, start, pos);
            pos++;
            char e = next();
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u': sb.append(unicode()); break;
                default: throw fail("unknown escape");
            }
            start = pos;
        }
    }

    private char unicode() {
        if (pos + 4 > text.length()) throw fail("short unicode escape");
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int d = hexValue(text.charAt(pos + i));
            if (d < 0) throw fail("bad unicode escape");
            v = v * 16 + d;
        }
        pos += 4;
        return (char) v;
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private boolean digit() {
        char c = peek();
        return c >= '0' && c <= '9';
    }

    private Object number() {
        int start = pos;
        boolean integer = true;
        if (peek() == '-') pos++;
        if (peek() == '0') {
            pos++;
        } else if (digit()) {
            while (digit()) pos++;
        } else {
            throw fail("bad number");
        }
        if (peek() == '.') {
            integer = false;
            pos++;
            if (!digit()) throw fail("bad number");
            while (digit()) pos++;
        }
        if (peek() == 'e' || peek() == 'E') {
            integer = false;
            pos++;
            if (peek() == '+' || peek() == '-') pos++;
            if (!digit()) throw fail("bad number");
            while (digit()) pos++;
        }
        if (pos - start > MAX_NUMBER) throw fail("number too long");
        String s = text.substring(start, pos);
        if (integer) {
            try {
                return Long.valueOf(s);
            } catch (NumberFormatException tooBig) {
                // An integer that does not fit a long is read as a double, which no size check accepts.
            }
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            throw fail("bad number");
        }
    }
}
