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
- the **round bound** stops a loop without capping work that is progressing.

None of that is clever. All of it is a post-mortem written down as code, and the comments say which
one. That is the library: the reasons ship in the sources jar, not just the bytecode.

## Two modules, split by dependency

| artifact | depends on | what is in it |
| --- | --- | --- |
| `ratchet-core` | nothing outside the JDK | the flow, the record, the journal, the bounds |
| `ratchet-langchain4j` | `ratchet-core`, langchain4j | the model wiring, the tool loop, the listeners |

Take the first without taking a model client. The split is enforced by the compiler, not by a
convention.

```xml
<dependency>
  <groupId>tech.mikhailov.ratchet</groupId>
  <artifactId>ratchet-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

Java 17 or later. Versions are plain releases and are never republished: a version number here
names one set of bytes forever. There are no SNAPSHOTs, deliberately, because a dependency naming a
moving target is how the thing this replaced became unusable.

## What is in each package

`tech.mikhailov.ratchet.flow` decides what runs next. `Agent` is one method, `String run(String)`,
and everything is one: a model call, a sequence, a walk, a loop, plain code. `Flow` gives you
sequence, selection and iteration over that one notion, plus `resumable`, which is the journal as a
decorator. `Triad` is planner, doer, verifier with the loop held by the verifier rather than the
producer. `Shape` walks the tree that runs, so a picture of the program cannot drift from it.
`Reply` reads the one word a verifier settled on.

`tech.mikhailov.ratchet.record` writes down what happened and reads it back. `Trace` is the one
interface everything reports through. `JsonlTrace` is the file format: one event per line, plus a
settlements file beside it holding the last word per unit of work. `Journal` is the append-only
file a resume reads, torn last line and all. `Json` is the small writer and the tolerant reader for
arguments a model composed. `Digest` is the eight-character fingerprint every row carries.

`tech.mikhailov.ratchet.config` is what the run was told before the code started: `Env`, and
`Prompts`, an on-disk store whose text replaces the code's own, per agent and per variant.

`tech.mikhailov.ratchet.llm` is the langchain4j artifact. `Model` builds the client, with a
thinking budget and a patience that is deliberately never the guard that fires. `Streamed` makes
the guard time since the last token rather than time since the request. `Asking` is one agent: a
system prompt, a closed set of tools, a bounded tool loop. `Reasoning` reads the reasoning off the
wire that the client would otherwise discard, and latches once when it starts repeating itself.
`Listening` records every exchange as the client saw it. `Insisting` re-asks an agent that answered
nothing. `Recording` writes every tool call into the trace whole and returns it bounded.

## The shortest working pipeline

An OpenAI-compatible endpoint in `RATCHET_BASE`, `RATCHET_MODEL` and `RATCHET_KEY`; a `JsonlTrace`;
a map of tool specifications to executors that you write; `Recording.at` around it;
`Model.forProducer` and `Model.forCritic`; an `Asking` per agent, wrapped in `Insisting`, with
`Listening.register` called for each; then `Flow` and `Triad` to compose them and a `Journal` to
make the whole thing resumable. About forty lines.

## Three things that will catch you

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

## What is not here

The reader half. ratchet writes `settlements.jsonl` and `trace.jsonl` and does not ship anything
that parses them back into a page. That asymmetry is real and worth naming rather than hiding.

## Building

```
mvn -B verify
```

## Licence

Apache 2.0. See [LICENSE](LICENSE).
