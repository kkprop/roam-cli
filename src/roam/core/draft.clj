(ns roam.core.draft
  "File-based draft queue. Stages content locally before publishing to Roam."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [roam.core.write :as write]))

(defn- draft-path [graph-key]
  (str (System/getProperty "user.home") "/roam-cli/tmp/drafts/" (name graph-key) ".edn"))

(defn- load-drafts [graph-key]
  (let [f (io/file (draft-path graph-key))]
    (if (.exists f) (edn/read-string (slurp f)) [])))

(defn- save-drafts [graph-key drafts]
  (let [f (draft-path graph-key)]
    (io/make-parents f)
    (spit f (pr-str (vec drafts)))))

(defn add-draft [graph-key content]
  (let [drafts (load-drafts graph-key)
        draft {:text content :time (System/currentTimeMillis)}]
    (save-drafts graph-key (conj drafts draft))
    {:success true :count (inc (count drafts))}))

(defn list-drafts [graph-key]
  (load-drafts graph-key))

(defn publish [graph-key n]
  "Publish draft n (1-based) or all (n=nil). Writes to daily page, removes from queue."
  (let [drafts (load-drafts graph-key)]
    (if (empty? drafts)
      {:error "No drafts"}
      (let [to-publish (if n [(nth drafts (dec n))] drafts)
            remaining (if n (vec (concat (subvec drafts 0 (dec n)) (subvec drafts n))) [])]
        (doseq [{:keys [text]} to-publish]
          (write/write-flat graph-key text))
        (save-drafts graph-key remaining)
        {:success true :published (count to-publish) :remaining (count remaining)}))))

;; ── CLI wrappers ─────────────────────────────────────────────────────────────

(defn- ->key [s] (keyword (str/replace s ":" "")))

(defn- fmt-time [ms]
  (.format (java.text.SimpleDateFormat. "HH:mm") (java.util.Date. (long ms))))

(defn draft-cli [graph-key content]
  (let [result (add-draft (->key graph-key) content)]
    (if (:error result)
      (println "❌" (:error result))
      (println (str "📝 Draft saved (" (:count result) " total)")))))

(defn drafts-cli [graph-key]
  (let [drafts (list-drafts (->key graph-key))]
    (if (empty? drafts)
      (println "No drafts for" graph-key)
      (doseq [[i {:keys [text time]}] (map-indexed vector drafts)]
        (println (str "  " (inc i) ". [" (fmt-time time) "] "
                      (subs text 0 (min 80 (count text)))))))))

(defn publish-cli [graph-key & [n]]
  (let [idx (when n (Integer/parseInt n))
        result (publish (->key graph-key) idx)]
    (if (:error result)
      (println "❌" (:error result))
      (println (str "✅ Published " (:published result) " draft(s) to " graph-key
                    (when (pos? (:remaining result)) (str ", " (:remaining result) " remaining")))))))
