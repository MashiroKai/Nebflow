#!/bin/bash
# 60-qa-container-e2e.sh — PoC-d：前端 QA 隔离实例容器化全链（验收点①②④⑤⑥⑦ 脚本固化）
#   链路：fat JAR（PoC-a 容器内 assembly 产物）→ 容器隔离实例（NEBFLOW_HOME=容器内路径
#   + 发布端口，标准配方 W3 容器形态）→ 宿主探活 → verify-web-assets 资产断言 →
#   宿主 playwright 截图（A7 先行形态）→ docker rm -f 零残留断言 → 宿主 8080 无恙断言。
# 复现：bash scripts/sandbox-poc/60-qa-container-e2e.sh   （前置：50-sbt-matrix 已产出 assembly）
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh

TSV="$RESULTS/d-qa-e2e.tsv"
: > "$TSV"
tsv_append "$TSV" "phase	ms	note"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL: $1"; }
timed() { # timed <phase> <note> <cmd...>
  local ph="$1" note="$2"; shift 2
  local t0 t1 rc
  t0=$(now); set +e; "$@" >/dev/null 2>&1; rc=$?; set -e; t1=$(now)
  tsv_append "$TSV" "$ph	$(elapsed_ms "$t0" "$t1")	$note"
  return $rc
}

# ── 产物与端口 pre-flight ──
# >>> WINSORT-BEGIN v1 (version-order jar pick) >>>
# Name order != version order: the date scheme deliberately strips leading zeros
# (Windows version fields reject them), so as plain strings "2026.10.5" sorts
# BEFORE "2026.9.19". Rank the candidates by the parsed numeric tuple and take
# the max. Version core = the SAME contract as packaging/app-version.sh:21
#   ([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(-beta\.[0-9]+)?
# The -beta.N tail is the same-day sequence (O-3), i.e. the tuple's 4th field.
# Legacy semver shapes (1.4.1-beta.56) are ranked by the same 4-field tuple, so
# the order is total and deterministic. A name that parses as neither is
# EXCLUDED - deliberately NO silent fall back to name order: the caller's
# existing "jar not found" error path then fires.
_winsort_jar_key() { # <jar path> -> fixed-width numeric key (year,month,day,seq); rc=1 if unparseable
  local _base="${1:-}" _re
  _base="${_base##*/}"
  _re='-assembly-([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(-beta\.([0-9]+))?\.jar$'
  if [[ $_base =~ $_re ]]; then
    printf '%04d%03d%03d%06d' "$((10#${BASH_REMATCH[1]}))" "$((10#${BASH_REMATCH[2]}))" "$((10#${BASH_REMATCH[3]}))" "$((10#${BASH_REMATCH[5]:-0}))"
    return 0
  fi
  _re='-assembly-([0-9]+)\.([0-9]+)\.([0-9]+)(-beta\.([0-9]+))?\.jar$'
  if [[ $_base =~ $_re ]]; then
    printf '%04d%03d%03d%06d' "$((10#${BASH_REMATCH[1]}))" "$((10#${BASH_REMATCH[2]}))" "$((10#${BASH_REMATCH[3]}))" "$((10#${BASH_REMATCH[5]:-0}))"
    return 0
  fi
  return 1
}
_winsort_pick_newest() { # stdin: one candidate path per line -> prints the newest one
  local _cand _key _best='' _best_key=''
  while IFS= read -r _cand; do
    [ -n "$_cand" ] || continue
    _key=$(_winsort_jar_key "$_cand") || continue
    if [ -z "$_best_key" ] || [ "$_key" \> "$_best_key" ]; then
      _best="$_cand"; _best_key="$_key"
    fi
  done
  [ -n "$_best" ] && printf '%s\n' "$_best"
  return 0
}
# <<< WINSORT-END v1 <<<

JAR="$(ls "$WT"/target/scala-*/[a-z]*assembly*.jar 2>/dev/null | _winsort_pick_newest || true)"
[ -n "$JAR" ] || JAR="$(ls "$WT"/target/scala-*/*.jar 2>/dev/null | grep -v sources | _winsort_pick_newest || true)"
[ -n "$JAR" ] || { echo "FAIL: assembly jar 不存在（先跑 50-sbt-matrix 的 a.ctbind.assembly）"; exit 1; }
echo "[d] jar: $(basename "$JAR")"

PORT=""
for p in 8098 8099 8093 8092; do
  if ! lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then PORT="$p"; break; fi
done
[ -n "$PORT" ] || { echo "FAIL: 无空闲发布端口"; exit 1; }
echo "[d] published port: $PORT"

HOST8080_BEFORE=$(lsof -nP -iTCP:8080 -sTCP:LISTEN | tail -1 | awk '{print $1" "$2}')
echo "[d] host:8080 before: $HOST8080_BEFORE"

# ── 隔离 home（凭据不打印、用毕即焚） ──
STAGE=/tmp/nb-sbx-poc/qa-home
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp "$HOME/.nebflow/nebflow.json" "$STAGE/" 2>/dev/null || true
cp "$HOME/.nebflow/auth.json" "$STAGE/" 2>/dev/null || true

cleanup() {
  docker rm -f "$NB-qa" >/dev/null 2>&1 || true
  rm -rf "$STAGE"
}
trap cleanup EXIT

# ── 容器实例 ──
docker rm -f "$NB-qa" >/dev/null 2>&1 || true
timed create "容器创建" docker create --name "$NB-qa" -p "127.0.0.1:$PORT:$PORT" -v "$WT:$WT:ro" "$IMG_UB" sleep infinity || bad "create"
docker cp "$STAGE" "$NB-qa:/tmp/"    # → /tmp/qa-home（容器 fs，rm 即焚）
timed start "容器启动" docker start "$NB-qa" || bad "start"
timed exec_gateway "容器内起隔离实例(detached)" docker exec -d \
  -e NEBFLOW_HOME=/tmp/qa-home -e NEBFLOW_GATEWAY_PORT="$PORT" "$NB-qa" \
  sh -c "java --add-opens java.base/java.lang=ALL-UNNAMED -Xmx2g -jar '$JAR' --port '$PORT' > /tmp/qa-gateway.log 2>&1"

# ── 健康探活（宿主打发布端口；容器内监听零冲突的对照面） ──
t0=$(now); READY=0
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/api/health" 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then READY=1; break; fi
  sleep 2
done
t1=$(now)
tsv_append "$TSV" "health_ready	$(elapsed_ms "$t0" "$t1")	http=$code after $i polls"
if [ "$READY" = "1" ]; then ok "发布端口健康探活 200（$(elapsed_ms "$t0" "$t1")ms, ${i}x2s）"; else
  bad "健康探活失败（http=$code）——容器日志尾部："
  docker exec "$NB-qa" sh -c 'tail -30 /tmp/qa-gateway.log' 2>/dev/null || true
fi

# ── 资产断言（既有合并关卡脚本指向发布端口；遍历 worktree 源树对实例断言 200） ──
if (cd "$WT" && node scripts/verify-web-assets.mjs "http://127.0.0.1:$PORT" > "$LOGDIR/d-verify-web-assets.log" 2>&1); then
  ok "verify-web-assets：全部静态资产经发布端口 200"
else
  bad "verify-web-assets 存在不可达资产（$LOGDIR/d-verify-web-assets.log）"
  grep -E 'FAIL|✗|non-200' "$LOGDIR/d-verify-web-assets.log" | head -5 || true
fi

# ── 宿主侧 playwright 截图（A7 先行形态） ──
if node "$POC_DIR/61-qa-screenshot.cjs" "http://127.0.0.1:$PORT" "$RESULTS" > "$LOGDIR/d-screenshot.log" 2>&1; then
  ok "playwright 截图验收：$(grep -o '"status":[0-9]*' "$LOGDIR/d-screenshot.log" | head -1) $(ls "$RESULTS"/d-qa-root*.png 2>/dev/null | wc -l | tr -d ' ') 张"
else
  bad "playwright 截图失败"; tail -5 "$LOGDIR/d-screenshot.log"; fi

# ── kill 链：docker rm -f = 进程树全灭（T5 语义、W4 消失面） ──
timed destroy "docker rm -f（容器销毁=进程树全灭）" docker rm -f "$NB-qa"
docker inspect "$NB-qa" >/dev/null 2>&1 && bad "容器残留" || ok "容器已销毁（inspect 404）"
lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 && bad "发布端口未释放" || ok "发布端口 :$PORT 已释放"

# ── 宿主 8080 无恙（红线自检收口） ──
HOST8080_AFTER=$(lsof -nP -iTCP:8080 -sTCP:LISTEN | tail -1 | awk '{print $1" "$2}')
[ "$HOST8080_BEFORE" = "$HOST8080_AFTER" ] && ok "宿主 :8080 全程无扰（${HOST8080_AFTER}）" || bad "宿主 :8080 变化：${HOST8080_BEFORE} → ${HOST8080_AFTER}"
hcode=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8080/api/health 2>/dev/null || echo 000)
[ "$hcode" = "200" ] && ok "宿主网关健康 200" || bad "宿主网关健康 http=$hcode"

{
  echo "# PoC-d 前端 QA 容器化全链（port=$PORT, jar=$(basename "$JAR")）"
  echo
  echo "| phase | ms | note |"
  echo "|---|---|---|"
  tail -n +2 "$TSV" | awk -F'\t' '{printf "| %s | %s | %s |\n", $1, $2, $3}'
  echo
  echo "PASS=$PASS FAIL=$FAIL"
} > "$RESULTS/d-qa-e2e.md"
echo "[d] PASS=$PASS FAIL=$FAIL  md=$RESULTS/d-qa-e2e.md"
[ "$FAIL" = "0" ]
