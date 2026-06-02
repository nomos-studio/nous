; SPDX-License-Identifier: EPL-2.0
(ns nous.alembic
  "Alembic Faust→WASM pipeline integration for kairos-grid.

  Bridges alembic.compile/compile-to-wasm with the kairos-grid CLAP plugin,
  enabling live REPL-driven DSP authoring:

    ;; 1. Define a DSP graph with alembic
    (require '[alembic.core :as a])
    (def my-osc (a/graph [:osc {:freq 440}] [:out]))

    ;; 2. Compile and load into the running kairos instance
    (require '[nous.alembic :as na])
    (na/load-patch! my-osc)

    ;; 3. Iterate — recompile and replace the patch slot
    (na/load-patch! updated-graph :name \"my-osc\" :node-id :voice)

    ;; 4. Or work with a pre-compiled .wasm file
    (na/load-descriptor (na/patch-descriptor \"/tmp/my-synth.wasm\"))

  The :node-id key identifies the kairos-grid node within the kairos graph.
  Multiple WASM synths can coexist by using distinct :node-id values.

  Note: load-patch! rebuilds the patch slot (brief gap in audio). Gapless
  hot-swap requires kairos-grid to implement k_kairos_ext_hot_swap (planned)."
  (:require [alembic.compile :as ac]
            [nous.session    :as session]))

;; ---------------------------------------------------------------------------
;; Session descriptor construction (pure)
;; ---------------------------------------------------------------------------

(defn- wasm-patch
  "kairos-grid :patch map: one WASM module + audio-out, with cable connections."
  [wasm-path channels]
  {:modules [{:id :synth :type "wasm" :wasm-path wasm-path}
             {:id :out   :type "audio-out"}]
   :cables  (mapv (fn [ch] [:synth ch :out ch]) (range channels))})

(defn- default-routes
  "Stereo routes from node outputs to master left/right.
  Mono (channels=1) fans a single output to both sides."
  [node-id channels]
  (if (= channels 1)
    [{:from [node-id 0] :to :master/left}
     {:from [node-id 0] :to :master/right}]
    [{:from [node-id 0] :to :master/left}
     {:from [node-id 1] :to :master/right}]))

(defn patch-descriptor
  "Build a nomos-topology session map for a WASM module at wasm-path.

  This is the pure data construction step — no compilation happens.
  Useful for loading pre-compiled .wasm files or for inspecting the
  generated topology before sending it to kairos.

  Options:
    :node-id  — kairos node keyword (default :voice)
    :channels — number of audio output channels (default 2)

  Example:
    (na/patch-descriptor \"/tmp/my-synth.wasm\")
    (na/patch-descriptor \"/tmp/my-synth.wasm\" :node-id :filter :channels 1)"
  [wasm-path & {:keys [node-id channels]
                :or   {node-id :voice channels 2}}]
  {:topology
   {:nodes  [{:id    node-id
              :type  :kairos-grid
              :patch (wasm-patch wasm-path channels)}]
    :routes (default-routes node-id channels)}})

;; ---------------------------------------------------------------------------
;; Compilation
;; ---------------------------------------------------------------------------

(defn compile!
  "Compile an alembic graph to a Faust WASM module.

  Requires `faust` >= 2.50 on PATH (brew install faust).

  Returns {:wasm-path string :name string} on success.
  Throws ex-info with :errors and :source on Faust compilation failure.

  Options:
    :name    — stem for the output .wasm/.json files (default \"alembic-patch\")
    :out-dir — output directory (default: system temp dir)

  Example:
    (na/compile! my-graph)
    (na/compile! my-graph :name \"phasor\" :out-dir \"/tmp/alembic\")"
  [graph & {:keys [name out-dir] :or {name "alembic-patch"}}]
  (let [path (if out-dir
               (ac/compile-to-wasm graph :name name :out-dir out-dir)
               (ac/compile-to-wasm graph :name name))]
    {:wasm-path path :name name}))

;; ---------------------------------------------------------------------------
;; Load
;; ---------------------------------------------------------------------------

(defn load-descriptor
  "Load a pre-built session descriptor (from patch-descriptor) into kairos.

  Calls nous.session/load-session! and returns the translated graph map.
  Throws if kairos is not connected.

  Example:
    (na/load-descriptor (na/patch-descriptor \"/tmp/my-synth.wasm\"))"
  [descriptor]
  (session/load-session! descriptor))

(defn load-patch!
  "Compile an alembic graph to WASM and load it into the running kairos instance.

  Compiles `graph` via alembic.compile, constructs a minimal kairos-grid patch
  (one WASM synth module wired to audio-out), then calls session/load-session!.

  Returns the translated kairos graph map on success.
  Throws if Faust compilation fails or kairos is not connected.

  Note: rebuilds the patch slot — there is a brief gap in audio output.
  For gapless iteration, implement k_kairos_ext_hot_swap in kairos-grid (planned).

  Options:
    :name     — module name / .wasm file stem (default \"alembic-patch\")
    :node-id  — kairos graph node keyword (default :voice)
    :channels — number of audio output channels (default 2)
    :out-dir  — WASM output directory (default: system temp dir)

  Example:
    (na/load-patch! my-graph)
    (na/load-patch! my-graph :name \"filter\" :node-id :filt :channels 1)"
  [graph & {:keys [name node-id channels out-dir]
            :or   {name "alembic-patch" node-id :voice channels 2}}]
  (let [{:keys [wasm-path]} (if out-dir
                              (compile! graph :name name :out-dir out-dir)
                              (compile! graph :name name))
        descriptor          (patch-descriptor wasm-path
                                              :node-id  node-id
                                              :channels channels)]
    (session/load-session! descriptor)))

(defn hot-swap!
  "Recompile an alembic graph and replace the kairos-grid patch slot.

  Equivalent to load-patch! — rebuilds the patch slot with the recompiled
  WASM module. There is a brief gap in audio output.

  Gapless hot-swap via k_kairos_ext_hot_swap is planned but not yet
  implemented in kairos-grid.

  Options: same as load-patch!."
  [graph & opts]
  (apply load-patch! graph opts))
