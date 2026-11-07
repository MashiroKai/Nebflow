# pilogic

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

循环内核与位点/hook 机制（pilogic 接线批）。

**入口建议**：先读 [`LoopRuntime.scala`](LoopRuntime.scala)（本模块最大文件）。

## 文件（13）

| 文件 | 说明 |
|---|---|
| [`HookDispatch.scala`](HookDispatch.scala) | 执行面（设计 §Ⅲ.3/§Ⅲ.4/§Ⅲ.5）：Interceptor/Observer 单点分派 + 执行序/短路/否决传播 + 隔离包装。 隔离性做进 dispatch 单点（不是靠各调用点自觉）：任何 hook 异常 |
| [`HookPoints.scala`](HookPoints.scala) | hook 身份：注册表分配，`without` 按它注销。 |
| [`HookRegistry.scala`](HookRegistry.scala) | 注册面（设计 §Ⅲ.2）：不可变注册表 + COW 快照。 编译期接线（构造期注入 RunSeed.hooks）；运行期增删 = 拿到新 registry 后下一轮生效 |
| [`KernelEvent.scala`](KernelEvent.scala) | 内核事件 envelope——全项目事件的最小统一形状（全项目等它对拍）。 对拍锚：db 线 D-12（`docs/20261104_135608_scala-db-module-impl-v1__delegate-ne |
| [`LoopCore.scala`](LoopCore.scala) | 循环核心（设计 §Ⅱ.1/§Ⅱ.4；任务书语「LoopEngine」即本 trait——命名从设计 §Ⅱ.1 AgentLoop）。 == 分支语义（本轮以契约钉死；双层循环体实现属下一增量轮） == |
| [`LoopEvents.scala`](LoopEvents.scala) | 事件与出口（设计 §Ⅱ.3/§Ⅱ.4）：LoopEvent 密封层级 + EventSink[F] 推送面 + 崩溃合成助手。 L-2 采纳推荐档 (c)：自建 EventSink 接口——push 对位 scala 现 |
| [`LoopRuntime.scala`](LoopRuntime.scala) | 位点 payload 编解码单源（增量轮：Json 载体形状的位点专属 Event/Value 面——每位点 输入 schema 与效果契约在此唯一声明，循环与测试共用；P#Event 路径依赖类型化留接线期， |
| [`Policy.scala`](Policy.scala) | 策略位（设计 §Ⅱ.2，L-10 三元全 Option）：上限的唯一来源是构造参数，缺省无帽； 引擎触及上限的唯一方式 = 每轮循环头对本对象做谓词查询。 |
| [`Provider.scala`](Provider.scala) | 模型边界（设计 §Ⅳ Provider.scala）：ProviderPort[F]、请求/响应/流块、AbortSignal[F]。 llm 适配经接线层实现进来——pilogic 禁反向依赖 `nebflow.llm |
| [`RunLedger.scala`](RunLedger.scala) | L-8 消费侧 run 账本（接线期四件 ③）：run 身份 + 会话域 KernelEvent 序的**不可变值**。 纪律（病灶 #1 结构性免疫在接线层的延续）：跨 run 记忆以值形态显式进宿主状态 |
| [`Sequence.scala`](Sequence.scala) | 序域（设计 §Ⅱ.1）：TurnNumber / MessageSeq 单调值对象，从 1 起。 分配器由循环本体（单写者）持有——不暴露自增 API 给调用方，调用方只读； |
| [`SiteEvents.scala`](SiteEvents.scala) | 接线期四件 ①：29 采纳位点的**强类型输入面**（D-2 收敛）。 范围裁定（诚实收敛）：位点入参从「LoopRuntime 手拼 Json」升级为逐位点 case class + |
| [`Tools.scala`](Tools.scala) | 工具面（设计 §Ⅳ 数据谁持有：工具是数据 N3；执行器由接线层注入——pilogic 只管 批编排、L-5 重校验与 terminate 语义）。 |
