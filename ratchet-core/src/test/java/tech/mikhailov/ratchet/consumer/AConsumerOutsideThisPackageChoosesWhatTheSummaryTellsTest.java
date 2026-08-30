package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import tech.mikhailov.ratchet.record.JsonlTrace;
import tech.mikhailov.ratchet.record.Telling;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IN ANOTHER PACKAGE, BECAUSE {@link Trace#happened} HAS NO CALLER INSIDE THIS LIBRARY AT ALL.
 *
 * <p>It exists to be called from outside — one consumer exposes it as a {@code what_happened} tool,
 * which is how one stage's agent learns what an earlier stage's agent concluded — and the one thing
 * outside could not reach was how much of each line survived. 180 characters, a private literal.
 * That is the eighth seam in this library to stop one step short of the package boundary, and the
 * first where the method itself is consumer-only.
 *
 * <p>ratchet-core had no test outside its own packages until this file. ratchet-llm gained one
 * with {@code Keeping}; the same argument applies here and the same one directory buys it.
 */
class AConsumerOutsideThisPackageChoosesWhatTheSummaryTellsTest {

    private static final String LONG = "y".repeat(4_000);

    @Test
    void aConsumerOutsideThisPackageChoosesHowMuchOfEachLineTheSummaryTells(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.progress("", LONG);

        String whole = trace.happened("", "", 10, Telling.whole());

        assertTrue(whole.contains(LONG),
                "a consumer that says whole gets the line whole, which before this door meant "
                        + "the agent re-reading whatever the line was about");
    }

    @Test
    void aSummaryNobodyChoseForStillClipsWhereItAlwaysDid(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.progress("", LONG);

        String shipped = trace.happened("", "", 10);

        assertFalse(shipped.contains(LONG), "the default did not move");
        assertTrue(shipped.contains("y".repeat(180) + " ..."),
                "and it is still exactly 180 characters and the marker: " + shipped);
    }

    @Test
    void aConsumerCanBoundOneKindWithoutBoundingTheOthers(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.progress("", LONG);
        trace.applied("a-stage", LONG);

        String mixed = trace.happened("", "", 10, Telling.whole().butFor("progress", 40));

        assertTrue(mixed.contains("* " + "y".repeat(40) + " ..."),
                "the kind they bounded is bounded: " + mixed.substring(0, Math.min(120, mixed.length())));
        assertTrue(mixed.contains("[a-stage] " + LONG),
                "and the kind they did not is not");
    }

    @Test
    void aBoundThatTellsAnAgentNothingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Telling.upTo(0));
        assertThrows(IllegalArgumentException.class, () -> Telling.whole().butFor("tool", -1));
        assertEquals(1, Telling.upTo(1).room("tool", "anything"),
                "one character is a choice somebody made; zero is a line that says nothing");
    }

    /**
     * The property the other three bounds in this library already had and this one did not. A
     * marker reading only {@code " ..."} cannot be told apart in a corpus from the watcher's clip
     * or the record's, so a reader who finds a stub cannot learn which bound produced it — which
     * is what the consumer who reported this had to read the sources to work out.
     */
    @Test
    void whateverTheSummaryCutsSaysHowMuchThereWasSoTheClipsCanBeToldApart(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.progress("", LONG);

        String cut = trace.happened("", "", 10, Telling.upTo(50));

        assertTrue(cut.contains("(truncated, total 4000 chars)"),
                "the size of the whole line, so a reader knows what is missing and which bound "
                        + "took it: " + cut);
    }

    private static Trace written(Path dir) {
        return new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settlements.jsonl"), "run");
    }
}
