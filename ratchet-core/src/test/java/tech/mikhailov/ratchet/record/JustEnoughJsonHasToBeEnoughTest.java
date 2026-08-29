package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUST ENOUGH JSON HAS TO ACTUALLY BE ENOUGH, because everything this library writes down and
 * everything a model says back to it comes through this one file.
 *
 * <p>There is no tree model here on purpose: a parser would be a dependency taken by every process
 * that writes a row, and those processes are already running a stranger's code. The price of that
 * decision is that the handwritten subset has to be right about the documents that actually
 * arrive, and this is the file that says which ones those are. Since the langchain4j removal that
 * includes every byte of every model response: a streamed frame arrives as text and {@link Json}
 * is the only thing that turns it into a token count, a sentence, or a tool call.
 *
 * <p>THREE OF THE REQUIREMENTS BELOW WERE PAID FOR RATHER THAN DESIGNED.
 *
 * <p>{@link Json#read} is deliberately blind to a bare word, and that blindness swallowed every
 * integer argument any agent ever sent: one tool's {@code limit} was read as absent and silently
 * replaced by its default on every call the tool ever received, for as long as it existed, with
 * nothing anywhere saying so. {@link Json#number} is the carve-out, and the tests for it are the
 * only record of why it is not simply part of {@code read}.
 *
 * <p>A flat scan takes the FIRST match, which is right for a tool argument and wrong for a streamed
 * frame: a frame carrying a tool call has an {@code index} at the choice level and a second
 * {@code index} inside {@code tool_calls}, and the choice's comes first and is always zero. With
 * one call in flight nothing shows; with two calls in one turn the second call's arguments are
 * filed against the first. {@link Json#part} descends one step so that cannot happen, and
 * {@link #theSecondToolCallsArgumentsAreNotFiledAgainstTheFirst} is that requirement written down.
 *
 * <p>And a frame gets cut. The transport hands over whatever arrived, so a value cut at its colon,
 * inside its string, or on a backslash is the normal case rather than a fault — the same thing the
 * journal says about its own last line. Every reader here answers a truncated document with what it
 * has instead of an exception, because an exception in this position ends a run that was working.
 */
class JustEnoughJsonHasToBeEnoughTest {

    /**
     * One frame in the shape the endpoint sends it: the SECOND tool call of a turn, which is the
     * case that was being got wrong. Note the two {@code index} fields and the arguments arriving
     * as a JSON document inside a JSON string.
     */
    private static final String FRAME =
            "{\"id\":\"chatcmpl-9\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\","
            + "\"tool_calls\":[{\"index\":1,\"id\":\"call_2\",\"function\":{\"name\":\"edit\","
            + "\"arguments\":\"{\\\"path\\\":\\\"src/a,b.java\\\"}\"}}]},"
            + "\"finish_reason\":null}],\"usage\":null}";

    @Test
    void theAbsentOptionalAndTheEmptyStringAreDifferentAnswers() {
        // A page reading the record has to tell "nobody answered" from "answered with nothing".
        // Collapsing them is how an empty string gets shown as a result somebody produced.
        assertEquals("null", Json.optional(null), "nothing to say is JSON null, not an empty string");
        assertEquals("null", Json.optional(""), "and so is the empty string an agent sends instead");
        assertEquals("null", Json.optional("   "), "and so is the whitespace it sends instead of that");
        assertEquals("\"a note\"", Json.optional("a note"));
        assertEquals("null", Json.string(null), "a null string is the same four unquoted characters");
        assertEquals("\"\"", Json.string(""),
                "but string() is the one that was asked for an empty string on purpose");
    }

    @Test
    void anEmptyObjectIsStillAnObject() {
        // Every one of these returning "" instead would produce a row that parses as nothing at
        // all, and the row is the only thing that outlives the process that wrote it.
        assertEquals("{}", Json.object(), "no fields is an empty object, not an empty document");
        assertEquals("[]", Json.array(List.of(), Json::string), "and no items is an empty array");
        assertEquals("{}", Json.map(Map.of()));
        assertEquals("{\"a\":\"1\",\"rounds\":2}",
                Json.object(Json.field("a", Json.string("1")), Json.field("rounds", "2")),
                "fields are joined by exactly one comma, and field() writes the value raw");
        assertEquals("[\"a\",\"b\"]", Json.array(List.of("a", "b"), Json::string));
    }

    @Test
    void aKeyThatCameFromDataIsEscapedOnTheWayOut() {
        // map() is the one writer whose KEYS are data rather than literals, so it is the one that
        // escapes them. field() does not, and a caller that passes it a name a model chose would
        // write a row nothing can read back.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("pa\"th", Json.string("src/a.java"));
        values.put("n", "2");

        assertEquals("{\"pa\\\"th\":\"src/a.java\",\"n\":2}", Json.map(values),
                "the key is escaped and the value is passed through in the JSON form it arrived in");
    }

    @Test
    void everyAwkwardCharacterSurvivesTheWriterAndTheReaderTogether() {
        // The one escaper is Settlement's, and the reason it is shared is a control character: the
        // field most likely to carry one is a trace body, and a body is exactly what this is.
        String body = "line one\n\tsaid \"no\" \\ and stopped" + (char) 0x07;

        String written = Json.object(Json.field("body", Json.string(body)));

        assertEquals(1, written.lines().count(), "whatever it contained, it is one line");
        assertEquals(body, Json.read(written, "body"),
                "what this file wrote, this file reads back, character for character");

        // A ROUND TRIP CANNOT SEE AN ESCAPING FAILURE THE READER UNDOES, and this test could not.
        // Stop Settlement.escape escaping control characters and everything above still passes: the
        // raw 0x07 goes into the row, comes straight back out, and BEL is not a line terminator so
        // the count stays 1. The mutation run says so out loud -- Settlement.java:153, the
        // `c < 0x20` guard, replaced with false: SURVIVED.
        //
        // The row is read by other processes and by tools that are not this reader, so what matters
        // is the BYTES, not the symmetry. Asserted on the written text, which no reader can undo.
        assertTrue(written.chars().noneMatch(c -> c < 0x20),
                "no raw control character may reach a row that another process has to parse: "
                        + written.chars().filter(c -> c < 0x20).count() + " got through");
        assertTrue(written.contains("\\u0007"),
                "and a control character is written as its escape rather than dropped: " + written);
    }

    @Test
    void aBareWordIsNotAValueThisReaderReturns() {
        // THE BLINDNESS IS THE BEHAVIOUR. In an argument a model composed, an unquoted value is far
        // more often a mistake than an intention, and answering "" sends the caller to its default.
        assertEquals("", Json.read("{\"limit\":50}", "limit"), "a bare number is not a string");
        assertEquals("", Json.read("{\"recursive\":true}", "recursive"), "nor is a bare word");
        assertEquals("", Json.read("{\"usage\":null}", "usage"), "nor is a bare null");
        assertEquals(50, Json.number("{\"limit\":50}", "limit", 80),
                "and number() is the carve-out that stopped every integer argument being dropped");
    }

    @Test
    void aNumberIsTheOneCarveOutAndItTakesItQuotedOrBare() {
        // Moved here from the trace's own test, which is not where this requirement lives.
        assertEquals(50, Json.number("{\"limit\": 50}", "limit", 80));
        assertEquals(50, Json.number("{\"limit\":\"50\"}", "limit", 80),
                "quoted works too: a model quotes its integers whenever it feels like it");
        assertEquals(80, Json.number("{\"stage\":\"migrate\"}", "limit", 80), "absent falls back");
        assertEquals(80, Json.number("{\"limit\":\"lots\"}", "limit", 80), "so does nonsense");
        assertEquals(-3, Json.number("{\"offset\":-3}", "offset", 0), "a minus sign is part of it");
        assertEquals(0, Json.number("{\"index\":0}", "index", -1),
                "and zero is a number rather than an absence, which is what keys a tool call");
    }

    @Test
    void aNameThatIsNotThereIsNotAnsweredWithAnotherFieldsValue() {
        // The offset of a value is computed from the offset of its name. Without the check that the
        // name was found at all, that arithmetic still produces an offset, and it lands somewhere:
        // for a seven-character name in the document below, exactly on the digits of `limit`.
        assertEquals(3, Json.number("{\"limit\":50}", "retries", 3), "the fallback, not 50");
        assertEquals(3, Json.number("{\"limit\":50}", "n", 3));
        assertEquals(3, Json.number("{\"limit\":50}", "rounds", 3));
        assertEquals("", Json.read("{\"limit\":\"50\"}", "retries"), "and the same for read");
        assertEquals("", Json.part("{\"delta\":{\"content\":\"hi\"}}", "choices"),
                "and for part, which a frame with no choices in it is asked on every turn");
        assertEquals("3", Json.read("{\"limit_x\":\"9\",\"limit\":\"3\"}", "limit"),
                "a name is matched with its quotes and its colon, so limit_x is not limit");
    }

    @Test
    void anEscapeIsUndoneOnceAndAnUnknownEscapeIsTheCharacterItself() {
        assertEquals("a\nb", Json.read("{\"t\":\"a\\nb\"}", "t"));
        assertEquals("a\tb", Json.read("{\"t\":\"a\\tb\"}", "t"));
        assertEquals("a\rb", Json.read("{\"t\":\"a\\rb\"}", "t"));
        assertEquals("said \"no\"", Json.read("{\"t\":\"said \\\"no\\\"\"}", "t"));
        assertEquals("one \\ two", Json.read("{\"t\":\"one \\\\ two\"}", "t"));
        // \/ is legal JSON that this library never writes and other writers do; the default arm is
        // what passes it through as the character it stands for.
        assertEquals("src/a.java", Json.read("{\"path\":\"src\\/a.java\"}", "path"));
        assertEquals("café", Json.read("{\"who\":\"caf\\u00e9\"}", "who"),
                "and a \\u escape is the character it spells, not six characters");
    }

    @Test
    void aValueEndsAtItsClosingQuoteAndTheNextFieldIsNotSwallowed() {
        assertEquals("done", Json.read("{\"kind\":\"done\",\"why\":\"the gate held\"}", "kind"),
                "a value that ran on would carry the rest of the row into the field before it");
        assertEquals("the gate held", Json.read("{\"kind\":\"done\",\"why\":\"the gate held\"}", "why"));
        // A REQUIREMENT NOBODY WROTE DOWN: read cannot tell an empty value from an absent one. Both
        // are "", which is why Wire tests a delta with part() before it reads inside it.
        assertEquals("", Json.read("{\"content\":\"\"}", "content"),
                "an empty string reads the same as a name that is not there");
    }

    @Test
    void aFrameCutInHalfGivesUpWhatArrivedRatherThanTakingDownTheStream() {
        // A frame is cut wherever the transport happened to stop. None of these is a fault, and an
        // exception raised here would end a run that had been working for hours.
        assertEquals("", Json.read("{\"content\":", "content"), "cut at the colon");
        assertEquals("", Json.read("{\"content\": ", "content"), "cut in the space after it");
        assertEquals("half a sen", Json.read("{\"content\":\"half a sen", "content"),
                "cut inside the value: what arrived is the answer");
        assertEquals("half\\", Json.read("{\"content\":\"half\\", "content"),
                "cut on a backslash, which is where an escape gets split between two frames");
        assertEquals(80, Json.number("{\"limit\":", "limit", 80), "and a number cut at its colon");
        assertEquals(50, Json.number("{\"limit\":50", "limit", 80),
                "while digits that run into the end of the buffer are still digits");
    }

    @Test
    void aNumberTooBigForAnIntFallsBackRatherThanEndingTheRun() {
        assertEquals(80, Json.number("{\"limit\":99999999999}", "limit", 80),
                "a model writes an absurd number; the tool takes its default and the turn goes on");
        assertEquals(7, Json.number("{\"limit\":-}", "limit", 7),
                "and a minus sign with nothing after it is not a number either");
    }

    @Test
    void aFragmentMayBeginWithTheNameBeingLookedFor() {
        // THE ONE REQUIREMENT HERE THAT WAS ARGUED RATHER THAN PAID FOR, said out loud so the next
        // reader can disagree with it. No caller in this repository hands over a fragment shaped
        // like this today: part() returns something opening with {, [, " or a scalar, and Wire
        // splits a tool_calls array into objects, so the name is never at offset zero and offset
        // zero is indistinguishable from "not found" in every document that actually arrives.
        //
        // It is asserted anyway because part()'s contract is "here is a substring, read inside it",
        // and a substring is whatever the caller cut. A reader that quietly needed a wrapper in
        // front of the name would work for as long as every fragment happened to have one and then
        // read a field as absent, which is the failure this whole file is about: absent and wrong
        // are the same answer here. Deleting this method leaves three boundary mutants alive.
        assertEquals("stop", Json.read("\"finish_reason\":\"stop\"", "finish_reason"));
        assertEquals(42, Json.number("\"prompt_tokens\":42", "prompt_tokens", 0));
        assertEquals("{\"content\":\"hi\"}", Json.part("\"delta\":{\"content\":\"hi\"}", "delta"));
    }

    @Test
    void aFieldNameQuotedInsideAnotherFieldsTextIsNotAField() {
        // WHY THE FLAT SCAN IS SAFE AGAINST PROSE, WHICH IS NOT WRITTEN DOWN ANYWHERE. The reason
        // part() exists is that a flat scan takes the FIRST match, and the first match is not
        // always the intended field. The obvious way for that to go wrong is a model that writes
        // about JSON: an agent asked to explain a frame streams the characters "finish_reason":
        // inside its own content, and it arrives BEFORE the real field on the same line.
        //
        // It cannot match, and the reason is structural rather than lucky: a quote inside a JSON
        // string is escaped, so what a name inside text ends with is backslash-quote and the needle
        // is quote-colon. The hazard part() was built for is NESTING and only nesting. If a writer
        // ever hands this library a string it did not escape, this stops being true and every
        // reader below reads the model's prose as the run's state.
        String choices = "[{\"index\":0,\"delta\":{\"content\":\"write \\\"finish_reason\\\":"
                + "\\\"stop\\\" at the end\"},\"finish_reason\":\"length\"}]";

        assertEquals("length", Json.read(choices, "finish_reason"),
                "the field, not the sentence about the field: a run that read `stop` here would "
                + "record an answer that ran out of room as a complete one");
        assertEquals("write \"finish_reason\":\"stop\" at the end",
                Json.read(Json.part(choices, "delta"), "content"),
                "and the sentence is still delivered whole");
        assertEquals("{\"prompt_tokens\":41}",
                Json.part("{\"delta\":{\"content\":\"the \\\"usage\\\":{\\\"prompt_tokens\\\":9} "
                        + "block\"},\"usage\":{\"prompt_tokens\":41}}", "usage"),
                "the same for part: 41 tokens were spent, whatever the model was talking about");
        assertEquals("thought", Json.row("{\"kind\":\"thought\",\"content\":\"see \\\"kind\\\":"
                        + "\\\"done\\\" there\"}").get("kind"),
                "and the journal's own reader, which walks values instead of searching for names");
    }

    @Test
    void partHandsBackRawJsonAndReadIsStillTheOnlyThingThatDecodesIt() {
        assertEquals("\"hi\"", Json.part("{\"content\":\"hi\"}", "content"),
                "quotes and all: part does not unescape and does not parse");
        assertEquals("hi", Json.read("{\"content\":\"hi\"}", "content"), "read is what decodes");
        assertEquals("null", Json.part("{\"usage\":null}", "usage"),
                "the four characters, because a caller tells a null usage from an absent one by them");
        assertEquals("", Json.part("{\"usage\":null}", "spend"), "and absent really is empty");
        assertEquals("", Json.part(null, "usage"), "as is a document that never arrived");
        assertEquals("[]", Json.part("{\"choices\":[]}", "choices"),
                "an empty array is [] rather than nothing: a frame with no choices is skipped by it");
        assertEquals("0", Json.part("{\"index\":0,\"delta\":{}}", "index"),
                "and a bare scalar stops at its comma");
    }

    @Test
    void theSecondToolCallsArgumentsAreNotFiledAgainstTheFirst() {
        // THE INCIDENT part() EXISTS FOR. The frame below is the second call of a turn. Read flat,
        // the first `index` in it belongs to the choice and is zero, so every fragment of the
        // second call's arguments was appended to the first call's.
        String calls = Json.part(Json.part(Json.part(FRAME, "choices"), "delta"), "tool_calls");

        assertEquals(0, Json.number(FRAME, "index", -1),
                "flat, the first index in a frame is the choice's, and the choice's is always zero");
        assertEquals(1, Json.number(calls, "index", -1),
                "one step down it is the tool call's, which is what the fragments are keyed by");
        assertEquals("chatcmpl-9", Json.read(FRAME, "id"),
                "the same trap for id: flat it is the completion's");
        assertEquals("call_2", Json.read(calls, "id"), "and one step down it is the call's");
    }

    @Test
    void aWholeToolCallSurvivesArgumentsFullOfBracesAndCommas() {
        String function = Json.part(Json.part(Json.part(Json.part(FRAME, "choices"), "delta"),
                "tool_calls"), "function");

        assertEquals("edit", Json.read(function, "name"));
        assertEquals("{\"path\":\"src/a,b.java\"}", Json.read(function, "arguments"),
                "arguments are a JSON document inside a JSON string, and the string is the value");
        assertEquals("\"{\\\"path\\\":\\\"src/a,b.java\\\"}\"", Json.part(function, "arguments"),
                "part returns that string still escaped, because it is raw JSON either way");
    }

    @Test
    void aBraceInsideAStringIsNotABrace() {
        // A model writing code streams braces in prose, and a comma inside a string value is the
        // ordinary case rather than the exotic one. Counting either as structure ends the value
        // early, and what is lost is the tail of whatever the model was saying.
        assertEquals("{\"content\":\"} else {\"}",
                Json.part("{\"delta\":{\"content\":\"} else {\"},\"index\":0}", "delta"),
                "the braces in the sentence are not the object's braces");
        assertEquals("{\"content\":\"say \\\"}\\\" out loud\"}",
                Json.part("{\"delta\":{\"content\":\"say \\\"}\\\" out loud\"},\"index\":0}", "delta"),
                "and an escaped quote does not end the string that a brace is hiding in");
        assertEquals("\"{\\\"path\\\":\\\"a,b\\\"}\"",
                Json.part("{\"arguments\":\"{\\\"path\\\":\\\"a,b\\\"}\",\"name\":\"edit\"}",
                        "arguments"),
                "a quoted value is taken as a string, so its commas and braces are not boundaries");
    }

    @Test
    void anObjectIsNotCutShortByTheOneNestedInsideIt() {
        String frame = "{\"delta\":{\"content\":\"\",\"tool_calls\":[{\"index\":1}]},"
                + "\"finish_reason\":null}";

        assertEquals("{\"content\":\"\",\"tool_calls\":[{\"index\":1}]}", Json.part(frame, "delta"),
                "the delta ends at ITS closing brace, not at the first one that goes past");
        assertEquals("[{\"index\":1}]", Json.part(Json.part(frame, "delta"), "tool_calls"),
                "and an array of objects is the whole array");
    }

    @Test
    void aFrameCutBeforeItsBracketClosesIsNothingRatherThanHalfAnObject() {
        assertEquals("", Json.part("{\"delta\":{\"content\":\"hi\"", "delta"),
                "half an object is not an object; a caller handed one would descend into a lie");
        assertEquals("", Json.part("{\"content\":\"half", "content"), "and half a string is not one");
        assertEquals("", Json.part("{\"delta\":", "delta"), "cut at the colon");
        assertEquals("", Json.part("{\"delta\": ", "delta"), "cut in the space after it");
        assertEquals("0", Json.part("{\"index\":0", "index"),
                "a bare scalar is the exception: there is no closing anything to wait for");
    }

    @Test
    void whitespaceAfterTheColonIsNotPartOfTheValue() {
        // Which endpoint a run is pointed at is the run's business, and whether it puts a space
        // after its colons is not something this library gets to insist on.
        assertEquals("hi", Json.read("{\"content\": \"hi\"}", "content"));
        assertEquals("{\"prompt_tokens\": 7}", Json.part("{\"usage\": {\"prompt_tokens\": 7}}", "usage"));
        assertEquals(7, Json.number("{\"usage\": {\"prompt_tokens\": 7}}", "prompt_tokens", 0));
        assertEquals("0", Json.part("{\"index\": 0 ,\"delta\":{}}", "index"),
                "and a scalar keeps neither the space in front of it nor the one behind");
    }

    @Test
    void aRowsBareValuesAreNotSwallowedByTheFieldsAroundThem() {
        // The trace quotes everything and the settled row does not: `baseline` and `gate` are bare
        // booleans that a dashboard reads by name. One reader has to take both.
        Map<String, String> row = Json.row("{\"bump\":\"core\",\"baseline\":true,\"gate\":false,"
                + "\"rounds\":12,\"because\":\"7 tests, all green\"}");

        assertEquals("core", row.get("bump"));
        assertEquals("true", row.get("baseline"), "a bare value ends at its comma");
        assertEquals("false", row.get("gate"), "and the field after it is its own field");
        assertEquals("12", row.get("rounds"));
        assertEquals("7 tests, all green", row.get("because"),
                "while a comma inside a string is just a comma");
        assertEquals(5, row.size(), "five fields written, five fields read: " + row);
    }

    @Test
    void aSpaceAfterTheColonLeavesTheValueTheSameValue() {
        Map<String, String> row = Json.row("{\"bump\": \"core\", \"rounds\": 12}");

        assertEquals("core", row.get("bump"), "not the same string with its quotes still attached");
        assertEquals("12", row.get("rounds"));
    }

    @Test
    void aTornRowGivesUpTheFieldsThatArrivedAndInventsNoOthers() {
        // A killed run leaves half a row and no newline after it; the journal's own test calls that
        // the normal case. What matters here is the other half of it: no phantom field, no value
        // stitched together out of the row's punctuation, and no exception on the way past.
        assertEquals(Map.of("at", "1", "kind", "done", "node", "before-pins", "key", "w"),
                Json.row("{\"at\":\"1\",\"kind\":\"done\",\"node\":\"before-pins\",\"key\":\"w"),
                "cut inside a value: the fields before it are the run's memory");
        assertEquals(Map.of("at", "1"), Json.row("{\"at\":\"1\",\"kind\""),
                "cut after a name, before its colon: a name with no value is not a field");
        assertEquals(Map.of("at", "1"), Json.row("{\"at\":\"1\", "),
                "cut after a comma: there is no next field to invent");
        assertEquals(Map.of("at", ""), Json.row("{\"at\":"), "cut at the colon");
        assertEquals(Map.of("at", ""), Json.row("{\"at\": "), "cut in the space after it");
        assertEquals(Map.of("because", "half\\"), Json.row("{\"because\":\"half\\"),
                "cut on a backslash, which is where the writer's escape was split");
        assertEquals(Map.of("gate", "tru"), Json.row("{\"gate\":tru"),
                "cut inside a bare value: what arrived, and no more");
    }

    @Test
    void aUnicodeEscapeInARowIsTheCharacterItSpells() {
        // Written by whichever process wrote the row, which is not always this library: any writer
        // that escapes non-ASCII produces these, and a row read back as six literal characters is a
        // row that no longer matches the one that was written.
        assertEquals("café crash", Json.row("{\"because\":\"caf\\u00e9 crash\"}").get("because"));
        assertEquals("a\nb\t\"c\" \\ d", Json.row("{\"because\":\"a\\nb\\t\\\"c\\\" \\\\ d\"}")
                .get("because"), "and the rest of the escapes are the writer's own");
    }
}
