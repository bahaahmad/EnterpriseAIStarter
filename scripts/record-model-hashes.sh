#!/usr/bin/env bash
# D9 model repository (laptop form): digest + licence status per approved model.
set -euo pipefail
out=models/registry.yaml
echo "# generated $(date -u +%FT%TZ) — fill licence/approval by hand" > "$out"
echo "models:" >> "$out"
docker compose exec -T ollama ollama list | tail -n +2 | while read -r name id size unit _; do
  printf '  - name: %s\n    ollama_id: %s\n    size: %s %s\n    licence: TODO\n    approved_by: TODO\n' \
    "$name" "$id" "$size" "$unit" >> "$out"
done
cat "$out"
