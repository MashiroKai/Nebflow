package nebflow.core

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.NebflowLogger

import scala.jdk.CollectionConverters.*

/**
 * 死日志复活钉子（2026-08-21 专项，qwen 批 qa 打回实录的家族病根）：
 *
 * NebulaLogger.info/warn 返回 IO[Unit]——包一层 IO(...) 得 IO[IO[Unit]]，
 * 外层运行后内层被丢弃，日志永不执行（经 handleErrorWith 的 B=Any 型擦除
 * 编译通过）。全部实例位于错误恢复路径 = 出问题时静默。
 *
 * 本 spec 以 UsageTracker.loadPattern 为代表钉死「修复后日志确实会在该
 * 错误路径执行」：dataRoot 下 usage-pattern.json 被替换为目录 → os.read
 * 抛异常 → handleErrorWith 回退 → WARN 必须真实发出（ListAppender 源级
 * 断言，纯返回值断言测不到 built-but-discarded）。
 *
 * 家族防回归由 scripts/check-dead-logging.sh（CI dead-logging-gate job）
 * 静态把门；本 spec 提供运行时端的语义证明。
 */
class DeadLoggingResurrectionSpec extends FunSuite:

  test("loadPattern failure path emits its WARN (was dead IO[IO[Unit]])") {
    val originalRoot = PathUtil.dataRoot
    val tmp = os.pwd / "target" / "dead-log-spec" / System.nanoTime().toString
    PathUtil.setDataRoot(tmp)
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.usage").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    try
      os.makeDir.all(tmp)
      // A directory where the pattern FILE is expected: os.exists passes,
      // os.read throws → the handleErrorWith recovery path runs.
      os.makeDir.all(tmp / "usage-pattern.json")
      appender.start()
      lbLogger.addAppender(appender)

      val result = UsageTracker.loadPattern().unsafeRunSync()

      assertEquals(result, UsagePattern.empty, "recovery must still return the empty pattern")
      val warns = appender.list.asScala.toList
        .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
        .map(_.getFormattedMessage)
      assert(
        warns.exists(_.contains("loadPattern failed")),
        s"expected the recovery-path WARN to actually fire, got ${warns}"
      )
    finally
      lbLogger.detachAppender(appender)
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

end DeadLoggingResurrectionSpec
