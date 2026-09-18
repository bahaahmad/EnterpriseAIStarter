#!/usr/bin/env bash
# A2: create the scoped gateway keys with the fixed values from .env (no copy-paste step).
# Run once after the first stage-1 start, and again after `docker compose down -v`.
# "already exists" on a re-run is fine.
set -euo pipefail
set -a; source .env; set +a
gen() {
  curl -s http://localhost:4000/key/generate \
    -H "Authorization: Bearer $LITELLM_MASTER_KEY" -H 'Content-Type: application/json' \
    -d "{\"key\":\"$1\",\"key_alias\":\"$2\",\"models\":$3}"
  echo
}
# rag-service may only reach the models, never kb-copilot (prevents a loop through itself)
gen "$RAG_GATEWAY_KEY" rag-service '["chat-default","embed-default"]'
# Open WebUI may only reach the product and the plain chat model
gen "$OPENWEBUI_KEY"   open-webui  '["kb-copilot","chat-default"]'
