# R8 — Flow 引擎两项核心增强设计：并行节点 + 结构化消息传递

> 纯设计文档，不改代码 · 2026-08-15 · 输入：用户观点（skill/flow 边界、并行 barrier、结构化判断）+ v2.1 后代码基线
> 代码基线：archive/scala @ 2179be7e（超时删除 85053fa7 已合入：无 wall-clock 超时、cancel 穿透、Mail 结构性禁用）
> 上游报告：/tmp/ecosystem-R4-flow-guide.md（其"单节点 flow 合法"主张已被用户否决，本报告 §1.4 修正）

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 并行表达 | 扩展 `onComplete` 为 `{"parallel": [...]}` 扇出 + 多入边节点天然 Barrier；不引入显式 edges 数组（保持 onComplete 内嵌路由的现有格式） |
| 执行模型 | 分支 fiber 模型：并行节点各起独立 fiber 走现有 runNode 递归，共享 FlowExecContext（Ref 化），汇聚点用**计数屏障**（等齐所有上游，绝不逐个触发） |
| 部分失败 | 默认 `abort`（一支失败→cancel 信号穿透停掉其余在跑 agent，flow 失败，与现有串行语义一致）；扇出可声明 `onFail: collect`（失败支产出占位结果，屏障照常放行，由下游 verdict 决定打回） |
| 结构化判断 | FlowReport 升级为**节点输出契约**：verdict 枚举（case keys）+ `slots` 类型化字段；switch 节点强制 FlowReport，工具级校验错→agent 当轮自我纠正；删除 substring 猜测（默认） |
| 消息传递 | 下游输入保持 `$x.output` 模板（显式契约），新增 `$x.slots.<field>` 字段级引用；汇聚节点按来源标注拼接由 input 模板自己声明 |
| 错位审计 | 2 个 flow 结构性断裂（永远以 maxLoop 失败收场）、research flow prompt 全部错位；academic-survey skill 实为 flow（483 行散文编排的并行调研-校验循环）；12 个 skill 中 10 个定位正确 |
| 单节点 flow | 按用户定论：**不合法**。加载时 reject 节点数 <2 的 flow（推翻 R4 §3.4） |

---

## 1. 现状核实（代码事实）

### 1.1 执行模型：严格串行，无并行原语

- 路由原语只有三种：`Goto(单目标)` / `Switch` / `Return`（`EntityTypes.scala:112-126`）。`onComplete` 是**单值路由**——一个节点完成只能去一个地方（Switch 选一个 case），**格式上无法表达扇出**。
- 执行是 `runNode` 递归：execute → handleResult → route → `runNode(target)`（`FlowDagExecutor.scala:226-271, :181, :206`），一次只有一个节点在跑。
- flow.json 没有显式 edges 字段；边由 onComplete 推导，前端 DAG 图由 executor 组装（`FlowDagExecutor.scala:294-308`、registerFlow `:352-364`）。因此"多前驱/多后继"在现有格式中**既无语法也无语义**。
- 节点间消息传递：`$task` / `$<nodeId>.output` 模板全文替换（`FlowDagExecutor.scala:72-81`），无截断无结构化；引用未运行节点时插入字面量 `[output of x not found]`（`:79`——entity-creator builder 首轮 prompt 里就有这个占位噪音）。
- 每节点全新 AgentActor + 独立 session（`dag-<flow>-<node>-<ts>`，`:411, :448-465`），结束即删 session（`:502`）。节点间零共享状态——这是"关注点强制分离"的特性，不是缺陷。

### 1.2 判定与收敛现状（v2.1 后）

- 超时已全删：无 wall-clock 超时，终止纯事件驱动（`:311-314` 方法注释、`:379-396`）；节点存活由 agent 内部超时（LLM 首 token/stream-idle、工具超时）保证。
- cancel 穿透：per-instance `Deferred` cancelSignal（`RunningFlowRegistry.scala:42-81`），`IO.race(resultDeferred.get, cancelSig.get)`（`FlowDagExecutor.scala:481-482`）+ Stop 穿透（`:488-492`）+ 节点间预检（`:229-231`）。bridge actor 竞态已修（Deferred 在 receive 内同步完成，`:430-437` 注释）。
- verdict 路由已有半结构化通道：FlowReport 工具（verdict+output，`FlowReportTool.scala:31-77`）→ FlowReportStore（session 键控，`FlowReportStore.scala:11-22`）→ `NodeResult.verdict`（`FlowDagExecutor.scala:503-515`）→ `VerdictFamily.matchCase` 精确→同族→substring 三级归一（`FlowSemantics.scala:110-136`）。
- **问题所在**：FlowReport 是可选的。无 FlowReport 时 `extractSwitchValue` 走 JSON 字段→文本 regex→**substring 容忍猜测**（`FlowDagExecutor.scala:567-601`），第 3 级靠"case key 恰好是输出子串"命中，warn 了但不失败——这正是用户说的"靠让 agent 输出文本判断肯定不对"。且 verdict 是单字符串，无多字段结构。
- Mail 对 flow agent 结构性禁用（`AgentCore.scala:915-919`）；FlowReport 按 category 自动注入（`:885-888`）。触发入口唯一：Delegate(flow=...) + flows 白名单（`DelegateTool.scala:188-224`）→ 一次性 FlowDagRunner → 结果 ImmediateInput 文本回传（`FlowDagRunner.scala:52-63`）。
- 无任何加载校验：parseFlowJson 只 decode（`EntityLoader.scala:80-94`）——不查环、不查可达性、不查终止路径（§4 两个断裂 flow 即证据）。

### 1.3 既有 7 个 flow 的事实核查

| flow | 结构 | 核查结论 |
|---|---|---|
| code-review | scanner→reviewer⇄fixer，2 个 switch，maxLoop=5 | 健康样板：verdict pass/fix 均有出口 |
| entity-creator | architect→builder⇄reviewer，switch pass/revise | 健康但 builder 首轮含 `[output of reviewer not found]` 占位噪音 |
| git-merge | scanner→evaluator⇄merger，switch merge/skip | 健康 |
| nebflow-review-merge | scanner→reviewer→merger，maxLoop=1 | 健康（无回边） |
| **memory-consolidation** | scanner⇄consolidator 纯环，**无 $return 无 switch** | **结构性断裂**：唯一出口是 `Max loop (3) exceeded` 失败——这个 flow **永远不可能成功**（`FlowDagExecutor.scala:174-175`） |
| **release-stable** | reviewer⇄coder 纯环，**无 $return 无 switch** | **同样断裂**：唯一终点是 maxLoop 失败 |
| **research** | researcher→analyst→reviewer 串行 | **实现错位**：三个 agent 的 system.md 全是"read-only codebase investigation"模板（与调研无关）；agent.json 描述"网页爬取/文献搜索"但 prompt 从未提及；tools 列了 Mail（本就被结构性剥除）。且结构是单调研员串行——用户要的"多路并行→校验→打回"模式完全没实现 |

### 1.4 对 R4 报告的修正

用户定论："Flow 也可以只有一个 agent 的判断是错的——如果一个 agent，完全可以用 skill 代替"。**单节点 flow 不合法**：它提供的白名单/可视化/入口稳定性收益（R4 §3.4 论证）不构成独立实体理由，需要这些就用 agent + flows 白名单（AgentEntry.flows 已支持，`EntityTypes.scala:25`）或未来加"受控入口"概念。本设计在加载校验中 reject 节点数 <2 的 flow。R4 的 17 条 checklist 其余部分（拆分三理由、收敛三层、契约设计）仍然有效，本报告引用不重复。

---

## 2. 增强一：并行节点 + Barrier 语义

### 2.1 拓扑表达：三种选型

| 方案 | 说明 | 权衡 |
|---|---|---|
| A. onComplete 数组扇出 | `"onComplete": ["r1","r2","r3"]`，多入边节点 = Barrier | 改动最小；但 join 语义要 executor 从全局图反推入度，和"路由内嵌于节点"的格式精神有张力 |
| B. NodeRoute.Parallel(fan, join) | 专用路由类型（deepseek 对比报告 P2 建议） | 扇出/汇聚成对声明、直观；但菱形/多汇聚点表达别扭，join 要写两遍（fan 声明一次、每个分支 onComplete 还要指向 join） |
| C. 显式 edges 数组 + 入度调度器 | 经典 DAG 格式，节点只声明条件 | 表达力最强；但推翻现有 7 个 flow 的格式，前端/注册/热路径全要改，迁移成本不成比例 |

**推荐 A 的变体（扇出对象 + 隐式 join）**：`"onComplete": {"parallel": ["r1","r2","r3"]}` 扇出；join 不需要声明——**多入边节点天然是 Barrier**（executor 静态算入度，registerFlow 已经在推导边表 `FlowDagExecutor.scala:352-364`，扩一步即可）。理由：格式向后兼容（现有单目标 Goto/Switch 原样合法）、表达力覆盖扇出/汇聚/菱形/回边、迁移零成本。方案 C 留作远期（若出现复杂多汇聚 DAG 再议，见 §6）。

### 2.2 执行层：分支 fiber + 计数屏障

不重写 runNode 递归（它承载了 cancel 预检、状态上报、onError、route 全部正确逻辑），而是**把它复用为分支步行器**：

1. `FlowExecContext` 从不可变参数改为 `Ref[IO, FlowExecContext]` 共享状态（nodeOutputs/loopCounts 并发合并；traverse 语义用 Ref.update 串行化，无锁竞争热点）。
2. route 遇到 `Parallel(fan)`：对 fan 每个目标 `fork` 一个 fiber 调 `runNode(target)`；当前路径挂起在**屏障 Deferred** 上。
3. 屏障 = 计数器：join 节点静态入度 N（来自所有 onComplete 指向它的边）；每个上游分支到达 join 时 `decrement`；归零 → 完成屏障 Deferred → 激活 join（一条 fiber 跑 runNode(join)，其余到达分支直接结束）。**这就是"阻塞等齐所有结果，而不是一个一个发"**——join 只被激活一次，输入是齐的。
4. 回边再入：verify→revise→parallel fan 重新扇出时，join 屏障计数器**重新装填**（每轮激活配一套新计数，按"激活轮次"而非静态入度计——数据流机的 dynamic indegree 标准做法）。loopCounts 按边计数不变（`FlowDagExecutor.scala:174-181`），maxLoop 直接约束打回轮数。
5. 并行度上限：`maxFanout`（flow 级可选，默认 4）——v2.1 删了超时，fanout 上限是防止"无限扇出烧钱"的新护栏。academic-survey 的"每批≤2"经验值即此参数的先例。

### 2.3 部分失败语义（结合 v2.1 无超时 + cancel 穿透）

- **默认 `abort`（fail-fast）**：任一分支失败（onError 处理后仍失败）→ 对其余在跑分支发 per-flow 取消信号（复用 `RunningFlowRegistry.cancelSignal` 机制，`RunningFlowRegistry.scala:42-56`；分支 fiber 的 `IO.race` 收到后 Stop 各自 agent，`:488-492` 的穿透逻辑逐分支生效）→ flow Left 失败。与现有串行语义一致（任一节点 stop → flow 失败），心智模型不变。
- **可选 `onFail: "collect"`（扇出级声明）**：失败分支产出占位结果 `[node r2 failed: <err>]` 写入 nodeOutputs，屏障**照常等齐放行**；join/verifier 看到带标注的部分结果 + 失败说明，用 verdict 决定打回（revise 回边会重跑全部分支）。适用调研类：3 路死 1 路，校验者判"2 路可用+1 路缺失"→ revise 或降级通过。这是 deepseek "per-item 映射 null 不中断"的对应物，但收敛决定权在结构化 verdict 而非引擎兜底。
- **用户 cancel**：cancelSignal 是 instance 级的，天然穿透所有分支——每个分支 fiber 各自 race 到信号、Stop agent、退出。executor 收尾逻辑（`:316-335`）不变。
- **无超时的活锁护栏**：分支 liveness 仍归 agent 内部超时（v2.1 原则不变）；屏障不会永远等待——任一分支最终要么 Completed/Failed（触发上述两路径）要么被 cancel 穿透，不存在"屏障悬空"第三态。

### 2.4 汇聚节点的输入组装

保持"input 模板显式列出所需上游输出"的契约纪律（R4 checklist C1），**引擎不自动拼接**：

```
"verify": { "input": "校验以下三路调研结果（每路需含来源链接）：\n=== 子题A ===\n$r1.output\n=== 子题B ===\n$r2.output\n=== 子题C ===\n$r3.output" }
```

按来源标注（`=== 子题X ===`）由 flow 作者写在模板里——顺序、标签、裁剪都是流程契约的一部分，自动拼接反而隐藏信息损耗。加一个糖：`$fan.output` 展开为"按 fan 声明顺序、带 `=== <nodeId> ===` 标头"的组合块，懒人可用（P2 再做，非必需）。

### 2.5 用户例子的完整建模（research flow 重写）

![research flow 改造前后拓扑](/tmp/r8-research-topology.svg)

```json
{
  "name": "research",
  "description": "多源并行调研→真实性校验→打回循环→汇总。Use when investigating a topic needing multi-perspective verified research.",
  "entry": "planner",
  "maxLoop": 2,
  "maxFanout": 3,
  "nodes": {
    "planner": {
      "agent": "planner",
      "input": "$task\n\n拆分为 3 个正交的调研子题（每个给一行范围说明），供三路调研员并行执行。",
      "onComplete": { "parallel": ["r1", "r2", "r3"] }
    },
    "r1": { "agent": "researcher", "input": "调研子题A：\n$planner.slots.topics\n每条结论必须附来源链接。", "onComplete": "verify" },
    "r2": { "agent": "researcher", "input": "调研子题B：\n$planner.slots.topics\n每条结论必须附来源链接。", "onComplete": "verify" },
    "r3": { "agent": "researcher", "input": "调研子题C：\n$planner.slots.topics\n每条结论必须附来源链接。", "onComplete": "verify" },
    "verify": {
      "agent": "verifier",
      "input": "校验以下三路调研结果的真实性与来源质量，逐条抽查来源链接可达性与内容一致性：\n=== 子题A ===\n$r1.output\n=== 子题B ===\n$r2.output\n=== 子题C ===\n$r3.output",
      "onComplete": {
        "switch": "$verify.verdict",
        "cases": { "pass": "writer", "revise": { "parallel": ["r1", "r2", "r3"] } },
        "default": "writer"
      }
    },
    "writer": { "agent": "writer", "input": "汇总为最终报告（附完整来源列表）：\n$verify.output", "onComplete": "$return" }
  }
}
```

语义要点：verify 是 3 入边 Barrier（等齐 r1/r2/r3 才激活一次）；revise 回边重新扇出三路（屏障计数重新装填）；`verify→r1` 这条边每轮 loopCounts+1，maxLoop=2 → 最多 2 轮打回；planner 的 `slots.topics` 见 §3.2。三个 researcher 共用同一 flow-local agent 定义但**各自独立 session**（现有 executeAgent 天然如此，`:448-465`）。

---

## 3. 增强二：结构化消息传递与分支判断

### 3.1 现状的三个问题

1. FlowReport 可选，substring 兜底在猜（§1.2）——判断可靠性靠运气。
2. 输出传递只有整文本 `$x.output` 一个通道——下游要"结论+来源+置信度"分开用，只能自己解析散文。
3. FlowReport 的 verdict 合同写在**工具的静态 description**里（"exactly one of the case keys declared there"，`FlowReportTool.scala:39`），agent 并不知道自己节点的 case keys 到底是哪几个——错 verdict 靠引擎端 VerdictFamily 同族归一救（pass→ok），救不回的落到 substring。

### 3.2 设计：FlowReport 升级为节点输出契约

**flow.json 节点声明契约**（FlowNode 增可选字段）：

```json
"planner": {
  "agent": "planner",
  "outputs": { "topics": "string" },
  "onComplete": { "parallel": ["r1","r2","r3"] }
},
"verify": {
  "agent": "verifier",
  "outputs": { "issues": "array" },
  "onComplete": { "switch": "$verify.verdict", "cases": { "pass": "writer", "revise": { "parallel": ["r1","r2","r3"] } } }
}
```

**FlowReport 参数扩为三层**：`verdict`（string，switch 节点必须 ∈ case keys；顺序节点 "done"）+ `output`（string，全文，兼容现状）+ `slots`（object，按节点 `outputs` 声明校验类型）。

**引擎侧**：
- `NodeResult` 增 `slots: Map[String, Json]`（`EntityTypes.scala:262-269`）。
- `resolveInput` 增 `$x.slots.<field>` 引用（`FlowDagExecutor.scala:72-81` 扩一条正则分支）——下游按字段取结构化数据，不再解析散文。
- FlowReportStore 值扩为 `(verdict, output, slots)`（`FlowReportStore.scala:12`）。

### 3.3 可靠性兜底：工具级校验 + 当轮自我纠正（关键设计）

判断错误的**第一道防线放在工具调用时**，而不是事后解析：

1. flow 节点的 FlowReport 工具 description **按节点动态注入**该节点的 case keys 与 slots schema（spawn AgentActor 时把契约写进 system reminder，或 AgentCore 注入 per-node 工具描述）——agent 从一开始就知道自己的枚举。
2. FlowReport.call 校验：verdict 不在 case keys / slots 类型不符 → 返回 **ToolError**（"verdict must be one of: pass, revise"）——agent 在同一轮内收到错误并自我修正重调。这是 LLM 结构化输出失败最便宜的兜底：纠正成本 = 一次工具往返，而非整个 flow 路由错误。
3. 二道防线（节点终态仍无合法 verdict）：switch 节点直接 NodeResult 失败（onError 链生效，可 restart 重跑），错误信息列出期望 keys（沿用 `:214-221` 的清晰报错格式）。
4. **substring 兜底降级为显式 opt-in**：`Switch` 增 `lenient: true` 才启用 JSON/regex/substring 逐级猜测（默认关）。默认路径 = FlowReport verdict 或失败，行为确定可预测。

### 3.4 边条件谓词：保持枚举等值，不做表达式语言

Switch 只做 `$node.verdict`（及 `$node.slot.<name>` 的字符串等值）匹配 case key——**不引入 when/表达式/数值比较**。理由：用户反对的是"用文本猜分支"，解法是让判断值结构化可靠，而不是把 flow.json 变成图灵完备；多因素分支应该建模为一个 verdict 节点（agent 综合判断输出枚举），而不是引擎端算布尔表达式。回边循环的合法性表达 = 现有 `Goto 回边 + maxLoop`（`:174-181`）+ 结构化 verdict 触发，无需新原语。

### 3.5 兼容与迁移（7 个 flow 影响）

| flow | 影响与动作 |
|---|---|
| code-review / entity-creator / git-merge / nebflow-review-merge | 已用 switch；给 4 个 flow 的 switch 节点对应 agent system.md 补"必须 FlowReport(verdict∈…)"一句 + 声明 outputs（可选）。不改造也能跑（lenient 未加 = 它们依赖的 substring 被关——**需迁移**：默认 strict 下无 FlowReport 会失败，故此项为 P1 必做，工作量 = 每个 agent prompt 加 2 行） |
| memory-consolidation / release-stable | 本就断裂，直接按新格式重写出口（§4） |
| research | 按 §2.5 全量重写（新 agent prompt + 并行结构） |

过渡策略：引擎加 flow 级 `strictVerdict` 开关（默认 true），一个 release 周期内旧 flow 未迁移完可临时 false。

---

## 4. 附：skill/flow 错位审计（只建议，不实施）

判定准则（用户定义 + 代码事实）：**skill = 教 agent 怎么做一件事**（知识/规范注入，单 agent 内完成）；**flow = 把一件事交给固定多节点流程**（引擎强制路由 + 结构化收敛 + ≥2 个角色分离的 agent）。错位的信号：skill 里出现"编排其他 agent/批次/循环打回"，或 flow 里只有一个角色。

### 4.1 flow 审计（7 个）

| flow | 判定 | 建议 |
|---|---|---|
| code-review | ✅ 正确 | 保留；顺手迁移 strict verdict |
| entity-creator | ✅ 正确 | 保留；修 builder 首轮 `[output of reviewer not found]` 噪音（模板首轮分支） |
| git-merge | ✅ 正确 | 保留 |
| nebflow-review-merge | ✅ 正确 | 保留 |
| memory-consolidation | ❌ 断裂 | 重写：scanner 加 switch（`clean→$return / found→consolidator`），consolidator 改 `→$return` |
| release-stable | ❌ 断裂 | 重写：reviewer 加 switch（`ready→coder / blocked→$return`），coder→reviewer，reviewer pass→$return |
| research | ❌ 错位 | 按 §2.5 重写为并行调研流程（agent prompt 全部重写——现在是 codebase 探索模板） |

**无 flow 应降级为 skill**（全部 ≥2 节点 ≥2 角色，符合用户"单 agent 用 skill 代替"的反向要求）。

### 4.2 skill 审计（12 个）

| skill | 判定 | 依据 |
|---|---|---|
| **academic-survey** | ❌ **实为 flow，建议升格** | 483 行散文编排了完整的"并行调研（批次≤2，run_in_background）→合并→校验-打回循环→交付"——正是用户举例的模式，但 barrier/批次/循环全靠 prompt 纪律维持，无结构化收敛保证。§2.5 的 research flow 即其引擎化版本；升格后 SKILL.md 可缩为"报告写作规范"类知识（引用格式、图表管理）留在 skill，编排交 flow |
| grill | ⚠️ 边界 | 架构设计多阶段但单 agent 顺序完成、无校验关卡——目前保留 skill；若加入"评审-打回"循环则升格 flow |
| thesis-review | ⚠️ 边界 | 单角色审阅（一个 agent 的深工作）——保留 skill；若扩展"作者修订→复审"循环则升格 |
| card-design / visual-report / guizang-ppt / guizang-social-card / learn-anything / phd-note / skill-creator / deploy-website / _example | ✅ 正确 | 纯知识/规范/单 agent 程序，无编排语义 |

---

## 5. 实施要点（文件级 + 分期）

**Phase 1 — 结构化 verdict 契约（先行，独立可发布）**
- `EntityTypes.scala`：FlowNode 增 `outputs` 声明；Switch 增 `lenient`；FlowDagDef 增 `strictVerdict`
- `FlowReportTool.scala`：参数扩 slots；call 内契约校验 + ToolError 自纠
- `FlowDagExecutor.scala`：`extractSwitchValue` 三级猜测移入 lenient 分支；strict 下无 verdict → 节点失败
- `AgentCore.scala` / 节点 spawn 路径：per-node case keys + slots schema 注入工具描述
- 4 个健康 flow 的 agent prompt 补 FlowReport 契约句

**Phase 2 — 并行节点 + Barrier**
- `EntityTypes.scala`：NodeRoute 增 `Parallel(fan: List[String])`（decoder 接受 `{"parallel":[...]}` 与字符串两种 onComplete 形态）
- `FlowDagExecutor.scala`：FlowExecContext Ref 化；分支 fiber + 计数屏障（含回边再装填）；扇出 onFail abort/collect；per-branch cancel 复用 cancelSignal
- `RunningFlowRegistry.scala`：无需大改（NodeState 已按 node 粒度）；edges 推导扩 parallel
- 前端：flowStarted 的 edges 已含 parallel 边（`:294-308` 扩展），DAG 渲染并行节点同层
- `EntityLoader.scala`：加载校验——节点数≥2（单节点 reject）、join 入度≥2 需存在 parallel 上游、所有节点可达且有终止路径（纯环 reject，防 §4 两断裂 flow 复发）、maxFanout 上限

**Phase 3 — 审计落地**：research 重写（§2.5）、memory-consolidation / release-stable 出口修复、academic-survey → flow 迁移

**验收条件（实施后逐条可测）**
1. 冒烟：`sbt stage` 后真实启动网关，Delegate(flow="research") 触发；WS flowStarted 收到 5 节点 7 边 DAG；r1/r2/r3 三个 flowProgress(Running) 时间戳重叠（并行证据）
2. Barrier：verifier 的 startedAt ≥ max(r1,r2,r3.completedAt)（等齐才激活，逐条日志可证）；总耗时 ≈ max(单路) 而非 sum
3. 打回循环：verifier 出 revise → 三路重新 Running、loopCounts[verify→r1] 递增；连续 2 次 revise → flow 失败 `Max loop (2) exceeded`
4. cancel 穿透：并行中途 cancel → 3 个 agent 全部 5s 内 stopped，flow 终态 cancelled（FlowDagExecutorCancelSpec 模式补并行用例）
5. 结构化：verifier 返回非法 verdict → FlowReport ToolError 当轮自纠日志可见；strict 下拒绝 FlowReport 的 switch 节点 → 节点 Failed 且错误信息含期望 keys；无 lenient 时 substring 兜底不再触发（无 warn 日志）
6. 兼容：7 个旧 flow 中 4 个健康 flow 迁移后跑通全链路；未迁移 flow 在 strictVerdict=false 下行为与现版本一致
7. 校验：单节点 flow.json 与纯环 flow.json 加载即 reject；现有测试 `sbt test` 全绿 + 新增 ParallelSpec/BarrierSpec/StrictVerdictSpec

---

## 6. 开放问题（附倾向）

| 问题 | 选项 | 倾向 |
|---|---|---|
| 并行分支早退 $return | 禁止（扇出兄弟必须汇聚或全部 return，加载期静态校验）/ 动态等待所有分支 settle | **静态校验禁止**：并行分支中途 return 语义含混（先到先得丢结果），菱形+汇聚已是完备表达；加载期拒绝最便宜 |
| fiber 分支 vs 全量数据流调度器（work-queue 重写） | 渐进复用 runNode / 一步到位入度调度 | **fiber 先行**：改动半径小、cancel/onError/route 逻辑全复用；等出现"两个以上独立汇聚点"的真实需求再重写（那时 edges 数组格式一并考虑） |
| maxFanout 默认值 | 3 / 4 / 不设上限 | **4**：覆盖 research 三路 + 一路余量；是删超时后唯一的扇出成本护栏 |
| slots 类型系统 | string 单类型起步 / 完整 string/number/array/object | **string+array 起步**：模板注入场景两类够用，object 需要序列化规范，等真实需求 |
| maxLoop 全局 vs 按边覆盖 | 现状全局 / 边级 `maxLoops` | **保持全局**：现有按边计数 + 全局上限已够；并行回边（一次 revise 算 3 条边各 +1）语义在 §2.5 示例中已明确，先跑起来再议 |
| planner 拆题 vs 手写固定三路 | 入口 agent 动态拆 N 路（fanout 运行时定）/ flow.json 静态声明 | **静态声明先行**：并行表达力已满足用户例子；动态 fanout 需要 Parallel 接受 `$planner.slots.topics` 展开，作为 P2 增强（依赖 slots 先落地） |
