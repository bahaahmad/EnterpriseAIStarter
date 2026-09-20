#!/usr/bin/env bash
# One command per bring-up stage. Sets the .env values that stage needs, then starts exactly the right
# services. Single source of truth stays .env — nothing is duplicated into a second compose or config file.
#
#   ./scripts/stage.sh dev      core: postgres, ollama, litellm, open-webui
#   ./scripts/stage.sh ingest   + docling, for loading the corpus
#   ./scripts/stage.sh full     + presidio, phoenix, and the full gateway config (guardrails, tracing)
#   ./scripts/stage.sh eval     full minus open-webui and docling, 8B judge, more memory for Ollama
#   ./scripts/stage.sh status   what .env says and what is running
#   ./scripts/stage.sh down     stop everything, keep all data
set -uo pipefail
cd "$(dirname "$0")/.."

set_env() {                       # set_env KEY value — rewrite one line in .env
  local key=$1 val=$2
  if grep -q "^$key=" .env; then
    sed -i "s|^$key=.*|$key=$val|" .env
  else
    printf '%s=%s\n' "$key" "$val" >> .env
  fi
  printf '  %-18s %s\n' "$key" "$val"
}

case "${1:-status}" in
  dev)
    echo "Stage: dev — plumbing. UI -> gateway -> rag-service -> model."
    set_env LITELLM_CONFIG config.stage1.yaml
    set_env OLLAMA_MEM 5g
    docker compose up -d postgres ollama litellm open-webui
    echo "Next: run rag-service in IntelliJ, then ./scripts/smoke-test.sh all"
    ;;
  ingest)
    echo "Stage: ingest — corpus into pgvector."
    set_env LITELLM_CONFIG config.stage1.yaml
    set_env OLLAMA_MEM 5g
    docker compose --profile ingest up -d postgres ollama litellm docling
    echo "Next: POST /admin/ingest, then ./scripts/stage.sh full (docling can stop afterwards)"
    ;;
  full)
    echo "Stage: full — guardrails and tracing on."
    set_env LITELLM_CONFIG config.full.yaml
    set_env OLLAMA_MEM 5g
    docker compose --profile guard --profile trace up -d \
      postgres ollama litellm open-webui presidio-analyzer presidio-anonymizer phoenix
    docker compose stop docling 2>/dev/null
    echo "Next: ask a question with a name in it and check the trace at http://localhost:6006"
    ;;
  eval)
    echo "Stage: eval — measuring. UI and docling stopped so the judge model fits."
    set_env LITELLM_CONFIG config.full.yaml
    set_env OLLAMA_MEM 7g
    set_env EVAL_JUDGE_MODEL chat-quality
    docker compose stop open-webui docling 2>/dev/null
    docker compose --profile guard --profile trace up -d \
      postgres ollama litellm presidio-analyzer presidio-anonymizer phoenix
    echo "Next: rag-service running, then (cd eval/src && ../.venv/Scripts/python -m eval.run_eval)"
    ;;
  down)
    docker compose --profile full down
    echo "Stopped. Volumes kept: knowledge base, gateway keys, UI accounts, traces, models."
    ;;
  status)
    echo "From .env:"
    grep -E '^(LITELLM_CONFIG|OLLAMA_MEM|EVAL_JUDGE_MODEL)=' .env | sed 's/^/  /'
    echo
    docker compose ps --format 'table {{.Service}}\t{{.Status}}'
    ;;
  *)
    sed -n '3,12p' "$0"; exit 2 ;;
esac
