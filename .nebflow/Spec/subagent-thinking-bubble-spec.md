# 子 agent thinking 气泡显示方案

> 版本：v1.0（2026-08-19） · 状态：待确认 · 问题：#327
> 约束：只写方案不碰代码 · 前端 QA 简化（用户自验）· 与主窗口 thinking 气泡样式一致

---

## 1. 结论摘要

**子 agent 窗口看不到 thinking 气泡，原因是三层数据链上 thinking 内容在每一层被丢弃/掏空：**

| 层 | 位置 | 行为 | 影响 |
|---|---|---|---|
| 后端发流 | `AgentCore.scala:1165` | `if (!isSubagent) thinkingBuf.append(delta)` — 子 agent 的 thinking 文本**从不入缓冲区** | thinking 文本到达后端即被丢弃 |
| 后端发流 | `AgentCore.scala:1142` + `protocol.scala:415` | flush 时发出 `AgentStreamEvent.Thinking` → JSON `agentThinking{agentId}` **无 delta 字段** | WS 帧不携带 thinking 内容 |
| 前端转换 | `ws.js:85` | `agentThinking` → `thinkingDelta{delta:''}` 空串 | 转换结果 delta 恒为空 |
| 前端渲染 | `main.js:340` + `chat.js:1930` | `appendThinkingDelta('')` → 创建 thinking 气泡但内容为空 | 弹窗即使显示也是空气泡 |
| 后端持久化 | `WebSocketRoutes.scala:3648`(`agentThinking` 无累积)、`:3657`(agentToolEnd)、`:3687`(agentDone) | `sessionThinkingBuffers` 从未为子 agent 累积/flush | 子 agent 的 ui.json 中的 `Ai` 消息 thinking=None，历史重开也无思维气泡 |

**核心心智模型**：主会话链路上 `ThinkingDelta(text)` → `thinkingDelta{delta}` → `appendThinkingDelta(delta)` 全程携带文本；子 agent 链路上 `ThinkingDelta(text)` → `AgentStreamEvent.Thinking`（丢弃 payload）→ `agentThinking{agentId}` → `thinkingDelta{delta:''}` → 空串拼接。

**修复方案**：打通这条链，同时在后端持久化 thinking 到子 agent 的 ui.json。前端零改动或极小改动（见 §3）。

---

## 2. 现状分析

### 2.1 主窗口 thinking 气泡渲染（工作正常）

完整链路：
```
LLM 流 → StreamChunk.ThinkingDelta(delta 携带文本)
  → AgentCore.streamEmitter (isSubagent=false, L1164-1165: thinkingBuf.append(delta))
  → flushThinking (L1144-1145: JSON thinkingDelta{delta})
  → WS → main.js onMessage('thinkingDelta') L317-341
  → state.sessionThinkingBuffers[sid] += delta（无论 view 都做）
  → if (view) appendThinkingDelta(delta) (L340) → chat.js L1928+
  → 创建 .row.ai.thinking-row > .bubble.ai.thinking-bubble > .thinking-label + .thinking-content
  → rAF 渲染 .thinking-content
```

关键：主会话的 `view` 不为 null（主 ChatView `visible=true`）。

### 2.2 子 agent popup 缺失的原因——三层断裂

**断裂点 A：后端丢弃 thinking 文本（AgentCore.scala:1164-1168）**

```scala
case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty && !isCompactTurn =>
  if !isSubagent then thinkingBuf.append(delta)   // ← 子 agent 不 append！
  thinkingCount += 1
  if thinkingCount >= MaxBatch || ... then flushAll()
```

子 agent 的 thinking 文本永远不进入 `thinkingBuf`。

**断裂点 B：flush 时发无 payload 的 agentThinking（AgentCore.scala:1138-1149 + protocol.scala:414-416）**

```scala
def flushThinking(): IO[Unit] =
  if thinkingCount == 0 then IO.unit
  else
    val json =
      if isSubagent then AgentStreamEvent.Thinking.toJson(...)  // ← 无 delta
      else val delta = thinkingBuf.toString; Json.obj(... "delta" -> delta)  // ← 主会话有 delta
```

`protocol.scala:415`: `if isSubagent then Json.obj("type" -> "agentThinking", "agentId" -> agentId)` — **不序列化 thinking 文本**。

**断裂点 C：前端转换掏空 delta（ws.js:84-85）**

```js
case 'agentThinking':
  return { type: 'thinkingDelta', sessionId: sid, delta: '' };  // ← delta 恒为 ''
```

**断裂点 D：前端渲染空内容（chat.js:1930）**

即使过了 thinkingDelta 的守卫，`appendThinkingDelta('')` 也会创建气泡但内容用空串拼接 → 气泡永远为空。

**断裂点 E：后端持久化不落盘（WebSocket/HTTP 记录——WebSocketRoutes.scala:3648-3655, 3657-3685, 3687-3726）**

- `agentThinking` 只记录 turnStart，不累积 `sessionThinkingBuffers`；
- `agentToolEnd` flush `sessionTextBuffers` → `UiMessage.Ai(text, ..., None)`，**thinking 参数恒 None**；
- `agentDone` flush `sessionTextBuffers` → `UiMessage.Ai(text, durationMs, model, None)`，同。

因此即使前端零改动，popup 打开调 `getHistory` 也拿不到 thinking——ui.json 里根本没有。

---

## 3. 改动方案

### 3.1 后端：让 subagent thinking 进入 WS 帧 + 持久化（核心改动）

**改 3.1.1 `AgentCore.scala:1164-1149` 的 streamEmitter**——子 agent thinking 也进入 buffer，flush 时带 delta：

```scala
case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty && !isCompactTurn =>
  // 子 agent 也累积 thinking 文本，flush 时携带 delta
  thinkingBuf.append(delta)
  thinkingCount += 1
  if thinkingCount >= MaxBatch || System.currentTimeMillis() - lastFlushMs >= FlushWindowMs then flushAll()
  else IO.unit
```

`flushThinking` 改为对 subagent 也发 `thinkingDelta` 语义（新增统一样式），不再发裸 `agentThinking`。有两种做法：
- 做法 A（推荐）：让 subagent 直接发 `thinkingDelta{sessionId,delta}` 标准事件——前端 convertAgentEvent 已能把 `agentX` 转为标准事件，但这里直接发标准事件即可，无需转换。需要带 `nodeSessionId` 以便 popup 路由。
- 做法 B：保持 `agentThinking` 事件类型，但携带 `delta` 字段，前端转换时透传 delta。

推荐做法 A（最小改动 + 复用前端标准 pipeline）：

```scala
def flushThinking(): IO[Unit] =
  if thinkingCount == 0 then IO.unit
  else
    val delta = thinkingBuf.toString
    thinkingBuf.setLength(0)
    thinkingCount = 0
    lastFlushMs = System.currentTimeMillis()
    // subagent 也发标准 thinkingDelta，sessionId=nodeSessionId（pipeline 复用）
    val json = if isSubagent then
      Json.obj("type" -> "thinkingDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
    else
      Json.obj("type" -> "thinkingDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
    wsSend(json)
```

简化后统一为一条 `thinkingDelta`。注意 `isSubagent` 时 `sessionId`（从 AgentCore 传入的 sessionIdOpt）在子 agent 场景就是 nodeSessionId（子 agent 的 session），前端 findViewBySessionId 能匹配到子 agent view。

同时需要在 WebSocketRoutes `makeRecordingWsSend` 中补 `sessionThinkingBuffers` 对子 agent 的累积。当前 `thinkingDelta` case（L3464）只处理主 sessionId 的累积，agent 发来的 `thinkingDelta` 会有 nodeSessionId。让我确认 WSR 的 recordBuffer 中的 sessionId 变量是啥——`makeRecordingWsSend(json)` 之外还有 wrap，把 text/thinking 显式处理？

后端实际持久化走的是 `recordBuffer`（上面 grep 到的 `sessionThinkingBuffers` 的 accumulate/modify 逻辑）。它按 `sessionId` 变量处理，而那个 sessionId 对 sub-agent 事件需要从 `nodeSessionId` 解析。让我搜索 `makeRecordingWsSend` 内部对 sessionId 的判断。

<｜DSML｜tool_calls>
<｜DSML｜invoke name="Read">
<｜DSML｜parameter name="file_path" string="true">/Users/dev/Claude code/Nebflow/src/main/scala/nebflow/gateway/WebSocketRoutes.scala