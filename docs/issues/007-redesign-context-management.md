# Refactor: 重构上下文管理工具的正确性、安全与可观察性

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`templates/_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成前必须填写；未标注 = 按需填写
> 状态流转见 `templates/_glossary.md` → Engineering Task。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | backend, security, perf |
| 优先级 | P1-高 |
| 创建日期 | 2026-05-04 |
| 目标日期 | 未确定 |
| 预估工时 | 16h |
| 实际工时 | 未评估 |

---

## [创建] 一句话描述

> 修复上下文管理工具的正确性问题（消息顺序、tool_use/tool_result 配对、原始历史不可逆丢失）、安全问题（SubAgent 工具白名单语义反转、prompt 注入面）与可观察性问题（前端无 compact 事件），并删除围绕 ContextManageTool 的双重实现与死代码。

---

## 背景

`feat/multi-agent` 与 `feat/context-management` 分支合并入 main 后（commit `ebbb1b2`、`37ba0c5`），上下文管理由 `nebflow.core.compact.*` 与 `AgentActor` 共同实现，分为 **autocompact**（turn 间自动）和 **manual compact**（LLM 调用 `ContextManage` 工具）两条触发路径，两者最终都通过 spawn `subagents/context-manage` 子 agent 来运行 LLM 摘要。

实测与代码审查发现该实现存在多处正确性缺陷（消息顺序错乱可被 Anthropic API 直接 400、原始历史被覆盖且无备份）、安全缺陷（压缩 SubAgent 默认拥有 Read/Write/Bash 全部工具）以及大量死代码（`autoCompactThreshold`、`circuitBreakerMax`、`pendingManualCompaction`、`MicroCompact.compact`、`FullCompact.compact` 全部从未被读取/调用）。本次 issue 在不引入新功能的前提下完成一次系统性整改。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

`AgentActor` 与 `nebflow.core.compact.*` 共同承担"判定阈值 → spawn SubAgent → 解析输出 → 替换历史"的全流程，但职责互相穿插、错误处理缺失、死代码混杂，导致：

1. **触发条件与文档分离**：`ContextManageTool.description` 自称"80% 阈值"，代码实际公式是 `agentDef.contextWindow - CompactConfig().bufferTokens`（13000），且 `CompactConfig.autoCompactThreshold = 0.80f` 字段从未被读取。
2. **正确性破坏**：`MicroCompact.parseAndApply` 在补齐缺失 index 时把 `Keep(missing)` **尾插**到指令列表，重建后的 messages 顺序错乱（user/assistant 不再交替），同时不校验 `tool_use`/`tool_result` 配对，Anthropic API 会直接 400。
3. **不可逆数据丢失**：`FullCompact.parseResponse` 用单条摘要消息**完全替换** `state.messages`，且 `SessionStore` 在 `AgentActor.ToolsComplete` 与 `finishTurn` 时直接覆盖文件，无快照、无 audit、无回滚通道。
4. **安全面**：`AgentActor.buildToolList` 把 `tools = Nil` 解释为"所有工具"，导致压缩 SubAgent 默认获得 Read/Write/Bash/Edit/ContextManage 全部工具；同时压缩 SubAgent 通过 `UserInput(messagesJson)` 把整个会话 JSON 当用户输入塞入，对话中任何不可信文本都可能改写压缩行为（间接 prompt 注入）。
5. **压缩失败无熔断**：`DelegateResult` 失败分支用原超长 messages 直接 `pipeLlmCall`，下一轮 LLM 调用前 `maybeAutoCompact` 又会再次触发，构成潜在死循环；`CompactConfig.circuitBreakerMax` 字段没有任何引用。
6. **双实现漂移**：`ContextManageTool.call` 与 `AgentActor.pipeToolExecutions` 的 `case "ContextManage"` 分支是两份独立实现，文案不同步，schema 修改可能不影响真实行为。
7. **可观察性缺失**：WebSocket 不发送 compact 专用事件；前端无法区分"busy = 推理 / 工具执行 / 压缩中"。

| Concern | Current Location | Should Be |
|---------|------------------|-----------|
| 是否需要压缩 + 触发阈值 | `AgentActor.maybeAutoCompact` 内联，`bufferTokens` 写死，`CompactConfig` 字段不读 | 单一真相源：`CompactConfig` 完整暴露阈值并被真实读取 |
| Token 估计 | `AgentActor.estimateTokens` (chars/3) 与 `latestUsage.inputTokens` 二选一 | 抽到 `compact.TokenEstimator`，明确"上一轮 used"与"即将发送"语义差异 |
| 压缩失败/死循环熔断 | 无 | `CompactConfig.circuitBreakerMax` 真正生效 + 原始历史快照可回滚 |
| 压缩输出解析 | `MicroCompact` / `FullCompact` 各自硬编码正则 | 容错正则 + 排序后 apply + tool_use/tool_result 配对校验 |
| ContextManage 工具运行 | `ContextManageTool.call` + `AgentActor.pipeToolExecutions` 双份 | 单一实现，Tool 是 façade，AgentActor 仅响应消息 |
| SubAgent 工具白名单 | `tools = Nil` ⇒ 全部工具 | `tools = Nil` ⇒ 无工具；`AgentLibrary` 显式声明 |
| 可观察性 | 普通 `AgentStart`/`AgentEnd` 子流 | 专用 `CompactStart` / `CompactComplete` / `CompactFailed` 流事件 |

关键代码片段（行号锚定 main HEAD `83640a5`）：

```scala
// AgentActor.scala:541-543 —— 触发公式与 CompactConfig 字段脱钩
val inputTokens = state.latestUsage.map(_.inputTokens).getOrElse(estimateTokens(state.messages))
val threshold = agentDef.contextWindow - CompactConfig().bufferTokens
if inputTokens > threshold then ...
```

```scala
// MicroCompact.scala:55-95（Keep(missing) 尾插在 76-77 行）—— 缺失 index 尾插破坏顺序
val coveredIndices = instructions.flatMap { ... }.toSet
val expectedIndices = (0 until messages.length).toSet
val missing = (expectedIndices -- coveredIndices).toList.sorted
val augmentedInstructions =
  if missing.nonEmpty then instructions :+ Keep(missing) // <-- 顺序错乱根因
  else instructions
augmentedInstructions.flatMap {
  case Keep(indices) => indices.map(messages(_))
  case Compact(_, _, summary) => List(Message(MessageRole.User, Left(s"<context-compact mode=\"micro\">...$summary</context-compact>")))
}
```

```scala
// AgentActor.scala:1091-1094 —— 工具白名单语义反转
private def buildToolList(...): Option[List[ToolDefinition]] =
  val base =
    if agentDef.tools.nonEmpty then ToolRegistry.ALL_TOOLS.filter(t => agentDef.tools.contains(t.name))
    else ToolRegistry.ALL_TOOLS // <-- context-manage tools=Nil → 拿到全部工具
```

```scala
// FullCompact.scala:54-72 —— 直接覆盖原始历史
val summaryMessage = Message(MessageRole.User, Left(buildSummaryWrapper(text)))
val fileRestoreMessage = ...
Right(List(summaryMessage) ++ fileRestoreMessage.toList) // <-- 整段历史只剩 1~2 条
```

---

## [创建] 目标

**一句话定义成功状态。**

> 上下文管理对外行为不变（auto/manual 两条触发路径仍可用），但内部满足：① 任何压缩都不会让消息顺序或 tool_use/tool_result 配对失效；② 任何压缩都先把原始 messages 写入快照，可通过单一日志条目追踪；③ 压缩 SubAgent 仅在显式白名单内拿到工具；④ 失败次数累计达 `CompactConfig.circuitBreakerMax` 时熔断并明确报错；⑤ `ContextManageTool` 与 `AgentActor` 中只剩一份实现；⑥ 前端可通过专用 stream 事件感知压缩开始/完成/失败；⑦ 死代码全部清理。

---

## [创建] 范围

### In Scope

- [ ] 修正 `MicroCompact.parseAndApply` 中缺失 index 的处理：按原消息 index 升序合并所有指令(对 `Keep(idx)` 取 `idx.min`、对 `Compact(s,e,_)` 取 `s` 作为排序键)后再 apply；同时对 `Keep(indices)` 内部做 `idx.distinct.sorted`，避免 LLM 输出 `<keep>3,1,2</keep>` 时仍破坏顺序；校验产物中 `tool_use`/`tool_result` 配对(下文 §2)。
- [ ] `FullCompact.parseResponse` 与 `SessionStore` 协作，在每次压缩**写盘前**保存 `archives/<sessionId>/<ts>.json` 快照(JSON 数组,与 `SessionStore` 的 `spaces2` 格式一致,便于后续做单一回滚路径);快照路径作为日志/事件字段返回。
- [ ] 修正 `AgentActor.buildToolList`(`AgentActor.scala:1091-1094`)的语义:`tools = Nil` 解释为"无工具"、`tools = List("*")` 解释为"全部工具"。同步 audit `AgentLibrary` 中所有 builtin agent 定义:
  - `context-manage`:显式 `tools = List()`(压缩 SubAgent 不需要任何工具)。
  - `default` agent(SessionActor 的 fallback,见 `AgentLibrary.scala:33-43`):**必须**改为 `tools = List("*")`,否则普通 chat 在重构后将失去所有工具能力。
- [ ] 删除 `AgentActor.pipeToolExecutions` 的 `case "ContextManage"` 硬编码分支(`AgentActor.scala:693-704`),统一走 `ToolRegistry` → `ContextManageTool.call`。`ToolContext.agentActorRef` 已就绪(`types.scala:15`),无需任何上下文改造。
- [ ] 启用 `CompactConfig.circuitBreakerMax`:`AgentState` 增加 `compactionFailures: Int`(默认 0),连续失败达阈值后将状态切到 `idle` 并通过 `AgentEvent.Failed` + `AgentStreamEvent.CompactFailed` 上报;成功后清零。`maybeAutoCompact` 在 `compactionFailures >= circuitBreakerMax` 时直接返回 `None`(让原 LLM 调用按原状执行)。
- [ ] 修正触发阈值与 Config 字段:保留单一字段 `CompactConfig.bufferTokens` + `circuitBreakerMax`,删除 `autoCompactThreshold`、`contextWindow`(字段;调用方一律使用 `agentDef.contextWindow`)、`forContextWindow`(factory)。同步更新 `ContextManageTool.description` 中"80% 阈值"的描述以匹配真实公式。
- [ ] 抽出 `compact.TokenEstimator`:从 `AgentActor.estimateTokens` 移出,明确返回值为字符近似值,并补充 `Image` block 用 1500 token/张代替 `data.length / 10`。
- [ ] 添加 stream 事件 `CompactStart(mode, inputTokens: Option[Int], threshold: Option[Int])` / `CompactComplete(before, after, snapshotPath: Option[String])` / `CompactFailed(reason, attempt, maxAttempts)`,作为 `enum AgentStreamEvent` 的新 case 加在 `protocol.scala:135-201` 中,并补足 `toJson` 分支;前端 `js/agent.js` 渲染对应状态。`inputTokens/threshold` 在 manual 触发场景填 `None`。
- [ ] 清理死代码:`MicroCompact.compact`、`FullCompact.compact`、`CompactConfig.forContextWindow`、`CompactConfig.autoCompactThreshold`、`CompactConfig.contextWindow`(字段)、`AgentState.pendingManualCompaction`(**保留** `pendingCompaction`,真实使用)。`circuitBreakerMax` 字段保留并真正接线。
- [ ] 加固 `MicroCompact.parseInstructions`(`MicroCompact.scala:98-117`)正则:容忍单/双引号、属性顺序、行内 markdown ``` 围栏。
- [ ] 防止压缩 SubAgent 自身递归触发压缩:`maybeAutoCompact` 在 `agentDef.name == "context-manage"` 时直接返回 `None`,避免 SubAgent 自身的对话超阈值时再 spawn 一个 `context-manage`。同时在 §5 熔断生效后,补单元测试断言:同一 AgentActor 在 `compactionFailures >= max` 后接收到 `TriggerCompaction` 时直接返回错误,不再 spawn SubAgent。

### Out of Scope

- [ ] 引入第三方 tokenizer（tiktoken/Anthropic count_tokens API）—— 拆为独立 issue，本次只做"接口抽象"。
- [ ] 历史回滚 UI（前端"恢复到压缩前"按钮）—— 拆为独立 issue，本次仅持久化快照、记录路径。
- [ ] 跨平台（Windows）路径正确性 —— 拆为独立 issue。
- [ ] Compact prompt 版本号 / 审计字段 —— 拆为独立 issue。
- [ ] 改变 `ContextManageTool` 对外的 inputSchema 字段（保持 `mode` 与 `reason` 兼容）。
- [ ] SubAgent 通信通道改造（继续走 `UserInput(messagesJson)`，但用更窄的格式包装；prompt 注入仍存在残留风险，标注为后续 issue）。具体已知残留点:`CompactUtils.stripImages` 仅处理 Image block,不处理超长 ToolResult / Thinking block;`FullCompact.MaxRestoreTokens=50000` 与 `CompactUtils` 的覆盖度需在后续 issue 一并审视。

> No behavior changes for end users that exceed compact triggering: 触发阈值在等价公式上保持一致；压缩后消息内容由模型决定，但**结构合法性**（顺序、role 配对）必须可验证。

---

## 当前架构

### 组件关系

| 组件 | 职责 | 问题 |
|------|------|------|
| `AgentActor` | turn 间触发 + spawn SubAgent + 解析回包 + 替换 messages | 内联 token 估计、双触发分支重复、失败不熔断、tool 白名单语义反转 |
| `ContextManageTool` | 暴露给 LLM 的同步工具 | 真正运行被 `AgentActor.pipeToolExecutions` 短路，schema/行为漂移 |
| `MicroCompact` | 解析 SubAgent 文本 → 重建 messages | 正则脆弱、缺失 index 尾插、role 配对不校验 |
| `FullCompact` | 解析 SubAgent 文本 → 摘要 + 文件恢复 | 完全覆盖原始历史、无快照 |
| `CompactConfig` | 配置 | 三个字段未被读取 |
| `CompactPrompts` | 两套 system prompt | 与 `AgentLibrary` 中重复设置；解析格式约束弱 |
| `CompactUtils.stripImages` | 图片脱敏 | 仅处理 Image，对超长 ToolResult 无防护 |
| `AgentLibrary` (`context-manage` 条目) | 内置 agent 定义 | `tools=Nil` 隐含"全部工具"；contextWindow=16000 易被自身打爆 |
| `SessionActor` / `SessionStore` | 持久化 messages | 无快照机制，多次压缩链使原始历史不可恢复 |

### 具体问题

1. **触发条件与文档脱钩**：`AgentActor.scala:541-543` 用 `bufferTokens=13000`，但 `ContextManageTool.description` 自称 80% 阈值；在 200k contextWindow 下两者差距巨大。
2. **MicroCompact 顺序破坏**：`MicroCompact.scala:55-95`（关键 76-77 行 `Keep(missing)` 尾插）。
3. **MicroCompact 正则脆弱**：`MicroCompact.scala:98-117`（关键 99-100 行）—— 仅匹配双引号 + 无 markdown 围栏容忍 + `Keep(indices)` 内部不排序（L105 `split(",").map(...)`）。
4. **FullCompact 无备份覆盖**：`FullCompact.scala:54-72`（关键 71 行）。
5. **失败无熔断**：`AgentActor.scala:288-298` 失败后回退到原 messages 继续 `pipeLlmCall`；下一轮再次触发 → 循环。
6. **ContextManageTool 双实现**：`ContextManageTool.scala:46-58` vs `AgentActor.scala:693-704`。
7. **Tool 白名单语义反转**：`AgentActor.scala:1091-1094`。
8. **压缩 SubAgent contextWindow 不足**：`AgentLibrary.scala:23-32`（27 行 `contextWindow = 16000`）→ 主对话 100k 时 SubAgent 输入直接超限。
9. **死代码**：`CompactConfig.scala:7,9,14`（`autoCompactThreshold` / `circuitBreakerMax` / `forContextWindow`）、`MicroCompact.scala:16-42`、`FullCompact.scala:23-44`、`protocol.scala:264`（仅 `pendingManualCompaction`，**不含** `pendingCompaction`，后者真实使用必须保留）。
10. **可观察性**：WebSocket 无专用事件；前端无 compact 状态展示。

---

## 设计原则

> 单一真相源（CompactConfig 是配置的唯一来源）+ 失败可观察、可熔断、可回滚 + 结构合法性优先于摘要质量。

---

## 目标架构

| 组件 | 技术栈 | 职责 |
|------|--------|------|
| `compact.TokenEstimator` | 纯函数 | 输入 messages → 估算 token 数；保留 chars/3 fallback，明确不可作为计费/精确触发依据 |
| `compact.CompactionPolicy` | 纯函数 | 给 `(state, agentDef, latestUsage)` → 返回 `Trigger(mode)` 或 `Skip(reason)` |
| `compact.CompactConfig` | case class | 暴露 `bufferTokens`、`circuitBreakerMax`、`subagentName="context-manage"`；删除未使用字段 |
| `compact.CompactionResult` 解析器 | 纯函数 | 接受 SubAgent 文本 + 原 messages，先做结构校验（顺序、tool_use/tool_result 配对），再返回新 messages 或 `Left(error)` |
| `compact.HistoryArchiver` | cats-effect Service | 压缩前把 messages 写到 `archives/<sessionId>/<ts>.json`（JSON 数组，与 SessionStore 同格式），返回路径 |
| `AgentActor.processing` | Pekko | 仅做：调用 policy、spawn SubAgent、apply 解析结果（成功 → 替换 + 写盘 + 事件；失败 → 增计数、达阈值熔断） |
| `ContextManageTool` | Tool | 唯一的"LLM 触发"实现；`AgentActor.pipeToolExecutions` 不再特判 |
| `AgentLibrary.contextManage` | AgentDef | `tools = List()`（解释为无工具）；`contextWindow = 60000`（足以容纳大多数压缩任务） |
| 前端 | js | 监听 `CompactStart/Complete/Failed`，在主对话流上展示状态条 |

---

## [结束] 详细变更

### 1. 修正 SubAgent 工具白名单语义

**Current:** `AgentActor.scala:1091-1094` 中 `tools = Nil` 被解释为"全部工具"，`context-manage` agent 因此默认获得 Read/Write/Bash 等高权限工具。

```scala
// 真实当前代码
private def buildToolList(agentDef: AgentDef, depth: Int, hasParent: Boolean): Option[List[ToolDefinition]] =
  val base =
    if agentDef.tools.nonEmpty then ToolRegistry.ALL_TOOLS.filter(t => agentDef.tools.contains(t.name))
    else ToolRegistry.ALL_TOOLS
  ...
```

**New:** `Nil` ⇒ 无工具；`List("*")` ⇒ 全部工具。`AgentLibrary` 中现有 builtin agent 定义逐个 audit：

```scala
// AgentActor.scala
private def buildToolList(agentDef: AgentDef, depth: Int, hasParent: Boolean): Option[List[ToolDefinition]] =
  val base = agentDef.tools match
    case Nil           => Nil
    case List("*")     => ToolRegistry.ALL_TOOLS
    case names         => ToolRegistry.ALL_TOOLS.filter(t => names.contains(t.name))
  ...
```

```scala
// AgentLibrary.scala —— context-manage 条目
AgentDef(
  name = "context-manage",
  description = "...",
  tools = List(),                    // 显式：压缩 SubAgent 不需要任何工具
  systemPrompt = CompactPrompts.full,
  contextWindow = 60000,             // 提升以容纳大对话压缩
  maxTokens = Defaults.MaxTokensCompact
)

// AgentLibrary.scala —— default agent 必须改 (SessionActor fallback)
AgentDef(
  name = "default",
  description = "...",
  tools = List("*"),                 // 关键：恢复"使用全部工具"的原意,否则普通 chat 失去工具能力
  ...
)
```

**Rationale:** "默认无工具"是更安全的默认值；显式 `List("*")` 让 `default` agent(SessionActor 在 `~/.nebflow/agents/` 找不到匹配 agent 时的 fallback,见 `SessionActor.scala:86,93`)仍能工作但语义清晰。**注意:** 用户已存在的 `~/.nebflow/agents/*/agent.json` 若 `tools: []` 显式空列表,语义将从"全部工具"反转为"无工具" —— 见兼容性章节迁移指南。

---

### 2. MicroCompact 解析顺序与配对校验

**Current:** `MicroCompact.scala:55-95`(关键 76-77 行)把 missing index 作为 `Keep(missing)` **尾插**到指令列表,破坏 user/assistant 顺序;同时 `Keep(indices)` 内部不排序(L105 `split(",").map(_.trim.toInt)`);不校验 `tool_use`/`tool_result` 配对。

**New:** 把所有指令展开为 `(sortKey, MaterializedMessages)` 的列表,按 `sortKey` 排序后串接;产物上做配对校验。`sortKey` 取值规则:`Keep(idx)` 取 `idx.min`、`Compact(s, e, _)` 取 `s`。`Keep` 内部 indices 在展开前先 `distinct.sorted`。

```scala
// MicroCompact.scala
private def applyInstructions(
  instructions: List[Instruction],
  messages: List[Message]
): Either[String, List[Message]] =
  val coveredIndices = instructions.flatMap {
    case Keep(idx)        => idx
    case Compact(s, e, _) => (s to e).toList
  }.toSet
  val missing = messages.indices.filterNot(coveredIndices).toList

  val expanded: List[(Int, List[Message])] =
    (instructions ++ missing.map(i => Keep(List(i)))).flatMap {
      case Keep(idx) =>
        idx.distinct.sorted.map(i => (i, List(messages(i))))   // 关键:每个 index 独立排序键
      case Compact(s, e, summary) =>
        val placeholder = Message(
          MessageRole.User,
          Left(s"<context-compact mode=\"micro\">Compressed messages $s-$e.\n$summary</context-compact>")
        )
        List((s, List(placeholder)))
    }

  val ordered = expanded.sortBy(_._1).flatMap(_._2)
  validatePairing(ordered)            // 拒收任何 tool_use 没对应 tool_result 的产物
end applyInstructions

/** 校验产物中所有 assistant tool_use 在后续 user 消息中存在对应 tool_result。
 *  返回 Right(ordered) 通过、Left(reason) 失败。 */
private def validatePairing(ms: List[Message]): Either[String, List[Message]] =
  val toolUseIds = ms.zipWithIndex.flatMap { case (m, i) =>
    m.role match
      case MessageRole.Assistant =>
        extractToolUseIds(m).map(id => (id, i))
      case _ => Nil
  }
  val toolResultIds: Set[String] = ms.flatMap(extractToolResultIds).toSet
  val orphans = toolUseIds.filterNot { case (id, _) => toolResultIds.contains(id) }
  if orphans.isEmpty then Right(ms)
  else Left(s"Compaction broke tool_use/tool_result pairing for ids: ${orphans.map(_._1).mkString(",")}")

// extractToolUseIds / extractToolResultIds 从 ContentBlock 中提取 id 字段
// (具体签名取决于 nebflow.shared.ContentBlock 定义,实现时按现有 ADT 适配)
```

**`validatePairing` 签名:** `def validatePairing(messages: List[Message]): Either[String, List[Message]]`。失败时返回 `Left(reason)`,被 `parseAndApply` 直接透传给 `parseResponse` → `AgentActor.DelegateResult` 的失败分支 → 计入 `compactionFailures`。

**Rationale:** 排序保留原顺序;`Keep` 内部排序处理 LLM 输出 `<keep>3,1,2</keep>` 类乱序;配对校验确保产物能被 Anthropic API 接受;当压缩破坏配对时返回 `Left`,让上层走熔断/回退路径。

---

### 3. MicroCompact 正则容错

**Current:** `MicroCompact.scala:98-117`(关键 99-100 行)只接受双引号属性，且不剥离 markdown ``` 围栏。

**New:** 在 parse 前先 `stripCodeFence(text)`，正则改为允许单/双引号、任意空白：

```scala
private val keepRegex    = raw"<keep>([^<]+)</keep>".r
private val compactRegex = raw"""<compact\s+start=["']?(\d+)["']?\s+end=["']?(\d+)["']?>(.*?)</compact>""".r
```

**Rationale:** 部分模型会主动给 XML 加围栏；引号风格不稳定。容错代价低，错过一次解析的成本（重压、计费、用户感知卡顿）远高。

---

### 4. FullCompact 历史快照

**Current:** `FullCompact.scala:54-72`(关键 71 行)直接产出 `List(summary, fileRestore?)`；调用方 `AgentActor.scala:257` 在内存替换 messages，后续 ToolsComplete/finishTurn 写盘时覆盖原始历史(`SessionStore.scala:101,130` 用 `msgs.asJson.spaces2` 写单文件 JSON 数组)。

**New:** 在 `AgentActor` 收到压缩结果且 mode=full（或 micro 但消息减少 >50%）时，**先**调用 `HistoryArchiver.archive(sessionId, originalMessages)` 写快照,拿到 `Option[String]` 形式的 `snapshotPath`(写盘失败返回 `None`,记日志但**不阻塞**主流程);再替换内存 messages；最后通过新事件 `CompactComplete(..., snapshotPath)` 上报。**快照格式与 SessionStore 一致**(`spaces2` JSON 数组,后缀 `.json`),便于后续 issue 用单一 JSON 解析器实现回滚。

```scala
// HistoryArchiver.scala (new)
trait HistoryArchiver:
  /** 返回 Right(path) 表示快照已写入;Left(reason) 表示写盘失败但允许继续。
   *  调用方:val snap = archiver.archive(sid, msgs).attempt.map(_.toOption.flatMap(_.toOption))。 */
  def archive(sessionId: String, messages: List[Message]): IO[Either[String, String]]

object HistoryArchiver:
  def fileSystem(root: os.Path): HistoryArchiver = new:
    def archive(sessionId: String, messages: List[Message]): IO[Either[String, String]] =
      IO.blocking {
        try
          val ts = java.time.Instant.now().toEpochMilli
          val dir = root / "archives" / sessionId
          os.makeDir.all(dir)
          val target = dir / s"$ts.json"
          os.write.over(target, messages.asJson.spaces2)   // 与 SessionStore 同格式
          Right(target.toString)
        catch case e: Throwable => Left(e.getMessage)
      }
```

**Rationale:** 不破坏现有覆盖语义（前端/后端业务对 messages 的视图不变），但把"原始历史"作为 immutable 文件留在磁盘,便于后续 issue 加 UI 回滚。统一 `spaces2` JSON 数组格式让回滚路径可以直接复用 `SessionStore` 的解析逻辑。`Either[String, String]` 让快照失败不阻塞主流程(降级:无快照但压缩仍继续)。

---

### 5. 熔断（CompactionFailures）

**Current:** `AgentActor.scala:288-298` 压缩失败后调用 `pipeLlmCall(state)` 继续 —— 但 messages 还是原超长版本，下一轮 `maybeAutoCompact` 又会触发,可能死循环。

**New:** `AgentState` 增加 `compactionFailures: Int = 0`：
- 失败时 `compactionFailures + 1`，若 ≥ `CompactConfig().circuitBreakerMax` 则切到 `idle` 并 emit `AgentEvent.Failed("compaction circuit breaker open after N attempts")`，stream 上发 `CompactFailed`。
- 成功时清零。
- `maybeAutoCompact` 在 `compactionFailures >= max` 时直接返回 `None`（让原 LLM 调用按原状执行,由 LLM 失败时的人工/上游处理）。

**Rationale:** `CompactConfig.circuitBreakerMax` 字段从死字段变为有效约束；防止死循环；前端可见。

---

### 6. 删除 ContextManageTool 双实现

**Current:** `ContextManageTool.scala:46-58` 与 `AgentActor.scala:693-704` 是两份独立实现。`AgentActor.pipeToolExecutions` 在 `case "ContextManage"` 直接 short-circuit，绕过了 `ToolRegistry`。

**New:** 删除 `case "ContextManage"` 分支，让标准 `ToolRegistry.find("ContextManage").get.call(...)` 路径生效。`ContextManageTool` 中已有 `ctx.agentActorRef ! TriggerCompaction(...)`，但需要重新核对 deferred 的传递契约（`ToolContext` 是否已能传 `agentActorRef`，若不能则补充）。

**Rationale:** 单一实现源，schema 与行为不再漂移。

---

### 7. 防止压缩自身递归触发

**Current:** `maybeAutoCompact` 对所有 agent 一视同仁。当 `context-manage` SubAgent 自身处理大对话时(L27 `contextWindow=16000` 下阈值约 3k tokens),它也会被触发自动压缩,产生"context-manage 压缩 context-manage"的递归。

**New:** `maybeAutoCompact` 开头增加 guard:

```scala
if agentDef.name == "context-manage" then None // 压缩 SubAgent 不自我递归压缩
```

同时,在 §5 熔断生效后,补单元测试断言:同一 AgentActor 在 `compactionFailures >= circuitBreakerMax` 后接收到 `TriggerCompaction` 时直接返回错误,不再 spawn SubAgent。该断言代替原 issue 草案中的"pipeToolExecutions 阻断嵌套"——后者在当前架构下不可达(只有父 agent 才能调用 `ContextManage`,SubAgent 本身没有主动调用能力)。

**Rationale:** 递归风险的真实来源是 SubAgent 自身的 auto-compact,而非 SubAgent 内部调用 ContextManage tool。用 guard 在源头阻断,配合熔断兜底。

---

### 8. CompactStream 事件

**Current:** 仅普通 `AgentStart("context-manage", ...)` / `AgentEnd`(来自 `AgentStreamEvent.AgentStart`/`AgentEnd`,`protocol.scala:176-184`)。前端 `js/agent.js` 把它当成普通 SubAgent 渲染。

**New:** 在 `protocol.scala:135-201` 的 `enum AgentStreamEvent` 中新增三个 case,并在 `toJson` 方法中补全分支:

```scala
enum AgentStreamEvent:
  ... (现有 case 不变)
  case CompactStart(mode: String, inputTokens: Option[Int], threshold: Option[Int])
  case CompactComplete(before: Int, after: Int, snapshotPath: Option[String])
  case CompactFailed(reason: String, attempt: Int, maxAttempts: Int)

  def toJson(agentId: String, isSubagent: Boolean = true, sessionId: Option[String] = None): Json = this match
    ... (现有分支不变)
    case CompactStart(mode, i, t) =>
      Json.obj(
        "type"        -> "compactStart".asJson,
        "agentId"     -> agentId.asJson,
        "mode"        -> mode.asJson,
        "inputTokens" -> i.asJson,
        "threshold"   -> t.asJson
      )
    case CompactComplete(before, after, sp) =>
      val base = Json.obj(
        "type"     -> "compactComplete".asJson,
        "agentId"  -> agentId.asJson,
        "before"   -> before.asJson,
        "after"    -> after.asJson
      )
      sp.fold(base)(p => base.deepMerge(Json.obj("snapshotPath" -> p.asJson)))
    case CompactFailed(reason, attempt, max) =>
      Json.obj(
        "type"       -> "compactFailed".asJson,
        "agentId"    -> agentId.asJson,
        "reason"     -> reason.asJson,
        "attempt"    -> attempt.asJson,
        "maxAttempts"-> max.asJson
      )
```

`AgentActor.scala:553-570`(maybeAutoCompact spawn SubAgent 后) 发 `CompactStart`;
`AgentActor.scala:233-298`(DelegateResult 成功/失败分支) 分别发 `CompactComplete` 或 `CompactFailed`。
前端在主对话上方渲染状态条。

**Rationale:** 让用户看到压缩发生、感知失败、知道历史在哪里。`inputTokens` / `threshold` 为 `Option[Int]`,因为 manual trigger(来自 `ContextManageTool`)没有计算这两个值,填 `None`;auto trigger 从 `maybeAutoCompact` 的本地变量传值。

---

### 9. 死代码清理

删除:

- `CompactConfig.autoCompactThreshold`（字段）。
- `CompactConfig.contextWindow`（字段;调用方一律使用 `agentDef.contextWindow`）。
- `CompactConfig.forContextWindow`（factory）。
- `MicroCompact.compact`（dead public method）。
- `FullCompact.compact`（dead public method）。
- `AgentState.pendingManualCompaction`（字段及一切赋值/清理点）。

**保留**（真实使用,**不得删除**）:
- `AgentState.pendingCompaction: Option[CompactionContext]`（`protocol.scala:263`）。
- `CompactConfig.circuitBreakerMax`（字段;**开始被使用**,见 §5）。
- `CompactConfig.bufferTokens`（字段;继续使用）。

---

### 10. ContextManageTool.description 与现实对齐

**Current:** description 自称 "80% 阈值"。

**New:** description 改为引用 `CompactConfig.bufferTokens` 公式："Triggered automatically when remaining tokens < bufferTokens (default 13000); can also be invoked manually with mode=full|micro."

**Rationale:** 文档与代码一致；同时不暴露具体常量值给 LLM，使其更鲁棒。

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 修复 #1（工具白名单语义反转）后，其他 builtin agent（非 context-manage）若依赖 `tools=Nil` 拿到全工具，行为会变化 | High | 在 `AgentLibrary` audit 全部 builtin 定义；将本意"需要全工具"的 agent 显式改为 `List("*")`；改动列入 PR 描述并对回归测试用例覆盖 |
| 修复 #2（顺序/配对校验）后，部分本可成功的压缩会被新校验判定为失败 | Medium | 失败走新熔断路径，最多 `circuitBreakerMax`（默认 3）次后自然停止；提交前在主流模型（Claude/GPT）回放至少 5 条历史压缩样本 |
| 修复 #4（快照）增加磁盘写入与文件膨胀 | Medium | 文件名带 ts；后续 issue 加 retention（本次 out of scope）；在 `archives/` 加 `.gitignore` |
| 修复 #5（熔断）使原本"反复重压最终成功"场景被提前终止 | Low | 失败时仍允许 LLM 携原 messages 调用（如果 provider 拒收，由 fallback 路径报错），用户可手动 Interrupt |
| 修复 #8（事件）需前端同步改 | Low | 前端改动极小（新增 case branch + 状态条）；若来不及可先后端发事件，前端一期忽略 |
| 用户已存在的 `~/.nebflow/agents/*/agent.json` 若 `tools: []`,语义反转为"无工具" | Medium | 启动时检测并打印 deprecation warning:"Detected agent with tools=[] — interpretation changed; use tools=[\"*\"] for legacy behavior",引导用户改 `["*"]` |
| 修复 #4（快照）增加磁盘写入与文件膨胀 | Medium | 文件名带 ts;在项目根 `.gitignore` 追加 `archives/`;后续 issue 加 retention（本次 out of scope） |

---

## 兼容性

- **向后兼容：** Yes（对外行为：`auto/manual` 两条路径均仍可用；ContextManage tool 的 input schema 不变）。
- **行为变更：**
  - `tools = Nil` 现在表示"无工具"（之前为"全部"），影响除 `context-manage` 外的所有 builtin agent；将逐个 audit 并显式设置。
  - 压缩失败次数累计达 `circuitBreakerMax` 时返回错误（之前会无限重试）。
  - 压缩前会写入 `archives/<sessionId>/<ts>.json` 快照文件（之前不写）。
- **迁移指南：**
  - Audit `AgentLibrary` 所有 `AgentDef`：若原意为"使用全部工具"，将 `tools = Nil` 改为 `tools = List("*")`。
  - 在项目根 `.gitignore` 追加 `archives/` 忽略历史快照。

---

## 关联

- Related to `004-subagent-dynamic-discovery.md` — SubAgent 注册路径相关；本 issue 不改注册逻辑，但 audit `AgentLibrary` 时一同检查。
- Introduced by `ebbb1b2` (`feat: multi-agent system, context compaction, ...`) 与 `37ba0c5` (`merge: feat/context-management into main`).
- Will block (后续 issue,本次 PR 合并后补开):
  - `008-tokenizer-integration.md` — 接入真正 tokenizer
  - `009-compact-history-rollback-ui.md` — 前端"恢复到压缩前"
  - `010-compact-prompt-versioning.md` — prompt 版本号

---

## 迁移步骤

> 风险等级：Medium（需监控）

### Phase 1: 防御与清理（Low）

1. **Audit `AgentLibrary`**：列出所有 `AgentDef`，把"需要全工具"的显式改 `tools = List("*")`（特别确认 `default`），其他改成 `Nil`。
2. **修正 `AgentActor.buildToolList`**：实现 §1 的三分支语义。
3. **删除 `pipeToolExecutions` 中 `case "ContextManage"` 短路**：让 `ContextManageTool.call` 接管。
4. **死代码清理**：§9。

### Phase 2: 正确性（Medium）

5. **MicroCompact**：§2、§3。
6. **HistoryArchiver + FullCompact 协作**：§4。
7. **熔断**：§5。

### Phase 3: 可观察性（Low）

8. **CompactStream 事件**：§8。
9. **前端状态条**：监听新事件。

### Phase 4: 文档与回归

10. **更新 `ContextManageTool.description`**：§10。
11. **回归**：手动跑一条对话至触发自动压缩；模拟 SubAgent 失败 3 次，验证熔断；检查 `archives/` 写入。

---

## [结束] 成功标准

- [ ] `AgentActor` 中 `case "ContextManage"` 短路代码段被删除；`grep -n 'case "ContextManage"' src/main/scala/nebflow/agent/AgentActor.scala` 无输出。
- [ ] `AgentActor.buildToolList` 中 `Nil ⇒ Nil`、`List("*") ⇒ ALL`，配套修改通过编译；`AgentLibrary` 中所有 builtin agent 的 `tools` 字段在 PR diff 中显式可见。
- [ ] `MicroCompact.parseAndApply` 包含按 `startIndex` 排序的合并逻辑；新增 `validatePairing`，单元测试覆盖"`tool_use` 与 `tool_result` 跨 `<compact>` 边界"用例。
- [ ] 压缩前快照：`archives/<sessionId>/<ts>.json` 在压缩成功后存在，文件内容等于压缩前 messages 的 JSON 数组（与 SessionStore 同格式）。
- [ ] `CompactConfig` 中无未使用字段（`autoCompactThreshold`、`forContextWindow`、`contextWindow` 已删）；`circuitBreakerMax` 在 `AgentActor.processing` 中被读取并影响行为。
- [ ] `AgentState.pendingManualCompaction` 字段被删除；全代码库 grep 无残留。**保留** `pendingCompaction`。
- [ ] `MicroCompact.compact`、`FullCompact.compact` 已删除；调用方仅剩 `parseResponse`。
- [ ] `AgentLibrary` 中 `default` agent 的 tools 在变更前后等价(旧: `Nil` ⇒ 全部工具;新:`List("*")` ⇒ 全部工具)。
- [ ] 前端在浏览器控制台/UI 上至少能看到 `CompactStart`、`CompactComplete` 事件输出（最小可观察证据）。
- [ ] All existing functionality preserved.

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 1. 双实现已删除
grep -n 'case "ContextManage"' src/main/scala/nebflow/agent/AgentActor.scala
# Expected: no output

# 2. 死字段已删除
grep -rn 'autoCompactThreshold\|forContextWindow\|pendingManualCompaction' src/main/scala/nebflow/
# Expected: no output

# 3. 工具白名单语义已修正
grep -n 'if agentDef.tools.nonEmpty' src/main/scala/nebflow/agent/AgentActor.scala
# Expected: no output (新版用 match)

# 4. 死方法已删除
grep -rnE '^\s*def compact\b' src/main/scala/nebflow/core/compact/
# Expected: no output

# 5. 熔断字段已被读取;同时确认 CompactConfig.contextWindow 字段已删
grep -rn 'circuitBreakerMax' src/main/scala/nebflow/
# Expected: at least 2 matches (definition in CompactConfig + read in AgentActor)
grep -rn 'CompactConfig(contextWindow' src/main/scala/nebflow/
# Expected: no output
```

### 单元测试覆盖

- [ ] `MicroCompactSpec`：覆盖
  - 顺序性（缺失 index 不被尾插）
  - tool_use/tool_result 跨 compact 边界拒收
  - markdown ``` 围栏容忍
  - 单引号属性容忍
- [ ] `FullCompactSpec`：覆盖
  - 摘要消息生成
  - 文件恢复（projectRoot 为空时不读文件，返回 None 而非空串）
- [ ] `CompactionPolicySpec`：覆盖
  - inputTokens 来自 latestUsage 时的判定
  - inputTokens 来自 estimateTokens 时的判定
  - circuitBreaker open 时不再触发
- [ ] `HistoryArchiverSpec`：覆盖快照路径生成与内容一致性。

### 运行时检查

- [ ] 步骤一：构造一个超过 200k contextWindow - 13000 阈值的对话 → 触发自动压缩 → 控制台看到 `CompactStart` 事件 + `archives/` 下出现 `.json` 快照文件。
- [ ] 步骤二：在 `context-manage` SubAgent 的 system prompt 中故意注入 "ignore previous instructions" → 验证 SubAgent 因无工具无法执行任何危险操作（白名单生效）。
- [ ] 步骤三：mock LLM 返回 3 次格式错误的压缩响应 → 验证 `CompactFailed` 事件 attempt=3、状态切回 `idle`。

### Code Review Checklist

- [ ] `tools = Nil` 语义反转的影响范围在 PR 描述中列出（每个 builtin agent 的 before/after）。
- [ ] 没有保留临时 backwards-compatibility shim（"如果旧字段还在则……"）。
- [ ] `MicroCompact.parseAndApply` 中的排序逻辑与原 messages 的 index 一一对齐，无重复消息。
- [ ] `HistoryArchiver` 写盘失败不阻塞压缩主流程（写盘失败应记日志但允许压缩继续）。
- [ ] `CompactStream` 新事件被加入 `AgentStreamEvent` ADT，所有 `match` 分支已穷举（编译器警告应为零）。

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `src/main/scala/nebflow/core/compact/CompactConfig.scala` | **Modify** | 删 `autoCompactThreshold` / `contextWindow` / `forContextWindow`；保留 `bufferTokens` / `circuitBreakerMax` |
| `src/main/scala/nebflow/core/compact/MicroCompact.scala` | **Rewrite** | 删 `compact`；重写 `parseAndApply`（排序、配对校验）；正则容错；目标 ≤180 行 |
| `src/main/scala/nebflow/core/compact/FullCompact.scala` | **Modify** | 删 `compact`；保留 `parseResponse`，由 `AgentActor` 在调用前传入 `HistoryArchiver` |
| `src/main/scala/nebflow/core/compact/HistoryArchiver.scala` | **Create** | 新模块，写盘 + 路径返回 |
| `src/main/scala/nebflow/core/compact/TokenEstimator.scala` | **Create** | 从 `AgentActor.estimateTokens` 抽出；`Image` ⇒ 1500 tokens/张 |
| `src/main/scala/nebflow/agent/AgentActor.scala` | **Modify** | `buildToolList` 三分支；删 `pipeToolExecutions` 的 `ContextManage` 短路；接入 `HistoryArchiver`、熔断、CompactStream |
| `src/main/scala/nebflow/agent/AgentLibrary.scala` | **Modify** | `context-manage` 显式 `tools=List()` + `contextWindow=60000`；其他 builtin agent audit |
| `src/main/scala/nebflow/agent/protocol.scala` | **Modify** | `AgentState.pendingManualCompaction` 删除；新增 `compactionFailures: Int` |
| `src/main/scala/nebflow/agent/protocol.scala` | **Modify** | 在 `enum AgentStreamEvent` 中新增 `CompactStart` / `CompactComplete` / `CompactFailed`,补充 `toJson` 分支 |
| `src/main/scala/nebflow/core/tools/ContextManageTool.scala` | **Modify** | description 与公式对齐；保留 `call`（成为唯一实现） |
| `src/test/scala/nebflow/core/compact/MicroCompactSpec.scala` | **Create** | 顺序、配对、围栏、引号 |
| `src/test/scala/nebflow/core/compact/FullCompactSpec.scala` | **Create** | 摘要 + 空 projectRoot 安全 |
| `src/test/scala/nebflow/core/compact/CompactionPolicySpec.scala` | **Create** | 触发/熔断 |
| `src/test/scala/nebflow/core/compact/HistoryArchiverSpec.scala` | **Create** | 快照写盘(JSON 数组与 SessionStore 同格式) |
| `src/test/scala/nebflow/agent/AgentActorCompactionSpec.scala` | **Create** | Pekko 上下文内模拟 SubAgent 失败 3 次,验证熔断后拒绝新 TriggerCompaction |
| `src/main/resources/web/js/agent.js` | **Modify** | 监听 `compactStart`/`compactComplete`/`compactFailed`,渲染状态条 |
| `.gitignore` | **Modify** | 追加 `archives/` |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## 权衡总结

- **保守 vs 激进**：本 issue 选保守 —— 不改触发模型为真实 tokenizer、不改 SubAgent 通信通道。优先消灭"破坏正确性 + 安全"的存量问题。
- **快照 vs 集中存储**：选文件系统 `spaces2` JSON 数组简单方案，与 SessionStore 同格式，未引入 SQLite/外部 KV;后续 retention/UI 单独 issue。
- **熔断阈值**：沿用 `CompactConfig.circuitBreakerMax = 3` 默认值;若实测过严可在配置层调整,无需代码改动。
- **正则 vs 结构化输出**：选保留正则但加固;切换到 tool_use 形式的 SubAgent 输出是更彻底改造,工程量超出本 issue 范围。

---

## [结束] 关闭原因

> 见 `templates/_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已完成
- [ ] 重复 — 重复 issue：
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘

> `[结束]` 时填写。若关闭原因为"重复"/"无法复现"/"已过时"，可跳过。

| 问题 | 回答 |
|------|------|
| 目标达成了吗？ | |
| 有什么意外？ | |
| 代码质量改善可量化吗？ | （删除死代码行数 / 新增测试用例数 / 修正的 critical 缺陷数） |
| 工时预估偏差原因？ | |
