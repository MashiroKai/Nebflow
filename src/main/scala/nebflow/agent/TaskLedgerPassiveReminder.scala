package nebflow.agent

import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.shared.PathUtil

import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.util.UUID

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
  private def atomicWrite(
    file: os.Path,
    content: String,
    replace: (java.nio.file.Path, java.nio.file.Path) => Unit
  ): Unit =
    val tmp = file / os.up / s".${file.last}.tmp-${UUID.randomUUID()}.tmp"
    try
      Files.writeString(tmp.toNIO, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      replace(tmp.toNIO, file.toNIO)
    finally Files.deleteIfExists(tmp.toNIO)

  private val atomicReplace: (java.nio.file.Path, java.nio.file.Path) => Unit = (from, to) =>
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

  /** Production reminder entry: gates the due check itself, so excluded turns cannot spend quota. */
  private[agent] def forTurn(
    isRealUserTurn: Boolean,
    isRootAgent: Boolean,
    isCompactTurn: Boolean,
    isAskTurn: Boolean,
    sessionId: Option[String],
    nowMs: Long,
    summary: => String
  ): Option[nebflow.shared.SystemReminder] =
    if isRealUserTurn && isRootAgent && !isCompactTurn && !isAskTurn then
      sessionId
        .flatMap(sid => due(sid, nowMs, summary))
        .map(text => nebflow.shared.SystemReminder("task-ledger", text))
    else None

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
    dueAt(
      PathUtil.dataRoot / MainFile,
      sessionId,
      nowMs,
      summary,
      (from, to) => Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    )

  private[agent] def dueAt(
    file: os.Path,
    sessionId: String,
    nowMs: Long,
    summary: => String,
    moveAside: (java.nio.file.Path, java.nio.file.Path) => Unit
  ): Option[String] = dueAtWithReplace(file, sessionId, nowMs, summary, moveAside, atomicReplace)

  /** Small production dependency seam: inject only the final replacement operation in tests. */
  private[agent] def dueAtWithReplace(
    file: os.Path,
    sessionId: String,
    nowMs: Long,
    summary: => String,
    moveAside: (java.nio.file.Path, java.nio.file.Path) => Unit,
    replace: (java.nio.file.Path, java.nio.file.Path) => Unit
  ): Option[String] = lock.synchronized {
    try
      withFileLock(file) {
        readState(file, moveAside).flatMap { state =>
          val last = state.lastDeliveredBySession.get(sessionId)
          if last.exists(t => nowMs - t < GapMs) then None
          else
            val text = scala.util.Try(summary).getOrElse("")
            if text.trim.isEmpty then None
            else
              val next = State(
                (state.lastDeliveredBySession - sessionId + (sessionId -> nowMs)).toList
                  .sortBy { case (sid, at) => (at, sid) }
                  .takeRight(MaxSessionKeys)
                  .toMap
              )
              try
                atomicWrite(file, next.asJson.noSpaces, replace)
                Some(s"Task ledger (fresh snapshot):\n$text")
              catch case _: Exception => None
          end if
        }
      }
    catch case _: Exception => None
  }
end TaskLedgerPassiveReminder
