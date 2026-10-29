(ns samuraibff.http.access-routes
  "Reitit route data for access control: sharing, directory and People.

  Kept out of `samuraibff.http.router` so the router namespace stays small.
  Every route here sits under `/api` and therefore behind authentication and
  `samuraibff.access.principal/wrap-principal`.

  Public API:
  - `principal-middleware`, `api-routes`"
  (:require
   [samuraibff.access.principal :as access.principal]
   [samuraibff.access.schemas :as access.schemas]
   [samuraibff.http.people :as http.people]
   [samuraibff.http.sharing :as http.sharing]
   [samuraibff.schemas :as schemas]))

(defn principal-middleware
  "Reitit middleware data that resolves the principal for each /api route.

  Routes with `:access/allow-inactive? true` receive inactive access states;
  all other routes require an active principal."
  [deps]
  {:name ::principal
   :compile (fn [route-data _opts]
              (let [allow-inactive? (true? (:access/allow-inactive? route-data))]
                (fn [handler]
                  (access.principal/wrap-principal handler deps allow-inactive?))))})

(defn api-routes
  "Return access-control route vectors to splice under `/api`.

  Inputs: router deps map and revoke-credentials! (fn [tenant-id manager-id])."
  [deps revoke-credentials!]
  [["/directory"
    {:get {:summary "Find colleagues to share with"
           :description "Returns up to 20 active people of the caller's organization."
           :parameters {:query [:map [:q {:optional true} [:string {:max 100}]]]}
           :responses {200 {:body access.schemas/DirectoryResponse}
                       403 {:body schemas/ApiErrorResponse}
                       503 {:body schemas/ApiErrorResponse}}
           :handler (http.sharing/directory-handler deps)}}]

   ["/sessions/:session_id/access"
    {:parameters {:path [:map [:session_id :string]]}
     :get {:summary "Get session audience"
           :description "Returns who can read the session. Only the owner sees recipients."
           :responses {200 {:body access.schemas/SessionAccessResponse}
                       400 {:body schemas/ApiErrorResponse}
                       404 {:body schemas/ApiErrorResponse}
                       503 {:body schemas/ApiErrorResponse}}
           :handler (http.sharing/get-access-handler deps)}
     :put {:summary "Share session"
           :description (str "Replaces the session audience: private, selected people or everyone in the "
                             "organization. Owner only. Send the current revision; a stale revision returns 409.")
           :parameters {:body access.schemas/UpdateSessionAccessRequest}
           :responses {200 {:body access.schemas/SessionAccessResponse}
                       400 {:body schemas/ApiErrorResponse}
                       403 {:body schemas/ApiErrorResponse}
                       404 {:body schemas/ApiErrorResponse}
                       409 {:body [:map [:ok :boolean] [:message :string] [:revision {:optional true} :int]]}
                       503 {:body schemas/ApiErrorResponse}}
           :handler (http.sharing/put-access-handler deps)}}]

   ["/sessions/:session_id/owner"
    {:parameters {:path [:map [:session_id :string]]}
     :put {:summary "Assign session owner"
           :description (str "Admin only. Gives a session recorded before access control (owner unknown) "
                             "an owner: an active person of the organization. The audience stays. "
                             "Send the current revision; a stale revision returns 409.")
           :parameters {:body access.schemas/AssignSessionOwnerRequest}
           :responses {200 {:body access.schemas/SessionAccessResponse}
                       400 {:body schemas/ApiErrorResponse}
                       403 {:body schemas/ApiErrorResponse}
                       404 {:body schemas/ApiErrorResponse}
                       409 {:body [:map [:ok :boolean] [:message :string] [:revision {:optional true} :int]]}
                       503 {:body schemas/ApiErrorResponse}}
           :handler (http.sharing/put-owner-handler deps)}}]

   ["/people"
    {:no-doc false}
    [""
     {:get {:summary "List people"
            :description "Admin only. Lists people of the Admin's organization."
            :parameters {:query [:map
                                 [:q {:optional true} [:string {:max 100}]]
                                 [:limit {:optional true} :int]
                                 [:offset {:optional true} :int]]}
            :responses {200 {:body access.schemas/PeopleListResponse}
                        403 {:body schemas/ApiErrorResponse}
                        503 {:body schemas/ApiErrorResponse}}
            :handler (http.people/list-people-handler deps)}
      :post {:summary "Add person"
             :description "Admin only. Creates the identity and sends a setup email when provisioning is enabled."
             :parameters {:body access.schemas/AddPersonRequest}
             :responses {200 {:body access.schemas/PersonResponse}
                         400 {:body schemas/ApiErrorResponse}
                         403 {:body schemas/ApiErrorResponse}
                         409 {:body schemas/ApiErrorResponse}
                         502 {:body schemas/ApiErrorResponse}
                         503 {:body schemas/ApiErrorResponse}}
             :handler (http.people/add-person-handler deps)}}]
    ["/:id"
     {:parameters {:path [:map [:id :string]]}}
     [""
      {:patch {:summary "Change person role or access"
               :description "Admin only. The last active Admin cannot be demoted or suspended."
               :parameters {:body access.schemas/UpdatePersonRequest}
               :responses {200 {:body access.schemas/PersonResponse}
                           400 {:body schemas/ApiErrorResponse}
                           403 {:body schemas/ApiErrorResponse}
                           404 {:body schemas/ApiErrorResponse}
                           409 {:body schemas/ApiErrorResponse}
                           503 {:body schemas/ApiErrorResponse}}
               :handler (http.people/update-person-handler deps revoke-credentials!)}}]
     ["/setup-email"
      {:post {:summary "Resend setup email"
              :description "Admin only. Resends the setup email to a pending person (once per minute)."
              :responses {200 {:body access.schemas/PersonResponse}
                          403 {:body schemas/ApiErrorResponse}
                          404 {:body schemas/ApiErrorResponse}
                          409 {:body schemas/ApiErrorResponse}
                          429 {:body schemas/ApiErrorResponse}
                          503 {:body schemas/ApiErrorResponse}}
              :handler (http.people/resend-setup-email-handler deps)}}]]]])
