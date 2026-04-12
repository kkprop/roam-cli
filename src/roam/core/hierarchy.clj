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
          (if (and (str/starts-with? line "#")
                   (let [hashes (count (take-while #(= % \#) line))]
                     (and (<= hashes 6) (> (count line) hashes) (= \space (nth line hashes)))))
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

(defn- join-table-lines
  "Detect consecutive |-prefixed lines and join them into a single block string."
  [lines]
  (loop [remaining lines, table-acc [], result []]
    (if (empty? remaining)
      (if (seq table-acc)
        (conj result (str/join "\n" table-acc))
        result)
      (let [line (first remaining)]
        (if (str/starts-with? (str/trim line) "|")
          (recur (rest remaining) (conj table-acc line) result)
          (if (seq table-acc)
            (recur (rest remaining) [] (conj (conj result (str/join "\n" table-acc)) line))
            (recur (rest remaining) [] (conj result line))))))))

(defn- parse-list-items
  "Parse indented list items into nested block tree.
   Handles: - item, * item, plain text with 2-space indent levels,
   bold headers (**text** or text:) as implicit parents, and markdown tables."
  [lines]
  (let [lines (join-table-lines lines)
        parse-line (fn [line]
                     (let [trimmed (str/trim line)
                           indent (count (take-while #(= % \space) line))
                           level (quot indent 2)
                           text (cond (str/starts-with? trimmed "- ") (subs trimmed 2)
                                      (str/starts-with? trimmed "* ") (subs trimmed 2)
                                      :else trimmed)
                           parent? (and (not (str/starts-with? trimmed "- "))
                                        (not (str/starts-with? trimmed "* "))
                                        (or (str/includes? text "**")
                                            (str/ends-with? (str/trim text) ":")))]
                       {:level level :text text :parent parent?}))
        items (mapv parse-line (remove str/blank? lines))
        ;; adjust-implicit-parents: when a :parent item at level N is followed
        ;; by non-parent items at the same level N, bump followers to N+1
        items (loop [remaining items, result [], last-parent-level nil]
                (if (empty? remaining)
                  result
                  (let [{:keys [level parent] :as item} (first remaining)]
                    (cond
                      parent
                      (recur (rest remaining) (conj result item) level)

                      (and last-parent-level (= level last-parent-level))
                      (recur (rest remaining) (conj result (update item :level inc)) last-parent-level)

                      :else
                      (recur (rest remaining) (conj result item) nil)))))]
    (letfn [(build [items min-level]
              (loop [remaining items, result []]
                (if (empty? remaining)
                  [result remaining]
                  (let [{:keys [level]} (first remaining)]
                    (cond
                      (= level min-level)
                      (let [[children rest-items] (build (rest remaining) (inc min-level))]
                        (recur rest-items (conj result (cond-> {:block/string (:text (first remaining))}
                                                        (seq children) (assoc :block/children children)))))
                      (> level min-level)
                      (let [[children rest-items] (build remaining (inc min-level))]
                        [(into result children) rest-items])
                      :else
                      [result remaining])))))]
      (first (build items (if (seq items) (:level (first items)) 0))))))

(defn to-roam-blocks
  "Convert tree nodes to Roam block format."
  [tree]
  (mapv (fn [{:keys [text content children]}]
          (let [content-lines (when-not (str/blank? content)
                                (remove str/blank? (str/split-lines content)))
                content-blocks (when (seq content-lines)
                                 (parse-list-items content-lines))
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
