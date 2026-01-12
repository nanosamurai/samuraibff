(ns samuraibff.ui.core
  "SPA entrypoint for the samuraibff UI.

  Shadow-cljs builds this namespace into resources/public/js/main.js (see shadow-cljs.edn)."
  (:require
    [io.factorhouse.hsx.core :as hsx]
    [samuraibff.ui.components :as components]
    [samuraibff.ui.router :as router]
    [samuraibff.ui.store :as store]
    ["react-dom/client" :refer [createRoot]]))

(defonce root*
  (createRoot (.getElementById js/document "app")))

(defn render!
  "Render the root React component." 
  []
  (.render root*
           (hsx/create-element
             [components/app])))

(defn init
  "Initialize the UI." 
  []
  (store/append-log! "[boot] UI init")
  (router/init-router!)
  (render!))

(defn ^:dev/after-load reload
  "Hot-reload hook for shadow-cljs." 
  []
  (components/memo-clear!)
  (router/memo-clear!)
  (render!))

(init)
