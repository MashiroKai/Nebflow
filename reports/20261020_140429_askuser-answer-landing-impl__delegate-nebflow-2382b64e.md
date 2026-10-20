# AskUser 社交腿答案回注——实施批交付报告

设计依据：`reports/20261020_123548_askuser-social-answer-inject/`（四件，原案落地；开放问题按设计推荐默认采纳）。任务令两新增缺陷一并修复。

## 根因与治本

上行链 U1–U6（sidecar 收信 → 渠道闸 → 映射解析 → hub `Answered` → `complete`）全部健康；唯一断点在 U7——`AskUserAnswerBridge.deliver` 的 `sessionAlive=false` 分支（原 `AskUserAnswerBridge.scala:134-137`）把答案 WARN + 静默丢。本批把该分支升级为**三臂着陆面**，任何出口不静默。

## 改动清单

### 新建

1. **`src/main/scala/nebflow/bridge/AskAnswerReceipt.scala`** —— 回执反向通道注册表（缺陷①的载具）。
   - `Entry(channel, routeKey, send, createdAt, seq)`；`record`（幂等覆盖 + 容量 256 溢出逐出最早项，`(createdAt, seq)` 双键裁决——`createdAt` 毫秒粒度在同毫秒批量下不可分，seq 盖章保证「最旧 = 最早登记」）/ `peek`（只读）/ `take`（原子消费）/ `drop`（退役随撤）/ `size` / `sendLine`（best-effort：无登记或发送失败只 WARN，绝不 raise）。
   - 纯内存 Ref，零定时器——零超时纪律不破。生产写点：微信 `answerAsk` 转发时、飞书文本腿与卡按钮作答时（同一时刻渠道地址在手，闭包零成本）；清理点：取消关键字 / `askUserClosed` / B-3 新问顶旧问。

2. **`src/main/scala/nebflow/agent/AskAnswerLanding.scala`** —— 着陆面（`land` 是 bridge 亡会话分支的唯一入口）。
   - `decide` 纯函数四布尔判据（hasAttribution / nodeInProjectGraph / projectActorPresent / delegateHostAlive）→ `ProjectReentry | DelegateInjection | Fail(reason)`，单点分派。
   - **臂1 引擎重入**：归因在（`ctx.projectName + flowNodeId`，构造时快照）∧ 节点在项目图 ∧ 宿主带 actorRef ⇒ 审计 `answer-retry`（FlowMapEventLog 基座）+ `ProjectActor.AskAnswerReentry(nodeId, requestId, answerText)` + 渠道确认回执行「已收到。发起会话已结束，答案已转交引擎重入（…）」。
   - **臂2 委托注入**：节点不在项目图（委托作用域）∧ `DelegateFlowHosts` 宿主在册 ∧ 委托会话活 ⇒ `ImmediateInput(fromUser=true, source=AnswerSource)` 注入委托会话（作用域无分发器，委托会话即编排者），同审计 + 确认回执。
   - **臂3 失败出口**：归因缺席 / 宿主不在 ⇒ WARN + `answer-retry-failed` 审计（有 ws/project/node 锚点时）+ 桌面 toast（`interactionAnswerRejected` 既有帧族，`reason="answer-undeliverable"`，前端零改动）+ **渠道失败回执行**「问题已作废：答案未送达——<原因>。该问题不会再等待作答（如需继续请在桌面面板重新发起）。」。

3. **`src/test/scala/nebflow/agent/AskAnswerLandingSpec.scala`** —— 8 测：L0 活会话既有不变（字节回归）/ L1 重入臂（指令 + 审计文件 + 回执三断言）/ L2 归因缺席 / L3 宿主不在（回执 + toast + 零注入）/ L4 decide 判据表 / R1-R3 注册表语义（登记→消费→退役→有界→sendLine）。

### 修改

4. **`AskUserAnswerBridge.scala`** —— `deliver` 的 `sessionAlive=false` 分支接 `AskAnswerLanding.land`（附件清单段随答案同走重入）；类 doc 更新。取消通告 / 派发失败信号两分支不动（设计表5 既有处置：取消通知仍须注入会话，与答案着陆无关）。

5. **`ProjectActor.scala`** —— `ProjectCommand.AskAnswerReentry(nodeId, requestId, answerText)` + 主循环 handler（HotRestart admissionGate → `dispatchAnswerReentry`）。`dispatchAnswerReentry` 与 `dispatchReentry` 同族：节点竞态消失兜底 WARN（答案未投诚实留痕）→ 并发闸 `underConcurrencyCap` → 有活分发器 per-slot 投 `UserInput(answerReentryInjectionText)`（source=SourceTask）/ 无则 spawn 新分发器（`answerReentryPrompt`）。文案 helper：`answerReentryPrompt` / `answerReentryInjectionText` / `answerReentryTaskText`（开放问题 #5 采纳 5b：答案 = 对该问题的直接回答，分发器把答案折进节点任务后 NodeEdit 重激活，答案随 task 重读进入节点）。

6. **`InteractionHub.scala`** —— `apply(liveness: Option[String => IO[Boolean]] = None)`（缺省 None = 旧构造零改动）；`handleListPendingAsks` / `handleListAllPendingAsks` 增 `annotateAskerAlive`：按 `sourceSession` 现查落 `askerAlive` 布尔键——**读时投影、非破坏性**（探针缺席逐字节不改帧；失败 WARN 保留原帧）。

7. **`GatewayMain.scala`** —— hubSetup 接线探针 `Some(sid => agentRegistry.get.map(_.contains(sid)))`（开放问题 #3 推荐默认）。

8. **`outbound/AskPrompt.scala`** —— 新增 `batchHeaderLine(batch)` 单一来源模板：非空 → `[批名]`，空 → `""`（两渠道禁第二份字面量）。

9. **`WeixinAskLeg.scala`** —— ① `ShortNumberFree = "^(\d{1,3})\s+(\S.*)$"` 短号自由文本臂（插序：裸编号之后、label 臂之前；`{1,3}` 位宽 + `toIntOption` 双保险；N 超界 / 空白内容 → RangeReject；secret 题 → SecretReject；`0 `/`他` 前缀臂与逗号多选臂零变化——B-2 白名单不放宽）。② `renderAskText` / `renderAskTextTruncated` / `renderAskTextBudgeted` 增 `batch: String = ""` 尾参，卡头前置批名行（批名行计入首段、不参与截断判据；空批名逐字节兼容）。文件头 Parsing rules 文档同步。

10. **`WeixinIlinkBridgePlugin.scala`** —— ①**缺陷②媒体守卫**：`askLegs = if in.mediaLanded then IO.pure(None) else askTextLegs(ctx, in)`——媒体消息（text 为 sidecar 合成占位标签 `[图片]`/`[语音]`/`[文件]`/`[视频]`）永不进 ask 前置臂，占位标签与选项标签的规范化碰撞不再可能把「发了张图」消费成作答；媒体恒走既有媒体臂/聊天流。②`answerAsk` 转发前 `AskAnswerReceipt.record`。③`cancelAsk` / `onAskClosedFrame` / B-3 closeOld 随撤登记。④`onAskUserFrame` 读帧 `project`（回落 `nodeName`）为 batch → `sendAskCard` 透传渲染。

11. **`FeishuBridgePlugin.scala`** —— `sendTextFallback` / `sendAskCards` 增 `batch` 参（fallback 文本前置批名行；全拒降级腿透传）；`onAskUserFrame` 读 batch；`forwardTextAnswer` 与卡按钮 `answerFromFrame` 在 forward 前 record（send 闭包 = `reply(rootSid, _)`）；`keywordCancel` drop。

12. **`WeixinAskLegSpec.scala`** —— 追加 5 测（WA-P8 短号臂 / WA-R4 批名渲染 / WI-A8 媒体守卫 / WI-A9 帧归因卡头 / WI-A10 回执登记与随撤）。

## 裁量注明（设计文档字面 vs 实现）

- **审计事件类型取 `answer-retry`**（任务令措辞；设计文档写 `answer-reentry`）——任务令为准，先例 `reentry-triggered` 同基座。
- **批名卡头格式 `[批名]` 一行**（设计文档字面 `[问题N·批名]` 的 N 与问头 `第 N/M 问` 重复，故省略；模板单一来源 `AskPromptCodec.batchHeaderLine`）。
- **「恒单问下的自由文本」实现为既有 `0 `/`他` 前缀臂 + 新增 `N <文本>` 带题号变体**——B-2 白名单不放宽，裸文本仍恒为聊天。
- **逐出裁决键 `(createdAt, seq)`**：单测实测同毫秒批量登记下 `createdAt` 不可分，补单调 seq 盖章保证「最旧 = 最早登记」。

## 测试

- 家族回归：**16 spec / 200 测全绿**（AskAnswerLandingSpec 8 + WeixinAskLegSpec 31 + AskUserCancelDeliverySpec / AskCancelChannelSpec / AskUserBuildJsonSpec / AskUserDispatchUnavailableSpec / InteractionHubSpec / InteractionHubCancelSpec / InteractionRequestIdSpec / FeishuAskCardParseSpec / FeishuAskCardSpec / FeishuBridgePluginSpec / AskSecretChannelSpec / WeixinIlinkBridgeSpec / DispatcherSpawnPromptSpec / InteractionHubReplaySpec）。
- 零孤儿引用自检：`eventSnapshot` 0 命中；`answer NOT delivered` 仅存于着陆面失败出口的 WARN 腿（设计内）；`renderAskText*` 全部调用点（含 `AskSecretChannelSpec` 位置实参调用）经尾参默认值零破坏；`Test / compile` 全绿（`-Xfatal-warnings` 下零警告）。

## 生效条件

**引擎侧候重启窗**：`GatewayMain`（hub liveness 接线）与 `InteractionHub.apply` 签名变更需进程重启生效；其余（桥插件、着陆面、ProjectActor 指令）同样随重启装载。改动全部在 Scala 侧，无存储迁移、无前端改动。
