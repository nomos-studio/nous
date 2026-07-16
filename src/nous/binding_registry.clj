; SPDX-License-Identifier: EPL-2.0
(ns nous.binding-registry
  "A Clojure-side registry of control-node bindings, consulted by the nomos-rt
  IPC mount (nous.ipc-mount) to dispatch a ctrl-tree write to hardware.

  It holds the *dispatch-relevant* fields of a control node — type, node-meta
  (for introspection/display), and the ordered vector of hardware bindings —
  but NOT values (values live on the ctrl-tree, the single source of truth).
  This is where output bindings move as the nous.ctrl binding model is retired
  (see doc/design-ctrl-authority.md): device.clj / schema.clj register here
  instead of calling nous.ctrl/bind!, and the mount reads bindings from here
  (unioned with the legacy nous.ctrl node during the transition).

  The binding vocabulary matches nous.ctrl/bind! — a binding is a plain map
  with :type (:midi-cc, :midi-nrpn, …), :priority, and type-specific keys —
  so nous.dispatch/dispatch-binding! consumes registry bindings unchanged."
  (:refer-clojure :exclude [bind!]))

;; path → {:type kw :node-meta map :bindings [binding-maps sorted by :priority]}
(defonce ^:private registry (atom {}))

(defn register-node!
  "Create or update the node entry at `path` with an optional :type and
  :node-meta. Preserves any existing bindings. Returns nil."
  [path & {:keys [type node-meta]}]
  (swap! registry update path
         (fn [node]
           (assoc (or node {:bindings []})
                  :type type
                  :node-meta (or node-meta {}))))
  nil)

(defn bind!
  "Add `binding` (a map; see nous.ctrl/bind!) to the node at `path` at `priority`
  (default 20; lower = higher precedence). Bindings are stored sorted by
  :priority. Raises on a same-priority conflict — call unbind! first — matching
  nous.ctrl/bind! semantics. Creates the node entry if absent. Returns nil."
  [path binding & {:keys [priority] :or {priority 20}}]
  (let [b (assoc binding :priority priority)]
    (swap! registry update path
           (fn [node]
             (let [node     (or node {:type nil :node-meta {} :bindings []})
                   existing (:bindings node)
                   conflict (first (filter #(= priority (:priority %)) existing))]
               (when conflict
                 (throw (ex-info
                         (str "binding-registry/bind!: path " (pr-str path)
                              " already has a binding at priority " priority
                              "; use a different priority or call unbind! first")
                         {:path path :existing-binding conflict :new-binding b})))
               (assoc node :bindings (vec (sort-by :priority (conj existing b))))))))
  nil)

(defn unbind!
  "Remove bindings from the node at `path`. `priority` is a number (remove the
  binding at that priority) or :all (remove all bindings). Returns nil."
  [path priority]
  (swap! registry update path
         (fn [node]
           (when node
             (assoc node :bindings
                    (if (= :all priority)
                      []
                      (vec (remove #(= priority (:priority %)) (:bindings node))))))))
  nil)

(defn unregister-path!
  "Remove the entire node entry at `path`. Returns nil."
  [path]
  (swap! registry dissoc path)
  nil)

(defn bindings-for
  "Return the ordered binding vector at `path` (empty when absent). The mount
  calls this to dispatch."
  [path]
  (:bindings (get @registry path) []))

(defn node-info
  "Return the full node entry {:type :node-meta :bindings} at `path`, or nil
  when absent. For introspection/display."
  [path]
  (get @registry path))

(defn clear!
  "Remove every registered node. Test/REPL utility."
  []
  (reset! registry {})
  nil)
