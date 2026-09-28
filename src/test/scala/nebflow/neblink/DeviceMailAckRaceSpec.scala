package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.core.PathUtil
import org.slf4j.LoggerFactory

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 设备邮件 ack 登记/匹配面的**时序竞态**（ackfix 批，2026-09-17；卡 #678）。
 *
 * 缺陷（上游 `acktrace-forensic` 已证实的时序前提）：登记点落在 `relayAgentMail` 的
 * **HTTP 响应之后**，而对端 ack 常在响应之前抵达隧道 ⇒ 「**ack 先到、登记后到**」。
 * 改前该时序下 ack 未命中即被丢弃（无缓冲、无回填），而已挂的超时腿必然在 15 s 后打出
 * 一对假警告：`ack UNMATCHED …` + `ack TIMEOUT … (delivery receipt not seen)`。
 *
 * 既有用例（`DeviceMailSpec` ⑥/⑦）把「**先 await 后 handle**」固化为前提 ⇒ 竞态零覆盖，
 * 这正是缺陷未被测试拦住的原因。本 spec 补齐三个方向的读数：
 *
 *   - **假告警的缺席**（顺序构造：ack 先到、登记后到）——断言窗口覆盖整个 15 s 等待窗，
 *     故「无假 TIMEOUT」是实测而非推断；
 *   - **假告警的缺席**（残余交错窗：登记的先查恰好早于缓冲写入）——超时腿到点再查缓冲，
 *     必须降为 INFO，且该降级确实发生过（`ack-race-late` 在场）；
 *   - **正控：真超时仍判出**——对端确实不回时 `delivery receipt not seen` 必须仍在
 *     （🔴 禁把真超时也吞掉）。
 *
 * 判据面 = ① logback 捕获的 WARN/INFO 原文（与 `AnthropicEmptyEventWarnSpec` 同款手法）
 * + ② 审计盘面（`PathUtil.dataRoot/logs/relay-exec-audit.jsonl` 的 `action` 列，落盘可复核）
 * + ③ 登记/缓冲计数读数。三者都不依赖肉眼。
 */
class DeviceMailAckRaceSpec extends FunSuite:

  private val LogName = "nebflow.neblink.devicemail.ack"

  /** munit 默认单测超时 30 s。本 spec 的判据**必须实测整个等待窗/TTL**（最长 = 新增⑤ 的
    * `EarlyAckTtl`(30 s) + 2 s = 32 s）⇒ 抬到 75 s。仍远低于「挂死」量级，故守卫不放空。 */
  override def munitTimeout: Duration = 75.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-device-mail-race"
  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: BeforeEach): Unit =
    DeviceMailAck.resetForTest().unsafeRunSync()
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

  // ── 读数设施 ────────────────────────────────────────────────────────

  private def ackFrame(innerId: String): Json =
    parse(s"""{"type":"ack","eventId":"message-$innerId"}""").toOption.getOrElse(fail("夹具 JSON 非法"))

  /** 捕获 `nebflow.neblink.devicemail.ack` 名下的日志原文（按级别分列）。 */
  private def withAckLog[A](f: => A): (A, List[String], List[String]) =
    val lbLogger = LoggerFactory.getLogger(LogName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    lbLogger.addAppender(appender)
    try
      val a = f
      val events = appender.list.asScala.toList
      (
        a,
        events.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage),
        events.filter(_.getLevel == Level.INFO).map(_.getFormattedMessage)
      )
    finally lbLogger.detachAppender(appender)

  /** 审计盘面的 `action` 列（落盘读数，逐行现读）。 */
  private def auditActions(): List[String] =
    val f = (PathUtil.dataRoot / "logs" / "relay-exec-audit.jsonl").toNIO
    if !java.nio.file.Files.exists(f) then Nil
    else
      java.nio.file.Files
        .readAllLines(f)
        .asScala
        .toList
        .flatMap(l => parse(l).toOption.flatMap(_.hcursor.get[String]("action").toOption))

  /** 有界等待 + 5 s 心跳（长跑纪律②：不留 30 s 无输出窗口）。 */
  private def waitFor(span: FiniteDuration, rt: IORuntime = IORuntime.global): Unit =
    var left = span
    while left > Duration.Zero do
      val d = if left < 5.seconds then left else 5.seconds
      IO.sleep(d).unsafeRunSync()(rt)
      left = left - d
      println(s"[ackfix-spec] 等待中… 剩余 ${left.toSeconds}s")

  // ── 测试（按声明序执行；每条前置 resetForTest ⇒ 上个用例挂的超时腿到点即 no-op）──

  test("新增①时序竞态（顺序构造，= X1）：ack 先到、登记后到 ⇒ 无假 UNMATCHED、无假 TIMEOUT、不挂超时腿"):
    val (_, warns, infos) = withAckLog {
      // ① ack 先到（登记尚未建立）：改前此处 WARN UNMATCHED 且该 ack 被丢弃
      DeviceMailAck.handle(ackFrame("race-seq-1")).unsafeRunSync()
      assertEquals(
        DeviceMailAck.earlyAckCount.unsafeRunSync(),
        1,
        "未命中的 ack 必须进「抢先 ack」缓冲（有界），禁丢弃"
      )
      // ② 登记后到：命中缓冲 ⇒ 立即出队 + **不挂超时腿**
      assertEquals(DeviceMailAck.await("dev-b", "race-seq-1").unsafeRunSync(), "message-race-seq-1")
      assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "命中缓冲 ⇒ 立即出队（无待回执残留）")
      assertEquals(DeviceMailAck.earlyAckCount.unsafeRunSync(), 0, "命中即消费（禁二次匹配）")
      // ③ 静置**整个等待窗**：若仍挂着超时腿，这里必出假 TIMEOUT（改前即如此）
      waitFor(DeviceMailAck.AckTimeout + 1.second)
      assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0)
    }
    assert(!warns.exists(_.contains("UNMATCHED")), s"🔴 禁假 UNMATCHED，实得：$warns")
    assert(
      !warns.exists(_.contains("delivery receipt not seen")),
      s"🔴 禁假 TIMEOUT（回执确已抵达并被消费），实得：$warns"
    )
    assertEquals(warns, Nil, "该时序下不应有任何 WARN")
    assert(
      infos.exists(_.contains("receipt arrived before this send's registration")),
      s"应有「缓冲命中」INFO（命中即关联），实得：$infos"
    )
    assertEquals(
      auditActions(),
      List("DeviceMail.ack-buffered", "DeviceMail.ack-race-resolved"),
      "审计盘面：入缓冲 + 竞态已解 —— 禁出现 ack-unmatched / ack-timeout"
    )

  test("新增②残余交错窗（覆盖 R1 ③）：登记的先查早于缓冲写入 ⇒ 超时腿到点再查缓冲并降为 INFO"):
    // 单线程 compute pool + miss 分支的显式调度点（`IO.cede`）⇒ 「handle 的快照先于登记、
    // 缓冲写入后于登记」这一交错可被**确定性**复现（多线程下是概率事件，不能作判据）。
    val pool = Executors.newFixedThreadPool(1)
    val rt = IORuntime
      .builder()
      .setCompute(ExecutionContext.fromExecutorService(pool), () => pool.shutdown())
      .build()
    val (_, warns, infos) =
      try
        withAckLog {
          IO.both(
            DeviceMailAck.handle(ackFrame("race-win-1")),
            DeviceMailAck.await("dev-b", "race-win-1")
          ).unsafeRunSync()(rt)
          waitFor(DeviceMailAck.AckTimeout + 1.second, rt)
        }
      finally
        pool.shutdownNow()
        rt.shutdown()
    assert(
      infos.exists(_.contains("ack matched late")),
      s"③ 必须命中缓冲并降为 INFO（证明该交错确被触发、且降级支确在场），实得：$infos"
    )
    assert(!warns.exists(_.contains("delivery receipt not seen")), s"🔴 禁假 TIMEOUT，实得：$warns")
    assert(auditActions().contains("DeviceMail.ack-race-late"), s"③ 的审计行必在场：${auditActions()}")
    assert(!auditActions().contains("DeviceMail.ack-timeout"), s"🔴 禁 ack-timeout：${auditActions()}")
    assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "到点出队（不残留）")

  test("新增③正控（= X2③）：对端确实不回 ⇒ 真超时仍判出（`delivery receipt not seen` 必须仍在）"):
    val (_, warns, infos) = withAckLog {
      assertEquals(DeviceMailAck.await("dev-offline", "true-timeout").unsafeRunSync(), "message-true-timeout")
      waitFor(DeviceMailAck.AckTimeout + 1.second)
      assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "到点出队")
    }
    assert(
      warns.exists(_.contains("delivery receipt not seen")),
      s"🔴 真超时必须仍判出（禁把真超时一并吞掉），实得：$warns"
    )
    assert(!infos.exists(_.contains("ack matched late")), s"无抢占 ⇒ 不得走降级支，实得：$infos")
    assertEquals(auditActions(), List("DeviceMail.ack-timeout"), "真超时的审计行逐字不变")
    assertEquals(DeviceMailAck.earlyAckCount.unsafeRunSync(), 0, "无 ack ⇒ 缓冲空")

  test("新增④有界性（= X5）：容量上界 + 溢出丢最旧（不崩 / 不误判 / 不无界增长）"):
    val cap = DeviceMailAck.EarlyAckCapacity
    val n = cap + 20
    val (_, warns, _) = withAckLog {
      (0 until n).foreach(i => DeviceMailAck.handle(ackFrame(s"bulk-$i")).unsafeRunSync()) // ① 不崩
      assertEquals(
        DeviceMailAck.earlyAckCount.unsafeRunSync(),
        cap,
        s"③ 有界：恒 ≤ 容量上界 $cap（灌入 $n 条后仍为 $cap ⇒ 无无界增长）"
      )
      // ② 不误判：仍在缓冲内的 id 命中并消费（不虚报不误配）
      assertEquals(DeviceMailAck.await("dev-b", s"bulk-${n - 1}").unsafeRunSync(), s"message-bulk-${n - 1}")
      assertEquals(DeviceMailAck.earlyAckCount.unsafeRunSync(), cap - 1, "命中 ⇒ 恰消费一条")
      assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "命中 ⇒ 出队，不误判为待回执")
      // ② 不误判：被溢出丢弃的最旧 id **不得**复活为命中 ⇒ 照常入待回执 + 挂超时腿
      assertEquals(DeviceMailAck.await("dev-b", "bulk-0").unsafeRunSync(), "message-bulk-0")
      assertEquals(DeviceMailAck.earlyAckCount.unsafeRunSync(), cap - 1, "溢出丢弃的 id 不得复活")
      assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 1, "未命中 ⇒ 保留待回执（真超时路径不受影响）")
    }
    assert(
      warns.exists(_.contains("early-ack buffer overflow")),
      s"溢出必须可见（禁静默），实得：$warns"
    )
    assert(auditActions().contains("DeviceMail.ack-buffer-overflow"), s"溢出审计行必在场：${auditActions()}")

  test("新增⑤措辞准确性：到期未认领的 ack 文案自称「进程内」而不再自称「本会话」"):
    // 真 TTL 到期（2× 等待窗）后由下一次触碰缓冲的写入触发逐条宣告。
    val (_, warns, _) = withAckLog {
      DeviceMailAck.handle(ackFrame("orphan-1")).unsafeRunSync()
      waitFor(DeviceMailAck.EarlyAckTtl + 2.seconds)
      DeviceMailAck.handle(ackFrame("touch-1")).unsafeRunSync() // 触碰 ⇒ 裁掉过期条目并宣告
    }
    val unmatched = warns.filter(_.contains("UNMATCHED"))
    assert(unmatched.nonEmpty, s"到期未认领的 ack 必须可见（禁静默），实得：$warns")
    assert(
      unmatched.exists(_.contains("in this process")),
      s"措辞必须与事实一致（登记表/缓冲是进程内全局单例），实得：$unmatched"
    )
    assert(
      !unmatched.exists(_.contains("in this session")),
      s"🔴 禁再自称 'in this session'（无 per-session 表），实得：$unmatched"
    )
    assert(
      unmatched.forall(_.contains("orphan-1")),
      s"宣告的必须是那条真·未认领的 ack（不是登记成功的 touch-1），实得：$unmatched"
    )
    assert(auditActions().contains("DeviceMail.ack-unmatched"), s"未认领审计行必在场：${auditActions()}")

  test("回归：正常命中路径行为不变（登记后 ack ⇒ ack-received + elapsed_ms，缓冲零参与）"):
    val (_, warns, infos) = withAckLog {
      DeviceMailAck.await("dev-b", "normal-1").unsafeRunSync()
      DeviceMailAck.handle(ackFrame("normal-1")).unsafeRunSync()
      assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "命中 ⇒ 出队")
      assertEquals(DeviceMailAck.earlyAckCount.unsafeRunSync(), 0, "正常命中不碰缓冲")
    }
    assert(
      infos.exists(l => l.contains("ack received") && l.contains("elapsed_ms=")),
      s"正常命中的 INFO 逐字不变（elapsed_ms 读数仍在），实得：$infos"
    )
    assertEquals(warns, Nil)
    assertEquals(auditActions(), List("DeviceMail.ack-received"), "正常命中审计行逐字不变")

end DeviceMailAckRaceSpec
