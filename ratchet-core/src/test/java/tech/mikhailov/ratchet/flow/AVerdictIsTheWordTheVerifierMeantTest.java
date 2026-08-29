package tech.mikhailov.ratchet.flow;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE VERDICT IS THE FIRST WORD OF A LINE, AND EVERYTHING ELSE IS NOT A VERDICT.
 *
 * <p>THIS FILE USED TO HOLD EIGHT REQUIREMENTS AND NOW HOLDS FOUR, because five of them described
 * the parser rather than the system. {@code Reply.word} scanned the whole reply as prose: word
 * boundaries so {@code sound} could not hide inside {@code unsound}, a seventeen-form English
 * negation detector over a 24-character lookbehind so "this is not done" did not read as done, and
 * earliest-match arbitration for a reply naming two verdicts. Sixteen of that file's forty-six
 * lines.
 *
 * <p>Those collisions could only arise BECAUSE the reply was searched as prose. Reading the first
 * token instead does not need to be told that {@code against} is not {@code again}; the question
 * never comes up. And the case the whole apparatus defended — a verifier answering in prose — has
 * never occurred: every verifier in this library and in the pipeline built on it is plain code
 * returning a literal, {@code "done"} or {@code "again: <reason>"}.
 *
 * <p>THE FIFTH RETIRED REQUIREMENT WAS ACTIVELY WRONG. "A reply that names no verdict is read as
 * approval": {@code word} fell back to {@code allowed[0]}, which the one caller passes as
 * {@code "done"}, so silence approved — and {@code Flow.Triad.verdictOf} carried a second blank
 * check returning "again" to undo it. Two layers doing one job, the lower one defaulting to the
 * reading this project argues against everywhere else. What silence means is decided once now, by
 * the caller that knows.
 */
class AVerdictIsTheWordTheVerifierMeantTest {

    @Test
    void aLineThatOpensWithAVerdictIsThatVerdictHoweverItIsDecorated() {
        // ONE RULE INSTEAD OF SEVEN CLAUSES. The old code enumerated `done`, `done:`, `done `,
        // `done.`, `done,`, `done;` and `done!` and would have missed the eighth punctuation mark
        // somebody eventually used.
        //
        // THE FIRST ATTEMPT AT THE ONE RULE WAS WRONG, and this comment argued for it: it split the
        // LINE on [\s\p{Punct}] and compared the first token, which is the same rule only while
        // every allowed word is one word. See aVerdictMadeOfTwoWordsJoinedByAHyphenIsMatchedWhole.
        for (String said : new String[]{"done", "done:", "done.", "done!", "done — nothing left",
                                        "  done  ", "- done", "> done", "`done`"}) {
            assertEquals("done", Reply.word(said, "done", "again", "replan"),
                    "the verifier said done, however it decorated it: " + said);
        }
    }

    @Test
    void theVerdictMayArriveOnALaterLine() {
        // A verifier that opens with a blank line or a heading still has a verdict, and this is the
        // shape that actually occurs: 95% of one corpus's replies began with a blank line.
        assertEquals("again", Reply.word("\n\nagain: the span does not occur in the chapter",
                "done", "again", "replan"));
        assertEquals("replan", Reply.word("## verdict\nreplan: the plan asked for the wrong thing",
                "done", "again", "replan"));
    }

    @Test
    void aWordInsideAnotherWordIsNotAVerdict() {
        // The defence is that the allowed word must END where it ends: "against" continues with a
        // letter after "again", so the word did not end there. A line beginning "against" or
        // "unsound" must not be read as "again" or "done".
        assertEquals("", Reply.word("against the plan as written", "done", "again", "replan"));
        assertEquals("", Reply.word("unsound reasoning throughout", "done", "again", "replan"));
    }

    @Test
    void aReplyThatNamesNoVerdictSaysSoRatherThanGuessing() {
        // THE ONE THAT WAS WRONG BEFORE. This used to answer the caller's first word, which the
        // only caller passes as the approving one, so a verifier that wrote a paragraph without a
        // verdict approved the work. It answers nothing now, and the caller decides what nothing
        // means — see theTriadReadsNothingAsAgain.
        assertEquals("", Reply.word("the chapter is long and I am unsure", "done", "again"));
        assertEquals("", Reply.word("", "done", "again"));
        assertEquals("", Reply.word(null, "done", "again"));
    }

    /**
     * REPORTED FROM OUTSIDE, AND UNSEEABLE FROM IN HERE.
     *
     * <p>The vocabulary this library itself asks for is {@code done}, {@code again} and
     * {@code replan} — three single words — so every test in this repository passed while
     * {@code \p{Punct}} silently ate the hyphen out of a consumer's. Their verdicts are
     * {@code blocked-dependency}, {@code behavior-change}, {@code off-target} and
     * {@code wrong-call}, and on 0.15.0 every one of them came back as the empty string into the
     * field their whole corpus is aggregated on.
     */
    @Test
    void aVerdictMadeOfTwoWordsJoinedByAHyphenIsMatchedWhole() {
        String[] theirs = {"blocked-dependency", "behavior-change", "off-target", "done"};

        assertEquals("blocked-dependency",
                Reply.word("blocked-dependency: the parent pom is not published", theirs));
        assertEquals("behavior-change",
                Reply.word("behavior-change: the serialiser now emits nulls", theirs));
        assertEquals("off-target", Reply.word("- off-target: it fixed something else", theirs));
        assertEquals("done", Reply.word("done: all green", theirs),
                "and a one-word verdict in the same vocabulary still reads");
    }

    /** The other half of the same rule, or the fix would let a prefix answer for the whole. */
    @Test
    void aShorterVerdictDoesNotSwallowALongerOneThatBeginsWithIt() {
        assertEquals("", Reply.word("blocked-dependency: the parent pom is missing", "blocked"),
                "a line saying blocked-dependency has not said blocked");
        assertEquals("blocked", Reply.word("blocked: the parent pom is missing", "blocked"),
                "and the shorter word still reads when it is what was actually written");
    }

    @Test
    void theTriadReadsNothingAsAgain() throws Exception {
        // THE HALF THAT MUST NOT CHANGE, asserted through the triad rather than through the parser,
        // because it is the triad that decides what silence costs. A verifier that says nothing has
        // not approved: an empty answer from a critic reads as "nothing is wrong", which is the most
        // expensive thing it could say by mistake.
        int[] attempts = {0};
        Agent stage = Flow.triad("stage",
                brief -> "the plan",
                (plan, feedback) -> {
                    attempts[0]++;
                    return "an answer";
                },
                judged -> "I have no opinion about this",
                () -> "",
                Trace.quiet(), "key", 3);

        stage.run("go");

        assertEquals(3, attempts[0],
                "a verifier that names no verdict has not approved, so the doer runs again until "
                        + "the budget is spent rather than settling on the first round");
    }
}
