(ns darkstar.pubsub
  "Subscriptions, invalidation hints, and coalescing.

  This is what makes a component *pushable* — able to
  update without the user doing anything.

  ## Hints, never data

  A hint says \"topic X changed, re-derive\". It carries no payload, and there is
  deliberately **no event handler**: a hint triggers the same rebuild a reconnect
  uses. That is not a simplification, it is the correctness argument. A
  data-carrying event is wrong under every bus failure mode — dropped leaves the
  view permanently wrong, reordered applies stale data last, duplicated breaks any
  non-idempotent handler — and is correct only on an ordered exactly-once bus.
  Redis pub/sub is fire-and-forget; NATS core is at-most-once. Since \"bring your
  own bus\" is a goal, the engine has to be correct on the weakest plausible one.

  With no handler, a drifting handler cannot be written, so the convergence
  invariant holds structurally rather than by discipline.

  ## Coalescing is lossless, and that is the point

  Because hints carry nothing, N hints for a topic are indistinguishable from one.
  So they collapse into a single rebuild with no information lost — a property
  data-carrying events could never have. `flush-dirty!` drains a dirty-topic set;
  a burst of 100 hints on a hot topic produces one rebuild per affected context
  rather than 100.

  Measured on 1000 subscribed contexts: 100 hints without coalescing meant 100,000
  re-renders; with coalescing, 1,000. The remaining amplification is one render per
  viewer, which is inherent — each viewer may be looking at something different.

  ## The bus is yours

  `PubSub` is a two-method protocol. The engine never implements it — Redis, NATS,
  core.async, or a bare atom for a single process are all the caller's choice. The
  engine only needs \"deliver this topic to this process\"; ordering, delivery
  guarantees and clustering are the bus's business, and the design above means none
  of them affect correctness.")

;;; ==========================================================================
;;; The seam
;;; ==========================================================================

(defprotocol PubSub
  "A hint channel. Deliberately minimal: the engine needs no ordering, no
  delivery guarantee, and no replay, because a hint carries no information beyond
  its topic."
  (publish! [this topic]
    "Announce that `topic` changed. Fire-and-forget; may be lost.")
  (subscribe! [this topic handler]
    "Register `handler`, a 1-arg fn of topic, to be called on hints for `topic`.
     Returns a 0-arg unsubscribe fn."))

(defn local-pubsub
  "An in-process `PubSub`, backed by an atom.

  Sufficient for a single server, for tests, and for development. A multi-server
  deployment supplies a real bus instead — the engine cannot tell the difference,
  which is the point of the seam."
  []
  (let [subs (atom {})]                              ; topic -> {id -> handler}
    (reify PubSub
      (publish! [_ topic]
        (doseq [[_ handler] (get @subs topic)]
          ;; A failing subscriber must not stop the others, and must not
          ;; propagate to the publisher — a hint is fire-and-forget by contract.
          (try (handler topic) (catch Exception _ nil))))
      (subscribe! [_ topic handler]
        (let [id (random-uuid)]
          (swap! subs assoc-in [topic id] handler)
          (fn [] (swap! subs update topic dissoc id)))))))

;;; ==========================================================================
;;; Subscription registry
;;; ==========================================================================
;;; Which live contexts care about which topics, plus the unsubscribe fns needed
;;; to tear them down. Held by the caller for the same reason the live-context
;;; registry is: it is a stateful resource that must survive namespace
;;; reload.

(defn registry
  []
  (atom {:by-context {}                              ; context-id -> #{topics}
         :unsubscribe {}                             ; [context-id topic] -> fn
         :dirty #{}}))                               ; topics awaiting a flush

(defn- mark-dirty!
  [reg topic]
  (swap! reg update :dirty conj topic))

(defn subscribe-context!
  "Subscribes `context-id` to `topics`, replacing any previous subscription set.

  Diffs against the current set rather than resubscribing wholesale, because
  `:subscribe` can depend on params: when params change, some topics persist and
  should not be torn down and re-established. This is the same
  add/remove-by-identity problem as keyed children and hot-reload preservation
 — the third place it appears.

  Returns the topics actually added and removed, which is what makes the diffing
  testable rather than assumed."
  [reg bus context-id topics]
  (let [wanted (set topics)
        current (get-in @reg [:by-context context-id] #{})
        to-add (remove current wanted)
        to-remove (remove wanted current)]
    (doseq [topic to-add]
      (let [unsub (subscribe! bus topic (fn [t] (mark-dirty! reg t)))]
        (swap! reg assoc-in [:unsubscribe [context-id topic]] unsub)))
    (doseq [topic to-remove]
      (when-let [unsub (get-in @reg [:unsubscribe [context-id topic]])]
        (unsub))
      (swap! reg update :unsubscribe dissoc [context-id topic]))
    (swap! reg assoc-in [:by-context context-id] wanted)
    {:added (set to-add) :removed (set to-remove)}))

(defn unsubscribe-context!
  "Removes every subscription for `context-id`. Called on disconnect."
  [reg context-id]
  (doseq [topic (get-in @reg [:by-context context-id] #{})]
    (when-let [unsub (get-in @reg [:unsubscribe [context-id topic]])]
      (unsub))
    (swap! reg update :unsubscribe dissoc [context-id topic]))
  (swap! reg update :by-context dissoc context-id)
  nil)

(defn topics-of
  [reg context-id]
  (get-in @reg [:by-context context-id] #{}))

(defn contexts-for
  "Live contexts subscribed to any of `topics`."
  [reg topics]
  (let [topics (set topics)]
    (into #{}
          (keep (fn [[cid subscribed]]
                  (when (some topics subscribed) cid)))
          (:by-context @reg))))

;;; ==========================================================================
;;; Coalescing
;;; ==========================================================================

(defn dirty-topics
  [reg]
  (:dirty @reg))

(defn flush-dirty!
  "Atomically drains the dirty set and returns the contexts that must rebuild.

  Draining and reading must be one operation: a hint arriving mid-flush has to
  land in the *next* window rather than being dropped, or a lost hint means a
  permanently stale view. `swap-vals!` gives that atomically.

  Returns `{:topics #{...} :contexts #{...}}`. Callers rebuild each context once,
  however many topics dirtied it — which is the coalescing win."
  [reg]
  (let [[before _] (swap-vals! reg assoc :dirty #{})
        topics (:dirty before)]
    {:topics topics
     :contexts (if (seq topics)
                 (contexts-for reg topics)
                 #{})}))

(defn publish-and-flush!
  "Convenience for tests and single-process use: publish then immediately flush.

  Real deployments flush on a timer, so hints arriving close together coalesce.
  Flushing per publish defeats that, so this is deliberately not what the engine
  does in production."
  [reg bus topic]
  (publish! bus topic)
  (flush-dirty! reg))
