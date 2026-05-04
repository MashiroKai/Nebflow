# Issue: 压缩流程可观测性、自动续跑与真实 Token 触发

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`templates/_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。
>
> **升级规则**：见 `_glossary.md` → Quick Fix 升级规则。涉及行为变更时，关闭 quickfix，改用本模板。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | backend, observability, context-management |
| 优先级 | P1-高 |
| 创建日期 | 2026-05-04 |
| 目标日期 | 未确定 |
| 预估工时 | 4h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

> 优化上下文压缩的触发精度、运行可见性与自动恢复机制：使用上一轮 LLM 真实 usage 触发压缩，压缩子 agent 过程对前端透明，压缩成功后自动恢复 LLM 推理，并持久化压缩前后的消息对比记录。

---

## 背景（可选）

> Issue `007-redesign-context-management.md` 已完成上下文管理的基础正确性修复（消息顺序、tool 配对、熔断、快照）。本 issue 在此基础上解决触发精度、可观测性和自动恢复三个体验问题。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

1. **Token 触发不精确**：`AgentActor.maybeAutoCompact` 当前使用 `state.latestUsage.map(_.inputTokens).getOrElse(TokenEstimator.estimate(...))`。当 `latestUsage` 缺失时 fallback 到字符近似估算（`chars/3`），与真实 token 消耗偏差大，可能导致过早/过晚触发。
2. **压缩过程干扰用户**：`context-manage` 子 agent 运行时，其内部的 `AgentStart`/`AgentEnd`/`TextDelta`/`ToolStart`/`ToolEnd` 等事件通过 `SubagentStreamEvent` 透传给前端，用户会看到一闪而过的子 agent 名称和碎片输出，但这对终端用户毫无意义。
3. **手动压缩后对话卡住**：LLM 主动调用 `ContextManage` 工具（manual compact）成功后，`AgentActor` 仅更新内存 `state.messages` 并返回 `processing(...)`，不再触发 `pipeLlmCall`。对话流停止，用户必须再次发送消息才能继续。
4. **缺乏可对比的压缩记录**：当前 `HistoryArchiver.archive` 只保存了压缩前的 `before` 快照。排查压缩问题时，无法直接拿到同一时间点、同一文件内的 `before`/`after` 完整消息数组做 diff。

---

## [创建] 目标

**一句话定义成功状态。**

> Auto-compact 严格基于真实 usage 触发；compression subagent 的运行过程对前端完全透明；无论 auto 还是 manual 压缩，成功后均自动恢复 LLM 推理；每次压缩在磁盘上留下包含 before/after 完整消息的对比文件。

---

## [创建] 范围

### In Scope

- [ ] `maybeAutoCompact` 中移除 `TokenEstimator.estimate` fallback，严格使用 `state.latestUsage.inputTokens` 作为触发依据。
- [ ] 压缩子 agent 的运行过程对前端 WebSocket 不可见：内部 `TextDelta`/`ToolStart`/`ToolEnd`/`AgentStart`/`AgentEnd` 等事件不 emit 到前端，但保留后端 `logAgentEvent` 与 `NebflowLogger` 记录。
- [ ] Manual compact（`TriggerCompaction` via `ContextManageTool`）成功后自动调用 `pipeLlmCall` 恢复 LLM 推理；失败且未熔断时同样调用 `pipeLlmCall` 使用原始消息继续，保持与 auto compact 行为一致。
- [ ] 扩展 `HistoryArchiver`，在压缩完成后写入包含 `before` / `after` 完整消息数组的对比 JSON 文件（路径返回并记入日志）。

### Out of Scope

- [ ] 接入真实 tokenizer（tiktoken / Anthropic count_tokens API）—— 拆为独立 issue。
- [ ] 压缩质量回检（检查摘要是否遗漏关键决策）—— 后续 issue。
- [ ] 前端"恢复到压缩前"的 UI 回滚功能 —— 后续 issue。
- [ ] 改变 `ContextManageTool` 对外的 `inputSchema` 字段。
- [ ] 改变压缩子 agent 的 prompt 或解析逻辑。

---

## [创建] 需求

### 功能需求

1. **真实 Usage 触发**
   - `maybeAutoCompact` 中 `inputTokens` 仅读取 `state.latestUsage.map(_.inputTokens)`。
   - 若 `latestUsage` 缺失（如首次 turn 或 usage 未上报），**不触发** auto compact，也不再 fallback 到 `TokenEstimator.estimate`。
   - `TokenEstimator` 模块本身保留（测试/Demo 仍使用），仅在 auto compact 触发逻辑中移除 fallback 调用。

2. **压缩过程前端不可见、后端可查**
   - `CompactionDefLoaded` 中 spawn 压缩子 agent 时，传给子 agent 的 `wsSend` 替换为静默函数 `_ => IO.unit`，使其内部事件不涌向前端。
   - 父 agent 在 `CompactionDefLoaded` 处理中不再 emit `AgentStreamEvent.AgentStart(...)` 到前端 WebSocket。
   - 父 agent 的 `SubagentStreamEvent` 处理中，若事件来源是 `state.pendingCompaction.map(_.subagentId)`，则跳过 `emitStream`，仅保留 `logAgentEvent`（DEBUG 级别）。
   - `CompactStart` / `CompactComplete` / `CompactFailed` 三个专用事件继续向前端发送，用户仍可感知"压缩开始/结束"。

3. **压缩后自动恢复 LLM 推理**
   - `DelegateResult` 处理 compaction 的**成功分支**中，`if pending.replyTo.isDefined then ... else ...` 的 `else` 分支不再只是返回 `processing(...)`，而是调用 `pipeLlmCall(agentDef, resources, depth, parentRef, successState, stash, ctx, None)`。
   - **失败但未熔断分支**中，`else if pending.replyTo.isDefined then ... else ...` 的 `else` 分支同样调用 `pipeLlmCall(..., failedState, ..., None)`，避免 manual compact 失败后对话卡住。
   - Auto compact 的现有行为（`replyTo` 有值时）保持不变。

4. **压缩前后消息对比记录**
   - 新增 `HistoryArchiver.archiveComparison(sessionId, mode, before, after): IO[Either[String, String]]`。
   - 输出格式：单一 JSON 文件，包含 `timestamp`、`mode`、`beforeCount`、`afterCount`、`before`（Message 数组）、`after`（Message 数组）。
   - 文件路径示例：`archives/<sessionId>/<ts>-comparison.json`。
   - 在 `AgentActor.DelegateResult` 的 compaction 成功分支中，调用 `archiveComparison`（非阻塞；失败仅记日志，不中断主流程）。
   - `CompactComplete` 事件中的 `snapshotPath` 改为指向对比文件路径（或新增字段 `comparisonPath`）。

### 非功能需求

- [ ] 向后兼容：前端不感知内部子 agent 事件消失；`CompactStart`/`CompactComplete`/`CompactFailed` 事件格式不变。
- [ ] 不引入新的阻塞 IO：快照/对比写入均走 `unsafeRunAndForget` 或 `handleError`，失败不卡主流程。

---

## 设计

### 接口/行为变更

- **新增接口**：`HistoryArchiver.archiveComparison(...)`
- **变更接口**：`AgentActor.maybeAutoCompact` 触发逻辑；`AgentActor.DelegateResult` 的 manual compact 成功/失败分支；`AgentActor.CompactionDefLoaded` 的 wsSend 传递。
- **废弃接口**：无。

### 关键决策

| 决策点 | 选项 A | 选项 B | 选择 | 理由 |
|--------|--------|--------|------|------|
| manual compact 失败后是否也恢复 LLM？ | 仅成功恢复 | 成功/失败均恢复 | **B** | 与 auto compact 行为一致，避免对话卡住；失败时仍用原始消息尝试，由 LLM/上游处理可能的上下文超限 |
| 对比记录是单文件还是双文件？ | 单文件含 before+after | 两个独立文件 | **A** | 单文件便于 `jq` 提取后直接 `diff`；路径管理更简单 |
| 前端是否仍能看到 compact 状态？ | 隐藏全部 | 保留 CompactStart/Complete/Failed | **B** | 用户需要知道"正在压缩"和"压缩完成"，但不需要看子 agent 内部碎片 |

### 代码示例

```scala
// 1. 真实 usage 触发（AgentActor.scala）
val inputTokens = state.latestUsage.map(_.inputTokens)
inputTokens match
  case Some(tokens) if tokens > threshold =>
    // trigger auto compact
  case _ =>
    // 不触发，无论 TokenEstimator 估算值多少
    None

// 2. 压缩子 agent 静默 wsSend（AgentActor.scala）
val silentWsSend: Json => IO[Unit] = _ => IO.unit
val subActor = ctx.spawn(
  AgentActor(subDef, resources, silentWsSend, depth + 1, Some(ctx.self), readTracker = state.readTracker),
  subId
)
// 不再 emit AgentStart 到前端

// 3. manual compact 成功后自动续跑（AgentActor.scala）
if pending.replyTo.isDefined then
  pipeLlmCall(agentDef, resources, depth, parentRef, successState, stash, ctx, pending.replyTo)
else
  pipeLlmCall(agentDef, resources, depth, parentRef, successState, stash, ctx, None)

// 4. 对比记录（HistoryArchiver.scala）
def archiveComparison(
  sessionId: String,
  mode: String,
  before: List[Message],
  after: List[Message]
): IO[Either[String, String]] = IO.blocking {
  try
    val ts = java.time.Instant.now().toEpochMilli
    val dir = root / "archives" / sessionId
    os.makeDir.all(dir)
    val target = dir / s"$ts-comparison.json"
    val json = Json.obj(
      "timestamp" -> Json.fromLong(ts),
      "mode" -> Json.fromString(mode),
      "beforeCount" -> Json.fromInt(before.size),
      "afterCount" -> Json.fromInt(after.size),
      "before" -> before.asJson,
      "after" -> after.asJson
    )
    os.write.over(target, json.spaces2)
    Right(target.toString)
  catch case e: Throwable => Left(e.getMessage)
}
```

---

## [创建] 兼容性

- **向后兼容：** Yes
  - `CompactStart`/`CompactComplete`/`CompactFailed` 事件格式不变；前端仍可见压缩起止。
  - 内部子 agent 事件消失对前端是"少收到消息"，不会破坏已有逻辑。
- **行为变更：**
  - `maybeAutoCompact` 在 `latestUsage` 缺失时不再触发（之前会 fallback 到估算值并可能触发）。
  - Manual compact 成功后自动触发下一轮 LLM 调用（之前会静默等待）。
- **迁移指南：** 无需用户/调用方调整。

---

## [结束] 成功标准

**如何量化地判断是否成功？**

- [ ] `grep -n 'TokenEstimator.estimate' src/main/scala/nebflow/agent/AgentActor.scala` 无输出（`maybeAutoCompact` 中不再调用）。
- [ ] 运行一条足够长的对话（触发 auto compact），前端控制台**看不到** `agentStart`/`agentEnd`/`agentTextDelta` 等压缩子 agent 的事件，但能看到 `compactStart` + `compactComplete`。
- [ ] LLM 主动调用 `ContextManage` 工具（manual compact）成功后，无需用户再次输入，对话自动继续（LLM 给出下一轮回复）。
- [ ] `archives/<sessionId>/` 目录下存在 `*-comparison.json` 文件，其内容包含 `before` 和 `after` 字段，且 `jq '.before | length'` 与 `jq '.after | length'` 分别等于压缩前后的消息数。
- [ ] All existing functionality preserved.

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| `latestUsage` 缺失导致 auto compact 永不触发 | Medium | `latestUsage` 在 `LlmComplete` 中已可靠设置；若 provider 未上报 usage（极少见），对话会自然增长到 provider 报错，用户可手动调用 ContextManage 或 Interrupt |
| manual compact 自动续跑可能导致意外多轮输出 | Low | 与 auto compact 行为一致，符合预期；`finish` 工具检测仍会在 LLM 想结束时生效 |
| 对比文件过大导致磁盘压力 | Low | 文件名带 timestamp；本次不改 retention；在项目根 `.gitignore` 中已忽略 `archives/` |

---

## [结束] 验证

### 功能验证

- [ ] 步骤一：构造超过阈值的对话 → 确认触发 auto compact → 检查前端无子 agent 碎片事件 → 检查 `archives/` 下出现 `*-comparison.json` → 对话自动继续。
- [ ] 步骤二：在对话中让 LLM 调用 `ContextManage(mode="micro")` → 确认压缩完成后 LLM 自动给出下一轮回复。
- [ ] 步骤三：mock `latestUsage = None` → 确认 `maybeAutoCompact` 返回 `None`，不触发压缩。

### 测试策略

- [ ] 单元测试：`CompactionPolicySpec` 补充 "latestUsage present triggers compact" / "latestUsage absent skips compact" 用例。
- [ ] 单元测试：`AgentActorCompactionSpec` 补充 "manual compact success resumes LLM" / "manual compact failure resumes LLM with original messages" 用例。
- [ ] 单元测试：`HistoryArchiverSpec` 补充 `archiveComparison` 写入与内容校验用例。

### 回归检查

- [ ] Auto compact 成功后仍调用 `pipeLlmCall`（行为不变）。
- [ ] `CompactStart`/`CompactComplete`/`CompactFailed` 事件仍被前端接收（行为不变）。
- [ ] 现有 SubAgent（非压缩）的 `AgentStart`/`AgentEnd` 仍正常透传（不受静默逻辑影响）。

---

## 关联

- Depends on `007-redesign-context-management.md` — 前置基础修复。
- Related to `007-progress-aware-adaptive-loop.md` — 均涉及 `AgentActor` 状态流转。
- Blocks 后续 issue：`010-tokenizer-integration.md`（真实 tokenizer）。

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `src/main/scala/nebflow/agent/AgentActor.scala` | **Modify** | 移除 `TokenEstimator.estimate` fallback；静默压缩子 agent wsSend；manual compact 成功后调用 `pipeLlmCall`；`SubagentStreamEvent` 过滤 compaction 子 agent |
| `src/main/scala/nebflow/core/compact/HistoryArchiver.scala` | **Modify** | 新增 `archiveComparison` 方法 |
| `src/test/scala/nebflow/core/compact/CompactionPolicySpec.scala` | **Modify** | 补充触发条件测试（latestUsage present/absent） |
| `src/test/scala/nebflow/agent/AgentActorCompactionSpec.scala` | **Modify** | 补充 manual compact 自动续跑测试 |
| `src/test/scala/nebflow/core/compact/HistoryArchiverSpec.scala` | **Modify** | 补充 `archiveComparison` 测试 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已完成
- [ ] 重复 — 重复 issue：
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘（可选）

> `[结束]` 时填写。若关闭原因为"重复"/"无法复现"/"已过时"，可跳过。

| 问题 | 回答 |
|------|------|
| 目标达成了吗？ | |
| 有什么意外？ | |
| 用户反馈如何？ | |
| 工时预估偏差原因？ | |
