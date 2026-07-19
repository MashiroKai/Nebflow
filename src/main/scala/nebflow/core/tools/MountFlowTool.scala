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
    """管理 pipeline flow 的挂载和触发。

Pipeline 是一个持久的流水线：mount 一次后一直存在，可以反复用不同的 input 触发执行。

## 三种操作

### 1. 挂载 pipeline（默认）
从 ~/.nebflow/flows/<name>.yaml 加载定义，创建一个持久 pipeline 实例。
mount 后 pipeline 处于 idle 状态，需要用 retrigger 触发才会执行。

示例 — 挂载名为 "code-review" 的 flow：
  {"source": "code-review"}

也可以用 inline YAML 直接定义：
  {"inline": "name: my-flow\\nbranch:\\n  type: pipeline\\n  steps:\\n    - id: analyze\\n      agent: Explorer\\n      prompt: \\"分析代码\\"\\n  verify:\\n    agent: Explorer\\n    prompt: \\"检查结果\\""}

### 2. 触发已挂载的 pipeline（retrigger=true）
给已挂载的 pipeline 发送输入，开始执行。执行完成后 pipeline 回到 idle，可以再次触发。

示例 — 触发 code-review pipeline，传入要 review 的文件路径：
  {"source": "code-review", "retrigger": true, "input": "src/auth/Login.scala"}

### 3. 卸载 pipeline（unmount=true）
删除 pipeline 实例。

示例：
  {"source": "code-review", "unmount": true}

## 参数说明
- source: flow 定义名称（从 ~/.nebflow/flows/<name>.yaml 加载）或已挂载的实例名
- inline: 内联 YAML 定义（与 source 二选一）
- input: 触发时传入的输入文本，会替换 step prompt 中的 ${input}
- retrigger: true=触发已挂载的 pipeline
- unmount: true=卸载 pipeline
- instanceName: 覆盖实例名（多个相同 flow 实例时用）

## YAML 格式
定义文件放在 ~/.nebflow/flows/<name>.yaml，格式：
  name: <flow名称>
  branch:
    type: pipeline
    steps:
      - id: <步骤ID>
        agent: <agent名称>
        prompt: <提示词，可用 ${input} 和 ${上游步骤ID}>
        dependsOn: [上游步骤ID]
        retry: 2
        timeoutSeconds: 1800
    verify:
      agent: <验证agent>
      prompt: <验证提示词>
    loop:
      fix:
        agent: <修复agent>
        prompt: <修复提示词>
      maxIterations: 3
    maxConcurrency: 5
"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "source" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "flow 定义名称（从 ~/.nebflow/flows/<name>.yaml 加载）或已挂载的 pipeline 实例名".asJson
        ),
        "inline" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "内联 YAML 定义（与 source 二选一）".asJson
        ),
        "input" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "触发时传入的输入文本，会替换 step prompt 中的 ${input}。仅在 retrigger=true 时有效".asJson
        ),
        "instanceName" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "覆盖实例名（多实例场景）".asJson
        ),
        "unmount" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "true=卸载 pipeline".asJson
        ),
        "retrigger" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "true=触发已挂载的 pipeline，配合 input 参数传入任务".asJson
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
        s"""Pipeline '${defn.name}' mounted successfully. It is now idle and persistent.
           |To execute it, call this tool again with retrigger=true and provide an input:
           |  {"source": "${defn.name}", "retrigger": true, "input": "<your task input>"}
           |The pipeline will run its steps and report results via system messages.""".stripMargin
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
          yield Right(s"Pipeline '$name' triggered with input (${triggerInput.length} chars). You will receive progress updates and a completion message.")
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
