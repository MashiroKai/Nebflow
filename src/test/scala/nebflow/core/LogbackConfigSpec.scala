package nebflow.core

import munit.FunSuite
import org.slf4j.{Logger, LoggerFactory}
import ch.qos.logback.classic.Logger as LogbackLogger

/** #36: the test classpath must resolve to logback-test.xml (console only),
  * never logback.xml's FILE appender — otherwise every sbt test run writes
  * into the host's nebflow.log (${user.home}/.nebflow, regardless of --home).
  *
  * Regression guard: if someone drops the test config or re-adds a FILE
  * appender to it, this spec fails loud instead of silently polluting logs.
  */
class LogbackConfigSpec extends FunSuite:

  private def rootAppenderNames: List[String] =
    val root = LoggerFactory
      .getLogger(Logger.ROOT_LOGGER_NAME)
      .asInstanceOf[LogbackLogger]
    val it = root.iteratorForAppenders()
    val buf = scala.collection.mutable.ListBuffer.empty[String]
    while it.hasNext do buf += it.next().getName
    buf.toList

  test("test classpath has no FILE appender (host-log pollution guard, #36)") {
    val names = rootAppenderNames
    assert(!names.contains("FILE"), s"test classpath resolved to logback.xml (FILE appender present): $names")
  }

  test("test classpath still configures a console appender") {
    val names = rootAppenderNames
    assert(names.contains("CONSOLE"), s"expected CONSOLE appender on test classpath, got: $names")
  }
end LogbackConfigSpec
