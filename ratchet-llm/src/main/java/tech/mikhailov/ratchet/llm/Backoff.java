package tech.mikhailov.ratchet.llm;

import java.time.Duration;

/**
 * HOW LONG TO WAIT BEFORE AN ATTEMPT.
 *
 * <p>Its own type, and not a number inside {@link Retrying}, because the schedule is the part worth
 * testing on its own: a rule that produces the wrong ninth wait is a rule nobody notices until the
 * ninth wait, and by then it is inside a lane that is already failing. Here it is a pure function of
 * the attempt number, so the whole schedule can be read off without a model, a clock or a socket.
 */
@FunctionalInterface
interface Backoff {

    /**
     * The wait before {@code attempt}, numbered from one.
     *
     * <p>{@code before(1)} is the first attempt and is always {@link Duration#ZERO}: a first try
     * that slept would turn every call into a slower call, including the overwhelming majority that
     * never fail.
     */
    Duration before(int attempt);

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
        return attempt -> {
            if (attempt <= 1) {
                return Duration.ZERO;
            }
            long previous = 0;
            long current = 1;
            for (int i = 1; i < attempt; i++) {
                long next = previous + current;
                previous = current;
                current = next;
            }
            return Duration.ofSeconds(previous);
        };
    }
}
