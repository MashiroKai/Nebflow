#!/bin/bash
# msgchip 真实环验收 runner（hostcp-assembly-template.sh 照抄改参，2026-11-01）。
# 口径与模板一致：宿主实载工件现取 + 本支 web 树前置 + **本支 classes 前置**（本批
# 后端改动 = classes 目录影子盖过宿主 jar——与 webroot 前置同一条 classpath 序机制）。
# 端口 ≠8080/≠6138（宿主网关）；收尾只杀自起 PID 且先验身（nebflow.Main）。
set -u

WT="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-447ddb40"
P=18473
TAG=msgchip
TMP="/tmp/qa-$TAG"
HOME_DIR="$TMP/home"
MOCK_PORT=18990

echo "== 0. 前提 =="
# 🔴 不依赖任何在跑进程：classpath = **本支自己**的 runtime fullClasspath（sbt export，
#    与本支 classes 同源同版）——宿主/其它在飞会话的进程零读取零触碰。
FCP_FILE="/tmp/msgchip-fcp.txt"
[ -s "$FCP_FILE" ] || { echo "FATAL: 先跑 sbt -batch --error 'export Runtime/fullClasspath' > $FCP_FILE"; exit 2; }
HOST_CP=$(tail -1 "$FCP_FILE" | tr -d '\n')
echo "HOST_CP_ENTRIES=$(echo "$HOST_CP" | tr ':' '\n' | wc -l | tr -d ' ')"
OCC=$(lsof -nP -iTCP:$P -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')
[ -n "$OCC" ] && { echo "FATAL: 端口 $P 已被占用：$OCC"; exit 2; }
H8080_BEFORE=$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null | sort | tr '\n' ' ')
H6138_BEFORE=$(lsof -nP -iTCP:6138 -sTCP:LISTEN -t 2>/dev/null | sort | tr '\n' ' ')
echo "PORT_${P}_BEFORE=[empty] OK  H8080=[$H8080_BEFORE]  H6138=[$H6138_BEFORE]"

echo "== 1. 本支 classpath 就绪 =="
FIRST_JAR=$(echo "$HOST_CP" | cut -d: -f1)
echo "CP_HEAD=$FIRST_JAR"
[ -d "$FIRST_JAR" ] || { echo "FATAL: classes 目录不存在"; exit 3; }

echo "== 2. 夹具 home 装配（幂等重置） =="
rm -rf "$TMP"; mkdir -p "$TMP"
NEBFLOW_HOME_DIR="$HOME_DIR" MOCK_PORT=$MOCK_PORT node "$WT/tests/fixtures/askuser-refresh/fixture-home.mjs" --provision | tail -3 || exit 4
# 复注 llm.providers（幂等，reinject-llm.mjs）：实拍批 2026-11-01 观察到隔离实例
# 首启后 nebflow.json 的 llm 节消失（boot 读到空 providers ⇒ "No LLM provider
# configured"）；askuser-refresh 批的 spec 腿走 REST turn 直驱同一 home 未复现此
# 丢失，此处按实拍夹具自守口径补写同形 llm 节（与 fixture-home provision 的
# llm.providers.mock 逐字段一致，MOCK_PORT 同源）。⚠️ 不可用 `node - <<EOF` 内联：
# 仓根 package.json type=module 会让 stdin 脚本按 ESM 解析（require 崩）。
node "$WT/tests/fixtures/msg-token-chip/reinject-llm.mjs" "$HOME_DIR/nebflow.json" $MOCK_PORT

echo "== 3. mock LLM 起服 =="
( cd "$TMP" && exec node "$WT/tests/fixtures/msg-token-chip/mock-llm.mjs" $MOCK_PORT "$HOME_DIR" ) > "$TMP/mock.log" 2>&1 &
MOCK_PID=$!
echo "MOCK_PID=$MOCK_PID"
sleep 1

echo "== 4. classpath 组装（webroot + 本支 classes 前置） =="
mkdir -p "$TMP/webroot"
cp -R "$WT/src/main/resources/web" "$TMP/webroot/web"
CLASSES="$WT/target/scala-3.5.2/classes"
[ -d "$CLASSES" ] || { echo "FATAL: 本支 classes 不存在（先 sbt compile）"; exit 5; }
CP="$TMP/webroot:$CLASSES:$HOST_CP"
echo "CP head=$(echo "$CP" | cut -d: -f1-2 | tr ':' ' ')"

cleanup() {
  local rc=$?
  echo "== cleanup (rc=$rc) =="
  kill "$MOCK_PID" 2>/dev/null
  # nebflow.Main 会 fork --daemon-child 子进程持有端口 ⇒ 清理 = spawn PID + lsof 实听 PID
  # 双杀，实听 PID 先验身（命令行必须含本夹具 home，防误伤）。
  local SPID; SPID=$(cat "$TMP/instance.pid" 2>/dev/null || true)
  local LPID; LPID=$(lsof -nP -iTCP:$P -sTCP:LISTEN -t 2>/dev/null | head -1)
  for VICTIM in "$LPID" "$SPID"; do
    [ -z "$VICTIM" ] && continue
    ps -p "$VICTIM" >/dev/null 2>&1 || continue
    local CMD; CMD=$(ps -p "$VICTIM" -o command= 2>/dev/null)
    case "$CMD" in
      *nebflow.Main*"--home $HOME_DIR"*|"*nebflow.Main* --home $HOME_DIR "*|*"--home $HOME_DIR"*)
        echo "kill $VICTIM identity=nebflow.Main@qa-msgchip"
        kill "$VICTIM" 2>/dev/null
        for i in 1 2 3 4 5 6 7 8 9 10; do ps -p "$VICTIM" >/dev/null 2>&1 || break; sleep 1; done
        ps -p "$VICTIM" >/dev/null 2>&1 && { kill -9 "$VICTIM" 2>/dev/null; sleep 1; echo "escalated SIGKILL $VICTIM"; } ;;
      *) echo "PID $VICTIM 身份不符（非本夹具 nebflow.Main）⇒ 不杀" ;;
    esac
  done
  echo "PORT_${P}_AFTER=[$(lsof -nP -iTCP:$P -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')]"
  local H8 H6
  H8=$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null | sort | tr '\n' ' ')
  H6=$(lsof -nP -iTCP:6138 -sTCP:LISTEN -t 2>/dev/null | sort | tr '\n' ' ')
  [ "$H8" = "$H8080_BEFORE" ] && echo "8080 SET UNCHANGED: OK" || echo "8080 SET CHANGED: VIOLATION"
  [ "$H6" = "$H6138_BEFORE" ] && echo "6138 SET UNCHANGED: OK" || echo "6138 SET CHANGED: VIOLATION"
  echo "TMP_KEPT=${TMP}（proof 留证；复跑前 rm -rf ${TMP}）"
}

trap cleanup EXIT INT TERM

echo "== 5. 启动隔离实例 =="
( cd "$TMP" && exec env NEBFLOW_GATEWAY_PORT=$P java --add-opens java.base/java.lang=ALL-UNNAMED \
    -cp "$CP" nebflow.Main --home "$HOME_DIR" --port $P --no-browser start ) > "$TMP/instance.log" 2>&1 &
echo $! > "$TMP/instance.pid"
SPID=$(cat "$TMP/instance.pid")
echo "spawned self PID=$SPID"
OK=0
for i in $(seq 1 30); do
  sleep 2
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$P/" 2>/dev/null || echo 000)
  [ "$code" = "200" ] && { OK=1; echo "heartbeat ${i}: GET / -> $code"; break; }
done
[ "$OK" = "1" ] || { echo "FATAL: 实例未起（instance.log 尾部如下）"; tail -30 "$TMP/instance.log"; exit 6; }
echo "PORT_${P}_LISTENERS=[$(lsof -nP -iTCP:$P -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')] (expect $SPID)"

echo "== 6. 收据 =="
TOKEN=$(cat "$HOME_DIR/auth.json" 2>/dev/null | tr -d '"[:space:]')
echo "TOKEN_FILE=$HOME_DIR/auth.json"
echo "BASE_URL=http://127.0.0.1:$P"
echo "READY=1"
# hold：nebflow.Main fork --daemon-child 后父进程即退 ⇒ 等**实听子进程**退出
# （每 5s 重探 lsof；子进程消失 = 实例终 ⇒ cleanup）。驱动件（real-loop.mjs）
# 另起跑；runner 收 TERM/INT 即 cleanup 双杀。
while true; do
  LPID_NOW=$(lsof -nP -iTCP:$P -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -z "$LPID_NOW" ] && break
  sleep 5
done
echo "== instance exited =="
