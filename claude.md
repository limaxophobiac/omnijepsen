# ID2203 Project: Battle-testing OmniPaxos with Jepsen

## Status

| Task | Status |
|------|--------|
| 2.1.1 HTTP shim (PUT/GET /kv/:key, /health on port 7000) | DONE |
| 2.1.2 Jepsen client + read/write generator + Knossos checker | DONE |
| 2.1.3 Nemesis (partitions + crashes) + linearizability verification | TODO |

## Repository Layout

```
Omnipax/                        — Rust OmniPaxos KV codebase
  src/
    server/main.rs              — OmniPaxos server binary
    client/api_shim.rs          — HTTP shim binary (axum, port 7000)
  Cargo.toml                    — [[bin]] api-shim registered here
  server.dockerfile             — builds `server` binary
  client.dockerfile             — builds `client` binary (NOT api-shim)
  build_scripts/
    docker-compose.yml          — spins up s1/s2/s3 + c1/c2

jepsen-omnipaxos/               — Clojure Jepsen test suite
  src/jepsen/omnipaxos/
    client.clj                  — HTTP client, indeterminate-state handling
    core.clj                    — generator (read/write mix), Knossos checker, noop db stub
  project.clj                   — deps: jepsen 0.3.11-SNAPSHOT, clj-http

jepsen-main/docker/             — Jepsen's own Docker environment
  bin/up                        — starts control + n1..n5 nodes
  bin/console                   — drops into control node shell
  docker-compose.yml            — control container + n1-n5 Debian nodes (SSH-accessible)
```

## Architecture for the Test Run

Each Jepsen node (n1, n2, n3) must run:
- `server` binary — OmniPaxos consensus node on port 8000
- `api-shim` binary — HTTP bridge on port 7000, connects to local server

Jepsen control node runs the Clojure test, which:
1. Deploys + starts server and api-shim on each node (via `db/setup!`)
2. Drives reads/writes through the HTTP shim
3. Injects failures via nemesis
4. Verifies linearizability with Knossos

---

## Step-by-Step Plan

### Step 1 — Build the api-shim Docker image

The current `client.dockerfile` builds `client`, not `api-shim`.
Create `Omnipax/api-shim.dockerfile` that builds the `api-shim` binary:

```dockerfile
FROM rust:1.88 AS chef
ENV CARGO_REGISTRIES_CRATES_IO_PROTOCOL=sparse
RUN cargo install cargo-chef --locked
WORKDIR /app

FROM chef AS planner
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

FROM chef AS builder
COPY --from=planner /app/recipe.json recipe.json
RUN cargo chef cook --release --recipe-path recipe.json
COPY . .
RUN cargo build --release --bin api-shim

FROM debian:bookworm-slim AS runtime
WORKDIR /app
COPY --from=builder /app/target/release/api-shim /usr/local/bin
COPY --from=builder /app/target/release/server /usr/local/bin
EXPOSE 7000 8000
```

Or simpler: build both binaries locally (`cargo build --release`) and copy
them into the Jepsen nodes during db/setup! over SSH.

### Step 2 — Implement the Jepsen DB component

Create `jepsen-omnipaxos/src/jepsen/omnipaxos/db.clj`.

This component uses `jepsen.control` (SSH) to:
- `setup!`: copy server + api-shim binaries to each node, write config files,
  start both processes
- `teardown!`: kill both processes, clean up

Config files to generate per node:
- `server-config.toml`: `server_id`, `listen_address=0.0.0.0`, `listen_port=8000`
- `cluster-config.toml`: `nodes=[1,2,3]`, `node_addrs=["n1:8000","n2:8000","n3:8000"]`
- `client-config.toml` (for api-shim): `server_id=<this_node>`, `server_address=localhost:8000`

Env vars the server already reads (from docker-compose precedent):
- `OMNIPAXOS_NODE_ADDRS`, `OMNIPAXOS_LISTEN_ADDRESS`, `OMNIPAXOS_LISTEN_PORT`
- `SERVER_CONFIG_FILE`, `CLUSTER_CONFIG_FILE`
- api-shim: `API_LISTEN_ADDR=0.0.0.0:7000`, `CONFIG_FILE=...`

### Step 3 — Wire db into core.clj

Update `core.clj` to replace the noop db stub with the real db:
```clojure
(:require [jepsen.omnipaxos.db :refer [omnipaxos-db]])
...
:db (omnipaxos-db binary-path)
```

### Step 4 — Implement the Nemesis (2.1.3)

Create `jepsen-omnipaxos/src/jepsen/omnipaxos/nemesis.clj`.

Two nemeses to implement:

**A. Network partition** — isolate leader from followers or split cluster:
```clojure
(jepsen.nemesis/partition-random-halves)
; or custom: isolate n1 (leader) from n2, n3
```
Uses iptables on the Jepsen nodes (they run as privileged containers).

**B. Crash nemesis** — kill and restart server + api-shim on a random node:
```clojure
; kill: jepsen.control/exec :pkill :-f "server"
; restart: re-run the start commands from db/setup!
```

Compose them with `jepsen.nemesis/compose` and interleave with generator using
`gen/nemesis` phases.

Update `core.clj` generator to add nemesis phases:
```clojure
:generator (gen/phases
             (->> (gen/mix [r w])
                  (gen/stagger 1/10)
                  (gen/nemesis (gen/cycle
                                 [(gen/sleep 10)
                                  {:type :info :f :start-partition}
                                  (gen/sleep 15)
                                  {:type :info :f :stop-partition}]))
                  (gen/time-limit (:time-limit opts 60)))
             (gen/nemesis {:type :info :f :stop-partition})
             (gen/sleep 5))
```

### Step 5 — Linearizable reads (hint from instructions)

The instructions note that local reads are not linearizable by default.
The current api-shim sends GET as `ClientMessage::Append` (goes through Paxos log) —
this IS linearizable. Verify this is correct and document it.

If reads were served locally (from in-memory state) they would be stale.
The current implementation routes reads through consensus, which is correct.

### Step 6 — Run the test

```bash
# From jepsen-main/docker/
bin/up --nodes 3          # start control + n1, n2, n3

bin/console               # shell on control node

# Inside control:
# Copy jepsen-omnipaxos project + binaries to control node (via docker cp or volume mount)
cd /path/to/jepsen-omnipaxos
lein run -- test --nodes n1,n2,n3 --time-limit 60 --concurrency 5
```

Results land in `store/` — open with `lein run serve` + `bin/web` on port 8080.

---

## Running Without Jepsen Docker (Simpler Dev Loop)

For development/smoke testing before full Jepsen integration:

```bash
# 1. Start OmniPaxos cluster
cd Omnipax/build_scripts
docker compose build && docker compose up -d s1 s2 s3

# 2. Start api-shims (one per server, each as a separate process/container)
#    api-shim for s1: CONFIG_FILE=client-1-config.toml API_LISTEN_ADDR=0.0.0.0:7001 ./api-shim
#    api-shim for s2: CONFIG_FILE=client-2-config.toml API_LISTEN_ADDR=0.0.0.0:7002 ./api-shim
#    api-shim for s3: CONFIG_FILE=client-3-config.toml API_LISTEN_ADDR=0.0.0.0:7003 ./api-shim

# 3. Run the smoke test
bash build_scripts/run-api-shim-smoke.sh
```

---

## Key Files to Create/Modify

| File | Action | Notes |
|------|--------|-------|
| `Omnipax/api-shim.dockerfile` | CREATE | builds api-shim binary |
| `jepsen-omnipaxos/src/jepsen/omnipaxos/db.clj` | CREATE | SSH deploy + start/stop |
| `jepsen-omnipaxos/src/jepsen/omnipaxos/nemesis.clj` | CREATE | partition + crash nemesis |
| `jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj` | MODIFY | wire in db + nemesis |
| `Omnipax/build_scripts/client-3-config.toml` | CREATE | needed for 3-node shim deploy |

## Notes

- The Jepsen nodes in `jepsen-main/docker/` run as **privileged** containers with `cap_add: ALL`,
  so iptables partition nemesis will work out of the box.
- `jepsen 0.3.11-SNAPSHOT` in project.clj — may need the jepsen maven repo or local install.
  Check with `lein deps` inside the control container.
- `initial_leader = 1` is set in cluster-config — map node ID 1 → n1, 2 → n2, 3 → n3.
- The api-shim currently waits for `StartSignal` before serving requests; servers must be
  fully up before starting the shim, or db/setup! must handle ordering.
