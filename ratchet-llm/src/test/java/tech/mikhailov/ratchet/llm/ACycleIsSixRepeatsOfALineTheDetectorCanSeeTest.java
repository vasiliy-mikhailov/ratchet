package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIX REPEATS OF SIXTY CHARACTERS IS THE BOUND, AND A LINE THE SPLITTER NEVER TAKES OFF THE BUFFER
 * IS NOT COUNTED AT ALL.
 *
 * <p>{@link Reasoning}'s two constants are measurements rather than preferences: six repeats of a
 * substantial line caught 66 of 68 real runaways at a median 27% of the reasoning, against 4 false
 * positives in 824 healthy turns. Neither was pinned anywhere near its edge. The shortest repeated
 * line any test used measures TWELVE characters once flattened, against a threshold of sixty, so
 * {@code SUBSTANTIAL} could have been any number from thirteen to sixty with the suite green;
 * {@code REPEATS} was held only sideways, by a count of newlines in one recorded row, and nothing
 * anywhere said what five repeats of one line must do. A pair of numbers that cost a corpus study
 * to arrive at should be readable off the tests, from both sides.
 *
 * <p>THE MUTANT THAT COSTS THE MOST IS ON NEITHER CONSTANT. {@code pending.indexOf("\n") >= 0} is
 * the loop that cuts the stream into lines; move that boundary to {@code > 0} and every test in
 * this module stays green while the detector goes permanently blind the first time the model leaves
 * a BLANK LINE between two paragraphs — from then on the buffer begins with a newline,
 * {@code indexOf} answers 0, and nothing is ever taken off the front again. Reasoning arrives in
 * paragraphs. The line that would have been counted the sixth time is still sitting in the buffer
 * three hours later when the ceiling ends the lane, and the ceiling doing the detector's job costs
 * the whole budget the detector exists to save: 14 trapped generations were restarted with 2,500
 * more tokens each and none escaped.
 *
 * <p>AND THE ROW SAYS WHAT ARRIVED, NOT WHAT NEARLY DID. Both setters guard against nothing having
 * arrived and neither guard was asserted. {@link tech.mikhailov.ratchet.record.Json#read} answers
 * with an empty string for a field that is not on the frame, but a parser that distinguishes
 * absence answers with null, and {@code StringBuilder.append(null)} writes the four letters
 * {@code null} into the record as an answer the model never gave. A blank finish reason is the same
 * mistake in the other column: every delta but one carries {@code "finish_reason": null}, and the
 * one that says {@code length} is the difference between a truncation somebody can diagnose and an
 * empty nobody can — only 75 of 182 empties were visible as {@code length} before that row existed.
 *
 * <p>WHAT IS BELOW THIS FILE AND CANNOT BE ASSERTED FROM IT: the counter only ever sees text that
 * arrived with a newline behind it. Reasoning that repeats WITHOUT a line break never reaches a
 * counter at all — it accumulates in {@code pending} until the generation ends — so the unit the
 * threshold is measured in is also the limit of what the threshold can see. Two of the 68 runaways
 * in the corpus went uncaught, and that shape is the first place to look. Raised separately;
 * nothing here pretends to cover it.
 */
class ACycleIsSixRepeatsOfALineTheDetectorCanSeeTest {

    /**
     * SIXTY FLATTENED CHARACTERS: the shortest line the detector is allowed to count.
     *
     * <p>Flattening collapses whitespace and punctuation to single spaces, strips and lowercases,
     * so a fixture written in lowercase letters and single spaces measures as itself. That is why
     * these two read a little plainly — what is written here IS what the threshold sees.
     */
    private static final String SUBSTANTIAL =
            "so the parent pom pins the compiler plugins and nothing else";

    /** The same sentence one character shorter, which is a habit and not a cycle. */
    private static final String ONE_SHORT =
            "so the parent pom pins the compiler plugin and nothing else";

    /** Comfortably over the threshold, for the tests that are about counting rather than length. */
    private static final String TRAPPED =
            "let me check each proactive trigger against the build files in turn now";

    @Test
    void aBlankLineBetweenParagraphsDoesNotBlindTheDetector() {
        // The ordinary shape of reasoning: a paragraph, a blank line, another paragraph. The blank
        // line leaves the newline that opens the next round at index 0 of the buffer, which is the
        // one position a `> 0` splitter cannot see — and once it cannot see it, it never advances
        // past it again, so this is the last line the detector would ever read.
        Recorder r = new Recorder();

        Reasoning.LoopDetected caught = assertThrows(Reasoning.LoopDetected.class,
                () -> client(r).read(reasoning(paragraphs(TRAPPED, 12))),
                "a cycle written in paragraphs is still a cycle");

        assertTrue(caught.getMessage().contains(TRAPPED),
                "and the trapped line is the diagnosis, so it is named: " + caught.getMessage());
        assertEquals("loop", r.thoughts.get(0).finish(),
                "the row says the generation was ended for looping, which is what stopped the bill");
        assertEquals(6, copiesIn(r.thoughts.get(0).thinking(), TRAPPED),
                "at the sixth paragraph, not at the twelfth when the stream happened to run out: "
                        + r.thoughts.get(0).thinking());
    }

    @Test
    void aLineExactlyAtTheLengthThresholdIsSubstantial() {
        Recorder r = new Recorder();
        assertEquals(60, SUBSTANTIAL.length(),
                "this fixture IS the boundary, so it has to sit on it exactly: " + SUBSTANTIAL);

        Reasoning.LoopDetected caught = assertThrows(Reasoning.LoopDetected.class,
                () -> client(r).read(reasoning(lines(SUBSTANTIAL, 12))),
                "sixty is the measured threshold and it is inclusive: a line that flattens to "
                        + "exactly sixty is the first one that counts, and an edge nobody has "
                        + "stood on is an edge nobody has checked");

        assertTrue(caught.getMessage().contains("6 times"),
                "six is the measured count and the message names the count that tripped it: "
                        + caught.getMessage());
        assertTrue(caught.getMessage().contains(SUBSTANTIAL),
                "with the line itself, because which line trapped it is the whole diagnosis: "
                        + caught.getMessage());
    }

    @Test
    void aLineOneCharacterShorterIsLeftToFinish() {
        // THE OTHER SIDE OF THE SAME BOUND, one character away. A detector that fires early is
        // worse than one that fires late: it ends a generation that was going to succeed, and the
        // measured price of the sixty is 4 false positives in 824 healthy turns. Held from above
        // only, the threshold could have been any number from thirteen to sixty and every test
        // written before this one would have agreed with all of them.
        Recorder r = new Recorder();
        assertEquals(59, ONE_SHORT.length(),
                "one under the threshold, so the pair states where the edge is: " + ONE_SHORT);

        Reply reply = client(r).read(reasoning(lines(ONE_SHORT, 12)));

        assertEquals("ok", reply.said(),
                "the generation was left to answer, which is what not firing means");
        assertEquals("stop", r.thoughts.get(0).finish(),
                "and the row says the server finished it, not that a guard did");
        assertEquals(12, copiesIn(r.thoughts.get(0).thinking(), ONE_SHORT),
                "all twelve arrived, so this passes because the line is short rather than because "
                        + "nothing reached the detector: " + r.thoughts.get(0).thinking());
    }

    @Test
    void fiveRepeatsIsNotYetACycle() {
        // Five repeats of one substantial line is what a model doing careful work on a repetitive
        // codebase looks like. Six is the number that separated 68 runaways from 824 healthy turns,
        // and it is a number: at five the generation is still the consumer's to finish.
        Recorder r = new Recorder();

        Reply reply = client(r).read(reasoning(lines(TRAPPED, 5)));

        assertEquals("ok", reply.said(),
                "five is under the bound and ending it here would throw away the answer");
        assertEquals("stop", r.thoughts.get(0).finish(),
                "and the row records an ordinary finish, not a detection");
        assertEquals(5, copiesIn(r.thoughts.get(0).thinking(), TRAPPED),
                "five repeats did arrive: " + r.thoughts.get(0).thinking());
    }

    @Test
    void aFieldThatIsNotOnTheFrameAddsNothingToTheRow() {
        // Most frames carry one of these two and not the other: a reasoning delta has no content
        // field at all, and a content delta has no reasoning. Json.read answers "" for a field that
        // is not there, so Wire hands down empties rather than nulls — but Reasoning is public and
        // its next caller may parse with something that distinguishes absence. Both buffers must
        // treat that as nothing having arrived: unguarded, one throws a NullPointerException out of
        // the middle of a live generation and the other writes the four letters n-u-l-l into the
        // record as an answer the model never gave. `thought` is a row prompt tuning replays, so an
        // invented answer there is an invented training pair.
        Recorder r = new Recorder();
        Reasoning watching = new Reasoning(r, "doer");

        watching.reasoned(null);
        watching.reasoned(TRAPPED + "\n");
        watching.said(null);
        watching.said("eleven");
        watching.said(null);
        watching.ended("stop");
        watching.flush();

        assertEquals(TRAPPED + "\n", r.thoughts.get(0).thinking(),
                "the row holds the thinking that arrived: " + r.thoughts.get(0).thinking());
        assertEquals("eleven", r.thoughts.get(0).content(),
                "and the answer the model gave, with nothing standing in for the frames that "
                        + "carried none: " + r.thoughts.get(0).content());
    }

    @Test
    void aFrameThatSaysNothingAboutWhyItStoppedDoesNotEraseTheOneThatDid() {
        // Absence has two spellings on this path — null from a parser that distinguishes it, and
        // "" from Json.read, which returns an empty string for a field that is not there — and one
        // frame in the generation carries the real reason. Whichever order they arrive in, the row
        // must report what the server SAID: `length` is a truncation somebody can act on and a
        // blank is an empty nobody can diagnose, which was the state of 107 of 182 of them.
        Recorder r = new Recorder();
        Reasoning watching = new Reasoning(r, "doer");

        watching.reasoned(TRAPPED + "\n");
        watching.ended(null);
        watching.ended("length");
        watching.ended("");
        watching.ended("   ");
        watching.flush();

        assertEquals("length", r.thoughts.get(0).finish(),
                "the one frame that said why it stopped is what the row reports: "
                        + r.thoughts.get(0).finish());
    }

    @Test
    void theGenerationIsStillEndedWhenNothingIsRecording() {
        // THE ABORT IS FOR THE BILL, NOT FOR THE ROW. A detector that only fires when somebody is
        // watching pays the full runaway on every untraced call, and greedy decoding does not
        // leave a cycle it has entered.
        //
        // BOTH DOORS, because they are not the same door. A consumer that wants no record hands
        // Wire a null trace, but Wire coerces that to Trace.quiet() in its own constructor and
        // passes the coerced field down, so the null NEVER REACHES Reasoning through the
        // transport — what the first half of this test drives is a quiet trace, not a missing one.
        // Reasoning is public with a public constructor, so its next caller reaches the second
        // door directly, and that is the one where the null actually arrives.
        Reasoning.LoopDetected throughTheWire = assertThrows(Reasoning.LoopDetected.class,
                () -> client(null).read(reasoning(lines(TRAPPED, 12))),
                "the cycle is ended whether or not there is anywhere to write it down");

        assertTrue(throughTheWire.getMessage().contains(TRAPPED),
                "and it still says which line trapped it: " + throughTheWire.getMessage());

        Reasoning unwatched = new Reasoning(null, "doer");
        Reasoning.LoopDetected untraced = assertThrows(Reasoning.LoopDetected.class,
                () -> {
                    for (int repeat = 0; repeat < 12; repeat++) {
                        unwatched.reasoned(TRAPPED + "\n");
                    }
                },
                "and a Reasoning built with no trace at all still ends the cycle: the guard is not "
                        + "a side effect of recording it");

        assertTrue(untraced.getMessage().contains("6 times"),
                "at the same sixth repeat, so nothing about the count depends on a listener: "
                        + untraced.getMessage());
        // The row goes nowhere and that must not be an exception out of a live generation either:
        // a trace that cannot be written must never be why a run fails.
        unwatched.ended("stop");
        unwatched.flush();
    }

    // ---------------------------------------------------------------- the fakes

    /**
     * A client with no socket under it, so frames can be handed straight to {@link Wire#read}.
     *
     * <p>A null trace is the shape a consumer that wants no record leaves behind. It does NOT reach
     * {@link Reasoning} as a null: {@link Wire} coerces it to {@link Trace#quiet()} in its own
     * constructor and hands the coerced field down, which is why the test that cares about a
     * missing trace builds a {@link Reasoning} itself as well.
     */
    private static Wire client(Trace trace) {
        return new Wire(Endpoint.of("http://localhost:1", "a-model"), Sampling.deterministic(),
                Watch.shipped(), true, trace);
    }

    /**
     * One reasoning delta per line, each ending its own line.
     *
     * <p>The {@code \\n} is two characters here and two bytes on the wire: JSON's escape for a
     * newline, which the reader turns back into the one character the detector splits on.
     */
    private static List<String> lines(String line, int times) {
        return Collections.nCopies(times, line + "\\n");
    }

    /** The same, with the blank line a model leaves between two paragraphs. */
    private static List<String> paragraphs(String line, int times) {
        return Collections.nCopies(times, line + "\\n\\n");
    }

    /**
     * One generation that thinks these deltas and then answers, in the frame shapes captured from
     * the production endpoint: the reasoning under the server's own {@code reasoning} field, the
     * finish reason on the last content frame, then the usage chunk whose {@code choices} is empty.
     */
    private static Stream<String> reasoning(List<String> deltas) {
        List<String> chunks = new ArrayList<>();
        chunks.add("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},"
                + "\"finish_reason\":null}]}");
        for (String delta : deltas) {
            chunks.add("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"" + delta + "\"},"
                    + "\"finish_reason\":null}]}");
        }
        chunks.add("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}");
        chunks.add("{\"choices\":[],\"usage\":{\"prompt_tokens\":57,\"total_tokens\":69,"
                + "\"completion_tokens\":12}}");
        return frames(chunks.toArray(new String[0]));
    }

    /** Each frame on a {@code data:} line, a blank line between frames, {@code [DONE]} at the end. */
    private static Stream<String> frames(String... chunks) {
        List<String> lines = new ArrayList<>();
        for (String chunk : chunks) {
            lines.add("data: " + chunk);
            lines.add("");
        }
        lines.add("data: [DONE]");
        return lines.stream();
    }

    /** How many times the line actually reached the row, so a count can fail loudly at zero. */
    private static int copiesIn(String text, String piece) {
        int found = 0;
        for (int at = text.indexOf(piece); at >= 0; at = text.indexOf(piece, at + piece.length())) {
            found++;
        }
        return found;
    }

    private record Thought(String finish, String thinking, String content) {
    }

    private static final class Recorder implements Trace {
        final List<Thought> thoughts = new ArrayList<>();

        public void thought(String agent, String f, String t, String c) {
            thoughts.add(new Thought(f, t, c));
        }

        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String k, String s, String w, boolean before, boolean after) {
        }

        public void failed(String k, Throwable c) {
        }

        public void progress(String k, String n) {
        }

        public void priced(String k, String m, String i) {
        }
    }
}
