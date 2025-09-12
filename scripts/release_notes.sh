#!/usr/bin/env bash
set -euo pipefail
LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
[ -z "$LAST_TAG" ] && git log --pretty="- %h %s (%ad)" --date=short || \
git log "$LAST_TAG"..HEAD --pretty="- %h %s (%ad)" --date=short
