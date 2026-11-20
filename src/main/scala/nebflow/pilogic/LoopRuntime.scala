package nebflow.pilogic

import cats.effect.Concurrent
import cats.effect.kernel.Clock
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.syntax.*

/**
 * 位点 payload 编解码单源（增量轮：Json 载体形状的位点专属 Event/Value 面——每位点
 * 输入 schema 与效果契约在此唯一声明，循环与测试共用；P#Event 路径依赖类型化留接线期，
 * 见收尾报告偏离表）。
 */
private[pilogic] object SitePayloads:

  def roleStr(r: LoopMessageRole): String = r match
    case LoopMessageRole.System => "system"
    case LoopMessageRole.User => "user"
    case LoopMessageRole.Assistant => "assistant"
    case LoopMessageRole.ToolResult => "toolResult"

  def roleOf(s: String): Option[LoopMessageRole] = s match
    case "system" => Some(LoopMessageRole.System)
    case "user" => Some(LoopMessageRole.User)
    case "assistant" => Some(LoopMessageRole.Assistant)
    case "toolResult" => Some(LoopMessageRole.ToolResult)
    case _ => None

  given messageEncoder: Encoder[LoopMessage] =
    m => Json.obj("role" -> roleStr(m.role).asJson, "content" -> m.content)

  given messageDecoder: Decoder[LoopMessage] = (c: HCursor) =>
    for
      role <- c.get[String]("role")
      content <- c.get[Json]("content")
      r <- roleOf(role).toRight(DecodingFailure(s"unknown role: $role", c.history))
    yield LoopMessage(r, content)

  given messagesEncoder: Encoder[Vector[LoopMessage]] = ms => Json.arr(ms.map(messageEncoder.apply)*)

  given messagesDecoder: Decoder[Vector[LoopMessage]] =
    _.as[List[Json]].flatMap(_.traverse(_.as[LoopMessage]).map(_.toVector))

  given requestEncoder: Encoder[ProviderRequest] = r =>
    Json.obj(
      "model" -> r.model.asJson,
      "system" -> r.system.getOrElse(Json.Null),
      "messages" -> r.messages.asJson,
      "options" -> r.options,
      "headers" -> r.headers
    )

  given requestDecoder: Decoder[ProviderRequest] = (c: HCursor) =>
    for
      model <- c.get[String]("model")
      system <- c.get[Option[Json]]("system")
      msgs <- c.get[Vector[LoopMessage]]("messages")
      options <- c.get[Json]("options")
      headers <- c.get[Json]("headers")
    yield ProviderRequest(model, system, msgs, options, headers)

  def responseJson(resp: ProviderResponse): Json = Json.obj(
    "message" -> resp.message.asJson,
    "stopReason" -> resp.stopReason.toString.asJson,
    "toolCalls" -> Json.arr(resp.toolCalls.map(ToolCall.toJson)*)
  )

  /** 循环侧拒绝非法改写时的统一事件（hookId=循环守卫名，cause 说明拒绝原因）。 */
  def guardError(point: HookPoint, guard: String, cause: String): LoopEvent.HookErrored =
    LoopEvent.HookErrored(point.display, guard, cause)

end SitePayloads

import SitePayloads.given
import SiteEvents.given

/**
 * 双层 while 循环体（增量轮：AgentLoop trait 契约落成可运行实现，设计 §Ⅱ.1 骨架逐行对位）。
 *
 * == 结构（对位 pi agent-loop.ts） ==
 *   - 外层（对位 :178 while(true)）：轮首 steering 排水（:175）→ 内层 → 自然断后
 *     postRun.Settle / postRun.FollowUp 续命判定（:300-313）；
 *   - 内层（对位 :182）：预算谓词（循环头，§Ⅱ.2）→ TurnStarted → preRequest 族 →
 *     providerPort.Payload/Headers → provider 流（消息事件边发边转 sink）→
 *     error|aborted 硬退出（:244-255，此路径不读 finishTurn）→ Length 截断保护（:475-500）→
 *     工具批（:262-277；terminate 全批一致 :685-687）→ 决策（:285 早于 TurnEnded 事件 :286）→
 *     TurnEnded → End 即收 / 续命条件判定（:316）→ 轮尾 steering 排水（:294）。
 *
 * == 采纳 29 位点挂接（§Ⅰ 位点表 → 本文件对应阶段） ==
 *   run 入口：session.Started（空上下文=新会话等价）→ run.Gate → preRun.Start（system 改写
 *   通道）/ preRun.Inject（消息注入通道）；每轮：turn.Started → preRequest.Context /
 *   preRequest.System / preRequest.Options → model.Selected / thinkingLevel.Selected（实际
 *   变化时）→ providerPort.Payload（#64 消息条数冻结守卫）→ providerPort.Headers →
 *   providerPort.Responded → providerPort.Chunked（逐块，L-11 Dropping 档）→ message.Started/
 *   Delta/Ended（Ended 可改写、role 约束强制）→ tool.Pre（L-5 改参重校验）/ tool.Post（仅真
 *   执行调用）/ toolExecution.Started/Delta/Ended / tool.BatchSettled → turn.Ended（决策早于
 *   事件）→（自然断）postRun.Settle / postRun.FollowUp / postRun.Settled（收尾后终局通知）。
 *   session.Ending 与 compact.Ended 触发点归会话主人（接线层）——SessionStages 单点入口。
 */
/**
 * 会话级位点的单点触发入口（session.Ending #10 / compact.Ended #8）：循环不含会话拆除与
 * 历史压缩语义，接线层（会话主人）在真实阶段调用；returnRead=true 的位点强制消费返值
 * （helper 返回 DispatchResult 即「必读」的类型化）。
 */
object SessionStages:

  /** 会话拆除前（pi session_shutdown 返 true 供调用方跳重复拆除——Decided 效果由调用方解释）。 */
  def ending[F[_]: Concurrent](snapshot: HookSnapshot[F], sink: EventSink[F]): F[DispatchResult] =
    HookDispatch.dispatch(HookPoint.SessionEnding, SiteEvents.SessionEndingInput().asJson, snapshot, sink)

  /** 压缩落库后（#8 观察级）。 */
  def compactEnded[F[_]: Concurrent](snapshot: HookSnapshot[F], sink: EventSink[F]): F[DispatchResult] =
    HookDispatch.dispatch(HookPoint.CompactEnded, SiteEvents.CompactEndedInput().asJson, snapshot, sink)

/** run 内私有状态（run-local 值传递——病灶 #1 结构性免疫；LoopState 为 §Ⅱ 公开签名件）。 */
private[pilogic] final case class RState(
  ctx: LoopContext,
  loop: LoopState,
  systemOverride: Option[Json] = None,
  prevModel: Option[String] = None,
  prevThinking: Option[Json] = None,
  settles: Int = 0
)

private[pilogic] sealed trait TurnExit
private[pilogic] case class TerminalOut(out: RunOutcome) extends TurnExit
private[pilogic] case class NextTurn(st: RState) extends TurnExit
private[pilogic] case class BreakInner(st: RState) extends TurnExit

private[pilogic] final case class BatchOutcome(
  results: Vector[(ToolCall, ToolExecutionResult)],
  terminated: Boolean,
  aborted: Boolean
)

final class LoopRuntime[F[_]: Concurrent: Clock] extends AgentLoop[F]:

  private val F = Concurrent[F]
  private type Dur = scala.concurrent.duration.FiniteDuration

  // ── 入口 ─────────────────────────────────────────────────────────────────

  def run(seed: RunSeed[F]): F[RunOutcome] =
    drive(seed, seed.context)

  def continueFrom(context: LoopContext, seed: RunSeed[F]): F[RunOutcome] =
    AgentLoop.validateContinuation(context) match
      case Left(err) =>
        // A-5：拒绝分支也是一次完整 run 生命周期（RunStarted…RunEnded 成对）
        seed.sink.emit(LoopEvent.RunStarted) *> finishRun(seed, RunOutcome.Failed(err))
      case Right(()) => drive(seed, context)

  private def drive(seed: RunSeed[F], ctx0: LoopContext): F[RunOutcome] =
    val body =
      for
        gates <- DropGates.of(seed.hooks.snapshot) // L-11 run-scoped 忙门
        started <- Clock[F].monotonic // 墙钟基准（A-2 三元谓词之一）
        _ <- seed.sink.emit(LoopEvent.RunStarted) // 对位 :116
        _ <-
          if ctx0.messages.isEmpty
          then
            observe(
              seed,
              HookPoint.SessionStarted,
              seed.hooks.snapshot,
              gates,
              SiteEvents.SessionStartedInput(fresh = true).asJson
            )
          else F.unit
        gated <- HookDispatch.dispatch(
          HookPoint.RunGate,
          SiteEvents.RunGateInput(ctx0.messages.size, seed.model.getOrElse("")).asJson,
          seed.hooks.snapshot,
          seed.sink,
          gates
        )
        out <- gated match
          case DispatchResult.Decided(HookEffect.Veto(reason)) =>
            finishRun(seed, RunOutcome.Failed(LoopError.RunGated(reason)))
          case _ => preRunStage(seed, ctx0, gates, started)
      yield out
    // FailRun 档（RunGate/ToolPre 收紧档）raise 的 HookRunFatal 回收为 Failed 收尾——
    // A-5 成对不变量保持（RunStarted 已发 ⇒ RunEnded 必发）
    body.recoverWith { case e: LoopError.HookRunFatal =>
      finishRun(seed, RunOutcome.Failed(e))
    }

  end drive

  /**
   * run 入口族：preRun.Start（system 改写通道）/ preRun.Inject（消息注入通道）→ 外层循环。
   * 两通道分位点承载（Chain 聚合单决定性效果——复合改写由单 hook 表达，见报告偏离表）。
   */
  private def preRunStage(seed: RunSeed[F], ctx0: LoopContext, gates: DropGates[F], started: Dur): F[RunOutcome] =
    val snap = seed.hooks.snapshot
    val sysNow = effectiveSystem(ctx0.messages, None)
    for
      startEff <- HookDispatch.dispatch(
        HookPoint.PreRunStart,
        SiteEvents.PreRunStartInput(sysNow.getOrElse(Json.Null)).asJson,
        snap,
        seed.sink,
        gates
      )
      sysOverride = startEff match
        case DispatchResult.Decided(HookEffect.Modify(v)) => Some(v)
        case _ => None
      injEff <- HookDispatch.dispatch(
        HookPoint.PreRunInject,
        SiteEvents.PreRunInjectInput(ctx0.messages.size).asJson,
        snap,
        seed.sink,
        gates
      )
      ctx1 = injEff match
        case DispatchResult.Decided(HookEffect.Inject(ms)) => ms.foldLeft(ctx0)(_.appended(_))
        case _ => ctx0
      out <- outerLoop(RState(ctx1, LoopState.initial, systemOverride = sysOverride), seed, gates, started)
    yield out

    end for

  end preRunStage

  /** 收尾单点：RunEnded 事件 + postRun.Settled 终局通知（#23 整 run 最末）——一切终态路径经此。 */
  private def finishRun(seed: RunSeed[F], outcome: RunOutcome): F[RunOutcome] =
    seed.sink.emit(LoopEvent.RunEnded(outcome)) *>
      observe(
        seed,
        HookPoint.PostRunSettled,
        seed.hooks.snapshot,
        DropGates.none,
        SiteEvents.PostRunSettledInput(outcome).asJson
      ).as(outcome)

  private def naturalOutcome(st: RState): RunOutcome =
    if st.settles > 0 then RunOutcome.SettledExtra(st.settles, st.loop.newMessages)
    else RunOutcome.Completed(st.loop.newMessages)

  // ── 外层（对位 :178）─────────────────────────────────────────────────────

  private def outerLoop(st: RState, seed: RunSeed[F], gates: DropGates[F], started: Dur): F[RunOutcome] =
    for
      st1 <- drainSteering(seed, st) // 轮首排水（对位 :175）
      inner <- innerLoop(st1, seed, gates, started)
      out <- inner match
        case Left(out) => F.pure(out) // 硬退出/End：RunEnded 已发
        case Right(stEnd) =>
          // 自然断：postRun.Settle（#22 关键位）+ postRun.FollowUp（#43/#67）续命判定
          val snap = seed.hooks.snapshot
          for
            settle <- HookDispatch.dispatch(
              HookPoint.PostRunSettle,
              SiteEvents.PostRunSettleInput(stEnd.loop.newMessages.size, stEnd.loop.turn).asJson,
              snap,
              seed.sink,
              gates
            )
            follow <- HookDispatch.dispatch(
              HookPoint.PostRunFollowUp,
              SiteEvents.PostRunFollowUpInput(stEnd.ctx.messages.size).asJson,
              snap,
              seed.sink,
              gates
            )
            cbMsgs <- seed.getFollowUpMessages.getOrElse(F.pure(Vector.empty[LoopMessage]))
            hookMsgs = follow match
              case DispatchResult.Decided(HookEffect.Inject(ms)) => ms
              case _ => Vector.empty
            explicitContinue = settle match
              case DispatchResult.Decided(HookEffect.ContinueRun | HookEffect.Veto(_)) => true
              case _ => false
            followUps = hookMsgs ++ cbMsgs
            res <-
              if explicitContinue || followUps.nonEmpty then
                val st2 = followUps.foldLeft(stEnd.copy(settles = stEnd.settles + (if explicitContinue then 1 else 0)))(
                  (s, m) => s.copy(ctx = s.ctx.appended(m))
                )
                outerLoop(st2, seed, gates, started)
              else finishRun(seed, naturalOutcome(stEnd))
          yield res
          end for
    yield out

  // ── 内层（对位 :182）─────────────────────────────────────────────────────

  private def innerLoop(
    st: RState,
    seed: RunSeed[F],
    gates: DropGates[F],
    started: Dur
  ): F[Either[RunOutcome, RState]] =
    for
      spent <- seed.meter.spent
      now <- Clock[F].monotonic
      budgetHit = seed.policy.exceedsTurns(st.loop.turn) ||
        seed.policy.exceedsTokens(spent.tokensSpent) ||
        seed.policy.exceedsWallClock(now - started)
      res <-
        if budgetHit then
          seed.sink.emit(LoopEvent.BudgetReported(spent)) *>
            finishRun(seed, RunOutcome.Failed(LoopError.BudgetExhausted)).map(Left(_))
        else
          turnBody(st, seed, gates, started).flatMap {
            case TerminalOut(out) => F.pure(Left(out))
            case NextTurn(st2) => innerLoop(st2, seed, gates, started)
            case BreakInner(st2) => F.pure(Right(st2))
          }
    yield res

  /**
   * 单轮主体：事件序 = turn.Started → preRequest.* → model/thinking diff 位 →
   * providerPort.Payload/Headers → provider → Responded → message.* →（error|aborted 硬退出
   * | 工具批 → 决策早于事件 → turn.Ended）。
   */
  private def turnBody(st: RState, seed: RunSeed[F], gates: DropGates[F], started: Dur): F[TurnExit] =
    val snap = seed.hooks.snapshot // 每 turn 重取（A-9 帧边界=turn 边界）
    val turn = st.loop.turn
    for
      _ <- seed.sink.emit(LoopEvent.TurnStarted(turn))
      // turn.Started（#1 位点：与事件同步落地，入参 = 轮次号；早于 preRequest 族）
      _ <- observe(seed, HookPoint.TurnStarted, snap, gates, SiteEvents.TurnStartedInput(turn).asJson)

      // preRequest.Context（#13/#44/#53：消息史改写链式；解码失败=弃改写+守卫事件）
      ctxEff <- HookDispatch.dispatch(
        HookPoint.PreRequestContext,
        SiteEvents.PreRequestContextInput(st.ctx.messages).asJson,
        snap,
        seed.sink,
        gates
      )
      (ctx1, ctxGuard) = ctxEff match
        case DispatchResult.Decided(HookEffect.Modify(v)) =>
          v.as[Vector[LoopMessage]] match
            case Right(ms) => (LoopContext(ms), None)
            case Left(err) =>
              (st.ctx, Some(SitePayloads.guardError(HookPoint.PreRequestContext, "loop.decode", err.toString)))
        case _ => (st.ctx, None)
      _ <- ctxGuard.traverse_(seed.sink.emit)

      // preRequest.System（#14/#52）
      sysNow = effectiveSystem(ctx1.messages, st.systemOverride)
      sysEff <- HookDispatch.dispatch(
        HookPoint.PreRequestSystem,
        SiteEvents.PreRequestSystemInput(sysNow.getOrElse(Json.Null)).asJson,
        snap,
        seed.sink,
        gates
      )
      sysOverride1 = sysEff match
        case DispatchResult.Decided(HookEffect.Modify(v)) if v.isNull => None
        case DispatchResult.Decided(HookEffect.Modify(v)) => Some(v)
        case _ => st.systemOverride
      // 请求侧有效 system：位点改写值优先，未改写沿用本轮现值（含消息史兜底）
      sysEffective = sysEff match
        case DispatchResult.Decided(HookEffect.Modify(v)) => if v.isNull then None else Some(v)
        case _ => sysNow

      // preRequest.Options（#45：Patch=增量 deepMerge / Modify=整块替换，显式分档）
      optEff <- HookDispatch.dispatch(
        HookPoint.PreRequestOptions,
        SiteEvents.PreRequestOptionsInput(seed.options).asJson,
        snap,
        seed.sink,
        gates
      )
      options1 = optEff match
        case DispatchResult.Decided(HookEffect.Modify(v)) => v
        case DispatchResult.Decided(HookEffect.Patch(d)) => seed.options.deepMerge(d)
        case _ => seed.options

      model = seed.model.getOrElse("")

      // 请求装配 + providerPort.Payload（#15/#46 整块替换；#64 消息条数冻结守卫）
      req0 = ProviderRequest(model, sysEffective, ctx1.messages, options1, Json.obj())
      payloadEff <- HookDispatch.dispatch(
        HookPoint.ProviderPayload,
        SiteEvents.ProviderPayloadInput(req0).asJson,
        snap,
        seed.sink,
        gates
      )
      (req1, payloadGuard) = payloadEff match
        case DispatchResult.Decided(HookEffect.Modify(v)) =>
          v.as[ProviderRequest] match
            case Right(r) if r.messages.size == req0.messages.size => (r, None)
            case Right(r) =>
              (
                req0,
                Some(
                  SitePayloads.guardError(
                    HookPoint.ProviderPayload,
                    "loop.message-freeze",
                    s"#64 prepareRequest 不可追加消息：${req0.messages.size} → ${r.messages.size}，弃改写"
                  )
                )
              )
            case Left(err) =>
              (req0, Some(SitePayloads.guardError(HookPoint.ProviderPayload, "loop.decode", err.toString)))
        case _ => (req0, None)
      _ <- payloadGuard.traverse_(seed.sink.emit)

      // providerPort.Headers（#16 改造位：返值必读——Modify 直接生效）
      hdrEff <- HookDispatch.dispatch(
        HookPoint.ProviderHeaders,
        SiteEvents.ProviderHeadersInput(req1.headers).asJson,
        snap,
        seed.sink,
        gates
      )
      req = hdrEff match
        case DispatchResult.Decided(HookEffect.Modify(v)) => req1.copy(headers = v)
        case _ => req1

      // model.Selected / thinkingLevel.Selected（#34/#35）：diff 比较**请求有效值**——
      // 钩子（Payload/Options）改写出来的换档也看得见；seed 常量不参与比较（根因修复④）。
      effThinking = req.options.hcursor.get[Json]("thinkingLevel").toOption
      _ <- st.prevModel match
        case Some(prev) if prev != req.model =>
          observe(seed, HookPoint.ModelSelected, snap, gates, SiteEvents.ModelSelectedInput(prev, req.model).asJson)
        case _ => F.unit
      _ <- (st.prevThinking, effThinking) match
        case (Some(prev), Some(cur)) if prev != cur =>
          observe(
            seed,
            HookPoint.ThinkingLevelSelected,
            snap,
            gates,
            SiteEvents.ThinkingLevelSelectedInput(prev, cur).asJson
          )
        case _ => F.unit

      // provider 调用（中止前置检查 + 可重试腿）
      aborted0 <- seed.signal.aborted
      res <- aborted0 match
        case true =>
          seed.sink.emit(LoopEvent.TurnEnded(turn, None, Vector.empty, TurnEndOutcome.Aborted)) *>
            finishRun(seed, RunOutcome.Aborted(AbortCause.External("aborted before provider call")))
              .map(out => TerminalOut(out))
        case false =>
          providerWithRetry(seed, req).flatMap {
            case Left(errMsg) =>
              // 抛出型失败重试耗尽：硬退出（此路径不读 finishTurn，§Ⅱ.4(2)）
              seed.sink.emit(LoopEvent.TurnEnded(turn, None, Vector.empty, TurnEndOutcome.Error(errMsg))) *>
                finishRun(seed, RunOutcome.Failed(LoopError.ProviderFailed(errMsg)))
                  .map(out => TerminalOut(out))
            case Right(pstream) =>
              afterProvider(st, seed, gates, snap, turn, pstream, req, ctx1, sysOverride1)
          }
    yield res

    end for

  end turnBody

  /** provider 返回后：Responded 观察 → 消息事件族 → error|aborted 硬退出 → 工具批+决策。 */
  private def afterProvider(
    st: RState,
    seed: RunSeed[F],
    gates: DropGates[F],
    snap: HookSnapshot[F],
    turn: TurnNumber,
    pstream: ProviderStream,
    req: ProviderRequest,
    ctx1: LoopContext,
    sysOverride1: Option[Json]
  ): F[TurnExit] =
    val resp = pstream.response
    for
      // providerPort.Responded（#17/#54：收到响应消费前，只读）
      _ <- observe(seed, HookPoint.ProviderResponded, snap, gates, SiteEvents.ProviderRespondedInput(resp).asJson)

      // 消息事件族（seq 单写者；块逐个转发：message.Delta 事件+位点 + providerPort.Chunked）
      seq = MessageSeq(st.loop.nextSeqValue)
      _ <- seed.sink.emit(LoopEvent.MessageStarted(seq))
      _ <- observe(seed, HookPoint.MessageStarted, snap, gates, SiteEvents.MessageStartedInput(seq).asJson)
      _ <- pstream.chunks.traverse_ { chunk =>
        seed.sink.emit(LoopEvent.MessageDelta(seq, chunk.delta)) *>
          observe(seed, HookPoint.MessageDelta, snap, gates, SiteEvents.MessageDeltaInput(seq, chunk.delta).asJson) *>
          observe(seed, HookPoint.ProviderChunked, snap, gates, SiteEvents.ProviderChunkedInput(chunk.delta).asJson)
      }

      // message.Ended（#30/#47：改写 + role 约束强制——pi 两实现不一致处不照抄）
      msgEff <- HookDispatch.dispatch(
        HookPoint.MessageEnded,
        SiteEvents.MessageEndedInput(resp.message).asJson,
        snap,
        seed.sink,
        gates
      )
      (finalMsg, msgGuard) = msgEff match
        case DispatchResult.Decided(HookEffect.Modify(v)) =>
          v.as[LoopMessage] match
            case Right(m) if m.role == resp.message.role => (m, None)
            case Right(m) =>
              (
                resp.message,
                Some(
                  SitePayloads.guardError(
                    HookPoint.MessageEnded,
                    "loop.role-guard",
                    s"替换须同 role：${resp.message.role} → ${m.role}，弃改写"
                  )
                )
              )
            case Left(err) =>
              (resp.message, Some(SitePayloads.guardError(HookPoint.MessageEnded, "loop.decode", err.toString)))
        case _ => (resp.message, None)
      _ <- msgGuard.traverse_(seed.sink.emit)
      _ <- seed.sink.emit(LoopEvent.MessageEnded(seq, finalMsg))

      st1 = st.copy(
        loop = st.loop.recordMessage(finalMsg),
        ctx = ctx1.appended(finalMsg),
        systemOverride = sysOverride1,
        prevModel = Some(req.model),
        prevThinking = req.options.hcursor.get[Json]("thinkingLevel").toOption
      )

      res <- resp.stopReason match
        // 硬退出：error|aborted 不读 finishTurn（照抄 pi :244-255——continue 决策在错误路径无效）
        case StopReason.Error =>
          seed.sink.emit(
            LoopEvent.TurnEnded(turn, Some(finalMsg), Vector.empty, TurnEndOutcome.Error("provider error"))
          ) *>
            finishRun(seed, RunOutcome.Failed(LoopError.ProviderFailed("provider reported stopReason=error")))
              .map(out => TerminalOut(out))
        case StopReason.Aborted =>
          seed.sink.emit(LoopEvent.TurnEnded(turn, Some(finalMsg), Vector.empty, TurnEndOutcome.Aborted)) *>
            finishRun(seed, RunOutcome.Aborted(AbortCause.External("provider reported stopReason=aborted")))
              .map(out => TerminalOut(out))
        case reason =>
          toolPhase(st1, seed, gates, snap, turn, finalMsg, reason, resp.toolCalls)
    yield res

    end for

  end afterProvider

  /** 工具批 + 决策（Length 截断保护 → 批执行 → turn.Ended 决策早于事件 → 续命判定）。 */
  private def toolPhase(
    st: RState,
    seed: RunSeed[F],
    gates: DropGates[F],
    snap: HookSnapshot[F],
    turn: TurnNumber,
    msg: LoopMessage,
    stopReason: StopReason,
    calls: Vector[ToolCall]
  ): F[TurnExit] =
    // Length 截断保护（对位 pi :475-500）：整批判失败、不执行（亦不发单调用事件）
    val truncated = stopReason == StopReason.Length
    for
      batch <-
        if truncated then
          F.pure(
            BatchOutcome(
              calls.map(c => c -> ToolExecutionResult.error("tool call truncated by length")),
              terminated = false,
              aborted = false
            )
          )
        else runBatch(seed, gates, snap, calls)

      resultMsgs = batch.results.map((c, r) =>
        LoopMessage(LoopMessageRole.ToolResult, ToolExecutionResult.toJson(c, r))
      )
      st2 = resultMsgs.foldLeft(st)((s, m) => s.copy(ctx = s.ctx.appended(m)))

      res <-
        if batch.aborted then
          // 中止传播（A-10）：未开始调用已在 runBatch 内得合成 aborted 结果——硬退出
          seed.sink.emit(LoopEvent.TurnEnded(turn, Some(msg), resultMsgs, TurnEndOutcome.Aborted)) *>
            finishRun(seed, RunOutcome.Aborted(AbortCause.External("aborted during tool batch")))
              .map(out => TerminalOut(out))
        else decisionPhase(st2, seed, gates, snap, turn, msg, stopReason, batch, truncated, resultMsgs)
    yield res

    end for

  end toolPhase

  /**
   * 决策阶段（独立方法防深层嵌套 for）：决策早于 TurnEnded 事件（对位 :285 早于 :286）。
   * 优先级（#27）：terminate 强制 End > 显式 finishTurn End > turn.Ended 位
   * （EndRun/Veto=End，ContinueRun/Inject=续）> 自然规则。
   * End 语义=不再续轮，但仍走外层 settle/followUp 收尾（对位 pi :301-313——#43/#67
   * 「本会停止才消费的消息」须在自然停止路径可消费）；立即返回仅硬退出路径。
   */
  private def decisionPhase(
    st2: RState,
    seed: RunSeed[F],
    gates: DropGates[F],
    snap: HookSnapshot[F],
    turn: TurnNumber,
    msg: LoopMessage,
    stopReason: StopReason,
    batch: BatchOutcome,
    truncated: Boolean,
    resultMsgs: Vector[LoopMessage]
  ): F[TurnExit] =
    for
      upstream <-
        if batch.terminated then F.pure(TurnDecision.End)
        else
          seed.finishTurn match
            // 自然规则=续行交给续命条件（无显式结束者 ⇒ 内层条件/外层收尾定去留）
            case Some(f) => f(TurnSnapshot(turn, Some(msg), batch.results.map(_._2), stopReason))
            case None => F.pure(TurnDecision.Continue("natural"))
      upstreamExplicitEnd = !batch.terminated && seed.finishTurn.isDefined && upstream == TurnDecision.End
      turnEff <- HookDispatch.dispatch(
        HookPoint.TurnEnded,
        SiteEvents.TurnEndedInput(turn, Some(msg), batch.results.map(_._2.output), upstream).asJson,
        snap,
        seed.sink,
        gates
      )
      effective =
        if batch.terminated then TurnDecision.End
        else if upstreamExplicitEnd then TurnDecision.End
        else
          turnEff match
            case DispatchResult.Decided(HookEffect.EndRun | HookEffect.Veto(_)) => TurnDecision.End
            case DispatchResult.Decided(HookEffect.ContinueRun | _: HookEffect.Inject) =>
              TurnDecision.Continue("turn.Ended hook")
            case _ => upstream
      explicitEnd = batch.terminated || upstreamExplicitEnd || turnEff.endRequested
      outcome =
        if truncated then TurnEndOutcome.Truncated
        else if effective == TurnDecision.End && explicitEnd then TurnEndOutcome.ExplicitEnd
        else TurnEndOutcome.Natural
      _ <- seed.sink.emit(LoopEvent.TurnEnded(turn, Some(msg), resultMsgs, outcome))

      res2 <- effective match
        case TurnDecision.End =>
          // 不续轮：交外层 settle/followUp 决定是否复活（自然收尾在 outerLoop）
          F.pure(BreakInner(st2))
        case TurnDecision.Continue(_) =>
          // 续命判定（对位 :316）+ turn.Ended 位 entries 注入（pi #27 entries 通道）+
          // 轮尾 steering 排水（对位 :294）
          val injected = turnEff match
            case DispatchResult.Decided(HookEffect.Inject(ms)) => ms
            case _ => Vector.empty
          val st2i = injected.foldLeft(st2)((s, m) => s.copy(ctx = s.ctx.appended(m)))
          drainSteering(seed, st2i).map { st3 =>
            val arrived = st3.ctx.messages.size > st2.ctx.messages.size // 含注入与 steering
            if resultMsgs.nonEmpty || arrived then NextTurn(st3.copy(loop = st3.loop.nextTurn))
            else BreakInner(st3)
          }
    yield res2

  // ── 工具批（串行编排；terminate 全批一致 = #37 与批语义同处定义）──────────

  private def runBatch(
    seed: RunSeed[F],
    gates: DropGates[F],
    snap: HookSnapshot[F],
    calls: Vector[ToolCall]
  ): F[BatchOutcome] =
    def go(
      rest: Vector[ToolCall],
      acc: Vector[(ToolCall, ToolExecutionResult)],
      terminated: Boolean,
      aborted: Boolean
    ): F[BatchOutcome] =
      rest.headOption match
        case None =>
          // 空批不发 batch.Settled（文本轮 calls 为空；根因修复③：位点语义=「有批收尾」）
          (if acc.isEmpty then F.unit
           else
             observe(
               seed,
               HookPoint.ToolBatchSettled,
               snap,
               gates,
               SiteEvents.ToolBatchSettledInput(acc.map(SiteEvents.ToolOutcome.apply)).asJson
             )
          )
            .as(BatchOutcome(acc, terminated, aborted))
        case Some(c) =>
          def skipped(r: ToolExecutionResult): F[BatchOutcome] =
            go(rest.tail, acc :+ (c -> r), terminated, aborted)
          if terminated then skipped(ToolExecutionResult.error("skipped: batch terminated"))
          else
            seed.signal.aborted.flatMap {
              case true =>
                // 未开始的调用收到 abort ⇒ 合成中止结果（pi :610-625 同款，A-10）
                skipped(ToolExecutionResult.error("Operation aborted")).map(_.copy(aborted = true))
              case false =>
                runOneCall(seed, gates, snap, c).flatMap { case (r, term) =>
                  go(rest.tail, acc :+ (c -> r), terminated || term, aborted)
                }
            }
    go(calls, Vector.empty, terminated = false, aborted = false)

  end runBatch

  /**
   * 单调用：Started → 引擎侧原始参校验 → tool.Pre（L-5 改参重校验）→ 执行 →
   * tool.Post（仅真执行过的调用）→ Ended。
   */
  private def runOneCall(
    seed: RunSeed[F],
    gates: DropGates[F],
    snap: HookSnapshot[F],
    call: ToolCall
  ): F[(ToolExecutionResult, Boolean)] =

    def finishWith(r: ToolExecutionResult, terminate: Boolean): F[(ToolExecutionResult, Boolean)] =
      seed.sink.emit(LoopEvent.ToolExecutionEnded(call.callId, r.output, r.isError)) *>
        observe(
          seed,
          HookPoint.ToolExecutionEnded,
          snap,
          gates,
          SiteEvents.ToolExecutionEndedInput(call.callId, r.output, r.isError).asJson
        )
          .as((r, terminate))

    /** tool.Post（#37/#49/#57/#69：结果改写链式 + Terminate 批停通道）——仅真执行过的调用。 */
    def postThenFinish(r: ToolExecutionResult): F[(ToolExecutionResult, Boolean)] =
      HookDispatch
        .dispatch(HookPoint.ToolPost, SiteEvents.ToolOutcome(call, r).asJson, snap, seed.sink, gates)
        .flatMap {
          case DispatchResult.Decided(HookEffect.Modify(v)) =>
            finishWith(decodeResult(v).getOrElse(r), terminate = false)
          case DispatchResult.Decided(HookEffect.Patch(d)) =>
            finishWith(
              decodeResult(SiteEvents.ToolOutcome(call, r).asJson.deepMerge(d)).getOrElse(r),
              terminate = false
            )
          case DispatchResult.Decided(HookEffect.Terminate(_)) =>
            finishWith(r, terminate = true)
          case _ => finishWith(r, terminate = false)
        }

    def executeThenFinish(c: ToolCall): F[(ToolExecutionResult, Boolean)] =
      val onDelta: Json => F[Unit] = partial =>
        seed.sink.emit(LoopEvent.ToolExecutionDelta(c.callId, partial)) *>
          observe(
            seed,
            HookPoint.ToolExecutionDelta,
            snap,
            gates,
            SiteEvents.ToolExecutionDeltaInput(c.callId, partial).asJson
          )
      seed.executor.execute(c, seed.signal, onDelta).attempt.flatMap {
        case Right(r) => postThenFinish(r)
        case Left(e: LoopError) => F.raiseError(e) // 引擎级错误透传（drive 统一回收为 Failed 收尾）
        case Left(e) =>
          // 灾难性失败收编为错误结果（ToolExecutor 契约；批停语义不由此通道触发）
          finishWith(
            ToolExecutionResult.error(s"executor failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"),
            terminate = false
          )
      }
    end executeThenFinish

    seed.sink.emit(LoopEvent.ToolExecutionStarted(call.callId, call.name)) *>
      observe(
        seed,
        HookPoint.ToolExecutionStarted,
        snap,
        gates,
        SiteEvents.ToolExecutionStartedInput(call.callId, call.name).asJson
      ) >>
      // 引擎侧原始参校验（pi「校验后 execute 前」——校验先行、hook 后置；无 spec=不可校验放行）
      (seed.tools.specOf(call.name) match
        case Some(spec) => spec.validateArgs(call.args).pure[F]
        case None => F.pure(Right(()))
      ).flatMap {
        case Left(err) =>
          finishWith(ToolExecutionResult.error(s"invalid arguments: $err"), terminate = false)
        case Right(()) =>
          HookDispatch
            .dispatch(HookPoint.ToolPre, SiteEvents.ToolPreInput(call).asJson, snap, seed.sink, gates)
            .flatMap {
              case DispatchResult.Decided(HookEffect.Block(reason, terminate)) =>
                finishWith(ToolExecutionResult.error(s"blocked: $reason"), terminate)
              case DispatchResult.Decided(HookEffect.Veto(reason)) =>
                finishWith(ToolExecutionResult.error(s"vetoed: $reason"), terminate = false)
              case DispatchResult.Decided(HookEffect.Terminate(reason)) =>
                finishWith(ToolExecutionResult.error(s"terminated: $reason"), terminate = true)
              case DispatchResult.Decided(HookEffect.Modify(newArgs)) =>
                // L-5（拍板=重校验）：改参必须过该工具 ToolSpec.validateArgs——坏参不落执行器
                val check: Either[String, Json] =
                  if HookPoint.ToolPre.revalidateModifiedArgs then
                    seed.tools.specOf(call.name) match
                      case Some(spec) => spec.validateArgs(newArgs).map(_ => newArgs)
                      case None => Right(newArgs) // 无 spec=不可校验（同权放行，Tools.scala 契约）
                  else Right(newArgs)
                check match
                  case Left(err) =>
                    seed.sink.emit(SitePayloads.guardError(HookPoint.ToolPre, "loop.revalidate", err)) *>
                      finishWith(ToolExecutionResult.error(s"hook-modified args rejected: $err"), terminate = false)
                  case Right(validArgs) => executeThenFinish(call.copy(args = validArgs))
              case _ => executeThenFinish(call)
            }
      }
  end runOneCall

  // ── 小件 ─────────────────────────────────────────────────────────────────

  /** 观察位单点（AllRun 腿；背压档由位点声明驱动——providerPort.Chunked=Dropping）。 */
  private def observe(
    seed: RunSeed[F],
    point: HookPoint,
    snap: HookSnapshot[F],
    gates: DropGates[F],
    input: Json
  ): F[Unit] =
    HookDispatch.dispatch(point, input, snap, seed.sink, gates).void

  private def drainSteering(seed: RunSeed[F], st: RState): F[RState] =
    seed.getSteeringMessages match
      case None => F.pure(st)
      case Some(f) => f.map(ms => ms.foldLeft(st)((s, m) => s.copy(ctx = s.ctx.appended(m))))

  private def effectiveSystem(messages: Vector[LoopMessage], overrideValue: Option[Json]): Option[Json] =
    overrideValue.orElse(messages.reverse.find(_.role == LoopMessageRole.System).map(_.content))

  /** provider 可重试腿：RetryPolicy 只数失败重投（None=单次不重试）；耗尽=硬退出错误。 */
  private def providerWithRetry(seed: RunSeed[F], req: ProviderRequest): F[Either[String, ProviderStream]] =
    def go(n: Int): F[Either[String, ProviderStream]] =
      seed.provider.stream(req, seed.signal).map(Right(_)).recoverWith { case e =>
        val maxAttempts = seed.retry.maxAttempts.getOrElse(1)
        if n < maxAttempts then go(n + 1)
        else F.pure(Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
      }
    go(1)

  private def decodeResult(v: Json): Option[ToolExecutionResult] =
    for
      output <- v.hcursor.get[Json]("output").toOption
      isError <- v.hcursor.get[Boolean]("isError").toOption
    yield ToolExecutionResult(output, isError)

end LoopRuntime
