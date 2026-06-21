# bwosc — Bitwig Studio nous Integration

## Status

Design document. Pre-implementation. See `design-studio-orchestration.md` for the
broader three-host studio model that bwosc serves.

---

## Overview

bwosc is a **Bitwig Studio Controller Extension** — a Java class loaded directly
into Bitwig's embedded JVM when the controller is activated. It bridges Bitwig
Studio into the nous peer fabric: publishing Bitwig's transport, track, device, and
clip state to the nous ctrl tree, and accepting ctrl tree changes that drive Bitwig
parameters and clip transport.

Key properties:

- **Platform-native**: runs inside Bitwig's JVM on any platform Bitwig supports
  (macOS, Linux, Windows). No nous binary required on the Bitwig host.
- **Nous peer**: participates in UDP multicast discovery (239.255.43.99:7743) and
  serves the standard HTTP ctrl-tree endpoint. The main nous node discovers and mounts
  it with `peer/mount-peer!` exactly like any other node.
- **Bidirectional**: Bitwig's observer API pushes state changes into bwosc handlers;
  bwosc's HTTP server exposes that state and accepts commands. Phase 2 adds
  active nREPL push for sub-second latency.
- **Standalone repo**: `github.com/nous/bwosc` — Java Maven project, separate from nous.

---

## Position in the nous fabric

```
nous (Mac Mini)
│
│  UDP multicast beacon (239.255.43.99:7743)
│  nous.peer/start-discovery!
│  nous.peer/mount-peer! :bitwig
│  nous.remote/eval-on-peer! :bitwig "..."    ← Phase 2
│
▼
bwosc controller extension (inside Bitwig's JVM)
├── HTTP server (port 7178) — ctrl-tree GET/PUT
├── UDP beacon sender — advertises :bitwig peer
├── Bitwig observer callbacks → local state atom
└── nREPL server (port 7889) — remote eval gate to Bitwig API  ← Phase 2
```

Bitwig participates in the fabric as an application-layer peer — no kairos or aion
binary on the Bitwig host. If Bitwig runs on the same Mac as nous, bwosc is a local
peer. If Bitwig runs on Ubuntu or Windows, bwosc is a remote peer discovered via
multicast.

The MIDI note event path is separate and coexists with bwosc. nous sends note events
via kairos MIDI out → Bitwig MIDI in using the `:sched-ahead-ms/daw-midi` schedule
window (default 50ms). bwosc handles the ctrl-tree / structural side; MIDI handles
the real-time note-event side.

---

## Hosting environment

Bitwig controller scripts implement `ControllerExtension`. Bitwig loads the jar from
`~/Documents/Bitwig Studio/Extensions/` on macOS/Linux; from
`%USERPROFILE%\Documents\Bitwig Studio\Extensions\` on Windows.

Available at init:
- `Host` — factory for all Bitwig API objects; also provides `scheduleTask()` for
  deferred work and accepts normal Java thread creation for persistent background work
- `Transport` — play/stop/record, tempo, loop, timeline position
- `TrackBank` — fixed-size window of tracks, devices, clips
- `Application` — DAW-level actions (open project, undo, etc.)
- `MidiIn` / `MidiOut` — if MIDI I/O is declared in the extension manifest

bwosc starts two background threads in `init()`:

1. **UDP beacon thread** — same EDN payload as any nous node, with
   `:node-id :bitwig`, `:role :daw`, `:http-port 7178`, `:nrepl-port 7889`
2. **HTTP server thread** — `com.sun.net.httpserver.HttpServer`, ctrl-tree GET/PUT

All Bitwig API calls must happen on the Bitwig controller thread. bwosc queues
changes received on the HTTP thread back to the Bitwig thread via
`Host.scheduleTask(Runnable, 0)`. This is the standard controller threading model.

---

## Ctrl tree schema

bwosc publishes and subscribes to paths under the `:bitwig` namespace.

### Transport

| Path | Type | Direction | Description |
|------|------|-----------|-------------|
| `[:bitwig :transport :playing]` | bool | read | transport playing |
| `[:bitwig :transport :recording]` | bool | read | transport recording |
| `[:bitwig :transport :tempo]` | float | read | BPM (informational; use Link for sync) |
| `[:bitwig :transport :position]` | map | read | `{:beat N :bar N :beat-in-bar N}` |
| `[:bitwig :transport :loop]` | bool | read/write | loop enabled |
| `[:bitwig :transport :play!]` | trigger | write | start transport |
| `[:bitwig :transport :stop!]` | trigger | write | stop transport |
| `[:bitwig :transport :toggle-play!]` | trigger | write | toggle play/stop |

### Tracks

`N` is the 0-based track bank index (bwosc declares a fixed bank size; default 16 tracks).

| Path | Type | Direction | Description |
|------|------|-----------|-------------|
| `[:bitwig :track N :name]` | string | read | track name |
| `[:bitwig :track N :color]` | [r g b] | read | track color (0–255 each) |
| `[:bitwig :track N :type]` | kw | read | `:instrument` `:audio` `:effect` `:group` |
| `[:bitwig :track N :volume]` | float | read/write | fader level 0.0–1.0 |
| `[:bitwig :track N :pan]` | float | read/write | pan -1.0 to 1.0 |
| `[:bitwig :track N :muted]` | bool | read/write | mute state |
| `[:bitwig :track N :soloed]` | bool | read/write | solo state |
| `[:bitwig :track N :armed]` | bool | read/write | record arm |
| `[:bitwig :track N :send M :level]` | float | read/write | send N to return M |

### Devices and parameters

bwosc observes a fixed-depth device chain per track (default 4 devices) and a fixed
parameter page per device (default 8 params, the Bitwig Remote Controls page).

| Path | Type | Direction | Description |
|------|------|-----------|-------------|
| `[:bitwig :track N :device M :name]` | string | read | device name |
| `[:bitwig :track N :device M :enabled]` | bool | read/write | device on/off |
| `[:bitwig :track N :device M :param K :name]` | string | read | parameter name |
| `[:bitwig :track N :device M :param K :value]` | float | read/write | normalised 0.0–1.0 |
| `[:bitwig :track N :device M :param K :display]` | string | read | formatted display value |
| `[:bitwig :track N :device M :param K :exists]` | bool | read | slot occupied |

The Remote Controls page (8 params) is the standard Bitwig exposure surface. Plugin
authors map their most important parameters there. bwosc follows this convention — it
does not attempt to expose the full plugin parameter list, which can have hundreds of
entries and no stable indices. The Remote Controls page is stable, user-curated, and
exactly the 8 parameters the performer has decided matter.

For alembic Grid patches specifically: Grid exposes its I/O ports (knobs, CV inputs)
as Remote Controls. bwosc makes a Grid patch look identical to any other Bitwig device
from the nous ctrl tree perspective — the performer maps the most compositionally
important Grid controls onto the Remote Controls page.

### Clip launcher

`N` = track index, `M` = scene/slot index.

| Path | Type | Direction | Description |
|------|------|-----------|-------------|
| `[:bitwig :scene M :name]` | string | read | scene name |
| `[:bitwig :scene M :launch!]` | trigger | write | launch scene |
| `[:bitwig :clip N M :state]` | kw | read | `:empty` `:has-content` `:playing` `:recording` `:stopping` |
| `[:bitwig :clip N M :launch!]` | trigger | write | launch clip |
| `[:bitwig :clip N M :stop!]` | trigger | write | stop clip |
| `[:bitwig :clip N M :name]` | string | read | clip name |

---

## Phase 1: HTTP peer (passive model)

Phase 1 makes bwosc immediately usable with the existing `nous.peer` infrastructure,
with no changes to the main nous codebase.

**bwosc side:**
1. On `init()`, start the UDP beacon thread and HTTP server on port 7178.
2. Subscribe Bitwig observers on all exposed paths (transport, tracks, devices, clips).
   Each observer updates a local atom: `(swap! state assoc-in path value)`.
3. HTTP GET `/ctrl/<path>` → read from the local state atom, return `{"value": ...}` JSON.
4. HTTP PUT `/ctrl/<path>` with `{"value": ...}` body → queue a `Host.scheduleTask()`
   that calls the appropriate Bitwig API method.

**nous side — no code changes required:**
```clojure
;; Discover and mount bwosc exactly like any other peer
(peer/start-discovery!)

;; After a few seconds:
(peer/peers)
;; => {:bitwig {:node-id :bitwig :host "192.168.1.42" :http-port 7178 :role :daw ...}}

(peer/mount-peer! :bitwig)

;; Read transport state
(ctrl/get [:peers :bitwig :bitwig :transport :playing])
;; => false

;; (no write path in Phase 1 — nous.peer only polls, no PUT support yet)
```

The polling interval (2s by default in `nous.peer`) means Phase 1 is suitable for
structural state (knowing what clips are loaded, what tracks exist) but not for
real-time parameter animation. That's Phase 2.

**Phase 1 write path** — use `nous.remote/eval-on-peer!` against bwosc's nREPL
(also started in Phase 1, port 7889) to call Bitwig API methods directly:
```clojure
(remote/eval-on-peer! :bitwig
  "(-> bitwig/transport .play)")
```
This works immediately; it's just not integrated with the ctrl tree write path yet.

---

## Phase 2: Active nREPL push (low-latency)

Phase 2 replaces polling with push: bwosc's Bitwig observers, on each state change,
immediately POST the new value to nous's HTTP server (or eval a `ctrl/set!` call on
the nous nREPL). This gives sub-second latency for tracking Bitwig state.

**Implementation:**

bwosc starts a background thread that holds an open `nous.remote` style TCP connection
to the main nous nREPL port (7888). On each Bitwig observer callback:

```java
// Inside a Bitwig observer
volume.addValueObserver(v -> {
    // queue a ctrl/set! eval on the nous nREPL connection
    nreplClient.evalAsync(
        String.format("(ctrl/set! [:bitwig :track %d :volume] %.4f)", trackIdx, v)
    );
});
```

The nREPL eval is non-blocking — bwosc queues the eval and returns. The main nous
ctrl tree is updated in real time as Bitwig's state changes.

**Write path (ctrl tree → Bitwig):**

Phase 2 adds a subscription mechanism: bwosc polls nous's HTTP server for write targets
at short intervals (100ms), or nous actively sends an HTTP PUT to bwosc's server on
ctrl tree changes to paths bwosc owns. The second approach (nous pushes to bwosc) is
cleaner and requires nous.peer to gain a PUT-on-change notification mechanism — a
natural Phase 3 enhancement for the peer protocol generally.

---

## Transport and Link integration

Both nous and Bitwig are Ableton Link peers. Tempo synchronisation happens over Link
automatically — do not attempt MIDI clock sync between them. The Link session is the
shared beat source.

bwosc publishes `[:bitwig :transport :tempo]` as informational. nous reads it but does
not write it; tempo changes happen via `nous.link/set-tempo!` which updates the Link
session that all peers (including Bitwig) follow.

Transport start/stop can be driven from nous via the ctrl tree write path:
```clojure
;; Launch from a journey transition (next bar)
(schedule-at! (+ (current-beat) (beats-until-next-bar))
  #(ctrl/set! [:bitwig :transport :play!] true))
```

In practice, for live sets, both nous and Bitwig are usually left running; clip
transport is the compositional control, not global play/stop.

---

## Clip launcher as a compositional device

The clip launcher is where bwosc becomes compositionally powerful. Journey transitions
can launch clips as part of a structural arc:

```clojure
;; In a journey transition at bar 32: drop into the B section
(defmethod on-transition :a-to-b [_]
  (ctrl/set! [:bitwig :scene 2 :launch!] true)     ; launch "B section" scene
  (ctrl/set! [:bitwig :track 0 :device 0 :param 2 :value] 0.8)) ; pad reverb up
```

bwosc's clip state observer keeps `[:bitwig :clip N M :state]` current. nous can
read clip state to make conditional decisions — don't launch a clip that's already
playing, stop before relaunching, etc.

---

## Device parameter animation

Since Phase 1 already exposes device parameters as ctrl tree paths, trajectory curves
and ctrl tree automation work immediately:

```clojure
;; Animate Bitwig device param over 8 bars from 0.2 to 0.8
(traj/animate! [:bitwig :track 2 :device 0 :param 3 :value]
               :from 0.2 :to 0.8 :over (bars 8))
```

Phase 2 makes this real-time rather than polling-delayed. Phase 1 is sufficient for
parameter changes at bar or phrase granularity; Phase 2 is needed for LFO-rate
automation.

---

## Build and installation

bwosc is a Java Maven project targeting Java 17+. Dependencies:

- Bitwig Controller API jar — located at:
  - macOS: `/Applications/Bitwig Studio.app/Contents/API/bitwig-extension-api-*.jar`
  - Linux: `/opt/bitwig-studio/bitwig-extension-api-*.jar`
  - Not available as a Maven artifact; install-to-local-repo or use `system` scope
- No other runtime dependencies. The UDP/HTTP/nREPL stack is built from Java stdlib
  (`DatagramSocket`, `com.sun.net.httpserver`, plain TCP sockets + bencode).

Build produces a single jar at `target/bwosc-<version>.jar`.

Installation:
```sh
# macOS / Linux
cp target/bwosc-*.jar ~/Documents/Bitwig\ Studio/Extensions/

# Windows
copy target\bwosc-*.jar "%USERPROFILE%\Documents\Bitwig Studio\Extensions\"
```

Activate in Bitwig: Settings → Controllers → Add Extension → select "bwosc".
Configure the nous host IP in the extension settings (default: localhost).

---

## Configuration

bwosc extension settings (editable in Bitwig's Controller settings panel):

| Setting | Default | Description |
|---------|---------|-------------|
| `nous-host` | `127.0.0.1` | IP of the main nous node |
| `nous-nrepl-port` | `7888` | nREPL port on the nous node |
| `bwosc-http-port` | `7178` | HTTP port bwosc serves |
| `bwosc-nrepl-port` | `7889` | nREPL port bwosc serves |
| `track-bank-size` | `16` | number of tracks in bank |
| `scene-bank-size` | `32` | number of scenes in bank |
| `devices-per-track` | `4` | device chain depth |
| `params-per-device` | `8` | Remote Controls page size |

---

## Implementation phasing

### Phase 1 — HTTP passive peer
- [ ] Bitwig ControllerExtension skeleton with manifest
- [ ] UDP multicast beacon sender thread
- [ ] HTTP server (port 7178): GET `/ctrl/<path>`, PUT `/ctrl/<path>`
- [ ] Local state atom updated by Bitwig observers
- [ ] Transport observers (play, record, tempo, position)
- [ ] Track observers (volume, pan, mute, solo, arm, name, color) for N tracks
- [ ] Device/parameter observers (Remote Controls page) for M devices per track
- [ ] Clip state observer for scene × track matrix
- [ ] HTTP PUT → `Host.scheduleTask()` → Bitwig API call
- [ ] nREPL server (port 7889) — minimal, for eval-on-peer! access in Phase 1

### Phase 2 — Active nREPL push
- [ ] Persistent nREPL client connection to nous node
- [ ] Observer callbacks push `ctrl/set!` evals to nous via nREPL client
- [ ] Connection management (reconnect on nous restart)
- [ ] HTTP PUT from nous → bwosc for command path (replaces eval-on-peer! for writes)

### Phase 3 — Ctrl tree write subscription
- [ ] Subscribe to ctrl tree write targets on nous's OSC `/sub` endpoint
  (when nous implements the push subscription protocol)
- [ ] Eliminates polling for write-path round-trip

---

## Open questions

1. **Remote Controls page vs full parameter list**: the 8-param Remote Controls page
   is right for compositional use, but some workflows (e.g. automating a specific
   compressor attack) need parameters not on the page. Investigate whether
   `CursorDevice.createSpecificBitwigDevice()` or `HardwareActionBindable`-based
   approaches give stable indexed access to full parameter lists. Low priority until
   a concrete use case requires it.

2. **Multiple Bitwig instances**: the studio model has Bitwig on Ubuntu plus potentially
   Bitwig on Windows. Two bwosc peers on the same multicast group collide on node-id.
   bwosc should derive its node-id from the hostname or a persisted UUID, not a
   hardcoded `:bitwig`. Affects the ctrl tree path prefix too
   (`[:bitwig-ubuntu :track N ...]` vs `[:bitwig-windows :track N ...]`).

3. **Grid patch param labeling**: Grid modules have machine-generated parameter names
   (`KNOB_1`, `CV_IN_2`). The performer assigns meaningful names via the Remote
   Controls page in Bitwig's UI. Verify that `RemoteControl.getName()` reflects the
   user-assigned label, not the module port name.

4. **Note clip content**: bwosc currently exposes clip *state* (playing/empty/etc.) but
   not clip *content* (notes, lengths, velocity). Reading note content would let nous
   analyse what Bitwig clips are doing harmonically. The Bitwig API exposes
   `NoteStep` sequences via `Clip.getNoteStep()`. Deferred to a later sprint when
   there's a concrete use case (e.g. nous reading a clip to analyse its harmonic
   content and feeding that into the harmony engine).

5. **Overbridge / Elektron**: GigPerformer integrates Overbridge for Elektron device
   control. If bwosc runs on the same host as Overbridge, Elektron device params
   exposed to Bitwig via Overbridge appear as normal Bitwig device params in the
   Remote Controls page — no special bwosc code needed. Track when Overbridge
   confirms this in practice.
