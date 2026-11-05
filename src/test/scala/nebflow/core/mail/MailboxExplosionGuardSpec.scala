package nebflow.core.mail

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 刀三·爆炸保护 —— **磁盘邮箱**深度闸的反向验红（段D ①）。
 *
 * 裁定口径（不可改）：**只做爆炸保护，不做流量控制**。上界极高
 * （[[SessionMailbox.DefaultMaxPending]] = 10000，`-D nebflow.mailbox.maxPending`
 * 可覆写），只在极端积压时拒绝 + 计数 + 可见；绝不 TTL / 自动丢弃 / 常态限流。
 * 头部「积压零墙钟、一件不丢」仍成立。
 *
 * ## 本 spec 钉住的三条边
 *
 * - **G1（闸真的会关）**：pending 深度 == 上限时，**新条目**入队返回
 *   `Failed("mailbox-full")`——条目**不落盘**、深度不变、绝不静默吞。
 * - **G2（闸不误伤）**：同一满箱状态下 `Coalesced`（合条）与 `AlreadyPresent`
 *   （幂等防写重）**必须在闸之前返回**——满箱时合条照常合、幂等照常幂等。
 * - **G3（-D 面 + 常规不回归）**：`-D nebflow.mailbox.maxPending` 调到不可达时，
 *   Enqueued / AlreadyPresent / Coalesced 三条路径与今日（无闸时代）完全一致。
 *
 * 🔴 非空洞：G1 先断言**前置深度确为 10000**（否则「满了」这句话本身是假的）；
 * G2 先断言满箱（深度仍是 10000）再断言 Coalesced 到手——若闸挪到 `case None`
 * 之外，G2 会立刻红。
 *
 * 🔴 零污染：数据根隔离在 `target/` 之外的临时目录（`PathUtil.setDataRoot`），
 * 不触 real-HOME；不监听端口；`-D` 覆写只**调大**（对并行套件只会更宽松，
 * 不会造成别人的条目被拒），测后**逐字还原**。
 */
class MailboxExplosionGuardSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private val originalRoot = PathUtil.dataRoot
  private val tmp: os.Path = os.Path(java.nio.file.Files.createTempDirectory("nb-mailbox-guard"))
  PathUtil.setDataRoot(tmp)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    if os.exists(tmp) then os.remove.all(tmp)

  private val limitKey = "nebflow.mailbox.maxPending"
  private val fullSid = "guard-full"
  private val seedCount = SessionMailbox.DefaultMaxPending

  private def evt(payload: String, eventType: String = "completed"): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = eventType,
      payload = payload,
      metadata = JsonObject.empty,
      correlationId = None
    )

  private def enqueue(
    sid: String,
    identity: String,
    payload: String,
    coalesce: Boolean = false
  ): IO[SessionMailbox.MailResult] =
    SessionMailbox.mailOnce(sid, evt(payload), kind = "delegate-terminal", identity = identity, coalesce = coalesce)

  private def pendingNames(sid: String): IO[List[String]] =
    IO.blocking {
      val dir = tmp / "sessions" / sid / "mailbox" / "pending"
      if !os.exists(dir) then Nil else os.list(dir).map(_.last).toList
    }

  /**
   * 把 `guard-full` 的 pending 铺到**恰好**上限（10000 条**合法**条目）。
   *
   * 必须写合法 Entry JSON：`mailOnce` 会用 `existingPending` 扫盘解码，坏形件
   * 会被搬去 quarantine（那是另一条不变式的处理，不是本 spec 的被测面）。
   * 一次铺满后供 G1/G2 共用（不重复铺 2 万件）。
   */
  private lazy val seedFull: Unit =
    val dir = tmp / "sessions" / fullSid / "mailbox" / "pending"
    os.makeDir.all(dir)
    (1 to seedCount).foreach { i =>
      val id = f"seed-$i%05d"
      val e = SessionMailbox.Entry(
        seq = i.toLong,
        identity = id,
        kind = "delegate-terminal",
        coalesce = false,
        enqueuedAt = 1000L,
        producer = "guard-spec",
        suppressedCount = 0,
        event = evt(s"seed #$i")
      )
      os.write.over(dir / f"$i%012d-$id.json", e.asJson.noSpaces)
    }

  // ── G1：满箱后第 N+1 条新条目被拒 ───────────────────────────────────────

  test("G1: at pending depth == MaxPending the NEXT new entry is Failed(\"mailbox-full\") — not on disk, depth unchanged, never silent") {
    seedFull
    for
      depthBefore <- SessionMailbox.depth(fullSid)
      res <- enqueue(fullSid, "overflow-new", "one too many")
      depthAfter <- SessionMailbox.depth(fullSid)
      names <- pendingNames(fullSid)
    yield
      // 非空洞前置：「满了」这件事本身必须先为真
      assertEquals(SessionMailbox.maxPending, seedCount, "the live gate value must equal the seeded depth (else 'full' is meaningless)")
      assertEquals(depthBefore, seedCount, "precondition: the pending depth must sit exactly at MaxPending")
      assertEquals(
        clue(res),
        SessionMailbox.MailResult.Failed("mailbox-full"): SessionMailbox.MailResult,
        "the N+1-th NEW entry must be rejected with the explicit mailbox-full failure"
      )
      assertEquals(depthAfter, seedCount, "a rejected entry must not change the depth")
      assert(
        !names.exists(_.contains("overflow-new")),
        s"the rejected entry must NOT be on disk (explosion guard never half-writes), got ${names.count(_.contains("overflow"))} hits"
      )
      assertEquals(clue(names.size), seedCount, "no file added, none removed")
  }

  // ── G2：满箱时合条 / 幂等仍在闸之前生效（闸不误伤）─────────────────────

  test("G2: while the box is FULL, Coalesced (merge) and AlreadyPresent still reach their old outcomes — the gate must not misfire") {
    seedFull
    for
      depthBefore <- SessionMailbox.depth(fullSid)
      merged <- enqueue(fullSid, "seed-00001", "notice v2", coalesce = true)
      again <- enqueue(fullSid, "seed-00002", "done twice", coalesce = false)
      depthAfter <- SessionMailbox.depth(fullSid)
      pending <- SessionMailbox.scanPending(fullSid)
    yield
      assertEquals(SessionMailbox.maxPending, seedCount, "the live gate value must equal the seeded depth (the box is genuinely FULL)")
      assertEquals(depthBefore, seedCount, "precondition: the box is FULL before the merge attempt")
      val c = merged match
        case SessionMailbox.MailResult.Coalesced(e) => e
        case other => fail(
            s"a full box must still coalesce (already-present entries return BEFORE the depth gate), got $other"
          )
      assertEquals(clue(c.seq), 1L, "merge rewrites the existing entry in place (seq/FIFO position unchanged)")
      assertEquals(clue(c.suppressedCount), 1, "the merge absorbed one same-cause notice")
      assertEquals(
        clue(again),
        SessionMailbox.MailResult.AlreadyPresent: SessionMailbox.MailResult,
        "idempotency (non-coalesce duplicate) must still short-circuit before the gate"
      )
      assertEquals(depthAfter, seedCount, "neither branch adds an entry (nothing was dropped either)")
      assertEquals(clue(pending.size), seedCount, "one entry mutated in place, zero added, zero lost")
  }

  // ── G3：-D 调到不可达 ⇒ 常规三路径与今日一致 ────────────────────────────

  test("G3: with -D nebflow.mailbox.maxPending set unreachable, Enqueued / AlreadyPresent / Coalesced behave exactly as before the gate") {
    val had = sys.props.get(limitKey)
    // 只**调大**（对并行套件只会更宽松），测后逐字还原。
    sys.props(limitKey) = "1000000"
    val program = for
      a <- enqueue("guard-unreachable", "task-a", "done once")
      b <- enqueue("guard-unreachable", "task-a", "done twice")
      c <- enqueue("guard-unreachable", "esc-a", "notice v1", coalesce = true)
      d <- enqueue("guard-unreachable", "esc-a", "notice v2", coalesce = true)
      depth <- SessionMailbox.depth("guard-unreachable")
      pending <- SessionMailbox.scanPending("guard-unreachable")
    yield
      assertEquals(SessionMailbox.maxPending, 1000000, "the -D override must be honoured")
      assert(a.isInstanceOf[SessionMailbox.MailResult.Enqueued], s"first write must enqueue, got $a")
      assertEquals(clue(b), SessionMailbox.MailResult.AlreadyPresent: SessionMailbox.MailResult)
      // 今日口径（无闸时代逐字相同）：空箱上**首条** coalesce 写入没有既有 identity
      // 可并，故照样落盘为 Enqueued；**第二条**同 identity 才原位合条为 Coalesced。
      assert(c.isInstanceOf[SessionMailbox.MailResult.Enqueued], s"first coalesce write lands as a NEW entry, got $c")
      val merged = d match
        case SessionMailbox.MailResult.Coalesced(e) => e
        case other => fail(s"second coalesce write must merge in place, got $other")
      assertEquals(clue(merged.seq), 2L, "merge keeps the original FIFO position (seq unchanged)")
      assertEquals(clue(merged.suppressedCount), 1, "merge absorbed exactly one same-cause notice")
      assertEquals(clue(depth), 2, "two entries on disk (task-a + esc-a), no rejections")
      assertEquals(clue(pending.map(_.identity).sorted), List("esc-a", "task-a"))
    program.guarantee(IO {
      had match
        case Some(v) => sys.props(limitKey) = v
        case None => sys.props.remove(limitKey)
      ()
    })
  }

end MailboxExplosionGuardSpec
