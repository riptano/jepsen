(ns cassandra.cvh-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [cassandra.core :as core]
            [cassandra.core-test :refer :all]
            [jepsen [core :as jepsen]
             [client :as client]
             [generator :as gen]
             [report :as report]])
  (:import cassandra.cvh.SimpleWriteModule))

(deftest cvh-basic
  (run-test! (core/cassandra-test
              (str "cvh test")
              {:nodes [:n1]
               :client (SimpleWriteModule.)
               :generator (gen/once {:type :invoke :f :write})})))
