package nebflow.core.executor

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.actor.ActorRef
import nebflow.core.AgentRuntimePort
import nebflow.core.GitWorktrees
import nebflow.core.delegate.ChainStore
import nebflow.core.delegate.DelegateAutoMerge
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.delegate.DelegateWorkspace
import nebflow.core.delegate.ExternalJudgeHook
import nebflow.core.delegate.MergeAcceptance
import nebflow.core.delegate.MergeQueue
import nebflow.core.delegate.DelegateTerminal
import nebflow.core.delegate.PostMergeCleanup
import nebflow.shared.Message
import nebflow.shared.MessageRole
import nebflow.shared.NebflowLogger
import nebflow.shared.SubAgentTask

import scala.collection.immutable.Queue
import scala.util.Try

/**
 * ExternalSessionRunner —— 外部执行器会话运行器（external-executors 批
 * #1225，2026-10-06）。R2/R11 外部腿真通路的执行半：子进程生命周期、流帧、
 * 终态投递、续聊与取消。
 *
 * == 通路（增补任务书 §一/§二 定稿句）==
 *   - spawn：[[ExecutorRegistry]] 检测面 → [[ExternalExecutor.launchArgs]] →
 *     `ProcessBuilder`（cwd = project workspace 或默认 general 目录）→ stdout
 *     逐行 → 适配器帧 → WS（nodeSessionId=`external-<exec>-<id>`；与
 *     routeSubagentWsSend 同契约：rootSessionId 归桶键 + sessionId 路由键 +
 *     nodeSessionId 弹窗键）→ agentStart/agentTextDelta/agentToolStart/agentDone。
 *   - 会话 = SessionStore 持久会话（`getOrCreateSession(subagentId)` ＋ 逐轮
 *     user/assistant 追加）——外部会话历史与内部同库（#1228 任务行/历史消费）。
 *   - 台账/地址/任务行 = DelegateRegistry（持久记录；cliSessionId = 续聊锚点）
 *     ＋ SubAgentTaskStore（per-parent 任务行）＋ TaskLedger note。
 *   - 取消 = ProcessTree.killProcessTree（原书）；进度 = [[ExternalOutputStore]]
 *     缓冲（REST `GET /api/delegates/:id/output`，仿 bg-tasks 输出面）。
 *   - 终态 = ExternalEvent(source="delegate") 直投父会话（与内部 delegate 同一
 *     回投链、同一 barrier 语义）；三态判定缝 = [[ExternalJudgeHook]]（缺省接
 *     DelegateJudgePort 判定机——引擎加固批 2026-10-09 P1-2；未接线回退进程结果
 *     直判）。
 *   - **座位纪律（P1-2，2026-10-09）**：spawn 前与内部腿走同一
 *     [[nebflow.core.delegate.DelegateWorkspace.prepare]]（git 工作区 = 分支
 *     `delegate/<id>` ＋ worktree 座位，进程 cwd = 座位；非 git = 旧形态逐字）；
 *     判定机判 ready-to-merge ⇒ 并入 [[nebflow.core.delegate.MergeQueue]]（外部
 *     通道条目，合并轮经续聊通道注入）；合并轮 completed ⇒ 同一机械验收闸
 *     （[[nebflow.core.delegate.MergeAcceptance]]）＋同闸现场回收（P2-4）。
 *     漂移闸对外部腿 = 注记级（[[nebflow.core.delegate.DelegateTerminal]]
 *     .externalDriftNote 并入判定输入——零罚则面，保护等级注记级）。
 *   - 续聊（S15 通道，增补条款 3.5：地址面零新面——同一 `delegate:<id>`）：
 *     live ⇒ mailbox 排队（本 run 终态后一条 resume 消化）；终态 ⇒ CLI resume
 *     续起。**合并轮也走这同一条注入通道**（MergeQueue → 注入一条
 *     `merge --no-ff` brief 消息——S15 钉死「无第二机制」）。
 *
 * == fail-closed（增补条款 3.4，R2/R11）==
 * 适配器建立 / resume / 进程失败一律显式终态（blocked ＋原因），错误面走任务行
 * / AgentControl status / REST 输出面；**零静默回退 nebflow**、零自动重试
 * （单次失败即终态，重派是主 agent 的决策）。
 */
object ExternalSessionRunner:

  private val logger = NebflowLogger.forName("nebflow.executor.external")

  /**
   * 外部会话 id 前缀（nodeSessionId=`external-<exec>-<id>`；前端 isBgAgentId /
   * 面板 kind 徽章按此前缀识别）。
   */
  val SessionPrefix = "external-"

  // ── 运行态（进程级）──────────────────────────────────────────────────
  private val cancelRequested: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
  private val settled: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)

  /** 续聊启动互斥窗（防「同时 resume 双进程」竞态——输家入队 mailbox）。 */
  private val resuming: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)

  /**
   * 合并轮在途标记（引擎加固批 2026-10-09，P1-2/P2-5）：合并指令经通道注入
   * （MergeQueue 外部腿条目 / boot sweep 恢复腿）的会话——下一次 settle 按合并轮
   * 组装判定素材（mergeRound 旗进判定缝）并跑机械验收闸。settle 读时即清
   * （一旗一轮）。
   */
  private val mergeRounds: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)

  /** 合并轮标记（MergeQueue 通道闭包 / boot sweep 恢复腿调用——下一次 settle 消费）。 */
  def noteMergeRound(sessionId: String): IO[Unit] = mergeRounds.update(_ + sessionId)

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
    resources: AgentRuntimePort
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
            for
              // 引擎加固批 2026-10-09（P1-2 外部腿接纪律）：外部 CLI 与内部腿走
              // **同一** DelegateWorkspace.prepare——git 工作区 = 判定机（jev/small
              // -judge）裁决 + 分支 `delegate/<id>` + worktree 座位（cwd = worktree
              // +分支）；非 git 工作区 = None（旧形态逐字：座位 = 原 workspace）。
              // 判定机两层全败 = prepare 内部 fail-open 反转判是（分支纪律不丢）。
              binding <- DelegateWorkspace
                .prepare(spec.workspace, spec.task, sessionId)
                .handleErrorWith(e =>
                  logger
                    .warn(
                      s"DelegateWorkspace.prepare failed on the external leg (degraded to legacy spawn): ${e.getMessage}"
                    )
                    .as(None)
                )
              seatWs = binding.map(_.cwd).getOrElse(spec.workspace)
              // 进程 cwd = 工作座位（worktree 腿）；spawn 后续 settle 的 git 读数同座。
              seatedSpec = spec.copy(workspace = seatWs)
              // brief 行前置（🔴 模型可见文案——workingBranchLine 同内部腿；外部零
              // 平台注入，任务文本即全部上下文——申报行走文本前位）。
              seatedTask = binding.flatMap(_.briefLine).map(l => s"$l\n\n${spec.task}").getOrElse(spec.task)
              _ <- ExternalOutputStore.open(sessionId)
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
                    // P1-2：分支纪律四键（非 git 腿 = None——解码缺键容忍不变）
                    branch = binding.map(_.branch),
                    worktree = binding.flatMap(_.worktree),
                    baseBranch = binding.flatMap(_.baseBranch),
                    projectWorkspace = binding.map(_.baseWs),
                    // P2-3 判据面：判定机裁决随记录持久化
                    worktreeJudged = binding.flatMap(_.worktreeJudged),
                    // G1 编排模式初值（delegate-ui 批 2026-10-08）：外部执行器零平台
                    // 工具注入（无 Subagent/AgentFlow 可调）⇒ 恒 single，无升级腿。
                    orchestrationMode = Some(DelegateRegistry.ModeSingle)
                  )
                )
              }
              _ <- recordTaskTolerant(spec.resources, sessionId, spec.parentSessionId, ex.id, spec.task, spec.title)
              _ <- persistUserTurn(spec.resources, sessionId, spec.title, seatedTask)
              // ⑤A 链存储：外部腿 spawn 行（P1-2 起外部腿与内部腿同账——branch/
              // worktree/判定两键齐备；非 git 腿 binding=None，旧行字节不变）。
              _ <- IO
                .blocking(
                  ChainStore.appendSync(
                    ChainStore.keyFor(spec.taskId, sessionId),
                    "delegate-spawn",
                    DelegateWorkspace.spawnPayload(sessionId, ex.id, spec.project, binding)
                  )
                )
                .handleErrorWith(e =>
                  logger.warn(s"ChainStore delegate-spawn line (external) failed: ${e.getMessage}").void
                )
              ack =
                s"""Delegate task started in the background${spec.taskId.map(n => s" (task #$n)").getOrElse("")}.
Executor: ${ex.displayName} (external CLI); workspace: $seatWs${spec.project
                    .map(p => s" (project '$p')")
                    .getOrElse("")}${binding
                    .map(b => s"; branch: ${b.branch}")
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
              _ <- launchTurn(sessionId, ex, seatedSpec, prompt = seatedTask, resumeSid = None)
              _ = logger.info(
                s"External delegate spawned: $sessionId (executor=${ex.id}, workspace=$seatWs${binding.map(b => s", branch=${b.branch}${b.worktree.map(w => s", worktree=$w").getOrElse("")}").getOrElse("")})"
              )
            yield Right((sessionId, ack))

            end for

  /** Mail 续聊腿。live ⇒ mailbox 排队；终态 ⇒ CLI resume 续起（S15 注入通道）。 */
  def continueExternal(
    record: DelegateRegistry.Record,
    message: String,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[Json => IO[Unit]],
    resources: AgentRuntimePort
  ): IO[Either[String, String]] =
    if record.executor == ExecutorRegistry.DefaultId then
      // 前置违例（内部记录误入外部腿）：显式拒绝，不猜路由。
      IO.pure(
        Left(s"Delegate '${record.id}' is an internal (${nebflow.shared.Branding.productName}) record — wrong routing leg. (EXTERNAL_LEG_MISMATCH)")
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
                resources = resources
              )
              ExternalOutputStore.isLive(sid).flatMap { live =>
                if live then
                  ExternalOutputStore.enqueue(sid, message).map { depth =>
                    Right(
                      s"[delegate #$sid] Instance is running — your message is queued in its mailbox (depth $depth) and will be consumed as one continuation turn when the current run finishes."
                    )
                  }
                else
                  resuming.modify { s => if s.contains(sid) then (s, false) else (s + sid, true) }.flatMap { acquired =>
                    if !acquired then
                      ExternalOutputStore
                        .enqueue(sid, message)
                        .map(depth =>
                          Right(
                            s"[delegate #$sid] A continuation turn is starting — your message is queued (depth $depth)."
                          )
                        )
                    else startResumeTurn(record, ex, message, spec).guarantee(resuming.update(_ - sid))
                  }
              }
            case Right(ExecutorRegistry.ResolvedSelection.Internal) =>
              IO.pure(
                Left(
                  s"Delegate '${record.id}' executor '${record.executor}' resolved to the built-in executor — wrong leg. (EXTERNAL_LEG_MISMATCH)"
                )
              )

  /** 终态后续起的 turn：CLI resume（cliSessionId 在手）或带前文的续聊 brief。 */
  private def startResumeTurn(
    record: DelegateRegistry.Record,
    ex: ExternalExecutor.ExternalExecutor,
    message: String,
    spec: SpawnSpec
  ): IO[Either[String, String]] =
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
      // P1-2（引擎加固批 2026-10-09）：resume 轮的 settle 重武装——`settled` 单飞
      // 标记原为「进程生命周期一次」防重入；合并轮（和 mailbox 续聊轮）是同会话的
      // **新** run，其终态必须再判（机械验收闸/父投递都在 settle），不重武装 =
      // 合并轮永不终结（队列窗口悬挂）。
      _ <- settled.update(_ - record.id)
      _ <- ExternalOutputStore.resetForNewRun(record.id)
      _ <- persistUserTurn(spec.resources, record.id, record.title, message)
      _ <- recordTaskTolerant(
        spec.resources,
        record.id,
        record.parentSessionId,
        ex.id,
        message,
        s"continuation: ${record.title}"
      )
      _ <- emitAgentStart(spec.wsSend, record.id, record.parentSessionId, record.parentSessionId, ex, record.title)
      _ <- launchTurn(record.id, ex, spec, prompt = brief, resumeSid = resumeSid)
      _ <- appendLedgerNote(
        record.taskId,
        s"Mail supplement → external resume turn (${ex.id}${resumeSid.fold("")(sid => s" cli=$sid")})"
      )
    yield Right(
      s"[delegate #${record.id}] Instance was not live — your message CONTINUED the external ${ex.displayName} session (resume turn started). Its result is delivered back when it finishes."
    )

  // ── 取消（AgentControl / 面板 cancelAgent 共用；原书：杀进程树）─────────

  def cancel(id: String, reason: String, by: String): IO[Either[String, String]] =
    ExternalOutputStore.kill(id).flatMap {
      case false =>
        IO.pure(Left(s"No live external delegate '$id' to cancel (already settled or never ran on this instance)."))
      case true =>
        cancelRequested
          .update(_ + id)
          .as(
            Right(
              s"Cancel sent for external delegate '$id' — the CLI process tree is being terminated; the parent session receives a cancelled notification."
            )
          )
    }

  // ── turn 执行（子进程 + 流帧 + 终态）──────────────────────────────────

  private def launchTurn(
    sessionId: String,
    ex: ExternalExecutor.ExternalExecutor,
    spec: SpawnSpec,
    prompt: String,
    resumeSid: Option[String]
  ): IO[Unit] =
    IO.delay(buildProcess(ex, spec, prompt, resumeSid))
      .flatMap { pb =>
        IO.blocking {
          val p = pb.start()
          // codex-spawn-fix 批 2026-10-19 死因②：spawn 后立即关闭子进程 stdin——
          // codex exec 发现 stdin 可读就「Reading additional input from stdin...」
          // 永等（引擎会话 40 秒变 orphan 的要因）；exec 模式的 claude/zcode/codex
          // 任务文本全走 argv，零 stdin 依赖，统一关闭。
          sealChildStdin(p)
          // stderr 全量入缓冲（错误面留痕），不入流帧——读取线程 = 守护线程。
          val errThread = new Thread(() =>
            val reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream))
            Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten.foreach { line =>
              ExternalOutputStore.append(sessionId, s"[stderr] $line").unsafeRunSync()
            }
          )
          errThread.setDaemon(true)
          errThread.setName(s"external-stderr-$sessionId")
          errThread.start()
          p
        }.flatMap { p =>
          ExternalOutputStore.attachProcess(sessionId, p) *>
            // 读取循环放后台 fiber——spawn 立即返回（ack 不等进程）。
            readLoop(sessionId, ex, spec, p)
              .handleErrorWith(e =>
                logger.warn(s"External delegate $sessionId read loop crashed: ${e.getMessage}") *>
                  ExternalOutputStore.append(sessionId, s"[read-loop crashed] ${e.getMessage}") *>
                  // fail-closed：读取循环崩溃 ≠ 完成——显式失败帧进 settle（blocked），
                  // 不向可能仍存活的进程要退出码（exitValue 会抛）。
                  settle(
                    sessionId,
                    ex,
                    spec,
                    exitCode = None,
                    finalFrame =
                      Some(ExternalExecutor.Frame.Final(s"read loop crashed: ${e.getMessage}", failed = true))
                  )
              )
              .start
              .void
        }
      }
      .handleErrorWith { e =>
        // 进程建立失败 = fail-closed 终态（blocked），绝不静默回落。
        logger.warn(s"External delegate $sessionId process launch failed: ${e.getMessage}") *>
          ExternalOutputStore.append(sessionId, s"[launch-failed] ${e.getMessage}") *>
          settle(
            sessionId,
            ex,
            spec,
            exitCode = None,
            finalFrame = Some(ExternalExecutor.Frame.Final(s"process launch failed: ${e.getMessage}", failed = true))
          )
      }

  /** 构造 ProcessBuilder（cwd = 工作座位；stdout/stderr 分管）。 */
  private def buildProcess(
    ex: ExternalExecutor.ExternalExecutor,
    spec: SpawnSpec,
    prompt: String,
    resumeSid: Option[String]
  ): java.lang.ProcessBuilder =
    configureProcessBuilder(spec.binPath, spec.workspace, ex.launchArgs(prompt, resumeSid))

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

  /** stdout 读取循环：行 → 缓冲 + 帧解析 → WS；进程退出后 settle。 */
  private def readLoop(
    sessionId: String,
    ex: ExternalExecutor.ExternalExecutor,
    spec: SpawnSpec,
    p: java.lang.Process
  ): IO[Unit] =
    IO.blocking {
      val reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream))
      val lines = Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten
      var finalFrame: Option[ExternalExecutor.Frame.Final] = None
      lines.foreach { line =>
        ExternalOutputStore.append(sessionId, line).unsafeRunSync()
        ex.parseLine(line).foreach {
          case ExternalExecutor.Frame.Start(cliSidOpt) =>
            cliSidOpt.foreach { cliSid =>
              (ExternalOutputStore.setCliSession(sessionId, cliSid) *> updateRecordCliSession(sessionId, cliSid))
                .unsafeRunSync()
            }
          case ExternalExecutor.Frame.Text(t) =>
            emitFrame(spec.wsSend, spec.rootSessionId, spec.parentSessionId, sessionId)(
              Json.obj("type" -> "agentTextDelta".asJson, "agentId" -> sessionId.asJson, "delta" -> t.asJson)
            ).unsafeRunSync()
          case ExternalExecutor.Frame.Tool(label) =>
            emitFrame(spec.wsSend, spec.rootSessionId, spec.parentSessionId, sessionId)(
              Json.obj("type" -> "agentToolStart".asJson, "agentId" -> sessionId.asJson, "label" -> label.asJson)
            ).unsafeRunSync()
          case f: ExternalExecutor.Frame.Final => finalFrame = Some(f)
        }
      }
      finalFrame
    }.flatMap(finalFrame =>
      IO.blocking(p.waitFor()).flatMap(exit => settle(sessionId, ex, spec, Some(exit), finalFrame))
    )

  // ── 终态 settle（三态缝 → 父投递 → 面板帧 → mailbox 消化）───────────────

  private def settle(
    sessionId: String,
    ex: ExternalExecutor.ExternalExecutor,
    spec: SpawnSpec,
    exitCode: Option[Int],
    finalFrame: Option[ExternalExecutor.Frame.Final]
  ): IO[Unit] =
    settled.modify(s => if s.contains(sessionId) then (s, false) else (s + sessionId, true)).flatMap {
      case false => IO.unit // 首个终态胜出（cancel 与自然终态竞态防重入）
      case true =>
        for
          cancelledNow <- cancelRequested.get.map(_.contains(sessionId))
          processFailed = exitCode.exists(_ != 0) || finalFrame.exists(_.failed)
          text = finalFrame.map(_.text).map(_.trim).filter(_.nonEmpty).getOrElse("")
          // 引擎加固批 2026-10-09（P1-2）：判定缝素材补全——git 摘要（工作座位）
          // ＋漂移注记（[[DelegateTerminal.externalDriftNote]]，注记级覆盖）＋合并
          // 轮旗（合并指令经通道注入过的下一拍 settle）。
          rec = DelegateRegistry.find(sessionId)
          gitDigest <- IO
            .blocking {
              Try(os.Path(spec.workspace)).toOption
                .filter(GitWorktrees.isGitRepo)
                .map(ws => GitWorktrees.statusDigest(ws, rec.flatMap(_.baseBranch)))
                .getOrElse("")
            }
            .handleError(_ => "")
          driftNote <- DelegateTerminal.externalDriftNote(rec)
          digest = driftNote.map(n => if gitDigest.isEmpty then n else s"$gitDigest\n$n").getOrElse(gitDigest)
          mergeRoundNow <- mergeRounds.modify(s => (s - sessionId, s.contains(sessionId))) // 读时即清：一旗一轮
          verdict <- ExternalJudgeHook.decide(
            ExternalJudgeHook.Input(
              id = sessionId,
              executor = ex.id,
              workspace = spec.workspace,
              project = spec.project,
              taskId = spec.taskId,
              title = spec.title,
              finalText = text,
              exitCode = exitCode,
              processFailed = processFailed && !cancelledNow,
              gitDigest = digest,
              mergeRound = mergeRoundNow
            )
          )
          outcome0 <- cancelledNow match
            case true => IO.pure(Outcome.Cancelled)
            case false =>
              (verdict, processFailed) match
                // 合并轮**无 ready-to-merge 态**（merge brief 明言）——判定机若在
                // 合并轮回 ready-to-merge = 越权判词，交给机械验收闸定夺（真并了 =
                // completed 放行；没并 = blocked）。绝不回灌队列（防判定机缺陷驱动的
                // 无限重入循环）。
                case (Some(ExternalJudgeHook.Verdict.ReadyToMerge), _) =>
                  if mergeRoundNow then IO.pure(Outcome.Completed) else IO.pure(Outcome.ReadyToMerge)
                case (Some(ExternalJudgeHook.Verdict.Blocked(r)), _) => IO.pure(Outcome.Blocked(r))
                case (Some(ExternalJudgeHook.Verdict.Completed), _) => IO.pure(Outcome.Completed)
                case (None, true) =>
                  IO.pure(
                    Outcome.Blocked(
                      finalFrame
                        .map(_.text)
                        .filter(_.trim.nonEmpty)
                        .getOrElse(
                          s"external CLI exited ${exitCode.map(_.toString).getOrElse("abnormally")} without a success result"
                        )
                    )
                  )
                case (None, false) => IO.pure(Outcome.Completed)
          // P1-1① 外部合并轮同一机械验收闸（绝不凭自申报放行）：合并轮 completed
          // ⇒ 确定性 git 校验；通过 = Release ＋现场回收（P2-4 同闸同守卫）；不
          // 满足 = 判词回落 blocked（报父，零 LLM）。
          outcome <-
            (outcome0, mergeRoundNow) match
              case (Outcome.Completed, true) =>
                IO.blocking(MergeAcceptance.verifyRecord(rec))
                  .flatMap { r =>
                    if r.ok then PostMergeCleanup.run(rec).as(Outcome.Completed)
                    else
                      IO.pure(
                        Outcome.Blocked(
                          s"mechanical merge acceptance refused the release (the CLI claimed a completed merge): ${r.evidence}"
                        )
                      )
                  }
                  .handleErrorWith(e =>
                    IO.pure(Outcome.Blocked(s"mechanical merge acceptance unreadable: ${e.getMessage}"))
                  )
              case (other, _) => IO.pure(other)
          // 引擎原生 delegate worktree 自动合并批（#524）：外部腿 completed 终态 ×
          // 非合并轮 ⇒ 与内部腿同闸（[[DelegateAutoMerge.onCompletedHook]]，引擎侧
          // FIFO `merge --no-ff` 回集成分支）。外部执行器经 DelegateWorkspace.prepare
          // 同样有分支锚——能拿到分支就纳入（作者令边界条款）；锚缺失（非 git 腿 /
          // 旧盘记录）= 注记跳过、诚实可查。合并轮豁免（外部合并轮的机械验收闸在
          // 上方 P1-1①）。
          autoMergeNote <-
            (outcome, mergeRoundNow) match
              case (Outcome.Completed, false) =>
                rec match
                  case Some(r) if r.branch.isDefined => DelegateAutoMerge.onCompletedHook(rec)
                  case _ =>
                    IO.pure(
                      Some("\n\n[auto-merge] skipped (no branch anchors on record) — branch behaviour not evaluated")
                    )
              case _ => IO.pure(None)
          reasonOpt = outcome match
            case Outcome.Blocked(r) => Some(r)
            case Outcome.Cancelled => Some("cancelled")
            case _ => None
          // 进度面终态（3.4 词表：blocked；ready-to-merge 在缓冲面记 completed——
          // 进程本身成功了，合并轮语义归 #1224 队列）。
          _ <- ExternalOutputStore.finalizeTask(
            sessionId,
            status = outcome match
              case Outcome.Blocked(_) => "blocked"
              case Outcome.ReadyToMerge | Outcome.Completed => "completed"
              case Outcome.Cancelled => "cancelled",
            exitCode = exitCode,
            errorHint = reasonOpt
          )
          // 会话持久化：assistant 终稿（空输出也给占位，历史面完整）。
          _ <- persistAssistantTurn(spec.resources, sessionId, if text.nonEmpty then text else "(no text output)")
          // 任务行终态（ready-to-merge 保持 running——父回报权在合并轮）。
          _ <- outcome match
            case Outcome.Completed =>
              updateTaskTolerant(spec.resources, spec.parentSessionId, sessionId, "completed", lastError = None)
            case Outcome.ReadyToMerge => IO.unit
            case Outcome.Cancelled =>
              updateTaskTolerant(spec.resources, spec.parentSessionId, sessionId, "cancelled", lastError = reasonOpt)
            case Outcome.Blocked(_) =>
              updateTaskTolerant(spec.resources, spec.parentSessionId, sessionId, "failed", lastError = reasonOpt)
          _ <- appendLedgerNote(
            spec.taskId,
            outcome match
              case Outcome.Completed =>
                s"External delegate finished (executor=${ex.id}, exit=${exitCode.map(_.toString).getOrElse("-")})"
              case Outcome.ReadyToMerge =>
                s"External delegate ready-to-merge (judge; executor=${ex.id}) — enqueued to the merge queue"
              case Outcome.Cancelled => s"External delegate cancelled (executor=${ex.id})"
              case Outcome.Blocked(r) => s"External delegate blocked (executor=${ex.id}): $r"
          )
          // P1-2：ready-to-merge 并入 MergeQueue（外部通道条目——合并轮经续聊通道
          // 注入、settle 终态再判）。锚缺失（非 git 腿误判 ready-to-merge / 记录
          // 缺键）= 诚实降级：WARN ＋ completed 报父（绝不静默挂起）。
          _ <- outcome match
            case Outcome.ReadyToMerge =>
              rec.filter(r => r.branch.isDefined && r.baseBranch.isDefined) match
                case Some(r) =>
                  val baseWs = r.projectWorkspace.getOrElse(r.cwd)
                  val channel = MergeQueue.ExternalLeg { msg =>
                    noteMergeRound(sessionId) *>
                      continueExternal(
                        r,
                        msg,
                        parentRef = spec.parentRef,
                        wsSend = spec.wsSend,
                        resources = spec.resources
                      )
                        .map(_.map(_ => ()))
                  }
                  MergeQueue.enqueueIO(
                    MergeQueue.Entry(
                      delegateId = sessionId,
                      branch = r.branch.get,
                      baseWs = baseWs,
                      baseBranch = r.baseBranch.get,
                      worktree = r.worktree.isDefined,
                      projectKey = Try(os.Path(baseWs)).map(GitWorktrees.projectKeyOf).getOrElse(baseWs),
                      taskId = r.taskId,
                      parentSessionId = r.parentSessionId,
                      description = r.title,
                      readyAt = System.currentTimeMillis(),
                      childRef = MergeQueue.NoopChild,
                      supervisorRef = MergeQueue.NoopSupervisor,
                      external = Some(channel)
                    )
                  )
                case None =>
                  logger.warn(
                    s"external delegate $sessionId judged ready-to-merge but the record lacks branch anchors — " +
                      s"falling back to a completed parent report (nothing enqueued)"
                  ) *> notifyParent(
                    spec,
                    sessionId,
                    Outcome.Completed,
                    s"$text\n\n[delegate-judge] ready-to-merge could not be scheduled (no branch anchors on record) — reported completed",
                    None
                  )
            case _ => IO.unit
          // P1-2/P2-4（引擎加固批 2026-10-09）：外部腿**终态出队**（内部腿同语义 =
          // DelegateTerminal.onTerminalCleanup 的出队臂）。合并轮终态（机械验收
          // 拒绝 blocked / 放行 completed）后条目必须出队＋单飞行窗释放——否则窗
          // 泄漏（同项目后继全楔死）且账本无 `dequeued` 后继行，下次 boot sweep
          // 会把已终结成员误判为中断成员重挂。ReadyToMerge = 刚入队，出队权归
          // 合并轮。未入队过（completed/cancelled 首轮终态）= onTerminal 幂等 no-op。
          _ <- outcome match
            case Outcome.ReadyToMerge => IO.unit
            case other => MergeQueue.onTerminalIO(sessionId)
          // 父投递（ready-to-merge 不发——MergeQueue 持有父回报权）。#524：
          // auto-merge 尾注并入终文——冲突挂起的文件清单随终态抵达父会话。
          _ <- outcome match
            case Outcome.ReadyToMerge => IO.unit
            case other =>
              notifyParent(
                spec,
                sessionId,
                other,
                autoMergeNote.map(n => s"$text$n").getOrElse(text),
                reasonOpt
              )
          // 面板终态帧（与内部 delegate 的 agentDone 同契约）。
          _ <- emitFrame(spec.wsSend, spec.rootSessionId, spec.parentSessionId, sessionId)(
            Json.obj("type" -> "agentDone".asJson, "agentId" -> sessionId.asJson)
          )
          _ = logger.info(
            s"External delegate settled: $sessionId outcome=$outcome exit=${exitCode.map(_.toString).getOrElse("-")}${
                if mergeRoundNow then " (merge round)" else ""
              }"
          )
          // mailbox 消化：live 期间的来信 = 一条 resume 续聊（S15 同通道）。
          _ <- outcome match
            case Outcome.Cancelled => IO.unit // 取消即终止——来信留队列由下轮续聊取
            case _ =>
              ExternalOutputStore.drainMailbox(sessionId).flatMap {
                case q if q.isEmpty => IO.unit
                case q =>
                  DelegateRegistry.find(sessionId) match
                    case None => IO.unit
                    case Some(rec) =>
                      val joined = q.mkString("\n\n[queued message]\n\n")
                      continueExternal(rec, joined, spec.parentRef, spec.wsSend, spec.resources).void
                      // 回执（queued/续起）丢弃——父已被终态通知，消化是后台续轮。
              }
        yield ()
    }

  /** settle 的终态词汇（三态判词 ＋ cancelled 的 runner 内部形态）。 */
  private enum Outcome:
    case Completed
    case Blocked(reason: String)
    case ReadyToMerge
    case Cancelled

  /**
   * 父会话投递（ExternalEvent source="delegate"——与内部 delegate 同一回投链，
   * 同一 barrier 释放语义）。
   */
  private def notifyParent(
    spec: SpawnSpec,
    sessionId: String,
    outcome: Outcome,
    text: String,
    reasonOpt: Option[String]
  ): IO[Unit] =
    spec.parentRef match
      case None =>
        // mail-continuation delivery backstop（2026-10-09，与 BackoffSupervisor 同款
        // 纪律）：parentRef=None ⇒ 终态结果无投递对象——历史分支是裸 IO.unit（静默
        // 丢）。 绝不静默：响亮 WARN（带 payload 头部）。taskStore 状态写入与
        // agentDone 面板帧照常（上方），终态仍可查。
        logger.warn(
          s"[terminal-result-undelivered] external delegate session '$sessionId' (${spec.executorId}) reached its terminal state " +
            s"but no parent ref exists (parent session '${spec.parentSessionId}' not live at spawn) — the result was NOT delivered to any session. " +
            s"Payload head: ${text.take(500)}"
        )
      case Some(parent) =>
        val (eventType, payload, failureMetadata) = outcome match
          case Outcome.Completed =>
            val body = if text.nonEmpty then s""""${spec.title}":\n$text""" else s""""${spec.title}" (no text output)"""
            ("completed", body, io.circe.JsonObject.empty)
          case Outcome.Cancelled =>
            (
              "cancelled",
              s""""${spec.title}": cancelled — ${reasonOpt.getOrElse("external CLI process terminated")}""",
              io.circe.JsonObject(
                "failedSessionId" -> sessionId.asJson,
                "retryable" -> false.asJson,
                "failureType" -> "cancelled".asJson,
                "cancelled" -> true.asJson,
                "reason" -> reasonOpt.getOrElse("").asJson
              )
            )
          case Outcome.Blocked(_) => // ⑬：经三态 blocked 上报，无旁路上送、无 AgentFrozen 面
            (
              "failed",
              s""""${spec.title}": blocked — ${reasonOpt.getOrElse("external executor failed")}""",
              io.circe.JsonObject(
                "failedSessionId" -> sessionId.asJson,
                "retryable" -> false.asJson,
                "failureType" -> "blocked".asJson,
                "reason" -> reasonOpt.getOrElse("").asJson
              )
            )
          case Outcome.ReadyToMerge => ("completed", s""""${spec.title}" (merge pending)""", io.circe.JsonObject.empty)
        (parent ! AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = eventType,
          payload = payload,
          metadata = io.circe
            .JsonObject("description" -> spec.title.asJson, "agentName" -> spec.executorId.asJson)
            .deepMerge(failureMetadata),
          correlationId = Some(sessionId)
        )).void
          .handleErrorWith(e => logger.warn(s"External delegate parent notify failed: ${e.getMessage}"))

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
            val withSession =
              if withRoot.contains("sessionId") then withRoot
              else withRoot.add("sessionId", parentSessionId.asJson)
            val withNode =
              if withSession.contains("nodeSessionId") then withSession
              else withSession.add("nodeSessionId", sessionId.asJson)
            Json.fromJsonObject(withNode)
          case None => frame
        send(routed).handleErrorWith(e => logger.warn(s"External delegate WS frame failed: ${e.getMessage}"))

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

  /** SessionStore 持久会话：确保存在 + 追加 user 消息（source=delegate）。 */
  private def persistUserTurn(
    resources: AgentRuntimePort,
    sessionId: String,
    title: String,
    userText: String
  ): IO[Unit] =
    val store = resources.sessionStore
    store
      .getOrCreateSession(sessionId, title)
      .flatMap { _ =>
        store
          .loadMessagesForSession(sessionId)
          .map(_ :+ Message(MessageRole.User, Left(userText), source = Some("delegate")))
      }
      .flatMap(msgs => store.saveMessagesForSession(sessionId, msgs))
      .handleErrorWith(e => logger.warn(s"External session persist failed for $sessionId: ${e.getMessage}"))

  end persistUserTurn

  private def persistAssistantTurn(resources: AgentRuntimePort, sessionId: String, text: String): IO[Unit] =
    val store = resources.sessionStore
    store
      .loadMessagesForSession(sessionId)
      .map(_ :+ Message(MessageRole.Assistant, Left(text), source = Some("delegate")))
      .flatMap(msgs => store.saveMessagesForSession(sessionId, msgs))
      .handleErrorWith(e => logger.warn(s"External assistant persist failed for $sessionId: ${e.getMessage}"))

  private def priorFinalText(resources: AgentRuntimePort, sessionId: String): IO[String] =
    resources.sessionStore
      .loadMessagesForSession(sessionId)
      .map(msgs =>
        msgs.reverse.collectFirst { case m if m.role == MessageRole.Assistant => m.textContent }.getOrElse("")
      )
      .handleError(_ => "")

  /** 任务行记录（fail-soft——任务行失败不阻断派发，同内部口径）。 */
  private def recordTaskTolerant(
    resources: AgentRuntimePort,
    taskId: String,
    parentSessionId: String,
    agentName: String,
    prompt: String,
    description: String
  ): IO[Unit] =
    resources.subAgentTaskStore
      .recordTask(
        SubAgentTask(
          taskId = taskId,
          parentSessionId = parentSessionId,
          agentName = agentName,
          prompt = prompt,
          description = description,
          status = "running",
          retryCount = 0,
          spawnedAt = System.currentTimeMillis(),
          completedAt = None,
          lastError = None,
          source = "delegate"
        )
      )
      .handleErrorWith(e => logger.warn(s"External taskStore.recordTask failed: ${e.getMessage}"))

  private def updateTaskTolerant(
    resources: AgentRuntimePort,
    parentSessionId: String,
    taskId: String,
    status: String,
    lastError: Option[String]
  ): IO[Unit] =
    resources.subAgentTaskStore
      .updateStatus(
        parentSessionId,
        taskId,
        status,
        lastError = lastError,
        completedAt = Some(System.currentTimeMillis())
      )
      .handleErrorWith(e => logger.warn(s"External taskStore.updateStatus failed: ${e.getMessage}"))

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
