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

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;

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
     * WHAT IS WORTH ASKING AGAIN, DECIDED ON TYPES AND A STATUS CODE.
     *
     * <p>This used to read the exception's MESSAGE and look for "401" in it, which was wrong in both
     * directions: a 500 whose body happened to quote a 401 was treated as permanent, and a refusal
     * phrased any other way was retried nine times. The client has carried the answer as types all
     * along — {@code NonRetriableException} covers authentication, an invalid request and a model
     * that does not exist, {@code RetriableException} covers rate limits, timeouts and 5xx — and
     * {@code HttpException} carries {@link dev.langchain4j.exception.HttpException#statusCode()}.
     *
     * <p>BOTH ARE CHECKED, because the mapping from status to type happens in an {@code internal}
     * package this library will not depend on, and there is no promise it has run by the time a
     * streaming error reaches here. If it did, the type answers; if it did not, the status does.
     *
     * <p>Two more are refused for reasons of this library's own: {@link Streamed.GaveUp}, the total
     * ceiling, which fires on a stream that IS producing and is handing the slot back on purpose,
     * and an interruption, because a lane being stopped must stop rather than serve out the
     * schedule.
     *
     * <p>Anything unrecognised is RETRIED. The cost of retrying something hopeless is one bounded
     * sequence; the cost of not retrying something transient is a whole stage.
     */
    static Predicate<Throwable> transportFailures() {
        return failure -> {
            for (Throwable at = failure; at != null; at = at.getCause()) {
                if (at instanceof Streamed.GaveUp || at instanceof InterruptedException) {
                    return false;
                }
                // A TRUNCATION IS NOT TRANSIENT. The identical request meets the identical budget,
                // so ten attempts buy ten more full generations and the same empty answer.
                if (at instanceof Streamed.Truncated) {
                    return false;
                }
                if (at instanceof NonRetriableException) {
                    return false;
                }
                if (at instanceof RetriableException) {
                    return true;
                }
                if (at instanceof HttpException http) {
                    return worthAnotherRequest(http.statusCode());
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

    private final ChatModel inner;
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
     *                 {@link Streamed}'s ceiling exists to prevent and cannot, because the ceiling
     *                 is per attempt and this loop starts a new one. Fast failures still get every
     *                 attempt; slow ones stop here.
     * @param now      the clock, handed in so the budget has a test that does not take an hour
     */
    Retrying(ChatModel inner, int attempts, Duration budget, Backoff backoff, Pause pause,
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
        this.trace = trace;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return doChat(request);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        long deadline = now.millis() + budget.toMillis();
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
                if (now.millis() >= deadline) {
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
