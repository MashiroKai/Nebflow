#!/bin/bash
# ab-visual-attribution.sh — 用 --web-root 做 A/B 归因：
#   A = 迁移前 web 树（git HEAD 抽出到 /tmp，与本批改动无关）
#   B = 当前工作树（已迁移）
# 交替 A1 B1 A2 B2，判据：
#   · A1 vs A2 = 本机噪声带（同一棵树、同一改动状态，两次拍）
#   · A1 vs B1 = 迁移前后的真实差异
#   · A1 vs B2 = 迁移前后差异的复现性
#   · B1 vs B2 = 迁移后自身的噪声带
# 若 A1↔A2 与 B1↔B2 在同一 bbox 也抖，则 A1↔B* 的差异 = 噪声，不是迁移。
#
# 每次 --update 都会写**入库基准目录**（tests/visual/baseline/<platform>），
# 故每拍之后立刻 git restore 复原，保证「不许改 baseline 文件」这条硬约束。
set -u
cd "$(dirname "$0")/../.." || exit 1
ROOT=$(pwd)
E="$ROOT/reports-evidence/20261104_design-scale-migration/visual"
FILTER="${1:-tab}"
BASE=tests/visual/baseline/darwin-chromium-151
PRIS=/tmp/nebflow-pristine-web

restore_baseline() {
  git restore --source=HEAD -- "$BASE" 2>/dev/null
  git ls-files --others --exclude-standard "$BASE" | while read -r f; do rm -f "$f"; done
}

capture() {   # capture <outname> [extra args...]
  local name=$1; shift
  node scripts/visual-baseline.mjs --update --filter "$FILTER" "$@" > "/tmp/ab-$name.log" 2>&1
  echo "  capture $name exit=$?"
  cp -R "$BASE" "$E/$name"
  restore_baseline
}

if [ ! -d "$PRIS/src/main/resources/web" ]; then
  mkdir -p "$PRIS"
  git archive HEAD src/main/resources/web | tar -x -C "$PRIS" || exit 1
  echo "pristine web tree -> $PRIS"
fi

echo "=== A1 (pristine HEAD web) ==="; capture ab-A1 --web-root "$PRIS/src/main/resources/web"
echo "=== B1 (migrated working tree) ==="; capture ab-B1
echo "=== A2 (pristine HEAD web) ==="; capture ab-A2 --web-root "$PRIS/src/main/resources/web"
echo "=== B2 (migrated working tree) ==="; capture ab-B2
echo "=== baseline restored? ==="; git status --short "$BASE" | head
echo "done"
