# G5：压缩摘要的视觉内容保留 — 设计方案（spec only，未实现）

> 2026-08-15 · Backend（闲时批处理）· 源自 ~/.nebflow/plan/Nebflow/20260814_vision-support-audit.md G5
> 状态：**方案 A + 决策点推荐值已实施**（commit 2ffb99ab，分支 feat/g5-compaction-vision，2026-08-15）——Epilogue 共享 Rules 加图片保留条目（三个 profile 同时生效），固定格式 `[图片: <path> | <描述或未描述>]`；死代码 CompactUtils.stripImages 已删。方案 B 挂起（触发条件见 §3）。

## 0. 前提更正（相对审计报告）

审计 G5 的描述——"CompactUtils.stripImages → 摘要子 agent 只见 `[image: mediaType]`"（CompactUtils.scala:11-20）——**基于过时的架构认知**。在当前 HEAD（85053fa7）核查：

- `CompactUtils.stripImages` **零调用方，是死代码**（全仓 grep 仅定义处一条；唯一活引用在 interface.scala 的同名函数，语义不同）。
- 当前压缩是 **inline 架构**（CompactService.scala:10-22）：压缩 = 同一 agent、同一模型链、追加 `buildCompactReminder` 的一个普通 turn，无独立摘要请求、无消息预处理。图片块的走向由 **interface.scala:145 的 effectiveVision 门控**决定，与普通 turn 完全一致。

因此 G5 的真实形态如下。

## 1. 现状分析（当前架构下图片在压缩中的实际命运）

压缩 turn 发送 `state.messages :+ compactReminder` → `pipeLlmCall` → interface 层：

| 会话模型 | 压缩 turn 中图片块 | 摘要是否了解图片内容 |
|---|---|---|
| vision=true（B3 后默认乐观） | **原样发送**（完整 base64） | 能看到——无 G5 损失，但重新发送全部图片 base64（token 成本，TokenEstimator 按 1500 tok/图） |
| vision=false（含 B3 运行时降级后） | interface stripImages → `[image omitted: model does not support vision]` | **看不到——G5 损失发生** |

压缩完成后 `compactedMessages` **整体替换** state.messages（AgentActor.scala:1266）：无论哪种情况，图片块本身从活上下文中消失（这是压缩的目的）。上传文件仍在 `~/.nebflow/uploads/<sid>/` 磁盘持久。

**缓解通道（已存在）**：用户传图时 WebSocketRoutes 同时落 `ContentBlock.Text("[用户附加图片: <path>]")` 与 Image 块。Text 块是普通文本，若摘要 prompt 覆盖到就会保留路径 → 压缩后 agent 仍知道"曾有过图、在哪"，可用 ReadTool 重读（路径 + 文件都活着）。**当前三个 CompactReminder 模板（Root/Manager/Worker）均无任何关于图片保留的指示**——路径是否进摘要全凭模型自发，不稳定。

**G5 损失的准确定义**：非 vision 会话压缩后，摘要既无图片内容描述、也大概率无路径引用；agent 对"之前看过什么图"失忆。

## 2. 方案对比

### 方案 A — CompactReminder 模板加图片保留指示（纯 prompt，零代码）

三个 reminder 模板加一段，大意：

> 摘要中必须保留图片附件的线索：若消息含 `[用户附加图片: path]`，在摘要相应条目中保留该路径并注明"图片内容未压缩保留，可 Read 重读"；若你能看到图片（vision 模型），用一句话概括其关键内容并连同路径一起保留。

- 优点：零代码零 schema；对 vision 会话额外获得"摘要含图片描述"（当前纯凭运气）；对非 vision 会话兜底保留路径 → 可重读；与 B3 双降级机制天然配合（降级后的会话摘要自动走路径保留分支）
- 缺点：prompt 遵从性非 100%；非 vision 会话仍无图片内容描述（需 agent 主动重读才有）
- 成本：~10 行模板改动

### 方案 B — vision 摘要者选择（审计 :149 原建议，代码改动）

含图会话触发压缩时，若会话模型链无 vision 能力，压缩 turn 改用全局链中 vision=true 的模型执行（`pipeLlmCall` 已有 `model` 覆盖参数的管道）。

- 优点：非 vision 会话的摘要也能包含图片内容描述（配合方案 A 的模板指示）
- 缺点：① 压缩 turn 与会话模型不同 → **丢失 system prompt + tools 的 provider 缓存命中**（inline 架构的核心收益，CompactService.scala:17-21 明确列出）；② 需要跨链 vision 候选解析 + 失败回退逻辑；③ 与 B3 运行时降级的交互：刚被降级的模型可能恰是全局唯一 vision 候选；④ 图片内容描述的价值场景本身边缘（审计定级"低"）
- 成本：中等（模型解析 + 覆盖传递 + 回退，估 60-100 行 + 测试）

### 方案 C — 占位符→图片描述替换（压缩前预扫描）

压缩 turn 前插入一步：对历史中每个 Image 块调 vision 模型生成一句话描述，替换为 Text 块，再走正常压缩。

- 优点：描述永久落上下文，不依赖摘要遵从
- 缺点：每图一次 LLM 调用（延迟 + 成本）；实现最重；与"压缩是紧急降载路径"的定位冲突（触发时上下文已紧张，再串行 N 次调用放大风险
- 成本：高。**不推荐**

### 方案 D — 压缩后保留图片块

违反压缩目的（图片正是 token 大头，G6/前端已把单图压到 ≤1920px/5MB，但 1500 tok/图 × N 张仍是主要负载）。**否决**。

## 3. 推荐

**方案 A 单独实施**（可并入任意后续改动，~10 行模板 + 无测试负担——模板是纯字符串）。方案 B 挂起，触发条件：用户实际使用中出现"非 vision 会话 + 图片密集历史 + 频繁压缩"的组合，且方案 A 的路径保留被验证不够用。

理由：G5 审计定级"低（边缘场景）"；缓解通道（uploads 持久 + 路径 Text 块 + ReadTool 重读，G6 之后还保证重读时自动降采样）已覆盖大部分恢复需求，方案 A 把"凭运气"变成"有指示"，收益/成本比最高。方案 B 的缓存损失直接伤害每次压缩（高频路径），换的是边缘场景的内容描述，得不偿失。

## 4. 决策点（需用户拍板）

1. 是否只做方案 A？（推荐：是）
2. 方案 A 的模板指示是否对 Worker/Manager profile 也生效，还是只 Root？（推荐：三个都生效——Worker/Flow agent 同样会收到带图会话）
3. 摘要中的图片条目格式：自由文本 vs 固定格式（如 `[图片: <path> | <一句话描述或"未描述">]`）？（推荐：固定格式起步，便于压缩后 agent 解析重读；格式写进 reminder 模板即可，无需 schema）
4. 方案 B 是否挂起而非否决？（推荐：挂起，写入本文档触发条件即可）

## 5. 涉及文件（若采纳方案 A）

- `src/main/scala/nebflow/core/compact/CompactService.scala` — RootCompactReminder / ManagerCompactReminder / WorkerCompactReminder 三模板各加图片保留段（:246/:287/:321）
- 顺手清理：删除死代码 `CompactUtils.stripImages`（CompactUtils.scala:11-21，零调用方）——或保守起见留待用户确认后删

---
Sources: 审计报告 G5（:137、:149）；CompactService.scala inline 架构注释（:10-22）；interface.scala:145 effectiveVision 门控；WebSocketRoutes 附件双块落库（[用户附加图片: path] Text 块）；AgentActor.scala:1266 compactedMessages 整体替换。
