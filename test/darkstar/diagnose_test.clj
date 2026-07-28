(ns darkstar.diagnose-test
  "Tests for the diagnostics, written against the three bugs they exist to catch.

  Each `deftest` reproduces a mistake that was made for real while building an
  application on `watch`, and produced no error at the time."
  (:require [clojure.test :refer [deftest is testing]]
            [darkstar.diagnose :as d]
            [darkstar.watch :as w]))

(deftest a-reader-that-rebuilds-its-value-is-reported
  ;; Bug 3, from the dashboard: `all-jobs` sorted into a fresh vector per call, so
  ;; `identical?` always failed and the two SLOWEST fragments re-rendered ten times a
  ;; second. Nothing was wrong on screen, which is what made it invisible.
  ;; `(vec @rows)` would NOT reproduce this: `vec` on a vector returns the same
  ;; vector, so it is perfectly stable. It takes an actual rebuild — a sort, a map, a
  ;; filter — to defeat `identical?`. Worth recording, because the first version of
  ;; this test used `vec` and reported the detector as broken when the FIXTURE was.
  (let [rows (atom [{:id 2} {:id 1}])
        bad (fn [_] (w/fragment "list"
                                (fn [] [:ul {:id "list"}
                                        (count (w/watch [:rows] #(vec (sort-by :id @rows))))])))
        problems (d/check-component bad {} :known-topics #{[:rows]})]
    (is (= 1 (count problems)))
    (is (= :unstable-reader (:problem (first problems))))
    (is (= "list" (:fragment (first problems)))))

  (testing "and a cached reader is not"
    (let [cache (atom {:rows [{:id 1}]})
          good (fn [_] (w/fragment "list"
                                   (fn [] [:ul {:id "list"}
                                           (count (w/watch [:rows] #(:rows @cache)))])))]
      (is (= [] (d/check-component good {} :known-topics #{[:rows]}))))))

(deftest a-reader-returning-a-fresh-scalar-is-not-reported
  ;; `same?` falls back to `=` for scalars, so a fresh `Long` or `String` prunes fine.
  ;; Reporting those would bury the real signal — `(count xs)` boxes a new Long on
  ;; every call and is entirely correct.
  (let [n (atom 5)
        f (fn [_] (w/fragment "c" (fn [] [:span {:id "c"}
                                          (w/watch [:n] #(+ 0 @n))])))]
    (is (= [] (d/check-component f {} :known-topics #{[:n]})))))

(deftest a-fragment-that-reads-nothing-is-reported
  ;; Bug 4, from the dashboard: `job-row` took the job map as an argument instead of
  ;; watching `[:job id]`, so a progress bar that changes ten times a second would have
  ;; been frozen at whatever the list last held.
  (let [data (atom {:name "web"})
        bad (fn [_] (w/fragment "row" (fn [] [:li {:id "row"} (:name @data)])))
        problems (d/check-component bad {})]
    (is (= [:silent-fragment] (mapv :problem problems)))
    (is (= "row" (:fragment (first problems)))))

  (testing "unless it is declared static"
    (let [bad (fn [_] (w/fragment "row" (fn [] [:li {:id "row"} "static"])))]
      (is (= [] (d/check-component bad {} :allow-silent #{"row"}))))))

(deftest a-topic-nobody-publishes-is-reported
  ;; Bug 5, the one that cost the most time: the ported chat app watched
  ;; `[:members id]` while the server published `[:channel id]`. First render correct,
  ;; never updated again, no error — a name invented at the read site has no
  ;; counterpart at the publish site to disagree with.
  (reset! d/published #{})
  (d/note-published! [:channel "c1"])
  (let [bad (fn [_] (w/fragment "r" (fn [] [:ul {:id "r"}
                                            (w/watch [:members "c1"] (fn [] []))])))
        problems (d/check-component bad {})]
    (is (some #(= :orphan-topic (:problem %)) problems))
    (is (some #(= [:members "c1"] (:topic %)) problems)))

  (testing "and the published name is accepted"
    (let [good (fn [_] (w/fragment "r" (fn [] [:ul {:id "r"}
                                               (w/watch [:channel "c1"] (fn [] []))])))]
      (is (= [] (d/check-component good {}))))))

(deftest one-problem-is-reported-once-not-once-per-ancestor
  ;; Topics bubble up to containing fragments, so a naive walk reports the same unstable
  ;; reader once per ancestor — three copies of one problem for a three-deep tree, which
  ;; buries the signal. Reported against the innermost fragment only.
  (let [rows (atom [1 2])
        nested (fn [_]
                 (w/fragment "outer"
                             (fn [] [:div {:id "outer"}
                                     (w/fragment "middle"
                                                 (fn [] [:div {:id "middle"}
                                                         (w/fragment "inner"
                                                                     (fn [] [:ul {:id "inner"}
                                                                             (count (w/watch [:rows] #(vec (sort-by :id @rows))))]))]))])))
        problems (d/check-component nested {} :known-topics #{[:rows]})]
    (is (= 1 (count problems)))
    (is (= "inner" (:fragment (first problems))))))

(deftest correct-code-produces-nothing
  ;; The property that decides whether this is usable: a checker that cries wolf on
  ;; sound code will be turned off and then it protects nothing.
  (reset! d/published #{})
  (d/note-published! [:rows])
  (let [cache (atom {:rows [{:id 1 :name "web"}]})
        good (fn [_]
               (w/fragment "app"
                           (fn [] [:div {:id "app"}
                                   (w/fragment "list"
                                               (fn [] [:ul {:id "list"}
                                                       (count (w/watch [:rows] #(:rows @cache)))]))
                                   (w/fragment "title" (fn [] [:h1 {:id "title"} "Builds"]))])))]
    (is (= [] (d/check-component good {} :allow-silent #{"title"})))))
