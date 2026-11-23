package nebflow.core.mail

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.PathUtil

/**
 * 送达链路修复批（2026-11-23）P0② 判红锚：墓碑语义硬化。
 *
 * 诊断书 `stand-delivery-chain-diagnosis__20261123_091822.md`：续轮终态撞第一轮墓碑
 * 被静默去重 ⇒ 约 36 份续轮终报丢失。硬化后：同身份 + 不同载荷 ⇒ 强制入箱；
 * 同身份 + 同载荷（真重放）⇒ 仍静默。
 */
class DeliveryChainTombstoneSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-delivery-tombstone"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  private def terminal(payload: String): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "completed",
      payload = payload,
      metadata = JsonObject.empty,
      correlationId = Some("task-x")
    )

  test("P0②: same identity + DIFFERENT payload after consumption is forced into the box") {
    val sid = "dct-sid-diff"
    for
      _ <- SessionMailbox.mailOnce(sid, terminal("ROUND-ONE"), "delegate-terminal", "task-x", deduplicateReceipts = true)
      p1 <- SessionMailbox.scanPending(sid)
      _ <- SessionMailbox.take(sid, p1)
      _ <- SessionMailbox.unlinkTaken(sid, List("task-x"))
      r2 <- SessionMailbox.mailOnce(sid, terminal("ROUND-TWO"), "delegate-terminal", "task-x", deduplicateReceipts = true)
      pending <- SessionMailbox.scanPending(sid)
    yield
      assert(clue(r2).isInstanceOf[SessionMailbox.MailResult.Enqueued], clue(r2))
      assertEquals(clue(pending).size, 1, "new round content must land in the box")
      assertEquals(clue(pending.head.event.payload), "ROUND-TWO")
      assert(clue(pending.head.identity).startsWith("task-x#f"), "forced entry carries a fingerprinted identity")
  }

  test("P0②: same identity + SAME payload after consumption stays silent (true replay still deduplicated)") {
    val sid = "dct-sid-same"
    for
      _ <- SessionMailbox.mailOnce(sid, terminal("SAME"), "delegate-terminal", "task-x", deduplicateReceipts = true)
      p1 <- SessionMailbox.scanPending(sid)
      _ <- SessionMailbox.take(sid, p1)
      _ <- SessionMailbox.unlinkTaken(sid, List("task-x"))
      r2 <- SessionMailbox.mailOnce(sid, terminal("SAME"), "delegate-terminal", "task-x", deduplicateReceipts = true)
      pending <- SessionMailbox.scanPending(sid)
    yield
      assertEquals(clue(r2), SessionMailbox.MailResult.AlreadyPresent: SessionMailbox.MailResult)
      assertEquals(clue(pending), Nil)
  }

  test("P0②: legacy v1 tombstone without fingerprint keeps same-identity suppression (no upgrade re-flood)") {
    val sid = "dct-sid-legacy"
    val legacy = SessionMailbox.Delivered(1, List("task-x"))
    for
      _ <- IO.blocking {
        os.makeDir.all(tmp / "sessions" / sid / "mailbox")
        os.write.over(tmp / "sessions" / sid / "mailbox" / "delivered.json", legacy.asJson.noSpaces)
      }
      r <- SessionMailbox.mailOnce(sid, terminal("ANY"), "delegate-terminal", "task-x", deduplicateReceipts = true)
    yield assertEquals(clue(r), SessionMailbox.MailResult.AlreadyPresent: SessionMailbox.MailResult)
  }

  test("P0②: payload fingerprint is stable for identical events and differs when payload differs") {
    val a = SessionMailbox.payloadFingerprint(terminal("x"))
    val b = SessionMailbox.payloadFingerprint(terminal("x"))
    val c = SessionMailbox.payloadFingerprint(terminal("y"))
    assertEquals(a, b)
    assertNotEquals(a, c)
    assertEquals(a.length, 16)
  }

end DeliveryChainTombstoneSpec
