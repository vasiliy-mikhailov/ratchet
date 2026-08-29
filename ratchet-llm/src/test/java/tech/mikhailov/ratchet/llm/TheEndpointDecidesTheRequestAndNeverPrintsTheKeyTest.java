package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AN ENDPOINT'S THREE FIELDS ARE THREE DECISIONS TAKEN IN ANOTHER CLASS, AND NOT ONE OF THEM WAS
 * ASSERTED WHERE IT LANDS.
 *
 * <p>A mutation run over ratchet-llm with the STRONGER set put the number on it. {@link Endpoint}
 * itself scores 45%, but the expensive survivors are not in the record — they are in {@link Wire},
 * which is the only place its values are ever spent:
 *
 * <pre>
 * Endpoint:81  key.isEmpty() ? "" : ", keyed"           condition -&gt; false  SURVIVED
 * Wire:147     if (!endpoint.key().isEmpty())           condition -&gt; true   SURVIVED
 * Wire:147     if (!endpoint.key().isEmpty())           condition -&gt; false  SURVIVED
 * Wire:104     endpoint.secure() ? HTTP_2 : HTTP_1_1    condition -&gt; true   SURVIVED
 * Wire:381     base.endsWith("/") ? stripped : base     condition -&gt; false  SURVIVED
 * </pre>
 *
 * <p>Five survivors, one failure: {@code AConsumerCanSayWhereTheModelIsTest} asks the record what
 * it HOLDS and nothing asks the socket what was SENT. As the module stands, the Authorization
 * header can be attached to every request or to none, {@code secure()} can be deleted from the
 * client's construction, and the trailing slash can stop being stripped — and every test in
 * ratchet-llm stays green. That is the shape this project already has a name for: the unit was
 * tested and the wiring to it was not.
 *
 * <p>WHAT THE KEY ONE COSTS, EXACTLY. A key the process did not get comes back as 401, and 401 is
 * the one status {@link Retrying#transportFailures} deliberately refuses to retry — a bad key is
 * not worth a second request. So a sweep that lost its key does not limp, it stops on the first
 * call, and the one line anybody has to diagnose that with is {@link Endpoint#toString()}. The
 * mutant that survived is the one that makes it print ", keyed" for every endpoint, keyed or not:
 * the only diagnostic there is, saying the opposite of the truth, in the one minute somebody is
 * reading it. It survived because the assertion that exists only ever built a KEYED endpoint — a
 * test of one branch of a two-branch fact, which is a test of a constant.
 *
 * <p>Its mirror in {@code Wire:147} is the same failure pointed the other way: with the condition
 * always true, an endpoint that wants no token is sent {@code Authorization: Bearer } — an empty
 * bearer. A gateway that reads an empty bearer as a bad one answers 401, and 401 is not retried, so
 * the local process that has never wanted a token stops on the first call too. An empty key has to
 * mean silence, and silence is only observable from the far end of the socket.
 *
 * <p>So the tests below send real requests to a loopback and assert what ARRIVED, in the pattern
 * {@code AConsumerBringsItsOwnModelTest} established for temperature after the same kind of mutant
 * got through the same kind of gap.
 *
 * <p>WHAT THEY KILL, COUNTED RATHER THAN ESTIMATED. Each of twelve changes was applied to a scratch
 * copy of {@link Endpoint} and {@link Wire} and this file run against it, on Temurin 21 and 26.
 * Nine go red: both directions of Endpoint:81, both of Wire:147, both of Wire:381, BOTH of
 * Wire:104, and the {@code fromEnv} conditional that decides which missing variable is named first.
 * Three do not, and no assertion would change that: each needs an environment variable set to a
 * value the test chose, which is the seam described below.
 *
 * <p>{@code Wire:104 -> false} — an https endpoint quietly downgraded to HTTP/1.1 — looked like a
 * fourth, because it cannot be seen from a plaintext loopback and this class exposes no accessor
 * for the version it chose. It does not need a TLS server either. The choice is spent in the
 * CLIENTHELLO, before any certificate is asked for: a client built for HTTP/2 offers ALPN and one
 * built for HTTP/1.1 offers none, so a bare {@link java.net.ServerSocket} that reads the first
 * flight and hangs up settles it with no key and no certificate anywhere in the test tree.
 *
 * <p>WHAT IS STILL UNREACHABLE, AND WHY THAT IS NOT A TEST'S FAULT. Five of Endpoint's eleven
 * mutants are in {@link Endpoint#fromEnv()} and four of them need an environment variable set to a
 * value the test CHOSE. {@code fromEnv} resolves through {@code Model.setting} to {@code Env.get}
 * to {@code System.getenv}, and a JVM cannot set its own environment; a child JVM is not the answer
 * either, because PIT mutates bytecode inside the minion that runs the test and a spawned process
 * would load the unmutated class off disk and kill nothing. ratchet-core's
 * {@code AMissingSettingIsNotAValueTest} hit the same wall for {@code Env} and wrote down the
 * answer this project prefers: not a back door for a test, but the rule itself as a function of its
 * arguments — an {@code Endpoint.from(String base, String model, String key)} carrying the two
 * {@link IllegalStateException} messages, with {@code fromEnv} reading the three names and
 * delegating to it. A consumer resolving an endpoint out of a properties file, a CLI flag or a
 * settings page wants that function as much as a test does, which is the difference between a seam
 * and a hole. NO SUCH SEAM WAS ADDED HERE. The one test below that does call {@code fromEnv} takes
 * the environment as it finds it and asserts the branch it can reach.
 */
class TheEndpointDecidesTheRequestAndNeverPrintsTheKeyTest {

    // ------------------------------------------------------- what the key says, and to whom

    @Test
    void anEndpointWithNoTokenDoesNotReadAsOneThatHasOne() {
        // THE SURVIVOR. Endpoint:81 with the condition replaced by false prints ", keyed" for
        // everything, and the existing assertion — built on a keyed endpoint — passes through it.
        Endpoint open = Endpoint.of("https://inference.example.tech/v1", "some-model");

        assertFalse(open.toString().contains("keyed"),
                "this endpoint has no token, and the line an operator reads at a 401 must not "
                        + "claim it has: " + open);
    }

    @Test
    void thatOneWordIsTheWholeAnswerToWhetherThisProcessIsAuthenticating() {
        // The requirement stated as a requirement, in one assertion that both directions of the
        // flag fail: the two string forms have to be TELLABLE APART. Which word does it is not the
        // point; that the question has an answer at all is.
        String same = "https://inference.example.tech/v1";
        Endpoint open = Endpoint.of(same, "some-model");
        Endpoint keyed = new Endpoint(same, "some-model", "sk-live-9c3f");

        assertNotEquals(open.toString(), keyed.toString(),
                "same host, same model, one of them authenticating and one of them not — if the "
                        + "two print identically then the only diagnostic for a 401 is a constant");
        // Load-bearing rather than a repeat of the existing key-absence assertion: without it the
        // line above is satisfied by the difference BEING the token.
        assertFalse(keyed.toString().contains("sk-live-9c3f"),
                "and the difference must not be the token itself: " + keyed);
    }

    // ------------------------------------------------- what the key does, at the far end of a socket

    @Test
    void anEndpointThatWantsNoTokenSendsNoAuthorizationHeaderAtAll() throws IOException {
        // Wire:147, condition replaced by true: every request grows `Authorization: Bearer `, an
        // empty bearer, including to the local process that never wanted one. Nothing in this
        // module looked at a request's headers before this file, so nothing noticed.
        Arrived arrived = oneRequestTo(host -> Endpoint.of(host + "/v1", "some-model"));

        assertNull(arrived.header("Authorization"),
                "an endpoint that wants no token must be spoken to anonymously, not with an empty "
                        + "bearer a gateway is entitled to read as a bad one; it sent: "
                        + arrived.header("Authorization"));
    }

    @Test
    void aKeyedEndpointSendsThatTokenAndInThatForm() throws IOException {
        // Wire:147, condition replaced by false: the header is never attached and every call to a
        // keyed endpoint is anonymous. That is the 401 this file's class comment is about, and
        // until now the only thing that would have caught it is a real deployment.
        Arrived arrived = oneRequestTo(
                host -> new Endpoint(host + "/v1", "some-model", "sk-loopback-7"));

        assertEquals("Bearer sk-loopback-7", arrived.header("Authorization"),
                "the scheme is part of the contract: an OpenAI-compatible endpoint reads a bare "
                        + "token as a malformed header and answers 401, which nothing retries");
    }

    @Test
    void aNullKeyIsAnEndpointThatWantsNoneAllTheWayToTheSocket() throws IOException {
        // THE NORMALISATION, PROVED WHERE IT MATTERS. The record's own test proves the compact
        // constructor turns null into "", which is one step short of the reason it does: "" is the
        // right answer only because Wire reads it as "send nothing". Were the constructor to keep
        // the null, this is a NullPointerException from inside call(); were Wire to stop asking,
        // it is a blank bearer. Both are invisible to an assertion on key().
        Arrived arrived = oneRequestTo(host -> new Endpoint(host + "/v1", "some-model", null));

        assertNull(arrived.header("Authorization"),
                "a key nobody set is a key nobody sends: " + arrived.header("Authorization"));
    }

    // ------------------------------------------------------------ what the base decides, likewise

    @Test
    void aPlaintextEndpointIsNotAskedToSpeakHttp2() throws IOException {
        // Wire:104, condition replaced by true: HTTP_2 for a cleartext endpoint. The JDK does not
        // then quietly speak HTTP/1.1 — it opens with the h2c upgrade dance, adding
        // `Connection: Upgrade, HTTP2-Settings`, `Upgrade: h2c` and a base64 `HTTP2-Settings`
        // preamble to the first request. Measured on Temurin 21 and 26: three headers with HTTP_2
        // against none with HTTP_1_1, which makes this the one place secure() is observable from
        // outside without a TLS listener.
        //
        // The ternary exists because a cleartext hop in this deployment is a local process or an
        // in-cluster service behind a proxy, and an upgrade offer to something that has never heard
        // the word fails at the transport, before this library has an opinion about anything. What
        // is certain either way is that the choice was unobserved: both branches of Wire:104
        // survived, so secure() could be deleted from the client's construction outright.
        Arrived arrived = oneRequestTo(host -> Endpoint.of(host + "/v1", "some-model"));

        assertNull(arrived.header("Upgrade"),
                "a base URL that is not https must be spoken to as HTTP/1.1 and must not carry an "
                        + "h2c upgrade offer; this request carried: " + arrived.header("Upgrade"));
    }

    @Test
    void anHttpsEndpointIsAskedForHttp2BeforeAnyCertificateIsNeeded() throws Exception {
        // Wire:104's OTHER half, and the half that looked unreachable: with the condition replaced
        // by false a TLS endpoint is spoken to as HTTP/1.1. No plaintext loopback can see that, and
        // nothing on this class exposes the version it chose — but the choice is spent in the
        // CLIENTHELLO, which is the first thing the client says and is sent before any certificate
        // is asked for. A client built for HTTP_2 on an https URI offers ALPN, `h2` first and
        // `http/1.1` behind it; one built for HTTP_1_1 sends no ALPN extension at all. So a bare
        // ServerSocket that accepts one connection, reads the first flight and hangs up is enough,
        // with no key, no certificate and no TLS on this side. Measured on Temurin 21 and 26: 440
        // bytes with the offer, 422 without, ~10ms either way.
        //
        // WHY THE DOWNGRADE IS WORTH A TEST. HTTP/2 is what carries a long prefill and a slow
        // stream through the hops in front of a model: one flow-controlled connection, in frames a
        // proxy forwards rather than buffers. A deployment quietly demoted to HTTP/1.1 returns the
        // same answers and the same statuses — it surfaces as slower sweeps, and as Watch#stall
        // firing on connections that were alive, which is the failure this module is least able to
        // tell apart from a dead endpoint.
        byte[] hello = theFirstThingSaidTo(
                port -> Endpoint.of("https://127.0.0.1:" + port + "/v1", "some-model"));

        assertTrue(new String(hello, StandardCharsets.ISO_8859_1).contains(ALPN_H2_FIRST),
                "an https endpoint must be asked for HTTP/2, and the ClientHello is where that is "
                        + "said: this one carried " + hello.length + " bytes with no `h2` ALPN "
                        + "offer in them, which is a client that was built for HTTP/1.1");
    }

    @Test
    void aBaseUrlWithATrailingSlashIsTheSameEndpoint() throws IOException {
        // Wire:381. `LLM_BASE_URL=http://host:8000/v1/` is what a .env file and a compose entry
        // most often hold, and the strip is the line that makes the two spellings one endpoint.
        // With the condition replaced by false the URL becomes `/v1//chat/completions`, which no
        // server routes — and nothing here had ever built a base URL ending in a slash.
        Arrived arrived = oneRequestTo(host -> Endpoint.of(host + "/v1/", "some-model"));

        assertEquals("/v1/chat/completions", arrived.path(),
                "the same path the endpoint without the slash reaches; a deployment does not get "
                        + "to find out which spelling this library prefers from a 404");
    }

    // ---------------------------------------------------------------- the environment, as found

    @Test
    void aDeploymentThatNamedNoEndpointIsToldWhichVariableToSet() {
        // WHAT THIS CAN ASSERT DEPENDS ON THE LAUNCHER, AND IT SAYS SO RATHER THAN PRETENDING.
        // Neither branch is a free pass: with nothing set, the exact sentence is the requirement —
        // Endpoint's own comment promises the messages build threw before this type existed, so a
        // deployment that has set nothing does not learn a new sentence for it — and with the
        // conditional on line 51 removed, that sentence names RATCHET_MODEL instead, sending
        // somebody to fix the second missing variable while the first goes unmentioned and then
        // arrives as a different exception type with a different vocabulary.
        String base = Model.setting("BASE", null);
        String model = Model.setting("MODEL", null);

        if (base != null && model != null) {
            Endpoint said = Endpoint.fromEnv();

            assertEquals(base, said.base(), "the endpoint this JVM was launched pointing at");
            assertEquals(model, said.model(), "and the model that endpoint serves");
            assertEquals(Model.setting("KEY", ""), said.key(),
                    "an endpoint whose environment names no key wants none, rather than null");
            return;
        }

        IllegalStateException nowhereToAsk = assertThrows(IllegalStateException.class,
                Endpoint::fromEnv,
                "a process with no endpoint in its environment must fail at the door, not at the "
                        + "first call an hour into a sweep");
        assertEquals(base == null
                        ? "RATCHET_BASE must be set to an OpenAI-compatible chat endpoint"
                        : "RATCHET_MODEL must be set",
                nowhereToAsk.getMessage(),
                "the missing setting named first is the one nobody can guess, and it is named in "
                        + "the words the deployment already knows");
    }

    // ------------------------------------------------------------------------- the loopback

    /** What one request looked like from the far side of the socket. */
    private record Arrived(Map<String, List<String>> headers, String path) {

        /** Case-insensitively, because a header name's spelling is the transport's business. */
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(header -> header.getKey().equalsIgnoreCase(name))
                    .map(header -> String.join(", ", header.getValue()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static final Arrived NEVER_ARRIVED =
            new Arrived(Map.of(), "(no request reached the loopback at all)");

    /**
     * The ALPN protocol list a JDK client built for HTTP/2 offers, byte for byte: each name is
     * preceded by its own length, so {@code h2} is {@code 0x02 h 2} and {@code http/1.1} is
     * {@code 0x08 h t t p / 1 . 1}. Matched as a twelve-byte sequence rather than as the word "h2"
     * on its own, because two ASCII bytes turn up in a ClientHello's random by chance about once in
     * a hundred and a flaky test proves less than no test.
     */
    private static final String ALPN_H2_FIRST = "\002h2\010http/1.1";

    /**
     * THE FIRST FLIGHT OF BYTES AN ENDPOINT WOULD HAVE RECEIVED, WHICH IS WHERE THE HTTP VERSION IS
     * SPENT FOR A TLS ENDPOINT.
     *
     * <p>Not a TLS server: a plain {@link ServerSocket} that accepts one connection, reads the
     * first TLS record whole and closes. That is all this assertion needs — the ClientHello is the
     * client's opening statement and carries the ALPN offer, so the version is observable without a
     * key, a certificate or an {@code HttpsServer}. The call then fails, of course, with "Remote
     * host terminated the handshake"; the failure is the point of the listener rather than a
     * problem with it, and what was already read is what is asserted.
     *
     * @param theirEndpoint given the port this listener is on, the endpoint under test
     */
    private static byte[] theFirstThingSaidTo(IntFunction<Endpoint> theirEndpoint) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<byte[]> hello = new AtomicReference<>(new byte[0]);
            Thread ear = new Thread(() -> {
                try (Socket one = listener.accept(); InputStream said = one.getInputStream()) {
                    // Until the first TLS record is whole, rather than one read(): a record's
                    // length is the two bytes after its type and version, and a ClientHello split
                    // across two packets would otherwise read as a client that offered nothing.
                    byte[] buffer = new byte[16_640];
                    int have = 0;
                    while (have < buffer.length
                            && (have < 5 || have < 5 + (((buffer[3] & 0xff) << 8)
                                    | (buffer[4] & 0xff)))) {
                        int read = said.read(buffer, have, buffer.length - have);
                        if (read < 0) {
                            break;
                        }
                        have += read;
                    }
                    hello.set(Arrays.copyOf(buffer, have));
                } catch (IOException hungUp) {
                    // Whatever arrived before the socket went is already in `hello`, and an empty
                    // `hello` fails the assertion with its own byte count, which says so.
                }
            }, "ratchet-clienthello-ear");
            ear.setDaemon(true);
            ear.start();

            Endpoint endpoint = theirEndpoint.apply(listener.getLocalPort());
            Chat built = Model.forProducer(Trace.quiet(), endpoint, Retry.none(),
                    Sampling.deterministic());
            assertThrows(RuntimeException.class,
                    () -> built.answer(Ask.of(List.of(Said.user("who speaks first?")))),
                    "this listener answers no handshake, so the call cannot have succeeded; if it "
                            + "did, the bytes read below are not the ones this endpoint sent");
            ear.join(10_000);
            return hello.get();
        }
    }

    /** One generation, in the frame shapes captured from the production endpoint. */
    private static final String ONE_FRAME =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";

    /**
     * THE REQUEST AS THE SERVER SAW IT, WHICH IS THE ONLY PLACE THESE FOUR DECISIONS EXIST.
     *
     * <p>A loopback on an ephemeral port, one request, one frame back, and the whole chain
     * {@link Model} assembles in between — not {@link Wire} directly, because two of the mutants
     * this file is written against live in the wiring and a test that reaches past it would stay
     * green through them. Nothing here waits: the server answers immediately, so the read loop's
     * first poll returns and the exchange is a few milliseconds.
     *
     * @param theirEndpoint given {@code http://127.0.0.1:<port>}, the endpoint under test
     */
    private static Arrived oneRequestTo(Function<String, Endpoint> theirEndpoint) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Arrived> seen = new AtomicReference<>(NEVER_ARRIVED);
        server.createContext("/v1/chat/completions", exchange -> {
            seen.set(new Arrived(Map.copyOf(exchange.getRequestHeaders()),
                    exchange.getRequestURI().getPath()));
            exchange.getRequestBody().readAllBytes();
            byte[] answer = ONE_FRAME.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(answer);
            }
        });
        server.start();
        try {
            Endpoint endpoint =
                    theirEndpoint.apply("http://127.0.0.1:" + server.getAddress().getPort());
            Chat built = Model.forProducer(Trace.quiet(), endpoint, Retry.none(),
                    Sampling.deterministic());
            try {
                assertEquals("ok", built.answer(Ask.of(List.of(Said.user("who speaks first?"))))
                        .said(), "the loopback stood in for the endpoint");
            } catch (Refused wrongDoor) {
                throw new AssertionError("nothing reached the chat context on the loopback — the "
                        + "server answered " + wrongDoor.status() + ". The URL built out of "
                        + endpoint.base() + " is not the one that endpoint names.", wrongDoor);
            }
            return seen.get();
        } finally {
            server.stop(0);
        }
    }
}
