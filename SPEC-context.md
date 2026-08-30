# What the model sees

A specification for ratchet 0.19–0.20. One principle and five pieces; **all five are in as
of 0.20.0**, and what each of them turned out NOT to be is recorded beside what it is.

> **Truncate the view, never the record, and make the view's link to the record explicit.**

And one prior constraint on all of it, which decides what belongs here at all:

> **The harness is smarter than the library. Don't lose, don't lie, don't decide.**

A harness has a human watching, an abort signal, a session and the whole process. ratchet is called
from inside somebody else's program and knows nothing about it — not the corpus, not the tools, not
the context window, not whether anyone is watching. Every defect a consumer reported this week was
this library deciding something that was not its to decide: when work should stop, what was worth
keeping, what silence meant, what a verdict looked like. Each was reasonable to whoever wrote it and
wrong for a caller they had not met.

So the test for anything below is: **does it need to know something only the caller knows?** If it
does, it is a seam and not a feature. If a consumer could have written it themselves given the right
hook, give them the hook.

Every bound this library shipped before 0.16 broke that rule in the same direction: it cut at the
moment of *writing*, where the information is still available and nobody yet knows what will be
needed. `Keeping` and `Telling` moved the decision to the caller. This moves it to the point of
*sending*, which is the only place it can be made with the whole thing in hand.

The design is measured against `@deepseek-ai/dsh`, which implements all five. Numbers below are
theirs unless marked otherwise.

---

## 1. `Retained` — the primitive

One type owning one question: *what did we keep, and what did we omit?*

```java
public record Retained(String text, Omitted omitted) { }
public sealed interface Omitted { record None() ...; record Exact(long bytes) ...; record Unknown() ...; }
```

- **Characters, not bytes** — corrected in the build. Bytes are right where the thing bounded is a
  byte stream; every caller here bounds text a person or a model will read, which is what those
  callers already promise.
- **Code-point boundaries at every cut.** Ratchet clips with `String.substring` in six places.
  On the wire a split surrogate pair becomes `?`. In the record it is worse: `Files.writeString`
  throws `UnmappableCharacterException` on the lone surrogate, `JsonlTrace` catches the `IOException`
  and prints `trace: Input length = 1`, and **the whole row is never written** — under a comment
  reading "a silently absent trace is worse than a loud one". Verified on this JDK. Shipped in
  0.18.1. Fixed in 0.20.0: a tool result with an emoji at the cut now writes its row where it used
  to write none.
- **Two budgets, not one** — corrected in the build. `head` bounds CONTENT and adds the notice;
  `within` is a hard CAP and pays for the notice out of it. Reserving everywhere was dsh's answer to
  a cap on bytes entering a model's context and is wrong for a readability bound: at `upTo(40)` it
  yields seven characters of content and thirty-three of apology. Both keep the one invariant the
  191-into-180 defect actually proved — **a result is never larger than what it replaced**.
- **Idempotence belongs to `within`**, and that falls out of what each promises rather than being a
  defect in either: `head` returns budget plus notice, which is by construction over budget.
- **`Omitted.Unknown` is a real answer.** A record that cannot say how much must say that, not
  nothing.
- **"Omitted" is a budget fact, never "incomplete."** A permission failure, an unreadable file or a
  provider error is a tool-domain field. Conflating them is the bug this naming most invites.

Replaced six clip sites in 0.20.0. Three wrote the *same sentence* differing only in leading
whitespace — and that whitespace turned out to be the half that was NOT accidental, so the sentence
is shared and the separator is the caller's: a summary promising one line per event cannot open its
notice with a newline. `Recording`'s was the best of the six, the only one that said what to DO
about the loss, and that half survives as `recoverableBy`. `Refused` wrote a bare `…` carrying no
magnitude and now carries one. `Reasoning` cut the offending line at 90 characters with **no marker
at all**, in the diagnostic for a runaway where the repeated line is the evidence.

## 2. The `LENGTH` guard

`Wire.read()` refuses a truncated reply only when the content is blank **and** the call list is
empty **and** the finish is `LENGTH` — a three-way conjunction. So a reply cut mid-tool-call fails
the second clause and returns carrying `Ending.LENGTH` and half-written JSON. `Asking` never
inspects `ending()` — zero references in the file — and executes it.

**On a `LENGTH` finish carrying tool calls, drop the calls.** Not throw: throwing ends the lane,
which is the cost being removed. Leave the finish reason readable, because it is the only evidence
of which turns hit the wall.

**Order matters, and getting it wrong reintroduces the throw.** A blank-content reply whose only
output was a tool call satisfies all three clauses the moment the call is dropped, so a drop placed
before the guard turns exactly the case this section exists for into the `Truncated` it rules out.
The refusal is decided on the reply as received; the drop is applied after.

Three independent implementations agree on every choice here — dsh's assembler, java-faker's
`Model.whole`, and this. The guard asks *was this finished*, where the old one asked *is there
anything to act on*.

## 3. Somewhere to put the rest — a seam, not a store

A tool result over its cap is not cut and thrown away. But **ratchet must not own the store**: where
the whole text goes is a fact about the caller's filesystem, session and retention, none of which
this library can see. So it owns the shape of the notice and nothing else.

```java
public interface Spilling { String kept(String whole, int room); }   // returns preview + locator
```

`Recording` already takes the cap. What it lacks is a way for the caller to say *and here is where I
put the rest*. Given that, the model-facing result becomes preview + magnitude + locator + retrieval
hint, and the whole text lives wherever the harness decided:

```
(Omitted 41208 bytes. Full formatted result stored at: /…/session-…/a1b2c3d4e5f6-web_fetch.txt.
 Use read with offset/limit, or grep this path to search within it.)
```

A magnitude tells a reader something is missing; a locator lets them go and get it. `Spilling.none()`
is the honest fallback for a caller with nowhere to point: it says how much and what to do, which is
the most that can be said without a store.

Skip `read` results, or a `read → spill → read again` loop follows. That rule is the harness's too,
but it is worth writing down because both implementations that got here first had to learn it.

## 4. Tools — not ratchet's, and that is the finding

A locator nothing can visit is a longer way of saying "truncated". So (3) needs `read` with
offset/limit and `grep` to be worth anything — **and neither belongs here.** `Tool` is three strings
and this library ships none, deliberately: the loop must not decide what a caller's agent can do.

What was missing is not a module but an answer. Every caller guesses the same list, and there is now
a measured one — [TOOLS.md](TOOLS.md), 816 calls across six runs, with its own caveat that the runs
were uncontrolled and it is evidence of *which tools get reached for* rather than a ranking to tune
against. Reference it from the README. Do not ship it as code.

## 5. Compaction — the seam, pulled from dsh's API rather than invented

Read `@deepseek-ai/dsh-session/lib/types/surface.d.ts` before building any of this. The shapes below
are theirs, not a paraphrase.

**The inversion that decides everything else.** In dsh the append-only log is the source of truth and
*the message history is derived from it*. Ratchet has this backwards: `Asking` holds a
`List<Said>` and that list IS the conversation, so there is nothing to derive from and nothing to
replace against.

```ts
SurfaceOp              = 'append' | { op: 'replace', start, end }
SurfaceFoldReplacement = { seq, start, end, shadowedSeqs }
deriveEventMessage(event) -> Message | null      // THE per-node projection rule
```

Three properties fall out of that, and they are what ratchet actually needs:

- **A replacement is an append.** Compaction never mutates: it appends a node carrying
  `surfaceOp: replace(start, end)` and `sourceEventSeqs`, and the shadowed events stay in the log.
  "Nothing is destroyed; a second view is shortened."
- **One projection rule means replay is exact.** `deriveMessages` folds it over the live surface, and
  an external reconstructor folds the *same function* over a log prefix to rebuild the exact messages
  any past request was built from.
- **Two audiences, one log, separated by a predicate.** `isAppendSurfaceEvent` is the human
  transcript; the model-visible surface deliberately shadows replaced ranges and is *"the wrong
  source for a human transcript — a landed replacement would erase conversation the user already
  saw."* Ratchet has the same split already and has never named it: `JsonlTrace` is the transcript,
  the conversation is the surface.

### What ratchet takes — built in 0.21.0

Not `Session`, not persistence, not sequence numbers that outlive a process. Those are a harness.
Two things, because only the library holding the conversation can provide them:

```java
public final class Turns {
    void said(Said s);                          // append
    void replace(int from, int to, Said with);  // append a replacement citing what it shadowed
    List<Said>  messages();                     // the surface: what the model sees
    List<Entry> spoken();                       // the transcript: everything, replacements included
    int generation();                           // how many replacements have landed
}

@FunctionalInterface
public interface Between { void turn(Turns turns); }   // called before each request
```

**A RANGE, NOT A NEW LIST**, and 0.20.0 got that wrong. Returning a list said what to SEND: ratchet
kept the original so nothing was destroyed, but nobody could tell afterwards which turns a
compaction had shadowed, or build the next one on the last. A replacement is an append that cites
the positions it covered, which is dsh's shape and the reason a compacted conversation stays
legible.

**Two audiences, one log**, which ratchet had all along and never named. `messages()` shadows what
was replaced; `spoken()` does not, because a landed replacement would erase conversation a reader
has already seen.

**The edge check is not the caller's to skip.** `replace` refuses a range that would cut a call from
its result rather than trusting the one it was handed — the guard against orphaned calls must not be
the thing that makes one.

**The pairing helpers are ratchet's because only it knows what a `Said` is.** Contract taken from
theirs: *true when no unanswered tool call crosses the cut* — and note that a tool result with no
preceding open call **throws** rather than returning false. That is corrupt state, not an unbalanced
edge, and conflating them is how an orphaned call gets manufactured by the thing meant to prevent it.

### What ratchet leaves

Trigger policy, retention ratios, summarization, the token meter. dsh resolves capacity from its own
adapter and prices the envelope at every step boundary; a library called from inside somebody else's
program has none of that. Its numbers are here to be copied by whoever writes the policy: compact at
`0.8` of the routed window, retain `0.16`, prune tool results at `8192 → 4096 + marker + 1024` first,
remeasure, summarize only if still over. Eleven cycles, one summarization, 788 prune marks over a
4.1 MB session — **ten of eleven absorbed with no model call at all.**

## Order — as built

1. `Retained` (0.19), wired through all six clip sites in 0.20.0. Two budgets, not one: a content
   bound and a hard cap, because implementing it showed those are different questions.
2. The `LENGTH` guard (0.19). Order pinned: refusal is decided on the turn as received.
3. `Spilling` (0.20.0) — ratchet decides WHEN, the caller decides WHERE.
4. `TOOLS.md`, linked from the README. Not code, which was the finding.
5. `Between` (0.20.0) — the conversation before each request, and the pairing helpers.

Two things changed shape between spec and build, both because the code refused the spec:
reserving the notice out of every budget was dsh's answer to a hard byte cap and wrong for a
readability bound, and the three markers' differing whitespace turned out to be the half that was
not accidental.

**What is deliberately still missing, and it is all harness:** persistence and replay across
processes, per-step token metering against a route's real capacity, and the compaction policy
itself. dsh has all three and needs them; a library called from inside somebody else's program can
see none of what they depend on. The structural gap — a conversation you can derive from and replace
against — closed in 0.21.0.

## Out of scope

dsh's plugin architecture — service definition, provider and policy as separable packages — is
cordis-shaped and does not transfer. Its event-sourced session with sequence numbers, lock brackets
and orphan detection is the right design for a harness and too much for a zero-dependency library.
Take the invariants, not the machinery.
