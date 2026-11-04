package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * 后台任务的**唯一等待原语**（bash overhaul T1，作者令 2026-11-04「用业界成熟做法
 * 完整实现，一步到位」）。
 *
 * 为什么必须有它（自查件 §0-4/§5.2 的结论）：本批之前**没有任何等待原语**——提示词
 * 面禁止轮询（`PromptCatalog.Shared.NoSleepPollRule`），工具回执却又主动邀请用
 * `background_job_id` 反复查进度，于是「合法等待」的姿势为零，轮询
 * 是被逼出来的唯一解（缺陷 D3，事故形态 = 同参 `background_job_id` 查询第 10 拍
 * 被 LoopGuard 判死，而任务 11 秒后就有结果）。业界依据（DeepSeek Harness
 * `job_output(wait: true, timeout_ms)`）：**有界等待**——等超时不杀任务，直接回
 * 「仍在跑」，由完成通知接管。
 *
 * 语义契约（钉死）：
 *  - **到全部终态或期限到**（二者先到者），**绝不杀 job、绝不 cancel**；
 *  - 阻塞上限**恒等于**前台等待预算 `ctx.bashConfig.foregroundWaitBudgetMs`
 *    （deadline 缺省 = 该值，且**是被该值钳住的上限**）——**同源，禁第二个上限**；
 *  - 期限到仍在跑 ⇒ 已完成者照返 + `[still running] Job ID: X — Ns running`
 *    + 一行 `[deadline reached after Nms] …`（**不含任何「再来查/随时可查」的邀请**）；
 *  - 终态交付即**计去重**（`BgTaskRegistry.markAwaited`，单点由 T2 定义）：同一
 *    jobId 的终态结果不会因完成通知再来一遍。
 *
 * 单任务返回面（逐字口径，`BgTaskOutputStore`/`BgNotifyPayload` 同源）：
 *  - 终态成功：`[completed] Job ID: X (exit N · <dur>)` + 输出尾段
 *    （`BgNotifyPayload.tailWithMarker`，缺省 20 行，截断带标注行）；
 *  - 终态失败：`[failed] Job ID: X` + 错误尾段；
 *  - 仍未来得及终态且**在册**：`[still running] Job ID: X — Ns running`；
 *  - 全无其行：`[not found] Job ID: X`。
 *
 * 面纪律：成功后一律 `Right(...)`（`isError=false`）——「期限到」不是失败，它是
 * 本原语的定义行为（把它折成错误会让模型把正常等待当成故障重试）。
 */
object BgWaitTool extends Tool:
  val name = "BgWait"

  val description = """Wait — BOUNDED — until the listed background jobs reach a terminal state, or the deadline is reached.

## When to Use
- You are genuinely blocked INSIDE the turn on a job you started with Bash `run_in_background: true` (or one you were told to wait for): call BgWait. One call with a real deadline replaces any sequence of status checks on `background_job_id`.
- Several jobs at once: pass all their ids — the call returns as soon as EVERY listed job is terminal, or when the deadline expires (whichever comes first).

## Semantics (bounded, never destructive)
- The call NEVER kills and NEVER cancels a job. A job still running at the deadline keeps running; you get a `[still running]` block for it and the completion notification takes over.
- `deadline` defaults to — and is CAPPED AT — the same foreground wait budget as Bash (`bashForegroundWaitBudgetMs`). There is exactly ONE ceiling: a wait can never outlive the budget, and BgWait introduces no second one.
- Terminal results are delivered once: the delivery is recorded as awaited, so the completion notification will not report the same job a second time.
- Ending your turn is always at least as good as waiting: if you do not need the result this second, end the turn and the completion notification wakes you.

## Parameters
- **jobIds** (required, array of string, at least one): the background job ids to wait on, e.g. ["ab12cd34"].
- **deadline** (optional, number, milliseconds): how long you are willing to block. Omitted = the foreground wait budget (which is also the hard cap).

## Returns
Per job, in the order given: `[completed] Job ID: X (exit N · <duration>)` + the output tail (last 20 lines, with the standard truncation marker), `[failed] Job ID: X` + the error, `[still running] Job ID: X — Ns running` when the deadline hit first, or `[not found] Job ID: X` when no such job is known. A deadline hit appends one line stating that you will be notified on completion."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "jobIds" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Background job id(s) to wait on — at least one. The call returns when every listed job is terminal or the deadline is reached.".asJson
        ),
        "deadline" -> Json.obj(
          "type" -> "number".asJson,
          "description" -> "Optional wait budget in milliseconds. Omitted = the foreground wait budget (bashForegroundWaitBudgetMs); a larger value is clamped to that same budget — one single ceiling, never a second one. A job still running at the deadline is NOT killed.".asJson
        )
      ),
      "required" -> Json.arr("jobIds".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val ids = input("jobIds").flatMap(_.asArray).map(_.flatMap(_.asString)).getOrElse(Nil)
    s"BgWait(${ids.mkString(", ")})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.contains("[still running]") then "Still running"
    else if result.contains("[failed]") then "Failed"
    else if result.contains("[not found]") then "Not found"
    else "Completed"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // 上限单源：前台等待预算（作者裁定「主 agent 一点不能阻塞」的同一个值）。
    // 这里**只允许**做 min(入参, 预算) —— 任何第三个数都是第二个上限，禁止。
    val budgetMs = math.max(1L, ctx.bashConfig.foregroundWaitBudgetMs)
    val waitMs = clampDeadline(input("deadline"), budgetMs)
    parseJobIds(input("jobIds")) match
      case Left(err) => IO.pure(Left(ToolError(err)))
      case Right(ids) =>
        for
          // 耗时段读数：终态回调会注销注册行，故等之前先抓 startedAtMs（抓不到再回落
          // 输出留存区的 startedAtMs/finishedAtMs——同一口径，无第二事实源）。
          started <- ids.traverse(id => BgTaskRegistry.find(id).map(row => id -> row.map(_.startedAtMs))).map(_.toMap)
          // 会话键与 BashTool 完全同口径（BashTool.doCall 的 `val sessionId =
          // ctx.sessionId.getOrElse("default")`）：后台任务登记在**那个**会话的任务图里，
          // 等待必须按同一把钥匙找会话。
          // peekSession = 非创建式读面（等待是观察，绝不因等待而造会话）。
          shell <- ShellSession.peekSession(sessionKey(ctx))
          terminal <-
            shell match
              // 无会话（会话已销毁/纯 REST 上下文）⇒ 没有任务图可等，只做注册表面读取；
              // 常态（有会话）下与会话内任务同源，见 ShellSession.waitBackgroundResults。
              case Some(sh) => sh.waitBackgroundResults(ids, waitMs)
              case None => IO.pure(Map.empty[String, Either[Throwable, ProcessResult]])
          blocks <- ids.traverse(id => blockFor(id, terminal.get(id), started.getOrElse(id, None), shell))
          pending = ids.filterNot(terminal.contains)
          footer = if pending.nonEmpty then List(deadlineFooter(waitMs)) else Nil
          // 计去重（本批 T2 单点）：终态结果已交付给 agent ⇒ 记账，完成通知不再重复交付。
          _ <- ids.traverse_(id => IO.whenA(terminal.contains(id))(BgTaskRegistry.markAwaited(id).attempt.void))
        yield Right((blocks ++ footer).mkString("\n"))

  // ── 入参 ─────────────────────────────────────────────────────────────

  /**
   * `jobIds` 解析：array of string（≥1）。也宽容单个字符串（模型手滑），空 ⇒ 可行动错误
   * （错误文案自带正确形态，别让模型猜）。
   */
  private def parseJobIds(v: Option[Json]): Either[String, List[String]] =
    v match
      case None | Some(Json.Null) =>
        Left("[BgWait requires jobIds] pass the background job id(s) you are blocked on, e.g. jobIds: [\"ab12cd34\"]")
      case Some(json) =>
        val raw = json.asArray.map(_.flatMap(_.asString)).orElse(json.asString.map(List(_))).getOrElse(Nil)
        val ids = raw.map(_.trim).filter(_.nonEmpty).distinct.toList
        if ids.isEmpty then
          Left("[BgWait requires at least one job id] jobIds must be a non-empty array of job ids, e.g. [\"ab12cd34\"]")
        else Right(ids)

  /**
   * 期限解析：缺省 = 预算；显式值 = `min(入参, 预算)`；下界 1ms。
   * 🔴 这里是全工具**唯一**的上限点（同源 `ctx.bashConfig.foregroundWaitBudgetMs`）。
   */
  private def clampDeadline(v: Option[Json], budgetMs: Long): Long =
    val requested = v.flatMap(_.asNumber).map(_.toDouble)
    requested match
      case None => budgetMs
      case Some(ms) => math.max(1L, math.min(budgetMs, ms.toLong))

  /** 会话键（与 BashTool 同口径；见 call 内注释）。 */
  private def sessionKey(ctx: ToolContext): String =
    ctx.sessionId.map(_.trim).filter(_.nonEmpty).getOrElse("default")

  // ── 单任务返回面 ─────────────────────────────────────────────────────

  private def blockFor(
    jobId: String,
    terminal: Option[Either[Throwable, ProcessResult]],
    startedAtMs: Option[Long],
    shell: Option[ShellSession]
  ): IO[String] =
    terminal match
      case Some(Right(pr)) =>
        durationOf(jobId, startedAtMs).map { dur =>
          s"[completed] Job ID: $jobId ${exitTag(pr.exitCode, dur)}\n${tailOf(pr.stdout, pr.stderr, jobId)}"
        }
      case Some(Left(e)) =>
        durationOf(jobId, startedAtMs).map { dur =>
          val msg = Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
          val durTag = dur.fold("")(d => s" (${BgNotifyPayload.formatDuration(d)})")
          s"[failed] Job ID: $jobId$durTag\n${tailOf(msg, "", jobId)}"
        }
      case None => pendingFace(jobId, shell)

  /**
   * 未终态面：注册行在 ⇒ 仍在跑（耗时段用注册时刻）；行已消失但会话任务图里还有
   * 终态腿 ⇒ 同样报仍在跑（健康面读数）；再回落输出留存区的终态记录（期限到与
   * 终态之间几毫秒的竞态——照终态报，别误报仍在跑）；三者皆无 = 未知 jobId。
   *
   * 三态判据同源：注册行 = 在册即未终态（终态回调注销）；任务图 = getBackgroundJobHealth；
   * 留存区 = BgTaskOutputStore.read 的 status。禁第四种事实源。
   */
  private def pendingFace(jobId: String, shell: Option[ShellSession]): IO[String] =
    BgTaskRegistry.find(jobId).flatMap {
      case Some(row) =>
        val runningSec = math.max(0L, System.currentTimeMillis() - row.startedAtMs) / 1000L
        IO.pure(s"[still running] Job ID: $jobId — ${runningSec}s running")
      case None =>
        val healthIo = shell.fold(IO.pure(Option.empty[BackgroundJobHealth]))(_.getBackgroundJobHealth(jobId))
        healthIo.flatMap {
          case Some(h) =>
            IO.pure(s"[still running] Job ID: $jobId — ${math.max(0L, h.runningMs) / 1000L}s running")
          case None =>
            BgTaskOutputStore.read(jobId, 0L).map {
              case Some(o) if o.status == "completed" =>
                s"[completed] Job ID: $jobId (exit ${o.exitCode.getOrElse(0)})\n${tailOf(o.output, "", jobId)}"
              case Some(o) if o.status != "running" =>
                val hint = o.errorHint.getOrElse(s"job $jobId ended with status '${o.status}'")
                s"[failed] Job ID: $jobId\n${tailOf(hint, "", jobId)}"
              case _ => s"[not found] Job ID: $jobId"
            }
        }
    }

  private def durationOf(jobId: String, startedAtMs: Option[Long]): IO[Option[Long]] =
    startedAtMs match
      case Some(st) => IO.pure(Some(math.max(0L, System.currentTimeMillis() - st)))
      case None =>
        // 注册行已被终态回调注销：留存区 meta 的 startedAtMs/finishedAtMs 是同一读数。
        BgTaskOutputStore.read(jobId, 0L).map(_.flatMap { o =>
          for st <- o.startedAtMs; fin <- o.finishedAtMs yield math.max(0L, fin - st)
        })

  /** `(exit N · <dur>)` 标签段（dur 缺席 = 只报退出码；退出码恒在，同 BgNotifyPayload 口径）。 */
  private def exitTag(exitCode: Int, durationMs: Option[Long]): String =
    durationMs match
      case Some(d) => s"(exit $exitCode · ${BgNotifyPayload.formatDuration(d)})"
      case None => s"(exit $exitCode)"

  /**
   * 输出尾段：**复用** `BgNotifyPayload.tailWithMarker` 的既有口径（缺省 20 行、
   * 截断标注行带 jobId 指回详情卡、零输出 = `[no output]`）——禁第二套截断实现。
   */
  private def tailOf(stdout: String, stderr: String, jobId: String): String =
    val body0 = if stderr.trim.nonEmpty then stdout + s"\n[stderr]:\n$stderr" else stdout
    val (body, _, _) = BgNotifyPayload.tailWithMarker(body0, BgNotifyPayload.tailLines, jobId)
    body

  /**
   * 期限到但仍未终态的唯一一句话（逐字口径）：**无**「再来查/随时可查」类邀请——
   * 只给两条合法出路（结束回合等通知 / 再等一次）。
   */
  private def deadlineFooter(waitMs: Long): String =
    s"[deadline reached after ${waitMs}ms] you will be notified on completion; end your turn or call BgWait again"

end BgWaitTool
