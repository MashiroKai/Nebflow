package nebflow.core.daemon

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

/** File-based persistence for daemon configurations (~/.nebflow/daemons.json). */
class DaemonStore(configPath: os.Path = PathUtil.dataRoot / "daemons.json"):

  private val logger = NebflowLogger.forName("nebflow.daemon.store")

  private def ensureParent: IO[Unit] = IO.blocking {
    os.makeDir.all(configPath / os.up)
  }

  def load(): IO[List[DaemonConfig]] = IO
    .blocking {
      if !os.exists(configPath) then Nil
      else
        val raw = os.read(configPath)
        // Support both bare array [...] and wrapper {"daemons": [...]} formats
        decode[List[DaemonConfig]](raw) match
          case Right(list) => list
          case _ =>
            decode[DaemonConfigFile](raw) match
              case Right(file) => file.daemons
              case Left(err) =>
                logger.warnSync(s"Failed to parse daemons.json: ${err.getMessage}")
                Nil
    }
    .handleErrorWith { e =>
      logger.warn(s"Failed to load daemons.json: ${e.getMessage}").as(Nil)
    }

  def save(daemons: List[DaemonConfig]): IO[Unit] =
    ensureParent *> IO.blocking {
      val tmp = configPath / os.up / ".daemons.json.tmp"
      os.write(tmp, DaemonConfigFile(daemons).asJson.noSpaces)
      os.move.over(tmp, configPath)
    }

  def add(config: DaemonConfig): IO[List[DaemonConfig]] =
    load().flatMap { existing =>
      val updated = existing.filterNot(_.id == config.id) :+ config
      save(updated).as(updated)
    }

  def remove(id: String): IO[List[DaemonConfig]] =
    load().flatMap { existing =>
      val updated = existing.filterNot(_.id == id)
      save(updated).as(updated)
    }

  def update(id: String, fn: DaemonConfig => DaemonConfig): IO[Option[DaemonConfig]] =
    load().flatMap { existing =>
      existing.find(_.id == id) match
        case None => IO.pure(None)
        case Some(cfg) =>
          val updated = fn(cfg)
          save(existing.map(d => if d.id == id then updated else d)).as(Some(updated))
    }

end DaemonStore
