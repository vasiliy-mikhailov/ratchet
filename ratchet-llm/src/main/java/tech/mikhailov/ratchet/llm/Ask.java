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
 *
 * <p>{@code from} IS WHO IS ASKING, AND IT DOES NOT GO ON THE WIRE. It is here because until 0.14.0
 * the record answered that question by scanning a process-global map for the longest registered
 * system prompt contained in the message — reconstructing from content a string the caller had in a
 * field three lines away. That was forced when the request type belonged to somebody else and had
 * nowhere to put a label. This library defines the request now, so the reason went away and the
 * registry did not.
 *
 * <p>ratchet#10 measured what it cost: 277,022 exchange rows in one sweep, each scanning around
 * twenty registered prompts, needle and haystack both tens of kilobytes, because one agent's key was
 * its prompt with a 23 KB bill of materials spliced into it. It was also global mutable state in a
 * library, silently wrong if a consumer forgot to register — 737 exchanges in one sweep attributed
 * to nobody — and quietly ambiguous for two agents sharing a prompt file.
 */
public record Ask(List<Said> messages, List<Tool> tools, String from) {

    public Ask {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        from = from == null ? "" : from;
    }

    public static Ask of(List<Said> messages) {
        return new Ask(messages, List.of(), "");
    }

    /** The same question, said to be from somebody. */
    public Ask from(String who) {
        return new Ask(messages, tools, who);
    }
}
