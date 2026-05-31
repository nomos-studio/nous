; SPDX-License-Identifier: EPL-2.0
(ns nous.synth
  "Synthesis graph vocabulary — engine-agnostic synth definitions.

  A synth definition is a Clojure map with two required keys:

    :args   — map of argument names to default values
    :graph  — hiccup-style UGen graph; top-level node must be [:out ...]

  Graphs are Clojure data: inspectable, transformable, serializable to EDN,
  and compilable to multiple backends via the `compile-synth` multimethod.

  ## Defining a synth

    (defsynth! :pad
      {:args  {:freq 440 :amp 0.5 :attack 0.01 :release 1.0 :pan 0.0}
       :graph [:out 0
                [:pan2
                 [:* [:env-gen [:adsr :attack 0.1 0.8 :release] :gate :amp]
                     [:sin-osc :freq]]
                 :pan]]})

  ## Graph node types

    keyword   — argument reference (:freq → the `freq` arg)
    number    — numeric literal
    vector    — UGen call: [:ugen-name & args]

  ## Standard UGen names

    Oscillators: :sin-osc :saw :pulse :var-saw :lf-tri :white-noise :pink-noise
    Envelopes:   :env-gen :adsr :perc :linen
    Filters:     :lpf :hpf :rlpf :moog-ff
    Effects:     :free-verb :comb-n :allpass-n
    Mix/Pan:     :mix :pan2 :balance2
    Math:        :* :+ :- :/ :clip :lag :abs
    Output:      :out

  ## Compilation backends

    (compile-synth :sc   synth-name)   — sclang SynthDef string
    (compile-synth :sc-node synth-map) — OSC /s_new args map

  ## Graph transforms

    (map-graph f synth)    — apply f to every node in the graph
    (synth-ugens synth)    — collect all UGen names used
    (transpose-synth synth semitones) — shift :freq arg default

  ## Built-in synths

  Five synths are pre-loaded from resources/synths/:
    :sine     — ADSR sine wave
    :saw-pad  — detuned saw pair
    :blade    — triple VarSaw + LPF (Sonic-Pi :blade equivalent)
    :perc     — sine percussion with pitch decay
    :prophet  — saw+pulse + RLPF (Sonic-Pi :prophet equivalent)"
  (:require [clojure.java.io :as io]
            [clojure.edn     :as edn]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private synth-registry (atom {}))

(defn defsynth!
  "Register a named synth definition.

  SC-backed synths require:
    :args   — map of {arg-kw default-value}
    :graph  — hiccup UGen graph; top-level node must be [:out ...]

  CLAP-backed synths require:
    :clap/plugin-id — CLAP plugin ID string (e.g. \"com.surge-synth-team.surge-xt\")
    :args           — optional map of default param values (defaults to {})

  Optional for both:
    :synth/tags — seq of keyword tags
    :synth/doc  — docstring

  Idempotent: re-registering replaces the previous definition.

  Examples:
    ;; SC-backed
    (defsynth! :pad
      {:args  {:freq 440 :amp 0.5}
       :graph [:out 0 [:sin-osc :freq]]})

    ;; CLAP-backed
    (defsynth! :surge-xt
      {:clap/plugin-id \"com.surge-synth-team.surge-xt\"
       :args           {:freq 440 :amp 1.0}})"
  [synth-name synth-map]
  (when-not (keyword? synth-name)
    (throw (ex-info "defsynth!: synth name must be a keyword" {:name synth-name})))
  (when (and (contains? synth-map :args) (not (map? (:args synth-map))))
    (throw (ex-info "defsynth!: :args must be a map" {:synth synth-name})))
  (when (and (not (:clap/plugin-id synth-map))
             (not (vector? (:graph synth-map))))
    (throw (ex-info "defsynth!: :graph must be a vector (or supply :clap/plugin-id)"
                    {:synth synth-name})))
  (swap! synth-registry assoc synth-name
         (cond-> synth-map (not (contains? synth-map :args)) (assoc :args {})))
  synth-name)

(defn register-precompiled!
  "Register a named synth whose SC SynthDef string is already compiled.

  Used by `nous.sc/send-fm-synthdef!` to make FM synths available via
  `sc-synth!` and `sc-play!` without going through the graph compiler.

  `synth-name` — keyword name the synth will be addressable as
  `args-map`   — default arg values (same shape as :args in defsynth!)
  `sc-string`  — complete sclang SynthDef string

  The stored entry carries `:sc/precompiled` so `sc/synthdef-str` can
  return it directly instead of trying to compile a :graph."
  [synth-name args-map sc-string]
  (when-not (keyword? synth-name)
    (throw (ex-info "register-precompiled!: synth name must be a keyword" {:name synth-name})))
  (swap! synth-registry assoc synth-name
         {:args         (or args-map {})
          :graph        [:out 0 [:sin-osc 440]]   ; placeholder — never compiled
          :sc/precompiled sc-string})
  synth-name)

(defn get-synth
  "Return the synth definition for `synth-name`, or nil if not found."
  [synth-name]
  (get @synth-registry synth-name))

(defn synth-names
  "Return a sorted seq of all registered synth names."
  []
  (sort (keys @synth-registry)))

;; ---------------------------------------------------------------------------
;; Graph utilities
;; ---------------------------------------------------------------------------

(defn map-graph
  "Apply `f` to every node in `graph`, returning a transformed graph.
  `f` receives each node (keyword, number, or vector) and returns the
  replacement. Called depth-first — children are transformed before parents."
  [f graph]
  (cond
    (vector? graph)
    (f (into [(first graph)] (map (partial map-graph f) (rest graph))))
    :else
    (f graph)))

(defn synth-ugens
  "Return the set of all UGen names (keywords) referenced in `synth`'s graph."
  [synth]
  (let [ugens (atom #{})]
    (map-graph (fn [node]
                 (when (and (vector? node) (keyword? (first node)))
                   (swap! ugens conj (first node)))
                 node)
               (:graph synth))
    @ugens))

(defn transpose-synth
  "Return a new synth with the :freq default shifted by `semitones`."
  [synth semitones]
  (update-in synth [:args :freq]
             (fn [f] (when f (* f (Math/pow 2.0 (/ semitones 12.0)))))))

(defn scale-amp
  "Return a new synth with the :amp default multiplied by `factor`."
  [synth factor]
  (update-in synth [:args :amp] (fn [a] (when a (* a factor)))))

(defn replace-arg
  "Return a new synth with arg `k` default replaced by `v`."
  [synth k v]
  (assoc-in synth [:args k] v))

;; ---------------------------------------------------------------------------
;; Compilation multimethod
;; ---------------------------------------------------------------------------

(defmulti compile-synth
  "Compile a registered synth to a backend-specific representation.

  Dispatch on `backend` keyword. Built-in backends:
    :sc  — returns a sclang SynthDef string (from nous.sc)

  Example:
    (compile-synth :sc :blade)
    ;=> \"SynthDef(\\\\blade, { |freq=440.0, ..., gate=1| ... }).add;\""
  (fn [backend _synth-name] backend))

(defmethod compile-synth :default [backend synth-name]
  (throw (ex-info "compile-synth: unknown backend"
                  {:backend backend :synth synth-name})))

;; ---------------------------------------------------------------------------
;; Backend identification
;; ---------------------------------------------------------------------------

(defn synth-backend
  "Return the synthesis backend for a named synth.
  :clap — CLAP plugin via kairos (:clap/plugin-id present in the definition)
  :sc   — SuperCollider UGen graph (default for all other registered synths)
  nil   — synth not registered"
  [synth-name]
  (when-let [s (get-synth synth-name)]
    (if (:clap/plugin-id s) :clap :sc)))

;; ---------------------------------------------------------------------------
;; Backend dispatch — place-synth!
;; ---------------------------------------------------------------------------

(defmulti place-synth!
  "Instantiate a named synth in the appropriate backend.

  node-id    — keyword identifying this instance.
               For kairos/CLAP: used as the graph node key (addressable via
               send-param-set!, etc.).
               For SC: passed for symmetry; SC generates its own integer node ID.
  synth-name — keyword registered via defsynth!

  Options:
    :params — map of parameter values to apply on top of the synth's :args defaults

  Returns:
    :clap backend → node-id keyword
    :sc   backend → SC node integer

  Backend is resolved automatically from the synth definition (synth-backend).
  Methods are registered by nous.session (:clap) and nous.sc (:sc).

  Examples:
    ;; CLAP synth placed in kairos
    (defsynth! :surge-xt {:clap/plugin-id \"com.surge-synth-team.surge-xt\" :args {}})
    (place-synth! :lead :surge-xt)
    (place-synth! :lead :surge-xt {:params {:freq 880}})

    ;; SC synth instantiated in scsynth
    (place-synth! :my-pad :pad)"
  (fn [_node-id synth-name & _] (synth-backend synth-name)))

(defmethod place-synth! nil [_node-id synth-name & _]
  (throw (ex-info "place-synth!: synth not found" {:name synth-name})))

;; ---------------------------------------------------------------------------
;; EDN loader — resources/synths/
;; ---------------------------------------------------------------------------

(defn load-synth-map
  "Load a synth definition from an EDN file on the classpath.

  `path` is resolved relative to the classpath root:
    (load-synth-map \"synths/sine.edn\")

  Returns the parsed synth map."
  [path]
  (if-let [url (io/resource path)]
    (edn/read-string (slurp url))
    (throw (ex-info "nous.synth/load-synth-map: file not found"
                    {:path path}))))

(defn- load-builtin! [filename]
  (let [m (load-synth-map (str "synths/" filename))
        id (:synth/id m)]
    (defsynth! id m)))

;; ---------------------------------------------------------------------------
;; Boot — load built-in synths
;; ---------------------------------------------------------------------------

(defonce ^:private _builtins-loaded
  (do
    (load-builtin! "beep.edn")
    (load-builtin! "sine.edn")
    (load-builtin! "saw-pad.edn")
    (load-builtin! "blade.edn")
    (load-builtin! "prophet.edn")
    (load-builtin! "supersaw.edn")
    (load-builtin! "dull-bell.edn")
    (load-builtin! "fm.edn")
    (load-builtin! "tb303.edn")
    (load-builtin! "perc.edn")
    (load-builtin! "sine-bus.edn")
    (load-builtin! "reverb-bus.edn")
    (load-builtin! "buf-player.edn")
    (load-builtin! "buf-looper.edn")
    (load-builtin! "granular-voice.edn")
    ;; Sonic-Pi / Overtone parity synths
    (load-builtin! "saw.edn")
    (load-builtin! "tri.edn")
    (load-builtin! "pulse.edn")
    (load-builtin! "subpulse.edn")
    (load-builtin! "dsaw.edn")
    (load-builtin! "dpulse.edn")
    (load-builtin! "dtri.edn")
    (load-builtin! "pretty-bell.edn")
    (load-builtin! "pluck.edn")
    (load-builtin! "hollow.edn")
    (load-builtin! "zawa.edn")
    (load-builtin! "dark-ambience.edn")
    (load-builtin! "growl.edn")
    (load-builtin! "noise.edn")
    (load-builtin! "bass.edn")
    ;; s42 instrument model (Solar42-inspired)
    (load-builtin! "s42-drone-voice.edn")
    (load-builtin! "s42-vco-voice.edn")
    (load-builtin! "s42-papa-voice.edn")
    (load-builtin! "s42-filter.edn")
    ;; SuperKarplus-inspired warpable KS voice
    (load-builtin! "superkar-voice.edn")
    ;; Chaos synthesis — Lorenz, Hénon, and hybrid FM-chaos voices
    (load-builtin! "chaos-lorenz.edn")
    (load-builtin! "chaos-henon.edn")
    (load-builtin! "lorenz-fm.edn")
    ;; Waveshaping — tanh saturation and wavefold
    (load-builtin! "wshape-saturate.edn")
    (load-builtin! "wshape-fold.edn")
    ;; Physical modeling — DynKlank resonant filter banks
    (load-builtin! "klank-bell.edn")
    (load-builtin! "klank-bars.edn")
    true))
