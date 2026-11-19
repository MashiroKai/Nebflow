# Cold-start revision driver

The driver is independent of the target checkout. Its `--dry-run` means a real detached checkout and `sbt compile`, but no instance launch. It prints both driver hashes and the exact resolved target commit. Live mode is intentionally disabled until process cleanup can be verified.

From the driver checkout:

```sh
bash scripts/coldstart-ref.sh --ref my-branch --port 39127 --home /tmp/my-new-cold-home --dry-run
```

For a stable entry, install a reviewed revision by copying both `scripts/coldstart-ref.sh` and `scripts/coldstart.sh` together to a private directory outside any checkout. Invoke it with `COLDSTART_REPO=/absolute/path/to/repo bash /absolute/path/to/private-dir/coldstart-ref.sh ...`. Upgrade by replacing both files together from a reviewed commit; compare the printed SHA-256 hashes to identify the installed driver. No installation is performed by this change. Do not use the printed launch command as an automated smoke test: its lifecycle is not yet safe to clean up automatically.

The data-root argument must be a nonexistent path below `/tmp` with an existing parent. Protected ports and occupied ports are rejected. Dependencies include git, sbt, a supported JDK, curl, lsof, shasum and bash; compile may need network and caches. Build uses `-J-Xmx4g -J-XX:MaxMetaspaceSize=2g`; ensure sufficient memory and coordinate other builds. Build failure is an error; temporary worktree cleanup is attempted on exit.

## Manual live test (author-operated; automated live mode unavailable)

Platform cancellation is best effort for forked process trees; it does not prove that an orphaned JVM has exited. The commands below are an unverified manual procedure, not an automated cleanup guarantee. Use a reviewed driver checkout, a free nonprotected port and a new isolated root. Do not run against an existing instance. Keep the terminal open and inspect output for errors. Example:

```sh
REPO='/absolute/path/to/Stand'
DRIVER='/absolute/path/to/reviewed-driver'
TREE=/tmp/stand-manual-cold-tree
ROOT=/tmp/stand-manual-cold-root
PORT=39127
# Abort this shell if either path exists, or if the port is occupied.
if [ -e "$TREE" ] || [ -e "$ROOT" ] || lsof -nP -iTCP:"$PORT" -sTCP:LISTEN; then
  printf 'Unsafe path or occupied port; stop.\n' >&2
  exit 1
fi
git -C "$REPO" worktree add --detach "$TREE" fix/console-startup-observer-loop
mkdir "$ROOT"
(cd "$TREE" && COLDSTART_PORT="$PORT" COLDSTART_HOME="$ROOT" \
 COLDSTART_SECRETS_DIR="$ROOT/secrets-store" COLDSTART_FULL=1 COLDSTART_NO_OPEN=1 \
 NEBFLOW_ALLOW_PROD_ENROLL=0 bash "$DRIVER/coldstart.sh" --net off)
```

In a second terminal check `curl --noproxy '*' -fsS http://127.0.0.1:39127/api/health`; HTTP 200 is required. Record the listener before stopping with `lsof -nP -iTCP:39127 -sTCP:LISTEN`, and inspect that PID with `ps -p <listener-pid> -o pid,ppid,command`. The author stops the foreground run with Ctrl-C, then verifies `lsof -nP -iTCP:39127 -sTCP:LISTEN` returns no listener and checks the recorded PID using `ps -p <listener-pid> -o pid,ppid,command`, as well as the instance's `.pid` / `.pid.meta` for any surviving owned processes. Ctrl-C and the legacy script's trap are not proof that a fork JVM exited; if ownership or termination is uncertain, stop and investigate rather than touching any other process. Only after confirming no instance remains, remove the isolated worktree with `git -C "$REPO" worktree remove --force "$TREE"`, and remove the isolated root manually. If build or startup fails, still perform these checks and cleanup. Never use this procedure on ports 6138, 8080, or 8097 or on `~/.stand`.
