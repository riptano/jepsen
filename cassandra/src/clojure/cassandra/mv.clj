(ns cassandra.mv
  (:require [clojure [pprint :refer :all]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen [core      :as jepsen]
             [codec     :as codec]
             [db        :as db]
             [util      :as util :refer [meh timeout]]
             [control   :as c :refer [| lit]]
             [client    :as client]
             [checker   :as checker]
             [model     :as model]
             [generator :as gen]
             [nemesis   :as nemesis]
             [store     :as store]
             [report    :as report]
             [tests     :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control [net :as net]
             [util :as net/util]]
            [jepsen.os.debian :as debian]
            [knossos.core :as knossos]
            [clojurewerkz.cassaforte.client :as cassandra]
            [clojurewerkz.cassaforte.query :refer :all]
            [clojurewerkz.cassaforte.policies :refer :all]
            [clojurewerkz.cassaforte.cql :as cql]
            [cassandra.core :refer :all]
            [cassandra.checker :as extra-checker]
            [cassandra.conductors :as conductors])
  (:import (clojure.lang ExceptionInfo)
           (com.datastax.driver.core ConsistencyLevel)
           (com.datastax.driver.core.policies FallthroughRetryPolicy
                                              RoundRobinPolicy
                                              WhiteListPolicy)
           (com.datastax.driver.core.exceptions UnavailableException
                                                WriteTimeoutException
                                                ReadTimeoutException
                                                NoHostAvailableException)
           (java.net InetSocketAddress)))

(defrecord MVMapClient [conn connect-type read-cl]
  client/Client
  (setup! [_ test node]
    (locking setup-lock
      (let [conn (cassandra/connect [(name node)]
                                    {:load-balancing-policy
                                     (WhiteListPolicy.
                                      (RoundRobinPolicy.)
                                      (map (fn [node-name]
                                             (InetSocketAddress. node-name 9042))
                                           (if (= connect-type :single)
                                             (do
                                               (info "load balancing only" node)
                                               [(name node)])
                                             (->> test :nodes (map name)))))})]
        (cql/create-keyspace conn "jepsen_keyspace"
                             (if-not-exists)
                             (with {:replication
                                    {:class "SimpleStrategy"
                                     :replication_factor 3}}))
        (cql/use-keyspace conn "jepsen_keyspace")
        (cql/create-table conn "map"
                          (if-not-exists)
                          (column-definitions {:key :int
                                               :value :int
                                               :primary-key [:key]})
                          (with {:compaction
                                 {:class (compaction-strategy)}}))
        (try (cassandra/execute conn (str "CREATE MATERIALIZED VIEW mvmap AS SELECT"
                                          " * FROM map WHERE value IS NOT NULL"
                                          " AND key IS NOT NULL "
                                          "PRIMARY KEY (value, key)"
                                          "WITH compaction = "
                                          "{'class' : '" (compaction-strategy)
                                          "'};"))
             (catch com.datastax.driver.core.exceptions.AlreadyExistsException e))
        (->MVMapClient conn connect-type read-cl))))
  (invoke! [this test op]
    (case (:f op)
      :assoc (try (with-retry-policy FallthroughRetryPolicy/INSTANCE
                    (with-consistency-level ConsistencyLevel/ONE
                      (cql/update conn
                                  "map"
                                  {:value (:v (:value op))}
                                  (where [[= :key (:k (:value op))]]))))
                  (assoc op :type :ok)
                  (catch UnavailableException e
                    (assoc op :type :fail :value (.getMessage e)))
                  (catch WriteTimeoutException e
                    (assoc op :type :info :value :timed-out))
                  (catch NoHostAvailableException e
                    (info "All nodes are down - sleeping 2s")
                    (Thread/sleep 2000)
                    (assoc op :type :fail :value (.getMessage e))))
      :read (try (let [value (->> (with-consistency-level read-cl
                                    (cql/select conn "mvmap"))
                                  (#(zipmap (map :key %) (map :value %))))]
                   (assoc op :type :ok :value value))
                 (catch UnavailableException e
                   (info "Not enough replicas - failing")
                   (assoc op :type :fail :value (.getMessage e)))
                 (catch ReadTimeoutException e
                   (assoc op :type :fail :value :timed-out))
                 (catch NoHostAvailableException e
                   (info "All nodes are down - sleeping 2s")
                   (Thread/sleep 2000)
                   (assoc op :type :fail :value (.getMessage e))))))
  (teardown! [_ _]
    (info "Tearing down client with conn" conn)
    (cassandra/disconnect! conn)))

(defn mv-map-client
  "A map implemented using MV"
  ([]
   (->MVMapClient nil :single ConsistencyLevel/ALL))
  ([load-balancing-policy]
   (->MVMapClient nil load-balancing-policy ConsistencyLevel/ALL))
  ([load-balancing-policy read-cl]
   (->MVMapClient nil load-balancing-policy read-cl)))

(defn mv-map-test
  [name opts]
  (merge (cassandra-test (str "mv contended map " name)
                         {:client (mv-map-client)
                          :generator (gen/phases
                                      (->> (gen/clients (assocs identity))
                                           (gen/delay 1)
                                           (std-gen 250))
                                      (gen/conductor :replayer
                                                     (gen/once {:type :info :f :replay}))
                                      (read-once)
                                      (->> (gen/clients (assocs -))
                                           (gen/delay 1)
                                           (std-gen 250))
                                      (gen/conductor :replayer
                                                     (gen/once {:type :info :f :replay}))
                                      (read-once))
                          :checker (checker/compose
                                    {:map checker/associative-map})})
         (merge-with merge {:conductors {:replayer (conductors/replayer)}} opts)))

(defrecord ConsistencyDelayClient [conn node writes]
  client/Client
  (setup! [_ test node]
    (locking setup-lock
      (let [conn (cassandra/connect [(name node)]
                                    {:load-balancing-policy
                                     (WhiteListPolicy.
                                      (RoundRobinPolicy.)
                                      [(InetSocketAddress. (name node) 9042)])})]
        (cql/create-keyspace conn "jepsen_keyspace"
                             (if-not-exists)
                             (with {:replication
                                    {:class "SimpleStrategy"
                                     :replication_factor 3}}))
        (cql/use-keyspace conn "jepsen_keyspace")
        (cql/create-table conn "map"
                          (if-not-exists)
                          (column-definitions {:key :int
                                               :value :int
                                               :primary-key [:key]})
                          (with {:compaction
                                 {:class (compaction-strategy)}}))
        (try (cassandra/execute conn (str "CREATE MATERIALIZED VIEW mvmap AS SELECT"
                                          " * FROM map WHERE value IS NOT NULL"
                                          " AND key IS NOT NULL "
                                          "PRIMARY KEY (value, key)"
                                          "WITH compaction = "
                                          "{'class' : '" (compaction-strategy)
                                          "'} and read_repair_chance = 0;"))
             (catch com.datastax.driver.core.exceptions.AlreadyExistsException e))
        (->ConsistencyDelayClient conn node writes))))
  (invoke! [this test op]
    (case (:f op)
      :assoc (try (with-retry-policy FallthroughRetryPolicy/INSTANCE
                    (with-consistency-level ConsistencyLevel/ONE
                      (cql/update conn
                                  "map"
                                  {:value (:v (:value op))}
                                  (where [[= :key (:k (:value op))]]))))
                  (doseq [host (str/split-lines
                                 (nodetool node "getendpoints" "jepsen_keyspace"
                                           "mvmap" (str (:v (:value op)))))]
                    (swap! writes update-in [host] conj (:k (:value op))))
                  (assoc op :type :ok)
                  (catch UnavailableException e
                    (assoc op :type :fail :value (.getMessage e)))
                  (catch WriteTimeoutException e
                    (assoc op :type :fail :value :timed-out))
                  (catch NoHostAvailableException e
                    (info "All nodes are down - sleeping 2s")
                    (Thread/sleep 2000)
                    (assoc op :type :fail :value (.getMessage e))))
      :read (try (let [value (->> (with-retry-policy FallthroughRetryPolicy/INSTANCE
                                    (with-consistency-level ConsistencyLevel/ONE
                                      (cql/select conn "mvmap"
                                                  (where [[:in :value (get @writes (dns-resolve node))]]))))
                                  (#(zipmap (map :key %) (map :value %))))]
                   (info (util/linear-time-nanos))
                   (assoc op :type :ok :node node :value value))
                 (catch UnavailableException e
                   (info "Not enough replicas - failing")
                   (assoc op :type :fail :value (.getMessage e)))
                 (catch ReadTimeoutException e
                   (assoc op :type :fail :value :timed-out))
                 (catch NoHostAvailableException e
                   (info "All nodes are down - sleeping 2s")
                   (Thread/sleep 2000)
                   (assoc op :type :fail :value (.getMessage e))))))
  (teardown! [_ _]
    (info "Tearing down client with conn" conn)
    (cassandra/disconnect! conn)))

(defn consistency-delay-client
  []
  (->ConsistencyDelayClient nil nil (atom {})))

(def paired-nodes
  (comp nemesis/complete-grudge (partial partition-all 2)))

(def isolate-all
  (comp nemesis/complete-grudge (partial map vector)))

;; EC convergence test
(def delay-test
  (cassandra-test (str "consistency delay")
                  {:client (consistency-delay-client)
                   :generator (gen/phases
                               (gen/nemesis (gen/once {:type :info :f :start :grudge :pair}))
                               (gen/sleep 25)
                               (->> (gen/clients (assocs identity))
                                    (gen/time-limit 200))
                               (gen/nemesis (gen/once {:type :info :f :stop}))
                               (->> (fn [] [
                                            (gen/delay 3 (gen/nemesis (gen/once {:type :info :f :start
                                                                                 :grudge :complete})))
                                            (gen/concat (gen/on (partial = 0)
                                                                (gen/once {:type :invoke :f :read}))
                                                        (gen/on (partial = 1)
                                                                (gen/once {:type :invoke :f :read}))
                                                        (gen/on (partial = 2)
                                                                (gen/once {:type :invoke :f :read}))
                                                        (gen/on (partial = 3)
                                                                (gen/once {:type :invoke :f :read}))
                                                        (gen/on (partial = 4)
                                                                (gen/once {:type :invoke :f :read})))
                                            (gen/nemesis (gen/once {:type :info :f :stop}))])
                                    (repeatedly 7)
                                    (apply concat)
                                    (apply gen/phases)))
                   :conductors {:nemesis (conductors/flexible-partitioner {:pair paired-nodes
                                                                           :complete isolate-all})}
                   :checker (checker/compose
                             {:map (checker/latency-graph-no-quantiles
                                    (extra-checker/ec-history->latencies 3))})}))

;; Uncontended tests
(def bridge-test
  (mv-map-test "bridge"
               {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))}}))

(def halves-test
  (mv-map-test "halves"
               {:conductors {:nemesis (nemesis/partition-random-halves)}}))

(def isolate-node-test
  (mv-map-test "isolate node"
               {:conductors {:nemesis (nemesis/partition-random-node)}}))

(def crash-subset-test
  (mv-map-test "crash"
               {:conductors {:nemesis (crash-nemesis)}}))

(def clock-drift-test
  (mv-map-test "clock drift"
               {:conductors {:nemesis (nemesis/clock-scrambler 10000)}}))

(def flush-compact-test
  (mv-map-test "flush and compact"
               {:conductors {:nemesis (conductors/flush-and-compacter)}}))

(def bridge-test-bootstrap
  (mv-map-test "bridge bootstrap"
               {:bootstrap (atom #{:n4 :n5})
                :conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                             :bootstrapper (conductors/bootstrapper)}}))

(def halves-test-bootstrap
  (mv-map-test "halves bootstrap"
               {:bootstrap (atom #{:n4 :n5})
                :conductors {:nemesis (nemesis/partition-random-halves)
                             :bootstrapper (conductors/bootstrapper)}}))

(def isolate-node-test-bootstrap
  (mv-map-test "isolate node bootstrap"
               {:bootstrap (atom #{:n4 :n5})
                :conductors {:nemesis (nemesis/partition-random-node)
                             :bootstrapper (conductors/bootstrapper)}}))

(def crash-subset-test-bootstrap
  (mv-map-test "crash bootstrap"
               {:bootstrap (atom #{:n4 :n5})
                :conductors {:nemesis (crash-nemesis)
                             :bootstrapper (conductors/bootstrapper)}}))

(def clock-drift-test-bootstrap
  (mv-map-test "clock drift bootstrap"
               {:bootstrap (atom #{:n4 :n5})
                :conductors {:nemesis (nemesis/clock-scrambler 10000)
                             :bootstrapper (conductors/bootstrapper)}}))

(def bridge-test-decommission
  (mv-map-test "bridge decommission"
               {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                             :decommissioner (conductors/decommissioner)}}))

(def halves-test-decommission
  (mv-map-test "halves decommission"
               {:conductors {:nemesis (nemesis/partition-random-halves)
                             :decommissioner (conductors/decommissioner)}}))

(def isolate-node-test-decommission
  (mv-map-test "isolate node decommission"
               {:conductors {:nemesis (nemesis/partition-random-node)
                             :decommissioner (conductors/decommissioner)}}))

(def crash-subset-test-decommission
  (mv-map-test "crash decommission"
               {:conductors {:nemesis (crash-nemesis)
                             :decommissioner (conductors/decommissioner)}}))

(def clock-drift-test-decommission
  (mv-map-test "clock drift decommission"
               {:conductors {:nemesis (nemesis/clock-scrambler 10000)
                             :decommissioner (conductors/decommissioner)}}))
