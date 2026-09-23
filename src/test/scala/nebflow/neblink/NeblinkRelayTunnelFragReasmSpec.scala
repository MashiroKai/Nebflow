package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * 客户端分片重组修复批（fragreasm-impl，2026-09-19）—— **缺陷复现 + 修复钉**。
 *
 * 缺陷（`NeblinkRelayTunnel.scala:982-984` 修前现读）：`onText` 的 `last` 参数
 * **收到但从不使用** —— 每次 WS 调用都直接把 `data.toString` 当整帧 `decode[Json]`。
 * 后果（两条，机械可分）：
 *   - 大载荷被 WS 分帧传输时，**后继分片被整帧丢弃**：首片是残片 ⇒ JSON 解析失败
 *     ⇒ 后续每片各自走同一路径 ⇒ 零重组、零派发；
 *   - 首片若**恰好自身可解析**，还会被当整帧派发（部分语义帧被消费）。
 *
 * 本 spec 的判据面全部走**真 RFC 6455 socket**（`RelayAuthFixtureServer` 127.0.0.1
 * ephemeral 端口）：分片帧由夹具按线格式逐片写入真 socket，被测算走生产 listener 的
 * `onText` 路径（不是直调内部方法），观测面 = 生产 relay 通道的日志行（W10 未知类型 /
 * W11 不可解析）与夹具记录的客户端文本帧（pong 回执）。
 *
 * 三条判据：
 *   ① 分片端到端重组 100%：3 片（边界**落在 JSON 内部**）⇒ 恰 1 次派发、载荷长度 =
 *      整帧长度（修前：0 次派发 + 3 条 `undecodable`）；
 *   ② 逐字节等价：同一载荷「一次性整帧」与「分片」两条投递路径在派发边界给出
 *      **相同长度/相同类型**（且分片侧零 undecodable）；
 *   ③ 既有分支不因分片而断：分片 `{"type":"ping"}` ⇒ 线上必须有 `{"type":"pong"}`
 *      （对照臂 = 整帧 ping 同样出 pong，证断言非空转）。
 */
class NeblinkRelayTunnelFragReasmSpec extends CatsEffectSuite:

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-fragreasm-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ---- harness（与 NeblinkRelayTunnelKickParkSpec 同款；本批零改写其件）----

  private final class RelayLogAppender
      extends ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
    val lines = new ConcurrentLinkedQueue[String]()

    override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
      lines.add(event.getFormattedMessage)

  private def captureRelayLog[A](body: ConcurrentLinkedQueue[String] => IO[A]): IO[A] =
    IO {
      LoggerFactory.getLogger("nebflow.neblink.relay") match
        case lb: ch.qos.logback.classic.Logger =>
          val appender = new RelayLogAppender
          appender.setContext(lb.getLoggerContext)
          appender.start()
          lb.addAppender(appender)
          (lb, appender)
        case other => fail(s"expected a logback logger for the relay channel, got $other")
    }.flatMap { (lb, appender) =>
      body(appender.lines).guarantee(IO(lb.detachAppender(appender)))
    }

  private def relayLines(raw: ConcurrentLinkedQueue[String]): List[String] =
    val out = scala.collection.mutable.ListBuffer.empty[String]
    raw.forEach(l => out += l)
    out.toList

  private def countWith(lines: List[String], needle: String): Int =
    lines.count(_.contains(needle))

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

  private def mkClient(fix: RelayAuthFixtureServer): NeblinkClient =
    new NeblinkClient(
      NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
      0,
      identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos")))
    )

  /** 与 `NeblinkRelayTunnelKickParkSpec.withStack` 同款装配（真登录 + 真隧道 + 真 socket）。 */
  private def withTunnel[A](fix: RelayAuthFixtureServer)(body: NeblinkRelayTunnel => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = mkClient(fix)
        _ = ms.setRelayClient(Some(client))
        _ <- client.login(Device, "qa-host", "macos", Nil)
        _ <- ms.updateConfig(
          _.copy(
            enabled = true,
            neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
          )
        )
        tunnel = new NeblinkRelayTunnel(ms, () => IO(client.currentSessionToken))(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        fiber <- tunnel.connect().start
        out <- body(tunnel).guarantee(fiber.cancel *> tunnel.stop())
      yield out
    }

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap(f => body(f).guarantee(IO.blocking(f.close())))

  private def up(fix: RelayAuthFixtureServer): IO[Boolean] =
    waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))

  /**
   * 未知类型的大载荷帧：`type` 落在 W10 分支（不需要 wire 契约），`pad` 撑到 >4 KB
   * ⇒ 分片边界可以落在 JSON 内部任意位置。派发面读数 = W10 行的 `len=` 字段。
   */
  private def probeFrame(marker: String, padChars: Int): String =
    s"""{"type":"$marker","pad":"${"x" * padChars}"}"""

  /** 把载荷切成 3 片，**每片单独都不是合法 JSON**（边界落在字符串字面量 / 字段中间）。 */
  private def threeParts(payload: String, midChars: Int): List[String] =
    val cut1 = math.min(20, payload.length / 3)
    val cut2 = math.min(payload.length - 1, cut1 + midChars)
    List(payload.substring(0, cut1), payload.substring(cut1, cut2), payload.substring(cut2))

  // ── ① 分片端到端重组 100% ────────────────────────────────────────────

  test("frag: a text message split into 3 frames is reassembled and dispatched ONCE as the full payload") {
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      captureRelayLog { raw =>
        withTunnel(fix) { _ =>
          val payload = probeFrame("qa_frag_probe", 4096)
          val parts = threeParts(payload, 4080)
          for
            isUp <- up(fix)
            _ <- IO(assert(isUp, s"baseline: the relay tunnel must be up (${fix.relayAttempts})"))
            // 用例自证「分片形态」：整条载荷可解析，而**每一片单独都不可解析**
            // （⇒ 修前每一片都会落到 W11 `undecodable_frame`，后继片被整帧丢弃）。
            _ <- IO(assert(io.circe.parser.parse(payload).isRight, "the full payload must be valid JSON"))
            _ <- IO(
              parts.zipWithIndex.foreach((p, i) =>
                assert(io.circe.parser.parse(p).isLeft, s"part #$i must NOT be a parseable frame: $p")
              )
            )
            sent <- IO(fix.sendFragmentedTextToRelay(parts))
            _ <- IO(assert(sent >= 1, "the fixture must have an open relay socket to push into"))
            _ <- waitUntil(5.seconds)(IO(countWith(relayLines(raw), "reason=unknown_frame_type") >= 1))
            ls = relayLines(raw)
            dispatched = ls.filter(_.contains("reason=unknown_frame_type"))
            undecodable = countWith(ls, "reason=undecodable_frame")
            _ <- IO(
              println(
                s"[reading][frag/e2e] parts=${parts.map(_.length).mkString(",")} full=${payload.length} " +
                  s"dispatched=$dispatched undecodable=$undecodable"
              )
            )
            _ <- IO(
              assertEquals(
                dispatched.size,
                1,
                s"a 3-frame message must reach the dispatch path EXACTLY once (no frame loss, no per-fragment dispatch); log=${ls.mkString(" | ")}"
              )
            )
            _ <- IO(
              assert(
                dispatched.head.contains("type=qa_frag_probe") && dispatched.head.contains(s"len=${payload.length}"),
                s"the dispatched payload must be the COMPLETE message (len=${payload.length}); got ${dispatched.head}"
              )
            )
            _ <- IO(
              assertEquals(
                undecodable,
                0,
                s"zero fragment may be treated as a whole frame; log=${ls.mkString(" | ")}"
              )
            )
          yield ()
          end for
        }
      }
    }
  }

  // ── ② 与一次性整帧投递逐字节等价 ─────────────────────────────────────

  test("equiv: one-frame delivery and 3-frame delivery of the same payload reach the dispatch path identically") {
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      captureRelayLog { raw =>
        withTunnel(fix) { _ =>
          val payload = probeFrame("qa_frag_equiv", 2048)
          val parts = threeParts(payload, 2032)
          for
            isUp <- up(fix)
            _ <- IO(assert(isUp, s"baseline: the relay tunnel must be up (${fix.relayAttempts})"))
            single <- IO(fix.sendTextToRelayBig(payload))
            _ <- IO(assert(single >= 1, "the fixture must have an open relay socket for the single-frame arm"))
            _ <- waitUntil(5.seconds)(IO(countWith(relayLines(raw), "reason=unknown_frame_type") >= 1))
            fragSent <- IO(fix.sendFragmentedTextToRelay(parts))
            _ <- IO(assert(fragSent >= 1, "the fixture must have an open relay socket for the fragmented arm"))
            _ <- waitUntil(5.seconds)(IO(countWith(relayLines(raw), "reason=unknown_frame_type") >= 2))
            ls = relayLines(raw)
            dispatched = ls.filter(_.contains("reason=unknown_frame_type"))
            _ <- IO(
              println(
                s"[reading][equiv] single=1 frag=${parts.map(_.length).mkString(",")} full=${payload.length} " +
                  s"dispatched=${dispatched.size} lens=${dispatched.map(_.split("len=").last).mkString(",")}"
              )
            )
            _ <- IO(
              assertEquals(
                dispatched.size,
                2,
                s"both deliveries must dispatch exactly once each; log=${ls.mkString(" | ")}"
              )
            )
            _ <- IO(
              assertEquals(
                dispatched.map(_.split("len=").last.trim),
                List(payload.length.toString, payload.length.toString),
                s"the fragmented payload must be byte-equivalent to the single-frame payload (same length); log=${dispatched.mkString(" | ")}"
              )
            )
            _ <- IO(
              assertEquals(countWith(ls, "reason=undecodable_frame"), 0, "no fragment may be parsed as a whole frame")
            )
          yield ()
          end for
        }
      }
    }
  }

  // ── ③ 既有分支（ping ⇒ pong）不因分片而断 ────────────────────────────

  test("frag: an existing branch (ping => pong) still fires when the ping arrives fragmented") {
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      fix.recordClientFrames = true // 必须在升级前置位：排空循环形态在 upgrade 时定型
      withTunnel(fix) { _ =>
        def pongs: Int = fix.clientFrames.count(_ == """{"type":"pong"}""")
        for
          isUp <- up(fix)
          _ <- IO(assert(isUp, s"baseline: the relay tunnel must be up (${fix.relayAttempts})"))
          // 对照臂（非空转证据）：整帧 ping 必须出 pong
          _ <- IO(fix.sendTextToRelay("""{"type":"ping"}"""))
          control <- waitUntil(5.seconds)(IO(pongs >= 1))
          _ <- IO(assert(control, s"control arm: a single-frame ping must produce a pong; frames=${fix.clientFrames}"))
          // 被测臂：分片 ping（边界落在 JSON 内部）
          sent <- IO(fix.sendFragmentedTextToRelay(List("""{"ty""", """pe":"pi""", """ng"}""")))
          _ <- IO(assert(sent >= 1, "the fixture must have an open relay socket to push into"))
          fragPong <- waitUntil(5.seconds)(IO(pongs >= 2))
          _ <- IO(
            println(
              s"[reading][frag/ping] control_pongs=1 fragmented_pongs=${pongs - 1} frames=${fix.clientFrames}"
            )
          )
          _ <- IO(
            assert(
              fragPong,
              s"a fragmented ping must be reassembled and answered with a pong; frames=${fix.clientFrames}"
            )
          )
        yield ()
        end for
      }
    }
  }
  // ── ④..⑩ 重组器语义（纯函数面 · 有界性 / 生命周期 / 幂等键）────────────
  //
  // 与 ①..③ 并列：端到端腿证「真 socket 上的分片能到派发路径」，本段证**判废面**
  // （端到端伸不到：8 MiB 上限与 30 s 期限都是窗口内不可制造的量）。

  private def reassembler(
    maxChars: Int = RelayWsListener.MaxAssembledFrameChars,
    deadlineMs: Long = RelayWsListener.AssemblyDeadlineMs,
    clock: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(1_000L)
  ): RelayWsListener.FrameReassembler =
    new RelayWsListener.FrameReassembler(maxChars, deadlineMs, () => clock.get())

  test("unit①: fragments reassemble to a payload byte-identical to the one-frame delivery (JSON boundary crossed)") {
    val payload = probeFrame("qa_unit_equiv", 4096)
    val parts = threeParts(payload, 4000)
    val r = reassembler()
    val steps = parts.zipWithIndex.map((p, i) => r.accept(p, i == parts.length - 1))
    println(
      s"[reading][unit/equiv] parts=${parts.map(_.length).mkString(",")} full=${payload.length} steps=${steps.mkString(",")}"
    )
    assertEquals(steps.take(2), List(RelayWsListener.FrameStep.Partial, RelayWsListener.FrameStep.Partial))
    // 逐字符等价（不是「长度相同」而是同一个 String 值）
    assertEquals(steps.last, RelayWsListener.FrameStep.Complete(payload))
    assertEquals(r.pendingChars, 0, "completion must clear the buffer")
    // 同一载荷一次性整帧投递 ⇒ 同一条 Complete 值（直通分支）
    assertEquals(reassembler().accept(payload, true), RelayWsListener.FrameStep.Complete(payload))
  }

  test("unit②: the one-frame path is a verbatim passthrough (zero behaviour drift, incl. non-String CharSequence)") {
    val r = reassembler()
    // 生产上 JDK 传的是 CharBuffer 视图 ⇒ 用同类 CharSequence 钉「逐字透传」
    val buffer: CharSequence = java.nio.CharBuffer.wrap("""{"type":"qa_passthrough"}""")
    assertEquals(r.accept(buffer, true), RelayWsListener.FrameStep.Complete("""{"type":"qa_passthrough"}"""))
    assertEquals(r.accept("", true), RelayWsListener.FrameStep.Complete(""))
    assertEquals(r.pendingChars, 0)
    assertEquals(r.droppedMessages, 0, "the one-frame path must not drop anything")
  }

  test("unit③: no fragment is ever dispatched — only the invocation with last=true completes") {
    val payload = probeFrame("qa_unit_prefix", 4096)
    val parts = payload.grouped(997).toList
    assert(parts.length >= 3, s"the payload must fragment into ≥3 parts, got ${parts.length}")
    val r = reassembler()
    parts.dropRight(1).foreach(p => assertEquals(r.accept(p, false), RelayWsListener.FrameStep.Partial))
    assertEquals(r.pendingChars, payload.length - parts.last.length)
    assertEquals(r.accept(parts.last, true), RelayWsListener.FrameStep.Complete(payload))
    assertEquals(r.droppedMessages, 0)
  }

  test(
    "unit④: the buffer is bounded — an over-cap message is dropped, never dispatched, and the next message is clean"
  ) {
    val r = reassembler(maxChars = 64)
    val a = "a" * 32
    val b = "b" * 32
    val c = "c" * 32
    assertEquals(r.accept(a, false), RelayWsListener.FrameStep.Partial)
    assertEquals(
      r.accept(b, false),
      RelayWsListener.FrameStep.Partial,
      "64 chars is exactly at the cap (cap is a strict bound)"
    )
    assertEquals(r.accept(c, false), RelayWsListener.FrameStep.Dropped("frag_oversize", 96))
    assertEquals(r.pendingChars, 0, "the buffer must be released at the drop decision (bounded)")
    assert(r.isDiscarding, "the rest of the poisoned message must be discarded, never fed in as a new message")
    // 残尾绝不作为新消息上送（否则残尾若恰好可解析 = 派发一条被截断的语义帧）
    assertEquals(r.accept("d" * 1000, false), RelayWsListener.FrameStep.Discarding)
    assertEquals(r.accept("""{"type":"qa_smuggled"}""", false), RelayWsListener.FrameStep.Discarding)
    assertEquals(r.accept("e", true), RelayWsListener.FrameStep.Discarding)
    assertEquals(r.isDiscarding, false, "the message end clears the discard state")
    assertEquals(r.droppedMessages, 1)
    println(
      s"[reading][unit/oversize] dropped=${r.droppedMessages} pending=${r.pendingChars} discarding=${r.isDiscarding}"
    )
    // 下一条消息（含整帧）不受影响
    assertEquals(r.accept("""{"type":"ping"}""", true), RelayWsListener.FrameStep.Complete("""{"type":"ping"}"""))
  }

  test("unit⑤: a half message past the deadline is dropped lazily (no timer thread) and never dispatched truncated") {
    val clock = new java.util.concurrent.atomic.AtomicLong(1_000L)
    val r = reassembler(deadlineMs = 30_000L, clock = clock)
    val p1 = """{"type":"qa_st"""
    val p2 = """ale","pad":"x"""
    assertEquals(r.accept(p1, false), RelayWsListener.FrameStep.Partial)
    clock.addAndGet(29_999L) // 期限内
    assertEquals(r.accept(p2, false), RelayWsListener.FrameStep.Partial)
    // 惰性语义（显式申报的代价）：无定时线程 ⇒ 缓冲在「下一次调用」之前一直保留
    clock.addAndGet(30_000L)
    assertEquals(r.pendingChars, p1.length + p2.length, "the deadline is only evaluated on the next invocation (lazy)")
    assertEquals(r.accept("""x"}""", false), RelayWsListener.FrameStep.Dropped("frag_stale", p1.length + p2.length))
    assertEquals(r.pendingChars, 0)
    assert(r.isDiscarding)
    assertEquals(r.accept("tail", true), RelayWsListener.FrameStep.Discarding)
    assertEquals(r.isDiscarding, false)
    assertEquals(r.droppedMessages, 1)
    println(s"[reading][unit/stale] deadline=${30_000L} dropped=${r.droppedMessages} pending=${r.pendingChars}")
    // 同一载荷一次性整帧投递（不受期限影响 ⇒ 单帧路径零行为漂移）
    assertEquals(r.accept(p1 + p2 + """x"}""", true), RelayWsListener.FrameStep.Complete(p1 + p2 + """x"}"""))
  }

  test(
    "unit⑥: completion clears the buffer, consecutive messages never weld, and connection reset discards a half message"
  ) {
    val r = reassembler()
    val m1 = """{"type":"qa_weld","n":1}"""
    val m2 = """{"type":"qa_weld","n":2}"""
    assertEquals(r.accept(m1.take(12), false), RelayWsListener.FrameStep.Partial)
    assertEquals(r.accept(m1.drop(12), true), RelayWsListener.FrameStep.Complete(m1), "must not be welded to anything")
    assertEquals(r.pendingChars, 0)
    assertEquals(
      r.accept(m2, true),
      RelayWsListener.FrameStep.Complete(m2),
      "a one-frame message right after a fragmented one is verbatim"
    )
    // 半成品 + 连接关闭（onClose/onError 的收口）⇒ 清空 + 计数；新实例（新连接）不受影响
    assertEquals(r.accept(m1.take(5), false), RelayWsListener.FrameStep.Partial)
    r.reset()
    assertEquals(r.pendingChars, 0)
    assertEquals(r.isDiscarding, false)
    assertEquals(r.droppedMessages, 1, "a half message at connection end is a dropped message (counted)")
    assertEquals(r.accept(m2, true), RelayWsListener.FrameStep.Complete(m2))
  }

  test("unit⑦: the declared bounds are the shipped constants (boundedness is an explicit, readable choice)") {
    assertEquals(RelayWsListener.MaxAssembledFrameChars, 8 * 1024 * 1024)
    assertEquals(RelayWsListener.AssemblyDeadlineMs, 30_000L)
    val r = reassembler()
    assertEquals(r.maxChars, RelayWsListener.MaxAssembledFrameChars)
    assertEquals(r.deadlineMs, RelayWsListener.AssemblyDeadlineMs)
  }

  test("unit⑧: a reassembled device-mail frame is byte-identical ⇒ the existing eventId dedup keys are unchanged") {
    val eventId = "message-1000000000000042"
    val envelope: Json = Json.obj(
      "type" -> "friend_event".asJson,
      "eventId" -> eventId.asJson,
      "event" -> Json.obj(
        "payload" -> Json.obj(
          "type" -> "agent_mail".asJson,
          "from_device" -> "KAI-MBP".asJson,
          "from_device_id" -> "dev-a".asJson,
          "to_nebula" -> true.asJson,
          "text" -> ("frag-equivalence " * 400).asJson
        ),
        "type" -> "agent_mail".asJson
      )
    )
    val wire = envelope.noSpaces
    val parts = wire.grouped((wire.length / 3) + 1).toList
    assert(parts.length == 3, s"the envelope must fragment into exactly 3 parts, got ${parts.length}")
    assert(parts.forall(p => io.circe.parser.parse(p).isLeft), "every part must be an unparseable fragment")
    val r = reassembler()
    val complete = parts.zipWithIndex.map((p, i) => r.accept(p, i == parts.length - 1)).collect {
      case RelayWsListener.FrameStep.Complete(p) => p
    }
    assertEquals(complete, List(wire), "fragmented delivery must yield the byte-identical message")
    // 生产侧**既有** eventId 取值器（`DeviceMail.frameEventId`，DeviceMail.scala:148）——
    // 去重闸（`FriendService.guard.dedupe`，FriendService.scala:302；`DeviceMailInbox.injectWithDedup`，
    // DeviceMailInbox.scala:224）消费的键对两种投递形态必须同值。
    val parsedWire = io.circe.parser.parse(wire).toOption
    val parsedReasm = io.circe.parser.parse(complete.head).toOption
    assertEquals(parsedWire.flatMap(DeviceMail.frameEventId), Some(eventId))
    assertEquals(parsedReasm.flatMap(DeviceMail.frameEventId), parsedWire.flatMap(DeviceMail.frameEventId))
    assertEquals(parsedReasm.map(DeviceMail.isAgentMailEnvelope), Some(true))
    assertEquals(
      parsedReasm.flatMap(j => DeviceMail.parseEnvelope(j).toOption).map(_._1.text),
      parsedWire.flatMap(j => DeviceMail.parseEnvelope(j).toOption).map(_._1.text)
    )
    // at-least-once 重放形态（同一完整消息再送一次）：重组层不丢不改，去重仍归**既有**闸
    assertEquals(r.accept(wire, true), RelayWsListener.FrameStep.Complete(wire))
    assertEquals(
      r.droppedMessages,
      0,
      "the reassembly layer must neither drop nor duplicate: it is 1:1 with WS messages"
    )
    println(
      s"[reading][unit/eventid] eventId=$eventId wire=${wire.length} parts=${parts.map(_.length).mkString(",")} reassembled_equals_wire=true"
    )
  }
end NeblinkRelayTunnelFragReasmSpec
