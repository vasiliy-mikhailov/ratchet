package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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

    // ---------------------------------------------------- navigating instead of being summarised

    /**
     * THE CALL THAT MAKES THE OTHER TWO SAFE RATHER THAN RECKLESS.
     *
     * <p>An agent told there are 1,400 events can decide to read forty. Unbounded reads are only
     * dangerous when nobody knows the size first, which is why the count takes the same narrowing
     * as the slice: a count from one narrowing and a slice from another would not line up.
     */
    @Test
    void theCountAndTheSliceAgreeBecauseBothAreNarrowedTheSameWay(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.asked("planner", "p", "one");
        trace.asked("doer", "p", "two");
        trace.asked("doer", "p", "three");

        assertEquals(3, trace.traceEvents("", ""), "all of them with no narrowing");
        assertEquals(2, trace.traceEvents("", "doer"), "and the narrowing counts what it returns");
        assertEquals(trace.traceEvents("", "doer"),
                trace.traceSlice("", "doer", 0, 99).size(),
                "the count is the size of the slice that asks for everything");
    }

    /** The point of the whole thing: the caller's range replaces the library's guess. */
    @Test
    void aSliceComesBackWholeWhereTheSummaryWouldHaveClippedIt(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.progress("", LONG);

        assertEquals(LONG, trace.traceSlice("", "", 0, 1).get(0).text(),
                "four thousand characters, none of them decided by this library");
        assertFalse(trace.happened("", "", 10).contains(LONG),
                "which the summary at its shipped bound would not have given them");
    }

    @Test
    void anAgentCanWalkBackwardsFromTheEndWithoutDoingArithmetic(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.asked("a", "p", "one");
        trace.asked("a", "p", "two");

        assertEquals(2, trace.traceSlice("", "", -5, 900).size(),
                "an out-of-range read is a question, not a fault");
        assertEquals(List.of(), trace.traceSlice("", "", 900, 901),
                "and past the end is empty rather than a throw");
    }

    /**
     * A LITERAL SUBSTRING, AND THE REASON IS WHO WRITES THE PATTERN.
     *
     * <p>This is a model's input. A model-written regular expression over a 44,000-character row
     * can backtrack for an unbounded time and Java gives no way to stop it.
     */
    @Test
    void aSearchIsALiteralSubstringBecauseAModelWritesThePattern(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.asked("a", "p", "the parent pom is not published");

        assertEquals(1, trace.traceFind("PARENT POM", "", "", 10).size(),
                "case-insensitive, because a model does not match the record's casing");
        assertEquals(List.of(), trace.traceFind("p.rent", "", "", 10),
                "and a regular expression is not one: this is a literal and says so");
    }

    /**
     * THE ONE PLACE THIS DIFFERS FROM THE PROPOSAL IT CAME FROM. The argument that a search bounds
     * itself by being specific holds for a person and not for a model: a needle of "e" has named
     * every row. The ceiling is the CALLER's, which is the difference between them bounding
     * themselves and this library guessing on their behalf.
     */
    @Test
    void aSearchKeepsTheNewestMatchesWhenItHitsTheCeilingTheCallerNamed(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.asked("a", "p", "first mention of maven");
        trace.asked("a", "p", "second mention of maven");
        trace.asked("a", "p", "third mention of maven");

        List<Trace.Event> found = trace.traceFind("maven", "", "", 2);

        assertEquals(2, found.size(), "the caller named two and got two");
        assertEquals("third mention of maven", found.get(0).text(),
                "newest first, because the most recent conclusion is the one that settles it");
    }

    /** happened flattens newlines into a ribbon. That is a rendering decision, not the record. */
    @Test
    void theStructureInsideARowSurvivesWhereTheSummaryFlattensIt(@TempDir Path dir) {
        Trace trace = written(dir);
        trace.asked("a", "p", "line one\nline two\tand a tab");

        assertEquals("line one\nline two\tand a tab", trace.traceSlice("", "", 0, 1).get(0).text(),
                "a caller reading a stack trace or a diff wants the structure that was in it");
        assertFalse(trace.happened("", "", 10).contains("\n" + "line two"),
                "while the summary still promises one line per event");
    }

    private static Trace written(Path dir) {
        return new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settlements.jsonl"), "run");
    }
}
