#!/bin/bash
# 合后复跑 · 视觉/操作 shots（本批相关既有 spec 不转红）
set -u
WT="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5"
LOG="$WT/reports-evidence/20261104_todo-unify-backend__delegate-nebflow-79768ef5/sync/logs"
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
  tail -6 "$LOG/$name.log"
}

run 22_shots_todo_face node tests/todo-face-shots.mjs
run 23_shots_todo_page node tests/todo-page-shots.mjs
echo "ALL_DONE"
