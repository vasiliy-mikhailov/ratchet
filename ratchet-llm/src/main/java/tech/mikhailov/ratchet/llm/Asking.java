package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.record.ToolWatching;

/**
 * ONE AGENT: A SYSTEM PROMPT, A CLOSED SET OF TOOLS, AND A QUESTION ASKED UNTIL IT IS ANSWERED.
 *
 * <p>THIS USED TO BE CONFIGURATION AND IS NOW THE LOOP ITSELF. A third party owned the loop, so
 * this class was a handful of builder calls; owning it costs about fifteen lines, which is the
 * measurement that decided the dependency. What that jar carried besides the loop — a todo list,
 * skills, a memory file, an orchestrator that delegates to sub-agents — is what
 * {@link tech.mikhailov.ratchet.flow.Flow#triad} and the flow around it were built to stand in for.
 * A batteries-included harness assumes a model clever enough to choose its own trajectory; the
 * structure in this library is what it substitutes for that assumption, so the parts that were
 * never used were never missing.
 *
 * <p>NOTHING SURVIVES BETWEEN CALLS. Every {@link #run} is a fresh two-message conversation: the
 * system prompt, then the task. An agent asked twice in one run is asked twice from nothing, and
 * what it knows the second time is whatever its tools can tell it.
 *
 * <p>NO PROMPT TEMPLATING, WHICH IS A BEHAVIOUR THIS DROPS ON PURPOSE. Both strings used to be
 * rendered as templates, so a {@code {{name}}} anywhere in a system prompt or a task was either
 * substituted from the current date or was a fatal error naming the variable. No prompt in this
 * program contains one, and the failure mode — a run dying because a model's own output was quoted
 * back into a later prompt with braces in it — is worse than the feature.
 *
 * <p>TOOLS ARE EXECUTED SEQUENTIALLY, in the order the model asked for them, on the calling thread:
 * nothing here runs two tools at once even when one assistant turn asks for a dozen.
 */
public final class Asking implements Agent {

    /**
     * TWENTY-FIVE ROUNDS, AND THE TWENTY-SIXTH THROWS. THE NUMBER AND ITS SHAPE ARE BOTH PRESERVED.
     *
     * <p>Neither was invented here. The jar this replaces passed exactly 25, and its loop had a
     * particular shape that the round count alone does not describe: the first response is fetched
     * BEFORE the loop, and each pass decrements a counter and throws at zero BEFORE testing whether
     * the response even asked for a tool. So at most 25 responses are processed, an answer is
     * returned only if it arrives as the twenty-fifth or earlier, and the twenty-sixth response is
     * fetched and thrown away. Confirmed against the bytecode of {@code ToolService} rather than
     * from its documentation, because this is the kind of contract a minor version changes quietly.
     *
     * <p>IT BINDS, AND IT BINDS OFTEN. That exact message appears 60,173 times in one corpus's own
     * record, and in one results tree 59 of 208 runs hit it at least once. Both callers catch the
     * RuntimeException, record "unreachable" against the agent, and re-ask the whole question
     * through the thinking-off retry model, so every firing costs a second conversation. The bound
     * counts ROUNDS and not calls, despite its name: one assistant message asking for five tools
     * costs one, and the busiest recorded conversation fitted 465 calls inside this budget.
     *
     * <p>THE WASTED TWENTY-SIXTH CALL IS NOW VISIBLE AND IS STILL MADE. It costs a whole model
     * response, on a conversation that is by then at its longest, and this library can finally see
     * it. Changing it is a change to what more than a quarter of runs do, and doing that silently
     * while removing a dependency is exactly how a migration hides a regression. It is a decision to
     * take deliberately and measure.
     */
    private static final int MAX_ROUNDS = 25;

    /** What a listener is shown of an argument or a result. It is for watching, not for the record. */
    private static final int MAX_WATCHED = 8_000;

    private final Chat model;
    private final String systemPrompt;
    private final Map<String, Calling> byName;
    private final List<Tool> advertised;
    private final String label;
    private final ToolWatching listener;

    /**
     * @param model        who answers; a critic and a producer are given different ones
     * @param systemPrompt what the model is told before the question, in full
     * @param tools        every tool this agent may call, and no others
     * @param label        who is calling, for the listener, e.g. {@code agent:survey-doer}
     * @param listener     told about each call, or null when nobody is watching
     */
    public Asking(Chat model, String systemPrompt, Map<Tool, Calling> tools, String label,
                  ToolWatching listener) {
        this.model = model;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.label = label == null || label.isBlank() ? "agent" : label;
        this.listener = listener;
        // THE DECLARED ORDER IS KEPT. What this replaces copied the map into an unordered one on the
        // way in, so the order tools were advertised in was an accident of hashing; here it is the
        // order the caller declared them, which is the order a reader of that declaration expects.
        Map<String, Calling> wired = new LinkedHashMap<>();
        List<Tool> offered = new ArrayList<>();
        if (tools != null) {
            tools.forEach((tool, doing) -> {
                // TWO TOOLS SHARING A NAME USED TO COLLIDE IN SILENCE — the client keyed executors
                // by name while advertising the specifications as a list, so the last writer won and
                // both were still offered to the model. There was nowhere to catch it. Here there is.
                if (wired.put(tool.name(), doing) != null) {
                    throw new IllegalArgumentException("two tools are called " + tool.name()
                            + "; the model would be offered both and only one could ever run");
                }
                offered.add(tool);
            });
        }
        this.byName = wired;
        this.advertised = List.copyOf(offered);
    }

    /**
     * The final assistant text, WHICH CAN BE EMPTY.
     *
     * <p>A model that ends its turn with tool calls and no content answers nothing at all, and that
     * is an empty judgement rather than a failure: {@link Insisting} is what reads it as one and
     * asks again. Throws whatever the model call or the round bound throws, which callers catch and
     * record rather than let end the run.
     */
    @Override
    public String run(String task) {
        List<Said> conversation = new ArrayList<>();
        conversation.add(Said.system(systemPrompt));
        conversation.add(Said.user(task));

        Reply reply = model.answer(new Ask(conversation, advertised, label));
        int left = MAX_ROUNDS;
        while (true) {
            // BEFORE THE TOOL TEST, not after it. See MAX_ROUNDS: this ordering is the whole
            // difference between an answer on the twenty-fifth round being returned and being lost.
            if (left-- == 0) {
                // A TYPE, SO THE RETRY CAN REFUSE IT. This was a bare IllegalStateException, and
                // transportFailures() retries what it does not recognise — deliberately, since the
                // cost of retrying something hopeless is one bounded sequence. That reasoning does
                // not hold here: the retry re-runs all twenty-five rounds from nothing, so ten
                // attempts is two hundred and fifty rounds of model calls to reach the same wall.
                // The message is unchanged and the type still extends IllegalStateException, so
                // every caller that catches this keeps catching it.
                throw new Exhausted("Something is wrong, exceeded " + MAX_ROUNDS
                        + " sequential tool executions");
            }
            if (!reply.wantsTools()) {
                return reply.said();
            }
            conversation.add(Said.assistant(reply.said(), reply.calls()));
            for (Called call : reply.calls()) {
                conversation.add(Said.result(call, ran(call)));
            }
            reply = model.answer(new Ask(conversation, advertised, label));
        }
    }

    /**
     * ONE CALL, AND THE TWO FAILURES THAT LOOK ALIKE AND ARE NOT ALIKE AT ALL.
     *
     * <p>A tool that EXISTS and goes wrong is reported back to the model as that call's result, so
     * the conversation carries on. A tool the model INVENTED raises, and the raise leaves
     * {@link #run} with the ask lost — which is why a tool a phase needs is written rather than
     * assumed. Both were the previous runtime's behaviour and both are pinned by tests; neither is
     * changed here.
     */
    private String ran(Called call) {
        Calling doing = byName.get(call.name());
        if (doing == null) {
            throw new IllegalArgumentException("the model called a tool that does not exist: "
                    + call.name() + ". This agent was given " + byName.keySet());
        }
        String result;
        try {
            result = doing.run(call);
        } catch (RuntimeException threw) {
            // Handed to the model rather than propagated. Answering with a written sentence instead
            // of throwing is still the better habit, because a sentence composed for a reader beats
            // whatever a stack trace's first line happens to say.
            result = threw.getMessage() == null ? threw.getClass().getSimpleName()
                    : threw.getMessage();
        }
        String answered = result == null ? "" : result;
        if (listener != null) {
            // The listener is told "" for a null result; the model is told exactly what the tool
            // returned, null included, because that is what it returned.
            listener.onToolInvocation(label, call.name(), null, shortened(call.arguments()),
                    shortened(answered));
        }
        return answered;
    }

    private static String shortened(String text) {
        return text.length() <= MAX_WATCHED
                ? text
                : text.substring(0, MAX_WATCHED) + "... (truncated, total " + text.length()
                        + " chars)";
    }
}
