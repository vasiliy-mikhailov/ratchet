package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import tech.mikhailov.ratchet.record.Trace;

/**
 * ASKS AGAIN WHEN THE ENDPOINT DROPS, BECAUSE THE STAGE IS THE UNIT OF LOSS AND IT IS A LARGE ONE.
 *
 * <p>The journal preserves exactly one wrapped node. Nothing inside a node survives: not a triad's
 * plan, not a round that finished, not a verifier's verdict. So a connection reset in the middle of
 * a six-round triad does not cost one call, it costs every call that node had already paid for —
 * measured on this library at seven model calls destroyed by a failure on round three, with the
 * journal file never created because {@code Flow.resumable} only writes its row once the node
 * returns. Re-asking the model is cheap next to re-paying for that.
 *
 * <p>IT SITS BENEATH {@link Insisting}, and that ordering is the whole reason this class helps.
 * {@code Insisting} catches a throwing model and reads it as an empty answer, which its own javadoc
 * records the cost of: a run that "ran for the better part of an hour with no credentials at all,
 * and every model call threw, was caught, and was returned as an empty answer." Retrying below it
 * means a dropped connection is a dropped connection, retried as one, and only a model that is
 * genuinely still answering nothing after every attempt reaches the layer that judges silence.
 *
 * <p>EVERYTHING IS HANDED IN. The schedule, the wait, the count and the question of what is worth
 * retrying are all constructor arguments, so each can be tested without a socket and the whole
 * ten-attempt schedule can be asserted in milliseconds. {@link Model} is the only place that
 * chooses the production values.
 */
public final class Retrying implements Chat {

    /**
     * WRAP A {@link Chat} YOU ALREADY HAVE.
     *
     * <p>The door two consumers asked for, in ratchet#2 and ratchet#5, and the reason both gave is
     * the same: {@link Model} builds the client itself, and a consumer that resolves its endpoint
     * from a settings file per call, or sets a temperature per stage, or already has its own
     * listeners and its own silence ceiling, cannot take it. Adopting {@code Model} to reach the
     * retry meant rewriting everything around the one part they wanted.
     *
     * <p>ratchet#2 puts the important half well: the judgement about which failures are worth
     * another attempt is harder to get right than the loop around it. That judgement is
     * {@link #transportFailures()}, and it is public for the same reason.
     */
    public static Chat on(Chat around, Retry retry, Trace trace) {
        return new Retrying(around, retry.attempts(), retry.budget(), retry.backoff(),
                retry.pause(), retry.worthRetrying(), retry.now(), trace);
    }

    /**
     * THE SAME LOOP, AROUND ANYTHING AT ALL — because it never needed a model.
     *
     * <p>Asked for in ratchet#8 by a consumer whose agent runtime is a third party's
     * ({@code com.deepagents:langchain4j-deepagents}), whose constructor takes a langchain4j
     * {@code ChatModel} and a {@code Map<ToolSpecification, ToolExecutor>}. They do not own it and
     * cannot make it take a {@link Chat}, so 0.13.0 left them with no door: {@link #on} is the only
     * way in and it is spelled in terms of one call shape.
     *
     * <p>THEY WERE RIGHT AND THE MEASUREMENT IS ONE LINE. At v0.13.0 the whole of this class touched
     * the message model in exactly one place — {@code inner.answer(ask)} — and everything around it
     * is attempts, a budget, a schedule, a predicate and a trace note. A loop that is already
     * agnostic and reachable only through one concrete type is a seam that stopped at the package
     * boundary, which is the fifth time this library has done that and the fifth time a consumer
     * found it rather than a test.
     *
     * <p>This is a SMALLER surface than {@link #on}, not a larger one: no message model in the
     * signature, no langchain4j in the artifact, nothing about {@link Wire} affected. It also
     * retries things that were never model calls — an HTTP fetch, a tool invocation, a push to a
     * registry — with the schedule that has already been tested here.
     *
     * <p>The call is re-run whole on each attempt, so it must be safe to repeat. That is the same
     * contract {@link #on} has always had and the reason {@link #transportFailures()} refuses a
     * {@link Truncated}: some failures mean the identical request meets the identical wall.
     *
     * @param call  what to attempt, and what to attempt again
     * @param retry the count, the budget, the schedule, the wait, the clock and the judgement
     * @param trace where every attempt is recorded, or null for none
     */
    public static <T> Supplier<T> around(Supplier<T> call, Retry retry, Trace trace) {
        return () -> attempt(call, retry.attempts(), retry.budget(), retry.backoff(), retry.pause(),
                retry.worthRetrying(), retry.now(), trace);
    }

    /**
     * WHAT IS WORTH ASKING AGAIN — NOW ONE STATUS CODE AND FOUR REFUSALS.
     *
     * <p>This has been through three shapes and the arithmetic of each is worth keeping. It began by
     * reading the exception's MESSAGE and looking for "401" in it, which was wrong in both
     * directions: a 500 whose body happened to quote a 401 was treated as permanent, and a refusal
     * phrased any other way was retried nine times. It then tested a client's typed hierarchy AND a
     * raw status code, because the mapping between them ran in an {@code internal} package this
     * library would not depend on, with no promise it had run by the time a streaming error arrived.
     * Now the client is this library's, {@link Refused} carries the status it read off the response,
     * and the whole judgement is one comparison.
     *
     * <p>FOUR THINGS ARE REFUSED, each for its own reason:
     *
     * <ul>
     * <li>{@link GaveUp} — the total ceiling, which fires on a stream that IS producing and is
     *     handing the slot back on purpose.
     * <li>{@link Truncated} — the identical request meets the identical budget, so ten attempts buy
     *     ten more full generations and the same empty answer.
     * <li>{@link Reasoning.LoopDetected} — greedy decoding cannot leave a cycle it has entered. ONE
     *     experiment restarted 14 trapped generations with 2,500 more tokens each and none escaped.
     *     <strong>This is new, and it is new because it used to be impossible.</strong> The
     *     detection was thrown out of an SSE listener, and the client swallowed it there — it never
     *     reached this class at all. A client this library owns lets it through, so for the first
     *     time the retry has to have an opinion about it, and the opinion is no: what follows is
     *     {@link Insisting}, which re-asks with thinking off, and thinking off measured 0 of 10
     *     runaway against a 62.5% control.
     * <li>{@link Exhausted} — the agent spent its round budget, and a retry re-runs all twenty-five
     *     rounds from nothing. Every other refusal here costs one attempt to rediscover; this one
     *     costs a conversation, so ten attempts is two hundred and fifty rounds of model calls to
     *     reach the same wall.
     * <li>An interruption, because a lane being stopped must stop rather than serve out the
     *     schedule.
     * </ul>
     *
     * <p>Anything unrecognised is RETRIED. The cost of retrying something hopeless is one bounded
     * sequence; the cost of not retrying something transient is a whole stage.
     */
    public static Predicate<Throwable> transportFailures() {
        return failure -> {
            for (Throwable at = failure; at != null; at = at.getCause()) {
                if (at instanceof GaveUp || at instanceof Truncated
                        || at instanceof Reasoning.LoopDetected
                        || at instanceof Exhausted
                        || at instanceof InterruptedException) {
                    return false;
                }
                if (at instanceof Refused refused) {
                    return worthAnotherRequest(refused.status());
                }
                if (at.getCause() == at) {
                    break;
                }
            }
            return true;
        };
    }

    /**
     * A status that a second identical request could answer differently.
     *
     * <p>408 and 429 are the server saying "not now"; 5xx is the server failing. Every other 4xx is
     * the server saying the request itself is wrong, and sending it again unchanged cannot help.
     */
    private static boolean worthAnotherRequest(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private final Chat inner;
    private final int attempts;
    private final Duration budget;
    private final Backoff backoff;
    private final Pause pause;
    private final Predicate<Throwable> retryable;
    private final Now now;
    private final Trace trace;

    /**
     * @param attempts how many times the call is made before the stage is allowed to fail
     * @param budget   a wall-clock bound on the WHOLE sequence, and the reason it exists is that a
     *                 count of attempts does not bound anything on its own. A frozen endpoint costs
     *                 a stall — twenty minutes by default — per attempt, so ten attempts is three
     *                 and a half hours, and a lane that holds a slot that long is exactly what
     *                 {@link Watch#ceiling()} exists to prevent and cannot, because the ceiling is
     *                 per attempt and this loop starts a new one. Fast failures still get every
     *                 attempt; slow ones stop here.
     * @param now      the clock, handed in so the budget has a test that does not take an hour
     */
    Retrying(Chat inner, int attempts, Duration budget, Backoff backoff, Pause pause,
             Predicate<Throwable> retryable, Now now, Trace trace) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, not " + attempts);
        }
        this.inner = inner;
        this.attempts = attempts;
        this.budget = budget;
        this.backoff = backoff;
        this.pause = pause;
        this.retryable = retryable;
        this.now = now;
        this.trace = trace == null ? Trace.quiet() : trace;
    }

    @Override
    public Reply answer(Ask ask) {
        return attempt(() -> inner.answer(ask), attempts, budget, backoff, pause, retryable, now,
                trace);
    }

    /**
     * THE LOOP ITSELF, AND IT NAMES NOTHING FROM THE MESSAGE MODEL.
     *
     * <p>Static and generic so {@link #around} and {@link #answer} are the same code rather than two
     * copies that will eventually disagree about the budget.
     */
    private static <T> T attempt(Supplier<T> call, int attempts, Duration budget, Backoff backoff,
                                 Pause pause, Predicate<Throwable> retryable, Now now, Trace trace) {
        long deadline = now.millis() + budget.toMillis();
        // THE HISTORY, KEPT BECAUSE THE SCHEDULE IS ENTITLED TO SEE IT. A Backoff that can look at
        // every failure so far can tell nine identical rate limits from nine different transport
        // errors; one that is handed a count cannot, and has to answer both the same way.
        List<Throwable> failures = new ArrayList<>();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException failed) {
                failures.add(failed);
                if (attempt == attempts || !retryable.test(failed)) {
                    throw failed;
                }
                // THE BUDGET IS CHECKED AFTER THE CALL, NOT BEFORE IT. What overruns is almost
                // never the waiting — it is the attempt itself, sitting on a stalled socket for
                // twenty minutes. Asking before would let a call that has already spent the whole
                // budget start another one.
                if (now.millis() >= deadline) {
                    say(trace, attempts, attempt, "spent " + budget.toMinutes()
                            + "m of retries", failed);
                    throw failed;
                }
                Duration wait = backoff.before(List.copyOf(failures));
                // EVERY ATTEMPT IS IN THE RECORD, including the ones that worked in the end. A
                // retry nobody can see is an endpoint whose flakiness never shows up anywhere, and
                // the first anyone hears of it is a bill or a lane that takes an hour.
                say(trace, attempts, attempt, "asking again in " + wait.toSeconds() + "s", failed);
                try {
                    pause.of(wait);
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    throw failed;
                }
            }
        }
        // Unreachable: the loop either returns or throws. Thrown rather than returned null so a
        // future edit that breaks that cannot hand a caller an answer that does not exist.
        throw failures.isEmpty() ? new IllegalStateException("no attempt was made")
                : (RuntimeException) failures.get(failures.size() - 1);
    }

    private static void say(Trace trace, int attempts, int attempt, String next,
                            RuntimeException failed) {
        try {
            trace.progress("", "model call failed on attempt " + attempt + " of " + attempts
                    + " (" + failed.getMessage() + "); " + next);
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            // The same rule the rest of this package keeps: a trace that cannot be written is not
            // a reason to fail a call that might still succeed.
        }
    }
}
