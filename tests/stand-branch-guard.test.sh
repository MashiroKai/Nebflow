#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
guard=tools/stand-branch-guard
count=0
expect() {
  expected=$1; shift
  actual=0
  "$@" >/dev/null 2>&1 || actual=$?
  if [ "$actual" -ne "$expected" ]; then
    printf 'FAIL expected %s got %s: ' "$expected" "$actual" >&2
    printf '%q ' "$@" >&2; printf '\n' >&2; exit 1
  fi
  count=$((count + 1))
}
# branch and MR fixtures
for branch in main feat/example bug/example achieve/JeV release/beta release/stable feat/nested/name; do
  expect 0 "$guard" --check-branch "$branch"
done
for branch in fix/legacy release/other 'release:beta' 'feat/' 'feat/a..b' 'feat/-bad.lock' ''; do
  expect 1 "$guard" --check-branch "$branch"
done
for branch in feat/example bug/example; do expect 0 "$guard" --check-pr-source "$branch"; done
for branch in main achieve/JeV fix/legacy 'feat/a..b' release/beta; do expect 1 "$guard" --check-pr-source "$branch"; done
expect 2 "$guard" --check-branch
expect 2 "$guard" --check-push --unknown
expect 0 "$guard" --help
# push fixtures
sha=1111111111111111111111111111111111111111
zero=0000000000000000000000000000000000000000
push() { printf '%s\n' "$1" | "$guard" --check-push "${@:2}"; }
expect 0 push "refs/heads/feat/x $sha refs/heads/feat/x $zero"
expect 1 push "refs/heads/feat/x $sha refs/heads/main $sha"
expect 1 push "(delete) $zero refs/heads/release/beta $sha"
expect 1 push "refs/heads/feat/x $sha refs/heads/release/other $sha"
expect 0 push "refs/heads/feat/x $sha refs/heads/release/stable $sha" --allow origin example
expect 1 push "refs/heads/feat/x $sha refs/heads/main $sha extra" --allow
expect 1 push "refs/heads/feat/x bad refs/heads/main $sha" --allow
expect 1 push "refs/heads/feat/x $sha refs/heads/main $sha
refs/heads/feat/x $sha refs/heads/feat/x $sha"
expect 0 bash -c 'tools/stand-branch-guard --check-push </dev/null'
printf 'PASS: %s branch, MR, push and invocation fixtures\n' "$count"
