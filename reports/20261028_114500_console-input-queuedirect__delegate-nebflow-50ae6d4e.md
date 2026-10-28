# 控制台输入「排队改直通」（queuedirect 批）实施报告

- 日期：2026-10-28
- 作者令：11:1x「取消我们的这个输入排队功能（不删代码），就是我现在给你的输入，如果你在回复过程中，那么就会进入排队，改为，直接输入给你。」；11:14 补充裁定「应该不会打断你的 llm 调用吧？效果就像是我们现在的立即发送一样。」
- 分支：`delegate/delegate-nebflow-50ae6d4e`（工作树 `.nebflow/worktrees/delegate-nebflow-50ae6d4e`）
- 在产引擎零接触：本批**引擎 main 代码零改动**（`src/main/scala` 无 diff）；新行为 = 纯控制台（web 前端）接线，随下一波前端包生效。

## 0. 语义裁定落点（作者 11:14 令覆盖 brief 的「打断」描述）

不取消、不打断在飞 LLM 调用与工具批次。目标 = **回合内最近安全边界注入**：busy 会话收到用户消息 ⇒ 消息并入 agent **正在进行的回合**（当前 LLM 调用/工具批次完成后、下一次 LLM 调用前）；已无更多内部边界 ⇒ 自然落到回合结束注入（与旧行为等价，等待可忽略）。这正是引擎 `ImmediateInput` 的既有承载语义（#939 Mail 工具边界注入同源），**引擎零改动即可复用**——控制台缺的只是「busy 时也把消息立刻发出去」这半边。

## 1. 机制图：输入框 → agent 视野的两条路径对照

```
【direct（新缺省）】busy 会话输入 → 立即出线
  输入框 Enter
    └─ send() isBusy ∧ mode=direct（缺省）∧ WS 开 ∧ 非冻结/非重发
        └─ dispatchQueuedItemNow(sid, item, {quiesce:false, newTurn:false, wire:'immediate'})
            ├─ 乐观气泡即上屏（data-msg-id = 幂等键）＋历史/落盘
            └─ 纯文本 ⇒ WS 帧 {type:'immediateInput', content, clientMessageId, device, sessionId}
                （带附件/引用/任务 ⇒ 默认用户帧，「立即发送」同律）
                └─ 网关 handleImmediateInput → dispatchUserText
                    ├─ UiMessage.User 落盘（先落盘后投递，崩不丢）
                    └─ AgentCommand.ImmediateInput(fromUser=true)
                        ├─ 引擎 idle ⇒ 直接开新 turn（既有）
                        └─ 引擎 Processing ⇒ pendingImmediateInputs（最近安全边界注入）
                            ├─ 工具批次在飞 ⇒ ToolsComplete 边界：工具结果后、下一次
                            │   LLM 调用前，作为 user 消息并入续轮（同回合）
                            ├─ 在飞 LLM 轮且无工具边界 ⇒ 回合末续轮注入
                            └─ compaction 挂起 ⇒ 守卫扣住（注入进将替换的历史=丢失），CompactionComplete 排空
                            【全程不取消在飞 LLM 调用/工具批次】

【queue（console.inputMode='queue' 切回）】旧行为逐字
  输入框 Enter (busy)
    └─ queueMessage() 本地队列（排队条呈现，零气泡，幂等键入队钉死，persistQueue 落盘）
        └─ （不发帧，干等）
            └─ 引擎回合 done 终态帧
                └─ drainMessageQueue → dispatchQueuedItemNow（默认用户帧）
                    └─ 引擎 idle ⇒ 开新 turn（排队实现一行未删）

【queue 旧轨引擎对照】默认用户帧 busy 到达 ⇒ UserInput → pendingUserInputs →
  turn 末 head-forward 逐条独立 turn（2026-09-15 ub 裁定面，本批不动 = W3 负对照钉住）
```

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/featureFlags.js` | 新增 `consoleInputMode()`：读 `state.parsedConfig?.console?.inputMode`（nebflow.json `console.inputMode` 经既有 configData 通道），`'queue'` 显式才走旧轨，**缺席/非法值一律 direct（新缺省）**；实时读，无 latch |
| `src/main/resources/web/js/input.js` | ① send() busy 分支改双模：direct（且 WS 开）⇒ 直通腿；queue 或 WS 已断 ⇒ 旧排队腿逐字保留。② 从 `drainMessageQueueNow` 抽出单条派发台 `dispatchQueuedItemNow(sessionId, item, opts)`，drain 腿与直通腿共用；opts：`quiesce`（占位清理/stream 复位，直通=false——回合还在流式进行，清了会撕裂在飞气泡）、`newTurn`（乐观新 turn 记账：busy 置位/确认网/计时戳/终止戳清账，直通=false——busy 事实本身来自引擎在场，幻影形态不成立，不重挂确认网不动计时）、`wire`（`'immediate'` = 纯文本走 immediateInput 帧；缺省 = drain 旧行为默认用户帧）。③ skill 模式 busy 直通腿（type:'skill' 帧立发）；compact 模式**两模都保持入队**（其即时命令面 `command:compact` 已随 2026-10-09 命令面退役成网关静默 no-op，直投=静默丢失） |
| `src/test/scala/nebflow/agent/BusyConsoleInputInjectionSpec.scala` | 新增引擎语义钉（3 用例，见 §3）——引擎零改动的行为红线 |
| `tests/console-input-direct.spec.mjs` | 新增控制台验收（26 断言，D/Q/S 三景，见 §3） |
| `tests/f1-send-queue.spec.mjs` 等 9 个存量 spec | 桩 configData 显式钉 `console.inputMode:'queue'`（一行/文件）——这些 spec 的契约 = 排队机械（drain/flush/确认网/排队条），该机械现为可切换模式，钉住被测模式即恢复原契约；其中 `queuebar-informative` S4 与 `send-bubble-independence` S6d 的源码钉随 `dispatchQueuedItemNow` 抽取同步更新（`item.ts` 时间戳真值钉保持，仅调用形态加第四参）。详见 §4 |

排队功能保留面核对：`queueMessage` / `state.messageQueue` / `persistQueue` / `drainMessageQueue(Now)` / 排队条（chatQueue.js 渲染、拖拽/撤回/移除/立即发送按钮）/ 离线轨（enqueueOfflineMessage/flushOfflineQueue）/ 重连确认网 —— **全部零删除**，`console.inputMode='queue'` 一键整体切回。

## 3. 测试证据

### 3.1 引擎语义钉（sbt testOnly，2026-10-28 11:44，exit 0）

`nebflow.agent.BusyConsoleInputInjectionSpec` — real AgentActor + 脚本化 LLM + 门文件 Bash（MailBoundaryInjectionSpec 同款夹具，零 spawn 零端口零 :8080 接触）：

- **W1 ✅** 工具批次在飞窗口（门文件扣住 Bash）投入控制台形 `ImmediateInput(fromUser=true)` ⇒ 门释放后续轮请求（第 2 次 LLM call）**携带直通正文**（工具边界注入、同回合）；在飞批次**完整跑完**（批次回显 `batch-done` 进续轮转录 = 未被砍）；第 1 轮请求不含后到消息（无重发/重放）。
- **W2 ✅** Deferred 门扣住的 LLM 流在飞（无工具边界）投入直通消息 ⇒ 在飞轮完整跑完（round-1 产出 `first answer` 保留进续轮转录 = **LLM 调用未被取消/丢弃**）后续轮请求携带该正文（已无内部边界 ⇒ 回合末注入）。
- **W3 ✅** 同窗口投入真人 `UserInput`（queue 旧轨引擎承载形）**不进**工具边界续轮——留队 turn 末逐条独立 turn（旧排队语义负对照）。

```
+ W1: busy 会话的控制台形 ImmediateInput 在工具边界并入续轮请求（在飞批次完整跑完） 1.816s
+ W2: Deferred 门扣住的 LLM 流在飞窗口投入 ImmediateInput ⇒ 在飞轮完整跑完后续轮携带该正文 0.351s
+ W3: busy 会话的真人 UserInput（queue 旧轨引擎承载形）不进工具边界续轮——旧排队语义逐字不变 1.395s
[info] Passed: Total 3, Failed 0, Errors 0, Passed 3
```

### 3.2 控制台验收（playwright 真发送链，node tests/console-input-direct.spec.mjs，ALL PASS 26/26）

- **D 景（direct 缺省，config 不带 console 键）**：持轮会话（桩只回 sessionBusy{true} 扣发终态）中再发消息 ⇒ 恰一帧 `type:'immediateInput'`（content+幂等键+sessionId，不走默认用户帧腿）、零本地入队、零排队条、乐观气泡即上屏、busy 镜像不被打断、连击第二条同样立即出线、零 pageerror。
- **Q 景（显式 `"console":{"inputMode":"queue"}`）**：busy 发送零出线、入本地队（幂等键入队钉死）、呈现面=排队条、会话流零气泡；done 终态后 drain 恰补发一帧（默认用户帧、id=入队钉死幂等键）、队列排空、排队条收起（旧行为逐字回归）。
- **S 景源钉**：`consoleInputMode()` 缺省 direct；直通腿 `wire:'immediate'`；`queueMessage` 旧轨保留在场。

### 3.3 静态门与构建

- checkJs 类型门：PASS（257 < 基线 260，零新增）。
- `npm run build`（esbuild 产物链）：PASS（188 源全入 bundle 图，orphans 0）。

### 3.4 存量回归（详见 §4）

queue/busy 族 12 个 spec 全绿：`f1-send-queue` / `queue-one-per-turn` / `queue-idle-flush` / `queue-idle-return` / `busy-lifecycle` / `queue-image-refresh` / `queuebar-informative` / `resend-dedup` / `send-bubble-independence`（九个钉 queue 模式后 ALL PASS）+ `attach-split`（未钉、模式无关全绿）+ `console-input-direct`（新，direct 缺省）+ 引擎钉 3/3。

三个 spec（`msgqueue-recall-attach` / `msgqueue-image-mime` / `queuepos-detail`）在 pristine main 上即崩（@playwright/test 装载形态 vs 全局 playwright 解析冲突，Node v23.9.0 启动即败）——**存量问题与本批无关**，未触碰。

## 4. 存量 spec 的模式钉（回归修复说明）

direct 成为缺省后，以下 spec 的 busy 轨场景（其断言 = 「busy 中发送不出线/入队」）不再成立——这不是排队机械坏了，而是被测模式变了。九个 spec 的桩 configData 一行显式钉 `queue`，恢复其原契约（它们继续守护排队机械：drain 一次一条、重连确认网、幻影收口、排队条信息面、双气泡互斥等——该机械在 queue 模式与离线轨照常服役）：

`f1-send-queue` / `queue-one-per-turn` / `queue-idle-flush` / `queue-idle-return` / `busy-lifecycle` / `queue-image-refresh` / `queuebar-informative` / `resend-dedup` / `send-bubble-independence`

（`queue-image-refresh` 未钉亦绿——其 busy 交互模式无关，佐证离线/终态轨不受本批影响；`attach-split` / `send-bubble-independence` 的源码钉、`queuebar-informative` S4 已核对同步。）

## 5. 边界决策与已知特性（申报）

1. **compact 模式不入直通**（两模都入队）：compact 的即时命令面已是网关静默 no-op，直投=静默丢失；入队保持现状端到端行为。
2. **带附件的直通消息**走默认用户帧（引擎侧落 pendingUserInputs → turn 末注入），非 immediateInput 腿——`handleUserText` 文本腿不承载附件，「立即发送」既有同律，等价体验。
3. **WS 恰断的直通回退**：直通腿要求 WS OPEN，否则落回旧队（busy 轨项由重连确认网收口补发）——不丢消息。
4. **直通腿不重挂 turn-start 确认网/不动计时戳**：busy 判定本身来自引擎 sessionBusy 在场，「派发被判重 ⇒ 无 turn」的幻影形态不成立；在飞回合的看门/计时照旧。
5. **引擎侧 immediateInput 腿无 clientMessageId 判重**（网关既有口径，「立即发送」同款）——双击防抖 300ms + 派发台幂等键钉死已是既有全部防线；本批未加宽也未收窄。
6. 直通消息到达时机在 compaction 挂起窗 ⇒ 引擎守卫扣住至 CompactionComplete（防注入进将替换的历史），等待可忽略（压缩秒级）。

## 6. 交付

- commit：见分支 `delegate/delegate-nebflow-50ae6d4e`（本报告随交付 commit 落盘）。
- 在产生效：纯前端接线，随下一波前端包/重启窗口生效；引擎 main 零改动。
