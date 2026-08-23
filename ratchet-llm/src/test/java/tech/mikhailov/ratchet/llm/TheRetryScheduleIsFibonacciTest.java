package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SCHEDULE, READ OFF WITHOUT A MODEL, A CLOCK OR A SOCKET.
 *
 * <p>The waits matter most at the end, where they are longest and where nobody ever gets to in a
 * hand test. A rule that produced the wrong ninth wait would be a rule that only misbehaves during
 * an outage, which is the worst possible time to find out. So the whole schedule is asserted here
 * as a list of numbers, and {@link Backoff} is a pure function precisely so that it can be.
 */
class TheRetryScheduleIsFibonacciTest {

    @Test
    void theFirstAttemptDoesNotWait() {
        assertEquals(Duration.ZERO, Backoff.fibonacciSeconds().before(1),
                "a first try that slept would slow down every call that never fails");
    }

    @Test
    void theNineWaitsOfTenAttemptsAreFibonacciSeconds() {
        Backoff backoff = Backoff.fibonacciSeconds();
        List<Long> waits = new ArrayList<>();
        for (int attempt = 2; attempt <= 10; attempt++) {
            waits.add(backoff.before(attempt).toSeconds());
        }

        assertEquals(List.of(1L, 1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L), waits,
                "the whole schedule, in order, before attempts two through ten");
    }

    @Test
    void tenAttemptsSpendEightyEightSecondsWaiting() {
        Backoff backoff = Backoff.fibonacciSeconds();
        long total = 0;
        for (int attempt = 1; attempt <= 10; attempt++) {
            total += backoff.before(attempt).toSeconds();
        }

        assertEquals(88, total, "nine waits, and this is what a fully exhausted retry costs in time");
    }

    @Test
    void eachWaitIsTheSumOfTheTwoBeforeIt() {
        Backoff backoff = Backoff.fibonacciSeconds();

        for (int attempt = 4; attempt <= 12; attempt++) {
            long twoBack = backoff.before(attempt - 2).toSeconds();
            long oneBack = backoff.before(attempt - 1).toSeconds();
            assertEquals(twoBack + oneBack, backoff.before(attempt).toSeconds(),
                    "wait before attempt " + attempt + " is the sum of the two before it");
        }
    }

    @Test
    void theScheduleDoesNotDecideWhenToStop() {
        Backoff backoff = Backoff.fibonacciSeconds();

        assertEquals(55, backoff.before(11).toSeconds(),
                "it keeps answering past ten; the cap is a count of attempts, held elsewhere");
        assertTrue(backoff.before(20).toSeconds() > backoff.before(11).toSeconds(),
                "and it keeps growing, so a larger cap is a decision Retrying makes alone");
    }
}
