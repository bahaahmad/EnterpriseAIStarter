#!/usr/bin/env bash
# I11 supply chain (REQ-SUP-03): CVE scan of every image in the stack.
# Trivy runs as a container — nothing is installed on the laptop. It reads the Docker socket to inspect
# local images, and caches its vulnerability database in a named volume so later runs work offline.
# Usage: ./scripts/scan-images.sh [severity]    default HIGH,CRITICAL
set -uo pipefail
cd "$(dirname "$0")/.."
SEV=${1:-HIGH,CRITICAL}
OUT=eval/results/trivy-$(date +%Y%m%d-%H%M%S).txt
mkdir -p eval/results

# Git Bash rewrites Unix-looking paths into Windows ones ("C:\Program Files\Git\var\run\..."), which makes
# the socket mount fail. MSYS_NO_PATHCONV=1 and the leading // both stop that; harmless on Linux and macOS.
trivy() {
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker run --rm \
    -v //var/run/docker.sock://var/run/docker.sock \
    -v trivy-cache:/root/.cache/trivy \
    aquasec/trivy:latest "$@"
}

# Images only need to exist locally — containers do not have to be running.
echo "Severity: $SEV -> $OUT"
docker compose --profile full config --images | sort -u | while read -r img; do
  echo "== $img" | tee -a "$OUT"
  trivy image --severity "$SEV" --scanners vuln --quiet "$img" 2>&1 | tee -a "$OUT"
done

echo
echo "Findings are expected — most sit in base-OS packages you never call, and many have no fix."
echo "The deliverable is a decision per HIGH/CRITICAL: replace / accept with a reason / out of scope."
echo "Saved: $OUT"
