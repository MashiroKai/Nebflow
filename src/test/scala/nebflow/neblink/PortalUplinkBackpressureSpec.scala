package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.neblink.PortalWireCodec.*
import nebflow.shared.PathUtil
import org.http4s.HttpApp

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.duration.*

/**
 * 台账 #949（portal 上行背压）三件套的钉子：
 *
 *  - 面一·同因限频：同一 requestId 的「inbox full」WARN 首条 + 每 32 事件一条，
 *    恢复（状态变化）即报——根治「一行×每秒」刷屏；
 *  - 面二·排队等容量：inbox 满不再丢件——拍退避等容量（翻倍封顶，零墙钟），
 *    周期死亡件进持久死信区（dataRoot 下，带来源与 requestId）；
 *  - 面二·死信两态迁移：落盘 →（relay 恢复）按序补投 → 成功出队 / 失败留盘；
 *    teardown 收尾残件同样全量落盘。
 */
class PortalUplinkBackpressureSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-uplink-bp-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── harness（PortalTunnelBridgeSpec 同款最小桩） ─────────────────────────

  private final class FakeWebSocket extends java.net.http.WebSocket:
    val sent = new ConcurrentLinkedQueue[String]()
    @volatile var aborted = false
    private def done: java.util.concurrent.CompletableFuture[java.net.http.WebSocket] =
      java.util.concurrent.CompletableFuture.completedFuture(this)
    override def sendText(data: CharSequence, last: Boolean): java.util.concurrent.CompletableFuture[java.net.http.WebSocket] =
      sent.add(data.toString); done
    override def sendBinary(data: java.nio.ByteBuffer, last: Boolean): java.util.concurrent.CompletableFuture[java.net.http.WebSocket] =
      done
    override def sendClose(code: Int, reason: String): java.util.concurrent.CompletableFuture[java.net.http.WebSocket] =
      done
    override def request(n: Long): Unit  = ()
    override def sendPing(data: java.nio.ByteBuffer): java.util.concurrent.CompletableFuture[java.net.http.WebSocket] = done
    override def sendPong(data: java.nio.ByteBuffer): java.util.concurrent.CompletableFuture[java.net.http.WebSocket] = done
    override def getSubprotocol: String  = ""
    override def isOutputClosed: Boolean = false
    override def isInputClosed: Boolean  = false
    override def abort(): Unit           = aborted = true

  private def mkBridge(fake: FakeWebSocket): PortalTunnelBridge =
    val faces = new PortalHostFaces(gatewayPort = 1)
    faces.bindHttpApp(HttpApp[IO](_ => IO.raiseError(new IllegalStateException("not under test"))))
    new PortalTunnelBridge(
      new PortalTunnelDeps(
        faces,
        ConsoleTicketStore.unsafeCreate(),
        PortalBindingState.unsafeCreate(),
        IO.pure(DeviceIdentity("qa-device", "qa-host", "windows")),
        IO(0)
      ),
      new RelayFrameSender(fake)
    )

  // 传过 PortalWireCodec 的 requestId 必须是 wire 合法 UUID（jsonUuid 白名单：
  // v1-5 / [89ab] variant）——需要 decode 回读断言的用例一律用这些。
  private val UuidDead = "00000000-0000-4000-8000-000000000007"
  private val UuidT1   = "00000000-0000-4000-8000-0000000000d1"
  private val UuidT2   = "00000000-0000-4000-8000-0000000000d2"
  private val UuidRe   = "00000000-0000-4000-8000-000000000009"

  // Inbox depth follows the production constant (ledger #949 §2 face-③: tuned
  // 128→64) — specs never hardcode it, so a future retune can't rot assertions.
  private val Slots = PortalTunnelBridge.WsQueueSlots

  private def frame(b: Int, rid: String = "rid"): PortalTunnelBridge.WsCycle.UplinkItem.Frame =
    PortalTunnelBridge.WsCycle.UplinkItem.Frame(WsFrame(rid, "text", Array(b.toByte), fin = true))

  private def waitUntil(timeout: FiniteDuration)(cond: IO[Boolean]): IO[Boolean] =
    IO.monotonic.flatMap { start =>
      def loop: IO[Boolean] =
        cond.flatMap { ok =>
          if ok then IO.pure(true)
          else
            IO.monotonic.flatMap { now =>
              if now - start > timeout then IO.pure(false) else IO.sleep(50.millis) *> loop
            }
        }
      loop
    }

  // ── 面一：同因限频 ────────────────────────────────────────────────────────

  test("面一: 满态首条即报、中间静默计数、第32条再报、恢复即报、新回合重新起算"):
    val alarm = new UplinkFullAlarm("req-1")
    // 第 1 条：状态变化（flowing → full），即报
    val first = alarm.strike(depth = Slots, capacity = Slots, itemBytes = 4096)
    assert(first.isDefined, "first strike must log")
    assert(clue(first.get).contains("strike=1"))
    assert(clue(first.get).contains("QUEUED, NOT dropped"), "the line must say the item is queued, not dropped")
    // 第 2..31 条：限频吞掉
    (2 to 31).foreach { n =>
      val l = alarm.strike(Slots, Slots, 4096)
      assert(l.isEmpty, s"strike #$n must be suppressed")
    }
    assert(alarm.strikeCount == 31)
    // 第 32 条：再报一条
    val at32 = alarm.strike(Slots, Slots, 4096)
    assert(clue(at32).isDefined, "strike #32 must log")
    assert(clue(at32.get).contains("strike=32"))
    assert(clue(at32.get).contains("suppressed=30"), "the compression reading must be carried")
    // 恢复：状态变化即报，带回合计数
    val rec = alarm.recovered()
    assert(clue(rec).isDefined, "recovery must log (state change)")
    assert(clue(rec.get).contains("32 full strikes"))
    assert(clue(rec.get).contains("suppressed=30"))
    // 新回合重新起算
    val again = alarm.strike(Slots / 2, Slots, 1)
    assert(clue(again).isDefined, "new episode first strike must log")
    assert(clue(again.get).contains("episode=2"))
    // 回合内又静默 31 条
    (2 to 31).foreach { _ =>
      assert(alarm.strike(Slots / 2, Slots, 1).isEmpty)
    }

  test("面一: 无满态时 recovered 静默（不刷无事件恢复行）"):
    val alarm = new UplinkFullAlarm("req-2")
    assert(alarm.recovered().isEmpty, "recovery without any full strike must not log")

  // ── 面二：拍退避（翻倍封顶，零墙钟） ─────────────────────────────────────

  test("面二: 拍退避按轮翻倍、封顶 16 拍（400ms），无任何放弃语义"):
    assertEquals(UplinkBackoff.beatsFor(1), 1)
    assertEquals(UplinkBackoff.beatsFor(2), 2)
    assertEquals(UplinkBackoff.beatsFor(3), 4)
    assertEquals(UplinkBackoff.beatsFor(4), 8)
    assertEquals(UplinkBackoff.beatsFor(5), 16)
    assertEquals(UplinkBackoff.beatsFor(6), 16, "cap holds from round 6 on")
    assertEquals(UplinkBackoff.beatsFor(1000), 16)
    assertEquals(UplinkBackoff.parkMillisFor(6), 16L * UplinkBackoff.BaseBeatMillis)

  // ── 面二：inbox full 排队等容量（不丢件） ────────────────────────────────

  test("面二: 满队列时件排队等容量——pump 腾位后入队，零丢件零死信"):
    Dispatcher.parallel[IO].use { _ =>
      val fake = new FakeWebSocket
      val bridge = mkBridge(fake)
      for
        cycle <- IO(new PortalTunnelBridge.WsCycle("wait-capacity"))
        _ = bridge.wsCycles.put("wait-capacity", cycle) // 注册 = 活周期
        _ = (0 until Slots).foreach(i => cycle.uplink.offer(frame(i))) // 填满
        // 后台驱动：满了 → 拍退避等待（不丢）
        worker <- IO.blocking(
          bridge.offerUplinkAwaitCapacity(cycle, frame(0xAA))
        ).start
        _ <- IO.sleep(100.millis)
        early <- IO(cycle.uplink.size())
        _ = assertEquals(early, Slots, "while full, the item must NOT be enqueued NOR dropped")
        deadEarly <- PortalUplinkDeadLetter.pending
        _ = assertEquals(deadEarly, Nil, "a live cycle must never dead-letter")
        // 模拟 pump 腾一位
        _ = cycle.uplink.poll()
        done <- waitUntil(10.seconds)(worker.join.attempt.map(_.isRight))
        _ = assert(done, "the waiter must land the item once capacity appears")
        landed <- IO(cycle.uplink.size())
        _ = assertEquals(landed, Slots, "the item must be QUEUED once capacity appeared (never dropped)")
        // FIFO 保持：抽干队列，最后一件必须是等容量进来的 0xAA（poll 序 == 入队序）
        drained <- IO(List.fill(Slots + 1)(Option(cycle.uplink.poll())))
        _ = assert(drained.take(Slots).forall(_.isDefined), s"queue must hold exactly $Slots items")
        _ = assert(drained(Slots).isEmpty, s"queue must be empty after $Slots polls")
        lastByte = drained(Slots - 1).collect { case PortalTunnelBridge.WsCycle.UplinkItem.Frame(f) => f.data(0) }
        _ = assertEquals(lastByte, Some(0xAA.toByte), "the waited item must keep FIFO order (offered last)")
        deadAfter <- PortalUplinkDeadLetter.pending
        _ = assertEquals(deadAfter, Nil)
      yield ()
    }

  test("面二: 等容量期间周期死亡——件进持久死信区（带来源与 requestId），不入队"):
    val fake = new FakeWebSocket
    val bridge = mkBridge(fake)
    val cycle = new PortalTunnelBridge.WsCycle(UuidDead) // 从不注册 = 已死
    (0 until Slots).foreach(i => cycle.uplink.offer(frame(i, UuidDead)))
    for
      _ <- IO.blocking(bridge.offerUplinkAwaitCapacity(cycle, frame(0x07, UuidDead))) // 死亡分支：同步返回，不等待
      _ = assertEquals(cycle.uplink.size(), Slots, "the dead cycle's queue must be untouched")
      entries <- PortalUplinkDeadLetter.pending
      _ = assert(entries.size == 1, s"exactly one dead letter, got ${entries.map(e => (e.requestId, e.kind))}")
      e = entries.head
      _ = assertEquals(e.requestId, UuidDead)
      _ = assertEquals(e.source, PortalUplinkDeadLetter.SourceWaitCycleDeath)
      _ = assertEquals(e.kind, "ws_frame")
      _ = assertEquals(e.bytes, 1L)
      // 帧原文可回放：解码回来即原帧（wire 合法 UUID，round-trip 完整）
      decoded = PortalWireCodec.decode(e.frameText)
      _ = decoded match
        case Right(WsFrame(rid2, kind2, data2, fin2)) =>
          assertEquals(rid2, UuidDead)
          assertEquals(kind2, "text")
          assertEquals(data2.toList, List(0x07.toByte))
          assert(fin2)
        case other => fail(s"dead-letter frameText must decode to the original ws_frame, got $other")
    yield ()

  // ── 面二：teardown 收尾残件全量死信 ──────────────────────────────────────

  test("面二: teardown 收尾——两个周期残件全量落盘、按队序保序、来源=teardown"):
    val fake = new FakeWebSocket
    val bridge = mkBridge(fake)
    for
      c1 <- IO(new PortalTunnelBridge.WsCycle(UuidT1))
      c2 <- IO(new PortalTunnelBridge.WsCycle(UuidT2))
      _ = bridge.wsCycles.put(UuidT1, c1)
      _ = bridge.wsCycles.put(UuidT2, c2)
      _ = c1.local = fake
      _ = c2.local = fake
      _ = { c1.uplink.offer(frame(1, UuidT1)); c1.uplink.offer(frame(2, UuidT1)) }
      _ = c2.uplink.offer(frame(3, UuidT2))
      _ <- bridge.teardown()
      entries <- PortalUplinkDeadLetter.pending
      _ = assertEquals(entries.size, 3, s"all pending uplink items must be dead-lettered, got ${entries.map(e => (e.requestId, e.bytes))}")
      _ = assert(entries.forall(_.source == PortalUplinkDeadLetter.SourceTeardown))
      _ = assert(entries.forall(e => e.requestId == UuidT1 || e.requestId == UuidT2))
      // 每周期内部保序（poll 序 == 死信文件序）
      c1Bytes = entries.filter(_.requestId == UuidT1).flatMap { e =>
        PortalWireCodec.decode(e.frameText).toOption.collect { case WsFrame(_, _, data, _) => data(0) }
      }
      _ = assertEquals(c1Bytes.toList, List(1.toByte, 2.toByte), "per-cycle FIFO must survive the dead letter")
      _ = assert(bridge.wsCycles.isEmpty, "cycles leave the registry at teardown")
    yield ()

  // ── 面二：死信两态迁移（落盘 → 补投出队 / 失败留盘） ─────────────────────

  test("面二: 死信迁移——补投成功出队（文件消失）；失败留盘（attempts 读数升）"):
    for
      _ <- IO(PortalUplinkDeadLetter.enqueueSync("req-A", PortalUplinkDeadLetter.SourceTeardown, "ws_frame", """{"type":"ws_frame"}""", 9L, "unit"))
      _ <- IO(PortalUplinkDeadLetter.enqueueSync("req-B", PortalUplinkDeadLetter.SourceTeardown, "relay_close", """{"type":"ws_close"}""", 0L, "unit"))
      first <- PortalUplinkDeadLetter.pending
      _ = assertEquals(first.map(_.requestId).sorted, List("req-A", "req-B"))
      // 成功腿：全出队
      ok <- PortalUplinkDeadLetter.drain(_ => IO.unit)
      _ = assertEquals(ok.replayed.size, 2)
      _ = assertEquals(ok.kept, Nil)
      after <- PortalUplinkDeadLetter.pending
      _ = assertEquals(after, Nil, "delivered entries must leave the disk")
      // 失败腿：留盘 + attempts 读数
      _ <- IO(PortalUplinkDeadLetter.enqueueSync("req-C", PortalUplinkDeadLetter.SourceTeardown, "ws_frame", """{"type":"ws_frame"}""", 1L, "unit"))
      fail <- PortalUplinkDeadLetter.drain(_ => IO.raiseError(new java.io.IOException("relay down")))
      _ = assertEquals(fail.replayed, Nil)
      _ = assertEquals(fail.kept.size, 1, "the failed entry must be reported kept")
      kept <- PortalUplinkDeadLetter.pending
      _ = assertEquals(kept.map(_.requestId), List("req-C"), "failed entries stay on disk")
      _ = assertEquals(kept.headOption.map(_.attempts), Some(1), "attempts is a pure reading, bumped per try")
    yield ()

  test("面二: 补投严格按入队序（文件名序 == poll 序）"):
    for
      entries <- IO {
        (1 to 5).map { i =>
          PortalUplinkDeadLetter.enqueueSync("req-order", PortalUplinkDeadLetter.SourceTeardown, "ws_frame", s"""{"i":$i}""", i.toLong, "unit")
          i
        }.toList
      }
      _ = assertEquals(entries, List(1, 2, 3, 4, 5))
      seen <- IO(new ConcurrentLinkedQueue[Long]())
      rep <- PortalUplinkDeadLetter.drain(e => IO(seen.add(e.bytes)).void)
      _ = assertEquals(rep.replayed.size, 5)
      order <- IO { var acc = List.empty[Long]; seen.forEach(b => acc = acc :+ b); acc }
      _ = assertEquals(order, List(1L, 2L, 3L, 4L, 5L), "replay must follow enqueue order")
      after <- PortalUplinkDeadLetter.pending
      _ = assertEquals(after, Nil)
    yield ()

  test("面二: bridge.replayDeadLetters 经当前连接 sender 补投并出队"):
    val fake = new FakeWebSocket
    val bridge = mkBridge(fake)
    for
      _ <- IO(
        PortalUplinkDeadLetter.enqueueSync(
          UuidRe,
          PortalUplinkDeadLetter.SourceWaitCycleDeath,
          "ws_frame",
          // wire 面数据是 base64——断言走 decode round-trip，不做原文 contains
          PortalWireCodec.encode(WsFrame(UuidRe, "text", "replay-me".getBytes(UTF_8), fin = true)),
          9L,
          "unit"
        )
      )
      _ <- bridge.replayDeadLetters
      sent = fake.sent.toArray().toList.map(_.toString)
      replayedOk = sent.exists { t =>
        PortalWireCodec.decode(t) match
          case Right(WsFrame(rid2, kind2, data2, fin2)) =>
            rid2 == UuidRe && kind2 == "text" && data2.toList == "replay-me".getBytes(UTF_8).toList && fin2
          case _ => false
      }
      _ = assert(replayedOk, s"the frame text must go out through the sender (decode round-trip), sent=$sent")
      after <- PortalUplinkDeadLetter.pending
      _ = assertEquals(after, Nil, "replayed entries leave the disk")
    yield ()

  test("面二: 空死信区补投是 no-op"):
    val fake = new FakeWebSocket
    val bridge = mkBridge(fake)
    for
      _ <- bridge.replayDeadLetters
      sent = fake.sent.toArray().toList
      _ = assertEquals(sent.size, 0, "empty dead letter must not touch the wire")
    yield ()

end PortalUplinkBackpressureSpec
