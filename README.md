# Darkstar

Server-held UI state for Clojure, built on Datastar.

[Datastar](https://data-star.dev) gives the browser a hypermedia runtime: declarative
attributes for events, bindings and reactive signals, and a live connection over which the
server can patch elements, patch those signals, run scripts or redirect. You write markup
and server handlers instead of a client application.

Darkstar adds the piece Datastar leaves to you — deciding *what* to send and *where*. Your
domain state lives on the server, and a fragment declares which parts of it it reads:

```clojure
(defn counter [_]
  (fragment "count"
    (fn [] [:span {:id "count"} (watch [:clicks] #(deref clicks))])))
```

That fragment re-renders whenever anything publishes `[:clicks]`. You never name a CSS
selector, never write a patch, and never track which parts of which page are showing that
number.

## Is this for you?

It suits applications where **the server learns things first** and the browser needs to
find out: dashboards, queue monitors, build status, log tails, live prices, admin panels,
chat, presence, collaborative lists. Also ordinary CRUD that wants live validation,
dependent selects, inline editing or typeahead without a client-side data layer.

There is no JSON API to design for your own UI, no duplicated validation, and no build
step. Client-side state doesn't disappear — Datastar signals hold it, and that is the
right home for things like a dropdown's open/closed — but your domain data has one copy.

It is a poor fit for:

- **Offline or unreliable networks.** No connection means no application.
- **Sub-frame interaction.** Drag-and-drop, canvas drawing, games. Datastar signals can
  hold local UI state, but that boundary needs deliberate design.
- **State the browser already owns.** A half-typed input belongs in the DOM. Mirror it
  server-side and you will echo a stale value back into the field someone is typing in.

## Install

```clojure
com.github.brettatoms/darkstar {:mvn/version "0.1.17"}
```

The patch number is the commit count at release time, so it moves often — check
[Clojars](https://clojars.org/com.github.brettatoms/darkstar) for the current version.

You also need a Datastar SSE adapter for your server: `dev.data-star.clojure/ring` or
`dev.data-star.clojure/http-kit`.

## Start with plain Datastar

Darkstar is a set of conveniences over Datastar, so it helps to see what it is a
convenience *for*. Here is a counter with no Darkstar at all:

```clojure
(defn push-count! [sse-gen n]
  (d*/patch-elements! sse-gen (chassis/html [:span {:id "count"} n])
                      {d*/selector "#count" d*/patch-mode d*/pm-outer}))
```

Three things to get right at each call site — the selector, the patch mode, and HTML that
matches what the page rendered — plus one obligation: call it from everywhere the data
changes.

For a handful of update paths this is a fine way to build, and it has a real advantage
over anything cleverer: **when it breaks, it says so.** A selector matching nothing prints
`PatchElementsNoTargetsFound` in the browser console.

Reach for Darkstar when that stops scaling.

## Where it stops scaling

Add a "3 left" badge to a todo list. Now two places on the page show the same data, so
every mutation needs two pushes:

```clojure
(defn complete! [id]
  (swap! todos assoc-in [id :done] true)
  (push-list!)       ; and don't forget
  (push-badge!))     ; this one
```

Add a third place and every mutation site needs a third call. Forget one and the screen is
quietly wrong — no error, just a stale number.

With `watch` you say what each part of the page *reads*:

```clojure
(defn badge []
  (fragment "left"
    (fn [] [:span {:id "left"}
            (count (remove :done (watch [:todos] #(deref todos)))) " left"])))

(defn todo-list []
  (fragment "list"
    (fn [] [:ul {:id "list"} (mapv item (watch [:todos] #(deref todos)))])))
```

and completing a todo publishes one name:

```clojure
(defn complete! [id]
  (swap! todos assoc-in [id :done] true)
  (publish! [:todos]))
```

Both fragments update. A fourth place showing the same data needs no change at all.

### Different viewers, different pages

This is where it stops being a convenience and becomes the reason to use it. Say each
viewer picks a server and watches its metrics:

```clojure
(defn app [{:keys [conn-id]}]
  (fragment "app"
    (fn []
      (let [chosen (watch [:selected conn-id] #(get @selections conn-id))]
        [:div {:id "app"} (when chosen (metrics-panel chosen))]))))
```

Alice picks server `a`, Bob picks `b`. Their subscriptions come out of what each render
actually read:

```
alice -> [:metrics "a"], [:selected "conn-1"]
bob   -> [:metrics "b"], [:selected "conn-2"]
```

Publish `[:metrics "a"]` and Alice's panel updates. Bob's connection does no work at all —
not "renders and discards", but never hears about it.

By hand, that means tracking per connection which parts of the page are currently open and
consulting it in every push function. Here it falls out of the render.

## How this fails

Plain Datastar fails loudly: a selector matching nothing logs
`PatchElementsNoTargetsFound`. Declaring dependencies instead of naming targets trades
that for a different failure — a page that looks right and then stops updating. So most
of the ways to get it wrong throw immediately.

**Throws, at the render that caused it:**

- an element whose `:id` does not match its fragment id, or has none — the patch target
  would miss
- two fragments sharing an id — one element would be lost from the tree entirely
- an unrealised lazy sequence — it escapes the recording, so `watch` calls inside it are
  never seen (use `mapv`, not `for`)
- **a fragment that reads no topic** — nothing can ever update it, so it renders once and
  freezes. This is the commonest mistake: passing a value into a fragment instead of
  reading it there.

That last one is worth seeing, because the broken version looks completely reasonable:

```clojure
(defn job-row [job]                                    ; the job arrives as a value
  (fragment (str "job-" (:id job))
    (fn [] [:tr {:id (str "job-" (:id job))} (:progress job)])))
```

Publishing `[:job 7]` finds no fragment that read it, so the row never updates. The fix is
for the child to read rather than receive:

```clojure
(defn job-row [id]
  (fragment (str "job-" id)
    (fn [] [:tr {:id (str "job-" id)}
            (:progress (watch [:job id] #(get @jobs id)))])))
```

A patchable-but-never-invalidated fragment is legitimate — a static heading, a form whose
input owns its own value. Darkstar cannot tell that from a forgotten `watch`, so say which
you mean:

```clojure
(fragment "composer" {:static? true}
  (fn [] [:form {:id "composer"} …]))
```

**Still silent, and checked by `darkstar.diagnose` rather than thrown:**

```clojure
(darkstar.diagnose/check-component my-app {:conn-id "c1"})
;=> [{:problem :unstable-reader
;     :message "reader for [:jobs] in fragment \"job-list\" returns an equal but not
;               identical? PersistentVector on every call, so this fragment can never
;               be pruned. Compute it once at write time and cache it."}]
```

- **`:unstable-reader`** — your reader rebuilds its collection on every call, so Darkstar
  can never tell nothing changed. The page stays correct but re-renders constantly.
- **`:orphan-topic`** — a topic is watched but never published. Usually a typo: watching
  `[:members id]` while the writer publishes `[:channel id]`. Necessarily a heuristic,
  since a topic may simply not have been published yet.

Both are reported rather than thrown because neither is certainly wrong.

**And one that nothing catches.** A reader closing over a snapshot instead of re-reading:

```clojure
(let [snap @data]
  (fragment "x" (fn [] [:div {:id "x"} (watch [:n] #(:n snap))])))   ; frozen
```

This subscribes correctly and returns a stable value, so pruning suppresses the re-render
— which is indistinguishable, from the engine's side, from a correct component whose data
genuinely did not change. Read inside the reader, not outside it.

## Putting it together

A complete server. `darkstar.sse` handles the connection lifecycle; you supply the
component and where to put it.

```clojure
(require '[darkstar.action :as action]
         '[darkstar.live :as live]
         '[darkstar.sse :as sse]
         '[darkstar.watch :refer [fragment watch]]
         '[dev.onionpancakes.chassis.core :as chassis]
         '[starfederation.datastar.clojure.adapter.ring :as d*ring])

(def clicks (atom 0))

(defn counter [_]
  (fragment "app"
    (fn [] [:div {:id "app"}
            [:span (watch [:clicks] #(deref clicks))]
            [:button {:data-on:click (action/post "/click")} "+1"]])))

(def engine
  (live/engine {:components {:counter #'counter}
                :render-fn chassis/html}))

;; topic -> the connections watching it
(defonce subscriptions (atom {}))

(defn- subscribe! [id topics]
  (swap! subscriptions
         (fn [m] (reduce (fn [m t] (update m t (fnil conj #{}) id)) m topics))))

(defn publish! [topic]
  (doseq [id (get @subscriptions topic)]
    (live/refresh! engine id topic)))

(defn live-route [request]
  (let [{:keys [on-open on-close]}
        (sse/handlers {:engine engine
                       :component :counter
                       :root "#app"
                       :subscribe! subscribe!})]
    (d*ring/->sse-response request
                           {d*ring/on-open on-open
                            d*ring/on-close on-close})))

(defn click-route [_request]
  (swap! clicks inc)
  (publish! [:clicks])
  {:status 204})
```

The page needs a mount point and a connection:

```clojure
[:div {:id "app"} "connecting…"]
[:div {:data-init "@get('/live', {requestCancellation: 'cleanup'})"}]
```

`requestCancellation: 'cleanup'` matters. Without it, navigating between pages leaves the
old connection open and one tab can accumulate several.

That `subscribe!` is deliberately the simplest thing that works. For subscriptions that
shrink as well as grow, and for hint coalescing, see `darkstar.pubsub` and the example
applications.

## Actions

```clojure
(require '[darkstar.action :as action])

[:button {:data-on:click (action/post "/live/act" :remove {:id 42})} "delete"]
;; @post('/live/act', {payload: {"event":"remove","id":42,"liveId":$liveId}})
```

Arguments travel as JSON, so types survive: `42` arrives as a number, `"42"` as a string.
`action/get`, `put`, `patch` and `delete` work the same way.

Note the `{payload: {...}}` wrapper. Datastar's second argument is an *options* map, so a
bare `{:id 42}` is ignored and Datastar sends its signals instead — an empty body, no
error, and a handler that receives nothing. `action/post` gets this right for you.

On the receiving side, `sse/read-payload` handles both shapes a payload can arrive in:
already parsed by middleware, or as an unread request body.

## Two rules for writing components

**Use `mapv`, not `for`.** A lazy sequence is realised after the recording has finished,
so its `watch` calls are never seen and those fragments never update. The first render
looks perfect. Darkstar throws when it detects an unrealised sequence, but writing it
correctly beats relying on the check.

**Return stable values from readers.** Darkstar decides whether to re-render by comparing
what a reader returned last time against what it returns now, `identical?` first. A reader
that rebuilds its result each call always looks changed:

```clojure
(defn all-jobs [] (vec (sort-by :id (vals @jobs))))   ; re-renders always
(defn all-jobs [] (:sorted @cache))                   ; prunes correctly
```

Compute derived collections once when the data changes and hand out the cached value.
`darkstar.diagnose` reports the first form.

## Namespaces

Each stands alone; use what you need.

| namespace | for |
|---|---|
| `darkstar.action` | `@post(...)` expressions that call your handlers |
| `darkstar.watch` | `fragment` and `watch` — declaring what a fragment reads |
| `darkstar.live` | connections, and turning a published topic into patches |
| `darkstar.sse` | the connection lifecycle and patches onto a Datastar stream |
| `darkstar.diagnose` | development checks for the three quiet mistakes |
| `darkstar.pubsub` | invalidation hints, with coalescing |
| `darkstar.source` | a one-method read protocol, if you want reads behind an interface |

`fragment` is opt-in per element, so a component can hold managed fragments alongside
plain hiccup you push yourself:

```clojure
(fragment "app"
  (fn [] [:div {:id "app"}
          (fragment "a" (fn [] [:span {:id "a"} (watch [:a] #(:a @data))]))  ; managed
          [:span {:id "b"} (:b @data)]]))                                     ; yours
```

## Choosing a server

Every viewer holds one connection open for as long as their page is live, so what matters
is the cost of an idle connection rather than requests per second. Darkstar names no
server, but the choice is consequential.

On one laptop (macOS, JDK 26, 8 cores, 2 GB heap), idle connections, marginal cost above a
zero-connection baseline:

| transport | connections | heap | per connection |
|---|---|---|---|
| http-kit | 44,836 | 167 MB | **3.6 KB** |
| Jetty + virtual threads | 45,497 | 892 MB | **19.8 KB** |
| Jetty + platform threads | **fails at 4,060** | — | — |

Platform threads park an OS thread per connection and hit the kernel's thread limit.
Virtual threads clear that and match http-kit's count at roughly 5.5× the memory.

Both successful runs were limited by the load generator rather than the server, so the real
ceiling is higher and unmeasured. Heap was never the constraint for http-kit: 167 MB of
2 GB at 44,836 connections.

### http-kit

```clojure
(hk/run-server handler {:port 3000 :thread 32
                        :backlog 65536 :max-connections 200000})
```

Event-driven, so `on-open` returns immediately and there is no per-connection thread.
Needs `dev.data-star.clojure/http-kit`.

Raise `:backlog`. The default accept queue overflows under load and the failures surface
on the *client* as `ConnectException: Operation timed out`, which looks exactly like a
server limit.

### Jetty

```clojure
(jetty/run-jetty handler
  {:port 3000
   :async? true
   :async-timeout 0
   :thread-pool (doto (org.eclipse.jetty.util.thread.VirtualThreadPool.)
                  (.setMaxThreads 100000))})
```

`:async? true` and `:async-timeout 0` are required — an open SSE handler must not hold a
request worker or be timed out. Use `setMaxThreads`; `setMaxConcurrentTasks` existed in
older Jetty and throws on 12.

### Under load

Idle connections are the easy case; pushing to them is the real workload. On a dashboard
whose fragments update at rates spanning 100×, with one writer:

| connections | patches delivered in 60s | server CPU (of 800%) |
|---|---|---|
| 500 | 478,000 | 48% |
| 1,000 | 837,952 | 32% |
| 2,000 | 978,430 | 26% |

Every connection kept receiving throughout. Two things were needed: fan out on a
work-stealing pool rather than the publishing thread, and keep the subscription index per
connection. A single map of `topic -> #{connection}` rebuilt on every refresh becomes the
bottleneck long before rendering does.

One caveat: at 2,000 connections, *opening* them all took 305 seconds, because each mount
competes with fan-out already in flight. Steady-state throughput was unaffected, but a mass
reconnect after a deploy will be slow.

`dev/soak.clj` and `bin/soak.sh` hold the harness if you want to measure your own setup.

## Development

```
clojure -M:test
clojure -M:clj-kondo --lint src test dev
clojure -M:cljfmt check src test dev
bin/soak.sh http-kit 2g
```

## Examples

[Zodiac Live](https://github.com/brettatoms/zodiac-live) is a
[Zodiac](https://github.com/brettatoms/zodiac) extension with four runnable applications:
an end-to-end encrypted chat app, a build dashboard, the same chat app written with no
engine for comparison, and a minimal single-fragment server.

## Status

New and unproven in production. Browser-verified and soak-tested, but the API is likely to
change.

Darkstar was previously split across two artifacts, `remuda` and `darkstar`. They are
merged; **`remuda` is archived and should not be used.**

## License

MIT. See [LICENSE](LICENSE).
