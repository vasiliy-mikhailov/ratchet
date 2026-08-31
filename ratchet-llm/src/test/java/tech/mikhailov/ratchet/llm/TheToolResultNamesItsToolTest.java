package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A RESULT WITH NO NAME READS AS AN ANSWER FROM NOWHERE, and by the third turn a request carries
 * several of them.
 *
 * <p>ratchet#9. In 0.12.0 a tool result travelled as a client library's own message type, which
 * carried an id, a NAME and the text, and this library's listener rendered all three. 0.13.0 defined
 * the message type itself, kept the id and dropped the name, and the recorded request went from
 *
 * <pre>
 * [tool result]                 [tool result]
 * glob -&gt; no files match        no files match
 * </pre>
 *
 * <p>The consumer who found it had asserted the left-hand shape since their record page was built.
 * Nothing in THIS repository had an opinion: {@code grep -rn 'tool result' ratchet-llm/src/test}
 * returned nothing, because the test that covered tool calls covered the call going OUT and not the
 * result coming BACK and being attributed. So the field went with the type and no test noticed.
 *
 * <p>This file is that opinion. It asserts both halves — the id, which is what the server matches
 * on, and the name, which is what a reader matches on — because keeping only one of them is exactly
 * what happened.
 */
class TheToolResultNamesItsToolTest {

    @Test
    void theResultCarriesTheWholeCallItAnswers() {
        Called asked = new Called("call-7", "glob", "{\"pattern\":\"**/*.gradle\"}");

        Said result = Said.result(asked, "no files match **/*.gradle");

        assertEquals("call-7", result.answering().id(), "what the server matches on");
        assertEquals("glob", result.answering().name(), "what a reader matches on");
        assertEquals("no files match **/*.gradle", result.text());
    }

    @Test
    void onlyTheIdGoesOnTheWire() {
        // THE NAME IS FOR THE RECORD, NOT FOR THE SERVER. A `name` on a tool result is not part of
        // the request shape this endpoint accepts, so carrying it in the record must not change what
        // is sent — otherwise fixing a reading problem breaks a conversation.
        String wire = Said.result(new Called("call-7", "glob", "{}"), "nothing").wire();

        assertTrue(wire.contains("\"tool_call_id\":\"call-7\""), wire);
        assertTrue(wire.contains("\"role\":\"tool\""), wire);
        assertTrue(wire.contains("\"content\":\"nothing\""), wire);
        assertEquals(3, wire.split(":").length - 1, "three fields and no fourth: " + wire);
    }

    @Test
    void theRecordedRequestNamesTheToolThatAnswered() {
        // THE ASSERTION THE CONSUMER HAD AND THIS REPOSITORY DID NOT. Driven through the whole tool
        // loop rather than through Listening directly, because the regression was that the name
        // stopped being CARRIED, and a test that hands Listening a message it built itself would
        // have stayed green through the whole thing.
        List<Trace.Exchange> rows = new ArrayList<>();
        Map<Tool, Calling> tools = new LinkedHashMap<>();
        tools.put(new Tool("glob", "find files", "{\"type\":\"object\",\"properties\":{}}"),
                call -> "no files match **/*.gradle");

        List<Ask> asked = new ArrayList<>();
        Chat model = ask -> {
            asked.add(ask);
            return ask.messages().size() == 2
                    ? new Reply("", "", List.of(new Called("t1", "glob", "{}")), Ending.TOOLS,
                            Spend.NONE)
                    : new Reply("done", "", List.of(), Ending.STOPPED, Spend.NONE);
        };
        Listening listening = new Listening(recording(rows));

        new Asking(ask -> {
            listening.sending(ask);
            Reply reply = model.answer(ask);
            listening.back(ask, reply, 0);
            return reply;
        }, "you are a test", tools, "agent:test", null).run("go");

        String secondRequest = rows.stream().filter(r -> r.direction().equals("to"))
                .reduce((a, b) -> b).orElseThrow().sent();
        assertTrue(secondRequest.contains("glob -> no files match **/*.gradle"),
                "the tool that produced it matters as much as the text:\n" + secondRequest);
    }

    @Test
    void aMessageThatAnswersNothingSaysNothingExtra() {
        // The guard against fixing this by prefixing everything: a user turn has no call behind it
        // and must not grow an arrow.
        List<Trace.Exchange> rows = new ArrayList<>();
        Listening listening = new Listening(recording(rows));

        listening.sending(new Ask(List.of(Said.system("you are a test"), Said.user("the question")),
                List.of(), "agent:test"));

        String sent = rows.get(0).sent();
        assertTrue(sent.contains("the question"), sent);
        assertTrue(!sent.contains("->"), "nothing was answering anything: " + sent);
    }

    private static Trace recording(List<Trace.Exchange> into) {
        return new Trace() {
            @Override
            public void exchanged(Exchange exchange) {
                into.add(exchange);
            }

            @Override
            public void asked(String agent, String prompt, String reply) {
            }

            @Override
            public void applied(String stage, String what) {
            }

            @Override
            public void tool(String agent, String tool, String arguments, String result) {
            }

            @Override
            public void thought(String agent, String finishReason, String thinking, String content) {
            }

            @Override
            public void built(String phase, Outcome result) {
            }

            @Override
            public void settled(String key, String state, String because, boolean beforeOk,
                                boolean afterOk) {
            }

            @Override
            public void failed(String agent, String key, Throwable cause) {
            }

            @Override
            public void progress(String key, String note) {
            }

            @Override
            public void priced(String key, String minutes, String itemisation) {
            }
        };
    }
}
