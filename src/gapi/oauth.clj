(ns gapi.oauth)

(def auth_url_base "")
(def token_url_base "")

;; Return a state map used to manage the authentication session. 
(defmethod set-auth-params)

(defn set-auth-params [api_key]
    (atom {apikey api_key}))

(defn set-auth-params [client_id, client_secret, redirect_url]
    (atom {clientid client_id, clientsecret client_secret, redirecturl redirect_url}))

;; Return a state for an oauth object
(defn generate-auth-url [state]
     ;; Pull out, and store state in state tuple
    )
    
;; Retrieve a token for for the connection, and put
;; into the state object. Returns true on success
;; false on failure
(defn exchange_token [state, code, checkstate]
    
    )

;; Refresh an almost expired token 
(defn refresh_token [state] 
    
    )