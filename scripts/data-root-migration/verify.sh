#!/bin/bash
# verify.sh — 数据根迁移对账：源/目标抽样 sha256 + SQLite integrity + 任务台账读数
#
#   verify.sh            报告模式（预拷贝阶段用；引擎运行中，热文件差异属预期，只报告）
#   verify.sh --strict   严核模式（finalize 之后用；任何 FAIL → exit 1）
#
# 抽样 30 个文件：固定必要清单（tasks-v2.json / User.md / auth.json / neblink/config.json /
#   delegates / sessions / chains / bin …）+ delegates/sessions/chains 确定性补齐。
# 已知会被 finalize 有意改写的文件（daemons.json 等）按「路径归一化后比对」判定。
# 源侧参照目录：$SRC 存在用 $SRC；否则用最新的 ${SRC}-migrated-*（切换后核对）。
# 环境变量覆盖：NEBFLOW_MIGRATE_SRC / NEBFLOW_MIGRATE_DST
set -u
SRC="${NEBFLOW_MIGRATE_SRC:-$HOME/.nebflow}"
DST="${NEBFLOW_MIGRATE_DST:-$HOME/.stand}"
STRICT=0
[ "${1:-}" = "--strict" ] && STRICT=1

pass=0; fail=0; patched_as_intended=0; skip=0
PATCHABLE="daemons.json install-manifest.json bin/nebflow-autostart.sh bin/memsample.sh"
shasum_bin="$(command -v shasum || command -v shasum256)"

if [ -d "$SRC" ]; then REF="$SRC"; REF_KIND="live-source"
elif ls -d "${SRC}"-migrated-* >/dev/null 2>&1; then
  REF="$(ls -d "${SRC}"-migrated-* | sort | tail -n1)"; REF_KIND="renamed-backup: $REF"
else REF=""; REF_KIND="none (source gone and no migrated backup — content compare impossible)"; fi

echo "=== verify.sh $( [ $STRICT = 1 ] && echo --strict ) @ $(date '+%F %T') ==="
echo "DST(target) = $DST"
echo "REF(compare) = ${REF:-n/a}  [$REF_KIND]"
[ -d "$DST" ] || { echo "FATAL: target $DST missing"; exit 1; }

is_patchable() { case " $PATCHABLE " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

check_one() { # $1 = relpath (exists in DST)
  rel="$1"
  d="$DST/$rel"
  [ -f "$d" ] || { echo "MISS  $rel (absent in target)"; fail=$((fail+1)); return; }
  if [ -n "$REF" ] && [ -f "$REF/$rel" ]; then
    if [ "$( "$shasum_bin" -a 256 "$d" | awk '{print $1}')" = "$( "$shasum_bin" -a 256 "$REF/$rel" | awk '{print $1}')" ]; then
      echo "PASS  $rel"; pass=$((pass+1)); return
    fi
    if is_patchable "$rel"; then
      ns="$(DST="$DST" SRC="$SRC" perl -pe 's{\Q$ENV{DST}\E(?![-\w])}{$ENV{SRC}}g' "$d" | "$shasum_bin" -a 256 | awk '{print $1}')"
      if [ "$ns" = "$( "$shasum_bin" -a 256 "$REF/$rel" | awk '{print $1}')" ]; then
        echo "PASS* $rel (patched-as-intended: path refs rewritten to target)"; patched_as_intended=$((patched_as_intended+1)); return
      fi
    fi
    echo "FAIL  $rel (sha differs vs reference)"; fail=$((fail+1)); return
  fi
  echo "SKIP  $rel (no reference copy to compare — target-only)"; skip=$((skip+1))
}

# ---- fixed required samples ------------------------------------------------
FIXED="tasks-v2.json tasks.json User.md auth.json nebflow.json preferences.json models.json daemons.json daemon-pids.json install-manifest.json friend-remarks.json device.json canvas_tabs.json shutdown-marker.json .seed-state.json .pid.meta neblink/config.json neblink/device.json neblink/peers.json bin/nebflow-autostart.sh"
for f in $FIXED; do [ -f "$DST/$f" ] && check_one "$f"; done

# newest log file
newest_log="$(find "$DST/logs" -type f 2>/dev/null | while IFS= read -r f; do stat -f '%m %N' "$f"; done | sort -rn | head -n1 | cut -d' ' -f2-)"
[ -n "${newest_log:-}" ] && check_one "${newest_log#"$DST"/}"

# ---- deterministic fill to 30 -----------------------------------------------
fill_from() { # $1=subdir $2=count $3=filter-out-regex(optional)
  local dir="$DST/$1" n="${2:-0}"
  [ -d "$dir" ] || return 0
  local sel
  sel="$(ls "$dir" 2>/dev/null | sort | ${3:+grep -vE "$3"} | head -n "$n")"
  local s
  for s in $sel; do [ -f "$dir/$s" ] && check_one "$1/$s"; done
}
TOTAL_NOW="$(find "$DST" -type f 2>/dev/null | wc -l | tr -d ' ')"
NEED=$(( 30 - (pass + fail + skip + patched_as_intended) ))
if [ "$NEED" -gt 0 ]; then fill_from delegates "$((NEED/3+1))"; fi
NEED=$(( 30 - (pass + fail + skip + patched_as_intended) ))
if [ "$NEED" -gt 0 ]; then fill_from sessions "$NEED" '_index\.json\.tmp|^_index'; fi
NEED=$(( 30 - (pass + fail + skip + patched_as_intended) ))
if [ "$NEED" -gt 0 ]; then fill_from chains "$NEED"; fi
NEED=$(( 30 - (pass + fail + skip + patched_as_intended) ))
if [ "$NEED" -gt 0 ]; then fill_from projects 1; fi

SAMPLED=$((pass + fail + skip + patched_as_intended))

# ---- SQLite integrity --------------------------------------------------------
echo "--- sqlite integrity ---"
sqlok=0; sqlbad=0; sqno=0
if command -v sqlite3 >/dev/null; then
  find "$DST" -maxdepth 3 \( -name '*.db' -o -name '*.sqlite' -o -name '*.sqlite3' \) -type f 2>/dev/null | head -n 6 | while IFS= read -r db; do
    r="$(sqlite3 "$db" 'PRAGMA integrity_check;' 2>&1 | head -n1)"
    if [ "$r" = "ok" ]; then echo "SQLITE-OK   ${db#"$DST"/}"; else echo "SQLITE-BAD  ${db#"$DST"/} → $r"; fi
  done
else
  echo "sqlite3 not available — integrity check skipped"
fi

# ---- ledger counts -----------------------------------------------------------
echo "--- task ledger counts ---"
cnt() { python3 -c 'import json,sys
d=json.load(open(sys.argv[1]))
t=d.get("tasks") if isinstance(d,dict) else d
print(len(t) if isinstance(t,(list,dict)) else len(d))' "$1" 2>/dev/null; }
for lf in tasks-v2.json tasks.json; do
  [ -f "$DST/$lf" ] || continue
  c_dst="$(cnt "$DST/$lf")"
  c_ref="n/a"; [ -n "$REF" ] && [ -f "$REF/$lf" ] && { c_ref="$(cnt "$REF/$lf")"; }
  s_dst="$(stat -f %z "$DST/$lf")"
  echo "LEDGER $lf: target_entries=$c_dst ref_entries=$c_ref bytes=$s_dst"
done 2>/dev/null

# ---- summary ------------------------------------------------------------------
echo "--- summary ---"
echo "files sampled=$SAMPLED pass=$pass pass*(patched-as-intended)=$patched_as_intended fail=$fail skip=$skip"
echo "target file count=$TOTAL_NOW"
df -k "$(dirname "$DST")" | awk 'NR==2{printf "volume free=%.1f GiB\n", $4/1048576}'
if [ "$STRICT" = "1" ] && [ "$fail" -gt 0 ]; then
  echo "STRICT RESULT: FAIL ($fail mismatches)"; exit 1
fi
echo "RESULT: report-only OK (use --strict after finalize for pass/fail gate)"
exit 0
