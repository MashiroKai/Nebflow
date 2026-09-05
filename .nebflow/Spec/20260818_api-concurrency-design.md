# API 并发管理设计（P0）

> 状态：draft（等 Manager 审）
> 版本：v0.1（2026-08-18）
> 关联：issue #19 同族（subagent 卡死无恢复）、12:39 现场事故、Nebula 用户报障

## 1. 背景与问题

### 1.1 用户报障（原话）

> "我们 nebflow 少了一个并发管理的功能。很多 API 都是有并发限制的，比如 RPM 和最大并发。这些可以对 agent 无感，但是我们 nebflow 要做并发管理、排队。像你现在触发的很多 subagent，现在其实已经失败了，就卡在后台。我们后台也没有一个识别和恢复机制。并且这个并发管理是分 api 的，每个 api 是不一样的。"

### 1.2 现场事故（12:39 实测）

- Nebula 11:08 一次性并发派 7 个带图 Delegate（Explorer），撞 API 并发限制。
- 10 个 running 任务中 5 个卡死（26-44 分钟零活动）：entity-creator architect/reviewer 2 + PDF 提取 Explorer 3。
- 因果链（探索员实证，见 §2）：parTraverse 工具批同时 spawn 7 个独立 AgentActor → 7 个并发 LLM 调用撞 API 限流 → 429 按 Transient 进入**各 agent 独立**的 retry（1000→2000ms）+ fallback 链 + provider probe/markDown 循环（probe 周期 120s）→ turn 长期不完成、零事件输出 → agent 既不完成也不崩溃 → BackoffSupervisor 无 Terminated 不介入 → 前端凭 registry 存在继续报 running → **无任何机制发现或恢复**。

### 1.3 派发可靠性背景（并入设计动因）

12:44 关键 INTERRUPT 派发 Mail 用了默认 `immediate` 投递，遇重启窗口丢件——**关键派发 Mail 一律 `delivery="queue"`**（持久化、重启不丢）。本设计不直接改 Mail，但"排队不失败"是同一哲学：系统各层的不可靠窗口都要有持久兜底。

## 2. 现状结论（探索证据，只读 @57044d12）

三份探索转录：`~/.nebflow/sessions/subtask-79594f85.json`（并发调度层）、`subtask-8b4c97c8.json`（配置与状态层）、`subtask-44699511.json`（LLM 请求层）。要点：

### 2.1 三层并发，全部无全局闸门

| 并发层 | 位置 | 说明 |
|---|---|---|
| 工具批并行 | `AgentCore.scala:600` `filteredCalls.parTraverse` | 一个 LLM 响应里多个 Delegate/SubTask 调用同时 spawn |
| Flow DAG 扇出 | `FlowDagExecutor.scala:541-554` `.start` per fan 目标 | 每节点独立 AgentActor |
| LLM 调用本身 | `interface.scala` `send/sendStream` | 每次 turn 直接 fire，无信号量包裹 |

- `askSemaphore = Semaphore[IO](1)`（GatewayMain.scala:279）创建但**全仓无 acquire**——死代码。
- `RateLimiter`（gateway/ratelimit.scala，60req/60s）只用于 WS 用户消息，不作用于 LLM API。
- 唯一的"限流"是**事后撞墙**：LLM 流看门狗（首 token 90s/空闲 60s）、fallback 链、`ProviderHealthMonitor` markDown（429→Transient→重试→markDown，probe 120s）、全 Down 时 `waitForAnyUp` ≤120s → agent 级 llm-fail-retry 3 次（2s→4s→8s）。**这些是撞墙后的重试噪音，不是限流闸门**——7 个并发请求同时撞墙、同时进入各自重试循环。

### 2.2 卡死检测空白

- `SubAgentTask`（SubAgentTaskStore.scala:21-33）：只有 status/spawnedAt/completedAt，**无 lastActivity**。
- `AgentRecord`（protocol.scala:218-224）：无时间字段；`filterActiveAgents`（WebSocketRoutes.scala:4206-4214）凭 registry 存在即报 running。
- `AgentState`/`ExecutionContext`（protocol.scala:725-806/618-658）：status/turnIdx/currentTurnId，**无时间戳**。
- `sessionBusy` 仅根 agent 发送（depth==0），subagent 从不发。
- 看门狗盘点：LLM 流看门狗只覆盖"彻底不吐 chunk"的死流；Bash 进程 idle-kill 只覆盖 OS 进程；BackoffSupervisor **只在 Terminated（崩溃）时重启，卡死（活着但不动）永不触发**；FlowDagExecutor 节点超时被移除（5min 曾误杀 8min sbt 编译，09f4be58）。

### 2.3 run_in_background 语义（防误杀设计依据）

- 后台任务发起后 **agent 的 turn 结束、回到 idle**（BashTool.scala:388-399 立即返回 jobId）。
- "最后活动"是 **turn 活动**（LLM 流 chunk/工具结果），**不是进程活动**；进程活动由 `JobHealth.lastActivityMs`（输出行时更新）独立跟踪。
- **idle 是合法状态**——不能按"agent 空闲"判卡死；卡死的真实信号是 **Processing 态 + turn 活动长时间为零**。

### 2.4 配置与可复用机制

- `ProviderConfig`（config.scala:37-48）：baseUrl/apiKey/protocol/models/requireThinkingPassback，**无并发/RPM 字段**；`deriveDecoder`（config.scala:51）——**新增字段必须带默认值**。
- 配置热加载：`PATCH /api/config` → `ConfigService.updateConfig` → `providerRegistry.reloadConfig()`（registry.scala:174-189，清 adapter 缓存），**无需重启**。
- 队列先例：actor mailbox（天然 FIFO）+ 内存 pending 队列（每 turn drain 一个）+ **磁盘 `MailQueueStore`**（core/flow/MailQueueStore.scala，原子 tmp+move FIFO，append/removeHead 现成）。
- 退避已三层：Fallback 1000→10000ms、AgentActor 2000→10000ms、BackoffSupervisor 5s→60s；429 已是 Transient 可重试。**无需新造退避**。

## 3. 设计目标

1. **对 agent 无感**：agent/flow 代码零改动，LLM 层透明排队。
2. **分 API**：per-provider 独立闸门（每个 provider 的并发/RPM 不同）。
3. **排队不失败**：并发超限 → 排队等待，不抛错、不触发 fallback churn。
4. **卡死可识别可恢复**：Processing 态 + turn 活动超时 → 识别 → 子 agent 重启 / 根 agent 通知。
5. **不误杀 run_in_background**：idle 态永不判卡死；进程活动与 turn 活动分离。
6. **队列持久化落盘**：排队项重启不丢。

## 4. 核心设计

### 4.1 Per-provider 并发闸门（ConcurrencyGate）

新增 `src/main/scala/nebflow/llm/gate.scala`：

```scala
final class ConcurrencyGate(
  maxConcurrency: Int,          // 默认 3
  rpm: Option[Int],             // 默认 None（不限）
  queueTimeout: FiniteDuration  // 默认 60s
):
  // Semaphore(maxConcurrency) 限并发；滑动窗口限速（复用 gateway/ratelimit 的
  // Ref[IO, Map[String, List[Long]]] 模式，窗口 60s）；per-provider FIFO 排队
  def acquire: IO[ConcurrencyPermit]  // 排队；超时抛 QueueTimeout
```

- **owner**：`ProviderRegistry` 持 `gates: Ref[IO, Map[String, ConcurrencyGate]]`，按 providerId 惰性构建；`reloadConfig()` 时重建（配置热加载即生效）。
- **插入点**：`LlmHandle.send` 的 Fallback action 开头（interface.scala:125-172）与 `sendStream` 的 `tryCandidate` 中 `adapter.sendMessageStream` 前（interface.scala:315-339）——**所有 LLM 调用的唯一入口**，覆盖 AgentActor/EphemeralAgentRunner/TurnEndpoint 全部调用方。
- **fallback 链语义**：每个候选 provider 尝试前 acquire **自己的** gate；A provider 排队超时 → `QueueTimeout`（Transient）→ fallback 到 B provider（B 有自己的 gate，独立并发）。排队**不阻塞** fallback 链的其他 provider。
- **probe 旁路**：`HealthMonitor.probe`（HealthMonitor.scala:198-200）也走 adapter——探测必须**跳过 gate**（非阻塞 tryAcquire，无 permit 即放弃本轮），否则全 Down 时探测被排在大队列后饥饿，恢复延迟恶化。
- **架构边界**：保持"Actors 管消息、IO 管副作用"——gate 是纯 cats-effect 结构，不碰 actor。

### 4.2 排队语义与超时兜底

```
LLM 调用到达 → acquire(provider gate)
  ├─ 有 permit 且未超 RPM → 立即执行
  ├─ 无 permit / 超 RPM → 进 per-provider FIFO 队列
  │    ├─ permit 释放 / 窗口滑过 → 出队执行
  │    └─ 排队 > queueTimeout(60s) → QueueTimeout(Transient) → fallback 链
  └─ 队列满（可选 cap=100）→ 立即 QueueTimeout（防无界堆积）
```

- 排队顺序 FIFO（公平，先到先得）。
- RPM 超限时队首**主动 sleep 到窗口滑过**再执行（复用 `IO.realTime` 计算），或直接排队等窗口——实现取排队（统一路径）。
- 429 撞墙仍走现有 Transient 重试链（闸门未覆盖的瞬时抖动），但闸门存在后 429 应大幅减少。

### 4.3 配置 schema（向后兼容）

`ProviderConfig`（config.scala:37-48）追加三字段（**全部带默认值**，deriveDecoder 缺字段不炸）：

```scala
case class ProviderConfig(
  baseUrl: String,
  apiKey: String,
  protocol: LlmProtocol,
  models: List[ModelConfig] = Nil,
  requireThinkingPassback: Option[Boolean] = None,
  // 新增：
  maxConcurrency: Option[Int] = None,   // None → 默认 3；显式 0 = 不限
  rpm: Option[Int] = None,              // None = 不限
  queueTimeoutMs: Option[Int] = None    // None → 默认 60000
)
```

- 校验加入 `ConfigService.validateConfigJson`（ConfigService.scala:39-95）：maxConcurrency ≥ 0、rpm ≥ 0、queueTimeoutMs > 0。
- 深合并（ConfigService.scala:163-190）对新字段天然保留；走现有 `reloadConfig()` 热加载，无需重启。
- 示例：

```json
{ "llm": { "providers": {
    "anthropic": { "baseUrl": "...", "apiKey": "...", "protocol": "anthropic",
      "maxConcurrency": 2, "rpm": 50 },
    "deepseek": { "baseUrl": "...", "apiKey": "...", "protocol": "openai",
      "maxConcurrency": 3 }
} } }
```

### 4.4 卡死识别与恢复

**活动戳**（新增字段，全部向后兼容）：
- `ExecutionContext`（protocol.scala:618-658）+ `lastActivityMs: Long`——turn 活动中更新点：LLM 流 chunk（AgentCore 收流处）、工具执行完成、turn 完成。`def touchActivity: AgentState` 在 AgentActor/AgentCore 关键路径调用。
- `AgentRecord`（protocol.scala:218-224）+ `startedAt: Long`。

**扫描器**（新增 `nebflow/core/processor/TaskStuckWatcher.scala`，GatewayMain 启动一个 fiber，周期 30s）：
- 扫 `agentRegistry` 中 `AgentKind ∈ {Delegate, Ephemeral, Flow, SubTask}`（与 filterActiveAgents 同集合）。
- 判定：`status == Processing` **且** `now - lastActivityMs > StuckThresholdMs`（默认 10min，可配 `stuckThresholdMs`）。
- **idle 态永不判卡死**（run_in_background 时 agent idle 合法）——防误杀铁律。
- 恢复动作：
  - **子 agent**（有 parentRef）：发 `AbortTask`（已有机制，FlowDagExecutor 用过）→ 子 agent 正常结束 → BackoffSupervisor 收 Terminated → **按现有退避重启**（5s→60s）。不新造重启机制，复用 BackoffSupervisor。
  - **根 agent**：不自动重启——发 WS 事件通知用户（`{"type":"taskStuck","sessionId":...}`）+ 写日志，由用户决定。
- 与 llm-fail-retry 协同：卡死判定阈值（10min）远大于 llm-fail 退避上限（8s×3），不会抢在重试链前误判。

### 4.5 队列持久化

- 排队项落盘：`~/.nebflow/llm-queue/<providerId>.json`，**复用 MailQueueStore 形态**（原子 tmp+move、容忍 decode、append/removeHead）。
- 入队 append、出队 removeHead（与 MailQueueStore 同 API）。
- 重启恢复：GatewayMain 启动时 load 所有 provider 队列文件，重新 enqueue 到内存队列按序 drain（drain 前重取 permit）。
- 落盘内容：完整 `LlmRequest` JSON（messages/tools 已在 mail-queue.json 有落盘先例，体积可接受）。
- 配置开关：`queuePersist: Boolean = true`（ProviderConfig 或全局，默认开）。

### 4.6 前端可见性（二期，不在本 P0）

- `activeAgentEntryJson`（WebSocketRoutes.scala:4232-4240）+ startedAt/idleMs → 前端画"排队中/卡死"标签。
- subagent 发 sessionBusy（当前 depth==0 才发）——二期评估。

## 5. 组件改动清单

| 文件 | 改动 |
|---|---|
| `llm/config.scala` | ProviderConfig + 3 字段（带默认值） |
| `service/ConfigService.scala` | validateConfigJson 校验新字段 |
| `llm/gate.scala`（新） | ConcurrencyGate（Semaphore + 滑窗限速 + FIFO 队列 + QueueTimeout） |
| `llm/queue_store.scala`（新） | LlmQueueStore（MailQueueStore 形态，原子写） |
| `llm/registry.scala` | gates Ref + getGate + reloadConfig 重建 |
| `llm/interface.scala` | send/sendStream 插入 acquire |
| `llm/fallback.scala` | classifyError + QueueTimeout（Transient） |
| `agent/protocol.scala` | ExecutionContext.lastActivityMs + AgentRecord.startedAt |
| `agent/AgentCore.scala` / `agent/AgentActor.scala` | touchActivity 调用点（流 chunk/工具完成/turn 完成） |
| `core/processor/TaskStuckWatcher.scala`（新） | 卡死扫描器 fiber |
| `gateway/GatewayMain.scala` | 启动 watcher + 重启恢复队列 |
| `shared/Defaults.scala` | 默认值常量（maxConcurrency=3、queueTimeout=60s、stuckThreshold=10min、probe=30s） |
| 测试 | gate 单测、watcher 单测、E2E 冒烟 |

## 6. 对 Manager 裁定逐条回应

| # | 裁定 | 回应 |
|---|---|---|
| 1 | 排队优先（fallback 仅排队超时兜底） | **同意**。排队=用户点名要求（"要做并发管理、排队"）；fallback 只在 queueTimeout（60s）后触发，避免"排队永等"与"换 provider 保活"矛盾。 |
| 2 | 每 provider 并发 2-3 | **同意并定值 3**。理由：主流 API 免费/常见档并发下限 ≥3（OpenAI/Anthropic/DeepSeek/GLM 均 ≥3），3 是安全下限；7 个并发 Delegate 场景下 4 个排队而非撞墙。**反驳点**：不硬编码死——`maxConcurrency` 可配（0=不限），高配额自建网关用户可放开。默认 3 是普适安全值。 |
| 3 | 常见档 RPM | **部分同意，RPM 默认 None（不限）**。理由：RPM 因账号 tier 差异 100 倍（Anthropic 免费 50/min vs 企业 4000/min），给"常见档默认"必然误伤一端；且 RPM 超限走现有 429→Transient→fallback 链能自愈（不会造成永不完成的卡死——卡死的根因是并发堆积）。文档给出常见档参考表（Anthropic 50/OpenAI 3500/DeepSeek 60/GLM 120），显式配置。**反驳依据**：并发闸门解决卡死，RPM 是平滑辅助，缺省不限不破坏现有行为。 |
| 4 | 卡死判定=最后活动时间+类型（防误杀 run_in_background） | **同意并细化**。判定只对 **Processing 态**；idle 永不判（run_in_background 时 agent idle 合法）。"最后活动"=turn 活动（LLM chunk/工具结果），与进程活动（JobHealth）分离。阈值 10min（≫ llm-fail 退避上限，不抢重试）。 |
| 5 | 队列持久化落盘 | **同意**。复用 MailQueueStore 原子写模式，`~/.nebflow/llm-queue/<providerId>.json`，重启 load 重新排队；配置开关 queuePersist 默认开。 |

## 7. 验收条件（可执行）

**红线：隔离实例冒烟第一项，不通过全部无效。**

隔离实例模式：`NEBFLOW_HOME=/tmp/nb-cc-smoke nohup sbt -batch "run --home /tmp/nb-cc-smoke --port 8093 --no-browser" > /tmp/nb-cc-smoke.log 2>&1 & disown`；就绪信号=日志 grep "gateway listening"。冒烟后清理两杀：`pgrep -f -- "--port 8093"` + `lsof -tiTCP:8093 -sTCP:LISTEN`。

1. **编译与测试**：`sbt test` 全绿（含 gate 单测、watcher 单测、协议字段兼容测试）。
2. **并发排队（核心）**：nebflow.json 配 `maxConcurrency: 1` 的单 provider + 同一 LLM 响应里 2 个 Delegate → 第二个 Delegate 的 LLM 请求**排队**（router 日志可见两个 sendStream 时间戳相差 ≥ 首个请求耗时），两者都完成、无失败。断言：无 429 错误、无 fallback 记录、两个 agentDone。
3. **RPM 限速**：`rpm: 2` + 连续 3 个请求 → 第三个排队等待窗口滑过，全部成功。
4. **排队超时 fallback**：provider A `maxConcurrency: 1` + A 挂起（mock 长流）→ 第二个请求排 A 队超 queueTimeout（测试配置 2s）→ fallback 到 provider B 成功。断言：A 无 markDown（排队超时≠provider 故障）。
5. **卡死识别恢复（子 agent）**：mock LLM 发一个永不完成的流（Stream.never）→ TaskStuckWatcher（测试配置阈值 3s、probe 1s）→ 识别 → AbortTask → BackoffSupervisor 重启 → 新 turn 用正常 mock 完成。断言：task 状态 running→completed、重启计数 +1、无残留 running。
6. **防误杀 run_in_background**：agent 发起 `run_in_background` 长命令（sleep 300s）后回 idle → watcher 扫 N 轮**不判卡死**（idle 豁免断言）。agent 发起前台 sleep 60s 命令（30s 自动后台化）同理。
7. **队列持久化**：`maxConcurrency: 1` + 2 个并发请求（第二个排队中）→ 重启实例（同 NEBFLOW_HOME）→ 排队项恢复并按序完成。断言：两个请求都成功、无丢失。
8. **配置兼容**：旧 nebflow.json（无新字段）正常加载启动；`PATCH /api/config` 热加载 maxConcurrency 变更即时生效（无重启）。
9. **向后兼容回归**：全量 sbt test 无失败；生产实例 8080 不受影响（隔离实例验证）。

## 8. 风险与回滚

- **默认 3 的误伤风险**：极少数场景（单 agent 多工具循环）并发天然 <3，不受影响；大并发用户显式 `maxConcurrency: 0` 放开。回滚=删除配置字段（默认值语义保持 3）或改回 None 默认（一行常量）。
- **队列落盘体积**：LlmRequest 含 tools/schema，单条可能数百 KB；queuePersist=false 可关。排队项生命周期短（秒级），文件自清理（removeHead）。
- **watcher 误判**：阈值 10min 保守；任何"processing 但 LLM 在重试链中"的场景（8s×3 + probe 120s）都不会超 10min 无活动——重试链每次动作都 touchActivity。极端情况（用户挂起单步调试）阈值可配。
- **实施顺序**：4.1-4.3（闸门+排队）→ 4.5（持久化）→ 4.4（卡死恢复）→ 验收 1-4/7-9 先行，5-6 后行。
