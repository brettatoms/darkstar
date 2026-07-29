(ns darkstar.sse
  "The transport half: patches onto a Datastar SSE stream, and the connection
  lifecycle around it.

  ## What this absorbs, and why

  Four things recurred across every application built on `darkstar.live`, and each was
  written by hand every time:

  1. **Translating `{:selector :mode :html}` into `patch-elements!` calls.** Written four
     times in four apps — and *inconsistently*: two handled all eight patch modes and two
     handled only `:inner`/`:outer`, so a `:remove` patch would have been silently
     mis-applied as an `:outer` replacement.
  2. **The open/close lifecycle.** `connect!`, write the connection id back into params,
     `mount!`, subscribe to the topics the render reported, push the initial HTML, and on
     close unsubscribe and `disconnect!`. Structurally identical everywhere.
  3. **Sharing the connection id between `on-open` and `on-close`.** They are separate
     closures, so every app wrote the same `promise` and `(deref id* 1000 nil)`.
  4. **Reading the Datastar payload from a POST.** Two strategies were needed for two
     stacks and both cost real debugging time — see `read-payload`.

  ## What it does not do

  **Name a server.** This namespace requires only the Datastar SDK, so it works with
  Jetty, http-kit or anything else. The caller supplies the SSE response wrapper
  (`->sse-response`) and passes `on-open`/`on-close` through; `handlers` builds those two
  functions and nothing more.

  It also does not own subscriptions. `:subscribe!` and `:unsubscribe!` are functions you
  supply, because how subscriptions are stored is a real choice: `darkstar.pubsub` gives
  coalescing, and an application under heavy parallel fan-out may want a
  `ConcurrentHashMap` index instead. Both were measured; neither is right for everyone."
  (:require [clojure.string :as str]
            [darkstar.live :as live]
            [starfederation.datastar.clojure.api :as d*]))

;;; ==========================================================================
;;; Patches onto a stream
;;; ==========================================================================

(def mode->datastar
  "Engine patch modes to Datastar patch modes.

  All eight, because an incomplete map fails silently. Two of the four applications that
  wrote this by hand covered only `:inner` and `:outer`, which turns a `:remove` into an
  `:outer` replacement with the removed element's own HTML — no error, wrong DOM."
  {:outer d*/pm-outer
   :inner d*/pm-inner
   :remove d*/pm-remove
   :append d*/pm-append
   :prepend d*/pm-prepend
   :before d*/pm-before
   :after d*/pm-after
   :replace d*/pm-replace})

(defn send-patches!
  "Applies engine patches to an SSE stream.

  A patch is `{:selector :mode :html}`; `:mode` defaults to `:outer`. A `:remove` sends
  empty content, because Datastar removes the target and any HTML would be discarded.

  Throws on an unknown mode rather than defaulting. A typo'd mode silently applied as
  `:outer` is the kind of failure that shows up as a corrupted DOM three screens later."
  [sse-gen patches]
  (doseq [{:keys [selector mode html]} patches]
    (let [mode (or mode :outer)
          dsm (mode->datastar mode)]
      (when-not dsm
        (throw (ex-info "Unknown patch mode"
                        {:mode mode :known (set (keys mode->datastar))})))
      (d*/patch-elements! sse-gen
                          (if (= :remove mode) "" (or html ""))
                          {d*/selector selector d*/patch-mode dsm}))))

(defn sender
  "A `:send!` function for `darkstar.live/connect!`, bound to one stream."
  [sse-gen]
  (fn [patches] (send-patches! sse-gen patches)))

;;; ==========================================================================
;;; Reading a payload back
;;; ==========================================================================

(defn read-payload
  "The JSON payload from a Datastar action request, as a map with keyword keys.

  `parse-json` is a `(fn [string] -> map)` — your own JSON reader, so this namespace
  takes no JSON dependency.

  ## Two shapes, because two stacks deliver it differently

  - **`:body-params`**, when middleware has already parsed the body. Ring apps running
    muuntaja are here, and it must be checked FIRST: muuntaja has consumed the stream by
    the time a handler runs, so asking the SDK for it throws `EOFException`. That
    exception being swallowed produced signals of `nil` and a 409 on every action, which
    reads like a missing connection rather than a drained body.
  - **the raw body**, when nothing has parsed it. `d*/get-signals` returns a *stream*
    here — a Jetty `HttpInput`, not a map and not a string — so code testing `map?` then
    `string?` matches neither and silently yields `nil`.

  Returns `nil` when there is no payload, and **throws** when there is one that cannot be
  parsed. Swallowing that distinction is what made both failures above invisible."
  [request parse-json]
  (or (:body-params request)
      (when-let [raw (d*/get-signals request)]
        (let [s (if (string? raw) raw (slurp raw))]
          (when-not (str/blank? s)
            (parse-json s))))))

;;; ==========================================================================
;;; The connection lifecycle
;;; ==========================================================================

(defn handlers
  "Builds `{:on-open :on-close}` for one SSE connection.

  Pass them to whichever `->sse-response` your server adapter provides — this namespace
  names no server:

      (d*ring/->sse-response
       request
       (let [{:keys [on-open on-close]} (sse/handlers opts)]
         {d*ring/on-open on-open
          d*ring/on-close on-close}))

  Required:

  - `:engine`     a `darkstar.live` engine
  - `:component`  the component name to mount
  - `:root`       the selector the initial render replaces, e.g. `\"#app\"`

  Optional:

  - `:params`       params for the component. `:conn-id` is added automatically.
  - `:subscribe!`   `(fn [id topics])`, called with the topics the render read
  - `:unsubscribe!` `(fn [id])`, called on close
  - `:on-mount`     `(fn [id])`, after the initial push. Publish arrival hints here —
                    after, so this connection hears its own.
  - `:on-closed`    `(fn [id])`, after unsubscribe and disconnect
  - `:expose-id?`   push `window.__darkstarId`, so a POST can name its connection.
                    Defaults true; a POST is a separate request with no association to
                    the stream.

  ## Ordering is load-bearing

  Mount, then subscribe, then announce. Publishing before this connection is subscribed
  and mounted means its own arrival hint reaches every viewer except itself — a bug this
  project hit twice, where one tab showed a joiner and another did not."
  [{:keys [engine component root params subscribe! unsubscribe!
           on-mount on-closed expose-id?]
    :or {expose-id? true}}]
  {:pre [(some? engine) (some? component) (string? root)]}
  ;; `on-open` and `on-close` are separate closures, so the id has to be shared. A
  ;; promise rather than an atom: written once, and `on-close` must not proceed before
  ;; `on-open` has produced it.
  (let [id* (promise)]
    {:on-open
     (fn [sse-gen]
       (let [id (live/connect! engine component {:send! (sender sse-gen)
                                                 :params (or params {})})]
         (deliver id* id)
         ;; `:conn-id` cannot be passed to `connect!` — it IS the id `connect!` returns.
         ;; Written back before the first render, because a component keying
         ;; per-connection state needs it.
         (swap! (:registry engine) update id assoc-in [:params :conn-id] id)
         (let [{:keys [html topics]} (live/mount! engine id)]
           (when subscribe! (subscribe! id topics))
           (d*/patch-elements! sse-gen html
                               {d*/selector root d*/patch-mode d*/pm-outer}))
         (when expose-id?
           (d*/execute-script! sse-gen
                               (str "window.__darkstarId=" (pr-str id) ";")))
         (when on-mount (on-mount id))
         nil))

     :on-close
     (fn [& _]
       ;; A bounded wait, not an indefinite one: if `on-open` never delivered, the
       ;; connection failed before it had an id and there is nothing to clean up.
       (when-let [id (deref id* 1000 nil)]
         (when unsubscribe! (unsubscribe! id))
         (live/disconnect! engine id)
         (when on-closed (on-closed id))))}))
