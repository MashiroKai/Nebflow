package nebflow.core.task

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Task archive index (C2): a flat per-task index at
 * `<dataRoot>/tasks/_index.json` enabling cross-session queries
 * (TaskQuery tool + GET /api/nf-tasks) without scanning every session
 * directory. Disk constraint (qa R8.4): this is the ONLY new file under
 * tasks/ — per-task JSON layout is untouched.
 *
 * folderId/folderName are joined from SessionStore at refresh time;
 * sessions missing from the index (deleted) keep folderId=null → the
 * frontend groups them as 未分类/uncategorized.
 */
object TaskArchive:

  final case class SessionJoin(
    folderId: Option[String],
    folderName: Option[String],
    sessionName: Option[String]
  )

  val Unclassified: SessionJoin = SessionJoin(None, None, None)

  final case class IndexEntry(
    sessionId: String,
    folderId: Option[String],
    folderName: Option[String],
    sessionName: Option[String],
    taskId: String,
    subject: String,
    status: String,
    createdAt: Option[String],
    updatedAt: Option[String],
    completedAt: Option[String],
    noteCount: Int,
    hasLinks: Boolean
  )

  object IndexEntry:
    given Configuration = Configuration.default.withDefaults
    given Codec[IndexEntry] = ConfiguredCodec.derived

  private val logger = NebflowLogger.forName("nebflow.taskarchive")

  /** `def` — dataRoot may be swapped after init (see FileTaskStore.root). */
  private def tasksRoot: os.Path = PathUtil.dataRoot / "tasks"
  private def indexPath: os.Path = tasksRoot / "_index.json"

  def loadIndex(): IO[List[IndexEntry]] = IO.blocking {
    if !os.exists(indexPath) then Nil
    else
      decode[List[IndexEntry]](os.read(indexPath)) match
        case Right(entries) => entries
        case Left(err) =>
          logger.warn(s"Task index unreadable, treating as empty: ${err.getMessage}")
          Nil
  }

  private def writeIndex(entries: List[IndexEntry]): IO[Unit] = IO.blocking {
    os.makeDir.all(tasksRoot)
    os.write.over(indexPath, entries.asJson.noSpaces)
  }

  private def entriesForSession(sessionId: String, join: String => SessionJoin): IO[List[IndexEntry]] =
    val dir = tasksRoot / sessionId
    IO.blocking {
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(p => p.last.endsWith(".json") && !p.last.startsWith("."))
          .toList
          .sortBy(_.last.stripSuffix(".json").toIntOption.getOrElse(0))
    }.flatMap { paths =>
      val j = join(sessionId)
      paths.traverse { p =>
        IO.blocking {
          decode[Task](os.read(p)).toOption.map { t =>
            IndexEntry(
              sessionId = sessionId,
              folderId = j.folderId,
              folderName = j.folderName,
              sessionName = j.sessionName,
              taskId = t.id,
              subject = t.subject,
              status = t.status.toString.toLowerCase,
              createdAt = t.createdAt,
              updatedAt = t.updatedAt,
              completedAt = t.completedAt,
              noteCount = t.notes.size,
              hasLinks = t.notes.exists(_.links.nonEmpty)
            )
          }
        }
      }.map(_.flatten)
    }

  /** Full rebuild across every session directory on disk. */
  def rebuildIndex(join: String => SessionJoin): IO[List[IndexEntry]] = IO.blocking {
    if !os.exists(tasksRoot) then Nil
    else os.list(tasksRoot).filter(os.isDir(_)).toList.map(_.last)
  }.flatMap { sessionIds =>
    sessionIds.traverse(entriesForSession(_, join)).map(_.flatten)
  }.flatMap { entries =>
    writeIndex(entries).as(entries)
  }

  /**
   * Incremental refresh for one session (qa R8.3): replace that session's
   * slice in the index, keep every other session's entries as-is. Creates
   * the index file if absent.
   */
  def refreshSession(sessionId: String, join: String => SessionJoin): IO[Unit] =
    loadIndex().flatMap { existing =>
      entriesForSession(sessionId, join).flatMap { fresh =>
        writeIndex(existing.filterNot(_.sessionId == sessionId) ++ fresh)
      }
    }.handleErrorWith { e =>
      logger.warn(s"Task index refresh failed for $sessionId: ${e.getMessage}")
      IO.unit // best-effort — index staleness never blocks the tool call
    }

end TaskArchive
