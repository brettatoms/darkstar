(ns darkstar.watch-test
  "Tests for subscription discovery.

  The point of `watch` is that a component declares a dependency once instead of
  three times, so these tests assert on the thing that used to drift: whether the
  recorded subscription set matches what the render actually read."
  (:require [clojure.test :refer [deftest is testing]]
            [darkstar.watch :as w]))

(defn- row
  "A component in the intended style: one function, dependency inline."
  [presence username]
  (w/fragment (str "member-" username)
              (fn []
                (let [online? (w/watch [:presence username]
                                       #(contains? @presence username))]
                  [:li {:id (str "member-" username)}
                   username (if online? "online" "offline")]))))

(deftest topics-come-from-what-the-render-read
  (let [presence (atom #{"alice"})
        r (w/render-recording
           (fn [] [:ul (mapv #(row presence %) ["alice" "bob"])]))]
    (testing "nothing declared these; they were observed"
      (is (= #{[:presence "alice"] [:presence "bob"]} (:topics r))))
    (testing "and they are exactly what a subscription needs"
      (is (= #{[:presence "alice"] [:presence "bob"]}
             (set (w/subscriptions r)))))))

(deftest each-fragment-records-only-its-own-topics
  (let [presence (atom #{})
        r (w/render-recording
           (fn [] [:ul (mapv #(row presence %) ["alice" "bob"])]))
        fragments (:fragments r)]
    (is (= #{[:presence "alice"]} (:topics (get fragments "member-alice"))))
    (is (= #{[:presence "bob"]} (:topics (get fragments "member-bob"))))
    (testing "so a hint resolves to one row, not the whole list"
      (is (= ["member-bob"] (w/fragments-for-topic fragments [:presence "bob"]))))))

(deftest an-outer-fragment-inherits-its-childrens-topics
  ;; A hint must reach both the narrow fragment and anything containing it, so a
  ;; caller can choose granularity. Narrowest first.
  (let [presence (atom #{})
        r (w/render-recording
           (fn []
             (w/fragment "roster"
                         (fn [] [:ul {:id "roster"}
                                 (mapv #(row presence %) ["alice" "bob"])]))))
        fragments (:fragments r)]
    (is (= #{[:presence "alice"] [:presence "bob"]}
           (:topics (get fragments "roster"))))
    (testing "narrowest fragment first, so minimal updates are the default choice"
      (is (= ["member-bob" "roster"]
             (w/fragments-for-topic fragments [:presence "bob"]))))))

(deftest a-lazy-seq-in-a-render-throws
  ;; The hazard that makes this whole approach dangerous if unguarded: a lazy seq
  ;; escapes the recording binding, so its `watch` calls are never seen and its
  ;; fragments never update — while the FIRST render looks perfectly correct.
  ;;
  ;; Failing loudly is the whole point. Verified by writing it the wrong way.
  (let [presence (atom #{})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unrealised lazy sequence"
         (w/render-recording
          (fn [] [:ul (for [u ["a" "b"]] (row presence u))]))))
    (testing "and the same render with mapv is fine"
      (is (= #{[:presence "a"] [:presence "b"]}
             (:topics (w/render-recording
                       (fn [] [:ul (mapv #(row presence %) ["a" "b"])]))))))))

(deftest a-component-still-renders-outside-a-recording
  ;; `watch` outside a recording is just a read, so a component is callable in a
  ;; test or at a REPL with no engine. That property is what made the framework
  ;; version's components hard to poke at, and it should not be lost here.
  (let [presence (atom #{"alice"})]
    (is (false? (w/recording?)))
    (is (= [:li {:id "member-alice"} "alice" "online"]
           (row presence "alice")))))

(deftest fragments-with-no-watch-record-an-empty-topic-set
  ;; A purely static fragment is legitimate — it is patchable but never invalidated.
  ;; It must not accidentally inherit topics it did not read.
  (let [r (w/render-recording
           (fn [] (w/fragment "static"
                              (fn [] [:div {:id "static"} "nothing dynamic here"]))))]
    (is (= #{} (:topics (get (:fragments r) "static"))))
    (is (= [] (w/fragments-for-topic (:fragments r) [:presence "anyone"])))))

;;; ==========================================================================
;;; The fragment id must match the element id
;;; ==========================================================================
;;; Not a style rule. A fragment id IS the patch selector, so a mismatch means every
;;; patch for that fragment misses — surfacing much later as a
;;; `PatchElementsNoTargetsFound` in a browser console, with nothing pointing back to
;;; the render that caused it. Checking it here converts that into a stack trace at
;;; the callsite.
;;;
;;; Worth noting these were written after the check found two real instances of this
;;; in the fixtures above, both of which had looked fine.

(deftest a-fragment-whose-element-has-no-id-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"no :id, so no patch could target it"
       (w/render-recording
        (fn [] (w/fragment "roster" (fn [] [:ul [:li "no id on the ul"]])))))))

(deftest a-fragment-whose-element-has-the-wrong-id-throws
  ;; The more dangerous case: an id exists, so nothing looks obviously wrong, and the
  ;; element even appears in the DOM. Only the patches silently go nowhere.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"whose :id is \"roster\""
       (w/render-recording
        (fn [] (w/fragment "member-alice" (fn [] [:li {:id "roster"} "alice"])))))))

(deftest the-check-also-applies-outside-a-recording
  ;; A component called at a REPL or in a test is the cheapest place to find this, so
  ;; the check must not be conditional on being inside an engine.
  (is (false? (w/recording?)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"no :id"
       (w/fragment "x" (fn [] [:div "nope"])))))

(deftest a-fragment-that-renders-nothing-is-allowed
  ;; Legitimate: a row for a member who just left, a panel that is collapsed. There is
  ;; no element, so there is no id to disagree with — and a patch simply has nothing
  ;; to target, which is correct rather than broken.
  (let [r (w/render-recording (fn [] (w/fragment "gone" (fn [] nil))))]
    (is (nil? (:tree r)))
    (is (contains? (:fragments r) "gone"))))

(deftest one-fragment-reading-a-topic-twice-keeps-both-reads
  ;; FOUND BY PORTING AN APP. A fragment may legitimately read one topic more than
  ;; once: a message list reads `[:messages id]` for the messages and again for the
  ;; count of older ones. Keying `:reads` by topic alone made the second read overwrite
  ;; the first, so `unchanged?` compared the wrong value — and pruned a real change. A
  ;; new message produced NO patch, because the surviving read was a count that had not
  ;; moved.
  (let [msgs (atom [{:id 1}])
        older-count (atom 0)
        render #(w/fragment "list"
                            (fn []
                              (let [ms (w/watch [:messages] (fn [] @msgs))
                                    more (w/watch [:messages] (fn [] @older-count))]
                                [:ul {:id "list"} (count ms) "/" more])))
        r (w/render-recording render)
        reads (:reads (get (:fragments r) "list"))]
    (testing "both reads are recorded, not just the last"
      (is (= 2 (count (get reads [:messages])))))
    (testing "and a change to EITHER is detected"
      (is (true? (w/unchanged? reads)))
      (swap! msgs conj {:id 2})
      (is (false? (w/unchanged? reads))
          "the message list changed; keying by topic alone hid this behind the count"))))

(deftest two-fragments-sharing-an-id-throws
  ;; A REGRESSION TEST in the strict sense: the old design catches this
  ;; (`render/validate` reports `:duplicate-id`) and it silently broke when fragments
  ;; accumulated through a plain `merge`. One row was dropped from the tree with no
  ;; error — worse than a mismatched id, which at least leaves the element on the page.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Duplicate fragment id \"dup\""
       (w/render-recording
        (fn [] [:ul (mapv (fn [u] (w/fragment "dup" (fn [] [:li {:id "dup"} u])))
                          ["alice" "bob"])])))))

(deftest a-nested-fragment-sharing-its-parents-id-throws
  ;; The other collision path. Siblings collide on the way in; a child collides with
  ;; its parent on the way up. Both need checking, because they go through different
  ;; calls.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Duplicate fragment id \"x\""
       (w/render-recording
        (fn [] (w/fragment "x"
                           (fn [] [:div {:id "x"}
                                   (w/fragment "x" (fn [] [:p {:id "x"} "in"]))])))))))

(deftest a-fragment-body-that-cannot-carry-an-id-throws
  ;; Carried over from `render_test/a-boundary-must-be-able-to-carry-metadata`. A
  ;; string, a number or a seq cannot hold an `:id`, so it can never be a patch
  ;; target. Pinned by a test because it currently holds only as a side effect of
  ;; `node-id` returning nil for them — nothing states it on purpose.
  (doseq [body ["text" 42 '([:li {:id "x"} "a"]) [:div "no attr map"]]]
    (is (thrown? clojure.lang.ExceptionInfo (w/fragment "x" (fn [] body)))
        (str "should reject " (pr-str body)))))

(deftest distinct-ids-in-a-collection-are-unaffected
  ;; The check must not make the ordinary case harder — this is the shape every roster
  ;; has.
  (let [r (w/render-recording
           (fn [] [:ul (mapv (fn [u] (w/fragment (str "m-" u)
                                                 (fn [] [:li {:id (str "m-" u)} u])))
                             ["alice" "bob"])]))]
    (is (= ["m-alice" "m-bob"] (sort (keys (:fragments r)))))))

(deftest a-matching-id-passes-and-the-tree-is-unchanged
  ;; The check must be transparent: it returns the tree, it does not rewrite it, and
  ;; in particular it does not *insert* the id. Generating the id is the thing this
  ;; design deliberately does not do — a derived target is what drifted before.
  (let [tree (w/fragment "member-alice"
                         (fn [] [:li {:id "member-alice" :class "member"} "alice"]))]
    (is (= [:li {:id "member-alice" :class "member"} "alice"] tree))))
