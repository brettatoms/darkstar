(ns darkstar.action-test
  "Tests for the action expression builder.

  Each of the three known defects is pinned by a test that **fails against
  the old query-string form**, which is the only way to know these assert anything.
  Earlier in this project a pruning test suite passed 41 assertions with the
  optimisation sabotaged, so a test that cannot fail is treated here as no test."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [darkstar.action :as action]))

(def ^:private path "/live/act")

;;; ==========================================================================
;;; JSON writing
;;; ==========================================================================

(deftest write-json-scalars
  (is (= "null" (action/write-json nil)))
  (is (= "true" (action/write-json true)))
  (is (= "false" (action/write-json false)))
  (is (= "42" (action/write-json 42)))
  (is (= "-1" (action/write-json -1)))
  (is (= "1.5" (action/write-json 1.5)))
  (is (= "\"hi\"" (action/write-json "hi")))
  (testing "keywords and symbols degrade to strings, losing namespace"
    (is (= "\"a\"" (action/write-json :a)))
    (is (= "\"bar\"" (action/write-json :foo/bar)))
    (is (= "\"s\"" (action/write-json 's)))))

(deftest write-json-collections
  (is (= "[1,2,3]" (action/write-json [1 2 3])))
  (is (= "[]" (action/write-json [])))
  (is (= "{}" (action/write-json {})))
  (is (= "{\"a\":1}" (action/write-json {:a 1})))
  (testing "nested"
    (is (= "{\"a\":{\"b\":[1,\"c\"]}}"
           (action/write-json {:a {:b [1 "c"]}})))))

(deftest write-json-rejects-unrepresentable
  (testing "a bare object is refused rather than toString'd"
    (is (thrown? clojure.lang.ExceptionInfo
                 (action/write-json (java.util.Date.)))))
  (testing "non-finite doubles have no JSON form"
    (is (thrown? clojure.lang.ExceptionInfo (action/write-json (/ 1.0 0.0))))
    (is (thrown? clojure.lang.ExceptionInfo (action/write-json (Math/sqrt -1)))))
  (testing "types whose print form is not valid JSON are converted"
    ;; 1/3 and 1.0M would both be syntax errors in a JSON parser.
    (is (= "0.3333333333333333" (action/write-json (/ 1 3))))
    (is (= "1.5" (action/write-json 1.5M)))))

(deftest write-json-escaping
  (testing "JSON minimum"
    (is (= "\"a\\\"b\"" (action/write-json "a\"b")))
    (is (= "\"a\\\\b\"" (action/write-json "a\\b")))
    (is (= "\"a\\nb\"" (action/write-json "a\nb")))
    (is (= "\"a\\tb\"" (action/write-json "a\tb"))))
  (testing "control characters"
    (is (= "\"\\u0000\"" (action/write-json (str (char 0))))))
  (testing "apostrophe, as \\u0027 rather than \\' — the latter is invalid JSON"
    (is (= "\"o\\u0027brien\"" (action/write-json "o'brien")))
    (is (not (str/includes? (action/write-json "o'brien") "\\'"))))
  (testing "< so a value cannot open a </script> sequence"
    (is (= "\"\\u003c/script>\"" (action/write-json "</script>")))))

;;; ==========================================================================
;;; The three defects from 
;;; ==========================================================================
;;; Each of these is written to fail against the old form. The old form is
;;; reproduced here so the comparison is explicit rather than asserted.

(defn- old-style
  "The pre-fix expression builder, kept only so the defect tests can demonstrate
  they distinguish old from new."
  [event args]
  (str "@post('/act?event=" (name event)
       (apply str (map (fn [[k v]] (str "&" (name k) "=" v)) args))
       "')"))

(deftest defect-1-apostrophe-no-longer-closes-the-string-literal
  (let [old (old-style :remove {:id "o'brien"})
        new (action/post path :remove {:id "o'brien"})]
    (testing "the old form put a bare ' inside a single-quoted expression"
      ;; @post('/act?event=remove&id=o'brien')  <- literal ends at o
      (is (str/includes? old "o'brien")))
    (testing "the new form carries no bare apostrophe at all"
      (is (not (str/includes? new "o'brien")))
      (is (str/includes? new "o\\u0027brien")))
    (testing "quoting stays balanced: the only bare ' are the two delimiters"
      (is (= 2 (count (filter #(= \' %) new)))))))

(deftest defect-1-ampersand-no-longer-splits-a-param
  (let [old (old-style :remove {:id "a&b"})
        new (action/post path :remove {:id "a&b"})]
    (testing "the old form produced a second query param"
      (is (str/includes? old "id=a&b")))
    (testing "the new form keeps it one JSON value"
      (is (str/includes? new "\"id\":\"a&b\""))
      ;; No query string at all, so there is nothing for & to split.
      (is (not (str/includes? new "?"))))))

(deftest defect-3-types-survive
  (testing "a query string flattens everything to a string; JSON does not"
    (let [new (action/post path :set {:n 42 :s "42" :b true :nil nil})]
      (is (str/includes? new "\"n\":42"))
      (is (str/includes? new "\"s\":\"42\""))
      (is (str/includes? new "\"b\":true"))
      (is (str/includes? new "\"nil\":null"))
      (testing "42 and \"42\" are distinguishable, which is the whole point"
        (is (not= (str/index-of new "\"n\":42")
                  (str/index-of new "\"s\":\"42\"")))))))

;;; ==========================================================================
;;; Expression shape
;;; ==========================================================================

(deftest act-basic-shape
  (is (= "@post('/live/act', {payload: {\"event\":\"inc\",\"liveId\":$liveId}})"
         (action/post path :inc)))
  (testing "args land between the event and the live id"
    (is (= (str "@post('/live/act', {payload: "
                "{\"event\":\"remove\",\"id\":42,\"liveId\":$liveId}})")
           (action/post path :remove {:id 42})))))

(deftest act-includes-live-id-as-an-expression-not-a-literal
  ;; payload REPLACES the signal set, so if the id were not merged back in the
  ;; server would reject every action with "no live context". And it must be an
  ;; unquoted $liveId — quoting it sends the literal text "$liveId".
  (let [expr (action/post path :inc)]
    (is (str/includes? expr "\"liveId\":$liveId"))
    (is (not (str/includes? expr "\"$liveId\"")))))

(deftest act-path-is-a-parameter
  ;; 's third question: the path belongs to whoever mounted the route.
  (is (str/starts-with? (action/post "/custom/dispatch" :inc)
                        "@post('/custom/dispatch'"))
  (is (str/includes? (action/put path :inc) "@put(")))

(deftest act-custom-signal
  (let [expr (action/post path :inc nil {:signal "ctxId"})]
    (is (str/includes? expr "\"ctxId\":$ctxId"))
    (is (not (str/includes? expr "liveId")))))

(deftest act-rejects-shadowing-args
  (testing "an arg named event would silently redirect the dispatch"
    (is (thrown? clojure.lang.ExceptionInfo
                 (action/post path :inc {:event "other"}))))
  (testing "an arg named liveId would silently retarget the context"
    (is (thrown? clojure.lang.ExceptionInfo
                 (action/post path :inc {:liveId "other"}))))
  (testing "string keys shadow just as effectively as keywords"
    (is (thrown? clojure.lang.ExceptionInfo
                 (action/post path :inc {"event" "other"})))))

(deftest act-validates-its-inputs
  (is (thrown? clojure.lang.ExceptionInfo (action/post nil :inc)))
  (is (thrown? clojure.lang.ExceptionInfo (action/post "" :inc)))
  (is (thrown? clojure.lang.ExceptionInfo (action/post path nil))))

(deftest act-accepts-string-events
  (is (str/includes? (action/post path "inc") "\"event\":\"inc\"")))

(deftest raw-emits-a-client-expression-unescaped
  ;; The evt.target.value case. A plain string would be quoted into a literal,
  ;; which is correct for data and wrong for code — hence the marker.
  (let [expr (action/post path :draft {:text (action/raw "evt.target.value")})]
    (is (str/includes? expr "\"text\":evt.target.value"))
    (is (not (str/includes? expr "\"evt.target.value\""))))
  (testing "an unmarked string is still escaped, so raw is opt-in only"
    (is (str/includes? (action/post path :draft {:text "evt.target.value"})
                       "\"text\":\"evt.target.value\""))))

;;; ==========================================================================
;;; Method-named entry points
;;; ==========================================================================

(deftest every-method-has-an-entry-point
  (testing "the method is visible at the call site rather than an option"
    (are [f m] (str/starts-with? (f path :inc) (str "@" m "('"))
      action/post "post"
      action/get "get"
      action/put "put"
      action/patch "patch"
      action/delete "delete")))

(deftest methods-differ-only-in-the-action-name
  ;; The payload shape must not vary by method: GET and DELETE put it in a query
  ;; parameter rather than a body, but that is datastar's doing, not ours, so the
  ;; expression we emit is identical apart from the action.
  (let [payload-of #(subs % (str/index-of % ", {payload:"))]
    (is (apply = (map #(payload-of (% path :remove {:id 42}))
                      [action/post action/get action/put
                       action/patch action/delete])))))

(deftest all-methods-share-the-soundness-guarantees
  ;; Defect 1 must not creep back in through a wrapper that skipped escaping.
  (doseq [f [action/post action/get action/put action/patch action/delete]]
    (let [expr (f path :remove {:id "o'brien"})]
      (is (not (str/includes? expr "o'brien")))
      (is (= 2 (count (filter #(= \' %) expr)))))))
