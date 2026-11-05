#!/bin/sh
# setup-engine-before.sh — A/B 的「改前」半边：**修复提交的父提交**（567d83f16）的干净树。
#
# 为什么不用 /tmp/nearpath-fire/engine-src：那是 nearpath-fire 批留下的目录，事后被
# 覆盖成了**修复后**的树（本次实测：其中的 ws.js sha256 = 506e489…，含 loopback-pixel
# 腿与 timings 行 ⇒ 用它会得出「改前 WebKit 也命中」的假结论，2026-11-05 已撞到一次）。
# 所以改前必须自建一份，ref 钉死在 567d83f16（= 3858e3eb7 的父提交，近路行为修复前）。
#
# 用法：sh setup-engine-before.sh [ref] [worktree-path]
set -eu
REF="${1:-567d83f16}"
WT="${2:-/Users/kaiyu/Claude code/Stand}"
RIG_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT=/tmp/nearpath-fire
mkdir -p "$ROOT"

echo "[setup-before] export $REF"
git -C "$WT" rev-parse "$REF"
rm -rf "$ROOT/engine-src-before"
mkdir -p "$ROOT/engine-src-before"
git -C "$WT" archive "$REF" | tar -x -C "$ROOT/engine-src-before"

echo "[setup-before] ws.js sha256（改前基线；应 = 172fc7f3f6047e3cc9bc428f2fdfd17da4d4a2bd406bcb7de7b6dd15a272dd5b）"
shasum -a 256 "$ROOT/engine-src-before/src/main/resources/web/js/ws.js"
echo "[setup-before] 身份像素臂应为 0 命中（改前标志）"
grep -c "loopback-pixel" "$ROOT/engine-src-before/src/main/scala/nebflow/gateway/HealthRoutes.scala" || true

echo "[setup-before] sbt compile + classpath export（冷启动数分钟）"
cd "$ROOT/engine-src-before"
sbt -batch "show Compile/fullClasspath" > "$ROOT/cp-before-raw.log" 2>&1 || {
  echo "[setup-before] sbt failed — tail:"; tail -40 "$ROOT/cp-before-raw.log"; exit 1; }
node "$RIG_DIR/extract-cp.mjs" "$ROOT/cp-before-raw.log" "$ROOT/classpath-before.txt"

echo "[setup-before] done. engine-classes:"
ls -d "$ROOT"/engine-src-before/target/scala-*/classes
