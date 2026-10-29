---
type: design-note
status: done
---

# 回执补投静默 + 屏障 10 分钟定性（补投通知摘内部机制名批）

批：作者 2026-10-29 11:05 console 报障「这是怎么回事？另外，这里的通知还是有显示。
SYSTEM · NEBULA · FORCE-DRAIN NEBULA，需要摘除」。
实施：`src/main/scala/nebflow/agent/AgentActor.scala`、`AgentIdle.scala`、
`src/main/resources/web/js/locales/{zh-CN,en}.js`；
回归闸：`src/test/scala/nebflow/agent/HeldReceiptNoticeFaceSpec.scala`。
相关：[[turn-liveness-spec.zh-CN]]（零超时/活性纪律）、[[glossary]]。

## 1. 症状与根因链

作者在根会话（Nebula-d7c3cb54）看到一条来源「System · Force-drain」的通知卡，
正文「[投递补注] 1 条回执此前被子代理批屏障扣留未注入（屏障槽位已归还），现随本
回合补投: 1. [delegate·completed · 已等待10分钟] …」。

链路：`HeldDeliveryWatchdog`（催注调度）→ `AgentCommand.DrainHeldReceipts`
（AgentIdle 处理器）→ `AgentActor.emitForceDrainVisibility` 发独立注入气泡
（source=`system` + eventType=`force-drain` → `NotificationHeader` 兜底
「表外 eventType 原样全大写」→ header 逐字渲染 `SYSTEM · NEBULA · FORCE-DRAIN`）。
机制本身工作正常（回执确实补投了），坏的是**内部机制名原样暴露给作者**，且逐字
复述接收时刻已经见过的同一条回执（接收时刻已有正常 DELEGATE·COMPLETED 气泡）。

## 2. 修了什么（改动面）

1. **机械卡整体退役**：`emitForceDrainVisibility`（连同私有 helper `oneLine`）
   删除；`DrainHeldReceipts` 处理器不再发任何作者可见通知卡——补投**静默并入正常
   消息流**。回执本体的作者可见面由接收时刻的接收气泡承担（processing/idle 两腿
   本就发）；催注补投的留痕面 = lifecycle 日志（`held-receipts-force-drain`，
   操作者诊断面）+ LLM reminder（合批一 turn，与批完成汇聚腿同形）。投递可靠性
   机制（催注、台账、徽标帧、屏障槽位归还）零改动。
2. **徽标词条去机制词**（同模式）：`heldReceipts.held` 词条 zh「{n} 条回执被子
   代理屏障扣留」→「{n} 条回执待补投」；en "held by the sub-agent barrier" →
   "pending delivery"。徽标 tooltip 是作者可见面，此前带着引擎内部机制名词。
3. **spec 钉**：`HeldReceiptNoticeFaceSpec`——①发射面（emitInjectedUserEvent 五个
   宿主文件）代码面机械卡字面量（`[投递补注]`/`屏障槽位已归还`/
   `Some("force-drain")`）零命中；②heldReceipts 词条值面不带 屏障/barrier/
   force-drain；③正面钉（催注链路/台账/重试策略/徽标帧/词条键不得被误删）。

## 3. 屏障扣留 10 分钟定性（结论）

事故时间线（stand.log，会话 d7c3cb54，2026-10-29）：

| 时刻 | 事件 |
|------|------|
| 10:41:19 | spawn ×2（ask mode 三修、词条调研）→ outstanding=2 |
| 10:45:16 | 边栏图谱 delegate 完成 → 回执到父会话（父 processing）→ **HOLD**（接收气泡照发），producedAt=10:45:16 |
| 10:46:26 / 10:47:40 | 再 spawn ×2（设置面板四修、独立窗口本机加速）→ outstanding=4 |
| 10:55:20 | ask mode 三修完成 → 父 idle 直投腿注入（#418 腿）→ outstanding=3 |
| 10:55:46 | watchdog 催注（到期=producedAt+10min=10:55:16，60s 扫描拍）→ force-drain：held=1、**returned-outstanding=3** |

**定性：屏障无泄漏，10 分钟是设计内的兜底 wall-clock 超时。**

- 三个被「归还」的槽位全部是**活成员**：设置面板四修、独立窗口本机加速在 11:07
  仍在收件干活；词条调研 11:03 仍在续跑。屏障在等最后一个成员终态——批汇聚语义
  正常运转，该扣、扣得对。
- 放行时钟 = `Defaults.HeldDeliveryFirstRetryMs`（10min）＋ watchdog 60s 扫描拍：
  催注判据只看「idle 会话有 held 回执且 producedAt+N 分钟未注入」，**不看
  outstanding 槽位背后有没有活成员**——对健康长批次会提前放行（本次即如此）。
  提前放行对投递无害（回执照注、后续回执立即注入），破坏的只是「整批一起注入」
  的聚合语义。
- 正常屏障生命周期：槽位随 spawn 计入、随成员终态回执递减（processing/idle 两腿
  同点）；held 回执的正常放行 = ①turn 边界 drainBarrier（outstanding==0）、
  ②idle 收到最后一件的批汇聚腿、③#418 idle 单件直投腿、④任意后续输入的
  held-first-inject 腿、⑤TaskStuckWatcher giveUp（真幻影：Stop/hard-cancel 双
  失效后经 supervisor Cancelled 精确递减）。即「等到最后一个成员终态为止」，合法
  批次可以远超 10 分钟。

**红线对照（如实报告）**：作者红线「零超时设计——正确性靠监测表达，不允许设计性
wall-clock 超时当正确性手段」。`HeldDeliveryFirstRetryMs` 及其退避链
（10→20→30min 封顶 ×5 次 ≈2h exhausted）**是**设计性 wall-clock 超时，被用作
「回执投递正确性」的兜底判据——watchdog 自述「不是任务超时、绝不杀会话」对会话
成立，但对投递面确实以 wall-clock 为触发器。与本红线冲突，记录在案。零超时兼容
的修法方向（**本批未实施**，不扩工程）：催注判据加活性监测门——outstanding 槽位
在 registry 有活成员（Running/Waiting 子会话）⇒ 不催（继续等汇聚）；无活成员
（幻影，issue #31 类）⇒ 立即催注。如此 wall-clock 从「正确性手段」降级为幻影
监测的兜底残留，与 [[turn-liveness-spec.zh-CN]] 的监测表达同构。

## 4. 同类暴露清单（排查面）

| 面 | 内容 | 处置 |
|----|------|------|
| 注入气泡 header 兜底 | `NotificationHeader` 对表外 eventType 原样全大写进 STATE 段——`force-drain` 变 `FORCE-DRAIN` 的通道 | 本批拔掉唯一喂它的机制名（`Some("force-drain")`）；通道本身保留（在飞批合法源需要兜底） |
| heldReceipts 徽标 tooltip | zh/en 词条带「屏障/barrier」机制词 | 本批改人话 |
| LLM reminder（`HeldDeliveryPolicy.unreadReminder`） | "sub-agent barrier wedged — issue #31 class"——LLM 面，作者不直接可见但 LLM 可能转述 | 列出不改（非作者直接面；改文案动 LLM 提示面需另批评估） |
| 工具结果文本（MailTool ×3） | tool result 带 "(issue #22)" 内部台账号——LLM 面/控制台工具流 | 列出不改（同上） |
| lifecycle 日志事件名 | `held-receipts-force-drain` 等——操作者诊断面（stand.log），非作者聊天面 | 保留（诊断面需要机制名） |
| `.ui.json` 存量历史行 | 已落盘的旧机械卡行随历史渲染 | 不回写历史（留档）；新事件零产生 |
| 前端 mail-queue 旧腿标签 | source=`mail-queue` 不在 KIND 词表 → 回落「Mail-queue · Queue」 | 既有呈现，非机制 token，不动 |

## 5. 验证

- `sbt Test/compile` + `HeldReceiptNoticeFaceSpec`（新钉 5 test）+ 既有
  `HeldDeliveryFlushSpec`（heldFlushPlan 决策面）全绿。
- 源面自查：`src/main/scala` 内 `[投递补注]`/`屏障槽位已归还`/
  `Some("force-drain")` 零命中；locale 值面机制词零命中（残留命中全在注释与
  lifecycle 日志事件名，均为设计内保留面）。
