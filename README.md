# darkstar

[Datastar](https://data-star.dev) binding for
[remuda](https://github.com/brettatoms/remuda).

remuda holds server-side view state and reports which *paths* changed. Darkstar
turns that into something a browser can apply: CSS selectors, Datastar patch
modes, and the client-side expressions that call back into your handlers. It
exists so remuda can stay free of the DOM.

## What it is good for

The model is server-rendered HTML with a persistent connection: state lives on the
server, the browser runs no application code, and updates are pushed as HTML
fragments. That suits some applications well and others badly.

Good fits:

- **Anything where the server already knows something first.** Dashboards, queue
  monitors, build status, log tails, live prices, admin panels. The server pushes;
  there is nothing to poll.
- **Multi-user views of shared state.** Chat, comment threads, collaborative lists,
  presence indicators. A change publishes a hint and every viewer of that data
  rebuilds.
- **CRUD with interactive polish.** Forms with live validation, dependent selects,
  inline editing, filtered tables, typeahead. You get interactivity without a
  client-side data layer, an API, or duplicated validation.
- **Apps where the API exists only for the UI.** If nobody else consumes your JSON
  endpoints, they are pure overhead and this removes them.
- **Small teams and internal tools.** One language, one place where state lives, no
  build step for the client.

Poor fits:

- **Offline or flaky-network use.** State lives on the server, so no connection
  means no application. remuda's reconnect handling limits the damage but does not
  remove the dependency.
- **Latency-sensitive interaction.** Anything needing a sub-frame response —
  drag-and-drop, canvas drawing, games — should not round-trip. Datastar signals
  can cover local UI state, but the boundary needs deliberate design.
- **Very high per-user connection counts on modest hardware.** Every connected user
  holds server-side state and a connection. Cheap per user, but not free.
- **Public pages with no interaction.** Plain server rendering is simpler.
- **A native or third-party client.** If something other than a browser consumes
  your data, you need a real API, and then this is additive rather than a
  replacement.

## What it provides

- `darkstar.patch` — changed paths to `{:mode :selector}` patch descriptions,
  mapped onto Datastar's eight patch modes. Emits plain maps rather than calling
  the SDK, so it is testable with no server.
- `darkstar.action` — builds the expressions that invoke handlers:
  `(action/post "/live/act" :remove {:id 42})`. Args travel as a JSON payload, so
  types survive and there is no string quoting to get wrong.
- `darkstar.engine/dispatch-opts` — the options remuda's engine needs, bundled
  because they are not independent.

## Example

```clojure
(require '[darkstar.action :as action])

[:button {:data-on:click (action/post "/live/act" :remove {:id 42})} "delete"]
;; renders:
;; @post('/live/act', {payload: {"event":"remove","id":42,"liveId":$liveId}})
```

Note `data-on:click` with a colon. A hyphen fails silently.

Args are JSON rather than query-string parameters, which is what keeps them sound:
a value containing `'` or `&` is unremarkable inside a JSON string, and `42` stays
distinguishable from `"42"`.

## Usage

```clojure
(require '[darkstar.engine :as d*engine]
         '[remuda.engine :as engine])

(engine/dispatch! eng id :inc args d*engine/dispatch-opts)
```

Pass `dispatch-opts` rather than assembling the options yourself. It carries
`:retarget-fn`, and omitting that produces a patch whose target and content
disagree — root HTML sent against a child selector, which corrupts the DOM
silently.

## What is here

`dev/` holds a runnable vertical slice, a fan-out benchmark, and a soak harness.
`examples/adapters/` holds a bare http-kit server with no Ring, Zodiac or reitit,
which exists to check that remuda's seam is genuinely sufficient.

```
clojure -M:slice -m slice     # http://localhost:3000
```

## Related

- [remuda](https://github.com/brettatoms/remuda) — the engine: view state,
  diffing, tiers, reconnect
- [zodiac-live](https://github.com/brettatoms/zodiac-live) — Zodiac extension, plus
  an end-to-end example app

## Status

Working and tested, not released.

## License

MIT. See [LICENSE](LICENSE).
