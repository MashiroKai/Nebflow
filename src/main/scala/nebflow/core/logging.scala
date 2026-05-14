package nebflow.core

import cats.effect.IO
import org.slf4j.{Logger, LoggerFactory}

object NebflowLogger:
  def apply(cls: Class[?]): NebflowLogger = new NebflowLogger(LoggerFactory.getLogger(cls))
  def forName(name: String): NebflowLogger = new NebflowLogger(LoggerFactory.getLogger(name))

class NebflowLogger(private val logger: Logger):
  private val RESET = "\u001B[0m"
  private val CYAN = "\u001B[36m"
  private val YELLOW = "\u001B[33m"
  private val RED = "\u001B[31m"
  private val MAGENTA = "\u001B[35m"
  private val GREEN = "\u001B[32m"
  private val name = logger.getName

  private def fmt(level: String, color: String, msg: String): String =
    s"$color$level$RESET $name - $msg"

  /** Highlight a value with the given color for log messages. */
  def hl(value: String, color: String = MAGENTA): String = s"$color$value$RESET"

  /** Format session/agent context prefix for tool logs. */
  def ctxPrefix(agentName: String, sessionName: String): String =
    s"${hl(agentName, GREEN)} ${hl(sessionName, MAGENTA)}"

  private def withKv(msg: String, kv: Seq[(String, String)]): String =
    if kv.isEmpty then msg else s"$msg ${kv.map((k, v) => s"$k=$v").mkString(" ")}"

  def debug(msg: String): IO[Unit] = IO.delay(logger.debug(s"DEBUG $name - $msg"))
  def info(msg: String): IO[Unit] = IO.delay(logger.info(fmt("INFO ", CYAN, msg)))
  def warn(msg: String): IO[Unit] = IO.delay(logger.warn(fmt("WARN ", YELLOW, msg)))
  def error(msg: String): IO[Unit] = IO.delay(logger.error(fmt("ERROR", RED, msg)))
  def error(msg: String, cause: Throwable): IO[Unit] = IO.delay(logger.error(fmt("ERROR", RED, msg), cause))

  def info(msg: String, kv: (String, String)*): IO[Unit] =
    IO.delay(logger.info(fmt("INFO ", CYAN, withKv(msg, kv))))

  def warn(msg: String, kv: (String, String)*): IO[Unit] =
    IO.delay(logger.warn(fmt("WARN ", YELLOW, withKv(msg, kv))))

  def debug(msg: String, kv: (String, String)*): IO[Unit] =
    IO.delay(logger.debug(s"DEBUG $name - ${withKv(msg, kv)}"))

  // Sync variants — for use in Unit-returning contexts (e.g. Pekko actors)
  def infoSync(msg: String): Unit = logger.info(fmt("INFO ", CYAN, msg))
  def warnSync(msg: String): Unit = logger.warn(fmt("WARN ", YELLOW, msg))
  def errorSync(msg: String): Unit = logger.error(fmt("ERROR", RED, msg))
  def errorSync(msg: String, cause: Throwable): Unit = logger.error(fmt("ERROR", RED, msg), cause)

  def infoSync(msg: String, kv: (String, String)*): Unit =
    logger.info(fmt("INFO ", CYAN, withKv(msg, kv)))

  def warnSync(msg: String, kv: (String, String)*): Unit =
    logger.warn(fmt("WARN ", YELLOW, withKv(msg, kv)))

  def errorSync(msg: String, kv: (String, String)*): Unit =
    logger.error(fmt("ERROR", RED, withKv(msg, kv)))
end NebflowLogger
