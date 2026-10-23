# worktree 清理抢救 reports/evidence 守卫（实现报告）

- 批次：作者令 2026-10-24「worktree 清理抢救 reports/evidence」（治理微批）；分支 `delegate/delegate-nebflow-f52108dd`。
- 现象实证：canvas 报告卡指向 `reports/20261022_200808_weixin-outbound-group-capability/…`，报「不存在或被清理」——该报告只存在于已被清理的 delegate worktree，未并入 main 工作区，物理丢失不可恢复（作者 11:53 截图）。
- 根因：合并 Release 后的现场回收（`PostMergeCleanup` → `git worktree remove`）直接删目录，worktree 内 `reports/`、`evidence/` 下未并入主工作区的文件随之丢失。

## 0. 结论（一段话）

在唯一删除点（`PostMergeCleanup.run` 的移除分支）前挂一个抢救守卫 `WorktreeRescue`：删除 worktree 前扫描其下 `reports/` 与 `evidence/` 两类路径的常规文件，凡主工作区同相对路径不存在的先复制回主工作区（嵌套目录补建），已存在的跳过并记审计行，然后照旧清理。fail-soft 全包、幂等、绝不阻断清理；其余路径与清理语义零改动。

## 1. 实现（改动面 = 1 新文件 + 1 行为挂点 + 2 测试件 + 本报告）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/delegate/WorktreeRescue.scala` | **新**。守卫本体：`rescue(ws, worktree)` 扫描 `GuardedDirs = [reports, evidence]`，常规文件逐个 `os.exists(主工作区同相对路径)` 判定——缺失则 `os.copy.over(createFolders = true)` 复制回主工作区，已存在跳过；返回 `Report(rescued/skipped/failed)` 账面 |
| `src/main/scala/nebflow/core/delegate/PostMergeCleanup.scala` | 移除分支在 `GitWorktrees.removeWorktree` **前**调 `WorktreeRescue.rescue(ws, wt)`；清理账本行 detail 追加 rescue 摘要（`rescue rescued=N skipped=M failed=K [路径样本]`，仅非空时）。kept 分支（零领先守卫拦下）不经此——没有删除就没有丢失 |
| `src/test/scala/nebflow/core/delegate/WorktreeRescueSpec.scala` | **新**。纯文件面单测 4 条（无 git）：①未并报告被抢救（含嵌套目录、evidence、reports/ 之外绝不碰）②已并文件不重复覆盖（内容原样 + skipped 账面）③无 reports/evidence ⇒ 空账面零副作用（主工作区不被创建任何东西）④worktree 缺失/非目录 ⇒ 空账面绝不抛 |
| `src/test/scala/nebflow/core/delegate/PostMergeCleanupSpec.scala` | 增 ⑥：真仓集成——合并现场报告在主工作区缺失 ⇒ 移除前抢救回主工作区、worktree 照常移除、账本行携带 `rescue rescued=1`。`mkRepo` 加默认参 `extraFile`（零影响既有用例） |

## 2. 关键设计决策

- **消费点唯一**：全仓 worktree 删除只有一条路（`PostMergeCleanup.run` → `GitWorktrees.removeWorktree`，三个消费点：DelegateTerminal / ExternalSessionRunner / MergeQueueBootSweep 全经它），守卫挂一处即全覆盖，不另设扫描面。
- **幂等与安全**：只补缺失（`os.exists` 判定），worktree 被 git 拒绝保留（脏/锁定）时重清零副作用；单文件复制失败只 WARN 继续（`failed` 账面），顶层 Try 全包绝不抛、绝不阻断清理。
- **零超时**：无任何等待/重试机制——扫描与复制是纯本地文件面，最坏情形（巨树）也不产生阻塞语义；`os.walk` 每目录单独 Try 兜底。
- **审计**：每笔抢救/跳过落引擎日志（info 一行一文件），汇总进 ChainStore `worktree-cleanup` 行 detail（复用既有账本行种类，零迁移）。

## 3. 测试与验证

- `sbt testOnly nebflow.core.delegate.WorktreeRescueSpec nebflow.core.delegate.PostMergeCleanupSpec`：**Passed: Total 10, Failed 0**（2026-10-23 12:14:24 跑动记录；WorktreeRescueSpec 4 条 + PostMergeCleanupSpec 6 条全绿）。
- 既有 5 条 PostMergeCleanup 用例判言逐字未动——守卫是纯增量，kept/缺锚/no-op 路径行为不变。
- 实现期纠错一笔：os-lib `Path./` 对含 `/` 的 String 按单段拒收（`PathError$InvalidSegment`）——相对路径以 `os.RelPath` 对象参与 `ws / rel` 拼装，String 形态仅入账面/日志（WorktreeRescue.scala 内注释在案）。

## 4. 假设与边界

- 「主工作区」取 `projectWorkspace`（回落 `cwd`），与清理守卫既有口径一致。
- 报告卡路径解析面（卡片指向 worktree 绝对路径还是仓库相对路径）不在本批范围——本批只保证：worktree 内 reports/evidence 的文件在清理后必然能在主工作区同相对路径找到。
- 符号链接与非常规文件不搬（`isFile(followLinks = false)`），避免越界复制。
