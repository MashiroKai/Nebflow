> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 消息截断问题排查报告（#308 Re-dispatch 通知空壳）

> 阶段文档 · 2026-08-19 · Explorer 出品 · 完成即冻结
> 现象：用户反馈 Delegate 子 agent 完成通知 `"Re-dispatch #308 actual model display spec (general)":` 在前端"被截断"。

## 0. 结论摘要（先读这个）

**这不是前端 CSS/JS 截断。** 前端收到的 WS 消息文本本身就只有 58 个字符：`"Re-dispatch #308 actual model display spec (general)":\n\n\n` —— 标题 + 冒号 + 3 个空行，**没有任何结果内容**。前端如实渲染了它，气泡因此看起来像被截断。

**真正的 bug 在后端**：Delegate/SubTask 完成通知的文本提取逻辑用 `text.nonEmpty`（非空判断）而不是 `text.trim.nonEmpty`（去空白判断）。当子 agent 最后一条 assistant 消息的文本块是纯空白（本例为 `"\n\n"`，两个换行符）时，`"\n\n".nonEmpty == true`，代码走了"有输出"分支，构造出 `"描述":\n` + 空白 的空壳 payload；本应走"无输出"分支输出 `"(no text output)"` 标记。

**证据链**（全部来自磁盘数据，非推测）：

| 环节 | 证据 | 位置 |
|---|---|---|
| 父会话持久化的通知原文 | `text = "\"Re-dispatch #308 actual model display spec (general)\":\n\n\n"`（58 字符） | `~/.nebflow/sessions/5cc7590a-...ui.json` 索引 728 |
| 子 agent 最后一条 assistant 消息 | `[Thinking(...), Text("\n\n")]` —— 文本块就是两个换行符 | `~/.nebflow/sessions/delegate-Explorer-b5bd9368.json` 索引 58 |
| 子 agent 会话元数据 | model=`107/deepseek-v4-flash`，duration=496613ms，结束于一次 Grep 正则错误后 | 同上 `.ui.json` 索引 58 |

## 1. 完整因果链

![消息截断因果链](assets/20260819_message-truncation-chain.svg)

1. **子 agent（delegate-Explorer-b5bd9368）** 被 re-dispatch 写 #308 方案（prompt 落盘于 10:09:06）。它在探索前端代码过程中一次 Grep 因正则未闭合分组报错。
2. **10:18:54 LLM 返回**：`text = "\n\n"`（纯空白）+ thinking 块，无工具调用。
3. **`AgentActor.scala:2049`**：分支条件 `result.text.nonEmpty || result.thinking.nonEmpty` 为 true（`"\n\n"` 非空），走 `finishTurn` 而不是 `handleEmptyResponse`（空回复重试）。**注意：空白文本在这里就溜过了"空回复"检测。**
4. **`AgentActor.scala:2083` finishTurn**：`assistantContent = Right(List(Thinking(t), Text("\n\n")))`，空白文本块被原样写入消息历史。
5. **`AgentActor.scala:2249`**：turn 结束，向 `replyTo`（BackoffSupervisor）发 `AgentEvent.Completed(_, messages)`。
6. **`BackoffSupervisor.scala:313-319` `extractLastAssistantText`**：取最后一条 assistant 的 `textContent` = `"\n\n"`。
7. **`BackoffSupervisor.scala:128-132`（BUG 所在）**：`if text.nonEmpty` → true → payload = `"描述":\n` + `"\n\n"` = `"Re-dispatch #308 actual model display spec (general)":\n\n\n`。else 分支的 `"(no text output)"` 标记永远不会出现。
8. **`AgentActor.scala` ExternalEvent 处理（:517-564，idle 态 :1712-1745）** → `emitInjectedUserEvent`（:186-211）→ WS `{type:"user", injected:true, source:"delegate", eventType:"completed", sender:"Explorer"}`。
9. **前端 `main.js:1755`** → `renderInjectedBubble` → `chat.js:413` `renderMarkdownWithMath(text)` 原样渲染 → 气泡显示标题 + 3 空行。
10. **用户视角**：消息在冒号处"断掉"，看起来像被截断。

## 2. 截断发生的具体位置（精确到行号）

### 2.1 核心 bug：三处同模式的 `nonEmpty` 判断

| # | 文件 | 行号 | 代码 |
|---|---|---|---|
| 1 | `src/main/scala/nebflow/agent/BackoffSupervisor.scala` | 130 | `if text.nonEmpty then s""""$description":\n$text"""` |
| 1b | 同上 | 313-319 | `extractLastAssistantText`：`.filter(_.nonEmpty).getOrElse("")` |
| 2 | `src/main/scala/nebflow/core/tools/DelegateTool.scala` | 502 | `if text.nonEmpty then notifyParentAndStop("completed", s"\"$description\":\n$text")` |
| 2b | 同上 | 674-680 | 同款 `extractLastAssistantText` |
| 3 | `src/main/scala/nebflow/core/tools/SubTaskTool.scala` | 379 | `if text.nonEmpty then notifyParentAndStop("completed", s""""$description":\n$text""")` |
| 3b | 同上 | 419-425 | 同款 `extractLastAssistantText` |

**#1（BackoffSupervisor）是当前 ephemeral Delegate 的实际路径**（`DelegateTool.scala:382-415` 用 BackoffSupervisor 替代了旧 backgroundAdapter；DelegateTool:463 的 backgroundAdapter 是遗留代码路径）。三处都要修。

### 2.2 上游放大器：空白文本绕过空回复重试

`src/main/scala/nebflow/agent/AgentActor.scala:2049`：

```scala
else if result.text.nonEmpty || result.thinking.nonEmpty then
  // → finishTurn（把 Text("\n\n") 存进历史）
else handleEmptyResponse(...)  // → 重试 / max-tokens / context-exceeded 处理
```

`"\n\n".nonEmpty == true`，所以纯空白文本被当成"有回复"，直接落历史。如果这里用 `trim` 判断，子 agent 会被重试（`MaxEmptyResponseRetries`），大概率拿到正常输出——但这是行为变更，见 §4 修复方案的可选项。

### 2.3 排除项：前端无截断（已逐一验证）

| 检查点 | 文件:行号 | 结论 |
|---|---|---|
| 注入气泡容器 | `chat.css:593-600` `.bubble` | 只有 `max-width:100%`，**无 max-height / line-clamp** |
| 注入气泡样式 | `chat.css:613-621` `.bubble.injected` | 仅配色，无尺寸限制 |
| 气泡内容渲染 | `chat.js:413` `content.innerHTML = renderMarkdownWithMath(text)` | 全文渲染，无 JS 截断 |
| 历史恢复 | `persistence.js:256` `buildInjectedRow(m.text...)` | 全文，无截断 |
| 持久化 | `SessionStore.scala:1224` `sanitizeForStorage` | 只截断 `UiMessage.Tool.content`，注入气泡是 `UiMessage.User`，**不受影响** |
| WebSocket 传输 | `WebSocketRoutes.scala` | 无消息大小限制（无 maxFrameSize 配置） |

**已存在但有明确用途、与本案无关的 CSS 截断**（修复时不要误伤）：
- `chat.css:944` `.tool-card .label` — `overflow:hidden; text-overflow:ellipsis`，无 `white-space:nowrap`，多行会换行，实际不触发
- `chat.css:352` `.bgagent-tool` — 子 agent 下拉里的当前工具名，单行省略（设计如此）
- `chat.css:420-426` `.bg-task-desc` — 子 agent 下拉任务描述，单行省略（设计如此）
- `chat.css:1524-1530` `.notif-text` — 顶部通知横幅，单行省略（设计如此）
- `flowCss.js:181` `.flow-mail-content` 未展开态 `max-height:3.6em` — Mailbox 折叠预览（设计如此）

## 3. 复现条件

**触发条件**（全部满足即复现）：
1. 用 Delegate（或 SubTask）派发一个后台子 agent；
2. 子 agent 的 LLM **最后一轮**返回：`text` 为纯空白（`"\n"` / `"\n\n"` / `" "` 等，非空但 trim 后为空）+ 可选 thinking，且无工具调用；
3. stopReason 正常（非 max_tokens / context 错误——那些路径有各自的兜底文案）。

**观测结果**：父会话注入气泡显示 `"任务描述":` 后全是空行；无 `(no text output)` 标记；子 agent 的真实产出（如有文件落盘）不会出现在通知里。

**实测易复现**：低能力/免费模型（本例 `107/deepseek-v4-flash`）在长任务中更容易产出"只有 thinking 没有正文"的收尾轮。同批 re-dispatch 的 #325（`Write spec for #325 Mail delivery tags`）就有正常文本（1111 字符），#308 是空壳——同一晚两个分支的差异证明这是 LLM 输出的偶发形态，不是必现。

**复现步骤（手工）**：
1. 在聊天里让 agent 执行一个会以空白文本收尾的 delegate（例如 prompt 末尾要求"最后一轮只输出空行"）；
2. 等待完成通知到达父会话；
3. 观察注入气泡：只有 `"描述":` + 空行。

## 4. 修复方案

### 方案 A（核心，必做）：三处 `nonEmpty` → `trim.nonEmpty`

**改动 1 — `BackoffSupervisor.scala:128-132`**（当前 Delegate 实际路径）：
```scala
// 改前
val text = extractLastAssistantText(messages)
val payload =
  if text.nonEmpty then s""""$description":\n$text"""
  else s""""$description" (no text output)"""
// 改后
val text = extractLastAssistantText(messages)
val payload =
  if text.trim.nonEmpty then s""""$description":\n${text.trim}"""
  else s""""$description" (no text output)"""
```

**改动 2 — `BackoffSupervisor.scala:313-319`**（提取器本身加 trim 防御）：
```scala
.filter(_.trim.nonEmpty)   // 原：.filter(_.nonEmpty)
```

**改动 3 — `DelegateTool.scala:502-503` 与 `:674-680`**：同模式修改（遗留 backgroundAdapter 路径 + persistentAdapter 路径 :651 也用同一提取器，一并覆盖）。

**改动 4 — `SubTaskTool.scala:379-385` 与 `:419-425`**：同模式修改。

**改动 5（可选加固）— `PlanAgent.scala:119/:145`**：同款 `extractLastAssistantText`，检查是否有同样判断，如有则一并修。

效果：空白收尾 → 通知变为 `"任务描述" (no text output)`，用户能明确看到"子 agent 没有产出文本"，不再像截断。

### 方案 B（可选，行为增强）：上游让空白文本触发重试

`AgentActor.scala:2049` 的 `result.text.nonEmpty` → `result.text.trim.nonEmpty`。这样纯空白 + 有 thinking 的回复会走 `handleEmptyResponse` 重试（最多 `MaxEmptyResponseRetries` 次），大概率直接拿到正常输出，用户看到的是真实结果而不是 "(no text output)"。

**风险**：某些模型正常输出以换行开头（如 markdown 空行首段），trim 判断会把它们也送进重试，多消耗 1-2 次 LLM 调用。若采纳，需观察 `empty-response` 日志频率；保守做法是只做方案 A。

### 方案 C（可选，前端美化）：注入气泡尾部空白裁剪

`chat.js:413`：`renderMarkdownWithMath((text || '').trim(), false)`。让 `"描述":\n\n\n` 这类文本不再渲染出 3 个空段落。**不解决根因**，仅视觉收敛，可与 A 叠加。

### 推荐组合

**A 必做**（最小、安全、直击根因）；**C 可做**（1 行，视觉收敛）；**B 需产品决策**（改变重试语义）。

## 5. 涉及文件清单

| 文件 | 改动 | 类型 |
|---|---|---|
| `src/main/scala/nebflow/agent/BackoffSupervisor.scala` | :128-132 判断改 trim；:313-319 过滤改 trim | Scala |
| `src/main/scala/nebflow/core/tools/DelegateTool.scala` | :502-503 判断改 trim；:674-680 过滤改 trim | Scala |
| `src/main/scala/nebflow/core/tools/SubTaskTool.scala` | :379-385 判断改 trim；:419-425 过滤改 trim | Scala |
| `src/test/scala/nebflow/agent/SubAgentTaskStatusSpec.scala`（或新建 spec） | 新增空白文本用例 | 测试 |
| （可选）`src/main/resources/web/js/chat.js` | :413 trim 输入 | 前端 |

## 6. 验收条件

1. **单元测试（新增 3 个用例，全绿）**：
   - BackoffSupervisor：`AgentEvent.Completed` 携带最后一条 assistant 消息 `[Thinking("x"), Text("\n\n")]` → 通知 payload 断言包含 `(no text output)`，且不含 `":\n\n\n"` 空壳形态；
   - DelegateToolSpec：同形态输入 → `notifyParentAndStop` 的 payload 同上断言；
   - SubTaskTool（现有 spec 或新建）：同形态 → 同断言。
2. **回归**：`sbt test` 全量通过（现有 273+ 用例零回归；既有 "有正常文本" 用例不受影响——payload 仍为 `"desc":\n正常文本`）。
3. **编译**：`sbt compile` 无警告新增。
4. **冒烟测试（硬性）**：`sbt run` 真实启动 → `curl http://localhost:8080/` 返回 200 且包含挂载点 → WebSocket 可连接（页面加载无 JS 错误）。
5. **端到端（手工或脚本）**：派发一个 prompt 要求"最后一轮只回复两个空行"的 Delegate → 父会话注入气泡显示 `"...描述..." (no text output)`，截图存档；对照组（正常文本收尾的 Delegate）气泡完整显示 `"描述":\n正常文本`。
6. **数据兼容**：历史会话中已存在的空壳消息（如 `5cc7590a` 的索引 728 条目）在恢复渲染时不报错（只读不迁移）。

## 7. 附：同批次兄弟任务对照

| 派发 | 通知文本长度 | 形态 |
|---|---|---|
| `Write spec for #325 Mail delivery tags` | 1111 字符 | `"描述":\n方案文件已就绪...`（正常） |
| `Re-dispatch #308 actual model display spec (general)` | **58 字符** | `"描述":\n\n\n`（空壳，本案） |

同代码路径、不同 LLM 输出形态 → 印证根因在"空白文本的分支选择"，而非传输或渲染。
