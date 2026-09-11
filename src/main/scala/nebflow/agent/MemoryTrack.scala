package nebflow.agent

import cats.effect.{Deferred, IO}
import nebflow.actor.*
import nebflow.core.tools.{MemoryHistory, MemoryQueue}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.project.{ProjectMemory, ProjectStore}
import nebflow.service.MemoryBudget
import nebflow.shared.{ContentBlock, Message, MessageRole}

import java.util.UUID

import scala.concurrent.duration.*

/**
 * 记忆轨（压缩双轨第二轨，记忆改造批 2026-09-12，IMPL-3 / spec §4 候选① + §5 R3 O-A）。
 *
 * **挂点**（spec R3）：`AgentActor.handleCompactResponse` 的 `ctx.forkTurn(...)` 内、
 * 发 `AgentCommand.CompactionComplete` **之前** join 本轨 ⇒ 装机点（`processing` 的
 * `CompactionComplete(Right)` → `state.withMessages`）天然晚于两轨完成，**零新增
 * 状态位**（复用 `pendingCompaction` 作窗口守卫 + F0–F3 注入屏蔽）。
 *
 * **语义**：起一个 `memory-consolidator` 子会话（工具面 = `KernelFixedTools` 恰七件，
 * [[AgentCore.MemoryConsolidatorName]]），把队列记账条目落到三层记忆文件；本轨
 * **只做编排与护栏**：触发判定、快照、等待、硬超时降级、变更史落盘、信号置位。
 *
 * **触发谓词**（spec R4，纯函数 [[shouldRun]]）：字节 ∨ pending 计数 ∨ 未消费信号
 * 的**或**——纯字节谓词今天恒空（`User.md` 40.7KB < 40KB 软线？实测 40,703B 已过
 * 40,960B 之下——原文口径即「恒空」，故必须叠后两支）。**空队列 + 无信号 + 未超软线
 * ⇒ 本轨完全不发 LLM 请求**（R9 VC3 判据）。
 *
 * **降级（三档同一路径，fail-open）**：失败 / 超时 / hang ⇒ ① 对本轮**仍无 outcome**
 * 的待办逐条写 `outcome(result=rejected|timeout)`（有结局 ≠ 失败；**非静默丢**）；
 * ② 置位 [[MemoryTrackSignal]]（下一次压缩重试）；③ 照常装机（旧记忆）+ warn 日志 +
 * 事件 `memory-track-failed` / `memory-track-timeout`（事件由调用方 AgentActor 依
 * [[Result.status]] 落）。**否决任何无 timeout 的 join**（真 hang 会永久 hold 注入）。
 *
 * **时间阈值（prop 化）**：软 480s（超时**预警**，不降级）/ 硬 600s（`timeoutTo`
 * 降级点）。取这两值的理由：同类记忆审计节点实测 wall = 3.7–6.6 min（222–396s，
 * T4 读数）⇒ 硬顶留 ≈1.5× 余量、软线在实测上限之上——不选「把该轨压成快轨」的理由
 * 是快轨需要砍方法论（R6 已把轮数压到「读队列 + 批量编辑」，等效收益已取，不叠加）。
 * 覆盖：`-Dnebflow.memory.track.{soft,hard}TimeoutMs=<ms>`（`def` 读，测试可换）。
 *
 * **变更史（IMPL-1 必含项）**：起跑线读三处记忆文件（user / agent / 涉及项目的
 * project 层）字节，收尾再读一次并做行级 diff ⇒ 逐文件落一条
 * [[MemoryHistory]] `change` 行（`added`/`removed` 逐行全文）——这是「不读记忆正文
 * 即可重建谁/何时/把哪条改成了什么」的机械锚，也是「直写通道无内建留痕」的补位。
 *
 * **并发**：同一会话天然串行（同一时刻只有一个 `pendingCompaction`）；跨会话并发
 * 未增闸（与 spec §5 R5「并发上限」口径一致），跨进程互斥**本批不做**（P-4 代裁 =
 * 明确接受风险，见 spec §5 R7/§3.6 选项 (ii)）。
 */
object MemoryTrack:

  private val logger = NebflowLogger.forName("nebflow.memory.track")

  /** 记忆整理 agent 定义名（单点 = AgentCore 常量）。 */
  val AgentName: String = AgentCore.MemoryConsolidatorName

  /** 软超时（超时预警，不降级）。 */
  def softTimeoutMs: Long =
    sys.props.get("nebflow.memory.track.softTimeoutMs").flatMap(_.toLongOption).getOrElse(480_000L)

  /** 硬超时（`IO.timeoutTo` 降级点）——**必须有**：无 timeout 的 join 会让
    * `pendingCompaction` 永不清理、注入被守卫永久 hold（spec §4/§5 R3 明文否决）。 */
  def hardTimeoutMs: Long =
    sys.props.get("nebflow.memory.track.hardTimeoutMs").flatMap(_.toLongOption).getOrElse(600_000L)

  /** 整理成果文本入 detail 的上限（报告进日志，不进上下文）。 */
  val ReportMaxChars: Int = 2000

  // ── 触发谓词（纯函数） ─────────────────────────────────────────

  /** 「字节 ∨ pending 计数 ∨ 未消费信号」的或（spec §5 R4）。空队列/未超线/无信号
    * ⇒ false ⇒ 本轨零 LLM 请求（R9 VC3）。 */
  def shouldRun(
    userBytes: Long,
    agentBytes: Long,
    pendingCount: Int,
    unconsumedSignal: Boolean
  ): Boolean =
    userBytes > MemoryBudget.UserSoftBytes ||
      agentBytes > MemoryBudget.AgentSoftBytes ||
      pendingCount > 0 ||
      unconsumedSignal

  /** 三层记忆文件的字节读数（缺失 = 0）。 */
  def memoryBytes(): (Long, Long) =
    def bytesOf(p: os.Path): Long = if os.exists(p) && os.isFile(p) then os.size(p) else 0L
    (bytesOf(nebflow.service.MemoryStore.userMemoryPath), bytesOf(nebflow.service.MemoryStore.agentMemoryPath("Nebula")))

  // ── 结果模型 ───────────────────────────────────────────────────

  enum Status:
    case Skipped, Completed, Failed, Timeout

  /** `detail` = 整理 agent 的最终报告（截断）或失败原因；`pendingAtStart` = 起跑
    * 时待办条数；`outcomesWritten` = 降级路径代写的结局条数；`changed` = 变更史
    * 落下的文件数。 */
  final case class Result(
    status: Status,
    detail: String,
    pendingAtStart: Int,
    outcomesWritten: Int = 0,
    changed: Int = 0
  )

  object Result:
    val Skipped: Result = Result(Status.Skipped, "no trigger (empty queue, under soft lines, no signal)", 0)

  // ── 主入口 ─────────────────────────────────────────────────────

  /** 跑一轮记忆轨。**本方法自身不成败整个压缩**：一切失败都转成 [[Result]]。 */
  def run(
    resources: SharedResources,
    parentSessionId: Option[String],
    parentDepth: Int = 0,
    trigger: String = MemoryQueue.TriggerCompaction
  ): IO[Result] =
    val notesAtStart = MemoryQueue.pendingNotes()
    val signal       = MemoryTrackSignal.peek()
    val (userBytes, agentBytes) = memoryBytes()
    if !shouldRun(userBytes, agentBytes, notesAtStart.size, signal.isDefined) then IO.pure(Result.Skipped)
    else
      for
        files <- memoryFilesOf(notesAtStart)
        before <- readAll(files)
        attempt <- attemptRun(resources, parentSessionId, parentDepth, trigger, notesAtStart)
          .timeoutTo(hardTimeoutMs.millis, IO.pure(Attempt(Status.Timeout, s"hard timeout after ${hardTimeoutMs}ms", "")))
          .handleErrorWith(e => IO.pure(Attempt(Status.Failed, s"${e.getClass.getSimpleName}: ${e.getMessage}", "")))
        _ = MemoryTrackSignal.take() // 本轮已跑：信号消费（无论成败——重试引线由降级路径重新置位）
        result <- finish(files, before, notesAtStart, attempt, trigger)
      yield result

  // ── 内部：快照 / 变更史 ─────────────────────────────────────────

  private type FileTarget = (os.Path, String) // (path, target label)

  /** 本轨可能触及的文件：user / agent 两层 + 待办点名的 project 层（经注册表解析）。 */
  private def memoryFilesOf(notes: Vector[MemoryQueue.Note]): IO[Vector[FileTarget]] =
    val base = Vector(
      nebflow.service.MemoryStore.userMemoryPath -> "user",
      nebflow.service.MemoryStore.agentMemoryPath("Nebula") -> "agent"
    )
    val projects = notes
      .flatMap(n => if n.target.startsWith("project:") then Some(n.target.stripPrefix("project:")) else None)
      .distinct
    projects.foldLeft(IO.pure(base)) { (accIO, name) =>
      for
        acc <- accIO
        pd  <- ProjectStore.load(name)
      yield acc ++ pd.map(d => ProjectMemory.path(d.workspace) -> s"project:$name").toVector
    }

  private def readAll(files: Vector[FileTarget]): IO[Vector[(os.Path, String, String)]] =
    IO.blocking(files.map { (p, t) =>
      val content = try if os.exists(p) && os.isFile(p) then os.read(p) else "" catch case _: Exception => ""
      (p, t, content)
    })

  /** 逐文件行级 diff → 变更史 `change` 行（无变化不落行；失败只 WARN，不失败主流程）。 */
  private def recordChanges(
    before: Vector[(os.Path, String, String)],
    after: Vector[(os.Path, String, String)],
    refs: List[String],
    trigger: String,
    actor: String
  ): IO[Int] =
    IO.blocking {
      val afterMap = after.map((p, _, c) => p.toString -> c).toMap
      var changed = 0
      before.foreach { (path, target, prev) =>
        val now = afterMap.getOrElse(path.toString, "")
        if now != prev then
          val (removed, added) = MemoryHistory.lineDiff(prev, now)
          MemoryHistory
            .appendChange(
              atMs = System.currentTimeMillis(),
              actor = actor,
              path = path.toString,
              target = target,
              trigger = trigger,
              refs = refs,
              added = added,
              removed = removed
            ) match
            case Right(_) => changed += 1
            case Left(e)  => logger.warn(s"[memory-track] change history append failed (${path.last}): $e")
      }
      changed
    }

  // ── 内部：跑 agent 会话 ─────────────────────────────────────────

  private final case class Attempt(status: Status, detail: String, report: String)

  private final case class Handle(
    agentRef: ActorRef[AgentCommand],
    bridgeRef: ActorRef[AgentEvent],
    sessionId: String
  )

  private def attemptRun(
    resources: SharedResources,
    parentSessionId: Option[String],
    parentDepth: Int,
    trigger: String,
    notes: Vector[MemoryQueue.Note]
  ): IO[Attempt] =
    resources.agentLibrary.get(AgentName).flatMap {
      case None =>
        IO.pure(Attempt(
          Status.Failed,
          s"agent definition '$AgentName' not found — expected ${PathUtil.dataRootRenderValue}/agents/$AgentName/{agent.json,system.md} " +
            s"(seeded from src/main/resources/seed/agents/$AgentName/). Nothing was applied; the queue is untouched.",
          ""
        ))
      case Some(defn) =>
        for
          started <- IO.monotonic
          spawned <- spawn(resources, defn, parentSessionId, parentDepth, trigger, notes)
          outcome <- spawned match
            case Left(err) => IO.pure(Attempt(Status.Failed, err, ""))
            case Right((handle, deferred)) =>
              deferred.get
                .map {
                  case Right(messages) => Attempt(Status.Completed, "consolidation run completed", lastAssistant(messages))
                  case Left(err)       => Attempt(Status.Failed, err, "")
                }
                .guarantee(cleanup(resources, handle))
          elapsed <- IO.monotonic
        yield
          val ms = (elapsed - started).toMillis
          if ms > softTimeoutMs && outcome.status == Status.Completed then
            logger.warn(
              s"[memory-track] run took ${ms}ms (> soft ${softTimeoutMs}ms) — near the hard cap ${hardTimeoutMs}ms; consider trimming the queue or the run scope")
          outcome
    }

  private def spawn(
    resources: SharedResources,
    defn: AgentDef,
    parentSessionId: Option[String],
    parentDepth: Int,
    trigger: String,
    notes: Vector[MemoryQueue.Note]
  ): IO[Either[String, (Handle, Deferred[IO, Either[String, List[Message]]])]] =
    val sessionId = s"memconsolidate-${UUID.randomUUID().toString.take(8)}"
    val root = parentSessionId.getOrElse(sessionId)
    for
      workRoot <- IO.blocking(java.nio.file.Files.createTempDirectory("nb-memory-").toString)
      deferred <- Deferred[IO, Either[String, List[Message]]]
      agentRef <- resources.actorSystem.spawn(
        AgentActor(
          agentDef = defn,
          resources = resources,
          wsSend = (_: io.circe.Json) => IO.unit, // 轨内事件不进前端（噪音面）；失败只留 lifecycle 日志
          depth = parentDepth + 1,
          parentRef = None,
          sessionId = Some(sessionId),
          sessionName = Some("memory-consolidation"),
          initialMessages = Nil,
          readTracker = None,
          fileHistory = None,
          contextWindow = resources.contextWindow,
          projectRoot = Some(workRoot),
          rootSessionId = root
        ),
        s"memory-consolidator-$sessionId"
      )
      // 桥 actor：收 AgentEvent → 完成 Deferred → 自停。必须 watch(agentRef)：直接
      // 对 agent 发 Stop（超时清理）时 Terminated 在此完成 deferred(Left)，否则
      // deferred.get 永久挂起（EphemeralAgentRunner 同款教训）。
      bridgeRef <- resources.actorSystem.spawn(
        Behaviors.setup[AgentEvent] { bctx =>
          bctx.watch(agentRef) *> IO.pure(
            new Behavior[AgentEvent]:
              def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
                event match
                  case AgentEvent.Completed(_, messages) =>
                    deferred.complete(Right(messages)).void.as(Behaviors.stopped)
                  case AgentEvent.Failed(_, err) =>
                    deferred.complete(Left(err.message)).void.as(Behaviors.stopped)
                  case AgentEvent.Cancelled(_, reason) =>
                    deferred.complete(Left(s"cancelled: $reason")).void.as(Behaviors.stopped)

              override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
                signal match
                  case SystemSignal.Terminated(_) =>
                    deferred.complete(Left("agent stopped")).void.handleErrorWith(_ => IO.unit).as(Behaviors.stopped)
          )
        },
        s"bridge-memory-$sessionId"
      )
      _ <- resources.agentRegistry.update(
        _ + (sessionId -> AgentRecord(
          sessionId,
          agentRef,
          AgentKind.Ephemeral,
          root,
          parentSessionId = parentSessionId.getOrElse(""),
          startedAt = System.currentTimeMillis(),
          lastActivityMs = System.currentTimeMillis()
        ))
      )
      _ <- (agentRef ! AgentCommand.UserInput(text = brief(workRoot, trigger, notes), replyTo = Some(bridgeRef))).void
    yield Right((Handle(agentRef, bridgeRef, sessionId), deferred))

  /** 轨内简报：自包含（agent 无历史消息）+ 绝对数据根（文件工具只吃绝对路径）。 */
  private def brief(workRoot: String, trigger: String, notes: Vector[MemoryQueue.Note]): String =
    val abs = PathUtil.dataRoot.toString
    val refs = notes.map(_.id).take(20).mkString(", ")
    s"""[记忆整理轨] 触发=$trigger，待办 ${notes.size} 条。
- 数据根（绝对路径）：$abs —— 文件工具只接受绝对路径，直接用这个前缀。
- 队列：$abs/memory/queue.jsonl。待办 = 无 outcome 的 note + result ∈ {rejected, timeout} 的重试项（本轨起跑线的 ref 清单：${if refs.isEmpty then "(none)" else refs}）。
- 步骤与输出契约严格按本会话系统提示词：动笔前快照三处记忆文件 → 逐条执行 → 逐条回写 outcome → 报告结构化计数。
- 只允许改 4 个目标路径（$abs/User.md、$abs/agents/Nebula/memory.md、涉及项目的 <workspace>/.nebflow/memory.md、队列）；别的文件一律不碰；禁 git 写操作。
- 一次性会话工作根：$workRoot（临时目录，用完即弃）。"""
      .stripMargin

  private def cleanup(resources: SharedResources, handle: Handle): IO[Unit] =
    for
      _ <- resources.agentRegistry.update(_ - handle.sessionId)
      _ <- resources.actorSystem.stop(handle.agentRef).handleErrorWith(_ => IO.unit)
      _ <- resources.actorSystem.stop(handle.bridgeRef).handleErrorWith(_ => IO.unit)
      _ <- resources.sessionStore.deleteSession(handle.sessionId).handleErrorWith(_ => IO.unit)
    yield ()

  private def lastAssistant(messages: List[Message]): String =
    messages.reverse.find(_.role == MessageRole.Assistant) match
      case Some(msg) =>
        val text = msg.content match
          case Left(t)       => t
          case Right(blocks) => blocks.collect { case ContentBlock.Text(t) => t }.mkString("\n")
        if text.length > ReportMaxChars then text.take(ReportMaxChars) + " …(truncated)" else text
      case None => "(no report text)"

  // ── 内部：收尾（变更史 + 降级） ─────────────────────────────────

  /** 降级（失败 / 超时同一路径，spec §5 R3 档 1/2/3）——对本轮**仍无 outcome** 的
    * 待办逐条写 `outcome(result=rejected|timeout)`（每条 note 必有结局，**禁静默丢**；
    * 已有结局的条目不动——agent 可能在超时前已回写了部分结局），并置位
    * [[MemoryTrackSignal]]（下一次压缩重试引线，因为「有结局」意味着不再是 pending）。
    * 返回实际写入条数。`private[agent]`：spec 直测面。 */
  private[agent] def degradeOutcomes(isTimeout: Boolean, detail: String): IO[Int] =
    IO.blocking {
      val stillPending = MemoryQueue.pendingNotes().map(_.id).toList
      val result = if isTimeout then MemoryQueue.ResultTimeout else MemoryQueue.ResultRejected
      val written = stillPending.count { ref =>
        MemoryQueue.recordOutcome(ref, result, AgentName, detail, AgentName).isRight
      }
      if stillPending.nonEmpty then
        MemoryTrackSignal.mark(
          s"previous memory-track run ${if isTimeout then "timeout" else "failed"} (${stillPending.size} note(s) not applied)")
      written
    }

  private def finish(
    files: Vector[FileTarget],
    before: Vector[(os.Path, String, String)],
    notes: Vector[MemoryQueue.Note],
    attempt: Attempt,
    trigger: String
  ): IO[Result] =
    val refs = notes.map(_.id).toList
    for
      after <- readAll(files)
      changed <- recordChanges(before, after, refs, trigger, AgentName)
      outcomesWritten <- attempt.status match
        case Status.Completed => IO.pure(0)
        case Status.Failed | Status.Timeout => degradeOutcomes(attempt.status == Status.Timeout, attempt.detail)
        case Status.Skipped => IO.pure(0)
      _ <- IO.pure(
        attempt.status match
          case Status.Completed =>
            logger.info(s"[memory-track] completed: ${notes.size} pending note(s) attempted, $changed file(s) changed")
          case Status.Failed =>
            logger.warn(s"[memory-track] FAILED: ${attempt.detail} — memory falls back to the current files (install proceeds), queue entries keep their outcome, retry armed for the next compaction")
          case Status.Timeout =>
            logger.warn(s"[memory-track] TIMEOUT after ${hardTimeoutMs}ms — memory falls back to the current files (install proceeds), timeout outcomes written, retry armed for the next compaction")
          case Status.Skipped => ()
      )
    yield
      attempt.status match
        case Status.Completed => Result(Status.Completed, attempt.report, notes.size, 0, changed)
        case Status.Failed    => Result(Status.Failed, attempt.detail, notes.size, outcomesWritten, changed)
        case Status.Timeout   => Result(Status.Timeout, attempt.detail, notes.size, outcomesWritten, changed)
        case Status.Skipped   => Result.Skipped

end MemoryTrack
