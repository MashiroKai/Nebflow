#!/usr/bin/env bash
# ============================================================
# Nebflow Coordinator - Foreground runner (dev/testing)
# macOS / Linux
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Find JAR: check local dir, then project assembly output
JAR=""
for candidate in \
  "$SCRIPT_DIR/nebflow.jar" \
  "$SCRIPT_DIR/../../target/scala-3.5.2/nebflow.jar" \
  "$HOME/.nebflow/nebflow.jar"; do
  if [ -f "$candidate" ]; then
    JAR="$candidate"
    break
  fi
done

if [ -z "$JAR" ]; then
  echo "nebflow.jar not found. Build with: sbt assembly"
  exit 1
fi

echo "Starting Nebflow Coordinator (port 9090)..."
echo "JAR: $JAR"
exec java -cp "$JAR" nebflow.coordinator.CoordinatorMain
