(ns darkstar.patch
  "Translates diff ops (see `remuda.diff`) into datastar patch
  instructions.

  This is the seam between the data model and the DOM model, and the part most
  likely to be wrong. The diff engine says \"path
  `[:items 2]` changed\"; datastar needs a CSS selector and a patch mode.

  Deliberately dependency-free: this namespace emits *descriptions* of patches
  as plain maps, not calls into the datastar SDK. Three reasons.

  1. It stays testable with no server, no SSE connection, and no SDK on the
     classpath — the same property that made the diff engine cheap to verify.
  2. It keeps the transport seam narrow . The SDK's own seam is
     a four-method `SSEGenerator` protocol, so whatever applies these
     descriptions is the only part that needs to know about a server.
  3. Op order is part of the diff contract (move ops are not commutative), and a
     vector of descriptions preserves it where a side-effecting translation
     might not.

  Mode selection, mapping the op vocabulary onto datastar's eight modes:

    :replace / :assoc  -> outer   (re-render the owning element)
    :dissoc / :delete  -> remove
    :insert / :move    -> before | after | append

  Note that `:replace` and `:assoc` do not patch a *value*; they identify which
  element owns the changed path and re-render it. Datastar patches elements, so
  the unit of update is an element, never a bare value.

  ## Boundaries: not every path owns an element

  The load-bearing correction found while building this . The
  first version of this namespace assumed every view path maps to a DOM element,
  and derived a selector directly from the path. That is wrong: paths are finer
  grained than elements. Given

      [:li {:id \"c1-items-2\"} (:text item)]

  the path `[:items 2 :text]` addresses a *text node*, not an element, so
  `#c1-items-2-text` matches nothing and the patch silently fails.

  So a component must declare which paths own elements — its **boundaries** — and
  a changed path resolves to its nearest boundary ancestor. `[:items 2 :text]`
  resolves to `[:items 2]`, and that `<li>` is re-rendered whole.

  Boundaries are declared as a set of paths, where `:*` is a wildcard matching
  any key of a keyed collection:

      #{[] [:title] [:items] [:items :*]}

  This is also what keeps re-render granularity honest: the smallest thing that
  can be patched is the smallest thing declared as a boundary, and a component
  author controls that trade directly.")

;;; ==========================================================================
;;; Element identity
;;; ==========================================================================
;;; A patch needs a CSS selector, so every patchable region needs a stable DOM
;;; id derived from the view path. The scheme has to satisfy three constraints:
;;;
;;;   - deterministic, so server and client agree without coordination;
;;;   - derivable from a path alone, so the diff's output is enough;
;;;   - stable under reordering, so a moved item keeps its identity — which is
;;;     the whole point of keyed collections.
;;;
;;; Keyed collection items are addressed by KEY, never by index (the diff
;;; engine already emits key-based paths), which is what makes the third
;;; constraint hold.

(defn- segment->str
  [seg]
  (cond
    (keyword? seg) (if-let [ns' (namespace seg)]
                     (str ns' "_" (name seg))
                     (name seg))
    (string? seg) seg
    :else (str seg)))

(defn path->id
  "DOM id for the element owning `path`, within component `component-id`.

  Ids are joined with `-`; path segments are stringified. Keyword segments lose
  their leading colon and namespaces are flattened with `_`, since `:` and `/`
  are not usable in a CSS id selector without escaping."
  [component-id path]
  (->> (cons component-id path)
       (map segment->str)
       (interpose "-")
       (apply str)))

(defn path->selector
  "CSS id selector for the element owning `path`."
  [component-id path]
  (str "#" (path->id component-id path)))

;;; ==========================================================================
;;; Boundary resolution
;;; ==========================================================================

(defn- boundary-match?
  "True if `path` is declared in `boundaries`, directly or via a `:*` wildcard
  in its last position (matching any key of a keyed collection)."
  [boundaries path]
  (or (contains? boundaries path)
      (and (seq path)
           (contains? boundaries (conj (vec (butlast path)) :*)))))

(defn resolve-boundary
  "Nearest ancestor of `path` that owns a DOM element, per `boundaries`.

  Returns `[]` (the component root) when nothing more specific matches, which
  degrades to re-rendering the whole component — correct, just coarse.

  This exists because view paths are finer grained than DOM elements: a path may
  address a text node or an attribute value, neither of which is patchable. See
  the namespace docstring."
  [boundaries path]
  (loop [p (vec path)]
    (cond
      (boundary-match? boundaries p) p
      (empty? p) []
      :else (recur (vec (butlast p))))))

;;; ==========================================================================
;;; Op -> patch description
;;; ==========================================================================

(defn op->patch
  "Translates one diff op into a patch description.

  `ctx` is `{:component-id, :boundaries}`.

  Returns a map with `:mode` and `:selector` (the element to patch). Patches
  that need HTML carry `:render {:path ...}` — a marker naming *what* to render
  rather than the HTML itself, since rendering belongs to `:render`. Moves carry
  `:move` (the selector of the element to relocate) and never `:render`, because
  the element already exists.

  Changed paths are resolved to their nearest declared boundary, so the selector
  always names an element that exists. See the namespace docstring."
  [{:keys [component-id boundaries boundary-ids]} op]
  (let [{:keys [op path key before after]} op
        ;; A container-level op (insert/delete/move) addresses an ITEM; a
        ;; value-level op addresses the path itself.
        item-of (fn [k] (conj (vec path) k))
        ;; Prefer the id the RENDER emitted for this boundary; fall back to the
        ;; derived scheme only when no rendered id is known (hand-declared
        ;; boundary sets, and tests). Preferring the rendered id is what makes a
        ;; selector/element mismatch impossible.
        sel (fn [p]
              (if-let [rid (get boundary-ids (vec p))]
                (str "#" rid)
                (path->selector component-id p)))
        bnd (fn [p] (resolve-boundary boundaries p))]
    (case op
      ;; A changed value re-renders whichever element owns it. Without boundary
      ;; resolution this produced selectors for text nodes, which match nothing.
      (:replace :assoc)
      (let [target (bnd path)]
        (cond-> {:mode :outer
                 :selector (sel target)
                 :render {:path target}}
          ;; Resolution, not rendering, is where granularity is lost: a path no
          ;; boundary covers resolves to the component root. Recording it here
          ;; means callers can see the diff's precision was discarded, which is
          ;; otherwise invisible.
          (not= target (vec path)) (assoc :widened-from (sel path)
                                          :widened-to target)))

      ;; A removed key: if the key itself owned an element, remove it; otherwise
      ;; the owning element must be re-rendered without it.
      :dissoc
      (let [target (bnd path)]
        (if (= target (vec path))
          {:mode :remove :selector (sel target)}
          {:mode :outer :selector (sel target) :render {:path target}}))

      :delete
      {:mode :remove :selector (sel (item-of key))}

      :insert
      (let [target (bnd (item-of key))]
        (cond
          (= after :remuda.diff/end)
          {:mode :append :selector (sel path) :render {:path target}}

          (some? after)
          {:mode :after :selector (sel (item-of after)) :render {:path target}}

          (some? before)
          {:mode :before :selector (sel (item-of before)) :render {:path target}}

          ;; No anchor: the collection was empty, so append to the container.
          :else
          {:mode :append :selector (sel path) :render {:path target}}))

      ;; A move carries `:moved-path` as well as `:move`, because datastar has
      ;; no move primitive: the transport must re-insert the element's HTML at
      ;; the new position and rely on idiomorph matching by id. The renderer
      ;; needs a path to render, not just a selector.
      :move
      (let [moving (sel (item-of key))
            moved-path (bnd (item-of key))]
        (cond
          (= after :remuda.diff/end)
          {:mode :append :selector (sel path) :move moving :moved-path moved-path}

          (some? after)
          {:mode :after :selector (sel (item-of after)) :move moving
           :moved-path moved-path}

          :else
          {:mode :before :selector (sel (item-of before)) :move moving
           :moved-path moved-path})))))

(defn ops->patches
  "Translates diff ops into patch descriptions, preserving order.

  Order preservation is not incidental: move ops are not commutative and their
  anchors assume earlier ops have been applied (see `remuda.diff`).

  Consecutive duplicate patches are collapsed — two fields of one item both
  changing resolve to the same boundary and would otherwise re-render it twice."
  [ctx ops]
  (->> ops
       (mapv #(op->patch ctx %))
       ;; Collapse consecutive duplicates by ACTION, ignoring diagnostic keys:
       ;; two fields of one item resolve to the same boundary and would otherwise
       ;; re-render it twice. `:widened-from` differs per originating path, so
       ;; comparing whole maps would defeat the collapse.
       (reduce (fn [acc p]
                 (let [action #(select-keys % [:mode :selector :render :move :moved-path])]
                   (if (and (peek acc) (= (action p) (action (peek acc))))
                     acc
                     (conj acc p))))
               [])))

;;; ==========================================================================
;;; Container paths for rendering
;;; ==========================================================================

(defn render-targets
  "Distinct view paths that must be re-rendered to satisfy `patches`.

  A caller renders each of these once, then supplies the HTML. Deduplicated
  because several ops can touch one element — e.g. two fields of the same item
  both change, and the owning element only needs rendering once."
  [patches]
  (into []
        (comp (keep #(get-in % [:render :path]))
              (distinct))
        patches))
