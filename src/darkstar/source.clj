(ns darkstar.source
  "`Source` — the durable truth `:mount` derives from.

  The engine never implements this: your database, event log,
  or external service does. The engine only injects the configured instance into
  the context as `:source` and threads a **basis token** through it.

  ## Why a protocol rather than just a db handle in the context

  A caller could already merge anything into the context, so the protocol has to
  earn its place. It does, for one reason: **basis tokens need a seam.**

  A basis is an opaque marker meaning \"as of this point\". On reconnect the client
  presents the basis its snapshot carried, and a store that can honour it rebuilds
  the *same* view the user was looking at rather than a newer one. Without a
  declared seam there is nowhere for that token to live, and reconnect-to-same-view
  becomes impossible — and that is the capability this whole namespace exists
  for.

  Secondarily, `:derive-key` has to cover everything `:mount` reads. A
  declared `:source` in the context makes \"what did `:mount` read\" answerable
  rather than a matter of inspecting arbitrary map keys.

  ## Basis is optional, and no store is privileged

  Stores with time-travel — Datomic, XTDB, an event log — can honour a basis.
  A plain mutable table cannot, and says so by returning `nil` from `basis`; the
  engine then rebuilds fresh, which is correct and simply loses
  reconnect-to-same-view. **The engine never interprets a basis**, so an
  implementation is free to make it a transaction id, a timestamp, an offset, or
  anything else.

  ## What this deliberately is not

  Not a query language, not an ORM, not a connection pool. `fetch` takes whatever
  query value your implementation understands, because the alternative is
  inventing an abstraction over every store — which is how a library becomes a
  framework ( non-goals).")

(defprotocol Source
  "Durable truth. Implement over whatever store you use."
  (fetch [this query] [this query basis]
    "Reads `query`. The 3-arity reads as of `basis`; an implementation that cannot
     honour a basis must ignore it and read current state rather than throwing —
     losing reconnect-to-same-view is acceptable, failing the reconnect is not.")
  (basis [this]
    "The current basis token, or `nil` if this store has no notion of one.
     Returned to the engine to ride in a recovery snapshot."))

;;; ==========================================================================
;;; Reference implementations
;;; ==========================================================================

(defn atom-source
  "A `Source` over an atom holding `{query -> value}`. No basis support.

  For tests, development, and the single-process case. Returns `nil` from `basis`,
  which is exactly how a store declares \"I cannot time-travel\"."
  [a]
  (reify Source
    (fetch [_ query] (get @a query))
    (fetch [_ query _basis] (get @a query))
    (basis [_] nil)))

(defn versioned-atom-source
  "A `Source` over an atom that keeps every version, so it *can* honour a basis.

  Exists to prove the basis seam works end to end rather than being a nominal
  parameter — 1 claims stores with time-travel rebuild the same
  view, and this is the smallest thing that demonstrates it.

  State is `{:versions [snapshot-0 snapshot-1 ...]}`; the basis is an index."
  [a]
  (reify Source
    (fetch [_ query]
      (get (peek (:versions @a)) query))
    (fetch [_ query basis]
      (let [versions (:versions @a)
            ;; An out-of-range or nil basis falls back to current, matching the
            ;; contract: a basis that cannot be honoured degrades rather than
            ;; failing.
            snapshot (if (and (integer? basis)
                              (nat-int? basis)
                              (< basis (count versions)))
                       (nth versions basis)
                       (peek versions))]
        (get snapshot query)))
    (basis [_] (dec (count (:versions @a))))))

(defn commit!
  "Appends a new version to a `versioned-atom-source`'s backing atom.

  A helper for tests rather than part of the protocol — writing is the
  application's business, not the engine's."
  [a f & args]
  (swap! a update :versions
         (fn [versions]
           (conj versions (apply f (peek versions) args)))))

;;; ==========================================================================
;;; Engine-facing helpers
;;; ==========================================================================

(defn source?
  [x]
  (satisfies? Source x))

(defn current-basis
  "The basis of `source`, or `nil` — including when `source` is absent.

  Nil-tolerant because a component may have no source at all: not every live view
  is backed by durable truth, and such a component should not need to care that
  the seam exists."
  [source]
  (when (source? source)
    (basis source)))
