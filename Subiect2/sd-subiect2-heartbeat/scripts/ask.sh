#!/usr/bin/env bash
set -euo pipefail
QUESTION=${*:-"Care e sensul vietii?"}
if command -v nc >/dev/null 2>&1; then
  printf "%s\n" "$QUESTION" | nc localhost 1600
else
  docker run --rm --network sd-subiect2-heartbeat_default sd-subiect2-heartbeat-teacher sh -c "printf '%s\n' '$QUESTION' | nc teacher 1600"
fi
