(ns jepsen.omnipaxos.core
  "Jepsen test suite for OmniPaxos KV store.

  Run with:
    lein run -- --nodes n1,n2,n3 --time-limit 60
      --server-bin /path/to/server --shim-bin /path/to/api-shim"
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [generator :as gen]
                    [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.omnipaxos.client :refer [omnipaxos-client]]
            [jepsen.omnipaxos.db :refer [omnipaxos-db]]
            [knossos.model :as model]))

; Use a small fixed key space so reads and writes contend on the same keys,
; giving Knossos enough overlap to detect linearizability violations.
(def key-count 1)

(defn w [_ _]
  {:type :invoke, :f :write, :key (str "k" (rand-int key-count)), :value (rand-int 100)})

(defn r [_ _]
  {:type :invoke, :f :read, :key (str "k" (rand-int key-count))})

(defn omnipaxos-test
  [opts]
  (merge tests/noop-test
         {:name      "omnipaxos-kv"
          :nodes     (:nodes opts)
          :db        (omnipaxos-db (:server-bin opts) (:shim-bin opts))
          :client    (omnipaxos-client)
          :checker   (checker/compose
                       {:linearizable (checker/linearizable
                                        {:model (model/register)})
                        :timeline     (timeline/html)})
          :generator (gen/phases
                       (->> (gen/mix [r w])
                            (gen/stagger 1/10)
                            (gen/clients)
                            (gen/time-limit (:time-limit opts 60)))
                       (gen/log "Test complete, waiting before final check")
                       (gen/sleep 5))}))

(def cli-opts
  "Additional CLI options beyond Jepsen defaults."
  [[nil "--key-count NUM" "Number of keys to operate on"
    :default  3
    :parse-fn parse-long
    :validate [pos? "Must be positive"]]
   [nil "--server-bin PATH" "Local path to the pre-built OmniPaxos server binary"
    :default "server"]
   [nil "--shim-bin PATH" "Local path to the pre-built api-shim binary"
    :default "api-shim"]])

(defn -main
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn   omnipaxos-test
                                  :opt-spec  cli-opts})
            args))
