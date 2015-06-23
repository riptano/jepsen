(ns cassandra.lwt
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
            [cassandra.checker :as extra-checker])
  (:import (clojure.lang ExceptionInfo)
           (com.datastax.driver.core ConsistencyLevel)
           (com.datastax.driver.core.exceptions UnavailableException
                                                WriteTimeoutException
                                                ReadTimeoutException
                                                NoHostAvailableException)))

(def ak (keyword "[applied]")) ;this is the name C* returns, define now because
                               ;it isn't really a valid keyword from reader's
                               ;perspective

(defrecord CasRegisterClient [conn]
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
        (cql/create-table conn "lwt"
                          (if-not-exists)
                          (column-definitions {:id :int
                                               :value :int
                                               :primary-key [:id]}))
        (->CasRegisterClient conn))))
  (invoke! [this test op]
    (case (:f op)
      :cas (try (let [[v v'] (:value op)
                      result (cql/update conn "lwt" {:value v'}
                                         (only-if [[= :value v]])
                                         (where [[= :id 0]]))]
                  (if (-> result first ak)
                    (assoc op :type :ok)
                    (assoc op :type :fail :value (-> result first :value))))
                (catch UnavailableException e
                  (assoc op :type :fail :error (.getMessage e)))
                (catch ReadTimeoutException e
                  (assoc op :type :info :value :read-timed-out))
                (catch WriteTimeoutException e
                  (assoc op :type :info :value :write-timed-out))
                (catch NoHostAvailableException e
                  (info "All the servers are down - waiting 2s")
                  (Thread/sleep 2000)
                  (assoc op :type :fail :error (.getMessage e))))
      :write (try (let [v' (:value op)
                        result (cql/update conn
                                           "lwt"
                                           {:value v'}
                                           (only-if [[:in :value (range 5)]])
                                           (where [[= :id 0]]))]
                    (if (-> result first ak)
                      (assoc op :type :ok)
                      (let [result' (cql/insert conn "lwt" {:id 0
                                                            :value v'}
                                                (if-not-exists))]
                        (if (-> result' first ak)
                          (assoc op :type :ok)
                          (assoc op :type :fail)))))
                  (catch UnavailableException e
                    (assoc op :type :fail :error (.getMessage e)))
                  (catch ReadTimeoutException e
                    (assoc op :type :info :value :read-timed-out))
                  (catch WriteTimeoutException e
                    (assoc op :type :info :value :write-timed-out))
                  (catch NoHostAvailableException e
                    (info "All the servers are down - waiting 2s")
                    (Thread/sleep 2000)
                    (assoc op :type :fail :error (.getMessage e))))
      :read (try (let [value (->> (with-consistency-level ConsistencyLevel/SERIAL
                                    (cql/select conn "lwt"
                                                (where [[= :id 0]])))
                                  first :value)]
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
    (cassandra/disconnect! conn)))

(defn cas-register-client
  "A CAS register implemented using LWT"
  []
  (->CasRegisterClient nil))

(defn cas-register-test
  [name opts]
  (merge (cassandra-test (str "lwt register " name)
                         {:client (cas-register-client)
                          :model (model/cas-register)
                          :generator (gen/phases
                                      (->> [r w cas cas cas]
                                           gen/mix
                                           (gen/stagger 1/10)
                                           (gen/delay 1)
                                           (gen/nemesis
                                            (gen/seq (cycle
                                                      [(gen/sleep 5)
                                                       {:type :info :f :stop}
                                                       (gen/sleep 15)
                                                       {:type :info :f :start}
                                                       ])))
                                           (gen/time-limit 50))
                                      gen/void)
                          :checker (checker/compose
                                    {:linear extra-checker/enhanced-linearizable})})
         opts))

(def bridge-test
  (cas-register-test "bridge"
                     {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))}}))

(def halves-test
  (cas-register-test "halves"
                     {:conductors {:nemesis (nemesis/partition-random-halves)}}))

(def isolate-node-test
  (cas-register-test "isolate node"
                     {:conductors {:nemesis (nemesis/partition-random-node)}}))

(def crash-subset-test
  (cas-register-test "crash"
                     {:conductors {:nemesis crash-nemesis}}))
