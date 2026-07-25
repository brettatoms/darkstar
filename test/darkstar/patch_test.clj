(ns darkstar.patch-test
  "Tests for diff-op -> datastar-patch translation. See "
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [remuda.diff :as diff]
            [darkstar.patch :as patch]))

(defn keyed [items]
  (with-meta (vec items) {:live/key :id}))

;; A component declares which view paths own DOM elements. :* matches any key of
;; a keyed collection. Paths finer than a boundary resolve up to it.
(def ctx {:component-id "c1"
          :boundaries #{[] [:title] [:count] [:a] [:b] [:v] [:i] [:items]
                        [:items :*] [:i :*] [:v :*]}})

;;; ==========================================================================
;;; Id derivation
;;; ==========================================================================

(deftest path->id-basics
  (testing "component id alone"
    (is (= "c1" (patch/path->id "c1" []))))
  (testing "a scalar field"
    (is (= "c1-count" (patch/path->id "c1" [:count]))))
  (testing "nested path"
    (is (= "c1-a-b-c" (patch/path->id "c1" [:a :b :c]))))
  (testing "keyed item, addressed by key not index"
    (is (= "c1-items-7" (patch/path->id "c1" [:items 7]))))
  (testing "field of a keyed item"
    (is (= "c1-items-7-label" (patch/path->id "c1" [:items 7 :label])))))

(deftest path->id-produces-valid-css-selectors
  (testing "namespaced keywords do not leak : or / into the id"
    ;; A raw namespaced keyword would give "#c1-my.ns/key", which is not a
    ;; usable id selector without escaping.
    (let [id (patch/path->id "c1" [:my.ns/key])]
      (is (not (str/includes? id "/")))
      (is (not (str/includes? id ":")))))
  (testing "string keys pass through"
    (is (= "c1-foo" (patch/path->id "c1" ["foo"]))))
  (testing "selector is the id with a # prefix"
    (is (= "#c1-count" (patch/path->selector "c1" [:count])))))

;;; ==========================================================================
;;; Mode mapping
;;; ==========================================================================

(deftest scalar-change-becomes-outer
  (let [ops (diff/diff {:count 1} {:count 2})
        [p] (patch/ops->patches ctx ops)]
    (is (= :outer (:mode p)))
    (is (= "#c1-count" (:selector p)))
    (testing "carries what to render, not rendered HTML"
      (is (= {:path [:count]} (:render p))))))

(deftest removal-becomes-remove
  (testing "a dropped map key"
    (let [[p] (patch/ops->patches ctx (diff/diff {:a 1 :b 2} {:a 1}))]
      (is (= :remove (:mode p)))
      (is (= "#c1-b" (:selector p)))))
  (testing "a deleted keyed item targets the item, not the container"
    (let [ops (diff/diff {:items (keyed [{:id 1} {:id 2}])}
                         {:items (keyed [{:id 1}])})
          [p] (patch/ops->patches ctx ops)]
      (is (= :remove (:mode p)))
      (is (= "#c1-items-2" (:selector p))))))

(deftest inserts-map-to-positional-modes
  (testing "prepend anchors before the following sibling"
    (let [ops (diff/diff {:items (keyed [{:id 1}])}
                         {:items (keyed [{:id 0} {:id 1}])})
          [p] (patch/ops->patches ctx ops)]
      (is (= :before (:mode p)))
      (is (= "#c1-items-1" (:selector p))
          "anchor selector must name the sibling, not the new item")))
  (testing "append anchors after the preceding sibling"
    (let [ops (diff/diff {:items (keyed [{:id 1}])}
                         {:items (keyed [{:id 1} {:id 2}])})
          [p] (patch/ops->patches ctx ops)]
      (is (= :after (:mode p)))
      (is (= "#c1-items-1" (:selector p)))))
  (testing "insert into an empty collection appends to the container"
    (let [ops (diff/diff {:items (keyed [])}
                         {:items (keyed [{:id 1}])})
          [p] (patch/ops->patches ctx ops)]
      (is (= :append (:mode p)))
      (is (= "#c1-items" (:selector p))
          "with no sibling to anchor to, the container is the target"))))

(deftest moves-carry-the-element-being-moved
  (let [ops (diff/diff {:items (keyed [{:id 1} {:id 2}])}
                       {:items (keyed [{:id 2} {:id 1}])})
        patches (patch/ops->patches ctx ops)]
    (is (every? #(contains? #{:before :after :append} (:mode %)) patches))
    (testing "a move identifies the moved element, and renders nothing"
      (doseq [p patches]
        (is (some? (:move p)))
        (is (nil? (:render p))
            "a move relocates an existing element; it must not re-render")))))

;;; ==========================================================================
;;; Order preservation
;;; ==========================================================================

(deftest patch-order-matches-op-order
  ;; Move ops are not commutative and their anchors assume earlier ops applied.
  ;; Reordering during translation would silently corrupt the result.
  (let [ops (diff/diff {:items (keyed [{:id 1} {:id 2} {:id 3}])}
                       {:items (keyed [{:id 3} {:id 2} {:id 1}])})
        patches (patch/ops->patches ctx ops)]
    (is (= (count ops) (count patches)))
    (is (= (mapv :key ops)
           (mapv (fn [p] (-> (:move p) (str/split #"-") last parse-long))
                 patches))
        "nth patch must correspond to nth op")))

;;; ==========================================================================
;;; Boundary resolution
;;; ==========================================================================
;;; The correction found in phase 2: not every view path owns a
;;; DOM element, so a path must resolve to its nearest declared boundary. The
;;; first implementation derived selectors straight from paths and produced
;;; selectors for text nodes, which match nothing and fail silently.

(deftest paths-resolve-to-their-owning-element
  (testing "a field of a keyed item resolves to the item"
    (is (= [:items 2]
           (patch/resolve-boundary (:boundaries ctx) [:items 2 :text]))))
  (testing "a declared path resolves to itself"
    (is (= [:title] (patch/resolve-boundary (:boundaries ctx) [:title]))))
  (testing "a deeply nested undeclared path resolves up to the nearest ancestor"
    (is (= [:title]
           (patch/resolve-boundary (:boundaries ctx) [:title :a :b :c]))))
  (testing "an entirely undeclared path falls back to the component root"
    (is (= [] (patch/resolve-boundary (:boundaries ctx) [:nope :nope])))))

(deftest selectors-name-elements-that-actually-render
  ;; The regression that motivated boundary resolution. A render function only
  ;; emits ids for its boundaries, so every selector a patch produces must be
  ;; one of those ids — otherwise the patch targets nothing.
  (let [view {:title "Todos"
              :items (keyed [{:id 1 :text "a"} {:id 2 :text "b"}])}
        ;; ids this component's :render would actually emit
        rendered-ids #{"c1" "c1-title" "c1-items" "c1-items-1" "c1-items-2"}
        scenarios [[view (assoc-in view [:items 1 :text] "b!")]
                   [view (assoc view :title "Done")]
                   [view (assoc-in view [:items 0 :text] "a!")]]]
    (doseq [[old new] scenarios
            p (patch/ops->patches ctx (diff/diff old new))]
      (let [id (subs (:selector p) 1)]
        (is (contains? rendered-ids id)
            (str "selector #" id " names no rendered element; patch " (pr-str p)))))))

;;; ==========================================================================
;;; Render targets
;;; ==========================================================================

(deftest render-targets-are-deduplicated
  (testing "two changed fields of one item resolve to one render of that item"
    (let [ops (diff/diff {:items (keyed [{:id 1 :a 1 :b 1}])}
                         {:items (keyed [{:id 1 :a 2 :b 2}])})
          patches (patch/ops->patches ctx ops)
          targets (patch/render-targets patches)]
      (is (= 2 (count ops)) "two fields changed")
      (is (= 1 (count patches))
          "...but both resolve to the item boundary, so one patch")
      (is (= [[:items 1]] targets))))
  (testing "moves contribute no render targets"
    (let [ops (diff/diff {:items (keyed [{:id 1} {:id 2}])}
                         {:items (keyed [{:id 2} {:id 1}])})
          targets (patch/render-targets (patch/ops->patches ctx ops))]
      (is (empty? targets)))))

;;; ==========================================================================
;;; Every op the differ can emit must translate
;;; ==========================================================================
;;;  made this a design constraint: nothing the diff engine emits
;;; may be unshippable. Asserted here rather than assumed.

(deftest every-op-shape-translates
  (let [scenarios [;; scalars and maps
                   [{:a 1} {:a 2}]
                   [{:a 1} {:a 1 :b 2}]
                   [{:a 1 :b 2} {:a 1}]
                   [{:a {:b 1}} {:a {:b 2}}]
                   ;; plain vectors
                   [{:v [1 2]} {:v [1 2 3]}]
                   [{:v [1 2 3]} {:v [1 2]}]
                   ;; keyed: insert, delete, update, move, and combinations
                   [{:i (keyed [])} {:i (keyed [{:id 1}])}]
                   [{:i (keyed [{:id 1}])} {:i (keyed [])}]
                   [{:i (keyed [{:id 1}])} {:i (keyed [{:id 0} {:id 1}])}]
                   [{:i (keyed [{:id 1}])} {:i (keyed [{:id 1} {:id 2}])}]
                   [{:i (keyed [{:id 1 :n 1}])} {:i (keyed [{:id 1 :n 2}])}]
                   [{:i (keyed [{:id 1} {:id 2}])} {:i (keyed [{:id 2} {:id 1}])}]
                   [{:i (keyed [{:id 1} {:id 2} {:id 3}])}
                    {:i (keyed [{:id 3} {:id 9} {:id 1}])}]]
        valid-modes #{:outer :inner :remove :prepend :append :before :after :replace}]
    (doseq [[old new] scenarios]
      (let [ops (diff/diff old new)]
        (doseq [op ops]
          (let [p (patch/op->patch ctx op)]
            (is (contains? valid-modes (:mode p))
                (str "op " (pr-str op) " produced mode " (:mode p)))
            (is (string? (:selector p))
                (str "op " (pr-str op) " produced no selector"))
            (is (str/starts-with? (:selector p) "#")
                (str "selector must be an id selector: " (:selector p)))
            (testing "no selector may contain an unresolved anchor sentinel"
              (is (not (str/includes? (:selector p) "remuda"))
                  (str "sentinel leaked into selector: " (:selector p))))))))))
