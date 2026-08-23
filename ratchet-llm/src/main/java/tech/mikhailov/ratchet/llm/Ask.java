package tech.mikhailov.ratchet.llm;

import java.util.List;

/**
 * ONE REQUEST: the conversation so far, and what may be called.
 *
 * <p>Deliberately not the sampling, the model name or the thinking budget. Those belong to the
 * client, which is where they were chosen and where every call in a process shares them — a request
 * that carried them would let one stage quietly answer at a different temperature than the stage
 * that judges it, which is the thing temperature zero is here to prevent.
 *
 * <p>An empty tool list is a legitimate shape and is NOT the same as tools being absent. An agent
 * with nothing to call is an ordinary agent; the field is simply omitted from the body, because a
 * server sent an empty {@code tools} array is entitled to complain about it.
 */
public record Ask(List<Said> messages, List<Tool> tools) {

    public Ask {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static Ask of(List<Said> messages) {
        return new Ask(messages, List.of());
    }
}
