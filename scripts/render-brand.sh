#!/usr/bin/env bash
# render-brand.sh — re-render the brand blocks inside release/install.* and
# release/uninstall.* from the repo-root brand.conf (L2 rebrand, batch 2).
#
# The brand blocks are the ONLY place these scripts carry brand values; the
# renderer rewrites the block assignments in place, leaving everything else
# byte-identical. Idempotent: rendering with the current brand.conf values
# produces zero diff (asserted by the batch-2 acceptance).
#
# Quoting styles match the blocks as authored: sh blocks assign unquoted
# (PRODUCT_NAME=Nebflow), ps1 blocks assign double-quoted ($ProductName = "Nebflow").
#
# Usage: scripts/render-brand.sh [--check]
#   (no flag)  rewrite the blocks in place
#   --check    verify the files are up to date WITHOUT touching them:
#              render a copy, diff against the original, exit 1 + diff if stale
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="$ROOT/brand.conf"
CHECK=0
[[ "${1:-}" == "--check" ]] && CHECK=1

# Parser parity with project/Branding.scala and runtime
# nebflow.core.Branding.parseBrandConf: `key = value`, full-line '#'
# comments, ` # ` starts an inline comment, trailing blanks trimmed.
brand_value() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$CONF" | sed 's/[[:space:]]#.*$//; s/[[:space:]]*$//' | head -1
}

PRODUCT_NAME="$(brand_value productName)"
LOWER_NAME="$(brand_value lowerName)"
COS_BUCKET="$(brand_value cosBucket)"
GH_ORG="$(brand_value githubOrg)"
GH_REPO="$(brand_value githubRepo)"
HOME_DIR="$(brand_value homeDirName)"
CONFIG_FILE="$(brand_value configFileName)"

fail() { echo "render-brand.sh: $*" >&2; exit 1; }
[[ -n "$PRODUCT_NAME" && -n "$LOWER_NAME" && -n "$COS_BUCKET" && -n "$GH_ORG" && -n "$GH_REPO" && -n "$HOME_DIR" && -n "$CONFIG_FILE" ]] \
  || fail "brand.conf is missing one of: productName lowerName cosBucket githubOrg githubRepo homeDirName configFileName"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Render sh-style blocks (unquoted assignments).
render_sh() { # file label
  local src="$ROOT/release/$1" out="$TMP/$1"
  cp "$src" "$out"
  sed -i '' \
    -e "s|^PRODUCT_NAME=.*\$|PRODUCT_NAME=$PRODUCT_NAME|" \
    -e "s|^LOWER_NAME=.*\$|LOWER_NAME=$LOWER_NAME|" \
    -e "s|^COS_BUCKET=.*\$|COS_BUCKET=$COS_BUCKET|" \
    -e "s|^GH_ORG=.*\$|GH_ORG=$GH_ORG|" \
    -e "s|^GH_REPO=.*\$|GH_REPO=$GH_REPO|" \
    -e "s|^HOME_DIR=.*\$|HOME_DIR=$HOME_DIR|" \
    -e "s|^CONFIG_FILE=.*\$|CONFIG_FILE=$CONFIG_FILE|" \
    -e "s|^WRAPPER_NAME=.*\$|WRAPPER_NAME=$LOWER_NAME|" \
    "$out"
  report "$src" "$out" "$2"
}

# Render ps1-style blocks (double-quoted assignments).
render_ps() { # file label
  local src="$ROOT/release/$1" out="$TMP/$1"
  cp "$src" "$out"
  sed -i '' \
    -e "s|^\\\$ProductName = .*\$|\$ProductName = \"$PRODUCT_NAME\"|" \
    -e "s|^\\\$LowerName = .*\$|\$LowerName = \"$LOWER_NAME\"|" \
    -e "s|^\\\$CosBucket = .*\$|\$CosBucket = \"$COS_BUCKET\"|" \
    -e "s|^\\\$GhOrg = .*\$|\$GhOrg = \"$GH_ORG\"|" \
    -e "s|^\\\$GhRepo = .*\$|\$GhRepo = \"$GH_REPO\"|" \
    -e "s|^\\\$HomeDir = .*\$|\$HomeDir = \"$HOME_DIR\"|" \
    -e "s|^\\\$ConfigFile = .*\$|\$ConfigFile = \"$CONFIG_FILE\"|" \
    -e "s|^\\\$WrapperName = .*\$|\$WrapperName = \"$LOWER_NAME\"|" \
    "$out"
  report "$src" "$out" "$2"
}

report() { # src rendered label
  local label="$3"
  if [[ $CHECK -eq 1 ]]; then
    if ! diff -u "$1" "$2" >/dev/null; then
      echo "FAIL: $label brand block is stale (would render):" >&2
      diff -u "$1" "$2" >&2 || true
      exit 1
    fi
    echo "OK: $label (up to date)"
  else
    mv "$2" "$1"
    echo "OK: $label (rendered)"
  fi
}

render_sh install.sh "release/install.sh"
render_sh uninstall.sh "release/uninstall.sh"
render_ps install.ps1 "release/install.ps1"
render_ps uninstall.ps1 "release/uninstall.ps1"
echo "Done${CHECK:+ (--check)}: brand blocks consistent with brand.conf."
