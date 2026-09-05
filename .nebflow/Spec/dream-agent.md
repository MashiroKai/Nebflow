# dream-agent 批 spec —— 梦境智能体（第 4 个 keeper，每日自动记忆审查）

- 日期: 2026-09-05
- 批次: dream-agent（Project+Node 架构；方案上下文 = memory-management-plan.md §4 审计 / §5 简练标准）
- 作者: dream-agent 分支开发节点（沙箱会话，Caller: project-dispatcher）
- 上游: memory-mech（n-b4dbcb58）——本支工作树 = 上游终态逐文件字节级复制 + 本批叠加；落地顺序 **memory-mech 先落 main → dream-agent 后并**（同内容 diff 自动消解）
- 协调: project-memory 批（在飞）——其 §4 明确「dream 批的全局记忆 snapshot-on-write 机制在其对象面（路径模式）上扩展即覆盖项目记忆，无需本批预留接口」

---

## 0. 结论速览

| 决定 | 定稿 | 一句话依据 |
|---|---|---|
| 权限模型 | **c（混合），分两阶段**：本批=报告制（机械/裁定分级标注）+ 引擎侧 snapshot-on-write；下批（需作者签准，触及 2026-08-31 裁定①）=MemoryEdit 准入扩展 agent=dream，机械类自动执行 | MemoryEdit 通道严格优于沙箱写例外；能力扩展不动职权单点 |
| 面板可见性 | **磁盘加载即见，零代码 hunk** | `AgentService.listAgents → loadAll → scanDisk`（AgentLibrary.scala:76-84），Seeds.all 仅 Nebula 兜底 |
| 触发时刻 | **每日 04:45（本地 UTC+8）** | 4 日 router 实测：04:00-05:00 用户轮次为零；总请求量 04:45 桶区间内最静（152 vs 185/187/295） |
| 活跃守卫 | 触发前 60 分钟内有用户轮次（agent=Nebula + channel=web + messages_count≤4）→ 当日放弃 | 深夜活跃=连续工作段，+30min 二次槽命中率低；日审时间常数（T2=7d/T3=14d）≫1 天；实现最简零状态 |
| 守卫位置 | **Nebula 跳（fired task 内容自带守卫指令）** | Schedule 只能注入所属会话（ScheduledTaskActor → ExternalEvent），Nebula 本就要转 Task 调用，顺带 Bash 查日志零加跳 |
| 快照先行 | **引擎侧 snapshot-on-write**（MemorySnapshot，本批新引擎件） | `git -C ~/.nebflow ls-files` 实测两文件不在跟踪层（.gitignore `/*` + `**/memory.md` 显式排除）——git 留史不可依赖；写面单点（MemoryEditTool + DreamMode）全部接闸，绕不过去 |
| 备份目录 | `~/.nebflow/memory-backups/<ts>/`（每目标保留 20 份，fail-closed） | NebflowBackup 日备是 24h 灾备层，两次日备间的写它看不见——细粒度回滚锚独立建 |
| 周接线 | **退役 Sunday 21:30 memory-consolidation 注册（若存在）→ 梦境日审单班**；scanner 报告格式（九项指标+条目级动作）被 dream 复用 | 周审计与日梦境职责重叠（都是 report-only scanner）； retention：周 scanner 的深度语义复核并入梦境裁定类清单 |

## 1. 权限模型选型（定稿 + 证据链）

**三选一：c（混合），且写能力的落地路径钉死为 MemoryEdit 准入扩展，否决沙箱写例外。**

### 1.1 引擎实现路径实测（三案对比）

**案 a——dream 节点沙箱写例外：否决（结构劣势 + 面积大）**
- `SandboxPolicy` 写面 = `writableRoots = root + extraWritable + tempRoots`（目录粒度，`contains()` 前缀语义）；**文件级写例外面不存在**。放行 `~/.nebflow` 目录 = 凭据层裸奔（auth.json/vps.env/nebflow.json 同目录），违反凭据红线。
- 按会话定向（agent=dream）：`SandboxConfig` 是实例级配置（nebflow.json），`forRoot` 无 agent 身份入参——定向需打通 AgentCore spawn → 策略构造的身份链，新机制面积大。
- 更本质：绕开 MemoryEdit 的裸写路径会丢掉已落地的全部护栏——条目级操作（无全文重写）、单条目 guard、per-file 锁、上游 MemoryBudget 预算闸。**同一能力，更差形态。**

**案 b——纯报告：否决（存在价值空心）**
- 与 memory-mech scanner-only 周审计职责完全重合；dream 降级为「更高频的 scanner」，无独立价值主张。

**案 c——混合：采纳，但「机械类自动执行」的正确引擎路径是 MemoryEdit 准入扩展**
- 实测 `AgentCore.NebulaExclusiveTools = Set("Schedule","Delegate","AgentControl","MemoryEdit")`，注释明文「非 Nebula agent 声明了也不给」，背书 = **2026-08-31 裁定①「记忆=Nebula 专属」**。dream 获得写能力必须动这行——这是用户裁定面，不是纯技术面，**需作者签准，本批不实施**。
- 签准后的形态（下批）：准入从 `agentName == "Nebula"` 扩为 `agentName ∈ {"Nebula","dream"}` + **动作面白名单**（dream 仅 remove/update/replace_section，无 append——「禁止写新记忆」铁律的工具面强制）。预算闸（MemoryBudget）与快照闸（本批 MemorySnapshot）自动覆盖 dream 的调用——零新增护栏工作。
- 本批交付的混合形态：报告逐项标注【机械】/【裁定】，Nebula 逐字执行机械项、逐条裁量裁定项——职权单点不破，执行成本低到「照单全收」。

### 1.2 快照先行（本批引擎交付，任何执行路径先过它）

`git -C ~/.nebflow ls-files User.md agents/Nebula/memory.md` = **空**（exit 1）；`check-ignore -v`：`/* `（白名单根）挡 User.md、`**/memory.md`（第 20 行「memory.md pending user decision」）挡 agents/Nebula/memory.md。**结论：快照先行不可依赖 git 留史，备份目录独立建。**

- 新引擎件 `nebflow.service.MemorySnapshot`：`snapshotBeforeWrite(target)` 读磁盘当前态 → 写 `<dataRoot>/memory-backups/<yyyyMMdd-HHmmss-SSS-<seq>>/<折叠名>` → 修剪（每目标 KeepPerFile=20）→ Right(备份路径)；任何失败 Left(reason)。备份文件名折叠自描述（`User.md` / `agents__Nebula__memory.md`）。
- **接线点（全部记忆写面，无一遗漏）**：
  1. `MemoryEditTool` 四动作（append×2/update/remove/replace_section）——`saveGuarded()` 包住全部 5 个 save 位点，在 per-file 锁内执行（快照-写入窗口与并发 MemoryEdit 互斥）；
  2. `DreamMode.updateMemory`（hook 写面）——快照失败 → `MergeResult.SnapshotBlocked`，合并跳过零写入（hook 不写无备份的覆盖）。
- **fail-closed 语义**：快照失败 = 写入整体中止（`MEMORYEDIT_SNAPSHOT` 结构化错误，目标文件原样）。理由：两文件无 git 史，无备份的覆盖不可回滚；写失败可重试，覆盖失败不可逆。
- 与 `NebflowBackup`（`~/.nebflowBackup/` 日备、保留 7 份）的关系：24h 灾备层 vs 逐写细粒度层，互补不替代。
- 验收：`MemorySnapshotSpec` 5 测 + `MemoryEditToolSpec` +3 测（备份内容==写前真身 / 四动作全覆盖 / fail-closed 零写入）+ `DreamModeSpec` +2 测（hook 快照 / SnapshotBlocked）+ e2e 实例级验证（§5）。
- 对象面扩展：project-memory 批落地后，`<workspace>/.nebflow/memory.md` 写面（MemoryEditTool → ProjectMemory.save 单点）按同模式扩展即覆盖项目记忆（该批 §4 已预判，零接口预留）。

### 1.3 预算闸复用（上游 memory-mech 交付，直接继承）

接线点 = `MemoryBudget.verdict(target, bytes)`（上游新件）：MemoryEditTool append/update 落盘前校验（硬顶 50KB/30KB 拒绝 `MEMORYEDIT_BUDGET`，80% 软警 40KB/24KB WARN）+ DreamMode.updateMemory 同判据（超限跳过+WARN）。dream 报告的每个动作项标注预算影响（清理后预估字节），与本闸形成「报告承诺 ↔ 闸 enforcement」闭环。本支零改动、纯继承。

## 2. agent 定义（staging → 宿主 cp → ~/.nebflow git commit）

- `staging/agents-dream/agent.json`：tools = Read/Grep/Glob/Bash（**无 Write/Edit/MemoryEdit**——铁律的结构保证）；category=standalone；preset=general；skills=[memory-consolidation]（方法论 on-demand）。
- `staging/agents-dream/system.md`：铁律四条（禁写新记忆/零直写/宁放过勿错杀/快照纪律入报告）；对象面=全局两级 + 项目记忆预留段；读取通道（直读优先——上游 auditReadableFiles 只读例外落地后生效，日志重建 fallback）；报告格式（九项指标 + 分级执行清单【机械】/【裁定】+ 项目迁移映射 + SKIP/OBSERVATION）；T1/T2/T3 判据；四判据；日审/周审两班倒边界。
- **面板可见性定稿：零代码改动。** 数据源实证：`AgentService.listAgents → AgentLibrary.loadAll → scanDisk()`（磁盘 agents/ 目录），`Seeds.all = List(Nebula)` 仅 Nebula 缺盘兜底（seed-defaults-converge 已落 main；任务文本「三 keeper」口径过时——现 main 即 Nebula 单 seed）。宿主 cp agents/dream/ 即见，重启后面板生效。
- 分发器认知：`staging/agents-project-dispatcher-system-addendum.md`（`<!-- dream-rules:start/end -->`）——「梦境日审」固定配方（单节点 agent=dream、无 worktree、out=Nebula、不接合并节点）；keeper 计数 3→4 更正。

## 3. 触发链（spec + 实测数据）

### 3.1 链路

```
Schedule(repeat=daily, triggerAt=04:45 本地)          ← Nebula 会话内注册（weekly-summary 同款外部驱动）
  → 04:45 ExternalEvent 注入 Nebula 会话（ScheduledTaskActor.fireDueTasks，
    会话消失自动 fallback 活跃会话，重启不丢——幂等重挂）
  → Nebula 执行活跃守卫：Bash 查 ~/.nebflow/logs/router/<今日>_summary.jsonl
    尾部 60min 窗口（agent=Nebula & channel=web & messages_count≤4 = 用户轮次指纹）
    ├─ 命中 → 当日放弃（回一句「用户活跃，梦境顺延明日」），次日定时任务自然再触发
    └─ 静默 → Task(project=nebflow, task=「梦境日审 …」)
  → ProjectActor.TriggerDispatcher → 分发器会话（dream-rules 配方）→ 单节点 agent=dream
  → dream 只读审计 → 报告沿 out 边 → Nebula 执行分级清单
```

**Schedule 投递能力实测**：`ScheduledTask` 是 session 附着（`sessionId` 必填），触发 = ExternalEvent 注入所属会话 + 持久化 user message（前端气泡可见）；`repeat` 支持 hourly/daily/weekly，下次触发从上次 triggerAt 递推（非壁钟，不漂移）；会话不存在自动 fallback 活跃会话。**只能注入所属会话 → 必须经 Nebula 转 Task(→project) 等效链**（即任务书预案的「Mail→项目任务等效链」的 Task 工具版，Task 与 Mail(→project) 同内核 ProjectActor.TriggerDispatcher）。

### 3.2 默认时刻实测（依据）

数据源 `~/.nebflow/logs/router/*_summary.jsonl`（09-02 ~ 09-05 四日，聚合 26,148 requests）：

- 小时分布（本地 UTC+8）：双峰 00-02 / 08-13 / 18-23；谷段 14-16（75/1/0）与 04-06。
- 任务书建议窗 04:00-05:00 内 15min 桶：04:00=185 / 04:15=187 / 04:30=295 / **04:45=152（最静）**。
- 用户轮次指纹（agent=Nebula + channel=web + messages_count≤4）四日累计：**04:00-05:00 = 0 次**；临近微突 05:30-05:45（6 次）。→ **定 04:45**：窗内用户轮次为零 + 总量最静桶 + 距 05:30 微突 45min 余量。
- 06:00-06:45 更静（37/36/42/0）但不取：超出建议窗，且趋近用户晨间活跃爬坡。

### 3.3 守卫阈值与顺延（二选一定稿：当日放弃）

- **阈值**：触发时刻前 60 分钟窗口内出现用户轮次指纹 → 活跃。依据：① 守卫护的是「审计全程落在静默段」——单轮审计 5-15min，60min 窗保证触发时用户已离场 ≥45min（深夜工作段呈连续 burst 形态）；② 指纹用「用户轮次」而非全请求——夜间批量节点/flow 流量（04:45 桶 152 次）多为自驱任务，不构成干扰，全请求口径会永久误跳。
- **顺延 = 当日放弃，次日自然再触发**（弃 04:30/05:00 双槽）。依据：① 守卫命中即深夜连续工作段，+30min 后仍活跃概率高，二次槽期望收益低、成本确定（多一轮 Nebula turn + 一次 spawn）；② 时间常数论证：T2 TTL=7 天、T3 M=14 天、FIFO=60 条，丢一天无实质堆积；③ 实现最简：单 Schedule 任务、零补偿调度状态、零跨日去重。
- **手动触发口（验收用最短路径）**：`Task(project=<p>, task=「梦境日审（手动触发，跳过活跃守卫）…」)` ——守卫在 Nebula 跳，Task 直入即天然跳过；隔离实例经 HTTP `/api/sessions/:id/turn` 驱动。

### 3.4 周接线退役口径

memory-mech 批的 Schedule 注册项（Sunday 21:30 memory-consolidation flow，若已注册）：**退役**（宿主删除 scheduled-tasks 中该注册；scanner 仍保留为 flow 资产，其报告格式已被 dream 继承）。理由：周审计（scanner，report-only）与梦境日审职责重叠——日节奏 ⊃ 周节奏，深度语义复核由 dream 报告的【裁定】类清单承接（Nebula 裁量即深度复核）。若作者希望保留周/日两班（周=全量深度、日=机械+增量），可改挂「周日 21:30 梦境周审」任务文本，dream 定义无需变更——**默认按退役执行，作者可反悔**。

## 4. dream-run-0（首轮只读审计）

口径：日志重建通道 A（宿主 auditReadableFiles 未部署，结构性直读不可得）。快照 = 最新 Nebula 非压缩请求 `3525b7a9`（2026-09-05T06:21:58Z）system_ref 重建。**字符口径**（重建限制；plan §1.1 同口径已验证：03:40 锚点 d43e1faa 重建值 84,450/64,500 与 plan 84,449/64,500 吻合）——权威 `wc -c` 待宿主回填。红线：run-0 零写入（仅 /tmp 工作文件），报告投递 Nebula 执行。全文见结果文本与本支 `staging/dream-run-0-report.md`。

## 5. 验收记录

- 隔离实例 e2e（`scripts/e2e-dream-agent.mjs`，stub LLM 内嵌 + fixture home + 隔离端口 8098/18998）：**11/11 ALL PASS（2026-09-05 15:14 实跑）**——①隔离网关起 ②Nebula 会话 ③MemoryEdit append 落盘 ④**snapshot-on-write：memory-backups/ 备份含写前真身（备份先于写的实例级证据）** ⑤Task 触发 ⑥分发器 spawn ⑦dream 节点 spawn（staging 定义加载）⑧dream 工具面无 Write/Edit/MemoryEdit ⑨**审计只读例外：dream 沙箱内直读 fixture User.md 成功（工具结果携带 seed）** ⑩flow-map 节点 completed 且 result 含报告标记（out 边投递）。自起进程 trap cleanup EXIT 清理实证（端口复查干净、fixture 删除）。脚本首跑教训：stub classify 全消息 includes 会把注入 Nebula 系统提示的 agent 目录（dream description 含「梦境审计员」）误判为 dream 节点——改 system 域身份头锚定（「你是 dream」）。
- 快照先行断言（选型含引擎写闸 → 必验）：单测三层（MemorySnapshotSpec 5 / MemoryEditToolSpec +3 / DreamModeSpec +2）+ e2e 实例级（上④）。**变异验红语义**：DreamModeSpec fail-closed 测试曾抓出真 bug——snapshot 分支的 `MergeResult.Merged` 因 Scala 3 缩进掉出 match，Left 路径 fall-through 谎报 Merged；修复后 Left=SnapshotBlocked 零写入。
- 环境性失败甄别（本节点沙箱，非本批回归）：SandboxSpec 25 + PopToolSpec 1（fixture 写 `~` 被 SANDBOX_DENIED，`~/.nb-sbx-dataroot-*`/`.nebflow-pop-test-tmp.png` Operation not permitted——project-memory 批同报告同结论）+ BashBackgroundHardTimeout/BashActivityBridge/ShellStuckDetector 各 1（spawn 子进程 CPU 计量在 Seatbelt profile 下不可得，health=false/no-activity 超时）。**diff 隔离证明**：BashTool/BgTaskRegistry/ShellStuckDetector 及全部涉试文件与 memory-mech 上游字节一致（`diff -rq` 为空）；本支净改动仅 DreamMode/MemoryEditTool(+spec) 与新件 MemorySnapshot(+spec)。全套件待宿主或沙箱可写 home 的节点复跑全绿。
- sbt 私有缓存配方：`SBT_OPTS="-Xmx3g -Dsbt.boot.directory=/tmp/nb-sbt/boot-dream -Dsbt.coursier.home=/tmp/nb-sbt/coursier-dream -Dsbt.global.base=/tmp/nb-sbt/global-dream"`，仅 compile+test。

## 6. 变更面（本支叠加部分；上游复制面见 §7）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/service/MemorySnapshot.scala` | **新文件**：snapshot-on-write 引擎件（fail-closed、每目标 20 份、折叠名、修剪） |
| `src/main/scala/nebflow/core/tools/MemoryEditTool.scala` | +`saveGuarded`（四动作 5 个 save 位点全部先过快照，锁内执行）+ description Snapshot 节 |
| `src/main/scala/nebflow/core/compact/DreamMode.scala` | updateMemory 写前快照 + `MergeResult.SnapshotBlocked`（hook fail-closed） |
| `src/test/scala/nebflow/service/MemorySnapshotSpec.scala` | **新文件**：5 测（真身/折叠名/首写放行/fail-closed/修剪） |
| `src/test/scala/nebflow/core/tools/MemoryEditToolSpec.scala` | +3 测（update 快照序 / replace+append 快照 / fail-closed 拒写） |
| `src/test/scala/nebflow/core/compact/DreamModeSpec.scala` | +2 测（hook 快照 / SnapshotBlocked 零写入） |
| `scripts/e2e-dream-agent.mjs` | **新文件**：隔离实例链路验收（stub LLM） |
| `staging/agents-dream/{agent.json,system.md}` | dream 定义 |
| `staging/agents-project-dispatcher-system-addendum.md` | dream-rules 锚定节（追加式） |
| `staging/dream-run-0-report.md` | run-0 报告存档（Nebula 执行版） |
| `staging/README.md` | 宿主落地索引 |
| `.nebflow/Spec/dream-agent.md` | 本 spec |

## 7. 上游复制面（memory-mech 终态，字节级继承）

ContextRefresher / DreamMode / NebulaMemoryHook / FileSandbox / SandboxPolicy / MemoryEditTool / SandboxSpec / MemoryEditToolSpec + 新件 MemoryHygieneSignal / MemoryBudget / MemoryHygieneSpec / DreamModeSpec + staging/（flow scanner-only 化 + skill + Nebula system + memory.md 规则节替换文本）。本支在其上叠加 §6 面落地顺序 memory-mech 在前即无冲突（FileSandbox/SandboxPolicy 本支零改动，git diff 对 memory-mech 为空）。

## 8. 边界与未尽事项

- 机械类自动执行（dream 直写）未落地——等作者对 2026-08-31 裁定①边界的签准（§1.1 案 c 下批）；本批 dream 结构性零写。
- 活跃守卫的「用户轮次指纹」阈值（60min/messages_count≤4）基于 4 日窗口实测，长期运行后由梦境报告指标头部第 9 项（间隔达标率）回头校准。
- run-0 字符口径的权威字节回填（plan §6.4-A 命令）由宿主执行后并入报告。
- 项目记忆审计对象面待 project-memory 批落地后启用（dream system.md 已预留，无需再改定义）。
