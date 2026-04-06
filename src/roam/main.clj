(ns roam.main
  "Entry point for standalone binary. Dispatches subcommands to core."
  (:require [clojure.string :as str]
            [roam.protocol.roam :as protocol]
            [roam.core.read :as read]
            [roam.core.write :as write]
            [roam.core.search :as search]
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
   "daily"           (fn [[g]]          (if g (read/daily-cli g)
                                            (println "Usage: roam daily <graph>")))
   "context"         (fn [[g uid]]      (if (and g uid) (read/context-cli g uid)
                                            (println "Usage: roam context <graph> <uid>")))
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
   "test-connection" (fn [[g]]          (if g (protocol/test-connection g)
                                            (println "Usage: roam test-connection <graph>")))
   "setup"           setup/setup-cli
   "graphs"          setup/graphs-cli})

(def docs
  {"read"            "Read page or block"
   "pull"            "Deep pull block with nested children"
   "daily"           "Show today's daily page"
   "context"         "Show block with ancestor chain"
   "query"           "Raw Datalog query"
   "search"          "Search block content"
   "pages"           "Search page titles"
   "write"           "Write content (--titled, --tree, --to)"
   "update"          "Update existing block"
   "move"            "Move block to new parent"
   "test-connection" "Verify graph API connection"
   "setup"           "Interactive config wizard"
   "graphs"          "List configured graphs"})

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
