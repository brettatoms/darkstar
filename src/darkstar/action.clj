(ns darkstar.action
  "Builds the client-side expressions that invoke `:on` handlers.

  1, which recorded this as \"unresolved, and currently unsound\".
  This namespace resolves it. The problem was not ergonomics — it was that the
  hand-written form was *wrong*:

      ;; before
      [:button {:data-on:click (str \"@post('/act?event=remove&id=\" (:id i) \"')\")}]

  Three defects, all verified against a real render rather than reasoned about:

  1. **Escaping.** Chassis HTML-escapes the attribute, so the markup is valid, but
     the browser un-escapes it before the expression is parsed. An arg containing
     `'` closes datastar's string literal early — `o'brien` became
     `@post('...id=o'brien')`, a syntax error. An arg containing `&` split into two
     query params. `=` and `+` corrupted the parse.
  2. **Args were int-only by accident.** The receiving handler did
     `(parse-long (get params \"id\"))`, hardcoded. A string arg silently became
     nil; a boolean could not round-trip at all.
  3. **Types were lost.** Query strings carry only strings, so every handler
     needed per-arg coercion it never declared.

  ## The fix: a JSON payload, not a query string

  Datastar's fetch actions take a `payload` option that **replaces** the signal set
  and is sent as the request body:

      @post('/live/act', {payload: {\"event\":\"remove\",\"id\":42}})

  This kills all three defects structurally rather than by escaping more
  carefully:

  - The arg sits inside a **JSON string**, where `'` and `&` are unremarkable. The
    nested-quoting problem does not arise, because there is no nested quoting.
  - JSON has types, so `42`, `\"42\"`, `true` and `null` stay distinguishable and
    the server never guesses.

  Escaping does not disappear entirely — it moves somewhere tractable. The JSON is
  embedded in an HTML attribute inside a JS expression, so a `'` or `\\` *within a
  string value* still has to be escaped for the JS literal. That is what
  `write-json` handles, and it is the one case worth testing hardest, since \"just
  use JSON\" is only 90% of the answer.

  ## `liveId` has to be merged in

  Not anticipated by  `payload` **replaces** the signals datastar would
  otherwise send, and `liveId` is a server-pushed *signal* — so a bare payload
  drops it and every action fails with \"no live context\". The expression therefore
  merges the live-id signal back in explicitly with datastar's `$` syntax.

  ## Why the path is a parameter

  The dispatch path belongs to whatever mounted the route,
  so hardcoding `/live/act` here would reintroduce exactly the drift that reading
  ids from the render removed
  for element ids. Callers pass it; adapters supply it from their own config.

  ## No arg schemas, deliberately

  Whether `:on` handlers should declare arg schemas is deliberately unsettled.
  They do not, for now. Types survive the wire on their own once args are JSON, which was the
  actual complaint. Adding an optional `:args` predicate later is additive — it
  slots in front of the handler call without changing this wire format or any
  existing handler — so the cheap version is not a dead end. Tracked in 

  Dependency-free, like the rest of the core: the JSON writer below is a few
  lines rather than a reason to put charred on the core's classpath. It follows
  `snapshot`, which hand-rolls its own encoding for the same reason."
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]))

;;; ==========================================================================
;;; JSON
;;; ==========================================================================
;;; Only what an arg map can contain needs support. This is not a general JSON
;;; writer and should not grow into one: if a caller needs to send something this
;;; cannot express, the right fix is for the adapter to pass a real JSON writer,
;;; not for the core to acquire a dependency.

(defn- escape-string
  "Escapes `s` for a JSON string that will also survive an HTML attribute.

  Beyond the JSON minimum, two characters are escaped for where the result ends
  up rather than for JSON itself:

  - `'` — the JSON sits inside a single-quoted region of a datastar expression,
    and this is defect 1 from the namespace docstring. `\\u0027` is used rather
    than `\\'` because a backslash-apostrophe is *not* valid JSON, so a strict
    reader on the way back in would reject it.
  - `<` — so a value can never begin a `</script>` sequence when the expression is
    rendered inside an inline script rather than an attribute.

  Both are pure JSON escapes, so a standard parser reads them back unchanged."
  [^String s]
  (let [sb (StringBuilder.)]
    (doseq [^Character ch s]
      (let [c (int ch)]
        (cond
          (= ch \") (.append sb "\\\"")
          (= ch \\) (.append sb "\\\\")
          (= ch \newline) (.append sb "\\n")
          (= ch \return) (.append sb "\\r")
          (= ch \tab) (.append sb "\\t")
          (= ch \formfeed) (.append sb "\\f")
          (= ch \backspace) (.append sb "\\b")
          (= ch \') (.append sb "\\u0027")
          (= ch \<) (.append sb "\\u003c")
          ;; Control characters have no literal form in JSON.
          (< c 0x20) (.append sb (format "\\u%04x" c))
          :else (.append sb ch))))
    (str sb)))

(defn- json-key
  "Renders a map key as a JSON object key.

  Keywords lose their namespace deliberately: `:foo/bar` becomes `\"bar\"`, because
  the round trip cannot restore it (the client sends back a plain string) and
  silently returning a differently-namespaced keyword to the handler would be
  worse than dropping it. Callers needing a namespace should use a string key."
  [k]
  (str \" (escape-string (cond
                           (keyword? k) (name k)
                           (symbol? k) (name k)
                           :else (str k)))
       \"))

;;; --------------------------------------------------------------------------
;;; Escape hatch: values that are JS expressions rather than data
;;; --------------------------------------------------------------------------
;;; Some args are genuinely client-side expressions — reading an input's current
;;; value is the common one:
;;;
;;;     (a/post path :draft {:text (a/raw "evt.target.value")})
;;;
;;; Those cannot go through `write-json`, which would correctly quote them into the
;;; literal string "evt.target.value". So they need a marked path.
;;;
;;; It is a distinct wrapper rather than "strings starting with evt." or some other
;;; sniffing, because guessing whether a string is data or code is exactly the
;;; ambiguity that produced defect 1. Here the caller states it, the escaping is
;;; visibly theirs to own, and a reviewer can grep for `raw`.

(defrecord Raw [expr])

(defn raw
  "Marks `expr` as a JavaScript expression to be emitted **unescaped**.

  For args whose value must be computed on the client, such as
  `(raw \"evt.target.value\")`. Everything else should be plain data and go through
  the normal escaping — `raw` is an escape hatch, and anything interpolated into it
  is the caller's responsibility. Never pass user or database content to it."
  [expr]
  (->Raw (str expr)))

(defn write-json
  "Serializes `x` as JSON. Supports what an arg map can hold: nil, booleans,
  numbers, strings, keywords, symbols, maps, and sequential collections.

  A `raw` value is emitted verbatim, which is the one case where the caller has
  taken responsibility for escaping.

  Keywords become strings — a JSON round trip cannot preserve keyword-ness, so
  `:a` arrives back as `\"a\"`. Handlers receiving enum-like args should compare
  against strings, or the adapter should coerce.

  Throws on anything else rather than emitting a `toString`: silently shipping the
  print form of an arbitrary object is how defect 2 (`parse-long` on whatever
  arrived) happened in the first place."
  [x]
  (cond
    (nil? x) "null"
    ;; Before the string branch: a Raw is emitted as-is, by the caller's choice.
    (instance? Raw x) (:expr x)
    (boolean? x) (str x)
    ;; Ratios and BigDecimals have print forms that are not valid JSON numbers
    ;; (1/3, 1.0M), so they are converted rather than printed.
    (ratio? x) (str (double x))
    (decimal? x) (str (double x))
    (and (number? x) (Double/isNaN (double x)))
    (throw (ex-info "NaN cannot be represented in JSON" {:value x}))
    (and (number? x) (Double/isInfinite (double x)))
    (throw (ex-info "Infinity cannot be represented in JSON" {:value x}))
    (number? x) (str x)
    (string? x) (str \" (escape-string x) \")
    (keyword? x) (str \" (escape-string (name x)) \")
    (symbol? x) (str \" (escape-string (name x)) \")
    (map? x) (str "{" (str/join "," (map (fn [[k v]]
                                           (str (json-key k) ":" (write-json v)))
                                         x))
                  "}")
    (or (sequential? x) (set? x))
    (str "[" (str/join "," (map write-json x)) "]")
    :else (throw (ex-info "Cannot serialize value as an action arg"
                          {:value x :type (type x)}))))

;;; ==========================================================================
;;; The expression
;;; ==========================================================================

(def ^:private default-signal
  "The signal the server pushes to identify a live context."
  "liveId")

(defn action
  "Builds a datastar expression invoking `event` (with optional `args`) at `path`
  over `method`.

  Prefer the method-named wrappers — `post`, `get`, `put`, `patch`, `delete` — which
  is where callers should normally enter. This is the general form they share.

  `args` keys must not collide with `\"event\"` or the live-id signal name; that
  throws rather than silently overwriting the dispatch target, since an arg named
  `event` would otherwise redirect the action to a different handler.

  Options:
  - `:signal`  the live-id signal name, default `\"liveId\"`

  The live id is interpolated as `$liveId` rather than baked in, because `payload`
  replaces the signal set that would normally carry it (see the namespace
  docstring) and the id is not known at render time on a reconnect."
  [method path event args {:keys [signal]}]
  (when (str/blank? (str path))
    (throw (ex-info "an action needs a dispatch path"
                    {:method method :path path})))
  (when (nil? event)
    (throw (ex-info "an action needs an event" {:method method :path path})))
  (let [signal (or signal default-signal)
        reserved #{"event" signal}
        clashes (filter #(contains? reserved (name %)) (keys args))]
    (when (seq clashes)
      (throw (ex-info "action args may not shadow the dispatch keys"
                      {:clashes (vec clashes) :reserved reserved})))
    ;; The payload is assembled as text, not as a map, because the live id is a
    ;; JS *expression* ($liveId) rather than a value — it cannot survive
    ;; write-json, which would quote it into the literal string "$liveId".
    (let [pairs (concat [(str "\"event\":" (write-json (name event)))]
                        (map (fn [[k v]]
                               (str (json-key k) ":" (write-json v)))
                             args)
                        [(str \" signal "\":$" signal)])]
      (str "@" method "('" path "', {payload: {" (str/join "," pairs) "}})"))))

;;; --------------------------------------------------------------------------
;;; Method-named entry points
;;; --------------------------------------------------------------------------
;;; Named for the HTTP method rather than a single `act` so the method is visible
;;; at the call site and GET/DELETE are expressible without an options map.
;;;
;;; Deliberately NOT named `$post`: `$` already means "read this signal" inside a
;;; datastar expression — it is why the payload emits `$liveId` — and reusing the
;;; sigil for "build an action" would make it mean two unrelated things within a
;;; single line. The namespace alias supplies the disambiguation instead:
;;;
;;;     (:require [darkstar.action :as a])
;;;     [:button {:data-on:click (a/post "/live/act" :remove {:id 42})}]
;;;
;;; `a/get` shadows `clojure.core/get` only under `:refer`, which callers should
;;; not use here.
;;;
;;; ## GET and DELETE carry the payload differently
;;;
;;; Verified in datastar's runtime, not assumed: the gate is
;;; `ot = e => !["GET","DELETE"].includes(e)` and the branch is
;;; `ot(t) ? Y.body = F : U.set("datastar", F)`. So POST/PUT/PATCH send the JSON as
;;; a request body, while GET/DELETE send it as a `datastar` query parameter.
;;;
;;; That difference does not reintroduce defect 1. The value is still
;;; `JSON.stringify`d and then encoded by `URLSearchParams`, both by datastar —
;;; the escaping this namespace had to get right was in the *expression* it builds,
;;; and there is none of that either way. Adapters do need to read args from the
;;; query parameter rather than the body for those two methods.

(defn post
  "A `@post` action expression. See `action`."
  ([path event] (action "post" path event nil nil))
  ([path event args] (action "post" path event args nil))
  ([path event args opts] (action "post" path event args opts)))

(defn get
  "A `@get` action expression. Sends args as a `datastar` query parameter rather
  than a body — see the section comment above. See `action`."
  ([path event] (action "get" path event nil nil))
  ([path event args] (action "get" path event args nil))
  ([path event args opts] (action "get" path event args opts)))

(defn put
  "A `@put` action expression. See `action`."
  ([path event] (action "put" path event nil nil))
  ([path event args] (action "put" path event args nil))
  ([path event args opts] (action "put" path event args opts)))

(defn patch
  "A `@patch` action expression. See `action`."
  ([path event] (action "patch" path event nil nil))
  ([path event args] (action "patch" path event args nil))
  ([path event args opts] (action "patch" path event args opts)))

(defn delete
  "A `@delete` action expression. Sends args as a `datastar` query parameter rather
  than a body — see the section comment above. See `action`."
  ([path event] (action "delete" path event nil nil))
  ([path event args] (action "delete" path event args nil))
  ([path event args opts] (action "delete" path event args opts)))
