(ns cassandra.counter
  (:require [clojure [pprint :refer :all]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen [core      :as jepsen]
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
            [cassandra.conductors :as conductors])
  (:import (clojure.lang ExceptionInfo)
           (com.datastax.driver.core ConsistencyLevel)
           (com.datastax.driver.core.exceptions UnavailableException
                                                WriteTimeoutException
                                                ReadTimeoutException
                                                NoHostAvailableException)
           (com.datastax.driver.core.policies FallthroughRetryPolicy
                                              ConstantSpeculativeExecutionPolicy)))

(defrecord CQLCounterClient [conn writec]
  client/Client
  (setup! [_ test node]
    (locking setup-lock
      (let [conn (cassandra/connect
                  (->> test :nodes (map name))
                  ;{:speculative-execution-policy (ConstantSpeculativeExecutionPolicy. 100000000 1)}
                  )]
        (cql/create-keyspace conn "jepsen_keyspace"
                             (if-not-exists)
                             (with {:replication
                                    {:class "SimpleStrategy"
                                     :replication_factor 3}}))
        (cql/use-keyspace conn "jepsen_keyspace")
        (cql/create-table conn "counters"
                          (if-not-exists)
                          (column-definitions {:id :int
                                               :count :counter
                                               :primary-key [:id]})
                          (with {:compaction
                                 {:class (compaction-strategy)}}))
        (cql/update conn {:enable-tracing false} "counters" {:count (increment-by 0)}
                    (where [[= :id 0]]))
        (info "Speculative executions" (.getCount (.getSpeculativeExecutions (.getErrorMetrics (.getMetrics (.getCluster conn))))))
        (->CQLCounterClient conn writec))))
  (invoke! [this test op]
    (case (:f op)
      :add (try (do
                  (with-retry-policy FallthroughRetryPolicy/INSTANCE
                    (with-consistency-level writec
                      (cql/update conn {:enable-tracing true}
                                  "counters"
                                  {:count (increment-by (:value op))}
                                  (where [[= :id 0]]))))
                  (assoc op :type :ok))
                (catch UnavailableException e
                  (assoc op :type :fail :error (.getMessage e)))
                (catch WriteTimeoutException e
                  (assoc op :type :info :value :timed-out))
                (catch NoHostAvailableException e
                  (info "All the servers are down - waiting 2s")
                  (Thread/sleep 2000)
                  (assoc op :type :fail :error (.getMessage e))))
      :read (try (let [value (->> (with-retry-policy FallthroughRetryPolicy/INSTANCE
                                    (with-consistency-level ConsistencyLevel/ALL
                                      (cql/select conn {:enable-tracing true}
                                                  "counters"
                                                  (where [[= :id 0]]))))
                                  first
                                  :count)]
                   (assoc op :type :ok :value value))
                 (catch UnavailableException e
                   (info "Not enough replicas - failing")
                   (assoc op :type :fail :value (.getMessage e)))
                 (catch ReadTimeoutException e
                   (assoc op :type :fail :value :timed-out))
                 (catch NoHostAvailableException e
                   (info "All the servers are down - waiting 2s")
                   (Thread/sleep 2000)
                   (assoc op :type :fail :error (.getMessage e))))))
  (teardown! [_ _]
    (info "Tearing down client with conn" conn)
    (info "Speculative executions" (.getCount (.getSpeculativeExecutions (.getErrorMetrics (.getMetrics (.getCluster conn))))))
    (info "Total" (.getCount (.getRequestsTimer (.getMetrics (.getCluster conn)))))
    (cassandra/disconnect! conn)))

(defn cql-counter-client
  "A counter implemented using CQL counters"
  ([] (->CQLCounterClient nil ConsistencyLevel/ONE))
  ([writec] (->CQLCounterClient nil writec)))

(defn cql-counter-inc-test
  [name opts]
  (merge (cassandra-test (str "cql counter inc " name)
                         {:client (cql-counter-client)
                          :model nil
                          :generator (->> (repeat 100 add)
                                          (cons r)
                                          gen/mix
                                          (gen/delay 1/10)
                                          std-gen)
                          :checker (checker/compose
                                    {:counter checker/counter})})
         opts))

(defn cql-counter-inc-dec-test
  [name opts]
  (merge (cassandra-test (str "cql counter inc dec " name)
                         {:client (cql-counter-client)
                          :model nil
                          :generator (->> (take 100 (cycle [add sub]))
                                          (cons r)
                                          gen/mix
                                          (gen/delay 1/10)
                                          std-gen)
                          :checker (checker/compose
                                    {:counter checker/counter})})
         opts))

(def bridge-inc-test
  (cql-counter-inc-test "bridge"
                        {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))}}))

(def halves-inc-test
  (cql-counter-inc-test "halves"
                        {:conductors {:nemesis (nemesis/partition-random-halves)}}))

(def isolate-node-inc-test
  (cql-counter-inc-test "isolate node"
                        {:conductors {:nemesis (nemesis/partition-random-node)}}))

(def flush-compact-inc-test
  (cql-counter-inc-test "flush and compact"
                  {:conductors {:nemesis (conductors/flush-and-compacter)}}))

(def crash-subset-inc-test
  (cql-counter-inc-test "crash"
                        {:conductors {:nemesis (crash-nemesis)}}))

(def bridge-inc-dec-test
  (cql-counter-inc-dec-test "bridge"
                            {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))}}))

(def halves-inc-dec-test
  (cql-counter-inc-dec-test "halves"
                            {:conductors {:nemesis (nemesis/partition-random-halves)}}))

(def isolate-node-inc-dec-test
  (cql-counter-inc-dec-test "isolate node"
                            {:conductors {:nemesis (nemesis/partition-random-node)}}))

(def crash-subset-inc-dec-test
  (cql-counter-inc-dec-test "crash"
                            {:conductors {:nemesis (crash-nemesis)}}))

(def flush-compact-inc-dec-test
  (cql-counter-inc-dec-test "flush and compact"
                            {:conductors {:nemesis (conductors/flush-and-compacter)}}))

(def bridge-inc-test-bootstrap
  (cql-counter-inc-test "bridge bootstrap"
                        {:bootstrap (atom #{:n4 :n5})
                         :conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                                      :bootstrapper (conductors/bootstrapper)}}))

(def halves-inc-test-bootstrap
  (cql-counter-inc-test "halves bootstrap"
                        {:bootstrap (atom #{:n4 :n5})
                         :conductors {:nemesis (nemesis/partition-random-halves)
                                      :bootstrapper (conductors/bootstrapper)}}))

(def isolate-node-inc-test-bootstrap
  (cql-counter-inc-test "isolate node bootstrap"
                        {:bootstrap (atom #{:n4 :n5})
                         :conductors {:nemesis (nemesis/partition-random-node)
                                      :bootstrapper (conductors/bootstrapper)}}))

(def crash-subset-inc-test-bootstrap
  (cql-counter-inc-test "crash bootstrap"
                        {:bootstrap (atom #{:n4 :n5})
                         :conductors {:nemesis (crash-nemesis)
                                      :bootstrapper (conductors/bootstrapper)}}))

(def bridge-inc-dec-test-bootstrap
  (cql-counter-inc-dec-test "bridge bootstrap"
                            {:bootstrap (atom #{:n4 :n5})
                             :conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                                          :bootstrapper (conductors/bootstrapper)}}))

(def halves-inc-dec-test-bootstrap
  (cql-counter-inc-dec-test "halves bootstrap"
                            {:bootstrap (atom #{:n4 :n5})
                             :conductors {:nemesis (nemesis/partition-random-halves)
                                          :bootstrapper (conductors/bootstrapper)}}))

(def isolate-node-inc-dec-test-bootstrap
  (cql-counter-inc-dec-test "isolate node bootstrap"
                            {:bootstrap (atom #{:n4 :n5})
                             :conductors {:nemesis (nemesis/partition-random-node)
                                          :bootstrapper (conductors/bootstrapper)}}))

(def crash-subset-inc-dec-test-bootstrap
  (cql-counter-inc-dec-test "crash bootstrap"
                            {:bootstrap (atom #{:n4 :n5})
                             :conductors {:nemesis (crash-nemesis)
                                          :bootstrapper (conductors/bootstrapper)}}))

(def bridge-inc-test-decommission
  (cql-counter-inc-test "bridge decommission"
                        {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                                      :bootstrapper (conductors/bootstrapper)}}))

(def halves-inc-test-decommission
  (cql-counter-inc-test "halves decommission"
                        {:conductors {:nemesis (nemesis/partition-random-halves)
                                      :decommissioner (conductors/decommissioner)}}))

(def isolate-node-inc-test-decommission
  (cql-counter-inc-test "isolate node decommission"
                        {:conductors {:nemesis (nemesis/partition-random-node)
                                      :decommissioner (conductors/decommissioner)}}))

(def crash-subset-inc-test-decommission
  (cql-counter-inc-test "crash decommission"
                        {:client (cql-counter-client ConsistencyLevel/QUORUM)
                         :conductors {:nemesis (crash-nemesis)
                                      :decommissioner (conductors/decommissioner)}}))

(def bridge-inc-dec-test-decommission
  (cql-counter-inc-dec-test "bridge decommission"
                            {:conductors {:nemesis (nemesis/partitioner
                                                    (comp nemesis/bridge shuffle))
                                          :decommissioner (conductors/decommissioner)}}))

(def halves-inc-dec-test-decommission
  (cql-counter-inc-dec-test "halves decommission"
                            {:conductors {:nemesis (nemesis/partition-random-halves)
                                          :decommissioner (conductors/decommissioner)}}))

(def isolate-node-inc-dec-test-decommission
  (cql-counter-inc-dec-test "isolate node decommission"
                            {:conductors {:nemesis (nemesis/partition-random-node)
                                          :decommissioner (conductors/decommissioner)}}))

(def crash-subset-inc-dec-test-decommission
  (cql-counter-inc-dec-test "crash decommission"
                            {:client (cql-counter-client ConsistencyLevel/QUORUM)
                             :conductors {:nemesis (crash-nemesis)
                                          :decommissioner (conductors/decommissioner)}}))
