# Omnipaxos-kv
This is an example repo showcasing the use of the [Omnipaxos](https://omnipaxos.com) consensus library to create a simple distributed key-value store. The source can be used to build server and client binaries which communicate over TCP. The repo also contains a benchmarking example which delploys Omnipaxos servers and clients onto [GCP](https://cloud.google.com) instances and runs an experiment collecting client response latencies (see `benchmarks/README.md`).

# Prerequisites
 - [Rust](https://www.rust-lang.org/tools/install)
 - [Docker](https://www.docker.com/)

# How to run
The `build_scripts` directory contains various utilities for configuring and running OmniPaxos clients and servers. Also contains examples of TOML file configuration.
 - `run-local-client.sh` runs two clients in separate local processes. Configuration such as which server to connect to defined in TOML files.
 - `run-local-cluster.sh` runs a 3 server cluster in separate local processes.
 - `docker-compose.yml` docker compose for a 3 server cluster.
 - See `benchmarks/README.md` for benchmarking scripts 

# How to run and test api_shim.rs
- Run `bash run-local-cluster.sh` (ensure you're in the `build_scripts` directory)
- In another terminal, run `API_LISTEN_ADDR=127.0.0.1:7001 CONFIG_FILE=./client-1-config.toml cargo run --manifest-path ../Cargo.toml --bin api-shim`
- In a third terminal, test the endpoints via `curl -i "http://127.0.0.1:7001/health"`, `curl -i -X PUT "http://127.0.0.1:7001/kv/x" --data-binary "1"` or `curl -i "http://127.0.0.1:7001/kv/x"`
Optional reset before rerun
- `pkill -f '/target/debug/server' || true`
- `pkill -f '/target/debug/api-shim' || true`

## One-command smoke test script
From `build_scripts`, run:
- `./run-api-shim-smoke.sh`

Useful options:
- `API_PORT=7002 ./run-api-shim-smoke.sh` (use a different port)
- `KEEP_RUNNING=1 ./run-api-shim-smoke.sh` (do not auto-stop services)