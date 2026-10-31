# 合并回执通知链修复批 REPORT

- 批：合并回执通知链修复（作者 2026-10-31 08:38 裁定：「delegate 不是应该合并完成了
  才通知你吗——怎么还没合并就通知你了，然后合并完了也没通知你」）
- 症状：多起 delegate 批 work result 到了 dispatcher、「已合并」回执失踪，作者只能
  自己去 git 查合并是否落地。

## 断点定位（为什么回执丢了）

链路：`BackoffSupervisor` 工作轮 Completed → `DelegateTerminal.onCompleted` 判
ready-to-merge → HoldForMerge；合并轮 Completed → 同路径判 completed →
`Outcome.Release` → `notifyParentAndStop` 投父（`correlationId=Some(delegateId)`）。

断点在**父会话 intake 的恰一次判重**：

1. 投递可靠性批 2026-10-29「停靠等合并不再扣住报告」给内部腿加了 **ready-to-merge
   停靠投递**：工作结果先行投父，`correlationId=Some(delegateId)`，并在
   `DelegateDeliveryReceipts` 落交付回执。
2. 父会话（dispatcher）注入该停靠件时按 #886 教义「注入即标记」：
   `DeliveredReceiptLedger.markDelivered` 把身份键 `delegateId` 写入
   `<dataRoot>/sessions/<sid>/delivered-receipts.json`。
3. 合并落地、合并轮终态回执到达父会话 intake：
   `DeliveredReceiptLedger.intakeAccepts → isDuplicateIdentity` 按身份键判重——
   `delegateId` 已被标记 ⇒ **合并回执被当重复件滤除**（审计行
   `external-event-duplicate-suppressed`），永不注入回合。
4. 补投机器（`DelegateDeliveryReceipts` sweep）同样无解：重投件经
   `redeliveryOf` 归位同一身份键，仍被同一判重滤除；3 次重投后 Retain，回执永久失踪。

旁证：停靠件与合并回执共用 correlationId 还会**多减一次父屏障槽位**
（`TurnBoundaryDrains.isSubagentResult` 只按 source 判）。外部执行器腿无停靠投递
（`ready-to-merge 不发——MergeQueue 持有父回报权`），不受影响——与「多起都是
work result 到了、回执失踪」的实证范围吻合（内部腿）。

## 修法（作者裁定语义）

**撤销停靠投递**：一个 delegate 生命周期对 dispatcher 恰一次通知 = 合并落地时的
终态回执（合并前的中间态零通知）。恰一次判重/屏障链随之全部归位：

- `BackoffSupervisor` HoldForMerge 分支：删除停靠投递（parkPayload/parkDelivery/
  parkedForMerge 元数据/停靠回执落盘），保留预算重挂、registry Waiting、
  delegateWaiting 面板帧。前端从未消费 `parkedForMerge`，零扩散。
- 工作结果零丢失：`DelegateRegistry.workReport` 保真槽（10-25 既有）在停靠时照写；
  合并轮 Release 附页（`DelegateWorkReport.appendixFor`）常态随行工作结果全文 +
  merge status 一行（附页的去重腿保留：存量已 ack 回执仍恰一次）。单条合并回执 =
  判词行 + 工作结果全文，一次到齐。
- 合并轮夭折（failed/cancelled 终态——合并窗超时/撞脏树/机械验收拒绝） Safety
  net：新增 `DelegateWorkReport.preservedWorkSuffix`，`notifyParentAndStop` 包装层
  对「delegate × 非 completed × 保真槽在场」终态随投保真工作结果全文——停靠不投父后
  该形态下工作结果的唯一回传面，工作成果绝不随夭折丢失。completed 终态工作段仍由
  附页承担，两段永不双投。

外部腿（`ExternalSessionRunner`）本就是「合并轮终态才投父」语义，本批后两腿同语义。

## Diff 摘要

- `src/main/scala/nebflow/agent/BackoffSupervisor.scala`
  - HoldForMerge 分支：-51/+20——停靠投递整体撤销（10-31 断点定位注释），预算重挂/
    Waiting/面板帧保留；
  - `notifyParentAndStop` → 包装层（拼保真随投段，`IO.blocking`）+ `notifyParentAndStopImpl`（原实现）；
  - Completed 入口注释更新（对父恰一次通知 = 合并落地时）。
- `src/main/scala/nebflow/core/delegate/DelegateWorkReport.scala`
  - 新增 `preservedWorkSuffix(delegateId, source, eventType)`（纯函数，fail-soft）；
  - `appendixFor` / `workResultAlreadyDelivered` 注释刷新（10-29 停靠投递撤销、去重腿
    保留为存量/防御面）；对象头消费点补随投保一行。
- 测试新增 `src/test/scala/nebflow/agent/MergeReceiptNotifyChainSpec.scala`（R1/R2）。

## 回归证据

- 新增 `MergeReceiptNotifyChainSpec`（真仓真并全生命周期模拟）：
  - **R1**：工作轮 judge ready-to-merge ⇒ 停靠期父**零** ExternalEvent（修复前红态：
    此时父已收到停靠 work result）；夹具代执行 `git merge --no-ff`（机械验收双亲+
    祖先判据真过）⇒ 合并轮 Completed ⇒ dispatcher **恰收到 1 件** completed 终态
    （correlationId=delegateId），payload 同时含 `[delegate-judge] verdict: completed`
    判词行 + 保真工作结果全文；交付回执落盘、任务行 completed、队列关账。
  - **R2**：merge-window-timeout cancel ⇒ 恰 1 件 cancelled 终态 + payload 含保真
    工作结果（夭折不丢工作成果）。
- 相邻套件回归：`DelegateWorkReportParkDedupSpec`（3/3）、
  `DelegateFinishWorkResultSpec`（5/5）、`DelegateTerminalSpec`（14/14）、
  `BackoffSupervisorCancelSpec`（2/2）、`DelegateBgWaitHoldSpec`（4/4）全绿；
  `MergeQueueSpec` **单独运行 16/16 全绿**。
- 既有 flake 备案（与本批无关，实证）：7 套件组合运行时 `MergeQueueSpec` ① 在
  `records==0` 断言处偶发失败（80ms 睡眠的时序窗）；**干净树对照**（本批改动全部
  stash、新 spec 不存在）跑同一 6 套件组合**同样失败**——跨套件组合干扰为存量
  问题（① 的调用链不经过本批任何改动函数），不在本批范围。

## 未做 / 开放项

- 停靠投递撤销后，dispatcher 在合并排队期间看不到工作结果（作者裁定语义本体：
  「合并前的中间态不吵」）；面板侧 ready-to-merge 行/合并队列面板仍可见进度
  （既有面，未动）。
- 机械验收拒绝的合并轮仍走 CompletedNote/Refused 关账 failed（既有语义），其投父
  附页由既有 Release 链承担，未改动。
