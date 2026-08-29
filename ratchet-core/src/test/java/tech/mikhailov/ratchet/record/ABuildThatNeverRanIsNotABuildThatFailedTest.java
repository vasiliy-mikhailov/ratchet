package tech.mikhailov.ratchet.record;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A MISSING TOOLCHAIN AND A FAILING TEST CALL FOR OPPOSITE RESPONSES, and the summary said the same
 * thing about both.
 *
 * <p>{@link Trace.Outcome} carries {@code infra} to separate them: a build that could not run at all
 * is not a build that ran and failed. One means fix the environment; the other means fix the code.
 *
 * <p>THE BRANCH THAT SAID SO WAS UNREACHABLE FOR THE LIFE OF THE FILE. {@code built} writes
 * {@code "infra":"true"} — a quoted string, because the row builder takes String values — and
 * {@code happened} tested {@code row.contains("\"infra\":true")} without the quotes. It never
 * matched, so every build that never ran was summarised as one that ran.
 *
 * <p>IT WAS FOUND BY A MUTATION THAT COULD NOT BE KILLED. PIT flipped the condition and no test
 * changed its answer — not because the tests were weak, but because a branch nothing can reach
 * cannot be made to behave differently. An unkillable mutant is usually a sign the mutation is
 * equivalent; here it was a sign the code was dead. That distinction is the reason this run was
 * worth doing, and it is why the fix reads the field rather than grepping for it.
 */
class ABuildThatNeverRanIsNotABuildThatFailedTest {

    @Test
    void theSummaryLineForABuildThatCouldNotRunSaysItDidNotRun(@TempDir Path dir) throws Exception {
        JsonlTrace trace = new JsonlTrace(dir.resolve("t.jsonl"), dir.resolve("s.jsonl"), "run");

        trace.built("compile", new Trace.Outcome(true, false, "the toolchain was missing"));

        assertTrue(trace.happened("", "", 5).contains("did not run"),
                "infra=true is a build that never happened, and reporting it as one that ran sends "
                        + "a reader to the code when the fault is the environment: "
                        + trace.happened("", "", 5));
    }

    @Test
    void aBuildThatRanAndFailedIsStillReportedAsHavingRun(@TempDir Path dir) throws Exception {
        JsonlTrace trace = new JsonlTrace(dir.resolve("t.jsonl"), dir.resolve("s.jsonl"), "run");

        trace.built("test", new Trace.Outcome(false, false, "nine assertions failed"));

        String said = trace.happened("", "", 5);
        assertTrue(said.contains("ran") && !said.contains("did not run"),
                "the other half of the distinction, so a fix cannot invert it: " + said);
    }

    @Test
    void theRowIsWrittenWithTheValueTheReaderLooksFor(@TempDir Path dir) throws Exception {
        // THE REGRESSION GUARD, at the seam where the two halves disagreed. A future change to how
        // rows are written must not silently reopen the gap: the writer's spelling and the reader's
        // expectation are asserted against each other rather than each against a literal.
        JsonlTrace trace = new JsonlTrace(dir.resolve("t.jsonl"), dir.resolve("s.jsonl"), "run");

        trace.built("compile", new Trace.Outcome(true, true, "nothing to do"));

        String row = Files.readString(dir.resolve("t.jsonl")).strip();
        assertEquals("true", Json.read(row, "infra"),
                "whatever the row's shape, the field must read back as the flag that was passed: "
                        + row);
    }
}
