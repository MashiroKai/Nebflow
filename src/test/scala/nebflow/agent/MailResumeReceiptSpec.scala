package nebflow.agent

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.core.mail.SessionMailbox
import nebflow.shared.PathUtil

/**
 * Mail 续聊（resume-in-place）终态回执必达 · 判红锚（receipt-loss 批 2026-11-23）。
 *
 * 事故形态（四批实证，根因链见终报 `receipt-loss-fix-20261123-report.md`）：delegate 经
 * Mail 续聊复活后，第二轮及之后的终态身份若退化为裸 subagentId，就会撞上第一轮已消费的
 * 墓碑（`delivered.json`，v1 旧账无指纹）⇒ mailOnce 返回 AlreadyPresent ⇒ 终态从未入箱
 * 也从未注入 root。修复路径（`feat/base` 既有）：续轮身份由本轮 runId 承载
 * （`BackoffSupervisor.resolveTerminalGeneration` 的 capturedRun 回落），每轮身份唯一。
 *
 * 本 spec 钉死：
 *   1. 续轮（runId 身份）在旧账墓碑存在时仍入箱、可取走、可消费（回执必达）；
 *   2. 连续多轮续聊（run-2 → run-3）逐轮独立入箱；
 *   3. 对照组：裸身份撞同一旧账墓碑被抑制——即事故机制本身（钉死，防回退到裸身份）。
 *
 * 身份拼接与 BackoffSupervisor.notify 的 `terminalIdentity` 同式：gen 空 ⇒ 裸 sid，否则 `sid#gen`。
 */
class MailResumeReceiptSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null
  private val sid = "mrr-root"
  private val delegate = "delegate-nebflow-72dd065a"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-mail-resume-receipt"))
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
      correlationId = Some(delegate)
    )

  /** 与 BackoffSupervisor.notify 同式的终态身份拼接。 */
  private def identityOf(generation: String): String =
    if generation.isEmpty then delegate else s"$delegate#$generation"

  /** 复现发布版遗留的旧账墓碑（v1：裸 id，无指纹）。 */
  private def seedLegacyTombstone(): IO[Unit] = IO.blocking {
    val box = tmp / "sessions" / sid / "mailbox"
    os.makeDir.all(box)
    os.write.over(box / "delivered.json", SessionMailbox.Delivered(1, List(delegate)).asJson.noSpaces)
  }

  /** 一轮：入箱 → 取走 → 注入即消费（unlink 落墓碑）。返回入箱结果。 */
  private def deliverRound(identity: String, payload: String): IO[SessionMailbox.MailResult] =
    for
      r <- SessionMailbox.mailOnce(sid, terminal(payload), "delegate-terminal", identity, deduplicateReceipts = true)
      pending <- SessionMailbox.scanPending(sid)
      taken <- SessionMailbox.take(sid, pending.filter(_.identity == identity))
      _ <- SessionMailbox.unlinkTaken(sid, taken.map(_.identity))
    yield r

  test("resumed round with run identity is delivered even though a legacy bare tombstone exists") {
    for
      _ <- seedLegacyTombstone()
      run2 = BackoffSupervisor.resolveTerminalGeneration(None, Some("run-2"))
      r <- deliverRound(identityOf(run2), "ROUND-TWO-FINAL-REPORT")
      delivered <- SessionMailbox.deliveredContains(sid, Set(s"$delegate#run-2"))
    yield
      assert(clue(r).isInstanceOf[SessionMailbox.MailResult.Enqueued], clue(r))
      assertEquals(clue(delivered), Set(s"$delegate#run-2"), "round-2 consumption must leave its own tombstone")
  }

  test("consecutive resumed rounds (run-2, run-3) each land and are consumed independently") {
    for
      _ <- seedLegacyTombstone()
      r2 <- deliverRound(identityOf(BackoffSupervisor.resolveTerminalGeneration(None, Some("run-2"))), "R2")
      r3 <- deliverRound(identityOf(BackoffSupervisor.resolveTerminalGeneration(None, Some("run-3"))), "R3")
      tombs <- SessionMailbox.deliveredContains(sid, Set(s"$delegate#run-2", s"$delegate#run-3"))
    yield
      assert(clue(r2).isInstanceOf[SessionMailbox.MailResult.Enqueued], clue(r2))
      assert(clue(r3).isInstanceOf[SessionMailbox.MailResult.Enqueued], clue(r3))
      assertEquals(clue(tombs).size, 2)
  }

  test("control (incident mechanism, pinned): bare identity after a legacy tombstone is suppressed as AlreadyPresent") {
    for
      _ <- seedLegacyTombstone()
      r <- SessionMailbox.mailOnce(
        sid,
        terminal("ROUND-N-BARE"),
        "delegate-terminal",
        identityOf(BackoffSupervisor.resolveTerminalGeneration(None, None)),
        deduplicateReceipts = true
      )
    yield assertEquals(clue(r), SessionMailbox.MailResult.AlreadyPresent: SessionMailbox.MailResult)
  }

end MailResumeReceiptSpec
