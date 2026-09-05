# Delegate COMPLETED 但父 session 无轮次 — 根因排查报告

> 2026-08-20 23:10 · 只读排查（Explorer, delegate-Explorer-d378caba）· 分支 archive/scala @dceac386
> 活样本：root session 5cc7590a-6dcb-4dba-8979-0ce5eb0a14fe，22:40–23:04 窗口
> 日志行号基于 `~/.nebflow/logs/nebflow.log`（排查时刻 66.5k 行快照），每条附时间戳双定位

---

## TL;DR

**不是 #25（3a5d791d）的回归。** 今晚现象是三层独立缺陷的叠加：

1. **直接机制**：并发 Delegate 的 batch barrier HOLD 语义（2026-08-18 引入，非 #25）——Coder 完成时 design-engineer 仍在飞，`outstandingSubagentResults 2→1 > 0`，结果被 hold 进 `pendingEvents`，**不触发轮次**，但 UI 气泡照发。
2. **触发器**：design-engineer 的 LLM 请求自 22:48:41 起无限悬挂（10 分钟 `LlmTimeoutMs` 未生效），batch 永不归零 → held 结果永久滞留。
3. **放大器**：用户 stop（22:59:14）与 TaskStuckWatcher hard-cancel（22:59:44 起，至今 `ignored 11+ Stops`）**全部失效**，supervisor 收不到任何事件 → barrier 上留下永不释放的 phantom slot。

**分叉条件**（一句话）：`outstanding-1 == 0` 的完成事件立即触发父轮次；`> 0` 则 HOLD 等最后者；**最后者若永不发事件（hang / 停止失败 / 异常退出）→ 永久死锁，且 phantom slot 污染后续所有 delegate，直到重启**。这就是「有的时候能触发」（单飞 / batch 全正常 / 重启后）与「间歇性不触发」（batch 有成员 hang）的完整解释。

---

## 1. 现象与时间线（活样本）

| 时刻 | 日志行 | 事件 |
|---|---|---|
| 22:40:26.908 | 65932 | root actor 重启 spawn（`msgs=17`）——in-memory barrier 清零，干净起点 |
| 22:41:25.243 | 65955 | `delegate-Coder-bd42703a` spawn（barrier +1） |
| 22:41:25.247 | 65957 | `nebflow-project/Manager` spawn（Mail 团队，**不计 barrier**） |
| 22:41:42.146 | 65970 | `delegate-design-engineer-36d95c33` spawn（barrier +1）→ **outstanding = 2** |
| 22:41:52.294 | 65987 | root `turn-complete`（msgs=25）——root 回 idle，两个 delegate 在飞 |
| 22:48:40.842 | 66478 | Coder `turn-complete`（msgs=27，模型 107/glm-5.2-107） |
| 22:48:40.865 | 66479 | root 收到 `external-event source=delegate type=completed` → **走 HOLD 分支，root 保持 idle，无轮次**（UI 气泡已先发出） |
| 22:48:41.596 | 66480 | design-engineer 最后一条工具日志（`Grep("SessionRecorder\(")` 返回）→ 发起下一轮 LLM 请求 → **从此悬挂** |
| 22:48:41 → 23:04+ | 66624+ | design-engineer 零活动；TaskStuckWatcher 22:59:44 起 `ignored 2/3/4/…/11 Stops — hard-cancelled 1 in-flight LLM request(s) (agent was suspended on its LLM fiber)`，每 30s 循环，**至今无效** |
| 22:55:12.828 | 66499 | 用户手动输入（「怎么还是存在，DELEGATE · CODER · COMPLETED 结果返回，但是没有触发你轮次」） |
| 22:55:12.844 | 66500 | root `start`（msgs=26）——手动触发；**此 turn 内无 `pending-events-injected-at-tools-complete` 日志**，Coder 结果仍 held |
| 22:56:57.750 | 66526 | root 又 delegate Explorer（本次排查）→ outstanding = 2（1 phantom + 1） |
| 22:59:14.637 | 66606 | design-engineer `event=stop detail=reason=user`——用户手动停止，**同样无效**（suspended actor 不消费 mailbox Stop） |

补充事实：23:02 起 watcher 同时报告另一个 team agent `aac1c33a` stuck 797s+——**今晚 provider（zhipu/107 线路）连接 stall 是批量性的**，这解释了「重启 22:40 后首次双 Delegate 并发即复现」：不是代码新回归，而是 hang 概率 × 双 delegate = 必然复现。

**「手动输入才被捡起」的真相**：root 对 Coder 工作的了解全部来自 nebflow-project Manager 的 **Mail 链路**（root session 文件尾部 `src=mail` 的 `[RESULT] fix/log-full-messages 已合并完结…c730d236`）。Coder 的 delegate 正式结果（`AgentEvent.Completed` 载荷）**至今滞留在 root 内存 `pendingEvents` 中，LLM 从未见过**——idle UserInput 分支不 drain 事件（AgentActor.scala:373-423），turn 内 drainBarrier 在 `outstanding>0` 时继续 hold（AgentActor.scala:89-92）。

---

## 2. 根因定位（证据链）

### 2.1 直接机制 — HOLD 分支：UI 可见 COMPLETED 但不触发轮次

代码路径 `src/main/scala/nebflow/agent/AgentActor.scala`（idle 态收到 ExternalEvent）：

```
L595   val isSubagentResult = TurnBoundaryDrains.isSubagentResult(event)   // source=="subtask"||"delegate" (L55-56)
L596   val outstanding = state.execution.outstandingSubagentResults        // = 2（Coder+design-engineer）
L597   val newOutstanding = if isSubagentResult then math.max(0, outstanding - 1) else outstanding  // = 1
L599   if isSubagentResult && newOutstanding > 0 then
L603-617  // HOLD 分支：
          //   receiveVisibility（L577-588）→ emitStream(ExternalEventReceived) + emitInjectedUserEvent
          //   ↑ UI 的「DELEGATE · Coder · COMPLETED」气泡来自这里——所以气泡必然可见
          //   然后 idle(...) 返回：event append 进 pendingEvents，无 pipeLlmCall，无轮次
L618   else if isSubagentResult && held.nonEmpty then   // batch 全部完成：注入全部 held + 触发轮次 ✓
L639   else                                            // 单飞（newOutstanding==0）：注入 + 触发轮次 ✓
```

**「UI 可见 COMPLETED 但 root 无轮次」的每一步**：supervisor `notifyParentAndStop` 发 `AgentEvent.Completed` → 父 actor 转 `ExternalEvent(source=delegate, type=completed)` → L597 递减 barrier 得 1 → L599 命中 HOLD → L577-588 先发 UI 气泡 → L605 `idle(...)` 返回。气泡可见 + 轮次不存在，同一条消息处理里同时成立。

日志实证：22:48:40.865（行 66479）`external-event` 之后到 22:55:12.844（行 66500）`start` 之间，root **零** start/turn-complete 日志（窗口内仅有 NebLink discovery 心跳）。

### 2.2 触发器 — design-engineer LLM 请求悬挂（10 分钟超时未生效）

- 最后工具返回：22:48:41.596（行 66480）。此后该 session 无任何 lifecycle/handlers 日志。
- `Defaults.scala:41`：`LlmTimeoutMs = 600_000`（10 分钟）。22:48:41 + 10min = 22:58:41 应触发超时 → llm-fail → 事件链。但 22:59:44 watcher 判定 stuck（说明 `lastActivityMs` 停在 22:48:41，**超时从未触发**）。
- `fallback.scala:158-159`：`withTimeoutIO(ioa, ms) = ioa.timeout(ms)` 只包裹 `tryProviderWithFallback` 的 IO action（L192）。流式路径（`sendStream`，本案所有 turn 均为 `textStreamed=true` 模式）的消费端是否在超时覆盖内，是本案暴露出的**独立待查缺陷**——结论以日志为准：超时没有救回这个请求。
- 佐证 hang 非孤例：23:02:14 watcher 同时报 team agent `aac1c33a` stuck 797s；22:48:04-06 有一长串 `degenerate tool-call fragment` 警告（provider 流异常的旁证）。

### 2.3 放大器 — 恢复机制双重失效 → phantom barrier slot 永存

**失效一：用户 stop 无效。** 22:59:14.637（行 66606）`event=stop detail=reason=user`。`AgentCommand.Stop` 是 mailbox 消息，而 actor suspended 在 LLM fiber 上永不消费 mailbox——这正是 TaskStuckWatcher 头注释（L34-38）记载的 gate-wedge 事故模式（「6.5h 每 30s 重发全部无效」）。

**失效二：TaskStuckWatcher 的 hard-cancel 无效。** watcher 按 P1-1 设计升级：`StopAttempts=2` 后调 `LlmInterface.cancelInflightFor(sessionId)`（TaskStuckWatcher.scala:178-194）。该方法（`interface.scala:64-70`）通过 `e.halt.complete(Left(new StuckAbort(sessionId)))` 中止——日志每次都报 `hard-cancelled 1 in-flight LLM request(s)`，说明 inflight 注册表命中且 Deferred 完成成功，**但 actor 的 turn fiber 没有被唤醒**（`ignored 2→11 Stops` 逐次递增，stuck 状态持续）。中止信号的产生与 turn fiber 的 await 之间存在断点（待专项排查：halt Deferred 的消费方接线）。

**净效果**：design-engineer 既不 completed 也不 failed 也不 cancelled → root 的 `outstandingSubagentResults` 里的那 1 个 slot **永远不会归还**。root 的 held 队列里 Coder 的结果 + 后续 Explorer 的结果（若我完成时 outstanding 2→1 > 0，同样 HOLD）**全部滞留到进程重启**。用户此前报的「DELEGATE · EXPLORER · COMPLETED 不触发」与此同源。

### 2.4 #25（3a5d791d）澄清 — 时间吻合是巧合，不是回归

用户提供的 hash 5f576929 实为 #341 TTL 修复；#25 parking 是 **3a5d791d**（19:58 合并）。其改动是：子 agent turn 结束时若自身 `outstandingSubagentResults > 0`，把**它自己的 replyTo** park 进 `owedCompletion`，不发 `AgentEvent.Completed`（AgentActor.scala:2274-2296, 2334-2337, 2429-2434, 2483-2489, 2556-2562）。

关键区分：#25 park 的是**子 agent → 其 requester 的完成通知**；今晚的问题在**root 接收侧**（root `replyTo=None`、`owedCompletion` 恒为 Nil，"Root agents unaffected" 是该提交明文保证且代码成立）。今晚 root 的 HOLD 行为来自 2026-08-18 的 worker blocking semantics（drainBarrier 及三分支），当晚 22:40 重启后的「首次复现」由 provider 批量 stall 促成，与 19:58 合并的 #25 无因果关系。

### 2.5 能 / 不能触发的分叉条件（代码 + 日志双证）

| 情形 | 分支 | 结果 | 实证 |
|---|---|---|---|
| 单 delegate 完成（outstanding 1→0） | L639-667 single immediate | **立即触发** | 21:46:50.836 completed → 21:47:09.402 root turn-complete (msgs=546)，19s 内 |
| batch 最后一个完成（→0 且 held 非空） | L618-638 batch complete | 注入全部 + **触发** | 语义上等价 single（代码路径可验证） |
| batch 非最后完成（→N>0） | L599-617 HOLD | **不触发**（等待，UI 显示 COMPLETED） | 今晚 22:48:40.865 → 22:55:12.844 零轮次 |
| batch 成员 hang / 停止失败 / 无事件终止 | — | **永久死锁** + phantom slot 污染后续所有 delegate | design-engineer 22:48:41 悬挂至今；22:56:57 后续 Explorer 也将被同一 phantom 吞掉 |
| 进程重启 | in-memory 清零 | 「又能触发了」 | 22:40:26 重启（msgs=17 起点） |

「间歇性多日」= phantom slot 的生命周期（产生后持续到重启）× provider stall 的偶发性。

---

## 3. 修复方案（最小改动优先，A 为必做）

### Fix A（核心·最小）：watcher 终极升级 — 保证 barrier 必然归还

`src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala:178-194`

hard-cancel 后下一轮扫描仍 stuck（即 `attempts >= StopAttempts + 2`，约 60s）时，**直接向 `rec.parentRef` 发一条兜底事件**：

```scala
// attempts >= StopAttempts + 2 时（escalate 后仍无活动）：
rec.parentRef.foreach(_ ! AgentCommand.ExternalEvent(
  source = "delegate",                    // 命中 isSubagentResult → 父 barrier 递减 (L596-597/L1769-1772)
  eventType = "failed",
  payload = "<system-reminder>子 agent <name> [session=...] 卡死超时被放弃，其结果不可用；如需可重新派发。</system-reminder>",
  metadata = Map("failedSessionId" -> rec.sessionId.asJson,
                 "agentName" -> rec.name.asJson,
                 "retryable" -> true.asJson,          // 触发父的 re-delegate 提示 (AgentActor.scala:561-571)
                 "failureType" -> "stuck".asJson)
))
// 并从 agentRegistry 注销该 session，终止重试循环
```

效果：无论 actor 本体死活，父的 `outstandingSubagentResults` 归还 → held 结果走 batch-complete/single 分支注入并**触发轮次**（复用现有 `failed` 事件的全部既有链路：`AgentActor.scala:561-571` 的 system-reminder、UI 气泡、`isSubagentResult` 递减）。~15 行改动，零新机制。

### Fix B（hang 本体）：流式 LLM 请求 idle-chunk 超时

`src/main/scala/nebflow/llm/`（sendStream 消费端接线处）——两个动作：
1. **排查**：确认 `withTimeoutIO`（fallback.scala:158-159）对流式路径（`sendStream`）的覆盖范围——本案证明 10 分钟总超时对「连接建立后流 stall」无效。
2. **修复**：流式增加 chunk 间空闲超时（如 120s 无任何 chunk → `TimeoutException` → 走现有 `classifyError`（fallback.scala:122-123, Timeout/Permanent）→ llm-fail 链）。这是 design-engineer 类悬挂的根治点，也顺带治好 23:02 的 team agent 同款 hang。

### Fix C（体验·可选）：HOLD 气泡标注

`emitInjectedUserEvent`（AgentActor.scala:583-588）在 HOLD 分支调用时附加 `waitingForBatch=true` → 前端气泡显示「已完成 · 等待同批任务」——把「COMPLETED 但父不动」从 bug 观感变成可理解的等待状态。

### Fix D（诊断·可选）：phantom 可见化

AgentControl(list) 输出加 `outstandingSubagentResults` / `pendingEvents.size` 两列——phantom slot 一眼可见，本案这类问题从 15 分钟日志考古变成一条命令。

---

## 4. 验收条件（二值化）

**冒烟（硬性第一项）**：
1. `sbt run` 真实启动 → WS 连接 → 让 Nebula 并发 delegate 两个子任务（一个正常 mock/真实，一个用 iptables/代理把 provider 连接 stall 掉）→ 快者完成后 UI 显示 COMPLETED → 慢者被 TaskStuckWatcher 处置（threshold 调至 60s）后 **60s 内 root 必须自动触发轮次**，且注入的 system-reminder 包含两个结果（修复前：永不触发）。日志断言：`external-event` (failed, source=delegate, stuck) → 紧随 root `start`。

**单元/集成测试**：
2. 新增 `StuckDelegateReleaseSpec`：mock LlmHandle 一支正常、一支 `Stream.never` → 断言 (a) 快者结果在慢者被 watcher 兜底后注入父并触发轮次；(b) 父的 `outstandingSubagentResults` 归零；(c) phantom 不残留（再 delegate 一支单飞任务，完成即触发）。
3. 回归三项全绿：单 delegate 完成即触发；双 delegate 全正常完成 → 最后一个完成触发 + 批量注入（现有 `NestedDelegateNotifySpec` 语义）；`git stash` 验证法下 Fix A 不影响正常路径。
4. 全量 `sbt test` 通过（现有 1312+ 用例无回归）。

**Fix B 专项**：
5. 流式 stall spec：mock stream 发 1 个 chunk 后 `Stream.never` → 120s（测试中调小）内必须以 Timeout 分类失败，agent 走 llm-fail 而非无限悬挂。

---

## 附：复核入口

- 日志：`grep -n "36d95c33\|5cc7590a.*external-event\|TaskStuckWatcher" ~/.nebflow/logs/nebflow.log`
- root session：`~/.nebflow/sessions/5cc7590a-6dcb-4dba-8979-0ce5eb0a14fe.json`（尾部 src=mail 的 [RESULT] 即 Manager 代传的 Coder 工作汇报——delegate 载荷不在其中）
- design-engineer session：`~/.nebflow/sessions/delegate-design-engineer-36d95c33.json`（61 条，尾部停在 22:48:41 的 Grep 结果 + assistant thinking）
- 三分支代码：`AgentActor.scala:589-668`；drain：`:83-95`；spawn 计数：`:1396-1412`；#25 park：`:2274-2296`
- watcher：`TaskStuckWatcher.scala:173-194`；cancel：`llm/interface.scala:64-70`；超时常量：`shared/Defaults.scala:41`
