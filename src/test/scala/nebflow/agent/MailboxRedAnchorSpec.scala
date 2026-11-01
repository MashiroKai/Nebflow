package nebflow.agent

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorContext, ActorSystem, AgentCommand, AgentState, Behavior}
import nebflow.actor.{messages, withMessages, withOutstandingSubagentResults}
import nebflow.core.mail.SessionMailbox
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * **判红锚 spec（10-31 事故重放）**：父会话冻结窗内 delegate 完工 → 邮箱条目在盘
 * → **解冻首拍（W4 resumeSweep）必注入完工原文**。
 *
 * 10-31 事故链（设计 §1）：冻结吞投递 → 投递标记已写/父未消费/watchdog 重投被同
 * 键判重压制 → 完工原文永久丢失。本 spec 钉死迁移后的不变式：
 *
 *   1. 冻结窗内的 `mailOnce` 条目在解冻首拍被 `resumeSweep` 取走并装配进返回
 *      state 的消息（payload 含完工原文——"锚"本身）；
 *   2. taken 残差（取走后崩）在解冻首拍重注（≤1 重复零丢失）；
 *   3. **零新回执、零旧标记**：全程 `sessions/<sid>/delivered-receipts.json`
 *      （[[nebflow.core.processor.DeliveredReceiptLedger]] 旧投递标记面）与
 *      delegates 目录下的 `<id>.receipt.json`
 *      （[[nebflow.core.delegate.DelegateDeliveryReceipts]]）计数不变——邮箱件
 *      永不经过旧链标记/ack 面；
 *   4. 屏障会计在解冻拍恰一次扣减（outstanding 1 → 0）。
 *
 * 执行形态：探针 actor 的 receive 内拿真 [[ActorContext]] 跑 `resumeSweep`
 * （visibility 的 emitStream 需要 ctx.forkTurn）——LLM 零调用（resumeSweep 只装
 * 配 state，不触 pipeLlmCall）。
 */
class MailboxRedAnchorSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-mailbox-red-anchor"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)

  override def beforeAll(): Unit =
    nebflow.ForceRemove.removeAll(tempRoot)
    os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    ()

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def evt(
    payload: String,
    eventType: String = "completed",
    source: String = "delegate",
    identity: String
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = source,
      eventType = eventType,
      payload = payload,
      metadata = JsonObject("taskId" -> identity.asJson),
      correlationId = Some(identity)
    )

  /** 旧标记/回执面的读数（前后对照 = 零新增）。 */
  private def legacyMarkerCount(sid: String): IO[(Boolean, Int)] =
    IO.blocking {
      val ledger = tempRoot / "sessions" / sid / "delivered-receipts.json"
      val delegates = tempRoot / "delegates"
      val receipts =
        if !os.exists(delegates) then 0
        else os.list(delegates).count(p => p.last.endsWith(".receipt.json"))
      (os.exists(ledger), receipts)
    }

  private val agentDef = nebflow.actor.AgentDef(name = "spec-host", description = "red anchor host")

  /**
   * 两段式 ctx 捕获：探针 receive 只做 `gate.complete(ctx)` 立即返回（不在
   * receive 执行体内跑 f——resumeSweep 的 forkTurn/盘读链挂在 receive 完成语义
   * 下会与 actor turn 生命周期互等）；ctx 是普通对象（self path + Ref 系），主
   * 线程持有后直接调用 f，forkTurn 的 fiber start 与调用线程无关。
   */
  private def runInActorContext[A](system: ActorSystem)(f: ActorContext[AgentCommand] => IO[A]): IO[A] =
    Deferred[IO, ActorContext[AgentCommand]].flatMap { gate =>
      system
        .spawn(
          new Behavior[AgentCommand]:
            def receive(ctx: ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
              msg match
                case AgentCommand.MailboxArrived => gate.complete(ctx).void.as(this)
                case _                           => IO.pure(this)
          ,
          s"red-anchor-${scala.util.Random.nextInt(100000)}"
        )
        .flatMap(ref => (ref ! AgentCommand.MailboxArrived).void)
        .flatMap(_ => gate.get)
        .timeout(30.seconds)
        .adaptError { case t => new RuntimeException(s"probe ctx capture failed: ${t.getMessage}") }
        .flatMap(ctx => f(ctx))
    }

  // ══ 锚①：冻结窗内完工条目 → 解冻首拍注入完工原文 ═══════════════════════

  test("ANCHOR: completion mailed during the frozen window reaches the parent VERBATIM at the first unfreeze beat") {
    val system = ActorSystem(s"red-anchor-1-${scala.util.Random.nextInt(100000)}")
    val sid = "parent-frozen-1"
    val finalText = "FINAL RESULT: migration batch P2 complete, all specs green."
    for
      resources <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- resources.sessionStore.getOrCreateSession(sid, "spec parent")
      before <- legacyMarkerCount(sid)
      // 冻结窗内：delegate 完工条目落父会话邮箱（此刻父 frozen，无人消费）。
      r <- SessionMailbox.mailOnce(
        sid,
        evt(finalText, identity = "delegate-x-abc12345"),
        kind = "delegate-terminal",
        identity = "delegate-x-abc12345"
      )
      // 解冻首拍：W4 resumeSweep（AgentFrozen 三条恢复腿的公共体）。
      frozenState = AgentState(sessionId = Some(sid), sessionName = Some("spec parent"))
        .withOutstandingSubagentResults(1)
      unfrozen <- runInActorContext(system)(ctx => MailboxDrain.resumeSweep(agentDef, resources, 0, frozenState)(using ctx))
      after <- legacyMarkerCount(sid)
      pend <- SessionMailbox.scanPending(sid)
      taken <- SessionMailbox.scanTaken(sid)
      tomb <- SessionMailbox.deliveredContains(sid, Set("delegate-x-abc12345"))
    yield
      assert(
        r.isInstanceOf[SessionMailbox.MailResult.Enqueued],
        s"entry must be on disk the moment it is mailed, got $r"
      )
      val injected = unfrozen.messages.last
      assert(
        clue(injected.textContent).contains(finalText),
        "10-31 anchor: the first unfreeze beat MUST inject the completion verbatim"
      )
      assertEquals(clue(injected.source), Some("delegate"), "single delegate event keeps its marker")
      assertEquals(
        clue(unfrozen.execution.outstandingSubagentResults),
        0,
        "take-time barrier accounting: 1 outstanding → 0, exactly once"
      )
      assertEquals(clue(pend), Nil, "box fully consumed at the unfreeze beat")
      assertEquals(clue(taken), Nil, "unlink = delivered (physical fact)")
      assertEquals(clue(tomb), Set("delegate-x-abc12345"), "consumption tombstone recorded")
      assertEquals(clue(after), clue(before), "ZERO new legacy markers/receipts — the mailbox path never touches them")
      assertEquals(clue(after._1), false, "legacy delivered-receipts ledger NEVER created by the mailbox path")
  }

  // ══ 锚②：取走后崩（taken 残差）→ 解冻首拍重注 ≤1 重复零丢失 ═════════════

  test("ANCHOR: taken residual (crash between take and inject) is replayed at the unfreeze beat, payload intact") {
    val system = ActorSystem(s"red-anchor-2-${scala.util.Random.nextInt(100000)}")
    val sid = "parent-frozen-2"
    val payload = "PARTIAL RESULT from a delegate killed mid-injection."
    for
      resources <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- resources.sessionStore.getOrCreateSession(sid, "spec parent")
      // 模拟：上一进程取走（rename 进 taken/）后、注入前 kill -9。
      _ <- SessionMailbox.mailOnce(
        sid,
        evt(payload, identity = "delegate-y-def67890"),
        kind = "delegate-terminal",
        identity = "delegate-y-def67890"
      )
      pend <- SessionMailbox.scanPending(sid)
      _ <- SessionMailbox.take(sid, pend)
      frozenState = AgentState(sessionId = Some(sid), sessionName = Some("spec parent"))
      unfrozen <- runInActorContext(system)(ctx => MailboxDrain.resumeSweep(agentDef, resources, 0, frozenState)(using ctx))
      taken <- SessionMailbox.scanTaken(sid)
    yield
      assert(
        clue(unfrozen.messages.last.textContent).contains(payload),
        "taken residual replays into the unfreeze turn — zero loss"
      )
      assertEquals(clue(taken), Nil, "resumeSweep unlinks after a successful replay")
  }

  // ══ 锚③：空箱解冻 = 逐字现状（零消息零会计）════════════════════════════

  test("ANCHOR: empty box at unfreeze returns the frozen state VERBATIM (no phantom message, no accounting)") {
    val system = ActorSystem(s"red-anchor-3-${scala.util.Random.nextInt(100000)}")
    val sid = "parent-frozen-3"
    for
      resources <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- resources.sessionStore.getOrCreateSession(sid, "spec parent")
      frozenState = AgentState(sessionId = Some(sid), sessionName = Some("spec parent"))
        .withMessages(List(Message(MessageRole.User, Left("history"))))
        .withOutstandingSubagentResults(2)
      unfrozen <- runInActorContext(system)(ctx => MailboxDrain.resumeSweep(agentDef, resources, 0, frozenState)(using ctx))
    yield
      assertEquals(clue(unfrozen.messages.map(_.textContent)), List("history"), "no phantom injection")
      assertEquals(clue(unfrozen.execution.outstandingSubagentResults), 2, "zero accounting on an empty box")
  }
end MailboxRedAnchorSpec
