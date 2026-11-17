#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
guard="$PWD/tools/stand-branch-guard"
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
# merge-direction fixtures: --check-mr needs both source and target (fail closed on a missing side)
for pair in 'feat/example feat/base' 'bug/example feat/base' 'fix/legacy feat/base' 'feat/nested/name feat/base' 'feat/base main' 'main feat/base'; do
  expect 0 "$guard" --check-mr $pair
done
for pair in 'feat/example main' 'bug/example main' 'fix/legacy main' 'feat/nested/name main' \
            'achieve/JeV feat/base' 'release/beta feat/base' 'main main' 'feat/base feat/base' \
            'feat/example release/beta' 'feat/base release/stable'; do
  expect 1 "$guard" --check-mr $pair
done
expect 1 "$guard" --check-mr 'feat/a..b' feat/base
expect 1 "$guard" --check-mr feat/example 'feat/a..b'
expect 2 "$guard" --check-mr feat/example
expect 2 "$guard" --check-mr
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
# feat/base reserved-branch fixtures: real repository objects so fast-forward ancestry is decidable
tip=$(git rev-parse HEAD)
parent=$(git rev-parse HEAD~1)
# Isolate the remote-tracking reference; never mutate the shared checkout.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git clone -q --no-hardlinks . "$tmp/repo"
git -C "$tmp/repo" update-ref refs/remotes/origin/main "$tip"
expect 0 bash -c 'cd "$1" && "$2" --check-mr bug/hotfix main "$3"' _ "$tmp/repo" "$guard" "$parent"
expect 1 bash -c 'cd "$1" && "$2" --check-mr bug/hotfix main "$3"' _ "$tmp/repo" "$guard" "$sha"
expect 0 push "refs/heads/feat/base $tip refs/heads/feat/base $parent"
expect 1 push "refs/heads/feat/base $parent refs/heads/feat/base $tip"
expect 1 push "(delete) $zero refs/heads/feat/base $tip"
expect 1 push "refs/heads/feat/base $tip refs/heads/feat/base $zero"
expect 1 push "refs/heads/feat/base $tip refs/heads/feat/base $sha"
expect 0 push "refs/heads/feat/base $parent refs/heads/feat/base $tip" --allow
printf 'PASS: %s branch, MR, push and invocation fixtures\n' "$count"
