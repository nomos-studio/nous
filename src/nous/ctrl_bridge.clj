; SPDX-License-Identifier: EPL-2.0
(ns nous.ctrl-bridge
  "Store-agnostic control access over both stores during the nous.ctrl →
  ctrl-tree migration.

  Control state lives across two stores while nous.ctrl is retired path-by-path
  (see doc/design-ctrl-authority.md): ctrl-tree is the single source of truth,
  nous.ctrl is the legacy store. A path lives in exactly one store, so union
  reads and ownership-routed writes are unambiguous.

  This namespace is a transitional bridge — a surface that must serve or accept
  arbitrary paths (the HTTP control plane in nous.server, the generic ctrl tools
  in nous.mcp) reads/writes through it rather than committing to one store. It is
  deletable once nous.ctrl is fully retired."
  (:require [ctrl-tree.core :as ct]
            [ctrl-tree.refs :as refs]
            [nous.ctrl      :as ctrl]))

(defn read-node
  "Return {:value :type :node-meta} for `path`, or nil when it exists in neither
  store. Prefers the nous.ctrl typed node (which carries :type/:node-meta); falls
  back to ctrl-tree, where a path has a value but no type/meta."
  [path]
  (or (ctrl/node-info path)
      (when (contains? @refs/tree-state path)
        {:value (ct/ctrl-read path) :type nil :node-meta {}})))

(defn read-value
  "Return just the value at `path` from either store, or nil when absent."
  [path]
  (:value (read-node path)))

(defn all-entries
  "Union of every ctrl node across both stores as {:path :value :type :node-meta}
  maps. nous.ctrl nodes carry type/meta; ctrl-tree paths render with nil/empty."
  []
  (let [nc     (ctrl/all-nodes)
        nc-set (into #{} (map :path) nc)]
    (into (vec nc)
          (for [[path value] @refs/tree-state
                :when (not (contains? nc-set path))]
            {:path path :value value :type nil :node-meta {}}))))

(defn snapshot
  "Return a {path value} map of the full control state across both stores."
  []
  (into {} (map (juxt :path :value)) (all-entries)))

(defn write-any
  "Write `value` at `path` to whichever store owns it — ctrl-tree when the path
  is already present there, otherwise nous.ctrl (legacy default for new paths).
  Logical write only; no hardware dispatch."
  [path value]
  (if (contains? @refs/tree-state path)
    (ct/ctrl-write! path value)
    (ctrl/set! path value)))
