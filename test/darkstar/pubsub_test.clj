(ns darkstar.pubsub-test
  "Tests for subscriptions, hints and coalescing."
  (:require [clojure.test :refer [deftest is testing]]
            [darkstar.pubsub :as pubsub]))

(defn- fixture []
  [(pubsub/registry) (pubsub/local-pubsub)])

;;; ==========================================================================
;;; The bus
;;; ==========================================================================

(deftest hints-reach-subscribers
  (let [bus (pubsub/local-pubsub)
        seen (atom [])]
    (pubsub/subscribe! bus [:counter 1] #(swap! seen conj %))
    (pubsub/publish! bus [:counter 1])
    (is (= [[:counter 1]] @seen))))

(deftest hints-do-not-reach-other-topics
  (let [bus (pubsub/local-pubsub)
        seen (atom [])]
    (pubsub/subscribe! bus [:counter 1] #(swap! seen conj %))
    (pubsub/publish! bus [:counter 2])
    (is (= [] @seen))))

(deftest unsubscribe-stops-delivery
  (let [bus (pubsub/local-pubsub)
        seen (atom [])
        unsub (pubsub/subscribe! bus :t #(swap! seen conj %))]
    (pubsub/publish! bus :t)
    (unsub)
    (pubsub/publish! bus :t)
    (is (= 1 (count @seen)))))

(deftest a-failing-subscriber-does-not-block-others
  ;; A hint is fire-and-forget by contract, so one bad subscriber must not stop
  ;; delivery or propagate to the publisher.
  (let [bus (pubsub/local-pubsub)
        seen (atom [])]
    (pubsub/subscribe! bus :t (fn [_] (throw (ex-info "boom" {}))))
    (pubsub/subscribe! bus :t #(swap! seen conj %))
    (is (nil? (pubsub/publish! bus :t)) "publish does not throw")
    (is (= [:t] @seen) "the healthy subscriber still ran")))

(deftest publishing-to-nobody-is-fine
  (is (nil? (pubsub/publish! (pubsub/local-pubsub) :nobody-listening))))

;;; ==========================================================================
;;; Subscription registry
;;; ==========================================================================

(deftest subscribing-a-context-records-its-topics
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [[:counter 1] [:user 7]])
    (is (= #{[:counter 1] [:user 7]} (pubsub/topics-of reg "c1")))))

(deftest resubscribing-diffs-rather-than-replacing
  ;; :subscribe can depend on params, so when params change some topics persist.
  ;; Tearing all of them down and re-establishing would drop hints in the gap.
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:a :b])
    (let [{:keys [added removed]}
          (pubsub/subscribe-context! reg bus "c1" [:b :c])]
      (is (= #{:c} added) "only genuinely new topics are subscribed")
      (is (= #{:a} removed) "only genuinely gone topics are torn down")
      (is (= #{:b :c} (pubsub/topics-of reg "c1"))))))

(deftest a-persisting-topic-keeps-receiving-hints-across-a-resubscribe
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:a :b])
    (pubsub/subscribe-context! reg bus "c1" [:b :c])
    (pubsub/publish! bus :b)
    (is (contains? (pubsub/dirty-topics reg) :b)
        "a topic present before and after must not be silently dropped")))

(deftest a-removed-topic-stops-dirtying
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:a])
    (pubsub/subscribe-context! reg bus "c1" [])
    (pubsub/publish! bus :a)
    (is (empty? (pubsub/dirty-topics reg)))))

(deftest unsubscribing-a-context-removes-everything
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:a :b])
    (pubsub/unsubscribe-context! reg "c1")
    (is (= #{} (pubsub/topics-of reg "c1")))
    (pubsub/publish! bus :a)
    (is (empty? (pubsub/dirty-topics reg)) "no lingering subscription")))

(deftest contexts-are-found-by-topic
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:a :shared])
    (pubsub/subscribe-context! reg bus "c2" [:b :shared])
    (pubsub/subscribe-context! reg bus "c3" [:c])
    (is (= #{"c1" "c2"} (pubsub/contexts-for reg [:shared])))
    (is (= #{"c1"} (pubsub/contexts-for reg [:a])))
    (is (= #{} (pubsub/contexts-for reg [:nobody])))))

;;; ==========================================================================
;;; Coalescing — the property that makes hints affordable
;;; ==========================================================================

(deftest many-hints-collapse-into-one-rebuild
  ;; Hints carry no data, so N of them are indistinguishable from one. Collapsing
  ;; them is therefore lossless — a property data-carrying events could not have.
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:hot])
    (dotimes [_ 100] (pubsub/publish! bus :hot))
    (let [{:keys [topics contexts]} (pubsub/flush-dirty! reg)]
      (is (= #{:hot} topics) "100 hints, one topic")
      (is (= #{"c1"} contexts) "and one rebuild")))
  (testing "and the dirty set is empty afterwards"
    (let [[reg bus] (fixture)]
      (pubsub/subscribe-context! reg bus "c1" [:hot])
      (pubsub/publish! bus :hot)
      (pubsub/flush-dirty! reg)
      (is (empty? (pubsub/dirty-topics reg))))))

(deftest a-context-dirtied-by-several-topics-rebuilds-once
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:a :b :c])
    (pubsub/publish! bus :a)
    (pubsub/publish! bus :b)
    (pubsub/publish! bus :c)
    (let [{:keys [contexts]} (pubsub/flush-dirty! reg)]
      (is (= #{"c1"} contexts) "three topics, one context, one rebuild"))))

(deftest flushing-an-empty-set-yields-nothing
  (let [[reg _] (fixture)]
    (is (= {:topics #{} :contexts #{}} (pubsub/flush-dirty! reg)))))

(deftest a-hint-arriving-during-a-flush-is-not-lost
  ;; The subtle one. Draining and reading must be atomic: a hint that lands
  ;; mid-flush has to appear in the NEXT window, because a dropped hint means a
;; permanently stale view — the design one genuinely bad failure mode.
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:a :b])
    (pubsub/publish! bus :a)
    (let [first-flush (pubsub/flush-dirty! reg)]
      ;; A hint arrives after the drain.
      (pubsub/publish! bus :b)
      (let [second-flush (pubsub/flush-dirty! reg)]
        (is (= #{:a} (:topics first-flush)))
        (is (= #{:b} (:topics second-flush))
            "the later hint lands in the next window, not nowhere")))))

(deftest concurrent-hints-are-never-lost-during-a-flush
  ;; The single-threaded test above documents the intent but cannot detect a
  ;; non-atomic drain — verified by sabotage: replacing swap-vals! with a
  ;; read-then-clear pair left it passing. Only concurrency exposes it, and with
  ;; the sabotage in place this loses 6-29 of 2000 hints.
  ;;
;; A lost hint means a permanently stale view, which is the design one genuinely bad
  ;; failure mode, so it earns a real race.
  ;;
  ;; Structured to be deterministic rather than timing-dependent: the publisher
  ;; runs to completion, the flusher drains until the publisher is done AND two
  ;; consecutive drains come back empty. An earlier version looped on a count and
  ;; was flaky at roughly 1 run in 7.
  (let [[reg bus] (fixture)
        n 2000
        seen (atom #{})]
    (pubsub/subscribe-context! reg bus "c1" (mapv #(vector :t %) (range n)))
    (let [publisher (future (dotimes [i n] (pubsub/publish! bus [:t i])))
          flusher (future
                    (loop [empties 0]
                      (let [{:keys [topics]} (pubsub/flush-dirty! reg)]
                        (swap! seen into topics)
                        (cond
                          ;; Publisher done and the set has stayed empty twice —
                          ;; nothing more can arrive.
                          (and (realized? publisher) (empty? topics) (pos? empties))
                          :done

                          (empty? topics) (recur (inc empties))
                          :else (recur 0)))))]
      @publisher
      (is (= :done (deref flusher 10000 :timeout)) "flusher terminated")
      (is (= n (count @seen))
          (str "lost " (- n (count @seen)) " of " n " hints to the drain race")))))

(deftest hints-for-unsubscribed-topics-dirty-nothing
  (let [[reg bus] (fixture)]
    (pubsub/subscribe-context! reg bus "c1" [:a])
    (pubsub/publish! bus :unrelated)
    (is (empty? (pubsub/dirty-topics reg)))))

;;; ==========================================================================
;;; Fan-out shape
;;; ==========================================================================

(deftest one-hint-fans-out-to-every-subscribed-context
;; This is the amplification quantifies: a hint on a shared topic dirties
  ;; every viewer of it. Coalescing bounds the hint count, not the viewer count —
;; that needs shared derivation, which is not built.
  (let [[reg bus] (fixture)]
    (doseq [i (range 1000)]
      (pubsub/subscribe-context! reg bus (str "c" i) [:shared]))
    (pubsub/publish! bus :shared)
    (let [{:keys [contexts]} (pubsub/flush-dirty! reg)]
      (is (= 1000 (count contexts))
          "one hint, 1000 rebuilds — the cost shared derivation addresses"))))

(deftest a-swappable-bus-is-the-whole-seam
  ;; The engine must work against any bus. Verified by supplying a trivial one
  ;; that is not the built-in.
  (let [reg (pubsub/registry)
        delivered (atom [])
        fake (reify pubsub/PubSub
               (publish! [_ topic] (swap! delivered conj topic))
               (subscribe! [_ _topic _handler] (fn [] nil)))]
    (pubsub/subscribe-context! reg fake "c1" [:a])
    (pubsub/publish! fake :a)
    (is (= [:a] @delivered) "the engine only needs publish!/subscribe!")))

;;; ==========================================================================
;;; Announce AFTER you subscribe, not before
;;; ==========================================================================

(deftest a-hint-published-before-subscribing-still-dirties-the-new-subscriber
  ;; The sequencing trap behind a real bug: a chat member joined, and one viewer's
  ;; roster updated while another's did not.
  ;;
  ;; The joining connection did this, in order:
  ;;   1. publish [:channel c]     — announce the new member
  ;;   2. subscribe to [:channel c]
  ;;   3. mount                    — derive the view, which now HAS the new member
  ;;
  ;; Step 1 marks every current subscriber dirty. But the hint is still pending
  ;; when step 2 runs, so this brand-new context is *also* in the next flush — and
  ;; by then step 3 has already given it the new roster. The flush therefore diffs
  ;; the new view against itself, emits nothing, and whichever context happened to
  ;; be flushed in that window silently lost its update.
  ;;
  ;; This test pins the pubsub behaviour that makes that possible, so the ordering
  ;; requirement is written down rather than rediscovered.
  (let [bus (pubsub/local-pubsub)
        reg (pubsub/registry)]
    ;; An existing viewer.
    (pubsub/subscribe-context! reg bus "viewer" [[:channel "c"]])
    ;; A joiner announces BEFORE subscribing — the buggy order.
    (pubsub/publish! bus [:channel "c"])
    (pubsub/subscribe-context! reg bus "joiner" [[:channel "c"]])
    (let [{:keys [contexts]} (pubsub/flush-dirty! reg)]
      (testing "the existing viewer is dirty, which is correct"
        (is (contains? contexts "viewer")))
      (testing "and so is the joiner, which is the trap"
        (is (contains? contexts "joiner")
            (str "A hint published before subscribing still reaches the new "
                 "subscriber, because the dirty set is per-topic and drained "
                 "later. So a context that mounts fresh data after publishing "
                 "will diff that data against itself and emit nothing. "
                 "Subscribe and mount BEFORE announcing."))))))

(deftest announcing-after-subscribing-and-mounting-is-safe
  ;; The correct order, for contrast: the joiner subscribes and mounts, and only
  ;; then announces. It is still marked dirty by its own hint — unavoidable, since
  ;; it is a subscriber — but its view is already current, so a redundant refresh
  ;; is a wasted rebuild rather than a lost update. Other viewers still get theirs.
  (let [bus (pubsub/local-pubsub)
        reg (pubsub/registry)]
    (pubsub/subscribe-context! reg bus "viewer" [[:channel "c"]])
    (pubsub/subscribe-context! reg bus "joiner" [[:channel "c"]])
    ;; ... joiner mounts here, with the new roster ...
    (pubsub/publish! bus [:channel "c"])
    (let [{:keys [contexts]} (pubsub/flush-dirty! reg)]
      (is (contains? contexts "viewer") "the viewer must still be told")
      (is (contains? contexts "joiner")
          "the joiner is dirty too; harmless, because its view is already current"))))
