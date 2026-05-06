# Feature: `/ask` Slash Command — Isolated LLM Q&A

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。
>
> **升级规则**：见 `_glossary.md` → Quick Fix 升级规则。涉及行为变更时，关闭 quickfix，改用本模板。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | `backend`, `frontend` |
| 优先级 | P2-中 |
| 创建日期 | 2025-07-11 |
| 目标日期 | 未确定 |
| 预估工时 | 4h |
| 实际工时 | 未评估 |

---

## [创建] 一句话描述

新增 `/ask <question>` 斜杠命令，用户可针对当前对话内容向 LLM 发起一次性提问，回答直接渲染在聊天区域，但不修改 agent 的上下文消息列表。

---

## 背景（可选）

当前用户在阅读 LLM 回复时，如果想针对回复内容追问一个简单问题（如"这个函数的作用是什么？"、"用中文解释一下这段话"），必须通过正常输入发送消息。这会导致：
1. 用户的追问被写入 agent 的 `state.messages`，污染后续对话上下文
2. 增加不必要的 token 消耗
3. 追问的回答也会被持久化到上下文，影响 agent 的行为

已有的 `/clear`、`/thinking`、`/trust` 等斜杠命令均为控制类命令（不调用 LLM），本 issue 是第一个"调用 LLM 但不修改上下文"的斜杠命令。

---

## [创建] 动机

**为什么需要这个功能？**

- 用户场景：阅读 LLM 长回复时，想快速确认某个概念、翻译某段文字、要求简化解释，但不想让这些临时提问影响主对话流
- 如果不做，当前 workaround 是正常发送消息，但会导致上下文膨胀，agent 可能因临时追问而偏离原始任务
- 类似产品参考：Claude 的"Ask about this"浮层、ChatGPT 的临时对话

---

## [创建] 目标

1. 用户在输入框输入 `/ask <question>` 后，系统截取当前对话最近一轮 assistant 回复 + 用户问题，构造临时 prompt 调用 LLM
2. LLM 回答以独立的"询问"气泡渲染在聊天区域，视觉上与正常对话区分
3. 整个过程不修改 `AgentState.messages`，不影响 agent 的后续行为
4. `/ask` 命令在 agent 处理中（processing 状态）时不可用，避免并发冲突

---

## 优先级（可选）

| 属性 | 评估 |
|------|------|
| 优先级 | P2-中 |
| 目标版本 | 未确定 |
| 目标发布日期 | 未确定 |

---

## [创建] 范围

### In Scope

- [ ] 前端 `/ask` 斜杠命令注册、参数解析、自动补全提示
- [ ] 前端发送 `ask` 类型 WebSocket 消息
- [ ] 后端 `WebSocketRoutes.handleMessage` 新增 `"ask"` 消息处理分支
- [ ] 后端独立 LLM 调用逻辑（不经过 AgentActor 状态机）
- [ ] 前端独立的回答气泡渲染（与正常 AI 回复视觉区分）
- [ ] 流式响应支持（textDelta/done）

### Out of Scope

- [ ] 不支持 `/ask` 时附带附件
- [ ] 不支持 agent 正在处理时排队等待（直接提示不可用）
- [ ] 不持久化 ask 的回答到 UiMessage（刷新后消失）
- [ ] 不支持 ask 历史记录

---

## [创建] 需求

### 功能需求

- [ ] **F1** 用户输入 `/ask 什么是递归` 后发送，前端解析命令和参数
- [ ] **F2** 前端发送 `{ type: "ask", question: "什么是递归", sessionId: "..." }` WebSocket 消息
- [ ] **F3** 后端收到消息后，从 SessionStore 加载当前 session 最近 N 条消息（assistant 回复）作为上下文
- [ ] **F4** 后端构造临时 LLM 请求，使用精简 system prompt（不包含工具定义），直接调用 LLM
- [ ] **F5** LLM 回答通过 WebSocket 流式返回前端，使用专用事件类型 `askTextDelta` / `askDone`
- [ ] **F6** 前端渲染独立的 ask 回答气泡，带可区分的视觉样式（如左侧带"?"图标、浅色背景）
- [ ] **F7** 整个过程不修改 AgentActor 的状态和消息历史
- [ ] **F8** 如果 agent 当前处于 processing 状态，返回错误提示"Agent is busy, please wait"

### 非功能需求

- [ ] LLM 回答延迟与正常对话一致（流式）
- [ ] 不影响正常对话的性能
- [ ] 向后兼容：不支持 ask 的客户端发送 ask 消息时后端忽略即可

---

## 设计

### 整体架构

```
┌─────────────┐    WS: {type:"ask", ...}     ┌──────────────────────┐
│   Frontend   │ ───────────────────────────> │   WebSocketRoutes    │
│   input.js   │                              │   handleMessage()    │
│              │ <─── askTextDelta/askDone ── │       │              │
│   chat.js    │    (流式响应)                 │       ▼              │
└─────────────┘                              │  LLM 直接调用        │
                                             │  (不经过 AgentActor)  │
                                             │       │              │
                                             │       ▼              │
                                             │  SessionStore        │
                                             │  (只读最近消息)       │
                                             └──────────────────────┘
```

**关键设计决策：不经过 AgentActor**

`/ask` 的 LLM 调用直接在 `WebSocketRoutes.handleMessage` 的 IO 中完成，完全绕过 AgentActor 状态机。理由：
1. AgentActor 的 `idle`/`processing` 状态机是为了管理多轮对话的上下文和工具调用，`/ask` 不需要这些
2. 避免在 AgentActor 中引入"旁路"消息类型，增加状态机复杂度
3. 独立调用更简单，不会与正常对话流程产生竞争条件

### 接口/行为变更

- **新增接口**：
  - WebSocket 消息类型 `"ask"`（client → server）
  - WebSocket 事件类型 `"askTextDelta"`（server → client，流式文本）
  - WebSocket 事件类型 `"askDone"`（server → client，完成信号）
  - WebSocket 事件类型 `"askError"`（server → client，错误信号）
- **变更接口**：无
- **废弃接口**：无

### WebSocket 消息协议

**Client → Server**
```json
{
  "type": "ask",
  "question": "用中文解释一下上一条回复中的递归部分",
  "sessionId": "abc123"
}
```

**Server → Client（流式）**
```json
{ "type": "askTextDelta", "sessionId": "abc123", "delta": "递归是..." }
{ "type": "askTextDelta", "sessionId": "abc123", "delta": "一种函数..." }
{ "type": "askDone", "sessionId": "abc123" }
```

**Server → Client（错误）**
```json
{ "type": "askError", "sessionId": "abc123", "message": "Agent is busy, please wait" }
```

### 关键决策

| 决策点 | 选项 A | 选项 B | 选择 | 理由 |
|--------|--------|--------|------|------|
| LLM 调用位置 | 在 AgentActor 中新增旁路消息 | 在 WebSocketRoutes 中直接调用 | **B** | 避免状态机复杂化；ask 不需要工具/状态管理 |
| 上下文来源 | 从 AgentActor state 获取 | 从 SessionStore 只读获取 | **B** | 解耦，不依赖 agent 状态；ask 本身就是只读操作 |
| System prompt | 复用 agent 的 system prompt | 使用精简专用 prompt | **B** | 不需要工具能力；精简 prompt 节省 token；回答更聚焦 |
| 是否持久化 | 持久化到 UiMessage | 不持久化，仅前端渲染 | **B** | ask 是临时提问，不需要保留在历史中 |
| 并发控制 | 允许与 agent 同时 ask | agent busy 时禁止 ask | **B** | 避免同一 session 的 LLM 调用冲突；避免用户混淆 |

### 待决策项

- [x] 上下文窗口取多少条最近消息？→ 建议：最近 1 条 assistant 消息（即最新回复），保持简洁
- [x] 是否需要支持追问（多轮 ask）？→ v1 不支持，每次 ask 独立
- [ ] ask 专用的 system prompt 内容待细化

### 代码示例

**前端 `input.js` — 注册斜杠命令**
```javascript
'/ask': {
  desc: 'Ask a question about the latest response (does not affect context)',
  run: () => {
    // `/ask` without args: show hint
    renderSystemBubble('Usage: /ask <your question>');
  }
}
```

**前端 `send()` — 拦截 `/ask` 并提取参数**
```javascript
// In send(), before normal message handling:
if (text.startsWith('/ask ')) {
  const question = text.slice(5).trim();
  if (question) {
    sendWs({ type: 'ask', question, sessionId: state.activeSessionId });
    renderAskBubble(question);  // 渲染用户 ask 气泡
  }
  input.value = '';
  return;
}
```

**前端 `chat.js` — 渲染 ask 回答**
```javascript
function renderAskAnswer(delta) {
  // 复用 AI 气泡结构，但添加 ask 特殊样式
  if (!state.currentAskBubble) {
    const row = document.createElement('div');
    row.className = 'row ai ask-answer';
    state.currentAskBubble = document.createElement('div');
    state.currentAskBubble.className = 'bubble ai ask-bubble';
    // 添加 ask 标识头部
    state.currentAskBubble.innerHTML = '<div class="ask-header">💬 Ask</div><div class="ask-content"></div>';
    row.appendChild(state.currentAskBubble);
    state.dom.chat.appendChild(row);
  }
  state.currentAskBubble.querySelector('.ask-content').textContent += delta;
  smartScroll();
}
```

**后端 `WebSocketRoutes.scala` — ask 消息处理**
```scala
case "ask" =>
  val question = parse(text).flatMap(_.hcursor.downField("question").as[String]).getOrElse("")
  val askSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
  if question.nonEmpty && askSessionId.nonEmpty then
    // 检查 agent 是否 busy
    rootAgents.get.flatMap { agents =>
      agents.get(askSessionId) match
        case Some(_) =>
          // Agent exists — check if it's busy via sessionStore
          // (agent busy 由前端 state 管理，后端直接执行)
          executeAsk(askSessionId, question, wsSend)
        case None =>
          // Agent not yet created — still allow ask with session history
          executeAsk(askSessionId, question, wsSend)
    }
  else IO.unit

private def executeAsk(
  sessionId: String,
  question: String,
  wsSend: Json => IO[Unit]
): IO[Unit] =
  for
    // 1. 从 SessionStore 加载最近消息
    messages <- sharedResources.sessionStore.loadMessagesForSession(sessionId)
    // 2. 取最近一条 assistant 消息作为上下文
    lastAssistantMsg = messages.reverseIterator
      .find(_.role == MessageRole.Assistant)
      .map(_.textContent)
      .getOrElse("")
    // 3. 构造精简 prompt
    systemPrompt = "You are a helpful assistant. Answer the user's question about the provided content concisely."
    userContent = if lastAssistantMsg.nonEmpty then
      s"--- Latest assistant response ---\n$lastAssistantMsg\n\n--- End ---\n\nQuestion: $question"
    else
      s"Question: $question"
    request = LlmRequest(
      messages = List(
        Message(MessageRole.System, Left(systemPrompt)),
        Message(MessageRole.User, Left(userContent))
      ),
      sessionId = sessionId,
      agentId = "ask",
      tools = None,  // 不暴露工具
      maxTokens = Some(2048)
    )
    // 4. 流式调用 LLM
    _ <- sharedResources.llm
      .sendStream(request)
      .evalMap {
        case StreamChunk.TextDelta(delta) =>
          wsSend(Json.obj(
            "type" -> "askTextDelta".asJson,
            "sessionId" -> sessionId.asJson,
            "delta" -> delta.asJson
          ))
        case StreamChunk.Done(_, _, _) =>
          wsSend(Json.obj(
            "type" -> "askDone".asJson,
            "sessionId" -> sessionId.asJson
          ))
        case _ => IO.unit
      }
      .compile
      .drain
      .handleErrorWith { e =>
        wsSend(Json.obj(
          "type" -> "askError".asJson,
          "sessionId" -> sessionId.asJson,
          "message" -> s"Ask failed: ${e.getMessage.take(200)}".asJson
        ))
      }
  yield ()
```

**前端 `main.js` — 处理 ask 流式事件**
```javascript
// In WS message handler switch:
case 'askTextDelta':
  import('./chat.js').then(({ renderAskAnswer }) => renderAskAnswer(json.delta));
  break;
case 'askDone':
  state.currentAskBubble = null;  // 重置，允许下次 ask
  break;
case 'askError':
  import('./chat.js').then(({ renderSystemBubble }) => renderSystemBubble(json.message));
  state.currentAskBubble = null;
  break;
```

---

## [创建] 兼容性

- **向后兼容：** Yes — 不影响现有命令和消息流
- **迁移指南：** 无
- **废弃计划：** 无

---

## [结束] 成功标准

- [ ] 用户可通过 `/ask <question>` 针对最新回复提问并获得流式回答
- [ ] ask 的 LLM 调用不修改 agent 的 messages 列表（可通过日志/断言验证）
- [ ] ask 回答气泡与正常 AI 回复视觉可区分
- [ ] agent busy 时 ask 返回友好的错误提示
- [ ] 不影响正常对话的性能和功能

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| ask 调用与 agent 调用并发导致 provider 限流 | Medium | ask 复用 rateLimiter；ask 的 maxTokens 限制为 2048 |
| SessionStore.loadMessagesForSession 在 session 无 agent 时返回空 | Low | 无 assistant 消息时直接以问题调用 LLM，仍可回答一般性问题 |
| 前端 ask 气泡与正常 AI 气泡混淆 | Low | 专用 CSS class `.ask-bubble` + 标识头部 |

---

## [结束] 验证

### 功能验证

- [ ] 步骤一：发送一条正常消息，等待 LLM 回复完成
- [ ] 步骤二：输入 `/ask 用中文总结上面的回复`，确认流式回答出现
- [ ] 步骤三：发送下一条正常消息，确认 agent 上下文中不包含 ask 的问答
- [ ] 步骤四：在 agent 处理中执行 `/ask`，确认返回 "Agent is busy" 提示
- [ ] 步骤五：输入 `/ask`（无参数），确认显示用法提示

### 测试策略

- [ ] 单元测试覆盖 `executeAsk` 逻辑（prompt 构造、错误处理）
- [ ] 集成测试验证 ask 调用不修改 agent state

### 回归检查

- [ ] 现有斜杠命令（`/clear`, `/thinking`, `/trust`, `/new`）不受影响
- [ ] 正常对话流程不受影响

---

## 关联

- Related to `docs/issues/templates/feature.md` — 使用 feature 模板

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `WebSocketRoutes.scala` | **Modify** | 新增 `"ask"` 消息处理分支 + `executeAsk` 方法 |
| `input.js` | **Modify** | 注册 `/ask` 斜杠命令、拦截发送、参数解析 |
| `chat.js` | **Modify** | 新增 `renderAskBubble` / `renderAskAnswer` 函数 |
| `main.js` | **Modify** | WS 消息分发新增 `askTextDelta` / `askDone` / `askError` |
| `chat.css` | **Modify** | 新增 `.ask-bubble` / `.ask-answer` 样式 |
| `state.js` | **Modify** | 新增 `currentAskBubble` 状态 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已发布
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
