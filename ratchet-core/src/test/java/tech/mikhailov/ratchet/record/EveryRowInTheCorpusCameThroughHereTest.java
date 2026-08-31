package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY ROW IN EVERY CORPUS THIS PROJECT HAS WAS WRITTEN BY THIS CLASS, and no row can be written
 * a second time. By the time anyone reads one the run is over, the harness has changed underneath
 * it, and the model that produced the answer has been retired. A field that is quietly wrong here
 * is quietly wrong for good.
 *
 * <p>MEASURED: a mutation run over the record made 53 changes to this class that the existing
 * tests executed and did not notice -- a rank that no longer ranks, a comma in front of the first
 * field, a cause chain dropped off an exception, a fingerprint that stops being written after the
 * first row, a summariser that returns its lines newest-first. Every one of those produces a file
 * that still parses and still looks like a trace.
 *
 * <p>THREE REQUIREMENTS TURNED UP HERE THAT WERE WRITTEN IN COMMENTS AND NOWHERE ELSE, and the
 * tests that hold them say so: the pipeline is asked its name ONCE per lane and never again; a
 * supplier that returns null is a different failure from a supplier that throws, and only the
 * second was guarded; and the reader has to survive rows it did not write, because this file is
 * appended to by a process that gets killed and read back by a harness that has changed since.
 */
class EveryRowInTheCorpusCameThroughHereTest {

    private static final String KEY = "owner/repo|abc123|17|21";

    /** The two lists a pipeline hands the record, which is where its own vocabulary lives. */
    private static final List<String> DECISIVE = List.of("fail_");

    private static final List<String> DISPUTED = List.of("gaming");

    // --- what is written, which is the half that cannot be recovered ---

    @Test
    void everyRowSaysWhenItHappenedWhichRunItBelongsToAndWhatKindItIs(@TempDir Path dir) {
        Path file = dir.resolve("trace.jsonl");

        new JsonlTrace(file, dir.resolve("settlements.jsonl"), "k").applied("gate", "raised two pins");

        String row = rows(file).get(0);
        // Pinned as bytes rather than through the reader: the reader is lenient by design, so it
        // accepts a row with a comma before the first field and a row with no commas at all, and
        // the consumers that grep this file are not so forgiving.
        assertEquals("{\"at\":\"CLOCK\",\"bump\":\"k\",\"kind\":\"applied\",\"stage\":\"gate\","
                        + "\"what\":\"raised two pins\"}", masked(row),
                "exactly one comma between fields and none before the first: " + row);
        assertTrue(Long.parseLong(Json.row(row).get("at")) > 1_700_000_000_000L,
                "a real clock: how long a thing took is half of what anyone reads a trace for");
    }

    @Test
    void theKindsTheRunIsNeverShownAreStillWrittenDown(@TempDir Path dir) {
        Path file = dir.resolve("trace.jsonl");
        Trace trace = trace(dir);

        trace.thought("doer", "length", "the tool returned nothing, so try the other one",
                "I will retry");
        trace.priced(KEY, "45", "3 files read, 2 reviews, 1 rewrite");
        trace.exchanged(new Trace.Exchange("chat", "step-doer", 3, "the prompt", "the answer",
                "read_file", "stop", 1200, 340, 4500, ""));

        List<String> written = rows(file);
        assertEquals(3, written.size(), "three events, three rows: " + written);
        assertEquals("the tool returned nothing, so try the other one",
                Json.row(written.get(0)).get("thinking"),
                "thinking is paid for once; unrecorded it is paid for and discarded");
        assertEquals("45", Json.row(written.get(1)).get("minutes"),
                "what the same work would have cost a person is a number nothing else records");
        assertEquals("1200", Json.row(written.get(2)).get("in"),
                "the server's own token count, which no curated event carries");

        // THE KIND IS THE COLUMN THE CORPUS GROUPS BY, and it is not this library's to choose, for
        // the reason the comment on `bump` gives: it is already written into every row of every
        // corpus this project has, and a query cannot be corrected retrospectively across files
        // nothing will rewrite. Eight of the nine methods write their own name; `exchanged` writes
        // "exchange", and that inconsistency is now a fact about the archive rather than a
        // preference. Renaming it would silently halve the count of every query that groups by it.
        assertEquals("thought", Json.row(written.get(0)).get("kind"));
        assertEquals("priced", Json.row(written.get(1)).get("kind"));
        assertEquals("exchange", Json.row(written.get(2)).get("kind"),
                "the one method whose kind is not its own name; the corpus already says "
                        + "\"exchange\", so the name is now owed to the rows rather than to taste");
        assertEquals("", trace.happened("", "", 80),
                "and the run is shown none of it: its own reasoning back in its own prompt is the "
                        + "conversation inside itself");
    }

    @Test
    void aKindWithNoSentenceForItCostsNoLineAtAll(@TempDir Path dir) {
        Trace trace = trace(dir);

        trace.progress(KEY, "step one landed");
        trace.thought("doer", "stop", "a page of reasoning nobody is shown", "the answer");
        trace.progress(KEY, "step two landed");

        String log = trace.happened("", "", 80);

        assertEquals("* step one landed\n* step two landed", log,
                "a kind the summariser has no sentence for is skipped, not rendered as an empty "
                        + "line: the caller counts these lines and pays for each of them");
        assertEquals(2, log.lines().count(), "two events with something to say, two lines");
    }

    /**
     * A DEFECT FOUND WHILE WRITING THIS, WRITTEN DOWN RATHER THAN PINNED, AND SINCE FIXED.
     *
     * <p>What stood here: the summary line asked the row for {@code "infra":true} while every value
     * this class writes is quoted, so the row said {@code "infra":"true"} and the test never
     * matched. "did not run" was unreachable — to an agent reading its own record, an outage of
     * ours looked exactly like a check that ran and failed. The row on disk was right, which is why
     * the corpus could still tell them apart and why the assertions below are on the row.
     *
     * <p>Refusing to assert the broken behaviour was the correct call and it is worth naming why:
     * a test written to match the code would have PINNED the defect, and the fix would then have
     * had to break a passing test to land. That is not hypothetical here — a generated test in
     * ratchet-llm did exactly that a day later, asserting a stall was reported in minutes because
     * the code divided by 60,000, and it had to be rewritten before a two-minute bound could stop
     * announcing itself as zero.
     *
     * <p>The summary reads the field now instead of grepping for it, so the line is asserted below
     * as well. {@code ABuildThatNeverRanIsNotABuildThatFailedTest} is where the fix itself lives.
     */
    @Test
    void theWrittenRowForABuildThatCouldNotRunCarriesInfraTrueAndPassedFalse(@TempDir Path dir) {
        Path file = dir.resolve("trace.jsonl");
        Trace trace = trace(dir);

        trace.built("gate", new Trace.Outcome(true, false, "docker daemon not reachable"));
        trace.built("gate", new Trace.Outcome(false, true, "14 tests, all green"));

        List<String> written = rows(file);
        Map<String, String> outage = Json.row(written.get(0));
        assertEquals("true", outage.get("infra"),
                "an outage of ours is not a verdict on the work, and a corpus that cannot tell "
                        + "them apart scores our own downtime against the model");
        assertEquals("false", outage.get("passed"), "it did not pass, and it also did not fail");
        assertEquals("docker daemon not reachable", outage.get("summary"),
                "the summary is the only thing that says which of the two it was");
        Map<String, String> checked = Json.row(written.get(1));
        assertEquals("false", checked.get("infra"));
        assertEquals("true", checked.get("passed"), "and a check that ran has a verdict");

        List<String> summary = trace.happened("", "", 80).lines().toList();
        assertEquals("[build gate] did not run", summary.get(0),
                "THE HALF THIS TEST USED TO WITHHOLD. A missing toolchain and a failing suite call "
                        + "for opposite responses — fix the environment, or fix the code — and the "
                        + "summary is what a person reads to choose: " + summary);
        assertEquals("[build gate] ran", summary.get(1),
                "a check that did run says so to the run reading itself back");
    }

    @Test
    void aRunThatDiedSaysSoWhereTheLauncherIsLooking(@TempDir Path dir) {
        Trace trace = trace(dir);

        trace.failed("doer", KEY, new IllegalStateException("agent loop died",
                new java.net.SocketTimeoutException("timed out reading the response")));

        Map<String, String> row = Json.row(rows(dir.resolve("trace.jsonl")).get(0));
        assertEquals("IllegalStateException: agent loop died", row.get("cause"),
                "the type and the message, because a bare message does not say what threw it");
        assertTrue(row.get("stack").contains("timed out reading the response"),
                "the wrapper's message names nothing anyone can act on; the cause underneath is "
                        + "the whole diagnosis: " + row.get("stack"));
        assertTrue(row.get("stack").contains("at tech.mikhailov"), "and where it happened");

        String settled = rows(dir.resolve("settlements.jsonl")).get(0);
        assertTrue(settled.contains("\"state\":\"infra\""),
                "the launcher reads only this file, so a dead run that says nothing here is "
                        + "indistinguishable from one still going: " + settled);
        assertTrue(settled.contains("agent loop died"), settled);
    }

    @Test
    void anExceptionWithNothingUnderItDoesNotInventACause(@TempDir Path dir) {
        Trace trace = trace(dir);

        trace.failed(null, KEY, new IllegalStateException("the workspace vanished"));

        String stack = Json.row(rows(dir.resolve("trace.jsonl")).get(0)).get("stack");
        assertFalse(stack.contains("caused by"),
                "a cause that does not exist must not be recorded as `caused by null`, which is "
                        + "the one line a reader would go and look for: " + stack);
    }

    @Test
    void aSettlementLandsInBothFilesBecauseTheyAnswerDifferentQuestions(@TempDir Path dir) {
        Trace trace = trace(dir);

        trace.settled(KEY, "PASS", "14 tests conserved, effective target 21", true, true, true);

        Map<String, String> row = Json.row(rows(dir.resolve("trace.jsonl")).get(0));
        assertEquals("settled", row.get("kind"),
                "settlements.jsonl says what happened; only the trace can line the verdict up "
                        + "with the events that produced it, and that is what tuning replays");
        assertEquals("PASS", row.get("state"));
        assertEquals("14 tests conserved, effective target 21", row.get("because"));
        assertEquals("true", row.get("baseline"));
        assertEquals("true", row.get("gate"));
        assertEquals("true", row.get("resumed"), "a resumed run is a different trial in both files");
        assertEquals(1, rows(dir.resolve("settlements.jsonl")).size(), "and the last word is there too");
    }

    @Test
    void anEmptyAnswerIsRecordedAsEmptyRatherThanEndingTheRun(@TempDir Path dir) {
        Path file = dir.resolve("trace.jsonl");
        Trace trace = trace(dir);

        assertDoesNotThrow(() -> trace.asked("step-verifier", "the prompt", null),
                "an empty model answer is a live failure mode on a small local model, and it is a "
                        + "judgement about the model rather than a fault in the recorder");

        Map<String, String> row = Json.row(rows(file).get(0));
        assertEquals("the prompt", row.get("prompt"), "the half that did arrive is still kept");
        assertEquals("", row.get("reply"), "and the silence is recorded as silence");
    }

    @Test
    void theRecordMakesItsOwnPlaceToLive(@TempDir Path dir) {
        Path deep = dir.resolve("results/owner-repo/17/trace.jsonl");

        new JsonlTrace(deep, dir.resolve("results/owner-repo/17/settlements.jsonl"), KEY)
                .applied("gate", "raised two pins");

        assertTrue(Files.exists(deep),
                "the results tree does not exist until the first row is written, and a first row "
                        + "that has to be dropped is the row saying the run started");
    }

    /**
     * THE ONLY WAY TO HEAR THIS REQUIREMENT IS TO STAND WHERE THE COMPLAINT GOES. There is no seam
     * between {@code write} and {@code System.err}, so the test swaps the stream and puts it back.
     * That is process-wide state: the pom configures no parallelism today, and this test needs
     * {@code @Isolated} on the day it does.
     */
    @Test
    void aTraceThatCannotBeWrittenSaysSoAndTheRunGoesOn(@TempDir Path dir) throws Exception {
        Path occupied = dir.resolve("occupied");
        Files.writeString(occupied, "a regular file standing where a directory would have to be");
        Path impossible = occupied.resolve("trace.jsonl");
        PrintStream real = System.err;
        ByteArrayOutputStream complaint = new ByteArrayOutputStream();
        System.setErr(new PrintStream(complaint, true, StandardCharsets.UTF_8));
        try {
            assertDoesNotThrow(() -> new JsonlTrace(impossible, dir.resolve("s.jsonl"), KEY)
                            .applied("gate", "raised two pins"),
                    "a run that is otherwise fine must not end because its record could not be written");
        } finally {
            System.setErr(real);
        }

        assertTrue(complaint.toString(StandardCharsets.UTF_8).contains("trace:"),
                "but it has to say so out loud: a silently absent trace is worse than a loud one, "
                        + "because the corpus reads an empty file as a run that did nothing");
        assertFalse(Files.exists(impossible));
    }

    @Test
    void theShortenedDuplicateOfAToolCallIsNotWritten(@TempDir Path dir) {
        Path file = dir.resolve("trace.jsonl");
        JsonlTrace trace = trace(dir);

        trace.onToolInvocation("agent:doer", "read_file", "memory-1", "{\"path\":\"pom", "<proj");

        assertFalse(Files.exists(file),
                "the executor already recorded this call in full; the watcher's copy is truncated, "
                        + "so writing it would double the file and lose the argument that matters");
    }

    // --- the fingerprint, which is what tells one fortnight's harness from the next ---

    @Test
    void thePipelineIsAskedItsNameOnceHoweverManyRowsCarryIt(@TempDir Path dir) {
        Path settlements = dir.resolve("settlements.jsonl");
        AtomicInteger asked = new AtomicInteger();
        Trace trace = new JsonlTrace(dir.resolve("trace.jsonl"), settlements, KEY,
                () -> {
                    asked.incrementAndGet();
                    return "\"commit\":\"dd07585e\"";
                }, List.of(), List.of());

        trace.progress(KEY, "step one landed");
        trace.progress(KEY, "step two landed");
        trace.settled(KEY, "PASS", "14 tests conserved", true, true);

        // WRITTEN IN A COMMENT AND NOWHERE ELSE UNTIL NOW: a fingerprint typically builds every
        // agent in the run and hashes what each is handed. A lane keeps the code it started with,
        // so the answer cannot change, and paying for it on every progress note is paying again
        // for an answer already known.
        assertEquals(1, asked.get(), "the pipeline is asked its name once per lane, not per row");
        List<String> written = rows(settlements);
        assertEquals(3, written.size());
        assertTrue(written.stream().allMatch(r -> r.contains("\"commit\":\"dd07585e\"")),
                "and holding the answer must not cost the later rows their fingerprint, which is "
                        + "the field that tells this fortnight's harness from the next: " + written);
    }

    @Test
    void aPipelineThatCannotNameItselfStillSettles(@TempDir Path dir) {
        Path threw = dir.resolve("threw/settlements.jsonl");
        Trace throwing = new JsonlTrace(dir.resolve("threw/trace.jsonl"), threw, KEY,
                () -> {
                    throw new IllegalStateException("the image is not built yet");
                }, List.of(), List.of());

        assertDoesNotThrow(() -> throwing.settled(KEY, "PASS", "14 tests conserved", true, true),
                "a row recorded without its version is worse than one recorded with it, and a row "
                        + "not recorded at all is worse than both");
        assertTrue(rows(threw).get(0).endsWith("\"resumed\":false}"),
                "the row lands, simply without a fingerprint: " + rows(threw).get(0));

        // THE SECOND HALF, WHICH THE COMMENT DOES NOT CLAIM AND THE CATCH CANNOT SEE. A supplier
        // that answers null has not thrown anything, so nothing above catches it; the null travels
        // to the settlement writer and takes down the one call this class exists to make.
        Path silent = dir.resolve("silent/settlements.jsonl");
        Trace nameless = new JsonlTrace(dir.resolve("silent/trace.jsonl"), silent, KEY,
                () -> null, List.of(), List.of());

        assertDoesNotThrow(() -> nameless.settled(KEY, "FAIL", "the gate never went green", false, false),
                "answering null is not the same failure as throwing, and only throwing was guarded");
        assertEquals(1, rows(silent).size(), "the settlement is written either way");
    }

    // --- what is read back, for the agents living inside the run ---

    @Test
    void whenOnlyOneLineFitsItIsTheVerdictAndNotTheNewestThing(@TempDir Path dir) {
        Trace trace = trace(dir);
        ladder(trace);

        assertEquals(VERDICT, trace.happened("", "", 1),
                "measured over five real calls: 13 of 349 returned lines carried a decision and "
                        + "the rest was an hour-old reader reading build files");
        assertEquals(VERDICT + "\n" + OBJECTION + "\n" + NOTE, trace.happened("", "", 3),
                "a verdict, then an objection, then where the loop got to -- in that order of "
                        + "worth, whatever order they arrived in");
    }

    @Test
    void theRoomLeftOverGoesToTheWorkAndNotToTheFileReads(@TempDir Path dir) {
        Trace trace = trace(dir);
        ladder(trace);

        List<String> four = trace.happened("", "", 4).lines().toList();
        assertEquals(WORK, four.get(3),
                "the fourth line is the newest thing that was actually done: " + four);
        String five = trace.happened("", "", 5);
        assertTrue(five.contains(ANSWER), "the fifth is the older one: " + five);
        assertFalse(five.contains("read_file"),
                "a file read is the bulk of any trace and almost never the point; unranked, it is "
                        + "what the whole budget goes on: " + five);
    }

    @Test
    void everythingThatFitsComesBackInTheOrderItHappened(@TempDir Path dir) {
        Trace trace = trace(dir);
        ladder(trace);

        String log = trace.happened("", "", 80);

        assertEquals(String.join("\n", VERDICT, OBJECTION, NOTE, ANSWER, WORK, READ), log,
                "ranked to choose what survives the budget, then put back into order: read "
                        + "newest-first, a log shows an agent the effects before the causes");
        assertFalse(log.contains("\u0000"),
                "and the ranking marks are scaffolding for the sort, never something an agent is "
                        + "shown -- a control character in a prompt is a payload, not a line");
    }

    @Test
    void aSettlementOutranksEverythingWithNoVocabularyNeededToRecogniseIt(@TempDir Path dir) {
        // Handed null for both word lists, which is what a pipeline with no vocabulary of its own
        // supplies. The kinds still rank, that fallback being structural and true of any pipeline,
        // and a NullPointerException raised while recording is the one failure this library will
        // not have.
        Trace trace = new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settlements.jsonl"),
                KEY, () -> "", null, null);
        trace.settled(KEY, "PASS", "14 tests conserved, effective target 21", true, true);
        for (int i = 1; i <= 4; i++) {
            trace.progress(KEY, "step " + i + " landed");
        }

        assertEquals("SETTLED PASS: 14 tests conserved, effective target 21",
                trace.happened("", "", 1),
                "four newer notes do not push out the one line that says how it ended");
    }

    @Test
    void anAgentAskingWhatItSaidIsNotHandedWhatSomebodyElseSaid(@TempDir Path dir) {
        Trace trace = trace(dir);

        trace.asked("step-doer", "the prompt", "added a Kaptcha bean");
        trace.asked("step-verifier", "the prompt", "gaming - the stub deletes image rendering");

        assertEquals("[step-doer] answered: added a Kaptcha bean",
                trace.happened("", "step-doer", 80),
                "a named agent means that agent: a critic handed the doer's answers as though they "
                        + "were its own has been given a record of a conversation it never had");
    }

    @Test
    void aReplyWithNewlinesTabsAndQuotesComesBackAsExactlyOneLine(@TempDir Path dir) {
        Trace trace = trace(dir);

        trace.asked("step-verifier", "the prompt",
                "again:\nthe stub says \"conserved\"\tand deletes image rendering");

        String log = trace.happened("", "", 80);
        assertEquals("[step-verifier] answered: again: the stub says \"conserved\" and deletes "
                        + "image rendering", log,
                "one line per event is what the caller budgets by, so the newlines and tabs inside "
                        + "a reply flatten to spaces rather than becoming letters or vanishing");
        assertEquals(1, log.lines().count(), "and the quotation marks the reply was written with "
                + "do not cut it short where they were escaped");
    }

    @Test
    void aLineIsOnlyCutWhenCuttingItSavesSomething(@TempDir Path dir) {
        Trace trace = trace(dir);

        trace.progress(KEY, "x".repeat(180));
        trace.progress(KEY, "y".repeat(181));
        trace.progress(KEY, "z".repeat(400));

        List<String> log = trace.happened("", "", 80).lines().toList();
        assertEquals("* " + "x".repeat(180), log.get(0),
                "the mark means something was lost, so putting it on a line that lost nothing is "
                        + "a lie a reader cannot check");
        // ONE CHARACTER OVER IS NOT CUT EITHER, AND THAT IS NEW. The marker costs 33 characters, so
        // cutting a 181-character line at 180 returns 213 — larger for having been cut, and missing
        // its end. The summary would have paid more to say what it withheld than keeping it cost.
        // Found by running a consumer against a live model; no fixture here had a line inside the
        // marker's own width, so nothing in this suite could see it.
        assertEquals("* " + "y".repeat(181), log.get(1),
                "cutting this loses a character and adds thirty-two, so it is not a cut");
        assertEquals("* " + "z".repeat(180) + " ... (truncated, total 400 chars)", log.get(2),
                "and a line far enough over IS cut, and says how much there was, because a marker "
                        + "that only says it was cut cannot be told apart in a corpus from the "
                        + "other three bounds in this library, all of which report a total");
    }

    // --- rows this run did not write, which is most of them ---

    @Test
    void anotherRunsRowsInTheSameFileAreNotThisRunsRecord(@TempDir Path dir) {
        Path file = dir.resolve("trace.jsonl");
        Path settlements = dir.resolve("settlements.jsonl");
        Trace mine = new JsonlTrace(file, settlements, KEY, () -> "", List.of(), List.of());
        Trace theirs = new JsonlTrace(file, settlements, "other/project|def456|17|21",
                () -> "", List.of(), List.of());

        mine.applied("gate", "raised two pins");
        theirs.applied("gate", "reverted the rewrite");

        // The match is a substring test over the whole row rather than a parse of the key field,
        // so a run whose key is a prefix of another's would still read the other's rows. That is
        // not asserted here because it is a defect, not a requirement; it is written down as one.
        assertEquals("[gate] raised two pins", mine.happened("", "", 80),
                "an agent shown another run's work will re-do it or argue with it");
        assertEquals("[gate] reverted the rewrite", theirs.happened("", "", 80));
    }

    @Test
    void aKeyWithAQuoteInItStillFindsItsOwnRows(@TempDir Path dir) {
        String quoted = "owner/repo|\"HEAD\"|17|21";
        Trace trace = new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settlements.jsonl"),
                quoted, () -> "", List.of(), List.of());

        trace.applied("gate", "raised two pins");

        assertEquals("[gate] raised two pins", trace.happened("", "", 80),
                "the key is written through the escaper like every other value, so it has to be "
                        + "looked for escaped or the run cannot find a single row of its own");
    }

    @Test
    void aRowCutInHalfSaysWhatItCanAndCostsNothingWrittenBeforeIt(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("trace.jsonl");
        Trace trace = trace(dir);
        trace.progress(KEY, "step one landed");
        // What a kill in the middle of a write leaves behind: a value with no closing quote, and
        // -- the worse one -- a value cut between a backslash and the character it was escaping.
        Files.writeString(file, row("progress", "\"note\":\"step two half-w") + "\n"
                        + row("progress", "\"note\":\"step three \\"),
                StandardOpenOption.APPEND);

        // A sweep runs for a fortnight and lanes are killed daily, so a torn tail is the normal
        // case rather than a fault. A reader that walks off the end of one throws, and an agent
        // that asks what happened and gets an exception loses its turn.
        String log = trace.happened("", "", 80);

        assertEquals("* step one landed\n* step two half-w\n* step three \\", log,
                "half a row still says what it can, and costs nothing written before it");
    }

    @Test
    void aFieldTheRowDoesNotCarryIsAbsentRatherThanTheOneNextToIt(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("trace.jsonl");
        Trace trace = trace(dir);
        // The shape an older harness wrote: an answer with no `reply` field on it at all. The file
        // outlives the format, so the reader meets rows whose fields it does not have.
        Files.writeString(file, row("asked", "\"agent\":\"step-verifier\"}") + "\n",
                StandardOpenOption.CREATE);

        assertEquals("[step-verifier] answered:", trace.happened("", "", 80),
                "a field that is not there reads as nothing; read as the bytes that happen to sit "
                        + "at that offset, the summary quietly reports the clock as the answer");
    }

    // --- the shape of a real trace: a verdict, an objection, a note, two pieces of work, a read ---

    private static final String VERDICT = "[gate] turn 1: FAIL_test_conservation (pre=7 lost=4)";

    private static final String OBJECTION =
            "[step-verifier] answered: gaming - the stub deletes image rendering";

    private static final String NOTE = "* handing the step back to the loop";

    private static final String ANSWER = "[step-doer] answered: added a Kaptcha bean";

    private static final String WORK = "[migrate] rewrote the parent block to 2.7.3";

    private static final String READ = "[step-doer] read_file({\"path\":\"pom.xml\"}) -> <project/>";

    private static void ladder(Trace trace) {
        trace.applied("gate", "turn 1: FAIL_test_conservation (pre=7 lost=4)");
        trace.asked("step-verifier", "the prompt", "gaming - the stub deletes image rendering");
        trace.progress(KEY, "handing the step back to the loop");
        trace.asked("step-doer", "the prompt", "added a Kaptcha bean");
        trace.applied("migrate", "rewrote the parent block to 2.7.3");
        trace.tool("step-doer", "read_file", "{\"path\":\"pom.xml\"}", "<project/>");
    }

    private static JsonlTrace trace(Path dir) {
        return new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settlements.jsonl"), KEY,
                () -> "", DECISIVE, DISPUTED);
    }

    /** A row as some other writer left it, so that a test can hand the reader what it will meet. */
    private static String row(String kind, String tail) {
        return "{\"at\":\"1755000000000\",\"bump\":\"" + KEY + "\",\"kind\":\"" + kind + "\","
                + tail;
    }

    private static String masked(String row) {
        return row.replaceFirst("\"at\":\"\\d+\"", "\"at\":\"CLOCK\"");
    }

    private static List<String> rows(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
