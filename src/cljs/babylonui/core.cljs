(ns babylonui.core
  (:require
   [accountant.core :as accountant]
   [clerk.core :as clerk]
   [cljslog.core :as log]
   [clojure.core :as c]
   [dag_unify.core :as u]
   [babylonui.language :as l]
   [reagent.core :as reagent]
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
;; -------------------------
;; Page components

(def en-lexical-entry (reagent/atom ""))
(def nl-lexical-entry (reagent/atom ""))

(defn load-an-entry [lexicon]
  (let [key (first (shuffle (keys lexicon)))]
    (log/info (str "loading entry: " key))
    [:div
     [:b {:style {:font-size "200%"}} key ] " " (first (shuffle (get lexicon key)))]))
(defn generate-a-np [grammar lexicon]
  (let [rule (first (shuffle (filter #(= :noun (u/get-in % [:cat]))
                                     grammar)))]
    (log/info (str "showing noun-type rule: " (u/get-in rule [:rule])))
    [:div
     [:b {:style {:font-size "200%"}} (u/get-in rule [:rule])] " " rule]))

(defn home-page []
  (fn []
    [:div.main
     [:h1 "babylon UI"]
     [:div.language
      [:input {:type "button" :value "NL lexeme"
               :on-click #(swap! nl-lexical-entry (fn [] (load-an-entry l/nl-lexicon)))}]
      [:div.lexeme
       @nl-lexical-entry]]

     [:div.language
     [:div.expression
      [:input {:type "button" :value "NL NP"
               :on-click #(swap! nl-np-contents (fn [] (generate-a-np l/nl-grammar l/nl-lexicon)))}]
      [:div.behind-the-scenes
       @nl-np-contents]]
      [:input {:type "button" :value "EN lexeme"
               :on-click #(swap! en-lexical-entry (fn [] (load-an-entry l/en-lexicon)))}]
      [:div.lexeme
       @en-lexical-entry]]]))

(defn items-page []
  (fn []
    [:span.main
     [:h1 "The items of babylonUI"]
     [:ul (map (fn [item-id]
                 [:li {:name (str "item-" item-id) :key (str "item-" item-id)}
                  [:a {:href (path-for :item {:item-id item-id})} "Item: " item-id]])
               (range 1 60))]]))


(defn item-page []
  (fn []
    (let [routing-data (session/get :route)
          item (get-in routing-data [:route-params :item-id])]
      [:span.main
       [:h1 (str "Item " item " of babylon UI")]
       [:p [:a {:href (path-for :items)} "Back to the list of items"]]])))


(defn about-page []
  (fn [] [:span.main
          [:h1 "About babylon UI"]]))


;; -------------------------
;; Translate routes -> page components

(defn page-for [route]
(case route
  :index #'home-page
  :about #'about-page
  :items #'items-page
  :item #'item-page))


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
         [:a {:href "https://github.com/reagent-project/reagent-template"} "Reagent Template"] "."]]])))

;; -------------------------
;; Initialize app

(defn mount-root []
(reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
(clerk/initialize!)
(accountant/configure-navigation!
 {:nav-handler
  (fn [path]
    (let [match (reitit/match-by-path router path)
          current-page (:name (:data  match))
          route-params (:path-params match)]
      (reagent/after-render clerk/after-render!)
      (session/put! :route {:current-page (page-for current-page)
                            :route-params route-params})
      (clerk/navigate-page! path)
      ))
  :path-exists?
  (fn [path]
    (boolean (reitit/match-by-path router path)))})
  (accountant/dispatch-current!)
  (mount-root))
