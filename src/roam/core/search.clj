(ns roam.core.search
  "API-agnostic search: fuzzy pages, fuzzy blocks, exact match, temporal."
  (:require [clojure.string :as str]
            [roam.protocol.roam :as roam]))

;; ── Fuzzy search ─────────────────────────────────────────────────────────────

(defn find-pages
  "Find pages whose title contains term."
  [graph-key term]
  (let [result (roam/q graph-key
                       "[:find ?title :in $ ?t :where [?p :node/title ?title] [(clojure.string/includes? ?title ?t)]]"
                       [term])]
    (if (:error result)
      result
      {:pages (mapv first (:result result))})))

(defn find-blocks
  "Find blocks whose content contains term. Returns [{:uid :string} ...]."
  [graph-key term]
  (let [result (roam/q graph-key
                       "[:find ?uid ?s :in $ ?t :where [?b :block/uid ?uid] [?b :block/string ?s] [(clojure.string/includes? ?s ?t)]]"
                       [term])]
    (if (:error result)
      result
      {:blocks (mapv (fn [[uid s]] {:uid uid :string s}) (:result result))})))

;; ── Exact match (used by write for UID capture) ─────────────────────────────

(defn find-block-by-content
  "Find most recent block UID matching exact content string."
  [graph-key content]
  (let [result (roam/q graph-key
                       "[:find ?uid ?time :in $ ?c :where [?b :block/uid ?uid] [?b :block/string ?c] [?b :create/time ?time]]"
                       [content])]
    (when-not (:error result)
      (->> (:result result)
           (sort-by second >)
           first first))))

;; ── Temporal search ──────────────────────────────────────────────────────────

(defn blocks-created-after
  "Find blocks created after timestamp (millis)."
  [graph-key timestamp]
  (let [result (roam/q graph-key
                       "[:find ?uid ?s :in $ ?ts :where [?b :block/uid ?uid] [?b :block/string ?s] [?b :create/time ?t] [(> ?t ?ts)]]"
                       [timestamp])]
    (if (:error result)
      result
      {:blocks (mapv (fn [[uid s]] {:uid uid :string s}) (:result result))})))

;; ── CLI wrappers ─────────────────────────────────────────────────────────────

(defn- ->key [s] (keyword (str/replace s ":" "")))

(defn search-cli [graph-key term]
  (let [result (find-blocks (->key graph-key) term)]
    (if (:error result)
      (println "❌" (:error result))
      (doseq [{:keys [uid string]} (:blocks result)]
        (println (str "🔗 " uid " — " (subs string 0 (min 100 (count string)))))))))

(defn pages-cli [graph-key term]
  (let [result (find-pages (->key graph-key) term)]
    (if (:error result)
      (println "❌" (:error result))
      (doseq [title (:pages result)]
        (println "📄" title)))))
