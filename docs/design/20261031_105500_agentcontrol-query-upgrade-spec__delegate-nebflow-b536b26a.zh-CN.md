---
type: design-spec
status: draft
title: AgentControl 查询能力升级 — 实施规格书（JSON schema 草案 + 数据源核验表）
created: 2026-10-31
author: stand (delegate-nebflow-b536b26a, 主用户 Nebula/Spider 委托)
tags: [design, agentcontrol, query, spec, schema]
---

# AgentControl 查询能力升级 · 实施规格书（schema 草案）

> 配套设计稿（why）：[[20261031_105500_agentcontrol-query-upgrade__delegate-nebflow-b536b26a.zh-CN]]。
> 本文是 how：JSON 参数级 schema 草案 + 数据源核验表 + 实施任务清单。**只设计不实施**，
> 呈作者拍板后另批实施。

## §1 总 schema（AgentControl inputSchema 扩展草案）

原则：`required` 仍只有 `action`；其余全部可选；旧 property（sessionId/reason/scope/jobId/confirm）
逐字不动。`action` 枚举扩为 9 值：

```json
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["list", "status", "cancel", "restart",
               "search", "mergeboard", "bgtasks", "mailq", "pending-me"],
      "description": "list/status/cancel/restart = 现有四操作（语义不变）；search = 持久注册表检索（含历史实例）；mergeboard = 合并三段板；bgtasks = 后台任务清单；mailq = mail/投递队列；pending-me = 等我的动作聚合。"
    },
    "sessionId":  { "type": "string", "description": "status/cancel/restart 必填（现状不变）；bgtasks/mailq 可选（缺省 = 可见范围全量）。" },
    "reason":     { "type": "string", "description": "现状不变。" },
    "scope":      { "type": "string", "enum": ["session", "bgTask"], "description": "现状不变。" },
    "jobId":      { "type": "string", "description": "现状不变。" },
    "confirm":    { "type": "boolean", "description": "现状不变。" },

    "filter": {
      "type": "object",
      "description": "仅 list：全部可选，全缺省 = 现状输出逐字节不变。任一字段非缺省 = 过滤渲染路径（同表头，行数变少）。",
      "properties": {
        "kind":       { "type": "string", "enum": ["Delegate", "SubTask", "Ephemeral", "Team", "Flow", "Root", "Plan"], "description": "AgentKind 过滤。" },
        "status":     { "type": "string", "enum": ["Idle", "Processing", "Waiting", "Failed"], "description": "AgentStatus 过滤。" },
        "stuckOnly":  { "type": "boolean", "description": "true = 只留 stuckAssessment 命中的 Processing 行。" },
        "idleMinMin": { "type": "integer", "description": "只留 idle ≥ N 分钟的行。" },
        "ageMinMin":  { "type": "integer", "description": "只留 up ≥ N 分钟的行。" },
        "device":     { "type": "string", "description": "roster deviceId 匹配；外部/远程行取 ActiveTask.deviceName、delegatedBy；本机行经 AgentRecord.deviceId 埋点。存量缺字段行不匹配、不报错。" },
        "project":    { "type": "string", "description": "DelegateRegistry.Record.project 匹配（内部行 registry join，外部行直取记录）。" },
        "text":       { "type": "string", "description": "任务描述子串，大小写不敏感。" }
      }
    },
    "stats":   { "type": "boolean", "description": "仅 list：true = 表尾追加一行汇总（live/flying/idle/stuck/orphan/external/pendingMerge/readOnly/zombie）。" },
    "verbose": { "type": "boolean", "description": "仅 list/status：true = 放宽截断（task 48→96、parent 全显、新节全量）；缺省 = 现状窄面。" },

    "q":              { "type": "string", "description": "仅 search：title/任务文本子串。" },
    "branch":         { "type": "string", "description": "仅 search：branch/baseBranch 精确或前缀。" },
    "reportStatus":   { "type": "string", "enum": ["finish", "blocked", "merge"], "description": "仅 search/mergeboard：delegate_report 状态槽过滤。" },
    "since":          { "type": "integer", "description": "仅 search：createdAt ≥ 毫秒。" },
    "until":          { "type": "integer", "description": "仅 search：createdAt ≤ 毫秒。" },
    "limit":          { "type": "integer", "default": 20, "description": "search/mergeboard/bgtasks/mailq 返回行上限。" },
    "includeArchived": { "type": "boolean", "default": true, "description": "仅 search：false = 只查在册（archivedAt 空）。" },
    "days":     { "type": "integer", "default": 7, "description": "仅 mergeboard：merged 段窗口天数。" },
    "verdict":  { "type": "string", "enum": ["pass", "fail"], "description": "仅 mergeboard：rejected 段判词过滤。" }
  },
  "required": ["action"]
}
```

## §2 各 action 出参草案（文本表格面，字段级）

### §2.1 list（扩展，痛点 1/3/4）

- 过滤路径表头与现状 14 列完全一致；`stats=true` 表尾追加：
  `stats: live=.. flying=.. idle=.. stuck=.. orphan=.. external=.. pendingMerge=.. readOnly=.. zombie=..`
- `verbose=true`：task 列 `take(96)`，parent 列不截断；其余列不变。

### §2.2 status（扩展，痛点 6/8/9/10）

既有行逐字保留；文件尾按需追加四节（无数据整节省略）：

```
--- activity ---
lastEvent: <summary≤128字> <fmtDuration> ago
--- barrier diagnosis ---
outstanding: <N> — <人话：在飞批次成员清单或「无」>
held:        <N> — <人话>
unread:      <N> — <人话>
pendingDelivery: <N> — <人话>
--- artifacts ---
report:   <abs path>
evidence: <abs path> (sha256:<前12位>)   ×N
--- mail ---
mailbox: <MailQueueStore FIFO depth>
receipts pending ack: <N> (<delegateId>, eventType=failed, redeliveries <n>, lastDelivered <dur> ago)
held retries: <attempts/max, exhausted?>
terminal verdicts: <Receipt.eventType completed/failed/cancelled 行> ×N
（判词源 = 回执 eventType + merge-ledger closeReason；DeliveredReceiptLedger 是纯去重身份集，不作判词源）
```

### §2.3 search（新，痛点 2/11）

```
delegates matching <条件> (N, incl archived):

| id | branch | base | tip | mergeCommit | reportStatus | created | archived | title |
每行 ≤ limit；行尾无指引列——输出尾统一一行：
  live → AgentControl(status)；终态 → workReport + artifacts（同 id 行内字段）
```

### §2.4 mergeboard（新，痛点 5）

```
Merge board — waiting <N> / merged(<days>d) <N> / rejected <N>
waiting:   | id | branch | base | tip | mergeCommit | reportStatus | queuePos | verdict | age |
merged:    | id | branch | base | grantedBaseTip | mergeCommit | reportStatus | - | verdict | closedAge |
rejected:  | id | branch | base | tip | - | reportStatus | - | closeReason(≤32) | age |
尾行：waiting → 引擎合并提醒队列自动唤醒（MergeQueue pump）；rejected → cancel+重派 / Mail 续聊
```

### §2.5 bgtasks（新，痛点 7）

```
bg tasks of <sessionId|scope> (N active, registry is in-memory — history via BgTaskOutputStore):
| jobId | session | kind | device | origin | status | age | health | output |
health  = suspicionLevel/escalated/lastActivity 三值压缩（如 "ok/- /4m"）；health!=ok 的行追加 ⚠
output  = remote: outputPath；local: BgTaskOutputStore 留存区指针（无行内 logPath 是现状承载面约束）
```

### §2.6 mailq（新，痛点 10）

数据源实证（recon）：`delivery="queue"` 模式**已退役**（MailTool 硬报错）；现有可查询面 =
DelegateDeliveryReceipts 全字段（eventType completed/failed/cancelled = 终态判词、ackedAt、
redeliveries、deliveredAt）+ HeldReceiptStore 重试记账（attempts/exhausted，指数退避）+
MailQueueStore 每会话 FIFO（`<dataRoot>/sessions/<sid>/mail-queue.json`，无 retry/verdict 字段）
+ DeliveredReceiptLedger（纯注入去重身份集，**无判词**，不作 verdict 源）。

```
mail queue — receipts / held / inbox 三段，每段紧凑表 + 一行建议动作
receipts: | delegateId | parent | eventType(completed/failed/cancelled) | acked | redeliveries | lastDelivered(age) |
held:     | id | source | attempts/max | exhausted | lastAttempt(age) |
inbox:    | sid | from | queued(size) | headAge |     ← MailQueueStore FIFO 快照
建议动作：unacked 持续 & redeliveries>0 → 查投递链/sweep WARN 日志；exhausted → 下条输入自动补注入；
         failed/cancelled 判词 → 附 cancel+重派 或 Mail 续聊指引
```

§2.2 status 的 `--- mail ---` 节同源收窄（单会话切片）：pending ack 明细（含 redeliveries）、
held 重试行、inbox 深度。

### §2.7 pending-me（新，痛点 12）

```
waiting on you (<caller>) — mail <N> / merge <N> / verdict <N> / receipts <N>
四段，每行带「下一步」列：Mail(address=…) / 合并(队列#pos) / cancel+重派 / 无需动作(自动补注入)
```

## §3 数据源核验表（recon 实证，file:line）

| 面 | 数据源 | 持久性 | 关键读面 | 出处 |
|---|---|---|---|---|
| 活体注册表 | `agentRegistry`（AgentRecord 内存 map：时间戳族 + currentToolName/StartedAt/DeadlineMs + loop/escalation 镜像） | 内存（重启清） | `get` / scopeFilterFor 既有 | AgentState.scala:25-227; AgentControlTool.scala:514-545 |
| 活性内容 | **无承载**——touchRegistryActivity 只写 status/lastActivityMs/turnStartedAt/currentTool*，无内容参数（全库 grep lastEvent/lastToolResult 零命中记录面） | — | 新增埋点 `lastEventSummary`（写点 touchRegistryActivity，AgentRegistryEmit.scala:101-141） | AgentRegistryEmit.scala:101-141 |
| device↔会话 | 持久面仅 `DelegateRegistry.Record.delegatedBy`（执行设备侧）；AgentRecord 无 deviceId；SessionContext.sessionDevice 为内存路由设备 | 持久（delegatedBy）/内存（sessionDevice） | list device 过滤；新增 AgentRecord.deviceId 埋点 | DelegateRegistry.scala:121-127; AgentState.scala:398 |
| delegate 地址簿 | `<dataRoot>/delegates/<id>.json`（每实例一文件，零删除） | **持久** | `DelegateRegistry.list()/find` | DelegateRegistry.scala:159-191 |
| 合并队列 | `<dataRoot>/merge-ledger.json` + `merge-channel/<id>.json` 镜像 | **持久**（state: waiting/turn-granted/merged/owner-gone/failed；closeReason；grantedBaseTip） | `queueViewOf`（position/total/ahead/holder）；既有 REST `GET /api/merge-queue`、ChainsReadFace `mergeQueuePos` | MergeQueue.scala:116-154, 454-497; PresenceRoutes.scala:706-775 |
| 合并 commit SHA | **无存储**——settle 时机械验证（MergeAcceptance.verify 双父提交）后即丢 | — | 新增埋点 `mergeCommitSha`（写点：closeMerged，MergeQueue.scala:936-959） | MergeAcceptance.scala:50-67 |
| bg 任务 | `BgTaskRegistry`（ActiveTask 行：jobId/sessionId/kind/deviceName/outputPath(remote only)/status/健康三字段/ownerMode） | **内存**（Ref，重启清；历史输出走 BgTaskOutputStore） | `tasksOf`/`waitingFor`/`allActiveTasks` | BgTaskRegistry.scala:31-101, 558-664 |
| 投递回执 | `DelegateDeliveryReceipts`（Receipt：delegateId/parentSessionId/eventType=completed/failed/cancelled/payload/deliveredAt/ackedAt/redeliveries，每 delegate 一文件） | 持久 | `list()` 按 parent 计数（现状）；mailq 读全字段 | DelegateDeliveryReceipts.scala:96-110; AgentControlTool.scala:574-579 |
| held 重试记账 | `HeldReceiptStore.HeldReceipt`（attempts/exhausted，指数退避，cap=Defaults.HeldDeliveryMaxAttempts） | 持久 | mailq held 段 | HeldReceiptStore.scala:44-61; HeldDeliveryWatchdog.scala:103-132 |
| mail 收件 FIFO | `MailQueueStore`（`<dataRoot>/sessions/<sid>/mail-queue.json`，MailQueueItem 无 retry/verdict 字段；queue 投递模式已退役，工具面全 immediate） | 持久 | mailq inbox 段；既有 REST `GET/DELETE /teams/mail-queue/:sid` | MailQueueStore.scala:41-116; MailTool.scala:21-36 |
| 注入去重 | `DeliveredReceiptLedger`（身份集 cap 256，**无判词**——不作 verdict 源） | 持久 | 不直接展示（mailq 仅备注） | DeliveredReceiptLedger.scala:57-71 |
| 外部 delegate 进度/mailbox | `ExternalOutputStore`（status/mailboxDepth/errorHint） | 持久 | AgentControl 外部行/status 现状已用 | AgentControlTool.scala:651-671 |
| 任务行 | `SubAgentTaskStore`（description/prompt/lastError/retryCount/status） | 持久（per-parent 文件） | findRunningTasks/findByTaskId 现状已用 | AgentControlTool.scala:527, 743 |
| 语义检索 | `TaskSearchIndex`（TaskLedger + DelegateRegistry title/reportDetail 嵌入；**不含 branch/sessionId**） | 持久（vectors.json） | `TaskSearchTool(query,k)` 现状不变 | TaskSearchIndex.scala:93-142 |

## §4 新增埋点写读契约

```scala
// DelegateRegistry.Record 追加三字段（Decoder 手工容错，存量零迁移）
mergeCommitSha:  Option[String] = None,   // 写点：MergeQueue.closeMerged（settle 成功，first-write-wins）
tipSha:          Option[String] = None,   // 写点：spawn 基线快照 ∪ closeMerged 回写（first-write-wins 留底）
reportArtifacts: Option[List[String]] = None, // 写点：DelegateReportTool 落点（非空追加，幂等去重）

// AgentRecord 追加两字段（内存 case class，无持久兼容问题）
lastEventSummary: Option[(String, Long)] = None,  // 写点：touchRegistryActivity 扩签名（AgentRegistryEmit.scala:101-141），摘要 ≤128 字
deviceId:         Option[String] = None,          // 写点：会话 spawn（种子 = SessionContext.sessionDevice 内存路由设备）
```

## §5 实施任务清单

- [ ] DelegateRegistry.Record 三字段 + 手工 Decoder 扩面 + 写点（closeMerged / DelegateReportTool / spawn 基线）
- [ ] AgentRecord 两字段 + actor 事件桥写点
- [ ] AgentControl inputSchema 扩展（§1 草案落码）+ description 增补（旧段落逐字保留）
- [ ] list：filter/stats/verbose 三路径（if-flat 隔离，无参 = 旧渲染路径）
- [ ] status：activity/barrier diagnosis/artifacts/mail 四节追加（有数据才追加）
- [ ] search / mergeboard / bgtasks / mailq / pending-me 五个新 action（复用 scopeFilterFor 语义）
- [ ] AgentControlE2ESpec：无参字节级回归 + 五新 action 烟测 + 埋点写读回路用例
- [ ] 文档：tool description 新段落 + 本设计稿状态翻 `approved`/`implemented`

## §6 验收口径

1. 现有 AgentControlE2ESpec 全绿且 list/status 无参输出与升级前**逐字节一致**（快照比对）。
2. 12 痛点逐条有可演示的查询命令（附期望输出首行）。
3. 新埋点写读回路：spawn → report → merge settle 后 search/mergeboard/status 三读面字段齐。
4. Manager 子树 scope：新 action 对子树外目标返回 Permission denied（同现状 status 语义）。
