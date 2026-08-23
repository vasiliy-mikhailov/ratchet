package tech.mikhailov.ratchet.llm;

import java.util.List;

import tech.mikhailov.ratchet.record.Json;

/**
 * ONE MESSAGE IN A CONVERSATION, AND HOW IT GOES ON THE WIRE.
 *
 * <p>It writes itself, which is the point. What a request actually contains was previously decided
 * inside a client library, so the only way to check it was to send one and watch; here
 * {@link #wire()} is a pure function of the record and a test asserts the exact bytes without a
 * socket. That mattered immediately — an assistant turn carrying tool calls must send
 * {@code "content":null} and not {@code "content":""}, and a server that accepts one and rejects
 * the other is a bug nobody finds by reading a builder.
 *
 * <p>THE TOOL RESULT CARRIES THE ID IT IS ANSWERING. A conversation that answers a call with the
 * wrong id, or with none, is one the model cannot follow, and it is the single easiest thing to get
 * wrong when writing this loop by hand.
 */
public record Said(Role role, String text, List<Called> calls, String answering) {

    /** Who is speaking. The four the endpoint knows. */
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public Said {
        text = text == null ? "" : text;
        calls = calls == null ? List.of() : List.copyOf(calls);
        answering = answering == null ? "" : answering;
    }

    public static Said system(String text) {
        return new Said(Role.SYSTEM, text, List.of(), "");
    }

    public static Said user(String text) {
        return new Said(Role.USER, text, List.of(), "");
    }

    /** What the model said, and what it asked to call. Either may be empty; both may not. */
    public static Said assistant(String text, List<Called> calls) {
        return new Said(Role.ASSISTANT, text, calls, "");
    }

    /** One tool's answer, against the id of the call that asked for it. */
    public static Said result(Called call, String result) {
        return new Said(Role.TOOL, result, List.of(), call.id());
    }

    /** This message as the endpoint expects it. */
    public String wire() {
        String name = switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
        if (role == Role.TOOL) {
            return Json.object(Json.field("role", Json.string(name)),
                    Json.field("tool_call_id", Json.string(answering)),
                    Json.field("content", Json.string(text)));
        }
        if (role == Role.ASSISTANT && !calls.isEmpty()) {
            // NULL, NOT EMPTY STRING. An assistant turn whose whole content is tool calls has no
            // content, and the two are different values to a server that validates its input.
            return Json.object(Json.field("role", Json.string(name)),
                    Json.field("content", text.isEmpty() ? "null" : Json.string(text)),
                    Json.field("tool_calls", Json.array(calls, c -> Json.object(
                            Json.field("id", Json.string(c.id())),
                            Json.field("type", Json.string("function")),
                            Json.field("function", Json.object(
                                    Json.field("name", Json.string(c.name())),
                                    Json.field("arguments", Json.string(c.arguments()))))))));
        }
        return Json.object(Json.field("role", Json.string(name)),
                Json.field("content", Json.string(text)));
    }
}
