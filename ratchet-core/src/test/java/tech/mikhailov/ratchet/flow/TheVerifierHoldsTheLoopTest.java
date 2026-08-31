package tech.mikhailov.ratchet.flow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE LOOP BELONGS TO THE VERIFIER, AND IT CHOOSES HOW FAR BACK TO SEND THE WORK.
 *
 * <p>Every stage used to loop its producer and ask its critic once, at the end. That put the two
 * jobs in the wrong hands: the agent that chose a plan decided whether to keep running it, and the
 * critic saw only the end state. One stage hid a real defect behind exactly that shape for as long
 * as it existed.
 */
class TheVerifierHoldsTheLoopTest {

    @Test
    void doneOnTheFirstRoundRunsTheDoerOnce() throws IOException {
        Script s = new Script(List.of("done"));

        s.triad(3).run("go");

        assertEquals(1, s.planned, "one plan");
        assertEquals(1, s.did.size(), "and one execution");
    }

    @Test
    void againKeepsThePlanAndTellsTheDoerWhy() throws IOException {
        Script s = new Script(List.of("again: the edit did not reach the property", "done"));

        s.triad(3).run("go");

        assertEquals(1, s.planned, "again does not replan: the plan was not the problem");
        assertEquals(2, s.did.size());
        assertTrue(s.did.get(0).feedback().isBlank(), "the first round has nothing to answer");
        assertTrue(s.did.get(1).feedback().contains("did not reach the property"),
                "the second is told what the objection was: " + s.did.get(1).feedback());
        assertEquals(s.did.get(0).plan(), s.did.get(1).plan(), "and works to the same plan");
    }

    @Test
    void replanGoesBackToThePlannerAndDropsTheObjectionToTheDoer() throws IOException {
        Script s = new Script(List.of("replan: that part does not declare it", "done"));

        s.triad(3).run("go");

        assertEquals(2, s.planned, "the plan itself was the objection");
        assertEquals(2, s.did.size());
        assertTrue(s.did.get(1).feedback().isBlank(),
                "a fresh plan is not also handed the old plan's complaint");
        assertTrue(s.replanBrief.contains("that part does not declare it"),
                "but the planner is: " + s.replanBrief);
    }

    @Test
    void theTwoFailurePathsAreNotTheSame() throws IOException {
        // Collapsing them is how a loop spends its whole budget re-running a plan that was wrong
        // from the first round.
        Script again = new Script(List.of("again: x", "again: x", "again: x", "done"));
        Script replan = new Script(List.of("replan: x", "replan: x", "replan: x", "done"));

        again.triad(6).run("go");
        replan.triad(6).run("go");

        assertEquals(1, again.planned, "four rounds of `again` is one plan");
        assertEquals(4, replan.planned, "four rounds of `replan` is four");
        assertEquals(again.did.size(), replan.did.size(), "and the doer runs the same either way");
    }

    @Test
    void aBlankVerdictIsNotAgreement() throws IOException {
        // An empty reply is a live failure mode on a small local model. Reply.word falls back to
        // its first argument, so reading silence as `done` would close a stage because a request
        // came back empty, which is the one reading of silence that loses work.
        Script s = new Script(List.of("", "done"));

        s.triad(3).run("go");

        assertEquals(2, s.did.size(), "silence sent it round again rather than closing it");
    }

    @Test
    void aSpentBudgetStopsRatherThanRunningOn() throws IOException {
        Script s = new Script(List.of("again: x", "again: x", "again: x", "again: x", "again: x"));

        s.triad(2).run("go");

        assertEquals(2, s.did.size(), "two rounds means two");
        assertTrue(s.notes.stream().anyMatch(n -> n.contains("rounds spent")),
                "and the record says the budget ran out rather than that it settled: " + s.notes);
    }

    @Test
    void theVerifierReadsTheWorkspaceAndNotOnlyTheReport() throws IOException {
        // A report is an opinion. One corpus has a preparer answering NOTHING-TO-DO while its own
        // stage recorded edits, and a troubleshooter reporting a fix it had reverted a turn earlier.
        Script s = new Script(List.of("done"));

        s.triad(3).run("go");

        assertTrue(s.judged.get(0).contains("FACTS"),
                "the verifier is handed what the workspace says: " + s.judged.get(0));
        assertTrue(s.judged.get(0).contains("DID"), "alongside what was claimed");
    }

    /** A triad whose agents are scripted, so the control flow is the only thing under test. */
    @Test
    void theObjectionReachesTheProgressNoteEvenWhenTheReplyOpensWithABlankLine() throws IOException {
        // MEASURED, NOT HYPOTHETICAL. Of 1,544 verifier replies in one corpus, 1,468 began with a
        // blank line, and the note took lines().findFirst() literally, so 95 per cent of them
        // logged the stage and the verdict and then stopped, with the objection missing. The
        // objection was never lost to the doer, which is handed the whole judgement; what was lost
        // was the one line a person reads to work out why a run is still going.
        Script s = new Script(List.of("\n\nagain: the parent block still reads 2.7.3", "done"));

        s.triad(3).run("go");

        assertTrue(s.notes.stream().anyMatch(n -> n.contains("the parent block still reads 2.7.3")),
                "the note carries the objection: " + s.notes);
    }

    @Test
    void silenceIsNotLoggedAsThoughSomebodyHadObjected() throws IOException {
        // verdictOf reads a blank reply as `again`, deliberately: defaulting silence to `done`
        // would close a stage because a request came back empty. But then the note for a reviewer
        // who objected and the note for a reviewer who said nothing are the same sentence, and they
        // call for opposite responses from whoever is reading. It fired 64 times in 1,544 calls.
        Script s = new Script(List.of("", "done"));

        s.triad(3).run("go");

        assertTrue(s.notes.stream().anyMatch(n -> n.contains("answered nothing")),
                "silence says so: " + s.notes);
    }

    @Test
    void theWorkspaceIsReadAgainBeforeEveryVerdictAndNotOnceAtTheStart() throws Exception {
        // THE FOUNDING REQUIREMENT OF A TRIAD, AND NOTHING ASSERTED IT. Hoist `facts.read()` above
        // the round loop and all 144 tests in ratchet-core stay green — verified by doing it.
        //
        // A triad exists because a doer's REPORT is not evidence: the verifier is given what the
        // workspace says instead. Read once, that guarantee inverts after the first round. The doer
        // fixes what round one got wrong, the verifier is handed round one's workspace, and it
        // rejects work that is now correct — or accepts work that has since been broken. A stage
        // then spends its whole budget arguing about a snapshot nobody is looking at any more,
        // which is the failure mode this design was built to delete.
        //
        // Pinned with a workspace that CHANGES: the doer repairs it on the second round, and the
        // verifier settles only on what it can see.
        StringBuilder workspace = new StringBuilder("broken");
        int[] rounds = {0};

        String answer = Flow.triad("stage",
                brief -> "the plan",
                (plan, feedback) -> {
                    rounds[0]++;
                    if (rounds[0] == 2) {
                        workspace.setLength(0);
                        workspace.append("repaired");
                    }
                    return "I did round " + rounds[0];
                },
                judged -> judged.contains("repaired") ? "done" : "again: the workspace is broken",
                workspace::toString,
                Trace.quiet(), "key", 5).run("go");

        assertEquals(2, rounds[0],
                "the doer repaired the workspace on round two and the verifier could see it. Read "
                        + "once at the start, the verifier is handed 'broken' for ever and the "
                        + "stage burns its whole budget rejecting work that is already correct");
        assertEquals("I did round 2", answer, "and the stage hands back what settled it");
    }

    private static final class Script {

        record Run(String plan, String feedback) {
        }

        private final List<String> verdicts;
        private final List<Run> did = new ArrayList<>();
        private final List<String> judged = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private int planned;
        private int asked;
        private String replanBrief = "";

        Script(List<String> verdicts) {
            this.verdicts = verdicts;
        }

        Flow.Node triad(int rounds) {
            Agent planner = brief -> {
                planned++;
                if (planned > 1) {
                    replanBrief = brief;
                }
                return "PLAN-" + planned;
            };
            Flow.Doer doer = (plan, feedback) -> {
                did.add(new Run(plan, feedback));
                return "DID " + plan;
            };
            Agent verifier = brief -> {
                judged.add(brief);
                return asked < verdicts.size() ? verdicts.get(asked++) : "done";
            };
            return Flow.triad("test", planner, doer, verifier, () -> "FACTS", new Notes(notes),
                    "b", rounds);
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

        public void failed(String agent, String b, Throwable c) {
        }

        public void priced(String b, String m, String i) {
        }
    }
}
