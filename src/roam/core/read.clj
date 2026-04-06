(ns roam.core.read
  "API-agnostic read operations. No HTTP, no auth, no Roam-specific logic."
  (:require [clojure.string :as str]
            [roam.protocol.protocol :as proto]
            [roam.protocol.roam :as roam]))

;; ── Block / Page pull ────────────────────────────────────────────────────────

(defn pull-block [graph-key uid & {:keys [deep] :or {deep false}}]
  (let [selector (if deep proto/selector-deep proto/selector-basic)
        result (roam/pull graph-key (proto/block-eid uid) selector)]
    (if (:error result) result (:result result))))

(defn pull-page [graph-key title]
  (let [result (roam/pull graph-key (proto/page-eid title) proto/selector-page)]
    (if (:error result) result (:result result))))

;; ── Daily page ───────────────────────────────────────────────────────────────

(defn daily [graph-key]
  (pull-page graph-key (roam/daily-title)))

;; ── Context (ancestor chain) ─────────────────────────────────────────────────

(defn- get-parent [graph-key uid]
  (let [q "[:find ?puid ?pstr :in $ ?cuid :where
            [?c :block/uid ?cuid] [?p :block/children ?c]
            [?p :block/uid ?puid] [?p :block/string ?pstr]]"
        result (roam/q graph-key q [uid])]
    (when-not (:error result)
      (when-let [[puid pstr] (first (:result result))]
        {:uid puid :string pstr}))))

(defn context
  "Walk ancestor chain from block to root. Returns {:block ... :ancestors [...]}."
  [graph-key uid]
  (let [block (pull-block graph-key uid :deep true)]
    (if (:error block)
      block
      (loop [cur uid, ancestors []]
        (if-let [parent (get-parent graph-key cur)]
          (recur (:uid parent) (conj ancestors parent))
          {:block block :ancestors (reverse ancestors)})))))

;; ── ((uid)) reference resolution ─────────────────────────────────────────────

(def ^:private ref-pattern #"\(\(([^)]+)\)\)")

(defn- resolve-refs
  "Replace ((uid)) with ((uid)) ↳ content. One level deep, no recursion."
  [graph-key text]
  (if (and graph-key text (str/includes? text "(("))
    (str/replace text ref-pattern
                 (fn [[match uid]]
                   (let [result (roam/pull graph-key (proto/block-eid uid)
                                          "[:block/string]")]
                     (if-let [s (get-in result [:result :block/string])]
                       (str match " ↳ " s)
                       match))))
    (or text "")))

;; ── Output formatting ────────────────────────────────────────────────────────

(defn format-tree
  "Render block tree as indented text. When graph-key is provided, resolves ((uid)) refs."
  ([block indent] (format-tree block indent nil))
  ([block indent graph-key]
   (let [s (resolve-refs graph-key (or (:block/string block) (:node/title block) ""))
         children (or (:block/children block) [])]
     (str (apply str (repeat indent "  ")) "- " s "\n"
          (apply str (map #(format-tree % (inc indent) graph-key) children))))))

(defn- format-query-table
  "Format query results as aligned columns."
  [rows]
  (if (empty? rows)
    "(no results)"
    (let [;; Stringify all cells
          str-rows (mapv (fn [row] (mapv str row)) rows)
          ncols (apply max (map count str-rows))
          ;; Compute max width per column
          widths (for [c (range ncols)]
                   (apply max 0 (map #(count (get % c "")) str-rows)))
          ;; Pad and join
          fmt-row (fn [row]
                    (str/join "  "
                              (map-indexed (fn [i cell]
                                            (let [w (nth widths i 0)
                                                  s (or cell "")]
                                              (str s (apply str (repeat (- w (count s)) " ")))))
                                          row)))]
      (str/join "\n" (map fmt-row str-rows)))))

;; ── CLI wrappers ─────────────────────────────────────────────────────────────

(defn- ->key [s] (keyword (str/replace s ":" "")))

(defn read-cli [graph-key id]
  (let [g (->key graph-key)
        block (pull-block g id)]
    (if (and block (not (:error block)))
      (print (format-tree block 0 g))
      (let [page (pull-page g id)]
        (if (and page (not (:error page)))
          (print (format-tree page 0 g))
          (println "❌ Not found:" id))))))

(defn pull-cli [graph-key uid]
  (let [g (->key graph-key)
        result (pull-block g uid :deep true)]
    (if (and result (not (:error result)))
      (print (format-tree result 0 g))
      (println "❌ Not found:" uid))))

(defn daily-cli [graph-key]
  (let [g (->key graph-key)
        result (daily g)]
    (if (and result (not (:error result)))
      (print (format-tree result 0 g))
      (println "❌ Could not load daily page for" (roam/daily-title)))))

(defn context-cli [graph-key uid]
  (let [g (->key graph-key)
        result (context g uid)]
    (if (:error result)
      (println "❌" (:error result))
      (let [ancestors (:ancestors result)
            depth (count ancestors)]
        (doseq [[i {:keys [string]}] (map-indexed vector ancestors)]
          (println (str (apply str (repeat i "  ")) "↳ " (resolve-refs g string))))
        (print (format-tree (:block result) depth g))))))

(defn query-cli [graph-key query-str]
  (let [g (->key graph-key)
        result (roam/q g query-str [])]
    (if (:error result)
      (println "❌" (:error result))
      (println (format-query-table (:result result))))))
