; SPDX-License-Identifier: EPL-2.0
(ns nous.ipc-mount
  "IpcMount — a ctrl-tree IMount that projects control writes to nomos-rt over
  the IPC channel (the KairosIpcMount / DeviceMapMount the ctrl-tree substrate
  anticipates).

  On a ctrl-tree write to a mounted path, mount-write! resolves the path's
  hardware bindings and emits the corresponding nomos-rt frames via
  nous.dispatch (scale + kairos EDN frame → nomos-rt does the MIDI wire bytes).
  See doc/design-ctrl-authority.md and the project note on ctrl-tree output via
  nomos-rt IPC.

  ## Transitional hybrid (during the nous.ctrl → ctrl-tree migration)
  The *value* lives on the ctrl-tree (this write triggered the mount), but the
  *binding* is still read from the legacy nous.ctrl node's :bindings — exactly
  as nous.server reads both stores. This lets dispatch move onto ctrl-tree
  before the binding/device-map model migrates (later increments will move
  bindings to a Clojure-side registry this mount consults directly).

  Mounts fire post-commit (never inside the STM transaction), so the blocking
  IPC send is safe here."
  (:require [protomatter.protocols :as p]
            [nous.ctrl     :as ctrl]
            [nous.dispatch :as dispatch]
            [nous.kairos   :as kairos]))

(deftype IpcMount []
  p/IMount
  (mount-write! [_ path value]
    ;; Resolve the path's bindings from the legacy store and emit a frame per
    ;; binding, when the transport is connected. Swallow errors — a mount must
    ;; not break the committing write.
    (try
      (when (kairos/connected?)
        (doseq [binding (:bindings (ctrl/node-info path))]
          (dispatch/dispatch-binding! binding value)))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "[nous.ipc-mount] dispatch error at " (pr-str path)
                        ": " (.getMessage e)))))))
  (mount-recable! [_ _changes]
    nil))

(defn ipc-mount
  "Construct an IpcMount. Register it in ctrl-tree.refs/mount-table under a path
  prefix so writes below that prefix dispatch to nomos-rt."
  []
  (IpcMount.))
