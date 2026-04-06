(ns roam.core.hierarchy
  "Markdown → Roam block tree parser. Ported from qq.roam.hierarchy."
  (:require [clojure.string :as str]))

(defn parse-markdown
  "Parse markdown into header/content tree nodes."
  [content]
  (let [lines (str/split-lines content)]
    (loop [remaining lines, header nil, content-lines [], result []]
      (if (empty? remaining)
        (if header
          (conj result (assoc header :content (str/join "\n" content-lines)))
          result)
        (let [line (first remaining)]
          (if (str/starts-with? line "#")
            (let [level (count (take-while #(= % \#) line))
                  text (str/trim (subs line level))
                  updated (if header
                            (conj result (assoc header :content (str/join "\n" content-lines)))
                            result)]
              (recur (rest remaining) {:level level :text text} [] updated))
            (recur (rest remaining) header (conj content-lines line) result)))))))

(defn build-tree
  "Build nested tree from flat header list."
  [headers min-level]
  (loop [remaining headers, result []]
    (if (empty? remaining)
      [result remaining]
      (let [{:keys [level] :as h} (first remaining)]
        (cond
          (= level min-level)
          (let [[children rest-h] (build-tree (rest remaining) (inc level))]
            (recur rest-h (conj result (assoc h :children children))))
          (< level min-level)
          [result remaining]
          :else
          (recur (rest remaining) result))))))

(defn to-roam-blocks
  "Convert tree nodes to Roam block format."
  [tree]
  (mapv (fn [{:keys [text content children]}]
          (let [content-blocks (when-not (str/blank? content)
                                 (->> (str/split-lines content)
                                      (remove str/blank?)
                                      (mapv (fn [line]
                                              (let [clean (cond (str/starts-with? (str/trim line) "- ") (subs (str/trim line) 2)
                                                                (str/starts-with? (str/trim line) "* ") (subs (str/trim line) 2)
                                                                :else (str/trim line))]
                                                {:block/string clean})))))
                child-blocks (to-roam-blocks children)
                all-children (into (vec (or content-blocks [])) child-blocks)]
            (cond-> {:block/string text}
              (seq all-children) (assoc :block/children all-children))))
        tree))

(defn parse-and-convert [content]
  (let [headers (parse-markdown content)
        min-level (if (seq headers) (apply min (map :level headers)) 1)
        [tree _] (build-tree headers min-level)]
    (to-roam-blocks tree)))
