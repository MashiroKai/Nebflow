package nebflow.pilogic

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

/** 增量轮测试工具件：脚本化 provider / 录制执行器 / 可翻转中止信号 / 恒零成本表。 */
object LoopTestKit:

  /** Ref 背衬中止信号（可中途翻 true）。 */
  final class TestSignal(ref: Ref[IO, Boolean]):
    val signal: AbortSignal[IO] = new AbortSignal[IO]:
      def aborted: IO[Boolean] = ref.get
    def abortNow: IO[Unit] = ref.set(true)
  object TestSignal:
    def make: IO[TestSignal] = Ref.of[IO, Boolean](false).map(new TestSignal(_))

  /** 脚本化 provider：按序吐出预设流；请求全录（供位点断言）。耗尽=抛错（重试耗尽路径同形）。 */
  final class ScriptedProvider(scripts: Ref[IO, Vector[ProviderStream]], reqs: Ref[IO, Vector[ProviderRequest]]):
    val port: ProviderPort[IO] = (req, _) =>
      scripts.modify {
        case v +: rest => (rest, v)
        case empty     => (empty, sys.error("scripted provider exhausted"))
      }.flatMap(s => reqs.update(_ :+ req).as(s))
    def requests: IO[Vector[ProviderRequest]] = reqs.get
  object ScriptedProvider:
    def of(scripts: ProviderStream*): IO[ScriptedProvider] =
      for
        s <- Ref.of[IO, Vector[ProviderStream]](scripts.toVector)
        q <- Ref.of[IO, Vector[ProviderRequest]](Vector.empty)
      yield new ScriptedProvider(s, q)

  def textTurn(content: String, reason: StopReason = StopReason.Stop,
      calls: Vector[ToolCall] = Vector.empty, chunks: Vector[ProviderChunk] = Vector.empty): ProviderStream =
    ProviderStream(chunks, ProviderResponse(
      LoopMessage(LoopMessageRole.Assistant, Json.fromString(content)), reason, calls))

  def toolTurn(call: ToolCall, content: String = "using tool",
      chunks: Vector[ProviderChunk] = Vector.empty): ProviderStream =
    textTurn(content, StopReason.ToolUse, Vector(call), chunks)

  /** 录制执行器：记录调用（断言改参/跳过）；resultOf 可效应化（如执行中翻转中止信号）；
    * progressOnce=执行中发一次进度。 */
  final class RecordingExecutor(ref: Ref[IO, Vector[ToolCall]],
      resultOf: ToolCall => IO[ToolExecutionResult], progressOnce: Boolean):
    val executor: ToolExecutor[IO] = (call, _, onDelta) =>
      ref.update(_ :+ call) *>
        (if progressOnce then onDelta(Json.obj("progress" -> 1.asJson)) else IO.unit) *>
        resultOf(call)
    def calls: IO[Vector[ToolCall]] = ref.get
  object RecordingExecutor:
    def make(resultOf: ToolCall => IO[ToolExecutionResult] =
        c => IO.pure(ToolExecutionResult.ok(Json.fromString(s"done:${c.name}"))),
        progressOnce: Boolean = false): IO[RecordingExecutor] =
      Ref.of[IO, Vector[ToolCall]](Vector.empty).map(new RecordingExecutor(_, resultOf, progressOnce))

  val constMeter: CostMeter[IO] = new CostMeter[IO]:
    def spent: IO[CostSnapshot] = IO.pure(CostSnapshot(0L, 0L))

  val constSignal: AbortSignal[IO] = new AbortSignal[IO]:
    def aborted: IO[Boolean] = IO.pure(false)

  def recordingSink(ref: Ref[IO, Vector[LoopEvent]]): EventSink[IO] = e => ref.update(_ :+ e)

  def recordingObserver(ref: Ref[IO, Vector[Json]]): Observer[IO] = j => ref.update(_ :+ j)

  /** 位点声明断言数据（L-11 分位点声明 + L-5 位点级常量）。 */
  val adoptedPoints: Vector[HookPoint] = Vector(
    HookPoint.RunGate, HookPoint.PreRunStart, HookPoint.PreRunInject, HookPoint.PreRequestContext,
    HookPoint.PreRequestSystem, HookPoint.PreRequestOptions, HookPoint.ProviderPayload,
    HookPoint.ProviderHeaders, HookPoint.ProviderResponded, HookPoint.ProviderChunked,
    HookPoint.MessageStarted, HookPoint.MessageDelta, HookPoint.MessageEnded, HookPoint.ToolPre,
    HookPoint.ToolPost, HookPoint.ToolBatchSettled, HookPoint.ToolExecutionStarted,
    HookPoint.ToolExecutionDelta, HookPoint.ToolExecutionEnded, HookPoint.TurnStarted,
    HookPoint.TurnEnded, HookPoint.PostRunSettle, HookPoint.PostRunSettled, HookPoint.PostRunFollowUp,
    HookPoint.SessionStarted, HookPoint.SessionEnding, HookPoint.ModelSelected,
    HookPoint.ThinkingLevelSelected, HookPoint.CompactEnded,
  )

  def seedOf(
      provider: ProviderPort[IO],
      executor: ToolExecutor[IO],
      sink: EventSink[IO],
      hooks: HookRegistry[IO] = HookRegistry.empty[IO],
      policy: LoopPolicy = LoopPolicy.none,
      retry: RetryPolicy = RetryPolicy(),
      context: LoopContext = LoopContext(Vector(LoopMessage(LoopMessageRole.User, Json.fromString("hi")))),
      tools: ToolSet = ToolSet.empty,
      steering: Option[IO[Vector[LoopMessage]]] = None,
      followUp: Option[IO[Vector[LoopMessage]]] = None,
      finishTurn: Option[TurnSnapshot => IO[TurnDecision]] = None,
      model: Option[String] = None,
      signal: AbortSignal[IO] = constSignal,
      options: Json = Json.obj(),
  ): RunSeed[IO] = RunSeed(
    context, tools, policy, retry, hooks, sink, provider, constMeter, signal, executor,
    steering, followUp, finishTurn, model, options,
  )

  /** A-5 成对断言（纯检查抛 AssertionError——不依赖 munit 基类，供普通 object 使用）：
    * RunStarted..RunEnded 恰一对；TurnStarted/TurnEnded 逐轮成对。 */
  def assertPaired(events: Vector[LoopEvent]): Unit =
    val runStarts = events.count { case LoopEvent.RunStarted => true; case _ => false }
    val runEnds = events.count { case LoopEvent.RunEnded(_) => true; case _ => false }
    val turnStarts = events.count { case LoopEvent.TurnStarted(_) => true; case _ => false }
    val turnEnds = events.count { case LoopEvent.TurnEnded(_, _, _, _) => true; case _ => false }
    if runStarts != 1 then throw AssertionError(s"A-5: RunStarted x$runStarts")
    if runEnds != 1 then throw AssertionError(s"A-5: RunEnded x$runEnds")
    if turnStarts != turnEnds then throw AssertionError(s"A-5: Turn 对不齐 $turnStarts/$turnEnds")

  /** 计数工具：TurnStarted 事件次数。 */
  def turnCount(events: Vector[LoopEvent]): Int =
    events.count { case LoopEvent.TurnStarted(_) => true; case _ => false }
