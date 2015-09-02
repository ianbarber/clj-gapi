(ns gapi.core
    (:require
        [clojure.data.json :as json]
        [clj-http.client :as http]
        [clj-http.util :refer [url-encode]]
        [clojure.string :as string]
		[gapi.auth :as auth]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;; SETUP ;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Forward Declarations
(declare extract-methods)
(declare build-ns)

;; The base Discovery Service URL we will process
(def ^{:private true} discovery_url "https://www.googleapis.com/discovery/v1/apis")
(def ^{:private true} cache (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;  API  ;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn list-apis
	"Directly eturn a list of APIs available to built, and endpoint discovery document URLs."
	[]
    (let [discovery-doc (json/read-json ((http/get discovery_url) :body))]
        (map #(vector (%1 :name) (%1 :version) (%1 :discoveryRestUrl))
					(filter #(= (%1 :preferred) true) (discovery-doc :items)))))

(defn build-resource [base r]
  (reduce
   (fn [methods [key resource]]
     (merge methods
            (extract-methods base resource)
            (build-resource base resource)))
   {}
   (:resources r)))

(defn build
	"Given a discovery document URL, construct an map of names to functions that
	will make the various calls against the resources. Each call accepts a gapi.auth
	state map, and list of argument values, an in some cases a JSON encoded body to send
	(for write calls)"
	[api_url]
        (let [r (json/read-json ((http/get api_url) :body))]
          (build-resource (:baseUrl r) r)))

(defn list-methods
	"List the available methods in a service"
	[service]
	(keys service))

(defn get-doc
	"Retrieve a docstring for the service call"
	[service method]
	((service method) :doc))

(defn get-scopes
	"Retrieve a vector of scopes for the service"
	[service method]
	((service method) :scopes))

(defn call
	"Call a service function"
	[auth service method & args]
	(apply ((service method) :fn) auth args))

;; Memoized versions of API calls
(def ^{:private true} m-list-apis (memoize list-apis))
(def ^{:private true} m-build (memoize build))

(defn im
	"Call a service, constructing if necessary"
	([auth method_name & args]
		(let [
			service_name (first (clojure.string/split method_name #"[\.\/]"))
			api (last (filter #(= (first %1) service_name) (m-list-apis)))
			service (m-build (last api))]
			(apply call auth service method_name args))))

(defn anon-im
	"Call to a service without authentication"
	[method_name & args]
	(apply im nil method_name args))

(defn api-ns
	"Create a namespace for the API calls. TODO: details"
	([api_url]
		(api-ns nil api_url))
	([auth api_url]
		(let [	service (m-build api_url)
				build-fn (partial build-ns auth)]
			(map build-fn service))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;; HELPER METHODS ;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- build-ns
	"Create an entry in a namespace for the method"
	[auth [mname method]]
	(let [	name (str "gapi." mname)
			parts (clojure.string/split name #"[\.\/]")
			namespace (symbol (clojure.string/join "." (pop parts)))]
				(if (= nil (find-ns namespace)) (create-ns namespace))
				(intern namespace
					(with-meta (symbol (last parts)) {:doc (method :doc) :arglists (method :arglists)})
					(partial (method :fn) auth))
				name))

(defn- get-method-name
	"Get a friendly namespace-esque string for the method"
	[name]
	(let [parts (clojure.string/split name #"\.")]
		(str (clojure.string/join "." (pop parts)) "/" (last parts))))

(defn- match-params
	"Filter the parameters by a given function"
	[params func]
	(let [required (filter (fn [[k v]] (func v)) params)]
		(map (fn [[k v]] (name k)) required)))

(defn- get-path-params
	"Return a vector of parameter names which appear in the URL path"
	[params]
	(match-params params (fn [p] (= "path" (p :location)))))

(defn- get-required-params
	"Return a vector of required parameter names"
	[params]
	(match-params params (fn [p] (p :required))))

(defn- hasreqs?
	"Determine whether the required params are present in the arguments"
	[params args]
	(reduce #(and %1 %2) true (map #(contains? args %1) (get-required-params params))))

(defn- get-response
	"Check an HTTP response, JSON decoding the body if valud"
	[res]
	(if (= (res :status) 200)
		(json/read-json (res :body))
		(let [body (json/read-json (res :body))]
			{:error ((body :error) :message)})))

(defn- get-url
	"Replace URL path parameters with their values"
	[base_url path params args]
	(str base_url
		(reduce #(string/replace %1 (str "{" %2 "}") (url-encode (args %2))) path params)))

(defmulti #^{:private true} callfn
	"Retrieve an anonymous function that makes the proper call for the
	supplied method description."
	(fn [base_url method] (method :httpMethod)))

(defmethod callfn "GET" [base_url {path :path method_params :parameters}]
	(fn ([state args]
		{:pre [(hasreqs? method_params args)]}
		(get-response (http/get (get-url base_url path (get-path-params method_params) args)
			(auth/call-params state {:throw-exceptions false :query-params args}))))))

(defmethod callfn "POST" [base_url {path :path method_params :parameters}]
	(fn ([state args body]
		{:pre [(hasreqs? method_params args)]}
		(get-response (http/post (get-url base_url path (get-path-params method_params) args)
			(auth/call-params state {:throw-exceptions false :body (json/json-str body) :content-type :json :query-params args}))))))

(defmethod callfn "DELETE" [base_url {path :path method_params :parameters}]
	(fn ([state args body]
		{:pre [(hasreqs? method_params args)]}
		(get-response (http/delete (get-url base_url path (get-path-params method_params) args)
			(auth/call-params state {:throw-exceptions false :body (json/json-str body) :content-type :json :query-params args}))))))

(defmethod callfn "PUT" [base_url {path :path method_params :parameters}]
	(fn ([state args body]
		{:pre [(hasreqs? method_params args)]}
		(get-response (http/put (get-url base_url path (get-path-params method_params) args)
			(auth/call-params state {:throw-exceptions false :body (json/json-str body) :content-type :json :query-params args}))))))

(defmethod callfn "PATCH" [base_url {path :path method_params :parameters}]
	(fn ([state args body]
		{:pre [(hasreqs? method_params args)]}
		(get-response (http/patch (get-url base_url path (get-path-params method_params) args)
			(auth/call-params state {:throw-exceptions false :body (json/json-str body) :content-type :json :query-params args}))))))

(defn- docstring
	"Return a description for this method"
	[method]
	(str (method :description) "\n"
		"Required parameters: " (string/join " "(get-required-params (method :parameters)))
		"\n"))

(defn- arglists
	"Return an argument list for the method"
	[method]
	(let [base_args
			(if (= (method :description) "POST") '[[auth parameters body]] '[[auth parameters]])]
		base_args))

(defn- extract-methods
	"Retrieve all methods from the given resource"
	[base_url resource]
	(reduce
		(fn [methods [key method]]
			(assoc methods
				(get-method-name (method :id))
				{:fn (callfn base_url method)
				 :doc (docstring method)
				 :arglists (arglists method)
				 :scopes (method :scopes)}))
		{} (resource :methods)))
