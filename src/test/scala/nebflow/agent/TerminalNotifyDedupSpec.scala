package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.PathUtil

/**
 * 修复批 2026-11-06 · delegate 终态通知**聚合 + 去重**（作者 20:09-20:12 令）。
 *
 * 现网实证（20:06 引擎重启 boot sweep 收殓）：一屏 ~10 条终态注入泡（9×FAILED +
 * 1×COMPLETED，同一分钟）。作者令逐字：「像这种通知气泡也应该要有去重的逻辑，
 * 现在一下通知太多了」「一屏 10 条应聚 1 条」。
 *
 * 判据面 = `MailboxDrain.terminalNotifications`（呈现面单点；模型消息 / 屏障会计 /
 * unlink 消费**原始事件**不受影响；面板逐批明细不灭）。
 *
 * 钉死断言：
 *   - **A 同拍聚合**：9 failed + 1 completed 同拍 ⇒ 恰 **1 条**聚合行，判词头取
 *     failed（作者令：FAILED 绿=成功色语义即错），正文逐成员分行列明细 + 总览行；
 *     台账落盘 `delegate-terminal-announce.json`。
 *   - **B 同集合重放零重播**：已播成员重放（crash-window / boot-sweep 合成）⇒ 0 条。
 *   - **C 新成员恰播一次**：新 failed ⇒ 恰 1 行且只带新成员（增量语义）；台账累计。
 *   - **D 台账读失败 fail-soft**：坏账 ⇒ 事件原样透传，真终态绝不静音。
 *
 * 改前红 = `terminalNotifications` 为本批新符号（改前 Test/compile 对本 spec
 * 编译错——与 provider-concurrency 批「新符号不存在」同口径的编译红）。
 */
class TerminalNotifyDedupSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-terminal-notify-")
    PathUtil.setDataRoot(base / "data")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(base)
    catch case _: Throwable => ()

  private def ledger(sid: String): os.Path =
    PathUtil.dataRoot / "sessions" / sid / "delegate-terminal-announce.json"

  private def term(id: String, eventType: String, recoveredBy: Option[String] = None): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = eventType,
      payload = s"payload $id",
      metadata = recoveredBy match
        case Some(v) => JsonObject.fromIterable(List(
          "taskId" -> id.asJson,
          "agentName" -> s"agent-$id".asJson,
          "recoveredBy" -> v.asJson
        ))
        case None => JsonObject.fromIterable(List(
          "taskId" -> id.asJson,
          "agentName" -> s"agent-$id".asJson
        )),
      correlationId = Some(id)
    )

  private def other: AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "mail",
      eventType = "info",
      payload = "mail body",
      correlationId = Some("m1")
    )

  /** 作者截图同构爆发：9×FAILED（boot sweep 收殓）+ 1×COMPLETED。 */
  private val burst: List[AgentCommand.ExternalEvent] =
    (1 to 9).toList.map(i => term(s"t$i", "failed", Some("startup-recovery"))) :+
      term("c1", "completed", Some("completion-finalize"))

  test("A: 9 failed + 1 completed in one pump aggregate into exactly one row") {
    for out <- MailboxDrain.terminalNotifications(other :: burst, ledger("root"))
    yield
      val rows = clue(out.filter(_.source == "delegate"))
      assertEquals(rows.size, 1, "burst aggregates into exactly one notification bubble")
      val merged = rows.head
      assert(clue(merged.payload).contains("FAILED: 9"), "overview line carries the failed count")
      assert(clue(merged.payload).contains("COMPLETED: 1"), "overview line carries the completed count")
      assert(clue(merged.payload).contains("t1"), "per-member detail lines preserved (明细不灭)")
      assert(clue(merged.payload).contains("c1"), "completed member preserved")
      assertEquals(clue(merged.eventType), "failed", "header verdict = failed (failure semantics survive)")
      assert(clue(os.exists(ledger("root"))), "announce ledger persisted to disk")
      assertEquals(
        clue(out.filter(_.source == "mail").map(_.correlationId)),
        List(Some("m1")),
        "non-terminal events untouched and in order"
      )
  }

  test("B: same member set replay announces nothing (boot sweep / crash window)") {
    for
      _ <- MailboxDrain.terminalNotifications(burst, ledger("root"))
      out <- MailboxDrain.terminalNotifications(burst, ledger("root"))
    yield
      assertEquals(
        clue(out.filter(_.source == "delegate")),
        Nil,
        "already-announced members stay silent on replay"
      )
  }

  test("C: a new member announces exactly once, delta only; ledger accumulates") {
    for
      _ <- MailboxDrain.terminalNotifications(List(term("x1", "failed")), ledger("delta"))
      mid <- MailboxDrain.terminalNotifications(List(term("x1", "failed")), ledger("delta"))
      out <- MailboxDrain.terminalNotifications(List(term("x2", "failed")), ledger("delta"))
    yield
      assertEquals(clue(mid.filter(_.source == "delegate")), Nil, "same member silent")
      assertEquals(
        clue(out.filter(_.source == "delegate")).map(_.correlationId),
        List(Some("x2")),
        "delta row carries only the new member"
      )
  }

  test("D: unreadable ledger fails soft — real terminal state is never swallowed") {
    os.makeDir.all(ledger("broken")) // ledger path occupied by a directory ⇒ read throws
    for out <- MailboxDrain.terminalNotifications(List(term("d1", "failed")), ledger("broken"))
    yield
      assertEquals(
        clue(out.filter(_.source == "delegate")).map(_.correlationId),
        List(Some("d1")),
        "fail-soft: ledger failure degrades to per-event bubbles, never silence"
      )
  }

  test("E: batches without terminal events pass through byte-identically") {
    val events = List(other, term("skip", "frozen-waiting"))
    for out <- MailboxDrain.terminalNotifications(events, ledger("plain"))
    yield assertEquals(clue(out), events, "no terminal events ⇒ zero transformation")
  }
end TerminalNotifyDedupSpec
