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
 * <p>THE TOOL RESULT CARRIES THE WHOLE CALL IT IS ANSWERING, not just the id. A conversation that
 * answers a call with the wrong id, or with none, is one the model cannot follow — and that is only
 * half of it. The NAME is what a reader needs: 0.13.0 kept the id and dropped the name, and the
 * record went from naming the tool to showing a column of answers with nothing attached.
 *
 * <pre>
 * [tool result]                 [tool result]
 * no files match **&#47;*.gradle    glob -&gt; no files match **&#47;*.gradle
 * </pre>
 *
 * <p>Reported as ratchet#9 by a consumer whose own test had asserted the right-hand shape since its
 * record page was built, with the reason written beside it: <em>"a result with no name reads as an
 * answer from nowhere, and by the third turn a request carries several of them."</em> Nothing in
 * this repository had an opinion — the type moved, the field went with it, and no test noticed. The
 * whole {@link Called} is kept now, so the two things a reader and a server each need cannot drift
 * apart again.
 */
public record Said(Role role, String text, List<Called> calls, Called answering) {

    /** Who is speaking. The four the endpoint knows. */
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public Said {
        text = text == null ? "" : text;
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    public static Said system(String text) {
        return new Said(Role.SYSTEM, text, List.of(), null);
    }

    public static Said user(String text) {
        return new Said(Role.USER, text, List.of(), null);
    }

    /** What the model said, and what it asked to call. Either may be empty; both may not. */
    public static Said assistant(String text, List<Called> calls) {
        return new Said(Role.ASSISTANT, text, calls, null);
    }

    /** One tool's answer, against the call that asked for it — its id AND its name. */
    public static Said result(Called call, String result) {
        return new Said(Role.TOOL, result, List.of(), call);
    }

    /** What this is an answer to, or "" when it is not an answer to anything. */
    public String answeringName() {
        return answering == null ? "" : answering.name();
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
                    Json.field("tool_call_id",
                            Json.string(answering == null ? "" : answering.id())),
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
