(ns samuraibff.ui.recordings-page
  "Request lifecycle for one server-paginated Sessions page."
  (:require
   ["react" :as react]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.pagination :as pagination]))

(defn use-recordings-page
  "Load sessions for local page/filter state and return data plus UI callbacks.

  Takes no arguments. Returns :items, :total, :drafts-count, :page-info,
  :show-drafts?, :loading?, :error and callbacks :refresh!, :set-page!,
  :set-page-size!, :set-show-drafts!. Filter/size changes reset to page one.
  Ignores responses after cleanup and reloads the last valid page after deletion.
  Request failures become a visible error; callers can retry with :refresh!."
  []
  (let [[query set-query!] (react/useState {:page 0 :page-size 20 :show-drafts? false :revision 0})
        [result set-result!] (react/useState nil)
        {:keys [page page-size show-drafts? revision]} query
        {:keys [data error]} result
        total (or (:total data) 0)
        loading? (or (:loading? result) (not= query (:query result)))
        refresh! (fn [] (set-query! #(update % :revision inc)))]
    (react/useEffect
     (fn []
       (let [active? (atom true)]
         (set-result! #(assoc % :loading? true :error nil))
         (-> (api/list-recordings! {:limit page-size :offset (* page page-size)
                                    :show-drafts? show-drafts?})
             (.then (fn [response]
                      (when @active?
                        (let [last-valid-page (:page (pagination/page-info (:total response) page page-size))]
                          (if (< last-valid-page page)
                            (set-query! #(assoc % :page last-valid-page))
                            (set-result! {:query query :data response :loading? false}))))))
             (.catch (fn [_]
                       (when @active?
                         (set-result! {:query query :data data :loading? false
                                       :error "Could not load sessions. Use Refresh to try again."})))))
         (fn [] (reset! active? false))))
     #js [page page-size show-drafts? revision])
    {:items (if (or loading? error) [] (:items data))
     :total total
     :drafts-count (or (:drafts_count data) 0)
     :page-info (pagination/page-info total page page-size)
     :show-drafts? show-drafts?
     :loading? loading?
     :error (when-not loading? error)
     :refresh! refresh!
     :set-page! (fn [value] (set-query! #(assoc % :page value)))
     :set-page-size! (fn [value] (set-query! #(assoc % :page 0 :page-size value)))
     :set-show-drafts! (fn [value] (set-query! #(assoc % :page 0 :show-drafts? value)))}))
