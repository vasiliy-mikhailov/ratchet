package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE BOUND IS PART OF THE PROGRAM, so it is asserted rather than described.
 *
 * <p>{@link Asking} caps an agent at twenty-five rounds because the harness it replaced did, and
 * that cap fires on more than a quarter of the runs in one corpus's own record: the agent loses its
 * final message and the whole question is re-asked. Raising or lowering it changes what a large
 * share of runs do, so it is written down here where a change to it goes red rather than
 * unnoticed.
 *
 * <p>No model is reached. The stub below answers whatever the test needs it to answer, which is the
 * only way to ask a loop about its own arithmetic.
 */
class TheToolLoopStopsAtTwentyFiveRoundsTest {

    @Test
    void aModelThatNeverStopsCallingToolsIsCutOffWithThatExactMessage() {
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(request -> AiMessage.from(
                ToolExecutionRequest.builder().id("t").name("ping").arguments("{}").build()));

        Asking asking = new Asking(model, "you are a test", one("ping", calls), "agent:test", null);

        RuntimeException stopped = assertThrows(RuntimeException.class, () -> asking.run("go"));
        assertTrue(stopped.getMessage().contains("exceeded 25 sequential tool executions"),
                "the bound announces itself in the words the corpus already carries: "
                        + stopped.getMessage());
        // TWENTY-FIVE ROUNDS REALLY RAN. The tools executed and their effects are real; it is the
        // twenty-sixth answer that is fetched and thrown away, which is why an agent cut off here
        // has done its work and lost only the word for it.
        assertEquals(25, calls.get(), "every round up to the bound executed its tools");
    }

    @Test
    void anAnswerArrivingInsideTheBoundComesBackWithTheToolsHavingRun() {
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(request -> calls.get() < 3
                ? AiMessage.from(
                        ToolExecutionRequest.builder().id("t").name("ping").arguments("{}").build())
                : AiMessage.from("done"));

        String answer = new Asking(model, "you are a test", one("ping", calls), "agent:test", null)
                .run("go");

        assertEquals("done", answer);
        assertEquals(3, calls.get());
    }

    @Test
    void theBoundCountsRoundsAndNotCalls() {
        // ONE ASSISTANT MESSAGE ASKING FOR FIVE TOOLS COSTS ONE. The busiest conversation in the
        // record fitted 465 calls inside this budget by batching, which is only possible because
        // the counter moves once per answer.
        AtomicInteger calls = new AtomicInteger();
        Scripted model = new Scripted(request -> AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("a").name("ping").arguments("{}").build(),
                ToolExecutionRequest.builder().id("b").name("ping").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c").name("ping").arguments("{}").build())));

        assertThrows(RuntimeException.class,
                () -> new Asking(model, "you are a test", one("ping", calls), "agent:test", null)
                        .run("go"));

        assertEquals(75, calls.get(), "twenty-five rounds of three calls each");
    }

    @Test
    void theConversationIsTheSystemPromptThenTheTaskAndNothingElse() {
        Scripted model = new Scripted(request -> AiMessage.from("done"));

        new Asking(model, "you are a test", Map.of(), "agent:test", null).run("the question");

        List<ChatMessage> sent = model.seen.get(0);
        assertEquals(2, sent.size(), "nothing is prepended and nothing is remembered");
        assertEquals("you are a test", assertInstanceOf(SystemMessage.class, sent.get(0)).text());
        assertEquals("the question",
                assertInstanceOf(UserMessage.class, sent.get(1)).singleText());
    }

    @Test
    void aSecondQuestionStartsFromNothing() {
        // NO MEMORY IS CONFIGURED, deliberately: an agent asked twice in a run is asked twice from
        // nothing, and what it knows the second time is whatever its tools can tell it.
        Scripted model = new Scripted(request -> AiMessage.from("done"));
        Asking asking = new Asking(model, "you are a test", Map.of(), "agent:test", null);

        asking.run("first");
        asking.run("second");

        assertEquals(2, model.seen.get(1).size(), "the second conversation carries no history");
        assertEquals("second",
                assertInstanceOf(UserMessage.class, model.seen.get(1).get(1)).singleText());
    }

    @Test
    void whatAToolAnsweredIsWhatTheModelIsToldNextTurn() {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(spec("ping"), (request, memoryId) -> "the answer, verbatim");
        Scripted model = new Scripted(request -> request.messages().size() == 2
                ? AiMessage.from(
                        ToolExecutionRequest.builder().id("t").name("ping").arguments("{}").build())
                : AiMessage.from("done"));

        new Asking(model, "you are a test", tools, "agent:test", null).run("go");

        List<ChatMessage> second = model.seen.get(1);
        assertEquals(4, second.size(), "the assistant turn and one result per call are appended");
        assertEquals("the answer, verbatim",
                assertInstanceOf(ToolExecutionResultMessage.class, second.get(3)).text());
    }

    @Test
    void aToolNameTheModelInventedIsNotAnsweredTheSameWayAsAToolThatFailed() {
        // THE OTHER HALF OF THE SAME PARAGRAPH, asserted rather than described, because the two
        // failures look alike from a prompt and are not alike at all: a tool that exists and goes
        // wrong is reported back to the model, and a tool that does not exist is langchain4j's
        // hallucination strategy, which is a different path entirely. Whatever this does, a phase
        // that needs a tool writes it rather than assuming it.
        Scripted model = new Scripted(request -> request.messages().size() == 2
                ? AiMessage.from(ToolExecutionRequest.builder()
                        .id("t").name("no_such_tool").arguments("{}").build())
                : AiMessage.from("done"));

        RuntimeException raised = assertThrows(RuntimeException.class,
                () -> new Asking(model, "you are a test", one("ping", new AtomicInteger()),
                        "agent:test", null).run("go"));

        assertTrue(raised.getMessage().contains("no_such_tool"),
                "the invented name is named: " + raised.getMessage());
    }

    @Test
    void aToolThatThrowsIsAnsweredToTheModelRatherThanEndingTheRun() {
        // MEASURED, BECAUSE THIS CLASS'S OWN SUBJECT USED TO CLAIM THE OPPOSITE. langchain4j
        // catches what an executor throws and hands the model the exception's message as that
        // call's result, so the conversation carries on rather than dying. Answering with a
        // written error string is still the better habit, because a sentence composed for a
        // reader beats whatever a stack trace's first line happens to say, but it is a courtesy
        // to the model rather than the thing keeping the run alive.
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(spec("ping"), (request, memoryId) -> {
            throw new IllegalStateException("the tool blew up");
        });
        Scripted model = new Scripted(request -> request.messages().size() == 2
                ? AiMessage.from(
                        ToolExecutionRequest.builder().id("t").name("ping").arguments("{}").build())
                : AiMessage.from("done"));

        String answer = new Asking(model, "you are a test", tools, "agent:test", null).run("go");

        assertEquals("done", answer, "the run survived a tool that threw");
        assertTrue(assertInstanceOf(ToolExecutionResultMessage.class, model.seen.get(1).get(3))
                        .text().contains("the tool blew up"),
                "and the model was told what went wrong: " + model.seen.get(1).get(3));
    }

    @Test
    void aListenerIsToldAboutEachCallAndIsShownAtMostEightThousandCharacters() {
        String huge = "x".repeat(9_000);
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(spec("ping"), (request, memoryId) -> huge);
        List<String> watched = new ArrayList<>();
        Scripted model = new Scripted(request -> request.messages().size() == 2
                ? AiMessage.from(
                        ToolExecutionRequest.builder().id("t").name("ping").arguments("{}").build())
                : AiMessage.from("done"));

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
    private static Map<ToolSpecification, ToolExecutor> one(String name, AtomicInteger calls) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(spec(name), (request, memoryId) -> {
            calls.incrementAndGet();
            return "pong";
        });
        return tools;
    }

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description("a tool that exists so the loop has something to turn on")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    /**
     * A model that answers from a function of the request, and keeps every conversation it was
     * shown so a test can ask what the loop actually sent.
     */
    private static final class Scripted implements ChatModel {

        private final java.util.function.Function<ChatRequest, AiMessage> answering;
        private final List<List<ChatMessage>> seen = new ArrayList<>();

        private Scripted(java.util.function.Function<ChatRequest, AiMessage> answering) {
            this.answering = answering;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            seen.add(List.copyOf(request.messages()));
            return ChatResponse.builder().aiMessage(answering.apply(request)).build();
        }
    }
}
