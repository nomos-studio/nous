# What falls out of the log — transformations over transactional state

*This is a speculative document. It describes what the transactional model
makes possible, not what is currently implemented. Some of these capabilities
are near-term consequences of decisions already made; others are further out.
All of them fall out of the model rather than requiring the model to be
extended.*

---

## The reframing

The ctrl tree foundation document establishes that current state is the log
replayed to now. Schema, in the Datomic sense, is values in the same log.
Device models and realization bindings are schema-level transactions. Every
musical event, parameter change, loop transition, and error is an observation
recorded at a beat position.

If that is the model, then a session is not a running program. It is an
accumulation of facts. And facts can be projected, transformed, composed, and
replayed.

The question this document asks is: *what operations over log state become
natural given this foundation?*

---

## Session templates are log projections

A session template is not a configuration file. Configuration files have their
own format, their own lifecycle, their own drift from reality. They are
maintained separately from the thing they configure and tend to rot.

A session template is a **curated projection of log state** materialized into
a new execution context. It is a log segment — a contiguous or filtered subset
of transactions from one or more prior sessions — together with enough schema
context to make those transactions meaningful when replayed.

This distinction matters because everything the log model gives you comes along
with the template:

- It carries the device model schema that was active when it was recorded
- It carries the capability profile declarations that governed what was portable
- It carries its position on the universal timeline, in beats
- It carries errors and compensating transactions — the full history, not just
  the happy path

Materializing a template into a new execution context is replay with
realization remapping. The schema transactions that bound `:moog-voice` to
`:subsequent-37` are intercepted and rebound to `:minimoog-vst`. The value
transactions — canonical patch states, filter arcs, harmonic contexts — replay
unchanged, because they were written against model paths, not transport
addresses. The capability system governs what is portable: extended paths either
degrade gracefully, substitute, or surface early as capability assertions.

A template is not a snapshot. A snapshot is a point-in-time value map — a
photograph of the studio with no history and no adaptability. A template is a
score that can be performed in any room with the right instruments.

---

## BPM agnosticism is free

The log records beat positions, not wall-clock timestamps.

A filter arc that unfolds over 64 beats unfolds over 64 beats at any tempo. A
template developed at a contemplative 75 BPM materializes at 120 BPM in a live
context by arithmetic on the universal timeline. There is nothing to convert.
The beat positions are the same; the wall-clock durations change; the Link
clock at the materialization site owns that relationship entirely.

This has a practical consequence: a template is BPM-agnostic by construction,
not by design effort. It costs nothing. The model cannot record wall-clock
timestamps without losing this property, so it does not.

It also has a musical consequence. A Berlin school arc developed at 75 BPM can
be stress-tested at 140 without touching the template. If the filter arc still
works musically — if the envelope contours still make sense, if the loop phase
relationships still land — the arc is genuinely tempo-invariant. If it falls
apart, that is musical information: some of what felt like the arc was an
artifact of the specific BPM regime it was developed in.

---

## Log composition

If a session is a log, and a template is a log segment, then composing sessions
is composing log segments.

**Splicing** is the simplest case: append one log segment to another at a beat
boundary. The Berlin school template at beat 0 runs to beat 128; the psytrance
arc begins at beat 128. Each brings its own realization context; the transition
is a schema transaction that rebinds the active realizations at the splice
point.

**Concurrent segments** are two log streams running simultaneously with shared
beat coordinates. A crossfade is two concurrent segments with opposing gain
arcs — which are themselves value transactions on mix parameters. The system
does not know this is a crossfade; it knows two log segments are active and
that gain parameters are changing over time.

**Beat ratio composition** is where the arithmetic becomes interesting. A
half-tempo breakdown inserts a log segment whose beat clock advances at half
the rate of the surrounding context. If the Berlin material runs at 75 BPM
inside a 150 BPM psytrance session, the ratio is 1:2 — every beat of the
breakdown template corresponds to two beats of the outer timeline. Everything
stays on-grid. The phase relationships across the splice points are
deterministic. This is not a special mode; it is the universal timeline doing
its job.

**Forking** is materializing the same log segment into two concurrent execution
contexts with different realization bindings — the same harmonic arc playing on
the Subsequent 37 in the studio and on the TimewARP VST on a laptop
simultaneously. Whether that is useful for performance or for A/B comparison of
realizations, the model supports it for free.

---

## Schema evolution in the log

Device models evolve. A new firmware on the Grey Meanie exposes a previously
inaccessible parameter. A VST update adds an extended capability. A new
realization is defined for an instrument that previously had only one.

In a world where schema is a separate artifact, these are migrations — steps
outside the transactional model that have to be coordinated with session
history. In a world where schema is values in the log, they are transactions.
The log entry that adds a new parameter path to the `:grey-meanie` realization
is first-class. Temporal introspection extends to schema: "what did the ARP
2600 model look like in session 5, before the firmware update?" is answerable
by replaying the log to that point.

Schema evolution is additive for the same reason it is in Datomic. Adding
parameters, adding realizations, extending profiles — all tractable. Removing
a parameter from a model that prior sessions have written values against cannot
make those prior transactions not have happened. Deprecation is a forward
transaction; retraction is not available.

---

## The constraints become musical

The meta-observation that ties this together: when the model is right, the
binding constraints are musical rather than technical.

The question "can I splice a Berlin school arc into a psytrance set?" is not a
systems question. The system's answer is "yes, and what beat?" The question
that remains — whether the splice is musically coherent, whether the
contemplative filter arc survives at the faster tempo, whether the harmonic
context of one arc is intelligible in the other — is irreducibly a musical
question. No amount of architecture answers it.

That shift is not a small thing. A significant fraction of the effort in live
electronic music goes into fighting tools that were not designed for
composability. Bespoke solutions to problems the data model would have solved
automatically. The transactional log model does not make music. It removes the
accidental complexity that prevents the musician from asking interesting
questions.

Data as values over time. The log as source of truth. Beat position as the
universal coordinate. These are not philosophical choices. They are the load-
bearing premises that make the rest of this document not speculative.

---

## What remains speculative

The operations described above follow from the model. What is genuinely
uncertain is the **surface** — the API and tooling through which a musician
actually expresses log composition.

Splicing two log segments in a live coding session is not obviously a
comfortable operation at the REPL. A template library that makes named
segments first-class, a query language for filtering log state by device or
path or beat range, a UI for visualizing concurrent segments — these are
problems of interface, not of model. The model does not foreclose any of them.

The other open question is **persistence**. The log as described is an
in-memory structure. A session template that survives across process restarts,
that can be shared with a collaborator, that can be archived and replayed
years later, requires a durable log format. That format should be the same log,
serialized — not a derived export format that loses the transactional structure.
EDN is an obvious candidate; the question of what makes a log segment a
self-contained portable artifact is the thing to design.
