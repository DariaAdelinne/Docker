#!/usr/bin/env bash
set -euo pipefail
QUESTION=${*:-"Care e sensul vietii?"}
java -cp target/sd-subiect2-heartbeat-1.0-SNAPSHOT-jar-with-dependencies.jar com.sd.laborator.client.ChatClientMainKt "$QUESTION"
