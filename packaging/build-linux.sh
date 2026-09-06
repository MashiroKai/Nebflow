#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# [FROZEN 2026-09-06] Desktop packaging sealed (script-install-release v2, batch 5)
# Zero code changes below this header - file kept byte-identical for the
# certificate-era restore. See:
#   - .nebflow/Spec/desktop-trust-and-icp-filing-plan.md  (trust chain / restore gates)
#   - .nebflow/Spec/script-install-release-v2.md section 2.7  (sealing scope)
# CI package jobs (release.yml / auto-release.yml) are disabled via `if: false`.
# Restore = certificates + CI notarytool/signtool integration, then unseal CI.
# G3 audit note: AutoStartService only branches on `.app/Contents` (jpackage
# bundle detection); under the script/jar layout it falls back to the classic
# `java -jar` template - no jpackage launcher hard-coupling, backward
# compatible, no runtime changes needed for the freeze.
# ----------------------------------------------------------------------------
# Build the Linux .deb + app-image tar.gz of Nebflow from the sbt-assembly
# fat jar via jpackage.
#
# Usage: packaging/build-linux.sh [--jar-dir DIR] [--out DIR]
#   --jar-dir  directory containing ${LOWER_NAME}-assembly-*.jar (default target/scala-3.5.2)
#   --out      output directory (default build/dist)
#
# Requirements:
#   - JDK 17+ with jpackage on PATH (or JAVA_HOME set), fat jar built (sbt assembly)
#   - dpkg / dpkg-deb / fakeroot for the .deb target (Debian/Ubuntu:
#     apt-get install dpkg fakeroot) — jpackage's LinuxDebBundler hard-depends on them
#   - MUST run on Linux: jpackage has no cross-platform compilation
#
# Outputs (fixed naming spec, shared with build-dmg.sh / build-msi.sh):
#   build/dist/${PRODUCT_NAME}-${RAW_VERSION}-${ARCH}.deb
#   build/dist/${PRODUCT_NAME}-${RAW_VERSION}-${ARCH}-app-image.tar.gz
set -euo pipefail

# ── Brand values (L2 rebrand): repo-root brand.conf is the only edit point ──
# Parser parity with project/Branding.scala and runtime nebflow.core.Branding:
# `key = value`, full-line '#' comments, ` # ` starts an inline comment.
BRAND_CONF="$(cd "$(dirname "$0")/.." && pwd)/brand.conf"
brand_value() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$BRAND_CONF" | sed 's/[[:space:]]#.*$//; s/[[:space:]]*$//' | head -1
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
if ! command -v fakeroot >/dev/null 2>&1; then
  echo "ERROR: fakeroot not on PATH — jpackage .deb hard-depends on it." >&2
  echo "       apt-get install -y dpkg fakeroot" >&2
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

# Trimmed runtime: same explicit module list as build-dmg.sh / build-msi.sh
# (see packaging/jlink-modules.txt). Shared across the deb and app-image
# invocations via --runtime-image.
# App icon (2026-08-27 desktop-form task): committed under packaging/icons/.
# Regenerate: python3 packaging/gen-icons.py <logo.png> --out packaging/icons
ICON_FILE="packaging/icons/nebflow.png"
if [ ! -f "$ICON_FILE" ]; then
  echo "ERROR: $ICON_FILE missing — regen via packaging/gen-icons.py" >&2
  exit 1
fi

MODULES=$(grep -v '^#' packaging/jlink-modules.txt | tr -d '[:space:]' | tr -d '\n')
jlink \
  --add-modules "$MODULES" \
  --strip-debug --no-man-pages --no-header-files --compress zip-6 \
  --output "$RUNTIME"

# ── .deb ──
jpackage \
  --name "$PRODUCT_NAME" \
  --type deb \
  --input "$STAGE" \
  --main-jar "$(basename "$JAR")" \
  --main-class nebflow.Main \
  --arguments --server \
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
  --java-options "-Xmx1g" \
  --runtime-image "$RUNTIME" \
  --icon "$ICON_FILE" \
  --app-version "$APP_VERSION" \
  --dest "$OUT"

# ── app-image tar.gz (distribution-agnostic fallback) ──
APP_IMAGE_ROOT="build/app-image-root"
rm -rf "$APP_IMAGE_ROOT"
mkdir -p "$APP_IMAGE_ROOT"
jpackage \
  --name "$PRODUCT_NAME" \
  --type app-image \
  --input "$STAGE" \
  --main-jar "$(basename "$JAR")" \
  --main-class nebflow.Main \
  --arguments --server \
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
  --java-options "-Xmx1g" \
  --runtime-image "$RUNTIME" \
  --icon "$ICON_FILE" \
  --dest "$APP_IMAGE_ROOT"

# Normalize arch label for asset naming (uname -m gives x86_64 on Intel).
ARCH=$(uname -m | sed 's/x86_64/x64/')
FINAL_DEB="$OUT/${PRODUCT_NAME}-${RAW_VERSION}-${ARCH}.deb"
FINAL_TGZ="$OUT/${PRODUCT_NAME}-${RAW_VERSION}-${ARCH}-app-image.tar.gz"
mv "$OUT"/*.deb "$FINAL_DEB"
tar -C "$APP_IMAGE_ROOT" -czf "$FINAL_TGZ" "$PRODUCT_NAME"
echo "OK: $FINAL_DEB ($(du -h "$FINAL_DEB" | cut -f1))"
echo "OK: $FINAL_TGZ ($(du -h "$FINAL_TGZ" | cut -f1))"
