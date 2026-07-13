# Control-state authority rule

*Status: active. Companion to `design-ctrl-foundation.md`. Written 2026-07-13 to
close Gate 4 finding #2 (two control stores, no documented authority rule).*

## The rule

**`ctrl-tree` is the single source of truth for control-surface state.** New
control state lands on `ctrl-tree` only. There is no second place for a control
value to live.

`nous.ctrl` (the `system-state` atom, with typed nodes, MIDI dispatch, undo,
checkpoints, and watchers) is **legacy under migration**. It is not deleted —
~36 namespaces still read and write it — but it is retired path-by-path, and
**no new state is added to it**. Full retirement is the goal; it is gated on the
still-draft transaction model (`design-transactional-ctrl.md`) and proceeds as a
series of scoped increments. Increment 1 (2026-07-13) moved the M14–M18 surface
— keyboard, tuning, seq, notation — fully onto `ctrl-tree`.

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
   `ctrl-tree.core/ctrl-write!`. Application code does **not** dereference
   `ctrl-tree.refs/tree-state` directly. The sole current exception is
   `add-watch`/`remove-watch` on the ref (there is no ctrl-tree watch primitive
   yet); when one exists, that exception goes away too.

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
   but is not control state and does not belong on `ctrl-tree`.

7. **Mount coverage is part of the surface.** Any control path that must reach
   BEAM needs a mount registered in `nous.jinterface` `start!`/`stop!`. Paths
   deliberately kept off the UI (e.g. an object cache) live under an *unmounted*
   prefix so their writes never attempt to echo.

## Migration posture

When you touch a `nous.ctrl` path, prefer moving it to `ctrl-tree` rather than
extending its `nous.ctrl` usage — provided its value is serialisable (rule 4)
and its behaviour does not depend on `nous.ctrl`-only capabilities (MIDI
dispatch, binding priority, undo, checkpoints, typed-node metadata). Those
capabilities are the hard part of full retirement and are ported deliberately in
later increments, not opportunistically.
