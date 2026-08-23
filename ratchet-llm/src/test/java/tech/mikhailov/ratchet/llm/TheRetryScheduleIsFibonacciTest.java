package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** A history of {@code n} failures, which is what the schedule is a function of. */
    private static List<Throwable> after(int n) {
        List<Throwable> failed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            failed.add(new IllegalStateException("connection reset " + i));
        }
        return failed;
    }

    @Test
    void theScheduleCanSeeWhatWentWrongAndNotOnlyHowOften() {
        Backoff readsTheHistory = failed -> {
            boolean allTheSame = failed.stream()
                    .map(t -> String.valueOf(t.getMessage())).distinct().count() == 1;
            return Duration.ofSeconds(allTheSame && failed.size() >= 3 ? 300 : 1);
        };

        List<Throwable> sameRefusalThrice = List.of(
                new IllegalStateException("status code: 429"),
                new IllegalStateException("status code: 429"),
                new IllegalStateException("status code: 429"));

        assertEquals(300, readsTheHistory.before(sameRefusalThrice).toSeconds(),
                "one rate limit repeating is a different situation from three unrelated drops");
        assertEquals(1, readsTheHistory.before(after(3)).toSeconds(),
                "and three different transport errors are not that situation");
    }

    @Test
    void theHistoryCarriesBothTheCountAndTheLastReason() {
        Backoff readsBoth = failed -> Duration.ofSeconds(
                failed.size() * 10L + failed.get(failed.size() - 1).getMessage().length());

        List<Throwable> three = after(3);
        assertEquals(30 + three.get(2).getMessage().length(),
                readsBoth.before(three).toSeconds(),
                "attempt number is failed.size() + 1 and the reason is the last element");
    }



    @Test
    void theFirstAttemptDoesNotWait() {
        assertEquals(Duration.ZERO, Backoff.fibonacciSeconds().before(after(0)),
                "a first try that slept would slow down every call that never fails");
    }

    @Test
    void theNineWaitsOfTenAttemptsAreFibonacciSeconds() {
        Backoff backoff = Backoff.fibonacciSeconds();
        List<Long> waits = new ArrayList<>();
        for (int attempt = 2; attempt <= 10; attempt++) {
            waits.add(backoff.before(after(attempt - 1)).toSeconds());
        }

        assertEquals(List.of(1L, 1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L), waits,
                "the whole schedule, in order, before attempts two through ten");
    }

    @Test
    void tenAttemptsSpendEightyEightSecondsWaiting() {
        Backoff backoff = Backoff.fibonacciSeconds();
        long total = 0;
        for (int attempt = 1; attempt <= 10; attempt++) {
            total += backoff.before(after(attempt - 1)).toSeconds();
        }

        assertEquals(88, total, "nine waits, and this is what a fully exhausted retry costs in time");
    }

    @Test
    void eachWaitIsTheSumOfTheTwoBeforeIt() {
        Backoff backoff = Backoff.fibonacciSeconds();

        for (int attempt = 4; attempt <= 12; attempt++) {
            long twoBack = backoff.before(after(attempt - 3)).toSeconds();
            long oneBack = backoff.before(after(attempt - 2)).toSeconds();
            assertEquals(twoBack + oneBack, backoff.before(after(attempt - 1)).toSeconds(),
                    "wait before attempt " + attempt + " is the sum of the two before it");
        }
    }

    @Test
    void theDrawIsAddedToEachWaitAndNotSubstitutedForIt() {
        Backoff jittered = Backoff.jitteredBy(Backoff.fibonacciSeconds(), () -> 40);
        List<Long> waits = new ArrayList<>();
        for (int attempt = 2; attempt <= 10; attempt++) {
            waits.add(jittered.before(after(attempt - 1)).toSeconds());
        }

        assertEquals(List.of(41L, 41L, 42L, 43L, 45L, 48L, 53L, 61L, 74L), waits,
                "the schedule still grows underneath; the draw moves the whole of it");
    }

    @Test
    void theFirstAttemptDoesNotWaitEvenWithADraw() {
        Backoff jittered = Backoff.jitteredBy(Backoff.fibonacciSeconds(), () -> 59);

        assertEquals(Duration.ZERO, jittered.before(after(0)),
                "jitter spreads retries, and the first attempt is not a retry");
    }

    @Test
    void everyWaitDrawsItsOwnNumber() {
        AtomicInteger draws = new AtomicInteger();
        Backoff jittered = Backoff.jitteredBy(Backoff.fibonacciSeconds(), draws::incrementAndGet);

        List<Long> waits = new ArrayList<>();
        for (int attempt = 2; attempt <= 5; attempt++) {
            waits.add(jittered.before(after(attempt - 1)).toSeconds());
        }

        assertEquals(List.of(2L, 3L, 5L, 7L), waits,
                "1+1, 1+2, 2+3, 3+4 — a fresh draw each time, not one reused for the sequence");
        assertEquals(4, draws.get(), "and exactly one draw per wait");
    }

    @Test
    void twoLanesOnTheSameScheduleDoNotComeBackTogether() {
        Backoff laneA = Backoff.jitteredBy(Backoff.fibonacciSeconds(), () -> 3);
        Backoff laneB = Backoff.jitteredBy(Backoff.fibonacciSeconds(), () -> 47);

        assertNotEquals(laneA.before(after(1)), laneB.before(after(1)),
                "which is the whole point: eight lanes failing at once must not all return at once");
    }

    @Test
    void theProductionDrawStaysInsideItsMinute() {
        for (int i = 0; i < 500; i++) {
            int drawn = Model.jitterSeconds();
            assertTrue(drawn >= 0 && drawn < 60,
                    "a draw outside [0,60) would push a wait somewhere nobody chose: " + drawn);
        }
    }

    @Test
    void theScheduleDoesNotDecideWhenToStop() {
        Backoff backoff = Backoff.fibonacciSeconds();

        assertEquals(55, backoff.before(after(10)).toSeconds(),
                "it keeps answering past ten; the cap is a count of attempts, held elsewhere");
        assertTrue(backoff.before(after(19)).toSeconds() > backoff.before(after(10)).toSeconds(),
                "and it keeps growing, so a larger cap is a decision Retrying makes alone");
    }
}
