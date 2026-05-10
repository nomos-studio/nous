# Device model — instrument identity, capability profiles, and transport bindings

## The tension

`cljseq.device` currently conflates three distinct concerns in one EDN structure:

1. **The semantic parameter space** — what an instrument *is*: its oscillators,
   filters, envelopes, routing, the vocabulary a composer thinks in when working
   with it.
2. **The transport binding** — how a particular realization of that instrument
   is addressed: MIDI CC on a channel, NRPN, CLAP automation IDs, OSC addresses.
3. **The wire encoding** — the protocol-level mechanics: 7-bit CC, 14-bit NRPN,
   OSC float bundle, sysex frame.

An EDN entry like `{:cc 74 :path [:filter :cutoff]}` names a semantic parameter
and a MIDI wire address in the same breath. This is expedient but forecloses the
separation that compositional portability requires.

---

## The concrete case that makes it non-academic

The studio has three realizations of the same instrument — the ARP 2600:

- **Behringer Grey Meanie** — hardware analog clone; addressable via MIDI CC and
  NRPN on a fixed channel; no readback; physical panel normalled connections that
  have no MIDI equivalent
- **VST clone A** — a plugin implementation; addressable via CLAP automation
  parameter IDs; likely a different numbering scheme; may expose parameters the
  hardware never surfaced (drift, component modeling, spring reverb decay as a
  controllable value)
- **VST clone B** — a different plugin implementation of the same instrument;
  its own CLAP parameter IDs; no guaranteed correspondence with clone A

All three share a sonic identity and a compositional vocabulary. None of them
are controllable in the same way. There is no transport-level commonality to
exploit.

The same instrument also spans contexts. A composition developed in the studio
against the Grey Meanie may want to travel to a live laptop rig — where the
hardware stays home but a VST clone comes along. The musical ideas are invariant;
the substrate is not.

---

## The asymmetry: extended capability

The VST is not simply the Grey Meanie made digital. It is an *extension* of the
ARP 2600 concept: all the parameters the hardware exposes (in some form), plus
parameters the hardware never had as controllable signals — oscillator drift
amount, component aging models, possibly polyphony beyond the original's
monophonic architecture.

This means the device model cannot be:

- **The intersection** — discards the VST's expressive range; reduces the model
  to the lowest common denominator
- **The union** — declares parameters that hardware realizations silently cannot
  honor; creates false expectations

The correct structure is layered **capability profiles**.

---

## Capability profiles

A device model declares a **base profile** — the parameter paths any compliant
realization of this instrument must support in some form. Realizations then
declare which **extended profiles** they satisfy beyond the base.

```clojure
;; Model declaration — transport-agnostic
(defdevice-model :arp2600
  {:profile/base
   {:osc1  {:freq    {:type :float :range [0.0 1.0]}
            :wave    {:type :enum  :values [:sin :saw :sqr :tri]}
            :level   {:type :float :range [0.0 1.0]}}
    :osc2  {:freq    {:type :float :range [0.0 1.0]}
            :fine    {:type :float :range [-1.0 1.0]}
            :wave    {:type :enum  :values [:sin :saw :sqr :tri]}
            :level   {:type :float :range [0.0 1.0]}}
    :filter {:cutoff {:type :float :range [0.0 1.0]}
             :res    {:type :float :range [0.0 1.0]}
             :env    {:type :float :range [-1.0 1.0]}}
    :env    {:attack  {:type :float :range [0.0 1.0]}
             :decay   {:type :float :range [0.0 1.0]}
             :sustain {:type :float :range [0.0 1.0]}
             :release {:type :float :range [0.0 1.0]}}}

   :profile/extended
   {:analog {:drift   {:type :float :range [0.0 1.0]}
             :warmth  {:type :float :range [0.0 1.0]}}
    :reverb {:time    {:type :float :range [0.0 1.0]}
             :mix     {:type :float :range [0.0 1.0]}}}})
```

Realizations declare what they satisfy:

```clojure
;; Hardware realization
(defrealization :grey-meanie
  {:model   :arp2600
   :satisfies #{:arp2600/base}
   :binding :midi
   :channel 1
   :cc-map  {:osc1/freq    {:cc 16 :range [0 127]}
             :filter/cutoff {:cc 74 :range [0 127]}
             ;; ...
             }})

;; VST realization
(defrealization :timewarp-vst
  {:model     :arp2600
   :satisfies #{:arp2600/base :arp2600/extended}
   :binding   :clap
   :param-map {:osc1/freq       0x0010
               :filter/cutoff   0x0040
               :analog/drift    0x0200
               ;; ...
               }})
```

---

## Session-level realization selection

At session setup, a single declaration resolves which realization is active for
a model in the current environment:

```clojure
;; Studio session
(realize! :arp2600 :grey-meanie)

;; Live laptop session
(realize! :arp2600 :timewarp-vst)
```

Compositional code addresses the model. The binding layer is swapped at session
boundary without touching the composition:

```clojure
;; Portable — works on any realization satisfying :arp2600/base
(ctrl/set! [:arp2600 :filter :cutoff] 0.6)

;; Extended — only meaningful where :arp2600/extended is satisfied
(ctrl/set! [:arp2600 :analog :drift] 0.3)
```

---

## Graceful degradation

When compositional code reaches an extended capability on a realization that does
not satisfy it, the system has three options, declared per-site or per-path:

- **Silence** (default) — the `ctrl/set!` call is a no-op; nothing is sent; no
  error. Appropriate when the parameter is an enhancement, not a requirement.
- **Substitution** — a fallback path is declared in the realization binding;
  the extended parameter maps to a base-profile approximation on hardware.
  (E.g. `:analog/drift` → pitch-bend modulation on a hardware realization.)
- **Capability assertion** — the motif or live-loop declares a required profile;
  the session loader fails early rather than silently doing nothing at
  performance time.

```clojure
;; Capability assertion — fail at session load, not at 2am on stage
(deflive-loop drift-texture
  {:requires #{:arp2600/extended}}
  ...)
```

---

## Relationship to the ctrl tree

The ctrl tree path `[:arp2600 :filter :cutoff]` is already model-level thinking.
`ctrl/bind!` is already the transport declaration. What the device model adds:

1. **The parameter schema exists independently** of any realization — it can be
   introspected, validated, and reasoned about without reference to MIDI CC
   numbers or CLAP parameter IDs.
2. **Realizations are named and versioned** — swapping realization is an explicit
   act at session scope, not a side effect of loading a different EDN file.
3. **Capability profiles are first-class** — the system knows which paths are
   meaningful for the current realization; extended paths are not silent accidents.

---

## What this means for `cljseq.device`

`defdevice` as it stands is declaring a realization, not a model — and conflating
the two. The migration path is additive:

1. Introduce `defdevice-model` — transport-agnostic parameter schema, capability
   profiles, shared vocabulary.
2. Introduce `defrealization` — binds a model to a transport (MIDI, CLAP, OSC)
   with a concrete address mapping.
3. `realize!` — session-scope declaration; sets up the ctrl tree nodes and
   bindings for the active realization.
4. Existing `defdevice` EDN maps become realization bindings; the model layer
   is added alongside them, not replacing them immediately.

The device model is not a replacement for the ctrl tree. It is the layer that
explains *why* the ctrl tree has the shape it does for a given device, and that
makes the shape portable across contexts.

---

## Open questions

**Q74 — Model identity and versioning**

The Grey Meanie, TimewARP, and a future Behringer firmware revision are all
"ARP 2600 realizations," but their parameter spaces diverge. Should `:arp2600`
be a versioned spec? Who owns it — cljseq core, a shared EDN file, or the user?

**Q75 — Substitution binding expressiveness**

How much substitution logic belongs in the realization binding vs. in the motif?
A simple scalar fallback (`:analog/drift` → pitch-bend) is tractable in EDN.
A context-sensitive substitution (drift → LFO rate if LFO is free, else ignore)
may require a Clojure function rather than a data declaration.

**Q76 — Realization switching at runtime**

`realize!` is a session-scope declaration. Can realizations be swapped mid-session
— e.g. switching from hardware to VST while a loop is running? What happens to
in-flight ctrl tree values and bindings? This is probably out of scope for the
initial implementation but should not be foreclosed by the data model.
