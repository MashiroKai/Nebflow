#!/usr/bin/env bash
# Build the Windows .msi of Nebflow from the sbt-assembly fat jar via jpackage.
#
# Usage: packaging/build-msi.sh [--jar-dir DIR] [--out DIR]
#   --jar-dir  directory containing nebflow-assembly-*.jar (default target/scala-3.5.2)
#   --out      output directory for the .msi (default build/dist)
#
# Requirements: JDK 17+ (jpackage + jlink), WiX 3.x (candle.exe/light.exe) on
# PATH — WiX is a HARD dependency of jpackage msi/exe targets. WiX 3.14 pairs
# with JDK <= 23 (JDK 24+ wants WiX 4/5); install via:
#   choco install wixtoolset --version=3.14.1.2728 -y
# Unsigned msi triggers SmartScreen "unknown publisher" on first install —
# documented on the website download page; Certum OSS cert is the cheap fix.
#
# Runs on windows runners via git-bash and on developer machines via Git Bash.
set -euo pipefail

# ── Brand values (L2 rebrand): repo-root brand.conf is the only edit point ──
# Parser parity with project/Branding.scala and runtime nebflow.core.Branding:
# `key = value`, full-line '#' comments, ` # ` starts an inline comment.
BRAND_CONF="$(cd "$(dirname "$0")/.." && pwd)/brand.conf"
brand_value() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$BRAND_CONF" | sed 's/[[:space:]]#.*$//' | head -1
}
PRODUCT_NAME="$(brand_value productName)"
LOWER_NAME="$(brand_value lowerName)"

JAR_DIR="target/scala-3.5.2"
OUT="build/dist"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar-dir) JAR_DIR="$2"; shift 2 ;;
    --out)     OUT="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

JAR=$(ls "$JAR_DIR"/${LOWER_NAME}-assembly-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
  echo "ERROR: no ${LOWER_NAME}-assembly-*.jar in $JAR_DIR — run 'sbt assembly' first." >&2
  exit 1
fi
if ! command -v jpackage >/dev/null 2>&1; then
  echo "ERROR: jpackage not on PATH — install JDK 17+ or set JAVA_HOME." >&2
  exit 1
fi
if ! command -v candle >/dev/null 2>&1; then
  echo "ERROR: candle.exe (WiX 3.x) not on PATH — jpackage msi hard-depends on WiX." >&2
  echo "       choco install wixtoolset --version=3.14.1.2728 -y" >&2
  exit 1
fi

RAW_VERSION=$(cat VERSION)
APP_VERSION=$(packaging/app-version.sh)

STAGE="build/jpackage-input"
RUNTIME="build/runtime"
rm -rf "$STAGE" "$OUT" "$RUNTIME"
mkdir -p "$STAGE" "$OUT"
cp "$JAR" "$STAGE/"

echo "  jar:        $JAR"
echo "  VERSION:    $RAW_VERSION (app-version $APP_VERSION)"

MODULES=$(grep -v '^#' packaging/jlink-modules.txt | tr -d '[:space:]' | tr -d '\n')
jlink \
  --add-modules "$MODULES" \
  --strip-debug --no-man-pages --no-header-files --compress zip-6 \
  --output "$RUNTIME"

jpackage \
  --name "$PRODUCT_NAME" \
  --type msi \
  --input "$STAGE" \
  --main-jar "$(basename "$JAR")" \
  --main-class nebflow.Main \
  --arguments --server \
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
  --java-options "-Xmx1g" \
  --runtime-image "$RUNTIME" \
  --app-version "$APP_VERSION" \
  --win-menu --win-shortcut --win-dir-chooser \
  --dest "$OUT"

FINAL="$OUT/${PRODUCT_NAME}-${RAW_VERSION}-x64.msi"
mv "$OUT"/${PRODUCT_NAME}-*.msi "$FINAL"
echo "OK: $FINAL"
