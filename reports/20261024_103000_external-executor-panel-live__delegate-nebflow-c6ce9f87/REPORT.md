# 外部执行器会话可视化（子智能体面板实时解析）· REPORT

- 时间：2026-10-24 09:10–10:4x（worktree 分支 `delegate/delegate-nebflow-c6ce9f87`）
- 任务（作者令 09:00 逐字）：外部执行器的动态不用终端打开来看，与内部执行器同权——实时解析 CLI 会话流到引擎既有 delegate 事件模型，复用子智能体面板渲染；交互与普通 delegate 一致（面板点开看细节）；可见性底线 = 至少「外部会话有事件流」，无结构化输出也要有退化方案而不是黑盒；历史落盘与内部同库可回看。

## 0. 结论一句话

外部 delegate 现在从 spawn 到终态的每一拍（开始/思考/工具调用与收尾/文本增量/token 用量/心跳/完成）都以引擎既有 WS 帧同契约推送到子智能体面板，点开体验与内部 delegate 一致，且顺带修复了今晨 codex 批「连续两轮空返回」的两个直接根因。

## 1. 侦察结论：三 CLI 输出模式（装机实证）

| CLI | 二进制 | 结构化模式 | 实证 |
|---|---|---|---|
| claude-code | /opt/homebrew/bin/claude | `-p <task> --output-format stream-json --verbose`（在册旗标） | 实跑拿到 system/init、assistant、result 行；今晨账号 429 限流至 10-28，成功流形态按 stream-json 文档+部分实流映射 |
| codex | /opt/homebrew/bin/codex | `codex exec --json --skip-git-repo-check <task>`（JSONL 事件流） | 实跑 exit 0：thread.started / item.started|completed(reasoning, command_execution, agent_message, error) / error（瞬态 "Reconnecting... 2/5"）/ turn.completed{usage} —— **无 Final 文本帧** |
| zcode | /Users/kaiyu/.stand/bin/zcode | 隐藏旗标 `--output-format stream-json`（0.16.9 bundle 白名单在册） | 实跑 exit 0，37 事件：model.streaming(kind=start/reasoning_start/**reasoning_delta**/reasoning_end/text_start/**text_delta**/text_end/finish) + turn.completed{response,usage} + 终局 `{type:"result",sessionId,response,usage}` |

旗标选型定稿：zcode 弃 `--json`（pretty 摘要模式 turn 期间 stdout 静默 = 黑盒，作者令禁止的形态），恒 stream-json；pretty 方言解析保留为兼容层。probe 捕获存 `/tmp/extvis/`（zcode-full.jsonl / codex-out.jsonl / claude2-out.jsonl，未入仓——内含请求头）。

## 2. 归一化事件模型映射表（一句话版）

CLI 事件 → 适配器帧（`ExternalExecutor.Frame` 七帧）→ 引擎 WS 帧（与内部 delegate 同一 `AgentStreamEvent` 序列化面）：Start→（续聊锚点落盘）/ Text→agentTextDelta / Thinking→agentThinking / Tool→agentToolStart / ToolEnd→agentToolEnd / Usage→agentDone.inputTokens|outputTokens / Final→终稿与失败判据；心跳 = runner 每 30s 发 agentToolHeartbeat（内部执行器同款）。

## 3. 落地面（全部在引擎侧，前端零改动）

### 3.1 适配器帧集扩四帧 + 三适配器重映射 — `ExternalExecutor.scala`

- 新帧：`ToolEnd(label)`（工具收尾——此前面板工具 tile 永久挂起）、`Thinking(delta)`（claude thinking 块 / codex reasoning item / zcode reasoning_delta → 思考区渲染）、`Usage(input,output)`（claude result.usage / codex turn.completed.usage / zcode result.usage → 终局 token 徽标）。
- claude-code：assistant thinking 块、user/tool_result → ToolEnd、result.usage → Usage、`system/api_retry` → 可见 Text（429 退避静默期面板有事件——卡住可感知）。
- codex：**`item.completed{type:error}` 与瞬态 `{"type":"error"}` 事件 → 可见 Text**（今晨假死疑案的直接观感来源——此前全被静默丢弃）；reasoning → Thinking；command_execution/mcp_tool_call 完成 → ToolEnd；turn.completed.usage → Usage。
- zcode：launchArgs 翻 stream-json（见 §1）；reasoning_delta → Thinking；tool.updated completed 系 → ToolEnd；result.usage → Usage。

### 3.2 Runner 四修 — `ExternalSessionRunner.scala`

- **codex 空返回修复（真 bug）**：成功路径 codex 不给 Final 帧，`settle` 终稿恒空 → 父会话收到 "(no text output)"。今晨两轮空回传根因之一。修复 = readLoop 记 `lastText`（流面最后一段助手文本），Final 帧缺席/空时兜底为终稿。
- **运行期心跳**：`heartbeatLoop` 每 `Defaults.ToolHeartbeatSec`(30s) 发 `agentToolHeartbeat`，进程死亡即停——外部 CLI 静默段（长推理/网络重试/代理等待）不再触发前端 630s 纯静默误杀与幻影流判定。
- **agentStart 归属三键**：补 `project/taskId/taskTitle`（与内部腿 `routeSubagentWsSend` 同契约）——外部 delegate 行的项目名/#任务号徽章与内部同渲染。
- **agentDone 带 token 徽标**：usage 随终局帧（`inputTokens/outputTokens`）。

### 3.3 可见性底线（退化路径）— `parseLineWithFallback`

纯函数契约：未解析行在「本 run 尚无结构化帧」档逐行 passthrough 为 Text 帧（行缓冲 tail 注入面板——无结构化 CLI 也绝不黑盒；单帧截 500 字符、累计 200 行洪水闸）；结构化帧出现后，仅当适配器显式声明 `passthroughUnparsed` 才继续（三既有适配器 JSONL 全程结构化，声明 false 防注噪）。未来接无结构化 CLI 时置 true 即获得底线。

### 3.4 前端零改动核验

`ws.js convertAgentEvent` / STREAM_MSG_TYPES 已全量覆盖 agentStart/agentTextDelta/agentThinking/agentToolStart|End/agentToolHeartbeat/agentDone/usageUpdate；`bgAgents.js` 行渲染对 `external-*` 前缀已有 kind 徽章且行点击 → bgAgentPopup 按 nodeSessionId 取历史；`.ui.json` 录制面（`makeRecordingWsSend`）按 nodeSessionId 键——外部帧三键同契约到达即落同一存储面。交互一致 = 渲染链路本就同权，缺的是事件，事件补齐即闭合。

## 4. 实测

- **真 CLI 流走查**（§1 表）：zcode 37 事件全解析映射正确；codex 成功流（含 12 条此前不可见的 error/reconnect 事件）全映射；claude 受账号限流（429 至 10-28），成功流映射按文档 + 部分实流（init/assistant/result 行形态实测一致）。
- **端到端 spec**：`ExternalSessionRunnerStreamSpec` —— 假 codex 二进制（行间 sleep 0.3s）过真 `spawn`：断言帧序 agentStart→agentThinking→agentToolStart→agentToolEnd→agentTextDelta→agentDone（增量性）、三键盖章、usage 徽标、SessionStore 历史（user+assistant 各一轮，assistant 文本 = "hello from fake cli"——空返回兜底生效）、DelegateRegistry cliSessionId 落盘。
- **面板可见**：帧面即 WS 契约面，sink 收集的 JSON 与前端消费的形状逐键一致（convertAgentEvent 入口契约）；未起整网关做浏览器目验（证据由契约一致性 + 端到端帧序列承担）。

## 5. Spec 结果

- `ExternalExecutorSpec` 扩 7 组：claude thinking/tool_result/usage/api_retry；codex ToolEnd/Usage/error 可见性；zcode stream-json reasoning/ToolEnd/result usage；`parseLineWithFallback` 四性质（结构化透传/退化档 passthrough/结构化后停注/洪水闸）。既有 codex/zcode 断言随新语义更新。
- 结果：**全绿**（`ExternalExecutorSpec` + `ExternalSessionRunnerStreamSpec` + 既有 executor 套件 ExternalSpawn/LegPrepare/OpenNative/OutputStore/Registry 无回归）。

## 6. 边界与未做

- 未改外部 CLI 本身 / 派单语义 / 鉴权（作者令边界）。
- claude 成功流映射未经装机实测（账号限流）——10-28 复位后可一条命令复核：`/opt/homebrew/bin/claude -p "Reply: OK" --output-format stream-json --verbose`。
- ToolEnd 的 label 无工具输出详情（CLI 流不携带），面板工具卡只落 label——与内部 delegate 的工具卡比信息量少，属 CLI 流面固有限制。
