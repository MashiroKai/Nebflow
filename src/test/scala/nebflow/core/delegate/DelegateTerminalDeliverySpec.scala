package nebflow.core.delegate

import cats.effect.IO
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.PathUtil

/**
 * delegate 终态交付回执 + 「父未确认」重投监测（诊断修复批 2026-10-24 ·
 * delegate-nebflow-52308ee4 完成结果投递丢失）的判红锚：
 *
 *  - [[DelegateDeliveryReceipts.needsRedelivery]]：超龄未确认判据——钉死本例形态
 *    「等待屏障清零（barrier 读数 0/0）但父会话未收到」：首投回执存在且
 *    `now - deliveredAt > grace` 且未 ack ⇒ 判真（与 barrier/held 读数正交）；
 *    已 ack / 未超龄 ⇒ 判假；
 *  - [[DelegateDeliveryReceipts.decide]]：父已删 ⇒ Drop；重投达上限 ⇒ Retain；
 *    否则 Redeliver；
 *  - 持久往返：record → find（payload 全量保真）→ ack（幂等零重写）→
 *    list/delete；`.receipt.json` 后缀不污染 DelegateRegistry.list()；
 *  - [[DelegateDeliveryReceipts.ackEvent]]：source=delegate × 终态词表 × 合法
 *    correlationId 才 ack；mail/非终态/无 correlationId ⇒ 零写。
 */
class DelegateTerminalDeliverySpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-delivery-receipts"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def receipt(
    id: String = "delegate-nebflow-52308ee4",
    deliveredAt: Long = 1000L,
    ackedAt: Option[Long] = None,
    redeliveries: Int = 0
  ): DelegateDeliveryReceipts.Receipt =
    DelegateDeliveryReceipts.Receipt(
      delegateId = id,
      parentSessionId = "d7c3cb54-dd90-4f3f-bbf6-e7898c425cff",
      eventType = "completed",
      payload = """"绘图批":\n## 完成报告……（终文全量）""",
      metadata = JsonObject.empty,
      deliveredAt = deliveredAt,
      ackedAt = ackedAt,
      redeliveries = redeliveries
    )

  // ── needsRedelivery：本例形态的机检读数 ────────────────────────────────

  test("needsRedelivery: 超龄未确认 = true（barrier 清零但父未收到的精确形态）") {
    // 本例实态钉死：delegate_report(finish) 已落盘、会话 Idle、barrier 0/0——
    // 但父会话从未收到终态；首投回执超龄未 ack 即该形态。
    assert(
      DelegateDeliveryReceipts.needsRedelivery(receipt(), now = 1000L + 10 * 60 * 1000L + 1, graceMs = 10 * 60 * 1000L)
    )
  }

  test("needsRedelivery: 未超龄 / 已确认 = false") {
    val grace = 10 * 60 * 1000L
    assert(
      !DelegateDeliveryReceipts.needsRedelivery(receipt(deliveredAt = 1000L), now = 1000L + grace, graceMs = grace)
    )
    assert(
      !DelegateDeliveryReceipts.needsRedelivery(
        receipt(ackedAt = Some(2000L)),
        now = 1000L + grace * 10,
        graceMs = grace
      )
    )
  }

  // ── decide：单条处置裁决 ────────────────────────────────────────────────

  test("decide: 父已删 = Drop；达上限 = Retain；否则 Redeliver") {
    import DelegateDeliveryReceipts.Decision.*
    assertEquals(DelegateDeliveryReceipts.decide(receipt(), parentExists = false), Drop)
    assertEquals(
      DelegateDeliveryReceipts
        .decide(receipt(redeliveries = DelegateDeliveryReceipts.MaxRedeliveries), parentExists = true),
      Retain
    )
    assertEquals(DelegateDeliveryReceipts.decide(receipt(), parentExists = true), Redeliver)
  }

  // ── 持久往返 ────────────────────────────────────────────────────────────

  test("record/find/list/ack/delete 往返：payload 全量保真、ack 幂等、与 registry 目录隔离") {
    val r = receipt()
    for
      _ <- DelegateDeliveryReceipts.record(r)
      found = DelegateDeliveryReceipts.find(r.delegateId)
      _ = assertEquals(found.map(_.payload), Some(r.payload))
      _ = assertEquals(found.map(_.metadata), Some(r.metadata))
      // ack 幂等：首 ack true，重复 ack false 且 ackedAt 不漂移
      a1 <- DelegateDeliveryReceipts.ack(r.delegateId, now = 5000L)
      a2 <- DelegateDeliveryReceipts.ack(r.delegateId, now = 9000L)
      _ = assert(a1 && !a2)
      _ = assertEquals(DelegateDeliveryReceipts.find(r.delegateId).flatMap(_.ackedAt), Some(5000L))
      _ = assertEquals(DelegateDeliveryReceipts.list().map(_.delegateId), List(r.delegateId))
      _ <- DelegateDeliveryReceipts.delete(r.delegateId)
      _ = assert(DelegateDeliveryReceipts.find(r.delegateId).isEmpty)
      // DelegateRegistry.list 不被 receipt 文件污染（解码失败静默跳过 + 空目录）
      _ = assert(DelegateRegistry.list().isEmpty)
    yield ()
    end for
  }

  test("ackEvent: 只认 source=delegate × 终态词表 × 合法 correlationId") {
    val terminal = AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "completed",
      payload = "p",
      metadata = JsonObject.empty,
      correlationId = Some("delegate-nebflow-52308ee4")
    )
    val mail = terminal.copy(source = "mail")
    val nonTerminal = terminal.copy(eventType = "progress")
    val noCorr = terminal.copy(correlationId = None)
    for
      _ <- DelegateDeliveryReceipts.record(receipt())
      _ <- DelegateDeliveryReceipts.ackEvent(terminal)
      _ = assert(DelegateDeliveryReceipts.find("delegate-nebflow-52308ee4").flatMap(_.ackedAt).isDefined)
      // 其余形态幂等零写（对已 ack 回执再投任何事件都不改 ackedAt 语义）
      _ <- DelegateDeliveryReceipts.ackEvent(mail)
      _ <- DelegateDeliveryReceipts.ackEvent(nonTerminal)
      _ <- DelegateDeliveryReceipts.ackEvent(noCorr)
      _ = assertEquals(DelegateDeliveryReceipts.list().length, 1)
    yield ()
  }

  // ── #886 完成回执重启去重批（2026-10-26）：重投件的 ack 身份归位 ──────────
  //
  // 实证链（10-25 23:15 重启后 canvas ×3 / console-card ×2 / imagegen ×3）：
  // 重投腿把 correlationId 改写为 `delivery-redelivery-<id>`，ackEvent 按改写 id
  // 去 find 永远查无回执 ⇒ 原回执永挂未确认 ⇒ sweep 每 grace（10min）拍重投一次
  // 直至上限——同一 [delegate-finish] 载荷重复到达根会话。修后：重投注入按
  // redeliveryOf / 前缀剥离归位**原回执 id**，一拍即 ack、重投链在第一拍终止。

  test("#886 ackableReceiptId: 重投件归位原回执 id（metadata redeliveryOf 优先、前缀剥离次之）") {
    val base = AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "completed",
      payload = "p",
      metadata = JsonObject.empty,
      correlationId = Some("delegate-nebflow-52308ee4")
    )
    val viaMeta = base.copy(
      correlationId = Some(s"${DelegateDeliveryReceipts.RedeliveryCorrelationPrefix}delegate-nebflow-52308ee4"),
      metadata = JsonObject("redeliveryOf" -> io.circe.Json.fromString("delegate-nebflow-52308ee4"))
    )
    val viaPrefix = base.copy(
      correlationId = Some(s"${DelegateDeliveryReceipts.RedeliveryCorrelationPrefix}delegate-nebflow-52308ee4")
    )
    assertEquals(DelegateDeliveryReceipts.ackableReceiptId(viaMeta), Some("delegate-nebflow-52308ee4"))
    assertEquals(DelegateDeliveryReceipts.ackableReceiptId(viaPrefix), Some("delegate-nebflow-52308ee4"))
    assertEquals(DelegateDeliveryReceipts.ackableReceiptId(base), Some("delegate-nebflow-52308ee4"))
    // 非终态 / 非 delegate 源：身份解析直接拒绝（与 ackEvent 词表闸一致）
    assertEquals(DelegateDeliveryReceipts.ackableReceiptId(viaMeta.copy(eventType = "progress")), None)
    assertEquals(DelegateDeliveryReceipts.ackableReceiptId(viaMeta.copy(source = "mail")), None)
  }

  test("#886 ackEvent 对重投件 ack 原回执——重投链第一拍终止（不重复轰炸）") {
    for
      _ <- DelegateDeliveryReceipts.record(receipt(ackedAt = None))
      redelivered = AgentCommand.ExternalEvent(
        source = "delegate",
        eventType = "completed",
        payload = """"绘图批":\n## 完成报告……（终文全量）""",
        metadata = JsonObject("redeliveryOf" -> io.circe.Json.fromString("delegate-nebflow-52308ee4")),
        correlationId = Some(s"${DelegateDeliveryReceipts.RedeliveryCorrelationPrefix}delegate-nebflow-52308ee4")
      )
      _ <- DelegateDeliveryReceipts.ackEvent(redelivered)
      acked = DelegateDeliveryReceipts.find("delegate-nebflow-52308ee4").flatMap(_.ackedAt)
      _ = assert(acked.isDefined, "redelivered-event injection must ack the ORIGINAL receipt")
      // 已 ack ⇒ 不再判超龄 ⇒ sweep 停投（重复链终止的机检等价）
      _ = assert(
        !DelegateDeliveryReceipts.needsRedelivery(
          DelegateDeliveryReceipts.find("delegate-nebflow-52308ee4").get,
          now = Long.MaxValue / 2,
          graceMs = 0L
        )
      )
    yield ()
  }

end DelegateTerminalDeliverySpec
