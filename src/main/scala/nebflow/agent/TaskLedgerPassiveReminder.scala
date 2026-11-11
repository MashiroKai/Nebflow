package nebflow.agent

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
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

  /** Test seam for deterministic boundary/concurrency checks. */
  private[agent] def due(sessionId: String, nowMs: Long, summary: => String): Option[String] = lock.synchronized {
    val file = PathUtil.dataRoot / "task-ledger-reminder-throttle.json"
    val prior =
      if !os.exists(file) then State()
      else decode[State](os.read(file)).getOrElse(State())
    val last = prior.lastDeliveredBySession.get(sessionId)
    if last.exists(t => nowMs - t < GapMs) then None
    else
      val text = scala.util.Try(summary).getOrElse("")
      if text.trim.isEmpty then None
      else
        val next = State((prior.lastDeliveredBySession - sessionId + (sessionId -> nowMs)).toList
          .sortBy(_._2).takeRight(MaxSessionKeys).toMap)
        try
          os.write.over(file, next.asJson.noSpaces, createFolders = true)
          Some(s"Task ledger (fresh snapshot):\n$text")
        catch case _: Exception => None
  }
end TaskLedgerPassiveReminder
