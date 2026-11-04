#!/bin/bash
# 合后复跑 · 本批既有 spec（Playwright 腿）
# 口径：worktree 为 cwd（相对路径解析到本分支树），CLI 用主 checkout 的 node_modules。
set -u
WT="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5"
CLI="/Users/kaiyu/Claude code/Stand/node_modules/@playwright/test/cli.js"
LOG="$WT/reports-evidence/20261104_todo-unify-backend__delegate-nebflow-79768ef5/sync/logs"
cd "$WT" || exit 9

run() {
  local name="$1"; shift
  {
    echo "\$ cd $WT"
    echo "\$ node $CLI test $*"
    node "$CLI" test "$@"
    echo "EXIT=$?"
  } > "$LOG/$name.log" 2>&1
  echo "$name => $(grep -o 'EXIT=[0-9]*' "$LOG/$name.log" | tail -1)"
  grep -E '^\s*[0-9]+ (passed|failed|skipped|flaky)' "$LOG/$name.log" | tail -3
}

run 20_spec_capsule_and_store tests/todo-capsule-create.spec.mjs tests/todo-store-backend.spec.mjs
run 21_spec_todo_split tests/todo-split.spec.mjs
echo "ALL_DONE"
