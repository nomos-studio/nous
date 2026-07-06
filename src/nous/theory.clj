; SPDX-License-Identifier: EPL-2.0
(ns nous.theory
  "Pure pitch/mode/scale/chord functions — M12 thin theory layer.

  All functions are pure data transformers. No side-effects, no deps on
  the session or ctrl-tree. Pitch classes are integers 0–11 (C=0, B=11).

  ## Key representation
  Keys are strings: \"C\" \"G\" \"F\" \"Bb\" \"F#\" etc.
  The canonical note table uses sharps for black keys (C#=1, D#=3, …).
  Flat inputs are normalised via the enharmonic map.

  ## Mode representation
  Modes are keywords or strings (coerced internally to keywords):
    :major / :ionian   :dorian  :phrygian  :lydian  :mixolydian
    :aeolian / :minor  :locrian :harmonic-minor :melodic-minor
    :pentatonic-major  :pentatonic-minor  :blues  :whole-tone
    :diminished (half-whole)  :chromatic

  ## Derivation hook
  Call (install-theory-watch!) from session! to auto-derive
  [:theory :scale-pcs] and [:theory :scale-notes] whenever
  [:theory :key] or [:theory :mode] changes in the ctrl-tree."
  (:require [ctrl-tree.core :as ct]
            [ctrl-tree.refs :as refs]))

;; ---------------------------------------------------------------------------
;; Note tables
;; ---------------------------------------------------------------------------

(def ^:private sharp-names
  ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(def ^:private flat-names
  ["C" "Db" "D" "Eb" "E" "F" "Gb" "G" "Ab" "A" "Bb" "B"])

(def ^:private enharmonic
  {"Cb" "B"  "Db" "C#" "Eb" "D#" "Fb" "E"
   "Gb" "F#" "Ab" "G#" "Bb" "A#" "B#" "C"})

;; Keys that conventionally use flats (determines output note spelling).
(def ^:private flat-keys
  #{"F" "Bb" "Eb" "Ab" "Db" "Gb"})

;; ---------------------------------------------------------------------------
;; Mode intervals
;; ---------------------------------------------------------------------------

(def ^:private mode-intervals
  {:ionian           [0 2 4 5 7 9 11]
   :major            [0 2 4 5 7 9 11]
   :dorian           [0 2 3 5 7 9 10]
   :phrygian         [0 1 3 5 7 8 10]
   :lydian           [0 2 4 6 7 9 11]
   :mixolydian       [0 2 4 5 7 9 10]
   :aeolian          [0 2 3 5 7 8 10]
   :minor            [0 2 3 5 7 8 10]
   :locrian          [0 1 3 5 6 8 10]
   :harmonic-minor   [0 2 3 5 7 8 11]
   :melodic-minor    [0 2 3 5 7 9 11]
   :pentatonic-major [0 2 4 7 9]
   :pentatonic-minor [0 3 5 7 10]
   :blues            [0 3 5 6 7 10]
   :whole-tone       [0 2 4 6 8 10]
   :diminished       [0 2 3 5 6 8 9 11]
   :chromatic        [0 1 2 3 4 5 6 7 8 9 10 11]})

;; ---------------------------------------------------------------------------
;; Core conversions
;; ---------------------------------------------------------------------------

(defn note->pc
  "Convert a note name string to a pitch class (0–11).
  Handles sharps (#), flats (b), and enharmonic equivalents.
  Returns nil for unrecognised input."
  [note]
  (let [n   (get enharmonic note note)
        idx (.indexOf sharp-names n)]
    (when (>= idx 0) idx)))

(defn pc->note
  "Convert a pitch class (0–11) to a note name string.
  Uses flats when `flat?` is true (default false → sharps)."
  ([pc] (pc->note pc false))
  ([pc flat?]
   (if flat?
     (nth flat-names (mod pc 12))
     (nth sharp-names (mod pc 12)))))

(defn- mode-kw [mode]
  (if (keyword? mode) mode (keyword mode)))

;; ---------------------------------------------------------------------------
;; Scale functions
;; ---------------------------------------------------------------------------

(defn scale-pcs
  "Return a vector of pitch classes for the given key and mode.
  key  — note name string, e.g. \"G\", \"Bb\", \"F#\"
  mode — keyword or string, e.g. :major, \"minor\""
  [key mode]
  (let [root      (note->pc key)
        intervals (get mode-intervals (mode-kw mode))]
    (when (and root intervals)
      (mapv #(mod (+ root %) 12) intervals))))

(defn scale-notes
  "Return a vector of note name strings for the given key and mode.
  Spelling uses flats for flat keys (F, Bb, Eb, Ab, Db, Gb)."
  [key mode]
  (let [pcs  (scale-pcs key mode)
        flat? (contains? flat-keys key)]
    (when pcs
      (mapv #(pc->note % flat?) pcs))))

(defn in-scale?
  "Return true if pitch class `pc` is in the scale for `key`/`mode`."
  [pc key mode]
  (boolean (some #{(mod pc 12)} (scale-pcs key mode))))

(defn nearest-in-scale
  "Return the nearest pitch class in `key`/`mode` to `pc`.
  Ties prefer the lower pitch class."
  [pc key mode]
  (let [target (mod pc 12)
        dist   (fn [x] (let [d (Math/abs (- target x))] (min d (- 12 d))))
        pcs    (scale-pcs key mode)]
    (when (seq pcs)
      (reduce (fn [best candidate]
                (let [db (dist best) dc (dist candidate)]
                  (cond
                    (< dc db) candidate
                    (= dc db) (min best candidate)
                    :else     best)))
              pcs))))

;; ---------------------------------------------------------------------------
;; Chord functions
;; ---------------------------------------------------------------------------

(defn chord-pcs
  "Return the pitch classes of the diatonic triad at scale degree `degree`
  (1-indexed) in `key`/`mode`. Returns nil for modes with fewer than 7 degrees."
  [key mode degree]
  (let [pcs (scale-pcs key mode)]
    (when (and pcs (>= (count pcs) 7) (<= 1 degree 7))
      (let [i (dec degree)]
        [(nth pcs i)
         (nth pcs (mod (+ i 2) 7))
         (nth pcs (mod (+ i 4) 7))]))))

(defn chord-notes
  "Return the note names of the diatonic triad at scale degree `degree`
  (1-indexed) in `key`/`mode`."
  [key mode degree]
  (let [flat? (contains? flat-keys key)]
    (some->> (chord-pcs key mode degree)
             (mapv #(pc->note % flat?)))))

;; ---------------------------------------------------------------------------
;; MIDI quantization
;; ---------------------------------------------------------------------------

(defn quantize-to-scale
  "Return the nearest in-scale MIDI note number to `midi-note`.
  Preserves octave register; ties prefer the lower note.
  Returns nil if key or mode is unrecognised."
  [midi-note key mode]
  (when-let [nearest-pc (nearest-in-scale midi-note key mode)]
    (let [octave-base (- (long midi-note) (mod (long midi-note) 12))
          same        (+ octave-base nearest-pc)
          below       (- same 12)
          above       (+ same 12)
          dist        #(Math/abs (- (long midi-note) (long %)))]
      (-> (reduce (fn [best c]
                    (let [db (dist best) dc (dist c)]
                      (cond (< dc db) c
                            (= dc db) (min best c)
                            :else     best)))
                  [below same above])
          (max 0)
          (min 127)))))

(defn constrain-event
  "Constrain the :pitch/midi of `event` to the nearest in-scale MIDI note.
  Returns the event unchanged when :pitch/midi is absent or key/mode are nil."
  [event key mode]
  (if-let [midi (:pitch/midi event)]
    (if-let [q (quantize-to-scale midi key mode)]
      (assoc event :pitch/midi q)
      event)
    event))

;; ---------------------------------------------------------------------------
;; Ctrl-tree read helpers
;; ---------------------------------------------------------------------------

(defn current-key
  "Return the current theory key from the ctrl-tree, or nil."
  []
  (get @refs/tree-state [:theory :key]))

(defn current-mode
  "Return the current theory mode from the ctrl-tree, or nil."
  []
  (get @refs/tree-state [:theory :mode]))

;; ---------------------------------------------------------------------------
;; Ctrl-tree derivation hook
;; ---------------------------------------------------------------------------

(defn install-theory-watch!
  "Install a watch on the ctrl-tree that auto-derives
  [:theory :scale-pcs] and [:theory :scale-notes] whenever
  [:theory :key] or [:theory :mode] changes.

  Safe to call multiple times — uses a fixed watch key (:nous.theory/deriver)
  so reinstallation replaces the prior watch."
  []
  (add-watch refs/tree-state :nous.theory/deriver
    (fn [_ _ old new]
      (let [old-key  (get old [:theory :key])
            new-key  (get new [:theory :key])
            old-mode (get old [:theory :mode])
            new-mode (get new [:theory :mode])
            key      new-key
            mode     new-mode]
        (when (and key mode
                   (or (not= old-key new-key)
                       (not= old-mode new-mode)))
          ;; Run in a future — watches run on the STM agent thread;
          ;; ct/ctrl-write! uses dosync, which is safe but we avoid blocking.
          (future
            (when-let [pcs (scale-pcs key mode)]
              (ct/ctrl-write! [:theory :scale-pcs]   pcs)
              (ct/ctrl-write! [:theory :scale-notes]  (scale-notes key mode)))))))))

(defn remove-theory-watch!
  "Remove the theory derivation watch."
  []
  (remove-watch refs/tree-state :nous.theory/deriver))
