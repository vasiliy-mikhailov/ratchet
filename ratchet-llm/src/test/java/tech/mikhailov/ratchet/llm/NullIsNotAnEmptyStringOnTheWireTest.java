package tech.mikhailov.ratchet.llm;

import java.util.List;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * NULL IS NOT AN EMPTY STRING, AND A SERVER THAT ACCEPTS ONE AND REJECTS THE OTHER IS A BUG NOBODY
 * FINDS BY READING A BUILDER.
 *
 * <p>{@link Said#wire()} is the entire request shape for one message, and it was written as a pure
 * function precisely so that the exact bytes could be asserted without a socket — its own javadoc
 * says a test does that. Nothing did. Before this file
 * {@code grep -rn 'wire()' ratchet-llm/src/test} returned ONE line: a tool result, checked with
 * three {@code contains} and a count of colons. The ASSISTANT turn — the only shape anybody wrote a
 * rule down for — goes out on every tool call this library has ever made, and no test had ever
 * called {@code wire()} on one.
 *
 * <p>The stronger mutator set put a number on it: twelve mutants lived in {@link Said}, six of them
 * with NO COVERAGE AT ALL because they sit in the assistant branch. Two of those six flip
 * exactly the rule the class javadoc spends a paragraph on. Make {@code text.isEmpty()} constantly
 * false and every tool-call turn sends {@code "content":""} — the shape that was worth a paragraph
 * because a validating server rejects it. Make it constantly true and the model's own words
 * alongside its calls are dropped from the history it is re-prefilled with on the very next round,
 * so the model reads back a turn in which it called three tools and said nothing about why. Both
 * mutants leave every other test in this module green.
 *
 * <p>So what follows asserts BYTES — the whole string, never a fragment — because every mutant that
 * lived here lived in the part of the message no assertion was looking at. The last test drives
 * {@link Wire#body}, because a serialiser that is correct and unwired writes nothing.
 */
class NullIsNotAnEmptyStringOnTheWireTest {

    private static final Called GLOB = new Called("call-1", "glob", "{}");

    @Test
    void anAssistantTurnOfNothingButToolCallsSendsNullAndNotAnEmptyString() {
        // THE RULE THE CLASS EXISTS FOR, ASSERTED ON THE BYTES. `null` says there is no content;
        // `""` says the model produced content and it was empty. They are different values to a
        // server that validates its input, and only one of them is what happened.
        String wire = Said.assistant("", List.of(GLOB)).wire();

        assertEquals("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"glob\",\"arguments\":\"{}\"}}]}",
                wire,
                "the whole turn, byte for byte: a fragment assertion is how the four mutants in "
                        + "this branch survived");
        assertFalse(wire.contains("\"content\":\"\""),
                "an empty string here is a claim the model said something empty, and the endpoint "
                        + "this library talks to is entitled to refuse the request: " + wire);
    }

    @Test
    void whatTheModelSaidAlongsideItsCallsGoesBackToIt() {
        // THE OTHER HALF OF THE SAME TERNARY, and the one that is silent rather than loud. A turn
        // with content AND calls must keep the content: every subsequent round re-prefills the
        // whole conversation, so blanking it here means the model reads back a turn where it called
        // a tool and gave no reason. Nothing errors; the answers just get worse.
        String wire = Said.assistant("Let me look at the build files.", List.of(GLOB)).wire();

        assertEquals("{\"role\":\"assistant\","
                        + "\"content\":\"Let me look at the build files.\",\"tool_calls\":["
                        + "{\"id\":\"call-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"glob\",\"arguments\":\"{}\"}}]}",
                wire,
                "the content is null ONLY when there is none, not whenever there are tool calls");
    }

    @Test
    void anAssistantTurnWithNoCallsHasNoToolCallsFieldAtAll() {
        // NOT AN EMPTY ARRAY. `tool_calls: []` on an ordinary answer is the same class of mistake
        // as the empty `tools` array Ask's javadoc refuses to send: a server handed a field
        // announcing calls that are not there is entitled to complain about it.
        String answered = Said.assistant("done", List.of()).wire();

        assertEquals("{\"role\":\"assistant\",\"content\":\"done\"}", answered,
                "two fields and no third: an ordinary answer is not a tool-call turn");
        assertFalse(answered.contains("tool_calls"),
                "an empty tool_calls array announces calls that were never made: " + answered);

        // AND THE CONTRAST THAT MAKES THE NULL RULE READABLE. An assistant turn with nothing to say
        // and nothing to call sends "" — because there IS content and it is empty. The null above
        // is not about emptiness, it is about a turn whose whole content is the calls.
        assertEquals("{\"role\":\"assistant\",\"content\":\"\"}",
                Said.assistant("", List.of()).wire(),
                "empty content is still content; only a tool-call turn has none");
    }

    @Test
    void everyCallInTheTurnIsWrittenWholeAndInTheOrderItWasAsked() {
        // A MODEL ASKS FOR SEVERAL TOOLS IN ONE TURN ROUTINELY, and each one must arrive with its
        // own id, its own name and its own arguments. Replacing the per-call writer with "" leaves
        // "tool_calls":[,] — malformed JSON that no unit assertion on a single call would notice,
        // and that fails at the socket rather than here.
        Said turn = Said.assistant("", List.of(
                new Called("call-1", "glob", "{\"pattern\":\"*.md\"}"),
                new Called("call-2", "read", "{\"path\":\"README.md\"}")));

        assertEquals("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call-1\",\"type\":\"function\",\"function\":"
                        + "{\"name\":\"glob\",\"arguments\":\"{\\\"pattern\\\":\\\"*.md\\\"}\"}},"
                        + "{\"id\":\"call-2\",\"type\":\"function\",\"function\":"
                        + "{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}"
                        + "]}",
                turn.wire(),
                "both calls, in order, with the arguments the model wrote escaped INTO a JSON "
                        + "string — arguments are a string on this wire and are never re-parsed "
                        + "here, so an unescaped quote would break the whole request");
    }

    @Test
    void onlyTheModelsOwnTurnIsAllowedToCarryToolCalls() {
        // THE BRANCH IS KEYED ON THE ROLE AND NOT MERELY ON THERE BEING CALLS, and the record
        // permits the difference: nothing in the compact constructor stops a USER message being
        // built with a call list. `tool_calls` is an assistant-only field in this request schema,
        // so if the role test were dropped, a consumer replaying its own stored conversation would
        // send a user message carrying tool_calls and be refused at the endpoint.
        Said notTheModelsTurn = new Said(Said.Role.USER, "run the build", List.of(GLOB), null);

        assertEquals("{\"role\":\"user\",\"content\":\"run the build\"}", notTheModelsTurn.wire(),
                "a user turn is written as a user turn whatever else the record is carrying");
    }

    @Test
    void aPlainMessageIsTwoFieldsAndTheRoleTheEndpointKnows() {
        // THE SHAPE EVERY REQUEST OPENS WITH, and the one a mutant can empty out unnoticed: wire()
        // returning "" here puts "messages":[,,] on the wire. Asserted as bytes for the same reason
        // as everything else in this file.
        assertEquals("{\"role\":\"system\",\"content\":\"you are a test\"}",
                Said.system("you are a test").wire(),
                "the system prompt is the first thing every conversation sends");
        assertEquals("{\"role\":\"user\",\"content\":\"the question\"}",
                Said.user("the question").wire(),
                "no tool_call_id on a message that is not answering a call: the field is not "
                        + "merely ignored by a validating server, it is refused");

        // AND THE TEXT IS ESCAPED INTO THE FIELD, not concatenated into it. A prompt containing a
        // quote or a newline is ordinary — this library sends whole file contents as tool results —
        // and an unescaped one truncates the request at the character the model happened to write.
        assertEquals("{\"role\":\"user\",\"content\":\"he said \\\"no\\\"\\nand left\"}",
                Said.user("he said \"no\"\nand left").wire(),
                "one quote in a prompt must not end the JSON string early");
    }

    @Test
    void aToolResultThatAnswersNothingStillWritesTheField() {
        // A DEFENSIVE BRANCH, ASSERTED RATHER THAN ASSUMED. Said is a public record and the
        // canonical constructor takes a null `answering` without complaint, so this shape is
        // reachable from outside this repository even though no factory here builds it. The guard
        // costs one ternary; without it, serialising the request throws NullPointerException and
        // takes down the whole run — instead of sending one message the server can reject and
        // report. The empty id is the honest thing to send: there is nothing to match on.
        Said orphan = new Said(Said.Role.TOOL, "some output", List.of(), null);

        assertEquals("{\"role\":\"tool\",\"tool_call_id\":\"\",\"content\":\"some output\"}",
                assertDoesNotThrow(orphan::wire,
                        "a malformed message must not be able to end the run before it is sent"),
                "the field is present and empty rather than absent: the server's own error names "
                        + "the message, which a stack trace from inside the serialiser does not");
    }

    @Test
    void aMessageThatAnswersNothingNamesNothing() {
        // answeringName() IS DOCUMENTED AS RETURNING "" WHEN THERE IS NOTHING BEING ANSWERED, and
        // Listening renders "name -> text" from it. Its only caller in this repository guards by
        // role first, which is why removing the null check here changed nothing and survived; the
        // contract is public, and a consumer rendering its own record page calls it on whatever it
        // has. What it must never do on an ordinary turn is throw.
        assertEquals("", Said.user("the question").answeringName(),
                "a user turn answers no call, and asking what it answers is a fair question");
        assertEquals("", Said.system("you are a test").answeringName(),
                "nor does a system prompt");
        assertEquals("glob", Said.result(GLOB, "no files match").answeringName(),
                "and a result names the tool that produced it — ratchet#9, which is the whole "
                        + "reason the name is carried at all");
    }

    @Test
    void theRequestBodyIsMadeOfTheseExactMessages() {
        // PROVE THE WIRING, NOT ONLY THE UNIT. Every assertion above is on wire() in isolation, and
        // a serialiser that is perfect and unreferenced sends nothing. This drives the one function
        // that builds the real POST body, over the conversation Asking actually produces —
        // system, user, the model's tool-call turn, the result answering it — and asserts the
        // messages array in full. It is also the only place the joining shows: wire() returning ""
        // for any message leaves "messages":[{...},,{...}], which is not JSON at all and fails at
        // the endpoint rather than in a test.
        Ask conversation = Ask.of(List.of(
                Said.system("you are a test"),
                Said.user("which build files are there"),
                Said.assistant("", List.of(GLOB)),
                Said.result(GLOB, "no files match")));

        String sent = Wire.body(conversation, Endpoint.of("http://test/v1", "a-model"),
                Sampling.deterministic(), true);

        assertEquals("[{\"role\":\"system\",\"content\":\"you are a test\"},"
                        + "{\"role\":\"user\",\"content\":\"which build files are there\"},"
                        + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"glob\",\"arguments\":\"{}\"}}]},"
                        + "{\"role\":\"tool\",\"tool_call_id\":\"call-1\","
                        + "\"content\":\"no files match\"}]",
                Json.part(sent, "messages"),
                "the request is these four messages in this order and nothing else — the whole "
                        + "body was:\n" + sent);
    }
}
