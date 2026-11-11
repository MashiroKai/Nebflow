package nebflow.agent

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.util.UUID
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
  private val MainFile = "task-ledger-reminder-throttle.json"

  /** Cross-process transaction lock. Lock the stable sibling, not the replaceable data file. */
  private def withFileLock[A](file: os.Path)(body: => A): A =
    val lockPath = file / os.up
    val lockFile = lockPath / s"${file.last}.lock"
    Files.createDirectories(lockPath.toNIO)
    val channel = FileChannel.open(lockFile.toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try
      val fileLock = channel.lock()
      try body
      finally fileLock.release()
    finally channel.close()

  /** Write a complete sibling and atomically replace the state; never truncate the old state. */
  private def atomicWrite(file: os.Path, content: String): Unit =
    val tmp = file / os.up / s".${file.last}.tmp-${UUID.randomUUID()}.tmp"
    try
      Files.writeString(tmp.toNIO, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      Files.move(tmp.toNIO, file.toNIO, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(tmp.toNIO)

  /** Parse a throttle record, preserving bounded recency order while tolerating old records. */
  private def readState(file: os.Path, moveAside: (java.nio.file.Path, java.nio.file.Path) => Unit): Option[State] =
    if !os.exists(file) then Some(State())
    else
      val raw = os.read(file)
      decode[State](raw) match
        case Right(state) if state.lastDeliveredBySession.values.forall(_ >= 0L) => Some(state)
        case _ =>
          val quarantine = file / os.up / s"${file.last}.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID()}"
          try
            moveAside(file.toNIO, quarantine.toNIO)
            Some(State())
          catch case _: Exception => None

  /** Test seam for deterministic boundary/concurrency/failure checks. */
  private[agent] def due(sessionId: String, nowMs: Long, summary: => String): Option[String] =
    dueAt(PathUtil.dataRoot / MainFile, sessionId, nowMs, summary, (from, to) =>
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    )

  private[agent] def dueAt(
      file: os.Path,
      sessionId: String,
      nowMs: Long,
      summary: => String,
      moveAside: (java.nio.file.Path, java.nio.file.Path) => Unit
  ): Option[String] = lock.synchronized {
    try withFileLock(file) {
      readState(file, moveAside).flatMap { state =>
        val last = state.lastDeliveredBySession.get(sessionId)
        if last.exists(t => nowMs - t < GapMs) then None
        else
          val text = scala.util.Try(summary).getOrElse("")
          if text.trim.isEmpty then None
          else
            val next = State((state.lastDeliveredBySession - sessionId + (sessionId -> nowMs)).toList
              .sortBy { case (sid, at) => (at, sid) }.takeRight(MaxSessionKeys).toMap)
            try
              atomicWrite(file, next.asJson.noSpaces)
              Some(s"Task ledger (fresh snapshot):\n$text")
            catch case _: Exception => None
      }
    }
    catch case _: Exception => None
  }
end TaskLedgerPassiveReminder
