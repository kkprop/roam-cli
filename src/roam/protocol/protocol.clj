(ns roam.protocol.protocol
  "Transport layer contract — selectors and EID helpers.
   core/ uses these constants instead of hardcoding pull patterns.
   The actual transport is roam.protocol.roam (standalone fns, clean for bb).")

;; ── Selectors (pull pattern constants) ───────────────────────────────────────

(def selector-basic
  "[:block/uid :block/string {:block/children [:block/uid :block/string]}]")

(def selector-deep
  "[:block/uid :block/string :create/time :edit/time {:block/children ...}]")

(def selector-page
  "[:node/title :block/uid {:block/children [:block/uid :block/string {:block/children ...}]}]")

(def selector-all
  "[* {:block/children ...}]")

;; ── EID helpers ──────────────────────────────────────────────────────────────

(defn block-eid [uid] (str "[:block/uid \"" uid "\"]"))
(defn page-eid [title] (str "[:node/title \"" title "\"]"))
