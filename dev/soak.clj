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

  Also raise the server's descriptor limit — macOS defaults
  `launchctl limit maxfiles` to 256, which caps a JVM around ~4,900 fds regardless
  of `ulimit -n` in the shell.

  ## What has been measured, and what has not

  Measured on one laptop (macOS, JDK 26, 8 cores):

  | transport | outcome |
  |---|---|
  | http-kit | 16,315 held flat, 17 threads, **3.4 KB/conn** |
  | jetty + virtual threads | comparable counts, **22.6 KB/conn**, 21 threads |
  | jetty + platform threads | **fails at 4,060** — `kern.num_taskthreads`, thousands of `pthread_create` failures |

  **Heap was never the binding constraint.** At 16,315 connections http-kit used 65 MB
  of a 2 GB heap — about 3%. 2 GB and 4 GB runs were therefore indistinguishable.

  So the headline question — *how many connections fit in 2 GB?* — is **unanswered**.
  The honest extrapolation is from KB/conn: 2 GB / 3.4 KB is roughly 600k connections
  of *view state*, but that excludes socket buffers, TLS state and per-socket kernel
  memory, and assumes GC behaves at 600k live objects as it does at 16k. Treat it as
  arithmetic, not a measurement.

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
            [remuda.engine :as engine]
            [remuda.render :as render])
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

(def component
  {:mount (fn [_] {:items (with-meta rows {:live/key :id})})
   :render (fn [{:keys [items] ::engine/keys [id]}]
             (render/boundary
              []
              [:div {:id id}
               (render/boundary [:items]
                                [:ul {:id (str id "-items")}
                                 (for [i items]
                                   (render/boundary [:items (:id i)]
                                                    [:li {:id (str id "-i-" (:id i))}
                                                     (:text i)]))])]))
   :on {}})

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
  (let [id (engine/connect! eng :c {:send! send!
                                    :close! #(d*/close-sse! sse-gen)})
        latch (CountDownLatch. 1)]
    (swap! latches assoc id latch)
    (d*/patch-elements! sse-gen (engine/mount! eng id {})
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
      ;; structural difference that was expected to matter.
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
                ;; this comparison meaningless.
                "jetty-vt" (let [s (jetty/run-jetty
                                    jetty-handler
                                    {:port port :join? false
                                     :async? true :async-timeout 0
                                     :thread-pool
                                     (doto (org.eclipse.jetty.util.thread.VirtualThreadPool.)
                                       (.setMaxConcurrentTasks 100000))})]
                             #(.stop s))
                ;; Event-driven: on-open returns immediately, no thread per
                ;; connection to cap.
                "http-kit" (hk/run-server http-kit-handler
                                          {:port port :thread 32
                                           :queue-size 100000}))
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
