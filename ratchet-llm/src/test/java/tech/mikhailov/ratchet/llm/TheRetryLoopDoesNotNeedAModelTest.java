package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE LOOP NEVER NEEDED A MODEL, AND FOR ONE RELEASE IT WAS ONLY REACHABLE THROUGH ONE.
 *
 * <p>ratchet#8, from a consumer whose agent runtime is a third party's and takes a langchain4j
 * {@code ChatModel} it cannot change. 0.13.0 left them with no door at all: {@link Retrying#on} was
 * the only way in and it is spelled in terms of {@link Chat}. Their measurement was one line — the
 * whole class touched the message model at {@code inner.answer(ask)} and nowhere else — and their
 * conclusion was that the loop should be reachable on its own.
 *
 * <p>They were right, and this file is the guard that stops it silently becoming untrue again. The
 * fifth time a seam in this library stopped one step short of the package boundary, and the fifth
 * time a consumer found it rather than a test: {@code Backoff}/{@code Pause}, then {@code Now},
 * then {@code Endpoint}, then {@code Watch}, now this.
 *
 * <p>ONE LOOP, NOT TWO. The tests below assert that {@link Retrying#around} and {@link Retrying#on}
 * behave identically given the same {@link Retry}, because the failure this file is really guarding
 * against is not the door being missing — it is the door opening onto a second copy of the loop that
 * drifts from the first.
 */
class TheRetryLoopDoesNotNeedAModelTest {

    private static final Trace QUIET = null;

    @Test
    void aPlainCallIsRetriedOnTheSameSchedule() {
        AtomicInteger tries = new AtomicInteger();
        Waits waits = new Waits();

        Supplier<String> flaky = Retrying.around(() -> {
            if (tries.incrementAndGet() < 4) {
                throw new IllegalStateException("dropped");
            }
            return "eventually";
        }, retry(10, waits), QUIET);

        assertEquals("eventually", flaky.get());
        assertEquals(4, tries.get(), "three failures and the attempt that worked");
        assertEquals(List.of(1L, 1L, 2L), waits.seconds,
                "the Fibonacci schedule, unchanged: this is the loop that was already tested");
    }

    @Test
    void nothingInTheSignatureNamesAModel() {
        // THE POINT OF THE ISSUE, ASSERTED RATHER THAN DESCRIBED. What is being retried here is not
        // a model call and could not be adapted into one: no Ask exists to build, no Reply to
        // return. The consumer who asked for this is retrying a third party's agent runtime; the
        // same door serves an HTTP fetch or a push to a registry.
        AtomicInteger tries = new AtomicInteger();

        Supplier<Integer> counted = Retrying.around(() -> {
            if (tries.incrementAndGet() < 3) {
                throw new IllegalStateException("not yet");
            }
            return tries.get() * 7;
        }, retry(10, new Waits()), QUIET);

        assertEquals(21, counted.get());
    }

    @Test
    void theCountBoundsItAndTheLastFailureIsWhatEscapes() {
        AtomicInteger tries = new AtomicInteger();

        IllegalStateException gaveUp = assertThrows(IllegalStateException.class,
                () -> Retrying.around(() -> {
                    throw new IllegalStateException("attempt " + tries.incrementAndGet());
                }, retry(3, new Waits()), QUIET).get());

        assertEquals(3, tries.get());
        assertEquals("attempt 3", gaveUp.getMessage(),
                "the caller is shown the failure that ended it, not the one that started it");
    }

    @Test
    void thePredicateStopsItEarlyAndIsTheConsumersOwn() {
        // A consumer with its own transport has its own ceiling exception, which this library has
        // never heard of and would otherwise RETRY — transportFailures() retries what it does not
        // recognise, deliberately. Composing is the answer, and it works because worthRetrying is
        // a plain Predicate on the Retry.
        AtomicInteger tries = new AtomicInteger();
        Retry mine = retry(10, new Waits())
                .withWorthRetrying(Retrying.transportFailures()
                        .and(failure -> !(failure instanceof TheirOwnCeiling)));

        assertThrows(TheirOwnCeiling.class, () -> Retrying.around(() -> {
            tries.incrementAndGet();
            throw new TheirOwnCeiling();
        }, mine, QUIET).get());

        assertEquals(1, tries.get(), "their ceiling is refused on the first attempt, not the tenth");
    }

    @Test
    void everyAttemptIsStillInTheRecord() {
        List<String> noted = new ArrayList<>();
        AtomicInteger tries = new AtomicInteger();

        Retrying.around(() -> {
            if (tries.incrementAndGet() < 3) {
                throw new IllegalStateException("dropped");
            }
            return "ok";
        }, retry(10, new Waits()), noting(noted)).get();

        assertEquals(2, noted.size(), "both failures are recorded, including the run that recovered");
        assertTrue(noted.get(0).contains("attempt 1 of 10"), noted.get(0));
        assertTrue(noted.get(0).contains("asking again in 1s"), noted.get(0));
    }

    @Test
    void itIsTheSameLoopAsTheOneChatGets() {
        // THE MUTATION THIS FILE EXISTS FOR. A second copy of the loop behind the new door would
        // pass every test above and drift from Chat's within a release. So both doors are driven
        // with one Retry and asserted to produce the same attempt count and the same waits.
        Waits throughAround = new Waits();
        AtomicInteger triesAround = new AtomicInteger();
        Retrying.around(() -> {
            if (triesAround.incrementAndGet() < 5) {
                throw new IllegalStateException("dropped");
            }
            return "ok";
        }, retry(10, throughAround), QUIET).get();

        Waits throughChat = new Waits();
        AtomicInteger triesChat = new AtomicInteger();
        Chat flaky = ask -> {
            if (triesChat.incrementAndGet() < 5) {
                throw new IllegalStateException("dropped");
            }
            return new Reply("ok", "", List.of(), Ending.STOPPED, Spend.NONE);
        };
        Retrying.on(flaky, retry(10, throughChat), QUIET).answer(Ask.of(List.of(Said.user("hi"))));

        assertEquals(triesAround.get(), triesChat.get(), "the same number of attempts");
        assertEquals(throughAround.seconds, throughChat.seconds, "and the same waits between them");
        assertEquals(List.of(1L, 1L, 2L, 3L), throughAround.seconds, "on the shipped schedule");
    }

    /** A consumer's own ceiling: this library has never heard of it and must not retry it. */
    private static final class TheirOwnCeiling extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static Retry retry(int attempts, Waits waits) {
        return new Retry(attempts, Duration.ofMinutes(30), Backoff.fibonacciSeconds(),
                Retrying.transportFailures(), waits, Now.frozenAt(0));
    }

    /** The waits, taken rather than lived through, so the whole schedule asserts in milliseconds. */
    private static final class Waits implements Pause {
        private final List<Long> seconds = new ArrayList<>();

        @Override
        public void of(Duration wait) {
            seconds.add(wait.toSeconds());
        }
    }

    private static Trace noting(List<String> into) {
        return new Trace() {
            @Override
            public void progress(String key, String note) {
                into.add(note);
            }

            @Override
            public void asked(String agent, String prompt, String reply) {
            }

            @Override
            public void applied(String stage, String what) {
            }

            @Override
            public void tool(String agent, String tool, String arguments, String result) {
            }

            @Override
            public void thought(String agent, String finishReason, String thinking, String content) {
            }

            @Override
            public void built(String phase, Outcome result) {
            }

            @Override
            public void settled(String key, String state, String because, boolean beforeOk,
                                boolean afterOk) {
            }

            @Override
            public void failed(String key, Throwable cause) {
            }

            @Override
            public void priced(String key, String minutes, String itemisation) {
            }
        };
    }
}
