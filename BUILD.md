# Building nous

nous is a Clojure library. There is no C++ code in this repository. Real-time
MIDI delivery is handled by a separate C++ runtime peer — **kairos** (full
CLAP host) or **aion** (MIDI/Link only) — which you build and install once from
its own repository.

---

## Prerequisites

### Clojure

| Tool | Minimum | Notes |
|------|---------|-------|
| Java (JDK) | 21 | OpenJDK 21+ recommended; tested on OpenJDK 25 |
| Leiningen | 2.11+ | Install via [leiningen.org](https://leiningen.org) |

### C++ peer (kairos or aion)

| Tool | Minimum | Notes |
|------|---------|-------|
| CMake | 3.22+ | C++ build system |
| C++20 compiler | — | Apple clang (Xcode CLT 14+), GCC 12+, or MSVC 2022 |

---

## 1. Clone and test

```bash
git clone https://github.com/nomos-studio/nous.git
cd nous
lein test      # 0 failures, 0 errors
lein repl
```

The Clojure library runs without a peer. Calls to `play!` are silently dropped
if no peer is connected, so you can develop theory, harmony, and generative
logic offline. Connecting a peer is only needed for real-time MIDI output.

---

## 2. Install kairos (recommended peer)

kairos is the full nomos-studio runtime: CLAP plugin host, audio I/O, MIDI
routing, Ableton Link, and nomos-rt scheduler.

```bash
git clone https://github.com/nomos-studio/kairos.git
cd kairos
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
sudo cp build/kairos /usr/local/bin/
```

`start-sidecar!` auto-discovers kairos at `/usr/local/bin/kairos`,
`/opt/homebrew/bin/kairos`, and `$PREFIX/bin/kairos`.

---

## 2a. Install aion (MIDI/Link only, alternative peer)

aion is a lightweight variant of the nomos-rt scheduler with no CLAP host and
no audio engine. Ideal for Raspberry Pi Zero 2W, remote sessions, or
development machines without an audio interface.

```bash
git clone https://github.com/nomos-studio/aion.git
cd aion
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
sudo cp build/aion /usr/local/bin/
```

`start-sidecar!` falls back to aion when kairos is not found.
`start-aion!` targets aion explicitly.

---

## 3. Start a peer from the REPL

```clojure
(require '[nous.user :refer :all])

;; Auto-discover kairos (preferred) or aion and connect:
(start-sidecar! :midi-port "IAC")

;; Or target explicitly:
(start-kairos! :midi-port "IAC" :plugin "/path/to/plugin.clap")
(start-aion!   :midi-port "IAC" :bpm 120)

;; Disconnect:
(stop-sidecar!)
```

---

## 4. Build kairos or aion with Ableton Link (optional, GPL-2.0-or-later)

Link provides beat-accurate tempo sync with other Link-enabled apps on the LAN
(Ableton Live, Bitwig, Sonic Pi, etc.).

> **License note**: The [Ableton Link SDK](https://github.com/Ableton/link) is
> GPL-2.0-or-later. Enabling Link changes the peer binary's license from
> LGPL-2.1-or-later to GPL-2.0-or-later. The nous Clojure library (EPL-2.0)
> is unaffected. See [doc/licensing.md](doc/licensing.md) for full details.

```bash
cmake -B build \
  -DCMAKE_BUILD_TYPE=Release \
  -DNOUS_ENABLE_LINK=ON
cmake --build build --parallel
```

Once built, enable Link from the REPL:

```clojure
(require '[nous.link :as link])
(start!)
(link/enable!)    ; join or create a Link session
(link/bpm)        ; => current session BPM
(link/peers)      ; => number of connected peers
```

---

## 5. MIDI device setup

### macOS

Enable the IAC Driver for a virtual loopback port (useful for testing without
hardware):

**Audio MIDI Setup → Window → Show MIDI Studio → IAC Driver → Device is online**

Then pass the port name to `start-sidecar!`:

```clojure
(start-sidecar! :midi-port "IAC Driver Bus 1")
```

### Linux

```bash
sudo apt install alsa-utils   # for aplaymidi / amidi diagnostics
aplaymidi -l                  # list available MIDI output ports
```

Pass the port name string returned by `aplaymidi -l` as `:midi-port`.

---

## 6. SuperCollider (optional)

nous can drive SuperCollider via OSC for synthesis and sample playback. This
is entirely optional and has no effect on kairos/aion MIDI delivery.

```bash
# macOS
brew install supercollider

# or download from supercollider.github.io
```

```clojure
(require '[nous.sc :as sc])
(sc/start-sc!)       ; launches scsynth subprocess
(sc/compile-synth :sc (defsynth :pad [...] ...))
```

---

## 7. Development workflow

### Running tests

```bash
lein test                          # full suite
lein test nous.user-test           # single namespace
```

Tests run entirely in the JVM. No peer binary is required — `nous.kairos`
functions that write to the socket are skipped or mocked via `^:dynamic` vars.

### Changing peer options at the REPL

```clojure
(stop-sidecar!)
(start-sidecar! :bpm 140 :midi-port "USB Midi")
```

### MIDI learn (device map authoring)

```clojure
(require '[nous.learn :as learn])
(learn/start! :device :my-synth)    ; listen for incoming CC/note events
;; wiggle a knob → nous writes an EDN entry to resources/devices/my-synth.edn
(learn/stop!)
```

---

## 8. Troubleshooting

**`No peer found at /usr/local/bin/kairos or /usr/local/bin/aion`**
Build and install kairos (§2) or aion (§2a). `start-sidecar!` will print the
locations it searched.

**`MIDI init failed — peer running without MIDI output`**
The peer found no MIDI output ports. On macOS, enable the IAC Driver. On
Linux, install `libasound2-dev` before building the peer.

**`could not connect to peer socket`**
The peer binary failed to start. Check the process log printed to `*out*` after
`start-sidecar!`. Common causes: a stale `.sock` file in `/tmp` (delete it and
retry) or a port conflict on the OSC port.

**`lein` fails with `No such program: java` or `JAVA_HOME not set`**

```bash
# macOS
export JAVA_HOME=$(/usr/libexec/java_home)

# Linux
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
```

Add to your shell profile (`~/.zshrc`, `~/.bashrc`) to persist.

**Apple Silicon (arm64)**
Builds natively on Apple Silicon. No Rosetta required. CMake selects the
native architecture automatically.

**CMake FetchContent hangs or fails (peer repos)**
The first peer build fetches ~60 MB of C++ dependencies (RtMidi, Asio, etc.).
If air-gapped, pre-populate the `build/_deps/` cache on a connected machine
and copy it over before running CMake. Set
`-DFETCHCONTENT_UPDATES_DISCONNECTED=ON` (on by default in kairos/aion).

---

## 9. Directory layout

```
nous/
├── src/nous/            Clojure source (EPL-2.0)
│   ├── user.clj           Public REPL API, peer lifecycle
│   ├── loop.clj           deflive-loop, sleep!, sync!
│   ├── kairos.clj         kairos / aion IPC client
│   ├── ctrl.clj           Control tree (bind!, send!, undo!)
│   ├── link.clj           Ableton Link client
│   ├── m21.clj            Music21 corpus integration
│   ├── freesound.clj      Freesound API v2 client
│   └── ...                (35+ namespaces)
├── test/nous/           Clojure tests
├── resources/
│   └── devices/           EDN device maps (37+ instruments)
├── doc/                 User manual, design docs, licensing
├── project.clj          Leiningen build
├── BUILD.md             This file
└── README.md            Project overview
```

C++ peer repos (separate git repositories):

```
kairos/    CLAP host + audio + MIDI + Link   github.com/nomos-studio/kairos
aion/      MIDI + Link only (no CLAP)        github.com/nomos-studio/aion
nomos-rt/  Shared C++ substrate              github.com/nomos-studio/nomos-rt
alembic/   Faust → WASM → CLAP compiler      github.com/nomos-studio/alembic
```
