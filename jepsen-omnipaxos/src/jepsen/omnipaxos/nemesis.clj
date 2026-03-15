(ns jepsen.omnipaxos.nemesis
  "Nemesis for OmniPaxos: network partitions and node crashes."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [control :as c]
                    [generator :as gen]
                    [nemesis :as nemesis]]
            [jepsen.control.util :as cu]
            [jepsen.omnipaxos.db :as db]))

(defn crash-nemesis
  "Kills server + api-shim on a random node, then restarts them on recover.
   Tracks the crashed node in an atom so recover knows where to go."
  []
  (let [crashed (atom nil)]
    (reify nemesis/Nemesis
      (setup! [this test] this)

      (invoke! [this test op]
        (case (:f op)
          :crash-node
          (let [node (rand-nth (:nodes test))]
            (info "nemesis crashing node" node)
            (c/on-nodes test [node]
              (fn [_ _]
                (cu/stop-daemon! db/shim-bin   db/shim-pid)
                (cu/stop-daemon! db/server-bin db/server-pid)))
            (reset! crashed node)
            (assoc op :type :info :value node))

          :recover-node
          (if-let [node @crashed]
            (do
              (info "nemesis recovering node" node)
              (c/on-nodes test [node]
                (fn [_ _]
                  (db/start-server!)
                  (Thread/sleep 3000)
                  (db/start-shim!)
                  (db/await-shim! node)))
              (reset! crashed nil)
              (assoc op :type :info :value node))
            (assoc op :type :info :value :nothing-to-recover))))

      (teardown! [this test]))))

(defn omnipaxos-nemesis
  "Combined nemesis: random-halves partition + single-node crash/restart."
  []
  (nemesis/compose
    {{:start-partition :start
      :stop-partition  :stop} (nemesis/partition-random-halves)
     #{:crash-node :recover-node} (crash-nemesis)}))

(defn nemesis-generator
  "Cycles through: partition → heal → crash → recover."
  []
  (gen/cycle
    [(gen/sleep 10)
     {:type :info :f :start-partition}
     (gen/sleep 15)
     {:type :info :f :stop-partition}
     (gen/sleep 5)
     {:type :info :f :crash-node}
     (gen/sleep 10)
     {:type :info :f :recover-node}
     (gen/sleep 5)]))
