package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SETTLED ROW EITHER LANDS OR IS SAID OUT LOUD, AND NEITHER OF THOSE MAY END THE RUN.
 *
 * <p>{@link Settlement#note} and {@link Settlement#settled} return void and swallow every
 * {@link java.io.IOException} they meet, because a lane that did its work must not die of a record
 * it could not file. That promise leaves the path the caller named as the only thing between the
 * row and the disk, and a caller really names three kinds: a results tree that does not exist yet,
 * a bare file name with no directory at all, and a path something else is already standing in.
 *
 * <p>WHY LANDING IS THE WHOLE POINT. This file is how a sweep's launcher decides a unit of work is
 * finished — it greps the last row for the key. A row that was never written reads as a lane that
 * never settled, so the work is handed out again; and because the write is silent about its own
 * failure by design, the one line on stderr is the only evidence that ever exists.
 *
 * <p>MEASURED: PIT rewrote {@code if (file.getParent() != null)} to each constant in turn and no
 * test in this module noticed either, and the {@code System.err.println} in the catch beneath it
 * had never been executed by any test at all. Pinned to {@code true}, a caller who names a bare
 * file hands {@code Files.createDirectories} a null and gets a NullPointerException — unchecked,
 * straight past the catch that exists to swallow write failures, killing the run at its last word.
 * Pinned to {@code false}, the first settlement of a sweep is dropped in silence, because the
 * results tree does not exist until a row is written into it.
 */
class TheLastWordSurvivesThePathItWasGivenTest {

    private static final String KEY = "owner/repo|abc123|17|21";

    /**
     * THE SETTLEMENTS TREE IS MADE BY THE ROW THAT NEEDS IT, not borrowed from the trace's.
     *
     * <p>Where the record lives and where the settlements live are two conventions the consumer
     * already had, and they arrive as two independent paths. A test that puts both files in one
     * directory cannot see this at all, because whichever wrote first made the directory for the
     * other: so this one leaves the trace somewhere it can already be written and sends the settled
     * row down a tree that is not there yet.
     */
    @Test
    void theResultsTreeIsCreatedByTheSettledRowItself(@TempDir Path dir) {
        Path settlements = dir.resolve("results").resolve("owner-repo").resolve("17")
                .resolve("settlements.jsonl");

        new JsonlTrace(dir.resolve("trace.jsonl"), settlements, KEY)
                .settled(KEY, "PASS", "14 tests conserved, effective target 21", true, true);

        List<Map<String, String>> rows = Settlement.rowsFor(settlements, KEY);
        assertEquals(1, rows.size(),
                "the tree a sweep writes into does not exist until a row makes it, and the first "
                        + "row of a lane is the one the launcher greps to see the lane is done");
        assertEquals("PASS", rows.get(0).get("state"),
                "and it is the settled row that arrived, not an empty file standing in for it");
    }

    /**
     * A BARE FILE NAME IS A PATH LIKE ANY OTHER, and it is the case the null check is there for.
     *
     * <p>A consumer that names its record {@code settlements.jsonl} and runs where it wants the
     * file has a path whose parent is null. The only way to stand where that caller stands is to
     * name a bare file, so this test names a unique one in the working directory and takes it away
     * again.
     */
    @Test
    void aSettlementNamedWithNoDirectoryAtAllStillLands(@TempDir Path dir) throws Exception {
        Path bare = Path.of("settlement-" + UUID.randomUUID() + ".jsonl");
        assertNull(bare.getParent(), "the case under test is a path with nothing above the file");
        try {
            assertDoesNotThrow(() -> new JsonlTrace(dir.resolve("trace.jsonl"), bare, KEY)
                            .settled(KEY, "PASS", "14 tests conserved", true, true),
                    "creating the parent of a parentless path is a NullPointerException, and it "
                            + "goes straight past the catch that makes this method safe to call: "
                            + "the run would end on the one line that says it succeeded");

            List<Map<String, String>> rows = Settlement.rowsFor(bare, KEY);
            assertEquals(1, rows.size(),
                    "the row belongs in the working directory the caller chose: "
                            + bare.toAbsolutePath());
            assertEquals("PASS", rows.get(0).get("state"),
                    "and the reader finds it there under the same key that wrote it");
        } finally {
            Files.deleteIfExists(bare);
        }
    }

    /**
     * THE ONLY PLACE TO HEAR THIS REQUIREMENT IS WHERE THE COMPLAINT GOES. There is no seam between
     * {@code write} and {@code System.err}, so the test swaps the stream and puts it back. That is
     * process-wide state: the pom configures no parallelism today, and this test needs
     * {@code @Isolated} on the day it does.
     *
     * <p>AND THE COMPLAINT HAS TO NAME THE SETTLEMENT AND THE PATH. Three writers in this package
     * report to that one stream — {@code journal:}, {@code trace:} and {@code settlement:} — and
     * they cost different things: a lost trace line costs a reader the story, a lost settled row
     * costs the launcher its only signal that the lane is finished and the lane is run again. An
     * operator reading a fortnight of sweep log has nothing else to tell those two apart, and
     * nowhere to go with the complaint unless it says which path was in the way.
     *
     * <p>WHERE THE PATH IN THAT LINE ACTUALLY COMES FROM, because it is worth a reader knowing what
     * this test is standing on. {@code write} prints {@code e.getMessage()} and nothing else; it
     * never adds {@code file}. The path is in the line here only because the failure is a
     * {@link java.nio.file.FileSystemException}, which carries the offending path in its own
     * message — observed: {@code settlement: <dir>/results/owner-repo: Not a directory}. An
     * {@link java.io.IOException} whose message does not carry one — a charset encoder refusing a
     * byte, a non-default {@code FileSystem} provider, a terse {@code errno} translation — reaches
     * the operator as {@code settlement: <reason>} with nothing in it to act on, and this
     * assertion would not see that day coming. Naming {@code file} in the complaint is the fix and
     * it is a production change, so it is reported rather than made.
     */
    @Test
    void aSettlementThatCannotBeWrittenNamesItselfAndThePathInTheWay(@TempDir Path dir)
            throws Exception {
        Path occupied = dir.resolve("results");
        Files.writeString(occupied, "a regular file standing where the results tree would have to be");
        Path impossible = occupied.resolve("owner-repo").resolve("settlements.jsonl");
        PrintStream real = System.err;
        ByteArrayOutputStream complaint = new ByteArrayOutputStream();
        System.setErr(new PrintStream(complaint, true, StandardCharsets.UTF_8));
        try {
            assertDoesNotThrow(() -> new JsonlTrace(dir.resolve("trace.jsonl"), impossible, KEY)
                            .settled(KEY, "PASS", "14 tests conserved", true, true),
                    "a lane that finished its work does not fail because its last word could not "
                            + "be filed; the work is done and the record is the casualty");
        } finally {
            System.setErr(real);
        }

        String said = complaint.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("settlement:"),
                "this row is gone and nothing else will ever mention it: the complaint has to say "
                        + "which record was lost, since the trace and the journal report to the "
                        + "same stream and a lost settled row is the expensive one: " + said);
        assertTrue(said.contains(occupied.toString()),
                "and it has to name the path that could not be made, or an operator is left with "
                        + "a complaint and nowhere to take it. Matched on the results tree rather "
                        + "than on the whole directory the writer wanted, because which of the two "
                        + "the message names is the JDK's choice and not this library's — see the "
                        + "class comment: " + said);
        assertFalse(Files.exists(impossible),
                "nothing was written, which is exactly why the line above is the whole record of it");
    }
}
