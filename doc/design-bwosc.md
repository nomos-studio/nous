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
- **Full ctrl tree peer**: owns `[:bitwig ...]` as a top-level namespace; state is
  push-live in both directions via nREPL, not polled.
- **`nous.bitwig` companion namespace**: a thin adapter in nous that registers ctrl
  tree watches on `[:bitwig ...]` writable paths and dispatches changes to bwosc via
  `eval-on-peer!`. Consistent with the pattern established by `nous.kairos`.
- **Standalone repo**: `github.com/nous/bwosc` — Java Maven project, separate from nous.

---

## Architecture

Two nREPL connections carry all ctrl tree traffic. One HTTP server handles
peer discovery and diagnostic inspection.

```
┌────────────────────────────────────────────────────┐
│  nous (Mac Mini / any host)                        │
│                                                    │
│  ctrl tree: [:bitwig :track 0 :volume] = 0.73     │
│             [:bitwig :transport :playing] = true   │
│             ...                                    │
│                                                    │
│  nous.bitwig — ctrl watch on [:bitwig ...]         │
│    ctrl/watch! [:bitwig :track N :volume]    ──────┼──→ nREPL eval-on-peer! :bitwig
│    ctrl/watch! [:bitwig :clip N M :launch!]  ──────┼──→ nREPL eval-on-peer! :bitwig
│    ctrl/watch! [:bitwig :transport :play!]   ──────┼──→ nREPL eval-on-peer! :bitwig
│                                                    │
│  nREPL server (port 7888) ←────────────────────────┼── bwosc pushes ctrl/set! here
└────────────────────────────────────────────────────┘
           ↑ outbound nREPL (nous→bwosc)
           ↓ inbound nREPL (bwosc→nous)
┌────────────────────────────────────────────────────┐
│  bwosc (inside Bitwig's JVM)                       │
│                                                    │
│  Bitwig observer callbacks:                        │
│    volume.addValueObserver(v ->                    │
│      nreplToNous.evalAsync(                        │
│        "(ctrl/set! [:bitwig :track 0 :volume] v)") │
│                                                    │
│  nREPL server (port 7889) ← nous eval-on-peer!     │
│    (bwosc/set-volume! 0 0.8) → Host.scheduleTask() │
│    (bwosc/launch-clip! 2 3)  → Host.scheduleTask() │
│                                                    │
│  UDP beacon sender (239.255.43.99:7743)            │
│  HTTP server (port 7178) — diagnostics + snapshot  │
└────────────────────────────────────────────────────┘
```

### State plane: Bitwig → nous ctrl tree

Bitwig's observer model provides push callbacks for every observable property
(volume, mute state, clip playback state, device params, tempo, etc.). Each
observer callback immediately evals a `ctrl/set!` call on the outbound nREPL
connection to nous:

```java
volume.addValueObserver(v ->
    nreplToNous.evalAsync(
        String.format("(nous.ctrl/set! [:bitwig :track %d :volume] %.6f)", idx, v)
    )
);
```

`evalAsync` is non-blocking — bwosc queues the message and returns. The main nous
ctrl tree at `[:bitwig :track N :volume]` is updated in real time, with the same
latency as a local ctrl tree write.

### Command plane: nous → Bitwig

Writes to `[:bitwig ...]` paths flow through the `nous.bitwig` namespace, which
registers ctrl watches on the writable subset:

```clojure
;; nous.bitwig — registers on start
(ctrl/watch! [:bitwig :track] bitwig-track-watch)
(ctrl/watch! [:bitwig :scene] bitwig-scene-watch)
(ctrl/watch! [:bitwig :transport] bitwig-transport-watch)

;; watch dispatch
(defn- bitwig-track-watch [path _old new]
  (let [[_ _ track-idx key] path]
    (remote/eval-on-peer! :bitwig
      (format "(bwosc/handle-track! %d %s %s)" track-idx key (pr-str new)))))
```

bwosc's nREPL server receives the eval, unmarshals the args, and queues a
`Host.scheduleTask(Runnable, 0)` call — the standard Bitwig pattern for
thread-safe API calls from background threads:

```java
// bwosc/handle-track! implementation (Clojure interop or Java)
public static void handleTrack(int trackIdx, String key, Object value) {
    host.scheduleTask(() -> {
        Track track = trackBank.getChannel(trackIdx);
        switch (key) {
            case ":volume" -> track.getVolume().set(((Number) value).doubleValue());
            case ":muted"  -> track.getMute().set(((Boolean) value));
            // ...
        }
    }, 0);
}
```

The programmer-facing API is therefore identical for local and remote targets:

```clojure
;; These all go through ctrl tree → nous.bitwig watch → bwosc nREPL → Bitwig API
(ctrl/set! [:bitwig :track 2 :volume] 0.8)
(ctrl/set! [:bitwig :scene 3 :launch!] true)
(ctrl/set! [:bitwig :transport :play!] true)
(ctrl/set! [:bitwig :track 0 :device 1 :param 3 :value] 0.65)
```

No special Bitwig API knowledge required in compositional code. Bitwig is just a
device whose parameters happen to live at `[:bitwig ...]`.

### HTTP server role

The HTTP server (port 7178) has two narrow purposes:

1. **Peer discovery response**: nous.peer reads `/ctrl/<path>` to confirm bwosc is
   alive and to fetch the initial tree snapshot on connect. One GET at startup, not
   a polling loop.
2. **Diagnostic inspection**: ad-hoc `GET /ctrl/bitwig/track/0/volume` from a browser
   or curl during development. Not part of the normal operating path.

The HTTP server is NOT the primary data path. Normal operation is entirely nREPL.

### UDP beacon

bwosc broadcasts a standard nous peer beacon:

```edn
{:node-id      :bitwig
 :role         :daw
 :http-port    7178
 :nrepl-port   7889
 :version      "0.1.0"
 :backends     {:bitwig {:host "127.0.0.1"}}
 :timestamp-ms 1750000000000}
```

The main nous node discovers bwosc via `peer/start-discovery!`. bwosc appears in
`(peer/peers)` and can be connected via `peer/connect-peer!` (which establishes
the nREPL connection, not just HTTP polling). `peer/mount-peer!` is NOT used —
that's the HTTP polling path. A new `peer/connect-peer!` function establishes the
full bidirectional nREPL peer relationship.

---

## `nous.bitwig` companion namespace

`nous.bitwig` is the ctrl tree adapter for bwosc — the same pattern as `nous.kairos`
for kairos. It lives in nous (not bwosc) and handles:

1. Registering ctrl tree watches on writable `[:bitwig ...]` paths on startup
2. Dispatching ctrl tree changes to bwosc via `remote/eval-on-peer!`
3. High-level compositional API that reads naturally in session code

```clojure
(ns nous.bitwig
  "Ctrl tree adapter for bwosc — Bitwig Studio peer.
  Registers watches on [:bitwig ...] write paths and dispatches to bwosc.
  Start with (bitwig/connect! host nrepl-port) after bwosc is discovered."
  (:require [nous.ctrl   :as ctrl]
            [nous.remote :as remote]
            [nous.peer   :as peer]))

(defn connect!
  "Establish bwosc peer connection and register ctrl tree watches.
  Discovers bwosc via peer registry if no host/port given."
  ([] (connect! (get-in (peer/peer-info :bitwig) [:host])
                (get-in (peer/peer-info :bitwig) [:nrepl-port])))
  ([host port]
   (reset! *conn* (remote/connect! host port))
   (register-watches!)
   nil))

(defn- dispatch! [code]
  (when-let [conn @*conn*]
    (remote/remote-eval! conn code)))

;; High-level API
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
  (ctrl/watch! [:bitwig :track]
    (fn [path _ new]
      (let [[_ _ idx key] path]
        (dispatch! (format "(bwosc/handle-track! %d %s %s)"
                           idx (pr-str key) (pr-str new))))))
  (ctrl/watch! [:bitwig :scene]
    (fn [path _ new]
      (let [[_ _ idx key] path]
        (dispatch! (format "(bwosc/handle-scene! %d %s %s)"
                           idx (pr-str key) (pr-str new))))))
  (ctrl/watch! [:bitwig :transport]
    (fn [path _ new]
      (let [[_ _ key] path]
        (dispatch! (format "(bwosc/handle-transport! %s %s)"
                           (pr-str key) (pr-str new)))))))
```

---

## Ctrl tree schema

bwosc owns all paths under `[:bitwig ...]`. Direction is relative to the ctrl tree:
`read` = bwosc pushes into the tree; `write` = nous.bitwig dispatches to bwosc.

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

### Tracks

`N` = 0-based index in track bank (bank size configurable, default 16).

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :track N :name]` | string | read | track name |
| `[:bitwig :track N :color]` | [r g b] | read | track colour (0–255) |
| `[:bitwig :track N :type]` | kw | read | `:instrument` `:audio` `:effect` `:group` |
| `[:bitwig :track N :volume]` | float | read/write | fader 0.0–1.0 |
| `[:bitwig :track N :pan]` | float | read/write | pan -1.0–1.0 |
| `[:bitwig :track N :muted]` | bool | read/write | mute state |
| `[:bitwig :track N :soloed]` | bool | read/write | solo state |
| `[:bitwig :track N :armed]` | bool | read/write | record arm |
| `[:bitwig :track N :send M :level]` | float | read/write | send M level |

### Devices and parameters

bwosc observes the Remote Controls page (8 params) per device, per track. The Remote
Controls page is the stable, user-curated exposure surface — do not attempt to expose
full plugin parameter lists by index.

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :track N :device M :name]` | string | read | device name |
| `[:bitwig :track N :device M :enabled]` | bool | read/write | device on/off |
| `[:bitwig :track N :device M :param K :name]` | string | read | parameter label |
| `[:bitwig :track N :device M :param K :value]` | float | read/write | normalised 0.0–1.0 |
| `[:bitwig :track N :device M :param K :display]` | string | read | formatted value |
| `[:bitwig :track N :device M :param K :exists]` | bool | read | slot is occupied |

Grid patches expose their I/O ports as Remote Controls. A Grid patch looks identical
to any other device from the ctrl tree perspective.

### Clip launcher

`N` = track index, `M` = slot/scene index.

| Path | Type | Dir | Description |
|------|------|-----|-------------|
| `[:bitwig :scene M :name]` | string | read | scene name |
| `[:bitwig :scene M :launch!]` | trigger | write | launch scene |
| `[:bitwig :clip N M :state]` | kw | read | `:empty` `:has-content` `:playing` `:recording` `:stopping` |
| `[:bitwig :clip N M :name]` | string | read | clip name |
| `[:bitwig :clip N M :launch!]` | trigger | write | launch clip |
| `[:bitwig :clip N M :stop!]` | trigger | write | stop clip |

---

## Transport and Link integration

Both nous and Bitwig join the same Ableton Link session. Tempo sync happens over Link
automatically. bwosc publishes `[:bitwig :transport :tempo]` as an informational
read — nous never writes it back. Tempo changes go through `nous.link/set-tempo!`
which updates the Link session that all peers (Bitwig included) follow.

Transport start/stop from a journey transition:

```clojure
;; Bar-accurate clip launch in a journey conductor
(defmethod on-transition :a-to-b [_]
  (schedule-at! (beats-until-next-bar)
    (fn []
      (bitwig/launch-scene! 2)
      (ctrl/set! [:bitwig :track 0 :device 0 :param 2 :value] 0.8))))
```

---

## Session examples

```clojure
;; Connect bwosc on startup (after peer/start-discovery! has run)
(bitwig/connect!)

;; Animate a Bitwig device parameter over 8 bars
(traj/animate! [:bitwig :track 2 :device 0 :param 3 :value]
               :from 0.2 :to 0.8 :over (bars 8))

;; Read current clip state
(ctrl/get [:bitwig :clip 0 3 :state])   ;=> :playing

;; Conditional launch — don't re-launch a playing clip
(when (not= :playing (ctrl/get [:bitwig :clip 0 3 :state]))
  (bitwig/launch-clip! 0 3))

;; Mute all but the kick track during a breakdown
(doseq [t (range 1 16)] (bitwig/mute! t))
```

---

## nous.peer extension: `connect-peer!`

The existing `peer/mount-peer!` is a polling relationship — unsuitable for bwosc.
A new `peer/connect-peer!` establishes a full bidirectional nREPL peer:

```clojure
(defn connect-peer!
  "Establish a full bidirectional nREPL peer relationship with `peer-node-id`.

  Unlike `mount-peer!` (which polls HTTP), `connect-peer!` opens a persistent
  nREPL connection to the peer's nREPL server. The peer can push ctrl/set! calls
  back to this node via its own outbound nREPL connection.

  Returns the nREPL connection map. Callers should pass this to the appropriate
  companion namespace (e.g. `nous.bitwig/connect!`) which registers the ctrl
  tree watches for the command plane.

  Example:
    (peer/connect-peer! :bitwig)
    (bitwig/connect!)"
  [peer-node-id]
  (let [{:keys [host nrepl-port]} (peer-info peer-node-id)]
    (when-not (and host nrepl-port)
      (throw (ex-info "connect-peer!: peer not in registry"
                      {:peer peer-node-id})))
    (remote/connect! host nrepl-port)))
```

`peer/connect-peer!` is the one new function needed in nous. Everything else uses
existing `nous.remote` and `nous.ctrl` infrastructure.

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
| `bwosc-nrepl-server` | inbound nREPL server (commands from nous) |
| `bwosc-nrepl-client` | persistent outbound connection to nous nREPL |
| `bwosc-http` | HTTP server — discovery response + diagnostics |

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
| `nous-host` | `127.0.0.1` | IP of the main nous nREPL server |
| `nous-nrepl-port` | `7888` | nous nREPL port |
| `bwosc-http-port` | `7178` | HTTP diagnostic/discovery port |
| `bwosc-nrepl-port` | `7889` | bwosc nREPL server port |
| `track-bank-size` | `16` | tracks in bank |
| `scene-bank-size` | `32` | scenes in bank |
| `devices-per-track` | `4` | device chain depth |
| `params-per-device` | `8` | Remote Controls page size (Bitwig max) |

---

## Implementation checklist

### bwosc (Java, `github.com/nous/bwosc`)
- [ ] ControllerExtension skeleton + manifest
- [ ] UDP multicast beacon thread
- [ ] HTTP server: GET `/ctrl/<path>` (reads local state atom), `/snapshot` (full EDN)
- [ ] nREPL server thread (inbound commands from nous)
- [ ] Outbound nREPL client thread (state pushes to nous)
- [ ] Local state atom — shadow of all observable Bitwig paths
- [ ] Bitwig observer subscriptions: transport, track bank, device bank, clip launcher
- [ ] Each observer: update local atom AND push `ctrl/set!` eval to nous nREPL
- [ ] Inbound nREPL API: `bwosc/handle-track!`, `bwosc/handle-scene!`,
  `bwosc/handle-transport!`, `bwosc/handle-device-param!`
- [ ] Each handler: `Host.scheduleTask()` → Bitwig API call

### nous (in `nous` repo)
- [ ] `nous.peer/connect-peer!` — open nREPL connection to a peer
- [ ] `nous.bitwig` namespace — ctrl tree adapter
  - [ ] `connect!` — discover bwosc via peer registry, call `connect-peer!`, register watches
  - [ ] `register-watches!` — ctrl/watch! on all writable `[:bitwig ...]` paths
  - [ ] `disconnect!` — deregister watches, close nREPL connection
  - [ ] High-level API: `set-volume!`, `mute!`, `unmute!`, `launch-scene!`, `launch-clip!`,
    `stop-clip!`, `play!`, `stop!`
- [ ] `nous.ctrl/watch!` — if not already implemented; needed by `nous.bitwig`
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

6. **Overbridge / Elektron**: Elektron Overbridge exposes Elektron device params to
   Bitwig as normal plugin parameters on Remote Controls pages. No special bwosc code
   needed — Overbridge devices appear as Bitwig devices automatically.
