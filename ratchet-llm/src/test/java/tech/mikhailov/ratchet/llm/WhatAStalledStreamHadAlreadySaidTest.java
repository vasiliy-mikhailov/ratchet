package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A STALLED GENERATION'S TOKENS ARE THE DIAGNOSIS, AND THEY WERE BEING DROPPED ON ARRIVAL.
 *
 * <p>Reported as ratchet#7 by a consumer who went to delete their own 173-line stall guard in favour
 * of this one and could not, for two reasons. Both were seams stopping one step short of the package
 * boundary, which is the fourth time — {@code Backoff}/{@code Pause} before 0.8.0, the clock before
 * 0.9.0, the endpoint before 0.10.0.
 *
 * <p>The second reason was the sharper one and worse than reported. The guard was then a wrapper
 * around somebody else's streaming client: it took a {@link Trace} and never wrote to it —
 * {@code grep -n "trace\."} returned nothing — but it also never KEPT the tokens: the
 * partial-response callback took the text and used it only to stamp a clock. So on a stall there was
 * nothing to record even had it wanted to, and the only evidence of a three-hour lane was that it
 * had lasted three hours.
 *
 * <p>WHERE THE BEHAVIOUR LIVES NOW. The wrapper went with the client underneath it. {@link Wire}
 * owns the socket, so both guards are its own read loop's: every delta goes to the {@link Reasoning}
 * accumulator as it arrives, and a stall, a ceiling or a truncation writes that row on the way out.
 * The frames go in the front door here — the same {@code read} a socket feeds — which is the only
 * reason a stream that stops producing, something no real endpoint will do on request, is assertable
 * at all.
 */
class WhatAStalledStreamHadAlreadySaidTest {

    @Test
    // KEPT, AND THE ONLY ONE IN THE FILE. A guard that stops firing does not fail a test here, it
    // hangs one: the stream is infinite by construction. With the clock handed in the loop no
    // longer waits on real time, so the hang would be a spin rather than a sleep — faster to notice
    // and no less fatal to a build without this.
    @Timeout(60)
    void aStalledStreamPutsWhatItSaidIntoTheRecordBeforeThrowing() {
        Notes notes = new Notes();
        // Thinks twice, starts an answer, then goes quiet for ever. The stall bound is
        // milliseconds here, which is only possible because Watch is a value now — this is the
        // test the constants forbade.
        // THE FIXTURE THINKS FIRST, AND IT NO LONGER HAS TO. This comment used to concede a gap:
        // the accumulator wrote its row off the THINKING buffer, so a stall in a generation with
        // thinking turned off recorded nothing at all — the very loss ratchet#7 reported, rebuilt
        // one layer down after the wrapper it reported against was deleted. It holds now, and
        // aStallInAGenerationThatOnlyAnsweredIsStillRecorded is where it is pinned.
        Wire guarded = client(notes, Watch.shipped(), Now.steppingBy(Duration.ofMinutes(5)));

        // IT NO LONGER COSTS A TICK OF WALL CLOCK. The loop used to notice silence only when its
        // poll timed out — the fifteen-second heartbeat — so no bound below it could fire sooner and
        // every test here paid that. Silence is keep-alive frames now, which arrive, so the guard
        // behind it never ends on its own.
        IllegalStateException stalled = assertThrows(IllegalStateException.class,
                () -> guarded.read(saysThenStalls("The First Consul", "Let me think about ",
                        "whether Napoleon ")));

        assertTrue(stalled.getMessage().contains("not producing"), stalled.getMessage());
        assertEquals(1, notes.thoughts.size(), "the tokens reached the record: " + notes.thoughts);
        assertTrue(notes.thoughts.get(0).contains("Let me think about whether Napoleon "),
                "and they are what the stream actually said: " + notes.thoughts.get(0));
        // THE ANSWER HALF TOO. The wrapper this replaced kept one buffer, filled from the
        // partial-CONTENT callback, so what the original test proved was that content produced
        // before the silence survived. It still must — a stall mid-answer is as diagnostic as a
        // stall mid-thought — but it is the third column now rather than the second.
        assertTrue(notes.thoughts.get(0).contains("The First Consul"),
                "including the answer it had started on: " + notes.thoughts.get(0));
        assertTrue(notes.thoughts.get(0).startsWith("no token for"),
                "and the row says what ended it, which no finish reason ever arrived to say: "
                        + notes.thoughts.get(0));
    }

    @Test
    void aTruncationRecordsTheReasoningThatSpentTheBudget() {
        // The case that argues hardest for keeping them: Truncated exists precisely BECAUSE the
        // answer is blank, so what was produced before it is the only thing there is to look at.
        Notes notes = new Notes();

        assertThrows(Truncated.class, () -> client(notes, Watch.shipped())
                .read(ended("length", "the Bourbons fled from the Revolution", "")));

        assertEquals(1, notes.thoughts.size());
        assertTrue(notes.thoughts.get(0).contains("the Bourbons fled"), notes.thoughts.get(0));
    }

    @Test
    void aCallThatWorksRecordsNothingExtra() {
        // EXTRA is the word that carries the claim, and it means more now than it did. This
        // generation does no thinking, so the ordinary one-row-per-response thought is not written
        // either and the count is a clean zero — a call that HAD thought would leave that row on
        // its way out, by design, which is TheReasoningIsNotDiscarded's subject rather than this
        // one. What must never appear here is a row a guard wrote.
        Notes notes = new Notes();

        Reply reply = client(notes, Watch.shipped()).read(ended("stop", "", "an answer"));

        assertEquals("an answer", reply.said());
        assertEquals(0, notes.thoughts.size(), "nothing failed, so there is nothing to diagnose");
    }

    @Test
    void theTwoBoundsAreValuesRatherThanClassInitConstants() {
        // ratchet#7: a settings page writes a file and every call re-reads it, so patience can
        // change while a 350-marker sweep runs. Restarting a container to change an environment
        // variable kills the pool and orphans every claim in flight.
        Watch patient = Watch.shipped().withStall(Duration.ofMinutes(45));

        assertEquals(Duration.ofMinutes(45), patient.stall());
        assertEquals(Duration.ofHours(3), patient.ceiling(), "and the other is untouched");
        assertEquals(Duration.ofMinutes(20), Watch.shipped().stall(), "the default is unchanged");
    }

    @Test
    void aWatchThatCouldOnlyEverFireIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Watch(Duration.ZERO, Duration.ofHours(3)),
                "a stall bound of zero ends every call, including the ones that are working");
        assertThrows(IllegalArgumentException.class,
                () -> new Watch(Duration.ofHours(4), Duration.ofHours(3)),
                "a ceiling below the stall makes the ceiling the only guard that ever fires");
    }

    @Test
    void aTraceThatThrowsDoesNotSwallowTheFailureUnderneath() {
        // Recording must not break the call it is recording — but it must not hide it either.
        Trace broken = new Notes() {
            @Override
            public void thought(String f, String t, String c) {
                throw new IllegalStateException("the record is unwritable");
            }
        };
        Wire guarded = client(broken, Watch.shipped(), Now.steppingBy(Duration.ofMinutes(5)));

        IllegalStateException stalled = assertThrows(IllegalStateException.class,
                () -> guarded.read(saysThenStalls("", "half a thought")));
        assertTrue(stalled.getMessage().contains("not producing"),
                "the stall is the news, not the unwritable record: " + stalled.getMessage());
        assertFalse(stalled.getMessage().contains("unwritable"), stalled.getMessage());
    }

    // ---------------------------------------------------------------- the fakes

    @Test
    void aStallInAGenerationThatOnlyAnsweredIsStillRecorded() {
        // THE HALF THIS FILE USED TO CONCEDE IT COULD NOT PROVE, in the comment three tests above.
        // The accumulator wrote its row off the THINKING buffer, so a generation that produced
        // CONTENT and no reasoning recorded nothing whatever when a guard stopped it.
        //
        // That is not a corner. Thinking off is what Model.forRetry uses on every re-ask — measured
        // at 0 of 10 runaway against a 62.5% control — so the call most likely to be running when a
        // lane wedges was the one call that left no evidence behind.
        Notes notes = new Notes();
        Wire guarded = client(notes, Watch.shipped(), Now.steppingBy(Duration.ofMinutes(5)));

        assertThrows(IllegalStateException.class,
                () -> guarded.read(saysThenStalls("a partial answer that was going somewhere")));

        assertEquals(1, notes.thoughts.size(),
                "a stall with thinking off left no row at all before this: " + notes.thoughts);
        assertTrue(notes.thoughts.get(0).endsWith(":: a partial answer that was going somewhere"),
                "and what it had said is in the answer column: " + notes.thoughts.get(0));
    }

    /**
     * A client with no socket under it, so the guards can be handed frames directly.
     *
     * <p>Everything the read loop consults is a value now — the patience, the sampling, the
     * endpoint, the trace — which is what lets a stall be provoked in milliseconds instead of
     * twenty minutes, and without a server that would have to agree to stop talking.
     */
    private static Wire client(Trace trace, Watch watch) {
        return client(trace, watch, Now.SYSTEM);
    }

    /**
     * THE CLOCK IS HANDED IN, WHICH IS WHY THESE TESTS CAN ASSERT THE SHIPPED BOUND.
     *
     * <p>They used to pass a one-millisecond stall and block the body on a latch, because the only
     * way to provoke a real twenty-minute guard was to wait twenty minutes. A one-millisecond bound
     * describes nothing — and once the stall began being checked on every pass rather than only on
     * a poll timeout, it stopped even working: it fired before the first frame was read.
     */
    private static Wire client(Trace trace, Watch watch, Now now) {
        return new Wire(Endpoint.of("http://localhost:1", "a-model"), Sampling.deterministic(),
                watch, true, trace, now);
    }

    /**
     * Thinks the given pieces, gets as far as {@code answer}, then never sends another line.
     *
     * <p>Both halves, because both are what the stream had already produced and the row keeps them
     * in different columns. The reasoning arrives a delta at a time, which is the accumulation the
     * assertion joins back up; the answer is one frame, since a stall mid-answer and a stall
     * mid-thought reach the record by the same path. Pass {@code ""} for an answer that had not
     * started.
     *
     * <p>The body has to BLOCK rather than end: a stream that finishes is a completed response, and
     * the guard being proved here is the one for a connection that is still open and has stopped
     * producing. Frames that are not {@code data:} lines would not do either — they are skipped
     * without touching the last-token clock, but they are also not silence.
     */
    private static Stream<String> saysThenStalls(String answer, String... pieces) {
        List<String> frames = new ArrayList<>();
        for (String piece : pieces) {
            frames.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"" + piece
                    + "\"},\"finish_reason\":null}]}");
            frames.add("");
        }
        if (!answer.isEmpty()) {
            frames.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + answer
                    + "\"},\"finish_reason\":null}]}");
            frames.add("");
        }
        // AND THEN KEEP-ALIVES FOR EVER, which is what an idle SSE connection actually sends and
        // what this fixture used to model by BLOCKING a thread on a latch nobody counts down.
        // Blocking made the loop wait a real tick to notice — fifteen seconds of wall clock per
        // test — and it modelled a socket that has died rather than one that has gone quiet. A
        // blank line is the frame separator: the loop skips it without touching the last-token
        // mark, so it is silence to the guard and traffic to the socket, and nothing has to wait.
        return Stream.concat(frames.stream(), Stream.generate(() -> "").limit(1_000_000));
    }

    /**
     * One generation, in the frame shapes captured from the production endpoint: the opening role
     * delta, whatever it thought and said, the finish reason, then {@code [DONE]}.
     */
    private static Stream<String> ended(String why, String reasoning, String content) {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                        + "\"content\":\"\"},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"" + reasoning + "\"},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
                        + "\"finish_reason\":\"" + why + "\"}]}",
                "",
                "data: [DONE]");
    }

    private static class Notes implements Trace {
        final List<String> thoughts = new ArrayList<>();

        /**
         * All three columns, because the tokens this file is about now arrive in two of them.
         *
         * <p>The wrapper this replaced had one buffer and put it in the middle column. The
         * accumulator keeps the thinking and the answer apart, so a row that dropped the third
         * column would hide exactly half of what the stalled stream had already produced.
         */
        public void thought(String f, String t, String c) {
            thoughts.add(f + " :: " + t + " :: " + c);
        }

        public void asked(String a, String p, String r) { }
        public void progress(String k, String n) { }
        public void applied(String s, String w) { }
        public void tool(String a, String t, String g, String r) { }
        public void built(String p, Trace.Outcome r) { }
        public void settled(String k, String s, String w, boolean b, boolean a) { }
        public void failed(String k, Throwable c) { }
        public void priced(String k, String m, String i) { }
    }
}
