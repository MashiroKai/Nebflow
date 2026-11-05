---
type: audit-report
status: published
date: 2026-11-01
scope: 消息身份牌（msgtokens）展开缝审计——设计声称的五个投递组包点逐点真行为实测 + 全引擎投递面扫描；断缝三处补齐 + order-377 提示词补课同批（作者 2026-11-01 09:20 令两件之②）
---

# 消息身份牌展开缝审计（作者 11-01 09:20 令②）

关联：[[index]]、[[20261031_084300_social-file-outbound-audit__delegate-nebflow-3001078c]]（社交出站腿——本审计裁定为二期范围的同族面）。

## 结论先行

1. **五个已声称投递点全部接通，无假接线**：MailTool.call（短名/`node:`/delegate 续聊/排队全腿单点，`src/main/scala/nebflow/core/tools/MailTool.scala:702` 附近 `msgTokensResolve`）、DelegateTool.spawnBackground（`DelegateTool.scala:1066`，简报装配后、`spawnSupervisedAdapter` initialPrompt 与首条 UserInput 同吃一份）、NodeDelivery.sendNodeMessage（`NodeDelivery.scala:241`，运行中注入/待启动补充/未达兜底三路由单点）、NodeStarter.buildInput（`NodeStarter.scala:323`，出口单点——任务正文与上游 result 同解）。机制本体（msgtokens 批 65c78366e）工作正常。
2. **但五个点此前只有注册表级回归（MsgTokenRegistrySpec 9 项），投递点组装面零行为测试**——「设计说接了」与「实测展开正确」之间没有证据链。本批补 `MsgTokenDeliveryPointsSpec` 七项真行为回归：每点真发引牌投递、断言收方收到展开全文 + 映射缺失时收方收到显式报错行，全绿。
3. **扫描发现三处同缝断点（五点清单之外、同为「引牌投递给其他会话」的组包面），已补齐**：
   - `NodeLoopRunner.verifyRound1Input`——loop 节点 verify 会话首轮输入由 `node.task` 独立组包，绕过 buildInput；任务引牌时 verify 收裸牌（worker 首轮经 buildInput 已解，verify 漏）。修复 = 出口 `.flatMap(resources.msgTokens.resolve)`（`src/main/scala/nebflow/core/project/NodeLoopRunner.scala:345` 附近）；轮 N≥2 短段 = 引擎模板 + verifier 意见（源自已解上下文），无需再解。
   - `BlockingSubagent.run`（Subagent 侦察/workflow 步首条消息）——`promptWithRule(prompt)` 直投子会话，引牌不展开。修复 = 投递前 `resources.msgTokens.resolve(prompt)`；`recordTask` 台账保持原文（台账 = 原文权威，消解只在投递关口）。
   - `SubTaskTool.spawnWorker`——adapter initialPrompt + 首条 UserInput + blocks 三处直投 `prompt`。修复 = 一份消解文本喂三处（崩溃重投 `BackoffSupervisor` initialPrompt replay 同吃已解文本）；recordTask 台账保持原文。
4. **查明不属缝（记录在案，防误报）**：SendMessage/社交出站腿（微信/飞书）——设计明示整腿二期（若 root 在渠道回复里引牌，用户看到裸牌；收方是人非会话，与 [[20261031_084300_social-file-outbound-audit__delegate-nebflow-3001078c]] 同族）；AskUserQuestion（面向用户本体，非会话投递）；FlowDagExecutor（实体 flow 的 `{{item}}` 模板是另一机制，输入来自预授权 flow 文件，非实时用户话语转发面）；MergeQueue merge-turn 简报与 DelegateRevival/BackoffSupervisor 重投（引擎组装文本或已解文本的重放，无裸牌源）；AgentIdle/AgentFrozen 自发转（同一会话内已收输入的队列重放）。
5. **提示词补课（同批之①）**：机制 10-31 上线但「引牌不引文」的用法没进提示词。补 `Defaults.MsgTokenUsageRule` 单点常量 + `PromptSections` order-377 条件段（isRootAgent，紧跟 order-376；order-375/376 同款存在性保证机制），文面 = 用户消息携牌、派发/转发引牌+一句概括不复述原文、引擎投递关口自动展开、例外=原文本身是交付物才贴全文；与 `MsgTokenRegistry.reminderContent`（每轮请求级牌宣告）同口径并存。顺带修掉 scaladoc order 表里 375 重复行。spec 双腿齐钉：prompt 注入腿 = PromptSectionsSpec（order-377 逐字/root-only/段序钉），展开腿 = MsgTokenDeliveryPointsSpec。

## 实测证据（七项回归，逐点）

| # | 投递点 | 收方面 | 引牌展开 | 缺失报错行 |
|---|--------|--------|---------|-----------|
| ① | Mail 短名腿（team lead 活会话） | 收方 ImmediateInput.text | ✅ 全文，无残留牌面 | ✅ 显式报错行随文本投递（不拒发） |
| ② | Mail `node:` 腿→NodeDelivery 运行中注入 | `[NODE-MESSAGE]` 注入文本 | ✅ | ✅ |
| ②' | 同上→待启动补充 | 节点 task「分发器补充」段 | ✅ | ✅ |
| ③ | DelegateTool.spawnBackground 简报 | 子会话首条 LLM 消息 | ✅（真发一条引牌 delegate 简报实测） | ✅ |
| ④ | NodeStarter.buildInput | 节点 spawn 输入 | ✅ 任务牌 + 上游 result 牌双解，`=== Node x ===` 段头保持 | ✅ |
| ⑤ | loop worker 首轮（buildInput）+ verify 首轮（本批修复） | 两相位 LLM 注入 | ✅（修复后 verify 首轮收到全文） | 同一 resolve 面（注册表级已钉） |
| ⑥ | BlockingSubagent 首条消息（本批修复） | 子会话首条 LLM 消息 | ✅ | 同上 |
| ⑦ | SubTaskTool worker（本批修复） | worker 首条 LLM 消息 | ✅；recordTask 台账保持原文 | 同上 |

## 五点之外的投递面全景（扫描记录）

- **已覆盖（传导）**：ChainWrite/AgentFlow 步 → 项目节点 → NodeStarter.buildInput（spawn 出口已解）；NodeEdit task 改写 → 同上；Mail `delegate:<id>`/`kernel:<id>` 续聊 → MailTool.call 单点。
- **二期范围**：社交出站腿（FriendMessage/SendMessage/Pop 渠道面）——msgtokens 批明示「社交腿整腿二期，本批只做引擎内」。
- **非缝**：第 4 条清单所列（引擎组装文本 / 预授权文件 / 面向用户本体的面）。

## 回归

- 新增 `MsgTokenDeliveryPointsSpec`（7 项）全绿；`MsgTokenRegistrySpec`（9 项）与 `PromptSectionsSpec`（含 order-377 新 2 项）全绿。
- 邻接套件：LoopNodeSpec / LoopExecutionLegSpec（verifyRound1Input 改动）、BlockingSubagentEndToEndSpec / SubagentTool 面、SubTaskToolSpec（spawnWorker 改动）、Mail 系（未动其主路径）全绿。
