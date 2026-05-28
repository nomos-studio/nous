# The ctrl tree — foundational framing

## What it is

The ctrl tree is a **semantic model of the studio**.

A studio, for any given session, is the set of devices, processors, and
compositional state that participate in that session — synthesizers, effects
processors, controllers, the running loops, the harmony context, session
configuration. The ctrl tree is nous's model of that studio: what devices
exist, what their parameter spaces look like, and what we currently believe
their state to be.

"What we currently believe" is the operative phrase. The ctrl tree does not
hold ground truth about the physical world. It holds our **best current
estimate** of studio state, derived from the transactions we have observed or
caused since the session began.

This is not a limitation or a bug. It is an honest statement of what any
software system can know about physical hardware it does not fully control.

---

## What it is not

The ctrl tree is **not a parameter store**.

A parameter store is a mutable container of current values — a map you write
to and read from, where the map *is* the state. The ctrl tree looks like this
from the outside but is not this conceptually. The distinction matters because
a parameter store makes an implicit claim that its values are authoritative.
The ctrl tree makes no such claim.

---

## The epistemological position

Every value in the ctrl tree is a **shadow** of some aspect of the physical
studio. Shadows differ in confidence but not in kind:

- An SC synth parameter: we sent a value; SC confirmed receipt; high
  confidence the synth reflects it — but we are still reasoning from our
  last write, not from direct observation.
- A MIDI CC value on a hardware synth: we sent it; no confirmation; moderate
  confidence.
- A knob on a write-only effects processor (NightSky, Volante): we sent it;
  the device has no readback; someone may have turned the knob since; low
  confidence.
- A harmony context value: entirely internal; we computed and wrote it; high
  confidence — but it is still our model of harmonic state, not harmonic state
  itself.

The NightSky case is not a special problem requiring special handling. It is
the general case made visible. Every parameter value in the ctrl tree is our
best estimate; we just happen to have better evidence for some than others.

This means `ctrl/get` returns "the value we last recorded at this path" — not
"the current state of the hardware." Callers that need to reason carefully
about confidence should be able to inspect the transaction that last wrote a
value, not just the value itself.

---

## The transaction log as source of truth

If the ctrl tree's materialized view is an estimate, what is the source of
truth? The **transaction log** — the ordered sequence of changes that produced
the current estimate.

The materialized view is a projection of the log to the present moment. Given
the log from session start, you can reconstruct the materialized view at any
point in time. Given only the materialized view, you cannot reconstruct the
log. The log is strictly more informative.

This has a precise consequence: **current state is definitionally the log
replayed to now**. Replay is not a feature added later — it is what "current
state" means. The in-memory materialized view is a cache of that replay,
maintained incrementally for read performance.

---

## The universal timeline

Every transaction has a position in the **universal timeline** — a monotonically
advancing beat count that is the same coordinate system for all participants in
a session.

When Ableton Link is present, the universal timeline is the Link beat clock:
the authoritative shared drummer to which all loops march. When Link is absent,
it is the local session beat clock derived from `start!`. In both cases, the
concept is identical — a single origin, a single rate, a single coordinate
for every musical event.

Individual loops have a *perception* of this timeline (their current beat
position, their phase within a bar), but that perception is a view over the
universal timeline, not a separate clock. A loop running at half-speed is still
advancing through the same universe; it just processes one beat of material for
every two beats of universal time.

Every transaction records its universal beat position. This is what makes
replay coherent: replaying a session at a different tempo is arithmetic on beat
positions, not on wall-clock timestamps.

---

## What follows

These premises are not philosophical decoration. They have direct design
consequences.

**`ctrl/get` is a best-estimate read, not a ground-truth lookup.**
The API does not change, but callers should understand this. A future
`ctrl/get-with-provenance` that returns value + the transaction that produced
it is a natural extension.

**All write operations produce transactions.**
A write is an observation: "at beat T, we caused or observed this change." The
transaction records that observation. The materialized view is updated as a
consequence. The log is the primary artifact; the view is derived.

**Errors are observations too.**
A loop body exception, a cascade abort, a failed hardware dispatch — these are
things that happened at a beat position with a cause. They belong in the log as
transactions, not on stderr as lost side effects.

**Undo is forward-only.**
You cannot un-observe something. Undo produces a compensating transaction —
a new observation that says "we are now returning to a prior estimate." The log
records both the original change and the decision to compensate. History is
preserved; the world moves forward.

**Write-only hardware does not require special-casing.**
The shadow state problem is the general case. The response to "we don't know
if the hardware agrees with our shadow" is the same everywhere: record what we
sent, note our confidence, make compensating transactions available. Whether to
re-send on undo is a property of the binding, not a special code path.

**The ctrl tree models the studio, not the wire.**
Watchers, transactions, and the semantic layer operate on studio state. MIDI,
OSC, and whatever comes after them are delivery mechanisms below that layer.
A watcher on `[:filter/cutoff]` should not need to know whether the value
arrived via CC 74 on channel 1 or via a REST call from a web UI. Transport is
metadata on the transaction source; it is not the trigger.

**Replay is a first-class operation.**
Because current state is the log replayed to now, and because beat time is
universal, temporal transformations are natural: replay at a different tempo,
isolate one device's transactions, fork from a prior moment, compose two
sessions. These capabilities are downstream consequences of the foundation,
not features to be designed separately.

---

## Open questions this framing does not yet answer

**What is the boundary of the studio model?**
The ctrl tree currently models parameter values and their bindings. Does it
also model device identity (what synths exist), signal routing (what feeds
what), session structure (what sections exist)? If the ctrl tree is a studio
model, it may need to model more than it currently does — or some of those
concerns belong in adjacent structures.

The existence question is sharp: when did a SC synthdef come into existence on
the timeline? Was it there at a "big bang" transaction at session start? Devices
that come and go mid-session — and the question of replaying a log against a
different topology — all depend on having an answer to what existence means in
this model.

**Confidence as a property of the binding, not the value.**
Confidence is not per-value metadata — it is a categorical property of a
device's readback capability, which applies uniformly to all parameters under
that device. Write-only hardware (NightSky, Volante) has low confidence on all
writes by definition. SC synths are readable and have high confidence.
Partially-readable devices (some parameters readable, some not) push confidence
to the per-binding level.

For readable devices, `ctrl/get` could in principle go ask the device. For
write-only devices it returns the shadow with an implicit caveat. The confidence
is in the binding/device type, not in the value stored in the tree.

**`:source/kind` is observational metadata, not a dispatch key.**
The system does not behave differently based on `:source/kind`. It is an open
value — not an enum — recorded for introspection and replay. Users may supply
any value meaningful to them: a built-in keyword, a custom sequencer type name,
a session-specific tag. The only constraint is that it is a value.

**Device identity: registry and session topology.**
The studio model has two distinct layers that are currently conflated:

- **Device registry** — what devices exist in the musician's world, their
  capabilities, parameter spaces, readback class, and preferred ctrl tree
  mappings. Stable across sessions; exists at a longer timescale than any
  individual session. A "Hydrasynth Deluxe" or a "Summit" tends to be present
  from one session to the next.

- **Session topology** — how registry devices are configured and routed for
  this specific session. MIDI channel, transport path (USB vs RTP-MIDI via
  MioXM), which parameters are exposed. Volatile; part of the session log.
  The same device can have named topology contexts: "studio" (MioXM + RTP)
  and "gig" (direct USB), selecting different routing while keeping device
  identity and default mappings stable.

Registry entries carry preferential mappings from device to ctrl tree path.
These are the sensible starting point for any session using that device, with
session topology providing overrides. Replay against a different topology means:
same device identities, different routing contexts — transactions remain
semantically meaningful even when the wire path changes.

This is the load-bearing open question. Device identity in the registry unlocks
the topology, confidence, existence, and replay-portability questions. It needs
explicit design before the transaction model can be considered complete.
