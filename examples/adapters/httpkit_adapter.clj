(ns adapters.httpkit-adapter
  "A second adapter, to test whether the core's seam is actually sufficient.

  Written *before* splitting the core into its own library, because a split fixes
  the boundary in place and a second consumer is the only way to find out whether
  the boundary is in the right spot. If this needed to reach into the engine, or to
  change it, the seam would be wrong.

  Deliberately **no Ring, no zodiac, no reitit** — a bare http-kit server with
  hand-rolled routing. That is the point: if the core can be driven this way, then
  Pedestal, Aleph, or anything else is a matter of writing ~100 lines of glue rather
  than of changing the engine.

  Run:
    clojure -M:soak -m adapters.httpkit-adapter
  then open http://localhost:3200"
  (:require [clojure.java.io :as io]
            [dev.onionpancakes.chassis.core :as chassis]
            [org.httpkit.server :as hk]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as d*hk]
            [darkstar.action :as action]
            [remuda.diff :as diff]
            [remuda.engine :as engine]
            [darkstar.patch :as patch]
            [remuda.pubsub :as pubsub]
            [remuda.render :as render]
            [remuda.source :as source]))

;;; ==========================================================================
;;; A component. Identical in shape to the zodiac one — no framework in sight.
;;; ==========================================================================

(defonce ^{:doc "The 'database'. An atom here; the point is the Source seam."}
  store
  (atom {:counter 0
         :items [{:id 1 :text "written without Ring"}
                 {:id 2 :text "or zodiac, or reitit"}]}))

(def app-source
  (reify source/Source
    (fetch [this q] (source/fetch this q nil))
    (fetch [_ [kind] _basis]
      (case kind
        :counter (:counter @store)
        :items (:items @store)
        nil))
    (basis [_] nil)))

(def counter-component
  {:state {:next-id {:tier :derived
                     :from (fn [{:keys [items]}]
                             (inc (reduce max 0 (map :id items))))}}

   :mount (fn [{:keys [source]}]
            {:counter (source/fetch source [:counter])
             :items (with-meta (vec (source/fetch source [:items]))
                      {:live/key :id})})

   :render (fn [{:keys [counter items] ::engine/keys [id]}]
             (render/boundary
              []
              [:div {:id id}
               [:h1 "second adapter: bare http-kit"]
               [:p "count: "
                (render/boundary [:counter]
                                 [:strong {:id (str id "-count")} counter])]
               [:p [:button {:data-on:click (action/post "/act" :inc)} "increment"]
                " "
                [:button {:data-on:click (action/post "/act" :add)} "add item"]]
               (render/boundary [:items]
                                [:ul {:id (str id "-items")}
                                 (for [i items]
                                   (render/boundary
                                    [:items (:id i)]
                                    [:li {:id (str id "-i-" (:id i))} (:text i)]))])]))

   :on {:inc (fn [view _ctx _args]
               (swap! store update :counter inc)
               (assoc view :counter (:counter @store)))
        :add (fn [{:keys [next-id] :as view} _ctx _args]
               (swap! store update :items conj {:id next-id
                                                :text (str "item " next-id)})
               (assoc view :items (with-meta (vec (:items @store))
                                    {:live/key :id})))}

   :subscribe (fn [_ctx] [[:app]])})

;;; ==========================================================================
;;; Wiring — this is the whole adapter
;;; ==========================================================================

(defonce registry (atom {}))
(defonce subscriptions (pubsub/registry))
(def bus (pubsub/local-pubsub))

(def eng
  (engine/engine {:components {:counter #'counter-component}
                  :render-fn chassis/html
                  :registry registry}))

(def ^:private dispatch-opts
  {:diff-fn diff/diff
   :patches-fn patch/ops->patches
   ;; Required: see darkstar.engine/dispatch-opts.
   :retarget-fn patch/path->selector})

(def ^:private mode->datastar
  {:outer d*/pm-outer :inner d*/pm-inner :remove d*/pm-remove
   :append d*/pm-append :prepend d*/pm-prepend
   :before d*/pm-before :after d*/pm-after :replace d*/pm-replace})

(defn- send-instructions!
  [sse-gen instructions]
  (doseq [{:keys [mode selector html move]} instructions]
    (cond
      (= :remove mode)
      (d*/patch-elements! sse-gen "" {d*/selector selector
                                      d*/patch-mode d*/pm-remove})
      move
      (do (d*/patch-elements! sse-gen "" {d*/selector move
                                          d*/patch-mode d*/pm-remove})
          (d*/patch-elements! sse-gen html {d*/selector selector
                                            d*/patch-mode (mode->datastar mode)}))
      html
      (d*/patch-elements! sse-gen html {d*/selector selector
                                        d*/patch-mode (mode->datastar mode)}))))

;;; The flush loop, same shape as the zodiac extension's. Coalescing lives in the
;;; core; this only has to drain and refresh.
(defonce flusher
  (delay
    (doto (Thread.
           (fn []
             (while true
               (Thread/sleep 100)
               (try
                 (let [{:keys [contexts]} (pubsub/flush-dirty! subscriptions)]
                   (doseq [id contexts]
                     (engine/refresh! eng id {:source app-source} dispatch-opts)))
                 (catch Exception _ nil))))
           "httpkit-adapter-flusher")
      (.setDaemon true)
      (.start))))

(defn- page
  []
  (chassis/html
   [chassis/doctype-html5
    [:html
     [:head [:script {:type "module" :src "/datastar.js"}]]
     [:body {:data-init "@get('/live')"}
      [:div {:id "live-root"} "connecting..."]]]]))

(defn- live-handler
  [request]
  (d*hk/->sse-response
   request
   {d*hk/on-open
    (fn [sse-gen]
      ;; Event-driven: on-open returns and http-kit holds the connection. No thread
      ;; to park, which is the structural difference the soak test measured —
      ;; 17,348 connections on 17 threads.
      (let [id (engine/connect! eng :counter
                                {:send! #(send-instructions! sse-gen %)
                                 :close! #(d*/close-sse! sse-gen)})]
        (pubsub/subscribe-context! subscriptions bus id [[:app]])
        (d*/patch-elements! sse-gen (engine/mount! eng id {:source app-source})
                            {d*/selector "#live-root"
                             d*/patch-mode d*/pm-outer})
        (d*/patch-signals! sse-gen (str "{\"liveId\":\"" id "\"}"))))}))

(defn- act-handler
  [request]
  (let [q (or (:query-string request) "")
        event (keyword (second (re-find #"event=([a-z-]+)" q)))
        ;; Any live context will do for a demo; a real app carries its own id.
        id (ffirst @registry)]
    (if (and id event (engine/live-context eng id))
      (do (engine/dispatch! eng id event nil dispatch-opts)
          ;; The mutation is shared state, so tell every viewer.
          (pubsub/publish! bus [:app])
          {:status 204})
      {:status 409 :body "no live context"})))

(defn handler
  "Hand-rolled routing. No reitit, no Ring middleware — the point is that the core
  needs neither."
  [{:keys [uri] :as request}]
  (case uri
    "/" {:status 200 :headers {"content-type" "text/html"} :body (page)}
    "/live" (live-handler request)
    "/act" (act-handler request)
    "/datastar.js" {:status 200
                    :headers {"content-type" "text/javascript"}
                    :body (slurp (io/resource "datastar.js"))}
    {:status 404 :body "not found"}))

(defn -main
  [& _]
  @flusher
  (hk/run-server handler {:port 3200})
  (println "second adapter on http://localhost:3200")
  @(promise))
