package nebflow.core.entity

import cats.effect.IO
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

/** Loads Team/Flow/Agent definitions from disk.
 *
 *  JSON-first: new format files are `teams/<name>/team.json`, `flows/<name>.json`,
 *  `agents/<name>/agent.json`. All writes are atomic (temp file + rename).
 */
object EntityLoader:
  private val logger = NebflowLogger.forName("nebflow.entity.loader")

  private def teamsDir: os.Path = PathUtil.dataRoot / "teams"
  private def flowsDir: os.Path = PathUtil.dataRoot / "flows"
  private def agentsDir: os.Path = PathUtil.dataRoot / "agents"

  // ==========================================================
  // Team
  // ==========================================================

  /** Load a team definition by name from `teams/<name>/team.json`. */
  def loadTeam(name: String): IO[Option[TeamDef]] =
    IO.blocking {
      val jsonPath = teamsDir / name / "team.json"
      if os.exists(jsonPath) then
        parseTeamJson(os.read(jsonPath)) match
          case Right(td) => Some(td)
          case Left(e) =>
            logger.warnSync(s"Failed to parse team '$name': $e")
            None
      else None
    }

  /** List all teams from `teams/<name>/team.json`. */
  def listTeams(): IO[Map[String, TeamDef]] =
    IO.blocking {
      if !os.exists(teamsDir) then Map.empty
      else
        os.list(teamsDir)
          .filter(os.isDir)
          .map { dir =>
            val jsonPath = dir / "team.json"
            if os.exists(jsonPath) then
              parseTeamJson(os.read(jsonPath)).toOption.map(dir.last -> _)
            else None
          }
          .flatten
          .toMap
    }

  /** Load team rules from `teams/<name>/rules.md`. */
  def loadTeamRules(name: String): IO[String] =
    IO.blocking {
      val p = teamsDir / name / "rules.md"
      if os.exists(p) then os.read(p) else ""
    }

  /** Load team lead system.md from `teams/<name>/agents/lead/system.md`. */
  def loadTeamLeadPrompt(name: String): IO[String] =
    IO.blocking {
      val p = teamsDir / name / "agents" / "lead" / "system.md"
      if os.exists(p) then os.read(p) else ""
    }

  // ==========================================================
  // Flow DAG
  // ==========================================================

  /** Load a flow DAG definition. Tries directory format first, then single file. */
  def loadFlow(name: String): IO[Option[FlowDagDef]] =
    IO.blocking {
      // 1. Try directory format: flows/<name>/flow.json
      val dirPath = flowsDir / name / "flow.json"
      // 2. Fallback to single file: flows/<name>.json
      val filePath = flowsDir / s"$name.json"
      val path = if os.exists(dirPath) then dirPath else filePath
      if os.exists(path) then
        parseFlowJson(os.read(path)) match
          case Right(fd) => Some(fd)
          case Left(e) =>
            logger.warnSync(s"Failed to parse flow '$name': $e")
            None
      else None
    }

  /** List all flows. Scans both directory format (flows/\<name\>/flow.json) and single file (flows/\<name\>.json). */
  def listFlows(): IO[Map[String, FlowDagDef]] =
    IO.blocking {
      if !os.exists(flowsDir) then Map.empty
      else
        // Directory format: flows/<name>/flow.json
        val dirFlows = os.list(flowsDir)
          .filter(os.isDir)
          .flatMap { dir =>
            val name = dir.last
            val jsonPath = dir / "flow.json"
            if os.exists(jsonPath) then
              parseFlowJson(os.read(jsonPath)).toOption.map(name -> _)
            else None
          }
        // Single file format: flows/<name>.json
        val fileFlows = os.list(flowsDir)
          .filter(f => os.isFile(f) && f.last.endsWith(".json"))
          .flatMap { f =>
            val name = f.last.stripSuffix(".json")
            parseFlowJson(os.read(f)).toOption.map(name -> _)
          }
        (dirFlows ++ fileFlows).toMap
    }

  /** Load flow lead system.md from `flows/<name>/agents/lead/system.md`. */
  def loadFlowLeadPrompt(name: String): IO[String] =
    IO.blocking {
      val p = flowsDir / name / "agents" / "lead" / "system.md"
      if os.exists(p) then os.read(p) else ""
    }

  // ==========================================================
  // Agent
  // ==========================================================

  /** Load agent entry from an arbitrary directory. Shared helper. */
  private def loadAgentFromDir(dir: os.Path): Option[AgentEntry] =
    val jsonPath = dir / "agent.json"
    if !os.exists(jsonPath) then None
    else
      parseAgentJson(os.read(jsonPath)).toOption.flatMap { entry =>
        val sysMd = dir / "system.md"
        val prompt = if os.exists(sysMd) then os.read(sysMd) else ""
        Some(entry.copy(systemPrompt = prompt))
      }

  /** Load agent entry from `agents/<name>/agent.json` + `system.md`. */
  def loadAgent(name: String): IO[Option[AgentEntry]] =
    IO.blocking { loadAgentFromDir(agentsDir / name) }

  /** Load team-local agent: `teams/<teamName>/agents/<agentName>/` → fallback to global. */
  def loadTeamAgent(teamName: String, agentName: String): IO[Option[AgentEntry]] =
    IO.blocking {
      val teamAgentDir = teamsDir / teamName / "agents" / agentName
      if os.exists(teamAgentDir / "agent.json") then loadAgentFromDir(teamAgentDir) else None
    }.flatMap {
      case Some(entry) => IO.pure(Some(entry))
      case None => loadAgent(agentName)  // fallback to global
    }

  /** Load flow-local agent: `flows/<flowName>/agents/<agentName>/` → fallback to global. */
  def loadFlowAgent(flowName: String, agentName: String): IO[Option[AgentEntry]] =
    IO.blocking {
      val flowAgentDir = flowsDir / flowName / "agents" / agentName
      if os.exists(flowAgentDir / "agent.json") then loadAgentFromDir(flowAgentDir) else None
    }.flatMap {
      case Some(entry) => IO.pure(Some(entry))
      case None => loadAgent(agentName)  // fallback to global
    }

  /** List all agents with runtime category inference. */
  def listAgents(): IO[Map[String, AgentEntry]] =
    for
      rawAgents <- IO.blocking {
        if !os.exists(agentsDir) then Map.empty[String, AgentEntry]
        else
          os.list(agentsDir)
            .filter(os.isDir)
            .flatMap { dir =>
              val name = dir.last
              loadAgentFromDir(dir).map(name -> _)
            }
            .toMap
      }
      teams <- listTeams()
      flows <- listFlows()
      // Infer category for agents that have default "standalone"
      inferred = rawAgents.map { (name, entry) =>
        if entry.category == "standalone" then
          val inferred = classifyAgent(name, teams, flows)
          name -> entry.copy(category = inferred)
        else name -> entry
      }
    yield inferred

  /** Infer agent category from team/flow membership. */
  def classifyAgent(name: String, teams: Map[String, TeamDef], flows: Map[String, FlowDagDef]): String =
    val inTeam = teams.values.exists(t => t.lead == name || t.members.contains(name))
    val inFlow = flows.values.exists(_.nodes.values.exists(_.agent == name))
    (inTeam, inFlow) match
      case (true, _) => "team"
      case (false, true) => "flow"
      case (false, false) => "standalone"

  // ==========================================================
  // Write operations (atomic: temp file + rename)
  // ==========================================================

  /** Atomically write JSON to a file path. */
  def writeJson(path: os.Path, json: String): IO[Unit] =
    IO.blocking {
      os.makeDir.all(path / os.up)
      val tmp = path / os.up / s".${path.last}.${System.nanoTime()}.tmp"
      os.write(tmp, json)
      os.move.over(tmp, path)
    }

  /** Write `teams/<name>/team.json`. */
  def writeTeam(team: TeamDef): IO[Unit] =
    writeJson(teamsDir / team.name / "team.json", team.asJson.noSpaces)

  /** Write `flows/<name>.json`. */
  def writeFlow(flow: FlowDagDef): IO[Unit] =
    writeJson(flowsDir / s"${flow.name}.json", flow.asJson.noSpaces)

  /** Write `agents/<name>/agent.json` + `system.md`. */
  def writeAgent(entry: AgentEntry): IO[Unit] =
    IO.blocking {
      val dir = agentsDir / entry.name
      os.makeDir.all(dir)
      val json = entry.copy(systemPrompt = "").asJson.noSpaces
      val tmpJson = dir / s".agent.json.${System.nanoTime()}.tmp"
      os.write(tmpJson, json)
      os.move.over(tmpJson, dir / "agent.json")
      if entry.systemPrompt.nonEmpty then
        val tmpMd = dir / s".system.md.${System.nanoTime()}.tmp"
        os.write(tmpMd, entry.systemPrompt)
        os.move.over(tmpMd, dir / "system.md")
    }

  /** Delete agent directory. */
  def deleteAgent(name: String): IO[Unit] =
    IO.blocking {
      val dir = agentsDir / name
      if os.exists(dir) then os.remove.all(dir)
    }

  // ==========================================================
  // Validation
  // ==========================================================

  /** Validate a TeamDef: check lead and all members exist in agent library. */
  def validateTeam(team: TeamDef, agents: Set[String]): List[String] =
    val missingLead = if !agents.contains(team.lead) then List(s"lead agent '${team.lead}' not found") else Nil
    val missingMembers = team.members.filterNot(agents.contains).map(m => s"member agent '$m' not found")
    val missingFlows = team.flows.filterNot(f => os.exists(flowsDir / s"$f.json"))
      .map(f => s"flow '$f' not found")
    missingLead ++ missingMembers ++ missingFlows

  /** Validate a FlowDagDef: check all node agents exist, entry is valid, routes are valid. */
  def validateFlow(flow: FlowDagDef, agents: Set[String]): List[String] =
    val entryMissing = if !flow.nodes.contains(flow.entry) then List(s"entry node '${flow.entry}' not in nodes") else Nil
    val missingAgents = flow.nodes.values.map(_.agent).filterNot(agents.contains)
      .map(a => s"agent '$a' not found").toList
    val badRoutes = flow.nodes.toList.flatMap { (id, node) =>
      node.onComplete match
        case NodeRoute.Goto(target) if !flow.nodes.contains(target) =>
          List(s"node '$id' routes to unknown node '$target'")
        case NodeRoute.Switch(_, cases) =>
          cases.toList.flatMap {
            case (_, NodeRoute.Goto(target)) if !flow.nodes.contains(target) =>
              List(s"node '$id' switch case routes to unknown node '$target'")
            case _ => Nil
          }
        case _ => Nil
    }
    entryMissing ++ missingAgents.distinct ++ badRoutes

  /** Check if an agent is referenced by any team or flow. */
  def agentReferenced(name: String): IO[Boolean] =
    for
      teams <- listTeams()
      flows <- listFlows()
      inTeams = teams.values.exists(t => t.lead == name || t.members.contains(name))
      inFlows = flows.values.exists(_.nodes.values.exists(_.agent == name))
    yield inTeams || inFlows

  // ==========================================================
  // Parsers
  // ==========================================================

  private def parseTeamJson(s: String): Either[String, TeamDef] =
    jsonParse(s).flatMap(_.as[TeamDef]).left.map(_.getMessage)

  private def parseFlowJson(s: String): Either[String, FlowDagDef] =
    jsonParse(s).flatMap(_.as[FlowDagDef]).left.map(_.getMessage)

  private def parseAgentJson(s: String): Either[String, AgentEntry] =
    jsonParse(s).flatMap(_.as[AgentEntry]).left.map(_.getMessage)

end EntityLoader
