#!/usr/bin/env bash
# Build the Windows .msi of Nebflow from the sbt-assembly fat jar via jpackage.
#
# Usage: packaging/build-msi.sh [--jar-dir DIR] [--out DIR] [--stage-only]
#   --jar-dir    directory containing nebflow-assembly-*.jar (default target/scala-3.5.2)
#   --out        output directory for the .msi (default build/dist)
#   --stage-only stop after staging build/jpackage-input (local verification
#                on non-Windows machines — asserts the bundled dependency
#                layout without invoking jpackage/WiX)
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
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$BRAND_CONF" | sed 's/[[:space:]]#.*$//; s/[[:space:]]*$//' | head -1
}
PRODUCT_NAME="$(brand_value productName)"
LOWER_NAME="$(brand_value lowerName)"

JAR_DIR="target/scala-3.5.2"
OUT="build/dist"
STAGE_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar-dir) JAR_DIR="$2"; shift 2 ;;
    --out)     OUT="$2"; shift 2 ;;
    --stage-only) STAGE_ONLY=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

JAR=$(ls "$JAR_DIR"/${LOWER_NAME}-assembly-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
  echo "ERROR: no ${LOWER_NAME}-assembly-*.jar in $JAR_DIR — run 'sbt assembly' first." >&2
  exit 1
fi
if [[ $STAGE_ONLY -eq 0 ]]; then
  if ! command -v jpackage >/dev/null 2>&1; then
    echo "ERROR: jpackage not on PATH — install JDK 17+ or set JAVA_HOME." >&2
    exit 1
  fi
  if ! command -v candle >/dev/null 2>&1; then
    echo "ERROR: candle.exe (WiX 3.x) not on PATH — jpackage msi hard-depends on WiX." >&2
    echo "       choco install wixtoolset --version=3.14.1.2728 -y" >&2
    exit 1
  fi
fi

RAW_VERSION=$(cat VERSION)
APP_VERSION=$(packaging/app-version.sh)

STAGE="build/jpackage-input"
RUNTIME="build/runtime"
rm -rf "$STAGE" "$OUT" "$RUNTIME"
mkdir -p "$STAGE" "$OUT"
cp "$JAR" "$STAGE/"

# ── Bundled Windows dependencies (Team #11, beta.53 gate) ──────────────────
# Pinned versions — bump deliberately and keep in step with
# release/install.ps1 (which installs the same lines for jar installs):
#   MinGit 2.55.0.3 — trimmed Git for Windows for embedding (~38MB zip):
#   git core + bash 5.3 engine (package manifest lists bash 5.3.015-1).
#   VERIFIED LAYOUT 2026-08-28: MinGit ships NO bin/bash.exe — the bash
#   binary is installed as usr/bin/sh.exe (same engine, sh argv0). We copy
#   it to usr/bin/bash.exe at stage time so it runs in full bash mode, and
#   invoke it with -l (login) + MSYSTEM=MINGW64 so /etc/profile assembles
#   the MSYS PATH (mingw64/bin holds git.exe) — see core/tools/shell.scala.
#   https://github.com/git-for-windows/git/releases/download/v2.55.0.windows.3/MinGit-2.55.0.3-64-bit.zip
#   ripgrep 14.1.1 (x86_64-pc-windows-msvc) — single rg.exe.
#   https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-pc-windows-msvc.zip
# jpackage copies --input wholesale into <install>\app\, so staging
# git/ + rg.exe + msi-install.marker here puts them at
# <install>\app\git\bin\bash.exe, <install>\app\rg.exe and
# <install>\app\msi-install.marker — the paths nebflow.core.InstallLayout
# and the bash/rg lookups probe.
CACHE="build/cache"
mkdir -p "$CACHE"

extract_zip() { # extract_zip <zip> <dest-dir>
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$1" -d "$2"
  else
    # Windows git-bash has no unzip; PowerShell Expand-Archive always exists.
    powershell -NoProfile -Command "Expand-Archive -LiteralPath '$1' -DestinationPath '$2' -Force"
  fi
}

MINGIT_TAG="v2.55.0.windows.3"
MINGIT_NAME="MinGit-2.55.0.3-64-bit.zip"
MINGIT_ZIP="$CACHE/$MINGIT_NAME"
if [ ! -s "$MINGIT_ZIP" ]; then
  echo "  downloading MinGit $MINGIT_TAG ..."
  curl -fSL --retry 3 --retry-delay 2 -o "$MINGIT_ZIP" \
    "https://github.com/git-for-windows/git/releases/download/$MINGIT_TAG/$MINGIT_NAME"
fi
rm -rf "$STAGE/git"
extract_zip "$MINGIT_ZIP" "$STAGE/git"
# MinGit ships the bash 5.3 engine only as usr/bin/sh.exe (sh argv0 = POSIX
# mode). Copy it to bash.exe so argv0 selects full bash mode; fail loudly
# BEFORE the msi is built if the pinned layout ever changes — the Scala bash
# lookup probes exactly these paths.
if [ ! -f "$STAGE/git/usr/bin/sh.exe" ]; then
  echo "ERROR: MinGit $MINGIT_TAG is missing usr/bin/sh.exe — layout changed?" \
       "Update packaging/build-msi.sh + nebflow.core.InstallLayout.bundledBash together." >&2
  exit 1
fi
cp "$STAGE/git/usr/bin/sh.exe" "$STAGE/git/usr/bin/bash.exe"

RG_VER="14.1.1"
RG_NAME="ripgrep-$RG_VER-x86_64-pc-windows-msvc.zip"
RG_ZIP="$CACHE/$RG_NAME"
if [ ! -s "$RG_ZIP" ]; then
  echo "  downloading ripgrep $RG_VER ..."
  curl -fSL --retry 3 --retry-delay 2 -o "$RG_ZIP" \
    "https://github.com/BurntSushi/ripgrep/releases/download/$RG_VER/$RG_NAME"
fi
rm -rf "$CACHE/rg-unpack"
extract_zip "$RG_ZIP" "$CACHE/rg-unpack"
RG_EXE=$(find "$CACHE/rg-unpack" -name rg.exe -type f | head -1)
if [ -z "$RG_EXE" ]; then
  echo "ERROR: rg.exe not found inside $RG_NAME — layout changed?" >&2
  exit 1
fi
cp "$RG_EXE" "$STAGE/rg.exe"

# msi form marker: its presence makes RemoteUpdateAction refuse the
# install.ps1 update path (plain-jar form drift).
printf 'installed-by=msi version=%s\n' "$RAW_VERSION" > "$STAGE/msi-install.marker"

echo "  bundled:    git/usr/bin/bash.exe (from sh.exe) + rg.exe + msi-install.marker staged"

if [[ $STAGE_ONLY -eq 1 ]]; then
  echo "OK (--stage-only): staging complete under $STAGE"
  exit 0
fi

echo "  jar:        $JAR"
echo "  VERSION:    $RAW_VERSION (app-version $APP_VERSION)"

# App icon (2026-08-27 desktop-form task): committed under packaging/icons/.
# Regenerate: python3 packaging/gen-icons.py <logo.png> --out packaging/icons
ICON_FILE="packaging/icons/nebflow.ico"
if [ ! -f "$ICON_FILE" ]; then
  echo "ERROR: $ICON_FILE missing — regen via packaging/gen-icons.py" >&2
  exit 1
fi

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
  --icon "$ICON_FILE" \
  --dest "$OUT"

FINAL="$OUT/${PRODUCT_NAME}-${RAW_VERSION}-x64.msi"
mv "$OUT"/${PRODUCT_NAME}-*.msi "$FINAL"
echo "OK: $FINAL"
