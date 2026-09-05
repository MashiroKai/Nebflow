# Flow 引擎「动态并发」能力设计（dynamic fanout）

> 需求提出：用户 2026-08-24 18:41（flow 内 agent 与调用者实时调整并发节点数量）
> 本文档为设计分析，不触碰产品代码。所有代码证据来自当前主仓 `/Users/dev/Claude code/Nebflow`（Scala 版，v1.4.1-beta.51 分支）。
> 状态：draft v1.0 · 待用户/Manager 确认后派发实施

---

## 1. 需求

flow 需要支持 flow 内 agent 和调用者**实时调整并发节点的数量**——例如调研类 flow：有时并行 3 个方面、有时 1-2 个就够，并发数写死（当前 presentation-prep 固定 4 路）缺乏灵活性。两层能力：

1. **flow 内 agent 按需 fanout**：planner（上游节点）根据任务需求决定并行检索/处理的路数 N（调研 3 方面 → 3 路并行），flow 动态展开 N 个并行节点。
2. **调用者实时调整**：调用者（Nebula/用户，FlowTrigger 触发方）能指定/调整并发数——触发时传参（如 prompt 里指定并发），甚至 flow 运行中增减（高级）。

---

## 2. 现状机制（代码证据）

### 2.1 fanout 完全静态——写死的节点 ID 列表

`NodeRoute.Parallel` 的 fan 字段是**静态 `List[String]`**，flow.json 里写死节点 ID：

```scala
// src/main/scala/nebflow/core/entity/EntityTypes.scala
case NodeRoute.Parallel(fan: List[String], onFail: OnFailMode)
```

呈现方式（`~/.nebflow/flows/`）：

| flow | 并行声明 | maxFanout |
|---|---|---|
| presentation-prep | `planner.onComplete: { "parallel": ["r1","r2","r3","r4"] }` | 4 |
| research | `planner.onComplete: { "parallel": ["r1","r2"] }` | 2 |
| code-review / entity-creator | 无 parallel（serial + switch） | 默认 4 |

**改变路数只能改 flow.json**（改文件 → 热加载/重启），运行时无法按需变化。

### 2.2 maxFanout 是「静态编译期上限」，不是动态能力

`FlowStructure.validate` 在**加载时**检查静态扇出数量，超过即拒绝加载：

```scala
// src/main/scala/nebflow/core/entity/FlowStructure.scala (L125-128)
parallelRoutes(flow).foreach { (owner, p) =>
  if p.fan.size > flow.maxFanout then
    errors += s"parallel fan of node '$owner' has ${p.fan.size} branches — exceeds maxFanout ${flow.maxFanout}"
```

语义是 R8-P2 的成本护栏（guardrail）：静态 DAG 声明几个分支，校验是否超上限。**与运行时按 slots 数量动态展开无关。**

### 2.3 slots 机制——模板替换，无引擎级下标取项

- planner 声明 `"outputs": { "topics": "array" }`，agent 通过 FlowReport 工具提交 slots。
- 下游用 `$<nodeId>.slots.<field>` 模板变量引用；array slot 插入 compact JSON（`["a","b","c"]`）：

```scala
// FlowDagExecutor.resolveInput (L166-176)
case Some(json) if json.isString => json.asString.getOrElse("")
case Some(json)                  => json.noSpaces   // array → ["a","b","c"]
```

- **当前"取第 N 项"是提示词级**：r1 的 input 写 `FIRST 项`，r2 写 `SECOND 项`，让 LLM 自己从插入的 JSON 数组里挑。引擎没有 `$planner.slots.topics[0]` 这类下标语法。

### 2.4 执行模型——barrier join 计数（可复用于动态展开）

`FlowDagExecutor.parallelDispatch`：按 fan 列表 fork 每 target 一个 fiber；多入边节点为 counting barrier（`pendingJoins` 计数，最后一个到达者激活 join 并继续走）。onFail 支持 abort（fail-fast 穿透兄弟分支）/ collect（失败分支写占位输出、结算 barrier）。**这套机制对 fan 数量不敏感**——动态展开只需在 fork 前把「fan 列表」换成「按 slots 数量生成的实例列表」。

### 2.5 调用者传参——只有 flow + prompt

`FlowTriggerTool` 仅 `flow` + `prompt` 两参数，prompt 直接作 entry 节点的 `$task`。无结构化参数通道；「指定并发」只能写在 prompt 自然语言里，靠 planner LLM 听从。

### 2.6 运行中控制——只有整体取消

`RunningFlowRegistry.cancel(instanceId)` 整体取消 + 内部 failFast（兄弟分支失败时穿透）。无「增减并发」通道。前端 `flowStarted` 一次性渲染静态 nodes/edges，`flowProgress` 逐节点上报状态。

### 2.7 结论：当前不支持动态 fanout

| 能力 | 现状 | 缺口 |
|---|---|---|
| 按 slots 数量动态展开 | ❌ fan 是静态 List[String] | 需要「fan 模板」语法 + 运行时实例化 |
| 引擎级数组取项 | ❌ 只有提示词级 FIRST/SECOND | 需要下标/占位符语法 |
| 下游引用 N 个实例输出 | ❌ 下游写死 `$r1.output..$r4.output` | 需要聚合引用语法 |
| 触发时指定并发 | ⚠️ 只能 prompt 自然语言 | 需要结构化 params |
| 运行中增减并发 | ❌ 只有整体 cancel | 见 §4.2 评估 |

---

## 3. 动态 fanout 设计（核心）

### 3.1 语法设计——最小改动：`parallel` 增加动态形态

`onComplete.parallel` 字段值保持现有语义（`["r1","r2"]` = 静态 fan，**现有 flow 零改动**），新增对象形态表达「按 slots 数组动态展开」：

```json
"onComplete": {
  "parallel": {
    "slots": "topics",          // 本节点 outputs 里声明的 array slot 名
    "template": "researcher"    // 模板节点 ID（flow.json 中正常声明的节点）
  },
  "onFail": "collect"           // 可选，缺省 abort（与静态形态同位置）
}
```

> **实现注（2026-08-24 定稿）**：`onFail` 放在 `parallel` 的**平级**（与静态 array 形态 `{"parallel": [...], "onFail": ...}` 一致），不放进 parallel 对象内部——两种形态语法统一，解码器/编码器/DAG 视图一致。早稿曾把 onFail 写在 parallel 对象内，已修正。

**语义**：本节点完成后，读其 `slots.topics`（array）→ N = 数组长度（clamp 到 `maxFanout`，见 §3.4）→ 以 `researcher` 节点为模板**实例化 N 个运行时节点** `researcher#1 … researcher#N`，并行执行。

### 3.2 模板节点与占位符

模板节点在 flow.json 中**正常声明**（agent/input/onComplete/onError/outputs 全都有），只是不被 entry 或任何静态边直接引用，而是被 `parallel.template` 引用。input 模板支持两个运行时占位符：

| 占位符 | 替换值 | 示例 |
|---|---|---|
| `{{item}}` | 当前数组元素（string 去引号，非 string 插 compact JSON） | `深入调研子话题：{{item}}` |
| `{{index}}` | 1-based 序号 | `你是 Researcher {{index}}` |

flow.json 示例（research flow 迁移后）：

```json
{
  "name": "research",
  "entry": "planner",
  "maxFanout": 4,
  "nodes": {
    "planner": {
      "agent": "planner",
      "input": "$task\n\n把任务拆成若干个正交子话题，每个一行 scope 说明。数组长度即并行路数（≤ $params.fanout 或 ≤4）。",
      "outputs": { "topics": "array" },
      "onComplete": { "parallel": { "slots": "topics", "template": "researcher", "onFail": "collect" } }
    },
    "researcher": {
      "agent": "researcher",
      "input": "你是 Researcher {{index}}。只深挖第 {{index}} 条分路：{{item}}\n\n每条结论必须带可验证来源；检索不到标「待用户提供」不编造。遵守 system.md。",
      "onError": "restart",
      "maxRetries": 2,
      "onComplete": "verify"
    },
    "verify": {
      "agent": "verifier",
      "input": "逐路抽查以下研究结论……\n\n=== 各路素材 ===\n$researcher.all.output",
      "onComplete": "writer"
    },
    "writer": {
      "agent": "writer",
      "input": "综合各路素材与验证报告写最终报告……\n\n=== 各路素材 ===\n$researcher.all.output\n\n=== 验证报告 ===\n$verify.output",
      "onComplete": "$return"
    }
  }
}
```

### 3.3 聚合引用——`$<template>.all.output`

N 动态时下游无法写死 `$r1.output..$rN.output`，新增聚合语法：

- `$researcher.all.output` → 所有实例 output **按 index 升序**拼接（每条带 `=== Track {{index}} ===` 分隔头）。
- `$researcher.all.slots.<field>` → 同法聚合（string slot 拼文本，array slot 拼数组——与单节点 slots 语义一致）。
- 实现：`resolveInput` 里 `$<id>.all.output` 走 ExecState 中「模板 → 实例列表」的注册表，读取各实例 output 拼接。

**可选（阶段 2 再做，非必须）**：引擎级下标取项 `$researcher.slots.topics[0]` —— 让模板节点内部引用上游数组的特定项，替代提示词级 FIRST/SECOND。优先级低：`{{item}}` 已覆盖 90% 场景。

### 3.4 执行细节（FlowDagExecutor 改动点）

`parallelDispatch(from, fan, onFail)` 扩展为「静态 fan 或动态展开」两条路径，动态路径：

1. **读 slot**：`st.slots.get(from)(slotField)` → 必须是 array，否则 fail（错误信息明确）。
2. **确定 N**：`N = min(len, flow.maxFanout)`；若 `len > maxFanout`，**clamp + warn**（成本护栏语义：maxFanout 是上限不是目标值；planner 提示词里声明上限，超了静默截断并记录 warn，避免脆性失败）。`len == 0` → 按 onFail 策略处理（abort：报错「planner 未产出任何 topic」；collect：走聚合空结果）。
3. **实例化**：为 i ∈ 1..N 生成实例节点 `(template + "#" + i)`：
   - input = 模板 input，`{{item}}`/`{{index}}` 替换为第 i 个元素 / i；
   - agent / onComplete / onError / maxRetries / outputs 从模板节点复制。
   - 实例节点写入 ExecState 的新注册表 `instances: Ref[IO, Map[String, List[String]]]`（模板 → 实例 ID 列表，供 `$template.all` 聚合用）。
4. **注册运行态**：`RunningFlowRegistry.update(instanceId)` 增补 N 个 NodeState；`emitProgress` 为每个实例发 Running/Completed（前端在 `flowStarted` 静态渲染之上叠加实例节点事件——新 WS 事件类型 `flowNodesAdded`，旧前端忽略之，向后兼容）。
5. **fork**：对 N 个实例 `walk(...).start`，join 后 `mergeOutcomes`——**完全复用现有 barrier 计数与 onFail 逻辑**（barrier 按 expectedJoins 武装，模板节点下游的 join 结构与静态一致）。

**barrier 语义确认**：模板节点的 `onComplete` 指向静态 join（如 verify），`FlowStructure.reachFrom` 对模板节点的下游分析在加载时静态成立，运行时 N 个实例共享同一组 barrier 计数（`pendingJoins` 累加 N 次）——与现有静态 fan 的「多分支汇聚同一 join」完全同构，**无需新 barrier 逻辑**。

### 3.5 类型与解析改动点

| 文件 | 改动 |
|---|---|
| `EntityTypes.scala` | `NodeRoute` 增 `ParallelDynamic(slotField: String, template: String, onFail: OnFailMode)`；Decoder：`parallel` 字段为 array → 静态 Parallel（原路径），为 object（含 slots+template）→ ParallelDynamic；Encoder 对称。模板节点本身是普通 FlowNode，无需新字段 |
| `FlowStructure.scala` | `routeTargets/displayEdges/analysisEdges` 对 ParallelDynamic 输出模板节点的边（与静态 fan 展开为模板节点一条边的语义一致，用于 join 分析）；`parallelRoutes` 返回两种形态；`validate`：动态形态**跳过 fan.size 检查**（运行时 clamp），converge/join 检查基于模板节点下游照常 |
| `FlowDagExecutor.scala` | `parallelDispatch` 动态路径（§3.4）；`resolveInput` 增 `$<template>.all.output` / `.all.slots.<field>` 聚合；ExecState 增 `instances` 注册表；`executeNode` 支持实例节点查表（`flow.nodes` 查不到时查实例注册表） |
| `RunningFlowRegistry.scala` | 不需要新 API——`update(instanceId)` 已可增补 NodeState（动态实例在展开时登记） |
| `FlowDagRunner.scala` | 无改动（flowDef 是静态声明，动态展开发生在执行期） |
| WS 前端协议 | 新增可选事件 `flowNodesAdded`（实例节点 + 边），旧前端忽略；`flowStarted` 保持静态渲染 |

### 3.6 边界情况

| 情况 | 行为 |
|---|---|
| slots 字段缺失 / 非 array | 按 onFail：abort → 明确报错；collect → 空聚合继续 |
| len > maxFanout | clamp 到 maxFanout + warn（planner 提示词声明上限） |
| len == 0 | abort → 报错「planner 未产出 topic」；collect → 下游拿到空聚合 |
| 模板节点不存在 | 加载时校验报错（与静态 fan 指向未知节点一致，EntityLoader.validateFlow） |
| 模板节点被静态边同时引用 | 允许（模板节点本身可执行，如复用现有 r1 语义）；`$template.all` 只聚合动态实例，不含静态执行 |
| N=1 | 与串行等价，barrier 计数=1，正常走通 |

---

## 4. 调用者调整设计

### 4.1 触发时指定并发（阶段 2a）——结构化 params

**语法**：`FlowTriggerTool` 增加可选 `params: object`；flow.json 顶层声明参数 schema（校验 + 文档）：

```json
{
  "name": "research",
  "params": {
    "fanout": { "type": "int", "default": 2, "min": 1, "max": 4, "description": "并行检索路数" }
  },
  "nodes": {
    "planner": {
      "input": "$task\n\n拆分 $params.fanout 个正交子话题，每个一行 scope…",
      ...
    }
  }
}
```

调用示例：

```
FlowTrigger(flow: "research", prompt: "调研 CZT 探测器读出噪声的三种抑制方案", params: { "fanout": 3 })
```

**执行链路**：
1. `FlowTriggerTool.call` 读取 `params` → 校验（类型/范围/未知 key）→ 传入 `RunFlow`。
2. `FlowDagRunner.RunFlow` 增 `params: JsonObject` 字段 → `FlowDagExecutor.execute` 接收。
3. `resolveInput` 增 `$params.<key>` 模板变量（从 params 读，缺失 → `[param fanout not provided]` 占位，planner 提示词兜底说明）。
4. planner（LLM）把 `$params.fanout` 作为**路数上限**：输出 ≤ 该值的 topics 数组；实际路数由任务复杂度决定（想用 2 路就用 2 路，上限 3）。
5. 不传 params 时用 flow.json 的 `default`（或提示词里的默认值）。

**为什么不只靠 prompt 自然语言**：prompt 指定是现状能力（LLM 会听），但无校验、无 schema 文档、无法被下游程序化引用（如前端 UI 选择器）。params 是结构化、可校验、可扩展的通道，prompt 自然语言退化为兜底。

### 4.2 运行中调整（阶段 2b）——可行性评估：**建议不做 fiber 级，降级为「多级 fanout」**

**直接做法（fiber 级增减）的复杂度**：
- 「增」：fork 的 fiber 无集中管理（`fan.traverse(...).start` 后只 join），新分支需要重新 arm barrier（`pendingJoins` 计数已按旧 N 武装）、新实例注册、状态同步——触碰并发核心。
- 「减」：需要 pierce 运行中的 agent（可复用 cancel/failFast 信号）并**修正 barrier 计数**（`pendingJoins` 按旧 N 武装，提前减 N 会让 join 提前激活，下游收到残缺结果）——竞态窗口大。
- 估计工作量 2-3 倍于阶段 1，且正确性风险集中在最脆弱的 barrier 计数上。

**收益**：调研类 flow 运行分钟级，中途增减并发的真实场景少；且「planner 一次性决定路数」已覆盖绝大多数需求。

**替代方案（推荐，成本低得多）——多级 fanout（阶段 3 并入）**：
- flow 内任何中间节点都可以再次声明 `parallel` 动态展开——实现「flow 内 agent 按需调整后续并发」而不触碰运行中 fiber。
- 例：content-planner 汇总 4 路后发现第 2 路素材不足 → 通过 switch 路由到一个「补充检索」子图，该子图按需 fanout 2 路 → 汇聚后回到主线。DAG 语义完整、复用阶段 1 引擎、barrier 天然正确。
- 运行中「人为增减」的最优解：**cancel 当前 flow + 带新 params 重跑**（现状已有 cancelFlow 通道，阶段 2a 后重跑可指定新并发）。

**结论**：2b 不做 fiber 级增减，纳入 backlog（若未来出现真实高频场景再评估）；「运行中调整」的产品语义由 **params 重跑 + 多级 fanout** 覆盖。

---

## 5. 兼容性

| 面 | 保证 |
|---|---|
| 现有 flow 定义 | `parallel` 为 array 的静态形态解码路径不变，code-review / entity-creator / research / presentation-prep / release-stable / git-merge / nebflow-review-merge / memory-consolidation / weekly-summary 全部零改动通过 `FlowStructure.validate` |
| 模板变量 | `$task` / `$<id>.output` / `$<id>.slots.<field>` 语义不变；新增 `$params.*`、`$<id>.all.*`、`{{item}}`/`{{index}}` 均为增量 |
| maxFanout | 静态语义不变（编译期上限）；动态语义为运行时 clamp 上限；默认 4 不变 |
| 前端 | `flowStarted`/`flowProgress`/`flowCompleted` 事件格式不变；新增 `flowNodesAdded` 为可选事件，旧前端忽略 |
| 解码器 | 严格区分 array（静态）/ object（动态），未知字段解析失败（circe 默认）——避免静默吞错 |
| FlowTrigger | 原两参数调用不变；`params` 可选 |

---

## 6. 分阶段实施 + 验收条件

### 阶段 1：引擎动态 fanout（核心）

改动：EntityTypes（NodeRoute 新形态 + 解码）、FlowStructure（动态跳过 size 校验 + 模板边）、FlowDagExecutor（parallelDispatch 动态路径 + 聚合引用 + 实例注册表）、RunningFlowRegistry（运行时增补 NodeState）。

**验收（硬性冒烟在首位）**：
- [ ] **冒烟测试**：`sbt run` 真实启动服务 → 用 FlowTrigger 触发一个动态 fanout 测试 flow（planner 输出 3 topics）→ 断言 `flowProgress` 出现 3 个实例节点的 Running/Completed、`flowCompleted` success=true、最终输出包含 3 路素材。
- [ ] 单元测试：`ParallelDynamic` 解码（array→静态 / object→动态）；`len==0`、`len>maxFanout`（clamp+warn）、`len==1`；`$template.all.output` 按 index 排序拼接；模板节点不存在 → 加载报错。
- [ ] barrier 正确性：N 个实例汇聚到静态 join（verify）→ join 恰好激活一次（复用 ParallelSpec/BarrierSpec 的断言方式）。
- [ ] 回归：现有 flow 结构校验全过（`FlowStructureSpec` 全绿）；code-review / entity-creator 端到端各跑通一次。
- [ ] 向后兼容：静态 `parallel` flow（research 未迁移前）行为与现状完全一致。

### 阶段 2：调用者触发时传参

改动：FlowTriggerTool（params 参数 + schema 校验）、FlowDagRunner（RunFlow.params）、FlowDagExecutor（`$params` 注入）、EntityLoader（flow.json `params` 声明解析 + 校验）。

**验收**：
- [ ] **冒烟**：真实启动 → `FlowTrigger(flow:"research", prompt:…, params:{fanout:3})` → planner 输出 3 topics → 3 路并行执行完成。
- [ ] 单测：params 类型/范围校验（`fanout:"abc"` 拒绝、`fanout:99` 超 max 拒绝）；缺省用 default；未知 key 拒绝或忽略（设计：拒绝，防拼写错误）。
- [ ] 无 params 调用（旧调用方式）行为不变。

### 阶段 3：迁移 + 多级 fanout

改动：research / presentation-prep flow.json 迁移到动态形态（§3.2 示例）；文档更新（GUIDE.md flow 章节）。

**验收**：
- [ ] research 迁移后：不传 params 走默认 2 路；`params.fanout:3` 走 3 路——两者结果均正确。
- [ ] presentation-prep 迁移后：用户裁定路数（3/4/5）均正确产出 deck-plan（含每路素材覆盖验证）。
- [ ] 多级 fanout：构造「补充检索」子图（switch → 动态 fanout → join 回主线），验证中间节点二次展开 + barrier 正确。
- [ ] 前端：新 `flowNodesAdded` 事件渲染实例节点（可选，旧前端忽略不回归）。

### 阶段 4（backlog，默认不做）

运行中 fiber 级增减并发——需要集中式 fiber 管理 + 动态 barrier 修正，成本高收益低；产品语义由「cancel + params 重跑」「多级 fanout」覆盖。有真实高频场景再评估。

---

## 7. 改动文件清单汇总

| 文件 | 阶段 | 改动 |
|---|---|---|
| `src/main/scala/nebflow/core/entity/EntityTypes.scala` | 1,2 | NodeRoute.ParallelDynamic；FlowDagDef.params 声明 |
| `src/main/scala/nebflow/core/entity/FlowStructure.scala` | 1 | 动态 fan 跳过 size 校验；模板边参与 join 分析 |
| `src/main/scala/nebflow/core/entity/FlowDagExecutor.scala` | 1,2 | 动态展开 + 聚合引用 + `$params` 注入 + 实例注册表 |
| `src/main/scala/nebflow/core/flow/RunningFlowRegistry.scala` | 1 | 无需新 API（update 已够），确认覆盖 |
| `src/main/scala/nebflow/core/tools/FlowTriggerTool.scala` | 2 | params 参数 + schema 校验 |
| `src/main/scala/nebflow/core/flow/FlowDagRunner.scala` | 2 | RunFlow.params 透传 |
| `src/main/scala/nebflow/core/entity/EntityLoader.scala` | 2 | flow.json params 解析 + 模板节点存在性校验 |
| WS 前端协议（`protocol.scala`/ws.js） | 1(可选),3 | `flowNodesAdded` 事件 |
| `~/.nebflow/flows/research/flow.json`、`presentation-prep/flow.json` | 3 | 迁移到动态形态 |
| `~/.nebflow/docs/GUIDE.md` | 3 | flow 文档补动态 fanout 章节 |
