package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * WHAT TO DO WHEN THE ENDPOINT DROPS THE CALL, AS ONE VALUE A CONSUMER CAN HAND IN.
 *
 * <p>The pieces underneath — {@link Backoff}, {@link Pause}, the predicate — were injected from the
 * start so that each could be tested alone, and for two releases they were injected only by this
 * package, which made the injection a private convenience rather than a contract. This is the
 * contract: one value, four named defaults, and {@code with} methods for the one field a consumer
 * usually wants to change.
 *
 * <p>THE DEFAULTS ARE NOT ALL THE SAME ANSWER, because the consumers are not the same. A sweep
 * against a shared endpoint over a proxy wants {@link #fromEnv}, which waits and spreads its lanes.
 * A pipeline whose model is a process on the same host wants {@link #local}: nothing there is
 * transient in the way a network is, and eighty-eight seconds of politeness buys a local socket
 * nothing. A test wants {@link #none} or its own {@link Pause}.
 *
 * @param attempts       how many times a call is made before the stage is allowed to fail; 1 means
 *                       no retry at all
 * @param budget         a wall-clock bound on the WHOLE sequence, which a count of attempts does
 *                       not give you: a frozen endpoint costs a stall per attempt, so ten attempts
 *                       is ten stalls
 * @param backoff        how long to wait, given every failure so far
 * @param worthRetrying  which failures are worth asking again about
 * @param pause          how the waiting is done; {@link Pause#NONE} makes a suite instant
 */
public record Retry(int attempts, Duration budget, Backoff backoff,
                    Predicate<Throwable> worthRetrying, Pause pause) {

    public Retry {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, not " + attempts);
        }
    }

    /**
     * WHAT SHIPS, and what {@link Model#forProducer(tech.mikhailov.ratchet.record.Trace)} uses.
     *
     * <p>Ten attempts, a Fibonacci second plus a draw of up to a minute, bounded at half an hour.
     * Every number is an environment variable, so a deployment can change its mind without a
     * rebuild: {@code RATCHET_ATTEMPTS}, {@code RATCHET_JITTER_SECONDS},
     * {@code RATCHET_RETRY_BUDGET_MINUTES}.
     */
    public static Retry fromEnv() {
        return new Retry(Model.attempts(), Model.budget(),
                Backoff.jitteredBy(Backoff.fibonacciSeconds(), Model::jitterSeconds),
                Retrying.transportFailures(), Pause.SLEEPING);
    }

    /**
     * ONE ATTEMPT, which is the behaviour before any of this existed.
     *
     * <p>Named rather than spelt {@code new Retry(1, ...)} so that a consumer turning retry off says
     * so, and so the reader of that line does not have to work out what the other four arguments
     * were chosen to mean.
     */
    public static Retry none() {
        return new Retry(1, Duration.ZERO, Backoff.immediate(),
                failure -> false, Pause.NONE);
    }

    /**
     * FOR AN ENDPOINT ON THE SAME HOST: retries, quickly, without the jitter.
     *
     * <p>The jitter exists to keep a sweep's lanes off one shared server. A local process is not
     * that, and spreading eight lanes across a minute in front of it only makes the sweep slower.
     */
    public static Retry local() {
        return new Retry(4, Duration.ofMinutes(2), Backoff.fixed(Duration.ofSeconds(1)),
                Retrying.transportFailures(), Pause.SLEEPING);
    }

    /** {@code n} attempts on the shipped schedule. */
    public static Retry times(int attempts) {
        return fromEnv().withAttempts(attempts);
    }

    public Retry withAttempts(int attempts) {
        return new Retry(attempts, budget, backoff, worthRetrying, pause);
    }

    public Retry withBudget(Duration budget) {
        return new Retry(attempts, budget, backoff, worthRetrying, pause);
    }

    /** Swap the schedule — this is where a {@code Retry-After}-aware one goes. */
    public Retry with(Backoff backoff) {
        return new Retry(attempts, budget, backoff, worthRetrying, pause);
    }

    public Retry with(Predicate<Throwable> worthRetrying) {
        return new Retry(attempts, budget, backoff, worthRetrying, pause);
    }

    /** {@link Pause#NONE} makes a consumer's own suite instant without shrinking the schedule. */
    public Retry with(Pause pause) {
        return new Retry(attempts, budget, backoff, worthRetrying, pause);
    }
}
