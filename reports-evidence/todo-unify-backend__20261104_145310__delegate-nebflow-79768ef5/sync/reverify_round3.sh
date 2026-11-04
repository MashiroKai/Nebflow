#!/bin/bash
# 第三轮（追平 0960a0898 后）全量复跑 —— 收束树读数
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

run 80_gate_frontend_gates      make frontend-gates
run 81_gate_design_spec_sync    node scripts/check-design-spec-sync.mjs
run 82_gate_component_contract  node scripts/check-component-contract.mjs
run 83_gate_process_artifacts   bash scripts/check-process-artifacts.sh

run 84_spec_capsule_and_store   node "$CLI" test tests/todo-capsule-create.spec.mjs tests/todo-store-backend.spec.mjs
run 85_spec_todo_split          node "$CLI" test tests/todo-split.spec.mjs

run 86_yield_chain_probe        node "$SYNC/yield_chain_probe.mjs"

run 87_shots_todo_face          node tests/todo-face-shots.mjs
run 88_shots_todo_page          node tests/todo-page-shots.mjs

echo "ALL_DONE"
