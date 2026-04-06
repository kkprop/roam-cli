(ns roam.main
  "Entry point for standalone binary. Dispatches subcommands to core."
  (:require [clojure.string :as str]
            [roam.protocol.roam :as protocol]
            [roam.core.read :as read]
            [roam.core.write :as write]
            [roam.core.search :as search]
            [roam.core.draft :as draft]
            [roam.setup :as setup]))

(def version
  (try (str/trim (slurp "VERSION")) (catch Exception _ "dev")))

(defn- parse-write-args
  "Parse write flags: --titled T, --tree, --to uid, then content."
  [[g & args]]
  (if (and g (seq args))
    (loop [remaining args, opts {}]
      (if (empty? remaining)
        (println "Usage: roam write <graph> [--titled T] [--tree] [--to uid] <content>")
        (let [a (first remaining)]
          (cond
            (= a "--titled") (recur (drop 2 remaining) (assoc opts :title (second remaining) :mode "titled"))
            (= a "--tree")   (recur (rest remaining) (assoc opts :mode "tree"))
            (= a "--to")     (recur (drop 2 remaining) (assoc opts :to (second remaining)))
            :else            (apply write/write-cli g (str/join " " remaining) (mapcat identity opts))))))
    (println "Usage: roam write <graph> [--titled T] [--tree] [--to uid] <content>")))

(def tasks
  {"read"            (fn [[g id]]       (if (and g id) (read/read-cli g id)
                                            (println "Usage: roam read <graph> <page-or-uid>")))
   "pull"            (fn [[g uid]]      (if (and g uid) (read/pull-cli g uid)
                                            (println "Usage: roam pull <graph> <uid>")))
   "pull-shallow"    (fn [[g uid]]      (if (and g uid) (read/pull-shallow-cli g uid)
                                            (println "Usage: roam pull-shallow <graph> <uid>")))
   "daily"           (fn [[g]]          (if g (read/daily-cli g)
                                            (println "Usage: roam daily <graph>")))
   "context"         (fn [[g uid]]      (if (and g uid) (read/context-cli g uid)
                                            (println "Usage: roam context <graph> <uid>")))
   "smart-context"   (fn [[g uid]]      (if (and g uid) (read/smart-context-cli g uid)
                                            (println "Usage: roam-cli smart-context <graph> <uid>")))
   "query"           (fn [[g & q]]      (if (and g (seq q)) (read/query-cli g (str/join " " q))
                                            (println "Usage: roam query <graph> '[:find ...]'")))
   "search"          (fn [[g & t]]      (if (and g (seq t)) (search/search-cli g (str/join " " t))
                                            (println "Usage: roam search <graph> <term>")))
   "pages"           (fn [[g & t]]      (if (and g (seq t)) (search/pages-cli g (str/join " " t))
                                            (println "Usage: roam pages <graph> <term>")))
   "write"           parse-write-args
   "update"          (fn [[g uid & c]]  (if (and g uid (seq c)) (write/update-cli g uid (str/join " " c))
                                            (println "Usage: roam update <graph> <uid> <content>")))
   "move"            (fn [[g uid p & [o]]] (if (and g uid p) (write/move-cli g uid p o)
                                            (println "Usage: roam move <graph> <block-uid> <new-parent-uid> [order]")))
   "write-before"    (fn [[g uid & c]]  (if (and g uid (seq c)) (write/write-before-cli g uid (str/join " " c))
                                            (println "Usage: roam write-before <graph> <sibling-uid> <content>")))
   "write-after"     (fn [[g uid & c]]  (if (and g uid (seq c)) (write/write-after-cli g uid (str/join " " c))
                                            (println "Usage: roam write-after <graph> <sibling-uid> <content>")))
   "test-connection" (fn [[g]]          (if g (protocol/test-connection g)
                                            (println "Usage: roam-cli test-connection <graph>")))
   "after"           (fn [[g id d]]     (if (and g id d) (read/after-cli g id d)
                                            (println "Usage: roam-cli after <graph> <uid-or-page> <date>")))
   "range"           (fn [[g id s e]]   (if (and g id s e) (read/range-cli g id s e)
                                            (println "Usage: roam-cli range <graph> <uid-or-page> <start> <end>")))
   "today"           (fn [[g]]          (cond
                                            (= g "--all") (read/today-all-cli)
                                            g              (read/today-cli g)
                                            :else          (println "Usage: roam-cli today <graph> or --all")))
   "setup"           setup/setup-cli
   "graphs"          setup/graphs-cli
   "draft"           (fn [[g & c]]      (if (and g (seq c)) (draft/draft-cli g (str/join " " c))
                                            (println "Usage: roam-cli draft <graph> <content>")))
   "drafts"          (fn [[g]]          (if g (draft/drafts-cli g)
                                            (println "Usage: roam-cli drafts <graph>")))
   "publish"         (fn [[g & [n]]]    (if g (draft/publish-cli g n)
                                            (println "Usage: roam-cli publish <graph> [n]")))})

(def docs
  {"read"            "Read page or block"
   "pull"            "Deep pull block with nested children"
   "pull-shallow"    "First-level children overview with UIDs"
   "daily"           "Show today's daily page"
   "context"         "Show block with ancestor chain"
   "smart-context"   "Smart context — find meaningful content root"
   "query"           "Raw Datalog query"
   "search"          "Search block content"
   "pages"           "Search page titles"
   "write"           "Write content (--titled, --tree, --to)"
   "update"          "Update existing block"
   "move"            "Move block to new parent"
   "write-before"    "Insert block before sibling"
   "write-after"     "Insert block after sibling"
   "test-connection" "Verify graph API connection"
   "after"           "Blocks created/edited after date"
   "range"           "Blocks created/edited in date range"
   "today"           "All blocks created/edited today"
   "setup"           "Interactive config wizard"
   "graphs"          "List configured graphs"
   "draft"           "Save draft locally"
   "drafts"          "List saved drafts"
   "publish"         "Publish draft(s) to Roam"})

(defn -main [& args]
  (let [cmd (first args)
        cmd-args (vec (rest args))]
    (cond
      (or (= cmd "--version") (= cmd "-v"))
      (println (str "roam-cli " version))

      (get tasks cmd)
      ((get tasks cmd) cmd-args)

      :else
      (do (println (str "roam-cli " version))
          (println "\nUsage: roam-cli <command> [args]\n")
          (doseq [t (sort (keys tasks))]
            (println (format "  %-17s %s" t (get docs t ""))))
          (when cmd (println (str "\nUnknown command: " cmd)))
          (System/exit (if cmd 1 0))))))
