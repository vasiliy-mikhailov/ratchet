package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trace was written for the corpus and for people, never for the run living inside it.
 *
 * <p>The workspace can say what LANDED. It cannot say what was tried and rejected, which is the
 * half that stops a loop repeating itself: a troubleshooter that already established that a
 * dependency cannot be used should not rediscover it, and a proposer ordering a fourth variation on
 * a step three critics have refused is not reasoning.
 */
class TheRunCanReadItsOwnRecordTest {

    private static final String RUN = "owner/repo|abc123|17|21";

    /** The two lists a pipeline hands the record, which is where its own vocabulary lives. */
    private static final List<String> DECISIVE = List.of("fail_", "pass (");

    private static final List<String> DISPUTED = List.of("gaming", "off-target", "blocked:",
            "rejected", "declined", "reverted");

    @Test
    void rejectedAttemptsAreInItAndLandedOnesAreNotTheWholeStory(@TempDir Path dir) {
        Trace trace = trace(dir);
        trace.applied("gate", "turn 1: FAIL_test_conservation (pre=7 lost=4)");
        trace.asked("step-doer", "the prompt", "WHY: added a Kaptcha bean");
        trace.asked("step-verifier", "the prompt", "gaming - the stub deletes image rendering");
        trace.progress(RUN, "step rejected; handing back to the loop");

        String log = trace.happened("", "", 80);

        assertTrue(log.contains("FAIL_test_conservation"), log);
        assertTrue(log.contains("gaming"), "the objection is the point of reading this: " + log);
        assertTrue(log.contains("step rejected"), log);
        assertEquals(4, log.lines().count(), "one line per event, not the rows themselves");
    }

    @Test
    void itIsSummarisedBecauseItIsReadIntoAPrompt(@TempDir Path dir) {
        Trace trace = trace(dir);
        // A tool result can be tens of thousands of characters; returning it whole would put the
        // conversation inside itself.
        trace.tool("step-doer", "inspect_jar", "{\"artifact\":\"a:b\"}", "x".repeat(50_000));

        String log = trace.happened("", "", 80);

        assertTrue(log.length() < 600, "a summary, not the payload: " + log.length() + " chars");
        assertTrue(log.contains("inspect_jar"), log);
        assertTrue(log.contains("..."), "and it says it was cut");
    }

    @Test
    void filtersNarrowItAndAnEmptyFilterIsNotAnError(@TempDir Path dir) {
        Trace trace = trace(dir);
        trace.applied("migrate", "applied the rewrite");
        trace.applied("prepare", "raised two pins");
        trace.asked("survey-doer", "p", "the subject is on 17");

        assertTrue(trace.happened("migrate", "", 80).contains("applied the rewrite"));
        assertFalse(trace.happened("migrate", "", 80).contains("raised two pins"),
                "a stage filter means that stage");
        assertTrue(trace.happened("", "survey-doer", 80).contains("the subject is on 17"));
        assertEquals("", trace.happened("nosuchstage", "", 80), "an empty answer, never a throw");
    }

    @Test
    void theMostRecentEventsAreTheOnesKept(@TempDir Path dir) {
        Trace trace = trace(dir);
        for (int i = 1; i <= 10; i++) {
            trace.progress(RUN, "event " + i);
        }
        String log = trace.happened("", "", 3);
        assertEquals(3, log.lines().count());
        assertTrue(log.contains("event 10"), "newest last: " + log);
        assertFalse(log.contains("event 7"), "older ones drop off the front: " + log);
    }

    private static Trace trace(Path dir) {
        return new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settlements.jsonl"), RUN,
                () -> "", DECISIVE, DISPUTED);
    }

    @Test
    void whenTheBudgetIsShortTheVerdictsSurviveAndTheFileReadsDoNot() {
        Trace trace = trace(dirFor("rank"));
        // The shape of a real trace: mostly tool calls, with the decisions buried in them.
        for (int i = 1; i <= 30; i++) {
            trace.tool("survey-doer", "read_file", "{\"path\":\"one" + i + ".xml\"}", "<x/>");
        }
        trace.applied("gate", "turn 1: FAIL_test_conservation (pre=7 lost=4)");
        for (int i = 1; i <= 30; i++) {
            trace.tool("survey-doer", "read_file", "{\"path\":\"more" + i + ".xml\"}", "<x/>");
        }
        trace.asked("step-verifier", "p", "gaming - the stub deletes image rendering");

        String log = trace.happened("", "", 5);

        assertEquals(5, log.lines().count());
        assertTrue(log.contains("FAIL_test_conservation"), "a failure is what a reader came for: " + log);
        assertTrue(log.contains("gaming"), "and so is an objection: " + log);
        // Measured on the live run before this: 13 of 349 returned lines carried a decision.
        assertTrue(log.lines().filter(l -> l.contains("read_file")).count() <= 3,
                "file reads fill what is left over, they do not take the budget: " + log);
    }

    // The integer-argument requirement that used to sit here is Json's rather than the trace's, and
    // it now lives with the rest of them in JustEnoughJsonHasToBeEnoughTest, which says why the
    // carve-out exists and covers the cases this stopped at.

    private static Path dirFor(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
