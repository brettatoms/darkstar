(ns slice
  "A vertical slice: one component, bare Ring, real
  browser, real datastar.

  Not part of the library. This lives under `dev/` and the `:slice` alias so the
  core stays dependency-free — 4 is enforced by the dependency
  graph, not by intent.

  Run:
    clojure -M:slice -m slice
  then open http://localhost:3000

  What this is for: finding out where the design is wrong. In particular the
  seam of rendering one boundary, and whether the engine's transport-agnostic
  `send!` really is enough to drive datastar."
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [dev.onionpancakes.chassis.core :as chassis]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.ring :as d*ring]
            [darkstar.action :as action]
            [remuda.diff :as diff]
            [remuda.engine :as engine]
            [darkstar.patch :as patch]
            [remuda.render :as render]
            [remuda.snapshot :as snapshot])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;;; ==========================================================================
;;; The component
;;; ==========================================================================
;;; A counter plus a keyed todo list, chosen so the slice exercises scalar
;;; changes, inserts, deletes and reorders — everything the diff engine emits.

(defn- keyed [items]
  (with-meta (vec items) {:live/key :id}))

(def ^:private act-path
  "The dispatch route, named once.

  The path belongs to whoever mounted the route, so `action/post` takes it
  as an argument rather than hardcoding it. Here that owner is `handler` below; a
  real adapter reads it from its own route config."
  "/act")

(def counter
  {;; Only the EXCEPTION is declared. :count and :items are authoritative by
   ;; default, so they need no entry. :next-id is :derived because it is a
   ;; function of :items — which also keeps it out of the diff, so adding an item
   ;; no longer widens the patch to a whole-component re-render.
   :state {:draft   {:tier :recoverable}   ; survives a restart, via the snapshot
           :next-id {:tier :derived
                     :from (fn [{:keys [items]}]
                             (inc (reduce max 0 (map :id items))))}}

   :mount
   (fn [_ctx]
     {:count 0
      :draft ""
      :items (keyed [{:id 1 :text "write a diff engine"}
                     {:id 2 :text "prove it in a browser"}])})

   ;; ONE render function. Boundaries are marked inline, so the engine discovers
   ;; both the patchable regions and their ids from this single pass. There is no
   ;; :render-at duplicating this markup and no :boundaries set to keep in sync.
   :render
   (fn [{:keys [count items draft] ::engine/keys [id]}]
     (render/boundary []
                      [:div {:id id}
                       [:h1 "darkstar slice"]
                       [:p "count: "
                        (render/boundary [:count] [:strong {:id (str id "-count")} count])]
                       ;; A :recoverable field. Type here, restart the server,
                       ;; reload: the text comes back while :count — sourced, and
                       ;; mounted at 0 — does not.
                       [:p (render/boundary
                            [:draft]
                            [:input {:id (str id "-draft")
                                     :value draft
                                     :size 40
                                     :placeholder "type here, then restart the server"
                                     ;; No data-bind: binding an input to a signal
                                     ;; makes datastar overwrite the server-rendered
                                     ;; value with the signal's (empty) value on
                                     ;; load, which clobbers a restored draft. The
                                     ;; handler reads evt.target.value instead.
                                     ;;
                                     ;; `action/raw` because this arg is a client
                                     ;; expression, not data — and note the
                                     ;; encodeURIComponent is gone: the value now
                                     ;; rides a JSON body, so there is no query
                                     ;; string to encode it for.
                                     :data-on:input
                                     (action/post act-path :draft
                                                  {:text (action/raw "evt.target.value")})}])]
                       [:div
                        [:button {:data-on:click (action/post act-path :inc)} "increment"]
                        [:button {:data-on:click (action/post act-path :add)} "add item"]
                        [:button {:data-on:click (action/post act-path :reverse)} "reverse"]]
                       (render/boundary [:items]
                                        [:ul {:id (str id "-items")}
                                         (for [i items]
                                           (render/boundary [:items (:id i)]
                                                            [:li {:id (str id "-items-" (:id i))}
                                                             (:text i)
                                                             [:button {:data-on:click
                                                                       (action/post act-path :remove
                                                                                    {:id (:id i)})}
                                                              " x"]]))])]))

   :on
   {:inc (fn [view _ctx _args] (update view :count inc))

    :draft (fn [view _ctx {:keys [text]}] (assoc view :draft (or text "")))

    :add (fn [{:keys [next-id] :as view} _ctx _args]
           (-> view
               (update :items #(with-meta
                                 (conj % {:id next-id
                                          :text (str "item " next-id)})
                                 (meta %)))
               (assoc :next-id (inc next-id))))

    :remove (fn [view _ctx {:keys [id]}]
              (update view :items
                      #(with-meta (filterv (fn [i] (not= id (:id i))) %) (meta %))))

    :reverse (fn [view _ctx _args]
               (update view :items #(with-meta (vec (reverse %)) (meta %))))}})

;;; ==========================================================================
;;; Transport: engine instructions -> datastar
;;; ==========================================================================
;;; This is the ONLY place that knows about datastar or a server. The engine
;;; hands over instructions; this turns them into SSE events. Keeping it this
;;; small is the goal — swapping Jetty for http-kit should touch
;;; nothing above this line.

(def ^:private mode->datastar
  {:outer d*/pm-outer
   :inner d*/pm-inner
   :remove d*/pm-remove
   :append d*/pm-append
   :prepend d*/pm-prepend
   :before d*/pm-before
   :after d*/pm-after
   :replace d*/pm-replace})

(defn- send-instructions!
  "Applies engine instructions to a datastar SSE connection.

  Datastar has no move primitive — `patch-elements` only inserts HTML — so the
  engine renders HTML for moves too and this layer applies a move as
  remove-then-insert. See the `move` branch for what the browser actually did
  when the remove was omitted."
  [sse-gen instructions]
  (doseq [{:keys [mode selector html move]} instructions]
    (cond
      (= :remove mode)
      (d*/patch-elements! sse-gen "" {d*/selector selector
                                      d*/patch-mode d*/pm-remove})

      ;; A move is remove-then-insert. Verified in the browser: inserting the
      ;; element's HTML with mode=before does NOT relocate the existing element.
      ;; Idiomorph morphs by id *within* the target it is handed, so a positional
      ;; insert simply creates a second element — reversing [1 2] produced
      ;; [2 1 2], with two nodes sharing id items-2.
      ;;
      ;; Removing first makes the insert unambiguous. Ordering matters: the
      ;; anchor must still exist when the insert runs, which the diff engine's
      ;; right-to-left move ordering guarantees.
      move
      (do (d*/patch-elements! sse-gen "" {d*/selector move
                                          d*/patch-mode d*/pm-remove})
          (d*/patch-elements! sse-gen html
                              {d*/selector selector
                               d*/patch-mode (mode->datastar mode)}))

      html
      (d*/patch-elements! sse-gen html {d*/selector selector
                                        d*/patch-mode (mode->datastar mode)}))))

;;; ==========================================================================
;;; Wiring
;;; ==========================================================================

(defonce ^{:doc "Held outside the engine so it survives namespace reload."}
  registry
  (atom {}))

(defonce ^{:doc "id -> CountDownLatch keeping each SSE handler thread parked."}
  latches
  (atom {}))

(defonce ^{:doc "id -> sse generator, so an action can refresh the snapshot."}
  generators
  (atom {}))

(def eng
  (engine/engine {:components {:counter counter}
                  :render-fn chassis/html
                  :registry registry}))

(def ^:private dispatch-opts
  {:diff-fn diff/diff
   :patches-fn patch/ops->patches
   ;; Required, not optional: without it a patch the engine widens after
   ;; translation keeps its child selector while carrying root HTML.
   :retarget-fn patch/path->selector})

(defn- page
  "First load: full HTML with datastar, plus an SSE connection request."
  []
  (chassis/html
   [chassis/doctype-html5
    [:html
     [:head
      [:title "darkstar slice"]
      ;; Served locally rather than from d*/CDN-url so the slice works without
      ;; external network access.
      [:script {:type "module" :src "/datastar.js"}]]
     ;; Reconnect uses datastar's own action, but re-invoked by us rather than by
     ;; datastar's automatic retry.
     ;;
     ;; The bug this avoids, verified rather than guessed: datastar CAPTURES THE
     ;; REQUEST URL when an action first runs and replays that identical URL on
     ;; every retry. After a server restart, every retry carried the snapshot the
     ;; signal held at page load — an empty draft — while the DOM still showed the
     ;; typed text. `allIdentical: true` across three retries. The server restored
     ;; empty, pushed empty back, and the good snapshot was destroyed. A frozen URL
     ;; cannot carry state that changes after load.
     ;;
     ;; Fixes:
     ;;  1. `_recovery` is seeded from sessionStorage at load, and written back only
     ;;     when the server sends a NEWER one (the guard below), so a stale replay
     ;;     cannot overwrite a good value.
     ;;  2. We re-issue @get ourselves on disconnect, which rebuilds the URL from
     ;;     the CURRENT signal value.
     [:body {:data-signals "{_recovery: sessionStorage.getItem('_recovery') || '', _connected: false}"
             ;; Persist only non-empty snapshots. The stale-replay case sends an
             ;; empty one; refusing it is what keeps recovery intact.
             :data-effect (str "$_recovery && $_recovery.length > 40 "
                               "&& sessionStorage.setItem('_recovery', $_recovery)")
             ;; datastar dispatches datastar-fetch events on the document; the
             ;; finished/error phases mean the stream is gone, so clear the gate.
             :data-on:datastar-fetch__document
             "$_connected = !['finished','error','aborted'].includes(evt.detail?.type)"
             :data-init (str "@get('/live', {filterSignals: "
                             "{include: /^_recovery$/, exclude: /^$/}, "
                             "retry: 'none'}); "
                             "setInterval(() => { if (!$_connected) "
                             "@get('/live', {filterSignals: "
                             "{include: /^_recovery$/, exclude: /^$/}, "
                             "retry: 'none'}) }, 1000)")}
      [:div {:id "live-root"} "connecting..."]]]]))

(def ^:private snapshot-opts
  ;; A real app takes this from config. Fixed here so a restart of THIS process
  ;; keeps verifying snapshots issued before it — which is exactly the
  ;; deploy-survival case the slice is meant to demonstrate.
  {:secret "slice-demo-secret-do-not-use-in-production"})

(defn- push-snapshot!
  "Sends the recovery snapshot to the browser as an `_`-prefixed signal.

  The underscore matters: datastar excludes `_`-prefixed signals from ordinary
  action requests, so the snapshot costs nothing per interaction and is
  asked for explicitly only when reconnecting."
  [sse-gen id]
  (d*/patch-signals!
   sse-gen
   (charred/write-json-str
    {:_recovery (snapshot/create snapshot-opts (engine/snapshot-data eng id))})))

(defn- live-handler
  "Opens the SSE stream, builds the view, and parks.

  Handles both first connection and reconnect. A browser that already holds a
  `_recovery` signal sends it with the request; if it verifies, its recoverable
  state is replayed over a fresh mount. If it does not verify — a
  rotated secret, a tampered value — this degrades to a plain mount rather than
  failing, since a broken snapshot should cost the user their draft text, not
  their page.

  The parking is the point: the datastar Ring adapter closes the connection when
  a sync handler returns, so a long-lived LiveView connection must block. That
  blocking thread is what the soak test measured — an event-driven
  server would not need it."
  [request]
  (let [;; Datastar sends non-underscore signals automatically; the recovery
        ;; signal is requested explicitly by the client's reconnect action.
        incoming (try (some-> (d*/get-signals request)
                              (charred/read-json :key-fn keyword))
                      (catch Exception _ nil))
        verified (some->> (:_recovery incoming)
                          (snapshot/verify snapshot-opts))
        snap (when (:ok verified) (:snapshot verified))]
    (d*ring/->sse-response
     request
     {d*ring/on-open
      (fn [sse-gen]
        (let [id (engine/connect! eng (or (:component snap) :counter)
                                  {:send! #(send-instructions! sse-gen %)
                                   :close! #(d*/close-sse! sse-gen)
                                   :params (or (:params snap) {})})
              latch (CountDownLatch. 1)
              html (engine/reconnect! eng id {} (:state snap))]
          (swap! latches assoc id latch)
          (swap! generators assoc id sse-gen)
          (d*/patch-elements! sse-gen html
                              {d*/selector "#live-root"
                               d*/patch-mode d*/pm-outer})
          ;; _connected gates the client's reconnect interval, so it only
          ;; re-issues @get when there is genuinely no live stream.
          (d*/patch-signals! sse-gen
                             (str "{\"liveId\":\"" id "\",\"_connected\":true}"))
          (push-snapshot! sse-gen id)
          (.await latch 1 TimeUnit/HOURS)
          (engine/disconnect! eng id)
          (swap! latches dissoc id)
          (swap! generators dissoc id)))})))

(defn- act-handler
  "Runs one interaction.

  Everything — the event, the live id and the args — arrives in one JSON body,
  because the render side builds actions with `darkstar.action` and datastar
  sends a `payload` as the request body. `d*/get-signals` extracts it.

  Args need **no coercion**, since JSON carries their types. This handler
  used to do

      (assoc :id (parse-long (get params \"id\")))

  reading args from the query string with a hardcoded int parse. A string arg
  became nil silently, and nothing but an integer could round-trip at all. JSON
  carries the type, so the payload is passed through as it arrived."
  [request]
  (let [signals (try (some-> (d*/get-signals request)
                             (#(charred/read-json % :key-fn keyword)))
                     (catch Exception _ nil))
        event (some-> (:event signals) name keyword)
        live-id (:liveId signals)
        id (or live-id (ffirst @registry))
        args (dissoc signals :liveId :event)]
    (if-let [_ctx (engine/live-context eng id)]
      (do (engine/dispatch! eng id event args dispatch-opts)
          ;; Refresh the snapshot so recoverable state stays current. Cheap: only
          ;; :recoverable fields are in it, and it rides an underscore signal.
          (when-let [gen (get @generators id)]
            (push-snapshot! gen id))
          {:status 204})
      {:status 409 :body "no live context"})))

(defn handler
  [{:keys [uri] :as request}]
  (case uri
    "/" {:status 200
         :headers {"content-type" "text/html"}
         :body (page)}
    "/live" (live-handler request)
    "/act" (act-handler request)
    "/datastar.js" {:status 200
                    :headers {"content-type" "text/javascript"}
                    :body (slurp (io/resource "datastar.js"))}
    {:status 404 :body "not found"}))

(def app (wrap-params handler))

(defn -main
  [& _]
  ;; : virtual threads, with maxConcurrentTasks raised. Left here at the
  ;; edge rather than inside the engine, since server config is adapter
  ;; territory.
  (println "slice on http://localhost:3000")
  (jetty/run-jetty app {:port 3000
                        :join? true
                        :thread-pool (doto (org.eclipse.jetty.util.thread.VirtualThreadPool.)
                                       (.setMaxConcurrentTasks 10000))}))
