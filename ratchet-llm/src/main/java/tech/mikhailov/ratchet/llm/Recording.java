package tech.mikhailov.ratchet.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.mikhailov.ratchet.record.Retained;
import tech.mikhailov.ratchet.record.Trace;
import tech.mikhailov.ratchet.record.ToolWatching;

/**
 * EVERY TOOL CALL IN THE RECORD, WHOLE, WRITTEN AT THE EXECUTOR.
 *
 * <p>{@link ToolWatching} is for watching and its payloads arrive shortened, which is right for a
 * listener and wrong for a corpus: the argument to an edit tool IS the work the agent did, and a
 * record that truncated it would be a record nothing can be replayed or trained from. So the whole
 * call is written here, one layer lower, where the tool has it in full.
 *
 * <p>WHOLE INTO THE RECORD, BOUNDED BACK INTO THE PROMPT, and those are two different numbers on
 * purpose. An agent's context grows monotonically across its tool calls and every later call
 * re-prefills all of it, so an unbounded result is not paid once, it is paid again on every call
 * that follows. A generated file or a vendored source is exactly the read that does this.
 *
 * <p>IT IS HERE BECAUSE THE HOLE WAS DOCUMENTED AND THEN FILLED TWICE, DIFFERENTLY. One caller
 * truncated what went back and recorded a throw before rethrowing it; the other did neither, and
 * its own comment says what that cost: two units of work were postponed with correct reasons and
 * the trace recorded no postpone call at all. An action that leaves no record is the one thing a
 * record must not permit.
 */
public final class Recording {

    private Recording() {
    }

    /**
     * The same tools, each writing itself into the trace as it answers.
     *
     * <p>THE DECLARED ORDER IS KEPT, for the reason {@link Asking} keeps it: the order tools are
     * advertised in should be the order somebody wrote them down, not an accident of hashing.
     *
     * @param maxResult what may travel back to the model. The record always gets the whole thing;
     *                  pass {@link Integer#MAX_VALUE} for a caller whose results are small enough
     *                  that bounding them buys nothing.
     */
    public static Map<Tool, Calling> at(Map<Tool, Calling> tools, Trace trace, String agent,
                                        int maxResult) {
        return at(tools, trace, agent, maxResult, Spilling.none());
    }

    /**
     * THE SAME, WITH SOMEWHERE FOR THE REST TO GO.
     *
     * <p>The record has always kept the whole result and the prompt has always got a bound. What
     * was missing was a way to tell the model WHERE the rest is: a magnitude says something is
     * gone, a locator lets an agent go and get it, and the difference decides whether it asks again
     * or guesses. See {@link Spilling} — the store is the caller's, because where a result goes is
     * a fact about their filesystem and their retention and not about this library.
     */
    public static Map<Tool, Calling> at(Map<Tool, Calling> tools, Trace trace, String agent,
                                        int maxResult, Spilling spilling) {
        Map<Tool, Calling> wrapped = new LinkedHashMap<>();
        if (tools == null) {
            return wrapped;
        }
        tools.forEach((tool, doing) -> wrapped.put(tool, call -> {
            try {
                String result = doing.run(call);
                // Recorded whole, returned bounded: the corpus wants everything, the prompt does not.
                trace.tool(agent, tool.name(), call.arguments(), result);
                // THE SIXTH AND LAST HAND-ROLLED MARKER. This one was the best of them — the only
                // one that told the reader what to DO about the loss — and that half survives as
                // the recovery clause. The sentence is shared; the guidance stays this call site's,
                // because only it knows that narrowing the request is the way to get the rest.
                if (result == null || result.length() <= maxResult) {
                    return result;
                }
                return (spilling == null ? Spilling.none() : spilling).kept(result, maxResult);
            } catch (RuntimeException threw) {
                // A THROW IS AN EVENT, NOT AN ABSENCE. Asking catches this and hands the message to
                // the model as the call's result, so without this line the model is told something
                // the record never mentions.
                trace.tool(agent, tool.name(), call.arguments(),
                        "threw " + threw.getClass().getSimpleName() + ": " + threw.getMessage());
                throw threw;
            }
        }));
        return wrapped;
    }
}
