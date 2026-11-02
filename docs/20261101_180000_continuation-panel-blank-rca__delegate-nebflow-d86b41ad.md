---
type: audit
status: final
date: 2026-11-01
topic: delegate 会话详情面板「部分会话正文空白」根因——Mail 续聊腿 wsSend=None，子会话流帧在发射处被吞，ai/tool/thinking 三类 ui.json 行永不落盘（只剩注入行 = 归因徽章行）
---

# 续聊会话详情面板正文空白 · 根因与修法（2026-11-01）

关联：作者报障「delegate 会话详情面板**部分会话**正文空白（只剩归因徽章行）」，三次纠正口径 ①这是 bug 不是缺功能 ②与 output 块掩码无关 ③徽章行能渲染、正文不渲染 ⇒ 死在取数或渲染环节。同类先例：bluebubble 批（注入行落盘收口，见 `AgentActor#emitInjectedUserEvent` 注释）与 [[20261101_125600_mobile-chat-blank-hotfix__delegate-nebflow-0df6b25c]]（同为「部分会话/部分界面空白」家族，判别变量各异）。证据目录 `evidence/20261101_continuation-panel-blank/`，判据脚本 `evidence/20261101_continuation-panel-blank/census.py`，复现/实拍件 `tests/continuation-panel-blank.spec.mjs`。

## 结论（判别变量）

**判别变量 = 该会话是否由 Mail 续聊腿起。** 不是会话 kind、不是历史文件结构、不是面板渲染分支。

- `MailTool.continueDelegate`（`src/main/scala/nebflow/core/tools/MailTool.scala:1352-1373`，改前行 `wsSend = None`）把调用方 `wsSend` 传成了 `None`；
- `DelegateTool.routeWsSend`（`src/main/scala/nebflow/core/tools/DelegateTool.scala:221-260`，底座退化行在 **229**：`val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)`）在 `None` 时把底座变成空函数 ⇒ **该子会话的全部流帧在发射处即被吞掉**：既没有 live 广播，也到不了根会话的录制层；
- ai / tool / thinking 三类 `.ui.json` 行**唯一**写者是录制层 `WebSocketRoutes#makeRecordingWsSend`（`WebSocketRoutes.scala:2779`，`agentTextDelta`/`agentThinking`/`agentToolEnd`/`agentDone` 四个分支按帧内 `nodeSessionId` 落盘，行号 2947/2960/2976/3018）；
- 注入行**不经 wsSend**：由 `AgentActor#emitInjectedUserEvent` 直写 `SessionStore.appendUiMessages`（`AgentActor.scala:866-871`）；
- 于是该会话 ui.json 只剩注入行 ⇒ 面板里「归因徽章行」（`DELEGATE · NEBULA` / `BACKGROUND · NEBULA · COMPLETED` 等注入气泡标签）照常渲染，正文（助手文本 / 工具过程 / 思考块）**一条都没有**。

同族腿对照（为什么只有续聊中招）：新鲜派发腿 `DelegateTool.spawnKernel(wsSend = wsSend)` 收到的是根会话的录制 send；外置执行器续聊腿 `ExternalSessionRunner.continueExternal(..., wsSend = ctx.wsSend, ...)`（`MailTool.scala:1305-1315` 同行内相邻分支）也传 `ctx.wsSend`——**只有内置执行器续聊腿传了 None**。

## 实盘普查（判别变量可证伪）

`census.py` 对 `~/.stand/subagent-tasks/*.json` 全量 delegate 任务行 × 各自 `.ui.json` 行类统计：

| continuation? | 有 ai/tool 行 | 计数 |
|---|---|---|
| 否 | 是 | **1021** |
| 否 | 否 | 4（failed/cancelled 零工作会话，无正文可失） |
| 是 | 否 | **105** |
| 是 | 是 | **0** |

判别变量 105/105 命中、零反例；标本会话 `delegate-nebflow-8bbf8cf7`（2026-11-01 16:02 起的续聊重飞批）transcript 361 条消息、1.5 MB 工作痕迹，ui.json 却只有 22 条注入行、零正文行。

## 修法（最小修）

`MailTool.continueDelegate` 的 `spawnBackground` 调用把 `wsSend = None` 改为 `wsSend = ctx.wsSend`（附归因注释）。桥通后 `routeWsSend` 照旧给每帧盖 `nodeSessionId = 新会话 id`，录制层按该 id 落盘，live 广播同时恢复——零新增参数、零新字段、零行为面扩大。

## 证伪与排除

- **与 output 块掩码无关**：本批零触碰 `outputTag.js` / `applyOutputFace` / `<output>` 语法面；改前的空白会话载荷里根本没有任何 ai 行可供 output 面处理。
- **渲染腿无罪**（用真实前端 + 改前真实载荷实拍）：`tests/continuation-panel-blank.spec.mjs` 场景 A 把实盘 ui.json（22 条注入行）原样喂给真实 `bgAgentPopup.openStepPopup` + `restoreFromBackendHistory`，结果 = 22 条注入徽章行渲染、正文零行——**渲染器对它的输入忠实**；场景 B 喂同一会话「修后应有」的载荷（注入行原文 + 由该会话真实 transcript 按录制层规则重投影的 305 条 ai/tool 行）后正文全量恢复。⇒ 断点在**取数腿**，不在渲染腿。
- **为什么只有部分会话**：续聊腿只在「向已结束的 delegate 发 Mail/续聊指令」时走；今天大量会话是 LLM 故障后的重飞续聊，所以「一部分空白一部分正常」同时可见。

## 验收证据

- 机制钉 + 防复活钉：`src/test/scala/nebflow/core/tools/DelegateContinuationWsSendSpec.scala`（S1 底座 None ⇒ 零收帧；S2 底座在场 ⇒ 帧盖 `nodeSessionId/sessionId/rootSessionId`；S3 既有覆盖契约不破；S4 源码钉：续聊腿禁止回退 `wsSend = None`）。
- 面板实拍：Chromium + WebKit × 深/浅双主题，改前（A 只剩徽章行）/ 改后（B 正文 + B2 顶部归因行 + B3 展开后的工具卡与思考块）/ 全程零 console.error、零 pageerror。图见 `evidence/20261101_continuation-panel-blank/shots/`。

## 开放项（本批不做）

1. 同类 `wsSend = None` 还有两处：`DeviceDelegate.scala:531`（对端设备 delegate：对端无控制台 viewer，语义上可接受，未动）与 `ProjectCrashRecovery.scala:338`（boot 恢复挂载 flow 宿主时传 None ⇒ 重启窗口的 **node-\\* 会话**可能同样只落注入行，需另批取证）。
2. 已产生的 105 条续聊会话正文**不可回溯补录**（帧从未落盘）：面板对它们仍是空白；新起的续聊会话即刻恢复正常。
3. 续聊会话在修复前连 live 广播也没有（同一处吞帧），修复后 live 串流一并恢复——如需「运行中也能看」的回归钉，可另批补一条 WS 帧级断言。
