(ns soak
  "Soak test: how many concurrent live connections a server sustains, and at what
  memory cost per connection.

  The same engine, component and `Source` behind three transports — the only variable
  is which one holds the stream open. Driven by `bin/soak.sh`.

  ## Read this before trusting a number

  Every plateau this test produced on a single macOS laptop turned out to be a
  *harness or OS* limit rather than a server limit. Each one first presented as a
  server ceiling:

  - **~4,900**: the driver's 5-second connect timeout expiring on a deep accept
    backlog. 860 `SocketTimeoutException`s, zero refusals, and the server held every
    connection it accepted. Fixed by a 60-second timeout.
  - **4,553, apparently frozen**: a fixed read pool of 64 blocking the socket-open
    loop. Diagnosed by sampling `lsof` twice and seeing delta zero. Fixed by opening
    all sockets first and confirming a sample afterwards.
  - **186 sockets/sec**: sequential `connect`. Fixed by parallelising.
  - **16,315**: the real one on a single host — ephemeral ports (49152-65535).

  So: **`error-kinds` counts failures by reason, not in total.** A bare count cannot
  distinguish \"server refused\" from \"out of ports\" from \"timed out\", and those imply
  completely different conclusions. Always read it before believing a ceiling.

  ## The port ceiling, and how to get past it

  A single macOS host cannot exceed **16,384** outbound connections to one
  destination, because ephemeral ports are allocated **system-wide, not per
  process**. Measured with four driver processes each asking for 15,000: they got
  13191 / 3115 / 4 / 5, summing to the server's total, with 16,306 `BindException`s
  between them. Running more driver processes does not help.

  Two ways past it:

  1. **A second host as the driver** — the clean answer, and what a real capacity
     number needs.
  2. **Loopback aliases**, to widen the (src-ip, src-port, dst-ip, dst-port) tuple.
     Each alias grants another ~16k. Needs root:

         sudo ifconfig lo0 alias 127.0.0.2 up   # repeat for .3, .4, ...

     then one driver per alias:

         clojure -M:soak -m soak drive-once 15000 127.0.0.2

     **Verified.** Two drivers bound to 127.0.0.1 and 127.0.0.2 held 30,000
     connections with `errors=0` from both — nearly double the 16,315 single-address
     ceiling, and not itself a ceiling, since neither driver failed a single connect.
     Source IPs do get their own ephemeral range.

  Check the server's descriptor limit, but do not assume `launchctl limit maxfiles`
  is it. An earlier version of this docstring claimed the macOS default of 256 caps a
  JVM around ~4,900 fds regardless of the shell's `ulimit -n`. Retested directly on a
  host reporting `maxfiles 256` with `ulimit -n 1048576`: a JVM opened 20,000 file
  descriptors without error. The shell limit is what binds, and the ~4,900 plateau
  once attributed to `maxfiles` was the connect-timeout failure listed above.

  ## What has been measured, and what has not

  Measured on one laptop (macOS, JDK 26, 8 cores), three loopback source addresses,
  2 GB heap:

  | transport | connections | heap | KB/conn | bound by |
  |---|---|---|---|---|
  | http-kit | 44,836 | 167 MB | **3.6** | driver: kernel socket buffers |
  | jetty + virtual threads | 45,497 | 892 MB | **19.8** | driver: kernel socket buffers |
  | jetty + platform threads | **4,060** | — | — | `kern.num_taskthreads`, thousands of `pthread_create` failures |

  So virtual threads do lift Jetty past the platform-thread thread cap and reach the
  same count as http-kit — at **5.5x the memory per connection**. An earlier run
  reported 22.6 KB/conn for jetty-vt against an older Jetty API
  (`VirtualThreadPool.setMaxConcurrentTasks`, which no longer exists in 12.0.21); 19.8
  on the current API is close enough to treat the ratio as real rather than an artefact.

  **Heap is still not the binding constraint for http-kit** — 167 MB of 2 GB at 44.8k,
  about 8%. Both transports were stopped by the *driver* host running out of kernel
  socket buffers (`SocketException: No buffer space available`), not by the server.

  jetty-vt at 892 MB of 2 GB is within sight of a heap limit, so for that transport the
  2 GB/4 GB question would finally be answerable. For http-kit it still is not.

  Extrapolating from KB/conn — 2 GB / 3.6 KB is roughly 570k connections of view state —
  remains arithmetic rather than measurement: it excludes socket buffers, TLS state and
  per-socket kernel memory, and assumes GC behaves at 570k live objects as at 45k.

  Also: **idle connections only.** No broadcast fan-out, no diffing, no pushing — the
  workload that actually matters in production is measured separately in
  `dev/fanout.clj`.

  ## Methodology

  - Same engine, component and `Source` for every transport.
  - Heap read after an explicit GC at a plateau, so it is live data rather than
    garbage awaiting collection.
  - A zero-connection baseline is subtracted, so the figure is marginal cost.
  - Connections are **held**, never ramped-and-dropped: an earlier version read a
    high-water mark over a moving ramp and overreported by ~1,000.
  - Server and driver in **separate processes** — the driver's own threads count
    against the same `kern.num_taskthreads` cap, and sharing a JVM made Jetty appear
    to die at ~1,700 when half the budget had gone to the client.

  ## Usage

      bin/soak.sh http-kit 2g              # one config, one driver
      bin/soak.sh jetty-vt 4g              # \"jetty\" for platform threads
      bin/soak.sh http-kit 2g 4 15000 300  # drivers, per-driver target, hold seconds

  Or by hand, server and driver in separate shells:

      clojure -J-Xmx2g -J-Xms2g -M:soak -m soak serve http-kit
      clojure -M:soak -m soak drive-once 15000"
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as chassis]
            [org.httpkit.server :as hk]
            [ring.adapter.jetty :as jetty]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as d*hk]
            [starfederation.datastar.clojure.adapter.ring :as d*ring]
            [darkstar.watch :as w]
            [darkstar.live :as engine])
  (:import [java.util.concurrent CountDownLatch Executors TimeUnit]))

;;; ==========================================================================
;;; The component under test
;;; ==========================================================================
;;; Deliberately modest: a realistic view holds a page of rows, not a trivial map.
;;; A too-small view understates per-connection cost; a huge one drowns the
;;; transport difference we are trying to see.

(def ^:private rows
  (mapv (fn [i] {:id i :text (str "message number " i " with some realistic length")})
        (range 50)))

(defonce ^{:doc "The rows every connection renders. One shared value on purpose: a
  per-connection copy would measure the copy rather than the per-connection cost."}
  row-source
  (atom rows))

(defn component
  "The component under test, in the `watch` style.

  One fragment per row, so the fragment count per connection matches what a real app
  holds — the thing being measured is per-connection bookkeeping, and a single
  fragment would understate it.

  `mapv`, not `for`: a lazy seq escapes the recording binding and its fragments are
  never recorded. The old version used `for` safely because the diff engine walked
  the realised tree instead."
  [{:keys [conn-id]}]
  (w/fragment
   (str conn-id "-root")
   (fn []
     (let [items (w/watch [:items] #(deref row-source))]
       [:div {:id (str conn-id "-root")}
        [:ul {:id (str conn-id "-items")}
         (mapv (fn [i]
                 (w/fragment
                  (str conn-id "-i-" (:id i))
                  (fn [] [:li {:id (str conn-id "-i-" (:id i))} (:text i)])))
               items)]]))))

(defonce registry (atom {}))
(defonce latches (atom {}))

(def eng
  (engine/engine {:components {:c #'component}
                  :render-fn chassis/html
                  :registry registry}))

;;; ==========================================================================
;;; Handlers, one per transport
;;; ==========================================================================
;;; Same engine, same mount, same payload. The only difference is which adapter
;;; holds the stream open.

(defn- open! [sse-gen send!]
  (let [id (engine/connect! eng :c {:send! send!})
        latch (CountDownLatch. 1)]
    ;; `:conn-id` is the id `connect!` returns, so it is written back before the
    ;; first render. Ids are per connection here because the measurement is
    ;; per-connection fragment bookkeeping.
    (swap! registry update id assoc-in [:params :conn-id] id)
    (swap! latches assoc id latch)
    (d*/patch-elements! sse-gen (:html (engine/mount! eng id))
                        {d*/selector "#root" d*/patch-mode d*/pm-outer})
    [id latch]))

(defn jetty-handler
  [request respond _raise]
  (respond
   (d*ring/->sse-response
    request
    {d*ring/on-open
     (fn [sse-gen]
       (let [[id latch] (open! sse-gen (fn [_] nil))]
         ;; Blocks. Under Jetty's async mode this is not a request worker, but it
         ;; is still a thread — which is what `kern.num_taskthreads` caps.
         (.await ^CountDownLatch latch 1 TimeUnit/HOURS)
         (engine/disconnect! eng id)
         (swap! latches dissoc id)))})))

(defn http-kit-handler
  [request]
  (d*hk/->sse-response
   request
   {d*hk/on-open
    (fn [sse-gen]
      ;; http-kit is event-driven: on-open returns immediately and the connection
      ;; is held by the event loop, so there is no thread to park. That is the
      ;; structural difference §8.1 predicted would matter.
      (open! sse-gen (fn [_] nil)))}))

;;; ==========================================================================
;;; Measurement
;;; ==========================================================================

(defn- live-heap-mb
  "Live heap after a GC, so this is retained data rather than uncollected garbage."
  []
  (System/gc)
  (Thread/sleep 400)
  (System/gc)
  (Thread/sleep 400)
  (let [rt (Runtime/getRuntime)]
    (/ (- (.totalMemory rt) (.freeMemory rt)) 1048576.0)))

(defn- thread-count [] (.size (Thread/getAllStackTraces)))

(defn- open-connections!
  "Opens `n` SSE connections with raw sockets, returning how many received a mount.

  Socket creation must not block on reads. An earlier version submitted each read to
  a fixed pool of 64 and the *main loop* then stalled behind it — the driver froze at
  ~4,550 sockets with zero growth over 5 seconds, which looked like a server ceiling
  and was not. Verified by sampling `lsof` twice and seeing delta zero.

  So: open every socket first, then sample a subset for confirmation. Confirmation is
  a sanity check that the server is really mounting, not a census — the authoritative
  count is the server's own context registry.

  Sockets are deliberately never closed: the connection must stay open for the
  server-side measurement to mean anything."
  [port n local-addr]
  (let [held (java.util.ArrayList. (int n))
        errors (atom 0)
        error-kinds (atom {})]
    ;; Phase 1: open, in parallel. Sequential `connect` measured at only ~186
    ;; sockets/sec, which made a 56k target take five minutes per driver and caused
    ;; every earlier run to time out mid-ramp — reporting "how many connected before
    ;; the clock ran out" rather than a capacity limit.
    (let [addr (java.net.InetSocketAddress. "127.0.0.1" (int port))
          ;; Binding to a specific local address widens the (src-ip, src-port,
          ;; dst-ip, dst-port) tuple, so each extra loopback alias grants another
          ;; ~16k source ports. Without this a single host cannot exceed 16,384
          ;; regardless of how many driver processes it runs.
          bind-addr (when local-addr
                      (java.net.InetSocketAddress. ^String local-addr 0))
          req (.getBytes (str "GET /live HTTP/1.1\r\n"
                              "Host: 127.0.0.1\r\n"
                              "Accept: text/event-stream\r\n\r\n")
                         "UTF-8")
          pool (Executors/newFixedThreadPool 48)
          latch (CountDownLatch. (int n))]
      (dotimes [_ n]
        (.submit pool ^Runnable
                 (fn []
                   (try
                     (let [sock (java.net.Socket.)]
                       (.setSoTimeout sock 15000)
                       (.setTcpNoDelay sock true)
                       (when bind-addr (.bind sock bind-addr))
                       (.connect sock addr 60000)
                       (doto (.getOutputStream sock) (.write req) (.flush))
                       ;; ArrayList is not thread-safe; the pool writes concurrently.
                       (locking held (.add held sock)))
                     (catch Exception e
                       (swap! errors inc)
                       ;; Keep one example of each distinct failure. A bare count
                       ;; cannot distinguish "server refused" from "out of ports"
                       ;; from "out of file descriptors", and those imply completely
                       ;; different conclusions.
                       (swap! error-kinds update
                              (str (.getSimpleName (class e)) ": " (.getMessage e))
                              (fnil inc 0)))
                     (finally (.countDown latch))))))
      (.await latch 10 TimeUnit/MINUTES)
      (.shutdown pool))
    ;; Phase 2: confirm a sample, so a mount failure is still visible.
    (let [sample (take 50 (shuffle (vec held)))
          confirmed (atom 0)
          pool (Executors/newFixedThreadPool 25)]
      (doseq [^java.net.Socket sock sample]
        (.submit pool ^Runnable
                 (fn []
                   (try
                     (let [buf (byte-array 2048)
                           got (.read (.getInputStream sock) buf)]
                       (when (and (pos? got)
                                  (str/includes? (String. buf 0 got "UTF-8") "datastar"))
                         (swap! confirmed inc)))
                     (catch Exception _ nil)))))
      (.shutdown pool)
      (.awaitTermination pool 30 TimeUnit/SECONDS)
      {:held held
       :opened (.size held)
       :errors @errors
       :error-kinds @error-kinds
       :sampled (count sample)
       :confirmed @confirmed})))

(defn- serve!
  "Runs only the server, printing heap stats on a timer.

  Separate from the driver because the driver's own threads count against the same
  `kern.num_taskthreads` cap — running both in one JVM made the server appear to die
  at ~1700 connections when half the thread budget had gone to the client."
  [server-name port]
  (let [server server-name
        max-heap (/ (.maxMemory (Runtime/getRuntime)) 1073741824.0)
        stop! (case server
                ;; Platform threads: one parked thread per connection, which is what
                ;; kern.num_taskthreads caps at ~4096 on macOS.
                "jetty" (let [s (jetty/run-jetty jetty-handler
                                                 {:port port :join? false
                                                  :async? true :async-timeout 0
                                                  :max-threads 8000})]
                          #(.stop s))
                ;; Virtual threads. maxConcurrentTasks MUST be raised: it defaults to
                ;; 200 and is a semaphore, so a blocking SSE handler holds a permit
                ;; for the connection's life and client #201 never gets a handler.
                ;; That default reads as "virtual threads cap at 200" and would make
                ;; this comparison meaningless (§8).
                "jetty-vt" (let [s (jetty/run-jetty
                                    jetty-handler
                                    {:port port :join? false
                                     :async? true :async-timeout 0
                                     ;; Jetty 12.0.21 renamed this: `setMaxConcurrentTasks`
                                     ;; no longer exists and the original soak's call to
                                     ;; it now throws. `setMaxThreads` is the current
                                     ;; knob — worth noting when comparing against the
                                     ;; earlier 22.6 KB/conn figure, which was measured
                                     ;; against the older API.
                                     :thread-pool
                                     (doto (org.eclipse.jetty.util.thread.VirtualThreadPool.)
                                       (.setMaxThreads 100000))})]
                             #(.stop s))
                ;; Event-driven: on-open returns immediately, no thread per
                ;; connection to cap.
                ;; `:max-connections` and a deep `:backlog` both matter. The default
                ;; accept backlog overflows under a parallel driver and the failures
                ;; arrive as ConnectException "Operation timed out" on the CLIENT,
                ;; which reads exactly like a server ceiling. Measured at 4,982 with
                ;; 3,595 such timeouts before this was raised.
                "http-kit" (hk/run-server http-kit-handler
                                          {:port port :thread 32
                                           :queue-size 100000
                                           :backlog 65536
                                           :max-connections 200000}))
        baseline (do (Thread/sleep 1500) (live-heap-mb))]
    (println (format "server=%s max-heap=%.1fGB baseline=%.1fMB threads=%d"
                     server max-heap baseline (thread-count)))
    (println "READY")
    (flush)
    ;; Report on a timer so the driver process can read progress without RPC.
    (while true
      (Thread/sleep 3000)
      (let [n (count @registry)]
        (when (pos? n)
          (let [heap (live-heap-mb)]
            (println (format "STAT contexts=%d heap=%.1f threads=%d kb_per_conn=%.1f"
                             n heap (thread-count)
                             (* 1024 (/ (- heap baseline) n))))
            (flush)))))
    (stop!)))

(defn- drive-once!
  "Opens `n` connections and holds them until killed.

  Holds rather than ramping, so the server-side count is a stable plateau rather
  than a high-water mark over a moving ramp — reading a max over a ramp with churn
  is how the first version of this test overreported by ~1,000 connections.

  **Running several of these does not raise the ceiling.** Ephemeral ports are
  allocated system-wide on macOS, not per process. Measured with four drivers each
  asking for 15,000: they got 13191 / 3115 / 4 / 5, summing to the server's total,
  with 16,306 `BindException`s between them. Use one driver per *host*, or one host
  per ~16k connections.

  Optionally binds outgoing sockets to `local-addr`, which is the only way to widen
  the source tuple on a single machine — see the namespace docstring."
  [port n local-addr]
  (let [{:keys [opened errors error-kinds sampled confirmed]}
        (open-connections! port n local-addr)]
    (println (format "opened=%d errors=%d confirmed=%d/%d"
                     opened errors confirmed sampled))
    (doseq [[kind cnt] (sort-by (comp - val) error-kinds)]
      (println (format "  %6d x %s" cnt kind)))
    (flush)
    ;; Park forever. The parent script kills this once the server has been sampled;
    ;; closing here would release the connections we are trying to measure.
    @(promise)))

(defn -main
  "Modes:
    serve <jetty|jetty-vt|http-kit>  run only the server
    drive-once <n> [local-addr]      open n connections and hold. Pass a loopback
                                     alias to widen the source tuple; see the
                                     namespace docstring."
  [& [mode arg & more]]
  (case mode
    "serve" (serve! (or arg "jetty") 3100)
    "drive-once" (drive-once! 3100
                              (Integer/parseInt (or arg "12000"))
                              ;; Third argument: a local address to bind from.
                              (first more))
    (println "usage: -m soak serve <jetty|http-kit> | -m soak drive"))
  (when (= "drive" mode) (System/exit 0)))
