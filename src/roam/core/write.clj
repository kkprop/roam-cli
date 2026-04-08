(ns roam.core.write
  "API-agnostic write operations. Handles flat/titled/tree modes,
   UID capture after create, daily page lifecycle, recursive tree writes."
  (:require [clojure.string :as str]
            [roam.protocol.protocol :as proto]
            [roam.protocol.roam :as roam]
            [roam.core.hierarchy :as hierarchy]
            [roam.core.search :as search])
  (:import [java.util.concurrent Semaphore]))

;; ── Primitives ───────────────────────────────────────────────────────────────

(def ^:private uid-chars "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")

(defn gen-uid
  "Generate a 9-char alphanumeric UID matching Roam's format."
  []
  (let [rng (java.security.SecureRandom.)]
    (apply str (repeatedly 9 #(nth uid-chars (.nextInt rng (count uid-chars)))))))

(defn assign-uids
  "Walk parsed hierarchy and assign a :block/uid to every block."
  [blocks]
  (mapv (fn [block]
          (cond-> (assoc block :block/uid (gen-uid))
            (:block/children block)
            (update :block/children assign-uids)))
        blocks))

(defn create-block
  "Create block under parent. Returns {:success true} or {:error ...}.
   Optional :uid to pre-assign the block UID, :order for position."
  [graph-key parent-uid content & {:keys [order uid] :or {order "last"}}]
  (roam/write! graph-key {:action "create-block"
                          :location {:parent-uid parent-uid :order order}
                          :block (cond-> {:string content :open true}
                                   uid (assoc :uid uid))}))

(defn update-block [graph-key uid content]
  (roam/write! graph-key {:action "update-block"
                          :block {:uid uid :string content}}))

(defn move-block [graph-key uid new-parent-uid & {:keys [order] :or {order "last"}}]
  (roam/write! graph-key {:action "move-block"
                          :block {:uid uid}
                          :location {:parent-uid new-parent-uid :order order}}))

(defn create-page [graph-key title uid]
  (roam/write! graph-key {:action "create-page"
                          :page {:uid uid :title title}}))

;; ── UID capture ──────────────────────────────────────────────────────────────
;; Roam's create-block doesn't return the new UID. We write, wait for indexing,
;; then query by exact content + most recent create/time. Same pattern as
;; qq/roam/client.clj's find-block-by-content.

(def ^:private index-delay-ms 500)

(defn create-block-with-uid
  "Create block and capture its UID via post-write query.
   Returns {:success true :uid \"...\"} or {:error ...}."
  [graph-key parent-uid content & {:keys [order] :or {order "last"}}]
  (let [result (create-block graph-key parent-uid content :order order)]
    (if (:error result)
      result
      (do (Thread/sleep (max index-delay-ms (roam/pace-delay)))
          (if-let [uid (search/find-block-by-content graph-key content)]
            {:success true :uid uid}
            {:success true :uid nil :warning "block created but UID not found"})))))

;; ── Daily page ───────────────────────────────────────────────────────────────

(defn ensure-daily-page
  "Ensure today's daily page exists. Idempotent — 400 on duplicate is fine."
  [graph-key]
  (create-page graph-key (roam/daily-title) (roam/daily-uid)))

(defn- resolve-parent
  "Return explicit parent-uid, or ensure daily page and return its uid."
  [graph-key parent-uid]
  (if parent-uid
    parent-uid
    (do (ensure-daily-page graph-key)
        (roam/daily-uid))))

;; ── Read content from file or string ─────────────────────────────────────────

(defn- read-content [arg]
  (if (and (string? arg)
           (or (str/ends-with? arg ".md") (str/ends-with? arg ".txt"))
           (.exists (java.io.File. arg)))
    (slurp arg)
    arg))

;; ── Write modes ──────────────────────────────────────────────────────────────

(defn write-flat
  "Single block under parent (or daily page)."
  [graph-key content & {:keys [parent-uid]}]
  (let [parent (resolve-parent graph-key parent-uid)]
    (create-block graph-key parent content)))

(defn write-titled
  "Title block + content as child. Needs UID capture for the title."
  [graph-key title content & {:keys [parent-uid]}]
  (let [parent (resolve-parent graph-key parent-uid)
        title-result (create-block-with-uid graph-key parent title)]
    (if (:error title-result)
      title-result
      (if-let [title-uid (:uid title-result)]
        (let [child-result (create-block graph-key title-uid content)]
          (assoc child-result :title-uid title-uid))
        {:error "title block created but UID capture failed — cannot nest content"}))))

;; ── UID generation ────────────────────────────────────────────────────────────

(def ^:private uid-chars "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")

(defn gen-uid
  "Generate a 9-char Roam-compatible UID."
  []
  (apply str (repeatedly 9 #(nth uid-chars (rand-int (count uid-chars))))))

(defn assign-uids
  "Walk block tree, assigning :block/uid to every node that lacks one."
  [blocks]
  (mapv (fn [b]
          (let [b (if (:block/uid b) b (assoc b :block/uid (gen-uid)))]
            (if-let [children (:block/children b)]
              (assoc b :block/children (assign-uids children))
              b)))
        blocks))

;; ── Concurrent write support ─────────────────────────────────────────────────

(def ^:private write-semaphore (Semaphore. 5))

(defn- create-block-with-pre-uid
  "Create block with pre-assigned UID. Acquires semaphore permit for rate limiting."
  [graph-key parent-uid uid content order]
  (.acquire write-semaphore)
  (try
    (roam/write! graph-key {:action "create-block"
                            :location {:parent-uid parent-uid :order order}
                            :block {:uid uid :string content :open true}})
    (finally
      (.release write-semaphore))))

(defn- write-tree-concurrent
  "Write block tree with cross-branch parallelism. Siblings within a level are
   written sequentially (preserving order), but independent subtrees fan out
   concurrently. E.g. A's children and B's children write in parallel."
  [graph-key parent-uid blocks]
  ;; Write siblings sequentially to preserve order, collect child futures
  (let [child-futs (doall
                     (for [[i block] (map-indexed vector blocks)]
                       (let [uid (:block/uid block)
                             text (:block/string block)]
                         (.acquire write-semaphore)
                         (try
                           (roam/write! graph-key {:action "create-block"
                                                   :location {:parent-uid parent-uid :order i}
                                                   :block {:uid uid :string text :open true}})
                           (finally
                             (.release write-semaphore)))
                         ;; Return future for children (or nil for leaves)
                         (when-let [children (:block/children block)]
                           (future (write-tree-concurrent graph-key uid children))))))]
    ;; Wait for all child subtrees to complete
    (doseq [f child-futs :when f] @f)))

(defn- write-tree-sequential
  "Write block tree sequentially (old path). Uses UID capture for parents."
  [graph-key parent-uid blocks]
  (doseq [block blocks]
    (let [text (:block/string block)
          grandchildren (:block/children block)]
      (if (seq grandchildren)
        (let [result (create-block-with-uid graph-key parent-uid text)]
          (when-let [uid (:uid result)]
            (write-tree-sequential graph-key uid grandchildren)))
        (do (create-block graph-key parent-uid text)
            (Thread/sleep (roam/pace-delay)))))))

(defn- count-blocks [blocks]
  (reduce (fn [n b] (+ n 1 (count-blocks (or (:block/children b) [])))) 0 blocks))

(defn write-tree
  "Parse markdown → block hierarchy, write recursively.
   Default: concurrent with pre-assigned UIDs. Pass :sequential true for old path."
  [graph-key content & {:keys [parent-uid sequential]}]
  (let [parent (resolve-parent graph-key parent-uid)
        blocks (hierarchy/parse-and-convert content)]
    (if (empty? blocks)
      (write-flat graph-key content :parent-uid parent)
      (let [total (count-blocks blocks)]
        (when (> total 20)
          (println (str "⚠️  Writing " total " blocks"
                        (if sequential
                          (str ", estimated time ~" (int (* total 0.7)) "s")
                          " (concurrent, ~5 at a time)"))))
        (if sequential
          (write-tree-sequential graph-key parent blocks)
          (let [blocks (assign-uids blocks)]
            (write-tree-concurrent graph-key parent blocks)))
        {:success true :blocks-written total}))))

;; ── Positional insert ─────────────────────────────────────────────────────────

(defn- find-sibling-position
  "Query parent UID and block order for a sibling. Returns [parent-uid order] or nil."
  [graph-key sibling-uid]
  (let [q "[:find ?puid ?order :in $ ?cuid :where [?c :block/uid ?cuid] [?p :block/children ?c] [?p :block/uid ?puid] [?c :block/order ?order]]"
        result (roam/q graph-key q [sibling-uid])]
    (first (:result result))))

(defn write-before
  "Insert block before sibling (same order index = before)."
  [graph-key sibling-uid content]
  (if-let [[parent-uid order] (find-sibling-position graph-key sibling-uid)]
    (create-block graph-key parent-uid content :order order)
    {:error "Could not find parent of sibling block"}))

(defn write-after
  "Insert block after sibling (order + 1)."
  [graph-key sibling-uid content]
  (if-let [[parent-uid order] (find-sibling-position graph-key sibling-uid)]
    (create-block graph-key parent-uid content :order (inc order))
    {:error "Could not find parent of sibling block"}))

;; ── Smart entry point ────────────────────────────────────────────────────────

(defn write
  "Auto-detect write mode from content shape.
   opts — :parent-uid, :mode (:flat/:titled/:tree), :title, :sequential"
  [graph-key content-arg & {:keys [parent-uid mode title sequential]}]
  (let [content (read-content content-arg)
        effective-mode (or mode
                          (cond
                            title :titled
                            (re-find #"(?m)^#{1,6} " content) :tree
                            :else :flat))]
    (case effective-mode
      :flat   (write-flat graph-key content :parent-uid parent-uid)
      :titled (write-titled graph-key (or title "Untitled") content :parent-uid parent-uid)
      :tree   (write-tree graph-key content :parent-uid parent-uid :sequential sequential))))

;; ── CLI wrappers ─────────────────────────────────────────────────────────────

(defn- ->key [s] (keyword (str/replace s ":" "")))
(defn- ->uid [s] (proto/normalize-uid s))

(defn write-cli
  "roam write <graph> [--to uid] [--flat|--titled T|--tree] [--sequential] <content-or-file>"
  [graph-key content & {:keys [to mode title sequential]}]
  (let [g (->key graph-key)
        opts (cond-> {}
               to         (assoc :parent-uid (->uid to))
               mode       (assoc :mode (keyword mode))
               title      (assoc :title title)
               sequential (assoc :sequential true))
        result (apply write g content (mapcat identity opts))]
    (if (:error result)
      (println "❌" (:error result))
      (println "✅ Written to" (name g)))))

(defn update-cli [graph-key uid content]
  (let [result (update-block (->key graph-key) (->uid uid) content)]
    (if (:error result)
      (println "❌" (:error result))
      (println "✅ Block" (->uid uid) "updated"))))

(defn move-cli [graph-key uid new-parent-uid & [order]]
  (let [result (move-block (->key graph-key) (->uid uid) (->uid new-parent-uid)
                           :order (or order "last"))]
    (if (:error result)
      (println "❌" (:error result))
      (println "✅ Block" (->uid uid) "moved to" (->uid new-parent-uid)))))

(defn write-before-cli [graph-key sibling-uid content]
  (let [result (write-before (->key graph-key) (->uid sibling-uid) content)]
    (if (:error result)
      (println "❌" (:error result))
      (println "✅ Written before" (->uid sibling-uid)))))

(defn write-after-cli [graph-key sibling-uid content]
  (let [result (write-after (->key graph-key) (->uid sibling-uid) content)]
    (if (:error result)
      (println "❌" (:error result))
      (println "✅ Written after" (->uid sibling-uid)))))
