package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Telling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT BOUNDS AN AGENT IS THE CALLER'S, so it is asserted rather than described.
 *
 * <p>{@link Asking} used to cap an agent at twenty-five rounds because the harness it replaced did.
 * That cap fired on 59 of 208 runs in one corpus, discarding the conversation and re-asking the
 * whole question, and it counted ROUNDS rather than calls — so twenty-five turns of an edit
 * returning two hundred characters and twenty-five of a grep returning megabytes were the same
 * number to it. The same task family finishes in a couple of hours against a loop with no round
 * bound at all, on the same model.
 *
 * <p>IT IS A {@link Budget} NOW, AND IT IS THE CALLER'S. This library cannot see the corpus, the
 * tools, the context window or whether anyone is watching, so it is not the thing that should be
 * deciding when work stops. What it ships is a runaway guard set far above real work, and a door.
 *
 * <p>No model is reached. A {@link Chat} is one method, so the stub below is a function from the
 * {@link Ask} to the {@link Reply} the test needs, which is the only way to ask a loop about its
 * own arithmetic. It keeps every conversation it was shown, because half of what this class pins is
 * what the loop SENDS rather than what it returns.
 */
class TheToolLoopIsBoundedByWhatTheCallerAllowsTest {

    @Test
    void aModelThatNeverStopsCallingToolsIsCutOffByWhatTheCallerAllowed() {
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(ask -> wanting(called("t")));

        Asking asking = new Asking(model, "you are a test", one("ping", calls), "agent:test", null,
                Telling.upTo(8_000), new Budget(0, 5));

        RuntimeException stopped = assertThrows(RuntimeException.class, () -> asking.run("go"));
        assertTrue(stopped.getMessage().contains("without finishing"),
                "it says what was spent rather than blaming a round count nobody chose: "
                        + stopped.getMessage());
        assertEquals(5, calls.get(), "and it stopped where the caller said, not where a jar this "
                + "library replaced happened to stop");
    }

    @Test
    void anAnswerArrivingInsideTheBoundComesBackWithTheToolsHavingRun() {
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(ask -> calls.get() < 3
                ? wanting(called("t"))
                : saying("done"));

        String answer = new Asking(model, "you are a test", one("ping", calls), "agent:test", null)
                .run("go");

        assertEquals("done", answer);
        assertEquals(3, calls.get());
    }

    @Test
    void anAnswerArrivingInsideTheBudgetIsReturned() {
        // WHICH SIDE OF THE BOUNDARY AN ANSWER FALLS ON IS SETTLED BY THE ORDER OF TWO LINES, and
        // that is still true even though the number is gone. The ceiling is now tested after the
        // tools have run and before another turn is paid for, so an answer that arrives is read
        // rather than bought and discarded — the waste the old bound was criticised for, where the
        // twenty-sixth response was fetched on the longest conversation of the run and thrown away.
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(ask -> calls.get() < 24
                ? wanting(called("t"))
                : saying("done"));

        String answer = new Asking(model, "you are a test", one("ping", calls), "agent:test", null)
                .run("go");

        assertEquals("done", answer, "the answer is what comes out, not an exception about a count");
        assertEquals(24, calls.get(), "twenty-four rounds of tools, and then the answer");
        assertEquals(25, model.seen.size(), "on the twenty-fifth call, which used to be the wall");
    }

    /**
     * THE BEHAVIOUR THIS RELEASE REMOVES. Round 26 used to be fetched and discarded: the answer
     * arrived, was paid for, and was thrown away in favour of an exception naming a count. That
     * fired on 59 of 208 runs in one corpus, each firing costing a second whole conversation.
     */
    @Test
    void anAnswerArrivingPastTheOldRoundCapIsNoLongerThrownAway() {
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(ask -> calls.get() < 40 ? wanting(called("t")) : saying("done"));

        String answer = new Asking(model, "you are a test", one("ping", calls), "agent:test", null)
                .run("go");

        assertEquals("done", answer, "forty rounds is ordinary work and used to be unreachable");
        assertEquals(40, calls.get());
    }

    /** The backstop is still per turn, so one turn asking for three tools costs one of it. */
    @Test
    void theBackstopCountsTurnsAndNotCalls() {
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(ask -> wanting(called("a"), called("b"), called("c")));

        assertThrows(RuntimeException.class,
                () -> new Asking(model, "you are a test", one("ping", calls), "agent:test", null,
                        Telling.upTo(8_000), new Budget(0, 4)).run("go"));

        assertEquals(12, calls.get(), "four turns of three calls each");
    }

    /**
     * TOKENS ARE WHAT IS ACTUALLY SPENT, which is the unit the round count never was: twenty-five
     * turns of an edit returning two hundred characters and twenty-five of a grep returning
     * megabytes were the same number to it.
     */
    @Test
    void aConsumerChoosesWhatAnAgentMaySpend() {
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(ask -> new Reply("", "", List.of(called("t")), Ending.TOOLS,
                new Spend(400, 100)));

        RuntimeException stopped = assertThrows(RuntimeException.class,
                () -> new Asking(model, "you are a test", one("ping", calls), "agent:test", null,
                        Telling.upTo(8_000), Budget.of(2_000)).run("go"));

        assertTrue(stopped.getMessage().contains("tokens"), stopped.getMessage());
        assertEquals(4, calls.get(), "five hundred a turn reaches two thousand on the fourth, and "
                + "the fifth is never paid for — no turn is bought and discarded");
    }

    /**
     * A SERVER THAT REPORTS NO USAGE CANNOT BE BOUNDED ON TOKENS, and Spend.NONE totals zero, which
     * is indistinguishable from a free turn. Without the turn backstop this is an unfalsifiable
     * check — the fourth this library would have shipped.
     */
    @Test
    void aServerThatReportsNoUsageIsStillBounded() {
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(ask -> wanting(called("t")));

        assertThrows(RuntimeException.class,
                () -> new Asking(model, "you are a test", one("ping", calls), "agent:test", null,
                        Telling.upTo(8_000), Budget.of(1_000_000)).run("go"),
                "a million-token budget never fires against Spend.NONE; the backstop does");
    }

    @Test
    void theConversationIsTheSystemPromptThenTheTaskAndNothingElse() {
        Scripted model = new Scripted(ask -> saying("done"));

        new Asking(model, "you are a test", Map.of(), "agent:test", null).run("the question");

        List<Said> sent = model.seen.get(0);
        assertEquals(2, sent.size(), "nothing is prepended and nothing is remembered");
        assertEquals(Said.Role.SYSTEM, sent.get(0).role());
        assertEquals("you are a test", sent.get(0).text());
        assertEquals(Said.Role.USER, sent.get(1).role());
        assertEquals("the question", sent.get(1).text());
    }

    @Test
    void aSecondQuestionStartsFromNothing() {
        // NO MEMORY IS CONFIGURED, deliberately: an agent asked twice in a run is asked twice from
        // nothing, and what it knows the second time is whatever its tools can tell it.
        Scripted model = new Scripted(ask -> saying("done"));
        Asking asking = new Asking(model, "you are a test", Map.of(), "agent:test", null);

        asking.run("first");
        asking.run("second");

        assertEquals(2, model.seen.get(1).size(), "the second conversation carries no history");
        assertEquals(Said.Role.USER, model.seen.get(1).get(1).role());
        assertEquals("second", model.seen.get(1).get(1).text());
    }

    @Test
    void whatAToolAnsweredIsWhatTheModelIsToldNextTurn() {
        Map<Tool, Calling> tools = new LinkedHashMap<>();
        tools.put(spec("ping"), call -> "the answer, verbatim");
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("t"))
                : saying("done"));

        new Asking(model, "you are a test", tools, "agent:test", null).run("go");

        // THE TOOL WAS ADVERTISED BEFORE IT COULD BE CALLED, which is worth one line here because
        // it is a job that moved. A client library used to build the tools field of the request out
        // of the specifications it was configured with; the loop in this library builds it, so a
        // regression that sent an empty list would leave every other assertion in this class green
        // and no model would ever ask for anything again.
        assertEquals(List.of(spec("ping")), model.offered.get(0),
                "the one tool this agent was given is the one tool the request carried");
        List<Said> second = model.seen.get(1);
        assertEquals(4, second.size(), "the assistant turn and one result per call are appended");
        assertEquals(Said.Role.TOOL, second.get(3).role());
        assertEquals("the answer, verbatim", second.get(3).text());
        // AGAINST THE CALL THAT ASKED FOR IT, which is now this library's job rather than a client
        // library's: a conversation that answers a call with the wrong id is one the model cannot
        // follow, and it is the single easiest thing to get wrong when writing this loop by hand.
        assertEquals("t", second.get(3).answering().id());
        // AND THE NAME, which 0.13.0 dropped. The id is what the server matches on and the name is
        // what a reader matches on, and keeping only the first turned the record into a column of
        // answers with no questions attached. ratchet#9, and see TheToolResultNamesItsToolTest.
        assertEquals("ping", second.get(3).answering().name());
    }

    @Test
    void aToolNameTheModelInventedIsNotAnsweredTheSameWayAsAToolThatFailed() {
        // THE OTHER HALF OF THE SAME PARAGRAPH, asserted rather than described, because the two
        // failures look alike from a prompt and are not alike at all: a tool that exists and goes
        // wrong is reported back to the model, and a tool that does not exist raises out of the
        // loop with the ask lost. That hallucination strategy used to belong to the client library
        // this replaces; Asking owns it now and behaves the same way on purpose. Whatever this
        // does, a phase that needs a tool writes it rather than assuming it.
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(new Called("t", "no_such_tool", "{}"))
                : saying("done"));

        RuntimeException raised = assertThrows(RuntimeException.class,
                () -> new Asking(model, "you are a test", one("ping", new AtomicInteger()),
                        "agent:test", null).run("go"));

        assertTrue(raised.getMessage().contains("no_such_tool"),
                "the invented name is named: " + raised.getMessage());
    }

    @Test
    void aToolThatThrowsIsAnsweredToTheModelRatherThanEndingTheRun() {
        // MEASURED, BECAUSE THIS CLASS'S OWN SUBJECT USED TO CLAIM THE OPPOSITE. The client library
        // this replaces caught what an executor threw and handed the model the exception's message
        // as that call's result, so the conversation carried on rather than dying; Asking catches
        // it here and does the same, which is why the behaviour is pinned rather than described.
        // Answering with a written error string is still the better habit, because a
        // sentence composed for a reader beats whatever a stack trace's first line happens to say,
        // but it is a courtesy to the model rather than the thing keeping the run alive.
        Map<Tool, Calling> tools = new LinkedHashMap<>();
        tools.put(spec("ping"), call -> {
            throw new IllegalStateException("the tool blew up");
        });
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("t"))
                : saying("done"));

        String answer = new Asking(model, "you are a test", tools, "agent:test", null).run("go");

        assertEquals("done", answer, "the run survived a tool that threw");
        // AS A TOOL RESULT AND NOT AS SOMETHING ELSE. One message is one record now, so the role
        // has to be asserted where the type used to assert itself: a failure handed back as, say,
        // a user turn would still contain the words and would still be wrong.
        assertEquals(Said.Role.TOOL, model.seen.get(1).get(3).role());
        assertTrue(model.seen.get(1).get(3).text().contains("the tool blew up"),
                "and the model was told what went wrong: " + model.seen.get(1).get(3));
    }

    @Test
    void aListenerIsToldAboutEachCallAndIsShownAtMostTheWatchersBound() {
        String huge = "x".repeat(9_000);
        Map<Tool, Calling> tools = new LinkedHashMap<>();
        tools.put(spec("ping"), call -> huge);
        List<String> watched = new ArrayList<>();
        Scripted model = new Scripted(ask -> ask.messages().size() == 2
                ? wanting(called("t"))
                : saying("done"));

        new Asking(model, "you are a test", tools, "agent:test",
                (context, tool, memoryId, arguments, result) -> watched.add(context + " " + tool
                        + " " + result)).run("go");

        assertEquals(1, watched.size());
        assertTrue(watched.get(0).startsWith("agent:test ping "), watched.get(0));
        assertTrue(watched.get(0).endsWith("... (truncated, total 9000 chars)"),
                "a listener is for watching, so it is shown a shortened result: "
                        + watched.get(0).substring(watched.get(0).length() - 60));
    }

    // ---- the stand-ins ----

    /** One tool called ping, which does nothing but say it was reached. */
    private static Map<Tool, Calling> one(String name, AtomicInteger calls) {
        Map<Tool, Calling> tools = new LinkedHashMap<>();
        tools.put(spec(name), call -> {
            calls.incrementAndGet();
            return "pong";
        });
        return tools;
    }

    private static Tool spec(String name) {
        return Tool.of(name, "a tool that exists so the loop has something to turn on");
    }

    /** One call of the only tool these tests advertise. */
    private static Called called(String id) {
        return new Called(id, "ping", "{}");
    }

    /**
     * A turn that ends asking for tools, which is what keeps the loop going round.
     *
     * <p>{@link Ending#TOOLS} beside them because that is what the endpoint sends, but it is the
     * calls themselves the loop reads: {@link Reply#wantsTools} asks whether the list is empty and
     * nothing here reads the finish reason to decide.
     */
    private static Reply wanting(Called... calls) {
        return new Reply("", "", List.of(calls), Ending.TOOLS, Spend.NONE);
    }

    /** A turn that answers and stops. */
    private static Reply saying(String text) {
        return new Reply(text, "", List.of(), Ending.STOPPED, Spend.NONE);
    }

    /**
     * A model that answers from a function of the ask, and keeps every conversation it was shown so
     * a test can ask what the loop actually sent.
     *
     * <p>{@link Ask} copies the message list on the way in, so what is kept here is the
     * conversation as it stood at that call rather than a view of the list the loop goes on
     * appending to. The tools it was offered are kept beside them for the same reason: advertising
     * them is now this library's job rather than a client's, so it is something a test can ask
     * about.
     */
    private static final class Scripted implements Chat {

        private final Function<Ask, Reply> answering;
        private final List<List<Said>> seen = new ArrayList<>();
        private final List<List<Tool>> offered = new ArrayList<>();

        private Scripted(Function<Ask, Reply> answering) {
            this.answering = answering;
        }

        @Override
        public Reply answer(Ask ask) {
            seen.add(List.copyOf(ask.messages()));
            offered.add(List.copyOf(ask.tools()));
            return answering.apply(ask);
        }
    }

    @Test
    void aSpentRoundBudgetIsNotAskedAgainBecauseTheAnswerCostsTheWholeBudget() {
        // EVERY OTHER REFUSAL IN THIS LIBRARY COSTS ONE ATTEMPT TO REDISCOVER. This one costs a
        // conversation: the bound fires after twenty-five rounds of tool calls and a retry re-runs
        // all twenty-five from nothing, so ten attempts is two hundred and fifty rounds of model
        // calls to arrive at the same wall. The busiest recorded conversation fitted 465 tool calls
        // inside that budget.
        //
        // It was a bare IllegalStateException, and transportFailures() retries what it does not
        // recognise — which is right in general, because the cost of retrying something hopeless is
        // normally one bounded sequence. That reasoning is exactly what does not hold here.
        //
        // IT MATTERS BECAUSE OF THE DOOR ratchet#8 OPENED. In the shipped chain Asking sits ABOVE
        // Retrying, so this never reached the predicate. But Retrying.around exists so a consumer
        // can wrap something that is not a Chat — its javadoc names a tool invocation and a
        // third-party agent runtime — and a consumer wrapping their agent loop in it inherited a
        // retry of the one failure that cannot be retried.
        assertFalse(Retrying.transportFailures().test(new Exhausted("exceeded 25 rounds")),
                "asking again re-runs the whole conversation to reach the same wall");

        // THE TYPE IS THE ONLY THING THAT CHANGED, and it must stay catchable the way it was: both
        // callers catch RuntimeException and file "unreachable", and the message above appears
        // 60,173 times in one corpus's own record. aModelThatNeverStopsCallingToolsIsCutOffWith-
        // ThatExactMessage holds those two claims; this only checks the new type keeps them true.
        assertTrue(new Exhausted("x") instanceof IllegalStateException,
                "nothing that used to catch this stops catching it");
    }
}
