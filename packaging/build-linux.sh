#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# [FROZEN 2026-09-06] Desktop packaging sealed (script-install-release v2, batch 5)
# Zero code changes below this header - file kept byte-identical for the
# certificate-era restore. See:
#   - ~/.nebflow/memory/aliyun-icp.md  (ICP baseline; replaces the retired
#     desktop-trust-and-icp-filing-plan.md Spec, untracked 2026-09-08)
#   - ~/.nebflow/memory/script-install-release.md  (script-install ruling;
#     replaces the retired script-install-release-v2.md Spec section 2.7)
# CI package jobs (release.yml / auto-release.yml) are disabled via `if: false`.
# Restore = certificates + CI notarytool/signtool integration, then unseal CI.
# G3 audit note: AutoStartService only branches on `.app/Contents` (jpackage
# bundle detection); under the script/jar layout it falls back to the classic
# `java -jar` template - no jpackage launcher hard-coupling, backward
# compatible, no runtime changes needed for the freeze.
# [2026-09-10] JDK baseline 17 -> 21 (declaration only): the requirement text
# below plus a build-machine JDK assertion before jlink (A5 §4.3). Bundle
# layout and packaging flow unchanged.
# [2026-09-11] R5 delete-guard residue fix (guard-only insertion): lib/delguard.sh
# now blacklist-checks every default allow-root ($PWD, /tmp, $TMPDIR) and the
# allow-root itself before any rm -rf; no change to this script's bundle layout,
# staging or packaging flow.
# [2026-09-13] Spec pointers above repointed to their live landing spots: both
# .nebflow/Spec files were untracked on 2026-09-08 (commit 4f285811), so the
# originals recover via `git show 4f285811^:<path>`. Freeze semantics unchanged.
# [2026-09-17] WINSORT (author ruling, batch winsort): the fat-jar PICK line only
# was unsealed, to select by parsed version order instead of name order (the date
# scheme strips leading zeros, so "2026.10.5" sorts before "2026.9.17" as a
# string). Bundle layout / staging / packaging flow stay frozen and unchanged.
# ----------------------------------------------------------------------------
# Build the Linux .deb + app-image tar.gz of Nebflow from the sbt-assembly
# fat jar via jpackage.
#
# Usage: packaging/build-linux.sh [--jar-dir DIR] [--out DIR]
#   --jar-dir  directory containing ${LOWER_NAME}-assembly-*.jar (default target/scala-3.5.2)
#   --out      output directory (default build/dist)
#
# Requirements:
#   - JDK 21+ with jpackage on PATH (or JAVA_HOME set), fat jar built (sbt assembly)
#   - dpkg / dpkg-deb / fakeroot for the .deb target (Debian/Ubuntu:
#     apt-get install dpkg fakeroot) — jpackage's LinuxDebBundler hard-depends on them
#   - MUST run on Linux: jpackage has no cross-platform compilation
#
# Outputs (fixed naming spec, shared with build-dmg.sh / build-msi.sh):
#   build/dist/${PRODUCT_NAME}-${RAW_VERSION}-${ARCH}.deb
#   build/dist/${PRODUCT_NAME}-${RAW_VERSION}-${ARCH}-app-image.tar.gz
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
      [ $# -ge 2 ] || { echo "build-linux.sh: --out requires an argument (e.g. --out build/dist)" >&2; exit 2; }
      OUT="$2"
      # 入参即断言（fail-fast）：非空 / 非 `/` / 非 $HOME / 非用户目录根 / 在允许落点根之下
      delguard_assert_paths "option --out" "$OUT" || { echo "build-linux.sh: --out target rejected by delguard -> abort" >&2; exit 2; }
      shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

# >>> WINSORT-BEGIN v1 (version-order jar pick) >>>
# Name order != version order: the date scheme deliberately strips leading zeros
# (Windows version fields reject them), so as plain strings "2026.10.5" sorts
# BEFORE "2026.9.17". Rank the candidates by the parsed numeric tuple and take
# the max. Version core = the SAME contract as packaging/app-version.sh:21
#   ([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(-beta\.[0-9]+)?
# The -beta.N tail is the same-day sequence (O-3), i.e. the tuple's 4th field.
# Legacy semver shapes (1.4.1-beta.56) are ranked by the same 4-field tuple, so
# the order is total and deterministic. A name that parses as neither is
# EXCLUDED - deliberately NO silent fall back to name order: the caller's
# existing "jar not found" error path then fires.
_winsort_jar_key() { # <jar path> -> fixed-width numeric key (year,month,day,seq); rc=1 if unparseable
  local _base="${1:-}" _re
  _base="${_base##*/}"
  _re='-assembly-([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(-beta\.([0-9]+))?\.jar$'
  if [[ $_base =~ $_re ]]; then
    printf '%04d%03d%03d%06d' "$((10#${BASH_REMATCH[1]}))" "$((10#${BASH_REMATCH[2]}))" "$((10#${BASH_REMATCH[3]}))" "$((10#${BASH_REMATCH[5]:-0}))"
    return 0
  fi
  _re='-assembly-([0-9]+)\.([0-9]+)\.([0-9]+)(-beta\.([0-9]+))?\.jar$'
  if [[ $_base =~ $_re ]]; then
    printf '%04d%03d%03d%06d' "$((10#${BASH_REMATCH[1]}))" "$((10#${BASH_REMATCH[2]}))" "$((10#${BASH_REMATCH[3]}))" "$((10#${BASH_REMATCH[5]:-0}))"
    return 0
  fi
  return 1
}
_winsort_pick_newest() { # stdin: one candidate path per line -> prints the newest one
  local _cand _key _best='' _best_key=''
  while IFS= read -r _cand; do
    [ -n "$_cand" ] || continue
    _key=$(_winsort_jar_key "$_cand") || continue
    if [ -z "$_best_key" ] || [ "$_key" \> "$_best_key" ]; then
      _best="$_cand"; _best_key="$_key"
    fi
  done
  [ -n "$_best" ] && printf '%s\n' "$_best"
  return 0
}
# <<< WINSORT-END v1 <<<

JAR=$(ls "$JAR_DIR"/${LOWER_NAME}-assembly-*.jar 2>/dev/null | _winsort_pick_newest || true)
if [[ -z "$JAR" ]]; then
  echo "ERROR: no ${LOWER_NAME}-assembly-*.jar in $JAR_DIR — run 'sbt assembly' first." >&2
  exit 1
fi
if ! command -v jpackage >/dev/null 2>&1; then
  echo "ERROR: jpackage not on PATH — install JDK 21+ or set JAVA_HOME." >&2
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
# 删除点二次断言（含 STAGE/RUNTIME 常量）：任一不通过即 exit 2，绝不降级执行
delguard_rm_rf "pre-clean" "$STAGE" "$OUT" "$RUNTIME" || { echo "build-linux.sh: pre-clean rejected by delguard -> abort" >&2; exit 2; }
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
