# Cold-start revision driver

The driver is independent of the target checkout. Its `--dry-run` means a real detached checkout and `sbt compile`, but no instance launch. It prints both driver hashes and the exact resolved target commit. Live mode is intentionally disabled until process cleanup can be verified.

From the driver checkout:

```sh
bash scripts/coldstart-ref.sh --ref my-branch --port 39127 --home /tmp/my-new-cold-home --dry-run
```

For a stable entry, install a reviewed revision by copying both `scripts/coldstart-ref.sh` and `scripts/coldstart.sh` together to a private directory outside any checkout. Invoke it with `COLDSTART_REPO=/absolute/path/to/repo bash /absolute/path/to/private-dir/coldstart-ref.sh ...`. Upgrade by replacing both files together from a reviewed commit; compare the printed SHA-256 hashes to identify the installed driver. No installation is performed by this change. Do not use the printed launch command as an automated smoke test: its lifecycle is not yet safe to clean up automatically.

The data-root argument must be a nonexistent path below `/tmp` with an existing parent. Protected ports and occupied ports are rejected. Dependencies include git, sbt, a supported JDK, curl, lsof, shasum and bash; compile may need network and caches. Build failure is an error; temporary worktree cleanup is attempted on exit.
