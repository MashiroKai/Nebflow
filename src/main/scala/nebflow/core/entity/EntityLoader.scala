package nebflow.core.entity

import cats.effect.IO
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.agent.AgentDef
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Loads Team/Flow/Agent definitions from disk.
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
      // Guard against os-lib PathError$InvalidSegment: `teamsDir / name` requires
      // name to be a single path segment, so a scoped "team/agent" address must
      // never reach here — return None and let routing fall through.
      if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then None
      else
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
            if os.exists(jsonPath) then parseTeamJson(os.read(jsonPath)).toOption.map(dir.last -> _)
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
        val dirFlows = os
          .list(flowsDir)
          .filter(os.isDir)
          .flatMap { dir =>
            val name = dir.last
            val jsonPath = dir / "flow.json"
            if os.exists(jsonPath) then parseFlowJson(os.read(jsonPath)).toOption.map(name -> _)
            else None
          }
        // Single file format: flows/<name>.json
        val fileFlows = os
          .list(flowsDir)
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
  def loadAgentFromDir(dir: os.Path): Option[AgentEntry] =
    val jsonPath = dir / "agent.json"
    if !os.exists(jsonPath) then None
    else
      parseAgentJson(os.read(jsonPath)).toOption.flatMap { entry =>
        val sysMd = dir / "system.md"
        val prompt = if os.exists(sysMd) then os.read(sysMd) else ""
        val resolvedName = if entry.name.nonEmpty then entry.name else dir.last
        // Infer category from directory path (not from JSON field)
        val dirStr = dir.toString()
        val inferredCategory =
          if dirStr.contains("/teams/") then "team"
          else if dirStr.contains("/flows/") then "flow"
          else "standalone"
        Some(entry.copy(name = resolvedName, systemPrompt = prompt, category = inferredCategory))
      }

  end loadAgentFromDir

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
      case None => loadAgent(agentName) // fallback to global
    }

  /** Load flow-local agent: `flows/<flowName>/agents/<agentName>/` → fallback to global. */
  def loadFlowAgent(flowName: String, agentName: String): IO[Option[AgentEntry]] =
    IO.blocking {
      val flowAgentDir = flowsDir / flowName / "agents" / agentName
      if os.exists(flowAgentDir / "agent.json") then loadAgentFromDir(flowAgentDir) else None
    }.flatMap {
      case Some(entry) => IO.pure(Some(entry))
      case None => loadAgent(agentName) // fallback to global
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

  /**
   * List team-local agent entries under `teams/<teamName>/agents/`.
   *
   * Keys cover BOTH reference forms — the directory name (how loadTeamAgent
   * resolves) and the resolved agent.json name (how findAgentByName matches)
   * — so team validation accepts either. Team-local agents are invisible to
   * listAgents(); this scan is what makes them resolvable for validation.
   */
  def listTeamAgents(teamName: String): IO[Map[String, AgentEntry]] =
    IO.blocking {
      val agentsSubDir = teamsDir / teamName / "agents"
      if !os.exists(agentsSubDir) then Map.empty[String, AgentEntry]
      else
        os.list(agentsSubDir)
          .filter(os.isDir)
          .toList
          .flatMap { agentDir =>
            loadAgentFromDir(agentDir).map { entry =>
              if entry.name == agentDir.last then Map(agentDir.last -> entry)
              else Map(agentDir.last -> entry, entry.name -> entry)
            }
          }
          .foldLeft(Map.empty[String, AgentEntry])(_ ++ _)
    }

  /** Infer agent category from team/flow membership. */
  def classifyAgent(name: String, teams: Map[String, TeamDef], flows: Map[String, FlowDagDef]): String =
    val inTeam = teams.values.exists(t => t.lead == name || t.members.contains(name))
    val inFlow = flows.values.exists(_.nodes.values.exists(_.agent == name))
    (inTeam, inFlow) match
      case (true, _) => "team"
      case (false, true) => "flow"
      case (false, false) => "standalone"

  /**
   * Find an agent by name across all three layers (global → team → flow).
   *  Returns the first match as AgentDef, or None.
   *
   *  Global agents use name == dir name (fast path). Team and flow agents may
   *  have a `name` field in agent.json that differs from the directory name,
   *  so we scan all agent subdirectories and match by the resolved name.
   */
  def findAgentByName(name: String): IO[Option[AgentDef]] =
    for
      // 1. Global agents — name == dir name, fast path
      globalOpt <- IO.blocking {
        val dir = agentsDir / name
        if os.exists(dir) then loadAgentFromDir(dir) else None
      }
      // 2. Team agents — scan all team agent dirs, match by agent.json name field
      teamOpt <- globalOpt match
        case Some(_) => IO.pure(None)
        case None =>
          IO.blocking {
            if !os.exists(teamsDir) then None
            else
              os.list(teamsDir)
                .filter(os.isDir)
                .flatMap { teamDir =>
                  val agentsSubDir = teamDir / "agents"
                  if os.exists(agentsSubDir) then
                    os.list(agentsSubDir).filter(os.isDir).flatMap { agentDir =>
                      loadAgentFromDir(agentDir).filter(_.name == name)
                    }
                  else None
                }
                .headOption
          }
      // 3. Flow agents — same approach
      flowOpt <- (globalOpt, teamOpt) match
        case (Some(_), _) | (_, Some(_)) => IO.pure(None)
        case (None, None) =>
          IO.blocking {
            if !os.exists(flowsDir) then None
            else
              os.list(flowsDir)
                .filter(os.isDir)
                .flatMap { flowDir =>
                  val agentsSubDir = flowDir / "agents"
                  if os.exists(agentsSubDir) then
                    os.list(agentsSubDir).filter(os.isDir).flatMap { agentDir =>
                      loadAgentFromDir(agentDir).filter(_.name == name)
                    }
                  else None
                }
                .headOption
          }
    yield globalOpt.orElse(teamOpt).orElse(flowOpt).map(_.toAgentDef)

  /**
   * Find the directory path of an agent by name across all three layers.
   * Same search order as [[findAgentByName]] but returns the directory path
   * instead of an AgentDef. Used by PUT /api/agents/:name/model to locate
   * the agent.json to update.
   */
  def findAgentDir(name: String): IO[Option[os.Path]] =
    for
      // 1. Global agents — name == dir name, fast path
      globalOpt <- IO.blocking {
        val dir = agentsDir / name
        if os.exists(dir / "agent.json") then Some(dir) else None
      }
      // 2. Team agents
      teamOpt <- globalOpt match
        case Some(_) => IO.pure(None)
        case None =>
          IO.blocking {
            if !os.exists(teamsDir) then None
            else
              os.list(teamsDir)
                .filter(os.isDir)
                .flatMap { teamDir =>
                  val agentsSubDir = teamDir / "agents"
                  if os.exists(agentsSubDir) then
                    os.list(agentsSubDir).filter(os.isDir).flatMap { agentDir =>
                      loadAgentFromDir(agentDir).filter(_.name == name).map(_ => agentDir)
                    }
                  else None
                }
                .headOption
          }
      // 3. Flow agents
      flowOpt <- (globalOpt, teamOpt) match
        case (Some(_), _) | (_, Some(_)) => IO.pure(None)
        case (None, None) =>
          IO.blocking {
            if !os.exists(flowsDir) then None
            else
              os.list(flowsDir)
                .filter(os.isDir)
                .flatMap { flowDir =>
                  val agentsSubDir = flowDir / "agents"
                  if os.exists(agentsSubDir) then
                    os.list(agentsSubDir).filter(os.isDir).flatMap { agentDir =>
                      loadAgentFromDir(agentDir).filter(_.name == name).map(_ => agentDir)
                    }
                  else None
                }
                .headOption
          }
    yield globalOpt.orElse(teamOpt).orElse(flowOpt)

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

  /**
   * Write `teams/<name>/team.json`.
   *
   * @deprecated W3 decision (2026-08-15): the entity write path is retired —
   *   after the flow-creator team was archived no production caller remains
   *   (verified by grep: zero call sites in src/main). EntityLoader is
   *   read-only now; entity files are written by their owning creation
   *   surfaces (REST routes / user editing on disk). Do not add new callers;
   *   slated for removal.
   */
  @deprecated("W3: entity write path retired — EntityLoader is read-only; teams are created via their owning surfaces", "since 2026-08-15")
  def writeTeam(team: TeamDef): IO[Unit] =
    writeJson(teamsDir / team.name / "team.json", team.asJson.noSpaces)

  /**
   * Write `flows/<name>.json`.
   *
   * @deprecated W3 decision (2026-08-15): same retirement as [[writeTeam]] —
   *   no production caller; flows are created/edited as flow.json files by
   *   their owning surfaces. Do not add new callers; slated for removal.
   */
  @deprecated("W3: entity write path retired — EntityLoader is read-only; flows are created via their owning surfaces", "since 2026-08-15")
  def writeFlow(flow: FlowDagDef): IO[Unit] =
    writeJson(flowsDir / s"${flow.name}.json", flow.asJson.noSpaces)

  /**
   * Write `agents/<name>/agent.json` + `system.md`.
   *
   * @deprecated W3 decision (2026-08-15): same retirement as [[writeTeam]] —
   *   no production caller; agent definitions are written by their owning
   *   surfaces (AgentLibrary update methods, REST PUT endpoints). Do not add
   *   new callers; slated for removal.
   */
  @deprecated("W3: entity write path retired — EntityLoader is read-only; agents are created via their owning surfaces", "since 2026-08-15")
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
    missingLead ++ missingMembers

  /** Validate a FlowDagDef: check all node agents exist, entry is valid, routes are valid. */
  def validateFlow(flow: FlowDagDef, agents: Set[String]): List[String] =
    val entryMissing =
      if !flow.nodes.contains(flow.entry) then List(s"entry node '${flow.entry}' not in nodes") else Nil
    val missingAgents = flow.nodes.values
      .map(_.agent)
      .filterNot(agents.contains)
      .map(a => s"agent '$a' not found")
      .toList
    val badRoutes = flow.nodes.toList.flatMap { (id, node) =>
      FlowStructure.routeTargets(node.onComplete).collect {
        case (target, _) if target != FlowStructure.ReturnNode && !flow.nodes.contains(target) =>
          s"node '$id' routes to unknown node '$target'"
      }.distinct
    }
    entryMissing ++ missingAgents.distinct ++ badRoutes

  end validateFlow

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
    jsonParse(s).flatMap(_.as[FlowDagDef]).left.map(_.getMessage).flatMap { fd =>
      // R8-P2 structural validation at load time — reject structurally broken
      // flows (pure cycles, single nodes, uncovered joins, mid-branch returns,
      // fanout over cap) with a clear reason instead of letting them fail at
      // runtime in confusing ways. Agent-existence checks stay in validateFlow.
      FlowStructure.validate(fd) match
        case Nil   => Right(fd)
        case errs => Left(s"invalid flow structure: ${errs.mkString("; ")}")
      end match
    }

  private def parseAgentJson(s: String): Either[String, AgentEntry] =
    jsonParse(s).flatMap(_.as[AgentEntry]).left.map(_.getMessage)

end EntityLoader
