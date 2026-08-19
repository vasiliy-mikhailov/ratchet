package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sweep runs for a fortnight and the harness changes daily, so lanes are killed and every killed
 * run started over; one of them had twenty hours in it. The journal is the file that survives the
 * process, so what it says has to be true of a file written by something that was killed while
 * writing it.
 */
class TheJournalOutlivesTheProcessTest {

    @Test
    void whatOneItemFinishedIsNotWhatAnotherFinished(@TempDir Path dir) {
        Journal journal = new Journal(dir.resolve("journal.jsonl"));

        journal.done("before-pins", "core", "raised two pins", "sha1");

        assertEquals("raised two pins", journal.answered("before-pins", "core").orElse(""));
        assertTrue(journal.answered("before-pins", "web").isEmpty(),
                "keyed on the node alone, the other nineteen items are skipped after the first");
    }

    @Test
    void anAnswerOutlivesTheProcessThatMadeIt(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        new Journal(file).done("survey", "one", "the subject is on 8", "sha1");

        Journal resumed = new Journal(file);

        assertEquals("the subject is on 8", resumed.answered("survey", "one").orElse(""));
    }

    @Test
    void theLastLineIsUsuallyTornAndThatIsNotCorruption(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        Journal killed = new Journal(file);
        killed.done("before-pins", "core", "raised two pins", "sha1");
        killed.fact("baseline", "7 tests, all green");
        // What a kill in the middle of a write leaves behind: half a row, and no newline after it.
        Files.writeString(file, "{\"at\":\"1\",\"kind\":\"done\",\"node\":\"before-pins\",\"key\":\"w",
                StandardOpenOption.APPEND);

        Journal resumed = new Journal(file);

        assertEquals("raised two pins", resumed.answered("before-pins", "core").orElse(""),
                "a torn tail must not cost the rows written before it");
        assertEquals("7 tests, all green", resumed.fact("baseline").orElse(""));
        assertTrue(resumed.answered("before-pins", "w").isEmpty(), "half a row is not a row");
    }

    @Test
    void aTornRowThatHappensToParseIsStillNotAnAnswer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        // Cut inside the answer, after a brace the answer itself contained. It parses, it closes,
        // and every field a reader looks at is there. Only the end marker says it was never
        // finished, which is the whole reason the marker exists.
        Files.writeString(file, "{\"at\":\"1\",\"kind\":\"done\",\"node\":\"migrate\",\"key\":\"core\","
                + "\"answer\":\"applied ${revision}\n", StandardOpenOption.CREATE);

        Journal resumed = new Journal(file);

        assertTrue(resumed.answered("migrate", "core").isEmpty(),
                "replaying half an answer is worse than running the stage again");
    }

    @Test
    void theNextRowDoesNotSpliceOntoTheStump(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        String stump = "{\"at\":\"1\",\"kind\":\"done\",\"node\":\"before-pins\",\"key\":\"w";
        new Journal(file).done("before-pins", "core", "raised two pins", "sha1");
        Files.writeString(file, stump, StandardOpenOption.APPEND);

        Journal resumed = new Journal(file);
        resumed.done("before-pins", "web", "raised one pin", "sha2");

        List<String> rows = readRows(file);
        // The assertion is the STUMP, alone on its line. Appended straight onto it, the new row
        // still parses and still carries an end marker, because the parser walks the fields left to
        // right and the last value for each name wins. It reads as one row with two rows' fields in
        // it, which is a resume that skips a stage it was never told about.
        assertTrue(rows.contains(stump), "the torn line keeps its own line: " + rows);
        assertEquals(3, rows.size(), "the first row, the stump, and the new row: " + rows);
        assertEquals("raised one pin", new Journal(file).answered("before-pins", "web").orElse(""));
    }

    @Test
    void theBudgetIsCountedRatherThanKept(@TempDir Path dir) {
        Journal journal = new Journal(dir.resolve("journal.jsonl"));

        journal.done("repair", "core#1", "raised the surefire floor", "sha1");
        journal.done("repair", "core#2", "added the add-opens", "sha2");
        journal.done("repair", "web#1", "vendored the one class", "sha3");
        journal.done("before-pins", "core", "raised two pins", "sha4");

        assertEquals(3, journal.count("repair"), "three repair steps have been paid for");
        assertEquals(1, journal.count("before-pins"), "and a different node is a different budget");
        assertEquals(0, journal.count("gate"), "a node that has done nothing has spent nothing");
    }

    @Test
    void aRepeatedPairIsOneStepRatherThanTwo(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        Journal journal = new Journal(file);

        journal.done("repair", "core#1", "raised the surefire floor", "sha1");
        journal.done("repair", "core#1", "raised the surefire floor", "sha2");

        assertEquals(1, journal.count("repair"),
                "append-only means the file may carry a pair twice; the step still happened once");
        assertEquals(2, readRows(file).size(), "and both rows are still there");
    }

    @Test
    void whatCannotBeMeasuredAgainIsKeptAsAFact(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        Journal journal = new Journal(file);

        journal.fact("baseline", "7 measurements, all green, under conditions that no longer exist");
        journal.fact("items", "core,web");

        Journal resumed = new Journal(file);
        assertEquals("7 measurements, all green, under conditions that no longer exist",
                resumed.fact("baseline").orElse(""));
        assertEquals("core,web", resumed.fact("items").orElse(""),
                "the filter's choice is an agent's decision, not a fact about the subject");
        assertTrue(resumed.fact("nothing anybody recorded").isEmpty());
    }

    @Test
    void theJournalKnowsWhichTreeItWasMadeOn(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        Journal journal = new Journal(file);
        assertTrue(journal.standsOn("sha1"), "nothing has completed, so nothing disagrees");

        journal.done("before-pins", "core", "raised two pins", "sha1");
        journal.done("after-pins", "core", "raised one more", "sha2");

        Journal resumed = new Journal(file);
        assertEquals("sha2", resumed.tree().orElse(""), "the tree the last completed node left");
        assertTrue(resumed.standsOn("sha2"));
        assertFalse(resumed.standsOn("sha1"),
                "resuming onto a workspace that has moved is worse than starting over");
    }

    @Test
    void historyIsNeverRewritten(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        Journal journal = new Journal(file);

        journal.done("survey", "one", "the subject is on 8", "sha1");
        String first = readRows(file).get(0);
        journal.done("before-pins", "core", "raised two pins", "sha2");
        journal.fact("baseline", "7 tests");

        List<String> rows = readRows(file);
        assertEquals(3, rows.size(), "one row per call, appended");
        assertEquals(first, rows.get(0), "the first row is untouched, so a second reader can tail it");
    }

    @Test
    void anAnswerWithEveryAwkwardCharacterComesBackIntact(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        String nasty = "line one\n\t\"quoted\" \\ backslash\nline three";

        new Journal(file).done("migrate", "core", nasty, "sha1");

        assertEquals(nasty, new Journal(file).answered("migrate", "core").orElse(""));
        assertEquals(1, readRows(file).size(), "and it is still one line");
    }

    private static List<String> readRows(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
