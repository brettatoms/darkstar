(ns darkstar.live
  "Connections, and the fragments each one is showing.

  This is the runtime half of `darkstar.watch`: `watch` records which fragments read
  which topics, and this holds one such recording per connection so that a hint can be
  turned into the narrowest set of patches for that viewer.

  It began as an experiment beside an older view-and-diff engine. That engine is gone —
  it addressed regions by *view path* and resolved paths to element ids, and the
  path-to-id translation was where five of seven live-children bugs lived — so this is
  no longer an alternative to anything.

  ## The whole model

  A component is **one function** of params returning a tree, reading through
  `watch`. There is no view map, no `:mount`, no `:subscribe`, no diff.

      (defn row [{:keys [channel-id username]}]
        (fragment (member-id username)
          (fn []
            (let [online? (watch [:presence channel-id username]
                                 #(presence/online? channel-id username))]
              [:li {:id (member-id username)} …]))))

  Connect renders it once, recording which fragments read which topics. A hint then
  re-renders only the fragments that read that topic, and each fragment's id is its
  own patch target — so nothing derives an id and nothing can derive one wrongly.

  ## What the browser said

  Verified against two real browser tabs, checking computed styles rather than
  server-side values: a member joining appears in the other viewer immediately, and a
  member whose heartbeat lapses turns grey in the other viewer without a reload —
  patched one row at a time, not as a whole list. That last case is the bug that took
  nine fixes in the framework version.

  It did not work first time. Two defects, both worth recording because neither was
  visible headlessly:

  1. **A dependency set is data, so it changes.** Subscribing only at mount meant a
     viewer never heard about a member who joined later. See `refresh!`.
  2. **Jetty must run in async mode** (`:async? true`, `:async-timeout 0`), or an SSE
     response is torn down when `on-open` returns, and later writes to it report
     success while the client receives nothing.

  The second is a transport property with nothing to do with this namespace, but it
  cost an hour of suspecting this namespace, so it is written down here.

  ## What this does not do

  No state tiers and no recovery snapshot. A reconnect re-renders from scratch, which
  is correct because these components read their state at render time rather than
  holding a projection of it.

  An earlier design had both: tiers declaring how each field of a view survived a
  reconnect, and a signed client-held snapshot for state the server could not
  re-derive. Both were removed after measurement — the snapshot recovered a reload or a
  returning visitor rather than a dropped connection, and a dropped connection is
  already handled by the transport re-issuing its original request."
  (:require [darkstar.watch :as watch]))

(defn engine
  "Creates a watch engine. Starts nothing.

  - `:components` name -> component (see below)
  - `:render-fn`  (fn [tree] -> string)
  - `:registry`   optional atom; one is created if absent

  A component is either a bare `(fn [params] -> tree)` reading through `watch`, or a
  map `{:render (fn [params] -> tree) :on {event-name handler}}` when it also handles
  events. The bare form stays valid because most components have no events of their
  own, and requiring a map for all of them would be ceremony."
  [{:keys [components render-fn registry]}]
  {:pre [(map? components) (ifn? render-fn)]}
  {:components components
   :render-fn render-fn
   :registry (or registry (atom {}))})

(defn- component-of
  "Resolves by name on every use and derefs, so a REPL redefinition reaches a live
  connection. Registering `#'my.ns/component` is what lets a REPL redefinition reach
  already-connected contexts — the same trick Ring uses for `#'handler`."
  [{:keys [components]} name]
  (let [e (get components name)]
    (when-not e
      (throw (ex-info "Unknown component" {:component name
                                           :known (set (keys components))})))
    (if (instance? clojure.lang.IDeref e) @e e)))

(defn- render-of
  "The render fn for a component in either form."
  [component]
  (if (map? component) (:render component) component))

(defn connect!
  "Registers a connection. Renders nothing — the caller decides when to mount."
  [{:keys [registry]} component-name {:keys [send! params]}]
  (let [id (str "w" (subs (str (random-uuid)) 0 8))]
    (swap! registry assoc id {:id id
                              :component-name component-name
                              :params (or params {})
                              :send! send!
                              :fragments {}})
    id))

(defn disconnect! [{:keys [registry]} id] (swap! registry dissoc id) nil)

(defn mount!
  "Renders the component, stores its fragments, and returns the HTML.

  Returns `{:html :topics}` — the topics being what the caller must subscribe the
  connection to. They came from the render, so a caller cannot subscribe to the
  wrong set unless it ignores this.

  These are the topics as of *this* render only. See `refresh!`: the set changes with
  the data, so subscribing here and never again is a bug."
  [{:keys [registry render-fn] :as eng} id]
  (let [{:keys [component-name params]} (get @registry id)
        f (render-of (component-of eng component-name))
        {:keys [tree topics fragments]} (watch/render-recording #(f params))]
    (swap! registry update id assoc :fragments fragments :topics topics)
    {:html (render-fn tree) :topics (vec topics)}))

(defn refresh!
  "Re-renders the fragments that read `topic`, pushes each one, and reports the
  connection's current topic set.

  Returns `{:patches :topics :added :removed}`, so a test can assert on it without a
  browser and a caller can adjust its subscriptions.

  ## A dependency set is data, so it changes

  `:topics` is not a formality. What a component depends on is *itself* dependent on
  the data it read: a roster that reads one presence topic per member depends on a
  different set the moment the membership changes. Subscribing once at mount is
  therefore wrong, and wrong in a way that looks fine — found in a browser, where a
  member who joined after a viewer connected showed up in that viewer's roster and
  then never went grey, because the viewer had never subscribed to a topic that did
  not exist when it mounted.

  So a caller must re-subscribe on every refresh. `:added` and `:removed` are
  supplied to make that a diff rather than a teardown.

  ## Only the narrowest matching fragment is pushed

  Every dirty fragment that no *other* dirty fragment contains is patched. Both halves
  of that matter:

  - a containing fragment inherits its children's topics, so patching parent and child
    together would send the change twice and the outer patch would replace the inner
    element just updated — the bug the old engine showed as duplicated list items;
  - but two SIBLING fragments can legitimately read one topic (a roster and a message
    list both reading `[:channel id]`), and both must be patched. Taking only the
    narrowest match broke that silently: the sibling that lost the sort never updated.

  See `watch/innermost-independent`."
  ([eng id topic] (refresh! eng id topic nil))
  ([{:keys [registry render-fn]} id topic {:keys [read-fns] :as _opts}]
   (when-let [{:keys [fragments topics send!]} (get @registry id)]
     (let [dirty (watch/innermost-independent
                  fragments (watch/fragments-for-topic fragments topic))
           ;; One pass per dirty fragment, threading the fragment map so a later
           ;; fragment sees any nested entries an earlier one produced.
           {:keys [fragments' patches pruned]}
           (reduce
            (fn [acc target]
              (let [frag (get (:fragments' acc) target)]
                (cond
                  (nil? frag) acc

                  ;; Every value this fragment read is unchanged, so its HTML is
                  ;; unchanged. A handful of pointer comparisons instead of a render —
                  ;; what makes a speculative hint cheap, and the property the old
                  ;; engine got from `identical?` pruning over its view map.
                  ;;
                  ;; No `:read-fns` needed: `watch` recorded the component's own
                  ;; readers, so this cannot disagree with the component.
                  (watch/unchanged? (:reads frag) read-fns)
                  (assoc acc :pruned true)

                  :else
                  ;; Render ONLY this fragment. Its `body-fn` closes over what it
                  ;; needs, so a one-row change costs one row.
                  (let [{:keys [tree] :as re} (watch/re-render frag target)
                        ;; Fragments this one used to contain and may no longer
                        ;; produce. They must be DROPPED, not merged over: a plain
                        ;; merge only adds, so a departed member's fragment survived
                        ;; and went on contributing its topic. Caught by
                        ;; `a-dependency-that-goes-away-is-reported-as-removed`.
                        was-nested (disj (set (keys (:fragments frag))) target)]
                    (-> acc
                        (update :fragments'
                                (fn [fs]
                                  (-> (apply dissoc fs was-nested)
                                      (merge (:fragments re))
                                      (assoc target
                                             (merge frag
                                                    (select-keys re [:tree :topics
                                                                     :reads
                                                                     :fragments]))))))
                        (update :patches conj
                                {:selector (str "#" target)
                                 :mode :outer
                                 :html (render-fn tree)}))))))
            {:fragments' fragments :patches [] :pruned false}
            dirty)
           ;; Topics are re-derived from what the renders actually read, so a
           ;; fragment's dependencies may change without a full component render.
           topics' (set (mapcat :topics (vals fragments')))]
       (swap! registry update id assoc :fragments fragments' :topics topics')
       (when (and send! (seq patches)) (send! patches))
       (cond-> {:patches patches
                :topics (vec topics')
                :added (vec (remove (or topics #{}) topics'))
                :removed (vec (remove topics' (or topics #{})))}
         (and pruned (empty? patches)) (assoc :pruned true))))))

(defn dispatch!
  "Runs the `:on` handler for `event` and returns the topics it says changed.

  A handler is `(fn [ctx args] -> topics)`, where `ctx` is
  `{:id :params :component-name}` and `topics` is a seq of topics to publish (or nil).

  ## Why a handler returns topics rather than a new view

  There is no view. The old engine's handler was `(fn [view ctx args] -> new-view)`
  because the view *was* the state, and the engine diffed the return value to find
  what to push. Here the state lives in the application's own atoms, tables and
  sources, and a handler mutates those directly — so the only thing the engine needs
  back is which topics to invalidate.

  That keeps one invalidation path. A hint published by a handler and a hint published
  by another user's action are the same kind of thing, and both flow through the
  caller's pubsub into `refresh!`. The alternative — pushing from inside `dispatch!` —
  would mean an event updated the acting connection by one code path and everyone else
  by another, which is how the old engine ended up with a warm path and a cold path
  that could disagree.

  **Pushing is deliberately not done here.** This returns topics; the caller publishes
  them. The engine has no pubsub and should not grow one."
  [eng id event args]
  (when-let [{:keys [component-name params]} (get-in @(:registry eng) [id])]
    (let [component (component-of eng component-name)
          handler (get-in component [:on event])]
      (when-not handler
        (throw (ex-info "No handler for event"
                        {:event event
                         :component component-name
                         :known (set (keys (:on component)))})))
      (vec (handler {:id id :params params :component-name component-name} args)))))

(defn fragments-of
  "The recorded fragments for a connection. For tests and REPL inspection."
  [{:keys [registry]} id]
  (get-in @registry [id :fragments]))
