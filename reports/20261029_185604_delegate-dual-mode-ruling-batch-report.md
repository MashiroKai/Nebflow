# delegate 双模式补全批 REPORT——作者 2026-10-29 18:12 补充裁定落地 + fail-open 根因修复

批：作者 2026-10-29 18:12 补充裁定（六件）+ fail-open 根因追问（逐字：「还有归因为什么会出现fail-open的现象，有LLM兜底，应该是不会出现fail-open的。不然主agent都在正常使用llm，怎么会fail-open呢」）。
设计稿：[[design/20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5]]（本批回写 §0/§2.3/§3.1/§4.2/§5.1/§5.7/§8.1/§8.3）。
分支：`delegate/delegate-nebflow-52fbf252`（基于 main `982625c1b`）。

## 1. 现状盘点结论（先做增量不做重做）

main 上双模式**主体已落**，本批全部为增量：

| 面 | 盘点结论 | 证据 |
|---|---|---|
| 模式选择器 | **已落**。DelegateModeMachine（JeV 门→JeV 问对 need_dispatcher/fit_dispatcher→LLM fallback→fail-open kernel 可旋）+ DelegateModeAllocator 端口（core 零 llm import） | `llm/decision/DelegateModeMachine.scala`、`core/delegate/DualMode.scala:98-124` |
| Delegate 工具 mode 参数 | **已落**。schema enum kernel\|dispatcher\|auto；非 auto 即权威零模型调用 | `DelegateTool.scala:107-111` |
| mode-decision 审计行 | **已落**。总闸开才写（Inert 零行） | `DelegateTool.scala:764-782` |
| 工具面分叉 | **已落**。kernelFace 摘 Subagent/AgentFlow + node 四件截断（Q2 已采纳落地）；dispatcherFace 摘 Subagent 留 node 四件；工具内第二保险 DUAL_MODE_FACE_REMOVED | `AgentSessionExecution.scala:2483-2500`；spec `DualModeToolFaceSpec` F1-F6 |
| 灰度开关 | **已落**。`delegate.dualMode.enabled`（nebflow.json + sys prop 热读，缺省 false=Inert）+ `failopenMode` 旋钮（缺省 kernel） | `core/delegate/DualMode.scala`；spec `DualModeGateSpec` G1-G5 |
| mode 字段落记录 | **已落**。SubAgentTask.mode（缺键容忍解码）+ DelegateRegistry.Record.mode + modeFaceOf 读面（盘上有 mode 但闸关 ⇒ None 不分叉） | `DelegateTool.scala:1081`、`DelegateRegistry.scala:156,416` |
| 模式二机制 | **已落**。ChainWrite 新闸 NODE_PLUGINS_DISPATCHER_ALLOCATED（plugins 键在场整链拒）+ worktree/merge seal 同窗；逐 node 分配前移链登记时（0 spawn 阶段 allocatePreparation(node.task) 物化进 NodeDef.plugins；解析/注入失败整链显式拒、分配失败 fail-open 照走）；ACK 逐 node 附分配摘要行；writeScope 可选键 + 同时活跃对相交 write-scope-collision WARN 审计（软闸不拒）；NodeEdit 分发器作用域同闸 | `ChainWriteTool.scala:288,328-349,441-444,478,500-516,546-566,584-627`；spec `DualModeDispatcherScopeSpec` D1-D7 |
| 分发器本体零插件 | **已落**。spawn 腿跳过 allocatePreparation，delegate-spawn 行 `allocation=dispatcher-off` | `DelegateTool.scala:788-794,1034-1039` |
| stand-plugins CLI（Q7） | **已落**。`Defaults.PluginDiscoveryLine` 进 assembleBrief（全体 delegate 含分发器） | `DelegateTool.scala:147`、`Defaults.scala:137` |
| flowmap 生命周期 | **已落**。终态 summaryFlush→unmount→宽限删除；chain-archived 事件先行 | `DelegateTerminal.scala:624-676`、`DelegateFlowMapLifecycle.scala` |
| **worktree 判定机** | **已落但方向与 18:12 裁定相反**：`judgeWorkspace && !isDispatcher`（dispatcher 本体跳过）——本批①推翻 | `DelegateTool.scala:828`（改前） |

R1/R3/R4/R5 映射表兄弟批不在本批范围（任务文明确由其他在飞批次承接）。

## 2. fail-open 根因（逐字级归因，作者会追问）

### 2.1 现场取证（~/.stand 实盘）

- `chains/*.json` 609 件：**232 件** delegate-spawn 行带 `"allocation":"fail-open"`；三态分布 = **jev 442 / llm-fallback 25 / fail-open 27**（spawn+step 行合计）。**mode-decision 行 = 0 条**（本实例总闸从未开过）⇒ 作者看到的 fail-open 全部在**插件分配腿**，不在模式选择器。
- `logs/jev-allocation.jsonl` 773 行：timeout 75 / failure 217 / allocated 481。失败 top 原因：
  1. `decision API transport error: HTTP 451 {"title":"Typesafe is not available in y..."}` ×**101**（JeV 端点 geo-block，`jev.enabled=true` + provider=typesafe-jev）；
  2. `llm allocator: call failed (no reply)` ×**89**（LLM 兜底腿也败——真因被吞，见 2.2）；
  3. `llm allocator: TimeoutException` ×72；
  4. 其余：handshake terminated / HTTP 500 / 529 / malformed response 少量。

### 2.2 真因链（三层）

1. **诱因**：JeV 腿不可用（geo-block/超时/传输错）→ 按设计落 LLM 兜底——这半条是**正常回落**，不是病。
2. **真正病灶（作者追问的答案）**：LLM 兜底腿的候选解析 `LlmAllocator.candidateFrom` 只取**全局候选链的链头一个**（`registry.getCandidates().map(_.headOption)`），单发一次、失败即弃。而主 agent 的请求路径走**整链遍历**（preferred→fallbacks→储备层，`ProviderRegistry.getCandidatesForAgent`，2026-09-21 储备层批专门把「有效链深 1」打成结构不可达）。于是：**链头那个候选恰好坏掉时，主 agent 换链上后备照常用 LLM，兜底腿却已在链头撞死** ——「主 agent 都在正常使用 llm，怎么会 fail-open」的机制面答案。
3. **归因失真（为什么台账看不出真因）**：调用缝 `case Left(_) => None` 把真错吞成「call failed (no reply)」；PluginInjection 把 `FellBack(非 Off)` 一律记 `fail-open` ——无候选/网络错/超时/回文不可用四种情况同词，可恢复错误与真结构态不可分。

### 2.3 修复（同病同修：插件分配腿 + 模式选择器腿）

| 修 | 内容 | 落点 |
|---|---|---|
| F1 候选链整链遍历 | `candidatesFrom`：返回**整条候选链**（`judge.utilityModel` 钉首位+链去重；ref 不可解/未配 = 全链）；`chainCall` 调用缝：逐候选 sendMessage，坏候选 WARN 落 providerId/model/异常类后试下一个，**全链败才 None**——与主 agent 同一遍历哲学，`fromRegistry` 与 `DelegateModeMachine.face` 双消费 | `LlmAllocator.scala`（companion 重写） |
| F2 归因精确化 | `JevFallbackKind` 增 `LlmError`（wire `llm-error`，调用层失败=候选链耗尽/传输异常——可恢复）与 `ParseFail`（wire `parse-fail`，回文不可用——可恢复）；`fail-open` 自此只指「无可尝试之物」（无候选/face 未装配）的结构态。分配腿台账词（delegate-spawn `allocation` 字段）与模式腿 leg 词同步分野 | `JevFallback.scala`、`PluginInjection.scala`（FellBack 分派）、`DelegateModeMachine.fallbackLeg` |
| F3 真错留痕 | 调用缝逐候选 WARN（原来 `Left(_)` 静默吞）+ 审计 reason 携「candidate chain exhausted」指向 WARN 线 | `LlmAllocator.chainCall` |

修后语义：**主 agent LLM 正常（链上至少一候选可用）⇒ 兜底腿必答 ⇒ 选择器/分配器不 fail-open**；llm-error/parse-fail 仍在时 mode/分配取 failopen 方向但台账各归各，可恢复性一眼可辨。

### 2.4 范围外注记

`DelegateJudgeMachine`（终态判定机 SmallJudge）的 utilityCandidate 是另一条同族腿，本批未动（任务文点名「选择器+插件分配腿」）；若作者要同修可另派小批，改法即 F1 同款。

## 3. 六件落点（文件:行）

1. **worktree 裁定落地（①）**：`DelegateTool.scala:828` 邻域——`judgeWorkspace && !isDispatcher` → `judgeWorkspace`（作者逐字裁定注解在案）。连带：schema mode 文案去「no worktree」改「goes through the same worktree judge as kernel」（`:110`）；`DispatcherDisciplineLine` 座位共享精确化（`:157-177`）；ChainWrite ACK 提醒改「nodes share one workspace, node worktrees sealed」（`ChainWriteTool.scala:564-566`）。**节点侧与终态侧零新增代码**：`DelegateFlowHosts.mount(engineCwd = effectiveWs)`＝分发器座位（worktree 判是时即 worktree 路径）⇒ NodeEngine workspace 随座位 ⇒ 节点 projectRoot 自然落 worktree 内（共享非嵌套）；`DelegateTerminal.holdForMerge` 全由绑定驱动（`rec.branch/baseBranch/worktree`）⇒ 有分支即照走合并判定机/队列。设计稿回写：§0、§2.3#8、§3.1 图注、§5.1、§5.7 威胁模型+MergeQueue 段、§8.1 表、§8.3。
2. **kernel 工具面（②）**：`AgentSessionExecution.scala:2497-2501`——kernel 腿显式减 `NodeReportToolDef.Name`（保险销）；现状事实源 = node_report 经 RootExclusiveTools 防声明逃逸剥离+flowNodeSession 尾段专属挂载+工具内 flowNodeId 空即拒，本就不达 delegate 面（挂载面+身份拒绝双保险既有），本批把裁定钉成显式减法+spec。Q2（kernel 连摘 node 四件）盘点确认**已落**，未重做。TaskBoard/delegate_report/AskUser kernel 保留不动（F1 断言）。
3. **分发器提示词两段（③）**：`DelegateTool.DispatcherDisciplineLine`（`:157-177`，单点可调）——①subagent-via-nodes：单侦察/子任务=单节点链或入链节点、task SELF-CONTAINED（节点只见自己 task 文）、结果经 out 边/完成通知回流、判定用 verifier+(fail)worker:loop 回边；②整链一次编写：ONE ChainWrite call（多节点复杂链也是一次）、never assemble node-by-node、NodeEdit refinement-only；③写域相交 deps 串行（§5.7 纪律层，原有保留）。设计稿 §2.3#3「9.19 无此能力」表述按现状修正（ChainWrite 已收编 `e8d5f98f3`）+作者逐字入档。
4. **fail-open 根因修复（④）**：见 §2。落点 `LlmAllocator.scala`（candidatesFrom/chainCall/分类）、`DelegateModeMachine.scala`（face 装配+fallbackLeg leg 词）、`JevFallback.scala`（enum+wire+record 分支）、`JevShadow.scala`（exhaustive match 补两臂=尝试已发生）、`PluginInjection.scala`（allocation 词映射+值域注释）。
5. **模式二机制补全（⑤）**：盘点确认**全量已落**（新闸/分配前移/ACK 摘要/writeScope WARN/dispatcher-off/CLI），本批零改动——唯 ACK 提醒措辞随①精确化（D3 spec 同步）。
6. **灰度开关与审计（⑥）**：盘点确认**全量已落**（总闸/failopenMode/mode 字段/mode-decision 行），本批零改动。

## 4. spec 读数

新增/修订断言（文件→条）：

- `DelegateModeMachineSpec`：垃圾回文 leg=**parse-fail**（改）；调用缝全链耗尽 leg=**llm-error**（新）；缝异常 leg=llm-error+异常类入 reason（新）。
- `LlmAllocatorSpec`：③态改钉 `FellBack(LlmError, "candidate chain exhausted…")`（改）；**chainCall 四条**：头候选坏→第二候选答出且两候选皆尝试、全链败→None 且每候选有尝试痕迹、未知 provider 候选跳过不炸缝、空回文候选不截链（新×4）。
- `DualModeToolFaceSpec`：F1 增 node_report 不在 kernel 面；F2 增 node_report 现状事实源不达 dispatcher 面（本批不引入变化的事实钉）；F7 增 schema 无「zero plugins, no worktree」且带「worktree judge」；F9/F10 新增：纪律段 subagent-via-nodes 四要点 + 一次成链/禁拼链/座位共享精确化逐词锚。
- `DualModeDispatcherScopeSpec` D3：ACK 提醒断言更新为新措辞。

运行结果：sbt compile + 四个 spec 类（DelegateModeMachineSpec / LlmAllocatorSpec / DualModeToolFaceSpec / DualModeDispatcherScopeSpec + DualModeGateSpec 回归）——读数见 commit 信息附注与 §6。

## 5. 关键假设与边界

- fail-open 归因基于本实例（作者自用 ~/.stand）审计取证；其他实例若 fail-open 构成不同（如纯 parse-fail 占多），F1 链遍历仍成立但占比读数会不同。
- `chainCall` 遍历无墙钟预算（遵守 2026-10-19 零超时拍板）——N 候选串行最坏 N 倍耗时，由探活/审计表达（与既有纪律一致）。
- candidatesFrom 的单测未覆盖 `getCandidates()` 分支（该面会读写真实 HOME——ProviderChainDepthSpec 先例同款纪律，不触发）；链遍历语义由 chainCall 三条 spec 钉死。
- dispatcher 本体 worktree 行为（spawn 链）无轻量 spec harness（spawnBackground 无测试缝）；以①的注释锚+文案 spec+终态面绑定驱动零新增代码（结构上判定机照走=kernel 同路径）交付，实机验证依赖作者开闸灰度。
- 232 件历史 fail-open 行不回写重分类（审计行 append-only，只对新派发生效）。

## 6. 验证

- `sbt compile` 绿（main 667 源）；`Test / compile` 绿（913 源）；受影响 8 个 spec 类全绿：**Total 79, Failed 0, Errors 0**（DelegateModeMachineSpec / LlmAllocatorSpec / PluginAllocatorFallbackSpec / DualModeToolFaceSpec / DualModeDispatcherScopeSpec / DualModeGateSpec / DualModeGrayReleaseSpec / JevShadowSpec——后三类为回归面）。
- 总闸 off=现行为：Inert 断言既有（G1/F3/D6）未动、全部分叉判据仍以 `delegateMode=None` 失效——本批唯一闸关面触碰 = 无（①②③④全部在 mode 分叉/分配缝内，闸关时 delegateMode=None 走原路径逐字节）。
- docs vault 校验：`check_wiki_docs.py` 对本批改动零新增违规（现存 3 处 orphan-doc 为 main 既有，stash 对照验证）。
- 总闸 off=现行为：Inert 断言既有（G1/F3/D6）未动、全部分叉判据仍以 `delegateMode=None` 失效——本批唯一闸关面触碰 = 无（①②③④全部在 mode 分叉/分配缝内，闸关时 delegateMode=None 走原路径逐字节）。
