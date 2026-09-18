#!/usr/bin/env bash
# REQ-SUP-02: print IMG_* lines pinned to the digest of the images you have RIGHT NOW.
# Usage: ./scripts/pin-images.sh          (review, then paste over the IMG_* lines in .env)
#        ./scripts/pin-images.sh --write  (rewrite .env in place, keeping a .env.bak)
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a

out=$(mktemp)
for var in $(grep -o '^IMG_[A-Z_]*' .env); do
  ref=${!var}
  digest=$(docker image inspect "$ref" --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' 2>/dev/null || true)
  if [ -z "$digest" ]; then
    echo "# $var: image not pulled locally, left as ${ref}" >&2
    echo "$var=$ref" >> "$out"
  else
    echo "$var=$digest" >> "$out"
  fi
done
cat "$out"

if [ "${1:-}" = "--write" ]; then
  cp .env .env.bak
  while IFS='=' read -r k v; do
    sed -i "s|^$k=.*|$k=$v|" .env
  done < "$out"
  echo >&2 "Rewrote .env (backup: .env.bak). Now: docker compose up -d && rerun the golden set."
fi
rm -f "$out"
