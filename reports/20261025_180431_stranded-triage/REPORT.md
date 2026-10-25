# stranded WIP 鉴定批 · 12 条快照对账 REPORT

- 批次：stranded-triage（纯鉴定：只读 + 分类 + 报告；不合并不删分支，处置候作者定夺）
- 日期：2026-10-25
- 对象：12 条「wip: stranded-batch guard / 停机保护」自动落盘分支（10-25 转洁保护 ×8、10-24 停机保护 ×3、10-24 d8f43803 ×1）
- 对账基线：main@9e26b01a6（2026-10-25 17:51）

## 0. 方法与证据面

1. **分支定位**：题给 12 个哈希 = 分支名内嵌的 delegate 会话号（非 tip 哈希）；`delegate/delegate-nebflow-<id>` 全部存在。
2. **任务反查**：`/Users/kaiyu/.stand/delegates/delegate-nebflow-<id>.json`（delegate 台账，含 title/taskId/reportStatus）+ `/Users/kaiyu/.stand/tasks-v2.json`（任务台账）。
3. **覆盖判定**（三面交叉）：
   - `git cherry main <branch>` patch-id 等价：**12 条全部 `+`（无一等价补丁在 main）**；
   - 关键新件在 main 存在性：`BgTaskLedger` / `BgNotifyPayload` / `DelegateDeliveryReceipts` / `NfOfficeRoutes` / `SubagentGeneralizationSpec` / `attach-split.spec.mjs` 等 **全部 ABSENT**；
   - main 提交标题/合并清单（10-24 起 27 个 merge）逐题匹配：无一同名任务落地。
4. **任务台账状态**：12 个 taskId（#803/#806–#810/#814/#815/#817/#822–#824）在 tasks-v2.json **全部仍为 `open`**——无一 formally 收口。
5. **续作链发现**：9/12 的任务在搁浅前有 continuation 会话（31b21dd3、79a1f1d8、289560f8、c40796b6、08efa79a、cf58b93e、57cb9529、447d7317、96167011 等），它们在**共享 worktree 留下未提交改动**后被停机/转洁保护一并扫进 wip 提交——即 **wip 快照 = 各批最新进度**，不是过期残片。唯一例外：#817 重派 00a5f0e0 的产出（CI 迁移腿）已合入 main（tip d4a6e0c0b）。

**对账简报勘误**：992a3c8c 实为 ahead=2（1 条真货 hotfix + 1 条 wip），非 ahead=1；d8f43803 ahead=7 属实（6 真货 + 1 wip）。

## 1. 十二行对账表

| # | 会话(分支) | 任务台账 | 落盘时间 | 真货内容 | 分类 | 核心依据 |
|---|---|---|---|---|---|---|
| 1 | c6ce9f87 | #803 外部执行器会话可视化 | 10-25 00:27 | wip：ExternalExecutor +157 / ExternalSessionRunner +131 / 2 spec 376+ / REPORT | **续做** | 文件自 10-24 起 main 零触碰；续作 79a1f1d8/3619d5cc 未提交改动在快照内 |
| 2 | 1cb7b55c | #806 subagent 通用化 | 10-25 00:27 | wip：8 主文件 + 3 spec + REPORT（348+/59−），SubagentGeneralizationSpec 等 | **续做** | main 无通用化痕迹（fc9fb0b50 是另一 wait-guards 批，仅同文件）；续作 31b21dd3 报 merge 但提交从未落 branch |
| 3 | b73c3e73 | #807 delegate 完成结果投递丢失 | 10-25 00:27 | wip：新件 DelegateDeliveryReceipts 281 行（写/确认/重投三段）+ AgentIdle/BackoffSupervisor + 2 spec 613+ | **续做** | main 缺该件；fef7afcb 批（10-25 已并）修的是「汇报文本选段」另一条腿，回执/重投机制不存在 |
| 4 | f3b32bbf | #808 Pop 图片 HTTP 403 | 10-25 00:27 | wip：nfFileCache +56 / NfFileRoutes +26 / NfFileDenyLogSpec 140 / 403-heal e2e 253 | **续做** | NfFileRoutes main 零触碰；403 自愈面 absent |
| 5 | 992a3c8c | #809 社交入站图片无声丢失 | 10-25 00:27 | **真货 hotfix da594518c**（FeishuChannel 弃 SDK 腿自握下载 + FeishuInboundImageSpec 154）+ wip | **续做（P0）** | main:FeishuChannel:488 仍走坏 SDK 腿（oapi image().get，ByteArrayOutputStream→Gson/JDK23 必炸）；作者被阻中；delegate 已报 merge 未落 |
| 6 | 81af85aa | #810 网页端带附件消息整体丢失 | 10-25 00:27 | wip：input.js +136 / WebSocketRoutes +29 / OversizeFrameLoudRejectSpec / attach-split.spec 223 | **续做（P0）** | 高优先作者报障；main 仅 cache-busting/mobile-P1 触过同文件，修复 absent |
| 7 | ae1c2d58 | #814 canvas 标签页跨设备关闭不收敛 | 10-25 00:27 | wip：canvas.js 296± / CanvasTabStore +53 / ConfigRoutes +49 / LWW spec 203+62 | **续做** | 无续作会话；LWW 收敛面 main 缺失 |
| 8 | f30e15f7 | #815 Canvas PPTX/Office 在线查看 | 10-25 00:27 | wip：新件 NfOfficeRoutes 446 / pptx.js +180 / spec 264 + e2e 305 + live-accept + 4 证据图 | **续做** | NfOfficeRoutes ABSENT；main pptx.js 仍为 23KB 基线版 |
| 9 | d8f43803 | #817 CI 存量红分诊+修复 | 10-24 22:05 | **真货 ×6**（scalafmtAll 落盘 / SingleInstanceGuard rebrand marker 真回归 / JVM 31 红分诊 14 套件 / 门禁面三修 / web 17 spec 修复 / 3 spec 重钉）+ wip（round-2 scalafmt/import 整理 ~75 文件） | **续做（带条件）** | `git cherry` 7/7 全 `+`；重派 00a5f0e0 只并了 CI 迁移腿；分诊腿只在本地。落后 main 70 提交，需对现 main 重跑红面再落 |
| 10 | bd7a0f1c | #822 Schedule 双面化 | 10-24 22:05 | wip：ScheduleTool 517± / ScheduledTask 族 +416 / TodoScheduleSpec 473 + ScheduleInitiativeSpec 335 | **续做** | 续作 447d7317 已报「30 断言全绿（中文 NL 根因=ChineseDtRegex 捕获组错位）」但未提交——成果只在快照内；ScheduleTool main 零触碰 |
| 11 | f98fc1e2 | #823 后台任务完成通知详情 | 10-24 22:05 | wip：新件 BgNotifyPayload 148 / BashTool 重排 / e2e 脚本 204 / spec 137 | **续做** | main 的 device 批（0e10fb49f）明文把「②完成通知负载」让位给 #823；01e8a5286 是相邻 output-viewer 批非覆盖；续作 96167011 修的 4 红也在快照内 |
| 12 | a8a4de17 | #824 后台任务状态对 agent 不可见 | 10-24 22:05 | wip：新件 BgTaskLedger 344 / AgentActor/FinishTurn/Idle / 4 spec 1114+ | **续做** | 同上，device 批明文把「③持久账本」让位给 #824；无续作会话，快照是唯一实现 |

## 2. 分类统计

| 分类 | 条数 | 会话 |
|---|---|---|
| 续做 | **12** | 全部（c6ce9f87 / 1cb7b55c / b73c3e73 / f3b32bbf / 992a3c8c / 81af85aa / ae1c2d58 / f30e15f7 / d8f43803 / bd7a0f1c / f98fc1e2 / a8a4de17） |
| 已被覆盖 | 0 | —（三面证据：patch-id 全 `+`、关键新件全 ABSENT、12 任务全 open） |
| 废弃 | 0 | —（12 条均有真实产码 + spec/证据，无空转） |

## 3. 逐条鉴定（①任务对应 ②内容摘要 ③分类依据 ④处置建议）

### ① c6ce9f87 → #803【功能批 · 外部执行器会话可视化】（作者令 10-24 09:00）
- **内容**：ExternalExecutor.scala +157、ExternalSessionRunner.scala +131（会话帧→子智能体面板实时解析面）、ExternalExecutorSpec +126、新 ExternalSessionRunnerStreamSpec 250、reports REPORT.md。含续作会话 79a1f1d8/3619d5cc 的未提交收尾。
- **分类：续做**。main 上 ExternalExecutor/ExternalSessionRunner 自 10-24 零提交；可视化面（面板实时解析）在 main 无任何等价实现；仅有的先例是 10-19/10-22 设计批与行显示名修复批（已并），非本功能。
- **处置**：从快照分支续跑——先把 wip 提交里的 spec 跑绿，再补面板端到端验收，走合并队列。落点建议新派批，brief 注明「接手 c6ce9f87 快照」。

### ② 1cb7b55c → #806【功能批 · subagent 通用化（去只读、节点级能力）+ agentflow 用法审计】
- **内容**：AgentCore/BuiltinAgents/AgentFlowTool/BlockingSubagent/SubagentTool/registry/Defaults 八主文件 + 新 SubagentGeneralizationSpec 86 行 + DelegateToolSpec/SleepPollStartupRuleSpec 重钉 + REPORT。续作 31b21dd3 报告：spec 验证在途（5 spec sbt testOnly），「绿则提交 8 主文件 + 3 spec + REPORT 到本分支」——**提交从未发生**，work 只在 wip 快照里。
- **分类：续做**。main 无 SubagentGeneralizationSpec、无通用化语义标记；10-25 fc9fb0b50（subagent-wait 三缺口封堵，已并 9e26b01a6）是**另一问题域**（等待死链），但改了同一文件 BlockingSubagent → 落地前必须 rebase。
- **处置**：续做时先 rebase 到现 main、重跑 5 spec；agentflow 用法审计结论在快照 REPORT.md 里，一并回收。

### ③ b73c3e73 → #807【诊断修复批 · delegate 完成结果投递丢失（父会话永不收到）】
- **内容**：新件 DelegateDeliveryReceipts.scala 281 行——终态交付回执三段机制（BackoffSupervisor 写回执 / AgentIdle 注入即 ack / TaskStuckWatcher 扫尾超龄重投 deliverToRootOrInbox）+ AgentFinishTurn/AgentIdle/BackoffSupervisor/TaskStuckWatcher 接线 + DelegateWakeContinuationSpec 92 + DelegateTerminalDeliverySpec 123。设计文档 docs/design/delegate-supervision-revival-design.zh-CN.md 同族。
- **分类：续做**。main 缺该件；已并的 fef7afcb 批（delegate-finish-report-fix，8ae0e5317）修的是「双段汇报选错文本」的**汇报内容**腿，本批修的是「终态事件投递无确认无重投」的**运行时**腿——互补不重叠。续作 289560f8/df026ea6 的收尾在快照内。
- **处置**：续做。注意与已并批的接缝（DelegateWorkReport/DelegateTerminal 在两批都动过），rebase 后重跑 2 spec。

### ④ f3b32bbf → #808【修复批 · Pop 图片 Failed to render: HTTP 403】
- **内容**：nfFileCache.js +56（票过期自愈）、NfFileRoutes +26、新 NfFileDenyLogSpec 140、nf-ticket-403-heal.spec 253、REPORT。续作 c40796b6 收尾在快照内。
- **分类：续做**。main 的 NfFileRoutes/NfFileCache 自 10-24 零提交；403 票自愈 absent。关联背景：#808 与 10-24 已并的 imgdoor 二次加载批（b8fc616f5，经 00a5f0e0 并入）同域不同层——后者是抓取账本，本批是票失效自愈。
- **处置**：续做；rebase 时留意 nfFileCache.js 与 b8fc616f5 的冲突面。

### ⑤ 992a3c8c → #809【修复批 · 社交入站图片无声丢失】（作者报障 10-24 11:06「发了22张图你没收…」）
- **内容**：**真货 hotfix da594518c**：FeishuChannel 弃 oapi-sdk 下载腿、自握 wire（GetImageResp extends BaseResponse<ByteArrayOutputStream> 被 Gson 序列化 + JDK 23 强封装 → JsonIOException 必炸）+ 新 FeishuInboundImageSpec 154。delegate 台账 reportStatus=**merge**，reportDetail 明言「HOTFIX 需立刻落地，作者正被逐张发图 deterministically 炸」。
- **分类：续做（P0，全批最高优先）**。实证 bug 仍在 main：`main:src/main/scala/nebflow/social/FeishuChannel.scala:488` 仍为 `client.im().v1().image().get(req)` SDK 腿；该文件 10-20 以来零提交。
- **处置**：建议作者批准后**第一个落**——cherry-pick da594518c 到新分支、跑 FeishuInboundImageSpec、走合并队列。

### ⑥ 81af85aa → #810【修复批 · 高优先 · 网页端带附件消息整体丢失】（作者现场报障）
- **内容**：input.js +136（附件分片发送）、WebSocketRoutes +29（分片接收/重组）、main.js +32、en/zh-CN 文案 +3、新 OversizeFrameLoudRejectSpec 65、新 attach-split.spec.mjs 223。续作 cf58b93e 收尾在快照内。
- **分类：续做（P0）**。高优先作者报障；main 的 WebSocketRoutes 仅 cache-busting（4df51ddcd）、input.js 仅 mobile-P1（754745869）触过——分片修复 absent。
- **处置**：续做；rebase 冲突面 = input.js/WebSocketRoutes，两个已并批改动区域不同段，预计小冲突。

### ⑦ ae1c2d58 → #814【修复批 · canvas 标签页跨设备关闭不收敛（关了刷新又复活）】
- **内容**：canvas.js 296±、CanvasTabStore +53（LWW 戳）、ConfigRoutes +49、CanvasTabStoreSpec 62、canvas-tabs-lww.spec.mjs 203。
- **分类：续做**。无续作会话（12 条中唯一无 continuation 的 10-25 修复批）；CanvasTabStore main 零触碰，LWW 收敛 absent。
- **处置**：续做；批内已带完整 spec 对（Scala+前端 e2e），接手成本最低的一档。

### ⑧ f30e15f7 → #815【功能批 · Canvas 支持 PPTX/Office 文件在线查看】（作者令 10-24 14:35）
- **内容**：新件 NfOfficeRoutes.scala 446（转换路由）、viewers/pptx.js +180（降级卡/首页转换）、NfOfficeRoutesSpec 264、canvas-office-convert.spec.mjs 305、live-accept.mjs 95、4 张证据 png、REPORT。
- **分类：续做**。NfOfficeRoutes ABSENT；main pptx.js 23141 字节 vs 快照 31710 字节（基线版无转换面）。
- **处置**：续做；注意 nearpath.js 微改（+4−4）是否为其他在活会话在制件，落前核对。

### ⑨ d8f43803 → #817【治理批 · CI 存量红分诊+修复，通往全绿】（10-24 15:13 创，**特殊注意，7 提交**）
- **内容**：6 条真货——b411e9d43 scalafmtAll 落盘；c35a1338c SingleInstanceGuard rebrand 遗留 marker 识别（**CI 存量红真回归**）；3f40137eb JVM 31 红分诊（环境敏感/字面钉过期 14 套件）；d059b5ec7 门禁面三修；c6700e232 前端 17 spec 修复；c4e93330f 3 spec 重钉。+ wip 0ac14efb9 = round-2 838d973b 报告的「8 处 misaligned `end for` + 71 文件 import 整理（scalafix）」~75 文件未提交收尾。
- **分类：续做（带条件）**。`git cherry`：7/7 全 `+`（无一在 main）；重派 00a5f0e0（10-24 15:40「前一批崩溃，你接手」）只交付了 **CI 迁移腿**（ci.yml/JDK23/堆参数，已并），**分诊修复腿只在本地**。
- **条件**：分支落后 main 70 提交，其间已并 windows-test6 分诊批（44d5243fa）、queue 系 spec 换代等——**部分红可能已被别的批治好，部分修复可能与新基线冲突**。
- **处置**：不直接 merge。先对现 main 重跑 CI 红面做 delta 分诊：仍红的套件按快照修法落，已被治的丢弃；round-2 的 scalafmt 整理需对现 main 重生成（70 提交的漂移会使其大面积过时）。

### ⑩ bd7a0f1c → #822【功能批 · Schedule 定时工具双面化：定时任务 + 待办】
- **内容**：ScheduleTool 517±、ScheduledTaskActor/Model/Store +416、reminders +61、新 TodoScheduleSpec 473 + ScheduleInitiativeSpec 335。续作链 57cb9529→447d7317 已把编译红修净并根因修复中文 NL 解析（ChineseDtRegex 捕获组(4)/绑定(5) 错位 → 全中文形态 Left），**30 断言全绿**——但按纪律未提交，成果全在 wip 快照内。
- **分类：续做**。ScheduleTool/ScheduledTask 族 main 零触碰；「待办抗压缩」面 main 无等价物。
- **处置**：续做——快照据信已近完工态（30 断言绿），接手批只需全量 spec 复跑 + rebase + 交合并队列，性价比最高的功能批。

### ⑪ f98fc1e2 → #823【修复批 · 后台任务完成通知详情缺失/截断】（作者报障 10-24 17:58）
- **内容**：新件 BgNotifyPayload.scala 148（完成通知结构化负载）、BashTool 244±、BgTaskRegistry/RemoteExecutor/WsSystemHandlers 接线、e2e-bgtask-notify-detail.cjs 204、BgNotifyPayloadSpec 137、面板证据 png、REPORT。续作 96167011 修净 4 红（Scala3 插值 `$tagsSuffix(tags)` 缺花括号），未提交——在快照内。
- **分类：续做**。**覆盖反证**：main 已并的 device-bg-ledger 批（0e10fb49f）提交文写明「②完成通知负载/③持久账本两面在 #823（BgNotifyPayload）/#824（BgTaskLedger）批内……本批刻意零触碰」——main 主动给 #823/#824 留了缝。相邻批 01e8a5286（后台任务输出查看）是 output-viewer 域，非通知负载。
- **处置**：续做；与 #824 同族（改动面 BashTool/RemoteExecutor 相邻），建议两批接续落减少冲突。

### ⑫ a8a4de17 → #824【修复批 · 后台任务状态对 agent 不可见 + 完成事件可丢失（idle 无 barrier 死等）】
- **内容**：新件 BgTaskLedger.scala 344（持久账本）、AgentActor +61/AgentFinishTurn +58/AgentIdle +89/BackoffSupervisor +28/BashTool +14/RemoteExecutor 55±、BgLedgerRedeliverySpec 138 + BgWaitCapLedgerSpec 185 + BgTaskLedgerSpec 134 + BgWaitingCountSpec 52。
- **分类：续做**。同 #823 的覆盖反证（0e10fb49f 明文让位「③持久账本」给 #824）；无续作会话，快照是唯一实现。
- **处置**：续做；与 #823 打包接续。注意 AgentIdle/BackoffSupervisor 与 b73c3e73（#807）改动面重叠——三批（#824/#823/#807）落序需排，避免同文件三轮 rebase。

## 4. 处置建议汇总（候作者过目，本批不执行）

1. **P0 立落**：992a3c8c（#809 feishu 入站图片，bug 在 main 现行，hotfix 成品带 spec）＞ 81af85aa（#810 带附件丢失）＞ b73c3e73（#807 投递回执）。
2. **P1 成品回收**：bd7a0f1c（#822，30 断言绿近完工）、f98fc1e2 + a8a4de17（#823/#824，main 让位等落，建议成对落）。
3. **P2 常规续做**：ae1c2d58（#814）、f3b32bbf（#808）、c6ce9f87（#803）、1cb7b55c（#806，需与 fc9fb0b50 rebase）、f30e15f7（#815）。
4. **P2 带条件**：d8f43803（#817）——不直接 merge，先对现 main 做 delta 分诊 + round-2 scalafmt 重生成。
5. **台账**：12 个任务全 open 与实际进度脱节，建议按本表更新台账批注（续作接手时 brief 引用快照分支名即可，工作现场都在分支上）。

## 5. 附注

- 10-24 三条 wip 提交信息含乱码（「（¼2026-10-24 停机迁移前）」——「，」被写成 ¼），系落盘脚本编码问题，不影响内容。
- 判「未被覆盖」的核心可复现命令：`git cherry main delegate/delegate-nebflow-<id>`（12 条全 `+`）；`git cat-file -e main:<关键新件>`（全 ABSENT）。
- 本批零改码：未合并任何快照分支、未删分支、未改 tasks-v2.json。
