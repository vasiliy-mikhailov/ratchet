package tech.mikhailov.ratchet.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tech.mikhailov.ratchet.record.Journal;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A STAGE HANDS BACK WHAT ITS DOER DID, AND SHOWS WHAT ITS DOER CONTAINS. The plan and the verdict
 * never leave a stage as its answer; they leave it as one line in the record.
 *
 * <p>{@link TheVerifierHoldsTheLoopTest} pins the control flow — who is asked, how often, and what
 * each verdict costs — and it ignores the return value of every {@code run} it makes. A mutation
 * run said so: ten mutants of the triad lived, and the loudest of them replace the answer a stage
 * returns with the empty string on BOTH exits, the settled one and the spent one. Nothing went red.
 * That is the whole answer of the stage: the string a sequence passes on as its own last word, and
 * the string {@link Flow#resumable} writes into the journal as finished. A stage that had done
 * real work and returned nothing would be journaled as having answered nothing, and every resume
 * after it would replay that emptiness rather than the work, for the life of the file.
 *
 * <p>THE REQUIREMENT NOBODY HAD WRITTEN DOWN IS THE SECOND EXIT. Settling and running out of turns
 * return the same thing — the last attempt — and only the record tells them apart. A budget that
 * discarded the work when it expired would make the loop's last round the most expensive way to
 * lose it.
 *
 * <p>THE PICTURE OBEYS THE SAME RULE, and it had no test at all: {@link Flow#shape} and
 * {@link Flow#names} both walk unnamed nodes without drawing them, and both rules — do not print a
 * nameless node, do not indent under one — survived being deleted. A {@link Flow.Block} is exactly
 * such a node, and it is not a corner case: it is how a stage whose work is a whole sub-tree gets
 * drawn under the stage that runs it. Delete the rule and the walk under a stage either vanishes,
 * gains a blank line at a level nobody can point at, or drifts one level right and hangs under the
 * wrong parent — which is the class of untruth the {@link Shape} javadoc says this design exists to
 * make impossible.
 *
 * <p>Namelessness is also why a {@link Flow.Block} cannot be journaled: the journal keys a resume
 * on the node's name, so an unnamed node would share one row with every other unnamed node.
 */
class TheDoerIsWhatAStageHandsBackAndAllItShowsTest {

    @Test
    void whatAStageHandsBackIsWhatItsDoerDidRatherThanTheVerdict() throws IOException {
        Stage stage = new Stage("done");

        String answer = stage.triad(3).run("raise the pins of core");

        assertEquals("raised the pin, attempt 1", answer,
                "the caller is handed the work; the plan and the verdict are the stage's business");
    }

    @Test
    void aSpentBudgetHandsBackTheLastAttemptRatherThanNothing() throws IOException {
        Stage stage = new Stage("again: still 2.7.2", "again: still 2.7.2", "again: still 2.7.2");

        String answer = stage.triad(3).run("raise the pins of core");

        assertEquals("raised the pin, attempt 3", answer,
                "three rounds of work are still three rounds of work when the budget expires");
        assertFalse(stage.notes.stream().anyMatch(note -> note.contains("settled")),
                "and the record must not claim a verdict nobody reached: " + stage.notes);
    }

    @Test
    void aStageWhoseDoerIsAWholeSubTreeHandsBackWhatTheSubTreeSaid() throws IOException {
        // The answer has to travel up through the Block, which is the join between the two halves
        // of this file: the Block is the doer, so its body's last word is the stage's word, and the
        // Block has no name, so the picture shows the body directly under the stage.
        Flow.Node body = Flow.seq("module",
                Flow.code("bump", task -> "raised two pins"),
                Flow.code("build", task -> "the build is green"));

        Flow.Node stage = Flow.triad("raise-pins", brief -> "PLAN", block(body), brief -> "done",
                () -> "the pin reads 2.7.3", Trace.quiet(), "core", 1);

        assertEquals("the build is green", stage.run("raise the pins of core"),
                "a sub-chain's last word is the stage's word, unchanged on the way out");
    }

    @Test
    void theRecordSaysHowManyRoundsAStageTook() throws IOException {
        Stage straightThrough = new Stage("done");
        Stage fought = new Stage("again: still 2.7.2", "again: still 2.7.2", "done");

        straightThrough.triad(3).run("raise the pins of core");
        fought.triad(3).run("raise the pins of core");

        // The number is the only thing that tells a stage that went straight through from one that
        // spent two extra model calls arguing, and it is what makes a budget worth setting.
        assertEquals(List.of("raise-pins: settled after 1 round"), straightThrough.notes,
                "a stage that settles says so, once, and says it took one round");
        assertEquals("raise-pins: settled after 3 rounds",
                fought.notes.get(fought.notes.size() - 1),
                "and three rounds is rounds, plural: " + fought.notes);
    }

    @Test
    void silenceIsRecordedAsTheWordItWasReadAs() throws IOException {
        // verdictOf reads a blank reply as `again` rather than as agreement, and the note has to
        // carry that word, because the reader of a run is being told which of the three verdicts
        // the harness ACTED ON. A note that named no verdict would leave a round in the log that
        // nobody can account for. It happened 64 times in 1,544 verifier calls in one corpus.
        Stage stage = new Stage("", "done");

        stage.triad(3).run("raise the pins of core");

        assertTrue(stage.notes.get(0).startsWith("raise-pins: again "),
                "silence is logged as the verdict it became: " + stage.notes.get(0));
        assertTrue(stage.notes.get(0).contains("answered nothing"),
                "and as the reason it became it, so it is not confused with an objection");
    }

    @Test
    void aVerifierThatAnswersNullIsSilenceRatherThanACrash() throws IOException {
        // AN AGENT IS A LAMBDA AND A LAMBDA CAN RETURN NULL: a client that hands back the content
        // of a response returns null the day a response has no content. The production code says
        // this is expected — verdictOf and because BOTH test for it — and no test had ever passed
        // one. Deleting either guard survived. What it would cost is the run: a
        // NullPointerException thrown out of a stage mid-loop, and everything not yet journaled
        // goes with it, in exchange for a reply that was merely empty.
        Stage stage = new Stage(null, "done");

        stage.triad(3).run("raise the pins of core");

        assertEquals(2, stage.attempts, "null is silence, and silence sends the work round again");
        assertTrue(stage.notes.get(0).contains("answered nothing"),
                "recorded as the same nothing a blank reply is: " + stage.notes.get(0));
    }

    @Test
    void aStageShowsWhatItsDoerContainsAndNothingElse() {
        // Read the two triads in it. `survey` has a planner and a verifier and draws as a leaf,
        // because three lines saying every stage plans, does and verifies is not a picture.
        // `raise-pins` hands its work to a Block, and the Block itself is not on the page: it has
        // no name, so it neither draws a line of its own nor indents what it holds. The walk it
        // holds hangs directly under the stage, which is where a reader would point.
        String expected = """
                sweep
                    survey
                    raise-pins
                        modules
                            module
                                bump
                                gate
                                    build
                                    repair
                    release
                        tag
                """;

        assertEquals(expected, Flow.shape(sweep()),
                "the page is the program, printed; nothing here is drawn by hand");
    }

    @Test
    void everyNodeAPageCanPointAtHasAName() {
        // The Block in this tree is the nameless one, and it is absent from this list rather than
        // present as "". An empty entry here is a heading a reader cannot click, and it is the
        // journal's node column, so it is also a row shared with every other nameless node.
        assertEquals(List.of("sweep", "survey", "raise-pins", "modules", "module", "bump", "gate",
                        "build", "repair", "release", "tag"), Flow.names(sweep()),
                "the named nodes, in the order the program reaches them, and only those");
    }

    @Test
    void aNodeWithNoNameCannotBeJournaled(@TempDir Path dir) {
        Journal journal = new Journal(dir.resolve("journal.jsonl"));

        // The same namelessness that makes a Block invisible in the picture makes it unjournalable:
        // the name IS the journal's node column, so wrapping one would file every stage of every
        // walk under a single empty node, and the first resume would replay one of them as all of
        // them. Refusing is the only honest answer available at this point.
        assertThrows(IllegalArgumentException.class,
                () -> Flow.resumable(block(Flow.code("bump", task -> "raised two pins")), journal,
                        () -> "core"),
                "an unnamed node is refused rather than journaled under a name it does not have");
    }

    /**
     * A run with one of each combinator in it, so that the picture is walked over a tree with the
     * two shapes that make drawing hard: a stage whose doer is a lambda and draws as a leaf, and a
     * stage whose doer is a whole sub-tree behind a nameless {@link Flow.Block}.
     */
    private static Flow.Node sweep() {
        Flow.Node gate = Flow.loop("gate", 3, () -> true,
                Flow.code("build", task -> "red"),
                Flow.code("repair", task -> "patched the wall"));
        Flow.Node module = Flow.seq("module", Flow.code("bump", task -> "raised two pins"), gate);
        Flow.Node modules = Flow.each("modules", () -> List.of("core", "web"), item -> item,
                item -> module);

        Flow.Node survey = Flow.triad("survey", brief -> "PLAN",
                (plan, feedback) -> "the subject is on 8", brief -> "done",
                () -> "the pin reads 2.7.3", Trace.quiet(), "core", 1);
        Flow.Node raisePins = Flow.triad("raise-pins", brief -> "PLAN", block(modules),
                brief -> "done", () -> "the pin reads 2.7.3", Trace.quiet(), "core", 1);
        Flow.Node release = Flow.when("release", () -> true,
                Flow.code("tag", task -> "tagged the tree"));

        return Flow.seq("sweep", survey, raisePins, release);
    }

    /** A sub-tree standing as a doer, which places the plan itself: here, as the brief. */
    private static Flow.Block block(Agent body) {
        return new Flow.Block(body) {
            @Override
            public String run(String plan, String feedback) throws IOException {
                return run(plan);
            }
        };
    }

    /**
     * A stage whose three agents are scripted, so what it hands back and what it writes down are
     * the only variables. The doer numbers its answers, because the requirement is about WHICH of
     * them comes back.
     */
    private static final class Stage {

        private final List<String> verdicts;
        private final List<String> notes = new ArrayList<>();
        private int asked;
        private int attempts;

        Stage(String... verdicts) {
            this.verdicts = Arrays.asList(verdicts);
        }

        Flow.Node triad(int rounds) {
            Flow.Doer doer = (plan, feedback) -> {
                attempts++;
                return "raised the pin, attempt " + attempts;
            };
            Agent verifier = brief -> asked < verdicts.size() ? verdicts.get(asked++) : "done";
            return Flow.triad("raise-pins", brief -> "PLAN", doer, verifier,
                    () -> "the pin reads 2.7.3", new Notes(notes), "core", rounds);
        }
    }

    /** A trace that keeps only the progress lines, which are the loop's own account of itself. */
    private record Notes(List<String> notes) implements Trace {

        public void progress(String key, String note) {
            notes.add(note);
        }

        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String agent, String f, String t, String c) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String b, String s, String w, boolean x, boolean y) {
        }

        public void failed(String b, Throwable c) {
        }

        public void priced(String b, String m, String i) {
        }
    }
}
