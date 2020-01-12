(ns babylonui.nederlands
  (:require-macros [babylon.nederlands])
  (:require
   [babylon.generate :as g]
   [babylon.nederlands :as nl]
   [babylon.nederlands.lexicon :as l]
   [babylon.serialization :as s]
   [cljslog.core :as log]
   [dag_unify.core :as u]))

(declare grammar)
(declare index-fn)
(declare morph)
(declare syntax-tree)

(defn generate [spec & [times]]
  (let [attempt
        (try
          (g/generate spec
                      (grammar)
                      (fn [spec]
                        (shuffle (index-fn spec)))
                      syntax-tree)
          (catch js/Error e
            (cond
              (or (nil? times)
                  (< times 2))
              (do
                (log/info (str "retry #" (if (nil? times) 1 (+ 1 times))))
                (generate spec (if (nil? times) 1 (+ 1 times))))
              true nil)))]
      (cond
        (and (or (nil? times)
                 (< times 2))
             (or (= :fail attempt)
                 (nil? attempt)))
        (do
          (log/info (str "retry #" (if (nil? times) 1 (+ 1 times))))
          (generate spec (if (nil? times) 1 (+ 1 times))))
        (or (nil? attempt) (= :fail attempt))
        (log/error (str "giving up generating after 2 times; sorry."))
        true
        {:structure attempt
         :syntax-tree (syntax-tree attempt)
         :surface (morph attempt)})))

(declare morphology)

(defn morph
  ([tree]
   (cond
     (map? (u/get-in tree [:syntax-tree]))
     (s/morph (u/get-in tree [:syntax-tree]) (morphology))

     true
     (s/morph tree (morphology))))

  ([tree & {:keys [sentence-punctuation?]}]
   (if sentence-punctuation?
     (-> tree
         morph
         (nl/sentence-punctuation (u/get-in tree [:sem :mood] :decl))))))

(def grammar-atom (atom nil))
(def morphology-atom (atom nil))
(def expressions-atom (atom nil))

(defn grammar []
  (->> (nl/read-compiled-grammar)
       (map dag_unify.serialization/deserialize)))

(defn morphology []
  (or @morphology-atom
      (do (swap! morphology-atom (fn [] (nl/compile-morphology)))
          @morphology-atom)))

(defn expressions []
  (or @expressions-atom
      (do (swap! expressions-atom (fn [] (nl/read-expressions))))))

;; note that we exclude [:exception]s from the lexemes that we use for
;; generation since they are only to be used for parsing.
;; TODO: this is duplicated in babylon/nederlands.cljc (see def verb-lexicon).
(defn lexeme-map []
  (if (nil? @l/lexeme-map-atom)
    (do (swap! l/lexeme-map-atom
               (fn []
                 {:verb (->> (l/lexicon)
                             (filter #(= :verb (u/get-in % [:cat])))
                             (filter #(not (u/get-in % [:exception]))))
                  :det (->> (l/lexicon)
                            (filter #(= :det (u/get-in % [:cat]))))
                  :intensifier (->> (l/lexicon)
                                    (filter #(= :intensifier (u/get-in % [:cat]))))
                  :noun (->> (l/lexicon)
                             (filter #(= :noun (u/get-in % [:cat])))
                             (filter #(not (u/get-in % [:exception]))))
                  :top (l/lexicon)
                  :adjective (->> (l/lexicon)                                                          
                                  (filter #(= :adjective (u/get-in % [:cat]))))})))
    @l/lexeme-map-atom))

(defn syntax-tree [tree]
  (s/syntax-tree tree (morphology)))

(defn index-fn [spec]
  ;; for now a somewhat bad index function: simply returns
  ;; lexemes which match the spec's :cat, or, if the :cat isn't
  ;; defined, just return all the lexemes.
  (log/debug (str "looking for key: " (u/get-in spec [:cat] ::none)))
  (let [result (get (lexeme-map) (u/get-in spec [:cat] :top) nil)]
    (if (not (nil? result))
        (shuffle result)
        (do
          (log/info (str "no entry from cat: " (u/get-in spec [:cat] ::none) " in lexeme-map: returning all lexemes."))
          (l/lexicon)))))
