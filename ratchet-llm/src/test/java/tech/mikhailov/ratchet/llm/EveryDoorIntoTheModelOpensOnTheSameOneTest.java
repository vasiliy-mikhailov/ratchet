package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY DOOR INTO THIS LIBRARY MUST OPEN ON A MODEL THAT ANSWERS, AND ON THE SAME MODEL.
 *
 * <p>{@link Model} is the factory every consumer enters through, and it is the least-tested class
 * in the module: 28% on the stronger mutation set, with fourteen of its forty mutants NEVER
 * EXECUTED BY ANY TEST. Thirteen of the fourteen are the doors themselves — five {@code forProducer}
 * overloads, five {@code forCritic}, three {@code forRetry} — and the mutation is the same one every
 * time: <em>return null instead of a model</em>. Any one of those doors could have shipped hollow
 * and the whole suite would have stayed green, because the only doors any test had ever opened were
 * {@code forProducer(trace, endpoint, retry, sampling)} and {@code wrap(client, trace, retry)}. The
 * consumer finds the rest, at their first call, as a {@link NullPointerException} thrown from inside
 * a library they adopted yesterday.
 *
 * <p>AND A HOLLOW DOOR IS NOT THE ONLY WAY ONE CAN BE WRONG. A door that hands back a working model
 * built out of the WRONG CONFIGURATION is the more expensive shape, because nothing fails — it is
 * the mutation ratchet#5 found in {@link Wire}'s own door. PIT does not generate it: swapping an
 * argument for {@code Sampling.fromEnv()} is not a mutation operator. So two of them were written by
 * hand and measured here, and neither died until the assertions below were widened, because a test
 * that hands a door the environment's own defaults cannot tell whether the door used what it was
 * given or read the environment for itself.
 *
 * <p>WHETHER THE MODEL THINKS IS DECIDED ON ONE LINE, AND NOTHING WATCHED IT. {@code build} computes
 * {@code thinking && sampling.thinks() && Env.flag(...)}; PIT replaced each of those three with a
 * constant, in both directions, and all six survived. The suite could not tell a producer that
 * thinks from one that does not — the same shape as the temperature mutation
 * {@code AConsumerBringsItsOwnModelTest} was written against, one field over. What it costs is in
 * {@link Model}'s own javadoc: one closed-list critic answer is 537 completion tokens with thinking
 * on and 3 with it off, and the reasoning is the thing an agent produces that a judgement can be
 * audited from. A build that quietly stopped asking for it would read as a cost saving and a slight
 * drop in quality, for as long as nobody went looking.
 *
 * <p>AND THE DRAW COULD HAVE BEEN A CONSTANT. {@code theProductionDrawStaysInsideItsMinute} calls
 * {@link Model#jitterSeconds()} five hundred times and asserts each result is in {@code [0,60)} —
 * which zero is, five hundred times out of five hundred. So {@code Model.draw} replaced by
 * {@code return 0} passed that test, and eight lanes of a sweep that failed together would return
 * together, which is the one thing the draw exists to stop. The tests below assert that it VARIES,
 * both on its own and through the policy {@link Retry#fromEnv} actually installs.
 *
 * <p>WHAT THIS FILE KILLS, COUNTED RATHER THAN CLAIMED: each mutation was applied to a scratch copy
 * of {@link Model}, compiled, and this file run against it. Seventeen of the twenty-nine live
 * mutants die, along with the two hand-written configuration swaps — all eight doors that can be entered without an endpoint in the environment, five of
 * the six on the thinking line, and all four on the draw. Twelve do not, and none of them is
 * equivalent: six are the doors that call {@link Endpoint#fromEnv()} and cannot be entered without
 * {@code RATCHET_BASE} set, five are the name chain below, and the twelfth is the thinking flag
 * defaulting to true when nothing says otherwise, which is what it already does here.
 *
 * <p>WHAT THIS FILE CANNOT SAY, and it is the half the class comment cares most about: the
 * {@code RATCHET_} then {@code OC_} then {@code BJV_} chain in {@link Model#setting}. A JVM cannot
 * set its own environment, {@code Env.get} reads {@code System.getenv} itself, and no name with any
 * of those three prefixes is set in a test run — so every assertion available here is about the
 * fallback end of the chain, which is what a deployment that has set nothing gets. The precedence
 * itself, the thing the promise is made of, has no test in this repository and cannot have one until
 * the truth rules exist as functions of a string. {@code AMissingSettingIsNotAValueTest} in
 * ratchet-core reaches the same wall from the other side and names the seam that would end it.
 *
 * <p>So every assertion here about a value that came from the environment reads the fallback
 * branch, on the same premise {@code theDefaultIsTenAttempts} already runs on: nothing prefixed
 * {@code RATCHET_}, {@code OC_} or {@code BJV_} is set in this JVM, so the documented default is in
 * force. Set {@code RATCHET_THINKING=0} in a shell and this file goes red — which is the honest
 * behaviour, because that shell really does build a different model.
 */
class EveryDoorIntoTheModelOpensOnTheSameOneTest {

    @Test
    void aProducerThinksAndTheRequestSaysNothingToStopIt() throws IOException {
        // THE DEFAULT, TAKEN OFF THE SOCKET. Thinking is the product here: the endpoint runs a
        // reasoning parser, Wire reads the reasoning off the stream, and Insisting re-asks a blank
        // rather than reading it as agreement. Hardcoding this decision to false would have left
        // every test in the module green — it would show up as a sweep that suddenly costs a
        // hundredth of what it did, with judgements nobody can audit afterwards.
        try (Loopback endpoint = new Loopback()) {
            String sent = endpoint.sentBy(Model.forProducer(QUIET, endpoint.where(), Retry.none(),
                    Sampling.deterministic(), Watch.shipped()));

            assertFalse(sent.contains("enable_thinking"),
                    "a producer must not carry the template's off switch at all: " + sent);
            assertTrue(sent.contains("\"thinking_token_budget\":4000"),
                    "and the reasoning it does is bounded, or the answer never arrives: " + sent);
        }
    }

    @Test
    void aReAskTurnsThinkingOffWithTheServersOwnSwitch() throws IOException {
        // THE ONE PLACE THE ANSWER IS NO, and the measurement behind it: the answer-first
        // instruction this replaced ran 80% runaway against a 62.5% control, so the retry was
        // making the second attempt worse than the first. Thinking off measured 0 of 10 runaway.
        // A forRetry that forgot to say so is a re-ask that re-enters the cycle it was sent to
        // escape, and the caller sees two blanks instead of one.
        try (Loopback endpoint = new Loopback()) {
            String sent = endpoint.sentBy(Model.forRetry(QUIET, endpoint.where(), Retry.none()));

            assertTrue(sent.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"),
                    "the server template's own switch, not a prompt asking for brevity: " + sent);
            assertTrue(sent.contains("thinking_token_budget"),
                    "AND THE BUDGET SURVIVES THE SWITCH. They are not alternatives: a proxy that "
                            + "drops unknown fields drops the switch silently, and the budget is "
                            + "then the only thing bounding the reasoning: " + sent);
        }
    }

    @Test
    void aSamplingWithNoReasoningBudgetTurnsTheSwitchOffToo() throws IOException {
        // thinks() IS A CONSUMER SAYING NO, AND IT HAS TO REACH THE SERVER. Sampling's own
        // measurement is what makes this the expensive mutation: with no reasoning budget in force
        // the same prompt gave 11,700 characters of thinking and an EMPTY answer, finish reason
        // length. So a consumer that sets the budget to zero and is still given a thinking model
        // does not get a slower answer, it gets no answer — and Insisting then re-asks a question
        // that was never going to be answered.
        try (Loopback endpoint = new Loopback()) {
            String sent = endpoint.sentBy(Model.forProducer(QUIET, endpoint.where(), Retry.none(),
                    Sampling.deterministic().withThinkingTokens(0)));

            assertTrue(sent.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"),
                    "a zero reasoning budget must switch the template off, not leave it unbounded: "
                            + sent);
            assertFalse(sent.contains("thinking_token_budget"),
                    "and no budget field at all when there is no thinking to bound: " + sent);
        }
    }

    @Test
    void everyDoorThatDoesNotNeedTheEnvironmentOpensOnAModelThatAnswers() throws IOException {
        // THE THIRTEEN NULLS. Each line below is a door no test had ever opened, driven end to end
        // against a loopback endpoint: a door that returned null fails here at answer(), which is
        // where the consumer would have found it.
        //
        // AND THEY ALL SEND THE SAME REQUEST, which is the assertion worth having. "Producers and
        // critics share a configuration" is the first sentence of this class and it was a comment
        // rather than a fact. Six doors, one byte sequence.
        try (Loopback endpoint = new Loopback()) {
            Endpoint where = endpoint.where();
            Map<String, String> sentBy = new LinkedHashMap<>();

            sentBy.put("forProducer(trace, endpoint)",
                    endpoint.sentBy(Model.forProducer(QUIET, where)));
            sentBy.put("forCritic(trace, endpoint)",
                    endpoint.sentBy(Model.forCritic(QUIET, where)));
            sentBy.put("forProducer(trace, endpoint, retry)",
                    endpoint.sentBy(Model.forProducer(QUIET, where, Retry.none())));
            sentBy.put("forCritic(trace, endpoint, retry)",
                    endpoint.sentBy(Model.forCritic(QUIET, where, Retry.none())));
            sentBy.put("forCritic(trace, endpoint, retry, sampling)",
                    endpoint.sentBy(Model.forCritic(QUIET, where, Retry.none(),
                            Sampling.deterministic())));
            sentBy.put("forProducer(trace, endpoint, retry, sampling, watch)",
                    endpoint.sentBy(Model.forProducer(QUIET, where, Retry.none(),
                            Sampling.deterministic(), Watch.shipped())));

            assertEquals(1, Set.copyOf(sentBy.values()).size(),
                    "the same question, the same endpoint and the same sampling through six doors "
                            + "must put the same request on the socket: " + sentBy);

            // AND THE AGREEMENT MUST NOT BE "EVERY DOOR READ THE ENVIRONMENT". Four of the six take
            // no Sampling and resolve Sampling.fromEnv(); the other two were handed
            // Sampling.deterministic(), which in a JVM with nothing set is the SAME VALUE — so a
            // door that quietly dropped what it was given and read the environment instead would
            // have satisfied every byte of the assertion above. Measured: forCritic rewritten to
            // pass Sampling.fromEnv() in place of its argument leaves the six-door comparison
            // green. So the two doors that take a Sampling are driven again with one no environment
            // can produce, and the caller's numbers have to arrive on the socket.
            Sampling asked = Sampling.asTheModelRequires(0.7).withThinkingTokens(256)
                    .withMaxTokens(2048);
            String byCritic = endpoint.sentBy(Model.forCritic(QUIET, where, Retry.none(), asked));
            String byProducer = endpoint.sentBy(
                    Model.forProducer(QUIET, where, Retry.none(), asked, Watch.shipped()));

            assertEquals(byCritic, byProducer,
                    "the two doors that take a Sampling must still agree once the Sampling is one "
                            + "the environment would never have supplied");
            assertTrue(byCritic.contains("\"temperature\":0.7")
                            && byCritic.contains("\"max_tokens\":2048")
                            && byCritic.contains("\"thinking_token_budget\":256"),
                    "every number the caller chose has to reach the server: a door that swapped in "
                            + "the environment's own Sampling would answer at temperature zero with "
                            + "sixteen thousand tokens, and a consumer running two models in one "
                            + "process would get one of them twice: " + byCritic);
        }
    }

    @Test
    void theWatchAConsumerHandsInIsThePatienceThatIsActuallyEnforced() throws IOException {
        // THE ONE DOOR WHOSE WHOLE REASON FOR EXISTING IS INVISIBLE ON THE WIRE. The five-argument
        // forProducer was added for ratchet#7 — a consumer whose patience is a setting on a page —
        // and the Watch it takes appears nowhere in the request body, so no comparison of bytes can
        // tell it from a door that ignored the argument and called Watch.fromEnv(). Measured: that
        // exact swap leaves every other test in this file green. What it would cost the ratchet#7
        // consumer is the entire feature — a page that offers a two-second patience gets the
        // shipped twenty-minute stall and a three-hour ceiling, so the setting they were given is
        // decorative and the tab hangs.
        //
        // So the Watch is asserted where it is observable: against a server that keeps the socket
        // alive and never finishes. Both of the caller's bounds are far below the drip's total, and
        // the door that honours them gives up in well under a second; the door that read the
        // environment instead sits there until the server runs out of frames.
        try (Dripping endpoint = new Dripping()) {
            Chat door = Model.forProducer(QUIET, endpoint.where(), Retry.none(),
                    Sampling.deterministic(),
                    new Watch(Duration.ofMillis(400), Duration.ofMillis(600)));

            long began = System.currentTimeMillis();
            RuntimeException gaveUp = assertThrows(RuntimeException.class, () -> door.answer(ask()),
                    "the caller's patience ran out two frames ago and the call is still waiting: "
                            + "the Watch handed to this door is not the Watch being enforced");
            long took = System.currentTimeMillis() - began;

            assertTrue(gaveUp instanceof GaveUp || gaveUp.getMessage().contains("no token"),
                    "and it must end on one of the two liveness guards rather than on the socket "
                            + "breaking: " + gaveUp);
            assertTrue(took < Dripping.WHOLE_DRIP.toMillis(),
                    "it must end BEFORE the server would have finished on its own, or the guard "
                            + "proved nothing: " + took + "ms");
        }
    }

    @Test
    void theReAskDoorDiffersFromTheProducerDoorByTheSwitchAndNothingElse() throws IOException {
        // The other half of the same claim. forRetry is the ONE door that is documented to differ,
        // and it must differ by exactly the thing it is documented to differ by: a re-ask that also
        // quietly dropped the tools, the temperature or the completion budget would be a second
        // configuration nobody chose, reached only on the attempt that already went wrong.
        try (Loopback endpoint = new Loopback()) {
            Endpoint where = endpoint.where();

            String producer = endpoint.sentBy(Model.forProducer(QUIET, where, Retry.none()));
            String reAsk = endpoint.sentBy(Model.forRetry(QUIET, where, Retry.none()));

            assertEquals(producer,
                    reAsk.replace(",\"chat_template_kwargs\":{\"enable_thinking\":false}", ""),
                    "take the thinking switch back out of the re-ask and what is left must be the "
                            + "producer's own request, field for field:\n" + producer + "\n" + reAsk);
        }
    }

    @Test
    void theDoorThatReadsTheEnvironmentInstallsTheSameJudgementAsTheOneThatDoesNot() {
        // Model.wrap(client, trace) is the fourteenth never-executed mutant and the one a consumer
        // reaches for through Retrying.on. What it must not be is a door that hands back the bare
        // client: that looks identical until the endpoint hiccups, and then a whole stage fails on
        // a dropped socket that one more request would have fixed.
        AtomicInteger asked = new AtomicInteger();
        Chat badKey = ask -> {
            asked.incrementAndGet();
            throw new Refused(401, "invalid api key");
        };

        Refused refused = assertThrows(Refused.class,
                () -> Model.wrap(badKey, QUIET).answer(ask()));

        assertEquals(401, refused.status(), "the status reaches the caller intact");
        assertEquals(1, asked.get(),
                "and the production predicate came with the loop: a bad key is not a hiccup, and "
                        + "asking again would be ten refusals with eighty-eight seconds of waiting "
                        + "spread through them");
        assertEquals("hello", Model.wrap(
                        ask -> new Reply("hello", "", List.of(), Ending.STOPPED, Spend.NONE), QUIET)
                .answer(ask()).said(), "and an ordinary answer still passes straight through");
    }

    @Test
    void theDrawIsADrawAndNotAConstant() {
        // WHAT THE EXISTING TEST COULD NOT SEE. Five hundred draws asserted to be inside [0,60)
        // are five hundred assertions a constant zero satisfies. This is the other half: over two
        // hundred draws the same number cannot come back every time — the chance of that with a
        // sixty-wide spread is 60^-199 — so a draw that stopped drawing fails here.
        Set<Integer> drawn = new HashSet<>();
        for (int lane = 0; lane < 200; lane++) {
            drawn.add(Model.draw(60));
        }

        assertTrue(drawn.size() > 1,
                "two hundred lanes drew the same second, so the schedule is not being spread at "
                        + "all: " + drawn);
        assertTrue(drawn.stream().allMatch(second -> second >= 0 && second < 60),
                "and every draw is inside the minute somebody chose: " + drawn);

        // jitterSeconds() has no production caller left — Retry.fibonacciSeconds takes the spread
        // as a number and calls draw itself — so what is asserted here is that the pair still
        // means what its name says, for the next caller rather than for a live path.
        Set<Integer> throughTheSpread = new HashSet<>();
        for (int lane = 0; lane < 200; lane++) {
            throughTheSpread.add(Model.jitterSeconds());
        }

        assertTrue(throughTheSpread.size() > 1,
                "jitterSeconds() is draw(jitterSpread()), and a spread that collapsed to zero "
                        + "would show up here as one number and nowhere else: " + throughTheSpread);
    }

    @Test
    void aSpreadOfNothingIsNoDrawAtAllAndNotACrash() {
        // The guard is not decoration: ThreadLocalRandom.nextInt(0) THROWS. Retry.fibonacciSeconds
        // documents a spread of zero as the right choice for a single lane, and without this line
        // that choice turns the first transient failure into an IllegalArgumentException raised
        // while computing the wait — outside the try, so it escapes the retry loop rather than
        // being retried by it. A consumer who asked for no jitter would lose the retry entirely.
        assertEquals(0, Model.draw(0), "no spread is no draw, and must not be an exception");
        assertEquals(0, Model.draw(-1), "nor may a negative one reach nextInt");
    }

    @Test
    void everyLaneOfASweepGetsItsOwnScheduleFromTheShippedPolicy() {
        // THE WIRING, NOT THE UNIT. Every test that has ever watched the production waits pinned
        // the draw to a constant of its own — () -> 0, () -> 7, a test-local supplier — so
        // Retry.fromEnv() could have installed no draw at all and every one of them would still
        // have printed the schedule it expected. This drives the policy a deployment actually gets.
        List<Long> bare = List.of(1L, 1L, 2L);
        Set<List<Long>> lanes = new HashSet<>();

        for (int lane = 0; lane < 8; lane++) {
            Waits waits = new Waits();

            Model.wrap(dropping(3), QUIET,
                    Retry.fromEnv().withAttempts(4).with(waits).with(FROZEN)).answer(ask());

            assertEquals(3, waits.seconds.size(), "three failures, three waits");
            for (int wait = 0; wait < bare.size(); wait++) {
                long drawn = waits.seconds.get(wait) - bare.get(wait);
                assertTrue(drawn >= 0 && drawn < 60,
                        "the draw sits ON TOP OF the Fibonacci second rather than replacing it, "
                                + "and stays inside the minute: " + waits.seconds);
            }
            lanes.add(List.copyOf(waits.seconds));
        }

        assertTrue(lanes.size() > 1,
                "eight lanes failing together came back on the same schedule, which is the herd "
                        + "the draw exists to break: " + lanes);
    }

    @Test
    void theDefaultsADeploymentNeverSetAreTheOnesItAlwaysHad() {
        // The reachable end of the RATCHET_/OC_/BJV_ chain: every one of these resolves through
        // Model.setting and none of them had a number written down in a test. They are the four
        // that were private static finals parsed at class load before Endpoint, Watch and Sampling
        // became values, and the migration is only safe if the defaults did not move.
        assertEquals(60, Model.jitterSpread(),
                "a minute is wide enough to spread eight lanes across the early waits, where the "
                        + "schedule itself is only a second or two apart");
        assertEquals(Duration.ofMinutes(30), Model.budget(),
                "ten attempts against a frozen endpoint is three and a half hours of held slot "
                        + "unless the whole sequence is bounded by the clock");
        assertEquals(Watch.shipped(), Watch.fromEnv(),
                "the two liveness bounds a deployment that set nothing has always had");
        assertEquals(Sampling.deterministic(), Sampling.fromEnv(),
                "and temperature zero with four thousand tokens of reasoning, unchanged by the "
                        + "port: most replies here are branched on");
    }

    // ---------------------------------------------------------------- the fakes

    private static final Trace QUIET = Trace.quiet();

    /** A clock that does not move, so the budget is never the guard that ends a test. */
    private static final Now FROZEN = Now.frozenAt(0);

    private static Ask ask() {
        return Ask.of(List.of(Said.user("who speaks first?")));
    }

    /** An endpoint that drops its first {@code drops} calls exactly as {@link Wire} reports one. */
    private static Chat dropping(int drops) {
        AtomicInteger calls = new AtomicInteger();
        return ask -> {
            int call = calls.incrementAndGet();
            if (call <= drops) {
                throw new IllegalStateException("could not reach the endpoint: connection reset",
                        new IOException("connection reset"));
            }
            return new Reply("answer " + call, "", List.of(), Ending.STOPPED, Spend.NONE);
        };
    }

    /** The waits, taken rather than lived through, so the whole schedule asserts in milliseconds. */
    private static final class Waits implements Pause {
        private final List<Long> seconds = new ArrayList<>();

        @Override
        public void of(Duration wait) {
            seconds.add(wait.toSeconds());
        }
    }

    /**
     * A LOOPBACK ENDPOINT, BECAUSE THE QUESTION IS WHAT THE DOOR PUT ON THE SOCKET.
     *
     * <p>Every mutation this file is written against lives in the wiring between {@link Model} and
     * {@link Wire} — the thinking flag, the Sampling, the Watch — and both ends of that wiring are
     * already tested on their own. {@code Wire.body} proves the client writes the Sampling it was
     * GIVEN, which was never the thing that got through; what is missing is what the FACTORY gives
     * it. So the whole chain is pointed at a real server on an ephemeral port and the request is
     * read off the wire. Nothing waits: the server answers in one frame, so a door costs a few
     * milliseconds and the retry never has anything to retry.
     */
    private static final class Loopback implements AutoCloseable {

        private final HttpServer server;
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

        Loopback() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                byte[] answer = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, answer.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(answer);
                }
            });
            server.start();
        }

        Endpoint where() {
            return Endpoint.of("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "some-model");
        }

        /** Asks one question through the door and hands back what the door actually sent. */
        String sentBy(Chat door) {
            int before = requests.size();

            assertEquals("ok", door.answer(ask()).said(),
                    "the door must open on a model that answers; a door that returned null fails "
                            + "here, which is where the consumer would have found it");
            assertEquals(before + 1, requests.size(), "one question, one request");

            return requests.get(requests.size() - 1);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * A SERVER THAT IS ALIVE AND SLOW, which is the only shape either liveness bound can be seen
     * against.
     *
     * <p>A socket that dies is noticed by the transport; a server that answers at once is noticed
     * by nothing. What {@link Watch} exists for is the case in between — frames arriving, slowly,
     * for longer than the caller is willing to wait — so this one drips a token every
     * {@link #GAP} and stops after {@link #WHOLE_DRIP}. The gap is wider than the poll tick a
     * 400ms stall produces, so the read loop reaches its guards between frames, and the whole drip
     * is finite so a door that ignored the caller's Watch FAILS in a couple of seconds rather than
     * hanging the suite for twenty minutes.
     */
    private static final class Dripping implements AutoCloseable {

        static final Duration GAP = Duration.ofMillis(200);
        static final Duration WHOLE_DRIP = Duration.ofMillis(1_600);

        private final HttpServer server;

        Dripping() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream body = exchange.getResponseBody()) {
                    long frames = WHOLE_DRIP.toMillis() / GAP.toMillis();
                    for (long frame = 0; frame < frames; frame++) {
                        Thread.sleep(GAP.toMillis());
                        body.write(("data: {\"choices\":[{\"index\":0,\"delta\":"
                                + "{\"content\":\".\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                        body.flush();
                    }
                    body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                } catch (IOException wentAway) {
                    // The client gave up, which is the point of this server.
                }
            });
            server.start();
        }

        Endpoint where() {
            return Endpoint.of("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "some-model");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
