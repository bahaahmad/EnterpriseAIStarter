#!/usr/bin/env bash
# I11 supply chain: CVE scan of every image in the stack. Needs trivy installed locally.
set -euo pipefail
mkdir -p eval/results
docker compose config --images | sort -u | while read -r img; do
  echo "== $img"; trivy image --severity HIGH,CRITICAL --quiet "$img" | tee -a eval/results/trivy.txt
done
