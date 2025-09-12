#!/usr/bin/env bash
set -euo pipefail
BASE=${1:-http://localhost:9000}
curl -fsS "$BASE/health"  | grep -q '"status":"ok"'
curl -fsS "$BASE/metrics" | grep -q process_cpu_seconds_total
echo "Smoke OK against $BASE"
