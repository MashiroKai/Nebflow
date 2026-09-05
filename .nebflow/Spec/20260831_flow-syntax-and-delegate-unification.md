> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Flow 语法详解 + Delegate 融入 Flow 统一架构分析

> 阶段文档（完成即冻结）。纯分析，未修改任何代码/定义文件。
> 依据：`~/.nebflow/skills/flow-execute/SKILL.md`（四次实战实证铁律）、引擎源码 `~/Claude code/Nebflow`（FlowDagCompiler / FlowDagExecutor / FlowDagRunner / FlowStructure / FlowExecuteTool / FlowTriggerTool / FlowReportTool / DelegateTool / SubTaskTool / AgentCore / EntityTypes）、`~/.nebflow/flows/` 9 个预定义 flow 定义、设计文档 `20260826_flow-dag-compiler-design.md`（36 错误码三层体系）。
> 日期：2026-08-31。

---

# 第一章 Flow 语法详解（完整语法参考）

## 0. 工具分界总览——四个入口一张表

Nebflow 的「派一个 agent 干活」有四个工具，共享同一套 Agent 执行内核（AgentActor），但入口语义、结果形态、可用范围不同。写 DAG 前先对号入座：

| 工具 | 形态 | 可用者 | 结果注入方式 | 引擎路径 |
|---|---|---|---|---|
| **FlowExecute** | 内联 DAG（即用即弃，不落盘） | 机制层注入：全部 team 成员 + Nebula（Nebula 路由边界：不再直接写 DAG） | `ImmediateInput`（`[Flow '<name>' completed]`，source=flow） | FlowDagRunner → FlowDagExecutor → AgentActor（dag- 会话，isFlowNode=leaf） |
| **FlowTrigger** | 预定义 flow（`flows/<name>/flow.json`，白名单触发） | agent.json `flows` 白名单声明者 | `ImmediateInput`（同上） | 同一 FlowDagRunner → FlowDagExecutor |
| **Delegate** | 单个自主 worker（Nebula 派 standalone agent） | Nebula 专属（NebulaExclusiveTools） | `ExternalEvent`（source=delegate-地址，eventType=completed） | AgentActor（delegate- 会话）+ BackoffSupervisor（background）/ persistentAdapter（persistent） |
| **SubTask** | self-clone 后台 worker | team 成员（fixedToolsFor category=team） | `ExternalEvent`（source=subtask） | AgentActor（subtask- 会话）+ adapter |

**决策提示（先查后写）**：单个自主 worker → Delegate/SubTask；任务匹配预定义 flow 形状 → FlowTrigger（别重写 DAG）；形状随任务的多 agent 编排 → FlowExecute。两者共享同一执行引擎，可放心混用。

**深度上限**：四者统一 `MaxDepth = 5`（FlowExecuteTool / DelegateTool / SubTaskTool 同值；嵌套 Delegate/SubTask 深度计入）。

---

## 1. 动态 FlowExecute DSL（即用即弃内联 DAG）

### 1.1 整体结构

```
FlowExecute(
  name: "64页deck并行",          // 必填：显示名（WS 事件/UI），空 → E-017
  description: "64 页 deck 并行生成 + QA",  // 必填：一行说明
  prompt: "<自包含任务输入，flow 入口节点拿到>",  // 必填
  nodes: { <DAG: node-id → 节点定义> },      // 必填
  entry: "<入口节点 id>",                    // 必填
  maxFanout: 64,               // 可选，默认 4：本 flow 声明的并行规模（非系统限制）
  maxLoop: 10,                 // 可选，默认 10：循环保护（重做回路轮数上限）
  params: { ... }              // 可选：结构化参数，节点输入用 $params.<name> 引用
)
```

- `maxFanout`：**本 flow 声明的并行规模**——64 页 deck 就声明 64，引擎 `N = min(len, maxFanout)`（裁剪时 warn）。**不是系统限制**：无全局并发上限、无限流（用户 08-25 裁定：删除 Semaphore 分波与全局硬上限，信任 agent）。引擎只有硬顶 256（`MaxFanoutHardLimit`，E-007）。
- `maxLoop`：循环保护。超限时聚合器收到「N 块仍 fail」清单，交付标注待人工处理——不会无限循环。
- `params`：`{ <name>: { "type": "int"|"string"|"bool", "default": ..., "min"/"max": (仅 int), "description": ... } }`。缺失键 → `[param <name> not provided]` 占位符进提示（提示词可写 fallback 措辞）。
- **监管已内置**：动态 flow 节点编译期注入监督默认值（`applyDynamicDefaults`：onError=Restart + maxRetries=1，显式写的 JSON 优先）；LLM 停摆自动重试 + checkpoint restart（断点恢复，`#414 P1-P4`）。**不要给节点加超时/重试类提示词指令**——引擎已管，提示词层重复指令反而干扰。
- **prompt 必须自包含**：每个节点 agent 从干净上下文启动，不共享调用者会话记忆。每个节点 input 要写清「产出什么 + done 是什么」。

### 1.2 节点定义（FlowNode）

```json
"nodes": {
  "planner": {
    "agent": "Explorer",                  // 必填：全局 agent 名（动态 flow 只从全局库解析，E-401）
    "input": "$task",                     // 必填：输入模板（见 §1.4）
    "onComplete": "worker-fanout",        // 必填：路由（见 §1.3）
    "onError": "stop"|"resume"|"restart", // 可选，默认 stop
    "maxRetries": 2,                      // 可选，默认 0（restart 时重试次数）
    "outputs": { "blocks": "array", "summary": "string" },  // 可选：结构化槽声明
    "userFacing": false                   // 可选，默认 false：终审型节点豁免展示类工具剥离
  }
}
```

字段明细：
- `agent`（必填）：全局 agent 名。动态 flow **只从全局库解析**（不落盘 `flows/<name>/agents/`，不会误命中预定义 flow 的专属 agent——`dynamic=true` 时走 `EntityLoader.loadAgent`，预定义走 `loadFlowAgent` flow-local 优先 + 全局 fallback）。
- `input`（必填）：模板字符串。语法见 §1.4。**静态节点禁 `{{`，template 节点仅 item/index/len**（铁律 1/2）。
- `onComplete`（必填）：路由，四形态见 §1.3。
- `onError`：失败策略——`stop`（默认，中止 flow）/ `resume`（记日志继续路由）/ `restart`（重执行，最多 maxRetries 次）。restart 分支支持 **checkpoint 恢复**：重试的是同一 dag 会话（从持久化消息恢复 + continue 指令），不是从头重跑——20 分钟 planner 上下文可在上游停顿后幸存。
- `maxRetries`：restart 的重试预算。动态 flow 默认注入 1；显式 0 保持 0。
- `outputs`：结构化槽声明（slotName → `"string"|"array"`）。**下游 `$<node>.slots.<field>` 引用要求源节点声明此字段，否则 E-201**。类型错误（`"strng"`）在 decode 期即拒绝。agent 通过 FlowReport 的 `slots` 参数上报。
- `userFacing`：轨道二 #5 专用化护栏——默认 false 时引擎剥离面向用户的展示类工具（Pop/AskUserQuestion），即使 agent.json 显式声明也扣掉（deck-v6 实证：提示词约束在错位人设下会被推翻）；仅「天然面向用户的终审型节点」开启。

### 1.3 路由（onComplete）四形态全解

#### 形态 A：string 直线 / 终止

```json
"onComplete": "next-node"     // 串行下一步
"onComplete": "$return"       // 结束并返回结果（flow 终止路径，E-211 强制至少一条）
```

#### 形态 B：switch 分支（verdict 开关）

```json
"onComplete": {
  "switch": "$agg.verdict",
  "cases": { "pass": "agg", "fail": "redo" },
  "default": "agg",           // 可选：无 case 命中时的保守路由
  "lenient": false            // 可选，默认 false：老 flow 无 FlowReport 时允许从文本猜 verdict
}
```

- 表达式形态 `$<node>.<field>`（E-207 校验：节点必须存在、形态必须匹配）。典型读 `$<node>.verdict`。
- **verdict 来源**：节点 agent 通过 FlowReport 工具上报结构化 verdict（执行上下文自动注入，无需声明）。**无 verdict 的 switch → flow 失败（strictVerdict=true 时；路由层还兜底一次）**。
- case 值递归接受全部三种路由（case 内可再 fan）。
- **verdict 归一化**（`VerdictFamily.matchCase`）：① 精确匹配（大小写不敏感）→ ② 同族 fallback（ok/pass/success/done/clean/fixed 一族；error/fail/reject/revise/abort 一族）→ ③ 子串包含（warn）。家族 fallback 是安全网**不是设计工具**——声明规范 case key，让 agent 精确上报。
- strictVerdict（flow 级，默认 false 迁移期）：true 时 switch 节点无有效 FlowReport verdict 即节点失败（onError 链生效）。

#### 形态 C：parallel 固定 N 路并行扇出

```json
"onComplete": { "parallel": ["r1","r2","r3"], "onFail": "abort"|"collect" }
```

- `onFail` 默认 `abort`（fail-fast：首个分支失败即 `failFast` 刺穿兄弟分支，取消信号与用户 cancel 同构）；`collect`（研究型 flow 用：失败分支写入占位符输出 `[node x failed: ...]`，结算它永远到不了的 barrier，join/verifier 看到标注的部分结果自行裁决）。
- 静态 fan 分支数 ≤ maxFanout（E-208）。
- **分支中途 `$return` 禁止**（E-209）：并行分支必须先汇聚（barrier join）再终止，先到先得会丢结果。
- 多入边节点 = **barrier join**（计数屏障）：恰好一次、全部上游到达后才激活（`inDegrees ≥ 2` + dynamicJoinNodes）。**join 不得混入串行到达**（E-210：串行边会腐蚀 barrier 计数）。
- **无墙钟超时**（R8 删除 NodeTimeout/GlobalFlowTimeout）：终止是事件驱动的——路由到 $return / onError Stop / 用户 cancel。节点存活靠 agent 内部 LLM 超时。

#### 形态 D：dynamic fan 动态扇出（slots 模板化实例化）

```json
"worker-fanout": {                        // 发起者：静态壳节点（铁律 4）
  "agent": "some-worker",
  "input": "按工单分派。",                 // 静态说明一句即可，禁 {{（铁律 1）
  "onComplete": {
    "parallel": { "slots": "$planner.pages", "template": "worker" },
    "onFail": "collect"
  }
}
"worker": {                               // template 节点：被展开 N 份（铁律 2）
  "agent": "some-worker",
  "input": "第 {{index}}/{{len}} 页（共 {{len}} 页）：{{item}}。产出 ≤ 200 tokens",
  "onComplete": "join"
}
```

- `slots` 两种写法：全引用 `"$planner.pages"`（解析为引用）或字面量 key `"pages"`（默认读 owner 自己的槽）。**slotField 未命中（引用错误）→ 明确报错**（无论 onFail），区别于「key 存在但数组为空」的正常降级（`noInstances`：abort → 报错；collect → 空聚合继续）。
- `N = min(数组长度, maxFanout)`（裁剪 warn）；实例 id = `template#1..#N`（顺序 = 数组顺序，跨级对齐的基石）。
- template 节点 input 仅允许三个占位符：`{{item}}`（数组元素）、`{{index}}`（1 基索引）、`{{len}}`（**数组原始总长度，maxFanout 裁剪前**——#414 引擎补实现后与 skill 承诺一致）。
- **template 多主冲突**（E-304）：同一 template 被 >1 个 ParallelDynamic 引用 → 实例 id 冲突，编译期拒绝。
- 实例输出/槽值通过聚合引用汇入下游：`$<template>.all.output`（每实例一个 `=== Track N ===` 头，index 序）、`$<template>.all.slots.<field>`（槽值数组/文本 join）。
- 实例的 onComplete 目标是 barrier join，运行时以 N 武装（静态分析只把它当 1 条边——`dynamicJoinNodes` 补齐）。
- 实例失败：onFail=abort → fail-fast；collect → 占位符 + 结算 barrier。

### 1.4 输入模板语法（resolveInput 替换顺序）

| 引用 | 语义 | 缺失时 |
|---|---|---|
| `$task` | 调用者的 prompt（入口节点拿） | — |
| `$<nodeId>.output` | 节点的文本输出 | `[output of <id> not found]` |
| `$<nodeId>.slots.<field>` | 结构化槽值：string 直接插入；array 插入紧凑 JSON | `[slots.<field> of <id> not found]` |
| `$<template>.all.output` | 动态实例全部输出，index 序，每实例 `=== Track N ===` 头 | `[output of <id> not found]` |
| `$<template>.all.slots.<field>` | 实例槽值（string 直接插入 / array 紧凑 JSON） | `[slots.<field> of <id> not found]` |
| `$params.<name>` | 声明参数（string 直插 / 其他紧凑 JSON） | `[param <name> not provided]` |

**替换顺序**（引擎固定）：`$params.*` → `$task` → `.all.slots.*` → `.all.output` → `.slots.*` → `.output`。全部用 `quoteReplacement` 防 agent 输出里的 `$`/`\` 被当正则组引用（Illegal group reference 事故）。

**关键**：
- 引用**不加引号**（铁律 5）：`$planner.slots.blocks` ✅；`"$planner.slots.blocks"` ❌ 被当字面量 key。
- **C1 模板携带一切**：下游需要什么上游信息，input 模板就必须显式列全（`$x.output` / `$x.slots.<field>`）。模板引用了还没跑的节点 → 注入字面量 `[output of x not found]`，提示词必须告诉 agent 这个占位符什么意思、该怎么办（release-stable reviewer 首轮模式）。

### 1.5 FlowReport——verdict 结构化输出

FlowReport 工具**由执行上下文自动注入**每个 flow 节点（`baseDef.tools :+ "FlowReport"`，预定义 + 动态同构）。

```
FlowReport(verdict: "pass", output: "<全文产出>", slots: { "blocks": [...] })
```

- `verdict`：switch 节点必须恰为声明 case key 之一；串行节点 `"done"`；通用二元 `"ok"/"error"`。
- `output`：下游拿到的 `$<node>.output`（优先于最后一条 assistant 消息文本）。
- `slots`：与节点 `outputs` 声明精确匹配（键齐全 + 类型正确 + 无多余键）——违反返回 ToolError 让 agent 同一轮内自纠（R8-P1 契约校验，最便宜的错误修复点）。
- 无契约节点（非 switch 无 outputs）：legacy 行为——任何 verdict 都收。
- 存储：`FlowReportStore`（sessionId → verdict/output/slots），executeAgent 完成后读取、终态清理。

### 1.6 语法铁律（四次实战实证——fpga-agent-deck → redo R1/R2/R3 → deck v5）

> 违反任何一条轻则节点拦截、重则占位符原样进提示导致 agent 拿到字面量无从工作。

1. **静态节点 input 禁止任何 `{{` 字符（字符级检查）**——onComplete 为字符串路由或 `$return` 的节点，input 出现 `{{` 即 E-101 拦截；**说明文字里写「{{ 双花括号」也会被拦**。要拿数组槽数据用 `$<node>.slots.<field>` 或 `$<template>.all.slots.<field>`。
2. **template 节点 input 仅允许 `{{item}}`/`{{index}}`/`{{len}}`**（E-102）。
3. **跨节点 slots 引用要求源节点声明 `outputs.field`**（E-201/301）。entry 节点（$task 直达）豁免，但显式声明更规范。
4. **fanout 发起者是静态节点**（worker-fanout/qa-fanout/redo 等调度壳）——自身 input 无 `{{}}`，一句静态说明即可。
5. **slots/`$task`/`$<node>.output`/`$params.<name>` 引用不加引号**。
6. **提交前自行核对三件事**：引用的节点 id 存在（E-205/202 已拦）、槽字段名与上游 `outputs` 声明一致（E-201 已拦）、静态节点无 `{{`（E-101 已拦）——#414 起引擎**提交期静态拦截**，错误在 0 spawn / 0 token 时返回。

### 1.7 编译错误码全表（FlowDagCompiler，提交期 0 spawn 拦截）

编译器风格：错误码 + 位置（`node '<id>'.<fieldPath>`）+ 原因 + 修正建议，一次调用全量收集（不 fail-fast）。设计文档冻结声称 36 码三层体系（`20260826_flow-dag-compiler-design.md`）；下述为代码现状实现码（33 个，E-203.x 子码合并计数差异）。

**Pass 0 语法层 E-0xx**

| 码 | 触发 | 修正 |
|---|---|---|
| E-001 | nodes 为空 | 定义 ≥2 节点（1 节点另有 E-212） |
| E-002 | 节点 id 非法字符（须 `[a-zA-Z0-9_-]+`） | 改名（`$<id>.output` 无法解析） |
| E-003 | agent 为空 | 填全局 agent 名 |
| E-006 | entry 为空 | 指向已声明节点 |
| E-007 | maxFanout ≤0 或 >256 | 声明本 flow 实际并行规模 |
| E-008 | maxLoop <1 | 正整数循环保护 |
| E-010 | maxRetries <0 | 非负整数 |
| E-014 | switch cases 为空 | 声明 verdict 路由 |
| E-015 | parallel fan 空 | 空 fan 运行期死锁 |
| E-016 | params min > max | 合法 param spec |
| E-017 | name 为空 | 显示名（WS/UI）必填 |

**Pass 1 占位符语义 E-1xx**

| 码 | 触发 | 修正 |
|---|---|---|
| E-101 | 静态节点含 `{{...}}` | 改引用 `$<node>.slots.<field>` / `.all.slots.<field>` |
| E-102 | template 变量 ∉ {item,index,len} | 只用三个受支持变量 |
| E-103 | 畸形占位符（未闭合/空/坏字符） | 闭合或删掉 |

**Pass 2 引用与结构语义 E-2xx**

| 码 | 触发 | 修正 |
|---|---|---|
| E-201 | slot 引用：节点不存在 / 自引用 / 未声明 outputs.field | 引用声明了该字段的上游节点 |
| E-202 | 路由目标不存在（Goto/cases/default/fan/template） | 指向已声明节点 |
| E-203.1 | entry 不在 nodes | 修正 entry |
| E-203.2 | 孤儿节点（WARN 不阻塞） | 检查路由链或删节点 |
| E-203.3 | 无条件环（无 switch 条件可退出） | 加 switch 或 $return 断环 |
| E-204 | `$params.<name>` 未声明 | 加进 params 或改引用 |
| E-205 | `$<node>.output` 节点不存在 | 引用已声明上游 |
| E-206 | `.all` 聚合引用非 template 节点 | 只有动态扇出实例能 .all 聚合 |
| E-207 | switch 表达式非 `$<node>.<field>` 或节点不存在 | 指向已声明节点的 verdict |
| E-208 | 静态 fan 分支 > maxFanout | 提高 maxFanout 或裁剪 fan |
| E-209 | 并行分支在汇聚前 `$return` | 分支先汇聚再终止 |
| E-210 | join 混入并行外的串行到达 | 串行入边改走 fan 下游 |
| E-211 | 无终止路径（无 $return） | 纯环只能以 maxLoop 超限告终 |
| E-212 | 节点数 <2 | 单 agent = agent + skill，不是 flow |
| E-299 | 未知结构错误兜底（不应出现） | 见结构校验消息 |

**类型层 E-3xx**

| 码 | 触发 | 修正 |
|---|---|---|
| E-301 | 槽声明 string 但 dynamic fanout 需 array | 改 `"array"` |
| E-303 | `.all.slots` 引用的 template 未声明 outputs.field | 给 template 加声明 |
| E-304 | 同一 template 被 >1 个 fan 引用（实例 id 冲突） | 各 fan 用独立 template |

**Pass 4 agent 存在性**

| 码 | 触发 | 修正 |
|---|---|---|
| E-401 | agent 不在全局库 | 注册 agent 或改引用 |

---

## 2. 预定义 flow（flows/<name>/）

### 2.1 结构

```
~/.nebflow/flows/<name>/
├── flow.json          # DAG 定义（与 FlowExecute 内联 DAG 同 schema，同一编译器校验）
└── agents/
    └── <agent>/       # flow-local agent（可选；缺省 → 全局 agent 同名 fallback）
        ├── agent.json # name（如 "research-researcher"）/tools/description
        └── system.md  # 角色 + 规则 + Output Contract（FlowReport 契约）
```

- **agent 解析优先级**：flow-local（`flows/<name>/agents/<agent>/`）→ 全局同名 fallback（`loadFlowAgent`，D3 规则）。flow-local agent 的 prompt 必须匹配节点实际工作（旧 research flow 曾整段照搬 codebase-exploration 模板给 web 调研用——静默质量损失，教训）。
- **Output Contract**：flow-local system.md 末尾「final step MUST call FlowReport exactly once, verdict ∈ {case keys}」——switch 节点契约是 flow 的单一权威。
- **flow.json 与动态 DAG 同 schema**（FlowDagDef：name/description/nodes/entry/maxLoop/strictVerdict/maxFanout/params），同一 `FlowDagCompiler.validate` 校验；**差异仅在**：agent 解析范围（预定义允许 flow-local）、监督默认值（预定义不注入 Restart 默认，保持 onError=None → Stop 兼容承诺）、触发路径（FlowTrigger 白名单）。

### 2.2 触发与参数

- **FlowTrigger(flow="<name>", prompt="...", params={...})**：白名单驱动——agent.json `flows` 数组声明才注入工具（`"*"` 通配符同 skills 语义）；调用时再按同一白名单门禁。返回后 flow 后台运行，完成结果 `ImmediateInput` 送达。
- **params 校验**（`validateFlowParams`）：未知键拒绝（防拼写错）；类型（int/string/bool）与 int 范围强制；声明 default 填充缺失键。节点输入 `$params.<name>` 引用。
- **触发时重编译**（#424）：flow.json 可能被外部编辑过——FlowTrigger 触发时走同一编译器前端，坏 DAG 在任何节点 spawn 前拒绝。

### 2.3 与 FlowExecute 的关系（互补，非取代）

| 维度 | FlowTrigger | FlowExecute |
|---|---|---|
| 定义 | `flows/<name>/flow.json` 落盘 | 调用参数内联 |
| 形状 | 固定（重复同形状调用） | 随任务现写 |
| 触发 | 白名单纪律闸门 | 机制层注入即用 |
| agent | flow-local 优先 + 全局 fallback | 仅全局库 |
| 监督默认 | 保持原样（Stop） | 注入 Restart+maxRetries=1 |
| 用例 | 反复同形状流水线（release/code-review） | 一次性编排、规模随任务（64 页 deck） |

**共享同一执行引擎**（FlowDagRunner → FlowDagExecutor），可混用；flow 节点内不可再触发 flow（leaf 规则）。

### 2.4 现存 9 个预定义 flow

| flow | 结构 | 要点 |
|---|---|---|
| release-stable | reviewer → coder → packager → reviewer（switch ready/pass/blocked，maxLoop=3） | Gate-then-execute；首轮占位符处理模式 |
| code-review | scanner → reviewer ⇄ fixer（switch pass/fix，maxLoop=5） | Review-fix loop；scanner outputs issues/diff_summary |
| git-merge | scanner → evaluator → merger 循环 | 质量检查 + 冲突处理 |
| nebflow-review-merge | 编译+测试+review+merge+worktree 清理（maxLoop=1） | Nebflow 项目专用 |
| research | planner → {r1,r2 并行} → verify（Barrier）→ writer | maxFanout=2；verify 模板用 `=== Track X ===` join |
| presentation-prep | planner → 恰 4 路并行 → content-planner → visual-designer → verifier | maxFanout=4，strictVerdict=true，onError=restart+maxRetries=2 |
| weekly-summary | summarizer → reporter | Schedule 定时触发依赖持久身份 |
| memory-consolidation | scanner → consolidator | 两步清理纪律 |
| entity-creator | ~~评审流水线~~ **已转 skill**（08-25 裁定维持） | 行为纪律非并行编排 |

---

## 3. 完整示例

### 3.1 单节点形态（争议区——见第二章）

**当前引擎拒绝单节点 flow**（E-212 / FlowStructure.validate「a single agent is an agent + skill, not a flow」）。「Delegate = 单节点 flow」的统一构想正撞在这条规则上——第二章详析。若未来合法化，形态将是：

```json
{ "name": "单节点", "nodes": { "n": { "agent": "Coder", "input": "$task", "onComplete": "$return" } }, "entry": "n" }
```

### 3.2 串行链（预定义 research 结构简化）

```json
{
  "name": "调研", "description": "两步串行",
  "entry": "researcher", "maxLoop": 1, "maxFanout": 2,
  "nodes": {
    "researcher": { "agent": "researcher", "input": "$task\n\n每条结论必须带来源链接。", "onComplete": "writer" },
    "writer": { "agent": "writer", "input": "综合调研产出成文：\n$researcher.output", "onComplete": "$return" }
  }
}
```

### 3.3 并行 fan + Barrier join（研究型）

```json
{
  "name": "并行调研", "description": "planner 拆题 → 2 路并行 → verify 汇聚",
  "entry": "planner", "maxLoop": 1, "maxFanout": 2,
  "nodes": {
    "planner": {
      "agent": "planner",
      "input": "$task\n\n拆成恰好 2 个正交子题，每个一行 scope。",
      "outputs": { "topics": "array" },
      "onComplete": { "parallel": ["r1", "r2"] }
    },
    "r1": { "agent": "researcher", "input": "研究子题 A：\n$planner.slots.topics", "onComplete": "verify" },
    "r2": { "agent": "researcher", "input": "研究子题 B：\n$planner.slots.topics", "onComplete": "verify" },
    "verify": {
      "agent": "verifier",
      "input": "核验两路：\n=== Track A ===\n$r1.output\n=== Track B ===\n$r2.output",
      "onComplete": "$return"
    }
  }
}
```

（verify 是 Barrier——2 路都到才激活一次。）

### 3.4 redo 循环标准结构（R3 冒烟 + deck v5 二轮实战验证）

```
planner（outputs.blocks 工单数组）
  → worker-fanout（静态壳，parallel slots=$planner.slots.blocks template=worker）
  → worker（template：{{item}} 逐块实现）
  → qa-fanout（静态壳，parallel slots=$planner.slots.blocks template=qa）
  → qa（template：{{item}} 逐块审查，PASS 或具体问题）
  → judge1（switch $judge1.verdict + outputs.failBlocks）
      pass → integrate ｜ fail → redo
  → redo（静态壳，parallel slots=$judge1.slots.failBlocks template=redoworker）
  → redoworker（template：{{item}} 重做 fail 块——元素含 qa 问题清单）
  → judge2（switch 复审；仍 fail 再进 redo 用新 failBlocks）
      pass → integrate ｜ fail → redo
  → integrate（聚合终稿，$return）
```

**语义要点**：只重做 fail 块（省 token、聚焦）；fail 块携带问题清单重做（不是盲目重跑）；maxLoop 保护（超限交付「N 块仍 fail」清单）；首轮全 pass 时 redo 零实例无空跑。

### 3.5 大规模并行范式（64 页 deck：16 worker + 16 qa 一次通过）

规模上去后真正的难点是**并行写冲突与上下文一致性**，七条纪律：
1. 契约文件 + CSS 由 planner 唯一写
2. 工单自包含（每元素自带完整上下文：素材路径/规格/约束）
3. worker 独占各自 part 文件，禁碰共享
4. git 仅 planner + integrate 执行（并行 worker 各自 git 会撞锁）
5. 全节点 per-node preset 按任务选（视觉类 worker/qa 用 Vision；机械用 LowCost）
6. 指挥节点极简（静态壳一句话；judge 用 FlowReport 一句话 pass/fail）
7. 失败幂等（worker 按覆盖重写自己 part 文件设计）

---

# 第二章 Delegate 融入 Flow 统一架构分析

## 4. 现状盘点——四个入口的执行模型对比

![执行模型现状](assets/flow-exec-model.svg)

> 图：四入口 → 共享 Agent 执行内核（AgentActor / AgentRegistry / subAgentTaskStore / sessionStore），差异在**编排层**（DAG 步进 vs 单发 spawn）与**结果形态**（ImmediateInput vs ExternalEvent）。

**现状事实（源码级核对）**：

1. **FlowExecute / FlowTrigger 已共用同一引擎**：两者都走 FlowDagRunner → FlowDagExecutor（差异仅 dynamic 标志、agent 解析范围、监督默认值）。FlowDagExecutor.executeAgent 内部实现了完整的「spawn AgentActor + bridge 事件桥 + AgentRegistry 注册 + dag 会话管理 + checkpoint 恢复」——**这套逻辑与 Delegate 的 spawn 逻辑高度同构**。
2. **Delegate 核心语义**：Nebula 专属；`agent` 必填（#28 禁 self-clone）；background（默认，立即返回，结果 ExternalEvent 送达）/ persistent（`lifecycle:"persistent"`，完成后保留会话 + Mail 地址回父 + `SessionStarted`）；`preset` 覆盖目标 agent 模型；`images`（≤5）附 prompt；深度 ≤5；BackoffSupervisor 崩溃自动重启；subAgentTaskStore 持久化任务元数据。
3. **SubTask 核心语义**：team 成员；self-clone（继承 caller 的 agent def）；仅 ephemeral；leaf 剥离（Mail/SubTask/Delegate/FlowTrigger/FlowExecute 全剥）；结果 ExternalEvent(source=subtask)。
4. **flow 节点**：按名解析全局/flow-local agent；深度=1；isFlowNode=leaf（剥 FlowExecute/FlowTrigger/SubTask/Delegate + TeamTaskTools + 展示类工具）；FlowReport 注入；dag- 会话（可 checkpoint 恢复）；结果经 FlowDagRunner 以 `ImmediateInput` 注入父上下文。

## 5. 概念映射——Delegate 每个能力 → Flow 表达

| Delegate 能力 | Flow/FlowExecute 对应表达 | 差距 |
|---|---|---|
| `agent`（目标 standalone） | `node.agent`（全局 agent 名） | ✅ 一致 |
| `prompt`（自包含任务） | 入口节点 `input: "$task"` | ✅ 一致 |
| `description`（UI 标签） | `flow.name` / `flow.description` | ✅ 一致 |
| background（立即返回 + 后台跑） | FlowExecute 调用本身也是异步返回（"Flow started"） | ✅ 时序一致；结果形态不同（见 §7） |
| `preset`（per-spawn 模型覆盖） | **`node.preset` 声称存在但引擎未实现**——FlowNode case class（EntityTypes.scala）无 preset 字段，节点 JSON 写 `"preset"` 被 circe 静默丢弃；skill §5 文档承诺领先于实现 | ⚠️ 需补实现（设计已裁定：per-node preset 节点配置模型保留） |
| `images`（≤5 附 prompt） | **flow 节点 input 是纯文本模板，无 images 通道** | ❌ 缺口——需要节点级 image attachment 或 prompt 内路径引用替代 |
| 结果注入父会话 | `ImmediateInput`（"[Flow 'name' completed]"） | ⚠️ 形态不同：ExternalEvent vs ImmediateInput（§7） |
| 重启预算 | onError=Restart + maxRetries + **checkpoint 恢复** | ✅ flow 更优（Delegate 的 BackoffSupervisor 是从头重跑；flow 恢复持久化消息） |
| 崩溃恢复 | subAgentTaskStore（Delegate）；flow 节点靠 dag 会话文件 + checkpoint | ⚠️ 机制不同（flow 的会话保留是审计 + 恢复双用） |
| `lifecycle: ephemeral` | flow 节点天然 ephemeral（dag 会话终态清理） | ✅ |
| `lifecycle: persistent`（保活 + Mail 地址） | **flow 无此概念**——flow 节点完成即终结，无 Mail 身份；persistent 依赖「agent 活着 + registry 条目 + SessionStarted」 | ❌ 本质缺口（persistent 是身份/通信语义，非执行语义） |
| 深度上限 5 | MaxDepth=5 同值 | ✅ |
| leaf 工具剥离 | isFlowNode 剥离 = delegate 目标 standalone 默认工具集近似 | ⚠️ 行为差异：flow 节点额外剥 TeamTaskTools + 展示类工具（userFacing 豁免）；delegate 不剥 |
| 权限继承 | rootSessionId 继承（flow 与 delegate 同款） | ✅ 一致 |
| 并发多开 | Delegate 同响应多调用并行；flow 用 parallel fan | ✅ 表达不同、能力等价 |

**结论（概念层）**：「Delegate = 单节点 flow」在**执行语义**上成立——一个自主 worker 做一件事、做完回传，正是单节点 DAG 的语义。但 Delegate 有**三个超出纯执行语义**的能力：persistent 生命周期（Mail 身份）、images 附件、ExternalEvent 结果形态。统一必须覆盖这三者，否则 Delegate 能力降级。

## 6. 统一执行模型——三种方案

### 方案 A：NodeRunner 通用化（共享内核，双入口保留）

把 Delegate/SubTask/flow 节点三处重复的「AgentActor spawn + registry 注册 + 安全模式/根 session 继承 + 任务元数据持久化」抽成共享的 NodeRunner 基础设施；工具层（Delegate/SubTask/FlowExecute/FlowTrigger）与结果形态（ExternalEvent/ImmediateInput）保留差异。

- 优点：改动面可控；核心路径（Delegate）行为零变化；消除三份近似代码（DelegateTool.spawnBackground / spawnPersistent、SubTaskTool.spawnWorker、FlowDagExecutor.executeAgent 后半段）；后续统一自然演进。
- 缺点：不兑现「一个执行模型」的理想；flow 的 checkpoint 恢复与 delegate 的 BackoffSupervisor 仍需两套监督逻辑。
- 改动量：中（重构 + 回归测试）。

### 方案 B：Delegate 变薄——内部构造单节点 DAG 走 FlowDagRunner

Delegate 工具保留外部契约（参数/结果/生命周期），内部把调用翻译成单节点 flow 交给引擎。

- 优点：彻底统一执行路径；Delegate 成为「单节点 flow 的便捷入口」；FlowExecute 与 Delegate 语义趋同。
- **关键障碍**：
  1. **单节点 flow 被结构校验拒绝**（E-212 / FlowStructure「at least 2 nodes」）——必须加「单节点豁免」（仅内联路径）或包装哑节点（丑、破坏 DAG 语义）。
  2. **leaf 剥离差异**：flow 节点剥 TeamTaskTools + 展示类工具；delegate 目标 standalone 不剥——统一后 Delegate 派出的 worker 行为变化（副作用）。
  3. **persistent 无法表达**：flow 无 Mail 身份/保活。
  4. **images 无通道**。
  5. **结果形态**：flow 完成走 ImmediateInput；delegate 走 ExternalEvent + SessionUpdate——前端气泡/Sub-Agents 面板依赖 eventType/source 语义。
- 改动量：大（引擎豁免 + 工具重写 + 前端语义 + 全量回归），且需要为「不统一的差异」再开三个兼容后门——收益被抵消。

### 方案 C：FlowExecute 新增「单节点快捷形态」

FlowExecute 接受 `node` 参数（省略 nodes/entry 的糖），引擎自动包成单节点 flow 并豁免校验。

- 优点：DSL 层显式承认单节点形态；调用面最简。
- 缺点：与方案 B 相同的引擎豁免 + 结果形态问题；且**新增 API 面**而非收敛——与「Delegate 变薄」目标相反（多了一个入口）。
- 改动量：中-大。

### 对比小结

| 维度 | A 共享内核 | B Delegate 走 flow | C 单节点快捷形态 |
|---|---|---|---|
| 兑现统一理想 | 部分 | 彻底 | 部分 |
| 核心路径风险 | 低 | **高**（Delegate 是 Nebula 日常派发） | 中 |
| 需兼容后门 | 0 | 4（单节点豁免/leaf 差异/persistent/images） | 3 |
| 改动量 | 中 | 大 | 中-大 |
| 演进方向 | 自然 | 一步到位但回退难 | 中间态 |

## 7. 交互契约保留——结果注入父会话

Delegate 的核心语义 = **「父会话等待 + 结果注入父上下文」**。统一方案必须回答：单节点 flow 完成时如何兼容？

**现状两条通道**：

| 通道 | 形态 | 语义 | 前端呈现 |
|---|---|---|---|
| `ImmediateInput` | `[Flow 'x' completed]\n<output>`，source="flow"，eventType=completed | 同步注入父上下文（父在下一轮看到结果作为系统消息） | 注入气泡「Flow · x · Completed」 |
| `ExternalEvent` | source=delegate-地址 / subtask，eventType=completed | 事件送达父（父按事件类型处理） | Sub-Agents 面板状态 + 事件气泡 |

**兼容策略（若走 B/C）**：
- **FlowReport 注入 vs ImmediateInput 回传**：flow 完成结果天然是 `ImmediateInput`——已满足「结果注入父上下文」。Delegate 的 ExternalEvent 客户端（前端气泡、AgentControl 面板）需要适配 flow 通道，或 FlowDagRunner 增加「结果以 ExternalEvent 形态投递」选项（按触发工具区分投递形态）。
- **persistent 语义**：flow 引擎需要「节点完成后保留会话 + 注册 Mail 可达身份」——这是 FlowDagExecutor 之外的新能力（persistentAdapter 职责），等同给 flow 加「持久节点」概念。
- **SubTask 同类纳入**（阶段 3）：SubTask 是 self-clone——flow 节点需支持「继承 caller 的 agent def」而非按名解析，与 flow 现有模型（按名解析全局 agent）冲突，需 per-node 覆盖机制。

## 8. 渐进迁移路径（推荐）

### 阶段 1：共享内核抽取（NodeRunner 通用化）+ 补齐 flow 单节点能力

**内容**：
- 抽取共享 NodeRunner：AgentActor spawn / AgentRegistry 注册 / 根 session 与安全模式继承 / 任务元数据持久化，三处调用方收敛。
- 引擎层：FlowExecute 支持单节点 DAG（E-212 豁免改为「仅内联 FlowExecute 路径允许 1 节点」；预定义 flow.json 仍拒绝——GUIDE 决策树不变）；节点级 `images` 通道；**实现 per-node `preset`**（FlowNode + 解码器 + 执行器应用——skill §5 已承诺，补齐文档与实现落差）。
- 结果形态：FlowDagRunner 支持以 ExternalEvent 投递结果（供 Delegate 变薄时保契约）。

**验收点**：
- [ ] 单节点 FlowExecute DAG 编译通过并执行（0 个错误码回归；E-212 仅作用于预定义路径）
- [ ] per-node preset：节点 JSON `"preset": "LowCost"` 生效（模型链切换可观测），未声明回落 agent 自身 preset
- [ ] 节点 images：≤5 张路径附加生效（复用 ImageInject 解析/校验）
- [ ] 三处 spawn 逻辑收敛为共享 NodeRunner，行为等价（Delegate/SubTask/flow 节点全量现有 spec 通过）
- [ ] 冒烟：FlowExecute 单节点 + 预定义 flow + Delegate 各跑一遍，结果注入正常

### 阶段 2：Delegate 变薄（内部走 flow 引擎）

**内容**：Delegate 工具内部构造单节点 DAG → FlowDagRunner；外部契约全保留——`ExternalEvent` 结果形态（阶段 1 投递选项）、persistent 生命周期（引擎「持久节点」能力或 delegate 专用 wrapper 保留 persistentAdapter 分支）、images/preset 参数透传、self-clone 禁令、深度 5。

**验收点**：
- [ ] Delegate 全部现有参数语义不变（DelegateToolSpec 全绿）
- [ ] Sub-Agents 面板对 delegate 结果呈现不变（eventType/source 兼容）
- [ ] persistent 生命周期回归：SessionStarted / Mail 后续可达 / 崩溃通知 / cancel
- [ ] 崩溃恢复回归：BackoffSupervisor 语义（或等价 checkpoint）保障
- [ ] 双跑对照：同一任务 Delegate 与 FlowExecute 单节点产出一致

### 阶段 3（可选）：SubTask 统一

**内容**：SubTask 走同一引擎；需要「节点继承 caller agent def」覆盖机制（self-clone 语义）。评估是否值得——SubTask 已是纯 ephemeral self-clone，与 flow 按名解析差异最大，收益最小。

**验收点**：
- [ ] SubTask 全部现有 spec 通过（self-clone 继承、leaf 剥离、ExternalEvent）
- [ ] team worker 身份块/无 team 声明不变

## 9. 风险与回归

| 风险 | 等级 | 缓解 |
|---|---|---|
| **Delegate 是核心路径**（Nebula 日常派发）——任何行为漂移直接打击主工作流 | 高 | 阶段 1 先抽内核零行为变化；阶段 2 双跑对照 + 全 spec 回归后再切 |
| leaf 工具集差异：flow 节点剥 TeamTaskTools/展示类工具，delegate 不剥——统一后 worker 能力变化 | 高 | 阶段 1 明确「单节点 flow 的 leaf 剥离与 delegate 对齐」还是「delegate 接受剥离」，二选一并写进验收 |
| persistent 生命周期无法用纯 flow 表达 | 高 | 阶段 1 就定「持久节点」能力归属；否则阶段 2 保留 persistentAdapter 专用分支 |
| 前端语义：ImmediateInput vs ExternalEvent 气泡/面板路由 | 中 | 阶段 1 投递形态选项 + 前端回归 |
| FlowReport 强制注入：delegate 目标 standalone agent 可能无 Output Contract——统一后是否强制报 verdict | 中 | 单节点无 switch 无 outputs 时契约为空，FlowReport 退化为可选（现状已有 None 分支） |
| 单节点豁免被滥用：1 节点 flow 绕过「单 agent = agent + skill」纪律 | 中 | 豁免仅限 FlowExecute 内联路径；预定义 flow.json 仍拒绝；GUIDE 决策树不改 |
| 测试面 | — | 现有 DelegateToolSpec / SubTaskToolSpec / FlowExecuteToolSpec / FlowDagCompilerSpec / FlowSemanticsSpec 全量回归 + 每阶段冒烟 + 双跑对照 |

## 10. 结论建议

**概念上成立，工程上分阶段**：

1. **「Delegate = 单节点 flow」是准确的概念抽象**——执行语义上二者等价（一个自主 worker、干净上下文、结果回传、深度 5、权限继承同构），且 FlowExecute/FlowTrigger 已共用引擎，统一有清晰落点。
2. **不建议一步到位走方案 B/C**：Delegate 的三个超执行语义能力（persistent 生命周期、images、ExternalEvent 结果形态）+ leaf 工具集差异，迫使统一方案开 3-4 个兼容后门，核心路径风险高、收益被抵消。
3. **推荐路径 = 方案 A（共享内核）→ 阶段化收敛**：阶段 1 抽 NodeRunner + 补齐 flow 单节点/preset/images/结果形态选项（引擎能力完备）；阶段 2 Delegate 变薄（可选，视阶段 1 落地质量）；阶段 3 SubTask 统一（可选，收益最小）。每阶段验收点见 §8。
4. **立即值得做的一件事（与统一无关的欠账）**：per-node `preset` 是用户已裁定、skill 已承诺、引擎未实现的落差（节点 JSON 写 `"preset"` 被静默丢弃）——无论是否统一都该补。

> 一句话：**Delegate 是单节点 flow 的「便捷入口」这一心智模型可以直接采用**（文档/路由决策层），但代码统一应以「共享内核 + 保留入口差异」起步，待单节点 flow 能力完备后再考虑 Delegate 变薄。
