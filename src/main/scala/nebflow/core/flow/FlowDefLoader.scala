package nebflow.core.flow

import cats.effect.IO
import cats.implicits.*
import io.circe.yaml.parser as yamlParser
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil, Whitelist}

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

  /**
   * Build a compact flow catalog for system prompt injection.
   * @param flowFilter whitelist from the current agent's `flows` field.
   */
  def buildFlowCatalog(flowFilter: List[String] = List("*")): IO[String] =
    loadAll().map { flows =>
      val visible = flows.filter { case (name, _) => Whitelist.passes(name, flowFilter) }
      if visible.isEmpty then ""
      else
        val entries = visible.toList
          .sortBy(_._1)
          .map { case (name, fd) =>
            val nodeCount = fd.nodes.size
            val agents = fd.nodes.flatMap(_.agent).distinct.mkString("+")
            val mgr = fd.manager.getOrElse("-")
            s"- $name: ${nodeCount} node(s), manager: $mgr, agents: $agents"
          }
          .mkString("\n")
        s"""# Flows
           |
           |Flows are reusable, self-improving pipelines in ~/.nebflow/flows/. Each flow runs
           |multi-step agents with automatic verification and learning. Use ExecuteFlow to
           |mount and trigger flows.
           |
           |$entries""".stripMargin
      end if
    }

  /** Parse a YAML string into a FlowDef (pure, no I/O). */
  def parse(yaml: String): Either[String, FlowDef] =
    for
      json <- yamlParser.parse(yaml).left.map(e => s"YAML parse error: ${e.message}")
      name <- json.hcursor.downField("name").as[String].left.map(e => s"Invalid 'name': ${e.message}")
      manager <- json.hcursor.downField("manager").as[Option[String]].left.map(e => s"Invalid 'manager': ${e.message}")
      maxDepth <- json.hcursor.downField("maxDepth").as[Option[Int]].left.map(e => s"Invalid 'maxDepth': ${e.message}")
      nodes <- json.hcursor.downField("nodes").as[Option[List[FlowNode]]].left.map(e => s"Invalid 'nodes': ${e.message}")
    yield FlowDef(name, manager, nodes.getOrElse(Nil), maxDepth.getOrElse(5))

  /** Parse inline YAML and return FlowDef (for ExecuteFlow inline definitions). */
  def parseInline(yaml: String): IO[Either[String, FlowDef]] =
    parse(yaml) match
      case Left(e) =>
        logger.warn(s"Failed to parse inline flow definition: $e").as(Left(e))
      case Right(fd) =>
        logger.info(s"Parsed inline flow definition: ${fd.name}").as(Right(fd))

end FlowDefLoader
