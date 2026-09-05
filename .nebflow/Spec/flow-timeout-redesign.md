> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Flow 超时机制重设计方案（v2.1 — 移除编排层超时 + 结构性禁 Mail）

> 状态：待确认 | 分支：archive/scala | 纯分析产出，不改代码
> 演进：v1（idle watchdog）否决 → v2（移除超时 + ask 转后台）修正 → v2.1：Mail 对 flow agent **结构性禁用**（系统设计本意，补上被 `"*"` 穿透的防御洞），Mail ask 超时问题随之根除，转后台补丁不再需要。

---

## 1. 立场修正与验证

用户判断：**Flow 只是 Agent 编排的一种方式；Agent 系统已有完善的异常自恢复设计（actor 管理思想）；编排层加超时是多余且有害的。**

代码验证结论：**这个判断基本成立**。Agent 系统自愈分层全景：

| 层 | 已有的超时/自愈机制 | 代码位置 |
|----|-------------------|---------|
| LLM 层 | first-token 90s / 流不活跃 60s 判挂；失败自动 fallback 到备用模型（每次重试发 `agentRetryStatus` 事件） | `Defaults.scala:29/38`、`AgentCore.scala:263-271` |
| 工具层 | Bash 前台 timeout 参数（max 60min）；Bash 后台 idle 5min 自动杀；WebFetch 120s；Curl 120s | `Defaults.scala:47/75`、`shell.scala startJobHealthCheck` |
| Actor 层 | behavior `onError` 自恢复（异常 → 记日志 → 回 idle，`AgentActor.scala:1658-1663`）；turn fiber 可取消（`cancelCurrentTurn`）；`restartAgent` WS 命令人工重启（`WebSocketRoutes.scala:842`）；actor stop 时自动取消全部 turn fiber（`ActorSystem.scala:196-200`） | |
| 编排层（DAG） | `maxLoop=10` 防路由死循环；节点间检查用户取消；onError resume/restart/stop 策略 | `FlowDagExecutor.scala:180/234/147` |

**08-14 加的三处 wall-clock 超时（commit `09f4be58`）在这个体系里是异类**：它们是编排层对 agent 的"处决权"，与"异常自恢复"哲学相反——不区分死活直接杀，误杀 sbt 编译 8min 的 scanner、深审查 15min 的 reviewer，工作全部丢失。

但诚实起见，移除超时后有两个**真实存在的结构性洞**，需要非超时式的补丁（见 §3）。同时澄清 08-14 P0 的根因：commit message 自己写明——*"Code-review agents had Mail in their tool list, and a flow agent calling Mail(ask=true) hit path A deterministically. **Config fixes (Mail -> FlowReport, scanner switch default) are handled separately.**"* 即冻结的确定性根因是**配置错误**（flow agent 不该有 Mail 工具），超时只是外层防御。配置已单独修复，防御层的存在价值需要重新评估。

## 2. 移除内容（回滚 `09f4be58` 的三处 race）

| # | 移除项 | 位置 | 移除后行为 |
|---|--------|------|-----------|
| 1 | `NodeTimeout = 5.minutes` + `IO.race(resultDeferred.get, IO.sleep(NodeTimeout))` | `FlowDagExecutor.scala:34, 473-479` | 回到 `resultDeferred.get` 纯事件等待。节点结束只由 `AgentEvent.Completed/Failed` 驱动——`finishTurn` 全路径都保证 `replyTo.traverse_(_ ! Completed)`（`AgentActor.scala:1997/2064/2102/2171`），异常路径由 actor `onError` 兜底 |
| 2 | `GlobalFlowTimeout = 30.minutes` + 全局 race | `FlowDagExecutor.scala:37, 312-322` | flow 结束 = 路由到 `$return` / onError Stop / 用户取消。路由循环已由 `maxLoop` 防护 |
| 3 | `FlowAskTimeout = 5.minutes` + flow-caller 硬超时分支 | `MailTool.scala:38, 369-381` | 见 §3 补丁 1——不是简单删除，是统一为转后台语义 |

残留风险声明（接受项）：**agent turn fiber 挂死且不抛异常**（例如某个未来新增的无超时工具卡在外部调用上）将无自动恢复，flow 冻结。缓解：① 工具层准入——新工具必须自带超时（现有工具已盘点齐全）；② 人工恢复通道已存在（`restartAgent` WS 命令 + cancelFlow 穿透，见补丁 2）。这是 actor 体系的标准取舍：宁可信内部监督，不要编排层滥杀。

## 3. 两个非超时补丁（编排层本职：路由与用户控制权）

![修订方案架构](assets/flow-timeout-v2.svg)

### 补丁 1：Mail 对 flow agent 结构性禁用（系统设计本意，补防御洞）

**设计本意已存在但被穿透**：`AgentCore.fixedToolsFor`（`:1198-1202`）明确 flow agent = BaseTools + FlowReport（no Mail）。但 `buildAllowedToolSet`（`:881-884`）中 `tools=["*"]` 展开为 `ToolRegistry.ALL_TOOLS`（含 Mail），显式列 Mail 同样放行——只有 SubTask worker 有剔除防御（`:914` `-- Set("Mail", "SubTask", "Delegate")`）。08-14 P0 的确定性根因正是 flow agent 的 agent.json 持有 Mail（commit message: *"Code-review agents had Mail in their tool list"*）。

改动一（`AgentCore.scala buildAllowedToolSet:911-915`，与 SubTask 防御同构的一行）：

```scala
// SubTask workers are leaf agents: no Mail / no further delegation. ...
if isSubTaskWorker then mcpFiltered -- Set("Mail", "SubTask", "Delegate")
// Flow agents have no Mail — flow nodes report via FlowReport, not Mail.
// Defends against a flow agent whose agent.json explicitly lists Mail or uses "*".
else if agentDef.category == "flow" then mcpFiltered - "Mail"
else mcpFiltered
```

效果：Mail 从 flow agent 的工具集与 LLM 可见工具列表中彻底消失 → 不可能发起 ask → P0 path A（flow agent Mail ask 永久阻塞）**结构性不可能**。

改动二（`MailTool.scala`）随之删减死代码：
- 删 `FlowAskTimeout`（`:38`）与 `waitForForkAnswer` 的 `isFlowCaller` 分支（`:369-381`，含 `ctx.agentDef.exists(_.category == "flow")` 判定）
- 工具 description 中 "Flow callers get a hard 5-minute timeout" 字样移除（`:29`）
- 非 flow 的转后台语义（60s）原样保留，不受影响

**v2 的"ask 统一转后台"补丁不再需要**——flow agent 无 Mail 工具，不存在 flow-caller ask 场景。

### 补丁 2：cancelFlow 穿透到正在执行的节点（用户控制权）

现状缺陷：`RunningFlowRegistry.cancel` 只设 flag，`runNode` 只在**节点开始前**检查（`FlowDagExecutor.scala:234`）。节点执行中用户点取消 → flag 挂着，当前节点跑完（或挂死）前 flow 不会停。移除超时后，若节点真挂死，用户连取消都无效——必须有穿透。

改动（`FlowDagExecutor.scala` executeAgent + `RunningFlowRegistry.scala`）：

```scala
// RunningFlowRegistry 增加每实例的取消信号（flag 保留给节点间检查）：
private val cancelSignals: Ref[IO, Map[String, Deferred[IO, Unit]]] = ...
def cancel(instanceId: String): IO[Unit] =
  ...原 flag 逻辑... *> cancelSignals.get.flatMap(_.get(instanceId).traverse_(_.complete(()).void))
def cancelSignal(instanceId: String): IO[Deferred[IO, Unit]] = ...  // register 时创建

// executeAgent 等待处（替代被删除的超时 race）：
cancelSig <- nebflow.core.flow.RunningFlowRegistry.cancelSignal(instanceId)  // 需把 instanceId 传入 executeAgent
raceResult <- IO.race(resultDeferred.get, cancelSig.get)
// raceResult = Right(_) → 节点以 "Flow cancelled by user" 收场：
//   先 ref ! AgentCommand.Stop（cancelCurrentTurn 中断挂起的 turn fiber）
//   再 actorSystem.stop(ref)（其 guarantee 会再取消残留 fiber）→ 现有清理路径 :480-486
```

这不是超时：**只在用户显式取消时触发**，慢工作不受任何影响。同时它修复了一个独立 bug——现在取消一个正常运行的多节点 flow 也要等当前节点自然结束。

## 4. 涉及文件汇总

| 文件 | 改动 |
|------|------|
| `core/entity/FlowDagExecutor.scala` | 删 `NodeTimeout`/`GlobalFlowTimeout` 常量及两处 race；`executeAgent` 改 race cancel signal；`executeAgent` 签名加 `instanceId` 参数（executeNode 透传） |
| `agent/AgentCore.scala` | `buildAllowedToolSet` 加 flow agent 剔除 Mail 的一行防御（`:911-915`） |
| `core/tools/MailTool.scala` | 删 `FlowAskTimeout`、`isFlowCaller` 分支及 description 中的相关描述 |
| `core/flow/RunningFlowRegistry.scala` | cancel 时 complete 实例 cancel signal；register/cleanup 配套 |
| `core/flow/FlowDagRunner.scala` | 无实质改动（透传链路确认） |

不新增任何常量、配置项、schema 字段——**方案做减法：删三处超时 + 删一个死分支，加的只有一行 Mail 禁御和取消信号一条控制线**。

## 5. 行为变更说明（通俗版）

- **现在**：flow 节点满 5 分钟必死（在不在干活都杀），Mail 求答满 5 分钟报错，整个 flow 满 30 分钟必死；而用户点"取消 flow"却只能等当前节点自己跑完；flow agent 还能通过 agent.json 的 `"*"` 配置意外拿到 Mail 工具（08-14 P0 根因）。
- **改后**：编排层彻底不杀 agent。节点跑多久由工作本身决定（sbt 编译 40 分钟也安全）；agent 内部已有的超时（LLM 断流 60s、后台命令 5min 无输出自杀等）继续各自兜底；用户点"取消"立即生效——正在跑的节点被通知停止，flow 干净收场；Mail 工具对 flow agent 结构性不可见（无论 agent.json 怎么写），flow 节点只能用 FlowReport 汇报——P0 冻结路径从根上堵死。

## 6. 验收条件

### 6.1 冒烟测试（硬性，第一项）

```bash
sbt compile && sbt test
sbt "runMain nebflow.gateway.GatewayMain" --port 8082 &
sleep 8 && curl -sf http://localhost:8082/api/health        # 200
# Mail 触发真实 flow（如 code-review），WS 断言收到 flowStarted → flowProgress* → flowCompleted
```

### 6.2 单元测试

| 用例 | 类型 | 断言 |
|------|------|------|
| UT-1 慢前台工具存活 | 正向（=08-14 事故最小复现） | 节点 agent 跑 8min（测试中用短 wall-clock 观察窗口替代真实等待，断言"等待 8min 后仍未被杀"）后正常 Completed → 节点成功 |
| UT-2 持续活动长节点 | 正向 | agent 持续发事件 35min（超旧全局 30min）→ flow 正常完成，无全局超时 |
| UT-3 flow agent 无 Mail | 负向（结构性） | `buildAllowedToolSet(flowAgentDef with tools=["*"])` 返回集不含 "Mail"；agent.json 显式列 Mail 同样被剔除；team/standalone agent 的 Mail 不受影响 |
| UT-4 取消穿透-运行中 | 负向 | 节点执行中（agent 活着）cancelFlow → 节点收到 Stop、清理执行（agentRegistry 移除/session 删除）、节点状态 cancelled、flowCompleted(success=false) 发出 |
| UT-5 取消穿透-挂死中 | 负向 | 节点 agent turn fiber 挂在无超时 Deferred.get 上 → cancelFlow → cancelSignal race 触发 → stop(ref) 强制收场（验证 guarantee 取消 fiber） |
| UT-6 取消幂等 | 边界 | 重复 cancel / 完成后 cancel → Deferred.complete 二次调用无害（tryComplete），无异常 |
| UT-7 旧 flow.json 兼容 | 兼容 | 无新 schema 字段，存量 flow.json 原样解析（回归现有 Decoder 测试即可） |

### 6.3 端到端

- E2E-1：真实 flow 中 scanner 执行 `sleep 480; echo done`（Bash timeout=500000）→ 全程无超时、节点输出正确——**08-14 事故的直接复现用例，改后必须存活**。
- E2E-2：flow 运行中（长节点）前端点取消 → 数秒内 flow 状态变 cancelled，无残留 agent（AgentRegistry 中无该 session 记录）。
- E2E-3：向 flow 节点 agent 的 LLM 请求注入"给 teammate 发 Mail"指令 → 模型工具列表中无 Mail 可选，只能用 FlowReport 收尾（P0 根因复现，结构性防线验证）。

### 6.4 收敛自查

- [x] 冒烟：真实启动 + 健康检查 + WS 事件链
- [x] 每条可自动化（sbt test / curl / WS 断言 / AgentRegistry 查询）
- [x] 二值判断（存活/收场、事件到达/未到达）
- [x] 正负成对：慢工作不杀（UT-1/2）↔ 用户取消必达（UT-4/5）↔ Mail 结构性禁用（UT-3/E2E-3）
- [x] 兼容：零 schema 变更，存量 flow.json 不动

## 7. 风险与边界

| 风险 | 评估 |
|------|------|
| turn fiber 挂死无自动恢复（残留） | 概率极低：需所有工具层超时同时失效。人工通道已有（restartAgent 命令 + cancelFlow 穿透）。若未来真的高频出现，再按 v1 的 idle watchdog 方案定向加回（本文档保留为附录参考） |
| 移除全局 30min 后 flow 永跑不收敛 | maxLoop 防路由循环；节点不死则必发出 Completed/Failed（finishTurn 全路径保证）；活锁场景用户可取消（穿透生效） |
| cancelSignal 泄漏 | register/清理配套（flow 终态时从 cancelSignals 移除），cleanupStale 已有 5min 周期清理先例 |
| 剔除 Mail 影响 flow agent 现有合法用法 | 核对 flow 定义（~/.nebflow/flows/*/agents）：flow agent 间通信走 FlowReport（executor 收集），无节点间 Mail 依赖；08-14 后的配置修复已把 code-review 的 Mail 换成 FlowReport，无存量依赖 |
| 09f4be58 回滚后 P0 复发 | P0 根因（flow agent 持有 Mail）被结构性防线根除（`buildAllowedToolSet` 剔除，agent.json 写什么都没用），非配置约定 |
