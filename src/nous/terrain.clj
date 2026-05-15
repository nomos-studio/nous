; SPDX-License-Identifier: EPL-2.0
(ns cljseq.terrain
  "cljseq terrain sequencer — 3D fractal sequence space.

  Lifts the cljseq.fractal 2D chain into 3D by giving each node N children
  (one per transform). The space is addressed by three continuous phasors:

    X ∈ [0,1) — position within the sequence (step index = floor(X × trunk-len))
    Y ∈ [0,1) — depth into the transform tree (0 = trunk, 1 = max-depth)
    Z ∈ [0,1) — branch selection, decoded as a base-N fraction

  Adjacent branches are linearly blended so that moving Z continuously
  produces smooth morphs rather than hard cuts. Y and Z are registered as
  ctrl-tree float nodes so they can be driven by trajectory/bind!, ctrl/set!,
  or any external modulator.

  Stepping through a terrain is semantically identical to stepping through a
  fractal sequence: each call to next-step! returns the step map at the current
  X position and advances X by 1/N. The difference is that the content at each
  position is continuously parameterised by Y and Z.

  ## Quick start

    (defterrain seabed
      :trunk  [{:pitch/midi 36 :dur/beats 1/2 :gate/on? true :gate/len 0.7}
               {:pitch/midi 40 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
               {:pitch/midi 43 :dur/beats 1/4 :gate/on? true :gate/len 0.5}]
      :transforms [:reverse :inverse :mutate]
      :max-depth  4)

    ;; Manual step — reads Y/Z from ctrl tree (both default 0.0)
    (next-terrain-step! seabed)

    ;; Drift into the terrain over time
    (ctrl/set! [:terrain :seabed :y] 0.6)
    (ctrl/set! [:terrain :seabed :z] 0.35)

    ;; As IStepSequencer inside a live loop
    (deflive-loop :depth {}
      (run-step! (make-terrain-seq seabed)))

  Attribution: the terrain sequencer concept is original to cljseq.
  See doc/design/design-seed-terrain.md."
  (:require [cljseq.ctrl    :as ctrl]
            [cljseq.fractal :as frac]
            [cljseq.phasor  :as phasor]
            [cljseq.seq     :as sq]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn ^:no-doc register!
  "Register a terrain context atom. Used by defterrain."
  [k ctx-atom]
  (swap! registry assoc k ctx-atom))

;; ---------------------------------------------------------------------------
;; Path encoding — Z ∈ [0,1) as base-N fraction
;; ---------------------------------------------------------------------------

(defn z->path
  "Decode z ∈ [0, 1) as a base-n fraction into a transform-index path of `depth` entries.

  Each digit in the base-N expansion selects a child (transform index) at
  that level of the tree. With N transforms and depth D, there are N^D
  distinct branch paths addressable within Z.

  Example:
    (z->path 0.5 2 3)  ; => [1 0 0]  — binary: 0.5 = 0.100 → first digit 1
    (z->path 0.0 3 3)  ; => [0 0 0]
    (z->path 0.0 3 0)  ; => []"
  [z n depth]
  (loop [rem (double z) path [] i 0]
    (if (= i depth)
      path
      (let [scaled (* rem n)
            idx    (min (dec n) (int (Math/floor scaled)))]
        (recur (- scaled idx) (conj path idx) (inc i))))))

(defn- z-blend-t
  "Return t ∈ [0,1) — the fractional remainder after extracting `depth` base-N digits from z.
  This is the blend weight between path-lo and path-hi at the current depth."
  [z n depth]
  (loop [rem (double z) i 0]
    (if (= i depth)
      rem
      (let [scaled (* rem n)]
        (recur (- scaled (int (Math/floor scaled))) (inc i))))))

;; ---------------------------------------------------------------------------
;; Sequence generation — lazy O(depth) path application
;; ---------------------------------------------------------------------------

(defn terrain-seq
  "Compute the sequence at terrain address (y, z).

  y ∈ [0,1) → depth: 0 = trunk returned unchanged; depths 1..max-depth applied as Y→1.
  z ∈ [0,1) → branch path (base-N fraction over transforms).
  opts       — passed through to apply-transform (supports :scale :root etc.)."
  [trunk transforms max-depth y z opts]
  (let [n     (max 1 (count transforms))
        depth (if (empty? transforms)
                0
                (long (Math/floor (* (double y) (inc (int max-depth))))))]
    (reduce (fn [steps idx]
              (frac/apply-transform steps (nth transforms idx) opts))
            (vec trunk)
            (z->path z n depth))))

;; ---------------------------------------------------------------------------
;; Step-map interpolation
;; ---------------------------------------------------------------------------

(defn- lerp-step
  "Linearly interpolate between two step maps at blend parameter t ∈ [0,1).

  Blends :pitch/midi (rounded int), :dur/beats (float), :gate/len (float).
  :gate/on? and all other keys are taken from the majority step (a when t<0.5,
  b when t≥0.5)."
  [a b t]
  (let [t  (double t)
        t' (- 1.0 t)
        base (if (< t 0.5) a b)]
    (cond-> base
      (and (:pitch/midi a) (:pitch/midi b))
      (assoc :pitch/midi (long (Math/round ^double (+ (* t' (double (:pitch/midi a)))
                                                       (* t (double (:pitch/midi b)))))))
      (and (:dur/beats a) (:dur/beats b))
      (assoc :dur/beats (+ (* t' (double (:dur/beats a)))
                            (* t (double (:dur/beats b)))))
      (and (contains? a :gate/len) (contains? b :gate/len))
      (assoc :gate/len (+ (* t' (double (:gate/len a 0.5)))
                           (* t (double (:gate/len b 0.5))))))))

(defn- blend-steps
  "Address step by x-phase in seq-lo, blending with seq-hi by t."
  [seq-lo seq-hi x t]
  (let [N  (max 1 (count seq-lo))
        i  (min (dec N) (long (Math/floor (* (double x) N))))
        lo (nth seq-lo i)
        hi (nth seq-hi (min (dec (max 1 (count seq-hi))) i))]
    (lerp-step lo hi t)))

;; ---------------------------------------------------------------------------
;; Full terrain address lookup
;; ---------------------------------------------------------------------------

(defn terrain-step
  "Compute the blended step map at terrain address (x, y, z).

  x ∈ [0,1) — position within sequence (step index = floor(x × N))
  y ∈ [0,1) — depth (0 = trunk, increases toward max-depth as Y→1)
  z ∈ [0,1) — branch blend (decoded as base-N fraction)
  opts       — transform options (:scale :root :mutate-prob etc.)

  Adjacent branches (path-lo and path-hi, differing in the last digit by 1)
  are computed and blended by the fractional remainder of the base-N decode."
  [trunk transforms max-depth x y z opts]
  (let [n      (max 1 (count transforms))
        depth  (if (empty? transforms)
                 0
                 (long (Math/floor (* (double y) (inc (int max-depth))))))
        path   (z->path z n depth)
        t      (z-blend-t z n depth)
        path-hi (if (empty? path)
                  path
                  (let [li (dec (count path))]
                    (assoc path li (mod (inc (nth path li)) n))))
        ->seq  (fn [p]
                 (reduce (fn [steps idx]
                           (frac/apply-transform steps (nth transforms idx) opts))
                         (vec trunk)
                         p))
        seq-lo (->seq path)
        seq-hi (if (= path path-hi) seq-lo (->seq path-hi))]
    (blend-steps seq-lo seq-hi x t)))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(defn make-terrain-context
  "Build a terrain context map from a name keyword and raw options."
  [k opts]
  (let [{:keys [trunk transforms max-depth]
         :or   {trunk [] transforms [] max-depth 4}} opts]
    {:trunk       (vec trunk)
     :transforms  (vec transforms)
     :max-depth   (int max-depth)
     :x           0.0
     :y           0.0           ;; depth phasor — authoritative value
     :z           0.0           ;; branch phasor — authoritative value
     :y-ctrl-path [:terrain k :y]
     :z-ctrl-path [:terrain k :z]
     :opts        opts
     :last-step   nil}))

;; ---------------------------------------------------------------------------
;; defterrain macro
;; ---------------------------------------------------------------------------

(defmacro defterrain
  "Define a named terrain sequencer context and register ctrl-tree nodes for Y and Z.

  Parameters:
    :trunk        — vector of step maps (base sequence)
    :transforms   — vector of transform keywords applied at each tree level:
                    :reverse :inverse :transpose :mutate :randomize
    :max-depth    — integer maximum transform depth (default 4)
    :scale        — weighted-scale map (for :mutate/:inverse/:randomize)
    :root         — root MIDI note (default 60)

  Ctrl-tree nodes registered:
    [:terrain <name> :y]  — depth phasor, float 0.0–1.0
    [:terrain <name> :z]  — branch phasor, float 0.0–1.0

  Ctrl-tree watches keep the context atom in sync so that trajectory/bind!
  and ctrl/set! drive the terrain in real time without polling.

  Example:
    (defterrain seabed
      :trunk  [{:pitch/midi 36 :dur/beats 1/2 :gate/on? true :gate/len 0.7}
               {:pitch/midi 40 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
               {:pitch/midi 43 :dur/beats 1/4 :gate/on? true :gate/len 0.5}]
      :transforms [:reverse :inverse :mutate]
      :max-depth  4)"
  [gen-name & opts]
  (let [opts-map (apply hash-map opts)
        k        (keyword (name gen-name))]
    `(do
       (def ~gen-name (atom (make-terrain-context ~k ~opts-map)))
       (register! ~k ~gen-name)
       (ctrl/defnode! [:terrain ~k :y] :type :float :value 0.0)
       (ctrl/defnode! [:terrain ~k :z] :type :float :value 0.0)
       (ctrl/watch! [:terrain ~k :y]
                    (fn [_p# _o# v#]
                      (swap! ~gen-name assoc :y (double (or v# 0.0)))))
       (ctrl/watch! [:terrain ~k :z]
                    (fn [_p# _o# v#]
                      (swap! ~gen-name assoc :z (double (or v# 0.0)))))
       ~gen-name)))

;; ---------------------------------------------------------------------------
;; Step navigation
;; ---------------------------------------------------------------------------

(defn next-terrain-step!
  "Advance the terrain context and return the next step map.

  Y and Z are read from the context atom — they are kept in sync with the
  ctrl-tree nodes by watches installed by defterrain. X advances by 1/N per
  call (N = trunk length). One full X cycle traverses all trunk positions once."
  [ctx-atom]
  (:last-step
   (swap! ctx-atom
          (fn [{:keys [trunk transforms max-depth x y z opts] :as state}]
            (let [n    (max 1 (count trunk))
                  y    (double (or y 0.0))
                  z    (double (or z 0.0))
                  step (terrain-step trunk transforms max-depth x y z opts)
                  x'   (phasor/wrap (+ x (/ 1.0 n)))]
              (assoc state
                     :last-step step
                     :x        x'))))))

;; ---------------------------------------------------------------------------
;; TerrainSeq — IStepSequencer wrapper
;; ---------------------------------------------------------------------------

(defrecord TerrainSeq [ctx-atom vel])

(defn make-terrain-seq
  "Wrap a terrain context atom as an IStepSequencer.

  Options:
    :vel — default velocity 0–127 (default 100)

  Returns a TerrainSeq. Use with run-step! from cljseq.seq.
  seq-cycle-length returns nil (infinite, continuously varying)."
  [ctx-atom & {:keys [vel] :or {vel 100}}]
  (->TerrainSeq ctx-atom (long vel)))

(extend-protocol sq/IStepSequencer
  TerrainSeq
  (next-event [ts]
    (let [step  (next-terrain-step! (:ctx-atom ts))
          beats (double (:dur/beats step 1/4))]
      (if (or (nil? step) (not (:gate/on? step true)))
        {:event nil :beats beats}
        {:event (assoc step :mod/velocity (:vel ts)) :beats beats})))
  (seq-cycle-length [_] nil))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn terrain-names
  "Return a seq of registered terrain context names."
  []
  (or (keys @registry) '()))
