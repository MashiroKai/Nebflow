> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# #308 消息窗口显示实际使用模型 — 实施方案

| | |
|---|---|
| 状态 | 待批准（v1.1 已交叉验证） |
| 日期 | 2026-08-19 |
| 关联 | #313（header 模型名移除 + 气泡 badge 恢复，已完成，本方案不动 header） |
| 范围 | 只改 badge 显示链路，不碰模型选择/fallback 逻辑 |

## 版本日志

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-08-19 | v1 | 初稿（Explorer 探索产出） |
| 2026-08-19 | v1.1 | 重派 Explorer 逐条交叉验证：v1 全部断言核实通过（含 ui.json 实证复查）。发现并修复关键遗漏 **F0**——`usageUpdate` 不在 ws.js 消息白名单（GLOBAL/TERMINAL/STREAM 三集合），sub-agent 的 usageUpdate（sessionId=nodeSessionId ≠ activeSessionId）在 `ws.js:385-389` 被静默丢弃，原 F1 的转发与 F2 的记录对 popup 场景不可达。补充 F0 后 F1/F2 才生效 |

---

## 1. 问题（用户原话）

> "我看到，消息窗户上显示的模型，是他们 preset 中位列第一的模型（preferred），而不是他们实际正在使用的模型。"

即：agent 因 fallback / 健康过滤等原因实际使用了 fallback 模型，但界面仍显示 preset 配置第一位的 preferred 模型。

## 2. 现状分析（探索结论）

### 2.1 模型信息的完整链路（后端是正确的）

**LLM 响应 → Done 事件：实际模型已全链路传递。**

1. `src/main/scala/nebflow/llm/interface.scala:329-337`（非流式）与 `:505-544`（流式）：`LlmMeta.model` / `StreamChunk.Done` 的 meta 取自**实际成功的 candidate**（fallback 后 `result.usedCandidate.model`；流式路径 `:519` 还会修正 providerId）。
2. `src/main/scala/nebflow/agent/AgentCore.scala:1224-1226`：`aggregateChunks` 从 Done chunk 的 meta 提取 `providerId/model` 写入 `ConsumeResult.model`。
3. `src/main/scala/nebflow/agent/AgentActor.scala:2386-2392`：turn 结束发 `Done(model.orElse(state.lastModel))`（`lastModel` 只由实际 meta 更新，protocol.scala:745 初始 None）。
4. `src/main/scala/nebflow/agent/protocol.scala:425-433`：`done`/`agentDone` 事件序列化 `model` 字段。
5. 前端 `main.js:712` `finishAi(durationMs, msg.model)` → `chat.js:681-689` 气泡 badge 渲染；`persistence.js:345/501/668/836` 历史恢复同源。

**实证**：`~/.nebflow/sessions/36636aba-….ui.json` 中 turn 末段 Ai 消息的 model 字段混合记录了 `zhipu/GLM-5.3`（preferred）、`deepseek/deepseek-v4-flash`、`107/deepseek-v4-pro`（fallback）——后端持久化的就是实际模型。

### 2.2 真正显示 preferred 的三处（bug 所在）

popup header / team tile 的模型 badge 调用 `GET /api/agents/:name/model`（`RestApiRoutes.scala:1424-1458`），返回 `preferred`（配置第一位字符串）与 `current`（健康过滤后的第一候选）。**三处前端代码都把 preferred 排在 current 之前**：

| 位置 | 代码 | 语义 |
|---|---|---|
| `src/main/resources/web/js/flowAgentPopup.js:429-436` | `const current = cfg.preferred \|\| cfg.current \|\| cfg.default`（注释明写 "trust preferred over current"） | flow agent 消息弹窗 header badge |
| `src/main/resources/web/js/bgAgentPopup.js:430-435` | 同上 | 后台子 agent（Delegate/SubTask）消息弹窗 header badge |
| `src/main/resources/web/js/flowTeams.js:241-247` | 同上（结果缓存于 `modelCache`） | Teams 面板每个 agent tile 的模型标签 |

这是与用户描述完全吻合的显示路径：**多个 agent 的消息窗口 header 全部显示 preset 第一位，即使该 agent 正在用 fallback 模型**。

另外两处现状（非本 bug，但方案需明确处置）：

- **主窗口/popup 内气泡 badge（turn 末段）**：已显示实际模型（`done.model`），**无需改动**。
- **气泡 badge（turn 中间轮次段）**：`toolCallDetected` 在流中途发出（AgentCore.scala:1180-1186），早于携带 meta 的 Done chunk，渲染时刻后端不可能知道该轮实际模型 → 中间段 badge 无 model 标签（只有时间戳）。**保持现状**，理由见 §5。

### 2.3 数据流图

![现状与改后对比](assets/actual-model-display-flow.svg)

## 3. 改动方案

核心思路：**popup header / tile 的 badge 改为「实际使用模型」优先**。数据源用前端已有的 `state.sessionModelInfo[sid].model`（该 session 最近一次实际使用的模型），并让它在**每轮 LLM round**（而非仅 turn 末）live 更新——由 `usageUpdate` 事件携带 model 字段实现。

### 3.1 后端（2 个文件）

**B1. `src/main/scala/nebflow/agent/protocol.scala`**
- `UsageUpdate` case class（:349-354）追加字段 `model: Option[String] = None`。
- `toJson`（:434-450）两个分支（subagent / 主会话）均序列化 `model`（`Some` 时 deepMerge，风格对齐 `Done` 的 `withModel` 写法 :429）。

**B2. `src/main/scala/nebflow/agent/AgentActor.scala`**
- LlmComplete 处理器中 `UsageUpdate(...)` 构造处（约 :1002）追加 `model = updatedState.lastModel`。此时 `lastModel` 已在 :989 更新为本轮实际模型（`result.model.orElse(state.lastModel)`），即每轮 round 结束都会广播该轮实际模型。

改动为**纯增量字段**：老前端忽略未知字段，事件结构向后兼容。

### 3.2 前端（6 个文件）

**F0. `src/main/resources/web/js/ws.js`（前提性改动，缺此则 F1/F2 对 popup 无效）**
- `STREAM_MSG_TYPES` 集合（:144-154）增加 `'usageUpdate'`。
- 原因：sub-agent 的 `usageUpdate` 事件（protocol.scala :437-443 subagent 分支）同时携带 `sessionId` 与 `nodeSessionId`（值相同 = nodeSessionId）。ws.js 入口过滤（:385-389）对不在 GLOBAL/TERMINAL/STREAM 任何一个集合里、且 `sessionId !== activeSessionId` 的消息直接 `return`——当前 `usageUpdate` 不在任何集合，因此 **sub-agent 的 usageUpdate 在进入 interceptor/convertAgentEvent 之前就被丢弃**，F1 的 model 转发与 F2 的 sessionModelInfo 记录对 popup（依赖 nodeSessionId 会话）完全不可达。
- 副作用评估：放行后**非活跃主会话**的 usageUpdate 也会进入 dispatch。main.js 的 usageUpdate handler（:627-641）仅写 `state.sessionModelInfo` + localStorage，DOM 更新有 `if (view)` 守卫——行为是增强（切回会话时模型信息已在），无破坏。
- 对照：主会话（用户当前查看）的 usageUpdate 因 `sessionId === activeSessionId` 本就通过过滤，不受影响；`agentDone`（无 sessionId 字段 + 在 STREAM 集合）本就可达——popup 的 turn 级刷新（done 路径）现在就通，F0 补的是**轮级**刷新。

**F1. `src/main/resources/web/js/ws.js`**
- `convertAgentEvent` 的 `usageUpdate` 分支（:94-95）转发 `model: msg.model`（子 agent popup 的 live 更新依赖此转换）。与 F0 同文件同 PR。

**F2. `src/main/resources/web/js/main.js`**
- `usageUpdate` 处理器（:627-641）：`model: state.sessionModelInfo[sid]?.model` 改为 `model: msg.model || state.sessionModelInfo[sid]?.model`，使 `sessionModelInfo[sid].model` 每轮刷新为实际模型（现有 localStorage 持久化逻辑不变）。

**F3. `src/main/resources/web/js/flowAgentPopup.js`**
- `fetchAgentModelBadge`（:420-438）数据源改为三级 fallback：
  1. `state.sessionModelInfo[currentStepId]?.model`（live 实际模型）
  2. `cfg.current`（健康解析的下一候选）
  3. `cfg.preferred`（配置兜底）
- 删除 "trust it over current" 注释，替换为本方案语义说明。
- **live 刷新**：利用 ws.js 多 handler 机制（ws.js:114），在 `done` 与 `usageUpdate` 事件上注册 popup 自己的 handler，`currentStepId` 匹配时重渲染 `#flow-agent-model` badge。实际模型 ≠ `cfg.preferred` 时加 `flow-agent-model-badge` 高亮（现有 fallback 样式类，:434 已有）。

**F4. `src/main/resources/web/js/bgAgentPopup.js`**
- `fetchAgentModelBadge`（:421-435）与 F3 相同改法，badge 元素 `#bgagent-model`，session key 同样是 `currentStepId`（= nodeSessionId）。

**F5. `src/main/resources/web/js/flowTeams.js`**
- tile 标签（:232-254）数据源改为：若 `a.sessionId` 且 `state.sessionModelInfo[a.sessionId]?.model` 存在则用它；否则 `cfg.current || cfg.preferred`。`modelCache` 缓存 key 不变，但 live 值更新时需失效（收到 usageUpdate/done 后清对应 agent 缓存或直接以 sessionModelInfo 覆盖显示）。

### 3.3 不改的东西（明确排除）

- 气泡 badge 的取值链（`done.model` / `m.model`）——已正确。
- Header 上下文用量区——#313 已定案，不恢复模型名。
- `GET /api/agents/:name/model` 的响应结构——不动后端 REST。
- 模型选择、fallback、健康过滤逻辑——只改显示。

## 4. 预期行为变更（用户视角）

**现在**：打开任意 agent 的消息弹窗（或看 Teams 面板 tile），header 上显示的模型永远是 preset 配置的第一位（如 GLM-5.3）。哪怕该 agent 因为 provider 故障已 fallback 到 deepseek-v4-flash，窗口上仍写着 GLM-5.3。

**改后**：
- 消息弹窗 header 显示**该 agent 最近一轮实际调用**的模型；agent 每完成一轮 LLM 调用，弹窗打开着也会自动更新。实际模型与配置的 preferred 不同时，badge 高亮提示（复用现有 fallback 样式）。
- agent 从未运行过（无任何 LLM 调用记录）时，显示后端健康解析的下一候选；两者都无才显示 preferred。信息缺失时宁可降级，不再显示"配置值冒充实际值"。
- 消息气泡上的模型 badge（turn 末段）保持现状——本来就显示实际模型。

## 5. P1 候选（本次不做）：turn 中间轮次段的气泡 model

中间段在 `toolCallDetected`（流中途）收尾渲染，此刻 Done chunk（唯一携带 meta 的事件）尚未产生，前端拿到的任何"模型"都只能是上一轮或配置值——**错误信息比没有更糟**。若未来要做，需后端重构事件时序（如延迟到 round 结束再发 toolCallDetected，或前端延迟收尾），影响面大，单独立项。

## 6. 验收条件

按项目约束：前端 QA 简化，渲染效果用户自验；以下为可自动化项。

### 后端

- [ ] **A1 编译**：`sbt compile` 通过。
- [ ] **A2 单测**：`sbt test` 全量通过（现有 29 个测试文件无回归）。
- [ ] **A3 UsageUpdate 序列化含 model**：新增/扩展 `src/test/scala/nebflow/agent/` 下测试（如 `PromptSectionsSpec` 旁新建 `UsageUpdateModelSpec`）：构造 `UsageUpdate(inputTokens=1, contextWindow=1000, compactThreshold=0.8, model=Some("zhipu/GLM-5.3"))`，断言 `toJson` 主会话与 subagent 两个分支的 JSON 均含 `"model":"zhipu/GLM-5.3"`；`model=None` 时不含 `model` 键。
- [ ] **A4 冒烟（硬性）**：`sbt run` 真实启动 → `curl -s http://localhost:8080/api/health` 返回 200 → 发起任意一次聊天 turn → 抓 WS 帧断言存在 `"type":"usageUpdate"` 且含非空 `model` 字段（可用 `websocat` 或测试页；无自动化 WS 工具时降级为：检查启动日志无异常 + A3 单测覆盖序列化，并在验收记录中注明降级原因）。
- [ ] **A5 兼容**：`done` 事件 JSON 结构与改前一致（model 字段位置/语义不变）——由 A2 现有测试 + `git diff` 审查确认。

### 前端

- [ ] **B1 静态资源**：`sbt run` 后 `curl -s http://localhost:8080/` 返回 200 且 HTML 非空；`curl -s http://localhost:8080/js/flowAgentPopup.js`、`/js/bgAgentPopup.js`、`/js/flowTeams.js`、`/js/main.js`、`/js/ws.js` 均返回 200 且包含本次改动标记（如注释 `// #308 actual model`）。
- [ ] **B1b ws.js 白名单（F0 落位检查）**：`curl -s http://localhost:8080/js/ws.js` 的 `STREAM_MSG_TYPES` 集合中包含 `'usageUpdate'`（grep 可断言：`grep -c "usageUpdate" js/ws.js` ≥ 2——一处在白名单、一处在 convertAgentEvent 分支）。缺此改动时 sub-agent 的 usageUpdate 被入口过滤丢弃，B2-2（popup 轮内刷新）必挂。
- [ ] **B2 用户自验（核心场景，用户操作）**：
  1. 打开一个 agent（如 flow/bg agent）消息弹窗，header badge 在首轮完成后显示实际模型（观察与 provider 后台实际调用模型一致）。
  2. 将该 agent 的 preferred 模型 provider 手动置为不可用（或等真实 fallback），触发一次 turn → badge 变为 fallback 模型且高亮。
  3. Teams 面板 tile 标签显示实际模型。
  4. 主窗口气泡 badge 行为与 #313 之后完全一致（无回归）。
- [ ] **B3 历史恢复**：刷新页面重开会话，弹窗打开后 badge 依旧显示实际模型（localStorage `sessionModelInfo` 持久化生效）。

## 7. 风险与回滚

| 风险 | 评估 | 缓解 |
|---|---|---|
| usageUpdate 加字段破坏老前端 | 低：JSON 增量字段，前端 `msg.model \|\|` 容错 | A3 断言 None 时不序列化键 |
| F0 白名单放行后事件量增加 | 低：usageUpdate 每 LLM round 仅 1 条（非流式 delta 频率）；main.js handler 是 O(1) state 写入 | 无需节流；如未来敏感可按 type+sid 去重 |
| F0 放行非活跃会话 usageUpdate 进 dispatch | 低：handler 仅写 state/localStorage，DOM 有 `if (view)` 守卫 | B2-4 回归项覆盖（主窗口行为不变） |
| popup badge live 刷新引入渲染抖动 | 低：badge 是单 span innerHTML 替换，无布局依赖 | 只在值变化时更新 DOM |
| `modelCache` 与 live 值不一致（flowTeams） | 中：缓存未失效会显示旧值 | F5 明确：sessionModelInfo 有值即覆盖，不依赖缓存失效 |
| localStorage 旧数据无 model 字段 | 无：`?.model` 链式取值，undefined 走 fallback | — |
| 改动波及面 | 小：后端 2 文件增量字段，前端 6 文件局部函数 | 回滚 = revert 单个 commit，无数据迁移 |

**回滚步骤**：`git revert <commit>` → `sbt compile` → 重启服务。无存储格式变化、无 API 破坏，单 commit 可安全回滚。

## 8. 实施提示（给 Manager）

- 派发顺序：B1+B2（后端，可独立验证 A1-A3）→ F0+F1（ws.js，事件管道打通）→ F2（main.js 记录）→ F3/F4/F5（三个 UI 点，可并行）→ 冒烟 A4/B1/B1b → 用户自验 B2/B3。
- 涉及文件行号基于 2026-08-19 `feat/a2a-gateway-integration` 分支（HEAD 4140f3fb），实施时以当前代码为准重新定位。
- v1.1 验证补充：F0 缺失时 B1+B2+F1 依然编译/单测全绿（序列化与转换代码都"正确"，只是事件到不了）——这就是它作为遗漏能存活的原因。派发时 B1b 是唯一能抓住它的自动化断言，不可省略。
