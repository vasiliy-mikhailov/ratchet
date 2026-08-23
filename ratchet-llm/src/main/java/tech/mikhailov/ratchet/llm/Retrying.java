package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

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
final class Retrying implements ChatModel {

    /**
     * WHAT IS WORTH ASKING AGAIN.
     *
     * <p>A transport failure is worth retrying because the next attempt may well land. Three things
     * are not:
     *
     * <ul>
     * <li>{@link Streamed.GaveUp}, the total ceiling. It fires only on a stream that IS producing
     *     and has been producing for hours; the lane is being handed back deliberately, and asking
     *     again would spend another ceiling doing it.
     * <li>An interruption. The lane is being stopped, and a retry would add the rest of the
     *     schedule to every stop.
     * <li>A refusal by the endpoint to accept the request at all — a bad key, a model name it does
     *     not have. Nine more attempts cannot fix a credential, and each one re-prefills the whole
     *     conversation to be told the same thing.
     * </ul>
     *
     * <p>The stall IS retried: a connection that has gone silent for twenty minutes is exactly the
     * case a fresh connection fixes.
     */
    static Predicate<Throwable> transportFailures() {
        return failure -> {
            if (failure instanceof Streamed.GaveUp) {
                return false;
            }
            for (Throwable at = failure; at != null; at = at.getCause()) {
                if (at instanceof InterruptedException) {
                    return false;
                }
                String said = at.getMessage();
                if (said != null && refusedOutright(said)) {
                    return false;
                }
                if (at.getCause() == at) {
                    break;
                }
            }
            return true;
        };
    }

    /**
     * A REFUSAL READ OFF THE MESSAGE, WHICH IS NOT WHERE IT SHOULD BE READ FROM.
     *
     * <p>The client throws one type for every HTTP failure and puts the status in the text, so
     * there is nothing better to match on until it does otherwise. Kept deliberately narrow: only
     * the codes that no amount of waiting changes. Anything unrecognised is retried, because the
     * cost of retrying something hopeless is a bounded eighty-eight seconds and the cost of NOT
     * retrying something transient is a whole stage.
     */
    private static boolean refusedOutright(String said) {
        return said.contains("401") || said.contains("403") || said.contains("404")
                || said.contains("400");
    }

    private final ChatModel inner;
    private final int attempts;
    private final Duration budget;
    private final Backoff backoff;
    private final Pause pause;
    private final Predicate<Throwable> retryable;
    private final java.util.function.LongSupplier now;
    private final Trace trace;

    /**
     * @param attempts how many times the call is made before the stage is allowed to fail
     * @param budget   a wall-clock bound on the WHOLE sequence, and the reason it exists is that a
     *                 count of attempts does not bound anything on its own. A frozen endpoint costs
     *                 a stall — twenty minutes by default — per attempt, so ten attempts is three
     *                 and a half hours, and a lane that holds a slot that long is exactly what
     *                 {@link Streamed}'s ceiling exists to prevent and cannot, because the ceiling
     *                 is per attempt and this loop starts a new one. Fast failures still get every
     *                 attempt; slow ones stop here.
     * @param now      the clock, handed in so the budget has a test that does not take an hour
     */
    Retrying(ChatModel inner, int attempts, Duration budget, Backoff backoff, Pause pause,
             Predicate<Throwable> retryable, java.util.function.LongSupplier now, Trace trace) {
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
        this.trace = trace;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return doChat(request);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        long deadline = now.getAsLong() + budget.toMillis();
        // THE HISTORY, KEPT BECAUSE THE SCHEDULE IS ENTITLED TO SEE IT. A Backoff that can look at
        // every failure so far can tell nine identical rate limits from nine different transport
        // errors; one that is handed a count cannot, and has to answer both the same way.
        List<Throwable> failures = new ArrayList<>();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return inner.chat(request);
            } catch (RuntimeException failed) {
                failures.add(failed);
                if (attempt == attempts || !retryable.test(failed)) {
                    throw failed;
                }
                // THE BUDGET IS CHECKED AFTER THE CALL, NOT BEFORE IT. What overruns is almost
                // never the waiting — it is the attempt itself, sitting on a stalled socket for
                // twenty minutes. Asking before would let a call that has already spent the whole
                // budget start another one.
                if (now.getAsLong() >= deadline) {
                    say(attempt, "spent " + budget.toMinutes() + "m of retries", failed);
                    throw failed;
                }
                Duration wait = backoff.before(List.copyOf(failures));
                // EVERY ATTEMPT IS IN THE RECORD, including the ones that worked in the end. A
                // retry nobody can see is an endpoint whose flakiness never shows up anywhere, and
                // the first anyone hears of it is a bill or a lane that takes an hour.
                say(attempt, wait, failed);
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

    private void say(int attempt, Duration wait, RuntimeException failed) {
        say(attempt, "asking again in " + wait.toSeconds() + "s", failed);
    }

    private void say(int attempt, String next, RuntimeException failed) {
        if (trace == null) {
            return;
        }
        try {
            trace.progress("", "model call failed on attempt " + attempt + " of " + attempts
                    + " (" + failed.getMessage() + "); " + next);
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            // The same rule the rest of this package keeps: a trace that cannot be written is not
            // a reason to fail a call that might still succeed.
        }
    }

    @Override
    public List<ChatModelListener> listeners() {
        return inner.listeners();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return inner.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return inner.supportedCapabilities();
    }
}
