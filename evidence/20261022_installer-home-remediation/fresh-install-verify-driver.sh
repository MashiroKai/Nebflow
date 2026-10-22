#!/bin/bash
# 再生治本批 · 最终验证：沙箱 HOME 全新安装双变体（默认 ~/.stand / STAND_HOME 变体）
# 断言：沙箱 HOME 内零 .nebflow 触碰；落点全部自 base 派生；装出的 CLI 可跑且仍零 legacy 触碰。
set -u
WT="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-db64867a"
INSTALLER="$WT/release/install.sh"
OUT=/tmp/stand-fresh-verify.log
: > "$OUT"

profiles_for() { # $1=HOME -> print existing/default profile files
  for f in "$1/.zshrc" "$1/.zprofile" "$1/.zshenv" "$1/.bash_profile" "$1/.profile"; do
    [ -f "$f" ] && echo "$f"
  done
}

assert_no_legacy() { # $1=HOME $2=tag
  local home="$1" tag="$2" bad=0
  if [ -e "$home/.nebflow" ]; then echo "[$tag] FAIL: $home/.nebflow exists"; bad=1;
  else echo "[$tag] OK: zero .nebflow under fresh HOME"; fi
  local hits
  hits=$(grep -rn "$home/\.nebflow\|\$HOME/\.nebflow\|~/\.nebflow" "$home/.stand" 2>/dev/null | wc -l | tr -d ' ')
  [ "$hits" = "0" ] && echo "[$tag] OK: zero legacy-root literals in staged tree" || { echo "[$tag] FAIL: $hits legacy literals"; grep -rn "$home/\.nebflow\|\$HOME/\.nebflow\|~/\.nebflow" "$home/.stand" | head -5; bad=1; }
  local plines=0
  for p in $(profiles_for "$home"); do
    if grep -q '\.nebflow/bin' "$p"; then echo "[$tag] FAIL: legacy PATH line in $p"; plines=1; bad=1; fi
  done
  [ "$plines" = "0" ] && echo "[$tag] OK: no legacy PATH entry in profiles"
  return $bad
}

echo "=========== VARIANT A: default (STAND_HOME unset) ==========" | tee -a "$OUT"
SB_A=$(mktemp -d /tmp/stand-fresh-homeA-XXXX)
echo "sandbox HOME_A: $SB_A" | tee -a "$OUT"
env -u STAND_HOME HOME="$SB_A" bash "$INSTALLER" --beta >> "$OUT" 2>&1
rcA=$?
echo "installer exit(A)=$rcA" | tee -a "$OUT"

echo "--- ASSERT A ---" | tee -a "$OUT"
failA=0
for f in "$SB_A/.stand/bin/stand" "$SB_A/.stand/bin/nebflow" "$SB_A/.stand/nebflow.json" "$SB_A/.stand/install-manifest.json"; do
  if [ -f "$f" ]; then echo "[A] OK: $(basename "$f") staged" | tee -a "$OUT"; else echo "[A] FAIL missing: $f" | tee -a "$OUT"; failA=1; fi
done
if grep -qF "\"installDir\": \"$SB_A/.stand/bin\"" "$SB_A/.stand/install-manifest.json" 2>/dev/null; then
  echo "[A] OK: manifest installDir derives from sandbox base" | tee -a "$OUT"
else
  echo "[A] FAIL: manifest installDir mismatch"; cat "$SB_A/.stand/install-manifest.json" 2>/dev/null | head -12 | tee -a "$OUT"; failA=1
fi
pathline_ok=0
for p in $(profiles_for "$SB_A"); do
  if grep -qF "export PATH=\"$SB_A/.stand/bin:\$PATH\"" "$p"; then pathline_ok=1; echo "[A] OK: PATH line from base in $p" | tee -a "$OUT"; fi
done
[ "$pathline_ok" = "1" ] || { echo "[A] FAIL: no derived PATH line found in any profile"; failA=1; }
assert_no_legacy "$SB_A" "A"; [ $? -eq 0 ] || failA=1

echo "--- run staged CLI (version) then re-assert ---" | tee -a "$OUT"
vA=$(env -u STAND_HOME HOME="$SB_A" "$SB_A/.stand/bin/stand" version 2>&1 || env -u STAND_HOME HOME="$SB_A" "$SB_A/.stand/bin/stand" --version 2>&1)
echo "[A] CLI version output: $vA" | tee -a "$OUT"
assert_no_legacy "$SB_A" "A-postCLI"; [ $? -eq 0 ] || failA=1
echo "RESULT_A=$([ $failA -eq 0 ] && echo PASS || echo FAIL)" | tee -a "$OUT"

echo "" | tee -a "$OUT"
echo "=========== VARIANT B: STAND_HOME override ==========" | tee -a "$OUT"
SB_B=$(mktemp -d /tmp/stand-fresh-homeB-XXXX)
echo "sandbox HOME_B: $SB_B (STAND_HOME=$SB_B/base)" | tee -a "$OUT"
env HOME="$SB_B" STAND_HOME="$SB_B/base" bash "$INSTALLER" --beta >> "$OUT" 2>&1
rcB=$?
echo "installer exit(B)=$rcB" | tee -a "$OUT"

echo "--- ASSERT B ---" | tee -a "$OUT"
failB=0
if [ -f "$SB_B/base/bin/stand" ]; then echo "[B] OK: stand staged under \$STAND_HOME/bin" | tee -a "$OUT"; else echo "[B] FAIL: missing $SB_B/base/bin/stand"; failB=1; fi
if [ -e "$SB_B/.stand" ]; then echo "[B] FAIL: \$HOME/.stand created despite STAND_HOME override"; failB=1; else echo "[B] OK: default home NOT created under override"; fi
assert_no_legacy "$SB_B" "B"; [ $? -eq 0 ] || failB=1
pathlineB=0
for p in $(profiles_for "$SB_B"); do
  if grep -qF "export PATH=\"$SB_B/base/bin:\$PATH\"" "$p"; then pathlineB=1; echo "[B] OK: PATH line = \$STAND_HOME/bin" | tee -a "$OUT"; fi
done
[ "$pathlineB" = "1" ] || { echo "[B] FAIL: PATH line not from STAND_HOME"; failB=1; }
echo "RESULT_B=$([ $failB -eq 0 ] && echo PASS || echo FAIL)" | tee -a "$OUT"

echo "" | tee -a "$OUT"
echo "=========== FINAL ==========" | tee -a "$OUT"
echo "rcA=$rcA rcB=$rcB RESULT_A=$( [ $failA -eq 0 ] && echo PASS || echo FAIL ) RESULT_B=$( [ $failB -eq 0 ] && echo PASS || echo FAIL )" | tee -a "$OUT"
echo "SANDBOXES: A=$SB_A B=$SB_B (kept for inspection)"
echo "DONE-FRESH-INSTALL-VERIFY"
