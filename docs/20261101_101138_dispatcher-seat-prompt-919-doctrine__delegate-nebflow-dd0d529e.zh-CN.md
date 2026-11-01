---
type: batch-report
status: final
batch: 分发器模式提示词优化批（9.19 纯编排口径）
executed-at: 2026-11-01 10:01-10:40
author-order: 作者 2026-11-01 10:00 令
---

# 分发器模式提示词优化批（9.19 纯编排口径 · 作者 11-01 10:00 令）

> **令**：分发器模式应参照 9.19 版 dispatcher 的设计——**它自己不干活**（纯编排：拆解/派发/收拢/推进度，不亲自执行任务工作）；据此对分发器模式（delegate dual-mode dispatcher 腿）的提示词按 9.19 口径优化。四步：考古 → 对照重写 → 两模式语义自查 → spec 钉注入面 + 回归合入。
> **本批只动提示词与 spec，零机制改动**（工具面/选择器/链闸全部原样）。

## 0. 结论（一段话）

现行 dispatcher 腿座位提示词（`DispatcherDisciplineLine`）只讲「编排四件怎么用」，**没讲分发器是谁**——而两模式共享的 delegate 面系统提示词（`BuiltinAgents.NebflowDelegatePrompt`）通篇 executor 框架（「You are the task's executor, dispatcher and manager in one」「Simple work → you do it yourself」「You are not a forwarder of work」），dispatcher 腿此前无任何覆盖句：框架冲突时基线框架赢，这就是「还在怂恿它亲自干活」的怂恿面。本批把座位段重写为五段纯编排纪律（覆盖句 + 零亲自动手 / 拆解 / 派发 / 收拢 / 推进度），开场覆盖句显式废黜共享面的 executor 层（「simple work → you do it yourself」在本模式读作「simple work → a one-node chain」）；kernel 腿 brief 保持逐字节不变（判红锚），两模式语义分界由此单点承载。spec 新增 F11a/b/c + I1-I3 钉死注入面与全部纪律点，旧钉短语（F9/F10）逐字保留不破。

## 1. 考古：9.19 版 dispatcher 提炼了什么

**版本锚**：tag `2026.9.19`（`git show 949ce9159:VERSION` → `2026.9.19`；[[20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5]] §2.1 记 tag 对应 merge commit `249a83958`）。考古对象 = 该树 `src/main/resources/seed/agents/project-dispatcher/system.md`（11 行全文，git 可复读）+ 该设计的现役后裔（今 `BuiltinAgents.DispatcherPromptTemplate`，project-dispatcher 定形 2026-09-20）。

9.19 分发器的编排纪律要点（逐条可锚）：

| # | 纪律 | 9.19 出处（system.md 原文/机制） |
|---|---|---|
| 1 | **纯编排身份**：`NodeList` 先读盘 → 读 Flow Map → 排节点干活；自身工具面 NodeList/NodeEdit/NodeCancel/Mail/TaskBoard——**无 Write/Edit，机制级零亲自动手** | system.md 首段 + Tools 行 |
| 2 | **拆解粒度**：一 node = 一个会话能完成的最小单元；`task` = 节点收到的具体任务内容 | system.md「One node = …」条 |
| 3 | **接令盘点**（order intake）：新令/改令先盘点全部在飞节点（全状态），逐个判 stale/冲突/前提失效，逐点显式处置（注入修正 / 取消接管 / 判临时） | system.md「On a new direction order…」段 |
| 4 | **派发经济**：worktree 只在并行可能冲突时建；同 kind 补充复用旧节点不另起链；独立任务积极并行 | system.md (1)-(4) 条 |
| 5 | **链式推进**：出错处置归分发器（引擎只保活）；修链走 `Mail node:<id>` 三态路由（running 注入 / wiring·pending 追加 / terminal 拒） | system.md NodeEdit/Mail 条；设计文档 §2.2 机制表 |
| 6 | **禁面**：合并节点复用纪律（≤4 worktree）、node 级 worktree 声明、FlowDag DSL（2026-09-06 已裁不复活）、achieve 逻辑（考古零命中，不引入） | 设计文档 §2.3 保留/裁掉清单 |

其中 #1 在 9.19 是**机制级**的（工具面没有写工具，想干也干不了）；delegate 双模式的 dispatcher 腿**共享 executor 全量工具面**（kernel 才摘编排面，dispatcher 保留 Read/Write/Edit/Bash + node 四件——`DualModeToolFaceSpec` F2）——**零亲自动手在双模式里只剩提示词一道防线**，这正是本批要钉死的那道。

## 2. 对照：现行缺什么（怂恿面清单）

对照面 = dispatcher 腿会话实际收到的全部模型可见文本：系统提示词（`NebflowDelegatePrompt`，两模式共享）+ brief（`assembleBrief` 产出 + `DispatcherDisciplineLine` 追加段，仅 dispatcher 腿）。

| # | 缺口 | 证据锚 | 后果 |
|---|---|---|---|
| N1 | **覆盖句缺席（主怂恿面）**：共享 delegate 面系统提示词的 executor 框架在 dispatcher 腿无任何废黜句 | `BuiltinAgents.scala:535-585`（「executor, dispatcher and manager in one」「Simple work → you do it yourself」「You are not a forwarder of work」）；旧 `DispatcherDisciplineLine` 只有首句「you do not execute it yourself」，与通篇框架一比一比即沉 | 框架冲突时基线赢：微活顺手自己干、修失败亲自修——链被架空 |
| N2 | **拆解纪律缺失**：无 node 粒度句（9.19 #2）、无 task 三要素、无拓扑定量 | 旧段只说 SELF-CONTAINED | 节点切分随手化；该一条链的拆成一节点硬扛 |
| N3 | **接令盘点纪律缺失**（9.19 #3）：失败/补充令到达时先干什么、怎么处置在飞节点，只字未提 | 旧段尾句只有「fix the chain via Mail/NodeEdit/NodeCancel」 | 盲改令：不看在飞就发修正，与在飞节点互相踩 |
| N4 | **收拢纪律缺失**：到达后综合什么、终稿算什么（dispatch summary vs 节点转录）无纪律 | 旧段只说结果回流 | 终稿复述节点输出，父会话读到转录而非结论 |
| N5 | **推进度语义未成文**（9.19 #5）：「修理 = 链编辑，绝不亲自上手」未成句 | 旧段无 | N1 的具体化病灶：失败节点亲自修 |
| N6 | **插件发现行冲突未消解**：brief 固定行 `[plugin discovery]`（`Defaults.PluginDiscoveryLine`）邀请「declare the needed plugin(s)」，与 dispatcher 零点名语义相抵；旧段只说 never name plugins，未点名消解那行邀请 | `Defaults.scala:136-137`；`DualModeDispatcherScopeSpec` D1（plugins 键=整链拒） | 分发器在终稿里声明插件（被 D1 闸挡在机制面，但提示词面自相矛盾） |

**保留不动**（已是 9.19 口径或后续作者裁定的正解，旧段逐字继承）：整链一次 ChainWrite / NodeEdit 只精修（2026-10-29 作者补令）、Subagent 摘除后侦察走链、verifier+`(fail)<worker>:loop` 判定式（无 achieve）、座位共享精确化（2026-10-29 18:12 裁定）、`deps` 写域串行化、零点名插件（D1 闸）。

## 3. 改动面

| 件 | 改动 | 锚 |
|---|---|---|
| `DispatcherDisciplineLine`（dispatcher 腿座位段，单点） | 重写为五段：①覆盖句+零亲自动手 ②拆解 ③派发 ④收拢 ⑤推进度；开场 `── Dispatcher mode: pure orchestration (this section overrides the executor framing of your seat) ──` | `DelegateTool.scala`（常量唯一写点 + `assembleBrief` 装配行唯一注入点，本批零改装配逻辑） |
| kernel 腿 | **零改动**：`dispatcherDiscipline=None` = 缺省参数装配，brief 逐字节不变（I2 逐字节相等锚） | `DelegateTool.assembleBrief` |
| spec | `DualModeToolFaceSpec` 新增 F11a（覆盖句/零动手钉）/ F11b（拆解·盘点·收拢·推进度逐点钉）/ F11c（两模式语义分界钉）；`DualModeBriefInjectionSpec`（新件）钉 I1 注入位形 / I2 kernel 逐字节锚 / I3 零泄漏 | `src/test/scala/nebflow/agent/` 与 `src/test/scala/nebflow/core/tools/` |
| 旧钉 | F9/F10 断言短语全部逐字保留在新段中（Subagent gone by design / SELF-CONTAINED / completion notifications / verifier+loop / ONE ChainWrite call / never assemble a chain node-by-node / not for building it / 座位共享 / `deps`；负钉「no branches and no worktrees」继续缺席） | F9/F10 原样通过 |

**两模式提示词对比自查结论（步骤③）**：kernel = 自己干——共享 delegate 面（executor 框架即其语义）+ 编排工具机制级摘除（F1/F5/F6）+ brief 与闸关时代逐字节同（I2）；dispatcher = 只编排——同一共享面**加上**本覆盖段废黜 executor 层（F11a）+ 编排工具在面（F2）+ 零插件（dispatcher-off）。两模式共享 system prompt 是既定机制形态（本批不改），语义分界由「机制工具面 + brief 覆盖段」两层承载，且两层判据同源（`modeFaceOf`）。F11c 加钉：座位段永不提及 kernel（模式互不指涉）。

## 4. 验收与回归

- `DualModeToolFaceSpec`（F1-F11c）、`DualModeBriefInjectionSpec`（I1-I3）、`SleepPollStartupRuleSpec`（brief 装配既有锚）、`ResetAuditSpec`、`DualModeDispatcherScopeSpec`（D1-D11 链闸/退役面——零机制改动对照组）全绿；`sbt compile` 通过。执行记录见 §6。
- 注入面事实表（本批后）：dispatcher 腿 brief = 工作座行 → 绑定行 → reset 警示 → report 行 → 等待铁律 → 插件发现行 → output 行 → **座位纪律段（本批重写）** → 任务正文 → 插件注入块；kernel 腿 = 同布局**无座位段**（逐字节=现状）。

## 5. 开放项（本批不实施，留痕）

1. **续聊腿模式继承**：`MailTool.continueDelegate`（内置执行器续起）调 `spawnBackground` 不带 record.mode → 总闸开时由选择器重判，未按「模式 = spawn 时终身属性」继承（设计 §3.2）。机制面独立小批，非提示词面。
2. **共享面长效解**：覆盖句是单点补丁；若未来再拆 delegate 面为 kernel/dispatcher 双稿（如 `NebflowNodePrompt`/`NebflowDelegatePrompt` 先例），本段可瘦身回纯编排纪律。属来源拆分批口径，待作者令。
3. **project-dispatcher（per-project 分发器）与本段同族比对**：project 侧工具面机制级零写工具（Bash 限 worktree+git），双模式腿是全量 executor 面——「零亲自动手靠提示词」的残余风险在双模式侧结构性更高，若实证违例率不可接受，升级路径 = dispatcher 腿工具面摘 Write/Edit（机制级，Q2 同族延伸裁定），本批不动。

## 6. 执行记录

- 考古读面：`git show 949ce9159:src/main/resources/seed/agents/project-dispatcher/system.md`（9.19 全文 11 行）；`BuiltinAgents.scala`（现役 project-dispatcher 定形稿）；[[20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5]] §2/§5.7（考古表与纪律层设计）。
- 改码：`DelegateTool.scala`（座位段重写 + Scaladoc 五段批注）；`DualModeToolFaceSpec.scala`（F11a/b/c）；`DualModeBriefInjectionSpec.scala`（新件 I1-I3）。
- 回归：见 §4（本批提交内附执行输出）。

## 相关文档

- [[20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5]] — 双模式设计（§2 = 9.19 考古权威表；§5.7 = 三层防线纪律层）
- [[glossary]] — 词表；[[index]] — 文档库入口
