package nebflow.core.processor

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * ExternalEvent intake 身份判重（投递可靠性批 2026-10-29 · 作者令「不过时：
 * 消息按 (sessionId, kind, 幂等键) 去重；cancel 回执必须单发」）：
 *
 *  - ① **identityKeys 身份键**：raw correlationId ∪ delegate 终态回执归位 id
 *    （redeliveryOf 元数据优先、`delivery-redelivery-<id>` 前缀剥离次之）——
 *    重投件与原件同一身份（2026-10-29 21:49→21:59 cancel 回执双投的判据面）；
 *  - ② **isDuplicateIdentity**：身份键撞已投标记集 = 重复；撞待发队列里任一件
 *    = 重复（忙父队列滞留原件 + sweep 重投改写 id 的双投形态）；无身份键恒放行
 *    （既有纪律：同文多次通知合法，零回退）；
 *  - ③ **partitionDelivered 升级**：重投件按归位 id 命中已投标记被滤（重启重放
 *    闸不再复活原件已投递过的重投件）；
 *  - ④ **intakeAccepts fail-open**：台账读失败 = 放行（簿记面故障绝不丢件）。
 */
class ExternalEventIntakeDedupSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-intake-dedup"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def ev(
    correlationId: Option[String],
    source: String = "delegate",
    eventType: String = "completed",
    metadata: JsonObject = JsonObject.empty
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = source,
      eventType = eventType,
      payload = "payload",
      metadata = metadata,
      correlationId = correlationId
    )

  private def redeliveredOf(id: String): AgentCommand.ExternalEvent =
    ev(
      correlationId = Some(s"delivery-redelivery-$id"),
      metadata = JsonObject("redeliveryOf" -> id.asJson)
    )

  test("① identityKeys: a redelivered event resolves to its ORIGINAL receipt id (same identity as the first copy)") {
    val original = ev(Some("delegate-nebflow-x"))
    val redelivery = redeliveredOf("delegate-nebflow-x")
    assertEquals(
      DeliveredReceiptLedger.identityKeys(original),
      List("delegate-nebflow-x"),
      "the original carries its raw correlationId"
    )
    val ids = DeliveredReceiptLedger.identityKeys(redelivery)
    assert(ids.contains("delegate-nebflow-x"), s"the redelivery must resolve to the original id, got: $ids")
    assert(ids.contains("delivery-redelivery-delegate-nebflow-x"), "the raw rewritten id stays in the key set")
    // 非终态事件（background-task 等）：归位不适用，raw id 即身份
    assertEquals(
      DeliveredReceiptLedger.identityKeys(ev(Some("bg-job-1"), source = "background-task", eventType = "completed")),
      List("bg-job-1"),
      "non-delegate-terminal events keep the raw-id-only identity"
    )
    // 无 correlationId = 无身份键（永放行）
    assertEquals(DeliveredReceiptLedger.identityKeys(ev(None)), Nil, "no stable id = no identity keys")
  }

  test("② isDuplicateIdentity: delivered-mark hit, pending-queue hit, and the no-id pass-through") {
    val original = ev(Some("d-1"))
    val redelivery = redeliveredOf("d-1")
    // 已投标记含原件 id（注入腿 markDelivered(identityKeys) 的盘面）⇒ 重投件判重
    assertEquals(
      DeliveredReceiptLedger.isDuplicateIdentity(Set("d-1"), Nil, redelivery),
      true,
      "a redelivery whose ORIGINAL is already delivered-marked is a duplicate (cancel-receipt single-send)"
    )
    // 原件滞留待发队列（忙父）⇒ 重投件判重（21:49→21:59 双投形态）
    assertEquals(
      DeliveredReceiptLedger.isDuplicateIdentity(Set.empty, List(original), redelivery),
      true,
      "a redelivery while the original still sits in pendingEvents is a duplicate"
    )
    // 无标记且队列无原件 ⇒ 放行（原件丢失形态——重投是唯一投递机会）
    assertEquals(
      DeliveredReceiptLedger.isDuplicateIdentity(Set.empty, Nil, redelivery),
      false,
      "no mark and no queued original: the redelivery is the only chance — it passes"
    )
    // 无身份键恒放行
    assertEquals(
      DeliveredReceiptLedger.isDuplicateIdentity(Set("anything"), List(ev(None)), ev(None)),
      false,
      "events without a stable id are never deduplicated (legal repeat-notification form)"
    )
  }

  test("③ partitionDelivered: both copies of a double-delivery are identity-blocked when the ORIGINAL id is delivered-marked (restart replay gate)") {
    val original = ev(Some("d-2"))
    val redelivery = redeliveredOf("d-2")
    val unrelated = ev(Some("d-3"))
    val (kept, blocked) = DeliveredReceiptLedger.partitionDelivered(Set("d-2"), List(original, redelivery, unrelated))
    assertEquals(kept.map(_.correlationId), List(Some("d-3")), "only the unrelated event passes")
    assertEquals(
      blocked.map(_.correlationId),
      List(Some("d-2"), Some("delivery-redelivery-d-2")),
      "the original is blocked via its raw id; the redelivered copy is identity-blocked via its HOME id even though its RAW id was never marked"
    )
  }

  test("④ intakeAccepts: ledger-backed accept/drop with fail-open on ledger read failure (never lose a receipt)") {
    val sid = "spec-intake-session"
    val original = ev(Some("d-9"))
    val redelivery = redeliveredOf("d-9")
    for
      acceptedFirst <- DeliveredReceiptLedger.intakeAccepts(sid, Nil, original)
      _ <- DeliveredReceiptLedger.markDelivered(sid, DeliveredReceiptLedger.identityKeys(original))
      acceptedSecond <- DeliveredReceiptLedger.intakeAccepts(sid, Nil, redelivery)
      acceptedNoId <- DeliveredReceiptLedger.intakeAccepts(sid, Nil, ev(None))
    yield
      assertEquals(acceptedFirst, true, "the first copy passes (nothing delivered, nothing queued)")
      assertEquals(acceptedSecond, false, "after the original is delivered-marked, the redelivery is dropped at intake")
      assertEquals(acceptedNoId, true, "no-id events always pass")
  }

end ExternalEventIntakeDedupSpec
