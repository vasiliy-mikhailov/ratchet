# What the model sees

A specification for ratchet 0.19–0.22. It has one principle and five pieces.

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

- **Bytes, not characters.** A pipe and an HTTP body are byte streams.
- **UTF-8 boundaries preserved at every cut.** Ratchet clips with `String.substring` in six places.
  On the wire a split surrogate pair becomes `?`. In the record it is worse: `Files.writeString`
  throws `UnmappableCharacterException` on the lone surrogate, `JsonlTrace` catches the `IOException`
  and prints `trace: Input length = 1`, and **the whole row is never written** — under a comment
  reading "a silently absent trace is worse than a loud one". Verified on this JDK. Shipped in
  0.18.1, so any tool result carrying an emoji or an astral-plane character at a clip boundary loses
  its record row today.
- **The notice's cost is reserved out of the budget**, so a replacement is *strictly smaller* than
  its input and a second pass changes nothing. Validated where the budget is set, not at runtime.
  0.18.1 fixed the same defect by declining to clip; reserving is the structural form.
- **`Omitted.Unknown` is a real answer.** A record that cannot say how much must say that, not
  nothing.
- **"Omitted" is a budget fact, never "incomplete."** A permission failure, an unreadable file or a
  provider error is a tool-domain field. Conflating them is the bug this naming most invites.

Replaces six clip sites. Three of them — `Listening`, `JsonlTrace`, `Asking` — write the *same
sentence*, `... (truncated, total N chars)`, differing only in leading whitespace, which is the
argument for one implementation in a sentence. `Recording` writes a fourth, and is the only one that
tells the reader what to do about it. `Refused` writes a bare `…` carrying no magnitude. And
`Reasoning` cuts the offending line at 90 characters with **no marker at all**, in the diagnostic
for a runaway, where the repeated line is the evidence.

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

A magnitude tells a reader something is missing; a locator lets them go and get it. `Refused` still
has neither.

Skip `read` results, or a `read → spill → read again` loop follows. That rule is the harness's too,
but it is worth writing down because both implementations that got here first had to learn it.

## 4. Tools — not ratchet's, and that is the finding

A locator nothing can visit is a longer way of saying "truncated". So (3) needs `read` with
offset/limit and `grep` to be worth anything — **and neither belongs here.** `Tool` is three strings
and this library ships none, deliberately: the loop must not decide what a caller's agent can do.

What was missing is not a module but an answer. Every caller guesses the same list, and there is now
a measured one — `TOOLS-2026-08-30.md`, 816 calls across six runs, with its own caveat that the runs
were uncontrolled and it is evidence of *which tools get reached for* rather than a ranking to tune
against. Reference it from the README. Do not ship it as code.

## 5. Compaction — the policy is the harness's; the seam is ours

Everything about *when* and *what* to compact needs things ratchet cannot see: the route's real
context window, which results are cheap to re-fetch, whether a human is watching. dsh resolves
capacity from its own adapter and prices the whole envelope at every step boundary. A library called
from inside somebody else's program has none of that.

**So ratchet does not compact. It makes the conversation compactable**, which is the one thing only
it can do, because only it holds the list:

- the conversation is addressable between turns, not a private `List<Said>` discarded at the end
- a caller can read it and hand back a replacement before the next request
- tool-pairing boundaries are exposed, so a caller can find an edge where no unanswered call
  crosses — **never cut a call from its result**, which is the shape that poisons a conversation and
  can wedge a server's parser
- what was replaced stays in the record, linked to what replaced it

That is a seam, and it is small. What sits on top of it — prune tool results free first, remeasure,
summarize only if still over, retain a recent tail — is the harness's policy, and dsh's numbers are
there to be copied by whoever writes one: eleven compaction cycles, one summarization, 788 prune
marks over a 4.1 MB session. Ten of eleven absorbed with no model call at all.

**The prerequisite is still real and still ours.** `Asking` builds a `List<Said>` in memory and
discards it; `Journal` holds per-node answers and `JsonlTrace` a bounded summary. Neither is the
conversation. Until it is kept, there is nothing for a harness to compact.

## Order

**1 and 2 are done** (0.19). **5's seam next**, because it is the only piece here that nobody else
can build and everything else is more useful once the conversation is addressable. **3 after it.**
**4 is a README link, not code.**

## Out of scope

dsh's plugin architecture — service definition, provider and policy as separable packages — is
cordis-shaped and does not transfer. Its event-sourced session with sequence numbers, lock brackets
and orphan detection is the right design for a harness and too much for a zero-dependency library.
Take the invariants, not the machinery.
