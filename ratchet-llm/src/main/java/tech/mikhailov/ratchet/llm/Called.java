package tech.mikhailov.ratchet.llm;

/**
 * A TOOL CALL THE MODEL ASKED FOR: an id, a name, and the arguments as it wrote them.
 *
 * <p>The arguments stay a STRING and are never parsed here. They are JSON the model composed, which
 * means they are JSON only most of the time, and the one thing this library must not do is decide
 * a call is unintelligible before the tool that understands it has looked. {@code Json.read} is
 * deliberately tolerant for the same reason and is what a tool uses to read them.
 *
 * <p>The id comes back from the server and travels out again on the result message. It is opaque
 * and it matters: a conversation that answers a call with the wrong id is a conversation the model
 * cannot follow.
 */
public record Called(String id, String name, String arguments) {

    public Called {
        name = name == null ? "" : name;
        arguments = arguments == null ? "" : arguments;
    }
}
