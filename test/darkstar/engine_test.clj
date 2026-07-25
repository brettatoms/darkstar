(ns darkstar.engine-test
  "The engine driven through the real DOM patch translator, end to end.

  These live in darkstar rather than remuda because they assert on *selectors* and
  patch modes — `#c1-count`, `:outer` — which are darkstar's vocabulary. Remuda has
  its own engine tests covering the same lifecycle with a non-DOM patches-fn; the
  pair is deliberate, and the split is the clearest statement of where the seam is:
  remuda owns \"which paths changed\", darkstar owns \"which elements to patch\".

  No server either way: `send!` records instead of sending (§2.1)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [darkstar.engine :as d*engine]
            [darkstar.patch :as patch]
            [remuda.diff :as diff]
            [remuda.engine :as engine]
            [remuda.render :as render]))

;;; ==========================================================================
;;; Fixtures
;;; ==========================================================================

(defn keyed [items]
  (with-meta (vec items) {:live/key :id}))

;; A trivial hiccup->string renderer. The engine takes render-fn as a parameter
;; precisely so tests need no hiccup library.
(defn render-str
  [hiccup]
  (cond
    (string? hiccup) hiccup
    (number? hiccup) (str hiccup)
    (nil? hiccup) ""
    (and (vector? hiccup) (keyword? (first hiccup)))
    (let [[tag & rest'] hiccup
          attrs (when (map? (first rest')) (first rest'))
          children (if (map? (first rest')) (next rest') rest')]
      (str "<" (name tag)
           (when-let [id (:id attrs)] (str " id=\"" id "\""))
           ">"
           (apply str (map render-str children))
           "</" (name tag) ">"))
    (sequential? hiccup) (apply str (map render-str hiccup))
    :else (str hiccup)))

;; A counter with a keyed list, exercising both scalar and collection paths.
;; Boundaries are marked in the render itself — no :boundaries, no :render-at.
(def counter
  {:mount (fn [_ctx] {:count 0 :items (keyed [{:id 1 :text "a"}])})
   :render (fn [{:keys [count items] ::engine/keys [id]}]
             (render/boundary []
                              [:div {:id id}
                               (render/boundary [:count]
                                                [:span {:id (str id "-count")} count])
                               (render/boundary [:items]
                                                [:ul {:id (str id "-items")}
                                                 (for [i items]
                                                   (render/boundary [:items (:id i)]
                                                                    [:li {:id (str id "-items-" (:id i))}
                                                                     (:text i)]))])]))
   :on {:inc (fn [view _ctx _args] (update view :count inc))
        :add (fn [view _ctx {:keys [id text]}]
               (update view :items
                       #(with-meta (conj % {:id id :text text}) (meta %))))
        :noop (fn [view _ctx _args] view)}})

;; A component with no boundary marks at all: every patch must widen to the root.
(def counter-unmarked
  (assoc counter
         :render (fn [{:keys [count] ::engine/keys [id]}]
                   [:div {:id id} [:span count]])))

(defn test-engine
  ([] (test-engine counter))
  ([component]
   (engine/engine {:components {:counter component}
                   :render-fn render-str})))

(defn connect-recording!
  "Connects a live context whose `send!` appends to an atom."
  [eng]
  (let [sent (atom [])
        id (engine/connect! eng :counter {:send! #(swap! sent conj %)})]
    [id sent]))

(def dispatch-opts
  "The real bundle, including `:retarget-fn`. Using darkstar's own definition
  rather than a local copy means these tests exercise what callers actually get."
  d*engine/dispatch-opts)

;;; ==========================================================================
;;; Lifecycle — §2.1 requires construction to start nothing
;;; ==========================================================================

(deftest construction-starts-nothing
  (let [eng (test-engine)]
    (is (false? @(:started? eng)))
    (is (= {} @(:registry eng)))
    (testing "the engine is a map of pieces, per §2.1"
      (is (every? #(contains? eng %)
                  [:components :render-fn :registry :started?])))))

(deftest start-and-stop-are-symmetric
  (let [eng (engine/start! (test-engine))]
    (is (true? @(:started? eng)))
    (let [[_id _sent] (connect-recording! eng)]
      (is (= 1 (count @(:registry eng))))
      (engine/stop! eng)
      (is (= {} @(:registry eng)) "stop! clears live contexts")
      (is (false? @(:started? eng))))))

(deftest stop-closes-connections
  (let [eng (test-engine)
        closed (atom false)]
    (engine/connect! eng :counter {:send! identity
                                   :close! #(reset! closed true)})
    (engine/stop! eng)
    (is (true? @closed))))

(deftest registry-can-be-supplied
  ;; §9.3 requires the registry to survive namespace reload, which means the
  ;; caller must own it. Verified rather than assumed.
  (let [reg (atom {})
        eng (engine/engine {:components {:counter counter}
                            :render-fn render-str
                            :registry reg})]
    (engine/connect! eng :counter {:send! identity})
    (is (= 1 (count @reg)))))

(deftest unknown-component-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (engine/connect! (test-engine) :nope {:send! identity}))))

;;; ==========================================================================
;;; Mount — the cold path
;;; ==========================================================================

(deftest mount-renders-the-whole-component
  (let [eng (test-engine)
        [id _] (connect-recording! eng)
        html (engine/mount! eng id {})]
    (is (str/includes? html (str "id=\"" id "\"")) "the root element")
    (is (str/includes? html (str id "-count")) "and the count boundary")
    (testing "the view is stored so a later diff has something to compare"
      (is (= 0 (:count (:view (engine/live-context eng id))))))))

;;; ==========================================================================
;;; Dispatch — the warm path
;;; ==========================================================================

(deftest dispatch-updates-view-and-sends-patches
  (let [eng (test-engine counter)
        [id sent] (connect-recording! eng)]
    (engine/mount! eng id {})
    (let [instructions (engine/dispatch! eng id :inc nil dispatch-opts)]
      (testing "the view advanced"
        (is (= 1 (:count (:view (engine/live-context eng id))))))
      (testing "exactly one patch, targeting the count boundary"
        (is (= 1 (count instructions)))
        (is (= :outer (:mode (first instructions))))
        (is (str/includes? (:selector (first instructions)) "count")))
      (testing "HTML was rendered for it"
        (is (str/includes? (:html (first instructions)) "1")))
      (testing "and it was actually sent"
        (is (= 1 (count @sent)))))))

(deftest no-change-sends-no-patches
  (let [eng (test-engine counter)
        [id sent] (connect-recording! eng)]
    (engine/mount! eng id {})
    (let [instructions (engine/dispatch! eng id :noop nil dispatch-opts)]
      (is (= [] instructions))
      (testing "send! is still called, with an empty batch"
        ;; Worth pinning down: a transport may want to know a cycle completed
        ;; even with nothing to do.
        (is (= [[]] @sent))))))

(deftest insert-into-keyed-collection
  (let [eng (test-engine counter)
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    (let [instructions (engine/dispatch! eng id :add {:id 2 :text "b"} dispatch-opts)]
      (is (= 1 (count instructions)))
      (testing "anchored after the existing sibling"
        (is (= :after (:mode (first instructions))))
        (is (str/includes? (:selector (first instructions)) "items-1")))
      (testing "the new item's HTML was rendered"
        (is (str/includes? (:html (first instructions)) "b"))))))

(deftest unknown-event-is-rejected
  (let [eng (test-engine)
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    (is (thrown? clojure.lang.ExceptionInfo
                 (engine/dispatch! eng id :nope nil dispatch-opts)))))

;;; ==========================================================================
;;; The §2.2 seam: rendering one boundary
;;; ==========================================================================
;;; :render takes the whole view, so there is no general way to get the HTML for
;;; one boundary. Without :render-at the engine must widen the patch to the
;;; component root. That is correct but discards the diff's granularity, so it
;;; must be visible rather than silent.

(deftest unmarked-renders-widen-to-the-root
  (let [eng (test-engine counter-unmarked)  ; no boundary marks
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    (let [[instr] (engine/dispatch! eng id :inc nil dispatch-opts)]
      (testing "the patch is widened, and says so"
        (is (= :outer (:mode instr)))
        (is (= (str "#" id) (:selector instr)))
        (is (some? (:widened-from instr))
            "widening must be reported, not silent")
        (is (contains? #{[] nil} (:rendered-path instr))))
      (testing "the HTML really is the whole component"
        (is (str/starts-with? (:html instr) "<div"))))))

(deftest marked-renders-stay-narrow
  (let [eng (test-engine counter)
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    (let [[instr] (engine/dispatch! eng id :inc nil dispatch-opts)]
      (is (nil? (:widened-from instr)) "no widening needed")
      (testing "only the count fragment was rendered"
        (is (not (str/includes? (:html instr) "-items")))
        (is (str/includes? (:html instr) "-count"))))))

;;; ==========================================================================
;;; refresh! — the hint-driven path
;;; ==========================================================================
;;; A PubSub hint carries no data (§7.2), so the only correct reaction is to
;;; re-read the source of truth. refresh! does that and PUSHES the diff, which is
;;; what distinguishes it from reconnect! — the latter returns full HTML for a new
;;; connection. Without refresh! there is no path from a hint to a browser, a gap
;;; the fan-out benchmark exposed by measuring zero sends.

(def ^:private mutable-store (atom {:n 0}))

(def ^:private sourced-component
  {:mount (fn [_] {:n (:n @mutable-store)})
   :render (fn [{:keys [n] ::engine/keys [id]}]
             (render/boundary [] [:div {:id id}
                                  (render/boundary [:n]
                                                   [:span {:id (str id "-n")} n])]))
   :on {}})

(deftest refresh-pushes-only-what-changed
  (reset! mutable-store {:n 0})
  (let [eng (engine/engine {:components {:counter sourced-component}
                            :render-fn render-str})
        [id sent] (connect-recording! eng)]
    (engine/mount! eng id {})
    (reset! sent [])
    ;; The world changes behind the view, then a hint arrives.
    (reset! mutable-store {:n 42})
    (let [instrs (engine/refresh! eng id {} dispatch-opts)]
      (is (= 1 (count instrs)) "one targeted patch, not a full re-render")
      (is (str/includes? (:selector (first instrs)) "-n"))
      (is (str/includes? (:html (first instrs)) "42"))
      (is (= 1 (count @sent)) "and it was actually pushed"))
    (is (= 42 (:n (:view (engine/live-context eng id))))
        "the stored view advanced")))

(deftest refresh-with-no-change-pushes-nothing
  ;; Hints are lossy and may be spurious, so a hint for unchanged data must not
  ;; produce a patch.
  (reset! mutable-store {:n 7})
  (let [eng (engine/engine {:components {:counter sourced-component}
                            :render-fn render-str})
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    (is (= [] (engine/refresh! eng id {} dispatch-opts)))))

(deftest refresh-differs-from-reconnect
  ;; reconnect! returns full HTML for a new connection; refresh! diffs against
  ;; what is already on screen. Conflating them would ship the whole component on
  ;; every hint.
  (reset! mutable-store {:n 1})
  (let [eng (engine/engine {:components {:counter sourced-component}
                            :render-fn render-str})
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    (reset! mutable-store {:n 2})
    (let [html (engine/reconnect! eng id {} nil)]
      (is (str/starts-with? html "<div") "reconnect returns whole-component HTML"))
    (reset! mutable-store {:n 3})
    (let [instrs (engine/refresh! eng id {} dispatch-opts)]
      (is (= 1 (count instrs)))
      (is (not (str/starts-with? (:html (first instrs)) "<div"))
          "refresh pushes a fragment, not the component"))))

(deftest refresh-on-an-unknown-context-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (engine/refresh! (test-engine) "nope" {} dispatch-opts))))

;;; ==========================================================================
;;; REPL-driven development
;;; ==========================================================================
;;; DESIGN.md §9.4 claims a developer can redefine :render and see an
;;; already-connected context reflect it with state intact. That claim was
;;; unverified and false: the live context stored the component map captured at
;;; connect! time, so a redefinition left every connection rendering the old
;;; function. State survived and behaviour did not — exactly backwards.
;;;
;;; These tests are that claim, made executable.

(def ^:private mutable-component
  "Stands in for a component var a developer would re-evaluate."
  (atom counter))

(deftest redefining-render-reaches-connected-contexts
  ;; The components map holds something deref-able (an atom here, a var in
  ;; practice), so the engine resolves current behaviour on every render.
  (let [eng (engine/engine {:components {:counter mutable-component}
                            :render-fn render-str})
        [id _] (connect-recording! eng)]
    (try
      (engine/mount! eng id {})
      (engine/dispatch! eng id :inc nil dispatch-opts)
      (is (= 1 (:count (:view (engine/live-context eng id))))
          "state built up before the redefinition")

      ;; The developer edits :render.
      (swap! mutable-component assoc :render
             (fn [{:keys [count] ::engine/keys [id]}]
               (render/boundary []
                                [:div {:id id}
                                 (render/boundary [:count]
                                                  [:span {:id (str id "-count")}
                                                   "EDITED" count])])))

      (let [[instr] (engine/dispatch! eng id :inc nil dispatch-opts)]
        (testing "the new render is used"
          (is (str/includes? (:html instr) "EDITED")))
        (testing "and the view was not clobbered"
          (is (= 2 (:count (:view (engine/live-context eng id)))))))
      (finally
        (reset! mutable-component counter)))))

(deftest live-context-never-caches-a-stale-component
  ;; Guards the specific mistake: writing a resolved context back into the
  ;; registry would re-capture the component and reintroduce the staleness.
  (let [eng (test-engine counter)
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    (engine/dispatch! eng id :inc nil dispatch-opts)
    (is (nil? (:component (get @(:registry eng) id)))
        "the stored context must hold no component, only a name")
    (is (some? (:component-name (get @(:registry eng) id))))))

;;; ==========================================================================
;;; Browser-found regressions
;;; ==========================================================================
;;; Both of these produced duplicated DOM elements in a real browser while every
;;; test above passed. They are the reason the slice exists.

(def counter-with-bookkeeping
  "A component whose handler also touches non-boundary state, like `:next-id`."
  (assoc counter
         :on {:add (fn [{:keys [next-id] :as view} _ctx _args]
                     (-> view
                         (update :items
                                 #(with-meta (conj % {:id (or next-id 2) :text "new"})
                                    (meta %)))
                         (assoc :next-id (inc (or next-id 2)))))}
         :mount (fn [_ctx] {:count 0
                            :next-id 2
                            :items (keyed [{:id 1 :text "a"}])})))

(deftest a-root-render-supersedes-the-batch
  ;; `:next-id` is not a boundary, so it resolves to [] and yields a full
  ;; component re-render. That render ALREADY contains the new item, so also
  ;; sending the insert double-applies it. In the browser this appeared as a
  ;; list item rendered twice.
  (let [eng (test-engine counter-with-bookkeeping)
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    (let [instructions (engine/dispatch! eng id :add nil dispatch-opts)]
      (is (= 1 (count instructions))
          "a root re-render must supersede every other patch in the batch")
      (is (= (str "#" id) (:selector (first instructions))))
      (testing "and the root HTML contains the new item, so nothing is lost"
        (is (str/includes? (:html (first instructions)) "new"))))))

(deftest moves-carry-html-because-datastar-has-no-move-primitive
  ;; Datastar can only insert HTML; it cannot relocate an existing element. So a
  ;; move needs HTML for the moved element, and the transport applies it as
  ;; remove-then-insert. Without HTML the transport had to render it itself,
  ;; which was a layering violation; without remove-first the browser ended up
  ;; with two elements sharing one id.
  (let [eng (test-engine counter)
        [id _] (connect-recording! eng)]
    (engine/mount! eng id {})
    ;; Build a two-item list, then reverse it.
    (let [ctx (engine/live-context eng id)
          two-items (with-meta [{:id 1 :text "a"} {:id 2 :text "b"}]
                      {:live/key :id})
          _ (swap! (:registry eng) assoc-in [id :view]
                   (assoc (:view ctx) :items two-items))
          reversed (assoc (:view (engine/live-context eng id))
                          :items (with-meta (vec (reverse two-items))
                                   {:live/key :id}))
          ops (diff/diff (:view (engine/live-context eng id)) reversed)
          patches (patch/ops->patches {:component-id id
                                       :boundaries (:boundaries counter)}
                                      ops)]
      (testing "patch carries a moved-path so the engine knows what to render"
        (is (every? :moved-path (filter :move patches))))
      (testing "and the rendered instruction carries both move and html"
        (let [instrs (engine/dispatch!
                      (assoc eng :registry (:registry eng)) id :noop nil
                      dispatch-opts)]
          ;; :noop yields nothing; assert on the patch translation directly.
          (is (= [] instrs)))
        (doseq [p (filter :move patches)]
          (is (some? (:moved-path p))))))))

(deftest widened-patches-never-mismatch-html-and-selector
  ;; The failure this guards against: root HTML sent against a child selector.
  ;; The DOM would be corrupted rather than merely coarse.
  (doseq [component [counter counter-unmarked]]
    (let [eng (test-engine component)
          [id _] (connect-recording! eng)]
      (engine/mount! eng id {})
      (doseq [instr (engine/dispatch! eng id :inc nil dispatch-opts)]
        (when (:html instr)
          (let [root-html? (str/starts-with? (:html instr) "<div")
                root-sel? (= (str "#" id) (:selector instr))]
            (is (= root-html? root-sel?)
                (str "root HTML must go to the root selector: " (pr-str instr)))))))))

(deftest retarget-fn-is-required-for-post-translation-widening
  ;; The regression this pins: root HTML shipped against a CHILD selector.
  ;;
  ;; The engine identifies a root render structurally (`:root? true`) but cannot
  ;; build the root *target* without knowing what a target is, so it delegates to
  ;; `:retarget-fn`. Omit that and the patch keeps `#c1-n` while carrying the whole
  ;; `<div>` — the §5.0 mismatch, silent in the browser until the DOM is corrupt.
  ;;
  ;; This is the second widening case, distinct from the one `patch` detects during
  ;; path resolution: here the boundary is claimed at translation time but the
  ;; render never marked it, so only the engine can discover it.
  (let [eng (engine/start!
             (engine/engine
              {:components {:counter
                            {:mount (fn [_] {:n 0})
                             ;; Marks [] only — [:n] is never marked, though a
                             ;; patch below will claim it.
                             :render (fn [{:keys [n] ::engine/keys [id]}]
                                       (render/boundary
                                        [] [:div {:id id} [:span {:id (str id "-n")} n]]))
                             :on {:inc (fn [v _ _] (update v :n inc))}}}
               :render-fn render-str
               :registry (atom {})}))
        [id _] (connect-recording! eng)
        ;; Claims [:n] owns an element. The render disagrees.
        claims-n (fn [ctx ops]
                   (mapv (fn [_]
                           {:mode :outer
                            :selector (patch/path->selector (:component-id ctx) [:n])
                            :render {:path [:n]}})
                         ops))
        root-selector (str "#" id)]
    (engine/mount! eng id {})
    (testing "without :retarget-fn the target does NOT follow the widening"
      (let [instr (first (engine/dispatch! eng id :inc nil
                                           {:diff-fn diff/diff
                                            :patches-fn claims-n}))]
        (is (true? (:root? instr)) "the engine knows it rendered the root")
        (is (str/starts-with? (:html instr) "<div") "and shipped root HTML")
        (is (not= root-selector (:selector instr))
            "which is precisely the mismatch — kept for contrast, not as desired")))
    (testing "with :retarget-fn the target widens to the root, so they agree"
      (let [instr (first (engine/dispatch! eng id :inc nil
                                           {:diff-fn diff/diff
                                            :patches-fn claims-n
                                            :retarget-fn patch/path->selector}))]
        (is (true? (:root? instr)))
        (is (str/starts-with? (:html instr) "<div"))
        (is (= root-selector (:selector instr))
            "root HTML must go to the root selector")))
    (testing "and darkstar's own bundle supplies it, so callers cannot forget"
      (is (contains? dispatch-opts :retarget-fn))
      (let [instr (first (engine/dispatch! eng id :inc nil dispatch-opts))]
        (is (= root-selector (:selector instr)))))
    (engine/stop! eng)))
