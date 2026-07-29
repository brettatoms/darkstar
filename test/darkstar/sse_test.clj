(ns darkstar.sse-test
  "Tests for the transport half.

  Each of these covers something an application got wrong by hand — the reason the code
  exists is that four apps wrote it four times and two of them wrote it incompletely."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [darkstar.live :as live]
            [darkstar.sse :as sse]
            [darkstar.watch :as w]
            [starfederation.datastar.clojure.api :as d*]))

(defn- capturing
  "Runs `f` with `patch-elements!` and `execute-script!` recorded rather than sent.
  Returns the recorded calls."
  [f]
  (let [log (atom [])]
    (with-redefs [d*/patch-elements!
                  (fn [_gen html opts]
                    (swap! log conj {:kind :patch
                                     :selector (get opts d*/selector)
                                     :mode (get opts d*/patch-mode)
                                     :html html}))
                  d*/execute-script!
                  (fn [_gen script] (swap! log conj {:kind :script :script script}))]
      (f))
    @log))

;;; ==========================================================================
;;; Patch translation
;;; ==========================================================================

(deftest every-patch-mode-is-mapped
  ;; The reason this namespace exists. Two of four applications hand-wrote this map with
  ;; only :inner and :outer, which turns a :remove into an :outer replacement — no error,
  ;; wrong DOM.
  (is (= #{:outer :inner :remove :append :prepend :before :after :replace}
         (set (keys sse/mode->datastar))))
  (testing "and each maps to a real Datastar mode, not nil"
    (is (every? some? (vals sse/mode->datastar)))))

(deftest an-unknown-mode-throws-rather-than-defaulting
  ;; Defaulting a typo to :outer is the failure that shows up as a corrupted DOM
  ;; several screens later, with nothing pointing back at the cause.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown patch mode"
                        (sse/send-patches! nil [{:selector "#x" :mode :bogus}]))))

(deftest a-missing-mode-defaults-to-outer
  (let [[call] (capturing #(sse/send-patches! :gen [{:selector "#x" :html "<p>hi</p>"}]))]
    (is (= "#x" (:selector call)))
    (is (= d*/pm-outer (:mode call)))
    (is (= "<p>hi</p>" (:html call)))))

(deftest a-remove-sends-no-content
  ;; Datastar removes the target, so any HTML would be discarded. Sending it anyway is
  ;; harmless but misleading to anyone reading the wire.
  (let [[call] (capturing
                #(sse/send-patches! :gen [{:selector "#gone" :mode :remove
                                           :html "<p>ignored</p>"}]))]
    (is (= d*/pm-remove (:mode call)))
    (is (= "" (:html call)))))

(deftest nil-html-does-not-become-the-string-nil
  (let [[call] (capturing #(sse/send-patches! :gen [{:selector "#x" :html nil}]))]
    (is (= "" (:html call)))))

;;; ==========================================================================
;;; Payload reading
;;; ==========================================================================

(deftest body-params-wins-when-middleware-already-parsed
  ;; Must be checked FIRST. Muuntaja consumes the stream before a handler runs, so
  ;; asking the SDK afterwards throws EOFException — swallowed, that produced nil
  ;; signals and a 409 on every action, which reads like a missing connection.
  (is (= {:job 1}
         (sse/read-payload {:body-params {:job 1}} edn/read-string))))

(deftest a-raw-stream-body-is-read
  ;; `d*/get-signals` returns a STREAM when nothing has parsed the body — a Jetty
  ;; HttpInput, not a map and not a string. Code testing `map?` then `string?` matched
  ;; neither and silently yielded nil.
  (is (= {:job 2}
         (sse/read-payload
          {:body (java.io.ByteArrayInputStream. (.getBytes "{:job 2}"))
           :headers {"content-type" "application/json"}}
          edn/read-string))))

(deftest an-empty-body-is-nil-not-an-exception
  ;; Datastar sends `{}` when an action passes no payload, and a blank body when the
  ;; request carries none at all. Neither is an error.
  (is (nil? (sse/read-payload
             {:body (java.io.ByteArrayInputStream. (.getBytes ""))
              :headers {"content-type" "application/json"}}
             edn/read-string))))

;;; ==========================================================================
;;; The connection lifecycle
;;; ==========================================================================

(defn- test-engine []
  (let [data (atom {:n 1})]
    {:data data
     :engine (live/engine
              {:components
               {:app (fn [{:keys [conn-id]}]
                       (w/fragment "app"
                                   (fn [] [:div {:id "app"}
                                           (w/watch [:n] #(:n @data))
                                           conn-id])))}
               :render-fn pr-str})}))

(deftest the-lifecycle-runs-in-the-order-that-matters
  ;; Mount, subscribe, then announce. Publishing before this connection is subscribed
  ;; and mounted means its own arrival hint reaches every viewer except itself — a bug
  ;; this project hit twice, where one tab showed a joiner and another did not.
  (let [{:keys [engine]} (test-engine)
        events (atom [])
        {:keys [on-open on-close]}
        (sse/handlers {:engine engine :component :app :root "#app"
                       :subscribe! (fn [_ topics] (swap! events conj [:sub (vec topics)]))
                       :unsubscribe! (fn [_] (swap! events conj [:unsub]))
                       :on-mount (fn [_] (swap! events conj [:mounted]))
                       :on-closed (fn [_] (swap! events conj [:closed]))
                       :expose-id? false})
        patches (capturing #(on-open :gen))]

    (testing "subscribed to what the render read, before on-mount fired"
      (is (= [[:sub [[:n]]] [:mounted]] @events)))

    (testing "the initial render went to the root selector"
      (is (= ["#app"] (mapv :selector patches))))

    (reset! events [])
    (on-close)
    (testing "and close unsubscribes before reporting closed"
      (is (= [[:unsub] [:closed]] @events)))
    (is (empty? @(:registry engine)) "the context is gone")))

(deftest conn-id-reaches-the-component-without-clobbering-params
  ;; `:conn-id` cannot be passed to `connect!` — it IS the id `connect!` returns — so
  ;; every application reached into the engine registry to write it back. Doing that in
  ;; one place is half the reason `handlers` exists.
  (let [{:keys [engine]} (test-engine)
        {:keys [on-open]} (sse/handlers {:engine engine :component :app :root "#app"
                                         :params {:tenant "acme"}
                                         :expose-id? false})
        patches (capturing #(on-open :gen))
        id (first (keys @(:registry engine)))]
    (is (= id (get-in @(:registry engine) [id :params :conn-id])))
    (is (= "acme" (get-in @(:registry engine) [id :params :tenant]))
        "the caller's own params survive")
    (is (str/includes? (:html (first patches)) id)
        "and the component actually rendered with it")))

(deftest the-connection-id-is-exposed-to-the-client-by-default
  ;; A POST is a separate request with no association to the SSE stream, so the client
  ;; has to be able to name its connection.
  (let [{:keys [engine]} (test-engine)
        {:keys [on-open]} (sse/handlers {:engine engine :component :app :root "#app"})
        calls (capturing #(on-open :gen))
        script (first (filter #(= :script (:kind %)) calls))]
    (is (some? script))
    (is (str/starts-with? (:script script) "window.__darkstarId="))))

(deftest closing-a-connection-that-never-opened-is-safe
  ;; `on-close` can fire for a connection that failed before it had an id. A bounded
  ;; wait rather than an indefinite one, and nothing to clean up.
  (let [{:keys [engine]} (test-engine)
        {:keys [on-close]} (sse/handlers {:engine engine :component :app :root "#app"})]
    (is (nil? (on-close)))))

(deftest optional-callbacks-are-genuinely-optional
  ;; The minimum viable call: an engine, a component, a root.
  (let [{:keys [engine]} (test-engine)
        {:keys [on-open on-close]}
        (sse/handlers {:engine engine :component :app :root "#app"})]
    (is (seq (capturing #(on-open :gen))))
    (is (nil? (on-close)))))
