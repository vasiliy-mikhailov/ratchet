package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the trace writes, a reader must read back byte for byte, or every label on it lies. */
class TheRecordSurvivesARoundTripTest {

    @Test
    void aPromptWithEveryAwkwardCharacterComesBackIntact(@TempDir Path dir) throws Exception {
        Path trace = dir.resolve("trace.jsonl");
        String nasty = "line one\n\t\"quoted\" \\ backslash\nline three";
        new JsonlTrace(trace, dir.resolve("settlements.jsonl"), "r|s|8|11")
                .asked("fixer", nasty, "a reply");
        Map<String, String> row = Json.row(Files.readAllLines(trace).get(0));
        assertEquals(nasty, row.get("prompt"));
        assertEquals("asked", row.get("kind"));
        assertEquals("a reply", row.get("reply"));
    }
}
