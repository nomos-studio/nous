; SPDX-License-Identifier: EPL-2.0
(defproject nous "0.19.0"
  :description "nous — music-theory-aware Clojure sequencer targeting MIDI and OSC"
  :url "https://github.com/nomos-studio/nous"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [nomos-maths "0.2.1"]
                 [nomos-topology "0.1.0"]
                 [alembic "0.1.0"]
                 [org.clojure/data.json "2.5.1"]
                 [http-kit "2.8.0"]
                 [org.xerial/sqlite-jdbc "3.47.1.0"]
                 ;; ctrl-tree substrate + Jinterface IPC
                 [nomos-studio/protomatter "0.1.0-SNAPSHOT"]
                 [nomos-studio/ctrl-tree   "0.1.0-SNAPSHOT"]
                 [org.erlang.otp/jinterface "1.16.0"]]
  ;; lib/maven is a project-local Maven repo holding the jinterface jar.
  ;; Built from OTP 29.0.2 source (lib/jinterface/java_src) — travels with the
  ;; source tree so builds stay hermetic when bundling a JVM for distribution.
  :repositories [["project-jars" {:url "file:lib/maven"}]]
  :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
  :main ^:skip-aot nous.core
  :source-paths ["src"]
  :test-paths   ["test"]
  :resource-paths ["resources"]
  :target-path "target/%s"
  :repl-options {:timeout  120000
                 :init-ns  nous.user
                 :init     (require 'nous.user)}
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {}}
  :aliases {"mcp"        ["run" "-m" "nous.mcp"]
            "lint.reuse" ["shell" "reuse" "lint"]}
  :plugins [[lein-codox "0.10.8"]
            [lein-shell "0.5.0"]]
  :codox {:output-path "target/codox"
          :namespaces [;; Core sequencing
                       nous.loop
                       nous.clock
                       nous.timing
                       nous.dsl
                       nous.live
                       ;; Control and connectivity
                       nous.ctrl
                       nous.device
                       nous.bitwig
                       nous.learn
                       nous.link
                       nous.kairos
                       nous.midi-in
                       nous.osc
                       nous.peer
                       nous.supervisor
                       nous.topology
                       ;; Music theory
                       nous.analysis.counterpoint
                       nous.chord
                       nous.interval
                       nous.pitch
                       nous.rhythm
                       nous.scale
                       ;; Microtonal and JI
                       nous.lattice
                       nous.excursion
                       nous.defensemble
                       nous.mts
                       nous.scala
                       ;; Generative
                       nous.book
                       nous.berlin
                       nous.conductor
                       nous.extractor
                       nous.flux
                       nous.fractal
                       nous.journey
                       nous.pattern
                       nous.seq
                       nous.stochastic
                       nous.temporal-buffer
                       nous.terrain
                       nous.trajectory
                       ;; Corpus
                       nous.freesound
                       nous.m21
                       ;; Synthesis and samples
                       nous.alembic
                       nous.fm
                       nous.sample
                       nous.sc
                       nous.spectral
                       ;; Utilities
                       nous.arp
                       nous.ivk
                       nous.journal
                       nous.mod
                       nous.mod.graph
                       nous.morph
                       nous.random
                       nomos.maths.phasor]
          :source-uri "https://github.com/nomos-studio/nous/blob/{version}/{filepath}#L{line}"})
