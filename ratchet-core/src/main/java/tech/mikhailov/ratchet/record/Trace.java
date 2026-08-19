package tech.mikhailov.ratchet.record;

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
    record Outcome(boolean infra, boolean passed, String summary) {
    }

    /** What went, what came back, what it cost. Summaries: the full conversation is not kept. */
    record Exchange(String direction, String agent, int messages, String sent, String got,
                    String tools, String finish, long inTokens, long outTokens, long ms,
                    String error) {
    }
}
