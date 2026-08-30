package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.record.Retained;
import tech.mikhailov.ratchet.record.Telling;
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

    /** How much of a call and its answer the watcher is shown. It is for watching, not the record. */
    private final Telling watched;

    /** What this agent may spend before it is told it will not finish. See {@link Budget}. */
    private final Budget budget;

    /** What each request carries, which is the caller's between turns. See {@link Between}. */
    private final Between between;

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
        this(model, systemPrompt, tools, label, listener, Telling.upTo(8_000), Budget.shipped());
    }

    /**
     * THE SAME, WITH HOW MUCH THE WATCHER IS SHOWN CHOSEN BY THE CALLER.
     *
     * <p>{@code MAX_WATCHED} was a private literal here and a listener could not ask for more. It
     * is the third bound in this library a consumer reported having no door to, after the record's
     * and the summary's, and it is the one that collides with them: a consumer who asks the record
     * to keep everything still got 8,000 here, and the two clips are not distinguishable from the
     * record afterwards.
     *
     * <p>The kinds are {@code arguments} and {@code result}, so the call a tool was given and the
     * answer it gave can be bounded apart.
     */
    public Asking(Chat model, String systemPrompt, Map<Tool, Calling> tools, String label,
                  ToolWatching listener, Telling watched) {
        this(model, systemPrompt, tools, label, listener, watched, Budget.shipped(),
                Between.whole());
    }

    /**
     * THE SAME, WITH WHAT THE AGENT MAY SPEND CHOSEN BY THE CALLER.
     *
     * <p>The door that replaces a private {@code MAX_ROUNDS = 25}. See {@link Budget} for why the
     * unit changed as well as the number.
     */
    public Asking(Chat model, String systemPrompt, Map<Tool, Calling> tools, String label,
                  ToolWatching listener, Telling watched, Budget budget) {
        this(model, systemPrompt, tools, label, listener, watched, budget, Between.whole());
    }

    /**
     * THE SAME, WITH WHAT EACH REQUEST CARRIES CHOSEN BY THE CALLER BETWEEN TURNS.
     *
     * <p>The seam compaction sits on. This library does not compact — see {@link Between} — because
     * when, what and how much all need things only the caller can see.
     */
    public Asking(Chat model, String systemPrompt, Map<Tool, Calling> tools, String label,
                  ToolWatching listener, Telling watched, Budget budget, Between between) {
        this.between = between == null ? Between.whole() : between;
        this.budget = budget == null ? Budget.shipped() : budget;
        this.watched = watched == null ? Telling.upTo(8_000) : watched;
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
     * asks again.
     *
     * <p>THE LOOP RUNS UNTIL THE MODEL STOPS ASKING FOR TOOLS, OR THE CALLER'S {@link Budget} IS
     * SPENT. The 25 rounds that stood here was inherited from the jar this library replaced rather
     * than chosen, counted rounds rather than calls, and stopped more than a quarter of runs from
     * finishing; the same task family completes in a couple of hours against a loop with no round
     * bound at all, on this same model.
     *
     * <p>Two things throw {@link Exhausted}, and they say different things. A spent budget means
     * this agent cost more than the caller allowed. Two turns in a row cut off mid tool call means
     * the CONVERSATION no longer fits, so asking for less cannot help — which is a fact about the
     * context window rather than about the work, and is what compaction will remove.
     */
    @Override
    public String run(String task) {
        List<Said> conversation = new ArrayList<>();
        conversation.add(Said.system(systemPrompt));
        conversation.add(Said.user(task));

        Reply reply = model.answer(new Ask(sending(conversation), advertised, label));
        int wallHits = 0;
        int turns = 1;
        long spent = reply.spend().prompt() + reply.spend().completion();
        while (true) {
            // BEFORE wantsTools(), WHICH IS FALSE HERE AND WOULD END THE AGENT. Wire refuses a call
            // cut in half by the token wall, so the turn asked for a tool and has none to run. Left
            // to the check below, that reads as an ordinary answer and returns whatever prose the
            // model had managed — silently ending an agent mid-task on a truncation.
            //
            // The turn is kept, without its calls, and the model is told what happened. Nothing is
            // thrown: the lane continues, which is the point.
            if (reply.cutMidCall()) {
                // THE ONLY BOUND LEFT, AND IT BOUNDS THE DEAD END RATHER THAN THE WORK.
                //
                // Asking for less works when one turn was greedy. It cannot work when the
                // CONVERSATION is at the wall, because every turn from then on is cut in the same
                // place however small the ask — so this would spin, telling a model to be shorter
                // that has no room to be shorter in. Twice is enough to tell those apart: once is a
                // greedy turn, twice in a row is a context that no longer fits.
                //
                // It says the true cause. The bound it replaces said "exceeded 25 sequential tool
                // executions", which was never why the work stopped: 25 was inherited from the jar
                // this library replaced, it counted rounds and not calls — one turn asking for five
                // tools cost one, and the busiest recorded conversation fitted 465 calls inside it —
                // and it fired 60,173 times in one corpus, on 59 of 208 runs, each firing costing a
                // second whole conversation through the re-ask. The same task family completes in
                // hours against a loop with no round bound at all, on this same model. A cap that
                // stops a quarter of runs from finishing is not protecting anyone from a runaway.
                //
                // What actually bounds an agent is context, and the answer to context is compaction,
                // which is SPEC-context.md section 5 and not yet built. Until it is, this reports
                // the wall honestly instead of blaming a round count for it.
                if (++wallHits >= 2) {
                    throw new Exhausted("the conversation no longer fits: two turns in a row were "
                            + "cut off mid tool call, so asking for less cannot help. Compact the "
                            + "history, shorten the system prompt, or raise the context window.");
                }
                conversation.add(Said.assistant(reply.said(), List.of()));
                conversation.add(Said.user("That turn ran out of room while writing "
                        + reply.dropped() + (reply.dropped() == 1 ? " tool call" : " tool calls")
                        + ", so it was not sent. Ask for less in one call, or split the work."));
                reply = model.answer(new Ask(sending(conversation), advertised, label));
                turns++;
                spent += reply.spend().prompt() + reply.spend().completion();
                continue;
            }
            wallHits = 0;
            if (!reply.wantsTools()) {
                return reply.said();
            }
            conversation.add(Said.assistant(reply.said(), reply.calls()));
            for (Called call : reply.calls()) {
                conversation.add(Said.result(call, ran(call)));
            }
            // THE CALLER'S CEILING, ON WHAT IS ACTUALLY SPENT. A model that never stops asking
            // for tools is a real failure and nothing else here catches it: the wall guard above
            // fires only on a truncation, and a scripted model — or a real one calling a cheap tool
            // that returns almost nothing — never truncates at all. Removing the round cap without
            // this put a test JVM into an infinite loop and killed it with a heap error, which is
            // the clearest argument for the bound existing that anyone offered.
            //
            // CHECKED HERE, AFTER THE TOOLS RAN AND BEFORE ANOTHER TURN IS PAID FOR. At the top of
            // the loop it would refuse a turn already fetched — which is exactly the waste the
            // bound it replaces was criticised for: 25 rounds meant the twenty-sixth answer was
            // bought, on the longest conversation of the run, and thrown away unread.
            if (budget.spent(spent, turns)) {
                throw new Exhausted("this agent spent " + spent + " tokens over " + turns
                        + " turns without finishing, which is past what the caller allowed. Raise "
                        + "the Budget, or look at what it is repeating.");
            }
            reply = model.answer(new Ask(sending(conversation), advertised, label));
            turns++;
            spent += reply.spend().prompt() + reply.spend().completion();
        }
    }

    /**
     * WHAT THIS REQUEST CARRIES, WHICH IS NOT NECESSARILY THE WHOLE CONVERSATION.
     *
     * <p>The result is used for one request and never stored: the conversation goes on growing, so
     * the next turn hands the caller everything again and it can decide afresh. A caller that drops
     * nothing gets exactly the behaviour that existed before this seam.
     *
     * <p>A caller that hands back nothing at all is refused rather than obeyed. An empty request is
     * not a smaller request, and the failure it produces — a model answering with no system prompt
     * and no task — would be attributed to the model rather than to the policy that caused it.
     */
    private List<Said> sending(List<Said> conversation) {
        List<Said> carrying = between.turn(List.copyOf(conversation));
        if (carrying == null || carrying.isEmpty()) {
            throw new IllegalStateException("a Between returned "
                    + (carrying == null ? "null" : "an empty conversation")
                    + " for an agent that has said " + conversation.size() + " things. Compaction "
                    + "shortens what is sent; it does not remove the question.");
        }
        return carrying;
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
            listener.onToolInvocation(label, call.name(), null,
                    shortened("arguments", call.arguments()), shortened("result", answered));
        }
        return answered;
    }

    private String shortened(String kind, String text) {
        return Retained.head(text, Math.max(1, watched.room(kind, text)), "").text();
    }
}
