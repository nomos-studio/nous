; SPDX-License-Identifier: EPL-2.0
(ns nous.ctrl-bridge
  "Store-agnostic control access during the nous.ctrl → ctrl-tree migration.

  A control node is assembled from up to three facets (see
  doc/design-ctrl-authority.md):
    - a legacy nous.ctrl node — self-contained: value + :type + :node-meta;
    - a ctrl-tree value (ctrl-tree.refs/tree-state) — the value of a migrated path;
    - a nous.binding-registry entry — the :type / :node-meta (and bindings) of a
      migrated path; the registry is the typed-node metadata home.
  A migrated path carries its value on ctrl-tree and its type/meta in the registry,
  so read-node merges the two. nous.ctrl is retired path-by-path; a path lives in
  exactly one value store, so union reads and ownership-routed writes are unambiguous.

  This namespace is a transitional bridge — a surface that must serve or accept
  arbitrary paths (the HTTP control plane in nous.server, the generic ctrl tools
  in nous.mcp) reads/writes through it rather than committing to one store. It is
  deletable once nous.ctrl is fully retired."
  (:require [ctrl-tree.core :as ct]
            [ctrl-tree.refs :as refs]
            [nous.binding-registry :as breg]
            [nous.ctrl      :as ctrl]))

(defn read-node
  "Return {:value :type :node-meta} for `path`, or nil when no store knows it.

  A legacy nous.ctrl node is self-contained and preferred. Otherwise the path is
  ctrl-tree-era: its value comes from ctrl-tree and its :type/:node-meta from the
  binding-registry. A path known to either — a written ctrl-tree value OR a
  registry declaration (even before its first write) — resolves; a
  declared-but-unwritten registry node returns :value nil."
  [path]
  (or (ctrl/node-info path)
      (let [bnode    (breg/node-info path)
            in-tree? (contains? @refs/tree-state path)]
        (when (or bnode in-tree?)
          {:value     (ct/ctrl-read path)
           :type      (:type bnode)
           :node-meta (:node-meta bnode {})}))))

(defn read-value
  "Return just the value at `path` from either store, or nil when absent."
  [path]
  (:value (read-node path)))

(defn all-entries
  "Union of every ctrl node across all stores as {:path :value :type :node-meta}
  maps. Legacy nous.ctrl nodes carry their own type/meta; each ctrl-tree/registry
  path is assembled by read-node (value from ctrl-tree, type/meta from the
  binding-registry — including registry-declared nodes not yet written)."
  []
  (let [nc     (ctrl/all-nodes)
        nc-set (into #{} (map :path) nc)
        extra  (into #{} (remove nc-set)
                     (concat (keys @refs/tree-state) (breg/paths)))]
    (into (vec nc)
          (map (fn [p] (assoc (read-node p) :path p)) extra))))

(defn snapshot
  "Return a {path value} map of the full control state across both stores."
  []
  (into {} (map (juxt :path :value)) (all-entries)))

(defn write-any
  "Write `value` at `path` to whichever store owns it:
    - ctrl-tree, when the path is already present there;
    - the legacy nous.ctrl node, when one exists — updated in place (the bridge
      routes to the owning store; it does not opportunistically migrate, and a
      read-node prefers the nous.ctrl node, so its updates must stay there);
    - otherwise ctrl-tree — the single source of truth for NEW state (authority
      rule 1: no new state on nous.ctrl).
  Logical write only; no hardware dispatch."
  [path value]
  (cond
    (contains? @refs/tree-state path) (ct/ctrl-write! path value)
    (ctrl/node-info path)             (ctrl/set! path value)
    :else                             (ct/ctrl-write! path value)))
