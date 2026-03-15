(ns jepsen.omnipaxos.db
  "Deploys OmniPaxos server + api-shim onto each Jepsen node via SSH."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info]]
            [jepsen [db :as db]
                    [control :as c]]
            [jepsen.control.util :as cu]))

;;; Remote paths

(def dir        "/opt/omnipaxos")
(def server-bin (str dir "/server"))
(def shim-bin   (str dir "/api-shim"))
(def server-pid (str dir "/server.pid"))
(def shim-pid   (str dir "/shim.pid"))
(def server-log (str dir "/server.log"))
(def shim-log   (str dir "/shim.log"))

;;; Node ID mapping

(defn node->id
  "Maps n1 -> 1, n2 -> 2, etc."
  [node]
  (Integer/parseInt (re-find #"\d+" (name node))))

;;; Config file generators

(defn cluster-toml [nodes]
  (let [ids   (map node->id nodes)
        addrs (map #(str (name %) ":8000") nodes)]
    (str/join "\n"
      ["cluster_name = \"jepsen\""
       (str "nodes = [" (str/join ", " ids) "]")
       (str "node_addrs = [" (str/join ", " (map #(str "\"" % "\"") addrs)) "]")
       "initial_leader = 1"
       "[initial_flexible_quorum]"
       "read_quorum_size = 2"
       "write_quorum_size = 2"
       ""])))

(defn server-toml [node]
  (let [id (node->id node)]
    (str/join "\n"
      [(str "location = \"" (name node) "\"")
       (str "server_id = " id)
       "listen_address = \"0.0.0.0\""
       "listen_port = 8000"
       "num_clients = 1"
       (str "output_filepath = \"" dir "/server-" id ".json\"")
       ""])))

(defn shim-toml [node]
  (let [id (node->id node)]
    (str/join "\n"
      [(str "location = \"" (name node) "\"")
       (str "server_id = " id)
       "server_address = \"127.0.0.1:8000\""
       (str "summary_filepath = \"" dir "/shim-" id "-summary.json\"")
       (str "output_filepath = \"" dir "/shim-" id ".csv\"")
       "[[requests]]"
       "duration_sec = 86400"
       "requests_per_sec = 1"
       "read_ratio = 0.5"
       ""])))

;;; Helpers

(defn upload-str!
  "Writes content to a local temp file and uploads it to remote-path."
  [content remote-path]
  (let [tmp (java.io.File/createTempFile "omnipaxos" ".toml")]
    (try
      (spit tmp content)
      (c/upload (.getAbsolutePath tmp) remote-path)
      (finally (.delete tmp)))))

(defn start-server! []
  (cu/start-daemon!
    {:logfile server-log
     :pidfile server-pid
     :chdir   dir}
    "/usr/bin/env"
    (str "SERVER_CONFIG_FILE=" dir "/server.toml")
    (str "CLUSTER_CONFIG_FILE=" dir "/cluster.toml")
    "RUST_LOG=debug"
    server-bin))

(defn start-shim! []
  (cu/start-daemon!
    {:logfile shim-log
     :pidfile shim-pid
     :chdir   dir}
    "/usr/bin/env"
    (str "CONFIG_FILE=" dir "/shim.toml")
    "API_LISTEN_ADDR=0.0.0.0:7000"
    "RUST_LOG=debug"
    shim-bin))

(defn await-shim!
  "Polls /health on the local api-shim until it responds, up to ~60s."
  [node]
  (loop [tries 30]
    (when (zero? tries)
      (throw (ex-info "api-shim health check timed out" {:node node})))
    (let [ok (try
               (c/exec :curl :-sf "http://127.0.0.1:7000/health")
               true
               (catch Exception _ false))]
      (if ok
        (info node "api-shim healthy")
        (do (Thread/sleep 2000)
            (recur (dec tries)))))))

;;; Public API

(defn omnipaxos-db
  "Returns a Jepsen DB component.
   local-server-bin and local-shim-bin are paths to pre-built Rust binaries
   on the machine running the Jepsen control node."
  [local-server-bin local-shim-bin]
  (reify
    db/DB
    (setup! [_ test node]
      (info node "setting up OmniPaxos")
      (c/exec :mkdir :-p dir)
      ; Upload binaries
      (c/upload local-server-bin server-bin)
      (c/upload local-shim-bin   shim-bin)
      (c/exec :chmod :+x server-bin shim-bin)
      ; Write config files
      (upload-str! (cluster-toml (:nodes test)) (str dir "/cluster.toml"))
      (upload-str! (server-toml node)            (str dir "/server.toml"))
      (upload-str! (shim-toml node)              (str dir "/shim.toml"))
      ; Start server — it blocks internally until all peers + 1 client connect,
      ; so all nodes must start their servers before starting shims.
      (start-server!)
      ; Give all servers a moment to start listening before shims connect.
      ; Jepsen runs setup! in parallel across nodes so this small delay is
      ; usually enough for peers to be up.
      (Thread/sleep 5000)
      ; Start api-shim — connecting it to the local server satisfies
      ; the server's num_clients=1 requirement, unblocking Network::new.
      (start-shim!)
      ; Wait until the shim is fully initialised (StartSignal received).
      (await-shim! node))

    (teardown! [_ test node]
      (info node "tearing down OmniPaxos")
      (cu/stop-daemon! shim-bin   shim-pid)
      (cu/stop-daemon! server-bin server-pid)
      (c/exec :rm :-rf dir))

    db/LogFiles
    (log-files [_ test node]
      [server-log shim-log])))
