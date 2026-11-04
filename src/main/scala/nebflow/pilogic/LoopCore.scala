package nebflow.pilogic

import cats.Applicative

/** 循环核心（设计 §Ⅱ.1/§Ⅱ.4；任务书语「LoopEngine」即本 trait——命名从设计 §Ⅱ.1 AgentLoop）。
  *
  * == 分支语义（本轮以契约钉死；双层循环体实现属下一增量轮） ==
  *
  * 1. finishTurn 决策（`TurnDecision`）：决策点在 `TurnEnded` 事件【之前】——照抄 pi
  *    agent-loop.ts:285 早于 :286 的时序；上游 `End` 优先于 turn_end 的 `Continue`。
  * 2. error | aborted 硬退出：发 `TurnEnded(空 toolResults)` + `RunEnded(Failed|Aborted)` 后返回；
  *    【此路径不读 finishTurn 返回值】（pi :244-255 同款，continue 决策在错误路径无效——刻意语义非疏漏）。
  *    provider 契约要求把中断编码为 `StopReason.Aborted` 而非抛异常。
  * 3. abort 传播：`AbortSignal[F]` 贯穿 provider 流与工具执行；未开始的工具调用收到 abort ⇒
  *    合成 "Operation aborted" 错误结果（pi :610-625 同款）；工具批 terminate 早停 = 全批已定稿
  *    结果一致 terminate 才成立（pi :685-687 同款）。
  * 4. 续跑：`continueFrom` 显式入口（两校验照抄 pi :76-82，见 `validateContinuation`）；
  *    崩溃兜底 = 接线层职责，本模块提供 `EventSink.synthesizeTruncatedEvents` 助手。
  * 5. 预算：每轮循环头对 `RunSeed.policy` 做谓词查询，命中 ⇒ `BudgetReported` +
  *    `RunEnded(Failed(BudgetExhausted))`——上限唯一来源是构造参数（L-10），模块内零硬编码。
  */
trait AgentLoop[F[_]]:
  /** 对位 pi agentLoop（agent-loop.ts:37-60）：新提示词启动。 */
  def run(seed: RunSeed[F]): F[RunOutcome]

  /** 对位 pi agentLoopContinue（:70-99）：不追加消息、从当前上下文续跑（重试入口）。
    * 前置校验失败 ⇒ `Failed(InvalidContinuation(...))`（解释性错误值，不抛裸串）。 */
  def continueFrom(context: LoopContext, seed: RunSeed[F]): F[RunOutcome]

object AgentLoop:

  /** continueFrom 前置校验（验收 A-8；独立成函数供分派层/测试复用，两校验照抄 pi :76-82）：
    * 上下文空 ⇒ 拒；末条 assistant ⇒ 拒。 */
  def validateContinuation(context: LoopContext): Either[LoopError.InvalidContinuation, Unit] =
    context.messages.lastOption match
      case None =>
        Left(LoopError.InvalidContinuation("Cannot continue: no messages in context"))
      case Some(last) if last.role == LoopMessageRole.Assistant =>
        Left(LoopError.InvalidContinuation("Cannot continue from message role: assistant"))
      case Some(_) => Right(())

/** 消息史只读视图（L-9 采纳「只读引用」：所有权在调用方——副本会复制全史，写路径单一化归接线层）。 */
final case class LoopContext(messages: Vector[LoopMessage]):
  def lastMessage: Option[LoopMessage] = messages.lastOption
  def appended(m: LoopMessage): LoopContext = LoopContext(messages :+ m)

/** 本次 run 工具面快照（N3：工具是数据；执行器由接线层注入——pilogic 只管批编排与 terminate 语义）。 */
final case class ToolSet(toolNames: Vector[String])

object ToolSet:
  val empty: ToolSet = ToolSet(Vector.empty)

/** finishTurn 决策（§Ⅱ.4(1)）。 */
enum TurnDecision:
  case End
  case Continue(reason: String)

/** run 启动种子（§Ⅱ.2）：一切外部依赖经此注入；`policy` 是唯一上限来源。
  * steering/follow-up 回调（④面）为 Option 注入，缺省无。 */
final case class RunSeed[F[_]](
    context: LoopContext,
    tools: ToolSet,
    policy: LoopPolicy,
    retry: RetryPolicy,
    hooks: HookRegistry[F],
    sink: EventSink[F],
    provider: ProviderPort[F],
    meter: CostMeter[F],
    signal: AbortSignal[F],
    getSteeringMessages: Option[F[Vector[LoopMessage]]] = None,
    getFollowUpMessages: Option[F[Vector[LoopMessage]]] = None,
)

object RunSeed:
  /** 全无帽缺省策略便捷构造（帽仍由调用方显式给出）。 */
  def unbounded[F[_]: Applicative](
      context: LoopContext,
      provider: ProviderPort[F],
      meter: CostMeter[F],
      signal: AbortSignal[F],
  ): RunSeed[F] = RunSeed(
    context = context,
    tools = ToolSet.empty,
    policy = LoopPolicy.none,
    retry = RetryPolicy(),
    hooks = HookRegistry.empty,
    sink = EventSink.noop,
    provider = provider,
    meter = meter,
    signal = signal,
  )

/** 循环状态：run-local case class，逐轮值传递（病灶 #1 结构性免疫：模块内禁 object 可变态 /
 *  @volatile / Ref.unsafe / ConcurrentHashMap；跨 run 记忆必须显式进 RunSeed）。 */
final case class LoopState(
    turn: TurnNumber,
    lastMessageSeq: MessageSeq,
    newMessages: Vector[LoopMessage],
    explicitContinuation: Boolean,
):
  def nextTurn: LoopState = copy(turn = TurnNumber(turn.value + 1L))
  def nextSeqValue: Long = lastMessageSeq.value + 1L
  def recordMessage(m: LoopMessage): LoopState =
    copy(lastMessageSeq = MessageSeq(nextSeqValue), newMessages = newMessages :+ m)

object LoopState:
  def initial: LoopState = LoopState(
    turn = TurnNumber(1L),
    lastMessageSeq = MessageSeq(0L),
    newMessages = Vector.empty,
    explicitContinuation = false,
  )
