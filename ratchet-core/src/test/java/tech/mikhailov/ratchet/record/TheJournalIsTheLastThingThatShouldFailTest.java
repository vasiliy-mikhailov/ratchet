package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * THE JOURNAL IS THE LAST THING IN A RUN THAT IS ALLOWED TO FAIL, because it is the only part of a
 * run that cannot be derived again. The workspace is on disk and the agents are stateless, but the
 * baseline was measured under conditions that no longer exist and the walk's choices were an
 * agent's decision rather than a fact about the subject. One killed lane had twenty hours in it.
 *
 * <p>So it has to hold under the conditions that actually produce it, which are never the tidy
 * ones: a writer killed mid-byte, a column nobody filled in, a directory nobody made, a file this
 * process may no longer write. Under all of them it must do three things and no others — NOT THROW,
 * so the record never ends the run it is recording; NOT INVENT, so nothing is replayed that did not
 * happen; NOT FORGET, so a row that has less to say than the last one does not erase it.
 *
 * <p>{@link TheJournalOutlivesTheProcessTest} covers the journal being written and read back. Every
 * test here was written against a mutant that survived that one: a change to this class that no
 * test noticed. Several of them turned out to be requirements nobody had written down anywhere,
 * and they say so where they appear. Three more mutants are not tested and cannot be: they are
 * argued as equivalent in the report that came with this file, and an honest "this one changes
 * nothing" is worth more than an assertion built to catch it.
 */
class TheJournalIsTheLastThingThatShouldFailTest {

    /**
     * A NODE THAT FINISHED WITH NOTHING TO SAY HAS STILL FINISHED — nobody had written this down,
     * and the two halves of it cost differently. A stage whose answer reads back as absent is run
     * again, which costs one model call and is otherwise safe. A FACT that reads back as absent is
     * MEASURED again, and by then the workspace has moved: the second measurement is taken of a
     * different tree, it looks exactly like the first, and nothing downstream can tell. That is the
     * whole reason the baseline is in this file rather than derived.
     */
    @Test
    void anAnswerWithNothingInItIsStillAnAnswer(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        Journal journal = new Journal(file);

        journal.done("survey", "core", null, "sha1");
        journal.fact("baseline", null);

        assertEquals("", journal.answered("survey", "core").orElse("absent"),
                "the node completed and said nothing; absent would pay for it a second time");
        assertEquals(1, journal.count("survey"), "and it is a step that has been paid for");
        assertEquals("", journal.fact("baseline").orElse("absent"),
                "a baseline that measured nothing was still measured, and cannot be measured again");

        Journal resumed = new Journal(file);
        assertEquals("", resumed.answered("survey", "core").orElse("absent"),
                "the journal in memory and the journal on disk must agree about an empty answer");
        assertEquals("", resumed.fact("baseline").orElse("absent"),
                "and a baseline read back as absent is measured again against a tree that moved");
    }

    /**
     * A ROW THAT NAMES NO TREE DOES NOT ERASE THE TREE THE LAST ONE NAMED — also written down
     * nowhere. The supplier is a shell out to git and it answers with an empty string whenever it
     * cannot answer at all: a contended index.lock, a workspace that is not a checkout yet, a
     * detached state it will not name. If the next row wrote that emptiness over the tree, then
     * {@link Resume#picksUp} would find {@code tree().isEmpty()} and refuse to resume anything, so
     * one unlucky call to git costs the whole twenty hours — while a caller asking
     * {@link Journal#standsOn} directly would be told this journal belongs to every workspace there
     * is, which is the worse of the two answers.
     *
     * <p>THROUGH THE SUPPLIER RATHER THAN AROUND IT, because the supplier IS the failure being
     * described. The three-argument {@code done} is the only one production calls (Flow line 558)
     * and it is the one that asks git; a test that hands the sha over itself proves the field and
     * not the wiring. The last row uses the four-argument form for the other half of the same
     * requirement: a caller that has nothing to give may hand over nothing.
     */
    @Test
    void aRowThatNamesNoTreeDoesNotEraseTheTreeTheLastOneNamed(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        AtomicReference<String> git = new AtomicReference<>("sha1");
        Journal journal = new Journal(file, git::get);

        journal.done("before-pins", "core", "raised two pins");
        git.set("");
        journal.done("after-pins", "core", "raised one more");
        journal.done("survey", "core", "the subject is on 8", null);

        assertEquals("sha1", journal.tree().orElse("nothing"),
                "two rows that could not name a tree do not unbind the one that did");
        assertFalse(journal.standsOn("a workspace nobody built"),
                "a journal that forgot its tree agrees to every workspace, which is the worst resume");

        Journal resumed = new Journal(file);
        assertEquals("sha1", resumed.tree().orElse("nothing"),
                "and the same holds when it is read back");
        assertFalse(resumed.standsOn("a workspace nobody built"),
                "a resume is refused on the tree the journal names, so the file must keep naming it");
    }

    /**
     * A HOLE IN A COLUMN IS FILED WHERE THE READER WILL LOOK FOR IT. The pair (node, key) is
     * composed in three separate places — the map key, the row written to the file, and the prefix
     * {@link Journal#count} counts — and each one turns an absent half into an empty string on its
     * own. They only have to disagree once: the writer files the row under one string, the file
     * records another, and the resume looks in the third place and finds nothing. A completed stage
     * is then paid for twice and a budget is derived for a node that never ran, with nothing
     * thrown and nothing said.
     */
    @Test
    void aHoleInAColumnIsFiledWhereTheReaderWillLookForIt(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        Journal journal = new Journal(file);

        journal.done("survey", null, "the subject is on 8", "sha1");
        journal.done(null, "core", "raised two pins", "sha2");

        assertEquals("the subject is on 8", journal.answered("survey", null).orElse("absent"),
                "a walk whose item has no key yet has still completed for that item");
        assertEquals(1, journal.count(null),
                "and the count reads the same absent node the row was written under");

        Journal resumed = new Journal(file);
        assertEquals("the subject is on 8", resumed.answered("survey", null).orElse("absent"),
                "the key a row is written under is the key it is read back from, holes included");
        assertEquals("raised two pins", resumed.answered(null, "core").orElse("absent"),
                "and the same for a hole in the other half of the pair");
        assertEquals(1, resumed.count("survey"),
                "and the budget derived from the file is the budget derived from the writer");
    }

    /**
     * A CALLER WITH NOTHING TO BIND A ROW TO MAY HAND OVER NOTHING. The supplier is composed by the
     * consumer, and a consumer that cannot make one has no way to say so except by passing none.
     * That is a journal without a resume, not a run without a journal.
     */
    @Test
    void aCallerWithNothingToBindARowToMayHandOverNothing(@TempDir Path dir) {
        Journal journal = new Journal(dir.resolve("journal.jsonl"), null);

        journal.done("survey", "core", "the subject is on 8");

        assertEquals("the subject is on 8", journal.answered("survey", "core").orElse("absent"),
                "a journal with no way to name the tree still records what finished");
        assertTrue(journal.tree().isEmpty(), "it simply has no tree to bind a resume to");
        assertTrue(journal.standsOn("any workspace at all"),
                "and answering false there would refuse every fresh run");
    }

    /**
     * AN EMPTY JOURNAL IS NOT A TORN ONE. The file exists before the first row lands in it, so a
     * run killed in that window leaves nothing behind but the name. Two things must be true of it,
     * and both are guards that survived a mutant, so neither was written down.
     *
     * <p>OPENING IT MUST NOT WRITE TO IT. A file with nothing in it does not end in a newline
     * either, so it looks exactly like a torn tail to {@code heal}; healing it puts a byte into a
     * record whose entire claim is that every byte in it was put there by something that finished.
     *
     * <p>AND READING IT MUST NOT TRIP OVER A LINE THAT IS NOT THERE. {@code "".split("\n")} is one
     * empty string rather than none, so the empty file is the one input that reaches {@code accept}
     * with nothing to look at, and the length check in front of {@code charAt(0)} is the only thing
     * standing between it and a StringIndexOutOfBoundsException thrown out of the CONSTRUCTOR — by
     * the one class in this library whose contract is that it never ends the run it is recording,
     * and at the one moment it is opened, which is the resume.
     */
    @Test
    void anEmptyJournalIsNotATornOne(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        Files.createFile(file);

        Journal journal = new Journal(file);

        assertEquals(0, Files.size(file), "opening a journal with nothing in it must leave it empty");
        assertTrue(journal.answered("before-pins", "core").isEmpty(), "and it holds nothing");
    }

    /**
     * A LINE THAT DOES NOT BEGIN WHERE A ROW BEGINS IS NOT A ROW, and the end marker cannot be the
     * only thing that decides. The marker cannot be FORGED from inside a value, which is what
     * {@link Journal} says about it and is true. It can be INHERITED, which is not written down
     * anywhere: a line carrying half of one row in front of a whole one carries the whole one's
     * marker too.
     *
     * <p>MEASURED ON THE PARSER RATHER THAN IMAGINED. {@link Json#row} is a scanner, not a
     * validator: it starts at the second character and takes whatever quoted pairs it finds
     * anywhere in the line, so a fact row cut inside the baseline with a completed stage appended
     * behind it reads back as {@code fact(baseline, "7 tests, all green{")} — the first row's kind
     * and name, the truncated measurement, and the second row's end marker. The measurement that
     * cannot be taken again is filed truncated and whole, and the stage that finished is dropped.
     *
     * <p>The leading bytes are gone here because that is what a killed append leaves on a
     * filesystem that journals the length before the contents: the gap comes back as NUL, which is
     * not whitespace and does not strip. THE SHAPE CHECK IS NOT WHAT CATCHES A SPLICE WHOSE FIRST
     * BYTE SURVIVED — healing is, at the moment the file is opened — and the two halves of that
     * division of labour are worth keeping straight.
     */
    @Test
    void aLineThatDoesNotBeginWhereARowBeginsIsNotARow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        String baseline = factRow("baseline", "7 tests, all green");
        String cutInsideTheBaseline =
                baseline.substring(0, baseline.indexOf("green") + "green".length());
        Files.writeString(file, doneRow("survey", "one", "the subject is on 8", "sha1") + "\n"
                + "\0".repeat(3) + cutInsideTheBaseline
                + doneRow("before-pins", "web", "raised one pin", "sha2") + "\n");

        Journal resumed = new Journal(file);

        assertTrue(resumed.fact("baseline").isEmpty(),
                "half a measurement read back as a whole one is the one thing this file exists to "
                        + "prevent, and it came back as: " + resumed.fact("baseline").orElse(""));
        assertTrue(resumed.answered("before-pins", "web").isEmpty(),
                "and the row spliced in behind it is not a row either");
        assertEquals("the subject is on 8", resumed.answered("survey", "one").orElse("absent"),
                "while the whole row in front of it is untouched by any of that");
    }

    /**
     * A LINE CUT BEFORE ITS LAST BYTE IS NOT A ROW EITHER. The marker is written before the brace
     * and the newline, so a line can carry one and still be a write that did not finish.
     *
     * <p>DELIBERATELY THE CONSERVATIVE HALF OF A TRADE, and worth saying out loud because the
     * mutant that survived here is the one that takes the other side. The fields of such a line
     * are, in practice, all present, and refusing it costs a stage that runs again: one model call.
     * A reader cannot tell it apart from a line that inherited somebody else's marker, so there is
     * ONE rule for both — a line is a row when it begins and ends like one — rather than two rules
     * and a way to confuse them. That price is cheap for a done row. It is not cheap for a fact
     * cut in that same one-byte window, which loses a measurement nothing can take again, and this
     * trade is silent about that.
     */
    @Test
    void aLineCutBeforeItsLastByteIsNotARow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        String interrupted = doneRow("migrate", "core", "applied the revision", "sha2");
        Files.writeString(file, doneRow("survey", "one", "the subject is on 8", "sha1") + "\n"
                + interrupted.substring(0, interrupted.length() - 1));

        Journal resumed = new Journal(file);

        assertTrue(resumed.answered("migrate", "core").isEmpty(),
                "the marker says the writer meant to finish the row; the brace says it did");
        assertEquals("sha1", resumed.tree().orElse("nothing"),
                "and a tree named by a write that did not finish is not a tree to resume onto");
        assertEquals("the subject is on 8", resumed.answered("survey", "one").orElse("absent"));
    }

    /**
     * THE FIRST ROW MAKES THE DIRECTORY IT LANDS IN. The journal is written beside the trace of the
     * run it belongs to, in a directory named after that run, so on the first row of the first
     * attempt it does not exist yet.
     *
     * <p>THE ASSERTION IS TAKEN FROM A SECOND JOURNAL ON PURPOSE. The writer's own map answers
     * perfectly whether or not a single byte reached the disk, so a test that asks the writer what
     * it recorded passes for a journal that recorded nothing — and that failure is invisible until
     * the process is killed, which is the only moment this file is for.
     */
    @Test
    void theFirstRowMakesTheDirectoryItLandsIn(@TempDir Path dir) {
        Path file = dir.resolve("runs").resolve("2026-08-29").resolve("journal.jsonl");

        new Journal(file).done("before-pins", "core", "raised two pins", "sha1");

        assertEquals("raised two pins",
                new Journal(file).answered("before-pins", "core").orElse("absent"),
                "a row that never reached the disk is a resume that starts again from nothing");
    }

    /**
     * A PATH WITH NO PARENT IS STILL A JOURNAL, which is what {@code --journal journal.jsonl} hands
     * over. There is no directory to make, and asking for one anyway is not a harmless no-op: it is
     * an unchecked crash, thrown by the one class whose contract is that it never ends the run.
     */
    @Test
    void aPathWithNoParentIsStillAJournal() throws Exception {
        Path bare = Path.of("ratchet-journal-with-no-parent.jsonl");
        Files.deleteIfExists(bare);
        try {
            Journal journal = new Journal(bare);

            journal.done("survey", "core", "the subject is on 8", "sha1");

            assertEquals("the subject is on 8", journal.answered("survey", "core").orElse("absent"),
                    "a journal named without a directory records what finished like any other");
        } finally {
            Files.deleteIfExists(bare);
        }
    }

    /**
     * A JOURNAL THAT CANNOT BE WRITTEN SAYS SO, AND THE RUN GOES ON. Losing it costs the resume,
     * which is what every run cost before this file existed; throwing costs the run itself. The
     * line on stderr is the entire mechanism by which anybody finds out that the resume this run is
     * counting on will not be there, so the line is the requirement, not a nicety around it.
     */
    @Test
    void aJournalThatCannotBeWrittenSaysSoAndTheRunGoesOn(@TempDir Path dir) throws Exception {
        Path occupied = dir.resolve("occupied");
        Files.writeString(occupied, "a file sitting where the journal wants a directory\n");
        Journal journal = new Journal(occupied.resolve("journal.jsonl"));

        String said = stderrDuring(
                () -> journal.done("before-pins", "core", "raised two pins", "sha1"));

        assertTrue(said.contains("journal:"),
                "a resume that will not be there has to be said out loud, and it said: " + said);
        assertEquals("raised two pins", journal.answered("before-pins", "core").orElse("absent"),
                "and the run it could not record carries on regardless");
    }

    /**
     * A TORN TAIL THAT CANNOT BE HEALED DOES NOT STOP THE RUN, and it is the more expensive silence
     * of the two: the stump keeps its missing newline, so the next row appends straight onto it and
     * the two are read as one line with two rows' fields in it, which is the merge healing exists
     * to prevent. The rows already in the file are still readable and the run still has a record.
     * What has quietly gone is the promise that a line is a row, for every row written after this
     * one, and the stderr line is the only warning anybody gets.
     */
    @Test
    void aTornTailThatCannotBeHealedDoesNotStopTheRun(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "the file has to be made unwritable for there to be anything to report");
        Files.writeString(file, doneRow("before-pins", "core", "raised two pins", "sha1") + "\n"
                + "{\"at\":\"1\",\"kind\":\"done\",\"node\":\"before-pins\",\"key\":\"w");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        assumeFalse(Files.isWritable(file), "this user writes it regardless, so nothing gets refused");

        try {
            AtomicReference<Journal> reopened = new AtomicReference<>();
            String said = stderrDuring(() -> reopened.set(new Journal(file)));

            assertTrue(said.contains("journal:"),
                    "every row from here on will splice onto that stump, and it said: " + said);
            assertEquals("raised two pins",
                    reopened.get().answered("before-pins", "core").orElse("absent"),
                    "a journal it cannot heal is still a journal it can read");
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    /** A done row exactly as {@link Journal} writes one, so a test can leave one half-written. */
    private static String doneRow(String node, String key, String answer, String sha) {
        return "{\"at\":\"1\",\"kind\":\"done\",\"node\":\"" + node + "\",\"key\":\"" + key
                + "\",\"answer\":\"" + answer + "\",\"sha\":\"" + sha + "\",\"end\":\"row\"}";
    }

    /** The same for a fact row, which is the one whose value cannot be recovered by rerunning. */
    private static String factRow(String name, String value) {
        return "{\"at\":\"1\",\"kind\":\"fact\",\"name\":\"" + name + "\",\"value\":\"" + value
                + "\",\"end\":\"row\"}";
    }

    /** What the process said on stderr while it did that, with stderr put back afterwards. */
    private static String stderrDuring(Runnable work) {
        PrintStream real = System.err;
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
        try {
            work.run();
        } finally {
            System.setErr(real);
        }
        return said.toString(StandardCharsets.UTF_8);
    }
}
