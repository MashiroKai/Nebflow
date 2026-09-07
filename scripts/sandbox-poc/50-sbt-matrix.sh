#!/bin/bash
# 50-sbt-matrix.sh — PoC-a：sbt 真实负载 VirtioFS 三臂实测（A6 target-volume 裁定数据）
#   臂1 host-bare     ：宿主裸跑（APFS，/tmp 私有缓存——不污染宿主 ~/.sbt 等）
#   臂2 ct-bind-target：容器 bind mount 工作区，target/ 落 VirtioFS（与源码同 bind）
#   臂3 ct-vol-target ：容器 bind mount 工作区 + named volume 盖 target/（A6 候选形态）
#   附臂 ct-alpine    ：alpine/musl 镜像同负载（A5 musl 兼容与性能数据；compile+testcompile+testonly）
# 公平性：三臂同 sbt 1.10.10 / JDK21(容器) vs JDK23(宿主，环境差异记录在案)；
#         缓存卷从宿主私有缓存 seed（两容器臂 warm 相同）；每臂均 clean 起跑（zinc 从零）。
# 写入边界：仅写工作区 target/（bind 臂宿主可见；vol 臂在卷内）与 /tmp 私有缓存/缓存卷。
# 产物：results/a-sbt-matrix.tsv；宿主私有缓存 /tmp/nb-sbx-poc/hostcache（复验可复用）
# 复现：bash scripts/sandbox-poc/50-sbt-matrix.sh
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh

REPOS="$(poc_repos)"
HOST_CACHE=/tmp/nb-sbx-poc/hostcache
CACHE_VOL="$NB-cache"
TGT_VOL="$NB-tgt"
CTR="$NB-matrix"
TSV="$RESULTS/a-sbt-matrix.tsv"
[ -f "$TSV" ] || tsv_append "$TSV" "phase	wall_s	rc"
TEST_SUBSET="${TEST_SUBSET:-nebflow.core.sandbox.SandboxSpec nebflow.core.tools.ShellStuckDetectorSpec nebflow.agent.EmptyShellNotifySpec}"
# 注意：-batch / --no-colors 是 sbt 脚本层参数——容器内走 launcher jar 直连不识别
# （PoC 实证：error Expected 'addPluginSbtFile'）——颜色/supershell 关闭统一走 SBT_OPTS sysprops，两臂一致。
SBT_ARGS=""
HOST_SBT_OPTS="-Dsbt.global.base=$HOST_CACHE/sbt-global -Dsbt.boot.directory=$HOST_CACHE/sbt-boot -Dsbt.supershell=false -Dsbt.color=false -Dsbt.log.noformat=true -Xmx2g"
CT_SBT_OPTS="-Dsbt.global.base=/cache/sbt-global -Dsbt.boot.directory=/cache/sbt-boot -Dsbt.supershell=false -Dsbt.color=false -Dsbt.log.noformat=true -Xmx2g"

mkdir -p "$HOST_CACHE"
df_snapshot "a-start"

phase() { # phase <label> <logfile> <cmd...>
  local label="$1" logfile="$2"; shift 2
  local t0 t1 rc
  t0=$(now)
  set +e; "$@" > "$logfile" 2>&1; rc=$?; set -e
  t1=$(now)
  local w; w=$(python3 -c "print(f'{$t1-$t0:.1f}')")
  tsv_append "$TSV" "$label	$w	$rc"
  echo "[a] $label wall=${w}s rc=$rc log=$logfile"
  # 打点：防停滞看门狗误杀的长任务心跳行
  tail -c 400 "$logfile" | head -3 || true
  return 0
}

host_sbt() { # phase <label> <sbt-args...>
  local label="$1"; shift
  ( cd "$WT" && COURSIER_REPOSITORIES="$REPOS" COURSIER_CACHE="$HOST_CACHE/coursier" \
      SBT_OPTS="$HOST_SBT_OPTS" phase "$label" "$LOGDIR/$label.log" sbt $SBT_ARGS "$@" )
}

ct_exec() { # ct_exec <label> <container> <sbt-args...>
  local label="$1" ctr="$2"; shift 2
  phase "$label" "$LOGDIR/$label.log" docker exec \
    -e COURSIER_REPOSITORIES="$REPOS" -e COURSIER_CACHE=/cache/coursier -e SBT_OPTS="$CT_SBT_OPTS" \
    -e TZ=Asia/Shanghai \
    -w "$WT" "$ctr" sbt $SBT_ARGS "$@"
}

run_suite() { # run_suite <prefix> <mode host|ctbind|ctvol|alpine> <ctr-or-empty>
  local p="$1" mode="$2" ctr="$3"
  case "$mode" in
    host)
      host_sbt "$p.clean-compile" clean "Compile/compile"
      host_sbt "$p.test-compile" "Test/compile"
      host_sbt "$p.test-only" "testOnly $TEST_SUBSET"
      ;;
    ctbind)
      ct_exec "$p.clean-compile" "$ctr" clean "Compile/compile"
      ct_exec "$p.test-compile" "$ctr" "Test/compile"
      ct_exec "$p.test-only" "$ctr" "testOnly $TEST_SUBSET"
      ct_exec "$p.assembly" "$ctr" assembly   # fat JAR → $WT/target（bind 臂宿主可见，PoC-d 用）
      ;;
    ctvol)
      ct_exec "$p.clean-compile" "$ctr" clean "Compile/compile"
      ct_exec "$p.test-compile" "$ctr" "Test/compile"
      ct_exec "$p.test-only" "$ctr" "testOnly $TEST_SUBSET"
      ;;
    alpine)
      ct_exec "$p.clean-compile" "$ctr" clean "Compile/compile"
      ct_exec "$p.test-compile" "$ctr" "Test/compile"
      ct_exec "$p.test-only" "$ctr" "testOnly $TEST_SUBSET"
      ;;
  esac
}

echo "=== 臂1 host-bare（私有缓存冷起：boot/update 计时=真实冷环境拉包数据） ==="
( cd "$WT" && COURSIER_REPOSITORIES="$REPOS" COURSIER_CACHE="$HOST_CACHE/coursier" \
    SBT_OPTS="$HOST_SBT_OPTS" phase "a.host.boot" "$LOGDIR/a.host.boot.log" sbt $SBT_ARGS about )
( cd "$WT" && COURSIER_REPOSITORIES="$REPOS" COURSIER_CACHE="$HOST_CACHE/coursier" \
    SBT_OPTS="$HOST_SBT_OPTS" phase "a.host.update" "$LOGDIR/a.host.update.log" sbt $SBT_ARGS update "Test/update" )
run_suite a.host host ""

echo "=== 缓存卷 seed（宿主私有缓存 → named volume，tar 流式，模型=M2 缓存卷预热） ==="
docker volume rm "$CACHE_VOL" >/dev/null 2>&1 || true
docker volume create "$CACHE_VOL" >/dev/null
t0=$(now)
tar -C "$HOST_CACHE" -c coursier sbt-global sbt-boot 2>/dev/null | docker run --rm -i -v "$CACHE_VOL:/cache" alpine:latest tar -C /cache -xf -
t1=$(now); echo "[a] cache seed $(python3 -c "print(f'{$t1-$t0:.1f}')")s"
docker run --rm -v "$CACHE_VOL:/cache" alpine:latest du -sm /cache | awk '{print "[a] cache volume: "$1"MB"}'

echo "=== 臂2 ct-bind-target（target 落 VirtioFS） ==="
docker rm -f "$CTR" >/dev/null 2>&1 || true
docker run -d --name "$CTR" -v "$WT:$WT" -v "$CACHE_VOL:/cache" -w "$WT" "$IMG_UB" sleep infinity >/dev/null
run_suite a.ctbind ctbind "$CTR"

echo "=== 臂3 ct-vol-target（named volume 盖 target/，A6 候选） ==="
docker rm -f "$CTR" >/dev/null 2>&1 || true
docker volume rm "$TGT_VOL" >/dev/null 2>&1 || true
docker volume create "$TGT_VOL" >/dev/null
docker run -d --name "$CTR" -v "$WT:$WT" -v "$TGT_VOL:$WT/target" -v "$CACHE_VOL:/cache" -w "$WT" "$IMG_UB" sleep infinity >/dev/null
run_suite a.ctvol ctvol "$CTR"

echo "=== 附臂 ct-alpine（musl 兼容 + 性能） ==="
docker rm -f "$CTR" >/dev/null 2>&1 || true
docker run -d --name "$CTR" -v "$WT:$WT" -v "$CACHE_VOL:/cache" -w "$WT" "$IMG_AL" sleep infinity >/dev/null
run_suite a.alpine alpine "$CTR"

docker rm -f "$CTR" >/dev/null 2>&1 || true
df_snapshot "a-end"

echo "[a] done. tsv=$TSV"
echo "--- 汇总 ---"
cat "$TSV"
