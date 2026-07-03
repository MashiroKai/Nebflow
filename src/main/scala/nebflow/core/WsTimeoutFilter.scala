package nebflow.core

import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * Suppresses scary-but-harmless WebSocket disconnection stack traces caused by
 * OS sleep / wake. When the computer sleeps, TCP connections time out and http4s
 * logs "WebSocket connection terminated with exception" with a full IOException
 * stack trace. This filter silences those specific events so the log stays clean;
 * genuine errors still pass through.
 */
class WsTimeoutFilter extends TurboFilter:

  /** Throwable class names that indicate a sleep/network-interruption cause. */
  private val benignThrowables = Set(
    "java.io.IOException",
    "java.net.SocketException",
    "javax.net.ssl.SSLException"
  )

  /** Substrings in the throwable message that confirm a sleep/wake or network drop. */
  private val benignMessages = Seq(
    "Operation timed out",
    "Connection reset",
    "Broken pipe",
    "connection closed",
    "closed",
    "timeout"
  )

  override def decide(
    marker: Marker,
    logger: Logger,
    level: Level,
    format: String,
    params: Array[Object],
    t: Throwable
  ): FilterReply =
    if t == null || format == null then FilterReply.NEUTRAL
    else if !format.toLowerCase.contains("terminated") then FilterReply.NEUTRAL
    else
      // Walk the throwable cause chain looking for a benign network error
      def isBenign(throwable: Throwable): Boolean =
        val className = throwable.getClass.getName
        val msg = Option(throwable.getMessage).getOrElse("")
        benignThrowables.contains(className) && benignMessages.exists(m => msg.toLowerCase.contains(m.toLowerCase))

      def checkCause(throwable: Throwable, depth: Int): Boolean =
        if depth > 5 then false
        else if isBenign(throwable) then true
        else
          Option(throwable.getCause) match
            case Some(c) if c != throwable => checkCause(c, depth + 1)
            case _ => false

      if checkCause(t, 0) then FilterReply.DENY
      else FilterReply.NEUTRAL
  end decide

end WsTimeoutFilter
