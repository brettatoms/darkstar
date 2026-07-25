(ns darkstar.fuzz-test
  "Fuzzing for the DOM translation layer.

  Split out of remuda's fuzz suite when patch moved here: these specs assert that
  every op the differ can emit translates to a *shippable* DOM patch, which is a
  claim about datastar's vocabulary rather than about the diff. `PLAN.md` §1.3 made
  it a design constraint that nothing the differ emits may be unshippable."
  (:require [clojure.string :as str]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [darkstar.patch :as patch]
            [remuda.diff :as diff]
            [darkstar.generators :as g]))

;;; ==========================================================================
;;; patch — every op must be shippable
;;; ==========================================================================

(def ^:private valid-modes
  #{:outer :inner :remove :prepend :append :before :after :replace})

(defspec every-op-translates-to-a-valid-patch 1000
  ;; `PLAN.md` §1.3 made this a design constraint: nothing the differ emits may be
  ;; unshippable. Previously asserted against a hand-written scenario list.
  (prop/for-all [[old new] g/gen-derived-change]
    (let [ops (diff/diff old new)
          patches (patch/ops->patches {:component-id "c1" :boundaries #{[]}} ops)]
      (every? (fn [p]
                (and (contains? valid-modes (:mode p))
                     (string? (:selector p))
                     (str/starts-with? (:selector p) "#")
                     ;; A leaked sentinel would produce a selector matching nothing.
                     (not (str/includes? (:selector p) "remuda"))))
              patches))))

(defspec patch-translation-never-throws 1000
  (prop/for-all [[old new] g/gen-keyed-change]
    (do (patch/ops->patches {:component-id "c1"
                             :boundaries #{[] [:items] [:items :*]}}
                            (diff/diff old new))
        true)))

