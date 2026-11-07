package nebflow.db

import cats.effect.IO
import io.circe.{Json, parser}
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * 六类记录面的快乐路径 + 分片/轮转/索引 + D-12 对拍样例物化（事件流 + 待审单各一条
 * 完整 JSON 落 `target/db-samples/`，收尾报告引用）。
 */
class DbStreamsSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  /** D-12 对拍位：内核事件 envelope 用 pi-logic 最小形状的占位字段名（命名权归其线）。 */
  private def kernelEvent(n: Int, typ: String = "node-failed", subject: String = "node-3f2a"): Json =
    Json.obj(
      "type" -> Json.fromString(typ),
      "occurredAt" -> Json.fromLong(1762224000000L + n),
      "sessionId" -> Json.fromString("s-1"),
      "seqInSession" -> Json.fromInt(n),
      "payload" -> Json.obj(
        "summary" -> Json.fromString(s"sample-$n"),
        "chainId" -> Json.Null
      ),
      "subject" -> Json.fromString(subject)
    )

  private def openDb(root: Path, cfg: DbConfig => DbConfig = identity): DbHandle =
    Db.open(cfg(DbConfig(root))).unsafeRunSync()

  test("events: append + three-state read + paging + envelope integrity"):
    val root = Files.createTempDirectory("db-streams-events")
    val h = openDb(root)
    try
      assertEquals(h.readEvents(0L, 10, logical = false).unsafeRunSync(),
        ReadResult.Absent(), "空流 = Absent（不是空表顶替）")
      val s1 = h.appendEvent(kernelEvent(1), Some("proj-a:node-3f2a:failed:1762224000001")).unsafeRunSync()
      val s2 = h.appendEvent(kernelEvent(2), None).unsafeRunSync()
      assert(s2 > s1, "seq 单调")
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.size, 2)
          assertEquals(sr.tornTailDropped, 0)
          val env = sr.records.head
          assertEquals(env.seq, s1)
          assert(env.checksum.startsWith("sha256:"))
          assert(EventEnvelopeTest.verifyChecksum(env), "checksum = sha256(noSpaces(event))")
          assertEquals(env.event.hcursor.downField("type").as[String].toOption, Some("node-failed"),
            "内核字段原样不动（D-12：外层只包 seq/checksum/dedupKey）")
        case other => fail(s"unexpected $other")
      // fromSeq 分页。
      h.readEvents(s2, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) => assertEquals(sr.records.map(_.seq), List(s2))
        case other => fail(s"unexpected $other")
      verifyOk(h)
    finally h.close()

  test("events: shard rotation by bytes + idx reflects shards + oversized line admitted"):
    val root = Files.createTempDirectory("db-streams-shards")
    // tiny cap: 强制快速轮转。
    val h = openDb(root, c => c.copy(shardMaxBytes = 240L))
    try
      (1 to 12).foreach(n => h.appendEvent(kernelEvent(n), None).unsafeRunSync())
      val idx = h.eventIndex().unsafeRunSync()
      assert(idx.size >= 3, s"expected rotation, idx=${idx.size}")
      assertEquals(idx.map(_.shard), idx.map(_.shard).sorted, "idx 按片号有序")
      // 各片首尾 seq 链式衔接（不重号不跳号跨片——计数器单源）。
      idx.zip(idx.drop(1)).foreach { (a, b) => assertEquals(b.firstSeq, a.lastSeq + 1L) }
      // 单行超限例外放行。
      val big = kernelEvent(99).deepMerge(Json.obj(
        "payload" -> Json.obj("blob" -> Json.fromString("x" * 400))))
      h.appendEvent(big, None).unsafeRunSync()
      val idx2 = h.eventIndex().unsafeRunSync()
      assert(idx2.size > idx.size, "oversized line lands in its own shard")
      verifyOk(h)
    finally h.close()

  test("sessions: llm/ui append, compact point + snapshots, lazy index"):
    val root = Files.createTempDirectory("db-streams-sessions")
    val h = openDb(root)
    try
      val s1 = h.appendMessage("s-1", "llm", Json.obj("role" -> Json.fromString("user"),
        "text" -> Json.fromString("hello"))).unsafeRunSync()
      h.appendMessage("s-1", "ui", Json.obj("kind" -> Json.fromString("bubble"),
        "text" -> Json.fromString("hi"))).unsafeRunSync()
      // 快照 + 压缩点。
      val n1 = h.writeSnapshot("s-1", """{"messages":["a","b"],"tokens":120}""").unsafeRunSync()
      assertEquals(n1, 1)
      val n2 = h.writeSnapshot("s-1", """{"messages":["a"],"tokens":60}""").unsafeRunSync()
      assertEquals(n2, 2)
      h.appendCompactPoint("s-1", beforeRef = s"snapshots/$n1.json",
        afterRef = s"snapshots/$n2.json", tokens = 60L).unsafeRunSync()
      // 读面。
      h.readSession("s-1", "llm").unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.map(_.kind),
            List("message.append", "compact-point"))
          val cp = sr.records(1).payload.as[SessionEnvelopeTest.CompactPoint].toOption.get
          assertEquals(cp.beforeRef, "snapshots/1.json")
        case other => fail(s"unexpected $other")
      h.readSession("s-1", "ui").unsafeRunSync() match
        case ReadResult.Present(sr) => assertEquals(sr.records.size, 1)
        case other => fail(s"unexpected $other")
      h.readSnapshot("s-1", 1).unsafeRunSync() match
        case ReadResult.Present(text) => assert(text.contains("tokens"))
        case other => fail(s"unexpected $other")
      assertEquals(h.readSnapshot("s-1", 99).unsafeRunSync(), ReadResult.Absent())
      // 惰性索引（§3.11 步 6）。
      val idx = h.sessionIndex("s-1", "llm").unsafeRunSync()
      assertEquals(idx.totalLines, 2L)
      assertEquals(idx.tornTails, 0)
      verifyOk(h)
    finally h.close()

  test("pending: open → close appends resolution row, original row bytes untouched"):
    val root = Files.createTempDirectory("db-streams-pending")
    val h = openDb(root)
    try
      val card = PendingRecordTest.card("r-1")
      val seqOpen = h.openPending(card).unsafeRunSync()
      val file = root.resolve("db").resolve("pending").resolve("permission.jsonl")
      val afterOpen = Files.readString(file)
      // 闭单 = 追加决议行。
      assert(h.closePending(PendingStream.Permission, "r-1", closedBy = "user",
        closeReason = "granted", now = 1762224100000L).unsafeRunSync())
      val afterClose = Files.readString(file)
      assert(afterClose.startsWith(afterOpen), "原行字节不动（闭单只追加）")
      assert(afterClose.linesIterator.count(_.nonEmpty) == 2)
      // 幂等：再闭 ⇒ false，零追加。
      assert(!h.closePending(PendingStream.Permission, "r-1", "user", "granted",
        1762224200000L).unsafeRunSync())
      assertEquals(Files.readString(file), afterClose)
      h.readPending(PendingStream.Permission).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.size, 2)
          assertEquals(sr.records.last.state, "closed")
          assertEquals(sr.records.last.closeReason, Some("granted"))
        case other => fail(s"unexpected $other")
      verifyOk(h)
    finally h.close()

  test("schedule: put → tombstone → logical view flips; re-put revives with content"):
    val root = Files.createTempDirectory("db-streams-schedule")
    val h = openDb(root)
    try
      val task = Json.obj("id" -> Json.fromString("t-1"),
        "content" -> Json.fromString("daily report"), "triggerAt" -> Json.fromLong(1762224000000L))
      h.appendScheduleTask("s-9", task, 1762224000000L).unsafeRunSync()
      h.liveScheduleTasks("s-9").unsafeRunSync() match
        case ReadResult.Present(tasks) => assertEquals(tasks.size, 1)
        case other => fail(s"unexpected $other")
      val file = root.resolve("db").resolve("schedule").resolve("s-9.jsonl")
      val bytesBefore = Files.readString(file)
      // 逻辑删除：视图翻转，原文不动。
      assert(h.tombstoneScheduleTask("s-9", "t-1", 1762224100000L).unsafeRunSync())
      h.liveScheduleTasks("s-9").unsafeRunSync() match
        case ReadResult.Present(tasks) => assertEquals(tasks.size, 0, "tombstoned task drops out")
        case other => fail(s"unexpected $other")
      assert(Files.readString(file).startsWith(bytesBefore), "V10: 原文字节不变")
      // 复活 = 重新 put 同 id 完整行（upsert 语义）。
      h.appendScheduleTask("s-9", task, 1762224200000L).unsafeRunSync()
      h.liveScheduleTasks("s-9").unsafeRunSync() match
        case ReadResult.Present(tasks) =>
          assertEquals(tasks.size, 1)
          assertEquals(tasks.head.taskDef.hcursor.downField("content").as[String].toOption,
            Some("daily report"), "复活带完整内容（非桩）")
        case other => fail(s"unexpected $other")
      // 不存在的任务 tombstone ⇒ false。
      assert(!h.tombstoneScheduleTask("s-9", "t-404", 1L).unsafeRunSync())
      verifyOk(h)
    finally h.close()

  test("guard: write/read/reopen round-trip for both docs"):
    val root = Files.createTempDirectory("db-streams-guard")
    val h = openDb(root)
    try
      h.writeNotifyGuard(GuardDocsTest.notify("dispatch-notify:failed",
        List(1762224000000L, 1762224005000L), 1762224300000L, 1762224005000L)).unsafeRunSync()
      h.writeAvoidGuard(GuardDocsTest.avoid(Map("openai" -> 1762224300000L),
        1762224005000L)).unsafeRunSync()
      h.readNotifyGuard("dispatch-notify:failed").unsafeRunSync() match
        case ReadResult.Present(d) =>
          assertEquals(d.failedAt, List(1762224000000L, 1762224005000L))
          assertEquals(d.cooldownUntil, 1762224300000L)
        case other => fail(s"unexpected $other")
      h.readAvoidGuard("provider-avoid").unsafeRunSync() match
        case ReadResult.Present(d) => assertEquals(d.avoidUntil("openai"), 1762224300000L)
        case other => fail(s"unexpected $other")
      verifyOk(h)
    finally h.close()

  test("D-12 对拍样例物化：事件流 + 待审单各一条完整 JSON 落 target/db-samples/"):
    val root = Files.createTempDirectory("db-streams-samples")
    val h = openDb(root)
    try
      h.appendEvent(kernelEvent(1, typ = "node-failed", subject = "node-3f2a"),
        Some("proj-a:node-3f2a:failed:1762224000001")).unsafeRunSync()
      val card = PendingRecordTest.card("r-1", kind = "Permission")
      h.openPending(card).unsafeRunSync()
      h.closePending(PendingStream.Permission, "r-1", "user", "granted",
        1762224100000L).unsafeRunSync()
      // 物化到 target/db-samples/（报告对拍面；target 不进 git）。
      val outDir = Path.of(sys.props("user.dir")).resolve("target").resolve("db-samples")
      Files.createDirectories(outDir)
      val eventsFile = root.resolve("db").resolve("events").resolve("events.1.jsonl")
      val pendingFile = root.resolve("db").resolve("pending").resolve("permission.jsonl")
      Files.writeString(outDir.resolve("event.jsonl"), Files.readString(eventsFile))
      Files.writeString(outDir.resolve("pending.jsonl"), Files.readString(pendingFile))
      // 样例自检：两文件首行都解析为 JSON 且带设计面字段。
      val ev = parser.parse(Files.readString(eventsFile).linesIterator.next()).toOption.get
      assert(ev.hcursor.downField("seq").as[Long].isRight)
      assert(ev.hcursor.downField("checksum").as[String].isRight)
      assert(ev.hcursor.downField("event").succeeded)
      val pd = parser.parse(Files.readString(pendingFile).linesIterator.next()).toOption.get
      assert(pd.hcursor.downField("requestId").as[String].isRight)
      assert(pd.hcursor.downField("state").as[String].isRight)
    finally h.close()

  private def verifyOk(h: DbHandle): Unit =
    h.verify().unsafeRunSync() match
      case r if r.ok => ()
      case r => fail(s"verify not ok: $r")

end DbStreamsSpec

/** 测试内窥（主代码 API 不外泄内部 codec——测试面直接调 format 包）。 */
object EventEnvelopeTest:
  def verifyChecksum(e: nebflow.db.format.EventEnvelope): Boolean =
    nebflow.db.format.EventEnvelope.verify(e)

object SessionEnvelopeTest:
  type CompactPoint = nebflow.db.format.SessionEnvelope.CompactPoint

object PendingRecordTest:
  import nebflow.db.format.PendingRecord
  def card(requestId: String, kind: String = "Permission"): PendingRecord.PendingCard =
    PendingRecord.PendingCard(
      requestId = requestId,
      kind = kind,
      rootSessionId = "s-1",
      sourceSession = "s-2",
      sourceAgent = "stand",
      firstQuestion = "允许执行该命令吗？",
      payload = Json.obj("command" -> Json.fromString("rm -rf /tmp/x"))
    )

object GuardDocsTest:
  import nebflow.db.format.GuardDocs
  def notify(source: String, failedAt: List[Long], cooldownUntil: Long,
    updatedAt: Long): GuardDocs.NotifyGuardDoc =
    GuardDocs.NotifyGuardDoc(1, source, failedAt, cooldownUntil, updatedAt)
  def avoid(avoidUntil: Map[String, Long], updatedAt: Long): GuardDocs.AvoidGuardDoc =
    GuardDocs.AvoidGuardDoc(1, "provider-avoid", avoidUntil, updatedAt)
