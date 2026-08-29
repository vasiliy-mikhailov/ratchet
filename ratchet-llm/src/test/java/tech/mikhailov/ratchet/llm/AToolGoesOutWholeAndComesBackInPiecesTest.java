package tech.mikhailov.ratchet.llm;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A TOOL IS ADVERTISED ONCE AND WHOLE, AND THE CALL IT PRODUCES COMES BACK IN FRAGMENTS THAT MUST
 * BE PUT BACK TOGETHER BYTE FOR BYTE — THE ID, THE NAME AND EVERY CHARACTER OF THE ARGUMENTS.
 *
 * <p>Both halves are {@link Wire}'s own now. {@link Wire#body} writes the {@code tools} array, and
 * the read loop reassembles {@code choices[].delta.tool_calls[]} across frames using the index
 * inside {@code tool_calls} — never the choice-level {@code index}, which comes first and is always
 * zero. Neither half had been asserted.
 *
 * <p>THE MUTATION REPORT NAMED THE HOLE TWICE, AND THE SECOND WAY IS THE ONE WORTH READING.
 * Fifteen of Wire's fifty-four live mutants are in the four methods a tool passes through
 * ({@code body}, its tools lambda, {@code collect}, {@code entries}), and the lambda that writes one
 * tool onto the wire came back NO_COVERAGE: no test in this module has ever called {@code body}
 * with a tool in the {@link Ask}, so what a tool looks like to the server was decided by reading
 * the code. On the way back, {@code grep -rn "calls().get"} over this module's tests returns ONE
 * line, asserting a name, and an id read off the wire is asserted nowhere at all — which is why
 * both halves of {@code if (!id.isBlank())} survive being deleted. {@link Called}'s javadoc says
 * what that costs: "a conversation that answers a call with the wrong id is a conversation the
 * model cannot follow."
 *
 * <p>THE FIXTURE ALREADY EXISTED AND NOTHING CALLED IT. {@code TheClientOwnsTheSocketAndEverything
 * OnItTest} carries {@code twoCallsInOneTurn()} — the shape captured from the production endpoint,
 * with a paragraph of javadoc explaining why the flat read files the second call's arguments
 * against the first — and {@code grep -n twoCallsInOneTurn} over the whole test tree returns its
 * declaration and no call site. {@code breaksAfterSaying} is orphaned the same way. A fixture with
 * no caller is a requirement nobody checks, and it fails silently: the build stays green, the
 * javadoc still reads as coverage, and the mutants underneath it are the only thing that says
 * otherwise. The frames below are that shape, and this file calls them.
 *
 * <p>WHY THE ARGUMENTS ARE THE HARD PART. They are a JSON document the model composed, sent as a
 * JSON string inside another JSON document, split at arbitrary points — including the middle of a
 * string literal — and the splitter that separates one call from the next counts braces. A grep
 * pattern for a line ending in a brace and a glob with an alternation both carry a brace INSIDE a
 * quoted string, which is what a model searching a repository actually sends, and a splitter that
 * stops respecting strings or their escapes drops the fragment entirely rather than truncating it
 * visibly.
 */
class AToolGoesOutWholeAndComesBackInPiecesTest {

    /** A schema with something in it: the parameters go on the wire as JSON, not as a string. */
    private static final Tool GREP = new Tool("grep", "search the repository",
            "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}},"
                    + "\"required\":[\"pattern\"]}");

    /** And the empty one, which is a legitimate tool rather than a tool with no schema. */
    private static final Tool GLOB = Tool.of("glob", "list files by pattern");

    // ------------------------------------------------------------------ what goes out

    @Test
    void aToolIsAdvertisedWithItsSchemaAsJsonRatherThanAsAString() {
        // THE PARAMETERS ARE THE PAYLOAD AND THEY ARE NOT ESCAPED. Everything else in this body is
        // a string field; `parameters` is the one place raw JSON is spliced in, so quoting it is
        // the one mistake that produces a body the server parses happily and a tool the model can
        // never call correctly. Asserted as the exact array, because the shape is the requirement:
        // an object per tool, `type` beside `function`, and the three fields the endpoint reads.
        String sent = Wire.body(new Ask(List.of(Said.user("which build files are there")),
                List.of(GREP, GLOB), ""), Endpoint.of("http://test/v1", "a-model"),
                Sampling.deterministic(), true);

        assertEquals("[{\"type\":\"function\",\"function\":{\"name\":\"grep\","
                        + "\"description\":\"search the repository\",\"parameters\":"
                        + "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}},"
                        + "\"required\":[\"pattern\"]}}},"
                        + "{\"type\":\"function\",\"function\":{\"name\":\"glob\","
                        + "\"description\":\"list files by pattern\",\"parameters\":"
                        + "{\"type\":\"object\",\"properties\":{}}}}]",
                Json.part(sent, "tools"),
                "two tools, in the order the agent offered them, each with its schema inline — a "
                        + "tool that writes itself as an empty object advertises a name the model "
                        + "cannot call, and two of them write \"tools\":[,], which is not JSON at "
                        + "all. The whole body was:\n" + sent);
    }

    @Test
    void anAgentWithNothingToCallSendsARequestWithNoToolsFieldInIt() {
        // AN EMPTY SET IS NOT THE SAME AS NO TOOLS, which is Ask's own javadoc and the reason for
        // the branch. An agent with nothing to call is an ordinary agent, and a server handed
        // `"tools":[]` is entitled to refuse the request — so the field is absent rather than
        // empty.
        //
        // The WHOLE body, rather than the absence of one field. A test that asserts what a request
        // does not contain passes on every request that does not contain anything else either;
        // this says what the request IS, and the tools field is missing from it by being missing
        // from this string.
        String sent = Wire.body(Ask.of(List.of(Said.user("which build files are there"))),
                Endpoint.of("http://test/v1", "a-model"), Sampling.deterministic(), true);

        assertEquals("{\"model\":\"a-model\",\"stream\":true,"
                        + "\"stream_options\":{\"include_usage\":true},\"max_tokens\":16000,"
                        + "\"temperature\":0.0,\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"which build files are there\"}],"
                        + "\"thinking_token_budget\":4000}",
                sent,
                "this is the entire request an agent with no tools sends, and every field in it "
                        + "was measured on the live endpoint before it was trusted");
    }

    // ------------------------------------------------------------------ what comes back

    @Test
    void twoCallsInOneTurnKeepTheirOwnIdsNamesAndArguments() {
        // THE SHAPE THE ENDPOINT ACTUALLY SENDS: the first delta of a call carries its id and its
        // name and no arguments, every delta after it carries only an index and the next fragment,
        // and the two calls alternate. The index that separates them is inside `tool_calls`,
        // beneath a choice-level `index` that is always zero, which is why a flat read files the
        // second call's arguments against the first — and why nothing here reads the chunk flat.
        Reply reply = reading().read(twoCallsInOneTurn());

        assertEquals(2, reply.calls().size(),
                "two calls were asked for and they are two calls: " + reply.calls());
        assertEquals(new Called("call_a", "grep", "{\"pattern\":\"^func {$\"}"),
                reply.calls().get(0),
                "the first call, whole: the id the server issued — which travels back on the "
                        + "result message and is the only thing matching an answer to a question — "
                        + "the name it arrived with, and both fragments of the pattern in order");
        assertEquals(new Called("call_b", "glob", "{\"glob\":\"**/*.{js,ts}\"}"),
                reply.calls().get(1),
                "and the second, whose arguments arrived between the first call's two fragments; "
                        + "an assembler that loses the index files this text under call_a");
        assertEquals(Ending.TOOLS, reply.ending(),
                "the turn ended asking for tools, which is what makes the loop run them");
    }

    @Test
    void aBraceInsideAnArgumentIsNotTheEndOfTheCall() {
        // THE SPLITTER COUNTS BRACES AND THE ARGUMENTS ARE FULL OF THEM. This call is an edit whose
        // old and new text are both brace lines — the ordinary thing an agent editing Java sends —
        // and the fragment boundary falls in the middle of a JSON string, between the closing quote
        // of one value and the comma before the next.
        //
        // Every way of getting this wrong loses the whole fragment rather than mangling it: a
        // scanner that stops tracking strings, or stops honouring the backslash before a quote,
        // never sees the object's depth return to zero, so the fragment is silently not there and
        // the model is answered with a call it did not make.
        Reply reply = reading().read(oneEditInTwoFragments());

        assertEquals(new Called("call_e", "edit",
                        "{\"old\":\"if (a) {\",\"new\":\"} else {\"}"),
                reply.calls().get(0),
                "the arguments as the model wrote them, both braces and the escaped quotes "
                        + "included, joined at the point the frames were split");
    }

    // ---------------------------------------------------------------- the fakes

    /**
     * A client with no socket under it, on the shipped bounds.
     *
     * <p>{@link Wire#read} takes the frames directly, so nothing here needs a server, a clock or a
     * model: every stream below is finite and arrives at once, and the guards the shipped
     * {@link Watch} sets are twenty minutes and three hours away.
     *
     * <p>FINITE ON PURPOSE, AND THAT IS NOT A STYLE PREFERENCE. {@code body.close()} does not stop
     * the reader thread — it runs the stream's close handlers, and a {@code Stream.generate}
     * fixture has no idea it has been closed — so a test that hands this loop an endless stream
     * leaves a daemon thread appending to an unbounded queue for the life of the JVM. Measured
     * against the ceiling fixture in this package: the guard fired after 224,000 frames, and in the
     * 300 milliseconds AFTER {@code read} returned the reader produced 5.3 million more and took
     * the heap to 140 MB, still climbing a second later. A real socket's stream throws when it is
     * closed and the thread ends; a generator never does. That is the likeliest reason sixteen of
     * Wire's fifty-four live mutants come back MEMORY_ERROR rather than killed — a mutant that
     * removes a loop's exit condition cannot be scored by a JVM that runs out of memory first.
     */
    private static Wire reading() {
        return new Wire(Endpoint.of("http://test/v1", "a-model"), Sampling.deterministic(),
                Watch.shipped(), true, Trace.quiet());
    }

    /**
     * TWO CALLS IN ONE TURN, INTERLEAVED, WITH THE ARGUMENTS IN FRAGMENTS.
     *
     * <p>The blank lines are the SSE frame separators the endpoint sends between chunks, and the
     * arguments are a grep pattern for a line ending in an opening brace and a glob with an
     * alternation — both of which carry a brace inside a JSON string.
     */
    private static Stream<String> twoCallsInOneTurn() {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_a\","
                        + "\"type\":\"function\",\"index\":0,\"function\":{\"name\":\"grep\","
                        + "\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_b\","
                        + "\"type\":\"function\",\"index\":1,\"function\":{\"name\":\"glob\","
                        + "\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"pattern\\\":\\\"^func \"}}]},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"function\":{\"arguments\":\"{\\\"glob\\\":\\\"**/*.{js,ts}\\\"}\"}}]},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{$\\\"}\"}}]},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},"
                        + "\"finish_reason\":\"tool_calls\"}]}",
                "",
                "data: [DONE]");
    }

    /** One call, split mid-string, whose arguments are mostly braces and escaped quotes. */
    private static Stream<String> oneEditInTwoFragments() {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_e\","
                        + "\"type\":\"function\",\"index\":0,\"function\":{\"name\":\"edit\","
                        + "\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"old\\\":\\\"if (a) {\"}}]},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\",\\\"new\\\":\\\"} else {\\\"}\"}}]},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},"
                        + "\"finish_reason\":\"tool_calls\"}]}",
                "",
                "data: [DONE]");
    }
}
