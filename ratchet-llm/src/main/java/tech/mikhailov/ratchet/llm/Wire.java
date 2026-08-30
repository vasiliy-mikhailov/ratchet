package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.record.Trace;

/**
 * THE CLIENT: AN OPENAI-COMPATIBLE CHAT ENDPOINT, SPOKEN TO DIRECTLY, WITH NOTHING UNDERNEATH IT.
 *
 * <p>This replaces a dependency, and the arithmetic is the argument. What that dependency did for
 * this library was one POST and one SSE reader; what it cost was thirteen jars and eight megabytes
 * reaching every consumer who wanted the retry schedule, an NLP toolkit and a BPE tokenizer among
 * them, neither of which anything here has ever called.
 *
 * <p>AND IT WAS NOT FREE IN BEHAVIOUR EITHER. Three of this package's classes existed to work
 * around it. The reasoning had to be recovered by wrapping its SSE parser, because this server
 * writes {@code delta.reasoning} and the client read {@code reasoning_content} — eighty-four calls
 * produced eighty-four blanks before anyone noticed. The token counts were reachable only through a
 * listener context. The retry had to test a typed hierarchy AND a raw status code, because the
 * mapping between them ran in an {@code internal} package with no promise it had run at all. All
 * three are ordinary fields here, read off the wire by the code that wanted them.
 *
 * <p>STREAMED, ALWAYS, EVEN THOUGH {@link Chat} IS BLOCKING. A request here is not one generation:
 * it is the whole accumulated conversation re-prefilled, and it grows with every tool call the
 * agent has already made. While the server prefills, a non-streaming client sends nothing and
 * receives nothing, so an idle connection and a slow answer are indistinguishable to it and to
 * every proxy between them. The only way to make that wait finite is a total cap, and a total cap
 * kills work that is progressing — a survey that was working died at twelve minutes, and the retry
 * then spent the same twelve minutes twice more. Streaming replaces the question with a better one:
 * the guard is TIME SINCE THE LAST TOKEN, so a generation that takes an hour is never interrupted
 * and a socket that has actually died is noticed in {@link Watch#stall()}.
 */
public final class Wire implements Chat {

    /**
     * HOW OFTEN THE WAITING THREAD WAKES TO CHECK THE GUARDS. Not a timeout on anything.
     *
     * <p>A FRACTION OF THE STALL BOUND RATHER THAN A CONSTANT, and the constant it replaces was
     * wrong in a way only a test could show. Fifteen seconds is a sensible tick against the shipped
     * twenty-minute stall — it notices a dead socket at most fifteen seconds late, which nobody
     * cares about. But it is also the FLOOR on how fast any stall can be noticed, so a consumer who
     * sets a two-second patience gets a guard that fires at fifteen, and the setting they were
     * given quietly does not work. The port surfaced this as slow tests: proving the stall guard
     * cost one tick, so two test classes spent 45 seconds between them waiting for a constant.
     *
     * <p>A quarter of the stall notices it within 25% of the bound at any scale. Clamped below at
     * ten milliseconds so a pathological setting cannot turn the loop into a spin, and above at
     * fifteen seconds so the shipped configuration wakes exactly as often as it used to.
     */
    private static Duration tick(Watch watch) {
        Duration quarter = watch.stall().dividedBy(4);
        if (quarter.compareTo(Duration.ofSeconds(15)) > 0) {
            return Duration.ofSeconds(15);
        }
        return quarter.compareTo(Duration.ofMillis(10)) < 0 ? Duration.ofMillis(10) : quarter;
    }

    /** The end of the body, as a marker the queue can carry alongside lines and failures. */
    private static final Object END = new Object();

    private final Endpoint endpoint;
    private final Sampling sampling;
    private final Watch watch;
    private final boolean thinking;
    private final Trace trace;
    private final Listening listening;
    private final HttpClient http;

    /**
     * THE CLOCK, HANDED IN, because the two guards in this class are ENTIRELY about time and were
     * the only things in the library that took their own.
     *
     * <p>{@link Retrying} has taken a {@link Now} since 0.8.0, and the difference shows in what its
     * tests can say: the whole ten-attempt schedule — eighty-eight seconds of waiting — is asserted
     * in 47 milliseconds. The stall and the ceiling could only be reached by genuinely waiting, so
     * their tests grew sleeps, a latch nobody counts down, and a {@code @Timeout} on every method
     * because the failure mode was a hang rather than an assertion. One of them took 1.6 seconds to
     * assert a single comparison.
     *
     * <p>Worse than slow, they had to LIE ABOUT THE REQUIREMENT. A test for a three-hour ceiling
     * cannot wait three hours, so it asserted a 700-millisecond one — a bound chosen for the
     * patience of whoever runs the suite rather than to describe anything. With the clock handed in
     * the test says three hours, because it can.
     */
    private final Now now;

    /** Everything from the environment, which is what {@link Model} hands most callers. */
    public static Chat to(Endpoint endpoint) {
        return new Wire(endpoint, Sampling.fromEnv(), Watch.fromEnv(), true, null, Now.SYSTEM,
                Keeping.fromEnv());
    }

    /** Everything chosen. Nothing here reads the environment. */
    public static Chat to(Endpoint endpoint, Sampling sampling, Watch watch, boolean thinking,
                          Trace trace) {
        return to(endpoint, sampling, watch, thinking, trace, Keeping.shipped());
    }

    /**
     * THE SAME, WITH THE RECORD'S OWN BOUNDS CHOSEN.
     *
     * <p>{@link Listening} held three literals no caller could reach, and the one that mattered cut
     * 41% of a consumer's tool results while saying only {@code (truncated)}. This is the door that
     * makes them a value; {@link Keeping#everything()} is the answer to a consumer who wants the
     * exchange whole.
     */
    public static Chat to(Endpoint endpoint, Sampling sampling, Watch watch, boolean thinking,
                          Trace trace, Keeping keeping) {
        return new Wire(endpoint, sampling, watch, thinking, trace, Now.SYSTEM, keeping);
    }

    /**
     * THE SAME, WITH THE CLOCK CHOSEN — and public, which is the whole point of it existing.
     *
     * <p>Six times in this library a value was made injectable for its own tests and left
     * unreachable from outside, and every one of those was reported by a consumer rather than found
     * here. A clock that only this package could hand in would be the seventh, and it would be the
     * worst of them: {@link Retrying} already takes one, so a consumer wrapping their own client
     * can pin the retry's schedule and not the liveness bounds underneath it.
     */
    public static Chat to(Endpoint endpoint, Sampling sampling, Watch watch, boolean thinking,
                          Trace trace, Now now) {
        return to(endpoint, sampling, watch, thinking, trace, now, Keeping.shipped());
    }

    /** Everything chosen, the clock and the record's bounds included. */
    public static Chat to(Endpoint endpoint, Sampling sampling, Watch watch, boolean thinking,
                          Trace trace, Now now, Keeping keeping) {
        return new Wire(endpoint, sampling, watch, thinking, trace, now, keeping);
    }

    Wire(Endpoint endpoint, Sampling sampling, Watch watch, boolean thinking, Trace trace) {
        this(endpoint, sampling, watch, thinking, trace, Now.SYSTEM);
    }

    Wire(Endpoint endpoint, Sampling sampling, Watch watch, boolean thinking, Trace trace,
         Now now) {
        this(endpoint, sampling, watch, thinking, trace, now, Keeping.shipped());
    }

    Wire(Endpoint endpoint, Sampling sampling, Watch watch, boolean thinking, Trace trace,
         Now now, Keeping keeping) {
        this.now = now;
        this.endpoint = endpoint;
        this.sampling = sampling;
        this.watch = watch;
        this.thinking = thinking;
        // COERCED ONCE HERE, NOT CHECKED AT EVERY USE. Trace.quiet() was added to ratchet-core
        // because three classes guarded against a null trace in three different ways, and this was
        // one of them — a null listener, then a null check at each of its three call sites. Four
        // lines that can only ever be wrong, for the one caller that passes no trace.
        this.trace = trace == null ? Trace.quiet() : trace;
        this.listening = new Listening(this.trace, keeping);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(endpoint.secure() ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * THE TRANSPORT'S OWN TIMEOUT, DERIVED SO THAT IT CANNOT BE THE GUARD THAT FIRES.
     *
     * <p>It used to be its own setting, four hours by default, with a paragraph explaining that it
     * must always sit above {@link Watch#ceiling()} — which is a rule a deployment can break by
     * editing one variable, and the cost of breaking it is a hard cut through a generation that was
     * working. Deriving it from the ceiling makes the rule structural: whichever guard is correct,
     * the one that fires is the one watching for SILENCE, not the one counting elapsed time.
     */
    private Duration patience() {
        return watch.ceiling().plusHours(1);
    }

    @Override
    public Reply answer(Ask ask) {
        long began = now.millis();
        listening.sending(ask);
        try {
            Reply reply = call(ask);
            listening.back(ask, reply, now.millis() - began);
            return reply;
        } catch (RuntimeException failed) {
            listening.failed(ask, failed, now.millis() - began);
            throw failed;
        }
    }

    private Reply call(Ask ask) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(chatUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(patience())
                .POST(HttpRequest.BodyPublishers.ofString(body(ask, endpoint, sampling, thinking)));
        if (!endpoint.key().isEmpty()) {
            request.header("Authorization", "Bearer " + endpoint.key());
        }

        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException dropped) {
            // Retriable by default, which is what transportFailures() decides. Wrapped rather than
            // rethrown as a checked exception because Chat is a functional interface and a checked
            // throw would put a try/catch in every test that implements one.
            throw new IllegalStateException("could not reach " + endpoint.base() + ": "
                    + dropped.getMessage(), dropped);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before the model answered", stopping);
        }

        if (response.statusCode() != 200) {
            throw new Refused(response.statusCode(),
                    response.body().collect(Collectors.joining("\n")));
        }
        return read(response.body());
    }

    /**
     * THE READ LOOP AND ITS TWO GUARDS.
     *
     * <p>The body is drained on its own thread and the lines arrive through a queue, so that the
     * thread doing the waiting is free to notice that nothing has arrived. A stream consumed
     * directly blocks inside the JDK with no way to ask how long it has been blocked, which is the
     * whole reason the guard cannot live where the reading happens.
     *
     * <p>VISIBLE TO A TEST, AND THAT IS THE POINT OF SPLITTING IT HERE. The class this replaces was
     * provable because it wrapped a model handed in from outside; this one owns its socket, and a
     * guard that can only be reached through a socket is a guard nobody asserts. A test feeds this
     * the frames directly — including a stream that stops producing, which no real endpoint will do
     * on request — and a separate test drives the whole class against a {@code com.sun.net.httpserver}
     * on localhost, because a unit that passes in isolation and is wired to nothing is the failure
     * this library exists to argue against.
     */
    Reply read(Stream<String> body) {
        BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                body.forEach(lines::add);
                lines.add(END);
            } catch (RuntimeException stopped) {
                lines.add(stopped);
            }
        }, "ratchet-wire-reader");
        reader.setDaemon(true);
        reader.start();

        StringBuilder said = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, Building> calls = new LinkedHashMap<>();
        Reasoning watching = new Reasoning(trace);
        String finish = "";
        Spend spend = Spend.NONE;

        long lastToken = now.millis();
        long deadline = now.millis() + watch.ceiling().toMillis();
        try {
            while (true) {
                Object next;
                try {
                    next = lines.poll(tick(watch).toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while streaming", stopping);
                }
                if (next == END) {
                    break;
                }
                if (next instanceof RuntimeException broken) {
                    watching.ended(finish);
                    watching.flush();
                    throw broken;
                }
                // THE CEILING IS CHECKED ON EVERY PASS AND THE STALL ONLY ON SILENCE, because
                // they ask opposite questions. This code had both of them inside the branch below,
                // which is entered only when the poll TIMES OUT — so the ceiling could fire only on
                // a stream that had gone quiet, and GaveUp's whole reason for existing, in its own
                // javadoc, is that "it fires on a stream that IS producing and has been for hours".
                // The one case it is for was the one case it could not see: at the shipped tick of
                // fifteen seconds, a runaway generation emitting a token a second ran past the
                // three-hour ceiling untouched, holding the lane the ceiling exists to reclaim.
                if (now.millis() > deadline) {
                    keep(watching, finish, "still streaming after " + said(watch.ceiling()));
                    throw new GaveUp("still streaming after " + said(watch.ceiling())
                            + "; giving the lane back");
                }
                // TIME SINCE THE LAST TOKEN, WHICH IS WHAT THIS CLASS SAYS IT MEASURES AND WAS
                // NOT. This lived inside the branch below, entered only when the poll TIMES OUT, so
                // it was really time since the last LINE. A connection sending lines that are not
                // tokens therefore never stalled — and that is not a hypothetical shape, it is what
                // a keep-alive is: SSE comment frames and blank separators exist precisely so an
                // idle connection keeps arriving, and every proxy in the path may add them. Such a
                // stream ran to the three-hour ceiling instead of stopping at twenty minutes, which
                // is the ceiling doing the stall's job nine times slower.
                //
                // `lastToken` moves only on a data frame, so a stream that is genuinely producing
                // still never trips this.
                long quiet = now.millis() - lastToken;
                if (quiet > watch.stall().toMillis()) {
                    String how = said(Duration.ofMillis(quiet));
                    keep(watching, finish, "no token for " + how);
                    throw new IllegalStateException("no token for " + how
                            + ": the connection is not producing");
                }
                if (next == null) {
                    continue;
                }

                String line = (String) next;
                if (!line.startsWith("data:")) {
                    // Comments, event names and the blank lines between frames. SSE allows all of
                    // them and this endpoint sends the blanks.
                    continue;
                }
                lastToken = now.millis();
                String data = line.substring(5).strip();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    continue;
                }
                String usage = Json.part(data, "usage");
                if (!usage.isEmpty() && !usage.equals("null")) {
                    spend = new Spend(Json.number(usage, "prompt_tokens", 0),
                            Json.number(usage, "completion_tokens", 0));
                }
                String choices = Json.part(data, "choices");
                if (choices.isEmpty() || choices.equals("[]")) {
                    continue;
                }
                String reason = Json.read(choices, "finish_reason");
                if (!reason.isBlank()) {
                    finish = reason;
                }
                String delta = Json.part(choices, "delta");
                if (delta.isEmpty()) {
                    continue;
                }
                said.append(Json.read(delta, "content"));
                watching.said(Json.read(delta, "content"));
                // WHATEVER THE SERVER CALLS IT. This one writes `reasoning`; the specification that
                // was followed by the client this replaces says `reasoning_content`. Reading both
                // costs nothing and is the entire bug that took a transport interceptor to fix.
                String thought = Json.read(delta, "reasoning") + Json.read(delta, "reasoning_content");
                reasoning.append(thought);
                watching.reasoned(thought);
                collect(calls, Json.part(delta, "tool_calls"));
            }
        } finally {
            body.close();
        }

        watching.ended(finish);
        Ending ending = Ending.of(finish);
        // AN EMPTY ANSWER THAT RAN OUT OF ROOM IS NOT AN ANSWER, and the finish reason has been on
        // the response all along. Content that is blank because the budget went on reasoning is a
        // truncation, and saying so is the difference between a run that stops and a run that
        // quietly writes an empty string over real work.
        if (said.toString().isBlank() && calls.isEmpty() && ending == Ending.LENGTH) {
            watching.flush();
            throw new Truncated("the model reached the token limit before writing an answer; all of "
                    + "it went on reasoning. Raise the completion budget or lower the reasoning one "
                    + "(RATCHET_THINKING_TOKENS), or turn thinking off.");
        }
        watching.flush();
        List<Called> asked = new ArrayList<>();
        calls.values().forEach(c -> asked.add(c.done()));
        // A CALL CUT IN HALF IS NOT A CALL. The guard above asks whether the turn produced nothing;
        // this asks whether what it produced was FINISHED, which is a different question and the one
        // the old check could not answer. A generation that hits the wall mid-call leaves arguments
        // that are not valid JSON, and running them executes a tool on a fragment of its request;
        // worse, the malformed call goes into the conversation, is re-sent on every later turn, and
        // is re-parsed by the server every time. One consumer lost lanes permanently that way, and a
        // shared engine spent three hours wedged in a tool-call parser looping on such a stump.
        //
        // DROPPED, NOT THROWN. Throwing ends the lane, which is the cost being removed rather than
        // relocated. The finish reason stays LENGTH because it is the only evidence of which turns
        // hit the wall, and relabelling it would destroy the signal that says so.
        //
        // THE ORDER IS LOAD-BEARING. A blank-content turn whose only output was a tool call
        // satisfies every clause of the guard above the moment its call is dropped, so dropping
        // first would turn exactly this case into the Truncated it is meant to avoid. The refusal is
        // decided on the turn as received; the drop is applied after.
        if (ending == Ending.LENGTH && !asked.isEmpty()) {
            return new Reply(said.toString(), reasoning.toString(), List.of(), ending, spend,
                    asked.size());
        }
        return new Reply(said.toString(), reasoning.toString(), asked, ending, spend);
    }

    /**
     * A DURATION IN A UNIT THAT SURVIVES BEING SMALL.
     *
     * <p>These were {@code millis / 60_000} minutes and {@code ceiling.toHours()} hours, which is
     * fine for the shipped twenty minutes and three hours and wrong for everything ratchet#7 made
     * possible: since {@link Watch} became a per-call value, a consumer with a two-minute patience
     * was told "no token for 0 minutes" and a sub-hour ceiling reported "still streaming after 0h".
     * A bound that reports itself as zero reads as a bug in the guard rather than a fact about the
     * connection.
     */
    private static String said(Duration d) {
        // MILLISECONDS ARE A UNIT TOO, and leaving them out reproduced the bug this method exists
        // to fix, one step down. `toHours()` made a sub-hour ceiling announce itself as "0h"; this
        // had no branch below a second, so a sub-second bound announced itself as "0s" — and Watch
        // asks only that the stall be positive, so any consumer may set one.
        //
        // The test written alongside the first fix did not catch it, and how it failed is the
        // lesson: it asserted the message did NOT contain "0 minutes" or "0m". "0s" contains
        // neither, so it passed on the exact output it existed to forbid. A guard written as a list
        // of the shapes already seen cannot see the next one; asserting what the message SAYS
        // rather than what it avoids can.
        long millis = d.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        long seconds = d.toSeconds();
        if (seconds < 90) {
            return seconds + "s";
        }
        return d.toMinutes() < 90 ? d.toMinutes() + "m" : d.toHours() + "h";
    }

    /** A guard is ending this call, so whatever the stream had already said becomes the record. */
    private void keep(Reasoning watching, String finish, String why) {
        watching.ended(finish.isBlank() ? why : finish);
        watching.abandoned();
        watching.flush();
    }

    /** One tool call under construction; the name arrives once and the arguments in fragments. */
    private static final class Building {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        Called done() {
            return new Called(id, name, arguments.toString());
        }
    }

    /**
     * TOOL CALLS ARRIVE IN PIECES AND THE INDEX IS WHAT HOLDS THEM TOGETHER.
     *
     * <p>The first delta for a call carries its id, its name and usually nothing else; every delta
     * after it carries only the index and the next fragment of the arguments JSON. Two calls in one
     * turn interleave, which is exactly why the index cannot be read with a flat scan: a chunk has
     * an {@code index} at the choice level too, it comes first, and it is always zero.
     */
    private static void collect(Map<Integer, Building> calls, String toolCalls) {
        if (toolCalls.isEmpty() || toolCalls.equals("null")) {
            return;
        }
        for (String one : entries(toolCalls)) {
            Building building = calls.computeIfAbsent(Json.number(one, "index", 0),
                    at -> new Building());
            String id = Json.read(one, "id");
            if (!id.isBlank()) {
                building.id = id;
            }
            String function = Json.part(one, "function");
            if (function.isEmpty()) {
                continue;
            }
            String name = Json.read(function, "name");
            if (!name.isBlank()) {
                building.name = name;
            }
            building.arguments.append(Json.read(function, "arguments"));
        }
    }

    /** The objects of a JSON array, split at the top level only. */
    private static List<String> entries(String array) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = -1;
        boolean inString = false;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) {
                    from = i;
                }
            } else if (c == '}' && --depth == 0 && from >= 0) {
                out.add(array.substring(from, i + 1));
            }
        }
        return out;
    }

    private String chatUrl() {
        String base = endpoint.base();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/chat/completions";
    }

    /**
     * THE WHOLE REQUEST BODY, AS A PURE FUNCTION, so a test can assert what goes out.
     *
     * <p>What a request actually contained used to be decided inside a builder in another artifact,
     * which meant the only way to check it was to send one and watch the server's reaction — and
     * this endpoint accepts unknown fields silently, so watching proves nothing. Every parameter
     * here was measured on the live endpoint before it was trusted; that measurement is only
     * repeatable because this string is reachable without a socket.
     *
     * <p>{@code stream_options.include_usage} is the one field with no predecessor. It is what makes
     * {@link Spend} arrive at all, in a final chunk whose {@code choices} array is empty.
     */
    static String body(Ask ask, Endpoint endpoint, Sampling sampling, boolean thinking) {
        List<String> fields = new ArrayList<>();
        fields.add(Json.field("model", Json.string(endpoint.model())));
        fields.add(Json.field("stream", "true"));
        fields.add(Json.field("stream_options", "{\"include_usage\":true}"));
        // OMITTED RATHER THAN SENT AS ZERO. Zero on this field means "no budget" to this record
        // and "answer in nothing" to a server, which are opposite instructions.
        if (sampling.maxTokens() > 0) {
            fields.add(Json.field("max_tokens", String.valueOf(sampling.maxTokens())));
        }
        fields.add(Json.field("temperature", String.valueOf(sampling.temperature())));
        fields.add(Json.field("messages",
                Json.array(ask.messages(), Said::wire)));
        // AN EMPTY SET IS NOT THE SAME AS NO TOOLS. An agent with nothing to call is a legitimate
        // shape, and a server handed an empty tools array is entitled to complain about it.
        if (!ask.tools().isEmpty()) {
            fields.add(Json.field("tools", Json.array(ask.tools(), t -> Json.object(
                    Json.field("type", Json.string("function")),
                    Json.field("function", Json.object(
                            Json.field("name", Json.string(t.name())),
                            Json.field("description", Json.string(t.description())),
                            Json.field("parameters", t.schema())))))));
        }
        // Qwen's own field, and it bounds the REASONING rather than the output. Measured before it
        // was wired, because a parameter being ACCEPTED and being HONOURED are different claims and
        // this server drops unknown fields in silence: budget 300 gave 1,015 characters of thinking
        // and an answer; unbounded gave no reply within 300 seconds.
        // NOT GATED ON `thinking`, AND A TEST CAUGHT THIS BEING GATED DURING THE PORT. The two
        // fields were added on different days for different reasons and they are not alternatives:
        // the budget is what the server honours if the template switch is ignored — which a proxy
        // that drops unknown fields will do silently — so dropping it for the non-thinking agents
        // would leave exactly those calls with no bound on the reasoning at all.
        if (sampling.thinks()) {
            fields.add(Json.field("thinking_token_budget", String.valueOf(sampling.thinkingTokens())));
        }
        if (!thinking) {
            // The server template's own switch, not a prompt asking for brevity: an instruction to
            // answer first was measured to increase the runaway rate, not reduce it.
            fields.add(Json.field("chat_template_kwargs", "{\"enable_thinking\":false}"));
        }
        return Json.object(fields.toArray(new String[0]));
    }
}
