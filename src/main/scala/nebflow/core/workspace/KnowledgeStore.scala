package nebflow.core.workspace

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

class KnowledgeStore(baseDir: os.Path):

  private val logger = NebflowLogger.forName("nebflow.workspace.store")

  private def sessionFile(sessionId: String): os.Path =
    baseDir / s"$sessionId.json"

  private def ensureBaseDir: IO[Unit] = IO.blocking {
    if !os.exists(baseDir) then os.makeDir.all(baseDir)
  }

  def loadItems(sessionId: String): IO[List[WorkspaceItem]] = IO.blocking {
    val f = sessionFile(sessionId)
    if !os.exists(f) then Nil
    else
      decode[List[WorkspaceItem]](os.read(f)) match
        case Right(list) => list
        case Left(err) =>
          logger.warnSync(s"Failed to parse workspace items for $sessionId: ${err.getMessage}")
          Nil
  }

  def saveItems(sessionId: String, items: List[WorkspaceItem]): IO[Unit] =
    ensureBaseDir *> IO.blocking {
      os.write.over(sessionFile(sessionId), items.asJson.noSpaces)
    }

  def addItem(item: WorkspaceItem): IO[Unit] =
    loadItems(item.sessionId).flatMap { existing =>
      saveItems(item.sessionId, existing :+ item)
    }

  def deleteItem(sessionId: String, itemId: String): IO[Unit] =
    loadItems(sessionId).flatMap { existing =>
      saveItems(sessionId, existing.filterNot(_.id == itemId))
    }

end KnowledgeStore
