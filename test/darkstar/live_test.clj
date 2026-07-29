(ns darkstar.live-test
  "Tests for the watch runtime, written from what a browser actually did.

  Each of these corresponds to a real observed failure or a real observed pass in
  `zodiac-live/examples/watchspike`, not to a guess about what might go wrong."
  (:require [clojure.test :refer [deftest is testing]]
            [darkstar.watch :as w]
            [darkstar.live :as we]))

;;; A roster, in the intended style: one function, dependencies at point of use.

(defn- roster-component
  [members presence]
  (fn [{:keys [channel-id]}]
    (w/fragment "roster"
                (fn []
                  (let [ms (w/watch [:members channel-id]
                                    #(vec (sort (get @members channel-id))))]
                    [:div {:id "roster"}
                   ;; mapv, not for: a lazy seq escapes the recording binding.
                     (mapv (fn [u]
                             (w/fragment (str "member-" u)
                                         (fn []
                                           (let [on? (w/watch [:presence channel-id u]
                                                              #(contains? @presence u))]
                                             [:li {:id (str "member-" u)}
                                              u (if on? "online" "offline")]))))
                           ms)])))))

(defn- fixture
  "An engine with one connection mounted, plus the sink it pushes to."
  [members presence]
  (let [sent (atom [])
        eng (we/engine {:components {:roster (roster-component members presence)}
                        :render-fn pr-str})
        id (we/connect! eng :roster {:params {:channel-id "c1"}
                                     :send! #(swap! sent into %)})
        mounted (we/mount! eng id)]
    {:eng eng :id id :sent sent :mounted mounted}))

(deftest a-new-dependency-is-reported-so-a-caller-can-subscribe-to-it
  ;; THE BUG THIS EXISTS FOR, found in a browser and nowhere else.
  ;;
  ;; A viewer mounts when the channel holds only itself, so it subscribes to its own
  ;; presence topic and the members topic. Another member then joins. The members
  ;; hint re-renders the viewer's roster — which now reads a presence topic for the
  ;; NEW member, one that did not exist at mount time.
  ;;
  ;; If `refresh!` does not report that, the viewer never subscribes to it, and the
  ;; new member appears in the roster and then never goes offline. The first render
  ;; is perfect and the join works, which is exactly why this survived a headless
  ;; test and a passing browser check of the join path.
  (let [members (atom {"c1" #{"amy"}})
        presence (atom #{"amy"})
        {:keys [eng id mounted]} (fixture members presence)]
    (is (= #{[:members "c1"] [:presence "c1" "amy"]} (set (:topics mounted)))
        "at mount, nick's topic cannot be known because nick does not exist")

    (swap! members update "c1" conj "nick")
    (swap! presence conj "nick")
    (let [{:keys [added topics patches]} (we/refresh! eng id [:members "c1"])]
      (testing "the newly-read topic is reported as added"
        (is (= [[:presence "c1" "nick"]] added)))
      (testing "and the full set is available too"
        (is (= #{[:members "c1"] [:presence "c1" "amy"] [:presence "c1" "nick"]}
               (set topics))))
      (testing "the join itself patches the list, since membership changed"
        (is (= ["#roster"] (mapv :selector patches)))))

    (testing "so a hint for the new member's presence now reaches ONE row"
      (swap! presence disj "nick")
      (let [{:keys [patches]} (we/refresh! eng id [:presence "c1" "nick"])]
        (is (= ["#member-nick"] (mapv :selector patches)))
        (is (re-find #"offline" (:html (first patches))))))))

(deftest a-dependency-that-goes-away-is-reported-as-removed
  ;; The other half: subscriptions must shrink, or a connection accumulates topics
  ;; for members it no longer renders and wakes for changes it cannot show.
  (let [members (atom {"c1" #{"amy" "nick"}})
        presence (atom #{"amy" "nick"})
        {:keys [eng id mounted]} (fixture members presence)]
    (is (contains? (set (:topics mounted)) [:presence "c1" "nick"]))
    (swap! members update "c1" disj "nick")
    (let [{:keys [removed topics]} (we/refresh! eng id [:members "c1"])]
      (is (= [[:presence "c1" "nick"]] removed))
      (is (not (contains? (set topics) [:presence "c1" "nick"]))))))

(deftest a-presence-change-patches-one-row-not-the-whole-list
  ;; Minimal updates are the point of fragments. Patching the containing fragment would
  ;; also be *correct*, so this asserts on the narrowness, which is the property a
  ;; browser makes visible and a correctness test does not.
  (let [members (atom {"c1" #{"amy" "nick"}})
        presence (atom #{"amy" "nick"})
        {:keys [eng id]} (fixture members presence)]
    (swap! presence disj "amy")
    (is (= ["#member-amy"]
           (mapv :selector (:patches (we/refresh! eng id [:presence "c1" "amy"])))))))

(deftest a-hint-nobody-read-sends-nothing
  ;; A hint carries no payload and may be published speculatively, so an irrelevant
  ;; one must be free rather than an error or a spurious full-tree patch.
  (let [members (atom {"c1" #{"amy"}})
        presence (atom #{"amy"})
        {:keys [eng id sent]} (fixture members presence)]
    (is (= [] (:patches (we/refresh! eng id [:presence "c1" "nobody"]))))
    (is (= [] @sent))))

(deftest pruning-uses-the-readers-the-component-supplied
  ;; No `:read-fns` is passed anywhere here. `watch` recorded each `read-fn`, so the
  ;; engine re-reads with the same function the component used and cannot disagree
  ;; with it. An application-supplied table of readers could — and it was a second
  ;; place to state every dependency, which is the thing `watch` exists to remove.
  (let [members (atom {"c1" #{"amy"}})
        presence (atom #{"amy"})
        {:keys [eng id]} (fixture members presence)]
    (testing "a hint where nothing changed renders nothing"
      (let [r (we/refresh! eng id [:presence "c1" "amy"])]
        (is (true? (:pruned r)))
        (is (= [] (:patches r)))))
    (testing "a real change is not pruned"
      (swap! presence disj "amy")
      (let [r (we/refresh! eng id [:presence "c1" "amy"])]
        (is (not (:pruned r)))
        (is (= ["#member-amy"] (mapv :selector (:patches r))))))
    (testing "and a member discovered AFTER mount prunes too, from a reader recorded
              during a partial re-render"
      (swap! members update "c1" conj "nick")
      (swap! presence conj "nick")
      (we/refresh! eng id [:members "c1"])
      (is (true? (:pruned (we/refresh! eng id [:presence "c1" "nick"]))))
      (swap! presence disj "nick")
      (is (= ["#member-nick"]
             (mapv :selector (:patches (we/refresh! eng id [:presence "c1" "nick"]))))))))

(deftest a-reader-closing-over-a-dereffed-value-goes-permanently-stale
  ;; THE HAZARD, pinned so it is not discovered in a browser. A recorded reader must
  ;; re-read current state when called. One that closes over an already-dereferenced
  ;; value reports "unchanged" forever and the fragment never updates again.
  ;;
  ;; This is the dangerous direction — a false positive, not a false negative — so it
  ;; is worth a test that states plainly that the engine cannot detect it.
  (let [presence (atom #{"amy"})
        sent (atom [])
        bad (fn [_params]
              ;; WRONG on purpose: the deref happens outside the thunk.
              (let [snap @presence]
                (w/fragment "row"
                            (fn []
                              (let [on? (w/watch [:presence "amy"]
                                                 #(contains? snap "amy"))]
                                [:li {:id "row"} (if on? "online" "offline")])))))
        eng (we/engine {:components {:r bad} :render-fn pr-str})
        id (we/connect! eng :r {:params {} :send! #(swap! sent into %)})]
    (we/mount! eng id)
    (swap! presence disj "amy")
    (is (true? (:pruned (we/refresh! eng id [:presence "amy"])))
        "the change is invisible: the closure froze the value at first render")
    (testing "whereas the same component reading inside the thunk sees it"
      (let [good (fn [_params]
                   (w/fragment "row"
                               (fn []
                                 (let [on? (w/watch [:presence "amy"]
                                                    #(contains? @presence "amy"))]
                                   [:li {:id "row"} (if on? "online" "offline")]))))
            eng2 (we/engine {:components {:r good} :render-fn pr-str})
            id2 (we/connect! eng2 :r {:params {} :send! (fn [_] nil)})]
        (reset! presence #{"amy"})
        (we/mount! eng2 id2)
        (swap! presence disj "amy")
        (is (not (:pruned (we/refresh! eng2 id2 [:presence "amy"]))))))))

(deftest two-sibling-fragments-reading-one-topic-both-get-patched
  ;; FOUND IN A BROWSER, and the reason `innermost-independent` exists.
  ;;
  ;; `refresh!` originally patched the single narrowest match, to stop a fragment and
  ;; its ancestor both being patched for one change. That is right for ancestors and
  ;; silently wrong for siblings: a roster and a message list both reading
  ;; `[:channel id]` is ordinary, and the one that lost the tie never updated. In the
  ;; chat app a member joined and the other viewer's roster stayed stale, because
  ;; `messages` and `roster` had the same topic count and `messages` sorted first.
  (let [members (atom ["ann"])
        msgs (atom [])
        component
        (fn [{:keys [channel-id]}]
          (w/fragment "root"
                      (fn []
                        [:div {:id "root"}
                         ;; Two SIBLINGS, same topic. Neither contains the other.
                         (w/fragment "roster"
                                     (fn []
                                       (let [ms (w/watch [:channel channel-id]
                                                         #(deref members))]
                                         [:ul {:id "roster"} (count ms)])))
                         (w/fragment "messages"
                                     (fn []
                                       (let [xs (w/watch [:channel channel-id]
                                                         #(deref msgs))]
                                         [:ul {:id "messages"} (count xs)])))])))
        eng (we/engine {:components {:c component} :render-fn pr-str})
        sent (atom [])
        id (we/connect! eng :c {:params {:channel-id "c"} :send! #(swap! sent into %)})]
    (we/mount! eng id)

    (testing "the sibling whose data changed is patched, even when it is not first"
      ;; `messages` sorts before `roster` on topic count, and used to win outright.
      (reset! sent [])
      (swap! members conj "ben")
      (is (= ["#roster"] (mapv :selector (:patches (we/refresh! eng id [:channel "c"]))))
          "roster changed; messages did not, so pruning leaves it alone"))

    (testing "and when BOTH changed, both are patched"
      (reset! sent [])
      (swap! members conj "cal")
      (swap! msgs conj {:id 1})
      (is (= #{"#roster" "#messages"}
             (set (mapv :selector (:patches (we/refresh! eng id [:channel "c"])))))))))

(deftest an-ancestor-is-not-patched-alongside-its-dirty-child
  ;; The other half, and the reason the rule is containment rather than "patch every
  ;; match". A containing fragment inherits its children's topics, so patching parent
  ;; and child together would send the change twice and the outer patch would replace
  ;; the inner element just updated — the bug the old engine showed as duplicated list
  ;; items.
  (let [presence (atom #{})
        component
        (fn [_]
          (w/fragment "roster"
                      (fn []
                        [:ul {:id "roster"}
                         (mapv (fn [u]
                                 (w/fragment (str "m-" u)
                                             (fn []
                                               (let [on? (w/watch [:presence u]
                                                                  #(contains? @presence u))]
                                                 [:li {:id (str "m-" u)}
                                                  (if on? "on" "off")]))))
                               ["a" "b"])])))
        eng (we/engine {:components {:c component} :render-fn pr-str})
        sent (atom [])
        id (we/connect! eng :c {:params {} :send! #(swap! sent into %)})]
    (we/mount! eng id)
    (reset! sent [])
    (swap! presence conj "b")
    (let [{:keys [patches]} (we/refresh! eng id [:presence "b"])]
      (is (= ["#m-b"] (mapv :selector patches))
          "the child alone; #roster also matched but contains it"))))

(deftest a-handler-returns-topics-and-the-caller-publishes-them
  ;; The event contract, and the reason it is shaped this way. The old engine's handler
  ;; returned a new VIEW, because the view was the state. Here the state lives in the
  ;; application's own atoms, so a handler mutates those and reports which topics to
  ;; invalidate — leaving one invalidation path for both "I did this" and "someone else
  ;; did this". Two paths is how the old engine got a warm and a cold path that could
  ;; disagree.
  (let [typists (atom #{})
        pages (atom {})
        component
        {:render (fn [{:keys [conn-id]}]
                   (w/fragment "root"
                               (fn []
                                 [:div {:id "root"}
                                  (w/fragment "typing"
                                              (fn []
                                                (let [t (w/watch [:typing] #(deref typists))]
                                                  [:div {:id "typing"} (str (sort t))])))
                                  (w/fragment "msgs"
                                              (fn []
                                                (let [n (w/watch [:pages conn-id]
                                                                 #(get @pages conn-id 1))]
                                                  [:ul {:id "msgs"} n])))])))
         :on {:typing (fn [{:keys [params]} {:keys [on?]}]
                        (swap! typists (if on? conj disj) (:user params))
                        [[:typing]])
              :older (fn [{:keys [id]} _]
                       (swap! pages update id (fnil inc 1))
                       [[:pages id]])}}
        eng (we/engine {:components {:c component} :render-fn pr-str})
        sent (atom [])
        a (we/connect! eng :c {:params {:user "amy"} :send! #(swap! sent into %)})
        b (we/connect! eng :c {:params {:user "nick"} :send! #(swap! sent into %)})]
    ;; A server puts the connection id in params; mirror that.
    (swap! (:registry eng) update a assoc-in [:params :conn-id] a)
    (swap! (:registry eng) update b assoc-in [:params :conn-id] b)
    (we/mount! eng a)
    (we/mount! eng b)

    (testing "a shared topic fans out to every subscriber, one fragment each"
      (reset! sent [])
      (let [topics (we/dispatch! eng a :typing {:on? true})]
        (is (= [[:typing]] topics))
        (doseq [t topics, id [a b]] (we/refresh! eng id t))
        (is (= ["#typing" "#typing"] (mapv :selector @sent)))))

    (testing "per-connection state reaches only that connection"
      (reset! sent [])
      (let [topics (we/dispatch! eng a :older nil)]
        (is (= [[:pages a]] topics))
        (doseq [t topics, id [a b]] (we/refresh! eng id t))
        (is (= ["#msgs"] (mapv :selector @sent))
            "nick renders the same component but subscribes to his own :pages topic")))

    (testing "dispatch! pushes nothing itself; publishing is the caller's job"
      (reset! sent [])
      (we/dispatch! eng a :typing {:on? false})
      (is (= [] @sent)))))

(deftest an-unknown-event-throws-rather-than-doing-nothing
  ;; A silent no-op here would look exactly like a handler that ran and changed
  ;; nothing, which is the failure mode this project has been bitten by repeatedly.
  (let [eng (we/engine {:components {:c {:render (fn [_] [:div {:id "x"}]) :on {}}}
                        :render-fn pr-str})
        id (we/connect! eng :c {:params {} :send! (fn [_] nil)})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No handler for event"
                          (we/dispatch! eng id :nope nil)))))

(deftest a-bare-function-component-still-works
  ;; Most components have no events. Requiring a map for all of them would be
  ;; ceremony, so the bare `(fn [params] -> tree)` form stays valid.
  (let [eng (we/engine {:components {:c (fn [_] (w/fragment "x" {:static? true}
                                                            (fn [] [:div {:id "x"} "hi"])))}
                        :render-fn pr-str})
        id (we/connect! eng :c {:params {} :send! (fn [_] nil)})]
    (is (= "[:div {:id \"x\"} \"hi\"]" (:html (we/mount! eng id))))))

(deftest a-child-given-its-data-throws-at-mount
  ;; The dashboard bug, end to end: `job-row` took the job map from the list instead of
  ;; watching `[:job id]`, so each row was a patch target with no dependency. It rendered
  ;; correctly and then froze. Caught at mount rather than by a stale screen.
  (let [rows (atom [{:id 1 :progress 10}])
        bad-row (fn [job]
                  (w/fragment (str "job-" (:id job))
                              (fn [] [:tr {:id (str "job-" (:id job))} (:progress job)])))
        bad (fn [_] (w/fragment "jobs"
                                (fn [] [:tbody {:id "jobs"}
                                        (mapv bad-row (w/watch [:jobs] #(deref rows)))])))
        eng (we/engine {:components {:c bad} :render-fn pr-str})
        id (we/connect! eng :c {:params {} :send! (fn [_] nil)})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\"job-1\" read no topic"
                          (we/mount! eng id))))

  (testing "and the version that reads its own row mounts and patches"
    (let [jobs (atom {1 {:id 1 :progress 10}})
          rows (atom [{:id 1}])
          good-row (fn [jid]
                     (w/fragment (str "job-" jid)
                                 (fn [] [:tr {:id (str "job-" jid)}
                                         (:progress (w/watch [:job jid]
                                                             #(get @jobs jid)))])))
          good (fn [_] (w/fragment "jobs"
                                   (fn [] [:tbody {:id "jobs"}
                                           (mapv (comp good-row :id)
                                                 (w/watch [:jobs] #(deref rows)))])))
          eng (we/engine {:components {:c good} :render-fn pr-str})
          id (we/connect! eng :c {:params {} :send! (fn [_] nil)})]
      (is (contains? (set (:topics (we/mount! eng id))) [:job 1]))
      (swap! jobs assoc-in [1 :progress] 99)
      (let [{:keys [patches]} (we/refresh! eng id [:job 1])]
        (is (= 1 (count patches)))
        (is (= "[:tr {:id \"job-1\"} 99]" (:html (first patches))))))))

(deftest refreshing-an-unknown-connection-is-nil-not-an-error
  ;; A hint can race a disconnect: the id set is snapshotted, and the connection can
  ;; be gone by the time its turn comes. Observed in the spike's log.
  (let [members (atom {}) presence (atom #{})
        {:keys [eng id]} (fixture members presence)]
    (we/disconnect! eng id)
    (is (nil? (we/refresh! eng id [:members "c1"])))))
