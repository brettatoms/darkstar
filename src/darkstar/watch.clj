(ns darkstar.watch
  "Subscriptions discovered from the render, rather than declared beside it.

  ## The problem this solves

  A component that updates when one thing changes previously had to say so three
  times, and keep the three consistent by hand:

      :mount     (fn [ctx] {:online? (presence/online? ch user)})  ; depends on…
      :subscribe (fn [ctx] [[:presence ch user]])                  ; …watch this…
      :render    (fn [view] (boundary [] …))                       ; …patch here

  Three declarations of one fact. Every live-children bug in this project was one of
  the three drifting from the other two, which is a structural problem rather than a
  run of bad luck.

  `watch` collapses them. It reads a value and, as a side effect of rendering,
  records that the surrounding fragment depended on that topic:

      (fragment (member-id username)
        (fn []
          (let [online? (watch [:presence ch username]
                               #(presence/online? ch username))]
            [:li {:id (member-id username)} …])))

  The subscription set is now *whatever the render actually read*, so it cannot
  disagree with the render. There is one site to change.

  ## The subscription set changes, so re-derive it on every render

  This follows from the above but is easy to miss, and missing it produces a bug that
  looks like a pass. A dependency set is **data**: a roster that reads one presence
  topic per member depends on a different set of topics the moment its membership
  changes. Subscribing once, at mount, is therefore not enough.

  Observed in a browser: a viewer mounted alone, a second member joined, and the
  viewer's roster re-rendered and showed them correctly — but that viewer had never
  subscribed to the new member's presence topic, because it did not exist at mount.
  The joiner appeared and then never went offline. The first render and the join both
  looked right, which is what let it through a headless test.

  So a caller must treat the topics from every render as the current truth. See
  `darkstar.live/refresh!`, which reports `:added` and `:removed` for exactly
  this.

  ## The laziness hazard — read this before using it

  Recording happens in a dynamic binding, so anything evaluated **outside** that
  binding is invisible to it. A lazy sequence escapes:

      [:ul (for [m members] (member-row m))]    ; WRONG — rows render later
      [:ul (mapv member-row members)]           ; right — forced inside

  This fails *silently*: the first render is correct because the seq is realised
  during serialisation, but the rows' topics were never recorded, so nothing ever
  re-renders them. `render-recording` therefore walks its result and throws on an
  unrealised lazy seq, which turns a silent staleness bug into a loud one.

  ## Relationship to `:subscribe` and boundaries

  This namespace requires nothing, and it is optional. A `darkstar` application can
  push patches by hand — naming a selector and rendering the HTML for it — and never
  touch `watch` at all. The two styles also **mix within one component**: markup wrapped
  in `fragment` is managed by `darkstar.live`, and plain hiccup beside it is the
  application's to push. Verified, not assumed.

  So the trade is worth stating plainly. Manual pushes fail loudly — a wrong selector
  produces `PatchElementsNoTargetsFound` in the browser console. `watch` fails silently,
  and `darkstar.diagnose` exists because of that.

  ## Why \"fragment\"

  It was `region` first, which was wrong on two counts. It described geometry — an area
  of the screen — when the interesting property is being independently pushable; a plain
  `[:div]` is also an area of the screen. And it did not pair with `watch`: a verb about
  dependency beside a noun about layout gave no hint the two are halves of one
  mechanism.

  \"Fragment\" is what this already is on the wire, and the word Datastar and htmx
  users have for a piece of HTML pushed on its own. `watch` says why it re-renders;
  `fragment` says what gets sent.")

(def ^:dynamic ^:private *recording*
  "Non-nil during a recording render. Holds `{:topics #{} :fragments {}}`."
  nil)

(defn recording?
  "True inside a recording render.

  Public so a caller can tell whether `watch` will record or merely read — useful
  when a component is rendered outside the engine, in a test or at a REPL."
  []
  (some? *recording*))

(defn watch
  "Reads `(read-fn)`, recording that the surrounding fragment depends on `topic`.

  Outside a recording render this is exactly `(read-fn)`, so a component stays
  callable on its own — which is what keeps it testable without an engine."
  [topic read-fn]
  (let [v (read-fn)]
    (when *recording*
      ;; The value AND the reader are recorded, not just the topic. The value makes
      ;; a later no-op hint answerable without rendering; the reader is what makes
      ;; it answerable without the application supplying a second copy of its own
      ;; read logic. See `unchanged?`.
      (vswap! *recording*
              (fn [acc]
                (-> acc
                    (update :topics conj topic)
                    ;; A VECTOR per topic, not one entry. A fragment may read the same
                    ;; topic more than once — `messages` reads `[:messages id]` for the
                    ;; list and again for the count of older ones — and keying by topic
                    ;; alone made the last read overwrite the first. `unchanged?` then
                    ;; compared the wrong value and pruned a real change: a new message
                    ;; produced no patch at all, because the surviving read was a count
                    ;; that had not moved.
                    (update-in [:reads topic] (fnil conj [])
                               {:value v :read-fn read-fn})))))
    v))

(defn- same?
  "Whether two read values can be treated as unchanged.

  `identical?` first, because that is the whole point: over persistent structures an
  untouched collection is the same object, so this is a pointer comparison and costs
  nothing. It is also what the old engine's pruning relied on.

  Then `=`, but **only for scalars**. That matters and is not fussiness: `=` on two
  equal 500-element vectors measured 167x slower than `identical?`, so an
  unconditional fallback would reintroduce exactly the cost this exists to avoid.
  Scalars need the fallback because they are commonly rebuilt rather than shared —
  `(count members)` boxes to a fresh `Long` outside the cache range, and `(str \"m-\" u)`
  is a fresh String every render. Without it, any fragment reading a count would never
  prune.

  Collections therefore rely on `identical?` alone. A collection that is rebuilt
  each read compares as changed and re-renders — wasteful, never wrong."
  [a b]
  (or (identical? a b)
      (and (or (number? a) (string? a) (keyword? a) (boolean? a))
           (= a b))))

(defn unchanged?
  "Whether every topic in `reads` still returns what it returned before.

  `reads` is `{topic [{:value v :read-fn f} ...]}` from a previous render — a vector
  because one fragment may read one topic several times. True means no fragment
  depending on these topics can have changed, so nothing needs rendering.

  Optional `read-fns` is `{topic (fn [] v)}`, overriding the recorded reader for that
  topic. Rarely needed — it exists for a caller reconstructing state from outside a
  render.

  ## Why this is the whole optimisation

  A fragment's output is a pure function of the values it read through `watch`. So if
  every read is unchanged, the HTML is unchanged — without producing it. That turns a
  no-op hint from a full component render into a handful of pointer comparisons.

  It matters because hints carry no payload and are meant to be published liberally;
  a design that punishes speculative hints undermines its own transport. Measured on a
  500-member roster: re-rendering to discover a no-op cost 45.9ms across 50 viewers,
  against 0.01ms for the old engine's `identical?` pruning. This closes that gap
  rather than accepting it.

  ## The reader comes from the render

  `watch` records the `read-fn` the component passed it, so re-reading uses the same
  function the component used. It therefore cannot disagree with the component — which
  an application-supplied table of readers could, and did: that table was a second
  place to state every dependency, working directly against the one thing `watch`
  exists to fix.

  ## The stale-closure hazard

  A recorded reader must re-read current state when called. That is what the natural
  form does:

      (watch [:presence u] #(online? ch u))          ; re-reads — correct
      (watch [:presence u] #(contains? @presence u)) ; derefs inside — correct

  A reader closing over an already-dereferenced value does not:

      (let [snap @presence]
        (watch [:presence u] #(contains? snap u)))   ; WRONG — frozen at first render

  That form reports \"unchanged\" forever and the fragment never updates again. It is
  the dangerous direction: a false positive, not a false negative. The safe forms are
  what one writes without thinking about it, and every `watch` call in the example
  apps is one of them — but the failure is silent, so it is worth knowing.

  False negatives remain possible and harmless: a collection rebuilt on each read
  compares as changed and re-renders."
  ([reads] (unchanged? reads nil))
  ([reads read-fns]
   (every? (fn [[topic entries]]
             (every? (fn [{:keys [value read-fn]}]
                       (if-let [f (or (get read-fns topic) read-fn)]
                         (same? value (f))
                         ;; A topic with no reader cannot be verified, so assume it
                         ;; changed.
                         false))
                     entries))
           reads)))

(defn- node-id
  "The `:id` a hiccup node declares for itself, or nil.

  Three lines rather than a dependency: this namespace requires nothing, which is what
  keeps it usable without the rest of `darkstar`."
  [node]
  (when (and (vector? node) (map? (second node)))
    (:id (second node))))

(defn- assert-id!
  "Throws unless `tree`'s root element carries `:id` equal to `id`.

  ## Why this is checked rather than generated

  The obvious alternative is for `fragment` to *write* the id into the element. That is
  the wrong direction, and re-creates the defect this whole design removed: five of
  seven live-children bugs in this project were a patch target that had been *derived*
  drifting from the element it was supposed to hit. An id the author writes at both
  sites cannot drift silently — and now cannot drift at all, because this compares
  them.

  So the duplication is load-bearing, and this makes it safe rather than merely
  conventional.

  ## What is allowed through

  A `nil` body, because a fragment that renders nothing is legitimate — a row for a
  member who just left. Anything that is not a vector with an attribute map is
  rejected outright: it cannot carry an id, so no patch could ever target it, and the
  failure would otherwise appear much later as a `PatchElementsNoTargetsFound` in a
  browser console."
  [id tree]
  (when (some? tree)
    (let [found (node-id tree)]
      (when-not (= id found)
        (throw (ex-info
                (if (nil? found)
                  (str "Fragment " (pr-str id) " rendered an element with no :id, so "
                       "no patch could target it. Give the root element "
                       "{:id " (pr-str id) "}.")
                  (str "Fragment " (pr-str id) " rendered an element whose :id is "
                       (pr-str found) ". The fragment id and the element id must "
                       "match, or patches for this fragment will miss it."))
                {:fragment id :element-id found :tree tree})))))
  tree)

(defn- merge-fragments
  "Merges fragment maps, throwing if an id appears in both.

  ## Why this cannot be a plain `merge`

  It was, and that was a silent data-loss bug. Two fragments sharing an id — a roster
  keyed by something not actually unique, say — collapsed into one entry, the later
  one winning. No error: one row simply never appeared and never updated, and the
  render that produced it looked completely reasonable.

  This was once caught by an older engine's `validate`, which reported it as
  `:duplicate-id`, and losing it when moving to fragments was a regression — a worse one
  than the mismatched-id case it was traded for. A mismatch means patches miss; a
  duplicate means an element is gone from the tree entirely.

  Ids are the patch targets, so they have to be unique for the same reason DOM ids do.
  Failing at the callsite says which id and points at the render; discovering it in a
  browser does not."
  [a b]
  (when-let [dup (first (filter (partial contains? a) (keys b)))]
    (throw (ex-info
            (str "Duplicate fragment id " (pr-str dup) ". Two fragments cannot share "
                 "an id — it is the patch target, so one would shadow the other and "
                 "one element would be lost from the render. If this is a collection, "
                 "key the id by something unique.")
            {:duplicate-id dup})))
  (merge a b))

(defn fragment
  "Marks `body-fn`'s output as a patchable fragment identified by `id`, recording the
  topics its body read.

  The rendered root element **must** carry `{:id id}`. That is checked here, not left
  to the author — see `assert-id!` for why it is verified rather than generated. The
  usual shape is one `*-id` function called from both sites:

      (defn member-id [u] (str \"member-\" u))

      (fragment (member-id username)
        (fn [] [:li {:id (member-id username)} …]))

  Nested fragments are kept: an inner fragment is recorded in its own right *and* its
  topics bubble up, so a hint reaches both the narrow fragment and any fragment
  containing it. A caller that patches the narrowest match gets minimal updates; one
  that patches the outermost still gets correctness."
  [id body-fn]
  (if-not *recording*
    ;; Checked outside a recording too. A component called at a REPL or in a test is
    ;; where a mismatched id is cheapest to find, and skipping the check there would
    ;; mean the tests that exercise components could not catch it.
    (assert-id! id (body-fn))
    (let [inner (volatile! {:topics #{} :fragments {} :reads {}})
          tree (assert-id! id (binding [*recording* inner] (body-fn)))]
      (vswap! *recording*
              (fn [acc]
                (-> acc
                    ;; Inner fragments first, so this fragment's own entry cannot be
                    ;; clobbered by the merge.
                    (update :fragments merge-fragments (:fragments @inner))
                    (update :fragments merge-fragments
                            {id {:topics (:topics @inner)
                                 :tree tree
                                 ;; This fragment's own reads, for `unchanged?`, and
                                 ;; its `body-fn`, so it can be re-rendered ALONE
                                 ;; rather than by re-rendering its parent.
                                 :reads (:reads @inner)
                                 :body-fn body-fn
                                 ;; What this fragment contained. A caller re-rendering
                                 ;; it needs to know which nested fragments to drop if
                                 ;; the new render no longer produces them.
                                 :fragments (:fragments @inner)}})
                    (update :topics into (:topics @inner))
                    (update :reads merge (:reads @inner)))))
      tree)))

(defn- assert-realised!
  "Throws if `tree` contains an unrealised lazy seq.

  See the namespace docstring: a lazy seq inside a render escapes the recording
  binding, so its `watch` calls are never seen and the fragments it produced never
  update. The first render looks correct, which is what makes it worth failing
  loudly here rather than leaving a silently stale screen."
  [tree]
  (letfn [(walk [node path]
            (cond
              (and (instance? clojure.lang.IPending node)
                   (not (realized? node)))
              (throw (ex-info
                      (str "Unrealised lazy sequence in a recording render at "
                           (pr-str path) ". A lazy seq escapes the recording, so "
                           "any `watch` inside it is never seen and its fragments "
                           "never update. Use `mapv` rather than `for`, or wrap in "
                           "`doall`.")
                      {:path path}))
              (vector? node) (doseq [[i n] (map-indexed vector node)]
                               (walk n (conj path i)))
              (seq? node) (doseq [[i n] (map-indexed vector node)]
                            (walk n (conj path i)))
              :else nil))]
    (walk tree [])
    tree))

(defn render-recording
  "Renders `render-fn` and returns `{:tree :topics :fragments :reads}`.

  - `:topics`  every topic read anywhere, which is the component's subscription set
  - `:fragments` `{id {:topics :tree :reads :body-fn}}`, one entry per `fragment`
  - `:reads`   `{topic value}` for every read, for `unchanged?`
  - `:tree`    the rendered output, unchanged

  Throws on an unrealised lazy seq — see `assert-realised!`."
  [render-fn]
  (let [acc (volatile! {:topics #{} :fragments {} :reads {}})
        tree (binding [*recording* acc] (render-fn))]
    (assert-realised! tree)
    (assoc @acc :tree tree)))

(defn re-render
  "Re-renders a single recorded fragment, returning `{:tree :topics :reads}`.

  This is the other half of the performance story, and the more structural half. A
  presence change to one roster row should cost one row, but `refresh!` originally
  re-rendered the whole component to obtain it — so a 500-member roster paid 500 rows
  to update one.

  A fragment's `body-fn` is a closure over exactly what it needs, so it can be called
  on its own. Recording during that call keeps its topics and reads current, which is
  what lets a fragment's dependencies change over time without a full render.

  Nested fragments inside `body-fn` are re-recorded and returned in `:fragments`, so a
  caller can refresh its stored map for the subtree it just rendered."
  [{:keys [body-fn] :as _fragment} id]
  (let [acc (volatile! {:topics #{} :fragments {} :reads {}})
        tree (assert-id! id (binding [*recording* acc] (body-fn)))]
    (assert-realised! tree)
    (assoc @acc :tree tree)))

(defn fragments-for-topic
  "Region ids whose recorded topics include `topic`.

  A hint names a topic; this answers which fragments have to re-render. Returned
  narrowest-first — by number of topics read, ascending — so a caller that wants
  minimal updates can take the first and a caller that wants safety can take them
  all."
  [fragments topic]
  (->> fragments
       (filter (fn [[_ {:keys [topics]}]] (contains? topics topic)))
       (sort-by (fn [[_ {:keys [topics]}]] (count topics)))
       (mapv key)))

(defn innermost-independent
  "Of the fragment ids in `ids`, those that do not *contain* another id in `ids`.

  ## Why this is not just \"the narrowest match\"

  A hint can dirty several fragments at once, and they fall into two very different
  relationships:

  - **ancestor/descendant.** A containing fragment inherits its children's topics, so
    a hint for a child also matches the parent. Patching both sends the change twice
    and the outer patch replaces the inner element that was just updated — the bug the
    old engine showed as duplicated list items.
  - **siblings.** Two unrelated fragments can read the same topic. A roster and a
    message list both reading `[:channel id]` is the ordinary case, not a corner one.
    Here BOTH must be patched.

  Taking the single narrowest match handles the first and silently breaks the second:
  the sibling that loses the tie never updates. Found in a browser, where a member
  joining left the other viewer's roster stale because `messages` and `roster` had the
  same topic count and `messages` sorted first.

  So the rule is containment, not count: drop a dirty fragment when a dirty
  *descendant* of it is also present, and keep everything else. The survivors are
  pairwise independent — no one contains another — so patching all of them sends each
  change exactly once."
  [fragments ids]
  (let [contains? (fn [outer inner]
                    (and (not= outer inner)
                         (clojure.core/contains?
                          (:fragments (get fragments outer)) inner)))]
    (vec (remove (fn [id] (some #(contains? id %) ids)) ids))))

(defn subscriptions
  "The topic set from a recording, shaped for `pubsub/subscribe-context!`.

  This is what replaces a hand-written `:subscribe`: the topics are the ones the
  render read, so they are correct by construction."
  [{:keys [topics]}]
  (vec topics))
