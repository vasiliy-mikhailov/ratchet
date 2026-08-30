# What the model sees

A specification for ratchet 0.19–0.22. It has one principle and five pieces.

> **Truncate the view, never the record, and make the view's link to the record explicit.**

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

## 3. `Spill` — the whole thing stays, addressable

```java
public interface Spill { Locator save(String whole); }
```

A tool result over its cap is not cut. The full text is saved and the model-facing result becomes
preview + magnitude + **locator** + **retrieval hint**:

```
(Omitted 41208 bytes. Full formatted result stored at: /…/session-…/a1b2c3d4e5f6-web_fetch.txt.
 Use read with offset/limit, or grep this path to search within it.)
```

Ratchet's markers carry magnitude and nothing else. Three have since 0.2.0 or 0.15.0; `Trace.happened`'s
gained one only in 0.16.1; `Refused`'s still has none. A magnitude tells a reader something is
missing. A locator lets them go and get it.

Skip `read` results when spilling, or a `read → spill → read again` loop follows.

## 4. Tools — what makes a locator real

A locator nothing can visit is a longer way of saying "truncated". `read` with offset/limit and
`grep` are the minimum for (3) to function at all.

Ratchet ships no tools, deliberately: the loop must not decide what a caller's agent can do. But
every caller then guesses the same list, and there is now a measured answer to that guess —
`TOOLS-2026-08-30.md`, from 816 calls across six runs. Read its caveat: the runs were not controlled
against each other, so it is evidence of *which tools get reached for*, not a ranking to tune
against.

This is an opt-in module, not a change to the loop.

## 5. Compaction — and the thing ratchet does not have

Staged, and the free stage does nearly all of it. Measured over one 4.1 MB session: **eleven
compaction cycles, one summarization, 788 prune marks.** Ten of eleven absorbed model-free.

1. Price the envelope against the *route's* real capacity, resolved from the endpoint, not a constant.
   Compact at `0.8`; retain `0.16` verbatim.
2. Prune oversized tool results — model-free, no LLM call. `8192 → 4096 head + marker + 1024 tail`.
3. Remeasure. **If pressure is safe, stop.** Summarization is the exception.
4. Only then summarize the oldest whole units, preserving a recent tail.

**Never cut a tool call from its result.** Edges snap to a boundary where no unanswered call
crosses. This is a hard requirement: an orphaned call is the shape that poisons a conversation and
can wedge a server's parser.

**The prerequisite.** All of the above assumes a *surface* — a rewritable view — over a *log* that
keeps everything, with each replacement carrying its source range. Ratchet has no conversation store
at all: `Asking` builds a `List<Said>` in memory and discards it. `Journal` holds per-node answers;
`JsonlTrace` holds a `Keeping`-bounded *summary* of exchanges. Neither is the conversation.

So compaction is not a feature that can be added to `Asking`. It requires ratchet to keep the
conversation append-only first, with the surface as a view over it. That is a larger change than
the other four together, and it is the decision that shapes (3) and (4).

---

## Order

**1 and 2 first** — small, and 1 blocks everything after it. **4 before 3**, because spill without a
reader hands the model an address it cannot visit. **5 last**, and only after its storage shape is
settled.

## Out of scope

dsh's plugin architecture — service definition, provider and policy as separable packages — is
cordis-shaped and does not transfer. Its event-sourced session with sequence numbers, lock brackets
and orphan detection is the right design for a harness and too much for a zero-dependency library.
Take the invariants, not the machinery.
