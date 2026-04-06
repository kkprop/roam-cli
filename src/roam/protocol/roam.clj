(ns roam.protocol.roam
  "Roam Research API protocol — HTTP transport, auth, 429 retry, rate limiting.
   Swappable: replace this ns for Obsidian/Logseq."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def base-url "https://api.roamresearch.com/api/graph")

(defn load-config []
  (let [home (System/getProperty "user.home")
        primary (str home "/.roam-cli/config.edn")
        legacy  (str home "/roam-cli/config.edn")
        path (cond (.exists (java.io.File. primary)) primary
                   (.exists (java.io.File. legacy))  legacy)]
    (if path
      (edn/read-string (slurp path))
      (do (println "No config found. Run: roam-cli setup")
          (System/exit 1)))))

(defn get-graph-config [graph-key]
  (let [cfg (load-config)
        k (cond (keyword? graph-key) graph-key
                (string? graph-key) (-> graph-key (str/replace ":" "") keyword)
                :else graph-key)]
    (get-in cfg [:roam-graphs (or k (:default-graph cfg))])))

;; --- Rate tracker ---

(def ^:private call-log (atom []))

(defn- prune-old-calls [now-ms]
  (swap! call-log (fn [log] (filterv #(> % (- now-ms 60000)) log))))

(defn pace-delay
  "Return ms to sleep before next call. 200ms normally, 1000ms if >50 calls/min."
  []
  (let [now (System/currentTimeMillis)]
    (prune-old-calls now)
    (if (> (count @call-log) 50) 1000 200)))

(defn- track-call! []
  (swap! call-log conj (System/currentTimeMillis)))

;; --- Rate limit / retry ---

(defn request
  "Make authenticated Roam API request with 429 exponential backoff.
   method: :post/:get, endpoint: q/pull/pull-many/write, data: request body map."
  [method endpoint graph-key data]
  (let [{:keys [token graph]} (get-graph-config graph-key)
        url (str base-url "/" graph "/" endpoint)
        headers {"X-Authorization" (str "Bearer " token)
                 "Content-Type" "application/json"
                 "Accept" "application/json"}]
    (loop [attempt 1]
      (track-call!)
      (let [resp (case method
                   :post (http/post url {:headers headers
                                         :body (json/generate-string data)
                                         :throw false})
                   :get  (http/get url {:headers headers :throw false}))]
        (if (= 429 (:status resp))
          (if (<= attempt 5)
            (let [delay-ms (if (<= attempt 3) (* attempt 2000) (* attempt 15000))]
              (binding [*out* *err*]
                (println "⏳ 429 retry in" (/ delay-ms 1000) "s (attempt" (str attempt "/5)")))
              (Thread/sleep delay-ms)
              (recur (inc attempt)))
            resp)
          resp)))))

(defn parse-response
  "Parse JSON response body, fixing Roam's colon-prefixed keys."
  [body]
  (json/parse-string body (fn [k]
                            (if (str/starts-with? k ":")
                              (keyword (subs k 1))
                              (keyword k)))))

(defn q
  "Datalog query. Returns parsed result or {:error ...}."
  [graph-key query args]
  (let [resp (request :post "q" graph-key {:query query :args args})]
    (if (= 200 (:status resp))
      (parse-response (:body resp))
      {:error "query failed" :status (:status resp) :body (:body resp)})))

(defn pull
  "Pull entity by eid string and selector string."
  [graph-key eid selector]
  (let [resp (request :post "pull" graph-key {:eid eid :selector selector})]
    (if (= 200 (:status resp))
      (parse-response (:body resp))
      {:error "pull failed" :status (:status resp) :body (:body resp)})))

(defn pull-many
  "Pull multiple entities."
  [graph-key eids selector]
  (let [resp (request :post "pull-many" graph-key {:eids eids :selector selector})]
    (if (= 200 (:status resp))
      (parse-response (:body resp))
      {:error "pull-many failed" :status (:status resp) :body (:body resp)})))

(defn write!
  "Write action (create-block, update-block, create-page, move-block, etc.)."
  [graph-key data]
  (let [resp (request :post "write" graph-key data)]
    (if (= 200 (:status resp))
      {:success true}
      {:error "write failed" :status (:status resp) :body (:body resp)})))

;; --- Helpers ---

(defn daily-uid
  "Today's daily page UID in MM-dd-yyyy format."
  []
  (.format (java.text.SimpleDateFormat. "MM-dd-yyyy") (java.util.Date.)))

(defn daily-title
  "Today's daily page title in Roam format: 'April 6th, 2026'."
  []
  (let [now (java.util.Date.)
        month (.format (java.text.SimpleDateFormat. "MMMM" java.util.Locale/ENGLISH) now)
        year (.format (java.text.SimpleDateFormat. "yyyy") now)
        day (Integer/parseInt (.format (java.text.SimpleDateFormat. "d") now))
        suffix (cond (#{11 12 13} day) "th"
                     (= 1 (mod day 10)) "st"
                     (= 2 (mod day 10)) "nd"
                     (= 3 (mod day 10)) "rd"
                     :else "th")]
    (str month " " day suffix ", " year)))

(defn test-connection [graph-key]
  (let [{:keys [token graph]} (get-graph-config graph-key)]
    (println "Testing" graph-key "→" graph)
    (let [resp (request :post "q" graph-key
                        {:query "[:find ?uid :where [?b :block/uid ?uid]]" :args []})]
      (if (= 200 (:status resp))
        (println "✅ Connected")
        (do (println "❌ Failed — status" (:status resp))
            (println (:body resp)))))))
