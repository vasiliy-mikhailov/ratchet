package tech.mikhailov.ratchet.flow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A VERDICT IS THE WORD A VERIFIER MEANT, NOT THE FIRST PLACE ITS LETTERS APPEAR.
 *
 * <p>One word is the entire control flow a model has here: it decides whether a stage settles, runs
 * again against the same plan, or goes back to the planner. Reading it with {@code indexOf} over
 * the lowercased reply cost three collisions, and all three are ordinary English rather than
 * adversarial input — {@code done} inside "not done", {@code again} inside "against", {@code sound}
 * inside "unsound", which is how a security critic's rejection was read as approval.
 *
 * <p>The two ways of being wrong do not cost the same. A false {@code again} spends one more round
 * of a budget that exists to be spent. A false {@code done} closes the stage, and the objection is
 * never acted on by anyone. {@code done} was both the earliest-colliding word and the fallback, so
 * the approving verdict was the easiest one to trigger by accident, which is backwards for a
 * construction whose whole point is that a reviewer can stop the work.
 *
 * <p>Three rules that nobody had written down anywhere turned up while writing these tests, and
 * each has a test that says so: {@link #silenceIsAnsweredWithWhicheverWordTheCallerPutFirst()},
 * {@link #aReplyThatNamesNoVerdictAtAllIsReadAsApproval()} and
 * {@link #aNegationCancelsTheWordItTouchesAndNotTheRestOfTheSentence()}.
 */
class AVerdictIsTheWordTheVerifierMeantTest {

    /**
     * The same words in the same order that the triad from {@link Flow#triad} hands to
     * {@link Reply#word}. The order is load-bearing rather than decorative: the first word is also
     * what an unreadable reply falls back to.
     */
    private static final String[] VERDICTS = {"done", "again", "replan"};

    /**
     * A verifier writes its verdict on a line of its own, and it decorates and punctuates that line
     * however its training left it. These thirteen forms are read by seven separate clauses of one
     * condition, so any one of them could stop working on its own without the others noticing;
     * every clause has at least one form here that only it answers.
     *
     * <p>The prose above the verdict says {@code done} on purpose: it is what the word-boundary
     * scan would settle on if the line rule stopped firing, which is what makes this a test of the
     * line rule rather than of the fallback. A verifier that reports what IS finished before saying
     * the work is not must not be read as having approved it.
     *
     * <p>FOUND HERE, NOT FIXED HERE: {@code **again**} is not in this list because it does not
     * work. The decoration is stripped from the front of the line only, so a bolded verdict — the
     * house style of every chat-tuned model — leaves {@code again**}, matches no clause, falls
     * through to the word scan, and reads as {@code done} off the prose above it. Confirmed by
     * running it, not assumed: {@code **again**}, {@code `again`} and {@code again?} all return
     * {@code done} for exactly the reply below. That is the expensive direction of the failure,
     * from the commonest formatting there is, and it is why the clause list is the wrong shape:
     * seven spellings of "the word, then something that is not a letter" cannot enumerate the
     * punctuation a model will actually use, and they say nothing at all about the tail.
     */
    @Test
    void theLineTheVerifierStartsWithIsTheVerdictHoweverItPunctuatedIt() {
        String reported = "The edit is done in the child pom, but the parent still reads 2.7.3.\n";
        List<String> forms = List.of(
                "again",
                "again:",
                "again: the parent block still reads 2.7.3",
                "again the parent block still reads 2.7.3",
                "again.",
                "again, the parent block still reads 2.7.3",
                "again; fix the parent first",
                "again!",
                "AGAIN: the parent block still reads 2.7.3",
                "  again  ",
                "- again: fix the parent first",
                "> again",
                "#### again");

        for (String form : forms) {
            assertEquals("again", Reply.word(reported + form, VERDICTS),
                    "a verdict line ends the reading, whatever it wears: <" + form + ">");
        }
    }

    /**
     * The rule that cost the most. A verifier that opens by denying completion scored {@code done}
     * on the denial itself, so the stage closed on the sentence that said it had not.
     */
    @Test
    void aVerifierThatDeniesCompletionHasNotApprovedIt() {
        String judgement = "not done — the parent block still reads 2.7.3, so run it again.";

        assertEquals("again", Reply.word(judgement, VERDICTS),
                "the words just before a match can turn it into its opposite, and here they do");
    }

    /**
     * A REQUIREMENT NOBODY WROTE DOWN, AND THE ONE THING HOLDING THE NEGATION RULE TOGETHER: a
     * negation cancels the word it is touching and no other word in the sentence.
     *
     * <p>The look-back window is 24 characters, which sounds like the rule, but it is not: the
     * pattern is anchored to the end of that window, so only whitespace and punctuation may sit
     * between the {@code not} and the match. Take the anchor away and leave the window and the
     * sentence below reads as neither verdict — the {@code not} that correctly kills the
     * {@code done} four characters after it still lies twenty characters before the {@code again}
     * the verifier actually asked for, so it kills that too, no word is left standing, and the
     * reply falls back to {@code done}. A denial would settle the stage it denies, which is the
     * exact failure this class was written to end.
     *
     * <p>Nothing pinned that anchor before this test. PIT does not mutate regular expressions, so
     * the mutation score could not have found it either: the window constant and the {@code $}
     * are invisible to it, and only the {@code $} is load-bearing. (Narrowing the window IS
     * caught, by {@link #aVerifierThatDeniesCompletionHasNotApprovedIt()} — any window under four
     * characters stops "not done" being a denial. Widening it is close to unobservable, because
     * the anchor, not the distance, is what does the work.)
     */
    @Test
    void aNegationCancelsTheWordItTouchesAndNotTheRestOfTheSentence() {
        String judgement = "This is not done, so run it again.";

        assertEquals("again", Reply.word(judgement, VERDICTS),
                "the 'not' denies the completion, not the retry the same sentence asks for");
    }

    /**
     * "Against" is what a reviewer writes when it is arguing, which is most of the time. Read as
     * {@code again} it outranks the real verdict, because it is earlier in the sentence than the
     * conclusion the sentence is building towards.
     */
    @Test
    void againstIsNotTheVerdictAgain() {
        String judgement = "My objection is against the plan itself, so we should replan the stage.";

        assertEquals("replan", Reply.word(judgement, VERDICTS),
                "a whole-word match only: 'against' is a preposition, not an instruction to retry");
    }

    /**
     * The incident that shows this is not a {@code done}/{@code again}/{@code replan} parser: a
     * reviewer with its own vocabulary answered {@code unsound}, the reading took the first place
     * the letters {@code sound} appeared, and a rejection was recorded as approval. The approving
     * word is first in this list too, which is why the second assertion is here — the fix has to
     * tell the two apart rather than simply stop saying yes.
     */
    @Test
    void unsoundIsNotSound() {
        assertEquals("unsound",
                Reply.word("The token is written to the log, so the design is unsound.",
                        "sound", "unsound"),
                "the critic's rejection is the verdict, not the approval hiding inside its letters");
        assertEquals("sound",
                Reply.word("The token never leaves the process, so the design is sound.",
                        "sound", "unsound"),
                "and a genuine approval still reads as one");
    }

    /**
     * When no line is a verdict line, the earliest verdict in the prose wins — earliest in the
     * reply, not first in the caller's list. A verifier naming a fallback ("try again, and only
     * replan if that fails") has asked for the cheaper of the two, and reading it as the later word
     * throws away a plan nobody objected to and pays for a fresh planner call to replace it.
     */
    @Test
    void theEarliestVerdictInTheProseWinsRatherThanTheLastOneNamed() {
        String judgement = "Run it again; if the parent block still refuses, replan the stage.";

        assertEquals("again", Reply.word(judgement, VERDICTS),
                "the verifier's order, not the argument list's order");
    }

    /**
     * A REQUIREMENT NOBODY WROTE DOWN: silence is answered with whichever word the caller happened
     * to put first, and for the triad that word is {@code done}.
     *
     * <p>An empty reply is a live failure mode on a small local model, so this is not hypothetical.
     * The triad built by {@link Flow#triad} therefore tests for a blank judgement itself before it
     * ever calls here, and that guard is the only reason an empty request does not close a stage.
     * The rule is really "put the safe word first", it is enforced nowhere, and the one caller in
     * this module violates it and compensates at the call site. Nothing stops the next caller from
     * doing the first half and forgetting the second.
     */
    @Test
    void silenceIsAnsweredWithWhicheverWordTheCallerPutFirst() {
        assertEquals("done", Reply.word(null, VERDICTS),
                "a reply that never arrived is not a crash, it is the caller's first word");
        assertEquals("done", Reply.word("  \n \t \n ", VERDICTS),
                "and whitespace is the same as nothing");
        assertEquals("again", Reply.word("", "again", "done"),
                "the fallback is positional, which is the whole of the contract Flow relies on");
    }

    /**
     * A REQUIREMENT NOBODY WROTE DOWN, AND THE ONE THIS CLASS'S OWN ARGUMENT SAYS IS BACKWARDS: a
     * reviewer that writes a paragraph of prose and names none of the three words has its stage
     * settled.
     *
     * <p>The collision fixes closed the accidental routes to {@code done}; they left the deliberate
     * one. Silence is guarded at the call site, but a verifier that answers with hedged prose —
     * which is what a model does when it is unsure — is not silent, so nothing catches it, and the
     * verdict it did not give is read as the one that stops the work.
     *
     * <p>This test states today's behaviour rather than the behaviour anyone chose. It is here so
     * that a change to it is a decision somebody makes on purpose.
     */
    @Test
    void aReplyThatNamesNoVerdictAtAllIsReadAsApproval() {
        String judgement = "The change looks reasonable, though I have concerns about the parent.";

        assertEquals("done", Reply.word(judgement, VERDICTS),
                "the fallback is the caller's first word even when the reply was not empty");
    }
}
