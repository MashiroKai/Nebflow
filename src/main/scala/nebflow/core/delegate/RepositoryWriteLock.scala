package nebflow.core.delegate

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import scala.util.Try

/** Shared by applications using different data roots but the same git common-dir. */
object RepositoryWriteLock:

  def withLock[A](repository: String)(action: => A): Either[String, A] =
    Try {
      val directory = java.nio.file.Path.of(repository).toRealPath()
      val channel = FileChannel.open(
        directory.resolve("stand-workspace-write.lock"),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
      )
      try
        val lock = channel.tryLock()
        require(lock != null, "Repository is in use by another workspace operation")
        try action
        finally lock.release()
      finally channel.close()
    }.toEither.left.map(e => Option(e.getMessage).filter(_.nonEmpty).getOrElse("Repository is in use"))
end RepositoryWriteLock
