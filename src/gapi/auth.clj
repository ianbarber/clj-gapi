(ns gapi.auth
  (:import [java.net URLEncoder])
  (:require [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [clojure.string :as string]
            [crypto.random :as random]))

(def ^:const auth-url "https://accounts.google.com/o/oauth2/auth")
(def ^:const token-url "https://accounts.google.com/o/oauth2/token")
(def oauth2-state (string/replace (random/base64 10) #"[\+=/]" "-"))

(defn valid? [{:keys [token expires]}]
  (when token
    (< (System/currentTimeMillis) expires)))

(defn auth-params [{:keys [token api-key]} params]  
  (if token
    (update-in params [:headers]
               #(assoc % "Authorization" (str "Bearer " token)))
    (update-in params [:query-params]
               #(assoc % "key" api-key))))

(defn encode-params [params]
  (->> params
       (map (fn [[k v]]
              (str (URLEncoder/encode (name k) "UTF-8") "="
                   (URLEncoder/encode (name v) "UTF-8"))))
       (interpose "&")
       (apply str)))

(defn generate-auth-url  
  ([auth scopes]
     (generate-auth-url auth scopes "auto"))
  ([{:keys [client-id redirect-url] :as auth} scopes approval-prompt]     
     (str auth-url "?"
          (encode-params
           {:client_id client-id
            :redirect_uri redirect-url
            :scope (string/join " " scopes)
            :state oauth2-state
            :response_type "code"
            :access_type "offline"
            :approval_prompt approval-prompt}))))

(defn generate-auth-url-force [auth scopes]
  (generate-auth-url auth scopes "force"))

(defn exchange-token
  [{:keys [client-id redirect-url client-secret] :as auth} code checkstate]
  (when (= oauth2-state checkstate)
    (let [resp
          @(http/post
            token-url
            {:form-params
             {:code code
              :client_id client-id
              :redirect_uri redirect-url
              :client_secret client-secret
              :grant_type "authorization_code"}
             :headers {"Content-Type" "application/x-www-form-urlencoded"}})
          {:keys [access_token refresh_token expires_in]}          
          (json/read-json (:body resp))]
      (assoc auth
        :token access_token
        :refresh refresh_token
        :expires (+ (System/currentTimeMillis) (* expires_in 1000))))))

(defn refresh-token
  [{:keys [client-id redirect-url client-secret refresh] :as auth}]
  (when refresh
    (let [resp
          @(http/post
            token-url
            {:form-params
             {:client_id client-id
              :client_secret client-secret
              :refresh_token refresh
              :grant_type "refresh_token"}
             :headers {"Content-Type" "application/x-www-form-urlencoded"}})
          {:keys [access_token expires_in]} (json/read-json (:body resp))]
      (assoc auth
        :token access_token
        :expires (+ (System/currentTimeMillis) (* expires_in 1000))))))
