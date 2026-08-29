package tech.mikhailov.ratchet.record;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A VALUE THIS READER CANNOT PARSE COSTS THAT FIELD AND NOTHING AFTER IT.
 *
 * <p>{@link Json#row} is flat by contract and the rows this library writes itself are flat. THE TAIL
 * OF A SETTLED ROW IS NOT THIS LIBRARY'S. {@link Settlement#note} splices the pipeline's own fields
 * onto the row verbatim — "JSON fields naming the pipeline, without a leading comma" — and what
 * composes them is a consumer this library cannot see. A fingerprint that names a list of pins or a
 * toolchain is a composite value, and {@code row} does not understand one: it stops the value at
 * the first comma or closing bracket inside it and takes the truncation. That is the documented
 * price of having no tree model, and it is a price per FIELD.
 *
 * <p>WHAT MUST SURVIVE IT IS THE REST OF THE ROW, because the same row is what {@link Resume} reads
 * to decide whether a killed run may pick up its own work. Resume compares the version field by
 * field through the reader the consumer hands in, and its own javadoc says why: "a missing field
 * changes the string". A row whose {@code commit} is read as absent is a row from a different
 * version, so the resumed run declines to pick up and re-runs stages the killed one already paid
 * for — against a workspace those stages were not built from.
 *
 * <p>THE MUTATION THIS FILE WAS WRITTEN FOR. {@code i = stop + 1} became {@code i = stop - 1}:
 * the scan resumes ON the punctuation that ended the value instead of past it, so the next field's
 * name is read as a fragment of that punctuation — the map comes back keyed by the object's own
 * closing characters, and {@code commit} is gone with its value filed under them. Every row in the
 * suite was flat, and on a flat row whose values end in a digit or a letter the two are
 * indistinguishable, so nothing noticed. The shape that separates them is one only a caller
 * composes.
 *
 * <p>THE OTHER SURVIVORS ON THESE LINES ARE EQUIVALENT, recorded so the next reader does not hunt
 * them twice. {@code k1 <= 0}, {@code k2 <= 0} and {@code colon <= 0} cannot differ from the
 * {@code < 0} they replace: the scan starts at offset 1 and only ever moves forward, so
 * {@code indexOf} on this row can return -1 or an offset of at least 1, never 0. The three on the
 * loop bound all LOOSEN it, and a further pass can only add a field if THREE characters
 * ({@code "":} — the key may be empty) are left, while the loosest of the three leaves at most one
 * — so they buy an extra pass that reaches a {@code break} and nothing else, and the {@code while}
 * that becomes {@code true} still terminates, because {@code i} advances by at least three on every
 * pass that does not break. And {@code i > from} in {@code number} guards a parse of an EMPTY
 * range: {@code from} is only ever set to an {@code i} the surrounding loops clamped to
 * {@code json.length()}, so {@code parseInt(json, from, from, 10)} passes its bounds check and
 * throws {@link NumberFormatException} into the catch directly below it, returning the same
 * fallback. The guard is a fast path, not a decision.
 *
 * <p>THE OTHER LIMIT OF A TORN VALUE, WHICH IS NOT A MUTANT AND IS NOT TESTED HERE. Everything
 * below is about a value this reader MISREADS. There is one it cannot read at all: the {@code \\u}
 * arm reads four characters past the backslash with no bound check, so a row cut inside an escape —
 * {@code "result":"[INFO] \\u001} — throws {@link IndexOutOfBoundsException} out of {@code row}
 * rather than giving up the fields that arrived. Both callers wrap {@code row} in
 * {@code catch (RuntimeException)}, so there it costs the WHOLE row instead of one field; the same
 * arm in {@link Json#read} has no such wrapper in {@code JsonlTrace.happened}, and takes the method
 * down. This is a live defect, not a documented price, so there is no assertion for it here: the
 * assertion that states the requirement fails against today's source.
 */
class AValueTheRowReaderCannotParseCostsOnlyItsOwnFieldTest {

    /**
     * A settled row as {@link Settlement} composes one, up to the point the pipeline's own fields
     * begin. The field order is the writer's, because the launcher that greps this row reads by
     * name and the reader below reads by order.
     */
    private static final String SETTLED =
            "{\"at\":\"1755000000000\",\"bump\":\"owner/repo|abc123|17|21\",\"state\":\"PAUSED\","
            + "\"because\":\"9 tests, all green\",\"baseline\":true,\"gate\":false,"
            + "\"resumed\":false,";

    @Test
    void theFieldAfterATruncatedObjectIsStillReadUnderItsOwnName() {
        // A pipeline that names its toolchain in its fingerprint composes an object. Nothing in
        // this library does that today and nothing in it can stop a consumer doing it tomorrow.
        Map<String, String> row = Json.row(SETTLED + "\"toolchain\":{\"jdk\":\"21\"},"
                + "\"commit\":\"dd07585e\"}");

        assertEquals("dd07585e", row.get("commit"),
                "the field written after the object is read under its own name; read as absent, "
                        + "Resume sees a different version and re-runs the stages the killed run "
                        + "already paid for: " + row);
        assertEquals("PAUSED", row.get("state"), "and the fields before it are untouched");
        assertEquals("owner/repo|abc123|17|21", row.get("bump"),
                "including the one the whole sweep is keyed by");
        assertEquals("{\"jdk\":\"21\"", row.get("toolchain"),
                "the composite value itself is cut at the punctuation inside it, which is what "
                        + "'flat' costs — one field, and this is the field");
    }

    @Test
    void theSameForAnArrayBecauseAProvenanceIsWhateverTheCallerComposed() {
        // The other composite. A version that names its pins is a list, and a list of objects is
        // the shape a bill of materials arrives in.
        Map<String, String> row = Json.row(SETTLED + "\"pins\":[{\"g\":\"a\"}],"
                + "\"commit\":\"dd07585e\"}");

        assertEquals("dd07585e", row.get("commit"),
                "an array ends the same way an object does and costs the same one field: " + row);
        assertEquals("[{\"g\":\"a\"", row.get("pins"), "and the one field it costs is its own");
    }

    @Test
    void theRowThisLibraryWritesItselfIsExactlyItsOwnFields() {
        // THE ORDINARY CASE, PINNED. Everything above is about a value the reader does not
        // understand; this is the row it was built for, and the scan has to land on every field of
        // it exactly once. A dashboard reads these names and a launcher greps them.
        Map<String, String> row = Json.row(SETTLED + "\"commit\":\"dd07585e\",\"image\":\"\","
                + "\"prompts\":\"5ed4079d\",\"boms\":\"e1cc07d3\"}");

        assertEquals(List.of("at", "bump", "state", "because", "baseline", "gate", "resumed",
                        "commit", "image", "prompts", "boms"),
                List.copyOf(row.keySet()),
                "eleven fields written, the same eleven read, in the order they were written: " + row);
        assertEquals("", row.get("image"),
                "a field written empty is present and empty, which is not the same as absent — "
                        + "Resume compares both sides through one reader and would see the "
                        + "difference as a version change");
        assertEquals("9 tests, all green", row.get("because"),
                "while a comma inside a string is just a comma");
        assertEquals("false", row.get("gate"), "and a bare boolean is the word, not a quoted one");
    }

    @Test
    void aCommaInsideACompositeValueCostsTheFieldAfterItAsWell() {
        // THE LIMIT, WRITTEN DOWN RATHER THAN DISCOVERED. Everything above holds because the value
        // had no top-level comma in it. With one, the scan resumes INSIDE the value, and what
        // follows the comma there is read as though it were the row: the array's second element
        // becomes a field name and the next real field's value is filed under it.
        //
        // So the guarantee is exactly this: a composite value costs its own field, and a composite
        // value CONTAINING A COMMA costs the field after it too. That is the boundary a consumer
        // composing a provenance has to stay inside — flat fields, or a composite with nothing but
        // its own single value in it.
        Map<String, String> row = Json.row(SETTLED + "\"pins\":[\"core\",\"ui\"],"
                + "\"commit\":\"dd07585e\"}");

        // Stated as the whole list the map DOES contain rather than as `commit is absent`: an
        // assertion naming only what must not be there passes on the next shape it did not think
        // of — a `commit` present and empty would satisfy `getOrDefault("commit", "")` exactly as
        // an absent one does, and those are different rows to Resume.
        assertEquals(List.of("at", "bump", "state", "because", "baseline", "gate", "resumed",
                        "pins", "ui"),
                List.copyOf(row.keySet()),
                "the row says these nine fields and commit is not among them: the fingerprint's "
                        + "commit is LOST, and a lost field is a different version, so this row "
                        + "cannot be resumed from whatever it says");
        assertEquals("dd07585e", row.get("ui"),
                "its value is filed under the array element the scan resumed on, so the loss is "
                        + "silent — the map is well formed and says something that was never written");
        assertEquals("PAUSED", row.get("state"),
                "the fields before the composite are still the run's memory, which is the reason "
                        + "the settled row puts the pipeline's own fields last");
    }
}
