> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 冻结功能实现方案（Freeze Schedule · 自动暂停/恢复）

> **版本**：v1.2 · 2026-08-19
> **状态**：v1.0 已实施；v1.2 语义修订已实施（#337）
> **版本日志**：
> - v1.0（2026-08-19 11:40）：初稿，12 项事实断言（F1-F12）+ 状态机 + 改动点 + 验收条件
> - v1.1（2026-08-19 11:5x）：二次核verify 后修正 4 处——① F2 精确化（shadow 非 override，AgentCore 内部递归的静态解析细节）；② 6a 修正 markTeamBusy 时序（必须无条件先于 gate，否则 frozen 分支 team agent 不标 busy）；③ frozen 唤醒路径补 checkDuplicate；④ 新增 D11 交互豁免（askMode + plan agent 的 freezeExempt 标志，机制具体化到字段级）
> - **v1.2（2026-08-19 23:16 用户澄清，#337）**：**配置语义反转为冻结时间（黑名单）**——segments 配的是**非工作时间/冻结时段**，段内冻结、段外正常工作。v1.0/v1.1 的白名单语义（segments=工作时间、时段外冻结）废弃：深夜 23 点设置时段被当成"工作时间"放行即此语义错误的实锤。同时：① 支持**跨午夜段**（start > end，如 23:00-08:00 = [23:00,24:00)∪[00:00,08:00)）；② start == end 仍非法（零长度无意义；不定义为 24h 全冻结——防误配全停）；③ nextChangeAt 升级为**下一次真实语义翻转点**（重叠段并集下跳过非翻转的中间边界；resumeAt 恒严格未来）；④ 代码层重命名：WorkSchedule→FreezeSchedule、WorkScheduleConfig→FreezeScheduleConfig、WorkScheduleSegment→FreezeSegment、WorkWindow(open)→FreezeWindow(frozen)、workScheduleRef→freezeScheduleRef——**JSON 键名 workSchedule 与 WS 命令 setWorkSchedule 保留不动**（前端契约）。
> **需求原话**（2026-08-19 11:17）：*"用户可以在设置里设置 nebflow 工作的时间段。比如上午 9 到 12 点下午 14 到 18 点，到点就自动冻结——比如在 llm 的工作轮次，调用了工具，执行了工具，但是冻结住，不给返回触发下一轮，直到到了工作时间才接着工作。可以选择是否开启。"*
> **语义澄清**（2026-08-19 23:16，#337 定案）：用户配置的是**冻结时间（非工作时间）**——区间内不工作（冻结），区间外正常工作，**黑名单语义**。原话中的"工作的时间段"按此理解：到冻结时段就停，出了时段就干。核心场景"23:00-08:00 冻结"需要跨午夜段支持。
> **硬指标**：冻结期间零 token 消耗；用户手动输入可立即唤醒。

---

## 1. 现状分析（关键代码定位）

### 1.1 Turn 循环全景

Turn 循环是消息驱动的 behavior 状态机（`idle` → `processing` → … → `idle`），核心链路：

```
idle(UserInput) ──→ pipeLlmCall ──→ [forkTurn: LLM 流式调用]
                                          │ LlmComplete
                                          ▼
                              handleLlmCompleteBranch
                              ├─ toolCalls 非空 → pipeToolExecutions ──→ [forkTurn: 工具执行]
                              │                                            │ ToolsComplete
                              │                                            ▼
                              │                    AgentActor.scala L1247 处理结果
                              │                    L1330 组装 newMessages（含工具结果）
                              │                    L1369 forkTurn(persistIfSession) ←★ 工具结果此刻已持久化
                              │                    L1376 pipeLlmCall ──→ 下一轮 LLM   ←★ 冻结点
                              └─ 纯文本 → finishTurn → finishTurnCont
                                          ├─ 有 pendingEvents/立即输入/邮件队列 → pipeLlmCall（续turn）
                                          └─ 无 → idle
```

**关键事实（设计依据）**：

| # | 事实 | 出处 | 对设计的影响 |
|---|------|------|------------|
| F1 | `ToolsComplete` handler 在调用 `pipeLlmCall`（L1376）**之前**已把工具结果 append 进 `state.execution.messages` 并 forkTurn 持久化（L1369-1374） | `AgentActor.scala` L1247-1377 | 冻结点选在 L1376 之前 → 工具结果安全落盘，冻结=纯内存等待，崩溃不丢结果 |
| F2 | 所有 AgentActor 层的 LLM dispatch 都经过 **`AgentActor.pipeLlmCall` shadow**（L2521-2540，`private def`、签名不同于 AgentCore 的 `protected def`——是遮蔽而非重写；`super.pipeLlmCall` 前只做了 markTeamBusy）。注意：AgentCore **内部**递归（`maybeAutoCompact`/`startDirectCompaction` → `pipeLlmCall`，AgentCore.scala L152/L259）静态解析到 AgentCore 自身方法、不经过 shadow——但它们只可能在 shadow 的 `super` 调用（即 gate 放行）之后被执行，因此 gate 在 shadow 处仍是单 choke point | `AgentActor.scala` L2521、`AgentCore.scala` L266-275、L152/L259 | 单一 choke point：在 shadow 里插 gate 即可拦截所有 AgentActor 层 dispatch 路径 |
| F3 | actor 消息循环**串行**执行 handler IO（`processMessageOrSignal` 直接 `action.flatMap`，无 fork） | `ActorSystem.scala` L166-193 | **禁止**在 handler 内联 `IO.sleep` 等待恢复——会阻塞整个 mailbox，Interrupt/Stop 无法送达。必须用独立 behavior + 外部唤醒 |
| F4 | `forkTurn(io)` = 启动可跟踪 fiber 立即返回；`cancelCurrentTurn()` 取消全部 turn fiber | `ActorContext.scala` L77-90 | fiber 只用于副作用；"forkTurn(sleep) \*> pipeLlmCall" **不能**实现延迟 dispatch（见 §7 已知问题） |
| F5 | TaskStuckWatcher 判卡死条件 = `status == Processing && now - lastActivityMs > 10min`，只扫 Processing | `TaskStuckWatcher.scala` L77、`Defaults.scala` L129 | 新增 `AgentStatus.Frozen` 即**天然豁免**，watcher 零改动 |
| F6 | `touchRegistryActivity` 是 registry status/lastActivityMs 的唯一写入口 | `AgentCore.scala` L1317-1329 | 冻结进入/退出时调用它写 Frozen/Processing |
| F7 | 前端 busy 会话发消息走**客户端排队**（`queueMessage`），不发给后端 | `input.js` L600-610 | 冻结期间若沿用 busy 排队，用户无法唤醒 → 前端必须对 frozen 会话绕过排队直发 |
| F8 | 前端有流超时（`streamTimeoutMs` 600s + 30s 缓冲，无活动即报超时+clearBusy） | `main.js` L298-312、`Defaults.scala` L19 | 冻结可能持续数小时 → frozen 事件必须停掉该计时器 |
| F9 | 配置持久化已有成熟范式：`thinkingConfig` 顶层节 + WS 命令 targeted write + `broadcastServerConfig` 广播 + `SharedResources.thinkingConfigRef` 运行时热更 | `WebSocketRoutes.scala` L719-758、`gatewayConfig` 初始化 | `workSchedule` 完全复制该范式 |
| F10 | ConfigService.updateConfig 深合并**保留** incoming 未提及的顶层键 | `ConfigService.scala` L173-200 | 新顶层节 `workSchedule` 与现有配置共存，无迁移 |
| F11 | 崩溃恢复：子 agent 走 BackoffSupervisor + 磁盘消息恢复（`ResumeTurn` 命令已定义但 AgentActor 无 handler，FlowTreeActor 的恢复路径目前是 no-op）；根 agent 无 turn 级自动恢复，重启后靠用户消息/ExternalEvent 重新拉起 | `protocol.scala` L130、`FlowTreeActor.scala` L646-688 | 冻结状态不持久化——重启后 gate 按「当前时钟 + 持久化配置」重新评估，语义自然正确 |
| F12 | usage 记录（token 消耗）只发生在 LlmComplete 成功分支 | `AgentActor.scala` L1014-1041 | 零 token 验收可用 UsageRecordStore 断言 |

### 1.2 冻结点精确位置

```scala
// AgentActor.scala L1368-1377（ToolsComplete handler 尾部）
for
  _ <- ctx.forkTurn(persistIfSession(resources, updatedState)...)   // L1369 ★工具结果已持久化
  _ <- immEventIO
  result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, tc.replyTo)  // L1376 ★冻结 gate 插入处
yield result
```

gate 实际放在 **`pipeLlmCall` shadow 内部**（覆盖 L1376 及全部其他 dispatch 点，见 F2）。

---

## 2. 方案总览
![冻结状态机](/Users/dev/.nebflow/docs/Nebflow/assets/freeze-state.svg)

![改动点架构全景](/Users/dev/.nebflow/docs/Nebflow/assets/freeze-arch.svg)

**三句话方案**：
1. **Gate**：`AgentActor.pipeLlmCall` shadow 头部检查冻结时间表（黑名单：段内=冻结；交互式轮次豁免，见 D11）——系统驱动的 dispatch 在冻结时段内**不调用 LLM**，转而进入新的 `frozen` behavior（持有完整 state + replyTo）。
2. **唤醒**：新增 `FreezeScheduler` 全局 fiber（仿 TaskStuckWatcher 模式，30s 扫描 registry 中 status==Frozen 的 agent 发 `CheckFreezeGate`，agent 自行重评估时间表）；用户 `UserInput` 在 frozen behavior 中直接唤醒（注入用户消息 + 立即 dispatch，优先于冻结的续跑）。
3. **配置**：`nebflow.json` 顶层 `workSchedule` 节 + 设置面板 UI + `setWorkSchedule` WS 命令 + `workScheduleRef` 热更，默认关闭。

### 2.1 设计决策记录

| ID | 决策 | 理由 |
|----|------|------|
| D1 | gate 放 `pipeLlmCall` override 单点，加 `cause` 参数区分「系统续跑（Gated）」与「用户发起（UserWake）」 | F2 单 choke point；唤醒规则收敛在一处判断 |
| D2 | 唤醒规则：`UserInput.clientMessageId.isDefined`（真实用户 WS 输入恒带 id；工具注入/Mail/BackoffSupervisor 的系统注入为 None）→ 唤醒；其余 UserInput → 入 `pendingUserInputs` 排队。`SkillActivate`/`AskQuestion`/`StartPlan`/`PlanApproved`/`PlanFeedback` 视为用户动作 → 唤醒 | 用户手动输入优先（需求）；系统注入（含 3am 子 agent 崩溃重启的 "continue"）不破坏零 token |
| D3 | frozen 用**独立 behavior**（不用 processing 加 flag） | processing 有 20+ case（重复 ToolsComplete 会双 dispatch、stale LlmComplete 等），独立 behavior 天然隔离这些交互；状态机显式可测 |
| D4 | 恢复用 **FreezeScheduler 30s 轮询**（不用 per-agent sleep fiber） | 仿 TaskStuckWatcher 已验证模式；天然支持运行中改配置/时钟漂移；无需 epoch 去重（CheckFreezeGate 到非 frozen behavior 即 no-op）；恢复延迟 ≤30s 可接受 |
| D5 | 冻结粒度：**全局**（所有 agent 同一时间表），一期不做 per-agent | 需求 #9 建议；per-agent 留二期（workSchedule 节预留 per-agent 覆盖的扩展位） |
| D6 | 时间段语义（v1.2 黑名单）：segments = **冻结时间（非工作时间）**；每日相同、`HH:mm` 段、start 含 end 不含；`start > end` = **跨午夜段**（23:00-08:00）；`start == end` 非法；enabled 且 segments 为空 → 配置非法（拒绝保存+后端 fail-safe 视为关闭）；段重叠 → 允许（判定取并集） | #337 用户澄清（2026-08-19 23:16）；跨午夜为核心场景 |
| D7 | 流式/工具执行**永不被打断**：gate 只在 dispatch 边界生效 | 需求 #5；实现上 gate 位置天然保证（LLM 流与工具都在 forkTurn fiber 里跑完才产生下一跳消息） |
| D8 | frozen 期间会话保持 `sessionBusy=true`，但前端单独标记 frozen 态（停流超时计时 + 发消息绕过排队直发） | F7/F8；不让前端误判「turn 结束」触发队列 drain |
| D9 | 冻结状态**不持久化**。重启后：gate 按「当前时间 + 磁盘配置」重新评估；工具结果已在冻结前持久化（F1），历史不丢 | F11；根 agent 本就无 turn 级自动恢复，冻结不恶化现状（见 §5.3 诚实声明） |
| D10 | `Interrupt`/`Stop` 在 frozen 态 = 放弃续跑（cancelCurrentTurn → idle，历史保留）——与 processing 的 Interrupt 语义一致 | 停止按钮的直觉语义：「别继续了」 |
| D11 | **交互豁免**：`state.askMode.isDefined`（ask 轮）或 `state.session.freezeExempt`（plan agent 等用户在场等待的交互会话）→ gate 直接放行，不冻结。机制：`SessionContext` 新增 `freezeExempt: Boolean = false` 字段（protocol.scala），`AgentState.apply`/`AgentActor.apply` 各加一个同名默认参数透传，`PlanAgent.spawn`（PlanAgent.scala L66）构造时传 `freezeExempt = true` | 用户正在看着规划面板/等 ask 回答时冻结 = 浪费用户时间而非省 token；这三处改动全部带默认值，既有 spawn 点与测试零改动。v1.0 此处只写「实现时确认」，v1.1 具体化到字段级 |

---

## 3. 精确改动点

### 3.1 后端（8 个文件，2 新建）

#### ① `src/main/scala/nebflow/core/schedule/FreezeSchedule.scala`（新建；v1.0 名为 WorkSchedule.scala，#337 重命名）

```scala
package nebflow.core.schedule

/** nebfloow.json 顶层 workSchedule 节的模型 + 纯函数时间表判定。 */
case class FreezeSegment(start: String, end: String)     // "23:00" / "08:00"（start>end = 跨午夜）
case class FreezeScheduleConfig(enabled: Boolean = false, segments: List[FreezeSegment] = Nil)

object FreezeSchedule:
  given Decoder/Encoder (circe derive)

  /** 解析 "HH:mm" → 当日分钟数。非法 → None。 */
  def parseHHmm(s: String): Option[Int]

  /** fail-safe 加载：decode 失败 / enabled 且 segments 空 / 任一段非法（含 start==end）→ disabled */
  def load(json: Option[Json]): FreezeScheduleConfig

  /** 核心判定（纯函数，注入 now 便于测试）。
   *  frozen=true → 当前在冻结时段内；nextChangeAt=下一次真实冻结/解冻翻转点的 epoch millis。 */
  def eval(cfg: FreezeScheduleConfig, now: Long): FreezeWindow
  // 规则（v1.2 黑名单）：now 的当日分钟数 ∈ 任一段覆盖范围 → frozen。
  // 跨午夜段（a>b）：m >= a || m < b（环绕判定）。
  // nextChangeAt：按时间序扫描未来边界，第一个使 frozen 态翻转的边界（重叠段并集
  // 下跳过非翻转的中间边界）——恒严格晚于 now；全天冻结 → None。
  // segments 空且 enabled=false → 恒不冻结（nextChangeAt=None）。
```

#### ② `src/main/scala/nebflow/agent/protocol.scala`

- **L545-549** `enum AgentStatus` 增 `case Frozen`（枚举无序列化消费方，影响面仅 pattern match 编译检查）
- **L332** `enum AgentStreamEvent` 增：
  ```scala
  case Frozen(resumeAtMillis: Option[Long])   // WS: type "frozen" / subagent "agentFrozen"
  case Resumed                                // WS: type "resumed" / subagent "agentResumed"
  ```
  `toJson`（L362-521）补两个 case，字段 `resumeAt`（epoch ms，可空）
- **L200** `object AgentCommand` 增 `case object CheckFreezeGate extends AgentCommand`
- **L586-629** `case class SessionContext` 增字段 `freezeExempt: Boolean = false`（D11 交互豁免；默认值 → 所有既有构造点零改动）

#### ③ `src/main/scala/nebflow/agent/SharedResources.scala`

新增字段（挨着 `thinkingConfigRef`）：
```scala
freezeScheduleRef: Ref[IO, FreezeScheduleConfig]   // 默认 Ref.unsafe(FreezeScheduleConfig()) — 由 GatewayMain 从配置覆写（JSON 键名仍为 workSchedule）
```
（带默认值 → 现有测试的 SharedResources 构造全部免改。）

#### ④ `src/main/scala/nebflow/gateway/GatewayMain.scala`

- thinkingConfigRef 初始化处（L289 附近）：读 `config.workSchedule` → `freezeScheduleRef.set(FreezeSchedule.load(...))`
- **L593-601** TaskStuckWatcher 启动块旁：
  ```scala
  _ <- FreezeScheduler.run(sharedResourcesWithDaemon, interval = Defaults.FreezeCheckIntervalSec.seconds).start
  ```

#### ⑤ `src/main/scala/nebflow/core/processor/FreezeScheduler.scala`（新建）

```scala
/** 冻结恢复扫描器（TaskStuckWatcher 的姊妹模式）：
  * 每 30s 扫 agentRegistry，status==Frozen 的 agent 发 CheckFreezeGate。
  * agent 在 frozen behavior 中重评估时间表：仍冻结 → 继续等；已开放 → 恢复 dispatch。
  * 错误自愈（handleErrorWith 吞掉防 fiber 崩溃）；`>>` 递归防栈溢出（同 TaskStuckWatcher L60-64）。 */
object FreezeScheduler:
  def run(resources: SharedResources, interval: FiniteDuration): IO[Unit]
```

#### ⑥ `src/main/scala/nebflow/agent/AgentActor.scala`（核心）

**6a. gate 注入 — pipeLlmCall shadow（L2521-2540）**：

```scala
sealed trait DispatchCause /** 系统续跑：受冻结调度约束 */
object DispatchCause:
  case object Gated extends DispatchCause    // ToolsComplete 续轮 / finishTurnCont 续 turn / ExternalEvent 触发 / CompactionComplete 恢复 …
  case object UserWake extends DispatchCause // 用户显式发起

private def pipeLlmCall(..., cause: DispatchCause = DispatchCause.Gated)(using ctx) =
  markTeamBusy(agentDef, state.sessionId) *>                    // ★v1.1：无条件先执行（幂等 Set 插入）——
                                                                //  放在 gate 之后的 else 分支会导致 frozen 的
                                                                //  team agent 不标 busy，Teams 面板显示错误
  resources.freezeScheduleRef.flatMap { cfg =>
    val window = FreezeSchedule.eval(cfg, System.currentTimeMillis())
    val interactive = state.askMode.isDefined || state.session.freezeExempt   // D11 交互豁免
    if window.frozen && cause == DispatchCause.Gated && !interactive then
      enterFrozen(agentDef, resources, depth, parentRef, state, replyTo, window.nextChangeAt)
    else
      super.pipeLlmCall(...)
  }
```

**6b. 用户发起的调用点显式传 `cause = UserWake`**（共 4 处）：
- `idle` 的 `UserInput` → L401（且仅当 `clientMessageId.isDefined`，见 D2；否则保持 Gated——排队语义由 frozen handler 处理）
- `idle` 的 `AskQuestion` L419、`SkillActivate` L442
- `planWaiting` 中用户动作路径（StartPlan/PlanApproved/PlanFeedback → 的 dispatch，实现时按调用链标注）

其余全部调用点（ToolsComplete L1376、finishTurnCont L2250/L2321/L2370、CompactionComplete L1551/L1608/L1654、idle ExternalEvent L600/L620、RestartAgent L1454 等）保持默认 `Gated`。

**6d. plan agent 豁免透传**：`AgentActor.apply`（L213-239）增参数 `freezeExempt: Boolean = false` → 传入 `AgentState.apply`（L811-866，同步增默认参数）→ 落入 `SessionContext`；`PlanAgent.spawn`（PlanAgent.scala L65-84）构造 `AgentActor(...)` 时传 `freezeExempt = true`。ask 轮无需透传——gate 直接检查 `state.askMode.isDefined`。

**6c. 新增 `frozen` behavior**（放在 processing 定义旁）：

```scala
private def frozen(
  agentDef, resources, depth, parentRef,
  state: AgentState,          // 已含工具结果/事件消息（冻结前组装完毕）
  replyTo: Option[ActorRef[AgentEvent]],
  resumeAt: Option[Long]      // 供 WS 事件展示；实际恢复以重评估为准
)(using ctx): Behavior[AgentCommand] =
  // 进入时已做（enterFrozen）：emitStream(Frozen(resumeAt)) + touchRegistryActivity(Frozen)
  Behaviors.receiveMessage:
    case AgentCommand.CheckFreezeGate =>
      // 唯一恢复驱动：重评估（支持运行中改配置）。仍冻结 → 原地留任；开放 → 恢复
      for
        cfg <- resources.freezeScheduleRef.get
        window = FreezeSchedule.eval(cfg, System.currentTimeMillis())
        result <-
          if !window.frozen then
            emitStream(Resumed) *>
            pipeLlmCall(agentDef, resources, depth, parentRef, state, replyTo)  // cause 默认 Gated，但已出冻结段 → 通过
          else IO.pure(frozen(..., window.nextChangeAt))   // 更新 resumeAt（可再 emit Frozen 刷新前端倒计时）
      yield result

    case AgentCommand.UserInput(text, replyTo2, clientMessageId, blocks, ...) =>
      // ★ 用户唤醒：注入用户消息到冻结中的上下文，立即 dispatch（工具结果+用户新指令一起喂给 LLM）
      if clientMessageId.isDefined then
        val (isDuplicate, _) = checkDuplicate(clientMessageId, state)          // ★v1.1：补 dedup（防 WS 重发
        if isDuplicate then IO.pure(frozen(...原参数...))                       //  重复注入两条用户消息）
        else
          val userMsg = ...  // 同 idle L377-380 的组装（含 source 标记）
          emitStream(Resumed) *>
          pipeLlmCall(..., state.withMessages(state.messages :+ userMsg), replyTo2, cause = UserWake)
      else
        // 系统注入（Mail/Delegate/Supervisor continue）：排队不唤醒
        IO.pure(frozen(..., state.copy(execution = pendingUserInputs :+ msg)))

    case AgentCommand.Interrupt() =>
      // 放弃续跑：同 processing L1380-1395 语义（cancelCurrentTurn + Interrupted 事件 + idle）
    case AgentCommand.Stop(_) => // 同 processing L1459-1465
    case AgentCommand.RestartAgent(level) => // 同 processing L1430-1456
    case ev: AgentCommand.ExternalEvent =>      // 排队 pendingEvents（不唤醒）
    case msg: AgentCommand.ImmediateInput =>    // 排队 pendingImmediateInputs
    case AgentCommand.MailQueued(...) =>        // 计数 +1
    case AgentCommand.SetSafetyMode / UpdateContextWindow / UpdateGitBranch =>
      // 镜像 processing 的轻量状态更新（保持一致性）
    case _ => IO.pure(frozen(...))              // stale LlmComplete/LlmFailed/ToolsComplete → 丢弃
```

`enterFrozen`（gate 转入辅助）：`emitStream(Frozen(resumeAt)) *> touchRegistryActivity(Frozen) *> IO.pure(frozen(...))`。
注：`sessionBusy` 维持 true 不动（进入前已是 busy；idle-ExternalEvent 路径在 gate 前也已 emit busy）。

#### ⑦ `src/main/scala/nebflow/gateway/WebSocketRoutes.scala`

- `handleMessage` 增加 `case "setWorkSchedule"`（仿 `setThinking` L1020-1032）：
  1. 解析 payload `{enabled, segments:[{start,end}]}` → `FreezeSchedule.validate`（start≠end（跨午夜 start>end 合法）、开启时非空、HH:mm 格式）→ 错误回 `{type:"configUpdateFailed", message}`
  2. `persistWorkSchedule`：targeted write 顶层节（照抄 `persistThinkingConfig` L744-758 的 read-merge-write）
  3. `freezeScheduleRef.set` + `broadcastServerConfig` + **立即对 registry 中 Frozen 的 agent 发 CheckFreezeGate**（配置改了不用等 30s）
- `broadcastServerConfig`（L719-741）payload 增加 `"workSchedule"` → 前端 settings 回显

#### ⑧ `src/main/scala/nebflow/shared/Defaults.scala`

```scala
/** FreezeScheduler 扫描间隔（恢复延迟上限）。 */
val FreezeCheckIntervalSec: Int = 30
```

### 3.2 前端（9 个文件）

| 文件 | 改动 |
|------|------|
| `web/js/state.js` | 新增 `frozenSessions: Set`、`workSchedule`（serverConfig 回显用） |
| `web/js/ws.js` | L125 serverConfig 处理存 workSchedule；L138 事件白名单加 `'frozen','resumed','agentFrozen','agentResumed'` |
| `web/js/main.js` | `onMessage('frozen')`：`state.frozenSessions.add(sid)` → 状态条显示「已冻结 · 将于 HH:mm 恢复」（复用 `setStatus`/statusWrap，chat.js L202）+ **`clearTimeout(state.sessionBusyTimeouts[sid])`** 停流超时；`onMessage('resumed')`：移除标记、清状态条、恢复 busy spinner。子 agent 事件转发 bgAgentPopup |
| `web/js/input.js` | `send()` L600 isBusy 分支前插：`if (state.frozenSessions.has(v.sessionId)) → 直发 sendWs（绕过 queueMessage，唤醒）`，输入框 placeholder 提示「发送消息立即唤醒」 |
| `web/js/sidebar.js` | `renderSettings()` L296-320 运行时区块：`toggle-schedule` 开关 + 时段编辑器（`segment-row`：两个 `time-input` + 删除钮 + 「添加时段」）；`bindSettingsEvents` 绑定校验与保存（发 `setWorkSchedule`） |
| `web/js/bgAgentPopup.js` | agentFrozen/agentResumed → tile 徽标「已冻结 · HH:mm 恢复」/恢复运行中样式 |
| `web/css/sidebar.css` | `.segment-row/.time-input/.seg-remove`（复用 cfg-* 变量体系） |
| `web/js/locales/zh-CN.js` | `settings.workSchedule`「工作时间（冻结调度）」、`settings.workScheduleHint`、`chat.frozen`「已冻结 · 将于 {time} 恢复」、`chat.frozenWakeHint`「发送消息可立即唤醒」、校验文案 ×4 |
| `web/js/locales/en.js` | 同上英文 |

**UI 视觉预览稿**：`/tmp/freeze-ui-preview.html`（设置面板开关态/关闭态、聊天冻结状态条、用户唤醒后、子 agent 弹窗徽标、校验规则）

### 3.3 测试（2 个新文件 + 编译修复）

| 文件 | 内容 |
|------|------|
| `src/test/scala/nebflow/core/schedule/WorkScheduleSpec.scala`（新） | `eval` 纯函数：段内/段外/边界（09:00 恰开、12:00 恰关）/多段并集/空段 fail-safe/非法段 fail-safe/nextChangeAt 跨日（23:50 → 次日 09:00） |
| `src/test/scala/nebflow/agent/FreezeGateSpec.scala`（新） | ① gate：冻结窗口内 ToolsComplete → 无 LLM 调用（mock LlmHandle 断言零调用）+ frozen 事件发出 + registry=Frozen；② 唤醒：frozen 态 UserInput(clientMessageId=Some) → 1 次 LLM 调用且消息含工具结果+用户消息（重复 clientMessageId 二次发送 → 不重复注入）；③ 系统注入不唤醒；④ CheckFreezeGate 开窗恢复 → 1 次调用、resumed 事件；⑤ Interrupt 放弃 → idle、历史保留；⑥ TaskStuckWatcher.scan 对 Frozen 记录零动作（豁免回归钉死）；⑦ askMode/freezeExempt 豁免（B11）；⑧ frozen team agent 保持 busy（B12） |
| 既有测试 | `AgentStatus`/`AgentStreamEvent`/`AgentCommand` 新 case 触发的非穷尽 match 编译错误逐一修复（预期集中在 toJson 类函数） |

---

## 4. 预期行为变更（用户视角）

**现在**：agent 一旦开工就连续工作到底——凌晨 3 点也会一轮接一轮调 LLM 烧 token，睡觉前不敢挂长任务。

**改后**：
1. 设置里开启「冻结调度」并配好**冻结时段**（黑名单，如 23:00-08:00 深夜段）。
2. 进入冻结时段时（设置后不立即打断当前 LLM 轮——下一个工具结果返回点冻结），agent 刚跑完一轮工具（文件已改、命令已执行、结果已存盘），正要发起下一轮 LLM 调用时——**停住**。聊天区 spinner 变成状态条：「已冻结 · 工具结果已保存，将于 <段结束时刻> 恢复继续」。子 agent 弹窗里对应 tile 显示 frozen 徽标。
3. 出冻结段（30 秒内）自动恢复：把攒着的工具结果喂给 LLM，继续干活，状态条消失。全过程无人值守、零 token 消耗。
4. 冻结期间任何时刻发消息（不会被排队）→ 立即唤醒，agent 带着已有工具结果+你的新指令继续。点停止按钮 → 放弃继续（历史保留）。
5. 不开启开关 → 行为与现在 100% 一致。

---

## 5. 边界情况

### 5.1 流式输出中途到冻结时间
LLM 正在流式输出/工具正在执行时到点 → **不打断**。流和工具在各自 forkTurn fiber 中跑完，产生的 `LlmComplete`/`ToolsComplete` 走到下一跳 dispatch 时才过 gate（D7）。最坏情况：12:00 到点，一条 8 分钟的长命令跑到 12:08 完成 → 12:08 起冻结，14:00 恢复。

### 5.2 冻结期间的用户干预
| 操作 | 行为 |
|------|------|
| 发消息 | **唤醒**：注入用户消息 + 立即 dispatch（工具结果与用户新指令同轮喂给 LLM），resumed 事件。后续若仍在冻结时段，新 turn 的 ToolsComplete 会再次过 gate → 再冻结（正确语义） |
| 点停止 | 放弃续跑：Interrupt 语义（历史含工具结果保留，等同今天在工具刚执行完时打断） |
| 改设置（开/关/改时段） | `setWorkSchedule` → ref 热更 + 主动向所有 Frozen agent 发 CheckFreezeGate → ≤即时重评估（新配置下已开放 → 立即恢复；新配置下仍冻结 → 更新 resumeAt） |
| 关闭功能 | 同上——所有 frozen agent 立即恢复（window 恒 open） |

### 5.3 崩溃恢复（诚实声明）
- 冻结前工具结果已持久化（F1），重启不丢。
- **根 agent**：冻结期间重启后 turn 不会自动续跑（根 agent 本就没有 turn 级自动恢复——今天的任何中断 turn 也一样）。下次用户发消息（任何时间，含冻结时段——用户输入即唤醒）或 ExternalEvent（定时任务/后台任务完成，冻结时段会先冻结、开窗后续跑）到达时自然续上。
- **子 agent**：BackoffSupervisor 重启 → 恢复磁盘消息 → 注入 "continue"（系统 UserInput，clientMessageId=None）→ 冻结时段被 gate 拦住继续冻结，开窗后 FreezeScheduler 驱动续跑。
- 结论：崩溃场景下冻结不丢数据、不烧 token、开窗后能续跑的路径都通；唯根 agent 的「自动」续跑以有 ExternalEvent/用户输入为前提——这是现状架构的既有边界，非本功能引入。

### 5.4 与其他机制的交互
| 机制 | 交互 |
|------|------|
| **TaskStuckWatcher** | Frozen ≠ Processing → 自动豁免（F5）；FreezeGateSpec ⑥ 钉死回归 |
| **压缩（compaction）** | Save turn 中的 ToolsComplete 续轮同样过 gate（冻结不区分压缩轮）；CompactionComplete 恢复 dispatch 过 gate。冻结发生在 gate 边界，pendingCompaction 随 state 持有在 frozen 态，恢复后无缝继续 |
| **ConcurrencyGate（LLM 并发闸）** | 冻结 agent 从不调 sendStream → 不占闸位，无闸位泄漏 |
| **llm-fail 重试** | 重试 dispatch 过 gate——冻结时段不重试（正确：不烧 token），开窗后恢复即重试 |
| **Mail ask（打断式）** | 到达 frozen agent → ImmediateInput → 排队（D2），开窗/唤醒后注入。与 draft 中的 interrupt-pending-mail-spec 交互在实现时复核 |
| **子 agent barrier**（outstanding>0 时 idle 持有结果） | idle 且不 dispatch → 无 gate 触点，行为不变；batch 完成 → ExternalEvent → dispatch → gate（冻结时段冻结整批注入） |
| **Plan mode** | D11 具体机制：plan agent 由 `PlanAgent.spawn` 以 `freezeExempt = true` 构造 → 其内部轮次不冻结（用户在场看规划面板）；主 agent 在 `planWaiting` 期间不 dispatch LLM；`PlanApproved`/`PlanFeedback` 后的 dispatch 是用户动作 → UserWake。ask 轮由 `askMode.isDefined` 豁免 |

---

## 6. 验收条件（全部二值可自动化）

### 6.1 冒烟测试（第一项，不过则全废）
```bash
# 1. 真实启动（带冻结时段配置：覆盖当前时刻的段，如现在是 11:30 → 配 [11:00-12:00]）
sbt run &
sleep 15
curl -sf http://localhost:8080/api/health                          # 200
curl -sf http://localhost:8080/ | grep -q '<div id="app"'          # 前端可达
# 2. Playwright（chromium）：
#    - 打开首页 → networkidle → 无 console error
#    - 设置面板 → 开启工作时间 → 配两段时段 → 保存 → 重开设置面板回显一致
#    - 发送带工具轮次的任务（如「读取 README 并总结」）→ 等 toolEnd 事件
#    - 把时段改为覆盖当前时刻的冻结段 (setWorkSchedule) → 观察 frozen 状态条出现
#    - 输入框发消息 → 不排队、立即出现 assistant 回复（唤醒成功）
#    - 截图 /tmp/freeze-smoke-{settings,frozen,wake}.png 人工复核视觉
```

### 6.2 后端验收
| # | 条件 | 验证 |
|---|------|------|
| B1 | 编译通过 | `sbt compile` exit 0 |
| B2 | 全量测试通过（含新增） | `sbt test` exit 0，新增 ≥25 用例（WorkScheduleSpec ≥12 + FreezeGateSpec ≥6 + 编译修复回归） |
| B3 | **零 token 硬指标** | FreezeGateSpec：冻结窗口内 ToolsComplete 后，mock LlmHandle 断言 `sendStream` 调用数 = 0；且 `UsageRecordStore` 无新记录 |
| B4 | gate 单点全覆盖 | FreezeGateSpec 用例覆盖：ToolsComplete 续轮 / finishTurnCont 续 turn / idle ExternalEvent 三条路径均被拦截（各 ≥1 用例） |
| B5 | 唤醒语义 | 用户 UserInput（clientMessageId=Some）→ dispatch 1 次且请求 messages 末条为用户消息、倒数含工具结果；系统 UserInput（None）→ pendingUserInputs 入队、零 dispatch |
| B6 | 自动恢复 | frozen + CheckFreezeGate（开窗）→ dispatch 1 次 + resumed 事件；FreezeScheduler 扫描 Frozen 记录送达 CheckFreezeGate |
| B7 | TaskStuckWatcher 豁免 | `TaskStuckWatcher.scan` 输入含 Frozen 记录（lastActivityMs 超 10min）→ 无 Stop/无 taskStuck 广播 |
| B8 | 配置 fail-safe | `FreezeSchedule.load`：JSON 非法/空段开启/坏时段（含 start==end）→ disabled（恒不冻结，功能关闭语义） |
| B9 | WS 协议 | `setWorkSchedule` 合法 payload → nebflow.json 顶层节写入且其余配置保留（ConfigServiceSpec 补 1 用例）；非法 payload（start≥end / 空 / 坏格式）→ 拒绝且配置不变 |
| B10 | 端到端（真实链路） | 集成测试：workable 窗口发任务（真实 ActorSystem + mock LLM）→ 中途 setWorkSchedule 关窗 → frozen 事件 → setWorkSchedule 开窗 → resumed + 后续 LLM 调用收到含工具结果的完整 messages |
| B11 | 交互豁免（D11） | FreezeGateSpec 补两用例：① `askMode` 轮在冻结窗口内 dispatch 不被拦截；② `freezeExempt = true` 的 agent 在冻结窗口内 dispatch 不被拦截（各 ≥1 次 sendStream 断言） |
| B12 | markTeamBusy 时序（v1.1 修正） | FreezeGateSpec：进入 frozen 的 team agent，`TeamSessionRegistry.isBusy` == true（防止 Teams 面板显示 idle） |

### 6.3 前端验收
| # | 条件 | 验证 |
|------|------|------|
| F1 | 静态资源 | `curl /js/sidebar.js`、`/js/main.js`、`/css/sidebar.css` 200 且内容非空 |
| F2 | 渲染 | Playwright：设置面板含「工作时间（冻结调度）」开关；开启后出现时段编辑器（默认 1 段 09:00-12:00） |
| F3 | 冻结状态条 | Playwright 模拟 frozen WS 事件（或后端真实触发）→ `.frozen-status` 可见且含恢复时间文本；spinner 隐藏 |
| F4 | 流超时抑制 | frozen 事件后等待 >streamTimeoutMs+30s（测试中注入缩短的超时）→ **无** timeout 错误卡、busy 不被清除 |
| F5 | 唤醒直发 | frozen 会话输入框发消息 → WS 帧立即发出（断言未经 queueMessage 排队）→ assistant 回复渲染 |
| F6 | 双主题 | 冻结状态条 + 设置时段编辑器在 light/dark 截图各一张，Read 人工复核 |
| F7 | i18n | 切中文/英文，设置区块与状态条文案正确 |

---

## 7. 风险与回滚

| 风险 | 等级 | 缓解 |
|------|------|------|
| gate 遗漏某条 dispatch 路径（如 planWaiting 内部）→ 冻结期间漏烧 token | 中 | B4 三路径 + 实现时对 `pipeLlmCall(` 全调用点 grep 复核（≤10 处）；漏网路径行为=现状（不冻结），不破坏正确性只损失省钱效果 |
| frozen behavior 遗漏某个消息类型 → 消息被吞 | 中 | catch-all 保持 frozen 态（不丢消息只延迟）；FreezeGateSpec 覆盖高频 6 类消息；code review 清单核对 processing 的 case 集 |
| 前端 frozen 态与 busy 态竞态（frozen/resumed/done 乱序） | 低 | 事件按 sessionId 串行处理；resumed 若在非 frozen 态到达 → no-op；done 到达 → 清 frozen 标记（兜底） |
| 长冻结（如跨夜）期间 WS 断连重连 | 低 | 重连后前端从 `serverConfig`+会话状态重建；frozen 标记丢失 → 兜底：sessionBusy=true 且无 spinner 时显示「等待中」；二期可加 `/api/agents` 状态查询 |
| 与 in-draft 的 interrupt-pending-mail-spec 冲突 | 低 | §5.4 标注；两 spec 实现时同步复核 |
| **已知无关问题（本方案不改，仅警示）**：`AgentActor.scala` L1135 `ctx.forkTurn(IO.sleep(...)) *> pipeLlmCall(...)` 的退避延迟实际不生效（forkTurn 立即返回，见 F4）——llm-fail 重试并无真实延迟。**实现冻结时严禁照抄此模式** | — | 建议顺手单独开任务修复（一行改动：把 sleep 放进 forkTurn 的 IO 内、dispatch 移入 sleep 之后） |

**回滚**：
- 软回滚（无需发版）：设置里关闭开关 → `freezeScheduleRef` 恒不冻结 → 所有 frozen agent 立即恢复，功能完全旁路（gate 只剩一次 ref 读，开销可忽略）。
- 硬回滚：revert 合并 commit。配置节 `workSchedule` 留在 json 中无害（旧代码不读）。
- 数据兼容：无 schema 迁移（新增节 + 新增枚举值，旧数据不受影响）。

---

## 8. 实施顺序建议（供 Manager 派发）

1. **P1 后端核心**：① FreezeSchedule 模型 + ② protocol 三处新增 + ③⑤ Scheduler + ⑥ gate/frozen behavior + ⑧ Defaults → FreezeGateSpec 全绿
2. **P2 配置链路**：⑦ WS 命令 + GatewayMain 初始化 + ConfigServiceSpec 用例
3. **P3 前端**：state/ws/main（事件+状态条+超时抑制）→ input（唤醒直发）→ sidebar（设置 UI）→ popup → i18n → css
4. **P4 验收**：冒烟 + Playwright F1-F7 + B10 端到端

预估：后端 ~500 行（含测试），前端 ~350 行。

---

*配置 schema 示例（nebflow.json 顶层）：*
```json
{
  "llm": { "...": "现有配置不动" },
  "workSchedule": {
    "enabled": true,
    "segments": [
      { "start": "09:00", "end": "12:00" },
      { "start": "14:00", "end": "18:00" }
    ]
  }
}
```
