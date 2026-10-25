#!/bin/bash
# E2E: release.sh 全链真跑(真 sbt assembly + 真 boot-smoke, 沙箱数据根 + 沙箱端口)
set -u
TS="$(date +%Y%m%d-%H%M%S)"
E2E="/tmp/rel-e2e-$TS"; mkdir -p "$E2E"
REPO="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-187d2ff9"
ROOT="$E2E/bin"
V="$(tr -d ' \t\r\n' < "$REPO/VERSION")"
PORT=$((21000 + RANDOM % 5000))
while [ -n "$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null)" ]; do PORT=$((21000 + RANDOM % 5000)); done

echo "== E2E release.sh full chain =="
echo "repo=$REPO root=$ROOT port=$PORT version=$V"

# 沙箱发布根预置: 一个「在产」旧版本作 rollback 落点
mkdir -p "$ROOT/versions/2026.9.19"
printf 'dummy-old-jar\n' > "$ROOT/versions/2026.9.19/stand-assembly-2026.9.19.jar"
printf '2026.9.19\n' > "$ROOT/current-version"

rel() { bash "$REPO/ops/release.sh" "$@" --repo "$REPO" --root "$ROOT"; }

echo
echo "──────────── [1] stage (real assembly + real boot-smoke, expect PASS) ────────────"
rel stage --smoke-port "$PORT"; STAGE_RC=$?
echo ">>> STAGE_RC=$STAGE_RC"

echo
echo "pointer after stage: $(tr -d ' \t\r\n' < "$ROOT/current-version")  (must still be 2026.9.19)"
echo "staged-release.json:"; cat "$ROOT/staged-release.json"

echo
echo "──────────── [2] approve (real promotion probe, expect switch + rollback line) ────────────"
PORT2=$((21000 + RANDOM % 5000))
while [ -n "$(lsof -ti tcp:"$PORT2" -sTCP:LISTEN 2>/dev/null)" ] || [ "$PORT2" = "$PORT" ]; do PORT2=$((21000 + RANDOM % 5000)); done
rel approve --smoke-port "$PORT2"; APPROVE_RC=$?
echo ">>> APPROVE_RC=$APPROVE_RC"
echo "pointer after approve: $(tr -d ' \t\r\n' < "$ROOT/current-version")  (must be $V)"
echo "previous-version: $(tr -d ' \t\r\n' < "$ROOT/previous-version" 2>/dev/null)  (must be 2026.9.19)"

echo
echo "──────────── [3] rollback (expect pointer restored) + idempotent ────────────"
rel rollback; RB_RC=$?
echo ">>> ROLLBACK_RC=$RB_RC"
echo "pointer after rollback: $(tr -d ' \t\r\n' < "$ROOT/current-version")  (must be 2026.9.19)"
rel rollback; RB2_RC=$?
echo ">>> ROLLBACK2_RC=$RB2_RC (idempotent, expect 0)"
echo "pointer after 2nd rollback: $(tr -d ' \t\r\n' < "$ROOT/current-version")"

echo
echo "──────────── [4] bad jar (truncated) stage -> FAIL, zero candidates ────────────"
BADROOT="$E2E/bin2"
BADREPO="$E2E/badrepo"; mkdir -p "$BADREPO"
( cd "$BADREPO" && git init -q && git config user.email t@t && git config user.name t \
  && printf '%s\n' "$V" > VERSION && git add VERSION && git commit -qm v ) >/dev/null 2>&1
REALJAR="$REPO/target/scala-3.5.2/stand-assembly-$V.jar"
PORT3=$((21000 + RANDOM % 5000))
while [ -n "$(lsof -ti tcp:"$PORT3" -sTCP:LISTEN 2>/dev/null)" ] || [ "$PORT3" = "$PORT" ] || [ "$PORT3" = "$PORT2" ]; do PORT3=$((21000 + RANDOM % 5000)); done
BADBUILD="mkdir -p \"\$RELEASE_REPO/target/scala-3.5.2\" && head -c 4096 \"$REALJAR\" > \"\$RELEASE_REPO/target/scala-3.5.2/stand-assembly-\$RELEASE_VERSION.jar\""
STAND_RELEASE_BUILD_CMD="$BADBUILD" \
  bash "$REPO/ops/release.sh" stage --repo "$BADREPO" --root "$BADROOT" --smoke-port "$PORT3" --smoke-timeout 20
BAD_RC=$?
echo ">>> BAD_STAGE_RC=$BAD_RC (expect 1)"
echo "bad root versions dir: $(ls "$BADROOT/versions" 2>/dev/null | tr '\n' ' ')(expect empty)"
[ -f "$BADROOT/staged-release.json" ] && echo "!!! staged json must not exist" || echo "bad root staged-release.json: absent (correct)"

echo
echo "──────────── [5] 收尾扫描: 零残留 + 在产无扰 ────────────"
echo "-- sandbox ports listeners left:"
for p in "$PORT" "$PORT2" "$PORT3"; do
  L="$(lsof -ti tcp:"$p" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
  echo "   port $p: ${L:-none}"
done
echo "-- production 6138/8080 untouched check (read-only):"
lsof -ti tcp:6138 -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | sed 's/^/   6138 listeners: /'
lsof -ti tcp:8080 -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | sed 's/^/   8080 listeners: /'
echo "-- live pointer untouched:"
echo "   ~/.stand/bin/current-version = $(tr -d ' \t\r\n' < "$HOME/.stand/bin/current-version")"
echo
echo "== E2E verdicts =="
echo "STAGE_RC=$STAGE_RC APPROVE_RC=$APPROVE_RC ROLLBACK_RC=$RB_RC ROLLBACK2_RC=$RB2_RC BAD_STAGE_RC=$BAD_RC"
