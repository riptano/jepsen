(defproject cassandra "0.1.0-SNAPSHOT"
  :description "Jepsen testing for Cassandra"
  :url "http://github.com/riptano/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/java.jmx "0.3.1"]
                 [jkni/jepsen "0.0.5-SNAPSHOT"]
                 [clojurewerkz/cassaforte "2.1.0-beta1"]
                 [com.codahale.metrics/metrics-core "3.0.2"]
                 [circleci/clj-yaml "0.5.4"]]
  :profiles {:dev {:plugins [[test2junit "1.1.1"]]}
             :trunk {:dependencies [[clojurewerkz/cassaforte "trunk-SNAPSHOT"]]}}
  :test-selectors {:steady :steady
                   :bootstrap :bootstrap
                   :map :map
                   :set :set
                   :mv :mv
                   :batch :batch
                   :lwt :lwt
                   :decommission :decommission
                   :counter :counter
                   :clock :clock
                   :all (constantly true)}
  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  :aot [jepsen.client]
  :prep-tasks [["compile" "jepsen.client"]
               "javac" "compile"])
