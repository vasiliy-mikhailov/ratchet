package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SWEEP RUNS FOR A FORTNIGHT AND THE HARNESS CHANGES DAILY, so runs get killed and every killed
 * run used to start over. One of them had twenty hours in it.
 *
 * <p>{@link Journal} holds what cannot be derived again and {@code Flow.resumable} hands it back,
 * so the mechanism of continuing was here from the start. The judgement was not: whether this
 * attempt may pick up that record is the question a wrong answer to is expensive, and it used to
 * live in the first consumer where the second one could not have it.
 *
 * <p>These tests are about the rule rather than about the journal, which has its own. A rule with
 * four clauses is worth being able to fail one clause at a time.
 */
class AKilledRunPicksUpWhereItStoppedTest {

    private static final String KEY = "owner/repo|abc123|17|21";

    /** The consumer's word for a run that died mid-sentence rather than reaching a boundary. */
    private static final String IN_FLIGHT = "bumping";

    /** One version, as the row records it and as the rule compares it. */
    // ESCAPED, BECAUSE THE THING IT STANDS FOR IS. Version.fields composes this fragment through
    // Settlement.escape, so a fixture that concatenated raw would be better behaved than production
    // and would hide exactly the defect this file now pins: a value carrying a quote used to be
    // read one way on the row side and another on the running side.
    private static String version(String commit, String image, String prompts, String boms) {
        return "\"commit\":\"" + Settlement.escape(commit)
                + "\",\"image\":\"" + Settlement.escape(image)
                + "\",\"prompts\":\"" + Settlement.escape(prompts)
                + "\",\"boms\":\"" + Settlement.escape(boms) + "\"";
    }

    private static final String VERSION =
            version("ff7a4ab3", "sha256:6f2c1b0a9d3", "54906737", "bb42094f");

    /** A settlement row in the shape the file already holds them, from THIS version. */
    private static void settle(Path settlements, String key, String state) {
        Settlement.note(settlements, key, state, "because", false, false, VERSION);
    }

    @Test
    void theVersionIsWhateverTheReaderSaysItIsAndNothingElse(@TempDir Path dir) throws Exception {
        // THE CONSUMER OWNS THE DIMENSIONS, which is the point of handing the reader in. Before it
        // was handed in, this class inferred the field set from whatever string it was given, so a
        // caller whose running version named fewer fields than its own writer had recorded compared
        // only the ones it happened to mention and resumed across the difference. One function on
        // both sides makes that unconstructable rather than undetectable.
        Path settlements = dir.resolve("settlements.jsonl");
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("before-pins", "core", "core: pinned", "sha1");
        settle(settlements, KEY, Round.PAUSED);

        Resume twoOfThem = Resume.of(settlements, KEY, IN_FLIGHT,
                row -> row.getOrDefault("commit", "") + "|" + row.getOrDefault("prompts", ""));

        assertTrue(twoOfThem.picksUp(journal, "sha1", "ff7a4ab3|54906737"),
                "a reader that names two fields is answered on two fields");
        assertFalse(twoOfThem.picksUp(journal, "sha1", "ff7a4ab4|54906737"),
                "and still refuses when one of the two it names has moved");
        assertTrue(twoOfThem.picksUp(journal, "sha1", "ff7a4ab3|54906737"),
                "the image and the boms are not dimensions of this reader, so they cannot refuse it");
    }

    // THE READER A CONSUMER WOULD HAND IN, and it is the same four names version() writes, which
    // is the point: one function composes both sides, so a field this stops naming changes the
    // string on both and the comparison sees it.
    private static String versionOf(java.util.Map<String, String> row) {
        return version(row.getOrDefault("commit", ""), row.getOrDefault("image", ""),
                row.getOrDefault("prompts", ""), row.getOrDefault("boms", ""));
    }

    private static Resume resume(Path settlements) {
        return Resume.of(settlements, KEY, IN_FLIGHT,
                AKilledRunPicksUpWhereItStoppedTest::versionOf);
    }

    /**
     * A RESUME TAKES FOUR CONDITIONS TO SAY YES AND ANY ONE OF THEM TO SAY NO.
     *
     * <p>Starting fresh has to remain the behaviour when anything is off, because a wrong resume is
     * worse than a slow one: the stages it skips are skipped against edits that are not in this
     * workspace, and the run is then judged on a workspace nobody built.
     */
    @Test
    void aResumeIsRefusedWheneverAnythingIsOff(@TempDir Path dir) throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("before-pins", "core", "core: pinned", "sha1");

        assertFalse(resume(settlements).picksUp(journal, "sha1", VERSION),
                "nothing has settled anything about this run, so nothing says it was interrupted");

        settle(settlements, KEY, IN_FLIGHT);
        assertTrue(resume(settlements).picksUp(journal, "sha1", VERSION));

        assertFalse(resume(settlements).picksUp(journal, "sha2", VERSION),
                "the workspace is not where the journal left it, and replaying onto it is worse "
                        + "than starting over");

        assertFalse(resume(settlements)
                        .picksUp(new Journal(dir.resolve("empty.jsonl"), () -> "sha1"),
                                "sha1", VERSION),
                "nothing completed, so there is nothing to pick up");

        settle(settlements, KEY, "PASS");
        assertFalse(resume(settlements).picksUp(journal, "sha1", VERSION),
                "that run finished; a settled row is not an interrupted one");

        settle(settlements, KEY, "requeued");
        assertFalse(resume(settlements).picksUp(journal, "sha1", VERSION),
                "and a requeue is somebody asking for the work to be done again from the start");

        // A ROW FOR SOMEBODY ELSE IS NOT A ROW FOR THIS RUN. The file is shared by the whole sweep,
        // so the key is checked rather than assumed.
        settle(settlements, "other/repo|def456|17|21", IN_FLIGHT);
        assertFalse(resume(settlements).picksUp(journal, "sha1", VERSION),
                "the last row about THIS run still says it finished");
    }

    /**
     * A ROUND BOUNDARY IS A RESUMABLE STATE AND A REQUEUE IS NOT, WHICH IS WHY THEY ARE TWO WORDS.
     *
     * <p>Both mean the run is unfinished and both send it back to the queue. They mean opposite
     * things about the stored state: a boundary is this attempt stopped mid-sentence, and a requeue
     * is somebody asking for the work to be done again from the start. Resuming one of those would
     * hand that person back exactly what they were trying to discard.
     */
    @Test
    void aRoundBoundaryIsPickedUpAndARequeueIsNot(@TempDir Path dir) throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("before-pins", "core", "core: pinned", "sha1");

        settle(settlements, KEY, Round.PAUSED);
        assertTrue(resume(settlements).picksUp(journal, "sha1", VERSION),
                "the run reached the end of its round between two stages; the workspace and the "
                        + "journal are the ones it left");

        settle(settlements, KEY, "requeued");
        assertFalse(resume(settlements).picksUp(journal, "sha1", VERSION));
    }

    /**
     * ONLY THIS LIBRARY'S OWN WORD IS RESUMABLE WITHOUT BEING NAMED.
     *
     * <p>{@link Round#PAUSED} is written here, so the rule knows it. What a consumer calls a run
     * that died mid-sentence is the consumer's, and a consumer that names nothing gets exactly one
     * resumable state rather than a guess about which of its words meant interrupted.
     */
    @Test
    void aConsumerThatNamesNoInFlightWordStillResumesItsOwnBoundaries(@TempDir Path dir)
            throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("before-pins", "core", "core: pinned", "sha1");
        Resume unnamed = Resume.of(settlements, KEY, "",
                AKilledRunPicksUpWhereItStoppedTest::versionOf);

        settle(settlements, KEY, IN_FLIGHT);
        assertFalse(unnamed.picksUp(journal, "sha1", VERSION),
                "nothing here says that word means interrupted");

        settle(settlements, KEY, Round.PAUSED);
        assertTrue(unnamed.picksUp(journal, "sha1", VERSION));
    }

    /**
     * AND NOT WHEN THE VERSION MOVED UNDER IT, WHICH IS WHAT A ROUND BOUNDARY MADE ROUTINE.
     *
     * <p>The first consumer deploys about once every ten hours against a six-hour budget, so a
     * paused run meeting a different version is the ordinary case rather than the exotic one.
     * Skipping stages a different version paid for would file one version's work under another's
     * name.
     *
     * <p>THE FIELDS ARE COMPARED ONE AT A TIME AND EMPTY IS A VALUE. Every row written before a
     * consumer started recording part of its identity carries an empty one, and reading empty as
     * "no objection" would resume across exactly the change that introduced this check.
     */
    @Test
    void aVersionCarryingAQuoteStillRecognisesItself(@TempDir Path dir) throws Exception {
        // THE ROW COMES BACK UNESCAPED AND THE RUNNING VERSION ARRIVES AS WRITTEN, so for a while
        // these two were compared by different readers: one regex that stopped at the first quote
        // and kept the backslashes, against a map Json.row had already unescaped. A value holding a
        // quote could not match its own recorded self, so the run never resumed, nothing failed and
        // nothing was logged, and the work was quietly done twice. Both sides go through one parser
        // now, and this is the case that tells the difference.
        Path settlements = dir.resolve("settlements.jsonl");
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("before-pins", "core", "core: pinned", "sha1");

        String awkward = version("say \"yes\"", "back\\slash", "54906737", "bb42094f");
        Settlement.note(settlements, KEY, Round.PAUSED, "b", false, false, awkward);

        assertTrue(resume(settlements).picksUp(journal, "sha1", awkward),
                "a version agrees with itself whatever characters it holds");
        assertFalse(resume(settlements).picksUp(journal, "sha1",
                        version("say \"no\"", "back\\slash", "54906737", "bb42094f")),
                "and still refuses one that only looks similar");
    }

    @Test
    void aResumeIsRefusedWhenTheRowWasWrittenByADifferentVersion(@TempDir Path dir)
            throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("before-pins", "core", "core: pinned", "sha1");
        settle(settlements, KEY, Round.PAUSED);

        assertTrue(resume(settlements).picksUp(journal, "sha1", VERSION));

        for (String moved : List.of(
                version("ff7a4ab4", "sha256:6f2c1b0a9d3", "54906737", "bb42094f"),
                version("ff7a4ab3", "sha256:00000000000", "54906737", "bb42094f"),
                version("ff7a4ab3", "sha256:6f2c1b0a9d3", "c3d4e5f6", "bb42094f"),
                version("ff7a4ab3", "sha256:6f2c1b0a9d3", "54906737", "0a0a0a0a"))) {
            assertFalse(resume(settlements).picksUp(journal, "sha1", moved),
                    "one field moved and that is a different version: " + moved);
        }

        // A ROW FROM BEFORE ANY OF THIS EXISTED NAMES NO VERSION, and it is not a match for one.
        Path older = dir.resolve("older.jsonl");
        Settlement.note(older, KEY, Round.PAUSED, "b", false, false, "");
        assertFalse(resume(older).picksUp(journal, "sha1", VERSION),
                "a blank version agrees with nothing except another blank one");

        // AND A HOST WHERE NOTHING CAN BE STAMPED STILL RESUMES, because empty matches empty and
        // the comparison simply loses those dimensions rather than refusing every run. What such a
        // host passes is four empty values rather than nothing at all: with the reader handed in,
        // a caller always composes, so version("","","","") is the shape and "" never occurs.
        // Injecting the reader is what made that distinction visible; it was hidden while this
        // class inferred the shape from whatever string it was given.
        assertTrue(resume(older).picksUp(journal, "sha1", version("", "", "", "")));
    }

    /**
     * A ROUND NUMBER ON THE ROW IS NOT PART OF THE VERSION IT IS COMPARED AGAINST.
     *
     * <p>The same writer puts both on the same row, and a consumer appends the round last because a
     * shell launcher greps this file and bash cannot be corrected while it runs. If the rule
     * compared everything on the row that looked like a field, every round would read as a new
     * version and nothing would ever resume. It compares the fields the RUNNING side names, which
     * is the side that knows what it puts its name to.
     */
    @Test
    void aRoundAppendedToTheRowIsNotReadAsAVersionThatMoved(@TempDir Path dir) throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        Journal journal = new Journal(dir.resolve("journal.jsonl"), () -> "sha1");
        journal.done("before-pins", "core", "core: pinned", "sha1");
        Settlement.note(settlements, KEY, Round.PAUSED, "b", false, false,
                VERSION + ",\"round\":\"3\"");

        assertTrue(resume(settlements).picksUp(journal, "sha1", VERSION),
                "the round moved and the version did not, so the next round picks this up");
    }
}
