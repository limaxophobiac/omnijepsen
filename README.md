# ID2203 Project: Battle-testing OmniPaxos with Jepsen

This project tests the linearizability of an OmniPaxos key-value store under network partitions and node crashes using Jepsen.

## Repository Layout

```
Omnipax/                  Rust OmniPaxos KV server + HTTP shim
jepsen-omnipaxos/         Clojure Jepsen test suite
jepsen-main/              Jepsen Docker environment (control node + DB nodes)
jepsen-results/           Test output (copied from control node after runs)
```

## Architecture

```
Jepsen control node
  worker 0  →  HTTP :7000  →  n1 api-shim  →  n1 OmniPaxos server  ─┐
  worker 1  →  HTTP :7000  →  n2 api-shim  →  n2 OmniPaxos server  ─┼─ consensus
  worker 2  →  HTTP :7000  →  n3 api-shim  →  n3 OmniPaxos server  ─┘
```

Each Jepsen node (n1, n2, n3) runs:
- **OmniPaxos server** — consensus node on port 8000
- **api-shim** — HTTP bridge on port 7000 that forwards all requests (reads and writes) through the OmniPaxos consensus log

The Jepsen control node generates operations, assigns them to workers, collects results, and runs Knossos to verify linearizability.

When a test starts, Jepsen automatically SSHs into n1/n2/n3 and deploys the server and api-shim binaries. You do not need to manually set anything up on the DB nodes.

### Linearizable Reads

All reads go through `ClientMessage::Append` — the same path as writes. This assigns each read a specific position in the consensus log, ensuring it observes exactly the state at that agreed point. This makes reads linearizable.

A naive implementation reading directly from a node's local state would not be linearizable — a follower's in-memory state may be stale if it hasn't yet received the latest committed entries.

---

## Prerequisites

- Docker with `docker compose`
- Rust toolchain (`rustup`)
- Java (already included in the Jepsen Docker image)

Make sure your user can run Docker without sudo:
```bash
sudo usermod -aG docker $USER
newgrp docker
# if that doesn't take effect in the current shell:
sudo chmod 666 /var/run/docker.sock
```

---

## Step 1 — Build the Rust Binaries

```bash
cd Omnipax
cargo build --release --bin server --bin api-shim
```

Binaries will be at `Omnipax/target/release/server` and `Omnipax/target/release/api-shim`.

---

## Step 2 — Start the Jepsen Docker Environment

```bash
cd jepsen-main/docker
bin/up --nodes 3
```

This starts one control node (`jepsen-control`) and three DB nodes (`n1`, `n2`, `n3`). Wait until you see:
```
Please run `bin/console` in another terminal to proceed.
```

---

## Step 3 — Copy Binaries and Test Suite to the Control Node

Run from the project root on the host machine:

```bash
docker cp Omnipax/target/release/server jepsen-control:/root/server
docker cp Omnipax/target/release/api-shim jepsen-control:/root/api-shim
docker cp jepsen-omnipaxos jepsen-control:/root/jepsen-omnipaxos
```

When the test runs, Jepsen will automatically SSH the binaries from the control node to each DB node and start them.

---

## Step 4 — Open a Shell on the Control Node

In a new terminal:

```bash
cd jepsen-main/docker
bin/console
```

---

## Step 5 — Run the Test

Inside the control node shell:

```bash
cd /root/jepsen-omnipaxos
lein run -- test --nodes n1,n2,n3 --time-limit 120 --server-bin /root/server --shim-bin /root/api-shim
```

If you get compilation errors after changing source files, run `lein clean` first.

### CLI Options

| Flag | Default | Description |
|------|---------|-------------|
| `--nodes` | — | Comma-separated list of DB nodes |
| `--time-limit` | 60 | Test duration in seconds |
| `--concurrency` | 5 | Number of concurrent Jepsen workers |
| `--server-bin` | `server` | Path to the OmniPaxos server binary on the control node |
| `--shim-bin` | `api-shim` | Path to the api-shim binary on the control node |

---

## Step 6 — View Results

Results are written to `/root/jepsen-omnipaxos/store/omnipaxos-kv/latest/` on the control node.

**Copy to host:**
```bash
docker cp jepsen-control:/root/jepsen-omnipaxos/store ./jepsen-results
```

**Key files:**

| File | Description |
|------|-------------|
| `results.edn` | Final verdict — `:valid? true/false` (written by Knossos) |
| `history.txt` | Human-readable operation log |
| `history.edn` | Full operation log |
| `timeline.html` | Visual timeline — open in a browser |
| `jepsen.log` | Full test log including setup and teardown |
| `n1/server.log` | OmniPaxos server log per node |
| `n1/shim.log` | api-shim log per node |

**Browse via web UI** (run inside the control node):
```bash
lein run -- serve
```
Then run `bin/web` from `jepsen-main/docker/` on the host and open `http://localhost:8080`.

---

## Test Structure

All test code lives in `jepsen-omnipaxos/src/jepsen/omnipaxos/`:

| File | Purpose |
|------|---------|
| `core.clj` | Entry point — wires together db, client, generator, checker, and nemesis |
| `client.clj` | Jepsen client — translates operations to HTTP calls against the api-shim |
| `db.clj` | DB component — deploys and starts/stops server + api-shim on each node via SSH |
| `nemesis.clj` | Fault injection — network partitions and node crashes |

### Indeterminate State Handling

The client correctly handles ambiguous outcomes:

| Outcome | Jepsen type | Meaning |
|---------|-------------|---------|
| HTTP 204 / 200 | `:ok` | Operation definitely succeeded |
| `ConnectException` | `:fail` | Request never reached server, definitely did not commit |
| `SocketTimeoutException` / HTTP 500 | `:info` | Unknown — may or may not have committed through consensus |

Only `:ok` operations are required to be visible to subsequent reads. `:fail` operations are safely ignored by Knossos. `:info` operations are considered in both possible states.

### Nemesis

The nemesis cycles through two fault types:

1. **Network partition** — splits the cluster into two halves using iptables (`partition-random-halves`)
2. **Node crash** — kills server + api-shim on a random node, then restarts them after a delay

---

## Iterating on the Test

When editing Clojure source files locally, copy the changed file to the control node and re-run:

```bash
docker cp jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj jepsen-control:/root/jepsen-omnipaxos/src/jepsen/omnipaxos/core.clj
```

Inside the control node:
```bash
lein clean && lein run -- test --nodes n1,n2,n3 --time-limit 120 --server-bin /root/server --shim-bin /root/api-shim
```

The Rust binaries only need to be rebuilt if you change server or shim code, after which repeat Step 3.

---

## Stopping the Cluster

```bash
cd jepsen-main/docker
docker compose down
```
