# pilogic

**一句话目标**：pi 对位的「标准循环 + hook 接入点」叶子模块——双层循环骨架、密封
`LoopEvent` 事件 + `EventSink[F]` 推送、Interceptor/Observer 双族位点（类型级区分）与
G-1…G-5 显式预留壳；一切轮数/成本/墙钟上限皆为构造参数（`LoopPolicy` 三元全
`Option`，缺省无帽），模块内零硬编码上限、零进程全局态、零 nebflow 内部依赖
（禁 import `nebflow.agent.*` / `nebflow.core.*` / `nebflow.gateway.*` /
`nebflow.llm.*`）；本轮为未接线骨架（设计方案 v1 §Ⅱ/§Ⅲ/§Ⅳ，
`docs/20261104_121214_scala-pi-logic-design-v1__delegate-nebflow-6513b1ad.md`）。

| 文件 | 职责（§Ⅳ） |
|---|---|
| `KernelEvent.scala` | 内核事件 envelope（对拍 db D-12；本模块新增于 §Ⅳ 布局） |
| `LoopCore.scala` | `AgentLoop.run/continueFrom`、`LoopState`（run-local 值传递）、分支语义 |
| `LoopRuntime.scala` | 双层 while 循环体实现（增量轮）+ 采纳 29 位点挂接 + `SitePayloads` + `SessionStages` |
| `LoopEvents.scala` | `LoopEvent` 密封层级、`EventSink[F]`、`synthesizeTruncatedEvents` |
| `HookPoints.scala` | `HookPoint` 分类学（采纳族 + Reserved G-1…G-5）、`HookCapability` 四元组、`Backpressure`（L-11） |
| `HookRegistry.scala` | 不可变注册表、COW 快照、`HookOrder` |
| `HookDispatch.scala` | Interceptor/Observer 单点分派、`HookErrorPolicy`、隔离包装、`DropGates`（L-11 丢弃档） |
| `Provider.scala` | `ProviderPort[F]`、请求/响应/流块、`AbortSignal[F]` |
| `Tools.scala` | `ToolCall`/`ToolExecutionResult`/`ToolSpec`（L-5 校验件）/`ToolExecutor[F]`（增量轮） |
| `Policy.scala` | `LoopPolicy` / `RetryPolicy` / `CostMeter` / `TokenBudget` |
| `Sequence.scala` | `TurnNumber` / `MessageSeq`、单写者分配 |

**增量轮（round 2）范围**：§Ⅱ 16 接口签名全量落地、双层 while 循环体可运行实现
（trait 契约 → 实现）、采纳 29 位点逐一挂进 dispatch/循环阶段、L-5（Tool.Pre 改参后
重校验）与 L-11（观察位背压=位点声明，不照抄 pi 顺序档）按方案推荐拍板、§Ⅵ 可自动化
判据全覆盖。envelope（`KernelEvent`）冻结不改。
