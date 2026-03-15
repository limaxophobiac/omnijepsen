# ID2203 Project: Battle-testing OmniPaxos with Jepsen

## Status

| Task | Status |
|------|--------|
| 2.1.1 HTTP shim (PUT/GET /kv/:key, /health on port 7000) | DONE |
| 2.1.2 Jepsen client + read/write generator + Knossos checker | DONE |
| 2.1.3 Nemesis (partitions + crashes) + linearizability verified | DONE |
| Final clean test run (RUST_LOG back to info) | TODO |
| Report | TODO |

## What Has Been Done

- `Omnipax/src/client/api_shim.rs` — HTTP shim using axum, port 7000. PUT/GET /kv/:key routed through OmniPaxos consensus via ClientMessage::Append. Reads are linearizable because they go through the log, not served locally.
- `Omnipax/api-shim.dockerfile` — builds the api-shim binary
- `jepsen-omnipaxos/src/jepsen/omnipaxos/client.clj` — Jepsen client with correct indeterminate state handling (timeout/500 → :info, ConnectException → :fail). Reads parse response body as Long to match integer write values.
- `jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj` — generator (read/write mix, key-count=1, gen/clients wrapper), Knossos linearizability checker, wired to db.clj and nemesis.clj
- `jepsen-omnipaxos/src/jepsen/omnipaxos/db.clj` — deploys server + api-shim to Jepsen nodes via SSH, generates TOML configs per node, starts server first then shim, polls /health to confirm ready. Currently RUST_LOG=debug, change back to info for final run.
- `jepsen-omnipaxos/src/jepsen/omnipaxos/nemesis.clj` — partition nemesis (partition-random-halves) + crash nemesis (kills and restarts server + api-shim on a random node)
- Test passed :valid? true under both partition and crash faults

## Key Findings

- OmniPaxos maintains linearizability under network partitions and node crashes
- When n1 (leader) crashes, n2 and n3 continue deciding entries without interruption — ~7.4 entries/sec vs ~9.4 entries/sec when all nodes up (21% drop, due to fewer workers submitting, not consensus slowdown)
- Reads are linearizable because they go through ClientMessage::Append — assigned a specific log index, so they observe exactly the state at that point in the agreed sequence
- Local reads (directly from HashMap) would NOT be linearizable — a follower's state could be stale
- Workers pinned to crashed node get :fail :connection-refused (correct — request never reached server). These are not acknowledged so losing them is fine per linearizability requirements.

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
    core.clj                    — generator, Knossos checker, db + nemesis wired in, key-count=1
    db.clj                      — SSH deploy/start/stop of server + api-shim on each node
    nemesis.clj                 — partition + crash nemesis
  project.clj                   — deps: jepsen 0.3.11-SNAPSHOT (pulls knossos 0.3.15), clj-http

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

# 3. Copy to control node (from project root)
docker cp Omnipax/target/release/server jepsen-control:/root/server
docker cp Omnipax/target/release/api-shim jepsen-control:/root/api-shim
docker cp jepsen-omnipaxos jepsen-control:/root/jepsen-omnipaxos

# 4. Open control node shell (from jepsen-main/docker/)
bin/console

# 5. Run test (inside control node)
cd /root/jepsen-omnipaxos
lein run -- test --nodes n1,n2,n3 --time-limit 120 --server-bin /root/server --shim-bin /root/api-shim

# 6. Copy results back to host
docker cp jepsen-control:/root/jepsen-omnipaxos/store ./jepsen-results
```

When editing Clojure files, copy the changed file then lein clean before re-running:
```bash
docker cp jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj jepsen-control:/root/jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj
```

## For the Final Clean Run

Change RUST_LOG back to info in db.clj (both start-server! and start-shim! functions), then copy and re-run.

## Report Outline

### Linearizable Reads Design

The key design decision: reads go through ClientMessage::Append (same as writes), routing them through the Paxos log.

**Why local reads are not linearizable:**
A follower's in-memory HashMap may be stale. If n1 commits a write and n2 hasn't received it yet, a local read from n2 returns the old value — a stale read, violating linearizability.

**Why log-based reads are linearizable:**
Each read is assigned a specific log index via consensus. The server applies the read at that exact position — after all prior writes, before all later writes. This guarantees the read reflects all acknowledged writes that preceded it.

**Tradeoff:**
Log-based reads require a full consensus round (slower) but are safe. Local reads are faster but not linearizable.

**Evidence:**
Test results show :valid? true under partitions and crashes, confirming log-based reads are linearizable in practice.

## Notes

- key-count = 1 — using single key because Knossos models the whole history as one register
- Knossos is pulled in as a transitive dependency of jepsen (knossos 0.3.15)
- Node ID mapping: n1→1, n2→2, n3→3 (hardcoded in db.clj node->id)
- initial_leader = 1 (n1) in cluster config
- Jepsen workers are permanently bound to nodes (worker→node fixed at open!)
- Worker pinned to crashed node fails fast (:fail), gets more operations from control node than live workers, but all fail
