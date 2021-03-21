(ns humboiserver.core
  (:gen-class)
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.pprint :as pp]
   [cognitect.anomalies :as anomalies]
   [datomic.client.api :as d]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def retryable-anomaly?
  "Set of retryable anomalies."
  #{::anomalies/busy
    ::anomalies/unavailable
    ::anomalies/interrupted})

(defn with-retry
  "Try op, return result if successful, if op throws, check exception against retry? pred,
  retrying if the predicate returns true. Optional backoff function controls retry wait. Backoff
  function should return msec backoff desired or nil to stop retry.
  Defaults to trying 10 times with linear backoff. "
  [op & {:keys [retry? backoff]
         :or {retry? (fn [e]
                       (-> e ex-data ::anomalies/category retryable-anomaly?))
              backoff (fn [epoch]
                        (when (<= epoch 10)
                          (* 200 epoch)))}}]
  (loop [epoch 1]
    (let [[success val] (try [true (op)]
                             (catch Exception e
                               [false e]))]
      (if success
        val
        (if-let [ms (and (retry? val) (backoff epoch))]
          (do
            (Thread/sleep ms)
            (recur (inc epoch)))
          (throw val))))))

(defn- anom-map
  "Helper for anomaly!"
  [category msg]
  {::anomalies/category (keyword "cognitect.anomalies" (name category))
   ::anomalies/message msg})

(defn anomaly!
  ([name msg]
   (throw (ex-info msg (anom-map name msg))))
  ([name msg cause]
   (throw (ex-info msg (anom-map name msg) cause))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; inventory 
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def colors [:red :green :blue :yellow])
(def sizes [:small :medium :large :xlarge])
(def types [:shirt :pants :dress :hat])

(defn make-idents
  [x]
  (mapv #(hash-map :db/ident %) x))

(def schema-1
  [{:db/ident :inv/sku
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :inv/color
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :inv/size
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :inv/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(def sample-data
  (->> (for [color colors size sizes type types]
         {:inv/color color
          :inv/size size
          :inv/type type})
       (map-indexed
        (fn [idx map]
          (assoc map :inv/sku (str "SKU-" idx))))))

(def order-schema
  [{:db/ident :order/items
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/isComponent true}
   {:db/ident :item/id
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :item/count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(def add-order
  {:order/items
   [{:item/id [:inv/sku "SKU-25"]
     :item/count 10}
    {:item/id [:inv/sku "SKU-26"]
     :item/count 20}]})

(def inventory-counts
  [{:db/ident :inv/count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(def inventory-update
  [[:db/add [:inv/sku "SKU-21"] :inv/count 7]
   [:db/add [:inv/sku "SKU-22"] :inv/count 7]
   [:db/add [:inv/sku "SKU-42"] :inv/count 100]])

(defn- has-ident?
  [db ident]
  (contains? (d/pull db {:eid ident :selector [:db/ident]})
             :db/ident))

(defn- data-loaded?
  [db]
  (has-ident? db :inv/sku))

(defn load-dataset
  [conn]
  (let [db (d/db conn)]
    (if (data-loaded? db)
      :already-loaded
      (let [xact #(d/transact conn {:tx-data %})]
        (xact (make-idents colors))
        (xact (make-idents sizes))
        (xact (make-idents types))
        (xact schema-1)
        (xact sample-data)
        (xact order-schema)
        (xact [add-order])
        (xact inventory-counts)
        (xact inventory-update)
        (xact [[:db/retract [:inv/sku "SKU-22"] :inv/count 7]
               [:db/add "datomic.tx" :db/doc "remove incorrect assertion"]])
        (xact [[:db/add [:inv/sku "SKU-42"] :inv/count 1000]
               [:db/add "datomic.tx" :db/doc "correct data entry error"]])
        :loaded))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                                        ; Connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def get-client
  (memoize #(if-let [r (io/resource "client-config.edn")]
              (d/client (edn/read-string (slurp r)))
              (throw (RuntimeException. "You need to add a resource client-config.edn with your connection config")))))

(def database-name "datomic-docs-tutorial")

(defn get-connection
  "Get shared connection."
  []
  (with-retry #(d/connect (get-client) {:db-name database-name})))

(defn- ensure-dataset
  "Ensure that a database named db-name exists, running setup-fn
  against the shared connection. Returns result of setup-fn"
  [db-name setup-sym]
  (require (symbol (namespace setup-sym)))
  (let [setup-var (resolve setup-sym)
        client (get-client)]
    (when-not setup-var
      (anomaly! :not-found (str "Could not resolve " setup-sym)))
    (let [conn (get-connection)]
      (setup-var conn))))

(defn ensure-sample-dataset
  "Creates db (if necessary) and transacts sample data."
  []
  (with-retry #(ensure-dataset database-name 'humboiserver.core/load-dataset)))

(defn get-db
  "Returns current db value from shared connection."
  []
  (d/db (get-connection)))

(defn get-schema
  "Returns a data representation of db schema."
  [db]
  (->> (d/pull db '{:eid 0 :selector [{:db.install/attribute [*]}]})
       :db.install/attribute
       (remove (fn [m] (str/starts-with? (namespace (:db/ident m)) "db")))
       (map #(update % :db/valueType :db/ident))
       (map #(update % :db/cardinality :db/ident))))

(defn get-items-by-type
  "Returns pull maps describing all items matching type"
  [db type pull-expr]
  (d/q '[:find (pull ?e pull-expr)
         :in $ ?type pull-expr
         :where [?e :inv/type ?type]]
       db type pull-expr))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (ensure-sample-dataset)
  (let [db (get-db)]
    (pp/pprint (get-schema db))
    (pp/pprint (get-items-by-type db :shirt '[*])))
  (println "Hello, World!"))
