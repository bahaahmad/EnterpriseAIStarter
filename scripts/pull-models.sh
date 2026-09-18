#!/usr/bin/env bash
# Run ONCE while connected (Git Bash or WSL). Everything after this must work offline (REQ-SUP-04).
set -euo pipefail
docker compose --profile full pull
docker compose up -d ollama
for m in qwen3:4b qwen3:8b bge-m3; do docker compose exec -T ollama ollama pull "$m"; done
./scripts/record-model-hashes.sh
