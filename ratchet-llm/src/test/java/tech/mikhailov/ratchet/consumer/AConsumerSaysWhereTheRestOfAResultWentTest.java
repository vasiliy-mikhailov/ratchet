package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Recording;
import tech.mikhailov.ratchet.llm.Spilling;
import tech.mikhailov.ratchet.llm.Tool;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A MAGNITUDE SAYS SOMETHING IS MISSING; A LOCATOR LETS THE READER GO AND GET IT.
 *
 * <p>In another package because the store is the consumer's. Where a spilled result goes is a fact
 * about their filesystem, their session and their retention, and a library called from inside
 * somebody else's program can see none of it.
 */
class AConsumerSaysWhereTheRestOfAResultWentTest {

    private static final String HUGE = "y".repeat(41_208);

    @Test
    void aConsumerSaysWhereTheRestOfAResultWent(@TempDir Path dir) {
        List<String> saved = new ArrayList<>();

        Spilling theirs = Spilling.to(whole -> {
            Path where = dir.resolve("a1b2c3d4e5f6-read_file.txt");
            try {
                Files.writeString(where, whole);
            } catch (Exception cannot) {
                throw new IllegalStateException(cannot);
            }
            saved.add(where.toString());
            return "Full result stored at: " + where + ". Use read with offset/limit, or grep it.";
        });

        String shown = ran(theirs, 2_000);

        assertTrue(shown.contains("(truncated, total 41208 chars)"), "the magnitude: " + shown);
        assertTrue(shown.contains("Use read with offset/limit"), "and the locator with it");
        assertEquals(HUGE, readBack(saved.get(0)), "and the whole thing is where they put it");
    }

    /**
     * A HARD CAP, BECAUSE THIS BOUND IS ON WHAT ENTERS A MODEL'S CONTEXT. The notice is paid for out
     * of the room rather than pushing the result past the ceiling it exists to enforce — which is
     * the one place in this library where reserving is right.
     */
    @Test
    void thePreviewAndItsNoticeTogetherFitInsideTheRoomAllowed(@TempDir Path dir) {
        Spilling theirs = Spilling.to(whole -> "at /tmp/x.txt");

        for (int room : new int[]{200, 2_000, 20_000}) {
            String shown = ran(theirs, room);
            assertTrue(shown.length() <= room + " at /tmp/x.txt".length(),
                    "room " + room + " gave " + shown.length() + ": the preview and its notice fit, "
                            + "and only the caller's own sentence is added on top");
        }
    }

    /** No store to point at is not a reason to say less about the loss. */
    @Test
    void aCallerWithNowhereToPutItStillSaysHowMuchAndWhatToDo() {
        String shown = ran(Spilling.none(), 2_000);

        assertTrue(shown.contains("(truncated, total 41208 chars)"), shown);
        assertTrue(shown.endsWith("Narrow the request if you need the rest."),
                "which is the most that can be said without somewhere to put it");
        assertFalse(shown.contains("stored at"), "and it does not invent a locator");
    }

    /** The corpus wants everything; the prompt does not. That split is older than this seam. */
    @Test
    void theRecordStillGetsTheWholeThingHoweverTheCallerSpillsIt() {
        List<String> recorded = new ArrayList<>();
        Trace trace = new Kept(recorded);

        Map<Tool, Calling> wrapped = Recording.at(
                Map.of(new Tool("read_file", "reads", "{}"), call -> HUGE),
                trace, "agent:doer", 2_000, Spilling.to(whole -> "at /tmp/x.txt"));

        String shown = wrapped.values().iterator().next().run(new Called("c", "read_file", "{}"));

        assertTrue(shown.length() < HUGE.length(), "the model got a preview");
        assertEquals(HUGE, recorded.get(0), "and the record got all 41,208 characters of it");
    }

    private static String ran(Spilling spilling, int room) {
        Map<Tool, Calling> wrapped = Recording.at(
                Map.of(new Tool("read_file", "reads", "{}"), call -> HUGE),
                new Kept(new ArrayList<>()), "agent:doer", room, spilling);
        return wrapped.values().iterator().next().run(new Called("c", "read_file", "{}"));
    }

    private static String readBack(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception cannot) {
            throw new IllegalStateException(cannot);
        }
    }

    /** A consumer's trace: eight empty methods and one that keeps what a tool returned. */
    private record Kept(List<String> results) implements Trace {

        @Override
        public void tool(String agent, String tool, String arguments, String result) {
            results.add(result);
        }

        @Override public void asked(String agent, String prompt, String reply) { }

        @Override public void applied(String stage, String what) { }

        @Override public void thought(String agent, String finish, String thinking, String content) { }

        @Override public void built(String phase, Trace.Outcome result) { }

        @Override public void settled(String key, String state, String because, boolean before,
                                      boolean after) { }

        @Override public void failed(String key, Throwable cause) { }

        @Override public void progress(String key, String note) { }

        @Override public void priced(String key, String minutes, String itemisation) { }
    }
}
