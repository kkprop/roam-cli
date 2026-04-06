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

;; ── Output formatting ────────────────────────────────────────────────────────

(defn format-tree [block indent]
  (let [s (or (:block/string block) (:node/title block) "")
        children (or (:block/children block) [])]
    (str (apply str (repeat indent "  ")) "- " s "\n"
         (apply str (map #(format-tree % (inc indent)) children)))))

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
      (print (format-tree block 0))
      (let [page (pull-page g id)]
        (if (and page (not (:error page)))
          (print (format-tree page 0))
          (println "❌ Not found:" id))))))

(defn pull-cli [graph-key uid]
  (let [g (->key graph-key)
        result (pull-block g uid :deep true)]
    (if (and result (not (:error result)))
      (print (format-tree result 0))
      (println "❌ Not found:" uid))))

(defn daily-cli [graph-key]
  (let [g (->key graph-key)
        result (daily g)]
    (if (and result (not (:error result)))
      (print (format-tree result 0))
      (println "❌ Could not load daily page for" (roam/daily-title)))))

(defn context-cli [graph-key uid]
  (let [g (->key graph-key)
        result (context g uid)]
    (if (:error result)
      (println "❌" (:error result))
      (let [ancestors (:ancestors result)
            depth (count ancestors)]
        ;; Print ancestor chain with increasing indent
        (doseq [[i {:keys [string]}] (map-indexed vector ancestors)]
          (println (str (apply str (repeat i "  ")) "↳ " string)))
        ;; Print the target block tree at the right depth
        (print (format-tree (:block result) depth))))))

(defn query-cli [graph-key query-str]
  (let [g (->key graph-key)
        result (roam/q g query-str [])]
    (if (:error result)
      (println "❌" (:error result))
      (println (format-query-table (:result result))))))
