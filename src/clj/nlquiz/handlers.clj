(ns nlquiz.handlers
  (:require
   [clojure.tools.logging :as log]
   [menard.english :as en]
   [menard.nederlands :as nl]
   [menard.translate :as tr]
   [dag_unify.core :as u]))

(def nl-expressions
  (filter #(= true (u/get-in % [:menuable?] true))
          nl/expressions))

(defn dag-to-string [dag]
  (-> dag dag_unify.serialization/serialize str))

(defn generate
  "generate a Dutch expression from _spec_ and translate to English, and return this pair
   along with the semantics of the English specification also."
  [spec]
  (let [debug (log/debug (str "generating a question with spec: " spec))
        debug (log/debug (str "input spec type: " (type spec)))
        target-expression (-> spec nl/generate)
        ;; try twice to generate a source expression: fails occasionally for unknown reasons:
        source-expression (->> (repeatedly #(-> target-expression tr/nl-to-en-spec en/generate))
                               (take 2)
                               (filter #(not (empty? %)))
                               first)
        source-semantics (->> source-expression en/morph en/parse (map #(u/get-in % [:sem])))]
    (log/info (str "given input input spec: " spec ", generated: '" (-> source-expression en/morph) "'"
                   " -> '"  (-> target-expression nl/morph) "'"))
    (let [result
          {:source (-> source-expression en/morph)
           :source-tree source-expression
           :target-tree target-expression
           :target-root (-> target-expression (u/get-in [:head :root] :top))
           :source-sem (map dag-to-string source-semantics)
           :target (-> target-expression nl/morph)}]
      (when (empty? source-expression)
        (log/error (str "failed to generate a source expression for spec: " spec "; target expression: "
                       (nl/syntax-tree target-expression)))
        (log/error (str " tried to generate from: "
                        (dag_unify.serialization/serialize (-> target-expression tr/nl-to-en-spec)))))
      result)))

(def ^:const clean-up-trees true)

(defn generate-with-alternations
  "generate with _spec_ unified with each of the alternates, so generate one expression per <spec,alternate> combination."
  [spec alternates]
  (log/debug (str "generating with spec: " spec " and alternates: "
                  (clojure.string/join "," alternates)))
  (let [derivative-specs
        (->>
         alternates
         (map (fn [alternate]
                (u/unify alternate spec))))
        ;; the first one is special: we will get the [:head :root] from it and use it with the rest of the specs.
        first-expression (generate (first derivative-specs))
        expressions
        (cons first-expression
              (->> (rest derivative-specs)
                   (map (fn [derivative-spec]
                          (generate (u/unify derivative-spec
                                             {:head {:root
                                                     (u/get-in first-expression [:target-tree :head :root] :top)}}))))))]
    (if clean-up-trees
      (->> expressions
           ;; cleanup the huge syntax trees:
           (map #(-> %
                     (dissoc % :source-tree (dag-to-string (:source-tree %)))
                     (dissoc % :target-tree (dag-to-string (:target-tree %))))))
          
      ;; don't cleanup the syntax trees, but serialize them so they can be printed to json:
      (map #(-> %
                (assoc :source-tree (dag-to-string (:source-tree %)))
                (assoc :target-tree (dag-to-string (:target-tree %))))))))

(defn generate-by-expression-index
  "look up a specification in the 'nl-expressions' array and generate with it."
  [_request]
  (let [expression-index (-> _request :path-params :expression-index)
        spec (nth nl-expressions (Integer. expression-index))]
    (generate spec)))

(defn generate-by-spec
  "decode a spec from the input request and generate with it."
  [_request]
  (let [spec (get (-> _request :query-params) "q")]
    (log/debug (str "spec pre-decode: " spec))
    (let [spec (-> spec read-string dag_unify.serialization/deserialize)]
      (log/debug (str "generate-by-spec with spec: " spec))
      (-> spec
          generate
          (dissoc :source-tree)
          (dissoc :target-tree)))))

(defn generate-by-spec-with-alts
  "decode a spec from the input request and generate with it."
  [_request]
  (let [spec (get (-> _request :query-params) "spec" "[]")
        alts (get (-> _request :query-params) "alts" "[]")]
    (let [spec (-> spec read-string dag_unify.serialization/deserialize)
          alts (->> alts read-string (map dag_unify.serialization/deserialize))]
      (log/debug (str "generate-by-spec-with-alts: spec decoded: " spec))
      (log/debug (str "generate-by-spec-with-alts: alts decoded: " alts))
      (generate-with-alternations spec alts))))

(defn parse-nl [_request]
  (let [string-to-parse
        (get
         (-> _request :query-params) "q")]
    (log/debug (str "parsing input: " string-to-parse))
    (let [parses (->> string-to-parse
                      clojure.string/lower-case
                      nl/parse
                      (filter #(or (= [] (u/get-in % [:subcat]))
                                   (= :top (u/get-in % [:subcat]))
                                   (= ::none (u/get-in % [:subcat] ::none))))
                      (filter #(= nil (u/get-in % [:mod] nil)))
                      (sort (fn [a b] (> (count (str a)) (count (str b)))))
                      (take 1))
          syntax-trees (->> parses (map nl/syntax-tree))]
      {:trees syntax-trees
       :english (-> (->> parses
                         (map tr/nl-to-en-spec)
                         (map en/generate)
                         (map en/morph)
                         (sort (fn [a b] (> (count a) (count b)))))
                    first)
       :sem (->> parses
                 (map #(u/get-in % [:sem]))
                 (map dag-to-string))})))

(defn parse-en [_request]
  (let [string-to-parse
        (get
         (-> _request :query-params) "q")]
    (log/debug (str "parsing input: " string-to-parse))
    (let [parses (->> string-to-parse clojure.string/lower-case en/parse
                      (filter #(or (= [] (u/get-in % [:subcat]))
                                   (= :top (u/get-in % [:subcat]))
                                   (= ::none (u/get-in % [:subcat] ::none))))
                      (filter #(= nil (u/get-in % [:mod] nil))))
          syntax-trees (->> parses (map en/syntax-tree))]
      {:trees syntax-trees
       :sem (->> parses
                 (map #(u/get-in % [:sem]))
                 (map dag-to-string))})))
