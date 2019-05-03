(ns bluegenes.events
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [re-frame.core :as re-frame :refer [reg-event-db reg-fx reg-event-fx dispatch subscribe]]
            [im-tables.events]
            [bluegenes.events.boot]
            [bluegenes.events.auth]
            [bluegenes.components.idresolver.events]
            [bluegenes.pages.mymine.events]
            [day8.re-frame.http-fx]
            [day8.re-frame.forward-events-fx]
            [day8.re-frame.async-flow-fx]
            [bluegenes.pages.reportpage.events]
            [bluegenes.components.search.events]
            [bluegenes.components.navbar.events]
            [bluegenes.pages.results.enrichment.events]
            [bluegenes.components.search.events :as search-full]
            [bluegenes.pages.reportpage.events]
            [bluegenes.pages.querybuilder.events]
            [bluegenes.effects]
            [bluegenes.persistence :as persistence]
            [imcljs.fetch :as fetch]
            [imcljs.path :as im-path]
            [clojure.string :refer [join split]]
            [cljs.core.async :refer [put! chan <! >! timeout close!]]
            [imcljs.fetch :as fetch]))

; Change the main panel to a new view
(reg-event-fx
 :do-active-panel
 (fn [{db :db} [_ active-panel panel-params evt]]
   (cond-> {:db (assoc db
                       :active-panel active-panel
                       :panel-params panel-params)}
     evt (assoc :dispatch evt))))

; A buffer between booting and changing the view. We only change the view
; when the assets have been loaded
(reg-event-fx
 :set-active-panel
 (fn [{db :db} [_ active-panel panel-params evt]]
   (cond-> {:db db}
     (:fetching-assets? db) ; If we're fetching assets then save the panel change for later
     (assoc :forward-events {:register :coordinator1
                             :events #{:finished-loading-assets}
                             :dispatch-to [:do-active-panel active-panel panel-params evt]})
     (not (:fetching-assets? db)) ; Otherwise dispatch it now (and the optional attached event)
     (assoc :dispatch-n
            (cond-> [[:do-active-panel active-panel panel-params evt]]
              evt (conj evt))))))

(reg-event-fx
 :save-state
 (fn [{:keys [db]}]
    ;;So this saves assets and current mine to the db. We don't do any complex caching right now - every boot or mine change, these will be loaded afresh and applied on top. It *does* mean that the assets can be used before they are loaded.
    ;;why isn't there caching? because it gets very complex deciding what and when to expire, so it's not really a minimum use case feature.
   (let [saved-keys (select-keys db [:current-mine :mines :assets])]
      ; Attach the client version to the saved state. This will be checked
      ; the next time the client boots to make sure the local storage data
      ; and the client version number are aligned.
     (persistence/persist! (assoc saved-keys :version bluegenes.core/version))
     {:db db})))

(reg-event-fx
 :set-active-mine
 (fn [{:keys [db]} [_ new-mine keep-existing?]]
   (let [new-mine-keyword (keyword (:namespace new-mine))
         in-mine-list? (get-in db [:mines new-mine-keyword])]

     {:db
      (cond->
       (assoc db :current-mine new-mine-keyword)
        (not keep-existing?) (assoc-in [:assets] {})
        (not in-mine-list?)
        (assoc-in [:mines new-mine-keyword]
                  {:service {:root (:url new-mine)}
                   :name (:name new-mine)
                   :id new-mine-keyword}))
      :dispatch-n (list
                   [:reboot]
                   [:set-active-panel :home-panel])
      :visual-navbar-minechange []})))

(reg-event-db
 :handle-suggestions
 (fn [db [_ results]]
   (assoc db :suggestion-results
          (:results results))))

(reg-event-fx
 :bounce-search
 (fn [{db :db} [_ term]]
   (let [connection (get-in db [:mines (get db :current-mine) :service])
         suggest-chan (fetch/quicksearch connection term {:size 5})]
     (if-let [c (:search-term-channel db)] (close! c))
     {:db (-> db
              (assoc :search-term-channel suggest-chan)
              (assoc :search-term term))
      :suggest {:c suggest-chan :search-term term :source (get db :current-mine)}})))

(reg-event-db
 :cache/store-organisms
 (fn [db [_ res]]
   (assoc-in db [:cache :organisms] (:results res))))

(reg-event-fx
 :cache/fetch-organisms
 (fn [{db :db}]
   (let [model (get-in db [:assets :model])
         organism-query {:from "Organism"
                         :select ["name"
                                  "taxonId"
                                  "species"
                                  "shortName"
                                  "genus"
                                  "commonName"]}]
     {:db db
      :im-chan {:chan (fetch/rows
                       (get-in db [:mines (:current-mine db) :service])
                       organism-query
                       {:format "jsonobjects"})
                :on-success [:cache/store-organisms]}})))

(reg-event-db
 :cache/store-possible-values
 (fn [db [_ mine-kw view-vec results]]
   (if (false? results)
     (assoc-in db [:mines mine-kw :possible-values view-vec] false)
     (assoc-in db [:mines mine-kw :possible-values view-vec] (not-empty (map :item (:results results)))))))

(reg-fx
 :cache/fetch-possible-values-fx
 (fn [{:keys [mine-kw service store-in summary-path query]}]
   (let [sum-chan (fetch/unique-values service query summary-path 7000)]
     (go
       (dispatch [:cache/store-possible-values mine-kw summary-path (<! sum-chan)])))))

(reg-event-fx
 :cache/fetch-possible-values
 (fn [{db :db} [_ path]]
   (let [mine (get-in db [:mines (get db :current-mine)])
         split-path (split path ".")
         existing-value (get-in db [:mines (get db :current-mine) :possible-values split-path])]

     (if (and (nil? existing-value) (not (im-path/class? (get-in mine [:service :model]) path)))
       {:cache/fetch-possible-values-fx {:service (get mine :service)
                                         :query {:from (first split-path)
                                                 :select [path]}
                                         :mine-kw (get mine :id)
                                         :summary-path path}}
       {:dispatch [:cache/store-possible-values (get mine :id) path false]}))))

(reg-event-db
 :flag-invalid-tokens
 (fn [db]
   (assoc db :invalid-tokens? true)))

(reg-event-db
 :scramble-tokens
 (fn [db]
   (assoc-in db [:mines :flymine-beta :service :token] "faketoken")))

(reg-event-db
 ;; IS THIS USED?
 :messages/add
 (fn [db [_ {:keys [markup style]}]]
   (let [id (gensym)]
     (assoc-in db [:messages id]
               {:markup markup
                :id id
                :style style
                :when (.getTime (js/Date.))}))))

(reg-event-db
 :messages/remove
 (fn [db [_ id]]
   (update db :messages dissoc id)))

(defn ^:export scrambleTokens []
  (dispatch [:scramble-tokens]))
