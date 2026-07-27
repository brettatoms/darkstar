# Transport: SSE now, and what WebSockets would change

Notes from measuring, kept because the question recurs and the intuitive answer
("WebSockets are faster") is the least important part of the real one.

## What Datastar actually requires

Datastar's client has **no WebSocket support**: zero occurrences of `WebSocket` in the
v1.0.0-RC.11 bundle. What it does have is `fetch` plus `getReader()` — a streaming
fetch that parses SSE framing, not an `EventSource`.

That matters in both directions:

- **Against** a swap: there is no client-side WebSocket path to opt into. Using one
  means writing the client half, and then darkstar is shipping a Datastar fork rather
  than a binding to it.
- **For** it being possible at all: because it is `fetch` + a stream reader rather than
  `EventSource`, the framing is a *convention* rather than a browser API. A WebSocket
  transport could carry the same `event:`/`data:` payloads and reuse every server-side
  patch builder unchanged.

## Measured differences

### Server -> client framing: small

On a real 7-event patch stream captured from the roster app (1,915 bytes):

| | bytes | framing overhead |
|---|---|---|
| SSE | 1,915 | 168 (8.8%) |
| WebSocket (same payloads) | 1,622 | 36 (2.2%) |

A 15% wire saving. Real, and the least interesting number here.

### Client -> server: 9x

This is the one that matters, and it is structural rather than incremental. With SSE
the downstream is a stream but **every upstream event is its own HTTP request**, paying
a full header block. A WebSocket frame pays 6 bytes.

One typing event carrying `{"liveId":"…","text":"hello"}`:

| | bytes |
|---|---|
| SSE + POST (request + 204 response) | 376 |
| WebSocket frame | 42 |

At five keystrokes/second with 1,000 users typing: **1,836 KB/s versus 205 KB/s.**

The asymmetry is the point. Datastar apps are chatty upstream by design —
`data-on:input`, `data-on:click`, and the heartbeat are all round trips — so the
protocol penalty lands exactly where this style of app spends its requests.

### Connections: SSE needs more of them

Ours is the platform where connection count binds first (measured: 44,836 before a
kernel buffer limit, with heap at 8% of 2 GB). SSE spends connections faster:

- one held stream per viewer, plus
- one transient request per upstream event, plus
- one per heartbeat tick.

A WebSocket is one socket doing all three.

There is also a bug class that only exists here. `examples/chat` once had a single tab
holding **six** live streams, because a streaming fetch's lifetime is tied to an
element and a URL rather than to the page — fixed with
`requestCancellation: 'cleanup'`. A WebSocket has one explicit open and close.

## Recommendation

**Keep SSE as the default. Do not add a WebSocket transport yet.**

Reasons, in order of weight:

1. **There is no client to talk to.** Datastar cannot speak WebSocket, so this is not
   an option to expose but a fork to maintain. That cost dwarfs a 15% framing saving.
2. **The 9x upstream penalty is avoidable without changing protocol.** Most of it is
   per-event HTTP headers on chatty events. Batching or debouncing upstream events
   recovers a large share, and a server-side keepalive tick (see below) removes the
   heartbeat request entirely.
3. **SSE's operational advantages are not small.** It is plain HTTP: proxies, CDNs,
   HTTP/2 multiplexing, `curl`, and every observability tool work without special
   cases. Every debugging session in this project so far used `curl -N` against a live
   stream, which is not available for a WebSocket.

Revisit if any of these changes: Datastar ships a WebSocket client; measurement shows
upstream volume dominating real workloads after batching; or connection count binds in
production the way it does in the soak test.

## The related change that IS worth making

Independent of protocol, darkstar should own **connection liveness**, because the two
mechanisms that provide it are transport configuration rather than application logic.

Measured against a black-holed socket (peer holds the connection open, never reads):

| mechanism | result |
|---|---|
| `:async-timeout` 0 or 20s, platform or virtual threads | connection held indefinitely, all four combinations |
| `SO_KEEPALIVE` | macOS `keepidle` defaults to 7,200,000 ms — two hours |
| **`ServerConnector.setIdleTimeout` + a periodic server write** | **reaped, `on-close` fired** |
| `ServerConnector.setIdleTimeout`, server silent | held (still held at 65s) |

So the write is what discovers the dead peer and the idle timeout is what acts on it;
neither alone suffices. That is a keepalive tick plus a connector setting — transport
concerns. Phoenix does exactly this inside Bandit and `phoenix/socket.js`, which is why
a Phoenix app needs no heartbeat code of its own.

Note the scope boundary: darkstar should own **liveness** (is this socket alive, reap
it if not), not **presence** (is `alice` online in `#general`). Presence is application
semantics — per-channel identity, whether a departed member stays listed — and should
be derived from liveness rather than reimplemented alongside it.
