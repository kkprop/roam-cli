(ns roam.core.write
  "API-agnostic write operations. Handles flat/titled/tree modes,
   UID capture after create, daily page lifecycle, recursive tree writes."
  (:require [clojure.string :as str]
            [roam.protocol.roam :as roam]
            [roam.core.hierarchy :as hierarchy]
            [roam.core.search :as search]))

;; ── Primitives ───────────────────────────────────────────────────────────────

(defn create-block
  "Create block under parent. Returns {:success true} or {:error ...}."
  [graph-key parent-uid content & {:keys [order] :or {order "last"}}]
  (roam/write! graph-key {:action "create-block"
                          :location {:parent-uid parent-uid :order order}
                          :block {:string content :open true}}))

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
      (do (Thread/sleep index-delay-ms)
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

(defn write-tree
  "Parse markdown → block hierarchy, write recursively with inter-block delays.
   Each parent needs UID capture before its children can be written."
  [graph-key content & {:keys [parent-uid]}]
  (let [parent (resolve-parent graph-key parent-uid)
        blocks (hierarchy/parse-and-convert content)]
    (if (empty? blocks)
      (write-flat graph-key content :parent-uid parent)
      (letfn [(write-children [parent-uid children]
                (doseq [block children]
                  (let [text (:block/string block)
                        grandchildren (:block/children block)]
                    (if (seq grandchildren)
                      ;; Need UID capture to nest grandchildren
                      (let [result (create-block-with-uid graph-key parent-uid text)]
                        (when-let [uid (:uid result)]
                          (write-children uid grandchildren)))
                      ;; Leaf node — no UID capture needed
                      (create-block graph-key parent-uid text)))))]
        (write-children parent blocks)
        {:success true :blocks-written (count blocks)}))))

;; ── Smart entry point ────────────────────────────────────────────────────────

(defn write
  "Auto-detect write mode from content shape.
   opts — :parent-uid, :mode (:flat/:titled/:tree), :title"
  [graph-key content-arg & {:keys [parent-uid mode title]}]
  (let [content (read-content content-arg)
        effective-mode (or mode
                          (cond
                            title :titled
                            (re-find #"(?m)^#{1,6} " content) :tree
                            :else :flat))]
    (case effective-mode
      :flat   (write-flat graph-key content :parent-uid parent-uid)
      :titled (write-titled graph-key (or title "Untitled") content :parent-uid parent-uid)
      :tree   (write-tree graph-key content :parent-uid parent-uid))))

;; ── CLI wrappers ─────────────────────────────────────────────────────────────

(defn- ->key [s] (keyword (str/replace s ":" "")))

(defn write-cli
  "roam write <graph> [--to uid] [--flat|--titled T|--tree] <content-or-file>"
  [graph-key content & {:keys [to mode title]}]
  (let [g (->key graph-key)
        opts (cond-> {}
               to    (assoc :parent-uid to)
               mode  (assoc :mode (keyword mode))
               title (assoc :title title))
        result (apply write g content (mapcat identity opts))]
    (if (:error result)
      (println "❌" (:error result))
      (println "✅ Written to" (name g)))))

(defn update-cli [graph-key uid content]
  (let [result (update-block (->key graph-key) uid content)]
    (if (:error result)
      (println "❌" (:error result))
      (println "✅ Block" uid "updated"))))
