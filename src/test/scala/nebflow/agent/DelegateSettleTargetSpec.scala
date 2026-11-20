package nebflow.agent

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorPath, AgentRecord, ActorRef, AgentCommand, AgentEvent, AgentKind}

import scala.concurrent.duration.FiniteDuration

/**
 * **dispatcher 终态批 2026-11-05**（作者报障「dispatcher 模式 delegate 无法正常结束」）
 * 的判据锚：**受监督会话的完成信号不得取决于唤醒者有没有带 `replyTo`**。
 *
 * ==改前病灶（现场实证，见本批报告）==
 * `completionTargets = replyTo.toList ++ owedCompletion`：
 *   - base-moved / flow-notify / dispatch-notify 走 `AgentCommand.ImmediateInput`（该命令
 *     **没有 replyTo 槽**），idle 腿转成 `UserInput(replyTo = None, …)` ⇒ 该回合
 *     `replyTo = None`；
 *   - 上一回合收尾已把 `owedCompletion` 清空 ⇒ 债也为空。
 * ⇒ 收尾时 `AgentEvent.Completed` 一个都不发 ⇒ `BackoffSupervisor` 收不到完成事件 ⇒
 * `DelegateTerminal.onCompleted`（终态判据本体）从不运行 ⇒ 会话挂在 Idle，被下一次
 * base-moved 再唤醒一轮，永不收敛。
 *
 * ==改后判据==
 * 目标为空 ⇒ 回查统一注册表补**本会话自己的监督者**（`AgentRecord.supervisorRef`）；
 * 根会话（无监督者）逐字不变。本 spec 四例钉住这条线。
 */
class DelegateSettleTargetSpec extends CatsEffectSuite:

  /** 记录臂：收到的 AgentEvent 全落 Ref（证明「监督者真会收到 Completed」）。 */
  private final class RecordingSup(seed: Ref[IO, List[AgentEvent]]) extends ActorRef[AgentEvent]:
    val path: ActorPath = ActorPath("recording-sup", Nil)
    def !(msg: AgentEvent): IO[Unit] = seed.update(_ :+ msg)

    def ?[Reply](makeMsg: ActorRef[Reply] => AgentEvent, timeout: Option[FiniteDuration]): IO[Reply] =
      IO.raiseError(new UnsupportedOperationException("spec recording ref does not answer asks"))

  /** 子会话臂（本 spec 从不往里投件；只需是一个合法 ref）。 */
  private final class DeadChild extends ActorRef[AgentCommand]:
    val path: ActorPath = ActorPath("dead-child", Nil)
    def !(msg: AgentCommand): IO[Unit] = IO.unit

    def ?[Reply](makeMsg: ActorRef[Reply] => AgentCommand, timeout: Option[FiniteDuration]): IO[Reply] =
      IO.raiseError(new UnsupportedOperationException("spec child ref does not answer asks"))

  private def record(
    sessionId: String,
    kind: AgentKind,
    supervisor: Option[ActorRef[AgentEvent]]
  ): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      ref = new DeadChild,
      kind = kind,
      rootSessionId = "root-x",
      supervisorRef = supervisor
    )

  private def rig(delegateId: String) =
    for
      got <- Ref.of[IO, List[AgentEvent]](Nil)
      sup = new RecordingSup(got)
      reg <- Ref.of[IO, Map[String, AgentRecord]](Map(delegateId -> record(delegateId, AgentKind.Delegate, Some(sup))))
    yield (got, sup, reg)

  // ── ① 纯判据：既有目标优先（零行为变化）────────────────────────────────
  test("pickSettleTargets: 既有完成目标非空 ⇒ 原样返回（Mail / merge-turn / standby-care 腿逐字不变）") {
    val got = new java.util.concurrent.atomic.AtomicInteger(0)
    val a: ActorRef[AgentEvent] = new ActorRef[AgentEvent]:
      val path = ActorPath("a", Nil)
      def !(m: AgentEvent) = IO.unit
      def ?[R](f: ActorRef[R] => AgentEvent, t: Option[FiniteDuration]) =
        IO.raiseError(new UnsupportedOperationException)
    val b: ActorRef[AgentEvent] = new ActorRef[AgentEvent]:
      val path = ActorPath("b", Nil)
      def !(m: AgentEvent) = IO.unit
      def ?[R](f: ActorRef[R] => AgentEvent, t: Option[FiniteDuration]) =
        IO.raiseError(new UnsupportedOperationException)
    assertEquals(AgentFinishTurn.pickSettleTargets(List(a), Some(b)), List(a))
    assertEquals(got.get(), 0)
  }

  // ── ② 纯判据：无目标 ⇒ 补监督者（本批的钉死点）────────────────────────
  test("pickSettleTargets: replyTo 缺席（base-moved 唤醒腿）⇒ 仍落到本会话监督者") {
    val sup: ActorRef[AgentEvent] = new ActorRef[AgentEvent]:
      val path = ActorPath("sup", Nil)
      def !(m: AgentEvent) = IO.unit
      def ?[R](f: ActorRef[R] => AgentEvent, t: Option[FiniteDuration]) =
        IO.raiseError(new UnsupportedOperationException)
    assertEquals(AgentFinishTurn.pickSettleTargets(Nil, Some(sup)), List(sup))
  }

  // ── ③ 端到端读面：无 replyTo 的回合收尾 ⇒ 监督者真收到 Completed ────────
  test("resolveSettleTargets: 无 replyTo 的 delegate 回合 ⇒ 目标 = supervisorRef，Completed 真抵达监督者") {
    val delegateId = "delegate-nebflow-settletarget"
    rig(delegateId).flatMap { case (got, sup, reg) =>
      for
        targets <- AgentFinishTurn.resolveSettleTargets(reg, Some(delegateId), Nil)
        _ = assertEquals(targets, List[ActorRef[AgentEvent]](sup), "无 replyTo ⇒ 必须补上本会话监督者（改前为空 = 零发信）")
        _ <- targets.traverse_(_ ! AgentEvent.Completed(delegateId, Nil))
        recv <- got.get
      yield assertEquals(recv.collect { case AgentEvent.Completed(sid, _) => sid }, List(delegateId))
    }
  }

  // ── ④ 零行为变化：根会话（无监督者）与未知会话仍为空 ────────────────────
  test("resolveSettleTargets: 根会话 / 未注册会话 ⇒ 目标仍为空（无监督者不得凭空造一个）") {
    for
      rootReg <- Ref.of[IO, Map[String, AgentRecord]](
        Map("root-session" -> record("root-session", AgentKind.Root, None))
      )
      asRoot <- AgentFinishTurn.resolveSettleTargets(rootReg, Some("root-session"), Nil)
      _ = assertEquals(asRoot, List.empty[ActorRef[AgentEvent]])
      unknownReg <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
      asUnknown <- AgentFinishTurn.resolveSettleTargets(unknownReg, Some("delegate-ghost"), Nil)
      _ = assertEquals(asUnknown, List.empty[ActorRef[AgentEvent]])
      noSid <- AgentFinishTurn.resolveSettleTargets(unknownReg, None, Nil)
    yield assertEquals(noSid, List.empty[ActorRef[AgentEvent]])
  }
end DelegateSettleTargetSpec
