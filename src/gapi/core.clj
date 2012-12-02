(ns gapi.core
    (:require
        [clojure.data.json :as json]
        [clj-http.client :as http]
        [clojure.string :as string]))
        )

;; The base Discovery service URL we will process
(def base_url "https://www.googleapis.com/discovery/v1/apis")

;; List the available APIs that can be built
(defn list-apis []
    (let [discovery-doc (json/read-json ((http/get "https://www.googleapis.com/discovery/v1/apis") :body))]
        (map #(vector (str (%1 :name) "-" (%1 :version)) (%1 :discoveryRestUrl)) (discovery-doc :items))))

;; Macro to handle the authorisation tupple
(defn with-auth [auth])

;; Generate a client for the given API based on its discovery
;; document. 
(defn build [api_url]
    (let [  plus-doc (json/read-json ((http/get api_url) :body))
            base-url (plus-doc :baseUrl)
            api-methods (reduce
                (fn [resources [key resource]]
                    (reduce
                        (fn [methods [ikey method]]
                            (assoc ilist (method :id) 
                            (create-method base-url method)))
                            methods
                            (resource :methods)))
                        {}
                        (plus-doc :resources))
            ]
            api-methods
    )
    
   
;; Generate a client from a local file. 
(defn build-local [file])

