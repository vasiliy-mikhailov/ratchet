package tech.mikhailov.ratchet.record;

/**
 * WHAT A CONSUMER MAY BE TOLD WHILE AN AGENT IS WORKING, owned here rather than imported.
 *
 * <p>The agent layer used to implement a third party's five-method listener in order to override
 * two of them, and that single import decided what the library could be published as. Four of the
 * five signatures reported on an orchestrator this design never constructs, and the record proves
 * it: across a whole recorded corpus, including one trace of 5,665 rows, not one orchestrator row
 * was ever written. What is left is the one thing a tool loop can honestly report.
 *
 * <p>IT LIVES IN THE CORE, ONE ARTIFACT BELOW THE THING THAT CALLS IT, and that is deliberate.
 * {@link JsonlTrace} implements it, and a core class cannot implement an interface that lives in a
 * module depending on core. Moving it up would cost the record its implementation and quietly turn
 * every {@code trace instanceof JsonlTrace} check in a consumer into a null. Core carries one
 * interface nobody there calls; the alternative is a behaviour change.
 *
 * <p>{@code memoryId} is {@link Object} for the same reason: it is whatever the agent runtime uses
 * to identify a conversation, and naming that type here would owe the runtime a dependency.
 *
 * <p>THE PAYLOADS ARRIVE SHORTENED, because a listener is for watching. Anything that wants a tool
 * call whole records it at the executor instead, which is what {@code Recording} does and why
 * {@link JsonlTrace}'s implementation of this is deliberately empty: writing the shortened
 * duplicate would double the file and lose the argument that matters.
 */
public interface ToolWatching {

    /**
     * One tool call, after it returned.
     *
     * @param context      the label the agent was built with, e.g. {@code agent:doer}
     * @param toolName     what the model asked for, falling back to the specification's own name
     * @param memoryId     the conversation the call belongs to, as the agent runtime knows it
     * @param argumentsTruncated the model's raw JSON arguments, shortened
     * @param resultTruncated    what the executor answered, shortened, and never null
     */
    void onToolInvocation(String context, String toolName, Object memoryId,
                          String argumentsTruncated, String resultTruncated);
}
