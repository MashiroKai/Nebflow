# pi-logic 接主链轮 A 接线计划（L-6=逐位点织入 · 铁律首 commit）

> 全文：`docs/20261104_202500_scala-pi-logic-wiring-a-plan__delegate-nebflow-9db9d0d1.md`
> （docs/ 被 .gitignore——本件为入库跟踪摘要；基线 `9004fe212`；对位设计方案 v1 §Ⅳ.3/§Ⅴ/§Ⅵ。）

## 总则

1. **现主链仍是驱动者**（Actor 消息循环）；pilogic 事件/位点/序域/策略位/崩溃兜底以适配层在真实时刻就位——每点行为可对照、每步可回退、无双循环分叉（L-6 逐位点织入，技术等价类默认，收尾报告单列理由）。
2. **行为等价口径**：既有 WS 帧/日志/消息流/时序逐字节不变；新增仅 (a) `pilogic-*` 审计行（logAgentEvent）+ (b) KernelEvent 影子写（db appendEvent，DbHost.peek 只查不开，best-effort——db 接线 C5 先例同形）。
3. **可信双定义（作者 11-04 20:06）**：安全性=测试唯一凭证（72+全量零回归+每接线点 ≥1 测）；功能完备性=pilogic/db 可信基、上层单向引用、发现须自造原语即停工上报。
4. 红线：db/llm/build.sbt 零触碰；pilogic 叶子性+A-3/A-4 门保持。

## 接线期四件

① `pilogic/SiteEvents.scala`：29 位点强类型输入面（wire 形逐字节对齐 SitePayloads 现状，编解码单源；Interceptor/Observer 保持 Json 载体=hook 边界 wire 协议）。
② `agent/PiLoopWiring.scala` 内 `PiEventBridge`：audit 面生效（LoopEvent 子集→审计行+KernelEvent 三型影子写）；ws 面翻译落地+测试钉、发帧闸门关（双发=行为变化）。
③ `pilogic/RunLedger`（不可变：runKey/runId/seqInSession 会话域跨 run 连续）+ AgentState 增 `pilogicLedger`（pingpong 同款无默认参法）。
④ `synthesizeTruncatedEvents` 接入真实崩溃点（forkTurn handleErrorWith / context-build 超时 / 工具管线失败 / Interrupt）。

## 十处接线（file:line @9004fe212）

| # | 点 | 织入 | 等价性 |
|---|---|---|---|
| 1 | AgentSessionExecution:538 pipeLlmCall | run 边界判定（ledger.runKey≠loopTurnKey⇒RunStarted+session.Started+KernelEvent）+TurnStarted/MessageStarted/MessageEnded | 帧零改动，仅审计行 |
| 2 | :584 turnId | TurnNumber 序域取号 + stale 判定类型化（AgentProcessing:54/:136） | 数值/时序逐字 |
| 3 | :1094 MaxTurnLlmCalls 闸 | RetryPolicy(maxAttempts=Some(Fallback.MaxTurnLlmCalls)) 归属显式化 | 阈值/异常/文案逐字 |
| 4 | :1163/:1170 LlmComplete/LlmFailed | PiLoopTranslator 映射表 + 完成点事件 | 消息投递零改动 |
| 5 | AgentFinishTurn:27-99 六分支 | 纯分类器 classifyLlmComplete（只读镜像）+TurnEnded 事件/位点（决策早于事件） | ladder 逐字不动 |
| 6 | AgentProcessing:638 ToolsComplete | tool.BatchSettled 位点+事件 | handler 零改动 |
| 7 | AgentIdle:724-726 静默吞（+AgentFrozen:851） | 吞前审计行 pilogic-late-event-discarded | 仍丢弃，可观测性升 |
| 8 | core/hooks 8 事件 | PiHookMapping 八条映射+PiHookAdapter 适配器（不注册进分派——外部 hook 已真跑，双发=副作用重复） | 零双发 |
| 9 | :1405 parTraverse 批 | PiToolExecutor 适配器+批腿 toolExecution.Started/Ended 事件；批本体不动（串/并=D-B 上报） | 并行度/权限序逐字 |
| 10 | llm/interface sendStream | PiProviderPort 适配（既有腿全复用注入） | llm 本体零改 |

## 分歧上报位（语义分歧，轮 A 不做）

D-A 驱动腿割接（AgentLoop.run 接管）；D-B 工具批串行(runBatch) vs 并行(parTraverse)——推荐 A=runBatch 增并行档；D-C bridge ws 面开闸（前端可见）。

## 三病灶

新代码零全局态（RunLedger 值传递）；重试预算归属显式；idle/frozen 吞分支有审计行。残留：三处 @volatile 本体、真轮帽（割接轮）、丢弃语义（分歧区）——收尾报告列清单。
