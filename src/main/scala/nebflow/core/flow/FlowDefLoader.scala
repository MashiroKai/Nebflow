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
            val meta = s"${nodeCount} node(s), manager: $mgr, agents: $agents"
            fd.description match
              case desc if desc.nonEmpty => s"- $name: $desc ($meta)"
              case _ => s"- $name: $meta"
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

  /** Parse a YAML string into a FlowDef (pure, no I/O). Performs structural
   *  validation and returns the first error (if any) as a readable string. */
  def parse(yaml: String): Either[String, FlowDef] =
    for
      json <- yamlParser.parse(yaml).left.map(e => s"YAML parse error: ${e.message}")
      name <- json.hcursor.downField("name").as[String].left.map(e => s"Invalid 'name': ${e.message}")
      manager <- json.hcursor.downField("manager").as[Option[String]].left.map(e => s"Invalid 'manager': ${e.message}")
      desc <- json.hcursor.downField("description").as[Option[String]].left.map(e => s"Invalid 'description': ${e.message}")
      maxConcurrency <- json.hcursor.downField("maxConcurrency").as[Option[Int]].left.map(e => s"Invalid 'maxConcurrency': ${e.message}")
      flowTimeoutSeconds <- json.hcursor.downField("flowTimeoutSeconds").as[Option[Int]].left.map(e => s"Invalid 'flowTimeoutSeconds': ${e.message}")
      nodes <- json.hcursor.downField("nodes").as[Option[List[FlowNode]]].left.map(e => s"Invalid 'nodes': ${e.message}")
      fd = FlowDef(name, manager, desc.getOrElse(""), nodes.getOrElse(Nil), maxConcurrency.getOrElse(5), flowTimeoutSeconds.getOrElse(3600))
      _ <- validate(fd)
    yield fd

  /** Structural validation of a FlowDef. Returns the first error as Left, or
   *  Right(fd) if valid. Checks: non-empty nodes, unique ids, valid node shape,
   *  dangling dependsOn/retry/condition references, dependsOn acyclicity, and
   *  that condition references a verdict node. This runs at parse time so bad
   *  definitions never reach the runtime — ExecuteFlow/mount reject them with
   *  a readable diagnostic instead of failing opaquely at execution. */
  def validate(fd: FlowDef): Either[String, FlowDef] =
    val errors = collectErrors(fd)
    if errors.nonEmpty then Left(errors.mkString("; "))
    else Right(fd)

  /** Collect ALL validation errors (not just the first) — useful for surfacing
   *  a complete diagnostic to the user/agent. */
  def collectErrors(fd: FlowDef): List[String] =
    val ids = fd.nodes.map(_.id)
    val idSet = ids.toSet

    val dupIds = ids.groupBy(identity).filter(_._2.size > 1).keys.toList
    val emptyNodes = if fd.nodes.isEmpty then List("flow has no nodes") else Nil
    val badShape = fd.nodes.filterNot(_.isValid).map(n => s"node '${n.id}' must have either (agent + prompt) or flow, not both/neither")
    val badDeps = fd.nodes.flatMap(n =>
      n.dependsOn.filterNot(idSet.contains).map(d => s"node '${n.id}' dependsOn unknown node '$d'")
    )
    val badRetry = fd.nodes.flatMap(n =>
      n.retry.flatMap(r =>
        if !idSet.contains(r.target) then Some(s"node '${n.id}' retry.target unknown node '${r.target}'")
        else if r.maxIterations < 1 then Some(s"node '${n.id}' retry.maxIterations must be >= 1")
        else None
      )
    )
    val badCond = fd.nodes.flatMap { n =>
      n.condition.flatMap { cond =>
        cond.lastIndexOf('.') match
          case idx if idx > 0 =>
            val (cid, ctype) = cond.splitAt(idx)
            val condType = ctype.drop(1)
            if !idSet.contains(cid) then Some(s"node '${n.id}' condition references unknown node '$cid'")
            else if condType != "fail" then Some(s"node '${n.id}' condition '$cond': only '.fail' is supported")
            else if !fd.nodes.find(_.id == cid).exists(_.verdict) then
              Some(s"node '${n.id}' condition references '$cid' which is not a verdict node")
            else None
          case _ => Some(s"node '${n.id}' condition '$cond' is malformed (expected 'nodeId.fail')")
      }
    }
    val cycle = detectCycle(fd.nodes).map(cyc => s"dependsOn cycle detected: ${cyc.mkString(" -> ")}")

    emptyNodes ++ dupIds.map(id => s"duplicate node id '$id'") ++ badShape ++ badDeps ++ badRetry ++ badCond ++ cycle

  /** Detect a cycle in the dependsOn graph (retry back-edges are allowed, so
   *  only dependsOn edges are considered). Returns the first cycle found. */
  private def detectCycle(nodes: List[FlowNode]): Option[List[String]] =
    val adj = nodes.map(n => n.id -> n.dependsOn.toList.filter(nodes.map(_.id).contains)).toMap
    // DFS-based cycle detection with recursion stack
    val visited = scala.collection.mutable.Set.empty[String]
    val stack = scala.collection.mutable.Set.empty[String]
    val path = scala.collection.mutable.ListBuffer.empty[String]

    def dfs(node: String): Option[List[String]] =
      if stack.contains(node) then
        val cycleStart = path.indexOf(node)
        Some(path.slice(cycleStart, path.length).toList :+ node)
      else if visited.contains(node) then None
      else
        visited += node
        stack += node
        path += node
        val result = adj.getOrElse(node, Nil).flatMap(dfs).headOption
        path.dropRightInPlace(1)
        stack -= node
        result
    nodes.map(_.id).flatMap(dfs).headOption

  /** Parse inline YAML and return FlowDef (for ExecuteFlow inline definitions). */
  def parseInline(yaml: String): IO[Either[String, FlowDef]] =
    parse(yaml) match
      case Left(e) =>
        logger.warn(s"Failed to parse inline flow definition: $e").as(Left(e))
      case Right(fd) =>
        logger.info(s"Parsed inline flow definition: ${fd.name}").as(Right(fd))

end FlowDefLoader
