package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.ToolWatching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT A CONSUMER LEAVES OUT IS ANSWERED WITH A DEFINITE VALUE, AND WHAT IT GETS WRONG IS REFUSED
 * BEFORE EITHER TOOL IS OFFERED TO A MODEL.
 *
 * <p>EVERY ARGUMENT THIS CLASS IS BUILT FROM COMES FROM OUTSIDE THIS REPOSITORY. {@code grep -rn
 * "new Asking(" --include='*.java'} over the whole tree finds nothing outside {@code src/test}:
 * the model, the prompt, the tool map, the label and the listener belong to a consumer, and so do
 * the {@link Calling} bodies underneath the tools. That is the reason every conditional in the
 * constructor and both in {@code Asking.ran} were still alive after the mutation run. Nothing in
 * here builds an agent the way a consumer does, so nothing in here had ever passed no prompt, no
 * label, no tool map at all, or two tools answering to one name — and a guard nothing reaches is a
 * guard nobody has read since it was written.
 *
 * <p>THE DUPLICATE NAME WAS DOCUMENTED AND NEVER ASSERTED. {@link Tool}'s javadoc states the
 * contract in prose — <em>"a duplicate name is two entries and {@link Asking} refuses it by name at
 * construction rather than losing one quietly"</em> — and {@code grep -rn "two tools are called"}
 * over the test tree returned nothing before this file. What it replaces did lose one quietly: the
 * client keyed executors by name while advertising the specifications as a list, so the last writer
 * won, both were still offered to the model, and every call of the losing tool ran the winner's
 * body. That failure answers promptly and looks like success, which is the worst shape a bug can
 * take on a conversation the model then reasons from.
 *
 * <p>AN EMPTY RESULT IS PAID FOR IN ROUNDS. When a tool throws with no message the type is the only
 * thing left to say; handing back "" instead describes a tool that succeeded and had nothing to
 * report, which is a different fact, and the model's answer to it is to call again. Rounds are the
 * one budget this loop counts — twenty-five of them, and that bound already fires on 59 of 208 runs
 * in one results tree, costing a whole second conversation each time.
 *
 * <p>THE NULL RESULT IS ONLY VISIBLE FROM THE LISTENER, so that is where it is asserted. {@link
 * Said} normalises a null text to "" on the way into the conversation, so the model cannot tell
 * that guard from its absence; the watcher can, and what it would see is not an empty string but a
 * NullPointerException thrown out of {@code shortened} — from the code that was only watching,
 * ending a run that had already done its work. {@link ToolWatching} promises a result that is
 * "never null", and one line keeps that promise.
 *
 * <p>EIGHT THOUSAND, AND THE EXISTING TEST ASKS AT NINE. {@code
 * TheToolLoopStopsAtTwentyFiveRoundsTest} shortens a 9,000-character result, which truncates on
 * both sides of the boundary: {@code <=} and {@code <} cannot be told apart by it, and the report
 * said so — "changed conditional boundary", survived. A bound tested only far from its edge is a
 * bound whose edge is a guess, so the two tests below stand one character apart.
 *
 * <p>No model is reached and no socket is opened. A {@link Chat} is one method, so the stand-in is
 * a function from the {@link Ask} to the {@link Reply} a test needs.
 */
class WhatAConsumerLeavesOutIsAnsweredAndWhatItGetsWrongIsRefusedTest {

    @Test
    void anAgentGivenNoToolMapAtAllIsAnOrdinaryAgent() {
        // "An agent with nothing to call is an ordinary agent" -- Ask's own javadoc, about the
        // empty tool list. Null is the shape a consumer with no tools writes, and the guard for it
        // is a line nothing in this repository has ever reached: the mutant that runs the wiring
        // loop unconditionally survived, and under it this constructor throws NullPointerException
        // at a consumer who did nothing wrong.
        Scripted model = new Scripted(ask -> saying("done"));

        String answer = new Asking(model, "you are a test", null, "agent:test", null).run("go");

        assertEquals("done", answer, "no tools is a configuration, not a failure");
        assertEquals(List.of(), model.seen.get(0).tools(),
                "and the request carries an empty tool list, which is a shape the endpoint knows");
    }

    @Test
    void anAgentGivenNoLabelIsCalledAgentOnEveryQuestionItAsks() {
        // ratchet#10 is about this field: who is asking travels WITH the question, and every
        // exchange row in the record is attributed to it. A consumer that passes none gets the word
        // "agent" -- one name for the unnamed, which a reader can at least count -- rather than a
        // null travelling into Ask and out into the record.
        Scripted model = new Scripted(ask -> saying("done"));

        new Asking(model, "you are a test", Map.of(), null, null).run("go");

        assertEquals("agent", model.seen.get(0).from(),
                "an agent nobody named still says who is asking");
    }

    @Test
    void anAgentGivenABlankLabelIsCalledAgentToTheWatcherToo() {
        // A LABEL READ OUT OF A CONFIGURATION ARRIVES BLANK RATHER THAN NULL: an empty value, a
        // line with a trailing space, a template that expanded to nothing. Both readers of the
        // field are pinned -- the question above, the watcher here -- because the label is handed
        // to two places and a repair to one of them would look complete from the other.
        List<Watched> watched = new ArrayList<>();
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("ping"))
                : saying("done"));

        new Asking(model, "you are a test", one("ping", call -> "pong"), "   ", watching(watched))
                .run("go");

        assertEquals("agent", model.seen.get(0).from(), "the question says who asked it");
        assertEquals("agent", watched.get(0).context(), "and the watcher is told the same name");
    }

    @Test
    void twoToolsAnsweringToOneNameAreRefusedBeforeTheAgentExists() {
        // THE COLLISION THIS REPLACES WAS SILENT AND ENTIRELY PLAUSIBLE: two builders each
        // contribute a "read", one wins the executor map, and thereafter every call of the loser
        // runs the winner's body while the model is offered both descriptions and chooses between
        // them. The map is keyed by the whole Tool record, so two entries differing anywhere are
        // two entries -- which is what leaves the name check at construction as the only place this
        // can be caught at all.
        Map<Tool, Calling> both = new LinkedHashMap<>();
        both.put(new Tool("read", "read a file", "{\"type\":\"object\",\"properties\":{}}"),
                call -> "the first body");
        both.put(new Tool("read", "read a file, with line numbers",
                "{\"type\":\"object\",\"properties\":{}}"), call -> "the second body");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Asking(new Scripted(ask -> saying("done")), "you are a test", both,
                        "agent:test", null));

        assertTrue(refused.getMessage().contains("read"),
                "the name that collided is named, because that is the thing a consumer has to "
                        + "change: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("only one could ever run"),
                "and it says why offering both is the part that misleads the model: "
                        + refused.getMessage());
    }

    @Test
    void twoToolsWithDifferentNamesAreBothWiredInTheOrderTheyWereDeclared() {
        // THE OTHER SIDE OF THE REFUSAL, and the expensive one to get wrong: a guard that fires
        // early would reject every agent holding more than one tool, at construction, before
        // anything could run. The declared order is asserted alongside it because that is what the
        // LinkedHashMap is for -- the order tools are advertised in should be the order somebody
        // wrote them down, not an accident of hashing.
        Map<Tool, Calling> two = new LinkedHashMap<>();
        two.put(spec("glob"), call -> "no files match");
        two.put(spec("read"), call -> "the file, read");
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("read"))
                : saying("done"));

        String answer = new Asking(model, "you are a test", two, "agent:test", null).run("go");

        assertEquals("done", answer, "two names is an ordinary agent");
        assertEquals(List.of(spec("glob"), spec("read")), model.seen.get(0).tools(),
                "both are offered, in the order they were declared");
        assertEquals("the file, read", model.seen.get(1).messages().get(3).text(),
                "and the second name runs its own body rather than the first one's");
    }

    @Test
    void aToolThatFailsWithNoMessageIsReportedToTheModelByItsType() {
        // THE TYPE IS THE MESSAGE WHEN THERE IS NO MESSAGE. NoSuchElementException is what
        // Iterator.next and Deque.removeFirst throw off the end of a collection and neither
        // bothers to say more, so the class name is the whole of what is known. Handing the model
        // "" instead reports a tool that ran and had nothing to say, and a model told that calls
        // again -- which spends the one budget this loop counts.
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("ping"))
                : saying("done"));

        String answer = new Asking(model, "you are a test", one("ping", call -> {
            throw new NoSuchElementException();
        }), "agent:test", null).run("go");

        assertEquals("done", answer, "a tool that exists and threw is answered, not propagated");
        Said result = model.seen.get(1).messages().get(3);
        assertEquals(Said.Role.TOOL, result.role(),
                "as that call's result and not as anything else");
        assertEquals("NoSuchElementException", result.text(),
                "with no message to pass on, the type is what the model is told");
    }

    @Test
    void aToolThatAnswersNullIsWatchedAsAnEmptyAnswerRatherThanKillingTheRun() {
        // A Calling IS CONSUMER CODE, and returning null where there was nothing to say is ordinary
        // Java. The model cannot tell whether this guard is present -- Said normalises a null text
        // to "" on the way into the conversation, which is exactly why the mutation survived -- but
        // the watcher can: without it shortened() dereferences the null, and a NullPointerException
        // comes out of the code that was only watching, ending a run that had already done its
        // work. ToolWatching promises a result "never null"; this is where that is kept.
        List<Watched> watched = new ArrayList<>();
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("ping"))
                : saying("done"));

        String answer = new Asking(model, "you are a test", one("ping", call -> null),
                "agent:test", watching(watched)).run("go");

        assertEquals("done", answer, "the run survived being watched");
        assertEquals("", watched.get(0).result(),
                "a listener is promised a result and is given one");
        assertEquals("", model.seen.get(1).messages().get(3).text(),
                "and the model is told an empty result rather than carrying a null onward");
    }

    @Test
    void aResultOfExactlyEightThousandCharactersIsShownWhole() {
        // THE EDGE ITSELF. Eight thousand characters is what a listener may be shown, so a result
        // of exactly eight thousand is shown all of them: the bound is inclusive, and the
        // difference between that and the alternative is one character of a result and a truncation
        // notice on a payload that was never truncated.
        String exactly = "x".repeat(8_000);
        List<Watched> watched = new ArrayList<>();
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("ping"))
                : saying("done"));

        new Asking(model, "you are a test", one("ping", call -> exactly), "agent:test",
                watching(watched)).run("go");

        assertEquals(8_000, watched.get(0).result().length(),
                "the last result that fits is shown at its own length, with nothing appended");
        assertEquals(exactly, watched.get(0).result(), "and unchanged, to the character");
    }

    @Test
    void aResultOneCharacterOverSaysHowMuchThereWasAltogether() {
        // ONE CHARACTER FURTHER, and the notice carries the total rather than the fact of
        // truncation: a watcher looking at eight thousand characters needs to know whether that is
        // most of the answer or a sliver of a generated file, and only the number says which.
        String overBy = "y".repeat(8_001);
        List<Watched> watched = new ArrayList<>();
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("ping"))
                : saying("done"));

        new Asking(model, "you are a test", one("ping", call -> overBy), "agent:test",
                watching(watched)).run("go");

        assertEquals("y".repeat(8_000) + "... (truncated, total 8001 chars)",
                watched.get(0).result(),
                "the first eight thousand characters, then the size of the whole thing");
    }

    @Test
    void theArgumentsGoWholeToTheToolAndShortenedToTheWatcher() {
        // WHOLE INTO THE TOOL, BOUNDED INTO THE WATCHER -- the same split Recording makes one layer
        // down, for the same reason. What a tool was asked to do IS the work, so the tool gets
        // every character the model wrote; a watcher is for watching, and an edit call whose
        // arguments carry a whole file would fill the log with a second copy of the record.
        String huge = "y".repeat(8_001);
        List<String> asAsked = new ArrayList<>();
        List<Watched> watched = new ArrayList<>();
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(new Called("t1", "ping", huge))
                : saying("done"));

        new Asking(model, "you are a test", one("ping", call -> {
            asAsked.add(call.arguments());
            return "pong";
        }), "agent:test", watching(watched)).run("go");

        assertEquals(8_001, asAsked.get(0).length(),
                "the tool is handed what the model wrote, whole");
        assertEquals("y".repeat(8_000) + "... (truncated, total 8001 chars)",
                watched.get(0).arguments(),
                "and the watcher is handed as much of it as a watcher is for");
    }

    @Test
    void aConsumerThatPassesNoSystemPromptSendsAnEmptySystemTurn() {
        // ASSERTED AT THE BOUNDARY BECAUSE THAT IS WHERE IT IS OBSERVABLE, and the reason is worth
        // writing down: the constructor's own null check cannot be seen from outside, since Said
        // normalises a null text the same way on the way in. The mutation that removes it is
        // genuinely equivalent -- two guards, one behaviour -- so this pins the behaviour rather
        // than either guard. It stays green while either one exists and goes red when both go,
        // which is the only failure a consumer would ever feel.
        Scripted model = new Scripted(ask -> saying("done"));

        new Asking(model, null, Map.of(), "agent:test", null).run("go");

        List<Said> sent = model.seen.get(0).messages();
        assertEquals(2, sent.size(), "the system turn is sent even with nothing in it");
        assertEquals(Said.Role.SYSTEM, sent.get(0).role(), "and it is still the system turn");
        assertEquals("", sent.get(0).text(),
                "an empty prompt is what an absent one becomes, all the way to the request");
    }

    // ---- the stand-ins ----

    /** One tool of a given name, doing whatever the test in hand needs it to do. */
    private static Map<Tool, Calling> one(String name, Calling doing) {
        Map<Tool, Calling> tools = new LinkedHashMap<>();
        tools.put(spec(name), doing);
        return tools;
    }

    private static Tool spec(String name) {
        return Tool.of(name, "a tool that exists so the loop has something to turn on");
    }

    /** One call of a tool by name, with arguments the tests below do not care about. */
    private static Called called(String name) {
        return new Called("t1", name, "{}");
    }

    /** A turn that ends asking for tools, which is what keeps the loop going round. */
    private static Reply wanting(Called... calls) {
        return new Reply("", "", List.of(calls), Ending.TOOLS, Spend.NONE);
    }

    /** A turn that answers and stops. */
    private static Reply saying(String text) {
        return new Reply(text, "", List.of(), Ending.STOPPED, Spend.NONE);
    }

    /** Exactly what a listener was shown, kept so a test can assert the strings themselves. */
    private record Watched(String context, String tool, Object memoryId, String arguments,
                           String result) {
    }

    private static ToolWatching watching(List<Watched> into) {
        return (context, tool, memoryId, arguments, result) ->
                into.add(new Watched(context, tool, memoryId, arguments, result));
    }

    /**
     * A model that answers from a function of the ask and keeps every ask it was given.
     *
     * <p>The whole {@link Ask} is kept rather than a copy of its parts: the record copies both
     * lists on the way in, so holding it holds the conversation as it stood at that call rather
     * than a view of the list the loop goes on appending to.
     */
    private static final class Scripted implements Chat {

        private final Function<Ask, Reply> answering;
        private final List<Ask> seen = new ArrayList<>();

        private Scripted(Function<Ask, Reply> answering) {
            this.answering = answering;
        }

        @Override
        public Reply answer(Ask ask) {
            seen.add(ask);
            return answering.apply(ask);
        }
    }
}
