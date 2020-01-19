(ns babylonui.core
  (:require
   [accountant.core :as accountant]
   [babylon.english :as en]
   [babylon.nederlands :as nl]
   [clerk.core :as clerk]
   [cljslog.core :as log]
   [clojure.string :as string]
   [dag_unify.core :as u]
   [dommy.core :as dommy]
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

(def expression-specification-atom (atom (nth nl/expressions 0)))
(def semantics-atom (reagent/atom nil))
(def debug-atom (atom (nth nl/expressions 0)))

(def app-state
  (reagent/atom
   {:expressions
    []}))

(defn update-expressions! [f & args]
  (apply swap! app-state update-in [:expressions] f args))

(defn source-spec [expression]
  {:sem (u/get-in expression [:sem])
   :phrasal (u/get-in expression [:phrasal])
   :head {:phrasal (u/get-in expression [:head :phrasal] :top)}
   :subcat (u/get-in expression [:subcat])
   :agr (u/get-in expression [:agr])
   :modal (u/get-in expression [:modal] false)
   :comp {:pronoun (u/get-in expression [:comp :pronoun] :top)
          :phrasal (u/get-in expression [:comp :phrasal] :top)}
   :cat (u/get-in expression [:cat])})

(defn contact [c]
  (log/info (str "generating nl.."))
  (let [expression (nl/generate @expression-specification-atom)]
    [:div.row
     [:div.expression 
      [:span (nl/morph expression)]]
     (log/info (str "generating en.."))
     [:div.expression 
      [:span (en/morph (en/generate (source-spec expression)))]]]))

(defn contact-list []
  [:div
   (for [c (:expressions @app-state)]
     [contact c])])

(defn add-contact! [c]
  (update-expressions! conj c))

(defn noop [arg])

(set! (.-onload js/window) 
      (fn []
        (noop 42)))

(declare show-expressions-dropdown)

(def next-key (atom 0))
(defn get-next-key []
  (let [next-value @next-key]
    (swap! next-key (fn [] (+ 1 @next-key)))
    next-value))

(defn home-page []
  (fn []
    [:div.main

     [:div
      [:input {:type "button" :value "Generate NL phrase"
               :on-click #(add-contact! {:key (get-next-key)})}]
      [show-expressions-dropdown]]

     [:div.debugpanel
      [:div
       (str @expression-specification-atom)]

      [:div
       (str @semantics-atom)]]

     [:div {:style {:width "100%" :float "left" :height "90%" :border "0px dashed blue"
                    :overflow "scroll"}}
      [contact-list]]]))

(defn show-expressions-dropdown []
  [:div {:style {:float "left" :border "0px dashed blue"}}
   [:select {:id "expressionchooser"
             :on-change #(do
                           (swap! expression-specification-atom
                                  (fn [x]
                                    (nth nl/expressions
                                         (js/parseInt (dommy/value (dommy/sel1 :#expressionchooser))))))                   
                           (add-contact! {}))}
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
