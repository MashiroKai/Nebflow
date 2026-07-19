package nebflow.core.tools

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.{AgentCommand, SharedResources}
import nebflow.core.NebflowLogger
import nebflow.core.flow.*

/**
 * MountFlow — mount a flow definition onto the session's FlowTree.
 *
 * Replaces ExecuteFlow. The flow definition comes from either:
 * - A named YAML file in ~/.nebflow/flows/<name>.yaml
 * - An inline YAML string
 *
 * Supports mount, unmount, and retrigger operations.
 */
object MountFlowTool extends Tool:
  private val logger = NebflowLogger(getClass)

  val name = "MountFlow"

  val description =
    """挂载一个 flow 定义到当前会话的 Flow 树。

目前支持 pipeline（管线型 DAG+verify+loop）枝条类型。

source 指定名称（从 ~/.nebflow/flows/ 加载），或 inline 提供 YAML 定义。
unmount=true 卸载指定枝条。
retrigger=true 带上 input 参数重新触发已挂载的 pipeline 执行新任务。

Pipeline 的 step prompt 中的 ${stepId} 会被上游 step 的输出替换，${input} 会被 trigger 时的输入替换。
"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "source" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "flow 定义名称（从 ~/.nebflow/flows/<name>.yaml 加载）或文件路径".asJson
        ),
        "inline" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "内联 YAML 定义（与 source 二选一）".asJson
        ),
        "instanceName" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "覆盖实例名（多实例场景）".asJson
        ),
        "unmount" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "true=卸载指定枝条".asJson
        ),
        "retrigger" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "true=重新执行已完成的 pipeline".asJson
        )
      )
    )
  )

  def summarize(input: JsonObject): String =
    val unmount = input("unmount").flatMap(_.asBoolean).getOrElse(false)
    val retrigger = input("retrigger").flatMap(_.asBoolean).getOrElse(false)
    val name = input("source")
      .flatMap(_.asString)
      .orElse(input("inline").flatMap(_.asString).map(_.take(30)))
      .getOrElse("?")
    if unmount then s"UnmountFlow($name)"
    else if retrigger then s"RetriggerFlow($name)"
    else s"MountFlow($name)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val unmount = input("unmount").flatMap(_.asBoolean).getOrElse(false)
    val retrigger = input("retrigger").flatMap(_.asBoolean).getOrElse(false)
    val instanceName = input("instanceName").flatMap(_.asString)

    if unmount then doUnmount(input, ctx)
    else if retrigger then doRetrigger(input, ctx)
    else doMount(input, ctx, instanceName)

  // ============================================================
  // Mount
  // ============================================================

  private def doMount(
    input: JsonObject,
    ctx: ToolContext,
    instanceName: Option[String]
  ): IO[Either[ToolError, String]] =
    val sourceOpt = input("source").flatMap(_.asString)
    val inlineOpt = input("inline").flatMap(_.asString)

    (sourceOpt, inlineOpt) match
      case (None, None) =>
        IO.pure(Left(ToolError("Must provide either 'source' or 'inline'")))

      case (Some(_), Some(_)) =>
        IO.pure(Left(ToolError("Cannot specify both 'source' and 'inline'")))

      case (Some(source), None) =>
        FlowDefLoader.load(source).flatMap {
          case None => IO.pure(Left(ToolError(s"Flow definition '$source' not found in ~/.nebflow/flows/")))
          case Some(defn) => sendMount(defn, instanceName, ctx)
        }

      case (None, Some(yaml)) =>
        FlowDefLoader.parseInline(yaml).flatMap {
          case Left(err) => IO.pure(Left(ToolError(s"Invalid YAML: $err")))
          case Right(defn) => sendMount(defn, instanceName, ctx)
        }

    end match

  end doMount

  private def sendMount(
    defn: FlowDef,
    instanceName: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    getOrCreateTreeActor(ctx).flatMap { treeRef =>
      for
        _ <- treeRef ! TreeCommand.MountBranch(defn, instanceName, None)
        _ <- logger.info(s"Sent MountBranch for '${defn.name}' (${defn.branchType.typeName})")
      yield Right(
        s"""Flow '${defn.name}' (${defn.branchType.typeName}) mounted.
           |You will receive updates via system messages.""".stripMargin
      )
    }

  // ============================================================
  // Unmount
  // ============================================================

  private def doUnmount(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val target = input("source")
      .flatMap(_.asString)
      .orElse(input("instanceName").flatMap(_.asString))
    target match
      case None =>
        IO.pure(Left(ToolError("Must specify branch name to unmount (via 'source' or 'instanceName')")))
      case Some(name) =>
        getOrCreateTreeActor(ctx).flatMap { treeRef =>
          for _ <- treeRef ! TreeCommand.UnmountBranch(name)
          yield Right(s"Branch '$name' unmount requested.")
        }

  // ============================================================
  // Retrigger
  // ============================================================

  private def doRetrigger(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val target = input("source")
      .flatMap(_.asString)
      .orElse(input("instanceName").flatMap(_.asString))
    val triggerInput = input("input").flatMap(_.asString).getOrElse("")
    target match
      case None =>
        IO.pure(Left(ToolError("Must specify pipeline name to retrigger")))
      case Some(name) =>
        getOrCreateTreeActor(ctx).flatMap { treeRef =>
          for _ <- treeRef ! TreeCommand.TriggerPipeline(name, triggerInput, None)
          yield Right(s"Pipeline '$name' triggered with input (${triggerInput.length} chars).")
        }

  // ============================================================
  // Find or create FlowTreeActor
  // ============================================================

  private def getOrCreateTreeActor(ctx: ToolContext): IO[ActorRef[TreeCommand]] =
    val sessionId = ctx.sessionId.getOrElse("default")

    FlowTreeRegistry.get(sessionId).flatMap {
      case Some(ref) => IO.pure(ref)
      case None =>
        // Create new FlowTreeActor
        (ctx.actorSystem, ctx.sharedResources, ctx.agentActorRef) match
          case (Some(system), Some(resources), Some(parentRef)) =>
            val safetyModeIO = ctx.sessionStore match
              case Some(store) => store.getSafetyMode(sessionId)
              case None => IO.pure("confirm-edits")

            safetyModeIO.flatMap { safetyMode =>
              val config = FlowTreeActor.TreeConfig(
                parentAgentRef = parentRef,
                wsSend = ctx.wsSend,
                sessionId = ctx.sessionId,
                resources = resources,
                projectRoot = ctx.projectRoot,
                safetyMode = safetyMode
              )
              for
                ref <- system.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")
                _ <- FlowTreeRegistry.register(sessionId, ref)
                _ <- logger.info(s"Created FlowTreeActor for session $sessionId")
              yield ref
            }
          case _ =>
            IO.raiseError(
              new RuntimeException("MountFlow requires ActorSystem, SharedResources, and agentActorRef")
            )
    }
  end getOrCreateTreeActor

end MountFlowTool
