# nous User Manual

Version 0.18.0 · June 2026

---

## Contents

1. [Prerequisites](#1-prerequisites)
2. [Build](#2-build)
3. [First Notes in 5 Minutes](#3-first-notes-in-5-minutes)
4. [Core Concepts](#4-core-concepts)
5. [MIDI Hardware Setup](#5-midi-hardware-setup)
6. [Live Loops](#6-live-loops)
7. [Music Theory](#7-music-theory)
8. [Device Maps](#8-device-maps)
9. [MPE and Microtonal Tuning](#9-mpe-and-microtonal-tuning)
10. [Ableton Link](#10-ableton-link)
11. [FX Automation](#11-fx-automation)
12. [Control Tree](#12-control-tree)
13. [Generative Techniques](#13-generative-techniques)
14. [Studio Topology](#14-studio-topology)
15. [Temporal Buffer](#15-temporal-buffer)
16. [Threshold Extractor](#16-threshold-extractor)
17. [Ensemble Harmony](#17-ensemble-harmony)
18. [ITexture and Spectral Analysis](#18-itexture-and-spectral-analysis)
19. [Ensemble Improvisation Agent](#19-ensemble-improvisation-agent)
20. [Peer Discovery](#20-peer-discovery)
21. [Note Transformers](#21-note-transformers)
22. [Score Corpus (`nous.m21`)](#22-score-corpus-nousm21)
22a. [Freesound Sample Library (`nous.freesound`)](#22a-freesound-sample-library-nousfreesound)
23. [SuperCollider Integration](#23-supercollider-integration)
24. [Spatial Field](#24-spatial-field)
25. [Sample Player and Freesound](#25-sample-player-and-freesound)
26. [SC Synthesis Showcase](#26-sc-synthesis-showcase)
27. [Waveshaping and Spectral Bridge](#27-waveshaping-and-spectral-bridge)
28. [DynKlank Physical Modeling](#28-dynklank-physical-modeling)
29. [Composition from Hardware](#29-composition-from-hardware)
30. [8-op FM Synthesis](#30-8-op-fm-synthesis)
31. [OSC Push-Subscribe](#31-osc-push-subscribe)
32. [nREPL Remote Eval](#32-nrepl-remote-eval)
33. [Configuration Registry](#33-configuration-registry)
34. [MCP Bridge (AI Compositional Collaborator)](#34-mcp-bridge)
35. [Keyboard Performance (`nous.ivk`)](#35-keyboard-performance-nousivk)
36. [MIDI Input (`nous.midi-in`)](#36-midi-input-nousmidi-in)
37. [Process Supervisor (`nous.supervisor`)](#37-process-supervisor-noussupervisor)
38. [Arpeggiator (`nous.arp`)](#38-arpeggiator-nousarp)
39. [Reference: REPL Commands and Step Keys](#39-reference)
40. [Browser Control Surface (`nous-ui`)](#40-browser-control-surface)
41. [Step Sequencer Protocol (`nous.seq`)](#41-step-sequencer-protocol-nousseq)
42. [Pattern × Rhythm Motifs (`nous.pattern`)](#42-pattern--rhythm-motifs-nouspattern)
43. [Transaction Journal (`nous.journal`)](#43-transaction-journal-nousjournal)
44. [Kairos CLAP Host (`nous.kairos`)](#44-kairos-clap-host-nouskairos)
45. [MTS Retune Arc (`nous.mts`)](#45-mts-retune-arc-nousmts)
46. [Berlin School Vocabulary (`nous.berlin`)](#46-berlin-school-vocabulary-nousberlin)
47. [Harmonic Excursion Arc (`nous.excursion`)](#47-harmonic-excursion-arc-nousexcursion)
48. [Journey Conductor (`nous.journey`)](#48-journey-conductor-nousjourney)
49. [Terrain Sequencer (`nous.terrain`)](#49-terrain-sequencer-noustterrain)
50. [Book of Sounds (`nous.book`)](#50-book-of-sounds-nousbook)

---

## 1. Prerequisites

| Requirement | Minimum |
|-------------|---------|
| Java | 21+ |
| [Leiningen](https://leiningen.org) | 2.10+ |
| CMake | 3.20+ |
| C++ compiler | clang 14 / GCC 12 / MSVC 2022 |
| MIDI interface or IAC Driver | macOS/Linux/Windows |

**macOS** (recommended): all C++ dependencies are auto-fetched by CMake via
FetchContent. No Homebrew packages needed beyond Java and Leiningen.

**Linux**: install ALSA headers and build tools for MIDI support.

Ubuntu / Ubuntu Studio (recommended Linux target):
```bash
sudo apt install libasound2-dev cmake build-essential
```

Fedora / RHEL / CentOS Stream:
```bash
sudo dnf install alsa-lib-devel cmake gcc-c++ make
```

**Windows**: use MSVC or clang-cl; MIDI uses WinMM (built-in).

---

## 2. Build

```bash
git clone https://github.com/nomos-studio/nous.git
cd nous
lein test   # Clojure test suite; no MIDI hardware needed
```

The Clojure library has no C++ build requirement on its own. The C++ runtime
peers (kairos and aion) live in separate repos and are built independently.

### kairos (recommended — CLAP host + audio synthesis)

kairos is the full-featured peer: it hosts CLAP audio plugins, runs DSP graphs
compiled from alembic, and handles audio I/O in addition to MIDI and Link.

```bash
git clone https://github.com/nomos-studio/kairos.git
cd kairos
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
sudo cp build/kairos /usr/local/bin/
```

### aion (lightweight — MIDI/Link only, no CLAP)

aion is the minimal peer: MIDI output, Ableton Link, and IPC only. No audio
engine, no CLAP. Suitable for low-power hardware (Raspberry Pi Zero 2W) or
sessions where you need only a MIDI clock / port router.

```bash
git clone https://github.com/nomos-studio/aion.git
cd aion
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
sudo cp build/aion /usr/local/bin/
```

`start-sidecar!` searches `/usr/local/bin`, `/opt/homebrew/bin`, and `$PREFIX/bin`
and launches kairos if found, aion as fallback.

### Build with Ableton Link

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release \
      -DNOUS_ENABLE_LINK=ON
cmake --build build --parallel
```

> **License**: Link changes kairos/aion's license from LGPL-2.1 to GPL-2.0.
> The Clojure library (EPL-2.0) is unaffected. See [doc/licensing.md](licensing.md).

### Offline build

On airgapped machines, pre-fetch the C++ dependencies and set
`FETCHCONTENT_SOURCE_DIR_ASIO` and `FETCHCONTENT_SOURCE_DIR_RTMIDI` to the
local paths. See [BUILD.md](../BUILD.md) for details.

---

## 3. First Notes in 5 Minutes

```bash
lein repl
```

```clojure
;; Load the nous API (convenience REPL namespace — exports everything)
(require '[nous.user :refer :all])

;; Connect MIDI output (opens port 0 by default)
(start-sidecar!)
;; or: (start-sidecar! :midi-port "IAC")   ; by name substring
;; or: (start-sidecar! :midi-port 2)       ; by index

;; Start the clock
(start! :bpm 120)

;; Your first live loop — plays middle C every beat
(deflive-loop :pulse {}
  (play! :C4)
  (sleep! 1))

;; Change it live — takes effect on the next iteration, no restart needed
(deflive-loop :pulse {}
  (play! (rand-nth [:C4 :E4 :G4]))
  (sleep! 1/2))

;; Add a second loop — they run concurrently
(deflive-loop :bass {}
  (play! {:pitch/midi 36 :dur/beats 2})
  (sleep! 2))

;; Stop individual loops
(stop-loop! :pulse)

;; Stop everything
(stop!)
```

---

## 4. Core Concepts

### Virtual Time

nous runs on *virtual time*: a beat counter that advances strictly from the
clock. `sleep!` parks the loop thread until the target beat, using
`LockSupport/parkUntil` for sub-millisecond accuracy. The JVM GC cannot cause
timing drift.

```
Beat 0  ─── play! ─── sleep!(1) ─── Beat 1 ─── play! ─── sleep!(1) ─── Beat 2
```

`sleep!` accepts beats as integers, fractions (`1/4`), or decimals (`0.25`).
One beat equals one quarter note at the current BPM.

### Steps

`play!` sends a *step* — a map of musical attributes:

```clojure
{:pitch/midi    60        ; MIDI note number (C4)
 :dur/beats     1/4       ; duration: 1/4 beat = one sixteenth note
 :mod/velocity  100       ; 0–127
 :mod/channel   1}        ; MIDI channel 1–16
```

You can pass a pitch keyword (`play! :C4`) or a raw step map. Steps flow
through the DSL pipeline: apply harmony context → apply tuning → schedule
with the runtime peer.

### The Runtime Peer

All MIDI output, timing, and audio routing is handled by a `nomos-rt` peer —
a native C++ process that schedules notes at precise wall-clock times,
independent of JVM GC. The Clojure REPL and the peer communicate over a
Unix domain socket.

```
REPL  ──(Unix socket)──  kairos / aion  ──(RtMidi)──  MIDI hardware
                               │
                      (CLAP plugins, audio I/O)  ← kairos only
```

Two peer variants share the same socket protocol:

| Peer | Default socket | Capabilities |
|------|---------------|--------------|
| **kairos** | `/tmp/kairos.sock` | Full CLAP host — audio synthesis, DSP graph, WASM hot-swap |
| **aion** | `/tmp/aion.sock` | MIDI + Ableton Link only; no CLAP; suitable for RPi Zero 2W |

`start-sidecar!` auto-discovers whichever peer is installed, preferring kairos.
`start-kairos!` and `start-aion!` target a specific peer explicitly.

---

## 5. MIDI Hardware Setup

### Discover ports

```clojure
(list-midi-ports)
;; => {:output [{:index 0 :name "IAC Driver Bus 1"}
;;              {:index 1 :name "Hydrasynth"}]
;;     :input  [{:index 0 :name "IAC Driver Bus 1"}
;;              {:index 1 :name "LinnStrument"}]}
```

### Connect output + input monitoring

```clojure
;; Output only
(start-sidecar! :midi-port "Hydra")

;; Output + MIDI input monitor (for MPE controllers, MIDI learn, etc.)
(start-sidecar! :midi-port "Hydra" :midi-in-port "Linn")

;; Wait for a specific incoming message (e.g. any NoteOn)
(await-midi-message #(= 0x90 (bit-and (:status %) 0xF0)) 5000)
;; => {:time-ns 1712345678900 :status 0x91 :b1 60 :b2 80}
```

### IAC Driver (macOS virtual ports)

Open **Audio MIDI Setup → MIDI Studio → IAC Driver** and create a Bus.
Start nous targeting that bus, then route it to a software synth (Surge XT,
VCV Rack, Ableton Live) in your DAW or audio router.

---

## 6. Live Loops

### Anatomy of a live loop

```clojure
(deflive-loop :name
  {:bpm     120         ; optional: override global BPM for this loop
   :channel 2           ; default MIDI channel
   :tuning  my-tuning}  ; optional: microtonal tuning context
  ;; body — evaluated every iteration
  (play! :C4)
  (sleep! 1))
```

### Hot-swap

Re-evaluating a `deflive-loop` form with the same name swaps the body *on the
next iteration boundary* — the running loop is never stopped, so there is no
click or timing glitch. The old body finishes its current `sleep!`, then the
new body starts.

### Loop control

```clojure
(stop-loop! :name)   ; stop after current iteration completes
(set-bpm! 140)       ; change global BPM; all loops update on their next sleep
```

### Beat-boundary sync — `:restart-on-bar` (Q32)

When starting a new loop mid-session you often want it to begin on a clean beat
boundary rather than wherever the clock happens to be. `:restart-on-bar` parks
the loop body until the next boundary arrives, then releases it. It works with
or without Ableton Link active.

```clojure
;; Snap to next system quantum (default 4 beats — reads :config :link/quantum)
(deflive-loop :bass {:restart-on-bar true}
  (play! :C2) (sleep! 1)
  (play! :G2) (sleep! 1))

;; Snap to next 2-beat boundary
(deflive-loop :hi-hat {:restart-on-bar 2}
  (play! :Cs4) (sleep! 1/2))

;; Snap to next 8-beat (2-bar) boundary
(deflive-loop :phrase {:restart-on-bar 8}
  ;; body starts exactly on a 2-bar boundary
  (play! :C4) (sleep! 4)
  (play! :G4) (sleep! 4))
```

When used with the Torso T-1 as a Link peer, `:restart-on-bar true` snaps to
the T-1's phrase boundary — new nous loops enter phase-locked to T-1 patterns
without manual timing.

### Playing chords and voicings

```clojure
;; Play all notes of a chord simultaneously
(play-chord! (make-chord :C 4 :maj7))

;; Play a specific voicing (vector of MIDI note numbers, bass → soprano)
(play-voicing! [48 55 64 71])

;; Arpeggiate a chord
(arp! (make-chord :A 4 :minor) :up 1/4)
;; or from root + quality keywords:
(arp! :A :minor :up 1/4)
```

---

## 7. Music Theory

### Scales and chords

```clojure
;; Build scales and chords
(def c-major (make-scale :C 4 :major))
(def am7     (make-chord :A 4 :m7))

;; Identify what chord a set of MIDI notes forms
(identify-chord [60 64 67 71])
;; => {:root :C :root-pc 0 :quality :maj7 :inversion 0 :score 1.0}

;; Roman numeral analysis — returns a map, :roman is a keyword
(chord->roman c-major (make-chord :G 4 :dom7))
;; => {:root :G :root-pc 7 :quality :dom7 :inversion 0 :score 1.0
;;     :degree 4 :roman :V :in-key? true}

;; Suggest scales that fit a set of pitch classes
(suggest-scales [0 2 4 5 7 9 11])
;; => [{:root :C :mode :major :fitness 1.0} ...]
```

### Key detection and analysis

```clojure
;; Detect key from a pitch-class set (0=C, 2=D, 4=E, ...)
(detect-key [0 2 4 5 7 9 11])
;; => {:root :C :mode :major :score 0.95}

;; Analyze a progression — each result is a chord->roman map
(analyze-progression c-major
  [(make-chord :C 4 :major) (make-chord :F 4 :major)
   (make-chord :G 4 :dom7)  (make-chord :C 4 :major)])
;; => [{:root :C :quality :major :roman :I  :degree 0 :in-key? true ...}
;;     {:root :F :quality :major :roman :IV :degree 3 :in-key? true ...}
;;     {:root :G :quality :dom7  :roman :V  :degree 4 :in-key? true ...}
;;     {:root :C :quality :major :roman :I  :degree 0 :in-key? true ...}]
```

### Tension and progression suggestion

```clojure
;; Score the tension of an analyzed chord (0.0 = stable, 1.0 = maximum tension)
;; tension-score takes a chord->roman result map, not a Chord record
(tension-score (chord->roman c-major (make-chord :G 4 :dom7)))  ; => 0.9  (V7)
(tension-score (chord->roman c-major (make-chord :C 4 :major))) ; => 0.0  (I)
;; or pass a bare map with :degree and :quality:
(tension-score {:degree 4 :quality :dom7})   ; => 0.9  (V7)
(tension-score {:degree nil :quality :major}) ; => 0.85 (chromatic — degree unknown)

;; Generate a progression matching a tension arc (4 chords)
(suggest-progression c-major [0.0 0.3 0.7 0.0])
;; => [{:root :C :quality :major}   ; I  — stable
;;     {:root :A :quality :minor}   ; vi — mild tension
;;     {:root :G :quality :dom7}    ; V7 — high tension
;;     {:root :C :quality :major}]  ; I  — resolution

;; Include borrowed (mode-mixture) chords
(suggest-progression c-major [0.0 0.8 1.0 0.0] :include-borrowed? true)
```

### Harmony context

```clojure
;; All play! calls in the loop snap pitches to the active scale
(use-harmony! (make-scale :C 4 :dorian))

;; Override harmony for a single form's duration
(with-harmony (make-scale :F 4 :lydian)
  (play! :C4)
  (play! :E4))
```

---

## 8. Device Maps

Device maps are EDN files in `resources/devices/` that describe a hardware
unit's MIDI implementation. They enable semantic CC access, NRPN routing,
and device-specific helpers.

### Loading a device

```clojure
(require '[nous.device :as device])

(device/load-device-map "hydrasynth-explorer.edn")
```

### Sending to a device

`device-send!` takes a device id, a path vector (the parameter location in
the device map), and a value (integer 0–127 or a semantic keyword).

```clojure
;; Send by path and numeric value
(device/device-send! :asm/hydrasynth-explorer [:filter :cutoff] 80)

;; Send using a semantic label defined in the device map's :values list
(device/device-send! :korg/minilogue-xd [:vco1 :wave] :saw)

;; Send an NRPN via the device's registered path
(device/device-send! :asm/hydrasynth-explorer [:osc1 :semitone] 64)
```

### Included device maps (v0.1.0)

| File | Device | Type |
|------|--------|------|
| `arturia-keystep-pro.edn` | Arturia KeyStep Pro | Controller / Sequencer |
| `arturia-keystep.edn` | Arturia KeyStep (original) | Controller |
| `hydrasynth-explorer.edn` | ASM Hydrasynth Explorer | Synth / MPE |
| `hydrasynth-desktop.edn` | ASM Hydrasynth Desktop | Synth / MPE |
| `hydrasynth-deluxe.edn` | ASM Hydrasynth Deluxe | Synth / MPE |
| `hydrasynth-keyboard61.edn` | ASM Hydrasynth Keyboard 61 | Synth / MPE |
| `korg-minilogue-xd.edn` | Korg Minilogue XD | Synth |
| `roger-linn-linnstrument.edn` | LinnStrument 200 | Controller / MPE |
| `strymon-nightsky.edn` | Strymon NightSky | Reverb synth |
| `strymon-volante.edn` | Strymon Volante | Delay / echo |
| `boss-dd-500.edn` | Boss DD-500 | Digital delay |
| `boss-rv-500.edn` | Boss RV-500 | Reverb |
| `boss-md-500.edn` | Boss MD-500 | Modulation |
| `boss-sde-3000d.edn` | Boss SDE-3000D | Dual delay |

> **Note on CC numbers:** Boss 500-series and Strymon CC assignments in these
> maps are representative. Verify against the official MIDI implementation
> chart (in each manufacturer's Owner's Manual) before relying on specific CC
> numbers in production.

---

## 9. MPE and Microtonal Tuning

### MPE (MIDI Polyphonic Expression)

nous supports MPE for controllers and synths that use per-note expression.
The Hydrasynth and LinnStrument ship with full MPE device maps. MPE events
(per-note pitch bend, CC 74 slide, channel pressure) are routed via the
peer's pitch-bend and channel-pressure IPC frames.

```clojure
;; Send a per-note pitch bend (14-bit: 0=full down, 8192=centre, 16383=full up)
(send-pitch-bend! time-ns channel 10000)

;; Send channel pressure (aftertouch)
(send-channel-pressure! time-ns channel 80)
```

### Scala / KBM microtonal tuning

Load `.scl` (scale definition) and `.kbm` (keyboard mapping) files from the
Scala scale archive or your own tuning files:

```clojure
(require '[nous.scala :as scala])

(def ms  (scala/load-scl "31edo.scl"))
(def kbm (scala/load-kbm "31edo.kbm"))   ; optional

;; Apply tuning to a live loop via the :tuning option
(deflive-loop :tuned-melody {:tuning {:scale ms :kbm kbm}}
  (play! :C4)
  (play! :E4)
  (sleep! 1))
```

Within the loop, `play!` automatically converts MIDI note numbers to the
nearest scale degree plus a pitch-bend value. The peer delivers this as
a NoteOn followed immediately by a PitchBend on the same channel.

### MTS Bulk Dump (Hydrasynth + MicroFreak)

The Hydrasynth (all models) and Arturia MicroFreak accept MTS (MIDI Tuning
Standard) Bulk Dump to retune all 128 keys at once without per-note pitch bend:

```clojure
;; Retune the Hydrasynth to 31-EDO
(def ms (scala/load-scl "31edo.scl"))
(send-mts! ms)

;; With an explicit keyboard map
(def kbm (scala/load-kbm "whitekeys.kbm"))
(send-mts! ms kbm)
```

The 408-byte SysEx is generated by `scale->mts-bytes` and sent via the
peer's SysEx frame (type 0x06). The tuning persists in the synth until
the patch changes or the unit is power-cycled.

---

## 10. Ableton Link

Ableton Link synchronises tempo and beat phase across applications on the
same LAN (Ableton Live, Bitwig, Sonic Pi, VCV Rack, and any other
Link-enabled software).

```clojure
(require '[nous.link :as link])

;; Join the Link session (creates one if no peers are present)
(link/enable!)

;; Query the session
(link/bpm)    ; => 120.0  (session BPM from most recent peer push)
(link/peers)  ; => 2      (number of connected peers)

;; New loops wait for the next bar boundary before their first iteration
(deflive-loop :synced {}
  (play! :C4)
  (sleep! 1))

;; Propose a tempo change to all peers
(link/set-bpm! 130)

;; Leave the session
(link/disable!)
```

Link requires kairos or aion to be built with `-DNOUS_ENABLE_LINK=ON`.

### Transport control (Phase 2)

`start-transport!` and `stop-transport!` commit a playing-state change to the
Link session so every peer starts or stops together:

```clojure
;; All Link peers start playing simultaneously
(link/start-transport!)

;; All Link peers stop
(link/stop-transport!)

;; Query transport state
(link/playing?)   ; => true / false / nil (not active)
```

`core/set-bpm!` propagates automatically to all Link peers when a session is
active — no separate `link/set-bpm!` call is needed.

### Transport-change hooks (Phase 2)

Register a callback to react when *any* Link peer changes the transport state:

```clojure
;; Start all local loops when a Link peer hits play
(link/on-transport-change! ::auto-start
  (fn [playing?]
    (if playing?
      (do (deflive-loop :kick {} (play! {:pitch/midi 36 :dur/beats 1/4}) (sleep! 1))
          (deflive-loop :bass {} (play! {:pitch/midi 43 :dur/beats 1/2}) (sleep! 2)))
      (stop!))))

;; Remove the hook
(link/remove-transport-hook! ::auto-start)

;; Inspect all registered hooks
(link/transport-hooks)
```

Hooks fire synchronously on the peer reader thread — keep them fast; any
exception is caught, printed, and swallowed so it cannot block the push path.

### Quantum

The quantum (phrase length in beats) determines phase alignment.  All peers
with the same quantum are guaranteed to be in phase with each other.  The
default is 4 beats; change it via the configuration registry:

```clojure
(config/set-config! :link/quantum 8)   ; 8-beat phrase alignment
```

---

## 11. FX Automation

nous treats MIDI-addressable FX units (delays, reverbs, modulators) as
first-class devices. Load their EDN maps and automate parameters with the
same tools used for synth parameters.

### Basic FX send

```clojure
;; Send a CC directly via device path
(device/device-send! :boss/dd-500 [:engine-a :feedback] 80)

;; Sweep feedback over 8 bars
(deflive-loop :delay-build {}
  (doseq [v (range 20 100 10)]
    (device/device-send! :boss/dd-500 [:engine-a :feedback] v)
    (sleep! 1))
  (stop-loop! :delay-build))
```

### Strymon NightSky texture arc

```clojure
;; Freeze the reverb — holds current texture as a sustained pad
(send-cc! time-ns 1 24 127)   ; CC 24 = 127: freeze on

;; Release
(send-cc! time-ns 1 24 0)     ; CC 24 = 0: freeze off
```

### Volante head matrix

```clojure
;; Bring in or mute the four tape echo heads independently
(defn set-heads! [h1 h2 h3 h4]
  (let [t (now)]
    (send-cc! t 1 20 h1)   ; head 1
    (send-cc! t 1 21 h2)   ; head 2
    (send-cc! t 1 22 h3)   ; head 3
    (send-cc! t 1 23 h4))) ; head 4

;; Tight single 16th echo only
(set-heads! 127 0 0 0)

;; Gradually add the long ambient echo over 4 bars
(deflive-loop :head-swell {}
  (doseq [v (range 0 80 20)]
    (send-cc! (now) 1 23 v)
    (sleep! 1))
  (stop-loop! :head-swell))
```

---

## 12. Control Tree

The control tree is a hierarchical parameter store with MIDI CC binding, undo,
and checkpoints. Bind a physical knob to a named path and read it from any loop.

```clojure
(require '[nous.ctrl :as ctrl])

;; Set a value
(ctrl/set! [:filter/cutoff] 64)

;; Bind a MIDI CC to a path
(ctrl/bind! [:filter/cutoff]
            {:type :midi-cc :channel 1 :cc-num 74})

;; Read in a loop (ctrl/get returns the current value)
(deflive-loop :filter-sweep {}
  (let [cutoff (ctrl/get [:filter/cutoff])]
    (device/device-send! :asm/hydrasynth-explorer [:filter :cutoff] cutoff))
  (sleep! 1/4))

;; Undo / checkpoint
(ctrl/checkpoint!)
(ctrl/set! [:filter/cutoff] 100)
(ctrl/undo!)   ; => restores 64
```

### HTTP control server

The ctrl tree is also exposed over HTTP so that peer nodes can poll it. Start
the server before calling `peer/mount-peer!` on a remote host.

```clojure
(require '[nous.server :as server])

;; Start on default port 7177
(server/start-server!)

;; Or choose a port
(server/start-server! :port 7278)

;; Check status
(server/server-running?)  ; => true
(server/server-port)      ; => 7278

;; Stop
(server/stop-server!)
```

The server exposes the following endpoints:

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/ping` | Health check — returns `{"ok": true}` |
| `GET`  | `/bpm` | Current BPM |
| `PUT`  | `/bpm` | Set BPM — body `{"bpm": 140}` |
| `GET`  | `/ctrl` | Full ctrl-tree dump (JSON array) |
| `GET`  | `/ctrl/<path>` | Read one node by slash-separated path |
| `PUT`  | `/ctrl/<path>` | Write one node — body `{"value": ...}` |

Peer nodes poll `/ctrl/ensemble/harmony-ctx` and `/ctrl/spectral/state`
automatically when mounted via `peer/mount-peer!`.

### WebSocket push (`/ws`)

`start-server!` also exposes a WebSocket endpoint at `ws://localhost:7177/ws`.
Any browser (or other client) that upgrades to WebSocket receives a JSON
message every time the ctrl tree changes:

```json
{"path": ["filter-a", "cutoff"], "value": 0.73}
```

Multiple clients are supported simultaneously. Clients may also write into the
ctrl tree by sending the same JSON format inbound — the server calls
`ctrl/set!` on arrival.

```javascript
// Browser — live ctrl-tree mirror
const ws = new WebSocket("ws://localhost:7177/ws");
ws.onmessage = (e) => {
  const {path, value} = JSON.parse(e.data);
  console.log(path, "→", value);
};

// Write from browser
ws.send(JSON.stringify({path: ["filter-a", "cutoff"], value: 0.5}));
```

### Global ctrl-tree watchers

`watch-global!` / `unwatch-global!` register a callback that fires on *every*
ctrl-tree write, regardless of path. The WebSocket broadcast uses this
internally; you can use the same hook for logging, OSC forwarding, or any
cross-cutting reaction:

```clojure
;; Log all ctrl changes to stdout
(watch-global! ::logger (fn [path value]
                          (println (str "[ctrl] " path " → " value))))

;; Remove it
(unwatch-global! ::logger)
```

Distinct from per-path `watch!` (fires only when a specific path changes) and
`unwatch-all!` (removes all per-path watchers on a given path).

### Browser control surface (`nous-ui`)

`start-server!` also serves a browser control surface at `http://localhost:7177/`.
The UI connects via `/ws` and displays two live panels:

- **Ctrl tree** — full snapshot of current ctrl-tree values, updated on every change.
- **Changes log** — last 60 ctrl writes, newest-first, with a brief green flash
  on each new entry.

BPM appears in the header alongside a **LIVE / connecting / OFFLINE** connection badge.

The UI is a compiled ClojureScript + Reagent application. Build it before starting
the server:

```sh
# Install npm deps (first time only)
npm install

# Development — hot-reload on CLJS changes
npx shadow-cljs watch app

# Production — single-pass release build
npx shadow-cljs release app
```

The compiled `resources/public/js/main.js` is served automatically by the http-kit
server; it is not committed to the repository (listed in `.gitignore`). If the build
has not been run, `GET /js/main.js` returns 404 and the page loads without
JavaScript.

The CLJS source lives in `src/nous_ui/core.cljs`. It uses only the native
`js/WebSocket` and `js/fetch` APIs plus Reagent — no additional runtime dependencies.

---

## 13. Generative Techniques

### Scale-weighted random pitch

```clojure
(require '[nous.random :as r])

;; Random pitch biased toward a scale's characteristic degrees
(r/weighted-scale (make-scale :C 4 :pentatonic-minor))
;; => a MIDI note number

;; Use in a loop
(deflive-loop :gen {}
  (play! (r/weighted-scale (make-scale :C 4 :pentatonic-minor)))
  (sleep! 1/4))
```

### Stochastic sequences

```clojure
(require '[nous.stochastic :as stoch]
         '[nous.seq        :as sq])

;; Build a stochastic generator context
(defstochastic marble
  {:channels 2 :x-spread 0.6 :x-bias 0.5 :t-bias 0.7})

;; IStepSequencer wrapper — use run-step! inside a live-loop
(def marble-seq (stoch/make-stochastic-seq marble :ch 0 :clock-div 1/8 :vel 90))

(deflive-loop :marble-melody {}
  (run-step! marble-seq))

;; Or draw pitch / gate manually:
(deflive-loop :marble-manual {}
  (when (stoch/next-t! marble 0)
    (play! {:pitch/midi (stoch/next-x! marble 0) :dur/beats 1/16}))
  (sleep! 1/8))

;; Euclidean rhythm pattern
(stoch/stochastic-rhythm {:pulses 5 :steps 16})
;; => [1 0 0 1 0 0 1 0 0 1 0 0 1 0 0 0]
```

`make-stochastic-seq` options: `:ch` (channel 0-based), `:vel`, `:clock-div`
(step duration, default `1/8`), `:gate` (gate fraction, default `0.9`).
`seq-cycle-length` returns nil — drive via `run-step!`, not `run-cycle!`.

### Fractal sequences

```clojure
(require '[nous.fractal :as frac]
         '[nous.seq     :as sq])

;; Define a fractal context with deffractal
(deffractal melody
  {:trunk      [{:pitch/midi 60 :dur/beats 1/4 :gate/on? true :gate/len 0.7}
                {:pitch/midi 64 :dur/beats 1/4 :gate/on? true :gate/len 0.7}
                {:pitch/midi 67 :dur/beats 1/4 :gate/on? true :gate/len 0.7}
                {:pitch/midi 71 :dur/beats 1/2 :gate/on? true :gate/len 0.8}]
   :transforms [:reverse :transpose]
   :root       60
   :transpose-semitones 7})

;; IStepSequencer wrapper — run-step! drives one step per call (infinite source)
(def mel-seq (frac/make-fractal-seq melody :vel 95))

(deflive-loop :fractal-mel {}
  (run-step! mel-seq))

;; Or use the raw next-step! API for manual control:
(deflive-loop :fractal-manual {}
  (when-let [step (frac/next-step! melody)]
    (when (:gate/on? step)
      (play! step)))
  (sleep! (:dur/beats step 1/4)))
```

`make-fractal-seq` option: `:vel` (default 100). Steps with `:gate/on? false`
are returned as rest events. `seq-cycle-length` returns nil — always use
`run-step!`, not `run-cycle!`.

### Conductor arcs

```clojure
;; Define a performance arc with named sections and built-in gestures
(defconductor! :set-1
  [{:name :intro     :bars 8  :gesture :groove-lock}
   {:name :buildup   :bars 16 :gesture :trance-buildup}
   {:name :drop      :bars 8  :gesture :drop}
   {:name :breakdown :bars 16 :gesture :breakdown}])

;; Fire it — plays through all sections in order
(fire! :set-1)

;; Jump to a section at the next bar boundary
(cue! :set-1 :drop)
```

---

## 14. Studio Topology

The studio topology file declares the logical layout of your MIDI studio --
which synthesizers, controllers, and FX units you have, and which substring
of the OS MIDI port name to use to find each one. This decouples nous
scripts from fragile port indices that change across reboots and USB reconnects.

### Creating your topology file

Copy the schema reference and fill in your hardware:

```bash
# Linux / macOS (XDG default)
cp doc/topology-example.edn ~/.config/nous/topology.edn
```

The path can also be set explicitly:

```bash
export NOUS_TOPOLOGY=/path/to/my-studio.edn
```

### Loading and using the topology

```clojure
(require '[nous.topology :as topology])

;; Load from default path (~/.config/nous/topology.edn or NOUS_TOPOLOGY)
(topology/load-topology!)

;; Or load from an explicit path
(topology/load-topology! "path/to/my-studio.edn")

;; Start kairos targeting a named device
(topology/start-kairos! :poly-synth)

;; Output to one device, monitor MIDI input from another
(topology/start-kairos! :poly-synth :input :keyboard)

;; Show what is loaded
(topology/print-topology)

;; Query individual devices
(topology/device-info :poly-synth)
;; => {:port-pattern "PolySynth" :midi/channel 1 :role :synth ...}

(topology/device-ids)
;; => [:bass-synth :cv-bridge :iac :keyboard :poly-synth :reverb]
```

### Topology file schema

See `doc/topology-example.edn` for a fully-annotated example. The essential
structure:

```edn
{:topology/id      :my-studio
 :topology/version "0.1.0"

 :devices
 {:poly-synth  {:port-pattern "PolySynth"
                :midi/channel  1
                :role          :synth}

  :keyboard    {:port-pattern "KeyboardCtrl Out"
                :in-pattern   "KeyboardCtrl In"  ; optional: separate input name
                :midi/channel  1
                :role          :controller}

  :reverb      {:port-pattern "ReverbFX"
                :midi/channel  1
                :role          :fx}}

 ;; Optional: document your interfaces and hosts (used in Layer 2+ routing)
 :interfaces  {:hub-1 {:manufacturer "Example" :model "USB Hub" ...}}
 :hosts       {:main {:role :nous-host :os :macos}}}
```

`port-pattern` is matched case-insensitively as a substring of the OS MIDI
port name — the same semantics as `(start-sidecar! :midi-port "substring")`.
Run `(list-midi-ports)` to see what port names your system reports.

---

## 15. Temporal Buffer

The Temporal Buffer is a beat-timestamped event store that replays events
from a sliding window of recent history. It is inspired by loop-and-modify
hardware (Mimeophon, Morphagene, Make Noise Phonogene) but operates in the
MIDI note-event domain rather than audio.

### Zones

Eight overlapping zones define the depth of history available for playback:

| Zone | Depth | Character |
|------|-------|-----------|
| `:z0` | 0.5 beats | Micro-repeat / flutter |
| `:z1` | 1 beat | Sixteenth note echo |
| `:z2` | 2 beats | Eighth note repeat |
| `:z3` | 8 beats | Short phrase (default) |
| `:z4` | 16 beats | Bar loop |
| `:z5` | 32 beats | Double bar |
| `:z6` | 64 beats | Four bars |
| `:z7` | 128 beats | Long form / ambient |

### Quick start

```clojure
(require '[nous.temporal-buffer :as tbuf])

;; Create a buffer with default settings (zone :z3, 8 beats deep)
(tbuf/deftemporal-buffer :echo)

;; Send events into the buffer from a live loop
(deflive-loop :melody {}
  (tbuf/temporal-buffer-send! :echo {:pitch/midi 60 :dur/beats 1/4})
  (sleep! 1/4))

;; Stop the buffer
(tbuf/stop! :echo)
```

### Built-in presets

Presets configure zone, rate, color, feedback and halo together:

```clojure
;; Start from a named preset
(tbuf/deftemporal-buffer-preset :my-buf :cosmos)

;; Available presets:
;;   :flux       -- dry passthrough, no processing
;;   :tape-echo  -- warm repeat with gentle feedback
;;   :looper     -- tight clocked loop with high feedback
;;   :cosmos     -- drifting ambient with warm color
;;   :mimeophon  -- dual-cursor Mimeophon-style with skew
```

### Rate and Doppler

Rate multiplies the playback cycle. Values above 1.0 speed up repetition
and pitch up (like tape); values below 1.0 slow down and pitch down.

```clojure
;; Half speed (and an octave down via Doppler)
(tbuf/temporal-buffer-rate! :echo 0.5)

;; Double speed
(tbuf/temporal-buffer-rate! :echo 2.0)

;; Clocked mode: rate affects timing only, pitch is frozen
(tbuf/temporal-buffer-clocked! :echo true)

;; Control the pitch coupling strength (cents per rate unit, default 1200)
(tbuf/temporal-buffer-tape-scale! :echo 80.0)  ; subtle Doppler
```

### Color

Color applies a per-pass transform to velocity, duration and pitch drift:

```clojure
;; Set color by preset keyword
(tbuf/temporal-buffer-color! :echo :warm)

;; Available presets:
;;   :dark      -- quieter, longer, slightly flat
;;   :warm      -- gentle fade, slight stretch
;;   :neutral   -- unmodified (default)
;;   :tape      -- slight random pitch wobble
;;   :bright    -- shorter, slightly sharp
;;   :crisp     -- short and bright (delay-like)

;; Or supply a custom fn [ev] -> ev
(tbuf/temporal-buffer-color! :echo
  (fn [ev] (update ev :mod/velocity #(max 0 (- % 5)))))
```

### Feedback

Feedback re-injects events back into the buffer after each playback pass:

```clojure
;; Gentle echo tail
(tbuf/temporal-buffer-feedback! :echo {:amount 0.4 :max-generation 6
                                        :velocity-floor 8})
;; :amount          -- re-injection probability per event (0.0-1.0)
;; :max-generation  -- stop feeding back after N passes
;; :velocity-floor  -- drop events quieter than this (prevents infinite tail)
```

### Halo

Halo generates temporal neighbor copies of each emitted event, creating a
smear or reverb-wash effect:

```clojure
(tbuf/temporal-buffer-halo! :echo
  {:amount 0.2 :copies 3 :spread 0.08 :pitch-spread 5
   :feedback-threshold 0.7 :max-halo-depth 4})
;; :amount              -- probability of generating halo copies
;; :copies              -- neighbor copies per emitted event
;; :spread              -- +/- beat offset range for copies
;; :pitch-spread        -- +/- cents range for copy pitch
;; :feedback-threshold  -- above this, copies enter the feedback buffer
;; :max-halo-depth      -- generation limit (prevents runaway wash)
```

### Hold (loop slice)

In Hold mode, large zones (`:z4`-`:z7`) lock a fixed window of the buffer
rather than following the rolling cursor:

```clojure
;; Freeze current 16-beat window and loop it
(tbuf/temporal-buffer-hold! :echo true)

;; Choose which beat offset to anchor the slice at
(tbuf/temporal-buffer-slice-start! :echo 4.0)

;; Unfreeze
(tbuf/temporal-buffer-hold! :echo false)
```

### Other controls

```clojure
;; Flip playback direction (retrograde)
(tbuf/temporal-buffer-flip! :echo true)

;; Change the active zone live
(tbuf/temporal-buffer-zone! :echo :z5)

;; Query buffer state
(tbuf/temporal-buffer-info :echo)

;; List all active buffers
(tbuf/buffer-names)
```

### trajectory integration

`temporal-buffer-set!` is a trajectory-compatible setter that lets conductor
arcs and trajectory curves drive buffer parameters live:

```clojure
;; Drive rate from a trajectory curve over 16 bars
(tbuf/temporal-buffer-set! :echo :rate
  (trajectory :smooth-step {:bars 16 :range [0.5 2.0]}))
```

---

## 16. Threshold Extractor

The Threshold Extractor watches a continuously varying value and fires note
or CC events whenever the value crosses a boundary. Rhythm emerges from the
*motion* of the source curve rather than from a pattern or random process.

Inspired by the MakeNoise GTE (Gestural Time Extractor), but operating in the
note-event domain with scale awareness and harmony integration.

### Core concept

A threshold comb of N-1 evenly-spaced boundaries divides the `[0.0, 1.0]`
source range into N channels. Crossing a boundary fires an `:on-cross` event.
The crossing rate is the rhythmic density; the current channel is the pitch
or routing target.

```
Source:   0.0 ─────────────────────────────── 1.0
               |      |      |      |      |
Channels: ch1    ch2    ch3    ch4    ch5    ch6
```

### Quick start

```clojure
(require '[nous.extractor :as ext])

;; A phasor cycling every 4 beats crosses 7 thresholds per cycle
(def my-phasor (make-phasor {:cycle-beats 4}))

(ext/defthreshold-extractor :rhythm
  {:source-fn  (fn [beat] (my-phasor beat))
   :channels   8
   :on-cross   {:type :note :pitch 60 :velocity 80 :duration 0.1
                :midi-channel 1}})

;; Stop it
(ext/extractor-stop! :rhythm)
```

### Source function

Any function `f(beat) -> [0.0, 1.0]` can serve as a source:

```clojure
;; Trajectory curve as source
(ext/defthreshold-extractor :tension-rhythm
  {:source-fn  (fn [b] ((trajectory :smooth-step {:bars 32}) b))
   :channels   8
   :on-cross   {:type :note :pitch 60 :velocity 80 :duration 0.05}})

;; Sine LFO
(ext/defthreshold-extractor :lfo-rhythm
  {:source-fn  (fn [b] (/ (inc (Math/sin (* 2 Math/PI (/ b 4.0)))) 2.0))
   :channels   4
   :on-cross   {:type :cc :cc 48 :value 127 :midi-channel 1}})
```

### Space parameter

`space` controls what fraction of `[0.0, 1.0]` the comb spans.
`space-center` sets the midpoint (default 0.5).

```clojure
;; Tight window: hypersensitive to small gestures in the center
(ext/defthreshold-extractor :sensitive
  {:source-fn  my-source
   :channels   8
   :space      0.3   ; comb spans 30% of the range
   :space-center 0.5
   :on-cross   {:type :note :pitch 60}})

;; Adjust space live
(ext/extractor-set! :sensitive :space 0.6)
(ext/extractor-set! :sensitive :space-center 0.7)
```

### Direction filter

```clojure
;; Only fire on rising crossings (source moving up)
(ext/defthreshold-extractor :rising-only
  {:source-fn  my-source
   :channels   4
   :direction  :rising    ; :both (default), :rising, :falling
   :on-cross   {:type :note :pitch 60}})
```

### Hysteresis

Hysteresis prevents rapid re-triggering when the source hovers near a
threshold boundary. The dead-zone is `hysteresis` x the inter-threshold spacing.

```clojure
;; Default hysteresis=0.01 (1% of spacing) — usually fine
;; Increase for a jittery source
(ext/defthreshold-extractor :noisy
  {:source-fn  noisy-fn
   :channels   8
   :hysteresis 0.05   ; 5% dead-zone
   :on-cross   {:type :note :pitch 60}})
```

### Arbitrary event dispatch

`:type :fn` gives full control over the output:

```clojure
(ext/defthreshold-extractor :custom
  {:source-fn my-source
   :channels  8
   :on-cross  {:type :fn
               :f    (fn [ch prev-ch direction beat]
                       ;; ch          -- new channel (1..N)
                       ;; prev-ch     -- prior channel
                       ;; direction   -- :rising or :falling
                       ;; beat        -- beat of crossing
                       (device/device-send! :boss/dd-500
                                           [:engine-a :feedback]
                                           (* ch 16)))}})
```

### Live mutation

All parameters can be changed on a running extractor:

```clojure
;; Replace source function
(ext/extractor-set! :rhythm :source-fn new-source-fn)

;; Replace on-cross config
(ext/extractor-set! :rhythm :on-cross {:type :note :pitch 64})

;; Query current state
(ext/extractor-status :rhythm)
;; => {:source-fn #fn :channels 8 :thresholds [...] :running? true ...}

;; Stop all extractors (called automatically by stop!)
(ext/extractor-stop-all!)
```

---

## 17. Ensemble Harmony

`nous.ensemble` provides a shared harmonic context derived from what the
music is actually playing, rather than what the programmer has declared in
advance. A background *harmony ear* watches a named Temporal Buffer, analyzes
its note content on a phrase boundary, and publishes an `ImprovisationContext`
map to `*harmony-ctx*` so that all live loops can read it.

### ImprovisationContext

The context is a plain Clojure map, inspectable and composable at the REPL:

```clojure
{:harmony/key       (scale/scale :D 3 :dorian)  ; detected or pinned Scale
 :harmony/chord     {:root :A :quality :min7
                     :roman :V :tension 0.65}    ; chord->roman result
 :harmony/tension   0.65   ; float [0.0, 1.0] — harmonic tension level
 :harmony/mode-conf 0.82   ; key-detection confidence (Krumhansl-Schmuckler)
 :harmony/ks-score  0.91   ; raw K-S Pearson correlation
 :harmony/pcs       {2 4, 9 3, 0 2}  ; pitch-class distribution in window
 :ensemble/register :mid   ; :low | :mid | :high — tessiture of the window
 :ensemble/density  0.4}   ; note events per beat in the active zone window
```

When `*harmony-ctx*` holds an `ImprovisationContext`, all DSL functions that
previously required a plain Scale record (`root`, `fifth`, `scale-degree`,
`in-key?`) still work — they extract `:harmony/key` automatically. Existing
loops using `:harmony (scale/scale ...)` are unaffected.

### Quick start

```clojure
(require '[nous.ensemble :as ensemble]
         '[nous.temporal-buffer :refer [deftemporal-buffer
                                          temporal-buffer-send!]])

;; 1. A Temporal Buffer accumulates what you play
(deftemporal-buffer :main {:active-zone :z3})  ; 8-beat window

;; 2. Start the ear — opt-in; nothing runs automatically
(ensemble/start-harmony-ear! :main)

;; 3. Live loops now see an ImprovisationContext in *harmony-ctx*
(deflive-loop :melody {}
  (play! (scale-degree (rand-int 7)) 1/4)  ; scale-degree uses detected key
  (sleep! 1/4))
```

### Manual snapshot

`analyze-buffer` performs a single analysis without starting the background
loop. Useful for one-shot inspection or for driving your own scheduling logic:

```clojure
(ensemble/analyze-buffer :main)
;; => {:harmony/key #Scale{...} :harmony/tension 0.3 :ensemble/density 1.25 ...}

;; With a key hint — skip detection, derive chord/tension on top of the hint:
(ensemble/analyze-buffer :main :key-scale (scale/scale :D 3 :dorian))
```

Key detection requires at least 3 distinct pitch classes in the window. Below
that threshold `:harmony/key`, `:harmony/chord`, and `:harmony/tension` are
omitted; the rest of the map is still populated.

### Ear options

```clojure
;; Phrase-length cadence (default 4 beats):
(ensemble/start-harmony-ear! :main :cadence 8)

;; Pin a key — skip auto-detection, still derive chord and tension:
(ensemble/start-harmony-ear! :main
  :key-scale (scale/scale :D 3 :dorian))

;; Transform the context before publishing (ctx → ctx):
(ensemble/start-harmony-ear! :main
  :transform (fn [ctx]
    ;; Fall back to a known key when confidence is low
    (if (< (:harmony/mode-conf ctx 0.0) 0.6)
      (assoc ctx :harmony/key (scale/scale :G 4 :major))
      ctx)))

;; Side-effect hook — called after each publish:
(ensemble/start-harmony-ear! :main
  :on-ctx (fn [ctx]
    (println "Key:" (get-in ctx [:harmony/key :root])
             "Tension:" (:harmony/tension ctx))))

;; Compose transforms with comp:
(ensemble/start-harmony-ear! :main
  :transform (comp pin-key-when-unsure add-session-data))

;; Stop the ear:
(ensemble/stop-harmony-ear!)
```

The ear registers as the live loop `:harmony-ear` and participates fully in
the loop lifecycle — `stop-loop!`, hot-swap on re-eval, and Link phase sync
all apply. `start!` must have been called first.

### Reading context in loops

```clojure
(deflive-loop :context-aware {}
  (let [ctx  loop-ns/*harmony-ctx*   ; may be Scale or ImprovisationContext
        t    (:harmony/tension ctx 0.0)
        reg  (:ensemble/register ctx :mid)]
    ;; Play a longer note when tension is high
    (play! (root) (if (> t 0.7) 1/2 1/4))
    ;; Drop an octave if the ensemble is playing high
    (when (= reg :high)
      (play! (- (pitch/pitch->midi (root)) 12) 1/4)))
  (sleep! 1/2))
```

### Empty buffer behaviour

When the Temporal Buffer has no events in the active zone window (e.g. during
a rest phrase), the ear retains the previous context unchanged. This means the
key and tension are never forgotten mid-performance due to silence. On the very
first call before any events have arrived, `analyze-buffer` returns nil.

---

## 18. ITexture and Spectral Analysis

`nous.texture` provides `ITexture` — a unified protocol for any device or
subsystem whose state can be set, faded, frozen, and thawed. `nous.spectral`
implements `ITexture` as a Spectral Analysis Model (SAM) that continuously
derives harmonic-texture characteristics from a live Temporal Buffer.

### ITexture protocol

```clojure
(defprotocol ITexture
  (freeze!       [t]             "Arrest analysis/output; hold current state.")
  (thaw!         [t]             "Resume analysis/output.")
  (frozen?       [t]             "True when frozen.")
  (texture-state [t]             "Read the current params map.")
  (texture-set!  [t params]      "Merge params immediately.")
  (texture-fade! [t target beats] "Fade to target over beats."))
```

Any hardware effects unit can implement `ITexture` and register with the
texture registry, which then becomes the target of `texture-transition!`.

### Shadow state (hardware devices)

Hardware devices have no readback — their parameters are write-only. The shadow
state registry tracks the last-sent values so you can query them without
re-reading hardware:

```clojure
(require '[nous.texture :as tx])

;; Declare a shadow node for a hardware device
(tx/shadow-init! :nightsky {:reverb/size 0.5 :reverb/mix 0.7})

;; Update it as you send CCs
(tx/shadow-update! :nightsky {:reverb/size 0.8})

;; Read back the shadow
(tx/shadow-get :nightsky)   ; => {:reverb/size 0.8 :reverb/mix 0.7}
```

### Named texture registry

```clojure
;; Register any ITexture implementation
(tx/deftexture! :reverb my-reverb-device)
(tx/deftexture! :delay  my-delay-device)

;; Inspect
(tx/texture-names)           ; => #{:reverb :delay}
(tx/get-texture :reverb)     ; => the registered device
```

### texture-transition!

Drive multiple devices at once from a single ops map. The same ops map shape
is used by `apply-texture-routing!` in the ensemble improv agent:

```clojure
;; Immediate set (no :beats) or fade (with :beats)
(tx/texture-transition!
  {:reverb {:target {:reverb/size 0.9 :reverb/mix 0.8} :beats 8}
   :delay  {:target {:delay/feedback 0.4}}})
```

### Spectral Analysis Model

`SpectralState` adds three texture fields derived from pitch content:

| Field | Range | Meaning |
|-------|-------|---------|
| `:spectral/density` | 0–1 | Distinct pitch classes ÷ 12 |
| `:spectral/centroid` | 0–1 | Mean MIDI pitch ÷ 127 |
| `:spectral/blur` | 0–1 | 0 = live analysis, 1 = fully frozen |

```clojure
(require '[nous.spectral :as spectral])

;; Start the SAM loop watching a Temporal Buffer
(def sam (spectral/start-spectral! :main))

;; Read the current spectral state
(spectral/spectral-ctx sam)
;; => {:spectral/density 0.58 :spectral/centroid 0.52 :spectral/blur 0.0}

;; Freeze — holds current state; analysis stops; on-ctx callbacks still fire
(tx/freeze! sam)
;; => blur becomes 1.0

;; Thaw — resumes analysis; blur → 0.0 on next tick
(tx/thaw! sam)

;; Register as a named texture and fade blur
(tx/deftexture! :spectral sam)
(tx/texture-transition! {:spectral {:target {:spectral/blur 0.5} :beats 8}})

;; Stop the SAM loop
(spectral/stop-spectral! sam)
```

### Using spectral context in loops

`spectral-ctx` returns a map fragment compatible with the ImprovisationContext,
so it can be merged directly:

```clojure
(deflive-loop :texture-aware {}
  (let [spec (spectral/spectral-ctx sam)
        harm loop-ns/*harmony-ctx*
        ctx  (merge harm spec)]
    ;; Drive note density from spectral density
    (when (< (Math/random) (:spectral/density ctx 0.3))
      (play! (root) 1/4))
    ;; Fade the reverb freeze based on blur
    (tx/texture-transition!
      {:reverb {:target {:hold? (> (:spectral/blur ctx 0) 0.5)}}}))
  (sleep! 1/4))
```

---

## 19. Ensemble Improvisation Agent

`nous.ensemble-improv` combines the harmony ear, the spectral SAM, and the
texture routing system into a single generative loop — the *improv agent* —
that responds to the live musical context and drives both MIDI output and
effects-device state from the same snapshot.

### Quick start

```clojure
(require '[nous.ensemble-improv :as improv]
         '[nous.spectral        :as spectral])

;; Start the harmony ear first
(ensemble/start-harmony-ear! :main)

;; Optionally start the SAM for spectral context
(def sam (spectral/start-spectral! :main))

;; Start the improv agent
(improv/start-improv! :main :sam sam)

;; Stop
(improv/stop-improv!)
```

### Note generation strategy

Pitches are chosen from the scale in `:harmony/key`, shaped by context:

| `:harmony/tension` | Degree pool |
|--------------------|-------------|
| < 0.35 | Root triad only — `[0 2 4]` |
| < 0.65 | Diatonic pentatonic — `[0 1 2 3 4]` |
| ≥ 0.65 | Full scale — all degrees |

`:ensemble/register` (:low/:mid/:high) shifts the pool by ±1 octave.
`:ensemble/density` × profile `:gate` scales play probability; capped at 0.95
so silence always remains possible.

When `:harmony/key` is absent (insufficient pitch diversity in the buffer) the
agent rests silently.

### Profile options

```clojure
(improv/start-improv! :main
  :profile {:step-beats   1/4   ; cadence between note attempts
            :gate         0.65  ; base play probability [0,1]
            :dur-beats    1/4   ; note duration
            :velocity     80    ; base MIDI velocity
            :vel-variance 16    ; ± random velocity spread
            :channel      1})   ; MIDI output channel
```

### Texture routing

Map ImprovisationContext fields to ITexture devices. Each `:params-fn` is
called with the current context and returns a params map for the target device:

```clojure
(improv/start-improv! :main
  :sam sam
  :routing
  {:reverb {:params-fn (fn [ctx]
                         {:hold? (> (:harmony/tension ctx 0) 0.8)})
             :beats 4}
   :delay  {:params-fn (fn [ctx]
                         {:active-zone (if (< (:ensemble/density ctx 0) 0.5)
                                         :z3 :z4)})}})
```

### Hot update

Profile and routing changes take effect on the next tick without restarting
the loop:

```clojure
(improv/update-improv! {:profile {:gate 0.9 :vel-variance 30}})
(improv/update-improv! {:routing {:reverb {:params-fn f :beats 4}}})
```

### Conductor integration

`improv-gesture-fn` returns an `:on-start`-compatible no-arg function for use
in conductor sections. At the section boundary it merges a profile override,
optionally replaces routing, and fires an immediate texture transition:

```clojure
(defconductor! :my-arc
  [{:name :buildup :bars 32 :gesture :buildup
    :on-start (improv/improv-gesture-fn
                {:profile {:gate 0.95 :vel-variance 30}
                 :texture {:reverb {:target {:hold? false} :beats 8}}})}
   {:name :wind-down :bars 8 :gesture :wind-down
    :on-start (improv/improv-gesture-fn
                {:profile {:gate 0.4 :vel-variance 10}
                 :texture {:reverb {:target {:hold? true}}}})}])

(fire! :my-arc)
```

---

## 20. Peer Discovery

`nous.peer` enables a multi-host nous studio: each node broadcasts a UDP
beacon so peers can discover it, and `mount-peer!` starts a background poll
that mounts the peer's harmony and spectral context into the local ctrl tree.

### Node identity

```clojure
(require '[nous.peer :as peer])

;; Call before start-discovery!
(peer/set-node-profile!
  {:node-id    :mac-mini   ; keyword — unique per node
   :role       :main
   :http-port  7177        ; must match (server/start-server! :port 7177)
   :nrepl-port 7888})
```

### Discovery

```clojure
;; Start broadcasting + listening (multicast 239.255.43.99:7743, 5 s interval)
(peer/start-discovery!)

;; After a few seconds
(peer/peers)
;; => {:ubuntu {:node-id :ubuntu :host "192.168.1.10" :http-port 7177 ...}
;;     :rpi    {:node-id :rpi    :host "10.0.0.5"     :http-port 7177 ...}}

;; Stop
(peer/stop-discovery!)
```

Peers expire automatically after 15 s without a beacon.

### Mounting peer context

Once a peer is discovered and its HTTP server is reachable, `mount-peer!`
polls two ctrl-tree paths and stores the results locally:

```clojure
;; Prerequisites: server must be running on the peer
;; (server/start-server!) on the remote host

(peer/mount-peer! :ubuntu)

;; Polled every 2 s from the peer:
;;   GET /ctrl/ensemble/harmony-ctx → [:peers :ubuntu :ensemble :ctx]
;;   GET /ctrl/spectral/state       → [:peers :ubuntu :spectral :ctx]

;; Read the mounted harmony context
(peer/peer-harmony-ctx :ubuntu)
;; => {:harmony/root "D" :harmony/octave 3
;;     :harmony/intervals [2 1 2 2 2 1 2]
;;     :harmony/tension 0.65 :ensemble/density 0.4 ...}

;; Reconstruct a live Scale record from the serialized form
(peer/serial->scale (peer/peer-harmony-ctx :ubuntu))
;; => #Scale{:root D3 :intervals [2 1 2 2 2 1 2]}

;; Spectral state
(peer/peer-spectral-ctx :ubuntu)
;; => {:spectral/density 0.58 :spectral/centroid 0.52 :spectral/blur 0.0}

;; Stop polling
(peer/unmount-peer! :ubuntu)
```

### Using peer context in loops

```clojure
;; Follow the remote node's key in a local loop
(deflive-loop :follower {}
  (when-let [remote-ctx (peer/peer-harmony-ctx :ubuntu)]
    (let [scale (peer/serial->scale remote-ctx)]
      (binding [loop-ns/*harmony-ctx* {:harmony/key scale}]
        (play! (scale-degree (rand-int 7)) 1/4))))
  (sleep! 1/4))
```

### Serialization format

Scale and Pitch records cannot round-trip through JSON. The harmony ear
publishes a simplified primitive map at `[:ensemble :harmony-ctx]`:

```clojure
{:harmony/root      "D"           ; string step name
 :harmony/octave    3             ; integer
 :harmony/intervals [2 1 2 2 2 1 2]
 :harmony/tension   0.65
 :harmony/mode-conf 0.82
 :ensemble/density  0.4
 :ensemble/register "mid"}
```

`peer/serial->scale` reconstructs the Scale record from these fields.
`peer/ctx->serial` performs the reverse conversion (useful for testing).

### Backend registry (Topology Layer 3)

Each node advertises which synthesis backends it is running.  The registry is
updated automatically when `connect-sc!` / `disconnect-sc!` is called; you can
also register arbitrary backends manually:

```clojure
(require '[nous.peer :as peer])

;; Register a backend (done automatically by connect-sc!)
(peer/register-backend! :sc {:host "localhost" :sc-port 57110 :lang-port 57120})
(peer/register-backend! :surge-xt {:host "localhost" :port 9000})

;; Deregister
(peer/deregister-backend! :surge-xt)

;; Local active backends
(peer/active-backends)
;; => {:sc {:host "localhost" :sc-port 57110 :lang-port 57120}}

;; Backends advertised by a discovered peer
(peer/peer-backends :ubuntu)
;; => {:sc {:host "192.168.1.10" :sc-port 57110 :lang-port 57120}}
```

The `:backends` map is included in the UDP discovery beacon so all peers can
see each other's synthesis capabilities without polling.

### Session profile

Publish a rich profile for peer discovery — useful for UI panels or scripted
multi-node sessions:

```clojure
(peer/publish-session-profile!
  {:session/name   "main-rig"
   :session/bpm    120
   :session/loops  [:kick :bass :pad]})
;; Stored at [:session :profile] in the ctrl tree — readable via HTTP
```

---

## 21. Note Transformers

`nous.transform` provides composable per-event processing that sits between
note generation and `play!`. Each transformer receives a note event map and
returns zero, one, or many `{:event map :delay-beats number}` results. An
empty result silently drops the event; multiple results produce multiple sounds.

### Quick start

```clojure
(require '[nous.transform :as xf])

;; Single transformer
(def my-echo (xf/echo {:repeats 3 :decay 0.7 :delay-beats 1/4}))

(deflive-loop :melody {}
  (when-let [note (pick-note)]
    (xf/play-transformed! my-echo note))
  (sleep! 1))
```

### Composing transformers

`compose-xf` chains transformers left to right. The output events from each
feed into the next; delay-beats accumulate so an echo of a strum replays the
full strummed pattern N times:

```clojure
(def my-chain
  (xf/compose-xf
    (xf/velocity-curve {:breakpoints [[0 0] [64 90] [127 127]]})
    (xf/harmonize {:intervals [7 12]})
    (xf/echo {:repeats 2 :decay 0.6 :delay-beats 1/4})))

(deflive-loop :melody {}
  (xf/play-transformed! my-chain (pick-note))
  (sleep! 1))
```

### Built-in transformers

#### `velocity-curve` — piecewise linear velocity mapping

```clojure
;; Raise mid-range velocities, leave extremes
(xf/velocity-curve {:breakpoints [[0 0] [64 90] [127 127]]})
```

#### `quantize` — snap pitch to scale

```clojure
;; Snap to C major; drop notes more than 1 semitone away
(xf/quantize {:scale  (make-scale :C 4 :major)
              :forgiveness 1})
```

When `:forgiveness` is set and the pitch is farther than that many semitones
from any scale degree, the event is dropped (not clamped).

#### `harmonize` — add interval voices

```clojure
;; Perfect fifth + octave above; snaps to *harmony-ctx* scale by default
(xf/harmonize {:intervals [7 12]})

;; Explicit scale, raw chromatic (no snapping)
(xf/harmonize {:intervals [4 7]
               :key       (make-scale :D 3 :dorian)
               :snap?     false})

;; Harmony only, omit the original
(xf/harmonize {:intervals [7] :include-original? false})
```

When `:snap?` is true (default), harmony notes are snapped to the nearest
scale degree. The scale is read from `*harmony-ctx*` unless `:key` is
provided. `harmonize` is the primary *ensemble follower* mechanism.

#### `echo` — decay repeat chain

```clojure
;; 3 echoes, each 70% of the previous velocity, 1/4 beat apart
(xf/echo {:repeats 3 :decay 0.7 :delay-beats 1/4})

;; Ascending echo (pitch rises each repeat)
(xf/echo {:repeats 4 :decay 0.8 :delay-beats 1/8 :pitch-step 2})
```

#### `note-repeat` — rhythmic copies

```clojure
;; 4 evenly spaced copies within 1 beat
(xf/note-repeat {:n-copies 4 :window-beats 1})

;; Euclidean distribution — 3 pulses spread across a 2-beat window
(xf/note-repeat {:n-copies 3 :window-beats 2 :euclidean? true})
```

Euclidean mode uses the Bjorklund algorithm to distribute copies as evenly as
possible, producing the characteristic feel of Euclidean rhythms.

#### `strum` — spread chord tones

Input event should carry `:chord/notes` — a vector of MIDI values.

```clojure
;; Strum a chord vector upward, one note every 1/32 beat
(xf/play-transformed!
  (xf/strum {:direction :up :rate-beats 1/32})
  {:chord/notes [60 64 67 72] :dur/beats 1 :mod/velocity 90})

;; Downward strum
(xf/strum {:direction :down :rate-beats 1/32})

;; Random order
(xf/strum {:direction :random :rate-beats 1/16})
```

Strum integrates naturally with `compose-xf` — strum a chord, then echo the
full strummed pattern: `(compose-xf (strum ...) (echo ...))`.

#### `dribble` — bouncing-ball timing

Repeats the event with exponentially shrinking inter-onset gaps, like a ball
bouncing to rest:

```clojure
(xf/dribble {:bounces       5
             :initial-delay 1/2   ; gap before first bounce
             :restitution   0.65}) ; each gap is 65% of the previous
```

#### `latch` — toggle or velocity gate

```clojure
;; Every other note passes (toggle open/closed)
(xf/latch {:mode :toggle})

;; Only notes with velocity ≥ 80 pass
(xf/latch {:mode :velocity :threshold 80})
```

Toggle starts open by default; pass `:initial false` to start closed.

### ITransformer protocol

You can define custom transformers:

```clojure
(defrecord MyTransformer [amount]
  xf/ITransformer
  (transform [_ event]
    ;; Return a seq of {:event map :delay-beats num}
    [{:event (update event :mod/velocity + amount) :delay-beats 0}]))
```

---

## 22. Score Corpus (`nous.m21`)

nous integrates with [Music21](https://web.mit.edu/music21/) to access its
entire score corpus — not just Bach chorales but Renaissance polyphony
(Palestrina, Josquin, Lassus), ABC tunebooks, MusicXML archives, and any
file the Music21 corpus index can locate. Requires Python 3.x:

```bash
pip install music21
```

The first call to any `m21/` function starts a persistent Python server. It
stays alive for the JVM session. Results are cached in memory (two-level:
server-side and disk at `~/.local/share/nous/corpora/m21/`).

### Bach chorales (BWV convenience functions)

```clojure
(require '[nous.m21 :as m21])

;; List available BWV numbers
(m21/list-chorales)   ; => [1 2 3 ... 371 ...]

;; Play BWV 371 — all voices on one channel
(m21/play-chorale! 371)

;; Play with separate MIDI channels per voice (SATB)
(m21/play-chorale-parts! 371)
(m21/play-chorale-parts! 371 {:dur-mult 0.8
                               :channels {:soprano 1 :alto 2 :tenor 3 :bass 4}})

;; Load chord vectors for analysis
(def chords (m21/load-chorale 371))
;; => [{:pitches [48 55 62 67] :dur/beats 1.0} ...]
```

### Searching the full corpus

`search-corpus` queries the full Music21 corpus index. Results are file paths
within the corpus that can be passed to `load-work`.

```clojure
;; Find all Palestrina works
(m21/search-corpus {:composer "palestrina"})
;; => ["palestrina/Palestrina_Missa_Brevis_Kyrie.xml"
;;     "palestrina/Palestrina_Missa_Brevis_Gloria.xml" ...]

;; Find by file extension
(m21/search-corpus {:extension "abc"})
;; => ["abc/..." ...]

;; Find by title keyword (case-insensitive)
(m21/search-corpus {:title "kyrie"})

;; Combine predicates (all must match)
(m21/search-corpus {:composer "josquin" :extension "xml"})
```

### Loading any corpus work

`load-work` accepts any corpus path from `search-corpus` and returns the
score in one of three modes:

```clojure
;; :parts — per-voice step vectors (default)
(def score (m21/load-work "palestrina/Palestrina_Missa_Brevis_Kyrie.xml"
                           :mode :parts))
;; => {:soprano [{:pitch/midi 64 :dur/beats 1.0} ...]
;;     :alto    [...] :tenor [...] :bass [...]}

;; :chords — vertical slices, like load-chorale
(def chords (m21/load-work "josquin/josquin_mass.xml" :mode :chords))
;; => [{:pitches [52 57 62 67] :dur/beats 1.0} ...]

;; :intervals — interval vectors between adjacent voices (analysis use)
(def ivs (m21/load-work "palestrina/..." :mode :intervals))
;; => [{:soprano-alto 5 :alto-tenor 3 :tenor-bass 7} ...]
```

### Playing a Palestrina Kyrie live

```clojure
(require '[nous.m21 :as m21]
         '[nous.loop :refer [deflive-loop sleep!]]
         '[nous.dsl :refer [play!]])

(def kyrie (m21/load-work "palestrina/Palestrina_Missa_Brevis_Kyrie.xml"
                           :mode :parts))

(let [soprano (cycle (:soprano kyrie))
      soprano-state (atom soprano)]
  (deflive-loop :kyrie-soprano {:midi-channel 1}
    (let [step (first @soprano-state)]
      (reset! soprano-state (rest @soprano-state))
      (play! step)
      (sleep! (:dur/beats step)))))
```

### Server lifecycle

```clojure
(m21/stop-server!)   ; stop the Python bridge; clears the in-memory cache
```

The disk cache persists across sessions; delete
`~/.local/share/nous/corpora/m21/` to force a re-parse.

---

## 22a. Freesound Sample Library (`nous.freesound`)

`nous.freesound` is an API v2 client for [Freesound.org](https://freesound.org/)
— a Creative Commons audio sample database. It integrates with `nous.sample`
so that downloaded samples are immediately available for granular playback and
live-loop dispatch.

Requires a free API key (create at freesound.org → API → Apply for key):

```bash
export FREESOUND_API_KEY=<your-key>
```

### Searching and downloading

```clojure
(require '[nous.freesound :as fs])

;; Search by keyword — returns a vector of result maps
(fs/search-freesound "tabla drone")
;; => [{:id 12345 :name "Tabla drone C2.wav" :duration 4.2 :license "CC0" ...} ...]

;; Download and register with the sample buffer registry
(fs/fetch-and-load! 12345)
;; => :freesound/12345   (key for use in play! step maps)

;; Play immediately
(play! {:sample :freesound/12345 :rate 1.0 :amp 0.7})
```

### Essentials catalog

The essentials catalog is a curated offline-ready subset of Freesound content.
`load-essentials!` downloads the catalog once; subsequent sessions use the disk
cache at `~/.local/share/nous/corpora/freesound/`.

```clojure
;; Prime the catalog (downloads on first call, ~200 MB)
(fs/load-essentials!)

;; Inspect the catalog
(fs/essentials)
;; => [{:id 12345 :name "..." :tags ["drone" "tabla"] :cached? true} ...]

;; Use a specific sample from the catalog by name pattern
(fs/fetch-and-load! (-> (fs/essentials) (filter #(re-find #"tabla" (:name %))) first :id))
```

### Curating the essentials

`curate-essentials!` rebuilds the catalog from a query list. Use this to
customise the offline library for a particular project or instrument focus:

```clojure
(fs/curate-essentials!
  [{:query "tabla drone" :count 10}
   {:query "sitar harmonics" :count 5}
   {:query "tanpura" :count 5}])
```

---

## 23. SuperCollider Integration

nous talks to SuperCollider on two ports:

- **sclang** (default 57120) — receives sclang code strings for SynthDef compilation
- **scsynth** (default 57110) — receives OSC node control messages

### Connecting

```clojure
(require '[nous.user :refer :all])
(session!)

(connect-sc!)                           ; localhost, defaults
(connect-sc! :host "192.168.1.5")       ; remote SC
(sc-connected?)                         ; => true
```

### SynthDef lifecycle

nous ships built-in synths (`:beep`, `:sine`, `:saw-pad`, `:blade`, `:prophet`,
`:supersaw`, `:dull-bell`, `:fm`, `:tb303`, `:perc`). Load them into a running SC
server with `send-synthdef!`:

```clojure
(send-synthdef! :blade)        ; compile + load one synth
(send-all-synthdefs!)          ; load every registered synth
(ensure-synthdef! :blade)      ; lazy — only sends if not already loaded this session
```

Inspect the generated sclang:

```clojure
(synthdef-str :prophet)
;=> "SynthDef(\\prophet, {\n  |freq=440.0, ...|\n  ...\n}).add;"
```

### Playing notes via SC

Once connected, `play!` routes any step map containing `:synth` to SC automatically:

```clojure
;; Route to SC — :synth key triggers the SC backend
(play! {:synth :blade :pitch/midi 60 :dur/beats 1/2})
(play! {:synth :sine  :pitch/midi 69 :pitch/midi 69 :dur/beats 1})

;; Inside a live loop
(deflive-loop :sc-bass {}
  (play! {:synth :tb303 :pitch/midi 36 :dur/beats 1/2})
  (sleep! 1))
```

Step maps without `:synth` continue to route through MIDI as before.

### Manual node control

```clojure
(sc-synth! :blade {:freq 440 :amp 0.6})   ; => node-id (e.g. 1001)
(set-param! 1001 :cutoff 60)              ; live update
(free-synth! 1001)                        ; gate=0 — envelope tail
(kill-synth! 1001)                        ; immediate removal
```

### Trajectory control

`apply-trajectory!` drives a live SC node parameter continuously over time using
nous's temporal vocabulary:

```clojure
;; 3-arity: drive node-id param with an ITemporalValue
(let [node (sc-synth! :blade {:freq 220 :amp 0.5})]
  (apply-trajectory! node :cutoff
    (trajectory :from 0.1 :to 0.9 :beats 16 :curve :s-curve :start (now))))

;; 2-arity: general form — any setter function
(apply-trajectory! #(println "val:" %)
  (trajectory :from 0.0 :to 1.0 :beats 8 :start (now)))
```

Both forms return a cancel function. Call it to stop the trajectory early.

`ramp-param!` is a simpler alternative for long linear ramps (drone fades,
filter arcs) where a future loop is more predictable than trajectory polling:

```clojure
;; Fade a drone amp in over 320 beats (~5 min at 100 BPM):
(ramp-param! verb-node :amp 0.0 0.18 320)

;; Close a filter over 80 bars; keep a handle to abort early:
(def cancel! (ramp-param! filter-node :cutoff 4000 200 80))
(cancel!)   ; stop mid-ramp

;; Override BPM or step count:
(ramp-param! node :room 0.2 0.8 64 :steps 80 :bpm 72)
```

Returns a zero-argument cancel function. Default is 40 interpolation steps
spaced evenly across the beat duration at the current BPM.

### SC process management

For live performance, `start-sc!` / `stop-sc!` / `sc-restart!` manage the
sclang subprocess from the REPL, enabling full recovery from an audio device
drop without a terminal window.

**First launch:**
```clojure
(start-sc! :binary "/opt/homebrew/Caskroom/supercollider/3.14.1/SuperCollider.app/Contents/MacOS/sclang"
           :script "script/sc-headless.scd")
;; Blocks until /sc-lang-ready OSC arrives (default 30 s timeout), then calls
;; connect-sc! automatically.
```

**Recovery after audio device reconnect:**
```clojure
(sc-restart!)
;; Sequence: stop process → relaunch → wait for boot → resend all SynthDefs
;; => [sc] restarted — N SynthDef(s) restored
```

**Supervisor integration** — wire `sc-restart!` as the auto-restart function
so the watchdog handles recovery without any REPL intervention:
```clojure
(supervisor/register-sc!)       ; defaults :restart-fn to sc/sc-restart!
(supervisor/register-kairos!)
(supervisor/start-watchdog!)
```

`start-sc!` options: `:binary` (required), `:script` (default
`"script/sc-headless.scd"`), `:timeout-ms` (default 30000).

`sc-restart!` throws if `start-sc!` was never called — it needs the stored
binary and script path. For externally managed SC processes, call
`connect-sc!` directly and pass a custom `:restart-fn` to `register-sc!`.

### Defining custom synths

```clojure
(defsynth! :my-pad
  {:args  {:freq 440 :amp 0.5 :attack 0.5 :release 2.0}
   :graph [:out 0 [:pan2 [:* [:env-gen [:adsr :attack 0.1 0.8 :release] :gate :amp]
                              [:sin-osc :freq]] 0.0]]})

(send-synthdef! :my-pad)
(sc-synth! :my-pad {:freq 330 :amp 0.3})
```

The graph DSL supports: oscillators (`:sin-osc`, `:saw`, `:pulse`, `:var-saw`, ...),
filters (`:lpf`, `:hpf`, `:rlpf`, `:bpf`, `:moog-ff`), envelopes (`:adsr`, `:perc`,
`:asr`), effects (`:free-verb`, `:comb-l`, `:allpass-n`, `:delay-l`),
pan/mix (`:pan2`, `:mix`), and math operators (`:*`, `:+`, `:-`, `:/`).

---

## 24. Spatial Field

`nous.spatial-field` models bouncing particles inside an N-sided polygon. Wall
collisions generate MIDI events (`:generation` mode) or sweep modulation parameters
(`:modulation` mode). The geometry is continuous — non-integer N values like `4.7`
produce asymmetric rooms with irrational reflection angles, creating natural polyrhythm.

### Room geometry

```clojure
;; Integer N — regular polygon
(ngon-walls 6)          ; hexagon, 6 equal walls

;; Decimal N — asymmetric: ceil(N) walls, irregular angles
(ngon-walls 4.7)        ; 5 walls, polyrhythmic character

;; Custom wall behaviour per wall
(ngon-walls 4 :type :generate)                           ; all walls fire events
(ngon-walls 4 :wall-types [:reflect :generate :absorb :portal])
```

Each wall has `:a`, `:b` (endpoints), `:normal` (inward unit vector), and `:type`.

Wall types:
| Type | Behaviour |
|------|-----------|
| `:reflect` | Elastic bounce — `v' = v − 2(v·n)n` |
| `:generate` | Reflect and emit a MIDI event |
| `:absorb` | Kill particle velocity |
| `:portal` | Teleport particle to the opposite wall |

### Defining and running a field

```clojure
(defspatial-field! ::my-room
  {:mode      :generation   ; or :modulation
   :sides     5.0
   :ball-speed 1.5
   :particles  1
   :forces    {:gravity {:direction :down :strength 0.3}
               :damping 0.1}})

(start-field! ::my-room)
(field-state  ::my-room)   ; => :running
(stop-field!  ::my-room)
```

Configuration keys:
| Key | Default | Description |
|-----|---------|-------------|
| `:mode` | `:generation` | `:generation` or `:modulation` |
| `:sides` | `4.0` | N-gon sides (decimal allowed) |
| `:ball-speed` | `1.0` | Initial particle speed (room units/s) |
| `:particles` | `1` | Number of simultaneous particles |
| `:min-velocity` | `0.001` | Stop threshold for absorbed particles |
| `:forces` | `{}` | Map of `{:gravity {:direction kw :strength f} :damping f}` |

### Named presets

8 presets are registered at load time:

| Preset | Mode | Character |
|--------|------|-----------|
| `:ricochet` | `:generation` | Fast 6-sided room, frequent events |
| `:dribble` | `:generation` | Low gravity, floor-bounce feel |
| `:drift` | `:modulation` | Slow multi-particle sweep |
| `:orbit` | `:modulation` | Circular motion, low damping |
| `:pinball` | `:generation` | Chaotic 7-sided room with portals |
| `:gravity-well` | `:generation` | Strong downward pull, 4-sided room |
| `:portal-room` | `:generation` | Mixed reflect/portal boundary types |
| `:instrument-quad` | `:modulation` | Quad-gains spatial panning |

```clojure
(start-field! :ricochet)
(play-through-field! {:pitch/midi 60 :dur/beats 1/4} :ricochet)
```

### ITransformer integration

Spatial fields implement `ITransformer`. The triggering event passes through with
`delay-beats 0`; collision-derived events follow with computed delays.

```clojure
(start-field! ::my-room)

;; Direct transformer use
(def xf (field-transformer ::my-room))
(play-transformed! {:pitch/midi 60 :dur/beats 1/4} xf)

;; Convenience wrapper
(play-through-field! {:pitch/midi 60 :dur/beats 1/4} ::my-room)
```

### Quad-gains (VBAP spatial panning)

`quad-gains` maps a 2D position `[x y]` ∈ [0,1]² to four-channel gains
`[FL FR RL RR]` using constant-power decomposition.

```clojure
(quad-gains [0.5 0.5])   ; centre → equal gains
(quad-gains [0.0 0.5])   ; hard left
(quad-gains [0.5 1.0])   ; hard front
```

Sum of squares ≈ 1.0 across the entire field (constant-power law).

### Axis mappings

Map particle physics axes to synthesis parameters:

```clojure
;; In :modulation mode, map :x → :pan and :speed → :velocity
(defspatial-field! ::spatial-pan
  {:mode :modulation
   :sides 4.0
   :mappings {:x :pan :speed :velocity}})
```

Available axes: `:x`, `:y`, `:r` (radius from centre), `:θ` (angle),
`:vx`, `:vy`, `:speed`, `:age`.

---

## 25. Sample Player and Freesound

`nous.sample` manages SC audio buffers and provides playback, looping, and granular synthesis. `nous.freesound` adds fetch-on-use sample acquisition from the Freesound archive, with a bundled essentials catalog that mirrors the Sonic-Pi sample vocabulary.

### Loading samples

```clojure
;; Register a sample path (does not allocate SC buffer yet)
(defbuffer! ::kick "/samples/kick.wav")

;; Allocate an SC buffer and load from disk (idempotent by path)
(load-sample! ::kick)

;; Play once
(sample! ::kick)
(sample! ::kick :rate 0.5 :amp 0.6 :pan -0.3)

;; Looping playback
(def node (loop-sample! ::kick :rate 0.95))
(sc/free-synth! node)
```

`defbuffer!` is idempotent — multiple names that share the same file path reuse one SC buffer ID. `load-sample!` checks the path index before sending a second `/b_allocRead`.

### Granular synthesis

```clojure
(defbuffer! ::texture "/samples/texture.wav")
(load-sample! ::texture)

;; Instantiate the :granular-cloud patch with this buffer
(def cloud (granular-cloud! ::texture :density 10 :pos 0.4))

;; Live control
(sc/set-patch-param! cloud :density 20)
(sc/set-patch-param! cloud :pos 0.7)

;; Automate grain density over 16 beats
(apply-trajectory! (get-in cloud [:nodes :grains]) :density
  (trajectory :from 4 :to 32 :beats 16 :curve :s-curve :start (now)))

(sc/free-patch! cloud)
```

### play! integration

The `:sample` key routes directly to the SC buffer player inside a live loop:

```clojure
(deflive-loop :drums {}
  (play! {:sample ::kick :sample/rate 1.0 :mod/velocity 100})
  (sleep! 1))
```

### Freesound fetch-on-use

Set a Freesound API key (free account at freesound.org):

```clojure
(set-freesound-key! "your-api-key-here")
;; or: export FREESOUND_API_KEY=...
```

Fetch and immediately load a sample by Freesound ID:

```clojure
;; Download to ~/.cache/nous/samples/12345.wav, register as :my/kick
(fetch-and-load! 12345 :my/kick)
(sample! :my/kick)
```

Subsequent calls use the local cache — no network request after the first download.

### Essentials catalog

The bundled catalog (`resources/samples/essentials.edn`) mirrors the Sonic-Pi category vocabulary:

| Category | Contents |
|----------|---------|
| `:ambi/*` | Ambient textures, drones, atmospheric beds |
| `:bass/*` | Bass hits (DnB, drop, woodsy) |
| `:bd/*` | Bass drum / kick drum variants |
| `:elec/*` | Electronic hits, bells, bleeps |
| `:guit/*` | Guitar chords and slides |
| `:loop/*` | Breakbeat and percussion loops |
| `:perc/*` | Acoustic percussion one-shots |
| `:sn/*` | Snare drums |
| `:tabla/*` | Indian tabla voices |
| `:vinyl/*` | Record surface noise and scratch |

```clojure
;; Fetch all catalog entries that have a Freesound ID populated
(load-essentials!)

;; Then play by keyword:
(sample! :bd/fat)
(sample! :ambi/drone :rate 0.95 :amp 0.6)

;; Entries with nil IDs need curation first — this searches Freesound
;; for each missing entry and prints candidate IDs:
(curate-essentials!)
```

Fetched files are cached under `(dirs/user-cache-dir)/samples/`. API metadata is also cached so repeated calls to `freesound-info` don't hit the network.

### Searching Freesound

```clojure
;; Returns seq of {:id int :name str :license str :tags [...] :username str}
(search-freesound "acoustic kick drum"
                  {:filter "license:\"Creative Commons 0\""
                   :num-results 10
                   :sort "downloads_desc"})

;; Inspect a specific sound
(freesound-info 12345)
;=> {:id 12345 :name "kick_acoustic" :license "CC0" :download "https://..." ...}
```

---

## 26. SC Synthesis Showcase

This section documents the built-in SC synthesis voices and multi-voice patches added in v0.5.0–v0.6.0. All require `(connect-sc!)` and `(send-all-synthdefs!)`.

### Karplus-Strong plucked string (`:pluck`)

The `:pluck` synth implements Karplus-Strong synthesis via SC's `Pluck` UGen. White noise excitation recirculates through a delay line; the `:coef` parameter controls the reflection coefficient — the "material" of the string.

| `:coef` | Character |
|---------|-----------|
| `-1.0` | Very bright, bell-like (little damping) |
| `-0.5` | Metallic steel string |
| `0.0` | Neutral |
| `0.5` | Warm nylon (default) |
| `0.95` | Dark gut string, almost a thump |

```clojure
;; Warm nylon pluck at A3
(sc/sc-play! {:synth :pluck :freq 220 :amp 0.7 :decay 15 :coef 0.5})

;; apply-trajectory! on a persistent long-sustaining node
(def drone (sc/sc-synth! :pluck {:freq 82.4 :amp 0.5 :decay 120 :coef -0.95}))
(apply-trajectory! drone :coef
  (trajectory :from -0.95 :to 0.85 :beats 64 :curve :exp :start (now)))
```

See `examples/ks_string_demo.clj` for the full showcase including per-note arc sampling, dual-drone coef animation, and chord section with three simultaneous trajectories.

### Solar42-inspired drone synthesizer (`:s42` patch)

The `:s42` patch models a multi-oscillator drone synthesizer architecture with four drone voices, two VCO voices, two FM/AM/S&H voices, a dual-RLPF filter stage, and a reverb effects block.

```clojure
(def s42 (sc/instantiate-patch! :s42))

;; Tune drone voices to a chord
(sc/set-patch-param! s42 :drone1-freq 110)   ; A2
(sc/set-patch-param! s42 :drone2-freq 146.8) ; D3
(sc/set-patch-param! s42 :drone3-freq 165.0) ; E3
(sc/set-patch-param! s42 :drone4-freq 220.0) ; A3

;; Sweep the Polivoks-style filter open over 64 beats
(apply-trajectory! (get-in s42 [:nodes :filter]) :cutoff
  (trajectory :from 20 :to 100 :beats 64 :curve :s-curve :start (now)))

(sc/free-patch! s42)
```

Constituent synths (usable independently):

| Synth | Description |
|-------|-------------|
| `:s42-drone-voice` | 6-saw voice; per-oscillator gate (`:on1`–`:on6`) and tuning ratio (`:tune1`–`:tune6`) |
| `:s42-vco-voice` | VarSaw + PWM modulation + sub oscillator |
| `:s42-papa-voice` | FM + AM + Sample&Hold noise blend (`:fm-depth`, `:fm-ratio`, `:noise-mix`) |
| `:s42-filter` | Dual cascaded RLPF (24 dB/oct, Polivoks-inspired); reads from `:in-bus` |

Scale vocabulary matching common drone synthesizer keyboard modes:

```clojure
(scale :A 2 :double-harmonic)   ; "Gypsy" — same as :dune
(scale :A 2 :pelog)             ; Balinese gamelan approximation
(scale :A 2 :in-scale)          ; Japanese in
(scale :D 3 :phrygian-dominant) ; Flamenco / `:hijaz` (Arabian)
(scale :C 4 :whole-tone)        ; Whole-tone / symmetric
```

### SuperKarplus-inspired warpable KS (`:superkar-voice`, `:superkar` patch)

Extends Karplus-Strong with a `:warp` parameter: `delaytime = warp / freq` (standard KS uses `1 / freq`). When `warp ≠ 1.0` the comb filter reinforces inharmonic partials — creating bell-like sidebands and gong-like overtones impossible on a standard string model.

```clojure
;; warp = 1.0 → standard harmonic string
(sc/sc-play! {:synth :superkar-voice :freq 220 :warp 1.0 :coef 0.5 :body-freq 600})

;; warp = 1.2 → gong-like inharmonicity
(sc/sc-play! {:synth :superkar-voice :freq 220 :warp 1.2 :coef -0.1 :body-freq 400})
```

The `:superkar` patch provides four independently controlled voices (A2/E3/A3/E4). The key technique: per-voice warp trajectories with staggered start times create an evolving inharmonic ensemble texture that hardware (which has a single global warp) cannot produce.

```clojure
(def skar (sc/instantiate-patch! :superkar))

;; Four voices drift to different warp values at different speeds
(apply-trajectory! (get-in skar [:nodes :voice1]) :warp
  (trajectory :from 1.0 :to 1.12 :beats 48 :curve :s-curve :start (now)))
(apply-trajectory! (get-in skar [:nodes :voice3]) :warp
  (trajectory :from 1.0 :to 0.88 :beats 36 :curve :lin :start (+ (now) 16)))
```

See `examples/superkar_demo.clj` for the full showcase.

### Chaos synthesis (`:chaos-lorenz`, `:chaos-henon`, `:lorenz-fm`)

Three voices based on deterministic chaotic dynamical systems. All expose a `:chaos` parameter [0..1] that controls the bifurcation: the system transitions from stable/periodic at low values to inharmonic strange attractors at high values.

#### `:chaos-lorenz` — Lorenz atmospheric convection model

`:chaos` maps to Rayleigh number `r = 10 + 40 × chaos`:

| `:chaos` | `r` | Behaviour |
|----------|-----|-----------|
| 0.0 | 10 | Stable fixed point, near-silent |
| 0.4 | 26 | Near onset, irregular bursts |
| 0.5 | 28 | **Classic butterfly attractor** — rich harmonics |
| 0.75 | 40 | Complex broadband texture |
| 1.0 | 50 | Deep chaos, dense noise-like |

#### `:chaos-henon` — Hénon discrete map

`:chaos` maps to `a = 1.0 + 0.6 × chaos`. The Hénon map period-doubles more sharply — the transition from period-2 → period-4 → chaos is audible as pitch subdivision. Produces a metallic, crisp texture distinct from the smooth Lorenz drift.

#### `:lorenz-fm` — FM with Lorenz modulation

A SinOsc carrier frequency-modulated by the Lorenz X output. As `:chaos` increases, the carrier frequency deviates by an increasingly chaotic amount per sample — a clean sine becomes a dense cloud of inharmonic partials. A second Lorenz instance modulates the filter cutoff, coupling the timbral and spectral evolution.

```clojure
;; Bifurcation arc — stable → butterfly over 48 beats
(def lz (sc/sc-synth! :chaos-lorenz {:chaos 0.2 :freq-cut 2000 :amp 0.4}))
(apply-trajectory! lz :chaos
  (trajectory :from 0.2 :to 0.75 :beats 48 :curve :s-curve :start (now)))

;; FM destabilization
(def lfm (sc/sc-synth! :lorenz-fm {:freq 220 :chaos 0.1 :fm-depth 0.3}))
(apply-trajectory! lfm :chaos
  (trajectory :from 0.1 :to 0.8 :beats 32 :curve :exp :start (now)))
```

The `:chaos-ensemble` patch combines two Lorenz voices (different Prandtl numbers `s=10` and `s=12`) with one Hénon voice. The slight `s` difference causes the attractors to diverge over time, producing organic stereo widening even at identical `:chaos` values.

```clojure
(def ens (sc/instantiate-patch! :chaos-ensemble))
(sc/set-patch-param! ens :lorenz1-chaos 0.5)
(sc/set-patch-param! ens :henon-chaos 0.4)
(sc/free-patch! ens)
```

See `examples/chaos_demo.clj` for bifurcation arcs, ensemble configuration, and a live loop that samples the current chaos level to drive melodic filter color.

---

## 27. Waveshaping and Spectral Bridge

Waveshaping applies non-linear transfer functions to audio signals, adding
harmonics in a musically controllable way. Two built-in voices are available,
both natural targets for `apply-trajectory!` and `bind-spectral!`.

### Saturation — `:wshape-saturate`

Uses a `tanh` transfer function (soft clipper) applied to a VarSaw oscillator.
Saturation adds **odd harmonics only**, monotonically: the timbre evolves from
clean sine-like through warm tube overdrive to square-ish as `:drive` rises.

| Arg | Default | Description |
|-----|---------|-------------|
| `:freq` | 440.0 | Fundamental frequency (Hz) |
| `:drive` | 0.2 | Saturation depth 0.0–1.0 |
| `:width` | 0.0 | VarSaw pulse width |
| `:attack` | 0.01 | Envelope attack (s) |
| `:release` | 2.0 | Envelope release (s) |
| `:amp` | 0.45 | Output amplitude |
| `:pan` | 0.0 | Stereo position |

```clojure
;; Start clean and drive into saturation over 32 beats
(def sat (sc/sc-synth! :wshape-saturate {:freq 220 :drive 0.0 :amp 0.4}))
(apply-trajectory! sat :drive
  (trajectory :from 0.0 :to 1.0 :beats 32 :curve :s-curve :start (now)))
```

### Wavefold — `:wshape-fold`

Uses `fold2` (wavefolding) applied to a SinOsc, with optional FM input.
Wavefold adds **both even and odd harmonics** in a non-monotonic pattern:
each fold depth is a qualitatively different timbral state, not just "more."
The character changes non-predictably as `:drive` sweeps — first fold adds
brightness, second fold adds density, deep folds create percussive texture.

| Arg | Default | Description |
|-----|---------|-------------|
| `:freq` | 440.0 | Carrier frequency (Hz) |
| `:drive` | 0.2 | Fold depth 0.0–1.0 |
| `:freq-mod` | 0.0 | FM depth (0 = no FM) |
| `:mod-ratio` | 2.0 | FM modulator ratio relative to carrier |
| `:attack` | 0.01 | Envelope attack (s) |
| `:release` | 2.0 | Envelope release (s) |
| `:amp` | 0.45 | Output amplitude |
| `:pan` | 0.0 | Stereo position |

```clojure
;; Two voices, same arc, different transfer functions
(def sat-a  (sc/sc-synth! :wshape-saturate {:freq 165 :drive 0.02 :pan -0.4}))
(def fold-a (sc/sc-synth! :wshape-fold     {:freq 220 :drive 0.02 :pan  0.4}))

(def arc (trajectory :from 0.02 :to 0.85 :beats 48 :curve :s-curve :start (now)))
(apply-trajectory! sat-a  :drive arc)
(apply-trajectory! fold-a :drive arc)
;; Both voices start clean and evolve to opposite timbral destinations.
```

### bind-spectral! — audio-reactive parameter control

`bind-spectral!` connects spectral analysis output (from `nous.spectral`) to
any live SC node parameter. It watches the `[:spectral :state]` ctrl path and
fires a callback after every SAM tick, mapping the spectral value through a
user-supplied transform function.

```clojure
;; Prerequisite: Temporal Buffer and spectral SAM loop running
(def buf (start-temporal-buffer! :main 4.0))
(def sam (start-spectral! :main :cadence 1/4))

;; Dense playing -> high drive (saturated timbre)
;; Sparse playing -> low drive (clean timbre)
(def spectral-sat (sc/sc-synth! :wshape-saturate {:freq 220 :drive 0.05 :amp 0.4}))
(def cancel!
  (bind-spectral! spectral-sat :drive :spectral/density
                  (fn [density] (+ 0.05 (* 0.7 density)))))

;; Stop binding
(cancel!)
```

`bind-spectral!` returns a cancel fn (call with no args to detach). The
complementary `unbind-spectral!` detaches by node id and param:

```clojure
(unbind-spectral! spectral-sat :drive)
```

**Spectral keys available** (published by `start-spectral!`):

| Key | Range | Description |
|-----|-------|-------------|
| `:spectral/centroid` | Hz | Weighted average frequency (brightness) |
| `:spectral/density` | 0–1 | Normalised harmonic density (busyness) |
| `:spectral/blur` | 0–1 | Spectral spread / diffuseness |

Both `bind-spectral!` and `unbind-spectral!` are available in `nous.user`
without a namespace prefix.

See `examples/waveshaper_demo.clj` for a complete session including saturation
vs fold comparison, drive arcs, and the chaos-to-waveshaper compound arc.

---

## 28. DynKlank Physical Modeling

DynKlank models struck resonators (bells, bars, plates) as a bank of parallel
second-order band-pass filters. Each filter represents one resonant mode of the
physical object. The instrument character is encoded in partial frequency ratios
(`:fN`) and decay times (`:dN`).

### Why DynKlank vs Karplus-Strong

| | Karplus-Strong (`:pluck`, `:superkar`) | DynKlank (`:klank-bell`, `:klank-bars`) |
|---|---|---|
| **Physical model** | Plucked/bowed string | Struck bar, plate, or bell |
| **Excitation** | Initial noise burst in delay line | White-noise impulse |
| **Pitch control** | Delay time = 1/freq | Explicit partial ratios |
| **Inharmonicity** | Via `:warp` parameter | Via `:fN` ratio detuning |
| **Decay** | Single feedback coefficient | Per-partial decay times |

### Bell — `:klank-bell`

Classic inharmonic bell using Chowning/Fletcher-Rossing partial ratios.

| Arg | Default | Description |
|-----|---------|-------------|
| `:freq-scale` | 440.0 | Fundamental pitch (Hz) — retunes all modes |
| `:f1`–`:f4` | 1.0, 2.756, 5.404, 8.933 | Partial frequency ratios |
| `:a1`–`:a4` | 1.0, 0.67, 0.35, 0.18 | Partial amplitudes |
| `:d1`–`:d4` | 1.2, 0.9, 0.55, 0.35 | Partial decay times (s) |
| `:amp` | 0.6 | Output amplitude |
| `:pan` | 0.0 | Stereo position |

The non-integer partial ratios (2.756, 5.404, 8.933) create an ambiguous pitch
centre — characteristic of struck metal bells. Changing `:f2` from 2.756 to 2.0
gives a cleaner octave; to 3.1 produces a harder, more metallic timbre.

```clojure
;; Default bell
(sc/sc-play! {:synth :klank-bell :freq-scale 440 :amp 0.6})

;; Larger, longer-decaying bell
(sc/sc-play! {:synth :klank-bell :freq-scale 220 :d1 3.0 :d2 2.2 :d3 1.4 :d4 0.8})

;; Decay arc: xylophone character -> vibraphone -> bell
(def bell (sc/sc-synth! :klank-bell {:freq-scale 330 :d1 0.3 :d2 0.2 :d3 0.1 :amp 0.6}))
(apply-trajectory! bell :d1 (trajectory :from 0.3 :to 3.5 :beats 32 :curve :exp :start (now)))
(apply-trajectory! bell :d2 (trajectory :from 0.2 :to 2.5 :beats 32 :curve :exp :start (now)))
(apply-trajectory! bell :d3 (trajectory :from 0.1 :to 1.5 :beats 32 :curve :exp :start (now)))
```

### Bar instruments — `:klank-bars`

Marimba / vibraphone / glockenspiel using BPF noise excitation. Three modes
with an additional `:exc-bw` (excitation bandwidth) parameter that controls
mallet hardness — narrow BPF = soft mallet, wide BPF = hard mallet.

| Arg | Default | Description |
|-----|---------|-------------|
| `:freq-scale` | 440.0 | Fundamental pitch (Hz) |
| `:f1`–`:f3` | 1.0, 2.756, 5.404 | Partial ratios (fewer than bell) |
| `:a1`–`:a3` | 1.0, 0.5, 0.25 | Partial amplitudes |
| `:d1`–`:d3` | 0.8, 0.4, 0.2 | Partial decay times (s) |
| `:exc-bw` | 200.0 | Excitation BPF bandwidth (Hz) |
| `:amp` | 0.5 | Output amplitude |
| `:pan` | 0.0 | Stereo position |

Common voicings:

```clojure
;; Marimba: short, percussive decay
(sc/sc-play! {:synth :klank-bars :freq-scale 440 :d1 0.4 :d2 0.2 :amp 0.65})

;; Vibraphone: octave f2, long singing decay
(sc/sc-play! {:synth :klank-bars :freq-scale 440 :f2 4.0 :d1 2.5 :d2 1.5 :amp 0.6})

;; Glockenspiel: high pitch, long sustain
(sc/sc-play! {:synth :klank-bars :freq-scale 1760 :d1 3.0 :d2 2.5 :d3 2.0 :amp 0.5})
```

### Ensemble patch — `:klank-ensemble`

Two bell voices and one bars voice routed through a shared reverb.

```clojure
(def klank (sc/instantiate-patch! :klank-ensemble))

;; Tune to an open fifth
(sc/set-patch-param! klank :bars-freq  220)
(sc/set-patch-param! klank :bell1-freq 330)
(sc/set-patch-param! klank :bell2-freq 440)

;; Add reverb
(sc/set-patch-param! klank :effects-room 0.85)
(sc/set-patch-param! klank :effects-mix  0.5)

(sc/free-patch! klank)
```

Addressable params: `:bell1-freq`, `:bell1-amp`, `:bell1-d1`–`:bell1-d4`,
`:bell2-freq`, `:bell2-amp`, `:bell2-d1`–`:bell2-d4`, `:bars-freq`,
`:bars-amp`, `:bars-d1`–`:bars-d3`, `:effects-room`, `:effects-mix`.

### Decay time as compositional arc

DynKlank `:dN` parameters are natural `apply-trajectory!` targets. The decay
envelope *is* the instrument — sweeping from short to long morphs a dry
woodblock into a ringing bell in real time.

Combined with `bind-spectral!`, sparse playing lets the resonances bloom while
dense playing compresses them to a tight, percussive response:

```clojure
(def bell (sc/sc-synth! :klank-bell {:freq-scale 440 :d1 2.0 :d2 1.5 :amp 0.5}))
(def cancel!
  (bind-spectral! bell :d1 :spectral/density
                  (fn [density]
                    ;; Dense playing -> short decay; sparse -> long
                    (+ 0.3 (* 2.5 (- 1.0 density))))))
```

### Built-in SC patches (updated)

| Patch | Voices | Key params |
|-------|--------|------------|
| `:reverb-chain` | sine oscillator → reverb | `:freq`, `:room`, `:mix` |
| `:granular-cloud` | granular voice → reverb | `:buf`, `:density`, `:pos`, `:rate` |
| `:s42` | 4 drone + 2 VCO + 2 papa → filter → reverb | `:filter-cutoff`, `:droneN-freq`, `:vcoN-freq` |
| `:superkar` | 4 warpable KS voices → reverb | `:voiceN-warp`, `:voiceN-coef`, `:voiceN-body-freq` |
| `:chaos-ensemble` | 2 Lorenz + 1 Henon → reverb | `:lorenzN-chaos`, `:henon-chaos`, `:effects-mix` |
| `:klank-ensemble` | bell1 + bell2 + bars → reverb | `:bell1-freq`, `:bars-freq`, `:effects-room` |

See `examples/klank_demo.clj` for a complete session covering all bell and bar
variants, the ensemble patch, the partial-ratio detuning guide, decay arcs, and
the `bind-spectral!` audio-reactive decay sculpting example.

---

## 29. Composition from Hardware

`nous.composition` (renamed from `nous.midi-repair` in v0.8.0) captures a
musical idea played on a hardware controller, cleans up timing and duplicate
notes, and returns it as a plain Clojure sequence of step maps that you can
play back, transform, and evolve using the full nous vocabulary.

### Recording a phrase

```clojure
(require '[nous.composition :as comp])

;; Capture MIDI input on channel 1 for 4 beats at 120 BPM
(def raw (comp/record! :channel 1 :beats 4))

;; raw is a seq of {:pitch/midi n :dur/beats d :start/beat b ...}
```

### Ingesting and cleaning up

```clojure
;; ingest! applies the full cleanup pipeline:
;;   quantize timing → deduplicate overlaps → normalise velocities
(def phrase (comp/ingest! raw :quantize 1/8 :dedup true))
```

### Playing it back

```clojure
;; One-shot playback
(doseq [step phrase]
  (play! step)
  (sleep! (:dur/beats step)))

;; In a live loop (loops automatically)
(deflive-loop :motif {}
  (doseq [step phrase]
    (play! step)
    (sleep! (:dur/beats step))))
```

### Transforming the phrase

Because `ingest!` returns a plain Clojure sequence, every transformation in
the standard library applies directly:

```clojure
;; Transpose up a fifth
(def up5 (map #(update % :pitch/midi + 7) phrase))

;; Reverse
(def retrograde (reverse phrase))

;; Apply a nous transformer
(require '[nous.transform :as xf])
(def echoed (xf/apply-transforms phrase [(xf/echo :repeats 2 :decay 0.5 :delay-beats 1/2)]))
```

---

## 30. 8-op FM Synthesis

`nous.fm` extends the existing 4-operator FM engine to 8 operators, adding
per-operator waveforms, Through-Zero FM (TZFM) routing, and cross-operator
feedback loops.  All operators are SuperCollider UGens compiled to SC synth
definitions via `nous.sc`.  The `:8op-cc` backend (Leviasynth-inspired) emits
per-OSC CC maps for hardware 8-operator FM synths; see `doc/attribution.md` for
the hardware sources that shaped this design.

### Quick start

```clojure
(require '[nous.fm :as fm])
(require '[nous.sc :as sc])

;; Load a named 8-op preset
(def synth (sc/sc-synth! (fm/build-synth :8op-brass) {:freq 220 :amp 0.4}))
```

### Preset library

| Preset | Character |
|--------|-----------|
| `:8op-brass` | Bright brass; series operator chain with TZFM depth |
| `:8op-bells` | Four carrier/modulator pairs with cross-feedback shimmer |
| `:8op-evolving-pad` | Transient modulator + `SinOscFB` operator for slow morphing |
| `:8op-dx7-pno` | DX7-inspired piano; percussive attack, soft decay |

### Algorithm templates

Templates describe the operator routing topology.  Pass one to `fm/build-synth`
as the `:algorithm` key or use a preset that sets it automatically:

| Template | Description |
|----------|-------------|
| `:8op-stack` | All 8 ops in series (op8 → op7 → … → op1 carrier) |
| `:4-pairs` | Four independent C+M pairs in parallel |
| `:2x4-stacks` | Two 4-op stacks in parallel |
| `:8-carriers` | All 8 ops are carriers (additive synthesis) |
| `:4mod-1` | Four modulators → one carrier + 3 free carriers |

### Per-operator waveforms

Each operator accepts a `:waveform` key:

```clojure
{:id 1 :freq-ratio 1.0 :amp 0.8 :waveform :saw}
```

| Waveform | SC UGen |
|----------|---------|
| `:sin` | `SinOsc.ar` (default) |
| `:saw` | `Saw.ar` |
| `:tri` | `LFTri.ar` |
| `:pulse` | `Pulse.ar` |
| `:sin-fb` | `SinOscFB.ar` — also accepts `:feedback 0.0–1.0` |

### TZFM routing

Add `{:tzfm? true}` to a routing entry to use Through-Zero FM, which keeps
timbre pitch-invariant across the keyboard:

```clojure
{:id 1 :freq-ratio 1.0 :amp 1.0
 :routing [{:to 1 :depth 0.5 :tzfm? true}]}  ; self TZFM
```

The shorthand `{mod-id target-id}` still works for standard FM routing.

### Cross-feedback

Cross-feedback generates `LocalIn.ar`/`LocalOut.ar` pairs in SuperCollider,
enabling inter-operator feedback loops:

```clojure
(fm/build-synth
  {:algorithm  :4-pairs
   :operators  [...]
   :cross-feedback [{:from 2 :to 1 :depth 0.3}
                    {:from 4 :to 3 :depth 0.2}]})
```

---

## 31. OSC push-subscribe

The OSC push-subscribe mechanism lets external clients (TouchOSC, Max/MSP,
custom GUIs, remote nous instances) receive live updates whenever a ctrl tree
path changes — without polling.

### Starting the OSC server

```clojure
(require '[nous.osc :as osc])

(osc/start-osc-server! :port 57121)   ; default port
(osc/osc-running?)                    ; => true
```

### Subscribing from Clojure

```clojure
;; Push filter/cutoff changes to TouchOSC at 192.168.1.50:9000
(osc/subscribe! [:filter/cutoff] {:host "192.168.1.50" :port 9000})

;; Subscribe multiple addresses to the same path
(osc/subscribe! [:filter/cutoff] {:host "192.168.1.51" :port 9001})

;; List subscribers on a path
(osc/subscribers)
;; => {[:filter/cutoff] #{{:host "192.168.1.50" :port 9000} ...}}

;; Unsubscribe one
(osc/unsubscribe! [:filter/cutoff] {:host "192.168.1.50" :port 9000})

;; Unsubscribe all on a path
(osc/unsubscribe-all! [:filter/cutoff])
```

When the last subscriber leaves, the internal `ctrl/watch!` watcher is
removed automatically so there is no overhead for idle paths.

### Wire routes (for external clients)

Any OSC-capable device can subscribe without writing Clojure code by sending
OSC messages to the nous control port:

| Message | Arguments | Effect |
|---------|-----------|--------|
| `/sub`   | `ctrl-addr host port` | Subscribe `host:port` to `ctrl-addr` |
| `/unsub` | `ctrl-addr host port` | Unsubscribe |
| `/tree`  | `ctrl-addr`           | Push current value back to sender |

`ctrl-addr` is the URL-encoded ctrl tree path:
- `[:filter/cutoff]` → `/ctrl/filter%2Fcutoff`
- `[:loops :bass]`   → `/ctrl/loops/bass`

Example from TouchOSC (OSC send on connect):
```
/sub /ctrl/filter%2Fcutoff 192.168.1.50 9000
```

### Push message format

When a subscribed path changes, nous sends an OSC message to each registered
address:

```
/ctrl/filter%2Fcutoff  <value>
```

The value is coerced: integers become `int32`, floats become `float32`, strings
stay strings, other values are serialised with `pr-str`.

### Converting between ctrl paths and OSC addresses

```clojure
(osc/ctrl-path->osc-address [:filter/cutoff])
;; => "/ctrl/filter%2Fcutoff"
```

---

## 32. nREPL Remote Eval

`nous.remote` provides a minimal nREPL TCP client for evaluating Clojure
expressions on remote nous instances.  This is Topology Layer 3: once peers
are discovered (§20) and their backends are known, you can script cross-node
automation entirely from the REPL.

`nous.bencode` (the underlying codec) is hand-rolled to keep the dependency
list at just `org.clojure/clojure` and `org.clojure/data.json`.

### Connecting to a peer

```clojure
(require '[nous.remote :as remote])

;; Open a connection and clone an nREPL session
(def conn (remote/connect! "192.168.1.10" 7888))

;; Evaluate an expression — returns a map with :value :out :err
(remote/remote-eval! conn "(+ 1 2)")
;; => {:value "3" :out "" :err ""}

;; Evaluate and pretty-print output
(remote/remote-eval! conn "(start! :bpm 140)")

;; Close
(remote/disconnect! conn)
```

### `with-peer` macro

`with-peer` opens a fresh connection, evaluates the body, and closes on exit:

```clojure
(remote/with-peer [conn "192.168.1.10" 7888]
  (remote/remote-eval! conn "(link/enable!)")
  (remote/remote-eval! conn "(set-bpm! 130)"))
```

### `eval-on-peer!` — one-shot shorthand

When a peer has been discovered via `peer/start-discovery!`, `eval-on-peer!`
looks up the peer's nREPL port from the beacon data and opens a fresh
connection per call:

```clojure
;; Assuming :ubuntu was discovered with :nrepl-port 7888
(remote/eval-on-peer! :ubuntu "(deflive-loop :pad {} (play! :C4) (sleep! 2))")
```

### Cross-node scripting example

```clojure
;; Coordinate a two-node jam from one REPL
(remote/with-peer [mac "192.168.1.10" 7888]
  (remote/with-peer [rpi "192.168.1.20" 7888]
    (remote/remote-eval! mac "(set-bpm! 120)")
    (remote/remote-eval! rpi "(set-bpm! 120)")
    (remote/remote-eval! mac "(link/enable!)")
    (remote/remote-eval! rpi "(link/enable!)")))
```

---

## 33. Configuration Registry

`nous.config` exposes all §25 tunable system parameters through a validated
registry.  Parameters are seeded into the ctrl tree on `start!` so every value
is immediately readable and writable via HTTP and OSC — no extra wiring needed.

### Reading configuration

```clojure
(require '[nous.config :as config])

(config/get-config :bpm)                       ; => 120
(config/get-config :link/quantum)              ; => 4
(config/get-config :ctrl/undo-stack-depth)     ; => 50
(config/get-config :osc/control-port)          ; => 57121

;; All parameters at once
(config/all-configs)
;; => {:bpm 120 :link/quantum 4 :osc/control-port 57121 ...}
```

### Writing configuration

```clojure
;; Change the Link quantum (takes effect on next loop start)
(config/set-config! :link/quantum 8)

;; Change BPM — equivalent to set-bpm!, with full side effects
(config/set-config! :bpm 140)

;; Validation is enforced — these throw ex-info
(config/set-config! :bpm -1.0)          ; negative BPM
(config/set-config! :link/quantum 0)    ; non-positive quantum
```

### HTTP access

All parameters are accessible via the control-plane HTTP server (§12):

```
GET  /ctrl/config/bpm                       → {"value": 120}
GET  /ctrl/config/link%2Fquantum            → {"value": 4}
GET  /ctrl/config/ctrl%2Fundo-stack-depth   → {"value": 50}
GET  /ctrl/config/sched-ahead-ms%2Fmidi-hardware → {"value": 100}
```

`set-bpm!` keeps `[:config :bpm]` in sync so the HTTP response always reflects
the live tempo.

### Parameter reference

| Key | Default | Description |
|-----|---------|-------------|
| `:bpm` | `120` | Master tempo (beats per minute) |
| `:link/quantum` | `4` | Ableton Link phrase length in beats |
| `:ctrl/undo-stack-depth` | `50` | Maximum undo-stack entries |
| `:lfo/update-rate-hz` | `100` | LFO continuous-parameter update rate |
| `:osc/control-port` | `57121` | OSC control-plane server UDP port |
| `:osc/data-port` | `57110` | OSC data-plane / peer passthrough port |
| `:sched-ahead-ms/midi-hardware` | `100` | MIDI hardware schedule-ahead (ms) |
| `:sched-ahead-ms/sc-osc` | `30` | SuperCollider OSC schedule-ahead (ms) |
| `:sched-ahead-ms/vcv-rack` | `50` | VCV Rack schedule-ahead (ms) |
| `:sched-ahead-ms/daw-midi` | `50` | DAW MIDI schedule-ahead (ms) |
| `:sched-ahead-ms/link` | `0` | Link sync schedule-ahead (ms) |

### Registry introspection

```clojure
(config/all-param-keys)
;; => #{:bpm :link/quantum :osc/control-port ...}

(config/param-info :bpm)
;; => {:default 120 :type :float :doc "Master tempo in beats per minute." :validate #fn}
```

### Startup behaviour

`core/start!` resets `:config` to registry defaults and clears the undo stack
on every call, so sessions start in a clean, predictable state.  The `:bpm`
argument overrides the `:bpm` default:

```clojure
(start! :bpm 110)   ; :config is {... :bpm 110 :link/quantum 4 ...}
```

---

## 34. MCP Bridge

`nous.mcp` is a standalone MCP (Model Context Protocol) server that exposes
the running nous session as AI-legible tools.  Launch it alongside your REPL
and Claude Code can participate in the session as a live compositional collaborator
— reading harmony context, writing loops, ingesting recordings, and reasoning
across the full synthesis stack.

### Why nous is uniquely suited for MCP

Most live-coding environments expose code to an AI; nous exposes *data*.  The
ctrl tree, harmony context, score map, and SynthDef graph are all plain Clojure
values.  An AI can read them, reason about them musically, and write back without
any special serialisation layer.

### Setup

```bash
# Start nous (lein repl or your usual entry point)
lein repl

# In a second terminal — start the MCP server
lein mcp                              # connects to localhost:7888
lein mcp --nrepl-host 192.168.1.10    # connect to remote node
```

Register with Claude Code (`.mcp.json` at the repo root, or `claude mcp add`):

```json
{
  "mcpServers": {
    "nous": {
      "command": "lein",
      "args": ["-C", "mcp"]
    }
  }
}
```

### Tool reference

#### Session control

| Tool | Description |
|------|-------------|
| `evaluate` | Eval any Clojure on the running session |
| `get-ctrl` | Read a ctrl tree path (EDN vector, e.g. `[:config :bpm]`) |
| `set-ctrl` | Write a value to a ctrl tree path |
| `get-harmony-ctx` | Current `*harmony-ctx*`: root, scale, tensions |
| `get-spectral` | Spectral analysis state: centroid, flux, density |
| `get-loops` | Running loop inventory with tick counts |
| `define-loop` | Create or hot-swap a named live loop |
| `stop-loop` | Stop a named loop |
| `play-note` | Trigger a single note event |
| `set-bpm` | Change master tempo (propagates to Link peers) |
| `get-config` / `set-config` | §25 configuration registry |
| `get-score` | Full ctrl tree snapshot as EDN |

#### Composition pipeline

The pipeline tools share a `composition` binding in the nREPL session.
`ingest-midi` populates it; subsequent tools read and update it.

| Tool | Description |
|------|-------------|
| `ingest-midi` | Full pipeline: parse → key/mode detect → voice separation → tension arc → outlier flag → resolution |
| `show-corrections` | Display outlier note proposals from the last ingest |
| `accept-corrections` | Apply corrections; rebind `composition` |
| `play-score` | Play the current `composition` via the event engine |
| `save-score` | Write `composition` to an EDN file (music area handoff) |

```
# Typical Claude workflow:
ingest-midi path=/path/to/recording.mid
→ shows: G ionian, 110 BPM, 32 bars, 0 corrections, peak tension bar 28

show-corrections
→ "No corrections proposed — recording stayed diatonic throughout"

play-score
→ plays back drone + pad + motifs

save-score path=~/org/areas/music/shipwreck-piano.edn
→ {:saved ".../shipwreck-piano.edn"}
```

#### Topology

| Tool | Description |
|------|-------------|
| `list-peers` | Topology registry: all discovered nodes with backends |
| `evaluate-on-peer` | Eval on any named node (e.g. `:ubuntu`) in the topology |

`evaluate-on-peer` proxies through `nous.remote/eval-on-peer!` on the primary
node, so the peer registry lookup happens in the running session rather than the
MCP process.  Use `list-peers` first to discover available node IDs.

### The `evaluate` escape hatch

Every named tool is a thin wrapper around `evaluate`.  Anything not covered by
a named tool is one `evaluate` call away.  The composition namespace, FM
compiler, Spatial Field, Euclidean rhythms — all accessible directly:

```
evaluate code=(comp/print-corrections composition)
evaluate code=(apply-trajectory! bass-node :coeff (buildup 8.0 {:from 0.95 :to 0.9998}))
evaluate code=(compile-fm :sc (fm-algorithm :8op-stack))
```

---

## 35. Keyboard Performance (`nous.ivk`)

The `nous.ivk` namespace turns the computer keyboard into a live performance
surface. Keyboard events from the kairos peer's `CGEventTap` (macOS) arrive as
`0x21 KbdEvent` frames and are dispatched through an open multimethod, making
layouts fully extensible without touching ivk internals.

### Starting keyboard input

```clojure
;; Register the keyboard handler (kairos must be started with --kbd)
(start-kbd!)

;; Switch to a layout
(set-layout! :harmonic)   ; or :interval, :chromatic, :scale, :ndlr

;; Stop keyboard input and clear any queued arp notes
(stop-kbd!)
```

### Built-in layouts

| Layout | Style | Left hand | Right hand |
|--------|-------|-----------|------------|
| `:interval` | Samchillian/Misha | Step down by scale degree | Step up by scale degree |
| `:chromatic` | Piano | Lower white keys | Upper white / black keys |
| `:scale` | Absolute degrees | Scale degrees I–IV | Scale degrees V–VII + upper |
| `:ndlr` | NDLR chord surface | Chord functions (I/II/IV/V) | Chord tones over active chord |
| `:harmonic` | TheoryBoard split | Harmonic context (I/IV/V/vi) | Melody (chord tones) |

### The `:harmonic` layout in detail

The home row is split at the `g`/`h` boundary. Left hand sets the harmonic
context; right hand plays melody over it. Number row pivots the scale root.

```
1    2    3    4    5    6    7         8    9
C    D    E    F    G    A    B         ♭    ♯
scale root pivot pads                  semitone shift

q    w    e    r    t    y    u    i    o    p
                         oct+1 chord tones

a    s    d    f    g  | h    j    k    l    ;
I    IV   V    vi   I  | 1    2    3    4    5   ← home row split
(chord functions)       (chord tones, root octave)

z    x    c    v    b    n    m
                              voiced mode toggle
```

The `|` marks the split. Tap `a`–`f` to change harmony (silent by default;
enable voiced mode with `m` for a root note on each chord change). Then `h`–`;`
play the melody tones of the active chord.

### Arp mode

```clojure
;; Key presses queue notes instead of playing immediately
(swap! ivk-state assoc :arp? true)
(start-arp!)            ; cycle the queue at :arp-rate (default 1/4 beat)
(stop-arp!)             ; stop cycling; clear queue
```

### Registering custom layouts

Layouts are plain Clojure maps — write one and register it:

```clojure
(register-layout! :my-layout
  {:layout/id   :my-layout
   :layout/name "My Layout"
   :key-map
   {\a {:action :play-absolute :midi 60}   ; C4
    \s {:action :apply-interval :n  1}     ; up one scale degree
    \d {:action :apply-interval :n -1}     ; down one scale degree
    \f {:action :set-chord-fn :roman :IV}
    \[ {:action :set-octave :delta -1}
    \] {:action :set-octave :delta  1}}})

(set-layout! :my-layout)
```

`handle-action!` is a multimethod dispatching on `:action`. Add new action
types with `defmethod`:

```clojure
(defmethod nous.ivk/handle-action! :my-action
  [{:keys [my-param]} state]
  ;; return updated state
  (assoc state :current-pitch my-param))
```

### Rendering a layout as a cheatsheet

```clojure
(render-layout :harmonic)
;; prints:
;; Harmonic (TheoryBoard-style)
;; ──────────────────────────────────────────────────
;; `     1     2     3     4     5     6     7     8     9     0     -     =
;;       C     D     E     F     G     A     B           ♭    ♯
;; ...
```

### T-1 PassThru pattern

When the Torso T-1 is connected as a MIDI input, use PassThru to anchor the
keyboard to the T-1's current pitch:

```clojure
(open-input! "Torso T-1")
(register-note-handler! "Torso T-1"
  (fn [msg] (set-pitch! (:pitch msg)))
  :passthru? true)

;; Now the keyboard navigates intervals relative to whatever T-1 last played
(set-layout! :interval)
```

---

## 36. MIDI Input (`nous.midi-in`)

`nous.midi-in` opens JVM MIDI input ports via `javax.sound.midi` — independent
of the kairos/aion peer. Multiple ports can be open simultaneously; handlers filter by
source, channel, and pitch range.

### Opening ports

```clojure
;; List available MIDI input ports
(list-midi-ports)   ; shows both output and input ports

;; Open by index
(open-input! 0)

;; Open by name substring (case-insensitive)
(open-input! "Torso T-1")
(open-input! "Hydra")

;; See all currently open inputs
(open-inputs)
;; => {"Torso T-1" #object[...], "Hydrasynth" #object[...]}

;; Close one port or all
(close-input! "Torso T-1")
(close-all-inputs!)
```

### Registering note handlers

```clojure
;; Basic handler — fires on every NoteOn from any open input
(register-note-handler! :my-handler
  (fn [{:keys [pitch velocity channel source]}]
    (play! pitch)))

;; Filter by source port
(register-note-handler! :t1-handler
  (fn [msg] (println "T-1 note:" (:pitch msg)))
  :source "Torso T-1")

;; Filter by channel and pitch range
(register-note-handler! :bass-handler
  (fn [msg] (play! (:pitch msg)))
  :channel 1
  :pitch-range [36 60])

;; Remove a handler
(unregister-note-handler! :my-handler)
```

Handler maps contain:
- `:pitch` — MIDI note number 0–127
- `:velocity` — 0–127 (0 = NoteOff)
- `:channel` — MIDI channel 1–16
- `:source` — port name string
- `:type` — `:note-on` or `:note-off`

### Torso T-1 device map

The `resources/devices/torso-t1.edn` device map covers all T-1 MIDI parameters:

```clojure
(require '[nous.device :as device])
(def t1 (device/load-device "torso-t1"))

;; Send T-1 parameters via CC
(device/send-cc! t1 :euclidean/steps 16)
(device/send-cc! t1 :euclidean/pulses 5)
(device/send-cc! t1 :tempo/bpm 120)
```

---

## 37. Process Supervisor (`nous.supervisor`)

The supervisor is a lightweight Erlang-style watchdog that monitors external
services (SC server, kairos/aion peer) and live loop threads, emitting lifecycle
events on state changes, optionally auto-restarting failed services, and
restoring last-known state after a recovery.

### Quick start

```clojure
(require '[nous.supervisor :as supervisor])

;; Register the SC server: health check + auto-restart via sc-restart! + synthdef restore.
;; Requires start-sc! to have been called so sc-restart! knows the binary/script path.
;; Pass :restart-fn to override (e.g. when SC is managed externally).
(supervisor/register-sc!)

;; Register kairos/aion: health check + auto-restart using saved start opts.
(supervisor/register-kairos!)

;; Start the watchdog (one bar scan period at current BPM, loop monitoring on).
(supervisor/start-watchdog!)

;; React to SC going down — pause an arc, alert the performer, etc.
(supervisor/on-event! :sc :down ::my-handler
  (fn [payload] (println "SC went down at" (:at payload))))

;; Remove the handler when done.
(supervisor/off-event! :sc :down ::my-handler)

;; Stop the watchdog.
(supervisor/stop-watchdog!)
```

### Service registration

`register!` accepts three optional hook functions:

| Option | Called when | Notes |
|--------|-------------|-------|
| `:check-fn` | Every watchdog tick | Return truthy = healthy |
| `:restart-fn` | Service goes `:down` | Should attempt reconnect; may throw |
| `:restore-fn` | After successful recovery | Re-send state (synthdefs, etc.) |

```clojure
(supervisor/register! :my-synth
  :check-fn    #(synth/connected?)
  :restart-fn  #(synth/reconnect!)
  :restore-fn  #(synth/reload-patches!))

(supervisor/deregister! :my-synth)
```

The two built-in registrations handle the common case:

```clojure
;; SC: health=sc-connected?, restore=resend-sent-synthdefs!
;; Pass :restart-fn if you want auto-reconnect.
(supervisor/register-sc!)
(supervisor/register-sc! :restart-fn #(sc/connect-sc!))

;; kairos/aion: health=kairos/connected?, restart=restart-kairos! (auto)
(supervisor/register-kairos!)
```

### Status queries

```clojure
(supervisor/service-status :sc)      ; => :up | :down | :unknown
(supervisor/all-statuses)            ; => {:sc :up, :kairos :down}
(supervisor/any-down? [:sc :kairos]) ; => truthy if either is :down
(supervisor/running?)                ; => true if watchdog is active
```

### Watchdog options

```clojure
;; Fixed 5-second interval, no loop monitoring:
(supervisor/start-watchdog! :interval-ms 5000 :monitor-loops? false)

;; BPM-derived: scan every 2 bars of 3/4 (reads core/get-beats-per-bar by default):
(supervisor/start-watchdog! :bars 2 :beats-per-bar 3)

;; Default: one bar at current BPM, beats-per-bar from core state, loops monitored.
(supervisor/start-watchdog!)
```

When `:interval-ms` is omitted the watchdog sleep is recomputed each tick as
`bars × beats-per-bar × (60 000 / bpm)`, so a live `set-bpm!` is automatically
reflected. The minimum sleep is 100 ms regardless of BPM.

### BPM-derived scan period

The scan interval is musically proportional when you leave `:interval-ms` unset:

| BPM | 1 bar (4/4) | 1 bar (3/4) |
|-----|-------------|-------------|
| 120 | 2 000 ms    | 1 500 ms    |
| 100 | 2 400 ms    | 1 800 ms    |
|  72 | 3 333 ms    | 2 500 ms    |
|  60 | 4 000 ms    | 3 000 ms    |

### Pause/resume integration with deflive-loop

Loops can declare service dependencies via `:pause-on-down`. When a dependency
is `:down`, the loop sleeps one `:resume-on-bar` period (default 4 beats) and
retries — maintaining virtual-time continuity without playing into silence. On
recovery the loop re-enters at the next `:resume-on-bar` beat boundary.

```clojure
;; Pause :voice-a while SC is down; resume on 4-beat boundary (default).
(deflive-loop :voice-a {:pause-on-down [:sc]}
  (play! {:synth :sine :pitch/midi 60 :dur/beats 1})
  (sleep! 4))

;; Resume on 8-beat (2-bar) boundary for larger loops:
(deflive-loop :voice-b {:pause-on-down [:sc :kairos] :resume-on-bar 8}
  (play! (berlin/next-step! ost-b))
  (sleep! 5))
```

The pause-check is wired in by `start-watchdog!` and unwired by `stop-watchdog!`.
Before the watchdog starts, `:pause-on-down` has no effect (loops run normally).

### Event bus

```clojure
;; Register: (on-event! service event-type handler-key handler-fn)
(supervisor/on-event! :sc :down ::alert
  (fn [{:keys [service at]}]
    (println service "went down at" at)))

(supervisor/on-event! :sc :up ::restore-arc
  (fn [{:keys [restarted]}]
    (when restarted (println "SC auto-restarted"))))

(supervisor/on-event! :sc :restore-failed ::log-fail
  (fn [{:keys [error]}] (println "Restore failed:" error)))

;; Deregister by key:
(supervisor/off-event! :sc :down ::alert)
```

Handler exceptions are caught and logged — one failing handler never suppresses
the others. The event payload always includes `:service` and `:at` (epoch ms).
`:up` events from auto-restart include `:restarted true`.

### restart-loop!

`restart-loop!` in `nous.loop` restarts a dead loop thread aligned to a beat
boundary without requiring a full `deflive-loop` re-evaluation:

```clojure
;; Restart :voice-a at the next 4-beat boundary (default):
(loop-ns/restart-loop! :voice-a)

;; Restart at the next 8-beat boundary:
(loop-ns/restart-loop! :voice-a :align-beats 8)
```

`restart-loop!` is a no-op (returns nil) if the loop thread is still alive.
The supervisor's loop-monitoring can call this automatically if you wire it via
an `on-event!` handler on `:loops :down`.

### Global beats-per-bar

The system-wide beats-per-bar setting lives in `core/system-state` alongside BPM:

```clojure
;; Set at boot:
(start! :bpm 100 :beats-per-bar 3)   ; 3/4 time

;; Change live:
(set-beats-per-bar! 5)               ; 5/4
(get-beats-per-bar)                  ; => 5

;; Automatically picked up by:
;;   (journey/start-bar-counter!)    — no-arg arity reads core value
;;   (supervisor/start-watchdog!)    — :beats-per-bar reads core value each tick
```

Pass `:beats-per-bar` explicitly to either function to override for that instance
without changing the global setting.

---

## 38. Arpeggiator (`nous.arp`)

`nous.arp` provides a first-class arpeggiator engine with a named pattern
library. Patterns can be used one-shot from the REPL, looped in the background,
or stepped through manually inside a `live-loop` or Berlin ostinato.

### Pattern formats

Two formats are supported:

**`:chord` patterns** — classic arpeggiation using chord-tone indices.

```clojure
{:type    :chord
 :order   [0 2 1 2]   ; indices into the chord (0=root, 1=3rd, 2=5th, ...)
 :rhythm  [1 1 1 1]   ; step durations in beats (1=quarter, 1/2=eighth)
 :dur     3/4}        ; gate fraction (75%)
```

Indices beyond the chord size wrap upward by octave: index 3 on a triad plays
root+octave (root+12), index 4 plays 3rd+octave, and so on.

**`:phrase` patterns** — semitone-offset figures, modelled after the Hydrasynth.

```clojure
{:type  :phrase
 :dur   3/4                       ; gate fraction (default 3/4; optional)
 :steps [{:semi 0 :beats 1}       ; root of held chord/note
          {:semi 4 :beats 1}       ; major third above root
          {:rest true :beats 1/2}  ; rest — no note played
          {:semi 7 :beats 1}]}     ; perfect fifth
```

`:semi` is a semitone offset from the root. The root comes from the
`chord-or-root` argument at play time. `:rest true` steps advance the clock
without playing.

### Built-in patterns

11 chord patterns and 64 Hydrasynth phrases are loaded automatically:

```clojure
(arp-ls)   ; list all registered patterns
;; => [[:alberti "Classical Alberti bass: root-fifth-third-fifth"]
;;     [:bounce  "Ascend then descend — pendulum through chord tones"]
;;     ...
;;     [:phrase-01 "phrase-01"] ... [:phrase-64 "phrase-64"]]
```

**Chord patterns**: `:up`, `:down`, `:bounce`, `:alberti`, `:waltz-bass`,
`:broken-triad`, `:guitar-pick`, `:jazz-stride`, `:montuno`, `:raga-alap`,
`:euclid-5-8`.

**Phrase patterns**: `:phrase-01` through `:phrase-64`, transcribed from the
ASM Hydrasynth Deluxe Owner's Manual v2.2. All are flagged `:verified? false`
pending hardware cross-check; treat as musically plausible approximations.

### One-shot playback

```clojure
;; Chord arp — pass a chord map or MIDI integer vector
(arp-play! :alberti (chord/make :C4 :major) :vel 90)
(arp-play! :up      [60 64 67])

;; Phrase arp — pass a root MIDI number or {:root N} map
(arp-play! :phrase-14 {:root 60} :vel 80)
(arp-play! :phrase-14 60         :vel 80)

;; Options
;;   :vel  0–127 (default 100)
;;   :oct  octave shift applied to all notes (default 0)
;;   :rate beat-duration multiplier: 2.0 = half-speed, 0.5 = double (default 1.0)
(arp-play! :euclid-5-8 [60 64 67] :rate 0.5 :vel 110)
```

`arp-play!` blocks for the full pattern duration — call it from a `live-loop`
body or a background thread.

### Looping arps

`ArpState` implements `IStepSequencer` (§41), so `seq-loop!` from `nous.seq`
is the standard way to run it in the background:

```clojure
(def my-state (make-arp-state :alberti [60 64 67] :vel 90))

;; Start a background looping arp
(def handle (seq-loop! my-state))

;; Stop it (after current cycle completes)
(stop-seq! handle)
```

`seq-loop!` returns `{:running? atom :future f}` — no global loop registry.
Tempo changes via `set-bpm!` take effect at the next cycle boundary.

### Step-by-step engine (live-loop integration)

`ArpState` is an `IStepSequencer` — use `run-cycle!` to play one full
pattern cycle inside a `deflive-loop`:

```clojure
(def my-arp (make-arp-state :montuno [60 64 67 70] :vel 95))

(deflive-loop :bass-arp {}
  (run-cycle! my-arp))
```

To change the chord voicing between cycles without resetting the step position:

```clojure
(deflive-loop :bass-arp {}
  (reset-arp-chord! my-arp (next-chord!))
  (run-cycle! my-arp))
```

For manual step-by-step control use `next-event` from `nous.seq`:

```clojure
(let [{:keys [event beats]} (sq/next-event my-arp)]
  ;; event is a standard note map {:pitch/midi N :dur/beats D :mod/velocity V}
  ;; or nil for rest steps
  (when event (play! event))
  (sleep! beats))
```

### Parameter locks

Both pattern formats support per-step parameter overrides that are merged
into the note map before dispatch.

**`:chord` patterns** — add a `:params` vector parallel to `:order`:

```clojure
(arp-register! :filtered-bounce
  {:type   :chord
   :order  [0 1 2 1]
   :rhythm [1 1/2 1/2 1]
   :dur    3/4
   :params [{} {:mod/cutoff 127} {:mod/cutoff 64} {}]})
   ;; step 1 opens filter; step 2 at mid; steps 0/3 unchanged
```

**`:phrase` patterns** — extra keys in a step map are locks automatically:

```clojure
{:type  :phrase
 :steps [{:semi 0 :beats 1 :mod/cutoff 64}
          {:semi 4 :beats 1 :mod/cutoff 127}
          {:semi 7 :beats 1}
          {:rest true :beats 1}]}
;; :pitch/microtone for Iridium MkII / Hydrasynth MTS per-step inflection:
;;   {:semi 3 :beats 1 :pitch/microtone -17}  ; 17 cents flat
```

Locked values flow through the standard ctrl binding dispatch in `core/play!`
— if `:mod/cutoff` is bound to a MIDI CC via `ctrl/bind!`, the CC fires
before the note-on automatically.

### Custom patterns

```clojure
;; Register a custom chord pattern
(arp-register! :my-stride
  {:type        :chord
   :description "My custom stride pattern"
   :order       [0 2 1 3 2]
   :rhythm      [2 1 1 1 1]
   :dur         1/2})

;; Register a custom phrase
(arp-register! :my-riff
  {:type  :phrase
   :steps [{:semi 0 :beats 1}
           {:semi 2 :beats 1/2}
           {:semi 4 :beats 1/2}
           {:semi 7 :beats 2}]})

;; Retrieve
(arp-get :my-stride)
```

Custom patterns are stored in the same registry as built-ins. They persist
for the lifetime of the JVM session.

### Keyboard arp (nous.ivk integration)

The ivk arp mode queues keyboard-pressed notes and cycles through them. It
now uses `loop-ns/sleep!` for timing (respects virtual time inside live-loops):

```clojure
(swap! ivk-state assoc :arp? true)
(start-arp!)   ; cycle :arp-notes at :arp-rate (default 1/4 beat)
(stop-arp!)    ; stop and clear queue
```

---

## 39. Reference

### REPL commands


| Command | Description |
|---------|-------------|
| `(start! :bpm 120)` | Start the clock |
| `(stop!)` | Stop all loops and the clock |
| `(set-bpm! 140)` | Change tempo live |
| `(stop-loop! :name)` | Stop one loop after its current iteration |
| `(start-sidecar!)` | Connect MIDI output (port 0) |
| `(start-sidecar! :midi-port "IAC")` | Connect by port name substring |
| `(stop-sidecar!)` | Disconnect MIDI output |
| `(list-midi-ports)` | Enumerate MIDI output and input ports |
| `(start-server!)` | Start the HTTP ctrl-tree server (port 7177) |
| `(start-server! :port 7278)` | Start on a custom port |
| `(stop-server!)` | Stop the HTTP server |
| `(server-running?)` | Returns true if the server is running |
| `(server-port)` | Returns the current server port |

### play! step keys

| Key | Type | Description |
|-----|------|-------------|
| `:pitch/midi` | int 0–127 | MIDI note number |
| `:dur/beats` | number | Duration in beats (quarter notes) |
| `:mod/velocity` | int 0–127 | Note velocity |
| `:mod/channel` | int 1–16 | MIDI channel |
| `:pitch/bend-cents` | float | Microtonal pitch bend (set by tuning pipeline) |

### Pitch name syntax

`(play! :C4)` — note name + octave. Accidentals: `:Cs4` (C#4), `:Db4` (D♭4),
`:Bb3` (B♭3). Octave range: 0–8. MIDI 60 = `:C4`.

### Sleep durations

All durations are in **beats** (quarter notes at the current BPM).

| Expression | Duration |
|------------|----------|
| `4` | 4 beats — one 4/4 bar |
| `2` | 2 beats — half note |
| `1` | 1 beat — quarter note |
| `1/2` | half a beat — eighth note |
| `1/4` | quarter beat — sixteenth note |
| `1/8` | eighth beat — 32nd note |
| `1/3` | triplet eighth |
| `2/3` | triplet quarter |

---

## 40. Browser Control Surface

The browser control surface is a ClojureScript + Reagent application served
directly by the nous HTTP server. It gives you a live view of the ctrl tree
during a session without having to stay in the REPL.

Open `http://localhost:7177/` after starting the server. The page requires the
CLJS build to have run (see below); before that, the HTML shell loads but
JavaScript is absent.

### What it shows

Two side-by-side panels fill the window:

| Panel | Contents |
|-------|----------|
| **Ctrl tree** | All current ctrl-tree values, sorted by path, updated in real time |
| **Changes log** | Last 60 writes, newest at top, with a brief green flash on arrival |

The header shows **BPM** (extracted from the ctrl tree) and a connection badge:
**LIVE** (green) when the WebSocket is open, **connecting** (amber) while
reconnecting, **OFFLINE** (red) if the server is unreachable. The connection
auto-recovers after 3 seconds.

### Building the UI

The CLJS source is in `src/nous_ui/core.cljs`. It compiles to
`resources/public/js/main.js` (gitignored). You need Node.js and npm.

```sh
# First time — install shadow-cljs, react, and react-dom
npm install

# Production build (single pass, minified) — run before a session
npx shadow-cljs release app

# Development — watch mode with hot-reload on CLJS saves
npx shadow-cljs watch app
```

The lein build (`lein test`, `lein uberjar`) is entirely independent of the
CLJS build. You do not need npm for any JVM-side operation.

### Workflow

```clojure
;; In the REPL — start the server (default port 7177)
(server/start-server!)

;; Then open http://localhost:7177/ in a browser.
;; Any ctrl/set! call appears in both panels immediately.

;; The UI also writes back — changes from the browser arrive via ctrl/set!
;; on the server (logical write only; does not dispatch MIDI CC).
```

### Build outputs and gitignore

| Path | Status |
|------|--------|
| `resources/public/index.html` | Committed — HTML shell |
| `resources/public/css/style.css` | Committed — dark theme stylesheet |
| `resources/public/js/` | **Gitignored** — compiled CLJS output |
| `node_modules/` | **Gitignored** — npm packages |
| `.shadow-cljs/` | **Gitignored** — shadow-cljs build cache |

### Architecture note

The control surface is Step 2 of a planned web UI sequence:

| Step | Version | Feature |
|------|---------|---------|
| 1 | v0.13.0 | `/ws` WebSocket endpoint + `watch-global!` in `nous.ctrl` |
| 2 | v0.14.0 | ClojureScript + Reagent frontend served from http-kit |
| 3 | planned | `defdevice :ui` key — device layout drives control surface generation |
| 4 | planned | Tauri packaging — `.app` wrapping JVM + scsynth as sidecars |

Steps 1 and 2 are complete. The control surface at Step 2 is a general-purpose
ctrl-tree observer; Step 3 will add device-specific panels generated from the
device model data.

---

## 41. Step Sequencer Protocol (`nous.seq`)

`nous.seq` defines the `IStepSequencer` protocol — the common interface
that all step-based note generators in nous implement. It provides a
uniform way to drive `ArpState`, `MotifState`, `FractalSeq`, `StochasticSeq`,
and any custom sequencer you write.

### Protocol

```clojure
(defprotocol IStepSequencer
  (next-event [sq])         ; advance one step → {:event note-map-or-nil :beats N}
  (seq-cycle-length [sq]))  ; steps per cycle, or nil for infinite/generative
```

`next-event` returns:

| Return value | Meaning |
|---|---|
| `{:event {:pitch/midi N :dur/beats D :mod/velocity V ...} :beats B}` | Play a note; sleep `:beats` |
| `{:event nil :beats B}` | Rest; sleep `:beats` only |

`:beats` is always the step clock duration. `:dur/beats` inside the event is
the gate (note duration ≤ `:beats`). Any extra keys in the event map are
parameter locks that `play!` routes via ctrl bindings.

### Runners

```clojure
;; Play one step (for infinite sources inside deflive-loop)
(run-step! sq)
(run-step! sq {:xf my-transformer})

;; Play one full cycle (seq-cycle-length steps)
(run-cycle! sq)
(run-cycle! sq {:xf my-transformer})

;; Loop indefinitely in background
(def handle (seq-loop! sq))
(def handle (seq-loop! sq {:xf my-transformer}))
(stop-seq! handle)
```

`run-cycle!` on an infinite source (nil `seq-cycle-length`) delegates to
`run-step!` — the caller's `deflive-loop` provides the outer loop.

### ITransformer composition

The `:xf` option applies a `nous.transform` `ITransformer` to each event
before dispatch — harmonies, echoes, octave doublings etc.:

```clojure
(deflive-loop :melody {}
  (run-cycle! my-motif {:xf (harmonize {:intervals [7]})}))
```

### Composing sequencers

Because all sources speak the same protocol, they compose:

```clojure
;; Drive arp chord from a motif — advance harmony once per bar
(def chord-seq (make-motif-state (named-pattern :up) (named-rhythm :four-on-floor)))
(def mel-arp   (make-arp-state   :bounce [60 64 67]))

(deflive-loop :comp {}
  (let [{:keys [event]} (sq/next-event chord-seq)]
    (when event
      (reset-arp-chord! mel-arp (:pitch/midi event))))
  (run-cycle! mel-arp))
```

### Implementing the protocol on custom types

```clojure
(defrecord MySeq [notes step-atom])

(extend-protocol sq/IStepSequencer
  MySeq
  (next-event [s]
    (let [i    (mod @(:step-atom s) (count (:notes s)))
          note (nth (:notes s) i)]
      (swap! (:step-atom s) inc)
      {:event {:pitch/midi note :dur/beats 0.4 :mod/velocity 100}
       :beats 0.5}))
  (seq-cycle-length [s] (count (:notes s))))
```

---

## 42. Pattern × Rhythm Motifs (`nous.pattern`)

`nous.pattern` provides the NDLR-style Pattern × Rhythm motif engine.
Pattern and Rhythm are orthogonal — they cycle at their own lengths. When
they differ, the combined phrase repeats every `lcm(len-pat, len-rhy)` steps,
generating emergent complexity from simple data.

### Constructors

```clojure
;; Pattern — tagged vector; first element is the type tag
(pattern [:chord 1 3 5 2 4])        ; chord-relative, 1-indexed
(pattern [:scale 1 2 3 5 6 8])      ; scale-relative, 1-indexed
(pattern [:chromatic 0 2 4 7 9])    ; semitone offsets from *harmony-ctx* root
(pattern [:pitch :C4 :E4 :G4])      ; absolute pitches
(pattern [:midi  60  64  67])       ; raw MIDI integers

;; Rhythm — velocity vector; nil=rest, :tie=hold
(rhythm [100 nil 80 nil])
(rhythm [127 :tie :tie nil 90])

;; Euclidean rhythm shortcut
(rhythm-from-euclid 3 8)         ; tresillo: 3 onsets in 8 steps
(rhythm-from-euclid 5 8 90)      ; 5 in 8, velocity 90
(rhythm-from-euclid 5 8 100 1)   ; rotated by 1 position
```

### One-shot playback with `motif!`

`motif!` plays one full `lcm(pat-len, rhy-len)` cycle. Call from a
`deflive-loop` body.

```clojure
(deflive-loop :melody {:harmony (scale :C 4 :major)
                       :chord   (chord :C 4 :maj7)}
  (motif! (named-pattern :bounce)
          (named-rhythm  :tresillo)
          {:clock-div 1/8}))
```

Options:

| Option | Default | Description |
|---|---|---|
| `:clock-div` | `1/8` | Duration per step in beats |
| `:gate` | `0.9` | Note duration as fraction of `:clock-div` |
| `:probability` | `1.0` | Per-note fire probability 0.0–1.0 |
| `:channel` | nil | MIDI channel override |
| `:locks` | nil | `Locks` record for parameter overrides |
| `:xf` | nil | `ITransformer` applied to each event |

### Stateful step engine with `make-motif-state`

`make-motif-state` returns a `MotifState` that implements `IStepSequencer`,
usable with all `nous.seq` runners:

```clojure
(def my-motif
  (make-motif-state (named-pattern :bounce)
                    (named-rhythm  :tresillo)
                    :clock-div 1/8
                    :gate 0.8))

;; Inside a live-loop — identical to motif! but the state persists across iterations
(deflive-loop :melody {:harmony (scale :C 4 :dorian)}
  (run-cycle! my-motif))

;; Loop in background
(def handle (seq-loop! my-motif))
(stop-seq! handle)
```

`seq-cycle-length` returns `lcm(pat-len, rhy-len)` — or the three-way lcm
`lcm(lcm(pat, rhy), locks-len)` when a `Locks` record is attached.

### Parameter locks with `Locks`

```clojure
;; Locks cycle independently; a 4-step Locks against a 5-note Pattern and
;; 3-step Rhythm repeats every lcm(4,5,3) = 60 steps.
(def my-locks
  (locks [{:mod/cutoff 64}
          {}
          {:mod/cutoff 127 :mod/resonance 80}
          {}]))

(def my-motif
  (make-motif-state (pattern [:chord 1 3 5])
                    (rhythm   [100 nil 80])
                    :locks my-locks
                    :clock-div 1/8))
```

Or pass directly to `motif!`:

```clojure
(motif! (named-pattern :zigzag)
        (named-rhythm  :offbeat)
        {:clock-div 1/8
         :locks (locks [{} {:mod/cutoff 127} {} {}])})
```

### Pattern transformations

```clojure
(reverse-pattern   p)          ; reverse selector order
(rotate-pattern    p 2)        ; cyclic shift right by 2 steps
(transpose-pattern p 2)        ; add 2 to all numeric indices
(invert-pattern    p)          ; reflect around min+max of indices
```

Compose transformations for live variation:

```clojure
(deflive-loop :melody {:harmony (scale :C 4 :minor)}
  (motif! (-> (named-pattern :bounce) (rotate-pattern @bar-count))
          (named-rhythm :tresillo)
          {:clock-div 1/8}))
```

### Named pattern library

Built-in chord patterns: `:up`, `:down`, `:bounce`, `:alberti`, `:waltz-bass`,
`:broken-triad`, `:guitar-pick`, `:stride`, `:raga-alap`, `:root-fifth`,
`:root-octave`, `:shell-up`, `:shell-down`, `:montuno`.

Built-in scale patterns: `:scale-up`, `:scale-down`, `:scale-bounce`,
`:pentatonic`, `:zigzag`, `:thirds-up`, `:peak`.

Built-in chromatic patterns: `:chromatic-4`, `:chromatic-bl` (blues hexatonic).

```clojure
(pattern-names)   ; sorted list of all built-in names
(named-pattern :bounce)
```

### Named rhythm library

Built-in rhythms: `:tresillo`, `:cinquillo`, `:son-clave`, `:rumba-clave`,
`:bossa-nova`, `:four-on-floor`, `:straight-8`, `:straight-16`, `:euclid-5-16`,
`:euclid-7-16`, `:offbeat`, `:onbeat`, `:syncopated`, `:habanera`, `:waltz`,
`:shuffle`.

```clojure
(rhythm-names)    ; sorted list of all built-in names
(named-rhythm :tresillo)
```

### Pitch resolution

| Pattern type | How pitches resolve |
|---|---|
| `:chord` | `*chord-ctx*` — set via `use-chord!` or the `:chord` option on `deflive-loop`. 1-indexed; index beyond chord size wraps upward by octave. |
| `:scale` | `*harmony-ctx*` — set via `use-harmony!` or the `:harmony` option. 1-indexed; wraps via `IScale/pitch-at`. |
| `:chromatic` | `*harmony-ctx*` root — integer semitone offset; 0=root, negative ok. |
| `:pitch` | Keywords (`:C4`), `Pitch` records, or MIDI integers — resolved absolutely. |
| `:midi` | Raw MIDI integers — no context needed. |

---

## 43. Transaction Journal (`nous.journal`)

Every parameter write goes through the peer, which appends a row to a
WAL-mode SQLite `changes` table. `nous.journal` reads and queries that log
from Clojure, with no IPC round-trip — it opens the file directly via
`sqlite-jdbc`.

### Layer 1 — reading the raw log

```clojure
(def txs (read-journal "/path/to/session.sqlite"))
;; => [{:tx/id #uuid "..." :tx/beat 0.0 :tx/wall-ns 1234 :tx/source :schema
;;      :tx/path [:nous/schema :device-models :arp2600]
;;      :tx/before nil :tx/after {...} :tx/parent nil} ...]
```

`read-journal` seals all persistence artifacts at the boundary: source
integers → keywords (`:user`, `:loop`, `:trajectory`, etc.), EDN strings →
Clojure data, `tx_id` bytes → `java.util.UUID`. Everything above this call
sees only Clojure data.

### Layer 2 — query functions

All L2 functions take the vector from `read-journal` as their first argument.

```clojure
;; All writes to a path, chronological
(tx-history txs [:arp2600 :filter :cutoff])
;; => [{:tx/beat 1.0 :tx/after 0.5 ...} ...]

;; Value at a given beat (last write ≤ beat)
(tx-at txs [:arp2600 :filter :cutoff] 20.0)
;; => 0.8

;; Writes in a beat window; optional :source / :path filters
(tx-range txs 64.0 128.0 :source :user)

;; All writes attributed to one source
(tx-by-source txs :loop)

;; Set of paths written at least once
(active-paths (tx-range txs 64.0 128.0))

;; Final state fold
(latest-values txs)
;; => {[:arp2600 :filter :cutoff] 0.6 ...}
```

### Layer 3 — semantic transforms

#### `crystallize` — performance window to timeline

```clojure
(crystallize txs 64.0 128.0 :source :loop)
;; => {[:arp2600 :filter :cutoff] [{:beat 0.0 :value 0.5}
;;                                  {:beat 16.0 :value 0.8}]}
```

Beats are normalized relative to `beat-from`, so beat 64 → 0.0. Use this
to turn a live-performance window into trajectory or step-sequence material.

Options:
- `:source` — restrict to one source kind keyword
- `:schema?` — include `[:nous/schema ...]` paths (default `false`)

#### `diff-sessions` — compare two journal files

```clojure
(diff-sessions "tuesday.sqlite" "thursday.sqlite")
;; => {:added     {[:synth :lfo :rate] 2.0}
;;     :removed   {}
;;     :changed   {[:arp2600 :filter :cutoff] {:before 0.5 :after 0.7}}
;;     :unchanged #{[:arp2600 :filter :res]}}
```

### Session lifecycle

Four functions in `nous.core` (re-exported via `nous.user`) wrap the
journal for session save/restore:

| Function | Description |
|---|---|
| `export-session!` | Snapshot live session state to a loadable Clojure script (conventionally `.nous`) |
| `load-session-script!` | Evaluate a session script written by `export-session!` (system must be started) |
| `restore-session!` | Restore state in-process from a SQLite journal — crash recovery, no export needed |
| `export-from-journal!` | Reconstruct final state from a SQLite journal → an editable `.clj` script |

```clojure
;; Save the current session
(export-session! "friday-ambient.nous")

;; Reload it next session (must (start!) first)
(load-session-script! "friday-ambient.nous")

;; Or recover from the raw sidecar journal if you never exported:
(restore-session! "/path/to/session.sqlite")        ; apply the last state in-process, or
(export-from-journal! "/path/to/session.sqlite")    ; → session.sqlite-export.clj to inspect/edit
(load-session-script! "/path/to/session.sqlite-export.clj")
```

The `.nous` export format is fully-qualified, human-readable Clojure forms
(`nous.schema/defdevice-model`, `nous.ctrl/set!`, etc.) — diff-friendly
and safe to commit to version control.

---

---

## 44. Kairos CLAP Host (`nous.kairos`)

kairos is a C++ CLAP host that nous controls via a Unix domain socket. It
manages the plugin graph, MIDI routing, and Ableton Link transport for the
CLAP synthesis layer. Use kairos when you need audio synthesis or DSP graphs;
use aion when you need only MIDI + Link on low-power hardware.

### Connection

```clojure
(require '[nous.kairos :as kairos])

;; Launch kairos as a managed subprocess and connect
(kairos/start-kairos! :binary "/usr/local/bin/kairos")

;; Or connect to an already-running kairos instance
(kairos/connect!)
(kairos/connect! :socket-path "/tmp/my-kairos.sock")

;; Check connection
(kairos/connected?)  ; => true

;; Disconnect (does not kill a managed subprocess)
(kairos/disconnect!)

;; Kill managed subprocess and disconnect
(kairos/stop-kairos!)

;; Kill and relaunch using stored opts from last start-kairos! call
(kairos/restart-kairos!)
```

### MIDI output via kairos

```clojure
;; Send a note on channel 1, MIDI note 60, velocity 100, duration in ms
(kairos/send-note-on!  channel note velocity duration-ms)
(kairos/send-note-off! channel note velocity)

;; Send CC
(kairos/send-cc! channel cc-num value)

;; Send pitch bend (14-bit: 0=full down, 8192=centre, 16383=full up)
(kairos/send-pitch-bend! channel bend-value)

;; Send channel pressure
(kairos/send-channel-pressure! channel pressure)

;; Send SysEx bytes
(kairos/send-sysex! [0xF0 0x41 0x10 0xF7])
```

### MTS via kairos

```clojure
;; Send MTS Bulk Dump from a scala Scale record
(require '[nous.scala :as scala])
(def ms (scala/load-scl "31edo.scl"))
(kairos/send-mts! ms)

;; With a keyboard map and explicit slot/device
(kairos/send-mts! ms kbm :tuning-prog 0 :device-id :all)

;; From a raw {MIDI-note → Hz} freq-map
(kairos/send-mts! freq-map :tuning-prog 0)
```

### Plugin graph

```clojure
;; Load a plugin graph from EDN (produced by alembic or hand-written)
(kairos/send-graph-load! graph-edn)

;; Reset the plugin graph (unload all plugins)
(kairos/send-graph-reset!)

;; List plugins the kairos instance has discovered
(kairos/list-plugins!)
;; => [{:id "org.nomos-studio.alembic.mypatch" :name "mypatch" :version "1.0.0"} ...]

;; Get cached plugin registry (last result from list-plugins!)
(kairos/plugin-registry)
```

### Hot-swap WASM patch

Alembic-compiled WASM patches can be hot-swapped into a running graph:

```clojure
;; Send a new WASM binary to the running graph node identified by plugin-id
(kairos/send-wasm-hot-swap! plugin-id wasm-bytes)
```

### Ableton Link via kairos

When kairos is the Link peer, use these instead of `nous.link`:

```clojure
(kairos/send-link-set-tempo! 120.0)
(kairos/send-link-start-transport!)
(kairos/send-link-stop-transport!)
```

### RT modulator engine

kairos hosts a sample-accurate modulator engine with 20+ modulator types.
Start and stop modulators by name:

```clojure
;; Start a named modulator (modulator-edn is a map describing type and params)
(kairos/start-modulator! modulator-name modulator-edn)

;; Stop a named modulator
(kairos/stop-modulator! modulator-name)
```

### MIDI input from kairos

kairos/aion buffers incoming MIDI into `midi-in-messages`:

```clojure
;; Ring-buffer of recent MIDI messages (last 256)
@kairos/midi-in-messages
;; => [{:port 0 :channel 1 :data [0x90 60 100]} ...]

;; Block until a matching message arrives
(kairos/await-midi-message #(= 0x90 (first (:data %))))
(kairos/await-midi-message #(= 0x90 (first (:data %))) :timeout-ms 2000)
```

### Tick callbacks

kairos sends a tick on every MIDI clock pulse. Register lightweight callbacks:

```clojure
(def handle (kairos/on-tick! ::my-cb (fn [] (println "tick"))))
(kairos/off-tick! handle)
```

---

## 45. MTS Retune Arc (`nous.mts`)

`nous.mts` provides `retune-arc!` — a gradual, beat-accurate retune that
interpolates between two `{MIDI-note → Hz}` freq-maps and sends MTS Bulk Dumps
at each step via `kairos/send-mts!`. Interpolation is log-linear (cents space)
so each step covers a perceptually equal interval.

### Building freq-maps from Scala scales

```clojure
(require '[nous.scala :as scala]
         '[nous.mts   :as mts])

;; Parse Scala files or inline strings
(def partch-43 (scala/load-scl "partch-43.scl"))
(def tet-12    (scala/parse-scl "! 12-tet.scl\n12\n100.0\n200.0\n..."))

;; Build freq-maps (128-entry {MIDI-note → Hz} maps)
(def ji-map  (scala/scale->freq-map partch-43))
(def tet-map (scala/scale->freq-map tet-12))
```

### Point-in-time interpolation

```clojure
;; Blend at t ∈ [0.0, 1.0] — useful for manual or trajectory-driven retune
(def blended (mts/lerp-freq-maps ji-map tet-map 0.5))
(kairos/send-mts! blended)
```

### Scheduled retune arc

```clojure
(require '[nous.link :as link]
         '[nous.loop :as loop-ns])

;; 8-step JI→12-TET arc over 32 beats, starting on the next 32-beat boundary
(let [start (link/next-quantum-beat (loop-ns/-current-beat) 32)]
  (mts/retune-arc! ji-map tet-map start 32))

;; With options
(mts/retune-arc! ji-map tet-map start 64
                 :steps 16         ; more steps = smoother morph
                 :tuning-prog 0    ; MTS program slot
                 :device-id :all)  ; or an integer device ID

;; retune-arc! returns a future — deref to wait for completion
@(mts/retune-arc! ji-map tet-map start 32)
```

Step spacing: at 120 BPM, 1 beat = 500 ms. The default 8 steps over 32 beats
gives one MTS dump every 4 beats (~2 s) — enough time for the synthesiser to
process each dump. Increase `:steps` for a smoother morph; decrease if CPU
or MIDI bandwidth is constrained.

### berlin/tuning-morph! (higher-level)

For direct Scale-to-Scale morphing in the Berlin vocabulary, use
`berlin/tuning-morph!` instead — it sends MTS at each bar boundary and
stops automatically when complete:

```clojure
(require '[nous.berlin :as berlin])

(berlin/tuning-morph! (scala/load-scl "12-tet.scl")
                      (scala/load-scl "carlos-alpha.scl")
                      32 nil)   ; 32 bars, no KBM
```

---

## 46. Berlin School Vocabulary (`nous.berlin`)

`nous.berlin` captures the structural grammar of Berlin School / Kosmische
music — slowly mutating ostinati, portamento glides, filter journeys, tape
drift, and Frippertronics-style SOS accumulation.

### Ostinato

An ostinato is a mutating repeating step sequence. Mutation fires at each
complete pass; the rhythmic skeleton (step count, durations) is preserved
across all mutation modes.

```clojure
(require '[nous.berlin :as berlin])

;; Build an ostinato context
(def ost
  (berlin/ostinato
    [{:pitch/midi 62 :dur/beats 1/8}
     {:pitch/midi 65 :dur/beats 1/8}
     {:pitch/midi 69 :dur/beats 1/8}
     {:pitch/midi 72 :dur/beats 1/4}]
    {:scale         (make-scale :D 4 :dorian)
     :mutation-rate 0.12   ; probability per note per pass
     :drift         :up    ; :up, :down, or :random
     :microtonal    true   ; per-note cent drift
     :gravity       0.7})) ; pull back toward equal temperament

;; Advance in a live loop
(deflive-loop :seq {}
  (play! (berlin/next-step! ost))
  (sleep! 1/8))
```

#### Mutation modes

| Function | Mode | Behaviour |
|---|---|---|
| `freeze-ostinato!` | — | Stop all mutation; pattern repeats exactly |
| `thaw-ostinato!` | :random-walk | Resume random-walk at rate (default 0.1) |
| `deflect-ostinato!` | :teleological | Drift toward a target pattern over passes |
| `crystallize!` | :crystallize | Pull any out-of-set note toward nearest target note |
| `dissolve!` | :dissolve | Notes drift away from current degrees (fragmentation) |
| `reset-ostinato!` | — | Reset position to step 0 (re-sync to beat boundary) |

```clojure
;; Freeze, then deflect toward a new pattern
(berlin/freeze-ostinato! ost)
(berlin/deflect-ostinato! ost
  [{:pitch/midi 64 :dur/beats 1/8}
   {:pitch/midi 67 :dur/beats 1/8}
   {:pitch/midi 71 :dur/beats 1/8}
   {:pitch/midi 74 :dur/beats 1/4}]
  {:rate 0.2})

;; Crystallize toward a target (pulls notes to nearest target pitch)
(berlin/crystallize! ost target-pattern {:rate 0.25})

;; Begin fragmentation
(berlin/dissolve! ost {:rate 0.2})
```

#### Trajectory-driven mutation rate

```clojure
(require '[nous.trajectory :as traj])

;; Rate rises from 0.02 to 0.25 over 64 passes
(berlin/set-mutation-trajectory! ost
  (traj/trajectory :smooth-step 0 0.02 1 0.25)
  64)
```

### Portamento

```clojure
;; Enable portamento on channel 1, 120 ms glide time
(berlin/set-portamento! 1 120)

;; Disable
(berlin/set-portamento! 1 0)

;; Scoped: enable for the play! call then disable
(deflive-loop :glide {}
  (berlin/with-portamento 1 80
    (play! (berlin/next-step! ost)))
  (sleep! 1/4))
```

### Filter journey

A slow CC sweep over N bars driven by a trajectory curve. Launches a
background loop that stops itself when complete.

```clojure
;; Open filter cutoff from 0→127 over 64 bars with a breathing curve
(berlin/filter-journey! [:filter :cutoff] 74 1 0 127 64 :breathe)
;; curves: :breathe (default), :linear, :smooth-step, :bounce
```

### Tuning morph

Interpolate between two Scala scales over N bars, sending MTS at each bar:

```clojure
(berlin/tuning-morph! (scala/load-scl "12-tet.scl")
                      (scala/load-scl "carlos-alpha.scl")
                      32 nil)  ; 32 bars, no KBM
```

### Phase drift

Register a fractional tempo offset between two loops:

```clojure
;; Loop :seq-b runs 1% slower than master tempo
(berlin/phase-drift! :seq-b 0.01)
(berlin/clear-drift! :seq-b)

;; Use journey/phase-pair to find when incommensurate loops re-align
(require '[nous.journey :as journey])
(journey/phase-pair 12 17)
;; => {:lcm 204 :alignment-beats [204 408 612 816 1020]
;;     :drift-rate 0.0294 :at-bpm {:bpm 100 :minutes 2.04}}
```

### Tape drift

Wrap an ostinato with tape-style pitch wobble (worn tape simulation):

```clojure
(def drifting-ost (berlin/tape-drift ost 12))  ; ±12 cents max drift

(deflive-loop :tape {}
  (play! (berlin/tick-tape! drifting-ost))
  (sleep! 1/8))
```

### Frippertronics SOS (Sound on Sound)

Models the Fripp/Eno tape loop technique using `nous.temporal-buffer`:

```clojure
;; Create a SOS accumulation buffer
(def sos (berlin/frippertronics! :sos
           {:zone            :z6   ; 64-beat tape loop
            :feedback        0.75
            :max-generation  12
            :color           :tape}))

;; Feed and play simultaneously
(def ost-a (berlin/tape-drift ost 8))
(deflive-loop :layer-a {}
  (play! (berlin/sos-send! sos (berlin/tick-tape! ost-a)))
  (sleep! 1/4))

;; After 64 beats, layer-a begins accumulating.
;; Add a second layer on top:
(deflive-loop :layer-b {}
  (play! (berlin/sos-send! sos (berlin/next-step! ost)))
  (sleep! 1/8))
```

Color presets: `:tape` (vel×0.95, dur×0.98, ±10¢ flutter), `:dark`
(vel×0.90, dur×1.15, −5¢ sag), `:warm` (vel×0.93, dur×1.05).

---

## 47. Harmonic Excursion Arc (`nous.excursion`)

`nous.excursion` implements the most characteristic Partch compositional
shape: a five-phase arc that gives complex JI intervals their weight by
framing them with established consonance.

```
Phase 1: GROUND     — establish the tonic region (low Tenney distance)
Phase 2: DEPARTURE  — move outward, increasing Tenney distance
Phase 3: EXCURSION  — reach the far territory (alien frontier)
Phase 4: RETURN     — follow gravity back toward origin
Phase 5: RESOLUTION — arrive at the tonic with perceptible weight
```

### Defining an excursion arc

```clojure
(require '[nous.excursion :as exc])

(defexcursion f-sharp-arc
  :fundamental :F#2
  :region {:otonal-limit 11 :utonal-limit 7 :tenney-limit 6.5}
  :arc {:ground     {:steps 8}
        :departure  {:steps 6  :target-tenney 5.0}
        :excursion  {:steps 4}
        :return     {:steps 8  :target-tenney 1.5}
        :resolution {:steps 2  :approach :septimal :to [1 1]}}
  :repeat true)
```

`defexcursion` creates a var bound to an atom and registers it at
`[:excursion :f-sharp-arc]` in the ctrl tree.

### Advancing and output keys

```clojure
(exc/next-step! f-sharp-arc)
;; => {:pitch/voct         ...        ; V/oct from fundamental (JI, not ET)
;;     :pitch/midi         42         ; nearest MIDI note
;;     :dur/beats          4.0        ; step duration
;;     :gate/on?           true
;;     :lattice/point      [3 2]      ; [m n] current lattice position
;;     :lattice/tenney     2.585      ; Tenney H (log₂(mn))
;;     :lattice/otonality  0.58       ; [0,1] otonal character
;;     :excursion/phase    :ground
;;     :excursion/step     0}         ; step within phase
```

In a live loop — the step already contains `:pitch/midi` and `:dur/beats`, so
`play!` + `sleep!` consume it naturally:

```clojure
(deflive-loop :excursion-voice {}
  (let [step (exc/next-step! f-sharp-arc)]
    (play! step)
    (sleep! (:dur/beats step))))
```

### Phase control

```clojure
;; Jump to a named phase immediately
(exc/skip-to-phase! f-sharp-arc :excursion)

;; Reset to ground phase, step 0
(exc/restart! f-sharp-arc)
```

### Phase config reference

```clojure
:arc {:ground     {:steps 8
                   :positions [[1 1] [5 4] [3 2]]  ; optional fixed cycle
                   :mode :gravity}                   ; default
      :departure  {:steps 6
                   :target-tenney 5.0               ; exit early if H ≥ this
                   :mode :expand}
      :excursion  {:steps 4
                   :mode :random-walk               ; or :gravity :expand
                               ;  :otonal-step :utonal-step
                   :otonality 0.5}
      :return     {:steps 8
                   :target-tenney 1.5               ; exit early if H ≤ this
                   :mode :gravity}
      :resolution {:steps 2
                   :approach :septimal              ; or :direct :supertonic
                               ;  :overtone :diatonic
                   :to [1 1]}}
```

### Duration modes

`:duration` controls how long each step lasts:

```clojure
;; Fixed
:duration 4.0

;; Tenney-modulated — longer duration at higher harmonic distance
:duration {:mode :tenney-modulated :base-beats 8.0 :scale :inverse}

;; Probability — hold until a Bernoulli event fires
:duration {:mode :probability :p 0.1 :min-beats 1.0}
```

### Voice name — inter-voice harmony

When used with `defensemble`, set `:voice-name` to publish pitch and motion
to the harmony bus so ensemble peers can monitor this voice:

```clojure
(defexcursion bass-arc
  :fundamental :C2
  :voice-name :bass
  ...)
```

---

## 48. Journey Conductor (`nous.journey`)

`nous.journey` provides the macro-temporal structure for long-form Berlin
School compositions: a global bar counter, a journey conductor that fires
transition functions at scheduled bars, and utilities for working with
incommensurate loop relationships.

### Global bar counter

```clojure
(require '[nous.journey :as journey])

;; Start the counter — increments every 4 beats (or beats-per-bar if set)
(journey/start-bar-counter!)
(journey/start-bar-counter! 3)   ; 3/4 time

;; Query
(journey/current-bar)   ; => 47

;; Reset without stopping
(journey/reset-bar-counter!)

;; Stop
(journey/stop-bar-counter!)
```

The bar counter is a live loop (`:journey/bar-counter`) — it updates
continuously and can be read from any other loop.

### Journey conductor

`start-journey!` takes a timeline of `[bar fn]` pairs and fires each
function exactly once when that bar is reached:

```clojure
(journey/start-journey!
  [[0   #(begin-emergence!)]
   [32  #(open-filter!)]
   [64  #(crystallise-ostinato!)]
   [128 #(begin-dissolution!)]]
  {:on-end #(println "Journey complete")})

;; Stop the conductor loop
(journey/stop-journey!)
(journey/stop-journey! :my-named-conductor)  ; if started with :name
```

### Phaedra arc scaffold

The canonical four-movement timeline for a ~20-minute journey at 100 BPM:

```clojure
(let [arc   (journey/phaedra-arc)
      tl    (:timeline arc)
      ;; (:timeline arc) => [[0 :begin-emergence] [32 :open-filter-1] ...]
      ;; Replace keyword placeholders with your transition functions:
      fns   {:begin-emergence  #(begin-emergence!)
             :open-filter-1    #(open-filter-1!)
             :crystallise      #(crystallise!)
             :enter-development #(enter-development!)
             ,,,}
      tl+fn (mapv (fn [[bar kw]] [bar (get fns kw #(println kw))]) tl)]
  (journey/start-journey! tl+fn))

;; Movement ranges
(:movements (journey/phaedra-arc))
;; => {:emergence [0 64] :crystallise [48 80]
;;     :development [64 240] :dissolution [240 320]}
```

### Phase-pair analysis

Two loops of incommensurate length drift in and out of phase. Calculate
their alignment events before you start:

```clojure
(journey/phase-pair 12 17)
;; => {:lcm 204
;;     :alignment-beats [204 408 612 816 1020]
;;     :drift-rate 0.02941...
;;     :at-bpm {:bpm 100 :minutes 2.04}}

;; Classic Rubycon-style pairs
(journey/phase-pair 16 23)  ; LCM 368  ≈ 3.7 min
(journey/phase-pair 12 19)  ; LCM 228  ≈ 2.3 min
(journey/phase-pair 17 23)  ; LCM 391  ≈ 3.9 min
```

Use these pairs as loop lengths in `deflive-loop` to build the drift
texture characteristic of Tangerine Dream and Klaus Schulze.

### Humanise

Apply analogue sequencer micro-timing and velocity variance to a step:

```clojure
;; Default: ±6 ms timing, ±8 velocity
(journey/humanise step)

;; Custom
(journey/humanise step {:timing-variance-ms 10
                        :velocity-variance   12
                        :timing-bias         :late})

;; In a loop
(deflive-loop :ost-humanised {}
  (play! (journey/humanise (berlin/next-step! ost)))
  (sleep! 1/8))
```

---

## 49. Terrain Sequencer (`nous.terrain`)

`nous.terrain` lifts the `nous.fractal` 2D chain into a 3D space addressed
by three continuous phasors. Where a fractal sequence is a 1D path through a
transform tree, a terrain is a 2D cross-section through that tree that can be
navigated live.

```
X ∈ [0,1) — position within the sequence (step index)
Y ∈ [0,1) — depth into the transform tree (0 = trunk, 1 = max-depth)
Z ∈ [0,1) — branch selection (decoded as base-N fraction)
```

Adjacent branches are linearly blended at each Z value, so moving Z
continuously produces smooth timbral morphs rather than hard cuts.

### Defining a terrain

```clojure
(require '[nous.terrain :as terrain])

(defterrain seabed
  :trunk  [{:pitch/midi 36 :dur/beats 1/2 :gate/on? true :gate/len 0.7}
           {:pitch/midi 40 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
           {:pitch/midi 43 :dur/beats 1/4 :gate/on? true :gate/len 0.5}]
  :transforms [:reverse :inverse :mutate]
  :max-depth  4)
```

The `:trunk` is the base sequence (equivalent to the fractal trunk). `:transforms`
are the available child operations at each tree node. `:max-depth` controls how
deep the Y axis reaches.

### Stepping

```clojure
;; Single step — reads Y and Z from ctrl tree (both default 0.0 = trunk)
(terrain/next-terrain-step! seabed)
;; => {:pitch/midi 36 :dur/beats 0.5 :gate/on? true ...}
```

### Navigating with the ctrl tree

Y and Z are registered as ctrl-tree float nodes, so any ctrl binding,
`trajectory` automation, or `ctrl/set!` call can drive them:

```clojure
(require '[nous.ctrl :as ctrl])

;; Dive into the transform tree over 16 bars
(ctrl/set! [:terrain :seabed :y] 0.0)
(trajectory/run! [:terrain :seabed :y] {:to 0.8 :bars 16 :shape :smooth-step})

;; Crossfade between branches
(ctrl/set! [:terrain :seabed :z] 0.0)
(trajectory/run! [:terrain :seabed :z] {:to 0.5 :bars 8 :shape :linear})
```

### Inside a live loop

`make-terrain-seq` returns an `IStepSequencer`, so it composes with `run-step!`:

```clojure
(deflive-loop :depth {}
  (run-step! (terrain/make-terrain-seq seabed))
  (sleep! 1/8))
```

### Terrain as a higher-dimensional arpeggiator

A terrain with `:max-depth 1` and no blending is equivalent to a 2D arpeggiator:
X selects the step, Z selects the transform variant. As depth and blending increase,
the space becomes a continuously morphable texture source.

---

## 50. Book of Sounds (`nous.book`)

`nous.book` is a harmonic-series sequencer inspired by Hans Otte's
*Das Buch der Klänge* (Book of Sounds, 1979–82). It treats the overtone
series above a fundamental as the pitch vocabulary, organises pitches into
named pages (harmonic regions), and navigates between them under
gravity-weighted selection.

This makes it a natural fit for JI performance: each harmonic is a pure
ratio above the fundamental, and gravity pulls repeated visits toward
lower-order (more consonant) harmonics.

### Defining a book

```clojure
(require '[nous.book :as book])

(defbook resonant-space
  :fundamental :C2
  :pages [{:name :ground   :harmonics [1 2 3 4]    :gravity 1.0}
          {:name :outer    :harmonics [7 9 11 13]  :gravity 0.3
           :selection {:mode :proximate}}]
  :navigation {:initial :ground :mode :manual})
```

`:harmonics` lists partial numbers (1 = fundamental, 2 = octave, 3 = fifth
above that, 7 = harmonic seventh, 11 = undecimal tritone, etc.).
`:gravity` biases repeated selection toward lower-numbered harmonics within
the page. `:selection :mode :proximate` picks the harmonic closest in
frequency to the previous step rather than by gravity alone.

### Stepping

```clojure
;; Advance to the next step in the current page
(book/next-step! resonant-space)
;; => {:pitch/voct 0.585 :pitch/midi 55 :dur/beats 4.0
;;     :book/harmonic 3   :book/gravity 0.12 :gate/on? true}

;; Jump to a different page (takes effect at the next call to next-step!)
(book/go-page! resonant-space :outer)
```

### Inside a live loop

```clojure
(deflive-loop :harmonics {}
  (run-step! (book/make-book-seq resonant-space))
  (sleep! 1))
```

### Cell mode

Cell mode commits a fixed-length motif from the current page and replays it
with slow gravity-directed drift — the book evolves over time without sudden
page changes:

```clojure
(defbook patient-cell
  :fundamental :F#2
  :output-mode :cell
  :cell-len    4
  :drift-prob  0.08     ; probability per step of drifting one harmonic
  :drift-rate  :per-pass
  :pages [{:name :ground :harmonics [1 2 3 4]   :gravity 1.0}
          {:name :outer  :harmonics [7 11 13 15] :gravity 0.2}])
```

In cell mode, `next-step!` cycles through the committed motif. When drift
fires, one step is replaced by a harmonically adjacent pitch, slowly steering
the cell toward or away from the `:outer` page according to gravity.

### Combining with excursion arcs

`nous.book` and `nous.excursion` address JI space differently:

- **`nous.book`** navigates the overtone series above a fixed fundamental;
  gravity biases toward simpler ratios; the harmonic identity of each pitch
  is explicit (partial number in step map)
- **`nous.excursion`** navigates a 2D Tenney-distance field and explicitly
  stages a five-phase dramatic arc (ground → departure → excursion → return →
  resolution)

For the SimSelections / Fate piece workflow, a book provides the note-level
vocabulary while an excursion arc controls which harmonic region is current.

---

*nous is released under EPL-2.0 (Clojure library) and LGPL-2.1 (C++ peers: kairos and aion).*
*See [doc/licensing.md](licensing.md) for the full licensing strategy.*
