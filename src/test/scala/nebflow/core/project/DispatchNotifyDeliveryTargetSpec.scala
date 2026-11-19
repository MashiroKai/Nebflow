package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * P0「dispatch-notify 投递地址 = 链属主 delegate」验收（RCA
 * `docs/20261102_200551_dispatcher-node-failure-notification-rca__delegate-nebflow-dfab3d21.md` §7-P0）。
 *
 * 三条契约的机械判据（直驱 [[DispatchNotify]]，窗口关闭 = 确定性）：
 *  - R1 缺省路径（`deliveryTarget = None`）：逐字节旧行为——投 `trigger`、恒 `markSent`
 *    （notifySentAt 非空）、既有 `triggered: failed → dispatcher (…)` 审计行照写、
 *    **零** parked/delivery 新行。
 *  - R2 目标 live（探针 true）：投探针、`markSent`、既有审计行 + 一条
 *    `dispatch-notify-delivery`（`outcome=delivered`）；`trigger` 腿零调用。
 *  - R3 目标不 live（探针 false）：**不 markSent**（notifySentAt 保持空 = 留在
 *    [[DispatchNotify.redeliver]] 候选集）、`dispatch-notify-parked`（`outcome=parked`）、
 *    既有「→ dispatcher」行零写入、`trigger`（项目分发器腿）零调用 = 不旁路。
 */
class DispatchNotifyDeliveryTargetSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def failureNode(id: String, name: String): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      status = NodeLifecycle.Failed,
      result = Some("boom: spec fixture failure"),
      out = List(OutEdge.root),
      createdAt = 1_700_000_000_000L,
      completedAt = Some(1_700_000_001_000L)
    )

  private case class Rig(
    store: FlowMapStore,
    dn: DispatchNotify,
    triggered: Ref[IO, List[String]],
    delivered: Ref[IO, List[String]],
    ws: String
  )

  private def mkRig(
    name: String,
    target: Option[DispatchNotify.DeliveryTarget],
    live: Boolean
  ): IO[Rig] =
    val ws = os.temp.dir(prefix = s"dn-target-$name") / "ws"
    for
      _ <- IO(os.makeDir.all(ws))
      store <- FlowMapStore.open(s"dn-target-$name", ws.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      delivered <- Ref.of[IO, List[String]](Nil)
      dn = new DispatchNotify(
        store,
        ws.toString,
        s"dn-target-$name",
        escalate = (_: String, _: String) => IO.unit,
        emitUpdated = (_: NodeDef) => IO.unit,
        trigger = (t: String) => triggered.update(_ :+ t),
        failedBudgetMax = 1000,
        failedWindowThreshold = 1000,
        windowMs = 0L,
        deliveryTarget = target.map(t =>
          DispatchNotify.DeliveryTarget(t.label, text => delivered.update(_ :+ text) *> t.deliver(text).map(_ && live))
        )
      )
      _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-fail" -> failureNode("n-fail", "FAIL")))).void
    yield Rig(store, dn, triggered, delivered, ws.toString)

  private def readEvents(ws: String): IO[List[String]] =
    IO.blocking {
      val p = os.Path(ws, PathUtil.dataRoot) / ".nebflow" / FlowMapEventLog.FileName
      if os.exists(p) then os.read.lines(p).toList else Nil
    }

  test("R1 default path (deliveryTarget=None): unchanged legacy semantics — trigger fired, notifySentAt set, verbatim audit line, no new rows") {
    for
      rig <- mkRig("default", None, live = true)
      _ <- rig.dn.notifyTerminal(failureNode("n-fail", "FAIL"), NotifyReason.Failed)
      marker <- rig.store.getNode("n-fail").map(_.flatMap(_.notifySentAt))
      triggered <- rig.triggered.get
      events <- readEvents(rig.ws)
      delivered <- rig.delivered.get
    yield
      assertEquals(triggered.size, 1, s"R1: the project-dispatcher trigger leg must fire once — got $triggered")
      assert(marker.isDefined, "R1: the default path must still markSent (notifySentAt set)")
      assert(
        events.exists(_.contains("triggered: failed → dispatcher")),
        s"R1: the legacy audit line must stay verbatim — got $events"
      )
      assert(
        !events.exists(_.contains(DispatchNotify.ParkedEventType)) &&
          !events.exists(_.contains(DispatchNotify.DeliveryEventType)),
        s"R1: the default path must write zero new delivery/parked rows — got $events"
      )
      assertEquals(delivered, Nil, "R1: no delivery probe on the default path")
  }

  test("R2 creator live (probe=true): delivered to the delegate target, markSent, legacy line + dispatch-notify-delivery") {
    for
      rig <- mkRig(
        "live",
        Some(DispatchNotify.DeliveryTarget(DispatchNotify.delegateCreatorLabel("delegate-x"), _ => IO.pure(true))),
        live = true
      )
      _ <- rig.dn.notifyTerminal(failureNode("n-fail", "FAIL"), NotifyReason.Failed)
      marker <- rig.store.getNode("n-fail").map(_.flatMap(_.notifySentAt))
      events <- readEvents(rig.ws)
      delivered <- rig.delivered.get
      triggered <- rig.triggered.get
    yield
      assertEquals(delivered.size, 1, "R2: the delegate delivery probe must be called once")
      assert(marker.isDefined, "R2: a delivered notification must markSent")
      assert(
        events.exists(_.contains("triggered: failed → dispatcher")),
        s"R2: the legacy audit line must survive — got $events"
      )
      assert(
        events.exists(l => l.contains(DispatchNotify.DeliveryEventType) && l.contains("outcome=delivered")),
        s"R2: a dispatch-notify-delivery row is expected — got $events"
      )
      assert(
        events.exists(_.contains("target=delegate-creator:delegate-x")),
        s"R2: the audit row must name the delegate creator target — got $events"
      )
      assertEquals(triggered, Nil, "R2: the project-dispatcher trigger leg must NOT be used when a target is set")
  }

  test("R3 creator not live (probe=false): PARKED — no markSent, parked audit, zero bypass to the project dispatcher") {
    for
      rig <- mkRig(
        "parked",
        Some(DispatchNotify.DeliveryTarget(DispatchNotify.delegateCreatorLabel("delegate-x"), _ => IO.pure(false))),
        live = false
      )
      _ <- rig.dn.notifyTerminal(failureNode("n-fail", "FAIL"), NotifyReason.Failed)
      marker <- rig.store.getNode("n-fail").map(_.flatMap(_.notifySentAt))
      events <- readEvents(rig.ws)
      triggered <- rig.triggered.get
      delivered <- rig.delivered.get
      // 仍在补投候选集（redeliver 会再次命中同一节点）
      again <- rig.dn.redeliver()
      deliveredAfter <- rig.delivered.get
    yield
      assertEquals(marker, None, "R3: a parked notification must NOT set notifySentAt (stays a redelivery candidate)")
      assert(
        events.exists(l => l.contains(DispatchNotify.ParkedEventType) && l.contains("outcome=parked")),
        s"R3: a dispatch-notify-parked row is expected — got $events"
      )
      assert(
        !events.exists(_.contains("triggered: failed → dispatcher")),
        s"R3: no '→ dispatcher' row may be written on the parked branch — got $events"
      )
      assertEquals(triggered, Nil, "R3: zero bypass — the project dispatcher leg must never be called")
      assertEquals(again, 1, "R3: the parked node stays in the redeliver candidate set (at-least-once)")
      assert(
        delivered.size >= 1 && deliveredAfter.size > delivered.size,
        s"R3: the delivery probe must re-run on the redelivery scan — first=${delivered.size} after=${deliveredAfter.size}"
      )
  }

  test("delivery exception stays unmarked and is retried after recovery") {
    for
      available <- Ref.of[IO, Boolean](false)
      target = DispatchNotify.DeliveryTarget("creator", _ => available.get.flatMap {
        case true => IO.pure(true)
        case false => IO.raiseError(new RuntimeException("receiver write failed"))
      })
      rig <- mkRig("exception", Some(target), live = true)
      _ <- rig.dn.notifyTerminal(failureNode("n-fail", "FAIL"), NotifyReason.Failed)
      before <- rig.store.getNode("n-fail")
      _ <- available.set(true)
      count <- rig.dn.redeliver()
      after <- rig.store.getNode("n-fail")
      calls <- rig.delivered.get
    yield
      assertEquals(before.flatMap(_.notifySentAt), None)
      assertEquals(count, 1)
      assert(after.flatMap(_.notifySentAt).isDefined)
      assertEquals(calls.size, 2)
  }

  test("empty recorded results are redelivered; absent results are not") {
    for
      rig <- mkRig("empty", Some(DispatchNotify.DeliveryTarget("creator", _ => IO.pure(true))), live = true)
      _ <- rig.store.mutate(s => s.copy(nodes = Map(
        "empty" -> failureNode("empty", "EMPTY").copy(result = Some("")),
        "blank" -> failureNode("blank", "BLANK").copy(result = Some("  \n")),
        "absent" -> failureNode("absent", "ABSENT").copy(result = None)
      )))
      count <- rig.dn.redeliver()
      snapshot <- rig.store.snapshot
    yield
      assertEquals(count, 2)
      assert(snapshot.nodes("empty").notifySentAt.isDefined)
      assert(snapshot.nodes("blank").notifySentAt.isDefined)
      assertEquals(snapshot.nodes("absent").notifySentAt, None)
  }

  test("legacy trigger exception does not acknowledge the result") {
    val ws = os.temp.dir(prefix = "dn-trigger-error")
    for
      store <- FlowMapStore.open("trigger-error", ws.toString)
      available <- Ref.of[IO, Boolean](false)
      dn = new DispatchNotify(
        store, ws.toString, "trigger-error",
        escalate = (_, _) => IO.unit,
        emitUpdated = _ => IO.unit,
        trigger = _ => available.get.flatMap {
          case true => IO.unit
          case false => IO.raiseError(new RuntimeException("trigger failed"))
        },
        failedBudgetMax = 1000, failedWindowThreshold = 1000, windowMs = 0L
      )
      _ <- store.mutate(s => s.copy(nodes = Map("n-fail" -> failureNode("n-fail", "FAIL"))))
      _ <- dn.notifyTerminal(failureNode("n-fail", "FAIL"), NotifyReason.Failed)
      before <- store.getNode("n-fail")
      _ <- available.set(true)
      _ <- dn.redeliver()
      after <- store.getNode("n-fail")
    yield
      assertEquals(before.flatMap(_.notifySentAt), None)
      assert(after.flatMap(_.notifySentAt).isDefined)
  }

end DispatchNotifyDeliveryTargetSpec
