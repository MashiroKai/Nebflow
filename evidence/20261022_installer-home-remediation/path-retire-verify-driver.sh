#!/bin/bash
# 决定性面：setup_path 退役 legacy PATH 行 + 新行自 STAND_BASE 派生（复用沙箱 A 重装）
set -u
WT="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-db64867a"
SB=/tmp/stand-fresh-homeA-wPDV
OUT=/tmp/stand-path-retire.log
: > "$OUT"

# 预置 profile：legacy 行（再生根源形态）+ 用户行
cat > "$SB/.zshrc" <<'EOF'
eval "$(direnv hook zsh)"
export PATH="$HOME/.nebflow/bin:$PATH"
alias ll='ls -la'
EOF
echo "[seed] .zshrc seeded with legacy .nebflow PATH line" | tee -a "$OUT"

env -u STAND_HOME HOME="$SB" bash "$WT/release/install.sh" --beta >> "$OUT" 2>&1
rc=$?
echo "installer exit=$rc" | tee -a "$OUT"

echo "--- .zshrc after reinstall ---" | tee -a "$OUT"
cat "$SB/.zshrc" | tee -a "$OUT"
echo "--- assertions ---" | tee -a "$OUT"
fail=0
grep -q '^# retired legacy home PATH entry.*export PATH="\$HOME/\.nebflow/bin:\$PATH"$' "$SB/.zshrc" \
  && echo "[R] OK: legacy line retired in place (commented, history preserved)" | tee -a "$OUT" \
  || { echo "[R] FAIL: legacy line not retired"; fail=1; }
[ -f "$SB/.zshrc.bak-nebflow-path" ] \
  && echo "[R] OK: backup .zshrc.bak-nebflow-path created" | tee -a "$OUT" \
  || { echo "[R] FAIL: no backup file"; fail=1; }
grep -qF "export PATH=\"$SB/.stand/bin:\$PATH\"" "$SB/.zshrc" \
  && echo "[R] OK: new PATH line derives from STAND_BASE" | tee -a "$OUT" \
  || { echo "[R] FAIL: new PATH line missing/mismatched"; fail=1; }
grep -q "alias ll" "$SB/.zshrc" \
  && echo "[R] OK: user lines preserved" | tee -a "$OUT" \
  || { echo "[R] FAIL: user lines lost"; fail=1; }
grep -q '^[[:space:]]*export PATH=.*\.nebflow/bin' "$SB/.zshrc" \
  && { echo "[R] FAIL: active legacy PATH line still present"; fail=1; } \
  || echo "[R] OK: no ACTIVE legacy PATH line remains" | tee -a "$OUT"
[ ! -e "$SB/.nebflow" ] && echo "[R] OK: still zero .nebflow after reinstall" | tee -a "$OUT" \
  || { echo "[R] FAIL: .nebflow appeared"; fail=1; }
echo "RESULT_R=$([ $fail -eq 0 ] && echo PASS || echo FAIL)" | tee -a "$OUT"
echo "DONE-PATH-RETIRE-VERIFY"
