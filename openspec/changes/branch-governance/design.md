# Design

Validate branch names with Git and the six-class allowlist; reject non-feature/bug MR sources. A pre-push hook consumes every destination ref, failing closed on malformed input and protecting main and release branches unless explicitly allowed. The override is not authorization to bypass author-only policy. Wire the MR check into the existing USTC GitLab static job; server-side protections remain necessary because local hooks and source-controlled CI can be bypassed.

裁定 2026-11-19 17:35：release 支链由 `beta→stable` 改为一律从 `main` 派生，规范面为权威面。

## Worktree lifecycle (BG-REQ-003)

The branch model already fixes where a branch may come from and where it may land; it did not fix
either end of the *worktree* that carries it, and both ends leaked in practice. Creation leaked
because a brief could hand-write `git worktree add <path>`: of the 180 linked worktrees listed by
`git worktree list` on this machine at 2026-11-19 21:00, only 17 sat under the unified
`<project>/.stand/worktrees/<name>/` shape produced by `scripts/stand-wt.py new`, while 128 sat under
`/private/tmp/*`, 21 under `~/stand-wt/*`, 6 under `/Volumes/*`, 3 under `.claude/worktrees/*` and 5
at other ad-hoc paths — trees that `stand-wt.py list`, the artifact manifest and every cleanup path
cannot see.
Reclamation leaked because a landed branch kept its tree: no rule said the tree has to go, so it
stayed, and nothing ever failed for it.

Two rulings by the author on 2026-11-19 close those ends as one pair — create in one place, reclaim
in one step:

- **Creation.** `scripts/stand-wt.py new <wtname> [--branch <branch>] [--base feat/base]` is the
  only entry; it owns the path `<project>/.stand/worktrees/<wtname>/`, the branch-name and
  merge-base validation, and the `.stand/tmp/<wtname>/` artifact roots. No brief, batch or node
  hand-writes `git worktree add` with a self-chosen path. The entry is deliberately not restructured
  here: another batch owns its copy-on-write path, and this change only makes the entry mandatory in
  the normative surface.
- **Reclamation.** `git worktree remove <path>` without `--force`, in the same action that lands the
  branch, gated by three preconditions (clean `status`, no process from `lsof +D`, no `MERGE_HEAD`
  / `rebase-*` / `CHERRY_PICK_HEAD`); a failing precondition skips the tree and names it rather than
  forcing the deletion, and the branch is always kept — branch retirement is a different decision.
  The existing landing driver `scripts/merge-sink.py` carried the older shape (dirty check only, then
  `git branch -d`); it is rewritten to this rule so the repository does not hold two versions of it.

The guard is `scripts/check-worktree-cleanup.mjs`, registered in a dedicated opt-in `worktree`
profile rather than in `static`: the judgment reads local disk state (which merged branch still holds
a tree), and on 2026-11-19 that backlog made it red on this machine — 181 linked worktrees judged,
41 merged-but-hanging. Putting it in `static` would keep every developer's static profile red for a
backlog and drown the code-level reds it exists to protect. The two exit paths are kept strictly
apart: a checkout with judgeable worktrees and a merged-but-hanging tree exits 1; a checkout with no
judgeable worktree (CI or hosted primary checkout, or an integration ref that cannot be read) passes
while stating that there was nothing to judge, so "nothing to judge" can never be read as "no
residue", and "residue" can never be hidden as "nothing to judge".

中文口径（与上文同义，*不另立一套说法*）：**建树统一、清树统一**。建树只走
`python3 scripts/stand-wt.py new <wtname>`，产物在 `<project>/.stand/worktrees/<wtname>/`，派发简报
禁止手写 `git worktree add <自选路径>`；一支并入 `feat/base` 或 `main` 后即用
`git worktree remove <path>`（不加 `--force`）回收该树，三个前置条件（`status` 干净 / `lsof +D`
无占用 / 无 `MERGE_HEAD`·`rebase-*`·`CHERRY_PICK_HEAD`）缺一即跳过并点名，**分支一律保留**，
落支报告写明删了哪棵、回收多少、跳过哪棵及原因。闸为 `scripts/check-worktree-cleanup.mjs`
（`--profile worktree` 可选档）：本机可判对象里出现「已并入却仍挂着」即 exit 1；CI/托管主检出无
可判对象则 PASS 并明写「无对象可判」——两种情形互不冒充。
