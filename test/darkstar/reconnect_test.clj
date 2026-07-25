(ns darkstar.reconnect-test
  "The reconnect path end to end: snapshot out, server dies, snapshot back in,
  view rebuilt. See DESIGN.md §3, §5.1, §5.4.

  This is the project's thesis under test. Everything else built so far is
  roughly what Ripley already does; surviving a disconnect with state intact is
  the part that is supposed to be new."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [remuda.engine :as engine]
            [darkstar.engine :as d*engine]
            [remuda.render :as render]
            [remuda.snapshot :as snapshot]))

(def sopts {:secret "reconnect-test-secret"})
(def dispatch-opts d*engine/dispatch-opts)

(defn- render-str [h]
  (cond
    (nil? h) ""
    (string? h) h
    (and (vector? h) (keyword? (first h)))
    (let [[tag & r] h
          attrs (when (map? (first r)) (first r))
          kids (if (map? (first r)) (next r) r)]
      (str "<" (name tag)
           (when-let [id (:id attrs)] (str " id=\"" id "\""))
           (when-let [v (:value attrs)] (str " value=\"" v "\""))
           ">" (apply str (map render-str kids)) "</" (name tag) ">"))
    (sequential? h) (apply str (map render-str h))
    :else (str h)))

(defn- component
  "A component whose `:mount` reads a mutable store, so a test can change the
  store while the connection is 'down'."
  [store]
  {:state {:draft {:tier :recoverable}
           :tab   {:tier :disposable :default :main}
           :n     {:tier :derived :from #(count (:items %))}}
   :mount (fn [_] {:items (with-meta (:rows @store) {:live/key :id})})
   :render (fn [{:keys [items draft tab] ::engine/keys [id]}]
             (render/boundary
              []
              [:div {:id id}
               (render/boundary [:draft] [:input {:id (str id "-draft") :value draft}])
               [:span (str "tab=" tab)]
               (render/boundary [:items]
                                [:ul {:id (str id "-items")}
                                 (for [i items]
                                   (render/boundary [:items (:id i)]
                                                    [:li {:id (str id "-i-" (:id i))}
                                                     (:text i)]))])]))
   :on {:type (fn [v _ {:keys [text]}] (assoc v :draft text))
        :switch (fn [v _ _] (assoc v :tab :other))}})

(defn- fixture []
  (let [store (atom {:rows [{:id 1 :text "one"} {:id 2 :text "two"}]})
        eng (engine/engine {:components {:c (component store)}
                            :render-fn render-str})]
    [store eng]))

;;; ==========================================================================
;;; The full cycle
;;; ==========================================================================

(deftest a-view-survives-a-disconnect-with-each-tier-handled-correctly
  (let [[store eng] (fixture)
        id1 (engine/connect! eng :c {:send! (fn [_]) :params {:user 7}})]
    (engine/mount! eng id1 {})
    (engine/dispatch! eng id1 :type {:text "half-typed"} dispatch-opts)
    (engine/dispatch! eng id1 :switch nil dispatch-opts)

    (let [signed (snapshot/create sopts (engine/snapshot-data eng id1))]
      ;; The server dies, and the world moves on while it is down.
      (engine/disconnect! eng id1)
      (swap! store update :rows conj {:id 3 :text "added-while-down"})
      (is (nil? (engine/live-context eng id1)) "the old context is gone")

      ;; The browser reconnects, presenting its snapshot.
      (let [verified (snapshot/verify sopts signed)
            snap (:snapshot verified)
            id2 (engine/connect! eng (:component snap)
                                 {:send! (fn [_]) :params (:params snap)})
            html (engine/reconnect! eng id2 {} (:state snap))
            view (:view (engine/live-context eng id2))]
        (is (:ok verified))
        (testing "recoverable state is replayed from the client"
          (is (= "half-typed" (:draft view))))
        (testing "disposable state resets rather than being replayed"
          (is (= :main (:tab view)))
          (is (not= :other (:tab view))))
        (testing "sourced state is re-queried, so changes made while down appear"
          (is (= ["one" "two" "added-while-down"] (mapv :text (:items view))))
          (is (str/includes? html "added-while-down")))
        (testing "derived state is recomputed, not restored"
          (is (= 3 (:n view))))
        (testing "params travel in the snapshot, so a fresh server can remount"
          (is (= {:user 7} (:params (engine/live-context eng id2)))))))))

(deftest reconnect-works-with-no-snapshot
  ;; A client that lost its snapshot, or a first connection. Must degrade to a
  ;; plain mount rather than failing.
  (let [[_ eng] (fixture)
        id (engine/connect! eng :c {:send! (fn [_])})
        html (engine/reconnect! eng id {} nil)
        view (:view (engine/live-context eng id))]
    (is (str/includes? html "<div"))
    (is (nil? (:draft view)) "nothing to replay")
    (is (= :main (:tab view)) "disposable still gets its default")
    (is (= 2 (:n view)) "derived still computed")))

(deftest mount-and-reconnect-agree-when-there-is-nothing-to-restore
  ;; The convergence invariant (§6) at the connection level: with no recoverable
  ;; state, a reconnect must produce exactly what a cold mount produces. If these
  ;; diverge, a reconnect shows the user a different page than a fresh load.
  (let [[_ eng] (fixture)
        id-a (engine/connect! eng :c {:send! (fn [_])})
        id-b (engine/connect! eng :c {:send! (fn [_])})
        mounted (engine/mount! eng id-a {})
        reconnected (engine/reconnect! eng id-b {} nil)]
    ;; Ids differ per context, so compare with them normalised out.
    (is (= (str/replace mounted id-a "ID")
           (str/replace reconnected id-b "ID")))))

;;; ==========================================================================
;;; Trust boundary
;;; ==========================================================================

(deftest a-snapshot-cannot-reinstate-sourced-state
  ;; The layer that matters most: even a validly-signed snapshot must not be able
  ;; to override state that comes from the source of truth.
  (let [[_ eng] (fixture)
        ;; A snapshot the server itself signed, but carrying hostile extras.
        hostile (snapshot/create sopts {:component :c
                                        :params {}
                                        :recoverable {:draft "fine"
                                                      :items [{:id 99 :text "injected"}]
                                                      :admin? true}})
        snap (:snapshot (snapshot/verify sopts hostile))
        id (engine/connect! eng :c {:send! (fn [_])})
        _ (engine/reconnect! eng id {} (:state snap))
        view (:view (engine/live-context eng id))]
    (is (= "fine" (:draft view)) "the declared recoverable field replays")
    (is (= ["one" "two"] (mapv :text (:items view)))
        "sourced state comes from :mount, not the client")
    (is (not (contains? view :admin?))
        "an undeclared key cannot enter the view")))

(deftest a-tampered-snapshot-never-reaches-reconnect
  ;; Verification is the caller's job, so this documents the contract: a failed
  ;; verify yields nil, and reconnect with nil is a plain mount.
  (let [[_ eng] (fixture)
        verified (snapshot/verify sopts "tampered.garbage")
        id (engine/connect! eng :c {:send! (fn [_])})]
    (is (false? (:ok verified)))
    (engine/reconnect! eng id {} (snapshot/recoverable-state verified))
    (is (nil? (:draft (:view (engine/live-context eng id))))
        "a rejected snapshot restores nothing")))

;;; ==========================================================================
;;; Snapshot data
;;; ==========================================================================

(deftest snapshot-data-carries-what-a-rebuild-needs
  (let [[_ eng] (fixture)
        id (engine/connect! eng :c {:send! (fn [_]) :params {:user 7}})]
    (engine/mount! eng id {})
    (engine/dispatch! eng id :type {:text "typed"} dispatch-opts)
    (let [data (engine/snapshot-data eng id)]
      (is (= :c (:component data)))
      (is (= {:user 7} (:params data)))
      (testing "only recoverable fields, so the snapshot stays small"
        (is (= {:draft "typed"} (:recoverable data))))
      (testing "no :basis yet — the Source protocol (§7.1) is unwritten"
        (is (not (contains? data :basis)))))))

(deftest the-snapshot-stays-small
  ;; It rides in a datastar signal, so size is a real constraint (§5.4).
  (let [[_ eng] (fixture)
        id (engine/connect! eng :c {:send! (fn [_]) :params {:user 7}})]
    (engine/mount! eng id {})
    (engine/dispatch! eng id :type {:text "a typical half-finished sentence"}
                      dispatch-opts)
    (let [signed (snapshot/create sopts (engine/snapshot-data eng id))]
      (is (< (count signed) 512)
          (str "snapshot was " (count signed) " bytes"))
      (testing "and it does not contain the sourced collection"
        (is (not (str/includes? signed "one")))))))
