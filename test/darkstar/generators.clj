(ns darkstar.generators
  "Shared generators for fuzzing the foundation namespaces.

  Written after a real bug — multi-element removal from an unkeyed vector emitted
  ascending `:dissoc` indices and threw — survived into working code and was then
  caught only by chance, on roughly 1 run in 9. The existing generators had four
  specific gaps, each of which this namespace closes:

  1. **Keyed collections only appeared at the top level**, so a keyed collection
     nested inside a map inside another collection was never diffed.
  2. **No sets**, though `diff` has a branch for them.
  3. **Views were generated independently**, so `old` and `new` shared no
     structure — meaning the `identical?` pruning path was never exercised by the
     property tests, only by hand-written ones.
  4. **Collections were 0-4 elements**, making multi-element shrinkage rare. The
     bug needed a vector losing 3+ elements at once.

  The generators here deliberately favour *awkward* shapes over realistic ones:
  empty collections, nils as values, keys that look like indices, deep nesting,
  and mutations derived from an original rather than generated beside it."
  (:require [clojure.test.check.generators :as gen]))

;;; ==========================================================================
;;; Scalars
;;; ==========================================================================

(def gen-scalar
  "Includes the values most likely to be handled specially somewhere: nil, empty
  string, zero, negative numbers, and keywords that could be confused with paths."
  (gen/one-of [gen/small-integer
               (gen/return 0)
               (gen/return -1)
               gen/string-alphanumeric
               (gen/return "")
               gen/boolean
               (gen/return nil)
               gen/keyword
               (gen/return :items)
               gen/large-integer]))

;;; ==========================================================================
;;; Keyed collections
;;; ==========================================================================

(def gen-item
  (gen/hash-map :id gen/nat
                :label gen/string-alphanumeric
                :n gen/small-integer))

(defn- dedupe-by-id
  [items]
  (->> items (group-by :id) vals (map first) vec))

(def gen-keyed-coll
  "Up to 12 items, deduplicated by `:id`. Larger than the previous 8 so
  multi-element changes are common rather than rare."
  (gen/fmap (fn [items] (with-meta (dedupe-by-id items) {:live/key :id}))
            (gen/vector gen-item 0 12)))

;;; ==========================================================================
;;; Views
;;; ==========================================================================

(def gen-nested
  "Nested structure mixing maps, plain vectors, sets and **keyed collections at
  any depth** — the gap that let the ascending-index bug through.

  Vectors go up to 8 elements so that shrinking one by several at once is a
  frequent case rather than an unlikely one."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/map gen/keyword inner {:max-elements 5})
                  (gen/vector inner 0 8)
                  ;; Sets: `diff` has a branch for them and nothing generated one.
                  (gen/set (gen/one-of [gen/small-integer gen/keyword])
                           {:max-elements 5})
                  ;; A keyed collection reachable from anywhere, not just the root.
                  gen-keyed-coll]))
   gen-scalar))

(def gen-view
  (gen/map gen/keyword gen-nested {:max-elements 6}))

;;; ==========================================================================
;;; Derived mutations
;;; ==========================================================================
;;; Two independently generated views share no structure, so `identical?` pruning
;;; never fires and the common real case — a handler changing one field of an
;;; existing view — is never generated. These produce a *derived* view instead,
;;; which is what an `:on` handler actually does.

(defn- paths-in
  "Every path into `x` that addresses a map entry or vector element."
  [x]
  (letfn [(walk [node path acc]
            (cond
              (map? node)
              (reduce (fn [a [k v]] (walk v (conj path k) (conj a (conj path k))))
                      acc node)

              (and (vector? node) (seq node))
              (reduce (fn [a i] (walk (nth node i) (conj path i) (conj a (conj path i))))
                      acc (range (count node)))

              :else acc))]
    (vec (walk x [] []))))

(def gen-derived-change
  "`[old new]` where `new` is `old` with one path mutated — so they share
  structure everywhere else.

  Mutations include replacing a value, dissoc'ing a key, growing a collection and
  **shrinking one by several elements at once**, which is the case the earlier
  generators effectively never produced."
  (gen/let [old gen-view
            v gen-scalar
            shrink-by (gen/choose 1 4)
            kind (gen/elements [:replace :dissoc :grow :shrink :clear])]
    (let [paths (paths-in old)]
      (if (empty? paths)
        [old (assoc old :added v)]
        (gen/let [path (gen/elements paths)]
          [old
           (try
             (case kind
               :replace (assoc-in old path v)
               :dissoc (if (= 1 (count path))
                         (dissoc old (first path))
                         (let [parent (get-in old (butlast path))
                               k (last path)]
                           (if (map? parent)
                             (update-in old (butlast path) dissoc k)
                             (assoc-in old path v))))
               :grow (let [node (get-in old path)]
                       (if (vector? node)
                         (assoc-in old path (with-meta (conj node v) (meta node)))
                         (assoc-in old path [v v v])))
               ;; The important one: drop several elements in a single step.
               :shrink (let [node (get-in old path)]
                         (if (and (vector? node) (seq node))
                           (assoc-in old path
                                     (with-meta
                                       (vec (drop-last (min shrink-by (count node)) node))
                                       (meta node)))
                           (assoc-in old path v)))
               :clear (let [node (get-in old path)]
                        (assoc-in old path
                                  (cond
                                    (vector? node) (with-meta [] (meta node))
                                    (map? node) {}
                                    (set? node) #{}
                                    :else nil))))
             ;; A generated mutation can be inapplicable (e.g. assoc-in through a
             ;; scalar). Falling back keeps the generator total rather than making
             ;; failures look like property violations.
             (catch Exception _ (assoc old :fallback v)))])))))

(def gen-keyed-change
  "`[old new]` for a keyed collection, exercising insert, delete, update, reorder
  and multi-element shrinkage together."
  (gen/let [coll gen-keyed-coll
            drop-n (gen/choose 0 4)
            change-n (gen/choose 0 4)
            add-n (gen/choose 0 4)
            shuffle? gen/boolean]
    (let [kept (vec (drop drop-n coll))
          changed (vec (map-indexed
                        (fn [i item]
                          (if (< i change-n)
                            (assoc item :label (str (:label item) "!"))
                            item))
                        kept))
          existing (set (map :id changed))
          additions (->> (range)
                         (remove existing)
                         (take add-n)
                         (mapv (fn [id] {:id id :label "new" :n 0})))
          combined (into changed additions)]
      [{:items coll}
       {:items (with-meta (if shuffle? (vec (shuffle combined)) combined)
                 {:live/key :id})}])))
