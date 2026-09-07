#!/bin/bash
# 90-cleanup.sh — PoC 资源清理与记账收口（红线：自建容器/卷清空；镜像保留并记账净增量）
set -uo pipefail   # best-effort，不因单项缺失中断
cd "$(dirname "$0")"
. lib/common.sh

echo "=== containers ==="
for c in "$NB-load" "$NB-idle" "$NB-task" "$NB-matrix" "$NB-qa"; do
  docker rm -f "$c" >/dev/null 2>&1 && echo "removed: $c" || echo "(absent: $c)"
done

echo "=== volumes ==="
for v in "$NB-cache" "$NB-tgt"; do
  docker volume rm "$v" >/dev/null 2>&1 && echo "removed volume: $v" || echo "(absent volume: $v)"
done

echo "=== residue assert ==="
left=$(docker ps -a --format '{{.Names}}' | grep "^$NB" || true)
if [ -z "$left" ]; then echo "PASS: 零 $NB-*= 容器残留"; else echo "FAIL: 容器残留: $left"; fi
leftv=$(docker volume ls --format '{{.Name}}' | grep "^$NB" || true)
if [ -z "$leftv" ]; then echo "PASS: 零 $NB-*= 卷残留"; else echo "FAIL: 卷残留: $leftv"; fi

echo "=== images（by design 保留：M1 工具链产物，作者可 docker rmi 处置） ==="
docker images "$NB-sbx" --format '{{.Repository}}:{{.Tag}}	{{.Size}}'

df_snapshot "cleanup-end"

echo "=== /tmp 产物处置 ==="
rm -rf /tmp/nb-sbx-poc/qa-home && echo "removed: qa-home staging（含凭据副本，即焚）"
du -sh /tmp/nb-sbx-poc/hostcache "$LOGDIR" 2>/dev/null || true
echo "(hostcache 保留供复验提速：/tmp 层、重启自清；logs 为 PoC 证据保留)"

echo "=== 发布端口复查 ==="
for p in 8098 8099 8093 8092; do
  lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1 && echo "WARN: :$p 仍被占用" || true
done
echo "done."
