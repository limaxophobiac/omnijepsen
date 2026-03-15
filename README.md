# ID2203 Project: Battle-testing OmniPaxos with Jepsen

This project tests the linearizability of an OmniPaxos key-value store using Jepsen.

## Repository Layout

```
Omnipax/                  Rust OmniPaxos KV server + HTTP shim
jepsen-omnipaxos/         Clojure Jepsen test suite
jepsen-main/              Jepsen Docker environment (control node + DB nodes)
jepsen-results/           Test output (copied from control node after runs)
```

## Architecture

Each Jepsen node (n1, n2, n3) runs:
- **OmniPaxos server** — consensus node on port 8000
- **api-shim** — HTTP bridge on port 7000 that forwards requests through consensus

The Jepsen control node runs the Clojure test, which drives reads/writes through the HTTP shim on each node and verifies linearizability using Knossos.

When a test starts, Jepsen automatically SSHs into n1/n2/n3 and deploys the server and api-shim binaries. You do not need to manually set anything up on the DB nodes.

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

Binaries will be at:
- `Omnipax/target/release/server`
- `Omnipax/target/release/api-shim`

---

## Step 2 — Start the Jepsen Docker Environment

```bash
cd jepsen-main/docker
bin/up --nodes 3
```

This starts one control node (`jepsen-control`) and three DB nodes (`n1`, `n2`, `n3`).
Wait until you see:
```
Please run `bin/console` in another terminal to proceed.
```

---

## Step 3 — Copy Binaries and Test Suite to the Control Node

Run these from the project root on the host machine:

```bash
docker cp Omnipax/target/release/server   jepsen-control:/root/server
docker cp Omnipax/target/release/api-shim jepsen-control:/root/api-shim
docker cp jepsen-omnipaxos                jepsen-control:/root/jepsen-omnipaxos
```

When the test runs, Jepsen will automatically SSH the binaries from the control node to each DB node (n1, n2, n3) and start them.

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
lein run -- test --nodes n1,n2,n3 --time-limit 60 \
  --server-bin /root/server --shim-bin /root/api-shim
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
| `results.edn` | Final verdict — `:valid? true/false` |
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

## Iterating on the Test

When editing Clojure source files locally, copy only the changed file to the control node:

```bash
docker cp jepsen-omnipaxos/src/jepsen/omnipaxos/client.clj \
  jepsen-control:/root/jepsen-omnipaxos/src/jepsen/omnipaxos/client.clj
```

Then inside the control node:
```bash
lein clean && lein run -- test --nodes n1,n2,n3 --time-limit 60 \
  --server-bin /root/server --shim-bin /root/api-shim
```

The Rust binaries only need to be rebuilt if you change server or shim code, after which repeat Step 3.

---

## Stopping the Cluster

```bash
cd jepsen-main/docker
docker compose down
```
