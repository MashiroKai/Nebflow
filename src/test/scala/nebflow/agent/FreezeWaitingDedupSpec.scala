package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.core.delegate.{DelegateFreeze, DelegateRegistry, FreezeNotice}
import nebflow.core.mail.SessionMailbox
import nebflow.shared.PathUtil

/**
 * 修复批 · frozen-waiting 通知「同因只播一次 + 跨因合一个蓝气泡」（2026-11-06 16:14）。
 *
 * 作者令逐字：「FROZEN-WAITING · NEBFLOW · FROZEN-WAITING 这种通知 只准去重通知一次，
 * 而且要几个同类型的聚合成一个蓝色气泡通知。」
 *
 * 现网实证（15:29→16:14）：同一组 frozen-waiting 摘要每 4 分钟重发一次，每次固定 3 行
 * （quota-balance 2 / upstream-5xx 1 / auth-subscription 12），内容逐字相同。机械根因：
 * 静默族冻结回执留在邮箱 taken/ 段随每拍重放，`collapseSilentReceipts` 只把 N 条收成
 * **每族一条**——族与族之间、拍与拍之间没有「同一状态只播一次」的抑制，也没有跨因聚合。
 *
 * 钉死的断言（与 `FreezeNotice.suppressRepeatedWaiting` 判据一一对应）：
 *   - **A 跨因合泡**：3 个族同拍 ⇒ **恰一条** frozen-waiting 事件（一个蓝气泡），
 *     正文逐族分行；台账落盘（`freeze-waiting-announce.json`）。
 *   - **B 同因集合未变 ⇒ 零重播**：同一批回执多次 pump ⇒ 只播一次，其后每拍
 *     frozen-waiting 行数为 **0**（抑制读数在解冻日志可复盘）。
 *   - **C 成员增减 ⇒ 恰再播一次**：新成员入族 ⇒ 恰一条新播报，且只带**变化的族**；
 *     再下一拍复归零重播。
 *   - **D 解冻恢复 ⇒ 清账**：成员全部不在冻 ⇒ 行静默清账；同成员再冻 = 新事件，允许再播。
 *   - **E 重启不重置**：抑制态是**纯文件台账**（无进程内缓存），重启（= 新进程再调）
 *     后同集合依旧零重播——由 B 的纯文件读路径 + A 的落盘断言共同钉死。
 */
class FreezeWaitingDedupSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd / "target" / "test-freeze-waiting-dedup"

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-freeze-waiting-")
    PathUtil.setDataRoot(base / "data")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(base)
    catch case _: Throwable => ()

  // ── 造件：与生产同一条 `DelegateFreeze.receiptText` 正文 + 同形 metadata ──────

  private def receipt(
    parent: String,
    id: String,
    reason: DelegateFreeze.Reason,
    detail: String
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "frozen",
      payload = DelegateFreeze.receiptText(
        delegateId = id,
        description = s"batch $id",
        reason = reason,
        detail = detail,
        workspace = s"/tmp/ws-$id"
      ),
      metadata = JsonObject(
        "description" -> s"batch $id".asJson,
        "frozenDelegateId" -> id.asJson,
        "frozenReason" -> DelegateFreeze.wire(reason).asJson,
        "frozenDetail" -> detail.asJson,
        "parentSessionId" -> parent.asJson,
        "retryable" -> true.asJson,
        "frozen" -> true.asJson
      ),
      correlationId = Some(s"delegate-frozen-$id-0")
    )

  /** 注册表落一条冻结记录（`frozenNow` 判据的数据源；恢复 = frozenSince=None）。 */
  private def registryFrozen(id: String, frozen: Boolean, now: Long): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = s"/tmp/ws-$id",
        project = None,
        taskId = None,
        parentSessionId = "spec-root",
        title = s"batch $id",
        createdAt = now,
        frozenSince = if frozen then Some(now) else None,
        frozenReason = if frozen then Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)) else None,
        frozenDetail = if frozen then Some("spec") else None
      )
    )

  private def seed(parent: String, events: List[AgentCommand.ExternalEvent]): IO[Unit] =
    events.traverse_(e =>
      SessionMailbox
        .mailOnce(
          parent,
          e,
          kind = "delegate-frozen",
          identity = e.correlationId.getOrElse(java.util.UUID.randomUUID().toString),
          coalesce = false,
          producer = "spec"
        )
        .as(())
    )

  /** 一拍 pump = 与 `MailboxDrain` W1/W4 同取数、同装配：taken 重放 ++ pending 全取 →
    * collapse（每族一条）→ suppress（同因只播一次 + 跨因合一条）。 */
  private def pump(parent: String): IO[(List[AgentCommand.ExternalEvent], Int)] =
    for
      replayed <- SessionMailbox.scanTaken(parent)
      pending <- SessionMailbox.scanPending(parent)
      drained <- SessionMailbox.take(parent, pending)
      (collapsed, _) = FreezeNotice.collapseSilentReceipts(replayed.map(_.event) ++ drained.map(_.event))
      suppressed <- FreezeNotice.suppressRepeatedWaiting(collapsed)
    yield suppressed

  private def waitingRows(events: List[AgentCommand.ExternalEvent]): List[AgentCommand.ExternalEvent] =
    events.filter(e => e.source == "delegate" && e.eventType == "frozen-waiting")

  private def freshParent(tag: String): String = s"waiting-parent-$tag-${scala.util.Random.nextInt(100000)}"

  /** 现网同形批：quota-balance ×2 / upstream-5xx ×1 / auth-subscription ×12。 */
  private def seedAuthorWave(parent: String, now: Long): List[AgentCommand.ExternalEvent] =
    val quota = (1 to 2).toList.map(i =>
      receipt(parent, s"dlg-quota-$i", DelegateFreeze.Reason.CancelledByQuota, s"zhipu/glm: insufficient balance (#$i)")
    )
    val fiveXX = List(
      receipt(parent, "dlg-5xx-1", DelegateFreeze.Reason.LlmFailure, "[provider] cmdcode/deepseek: statusCode: 550, response: (empty body)")
    )
    val auth = (1 to 12).toList.map(i =>
      receipt(parent, s"dlg-auth-$i", DelegateFreeze.Reason.LlmFailure, s"openai/gpt: statusCode: 403, response: no subscription (#$i)")
    )
    val all = quota ++ fiveXX ++ auth
    all.foreach(r => registryFrozen(r.metadata("frozenDelegateId").flatMap(_.asString).getOrElse(""), frozen = true, now))
    all

  private val now0 = System.currentTimeMillis()

  // ── A 跨因合泡：3 族同拍 ⇒ 恰一条蓝气泡 + 台账落盘 ─────────────────────────

  test("A. cross-family: 3 families in one pump ⇒ exactly ONE frozen-waiting event, per-family lines, ledger on disk") {
    val parent = freshParent("a")
    for
      _ <- seed(parent, seedAuthorWave(parent, now0))
      (events1, suppressed1) <- pump(parent)
    yield
      val rows = waitingRows(events1)
      // ① 跨因合成一条：3 个族同拍 ⇒ **恰一条** frozen-waiting（不是 3 条）。
      assertEquals(rows.size, 1, s"3 families must merge into ONE bubble, got ${rows.size}")
      // ② 底下的逐条冻结回执一条都不冒泡。
      assertEquals(events1.count(_.eventType == "frozen"), 0, "no raw frozen receipt may bubble")
      // ③ 首播抑制读数 = 0（这一拍是播报，不是抑制）。
      assertEquals(suppressed1, 0, "first announce is not a suppression")
      // ④ 正文逐族分行列明细（quota-balance / upstream-5xx / auth-subscription）。
      val text = rows.head.payload
      assert(text.startsWith("[frozen-waiting]"), s"marker: ${text.take(40)}")
      for fam <- List("quota-balance", "upstream-5xx", "auth-subscription") do
        assert(text.contains(s"- `$fam`"), s"per-family line missing for $fam in:\n$text")
      // ⑤ 合并件标记 + 成员并集（2 + 1 + 12 = 15）。
      assertEquals(rows.head.metadata("quietMerged").flatMap(_.asBoolean), Some(true), "quietMerged marker")
      val ids = rows.head.metadata("quietDelegateIds").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).toList
      assertEquals(ids.size, 15, "union of member ids across families")
      // ⑥ 抑制态落盘：台账文件在场，3 个族各有 members。
      val ledgerPath = FreezeNotice.waitingAnnouncePath(parent)
      assert(os.exists(ledgerPath), s"ledger file must exist at $ledgerPath (重启不重置的载体)")
      val families = FreezeNotice.readWaitingAnnounces(parent)
      assertEquals(families.map(_.family).sorted, List("auth-subscription", "quota-balance", "upstream-5xx"),
        "ledger holds one entry per family")
      assert(families.forall(_.members.nonEmpty), "each entry carries its member set")
    end for
  }

  // ── B 同因集合未变 ⇒ 零重播（多次 pump 只出一条）───────────────────────────

  test("B. same member set: announce ONCE, then 4 more pumps produce ZERO frozen-waiting rows (suppression from disk)") {
    val parent = freshParent("b")
    for
      _ <- seed(parent, seedAuthorWave(parent, now0))
      _ <- pump(parent) // 首播
      p2 <- pump(parent)
      p3 <- pump(parent)
      p4 <- pump(parent)
      p5 <- pump(parent)
    yield
      val rows2 = waitingRows(p2._1)
      assertEquals(rows2.size, 0, s"same member set must NOT re-announce, got ${rows2.map(_.payload.take(50))}")
      assertEquals(p2._2, 3, "all 3 family rows suppressed in one pump")
      List(p3, p4, p5).zipWithIndex.foreach { case ((events, sup), i) =>
        assertEquals(waitingRows(events).size, 0, s"pump ${i + 3} still quiet")
        assertEquals(sup, 3, s"pump ${i + 3} suppression count")
      }
      // 重启不重置的机制面：抑制态只在盘上（进程内零缓存）——台账仍带 3 族成员集。
      val families = FreezeNotice.readWaitingAnnounces(parent)
      assertEquals(families.size, 3, "ledger persists across pumps (file-backed, no in-memory reset)")
    end for
  }

  // ── C 成员增减 ⇒ 恰再播一次，且只带变化的族 ────────────────────────────────

  test("C. member added ⇒ exactly one re-announce carrying ONLY the changed family, then quiet again") {
    val parent = freshParent("c")
    for
      _ <- seed(parent, seedAuthorWave(parent, now0))
      _ <- pump(parent) // 首播：quota {q1,q2} / 5xx {x1} / auth {a1..a12}
      _ <- seed(parent, List(registryAdd(parent, "dlg-quota-3", now0)))
      (events2, sup2) <- pump(parent) // quota 成员 2→3 ⇒ 播；其余两族不变 ⇒ 抑制
      p3 <- pump(parent)
    yield
      val rows2 = waitingRows(events2)
      assertEquals(rows2.size, 1, s"member change ⇒ exactly one bubble, got ${rows2.size}")
      val text2 = rows2.head.payload
      assert(text2.contains("quota-balance"), "changed family present")
      assert(!text2.contains("auth-subscription") && !text2.contains("upstream-5xx"),
        s"unchanged families must NOT re-announce (suppressed), got:\n$text2")
      assertEquals(sup2, 2, "the two unchanged family rows count as suppressed")
      val ids = rows2.head.metadata("quietDelegateIds").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).toList
      assert(ids.contains("dlg-quota-3"), "new member carried")
      assertEquals(waitingRows(p3._1).size, 0, "next pump is quiet again")
    end for
  }

  /** C 用件：第三条 quota 冻结回执 + 注册表在冻（成员集 2→3）。 */
  private def registryAdd(parent: String, id: String, now: Long): AgentCommand.ExternalEvent =
    registryFrozen(id, frozen = true, now)
    receipt(parent, id, DelegateFreeze.Reason.CancelledByQuota, s"zhipu/glm: insufficient balance (new $id)")

  // ── D 解冻恢复 ⇒ 清账；同成员再冻 = 新事件允许再播 ──────────────────────────

  test("D. recovery clears the ledger (rows dropped while recovered); same members re-freezing announces again") {
    val parent = freshParent("d")
    val wave = seedAuthorWave(parent, now0)
    for
      _ <- seed(parent, wave)
      _ <- pump(parent) // 首播
      _ <- IO { wave.foreach(r =>
        registryFrozen(r.metadata("frozenDelegateId").flatMap(_.asString).getOrElse(""), frozen = false, now0)
      ) } // 全员解冻恢复
      (events2, sup2) <- pump(parent)
      // 清账断言必须在再冻**之前**读（pump3 的再播报会合法地写入新台账）。
      _ <- IO(assertEquals(FreezeNotice.readWaitingAnnounces(parent), Nil, "ledger cleared on recovery"))
      _ <- IO { wave.foreach(r =>
        registryFrozen(r.metadata("frozenDelegateId").flatMap(_.asString).getOrElse(""), frozen = true, now0)
      ) } // 同成员再冻
      (events3, sup3) <- pump(parent)
    yield
      assertEquals(waitingRows(events2).size, 0, "recovered family must not bubble")
      assertEquals(sup2, 0, "recovery is not counted as suppression")
      val rows3 = waitingRows(events3)
      assertEquals(rows3.size, 1, "re-freeze of the same members = new episode ⇒ announces again")
      assertEquals(sup3, 0, "the re-announce is a fresh announce")
      assert(FreezeNotice.readWaitingAnnounces(parent).forall(_.members.nonEmpty), "re-announce records the member set")
    end for
  }

end FreezeWaitingDedupSpec
