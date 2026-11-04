package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.entity.{EntityLoader, FlowDagDef}
import nebflow.shared.PathUtil

/**
 * Flow-definition validator.
 *
 * 团队功能删除批（team-feature-deletion-20261104）阶段 2：本工具原有的
 * `type: "team"` 分支（读 teams/<name>/team.json → 校验 → `FlowTreeRegistry
 * .getOrCreate` → `TreeCommand.MountTeam`）已整体摘除 —— 那是「团队挂载」的
 * 最后一条生产腿，随挂载入口一起下线。判定与依据见
 * reports-evidence/team-feature-deletion/stage2/README.md §A（工具本身保留：
 * `type: "flow"` 是**非团队**用途 —— 只校验 DAG 定义，不挂载任何东西）。
 */
object LoadTool extends Tool:
  val name = "Load"

  // `def` + s-interpolation（home 硬编码 → 运行时动态化批 2026-09-11）：定义文件
  // 路径走 PathUtil.dataRootRenderValue —— 默认 home ⇒ `~/.nebflow/...`（与旧字面
  // 逐字节一致），隔离实例 ⇒ 该实例 home 绝对路径。
  def description =
    s"""Load a Flow definition from disk and validate it.

Actions:
- type "flow": Read ${PathUtil.dataRootRenderValue}/flows/<name>.json, validate DAG structure + agent references.
  Reports syntax errors, missing agents, dangling routes, etc.

Use this after writing or editing flow.json files. Always loads the latest from disk — no separate create/update distinction."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "type" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> List("flow").map(_.asJson).asJson,
          "description" -> "\"flow\" to validate a flow definition".asJson
        ),
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Flow name (matches the JSON filename without .json)".asJson
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
        case "flow" => loadFlow(name)
        case _ => IO.pure(Left(ToolError(s"Unknown type '$entityType'. Use 'flow'.")))

  // ============================================================
  // Flow: validate only
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
