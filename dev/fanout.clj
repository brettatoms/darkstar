(ns fanout
  "Fan-out benchmark: what does one hint to N subscribed viewers actually cost?

  An earlier pass quantified re-derive amplification against a trivial in-memory
  mount. That left the decisive question open: **do queries or the wire dominate?**
  It matters because shared derivation only eliminates query cost. If the
  wire dominates, shared derivation is the wrong optimisation and the 1000x on
  paper is
  mostly theoretical.

  Run:
    clojure -M:slice -m fanout

  Methodology notes, because the last two attempts at this were invalid:

  - **The `Source` does real I/O** (SQLite, on disk). An atom-backed source makes
    per-viewer derivation ~free and forces the conclusion \"the wire dominates\"
    regardless of truth — measured at 0.05us versus 148us, a 3,064x difference.
  - **Stages are timed separately** rather than inferred from a total, so \"queries
    vs wire\" is measured rather than attributed.
  - **`send!` is instrumented, not faked away.** Serialising a patch to a string is
    part of the wire cost and has to be paid.
  - Warm-up runs precede every measurement, since the JIT makes first-run numbers
    meaningless on the JVM."
  (:require [dev.onionpancakes.chassis.core :as chassis]
            [next.jdbc :as jdbc]
            [remuda.cache :as cache]
            [remuda.diff :as diff]
            [remuda.engine :as engine]
            [darkstar.patch :as patch]
            [remuda.pubsub :as pubsub]
            [remuda.render :as render]
            [remuda.source :as source]))

;;; ==========================================================================
;;; A Source doing real I/O
;;; ==========================================================================

(def ^:private db-path "/tmp/darkstar-fanout.db")

(defn- setup-db!
  "5000 rows across 50 lists, indexed. A `:mount` fetches one list — 100 rows."
  []
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})]
    (jdbc/execute! ds ["drop table if exists todos"])
    (jdbc/execute! ds ["create table todos (id integer primary key, list_id int, text varchar)"])
    (with-open [con (jdbc/get-connection ds)]
      (jdbc/execute! con ["begin"])
      (dotimes [i 5000]
        (jdbc/execute! con ["insert into todos (id, list_id, text) values (?,?,?)"
                            i (mod i 50) (str "todo item number " i)]))
      (jdbc/execute! con ["commit"]))
    (jdbc/execute! ds ["create index idx_list on todos(list_id)"])
    ds))

(defn- sqlite-source
  [ds]
  (reify source/Source
    (fetch [_ query] (source/fetch _ query nil))
    (fetch [_ [_ list-id] _basis]
      (mapv (fn [r] {:id (:todos/id r) :text (:todos/text r)})
            (jdbc/execute! ds ["select id, text from todos where list_id = ?" list-id])))
    (basis [_] nil)))

;;; ==========================================================================
;;; The component under test
;;; ==========================================================================

(def component
  {:mount
   (fn [{:keys [source params]}]
     {:items (with-meta (source/fetch source [:list (:list-id params)])
               {:live/key :id})})

   :render
   (fn [{:keys [items] ::engine/keys [id]}]
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

;;; ==========================================================================
;;; Timing
;;; ==========================================================================

(defn- ms [nanos] (/ nanos 1e6))

(defmacro timed
  "Returns [result elapsed-nanos]."
  [& body]
  `(let [t0# (System/nanoTime)
         r# (do ~@body)]
     [r# (- (System/nanoTime) t0#)]))

(defn- bench-stages
  "One hint to `n` viewers, timing each stage separately.

  Returns nanos for: mount (the SQLite queries), render+diff+patch (turning views
  into patch instructions), and send (serialising and handing to the transport)."
  [ds n]
  (let [src (sqlite-source ds)
        reg (pubsub/registry)
        bus (pubsub/local-pubsub)
        sent (atom 0)
        bytes-sent (atom 0)
        eng (engine/engine {:components {:c component}
                            :render-fn chassis/html})
        ids (doall
             (for [i (range n)]
               (let [id (engine/connect!
                         eng :c
                         {:params {:list-id (mod i 50)}
                          ;; Serialising to a string is part of the wire cost, so
                          ;; it is paid here rather than optimised away.
                          :send! (fn [instrs]
                                   (swap! sent inc)
                                   (swap! bytes-sent +
                                          (reduce + 0 (map #(count (str (:html %) (:selector %)))
                                                           instrs))))})]
                 (pubsub/subscribe-context! reg bus id [[:list (mod i 50)]])
                 (engine/mount! eng id {:source src})
                 id)))]
    ;; Change the data, so a refresh produces real patches rather than an empty
    ;; diff. A benchmark of "nothing changed" would measure the wrong thing.
    (jdbc/execute! ds ["update todos set text = text || '+' where id % 10 = 0"])
    ;; A hint on every list dirties every viewer.
    (doseq [l (range 50)] (pubsub/publish! bus [:list l]))
    (let [{:keys [contexts]} (pubsub/flush-dirty! reg)
          ;; Stage 1: just the queries, as `:mount` would run them.
          [_ mount-ns] (timed (doseq [_ contexts] (source/fetch src [:list 7])))
          ;; Stage 2+3: the real hint-driven refresh, which re-mounts, diffs,
          ;; renders the changed fragments and pushes them. `refresh!` rather than
          ;; `reconnect!` — the latter returns HTML without sending, so using it
          ;; here measured zero wire cost, which is what exposed the missing push
          ;; path in the first place.
          _ (reset! sent 0)
          _ (reset! bytes-sent 0)
          [_ rebuild-ns] (timed (doseq [id contexts]
                                  (engine/refresh! eng id {:source src}
                                                   {:diff-fn diff/diff
                                                    :patches-fn patch/ops->patches
                                                    :retarget-fn patch/path->selector})))]
      {:viewers n
       :dirtied (count contexts)
       :mount-ns mount-ns
       :rebuild-ns rebuild-ns
       :sends @sent
       :bytes @bytes-sent
       :ids ids})))

(defn- bench-cached
  "Same fan-out, but with both cache entry points active.

  The component declares a `:derive-key` over list-id, which is what these viewers
  actually differ by — and, critically, is the *whole* of what `:mount` reads here.
  A real multitenant app would have to include the tenant."
  [ds n]
  (let [src (sqlite-source ds)
        c (cache/cache)
        cached-component (assoc component :derive-key (fn [ctx] (:list-id (:params ctx))))
        reg (pubsub/registry)
        bus (pubsub/local-pubsub)
        bytes-sent (atom 0)
        eng (engine/engine {:components {:c cached-component}
                            :render-fn #(cache/cached-render c chassis/html %)})
        ids (doall
             (for [i (range n)]
               (let [id (engine/connect!
                         eng :c
                         {:params {:list-id (mod i 50)}
                          :send! (fn [instrs]
                                   (swap! bytes-sent +
                                          (reduce + 0 (map #(count (str (:html %) (:selector %)))
                                                           instrs))))})]
                 (pubsub/subscribe-context! reg bus id [[:list (mod i 50)]])
                 (engine/mount! eng id {:source src})
                 id)))]
    (jdbc/execute! ds ["update todos set text = text || '+' where id % 10 = 0"])
    (doseq [l (range 50)] (pubsub/publish! bus [:list l]))
    (let [{:keys [contexts]} (pubsub/flush-dirty! reg)
          ;; A hint invalidates the cache: it announced that the data changed, so
          ;; serving a cached derivation would serve exactly what the hint said was
          ;; stale.
          _ (cache/invalidate! c)
          [_ rebuild-ns] (timed
                          (doseq [id contexts]
                            (engine/refresh! eng id
                                             {:source src
                                              :remuda.engine/cache c
                                              :component-name :c}
                                             {:diff-fn diff/diff
                                              :patches-fn patch/ops->patches
                                              :retarget-fn patch/path->selector})))]
      {:viewers n :rebuild-ns rebuild-ns :bytes @bytes-sent
       :stats (cache/stats c) :ids ids})))

(defn- shared-derivation-estimate
  "What sharing would save: one query per distinct params instead of one per viewer."
  [ds n distinct-params]
  (let [src (sqlite-source ds)]
    ;; Warm the page cache so this is not measuring first-read disk.
    (dotimes [_ 20] (source/fetch src [:list 0]))
    (let [[_ naive] (timed (dotimes [i n] (source/fetch src [:list (mod i 50)])))
          [_ shared] (timed (dotimes [i distinct-params]
                              (source/fetch src [:list (mod i 50)])))]
      {:naive-ns naive :shared-ns shared})))

(defn -main
  [& _]
  (println "Setting up SQLite (5000 rows, 50 lists, indexed)...")
  (let [ds (setup-db!)]
    (println "Warming up...")
    (bench-stages ds 50)
    (println)
    (println "=== One hint per list, to N viewers across 50 lists ===")
    (println (format "%8s %10s %12s %14s %10s %12s"
                     "viewers" "dirtied" "queries(ms)" "rebuild(ms)" "sends" "wire(KB)"))
    (doseq [n [100 500 1000]]
      (let [r (bench-stages ds n)]
        (println (format "%8d %10d %12.1f %14.1f %10d %12.1f"
                         (:viewers r) (:dirtied r)
                         (ms (:mount-ns r)) (ms (:rebuild-ns r))
                         (:sends r) (/ (:bytes r) 1024.0)))))
    (println)
    (println "=== Where does the time go, at 1000 viewers? ===")
    (let [r (bench-stages ds 1000)
          q (ms (:mount-ns r))
          total (ms (:rebuild-ns r))]
      (println (format "  queries alone:            %8.1f ms" q))
      (println (format "  full rebuild (all stages):%8.1f ms" total))
      (println (format "  => queries are %.0f%% of a rebuild" (* 100.0 (/ q total))))
      (println (format "  wire volume:              %8.1f KB" (/ (:bytes r) 1024.0))))
    (println)
    (println "=== What shared derivation would save ===")
    (let [{:keys [naive-ns shared-ns]} (shared-derivation-estimate ds 1000 50)]
      (println (format "  1000 viewers, one query each: %8.1f ms" (ms naive-ns)))
      (println (format "  50 distinct params, shared:   %8.1f ms" (ms shared-ns)))
      (println (format "  => %.0fx less query work" (double (/ naive-ns (max 1 shared-ns))))))
    (println)
    (println "=== With both cache entry points active ===")
    (println (format "%8s %14s %12s %28s" "viewers" "rebuild(ms)" "wire(KB)" "cache"))
    (doseq [n [100 500 1000]]
      (let [r (bench-cached ds n)
            st (:stats r)]
        (println (format "%8d %14.1f %12.1f  d:%d/%d f:%d/%d"
                         (:viewers r) (ms (:rebuild-ns r)) (/ (:bytes r) 1024.0)
                         (:derived-hits st) (:derived-misses st)
                         (:fragment-hits st) (:fragment-misses st)))))
    (println "  (d: derived hits/misses, f: fragment hits/misses)")
    (println)
    (println "Numbers above are the evidence; interpretation is elsewhere.")
    (System/exit 0)))
