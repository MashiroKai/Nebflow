# AskUserQuestion pending 期间 agent 侧注入消息误消费为答案 —— P0 修复报告

- 日期：2026-09-03
- 分支：`askuser-guard`（基线 main@2d8dc9f1 + 上游 compaction 合并 bc95dec1，worktree 独占施工）
- 合并：本地 main `1f10246a`（--no-ff，零冲突）；未 push、未动 origin
- 关联：GitHub issue **#43**（critical，agent-reported）、作者 2026-09-03 12:03 截图

---

## 1. Issue 读取确认

`gh issue view 43`（MashiroKai/Nebflow，OPEN，labels: bug/agent-reported/critical）全文已读，与任务内联事实一致：

- 两问 AskUserQuestion pending 期间两个 delegate 结果 + 一个节点结果到达；
- Q1 答案槽 = delegate 汇报开头 token `"分层压缩提示词设计稿":`，Q2 = `任务完成。`；
- 用户手打真实回答只覆盖 Q1（经 Other 路径），Q2 变 skipped，工具返回载荷污染；
- 前端卡片把 delegate 结果渲染成答案内容（12.03.41 截图）。

issue 中的「疑似根因面」（InteractionHub / AgentActor 注入分支梯把 agent 消息当 userMessage 消费）**经排查不成立**——后端答案通道本来就是纯用户侧的。真实根因在前端 restore 链路，见下。

## 2. 根因定位（代码行证据 + 落盘实证）

### 2.1 三重实证取证

1. **事发会话 ui.json**（`~/.nebflow/sessions/5cc7590a-….ui.json`，UUID 与截图上传路径一致）`[763]-[766]`：
   - `[763]` askUser 两问（8 条决策点处置 / M1 启动时机）；
   - `[764][765]` `type=user, injected=true, source=delegate` 的 delegate 汇报气泡**紧跟**在 askUser 之后落盘（后端 emitInjectedUserEvent 记录，形态正确）；
   - `[766]` 用户投诉消息。
2. **后端会话历史 `.json` `[31][32]`**：AskUserQuestion 工具结果 = `Q1 → 如果 delegate 结果丢失，那更是严重的 bug`（用户补救文本）、`Q2 → (skipped)`。
3. **gateway 日志**（`~/.nebflow/logs/nebflow.log:13864-13871`）：
   ```
   12:04:05.255 Chat-input passthrough: answering pending AskUser requestId=13e28bec root=5cc7590a… (24 chars)
   12:04:05.259 User text (immediateInput) → AskUser passthrough for session 5cc7590a… (24 chars)
   ```
   24 字符 = Q1 槽里的用户补救文本。**用户最终回答走的是输入框直通**（按 2026-08-29 裁定行为正确），单槽答案 → Q2 空 → `(skipped)`。

### 2.2 后端排查结论：答案通道已是纯用户侧，排队机制已存在且正确

- **hub 答案入口只有两个**：`InteractionHubCommand.Answered`（gateway `WebSocketRoutes.scala:962-973`，仅由前端卡片 `askUserAnswer` 帧触发）与 `AnswerViaChatInput`（`WebSocketRoutes.scala:3628-3677 handleUserText`，仅输入框家族 source，rest-turn 已被 `probesPassthrough` 排除）。delegate 结果走 `parent ! ExternalEvent`，**从不进 hub**。
- **全代码无任何对 AskUser 答案 Deferred 的后端旁路 complete**（grep `.complete(`，AgentActor 中全部是 compaction/interrupt/restart 语义）。
- **排队机制已存在**：AskUser 工具在 turn 内阻塞（`AskUserQuestionTool.scala:184-208` 经 `agentRef.?` 等待 hub 回答），期间 actor 处于 processing 态；ExternalEvent 走 `AgentActor.scala:2294-2332` 的 processing 梯入 `pendingEvents`（`:2313` 追加 = 到达序），turn 恢复后由 `TurnBoundaryDrains.drainBarrier`（`:42-57`）按序注入。**「pending 期间排队、恢复后注入」本就成立，无需新队列**——本次新增 spec 将该既有契约钉死。

### 2.3 真实根因：前端 restore 链路把 agent 注入气泡当卡片答案（两处协同缺陷）

**缺陷① 答案提取不校验来源** —— `src/main/resources/web/js/persistence.js:175-178`（修复前）：

```js
function askUserAnswerText(msgs, i) {
  const nextMsg = msgs[i + 1];
  return (nextMsg && nextMsg.type === 'user' && nextMsg.text) ? nextMsg.text : null;
}
```

只看「下一条是 type:'user'」，**不排除 `injected:true`**。刷新/切回会话时 `restoreFromBackendHistory`/`restoreFromStorage` 走到此函数：`[764]` 注入气泡被判为已录答案 → `chat.js:2048-2076 markAnsweredPick` 按 `\n` 拆行填槽 → **Q1 槽 = `"分层压缩提示词设计稿":`、Q2 槽 = `任务完成。`、两问 Other 键选中、卡片锁死**（`chat.js:2013-2040 renderAskUserHistory` 无条件锁定）——与作者截图逐字/逐形态一致。

**缺陷② pending 判定被注入气泡打败** —— `src/main/resources/web/js/main.js:1489-1492`（修复前）：

```js
const lastHistMsg = histMsgs && histMsgs[histMsgs.length - 1];   // 字面最后一条
const isAskUserPending = lastHistMsg && lastHistMsg.type === 'askUser' && …
```

pending 的 askUser 后面排着注入气泡时 `isAskUserPending=false` → `main.js:1621-1627` 的交互卡片重建不触发 → 锁死的污染卡片留在界面上，**用户无卡可答** → 被迫走输入框 → 直通单槽消费 → Q2 skip。闭环完成。

> 事故链总结：agent 消息本身被正确排队（后端无过）→ 但它以 `type:'user', injected:true` 形态落盘/渲染 → restore 侧把它消费成了「用户已录答案」并锁卡 → 用户改道输入框直通 → 单槽答案 → 第二问被 skip。

## 3. 改动清单（文件/函数级）

| 文件 | 函数/位置 | 改动 |
|---|---|---|
| `src/main/resources/web/js/persistence.js` | `askUserAnswerText` | 答案来源校验：`!nextMsg.injected` —— 只有用户发起（非注入）的相邻 user 消息才算已录答案；injected 气泡相邻 = 卡片仍 pending。方向安全（#12：重复回答已消费的 requestId 被 hub 拒绝且保留槽位） |
| `src/main/resources/web/js/persistence.js` | `findLastRealMessage`（**新增导出**） | pending 判定用回看扫描：跳过 injected 气泡找最近真实条目。提为导出纯函数是为了 main.js 与 spec 同源（非顺手重构，是修复可测化） |
| `src/main/resources/web/js/main.js` | historyPage 处理器 pending 判定（原 `:1489-1492`） | 改用 `findLastRealMessage(histMsgs)`；注释写明 #43 机理 |
| `src/test/scala/nebflow/agent/AskUserPendingInjectionSpec.scala` | **新 spec**（6 test） | 后端契约：真实 hub actor + 生产 drain 决策，见 §5 |
| `tests/askuser-answer-source.spec.mjs` + `tests/fixtures/askuser-answer-source/{harness.html,incident-fixture.json,answered-fixture.json}` | **新 Playwright spec**（5 test） | 前端 restore 真实缺陷路径，双静态服务器 baseline vs fixed，见 §5 |

后端生产代码**零改动**（`InteractionHub.scala` 仅临时变异验红后原样还原，`git diff` 为空）。输入框直通、卡片点击两条用户路径行为零变化。

## 4. 排队机制设计取舍

- **复用既有机制，不新增队列**：后端「窗口期队列」已语义吻合——AskUser pending 期间 agent 处于 processing 态，ExternalEvent 入 `pendingEvents`（到达序追加），turn 恢复（用户应答 → turn 结束）后 `TurnBoundaryDrains.drainBarrier` 放行注入；delegate 结果还额外受批次屏障（outstanding>0 持有、归零整批）保护。作者要求「排队机制优先复用既有窗口期队列类机制」→ 成立，零新增。
- **前端不排队的理由**：注入气泡在 UI 上即时可见是任务 Q 的既定设计（接收时可见性，`AgentActor.scala:2316-2323`）；要修的是 restore 侧对「答案」的来源判定，不是把气泡藏起来。
- **兼容性说明（与压缩窗口期队列代码相邻面）**：`TurnBoundaryDrains.drainHead`（compaction 挂起时全持有）与 `drainBarrier`（批次持有）语义均未触碰；新 spec 的 (d) 组断言与既有 `SubAgentBarrierSpec` 逐条同构（同构造函数、同决策入口），无语义漂移。

## 5. 回归双向断言逐条结果（全部真实执行）

### sbt：`AskUserPendingInjectionSpec`（真实 InteractionHub actor + 生产 TurnBoundaryDrains）

- **(a) 验红基线**：变异 `InteractionHub.answerCompletes` 短路为 `true` + `complete()` 把无 `answers` 字段的 text 负载强转单槽答案（模拟「agent 消息被强转进答案槽」缺陷类）→ 跑 spec：
  ```
  12:55:40.022 INFO InteractionAnswered requestId=guard-b kind=AskUser approved=None answers=None
  AskUserPendingInjectionSpec.scala:102 assertEquals(polluted, None, "agent 消息负载不得填入答案槽（pending 必须保持）")
  + value = List(
  +   """"分层压缩提示词设计稿":
  ```
  **红证据 = 答案槽被 `分层压缩提示词设计稿` 污染（与事故 Q1 槽逐字一致），Failed: Total 6, Failed 3**；还原变异（`git diff --stat` 空）→ 复跑 **Passed: Total 6, Failed 0**。断言真实钉住来源门。
- **(b) 修复后绿**：`"(b) agent 消息形态的负载不完成 pending AskUser —— 答案槽保持 pending、卡片保留可答"` 绿（日志实证来源门拒绝：`InteractionAnswered dropped: answer shape does not match kind=AskUser … card RETAINED`）；`(b) 排队期间到达的 agent 消息内容完整 —— 注入载荷与原汇报逐字一致` 绿（drainBarrier 载荷一字不丢）。
- **(c) 用户路径回归**：`(c) 卡片点击回答：pending → agent 消息到达 → 用户点击仍解除 pending` 绿；`(c) 输入框直通回答：… 用户自由文本成为工具结果` 绿（含 `askUserAnswered via=chat-input` 广播断言）。
- **(d) 排队时序**：`(d) pending 期间多条 agent 消息到达 → turn 恢复后按到达顺序注入（FIFO，不丢不改序）` 绿；`(d) …批次屏障语义：outstanding>0 时持有、归零时整批注入` 绿。

### Playwright：`tests/askuser-answer-source.spec.mjs`（真实渲染模块 + 事故形态 fixture，baseline bc95dec1 vs 工作树）

- **(a) 验红基线**（pre-fix 代码上跑同一断言集，Pass = 缺陷如实复现）：事故 fixture 重放 → `.option-custom-input` Q1=`"分层压缩提示词设计稿":`、Q2=`任务完成。`、Other 选中×2、按钮全 disabled、`-> 分层压缩提示词设计稿…` 回显、`isAskUserPending=false` 不重建 —— **截图 `20260903-askuser-answer-source-1-polluted-baseline.png` 与作者 12:03 事故截图逐形态一致**。
- **(b) 修复后**：`isAskUserPending=true`、唯一交互卡片（按钮 enabled、确认键可见、无 `.option-answer`、槽位全空、零选中）、两条 delegate 注入气泡照常渲染（`.bubble.injected`）—— 截图 `20260903-askuser-answer-source-2-fixed-pending.png`。
- **(c) 用户路径回归**：已录答案 fixture（非 injected 相邻 user 消息）→ 锁定终态 `-> 部分调整, 重启后再启动`、预设键选中×2，还原逐字一致；活动卡片 live 作答 → 出站帧 `answers=['部分调整','重启后再启动']` 完整、卡片锁定。
- **(d) 时序**（前端面）：`findLastRealMessage` incident→askUser / answered→尾部 ai / 空→null（main.js 同源实现直测）。

**结果：5 passed (4.3s)**。既有相邻 spec `history-replay-cards.spec.mjs` 复跑 **2 passed**（persistence.js 改动无回归）。

## 6. 全量 sbt test（worktree 内前台一次跑完）

```
Passed: Total 2087, Failed 0, Errors 0, Passed 2087, Ignored 7
Total time: 464 s (0:07:44.0)
```

（上游基线 2081 + 本次新增 6 = 2087，全绿。）

## 7. 分支 / merge commit hash

- 分支 `askuser-guard` commit：
  - `3c551f76` fix(askuser): 答案来源校验——restore 不再把 agent 注入气泡当卡片答案
  - `2a251ade` test(askuser): 后端契约 spec——pending AskUser 答案来源校验 + 排队 FIFO
  - `5e4f2ddf` fix(askuser): pending 判定回看跳过注入气泡——刷新后 pending 卡片重建为可交互
- merge commit（本地 main，--no-ff，ort 零冲突）：**`1f10246a`**
- 未 push、未动 origin；主仓 status 干净（无他人文件域冲突，未触发等待重查）

## 8. 合并后主仓子集复跑

```
sbt "testOnly nebflow.agent.AskUserPendingInjectionSpec nebflow.agent.InteractionHubSpec
          nebflow.agent.SubAgentBarrierSpec nebflow.agent.AgentActorCompactionSpec
          nebflow.core.compact.CompactionProfileSpec"
Passed: Total 76, Failed 0, Errors 0, Passed 76   （44s）
```
（新 spec 6 + 交互/屏障/compaction 既有 70；未跑第二次全量。）

## 9. 清理确认

- `git worktree remove .nebflow/worktrees/askuser-guard` 完成（worktree list 已无此项）；
- `.nebflow/askuser-guard` 软链已 rm；
- `git branch -d askuser-guard` 完成（曾为 5e4f2ddf，历史经 --no-ff merge `1f10246a` 保全）；
- 其余 worktree（askuser-refresh / proj-archive-btn 等）属其他任务，未触碰。

## 10. 生效说明

改动已进本地 main。**运行时生效需重建 + 重启宿主（重启后：restore 链路用新判定；pending 期间的注入气泡行为不变，已本就正确）。本次未执行任何重启动作**，重启由 Nebula 统一安排。

## 11. 遗留问题

1. **`restoreFromStorage`（localStorage 切会话路径）无交互重建**：pending 卡在该路径下历史上就渲染为锁定空卡（与本次缺陷无关的既有缺口）；本次修复使槽位不再被污染，但未为其补交互重建——建议后续统一两条 restore 路径的 pending 重建。
2. **输入框直通单槽语义**：直通按设计把自由文本作为最旧 pending 卡的单槽答案（多问卡的其余槽 = skip）。本次事故中用户被迫改道输入框放大了这一面；卡片可答后暴露面收窄。若要「直通按行拆多槽」需作者另行裁定（有误拆风险，未动）。
3. **issue #43 建议中的「前端不渲染 agent 消息为答案」已由本修复覆盖；后端建议（答案来源校验）经排查本就成立**，本次以 spec 钉死契约，无需改动。
4. `main.js` 交互重建的移除查询 `.row.ai:has(.option-box)` 会移除历史中所有锁死 askUser 卡（含更早已答卡）——既有行为，未动。

---

# 补充报告：AskUser 刷新存活（2026-09-03 12:23 作者补充 case）

上游 #43 主体（答案来源校验）合并 main（`1f10246a`）后接续，分支 `askuser-refresh`，merge commit `7710190c`（--no-ff，零冲突，未 push）。工作区 `.nebflow/worktrees/askuser-refresh`，基线 main@bc95dec1，开工 `git merge main` 零冲突带入 #43 主体。

## 12. 现状盘点（刷新缺口根因，代码行证据）

刷新后 AskUser 卡片的恢复链与失效点，共三处缺口（协同）：

1. **requestId / agentName 不落盘**：`protocol.scala:340` `UiMessage.AskUser` 只序列化 `{type, items}` → 刷新后 historyPage 还原的卡片**无 requestId**。后果：a) `askUserAnswered{requestId}`（输入框直通关卡帧）找不到卡 → 卡片作答后**不锁定**；b) #12 的 requestId 精确路由退化到「同根会话最老卡」兜底（单卡可用，多卡错绑）。
2. **askUser 条目可被挤出首屏历史页**：`SessionStore.getHistoryPage(None)` 返回**最后 50 条**（WebSocketRoutes getHistory 处理器，limit=50）；pending ask 之后若有 ≥50 条注入事件（#43 事故形态：delegate 结果注入排在 ask 之后），刷新后 askUser 条目落在首屏页外 → `main.js` historyPage pending 检测（`findLastRealMessage`，#43 修复）无输入可用 → **卡片彻底消失**。
3. **输入框直通只挂在 busy→队列链上**：gateway 直通检查只在 `handleUserText`（WS `immediateInput`/`userMessage` 用例，WebSocketRoutes.scala:3669+）；浏览器 Enter 主发送走**类型缺失帧**（`{content, sessionId, …}`，input.js:713），该分支**没有任何直通检查**（原 3632-3644 直接 `AgentCommand.UserInput`）。活卡期间页面处于 busy → 输入进 `messageQueue` → 排空时走 `immediateInput` → 直通可达；**刷新后 busy 标志丢失** → 输入直走类型缺失帧 → agent 侧 `user-input-queued`（AgentActor.scala:2411）→ 卡片不锁、工具不返回。

服务端权威源本身健在：`InteractionHub.pending: Ref[IO, Map[requestId → PendingRequest]]`（含 payload/rootSessionId/kind/createdAt），刷新不丢；`RegisterRoot` 注册的 recordingWsSend 落 ui.json，卡片事件持久。缺的是**重连时的重发**与**类型缺失帧的直通**。

## 13. 改动清单（文件 / 函数级）

| 文件 | 改动 |
|---|---|
| `InteractionHub.scala` | 新增 `InteractionHubCommand.ListPendingAsks(rootSessionId, reply)`；新增 `handleListPendingAsks`（只读快照：按 rootSessionId+AskUser 过滤、createdAt 排序、`renderAskUser` 首帧同构载荷 + `replayed:true`）；`renderAskUser` 改为从 `InteractionRequest` 渲染的私有复用函数 |
| `WebSocketRoutes.scala` | getHistory 处理器：初始订阅（`beforeIndex` 为空）成功发送 historyPage 后调用新增 `replayPendingAsks(sessionId, wsSend)`（resolveRootSessionId → hub 问询 → 逐帧重发，失败降级为不重发）；分页拉取不触发。类型缺失帧分支：新增 `askPassthroughOrDispatch`（hub 命中=答案、miss=原样 `ensureAgent` 分发），纯文本无 refs/附件帧走此检查，带 `taskRefs`/`refs`/附件载荷的帧保持原分发 |
| `main.js` | `onMessage('askUser')` 新增 `msg.replayed` 分支：移除本 ask 的未锁定卡（历史还原卡无 `data-request-id`，或同 requestId 的重复重放）→ 按 requestId 重渲染；非活动会话持久化同样去重 |
| `persistence.js` | 新增导出 `saveAskMsgDedup(entry, sessionId, requestId)`：requestId 已在缓存的 pending askUser 不重复落 localStorage（防刷新/重连后 restoreFromStorage 叠卡） |
| `tests/askuser-refresh-survive.spec.mjs` | 新增 E2E（作者验收，见 §15） |
| `tests/fixtures/askuser-refresh/mock-llm.mjs` | 新增 OpenAI 兼容流式 mock（隔离 E2E 用，状态机：tool 未回应→终答 / 'ask me now'→AskUserQuestion 两问工具调用 / 其余→ok） |

不兼容性说明：`ListPendingAsks` 为纯新增命令；重发帧是 `replayed:true` 标记的状态重投递，不改 #43 答案来源校验语义（`askUserAnswerText` 排除 injected 的判定不受影响——重发帧不是消息，不进 ui.json 消息流）；已答卡不重发（hub 快照即权威），回答竞态天然免疫。

## 14. 重连重发设计取舍

- **服务端权威 vs 前端拉取**：选服务端推（任务指定优先）。hub 是 pending 唯一权威，快照即真相：已消费的槽不重发（answered-before-replay 不可能复活卡片）；前端拉取需新增接口且仍要以 hub 为准，等价但多一跳。
- **挂点选 getHistory 初始订阅而非 WS connect**：connect 时前端还没选定会话（activeSessionId 未定），且 historyPage 初始加载会清 DOM 重建——connect 时推的帧会被随后的 initial-load 清掉。挂在 historyPage 之后（同一 outbound 队列，顺序有保证）恰好落在「DOM 已定、用户未操作」的窗口，覆盖刷新/重连/切会话三个入口（三者都汇到 getHistory 初始加载；前端 onReconnect 回调本就重拉历史）。
- **去重策略选「先删后渲」而非「跳过」**：历史还原卡不带 requestId（缺口 1），跳过会留下无绑定卡；先删本 ask 的未锁定卡再按 requestId 重渲，最终态恒为一张带绑定的活卡。已锁定卡（`.option-answer`）永不触碰。
- **类型缺失帧直通**：与其在前端按 pending 状态切换帧类型（依赖前端状态、多路径），不如在服务端类型缺失分支统一补上第六件语义——一处修复覆盖所有「非 busy 输入」入口。带 refs/附件载荷的帧保持原分发（结构化回执不是自由文本答案）。

## 15. E2E 逐条结果（Playwright，隔离实例 + mock LLM 真实全环）

环境：隔离 NEBFLOW_HOME（`/tmp/askuser-e2e-home`）+ `GATEWAY_PORT=18923` 独立实例 + OpenAI 兼容 mock LLM（127.0.0.1:18990）；发起 AskUser 经 REST turn 端点（`handleUserText` 同一生产分发路径，rest-turn 源不注入 time reminder，规避既有 agent-core 提醒×工具调用竞态——该竞态与本任务域无关，见 §20）；垫 30 轮 ok（REST turn）+ pending 期间注入 55 条 ExternalEvent（callbacks/inject，#43 事故形态）使 askUser 条目落在首屏 50 页外。

- **T1 卡片点选路径**（3.8s ✓）：发起 → 活卡带 `data-request-id` → `page.reload()` → WS 重连 → historyPage → **重放帧到达**（inbound `askUser{replayed:true}`）→ 卡片恢复可见、选项可点（`isEnabled` 断言）→ 点 alpha+yes → 确认 → outbound `askUserAnswer{requestId}` 帧捕获 → 卡片锁定 → **工具正常返回**（MOCK_DONE 气泡含 alpha）→ 55 条注入排空。
- **T2 输入框直通路径**（0.8s ✓）：发起 → 刷新 → 重放恢复（`data-request-id` 与活卡一致）→ 输入框输入「直通答案文本-xyz」→ Enter → gateway 日志 `User text (typeless) → AskUser passthrough` → inbound `askUserAnswered{via:'chat-input', requestId}` → 卡片锁定 → 工具返回携带直通文本。
- **亮暗双主题截图**（恢复 pending 态、按钮可用，`emulateMedia` 切换）：
  - `/Users/dev/.nebflow/docs/Nebflow/20260903_askuser-refresh-survive-dark.png`
  - `/Users/dev/.nebflow/docs/Nebflow/20260903_askuser-refresh-survive-light.png`

## 16. 验红红绿证据

- **红**：临时 worktree 检出 `1f10246a`（#43 已含、无本次改动），编译后独立实例（:18924，隔离 home）跑同一 spec → **T1 在 `waitForSelector('.option-box[data-request-id]')` 超时红**（刷新后无卡恢复——askUser 条目在首屏页外、无重发，缺口 2 直接复现）；T2 连带红。失败输出即证据（Playwright list reporter）。
- **绿**：改后实例（:18923）同一 spec → 2/2 绿（4.7s / 6.8s / 5.4s / 5.8s 多轮复跑全绿）。
- 红→绿同 spec、同断言，差异仅重发机制有无。

## 17. #43 回归结论

- **sbt**：`AskUserPendingInjectionSpec` + `InteractionHubSpec` + `AskUserBuildJsonSpec` 子集复跑 **24/24 绿**（改动前后各一次）；全量 2091 全绿（#43 spec 在内）。
- **Playwright**：`tests/askuser-answer-source.spec.mjs` **5/5 绿**（事故 fixture 重放：答案槽干净、注入气泡排队、pending 卡重建可交互、已录答案还原、live WS 作答出站帧完整）。
- **真实链路叠加**：本 E2E 的 55 条 pending 期间注入在回答后全部排空（`pending-events-injected` ×55、无丢失、无一被消费为答案）——#43 排队语义在事故形态 + 刷新场景下行为正确。

## 18. 全量 sbt（worktree 前台一次跑完）

**Total 2091, Failed 0, Errors 0, Passed 2091, Ignored 7，耗时 475s（7:55）**（较 #43 时点 +4 = 本补充新 spec 用例数）。

## 19. 分支 / merge / 子集复跑 / 清理

- 分支 `askuser-refresh` commits：`c50c47bc`（hub ListPendingAsks + getHistory 重发 + 前端重绑定 + InteractionHubReplaySpec）、`e5232d48`（typeless 直通 + E2E + mock）。
- merge commit（本地 main，--no-ff，零冲突）：**`7710190c`**；未 push、未动 origin。
- 合并后主仓子集复跑（新 spec + InteractionHubSpec + AskUserPendingInjectionSpec + AskUserBuildJsonSpec + AgentActorCompactionSpec + SubAgentBarrierSpec）：**Total 66, Failed 0**。
- 清理：`git worktree remove .nebflow/worktrees/askuser-refresh` 完成（list 无残留）；`.nebflow/askuser-refresh` 软链已 rm；`git branch -d askuser-refresh` 完成（曾为 e5232d48，历史经 --no-ff 保全）；隔离实例（:18923/:18924/:18990）全部终止，/tmp 隔离 home 与临时 worktree 已删；宿主（PID 87216/:8080）全程未触碰。

## 20. 生效说明与遗留

**运行时生效需重建 + 重启宿主（本次未执行任何重启动作，重启由 Nebula 统一安排）。**重启后：pending ask 在刷新/重连/切会话后由 hub 重发恢复（含 requestId 绑定）；输入框直通在刷新场景恢复可用。

遗留：
1. **time-reminder × tool-call 竞态（既有，与本任务域无关）**：浏览器真实用户回合的 time-reminder 注入偶发与工具调用执行竞态，导致该回合的工具调用被丢弃（gateway 日志 18:09:24 run：无 InteractionRequest、turn 以 "ok" 收尾）。E2E 以 REST turn 端点发起（同生产分发路径）规避。建议后续单独立案排查 pipeLlmCall 的 reminder 注入时序。
2. **UiMessage.AskUser 不持久化 requestId**（缺口 1 本体）：重放链已补齐绑定，但历史还原卡在无 hub 场景（如实例重启后）仍无绑定——若要彻底闭环需扩展持久化 schema（encoder/decoder + 前端），本次按最小改动原则未动。
3. **多 pending 卡的恢复顺序**：重发按创建序逐帧渲染，多卡场景视觉顺序 = 创建顺序（与直通消费最老卡语义一致）；未做按关注度排序的 UX 处理。
4. §11 的 1/2/4（restoreFromStorage 交互重建、直通单槽语义、锁死卡移除查询）维持原状。
