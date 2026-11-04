#!/bin/bash
# 第二轮（追平 995142a5f 后）全量复跑：四门禁 + 本批 spec + 视觉回归 + 让位链实测
set -u
WT="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5"
CLI="/Users/kaiyu/Claude code/Stand/node_modules/@playwright/test/cli.js"
SYNC="$WT/reports-evidence/20261104_todo-unify-backend__delegate-nebflow-79768ef5/sync"
LOG="$SYNC/logs"
cd "$WT" || exit 9

run() {
  local name="$1"; shift
  {
    echo "\$ cd $WT"
    echo "\$ $*"
    "$@"
    echo "EXIT=$?"
  } > "$LOG/$name.log" 2>&1
  echo "== $name => $(grep -o 'EXIT=[0-9]*' "$LOG/$name.log" | tail -1)"
}

run 50_gate_frontend_gates      make frontend-gates
run 51_gate_design_spec_sync    node scripts/check-design-spec-sync.mjs
run 52_gate_component_contract  node scripts/check-component-contract.mjs
run 53_gate_process_artifacts   bash scripts/check-process-artifacts.sh

run 54_spec_capsule_and_store   node "$CLI" test tests/todo-capsule-create.spec.mjs tests/todo-store-backend.spec.mjs
run 55_spec_todo_split          node "$CLI" test tests/todo-split.spec.mjs

run 56_yield_chain_probe        node "$SYNC/yield_chain_probe.mjs"

run 57_shots_todo_face          node tests/todo-face-shots.mjs
run 58_shots_todo_page          node tests/todo-page-shots.mjs

echo "ALL_DONE"
