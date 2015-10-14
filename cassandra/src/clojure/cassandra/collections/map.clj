(ns cassandra.collections.map
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
                                                NoHostAvailableException)))

(defrecord CQLMapClient [conn writec]
  client/Client
  (setup! [_ test node]
    (locking setup-lock
      (let [conn (cassandra/connect (->> test :nodes (map name)))]
        (cql/create-keyspace conn "jepsen_keyspace"
                             (if-not-exists)
                             (with {:replication
                                    {:class "SimpleStrategy"
                                     :replication_factor 3}}))
        (cql/use-keyspace conn "jepsen_keyspace")
        (cql/create-table conn "maps"
                          (if-not-exists)
                          (column-definitions {:id :int
                                               :elements (map-type :int :int)
                                               :primary-key [:id]})
                          (with {:compaction
                                 {:class (compaction-strategy)}}))
        (cql/insert conn "maps"
                    {:id 0
                     :elements {}})
        (->CQLMapClient conn writec))))
  (invoke! [this test op]
    (case (:f op)
      :add (try (with-consistency-level writec
                  (cql/update conn
                              "maps"
                              {:elements [+ {(:value op) (:value op)}]}
                              (where [[= :id 0]])))
                (assoc op :type :ok)
                (catch UnavailableException e
                  (assoc op :type :fail :value (.getMessage e)))
                (catch WriteTimeoutException e
                  (assoc op :type :info :value :timed-out))
                (catch NoHostAvailableException e
                  (info "All nodes are down - sleeping 2s")
                  (Thread/sleep 2000)
                  (assoc op :type :fail :value (.getMessage e))))
      :read (try (wait-for-recovery 30 conn)
                 (let [value (->> (with-retry-policy aggressive-read
                                    (with-consistency-level ConsistencyLevel/ALL
                                      (cql/select conn "maps"
                                                  (where [[= :id 0]]))))
                                  first
                                  :elements
                                  vals
                                  (into (sorted-set)))]
                   (assoc op :type :ok :value value))
                 (catch UnavailableException e
                   (info "Not enough replicas - failing")
                   (assoc op :type :fail :value (.getMessage e)))
                 (catch ReadTimeoutException e
                   (assoc op :type :fail :value :timed-out)))))
  (teardown! [_ _]
    (info "Tearing down client with conn" conn)
    (cassandra/disconnect! conn)))

(defn cql-map-client
  "A set implemented using CQL maps"
  ([] (->CQLMapClient nil ConsistencyLevel/ONE))
  ([writec] (->CQLMapClient nil writec)))

(defn cql-map-test
  [name opts]
  (merge (cassandra-test (str "cql map " name)
                         {:client (cql-map-client)
                          :model (model/set)
                          :generator (gen/phases
                                      (->> (adds)
                                           (gen/stagger 1/10)
                                           (gen/delay 1/2)
                                           std-gen)
                                      (read-once))
                          :checker (checker/compose
                                    {:set checker/set})})
         opts))

(def bridge-test
  (cql-map-test "bridge"
                {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))}}))

(def halves-test
  (cql-map-test "halves"
                {:conductors {:nemesis (nemesis/partition-random-halves)}}))

(def isolate-node-test
  (cql-map-test "isolate node"
                {:conductors {:nemesis (nemesis/partition-random-node)}}))

(def crash-subset-test
  (cql-map-test "crash"
                {:conductors {:nemesis (crash-nemesis)}}))

(def flush-compact-test
  (cql-map-test "flush and compact"
                {:conductors {:nemesis (conductors/flush-and-compacter)}}))

(def bridge-test-bootstrap
  (cql-map-test "bridge bootstrap"
                {:bootstrap (atom #{:n4 :n5})
                 :conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                              :bootstrapper (conductors/bootstrapper)}}))

(def halves-test-bootstrap
  (cql-map-test "halves bootstrap"
                {:bootstrap (atom #{:n4 :n5})
                 :conductors {:nemesis (nemesis/partition-random-halves)
                              :bootstrapper (conductors/bootstrapper)}}))

(def isolate-node-test-bootstrap
  (cql-map-test "isolate node bootstrap"
                {:bootstrap (atom #{:n4 :n5})
                 :conductors {:nemesis (nemesis/partition-random-node)
                              :bootstrapper (conductors/bootstrapper)}}))

(def crash-subset-test-bootstrap
  (cql-map-test "crash bootstrap"
                {:bootstrap (atom #{:n4 :n5})
                 :conductors {:nemesis (crash-nemesis)
                              :bootstrapper (conductors/bootstrapper)}}))

(def bridge-test-decommission
  (cql-map-test "bridge decommission"
                {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                              :decommissioner (conductors/decommissioner)}}))

(def halves-test-decommission
  (cql-map-test "halves decommission"
                {:conductors {:nemesis (nemesis/partition-random-halves)
                              :decommissioner (conductors/decommissioner)}}))

(def isolate-node-test-decommission
  (cql-map-test "isolate node decommission"
                {:conductors {:nemesis (nemesis/partition-random-node)
                              :decommissioner (conductors/decommissioner)}}))

(def crash-subset-test-decommission
  (cql-map-test "crash decommission"
                {:client (cql-map-client ConsistencyLevel/QUORUM)
                 :conductors {:nemesis (crash-nemesis)
                              :decommissioner (conductors/decommissioner)}}))
