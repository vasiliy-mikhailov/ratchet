package tech.mikhailov.ratchet.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A SETTING NOBODY NAMED IS NOT A SETTING THIS LIBRARY HAS, AND THE DOOR MUST SAY WHICH ONE IS
 * MISSING.
 *
 * <p>{@link Model} is the factory every consumer enters through, and six of its doors —
 * {@code forProducer(trace)}, {@code forCritic(trace)}, both of their {@code (trace, retry)} forms
 * and both {@code forRetry} forms — resolve {@link Endpoint#fromEnv()}, so no test can open one
 * ONTO A MODEL. {@code EveryDoorIntoTheModelOpensOnTheSameOneTest} drove the other eight against a
 * loopback server and stopped at these, and they are the ones a consumer reaches for FIRST:
 * {@code Model.forProducer(trace)} is the whole of the getting-started line. Nothing in this module
 * has ever executed them.
 *
 * <p>WHAT A DEPLOYMENT THAT NAMED NOTHING MUST GET IS A SENTENCE NAMING THE VARIABLE, AT THE DOOR.
 * Not a model with nowhere to ask, not null, and not a lazily-built client that resolves its
 * endpoint on first use — because a door that defers this hands the failure to a lane an hour into
 * a sweep, in someone else's log, where it arrives as a {@link NullPointerException} from inside a
 * library the consumer adopted yesterday. {@code Endpoint.fromEnv}'s own comment promises the
 * message {@code build} threw before that type existed; this file holds every door to it.
 *
 * <p>WHAT THIS FILE CANNOT SAY, AND IT IS ALL TWELVE OF {@link Model}'S LIVE MUTANTS. Every one of
 * them needs a variable set to a value the test CHOSE, and each is a different deployment getting
 * something different:
 *
 * <pre>
 * Model:267 &rarr; false   OC_ is never consulted     a launcher half-migrated to OC_ resolves no model
 * Model:267 &rarr; true    OC_ overwrites RATCHET_    the NEW name is the one that gets ignored
 * Model:270 &rarr; false   BJV_ is never consulted    the oldest launcher — the live one — resolves nothing
 * Model:270 &rarr; true    BJV_ overwrites both       every rename anyone has made is undone
 * Model:273 &rarr; true    the fallback always wins   nothing any deployment sets is ever read at all
 * Model:126 &rarr; true    the THINKING chain ignored no deployment can turn thinking off
 * Model:34,38,93,97,111,115  the six doors above hand back null
 * </pre>
 *
 * <p>{@code Env.get} calls {@code System.getenv} itself and a JVM cannot set its own environment. A
 * child JVM is not the answer either, for the reason
 * {@code TheEndpointDecidesTheRequestAndNeverPrintsTheKeyTest} already wrote down: PIT mutates
 * bytecode inside the minion that runs the test, so a spawned process loads the unmutated class off
 * disk and kills nothing. What is missing is not a back door for a test but the precedence rule as
 * a function of its arguments — {@code Model.firstNamed(String underNew, String underOc, String
 * underBjv, String fallback)}, with {@code setting} reading the three names and delegating,
 * unchanged. The ratchet#7 consumer resolving the same settings out of a page wants that function
 * as much as a test does, which is the difference between a seam and a hole.
 *
 * <p>SO THE SIX DOOR MUTANTS ARE EXECUTED BELOW AND STILL NOT KILLED, and that must not be read as
 * progress. With nothing in the environment the doors throw before they return, so the null they
 * would have returned is never reached: if the report turns those six from NO_COVERAGE to SURVIVED,
 * the number moved and the risk did not.
 *
 * <p>COUNTED RATHER THAN CLAIMED: each change was applied to a scratch copy of {@link Model},
 * compiled, and this file run against it in four environments — nothing named, and an endpoint
 * named under each of the three prefixes in turn:
 *
 * <pre>
 *                            nothing  RATCHET_   OC_     BJV_
 * a door hands back null      alive    KILLED   KILLED  KILLED
 * OC_ is never consulted      alive    alive    KILLED  alive
 * OC_ overwrites RATCHET_     alive    KILLED   alive   alive
 * BJV_ is never consulted     alive    alive    alive   KILLED
 * BJV_ overwrites both        alive    KILLED   KILLED  alive
 * the fallback always wins    alive    KILLED   KILLED  KILLED
 * the THINKING chain ignored  alive    alive    alive   alive
 * </pre>
 *
 * <p>The first column is the one PIT reads, so the honest number this file adds to the report is
 * ZERO. What the other three say is that eleven of the twelve die to this file the moment a
 * launcher names an endpoint — the environment is the only thing between them and dead, which is
 * why the missing seam below is a seam and not an excuse. The twelfth wants more than a variable:
 * the thinking flag is only visible in the request body, so killing it needs a {@code *_THINKING}
 * set to a falsehood AND a socket to read the body off.
 *
 * <p>THE FIRST DRAFT OF THIS FILE WAS ALIVE IN EVERY COLUMN, AND THAT WAS THE FILE'S OWN FAULT. It
 * read what to expect out of {@code Model.setting("BASE", null)} — the function under test — so a
 * {@link Model} that had stopped consulting {@code OC_} was asked what should happen, answered
 * "this deployment named nothing", and the assertion that followed was the one the mutant
 * satisfies. Measured: a JVM launched under {@code OC_BASE} and {@code OC_MODEL} against that
 * {@link Model} resolves NO ENDPOINT AT ALL — every lane of that deployment fails at the door —
 * and the first draft stayed green through it, in all four columns. A test that asks the mutated
 * function what to expect follows it wherever it goes. So the environment is read here through
 * {@code System.getenv} and the precedence rule is written out again below, because an oracle that
 * shares a line with the thing it is judging is not an oracle.
 *
 * <p>A SECOND SHARED RULE IS STILL SHARED, and it is the one {@code numberFrom}'s rewrite left
 * behind. Taking the fallback as an argument fixed six settings sharing ATTEMPTS's default; the
 * {@code Math.max(1, ...)} floor beside it is still ATTEMPTS's rule applied to all six. One is
 * right for attempts, which would otherwise ask zero times. It is wrong for
 * {@code RATCHET_THINKING_TOKENS=0}: {@link Sampling#thinks()} is {@code thinkingTokens > 0}, so a
 * deployment saying "do not think" through the environment gets ONE token of thinking rather than
 * none — the template switch stays on, {@code thinking_token_budget} goes out as 1, and every
 * generation is cut off mid-thought and comes back blank. That is the {@link Truncated} incident
 * the same javadoc records for {@code 4k}, reached from the other side, and it is not asserted here
 * because it cannot be asserted without failing.
 */
class ASettingNobodyNamedIsNotASettingTest {

    private static final Trace QUIET = Trace.quiet();

    /** The three names, in the order the promise puts them. */
    private static final String[] THE_THREE_PREFIXES = {"RATCHET_", "OC_", "BJV_"};

    /** A name no launcher has ever exported under any of the three. The premise, so it is checked. */
    private static final String NOBODY_HAS_EVER_NAMED_THIS = "NOTHING_HAS_EVER_NAMED_THIS";

    /** A value no environment could have supplied, so a fallback that came back is the caller's. */
    private static final String THE_CALLERS_OWN = "qwen3-8b-at-the-callers-own-address";

    @Test
    void everyDoorThatReadsTheEnvironmentAnswersForTheEnvironmentItFinds() {
        // THE SIX DOORS NO TEST HAS EVER OPENED, and the assertion is different on either side of
        // the one thing this JVM does not control. With an endpoint named, a door must hand back a
        // model — the six mutants that replace these returns with null die exactly there, on a
        // machine whose launcher named one. With nothing named, the requirement is the sentence:
        // the door fails at the door, and names the variable that is missing.
        Map<String, Supplier<Chat>> doors = new LinkedHashMap<>();
        doors.put("forProducer(trace)", () -> Model.forProducer(QUIET));
        doors.put("forCritic(trace)", () -> Model.forCritic(QUIET));
        doors.put("forProducer(trace, retry)", () -> Model.forProducer(QUIET, Retry.none()));
        doors.put("forCritic(trace, retry)", () -> Model.forCritic(QUIET, Retry.none()));
        doors.put("forRetry(trace)", () -> Model.forRetry(QUIET));
        doors.put("forRetry(trace, retry)", () -> Model.forRetry(QUIET, Retry.none()));

        String base = whatThisLauncherNamed("BASE");
        String model = whatThisLauncherNamed("MODEL");

        // THE SAME QUESTION THE DOORS ARE ABOUT TO ASK, ASKED OF THE VALUE FIRST, so a failure
        // says WHICH of the three names stopped being read rather than only that a door threw.
        // Not RATCHET_KEY: assertEquals prints both sides, and this library's one rule about the
        // key is that it is never printed — Endpoint.toString exists for that reason alone.
        assertEquals(base, Model.setting("BASE", null),
                "the endpoint this launcher named under RATCHET_/OC_/BJV_BASE, in that order, is "
                        + "the one this library must resolve; a chain that reads a different set "
                        + "of names than it promises leaves the deployment that used the other "
                        + "one with no endpoint at all and no sentence saying why");
        assertEquals(model, Model.setting("MODEL", null),
                "and the same three names for the model, because a launcher that cannot be edited "
                        + "while the sweep it started is running sets both or neither");

        doors.forEach((door, open) -> {
            if (base != null && model != null) {
                Chat opened;
                try {
                    opened = open.get();
                } catch (IllegalStateException nowhereToAsk) {
                    throw new AssertionError(door + " refused to open although this launcher named "
                            + "an endpoint (" + base + ", " + model + "). The door said \""
                            + nowhereToAsk.getMessage() + "\", so one of the three names this "
                            + "library promises to go on honouring is no longer read, and every "
                            + "lane started by that launcher fails at the door", nowhereToAsk);
                }
                assertNotNull(opened,
                        "this JVM's launcher named an endpoint, so " + door + " must hand back a "
                                + "model built from it; a door that hands back null is a "
                                + "NullPointerException raised inside a library the consumer "
                                + "adopted yesterday, at their first call rather than at ours");
                return;
            }
            IllegalStateException nowhereToAsk = assertThrows(IllegalStateException.class,
                    open::get,
                    door + " opened on nothing at all. A process with no endpoint in its "
                            + "environment must fail at the door it opened, not an hour into a "
                            + "sweep on the lane that finally asked");
            assertEquals(base == null
                            ? "RATCHET_BASE must be set to an OpenAI-compatible chat endpoint"
                            : "RATCHET_MODEL must be set",
                    nowhereToAsk.getMessage(),
                    "every door names the same missing variable in the same words, because the "
                            + "deployment reading it does not know which overload it called: "
                            + door);
        });
    }

    @Test
    void aNameNoLauncherHasEverNamedIsTheCallersOwnFallbackAndNothingElse() {
        // The unset end of the chain, which is what every door resolves through and what no test
        // has written down for the STRING overload. The three numeric settings have their defaults
        // pinned elsewhere; the string one carries the base URL, the model name and the key.
        nobodyHasNamedIt();

        assertEquals(THE_CALLERS_OWN, Model.setting(NOBODY_HAS_EVER_NAMED_THIS, THE_CALLERS_OWN),
                "the fallback is the caller's and comes back byte for byte as the caller wrote it, "
                        + "rather than as some value this library preferred");

        assertNull(Model.setting(NOBODY_HAS_EVER_NAMED_THIS, (String) null),
                "AND A FALLBACK OF NULL STAYS NULL. Endpoint.fromEnv tells 'this deployment named "
                        + "no endpoint' from 'it named one' by exactly this null; a chain that "
                        + "improved it to \"\" would hand back an endpoint with a base URL of "
                        + "empty string, and the deployment would find out from someone else's "
                        + "404 rather than from the sentence that names the variable");

        assertEquals(7, Model.setting(NOBODY_HAS_EVER_NAMED_THIS, 7),
                "and the number overload hands back the caller's own number, which is the whole "
                        + "of the rewrite that stopped an unreadable value doing ATTEMPTS's thing");
    }

    @Test
    void aVariableThisProcessReallyHasIsNotASettingUntilItIsNamedWithOneOfTheThree() {
        // THE PREFIX IS LOAD-BEARING, and it is the one half of the chain a JVM can check about
        // itself: whatever this launcher exported, it exported under its own name, and this
        // library must not read it. The temptation is real and has a name — every sibling
        // repository keeps its credentials as LLM_BASE_URL / LLM_MODEL / LLM_API_KEY and needs a
        // launcher to rename them, which is the cost Endpoint's javadoc opens with. A fourth,
        // unprefixed lookup added to shorten that launcher would make setting("KEY", "") read
        // whatever some unrelated program exported as KEY and send it to the model endpoint as a
        // bearer token.
        String bare = aNameThisProcessHasUnderNoneOfTheThree();

        assertEquals(THE_CALLERS_OWN, Model.setting(bare, THE_CALLERS_OWN),
                "this process really has " + bare + ", and it is still not this library's setting: "
                        + "the names are RATCHET_" + bare + ", OC_" + bare + " and BJV_" + bare
                        + ", and a bare name belongs to whoever exported it");

        assertEquals(7, Model.setting(bare, 7),
                "and the number overload reads the same three names and no others, so a value "
                        + "meant for another program cannot become this one's attempt count");
    }

    // ---------------------------------------------------------------- the premises, checked

    /**
     * THE PROMISE, WRITTEN OUT HERE RATHER THAN ASKED OF THE THING BEING JUDGED: the first of
     * {@code RATCHET_}, {@code OC_} and {@code BJV_} that this launcher named non-blank, and null
     * when it named none of them.
     *
     * <p>Nine lines that could have been one call to {@code Model.setting}, and that one call is
     * what made the first draft of this file unable to fail. The three names and their order are
     * the whole of the compatibility promise {@code setting()}'s javadoc makes to a sweep whose
     * launcher cannot be edited while it is running; a copy of it that is derived from the code
     * cannot notice the code changing it.
     */
    private static String whatThisLauncherNamed(String name) {
        for (String prefix : THE_THREE_PREFIXES) {
            String said = System.getenv(prefix + name);
            if (said != null && !said.isBlank()) {
                return said;
            }
        }
        return null;
    }

    /** Nothing anywhere has named the test's own name, or half this file measures another branch. */
    private static void nobodyHasNamedIt() {
        for (String prefix : THE_THREE_PREFIXES) {
            assertNull(System.getenv(prefix + NOBODY_HAS_EVER_NAMED_THIS),
                    "if a machine ever did name " + prefix + NOBODY_HAS_EVER_NAMED_THIS + ", the "
                            + "assertions below would be measuring the other branch and would "
                            + "still pass, which is worse than failing");
        }
    }

    /**
     * Some variable this process actually has, under a bare name none of the three prefixes covers.
     *
     * <p>The prefixed forms are filtered out rather than tolerated: on a machine whose launcher
     * exported both {@code FOO} and {@code RATCHET_FOO} the assertion would pass for the wrong
     * reason and no one would reproduce it. It throws rather than skipping, because a green run
     * that quietly checked nothing is the failure this file is about.
     */
    private static String aNameThisProcessHasUnderNoneOfTheThree() {
        return System.getenv().entrySet().stream()
                .filter(named -> named.getValue() != null && !named.getValue().isBlank())
                .map(Map.Entry::getKey)
                .filter(name -> System.getenv("RATCHET_" + name) == null)
                .filter(name -> System.getenv("OC_" + name) == null)
                .filter(name -> System.getenv("BJV_" + name) == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "this JVM was launched with no environment variable this file could use, "
                                + "so nothing here exercised the branch where a name really is "
                                + "exported and it is still not one of the three"));
    }
}
