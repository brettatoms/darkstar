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
