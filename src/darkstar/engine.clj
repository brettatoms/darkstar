(ns darkstar.engine
  "The options that wire remuda's engine to datastar's DOM vocabulary.

  Remuda's engine takes its diffing, patch translation and re-targeting as
  injected functions, which is what keeps `remuda` free of CSS selectors and patch
  modes. Every darkstar caller needs the same three, so they are named once here
  rather than assembled at each call site.

  This exists as its own namespace so `darkstar.patch` can stay `:require`-free —
  that namespace is deliberately standalone and testable with nothing else on the
  classpath, and importing `remuda.diff` into it just to hold a convenience map
  would spend that property cheaply."
  (:require [darkstar.patch :as patch]
            [remuda.diff :as diff]))

(def dispatch-opts
  "The `opts` map for `remuda.engine/dispatch!`, `refresh!` and `transition!`.

  The three keys are not independent, and **omitting `:retarget-fn` is a silent
  DOM-corrupting bug**, which is the reason this is a bundle rather than advice.

  `:retarget-fn` handles the widening the engine discovers *after* translation: a
  boundary that existed when the path was resolved but that the render did not
  actually mark. The engine then re-renders the component root, and the patch's
  target has to widen with it. Without it the patch keeps its child selector while
  carrying root HTML — `#c1-n` receiving the whole `<div>` — which is exactly the
  mismatch that reading ids from the render exists to prevent.

  The engine cannot construct that target itself without knowing what a target
  *is*, so it asks. That indirection is the seam: remuda says \"the root\", darkstar
  answers `#c1`.

  Verified by construction rather than assumed. With a component whose render marks
  only `[]` while a patch targets `[:n]`:

      without :retarget-fn  ->  selector=#c1-n  root?=true   (mismatch)
      with    :retarget-fn  ->  selector=#c1    root?=true"
  {:diff-fn diff/diff
   :patches-fn patch/ops->patches
   :retarget-fn patch/path->selector})
