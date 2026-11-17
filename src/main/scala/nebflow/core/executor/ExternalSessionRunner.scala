package nebflow.core.executor

import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Mutex
import scala.concurrent.duration.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.actor.ActorRef
import nebflow.core.AgentRuntimePort
import nebflow.core.GitWorktrees
import nebflow.core.delegate.ChainStore
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.Message
import nebflow.shared.MessageRole
import nebflow.shared.NebflowLogger
import nebflow.shared.SubAgentTask

import scala.util.Try

/**
 * Runs external CLI sessions with durable task admission, committed message history,
 * execution-scoped cancellation and mailbox-first result delivery. Process handles
 * are ephemeral; task records and session transcripts remain authoritative after exit.
 */
object ExternalSessionRunner:

  private val logger = NebflowLogger.forName("nebflow.executor.external")

  /**
   * 外部会话 id 前缀（nodeSessionId=`external-<exec>-<id>`；前端 isBgAgentId /
   * 面板 kind 徽章按此前缀识别）。
   */
  val SessionPrefix = "external-"

  // ── 运行态（进程级）──────────────────────────────────────────────────
  private final case class Execution(runId: String, decision: Ref[IO, String], gate: Mutex[IO], spec: SpawnSpec)
  private val executions: Ref[IO, Map[String, Execution]] = Ref.unsafe(Map.empty)
  private val gates: Ref[IO, Map[String, Mutex[IO]]] = Ref.unsafe(Map.empty)

  private def sessionGate(id: String): IO[Mutex[IO]] =
    Mutex[IO].flatMap(candidate =>
      gates.modify { current =>
        val gate = current.getOrElse(id, candidate)
        (current.updated(id, gate), gate)
      }
    )

  /** Retry the same durable operation, never an external execution. */
  private def retryWrite[A](action: IO[A], attempts: Int = 5): IO[A] =
    action.handleErrorWith { error =>
      if attempts <= 1 then IO.raiseError(error)
      else IO.sleep((6 - attempts).seconds) *> retryWrite(action, attempts - 1)
    }

  private def releaseExecution(id: String, run: Execution, spec: SpawnSpec): IO[Unit] =
    currentExecution(id, run).flatMap { current =>
      IO.whenA(current)(
        nebflow.actor.ExternalAgentRecord.detach(spec.resources.agentRegistry, id) *>
          executions.update(_ - id)
      )
    }

  private def startExecution(sessionId: String, spec: SpawnSpec): IO[Execution] =
    for
      decision <- Ref.of[IO, String]("running")
      gate <- sessionGate(sessionId)
      run = Execution(java.util.UUID.randomUUID().toString, decision, gate, spec)
      row = SubAgentTask(
        taskId = sessionId,
        parentSessionId = spec.parentSessionId,
        agentName = spec.executorId,
        prompt = spec.task,
        description = spec.title,
        status = "running",
        retryCount = 0,
        spawnedAt = System.currentTimeMillis(),
        completedAt = None,
        lastError = None,
        source = "delegate"
      )
      accepted <- spec.resources.subAgentTaskStore.beginRun(row, run.runId, spec.rootSessionId, spec.executorId)
      _ <- accepted.fold(reason => IO.raiseError(new IllegalStateException(reason)), _ => IO.unit)
      _ <- executions.update(_ + (sessionId -> run))
    yield run

  private def admissionLocked[A](id: String, spec: SpawnSpec)(action: Execution => IO[A]): IO[A] =
    IO.uncancelable { _ =>
      startExecution(id, spec).flatMap { run =>
        action(run).handleErrorWith { error =>
          settleLocked(id, run, spec, None, Nil, false, Some(s"Execution setup failed: ${error.getMessage}")) *>
            IO.raiseError(error)
        }
      }
    }

  private def withAdmission[A](id: String, spec: SpawnSpec)(action: Execution => IO[A]): IO[A] =
    sessionGate(id).flatMap(_.lock.surround(admissionLocked(id, spec)(action)))

  private def requireCommit(result: Either[String, SubAgentTask]): IO[SubAgentTask] =
    result.fold(reason => IO.raiseError(new IllegalStateException(reason)), IO.pure)

  private def currentExecution(sessionId: String, run: Execution): IO[Boolean] =
    executions.get.map(_.get(sessionId).exists(_.runId == run.runId))

  private val continuationProducer = "external-delegate-mail"

  /** spawn/续聊共用参数（Delegate 外部腿与 Mail 续聊腿一条通路）。 */
  final case class SpawnSpec(
    executorId: String,
    /** 检测解析出的二进制绝对路径（fail-closed 已在 ExecutorRegistry.resolveRequested 保证）。 */
    binPath: String,
    task: String,
    title: String,
    workspace: String,
    project: Option[String],
    taskId: Option[String],
    parentSessionId: String,
    rootSessionId: String,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[Json => IO[Unit]],
    resources: AgentRuntimePort,
    /**
     * 面板命名批（2026-10-31）：批次短名（Delegate 工具 `name` 参数）随外部腿
     * 落 Record——与内置腿同面板语义。缺省 None：旧调用面/续聊腿零变化。
     */
    name: Option[String] = None,
    /**
     * Device-project marker（跨设备批 2026-10-30 遗留）：设备项目（origin 绑对端）
     * = true——外部 CLI 是 host 本地进程，设备仓路径不是本机盘路径。座位政策统一
     * 后本标记只剩归属语义（外部腿不参与设备盘 git 面）。
     */
    deviceProject: Boolean = false,
    model: Option[String] = None,
    continuationInputId: Option[String] = None
  )

  /** Delegate 外部腿入口。成功 = `(sessionId, ack)`（续聊地址行由调用方拼）。 */
  def spawn(spec: SpawnSpec): IO[Either[String, (String, String)]] =
    ExternalExecutor.forId(spec.executorId) match
      case None =>
        IO.pure(
          Left(
            s"Delegate: no adapter for executor '${spec.executorId}' — fail-closed, no fallback. (EXTERNAL_ADAPTER_MISSING)"
          )
        )
      case Some(ex) =>
        validWorkspace(spec.workspace) match
          case Left(wsErr) =>
            IO.pure(Left(s"Delegate: $wsErr (EXTERNAL_WORKSPACE_UNAVAILABLE)"))
          case Right(_) =>
            val sessionId = newSessionId(ex.id)
            // 工作座政策（托管树退场）：座位恒 = 派发 workspace——外部 CLI 与
            // 内部腿同政策；引擎不再建分支/worktree（需要隔离检出时外部执行器
            // 自建自管）。
            val seatWs = spec.workspace
            val seatedSpec = spec.copy(model = ExecutorRegistry.configuredModel(ex.id))
            val seatedTask = spec.task
            withAdmission(sessionId, seatedSpec) { run =>
              for
                _ <- ExternalOutputStore.open(sessionId)
                _ <- ExternalOutputStore.claimRun(sessionId, run.runId)
                _ <- IO.blocking {
                  DelegateRegistry.put(
                    DelegateRegistry.Record(
                      id = sessionId,
                      executor = ex.id,
                      cwd = seatWs,
                      project = spec.project,
                      taskId = spec.taskId,
                      parentSessionId = spec.parentSessionId,
                      title = spec.title,
                      createdAt = System.currentTimeMillis(),
                      // 面板命名批（2026-10-31）：批次短名落盘；续聊腿 spec.name 缺席时
                      // 回读存量记录补保（复活/续起不丢名）。
                      name = spec.name.orElse(DelegateRegistry.find(sessionId).flatMap(_.name)),
                      // G1 编排模式初值（delegate-ui 批 2026-10-08）：外部执行器零平台
                      // 工具注入（无 Subagent/AgentFlow 可调）⇒ 恒 single，无升级腿。
                      orchestrationMode = Some(DelegateRegistry.ModeSingle),
                      externalModel = seatedSpec.model
                    )
                  )
                }
                // 作者裁定4「登记 AgentKind.External」（2026-11-05 09:38）：内部腿有
                // actor + 统一注册表行，外部腿此前**两样都没有**（AgentControlTool 自陈
                // 「外部 delegate 无 actor / agentRegistry 行」）。此处在统一注册表补一条
                // kind=External 记录（ref=null），只为关闭「所有 agent 都能被 list /
                // cancel / 挂终态」这条不变式——**不参与监督与恢复**（恢复走
                // ExternalOutputStore / CLI resume），终态首胜时由 settle 摘除。
                _ <- nebflow.actor.ExternalAgentRecord.attach(
                  spec.resources.agentRegistry,
                  nebflow.actor.ExternalAgentRecord.build(
                    sessionId = sessionId,
                    rootSessionId = spec.rootSessionId,
                    parentSessionId = spec.parentSessionId,
                    startedAt = System.currentTimeMillis()
                  )
                )
                _ <- persistUserTurn(spec.resources, sessionId, spec.title, seatedTask, run.runId)
                // ⑤A 链存储：外部腿 spawn 行（与内部腿同账——cwd/执行器；branch/
                // worktree 族键为空串形态，与历史无绑定行兼容）。
                _ <- IO
                  .blocking(
                    ChainStore.appendSync(
                      ChainStore.keyFor(spec.taskId, sessionId),
                      "delegate-spawn",
                      nebflow.core.delegate.ChainStore.delegateSpawnPayload(
                        sessionId,
                        ex.id,
                        spec.project,
                        seatWs
                      )
                    )
                  )
                  .handleErrorWith(e =>
                    logger.warn(s"ChainStore delegate-spawn line (external) failed: ${e.getMessage}").void
                  )
                ack =
                  s"""Delegate task started in the background${spec.taskId.map(n => s" (task #$n)").getOrElse("")}.
Executor: ${ex.displayName} (external CLI); workspace: $seatWs${spec.project
                      .map(p => s" (project '$p')")
                      .getOrElse("")}.
It runs in session $sessionId with no project context and no memory; the brief is its entire context. External executors receive the task text only — no platform tools or plugins are injected.
You will be notified when it completes via a system message. Do NOT duplicate this task — wait for the result or work on something unrelated."""
                _ <- emitAgentStart(
                  seatedSpec.wsSend,
                  sessionId,
                  seatedSpec.rootSessionId,
                  seatedSpec.parentSessionId,
                  ex,
                  spec.title
                )
                _ <- launchTurn(sessionId, run, ex, seatedSpec, prompt = seatedTask, resumeSid = None)
                _ = logger.info(
                  s"External delegate spawned: $sessionId (executor=${ex.id}, workspace=$seatWs)"
                )
              yield Right((sessionId, ack))
            }

  /** Mail 续聊腿。live ⇒ mailbox 排队；终态 ⇒ CLI resume 续起（S15 注入通道）。 */
  def continueExternal(
    record: DelegateRegistry.Record,
    message: String,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[Json => IO[Unit]],
    resources: AgentRuntimePort,
    messageId: String = java.util.UUID.randomUUID().toString
  ): IO[Either[String, String]] =
    if record.executor == ExecutorRegistry.DefaultId then
      // 前置违例（内部记录误入外部腿）：显式拒绝，不猜路由。
      IO.pure(
        Left(
          s"Delegate '${record.id}' is an internal (${nebflow.shared.Branding.productName}) record — wrong routing leg. (EXTERNAL_LEG_MISMATCH)"
        )
      )
    else
      ExternalExecutor.forId(record.executor) match
        case None =>
          IO.pure(
            Left(
              s"Delegate '${record.id}' ran on executor '${record.executor}' which has no adapter on this build — continuation unavailable (fail-closed, no fallback). (EXTERNAL_ADAPTER_MISSING)"
            )
          )
        case Some(ex) =>
          ExecutorRegistry.resolveRequested(Some(ex.id)) match
            case Left(err) =>
              // CLI 被卸载 / 检测丢失：显式失败，绝不回落内置执行器。
              IO.pure(Left(s"Cannot continue external delegate '${record.id}': $err"))
            case Right(ExecutorRegistry.ResolvedSelection.External(_, binPath)) =>
              val sid = record.id
              val spec = SpawnSpec(
                executorId = ex.id,
                binPath = binPath,
                task = message,
                title = record.title,
                workspace = record.cwd,
                project = record.project,
                taskId = record.taskId,
                parentSessionId = record.parentSessionId,
                rootSessionId = record.parentSessionId,
                parentRef = parentRef,
                wsSend = wsSend,
                resources = resources,
                model = record.externalModel
              )
              sessionGate(sid).flatMap(_.lock.surround {
                import nebflow.core.mail.{InputMailbox, InputPayload}
                for
                  result <- InputMailbox.enqueue(sid, InputPayload(InputPayload.KindUser, message), messageId, continuationProducer)
                  response <- result match
                    case InputMailbox.InputMailResult.Failed(cause) => IO.pure(Left(s"External mail was not queued: $cause"))
                    case InputMailbox.InputMailResult.AlreadyPresent =>
                      IO.pure(Right(s"[delegate #$sid] Duplicate message id already present or delivered; no new turn was started."))
                    case _ =>
                      executions.get.flatMap { current =>
                        if current.contains(sid) then
                          IO.pure(Right(s"[delegate #$sid] Message durably queued; it will be delivered after the current turn ends."))
                        else
                          drainContinuationsLocked(record, ex, spec).attempt.map {
                            case Right(_) => Right(s"[delegate #$sid] Message durably queued; continuation processing has started.")
                            case Left(error) => Left(s"Message was durably queued, but continuation requires recovery: ${error.getMessage}")
                          }
                      }
                yield response
              })
            case Right(ExecutorRegistry.ResolvedSelection.Internal) =>
              IO.pure(
                Left(
                  s"Delegate '${record.id}' executor '${record.executor}' resolved to the built-in executor — wrong leg. (EXTERNAL_LEG_MISMATCH)"
                )
              )

  private def drainContinuationsLocked(
    record: DelegateRegistry.Record,
    ex: ExternalExecutor.ExternalExecutor,
    spec: SpawnSpec
  ): IO[Unit] =
    import nebflow.core.mail.InputMailbox
    for
      taken <- InputMailbox.scanTaken(record.id)
      _ <- IO.raiseWhen(taken.exists(_.producer == continuationProducer))(
        new IllegalStateException("External continuation has an unresolved taken input; manual recovery is required")
      )
      pending <- IO.blocking(InputMailbox.scanPendingSync(record.id))
      _ <- pending.find(_.producer == continuationProducer).traverse_ { entry =>
        for
          claimed <- InputMailbox.take(record.id, List(entry))
          _ <- IO.raiseWhen(claimed.isEmpty)(new IllegalStateException("External continuation could not be claimed"))
          _ <- startResumeTurn(record, ex, entry.payload.text, spec.copy(task = entry.payload.text, continuationInputId = Some(entry.identity)))
        yield ()
      }
    yield ()

  /** 终态后续起的 turn：CLI resume（cliSessionId 在手）或带前文的续聊 brief。 */
  private def startResumeTurn(
    record: DelegateRegistry.Record,
    ex: ExternalExecutor.ExternalExecutor,
    message: String,
    spec: SpawnSpec
  ): IO[Either[String, String]] =
    admissionLocked(record.id, spec) { run =>
      for
        resumeSid <- ExternalOutputStore.cliSessionIdOf(record.id).map(_.orElse(record.cliSessionId))
        brief <- resumeSid match
          case Some(_) => IO.pure(message) // CLI 自带会话持久化——消息即续轮输入
          case None =>
            // 无 CLI 会话锚点（旧记录/流未给出）：带前文终稿的续聊 brief（诚实降级，
            // 仍在同一 delegate 地址、同一外部执行器内——零回落）。
            priorFinalText(spec.resources, record.id).map { prior =>
              val head =
                if prior.nonEmpty then
                  s"[continuation of delegate ${record.id} — the instance's last output follows]\n\n$prior\n\n[New instruction]\n"
                else s"[continuation of delegate ${record.id} — no prior output captured]\n\n[New instruction]\n"
              head + message
            }
        _ <- ExternalOutputStore.open(record.id)
        _ <- ExternalOutputStore.resetForNewRun(record.id)
        _ <- ExternalOutputStore.claimRun(record.id, run.runId)
        _ <- persistUserTurn(spec.resources, record.id, record.title, message, run.runId)
        _ <- nebflow.actor.ExternalAgentRecord.attach(
          spec.resources.agentRegistry,
          nebflow.actor.ExternalAgentRecord
            .build(record.id, spec.rootSessionId, spec.parentSessionId, System.currentTimeMillis())
        )
        _ <- emitAgentStart(spec.wsSend, record.id, record.parentSessionId, record.parentSessionId, ex, record.title)
        _ <- launchTurn(record.id, run, ex, spec, prompt = brief, resumeSid = resumeSid)
        _ <- appendLedgerNote(
          record.taskId,
          s"Mail supplement → external resume turn (${ex.id}${resumeSid.fold("")(sid => s" cli=$sid")})"
        )
      yield Right(
        s"[delegate #${record.id}] Instance was not live — your message CONTINUED the external ${ex.displayName} session (resume turn started). Its result is delivered back when it finishes."
      )
    }

  // ── 取消（AgentControl / 面板 cancelAgent 共用；原书：杀进程树）─────────

  def cancel(id: String, reason: String, by: String): IO[Either[String, String]] =
    sessionGate(id).flatMap(_.lock.surround {
      executions.get.flatMap(_.get(id) match
        case None => IO.pure(Left(s"No live external delegate '$id' to cancel."))
        case Some(run) =>
          run.decision
            .modify {
              case "running" => ("cancelled", true)
              case other => (other, false)
            }
            .flatMap {
              case false => IO.pure(Left(s"External delegate '$id' is already finishing."))
              case true =>
                val durable = retryWrite(
                  run.spec.resources.subAgentTaskStore
                    .updateRun(run.spec.parentSessionId, id, run.runId, diagnostic = Some("Cancellation requested"))
                    .flatMap(requireCommit)
                )
                durable
                  .guarantee(ExternalOutputStore.killRun(id, run.runId).void)
                  .as(Right(s"Cancellation recorded for external delegate '$id'."))
            })
    })

  private def launchTurn(
    sessionId: String,
    run: Execution,
    ex: ExternalExecutor.ExternalExecutor,
    spec: SpawnSpec,
    prompt: String,
    resumeSid: Option[String]
  ): IO[Unit] =
    IO.blocking {
      val process = buildProcess(ex, spec, prompt, resumeSid).start()
      sealChildStdin(process)
      process
    }.attempt
      .flatMap {
        case Left(error) =>
          settleLocked(
            sessionId,
            run,
            spec,
            None,
            Nil,
            hasTerminal = false,
            Some(s"Process launch failed: ${error.getMessage}")
          )
        case Right(process) =>
          (ExternalOutputStore.attachProcessForRun(sessionId, run.runId, process) *>
            retryWrite(
              spec.resources.subAgentTaskStore
                .updateRun(
                  spec.parentSessionId,
                  sessionId,
                  run.runId,
                  executionStatus = Some("running"),
                  activityAt = Some(System.currentTimeMillis()),
                  activityKind = Some("started"),
                  activityLabel = Some("Execution started")
                )
                .flatMap(requireCommit)
            ) *>
            readLoop(sessionId, run, ex, spec, process)
              .handleErrorWith { error =>
                ExternalOutputStore.stopOwnedProcess(process) *>
                  settle(
                    sessionId,
                    run,
                    spec,
                    None,
                    Nil,
                    false,
                    Some(s"Output reader failed: ${error.getClass.getSimpleName}: ${error.getMessage}")
                  )
              }
              .start
              .void).onError { case _ => ExternalOutputStore.stopOwnedProcess(process) }
      }

  /** 构造 ProcessBuilder（cwd = 工作座位；stdout/stderr 分管）。 */
  private def buildProcess(
    ex: ExternalExecutor.ExternalExecutor,
    spec: SpawnSpec,
    prompt: String,
    resumeSid: Option[String]
  ): java.lang.ProcessBuilder =
    configureProcessBuilder(spec.binPath, spec.workspace, ex.launchArgsWithModel(prompt, resumeSid, spec.model))

  /**
   * spawn 构造纯面（spec 可驱动）：argv + cwd + 分管 + 代理环境注入
   * （codex-spawn-fix 批 2026-10-19 死因①——[[ExternalSpawnConfig]] 点名键值，
   * 缺省零注入）。stdin 不在此处 redirect：关闭动作在 start() 后立即执行，
   * 见 [[sealChildStdin]] 的选型理由。
   */
  private[executor] def configureProcessBuilder(
    binPath: String,
    workspace: String,
    args: List[String]
  ): java.lang.ProcessBuilder =
    val pb = new java.lang.ProcessBuilder((binPath +: args)*)
    pb.directory(new java.io.File(workspace))
    pb.redirectErrorStream(false)
    ExternalSpawnConfig.installProxyEnv(pb)
    pb

  /**
   * spawn 后立即关闭子进程 stdin（所有外部执行器统一）。实现选型 =
   * start 后关闭管道写端（`getOutputStream.close()`），而非
   * `redirectInput(Redirect.from(/dev/null))`：
   *   - 跨平台——/dev/null 在 Windows 不存在，start() 会抛 IOException
   *     （shell.scala 的 Windows 分支正因同一教训另走 PIPE），引擎发 Windows 版，
   *     此处引入 /dev/null 即 Windows 回归；
   *   - 语义等价：子进程读 stdin 立即得 EOF（codex 不再永等 input）；
   *   - 三适配器零 stdin 依赖已确认：claude `-p` / zcode `-p` / codex `exec`
   *     的任务文本全部走 argv，exec/print 模式均不读 stdin。
   */
  private[executor] def sealChildStdin(p: java.lang.Process): Unit =
    try p.getOutputStream.close()
    catch case _: Throwable => () // 已关闭/进程早夭——无害，终态由 readLoop/settle 判定

  private def rawPath(sessionId: String, runId: String): os.Path =
    nebflow.shared.PathUtil.dataRoot / "sessions" / sessionId / s"external-$runId.log"

  /** Raw evidence has a per-run 8 MiB ceiling; full assistant history is stored separately. */
  private[executor] val RawLimitBytes = 8 * 1024 * 1024
  private val rawLock = new Object

  private[executor] def appendRawFile(path: os.Path, line: String): IO[Unit] = IO.blocking {
    rawLock.synchronized {
      import java.nio.file.{Files, LinkOption, StandardOpenOption}
      import java.nio.file.attribute.PosixFilePermissions
      val parent = path.toNIO.getParent
      Files.createDirectories(parent)
      val posix = Files.getFileStore(parent).supportsFileAttributeView("posix")
      if posix then Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
      if !Files.exists(path.toNIO, LinkOption.NOFOLLOW_LINKS) then
        if posix then
          Files.createFile(
            path.toNIO,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
          )
        else Files.createFile(path.toNIO)
      if Files.isSymbolicLink(path.toNIO) then throw new IllegalStateException("Raw log cannot be a symbolic link")
      if posix then Files.setPosixFilePermissions(path.toNIO, PosixFilePermissions.fromString("rw-------"))
      val size = Files.size(path.toNIO)
      val marker = "\n[Raw output truncated at 8 MiB; assistant history is retained separately.]\n"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      if size < RawLimitBytes then
        val bytes = (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val remaining = (RawLimitBytes - size).toInt
        val write =
          if bytes.length <= remaining - marker.length then bytes
          else bytes.take(math.max(0, remaining - marker.length)) ++ marker.take(remaining)
        Files.write(path.toNIO, write, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
    }
  }

  private def appendRaw(sessionId: String, run: Execution, line: String): IO[Unit] =
    appendRawFile(rawPath(sessionId, run.runId), line) *>
      ExternalOutputStore.appendForRun(sessionId, run.runId, line)

  private def commitMessages(
    sessionId: String,
    run: Execution,
    spec: SpawnSpec,
    messages: List[ExternalMessageCollector.CollectedMessage]
  ): IO[Unit] = messages.traverse_ { captured =>
    retryWrite(for
      current <- currentExecution(sessionId, run)
      _ <- IO.raiseWhen(!current)(new IllegalStateException("Stale external execution"))
      fresh <- spec.resources.sessionStore.appendDurableMessage(
        sessionId,
        Message(
          MessageRole.Assistant,
          Left(captured.text),
          source = Some("delegate"),
          messageId = Some(captured.id),
          runId = Some(run.runId)
        )
      )
      row <- spec.resources.subAgentTaskStore
        .updateRun(
          spec.parentSessionId,
          sessionId,
          run.runId,
          activityAt = Some(System.currentTimeMillis()),
          activityKind = Some("text"),
          activityLabel = Some("Assistant message received")
        )
        .flatMap(requireCommit)
      _ <- IO.whenA(fresh)(
        emitFrame(spec.wsSend, spec.rootSessionId, spec.parentSessionId, sessionId)(
          Json.obj(
            "type" -> "agentTextDelta".asJson,
            "agentId" -> sessionId.asJson,
            "taskId" -> sessionId.asJson,
            "runId" -> run.runId.asJson,
            "committedRevision" -> row.committedRevision.asJson,
            "messageId" -> captured.id.asJson,
            "delta" -> captured.text.asJson
          )
        )
      )
    yield ())
  }

  private def readLoop(
    sessionId: String,
    run: Execution,
    ex: ExternalExecutor.ExternalExecutor,
    spec: SpawnSpec,
    process: java.lang.Process
  ): IO[Unit] =
    val collector = new ExternalMessageCollector(run.runId)
    val failures = scala.collection.mutable.ListBuffer.empty[String]
    def capture(action: IO[Unit]): Unit = action.attempt.unsafeRunSync() match
      case Left(error) => failures.synchronized { failures += error.getClass.getSimpleName; () }
      case Right(_) => ()
    val stderr = IO.interruptibleMany {
      val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream))
      try
        Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten.foreach { line =>
          capture(appendRaw(sessionId, run, s"[stderr] $line"))
        }
      finally reader.close()
    }
    val stdout = IO.interruptibleMany {
      val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
      var lastFlush = System.nanoTime()
      var pendingChars = 0
      try
        Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten.foreach { line =>
          capture(appendRaw(sessionId, run, line))
          val frames = Try(ex.parseLine(line)).getOrElse {
            failures.synchronized { failures += "Frame parser failed" }
            Nil
          }
          frames.foreach { frame =>
            frame match
              case ExternalExecutor.Frame.Start(Some(cliSid)) =>
                capture(
                  ExternalOutputStore.setCliSession(sessionId, cliSid) *> updateRecordCliSession(sessionId, cliSid)
                )
              case ExternalExecutor.Frame.Tool(label) =>
                capture(
                  spec.resources.subAgentTaskStore
                    .updateRun(
                      spec.parentSessionId,
                      sessionId,
                      run.runId,
                      activityAt = Some(System.currentTimeMillis()),
                      activityKind = Some("tool"),
                      activityLabel = Some(label.take(120))
                    )
                    .flatMap(requireCommit)
                    .flatMap { row =>
                      emitFrame(spec.wsSend, spec.rootSessionId, spec.parentSessionId, sessionId)(
                        Json.obj(
                          "type" -> "agentToolStart".asJson,
                          "agentId" -> sessionId.asJson,
                          "taskId" -> sessionId.asJson,
                          "runId" -> run.runId.asJson,
                          "committedRevision" -> row.committedRevision.asJson,
                          "label" -> label.take(120).asJson
                        )
                      )
                    }
                )
              case ExternalExecutor.Frame.Text(text) => pendingChars += text.length
              case _ => ()
            end match
            capture(commitMessages(sessionId, run, spec, collector.accept(frame)))
          }
          if pendingChars >= 4096 || System.nanoTime() - lastFlush >= 1000000000L then
            capture(commitMessages(sessionId, run, spec, collector.drainPending()))
            pendingChars = 0
            lastFlush = System.nanoTime()
        }
      finally reader.close()
      end try
    }
    // Both pipes must drain even if one reader fails; neither waits for the other to start.
    for
      errFiber <- stderr.onError { case _ => ExternalOutputStore.stopOwnedProcess(process) }.attempt.start
      readResult <- IO
        .race(
          stdout,
          IO.fromCompletableFuture(IO(process.onExit())) *> IO.sleep(5.seconds) *>
            IO.raiseError[Unit](new IllegalStateException("Stdout remained open after process exit"))
        )
        .void
        .onError { case _ => ExternalOutputStore.stopOwnedProcess(process) }
        .attempt
      exited <- IO.blocking(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
      _ <- IO.unlessA(exited)(
        ExternalOutputStore.stopOwnedProcess(process) *>
          IO { failures.synchronized { failures += "Process did not exit after stdout closed"; () } }
      )
      exit <- IO.blocking(process.exitValue())
      errResult <- errFiber.joinWithNever.timeoutTo(
        3.seconds,
        IO.pure(Left(new IllegalStateException("Stderr did not close after process exit")))
      )
      _ <- IO(collector.flush())
      flushed <- commitMessages(sessionId, run, spec, collector.messages).attempt
      errors = failures.toList ++ List(readResult, errResult, flushed).collect { case Left(e) =>
        e.getClass.getSimpleName
      }
      diagnostic = (errors ++ collector.diagnostics).distinct.mkString("; ")
      _ <- IO
        .blocking {
          val path = rawPath(sessionId, run.runId)
          if os.exists(path) then
            val channel = java.nio.channels.FileChannel.open(path.toNIO, java.nio.file.StandardOpenOption.WRITE)
            try channel.force(true)
            finally channel.close()
        }
        .handleErrorWith(e => logger.warn(s"External raw output flush failed: ${e.getMessage}"))
      _ <- settle(
        sessionId,
        run,
        spec,
        Some(exit),
        collector.messages,
        collector.hasTerminal,
        Option.when(diagnostic.nonEmpty)(diagnostic),
        collector.failed
      )
    yield ()

    end for

  end readLoop

  private def settle(
    sessionId: String,
    run: Execution,
    spec: SpawnSpec,
    exitCode: Option[Int],
    messages: List[ExternalMessageCollector.CollectedMessage],
    hasTerminal: Boolean,
    diagnostic: Option[String],
    protocolFailed: Boolean = false
  ): IO[Unit] = run.gate.lock
    .surround(settleLocked(sessionId, run, spec, exitCode, messages, hasTerminal, diagnostic, protocolFailed))

  private def settleLocked(
    sessionId: String,
    run: Execution,
    spec: SpawnSpec,
    exitCode: Option[Int],
    messages: List[ExternalMessageCollector.CollectedMessage],
    hasTerminal: Boolean,
    diagnostic: Option[String],
    protocolFailed: Boolean = false
  ): IO[Unit] =
    run.decision
      .modify {
        case "running" => ("finishing", Some(false))
        case "cancelled" => ("finishing", Some(true))
        case other => (other, None)
      }
      .flatMap {
        case None => IO.unit
        case Some(cancelled) =>
          (for
            current <- currentExecution(sessionId, run)
            _ <- IO.raiseWhen(!current)(new IllegalStateException("Stale external completion"))
            incomplete = !hasTerminal || diagnostic.nonEmpty
            reason =
              if cancelled then Some("Cancelled")
              else if incomplete then Some("Result collection incomplete" + diagnostic.fold("")(d => s": $d"))
              else if exitCode.exists(_ != 0) || protocolFailed then
                Some(s"External execution failed (exit ${exitCode.getOrElse(-1)})")
              else None
            outcome =
              if cancelled then Outcome.Cancelled else reason.fold[Outcome](Outcome.Completed)(Outcome.Failed.apply)
            committed <- retryWrite(
              spec.resources.subAgentTaskStore.completeRun(
                spec.parentSessionId,
                sessionId,
                run.runId,
                executionStatus = outcome match
                  case Outcome.Completed => "finished"
                  case Outcome.Cancelled => "cancelled"
                  case _ => "failed",
                reason = reason,
                resultRef = Some(s"$sessionId#${run.runId}"),
                incomplete = incomplete,
                diagnostic = diagnostic,
                notificationId = Some(s"$sessionId#${run.runId}")
              )
            )
            row <- committed match
              case Right(row) => IO.pure(row)
              case Left("terminal-run") =>
                spec.resources.subAgentTaskStore.findByTaskId(sessionId).flatMap {
                  case Some(row) if row.runId.contains(run.runId) => IO.pure(row)
                  case _ => IO.raiseError(new IllegalStateException("Stale completion retry"))
                }
              case Left(reason) => IO.raiseError(new IllegalStateException(reason))
            persistedOutcome = row.executionStatus match
              case Some("finished") => Outcome.Completed
              case Some("cancelled") => Outcome.Cancelled
              case _ => Outcome.Failed(row.terminalReason.getOrElse("External execution failed"))
            text <- storedRunText(spec.resources.sessionStore, sessionId, run.runId)
            _ <- ExternalOutputStore.finalizeRun(sessionId, run.runId, row.status, exitCode, row.terminalReason)
            _ <- retryWrite(notifyParent(spec, sessionId, persistedOutcome, text, row.terminalReason, Some(run.runId)))
            _ <- retryWrite(
              spec.resources.subAgentTaskStore
                .acknowledgeNotification(spec.parentSessionId, sessionId, run.runId, s"$sessionId#${run.runId}")
                .flatMap(requireCommit)
            )
            _ <- spec.continuationInputId.traverse_(id =>
              IO.blocking(nebflow.core.mail.InputMailbox.unlinkTakenSync(sessionId, List(id)))
            )
            _ <- emitFrame(spec.wsSend, spec.rootSessionId, spec.parentSessionId, sessionId)(
              Json.obj(
                "type" -> "agentDone".asJson,
                "agentId" -> sessionId.asJson,
                "taskId" -> sessionId.asJson,
                "runId" -> run.runId.asJson,
                "committedRevision" -> row.committedRevision.asJson
              )
            )
          yield ())
            .handleErrorWith { error =>
              logger.warn(
                s"External completion remains pending after bounded retries for $sessionId/${run.runId}: ${error.getMessage}"
              )
            }
            .guarantee(releaseExecution(sessionId, run, spec)) *>
            IO.blocking(DelegateRegistry.find(sessionId)).flatMap {
              case Some(record) =>
                ExternalExecutor.forId(record.executor).fold(IO.unit)(ex =>
                  drainContinuationsLocked(record, ex, spec.copy(continuationInputId = None))
                )
              case None => IO.unit
            }.handleErrorWith(error => logger.warn(s"External continuation remains pending for $sessionId: ${error.getMessage}"))
      }

  private[executor] def reconstructRunText(messages: List[Message], runId: String): String =
    val groups = scala.collection.mutable.LinkedHashMap.empty[String, StringBuilder]
    messages.zipWithIndex.foreach { case (message, index) =>
      if message.role == MessageRole.Assistant && message.runId.contains(runId) then
        val key = message.messageId
          .map { id =>
            val block = id.lastIndexOf("/block/")
            if block >= 0 then id.take(block) else id
          }
          .getOrElse(s"legacy-$index")
        groups.getOrElseUpdate(key, new StringBuilder).append(message.textContent)
    }
    groups.valuesIterator.map(_.toString).mkString("\n\n")

  private def storedRunText(store: nebflow.core.SessionStore, sessionId: String, runId: String): IO[String] =
    store.loadMessagesForSession(sessionId).map(reconstructRunText(_, runId))

  private[executor] enum Outcome:
    case Completed
    case Failed(reason: String)
    case Cancelled

  private[executor] def notifyParent(
    spec: SpawnSpec,
    sessionId: String,
    outcome: Outcome,
    text: String,
    reasonOpt: Option[String],
    runId: Option[String] = None
  ): IO[Unit] =
    val identity = runId.fold(sessionId)(r => s"$sessionId#$r")
    val event = terminalEvent(sessionId, spec.title, spec.executorId, outcome, text, reasonOpt, runId)
    enqueueResult(spec.parentSessionId, identity, event) *>
      spec.resources.agentRegistry.get.map(_.get(spec.parentSessionId)).flatMap {
        case Some(record) if nebflow.actor.ExternalAgentRecord.isMessagable(record.kind) =>
          (record.ref ! AgentCommand.MailboxArrived).void.handleErrorWith(_ => IO.unit)
        case _ => IO.unit
      }

  end notifyParent

  private def terminalEvent(
    sessionId: String,
    title: String,
    executor: String,
    outcome: Outcome,
    text: String,
    reason: Option[String],
    runId: Option[String]
  ): AgentCommand.ExternalEvent =
    val limit = 48000
    val body =
      if text.length <= limit then text
      else
        text.take(limit) +
          s"\n[Truncated; full result remains in session $sessionId, execution ${runId.getOrElse("legacy")}.]"
    val eventType = outcome match
      case Outcome.Completed => "completed"
      case Outcome.Cancelled => "cancelled"
      case _ => "failed"
    val heading =
      if eventType == "completed" then "Execution finished; requirement completion is not inferred."
      else reason.getOrElse(eventType)
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = eventType,
      payload = s"$title: $heading\n$body\n[Result history: $sessionId]",
      metadata = io.circe.JsonObject(
        "description" -> title.asJson,
        "agentName" -> executor.asJson,
        "taskId" -> sessionId.asJson,
        "completionGeneration" -> runId.asJson,
        "failedSessionId" -> Option.when(eventType != "completed")(sessionId).asJson,
        "retryable" -> false.asJson,
        "reason" -> reason.asJson
      ),
      correlationId = Some(sessionId)
    )

  end terminalEvent

  private def enqueueResult(parentId: String, identity: String, event: AgentCommand.ExternalEvent): IO[Unit] =
    nebflow.core.mail.SessionMailbox
      .mailOnce(
        parentId,
        event,
        kind = "delegate-terminal",
        identity = identity,
        producer = "external-session-runner",
        deduplicateReceipts = true
      )
      .flatMap {
        case nebflow.core.mail.SessionMailbox.MailResult.Failed(cause) =>
          IO.raiseError(new IllegalStateException(cause))
        case _ => IO.unit
      }

  def recoverPendingNotifications(
    store: nebflow.core.SubAgentTaskStorePort,
    sessions: nebflow.core.SessionStore
  ): IO[Unit] =
    store
      .findAllTasks()
      .flatMap(
        _.filter(t =>
          t.executor.exists(_ != ExecutorRegistry.DefaultId) &&
            t.notificationPending.contains(true) && t.runId.nonEmpty && t.notificationId.nonEmpty
        ).traverse_ { task =>
          val runId = task.runId.get
          val outcome = task.executionStatus match
            case Some("finished") => Outcome.Completed
            case Some("cancelled") => Outcome.Cancelled
            case _ => Outcome.Failed(task.terminalReason.getOrElse("External execution failed"))
          (for
            text <- storedRunText(sessions, task.taskId, runId)
            _ <- retryWrite(
              enqueueResult(
                task.parentSessionId,
                task.notificationId.get,
                terminalEvent(
                  task.taskId,
                  task.description,
                  task.executor.getOrElse(task.agentName),
                  outcome,
                  text,
                  task.terminalReason,
                  Some(runId)
                )
              )
            )
            _ <- retryWrite(
              store
                .acknowledgeNotification(task.parentSessionId, task.taskId, runId, task.notificationId.get)
                .flatMap(requireCommit)
            )
          yield ()).handleErrorWith(e =>
            logger.warn(s"External notification remains pending for ${task.taskId}: ${e.getMessage}")
          )
        }
      )

  // ── WS 帧发射（routeSubagentWsSend 同契约的本地盖章面）──────────────────

  private def emitFrame(
    wsSend: Option[Json => IO[Unit]],
    rootSessionId: String,
    parentSessionId: String,
    sessionId: String
  )(frame: Json): IO[Unit] =
    wsSend match
      case None => IO.unit
      case Some(send) =>
        val routed = frame.asObject match
          case Some(obj) =>
            val withRoot =
              if rootSessionId.nonEmpty && !obj.contains("rootSessionId") then
                obj.add("rootSessionId", rootSessionId.asJson)
              else obj
            // 面板树批（2026-10-29 ①）：agentStart 入口帧注入 parentSessionId
            // （外部 delegate 行同样可被前端派生树认领；仅入口帧——载荷纪律同
            // DelegateTool.routeWsSend）。
            val isStart = obj("type").exists(_.asString.contains("agentStart"))
            val withParent =
              if !isStart || parentSessionId.isEmpty || obj.contains("parentSessionId") then withRoot
              else withRoot.add("parentSessionId", parentSessionId.asJson)
            val withSession =
              if withParent.contains("sessionId") then withParent
              else withParent.add("sessionId", parentSessionId.asJson)
            val withNode =
              if withSession.contains("nodeSessionId") then withSession
              else withSession.add("nodeSessionId", sessionId.asJson)
            Json.fromJsonObject(withNode)
          case None => frame
        send(routed)
          .timeout(5.seconds)
          .handleErrorWith(e => logger.warn(s"External delegate WS frame failed: ${e.getMessage}"))

  private def emitAgentStart(
    wsSend: Option[Json => IO[Unit]],
    sessionId: String,
    rootSessionId: String,
    parentSessionId: String,
    ex: ExternalExecutor.ExternalExecutor,
    title: String
  ): IO[Unit] =
    emitFrame(wsSend, rootSessionId, parentSessionId, sessionId)(
      Json.obj(
        "type" -> "agentStart".asJson,
        "agentId" -> sessionId.asJson,
        "name" -> ex.displayName.asJson,
        "agentType" -> "external".asJson,
        "taskDescription" -> title.asJson
      )
    )

  // ── 小件（SessionStore / 台账 / registry / 工具形态）────────────────────

  private def newSessionId(execId: String): String =
    s"${SessionPrefix}$execId-${java.util.UUID.randomUUID().toString.take(8)}"

  /** spec 钩子：会话 id 形态（prefix ＋ DelegateRegistry.validId 兼容）纯函数验证。 */
  private[executor] def newSessionIdForTest(execId: String): String = newSessionId(execId)

  private def validWorkspace(ws: String): Either[String, String] =
    try
      val p = os.Path(ws)
      if os.isDir(p) then Right(ws)
      else Left(s"workspace '$ws' is not an existing directory")
    catch case e: Throwable => Left(s"workspace '$ws' is invalid: ${e.getMessage}")

  private def persistUserTurn(
    resources: AgentRuntimePort,
    sessionId: String,
    title: String,
    userText: String,
    runId: String
  ): IO[Unit] =
    resources.sessionStore.getOrCreateSession(sessionId, title) *>
      resources.sessionStore
        .appendDurableMessage(
          sessionId,
          Message(
            MessageRole.User,
            Left(userText),
            source = Some("delegate"),
            messageId = Some(s"$runId-input"),
            runId = Some(runId)
          )
        )
        .void

  private def priorFinalText(resources: AgentRuntimePort, sessionId: String): IO[String] =
    resources.sessionStore
      .loadMessagesForSession(sessionId)
      .map(msgs =>
        msgs.reverse.collectFirst { case m if m.role == MessageRole.Assistant => m } match
          case Some(message) => message.runId.fold(message.textContent)(reconstructRunText(msgs, _))
          case None => ""
      )
      .handleError(_ => "")

  /** 台账 note（taskId 缺失 / 失败 = 静默——fail-soft，同内部口径）。 */
  private def appendLedgerNote(taskId: Option[String], note: String): IO[Unit] =
    taskId match
      case None => IO.unit
      case Some(tid) =>
        IO.blocking(
          nebflow.core.project.TaskLedgerStore
            .open()
            .appendNoteSync(
              tid,
              note,
              from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
              actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
            )
        ).attempt
          .void

  private def updateRecordCliSession(sessionId: String, cliSid: String): IO[Unit] =
    if cliSid.nonEmpty then
      IO.blocking {
        DelegateRegistry
          .find(sessionId)
          .foreach(r => DelegateRegistry.put(r.copy(cliSessionId = r.cliSessionId.orElse(Some(cliSid)))))
      }.void
    else IO.unit

end ExternalSessionRunner
