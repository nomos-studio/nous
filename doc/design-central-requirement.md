# The central requirement

## The loss

Three hours of F# minor at 70 BPM. An accidental interaction between a
composer and an NDLR that produced something genuinely unrepeatable — dread
that built without resolving, deepening rather than cycling, grinding ever more
menacingly toward you for the duration.

It exists as audio. The generative knowledge is gone.

The parameter state that produced it. The harmonic choices, deliberate and
accidental, that accumulated over three hours. The moment where the tension
deepened rather than repeated and what caused it. None of that is recoverable
from the audio. You can listen back but you cannot inhabit it again. You cannot
find the inflection point and ask what was different there. You cannot nudge it
in a different direction from a known position. You cannot understand the
accident well enough to have it deliberately.

The audio is the shadow on the wall. The log is the thing casting it.

---

## What the NDLR cannot do

The NDLR cycles. It does not arc.

It has no memory of where it has been. No model of where it is going. No
accumulation of harmonic state over time. It generates texture without
accumulating meaning. The three hour session happened *despite* the instrument,
not because of it — an accidental interaction between a composer's choices and
a tool that was indifferent to those choices as a sequence.

The dread was real. The instrument could not hold it intentionally. It could
not deepen it by design. It could not know it was in F# minor and that F# minor
at 70 BPM, held without resolution for three hours, is a specific kind of
existential terror. It just cycled.

---

## The central requirement

**Do not lose the generative knowledge.**

The audio captures what happened. The log captures *why* it happened — the
full transactional history of every parameter state, every harmonic context,
every accidental interaction, at every beat position across the entire session.

Given the log, beat 7240 is recoverable. The harmonic context at that moment
is readable. The parameter state that produced the deepening rather than the
cycling is inspectable. The accident is understandable. The understanding makes
the accident available as intention.

This is not about making sessions repeatable. Some of the best things should
not be exactly repeatable — the accidental quality is part of what they are.
It is about making sessions *recoverable*. About preserving the generative
knowledge that the audio cannot carry. About being able to return to a moment
and understand it rather than only remember it.

The transaction log is not a feature. It is the answer to a specific, concrete,
already-experienced loss.

---

## What this demands of the model

The log must be **complete**. A log that captures only explicit parameter
changes and loses the harmonic context, the loop state, the accidental
interactions between devices — is not the log. Partial recovery is better than
none but the requirement is the full picture.

The log must be **legible**. Raw transaction records that cannot be queried,
visualised, or reasoned about are not recoverable knowledge. The log exists to
be read as well as written.

The log must be **durable**. A session that exists only in memory is one
process crash away from the same loss. The log survives the session. It is an
artifact with a longer life than the execution context that produced it.

The log must speak **beat positions, not wall-clock time**. A session log in
wall-clock timestamps is tied to the tempo regime it was recorded in. A log in
beats is tempo-agnostic — the three hour F# minor session can be re-examined
at a different BPM without the timestamps becoming meaningless.

---

## The instrument being built

A composer working in F# minor at 70 BPM, building dread over three hours,
should be able to:

- Return to any moment in the session and know what the full parameter state was
- Identify the inflection points where the tension deepened
- Recover those moments as session templates — portable, replayable, available
  as starting points for new sessions
- Understand accidents well enough to have them deliberately

The NDLR cannot do this. No current instrument can do this.

That is what cljseq is for.
