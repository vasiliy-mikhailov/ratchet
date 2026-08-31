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
| `ratchet-llm` | `ratchet-core` | the model wiring, the tool loop, the listeners |

Neither takes anything outside the JDK. As of 0.14.0 `ratchet-llm` speaks to an OpenAI-compatible
endpoint directly: the request is written with ratchet's own JSON, the reply is read off the SSE
frames, and the tool loop is fifteen lines. It used to take langchain4j and three artifacts behind
it — 13 jars and 7.9 MB inherited by anyone who wanted so much as the retry schedule, including an
NLP toolkit and a BPE tokenizer that nothing here has ever called.

The split stays because the reason for it stays: core is the flow, the record and the journal; llm
is everything that knows an endpoint exists. Take the first without compiling against the second.

```xml
<dependency>
  <groupId>tech.mikhailov.ratchet</groupId>
  <artifactId>ratchet-core</artifactId>
  <version>0.26.0</version>
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
./install.sh v0.26.0 -r ~/.m2-fitness/repository   # into a repository another build reads
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

`tech.mikhailov.ratchet.llm` is the model layer. `Endpoint` is where the model is, as a value you
can hand in rather than three environment variables a launcher has to rename. `Wire` is the client:
one POST, one SSE reader, streaming always, so the liveness guard is time since the last TOKEN
rather than time since the request — and its transport timeout is derived from the ceiling so it
cannot be the guard that fires. `Chat`, `Ask`, `Reply`, `Said`, `Tool`, `Called` and `Calling` are
the vocabulary; `Ending` says why the model stopped and `Spend` what it cost. `Model` builds the
whole chain with a thinking budget. `Asking` is one agent: a system prompt, a closed set of tools,
a bounded tool loop it owns. `Reasoning` latches once when the reasoning starts repeating itself.
`Listening` records every exchange as it happened, attributed by the label the request
carries rather than by matching its system prompt against a global registry. `Retrying` asks a dropped call again, up to
`RATCHET_ATTEMPTS` times, because the journal only preserves a whole node and a reset halfway
through one destroys every call that node already paid for; the wait is a Fibonacci second plus a
draw of up to `RATCHET_JITTER_SECONDS`, so a sweep's lanes do not all come back at the same instant,
and `RATCHET_RETRY_BUDGET_MINUTES` bounds the whole sequence because a count of attempts does not —
ten stalls is three and a half hours. `Insisting` re-asks an agent that answered nothing — and sits
above `Retrying`, so a dead connection is never read as an answer. `Recording` writes every tool
call into the trace whole and returns it bounded.

### Taking one piece without the rest

`Model` builds a whole chain. If you have your own client, your own bounds, or an endpoint you
resolve per call, take the parts instead. `Chat` is one method, so anything can be one:

```java
Chat retried = Retrying.on(yourChat, Retry.fibonacciSeconds(), trace);   // the retry alone
Supplier<T> any = Retrying.around(() -> yourCall(), retry, trace);       // ...around anything
Chat guarded = Wire.to(endpoint, sampling,                               // ...with your own bounds
        Watch.shipped().withStall(Duration.ofMinutes(45)), true, trace);
Predicate<Throwable> worth = Retrying.transportFailures();               // just the judgement
Backoff schedule = Backoff.fibonacciSeconds();                           // just the schedule
```

`around` is the same loop with no message model in the signature, asked for in ratchet#8 by a
consumer whose agent runtime is a third party's and takes a langchain4j `ChatModel` they cannot
change. Their measurement was that the whole of `Retrying` touched the message model on one line, so
a loop that was already agnostic was reachable only through one concrete type. It retries things
that were never model calls too — an HTTP fetch, a tool invocation, a push to a registry. The call
is re-run whole, so it must be safe to repeat.

The last two are why the dependency had to go: `Backoff` is a pure function of a list of failures
and imports nothing but `java.time` and `java.util`, and it used to arrive with eight megabytes of
model client behind it.

`transportFailures()` is the half that is hard to get right. It now needs one comparison, because
the client is ours and `Refused` carries the status it read off the response: 408, 429 and 5xx are
worth another request, every other 4xx is the server saying the request itself is wrong. It refuses
`GaveUp` (the ceiling, handing the slot back on purpose), `Truncated` (the identical request meets
the identical budget), `Reasoning.LoopDetected` (greedy decoding cannot leave a cycle it entered)
and an interruption, and retries anything it does not recognise.

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
`Model.forProducer` and `Model.forCritic`; an `Asking` per agent, wrapped in `Insisting`; then
`Flow` to compose them and a `Journal` to make the whole thing resumable. About forty lines.

## Four things that will catch you

**A hand-built `Ask` should say who is asking.** `new Ask(messages, tools, "agent:doer")`, or the
exchange row is attributed to nobody. `Asking` fills it in from its own label, so a pipeline built
the ordinary way never has to think about it. Until 0.14.0 this was recovered by matching the system
prompt against a process-global registry you had to remember to populate — see ratchet#10 for what
that cost, and note that a consumer upgrading from 0.13.x deletes their `Listening.register` calls
rather than replacing them.

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

**Tools — until 0.22.0.** `Tool` is still three strings and `Asking` still has no opinion about
what it is handed; that principle is about the LOOP. What it did not justify was leaving every
consumer to write the same five hundred lines: one carried an entire client library because four
file tools came from it, and replacing them took 505 lines while removing 1,053. So there is a third
module, `ratchet-tools`, and taking it is a choice.

It offers `read`, `write`, `edit`, `list_dir`, `grep`, `glob`, `bash`, `job_output`, `job_list`,
`job_kill`, `todo_write`, with dsh's own snake_case schemas, so a model that has met theirs meets
these.

**`grep` and `glob` arrived in 0.23.0 because [TOOLS.md](TOOLS.md) was wrong about them.** That
measurement — three calls in six runs, and `bash` can do it — is real, but a second consumer's
corpus puts `grep` SECOND at 1,797 calls and 15.9%, in 24 of 24 lanes, with `glob` at 8.7% in all
24. Three orders of magnitude apart means two kinds of agent, not two samples: an agent asked what
shape a missing type must be can only answer by finding every use site of a name. The union ships.

**It is not a sandbox.** A root directory bounds the file tools and bounds nothing about `bash`,
which runs as whoever runs the JVM. dsh ships four sandbox packages and a policy layer around its
shell; this ships a working directory and says so.

**A thought row says whose it is, from 0.25.0.** `Trace.Event` has always carried an `agent` and
`traceEvents`/`traceFind` have always narrowed by it, but `thought` was the one writer that could
not supply one — so every reasoning row was unattributed and silently missed every agent-narrowed
read, which returns an empty list and reads exactly like an honest absence. Measured twice before it
was fixed: 737 rows in one sweep attributed to nobody (written down in `Listening`'s own javadoc and
then left while the two defects beside it were repaired), then a consumer whose nine agents all
appeared in the record as one. `thought(agent, finishReason, thinking, content)` is the only form, and 0.26.0 removed the
three-argument one it shipped deprecated for exactly one version. A deprecated method that still
compiles is a lossy path somebody is still on — an implementation overriding only it wrote
unattributed rows exactly as before and the record said nothing. **Every consumer takes a one-line
compile error; none of them takes a corpus that quietly stopped answering.** The end-to-end test
runs over a real loopback socket, because the half that was broken was the LINK between
`answer(ask)` and the row, and a link is only visible from both ends.

**Background work is bounded by processes, from 0.24.0.** `run_in_background` returns immediately,
and returning immediately is how a bounded thing escapes its bound: the foreground timeout bounds
one call, the registry's cap of 32 bounds how many jobs stay *readable* and never evicts a live one,
and nothing bounded the machine. `Jobs.RUNNING` is 16 as a runaway guard, settable per caller via
`new Jobs(n)` or `Kit.at(root, timeout, n)` — anyone sharing a box should say less. Found by
analogy, not by failure: dsh's `maxParallelToolCalls` cannot bound subagent concurrency for the same
mechanical reason, and one parent was measured holding 13 children under a cap of 1.

**`Kit.withoutShell(root)`** is the set with no `bash` and no job tools — nothing in it starts a
process. It exists because a consumer enforces every guarantee they have at the tool boundary
(an edit outside one directory reverted before the next turn, the credential store refused by path,
the test configuration unweakenable), runs unattended against repositories they do not own, and was
right that filtering a shell out of a kit that HAS one is a refactor away from handing it over. It
is still not a sandbox; it is narrower than that and says exactly what it is.

**A compaction policy.** `Between` hands a caller the conversation before each request and takes
back what to send; deciding when and what to drop needs the route's real context window and whether
anyone is watching, neither of which a library called from inside somebody else's program can see.
The same goes for `Spilling`: ratchet decides when a result is too big, and the caller decides
where the rest of it goes. See [SPEC-context.md](SPEC-context.md).

## Building

```
mvn -B verify
```

## Licence

Apache 2.0. See [LICENSE](LICENSE).

## A note on the name

`ratchet-llm` was called `ratchet-langchain4j` until 0.2.0, and was renamed on the argument that a
module should be named for what it is rather than for the library it happens to use. The paragraph
that stood here ended: *"A second binding, talking to an OpenAI-compatible endpoint directly, would
sit beside it without either name becoming wrong."*

In 0.14.0 that binding was written, and it did not sit beside — it replaced. The name survived the
change, which is the whole of the argument for having made it.

ratchet no longer depends on langchain4j. It is an independent open-source project and this one is
not affiliated with, sponsored by, or endorsed by it or by LangChain, Inc.
