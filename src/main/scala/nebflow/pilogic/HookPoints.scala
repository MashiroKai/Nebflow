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

/** 观察位背压档（L-11 拍板：分位点声明，不照抄 pi 全局顺序档）。
  *
  * 采纳理由（写进代码即写进报告）：pi 的 provider_stream_event 对每个流事件顺序 await——
  * pi 文档自认「慢 handler 拖慢流」是病（pi-agent-hooks.md A3 面）。pilogic 不照抄：
  * 逐流块的高频观察位（providerPort.Chunked）声明 Dropping——观察者在忙时新帧直接丢弃
  * （声明的有损通道，非错误、不发 HookErrored），生产者永不因观察者阻塞；
  * 语义类位点（message、tool、turn 三族）声明 Sequential——顺序档，事件序即观察序。
  * 声明随位点走（单源），引擎 dispatch 按声明执行：Dropping 档经注册件持有的单槽忙门
  * （Ref[F,Boolean]）+ 非阻塞投递实现「忙即丢」。作者可整体翻转为照抄 pi
  * （改位点的本字段即可，引擎零改动）。
  * 拦截族位点无背压语义（返值必读，天然同步）——恒 Sequential（惰性值）。 */
enum Backpressure:
  /** 顺序档：dispatch 逐个 await，事件序=观察序（语义类位缺省）。 */
  case Sequential
  /** 丢弃档：观察者忙时新帧丢弃、生产者不阻塞（高频流块位 providerPort.Chunked 缺省）。 */
  case Dropping

object Backpressure:
  val default: Backpressure = Backpressure.Sequential

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

/** 拦截族效果（§Ⅲ.1）：dispatch 单点必被消费。位点入参/改写值以 Json 承载（位点专属
  * payload 编解码单源在 LoopRuntime 的 SitePayloads——16 签名增量轮保持切片轮 Json 载体
  * 形状，P#Event 路径依赖类型化为接线期升级项，见收尾报告偏离表）。
  * Patch/Terminate 为增量轮补效果：Patch=#45「patch 与 replace 显式分档」的类型级落点
  * （pi ①②两面在同一位点语义相反处钉死）；Terminate=#37「terminate 早停语义与批语义
  * 同处定义」的载体（循环层解释为全批一致早停）。 */
sealed trait HookEffect extends Product with Serializable
object HookEffect:
  /** 弃权/放行。 */
  case object Proceed extends HookEffect
  /** 改写值=整块替换（Chain 链式传递 / LastWins 后者胜）。 */
  final case class Modify(newValue: Json) extends HookEffect
  /** 增量补丁：与当前值 deepMerge（undefined 删键语义=键置 Null，接线层解释）——
    * 与 Modify 显式分档，防「整块替换」暗吞未声明字段（#45 改造）。 */
  final case class Patch(delta: Json) extends HookEffect
  /** 否决（Session.* 预留位首个 Veto 即止；RunGate/ToolPre 亦用）。 */
  final case class Veto(reason: String) extends HookEffect
  /** 阻断该操作（Tool.Pre fail-closed；terminate=工具批早停语义由循环层解释）。 */
  final case class Block(reason: String, terminate: Boolean) extends HookEffect
  /** 注入消息（preRun.Inject / postRun.FollowUp 族）。 */
  final case class Inject(messages: Vector[LoopMessage]) extends HookEffect
  /** 强制续轮（postRun.Settle 族）。 */
  case object ContinueRun extends HookEffect
  /** 强制收轮（TurnEnded 位点的显式 End 优先）。 */
  case object EndRun extends HookEffect
  /** 工具批一致早停（#37：ToolPre Block(terminate=true) / ToolPost 的批停通道；
    * 循环层语义=剩余未开始调用得合成结果、本批终止、当轮强制收轮）。 */
  final case class Terminate(reason: String) extends HookEffect

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
  /** 观察位背压档（L-11 分位点声明；拦截族恒惰性 Sequential——返值必读天然同步）。 */
  def backpressure: Backpressure = Backpressure.default
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

  /** #18 provider_stream_event：逐流事件观察。L-11 拍板=本位点声明 Dropping（丢弃档）——
    * 不照抄 pi 顺序档：pi 对每流事件顺序 await、慢 handler 直接拖慢流消费是其自认病；
    * 此处观察者忙时新帧丢弃（声明的有损通道），生产者永不因观察者阻塞。 */
  case object ProviderChunked extends HookPoint:
    val capability = HookCapability.observe
    val aggregate = Aggregate.AllRun
    val defaultErrorPolicy = HookErrorPolicy.Continue
    override val backpressure = Backpressure.Dropping
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
    * （改参重校验开关 L-5=位点级常量，两实现语义相反处钉死）；fail-closed 缺省照抄。
    *
    * L-5 拍板（增量轮）：revalidateModifiedArgs=true——ToolPre 改参后**重校验**。
    * 采纳理由：pi ①②两面相反（①面 tool_call 改参不重校验 / ②面 before_tool.args 重校验）
    * 正说明此处必须单点钉死；scala 线模型自传参的信任面窄，改参不重校验=坏参直送执行器
    * （绕过权限/契约校验的通道）。校验件=ToolSet.specs 内该工具的 ToolSpec.validateArgs；
    * 重校验失败 ⇒ 弃改参、不执行、合成错误结果 + HookErrored（fail-closed 不回落）。
    * 此常量随位点走（单源），非逐 hook 自选。 */
  case object ToolPre extends HookPoint:
    val capability = HookCapability(mutateParam = true, mutateResult = false, veto = true, returnRead = true)
    val aggregate = Aggregate.FirstWins
    val defaultErrorPolicy = HookErrorPolicy.Block
    val display = "tool.Pre"
    /** L-5：改参后重校验（位点级常量，非注册方参数）。 */
    val revalidateModifiedArgs: Boolean = true

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
