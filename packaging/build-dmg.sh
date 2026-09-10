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
# [2026-09-10] JDK baseline 17 -> 21 (declaration only): the requirement text
# below plus a build-machine JDK assertion before jlink (A5 §4.3). Bundle
# layout and packaging flow unchanged.
# ----------------------------------------------------------------------------
# Build a macOS .dmg of Nebflow from the sbt-assembly fat jar via jpackage.
#
# Usage: packaging/build-dmg.sh [--jar-dir DIR] [--out DIR]
#   --jar-dir  directory containing ${LOWER_NAME}-assembly-*.jar (default target/scala-3.5.2)
#   --out      output directory for the .dmg (default build/dist)
#
# Requirements: JDK 21+ with jpackage on PATH (or JAVA_HOME set), fat jar built
# (sbt assembly). No signing — jpackage ad-hoc signs automatically. First launch
# on macOS 15+ requires System Settings > Privacy & Security approval.
#
# Version derivation (jpackage --app-version must be numeric dotted):
#   date scheme  2026.08.15[-beta.N] → 2026.8.15   (leading zeros stripped)
#   semver       1.4.1[-beta.N]      → 1.4.1       (suffix dropped)
set -euo pipefail

# ── 删除守卫（R5）：所有 rm -rf 目标先过 delguard 断言（入参处 + 删除点各一次）──
# 口径与负控入口见 packaging/lib/delguard.sh；DRY_RUN=1 只断言不删。
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/lib/delguard.sh"

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
    --out)
      [ $# -ge 2 ] || { echo "build-dmg.sh: --out requires an argument (e.g. --out build/dist)" >&2; exit 2; }
      OUT="$2"
      # 入参即断言（fail-fast）：非空 / 非 `/` / 非 $HOME / 非用户目录根 / 在允许落点根之下
      delguard_assert_paths "option --out" "$OUT" || { echo "build-dmg.sh: --out target rejected by delguard -> abort" >&2; exit 2; }
      shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

JAR=$(ls "$JAR_DIR"/${LOWER_NAME}-assembly-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
  echo "ERROR: no ${LOWER_NAME}-assembly-*.jar in $JAR_DIR — run 'sbt assembly' first." >&2
  exit 1
fi
if ! command -v jpackage >/dev/null 2>&1; then
  echo "ERROR: jpackage not on PATH — install JDK 21+ or set JAVA_HOME." >&2
  exit 1
fi

RAW_VERSION=$(cat VERSION)
# Numeric app-version for the bundle (shared with build-msi.sh).
APP_VERSION=$(packaging/app-version.sh)

# jpackage copies the ENTIRE --input dir into the app bundle — stage a clean
# dir with only the fat jar so target/classes etc. never leak in.
STAGE="build/jpackage-input"
RUNTIME="build/runtime"
# 删除点二次断言（含 STAGE/RUNTIME 常量）：任一不通过即 exit 2，绝不降级执行
delguard_rm_rf "pre-clean" "$STAGE" "$OUT" "$RUNTIME" || { echo "build-dmg.sh: pre-clean rejected by delguard -> abort" >&2; exit 2; }
mkdir -p "$STAGE" "$OUT"
cp "$JAR" "$STAGE/"

echo "  jar:        $JAR"
echo "  VERSION:    $RAW_VERSION (app-version $APP_VERSION)"

# Trimmed runtime: explicit module list (see jlink-modules.txt) keeps the dmg
# ~40MB under the full default java.se set. --runtime-image gives full control
# (no union with jpackage defaults).
# App icon (2026-08-27 desktop-form task): committed under packaging/icons/.
# Regenerate: python3 packaging/gen-icons.py <logo.png> --out packaging/icons
ICON_FILE="packaging/icons/nebflow.icns"
if [ ! -f "$ICON_FILE" ]; then
  echo "ERROR: $ICON_FILE missing — regen via packaging/gen-icons.py" >&2
  exit 1
fi

# ── Build-machine JDK assertion (A5 §4.3) ───────────────────────────────────
# jlink's runtime image IS the build machine's JDK: building the bundle on 17
# would ship a 17 runtime and re-introduce the HttpClient#close (21+) failure
# inside the product. Assert BEFORE jlink/jpackage does any work.
JAVA_BUILD_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [ ! -x "$JAVA_BUILD_BIN" ]; then
  JAVA_BUILD_BIN="$(command -v java || true)"
fi
JAVA_MAJOR=$("${JAVA_BUILD_BIN:-java}" -XshowSettings:properties -version 2>&1 \
  | awk -F'= *' '/java\.specification\.version/{print $2; exit}')
if [ -z "${JAVA_MAJOR:-}" ]; then
  echo "ERROR: cannot read java.specification.version from ${JAVA_BUILD_BIN:-java} — JDK 21+ is required to build the runtime image." >&2
  exit 1
fi
if [ "$JAVA_MAJOR" -ge 21 ] 2>/dev/null; then :; else
  echo "ERROR: need JDK 21+ to build the runtime image (got $JAVA_MAJOR) — point JAVA_HOME at a JDK 21+ install." >&2
  exit 1
fi

MODULES=$(grep -v '^#' packaging/jlink-modules.txt | tr -d '[:space:]' | tr -d '\n')
jlink \
  --add-modules "$MODULES" \
  --strip-debug --no-man-pages --no-header-files --compress zip-6 \
  --output "$RUNTIME"

jpackage \
  --name "$PRODUCT_NAME" \
  --type dmg \
  --input "$STAGE" \
  --main-jar "$(basename "$JAR")" \
  --main-class nebflow.Main \
  --arguments --server \
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
  --java-options "-Xmx1g" \
  --runtime-image "$RUNTIME" \
  --app-version "$APP_VERSION" \
  --icon "$ICON_FILE" \
  --mac-package-name "$PRODUCT_NAME" \
  --dest "$OUT"

# Normalize arch label for asset naming (uname -m gives x86_64 on Intel macs).
ARCH=$(uname -m | sed 's/x86_64/x64/')
FINAL="$OUT/${PRODUCT_NAME}-${RAW_VERSION}-${ARCH}.dmg"
mv "$OUT"/${PRODUCT_NAME}-*.dmg "$FINAL"
echo "OK: $FINAL ($(du -h "$FINAL" | cut -f1))"
