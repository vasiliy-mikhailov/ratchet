package tech.mikhailov.ratchet.tools;

import tech.mikhailov.ratchet.record.Json;

/**
 * WHAT THE MODEL WROTE, READ BACK WITH ITS NAME ON IT.
 *
 * <p>A tool's arguments arrive as JSON the model composed, so every field is a claim rather than a
 * value: it may be absent, it may be the wrong shape, and it may be a fragment of what was intended
 * because the generation hit the wall halfway through writing it. Each reader here says what was
 * missing, in the tool's own vocabulary, because that sentence goes back to the model and is the
 * only thing it has to correct itself with.
 *
 * <p>FIELD NAMES ARE snake_case AND THAT IS NOT THIS LIBRARY'S TASTE. They match the schemas models
 * are already trained against — {@code file_path}, {@code old_string}, {@code replace_all} — and a
 * tool that renames them for house style is asking every model that meets it to guess.
 */
final class Args {

    private Args() {
    }

    /** A required string, or a sentence naming what is missing. */
    static String need(String json, String name) {
        String value = Json.read(json, name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required and was not in the arguments: "
                    + Retain.glance(json));
        }
        return value;
    }

    /** An optional string, or {@code fallback}. */
    static String maybe(String json, String name, String fallback) {
        String value = Json.read(json, name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** An optional number, or {@code fallback} when absent or unreadable. */
    static int number(String json, String name, int fallback) {
        return Json.number(json, name, fallback);
    }

    /** An optional flag. Anything but a literal {@code true} is false, including nonsense. */
    static boolean flag(String json, String name) {
        return "true".equalsIgnoreCase(Json.read(json, name));
    }
}
