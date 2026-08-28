#!/usr/bin/env bash
# Attach host-built desktop artifacts to an existing GitHub Release.
#
# Usage: packaging/upload-release-assets.sh v<TAG> [--out DIR]
#   v<TAG>   the release tag (e.g. v2026.08.25) — assets are matched by the
#            fixed naming spec ${PRODUCT_NAME}-${VERSION}-<arch>.{dmg,msi,deb}
#            and ${PRODUCT_NAME}-${VERSION}-<arch>-app-image.tar.gz
#   --out    artifact directory (default build/dist)
#
# Requirements:
#   - gh CLI installed and authenticated (`gh auth login`)
#   - the GitHub Release for v<TAG> already exists (created by the CI
#     auto-release.yml on push to the release branch, or manually)
#   - only uploads artifacts that exist on THIS host — a macOS host uploads
#     the dmg, a Windows host the msi, a Linux host the deb + app-image.
#     The CI matrix builds and attaches the full three-platform set on the
#     release push; this script covers the host-built local artifact (e.g.
#     the "latest version real-run" dmg) and manual backfills.
#
# Gated: the release-stable flow's packager node does NOT run this — it only
# stages the command. Publishing requires the user's release window (no push,
# no publish without explicit instruction).
set -euo pipefail

TAG="${1:?usage: upload-release-assets.sh v<TAG> [--out DIR]}"
VERSION="${TAG#v}"

# ── Brand values (L2 rebrand): repo-root brand.conf is the only edit point ──
BRAND_CONF="$(cd "$(dirname "$0")/.." && pwd)/brand.conf"
brand_value() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$BRAND_CONF" | sed 's/[[:space:]]#.*$//; s/[[:space:]]*$//' | head -1
}
PRODUCT_NAME="$(brand_value productName)"

OUT="build/dist"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

if ! command -v gh >/dev/null 2>&1; then
  echo "ERROR: gh CLI not installed/authenticated." >&2
  exit 1
fi

# Match the fixed naming spec across all platforms (only existing files).
mapfile -t ASSETS < <(
  ls "$OUT"/${PRODUCT_NAME}-${VERSION}-*.dmg \
      "$OUT"/${PRODUCT_NAME}-${VERSION}-*.msi \
      "$OUT"/${PRODUCT_NAME}-${VERSION}-*.deb \
      "$OUT"/${PRODUCT_NAME}-${VERSION}-*-app-image.tar.gz \
      2>/dev/null || true
)
if [[ ${#ASSETS[@]} -eq 0 ]]; then
  echo "ERROR: no artifacts matching ${PRODUCT_NAME}-${VERSION}-* in $OUT — run the platform build script first." >&2
  exit 1
fi

echo "  tag:      $TAG"
for a in "${ASSETS[@]}"; do
  echo "  asset:    $a ($(du -h "$a" | cut -f1))"
done
gh release upload "$TAG" "${ASSETS[@]}" --clobber
echo "OK: uploaded ${#ASSETS[@]} asset(s) to release $TAG"
