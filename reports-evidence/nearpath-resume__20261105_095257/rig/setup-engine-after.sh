#!/bin/sh
# setup-engine-after.sh — 为「修复后」A/B 半边准备隔离引擎（不碰工作区、不碰生产 6138）。
#
# 与 nearpath-fire 批的 setup-engine.sh 同构，差别只有两处：
#   1) 取树的 ref 由 HEAD 改为调用方给的 ref（本次 = 修复已入 main 的 61b68fed7）；
#   2) 产物落到独立目录 engine-src-after / classpath-after.txt，**不覆盖** fire 批的台子产物。
#
# 用法：sh setup-engine-after.sh [ref] [worktree-path]
set -eu
REF="${1:-HEAD}"
WT="${2:-/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-local-path-resume}"
RIG_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT=/tmp/nearpath-fire
mkdir -p "$ROOT"

echo "[setup-after] export $REF of $WT"
git -C "$WT" rev-parse "$REF"
rm -rf "$ROOT/engine-src-after"
mkdir -p "$ROOT/engine-src-after"
git -C "$WT" archive "$REF" | tar -x -C "$ROOT/engine-src-after"

echo "[setup-after] ws.js sha256（被 A/B 的源文件；after 侧就是它）"
shasum -a 256 "$ROOT/engine-src-after/src/main/resources/web/js/ws.js"
echo "[setup-after] 身份像素臂是否在树里（修复标志，grep 应有命中）"
grep -c "loopback-pixel" "$ROOT/engine-src-after/src/main/scala/nebflow/gateway/HealthRoutes.scala" || true

echo "[setup-after] sbt compile + classpath export（冷启动数分钟）"
cd "$ROOT/engine-src-after"
sbt -batch "show Compile/fullClasspath" > "$ROOT/cp-after-raw.log" 2>&1 || {
  echo "[setup-after] sbt failed — tail:"; tail -40 "$ROOT/cp-after-raw.log"; exit 1; }
node "$RIG_DIR/extract-cp.mjs" "$ROOT/cp-after-raw.log" "$ROOT/classpath-after.txt"

echo "[setup-after] done. engine-classes:"
ls -d "$ROOT"/engine-src-after/target/scala-*/classes
