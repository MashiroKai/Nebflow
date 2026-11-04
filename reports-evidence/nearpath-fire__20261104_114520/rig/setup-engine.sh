#!/bin/sh
# setup-engine.sh — 为近路实测台准备一份**干净的**隔离引擎（不碰工作区、不碰生产）。
#
# 为什么用 `git archive HEAD` 而不是直接用工作区源码：
#   本批工作区里 src/** 有其它节点在改（2026-11-04 当班实测：NearPathStats.scala
#   未提交且 Compile 失败 `value ip is not a member of com.comcast.ip4s.IpAddress`），
#   直接编译工作区必然失败。台子只要「已提交的 HEAD 引擎」，工作区的 ws.js 与 HEAD
#   逐字节相同（本脚本会打印 sha256 对账）。
#
# 产物：/tmp/nearpath-fire/engine-src（源码树）+ /tmp/nearpath-fire/classpath.txt
#
# 用法：sh setup-engine.sh [worktree-path]
set -eu
WT="${1:-/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-be138470}"
RIG_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT=/tmp/nearpath-fire
mkdir -p "$ROOT"

echo "[setup] export HEAD of $WT"
rm -rf "$ROOT/engine-src"
mkdir -p "$ROOT/engine-src"
git -C "$WT" archive HEAD | tar -x -C "$ROOT/engine-src"

echo "[setup] ws.js sha256 (工作区 vs HEAD 树 — 必须相等 = 台子测的就是本批代码)"
shasum -a 256 "$WT/src/main/resources/web/js/ws.js" "$ROOT/engine-src/src/main/resources/web/js/ws.js"

echo "[setup] sbt compile + classpath export (冷启动数分钟)"
cd "$ROOT/engine-src"
sbt -batch "show Compile/fullClasspath" > "$ROOT/cp-raw.log" 2>&1 || {
  echo "[setup] sbt failed — tail:"; tail -30 "$ROOT/cp-raw.log"; exit 1; }
node "$RIG_DIR/extract-cp.mjs" "$ROOT/cp-raw.log" "$ROOT/classpath.txt"

echo "[setup] done. engine-classes:"
ls -d "$ROOT"/engine-src/target/scala-*/classes
