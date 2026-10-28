package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * 台账 #944（跨设备回报丢件腿）承重断言：**完成件先落盘、断连窗口不丢、重连补投**。
 *
 * 实证病灶（2026-10-26 双侧日志对账）：
 *  - KAI 14:39:04 进程重启，把在飞的 6 个 device-delegate 会话连**回投代理 actor**
 *    一起杀掉 ⇒ 终态事件永不到达 ⇒ 6 件**从未离机**（KAI `result_sent=0` 的 6 个
 *    spawnId 与 boot 收殓的 6 个孤儿 id 完全重合）；
 *  - boot 收殓腿（[[nebflow.agent.SubAgentStartupRecovery]]）按「父会话在本机」判，
 *    跨设备腿恒假 ⇒ 通知整条跳过，lastError 却写「delivered to parent」（静默蒸发）；
 *  - Mac 侧只收到 15:27 后报的 #939 一件，14:39 前的全丢。
 *
 * 本 spec 钉的修法面：
 *  - **落盘待发**：件产出即入 [[DeviceResultOutbox]]（发送失败/进程死都还在盘上）；
 *  - **重连补投**：relay 恢复后按入队序逐件补投，成功后出队；
 *  - **宁重勿丢**：投递失败件留盘（不删不超时），下一轮继续；
 *  - **幂等**：同 id 重复入队不重写不重排；宿主侧按 correlationId(=spawnId) 判重。
 *
 * 全部用例以纯桩驱动（无网络无 actor），零墙钟依赖。
 */
class DeviceResultOutboxSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-outbox"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  private def payloadOf(spawnId: String, text: String): Json =
    Json.obj(
      "kind" -> "delegate-result".asJson,
      "spawnId" -> spawnId.asJson,
      "status" -> "completed".asJson,
      "result" -> text.asJson
    )

  private def enqueue(id: String, text: String = "done", target: String = "host-dev"): IO[DeviceResultOutbox.Entry] =
    DeviceResultOutbox.enqueue(target, "delegate", payloadOf(id, text), id)

  // ── ① 落盘待发（断连窗口里产出 ⇒ 件在盘上）───────────────────────────

  test("W1: an entry is on disk the moment it is produced — survives a 'process death' before send") {
    val io = for
      _ <- enqueue("dd-1", "result one")
      _ <- enqueue("dd-2", "result two")
      // 模拟发送腿从未跑到（进程死/隧道不在）：不调用 drain 直接重读盘面。
      onDisk <- DeviceResultOutbox.pending
      // "重启"后的读面——同一盘面，零内存态。
      afterBoot <- IO.blocking(DeviceResultOutbox.pending).flatten
    yield (onDisk, afterBoot)

    val (onDisk, afterBoot) = io.unsafeRunSync()
    assertEquals(clue(onDisk).map(_.id), List("dd-1", "dd-2"))
    assertEquals(clue(afterBoot).map(_.id), List("dd-1", "dd-2"), "outbox is the durability face: disk == truth")
    assertEquals(onDisk.map(_.payload.hcursor.downField("spawnId").as[String].toOption), List(Some("dd-1"), Some("dd-2")))
  }

  // ── ② 断连窗口 → 重连补投（两次状态迁移：留在盘上 → 出队送达）─────────

  test("W2: entries held through the disconnect window are replayed on reconnect, in enqueue order") {
    val sent = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    // 第一次 drain：对端不可达（relay 断连窗口）——件必须留盘。
    val offline: (String, String, Json) => IO[Either[String, Json]] =
      (_, _, _) => IO.pure(Left("presence send failed and relay fallback failed: relay offline"))

    // 第二次 drain：relay 重连成功——件按序送达并出队。
    val online: (String, String, Json) => IO[Either[String, Json]] =
      (_, _, p) =>
        IO {
          sent.add(p.hcursor.downField("spawnId").as[String].getOrElse("?"))
          Right(p)
        }

    val io = for
      _ <- enqueue("dd-a", "A")
      _ <- enqueue("dd-b", "B")
      _ <- enqueue("dd-c", "C")
      // 迁移 1：断连窗口内一轮补投失败 ⇒ 三件全留盘（宁重勿丢，不删不超时）。
      report1 <- DeviceResultOutbox.drain(offline)
      stillHeld <- DeviceResultOutbox.pending
      // 迁移 2：relay 恢复 ⇒ 按序补投并出队。
      report2 <- DeviceResultOutbox.drain(online)
      drained <- DeviceResultOutbox.pending
    yield (report1, stillHeld, report2, drained)

    val (report1, stillHeld, report2, drained) = io.unsafeRunSync()
    assertEquals(clue(report1.delivered), Nil, "nothing left the device while the relay was down")
    assertEquals(clue(report1.failed).map(_._1), List("dd-a", "dd-b", "dd-c"))
    assertEquals(clue(stillHeld).map(_.id), List("dd-a", "dd-b", "dd-c"), "all three stay queued on disk")

    assertEquals(clue(report2.delivered), List("dd-a", "dd-b", "dd-c"), "reconnect replays everything")
    assertEquals(clue(report2.failed), Nil)
    assertEquals(
      scala.jdk.CollectionConverters.CollectionHasAsScala(sent).asScala.toList,
      List("dd-a", "dd-b", "dd-c"),
      "replay preserves enqueue order (parent renders by arrival order)"
    )
    assertEquals(clue(drained), Nil, "delivered entries leave the outbox")
  }

  // ── ③ 幂等：同 id 重复入队不重写不重排 ────────────────────────────────

  test("W3: re-enqueueing the same id is a no-op (same slot, same order, no duplicate delivery)") {
    val io = for
      first <- enqueue("dd-x", "original")
      second <- enqueue("dd-x", "rewritten")
      held <- DeviceResultOutbox.pending
    yield (first, second, held)

    val (first, second, held) = io.unsafeRunSync()
    assertEquals(clue(held).size, 1, "same id never doubles up")
    assertEquals(second.enqueuedAt, first.enqueuedAt, "the original slot (and thus its queue position) is kept")
  }

  // ── ④ 单件炸不拖垮其余（补投是逐件的）─────────────────────────────────

  test("W4: one throwing entry does not block the rest of the replay") {
    val seen = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val flaky: (String, String, Json) => IO[Either[String, Json]] =
      (_, _, p) =>
        val id = p.hcursor.downField("spawnId").as[String].getOrElse("?")
        if id == "dd-boom" then IO.raiseError(new RuntimeException("socket exploded"))
        else IO { seen.add(id); Right(p) }

    val io = for
      _ <- enqueue("dd-1")
      _ <- enqueue("dd-boom")
      _ <- enqueue("dd-3")
      report <- DeviceResultOutbox.drain(flaky)
      left <- DeviceResultOutbox.pending
    yield (report, left)

    val (report, left) = io.unsafeRunSync()
    assertEquals(clue(report.delivered), List("dd-1", "dd-3"))
    assertEquals(clue(report.failed).map(_._1), List("dd-boom"))
    assertEquals(clue(left).map(_.id), List("dd-boom"), "only the failing entry is retained")
    assertEquals(
      scala.jdk.CollectionConverters.CollectionHasAsScala(seen).asScala.toList,
      List("dd-1", "dd-3"),
      "the batch keeps going after a failure"
    )
  }

  // ── ⑤ 投递成功后出队（成功即删；不重复投）──────────────────────────────

  test("W5: a delivered entry is removed — a second drain sends nothing") {
    var calls = 0
    val counting: (String, String, Json) => IO[Either[String, Json]] =
      (_, _, p) => IO { calls += 1; Right(p) }

    val io = for
      _ <- enqueue("dd-once")
      r1 <- DeviceResultOutbox.drain(counting)
      r2 <- DeviceResultOutbox.drain(counting)
      left <- DeviceResultOutbox.pending
    yield (r1, r2, left)

    val (r1, r2, left) = io.unsafeRunSync()
    assertEquals(clue(r1.delivered), List("dd-once"))
    assertEquals(clue(r2.attempted), 0, "nothing left to replay")
    assertEquals(clue(left), Nil)
    assertEquals(clue(calls), 1, "delivered exactly once (no double send)")
  }

  // ── ⑥ 尝试计数（纯读数：升序可见，不参与任何判失败逻辑）────────────────

  test("W6: attempts is a read-only counter — it rises but never expires or drops an entry") {
    val offline: (String, String, Json) => IO[Either[String, Json]] =
      (_, _, _) => IO.pure(Left("relay offline"))

    val io = for
      _ <- enqueue("dd-retry")
      _ <- DeviceResultOutbox.drain(offline)
      _ <- DeviceResultOutbox.drain(offline)
      _ <- DeviceResultOutbox.drain(offline)
      held <- DeviceResultOutbox.pending
    yield held

    val held = io.unsafeRunSync()
    assertEquals(clue(held).size, 1, "zero-timeout discipline: no wall-clock TTL ever evicts a pending result")
    assertEquals(clue(held).head.attempts, 3)
  }

  // ── ⑦ 坏件不阻断整批（簿记面 fail-soft）──────────────────────────────

  test("W7: an unreadable entry file is skipped (warn) without blocking the readable ones") {
    val io = for
      _ <- enqueue("dd-good")
      // 手写一个坏件直接落进待发目录。
      _ <- IO {
        val d = PathUtil.dataRoot / "device-outbox"
        if !os.exists(d) then os.makeDir.all(d)
        os.write(d / "1-corrupt.json", "{not json")
      }
      held <- DeviceResultOutbox.pending
      report <- DeviceResultOutbox.drain((_, _, p) => IO.pure(Right(p): Either[String, Json]))
    yield (held, report)

    val (held, report) = io.unsafeRunSync()
    assertEquals(clue(held).map(_.id), List("dd-good"), "corrupt entries are skipped, never fatal")
    assertEquals(clue(report.delivered), List("dd-good"))
  }

end DeviceResultOutboxSpec
