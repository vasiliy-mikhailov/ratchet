package tech.mikhailov.ratchet.llm;

import java.time.Duration;

/**
 * THE WAIT ITSELF, HANDED IN RATHER THAN CALLED.
 *
 * <p>This exists so the retry schedule can be tested in milliseconds instead of the eighty-eight
 * seconds it actually spends. A test supplies a {@code Pause} that records what it was asked for
 * and returns at once, and can then assert the exact sequence of waits — which is the thing worth
 * asserting, and which a test that really slept could only assert by taking eighty-eight seconds to
 * do it.
 *
 * <p>IT IS A SEAM AND NOT A CONVENIENCE. {@link Wire}'s two bounds are {@code static final}
 * durations read from the environment at class load, which is why neither of them has a test: there
 * is no way to shorten twenty minutes from inside a test that has already loaded the class. That
 * mistake is not repeated here.
 */
@FunctionalInterface
public interface Pause {

    /** Waits, or returns at once for a zero or negative duration. */
    void of(Duration wait) throws InterruptedException;

    /**
     * The real one.
     *
     * <p>Interruption is not swallowed: a lane being stopped must stop, and a sleep that ignored
     * the interrupt would add the rest of the schedule to every {@code --stop}.
     */
    Pause SLEEPING = wait -> {
        if (!wait.isZero() && !wait.isNegative()) {
            Thread.sleep(wait.toMillis());
        }
    };

    /**
     * Returns at once, for a consumer's own tests.
     *
     * <p>Shipped rather than left to be written again in every consumer, because the version
     * everyone writes is the one that sleeps "just a little" to feel realistic, and a suite that
     * sleeps is a suite somebody eventually deletes.
     */
    Pause NONE = wait -> {
    };
}
