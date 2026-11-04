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
| `LoopEvents.scala` | `LoopEvent` 密封层级、`EventSink[F]`、`synthesizeTruncatedEvents` |
| `HookPoints.scala` | `HookPoint` 分类学（采纳族 + Reserved G-1…G-5）、`HookCapability` 四元组 |
| `HookRegistry.scala` | 不可变注册表、COW 快照、`HookOrder` |
| `HookDispatch.scala` | Interceptor/Observer 单点分派、`HookErrorPolicy`、隔离包装 |
| `Provider.scala` | `ProviderPort[F]`、请求/响应/流块、`AbortSignal[F]` |
| `Policy.scala` | `LoopPolicy` / `RetryPolicy` / `CostMeter` / `TokenBudget` |
| `Sequence.scala` | `TurnNumber` / `MessageSeq`、单写者分配 |
