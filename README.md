# Darkstar

Server-held UI state over [Datastar](https://data-star.dev), for Clojure.

State lives on the server. The browser runs no application code. When state changes the
server pushes HTML fragments down a persistent connection and Datastar applies them.

Darkstar is **a set of independent conveniences, not a framework.** Each namespace is
useful on its own, you can push patches by hand and use none of them, and the
hand-written and managed styles mix inside a single component.

## Install

```clojure
com.github.brettatoms/darkstar {:mvn/version "0.1.15"}
```

## Start with no library at all

This is the baseline everything else should be measured against:

```clojure
(defn push-count! [sse-gen n]
  (d*/patch-elements! sse-gen (chassis/html [:span {:id "count"} n])
                      {d*/selector "#count" d*/patch-mode d*/pm-outer}))
```

Three things to get right per call site — the selector, the patch mode, and HTML that
agrees with what the page rendered — and one obligation: call it from wherever the data
changed. For an app with a handful of update paths, a small helper around that is a
perfectly good answer, and it has a real advantage: **it fails loudly.** A wrong
selector prints `PatchElementsNoTargetsFound` in the browser console.

## `watch`: declare a dependency once

The alternative is to say what a fragment *reads* and let the library decide when to
re-render it:

```clojure
(require '[darkstar.watch :refer [fragment watch]])

(defn counter [_]
  (fragment "count"
    (fn [] [:span {:id "count"} (watch [:clicks] #(deref clicks))])))
```

A writer now publishes a name — `[:clicks]` — with no selector, no patch mode and no
HTML. Nothing has to remember which places on screen show that data.

That matters as an app grows. A todo list with a "3 left" badge reads the same data in
two fragments; one publish updates both, and adding a third place changes nothing. The
manual version needs a third push call at every mutation site, and a forgotten one is a
silently stale screen.

### Where it earns its keep

| your app | verdict |
|---|---|
| one thing changes, one place updates | a push helper is simpler; `watch` adds concepts for nothing |
| one thing changes, several places update | `watch` starts paying — publish a name, not a list of targets |
| different viewers see different subsets | `watch` is decisive |

The third row is the real case. Viewers pick a server and see its live metrics:

```clojure
(defn app [{:keys [conn-id]}]
  (fragment "app"
    (fn []
      (let [sel (watch [:selected conn-id] #(get @selected conn-id))]
        [:div {:id "app"} (when sel (detail sel))]))))
```

Alice subscribes to `[:metrics "a"]`, Bob to `[:metrics "b"]` — derived from what each
render actually read, not declared anywhere. Publishing `[:metrics "a"]` reaches Alice
and does **no work at all** for Bob. The manual equivalent means tracking
per-connection "what is on screen" yourself and consulting it in every push function.

### The trade

**Manual pushes fail loudly. `watch` fails silently.** A `watch` mistake produces
correct-looking output that never updates again.

Every bug found building two applications on `watch` was silent — wrong or frozen
output, never an exception. `darkstar.diagnose` exists because of that:

```clojure
(darkstar.diagnose/check-component my-app {:conn-id "c1"})
;=> [{:problem :unstable-reader
;     :message "reader for [:jobs] in fragment \"job-list\" returns an equal but not
;               identical? PersistentVector on every call, so this fragment can never
;               be pruned. Compute it once at write time and cache it."}]
```

- **`:unstable-reader`** — a reader that rebuilds its collection each call, so pruning
  never fires. Correct on screen, just re-rendered on every hint.
- **`:silent-fragment`** — a fragment handed its data as an argument rather than reading
  it through `watch`. It subscribes to nothing and freezes after the first render.
- **`:orphan-topic`** — a topic watched but never published. A name invented at the read
  site has no counterpart at the publish site to disagree with.

## Mixing styles

Markup wrapped in `fragment` is managed; plain hiccup beside it is yours to push:

```clojure
(fragment "app"
  (fn [] [:div {:id "app"}
          (fragment "a" (fn [] [:span {:id "a"} (watch [:a] #(:a @data))]))  ; managed
          [:span {:id "b"} (:b @data)]]))                                     ; you push
```

The library only knows what `fragment` wraps and only subscribes to what `watch` reads.

## Actions

```clojure
(require '[darkstar.action :as action])

[:button {:data-on:click (action/post "/live/act" :remove {:id 42})} "delete"]
;; renders:
;; @post('/live/act', {payload: {"event":"remove","id":42,"liveId":$liveId}})
```

Worth using even if you use nothing else here. Args travel as a JSON payload, so types
survive the round trip: `42` arrives as a number and `"42"` as a string.

Note the `{payload: {...}}` wrapper. The second argument to `@post` is an *options* map,
so passing a bare `{:id 42}` is silently ignored and Datastar sends its signal set
instead — an empty body, no error, and a handler that sees nothing. `action/get`, `put`,
`patch` and `delete` are also available.

## What it is good for

Good fits:

- **Anything the server knows first.** Dashboards, queue monitors, build status, log
  tails, live prices, admin panels. The server pushes; there is nothing to poll.
- **Multi-user views of shared state.** Chat, comment threads, collaborative lists,
  presence.
- **CRUD with interactive polish.** Live validation, dependent selects, inline editing,
  filtered tables, typeahead — without a client-side data layer or duplicated
  validation.
- **Apps whose API exists only for their own UI.** If nothing else consumes those
  endpoints, this removes them.
- **Internal tools and small teams.** One language, one place state lives, no client
  build step.

Poor fits:

- **Offline or flaky networks.** No connection means no application.
- **Sub-frame interaction.** Drag-and-drop, canvas drawing, games. Datastar signals can
  hold local UI state, but that boundary needs deliberate design.
- **State the client already owns.** A half-typed input belongs in the DOM. Mirroring it
  server-side means echoing a stale value back into the field being typed in.

## Connections

Every viewer holds one open SSE connection for as long as their page is open, so a
server runs thousands of parked connections rather than many short requests. What
matters is the cost of a *parked* connection, not requests per second.

Darkstar names no server — the transport is a function you supply — but the choice is
consequential. Measured on one laptop (macOS, JDK 26, 8 cores, 2 GB heap), **idle**
connections, marginal cost after subtracting a zero-connection baseline:

| transport | connections | heap | per connection | bounded by |
|---|---|---|---|---|
| http-kit | 44,836 | 167 MB | **3.6 KB** | the load generator's kernel buffers |
| Jetty + virtual threads | 45,497 | 892 MB | **19.8 KB** | the load generator's kernel buffers |
| Jetty + platform threads | **4,060** | — | — | `pthread_create` failure |

Platform threads park an OS thread per connection and hit the kernel's thread limit.
Virtual threads lift Jetty past that and reach http-kit's count, at ~5.5× the memory.

### http-kit

```clojure
(hk/run-server handler {:port 3000 :thread 32
                        :backlog 65536 :max-connections 200000})
```

Event-driven, so `on-open` returns immediately and there is no per-connection thread to
cap. Needs the http-kit Datastar adapter rather than the Ring one.

`:backlog` is not tuning. The default accept queue overflows under a parallel load
generator and the failures arrive on the *client* as `ConnectException: Operation timed
out`, which reads exactly like a server ceiling.

### Jetty with virtual threads

```clojure
(jetty/run-jetty handler
  {:port 3000
   :async? true
   :async-timeout 0
   :thread-pool (doto (org.eclipse.jetty.util.thread.VirtualThreadPool.)
                  (.setMaxThreads 100000))})
```

`:async? true` and `:async-timeout 0` are required: a parked SSE handler must not occupy
a request worker or be timed out.

`setMaxThreads`, not `setMaxConcurrentTasks` — the latter existed in earlier Jetty and
throws on 12.0.21.

### Fan-out, which is usually the number that matters

Idle connections are the easy half. Under active fan-out — one writer, N readers, on a
dashboard whose fragments update at rates spanning 100× — counting bytes received *per
connection*, so a connection that opened but receives nothing cannot be mistaken for a
working one:

| connections | events delivered in 60s | server CPU (of 800%) | any silent? |
|---|---|---|---|
| 500 | 478,000 | 48% | no |
| 1,000 | 837,952 | 32% | no |
| 2,000 | 978,430 patches | ~26% | no |

Two things were needed, and the second mattered more:

1. Fan-out on a work-stealing pool rather than the publishing thread.
2. A **per-connection** subscription index. A single atom holding `topic -> #{conn-id}`
   rebuilt on every refresh was O(topics) work per connection serialized by CAS retries
   — adding threads to that made it *worse*. Most of the CPU drop from 102% to ~30% is
   this, not the pool.

At 2,000 connections, *opening* them all took 305 seconds, because each mount competes
with fan-out already in flight. Steady-state throughput was unaffected, but a thundering
herd of reconnects after a deploy would be slow.

### What these numbers do not say

Heap was never the binding constraint for http-kit: 167 MB of 2 GB at 44,836
connections. Both idle runs stopped because the *load generator* ran out of kernel
socket buffers, so those ceilings are unknown.

Dividing 2 GB by 3.6 KB suggests ~570k connections of view state, but that excludes
socket buffers, TLS state and per-socket kernel memory, and assumes GC behaves at 570k
live objects as at 45k. That is arithmetic, not a measurement.

`dev/soak.clj` and `bin/soak.sh` hold the harness. Its docstring leads with the harness
errors that produced earlier wrong numbers, because every plateau on a single host so
far turned out to be an OS or harness limit rather than a server limit.

## Namespaces

- `darkstar.action` — `@post(...)` client expressions that invoke your handlers.
- `darkstar.watch` — `fragment` and `watch`. Records which fragments read which topics.
  Requires nothing else.
- `darkstar.live` — connections, and the fragments each is showing. Turns a hint into
  the narrowest patches for one viewer.
- `darkstar.diagnose` — development checks for the mistakes `watch` makes silent.
- `darkstar.pubsub` — invalidation hints and coalescing. Hints carry no payload, so they
  are safe to lose, reorder or duplicate, and many hints for one topic collapse into one
  rebuild.
- `darkstar.source` — a one-method read protocol, for when reads should sit behind an
  interface.

## Two things to know before using `watch`

**Use `mapv`, not `for`.** A lazy sequence escapes the recording, so its `watch` calls
are never seen and its fragments never update — while the first render looks perfect.
This is checked and throws, but writing it correctly beats relying on the guard.

**A dependency set is data, so re-derive it on every render.** A roster reading one
topic per member depends on a different set the moment its membership changes.
`darkstar.live/refresh!` reports `:added` and `:removed` for exactly this; subscribing
once at mount leaves a later joiner permanently stale.

## Development

```
clojure -M:test                        # 73 tests
clojure -M:clj-kondo --lint src test
clojure -M:cljfmt check src test
bin/soak.sh http-kit 2g                # concurrency
```

## Related

[Zodiac Live](https://github.com/brettatoms/zodiac-live) — a
[Zodiac](https://github.com/brettatoms/zodiac) extension, plus runnable examples: an
encrypted chat app, a build dashboard used for the fan-out numbers above, and the same
chat app written with no engine at all for comparison.

## Status

New and unproven in production. Browser-verified and soak-tested; the API is likely to
move.

Merged from a former two-artifact split (`remuda` + `darkstar`). The premise was that a
state engine could stay free of the DOM and be reused elsewhere. After the original
view-and-diff engine was replaced by `watch`, what remained had no code coupling to
justify separate CI, versioning and releases. **`remuda` is archived; do not use it.**

## License

MIT. See [LICENSE](LICENSE).
