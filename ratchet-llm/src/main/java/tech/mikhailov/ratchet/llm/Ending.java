package tech.mikhailov.ratchet.llm;

import java.util.Locale;

/**
 * WHY THE MODEL STOPPED, WHICH IS NOT THE SAME QUESTION AS WHETHER IT ANSWERED.
 *
 * <p>{@link #LENGTH} is the one that earns this its own type. A generation that ran out of room
 * mid-thought returns blank content and looks exactly like a model that declined, and the whole of
 * {@link Truncated} exists because the runtime returned that blank as an answer and a consumer
 * wrote it over a file that had something in it. The reason was on the response the entire time;
 * nothing read it.
 *
 * <p>{@link #OTHER} rather than a throw for an unrecognised word: a server naming a new stop reason
 * is not a reason to fail a call whose content arrived intact.
 */
public enum Ending {

    /** The model finished what it had to say. */
    STOPPED,
    /** The turn ended with tool calls; the loop runs them and asks again. */
    TOOLS,
    /** The token budget ran out. Blank content with this reason is a {@link Truncated}. */
    LENGTH,
    /** Something else, or nothing said at all. */
    OTHER;

    /** The server's word, mapped. {@code null} and anything unrecognised are {@link #OTHER}. */
    public static Ending of(String said) {
        if (said == null) {
            return OTHER;
        }
        return switch (said.toLowerCase(Locale.ROOT).strip()) {
            case "stop", "end_turn" -> STOPPED;
            case "tool_calls", "function_call" -> TOOLS;
            case "length", "max_tokens" -> LENGTH;
            default -> OTHER;
        };
    }
}
