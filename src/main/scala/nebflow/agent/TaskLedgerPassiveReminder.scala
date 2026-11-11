package nebflow.agent

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import java.nio.file.StandardCopyOption
import io.circe.syntax.*
import nebflow.shared.PathUtil

/** Passive task-ledger reminder: evaluated only from the real-user-turn reminder path. */
private[agent] object TaskLedgerPassiveReminder:
  private val GapMs = 2L * 60 * 60 * 1000
  private val MaxSessionKeys = 512
  private case class State(lastDeliveredBySession: Map[String, Long] = Map.empty)
  private given Encoder[State] = deriveEncoder
  private given Decoder[State] = deriveDecoder
  private val lock = new Object

  /** Parse a throttle record, preserving bounded recency order while tolerating old records. */
  private def readState(file: os.Path): Option[State] =
    if !os.exists(file) then Some(State())
    else
      val raw = os.read(file)
      decode[State](raw) match
        case Right(state) if state.lastDeliveredBySession.values.forall(_ >= 0L) => Some(state)
        case _ =>
          val quarantine = os.Path(s"${file}.corrupt-${System.currentTimeMillis()}")
          try
            java.nio.file.Files.move(file.toNIO, quarantine.toNIO, StandardCopyOption.ATOMIC_MOVE)
            Some(State())
          catch
            case _: java.nio.file.AtomicMoveNotSupportedException =>
              try
                java.nio.file.Files.move(file.toNIO, quarantine.toNIO)
                Some(State())
              catch case _: Exception => None
            case _: Exception => None

  /** Test seam for deterministic boundary/concurrency checks. */
  private[agent] def due(sessionId: String, nowMs: Long, summary: => String): Option[String] = lock.synchronized {
    val file = PathUtil.dataRoot / "task-ledger-reminder-throttle.json"
    val prior = readState(file)
    prior.flatMap { state =>
      val last = state.lastDeliveredBySession.get(sessionId)
      if last.exists(t => nowMs - t < GapMs) then None
      else
        val text = scala.util.Try(summary).getOrElse("")
        if text.trim.isEmpty then None
        else
          val next = State((state.lastDeliveredBySession - sessionId + (sessionId -> nowMs)).toList
            .sortBy { case (sid, at) => (at, sid) }.takeRight(MaxSessionKeys).toMap)
          try
            os.write.over(file, next.asJson.noSpaces, createFolders = true)
            Some(s"Task ledger (fresh snapshot):\n$text")
          catch case _: Exception => None
    }
  }
end TaskLedgerPassiveReminder
