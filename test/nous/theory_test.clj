; SPDX-License-Identifier: EPL-2.0
(ns nous.theory-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.theory  :as theory]
            [ctrl-tree.core :as ct]
            [ctrl-tree.refs :as refs]))

;; ---------------------------------------------------------------------------
;; note->pc
;; ---------------------------------------------------------------------------

(deftest note->pc-naturals
  (is (= 0  (theory/note->pc "C")))
  (is (= 2  (theory/note->pc "D")))
  (is (= 4  (theory/note->pc "E")))
  (is (= 5  (theory/note->pc "F")))
  (is (= 7  (theory/note->pc "G")))
  (is (= 9  (theory/note->pc "A")))
  (is (= 11 (theory/note->pc "B"))))

(deftest note->pc-sharps
  (is (= 1  (theory/note->pc "C#")))
  (is (= 3  (theory/note->pc "D#")))
  (is (= 6  (theory/note->pc "F#")))
  (is (= 8  (theory/note->pc "G#")))
  (is (= 10 (theory/note->pc "A#"))))

(deftest note->pc-flats-enharmonic
  (is (= 1  (theory/note->pc "Db")))
  (is (= 3  (theory/note->pc "Eb")))
  (is (= 6  (theory/note->pc "Gb")))
  (is (= 8  (theory/note->pc "Ab")))
  (is (= 10 (theory/note->pc "Bb"))))

(deftest note->pc-corner-cases
  (is (= 11  (theory/note->pc "Cb")))   ;; Cb → B
  (is (= 4   (theory/note->pc "Fb")))   ;; Fb → E
  (is (= 0   (theory/note->pc "B#")))   ;; B# → C
  (is (nil?  (theory/note->pc "Q"))))   ;; unrecognised → nil

;; ---------------------------------------------------------------------------
;; pc->note
;; ---------------------------------------------------------------------------

(deftest pc->note-sharps
  (is (= "C"  (theory/pc->note 0)))
  (is (= "C#" (theory/pc->note 1)))
  (is (= "F#" (theory/pc->note 6)))
  (is (= "B"  (theory/pc->note 11))))

(deftest pc->note-flats
  (is (= "Db" (theory/pc->note 1 true)))
  (is (= "Eb" (theory/pc->note 3 true)))
  (is (= "Gb" (theory/pc->note 6 true)))
  (is (= "Bb" (theory/pc->note 10 true))))

(deftest pc->note-wraps
  (is (= "C"  (theory/pc->note 12)))
  (is (= "C#" (theory/pc->note 13))))

;; ---------------------------------------------------------------------------
;; scale-pcs
;; ---------------------------------------------------------------------------

(deftest scale-pcs-c-major
  (is (= [0 2 4 5 7 9 11] (theory/scale-pcs "C" :major))))

(deftest scale-pcs-g-major
  (is (= [7 9 11 0 2 4 6] (theory/scale-pcs "G" :major))))

(deftest scale-pcs-f-major
  (is (= [5 7 9 10 0 2 4] (theory/scale-pcs "F" :major))))

(deftest scale-pcs-a-minor
  (is (= [9 11 0 2 4 5 7] (theory/scale-pcs "A" :minor))))

(deftest scale-pcs-d-dorian
  (is (= [2 4 5 7 9 11 0] (theory/scale-pcs "D" :dorian))))

(deftest scale-pcs-mode-as-string
  (is (= (theory/scale-pcs "C" :major) (theory/scale-pcs "C" "major"))))

(deftest scale-pcs-pentatonic
  (is (= [0 2 4 7 9] (theory/scale-pcs "C" :pentatonic-major))))

(deftest scale-pcs-bb-minor
  (let [pcs (theory/scale-pcs "Bb" :minor)]
    (is (= 7 (count pcs)))
    ;; Bb=10, C=0, Db=1, Eb=3, F=5, Gb=6, Ab=8
    (is (= [10 0 1 3 5 6 8] pcs))))

(deftest scale-pcs-returns-nil-for-unknown-key
  (is (nil? (theory/scale-pcs "Q" :major))))

(deftest scale-pcs-returns-nil-for-unknown-mode
  (is (nil? (theory/scale-pcs "C" :nonexistent))))

;; ---------------------------------------------------------------------------
;; scale-notes
;; ---------------------------------------------------------------------------

(deftest scale-notes-g-major-uses-sharps
  (is (= ["G" "A" "B" "C" "D" "E" "F#"] (theory/scale-notes "G" :major))))

(deftest scale-notes-f-major-uses-flats
  (is (= ["F" "G" "A" "Bb" "C" "D" "E"] (theory/scale-notes "F" :major))))

(deftest scale-notes-bb-major-uses-flats
  (is (= ["Bb" "C" "D" "Eb" "F" "G" "A"] (theory/scale-notes "Bb" :major))))

(deftest scale-notes-c-major-no-accidentals
  (is (= ["C" "D" "E" "F" "G" "A" "B"] (theory/scale-notes "C" :major))))

;; ---------------------------------------------------------------------------
;; in-scale?
;; ---------------------------------------------------------------------------

(deftest in-scale-c-major
  (is (true?  (theory/in-scale? 0  "C" :major)))  ;; C
  (is (true?  (theory/in-scale? 2  "C" :major)))  ;; D
  (is (false? (theory/in-scale? 1  "C" :major)))  ;; C#
  (is (false? (theory/in-scale? 6  "C" :major)))) ;; F#

(deftest in-scale-wraps-octave
  (is (true? (theory/in-scale? 12 "C" :major)))   ;; C above
  (is (true? (theory/in-scale? 14 "C" :major))))  ;; D above

;; ---------------------------------------------------------------------------
;; nearest-in-scale
;; ---------------------------------------------------------------------------

(deftest nearest-in-scale-already-in
  (is (= 0 (theory/nearest-in-scale 0 "C" :major))))

(deftest nearest-in-scale-c-sharp-snaps-to-d
  ;; C# (1) — nearest in C major: C (0) at distance 1, D (2) at distance 1
  ;; min-key last-wins on ties → D
  (is (= 2 (theory/nearest-in-scale 1 "C" :major))))

(deftest nearest-in-scale-a-sharp-snaps-to-b
  ;; A# (10) — nearest in C major: A (9) at d=1, B (11) at d=1
  ;; min-key last-wins on ties → B
  (is (= 11 (theory/nearest-in-scale 10 "C" :major))))

;; ---------------------------------------------------------------------------
;; chord-pcs
;; ---------------------------------------------------------------------------

(deftest chord-pcs-c-major-I
  (is (= [0 4 7] (theory/chord-pcs "C" :major 1))))  ;; C E G

(deftest chord-pcs-c-major-IV
  (is (= [5 9 0] (theory/chord-pcs "C" :major 4))))  ;; F A C

(deftest chord-pcs-c-major-V
  (is (= [7 11 2] (theory/chord-pcs "C" :major 5)))) ;; G B D

(deftest chord-pcs-c-major-VII
  (is (= [11 2 5] (theory/chord-pcs "C" :major 7)))) ;; B D F

(deftest chord-pcs-pentatonic-returns-nil
  (is (nil? (theory/chord-pcs "C" :pentatonic-major 1))))

(deftest chord-pcs-out-of-range
  (is (nil? (theory/chord-pcs "C" :major 0)))
  (is (nil? (theory/chord-pcs "C" :major 8))))

;; ---------------------------------------------------------------------------
;; chord-notes
;; ---------------------------------------------------------------------------

(deftest chord-notes-g-major-I-uses-sharps
  (is (= ["G" "B" "D"] (theory/chord-notes "G" :major 1))))

(deftest chord-notes-f-major-IV-uses-flats
  ;; IV in F major: Bb D F
  (is (= ["Bb" "D" "F"] (theory/chord-notes "F" :major 4))))

;; ---------------------------------------------------------------------------
;; Ctrl-tree derivation watch
;; ---------------------------------------------------------------------------

(defn- clear-theory-state! []
  (dosync
    (alter refs/tree-state
           dissoc
           [:theory :key] [:theory :mode]
           [:theory :scale-pcs] [:theory :scale-notes]))
  (theory/remove-theory-watch!))

(use-fixtures :each
  (fn [f]
    (clear-theory-state!)
    (try (f) (finally (clear-theory-state!)))))

(deftest install-and-fire-watch
  (theory/install-theory-watch!)
  (ct/ctrl-write! [:theory :key]  "G")
  (ct/ctrl-write! [:theory :mode] "major")
  ;; Give the future a moment to deliver the derived writes
  (Thread/sleep 100)
  (let [pcs   (get @refs/tree-state [:theory :scale-pcs])
        notes (get @refs/tree-state [:theory :scale-notes])]
    (is (= [7 9 11 0 2 4 6] pcs))
    (is (= ["G" "A" "B" "C" "D" "E" "F#"] notes))))

(deftest watch-fires-on-key-change
  (theory/install-theory-watch!)
  (ct/ctrl-write! [:theory :key]  "C")
  (ct/ctrl-write! [:theory :mode] "major")
  (Thread/sleep 100)
  (ct/ctrl-write! [:theory :key] "D")
  (Thread/sleep 100)
  (let [pcs (get @refs/tree-state [:theory :scale-pcs])]
    (is (= [2 4 6 7 9 11 1] pcs))))   ;; D major

(deftest watch-does-not-fire-without-mode
  (theory/install-theory-watch!)
  (ct/ctrl-write! [:theory :key] "G")
  (Thread/sleep 100)
  (is (nil? (get @refs/tree-state [:theory :scale-pcs]))))

(deftest install-is-idempotent
  (theory/install-theory-watch!)
  (theory/install-theory-watch!)
  (ct/ctrl-write! [:theory :key]  "C")
  (ct/ctrl-write! [:theory :mode] "major")
  (Thread/sleep 100)
  (is (= [0 2 4 5 7 9 11] (get @refs/tree-state [:theory :scale-pcs]))))
