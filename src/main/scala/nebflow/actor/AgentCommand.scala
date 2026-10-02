/* 从 protocol.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.actor

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.ActorRef
import nebflow.shared.{AskItem, ToolExecResult, *}

sealed trait AgentCommand

/** Restart level for supervisor-triggered agent restart. */
enum RestartLevel:
  case Soft, Rollback, Prune, Full

/**
 * 注入来源标注 —— blue injected bubble（浅蓝注入气泡）顶栏字段的**单点定义**
 * （bluebubble 批 2026-09-12）。
 *
 * 字段名与取值域**与前端判据同源**，两侧不得各写一套：
 *  - 后端载体：本 case class → [[AgentCommand.UserInput]] / [[AgentCommand.ImmediateInput]]
 *    的同名参数 → 唯一发射点 `AgentActor#emitInjectedUserEvent`（WS 帧
 *    `{type:"user", injected:true, source, sender, senderTeam, eventType, delivery}`）
 *    → 唯一落盘点（同方法内 `SessionStore.appendUiMessages`）→ `UiMessage.User`。
 *  - 前端消费：`web/js/persistence.js#isOutgoingInjection`（**判据**：`sender`
 *    与该会话自身 agent 名相等 ⇒ 本会话自己的外发，不渲染）＋
 *    `web/js/chat.js#injectedSourceLabel` / `#INJECTED_SOURCE_LABELS`（**呈现**：
 *    `SOURCE · AGENT · EVENT_TYPE`，Team 消息走 `team/agent`）。
 *    ⇒ `sender` 的取值域恒 = **发送方 agent 自身名**（与 `ownAgentName()` 同名空间）；
 *      `eventType` 取邮件类型(mailType.toLowerCase) / 终态码；`senderTeam` 取团队名。
 *
 * 空值语义：`None` = 该段不显示（向后兼容——既有调用点不传即保持既有呈现，
 * 前端旧历史行缺字段同样优雅降级）。
 */
case class InjectionAttribution(
  sender: Option[String] = None,
  senderTeam: Option[String] = None,
  eventType: Option[String] = None,
  /**
   * **收件判别字段**（mailbadge 批 2026-09-13，作者裁定「必须显示 MAIL」⇒ 选项 C）：
   * 本注入件是经**哪条收件通道**进来的，与 [[AgentCommand.UserInput.source]] 的
   * **会计语义分离**——`source` 恒为桥的消费计数口径（`"task"`，见
   * `ProjectActor.DispatcherInjectedSources`），本字段只承担**呈现判别**。
   *
   * 取值域 = [[InjectionAttribution.IntakeMarkers]]（后端唯一定名源）；前端
   * `web/js/chat.js#injectedSourceLabel` 用它**优先**取展示标签，缺席回落
   * `source`（回落路径逐字不变）。`None`（默认）= 无收件通道判别 ⇒ 呈现与
   * 改前逐字节一致（向后兼容：既有调用点与旧历史行零影响）。
   *
   * 两侧同源门 = `InjectionIntakeContractSpec`（置位侧 ↔ 帧字段 ↔ 标签优先级）。
   */
  intake: Option[String] = None,
  /**
   * **发送方所属项目**（「气泡四段式统一」批 2026-09-15，作者 12:33 令）：四段式
   * header `KIND · PROJECT · SUBJECT · STATE` 的第 2 段来源，取**发送方**（不是收件方）
   * 所属项目名。
   *
   * 取值链（禁臆造、禁静默填空；逐处落位见交付报告 §r3-2 与 R-A 补节）：
   *   ① 发送方项目 = 构造点的项目上下文（`MailTool.mailAttribution` / `MailTool.sendMail`
   *      取 `ToolContext.projectName`）——**已落位**（R-A 补 2026-09-15 ③）：Mail 三腿
   *      同源置位，故「发送方自带项目」的调用面（项目节点会话 / 分发器越面 `project:`
   *      调用 / 跨项目团队收件）在此取到发送方实际项目域；
   *   ② 未置位 ⇒ **按腿分派**：Mail 腿①（`Mail → project` 分发器收件面）由**发射面**
   *      `ProjectActor.leg1SenderProject` 取根域值（无项目上下文的发送方 =
   *      「跨 root 直投件」⇒ `NotificationHeader.RootProject`，**不**取收件方项目）；
   *      其余腿由发射点（`AgentActor#emitInjectedUserEvent`）用接收会话的
   *      `AgentState.projectName` 补 —— 发送方与接收方**同项目**时与本字段同值；
   *   ③ 仍取不到（跨 root 直投 / 根域注入）⇒ `NotificationHeader.RootProject`。
   *
   * NODE/CHAIN 腿**不依赖本字段**：其 `sender` 已按路径约定携带
   * `"<项目名>/<节点名|链id>"`，`NotificationHeader.header` 直接切分。
   * `None`（默认）= 构造点未置位 ⇒ 走 ②/③ 回落；既有调用点与旧历史行零影响。
   */
  project: Option[String] = None
)

object InjectionAttribution:

  /** 显式空标注（调用点表态用；等价于 Option 的 None）。 */
  val Empty: InjectionAttribution = InjectionAttribution()

  /**
   * 腿②（分发器 → `node:<id>`）的注入 source 定名（D-3 裁定：保持节点侧既有
   * 呈现，`mail` 只出现在真实邮件来源处）。
   */
  val SourceSystem: String = "system"

  /**
   * 收件通道判别定名（mailbadge 批 2026-09-13，选项 C）：`MailTool` 腿①
   * （`Mail(address="project:<name>")`）——本件是**收件方视角的 Mail**。
   * **只置位在腿①**：腿②（`node:<id>`，source 保持 `"system"` ⇒ 标签 `System`）
   * 与腿③（非 project 面，source 已是 `"mail"` ⇒ 标签 `Mail`）**不置位**——
   * 节点收件面不在本批（已单独立项），非 project 面呈现不得漂移。
   */
  val IntakeMail: String = "mail"

  /**
   * 后端**自定名**的收件通道取值域 —— 前端必须逐值显式登记（同
   * [[BackendNamedSources]] 的纪律：值取自 source 词表，故共用同一张
   * `INJECTED_SOURCE_LABELS` 表项即为显式登记）。契约门 =
   * `InjectionIntakeContractSpec`。
   */
  val IntakeMarkers: Set[String] = Set(IntakeMail)

  /**
   * 后端**自定名**（非用户可传）的 source 取值域 —— 前端
   * `web/js/chat.js#INJECTED_SOURCE_LABELS` 必须逐值**显式登记**（或由
   * `injectedSourceLabel` 的显式分支处理），**禁靠首字母大写兜底**。
   * 契约门 = `InjectionSourceContractSpec`（后端是唯一定名源，前端登记面
   * 落后于本集合即红）。
   *
   * 出处（逐个可溯源）：
   *   mail                MailTool#sendMail（腿③ / team 腿）
   *   task / dispatch     ProjectActor.SourceTask / SourceDispatch（腿①）
   *   system              NodeEngine#injectRunning（腿②，见 SourceSystem）
   *   node                NodeEngine#deliverToRoot（节点完成通报）
   *   skill               AgentActor SkillActivate 分支
   *   delegate / subtask / flow / tool   AgentIdle#inferInjectionSource（re-pin
   *                       2026-09-25：随 idle 态自 AgentActor 迁至 AgentIdle.scala）
   *   background          AgentActor#visibleExternalEventSource（源名 background-task）
   *   chain               **已退役（不再发射）**：原出处 = NodeEngine#deliverChainSummary
   *                       （链级聚合摘要投根，b64 批 2026-09-13）。全降级列表态批
   *                       （2026-09-16，作者裁定「全部降级列表态」）后链腿零投根
   *                       ⇒ 无发射点；本词表项保留**仅服务存量历史行**的渲染
   *                       （宿主 sessions 面现取 ≈49 处 source="chain" 落盘，条数随轮转漂移），
   *                       与前端 `INJECTED_SOURCE_LABELS.chain` / NotificationHeader
   *                       `KindLabels("chain")` 三处同源保留（删源会连带改词表 pin，属
   *                       本批未取侧）。
   *   deviceMail          跨设备 Nebula 邮件收件腿（device-mail 批 2026-09-15）：
   *                       对端设备经 NebLink 设备通道送来 `agent_mail` 载荷
   *                       （`DeviceMail.SourceDeviceMail`，注入点 = DeviceMailInbox）
   *                       ⇒ 注入本机 Nebula 会话的蓝色气泡（标签 = i18n
   *                       「来自 <from_device> 的 Nebula」，前端走显式分支，
   *                       同 `node` 先例）
   * 例外：`eventType=="inject"` 的 API 注入 source 由调用方提供（用户域），
   * 不受本集合约束 —— 前端对其走既有兜底分支。
   */
  val BackendNamedSources: Set[String] =
    Set(
      "mail",
      "task",
      "dispatch",
      "system",
      "node",
      "skill",
      "delegate",
      "subtask",
      "flow",
      "tool",
      "background",
      "chain",
      "deviceMail"
    )

end InjectionAttribution

/**
 * AskUserQuestion 的作答来源定名（本批收敛后**仅存这一处**）。
 *
 * 历史形态（工具面按角色分化批 B4，2026-09-13 作者裁定 T2=(a)）是 `Blocking |
 * NonBlocking` 双模式枚举；本批（root 2026-10-02 令 #462 裁②+①）收敛为
 * **非阻塞-only** ⇒ `AskMode` 枚举、`BlockingWire` / `NonBlockingWire`、
 * `parse`、`parksTurn` 随阻塞腿一并移除（`rootVariant` 失去目标、AgentCore 的
 * AskUser 变体分支整体删除）。
 *
 * 保留本对象的理由 = **不切断答案桥链**：`AskUserAnswerBridge.deliver` 把答复
 * 作为注入用户输入投达会话本体时，用本常量作 `ImmediateInput.source` /
 * `eventType`；`AgentActor.injectionSourceFor` 在 `fromUser=true` 时把 source 折成
 * `None`（呈现为普通 user 气泡）⇒ 本常量**不承担前端登记面**，只作后端归因口径。
 */
object AskUserAnswerSource:
  /**
   * 非阻塞答复的注入来源定名——真人点卡作答，故与 `fromUser=true` 同行。
   */
  val AnswerSource = "askUserAnswer"
end AskUserAnswerSource

object AgentCommand:

  case class UserInput(
    text: String,
    replyTo: Option[ActorRef[AgentEvent]] = None,
    clientMessageId: Option[String] = None,
    blocks: Option[List[ContentBlock]] = None,
    chatWidth: Int = 0,
    /**
     * Injection source marker (任务 P): Some(...) when this input was injected
     * by a tool (Mail/Delegate/SubTask/skill/ask) rather than typed by the
     * user. User WS inputs always carry clientMessageId and source=None.
     */
    source: Option[String] = None,
    /** Sender agent name for Mail-delivered messages (shown as attribution label). */
    sender: Option[String] = None,
    /** Team name of the sender for Mail-delivered messages (shown as attribution label). */
    senderTeam: Option[String] = None,
    /** Delivery mode marker: "queue" | "immediate" for Mail-injected inputs. */
    delivery: Option[String] = None,
    /**
     * Structured event type (e.g. completion status) for the UI source label —
     *  carried through from ImmediateInput so flow results render
     *  'Flow · <name> · Completed/Failed' instead of a bare 'Flow'.
     */
    eventType: Option[String] = None,
    /**
     * 收件通道判别（mailbadge 批 2026-09-13，选项 C）：见
     * [[InjectionAttribution.intake]]。**只影响呈现判别**——`source` 仍是桥的
     * 消费计数口径（`"task"`），本字段不参与任何会计/去重/生命周期判定。
     * 默认 `None` ⇒ 既有调用点与旧历史行呈现逐字不变。
     */
    intake: Option[String] = None,
    /**
     * ② (2026-09-11, queue-direct-pass diagnosis §2): real-user origin flag.
     * True only when this UserInput was born from a real human message
     * (WS direct send, or an ImmediateInput forwarded from one — see
     * [[ImmediateInput.fromUser]]). The idle judgement reads it as
     * `clientMessageId.isDefined || fromUser ⇒ source=None`, so a real-user
     * text that reached the agent through the ImmediateInput leg is never
     * mislabelled with an injection source. Server-side injections keep the
     * default false and say so explicitly at the call site.
     */
    fromUser: Boolean = false,
    /**
     * **发送方所属项目**（「气泡四段式统一」批 2026-09-15）：见
     * [[InjectionAttribution.project]] 的取值链。默认 `None` ⇒ 发射点走回落链，
     * 既有调用点零影响。
     *
     * **位置刻意置末**（`fromUser` 之后）：本仓存在**位置参数**构造点
     * （`AgentActor` 冻结腿的 `pendingUserInputs` 追加），插在中间会把旧实参
     * 错位到本字段 ⇒ 置末使既有位置调用逐字保持可编译。
     */
    project: Option[String] = None
  ) extends AgentCommand

  /** 窗冲刷载荷的单件（`ImmediateInput.windowItems` 的元素）。字段与既有
    * `RootNotifyEntry`（`NodeEngine.scala:6247`）同源 ⇒ 合并腿构造时直接映射、
    * 零二次转换；`nodeName`/`status` 即逐件气泡的身份与状态段来源，`sender` = 逐件
    * 完整身份 `"<项目名>/<节点名>"`（**写入点**用 `projectName` 构造，展开点零字符串
    * 手术 ⇒ 与 `NotificationHeader.split` 的「首个 `/`」切分口径结构同源）。 */
  case class WindowItem(text: String, nodeName: String, status: String, sender: String)

  case class ImmediateInput(
    text: String,
    blocks: Option[List[ContentBlock]] = None,
    /** Injection source marker (任务 P), e.g. "mail" for Mail delivery. */
    source: Option[String] = None,
    /** Structured event type (e.g. mail type, completion status) for the UI source label. */
    eventType: Option[String] = None,
    /** Sender agent name for Mail-delivered messages (shown as attribution label). */
    sender: Option[String] = None,
    /** Team name of the sender for Mail-delivered messages. */
    senderTeam: Option[String] = None,
    /** Delivery mode marker: "queue" | "immediate" for Mail delivery. */
    delivery: Option[String] = None,
    /**
     * ② (2026-09-11, queue-direct-pass diagnosis §2 根因): real-user origin
     * flag. The ImmediateInput leg carries NO clientMessageId by construction,
     * which used to make a real human text fall into the
     * `no clientMessageId ⇒ tool injection` fallback (AgentActor.idle) —
     * rendering a bogus blue `source:"tool"` card and dropping the turn out of
     * `isRealUserTurn` (time/task reminders degraded).
     *
     *   true  → WS `immediateInput` frame (user clicked send), CLI
     *           `userMessage` frame  [真人 = 客户端直接投递]
     *   false → every server-side injection: Mail / Delegate / SubTask / Flow /
     *           Node / Dispatcher / Schedule / system, AND the REST headless
     *           turn (`rest-turn` = a program, see dispatchHeadlessTurn)
     *
     * Kept as an explicit, defaulted field (not inferred) so every construction
     * site states its origin; the compiler + the explicit-argument discipline
     * keep the two families apart.
     */
    fromUser: Boolean = false,
    /**
     * **发送方所属项目**（「气泡四段式统一」批 2026-09-15）：见
     * [[InjectionAttribution.project]] 的取值链。`None` ⇒ 发射点走 ②/③ 回落。
     *
     * **位置刻意置末**（`fromUser` 之后）：本仓存在**位置参数**构造点
     * （`CompactionQueueStore:103`），插在中间会把 `fromUser` 实参错位到本字段
     * ⇒ 置末使既有位置调用逐字保持可编译。
     */
    project: Option[String] = None,
    /** **通知窗冲刷载荷（notifypack 解 b 批 · 作者裁定 A · 载体 A-ii）**：
      * root 通道通知打包窗冲刷件在**队列里是 1 个元素**（生产者侧已合并，见
      * `NodeEngine.flushRootNotify` 的 `case many`），本字段承载其 N 件原始载荷 ⇒
      * 消费侧可在同一边界（同一 turn）**逐件**展开注入（N 气泡 / 1 次唤醒）。
      *
      * **取值域**：`None`（默认；全部非窗腿与单件腿）｜`Some(items)`（仅 N≥2 的窗冲刷件）。
      * **写入点（唯一）**：`NodeEngine.flushRootNotify` 的 `case many` 分支
      *   （现读 `NodeEngine.scala:6349`）——该分支现读是 N≥2 的唯一合并出口。
      * **读取点（判据唯一）**：`TurnBoundaryDrains.expandWindowFlush`（`AgentActor:109`）
      *   ——它是谓词与幂等性的**唯一定义处**；接线点共 **6** 处，全部经它，无一自造谓词：
      *   三个 turn 边界（`AgentActor:1884` / `:2191` / `:3510`）、压缩后恢复
      *   （`AgentActor:504`，经 `expandRootNotify` 单点绑定 `:444`）、以及 **idle 直投腿**
      *   （`AgentActor:1497`——节点终态通知在上一个 turn 结束后才到 = 本链的生产常态
      *   到达路径）。
      * **语义边界**：本字段**零会计/零去重/零生命周期**参与面——`text` / `sender` /
      *   `eventType` / 60s 去重键 / `markNebulaDelivered` 记账序**全部逐字不变**；
      *   本字段只决定「消费侧把这一件展开成几条」。
      *
      * **位置刻意置末**（`project` 之后 · 与 `project` 自身的置末纪律同源）：本仓存在
      *   **位置实参**构造点（`CompactionQueueStore:103`）与两处**位置模式解构点**
      *   （`AgentActor:1497`、`DeviceMailSpec:410`）。置末使位置实参构造点逐字保持可
      *   编译；两处解构点须各追加一个 `_` 槽位（否则 arity 不匹配编译失败——即
      *   「向后兼容」的机械判据：编译器保证无一处被静默漏改）。 */
    windowItems: Option[List[WindowItem]] = None
  ) extends AgentCommand

  case class Interrupt() extends AgentCommand

  case class AskUser(
    requestId: String,
    items: List[AskItem],
    replyTo: Option[ActorRef[List[String]]] = None,
    /**
     * 附件引用（本批：AskUser 顶层可选参数 `attachments`）。路径已在工具调用期经
     * `FilePolicyPort` 校验通过；此处只搬运到 `buildAskUserJson` 的顶层键。
     * 缺省 `Nil` ⇒ 两个既有构造点（`ProjectCreateTool.pathPanel` / `SendConfirm.ask`）
     * 不传即载荷**逐字节不变**。
     *
     * 🔴 `mode` 形参已随「非阻塞-only」裁定移除（root 2026-10-02 令 #462 裁①/②）：
     * 提问一律非阻塞，`AgentProcessing` 的等待态标注随之消失。
     */
    attachments: List[String] = Nil,
    /**
     * 本**次派发**是否有等待答复的调用方（= 该调用方在自己的 fiber 里 `.?` 阻塞、
     * 需要答复作为**返回值**）。🔴 与已移除的 `AskMode` 枚举**不是**同一回事：
     * 那是 `AskUserQuestion` 的**工具面模式**（本批裁① 全量非阻塞，已删除）；
     * 这是**命令面**的客观事实——`AskUserQuestionTool` 一律非阻塞 ⇒ 恒 `false`；
     * 仍在阻塞等待的两个**既有构造点**（`ProjectCreateTool.pathPanel` 与
     * `SendConfirm`）中，走本命令的那个必须置 `true`，否则 `AgentProcessing` 不标
     * `WaitingForUser`（`TaskStuckWatcher` 会把「等人选路径」误判为真卡死并硬取消）
     * 且不暂停内核 wall-clock 预算（等人在 R11 第 4 层口径下不计入预算）。
     *
     * 缺省 `false` ⇒ AskUserQuestion 链与 SendConfirm 链均不受影响。
     */
    awaitsAnswer: Boolean = false
  ) extends AgentCommand

  case class LlmComplete(
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]],
    turnId: Long
  ) extends AgentCommand

  case class LlmFailed(
    error: Throwable,
    replyTo: Option[ActorRef[AgentEvent]],
    turnId: Long,
    /**
     * Block 3：LoopGuard L1 终止路径的计数器快照（terminatedFps 随 state 存活，
     * 后续 turn 同 fp 复发 → 直接 L2 冻结）。该路径不投 ToolsComplete——
     * LlmFailed 本身是计数器写回的唯一载体。其余调用方默认 None。
     */
    loopCounters: Option[nebflow.shared.Counters] = None
  ) extends AgentCommand

  case class SetPermissionDeferred(deferred: cats.effect.Deferred[IO, Boolean]) extends AgentCommand

  case class ToolsComplete(
    results: List[(ToolCall, ToolExecResult)],
    originalText: String,
    replyTo: Option[ActorRef[AgentEvent]],
    compactedMessages: Option[List[Message]] = None,
    thinking: Option[String] = None,
    thinkingSignature: Option[String] = None,
    /**
     * Block 3 循环检测器 L0（supervision trio §D3）：LoopGuard Warn 提醒——
     * 下一轮以 user system-reminder 消息注入，
     * 零成本给模型自纠机会。
     */
    loopReminder: Option[String] = None,
    /**
     * Block 3：本轮 evaluate 产出的计数器快照——pipeToolExecutions 的计数器在
     * 异步 IO 内计算，行为返回时不可见；经消息携带由 ToolsComplete handler 写回
     * state（S1/S2 跨轮、S3 跨 turn 持久化的载体）。None=非 loop-guard 路径。
     */
    loopCounters: Option[nebflow.shared.Counters] = None,
    /**
     * Block 3 L2：Some(detail) 时 handler 完成消息组装/持久化/计数器写回后
     * 不续轮（不 pipeLlmCall），转而冻结（loopDetected 广播 + 父通知 +
     * enterFrozen(Loop)）。此前用独立 LoopFreezeDetected 消息实现——但
     * ToolsComplete 链式 dispatch 会递增 currentTurnId，后续消息按 stale
     * 丢弃，冻结永不落地（wiring 实证）。
     */
    freezeAfter: Option[String] = None
  ) extends AgentCommand

  case class CompactionComplete(result: Either[String, List[Message]]) extends AgentCommand

  /**
   * F2 (2026-08-30, compact-injection-shield batch 2): sent to self at spawn
   * (before any external delivery) — load the persisted injection queues
   * (CompactionQueueStore) into the execution context. A crash mid-compaction
   * otherwise loses every ImmediateInput/ExternalEvent queued during the
   * window; replay restores them so the next turn injects them.
   */
  case object RecoverPersistedQueues extends AgentCommand

  case class TriggerCompaction(
    mode: String,
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None,
    postCompactInstruction: Option[String] = None
  ) extends AgentCommand

  case class Retry(reason: String) extends AgentCommand

  case class AskQuestion(question: String, sessionId: String) extends AgentCommand

  case class SkillActivate(
    skillName: String,
    input: String,
    sessionId: String,
    skillContent: String,
    skillBaseDir: String
  ) extends AgentCommand

  /**
   * FreezeScheduler（30s 轮询）/ setWorkSchedule（配置热更）→ frozen agent：
   * 重评估冻结时间表（#337 黑名单语义）——已出冻结段则恢复挂起的 dispatch，
   * 仍在冻结段内则更新 resumeAt 留任。到达非 frozen behavior 时 no-op（幂等，无需去重）。
   */
  case object CheckFreezeGate extends AgentCommand

  case class UpdateContextWindow(window: Int) extends AgentCommand

  /**
   * ctxthresh 批（2026-09-15 方案 A，作者卡答「按方案A实施」）：**本会话**的
   * 压缩阈值比例覆盖热更——`Some(r)` = 会话级覆盖，`None` = 清除覆盖（回现值
   * 函数）。投递口唯一 = WS `setCompactThreshold`（`ensureAgent(sessionId)`，即
   * **root 会话**）；非 root spawn 路径既不投本命令也不注入本字段（口径③）。
   *
   * 🔴 **刻意不复用 [[UpdateContextWindow]]**（设计 §7 逐字禁止）：那条命令在
   * `estimated > threshold` 时会顺手触发一次 full 压缩（`AgentActor` 的
   * `model-switch-compact` 分支）——用来调阈值会造成不可预期的压缩调用。
   * 本命令只改存储值：判定点在**下一个回合边界**按新值评估（`AgentCore` 的
   * auto-compact 判定），无副作用。
   */
  case class SetCompactThresholdRatio(ratio: Option[Double]) extends AgentCommand

  // 2026-09-13（permshield S1）：`SetSafetyMode` 命令**已退役** —— 档位是应用级
  // 持久值（`nebflow.json` 的 `safety.defaultMode`），写入口只有两条（WS
  // `setSafetyMode` 帧 / REST `PUT /api/safety/mode`），二者都直接落盘并热读，
  // 无需（也无处）通知某个活的 agent 改本地副本。删除它也一并消除了三个 actor
  // 状态分支里的"本会话覆盖"写入点。

  case class ResumeTurn(
    turnStartMessageCount: Int,
    turnIdx: Int
  ) extends AgentCommand

  /**
   * Stop a session (kill shell processes, fire lifecycle hooks, stop the actor).
   *
   * == Deferred-cancel variant (`Stop()`) — eng-deferred-cancel batch, 2026-10-02 ==
   *
   * `Stop(reason)` keeps the historical semantics byte-for-byte: the tool batch
   * in flight is torn down immediately (`cancelCurrentTurn`) and the actor stops
   * without persisting the partial turn.
   *
   * `Stop()` is the **deferred-cancel** variant (K-1): when the session is
   * currently executing a tool batch, the stop intent is recorded
   * ([[nebflow.actor.ExecutionContext.stopDeferred]]) and honoured only at the
   * tool-batch boundary — the batch's tool results are persisted to the session
   * transcript FIRST (K-2: produced artifacts are preserved, in the same
   * assistant `tool_use` / `tool_result` pair shape the normal continuation
   * writes), and the actor then stops without dispatching a new LLM turn (no
   * new tool call after cancellation takes effect).
   *
   * It is a **separate arity overload** (not a boolean field) on purpose: every
   * pre-existing call site and every positional pattern match keeps compiling
   * and behaving exactly as before (zero churn at the 20-odd Stop sites plus
   * the test anchors `AgentControlToolSpec` / `EphemeralBridgeWatchSpec` /
   * `StopHangTurnSpec`). A boolean field would have broken arity-based
   * pattern matches and silently changed nothing else.
   *
   * Not deferred-when-idle by design: with no tool batch in flight there is
   * nothing in flight to preserve, so `Stop()` falls back to the historical
   * immediate path (read at the point of receipt, not at construction time).
   */
  case class Stop(reason: String) extends AgentCommand

  object Stop:
    /**
     * Deferred-cancel stop (chain cancel: see `NodeStarter.runWithAgent`).
     * Reason text is fixed: it never reaches a user-visible surface (the chain
     * cancel reason is rendered into the node `result` by `NodeCompletion`).
     */
    def apply(): Stop = Stop(DeferredReason)

    /**
     * Deferred-cancel flag accessor. A dedicated overload beats a `deferred`
     * field: the field form would have to squeeze between `reason` and every
     * existing positional/interpolated construction, and this batch's rule is
     * that pre-existing call sites stay byte-identical.
     *
     * `true` iff the reason is exactly the deferred-cancel marker reason — the
     * marker is produced by [[apply]] alone, so a caller-supplied reason can
     * never be mistaken for a deferred stop.
     */
    extension (s: Stop) def deferred: Boolean = s.reason == DeferredReason

    /** Marker reason of the deferred-cancel variant (`Stop()`). */
    val DeferredReason: String = "Node cancelled (deferred)"

  case object ClearReadTracker extends AgentCommand
  case object ResetSession extends AgentCommand

  case class UpdateGitBranch(branch: Option[String]) extends AgentCommand

  /**
   * Supervisor-triggered restart. Levels:
   *  - Soft: cancel current work, re-dispatch LLM call (same messages)
   *  - Rollback: truncate last tool call pair, inject error, re-dispatch
   *  - Prune: (future) context prune + restart
   *  - Full: (future) reset to empty, reload from persisted history
   */
  case class RestartAgent(level: RestartLevel) extends AgentCommand

  case class BackgroundTaskNotification(
    taskId: String,
    description: String,
    status: String,
    output: String,
    exitCode: Option[Int] = None
  ) extends AgentCommand:

    def toExternalEvent: ExternalEvent = ExternalEvent(
      source = "background-task",
      eventType = status,
      payload = status match
        case "completed" =>
          val exitInfo = exitCode.filter(_ != 0).map(c => s" (exit code $c)").getOrElse("")
          s"\"$description\"$exitInfo:\n$output"
        case "failed" => s"\"$description\":\n$output"
        case _ => s"\"$description\"",
      metadata = JsonObject(
        "taskId" -> taskId.asJson,
        "description" -> description.asJson,
        "status" -> status.asJson,
        "output" -> output.asJson,
        "exitCode" -> exitCode.asJson
      ),
      correlationId = Option(taskId).filter(_.nonEmpty)
    )
  end BackgroundTaskNotification

  case class ExternalEvent(
    source: String,
    eventType: String,
    payload: String,
    metadata: JsonObject = JsonObject.empty,
    correlationId: Option[String] = None
  ) extends AgentCommand

  case class SessionStarted(
    address: String,
    agentName: String,
    taskDescription: String
  ) extends AgentCommand

  case class SessionClosed(address: String) extends AgentCommand
  case class SessionUpdate(address: String, status: String) extends AgentCommand

  /** A queued mail has arrived — drain it (idle) or increment the count (processing). */
  case class MailQueued(
    item: nebflow.shared.MailQueueItem,
    fromSessionId: String
  ) extends AgentCommand

  /**
   * v2 冻结式错误恢复升级链（§5.2）：FreezeScheduler.scan 发现 escalation 超时
   * 后发送——frozen behavior 中执行 escalate 动作（level+1，通知上一级/用户终态）。
   * 消息幂等：非 frozen/无 escalation 时 no-op。
   */
  case object Escalate extends AgentCommand
end AgentCommand
