package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

import tech.mikhailov.ratchet.record.Retained;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIX PLACES CUT TEXT AND EACH BROUGHT ITS OWN MARKER. This is the one that replaces them, and it
 * is in another package because a consumer bounding its own output needs it as much as this library
 * does.
 */
class WhatWeKeptAndWhatWeOmittedIsOneQuestionTest {

    /** An emoji is two UTF-16 units and one code point, which is where the old cut went wrong. */
    private static final String EMOJI = "😀";

    @Test
    void aResultIsNeverLargerThanTheBudgetItWasGiven() {
        for (int budget : new int[]{40, 100, 180, 1_000}) {
            Retained kept = Retained.head("y".repeat(5_000), budget);
            assertTrue(kept.text().length() <= budget,
                    "the notice is paid for out of the budget rather than added to it, so a result "
                            + "is at most what was asked for: budget " + budget + " gave "
                            + kept.text().length());
        }
    }

    /**
     * THE DEFECT A LIVE RUN FOUND, AND WHY RESERVING BEATS DECLINING. The notice costs 33
     * characters. Added ON TOP of the bound, a 191-character line at 180 rendered as 213 — larger
     * for having been cut, and missing its end. 0.18.1 fixed that by declining to cut at all, which
     * left the reader 191 characters where 180 were asked for.
     *
     * <p>Reserved OUT OF the bound instead, the same line cuts to 147 plus a 33-character notice:
     * exactly the 180 requested, smaller than the 191 it replaced, and the reader is told the size
     * of what is missing. Declining was the right fix for the wrong shape.
     */
    @Test
    void aCutResultIsAlwaysSmallerThanWhatItReplaced() {
        String justOver = "y".repeat(191);

        Retained kept = Retained.head(justOver, 180);

        assertEquals(180, kept.text().length(), "exactly the budget, not the budget plus a notice");
        assertTrue(kept.text().length() < justOver.length(), "and smaller than what it replaced");
        assertTrue(kept.cut(), "which is a cut, and it says so");
    }

    /** When even the notice will not fit, there is nothing to gain and the text comes back whole. */
    @Test
    void aBudgetTooSmallForTheNoticeKeepsTheTextWhole() {
        String text = "y".repeat(191);

        Retained kept = Retained.head(text, 20);

        assertEquals(text, kept.text(), "a 33-character notice inside a budget of 20 would leave no "
                + "room for any of the text, so the replacement would be all notice and no content");
        assertFalse(kept.cut(), "and it does not claim a cut it did not make");
    }

    /** A result that has already been cut is a result: running the rule again must be a no-op. */
    @Test
    void aSecondPassOverACutResultChangesNothing() {
        Retained once = Retained.head("z".repeat(9_000), 500);
        assertTrue(once.cut(), "the first pass cut it");

        Retained twice = Retained.head(once.text(), 500);

        assertEquals(once.text(), twice.text(), "every result is strictly smaller than its input, "
                + "so a second pass has nothing left to do");
        assertFalse(twice.cut());
    }

    /**
     * ON THE WIRE A SPLIT PAIR BECOMES A QUESTION MARK. In the record it is worse: writeString
     * refuses to encode a lone surrogate, and the writer that catches the failure drops the row.
     */
    @Test
    void aCutLandsOnACodePointBoundaryAndNeverInsideOne(@TempDir Path dir) throws Exception {
        String text = "x".repeat(60) + EMOJI + "y".repeat(400);

        // Every budget in this range puts the cut somewhere around the emoji.
        for (int budget = 88; budget <= 96; budget++) {
            String out = Retained.head(text, budget).text();

            assertFalse(out.chars().anyMatch(c -> Character.isHighSurrogate((char) c)
                            && out.indexOf((char) c) == out.length() - 1),
                    "a cut left a lone high surrogate at the end, at budget " + budget);
            assertFalse(new String(out.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                            .contains("�"),
                    "and it does not become a replacement character on the wire, at " + budget);
            Path row = dir.resolve("row-" + budget + ".txt");
            assertDoesNotThrow(() -> Files.writeString(row, out),
                    "and the record can encode it, at budget " + budget + " — an unencodable row "
                            + "is not written at all, and the writer swallows the failure");
        }
    }

    @Test
    void whatWasOmittedIsAFactAboutTheBudgetAndSaysHowMuch() {
        Retained kept = Retained.head("y".repeat(1_000), 200);

        assertEquals(new Retained.Omitted.Exact(1_000 - (200 - " ... (truncated, total 1000 chars)"
                .length())), kept.omitted(), "the count is what did not fit, not what was wrong "
                + "with the input: a permission failure or an unreadable file is somebody else's "
                + "field, and folding it in here is the mistake this naming most invites");
        assertTrue(kept.text().contains("total 1000 chars"), "and the reader is told the whole size");
    }

    /** A record that cannot say how much it withheld must say THAT, not say nothing. */
    @Test
    void unknownIsARealAnswerAndNotTheSameAsNothingOmitted() {
        assertNotEquals(new Retained.Omitted.None(), new Retained.Omitted.Unknown(),
                "one means everything fitted and the other means nobody knows, and a column that "
                        + "reports the second as the first is how a bound survives eight releases");
    }

    @Test
    void aBudgetThatKeepsNothingIsRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Retained.head("anything", 0)).getMessage().contains("no result at all"));
        assertThrows(IllegalArgumentException.class, () -> Retained.head("anything", -1));
    }

    /** A magnitude says something is missing. Recovery guidance says how to go and get it. */
    @Test
    void aConsumerCanSayHowToGetTheRest() {
        Retained kept = Retained.head("y".repeat(9_000), 300)
                .recoverableBy("Read the full result at /tmp/spill/abc-read_file.txt.");

        assertTrue(kept.text().endsWith("/tmp/spill/abc-read_file.txt."));
        assertTrue(kept.text().contains("total 9000 chars"), "magnitude and locator, not one or "
                + "the other: a magnitude tells a reader something is missing and a locator lets "
                + "them go and get it");
        assertEquals("nothing", Retained.head("nothing", 100).recoverableBy("go here").text(),
                "and nothing is appended when nothing was lost");
    }
}
