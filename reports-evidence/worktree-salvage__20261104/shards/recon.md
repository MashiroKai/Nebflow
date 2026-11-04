# 脏工作树落地保全批二轮 —— 开工前现算盘点与分片

只读盘点。本节点未对任何树执行 checkout / reset / clean / stash / commit / merge / remove，未删分支、未 kill 进程。
全部读数取于 2026-11-04 18:20~18:23（本机时间），main tip 现算。

## 1. main tip

| 项 | 值 |
|---|---|
| `git -C "/Users/kaiyu/Claude code/Stand" rev-parse HEAD` | `0fffa26b51e130f012329269d27f70be19fa3ab5`（短 `0fffa26b5`） |
| 当前分支 | `main`（主仓工作树干净基线未动） |
| 上一轮清仓批盘点时 tip | `d2fc65c82`（执行时）/ 收尾 `1bfe6892d`，见 inventory.md §0 |

> 判定口径：`ahead_main` = `git rev-list --count main..HEAD`；`dirty_count` = `git status --porcelain` 的非空行数。全部命令一律 `git -C <path> ...`。

## 2. worktree 登记总数

`git -C <repo> worktree list --porcelain` = **123** 条 = 主仓 1 + 树 122（上一轮收尾为 120，本批期间新起 3 条：`delegate-nebflow-71767a56`、`delegate-nebflow-7ecba82c`、`delegate-nebflow-750143b4`＝本节点自身）。

候选集合构造（任务书口径）：

| 步骤 | 条数 |
|---|---|
| 登记总数 | 123 |
| − 主仓自身 `/Users/kaiyu/Claude code/Stand` | −1 |
| − 本节点所在树 `delegate-nebflow-750143b4` | −1 |
| − 目录已不存在 | 0 |
| = **现存活树（全部逐棵算过四项）** | **121** |
| 其中：上一轮 §4.2∪§4.3∪§4.4 命中（in_inventory=Y） | 102 |
| 其中：清单外因「当前 status 非空」或「detached/有未合」而入选 | 9 |
| 其中：干净且已合 main、三类清单都不在 ⇒ 不入本批待处理集 | 10 |

> `inventory §4.4` 的 `/private/tmp/cs1030-pristine` 上一轮已 prune 掉，本次登记里不存在，故不计。
> `inventory §4.1`（上一轮 16 棵活树）里仍有 9 棵在登记中，它们不是本批候选来源，但其中 2 棵现在带工作（`183b016d` dirty 3、`fbe4618c` dirty 2/ahead 3）已按「status 非空」入榜；其余 7 棵干净已合或已消失。

## 3. 分片结果（7 个文件，三类互斥不重叠，合计 103）

| shard | 棵数 | 口径 | 备注 |
|---|---|---|---|
| `shards/dirty-1.txt` | **19** | 非 detached 且 `dirty_count>0`，按路径排序后轮转桶 1 | dirty 行数合计 292，ahead 合计 24 |
| `shards/dirty-2.txt` | **19** | 同上，桶 2 | dirty 合计 121，ahead 0 |
| `shards/dirty-3.txt` | **19** | 同上，桶 3 | dirty 合计 98，ahead 18 |
| `shards/dirty-4.txt` | **18** | 同上，桶 4 | dirty 合计 77，ahead 7 |
| `shards/dirty-5.txt` | **18** | 同上，桶 5 | dirty 合计 72，ahead 8 |
| `shards/unmerged.txt` | **2** | `ahead_main>0` 且 `dirty_count==0`，非 detached | 见下表 |
| `shards/detached.txt` | **8** | 全部 detached HEAD | 含 `/private/tmp` 下 6 棵 + 仓库内 2 棵 |
| **合计** | **103** | 待处理集合（已剔除全部活树） | |

轮转校验：dirty 93 棵 → 19/19/19/18/18，最大最小差 **1** ≤ 1 ✅；三类交集为空（脚本断言 + `sort | uniq -d` 输出 0 行）✅；各文件无空行 ✅。

> dirty 桶按棵数均衡，**不按 dirty 行数均衡**（任务书要求「按路径排序轮转」，故 `089582`/`189e295a` 这类 189 行的重树会落进同一桶，桶 1 因此偏重：292 行）。分批执行时建议按 manifest 的 `dirty_count` 再做一次内部排序，重的先做。

### unmerged.txt（2 棵，与任务书预期一致）

| 路径 | 分支 | dirty | ahead_main |
|---|---|---|---|
| `…/worktrees/delegate-nebflow-92944aba` | `delegate/delegate-nebflow-92944aba` | 0 | **1** |
| `…/worktrees/delegate-nebflow-98f9ca81` | `delegate/delegate-nebflow-98f9ca81` | 0 | **6** |

### detached.txt（8 棵）

| 路径 | HEAD | dirty |
|---|---|---|
| `…/worktrees/delegate-nebflow-6110565f` | `ec4a7f96d4e3` | 0 |
| `…/worktrees/tmp-bisect-63be` | `1a1cfe29cecc` | 0 |
| `/private/tmp/capsule-before` | `73c305f813a3` | 0 |
| `/private/tmp/g2-base-check` | `bc437df64e02` | **24** |
| `/private/tmp/lg-baseline` | `bc437df64e02` | 0 |
| `/private/tmp/lg-current` | `312d6fb28a50` | **24** |
| `/private/tmp/np-baseline` | `c4ac5a81658c` | 0 |
| `/private/tmp/verify-main-baseline` | `7fabc3eb68bf` | **12** |

> `/private/tmp/cs1030-pristine` 已在上轮 prune 中消失。`/private/tmp/bashoverhaul-baseline-aade799e` 也是 detached，但因活进程证据被剔出待处理集（见 §4）。

## 4. 活树（一律剔除待处理集，manifest 留行留证据）

判定口径 = 两条硬证据取并集：① 一次 `lsof -n -P` 全量快照（`/tmp/r2_lsof_recon.txt`，18591 行）里任一进程的 cwd 或打开文件落在候选树路径下；② `~/.stand/delegates/*.json` 中未归档、`reportStatus ∉ {finish, merge}`、最后活动（`standbySince`/`createdAt` 取大）≤27h 的 delegate 的 worktree 命中。共 1163 份 delegate 记录，其中满足②的 **15** 份，命中 Stand 登记树 **8** 棵。

**活树 8 棵（全部在待处理集外）**

| 路径 | 依据 | dirty / ahead |
|---|---|---|
| `…/delegate-nebflow-4280aa54` | lsof cwd（bash×3, sleep）＋ delegate standby 3.8h | 2 / 0 |
| `…/delegate-nebflow-485bb4f4` | lsof cwd+open 19 条（**java**、git、bash、tee）＋ delegate standby 5.2h | 3 / 0 |
| `…/delegate-nebflow-70e8d8ab` | delegate standby 6.4h（无 lsof 命中） | 1 / 0 |
| `…/delegate-nebflow-79768ef5` | delegate standby 4.1h | 1 / **21** |
| `…/delegate-nebflow-79bb6698` | delegate standby 19.6h | 15 / 0 |
| `…/delegate-nebflow-7ecba82c` | lsof cwd（bash, node, chrome-headless×4）＋ delegate `reportStatus=None` 0.1h（在飞） | **28** / 0 |
| `…/delegate-nebflow-b3bdf8d3` | lsof cwd（bash, sleep）＋ delegate standby 1.4h | 6 / 0 |
| `/private/tmp/bashoverhaul-baseline-aade799e` | lsof cwd：3 个 `sleep`（pid 61022/61907/62804） | 0 / 0 |

**⚠️ bashoverhaul 的证据性质**：只有 `sleep` 进程 cwd 落在树内，无 delegate 记录、无 git/java 进程、dirty=0/ahead=0 —— 高度疑似孤立睡眠进程（不是真在飞批次）。按任务书「取并集」口径仍判活并剔除；但它是 detached 快照且**零工作**，删与不删都不损失内容，aggregate 节点删前复核若发现 sleep 已消失可自行改判。

**另有 2 棵「有活证据但不属候选集」**（干净且已合 main，本批本就不处理）：

| 路径 | 依据 |
|---|---|
| `…/delegate-nebflow-71767a56` | delegate standby 0.0h（dispatcher，本批期间新起） |
| `…/delegate-nebflow-be138470` | delegate standby 6.4h |

> 本节点自身（`delegate-nebflow-750143b4`，standby 0.1h）已在候选构造阶段排除，未进 manifest。

## 5. 清单外新增树

`in_inventory=N` 且进入了待处理集的有 9 棵（其余 10 棵 `in_inventory=N` 干净已合，见 §6）：

| 路径 | dirty | ahead | 来源说明 |
|---|---|---|---|
| `…/delegate-nebflow-183b016d` | 3 | 0 | 上轮 §4.1 活树，现已无持有者 ⇒ **dirty-5** |
| `…/delegate-nebflow-fbe4618c` | 2 | 3 | 上轮 §4.1 活树，现已无持有者，dirty 且有 3 条未合提交 ⇒ **dirty-1** |
| 其余 7 棵 | — | — | 均为 §4.1 活树中现在带 dirty 的（`4280aa54`/`485bb4f4`/`70e8d8ab`/`79768ef5`/`79bb6698`/`7ecba82c`/`b3bdf8d3`）——但它们全部在活树集内，**不会进 shard** |

结论：**真正新进入待处理集合的只有 `183b016d` 与 `fbe4618c` 两棵**（已分别落进 dirty 桶，桶号见 manifest）。上一轮 §4.2 的 91 棵零流失（现算 dirty 全部仍 >0），§4.3 的 2 棵与 §4.4 的 9 棵（cs1030-pristine 已消失）全部在册。

## 6. 干净且已合 main、不入本批待处理集（10 棵，manifest 里 bucket=`out-of-scope:clean-merged`）

`delegate-nebflow-01a0ffe7`、`1d300d2e`、`4d537ce5`、`6f8eab41`、`71767a56`(活)、`835d17e9`、`af261318`、`b1d91721`、`be138470`(活)、`/private/tmp/cc-before` —— 全部 `dirty=0 / ahead=0`。

> `cc-before` 上轮靠 lsof 判活（§6 明确列为「有活进程 cwd 在里面」），本次 lsof 已无命中、delegate 也无记录 ⇒ **已失活且零工作**。它是 detached + 干净 + 已合，可直接删；但不在本批候选口径内，留给后续清仓批。`01a0ffe7/1d300d2e/4d537ce5/6f8eab41/835d17e9/af261318/b1d91721` 同理（均为上轮 §4.1 活树，现已失活、零工作）。

## 7. 开工基线读数（聚合节点算回收用）

| 指标 | 读数 | 命令 |
|---|---|---|
| 可用空间 | **69Gi**（Used 832Gi / 926Gi，93%） | `df -h "/Users/kaiyu/Claude code/Stand"` |
| 可用空间（KB 原值） | Avail **72133084** KB ≈ 68.8 GiB | `df -k "/Users/kaiyu/Claude code/Stand"` |
| `.nebflow/worktrees` 占用 | **49G** | `du -sh .nebflow/worktrees`（cwd = 主仓） |
| `/private/tmp` 占用（参考） | 13G | `du -sh /private/tmp` |

> 上一轮收尾读数为 `47G` / 可用 73Gi；本批开工为 `49G` / 可用 69Gi —— 期间新增 3 条登记树，且其他批在跑 sbt 构建，可用空间反向流失约 4Gi。**回收核算请以本节读数为基线**，不要复用上一轮的 47G/73Gi。
> `df` 在 du 前后各读一次，两次一致（Available 69Gi）。

## 8. 产物

| 文件 | 内容 |
|---|---|
| `shards/dirty-1.txt` … `shards/dirty-5.txt` | 19/19/19/18/18 行，每行一个绝对路径 |
| `shards/unmerged.txt` | 2 行 |
| `shards/detached.txt` | 8 行 |
| `shards/manifest.tsv` | 121 行 + 表头；列 = `path / branch / dirty_count / ahead_main / in_inventory / live / bucket` |
| `shards/recon.md` | 本文件 |

`manifest.tsv` 覆盖全部 121 棵现存活树（不止 103），`bucket` 取值：`dirty-1..5`（93）、`unmerged`（2）、`detached`（8）、`live`（8）、`out-of-scope:clean-merged`（10）。

## 9. 边界自查

| 约束 | 结果 |
|---|---|
| 只读 | 只跑 `git status --porcelain` / `git rev-list --count` / `git worktree list` / `lsof` / `df` / `du` ✅ |
| 未 checkout/reset/clean/stash/commit/merge | 一次未跑 ✅ |
| 未删树 / 未删分支 | 一次未跑 ✅ |
| 未 kill 任何 sbt/java/nebflow 进程 | 仅 `lsof` 读快照 ✅ |
| 未触碰 `/private/tmp/x1367-restored`、`/private/tmp/x1367-backup` | 未访问 ✅ |
| 主仓工作树 | 未写入（本节点全部产出落在自身树的 `reports-evidence/…` 下）✅ |
