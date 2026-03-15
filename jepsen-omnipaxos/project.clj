(defproject jepsen.omnipaxos "0.1.0"
  :description "Jepsen test for OmniPaxos KV store"
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [jepsen "0.3.11-SNAPSHOT"]
                 [clj-http "3.13.0"]]
  :main jepsen.omnipaxos.core
  :jvm-opts ["-Xmx4g" "-server"])
