; SPDX-License-Identifier: EPL-2.0
(defproject nous "0.16.0"
  :description "nous — music-theory-aware Clojure sequencer targeting MIDI and OSC"
  :url "https://github.com/nomos-studio/nous"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [nomos-maths "0.1.0"]
                 [nomos-topology "0.1.0"]
                 [org.clojure/data.json "2.5.1"]
                 [http-kit "2.8.0"]
                 [org.xerial/sqlite-jdbc "3.47.1.0"]]
  :main ^:skip-aot nous.core
  :source-paths ["src"]
  :test-paths   ["test"]
  :resource-paths ["resources"]
  :target-path "target/%s"
  :repl-options {:timeout 120000}
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies []}}
  :aliases {"mcp" ["run" "-m" "nous.mcp"]}
  :plugins [[lein-codox "0.10.8"]]
  :codox {:output-path "target/codox"
          :namespaces [nous.clock
                       nous.ctrl
                       nous.device
                       nous.dsl
                       nous.flux
                       nous.fractal
                       nous.loop
                       nous.m21
                       nous.stochastic
                       nous.mod
                       nous.mod.graph
                       nous.morph
                       nomos.maths.phasor
                       nous.random
                       nous.sidecar
                       nous.timing]
          :source-uri "https://github.com/nomos-studio/nous/blob/{version}/{filepath}#L{line}"})
