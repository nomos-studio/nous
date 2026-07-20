# Control-state authority rule

*Status: active. Companion to `design-ctrl-foundation.md`. Written 2026-07-13 to
close Gate 4 finding #2 (two control stores, no documented authority rule).
Migration-status section refreshed 2026-07-16 after Increment 10 (binding
plumbing fully migrated).*

## The rule

**`ctrl-tree` is the single source of truth for control-surface state.** New
control state lands on `ctrl-tree` only. There is no second place for a control
value to live.

`nous.ctrl` (the `system-state` atom, with typed nodes, MIDI dispatch, undo,
checkpoints, and watchers) is **legacy under migration**. It is not deleted —
~20 namespaces still read and write it — but it is retired path-by-path, and
**no new state is added to it**. Full retirement is the goal; it is gated on the
still-draft transaction model (`design-transactional-ctrl.md`) and proceeds as a
series of scoped increments. See the **Migration status** section below for the
increment history and the categorised inventory of what remains.

## What follows from it

1. **No dual-writes.** A given logical value has exactly one path in exactly one
   store. The M14–M18 code previously wrote the same value to both stores by
   hand (e.g. the tone row to `[:seq …]` in `nous.ctrl` *and* `[:keyboard …]` in
   `ctrl-tree`) to satisfy both a Clojure reader and the BEAM display. That is
   the anti-pattern this rule exists to prevent.

2. **BEAM echo is a mount side-effect of one write, not a second write.** Writing
   a path whose prefix is mounted (`nous.jinterface` registers `BeamMount` for
   `[:keyboard]`, `[:seq]`, `[:theory]`, `[:transport]`, …) echoes the value to
   the BEAM UI automatically, post-commit. To make a new control path visible on
   the UI you register a mount for its prefix — you do not write it twice.

3. **Reads and writes are symmetric.** Use `ctrl-tree.core/ctrl-read` and
   `ctrl-tree.core/ctrl-write!`. Application code does **not** dereference or
   `add-watch` `ctrl-tree.refs/tree-state` directly. To react to a change, use the
   path-watch primitive `ctrl-tree.core/ctrl-watch!` (or `ctrl-watch-global!`),
   added Inc 12. The former standing exception — raw `add-watch` on the ref — is
   retired: as of Inc 14 no nous code `add-watch`es `tree-state` (test code may
   still deref it for assertions).

4. **Serialisability rule.** Every `ctrl-write!` is persisted to the SQLite txlog
   (values serialised via `pr-str`; the log is the replayable session record —
   see `design-ctrl-foundation.md`). Therefore **only values that round-trip
   through `read-string` belong on the tree**: keywords, numbers, strings,
   booleans, and collections of those. Heavy or non-serialisable derived objects
   do not go on the tree.

5. **Object caches are not a second source of truth.** A value that is a *pure
   function of* authoritative ctrl-tree state may be memoised in a private cache
   without violating the single-store rule, because it can be reconstructed and
   holds no independent state. Example: `nous.tuning`'s scale registry caches
   `MicrotonalScale` objects keyed by the `:name` of the serialisable descriptor
   at `[:theory :tuning]`; each scale is a pure parse of its `.scl` `:file`. The
   descriptor is the truth; the object is a derivation.

6. **Runtime state is separate and exempt.** `nous.runtime` (process health,
   errors, subsystem status) is ephemeral — never persisted, never part of a
   session export or replay. It uses the same path/watch shape as control state
   but is not control state and does not belong on `ctrl-tree`. The same applies
   to high-frequency per-tick modulation state — e.g. `[:spatial <field> :state]`,
   rewritten ~20 Hz per field by the modulation loop: since every `ctrl-write!`
   persists to the SQLite replay txlog, such state would bloat the session record
   for no replay value. It stays off `ctrl-tree` (on `nous.ctrl` for now, pending
   a dedicated ephemeral store).

7. **Mount coverage is part of the surface.** Any control path that must reach
   BEAM needs a mount registered in `nous.jinterface` `start!`/`stop!`. Paths
   deliberately kept off the UI (e.g. an object cache) live under an *unmounted*
   prefix so their writes never attempt to echo.

## Migration posture

When you touch a `nous.ctrl` path, prefer moving it to `ctrl-tree` rather than
extending its `nous.ctrl` usage — provided its value is serialisable (rule 4)
and its behaviour does not depend on the `nous.ctrl`-only capabilities that are
**not yet ported** (undo, checkpoints, typed-node metadata, per-write tx
`:source/kind`, and beat-scheduled `send-at!`). Hardware *dispatch* is no longer
in that list — MIDI output and input binding plumbing were migrated in
Increments 5–10 (see below); dispatch now flows ctrl-tree → mount →
`nous.binding-registry`. The remaining capabilities are the hard part of full
retirement and are ported deliberately in later increments, not opportunistically.

---

## Migration status (as of Increment 10, 2026-07-16)

### Increment history

- **Inc 1–4 — surface + read/write plumbing.** M14–M18 control surface (keyboard,
  tuning, seq, notation, theory) moved fully onto `ctrl-tree`; `nous.server` and
  `nous.mcp` made store-agnostic via the transitional **`nous.ctrl-bridge`**
  (union read, ownership-routed write, dual-watch broadcast over both stores);
  the ensemble/peer/session/spectral cluster migrated.
- **Inc 5–10 — binding plumbing, now complete.** Output *and* input hardware
  binding moved off the `nous.ctrl` node model:
  - `nous.dispatch/dispatch-binding!` — shared scale-and-emit for `:midi-cc` /
    `:midi-nrpn`, driving both the legacy `ctrl/send-at!` path and the mount.
  - `nous.ipc-mount/IpcMount` — root `[]` mount; a ctrl-tree write resolves the
    path's bindings and emits nomos-rt frames post-commit.
  - `nous.binding-registry` — the single store for hardware bindings
    (`{path → {:type :node-meta :bindings}}`); every output declarer (device,
    schema, session, berlin) and the input declarer (device-bind!) register here.
  - `nous.midi-in` reads `breg/bindings-by-type` and writes routed inbound values
    via `ct/ctrl-write!` — inbound MIDI lands in `ctrl-tree`.
  - **The IpcMount's transitional `nous.ctrl` union read is gone (Inc 9).**
  **No production code reads or writes hardware bindings via `nous.ctrl`.**
  `ctrl/send!` / `ctrl/send-at!` / `ctrl/bind!` / `ctrl/bindings-by-type` remain
  defined but have essentially no production callers (the lone exception is
  `core/play!`'s per-step mod dispatch — see below).

### What still depends on `nous.ctrl` (~20 namespaces, by concern)

These are the targets for the *node/value-model* retirement, which is harder than
the binding work and involves open design decisions.

- **Transitional infra (keep until the model retires):** `ctrl-bridge` (union
  read/write over both stores; used by `server` + `mcp`), `server` (union reads +
  dual-watch broadcast), `user` / `core` (system lifecycle: `start!`/`stop!`/
  `started?`, plus `all-nodes`).
- **Value read/write (`get`/`set!`/`node-info`/`child-keys`) — migrate when
  touched, if serialisable:** `bitwig`, `excursion`, `lattice`, `target`,
  `morph`, `live`.
- **Typed-node declarers (`defnode!`):** `book`, `flux`, `fractal`, `stochastic`,
  `excursion`, `lattice`, `live`, `morph`, `config` — blocked on porting
  typed-node metadata to `ctrl-tree`.
- **Watch-driven reactions — fully migrated (Inc 12–18).** The ctrl-tree watch
  primitive `ctrl-watch!` landed Inc 12; all five raw `add-watch`-on-`tree-state`
  sites (`arc`/`tuning`/`sc` Inc 13, `server`/`theory` Inc 14) *and* all four legacy
  `nous.ctrl/watch!` consumers (`terrain` Inc 15, `osc` Inc 16, `bitwig` Inc 17,
  `defensemble` + its coupled `lattice`/`excursion` `[:harmony :voice-*]` writers
  Inc 18) now use it. **No nous code `add-watch`es `tree-state` or calls
  `nous.ctrl/watch!`.** (Migrating `terrain` and `defensemble` also fixed latent
  `ArityException`s — both had callbacks written against the primitive before it
  existed.)
- **`nous.schema` persistent state:** models/realizations/active-realization at
  `[:txlog/schema …]` via `with-source`/`set!`/`get`/`child-keys`. Blocked on the
  **`:source/kind :schema` decision** — `ct/ctrl-write!` cannot reproduce
  per-write source kinds that `schema_test` asserts on.
- **Undo:** `config` reads `undo-stack-depth` — blocked on porting the undo/
  checkpoint model.
- **Ephemeral, deliberately exempt (rule 6):** `spatial_field` `[:spatial … :state]`
  (~20 Hz) stays on `nous.ctrl` pending a dedicated ephemeral store.
- **The last dispatch caller:** `core/play!` still routes per-step modulation via
  `ctrl/send-at!` (beat-anchored). Migrating it to `ct/ctrl-write!` → mount needs
  the beat-scheduling semantics resolved (the mount dispatches immediately
  post-commit; `send-at!` records a beat-stamped tx).

### Open decisions gating full retirement

1. **`:source/kind` per-write provenance** — keep (needs a `ctrl-tree` mechanism)
   or drop (change `schema_test` + accept coarser txlog).
2. **Undo / checkpoints** — port to `ctrl-tree` or redefine against the SQLite
   txlog replay model.
3. **Typed-node metadata** — where `:type`/`:node-meta` live once nodes are
   `ctrl-tree` paths (candidate: fold into `nous.binding-registry`, already a
   `{:type :node-meta}` store).
4. **A `ctrl-tree` watch primitive** — to retire the `add-watch`-on-`tree-state`
   exception (rule 3).
5. **Beat-scheduled `send-at!`** — how modulation dispatch stays beat-accurate
   through the immediate-dispatch mount.
