package nebflow.core

import cats.effect.IO
import org.slf4j.{Logger, LoggerFactory, MDC}

object NebflowLogger:
  def apply(cls: Class[?]): NebflowLogger = new NebflowLogger(LoggerFactory.getLogger(cls))
  def forName(name: String): NebflowLogger = new NebflowLogger(LoggerFactory.getLogger(name))

class NebflowLogger(private val logger: Logger):
  def debug(msg: String): IO[Unit] = IO.delay(logger.debug(msg))
  def info(msg: String): IO[Unit] = IO.delay(logger.info(msg))
  def warn(msg: String): IO[Unit] = IO.delay(logger.warn(msg))
  def error(msg: String): IO[Unit] = IO.delay(logger.error(msg))
  def error(msg: String, cause: Throwable): IO[Unit] = IO.delay(logger.error(msg, cause))

  def info(msg: String, kv: (String, String)*): IO[Unit] = IO.delay {
    kv.foreach { case (k, v) => MDC.put(k, v) }
    try logger.info(msg)
    finally kv.foreach { case (k, _) => MDC.remove(k) }
  }

  def warn(msg: String, kv: (String, String)*): IO[Unit] = IO.delay {
    kv.foreach { case (k, v) => MDC.put(k, v) }
    try logger.warn(msg)
    finally kv.foreach { case (k, _) => MDC.remove(k) }
  }

  def debug(msg: String, kv: (String, String)*): IO[Unit] = IO.delay {
    kv.foreach { case (k, v) => MDC.put(k, v) }
    try logger.debug(msg)
    finally kv.foreach { case (k, _) => MDC.remove(k) }
  }
end NebflowLogger
