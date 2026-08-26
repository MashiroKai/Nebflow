package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.entity.{EntityLoader, FlowDagDef, TeamDef}
import nebflow.core.flow.{FlowTreeRegistry, TreeCommand}
import nebflow.core.{NebflowLogger, PathUtil}

object LoadTool extends Tool:
  private val logger = NebflowLogger.forName("nebflow.tools.load")

  val name = "Load"

  val description =
    """Load a Team or Flow definition from disk, validate it, and activate it.

Actions:
- type "team": Read ~/.nebflow/teams/<name>/team.json, validate structure + agent references, then mount.
  If valid, the Team is immediately ready — Mail the lead to trigger work.
- type "flow": Read ~/.nebflow/flows/<name>.json, validate DAG structure + agent references.
  Reports syntax errors, missing agents, dangling routes, etc.

Use this after writing or editing team.json / flow.json files. Always loads the latest from disk — no separate create/update distinction."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "type" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> List("team", "flow").map(_.asJson).asJson,
          "description" -> "\"team\" to load + mount a team, \"flow\" to validate a flow definition".asJson
        ),
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Team or Flow name (matches the JSON filename without .json)".asJson
        )
      ),
      "required" -> List("type", "name").asJson
    )
  )

  def summarize(input: JsonObject): String =
    val t = input("type").flatMap(_.asString).getOrElse("?")
    val n = input("name").flatMap(_.asString).getOrElse("?")
    s"Load($t: $n)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val entityType = input("type").flatMap(_.asString).getOrElse("")
    val name = input("name").flatMap(_.asString).getOrElse("")

    if name.isEmpty then IO.pure(Left(ToolError("Missing 'name'")))
    else
      entityType match
        case "team" => loadTeam(name, ctx)
        case "flow" => loadFlow(name)
        case _ => IO.pure(Left(ToolError(s"Unknown type '$entityType'. Use 'team' or 'flow'.")))

  // ============================================================
  // Team: validate + mount
  // ============================================================

  private def loadTeam(name: String, ctx: ToolContext): IO[Either[ToolError, String]] =
    val jsonPath = PathUtil.dataRoot / "teams" / name / "team.json"
    for
      fileExists <- IO.blocking(os.exists(jsonPath))
      result <-
        if !fileExists then IO.pure(Left(ToolError(s"Team file not found: teams/$name/team.json")))
        else
          IO.blocking(os.read(jsonPath)).flatMap { raw =>
            jsonParse(raw).flatMap(_.as[TeamDef]) match
              case Right(teamDef) => validateAndMount(teamDef, ctx)
              case Left(parseErr) =>
                IO.pure(
                  Left(
                    ToolError(
                      s"""JSON parse error in teams/$name/team.json:
                     |$parseErr
                     |
                     |Common issues:
                     |- Missing required fields: name, description, lead
                     |- Wrong field types (e.g. members must be an array of strings)
                     |- Trailing commas or unquoted strings""".stripMargin
                    )
                  )
                )
          }
    yield result

    end for

  end loadTeam

  /**
   * Agent-name set a team's references may resolve against: global agents ∪
   * the team's own local agents (teams/<name>/agents/). Team-local agents are
   * invisible to listAgents() — validating against the global set alone made
   * every Load(team=...) fail with "lead/member agent not found" for teams
   * whose agents live under the team directory.
   */
  private[tools] def teamValidationNames(team: TeamDef): IO[Set[String]] =
    for
      global <- EntityLoader.listAgents()
      local <- EntityLoader.listTeamAgents(team.name)
    yield global.keySet ++ local.keySet

  private def validateAndMount(team: TeamDef, ctx: ToolContext): IO[Either[ToolError, String]] =
    for
      agentNames <- teamValidationNames(team)
      errors = EntityLoader.validateTeam(team, agentNames)
      result <-
        if errors.nonEmpty then
          IO.pure(
            Left(
              ToolError(
                s"""Team '${team.name}' validation failed:
               |${errors.map("  - " + _).mkString("\n")}
               |
               |Fix the issues in teams/${team.name}/team.json and Load again.""".stripMargin
              )
            )
          )
        else
          for
            treeRef <- FlowTreeRegistry.getOrCreate(ctx)
            _ <- treeRef ! TreeCommand.MountTeam(team, None)
            agentCount = team.members.size + 1
            _ <- logger.info(s"Team '${team.name}' loaded and mounted ($agentCount agents)")
          yield Right(
            s"""Team '${team.name}' loaded and mounted successfully ($agentCount agents).
               |Lead: ${team.lead}
               |Members: ${team.members.mkString(", ")}
               |Trigger by Mailing the lead: Mail("${team.lead}", "your task")""".stripMargin.trim
          )
    yield result

  // ============================================================
  // Flow: validate only (mounted as part of team)
  // ============================================================

  private def loadFlow(name: String): IO[Either[ToolError, String]] =
    val jsonPath = PathUtil.dataRoot / "flows" / s"$name.json"
    for
      fileExists <- IO.blocking(os.exists(jsonPath))
      result <-
        if !fileExists then IO.pure(Left(ToolError(s"Flow file not found: flows/$name.json")))
        else
          IO.blocking(os.read(jsonPath)).flatMap { raw =>
            jsonParse(raw).flatMap(_.as[FlowDagDef]) match
              case Right(flowDef) => validateFlowDag(flowDef)
              case Left(parseErr) =>
                IO.pure(
                  Left(
                    ToolError(
                      s"""JSON parse error in flows/$name.json:
                     |$parseErr
                     |
                     |Common issues:
                     |- Missing required fields: name, description, entry, nodes
                     |- Node format: each node needs agent, input, onComplete
                     |- onComplete can be a string (node ID), "$$return", or a switch object""".stripMargin
                    )
                  )
                )
          }
    yield result

    end for

  end loadFlow

  private def validateFlowDag(flow: FlowDagDef): IO[Either[ToolError, String]] =
    for
      agents <- EntityLoader.listAgents()
      flowAgents <- EntityLoader.listFlowAgentNames(flow.name)
      // #424: compile the DAG with the shared compiler frontend (E-0xx..E-3xx
      // + agent existence). Predefined flows resolve node agents via
      // loadFlowAgent = flow-local agents first, global fallback — the
      // compiler's agentNames must cover both.
      agentNames = agents.keySet ++ flowAgents
      compileResult = nebflow.core.entity.FlowDagCompiler.validate(flow, agentNames)
    yield
      if compileResult.rejected then
        Left(
          ToolError(
            s"""Flow '${flow.name}' validation failed:
             |${compileResult.renderAll.split("\n").map("  - " + _).mkString("\n")}
             |
             |Fix the issues in flows/${flow.name}.json and Load again.""".stripMargin
          )
        )
      else
        Right(
          s"""Flow '${flow.name}' is valid.
             |Entry: ${flow.entry}
             |Nodes: ${flow.nodes.keys.toList.sorted.mkString(", ")}
             |Max loop: ${flow.maxLoop}""".stripMargin
        )

end LoadTool
