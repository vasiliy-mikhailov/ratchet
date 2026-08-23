# ratchet

An agent harness assumes a model clever enough to choose its own trajectory. ratchet is what you
substitute when it cannot.

It came out of a pipeline that runs on a small local model, over a corpus of real repositories, for
weeks at a time, where every run is killed at some point and every loop will eventually spin. The
property the pieces share is that work does not slide back:

- the **journal** means a killed run does not pay twice for what it already did,
- the **settlement file** is append-only and last row wins, so the path a run took through its
  states survives the run,
- the **runaway latch** fires once and stays fired,
- the **tool-loop bound** stops a loop without capping work that is progressing,
- the **round boundary** ends a run between stages when its budget is up, and the **resume rule**
  refuses to pick that workspace up again unless four things agree.

None of that is clever. All of it is a post-mortem written down as code, and the comments say which
one. That is the library: the reasons ship in the sources jar, not just the bytecode.

## Two modules, split by dependency

| artifact | depends on | what is in it |
| --- | --- | --- |
| `ratchet-core` | nothing outside the JDK | the flow, the record, the journal, the bounds |
| `ratchet-llm` | `ratchet-core`, langchain4j | the model wiring, the tool loop, the listeners |

Take the first without taking a model client. The split is enforced by the compiler, not by a
convention.

```xml
<dependency>
  <groupId>tech.mikhailov.ratchet</groupId>
  <artifactId>ratchet-core</artifactId>
  <version>0.12.0</version>
</dependency>
```

Java 17 or later. Versions are plain releases and are never republished: a version number here
names one set of bytes forever. There are no SNAPSHOTs, deliberately, because a dependency naming a
moving target is how the thing this replaced became unusable.

**It is not on Maven Central. Install it from its own source tree, in one command:**

```sh
git clone https://github.com/vasiliy-mikhailov/ratchet.git && cd ratchet
./install.sh                              # the newest tag, into ~/.m2
./install.sh v0.5.0                       # a specific one
./install.sh v0.12.0 -r ~/.m2-fitness/repository   # into a repository another build reads
```

`-r` becomes `-Dmaven.repo.local`, so it wants the **repository** directory rather than the `.m2`
directory above it. Every version from `0.1.0` onward has a tag, so any of them can be installed
this way. The script
checks the jars actually landed before it says so — a build that succeeded and a version a consumer
can resolve are different claims — and it puts your checkout back where it found it, including when
the build fails.

Which artifact repository your build resolves from is yours to decide. This library ships a source
tree and tags; it does not deploy into anybody's Nexus.

## What is in each package

`tech.mikhailov.ratchet.flow` decides what runs next. `Agent` is one method, `String run(String)`,
and everything is one: a model call, a sequence, a walk, a loop, plain code. `Flow` gives you
sequence, selection and iteration over that one notion, plus `resumable`, which is the journal as a
decorator. `Flow.triad` is planner, doer, verifier with the loop held by the verifier rather than
the producer. `Shape` walks the tree that runs, so a picture of the program cannot drift from it.
`Reply` reads the one word a verifier settled on.

`tech.mikhailov.ratchet.record` writes down what happened and reads it back. `Trace` is the one
interface everything reports through. `JsonlTrace` is the file format: one event per line, plus a
settlements file beside it holding the last word per unit of work. `Journal` is the append-only
file a resume reads, torn last line and all. `Json` is the small writer and the tolerant reader for
arguments a model composed. `Digest` is the eight-character fingerprint every row carries. `Round`
is which slice of a wall-clock budget this run is in, counted off the settlement rows rather than
kept anywhere, and whether a launcher has asked it to hand over. `Resume` is the four-clause rule
for whether this attempt may pick a killed one up.

`tech.mikhailov.ratchet.config` is what the run was told before the code started: `Env`, and
`Prompts`, an on-disk store whose text replaces the code's own, per agent and per variant.

`tech.mikhailov.ratchet.llm` is the langchain4j artifact. `Endpoint` is where the model is, as a
value you can hand in rather than three environment variables a launcher has to rename. `Model` builds the client, with a
thinking budget and a patience that is deliberately never the guard that fires. `Streamed` makes
the guard time since the last token rather than time since the request. `Asking` is one agent: a
system prompt, a closed set of tools, a bounded tool loop. `Reasoning` reads the reasoning off the
wire that the client would otherwise discard, and latches once when it starts repeating itself.
`Listening` records every exchange as the client saw it. `Retrying` asks a dropped call again, up to
`RATCHET_ATTEMPTS` times, because the journal only preserves a whole node and a reset halfway
through one destroys every call that node already paid for; the wait is a Fibonacci second plus a
draw of up to `RATCHET_JITTER_SECONDS`, so a sweep's lanes do not all come back at the same instant,
and `RATCHET_RETRY_BUDGET_MINUTES` bounds the whole sequence because a count of attempts does not —
ten stalls is three and a half hours. `Insisting` re-asks an agent that answered nothing — and sits
above `Retrying`, so a dead connection is never read as an answer. `Recording` writes every tool
call into the trace whole and returns it bounded.

### Taking one piece without the rest

`Model` builds a whole chain. If you already have a `ChatModel` — your own client, your own
listeners, an endpoint you resolve per call — take the parts instead:

```java
ChatModel retried = Retrying.on(yourModel, Retry.fibonacciSeconds(), trace);
ChatModel guarded = Streamed.over(yourStreamingModel, trace);            // the stall guard alone
ChatModel patient = Streamed.over(yourStreamingModel, trace,
        Watch.shipped().withStall(Duration.ofMinutes(45)));              // ...with your own bounds
Predicate<Throwable> worth = Retrying.transportFailures();       // just the judgement
```

`transportFailures()` is the half that is hard to get right: it decides on langchain4j's
`RetriableException`/`NonRetriableException` hierarchy and `HttpException.statusCode()`, refuses
`Streamed.GaveUp` and `Streamed.Truncated`, and retries anything it does not recognise.

### How the model answers

```java
Model.forProducer(trace, endpoint, retry, Sampling.deterministic());
Model.forProducer(trace, endpoint, retry, Sampling.asTheModelRequires(1.0));  // Qwen with reasoning
Sampling.deterministic().withThinkingTokens(500);   // leave room for an answer
```

Temperature is zero by default and that reason has not changed — most replies here are branched on.
But some models require 1.0 with reasoning on, and a hardcoded default their own documentation
forbids is a refusal rather than a default. `maxTokens` and `thinkingTokens` share one pool on the
server, so `Sampling` refuses a reasoning budget that leaves no room for a reply.

### Choosing the retry

`Model.forProducer(trace)` takes the shipped policy. `Model.forProducer(trace, retry)` takes yours:

```java
Model.forProducer(trace, Endpoint.of(base, model));   // point it where YOU keep your endpoint
Model.forProducer(trace, endpoint, retry);           // and choose the retry too

Model.forProducer(trace, Retry.fibonacciSeconds());   // the shipped shape, named: 10 attempts,
                                                     // 1 1 2 3 5 8 13 21 34 + a draw, 30m budget
Model.forProducer(trace, Retry.fibonacciSeconds(3)); // the same, three attempts
Model.forProducer(trace, Retry.none());              // one attempt, as before this existed
Model.forProducer(trace, Retry.local());             // a flat second, no jitter, same-host endpoint
Model.forProducer(trace, Retry.fromEnv());           // the shipped shape, numbers overridable by env

// and in your own tests — the whole schedule, none of the waiting, and time you control
Model.forProducer(trace, Retry.fibonacciSeconds()
        .withBudget(Duration.ofMinutes(5))
        .with(Pause.NONE)                            // waits return at once
        .with(Now.steppingBy(Duration.ofMinutes(2))));  // ...but the budget still runs out
```

`Retry` is a record of the four things that decide a retry, and `Backoff` is a function from every
failure so far to the next wait — which is where a `Retry-After`-aware schedule goes, without this
library having to grow one. Compose with the shipped schedule rather than replacing it:

```java
Backoff shipped = Retry.fromEnv().backoff();          // jittered Fibonacci
Backoff honoursTheServer = failed -> {
    Duration ours = shipped.before(failed);
    Duration theirs = retryAfter(failed.get(failed.size() - 1));   // null when unsaid
    return theirs == null || theirs.compareTo(ours) < 0
            ? ours
            : theirs.plusSeconds(draw());   // the server's floor, and still a draw on top
};
```

A rule that answers a flat `Duration.ofSeconds(90)` throws away the growth and the draw, and every
rate-limited lane then comes back in the same second — which is the pile-up the jitter is there to
break, rebuilt one layer up. Whatever the server asks for is a floor, not a schedule. `Pause.NONE` exists so that your suite can exercise retries without
living through them; the version everyone writes instead sleeps "just a little" to feel realistic,
and a suite that sleeps is a suite somebody eventually deletes. `Now` is the third seam and the one
that is easy to leave out: with `Pause.NONE` alone no time passes at all, so a budget can never be
reached and the branch that ends a hanging endpoint is never exercised. `Now.steppingBy` moves the
clock every time it is read, which is what an attempt sitting on a stalled socket does.

## The shortest working pipeline

An OpenAI-compatible endpoint in `RATCHET_BASE`, `RATCHET_MODEL` and `RATCHET_KEY`; a `JsonlTrace`;
a map of tool specifications to executors that you write; `Recording.at` around it;
`Model.forProducer` and `Model.forCritic`; an `Asking` per agent, wrapped in `Insisting`, with
`Listening.register` called for each; then `Flow` to compose them and a `Journal` to make the
whole thing resumable. About forty lines.

## Four things that will catch you

**`Listening.register` is explicit.** Call it once per agent, with the prompt actually in force, or
every exchange row is attributed to nobody: 737 of them in one sweep were. `Asking` does not do it
for you, because it is built with a label and you register under a name, and quietly making those
the same string would rewrite the agent column of every row a corpus already holds.

**`Prompts.beside` is a static global.** It is written before any agent is built and read after,
which is the only ordering that matters, and the alternative was threading a path through every
agent factory to serve a feature most runs never use. It is a deliberate trade, and a library that
makes you set a static should say so out loud.

**Two fields on the settled row are called `baseline` and `gate`** whatever your domain is, and the
key field is called `bump`. Those are the names the first consumer's launcher greps and its
dashboard reads, and bash re-reads a running script by byte offset, so that launcher cannot be
corrected while a sweep is up. Generalising the row into a caller-named shape is a later, additive
change. Until then the names are a wire format, and `TheSettledRowIsAWireFormatTest` pins them
character for character.

**`Resume` never learns what your version is.** It takes the same composed string you hand
`Settlement.note`, and it compares the fields that string names against the row on disk, one at a
time, with empty counting as a value. So a field the running side stopped emitting is a dimension
the comparison loses rather than a mismatch it reports, and anything else on the row, a round
number for instance, is invisible to it. That is deliberate: the alternative is this library
holding one consumer's idea of what a version is made of.

## What is not here

The reader half. ratchet writes `settlements.jsonl` and `trace.jsonl` and does not ship anything
that parses them back into a page. That asymmetry is real and worth naming rather than hiding.

## Building

```
mvn -B verify
```

## Licence

Apache 2.0. See [LICENSE](LICENSE).

## A note on the name

`ratchet-llm` is named for what it is, the model layer, rather than for the library it currently
uses. Naming a module after its dependency is a common enough convention, and langchain4j itself
ships `langchain4j-anthropic` and `langchain4j-mistral-ai`, but that convention fits an integration
whose whole purpose is the integration. This module's purpose is ratchet's model wiring, and
langchain4j is how that is built today rather than what it is. A second binding, talking to an
OpenAI-compatible endpoint directly, would sit beside it without either name becoming wrong.

This project is not affiliated with, sponsored by, or endorsed by LangChain4j or LangChain, Inc.
LangChain4j is an independent open-source project, and LANGCHAIN is a registered trademark of
LangChain, Inc. It is named here only as a dependency.
