package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * HOW LONG TO WAIT BEFORE THE NEXT ATTEMPT, GIVEN EVERY ATTEMPT THAT HAS ALREADY FAILED.
 *
 * <p>Its own type, and not a number inside {@link Retrying}, because the schedule is the part worth
 * testing on its own: a rule that produces the wrong ninth wait is a rule nobody notices until the
 * ninth wait, and by then it is inside a lane that is already failing. Here it is a pure function of
 * the failures so far, so the whole schedule can be read off without a model, a clock or a socket.
 *
 * <p>THE WHOLE HISTORY, AND NOT A COUNT AND A REASON. Both of those are already in it — the attempt
 * about to be made is {@code failed.size() + 1}, and what went wrong last is the last element — so
 * passing them separately would be passing the same thing twice and inviting the two to disagree.
 * What the history adds is the SHAPE of the failure, which is what a schedule actually wants: nine
 * identical rate limits and nine different transport errors are not the same situation, and a rule
 * that can only see "attempt nine" has to answer both the same way.
 *
 * <p>Neither schedule below reads it that closely yet — both use only the size — but the seam is
 * the point. Honouring a {@code Retry-After} header, or giving up early because the same refusal
 * has now repeated three times, is then a new {@code Backoff} rather than a change to
 * {@link Retrying}.
 */
@FunctionalInterface
public interface Backoff {

    /**
     * The wait before the next attempt.
     *
     * <p>An empty list is the first attempt and is always {@link Duration#ZERO}: a first try that
     * slept would turn every call into a slower call, including the overwhelming majority that
     * never fail.
     *
     * @param failed every attempt that has already failed, oldest first, never null
     */
    Duration before(List<Throwable> failed);

    /**
     * FIBONACCI SECONDS: 1, 1, 2, 3, 5, 8, 13, 21, 34 before attempts two through ten.
     *
     * <p>Eighty-eight seconds in total across nine waits. That is the point of the shape — it starts
     * under a second of delay, so a single dropped connection costs almost nothing, and it reaches
     * half a minute by the end, so a genuine outage is not being asked the same question thirty
     * times a minute. Doubling would reach the same total in fewer, coarser steps and spend longer
     * asleep after a blip that had already cleared.
     *
     * <p>Unbounded by design: the cap is a count of attempts, held by {@link Retrying}, because a
     * schedule that also decided when to stop would be two rules in one place and neither of them
     * testable alone.
     */
    static Backoff fibonacciSeconds() {
        return failed -> {
            if (failed.isEmpty()) {
                return Duration.ZERO;
            }
            long previous = 0;
            long current = 1;
            for (int i = 0; i < failed.size(); i++) {
                long next = previous + current;
                previous = current;
                current = next;
            }
            return Duration.ofSeconds(previous);
        };
    }

    /**
     * THE SAME SCHEDULE, PLUS A DRAW, SO LANES THAT FAIL TOGETHER DO NOT RETURN TOGETHER.
     *
     * <p>A sweep runs its lanes at once against one endpoint. When that endpoint hiccups they all
     * fail in the same instant, and on a schedule this exact they would all come back in the same
     * instant too — eight simultaneous requests at one second, then eight at two, at a server that
     * is already struggling. The schedule spreads the attempts of one lane and does nothing
     * whatever to spread the lanes.
     *
     * <p>So each wait carries an independent draw of up to a minute on top of its Fibonacci second.
     * Eight lanes then come back spread across that minute rather than stacked on one moment, and
     * the spread is widest where it matters most — the first waits, where the schedule itself is
     * only a second or two apart.
     *
     * <p>The draw is handed in rather than taken, so a test asserts an exact sequence and production
     * takes a real one.
     *
     * @param base         the schedule to spread
     * @param extraSeconds drawn once per wait; never called for the first attempt, which does not
     *                     wait at all
     */
    /** The same wait every time. */
    static Backoff fixed(Duration wait) {
        return failed -> failed.isEmpty() ? Duration.ZERO : wait;
    }

    /** No wait at all — for a local endpoint, where a second of politeness buys nothing. */
    static Backoff immediate() {
        return failed -> Duration.ZERO;
    }

    static Backoff jitteredBy(Backoff base, IntSupplier extraSeconds) {
        return failed -> {
            if (failed.isEmpty()) {
                return Duration.ZERO;
            }
            return base.before(failed).plusSeconds(extraSeconds.getAsInt());
        };
    }
}
