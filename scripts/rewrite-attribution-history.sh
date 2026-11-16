#!/usr/bin/env bash
# Explicitly invoked only after coordinated approval; rewrites a single local branch.
set -euo pipefail
if [[ ${1:-} != --execute || $# != 4 || $4 != I-ACCEPT-HISTORY-REWRITE ]]; then
  printf 'Usage: %s --execute BASE_SHA BRANCH I-ACCEPT-HISTORY-REWRITE\n' "$0" >&2
  exit 2
fi
base=$2 branch=$3
[[ $base =~ ^[0-9a-f]{40}$ ]] || { echo 'BASE_SHA must be a full object ID' >&2; exit 2; }
git check-ref-format "refs/heads/$branch"
git cat-file -e "$base^{commit}"
git merge-base --is-ancestor "$base" "refs/heads/$branch"
[[ -z $(git status --porcelain) ]] || { echo 'Worktree is not clean; stop' >&2; exit 2; }
script_dir=$(cd "$(dirname "$0")" && pwd -P)
python3 "$script_dir/preview-attribution-cleanup.py" "$base..refs/heads/$branch"
echo 'Back up refs and stop all writers before executing. This operation changes commit IDs.' >&2
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch --msg-filter "python3 '$script_dir/preview-attribution-cleanup.py' --filter" -- "$base..refs/heads/$branch"
