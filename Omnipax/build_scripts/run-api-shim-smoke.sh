#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

API_HOST="${API_HOST:-127.0.0.1}"
API_PORT="${API_PORT:-7001}"
API_LISTEN_ADDR="${API_LISTEN_ADDR:-$API_HOST:$API_PORT}"
CONFIG_FILE="${CONFIG_FILE:-./client-1-config.toml}"
KEEP_RUNNING="${KEEP_RUNNING:-0}"

CLUSTER_PID=""
SHIM_PID=""

cleanup() {
  local exit_code=$?

  if [[ -n "$SHIM_PID" ]] && kill -0 "$SHIM_PID" 2>/dev/null; then
    kill "$SHIM_PID" || true
  fi

  if [[ -n "$CLUSTER_PID" ]] && kill -0 "$CLUSTER_PID" 2>/dev/null; then
    kill "$CLUSTER_PID" || true
  fi

  wait || true
  exit "$exit_code"
}

trap cleanup EXIT INT TERM

cd "$SCRIPT_DIR"

echo "[1/5] Cleaning up old local processes (if any)..."
pkill -f '/target/debug/server' || true
pkill -f '/target/debug/api-shim' || true
pkill -f 'run-local-cluster.sh' || true

# Avoid common macOS conflict seen on 7000
if [[ "$API_PORT" == "7000" ]]; then
  if lsof -nP -iTCP:7000 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port 7000 is already in use. Set API_PORT to another port, e.g.: API_PORT=7001"
    exit 1
  fi
fi

echo "[2/5] Starting local OmniPaxos cluster..."
bash "$SCRIPT_DIR/run-local-cluster.sh" > "$SCRIPT_DIR/logs/cluster.out" 2>&1 &
CLUSTER_PID=$!

echo "[3/5] Starting api-shim on http://$API_LISTEN_ADDR ..."
API_LISTEN_ADDR="$API_LISTEN_ADDR" \
CONFIG_FILE="$CONFIG_FILE" \
cargo run --manifest-path "$PROJECT_ROOT/Cargo.toml" --bin api-shim > "$SCRIPT_DIR/logs/api-shim.out" 2>&1 &
SHIM_PID=$!

echo "[4/5] Waiting for /health endpoint..."
for _ in {1..60}; do
  if curl -sS -f "http://$API_LISTEN_ADDR/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -sS -f "http://$API_LISTEN_ADDR/health" >/dev/null 2>&1; then
  echo "api-shim did not become healthy in time."
  echo "See logs: $SCRIPT_DIR/logs/cluster.out and $SCRIPT_DIR/logs/api-shim.out"
  exit 1
fi

echo "[5/5] Running smoke test requests..."
echo "- PUT /kv/x"
curl -i --max-time 10 -X PUT "http://$API_LISTEN_ADDR/kv/x" --data-binary "1"
echo

echo "- GET /kv/x"
curl -i --max-time 10 "http://$API_LISTEN_ADDR/kv/x"
echo

echo "Smoke test finished successfully."
echo "Logs:"
echo "  Cluster:  $SCRIPT_DIR/logs/cluster.out"
echo "  api-shim: $SCRIPT_DIR/logs/api-shim.out"

if [[ "$KEEP_RUNNING" == "1" ]]; then
  echo "KEEP_RUNNING=1 set: leaving cluster and api-shim running. Press Ctrl+C to stop."
  wait
fi
