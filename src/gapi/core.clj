(ns gapi.core
  (:require
   [clojure.data.json :as json]
   [org.httpkit.client :as http]
   [clojure.string :as string]
   [gapi.auth :as auth]))

(def ^:const discovery-url "https://www.googleapis.com/discovery/v1/apis")
(def cache (atom {}))

(defn list-apis []
  (let [discovery-doc (json/read-json ((http/get discovery-url) :body))]
    (map #(vector (% :name) (% :version) (% :discoveryRestUrl))
         (filter #(= (% :preferred) true) (discovery-doc :items)))))

(defn get-method-name [name]
  (let [parts (clojure.string/split name #"\.")]
    (str (clojure.string/join "." (pop parts)) "/" (last parts))))

(defn match-params [params func]
  (let [required (filter (fn [[k v]] (func v)) params)]
    (map (fn [[k v]] (name k)) required)))

(defn get-path-params [params]
  (match-params params (fn [p] (= "path" (p :location)))))

(defn get-required-params [params]
  (match-params params (fn [p] (p :required))))

(defn docstring [method]
  (str (method :description) "\n"
       "Required parameters: " (string/join " " (get-required-params (method :parameters)))
       "\n"))

(defn arglists [method]
  (let [base_args
        (if (= (method :description) "POST") '[auth parameters body] '[auth parameters])]
    base_args))

(defn hasreqs? [params args]
  (reduce #(and % %2) true (map #(contains? args %) (get-required-params params))))

(defn get-response [res]
  (if (= (res :status) 200)
    (json/read-json (res :body))
    (let [body (json/read-json (res :body))]
      {:error ((body :error) :message)})))

(defn get-url [base_url path params args]
  (str base_url
       (reduce #(string/replace % (str "{" %2 "}") (args %2)) path params)))

(defmulti callfn (fn [base_url method] (method :httpMethod)))

(defmacro defcallfn [n fun]
  `(defmethod callfn ~n [base_url# {path# :path method_params# :parameters}]
     (fn ([state# args#]
            {:pre [(hasreqs? method_params# args#)]}
            (get-response (~fun (get-url base_url# path# (get-path-params method_params#) args#)
                                (auth/call-params state# {:throw-exceptions false :query-params args#})))))))

(defcallfn "GET" http/get)
(defcallfn "POST" http/post)
(defcallfn "PUT" http/put)
(defcallfn "PATCH" http/patch)
(defcallfn "DELETE" http/delete)
(defcallfn "COPY" http/copy)
(defcallfn "MOVE" http/move)
(defcallfn "HEAD" http/head)

(defn extract-methods [base_url resource]
  (reduce
   (fn [methods [key method]]
     (assoc methods
       (get-method-name (:id method))
       {:fn (callfn base_url method)
        :doc (docstring method)
        :arglists (arglists method)
        :scopes (:scopes method)}))
   {} (:methods resource)))

(defn build-resource [base r]
  (reduce
   (fn [methods [key resource]]
     (merge methods
            (extract-methods base resource)
            (build-resource base resource)))
   {}
   (:resources r)))

(defn build [api_url]
  (let [r (json/read-json ((http/get api_url) :body))]
    (build-resource (:baseUrl r) r)))

(def m-list-apis (memoize list-apis))
(def m-build (memoize build))

(defn call [auth service method & args]
  (apply ((service method) :fn) auth args))

(defn im
  ([auth method_name & args]
     (let [service_name (first (clojure.string/split method_name #"[\.\/]"))
           api (last (filter #(= (first %1) service_name) (m-list-apis)))
           service (m-build (last api))]
       (apply call auth service method_name args))))

(defn anon-im [method_name & args]
  (apply im nil method_name args))

(defn build-ns [auth [mname method]]
  (let [name (str "gapi." mname)
        parts (clojure.string/split name #"[\.\/]")
        namespace (symbol (clojure.string/join "." (pop parts)))]
    (if (= nil (find-ns namespace)) (create-ns namespace))
    (intern namespace
            (with-meta (symbol (last parts)) {:doc (method :doc) :arglists (method :arglists)})
            (partial (method :fn) auth))
    name))

(defn api-ns
  ([api_url]
     (api-ns nil api_url))
  ([auth api_url]
     (let [service (m-build api_url)
           build-fn (partial build-ns auth)]
       (map build-fn service))))

(defn list-methods [service]
  (keys service))

(defn get-doc [service method]
  ((service method) :doc))

(defn get-scopes [service method]
  ((service method) :scopes))
