package tech.mikhailov.ratchet.llm;

import java.util.function.Supplier;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.record.Trace;

/**
 * AN EMPTY ANSWER IS NOT A JUDGEMENT, AND THIS IS WHAT REFUSES TO READ IT AS ONE.
 *
 * <p>A model that ends its turn with tool calls and no content answers nothing at all. Every closed
 * word list in this design falls back to its first word, and the first word of a critic's list is
 * the approving one, so silence approves. That is the one reading of silence that loses work, and
 * it is not a hypothetical on a small local model: a blank means the reasoning entered a cycle
 * greedy decoding cannot leave.
 *
 * <p>SO THE RETRY ASKS A MODEL THAT DOES NOT REASON AT ALL. Instructing the thinking model to be
 * brief instead was measured to make the second attempt WORSE than the first: 80% runaway against a
 * 62.5% control. Thinking off measured 0 of 10 runaway, at 340 tokens and 17 seconds. See
 * {@link Model#forRetry}.
 *
 * <p>IT IS HERE BECAUSE IT WAS WRITTEN TWICE AND THE TWO COPIES DIFFERED. One caller recorded the
 * blank attempt in the trace and labelled the retry as a retry; the other did neither, and its own
 * comment says what that cost: it "ran for the better part of an hour with no credentials at all,
 * and every model call threw, was caught, and was returned as an empty answer. An empty answer from
 * a supervisor reads as 'nothing is wrong', which is the most expensive thing it could possibly say
 * by mistake." Both authors remembered the same rule and remembered it differently. A library whose
 * javadoc specifies a protocol it does not implement is an invitation to do that again.
 */
public final class Insisting {

    private Insisting() {
    }

    /**
     * The same agent, asked once more when it says nothing.
     *
     * <p>A DECORATOR, like a resumable node, so nothing else changes shape. It returns an
     * {@link Agent} and takes one, and a caller that never blanks cannot tell it is there.
     *
     * @param name         what to file both attempts under in the trace
     * @param systemPrompt what the agent was told, because a wrapper cannot see inside an
     *                     {@link Asking} and the trace records the pair, prompt and task together
     * @param first        the agent to ask, thinking and all
     * @param whenSilent   how to build the retry, asked for only when it is needed: most calls
     *                     never blank, and building it eagerly constructs a second model client per
     *                     agent per run for nothing
     * @param trace        where both attempts are recorded
     */
    public static Agent on(String name, String systemPrompt, Agent first,
                           Supplier<Agent> whenSilent, Trace trace) {
        return task -> {
            String reply;
            try {
                reply = first.run(task);
            } catch (RuntimeException unreachable) {
                // AN UNREACHABLE MODEL WITHHOLDS, AND SAYS SO. A critic that withholds waives; a
                // producer that withholds has done nothing, and whatever judges next will say so.
                // What must not happen is the exception being swallowed into a silence nobody can
                // tell apart from an opinion.
                reply = "";
                trace.progress("", name + " unreachable: " + unreachable.getMessage());
            }
            reply = reply == null ? "" : reply;
            if (reply.isBlank()) {
                // BOTH ATTEMPTS ARE RECORDED, including the one that said nothing. A retry that
                // overwrote the blank would leave a record in which the failure never happened.
                trace.asked(name, systemPrompt + "\n\n---\n\n" + task, "");
                trace.progress("", name + " answered nothing; asking once more without thinking");
                try {
                    reply = whenSilent.get().run(task);
                } catch (RuntimeException stillNothing) {
                    reply = "";
                }
                reply = reply == null ? "" : reply;
            }
            trace.asked(name, systemPrompt + "\n\n---\n\n" + task, reply);
            return reply;
        };
    }
}
