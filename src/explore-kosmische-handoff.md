# Handoff: explore/kosmische

This document is a session handoff. Read it in full before doing anything else.
Working directory: `~/Documents/org/areas/cljseq/src/cljseq` (main branch, v0.10.0).

---

## What we're doing

Setting up and beginning `explore/kosmische` — a new exploration branch off v0.10.0
main. This is a compositional vocabulary exploration, not a feature sprint. The goal
is to build a playable Phaedra/Rubycon-style session from the REPL, driven live
via the MCP bridge.

---

## Where we came from

`explore/berlin-school` was a spike branch (diverged from v0.1.0 era) that grew
beyond its original framing into five distinct aesthetic territories. Decision made:
**abandon in place as an archive**. Never rebase, never merge. Use as reference only.

The berlin-school archive contains (all spike quality, no tests):
- `cljseq.berlin` — ostinato mutation (6 modes), filter-journey!, phase-drift!, tuning-morph!
- `cljseq.journey` — global bar counter, journey conductor, phase-pair analysis, humanise
- `cljseq.ambient` — Eno generative field, Basinski tape degrade, probability decay
- `cljseq.dub` — pump shapes, echo-throw!, send-journey!, strip-down!
- `cljseq.tape` — Frippertronics SOS, varispeed, scatter-phrase
- `cljseq.formula` — Surge XT Formula Lua generation

`explore/kosmische` is the first dedicated thread, picking up the Kosmische/doom
vocabulary. The other threads (eno-field, dub-techno, tape-loops, surge-xt) follow
in later sessions.

---

## Aesthetic vision

The branch spans a personal synthesis — not a recreation of classic TD:

**Tonal lineage:** TD's *Zeit* (1972) and *Green Desert* (1973/released 1986) as the
dark foundation — pre-sequencer, drone-based, unsettling. Schulze's *Irrlicht* /
*Cyborg* as the parallel dark line. Phaedra/Rubycon as the crystallised sequencer
grammar that emerged from that darkness. TD's Canyon Dreams / Tyranny of Beauty era
as the cinematic counter-register — melodic light that makes the darkness heavier.

**Heavy inflection:** Black Sabbath's Phrygian/Locrian tritone palette and Sunn O)))'s
insight that doom slowed to stasis becomes dark ambient. The structural grammar is
identical to Kosmische — long-form trajectory evolution, perceptual threshold crossings,
patience.

**Compositional implications:**
- Locrian / Phrygian dominant / harmonic minor alongside Dorian
- Tritone as structural interval, not passing tension
- Tempo range extended down toward doom (60–80 BPM) as well as TD pulse (100–120)
- Drone as tectonic (sub-bass, very slow filter arc, weight) not atmospheric
- Four-movement arc: Zeit-darkness → Rubycon crystallisation → Development → Tyranny dissolution

---

## What to build this session

### Step 1 — Create the worktree and branch

```sh
git worktree add \
  ~/Documents/org/areas/cljseq/worktrees/cljseq/explore-kosmische \
  -b explore/kosmische
```

Work in the worktree. The primary checkout (`src/cljseq/`) stays on main.

### Step 2 — Port cljseq.journey (first infrastructure piece)

Source: `git show explore/berlin-school:src/cljseq/journey.clj`

Port to production quality:
- `src/cljseq/journey.clj` — carry all public functions; mark status as production (not SPIKE)
- `test/cljseq/journey_test.clj` — write tests for all public functions
- `src/cljseq/user.clj` — export: `start-bar-counter!`, `stop-bar-counter!`,
  `reset-bar-counter!`, `current-bar`, `start-journey!`, `stop-journey!`,
  `phase-pair`, `humanise`, `phaedra-arc`

Key functions to cover in tests:
- `start-bar-counter!` / `current-bar` — increments every N beats
- `start-journey!` — fires transition fns at scheduled bars, each fires exactly once
- `phase-pair` — LCM, drift-rate, alignment-beats calculations (pure fn, easy to test)
- `humanise` — applies timing/velocity variance to a step map (pure fn)
- `phaedra-arc` — returns a valid timeline map (structural test)

### Step 3 — Port cljseq.berlin

Source: `git show explore/berlin-school:src/cljseq/berlin.clj`

Port to production quality with tests. Key API surface:
- `ostinato` — creates an ostinato state record
- `next-step!` — advances ostinato by one step (returns a play! step map)
- `crystallize!` / `dissolve!` / `deflect-ostinato!` — compositional steering
- `set-mutation-trajectory!` — mutation rate as a trajectory over passes
- `filter-journey!` — long filter arc via send-journey pattern
- `with-portamento` — scoped CC 65/5 automation
- `phase-drift!` / `clear-drift!` — intentional phase offset between voices
- `tuning-morph!` — MTS scale interpolation over bars

Export all public functions from `cljseq.user`.

### Step 4 — Engage the MCP bridge

Once journey + berlin are ported and tests pass:

```sh
# Terminal 1 — in the kosmische worktree
cd ~/Documents/org/areas/cljseq/worktrees/cljseq/explore-kosmische
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home lein repl :start :port 7888

# Terminal 2 — MCP server
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home lein mcp --nrepl-port 7888
```

### Step 5 — First MCP evaluation: Rubycon polyrhythmic core

Use the MCP `evaluate` tool to run the sketch from the design doc:

```clojure
;; Verify the phase-pair calculation first
(journey/phase-pair 12 17)
;; => {:lcm 204 :drift-rate ... :at-bpm {:bpm 100 :minutes 2.04} ...}

;; Then run the two-voice core
(def rubycon-scale (make-scale :D 3 :dorian))

(def ost-a (berlin/ostinato
  [{:pitch/midi 38 :dur/beats 1/4}
   {:pitch/midi 40 :dur/beats 1/4}
   {:pitch/midi 41 :dur/beats 1/2}
   {:pitch/midi 43 :dur/beats 1/4}]
  {:scale rubycon-scale :mutation-rate 0.1}))

(def ost-b (berlin/ostinato
  [{:pitch/midi 50 :dur/beats 1/3}
   {:pitch/midi 53 :dur/beats 1/3}
   {:pitch/midi 55 :dur/beats 1/3}]
  {:scale rubycon-scale :mutation-rate 0.08}))

(deflive-loop :voice-a {:channel 1 :restart-on-bar true}
  (play! (berlin/next-step! ost-a))
  (sleep! 12))

(deflive-loop :voice-b {:channel 2 :restart-on-bar true}
  (play! (berlin/next-step! ost-b))
  (sleep! 17))
```

The `:restart-on-bar true` on both loops ensures they start phase-locked.
Verify the drift is perceptible over several minutes before building the
filter-journey! layer on top.

---

## Key design doc references (on the archive branch)

```sh
git show explore/berlin-school:doc/journey-composition-design.md
git show explore/berlin-school:doc/ostinato-design.md
```

The journey design doc contains the full four-movement arc mapping, the Rubycon
sketch, the five identified gaps (bar counter, phase drift, humanise wiring,
drone-journey!, harmonic grammar across voices), and the philosophical framing.

---

## Identified gaps from the archive to close as we go

1. ~~Bar counter integration~~ — carried into cljseq.journey production port
2. **Phase drift declaration** — `phase-pair` exists; surface in conductor API
3. **Humanise wiring** — wire `humanise` into the loop before `play!`; `:timing/offset-ms` key already in step map
4. **`drone-journey!`** — pair `start-drone!` (ambient.clj) with `filter-journey!`; write as new function in `cljseq.berlin` or `cljseq.ambient`
5. **Harmonic grammar across voices** — shared `harmonic-context` atom constraining concurrent ostinati; this is a session of its own

---

## Org / planning context

Living tracking: `~/Documents/org/areas/cljseq/index.org`
- Exploration backlog section has all five threads with TODO items
- Notes section has the full aesthetic vision and MCP workflow protocol
- Journal entries capture session decisions

Memory files (auto-loaded):
- `project_kosmische_aesthetic.md` — tonal palette, reference listening, how to apply
- `project_berlin_school_spike.md` — archive inventory
- `project_mcp_bridge.md` — MCP tool vocabulary and build-order rationale
