package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentCommand, FreezeDisplay}
import nebflow.core.delegate.{DelegateFreeze, FreezeNotice}
import nebflow.core.mail.SessionMailbox
import nebflow.shared.{Message, MessageRole, PathUtil}

/**
 * 修复批 · 可恢复的冻结一律不再上报作者（2026-11-06）——判红锚。
 *
 * 作者原话逐字（2026-11-06 09:43）：
 *   「不是说了 llm 不稳定 不需要向上通知吗 原地等待恢复就行了 搜一下我之前的要求吧
 *     现在还是在给你报障」
 *
 * 现场取证（`~/.stand/logs/stand.log`，2026-11-06 09:24 / 09:28 / 09:35 / 09:39 /
 * 09:43 五次）：同一批在每次解冻时 `mailbox-drained-at-unfreeze events=15
 * replayed=15`——冻结窗里累积的**冻结回执**被原样重放，每条过
 * `AgentActor.visibleExternalEventSource` 变成一格 `delegate-frozen` 气泡。静默总闸
 * 只关得住投递，关不住重放；既有 `check-freeze-silence`（11 项）全绿也证明它没看这条腿。
 *
 * 钉死的断言：
 *   用例 A（可恢复）——真实邮箱里积 15 条回执（12 条 llm-failure + 3 条
 *     cancelled-by-quota）⇒ 解冻重放面**用户面 0 条冻结回执**、同因合并后**恰 2 条**
 *     状态行（一族一条）、状态 = 等待恢复（`waiting-recovery`）、**不进 LLM 上下文的
 *     回执正文**。
 *   用例 B（真失败）——3 条 tool-failure + 2 条 engine-fault ⇒ **逐条保留**（5 条），
 *     每条带不可恢复理由，永不被合并吞掉。
 */
class FreezeReplaySilenceSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd / "target" / "test-freeze-replay"

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-freeze-replay-")
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

  /** 解冻重放面**现状**（与 `MailboxDrain.resumeSweep` 同取数、同装配，不含本批判据）。 */
  private def replayAsMainToday(parent: String): IO[List[AgentCommand.ExternalEvent]] =
    for
      replayed <- SessionMailbox.scanTaken(parent)
      pending <- SessionMailbox.scanPending(parent)
      drained <- SessionMailbox.take(parent, pending)
    yield (replayed ++ drained).map(_.event)

  /** 本批修复后的解冻重放面。 */
  private def replayFixed(parent: String): IO[(List[AgentCommand.ExternalEvent], Int)] =
    replayAsMainToday(parent).map(FreezeNotice.collapseSilentReceipts)

  /** 「一条事件是否会在用户面冒泡」——与 `AgentActor.visibleExternalEventSource` 同判据。 */
  private def bubbles(e: AgentCommand.ExternalEvent): Boolean =
    e.source == "delegate" && (e.eventType == "frozen" || e.eventType == "frozen-waiting")

  private def freshParent(tag: String): String = s"replay-parent-$tag-${scala.util.Random.nextInt(100000)}"

  // ── 用例 A：可恢复冻结（llm-failure ×12 + quota ×3）────────────────────────
  // 重写（fallback 废除批，作者令 2026-11-06 20:36「只在子agent面板里显示……不再
  // 向上通知」）：原断言「同因收成一条 frozen-waiting 状态行」——状态行仍是一条向上
  // 注入的父可见件 ⇒ 该面退役；collapseSilentReceipts 改为**整段丢弃**静默回执，
  // 生产路径零 frozen-waiting 行，呈现面只有子 agent 面板。原断言逐字见 git 历史。

  test("A(重写). recoverable freeze replay: ZERO user-facing receipts AND zero status rows — silent receipts dropped wholesale, panel is the only surface") {
    val parent = freshParent("a")
    val llmFails = (1 to 12).toList.map(i =>
      receipt(
        parent,
        s"dlg-llm-$i",
        DelegateFreeze.Reason.LlmFailure,
        s"[provider] cmdcode/deepseek: statusCode: 550, response: (empty body) (#$i)"
      )
    )
    val quotas = (1 to 3).toList.map(i =>
      receipt(parent, s"dlg-quota-$i", DelegateFreeze.Reason.CancelledByQuota, s"zhipu/glm: insufficient balance (#$i)")
    )
    val userMail = AgentCommand.ExternalEvent(
      source = "mail",
      eventType = "incoming",
      payload = "hello from the author",
      metadata = JsonObject.empty
    )
    val seeded = llmFails ++ quotas ++ List(userMail)
    for
      _ <- IO(println(s"[case A] seeded ${seeded.size} mailbox entries (12 llm-failure + 3 quota + 1 user mail)"))
      _ <- seed(parent, seeded)
      before <- replayAsMainToday(parent)
      beforeBubbles = before.filter(bubbles).size
      afterTried <- replayFixed(parent)
    yield
      // 现网形态先量一遍（同一个「解冻重放面」，判据未加时）：15 条回执条条冒泡。
      assertEquals(before.size, 16, "the queue really holds 16 entries before the fix")
      assertEquals(beforeBubbles, 15, s"REGRESSION BASELINE: today all 15 receipts bubble (got $beforeBubbles)")
      val (after, dropped) = afterTried
      // ① 用户面零冻结回执：逐条回执一条不冒泡。
      val receipts = after.filter(e => e.eventType == "frozen")
      assertEquals(receipts.size, 0, s"no per-delegate frozen receipt may bubble, got ${receipts.map(_.payload.take(60))}")
      // ② fallback 废除批：静默回执**整段丢弃**——15 条全丢，连状态行也不产（状态行
      //    本身是父可见件，随批退役）。原「collapsed == 13（折成 2 行）」面退役。
      assertEquals(dropped, 15, "all 15 silent receipts are dropped wholesale (no per-family folding anymore)")
      val statuses = after.filter(e => e.eventType == "frozen-waiting")
      assertEquals(statuses.size, 0, s"production never creates frozen-waiting rows, got ${statuses.size}")
      // ③ 面板级分类保持：冻结呈现面 = waiting-recovery（子 agent 面板词面）。
      assertEquals(freezeKindOfWire("delegate-frozen:llm-failure"), "waiting-recovery")
      assertEquals(freezeKindOfWire("delegate-frozen:cancelled-by-quota"), "waiting-recovery")
      // ④ 非回执事件原样保留（作者的 Mail 不许被吞）。
      assert(after.exists(e => e.source == "mail" && e.payload == "hello from the author"), "user mail survives")
      // ⑤ 进 LLM 的那条消息里没有任何一条回执正文、也没有状态行。
      val msg = MailboxDrain.mailboxMessage(after)
      val text = msg.content.fold(identity, blocks => blocks.toString)
      assert(!text.contains("[delegate-frozen]"), "no receipt body in the model-facing message")
      assert(!text.contains("[frozen-waiting]"), "no status row either — zero provider-freeze noise upward")
    end for
  }

  // ── 用例 B：真失败（tool-failure ×3 + engine-fault ×2）─────────────────────

  test("B. real failures: NOT merged — each keeps its own row with the non-recoverable reason") {
    val parent = freshParent("b")
    val tools = (1 to 3).toList.map(i =>
      receipt(parent, s"dlg-tool-$i", DelegateFreeze.Reason.ToolFailure, s"ToolPipeline threw at step 4 (#$i)")
    )
    val engines = (1 to 2).toList.map(i =>
      receipt(parent, s"dlg-engine-$i", DelegateFreeze.Reason.EngineFault, s"supervision exhausted after crash (#$i)")
    )
    for
      _ <- seed(parent, tools ++ engines)
      raw <- replayAsMainToday(parent)
      fixed = FreezeNotice.collapseSilentReceipts(raw)
    yield
      val (after, collapsed) = fixed
      assertEquals(raw.size, 5)
      assertEquals(after.size, 5, "real failures are never merged away")
      assertEquals(collapsed, 0)
      assertEquals(after.count(_.eventType == "frozen"), 5)
      assert(after.forall(_.eventType != "frozen-waiting"), "no status row for real failures")
      // 恰一条语义：每条都带「为什么不可恢复」——原因码 + 正文里的故障形状都在。
      after.foreach { e =>
        val why = e.metadata("frozenReason").flatMap(_.asString).getOrElse("")
        assert(why == "tool-failure" || why == "engine-fault", s"reason carried: $why")
        assert(e.payload.contains("NOT a failure verdict"), "receipt keeps its verdict text")
      }
      // 与可恢复族的面板口径分开：真失败不是「等待恢复」。
      assertNotEquals(freezeKindOfWire("delegate-frozen:tool-failure"), "waiting-recovery")
      assertNotEquals(freezeKindOfWire("delegate-frozen:engine-fault"), "waiting-recovery")
    end for
  }

  // ── 判据不另造一份：重放面读的就是既有分类单点 ─────────────────────────────

  test("classification is the SAME single source: tierOfReason / FreezeDisplay / ProviderFault agree") {
    val stable = DelegateFreeze.All
    stable.foreach { r =>
      val line = s"delegate-frozen:${DelegateFreeze.wire(r)}"
      val fd = FreezeDisplay.ofWire(line)
      val silent = FreezeNotice.tierOfReason(r) == FreezeNotice.Tier.Silent
      assertEquals(silent, fd.autoRecoverable, s"$line: tier must follow FreezeDisplay.autoRecoverable")
    }
    assert(FreezeNotice.receiptSilent(
      DelegateFreeze.receiptText("d", "x", DelegateFreeze.Reason.LlmFailure, "All providers failed", "/w")
    ))
    assert(!FreezeNotice.receiptSilent(
      DelegateFreeze.receiptText("d", "x", DelegateFreeze.Reason.ToolFailure, "ToolPipeline threw", "/w")
    ))
  }

  /** 面板判据（与前端 `freezeState.freezeKindOf` 同一张表；门禁另有 node 侧真跑）。 */
  private def freezeKindOfWire(line: String): String =
    if FreezeNotice.tierOfLine(line) == FreezeNotice.Tier.Silent then "waiting-recovery" else "needs-human"

end FreezeReplaySilenceSpec
