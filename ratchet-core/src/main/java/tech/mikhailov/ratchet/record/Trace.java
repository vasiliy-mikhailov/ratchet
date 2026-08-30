package tech.mikhailov.ratchet.record;

import java.util.List;

/**
 * EVERYTHING THAT HAPPENS, THROUGH ONE OBJECT.
 *
 * <p>Injected once and handed to every stage, so nothing in this design prints, appends or logs on
 * its own. A stage that writes its own line decides its own format, and a reader assembling a run
 * out of six formats is doing archaeology.
 *
 * <p>{@link #asked} CARRIES THE PAIR, UNTRUNCATED. Prompt tuning replays a recorded (prompt, reply)
 * pair and scores the reply, so a trace that abbreviates either one is a trace nothing can be
 * trained from. This is the whole reason the interface exists rather than a logger.
 *
 * <p>THE DISTINCTION THAT MUST SURVIVE: {@link #built} and {@link #applied} report facts, and
 * {@link #asked} reports an opinion. A reader who cannot tell which of the two decided a settlement
 * cannot audit it.
 */
public interface Trace {

    /** A model call and its answer, both in full. The unit prompt training replays. */
    void asked(String agent, String prompt, String reply);

    /** A deterministic stage did something: a rewrite ran, a floor landed, a wall was fixed. A fact. */
    void applied(String stage, String what);

    /** A tool an agent used, payloads in full: the argument to an edit tool IS the work it did. */
    void tool(String agent, String tool, String arguments, String result);

    /**
     * WHAT HAS ALREADY HAPPENED IN THIS RUN, readable by the agents living inside it.
     *
     * <p>The trace records every stage, every answer, every objection and every tool result, and
     * until now it was written for the corpus and for people, never for the run itself. So a loop
     * deciding what to try next could be told what LANDED, from the workspace, and never what was
     * tried and rejected -- which is the more useful half. A troubleshooter that already
     * established that a dependency cannot be used should not rediscover it, and a proposer
     * ordering a fourth variation on a step three critics have already refused is not reasoning,
     * it is looping.
     *
     * <p>Returns an empty string rather than throwing when nothing is readable: an agent that asks
     * what happened and gets an exception loses its turn.
     */
    default String happened(String stage, String agent, int limit) {
        // 180 CHARACTERS A LINE, WHICH IS WHAT THIS METHOD HAS ALWAYS DONE. It is preserved
        // rather than defended: a consumer measured it costing 83% of all bytes read in re-fetches,
        // because an agent shown a stub of an earlier agent's tool result goes and reads the file
        // again. It is not raised here because a longer summary is paid by every consumer, and the
        // door below is what lets the one who measured it choose. See Telling.
        return happened(stage, agent, limit, Telling.upTo(180));
    }

    /**
     * HOW MANY EVENTS THIS RUN HAS, WHICH IS THE CALL THAT MAKES THE REST SAFE.
     *
     * <p>{@link #happened} is a one-shot summary: it takes a line budget and a per-line budget,
     * ranks, clips, and hands back a fixed lump. Every number in it is a guess made by the caller
     * about what an agent will need BEFORE the agent has said what it needs. 180 was a bad guess
     * and 8,000 is a better one; both are guesses, and both are paid on every call whether or not
     * the agent wanted that line.
     *
     * <p>THESE THREE ARE NAVIGATION INSTEAD, so the agent sizes its own read and the bound stops
     * being a number in this library. It becomes the range the caller asked for — the shape a file
     * read already has, where nobody bounds the read because the reader named the file. An agent
     * told there are 1,400 events can ask for forty of them; an agent that searches for one
     * dependency has bounded itself by being specific.
     *
     * <p>THE NAMES ALL BEGIN WITH {@code trace} ON PURPOSE. A consumer exposes these to a model as
     * tools, and the model already has {@code grep} and {@code read_file} pointed at a repository.
     * A bare {@code grep} beside those is a collision that costs exactly the wasted calls this
     * exists to remove — one consumer's corpus has 22,423 repository greps in it.
     *
     * <p>{@link #happened} stays, and stays the right call for "I have no idea what happened, show
     * me the shape of it". It is no longer the only way in, and its bound is no longer load-bearing.
     *
     * @param stage narrows to one stage, or blank for all
     * @param agent narrows to one agent, or blank for all
     */
    default int traceEvents(String stage, String agent) {
        return 0;
    }

    /**
     * EVENTS {@code from} UP TO BUT NOT INCLUDING {@code to}, WHOLE AND UNCLIPPED.
     *
     * <p>Indices are positions within the narrowing, so {@code traceSlice(stage, agent, 0, n)}
     * pairs with {@code traceEvents(stage, agent)} and a walk backwards from the end is
     * {@code traceSlice(s, a, count - 40, count)}. They are stable within a run because the record
     * is append-only; they are NOT stable across a differently narrowed call, which is why the
     * count comes from the same two arguments.
     *
     * <p>Out-of-range ends are clamped rather than refused: an agent walking backwards should not
     * have to do arithmetic to avoid an exception.
     */
    default List<Event> traceSlice(String stage, String agent, int from, int to) {
        return List.of();
    }

    /**
     * EVENTS WHOSE TEXT CONTAINS {@code needle}, WHOLE, NEWEST FIRST, AT MOST {@code most} OF THEM.
     *
     * <p>A LITERAL SUBSTRING, CASE-INSENSITIVE, AND NOT A REGULAR EXPRESSION. The pattern here is
     * written by a model, and a model-written regex over a 44,000-character row can backtrack for
     * an unbounded time with no way to stop it from Java. A literal match cannot, and it is what a
     * model asking "what did the earlier stage say about this dependency" actually wants.
     *
     * <p>{@code most} IS THE CALLER'S BOUND AND IT IS REQUIRED, which is the one place this differs
     * from the proposal it came from. The argument that a search bounds itself by being specific
     * holds for a person and not for a model: a needle of {@code "e"} has named every row. Naming a
     * ceiling is the caller bounding itself rather than this library guessing, which is the whole
     * point of the three.
     */
    default List<Event> traceFind(String needle, String stage, String agent, int most) {
        return List.of();
    }

    /**
     * ONE RECORDED EVENT, STRUCTURED, so a caller can filter without a regex over rendered text.
     *
     * <p>{@link #happened} flattens newlines into a ribbon and prefixes each row, which is a
     * RENDERING decision. It should not be baked into the only way to read the record.
     *
     * @param at    position within the narrowing that produced it
     * @param kind  {@code asked}, {@code tool}, {@code applied}, {@code progress}, {@code built},
     *              {@code settled}, {@code thought} or {@code priced}
     * @param text  the whole of what that row carried, unflattened and unclipped
     */
    record Event(int at, String kind, String stage, String agent, String text) {
    }

    /**
     * THE SAME, WITH HOW MUCH OF EACH LINE SURVIVES CHOSEN BY THE CALLER.
     *
     * <p>The per-line bound was 180 characters, a private literal in {@link JsonlTrace} that no
     * caller could reach — in the one method on this interface that has no caller inside this
     * library at all. See {@link Telling} for what that cost the consumer who found it.
     */
    default String happened(String stage, String agent, int limit, Telling telling) {
        return "";
    }

    /**
     * The reasoning behind an answer, and why the answer ended.
     *
     * <p>An opinion like {@link #asked}, and the one that explains it. Recorded separately because
     * the runtime returns only the content: without this the thinking is paid for and discarded,
     * and an answer that ended mid-thought is indistinguishable from one that declined.
     */
    void thought(String finishReason, String thinking, String content);

    /** A check that either passed or did not, under a named condition. The only arbiter here. */
    void built(String phase, Outcome result);

    /** What the run became, the argument for it, and what the checks actually did. */
    void settled(String key, String state, String because, boolean beforeOk, boolean afterOk);

    /**
     * The same, saying whether this attempt picked up a killed one rather than starting fresh.
     *
     * <p>A RESUMED RUN IS NOT THE SAME TRIAL AS A FRESH ONE. It carries a different budget history
     * and possibly a different order of work, so a comparison over the corpus has to be able to
     * leave it out. A run whose conditions differ is a different experiment, whether or not anyone
     * meant it to be.
     *
     * <p>A DEFAULT, so that a trace double written to answer a question about something else does
     * not have to grow a method. Dropping the flag loses a filter; refusing the row would lose the
     * settlement.
     */
    default void settled(String key, String state, String because, boolean beforeOk,
                         boolean afterOk, boolean resumed) {
        settled(key, state, because, beforeOk, afterOk);
    }

    /** The run did not finish. A dropped connection must not look like nothing having happened. */
    void failed(String key, Throwable cause);

    /** Where the run is up to, for anything watching while it goes. */
    void progress(String key, String note);

    /** What the same work would have cost a person, and the itemisation behind the number. */
    void priced(String key, String minutes, String itemisation);

    /**
     * ONE EXCHANGE WITH THE MODEL, AS THE CLIENT SAW IT.
     *
     * <p>Everything else on this interface is the harness reporting what it chose to report. This
     * is the wire: recorded by a listener under all of it, so the record holds what happened rather
     * than what somebody remembered to save. It carries the two things the curated events could not
     * -- the server's own token counts, and which agent produced a given piece of reasoning.
     *
     * <p>A DEFAULT, deliberately. A trace double in a test exists to answer a question about
     * something else, and should not have to grow a method every time the wire learns a new fact.
     */
    default void exchanged(Exchange exchange) {
    }

    /**
     * WHAT A CHECK DID, in the three facts a record is entitled to.
     *
     * <p>{@link #built} used to take the build runner's own result type, and that one parameter was
     * the whole reason a general record writer could not exist without the shelling-out coming with
     * it. The three facts are about nobody's domain: a thing ran or it did not, it passed or it did
     * not, and here is what it said.
     *
     * <p>A RECORD RATHER THAN THREE ARGUMENTS. The alternative was
     * {@code built(phase, infra, passed, summary)}, and two adjacent booleans in a call nobody
     * reads twice is a transposition waiting to happen: swap them and every infrastructure failure
     * in the corpus records as a passing build, silently, with no compiler to say so.
     */
    /**
     * A TRACE THAT KEEPS NOTHING, so that "not recording" is a value rather than a null.
     *
     * <p>THREE PLACES IN THIS LIBRARY GUARDED AGAINST A NULL TRACE and did it three different
     * ways — {@code Retrying} returned early, {@code Wire} refused to build a listener, {@code
     * Reasoning} folded the check into an unrelated condition. Every one of those is the same
     * decision written again, and each is a line that can only ever be wrong: a fourth caller that
     * forgets it gets a NullPointerException from inside a recording path, which is the one place
     * this project has said repeatedly must never break the run it is recording.
     *
     * <p>FOURTEEN TEST FILES ALSO IMPLEMENTED THIS INTERFACE by hand, at nine empty methods each,
     * to say the one thing this constant says. That is around 560 lines whose entire content is
     * "no thank you".
     *
     * <p>IT IS NOT A NULL OBJECT SMUGGLED IN AS A DEFAULT. Nothing here makes it the default trace,
     * and it must not become one: a run whose record is empty because somebody forgot to pass a
     * real trace looks exactly like a run that had nothing to say, and telling those apart is what
     * the record is for. This is for a caller who has decided, and for a test that is asserting
     * something else.
     */
    static Trace quiet() {
        return new Trace() {
            @Override
            public void asked(String agent, String prompt, String reply) {
            }

            @Override
            public void applied(String stage, String what) {
            }

            @Override
            public void tool(String agent, String tool, String arguments, String result) {
            }

            @Override
            public void thought(String finishReason, String thinking, String content) {
            }

            @Override
            public void built(String phase, Outcome result) {
            }

            @Override
            public void settled(String key, String state, String because, boolean beforeOk,
                                boolean afterOk) {
            }

            @Override
            public void failed(String key, Throwable cause) {
            }

            @Override
            public void progress(String key, String note) {
            }

            @Override
            public void priced(String key, String minutes, String itemisation) {
            }
        };
    }

    record Outcome(boolean infra, boolean passed, String summary) {
    }

    /** What went, what came back, what it cost. Summaries: the full conversation is not kept. */
    record Exchange(String direction, String agent, int messages, String sent, String got,
                    String tools, String finish, long inTokens, long outTokens, long ms,
                    String error) {
    }
}
