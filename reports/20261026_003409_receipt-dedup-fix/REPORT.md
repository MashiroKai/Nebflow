# #886 完成回执重启去重 —— 修复批报告（2026-10-26）

- 批次：完成回执重启去重（#886，作者令优先）；症状 = 10-25 23:15 重启后 canvas ×3、console-card ×2、imagegen ×3、机制双件 ×2、原理图 ×2 —— 恢复前已投递过的 `[delegate-finish]` 载荷被当「未投递」重放，叠加合并轮新正式回执，同一载荷重复到达根会话。
- 分支：`delegate/delegate-nebflow-e38c5dda` @ `815cad90c`（已并 main@`421fed987` → 合并点 `1b18323e0`，零文件重叠）。
- 门禁：相关族 spec **180/180 全绿**（108 + 42 + 收殓族 36，含新增 8 判）+ boot-smoke（见 §6）+ 合并 main ✅。

## 1. 根因（代码实证 + 运行时实证双锚）

投递判定此前只有两腿：内存 `pendingEvents`（队列在 = 未投）＋ `DelegateDeliveryReceipts` 回执 ack（注入即确认）。两腿各有「已投被判未投」的缝，重启把缝放大成重复洪水：

**缝① 重投腿 ack 身份断链（重复的直接引擎）**
`DelegateDeliveryReceipts.handleOne` 重投时把事件 correlationId 改写为 `delivery-redelivery-<id>`；而 `ackEvent` 按事件 correlationId 去 `find(<改写 id>)` —— 查无回执（回执文件在 `<id>.receipt.json`）恒 no-op ⇒ **原回执永挂未确认** ⇒ `TaskStuckWatcher` 扫描拍的 sweep 按 `DelegateDeliveryAckGraceMs`（10min）节拍重投，直至 `MaxRedeliveries=3`。

运行时实证（`/Users/kaiyu/.stand/delegates/*.receipt.json`）：canvas/console-card/imagegen/机制双件 4 支回执 `acked=false, redeliveries=3`（上限打满）、原理图 `redeliveries=2`；根转录（Nebula `d7c3cb54`）中 canvas 两件间隔 ≈600s = grace 值逐字吻合。

**缝② 投递腿缺 ack/快照窗口（未确认件的来源）**
- `AgentProcessing` tools-complete 边界 drain 腿是投递面但 10-24 批只给 AgentFinishTurn/AgentIdle 接了 ack —— 忙碌父会话经此边界投出的终态回执永挂未确认（23:15 前的 canvas/imagegen 首件即经本面）。
- `flushHeldSideEffects`（先补注入第三腿）与压缩窗全量 flush（`drainQueuesAfterCompaction`）同为无 ack 投递腿。
- 已投件与 injection-queues.json 快照之间的窗口（缺 persist 腿 / 崩溃点）：重启后 `RecoverPersistedQueues` 把文件里的已投件当未投重放进 `pendingEvents`，watchdog 催注 / 先补注入腿再次注入。根转录第三件 imagegen 呈 `<system-reminder> 1. …` 编号形态 = held 批量注入器（催注/先补注入）的重放笔迹。

任务书假设「投递状态仅存内存」需修正为：**判定读数分散且互不闭合**——ack 落盘但部分投递腿不写、重投改写身份后判不中、队列文件快照与投递无原子性、恢复重放闸不查任何投递标记。

## 2. 修法（作者令四条逐条）

### ① 投递标记持久化 —— `DeliveredReceiptLedger`（新件，core/processor/DeliveredReceiptLedger.scala）
每会话一份**已投递事件身份集**：`<dataRoot>/sessions/<sid>/delivered-receipts.json`（AtomicJson 原子写 + 全局写锁；fail-soft 只 WARN）。
- 键 = `correlationId`（跨重启稳定：delegate 终态=子会话 id、重投=delivery-redelivery-<id>、mq-recovery=mq-recovery-<id>）。**无 correlationId 的事件不入账不过滤**——载荷摘要不具身份唯一性（同文多次通知合法，实证 delegate-nebflow-59f171d8 队列三连同文件），判重即误杀（语义零回退约束）。
- 容量 `MaxEntries=256` drop-oldest：标记集只服务重启不重放窗，出窗回退 at-least-once（有界代价换文件不涨）。

### ② 注入侧幂等（双保险）
- **恢复重放闸**：`AgentIdle.RecoverPersistedQueues` 重放前 `filterUndelivered` 滤除已投件（日志行 `queues-recovered … replayFiltered=N`）——重启不重放的唯一咽喉。
- **催注前闸**：`DrainHeldReceipts` idle 强制补投先滤已投件再注入（防陈旧内存/旧版快照），全滤空时持久化清队。
- **注入即标记**：全部投递腿标记——AgentIdle 直投/#418/批汇聚腿、AgentFinishTurn 边界、AgentProcessing tools-complete 边界、压缩窗 flush 两分支、`flushHeldSideEffects`、催注 force-drain。与 `ackEvent` 同点同序（「注入即确认」既有教义）。

### ③ 语义零回退
- `HeldDeliveryPolicy`（退避 10min→×2→30 封顶、attempts 穷尽、exhausted 终态）与 watchdog 判定面**一字未动**。
- `+u` flush 顺序、persistQueues 时序、未投回执的恰好一次投递全部保持：过滤只作用于**标记在案**的件；读失败 fail-open 全量放行（簿记面故障绝不丢件，R3b spec 钉死）。
- 重投机制本体保留（ legitimate 第二投递面），只修其 ack 归位——`ackableReceiptId`：metadata `redeliveryOf` 优先、`delivery-redelivery-` 前缀剥离次之、原 correlationId 兜底；普通事件行为逐字不变。

### ④ 回归 spec（新增 8 判）
- `DeliveredReceiptLedgerSpec`：**R1 已投→模拟恢复→不重放**（含混合队列单判定）；**R2 未投→恢复→恰好一次**（首闸放行→标记→第二次恢复滤除）；**R3 崩溃窗口契约**（无 id 恒放行防误杀 / 坏文件 fail-open）；P1 跨「重启」往返幂等 / P2 容量封顶 / P3 纯函数分区。
- `DelegateTerminalDeliverySpec` 增两判：`ackableReceiptId` 归位（meta/前缀/原值三形态 + 词表闸）；**重投件 ack 原回执 ⇒ 重投链第一拍终止**（needsRedelivery 翻假机检等价）。

## 3. 崩溃窗口语义（显式声明）

严格跨崩溃 exactly-once 不可达（标记与转录落盘无法原子）。本批取与既有「注入即确认」同点的标记：
- 标记后、turn 落盘前崩 ⇒ 恢复闸按标记跳过 = 该件不重放（ack 先例的既有代价类，非新增；且多数投递腿 persistQueues 本就先于 pipeLlmCall，该窗口改前即存在）。
- turn 已落盘、标记未写崩（理论窗口）⇒ 重放一次（at-least-once），与改前逐字同。
- 两窗都远窄于 #886 的慢性重放（每次重启必现 ×N）。

## 4. 验证（真实运行）

| 族 | 判数 | 结果 |
|---|---|---|
| 新增（DeliveredReceiptLedgerSpec 8 + DelegateTerminalDeliverySpec #886 两判） | 10 | ✅ |
| 回执/恢复族（HeldDeliveryWatchdog/HeldReceiptStore/SubagentCrashRecovery/TaskStuckWatcher） | 36+15 | ✅ |
| 队列/边界族（AgentActorCompaction/InjectionBatchDrain/HeldDeliveryFlush/SubAgentBarrier/CompactionQueueStore） | 57 | ✅ |
| 生产者族（MergeQueueBootSweep/MergeQueueRestartReminder/DelegateRevival/DelegateTerminal×3/MergeQueueStall/MergeWindowCare） | 42 | ✅ |

相关族合计 **180/180 全绿**（合并 main 后的树上重跑）。

## 5. 变更清单

- 新增 `src/main/scala/nebflow/core/processor/DeliveredReceiptLedger.scala`（持久标记台账）
- 新增 `src/test/scala/nebflow/core/processor/DeliveredReceiptLedgerSpec.scala`
- 改 `DelegateDeliveryReceipts.scala`（ackableReceiptId + ackEvent 归位；RedeliveryCorrelationPrefix 常量）
- 改 `AgentIdle.scala`（RecoverPersistedQueues 重放闸；DrainHeldReceipts 前闸+标记；三条 idle 投递腿标记）
- 改 `AgentFinishTurn.scala`（边界 drain 标记）
- 改 `AgentProcessing.scala`（tools-complete 补 ack+标记；压缩窗 flush 两分支 ack+标记）
- 改 `AgentActor.scala`（PostCompactDrain 增 drainedEvents；flushHeldSideEffects ack+标记）
- 改 `DelegateTerminalDeliverySpec.scala`（#886 两判）

## 6. boot-smoke

**PASS**（2026-10-26 00:49，本分支合并树自建 jar `a735d7a6…`，隔离数据根 + 端口 8089）：

```
[smoke] ready: GET /api/health -> 200 after 5s
[smoke] portal: GET / -> 200 (44449 bytes)
[smoke] ws: handshake HTTP/1.1 101 Switching Protocols (RFC6455 101, bearer token)
BOOT-SMOKE: PASS jar=stand-assembly-2026.10.21-beta.2.jar port=8089 boot_ready=5s checks=11
```

全量原文见同目录 `boot-smoke-output.txt`（`scripts/boot-smoke.sh` 铁律三层守卫不动在产 6138/8080；teardown 零残留）。引擎起得来——今晚感知批的教训门已过。

## 7. 假设与边界

1. 任务书点名的 SubagentCrashRecovery/HeldDeliveryWatchdog 为「恢复路径」嫌疑面：代码实证两件都不直接重放回执（前者管 transcript 悬挂 tool_use + 孤儿子会话，后者只按台账催注）；真凶是 RecoverPersistedQueues 重放闸不查投递标记 + sweep 重投的 ack 断链。watchdog 的催注只是把重放进来的已投件再次注入的**执行手**，修后恢复闸滤除后催注自然空转（幂等 no-op 路径既有）。
2. 重复计数的构成：首件（原始投递）+ 10min 节拍重投（grace 值逐字对上）+ held 批量注入器的编号 reminder 形态（第三件 imagegen 实证）。两缝同修后三源全断。
3. 存量已打满上限的 5 支回执（redeliveries=3/2）已 Retain 停投，无需迁移；新标记只对修复后的投递生效（无回填——回填无法区分「真已投」与「ack 丢失」）。
4. 仓内 fmt 门（scalafmtCheckAll）当前 165 文件红 = 既有 config 漂移，与本批无关、未越权代修。
