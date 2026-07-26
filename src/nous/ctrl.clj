; SPDX-License-Identifier: EPL-2.0
(ns nous.ctrl
  "nous control tree.

  The control tree is a persistent map within the shared system-state atom.
  Nodes hold a typed value and optional physical bindings (MIDI CC, OSC, etc.).

  ## set! — recording values

  ctrl/set! records a value into a control node. It never dispatches to physical
  outputs — use it for values arriving from outside (MIDI/OSC input, peer sync,
  UI, config) or for internal state with no hardware target. Recording an inbound
  value with set! cannot echo back to hardware, so it can't cause feedback loops.

  Hardware *output* no longer flows through this namespace. Clojure-originated
  values reach bound devices by writing the ctrl-tree (ctrl-tree.core/ctrl-write!),
  whose root IpcMount dispatches the path's bindings from nous.binding-registry via
  nous.dispatch. nous.ctrl is the legacy value/node model, retired path-by-path
  (see doc/design-ctrl-authority.md).

  ## Node lifecycle
    (ctrl/defnode! [:filter/cutoff] :type :float :meta {:range [0.0 1.0]} :value 0.5)
    (ctrl/set!     [:filter/cutoff] 0.3)    ; record a value — no hardware dispatch
    (ctrl/get      [:filter/cutoff])        ; => 0.3

  ## Safety net
    (ctrl/checkpoint! :known-good)          ; snapshot tree
    ;; ... live editing ...
    (ctrl/panic!)                           ; revert to :known-good immediately

  ## Undo
    (ctrl/undo!)                            ; revert last structural change
    (ctrl/undo! :now)                       ; explicit immediate (same in Phase 1)

  ## Node types (Q8)
    :int      integer; optional :range [lo hi]
    :float    floating-point; optional :range [lo hi]
    :bool     boolean
    :keyword  arbitrary keyword
    :enum     keyword from declared set; :values [k1 k2 ...]
    :data     opaque Clojure value (default)

  ## Binding priorities (Q9) — lower number = higher priority
    0   Clojure code (code-originated writes always win)
    10  Ableton Link
    20  MIDI
    30  OSC

  Key design decisions: Q4, Q8, Q9, Q10, Q47, Q48."
  (:refer-clojure :exclude [get])
  (:require [nous.kairos    :as kairos]
            [nous.timeline  :as timeline]))

;; ---------------------------------------------------------------------------
;; System reference — registered by nous.core/start! (avoids circular dep)
;; ---------------------------------------------------------------------------

(defonce ^:private system-ref (atom nil))

;; ---------------------------------------------------------------------------
;; Transaction log — source context and capacity
;; ---------------------------------------------------------------------------

(def ^:dynamic *current-tx-source*
  "Source context for transactions committed on this thread.
  Bind via `with-source` to attribute writes to a loop, device, or user action.
  Default: REPL/user context."
  {:source/kind :user :source/id :repl :source/parent nil})

(def ^:dynamic *tx-log-max*
  "Maximum number of transactions retained in the in-memory log.
  Oldest entries are evicted when the bound is exceeded."
  10000)

(defmacro with-source
  "Execute `body` with `*current-tx-source*` bound to `source`.
  All ctrl/set! calls within body record `source` as their origin.

  Example:
    (ctrl/with-source {:source/kind :loop :source/id :bass}
      (ctrl/set! [:filter/cutoff] 0.7))"
  [source & body]
  `(binding [*current-tx-source* ~source]
     ~@body))

(defn- build-tx
  "Build a transaction map for a single path write."
  [beat wall-ns source path before after]
  {:tx/id      (timeline/squuid)
   :tx/beat    beat
   :tx/wall-ns wall-ns
   :tx/source  source
   :tx/changes [{:path path :before before :after after}]})

(defn- append-tx
  "Return state with `tx` appended to :tx-log, evicting the oldest entry
  if the log exceeds *tx-log-max*."
  [s tx]
  (update s :tx-log
          (fn [log]
            (let [log' (conj log tx)]
              (if (> (count log') *tx-log-max*)
                (subvec log' 1)
                log')))))

;; ---------------------------------------------------------------------------
;; Watcher registry
;;
;; {path {watch-key fn}} — keyed by watch-key so callers can remove them.
;; Watchers fire synchronously (in the writer's thread) after every set! that
;; touches their path.
;; Exceptions in watchers are printed and swallowed so they can't kill the
;; caller (e.g. a mod-route! runner thread).
;; ---------------------------------------------------------------------------

(defonce ^:private watchers (atom {}))

;; Global watchers — fire on every set! regardless of path.
;; Stored separately from per-path watchers.
(defonce ^:private global-watchers (atom {}))

(defn -register-system!
  "Wire ctrl to the shared system-state atom. Called by nous.core/start!."
  [ref]
  (reset! system-ref ref))

;; ---------------------------------------------------------------------------
;; CtrlNode record (Q8)
;; ---------------------------------------------------------------------------

(defrecord CtrlNode
  [value      ; current value (any Clojure type)
   type       ; :int :float :bool :keyword :enum :data
   node-meta  ; metadata map — :range [lo hi], :values [...], etc.
   bindings]) ; vector of binding maps, sorted by :priority ascending

(defn- make-node
  "Create a new CtrlNode with type :data and no bindings."
  ([]        (->CtrlNode nil  :data {} []))
  ([v]       (->CtrlNode v    :data {} []))
  ([v t]     (->CtrlNode v    t     {} []))
  ([v t m]   (->CtrlNode v    t     m  [])))

;; ---------------------------------------------------------------------------
;; Internal path helpers
;; ---------------------------------------------------------------------------

(defn- tree-path
  "Prepend :tree to a user path vector for assoc-in navigation."
  [path]
  (into [:tree] path))

(defn- get-node [state path]
  (get-in state (tree-path path)))

(defn- put-node [state path node]
  (assoc-in state (tree-path path) node))

;; ---------------------------------------------------------------------------
;; Undo stack helpers (Q47)
;; ---------------------------------------------------------------------------

(def ^:private default-undo-depth 50)

(defn- push-undo
  "Return updated state with the current :tree and :serial pushed onto the
  undo stack, bounded at the configured depth (falls back to default-undo-depth)."
  [state]
  (let [depth (or (clojure.core/get-in state [:config :ctrl/undo-stack-depth])
                  default-undo-depth)]
    (update state :undo-stack
            (fn [stack]
              (let [entry     {:tree   (:tree state)
                               :serial (:serial state)}
                    new-stack (conj stack entry)]
                (if (> (count new-stack) depth)
                  (subvec new-stack 1)
                  new-stack))))))

(defn- fire-watchers!
  "Fire all watchers registered on the changed path, then all global watchers.
  Watcher signature: (fn [tx new-state] ...) where tx is the full transaction
  map and new-state is the materialized ctrl tree after the transaction."
  [tx new-state]
  (let [path (-> tx :tx/changes first :path)]
    (doseq [[_ f] (clojure.core/get @watchers path)]
      (try
        (f tx new-state)
        (catch Exception e
          (binding [*out* *err*]
            (println (str "[ctrl] watcher error on " (pr-str path)
                          ": " (.getMessage e)))))))
    (doseq [[_ f] @global-watchers]
      (try (f tx new-state)
           (catch Exception e
             (binding [*out* *err*]
               (println (str "[ctrl] global watcher error: " (.getMessage e)))))))))

;; ---------------------------------------------------------------------------
;; Core read/write API
;; ---------------------------------------------------------------------------

(defn get
  "Read the current value at `path` in the control tree.
  Returns nil if the path does not exist or has not been set.

  Example:
    (ctrl/get [:filter/cutoff])    ; => 0.5
    (ctrl/get [:loops :bass :vel]) ; => 64"
  [path]
  (some-> (get-node @@system-ref path) :value))

(defn node-info
  "Return the full CtrlNode map at `path`, or nil if absent.
  Useful for inspecting type, meta, and bindings."
  [path]
  (get-node @@system-ref path))

(defn set!
  "Record `value` at `path` in the control tree. Never dispatches to hardware.

  Use set! when the value originates outside Clojure — incoming MIDI CC,
  incoming OSC, peer sync, UI writes, config updates. Also use it for any
  internal state that has no hardware binding. Because set! never echoes to
  hardware, it is safe to call from MIDI/OSC input handlers without creating
  feedback loops.

  Not an undo target by default; pass :undoable true to push to the undo stack.
  Creates a :data node if the path does not exist; preserves existing type and bindings.

  set! records a value only; it never dispatches to hardware. Clojure-originated
  values that must reach bound devices go through ctrl-tree.core/ctrl-write! (the
  root IpcMount dispatches the path's registry bindings).

  Examples:
    (ctrl/set! [:filter/cutoff] 0.7)           ; record value, no hardware dispatch
    (ctrl/set! [:chord] :C-major :undoable true)
    ;; In a MIDI input handler — safe: will not echo back to hardware:
    (ctrl/set! [:cc/74] incoming-value)"
  [path value & {:keys [undoable]}]
  (let [beat    (timeline/current-beat)
        wall-ns (* (timeline/current-wall-ms) 1000000)
        source  *current-tx-source*
        result  (volatile! nil)]
    (swap! @system-ref
           (fn [s]
             (let [before (some-> (get-node s path) :value)
                   tx     (build-tx beat wall-ns source path before value)
                   s'     (if undoable (push-undo s) s)
                   s''    (-> s'
                              (put-node path (assoc (or (get-node s path) (make-node)) :value value))
                              (append-tx tx))]
               (vreset! result [tx s''])
               s'')))
    (let [[tx new-state] @result]
      (fire-watchers! tx new-state)
      (when (kairos/connected?)
        (kairos/send-tx-log! tx))))
  nil)

(defn send-raw-nrpn!
  "Fire a raw NRPN directly to kairos, bypassing the control tree.

  `channel`  — MIDI channel (1–16)
  `nrpn-num` — NRPN parameter number (0–16383); split into CC99/CC98
  `value`    — data value; 14-bit [0–16383] by default (see :bits option)

  Options:
    :bits — 7 for 7-bit value range [0,127]; 14 for 14-bit range [0,16383] (default).
            Both always send CC99/CC98/CC6/CC38 using standard 14-bit wire encoding
            (wire value = CC6*128 + CC38).  :bits only controls value clamping.

  Useful for compound-addressed Hydrasynth parameters (ribbon sub-params,
  ARP sub-params) where CC6 encodes a sub-parameter rather than data MSB.
  In that case pack the value as (bit-or (bit-shift-left sub-param 7) data).

  Does nothing if kairos is not connected.

  Examples:
    ;; Ribbon mode sub-param 0=off, NRPN group 0x41 (65):
    (ctrl/send-raw-nrpn! 1 (bit-or (bit-shift-left 65 7) 0) 1)

    ;; Portamento NRPN 1, 14-bit value 500:
    (ctrl/send-raw-nrpn! 1 1 500)"
  ([channel nrpn-num value]
   (send-raw-nrpn! channel nrpn-num value 14))
  ([channel nrpn-num value bits]
   (when (kairos/connected?)
     (let [ch        (int channel)
           nrpn      (int nrpn-num)
           bits      (int bits)
           max-val   (if (= 14 bits) 16383 127)
           clamped   (max 0 (min max-val (long value)))
           param-msb (bit-and (bit-shift-right nrpn 7) 0x7F)
           param-lsb (bit-and nrpn 0x7F)
           data-msb  (bit-and (bit-shift-right clamped 7) 0x7F)
           data-lsb  (bit-and clamped 0x7F)]
       (kairos/send-cc! ch 99 param-msb)
       (kairos/send-cc! ch 98 param-lsb)
       (kairos/send-cc! ch  6 data-msb)
       (kairos/send-cc! ch 38 data-lsb)))))

;; ---------------------------------------------------------------------------
;; Node declaration (Q8)
;; ---------------------------------------------------------------------------

(defn defnode!
  "Declare a typed control tree node at `path`.

  Updates the node's type and metadata without changing its current value
  (or sets an initial value if :value is provided and the node is new).
  If the node already exists its bindings are preserved.

  Options:
    :type   — #{:int :float :bool :keyword :enum :data} (default :data)
    :meta   — metadata map; for :int/:float use {:range [lo hi]};
              for :enum use {:values [:a :b :c]}
    :value  — initial value (ignored if the node already exists and has a value)

  This is a structural change; it increments the tree serial number.

  Example:
    (ctrl/defnode! [:filter/cutoff]
                   :type :float :meta {:range [0.0 1.0]} :value 0.5)"
  [path & {:keys [type node-meta value] :or {type :data node-meta {}}}]
  (swap! @system-ref
         (fn [s]
           (let [existing  (get-node s path)
                 cur-value (if (and (some? value) (nil? (some-> existing :value)))
                             value
                             (some-> existing :value))
                 node      (->CtrlNode cur-value type node-meta
                                       (or (some-> existing :bindings) []))]
             (-> (put-node s path node)
                 (update :serial inc)))))
  nil)

;; ---------------------------------------------------------------------------
;; Binding management (Q9)
;; ---------------------------------------------------------------------------

(defn bind!
  "Register a physical binding on the control tree node at `path`.

  Raises if the node already has a binding with the same priority (Q9 fail-fast).
  Bindings are stored sorted by priority (ascending; lower = higher priority).

  The `source` map must include at minimum :type. Recognized binding types:
    :midi-cc — requires :channel (1–16) and :cc-num (0–127);
               optional :range [lo hi] (default [0 127]) for value scaling.

  Options:
    :priority — integer (default 20 for MIDI); lower = higher priority.

  This is a structural change; increments the tree serial number.

  Examples:
    (ctrl/bind! [:filter/cutoff] {:type :midi-cc :channel 1 :cc-num 74})
    (ctrl/bind! [:filter/cutoff] {:type :midi-cc :channel 1 :cc-num 74}
                :priority 20)
    (ctrl/bind! [:global/bpm] link-source :priority 10)"
  [path source & {:keys [priority] :or {priority 20}}]
  (let [binding (assoc source :priority priority)]
    (swap! @system-ref
           (fn [s]
             (let [node     (or (get-node s path) (make-node))
                   existing (:bindings node)
                   conflict (first (filter #(= priority (:priority %)) existing))]
               (when conflict
                 (throw (ex-info
                         (str "ctrl/bind!: path " (pr-str path)
                              " already has a binding at priority " priority
                              "; use a different priority or call unbind! first")
                         {:path path :existing-binding conflict :new-binding binding})))
               (let [new-bindings (vec (sort-by :priority (conj existing binding)))]
                 (-> (put-node s path (assoc node :bindings new-bindings))
                     (update :serial inc)))))))
  nil)

(defn unbind!
  "Remove bindings from the node at `path`.

  If `priority` is a number, removes the binding at that priority.
  If `priority` is :all, removes all bindings.

  This is a structural change; increments the tree serial number.

  Example:
    (ctrl/unbind! [:filter/cutoff] 20)   ; remove MIDI binding
    (ctrl/unbind! [:filter/cutoff] :all) ; remove all bindings"
  [path priority]
  (swap! @system-ref
         (fn [s]
           (let [node (or (get-node s path) (make-node))]
             (-> (put-node s path
                           (assoc node :bindings
                                  (if (= :all priority)
                                    []
                                    (vec (remove #(= priority (:priority %))
                                                 (:bindings node))))))
                 (update :serial inc)))))
  nil)

(defn- walk-nodes
  "Walk nested tree map `m`, returning [[path node] ...] for every CtrlNode leaf."
  [m prefix]
  (reduce-kv
    (fn [acc k v]
      (let [p (conj prefix k)]
        (cond
          (instance? CtrlNode v) (conj acc [p v])
          (map? v)               (into acc (walk-nodes v p))
          :else                  acc)))
    []
    m))

(defn bindings-by-type
  "Return [[path binding] ...] for every binding of `binding-type` in the ctrl tree.

  Walks all nodes in the tree regardless of depth. Returns an empty sequence
  if the system is not started or no bindings of that type exist.

  Used by nous.midi-in to locate :midi-device-input bindings on each message.

  Example:
    (ctrl/bindings-by-type :midi-device-input)
    ;; => [[ [:filter/cutoff] {:type :midi-device-input :device :arturia/keystep ...} ] ...]"
  [binding-type]
  (when-let [s @system-ref]
    (let [tree (:tree @s)]
      (for [[path node] (walk-nodes tree [])
            binding     (:bindings node)
            :when       (= binding-type (:type binding))]
        [path binding]))))

(defn child-keys
  "Return the direct child keys at an intermediate tree node `path`.

  Unlike `get`, which reads leaf CtrlNode values, this reads the keys of
  an intermediate map in the tree — used by nous.schema to enumerate
  registered models and realizations.

  Returns nil if the path doesn't exist or is a CtrlNode leaf.

  Example:
    (ctrl/child-keys [:txlog/schema :device-models])
    ;; => (:arp2600 :korg/minilogue-xd)"
  [path]
  (when-let [s @system-ref]
    (let [node (get-in (:tree @s) path)]
      (when (and (map? node) (not (instance? CtrlNode node)))
        (keys node)))))

(defn all-nodes
  "Return a seq of {:path [...] :value v :type kw :node-meta m} maps for every ctrl node.

  Walks the entire tree; useful for serialising the tree to external consumers
  (e.g. the control-plane HTTP server).  Returns an empty seq if the system is
  not started.

  Example:
    (ctrl/all-nodes)
    ;; => ({:path [:filter/cutoff] :value 0.5 :type :float :node-meta {:range [0.0 1.0]}} ...)"
  []
  (if-let [s @system-ref]
    (map (fn [[path node]]
           {:path      path
            :value     (:value node)
            :type      (:type node)
            :node-meta (or (:node-meta node) {})})
         (walk-nodes (:tree @s) []))
    []))

(defn tx-log
  "Return the current in-memory transaction log as a vector of transaction maps.

  Each transaction has:
    :tx/id      — squuid
    :tx/beat    — beat position at write time
    :tx/wall-ns — wall-clock nanoseconds at write time
    :tx/source  — {:source/kind kw :source/id kw :source/parent nil-or-id}
    :tx/changes — [{:path [...] :before v :after v}]

  Returns an empty vector if the system is not started or no transactions have
  been recorded yet.

  Example:
    (ctrl/tx-log)
    ;; => [{:tx/id #uuid \"...\" :tx/beat 0.0 ...} ...]"
  []
  (if-let [s @system-ref]
    (or (:tx-log @s) [])
    []))

;; ---------------------------------------------------------------------------
;; Watcher API
;; ---------------------------------------------------------------------------

(defn watch!
  "Register a callback fired synchronously after every set! that writes to `path`.

  `watch-key` — any value; identifies this watcher for later removal.
                Use a namespaced keyword to avoid collisions, e.g. ::my-ns/key.
  `f`         — (fn [tx new-state]) called after MIDI dispatch completes.
                `tx` is the full transaction map; `new-state` is the
                materialized ctrl tree after the transaction.

  Re-registering the same path+watch-key replaces the previous callback.
  Exceptions thrown by `f` are printed to stderr and swallowed.

  Example:
    (ctrl/watch! [:arc/tension] ::my-listener
                 (fn [tx _state]
                   (println [:arc/tension] \"changed to\"
                            (:after (first (:tx/changes tx))))))"
  [path watch-key f]
  (swap! watchers assoc-in [path watch-key] f)
  nil)

(defn unwatch!
  "Remove the watcher identified by `watch-key` from `path`.
  No-op if the key is not registered."
  [path watch-key]
  (swap! watchers update path dissoc watch-key)
  nil)

(defn unwatch-all!
  "Remove all watchers registered on `path`."
  [path]
  (swap! watchers dissoc path)
  nil)

(defn watch-global!
  "Register `f` to be called with [path value] on every ctrl-tree change,
  regardless of which path was written.

  `watch-key` identifies this watcher for later removal via `unwatch-global!`.
  Use a namespaced keyword to avoid collisions, e.g. ::server/ws-broadcast.
  Re-registering the same key replaces the previous callback.
  Exceptions thrown by `f` are printed to stderr and swallowed.

  Example:
    (ctrl/watch-global! ::ws/broadcast
                        (fn [tx _state]
                          (broadcast! (-> tx :tx/changes first :path)
                                      (-> tx :tx/changes first :after))))"
  [watch-key f]
  (swap! global-watchers assoc watch-key f)
  nil)

(defn unwatch-global!
  "Remove the global watcher identified by `watch-key`.
  No-op if the key is not registered."
  [watch-key]
  (swap! global-watchers dissoc watch-key)
  nil)

;; ---------------------------------------------------------------------------
;; Checkpoints and panic (Q47)
;; ---------------------------------------------------------------------------

(defn checkpoint!
  "Save a named snapshot of the current control tree.

  The snapshot is stored in :checkpoints under `name`. Subsequent `panic!`
  calls with this name (or with no name, if this is the most recent checkpoint)
  will restore the tree to this state.

  Checkpoints are not themselves undo targets — panic! pushes to the undo stack
  before reverting, so `undo!` after `panic!` undoes the revert.

  Example:
    (ctrl/checkpoint! :known-good)
    (ctrl/checkpoint! :pre-breakdown)"
  [name]
  (swap! @system-ref
         (fn [s]
           (-> s
               (assoc-in [:checkpoints name]
                         {:tree     (:tree s)
                          :serial   (:serial s)
                          :saved-at (System/currentTimeMillis)})
               (assoc :last-checkpoint name))))
  nil)

(defn panic!
  "Revert the control tree to a saved checkpoint immediately.

  (panic!)          — revert to the most recently defined checkpoint
  (panic! :name)    — revert to the named checkpoint

  Before reverting, the current tree is pushed onto the undo stack so that
  `undo!` after `panic!` can restore the pre-panic state.

  Example:
    (ctrl/checkpoint! :known-good)
    ;; ... things go wrong ...
    (ctrl/panic!)           ; restore :known-good"
  ([]
   (let [name (:last-checkpoint @@system-ref)]
     (if name
       (panic! name)
       (binding [*out* *err*]
         (println "[ctrl] panic!: no checkpoint saved; call (ctrl/checkpoint! name) first")))))
  ([name]
   (let [cp (get-in @@system-ref [:checkpoints name])]
     (if-not cp
       (binding [*out* *err*]
         (println (str "[ctrl] panic!: no checkpoint named " (pr-str name))))
       (do
         (swap! @system-ref
                (fn [s]
                  (-> (push-undo s)
                      (assoc :tree   (:tree cp)
                             :serial (:serial cp)))))
         (binding [*out* *err*]
           (println (str "[ctrl] reverted to checkpoint " (pr-str name)))))))))

;; ---------------------------------------------------------------------------
;; Undo (Q47)
;; ---------------------------------------------------------------------------

(defn undo!
  "Revert the last structural change pushed to the undo stack.

  Phase 1: always immediate. Beat-aware boundary semantics (Q47 §undo-timing)
  are deferred — a future sprint will queue the revert at the next loop boundary
  when called without :now.

  (undo!)      — revert last structural change
  (undo! :now) — explicit immediate revert (same as above in Phase 1)

  Returns true if a change was reverted, false if the undo stack is empty."
  ([] (undo! :now))
  ([_timing]
   (let [empty? (empty? (:undo-stack @@system-ref))]
     (if empty?
       (do (binding [*out* *err*]
             (println "[ctrl] undo!: nothing to undo"))
           false)
       (do (swap! @system-ref
                  (fn [s]
                    (let [entry (peek (:undo-stack s))]
                      (-> s
                          (assoc :tree   (:tree entry)
                                 :serial (:serial entry))
                          (update :undo-stack pop)))))
           true)))))
