package tech.mikhailov.ratchet.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A BLANK ANSWER IS THE ONE FAILURE THAT LOOKS LIKE SUCCESS.
 *
 * <p>Every closed word list here falls back to its first word, and a critic's first word approves,
 * so an agent that says nothing approves by default. It was hand-written twice, the two copies
 * differed, and the copy that was written later was written without the guard at all: its first two
 * rounds returned nothing and recorded nothing, which reads exactly like a fleet with no problems.
 * These are the four things both copies were meant to do.
 */
class SilenceIsNotAgreementTest {

    @Test
    void anAgentThatSaysNothingIsAskedAgain() {
        Notes notes = new Notes();
        AtomicInteger retries = new AtomicInteger();
        Agent silent = task -> "";
        Agent second = task -> {
            retries.incrementAndGet();
            return "sound";
        };

        String reply = run(Insisting.on("critic", "you judge", silent, () -> second, notes));

        assertEquals("sound", reply, "the second answer is the answer");
        assertEquals(1, retries.get(), "asked once more, not twice more");
    }

    @Test
    void anAnswerIsNotSecondGuessed() {
        Notes notes = new Notes();
        AtomicInteger retries = new AtomicInteger();
        Agent spoke = task -> "gaming: the stub deletes the work";
        Agent second = task -> {
            retries.incrementAndGet();
            return "sound";
        };

        String reply = run(Insisting.on("critic", "you judge", spoke, () -> second, notes));

        assertEquals("gaming: the stub deletes the work", reply);
        assertEquals(0, retries.get(), "and the retry model was never even built");
        assertEquals(1, notes.asked.size(), "one exchange, recorded once");
    }

    @Test
    void bothAttemptsAreInTheRecordIncludingTheOneThatSaidNothing() {
        // A retry that overwrote the blank would leave a record in which the failure never happened,
        // and the blank is the row a reader needs to see the model derailing.
        Notes notes = new Notes();

        run(Insisting.on("critic", "you judge", task -> "", () -> task -> "sound", notes));

        assertEquals(List.of("", "sound"), notes.asked, "the blank, then the answer: " + notes.asked);
        assertTrue(notes.progress.stream().anyMatch(n -> n.contains("answered nothing")),
                "and the record says so in words: " + notes.progress);
    }

    @Test
    void anUnreachableModelWithholdsAndSaysSoRatherThanEndingTheRun() {
        // SWALLOWING THE EXCEPTION MADE A BROKEN SUPERVISOR LOOK LIKE A CLEAN FLEET. It ran for the
        // better part of an hour with no credentials at all, and every call threw, was caught, and
        // came back as an empty answer.
        Notes notes = new Notes();
        Agent broken = task -> {
            throw new IllegalStateException("401 Unauthorized");
        };

        String reply = run(Insisting.on("critic", "you judge", broken, () -> task -> "sound", notes));

        assertEquals("sound", reply, "the run carries on");
        assertTrue(notes.progress.stream().anyMatch(n -> n.contains("401 Unauthorized")),
                "and the reason is in the record rather than nowhere: " + notes.progress);
    }

    @Test
    void aRetryThatAlsoFailsIsAnEmptyAnswerRatherThanAThrow() {
        Notes notes = new Notes();
        Agent broken = task -> {
            throw new IllegalStateException("still down");
        };

        String reply = run(Insisting.on("critic", "you judge", broken, () -> broken, notes));

        assertEquals("", reply, "an empty judgement, which every caller here already reads as one");
    }

    private static String run(Agent agent) {
        try {
            return agent.run("the question");
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A trace that keeps the two things this decorator is judged on. */
    private static final class Notes implements Trace {
        final List<String> asked = new ArrayList<>();
        final List<String> progress = new ArrayList<>();

        public void asked(String agent, String prompt, String reply) {
            asked.add(reply);
        }

        public void progress(String key, String note) {
            progress.add(note);
        }

        public void applied(String stage, String what) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String agent, String f, String t, String c) {
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
