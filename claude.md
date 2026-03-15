# ID2203 Project: Battle-testing OmniPaxos with Jepsen

## Status

| Task | Status |
|------|--------|
| 2.1.1 HTTP shim (PUT/GET /kv/:key, /health on port 7000) | DONE |
| 2.1.2 Jepsen client + read/write generator + Knossos checker | DONE |
| 2.1.3 Nemesis (partitions + crashes) + linearizability verification | TODO |

## What Has Been Done

- `Omnipax/src/client/api_shim.rs` — HTTP shim using axum, port 7000. PUT/GET /kv/:key routed through OmniPaxos consensus via ClientMessage::Append. Reads are linearizable because they go through the log, not served locally.
- `Omnipax/api-shim.dockerfile` — builds the api-shim binary
- `jepsen-omnipaxos/src/jepsen/omnipaxos/client.clj` — Jepsen client with correct indeterminate state handling (timeout/500 → :info, ConnectException → :fail). Reads parse response body as Long to match integer write values.
- `jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj` — generator (read/write mix, key-count=1, gen/clients wrapper), Knossos linearizability checker, wired to db.clj
- `jepsen-omnipaxos/src/jepsen/omnipaxos/db.clj` — deploys server + api-shim to Jepsen nodes via SSH, generates TOML configs per node, starts server first then shim, polls /health to confirm ready
- Baseline test run passed: :valid? true with no faults

## Repository Layout

```
Omnipax/                        — Rust OmniPaxos KV codebase
  src/
    server/main.rs              — OmniPaxos server binary
    client/api_shim.rs          — HTTP shim binary (axum, port 7000)
  Cargo.toml                    — [[bin]] api-shim registered here
  api-shim.dockerfile           — builds api-shim binary
  server.dockerfile             — builds server binary

jepsen-omnipaxos/               — Clojure Jepsen test suite
  src/jepsen/omnipaxos/
    client.clj                  — HTTP client, indeterminate-state handling
    core.clj                    — generator, Knossos checker, db wired in, key-count=1
    db.clj                      — SSH deploy/start/stop of server + api-shim on each node
  project.clj                   — deps: jepsen 0.3.11-SNAPSHOT, clj-http

jepsen-main/docker/             — Jepsen Docker environment
  bin/up                        — starts control + n1..n5 nodes
  bin/console                   — drops into control node shell
```

## How to Run the Test

```bash
# 1. Build Rust binaries (from Omnipax/)
cargo build --release --bin server --bin api-shim

# 2. Start Jepsen Docker (from jepsen-main/docker/)
bin/up --nodes 3
# wait for "Please run bin/console in another terminal"

# 3. Copy to control node (from project root)
docker cp Omnipax/target/release/server   jepsen-control:/root/server
docker cp Omnipax/target/release/api-shim jepsen-control:/root/api-shim
docker cp jepsen-omnipaxos                jepsen-control:/root/jepsen-omnipaxos

# 4. Open control node shell (from jepsen-main/docker/)
bin/console

# 5. Run test (inside control node)
cd /root/jepsen-omnipaxos
lein run -- test --nodes n1,n2,n3 --time-limit 60 --server-bin /root/server --shim-bin /root/api-shim

# 6. Copy results back to host
docker cp jepsen-control:/root/jepsen-omnipaxos/store ./jepsen-results
```

When editing Clojure files, copy the changed file then `lein clean` before re-running:
```bash
docker cp jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj \
  jepsen-control:/root/jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj
```

## What Still Needs to Be Done — 2.1.3

### 1. Nemesis — network partition

Create `jepsen-omnipaxos/src/jepsen/omnipaxos/nemesis.clj`.

Use `jepsen.nemesis/partition-random-halves` which uses iptables (works because Jepsen
nodes run as privileged containers with CAP_ALL).

```clojure
(ns jepsen.omnipaxos.nemesis
  (:require [jepsen.nemesis :as nemesis]
            [jepsen.nemesis.combined :as nc]))

(defn partition-nemesis []
  (nemesis/partition-random-halves))
```

### 2. Nemesis — crash + restart

Add a crash nemesis that kills and restarts server + api-shim on a random node.
Use `jepsen.control/exec :pkill` to kill and re-run the start commands from db.clj.

```clojure
(defn crash-nemesis []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :crash (let [node (rand-nth (:nodes test))]
                 (c/on node
                   (c/exec :pkill :-9 :-f "api-shim")
                   (c/exec :pkill :-9 :-f "server"))
                 (assoc op :type :info :value node))
        :recover (let [node (:value op)]
                   ; re-run start commands from db.clj
                   (assoc op :type :info))))
    (teardown! [this test])))
```

### 3. Wire nemesis into core.clj

Add to `omnipaxos-test`:
```clojure
:nemesis (nemesis/partition-random-halves)
:generator (gen/phases
             (->> (gen/mix [r w])
                  (gen/stagger 1/10)
                  (gen/clients)
                  (gen/nemesis (gen/cycle [(gen/sleep 10)
                                           {:type :info :f :start}
                                           (gen/sleep 15)
                                           {:type :info :f :stop}]))
                  (gen/time-limit (:time-limit opts 60)))
             (gen/nemesis {:type :info :f :stop})
             (gen/sleep 5))
```

### 4. Linearizable reads — document the design

The instructions ask: "What mechanisms can be implemented to ensure linearizable reads?"

The current implementation sends reads through `ClientMessage::Append` which routes them
through the Paxos log. This ensures linearizability. Document this in the report:
- Local reads (served from in-memory state) would NOT be linearizable — stale reads possible
- Log-based reads (current approach) ARE linearizable — read is decided at a specific log index
- Alternative: read lease / quorum read (more complex, not implemented)

## Known Issues / Notes

- `key-count = 1` — Knossos does not partition by key correctly with multiple keys,
  so we use a single key. This is correct and sufficient for linearizability testing.
- The api-shim waits for StartSignal from the server before processing commands.
  db.clj sleeps 5s after starting servers before starting shims. Increase if cluster
  formation is slow.
- `jepsen 0.3.11-SNAPSHOT` — if lein deps fails, may need to build jepsen locally from
  jepsen-main/ and `lein install` it on the control node.
- Node ID mapping: n1→1, n2→2, n3→3 (hardcoded in db.clj node->id)
- initial_leader = 1 (n1) — n1 always starts as leader
