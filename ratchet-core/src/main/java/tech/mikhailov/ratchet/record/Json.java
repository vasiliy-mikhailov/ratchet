package tech.mikhailov.ratchet.record;

import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JUST ENOUGH JSON TO WRITE THE RECORD AND READ IT BACK, and deliberately no more.
 *
 * <p>The record is JSONL and the responses are small flat objects, so a library for this would be a
 * dependency taken by everything that ever writes a row, in a process that may already be running
 * arbitrary code from a stranger's repository. There is no tree model here and nothing needs one.
 *
 * <p>IT READS AS WELL AS WRITES, and the reader is handwritten for a second reason. What it parses
 * is mostly text a model wrote: {@link #read} stops at anything that does not open with a quote,
 * because in a tool argument a bare word is usually a mistake, and that tolerance is the behaviour
 * rather than an omission. {@link #number} is the exception carved out of it, and it exists because
 * that rule silently swallowed every integer argument any agent ever sent.
 *
 * <p>Escaping is {@link Settlement#escape}, which is the same function the record itself is written
 * with. Two escapers would eventually disagree about a control character, and the one place that
 * shows up is a trace body, which is exactly the field most likely to contain one.
 */
public final class Json {

    private Json() {
    }

    public static String string(String value) {
        return value == null ? "null" : "\"" + Settlement.escape(value) + "\"";
    }

    /** A string field, or JSON null when blank, which is what an absent optional means to a page. */
    public static String optional(String value) {
        return value == null || value.isBlank() ? "null" : string(value);
    }

    /**
     * One field of an object being WRITTEN: the name, then the value already in JSON form.
     *
     * <p>Not to be confused with {@link #read}, which goes the other way. They are not overloads of
     * one name on purpose: {@code field(a, b)} would mean two opposite things depending on which
     * of the two strings a reader thought was the document.
     */
    public static String field(String name, String rawValue) {
        return "\"" + name + "\":" + rawValue;
    }

    public static String object(String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    public static <T> String array(List<T> items, Function<T, String> each) {
        return "[" + items.stream().map(each).collect(Collectors.joining(",")) + "]";
    }

    public static String map(Map<String, String> values) {
        return "{" + values.entrySet().stream()
                .map(e -> string(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(",")) + "}";
    }
    /**
     * THE NAMED STRING FIELD OF A FLAT DOCUMENT, unescaped, or empty when it is not a string.
     *
     * <p>Deliberately blind to a bare word: this reads arguments a model composed, where an
     * unquoted value is far more often a mistake than an intention. {@link #number} is the one
     * carve-out, for the one case where unquoted is correct.
     */
    public static String read(String json, String name) {
        int at = json.indexOf("\"" + name + "\":");
        if (at < 0) {
            return "";
        }
        int i = at + name.length() + 3;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int p = i + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(++p);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(json, p + 1, p + 5, 16));
                        p += 4;
                    }
                    default -> out.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A NUMERIC argument, which {@link #read} cannot see.
     *
     * <p>read stops at anything not opening with a quote, deliberately. But a JSON integer property
     * arrives unquoted and legitimately, so every numeric argument was silently read as absent and
     * fell back to its default: one tool's {@code limit} was ignored on every call an agent ever
     * made, for as long as the tool existed, with nothing anywhere saying so.
     */
    public static int number(String json, String name, int fallback) {
        int at = json.indexOf("\"" + name + "\":");
        if (at < 0) {
            return fallback;
        }
        int i = at + name.length() + 3;
        while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == '"')) {
            i++;
        }
        int from = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
            i++;
        }
        try {
            return i > from ? Integer.parseInt(json, from, i, 10) : fallback;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** A whole flat row as a map. Tolerant of both quoted strings and bare numbers and booleans;
     *  the trace and the journal differ on that. */
    public static Map<String, String> row(String jsonl) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 1;
        while (i < jsonl.length() - 1) {
            int k1 = jsonl.indexOf('"', i);
            int k2 = k1 < 0 ? -1 : jsonl.indexOf('"', k1 + 1);
            if (k2 < 0) {
                break;
            }
            String key = jsonl.substring(k1 + 1, k2);
            int colon = jsonl.indexOf(':', k2);
            if (colon < 0) {
                break;
            }
            int scan = colon + 1;
            while (scan < jsonl.length() && jsonl.charAt(scan) == ' ') {
                scan++;
            }
            if (scan < jsonl.length() && jsonl.charAt(scan) == '"') {
                StringBuilder v = new StringBuilder();
                int p = scan + 1;
                while (p < jsonl.length()) {
                    char ch = jsonl.charAt(p);
                    if (ch == '\\' && p + 1 < jsonl.length()) {
                        char n = jsonl.charAt(++p);
                        switch (n) {
                            case 'n' -> v.append('\n');
                            case 't' -> v.append('\t');
                            case 'r' -> v.append('\r');
                            case 'u' -> {
                                v.append((char) Integer.parseInt(jsonl, p + 1, p + 5, 16));
                                p += 4;
                            }
                            default -> v.append(n);
                        }
                    } else if (ch == '"') {
                        break;
                    } else {
                        v.append(ch);
                    }
                    p++;
                }
                out.put(key, v.toString());
                i = p + 1;
            } else {
                int stop = scan;
                while (stop < jsonl.length() && ",}".indexOf(jsonl.charAt(stop)) < 0) {
                    stop++;
                }
                out.put(key, jsonl.substring(scan, stop).trim());
                i = stop + 1;
            }
        }
        return out;
    }
}
