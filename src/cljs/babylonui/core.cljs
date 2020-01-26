(ns babylonui.core
  (:require
   [accountant.core :as accountant]
   [babylon.english :as en]
   [babylon.nederlands :as nl]
   [babylon.translate :as tr]
   [clerk.core :as clerk]
   [cljslog.core :as log]
   [clojure.string :as string]
   [dag_unify.core :as u]
   [dommy.core :as dommy]
   [reagent.core :as r]
   [reagent.session :as session]
   [reitit.frontend :as reitit]))

;; -------------------------
;; Routes

(def router
  (reitit/router
   [["/" :index]
    ["/items"
     ["" :items]
     ["/:item-id" :item]]
    ["/about" :about]]))

(defn path-for [route & [params]]
  (if params
    (:path (reitit/match-by-name router route params))
    (:path (reitit/match-by-name router route))))

(path-for :about)

(def expression-specification-atom (atom (nth nl/expressions 0)))
(def semantics-atom (r/atom nil))

(def target-expressions
  (r/atom []))

(def source-expressions
  (r/atom []))

(declare show-expressions-dropdown)

(defn do-the-source-expression [target-expression]
  (let [source-expression-node {:morph
                                (try
                                  (-> target-expression
                                      tr/nl-to-en-spec
                                      en/generate
                                      en/morph)
                                  (catch js/Error e
                                    (do
                                      (log/warn (str "failed to generate: " e))
                                      "??")))}]
    (log/info (str "source-expression: " (:morph source-expression-node)))
    (swap! source-expressions
           (fn [existing-expressions]

             (log/info (str "length of existing expressions: " (count existing-expressions)))
             (if (> (count existing-expressions) 5)
               (cons source-expression-node (butlast existing-expressions))
               (cons source-expression-node existing-expressions))))))

(defn update-target-expressions! [expression-node]
  (swap! target-expressions
         (fn [existing-expressions]
           (log/info (str "length of existing expressions: " (count existing-expressions)))
           (if (> (count existing-expressions) 5)
             (cons expression-node (butlast existing-expressions))
             (cons expression-node existing-expressions)))))

(defn generate []
  (let [target-expression
        (nl/generate @expression-specification-atom)]
    (update-target-expressions!
     {:expression target-expression})
    (do-the-source-expression target-expression)))

(set! (.-onload js/window)
      (fn []))

(defn timer-component []
  (let [generated (r/atom 0)]
    (fn []
      (do
        (generate)
        (js/setTimeout #(swap! generated inc) 5000))
      [:div {:style {:float "left"}}
       "Generated: " (inc @generated)])))

(defn home-page []
  (fn []
    [:div.main
     [show-expressions-dropdown]
     [:div.debugpanel
      [:div
       (str @expression-specification-atom)]]

     [:div
      (str @semantics-atom)]

     [:div {:style {:float "left"}}
      [:div {:class ["expressions" "target"]}
       (map (fn [expression-node]
              (let [target-spec (:spec expression-node)
                    target-expression (:expression expression-node)]
                (log/info (str "target expression: " (nl/morph target-expression)))
                [:div.expression {:key (str expression-node)}
                 [:span (nl/morph target-expression)]]))
            @target-expressions)]

      [:div {:class ["expressions" "source"]}
       (map (fn [expression-node]
              [:div.expression {:key (str expression-node)}
               [:span (:morph expression-node)]])
            @source-expressions)]]
     [timer-component]]))

(defn show-expressions-dropdown []
  [:div {:style {:float "left" :border "0px dashed blue"}}
   [:select {:id "expressionchooser"
             :on-change #(reset! expression-specification-atom
                                 (nth nl/expressions
                                      (js/parseInt
                                       (dommy/value (dommy/sel1 :#expressionchooser)))))}
    (map (fn [item-id]
           (let [expression (nth nl/expressions item-id)]
             [:option {:name item-id
                       :value item-id
                       :key (str "item-" item-id)}
              (:note expression)]))
         (range 0 (count nl/expressions)))]])

(defn about-page []
  (fn [] [:span.main
          [:h1 "About babylon UI"]]))

;; -------------------------
;; Translate routes -> page components

(defn page-for [route]
  (case route
    :index #'home-page
    :about #'about-page))

;; -------------------------
;; Page mounting component

(defn current-page []
  (fn []
    (let [page (:current-page (session/get :route))]
      [:div
       [:header
        [:p [:a {:href (path-for :index)} "Home"] " | "
         [:a {:href (path-for :about)} "About babylon UI"]]]
       [page]
       [:footer
        [:p "Babylon UI was generated by the "
         [:a {:href "https://github.com/reagent-project/reagent-template"}
          "Reagent Template"] "."]]])))

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (clerk/initialize!)
  (accountant/configure-navigation!
   {:nav-handler
    (fn [path]
      (let [match (reitit/match-by-path router path)
            current-page (:name (:data  match))
            route-params (:path-params match)]
        (r/after-render clerk/after-render!)
        (session/put! :route {:current-page (page-for current-page)
                              :route-params route-params})
        (clerk/navigate-page! path)
        ))
    :path-exists?
    (fn [path]
      (boolean (reitit/match-by-path router path)))})
  (accountant/dispatch-current!)
  (mount-root))
