package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behavior, Behaviors}
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 后台作业死信**身份绑定**批（2026-11-03 · 作者报障逐字「第一个，后台任务的通知，
 * 应该是谁调用，就通知谁啊，这个现在没有做一个身份绑定吗？怎么失败了到处通知？」）。
 *
 * 实证病灶（11-03 10:50，jobId 40c10011「Re-download full Chinese PDF part1」）：
 * 作业属主 = Flow Map 节点会话 `node-338261c7`，其 `rootSessionId` = Nebula 根
 * `d7c3cb54`。旧 `BgTaskReaper.injectAlongChain` 投递面 = `sessionId ∪ rootSessionId`
 * ⇒ death-watch 死信同时落进**根会话**转录（控制台「BACKGROUND · SPIDER · FAILED」
 * 横幅，且归属段渲染成收件会话的项目 ⇒ 看起来是别人的事），根会话为此白烧一整轮。
 *
 * 修后判据（本 spec 三条机械锚）：
 *   ① 纯函数 [[BgTaskReaper.deadLetterTargets]]：属主恒在、**根会话永不出现**、
 *      属主不可达时**只**加直接父会话一跳（不溯根）；
 *   ② 真 actor 面「无关会话收不到死信 + 属主会话收到」：属主活 ⇒ 属主恰 1 件、
 *      根会话 0 件（改前红：根会话也收 1 件）；
 *   ③ 属主不可达 ⇒ 静默记账（只 WARN）+ **直接父会话恰 1 件**且标注 owner 身份
 *      （`ownerSessionId` / `ownerUnreachable`），根会话仍 0 件。
 */
class BgDeadLetterIdentitySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def withDataRoot[A](body: IO[A]): IO[A] =
    val prev = PathUtil.dataRoot
    val tmp = os.temp.dir()
    PathUtil.setDataRoot(tmp / "data")
    body.guarantee(IO { PathUtil.setDataRoot(prev); os.remove.all(tmp) }.attempt.void)

  private def row(job: String, owner: String, root: String): BgTaskRegistry.ActiveTask =
    BgTaskRegistry.ActiveTask(
      jobId = job,
      sessionId = owner,
      description = s"desc-$job",
      startedAtMs = System.currentTimeMillis(),
      kind = "local",
      rootSessionId = root
    )

  private def recordingRef(
    system: ActorSystem,
    name: String,
    sink: Ref[IO, List[AgentCommand]]
  ): IO[nebflow.actor.ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => sink.update(_ :+ cmd).as(loop))
    system.spawn(loop, name)

  /** 已死会话 ref：真 spawn 再 stop（registry 退出 ⇒ isAlive false）。 */
  private def deadRef(system: ActorSystem, name: String): IO[nebflow.actor.ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand](_ => IO.pure(loop))
    system.spawn(loop, name).flatMap(ref => system.stop(ref).as(ref))

  private val alwaysDead: BgTaskRegistry.ActiveTask => IO[BgTaskLiveness.Verdict] =
    _ => IO.pure(BgTaskLiveness.Dead("process dead"))

  private def eventsOf(cmds: List[AgentCommand]): List[AgentCommand.ExternalEvent] =
    cmds.collect { case e: AgentCommand.ExternalEvent => e }

  private def rec(sid: String, ref: nebflow.actor.ActorRef[AgentCommand], root: String, kind: AgentKind): AgentRecord =
    AgentRecord(sid, ref, kind, root, None)

  // ── ① 纯函数裁决面 ────────────────────────────────────────────────────

  test("① deadLetterTargets: 属主恒在；根会话永不出现；属主不可达时只加直接父会话一跳") {
    val r = row("j-1", "owner-sid", "root-sid")
    assertEquals(
      BgTaskReaper.deadLetterTargets(r, ownerAlive = true, parent = None),
      List("owner-sid"),
      "活属主 = 唯一收件人（根会话不在名单里）"
    )
    assertEquals(
      BgTaskReaper.deadLetterTargets(r, ownerAlive = false, parent = Some("parent-sid")),
      List("owner-sid", "parent-sid"),
      "死属主 ⇒ 属主 + 直接父会话一跳"
    )
    assertEquals(
      BgTaskReaper.deadLetterTargets(r, ownerAlive = false, parent = None),
      List("owner-sid"),
      "死属主且无父会话 ⇒ 只有属主（其余静默记账，绝不回退根会话）"
    )
    assert(
      !BgTaskReaper.deadLetterTargets(r, ownerAlive = false, parent = Some("root-sid")).drop(1).contains("owner-sid"),
      "sanity: 去重后不含重复属主"
    )
    // 根会话作为「父会话」被显式传入时也不得与属主同号重复，且列表仍不含多余项。
    assertEquals(
      BgTaskReaper.deadLetterTargets(r, ownerAlive = false, parent = Some("owner-sid")),
      List("owner-sid"),
      "自指父会话去重"
    )
    // 空属主（REST/ad-hoc 任务）：零收件人（只记账）。
    assertEquals(BgTaskReaper.deadLetterTargets(row("j-2", "", "root-sid"), ownerAlive = true, None), Nil)
  }

  // ── ② 无关会话收不到死信 + 属主会话收到 ──────────────────────────────

  test("② death-watch 死信只投属主会话——无关的根会话零收件（改前红：根会话也收 1 件）") {
    withDataRoot {
      val system = ActorSystem(s"bg-dead-letter-2-${scala.util.Random.nextInt(100000)}")
      val ownerSid = "node-spec-owner"
      val rootSid = "spec-root"
      for
        registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
        ownerSink <- Ref.of[IO, List[AgentCommand]](Nil)
        rootSink <- Ref.of[IO, List[AgentCommand]](Nil)
        ownerRef <- recordingRef(system, "owner-actor", ownerSink)
        rootRef <- recordingRef(system, "root-actor", rootSink)
        _ <- registry.update(
          _ + (ownerSid -> rec(ownerSid, ownerRef, rootSid, AgentKind.Flow)) +
            (rootSid -> rec(rootSid, rootRef, rootSid, AgentKind.Root))
        )
        _ <- BgTaskRegistry.register(
          "dl-2",
          ownerSid,
          "Re-download full Chinese PDF part1",
          "local",
          rootSessionId = rootSid
        )
        report <- BgTaskReaper.reapWith(Some(system), Some(registry), System.currentTimeMillis())(alwaysDead)
        _ <- IO.sleep(200.millis)
        ownerGot <- ownerSink.get
        rootGot <- rootSink.get
        rows <- BgTaskRegistry.allActiveTasks
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assertEquals(report.reapedJobIds, List("dl-2"), "收割照常发生")
        assertEquals(rows.map(_.jobId), Nil, "行已取走（等待集本拍即空）")
        val ownerEvents = eventsOf(ownerGot)
        assertEquals(ownerEvents.size, 1, "属主会话收到恰一条死信")
        assert(
          ownerEvents.head.payload.contains("Background task terminated"),
          s"属主收到的是 owner-lost 终态通知：${ownerEvents.head.payload.take(120)}"
        )
        assertEquals(
          ownerEvents.head.metadata("ownerSessionId").flatMap(_.asString),
          Some(ownerSid),
          "事件标注属主身份（身份绑定面）"
        )
        assertEquals(eventsOf(rootGot).size, 0, "🔴 无关的根会话**零收件**（作者报障「怎么失败了到处通知」的机械防线）")
      end for
    }
  }

  // ── ③ 属主不可达 ⇒ 静默记账 + 直接父会话一次性知情（带 owner 标注）──────

  test("③ 属主不可达 ⇒ 只投直接父会话一次并标注 owner；根会话仍零收件") {
    withDataRoot {
      val system = ActorSystem(s"bg-dead-letter-3-${scala.util.Random.nextInt(100000)}")
      val ownerSid = "delegate-nebflow-deadspec"
      val parentSid = "spec-parent"
      val rootSid = "spec-root-3"
      for
        registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
        parentSink <- Ref.of[IO, List[AgentCommand]](Nil)
        rootSink <- Ref.of[IO, List[AgentCommand]](Nil)
        ownerRef <- deadRef(system, "dead-owner-actor")
        parentRef <- recordingRef(system, "parent-actor", parentSink)
        rootRef <- recordingRef(system, "root-actor-3", rootSink)
        // 属主是 delegate ⇒ 直接父会话 = DelegateRegistry.parentSessionId
        _ <- IO {
          DelegateRegistry.put(
            DelegateRegistry.Record(
              id = ownerSid,
              executor = "nebflow",
              cwd = os.temp.dir().toString,
              project = None,
              taskId = None,
              parentSessionId = parentSid,
              title = "recon dead-letter spec",
              createdAt = 1L
            )
          )
          ()
        }
        _ <- registry.update(
          _ + (ownerSid -> rec(ownerSid, ownerRef, rootSid, AgentKind.Delegate)) +
            (parentSid -> rec(parentSid, parentRef, rootSid, AgentKind.Root)) +
            (rootSid -> rec(rootSid, rootRef, rootSid, AgentKind.Root))
        )
        _ <- BgTaskRegistry.register("dl-3", ownerSid, "stuck recon fetch", "local", rootSessionId = rootSid)
        // Isolate the process-global activator from unrelated WebSocketRoutes fixtures.
        // UnknownSession models an absent owner without claiming persistence or activation.
        previousActivator <- IO(BgSessionActivator.get)
        report <- (IO(
          BgSessionActivator.install(
            new BgSessionActivator.Face:
              def activate(
                sessionId: String,
                event: AgentCommand.ExternalEvent
              ): IO[BgSessionActivator.ActivationResult] =
                IO.pure(BgSessionActivator.UnknownSession)
          )
        ) *> BgTaskReaper.reapWith(Some(system), Some(registry), System.currentTimeMillis())(alwaysDead))
          .guarantee(IO(previousActivator.fold(BgSessionActivator.clear())(BgSessionActivator.install)))
        _ <- IO.sleep(200.millis)
        parentGot <- parentSink.get
        rootGot <- rootSink.get
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assertEquals(report.reapedJobIds, List("dl-3"))
        val parentEvents = eventsOf(parentGot)
        assertEquals(parentEvents.size, 1, "上级知情 = 直接父会话**恰一条**（不刷屏、不广播）")
        assert(
          parentEvents.head.payload.contains("owner-unreachable"),
          s"一跳载荷标注 owner 不可达：${parentEvents.head.payload.take(160)}"
        )
        assert(parentEvents.head.payload.contains(ownerSid), "载荷点名属主会话 id")
        assertEquals(
          parentEvents.head.metadata("ownerSessionId").flatMap(_.asString),
          Some(ownerSid),
          "元数据标注 owner 身份"
        )
        assertEquals(
          parentEvents.head.metadata("ownerUnreachable").flatMap(_.asBoolean),
          Some(true),
          "元数据标注 owner 不可达"
        )
        assertEquals(eventsOf(rootGot).size, 0, "根会话仍零收件（死信绝不广播）")
      end for
    }
  }

end BgDeadLetterIdentitySpec
