package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE ROW IS A FORMAT SOMEBODY ELSE PARSES, so it is asserted character for character.
 *
 * <p>Every other test here describes a behaviour. This one pins bytes, and it exists because the
 * consumer that reads them cannot be fixed if they move. A shell launcher decides a unit of work is
 * finished by grepping this row for a field called {@code bump}, and bash re-reads a running script
 * by byte offset, so that launcher cannot be corrected while a sweep is up. A dashboard reads the
 * two booleans by the names {@code baseline} and {@code gate}. None of those three names means
 * anything to this library, and all three are load-bearing.
 *
 * <p>The row below was copied out of a live results tree, from a sweep of 1,428 units of work that
 * was running while this library was extracted. Everything except the clock, the key and the prose
 * is asserted: the field names, their order, the unquoted booleans, and the fact that
 * {@code resumed} sits between {@code gate} and the pipeline fingerprint rather than after it.
 */
class TheSettledRowIsAWireFormatTest {

    /** Verbatim from a live settlements.jsonl, with only the newline inside `because` re-escaped. */
    private static final String LIVE =
            "{\"at\":\"1787087321637\","
            + "\"bump\":\"abdulaziz1928/sievelib4j|683da48ea45725cc343b6931fcb95159a4dc6bf4|17|21\","
            + "\"state\":\"PASS\","
            + "\"because\":\"PASS\\n14 tests conserved, effective target 21; "
            + "CRITICAL+HIGH 0 -> 0\","
            + "\"baseline\":true,\"gate\":true,\"resumed\":false,"
            + "\"commit\":\"dd07585e\",\"image\":\"\",\"prompts\":\"5ed4079d\","
            + "\"boms\":\"e1cc07d3\"}";

    @Test
    void aSettledRowIsByteIdenticalToTheOneTheConsumerAlreadyReads(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("settlements.jsonl");
        String key = "abdulaziz1928/sievelib4j|683da48ea45725cc343b6931fcb95159a4dc6bf4|17|21";
        String because = "PASS\n14 tests conserved, effective target 21; CRITICAL+HIGH 0 -> 0";
        String provenance = "\"commit\":\"dd07585e\",\"image\":\"\",\"prompts\":\"5ed4079d\","
                + "\"boms\":\"e1cc07d3\"";

        new JsonlTrace(dir.resolve("trace.jsonl"), file, key, () -> provenance, List.of(), List.of())
                .settled(key, "PASS", because, true, true, false);

        List<String> rows = Files.readAllLines(file);
        assertEquals(1, rows.size(), "one settled call, one row");
        // The clock is the only field this test cannot fix, so it is the only one masked.
        assertEquals(masked(LIVE), masked(rows.get(0)),
                "a launcher greps this row and cannot be corrected while it is running");
    }

    @Test
    void theProvenanceIsAppendedWithExactlyOneComma(@TempDir Path dir) throws Exception {
        // A leading comma in the supplier, or a second one here, and the row will not parse at all.
        Path file = dir.resolve("settlements.jsonl");
        new JsonlTrace(dir.resolve("trace.jsonl"), file, "k",
                () -> "\"commit\":\"abc\"", List.of(), List.of())
                .settled("k", "PASS", "why", true, false, true);

        String row = Files.readAllLines(file).get(0);
        assertTrue(row.contains(",\"resumed\":true,\"commit\":\"abc\"}"),
                "resumed sits between the two booleans and the fingerprint: " + row);
    }

    @Test
    void aRowWithNoPipelineBehindItSimplyEndsAfterTheBooleans(@TempDir Path dir) throws Exception {
        // A writer that is not one unit of work has no pipeline to name, and must not write a
        // trailing comma announcing a fingerprint that is not there.
        Path file = dir.resolve("settlements.jsonl");
        new JsonlTrace(dir.resolve("trace.jsonl"), file, "k").settled("k", "FAIL", "why", true, false);

        String row = Files.readAllLines(file).get(0);
        assertTrue(row.endsWith(",\"baseline\":true,\"gate\":false,\"resumed\":false}"), row);
    }

    @Test
    void aProgressNoteIsTheSameRowInTheBumpingState(@TempDir Path dir) throws Exception {
        // The launcher's finished-or-not test reads the LAST row for a key, so a progress note has
        // to be the same shape or the last word would be unreadable.
        Path file = dir.resolve("settlements.jsonl");
        new JsonlTrace(dir.resolve("trace.jsonl"), file, "k", () -> "", List.of(), List.of())
                .progress("k", "still going");

        String row = Files.readAllLines(file).get(0);
        assertTrue(row.contains("\"bump\":\"k\",\"state\":\"bumping\",\"because\":\"still going\""), row);
        assertTrue(row.endsWith(",\"baseline\":false,\"gate\":false}"), row);
    }

    @Test
    void everyAwkwardCharacterInTheReasonIsEscapedTheOneWay(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settlements.jsonl");
        new JsonlTrace(dir.resolve("trace.jsonl"), file, "k")
                .settled("k", "FAIL", "a \"quote\", a \\ backslash,\na newline,\ta tab", false, false);

        String row = Files.readAllLines(file).get(0);
        assertEquals("a \"quote\", a \\ backslash,\na newline,\ta tab",
                Json.row(row).get("because"), "written by Settlement, read by Json, one escaper");
        assertEquals(1, Files.readAllLines(file).size(), "and it is still one line");
    }

    private static String masked(String row) {
        return row.replaceFirst("\"at\":\"\\d+\"", "\"at\":\"CLOCK\"");
    }
}
