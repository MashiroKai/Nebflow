#!/bin/bash
# Auto-detect the fastest Maven mirror for the user's network.
# Outputs a COURSIER_REPOSITORIES string suitable for sbt.
#
# Strategy: test Maven Central latency with a short timeout.
#   - Fast (<2s): international user, use Maven Central directly.
#   - Slow/timeout: likely behind GFW, use Aliyun mirror as primary
#     with Maven Central as fallback.

set -euo pipefail

TIMEOUT=3   # seconds
THRESHOLD=2.0  # seconds — above this, consider Maven Central "slow"

# Test Maven Central latency via HEAD (no body download)
maven_time=$(curl -sI -o /dev/null -w '%{time_total}' \
  --max-time "$TIMEOUT" \
  'https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.10.10/sbt-launch-1.10.10.jar' \
  2>/dev/null || echo "$TIMEOUT")

is_slow=$(awk "BEGIN { print ($maven_time > $THRESHOLD) ? 1 : 0 }")

if [ "$is_slow" = "1" ]; then
  # Maven Central is slow — use Aliyun as primary
  echo "https://maven.aliyun.com/repository/public|https://repo1.maven.org/maven2"
else
  # Maven Central is fast — use it directly
  echo "https://repo1.maven.org/maven2"
fi
