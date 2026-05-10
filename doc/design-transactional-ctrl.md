# Transactional ctrl tree — in-memory design

> **Status**: Draft — review complete, foundational reframing pending.
> This document captures the transaction model as designed and the open questions
> surfaced during review. A prerequisite foundational document
> (`design-ctrl-foundation.md`) must be written before this proceeds to
> implementation. Several sections are marked provisional pending that work.

## Scope

This document covers the in-memory transaction model only.
Durable logging, sidecar integration, replay, and Datalog queries are explicitly
out of scope and are deferred until this layer is stable.

---

## Motivation

The current ctrl tree is a flat KV store wrapped in a system-state atom.
`set!` and `send!` mutate the tree and fire watchers as `(fn [path value])`.
This model works but loses information that is useful — and increasingly
necessary — as the system grows:

- **No causality**: a watcher cannot tell whether a value changed because the
  user typed at the REPL, because an incoming MIDI CC arrived, because a
  trajectory is sweeping, or because another watcher fired. All look the same.
- **No source chain**: a cascade of watcher-triggered writes cannot be
  attributed to the originating event. Debugging a feedback loop means
  inferring cause from timing.
- **No beat time**: writes carry no musical position. A write at beat 32.25 is
  musically meaningful; a write at "14:23:07.482" is not.
- **Opaque undo**: the undo stack stores `{:tree :serial}` snapshots. Rolling
  back is possible; understanding *what changed and why* is not.
- **Errors as side effects**: watcher exceptions and loop body failures are
  splattered to stderr and lost. There is no first-class representation of
  error state in the tree.

The transaction model makes change a first-class value. A `set!` or `send!`
produces a transaction map before mutating state. Watchers receive the full
transaction, not just `(path, value)`. The materialized view (the `:tree`
atom) stays as the primary read path and does not change shape.

---

## Foundational reframing (prerequisite)

> The review of this document surfaced a ground truth that was not previously
> explicit: **the ctrl tree is not a parameter store**. It is a semantic model
> of the studio — for whatever definition of studio is relevant to a given
> session. The current values of parameters within the tree are our best
> estimate of what the studio looks like if you replayed the transaction log
> from session start to the current instant.
>
> This reframing has consequences throughout this document. The full exploration
> belongs in `design-ctrl-foundation.md`, which should be written before this
> proceeds to implementation. Sections that are most affected are marked
> **[provisional]**.

Key consequences of the reframing:

- The materialized view is a *projection* of the transaction log, not ground
  truth. `ctrl/get` returns our best current estimate, not a guaranteed fact
  about the physical world.
- The shadow state problem (write-only hardware like NightSky, Volante) is not
  a special case — it is the general case. All parameter values are shadows.
  Confidence varies; epistemological status does not.
- Replay is definitional, not a feature. "Current state" *is* the log replayed
  to now.
- This makes accidental complexity in the current implementation visible: some
  of what looks like parameter storage is actually an incomplete model of studio
  state.

---

## Transaction representation

```clojure
{:tx/id      #uuid "..."          ; squuid — semi-sequential for natural ordering
 :tx/beat    32.25                ; universal timeline beat (primary — see below)
 :tx/wall-ns 1234567890000000000  ; System/nanoTime (secondary; for debug)
 :tx/source  { ... }              ; [provisional] who/what caused this — see below
 :tx/changes [{:path   [:filter/cutoff]
               :before 0.5
               :after  0.7}]}     ; one entry per path written
```

### `:tx/id` — squuid

UUIDs are generated as squuids (semi-sequential unique identifiers, as in
Datomic). The time-based prefix preserves natural chronological ordering while
the random suffix preserves uniqueness. Transactions are sensibly orderable by
`:tx/id` alone, with beat time as the primary musical ordering key and squuid
as a tiebreaker.

### `:tx/beat` — universal timeline

Beat time is always expressed in the **universal timeline's coordinates** —
never in a loop's local interpretation of that timeline. A loop running at
half-speed, in 7/8, or phase-shifted still commits transactions at the
universal beat position. Its local perception is a derived view over the
universal timeline, not a replacement for it.

The universal timeline is sourced from a single function `(timeline/current-beat)`:
- When Link is present: the Link timeline beat position.
- When Link is absent: beats derived from the local JVM clock since `start!`.
- Before `start!`: 0.0.

`loop/*beat*` inside a loop body *is* the universal timeline's current position
for that tick — it is not a local clock. All callers, inside or outside a loop
body, use the same concept.

This invariant enables temporal transformations on replay: a session replayed
at a different BPM is arithmetic on `:tx/beat` values. Replay is only coherent
if every transaction carries a universal position.

A transaction with multiple `:tx/changes` entries shares a single beat time —
one logical musical gesture, one position.

### `:tx/source` [provisional]

```clojure
{:source/kind    :user          ; see taxonomy below
 :source/id      :repl          ; or a loop name, device id, etc.
 :source/parent  nil}           ; tx/id of the transaction that caused this one,
                                ; or nil if this is a root cause
```

> **Provisional**: the source taxonomy and what `:source/kind` actually encodes
> are open questions surfaced during review. See open questions below. The
> shape here is directionally correct but should not be treated as settled.

**Provisional source taxonomy** — `:source/kind` values:

| Kind | Meaning | `:source/id` |
|---|---|---|
| `:user` | REPL or MCP tool call | `:repl`, `:mcp`, etc. |
| `:link-peer` | Ableton Link tempo change | peer identifier |
| `:input` | Any inbound external signal | device/transport id |
| `:trajectory` | `apply-trajectory!` sweep | trajectory name or path |
| `:loop` | `deflive-loop` body write | loop name keyword |
| `:supervisor` | Supervisor recovery action | `:supervisor` |
| `:watcher` | Watcher-triggered cascade | watcher's `watch-key` |
| `:undo` | Compensating transaction from `undo!` | `:undo` |
| `:error` | Error event in the system | originating context |

Note: `:midi-in` and `:osc-in` from the initial draft are replaced by `:input`.
Transport (MIDI, OSC, or something richer) is a property of `:source/id` or a
subordinate field, not a category of cause. The ctrl tree operates at the
semantic layer; transport is delivery infrastructure below that layer.
Watchers react to state change in the ctrl tree regardless of how the value
arrived.

The open question of whether `:source/kind` does real work (system behaviour
differs by kind) or is purely descriptive metadata is marked for the
foundational design session.

Intent vs consequence is expressed via `:source/parent`: a causality chain is
traceable from any transaction back to its root cause via parent links.

---

## Errors as first-class transactions [new]

Errors — watcher exceptions, loop body failures caught by the bombproof catch,
cascade aborts, cycle detection — are committed to the ctrl tree as transactions
rather than splattered to stderr.

An error transaction commits a value to a well-known path:

```clojure
;; loop body exception
[:loops :kick :last-error]  →  {:error/type    :exception
                                 :error/message "..."
                                 :error/beat    32.25
                                 :error/source  {:source/kind :loop :source/id :kick}}

;; watcher cascade abort
[:system/errors]  →  (conj existing {:error/type   :cascade-depth-exceeded ...})
```

stderr emission becomes a watcher on `[:system/errors]` and
`[:loops <name> :last-error]` — opt-in, replaceable, silenceable in tests.

This gives REPL-observable error history: `(ctrl/get [:loops :kick :last-error])`
tells you what went wrong and when, rather than requiring you to have been
watching stderr at the right moment.

---

## Commit path

```
(ctrl/set! path value opts)
  │
  ├─ build transaction map (tx/id, tx/beat, tx/source, tx/changes)
  │    id     ← (squuid)
  │    beat   ← (timeline/current-beat)   ; universal, always
  │    source ← *current-tx-source* dynamic
  │    before ← captured inside swap! (avoids the send-at! read-after-write race)
  │
  ├─ swap! @system-ref:
  │    (fn [s]
  │      (let [before (get-node s path)]
  │        (-> s
  │            (put-node path value)
  │            (update :tx-log conj (assoc-in tx [:tx/changes 0 :before] before))
  │            (update :serial inc))))
  │
  └─ dispatch-watchers! tx new-state       ; see watcher contract
```

Note: `:before` is captured *inside* the `swap!` to avoid the race in the
current `send-at!` implementation, which does the atom swap then re-reads the
node for MIDI dispatch. Both reads must see the same consistent state.

### `*current-tx-source*` dynamic var

```clojure
(def ^:dynamic *current-tx-source*
  {:source/kind :user :source/id :repl :source/parent nil})
```

Callers bind this via `with-source`:

```clojure
(ctrl/with-source {:source/kind :loop :source/id :kick}
  (ctrl/set! [:loops :kick :vel] 80))
```

`with-source` is a macro that binds `*current-tx-source*`. `:source/parent` is
set automatically by the watcher dispatcher — callers never set it manually.

---

## Watcher contract

### New signature

```clojure
(fn [tx state] → nil | seq-of-effects)
```

- `tx`    — the full transaction map that triggered this watcher.
- `state` — the new materialized state *after* `tx` was applied.
- Return  — a seq of `{:path [...] :value v}` effect maps, or `nil`/empty.

Watchers are pure functions of a transaction and state. They do not call
`ctrl/set!` directly; they return data describing what should change. The
commit machinery turns each effect into a child transaction with
`:source/kind :watcher`, `:source/id watch-key`, and
`:source/parent (:tx/id tx)`.

The ctrl tree is the semantic layer. A watcher reacts to *state change*,
not to the transport or mechanism that caused it. Watchers should be written
as if transport does not exist.

### Legacy compatibility

> There is no external-facing legacy. All watcher callsites are internal.
> Migration to the new signature is a single sprint: update all watchers in
> mod-route!, trajectory, peer broadcast, and WebSocket at once. No compat
> shim is needed. This is a decision for the implementation sprint, not a
> design constraint.

### Cascade and cycle detection

The commit loop tracks cascade depth. Exceeding `*max-watcher-depth*` (default
8) aborts the cascade and commits an error transaction (not a stderr print).
Cycle detection suppresses writes where the target path already appears in the
transaction's ancestry chain, with an error transaction recording the abort.

---

## Materialized view vs transaction log

```clojure
{:tree     { ... }       ; CtrlNode tree — primary read path, unchanged
 :serial   N             ; structural change counter — unchanged
 :tx-log   [tx tx tx]    ; recent transactions, bounded ring (default 1000)
 :tx-depth 0             ; current cascade depth
 :undo-stack [ ... ]
 :checkpoints { ... }}
```

**`:tree` is the primary read path.** `ctrl/get` is a single map lookup.
Nothing about reads changes.

**`:tx-log` is bounded and in-memory only.** It is a diagnostic window and
the input to whatever durable logging arrives later — not the source of truth
for current state, and not a replacement for the undo stack.

---

## Undo

Phase 1: snapshot undo is unchanged. The undo stack stores `{:tree :serial}`
entries; `undo!` restores the prior snapshot directly.

The transaction log enables a layered undo model for a future phase:
- In-process stack handles the common case (last few changes) — fast, O(1).
- When the stack is exhausted, fall back to the nearest preceding snapshot in
  the persistent log and replay forward from there.
- The in-process stack depth limit becomes the boundary between fast undo and
  log-backed undo, not an absolute limit on how far back you can go.

Compensating transactions (`:source/kind :undo`) are the right long-term model
but are deferred until the persistent log exists. For write-only hardware
targets, compensating transactions re-send the last known shadow value — which
may not match actual device state (see open questions).

The existing `panic!`/`checkpoint!` API is unchanged.

---

## Multi-change transactions and curve values [provisional]

A filter sweep is one musical gesture. It should be one transaction carrying a
curve description, not 50 transactions carrying individual sample values:

```clojure
{:tx/changes [{:path   [:filter/cutoff]
               :before 0.2
               :after  {:tx/curve    :smooth-step
                        :tx/from     0.2
                        :tx/to       0.8
                        :tx/duration 4.0}}]}
```

The 50 sample values are a sidecar delivery detail, not a transaction concern.
This connects to the schedule-over bundle concept: one IPC frame carrying a
curve description, sampled by the sidecar at whatever rate the target requires.

`ctrl/commit-tx!` is a lower-level function accepting a pre-built transaction
map for multi-change atomic commits. `set!` and `send!` call it internally.

**Open**: `:after` is currently assumed to be a scalar. Curve values as
first-class ctrl tree citizens raise the question of what `ctrl/get` returns
during an in-flight trajectory — the curve description, or the instantaneous
sampled value? Both are legitimate for different callers. This is connected to
the foundational question of what the ctrl tree *is*.

---

## `ctrl/set!` and `ctrl/send!` — API changes

External API is **unchanged**. `set!` and `send!` keep the same arities and
return `nil`. Source context is supplied via `*current-tx-source*` /
`with-source`.

---

## Open questions

### O1. Foundational: what is the ctrl tree? [blocks implementation]

The review identified that the ctrl tree is a semantic model of the studio,
not a parameter store. Current values are our best estimate of studio state
given the transactions we have observed — not ground truth. All parameters are
shadows; confidence varies. This reframing has consequences for the transaction
design that are not yet fully worked out. **`design-ctrl-foundation.md` must be
written before implementation begins.**

### O2. Source taxonomy — does `:source/kind` do real work?

Does the system behave differently based on `:source/kind`, or is it purely
descriptive metadata? If it's the latter, the taxonomy may be flatter than
drawn here. The transport/semantic-identity question (`:input` replacing
`:midi-in`/`:osc-in`) is partially resolved; the deeper question is not.
**Marked for foundational design session.**

### O3. Causality model depth

Source attribution, watcher-as-reducer, `fire-watchers!` generalization, and
the `:input` transport abstraction are facets of the same deeper question about
how the system models causality and the identity of external actors. The current
taxonomy is provisional pending a more rigorous treatment.

### O4. Universal timeline before `start!`

Beat 0.0 before `start!` is a reasonable default but should be explicit in the
foundation doc. The concept of "no musical context" needs a principled answer.

### O5. Undo and write-only hardware

Compensating transactions for write-only hardware (NightSky, Volante) re-send
the last known shadow value. Two distinct problems:
1. **Shadow drift** — divergence between shadow and device state happens
   continuously, not just at undo time.
2. **Compensating transaction semantics** — should a compensating transaction
   re-send to hardware (asserting the prior value) or update only the ctrl tree
   (acknowledging uncertainty)?

The second is a property of the binding (`:compensate :resend | :tree-only`).
The first is upstream and separable. Both need deeper exploration.

### O6. Watcher read-set declaration

Watchers reading paths outside their triggering transaction may observe
inconsistent state during peer reconciliation. Not a problem for the
single-process case. Flagged as a known limitation for when peer reconciliation
arrives.

### O7. `ctrl/get` semantics during in-flight trajectory

If curve values are first-class (O above), what does `ctrl/get` return during
a sweep? The curve description (intent) or the current sampled value
(instantaneous state)? Blocked on foundational work.

---

## What does NOT change

- `ctrl/get` — single map lookup, unchanged.
- `ctrl/defnode!`, `ctrl/bind!`, `ctrl/unbind!` — structural ops, unchanged.
- `ctrl/checkpoint!`, `ctrl/panic!` — snapshot ops, unchanged.
- `ctrl/watch!`, `ctrl/unwatch!`, `ctrl/watch-global!` — registration API,
  unchanged. Callback signature evolves; no compat shim (full migration).
- `CtrlNode` record — unchanged.
- System atom gains `:tx-log` and `:tx-depth`; existing keys unchanged.

---

## Implementation order

> **Blocked on `design-ctrl-foundation.md`.**
> The order below is correct given current understanding but may change.

1. Add `:tx-log` (bounded vector) to system atom initial state.
2. Add `(timeline/current-beat)` as the universal beat time source.
3. Extract `build-tx` pure function — captures `:before` inside `swap!`.
4. Modify `set!`/`send!`/`send-at!` to call `build-tx` and include tx in swap.
5. Modify `fire-watchers!` → `dispatch-watchers!`: pass tx + state, collect
   effects, commit as child transactions with cascade depth guard.
6. Add `*current-tx-source*` dynamic var and `with-source` macro.
7. Add `ctrl/recent-txs` for REPL inspection.
8. Add error transaction paths; convert stderr prints to error commits.
9. Migrate all internal watchers to new signature in one sprint (no shim).
10. Compensating transaction undo — deferred to persistent log sprint.
