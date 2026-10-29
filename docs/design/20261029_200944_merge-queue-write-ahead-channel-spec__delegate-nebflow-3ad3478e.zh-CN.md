---
type: design
status: implemented
---

# R5 合并队列重启丢队修复——write-ahead 通道 spec（映射表 P5）

[[20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN|委托双模式设计]] §1 P5 行 + [[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4|审计报告二]] §4.3 的独立实施批（第二次派发；报告二建议照抄有效）。台账实证：B2（WS 分片腿）13:00 判 ready 未入账、20:55 引擎重启、23:07 人工 `--no-ff` 归队。

## 病灶

- 投递通道进程内不落盘（`MergeQueue.Member.internal/external` 闭包/ref），账本回载后恒 None——重启后开放成员在放行腿命中 `no delivery channel on record` ⇒ owner-gone 人工处置；MergeQueueBootSweep 对账把「可并但属主失联」一并打成 owner-gone。
- 更早一层：ready-to-merge 之后、入账写盘之前被杀 ⇒ 连账本条目都没有（B2 形态；2026-10-28 批在 SubAgentStartupRecovery 落了僵尸收殓，但成员资格仍丢）。

## 修法（本批只加持久性，判定机/MergeAcceptance 零改动）

1. **write-ahead 通道** `MergeQueueChannel`（新文件，`<dataRoot>/merge-channel/<delegateId>.json`，DeviceResultOutbox 同型：一条一文件、AtomicJson 原子写、坏件跳过 WARN、fail-soft 不阻断主路径）。成员镜像与账本行同形（`memberJson` 输出）。
2. **先写面**：入账（enqueue）与全部状态迁移（占窗/盖章/阻塞回队/merged/owner-gone/failed/保留）在内存与账本写盘**之前**先 `MergeQueueChannel.sync`（MergeQueue.scala 各迁移点）。
3. **boot 重放** `replayChannelLocked`（MergeQueue.ensureLoadedLocked 尾，恰一次）：通道有账本无 ⇒ 收养入账（ready-前被杀窗口闭合）；账本开放、通道领先（关账/占窗先落通道）⇒ 并回通道状态；账本已关账/两面一致 ⇒ 零动作。重放后账本回归唯一读面。
4. **owner-gone 前提反转**：判定以「通道无未关账记录」为前提——`MergeQueue.notifyTurn` 无通道腿改三态（Delivered/Deferred/Failed）：通道闭包缺席但有未关账镜像 ⇒ Deferred（留队缓拍 + BlockNotifyPort 通知 + N 拍升级），**不 owner-gone**；无镜像（存量账面/直写账本）⇒ Failed 照旧关账。MergeQueueBootSweep 对账同前提：registry 记录缺席/可并但属主失联 + 通道镜像在盘 ⇒ `MergeQueue.preserveAfterRestart`（窗让回 → waiting + lastBlockReason + 必通知；任务行保持 ready-to-merge），不凭重启事实伪造 owner-gone。
5. **关账压缩**：终态镜像批量移除——`compact`（boot sweep 尾）+ `compactIfDue`（MergeQueueCare 拍尾，阈值 `CompactAfter=8`，事件驱动零定时器）。未关账镜像永不入压缩面（收养前提）；账本持终态审计行（面板权威）。

## 自愈闭环

重启后 preserved 成员：属主续活 ⇒ 下拍放行重投（at-least-once，合并指令自带只读账实核对预检兜重）；分支被人工并入 ⇒ 下拍账实核对直接关账 merged——B2 人工归队形态从此自动收口。

## 验收（spec 读数）

- `MergeQueueSpec` ⑫：enqueue→重启→重放恢复队列状态（state/FIFO/闭包缺席断言）+ 通道只写形态（账本写丢）boot 收养。
- `MergeQueueSpec` ⑬：前提反转——重启成员有未关账镜像 ⇒ 放行尝试留队不判死 + 通知/升级机械完好；对照组直写账本（无镜像）⇒ owner-gone 照旧。
- `MergeQueueSpec` ⑭：关账压缩——终态镜像出通道、开放镜像幸存、账本留审计行；阈值门零误删。
- `MergeQueueBootSweepSpec` ⑤：可并+属主失联+镜像在盘 ⇒ preserved（ownerGone 空、窗让回、restart-preserved 审计行）；⑤b：镜像在盘+记录缺席+已并 ⇒ fact-check 关账 merged（收养前提不挡 happy path）。
- 存量 ①-⑪/①-④ 零改动全绿（seedMemberForTest 走账本直写 = 无镜像 = 前提成立，旧行为逐字保留）。

## 边界声明

- 外部腿通道重建（重启后续聊 CLI 属主）不在本批——那是复活/续聊机制面；本批保证外部腿成员资格与状态跨重启不丢、面板可见、处置路径明确。
- 重放收养的「通道已终态」成员不做升级通知重投（at-most-once 面）；账本行为权威。
- 双模式零交集：模式二无物可并不进队（设计稿 §5.7/§8.3）；MergeAcceptance/判定机/放行闸语义逐字未动。
