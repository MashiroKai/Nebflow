package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.circe.parser.parse
import munit.FunSuite
import nebflow.core.PathUtil
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * **X1 判据探针（刻意只使用改前/改后共有的 API 面）**——为什么单独一件、为什么不能并进
 * `DeviceMailAckRaceSpec`：那个 spec 断言了新增的读数口（`earlyAckCount` / `EarlyAckCapacity`
 * / `EarlyAckTtl`），**改前源码里没有这些符号** ⇒ 把 `DeviceMailAck.scala` 还原成改前版本后
 * 整个 test 树编不过，RED 读数就无法机械复现。本件不用任何新符号，故可按下面两步复现
 * 「同判据双向」：
 *
 * {{{
 * BASE=39613957541b6d7bdbcb7206f4c1f79f41f6394c
 * # ① 改前（RED）：还原生产件 + 把用新符号的 spec 移出源码树（否则编译失败）
 * git -C <ws> show $BASE:src/main/scala/nebflow/neblink/DeviceMailAck.scala > <ws>/src/main/scala/nebflow/neblink/DeviceMailAck.scala
 * git -C <ws> show $BASE:src/test/scala/nebflow/neblink/DeviceMailSpec.scala    > <ws>/src/test/scala/nebflow/neblink/DeviceMailSpec.scala
 * mv <ws>/src/test/scala/nebflow/neblink/DeviceMailAckRaceSpec.scala /tmp/            # 临时移出
 * sbt -batch "testOnly nebflow.neblink.DeviceMailAckRaceProbeSpec"                     # 必 RED
 * # ② 改后（GREEN）：还原
 * git -C <ws> checkout HEAD -- src/main/scala/nebflow/neblink/DeviceMailAck.scala \
 *                               src/test/scala/nebflow/neblink/DeviceMailSpec.scala
 * mv /tmp/DeviceMailAckRaceSpec.scala <ws>/src/test/scala/nebflow/neblink/
 * sbt -batch "testOnly nebflow.neblink.DeviceMailAckRaceProbeSpec"                     # 必 GREEN
 * }}}
 *
 * 判据只有两条（都是**用户可见面的假陈述**，与实现细节解耦）：该时序下**不得**出现
 * `UNMATCHED`、**不得**出现 `delivery receipt not seen`；审计盘面**不得**出现
 * `DeviceMail.ack-unmatched` / `DeviceMail.ack-timeout`。
 */
class DeviceMailAckRaceProbeSpec extends FunSuite:

  private val LogName = "nebflow.neblink.devicemail.ack"

  /** munit 默认 30 s；本件判据须覆盖整个 15 s 等待窗（实测 ~16 s）⇒ 抬到 75 s。 */
  override def munitTimeout: Duration = 75.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-device-mail-race-probe"
  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: BeforeEach): Unit =
    DeviceMailAck.resetForTest().unsafeRunSync()
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

  private def withAckLog[A](f: => A): (A, List[String]) =
    val lbLogger = LoggerFactory.getLogger(LogName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    lbLogger.addAppender(appender)
    try
      val a = f
      (a, appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage))
    finally lbLogger.detachAppender(appender)

  private def auditActions(): List[String] =
    val f = (PathUtil.dataRoot / "logs" / "relay-exec-audit.jsonl").toNIO
    if !java.nio.file.Files.exists(f) then Nil
    else
      java.nio.file.Files
        .readAllLines(f)
        .asScala
        .toList
        .flatMap(l => parse(l).toOption.flatMap(_.hcursor.get[String]("action").toOption))

  test("X1 判据（同构造双向）：ack 先到、登记后到 ⇒ 禁假 UNMATCHED、禁假 TIMEOUT"):
    val (_, warns) = withAckLog {
      // ack 先到（登记尚未建立）
      DeviceMailAck
        .handle(parse("""{"type":"ack","eventId":"message-probe-1"}""").toOption.get)
        .unsafeRunSync()
      // 登记后到
      assertEquals(DeviceMailAck.await("dev-p", "probe-1").unsafeRunSync(), "message-probe-1")
      // 静置整个等待窗（改前：超时腿在此打出假 TIMEOUT）
      var left = DeviceMailAck.AckTimeout + 1.second
      while left > Duration.Zero do
        val d = if left < 5.seconds then left else 5.seconds
        IO.sleep(d).unsafeRunSync()
        left = left - d
        println(s"[ackfix-probe] 等待中… 剩余 ${left.toSeconds}s")
    }
    assert(!warns.exists(_.contains("UNMATCHED")), s"🔴 假 UNMATCHED（改前必现），实得：$warns")
    assert(
      !warns.exists(_.contains("delivery receipt not seen")),
      s"🔴 假 TIMEOUT（改前必现），实得：$warns"
    )
    val a = auditActions()
    assert(!a.contains("DeviceMail.ack-unmatched"), s"🔴 审计面不得有 ack-unmatched，实得：$a")
    assert(!a.contains("DeviceMail.ack-timeout"), s"🔴 审计面不得有 ack-timeout，实得：$a")

end DeviceMailAckRaceProbeSpec
