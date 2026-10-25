# merge-queue 滞留两病灶修复批 · REPORT

日期：2026-10-25　作者令：修复批（合并队列滞留两病灶）+ 同日补充令五点　分支：`delegate/delegate-nebflow-a1260cfc`

**与 10-23 批的关系**：本批是 `reports/20261023_212405_merge-silent-stall/REPORT.md`（合并路线静默空等修复批：腿① MergeRefusalCare 拒绝双通知 / 腿② 平台零代跑核对 / 腿③ restoreArtifacts 只还 tracked 修改）的**续作不是重做**——MergeRefusalCare 双通知面原样复用为本批拒绝通知的投递通道；10-23 还原腿原样保留（本批只补 rec 解耦版供合并轮消费）；未重造任何已有腿。

**铁律合规申报（作者 2026-10-23「合并不自动化」+ 2026-10-25 补充令②）**：本批「滞留重试」= **向 delegate 重发合并提醒（退避 + 事件驱动）+ 升级告警**——重试的实现是唤醒/续起 delegate 会话让它**自己执行** `git merge`（与作者已批准的 boot sweep internalRevival 同机制：内部腿续起基工作区座位 continuation 会话、合并指令 brief 原文照发；外部腿 CLI 续聊），**平台零代跑 merge**。全批无任何引擎亲自跑 `git merge` 的新增路径。

**收编属性申报（补充令④）**：生成物收编 = 平台代为 `git add` + `git commit` **工件本身**（evidence/、reports/ 白名单内 `??` 未跟踪文件，一笔 chore 提交落基分支），**不碰 delegate 分支的合并本身**——与铁律不冲突；性质同 10-23 腿③的「共享区卫生」申报（清障不代跑，决策/重试/合并仍在 delegate）。

**待裁定（不在本批范围）**：`DelegateAutoMerge`（#524 引擎原生终态自动合并）本体是否按「合并不自动化」退役——10-23 批已交作者裁定，本批沿用其现状（拒绝通知宿主 + 本批工件处置消费点），不自行扩动。

## 病灶（作者 2026-10-25 实证）

1. **工件处置不兑现**：auto-merge 拒并文案声称「generated artifacts under evidence/ and reports/ are auto-restored by the engine」，实测不发生——10-23 批的还原腿只处理**已跟踪文件的未提交修改**（porcelain M），`??` 未跟踪工件（`reports/20261023_182241_panels-mobile-ux/` 等）既不还原也不收编，滞留主树两天多、两次挡死合并队列。
2. **滞留无重试无告警**：被拒 delegate 只获一次通知，此后 `pendingRetry` 条目静默滞留——无重试者、无告警者，MergeQueue FIFO 不推进，作者肉眼才发现。

## 修法落地（作者三腿逐字对照）

### 腿① 工件处置兑现（未跟踪生成工件：收编优先；tracked 修改 fail-closed 不变）

- 新增 **`MergeArtifactCare`**（`src/main/scala/nebflow/core/delegate/MergeArtifactCare.scala`）：
  - `absorbUntracked`：evidence/、reports/ 下 `??` 未跟踪工件 `git add` + `git commit` **收编进待合并批**（一笔 chore 提交——报告/证据是批交付物，收编不丢）；提交前守卫：暂存区混入白名单外路径（他人 staged 的源码）⇒ 按路径撤销放弃收编，绝不卷走；
  - `restoreTrackedOverwrites`：白名单内 tracked-M 还原到 HEAD（`checkout HEAD --`，10-23 批语义的 rec 解耦版）；
  - `hygiene`：一拍全量处置，`remainingDirty` 如实回报白名单外余脏（源码等——调用方 fail-closed 拒绝，**tracked 修改语义零变化**）。
- **`DelegateAutoMerge`**：评估闸接腿⑤——tracked-M 还原后仍脏且余脏恰为白名单 `??` ⇒ 收编（审计行 `artifact-absorb`：路径+提交 sha），树转洁合并放行；配置开关 `delegateAutoMerge.absorbArtifacts`（缺省 true，false = 一键回收编前语义）。拒绝文案改为诚实版（tracked 覆写自动还原、未跟踪工件自动收编、源码 fail-closed）。
- **`MergeRefusalCare`**：拒绝通知文案同步兑现承诺（不再只说 auto-restored）。
- **`MergeQueue`**：合并轮提醒注入前跑 `artifactHygiene`（`fireViaActor`/`fireViaChannel`，fail-soft）——delegate 的 `git merge` 不再被工件挡死；有动作落账本行 `artifact-hygiene`。

### 腿② 滞留告警 + 退避重试（队首条目 ×「无竞争合并 ∧ 工件已清理」）

- **`MergeQueue`** 条目台账扩展：`retryCount` / `lastAttemptAt` / `stallNotified` / `stallEscalated`；被拒终态（`onTerminal(keepForRetry=true)`）进位计数、重置退避锚与通知旗；新面 `markStallNotified` / `markStallEscalated` / `bumpStallAttempt` / `acquireWindow` / `releaseWindow` / `retryInject`（通道腿重发：成功=盖章+持窗+`retry-injected` 行，失败=留队+退避进位）/ `stallSnapshot`（全队列滞留投影）。
- 新增 **`MergeStallCare`**（滞留看护，`src/main/scala/nebflow/core/delegate/MergeStallCare.scala`），消费点 = `TaskStuckWatcher.scan` 尾部（MergeWindowCare 邻位，30s 扫描拍，零新增定时器）：
  - **逐次拒绝通知（谁在挡、挡在哪）**：每次新拒绝 ⇒ 双通知（复用 `MergeRefusalCare.EscalationPort` 投递面）——delegate 持久注入（具体脏路径清单 + 领先条目/持窗者 + 引擎已处置面 + 重试预告），父会话一行 FYI；correlationId 携失败计数（每次拒绝是独立事件，持久队列不吞）；
  - **N 次退避重试**：每 project **队首**停靠条目，窗空闲 ∧ 无领先活条目时，先跑引擎清障（`MergeArtifactCare.hygiene`），清不净 = 本拍不烧 LLM 只记账（`stall-blocked-by-dirt`）；清净后重发合并轮——通道腿走 `MergeQueue.retryInject`；内部腿（会话已终态）走 **`RevivalPort`**（agent 层续起 continuation 会话 + rekey，复用 boot sweep `internalRevival`，GatewayMain boot 装配 `mergeStallPortSetup`）；退避 = base × 2^k（60s 起、10min 封顶）；
  - **升级告警根会话（不再静默）**：重试预算（`maxRetries`，缺省 3）耗尽 ⇒ 升级事件投最终责任方（root 活投 / 持久收件箱补投，`BgNotifyDelivery` 同族），correlationId 幂等 + 条目级粘住（一次停靠段只告警一次）；条目留队——分支/收编工件绝不丢。
- **障碍清除事件驱动唤醒（2026-10-25 补充令③——今日精确缺口「障碍清除后无人再唤醒 delegate」）**：树由脏转洁的**真过渡**（`MergeStallCare.obstacleClearPulse`，30s 扫描拍上采样、进程内基线；重启归零首拍只记基线）⇒ 对队首被拒 delegate **重发合并提醒**：退避闸旁路（`MergeQueue.markObstacleCleared`——lastAttemptAt 归零 + 账本行 `obstacle-cleared`）+ 升级粘住解除（障碍真清除后恢复自动推进资格，再滞留走完整重试+再升级，事件有界不风暴）。提醒重发仍走 delegate 会话（zero-proxy）；限界：30s 内完成的「洁→脏→洁」抖动不触发（拍上采样）。
- **`MergeQueueBootSweep`**：中断成员词表补 4 词（`stall-notice-sent` / `stall-attempt` / `retry-injected` / `stall-escalated`）——滞留条目跨重启照常重挂（交付绝不静默丢失；重试预算随重挂归零）。
- 配置：nebflow.json `mergeStall.{enabled,maxRetries,backoffBaseMs,backoffCapMs}`（热读，缺省 true/3/60s/10min）。

### 腿③ 可观测（队列状态面）

- 新增只读 API **`GET /api/merge-queue`**（`PresenceRoutes`）：按 project 分组的排队项全投影（位次/持窗者/停靠标记/提醒状态）+ 滞留台账（失败计数、上次动作时刻、通知与升级旗、readyAt）+ 每条目最近拒绝原因（ChainStore 扫描，fail-soft）。真源 = `MergeQueue.stallSnapshot`（引擎闸本体，与既有 `mergeQueuePos` 行投影同源）。

## 验证（spec 钉三锚全绿）

新增/扩展 spec（`sbt Test/testOnly`，全部通过）：

- **脏工件拒绝→自动收编→合并推进**：`DelegateAutoMergeSpec` ⑬（未跟踪 reports/ 工件挡路 → 收编入册 → 合并落地 + `artifact-absorb` 审计行）、⑬b（`absorbArtifacts=false` 杀开关回旧语义）、⑬c（混合脏：白名单收编、源码 fail-closed、拒绝携余脏路径）；⑪⑫（10-23 批还原/源码禁区）回归通过。
- **滞留 N 次告警路径**：`MergeStallCareSpec` ①（清障后经 RevivalPort 重发 + 持窗）、②（清障仍脏 ⇒ 记账退避不烧 LLM）、③（逐次拒绝通知：具体路径 + attempt 键 correlationId + 幂等盖章）、④（预算耗尽 ⇒ 恰一次升级投活 root，粘住）、⑤（退避未到零动作）、⑥（领先活条目让位）、**⑦（障碍清除事件驱动唤醒：树脏→洁真过渡 ⇒ 退避旁路 + 提醒重发给 delegate 会话，平台零代跑）**；`MergeQueueStallSpec` ①②③a③b④（台账进位/盖章面/retryInject 双腿/stallSnapshot 投影）。
- **既有合并族回归**：`MergeQueueSpec`、`MergeQueueHeadSkipSpec`、`MergeWindowCareSpec`、`MergeAcceptanceSpec`、`DelegateTerminalSpec`、`MergeQueueBootSweepSpec`、`MergeQueueRestartReminderSpec`、`PostMergeCleanupSpec`、`DelegateUiDataPlaneSpec` 全部通过（FIFO 排序、单飞行、队头被拒跳过、提醒不绑活体、冲突挂起、恢复重挂等既有判据零漂移）。

## 关键决策与假设

1. 作者令「tracked 修改仍 fail-closed 拒绝不变」按「白名单外 tracked 修改（源码等）fail-closed 不变、10-23 批的白名单 tracked-M 还原腿原样保留」落地——两类生成工件（tracked 覆写 → 还原、untracked → 收编）自此承诺都兑现。
2. 收编 = 提交到**基工作区当前分支**（与作者手工解锁批 `acb7f4f33` 同形：chore 收编提交先落、合并提交随后）——merge 提交的双亲校验/漂移归因（chore 提交是合并提交祖先 = 搭载归属）均不受影响。
3. 重试唤醒 = 续起/续聊 delegate 会话执行合并（作者 2026-10-23「合并不自动化」铁律与 2026-10-25 补充令②逐字合规：重试腿 = 提醒重发 + 升级告警，合并永远由 delegate 自己跑）；事件驱动腿 = 同准则的转洁唤醒（10-23 撤销的「转洁**代跑**重试」的合规范替身）。
4. 重启后进程内重试预算归零（boot sweep 重挂为新鲜条目）——correlationId 幂等挡住重复告警轰炸；已在 `MergeQueueBootSweep` 头注注明。
5. 已知限制（沿既有 MergeQueueBootSweep 形态）：rekey 换键后条目 reminderSentAt 已盖章，后续 pump 若再授窗会重投 brief（合并幂等 Already-up-to-date 兜住）——既有形态，本批未动。

## 文件清单

新增：`MergeArtifactCare.scala`、`MergeStallCare.scala`、`MergeStallCareSpec.scala`、`MergeQueueStallSpec.scala`
修改：`DelegateAutoMerge.scala`、`MergeRefusalCare.scala`、`MergeQueue.scala`、`MergeQueueBootSweep.scala`、`TaskStuckWatcher.scala`、`GatewayMain.scala`、`PresenceRoutes.scala`、`DelegateAutoMergeSpec.scala`
