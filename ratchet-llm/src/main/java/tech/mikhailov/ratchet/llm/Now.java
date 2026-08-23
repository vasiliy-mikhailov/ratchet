package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WHERE THE TIME COMES FROM, so a budget measured in minutes can be tested in microseconds.
 *
 * <p>{@link Retry#budget} bounds a whole retry sequence in wall-clock time, and wall-clock is the
 * one collaborator a fake cannot supply from outside. That left the budget as the single behaviour
 * a consumer could not test: with {@link Pause#NONE} no time passes at all, so the bound is never
 * reached and the branch is never exercised; with a real pause the test takes the budget; and with
 * a fake endpoint that happens to be slow the result depends on the machine, which is the worst of
 * the three because it passes until it does not.
 *
 * <p>So the clock is handed in like everything else. {@link #SYSTEM} in production, and for a test
 * either {@link #frozenAt} — nothing ever expires — or {@link #steppingBy}, which is the one people
 * actually want: time that moves a fixed amount every time anybody looks at it, so a frozen
 * endpoint costing a stall per attempt is a loop that finishes instantly.
 */
@FunctionalInterface
public interface Now {

    /** Milliseconds, on whatever scale; only differences are ever taken. */
    long millis();

    /** The real one. */
    Now SYSTEM = System::currentTimeMillis;

    /** Time that never moves, so no budget is ever spent. */
    static Now frozenAt(long millis) {
        return () -> millis;
    }

    /**
     * Time that jumps by {@code step} every time it is read.
     *
     * <p>Shipped rather than left to each consumer, because the version everyone writes is an
     * {@code AtomicLong} they got the units wrong in once. Reading is what advances it, which is
     * exactly what stands in for an attempt that sat on a stalled socket.
     */
    static Now steppingBy(Duration step) {
        AtomicLong at = new AtomicLong();
        return () -> at.getAndAdd(step.toMillis());
    }
}
