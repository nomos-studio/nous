; SPDX-License-Identifier: EPL-2.0
(ns nous.user
  "Convenience namespace for REPL sessions.

  Load this at the start of a session to get everything you need in scope:

    (require '[nous.user :refer :all])
    (session!)           ; start clock at 120 BPM, prints status
    (session! :bpm 140)  ; with custom BPM

  Everything in nous.core, nous.live, and the most-used theory namespaces
  is re-exported. You can also require just the pieces you need directly.

  Typical live session:

    (session!)
    (start-kairos! :binary \"/usr/local/bin/kairos\")  ; connect MIDI output

    ;; Simple loop
    (deflive-loop :kick {}
      (play! {:pitch/midi 36 :dur/beats 1/4})
      (sleep! 1))

    ;; Hot-swap the body any time
    (deflive-loop :kick {}
      (play! {:pitch/midi 38 :dur/beats 1/4})
      (sleep! 1/2))

    ;; Stop individual or all loops
    (stop-loop! :kick)
    (end-session!)

  See examples/ for full demonstrations."
  (:require [nous.analyze    :as analyze]
            [nous.arc        :as arc]
            [nous.ardour     :as ardour]
            [nous.link       :as link]
            [nous.chord      :as chord]
            [nous.conductor  :as conductor]
            [nous.core       :as core]
            [nous.live       :as live]
            [nous.fractal    :as frac]
            [nous.learn      :as learn]
            [nous.loop       :as loop-ns]
            [nous.mod        :as mod]
            [nous.pattern    :as pat]
            [nous.scala      :as scala]
            [nous.scale      :as scale]
            [nous.ensemble-improv :as ei]
            [nous.peer            :as peer]
            [nous.server          :as server]
            [nous.synth           :as synth]
            [nous.sc              :as sc]
            [nous.fm              :as fm]
            [nous.spectral        :as spectral]
            [nous.stochastic      :as stoch]
            [nous.texture    :as tx]
            [nous.trajectory :as traj]
            [nous.patch         :as patch]
            [nous.sample        :as smp]
            [nous.freesound     :as freesound]
            [nous.spatial-field :as sf]
            [nous.config     :as config]
            [nous.osc        :as osc]
            [nous.remote     :as remote]
            [nous.transform  :as xf]
            [nous.ivk        :as ivk]
            [nous.midi-in    :as midi-in]
            [nous.voice      :as voice]
            [nous.journey         :as journey]
            [nous.berlin          :as berlin]
            [nous.temporal-buffer :as tbuf]
            [nous.ctrl            :as ctrl-ns]
            [nous.supervisor      :as supervisor]
            [nous.arp             :as arp-ns]
            [nous.seq             :as sq]
            [nous.target          :as target]
            [nous.schema          :as schema]
            [nous.journal         :as journal]
            [nous.runtime         :as runtime]
            [nous.kairos          :as kairos]
            [nous.session         :as session]
            [nous.alembic         :as nalembic]
            [nous.book            :as book]
            [nous.excursion       :as excursion]
            [nous.defensemble     :as defensemble]
            [nous.lattice         :as lattice]
            [nous.terrain         :as terr]))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn session!
  "Start a nous session: boot the system clock and print a status summary.

  Options:
    :bpm — initial BPM (default 120)

  Does nothing if the system is already running (idempotent).

  Example:
    (session!)
    (session! :bpm 140)"
  [& {:keys [bpm] :or {bpm 120}}]
  (core/start! :bpm bpm)
  (println (str "Session ready — BPM " bpm
                "\n  (start-kairos! :binary \"/usr/local/bin/kairos\") to connect MIDI output"
                "\n  (end-session!) to stop"))
  nil)

(defn end-session!
  "Stop all loops and shut down the system."
  []
  (core/stop!)
  nil)

;; ---------------------------------------------------------------------------
;; Re-export the most-used core API
;; ---------------------------------------------------------------------------

(def start!               core/start!)
(def stop!                core/stop!)
(def open-session!        core/open-session!)
(def session-path         core/session-path)
(def export-session!      core/export-session!)
(def restore-session!     core/restore-session!)
(def export-from-journal! core/export-from-journal!)
(def load-session!        core/load-session!)

;; ---------------------------------------------------------------------------
;; Transaction journal — read and query
;; ---------------------------------------------------------------------------

(def read-journal      journal/read-journal)
(def tx-history        journal/tx-history)
(def tx-at             journal/tx-at)
(def tx-range          journal/tx-range)
(def tx-by-source      journal/tx-by-source)
(def active-paths      journal/active-paths)
(def latest-values     journal/latest-values)
(def crystallize       journal/crystallize)
(def diff-sessions     journal/diff-sessions)
(def source-kind->int  journal/source-kind->int)
(def int->source-kind  journal/int->source-kind)

;; Runtime state — ephemeral process health, never persisted
(def runtime-snapshot  runtime/snapshot)
(def runtime-get       runtime/get)
(def runtime-set!      runtime/set!)
(def runtime-watch!    runtime/watch!)
(def runtime-unwatch!  runtime/unwatch!)

(def set-bpm!             core/set-bpm!)
(def get-bpm              core/get-bpm)
(def set-beats-per-bar!   core/set-beats-per-bar!)
(def get-beats-per-bar    core/get-beats-per-bar)
(def time-sig!            core/time-sig!)
(def get-time-sig         core/get-time-sig)
(def bar-number           core/bar-number)
(def play!                core/play!)
(def sleep!               core/sleep!)
(def sync!                core/sync!)
(def stop-loop!           core/stop-loop!)
(def now                  core/now)
(defn apply-trajectory!
  "Apply an ITemporalValue trajectory.

  2-arity: (apply-trajectory! setter-fn traj) — calls setter-fn with each value.
  3-arity: (apply-trajectory! node-id param traj) — drives a live SC node parameter.

  Returns a cancel function."
  ([setter-fn traj]  (core/apply-trajectory! setter-fn traj))
  ([node-id param traj] (sc/apply-trajectory! node-id param traj)))

(defmacro deflive-loop [loop-name opts & body]
  `(core/deflive-loop ~loop-name ~opts ~@body))

(defmacro live-loop [loop-name opts & body]
  `(core/deflive-loop ~loop-name ~opts ~@body))

;; ---------------------------------------------------------------------------
;; Trajectory / arc types
;; ---------------------------------------------------------------------------

(def trajectory       traj/trajectory)
(def apply-curve      traj/apply-curve)
(def buildup          traj/buildup)
(def trance-buildup   traj/trance-buildup)
(def breakdown        traj/breakdown)
(def anticipation     traj/anticipation)
(def groove-lock      traj/groove-lock)
(def wind-down        traj/wind-down)
(def swell            traj/swell)
(def tension-peak     traj/tension-peak)
(def arc-merge        traj/arc-merge)
(def time-warp        traj/time-warp)
(def mod-route!    mod/mod-route!)
(def mod-unroute!  mod/mod-unroute!)
(def arc-bind!        arc/arc-bind!)
(def arc-unbind!      arc/arc-unbind!)
(def arc-send!        arc/arc-send!)
(def arc-routes       arc/arc-routes)
(def defconductor!    conductor/defconductor!)
(def fire!            conductor/fire!)
(def abort!           conductor/abort!)
(def cue!             conductor/cue!)
(def conductor-state  conductor/conductor-state)
(def conductor-names  conductor/conductor-names)

;; ---------------------------------------------------------------------------
;; Texture protocol
;; ---------------------------------------------------------------------------

(def deftexture!          tx/deftexture!)
(def get-texture          tx/get-texture)
(def texture-names        tx/texture-names)
(def buffer-texture       tx/buffer-texture)
(def texture-transition!  tx/texture-transition!)
(def shadow-init!         tx/shadow-init!)
(def shadow-update!       tx/shadow-update!)
(def shadow-get           tx/shadow-get)

;; ---------------------------------------------------------------------------
;; Signal graph patches
;; ---------------------------------------------------------------------------

(def defpatch!           patch/defpatch!)
(def get-patch           patch/get-patch)
(def patch-names         patch/patch-names)
(def patch-params        patch/patch-params)
(def compile-patch       patch/compile-patch)
(def canonical->backend  patch/canonical->backend)

;; Sample player
(def defbuffer!       smp/defbuffer!)
(def buffer-id        smp/buffer-id)
(def buffer-path      smp/buffer-path)
(def sample-names     smp/sample-names)
(def load-sample!     smp/load-sample!)
(def unload-sample!   smp/unload-sample!)
(def sample!          smp/sample!)
(def loop-sample!     smp/loop-sample!)
(def granular-cloud!  smp/granular-cloud!)

;; Freesound integration — fetch-on-use sample catalog
(def set-freesound-key!         freesound/set-api-key!)
(def fetch-sample!              freesound/fetch-sample!)
(def fetch-and-load!            freesound/fetch-and-load!)
(def freesound-info             freesound/sound-info)
(def search-freesound           freesound/search-freesound)
(def load-essentials!           freesound/load-essentials!)
(def load-and-prime-essentials! freesound/load-and-prime-essentials!)
(def curate-essentials!         freesound/curate-essentials!)

;; SC patch lifecycle (requires (connect-sc!) first)
(def instantiate-patch!  sc/instantiate-patch!)
(def set-patch-param!    sc/set-patch-param!)
(def free-patch!         sc/free-patch!)
(def sc-bus!             sc/sc-bus!)
(def free-bus!           sc/free-bus!)
(def bus-idx             sc/bus-idx)

;; ---------------------------------------------------------------------------
;; Spatial field
;; ---------------------------------------------------------------------------

(def defspatial-field!    sf/defspatial-field!)
(def start-field!         sf/start-field!)
(def stop-field!          sf/stop-field!)
(def stop-all-fields!     sf/stop-all-fields!)
(def field-state          sf/field-state)
(def field-transformer    sf/field-transformer)
(def play-through-field!  sf/play-through-field!)
(def get-field            sf/get-field)
(def field-names          sf/field-names)
(def quad-gains           sf/quad-gains)
(def ngon-walls           sf/ngon-walls)

;; ---------------------------------------------------------------------------
;; Spectral analysis
;; ---------------------------------------------------------------------------

(def start-spectral!  spectral/start-spectral!)
(def stop-spectral!   spectral/stop-spectral!)
(def spectral-ctx     spectral/spectral-ctx)

;; ---------------------------------------------------------------------------
;; Ensemble improv
;; ---------------------------------------------------------------------------

(def start-improv!        ei/start-improv!)
(def stop-improv!         ei/stop-improv!)
(def update-improv!       ei/update-improv!)
(def improv-state         ei/improv-state)
(def improv-gesture-fn    ei/improv-gesture-fn)
(def default-improv-profile ei/default-profile)

;; ---------------------------------------------------------------------------
;; Peer discovery and ctrl-tree mounting (Topology Layer 2)
;; ---------------------------------------------------------------------------

(def set-node-profile!   peer/set-node-profile!)
(def node-profile        peer/node-profile)
(def node-id             peer/node-id)
(def start-discovery!    peer/start-discovery!)
(def stop-discovery!     peer/stop-discovery!)
(def discovery-running?  peer/discovery-running?)
(def peers               peer/peers)
(def peer-info           peer/peer-info)
(def mount-peer!         peer/mount-peer!)
(def unmount-peer!       peer/unmount-peer!)
(def mounted-peers       peer/mounted-peers)
(def peer-harmony-ctx    peer/peer-harmony-ctx)
(def peer-spectral-ctx   peer/peer-spectral-ctx)
(def ctx->serial         peer/ctx->serial)
(def serial->scale       peer/serial->scale)

;; Topology Layer 3 — backends + session profile
(def register-backend!        peer/register-backend!)
(def deregister-backend!      peer/deregister-backend!)
(def active-backends          peer/active-backends)
(def peer-backends            peer/peer-backends)
(def publish-session-profile! peer/publish-session-profile!)

;; ---------------------------------------------------------------------------
;; nREPL remote eval (Topology Layer 3)
;; ---------------------------------------------------------------------------

(def connect-peer!    remote/connect!)
(def disconnect-peer! remote/disconnect!)
(def remote-eval!     remote/remote-eval!)
(def eval-on-peer!    remote/eval-on-peer!)
(defmacro with-peer [conn & body] `(remote/with-peer ~conn ~@body))

;; ---------------------------------------------------------------------------
;; Note transformers
;; ---------------------------------------------------------------------------

(def play-transformed!   xf/play-transformed!)
(def compose-xf          xf/compose-xf)
(def velocity-curve      xf/velocity-curve)
(def quantize            xf/quantize)
(def harmonize           xf/harmonize)
(def echo                xf/echo)
(def note-repeat         xf/note-repeat)
(def strum               xf/strum)
(def dribble             xf/dribble)
(def latch               xf/latch)

;; ---------------------------------------------------------------------------
;; Analysis
;; ---------------------------------------------------------------------------

(def pitch-class-dist          analyze/pitch-class-dist)
(def detect-key               analyze/detect-key)
(def detect-key-candidates    analyze/detect-key-candidates)
(def detect-mode              analyze/detect-mode)
(def identify-chord           analyze/identify-chord)
(def identify-chord-candidates analyze/identify-chord-candidates)
(def chord->roman             analyze/chord->roman)
(def analyze-progression      analyze/analyze-progression)
(def scale-fitness            analyze/scale-fitness)
(def suggest-scales           analyze/suggest-scales)
(def tension-score            analyze/tension-score)
(def progression-tension      analyze/progression-tension)
(def borrowed-chord?          analyze/borrowed-chord?)
(def annotate-progression     analyze/annotate-progression)
(def suggest-progression      analyze/suggest-progression)

;; ---------------------------------------------------------------------------
;; MIDI Learn — device map authoring
;; ---------------------------------------------------------------------------

(def start-learn-session!  learn/start-learn-session!)
(def learn-notes!          learn/learn-notes!)
(def learn-cc!             learn/learn-cc!)
(def learn-sequence!       learn/learn-sequence!)
(def cancel-learn!         learn/cancel-learn!)
(def learning?             learn/learning?)
(def learn-session-state   learn/learn-session-state)
(def clear-session!        learn/clear-session!)
(def export-device-map!    learn/export-device-map!)

;; ---------------------------------------------------------------------------
;; Re-export live context
;; ---------------------------------------------------------------------------

(defmacro with-dur [dur & body] `(live/with-dur ~dur ~@body))
(def use-harmony!   live/use-harmony!)

(defmacro with-harmony [scale & body]
  `(live/with-harmony ~scale ~@body))

(def use-chord!     live/use-chord!)
(def use-synth!     live/use-synth!)
(def use-tuning!    live/use-tuning!)
(def play-chord!    live/play-chord!)
(def play-voicing!  live/play-voicing!)
(defmacro phrase! [& args] `(live/phrase! ~@args))
(def ring           live/ring)
(def tick!          live/tick!)

(defmacro with-tuning [ctx & body]
  `(live/with-tuning ~ctx ~@body))

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; Scala microtonal scales
;; ---------------------------------------------------------------------------

(def parse-scl         scala/parse-scl)
(def load-scl          scala/load-scl)
(def degree-count      scala/degree-count)
(def degree->cents     scala/degree->cents)
(def degree->note      scala/degree->note)
(def degree->step      scala/degree->step)
(def interval-cents    scala/interval-cents)
(def parse-kbm         scala/parse-kbm)
(def load-kbm          scala/load-kbm)
(def midi->note        scala/midi->note)
(def midi->step        scala/midi->step)
(def scale->mts-bytes  scala/scale->mts-bytes)

;; ---------------------------------------------------------------------------
;; Ardour DAW integration
;; ---------------------------------------------------------------------------

(def connect-ardour!       ardour/connect!)
(def transport-play!       ardour/transport-play!)
(def transport-stop!       ardour/transport-stop!)
(def transport-record!     ardour/transport-record!)
(def goto-start!           ardour/goto-start!)
(def add-marker!           ardour/add-marker!)
(def ardour-save!          ardour/save!)
(def capture!              ardour/capture!)
(def capture-stop!         ardour/capture-stop!)
(def capture-discard!      ardour/capture-discard!)
(def capture-status        ardour/capture-status)
(def recording?            ardour/recording?)

;; ---------------------------------------------------------------------------
;; Synthesis vocabulary
;; ---------------------------------------------------------------------------

(def defsynth!      synth/defsynth!)
(def get-synth      synth/get-synth)
(def synth-names    synth/synth-names)
(def load-synth-map synth/load-synth-map)
(def compile-synth  synth/compile-synth)
(def map-graph      synth/map-graph)
(def synth-ugens    synth/synth-ugens)
(def transpose-synth synth/transpose-synth)
(def scale-amp      synth/scale-amp)
(def replace-arg    synth/replace-arg)
(def synth-backend  synth/synth-backend)
(def place-synth!   synth/place-synth!)

;; FM synthesis vocabulary
(def def-fm!       fm/def-fm!)
(def get-fm        fm/get-fm)
(def fm-names      fm/fm-names)
(def fm-algorithm  fm/fm-algorithm)
(def compile-fm    fm/compile-fm)

;; SuperCollider backend
(def connect-sc!         sc/connect-sc!)
(def disconnect-sc!      sc/disconnect-sc!)
(def start-sc!           sc/start-sc!)
(def stop-sc!            sc/stop-sc!)
(def sc-restart!         sc/sc-restart!)
(def sc-connected?       sc/sc-connected?)
(def synthdef-str        sc/synthdef-str)
(def send-synthdef!      sc/send-synthdef!)
(def send-all-synthdefs! sc/send-all-synthdefs!)
(def ensure-synthdef!    sc/ensure-synthdef!)
(def sc-synth!         sc/sc-synth!)
(def set-param!        sc/set-param!)
(def free-synth!       sc/free-synth!)
(def kill-synth!       sc/kill-synth!)
(def sc-play!          sc/sc-play!)
(def ramp-param!       sc/ramp-param!)
(def sc-status         sc/sc-status)
(def bind-spectral!    sc/bind-spectral!)
(def unbind-spectral!  sc/unbind-spectral!)

;; ---------------------------------------------------------------------------
;; HTTP control server + WebSocket broadcast
;; ---------------------------------------------------------------------------

(def start-server!    server/start-server!)
(def stop-server!     server/stop-server!)
(def server-port      server/server-port)
(def server-running?  server/server-running?)

;; Global ctrl-tree watchers — fire on every set!/send! regardless of path.
;; Primary use: server-side WebSocket broadcast, but open to any subscriber.
(def watch-global!    ctrl-ns/watch-global!)
(def unwatch-global!  ctrl-ns/unwatch-global!)

;; Transaction log and tree introspection
(def tx-log           ctrl-ns/tx-log)
(def child-keys       ctrl-ns/child-keys)

;; ---------------------------------------------------------------------------
;; OSC receive + push-subscribe (§17 Phase 2/3)
;; ---------------------------------------------------------------------------

(def start-osc-server!       osc/start-osc-server!)
(def stop-osc-server!        osc/stop-osc-server!)
(def osc-running?            osc/osc-running?)
(def osc-port                osc/osc-port)
(def osc-send!               osc/osc-send!)
(def osc-subscribe!          osc/subscribe!)
(def osc-unsubscribe!        osc/unsubscribe!)
(def osc-unsubscribe-all!    osc/unsubscribe-all!)
(def osc-subscribers         osc/subscribers)
(def ctrl-path->osc-address  osc/ctrl-path->osc-address)

;; ---------------------------------------------------------------------------
;; MIDI send shorthand (kairos)
;; ---------------------------------------------------------------------------

(def send-cc!                kairos/send-cc!)
(def send-pitch-bend!        kairos/send-pitch-bend!)
(def send-channel-pressure!  kairos/send-channel-pressure!)
(def send-sysex!             kairos/send-sysex!)
(def send-mts!               kairos/send-mts!)
(def await-midi-message      kairos/await-midi-message)
(def midi-in-messages        kairos/midi-in-messages)

(defn start-sidecar!
  "Deprecated. Delegates to start-kairos! — the sidecar is retired.
  Use (start-kairos! :binary path) directly."
  [& {:keys [binary] :as opts}]
  (println "[user] start-sidecar! is deprecated; delegating to start-kairos!")
  (kairos/start-kairos! :binary (or binary "/usr/local/bin/kairos"))
  nil)

(defn stop-sidecar!
  "Deprecated. Delegates to stop-kairos! — the sidecar is retired."
  []
  (kairos/stop-kairos!)
  nil)

;; ---------------------------------------------------------------------------
;; kairos (nous.kairos)
;; ---------------------------------------------------------------------------

;; Connection and status
(def kairos-connected?       kairos/connected?)
(def connect-kairos!         kairos/connect!)
(def disconnect-kairos!      kairos/disconnect!)
;; Process lifecycle
(def start-kairos!           kairos/start-kairos!)
(def stop-kairos!            kairos/stop-kairos!)
(def restart-kairos!         kairos/restart-kairos!)
;; Supervisor integration
(def register-kairos!        supervisor/register-kairos!)
;; Graph management
(def send-graph-load!        kairos/send-graph-load!)
(def send-graph-reset!       kairos/send-graph-reset!)
;; Plugin discovery
(def list-plugins!           kairos/list-plugins!)
(def plugin-registry         kairos/plugin-registry)
;; Parameter control
(def send-param-set!         kairos/send-param-set!)
;; WASM hot-swap
(def send-wasm-hot-swap!     kairos/send-wasm-hot-swap!)
;; Note / MIDI events
(def kairos-note-on!         kairos/send-note-on!)
(def kairos-note-off!        kairos/send-note-off!)
(def kairos-midi-in!         kairos/send-midi-in!)
;; Beat-accurate bundle scheduling
(def schedule-bundle!        kairos/schedule-bundle!)
;; 24 PPQN tick callbacks — aion/kairos pushes MSG-TICK on every beat tick
(def on-tick!                kairos/on-tick!)
(def off-tick!               kairos/off-tick!)
;; RT modulator engine — autonomous modulators running in kairos/aion
(def start-modulator!        kairos/start-modulator!)
(def stop-modulator!         kairos/stop-modulator!)
(def update-modulator!       kairos/update-modulator!)
;; Session logging
(def kairos-session-open!    kairos/send-session-open!)
(def kairos-session-close!   kairos/send-session-close!)
(def kairos-register-source! kairos/send-register-source!)
(def kairos-tx-log!          kairos/send-tx-log!)

;; Register :kairos as a MIDI target.  Allows
;;   (play! {:target :kairos :pitch/midi 60 :dur/beats 1/4})
;; as an explicit alternative to the default kairos/connected? dispatch path.
(target/register! :kairos
  (target/fn-target :kairos
    :live?-fn   kairos/connected?
    :param-root [:kairos]
    :trigger-fn (fn [event]
                  (let [note    (or (:pitch/midi event) 60)
                        vel     (or (:mod/velocity event) 64)
                        channel (or (:midi/channel event) 1)
                        beat    (double (or loop-ns/*virtual-time* 0.0))
                        dur     (double (or (:dur/beats event) 1/4))]
                    (kairos/send-note-on!  note vel :channel channel :beat beat)
                    (kairos/send-note-off! note     :channel channel :beat (+ beat dur))
                    {:note note :channel channel}))
    :release-fn (fn [handle]
                  (when handle
                    (kairos/send-note-off! (:note handle)
                                           :channel (:channel handle))))))

;; ---------------------------------------------------------------------------
;; Session topology (nous.session)
;; ---------------------------------------------------------------------------

(def session->graph        session/session->graph)
(def load-session!         session/load-session!)
(def active-session        session/active-session)
(def reload-session!       session/reload-session!)
(def clear-session!        session/clear-session!)
(def kairos-grid-plugin-id session/*kairos-grid-plugin-id*)
(def clear-placed-nodes!   session/clear-placed-nodes!)

;; ---------------------------------------------------------------------------
;; Alembic Faust→WASM pipeline (nous.alembic)
;; ---------------------------------------------------------------------------

(def alembic-compile!      nalembic/compile!)
(def alembic-patch-desc    nalembic/patch-descriptor)
(def alembic-load-desc!    nalembic/load-descriptor)
(def alembic-load-patch!   nalembic/load-patch!)
(def alembic-hot-swap!     nalembic/hot-swap!)

;; ---------------------------------------------------------------------------
;; Book of Sounds sequencer (nous.book)
;; ---------------------------------------------------------------------------

(defmacro defbook [& args] `(book/defbook ~@args))
(def next-step!          book/next-step!)
(def go-page!            book/go-page!)
(def reset-cell!         book/reset-cell!)
(def make-book-seq       book/make-book-seq)
(def make-book-context   book/make-book-context)
(def book-names          book/book-names)
(def current-page        book/current-page)
(def current-harmonic    book/current-harmonic)

;; ---------------------------------------------------------------------------
;; Harmonic lattice sequencer (nous.lattice)
;; ---------------------------------------------------------------------------

(defmacro deflattice [& args] `(lattice/deflattice ~@args))
(def lattice-next-step!     lattice/next-step!)
(def lattice-set-attractor! lattice/set-attractor!)
(def lattice-set-nav-mode!  lattice/set-nav-mode!)
(def lattice-jump!          lattice/jump!)
(def make-lattice-seq       lattice/make-lattice-seq)
(def make-lattice-context   lattice/make-lattice-context)
(def lattice-names          lattice/lattice-names)
(def current-position       lattice/current-position)
(def current-attractor      lattice/current-attractor)

;; ---------------------------------------------------------------------------
;; Excursion arc (nous.excursion)
;; ---------------------------------------------------------------------------

(defmacro defexcursion [& args] `(excursion/defexcursion ~@args))
(def excursion-next-step!    excursion/next-step!)
(def skip-to-phase!          excursion/skip-to-phase!)
(def restart-excursion!      excursion/restart!)
(def make-excursion-seq      excursion/make-excursion-seq)
(def make-excursion-context  excursion/make-excursion-context)
(def excursion-names         excursion/excursion-names)
(def current-phase           excursion/current-phase)
(def phase-progress          excursion/phase-progress)

;; ---------------------------------------------------------------------------
;; Ensemble tension monitor (nous.defensemble)
;; ---------------------------------------------------------------------------

(defmacro defensemble [& args] `(defensemble/defensemble ~@args))
(def ensemble-run-update!          defensemble/run-update!)
(def ensemble-start-monitor!       defensemble/start-monitor!)
(def ensemble-stop-monitor!        defensemble/stop-monitor!)
(def ensemble-names                defensemble/ensemble-names)
(def ensemble-tension              defensemble/ensemble-tension)
(def ensemble-consonance           defensemble/ensemble-consonance)
(def ensemble-parallel-pairs       defensemble/ensemble-parallel-pairs)
(def make-ensemble-context         defensemble/make-ensemble-context)

;; ---------------------------------------------------------------------------
;; Keyboard layout (nous.ivk)
;; ---------------------------------------------------------------------------

(def start-kbd!         ivk/start-kbd!)
(def stop-kbd!          ivk/stop-kbd!)
(def register-layout!   ivk/register-layout!)
(def set-layout!        ivk/set-layout!)
(def set-pitch!         ivk/set-pitch!)
(def current-pitch      ivk/current-pitch)
(def start-arp!         ivk/start-arp!)
(def stop-arp!          ivk/stop-arp!)
(def render-layout      ivk/render-layout)

;; ---------------------------------------------------------------------------
;; Arpeggiator pattern library (nous.arp)
;; ---------------------------------------------------------------------------

(def arp-ls             arp-ns/ls)
(def arp-get            arp-ns/get-pattern)
(def arp-register!      arp-ns/register!)
(def arp-play!          arp-ns/play!)
(def make-arp-state     arp-ns/make-arp-state)
(def reset-arp-chord!   arp-ns/reset-chord!)

;; ---------------------------------------------------------------------------
;; IStepSequencer runners (nous.seq)
;; ---------------------------------------------------------------------------

(def run-step!          sq/run-step!)
(def run-cycle!         sq/run-cycle!)
(def seq-loop!          sq/seq-loop!)
(def stop-seq!          sq/stop-seq!)

;; ---------------------------------------------------------------------------
;; Audio target registry (nous.target)
;; ---------------------------------------------------------------------------

(def register-target!   target/register!)
(def lookup-target      target/lookup)
(def registered-targets target/registered-targets)
(def fn-target          target/fn-target)
(def param-target       target/param-target)

;; ---------------------------------------------------------------------------
;; Device model schema (nous.schema)
;; ---------------------------------------------------------------------------

(def defdevice-model      schema/defdevice-model)
(def defrealization       schema/defrealization)
(def realize!             schema/realize!)
(def get-model            schema/get-model)
(def get-realization      schema/get-realization)
(def active-realization   schema/active-realization)
(def list-models          schema/list-models)
(def list-realizations    schema/list-realizations)
(def satisfies-profile?   schema/satisfies-profile?)

;; ---------------------------------------------------------------------------
;; MIDI input (nous.midi-in)
;; ---------------------------------------------------------------------------

(def open-input!              midi-in/open-input!)
(def close-input!             midi-in/close-input!)
(def close-all-inputs!        midi-in/close-all-inputs!)
(def open-inputs              midi-in/open-inputs)
(def register-note-handler!   midi-in/register-note-handler!)
(def unregister-note-handler! midi-in/unregister-note-handler!)

;; ---------------------------------------------------------------------------
;; Ableton Link (Phase 1 + Phase 2)
;; ---------------------------------------------------------------------------

(def link-enable!             link/enable!)
(def link-disable!            link/disable!)
(def link-active?             link/active?)
(def link-bpm                 link/bpm)
(def link-peers               link/peers)
(def link-playing?            link/playing?)
(def link-set-bpm!            link/set-bpm!)
(def link-start-transport!    link/start-transport!)
(def link-stop-transport!     link/stop-transport!)
(def on-transport-change!     link/on-transport-change!)
(def remove-transport-hook!   link/remove-transport-hook!)
(def link-state               link/link-state)
(def link-timeline            link/link-timeline)
(def next-quantum-beat        link/next-quantum-beat)

;; ---------------------------------------------------------------------------
;; Configuration registry (§25)
;; ---------------------------------------------------------------------------

(def get-config       config/get-config)
(def set-config!      config/set-config!)
(def all-configs      config/all-configs)
(def all-param-keys   config/all-param-keys)
(def param-info       config/param-info)

;; ---------------------------------------------------------------------------
;; Handy theory shortcuts at the top level
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Kosmische vocabulary — nous.journey + nous.berlin
;; ---------------------------------------------------------------------------

;; Journey conductor
(def start-bar-counter!  journey/start-bar-counter!)
(def stop-bar-counter!   journey/stop-bar-counter!)
(def reset-bar-counter!  journey/reset-bar-counter!)
(def current-bar         journey/current-bar)
(def start-journey!      journey/start-journey!)
(def stop-journey!       journey/stop-journey!)
(def phase-pair          journey/phase-pair)
(def humanise            journey/humanise)
(def phaedra-arc         journey/phaedra-arc)

;; Berlin School / Kosmische ostinato + performance tools
(def ostinato            berlin/ostinato)
(def next-step!          berlin/next-step!)
(def reset-ostinato!     berlin/reset-ostinato!)
(def freeze-ostinato!    berlin/freeze-ostinato!)
(def thaw-ostinato!      berlin/thaw-ostinato!)
(def deflect-ostinato!   berlin/deflect-ostinato!)
(def crystallize!        berlin/crystallize!)
(def dissolve!           berlin/dissolve!)
(def set-mutation-trajectory! berlin/set-mutation-trajectory!)
(def set-portamento!     berlin/set-portamento!)
(defmacro with-portamento [channel time-ms & body]
  `(berlin/with-portamento ~channel ~time-ms ~@body))
(def filter-journey!     berlin/filter-journey!)
(def phase-drift!        berlin/phase-drift!)
(def clear-drift!        berlin/clear-drift!)
(def tuning-morph!       berlin/tuning-morph!)
(def tape-drift          berlin/tape-drift)
(def tick-tape!          berlin/tick-tape!)
(def frippertronics!     berlin/frippertronics!)
(def sos-send!           berlin/sos-send!)

;; ---------------------------------------------------------------------------
;; Terrain sequencer — 3D fractal sequence space (nous.terrain)
;; ---------------------------------------------------------------------------

(defmacro defterrain [gen-name & opts] `(terr/defterrain ~gen-name ~@opts))
(def terrain-next-step!  terr/next-terrain-step!)
(def make-terrain-seq    terr/make-terrain-seq)
(def terrain-names       terr/terrain-names)
(def terrain-step        terr/terrain-step)
(def terrain-seq-at      terr/terrain-seq)
(def z->path             terr/z->path)

;; Temporal buffer — direct access for Hold, Flip, Color, Feedback, zone switching
(def deftemporal-buffer       tbuf/deftemporal-buffer)
(def deftemporal-buffer-preset tbuf/deftemporal-buffer-preset)
(def temporal-buffer-send!    tbuf/temporal-buffer-send!)
(def temporal-buffer-zone!    tbuf/temporal-buffer-zone!)
(def temporal-buffer-hold!    tbuf/temporal-buffer-hold!)
(def temporal-buffer-flip!    tbuf/temporal-buffer-flip!)
(def temporal-buffer-rate!    tbuf/temporal-buffer-rate!)
(def temporal-buffer-color!   tbuf/temporal-buffer-color!)
(def temporal-buffer-feedback! tbuf/temporal-buffer-feedback!)
(def temporal-buffer-halo!    tbuf/temporal-buffer-halo!)
(def temporal-buffer-info     tbuf/temporal-buffer-info)
(def temporal-buffer-set!     tbuf/temporal-buffer-set!)
(def stop-temporal-buffer!    tbuf/stop!)
(def color-presets            tbuf/color-presets)
(def buffer-presets           tbuf/presets)

;; Supervisor — service health, events, watchdog
(def register-service!      supervisor/register!)
(def deregister-service!    supervisor/deregister!)
(def register-sc!           supervisor/register-sc!)
(def register-sidecar!      supervisor/register-sidecar!)
(def start-watchdog!        supervisor/start-watchdog!)
(def stop-watchdog!         supervisor/stop-watchdog!)
(def service-status         supervisor/service-status)
(def all-service-statuses   supervisor/all-statuses)
(def on-supervisor-event!   supervisor/on-event!)
(def off-supervisor-event!  supervisor/off-event!)
(def restart-loop!          loop-ns/restart-loop!)

(def choose-from-scale  scale/choose-from-scale)

(defn make-scale
  "Build a Scale record. Common modal shorthand.

  Examples:
    (make-scale :C 4 :major)
    (make-scale :D 4 :dorian)
    (make-scale :A 3 :pentatonic-minor)"
  [root octave mode]
  (scale/scale root octave mode))

(defn make-chord
  "Build a Chord record.

  Examples:
    (make-chord :C 4 :maj7)
    (make-chord :G 3 :dominant7)"
  [root octave quality]
  (chord/chord root octave quality))

(defn progression
  "Build a sequence of chords from Roman numeral keywords.

  Example:
    (progression (make-scale :C 4 :major) [:I :IV :V7 :I])"
  [scale degrees]
  (chord/progression scale degrees))
