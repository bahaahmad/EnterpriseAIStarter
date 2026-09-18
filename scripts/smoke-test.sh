#!/usr/bin/env bash
# Gateway smoke tests (stage 1). Usage: ./scripts/smoke-test.sh {models|chat|embed|all|watch}
# Reads every value from .env. Run from the project root.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
GW=http://localhost:4000
AUTH="Authorization: Bearer $LITELLM_MASTER_KEY"
JSON="Content-Type: application/json"

hr() { printf '\n== %s\n' "$1"; }
timed() { local s=$SECONDS; "$@"; printf '\n-- %ss\n' "$((SECONDS-s))"; }

models() {
  hr "Models the gateway exposes (expect chat-default, chat-quality, embed-default, kb-copilot)"
  curl -sS "$GW/v1/models" -H "$AUTH" | python -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit('not JSON — is LiteLLM up? docker compose logs litellm')
ids = [m.get('id') for m in d.get('data', [])]
print('\n'.join(ids) if ids else 'EMPTY LIST — check LITELLM_CONFIG in .env')
"
}

chat() {
  local model=${1:-chat-default}
  hr "Plain question to $model (no RAG). First call includes the model load: 30-90 s on CPU"
  curl -sS "$GW/v1/chat/completions" -H "$AUTH" -H "$JSON" -d '{
    "model": "'"$model"'",
    "messages": [{"role":"user","content":"Reply with one short sentence: what is a vector database? /no_think"}]
  }' | python -c "
import sys, json
d = json.load(sys.stdin)
if 'error' in d: sys.exit('ERROR: %s' % d['error'])
print(d['choices'][0]['message']['content'].strip())
u = d.get('usage') or {}
print('tokens: prompt=%s completion=%s' % (u.get('prompt_tokens'), u.get('completion_tokens')))
"
}

embed() {
  hr "Embeddings via $GW (dimension must be 1024 to match kb_chunk)"
  curl -sS "$GW/v1/embeddings" -H "$AUTH" -H "$JSON" \
       -d '{"model":"embed-default","input":"refund approval limit"}' | python -c "
import sys, json
d = json.load(sys.stdin)
if 'error' in d: sys.exit('ERROR: %s' % d['error'])
v = d['data'][0]['embedding']
print('dimensions:', len(v), '(expected 1024)')
print('first values:', [round(x, 4) for x in v[:5]])
"
}

watch() { hr "Ollama log + container memory (Ctrl+C to stop)"; docker stats --no-stream; docker compose logs -f ollama; }

case "${1:-all}" in
  models) timed models ;;
  chat)   timed chat "${2:-chat-default}" ;;
  embed)  timed embed ;;
  watch)  watch ;;
  all)    timed models; timed embed; timed chat; hr "Done — all three passed if no ERROR above" ;;
  *)      echo "usage: $0 {models|chat [model]|embed|all|watch}"; exit 2 ;;
esac
