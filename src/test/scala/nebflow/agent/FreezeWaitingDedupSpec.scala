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
 * **fallback 废除批改写（作者令 2026-11-06 20:36「只在子agent面板里显示……不再向上
 * 通知」，逐条申报）**：frozen-waiting 行本身是一条父可见件 ⇒ 该播报面随批退役——
 * `collapseSilentReceipts` 改为**整段丢弃**静默回执，生产路径零 frozen-waiting 行
 * （原 A 用例「3 族同拍合一条蓝气泡」面退役）。`suppressRepeatedWaiting` 判据**原样
 * 保留**：只为吸收**存量盘面**里已落账的 frozen-waiting 行（本批起生产不再产新行，
 * 该闸就此空转）——原 B/C/D 用例改以**手造存量行**直喂 `suppressRepeatedWaiting`，
 * 继续钉死该吸收闸的判据。原断言逐字内容见 git 历史。
 *
 * 钉死的断言：
 *   - **A 生产面（新语义）**：种子 15 条静默回执 ⇒ pump **整段丢弃**（dropped=15），
 *     零 frozen 回执、零 frozen-waiting 行；重复 pump 同读数（taken 重放不产新行）。
 *   - **B 存量吸收·跨因合泡**：3 族存量行同拍 ⇒ 恰一条合泡（逐族分行），台账落盘。
 *   - **C 存量吸收·同集合零重播**：同批行重复喂 ⇒ 只播一次，其后零行（纯文件台账）。
 *   - **D 存量吸收·成员增减**：新成员 ⇒ 恰再播一次且只带**变化的族**。
 *   - **E 存量吸收·恢复清账**：成员全部不在冻 ⇒ 清账；同成员再冻 = 新事件，允许再播。
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

  /**
   * 一拍生产 pump = 与 `MailboxDrain` W1/W4 同取数：taken 重放 ++ pending 全取 →
   * collapse（fallback 废除批起 = **整段丢弃**，不再折叠出任何 frozen-waiting 行）。
   */
  private def pumpProd(parent: String): IO[(List[AgentCommand.ExternalEvent], Int)] =
    for
      replayed <- SessionMailbox.scanTaken(parent)
      pending <- SessionMailbox.scanPending(parent)
      drained <- SessionMailbox.take(parent, pending)
      (events, dropped) = FreezeNotice.collapseSilentReceipts(replayed.map(_.event) ++ drained.map(_.event))
    yield (events, dropped)

  private def waitingRows(events: List[AgentCommand.ExternalEvent]): List[AgentCommand.ExternalEvent] =
    events.filter(e => e.source == "delegate" && e.eventType == "frozen-waiting")

  private def freshParent(tag: String): String = s"waiting-parent-$tag-${scala.util.Random.nextInt(100000)}"

  /** 现网同形批：quota-balance ×2 / upstream-5xx ×1 / auth-subscription ×12。 */
  private def seedAuthorWave(parent: String, now: Long): List[AgentCommand.ExternalEvent] =
    val quota = (1 to 2).toList.map(i =>
      receipt(parent, s"dlg-quota-$i", DelegateFreeze.Reason.CancelledByQuota, s"zhipu/glm: insufficient balance (#$i)")
    )
    val fiveXX = List(
      receipt(
        parent,
        "dlg-5xx-1",
        DelegateFreeze.Reason.LlmFailure,
        "[provider] cmdcode/deepseek: statusCode: 550, response: (empty body)"
      )
    )
    val auth = (1 to 12).toList.map(i =>
      receipt(
        parent,
        s"dlg-auth-$i",
        DelegateFreeze.Reason.LlmFailure,
        s"openai/gpt: statusCode: 403, response: no subscription (#$i)"
      )
    )
    val all = quota ++ fiveXX ++ auth
    all.foreach(r =>
      registryFrozen(r.metadata("frozenDelegateId").flatMap(_.asString).getOrElse(""), frozen = true, now)
    )
    all
  end seedAuthorWave

  private val now0 = System.currentTimeMillis()

  /** 存量盘面形态的 frozen-waiting 行（本批前 collapse 的产出物；手造用于直喂吸收闸）。 */
  private def legacyWaitingRow(
    parent: String,
    family: String,
    ids: List[String]
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "frozen-waiting",
      payload = s"[frozen-waiting] legacy row ($family)",
      metadata = JsonObject(
        "parentSessionId" -> parent.asJson,
        "quietFamily" -> family.asJson,
        "quietDelegateIds" -> ids.asJson
      ),
      correlationId = Some(s"legacy-waiting-$family")
    )

  private def waveIds(prefix: String, n: Int): List[String] = (1 to n).map(i => s"dlg-$prefix-$i").toList

  // ── A 生产面（新语义）：静默回执整段丢弃，零行产出 ──────────────────────────

  test(
    "A(重写). production face: 15 silent receipts in one pump are dropped WHOLESALE — zero frozen receipts, zero frozen-waiting rows"
  ) {
    val parent = freshParent("a")
    for
      _ <- seed(parent, seedAuthorWave(parent, now0))
      (events1, dropped1) <- pumpProd(parent)
      (events2, dropped2) <- pumpProd(parent) // taken 段重放 ⇒ 每拍同读数、零新行
    yield
      assertEquals(dropped1, 15, "all 15 silent receipts dropped wholesale (no per-family folding anymore)")
      assertEquals(events1.count(_.eventType == "frozen"), 0, "no raw frozen receipt may bubble")
      assertEquals(waitingRows(events1).size, 0, "production never creates frozen-waiting rows")
      assertEquals(dropped2, 15, "replayed taken segment drops the same 15 every pump")
      assertEquals(waitingRows(events2).size, 0, "still zero rows on the next pump")
    end for
  }

  // ── B 存量吸收·跨因合泡：3 族存量行同拍 ⇒ 恰一条蓝气泡 + 台账落盘 ────────────

  test(
    "B(重写·存量吸收). cross-family: 3 legacy families in one feed ⇒ exactly ONE frozen-waiting event, per-family lines, ledger on disk"
  ) {
    val parent = freshParent("b")
    val quotaIds = waveIds("quota", 2)
    val xxIds = List("dlg-5xx-1")
    val authIds = waveIds("auth", 12)
    for
      _ <- IO(seedAuthorWave(parent, now0)) // 注册表在冻（frozenNow 判据的数据源）
      (rows, suppressed1) <- FreezeNotice.suppressRepeatedWaiting(
        List(
          legacyWaitingRow(parent, "quota-balance", quotaIds),
          legacyWaitingRow(parent, "upstream-5xx", xxIds),
          legacyWaitingRow(parent, "auth-subscription", authIds)
        )
      )
    yield
      val rowsOut = waitingRows(rows)
      // ① 跨因合成一条：3 个族同拍 ⇒ **恰一条** frozen-waiting（不是 3 条）。
      assertEquals(rowsOut.size, 1, s"3 families must merge into ONE bubble, got ${rowsOut.size}")
      // ② 首播抑制读数 = 0（这一拍是播报，不是抑制）。
      assertEquals(suppressed1, 0, "first announce is not a suppression")
      // ③ 正文逐族分行列明细。
      val text = rowsOut.head.payload
      assert(text.startsWith("[frozen-waiting]"), s"marker: ${text.take(40)}")
      for fam <- List("quota-balance", "upstream-5xx", "auth-subscription") do
        assert(text.contains(s"- `$fam`"), s"per-family line missing for $fam in:\n$text")
      // ④ 合并件标记 + 成员并集（2 + 1 + 12 = 15）。
      assertEquals(rowsOut.head.metadata("quietMerged").flatMap(_.asBoolean), Some(true), "quietMerged marker")
      val ids =
        rowsOut.head.metadata("quietDelegateIds").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).toList
      assertEquals(ids.size, 15, "union of member ids across families")
      // ⑤ 抑制态落盘：台账文件在场，3 个族各有 members。
      val ledgerPath = FreezeNotice.waitingAnnouncePath(parent)
      assert(os.exists(ledgerPath), s"ledger file must exist at $ledgerPath (重启不重置的载体)")
      val families = FreezeNotice.readWaitingAnnounces(parent)
      assertEquals(
        families.map(_.family).sorted,
        List("auth-subscription", "quota-balance", "upstream-5xx"),
        "ledger holds one entry per family"
      )
      assert(families.forall(_.members.nonEmpty), "each entry carries its member set")
    end for
  }

  // ── C 存量吸收·同集合零重播 ────────────────────────────────────────────────

  test("C(重写·存量吸收). same member set: announce ONCE, then repeated feeds produce ZERO rows (suppression from disk)") {
    val parent = freshParent("c")
    val rows = List(
      legacyWaitingRow(parent, "quota-balance", waveIds("quota", 2)),
      legacyWaitingRow(parent, "upstream-5xx", List("dlg-5xx-1")),
      legacyWaitingRow(parent, "auth-subscription", waveIds("auth", 12))
    )
    for
      _ <- IO(seedAuthorWave(parent, now0))
      _ <- FreezeNotice.suppressRepeatedWaiting(rows) // 首播
      p2 <- FreezeNotice.suppressRepeatedWaiting(rows)
      p3 <- FreezeNotice.suppressRepeatedWaiting(rows)
      p4 <- FreezeNotice.suppressRepeatedWaiting(rows)
    yield
      val rows2 = waitingRows(p2._1)
      assertEquals(rows2.size, 0, s"same member set must NOT re-announce, got ${rows2.map(_.payload.take(50))}")
      assertEquals(p2._2, 3, "all 3 family rows suppressed in one feed")
      List(p3, p4).zipWithIndex.foreach { case ((events, sup), i) =>
        assertEquals(waitingRows(events).size, 0, s"feed ${i + 3} still quiet")
        assertEquals(sup, 3, s"feed ${i + 3} suppression count")
      }
      // 重启不重置的机制面：抑制态只在盘上（进程内零缓存）——台账仍带 3 族成员集。
      val families = FreezeNotice.readWaitingAnnounces(parent)
      assertEquals(families.size, 3, "ledger persists across feeds (file-backed, no in-memory reset)")
    end for
  }

  // ── D 存量吸收·成员增减 ⇒ 恰再播一次，且只带变化的族 ────────────────────────

  test("D(重写·存量吸收). member added ⇒ exactly one re-announce carrying ONLY the changed family, then quiet again") {
    val parent = freshParent("d")
    val quotaIds = waveIds("quota", 2)
    val xxIds = List("dlg-5xx-1")
    val authIds = waveIds("auth", 12)
    def baseRows: List[AgentCommand.ExternalEvent] = List(
      legacyWaitingRow(parent, "quota-balance", quotaIds),
      legacyWaitingRow(parent, "upstream-5xx", xxIds),
      legacyWaitingRow(parent, "auth-subscription", authIds)
    )
    for
      _ <- IO(seedAuthorWave(parent, now0))
      _ <- FreezeNotice.suppressRepeatedWaiting(baseRows) // 首播：quota {q1,q2} / 5xx {x1} / auth {a1..a12}
      _ <- IO(registryFrozen("dlg-quota-3", frozen = true, now0)) // 新成员入族（注册表在冻）
      (events2, sup2) <- FreezeNotice.suppressRepeatedWaiting(
        List(
          legacyWaitingRow(parent, "quota-balance", quotaIds :+ "dlg-quota-3"),
          legacyWaitingRow(parent, "upstream-5xx", xxIds),
          legacyWaitingRow(parent, "auth-subscription", authIds)
        )
      ) // quota 成员 2→3 ⇒ 播；其余两族不变 ⇒ 抑制
      p3 <- FreezeNotice.suppressRepeatedWaiting(
        List(
          legacyWaitingRow(parent, "quota-balance", quotaIds :+ "dlg-quota-3"),
          legacyWaitingRow(parent, "upstream-5xx", xxIds),
          legacyWaitingRow(parent, "auth-subscription", authIds)
        )
      )
    yield
      val rows2 = waitingRows(events2)
      assertEquals(rows2.size, 1, s"member change ⇒ exactly one bubble, got ${rows2.size}")
      val text2 = rows2.head.payload
      assert(text2.contains("quota-balance"), "changed family present")
      assert(
        !text2.contains("auth-subscription") && !text2.contains("upstream-5xx"),
        s"unchanged families must NOT re-announce (suppressed), got:\n$text2"
      )
      assertEquals(sup2, 2, "the two unchanged family rows count as suppressed")
      val ids =
        rows2.head.metadata("quietDelegateIds").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).toList
      assert(ids.contains("dlg-quota-3"), "new member carried")
      assertEquals(waitingRows(p3._1).size, 0, "next feed is quiet again")
    end for
  }

  // ── E 存量吸收·恢复清账；同成员再冻 = 新事件允许再播 ────────────────────────

  test(
    "E(重写·存量吸收). recovery clears the ledger (rows dropped while recovered); same members re-freezing announces again"
  ) {
    val parent = freshParent("e")
    val wave = seedAuthorWave(parent, now0)
    val allIds = wave.map(r => r.metadata("frozenDelegateId").flatMap(_.asString).getOrElse(""))
    def rows: List[AgentCommand.ExternalEvent] = List(
      legacyWaitingRow(parent, "quota-balance", allIds.filter(_.startsWith("dlg-quota"))),
      legacyWaitingRow(parent, "upstream-5xx", allIds.filter(_ == "dlg-5xx-1")),
      legacyWaitingRow(parent, "auth-subscription", allIds.filter(_.startsWith("dlg-auth")))
    )
    for
      _ <- FreezeNotice.suppressRepeatedWaiting(rows) // 首播
      _ <- IO {
        wave.foreach(r =>
          registryFrozen(r.metadata("frozenDelegateId").flatMap(_.asString).getOrElse(""), frozen = false, now0)
        )
      } // 全员解冻恢复
      (events2, sup2) <- FreezeNotice.suppressRepeatedWaiting(rows)
      // 清账断言必须在再冻**之前**读（再播报会合法地写入新台账）。
      _ <- IO(assertEquals(FreezeNotice.readWaitingAnnounces(parent), Nil, "ledger cleared on recovery"))
      _ <- IO {
        wave.foreach(r =>
          registryFrozen(r.metadata("frozenDelegateId").flatMap(_.asString).getOrElse(""), frozen = true, now0)
        )
      } // 同成员再冻
      (events3, sup3) <- FreezeNotice.suppressRepeatedWaiting(rows)
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
