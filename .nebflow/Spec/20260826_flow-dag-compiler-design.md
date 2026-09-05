# Flow DAG 编译前端设计（flow 校验层编译器化）

> 阶段文档 · 2026-08-26 · 只读分析产出，不改产品代码
> 用户裁定（08-26 08:27）：「我们要以编译器的思想去设计」——flow 校验层从「运行时兜底报错」升级为「提交时静态编译」
> 版本：v1.0（冻结）

## 0. 结论摘要

- **问题**：FlowExecute 的 DAG 错误（静态节点用 `{{}}`、`$judge1.slots.failBlocks` 未声明 outputs、非法路由目标）都是**运行时**才暴露——planner+16 worker 跑完才报（v4 join 事故）、qa+judge1 跑完才报（redo 冒烟事故）。#414 已做「运行时明确报错」，但时机仍晚：静态可查的错误不该花 token 换报错时机。
- **方向**：在 FlowExecute 调用返回前（0 spawn / 0 token）完成静态编译校验——语法 → 语义 → 类型三层全量收集，错误格式编译器风格（错误码 + 位置 + 原因 + 修正建议）。
- **规模**：本设计定义 **36 个错误码**（E-001..E-017 语法层 / E-101..E-103 占位符语义 / E-201..E-212 引用与结构语义 / E-301..E-304 类型层）。其中 **14 项是当前完全没有编译期/加载期拦截、纯靠运行时才暴露的新检查**（E-002/007/008/010/014/015/016/017、E-101/102/103、E-201、E-204/205/206/207、E-301/303/304 中的新增项），其余为对现有检查（circe decode / FlowStructure.validate / EntityLoader.validateFlow）的统一码化。
- **实现落点**：新增 `nebflow.core.entity.FlowDagCompiler`（单一编译入口，组合复用 FlowStructure.validate + EntityLoader.validateFlow），FlowExecuteTool / LoadTool / FlowTriggerTool 三处调用统一。
- **验收**：每错误码一例错误 DAG 在 FlowExecute 返回时即报错（0 spawn 0 token）；R3 实证正确 DAG（planner outputs.blocks + judge outputs.failBlocks + template `{{item}}`）编译通过并正常执行；现存预定义 flow 回归零误杀。

---

## 1. 背景：校验时机缺口与事故记录

### 1.1 现状校验链（全部在「提交后」才发生，且层级残缺）

| 层 | 现状 | 位置 | 时机 |
|---|---|---|---|
| JSON 解码（≈语法） | circe decode，错误为 ad-hoc 字符串，无错误码 | `FlowExecuteTool.call` L165-167（`flowJson.as[FlowDagDef]`）；`FlowNode`/`NodeRoute`/`FlowParamSpec` decoder（EntityTypes.scala） | 调用时 ✓ |
| 结构校验 | `FlowStructure.validate`：≥2 节点 / 终止路径 / fanout 上限 / join 收敛 / 混合到达 | FlowExecuteTool L170-172；LoadTool | 调用时 ✓ |
| agent 存在性 | `EntityLoader.validateFlow`：entry 存在 / agent 存在 / 路由目标存在 | FlowExecuteTool L176-180 | 调用时 ✓ |
| **占位符残留** | 运行时 `contains("{{")` 拦截（#414 fix 4b） | FlowDagExecutor.executeNode L283-289 | **节点执行前**（部分节点已跑完） |
| **slots 引用缺失** | 运行时 slot 查找失败报错（#414 fix 4a） | FlowDagExecutor.parallelDispatchDynamic L733-751 | **fanout 触发时**（上游节点已跑） |
| **output 引用缺失** | 运行时替换为 `[output of X not found]` 占位符 | FlowDagExecutor.resolveInput L249 | 静默降级，不报错 |
| **slots 非数组** | 运行时 `not an array` 报错 | FlowDagExecutor L752-753 | fanout 触发时 |

### 1.2 事故记录（静态错误花运行时 token 才暴露）

| 事故 | 现象 | 静态错误本质 | 浪费 |
|---|---|---|---|
| **v4 join `{{}}` 残留** | join 节点 input 含 `{{item}}`（join 不是动态 fanout 的 template），planner + 16 个 worker 全部跑完后才在 join 执行前拦截 | E-101（静态节点用 `{{}}`） | planner ×1 + worker ×16 全部 token |
| **redo 冒烟 `$judge1.slots.failBlocks`** | redo 节点引用 judge1 未声明的槽，qa + judge1 跑完、redo fanout 触发时才报 | E-201（slots 引用未声明 outputs） | qa ×1 + judge1 ×1 |
| **`$planner.slots.blocks` 全引用字符串**（#414） | `"$planner.slots.blocks"` 带引号被当字面量 key → 0 实例 + collect 静默空聚合，零校验零告警 | E-201 的兄弟问题（引用形态） | 整轮 fanout 结果无效 |

**共性**：这些都是**纯静态可判**的错误（图是静态的、outputs 声明是静态的、`{{}}` 是否出现在静态节点是静态的），却被安排在运行时才暴露。编译器的回答：**编译期能抓的一切错误，绝不让它进执行期**。

---

## 2. 编译器思想映射

| 编译器概念 | flow DAG 对应物 | 现状 / 目标 |
|---|---|---|
| **源程序** | FlowExecute 调用时内联传入的 DAG JSON（nodes/entry/routes/params） | 现状即源程序（无中间表示） |
| **词法** | node id 标识符（`[a-zA-Z0-9_-]`）、模板变量 token（`$x.output` / `$x.slots.f` / `$x.all.output` / `{{item}}` / `$params.x`）、路由关键字（switch/parallel/cases/default） | **新增**：E-002（id 词法）、E-103（占位符词法）、E-1xx 系列 |
| **语法** | JSON 结构 → AST（FlowDagDef / FlowNode / NodeRoute） | circe decode 已做，但错误无码；目标：decode 错误映射 E-0xx |
| **语义** | 名字绑定（每个模板变量/路由目标绑定到声明的节点/参数/槽）、控制流图分析（可达性/环/收敛） | 部分（FlowStructure + validateFlow）；**目标**：E-2xx 全量，含 6 项纯新增 |
| **类型** | 槽类型（string/array）在引用处的检查：parallel.slots 必须 array、`all.slots.<f>` 必须声明、outputs 值域 | **新增**：E-3xx |
| **IR（中间表示）** | 校验通过的「已解析执行描述」：节点集 + 边集（routeTargets 派生）+ join 集 + expectedJoins + 实例化规则（template → N 实例） | FlowDagExecutor.execute L162-170 已隐式构建（joins/expectedJoins）；目标：编译器产出规范化的 IR 供执行引擎消费 |
| **执行引擎** | FlowDagExecutor（walk/advance/barrier/并行调度） | 现状即引擎，吃 FlowDagDef；演进可选：吃编译器产出的 IR（避免每次 execute 重算静态分析） |

**核心原则**：

1. **编译期捕获一切静态可查错误**——FlowExecute 调用返回前完成，0 spawn、0 token。编译错误不执行。
2. **运行时只留动态判定**——FlowReport verdict 实际值、`$node.slots` 运行时内容（长度/元素）、agent 输出质量、maxLoop 累计、onError 分支决策。
3. **编译器式错误报告**——错误码 + 位置（node.fieldPath）+ 原因 + 修正建议，一次调用全量收集（不 fail-fast 在第一个错误），方便 agent 一次性改完。
4. **组合而非复制**——结构规则（FlowStructure）与 agent 存在性（EntityLoader.validateFlow）已实现且被 executor 复用，编译器**组合调用**它们并统一码化，不复制逻辑。

---

## 3. 错误码总览（三层体系）

```
E-0xx  语法层     JSON 结构 / 字段类型 / 数值范围 / 词法（decode + 补充）
E-1xx  语义·占位符  {{}} 模板变量合法性（静态节点 vs dynamic template）
E-2xx  语义·引用结构 名字绑定（slots/output/params/路由目标）+ 控制流（可达/环/收敛）
E-3xx  类型层     槽类型在引用处的检查（array 要求 / 声明一致性）
```

| 层 | 码数 | 其中「当前无任何静态/加载拦截、纯运行时暴露」的新增检查 |
|---|---|---|
| E-0xx 语法 | 17 | E-002（id 词法）、E-007（maxFanout 范围）、E-008（maxLoop 范围）、E-010（maxRetries 负数）、E-014（switch cases 空）、E-015（fan 空）、E-016（min>max）、E-017（name 缺失静默补默认） |
| E-1xx 占位符 | 3 | E-101 / E-102（运行时 #414 才拦）、E-103（未拦） |
| E-2xx 引用结构 | 12 | E-201（运行时才拦）、E-203.2/.3（孤儿/环未拦）、E-204 / E-205 / E-206 / E-207（未拦，运行时静默占位符或回退） |
| E-3xx 类型 | 4 | E-301（运行时才拦）、E-303 / E-304（未拦） |
| **合计** | **36** | **18 项为编译前端新增价值点**（其余 18 项为现有检查统一码化） |

> 计数口径：E-203 含 .1/.2/.3 三个触发条件，计 1 码 3 触发；「新增价值点」= 现状完全没有编译期/加载期拦截的检查项（含 #414 运行时拦截项的前移）。

---

## 4. 三层静态校验清单（完整枚举）

每条给出：**错误码 · 触发条件 · 现状 · 错误信息模板 · 修正建议**。错误信息模板中的 `<...>` 为占位插值。

### 4.1 语法层 E-0xx（结构 / 字段类型 / 范围 / 词法）

| 码 | 触发条件 | 现状 | 错误信息模板（含修正建议） |
|---|---|---|---|
| **E-001** | `nodes` 缺失 / 非 JSON 对象 / 空对象 | 已拦截（decode） | `[E-001] flow '<name>'.nodes: must be a JSON object with ≥2 node entries — add node definitions or fix the top-level JSON shape` |
| **E-002** | 节点 id 为空或含 `[a-zA-Z0-9_-]` 之外字符（`.`/`$`/空格等）——模板变量正则 `\$([a-zA-Z0-9_-]+)\.output` 无法解析此类 id，引用永远落空 | **新增** | `[E-002] node '<id>'.id: node id must match [a-zA-Z0-9_-]+ (got '<id>') — template references like $<id>.output cannot resolve it; rename to a plain identifier` |
| **E-003** | `agent` 缺失 / 非字符串 / 空或全空白 | 部分拦截（decode 拦非字符串；空串 decode 通过，validateFlow 才报「not found」） | `[E-003] node '<id>'.agent: must be a non-empty string naming a global agent — set "agent": "<name>"` |
| **E-004** | `input` 缺失 / 非字符串 | 已拦截（decode） | `[E-004] node '<id>'.input: must be a string template — set "input": "$task" or a template referencing upstream nodes` |
| **E-005** | `onComplete` 缺失 / 非字符串且非对象 / switch 缺 `switch` 或 `cases` / parallel 数组元素非字符串 / dynamic parallel 缺 `slots` 或 `template` | 已拦截（decode） | `[E-005] node '<id>'.onComplete: must be "<target>" | "$return" | {switch...} | {parallel...} — see the four route forms in the tool description` |
| **E-006** | `entry` 缺失 / 非字符串 / 空 | 已拦截（FlowExecuteTool L160 + validateFlow） | `[E-006] flow '<name>'.entry: must name a node in nodes — set "entry": "<node-id>"` |
| **E-007** | `maxFanout` 非整数 / ≤ 0 / > 256 | **新增**（decode 只拦非整数；0/负数/超限静默通过） | `[E-007] flow '<name>'.maxFanout: must be a positive integer ≤ 256 (got <v>) — declare the parallelism this flow actually needs` |
| **E-008** | `maxLoop` 非整数 / < 1 | **新增** | `[E-008] flow '<name>'.maxLoop: must be a positive integer (got <v>) — loop protection bound for redo cycles` |
| **E-009** | `onError` ∉ {stop, resume, restart} | 已拦截（decode） | `[E-009] node '<id>'.onError: must be "stop" | "resume" | "restart" (got "<v>") — pick the failure strategy` |
| **E-010** | `maxRetries` 非整数 / < 0 | 部分拦截（decode 拦非整数；负数通过） | `[E-010] node '<id>'.maxRetries: must be a non-negative integer (got <v>) — number of restart attempts` |
| **E-011** | `outputs` 缺失 / 非对象 | 已拦截（decode） | `[E-011] node '<id>'.outputs: must be a JSON object mapping slot name → "string"|"array" — declare structured slots for downstream references` |
| **E-012** | `onFail` ∉ {abort, collect} | 已拦截（decode） | `[E-012] node '<id>'.onComplete.onFail: must be "abort" | "collect" (got "<v>") — pick the branch-failure policy` |
| **E-013** | `lenient` 非布尔 | 已拦截（decode） | `[E-013] node '<id>'.onComplete.lenient: must be a boolean (got <v>) — lenient opts this switch back into the legacy guess pipeline` |
| **E-014** | switch `cases` 为空对象 | **新增**（decode 接受 `{}`） | `[E-014] node '<id>'.onComplete.cases: switch needs at least one case — declare the verdict routes (e.g. {"pass": "agg", "fail": "redo"})` |
| **E-015** | `parallel` 数组为空 | **新增**（decode 接受 `[]`；运行时 mergeOutcomes 零分支 → 报「barrier deadlock」） | `[E-015] node '<id>'.onComplete.parallel: fan must list at least one target — an empty fan deadlocks at runtime` |
| **E-016** | `params` 声明非法：type ∉ {int,string,bool} / min/max 用于非 int / min > max | 部分拦截（decode 拦 type 与 min/max 归属；min>max 未拦） | `[E-016] flow '<name>'.params.<name>: <detail> — declare a valid param spec (type int|string|bool, min/max only for int, min ≤ max)` |
| **E-017** | FlowExecute 未提供顶层 `name`（当前被静默补默认 `"dynamic-flow"`，WS/UI 事件无法区分） | **新增**（建议） | `[E-017] flow '<name>'.name: a non-empty display name is required for WS/UI events — pass "name" to FlowExecute` |

### 4.2 语义层·占位符 E-1xx（`{{}}` 模板变量）

**背景规则**（flow-execute skill §3.4 沉淀）：`{{}}` 只在 dynamic fanout 的 template 实例上替换 `{{item}}`/`{{index}}`/`{{len}}`；onComplete 为字符串路由（Goto/Return）的**静态节点**不替换任何 `{{}}`。

| 码 | 触发条件 | 现状 | 错误信息模板（含修正建议） |
|---|---|---|---|
| **E-101** | 节点 **不是任何 `ParallelDynamic.template`**（无 `{parallel:{slots,template}}` 引用它）且 input 含 `{{` | 运行时 #414 fix 4b 才拦（`contains("{{")`，节点执行前） | `[E-101] node '<id>'.input: contains '{{...}}' but this node is NOT a dynamic-fanout template — static routes substitute no {{}} placeholders, so they reach the agent literally — reference the upstream slot instead ($<node>.slots.<field> / $<template>.all.slots.<field>), or make this node a dynamic fanout template` |
| **E-102** | 节点是某 `ParallelDynamic.template` 且 input 含 `{{<name>}}` 而 name ∉ {item, index, len}（含拼写错误 `{{iteam}}`、大小写错误 `{{Item}}`） | 运行时 #414 fix 4b 才拦（只判 `{{` 存在，不判变量名） | `[E-102] node '<id>'.input: template variable '{{<name>}}' is not supported — only {{item}} {{index}} {{len}} are substituted on dynamic instances; check the spelling or remove it` |
| **E-103** | 占位符词法畸形：`{{` 无配对 `}}` / `{{}}` 空变量 / 变量名含非法字符 | **新增**（未拦） | `[E-103] node '<id>'.input: malformed placeholder '<fragment>' — every {{ must close with }} and name a variable` |

> **豁免规则（推荐，待确认见 §10）**：E-101/E-102 按「节点是否被任一 ParallelDynamic.template 引用」判定，**不区分 entry/planner**——entry 节点 input 写 `{{}}` 同样报 E-101（entry 的正确输入来源是 `$task`）。

### 4.3 语义层·引用与结构 E-2xx（名字绑定 + 控制流）

| 码 | 触发条件 | 现状 | 错误信息模板（含修正建议） |
|---|---|---|---|
| **E-201** | slots 引用未声明：input 模板或 `parallel.slots`（全引用 `$<src>.slots.<f>` 或字面量 key 两种形态）中，源节点（全引用=引用目标节点；字面量=当前节点）未声明 `outputs.<f>`；含**自引用**（`$<self>.slots.<f>`——自身 slots 在运行前不存在，必然运行时缺失） | 运行时 #414 fix 4a 才拦（fanout 触发时） | `[E-201] node '<id>'.input: slot reference '$<src>.slots.<field>' — node '<src>' does not declare outputs.<field> (declared: <list>) — add "<field>": "array"|"string" to <src>.outputs, or fix the reference; self-references ($<self>.slots.*) can never resolve` |
| **E-202** | 路由目标不存在：Goto / switch cases / switch default / parallel fan / dynamic `template` 指向不存在的节点 | 已拦截（EntityLoader.validateFlow badRoutes） | `[E-202] node '<id>'.onComplete: routes to unknown node '<target>' — the target must be a declared node id` |
| **E-203** | DAG 结构，三个触发：<br>**.1** entry 不在 nodes<br>**.2** 从 entry 不可达的孤儿节点（含未被任何 fan 引用的孤立 template）<br>**.3** 存在**全无条件边**的环（Goto/Parallel 直接回边；无 switch 条件可退出 → 无限循环只能靠 maxLoop 兜底） | .1 已拦截（validateFlow entryMissing）；.2/.3 **新增** | `[E-203.1] flow '<name>'.entry: entry node '<id>' not in nodes — point entry at a declared node`<br>`[E-203.2] node '<id>': unreachable from entry '<entry>' (orphan) — no route chain leads here; check routing targets or remove the node`（**warning**，见 §10 决策点）<br>`[E-203.3] node '<id>': participates in an unconditional cycle (<a>→<b>→<a>) with no switch condition to exit — add a switch verdict route or a route to $return to break it` |
| **E-204** | `$params.<name>` 引用但 flow.params 未声明 `<name>`（注意：**声明了但调用未传**是合法降级——运行时 `[param x not provided]` 占位符，不报错） | **新增**（未拦；运行时静默占位符） | `[E-204] node '<id>'.input: parameter reference '$params.<name>' — flow declares no param '<name>' (declared: <list>) — add it to params or fix the reference` |
| **E-205** | `$<node>.output` 引用不存在的节点 | **新增**（未拦；运行时替换为 `[output of X not found]` 静默占位符） | `[E-205] node '<id>'.input: output reference '$<node>.output' — node '<node>' does not exist — reference a declared upstream node` |
| **E-206** | `$<t>.all.output` / `$<t>.all.slots.<f>` 引用的 `<t>` 不存在，或存在但**不是任何 `ParallelDynamic.template`**（无实例可聚合） | **新增**（未拦；运行时聚合空列表 → 空结果静默） | `[E-206] node '<id>'.input: aggregate reference '$<t>.all.<...>' — '<t>' is not a dynamic-fanout template (no {parallel:{slots,template}} references it) — only template instances aggregate via .all; declare a fan with template '<t>' or fix the reference` |
| **E-207** | switch 表达式引用缺失：`switch: "$<node>.<field>"` 的 `<node>` 不存在，或表达式无法解析为 `$<node>.<field>` 形态 | **新增**（extractSwitchValue 静默回退当前节点输出，路由结果错乱） | `[E-207] node '<id>'.onComplete.switch: expression '<expr>' references unknown node '<node>' / is not of the form $<node>.<field> — point the switch at a declared node's verdict (e.g. "$agg.verdict")` |
| **E-208** | 静态 parallel fan 分支数 > maxFanout | 已拦截（FlowStructure.validate） | `[E-208] node '<id>'.onComplete.parallel: fan has <n> branches — exceeds declared maxFanout <m> — raise maxFanout or trim the fan` |
| **E-209** | parallel 分支在 join 收敛前可达 `$return`（中途 return 语义歧义） | 已拦截（FlowStructure.validate） | `[E-209] node '<id>'.onComplete.parallel: branch '<b>' can reach $return before converging at a join — branches must converge first; early return is ambiguous` |
| **E-210** | join 节点混合并行/串行到达（in-edge 来自 fan 下游之外，会破坏 barrier 计数） | 已拦截（FlowStructure.validate） | `[E-210] join '<j>': mixes parallel and serial arrivals — in-edge '<s>→<j>' originates outside the fan's downstream — a serial arrival corrupts the barrier count` |
| **E-211** | 无终止路径（无任何节点路由到 `$return`；纯环 flow 只能以「Max loop exceeded」结束） | 已拦截（FlowStructure.validate） | `[E-211] flow '<name>': no termination path — no node routes to $return — add a route to $return (pure cycles only end in 'Max loop exceeded')` |
| **E-212** | nodes.size < 2 | 已拦截（FlowStructure.validate） | `[E-212] flow '<name>': needs at least 2 nodes (got <n>) — a single agent is an agent + skill, not a flow` |

### 4.4 类型层 E-3xx（槽类型在引用处检查）

| 码 | 触发条件 | 现状 | 错误信息模板（含修正建议） |
|---|---|---|---|
| **E-301** | `parallel.slots` 引用的槽（全引用 `$<src>.slots.<f>` 或字面量 key）在源节点 outputs 中声明为 **"string"**（非 array）——无法按元素展开实例 | 运行时 #414 fix 4a 才拦（`not an array`，fanout 触发时） | `[E-301] node '<id>'.onComplete.parallel.slots: slot '<field>' on '<src>' is declared "string" — dynamic fanout needs an "array" slot; change <src>.outputs.<field> to "array"` |
| **E-302** | `outputs` 槽类型值 ∉ {string, array} | 已拦截（FlowNode decoder L328-333）；码保留为类型层锚点 | `[E-302] node '<id>'.outputs.<slot>: type must be "string" or "array" (got "<v>")` |
| **E-303** | `$<t>.all.slots.<f>` 中 `<f>` 未在 template `<t>` 的 outputs 声明（实例槽类型由 template 的 outputs 决定，未声明则聚合必然落空） | **新增**（未拦；运行时 `[slots.f of id not found]` 占位符） | `[E-303] node '<id>'.input: aggregate slot reference '$<t>.all.slots.<f>' — template '<t>' does not declare outputs.<f> (declared: <list>) — add it so instances report the slot` |
| **E-304** | 同一节点被 **>1 个** `ParallelDynamic` 引用为 template → 两处 fanout 都实例化 `template#1..#N`，ExecState.instances 合并时覆盖 → 聚合错乱 | **新增**（未拦） | `[E-304] node '<id>': referenced as dynamic template by <n> fans (<list>) — instance ids collide (template#1...); give each fan its own template node or restructure to share one fan` |

---

## 5. 错误报告格式（编译器风格）

统一单行结构，按层分组输出：

```
[E-<code>] node '<id>'.<fieldPath>: <原因> — <修正建议>
```

- **`<code>`**：E-0xx 语法 / E-1xx 占位符 / E-2xx 引用结构 / E-3xx 类型
- **`<id>`**：错误所在节点（flow 级错误用 `flow '<name>'` 占位）
- **`<fieldPath>`**：字段路径（`input` / `onComplete` / `onComplete.switch` / `onComplete.parallel.slots` / `outputs.<slot>`）
- **`<原因>`**：含实际值（引用串、已声明列表、数值），可定位
- **`<修正建议>`**：可操作的动作（改哪里、改成什么）
- 附 `（warning）` 标记的为不阻塞警告（§10 决策点定级）

**示例**（对应两起事故）：

```
[E-101] node 'join'.input: contains '{{item}}' but this node is NOT a dynamic-fanout template — static routes substitute no {{}} placeholders, so they reach the agent literally — reference the upstream slot instead ($planner.slots.pages / $worker.all.slots.pages), or make this node a dynamic fanout template

[E-201] node 'redo'.input: slot reference '$judge1.slots.failBlocks' — node 'judge1' does not declare outputs.failBlocks (declared: passBlocks) — add "failBlocks": "array" to judge1.outputs, or fix the reference
```

**全量收集**：一次调用返回**所有**错误（按层、层内按 node id 排序），不以第一个错误终止——agent 拿到完整清单一次性改完。语法层错误（E-0xx）阻断后续层（decode 失败拿不到 AST，语义/类型无法进行）；decode 成功则语义+类型+结构全量收集。

---

## 6. 编译期 vs 运行时分界表

### 6.1 编译期拦截（FlowExecute 调用返回前，0 spawn / 0 token）

| 判定 | 归属层 | 说明 |
|---|---|---|
| 全部 E-0xx 语法 | 语法 | 结构/类型/范围/词法静态可查 |
| 全部 E-1xx 占位符 | 语义 | `{{}}` 合法性由「节点是否 template」静态决定 |
| E-2xx 引用解析（E-201/202/204/205/206/207） | 语义 | 图、outputs 声明、params 声明全静态 |
| E-2xx 控制流（E-203/208/209/210/211/212） | 语义 | 静态图分析（FlowStructure + 环/孤儿检测） |
| 全部 E-3xx 类型 | 类型 | outputs 声明静态，引用点类型可判 |
| agent 存在性（validateFlow） | 语义 | 全局 agent 库静态 |

### 6.2 运行时判定（编译期不可知，必须执行期评估）

| 判定 | 理由 |
|---|---|
| switch 表达式**值**（`$agg.verdict` 实际 verdict） | FlowReport 报告值是节点 agent 的运行时产物 |
| `$<node>.slots` 的**值**（数组长度、元素内容） | 依赖 agent 输出；编译期只判存在性（E-201）+ 类型（E-301） |
| 动态实例数 N = min(槽长度, maxFanout) | 槽长度运行时才知；maxFanout 裁剪警告运行时 |
| maxLoop 超限 | 边遍历计数运行时累计 |
| onError 决策（stop/resume/restart） | 节点失败事件运行时触发 |
| strictVerdict 缺失失败 | agent 是否调用 FlowReport 运行时才知 |
| onFail abort/collect 分支策略生效 | 分支失败事件运行时触发 |
| `$params.<name>` 声明了但调用未传 | 调用参数运行时给出；`[param x not provided]` 为**合法降级**，编译期不拦 |

### 6.3 「看似静态、必须运行时」的边界（编译器要克制的地方）

1. **switch 表达式值依赖 FlowReport verdict**——`$agg.verdict` 只能在该节点完成后评估。编译期只查节点存在（E-207），**不查 verdict 值**，更不推导分支。
2. **`$planner.slots.pages` 的「存在性与类型」静态可判，但「长度与元素」运行时才有**——编译期查声明（E-201/E-301），实例化数量 N 运行时算。
3. **动态 fanout 的 maxFanout 裁剪**——槽长度运行时才知道是否裁剪（编译期无法预测 len vs maxFanout），裁剪警告必须运行时发。
4. **switch 环是合法设计**（code-review 的 scanner→reviewer→fixer 回路）——编译期只拦**无条件环**（E-203.3），条件环的轮数由 maxLoop 运行时兜底。
5. **agent 输出内容/质量**——没有任何静态手段，留给节点契约（FlowReport 校验）与下游 verdict 判定。

---

## 7. 实现落点建议

### 7.1 新对象：`FlowDagCompiler`（编译前端单一入口）

```
src/main/scala/nebflow/core/entity/FlowDagCompiler.scala

object FlowDagCompiler:
  enum Severity: case Error, Warning
  case class Issue(code: String, nodeId: Option[String], fieldPath: String,
                   reason: String, fix: String, severity: Severity):
    def render: String = s"[E-$code] node '${nodeId.getOrElse(flow)}'.$fieldPath: $reason — $fix"
  case class CompileResult(errors: List[Issue], warnings: List[Issue]):
    def rejected: Boolean = errors.nonEmpty
    def renderAll: String   // 按层分组全量输出

  /** 编译入口：语法→语义→类型→结构→agent，全量收集 */
  def validate(flow: FlowDagDef, agentNames: Set[String]): CompileResult
```

**Pass 结构**（组合复用，不复制现有逻辑）：

| Pass | 内容 | 复用 |
|---|---|---|
| Pass 0 语法 | decode 错误映射 E-0xx；补充 E-002/007/008/010/014/015/016/017 范围与词法检查 | circe decoder（错误码化）+ 新增检查 |
| Pass 1 占位符 | 对每个节点：是否 template（收集全部 ParallelDynamic.template 引用集）→ E-101/102/103 | 新增；正则族与 resolveInput 同款 |
| Pass 2 引用 | input 模板扫描：`\$<node>.slots.<f>` / `\$<node>.output` / `\$<t>.all.*` / `\$params.<n>` / switch 表达式 / parallel.slots 两种形态 → E-201/204/205/206/207 + E-301/303/304 | 新增；正则族与 FlowDagExecutor.resolveInput L180-251、parallelDispatchDynamic L739 同款，**单点定义避免漂移** |
| Pass 3 结构 | 调用 FlowStructure.validate → 结果码化 E-208/209/210/211/212；新增 E-203.2 孤儿（reachFrom 覆盖差集）、E-203.3 无条件环（DFS on analysisEdges，switch 内边视为条件边） | FlowStructure.validate / reachFrom / analysisEdges |
| Pass 4 agent | 调用 EntityLoader.validateFlow → 码化 E-202/203.1 + agent 存在性（保留原文案或码化 E-401，建议并入现有消息） | EntityLoader.validateFlow |

### 7.2 调用点（三处统一）

1. **FlowExecuteTool.call**（L164-181）：decode 成功后、`FlowStructure.validate` 前插入 `FlowDagCompiler.validate(flowDef, agentNames)`；`listAgents()` 提前到 validate 之前一次调用（现 L176 已调用，合并即可）。编译错误 → `ToolError(compiler.renderAll)`，**0 spawn**。
2. **LoadTool.validateFlowDag**（L178-199）：预定义 flow 加载路径走同一编译器——错误 DAG 不应 Load 成功（错误码体系一致）。
3. **FlowTriggerTool**：触发时复用编译器做快速重验（flow.json 可能被外部改动；现仅 validateFlowParams，补全量校验成本可接受——图小）。

### 7.3 与 #414 运行时检查的关系

编译期拦截「静态可判」子集；FlowDagExecutor 的运行时检查（L283-289 占位符残留、L733-751 slots 缺失）**保留为防御性兜底**——slots 的**值**运行时才产生，`$<node>.slots.<f>` 即使声明正确也可能运行时缺失（agent 没报告该槽），运行时兜底仍有存在价值。编译前端只负责把「静态就错」的提前到 0 token。

### 7.4 单测清单（`src/test/scala/nebflow/core/entity/FlowDagCompilerSpec.scala`）

**每错误码一个用例**（构造最小错误 DAG → 断言返回对应 `[E-<code>]` 且格式含 `node '<id>'.<fieldPath>` + `— 修正建议`）：

| 组 | 用例 |
|---|---|
| 语法层 | E-001 空 nodes；E-002 id 含 `.`；E-003 空 agent；E-005 onComplete 畸形；E-007 maxFanout=0；E-008 maxLoop=0；E-010 maxRetries=-1；E-014 cases={}；E-015 parallel=[]；E-016 min>max；E-017 缺 name |
| 占位符 | E-101 静态节点含 `{{item}}`（v4 join 事故回归）；E-102 template 含 `{{iteam}}`；E-103 `{{item` 未闭合 |
| 引用 | E-201 redo 引用 `$judge1.slots.failBlocks` 未声明（redo 冒烟事故回归）+ 字面量 slotField 未声明 + 自引用；E-202 switch case 指向幽灵节点；E-203.1 entry 不在 nodes；E-203.2 孤儿节点（断言 warning 不阻塞）；E-203.3 无条件环 A→B→A；E-204 `$params.ghost`；E-205 `$ghost.output`；E-206 `$worker.all.output` 而 worker 非 template；E-207 switch `$ghost.verdict` |
| 类型 | E-301 parallel.slots 引用 string 槽；E-303 `$t.all.slots.f` 未声明；E-304 双 fanout 共享 template |
| 正例 | **R3 实证**：planner(`outputs:{blocks:"array"}`, input=`$task`) → fanout(`parallel:{slots:"$planner.slots.blocks",template:"worker"}`) → worker(input 含 `{{item}}`/`{{index}}`/`{{len}}`) → join → judge(`outputs:{failBlocks:"array"}`) → switch(`$judge.verdict`, cases pass→$return / fail→redo) → 断言 **零错误零警告** |
| 回归 | 现存 8 个活动预定义 flow.json（flows/ 目录，entity-creator 已转 skill）逐个编译 → 零错误（防误杀） |

**集成断言**（FlowExecuteToolSpec 扩展）：错误 DAG 调用 FlowExecute → 返回 `[E-<code>]` 错误，且**无 dagnode-*/bridge-* actor 创建、无 LLM 调用（0 token）、无 flowStarted WS 事件、RunningFlowRegistry 条目数不变**。

---

## 8. 验收条件

1. **冒烟测试（硬性）**：对每个错误码构造一个最小错误 DAG，`FlowExecute` 调用**返回时同步**收到 `[E-<code>]...` 错误。自动化断言：0 agent spawn（无 `dagnode-*`/`bridge-*` actor）、0 LLM 调用（0 token）、无 `flowStarted` WS 事件、`RunningFlowRegistry` 无新条目——**错误在提交时拦截，执行从未开始**。
2. **正例端到端（R3 实证）**：planner(`outputs.blocks` array) + judge(`outputs.failBlocks`) + dynamic fanout template 含 `{{item}}`/`{{index}}`/`{{len}}` 的正确 DAG 编译零错误，并完整执行成功（fanout N 实例 → join → switch → $return）。
3. **回归（零误杀）**：现存预定义 flow（`~/.nebflow/flows/` 8 个活动 flow.json）编译零错误——编译前端必须向后兼容所有合法 DAG。
4. **单测覆盖**：FlowDagCompilerSpec 每个错误码 ≥ 1 用例（§7.4 清单），全部通过（`sbt "testOnly nebflow.core.entity.FlowDagCompilerSpec"`）。
5. **错误格式**：所有编译错误输出匹配 `[E-<code>] node '<id>'.<fieldPath>: <原因> — <修正建议>`（单测断言）。
6. **共享入口**：FlowExecute / LoadTool / FlowTriggerTool 三处调用同一 `FlowDagCompiler.validate`，错误码与格式一致（测试覆盖 LoadTool 路径报同一 E-码）。
7. **分界正确**：§6.2 运行时判定项未被编译期误拦（switch verdict 值、slots 值、maxLoop 超限、onError 分支仍运行时处理；`$params` 声明但未传仍为合法降级占位符）。

---

## 9. 涉及文件

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/entity/FlowDagCompiler.scala` | **新增**：编译前端（§7.1） |
| `src/main/scala/nebflow/core/tools/FlowExecuteTool.scala` | 调用点 1：decode 成功后插入编译器，编译错误 0 spawn 返回（L164-181 区域） |
| `src/main/scala/nebflow/core/tools/LoadTool.scala` | 调用点 2：validateFlowDag 复用编译器（L178-199） |
| `src/main/scala/nebflow/core/tools/FlowTriggerTool.scala` | 调用点 3：触发时复用编译器 |
| `src/test/scala/nebflow/core/entity/FlowDagCompilerSpec.scala` | **新增**：§7.4 单测清单 |
| `src/test/scala/nebflow/agent/FlowExecuteToolSpec.scala` | 扩展：0 spawn / 0 token 集成断言 |
| `~/.nebflow/skills/flow-execute/SKILL.md` | §3.4 更新：引擎现在**提交时校验**（错误码表引用本设计），易错点第四条「引擎不校验占位符残留」改写为「提交时静态校验，E-1xx/E-2xx」 |
| `~/.nebflow/docs/Nebflow/20260826_flow-dag-compiler-design.md` | 本文档（冻结） |

**不改**：FlowDagExecutor 运行时检查（保留为防御兜底）、FlowStructure.validate 结构规则（组合复用）、EntityLoader.validateFlow（组合复用）。

---

## 10. 待确认决策点（实施前需用户裁定）

| # | 决策点 | 推荐 |
|---|---|---|
| D1 | **E-201 planner/entry 豁免**：slots 引用校验是否豁免 entry/planner 节点？ | **不豁免**，规则统一：任何 slots 引用（含对 entry 节点的引用）都要求源节点声明 outputs；entry 自身 slots 在运行前不存在，自引用必然运行时缺失 → 编译期拦。`$task` 是 entry 唯一合法输入补充 |
| D2 | **E-203.2 孤儿节点定级**：warning 还是 error？ | **v1 = warning**（不阻塞，防误杀过渡；dead-code 语义与编译器一致）；后续版本收紧为 error |
| D3 | **E-304 template 多主**：多 fanout 共享同一 template 直接禁止？ | **v1 = error**（实例 id 必然冲突，聚合错乱，无合法用途） |
| D4 | **E-017 name 缺失**：FlowExecute 未传 name 是否报错？ | **v1 = error**（WS/UI 事件无法区分运行；现静默补 "dynamic-flow" 是缺口） |
| D5 | **maxFanout 上限**：E-007 的硬上限定多少？ | **256**（64 页 deck × 4 有余量；防失控 fanout） |
| D6 | **现有检查码化深度**：circe decode 错误 / FlowStructure / validateFlow 的 ad-hoc 字符串是否全部重写为 `[E-<code>]` 格式？ | **是**（统一报告格式是编译器体验的核心；内部保留原字符串可作 `<原因>` 详情） |

---

## 11. 参考

- 主仓代码：`FlowExecuteTool.scala`（L147-212）、`FlowStructure.scala`（validate L141-198 / reachFrom L73-84 / analysisEdges L55-58）、`EntityTypes.scala`（FlowDagDef L352-401 / FlowNode L303-349 / NodeRoute L219-250 / FlowParamSpec L412-431）、`FlowDagExecutor.scala`（resolveInput L180-251、占位符拦截 L283-289、slots 运行时检查 L733-751、switch 表达式提取 L1170-1204、joins/expectedJoins L162-170）、`FlowDagRunner.scala`、`EntityLoader.scala`（validateFlow L422-438）、`LoadTool.scala`（validateFlowDag L178-199）、`FlowTriggerTool.scala`（validateFlowParams L86-）
- 事故记录：v4 join `{{}}` 残留（planner+16 worker 跑完才报）；redo 冒烟 `$judge1.slots.failBlocks` 未声明 outputs（qa+judge1 跑完才报）；#414（fix 4a slot-ref 引用语法 / fix 4b 占位符残留）
- flow-execute skill：`~/.nebflow/skills/flow-execute/SKILL.md` §3.4 易错点四条、§3.2 路由四形态与 `{{len}}` 承诺
- 用户裁定：2026-08-26 08:27「我们要以编译器的思想去设计」（flow 校验层）；08-26 00:09 FlowExecute/FlowTrigger 互补
