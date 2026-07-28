(ns darkstar.diagnose
  "Development-mode checks that turn `watch`'s silent failures into loud ones.

  ## Why this exists

  Five real bugs were found by building two applications on `watch`, and **all five
  were silent** — wrong output or frozen output, never an exception:

  | # | mistake | symptom |
  |---|---|---|
  | 1 | two sibling fragments read one topic; only one was patched | one goes stale |
  | 2 | a fragment read one topic twice; the second read overwrote the first | pruning suppresses a real change |
  | 3 | a reader rebuilt its collection every call | pruning never fires; slow, not wrong |
  | 4 | a value was passed in as an argument instead of read via `watch` | fragment subscribes to nothing, freezes |
  | 5 | a topic name was invented at the read site that nothing publishes | fragment never updates |

  1 and 2 were fixed in the engine and are now covered by tests. 3, 4 and 5 are
  **application** mistakes the engine cannot fix, because the application is free to
  write whatever readers it likes. So they are detected here instead.

  ## Cost, and why this is opt-in

  Every check needs work the hot path should not do: calling readers a second time and
  keeping a registry of published topics. So these are not wired into the engine — they
  are called explicitly, from a test or a REPL, via `check-component`.

  A `render-fn` wrapper was tried first and removed: by the time a `render-fn` runs the
  tree is already built and the recording is gone, so it could see nothing worth
  checking. Detection has to happen where the recording is, which is why the entry
  point takes a component rather than wrapping the renderer.

  ## What is deliberately NOT checked

  Plain hiccup that is not wrapped in `fragment`. The two styles mix — a component may
  hold `watch`-managed fragments alongside markup the application pushes itself — so an
  element with an id but no fragment is a legitimate choice, not a mistake."
  (:require [darkstar.watch :as w]))

;;; ==========================================================================
;;; 1. Readers that defeat pruning
;;; ==========================================================================

(defn unstable-readers
  "Readers whose value is not `identical?` across two consecutive calls.

  This is bug 3, and it is the most insidious of the five because nothing is *wrong* —
  the screen is correct, it is just re-rendered on every hint. Found in the dashboard,
  where `all-jobs` sorted into a fresh vector and `summary` built a fresh map per call,
  so the two SLOWEST fragments re-rendered ten times a second.

  `watch` compares reads with `identical?` first and falls back to `=` only for
  scalars, so a reader that rebuilds a collection always compares as changed. The fix
  is to compute derived values once at write time and hand out the cached value.

  Returns `[{:fragment id :topic t :type class}]`, empty when everything is stable.
  Scalars are skipped: a reader returning a fresh `Long` or `String` is fine, because
  `same?` falls back to `=` for those.

  Reported once per topic, against the **innermost** fragment that reads it. Topics
  bubble up to containing fragments, so a naive walk reports the same reader once per
  ancestor — three copies of one problem for a three-deep tree, which buries the
  signal it exists to raise."
  [recording]
  (let [frags (:fragments recording)
        ;; A fragment is innermost for a topic when no fragment it contains also reads
        ;; that topic.
        innermost? (fn [fid topic]
                     (not-any? (fn [inner]
                                 (contains? (:topics (get frags inner)) topic))
                               (keys (:fragments (get frags fid)))))]
    (vec
     (for [[fid frag] frags
           [topic entries] (:reads frag)
           :when (innermost? fid topic)
           {:keys [value read-fn]} entries
           :let [again (try (read-fn) (catch Throwable _ ::threw))]
           :when (and (not= again ::threw)
                      (coll? value)
                      (not (identical? value again))
                    ;; Equal but not identical is exactly the defeat-pruning case. Not
                    ;; equal means the data genuinely changed between the two calls,
                    ;; which is not a defect.
                      (= value again))]
       {:fragment fid :topic topic :type (class value)}))))

;;; ==========================================================================
;;; 2. Fragments that subscribe to nothing
;;; ==========================================================================

(defn silent-fragments
  "Fragments that read no topic at all.

  This is bug 4. A fragment given its data as an argument rather than reading it
  through `watch` renders correctly once and then never again — nothing can invalidate
  it, because it declared no dependency. Found in the dashboard, where `job-row` took
  the job map from the list instead of watching `[:job id]`, so a progress bar that
  changes ten times a second would have been frozen at whatever the list last held.

  A fragment with no reads is sometimes intentional — a static panel that is patchable
  but never invalidated — so this is a *report* rather than an error. Pass `:allow` to
  narrow it to the ones that surprise you."
  [recording & {:keys [allow] :or {allow #{}}}]
  (vec (for [[fid frag] (:fragments recording)
             :when (and (empty? (:topics frag))
                        (not (contains? allow fid)))]
         {:fragment fid})))

;;; ==========================================================================
;;; 3. Topics read but never published
;;; ==========================================================================

(defonce ^{:doc "Every topic the application has published, for `orphan-topics`.

  A set rather than a count: the question is whether a topic has EVER been published,
  and a topic published once is enough to prove the name is real."}
  published
  (atom #{}))

(defn note-published!
  "Records that `topic` was published. Call from the application's publish path."
  [topic]
  (swap! published conj topic)
  topic)

(defn orphan-topics
  "Topics a render subscribed to that nothing has ever published.

  This is bug 5, and it is the one that cost the most time: porting the chat app I
  wrote `[:members channel-id]` where the server published `[:channel channel-id]`.
  The first render was correct, the fragment never updated again, and nothing errored —
  because a topic name invented at the read site has no counterpart at the publish site
  to disagree with.

  Necessarily a *heuristic*: a topic may be legitimately unpublished at the moment a
  render happens and published later. So this is best read after an application has
  been exercised, and it warns rather than throws.

  Pass `:known` for topics that are expected never to be published — per-connection UI
  state whose publisher runs only on interaction, for example."
  [recording & {:keys [known] :or {known #{}}}]
  (let [seen @published]
    (vec (for [topic (:topics recording)
               :when (and (not (contains? seen topic))
                          (not (contains? known topic)))]
           {:topic topic}))))

;;; ==========================================================================
;;; Running the checks
;;; ==========================================================================

(defn check
  "Runs every check against one `render-recording` result. Returns a problem seq."
  [recording & {:keys [allow-silent known-topics]
                :or {allow-silent #{} known-topics #{}}}]
  (concat
   (map #(assoc % :problem :unstable-reader
                :message (str "reader for " (pr-str (:topic %)) " in fragment "
                              (pr-str (:fragment %))
                              " returns an equal but not identical? "
                              (.getSimpleName ^Class (:type %))
                              " on every call, so this fragment can never be pruned."
                              " Compute it once at write time and cache it."))
        (unstable-readers recording))
   (map #(assoc % :problem :silent-fragment
                :message (str "fragment " (pr-str (:fragment %))
                              " reads no topic, so nothing can ever update it."
                              " Read its data with `watch` rather than passing it in,"
                              " or list it in :allow-silent if it is meant to be"
                              " static."))
        (silent-fragments recording :allow allow-silent))
   (map #(assoc % :problem :orphan-topic
                :message (str "topic " (pr-str (:topic %))
                              " is watched but has never been published. Check the"
                              " name against the publish site, or list it in"
                              " :known-topics."))
        (orphan-topics recording :known known-topics))))

(defn check-component
  "Renders `component-fn` with `params` under a recording and reports problems.

  This is the practical entry point: it is what a test or a REPL calls to find out
  whether a component has any of the three application-level defects, without needing
  an engine or a connection.

      (diagnose/check-component my-app {:conn-id \"c1\"})
      ;=> [{:problem :silent-fragment :fragment \"row-1\" :message \"...\"}]"
  [component-fn params & opts]
  (let [recording (w/render-recording #(component-fn params))]
    (vec (apply check recording opts))))
