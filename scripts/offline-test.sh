#!/usr/bin/env bash
# Exit criterion X6 — the whole stack must work with no network.
#
# Why it matters: on the server the AI zone has deny-by-default egress. Anything quietly fetching a model,
# a tokenizer, a font or a telemetry endpoint works on a laptop and fails there. This is the rehearsal.
#
#   1. Disconnect Wi-Fi / unplug the cable.  2. ./scripts/offline-test.sh
#
# It pauses once, after restarting the containers, so you can restart rag-service in IntelliJ — the stack
# restart invalidates its database connections.
#
# The script stops everything, brings it back up from local images only, and runs the checks that together
# prove the stack is self-contained: gateway, embeddings, generation, ingestion and the leakage gate.
set -uo pipefail
cd "$(dirname "$0")/.."
FAILED=0
step() { printf '\n=== %s\n' "$1"; }
wait_for() {                       # wait_for <url> <seconds> <label>
  local url=$1 secs=$2 label=$3 i=0
  printf '  waiting for %s ' "$label"
  while [ $i -lt "$secs" ]; do
    if curl -s -o /dev/null --max-time 3 "$url"; then echo " up (${i}s)"; return 0; fi
    printf '.'; sleep 3; i=$((i+3))
  done
  echo " TIMED OUT after ${secs}s"; return 1
}
check() { if [ "$1" -eq 0 ]; then echo "  PASS"; else echo "  FAIL"; FAILED=1; fi; }

step "0 · Confirm the machine really is offline"
if curl -s --max-time 5 -o /dev/null https://registry.ollama.ai 2>/dev/null; then
  echo "  Still online — disconnect the network first, or this proves nothing."
  exit 2
fi
echo "  No route to the internet. Good."

step "1 · Restart the stack from local images (docker must not pull)"
docker compose --profile full down
./scripts/stage.sh full
wait_for http://localhost:4000/health 180 "gateway" || true
docker compose ps --format 'table {{.Service}}\t{{.Status}}' | sed 's/^/  /'

step "2 · Gateway, embeddings and generation"
./scripts/smoke-test.sh all
check $?

step "3 · rag-service"
# Step 1 recreated every container, including postgres, so a rag-service running in IntelliJ now holds
# connections to a database that no longer exists. It must be restarted, and only you can do that.
echo "  The stack was recreated. RESTART '2 rag-service' in IntelliJ now."
read -r -p "  Press Enter once it is up... " _
if curl -sf --max-time 10 http://localhost:8081/actuator/health > /dev/null; then
  echo "  PASS"
else
  echo "  FAIL — rag-service is not answering on 8081. Check its console for connection errors."
  FAILED=1
fi

step "4 · Ingestion (Docling + embeddings, no network)"
# Start Docling directly rather than through stage.sh ingest: that stage also resets LITELLM_CONFIG to
# config.stage1.yaml, which would quietly finish this test with the guardrails switched off.
docker compose --profile ingest up -d docling > /dev/null
wait_for http://localhost:5010/docs 180 "docling"   # it loads models on boot; 10s is not enough
curl -sf --max-time 1800 -X POST http://localhost:8081/admin/ingest | sed 's/^/  /'
check $?
docker compose stop docling > /dev/null 2>&1

step "5 · Leakage gate"
( cd eval/src && ../.venv/Scripts/python -m eval.run_eval --only leakage )
check $?

step "Result"
if [ "$FAILED" -eq 0 ]; then
  echo "  X6 PASSED — the stack is self-contained."
else
  echo "  X6 FAILED. Usual suspects, in order:"
  echo "   - LiteLLM's Prisma migrations trying to download engine files at startup"
  echo "   - Open WebUI or Docling fetching a model, tokenizer or font (OFFLINE_MODE / HF_HUB_OFFLINE)"
  echo "   - a component phoning a telemetry endpoint and blocking on the timeout"
  echo "   - an image tag that is not present locally, so compose tries to pull"
  echo "  Check 'docker compose logs <service>' for the one that failed, then fix it HERE, not on the server."
fi
exit $FAILED
