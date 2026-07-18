package nebflow.core.flow

import cats.effect.IO
import cats.implicits.*
import io.circe.yaml.parser as yamlParser
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil

/** Loads YAML flow definitions from the flows directory. */
object FlowDefLoader:
  private val logger = NebflowLogger(getClass)

  private def flowsDir: os.Path = PathUtil.dataRoot / "flows"

  /** Load a single flow definition by name. Looks for ~/.nebflow/flows/<name>.yaml */
  def load(name: String): IO[Option[FlowDef]] =
    val file = flowsDir / s"$name.yaml"
    IO.blocking {
      if os.exists(file) then
        parse(os.read(file)) match
          case Right(fd) => Some(fd)
          case Left(e) =>
            logger.warnSync(s"Failed to parse flow definition '$name': $e")
            None
      else None
    }

  /** Load all flow definitions from ~/.nebflow/flows/ */
  def loadAll(): IO[Map[String, FlowDef]] =
    for
      files <- IO.blocking {
        if os.exists(flowsDir) then
          os.list(flowsDir).toArray.toList.filter(_.last.endsWith(".yaml"))
        else Nil
      }
      results <- files.traverse { file =>
        val flowName = file.last.stripSuffix(".yaml")
        load(flowName).map(flowName -> _)
      }
    yield results.collect { case (name, Some(fd)) => name -> fd }.toMap

  /** Parse a YAML string into a FlowDef (pure, no I/O). */
  def parse(yaml: String): Either[String, FlowDef] =
    for
      json       <- yamlParser.parse(yaml).left.map(e => s"YAML parse error: ${e.message}")
      name       <- json.hcursor.downField("name").as[String].left.map(e => s"Invalid 'name': ${e.message}")
      maxDepth   <- json.hcursor.downField("maxDepth").as[Option[Int]].left.map(e => s"Invalid 'maxDepth': ${e.message}")
      branchType <- json.as[BranchType].left.map(e => s"BranchType decode error: ${e.message}")
    yield FlowDef(name, branchType, maxDepth.getOrElse(5))

  /** Parse inline YAML and return FlowDef (for MountFlow inline definitions). */
  def parseInline(yaml: String): IO[Either[String, FlowDef]] =
    parse(yaml) match
      case Left(e) =>
        logger.warn(s"Failed to parse inline flow definition: $e").as(Left(e))
      case Right(fd) =>
        logger.info(s"Parsed inline flow definition: ${fd.name}").as(Right(fd))

end FlowDefLoader
