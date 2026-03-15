(ns jepsen.omnipaxos.client
  "Jepsen client for the OmniPaxos KV HTTP shim.

  The shim exposes:
    PUT /kv/:key   body=value  -> 204 on success, 500 on error
    GET /kv/:key               -> 200+body on found, 404 on missing, 500 on error
    GET /health                -> 200 \"ok\"

  Indeterminate state handling:
  - ConnectException  -> :fail  (server unreachable, request never reached consensus)
  - SocketTimeout     -> :info  (unknown whether the write committed through Paxos)
  - HTTP 500          -> :info  (server may be mid-election or mid-partition)
  - HTTP 404 on read  -> :ok nil (key simply does not exist)"
  (:require [clj-http.client :as http]
            [jepsen.client :as client]
            [clojure.tools.logging :refer [warn]]))

(def api-port 7000)

(defrecord OmniPaxosClient [node url]
  client/Client

  (open! [this test node]
    (assoc this
           :node node
           :url  (str "http://" node ":" api-port)))

  (setup! [this test]
    ; Verify the shim is reachable before the test begins
    (http/get (str (:url this) "/health")
              {:socket-timeout 10000
               :conn-timeout   10000})
    this)

  (invoke! [this test op]
    (try
      (case (:f op)
        :write
        (let [resp (http/put (str (:url this) "/kv/" (:key op))
                             {:body             (str (:value op))
                              :socket-timeout   5000
                              :conn-timeout     1000
                              :throw-exceptions false})]
          (if (= 204 (:status resp))
            (assoc op :type :ok)
            ; Non-204: request reached the server but we don't know if it was
            ; committed through consensus (e.g. during a leader election).
            (assoc op :type :info, :error (:status resp))))

        :read
        (let [resp (http/get (str (:url this) "/kv/" (:key op))
                             {:socket-timeout   5000
                              :conn-timeout     1000
                              :throw-exceptions false})]
          (condp = (:status resp)
            200 (assoc op :type :ok, :value (Long/parseLong (:body resp)))
            404 (assoc op :type :ok, :value nil)
            ; 500 or other: indeterminate
            (assoc op :type :info, :error (:status resp)))))

      (catch java.net.ConnectException _
        ; Port closed: request never reached the server, so nothing committed.
        (assoc op :type :fail, :error :connection-refused))

      (catch java.net.SocketTimeoutException _
        ; In-flight timeout: for writes, the entry may or may not have been
        ; replicated and decided by Paxos. Must be treated as indeterminate.
        (assoc op :type :info, :error :timeout))

      (catch Exception e
        (warn "Unexpected exception during" (:f op) e)
        (assoc op :type :info, :error (.getMessage e)))))

  (teardown! [this test])

  (close! [this test]))

(defn omnipaxos-client
  "Returns a fresh OmniPaxosClient. Call open! to bind it to a node."
  []
  (map->OmniPaxosClient {}))
