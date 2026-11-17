#!/usr/bin/env bash
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd -P)
tmp=$(mktemp -d /tmp/stand-ref-test.XXXXXX)
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
printf '#!/bin/sh\n[ "$1" = -J-Xmx4g ] && [ "$2" = -J-XX:MaxMetaspaceSize=2g ] && [ "$3" = compile ] || exit 1\nprintf "MOCK sbt compile\\n"\n' > "$tmp/bin/sbt"
chmod +x "$tmp/bin/sbt"
export PATH="$tmp/bin:$PATH" COLDSTART_REPO="$base"
output=$(bash "$base/scripts/coldstart-ref.sh" --ref HEAD --port 39127 --home "$tmp/home" --dry-run)
printf '%s\n' "$output"
case "$output" in *'MOCK sbt compile'*'instance not started'*) :;; *) exit 1;; esac
[ ! -e "$tmp/home" ]
if bash "$base/scripts/coldstart-ref.sh" --ref HEAD --port 6138 --home "$tmp/home" --dry-run; then exit 1; fi
if bash "$base/scripts/coldstart-ref.sh" --ref not-a-real-ref --port 39127 --home "$tmp/home" --dry-run; then exit 1; fi
echo 'mock dry-run and refusal tests passed'
