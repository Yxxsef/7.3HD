#!/usr/bin/env bash
set -euo pipefail
echo "Define PREV_TAG then: docker pull yourdocker/7.3hd-api:$PREV_TAG && \
docker tag yourdocker/7.3hd-api:$PREV_TAG yourdocker/7.3hd-api:prod && \
docker compose -f docker-compose.prod.yml up -d"
