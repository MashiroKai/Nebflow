#!/usr/bin/env bash
# Build a macOS .dmg of Nebflow from the sbt-assembly fat jar via jpackage.
#
# Usage: packaging/build-dmg.sh [--jar-dir DIR] [--out DIR]
#   --jar-dir  directory containing nebflow-assembly-*.jar (default target/scala-3.5.2)
#   --out      output directory for the .dmg (default build/dist)
#
# Requirements: JDK 17+ with jpackage on PATH (or JAVA_HOME set), fat jar built
# (sbt assembly). No signing — jpackage ad-hoc signs automatically. First launch
# on macOS 15+ requires System Settings > Privacy & Security approval.
#
# Version derivation (jpackage --app-version must be numeric dotted):
#   date scheme  2026.08.15[-beta.N] → 2026.8.15   (leading zeros stripped)
#   semver       1.4.1[-beta.N]      → 1.4.1       (suffix dropped)
set -euo pipefail

JAR_DIR="target/scala-3.5.2"
OUT="build/dist"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar-dir) JAR_DIR="$2"; shift 2 ;;
    --out)     OUT="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

JAR=$(ls "$JAR_DIR"/nebflow-assembly-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
  echo "ERROR: no nebflow-assembly-*.jar in $JAR_DIR — run 'sbt assembly' first." >&2
  exit 1
fi
if ! command -v jpackage >/dev/null 2>&1; then
  echo "ERROR: jpackage not on PATH — install JDK 17+ or set JAVA_HOME." >&2
  exit 1
fi

RAW_VERSION=$(cat VERSION)
# Numeric app-version for the bundle (see header).
if [[ "$RAW_VERSION" =~ ^([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(-beta\.[0-9]+)?$ ]]; then
  APP_VERSION="${BASH_REMATCH[1]}.$((10#${BASH_REMATCH[2]})).$((10#${BASH_REMATCH[3]}))"
elif [[ "$RAW_VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
  APP_VERSION="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
else
  echo "WARN: unparsable VERSION '$RAW_VERSION', falling back to 1.0.0" >&2
  APP_VERSION="1.0.0"
fi

# jpackage copies the ENTIRE --input dir into the app bundle — stage a clean
# dir with only the fat jar so target/classes etc. never leak in.
STAGE="build/jpackage-input"
RUNTIME="build/runtime"
rm -rf "$STAGE" "$OUT" "$RUNTIME"
mkdir -p "$STAGE" "$OUT"
cp "$JAR" "$STAGE/"

echo "  jar:        $JAR"
echo "  VERSION:    $RAW_VERSION (app-version $APP_VERSION)"

# Trimmed runtime: explicit module list (see jlink-modules.txt) keeps the dmg
# ~40MB under the full default java.se set. --runtime-image gives full control
# (no union with jpackage defaults).
MODULES=$(grep -v '^#' packaging/jlink-modules.txt | tr -d '[:space:]' | tr -d '\n')
jlink \
  --add-modules "$MODULES" \
  --strip-debug --no-man-pages --no-header-files --compress zip-6 \
  --output "$RUNTIME"

jpackage \
  --name Nebflow \
  --type dmg \
  --input "$STAGE" \
  --main-jar "$(basename "$JAR")" \
  --main-class nebflow.Main \
  --arguments --server \
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
  --java-options "-Xmx1g" \
  --runtime-image "$RUNTIME" \
  --app-version "$APP_VERSION" \
  --mac-package-name Nebflow \
  --dest "$OUT"

ARCH=$(uname -m)
FINAL="$OUT/Nebflow-${RAW_VERSION}-${ARCH}.dmg"
mv "$OUT"/Nebflow-*.dmg "$FINAL"
echo "OK: $FINAL ($(du -h "$FINAL" | cut -f1))"
