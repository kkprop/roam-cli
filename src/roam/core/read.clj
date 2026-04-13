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
  (let [uid (proto/normalize-uid uid)
        block (pull-block graph-key uid :deep true)]
    (if (:error block)
      block
      (loop [cur uid, ancestors []]
        (if-let [parent (get-parent graph-key cur)]
          (recur (:uid parent) (conj ancestors parent))
          {:block block :ancestors (reverse ancestors)})))))

;; ── Smart context ────────────────────────────────────────────────────────────

(defn- stop-boundary?
  "True if this parent is a daily note, timestamp, or page-level container."
  [content]
  (when content
    (let [s (str/trim content)]
      (or (re-find #"^\d{2}:\d{2}$" s)           ; bare timestamp
          (re-find #"^\[\[.*\]\]$" s)             ; [[page ref]] wrapper
          (re-find #"(?i)#personal|#daily" s))))) ; personal/daily tags

(defn- content-root?
  "True if this block looks like a structured content root (has ((uid)) refs)."
  [content]
  (when content
    (and (str/includes? content "((")
         (str/includes? content "))"))))

(defn find-root-ancestor
  "Walk up from block, find the meaningful content boundary.
   Stops before daily notes / timestamps. Prefers blocks with ((uid)) refs."
  [graph-key uid]
  (loop [cur uid, best nil]
    (if-let [{:keys [uid string]} (get-parent graph-key cur)]
      (cond
        (stop-boundary? string) (or best cur)
        (content-root? string)  (recur uid uid)
        :else                   (recur uid best))
      (or best cur))))

(defn smart-context
  "Find meaningful root ancestor, deep pull from there."
  [graph-key uid]
  (let [uid (proto/normalize-uid uid)
        root-uid (find-root-ancestor graph-key uid)
        root (pull-block graph-key root-uid :deep true)]
    (if (:error root)
      root
      {:root-uid root-uid :target-uid uid :tree root})))

;; ── Shallow pull ──────────────────────────────────────────────────────────────

(defn pull-shallow
  "Pull block with first-level children only (UIDs, strings, child counts)."
  [graph-key uid]
  (let [q "[:find (pull ?b [:block/uid :block/string
                            {:block/children [:block/uid :block/string :block/order
                                              {:block/children [:block/uid]}]}])
            :in $ ?uid :where [?b :block/uid ?uid]]"
        result (roam/q graph-key q [(proto/normalize-uid uid)])]
    (if (:error result)
      result
      (if-let [block (ffirst (:result result))]
        block
        {:error "Block not found"}))))

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

(defn- parse-flags
  "Extract display flags from args. Defaults: uid=true, count=true.
   --clean suppresses both. --uid/--count toggle individually."
  [args]
  (loop [remaining args, opts {:uid true :count true}]
    (if (empty? remaining)
      [opts remaining]
      (case (first remaining)
        "--clean" (recur (rest remaining) (assoc opts :uid false :count false))
        "--uid"   (recur (rest remaining) (update opts :uid not))
        "--count" (recur (rest remaining) (update opts :count not))
        "--refs"  (recur (rest remaining) (assoc opts :refs true))
        [opts (vec remaining)]))))

(defn format-tree
  "Render block tree as indented text.
   opts: :uid (show UIDs), :count (show child count), :graph-key (resolve refs)."
  ([block indent] (format-tree block indent {}))
  ([block indent opts]
   (let [gk (:graph-key opts)
         raw (or (:block/string block) (:node/title block) "")
         s (resolve-refs gk raw)
         children (sort-by #(or (:block/order %) 0) (or (:block/children block) []))
         uid-str (when (:uid opts) (str " [" (:block/uid block) "]"))
         cnt-str (when (and (:count opts) (seq children)) (str " [+" (count children) "]"))
         prefix (apply str (repeat indent "  "))]
     (str prefix "- " s (or uid-str "") (or cnt-str "") "\n"
          (apply str (map #(format-tree % (inc indent) opts) children))))))

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

;; ── Date parsing ──────────────────────────────────────────────────────────────

(defn parse-date
  "Parse flexible date input to epoch millis.
   Accepts: yyyy-MM-dd, yyyy-MM-dd HH:mm, MM-dd (current year), epoch ms/s."
  [input]
  (cond
    (number? input) input
    (string? input)
    (cond
      (re-matches #"\d{13}" input) (Long/parseLong input)
      (re-matches #"\d{10}" input) (* (Long/parseLong input) 1000)
      (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}" input)
      (.getTime (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm") input))
      (re-matches #"\d{4}-\d{2}-\d{2}" input)
      (.getTime (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd") input))
      (re-matches #"\d{2}-\d{2}" input)
      (let [y (+ 1900 (.getYear (java.util.Date.)))]
        (.getTime (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd") (str y "-" input))))
      :else (throw (ex-info (str "Cannot parse date: " input) {:input input})))
    :else (throw (ex-info (str "Invalid date: " input) {:input input}))))

(defn- start-of-today []
  (.getTime (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd")
                    (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.)))))

;; ── Time-filtered reads ──────────────────────────────────────────────────────

(defn daily-blocks
  "Find all blocks created or edited in date range, with parent UIDs for tree building."
  [graph-key date-start date-end]
  (let [result (roam/q graph-key
                       "[:find ?uid ?s ?ct ?et ?puid :in $ ?ds ?de :where
                         [?b :block/uid ?uid] [?b :block/string ?s]
                         [?b :create/time ?ct] [?b :edit/time ?et]
                         [?p :block/children ?b] [?p :block/uid ?puid]
                         (or (and [(>= ?ct ?ds)] [(<= ?ct ?de)])
                             (and [(>= ?et ?ds)] [(<= ?et ?de)]))]"
                       [date-start date-end])]
    (if (:error result)
      result
      {:blocks (->> (:result result)
                    (mapv (fn [[uid s ct et puid]]
                            {:uid uid :string (or s "") :time (max ct et) :parent-uid puid}))
                    (sort-by :time))})))

;; ── CLI wrappers ─────────────────────────────────────────────────────────────

(defn- ->key [s] (keyword (str/replace s ":" "")))

(defn read-cli [graph-key & args]
  (let [[opts [id]] (parse-flags args)
        g (->key graph-key)
        opts (assoc opts :graph-key g)]
    (if-not id
      (println "Usage: bb read <graph> [--uid] [--count] <page-or-uid>")
      (let [block (pull-block g id :deep true)]
        (if (and block (not (:error block)))
          (print (format-tree block 0 opts))
          (let [page (pull-page g id)]
            (if (and page (not (:error page)))
              (print (format-tree page 0 opts))
              (println "❌ Not found:" id))))))))

(defn pull-cli [graph-key & args]
  (let [[opts [uid]] (parse-flags args)
        g (->key graph-key)
        opts (assoc opts :graph-key g)]
    (if-not uid
      (println "Usage: bb pull <graph> [--uid] [--count] <uid>")
      (let [result (pull-block g uid :deep true)]
        (if (and result (not (:error result)))
          (print (format-tree result 0 opts))
          (println "❌ Not found:" uid))))))

(defn pull-shallow-cli [graph-key uid]
  (let [g (->key graph-key)
        result (pull-shallow g uid)]
    (if (:error result)
      (println "❌" (:error result))
      (let [s (or (:block/string result) "")
            children (sort-by #(or (:block/order %) 0) (or (:block/children result) []))]
        (println (str "• " s "  [" (:block/uid result) "]"))
        (println (str "  " (count children) " children:"))
        (doseq [c children]
          (let [cs (or (:block/string c) "[empty]")
                gc (count (or (:block/children c) []))]
            (println (str "  • " (subs cs 0 (min 80 (count cs)))
                          "  [" (:block/uid c) (when (pos? gc) (str " +" gc)) "]"))))))))

(defn daily-cli [graph-key & args]
  (let [[opts _] (parse-flags args)
        g (->key graph-key)
        opts (assoc opts :graph-key g)
        result (daily g)]
    (if (and result (not (:error result)))
      (print (format-tree result 0 opts))
      (println "❌ Could not load daily page for" (roam/daily-title)))))

(defn context-cli [graph-key & args]
  (let [[opts [uid]] (parse-flags args)
        g (->key graph-key)
        opts (assoc opts :graph-key g)]
    (if-not uid
      (println "Usage: bb context <graph> [--clean] <uid>")
      (let [result (context g uid)]
        (if (:error result)
          (println "❌" (:error result))
          (let [ancestors (:ancestors result)
                depth (count ancestors)]
            (doseq [[i {:keys [string]}] (map-indexed vector ancestors)]
              (println (str (apply str (repeat i "  ")) "↳ " (resolve-refs g string))))
            (print (format-tree (:block result) depth opts))))))))

(defn smart-context-cli [graph-key & args]
  (let [[opts [uid]] (parse-flags args)
        g (->key graph-key)
        opts (assoc opts :graph-key g)]
    (if-not uid
      (println "Usage: bb smart-context <graph> [--clean] <uid>")
      (let [result (smart-context g uid)]
        (if (:error result)
          (println "❌" (:error result))
          (do (println (str "🎯 Root: " (:root-uid result) " → target: " (:target-uid result)))
              (print (format-tree (:tree result) 0 opts))))))))

(defn query-cli [graph-key query-str]
  (let [g (->key graph-key)
        result (roam/q g query-str [])]
    (if (:error result)
      (println "❌" (:error result))
      (println (format-query-table (:result result))))))

(defn- fmt-time [ms]
  (.format (java.text.SimpleDateFormat. "MM-dd HH:mm") (java.util.Date. (long ms))))

(defn after-cli [graph-key id date]
  (let [g (->key graph-key)
        ts (parse-date date)
        result (daily-blocks g ts (System/currentTimeMillis))]
    (if (:error result)
      (println "❌" (:error result))
      (doseq [{:keys [uid string time]} (:blocks result)]
        (println (str (fmt-time time) "  " uid "  " (subs string 0 (min 100 (count string)))))))))

(defn range-cli [graph-key id start end]
  (let [g (->key graph-key)
        result (daily-blocks g (parse-date start) (parse-date end))]
    (if (:error result)
      (println "❌" (:error result))
      (doseq [{:keys [uid string time]} (:blocks result)]
        (println (str (fmt-time time) "  " uid "  " (subs string 0 (min 100 (count string)))))))))

(defn- truncate [s n] (if (> (count s) n) (str (subs s 0 n) "...") s))

(defn- fmt-hm [ms]
  (.format (java.text.SimpleDateFormat. "HH:mm") (java.util.Date. (long ms))))

(defn- blocks->tree
  "Build tree from flat blocks with :parent-uid. Returns roots (blocks whose parent is not in the set)."
  [blocks]
  (let [uid-set (set (map :uid blocks))
        by-parent (group-by :parent-uid blocks)
        roots (filter #(not (uid-set (:parent-uid %))) blocks)]
    (letfn [(attach [block]
              (let [children (sort-by :time (get by-parent (:uid block) []))]
                (if (seq children)
                  (assoc block :children (mapv attach children))
                  block)))]
      (->> roots (sort-by :time) (mapv attach)))))

(defn- print-block-tree
  ([roots indent] (print-block-tree roots indent {:uid true :count true}))
  ([roots indent opts]
   (doseq [b roots]
     (let [prefix (apply str (repeat indent "  "))
           time-str (when (zero? indent) (str (fmt-hm (:time b)) "  "))
           text (truncate (:string b) 80)
           uid-str (when (:uid opts) (str " [" (:uid b) "]"))
           cnt (count (or (:children b) []))
           cnt-str (when (and (:count opts) (pos? cnt)) (str " [+" cnt "]"))]
       (println (str prefix (or time-str "") text (or uid-str "") (or cnt-str ""))))
     (when-let [children (:children b)]
       (print-block-tree children (inc indent) opts)))))

(defn today-cli [graph-key & args]
  (let [[opts _] (parse-flags args)
        g (->key graph-key)
        result (daily-blocks g (start-of-today) (System/currentTimeMillis))]
    (if (:error result)
      (println "❌" (:error result))
      (let [blocks (:blocks result)
            roots (blocks->tree blocks)]
        (println (str (count blocks) " blocks today:"))
        (print-block-tree roots 0 opts)))))

(defn today-all-cli []
  (let [cfg (roam/load-config)]
    (when-not cfg
      (println "No config found. Run: roam-cli setup")
      (System/exit 1))
    (let [graphs (keys (:roam-graphs cfg))
          now (System/currentTimeMillis)
          sot (start-of-today)]
      (doseq [g graphs]
        (let [r (daily-blocks g sot now)]
          (when (and (not (:error r)) (seq (:blocks r)))
            (let [roots (blocks->tree (:blocks r))]
              (println (str "=== " (name g) " (" (count (:blocks r)) " blocks) ==="))
              (print-block-tree roots 0)
              (println))))))))
