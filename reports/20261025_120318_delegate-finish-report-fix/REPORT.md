# delegate 双段汇报工作结果丢失 · 修复报告

- 批次：delegate-finish-report 修复批
- 日期：2026-10-25
- 作者令（逐字）：「现在 delegate report merge 这次是比较详细的汇报，而这次汇报的内容不会回传，而是汇报 delegate report finish，这次汇报的一般就是合并的结果而不是工作的结果，导致工作结果丢失。」
- 分支：`delegate/delegate-nebflow-fef7afcb`（worktree 作业，未触 main）

## 1. 根因（三条，代码定位）

delegate 生命周期 = 工作轮（终态判 ready-to-merge）→ 合并轮（finish 终态投父）。工作结果在三处丢失：

| # | 丢失点 | 位置（行号为修复前） |
|---|---|---|
| ① | 工作轮终稿（delegate_report merge 轮的终文——详实汇报）停靠即弃：`HoldForMerge` 只入队，resultText 不落盘、不投父 | `DelegateTerminal.scala` `holdForMerge`（原 L702-778）；`BackoffSupervisor.completeNormally` 对 HoldForMerge 不投递（设计如此——合并轮需 delegate 活着） |
| ② | `delegate_report` 状态槽是**单槽**：merge 轮申报的详细工作汇报被 finish 轮的「合并完成」短报**覆写** | `DelegateRegistry.updateReport`（原 L174-191，reportStatus/reportDetail 单值覆写） |
| ③ | 合并轮终态投父的 payload 只由合并轮终文组装（`payload + suffix`），父会话只见「合并完成」 | `BackoffSupervisor.scala` completeNormally（L166-168 payload 组装）→ `DelegateTerminal.onCompleted` Release 尾注（L288/L294-299） |

父会话消费点核实：`AgentActor.buildEventReminder`（L245-265）把 ExternalEvent payload 逐字注入 system-reminder——payload 里有什么父会话就见什么；故修复面在 payload 组装侧（引擎自动附页），父侧组装零改动即达。

## 2. 修法（三条根因逐条闭合，向后兼容）

**机制**：新增 `DelegateRegistry.workReport` 工作结果槽（持久，Option 字段缺键容忍）+ `DelegateWorkReport` 附页构造器（合并轮 Release 投父时自动拼接）。

1. **根因①**：`DelegateTerminal.holdForMerge` 新增 `resultText` 入参——停靠点把工作轮终稿保真进 `updateWorkResult`（**first-write-wins**：首个非空文本落槽后幂等零写）。fail-soft：保真失败只 WARN。
2. **根因②**：`DelegateRegistry.updateReport` preserve-before-overwrite——单槽覆写发生且旧 detail 非空、与新 detail 不同、workReport 槽尚空 ⇒ 旧 detail 留底进 workReport（老 delegate 无停靠保真时 merge 轮申报 detail 即回落的工作摘要；停靠保真已落槽则优先级更高、绝不被顶掉）。
3. **根因③**：`DelegateWorkReport.appendixFor(record)` 三段附页（全 fail-soft，任一段不可得即缺席；三段全空 = `""`——**老 delegate 逐字退化现状文本**）：
   - `[delegate-finish] merge status: <finish 申报 detail 首行截 200>`——合并信息降为一行状态；
   - `[delegate-finish] work result (preserved from the work phase…):` + workReport 截 2000 字符；
   - `[delegate-finish] report files (auto-attached):` — 基分支 tip 须为**双亲 merge 提交**（与 MergeAcceptance 同判据，未真并 = 缺席、不误归他人提交），`git diff --name-only <merge^1> <merge>` 中 basename=REPORT.md 的文件（≤3 个），每个携相对路径 + 首 40 行/2400 字符摘要。路径为基工作区相对路径——PostMergeCleanup 的 WorktreeRescue 已保证 reports 落主工作区，worktree 回收后路径仍可达。
   - 拼接点：`DelegateTerminal.onCompleted` 合并轮两个 Release 臂（Completed / CompletedNote）的尾注末尾；IO.blocking + fail-soft 空串兜底，绝不拖垮终态投递。非合并轮零附页（终文即工作结果本体）。
4. **提示词面对齐**（🔴 模型可见文案，§16 待过目）：`MergeQueue.mergeBriefFor` 增一行 finish 报告契约（终稿以工作结果为主、合并信息一行）；`DelegateReportTool` finish 确认文案同步。引擎附页是保底，delegate 自述摘要是第一交付面。
5. **外部执行器腿同机制**（同损失形态，低成本顺带）：`ExternalSessionRunner.settle` ReadyToMerge 入队前保真 `updateWorkResult(sessionId, text)`；合并轮投父前拼 `appendixFor(rec)`。两腿同一附页契约。

## 3. 交付物

| 文件 | 变更 |
|---|---|
| `src/main/scala/nebflow/core/delegate/DelegateWorkReport.scala` | **新增**：附页构造器（compose 纯函数锚 + 双亲 merge 第一亲 diff + 摘要截断） |
| `src/main/scala/nebflow/core/delegate/DelegateRegistry.scala` | Record +`workReport` 字段（手工 Decoder 缺键容忍）；`updateReport` 留底覆写；新增 `updateWorkResult`（first-write-wins） |
| `src/main/scala/nebflow/core/delegate/DelegateTerminal.scala` | `holdForMerge` +resultText 保真；onCompleted 合并轮 Release 尾注拼附页（fail-soft） |
| `src/main/scala/nebflow/core/delegate/MergeQueue.scala` | mergeBriefFor finish 报告契约行 |
| `src/main/scala/nebflow/core/tools/DelegateReportTool.scala` | finish 确认文案（工作结果为主、合并一行）+ 文档注 |
| `src/main/scala/nebflow/core/executor/ExternalSessionRunner.scala` | 外部腿 ReadyToMerge 保真 + 合并轮投父附页 |
| `src/test/scala/nebflow/core/delegate/DelegateFinishWorkResultSpec.scala` | **新增** spec（①-⑤ 见 §4） |

## 4. spec（新批判红锚）

`DelegateFinishWorkResultSpec`（真仓夹具，零触主仓）：

1. **① 停靠保真**：judge ready-to-merge ⇒ HoldForMerge 时 workReport = 工作轮终稿；重停靠 first-write-wins 不顶掉。
2. **② 留底覆写**：merge→finish 覆写 ⇒ 旧 detail 留底；已保真槽不被申报顶掉；同 detail 重报零留底。
3. **③ 附页回传 e2e**：真仓真并（机械验收判据：双亲 merge ＋第二亲=支头）——Release 尾注含 merge status 一行、work result 保真段（unique marker）、REPORT.md 相对路径 + 内容摘要行、既有判词行不回归。
4. **④ 向后兼容**：老盘面形态（无保真、无报告文件、finish 自申报在槽）⇒ 附页仅 merge status 一行、无 work/files 段；全裸记录（连申报都无——unjudged 腿）⇒ 尾注逐字现状（byte-identical）。
5. **⑤ 纯锚**：appendixFor(None)=""；无工作文本无 git 锚的记录 = ""（不产空附页）；workReport 截断边界 Bound.WorkChars；compose 三段全空 = ""。

**sbt 结果**（本 worktree 实跑，KAI 迁移纪律——分支内先跑绿）：

```
# 定向跑（新 spec + 邻域四 spec）
sbt "testOnly nebflow.core.delegate.DelegateFinishWorkResultSpec
           nebflow.core.delegate.DelegateTerminalSpec
           nebflow.core.delegate.DelegateRegistrySpec
           nebflow.core.delegate.MergeQueueSpec"
[info] Passed: Total 29, Failed 0, Errors 0, Passed 29

# 邻域面（delegate 全包 + executor 全包 + DelegateTool/DelegateReportTool/
# DelegatePluginAllocation + TaskSearchIndex）
sbt "testOnly nebflow.core.delegate.* nebflow.core.executor.*
              nebflow.core.tools.DelegateReportToolSpec nebflow.core.tools.DelegateToolSpec
              nebflow.core.tools.DelegatePluginAllocationSpec nebflow.core.index.TaskSearchIndexSpec"
[info] Passed: Total 205, Failed 0, Errors 0, Passed 205
```

（过程注记：默认堆下 `Test/compile` 全量 829 源曾 OOM——`SBT_OPTS="-Xmx6g"` 重跑即绿，与本仓当日 boot-oom-attribution 报告的机器内存面同族，非本批代码问题。）

## 5. 未做 / 开项

- **外部腿的 REPORT.md 附页段落**已随机制生效；外部执行器不能调 delegate_report（零平台工具），其工作摘要只靠 settle 保真的终文——提示词面契约对外部腿无效（零注入），属已知边界。
- 合并轮**冲突/拒绝**形态（CompletedNote 留队重试）：附页照拼（workReport 段在、report files 段因非双亲 merge 自然缺席）——拒绝详情仍走既有 `[delegate-judge] note:` 行，未在本批改写。
- `TaskSearchIndex` upkeep 的索引文本 hash 键为 reportStatus/reportDetail 面，workReport 未入索引键——新字段不触发额外重嵌（可后续评估是否值得入检索面）。
- 提示词终稿归 N3 提示词批（mergeBriefFor / 工具文案本批为最小成稿，§16 待过目）。

## 6. 关键假设

- 「工作结果」的权威载体 = 工作轮终稿（判 ready-to-merge 那次完成的 resultText）；merge 轮申报 detail 为回落摘要——与 delegate 纪律（终文即交付）一致。
- REPORT.md 判据取 basename 精确匹配（大小写不敏感），不假设目录形制——报告惯例 `reports/<ts>_<topic>/REPORT.md` 是本仓约定而非引擎硬编码。
