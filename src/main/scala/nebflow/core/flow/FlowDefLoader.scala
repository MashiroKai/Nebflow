package nebflow.core.flow

import cats.effect.IO
import cats.implicits.*
import io.circe.yaml.parser as yamlParser
import nebflow.core.{NebflowLogger, PathUtil}

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
        if os.exists(flowsDir) then os.list(flowsDir).toArray.toList.filter(_.last.endsWith(".yaml"))
        else Nil
      }
      results <- files.traverse { file =>
        val flowName = file.last.stripSuffix(".yaml")
        load(flowName).map(flowName -> _)
      }
    yield results.collect { case (name, Some(fd)) => name -> fd }.toMap

  /** Build a compact flow catalog for system prompt injection. */
  def buildFlowCatalog(): IO[String] =
    loadAll().map { flows =>
      val visible = flows.filter { case (_, fd) => fd.branchType.isInstanceOf[BranchType.Pipeline] }
      if visible.isEmpty then ""
      else
        val entries = visible.toList
          .sortBy(_._1)
          .map { case (name, fd) =>
            fd.branchType match
              case p: BranchType.Pipeline =>
                val stepCount = p.steps.size
                val agents = p.steps.flatMap(_.agent).distinct.mkString("+")
                val meta = s"${stepCount} step(s), agents: $agents"
                fd.description match
                  case desc if desc.nonEmpty => s"- $name: $desc ($meta)"
                  case _ => s"- $name: $meta"
              case other => s"- $name: ${other.typeName}"
          }
          .mkString("\n")
        s"""# Flows
           |
           |Flows are reusable, self-improving pipelines. Each flow runs multi-step agents with
           |automatic verification (verify-fix loop) and learns from past runs. Mount once, then
           |trigger with different inputs. Check the catalog below — if a flow matches your task,
           |prefer it over manual delegation: it handles orchestration, parallelism, and quality
           |gates for you.
           |
           |$entries""".stripMargin
      end if
    }

  /** Parse a YAML string into a FlowDef (pure, no I/O). */
  def parse(yaml: String): Either[String, FlowDef] =
    for
      json <- yamlParser.parse(yaml).left.map(e => s"YAML parse error: ${e.message}")
      name <- json.hcursor.downField("name").as[String].left.map(e => s"Invalid 'name': ${e.message}")
      desc <- json.hcursor.downField("description").as[Option[String]].left.map(e => s"Invalid 'description': ${e.message}")
      maxDepth <- json.hcursor.downField("maxDepth").as[Option[Int]].left.map(e => s"Invalid 'maxDepth': ${e.message}")
      branchType <- json.as[BranchType].left.map(e => s"BranchType decode error: ${e.message}")
    yield FlowDef(name, branchType, desc.getOrElse(""), maxDepth.getOrElse(5))

  /** Parse inline YAML and return FlowDef (for MountFlow inline definitions). */
  def parseInline(yaml: String): IO[Either[String, FlowDef]] =
    parse(yaml) match
      case Left(e) =>
        logger.warn(s"Failed to parse inline flow definition: $e").as(Left(e))
      case Right(fd) =>
        logger.info(s"Parsed inline flow definition: ${fd.name}").as(Right(fd))

end FlowDefLoader
