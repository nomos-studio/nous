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

  ## Binding source
  The *value* lives on the ctrl-tree (this write triggered the mount). Output
  bindings are read from nous.binding-registry — the single source for output
  dispatch; every declarer (device, schema, session, berlin) registers there.
  (Controller-*input* bindings, :midi-device-input, live on nous.ctrl and are
  consumed by nous.midi-in, not by this output mount.)

  Mounts fire post-commit (never inside the STM transaction), so the blocking
  IPC send is safe here."
  (:require [protomatter.protocols :as p]
            [ctrl-tree.refs :as refs]
            [nous.binding-registry :as breg]
            [nous.dispatch :as dispatch]
            [nous.kairos   :as kairos]))

(deftype IpcMount []
  p/IMount
  (mount-write! [_ path value]
    ;; Resolve the path's bindings from the binding registry and emit a frame
    ;; per binding, when the transport is connected. Swallow errors — a mount
    ;; must not break the committing write.
    (try
      (when (kairos/connected?)
        (doseq [binding (breg/bindings-for path)]
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

(defn install!
  "Register an IpcMount at the root prefix [] so any bound ctrl-tree path
  dispatches to nomos-rt. The root prefix matches every path and sorts last in
  the longest-prefix resolution, so it is the fallback for writes not caught by a
  more-specific mount (e.g. the BeamMounts on [:keyboard], [:seq], …). Idempotent.
  The mount itself gates on kairos/connected?, so it is inert until nomos-rt is up."
  []
  (dosync (alter refs/mount-table assoc [] (ipc-mount)))
  nil)

(defn uninstall!
  "Remove the root IpcMount installed by install!."
  []
  (dosync (alter refs/mount-table dissoc []))
  nil)
