package nebflow.shared

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger as JbLogger}
import ch.qos.logback.core.read.ListAppender
import munit.{BeforeEach, FunSuite}
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/**
 * TransportLog 降噪闸的单测（日志降噪批 C，2026-11-04）。
 *
 * 断言面 = **真实 logback 事件流**（不是「预计」）：把 ListAppender 挂到两条家族
 * logger 上，真跑降噪闸，按事件**级别 + 条数 + 行文**取数。四组关键断言：
 *   ① 缺省（aggregate）：N 次调用窗口内**零**逐条行，冲刷后**恰好一行**摘要且 `×N`；
 *   ② 回切（per-line）：逐条 INFO，行文与降噪前**逐字节相同**（可回切证明）；
 *   ③ 保留（debug）：逐条 DEBUG，INFO 面零行（痕迹不丢、让出 INFO）；
 *   ④ 摘要信息量：设备/通道/连接表/会话/状态分布/均值都在（降噪不等于失明）。
 */
class TransportLogSpec extends FunSuite:

  private val presenceLog = NebflowLogger.forName("nebflow.neblink.presence")
  private val syncLog = NebflowLogger.forName("nebflow.neblink.session-sync")

  override def beforeEach(context: BeforeEach): Unit =
    TransportLog.resetForTest()

  /** 在指定 logger 上抓 logback 事件（临时把级别放到 DEBUG，抓完复原）。 */
  private def capture(name: String)(body: => Unit): List[ILoggingEvent] =
    val jb = LoggerFactory.getLogger(name).asInstanceOf[JbLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    val prevLevel = jb.getLevel
    jb.addAppender(appender)
    jb.setLevel(Level.DEBUG)
    try
      body
      appender.list.asScala.toList
    finally
      jb.detachAppender(appender)
      jb.setLevel(prevLevel)
      appender.stop()

  private def messages(events: List[ILoggingEvent], level: Level): List[String] =
    events.filter(_.getLevel == level).map(_.getMessage)

  // ── ① 缺省 = 安静形态 ─────────────────────────────────────────────────

  test("aggregate（缺省）：1000 次调用 → 恰好一条 ×1000 摘要，逐条行零条"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.Aggregate))
    var pendingBeforeFlush = -1L
    val events = capture("nebflow.neblink.presence") {
      (1 to 1000).foreach(_ => TransportLog.presenceSendData(presenceLog, "6a64d249-iso", "agent-session", true))
      pendingBeforeFlush = TransportLog.pendingCounts("presence")
      TransportLog.flushNow()
    }
    assertEquals(pendingBeforeFlush, 1000L, "窗口内只许记账，不许输出")
    val info = messages(events, Level.INFO)
    assertEquals(info.size, 1, s"一次窗口只许一行摘要，实际 ${info.size} 行: ${info.take(3)}")
    val line = info.head
    assert(line.contains("presence sendData ×1000 in"), line)
    assert(line.contains("devices=1"), line)
    assert(line.contains("channels=1"), line)
    assert(line.contains("conn{true=1000,false=0}"), line)
    assert(line.contains("6a64d249-iso(conn=true)=1000"), line)
    assertEquals(messages(events, Level.WARN).size, 0)
    assertEquals(messages(events, Level.ERROR).size, 0)
    assertEquals(TransportLog.pendingCounts("presence"), 0L, "冲刷后计数归零")

  test("aggregate：session-sync 摘要带会话数/状态分布/目标均值/载荷均值"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.Aggregate))
    val events = capture("nebflow.neblink.session-sync") {
      (1 to 30).foreach(i => TransportLog.sessionSyncPush(syncLog, "delegate-alpha", "running", 1000L + i, 800, 2))
      (1 to 20).foreach(i => TransportLog.sessionSyncPush(syncLog, "subagent-beta", "completed", 1000L + i, 400, 0))
      TransportLog.flushNow()
    }
    val info = messages(events, Level.INFO)
    assertEquals(info.size, 1, s"$info")
    val line = info.head
    assert(line.contains("session-sync push ×50 in"), line)
    assert(line.contains("sessions=2"), line)
    assert(line.contains("running=30"), line)
    assert(line.contains("completed=20"), line)
    // 目标均值 = (30*2 + 20*0)/50 = 1.2；载荷均值 = (30*800 + 20*400)/50 = 640
    assert(line.contains("targetsAvg=1.2"), line)
    assert(line.contains("bytesAvg=640"), line)

  test("aggregate：空窗不产行（零流量 ⇒ 零日志）"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.Aggregate))
    val events = capture("nebflow.neblink.presence") {
      TransportLog.flushNow()
    }
    assertEquals(events.size, 0, s"空窗必须零行，实际: ${events.map(_.getMessage)}")

  // ── ② 回切 = 逐条 INFO，行文逐字节不变 ──────────────────────────────────

  test("per-line（回切开关）：逐条 INFO，行文与降噪前逐字节相同"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.PerLine))
    val events = capture("nebflow.neblink.presence") {
      TransportLog.presenceSendData(presenceLog, "40742cd4-kai", "agent-session", false)
      TransportLog.presenceSendData(presenceLog, "40742cd4-kai", "agent-session", false)
      TransportLog.presenceSendData(presenceLog, "40742cd4-kai", "agent-identity", true)
      // 回切模式下不许有聚合记账（否则两种形态会重复计）
      assertEquals(TransportLog.pendingCounts.values.sum, 0L)
    }
    val info = messages(events, Level.INFO)
    assertEquals(info.size, 3, s"回切必须逐条，实际: $info")
    // 降噪前生产日志的原行文（stand.log 逐字对齐）——只差 ANSI 前缀，FILE 编码器已剥色
    assertEquals(
      info.head,
      "\u001B[36mINFO \u001B[0m nebflow.neblink.presence - " +
        "presence sendData: device=40742cd4-kai channel=agent-session connectionsHasKey=false"
    )
    assertEquals(
      info(2),
      "\u001B[36mINFO \u001B[0m nebflow.neblink.presence - " +
        "presence sendData: device=40742cd4-kai channel=agent-identity connectionsHasKey=true"
    )

  test("per-line：session-sync 行文与降噪前逐字节相同"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.PerLine))
    val events = capture("nebflow.neblink.session-sync") {
      TransportLog.sessionSyncPush(syncLog, "delegate-nebflow-cb580112", "running", 1762228800123L, 812, 1)
    }
    assertEquals(
      messages(events, Level.INFO),
      List(
        "\u001B[36mINFO \u001B[0m nebflow.neblink.session-sync - " +
          "session-sync push: session=delegate-nebflow-cb580112 status=running " +
          "updatedAt=1762228800123 bytes=812 targets=1"
      )
    )

  // ── ③ debug 形态 ─────────────────────────────────────────────────────

  test("debug（保留形态）：逐条 DEBUG，INFO 面零行"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.Debug))
    val events = capture("nebflow.neblink.presence") {
      (1 to 5).foreach(_ => TransportLog.presenceSendData(presenceLog, "dev-x", "agent-session", true))
    }
    assertEquals(messages(events, Level.INFO).size, 0)
    val dbg = events.filter(_.getLevel == Level.DEBUG).map(_.getMessage)
    assertEquals(dbg.size, 5, s"$dbg")
    assert(
      dbg.forall(_.endsWith("presence sendData: device=dev-x channel=agent-session connectionsHasKey=true"))
    )

  // ── ④ 摘要的信息量 / 护栏 ────────────────────────────────────────────

  test("aggregate：多设备多通道混合连接态 —— 分布读数不丢"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.Aggregate))
    val events = capture("nebflow.neblink.presence") {
      (1 to 10).foreach(_ => TransportLog.presenceSendData(presenceLog, "dev-a", "agent-session", true))
      (1 to 5).foreach(_ => TransportLog.presenceSendData(presenceLog, "dev-b", "agent-session", false))
      (1 to 2).foreach(_ => TransportLog.presenceSendData(presenceLog, "dev-b", "agent-identity", false))
      TransportLog.flushNow()
    }
    val line = messages(events, Level.INFO).head
    assert(line.contains("×17 in"), line)
    assert(line.contains("devices=2"), line)
    assert(line.contains("channels=2"), line)
    assert(line.contains("conn{true=10,false=7}"), line)

  test("aggregate：基数帽生效（故障期键数爆炸不撑爆内存，超出并入 (+other)）"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.Aggregate))
    val events = capture("nebflow.neblink.presence") {
      (1 to (TransportLog.MaxDistinctKeys + 100)).foreach(i =>
        TransportLog.presenceSendData(presenceLog, s"dev-$i", "agent-session", true)
      )
      TransportLog.flushNow()
    }
    val line = messages(events, Level.INFO).head
    assert(line.contains(s"×${TransportLog.MaxDistinctKeys + 100} in"), line)
    assert(line.contains(TransportLog.OverflowKey), line)

  test("gate 只产 INFO 摘要，绝不把失败面降级/提升（WARN/ERROR 零行）"):
    TransportLog.setModeForTest(Some(TransportLog.Mode.Aggregate))
    val events = capture("nebflow.neblink.session-sync") {
      (1 to 50).foreach(_ => TransportLog.sessionSyncPush(syncLog, "s-1", "failed", 1L, 100, 0))
      TransportLog.flushNow()
    }
    assertEquals(messages(events, Level.WARN).size, 0)
    assertEquals(messages(events, Level.ERROR).size, 0)

  // ── 行文构造器（唯一出处）────────────────────────────────────────────

  test("行文构造器与降噪前生产日志逐字对齐"):
    assertEquals(
      TransportLog.presenceMessage("6a64d249-iso-a659864a", "agent-session", true),
      "presence sendData: device=6a64d249-iso-a659864a channel=agent-session connectionsHasKey=true"
    )
    assertEquals(
      TransportLog.sessionSyncMessage("delegate-nebflow-cb580112", "running", 1762228800123L, 812, 2),
      "session-sync push: session=delegate-nebflow-cb580112 status=running " +
        "updatedAt=1762228800123 bytes=812 targets=2"
    )

  test("模式解析：三形态 + 未知值回缺省 aggregate"):
    assertEquals(TransportLog.Mode.parse("aggregate"), Some(TransportLog.Mode.Aggregate))
    assertEquals(TransportLog.Mode.parse("per-line"), Some(TransportLog.Mode.PerLine))
    assertEquals(TransportLog.Mode.parse("PERLINE"), Some(TransportLog.Mode.PerLine))
    assertEquals(TransportLog.Mode.parse("debug"), Some(TransportLog.Mode.Debug))
    assertEquals(TransportLog.Mode.parse("bogus"), None)
    assertEquals(TransportLog.Mode.parse(null), None)
end TransportLogSpec
