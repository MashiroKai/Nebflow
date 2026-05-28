#!/bin/bash
# Upload Nebflow release JAR to Tencent COS
# Usage: ./scripts/cos-upload.sh [version]
#   version: tag name (e.g. v1.00.002). Defaults to latest public release.

set -euo pipefail
cd "$(dirname "$0")/.."

RELEASE_REPO="MashiroKai/Nebflow-Release"
COS_BUCKET="nebflow-releases-1411212853"
COS_REGION="ap-nanjing"

# Resolve version
if [ -n "${1:-}" ]; then
  TAG="$1"
else
  TAG=$(gh release list --repo "$RELEASE_REPO" --limit 1 --json tagName --jq '.[0].tagName')
  if [ -z "$TAG" ]; then
    echo "ERROR: No releases found on $RELEASE_REPO"
    exit 1
  fi
fi

echo "=== Nebflow COS Upload ==="
echo "Version: $TAG"

# Download JAR from public release repo (no auth needed)
JAR_NAME=$(gh release view "$TAG" --repo "$RELEASE_REPO" --json assets --jq '.assets[0].name')
if [ -z "$JAR_NAME" ]; then
  echo "ERROR: No assets found in release $TAG"
  exit 1
fi

LOCAL_JAR="dist/$JAR_NAME"

mkdir -p dist
if [ -f "$LOCAL_JAR" ]; then
  echo "Using existing: $LOCAL_JAR"
else
  echo "Downloading from GitHub Release ($RELEASE_REPO)..."
  gh release download "$TAG" --repo "$RELEASE_REPO" --pattern "$JAR_NAME" --dir dist
fi

FILE_SIZE=$(wc -c < "$LOCAL_JAR" | tr -d ' ')
echo "JAR size: $(numfmt --to=iec $FILE_SIZE 2>/dev/null || echo "${FILE_SIZE} bytes")"

# Upload to COS
echo "Uploading to COS ($COS_REGION)..."
coscmd upload "$LOCAL_JAR" "$JAR_NAME" -f

echo ""
echo "=== Done ==="
coscmd list 2>&1 | grep "$JAR_NAME" || true
echo ""
echo "COS URL: https://${COS_BUCKET}.cos.${COS_REGION}.myqcloud.com/${JAR_NAME}"
