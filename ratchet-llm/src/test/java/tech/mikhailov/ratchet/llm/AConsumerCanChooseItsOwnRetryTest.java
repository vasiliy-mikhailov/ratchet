package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE INJECTION IS A CONTRACT AND NOT A PRIVATE CONVENIENCE.
 *
 * <p>For two releases {@link Backoff}, {@link Pause} and the predicate were injected only by this
 * package. Everything was replaceable and nothing outside could replace it, which made the design
 * an argument rather than a feature: a consumer whose endpoint is a process on the same host still
 * got eighty-eight seconds of waiting and a minute of jitter chosen for a shared server behind a
 * proxy, and a consumer testing its own pipeline had to live through the waits or not test them.
 *
 * <p>These are the one-liners that fix that, asserted as a consumer would reach them.
 */
class AConsumerCanChooseItsOwnRetryTest {

    @Test
    void noneMeansTheBehaviourFromBeforeAnyOfThisExisted() {
        Flaky endpoint = new Flaky(3);

        assertThrows(RuntimeException.class,
                () -> Model.wrap(endpoint, new Notes(), Retry.none(), CLOCK).chat(ask()));

        assertEquals(1, endpoint.calls.get(), "asked once, exactly as before Retrying existed");
        // BOTH HALVES, because either alone would hold if the other were broken: none() refuses
        // every failure AND allows one attempt, and a test that checked only the call count would
        // pass with the count raised to five.
        assertEquals(1, Retry.none().attempts(), "one attempt, whatever the predicate says");
        assertFalse(Retry.none().worthRetrying().test(new IllegalStateException("connection reset")),
                "and nothing is worth retrying, whatever the count says");
    }

    @Test
    void localRetriesQuicklyAndWithoutTheJitterMeantForASharedServer() {
        Flaky endpoint = new Flaky(2);
        Waits waits = new Waits();

        Model.wrap(endpoint, new Notes(), Retry.local().with(waits), CLOCK).chat(ask());

        assertEquals(3, endpoint.calls.get());
        assertEquals(List.of(1L, 1L), waits.asked,
                "a flat second, and no minute-wide draw in front of a process on this host");
    }

    @Test
    void timesChoosesTheCountAndKeepsTheShippedSchedule() {
        Flaky endpoint = new Flaky(Integer.MAX_VALUE);
        Waits waits = new Waits();

        assertThrows(RuntimeException.class, () -> Model.wrap(endpoint, new Notes(),
                Retry.times(3).with(waits), CLOCK).chat(ask()));

        assertEquals(3, endpoint.calls.get(), "three attempts, not the default ten");
        assertEquals(2, waits.asked.size());
        assertTrue(waits.asked.get(0) >= 1 && waits.asked.get(0) <= 60,
                "still the shipped jittered schedule: " + waits.asked);
    }

    @Test
    void aConsumerCanSupplyItsOwnScheduleWhichIsWhereRetryAfterWouldGo() {
        Flaky endpoint = new Flaky(2, RateLimitException::new, "slow down");
        Waits waits = new Waits();

        // COMPOSES WITH THE SHIPPED SCHEDULE RATHER THAN REPLACING IT, which is the whole shape of
        // this. A rule that answers a flat number throws away the growth AND the draw, and every
        // rate-limited lane then returns at the same second — the herd the jitter exists to break.
        // So: take whichever is longer, and keep a draw on top of the server's number too.
        Backoff shipped = Retry.fromEnv().backoff();
        Backoff honoursTheServer = failed -> {
            Duration ours = shipped.before(failed);
            Duration theirs = retryAfter(failed.get(failed.size() - 1));
            return theirs == null || theirs.compareTo(ours) < 0
                    ? ours
                    : theirs.plusSeconds(DRAW.getAsInt());
        };

        Model.wrap(endpoint, new Notes(),
                Retry.fromEnv().with(honoursTheServer).with(waits), CLOCK).chat(ask());

        assertEquals(2, waits.asked.size());
        for (long wait : waits.asked) {
            assertTrue(wait >= 90 && wait < 150,
                    "the server's 90s is honoured and a draw sits on top of it: " + wait);
        }
    }

    @Test
    void composingWithTheShippedScheduleKeepsTheLanesApart() {
        // The failure mode of the obvious version: `? Duration.ofSeconds(90) : ...` answers the
        // same number to every lane, so eight rate-limited lanes return in the same second.
        Backoff shipped = Retry.fromEnv().backoff();
        Backoff composed = failed -> {
            Duration ours = shipped.before(failed);
            Duration theirs = retryAfter(failed.get(failed.size() - 1));
            return theirs == null || theirs.compareTo(ours) < 0
                    ? ours : theirs.plusSeconds(DRAW.getAsInt());
        };

        List<Throwable> oneRateLimit = List.of(new RateLimitException("slow down"));
        Set<Long> drawn = new HashSet<>();
        for (int lane = 0; lane < 200; lane++) {
            drawn.add(composed.before(oneRateLimit).toSeconds());
        }

        assertTrue(drawn.size() > 1,
                "two hundred lanes must not all pick the same second; got " + drawn);
        assertTrue(drawn.stream().allMatch(w -> w >= 90),
                "and none of them may go before the server said: " + drawn);
    }

    /** What a real one would read off a {@code Retry-After} header; null when the server said nothing. */
    private static Duration retryAfter(Throwable failure) {
        return failure instanceof RateLimitException ? Duration.ofSeconds(90) : null;
    }

    /** Stands in for the consumer's own draw. */
    private static final java.util.function.IntSupplier DRAW =
            () -> java.util.concurrent.ThreadLocalRandom.current().nextInt(60);

    @Test
    void pauseNoneLetsAConsumerTestItsOwnPipelineWithoutSleeping() {
        Flaky endpoint = new Flaky(4);
        long before = System.nanoTime();

        Model.wrap(endpoint, new Notes(), Retry.fromEnv().with(Pause.NONE), CLOCK).chat(ask());

        long tookMillis = (System.nanoTime() - before) / 1_000_000;
        assertEquals(5, endpoint.calls.get());
        assertTrue(tookMillis < 500,
                "four waits of the real schedule would be minutes; this took " + tookMillis + "ms");
    }

    @Test
    void aPolicyThatWouldAskNothingIsRefusedAtTheDoor() {
        assertThrows(IllegalArgumentException.class,
                () -> new Retry(0, Duration.ZERO, Backoff.immediate(), t -> true, Pause.NONE),
                "zero attempts asks the model nothing at all, which is never what anyone meant");
    }

    @Test
    void theWithMethodsChangeOneFieldAndLeaveTheRest() {
        Retry base = Retry.fromEnv();
        Retry changed = base.withAttempts(2).withBudget(Duration.ofMinutes(5));

        assertEquals(2, changed.attempts());
        assertEquals(Duration.ofMinutes(5), changed.budget());
        assertEquals(base.backoff(), changed.backoff(), "the schedule is untouched");
        assertEquals(base.worthRetrying(), changed.worthRetrying(), "and so is the predicate");
        assertEquals(10, base.attempts(), "and the original is unchanged, being a record");
    }

    // ---------------------------------------------------------------- the fakes

    private static final java.util.function.LongSupplier CLOCK = () -> 0L;

    private static ChatRequest ask() {
        return ChatRequest.builder().messages(UserMessage.from("what is the ratchet")).build();
    }

    private static final class Waits implements Pause {
        final List<Long> asked = new ArrayList<>();

        @Override
        public void of(Duration wait) {
            asked.add(wait.toSeconds());
        }
    }

    private static final class Flaky implements StreamingChatModel {
        final AtomicInteger calls = new AtomicInteger();
        private final int drops;
        private final java.util.function.Function<String, RuntimeException> failure;
        private final String because;

        Flaky(int drops) {
            this(drops, IllegalStateException::new, "connection reset");
        }

        Flaky(int drops, java.util.function.Function<String, RuntimeException> failure, String because) {
            this.drops = drops;
            this.failure = failure;
            this.because = because;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            int call = calls.incrementAndGet();
            if (call <= drops) {
                handler.onError(failure.apply(because));
                return;
            }
            handler.onPartialResponse("ans");
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from("answer " + call)).build());
        }
    }

    private static final class Notes implements Trace {
        public void asked(String agent, String prompt, String reply) {
        }

        public void progress(String key, String note) {
        }

        public void applied(String stage, String what) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String f, String t, String c) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String k, String s, String w, boolean before, boolean after) {
        }

        public void failed(String k, Throwable c) {
        }

        public void priced(String k, String m, String i) {
        }
    }
}
