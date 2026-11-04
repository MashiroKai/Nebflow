# 03 · 同名再派 ⇒ 复用同一棵树（绝不产生第二个目录）

判据：同名两次派发 ⇒ 座位指向**同一棵**树；即使有人硬走建树腿也**显式 Left**，不生成 `<name>-2`。

## 命令原文

同 `01-` 的 sbt 命令。

## 原始输出（`raw.log`）

```
nebflow.core.delegate.DelegateWorkspaceWorktreeLegSpec:
  + W3 同名复用：同一 name 再派一次 ⇒ 复用既有树，不产生第二个目录 0.328s
nebflow.core.delegate.WorktreeReuseSelectionSpec:
  + J2 candidate inventory tags the three states correctly (orphan / active / done) with work readings 0.396s
  + J2b rendered candidate list carries the three status words and the reuse instruction 0.446s
  + J3 reusing an orphan worktree binds the seat to it: same path, inherited uncommitted work visible, branch correct 0.384s
  + J4 without the worktree parameter NOTHING is allocated: the seat is the workspace itself (read-only channel) 0.207s
  + J5 reusing a worktree held by a LIVE session is REFUSED, and force=true is the only way through 0.334s
  + J5b an unknown target is REFUSED explicitly (no silent fallback to a fresh worktree) 0.226s
  + J6 today's real case: a dead delegate whose tree holds commits + uncommitted work is surfaced as ORPHAN with a reuse option 0.376s
  + J6b the reuse target resolves from all three shapes (delegate id / dirName / absolute path) 0.216s
```

## 解析规则（`WorktreeCandidates.resolveNamed`，纯读 + 纯函数）

1. **精确 name 命中** `<worktreeRoot>/<name>` ⇒ 复用（`SeatPlan.Reuse`，`seatSource = ExactName`）；
2. 旧口径兜底（delegate id / 目录名 / 绝对路径）⇒ 复用（`seatSource = Legacy`）；
3. 都没命中 ⇒ 按 name 新建（`SeatPlan.Create`）；同名目录被**非 worktree** 占用 ⇒ 显式拒（`RefuseCode.BadName`）；
4. 命中 active（活会话持有）且未 `force` ⇒ 显式拒（红线闸不动）。

改前读数：`worktree` 语义是「复用已有树」，同名不会被"建第二棵"是因为压根不建；
改后：同名 = 复用，且**存在恶意/误走建树腿的双保险**（W3 断言目录数仍为 1）。
