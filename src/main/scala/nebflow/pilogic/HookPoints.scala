package nebflow.pilogic

import io.circe.Json

/** hook 身份：注册表分配，`without` 按它注销。 */
final case class HookId(value: String) extends AnyVal

/** 四元组进类型（设计 §Ⅲ.1）：⟨改参, 改果, 否决, 返值被读⟩——每位点定义携带，缺项拒装。
  * 机械校验（验收 A-7）：returnRead=true ⇒ 只接受 Interceptor；false ⇒ 只接受 Observer——
  * 类型上不存在「返回了但没人读」的通道（`runner.ts:1290` 返值被忽略陷阱的类型级防重演）。 */
final case class HookCapability(
    mutateParam: Boolean,
    mutateResult: Boolean,
    veto: Boolean,
    returnRead: Boolean,
)

object HookCapability:
  /** 观察位四元组 ⟨✗,✗,✗,✗⟩。 */
  val observe: HookCapability = HookCapability(false, false, false, false)

/** 聚合档（设计 §Ⅲ.3 四档；pi 第五档「不 await」不设——F[_] 内一切皆可 await，
  * 无 fire-and-forget 静默腿；需异步旁路者自行 fork 并自担隔离）。
  * 聚合档写进位点定义（不是调用方参数）——单一点位单一定义。 */
enum Aggregate:
  /** 全跑丢弃（观察族缺省）。 */
  case AllRun
  /** 链式改写：后者见前者结果（context/message.Ended/tool.Post 族）。 */
  case Chain
  /** 首个满足即短路（block/cancel/handled/onYield 族；Tool.Pre 首个 Block 即止）。 */
  case FirstWins
  /** 后者胜（providerPort.Payload/Headers、cache warming 族；Proceed=弃权）。 */
  case LastWins

/** 错误策略（设计 §Ⅲ.4）：缺省档随位点固定；注册方可收紧不可放宽（severity: Continue<Block<FailRun）。
  * 隔离先例（亲读）：core/hooks/HookRunner.scala:40-52 双臂 recoverWith——continueOnError=true ⇒
  * WARN + 继续上下文；false ⇒ block。本模块把双臂策略化为位点级三档。 */
enum HookErrorPolicy:
  /** HookErrored 事件 + 跳过该 hook，run 继续（观察族 & 改写族缺省）。 */
  case Continue
  /** 抛错=拒绝该操作不回落（fail-closed；Tool.Pre 缺省，对位 pi tool_call）。 */
  case Block
  /** 整趟失败（RunGate 缺省，对位 pi before_drive hooks.ts:94）。 */
  case FailRun

object HookErrorPolicy:
  private[pilogic] def severity(p: HookErrorPolicy): Int = p match
    case Continue => 0
    case Block    => 1
    case FailRun  => 2

/** 拦截族效果（§Ⅲ.1）：dispatch 单点必被消费。位点入参/改写值本轮以 Json 承载——
  * 位点专属 Event/Value 类型（P#Event 形）属 16 签名全量增量轮。 */
sealed trait HookEffect extends Product with Serializable
object HookEffect:
  /** 弃权/放行。 */
  case object Proceed extends HookEffect
  /** 改写值（Chain 链式传递 / LastWins 后者胜）。 */
  final case class Modify(newValue: Json) extends HookEffect
  /** 否决（Session.* 预留位首个 Veto 即止）。 */
  final case class Veto(reason: String) extends HookEffect
  /** 阻断该操作（Tool.Pre fail-closed；terminate=工具批早停语义由循环层解释）。 */
  final case class Block(reason: String, terminate: Boolean) extends HookEffect
  /** 注入消息（preRun.Inject 族）。 */
  final case class Inject(messages: Vector[LoopMessage]) extends HookEffect
  /** 强制续轮（postRun.Settle 族）。 */
  case object ContinueRun extends HookEffect
  /** 强制收轮（TurnEnded 位点的显式 End 优先）。 */
  case object EndRun extends HookEffect

/** 拦截族接口：返回 HookEffect，引擎【必须】读——dispatch 单点是唯一 match，无忽略通道。 */
trait Interceptor[F[_]]:
  def handle(input: Json): F[HookEffect]

/** 观察族接口：返回 F[Unit]——类型上就没有可被忽略的返值。 */
trait Observer[F[_]]:
  def observe(input: Json): F[Unit]

/** 位点分类学（设计 §Ⅲ.1 采纳族 + §Ⅲ.6 Reserved 预留族；L-7 采纳「类型层 sealed children」——
  * 编译期可见防漂移，Reserved 位点零运行时成本）。
  * Reserved 族：dispatch 支持其类型的分派，循环内无触发点——是「格子」不是「功能」。 */
sealed trait HookPoint extends Product with Serializable:
  /** 四元组（⟨改参, 改果, 否决, 返值被读⟩）。 */
  def capability: HookCapability
  /** 聚合档。 */
  def aggregate: Aggregate
  /** 缺省错误策略。 */
  def defaultErrorPolicy: HookErrorPolicy
  /** 对位命名（设计 §Ⅲ.1 / pi 原名）。 */
  def display: String

// ── 采纳族（§Ⅰ 决定列 29 位点；四元组逐条取自 §Ⅰ 对拍表） ─────────────────────

object HookPoint:

  /** #42 before_drive：名义四元组全 ✗（fail-closed 属错误通道），此处策略化为 veto 位。 */
  case object RunGate extends HookPoint:
    val capability = HookCapability(mutateParam = false, mutateResult = false, veto = true, returnRead = true)
    val aggregate = Aggregate.FirstWins
    val defaultErrorPolicy = HookErrorPolicy.FailRun
    val display = "run.Gate"

  /** #19 before_agent_start ⟨✓,✓,✗,✓⟩。 */
  case object PreRunStart extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = true, veto = false, returnRead = true)
    val aggregate = Aggregate.Chain
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "preRun.Start"

  /** #41 before_run：注入 messages，append 链。 */
  case object PreRunInject extends HookPoint:
    val capability = HookCapability(mutateParam = false, mutateResult = true, veto = false, returnRead = true)
    val aggregate = Aggregate.Chain
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "preRun.Inject"

  /** #13/#44/#53 context 族：消息改写链式（本设计统一覆盖结构性请求，写明与 pi #44 的差异）。 */
  case object PreRequestContext extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = false, returnRead = true)
    val aggregate = Aggregate.Chain
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "preRequest.Context"

  /** #14/#52（#52 的工具集覆盖归改果，接线期细化）。 */
  case object PreRequestSystem extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = false, returnRead = true)
    val aggregate = Aggregate.Chain
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "preRequest.System"

  /** #45 before_request：patch 与 replace 显式分档（对位 triple「更强」判）。 */
  case object PreRequestOptions extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = false, returnRead = true)
    val aggregate = Aggregate.Chain
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "preRequest.Options"

  /** #15/#46 payload 组装后整块替换。 */
  case object ProviderPayload extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = false, returnRead = true)
    val aggregate = Aggregate.LastWins
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "providerPort.Payload"

  /** #16 before_provider_headers 改造：设为 Interceptor、返值必读——不许复制「返值被忽略」陷阱。 */
  case object ProviderHeaders extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = false, returnRead = true)
    val aggregate = Aggregate.LastWins
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "providerPort.Headers"

  /** #17/#54 收到响应后消费前，只读。 */
  case object ProviderResponded extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "providerPort.Responded"

  /** #18 provider_stream_event：逐流事件观察（背压档 L-11 分位点声明属下一轮）。 */
  case object ProviderChunked extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "providerPort.Chunked"

  /** #28 message_start。 */
  case object MessageStarted extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "message.Started"

  /** #29 message_update。 */
  case object MessageDelta extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "message.Delta"

  /** #30/#47 message_end 替换（role 约束强制——pi 两实现不一致处不照抄）。 */
  case object MessageEnded extends HookPoint:
    val capability = HookCapability(mutateParam = false, mutateResult = true, veto = false, returnRead = true)
    val aggregate = Aggregate.Chain
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "message.Ended"

  /** #36/#48/#56/#68 tool_call/before_tool/beforeToolCall/beforeToolCall 合并单一 ToolPre
    * （改参重校验开关 L-5=位点级常量，两实现语义相反处钉死）；fail-closed 缺省照抄。 */
  case object ToolPre extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = true, returnRead = true)
    val aggregate = Aggregate.FirstWins
    val defaultErrorPolicy = HookErrorPolicy.Block
    val display = "tool.Pre"

  /** #37/#49/#57/#69 tool_result/after_tool/afterTool/afterToolCall 合并单一 ToolPost。 */
  case object ToolPost extends HookPoint:
    val capability = HookCapability(mutateParam = false, mutateResult = true, veto = false, returnRead = true)
    val aggregate = Aggregate.Chain
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "tool.Post"

  /** #58 afterTools：一批工具结果落地后，观察者。 */
  case object ToolBatchSettled extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "tool.BatchSettled"

  /** #31 tool_execution_start。 */
  case object ToolExecutionStarted extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "toolExecution.Started"

  /** #32 tool_execution_update。 */
  case object ToolExecutionDelta extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "toolExecution.Delta"

  /** #33 tool_execution_end。 */
  case object ToolExecutionEnded extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "toolExecution.Ended"

  /** #26 turn_start。 */
  case object TurnStarted extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "turn.Started"

  /** #27 turn_end：entries + continue；上游 End 优先于 Continue（pi agent-session.ts:684-685 同款）。 */
  case object TurnEnded extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = true, returnRead = true)
    val aggregate = Aggregate.FirstWins
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "turn.Ended"

  /** #22/#55 agent_before_settle/onYield 合并（聚合取 FirstWins）：缺它扩展无法让 agent 再跑一轮。 */
  case object PostRunSettle extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = true, returnRead = true)
    val aggregate = Aggregate.FirstWins
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "postRun.Settle"

  /** #23 agent_settled：整 run 最末终局通知。 */
  case object PostRunSettled extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "postRun.Settled"

  /** #43 before_run_end：followUp 合成 user 续轮，last-wins。 */
  case object PostRunFollowUp extends HookPoint:
    val capability = HookCapability(mutateParam = false, mutateResult = true, veto = false, returnRead = true)
    val aggregate = Aggregate.LastWins
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "postRun.FollowUp"

  /** #3 session_start。 */
  case object SessionStarted extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "session.Started"

  /** #10 session_shutdown：观而返值被读（返 true 供跳重复拆除）⇒ Interceptor 槽。 */
  case object SessionEnding extends HookPoint:
    val capability = HookCapability(mutateParam = false, mutateResult = false, veto = false, returnRead = true)
    val aggregate = Aggregate.FirstWins
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "session.Ending"

  /** #34 model_select。 */
  case object ModelSelected extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "model.Selected"

  /** #35 thinking_level_select。 */
  case object ThinkingLevelSelected extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "thinkingLevel.Selected"

  /** #8 session_compact：压缩落库后，观察级采纳（G-2 族内唯一已采纳位）。 */
  case object CompactEnded extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    val display = "compact.Ended"

end HookPoint

/** 预留族标记（G-1…G-5；对应 triple §9 P-7…P-10 待决项——作者裁前只留形状不造功能）。 */
sealed trait ReservedPoint extends HookPoint

object Reserved:

  /** G-1 会话操作面（P-7；#5/#6/#11/#12）。 */
  sealed trait SessionOps extends ReservedPoint
  object SessionOps:
    case object BeforeSwitch extends SessionOps:
      val capability = HookCapability(mutateParam = false, mutateResult = false, veto = true, returnRead = true)
      val aggregate = Aggregate.FirstWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "session_before_switch"
    case object BeforeFork extends SessionOps:
      val capability = HookCapability(mutateParam = false, mutateResult = false, veto = true, returnRead = true)
      val aggregate = Aggregate.FirstWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "session_before_fork"
    case object BeforeTree extends SessionOps:
      val capability = HookCapability(mutateParam = false, mutateResult = true, veto = true, returnRead = true)
      val aggregate = Aggregate.FirstWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "session_before_tree"
    case object TreeEnded extends SessionOps:
      val capability = HookCapability.observe
      val aggregate = Aggregate.AllRun
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "session_tree"

  /** G-2 历史压缩面（P-8；#7/#9/#50/#59 预留；#8 观察级已采纳为 CompactEnded）。 */
  sealed trait Compact extends ReservedPoint
  object Compact:
    case object BeforeCompact extends Compact:
      val capability = HookCapability(mutateParam = false, mutateResult = true, veto = true, returnRead = true)
      val aggregate = Aggregate.FirstWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "session_before_compact"
    case object CompactFailed extends Compact:
      val capability = HookCapability.observe
      val aggregate = Aggregate.AllRun
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "session_compact_failed"
    case object BeforeCompaction extends Compact:
      val capability = HookCapability(mutateParam = false, mutateResult = true, veto = true, returnRead = true)
      val aggregate = Aggregate.FirstWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "before_compaction"
    case object BeforeCollapse extends Compact:
      val capability = HookCapability(mutateParam = false, mutateResult = true, veto = true, returnRead = true)
      val aggregate = Aggregate.FirstWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "beforeCollapse"

  /** G-3 人面输入腿（P-9；#39/#38/#24/#25）。 */
  sealed trait HumanInput extends ReservedPoint
  object HumanInput:
    case object Input extends HumanInput:
      val capability = HookCapability(mutateParam = true, mutateResult = false, veto = true, returnRead = true)
      val aggregate = Aggregate.Chain
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "input"
    case object UserBash extends HumanInput:
      val capability = HookCapability(mutateParam = false, mutateResult = true, veto = true, returnRead = true)
      val aggregate = Aggregate.FirstWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "user_bash"
    case object UiPromptStarted extends HumanInput:
      val capability = HookCapability.observe
      val aggregate = Aggregate.AllRun
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "ui_prompt_start"
    case object UiPromptEnded extends HumanInput:
      val capability = HookCapability.observe
      val aggregate = Aggregate.AllRun
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "ui_prompt_end"

  /** G-4 成本/预热（P-10；#40，last-wins 覆盖 warm/stop）。 */
  sealed trait CostWarm extends ReservedPoint
  object CostWarm:
    case object CacheWarmingDecision extends CostWarm:
      val capability = HookCapability(mutateParam = false, mutateResult = false, veto = true, returnRead = true)
      val aggregate = Aggregate.LastWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "cache_warming_decision"

  /** G-5 导航/跳过（派生 G-1/G-2；#51）。 */
  sealed trait Navigation extends ReservedPoint
  object Navigation:
    case object BeforeNavigation extends Navigation:
      val capability = HookCapability(mutateParam = false, mutateResult = true, veto = true, returnRead = true)
      val aggregate = Aggregate.FirstWins
      val defaultErrorPolicy = HookErrorPolicy.Continue
      val display = "before_navigation"

end Reserved
