# Darkstar

[Datastar](https://data-star.dev) binding for
[Remuda](https://github.com/brettatoms/remuda).

Remuda holds server-side view state and reports which *paths* changed. Darkstar
turns that into something a browser can apply: CSS selectors, Datastar patch
modes, and the client-side expressions that call back into your handlers.

## What it is good for

State lives on the server, the browser runs no application code, and updates are
pushed as HTML fragments over a persistent connection. That suits some
applications well and others badly.

Good fits:

- **Anything the server knows first.** Dashboards, queue monitors, build status,
  log tails, live prices, admin panels. The server pushes; there is nothing to
  poll.
- **Multi-user views of shared state.** Chat, comment threads, collaborative
  lists, presence. A change publishes a hint and every viewer of that data
  rebuilds.
- **CRUD with interactive polish.** Live validation, dependent selects, inline
  editing, filtered tables, typeahead — without a client-side data layer or
  duplicated validation.
- **Apps whose API exists only for their own UI.** If nothing else consumes those
  endpoints, this removes them.
- **Internal tools and small teams.** One language, one place state lives, no
  client build step.

Poor fits:

- **Offline or flaky networks.** No connection means no application. Remuda's
  reconnect handling limits the damage but does not remove the dependency.
- **Sub-frame interaction.** Drag-and-drop, canvas drawing, games. Datastar
  signals can hold local UI state, but that boundary needs deliberate design.
- **Very high connection counts on small hardware.** Each connected user holds
  server-side state and a connection. Cheap, not free.
- **Static public pages.** Plain server rendering is simpler.
- **Native or third-party clients.** Anything but a browser needs a real API, and
  then this is additive rather than a replacement.

## Connections

Every viewer holds one open SSE connection for as long as their page is open, so a
server runs thousands of idle long-lived connections rather than many short
requests. That inverts the usual sizing question: what matters is the cost of a
*parked* connection, not requests per second.

Darkstar names no server — the transport is a function you supply — but the choice
is consequential.

Measured on one laptop (macOS, JDK 26, 8 cores), idle connections, marginal cost
after subtracting a zero-connection baseline:

| transport | per connection | threads | outcome |
|---|---|---|---|
| http-kit | **3.4 KB** | 17 | 16,315 held flat |
| Jetty + virtual threads | **22.6 KB** | 21 | comparable counts |
| Jetty + platform threads | — | one per connection | **fails at ~4,060** |

Platform threads park an OS thread per connection and hit the kernel's thread
limit, so they are not viable at this shape. Both other transports handle tens of
thousands.

### Jetty with virtual threads

```clojure
(jetty/run-jetty handler
  {:port 3000
   :async? true
   :async-timeout 0
   :thread-pool (doto (org.eclipse.jetty.util.thread.VirtualThreadPool.)
                  (.setMaxConcurrentTasks 100000))})
```

`:async? true` and `:async-timeout 0` are required: a parked SSE handler must not
occupy a request worker or be timed out.

**Raise `maxConcurrentTasks`.** It defaults to 200 and is a semaphore, so a
blocking SSE handler holds a permit for the life of the connection and connection
201 never gets a handler. The default reads as "virtual threads cap at 200".

### http-kit

```clojure
(hk/run-server handler {:port 3000 :thread 32})
```

Event-driven, so `on-open` returns immediately and there is no per-connection
thread to cap. Roughly 6.6× less memory per connection than Jetty's virtual
threads, and no knob to get wrong. Needs the http-kit Datastar adapter rather than
the Ring one.

### What these numbers do not say

Heap was never the binding constraint: 16,315 connections used 65 MB of a 2 GB
heap, so 2 GB and 4 GB runs were indistinguishable. The runs were bounded by the
load generator's ephemeral ports rather than by any server, so the ceiling is
unknown.

Dividing 2 GB by 3.4 KB suggests roughly 600k connections of view state, but that
excludes socket buffers, TLS state and per-socket kernel memory, and assumes GC
behaves at 600k live objects as it does at 16k. That is arithmetic, not a
measurement.

These are **idle** connections — no diffing, no pushing. Broadcast fan-out is the
workload that matters in production and is measured separately in
`dev/fanout.clj`. `dev/soak.clj` and `bin/soak.sh` hold the harness and the full
caveats.

## Namespaces

- `darkstar.patch` — changed paths to `{:mode :selector}` patch descriptions,
  mapped onto Datastar's patch modes. Emits plain maps rather than calling the
  SDK, so it is testable with no server running.
- `darkstar.action` — the client expressions that invoke your handlers.
- `darkstar.engine/dispatch-opts` — the options Remuda's engine needs.

## Example

Bind an event to a handler:

```clojure
(require '[darkstar.action :as action])

[:button {:data-on:click (action/post "/live/act" :remove {:id 42})} "delete"]
;; renders:
;; @post('/live/act', {payload: {"event":"remove","id":42,"liveId":$liveId}})
```

Args travel as a JSON payload, so types survive the round trip: `42` arrives as a
number and `"42"` as a string. `action/get`, `put`, `patch` and `delete` are also
available.

Then run an interaction:

```clojure
(require '[darkstar.engine :as d*engine]
         '[remuda.engine :as engine])

(engine/dispatch! eng id :remove {:id 42} d*engine/dispatch-opts)
```

Pass `dispatch-opts` rather than assembling the options map yourself. It carries
`:retarget-fn`, and without it a patch that widens to the component root keeps its
narrower target — root HTML sent against a child selector, which corrupts the DOM
with no error.

## Development

`dev/` holds a runnable slice, a fan-out benchmark and a soak harness.
`examples/adapters/` has a bare http-kit server using no Ring, Zodiac or reitit.

```
clojure -M:slice -m slice     # http://localhost:3000
```

## Related

- [Remuda](https://github.com/brettatoms/remuda) — the engine: view state,
  diffing, tiers, reconnect
- [Zodiac Live](https://github.com/brettatoms/zodiac-live) — Zodiac extension and
  an example app

## Status

Working and tested, not released.

## License

MIT. See [LICENSE](LICENSE).
