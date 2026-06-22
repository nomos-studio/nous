# bwosc — Bitwig Studio nous Integration

## Status

Design document. Pre-implementation. See `design-studio-orchestration.md` for the
broader three-host studio model that bwosc serves.

---

## Overview

bwosc is a **Bitwig Studio Controller Extension** — a Java class loaded directly
into Bitwig's embedded JVM when the controller is activated. It is a **full ctrl
tree peer**: it owns the `[:bitwig ...]` subtree in the main nous ctrl tree, pushes
all Bitwig state changes into it in real time, and receives writes back as Bitwig API
calls. No polling. No mounted remote subtree under `[:peers ...]`. `[:bitwig ...]` is
a first-class namespace in the ctrl tree, indistinguishable from locally-owned paths.

Key properties:

- **Platform-native**: runs inside Bitwig's JVM on any platform Bitwig supports
  (macOS, Linux, Windows). No nous binary required on the Bitwig host.
- **Full ctrl tree peer**: participates via the standard OSC peer subscription protocol
  from `design-distributed-embedded.md` — the same mechanism any non-Clojure peer uses.
  No nREPL, no Clojure knowledge required in bwosc.
- **`nous.bitwig` companion namespace**: a thin adapter in nous that subscribes to
  bwosc's ctrl tree subtree and dispatches writes back as OSC. Consistent with the
  pattern established by `nous.kairos`.
- **Standalone repo**: `github.com/nous/bwosc` — Java Maven project, separate from nous.

---

## Architecture

OSC carries all ctrl tree traffic in both directions. One HTTP server handles
diagnostics and the initial snapshot on connect.

```
┌────────────────────────────────────────────────────┐
│  nous (Mac Mini / any host)                        │
│                                                    │
│  ctrl tree: [:bitwig :track :lead-synth :volume]   │
│             [:bitwig :transport :playing]          │
│             ...                                    │
│                                                    │
│  nous.bitwig                                       │
│    connect!: OSC /nous/bitwig/sub [:bitwig] ───────┼──→ bwosc OSC port 7179
│    watch dispatch: OSC /nous/bitwig/val <p> <v> ───┼──→ bwosc OSC port 7179
│                                                    │
│  nous OSC listener (port 57120) ←─────────────────┼── bwosc /nous/bitwig/val <p> <v>
└────────────────────────────────────────────────────┘
              ↑↓  OSC UDP — both planes, same protocol
┌────────────────────────────────────────────────────┐
│  bwosc (inside Bitwig's JVM)                       │
│                                                    │
│  Bitwig observer callbacks → OSC push:             │
│    volume.addValueObserver(v ->                    │
│      osc.send(nousAddr, "/nous/bitwig/val",        │
│               encodePath(...), v))                 │
│                                                    │
│  OSC server (port 7179):                           │
│    /nous/bitwig/sub <path> → register subscriber   │
│    /nous/bitwig/val <path> <v> → Bitwig API call   │
│                          via Host.scheduleTask()   │
│                                                    │
│  UDP beacon sender (239.255.43.99:7743)            │
│  HTTP server (port 7178) — diagnostics + snapshot  │
└────────────────────────────────────────────────────┘
```

Both planes use the same OSC message convention from `design-distributed-embedded.md §5`:

```
/nous/<node-id>/sub <path-edn>           ; nous → bwosc: subscribe to subtree
/nous/<node-id>/val <path-edn> <value>   ; bwosc → nous: push state change
/nous/<node-id>/val <path-edn> <value>   ; nous → bwosc: write (command)
```

The `/val` message is uniform in both directions. The `/sub` message registers
nous as a subscriber; bwosc records the source address and pushes all subsequent
`[:bitwig ...]` changes to it.

### State plane: Bitwig → nous ctrl tree

Bitwig's observer model fires a callback for every observable property change.
Each callback sends an OSC `/val` message to all registered subscribers:

```java
volume.addValueObserver(v ->
    osc.send(subscriberAddr, "/nous/bitwig/val",
             encodePath(":bitwig", ":track", trackKw, ":volume"), v)
);
```

`encodePath` serialises the path as a space-separated EDN string
(`":bitwig :track :lead-synth :volume"`) that nous.osc deserialises back to a
Clojure vector. The nous OSC listener receives the message and applies it to the
ctrl tree via `ctrl/set!`. No Clojure knowledge required in bwosc.

### Command plane: nous → Bitwig

Writes to `[:bitwig ...]` paths flow through `nous.bitwig`, which registers ctrl
watches on the writable subset. Each watch dispatch sends an OSC `/val` to bwosc:

```clojure
(defn- dispatch-write! [path value]
  (osc/send! bwosc-addr "/nous/bitwig/val"
             (osc-encode-path path) value))
```

bwosc's OSC server receives the `/val`, resolves the path keyword back to a Bitwig
entity via `NameNorm`, and queues the API call on the controller thread:

```java
oscServer.addHandler("/nous/bitwig/val", (path, value) ->
    host.scheduleTask(() -> {
        String[] parts = decodePath(path);  // [":bitwig", ":track", ":lead-synth", ":volume"]
        resolveAndSet(parts, value);
    }, 0)
);
```

The programmer-facing API is identical for local and remote targets:

```clojure
;; These all go through ctrl tree → nous.bitwig watch → OSC → bwosc → Bitwig API
(ctrl/set! [:bitwig :track :lead-synth :volume] 0.8)
(ctrl/set! [:bitwig :scene :drop :launch!] true)
(ctrl/set! [:bitwig :transport :play!] true)
(ctrl/set! [:bitwig :track :lead-synth :device :surge-xt :param :filter-cutoff :value] 0.65)
```

### Echo suppression

When nous writes a value via OSC, Bitwig fires the observer, which would push the
same value back to nous — a feedback loop. bwosc tracks the last value it received
*from nous* per path and skips the push if the observer fires with the same value
within a short window (default 50ms). This is the standard technique for any
bidirectional parameter binding.

### HTTP server role

1. **Initial snapshot**: `GET /snapshot` returns the full `[:bitwig ...]` subtree
   as EDN. `nous.bitwig/connect!` fetches this once on startup to populate the ctrl
   tree before the OSC subscription stream fills it in incrementally.
2. **Diagnostic inspection**: ad-hoc `GET /ctrl/bitwig/track/lead-synth/volume`
   from a browser or curl. Not part of the normal operating path.

### UDP beacon

bwosc broadcasts a standard nous peer beacon:

```edn
{:node-id      :bitwig
 :role         :daw
 :osc-port     7179
 :http-port    7178
 :version      "0.1.0"
 :timestamp-ms 1750000000000}
```

The main nous node discovers bwosc via `peer/start-discovery!`. `nous.bitwig/connect!`
reads `:osc-port` from the peer registry, sends `/sub`, fetches the HTTP snapshot,
and the subscription stream takes over. No new peer primitive needed beyond what
`peer/peer-info` already provides.

---

## `nous.bitwig` companion namespace

`nous.bitwig` is the ctrl tree adapter for bwosc — the same pattern as `nous.kairos`
for kairos. It lives in nous (not bwosc) and handles:

1. Subscribing to bwosc's `[:bitwig ...]` subtree via OSC `/sub` on startup
2. Fetching an initial snapshot via HTTP and bulk-applying it to the ctrl tree
3. Registering ctrl tree watches on writable paths and dispatching changes to bwosc via OSC
4. High-level compositional API that reads naturally in session code

```clojure
(ns nous.bitwig
  "Ctrl tree adapter for bwosc — Bitwig Studio peer.
  Subscribes to [:bitwig ...] over OSC and dispatches writes back to bwosc.
  Start with (bitwig/connect!) after bwosc is discovered via peer/start-discovery!."
  (:require [nous.ctrl :as ctrl]
            [nous.osc  :as osc]
            [nous.peer :as peer]))

(defn connect!
  "Subscribe to bwosc, load initial snapshot, register write watches."
  ([] (let [{:keys [host osc-port http-port]} (peer/peer-info :bitwig)]
        (connect! host osc-port http-port)))
  ([host osc-port http-port]
   (reset! *bwosc-addr* {:host host :port osc-port})
   (load-snapshot! host http-port)   ; bulk HTTP fetch → ctrl tree
   (osc/send! @*bwosc-addr* "/nous/bitwig/sub" (osc-encode-path [:bitwig]))
   (register-watches!)
   nil))

(defn- dispatch-write! [path value]
  (when-let [addr @*bwosc-addr*]
    (osc/send! addr "/nous/bitwig/val" (osc-encode-path path) value)))

;; High-level API — all delegate to ctrl/set!, which triggers the watch dispatch
(defn set-volume!   [track vol]  (ctrl/set! [:bitwig :track track :volume] vol))
(defn mute!         [track]      (ctrl/set! [:bitwig :track track :muted] true))
(defn unmute!       [track]      (ctrl/set! [:bitwig :track track :muted] false))
(defn launch-scene! [scene]      (ctrl/set! [:bitwig :scene scene :launch!] true))
(defn launch-clip!  [track slot] (ctrl/set! [:bitwig :clip track slot :launch!] true))
(defn stop-clip!    [track slot] (ctrl/set! [:bitwig :clip track slot :stop!] true))
(defn play!         []           (ctrl/set! [:bitwig :transport :play!] true))
(defn stop!         []           (ctrl/set! [:bitwig :transport :stop!] true))
```

The watch dispatch is inside `register-watches!`:

```clojure
(defn- register-watches! []
  (ctrl/watch! [:bitwig]
    (fn [path _ new]
      (dispatch-write! path new))))
```

The inbound OSC subscription stream (bwosc → nous) is handled by an OSC listener
registered in `connect!`. Each `/nous/bitwig/val` message decodes to a path vector
and a value, then calls `ctrl/set!` directly — no Clojure eval, no nREPL:

```clojure
(osc/on-msg! "/nous/bitwig/val"
  (fn [path-str value]
    (ctrl/set! (osc-decode-path path-str) value)))
```

---

## Ctrl tree schema

bwosc owns all paths under `[:bitwig ...]`. Direction is relative to the ctrl tree:
`read` = bwosc pushes into the tree; `write` = nous.bitwig dispatches to bwosc.

**Paths are named at every level**, derived from labels the user has set in Bitwig.
The path key is the canonical identity — it is also what a surface adapter uses as
the scribble strip label. `:filter-cutoff` → "FLTR CUT" on a 7-char display;
`:lead-synth` → "LEAD SYN". The `:display` sibling path provides the second
scribble line ("3.2 kHz", "-6.0 dB"). No separate label lookup is needed; the
path describes itself. See "Name normalisation" below for the derivation rules.

### Transport

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :transport :playing]` | bool | read | transport running |
| `[:bitwig :transport :recording]` | bool | read | recording active |
| `[:bitwig :transport :tempo]` | float | read | BPM (informational; Link is authoritative) |
| `[:bitwig :transport :position]` | map | read | `{:beat N :bar N :beat-in-bar N}` |
| `[:bitwig :transport :loop]` | bool | read/write | loop on/off |
| `[:bitwig :transport :play!]` | trigger | write | start transport |
| `[:bitwig :transport :stop!]` | trigger | write | stop transport |
| `[:bitwig :transport :record!]` | trigger | write | engage recording |

### Master track

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :master :volume]` | float | read/write | master fader 0.0–1.0 |
| `[:bitwig :master :pan]` | float | read/write | master pan -1.0–1.0 |

### Tracks

`<track>` is a keyword derived from the Bitwig track name (see Name normalisation).

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :track <track> :volume]` | float | read/write | fader 0.0–1.0 |
| `[:bitwig :track <track> :pan]` | float | read/write | pan -1.0–1.0 |
| `[:bitwig :track <track> :muted]` | bool | read/write | mute state |
| `[:bitwig :track <track> :soloed]` | bool | read/write | solo state |
| `[:bitwig :track <track> :armed]` | bool | read/write | record arm |
| `[:bitwig :track <track> :color]` | [r g b] | read | track colour 0–255 |
| `[:bitwig :track <track> :type]` | kw | read | `:instrument` `:audio` `:effect` `:group` |
| `[:bitwig :track <track> :send <return> :level]` | float | read/write | send to return track |

### Return tracks

`<return>` is derived from the Bitwig return track name.

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :return <return> :volume]` | float | read/write | return fader 0.0–1.0 |
| `[:bitwig :return <return> :pan]` | float | read/write | return pan -1.0–1.0 |
| `[:bitwig :return <return> :muted]` | bool | read/write | mute state |

### Devices and parameters

`<device>` is derived from the device name as it appears in Bitwig (usually the
plugin name, or a user-assigned rename). `<param>` is derived from the
**Remote Controls page label** — the name the user has assigned in Bitwig's
Remote Controls panel. This is the stable, user-curated surface; bwosc does not
attempt to expose full plugin parameter lists by internal index.

Grid patches expose their I/O ports as Remote Controls. A Grid patch is
indistinguishable from any other device from the ctrl tree perspective.

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :track <track> :device <device> :enabled]` | bool | read/write | device on/off |
| `[:bitwig :track <track> :device <device> :param <param> :value]` | float | read/write | normalised 0.0–1.0 |
| `[:bitwig :track <track> :device <device> :param <param> :display]` | string | read | formatted value string |

Where a device has multiple Remote Controls pages (user-defined), the page name
adds an optional level. Omitting `:page` addresses the current/default page:

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :track <track> :device <device> :page <page> :param <param> :value]` | float | read/write | param on named page |
| `[:bitwig :track <track> :device <device> :page <page> :param <param> :display]` | string | read | formatted value |

### Macros

Bitwig's macro knobs are user-named per track and can each map to multiple
underlying plugin parameters. A single nous trajectory on a macro ripples through
all its Bitwig mappings simultaneously.

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :track <track> :macro <macro> :value]` | float | read/write | normalised 0.0–1.0 |
| `[:bitwig :track <track> :macro <macro> :display]` | string | read | formatted value |

### Clip launcher

`<track>` and `<scene>` are both named keywords. Clips are addressed by their
position at the intersection of a named track and named scene row — matching
how a composer thinks about the session.

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :scene <scene> :launch!]` | trigger | write | launch scene row |
| `[:bitwig :clip <track> <scene> :state]` | kw | read | `:empty` `:has-content` `:playing` `:recording` `:stopping` |
| `[:bitwig :clip <track> <scene> :launch!]` | trigger | write | launch clip immediately |
| `[:bitwig :clip <track> <scene> :queue!]` | trigger | write | queue clip for next quantize boundary |
| `[:bitwig :clip <track> <scene> :stop!]` | trigger | write | stop clip |

### Discovery index

bwosc maintains these paths as sets of currently known name keywords. Session
code can read them to introspect the session without hardcoding names.

| Path | Type | Description |
|------|------|-------------|
| `[:bitwig :tracks]` | set of kw | all track name keywords |
| `[:bitwig :scenes]` | set of kw | all scene name keywords |
| `[:bitwig :returns]` | set of kw | all return track keywords |
| `[:bitwig :track <track> :devices]` | set of kw | device keywords on this track |
| `[:bitwig :track <track> :macros]` | set of kw | macro keywords on this track |
| `[:bitwig :track <track> :device <device> :params]` | set of kw | param keywords on current page |
| `[:bitwig :track <track> :device <device> :pages]` | set of kw | page keywords (when multiple exist) |

---

## Transport and Link integration

### Clock topology

In a studio with hardware word clock, Bitwig participates at two levels:

- **Word clock** — Bitwig is slaved to the same master word clock as Kairos (typically
  the RME Digiface USB). This governs audio buffer alignment and is handled entirely in
  hardware / Bitwig's audio preferences. bwosc has no visibility into it.

- **Ableton Link** — Bitwig joins the Link session as its own peer, alongside nous, Kairos,
  and any other Link-capable nodes. Link provides beat/bar synchronisation and phase
  alignment across the fabric. bwosc publishes `[:bitwig :transport :tempo]` as an
  informational read — nous never writes it back. Tempo changes go through
  `nous.link/set-tempo!` which updates the Link session; Bitwig follows automatically.

The two clocks operate independently. Word clock ensures audio stays glitch-free at the
sample level; Link ensures scene launches and parameter sweeps land on musical boundaries.
For beat-accurate triggers, use OSC timetag bundles via the distributed protocol rather
than relying on Link alone — Link tempo sync is continuous but transport start times can
diverge by a quantum boundary.

### Clip launch from a journey transition

```clojure
;; Bar-accurate clip launch in a journey conductor
(defmethod on-transition :a-to-b [_]
  (schedule-at! (beats-until-next-bar)
    (fn []
      (bitwig/launch-scene! :drop)
      (ctrl/set! [:bitwig :track :lead-synth :device :surge-xt :param :filter-cutoff :value] 0.8))))
```

---

## Session examples

```clojure
;; Connect bwosc on startup (after peer/start-discovery! has run)
(bitwig/connect!)

;; Introspect what's in the session — no hardcoded names needed
(ctrl/get [:bitwig :tracks])
;=> #{:kick :snare :bass :lead-synth :pad :reverb-sub}

;; Animate a device parameter over 8 bars (named at every level)
(traj/animate! [:bitwig :track :lead-synth :device :surge-xt :param :filter-cutoff :value]
               :from 0.2 :to 0.8 :over (bars 8))

;; Read current clip state
(ctrl/get [:bitwig :clip :lead-synth :verse :state])   ;=> :playing

;; Conditional launch — don't re-launch a playing clip
(when (not= :playing (ctrl/get [:bitwig :clip :lead-synth :verse :state]))
  (bitwig/launch-clip! :lead-synth :verse))

;; High-level API uses the same named keywords
(bitwig/set-volume! :lead-synth 0.75)
(bitwig/mute! :pad)
(bitwig/launch-scene! :drop)

;; Macro sweep — ripples through all Bitwig mappings behind :texture
(traj/animate! [:bitwig :track :pad :macro :texture :value]
               :from 0.0 :to 1.0 :over (bars 4))

;; Mute every track except kick during a breakdown
(doseq [t (disj (ctrl/get [:bitwig :tracks]) :kick)]
  (bitwig/mute! t))

;; Master fadeout
(traj/animate! [:bitwig :master :volume] :from 1.0 :to 0.0 :over (bars 8))

;; Send level — lead synth into reverb-hall return
(ctrl/set! [:bitwig :track :lead-synth :send :reverb-hall :level] 0.4)
```

---

## Name normalisation

bwosc converts every Bitwig label (track name, device name, Remote Controls page name,
parameter name, scene name, macro name) to a Clojure keyword using the same algorithm.
The keyword IS the ctrl tree path component — there is no separate label table.

**Algorithm** (applied in order):

1. Trim surrounding whitespace.
2. Replace any run of characters that are not ASCII alphanumeric or `-` with a single `-`.
3. Strip leading and trailing `-`.
4. Lowercase.
5. If the result starts with a digit, prefix with `track-` (tracks), `scene-` (scenes),
   `return-` (return tracks), `device-` (devices), `page-` (RC pages), or `param-`
   (parameters) — whichever scope applies.
6. Deduplicate within scope: if two items normalise to the same keyword, append `-2`,
   `-3`, etc. in order of appearance in Bitwig's bank.

**Examples:**

| Bitwig label | Keyword |
|---|---|
| "Lead Synth" | `:lead-synth` |
| "OB-6 Pad" | `:ob-6-pad` |
| "Surge XT" | `:surge-xt` |
| "Reverb Hall" | `:reverb-hall` |
| "Filter Cutoff" | `:filter-cutoff` |
| "Env. Attack" | `:env-attack` |
| "FX #1" | `:fx-1` |
| "1" (default Bitwig track name) | `:track-1` |
| "Pad" (first), "Pad" (second) | `:pad`, `:pad-2` |

**Track renames**: when a Bitwig track is renamed, bwosc removes the old keyword from
`[:bitwig :tracks]` and inserts the new one. All paths under the old keyword stop
delivering; any running trajectory or `ctrl/watch!` on the old path silently stops
receiving updates. Session code that needs to survive renames should watch
`[:bitwig :tracks]` and re-bind on change.

---

## Mapping customisation

Most sessions work without any configuration if Bitwig track and device names are
reasonable. For sessions where the raw normalised keywords are inconvenient — for
example, a template session reused across projects, or a session with many default
track names like "1", "2", "3" — an optional alias map provides stable keywords.

Place the mapping under the `:bwosc/mapping` key in the studio topology EDN (or in a
standalone file referenced by `:bwosc/mapping-file`):

```edn
;; In studio-topology.edn
{...
 :bwosc/mapping
 {:tracks   {:ob-6-pad   :pad        ; normalised form → session alias
             :track-1    :kick
             :track-2    :snare
             :track-3    :bass}
  :scenes   {:section-a  :intro
             :section-b  :verse
             :section-c  :drop}
  :returns  {:fx-return-1 :reverb
             :fx-return-2 :delay}}}
```

Aliases are applied uniformly. `:pad` becomes the canonical ctrl tree keyword;
`:ob-6-pad` no longer exists in the tree, in surface labels, or in nous.bitwig
API calls. `[:bitwig :tracks]` returns `#{:kick :snare :bass :pad ...}`.

The mapping file is loaded by bwosc at init via the peer connection topology path
(same resolution as `NOUS_TOPOLOGY`). Mapping changes require bwosc restart.
**Sessions that use descriptive Bitwig names need no mapping at all.**

---

## nous.peer: no new primitives needed

bwosc participates via the standard OSC peer protocol. `nous.bitwig/connect!` reads
the peer's OSC address from `peer/peer-info` directly — no new peer primitive is
required. `peer/mount-peer!` (HTTP polling) is not used. The existing peer registry
populated by `peer/start-discovery!` is sufficient.

The earlier `connect-peer!` proposal (an nREPL connection to bwosc) is no longer
needed. OSC-speaking peers register via the beacon and are addressed by host+port.

---

## Hosting environment and threading

Bitwig controller scripts implement `ControllerExtension`. Bitwig loads the jar from
`~/Documents/Bitwig Studio/Extensions/` on macOS/Linux and
`%USERPROFILE%\Documents\Bitwig Studio\Extensions\` on Windows.

All Bitwig API calls must happen on the Bitwig controller thread. bwosc queues all
inbound nREPL commands back to the controller thread via `Host.scheduleTask(fn, 0)`.
Observer callbacks (which fire on the controller thread) can queue nREPL evals to the
background thread holding the nous connection — this is safe in both directions.

Background threads started in `init()`:

| Thread | Purpose |
|--------|---------|
| `bwosc-beacon` | UDP multicast beacon, 5s interval |
| `bwosc-osc-server` | OSC server — subscription registration + command receives |
| `bwosc-http` | HTTP server — initial snapshot + diagnostics |

---

## Build and installation

bwosc is a Java Maven project targeting Java 17+. Dependencies:

- Bitwig Controller API jar — at:
  - macOS: `/Applications/Bitwig Studio.app/Contents/API/bitwig-extension-api-*.jar`
  - Linux: `/opt/bitwig-studio/bitwig-extension-api-*.jar`
  - Install to local Maven repo (`mvn install:install-file`) or use `system` scope
- Bencode library (for nREPL wire protocol): `org.clojure/tools.nrepl` or a minimal
  Java bencode impl (same approach as `nous.bencode`)
- No other runtime dependencies

Build: `mvn package` → `target/bwosc-<version>.jar`

Installation:
```sh
# macOS / Linux
cp target/bwosc-*.jar ~/Documents/Bitwig\ Studio/Extensions/

# Windows
copy target\bwosc-*.jar "%USERPROFILE%\Documents\Bitwig Studio\Extensions\"
```

Activate in Bitwig: Settings → Controllers → Add Extension → select "bwosc".

---

## Configuration

bwosc extension settings (editable in Bitwig's Controller settings panel):

| Setting | Default | Description |
|---------|---------|-------------|
| `nous-host` | `127.0.0.1` | IP of the nous OSC listener |
| `nous-osc-port` | `57120` | nous OSC listener port |
| `bwosc-osc-port` | `7179` | bwosc OSC server port |
| `bwosc-http-port` | `7178` | HTTP snapshot/diagnostics port |
| `mapping-file` | _(empty)_ | path to a `bwosc-mapping.edn` override file |

Internal bank sizes (track bank 64, scene bank 64, device chain depth 8, Remote Controls
8 params/page) are fixed constants not exposed to the user. They are chosen large enough
that most live sessions never hit them. bwosc logs a warning if a session exceeds a limit.

---

## Implementation checklist

### bwosc (Java, `github.com/nous/bwosc`)
- [ ] ControllerExtension skeleton + manifest
- [ ] UDP multicast beacon thread (advertises `:osc-port`, `:http-port`)
- [ ] HTTP server: `GET /snapshot` (full EDN of current state atom)
- [ ] OSC server thread (port 7179):
  - [ ] `/nous/bitwig/sub <path>` — register subscriber (record source address + path prefix)
  - [ ] `/nous/bitwig/val <path> <value>` — inbound write → resolve via NameNorm → `Host.scheduleTask()` → Bitwig API
- [ ] Local state atom — shadow of all observable Bitwig paths (keyed by named paths)
- [ ] **Name normalisation layer** — `NameNorm.toKeyword(String label, Scope scope)`;
  dedup registry per scope; `NameNorm.fromKeyword(kw, scope)` → back to Bitwig entity
- [ ] Mapping customisation loader — reads `:bwosc/mapping` from topology EDN at init;
  applies alias table after normalisation
- [ ] Echo suppression — per-path last-set-by-nous value + timestamp; skip push if observer fires same value within window
- [ ] Discovery index maintenance — update `[:bitwig :tracks]` etc. as bank changes fire
- [ ] Bitwig observer subscriptions: transport, master, track bank, return track bank,
  device chain, remote controls pages, clip launcher, macro knobs
- [ ] Each observer: update local atom AND push OSC `/nous/bitwig/val <path> <value>` to all subscribers

### nous (in `nous` repo)
- [ ] `nous.bitwig` namespace — ctrl tree adapter
  - [ ] `connect!` — read bwosc OSC/HTTP ports from peer registry; fetch snapshot; send `/sub`; register OSC listener; register write watches
  - [ ] `load-snapshot!` — `GET /snapshot` → bulk `ctrl/set!` into `[:bitwig ...]`
  - [ ] OSC listener — `osc/on-msg! "/nous/bitwig/val"` → `ctrl/set!`
  - [ ] `register-watches!` — `ctrl/watch! [:bitwig]` → `osc/send!` dispatch writes
  - [ ] `disconnect!` — deregister watches + OSC listener
  - [ ] High-level API: `set-volume!`, `mute!`, `unmute!`, `launch-scene!`, `launch-clip!`,
    `stop-clip!`, `play!`, `stop!`
- [ ] `nous.ctrl/watch!` — confirm implemented; needed by `nous.bitwig`
- [ ] `nous.osc/on-msg!` — confirm inbound OSC dispatch exists; needed by `nous.bitwig`
- [ ] Export `nous.bitwig` from `nous.user`
- [ ] User manual §N: bwosc / Bitwig integration

---

## Open questions

1. **`ctrl/watch!` implementation**: `nous.ctrl` must support subtree watches — a
   callback fired on any change under a given path prefix. This is standard `add-watch`
   semantics on the state atom, filtered by path prefix. Confirm whether this is already
   implemented or needs to be added alongside `nous.bitwig`.

2. **Multiple Bitwig instances**: if both Ubuntu Bitwig and Windows Bitwig run bwosc,
   they collide on `:node-id :bitwig`. bwosc should derive node-id from hostname or a
   persisted UUID (`~/.bwosc/node-id`). The ctrl tree namespace follows:
   `[:bitwig-ubuntu ...]` vs `[:bitwig-win ...]`. `nous.bitwig/connect!` would accept
   a `node-id` arg to target the right instance.

3. **Grid patch param labeling**: verify that `RemoteControl.getName()` returns the
   user-assigned label from the Remote Controls page, not the Grid module's internal
   port name. If not, labeling needs to be set explicitly via Bitwig's API.

4. **Note clip content**: bwosc exposes clip *state* but not clip *content* (notes).
   `Clip.getNoteStep()` could expose note content for nous harmonic analysis (e.g.
   detect what scale a clip is using). Deferred — implement when a concrete use case
   requires it.

5. **EDN snapshot on connect**: when bwosc first connects to nous, nous may want a
   full snapshot of Bitwig's current state rather than waiting for each observer to
   fire individually. bwosc's HTTP `/snapshot` endpoint returns the full local state
   atom as EDN. nous.bitwig reads this on connect and bulk-applies it to the ctrl tree
   before the observer stream starts filling it in.

6. **Track rename during a running session**: when a track is renamed mid-session, the
   old keyword disappears and the new one appears in `[:bitwig :tracks]`. Any active
   trajectory targeting the old path silently becomes a no-op on the next tick. Options:
   (a) bwosc pushes a `:renamed` event alongside the new keyword so nous.bitwig can
   notify running trajectories; (b) session code watches `[:bitwig :tracks]` and handles
   renames explicitly; (c) trajectories detect a missing target and log a warning. The
   practical answer likely depends on how often sessions rename tracks mid-performance.
   Defer until a real case surfaces.

6. **Overbridge / Elektron**: Elektron Overbridge exposes Elektron device params to
   Bitwig as normal plugin parameters on Remote Controls pages. No special bwosc code
   needed — Overbridge devices appear as Bitwig devices automatically.
