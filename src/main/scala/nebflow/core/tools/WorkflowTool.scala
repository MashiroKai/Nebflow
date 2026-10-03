package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*

/**
 * WorkflowTool —— 步级 DAG 并行执行（builtin-merge 批 2026-10-03；取代旧
 * dispatcher 的 flow 编排面：agent 写好步骤、引擎直接并发运行，零 worktree、
 * 零 FlowMap、零中间节点状态机）。
 *
 * 形态（zcode workflow 同型）：`steps = [{id, task, deps?}]` ——
 *   - 校验：id 唯一、deps 引用存在、Kahn 判环（非法 ⇒ 显式拒绝，零部分执行）；
 *   - 运行：每轮把「依赖已全完成」的步骤**并发**起子会话（基础工具执行面，
 *     [[BlockingSubagent]] 同一阻塞桥），上游结果按 `=== step <id> ===` 头
 *     拼进下游任务文本；
 *   - 汇总：全部步骤结果按拓扑序聚合为工具结果返回调用 agent。
 *
 * 规模上限（防误用）：12 步；单步 15 分钟硬顶（同 [[BlockingSubagent.Timeout]]）。
 */
object WorkflowTool extends Tool:
  val name = "Workflow"

  private val MaxSteps = 12

  val description =
    """Decompose a LARGE task into a DAG of steps and run them: independent steps run CONCURRENTLY, each in a fresh sub-agent session with the base tools (Read/Write/Edit/Glob/Grep/Bash/AskUserQuestion), and every step receives its upstream results under `=== step <id> ===` headers. The aggregated results come back as THIS tool's result.

**When to use:** genuinely parallelizable multi-part work (survey A + implement B + test C). A short sequential task is faster without it; a task needing your own judgment between phases is better done by you directly.

**Parameters:**
- `steps` (required): array of {id, task, deps} — id: short unique token; task: self-contained brief for that step (absolute paths; what done looks like); deps: ids that must finish first (omit for entry steps).
- `description` (optional): short UI label.

**Limits:** at most 12 steps; each step has a 15-minute cap; steps CANNOT delegate further (depth). Failed step => the tool result marks it FAILED and its downstream steps are skipped (completed steps' results are still returned).

**Merge discipline:** steps must NOT arrange merges of any produced branches — merging is engine-queued; report readiness instead."""

  def inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "steps" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "description" -> "DAG steps: [{\"id\": \"survey\", \"task\": \"...\", \"deps\": [\"...\"]}]. deps omitted = entry step.".asJson,
          "items" -> io.circe.Json.obj(
            "type" -> "object".asJson,
            "properties" -> io.circe.Json.obj(
              "id" -> io.circe.Json.obj("type" -> "string".asJson),
              "task" -> io.circe.Json.obj("type" -> "string".asJson),
              "deps" -> io.circe.Json.obj("type" -> "array".asJson, "items" -> io.circe.Json.obj("type" -> "string".asJson))
            ),
            "required" -> io.circe.Json.arr("id".asJson, "task".asJson)
          )
        ),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short label for the workflow (session naming).".asJson
        )
      ),
      "required" -> io.circe.Json.arr("steps".asJson)
    )
  )

  final case class Step(id: String, task: String, deps: List[String])

  /** 形态解析 + 结构校验（纯函数，spec 直驱）：非空、id 唯一、deps 存在、有环检测。 */
  private[tools] def parseSteps(input: JsonObject): Either[ToolError, List[Step]] =
    val parsed: Either[ToolError, List[Step]] = input("steps").flatMap(_.asArray) match
      case None => Left(ToolError("Missing required parameter: steps (an array of {id, task, deps})."))
      case Some(arr) =>
        arr.foldLeft[Either[ToolError, List[Step]]](Right(Nil)) { (acc, item) =>
          for
            steps <- acc
            obj <- item.asObject.toRight(ToolError("each step must be an object {id, task, deps}."))
            id <- obj("id").flatMap(_.asString).toRight(ToolError("each step needs an \"id\" string."))
            task <- obj("task").flatMap(_.asString).toRight(ToolError(s"step '$id' needs a \"task\" brief."))
            deps = obj("deps").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
          yield steps :+ Step(id.trim, task, deps.map(_.trim).filter(_.nonEmpty))
        }
    parsed.flatMap { steps =>
      if steps.isEmpty then Left(ToolError("steps is empty — nothing to run."))
      else if steps.size > MaxSteps then Left(ToolError(s"Too many steps (${steps.size}) — the cap is $MaxSteps. Split into multiple Workflow calls."))
      else
        val ids = steps.map(_.id)
        val dup = ids.diff(ids.distinct)
        if dup.nonEmpty then Left(ToolError(s"Duplicate step id(s): ${dup.mkString(", ")}."))
        else
          val known = ids.toSet
          val unknownDeps = steps.flatMap(_.deps.filterNot(known.contains)).distinct
          if unknownDeps.nonEmpty then Left(ToolError(s"deps reference unknown step id(s): ${unknownDeps.mkString(", ")}."))
          else if hasCycle(steps) then Left(ToolError("Cycle detected in deps — the workflow must be a DAG."))
          else Right(steps)
    }

  /** Kahn 判环（纯函数）。 */
  private[tools] def hasCycle(steps: List[Step]): Boolean =
    val depsOf = steps.map(s => s.id -> s.deps.toSet).toMap
    @scala.annotation.tailrec
    def go(remaining: Set[String], resolved: Set[String]): Boolean =
      if remaining.isEmpty then false
      else
        val ready = remaining.filter(id => depsOf(id).forall(resolved.contains))
        if ready.isEmpty then true // 有剩余但无可入度者 ⇒ 环
        else go(remaining -- ready, resolved ++ ready)
    go(steps.map(_.id).toSet, Set.empty)

  /** 拓扑轮次（调度核，纯函数）：每轮 = 依赖已完成的全部步骤。 */
  private[tools] def rounds(steps: List[Step]): List[List[Step]] =
    val byId = steps.map(s => s.id -> s).toMap
    @scala.annotation.tailrec
    def go(remaining: List[Step], done: Set[String], acc: List[List[Step]]): List[List[Step]] =
      if remaining.isEmpty then acc.reverse
      else
        val ready = remaining.filter(s => s.deps.forall(done.contains))
        if ready.isEmpty then acc.reverse // 防御：parseSteps 已判环
        else go(remaining.filterNot(s => ready.contains(s)), done ++ ready.map(_.id), ready :: acc)
    go(steps, Set.empty, Nil)

  def summarize(input: JsonObject): String =
    val n = input("steps").flatMap(_.asArray).map(_.size).getOrElse(0)
    val label = input("description").flatMap(_.asString).getOrElse("")
    s"Workflow(${n} steps${if label.nonEmpty then s": $label" else ""})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    parseSteps(input) match
      case Left(err) => IO.pure(Left(err))
      case Right(steps) =>
        val label = input("description").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).getOrElse("workflow")
        // 结果桶：stepId -> 文本（成功）或 Left(失败原因)。parseSteps 已判环，
        // rounds 覆盖全部步骤——桶的外层不再需要 Either。
        rounds(steps).foldLeftM(Map.empty[String, Either[String, String]]) { (results, round) =>
          round.traverse { step =>
            val failedDeps = step.deps.filter(d => results.get(d).exists(_.isLeft))
            if failedDeps.nonEmpty then
              IO.pure(step.id -> Left(s"skipped — upstream failed: ${failedDeps.mkString(", ")}"))
            else
              val upstreamBlock = step.deps.flatMap { d =>
                results.get(d).collect { case Right(text) => s"=== step $d ===\n$text" }
              }
              val prompt = (if upstreamBlock.nonEmpty then upstreamBlock.mkString("\n\n") + "\n\n" else "") + step.task
              // 步骤子会话跑执行面（基础工具），不是只读侦察面——步骤要写要执行。
              BlockingSubagent.run(prompt, s"$label/${step.id}", ctx, defName = nebflow.core.entity.BuiltinAgents.ExecutorName).map {
                case Right(text) => step.id -> Right(text)
                case Left(err)   => step.id -> Left(err.message)
              }
          }.map(stepResults => results ++ stepResults)
        }.map { results =>
          val body = steps.map { s =>
            results.get(s.id) match
              case Some(Right(text)) => s"=== step ${s.id} ===\n$text"
              case Some(Left(err))   => s"=== step ${s.id} (FAILED) ===\n$err"
              case None              => s"=== step ${s.id} (SKIPPED) ==="
          }.mkString("\n\n")
          val failed = steps.count(s => results.get(s.id).exists(_.isLeft))
          val head = if failed > 0 then s"Workflow finished with $failed failed/skipped step(s).\n\n" else "Workflow finished.\n\n"
          Right(head + body)
        }
