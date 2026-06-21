# nous

A music-theory-aware Clojure sequencer for live coding. Runs at a Clojure
nREPL; sends MIDI and audio to hardware synthesisers, controllers, and FX
units via kairos (CLAP host) or aion (lightweight MIDI peer).

```clojure
(require '[nous.user :refer :all])

(start! :bpm 120)

(deflive-loop :kick {}
  (play! :C2)
  (sleep! 1))

(deflive-loop :melody {}
  (use-harmony! (make-scale :C 4 :dorian))
  (play! (rand-nth [:C4 :Eb4 :G4 :Bb4]))
  (sleep! 1/2))

;; Edit and re-evaluate any loop live — takes effect on the next beat.

(set-bpm! 140)
(stop!)
```

---

## Features

### Live coding engine
- **Live loops** — named, continuously repeating loops; re-evaluate a
  `deflive-loop` form to swap the body mid-performance without stopping
- **Virtual time** — drift-free beat scheduling with `LockSupport/parkUntil`;
  BPM changes take effect on the next `sleep!` with zero drift
- **Hot-swap** — long `sleep!` calls are interrupted immediately when a loop
  body is reloaded; no waiting for the current bar to finish

### Runtime peers
- **kairos** — C++ CLAP host; manages a plugin graph, MIDI routing, audio I/O,
  and Ableton Link transport; sub-millisecond note scheduling independent of
  JVM GC; communicates via Unix domain socket
- **aion** — lightweight variant of kairos for MIDI + Link only; no CLAP, no
  audio engine; suitable for Raspberry Pi Zero 2W and remote sessions
- **`start-sidecar!`** — auto-discovers kairos (preferred) or aion in standard
  install locations; `start-kairos!` and `start-aion!` target a specific peer
- **Full MIDI coverage** — NoteOn/Off, CC, pitch bend, channel pressure,
  SysEx, MIDI clock; per-note MPE expression on all active channels
- **MIDI input** — incoming messages pushed to the JVM as events;
  `await-midi-message` for test and interactive workflows

### Music theory
- **Scale / chord / interval** — first-class records for all common modes,
  chord qualities, and intervals; pitch-class arithmetic
- **Key detection** — Krumhansl-Schmuckler probe-tone key detection from any
  pitch-class histogram
- **Chord identification** — identify chords by pitch set; Roman numeral analysis
- **Tension scoring** — tension model per chord quality and scale degree;
  `suggest-progression` generates progressions from a tension arc
- **Borrowed chords** — mode-mixture detection; suggest borrowed chords from
  parallel major/minor
- **Counterpoint analysis** — voice-leading checks (parallel motion, crossing,
  range); `analyze-counterpoint` aggregate report; Josquin-style rule set

### Microtonal
- **Scala files** — parse `.scl` and `.kbm` files; full Scala library compatible
- **`*tuning-ctx*`** — per-loop microtonal tuning context; `play!` auto-translates
  MIDI note numbers to the nearest scale degree + pitch bend
- **MTS Bulk Dump** — `scale->mts-bytes` generates a standards-compliant
  408-byte SysEx; `send-mts!` retunesHydrasynth / MicroFreak to any Scala scale
- **MTS retune arc** — `retune-arc!` interpolates beat-accurately between two
  frequency maps over a user-defined number of bars; drive gradual retuning
  across a performance (e.g. Partch 43-tone JI → 12-TET)

### Just intonation and harmonic lattice
- **`nous.lattice`** — `deflattice` navigates a 2D JI pitch space; lattice
  points encode ratio intervals; harmonic gravity pulls toward a movable
  tonal attractor; `lattice-step!` advances under voice-leading constraints
- **`nous.excursion`** — `defexcursion` sequences lattice movements into
  coherent harmonic arcs with configurable tension trajectory
- **`nous.defensemble`** — multi-voice JI ensemble coordinator; voices assigned
  lattice positions; counterpoint constraints enforced at note-generation time

### Berlin School and journey composition
- **`nous.berlin`** — sequencing vocabulary for the Tangerine Dream / Klaus
  Schulze idiom: ostinato weaving, phasing, filter-sweep arcs, and
  analogue-style sequencer step models
- **`nous.journey`** — trajectory-field composition conductor; independent
  parameters moving on different arcs at different rates; structural arc
  emerges from parameter interaction rather than explicit section markers

### Generative techniques
- **`nous.stochastic`** — weighted random, Markov chains, Euclidean rhythms
- **`nous.fractal`** — L-system melody expansion, recursive phrase generation
- **`nous.terrain`** — 3D fractal sequence space; three continuous phasors
  (position, altitude, warp) address a tree of transforms; use as a
  higher-dimensional arpeggiator or texture source
- **`nous.extractor`** — GTE-inspired threshold extractor; watches a continuous
  source `f(beat) → [0,1]` and fires note/CC events on boundary crossings;
  rhythm emerges from the curve's motion rather than a pattern
- **`nous.temporal-buffer`** — beat-timestamped event store with eight depth
  zones (0.5–128 beats); replay, morph, and Doppler-shift recent history
- **`nous.flux`** — evolving parameter sequences; freeze/unfreeze read head
- **`nous.conductor`** — section-based compositional arc with built-in gestures
  (buildup, drop, breakdown, tension-peak, anticipation)
- **`nous.trajectory`** — smooth automation curves (linear, breathe,
  smooth-step, bounce) for parameter automation over bars or sections
- **`nous.spectral`** — derives spectral characteristics from live Temporal
  Buffer snapshots; exposes texture as an improvisation context layer

### Synthesis and sampling
- **SuperCollider** — `compile-synth :sc` generates SynthDef strings; `start-sc!`
  manages the scsynth subprocess; `play!` dispatches to SC via OSC natively
- **FM synthesis** — `defoperator` / `compile-fm :sc` builds FM operator graphs
  and compiles them to sclang SynthDefs or alembic DSP patches
- **Sample player** — SC buffer management and playback; `play!` dispatch via
  `:sample` step-map key; TGrains granular playback
- **alembic** — Faust → WASM → CLAP pipeline; `alembic-load-patch!` compiles a
  Faust patch and loads it live into kairos; `alembic-hot-swap!` does a
  gapless block-boundary swap with no audio glitch

### Score and sample corpora
- **`nous.m21`** — Music21 corpus integration; `load-chorale` / `play-chorale!`
  for Bach SATB chorales by BWV number; `search-corpus` searches the full
  Music21 library by composer, file extension, or title; `load-work` loads
  any corpus work (Palestrina, Josquin, ABC files, MusicXML) in `:parts`,
  `:chords`, or `:intervals` mode; persistent server with two-level cache
- **`nous.freesound`** — Freesound API v2 client; `search-freesound` queries
  by keyword; `fetch-and-load!` downloads and registers a sample with
  the nous.sample buffer registry; `load-essentials!` primes a curated
  catalog of essential samples for offline use

### Ableton Link
- **Tempo sync** — join any Link session; all loops phase-quantize to the bar
  boundary; propose tempo changes that propagate to all peers
- **MIDI clock** — 24 PPQN MIDI clock output derived from the Link timeline
  (zero drift, hardware-grade accuracy)
- **Transport hooks** — callbacks fire on start/stop events from any peer;
  use to auto-arm SC or synchronise external hardware

### Device maps
- **EDN device files** — structured MIDI implementation maps for synthesisers,
  controllers, and FX units under `resources/devices/`
- **Semantic CC access** — `device-send!` by parameter name, not raw CC number
- **NRPN dispatch** — 14-bit NRPN sequences built and sent automatically
- **MPE device profiles** — LinnStrument, Hydrasynth; per-note axis documentation
- **MIDI learn** — `nous.learn` guides interactive device map authoring;
  tap/wiggle physical controls while nous listens and writes the EDN map

### Control and connectivity
- **Control tree** — hierarchical parameter store; `ctrl/set!` / `ctrl/get`;
  MIDI CC binding; undo stack; checkpoints; WebSocket push to browser
- **HTTP server** — `start-server!` exposes the ctrl tree over REST + WebSocket
  for browser control surfaces and peer nodes
- **Peer sync** — `nous.peer` mounts a remote nous session; polls ctrl-tree
  paths and propagates harmony context and spectral state
- **Keyboard performance** — `nous.ivk` turns the computer keyboard into a
  live performance surface with extensible layouts (harmonic, interval,
  chromatic, NDLR, scale)

---

## Quick start

### Prerequisites

- Java 21+ and [Leiningen](https://leiningen.org)
- A runtime peer: [kairos](https://github.com/nomos-studio/kairos) (recommended)
  or [aion](https://github.com/nomos-studio/aion) (MIDI/Link only)

See [BUILD.md](BUILD.md) for C++ peer build instructions and all options.

### REPL

```bash
lein repl
```

```clojure
(require '[nous.user :refer :all])

;; Auto-discover kairos or aion and connect MIDI output
(start-sidecar! :midi-port "IAC")

(start! :bpm 120)

(deflive-loop :hi-hat {}
  (play! {:pitch/midi 42 :dur/beats 1/8 :mod/velocity 80})
  (sleep! 1/4))

(set-bpm! 140)
(stop-loop! :hi-hat)
(stop!)
```

### Install a peer

```bash
# kairos — full CLAP host + audio (recommended)
git clone https://github.com/nomos-studio/kairos.git
cd kairos && cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
sudo cp build/kairos /usr/local/bin/

# aion — MIDI/Link only, no CLAP
git clone https://github.com/nomos-studio/aion.git
cd aion && cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
sudo cp build/aion /usr/local/bin/
```

See [doc/user-manual.md](doc/user-manual.md) for a complete guide.

---

## Architecture

```
Clojure REPL (nous)
  │  deflive-loop / play! / sleep! / ctrl/set!
  │
  ├── nous.loop      — virtual time, live-loop threads, *tuning-ctx*
  ├── nous.ctrl      — control tree (MIDI CC binding, undo, checkpoints)
  ├── nous.dsl       — play!, arp!, phrase!, harmony/chord/tuning context
  ├── nous.device    — EDN device maps, device-send!, NRPN dispatch
  ├── nous.link      — Ableton Link client (optional)
  ├── nous.sc        — SuperCollider via OSC (optional)
  └── nous.kairos    — kairos / aion via Unix domain socket
        │
        ├── kairos (C++)           ← CLAP host + audio
        │     ├── Plugin graph (CLAP plugins, alembic WASM)
        │     ├── MIDI out/in (RtMidi)
        │     ├── MIDI clock (24 PPQN, zero drift)
        │     └── Link engine (Ableton Link SDK)
        │
        └── aion (C++)             ← MIDI/Link only
              ├── MIDI out/in (RtMidi)
              ├── MIDI clock
              └── Link engine
```

The JVM never parks on a MIDI write. All note delivery happens in the C++
scheduler thread, independent of JVM garbage collection.

---

## Included device maps

### ASM

| Device | Type | Notes |
|--------|------|-------|
| Hydrasynth Explorer | Synth | 8-voice, MPE, full NRPN map, MTS |
| Hydrasynth Desktop | Synth | 8-voice desktop, MPE, full NRPN map, MTS |
| Hydrasynth Deluxe | Synth | 16-voice, MPE, full NRPN map, MTS |
| Hydrasynth Keyboard 61 | Synth | 61-key, 16-voice, MPE, full NRPN map, MTS |
| Leviasynth | Synth | Polyphonic; full NRPN map |

### Arturia

| Device | Type | Notes |
|--------|------|-------|
| MicroFreak | Synth | Mono, digital osc + analog filter, MTS Scale Tuning |

### Hologram Electronics

| Device | Type | Notes |
|--------|------|-------|
| Microcosm | Granular FX / Looper | 11 effects, 60s Phrase Looper, full CC map, PC preset selection |
| KeyStep Pro | Controller / Sequencer | 4-track sequencer, CV/Gate, polyphonic |
| KeyStep (original) | Controller | 32-key, hardware-unverified; see note below |

### Behringer

| Device | Type | Notes |
|--------|------|-------|
| LM DRUM | Drum machine | LinnDrum clone; 109 sounds, 64-step seq, tuning CCs 75-90, filter CC 74 |
| 2600 | Semi-modular | ARP 2600 clone; MIDI CV/gate + limited CC |
| K-2 MK2 | Semi-modular | Korg MS-20 MK2 clone |
| Kobol Expander | Semi-modular | RSF Kobol Expander clone |
| Neutron | Semi-modular | Original Behringer design; paraphonic; 3 CCs + SysEx |
| Pro-1 | Synth | Sequential Pro-One clone; mono |
| Pro-800 | Synth | 8-voice analog poly |
| Pro-800 Stereo Pair | Synth | Dual Pro-800 configured as stereo pair |
| Proton | Semi-modular | Paraphonic semi-modular |

### Boss

| Device | Type | Notes |
|--------|------|-------|
| DD-500 | Digital delay | Dual engine, tap tempo |
| ES-8 | Effects switcher | 8-loop patch bay, MIDI-controllable |
| MD-500 | Modulation | 32 algorithm types |
| RV-500 | Reverb | Dual reverb engine |
| SDE-3000D | Dual delay | Stereo engine A/B |
| WAZA-TAE | Amp / headphone | WAZA-AIR Top Amplifier Experience |

### Elektron

| Device | Type | Notes |
|--------|------|-------|
| Analog Heat FX+ | Stereo FX | 8-mode analog circuit, overdrive to saturation |
| Digitone | FM Synth | 8-voice, 4-part, FM + analog filter |

### Intellijel

| Device | Type | Notes |
|--------|------|-------|
| Cascadia | Semi-modular | Dual-voice, Eurorack + desktop |

### Korg

| Device | Type | Notes |
|--------|------|-------|
| Minilogue XD | Synth | 4-voice poly + digital multi-engine |

### Moog

| Device | Type | Notes |
|--------|------|-------|
| Minitaur | Synth | Mono bass, full NRPN map |
| Sub 37 | Synth | Paraphonic, duo mode, full NRPN map |
| Subsequent 25 | Synth | Sub Phatty successor; 25-key; full NRPN map |

### MuseKinetiks

| Device | Type | Notes |
|--------|------|-------|
| 12-Step | Controller | CV + MIDI chromatic pedal board |

### Novation

| Device | Type | Notes |
|--------|------|-------|
| Peak | Synth | 8-voice, digital osc + analog filter |
| Summit | Synth | 16-voice, stereo, dual Peak engines |

### Roger Linn Design

| Device | Type | Notes |
|--------|------|-------|
| LinnStrument 200 | Controller | MPE, per-note X/Y/Z axes |

### Singular Sound

| Device | Type | Notes |
|--------|------|-------|
| Aeros Loop Studio | Looper | 6×6 track looper; transport CC + song PC |
| BeatBuddy | Drum machine | MIDI-controlled drum pedal; part of ecosystem chain |
| MIDI Maestro | Foot controller | 6-button transmit-only; BeatBuddy + Aeros control |

### Strymon

| Device | Type | Notes |
|--------|------|-------|
| NightSky | Reverb synth | Texture automation, freeze/bloom cue |
| Volante | Delay / echo | Head matrix, SOS looping |

### Torso Electronics

| Device | Type | Notes |
|--------|------|-------|
| T-1 | Sequencer | Algorithmic, 16-track pattern generator |

### Two Notes

| Device | Type | Notes |
|--------|------|-------|
| Torpedo Captor X | Load box | Reactive load, IR loader, DI |

---

> **Hardware verification notes:**
> - Boss 500-series and Strymon CC assignments are from published MIDI implementation
>   charts; verify against the official Owner's Manual before relying on specific CC
>   numbers in production.
> - The original Arturia KeyStep device map has not been tested on hardware.
> - Behringer semi-modular CC counts vary by firmware; SysEx global config covers
>   parameters not exposed as real-time CCs.

---

## Ableton Link

Build kairos or aion with Link to sync with other apps on the LAN:

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release -DNOUS_ENABLE_LINK=ON
cmake --build build --parallel
```

> **License**: enabling Link changes the peer binary's license from
> LGPL-2.1-or-later to GPL-2.0-or-later. The Clojure library (EPL-2.0) is
> unaffected. See [doc/licensing.md](doc/licensing.md).

---

## License

| Component | License |
|-----------|---------|
| Clojure library (`src/`, `test/`) | [EPL-2.0](LICENSE) |
| C++ peers (kairos, aion) | [LGPL-2.1-or-later](LICENSES/LGPL-2.1.txt) |
| Link engine (kairos/aion with `NOUS_ENABLE_LINK=ON`) | [GPL-2.0-or-later](LICENSES/GPL-2.0.txt) |

See [doc/licensing.md](doc/licensing.md) for the full licensing strategy and
the implications of building with Ableton Link enabled.
