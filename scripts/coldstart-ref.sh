#!/usr/bin/env bash
# Versioned driver: execute this file from the driver revision, not the target checkout.
set -euo pipefail
usage() { echo 'Usage: bash scripts/coldstart-ref.sh --ref BRANCH_OR_COMMIT --port PORT --home ABSOLUTE_NEW_PATH [--dry-run]'; }
ref= port= home= dry=0 repo_arg=${COLDSTART_REPO:-}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --ref|--port|--home) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; key=$1; value=$2; shift 2; case "$key" in --ref) ref=$value;; --port) port=$value;; --home) home=$value;; esac ;;
    --dry-run) dry=1; shift;;
    --help) usage; exit 0;;
    *) usage >&2; exit 2;;
  esac
done
[ -n "$ref" ] && [ -n "$port" ] && [ -n "$home" ] || { usage >&2; exit 2; }
case "$port" in *[!0-9]*|'') echo 'Invalid port' >&2; exit 2;; esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || { echo 'Invalid port' >&2; exit 2; }
case "$port" in 6138|8080|8097) echo 'Protected port' >&2; exit 2;; esac
case "$home" in /tmp/*|/private/tmp/*) :;; *) echo 'Home must be a new path beneath /tmp' >&2; exit 2;; esac
[ ! -e "$home" ] && [ ! -L "$home" ] || { echo 'Home already exists' >&2; exit 2; }
# Resolve the parent, preventing symlinks and .. from escaping the temporary tree.
parent=$(dirname "$home")
[ -d "$parent" ] || { echo 'Home parent must exist' >&2; exit 2; }
parent=$(cd "$parent" && pwd -P)
case "$parent/" in /tmp/*|/private/tmp/*|/tmp/|/private/tmp/) :;; *) echo 'Home parent escapes /tmp' >&2; exit 2;; esac
home="$parent/$(basename "$home")"
[ "$(basename "$home")" != . ] && [ "$(basename "$home")" != .. ] || exit 2
[ ! -e "$home" ] && [ ! -L "$home" ] || { echo 'Home already exists' >&2; exit 2; }
command -v lsof >/dev/null || { echo 'lsof is required for port checking' >&2; exit 1; }
if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | grep -q .; then echo 'Port occupied' >&2; exit 1; fi
script_dir=$(cd "$(dirname "$0")" && pwd -P)
repo=$(git -C "${repo_arg:-$script_dir}" rev-parse --show-toplevel)
commit=$(git -C "$repo" rev-parse --verify --end-of-options "$ref^{commit}") || exit 1
driver=$(shasum -a 256 "$script_dir/coldstart-ref.sh" "$script_dir/coldstart.sh")
work=$(mktemp -d "${TMPDIR:-/tmp}/stand-cold-ref.XXXXXX")
checkout="$work/tree"
added=0
cleanup() {
  result=$?
  trap - EXIT
  if [ "$added" = 1 ]; then
    if ! git -C "$repo" worktree remove --force "$checkout"; then echo "ERROR: remove worktree manually: $checkout" >&2; exit 1; fi
  fi
  if [ -d "$work" ]; then rm -rf "$work"; fi
  exit "$result"
}
trap cleanup EXIT
printf 'driver=%s target=%s port=%s home=%s checkout=%s dry_run=%s\n' "$driver" "$commit" "$port" "$home" "$checkout" "$dry"
if [ "$dry" != 1 ]; then echo 'ERROR: live mode is disabled until owned-instance cleanup is safe and verified.' >&2; exit 1; fi
git -C "$repo" worktree add --detach "$checkout" "$commit"
added=1
printf 'build: (cd %q && sbt compile)\n' "$checkout"
(cd "$checkout" && sbt compile)
printf 'launch: (cd %q && COLDSTART_PORT=%q COLDSTART_HOME=%q COLDSTART_FULL=1 COLDSTART_NO_OPEN=1 bash %q --net off)\n' "$checkout" "$port" "$home" "$script_dir/coldstart.sh"
echo 'dry-run: build complete; instance not started'
