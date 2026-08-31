package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Tool;
import tech.mikhailov.ratchet.tools.Kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void theMeasuredSetIsWhatIsOffered(@TempDir Path dir) {
        assertEquals(
                java.util.List.of("read", "write", "edit", "list_dir", "bash",
                        "job_output", "job_list", "job_kill", "todo_write"),
                Kit.at(dir).tools().keySet().stream().map(Tool::name).toList(),
                "the set TOOLS.md measured, in the order a reader meets them");
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
