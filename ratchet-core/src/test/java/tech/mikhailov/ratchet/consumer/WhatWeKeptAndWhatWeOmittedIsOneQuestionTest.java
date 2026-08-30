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

    /** A readability bound: the budget is content, and the notice is the cost of saying so. */
    @Test
    void aBudgetOnContentKeepsThatMuchContentAndSaysWhatItDropped() {
        Retained kept = Retained.head("y".repeat(5_000), 100);

        assertTrue(kept.text().startsWith("y".repeat(100)), "a hundred characters of the line");
        assertTrue(kept.text().endsWith("(truncated, total 5000 chars)"), "and then the notice");
        assertEquals(new Retained.Omitted.Exact(4_900), kept.omitted());
    }

    /**
     * A HARD CAP IS A DIFFERENT QUESTION AND GETS A DIFFERENT METHOD. Reserving everywhere was the
     * first design and it was wrong for this library: at a bound of 40 it yields seven characters of
     * content and thirty-three of apology. It is right where the bound is on bytes entering a
     * model's context, which is what a spill notice must not push past.
     */
    @Test
    void aHardCapPaysForItsOwnNoticeSoTheResultFitsInside() {
        for (int cap : new int[]{100, 180, 1_000}) {
            Retained kept = Retained.within("y".repeat(5_000), cap);
            assertTrue(kept.text().length() <= cap,
                    "cap " + cap + " gave " + kept.text().length());
        }
    }

    /**
     * THE DEFECT A LIVE RUN FOUND, AND THE ONE INVARIANT BOTH BUDGETS KEEP. The notice costs 33
     * characters, so a 191-character line at a content bound of 180 would render as 213 — larger for
     * having been cut, and missing its end. Neither method will do that.
     *
     * <p>They differ in what they do instead. A content bound declines the cut and hands back all
     * 191. A hard cap has room to pay for the notice out of its own budget, so it cuts to 147 plus
     * 33 and fits inside the 180 it was given.
     */
    @Test
    void aCutResultIsNeverLargerThanWhatItReplaced() {
        String justOver = "y".repeat(191);

        assertEquals(justOver, Retained.head(justOver, 180).text(),
                "180 of content plus a 33-character notice is 213, which is bigger than the 191 it "
                        + "replaced and would have lost the end of the line to say so");
        assertEquals(180, Retained.within(justOver, 180).text().length(),
                "under a hard cap the same line fits, because the notice comes out of the budget");
    }

    /** When even the notice will not fit, there is nothing to gain and the text comes back whole. */
    @Test
    void aBudgetTooSmallForTheNoticeKeepsTheTextWhole() {
        String text = "y".repeat(191);

        Retained kept = Retained.within(text, 20);

        assertEquals(text, kept.text(), "a 33-character notice inside a cap of 20 would leave no "
                + "room for any of the text, so the replacement would be all notice and no content");
        assertFalse(kept.cut(), "and it does not claim a cut it did not make");
    }

    /**
     * IDEMPOTENCE BELONGS TO THE HARD CAP AND NOT TO THE CONTENT BOUND, and that is not a defect in
     * either — it falls out of what each one promises. {@link Retained#within} returns at most the
     * cap, so a second pass sees something already inside it and does nothing. {@link Retained#head}
     * returns the budget PLUS a notice, which is by construction over the budget, so a second pass
     * cuts again and keeps cutting.
     *
     * <p>It does not bite the callers here, which each cut once from raw text. It would bite a
     * caller that folded the rule over its own output, and that is worth knowing before they do.
     */
    @Test
    void aSecondPassChangesNothingUnderAHardCapAndKeepsCuttingUnderAContentBound() {
        Retained capped = Retained.within("z".repeat(9_000), 500);
        assertEquals(capped.text(), Retained.within(capped.text(), 500).text(),
                "already inside the cap, so there is nothing left to do");

        Retained bounded = Retained.head("z".repeat(9_000), 500);
        assertTrue(Retained.head(bounded.text(), 500).cut(),
                "content plus a notice is over the content bound by the width of the notice, so "
                        + "folding this one over its own output never settles");
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

        assertEquals(new Retained.Omitted.Exact(800), kept.omitted(), "the count is what did not fit, not what was wrong "
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
