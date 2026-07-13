; SPDX-License-Identifier: EPL-2.0
(ns nous.tuning
  "M18 — live tuning path and maqam preset navigation.

  Tuning is a live performance operation, not a config reload. Writing the
  [:theory :tuning] ctrl-tree path — from the REPL, a surface patch, or a
  cable — retunes all subsequent note events without stopping playback.

  The ctrl-tree at [:theory :tuning] carries the authoritative, serialisable
  descriptor; the heavy MicrotonalScale objects live in a private derived cache
  (memoised .scl parses keyed by the descriptor's :name), never on the tree:

    {:name \"rast\" :file \"rast.scl\" :period_cents 1200.0 :degree_count 7}

  ## Two write paths
  - set-tuning! / load-tuning! (REPL) hold the MicrotonalScale directly and
    install it synchronously, then reflect the descriptor at [:theory :tuning].
  - An external write to [:theory :tuning] (cable, patch) is caught by the
    installed watch, which resolves the scale from the registry (or loads it
    from :file) and installs it. Overlap with the REPL path is idempotent.

  ## Maqam preset navigation
  [:theory :maqam_presets] holds an ordered vector of {:name :file} maps;
  [:theory :maqam_index] is the current position; [:theory :maqam_name] is the
  active maqam name (status-strip echo). The navigation endpoint
  [:theory :maqam_nav] steps the list when written :next or :prev — drivable by
  footswitch, encoder, MIDI CC, or a deflive-loop expression.

  Install the watch from session! via (install-tuning-watch!)."
  (:require [clojure.string :as str]
            [ctrl-tree.core :as ct]
            [ctrl-tree.refs :as refs]
            [nous.live      :as live]
            [nous.loop      :as loop-ns]
            [nous.scala     :as scala]))

;; ---------------------------------------------------------------------------
;; Scale registry — name → MicrotonalScale
;;
;; This is a derived object cache, NOT a second source of truth. The
;; authoritative tuning is the serialisable descriptor on the ctrl-tree at
;; [:theory :tuning] ({:name :file :period_cents :degree_count}); a
;; MicrotonalScale is a pure memoised parse of its :file. It is deliberately
;; kept off the ctrl-tree because every ctrl-write! is persisted to the SQLite
;; txlog via pr-str, and a MicrotonalScale record does not round-trip through
;; read-string. See doc/design-ctrl-authority.md (object-cache carve-out).
;; ---------------------------------------------------------------------------

(defonce ^:private tuning-registry (atom {}))

(defn register-scale!
  "Register a MicrotonalScale under `name` (string) for later activation.
  Returns `name`."
  [name scale]
  (swap! tuning-registry assoc name scale)
  name)

(defn registered-scale
  "Return the MicrotonalScale registered under `name`, or nil."
  [name]
  (get @tuning-registry name))

(defn- resolve-scale
  "Return a MicrotonalScale for descriptor `d`, loading from :file when it is
  not already in the registry. Returns nil when neither source is available."
  [{:keys [name file]}]
  (or (get @tuning-registry name)
      (when (and file (not (str/blank? file)))
        (let [scale (scala/load-scl file)]
          (swap! tuning-registry assoc name scale)
          scale))))

;; ---------------------------------------------------------------------------
;; Tuning descriptor + activation
;; ---------------------------------------------------------------------------

(defn- descriptor
  "Serialisable ctrl-tree payload for `scale` under `name` (and optional file)."
  [name file scale]
  {:name         name
   :file         (or file "")
   :period_cents (double (:period-cents scale))
   :degree_count (long (count (:degrees scale)))})

(defn- activate!
  "Install `scale` as the live tuning, or nil to return to 12-TET. Synchronous;
  wraps nous.live/use-tuning!.

  Skips the alter-var-root when *tuning-ctx* already holds the target, so the
  redundant re-activation triggered by set-tuning!'s own [:theory :tuning] write
  (via the install watch) is a no-op rather than a second mutation."
  [scale]
  (let [target (when scale {:scale scale})]
    (when (not= target loop-ns/*tuning-ctx*)
      (live/use-tuning! target)))
  nil)

;; ---------------------------------------------------------------------------
;; Public tuning API
;; ---------------------------------------------------------------------------

(defn set-tuning!
  "Activate MicrotonalScale `scale` as the live tuning under `name` and reflect
  it at [:theory :tuning]. Installs synchronously; the watch re-installs
  idempotently on the resulting path write.

  Example:
    (set-tuning! \"rast\" (scala/load-scl \"rast.scl\"))"
  [name scale & {:keys [file]}]
  (register-scale! name scale)
  (activate! scale)
  (ct/ctrl-write! [:theory :tuning] (descriptor name file scale))
  nil)

(defn load-tuning!
  "Load a Scala (.scl) file and activate it as the live tuning under `name`."
  [name scl-path]
  (set-tuning! name (scala/load-scl scl-path) :file scl-path))

(defn clear-tuning!
  "Return to 12-TET: disable retuning and clear [:theory :tuning]."
  []
  (activate! nil)
  (ct/ctrl-write! [:theory :tuning] nil)
  nil)

(defn current-tuning
  "Return the active tuning descriptor from the ctrl-tree, or nil (12-TET)."
  []
  (ct/ctrl-read [:theory :tuning]))

;; ---------------------------------------------------------------------------
;; Maqam preset navigation
;; ---------------------------------------------------------------------------

(defn set-maqam-presets!
  "Install an ordered list of maqam presets for navigation.

  Each preset is a map with :name and either :scale (a MicrotonalScale) or
  :file (an .scl path to load). Registers every scale, writes the serialisable
  preset list to [:theory :maqam_presets], and resets [:theory :maqam_index]
  to 0 without selecting a tuning."
  [presets]
  (let [presets (vec presets)]
    (doseq [{:keys [name scale file]} presets]
      (cond
        scale (register-scale! name scale)
        file  (register-scale! name (scala/load-scl file))))
    (ct/ctrl-write! [:theory :maqam_presets]
                    (mapv (fn [{:keys [name file]}]
                            {:name name :file (or file "")})
                          presets))
    (ct/ctrl-write! [:theory :maqam_index] 0))
  nil)

(defn maqam-presets
  "Return the installed maqam preset list, or nil."
  []
  (ct/ctrl-read [:theory :maqam_presets]))

(defn maqam-index
  "Return the current maqam preset index (0 when unset)."
  []
  (or (ct/ctrl-read [:theory :maqam_index]) 0))

(defn maqam-goto!
  "Select the maqam preset at index `i` (wrapping), activate its tuning, and
  echo the name to [:theory :maqam_name] for the status strip. No-op when the
  preset list is empty."
  [i]
  (let [presets (maqam-presets)]
    (when (seq presets)
      (let [n              (count presets)
            idx            (mod (long i) n)
            {:keys [name]} (nth presets idx)
            scale          (registered-scale name)]
        (ct/ctrl-write! [:theory :maqam_index] idx)
        (ct/ctrl-write! [:theory :maqam_name]  name)
        (when scale
          (set-tuning! name scale)))))
  nil)

(defn maqam-next!
  "Advance to the next maqam preset (wrapping)."
  []
  (maqam-goto! (inc (maqam-index))))

(defn maqam-prev!
  "Step to the previous maqam preset (wrapping)."
  []
  (maqam-goto! (dec (maqam-index))))

(defn maqam-nav!
  "Step the maqam preset list. `dir` is :next or :prev. Dispatches directly and
  synchronously — a MIDI CC, footswitch, or deflive-loop handler calls this.

  This does NOT round-trip through a ctrl-tree level-keyword endpoint: a level
  path ([:theory :maqam_nav] = :next/:prev/:idle) silently drops a same-direction
  press issued before its async reset, because ref watches fire only on value
  change (Gate 4 finding #6). Cable/patch-driven navigation, once the cable
  system lands (M20r/M21), will bind through a proper edge/trigger type rather
  than a level path."
  [dir]
  (case dir
    :next (maqam-next!)
    :prev (maqam-prev!)
    nil))

;; ---------------------------------------------------------------------------
;; Ctrl-tree watch — live tuning install + maqam navigation endpoint
;; ---------------------------------------------------------------------------

(defn install-tuning-watch!
  "Install a watch on the ctrl-tree that activates the tuning whenever
  [:theory :tuning] changes — e.g. an external cable/surface-patch write, or
  set-tuning!'s own descriptor write (re-activation is guarded to a no-op).

  Idempotent — fixed watch key :nous.tuning/installer replaces any prior watch.

  Maqam navigation is NOT a watch endpoint: it is driven by calling maqam-nav! /
  maqam-next! / maqam-prev! directly (see maqam-nav! for why a level-keyword
  ctrl-tree endpoint was removed — Gate 4 finding #6)."
  []
  (add-watch refs/tree-state :nous.tuning/installer
    (fn [_ _ old new]
      (let [old-tuning (get old [:theory :tuning])
            new-tuning (get new [:theory :tuning])]
        ;; Tuning activation — run SYNCHRONOUSLY. Ref watches fire after the
        ;; transaction commits, so calling activate! (alter-var-root via
        ;; use-tuning!) here is on the committing/REPL thread, never the agent
        ;; pool. use-tuning! is documented single-thread-only; wrapping this in a
        ;; future would violate that contract (Gate 4 finding #3).
        (when (not= old-tuning new-tuning)
          (if (nil? new-tuning)
            (activate! nil)
            (when-let [scale (resolve-scale new-tuning)]
              (activate! scale))))))))

(defn remove-tuning-watch!
  "Remove the tuning/maqam-navigation watch."
  []
  (remove-watch refs/tree-state :nous.tuning/installer))
