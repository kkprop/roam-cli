(ns roam.setup
  "Interactive config wizard. Detects, creates, and manages config.edn."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def config-paths
  [(str (System/getProperty "user.home") "/.roam-cli/config.edn")
   (str (System/getProperty "user.home") "/roam-cli/config.edn")])

(defn detect-config
  "Find first existing config path, or nil."
  []
  (first (filter #(.exists (io/file %)) config-paths)))

(defn default-config-path []
  (first config-paths))

(defn load-config
  "Load config from detected path. Returns nil if not found."
  []
  (when-let [path (detect-config)]
    (edn/read-string (slurp path))))

(defn- prompt
  "Read a line from stdin with prompt text. Returns nil on empty if required is false."
  [text]
  (print text)
  (flush)
  (let [line (read-line)]
    (when-not (str/blank? line) (str/trim line))))

(defn- test-token
  "Validate graph+token by counting pages. Returns page count or nil."
  [graph token]
  (try
    (let [url (str "https://api.roamresearch.com/api/graph/" graph "/q")
          resp (http/post url {:headers {"X-Authorization" (str "Bearer " token)
                                         "Content-Type" "application/json"}
                               :body (json/generate-string
                                       {:query "[:find (count ?p) :where [?p :node/title]]" :args []})
                               :throw false})]
      (when (= 200 (:status resp))
        (let [parsed (json/parse-string (:body resp) true)]
          (or (ffirst (:result parsed)) 0))))
    (catch Exception _ nil)))

(defn- save-config [path cfg]
  (io/make-parents path)
  (spit path (pr-str cfg))
  (println (str "✅ Saved to " path)))

(defn setup-wizard
  "Interactive setup. Prompts for graph name, alias, token. Validates, writes config."
  []
  (println "No config found. Let's set up your Roam connection.\n")
  (if-let [graph (prompt "Graph name (from your Roam URL): ")]
    (let [default-alias (str/replace graph #"[^a-zA-Z0-9]" "")
          alias (or (prompt (str "Short alias [" default-alias "]: ")) default-alias)]
      (if-let [token (prompt "API token (from Roam Settings > Graph > API Tokens): ")]
        (do
          (print "Testing connection... ")
          (flush)
          (if-let [pages (test-token graph token)]
            (let [key (keyword alias)
                  path (default-config-path)
                  cfg {:roam-graphs {key {:token token :graph graph}}
                       :default-graph key}]
              (save-config path cfg)
              (println (str "✅ Connected to " graph " (" pages " pages)"))
              (println (str "\nTry: roam-cli read " alias " \"some page\"")))
            (println "❌ Connection failed. Check your graph name and token.")))
        (println "Aborted — no token provided.")))
    (println "Aborted — no graph name provided.")))

(defn add-graph
  "Add a new graph to existing config."
  [& [key-hint]]
  (if-let [cfg (load-config)]
    (do
      (println (str "Adding a new graph" (when key-hint (str " ('" key-hint "' not found)")) ".\n"))
      (if-let [graph (prompt (str "Graph name" (when key-hint (str " [" key-hint "]")) ": "))]
        (let [alias (or key-hint (prompt (str "Short alias [" graph "]: ")) graph)]
          (if-let [token (prompt "API token: ")]
            (do
              (print "Testing connection... ")
              (flush)
              (if-let [pages (test-token graph token)]
                (let [key (keyword alias)
                      path (detect-config)
                      updated (assoc-in cfg [:roam-graphs key] {:token token :graph graph})]
                  (save-config path updated)
                  (println (str "✅ Connected to " graph " (" pages " pages)")))
                (println "❌ Connection failed.")))
            (println "Aborted.")))
        (println "Aborted.")))
    (setup-wizard)))

(defn list-graphs
  "Print configured graphs."
  []
  (if-let [cfg (load-config)]
    (let [graphs (:roam-graphs cfg)
          default (:default-graph cfg)]
      (println (str "Config: " (detect-config) "\n"))
      (doseq [[k {:keys [graph]}] (sort-by key graphs)]
        (println (str "  " (name k)
                      (when (= k default) " (default)")
                      " → " graph))))
    (println "No config found. Run: roam setup")))

;; ── CLI wrappers ─────────────────────────────────────────────────────────────

(defn setup-cli [_args] (setup-wizard))

(defn graphs-cli [_args] (list-graphs))

(defn add-graph-cli [[key-hint]] (add-graph key-hint))

(defn ensure-config
  "Check config exists. If not, print friendly message and exit. Returns true if config available."
  []
  (if (detect-config)
    true
    (do (println "No config found. Run: roam-cli setup")
        (System/exit 1))))

(defn default-graph
  "Return the default graph key name as string, or nil.
   If only one graph configured, return that. Otherwise return :default-graph."
  []
  (when-let [cfg (load-config)]
    (let [graphs (:roam-graphs cfg)]
      (name (if (= 1 (count graphs))
              (ffirst graphs)
              (or (:default-graph cfg) (ffirst graphs)))))))
