# 团队功能删除 · 收尾（读数 / 能力删除清单 / 术语注记 / wiki 校验）

> 工作区 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-f97716ed`
> 分支 `delegate/delegate-nebflow-f97716ed` · 基线 `2af513702c81ff0522c952fb7ea7a43b59b578b2` · 收尾时 HEAD = `ae9647420`
> 本步 = **只读分析 + 写知识文档**：零 `src/` 改动、零 sbt、零 kill/signal（生产实例 PID 78176 / 6138 未触碰）。
> **阶段 3（收编 91 处引用 + 删 `FlowTreeActor` / `TeamSessionRegistry` 类型本体）被作者明确要求停住等确认，本步不执行**，只作为「待确认项」记录。

---

## 0. 一句话结论

团队功能今天**没有被删掉，只是被断掉了生产与入口**：阶段 1（停生产能力）+ 阶段 2（摘挂载入口）+ 数据归档三个提交已落；类型本体与 Mail 团队寻址 / AgentControl team scope **全部还在**。`FlowTreeActor` 45 处、`TeamSessionRegistry` src/main 89 处 —— **这两个数不是 0，不得写成「已归零」**。

---

## 1. 收尾 grep 读数（命令原文 + 读数）

统一形如 `git grep -n "<pattern>" <rev> -- <path> | wc -l`；改前 = `2af513702`，改后 = `ae9647420`（工作树与之相同、`git status --porcelain` 干净，故等价于 brief 的 `grep -rn ... | wc -l`）。

| # | 命令原文 | 改前 | 改后 |
|---|---|---|---|
| R1 | `git grep -n 'FlowTreeActor' <rev> -- src/ \| wc -l` | 53 | **45** |
| R1a | `git grep -n 'FlowTreeActor' <rev> -- src/main \| wc -l` | 47 | **41** |
| R1a2 | `git grep -n 'FlowTreeActor' <rev> -- src/main/scala \| wc -l` | 46 | **40** |
| R1b | `git grep -n 'FlowTreeActor' <rev> -- src/test \| wc -l` | 6 | **4** |
| R1c | `git grep -n 'FlowTreeActor' <rev> -- src/main/resources \| wc -l` | 1 | **1** |
| R2 | `git grep -n 'TeamSessionRegistry' <rev> -- src/main \| wc -l` | **91** | **89** |
| R2b | `git grep -n 'TeamSessionRegistry' <rev> -- src/test \| wc -l` | 181 | **182** |
| R3a | `git grep -n '/api/teams' <rev> -- src/main \| wc -l` | 12 | **11** |
| R3b | `git grep -n '/api/teams' <rev> -- src/test \| wc -l` | 10 | **7** |
| R3c | `git grep -n '/api/teams' <rev> -- src/main/resources/web \| wc -l` | 5 | **5** |
| R4 | `git grep -n 'treeBranch' <rev> -- src/ \| wc -l` | 4 | **0** |
| R4b | `git grep -n 'tree-branch' <rev> -- src/ \| wc -l` | — | 1（`ws.js:408` 注释改写后）|
| R5 | `git grep -n '/api/team/rules' <rev> -- src/ \| wc -l` | 3 | 4（+1 = 阶段 2 理由注；端点保留）|
| R6 | `git grep -n 'awaitRestore' <rev> -- src/ \| wc -l` | 17 | **4**（代码行 11 → 0，余 4 全为墓碑注）|
| R7 | `git grep -n 'MountTeam' <rev> -- src/ \| wc -l` | 6 | 6（代码行 5 → 4）|
| R8 | `git grep -n 'CancelPipeline' <rev> -- src/ \| wc -l` | 3 | 3（代码行 3 → 2）|
| R9 | `git grep -n 'ReloadDefinition' <rev> -- src/ \| wc -l` | 3 | 3（代码行 3 → 2）|
| R10 | `git grep -n 'MountedFlowStore' <rev> -- src/ \| wc -l` | 12 | 12（未动）|

### 剩余命中构成（不是垃圾，是阶段 3 待办面 / 记录）

- **`FlowTreeActor` 45 = 40（`src/main/scala`）+ 1（`src/main/resources`）+ 4（`src/test`）**；resources 那 1 处是 seed 插件样张文档 `visual-report/.../demos/tech-architecture.md:23`（实例文档非代码）。`src/test` 的 6 → 4 = `AwaitRestoreSpec` 整文件删带走 3 行、`DeviceFaceHardeningRoutesSpec:239` 新增理由注 +1 行。
- **`TeamSessionRegistry` src/main 89**：`FlowTreeActor.scala` 本体（`:37-278`）+ 内部调用；**`MailTool.scala` 最多**（团队寻址底座）；`AgentSessionExecution` / `ContextRefresher` / `AgentControlTool` / `AgentFinishTurn` / `WebSocketRoutes`（`isBusy`）；9 个 `Ws*Handlers` 的统一 import 行。
- **`/api/teams` src/main 11 = 全注释**（4 墓碑注 + 5 历史注 + 2 结构注），零活代码。
- **`/api/teams` src/test 7 = 断言清单**：`DeviceFaceHardeningRoutesSpec:227/228/236/242/256/257`（retired / stillServed 用例）+ `ActiveAgentsFilterSpec:19` 注释。**测试断言不是调用方。**
- **`/api/teams` 前端 5 = `flowViewers.js` 4 条 fetch（:223/256/309/345）+ `ws.js:1590` 1 条注释**。实测复核：这些 fetch 落在**零外部调用点**的函数内 ——
  ```
  openMailbox 0 / openDefinition 0 / openRules 0 / openNodeResultViewer 0 / refreshMailboxPending 0 次外部调用
  closeViewer 2 次命中全在 flowViewers.js 自己内部（:129 / :145）
  唯一 import 方 agentFileViewer.js:5 只取 openViewerShell
  ```

### 1.1 历史数据与归档目录的命中（**不算收尾失败**）

| 位置 | 读数 | 判读 |
|---|---|---|
| `~/.stand/archive/team-feature-deletion-20261104/`（113 文件，`a-w` 冻结）| `FlowTreeActor` 2（`RESTORE.md`、`agents/JUDGE-INDEX.md`）；`TeamSessionRegistry` 0；`/api/teams` 0；`treeBranch` 0 | 归档件是**证据**，记载「搬走了什么、怎么还原」是它的职责 |
| `~/.stand/docs/`（3783 文件旧文档库）| `FlowTreeActor` 62 / `TeamSessionRegistry` 6 / `/api/teams` 11 | 历史设计件，按「既有文档不追改」不动 |
| `<dataRoot>/projects/stand/.stand/docs/`（vault）| `FlowTreeActor` 20 / `TeamSessionRegistry` 7 / `/api/teams` 11 | 其中 4 篇是本批自己的阶段件 |
| `~/.stand/agents/` | `FlowTreeActor` / `TeamSessionRegistry` 0 | agent 面无团队声明（与 data-archive 判定一致）|
| `~/.stand/teams/`（活数据根）| 目录 0 / 文件 0 | 已归档并移出 dataRoot；空容器保留（`listTeams` 有 `!os.exists` 兜底）|
| `~/.stand/sessions/*/flows.json` | **24** | **活数据，刻意不动** |
| `~/.stand/sessions/*/flow-tree.json` | **0** | 阶段 1 已归档唯一 1 个 |

---

## 2. 能力删除清单（本批实际删掉的 12 条）

| # | 组 | 能力 | 原锚点 | 阶段 | 前端调用方 |
|---|---|---|---|---|---|
| 1 | 端点 | `GET /api/teams/mounted` | `PresenceRoutes.scala` 原 `:593-601` + `buildMountedTeamsJson` 原 `:2701-2745` | 2 | 无（仅 `ws.js:1590` 注释）→ `[RTE-R1] served=false` |
| 2 | 帧 | WS `treeBranchMounted` | `FlowTreeActor.scala` 原 `:309-313`、`:382` | 1 | 无 |
| 3 | 帧 | WS `treeBranchUpdated` | `FlowTreeActor.scala` 原 `:421` | 1 | 无 |
| 4 | 工具 | `Load(type:"team")` 分支（工具本体保留，enum → `["flow"]`）| `LoadTool.scala` 原 `:63-66` / `:72-101` / `:110-114` / `:116-144` | 2 | 无 agent 声明 `"Load"` |
| 5 | 腿 | 每会话无条件 flow-tree spawn | `WebSocketRoutes.scala` 原 `:299-301` + 原 `:489-517` | 1 | n/a |
| 6 | 腿 | 恢复等待腿 6 成员 + `treesRef` | `FlowTreeRegistry.scala` 原 `:21-69`；生产者 `FlowTreeActor.scala` 原 `:313` | 2 | 无（唯一消费者 = 第 1 条）|
| 7 | 腿 | rules 写后热重载（端点保留）| `PresenceRoutes.scala` 原 `:2120-2126` | 2 | `flowViewers.js:174` 写调用方**仍可用** |
| 8 | 腿 | WS `cancelFlow` 团队分支（帧 + `instanceId` 分支保留）| `WsSystemHandlers.scala` 原 `:319-326` | 2 | 无（前端 `cancelFlow` = 0）|
| 9 | 测试 | `AwaitRestoreSpec`、`LoadToolTeamValidationSpec` 整文件删 | `src/test/...` | 2 | n/a |
| 10 | 数据 | `~/.stand/teams/` 9 团队 103 文件 235,687 B → 归档根 | 数据面 | 4 | n/a |
| 11 | 数据 | `sessions/9d7638fb-…/flow-tree.json`（862 B, `97f111f8…bd74a`）| 数据面 | 1 | n/a |
| 12 | 数据 | `~/.stand/teams` 目录本体（`mv` → `~/.Trash/…__teams-originals`，零 `rm`）| 数据面 | 4 | n/a |

**一条都没删的两组**：Mail 寻址形态（团队名 / `team/agent` / 裸短名三形态照旧解析；`MAIL_TEAM_ADDRESS_RETIRED` / `MAIL_SHORTNAME_RETIRED` grep = **0，未实现**）；AgentControl team scope（`AgentControlTool.scala:233-241` / `:490-495` 全在）。

### 2.1 收尾时自行复核的两条（不转引上游结论）

```
$ git grep -n 'MountedFlowStore' HEAD -- src/
→ 12 处，全部落在 core/flow/ 两个文件：MountedFlowStore.scala 本体（:18/:77）
  + FlowTreeActor.scala 的 10 处（:24/:512/:522/:526/:572/:573/:581/:582/:682/:684）
⇒ 「7 个消费者全在 FlowTreeActor」成立：**该 store 在 FlowTreeActor 之外零消费者** ⇒ 删树即孤儿。

$ git grep -n 'getOrCreate' HEAD -- src/main/scala | grep -i flowtreeregistry
→ 仅 FlowTreeRegistry.scala 自身（:16 注释 / :39 定义 / :80 报错文案 / :84 end）
⇒ 「getOrCreate 零调用方」成立；全仓唯一 spawn 点
  `system.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")` 就在 FlowTreeRegistry.scala:72（getOrCreate 内）
⇒ 「本批之后没有任何 main 代码能再建一棵团队树」成立。
```

---

## 3. 阶段读数汇总 + baseline 核

| 阶段 | commit | 编译/测试 | 实测/静态 |
|---|---|---|---|
| 边界表 | `05e3a1880754974c6d87f39093adac384377d4b0` | — | 纯文档 |
| 阶段 1 | `63b1e0e0025969ce494c8482b37d6e62df9249e4` | compile exit 0（全量 749 sources / 71 s；增量 2 / 10 s；`-Xfatal-warnings` ⇒ 零 warning）；3 spec Passed 18 / Failed 0 / exit 0 | 实测（上游跑）|
| 阶段 2 | `4b7c768020b47c7edce9ee96bb29082853d10fd4` | `compile` + `testOnly …` exit 0 / 26 s；4 spec Passed 28 / Failed 0 / Errors 0；`[RTE-R0] served=true`、`[RTE-R1] retired GET /api/teams/mounted ⇒ served=false`、`[RTE-R2] live GET /api/team/rules/:name ⇒ served=true` | 实测（上游跑）|
| 数据归档 | `ae96474204a8a46b1386c4c1f492e44bd799bef6` | 无编译（零 src 改动；sha256 103/103 全等，四条独立链）| 静态 + 完整性校验 |
| 本步 | 见 `git log` | **未跑 sbt**（见下）| 静态证明 |

**本步为什么不跑 sbt（如实标注）**：`git diff --stat 4b7c76802..HEAD -- src/` **为空** —— 阶段 2 的绿编译产出之后，`src/` 未被任何提交改动（阶段 4 与本次均零代码改动，`git show --stat ae9647420 -- src/` 为空）。故复用阶段 2 的实测读数，不假装新跑。

**baseline 未抬高**：`git diff --stat 2af513702..HEAD | grep -Ei "baseline|allowlist|process-artifacts"` ⇒ **空输出**（`tests/type-baseline.json`、`tests/visual/baseline/*`、`scripts/component-contract-baseline.json`、`process-artifacts-allowlist.txt` 全未出现）。

**process-artifacts 闸（顺带跑，便宜）**：

```
$ bash scripts/check-process-artifacts.sh
✓ process-artifacts gate clean: git ls-files evidence/ reports/ docs/ = 0 违规（0 个被白名单豁免）
  白名单：0 条生效（scripts/process-artifacts-allowlist.txt）
EXIT=0
```

**分支纪律**：`main` tip 仍 `2af513702`；`git status --porcelain` 干净；零 kill/signal；`src/main/resources/web/` 本批唯一改动 = `ws.js:408` 一行注释；`flow-map` / `FlowDagExecutor` / `chain` / `ChainStore` / `MergeQueue` / `MountedFlowStore` 零改动。

---

## 4. 术语文档注记（不许静默消失）

目标件：`/Users/kaiyu/.stand/projects/stand/.stand/docs/actor-as-agent-framework-creation-paths-and-invariants__20261104_214436__delegate-nebflow-f3849ed1.md`

| 落点 | 追加内容 |
|---|---|
| §8.1（原 `:421`/`:423` 词条与其「删除令与阻止删除」注之后）| 日期化补注：作者 22:13 令「团队功能整体删掉」；阶段 1/2/归档 各自的锚点与 commit；**类型本体未删、等作者确认后才执行阶段 3**；Mail 三形态与 AgentControl scope 仍活；**「不许静默消失」永久条款**（连「东西真删干净以后也不得改写成已删除，只能再追加日期+提交号」这条纪律本身也写进文档）|
| §8.2（原 `:446`「表内锚点状态补注」之后）| 补注：拆文件建议**一步都没走**；本表两行锚点（`FlowTreeActor.scala:98`、`MailTool.scala:2505`、`FlowTreeActor.scala:36-60`）今天**仍有效且仍活**；但「团队挂载」已不可达 ⇒ 描述的是「类型还在、寻址还在、新实例不再诞生」的中间态；该中间态刻意、可回滚（两阶段两提交）|
| §10.3（原 `:554` 裁点 2 之下）| 补注：该裁点已被作者回答（选了「团队功能整体」）；四阶段状态；**方向已定但「类型与寻址」两层还在**；阶段 3 的 5 条待确认点指向本文；禁止日后改写成「已删除」而不追加日期与提交号 |

**关键纪律**：全程**未**把词条写成「已删除」「已移除」「已下线」——东西还在，那种写法就是假记。

---

## 5. wiki 校验

```
$ python3 /Users/kaiyu/.stand/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py --project stand
wiki-docs 校验：通过（/Users/kaiyu/.stand/projects/stand/.stand/docs，369 篇文档，零违规）
EXIT=0
```

改前读数 = 368 篇零违规（EXIT=0）；本步新增 1 篇知识文档 + 3 处补注后 = 369 篇零违规。
链接解析复核（`--graph`）：新文档出边 6 条全部解析成功（boundary / flow-tree-actor-blocked / stage1 / stage2 / stage4 / glossary），零未解析。

---

## 6. 合规自检

- [x] 零 `src/` 改动：`git status --porcelain` 仅 `reports-evidence/` 下本文件。
- [x] 未跑 sbt（`ps aux | grep -c "[s]bt"` = 5 读数只记录，未触碰 PID 78176）。
- [x] 未 commit 到 main、未 reset/clean。
- [x] 未执行阶段 3（未删任何类型本体、未收编任何引用）。
- [x] 未写「已删除」假记；未改他人文档（术语文档只追加、不删改原文）。
- [x] wiki check EXIT=0。
