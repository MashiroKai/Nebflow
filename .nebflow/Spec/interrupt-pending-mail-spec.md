# INTERRUPT 真打断 + mailbox pending 显示展开 方案

- **版本**：v1.2（2026-08-19）
- **Issue**：#326
- **状态**：已批准冻结 · 待派发实施
- **关联**：`msg-window-interaction-spec.md`（独立的前端消息窗口交互方案，本方案聚焦后端 INTERRUPT 语义 + mailbox 面板展示，无重叠）

> **v1.1 勘误**（基于源码二次核对）：
> 1. §2.2 CSS cursor 描述修正：`.flow-mail-row.pending` 实际是 `cursor: default`（`flowCss.js:208` 覆盖通用规则），非 `cursor: pointer`。
> 2. §3.1/§5.1 「无删除、无丢失」修正：`resetForInterrupt`（`protocol.scala:1019-1025`）经 `ExecutionContext.idle` 把 `pendingImmediateInputs` 重置为 `Nil`，后续 `.copy()` 不恢复——打断时积压的 immediate 邮件**会丢失**。这与现有「用户按 Stop」行为一致（同一代码路径），非新引入风险。queue 邮件（磁盘持久化）和 sub-agent 结果（`pendingEvents`/`outstandingSubagentResults` 显式保留）不受影响。

> **v1.2 更新**（用户澄清：Manager 实测用 `delivery:queue` 发 INTERRUPT）：
> 根因比 v1.0/v1.1 更复杂：不是「immediate 投递等 turn 边界」，而是 **LLM 给 INTERRUPT Mail 传了 `delivery:"queue"`**——queue 模式持久化到磁盘 FIFO（`MailQueueStore.append`，`MailTool.scala:627`），要等当前任务完成 + 队列前面积压全部 drain 完才轮到它，INTERRUPT 语义彻底失效。
> **双保险方案**（用户裁定"两者都要"）：
> - **路径 A — 强制重路由**：`MailTool.call` 在 `type=INTERRUPT` 时无视 LLM 传的 `delivery`，强制走 `immediate` 真打断路径（`sendMail` 追加 `Interrupt()`）。彻底覆盖 LLM 选错模式的情况。
> - **路径 B — queue 队列内插队**：即便因任何原因 INTERRUPT 仍走了 queue（未来新代码路径、或 LLM 绕过），在 `MailQueueStore` 增加 `prepend` 方法，`queueToSession` 在 `mailType=="interrupt"` 时用 `prepend` 把它插到队首而非队尾——下次 drain 立即取到。
> 双保险：A 解决「LLM 选错模式」的根因，B 兜底「queue 路径上的 INTERRUPT 也要尽快执行」。

## 1. 结论先行

两个问题根因清晰、改动隔离，均为低风险中收益：

1. **INTERRUPT 不是真打断**：根因是 LLM 给 INTERRUPT Mail 传了 `delivery:"queue"`（实测），走磁盘 FIFO，要等当前任务完成+前面积压全部 drain 完才轮到；即便用 immediate 也只是 turn 边界注入。方案=**双保险**：A1 强制 INTERRUPT 走 `immediate`（无视 LLM 传的 delivery）+ A2 追加 `Interrupt()` 取消当前 turn + 兜底 B `MailQueueStore.prepend` queue 插队队首。
2. **pending mail 无法展开**：`pendingRowHtml` 只渲染截断的 160 字符预览，没有 `expanded` 切换，也没有点击绑定；而 **History 行**已有完整的展开/收起机制。方案：让 pending 行复用 History 行的展开交互（meta 行加「展开 ▾」hint、content 靠 CSS `max-height` 折叠、row click 切换 `.expanded`），后端 `MailQueueItem` 已带 `from/message/type/timestamp` 全部字段，无需新字段。

执行成本：后端 3 处小改动（A1/A2/B）+ MailQueueStore 新增方法 + 前端 1 处模板改造，合计约 60 行改动。

---

## 2. 现状分析

### 2.1 问题一：INTERRUPT 为何不是真打断

**消息窗口看不到蓝色气泡的真正原因（v1.2 更新）**：Manager 发 INTERRUPT 时实际上传了 `delivery:"queue"`，走的是磁盘 FIFO（`MailQueueStore`）——要等当前任务完成 + 队列前面积压全部 drain 完才轮到，INTERRUPT 语义彻底失效。即便用 immediate，也只是 turn 边界注入。两条线上 INTERRUPT 都无法真打断。

关键代码路径（全部拿到证据，无推测）：

1. **投递不分流** — `MailTool.sendMail`（`src/main/scala/nebflow/core/tools/MailTool.scala:1081`）：
   - `sendMail` 无条件构造 `AgentCommand.ImmediateInput(...)`（L1095），`eventType = Some(mailType.toLowerCase)`（L1101，仅用于 UI 标签，**不影响投递行为**）。
   - immediate/queue 投递对 `type` 字段的唯一表面作用是气泡标签「Interrupt」。

2. **processing 状态只排队** — `AgentActor.processing` 的 `ImmediateInput` 分支（`server/agent/AgentActor.scala:1844`）将消息 append 到 `pendingImmediateInputs`，**不触发任何取消**。

3. **在 turn 边界释放** — 第 2253 行 `finishTurnCont` 分支：`pendingImmediateInputs` 非空时，在 turn 结束注入 head 并重新 `pipeLlmCall`。`ToolsComplete` 分支（L1301）同理。两个 drain 点都是「turn 边界」。

4. **真打断路径存在（但只给用户 WS 用）** — `WebSocketRoutes.handleMessage`（`L897`）把前端 `interrupt` 事件转成 `AgentCommand.Interrupt()`；`Agent.Interrupt`（processing 分支）执行 `ctx.cancelCurrentTurn()` + `state.resetForInterrupt` + 回 idle（L1380-1395）。**这条路径 Mail 从不触发**。

### 2.2 问题二：mailbox pending 为何无法展开

Mailbox 是 `flowViewers.js` 里的 overlay（`openMailbox` → `openViewerShell`），分 **Pending** 与 **History** 两节：

- **Pending 节**（`flowViewers.js:125-143` `pendingRowHtml`）：`preview = slice(0,160)+'…'`，直接渲染进 `.flow-mail-content`——**没有 `expanded` 切换、没有 row click 绑定**。且 `.flow-mail-row.pending` CSS 显式设为 `cursor: default`（`flowCss.js:208`，覆盖了通用 `.flow-mail-row { cursor: pointer }`），用户看到的是「不可点击」的视觉信号，连尝试点击的诱因都没有。
- **History 节**（`flowViewers.js:231-248`）：meta 行带「展开 ▾」hint，row click 切换 `.expanded`，content 靠 CSS（`flowCss.js:181` `.flow-mail-row:not(.expanded) .flow-mail-content { max-height: 3.6em; overflow: hidden }`）折叠。**机制现成，pending 行没复用**。

错误 demo：Pending 节里投递前就 160 字符截断，用户即使想看完整内容也没有入口——这就是「pending 无法展开」的全部根因，纯前端。

**数据结构已够用**：队列项 `MailQueueItem`（`MailQueueStore.scala:22-37`）已含 `id / from / fromSession / message / type / timestamp / imagePaths`，REST `GET /teams/mail-queue/:sid`（`RestApiRoutes.scala:1140`）返回完整字段。前端展开只需读取 `message` 原文并按 markdown 渲染，无需后端新增字段。

**计数与列表的「对不上」**：
- 徽章（`teamPendingCount`，`flowHelpers.js:48`）累计各会话 `mailPendingCounts`，由 `mailQueued`/`mailDequeued` WS 事件更新（`MailQueueStore.size`）。
- Pending 列表由 REST fetch 实时加载。
- 差异来源：WS 事件按会话逐个携带 `pendingCount`，若某事件缺失/延迟（WS 断线、后台 agent 未走 wsSend 广播），徽章与 REST 实时值会暂差；`loadPendingSection` 的取消/重排也会让列表数先行。这部分是**展示层不同步**，问题正文聚焦「无法展开」，计数同步作为次要修复一起纳入（见方案）。

---

## 3. 改动方案

### 3.1 后端：INTERRUPT 真打断（双保险）

**行为语义（行为变更说明）**
- **现在**：LLM 发 `Mail(type=INTERRUPT)` 时若传了 `delivery:"queue"`（实测如此），邮件进入磁盘 FIFO，要等当前任务完成 + 队列前面积压全部 drain 完才轮到；即便用 immediate，也是等 turn 边界才注入。两种情况都会让长 turn / 长队列下的 INTERRUPT 堆积数分钟，目标 agent 无感知，发件人无反馈。
- **改后**：`type=INTERRUPT` 的 Mail **无视 LLM 传的 delivery**，强制走立即真打断路径——目标若正在 processing，立刻 cancel 当前 turn（LLM 流、工具执行），回 idle 并把 INTERRUPT 作为新 user turn 立即注入（~100ms 气泡）。若目标 idle 照常立即处理。queue 路径兜底：万一仍有 INTERRUPT 进 queue（未来新代码路径），插到队首。

**精确改动点（A 强制重路由；B queue 插队兜底）**

`src/main/scala/nebflow/core/tools/MailTool.scala`

**改动 A1 — `call` 强制重路由**（`MailTool.scala:146-150`）：在 `delivery` 解析处，若 `mailType == "interrupt"` 则无视 LLM 传的参数强制 `"immediate"`（INTERRUPT 永远走 `deliverToShortName` / `sendMail` 分支，绝不到达 `deliverQueue`）：
```scala
val deliveryRaw = input("delivery").flatMap(_.asString)
  .orElse { /* ask 参数 fallback（L147-149）: ask→"ask" else→"immediate" */ }
  .getOrElse("immediate")
// INTERRUPT 的语义 = 立即打断；无视 LLM 可能传错的 delivery（v1.2 实测 Manager 传了 queue）
val delivery = if mailType.equalsIgnoreCase("interrupt") then "immediate" else deliveryRaw
```

**改动 A2 — `sendMail` 追加打断信号**（L1095-1105）：在 `ref ! AgentCommand.ImmediateInput(...)` 前，按 `mailType == "interrupt"` 追加发送 `AgentCommand.Interrupt()`：
```scala
_ <- if mailType.equalsIgnoreCase("interrupt") then ref ! AgentCommand.Interrupt() else IO.unit
_ <- ref ! AgentCommand.ImmediateInput(...)  // 元数据完整，气泡标签保留
```
- 顺序：`!` 异步邮箱，同进程本地 actor 串行处理——`Interrupt()` 先取消当前 turn → 回 idle → `ImmediateInput` 由 idle 分支直接转 `UserInput`（`AgentActor.scala:721-723`）启动新 turn。
- 复用现有 `AgentActor.scala:1380-1395` 打断分支：`cancelCurrentTurn()` + `resetForInterrupt` + `markTeamIdle` + 回 idle。**不改该分支本身**。

`src/main/scala/nebflow/core/flow/MailQueueStore.scala`

**改动 B — 新增 `prepend` 方法**（与 `append` L72-90 对称）：
```scala
/** Prepend an item to the head of the queue (INTERRUPT priority). */
def prepend(sessionId: String, item: MailQueueItem): IO[Unit] =
  // 原子读写同 append，仅 updated = item :: current
```

**改动 B2 — `queueToSession` 插队**（MailTool.scala L627）：INTERRUPT 若仍走到 queue，用 `prepend` 而非 `append`：
```scala
_ <- if mailType.equalsIgnoreCase("interrupt") then MailQueueStore.prepend(sessionId, item)
     else MailQueueStore.append(sessionId, item)
```
> 说明：A1 已让 INTERRUPT 不经过 queue——B 是防御纵深（未来新路径 / 直接调用 queueToSession 的场合）。

**不动的东西**（谨慎边界）：
- `AgentCommand.Interrupt()` / `AgentCommand.ImmediateInput()` 协议（protocol.scala）不加字段。
- 不改 `Interrupt()` 定义（现 `case class Interrupt()`，L52）。可选增强：加 `reason: "mail-interrupt"` 仅用于日志/审计，不改行为。

**INTERRUPT 分支已有语义（AgentActor.scala:1380-1395）——不改，直接用：**
```
case AgentCommand.Interrupt() =>
  _ <- ctx.cancelCurrentTurn()
  _ <- emitStream(... AgentStreamEvent.Interrupted ...)
  _ <- state.pendingCompaction.flatMap(_.replyDeferred).traverse_(...complete(Left("Interrupted...")))
  _ <- markTeamIdle(...)
  val interruptedState = state.resetForInterrupt.withPendingCompaction(None)
  idle(...)   // 回 idle
```
其中 `cancelCurrentTurn()` 取消 `forkTurn` 注册的所有已跟踪 fiber（`LocalActorContext` L87-90）——即当前 turn 的 LLM 流/工具执行/持久化，全部在途 IO 被取消。

**关键风险点与处置（详见 §5 风险与回滚）：**

1. **打断前 `pendingImmediateInputs` 里已有的非 INTERRUPT 邮件会被丢弃**——本方案最重要的副作用，必须显式承认：`resetForInterrupt`（`protocol.scala:1019-1025`）调用 `ExecutionContext.idle(...)`，`idle` 工厂（`protocol.scala:724-737`）把 `pendingImmediateInputs` 重置为 `Nil`；随后的 `.copy(pendingEvents=..., outstandingSubagentResults=...)` **不恢复** `pendingImmediateInputs`。打断瞬间队列里积压的 immediate 邮件全部丢失。

   **这并非新引入的风险**：用户按 Stop / 前端 Interrupt（`AgentActor.scala:1380-1395`）走同一 `resetForInterrupt`，同样丢弃 `pendingImmediateInputs`。本方案只是让 Mail INTERRUPT 复用既有路径，风险面与现有「用户打断」完全一致。

   **为何仍可接受**：
   - INTERRUPT 的语义就是「紧急、优先、打断」——目标 agent 收到 INTERRUPT 后立即处理新任务，旧的 immediate 积压本就不是设计意图要保留的。
   - queue 模式的邮件**不受影响**：queue 邮件持久化在磁盘（`MailQueueStore`），不走 `pendingImmediateInputs`，打断不丢。
   - sub-agent 结果（`pendingEvents` / `outstandingSubagentResults`）**不受影响**：`resetForInterrupt` 显式保留这两个字段（L1022-1023）。
   - 若未来要保留打断时的 immediate 积压，需在 `resetForInterrupt` 加 `.copy(pendingImmediateInputs = s.execution.pendingImmediateInputs)`——属对现有「用户打断」语义的改动，**不在本方案范围**，保持现状一致性。

2. **打断的新 INTERRUPT 如何避免也被丢**：新注入的 `ImmediateInput` 由 idle 分支（`AgentActor.scala:721-723`）直接转 `UserInput` 启动新 turn——**不进入 `pendingImmediateInputs` 队列**，经 actor 邮箱串行到达。`Interrupt()` 先到 → 处理完毕回 idle → `ImmediateInput` 后到 → idle 分支处理。**新 INTERRUPT 本身不排队，天然插队**。

   关键时序：`Interrupt()` 先到 → processing 分支执行 `cancelCurrentTurn` + `resetForInterrupt`（清空 `pendingImmediateInputs`）→ 回 idle → `ImmediateInput` 后到 → idle 分支转 `UserInput` → `pipeLlmCall`。

3. **queue 路径的 INTERRUPT 插队**：若 INTERRUPT 因任何原因进了磁盘 FIFO，`prepend` 让它插到队首——下一次 drain（`MailQueued` / `finishTurnCont` queue 分支）立即取到，无需等前面积压。

**序列**（文字版）：Sender → `MailTool.call`（type=interrupt, delivery 被强制 immediate）→ `sendMail` → 目标 actor: ① `Interrupt()` → cancelCurrentTurn → 回 idle → markTeamIdle → ② `ImmediateInput` → idle 分支转 `UserInput` → `pipeLlmCall` 新 turn → 蓝气泡即时出现。

### 3.2 前端：mailbox pending 展开显示

**行为变更说明**
- **现在**：mailbox 的 Pending 区每行只显示 160 字符截断预览，没有展开入口，看不到完整内容。
- **改后**：Pending 区每行与 History 行一致——meta 行显示 from → to、queue 标签、type 标签、相对时间、**「展开 ▾」hint**；点击行（或 hint）展开完整 message（markdown 渲染），再点收起。Cancel 按钮保持独立（点击 Cancel 不触发展开）。

**精确改动点**

`src/main/resources/web/js/flowViewers.js` — `pendingRowHtml`（L125-143）改三处：
1. content 不预截断：`.flow-mail-content` 内容 render markdown 全文（用 `renderMarkdownWithMath` 与 history 一致），折叠交给 CSS（`flowCss.js:181` 已有规则）。
2. meta 行追加 `展开 ▾` hint span（复用 `.flow-mail-expand` 类与 History 行的文案切换逻辑）。
3. 行点击：在 `loadPendingSection` 的 render 后为每个 `.flow-mail-row.pending` 绑定 click → toggle `.expanded` + 更新 hint 文案（与 History 行 L242-248 相同）。Cancel 按钮需 `e.stopPropagation()`（已有）避免触发折叠切换。

可选增强：在 meta 行 limit from/to 宽度（沿用 `.flow-mail-from/.flow-mail-to` 已有 max-width），长 senders 不溢出。

**计数同步修复（同批）**：`flowViewers.loadPendingSection` 在成功 render 后用 `items.length` 校正 `setMailPending`（每会话）。这样 Pending 列表的行数与 badge 数在打开面板后必然一致；未打开时仍靠 WS 广播（保持现状）。

### 3.3 改动文件清单

| 文件 | 改动 | 层级 |
|---|---|---|
| `src/main/scala/nebflow/core/tools/MailTool.scala` | A1 `call` 强制重路由（INTERRUPT→immediate）+ A2 `sendMail` 追加 `Interrupt()` + B2 `queueToSession` prepend | 后端 |
| `src/main/scala/nebflow/core/flow/MailQueueStore.scala` | B 新增 `prepend` 方法（队列插队兜底） | 后端 |
| `src/main/resources/web/js/flowViewers.js` | `pendingRowHtml` 展开 + `loadPendingSection` click 绑定 + 计数校正 | 前端 |
| `src/main/resources/web/js/flowCss.js` | （可选）pending 行展开态样式微调 + cursor 改 pointer | 前端 |
| `src/test/scala/...` | 新增 INTERRUPT 强制重路由 + 真打断 + prepend 插队单测 | 测试 |

---

## 4. 预期变更展示

### 4.1 后端：邮件路径对比

| 阶段 | 现状 | 改后 |
|---|---|---|
| `MailTool.call` delivery 解析 | 按 LLM 传的 `delivery`（可能 queue） | INTERRUPT 强制改 `immediate`（A1） |
| MailTool.sendMail | 一律 `ImmediateInput` | INTERRUPT 加发 `Interrupt()`（A2） |
| MailQueueStore | 只 `append` | INTERRUPT 走新增 `prepend`（B）插队首 |
| processing 收到 | 排 `pendingImmediateInputs` | `cancelCurrentTurn` → 回 idle |
| 注入时机 | turn 结束才 drain | 立即以新 turn 注入 |
| 气泡出现 | 延迟 N 秒/分钟 | ~100ms |
| `pendingImmediateInputs` 积压 | turn 结束后逐条 drain | **被 `resetForInterrupt` 清空**（与用户 Stop 同行为） |
| 附带副作用 | 无 | 打断方得以「立即反馈」（气泡=反馈信号） |

### 4.2 前端：pending 行展开设计

下图是 Pending 行改动后的视觉设计（折叠态 vs 展开态对比），用 HTML 自述展示。

---

## 5. 风险与回滚

### 5.1 真打断的主要风险：正在执行的工作如何被处理

`ctx.cancelCurrentTurn()`（`ActorContext.scala:87-90`）会取消**当前 turn 的全部已追踪 fiber**（LLM 流及其后置链、工具执行 IO、持久化 fork）。因此被打断的 turn：
- LLM 已流出的文本**不会回滚**（WS 已收的 TextDelta 无法撤回）——打断只停止后续未发生的工作，不修改已发生的输出。
- 工具执行（写入文件等）若执行了一半被取消——与现有「用户按 Stop」完全相同的语义，因为两者走同一 `Interrupt()`。**Nebflow 已有此机制，风险面不变**，只是触发源多了一个（Mail INTERRUPT）。
- `pendingCompaction` 的 deferred 会被 complete(Left(...))（L1387-1389）——与现打断一致，compaction job 不悬空。

**如何把「丢工作的风险」降到最低（设计决策）**：
- INTERRUPT 打断后**立即**以 INTERRUPT 内容开新 turn——目标 agent 收到的新任务是「改做 INTERRUPT 提到的事」，而不是空转。`resetForInterrupt` 保留 `pendingEvents/outstandingSubagentResults`（L1019-1025 注释明说「held results survive an interrupt」），子代理结果屏障不受破坏。
- **`pendingImmediateInputs` 会被清空**（`resetForInterrupt` → `ExecutionContext.idle` → `pendingImmediateInputs = Nil`）——打断时积压的 immediate 邮件丢失。这与现有「用户按 Stop」行为一致（同一代码路径），不是新引入的风险。queue 邮件不受影响（磁盘持久化）。详见 §3.1 处置 1。
- 打断只作用于**目标 agent 单个 actor**，链路不扩散——Sender、团队其他成员不受影响。

**语义边界**：INTERRUPT 只保证「立即注入」；若目标正处于 `WaitingForUser`（如 AskUserQuestion 等待用户回答），`cancelCurrentTurn` 无在途 LLM fiber，`Interrupt` 只是从 waiting 回 idle，INTERRUPT 消息照常注入。**不承诺**「用户的 AskUser 交互弹窗被自动关闭、自动批准/拒绝」——那超出打断范畴，保持现状。

### 5.2 前端风险

- 展开状态在 `loadPendingSection` 重载后丢失（每次 WS mailQueued/mailDequeued 或 Cancel 后都会重新 load）。可接受：pending 是易变数据，展开只保证「当次完整可见」，刷新后回到折叠态。若希望展开态持久，可把展开的 itemId 存 `Set`（key = itemId，跨 reload 保留）——作为可选增强列在 §7。
- `renderMarkdownWithMath` 渲染全文——超长 mail 会让行变高。由 CSS `max-height` 兜底，折叠态始终只露 3.6em。

### 5.3 回滚

改动集中于 3 个后端文件 + 1 个前端文件 + 测试，全部新增逻辑、无 API 破坏。回滚 = `git revert`：
- 后端 A1（`MailTool.call` delivery 强制）：删 1 行 `val delivery = if mailType...`
- 后端 A2（`sendMail` 追加 Interrupt）：删 1 行 `_ <- if mailType...`
- 后端 B（`MailQueueStore.prepend`）：删新增方法 + B2 改回 `append`
- 前端：还原 `pendingRowHtml` 截断版 + 删 click 绑定

无 schema 迁移、无持久化格式变更（`prepend` 写入格式与 `append` 一致，旧文件兼容）。

---

## 6. 验收条件

> 红线规则（本任务涉服务端）+ 前端分层（本任务涉 mailbox 面板）。约束声明：**前端 QA 简化，用户自验**——Playwright 级验证由用户执行，方案只保证静态资源与后端链路。

### 6.1 后端（自动化，二值）

- **AC1** 冒烟测试（硬性）：`sbt compile` 退出码 0。
- **AC2 强制重路由单测**：`Mail(type="interrupt", delivery="queue")` → 断言 A1：`delivery` 被改成 `immediate`（不经过 `deliverQueue`）；A2：目标 actor 收到 `Interrupt()` 命令。
- **AC3 真打断单测**：mock LLM（快速流）+ 真实 `AgentActor`，目标 processing 时收到 INTERRUPT → 断言目标 messages 出现 INTERRUPT 文本 + 回 idle + busy 清除（对应 `markTeamIdle`）。
- **AC4 顺序保证单测**：`Interrupt()` 在 `ImmediateInput` 之前被处理——断言「打断 turn 的内容是 INTERRUPT 文本」而非先消费 pendingImmediateInputs 里的旧消息。
- **AC5 回归：非 INTERRUPT 不打断**：`type=INFO/RESULT/FOLLOW_UP` 在目标 processing 时仍走 pendingImmediateInputs（不触发强制重路由），turn 不被打断——覆盖 `finishTurnCont`/`ToolsComplete` 分支。
- **AC6 prepend 插队单测**：`MailQueueStore.prepend` 后 `load` 返回队首为 INTERRUPT 项，`removeHead` 先取到它。

### 6.2 前端（静态资源 + 用户自验）

- **F1 静态资源 curl**：`curl /` 200 + HTML 含挂载点；`curl /js/flowViewers.js` 200 + text/javascript + 内容非空（改动所在文件）。
- **F2 用户自验（约束：前端 QA 简化）**：打开 team mailbox → Pending 区每行点击可展开完整 message（含 markdown 渲染）、再点收起；Cancel 按钮独立可用；展开状态在 Cancel/移除后（reload）回到折叠。
- **F3 计数一致性（用户自验）**：mailbox 打开后 Pending 区标题计数与面板内行数一致，团队卡徽章与打开后行数一致（校正逻辑生效）。
- **F4 用户自验（INTERRUPT 场景回归）**：对 Manager 发 `Mail(type="INTERRUPT")`（显式传 `delivery:"queue"` 验证强制重路由生效），Manager 长 turn 时气泡数秒内出现、当前 turn 被打断、INTERRUPT 内容立即处理。

### 6.3 编译 + 测试

- `sbt compile`：通过。
- 后端测试：`sbt test`，新增打断相关单测 ≥3 条通过（AC2/AC3/AC4/AC6），存量用例不回归。

> 说明：本环境执行 `sbt` 依赖完整构建链，若环境不可用，验收落到「代码评审 + 冒烟命令已列明」，交由派发方（Manager）在具备构建条件处执行。前端交互级 QA 改由用户自验——这是约束声明，不是省略。

## 7. 不做的事 / 可选增强（范围外）

- 不新增 Mail delivery mode（保持 API：ask / queue / immediate）。
- 不改 `AgentCommand.Interrupt()` 协议签名——可选增强：给 `Interrupt` 加 payload（来源/理由），让目标 agent 能区分「USER 打断」与「MAIL 打断」（现状两者语义一致，不引入）。若要加，字段 `reason: "mail-interrupt"`，用于日志/审计，不改行为。
- 不改 mailbox 的 History 节（已可展开）。
- 可选增强：pending 展开态持久化（itemId 集合跨 reload 保留）；打断提示 UI 层「已打断 agent」通知。
- 不做 IM-interrupt 的 UI 层通知条——除非用户后续要求。

---

_版本历史：v1.0 初稿 2026-08-19；v1.1 勘误 2026-08-19（CSS cursor 事实修正 + `resetForInterrupt` 清空 `pendingImmediateInputs` 的事实修正）；v1.2 2026-08-19（用户澄清：LLM 实测给 INTERRUPT 传了 `delivery:"queue"`——根因改为 LLM 选错模式。新增双保险方案：A1 强制重路由 + A2 追加 Interrupt + B/B2 queue prepend 插队兜底）_
