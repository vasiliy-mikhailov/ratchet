package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;

import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Spilling;
import tech.mikhailov.ratchet.llm.Tool;
import tech.mikhailov.ratchet.tools.Jobs;
import tech.mikhailov.ratchet.tools.Kit;
import tech.mikhailov.ratchet.tools.Search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TOOLS, EXERCISED AS A CONSUMER MEETS THEM: through {@link Kit}, from another package, by
 * name, with arguments written the way a model writes them.
 *
 * <p>Every assertion here is about what a MODEL is handed back, because that string is the whole of
 * what it has to act on. A tool that throws where it should answer takes the agent's turn with it.
 */
class TheToolsAnAgentActuallyReachesForTest {

    @Test
    void theSetIsTheUnionOfTwoCorporaRatherThanTheFirstOneMeasured(@TempDir Path dir) {
        assertEquals(
                java.util.List.of("read", "write", "edit", "list_dir", "grep", "glob", "bash",
                        "job_output", "job_list", "job_kill", "todo_write"),
                Kit.at(dir).tools().keySet().stream().map(Tool::name).toList(),
                "grep and glob are here because a second corpus put them at 15.9% and 8.7% of "
                        + "11,328 calls, in 24 of 24 lanes, against TOOLS.md's three calls in six "
                        + "runs. Both measurements are real; the union is what ships.");
    }

    /**
     * THE CALLER WHO REFUSES A SHELL IS THE ONE WHO MOST NEEDS SEARCH, which is what made leaving
     * grep out on the grounds that "bash can do it" the wrong call rather than merely a close one.
     */
    @Test
    void aKitWithoutAShellStillFindsThingsAndOffersNoWayToStartAProcess(@TempDir Path dir) {
        java.util.List<String> offered = Kit.withoutShell(dir).tools().keySet().stream()
                .map(Tool::name).toList();

        assertEquals(java.util.List.of("read", "write", "edit", "list_dir", "grep", "glob",
                "todo_write"), offered);
        assertFalse(offered.contains("bash"), "nothing here starts a process");
        assertFalse(offered.stream().anyMatch(name -> name.startsWith("job_")),
                "and the job tools go with it, since there is nothing to have started");
    }

    @Test
    void grepSaysWhichFileAndWhichLineAndHowManyItFoundAltogether(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/A.java"), "class A {\n  Missing thing;\n}\n");
        Files.writeString(dir.resolve("src/main/B.java"), "class B {\n  Missing other;\n}\n");
        Files.writeString(dir.resolve("notes.txt"), "nothing to see\n");
        Map<Tool, Calling> tools = Kit.withoutShell(dir).tools();

        String found = call(tools, "grep", "{\"pattern\":\"Missing\"}");

        assertTrue(found.contains("src/main/A.java"), found);
        assertTrue(found.contains("src/main/B.java"), found);
        assertTrue(found.contains("Line 2: "), "the line number is the coordinate: " + found);
        assertTrue(found.contains("2 matches"), "and the count is there: " + tail(found));

        String narrowed = call(tools, "grep",
                "{\"pattern\":\"Missing\",\"include\":\"A.java\"}");
        assertTrue(narrowed.contains("A.java"));
        assertFalse(narrowed.contains("B.java"), "include narrows what is searched: " + narrowed);

        String none = call(tools, "grep", "{\"pattern\":\"NotAnywhere\"}");
        assertTrue(none.toLowerCase().contains("no matches"), none);
    }

    /**
     * A PAGE OF 250 OUT OF 1,842 IS SAFE TO REASON FROM; A PAGE OF 250 CALLED "250 MATCHES" IS NOT
     * — a model told the smaller number believes it has seen the set and reasons about the absence
     * of everything else.
     */
    @Test
    void theCountIsOfEveryMatchAndNotOfWhatFitOnThePage(@TempDir Path dir) throws Exception {
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            many.append("hit ").append(i).append('\n');
        }
        Files.writeString(dir.resolve("many.txt"), many.toString());

        String found = call(Kit.withoutShell(dir).tools(), "grep", "{\"pattern\":\"hit\"}");

        assertTrue(found.contains("250 of 300 matches shown"), tail(found));
        assertFalse(found.contains("hit 300"), "the page really did stop");
    }

    @Test
    void globMatchesAPathAtAnyDepthAndAnswersNewestFirst(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test"));
        Path old = dir.resolve("src/test/OldTest.java");
        Path recent = dir.resolve("src/test/NewTest.java");
        Files.writeString(old, "old");
        Files.writeString(recent, "new");
        Files.writeString(dir.resolve("src/test/notes.md"), "not java");
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000_000_000_000L));
        Files.setLastModifiedTime(recent, FileTime.fromMillis(1_700_000_000_000L));
        Map<Tool, Calling> tools = Kit.withoutShell(dir).tools();

        String anyDepth = call(tools, "glob", "{\"pattern\":\"*Test.java\"}");
        assertTrue(anyDepth.contains("src/test/NewTest.java"),
                "a pattern with no slash matches a name at any depth, as gitignore does: "
                        + anyDepth);
        assertFalse(anyDepth.contains("notes.md"));
        assertTrue(anyDepth.indexOf("NewTest") < anyDepth.indexOf("OldTest"),
                "newest first, because the cap takes the head: " + anyDepth);

        assertTrue(call(tools, "glob", "{\"pattern\":\"**/*.md\"}").contains("notes.md"),
                "and ** crosses directories");
    }

    /**
     * A REGEX A MODEL WROTE CAN STILL RUN FOREVER, and java.util.regex cannot be interrupted.
     *
     * <p>THE PATTERN HERE IS CHOSEN FROM A MEASUREMENT, not from the textbook. The textbook
     * examples no longer bite: on the JDK this builds against, {@code (x+x+)+y} over sixty
     * characters answers in a millisecond, and so do {@code ^(a+)+$}, {@code ([a-zA-Z]+)*$} and
     * {@code ^(a|a)*$} — the engine memoises them away. A BACKREFERENCE cannot be memoised, and
     * {@code (a+)+\1b} over thirty characters was measured at 50.6 SECONDS; this file is longer
     * than that, so an unguarded run of this test would not end during anyone's working day.
     *
     * <p>And a backreference is exactly what ripgrep REFUSES and Java ACCEPTS, so the one place the
     * wider dialect is a hazard is the one place the engine cannot protect itself.
     */
    @Test
    void aBacktrackingPatternEndsTheCallRatherThanTheRun(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("wide.txt"), "a".repeat(32) + "\n");
        Map<Tool, Calling> tools = new Search(dir, Duration.ofMillis(300)).tools();

        long started = System.nanoTime();
        String answered = call(tools, "grep", "{\"pattern\":\"(a+)+\\\\1b\"}");
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(tookMillis < 20_000, "it came back at all, in " + tookMillis + "ms");
        assertTrue(answered.contains("backtracks"),
                "and it says what was wrong with the pattern: " + answered);
        assertTrue(answered.contains("wide.txt"), "and where: " + answered);
    }

    @Test
    void readComesBackNumberedAndSaysWhatItShowedOfWhatThereIs(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("big.txt"), lines(1, 5_000));
        Map<Tool, Calling> tools = Kit.at(dir).tools();

        String head = call(tools, "read", "{\"file_path\":\"big.txt\"}");
        assertTrue(head.contains("line 1"), "the first line is there");
        assertFalse(head.contains("line 2001"), "and it stopped at the two-thousand cap");
        assertTrue(head.contains("5000"), "the footer says how many lines the file has: " + tail(head));

        String middle = call(tools, "read", "{\"file_path\":\"big.txt\",\"offset\":4990,\"limit\":5}");
        assertTrue(middle.contains("line 4990"), "offset is 1-based");
        assertTrue(middle.contains("line 4994"));
        assertFalse(middle.contains("line 4995"), "and limit is honoured");
    }

    @Test
    void writeCreatesAndReplacesAndMakesItsParents(@TempDir Path dir) throws Exception {
        Map<Tool, Calling> tools = Kit.at(dir).tools();

        call(tools, "write", "{\"file_path\":\"deep/inside/new.txt\",\"content\":\"first\"}");
        assertEquals("first", Files.readString(dir.resolve("deep/inside/new.txt")));

        call(tools, "write", "{\"file_path\":\"deep/inside/new.txt\",\"content\":\"second\"}");
        assertEquals("second", Files.readString(dir.resolve("deep/inside/new.txt")),
                "write replaces rather than merging, which is why edit exists");
    }

    /**
     * THE UNIQUE-MATCH RULE IS WHY THIS IS SAFER THAN A SHELL SED, and this project learned that
     * the expensive way: a careless regex ate two whole class bodies in one session.
     */
    @Test
    void editRequiresAUniqueMatchAndSaysHowManyItFound(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("f.java"), "int a = 1;\nint b = 1;\nint c = 2;\n");
        Map<Tool, Calling> tools = Kit.at(dir).tools();

        String refused = call(tools, "edit",
                "{\"file_path\":\"f.java\",\"old_string\":\"= 1\",\"new_string\":\"= 9\"}");
        assertTrue(refused.contains("2"), "it says how many it found: " + refused);
        assertEquals("int a = 1;\nint b = 1;\nint c = 2;\n", Files.readString(dir.resolve("f.java")),
                "and changed nothing, which is the point");

        call(tools, "edit",
                "{\"file_path\":\"f.java\",\"old_string\":\"int c = 2;\",\"new_string\":\"int c = 3;\"}");
        assertTrue(Files.readString(dir.resolve("f.java")).contains("int c = 3;"),
                "a unique match lands");

        call(tools, "edit",
                "{\"file_path\":\"f.java\",\"old_string\":\"= 1\",\"new_string\":\"= 9\",\"replace_all\":true}");
        assertFalse(Files.readString(dir.resolve("f.java")).contains("= 1;"),
                "and replace_all is how you mean every occurrence");
    }

    @Test
    void aPathOutsideTheRootIsAnsweredRatherThanObeyed(@TempDir Path dir) {
        Map<Tool, Calling> tools = Kit.at(dir).tools();

        String refused = call(tools, "read", "{\"file_path\":\"../../../etc/passwd\"}");

        assertFalse(refused.contains("root:"), "it did not read it: " + refused);
        assertTrue(refused.toLowerCase().contains("outside") || refused.toLowerCase().contains("root"),
                "and it says why rather than failing blankly: " + refused);
    }

    /** A non-zero exit is a result the model interprets, not a failure of the tool. */
    @Test
    void bashSaysWhatItPrintedAndAlwaysSaysTheExitCode(@TempDir Path dir) {
        Map<Tool, Calling> tools = Kit.at(dir).tools();

        String ok = call(tools, "bash",
                "{\"command\":\"echo hello\",\"description\":\"say hello\"}");
        assertTrue(ok.contains("hello"), ok);
        assertTrue(ok.contains("[exit code: 0]"), "every result carries it: " + ok);

        String failed = call(tools, "bash",
                "{\"command\":\"echo oops >&2; exit 3\",\"description\":\"fail on purpose\"}");
        assertTrue(failed.contains("oops"), "stderr is shown: " + failed);
        assertTrue(failed.contains("[exit code: 3]"), "and the code is the model's to interpret");
    }

    @Test
    void aBackgroundCommandComesBackAsAJobThatCanBeReadAndListed(@TempDir Path dir)
            throws Exception {
        Map<Tool, Calling> tools = Kit.at(dir).tools();

        String started = call(tools, "bash", "{\"command\":\"echo working\","
                + "\"description\":\"a long build\",\"run_in_background\":true}");
        assertTrue(started.toLowerCase().contains("job"), "it hands back a job: " + started);

        String listed = call(tools, "job_list", "{}");
        assertTrue(listed.contains("a long build"), "listed by its label: " + listed);
    }

    @Test
    void theTodoListIsReplacedWholesaleEveryCall(@TempDir Path dir) {
        Kit kit = Kit.at(dir);
        Map<Tool, Calling> tools = kit.tools();

        call(tools, "todo_write", "{\"todos\":[{\"content\":\"read the pom\",\"status\":\"completed\"},"
                + "{\"content\":\"write the fake\",\"status\":\"in_progress\"}]}");
        assertTrue(kit.todos().current().contains("write the fake"));
        assertTrue(kit.todos().current().contains("read the pom"));

        call(tools, "todo_write", "{\"todos\":[{\"content\":\"only this\",\"status\":\"pending\"}]}");
        assertFalse(kit.todos().current().contains("read the pom"),
                "the model sends the whole list every time; there are no partial updates");
        assertTrue(kit.todos().current().contains("only this"));
    }

    /**
     * THE BOUND IS ON PROCESSES ALIVE, WHICH IS WHAT run_in_background GETS OUT FROM UNDER.
     *
     * <p>A foreground call is bounded by its timeout. A background one returns immediately, and
     * returning immediately is exactly how a bounded thing escapes its bound — the registry's own
     * cap of 32 is on how many jobs stay READABLE and explicitly never evicts a live one, so before
     * this it capped memory and nothing capped the machine.
     */
    @Test
    void backgroundWorkIsBoundedByProcessesAliveAndNotByIdsIssued() {
        Jobs jobs = new Jobs(2);
        Map<Tool, Calling> tools = jobs.tools();

        String first = jobs.start("first", sleeping());
        jobs.start("second", sleeping());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> jobs.start("third", sleeping()));
        assertTrue(refused.getMessage().contains("job_kill"),
                "and it names the tool that makes room: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("2"), refused.getMessage());

        call(tools, "job_kill", "{\"job_id\":\"" + first + "\"}");

        assertDoesNotThrow(() -> jobs.start("fourth", sleeping()),
                "an ended job frees its slot, because the count is of what is alive rather than "
                        + "of what was ever started");
        call(tools, "job_kill", "{\"job_id\":\"j2\"}");
        call(tools, "job_kill", "{\"job_id\":\"j4\"}");
    }

    /**
     * THE RECORD KEEPS THE WHOLE OUTPUT AND THE MODEL GETS A PAGE OF IT, WHICH IS THE OLDEST RULE
     * IN THIS LIBRARY AND WAS NOT TRUE OF {@code bash} UNTIL 0.27.0.
     *
     * <p>{@code Retain.MOST} bounds what the model is shown, correctly. {@code Shell.KEEPING}
     * bounds the heap, correctly. Everything between them — up to a million characters of a failing
     * build — was read, held, shown as sixteen thousand, and then dropped when the call returned.
     * For {@code read} that is harmless: the file is still on disk and the footer names the page.
     * For {@code bash} the output IS the only copy, and ten thousand lines of compiler errors exist
     * nowhere else once the process has gone.
     *
     * <p>Reported by the consumer whose own rule is "bound the prompt, never the record", who took
     * the narrowing knowingly rather than let it drift unrecorded.
     */
    @Test
    void bashHandsTheWholeOutputToTheStoreAndTheModelAPageWithTheWayBack(@TempDir Path dir) {
        StringBuilder saved = new StringBuilder();
        Map<Tool, Calling> tools = Kit.at(dir, Duration.ofMinutes(1), 4,
                Spilling.to(whole -> {
                    saved.append(whole);
                    return "The whole of it is at out.txt; read it with offset and limit.";
                })).tools();

        String shown = call(tools, "bash",
                "{\"command\":\"seq 1 5000\",\"description\":\"print a lot\"}");

        assertTrue(saved.toString().contains("\n5000"),
                "the store got the end of the output, which is the part a bound removes");
        assertFalse(shown.contains("\n5000\n"), "and the model did not: " + tail(shown));
        assertTrue(shown.contains("out.txt"),
                "and it is told where the rest is, which is the difference between a magnitude and "
                        + "a way back: " + tail(shown));
        assertTrue(shown.contains("[exit code: 0]"), "the code still survives the bound");
    }

    /**
     * AND A RESULT THAT FITS IS NOT SPILLED, which is not an optimisation.
     *
     * <p>{@code Spilling.to} passed {@code save.apply(whole)} as an ARGUMENT, so the store ran on
     * every result however small — and {@code recoverableBy} correctly drops the notice when
     * nothing was omitted, so the file was written and the locator thrown away. A caller wiring
     * this to a filesystem got one file per tool call, nearly all of them for output that travelled
     * whole.
     */
    @Test
    void aResultThatFitsIsNeverHandedToTheStore(@TempDir Path dir) {
        StringBuilder saved = new StringBuilder();
        Map<Tool, Calling> tools = Kit.at(dir, Duration.ofMinutes(1), 4,
                Spilling.to(whole -> {
                    saved.append(whole);
                    return "stored";
                })).tools();

        String shown = call(tools, "bash", "{\"command\":\"echo small\",\"description\":\"tiny\"}");

        assertTrue(shown.contains("small"), shown);
        assertEquals("", saved.toString(),
                "nothing was omitted, so nothing needed storing");
    }

    private static ProcessBuilder sleeping() {
        return new ProcessBuilder("sleep", "30");
    }

    private static String call(Map<Tool, Calling> tools, String name, String arguments) {
        return tools.entrySet().stream()
                .filter(e -> e.getKey().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tool called " + name))
                .getValue()
                .run(new Called("c1", name, arguments));
    }

    private static String lines(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to; i++) {
            out.append("line ").append(i).append('\n');
        }
        return out.toString();
    }

    private static String tail(String s) {
        return s.length() < 200 ? s : s.substring(s.length() - 200);
    }
}
