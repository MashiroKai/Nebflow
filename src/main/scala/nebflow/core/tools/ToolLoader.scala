package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import nebflow.core.{NebflowLogger, PathUtil}

object ToolLoader:
  private val logger = NebflowLogger.forName("nebflow.tools")

  private def toolsDir: os.Path = PathUtil.dataRoot / "tools"

  // Load all external tool definitions from ~/.nebflow/tools/*.json
  def loadAll(): IO[Map[String, ExternalToolConfig]] =
    IO.blocking {
      val dir = toolsDir
      if !os.exists(dir) then Nil
      else os.list(dir).filter(_.last.endsWith(".json")).toList
    }.flatMap { paths =>
      paths.traverse { p =>
        IO.blocking(decode[ExternalToolConfig](os.read(p))).flatMap {
          case Right(config) => IO.pure(Some(config))
          case Left(err) =>
            logger.warn(s"Skipping invalid tool config at ${p.last}: ${err.getMessage}").as(None)
        }
      }.map(_.flatten.iterator.map(c => c.name -> c).toMap)
    }

  // Load a single tool by name
  def load(name: String): IO[Option[ExternalToolConfig]] =
    val file = toolsDir / s"$name.json"
    IO.blocking(os.exists(file)).flatMap {
      case false => IO.pure(None)
      case true =>
        IO.blocking(os.read(file)).flatMap { content =>
          decode[ExternalToolConfig](content) match
            case Right(config) => IO.pure(Some(config))
            case Left(err) =>
              logger.warn(s"Failed to parse tool config '$name': ${err.getMessage}").as(None)
        }
    }

  // Load all and create ScriptTool instances ready for registration
  def loadScripts(): IO[List[ScriptTool]] =
    loadAll().map(_.values.map(config => ScriptTool(config)).toList)
