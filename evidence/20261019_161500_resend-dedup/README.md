# 修复批 #569 — 客户端断线重连自动重发消息（重复气泡）· resend-dedup

日期：2026-10-19　分支：`delegate/delegate-nebflow-189e295a`

## 0. 症状与定性

WS 断开 ≈15 分钟（relay 票据 TTL）后重连，客户端把上一条已发出的消息又自动重发一遍
→ 聊天里出现重复气泡、agent 跑第二个 turn。**重发行为本身错误，与断开时长无关**：
幂等键应该在「逻辑消息」上，而不是在「发送动作」上。

根因（两层都在）：

- 客户端半边：所有**重发/补发路径都现生成新 clientMessageId**（resendLastInput 重试腿、
  排队 drain 腿、立即发送腿）→ 服务端按 id 去重的既有机制（AgentSession.checkDuplicate，
  recentMessageIds 窗口 100）对带新 id 的重发**必然失效**——重发看起来就是一条全新消息。
  且重发腿还**再渲染一个乐观气泡**（重复气泡的客户端半边）。
- 服务端半边：busy 排队分支（AgentProcessing 的 `UserInput` 入队）**无查重**——重复帧
  被原样追加进 pendingUserInputs，下一 turn 边界再派发一次（第二个 turn）；且三个丢弃点
  只打日志，客户端**不知道**消息被判重（乐观置位的 busy 变幻影，看门走满阶梯凭空呈现
  「仍在处理」）。

## 1. 修法（幂等优先，两层都落）

### 客户端（web/js）

| 文件 | 改动 |
|---|---|
| `state.js` | 新增 `lastUserSendId[sid]` 登记：每会话最近一次真人派发的幂等键 |
| `input.js` | ① `send(resendId)` 同 id 重发分支：复用原 id、**零新增呈现/落盘/历史**（首次尝试已留痕）；② ask / skill / immediateInput 帧补 `clientMessageId`（wire 先行）；③ 排队项 `queueMessage` 入队即钉 id，`persistQueue`/`restoreQueue` 随项存活，`drainMessageQueue`/`sendImmediate` 同 id 复用（双击立即发送也只投一次）；④ `sendImmediate` 队列成员闸：项已不在队列 = 已派发 ⇒ 不再二连发 |
| `chat.js` | `resendLastInput`（重试/重试键语义）：同 id 重发（id 缺席时保持旧行为现生成） |
| `ws.js` | `flushPendingSends` 幂等契约注释：补发层行为不变（离线入队帧从未写过 socket ⇒ 补送是 ②「不丢」的承担腿）；「已送达还重发」的抑制单点 = 服务端 id 去重 |
| `main.js` | `messageDuplicate` 回执处理器：收幻影 busy/看门/turnExpecting（15s 流活动守卫，防误清真 turn 的 busy） |

### 服务端（scala/nebflow/agent）

| 文件 | 改动 |
|---|---|
| `AgentSession.scala` | `emitDuplicateReceipt`：`{type:'messageDuplicate', sessionId, clientMessageId}` 单点构造（旧前端无 handler = no-op，协议加法零破坏） |
| `AgentIdle.scala` | 直投丢弃点补回执 |
| `AgentFrozen.scala` | 冻结唤醒丢弃点补回执 |
| `AgentProcessing.scala` | busy 排队入队**前**查重（🔴 查而不记：注册点 = idle 直投；入队即注册会让 head-to-self 转发的队头被自己的注册误杀 ⇒ 排队消息永不派发）；重复 ⇒ 丢弃 + 回执 |

边界申报：`ImmediateInput`（排队条纯文本「立即发送」腿）与 skill/ask 命令的**服务端**
去重本批未收口（ImmediateInput 命令无 id 字段，穿线涉及排队持久化与多处 drain 面）；
前端已让这些帧带 id（wire 先行），候二期命令形状收口。errorRecovery 冻结唤醒重试
**刻意**用新 id（重复 id 会被唤醒分支静默丢弃 = 第二次点击变 no-op，见该文件 R2 注），
本批不动。

## 2. 验收证据

### 2.1 E2E（真页面 + 真发送链，WS 桩实现服务端判重回执契约）

`e2e-resend-dedup.log` — `node tests/resend-dedup.spec.mjs` ⇒ **ALL PASS (21/21)**

- A 已送达场景：首发帧带 id（A1）→ 断开 → 客户端自动重连（A3）→ 真重试键补发
  ⇒ **同一 clientMessageId**（A5）、桩判重零第二次受理（A6）、**零重复气泡**（A7）、
  `messageDuplicate` 回执到达且零 pageerror（A8）。
- B 真未送达场景：帧被桩静默丢弃 → 断开 → 重连 → 重试 ⇒ 同 id 补发、桩首见受理
  **恰好一次**（B2，不丢）、零新增气泡（B3）、零 pageerror（B4）。
- C 排队 drain：busy 中入队零出线（C1）→ 终态帧对 drain 恰一帧带 id（C2）→ 重复
  终态帧对**不二连派发**（C4）。
- D wire 契约静态钉：ask/skill/immediateInput 帧带 id（D1-D3）、排队项入队即钉 id（D4）。

### 2.2 服务端 spec（真实 AgentActor harness）

`scalatest-ResendDedupSpec.log` — `sbt testOnly nebflow.agent.ResendDedupSpec` ⇒ 3/3 PASS

- RD1 idle：已受理 id 重到 ⇒ 丢弃 + 回执 + 零第二次 LLM 调用。
- RD2 busy 排队（两层）：已受理 id（本 turn 的）busy 中重到 ⇒ 排队闸丢弃 + 回执；
  新 id 连到两份 ⇒ 都入队、drain 后**恰派发一次**（第二份下一边界 idle 收掉 + 回执）。
  🔴 兼任「查而不记」反向钉：入队若注册 id，head-to-self 转发会被误判 ⇒ 排队消息永不派发。
- RD3 frozen：同 id 重复唤醒 ⇒ 丢弃 + 回执 + 零第二次 turn。

### 2.3 回归与闸

- `regression-msgqueue-quote-slash.log`：msgqueue-recall-attach 6/6、msgqueue-image-mime
  5/5、quote-message 9/10（1 败为 B12 截图像素比对存量 flake，与本批触碰面零交集）、
  slash-clear-compact 其余全绿。
- checkJs（`checkjs.log`）：总量 251 < 基线 273；本批触碰 5 文件零错误。🔴 门红 1 项 =
  `askAttachments.js: new file with 1 errors` —— **存量**（stash 后 HEAD 同败，随
  427e0ed1b askuser #551 批入库、基线未更新），非本批。
- build-web：164 sources 全入图，orphans 0。

## 3. 改动面清单（pathspec 提交用）

```
src/main/resources/web/js/state.js
src/main/resources/web/js/input.js
src/main/resources/web/js/chat.js
src/main/resources/web/js/ws.js
src/main/resources/web/js/main.js
src/main/scala/nebflow/agent/AgentSession.scala
src/main/scala/nebflow/agent/AgentIdle.scala
src/main/scala/nebflow/agent/AgentFrozen.scala
src/main/scala/nebflow/agent/AgentProcessing.scala
src/test/scala/nebflow/agent/ResendDedupSpec.scala   (新增)
tests/resend-dedup.spec.mjs                          (新增)
```

范围红线自检：零触碰消息渲染管线（chat.css / 渲染函数未动；唯一呈现面动作 =
「同 id 重发不再渲染第二份」，属发送链呈现语义）。
