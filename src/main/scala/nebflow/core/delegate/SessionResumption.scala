package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.{ActorRef, ActorSystem, AgentEvent, AgentKind}
import nebflow.core.executor.ExecutorRegistry
import nebflow.core.ResumeHandoff
import nebflow.core.node.NodeRunner
import nebflow.core.project.DelegateFlowHosts
import nebflow.shared.{ContentBlock, NebflowLogger, SubAgentTask}

/**
 * Same-identity continuation for a completed or idle delegate with verified history.
 * Mail never creates a replacement identity. Cancellation, missing history and unknown
 * tool outcomes are explicit refusals; frozen and restart-interrupted sessions wait
 * for manual recovery. Historical scope migrations remain readable for diagnostics.
 */
object SessionResumption:

  private final class AdmissionRejected(message: String) extends IllegalStateException(message)

  private val logger = NebflowLogger.forName("nebflow.session-resumption")

  // ══════════════════════════════════════════════════════════════════════════
  // A. 显示名（单点剥离 + 单点取名）
  // ══════════════════════════════════════════════════════════════════════════

  /** 续命标记词（机械断言的判据词；显示名里出现即视为未归一）。 */
  val ContinuationMark: String = "continuation"

  /** 显示名回落长度（无 title 时取首条真实指令前 N 字）。 */
  val FallbackTitleChars: Int = 48

  private val ColonPrefix = "continuation:"
  private val BracketPrefix = "[continuation"
  private val OfDelegatePrefix = "continuation of delegate"

  /**
   * 剥掉**所有前导**续命段（循环剥离 ⇒ 历史叠加的 N 代前缀一次清空）。
   *
   * 剥法三形态（历史实际出现过的全部形态）：
   *   ① `continuation: ` —— 旧派生腿的 description 前缀（叠加源）；
   *   ② `[continuation of delegate <id> — …]` —— 续命 brief 的方括号段（整段丢）；
   *   ③ `continuation of delegate <id>` 无方括号形态 —— 剥头 + id 词 + 紧随的
   *      分隔说明（到行尾/方括号/破折号为止）。
   *
   * 幂等：`strip(strip(x)) == strip(x)`。剥空则返回**空串**（剥干净了就是没有内容
   * ——回退语义由 [[displayTitle]] 的 fallback 承担，本函数只忠实报告剥离结果）。
   */
  def stripContinuationPrefix(raw: String): String =
    val original = Option(raw).getOrElse("").trim
    var cur = original
    var changed = true
    var guard = 0
    while changed && guard < 32 do
      guard += 1
      changed = false
      val lower = cur.toLowerCase
      if lower.startsWith(ColonPrefix) then
        cur = cur.drop(ColonPrefix.length).trim
        changed = true
      else if lower.startsWith(BracketPrefix) then
        val close = cur.indexOf(']')
        if close >= 0 then
          cur = cur.drop(close + 1).trim
          changed = true
      else if lower.startsWith(OfDelegatePrefix) then
        // `continuation of delegate <id>` + 可能的说明段（— 说明 / : 说明）
        val rest0 = cur.drop(OfDelegatePrefix.length).trim
        // 丢掉紧随的 id 词
        val rest1 = if rest0.isEmpty then "" else rest0.dropWhile(c => !c.isWhitespace).trim
        // 说明段：若以分隔符开头，剥到该段结束（方括号 / 换行 / 分号）
        val rest2 =
          if rest1.isEmpty then rest1
          else if rest1.startsWith("—") || rest1.startsWith("-") || rest1.startsWith(":") || rest1.startsWith(";")
          then
            val cut = List(rest1.indexOf(']'), rest1.indexOf('\n')).filter(_ >= 0)
            if cut.nonEmpty then rest1.drop(cut.min + 1).trim
            else rest1.dropWhile(c => c != '\n' && c != ']').drop(1).trim
          else rest1
        if rest2.nonEmpty then
          cur = rest2
          changed = true
      end if
    end while
    cur

  end stripContinuationPrefix

  /**
   * 单点取名：任务账 title 的去叠加形态；剥空 ⇒ fallback（首条真实指令前 N 字 /
   * 批次短名 / 会话 id，由调用方给）。
   */
  def displayTitle(raw: String, fallback: String = ""): String =
    val clean = stripContinuationPrefix(raw)
    if clean.nonEmpty then clean
    else
      val fb = Option(fallback).getOrElse("").trim
      if fb.nonEmpty then fb.take(FallbackTitleChars) else Option(raw).getOrElse("").trim

  /** delegate 记录的显示名（任务账 title → 批次短名 → 会话 id 三档）。 */
  def displayTitle(record: DelegateRegistry.Record): String =
    displayTitle(record.title, record.name.filter(_.trim.nonEmpty).getOrElse(record.id))

  /** 显示名是否仍带续命标记（机械断言的判据；干净名恒 false）。 */
  def hasContinuationMark(name: String): Boolean =
    Option(name).exists(_.toLowerCase.contains(ContinuationMark))

  // ══════════════════════════════════════════════════════════════════════════
  // B. Same identity or explicit refusal
  // ══════════════════════════════════════════════════════════════════════════

  /** 续命位形判定。 */
  enum Verdict:
    /** 原位续：沿用原 sessionId（转录追加、链节点归属不变）。 */
    case InPlace(parentSessionId: String, transcriptMessages: Int)

    /** Preserve the identity and report why continuation cannot safely run. */
    case Refuse(reason: String)

  object Verdict:

    def isInPlace(v: Verdict): Boolean = v match
      case InPlace(_, _) => true
      case _ => false

    def reasonOf(v: Verdict): Option[String] = v match
      case Refuse(r) => Some(r)
      case _ => None

  /**
   * 判定单点（作者 16:51/16:53 裁定口径）：
   *   - **cancel 墓碑在场 ⇒ 拒绝**（取消意图持久在案，绝不原位复活——与
   *     [[nebflow.agent.DelegateRevival.eligibility]] 的墓碑闸同源语义）；
   *   - **转录读不出来 ⇒ 拒绝**（无现场可重放，原位续就是空壳）；
   *   - 历史完整时可判原位续；执行前还须核验手动恢复闸、工具结果和工作区占用。
   *
   * 外部执行器不进本判定（其续聊走 `ExternalSessionRunner.continueExternal`，
   * 自带 CLI session 续接面）。
   */
  def judge(
    record: DelegateRegistry.Record,
    transcriptLoadError: Option[String],
    transcriptMessages: Int
  ): Verdict =
    if record.cancelRequestedAt.isDefined then
      Verdict.Refuse(
        s"cancel tombstone present (cancelled by ${record.cancelRequestedBy.getOrElse("?")} at " +
          s"${record.cancelRequestedAt.get}) — a cancelled session is never resumed in place"
      )
    else if transcriptLoadError.isDefined then
      Verdict.Refuse(s"transcript unreadable (${transcriptLoadError.get}) — nothing to replay in place")
    else if transcriptMessages == 0 then Verdict.Refuse("Saved history is missing (RECOVERY_UNVERIFIED)")
    else Verdict.InPlace(record.parentSessionId, transcriptMessages)

  /** 续命运行期依赖面（与 boot 复活腿 [[nebflow.agent.DelegateRevival.Deps]] 同形）。 */
  final case class Deps(
    resources: nebflow.core.AgentRuntimePort,
    system: ActorSystem,
    wsSend: Option[Json => IO[Unit]]
  )

  /** 原位续命的结果读数（Mail 回执与审计行的数据源）。 */
  final case class InPlaceOutcome(
    delegateId: String,
    parentSessionId: String,
    transcriptMessages: Int,
    displayTitle: String,
    parentLive: Boolean,
    scopeMounted: Boolean
  )

  /**
   * **原位续命本体**：同一 sessionId 重建执行窗口。
   *
   * 与 boot 复活腿（[[nebflow.agent.DelegateRevival]]）同源的七步，但**零预算记账**
   * （复活预算属于崩溃恢复域，Mail 续命不消耗它）：
   *   ① 任务行翻 running（行在才动；行缺 = 静默跳过——Mail 续命不凭空造行）；
   *   ② 转录 checkpoint 读盘作为 `initialMessages`（**上下文到场**：空历史起跑会让
   *      首个回合收尾的 save 把盘上历史整段覆盖成「一条新输入」）；
   *   ③ 父会话活引用（结果回投锚点，判读同续聊腿）；
   *   ④ 同 id spawn + 监督者 + registerAgent（`rootSessionId` = 父会话 ⇒ 链节点/
   *      归因面与原会话逐字节同源）；
   *   ⑤ 作用域宿主**同 id 重挂**——`DelegateFlowHosts.mount(delegateId = 原 id)`
   *      重开同一张 `<base>/.nebflow/flow-maps/<id>/` ⇒ 全部原有节点原地可见
   *      （这是作者 16:51「下面的节点依赖它」的结构性答案）；
   *   ⑥ 预算重挂（结构同位，机制现值 no-op）；
   *   ⑦ ChainStore `delegate-resume-in-place` 审计行 + 摘重启打断旗 + 输入注入。
   *
   * 失败返回Left，原身份、历史和作用域保留；不派生替代会话。
   */
  /** Shared with Mail: supplements never release a freeze or a saved restart gate. */
  def requiresManual(
    record: Option[DelegateRegistry.Record],
    task: Option[nebflow.shared.SubAgentTask],
    live: Option[nebflow.actor.AgentRecord],
    workspace: Option[WorkspaceLedger.Workspace],
    initializationPending: Boolean = false
  ): Boolean =
    record.exists(r =>
      r.frozenSince.nonEmpty || r.frozenReason.nonEmpty || r.restartInterruptedAt.nonEmpty || r.cancelRequestedAt.nonEmpty
    ) ||
      task.exists(t => Set("pending-recovery", "frozen").contains(t.status)) ||
      live.exists(r => r.status == nebflow.actor.AgentStatus.Frozen || r.frozenReason.nonEmpty) ||
      workspace.exists(_.activity == "frozen") || initializationPending

  def resumeInPlace(
    deps: Deps,
    record: DelegateRegistry.Record,
    message: String,
    blocks: Option[List[ContentBlock]] = None
  ): IO[Either[String, InPlaceOutcome]] =
    MergerInitialization
      .exclusive(record.id)(resumeInPlaceLocked(deps, record, message, blocks))
      .handleError(e => Left(s"Continuation could not safely start: ${e.getMessage}"))

  /** Caller already holds the same session-start lease as initialization and manual revival. */
  private[nebflow] def resumeInPlaceLocked(
    deps: Deps,
    record: DelegateRegistry.Record,
    message: String,
    blocks: Option[List[ContentBlock]] = None
  ): IO[Either[String, InPlaceOutcome]] =
    for
      latest <- IO.blocking(DelegateRegistry.find(record.id))
      task <- deps.resources.subAgentTaskStore.findByTaskId(record.id)
      live <- deps.resources.agentRegistry.get.map(_.get(record.id))
      ledger <- IO.blocking(WorkspaceLedger.default.snapshot)
      result <- latest match
        case None => IO.pure(Left("Delegate identity is missing; inspect the saved context"))
        case Some(current) if current.cancelRequestedAt.nonEmpty =>
          IO.pure(Left("Delegate was manually cancelled; create new work explicitly (DELEGATE_CANCELLED)"))
        case Some(current)
            if requiresManual(
              Some(current),
              task,
              live,
              ledger.workspaces.find(_.owner == current.id),
              ledger.mergerBootstraps.get(current.id).exists(_.status != "ready")
            ) =>
          IO.pure(Left("Continuation is saved behind a manual recovery gate (MANUAL_RECOVERY_REQUIRED)"))
        case Some(current) =>
          live.fold(IO.pure(false))(entry => deps.system.isAlive(entry.ref.path)).flatMap {
            case true => IO.pure(Left("Session is already live; deliver the supplement to its existing actor"))
            case false => resumeAdmitted(deps, current, message, blocks)
          }
    yield result

  private def resumeAdmitted(
    deps: Deps,
    record: DelegateRegistry.Record,
    message: String,
    blocks: Option[List[ContentBlock]]
  ): IO[Either[String, InPlaceOutcome]] =
    val id = record.id
    val title = displayTitle(record)
    cats.effect.Ref.of[IO, IO[Unit]](IO.unit).flatMap { cleanup =>
      val attempt: IO[Either[String, InPlaceOutcome]] =
        for
          taskOpt <- deps.resources.subAgentTaskStore.findByTaskId(id).attempt.map(_.toOption.flatten)
          parentSid = taskOpt
            .map(_.parentSessionId)
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse(record.parentSessionId.trim)
          // ② 转录到场（读盘失败 = 明确拒绝，保留原身份）。
          transcriptAttempt <- deps.resources.sessionStore.loadMessagesForSession(id).attempt
          result <- transcriptAttempt match
            case Left(err) =>
              IO.pure(Left(s"transcript read failed for '$id': ${err.getMessage}"))
            case Right(transcript)
                if transcript.isEmpty || nebflow.core.ResumeHandoff.hasUnresolvedToolCalls(transcript) =>
              IO.pure(Left("Saved history or a tool outcome is unknown (RECOVERY_UNVERIFIED)"))
            case Right(transcript) =>
              for
                _ <- IO
                  .blocking(WorkspaceLedger.default.admit(id, allowFrozen = false))
                  .flatMap(_.fold(error => IO.raiseError[Unit](new AdmissionRejected(error)), _ => IO.unit))
                // ① 行先翻 running（复活半途再崩 ⇒ 下一 boot 看到诚实的 running 行）。
                _ <- IO.whenA(parentSid.nonEmpty)(
                  deps.resources.subAgentTaskStore
                    .updateStatus(parentSid, id, "running")
                    .handleErrorWith(e =>
                      logger.warn(s"[session-resume] task-row flip failed for $id: ${e.getMessage}")
                    )
                )
                // ③ 父活引用。
                parentRef <-
                  if parentSid.isEmpty then IO.pure(None)
                  else deps.resources.agentRegistry.get.map(_.get(parentSid).map(_.ref))
                baseDef <- IO.blocking(
                  nebflow.core.entity.BuiltinAgents
                    .delegateEntry(nebflow.core.entity.BuiltinAgents.ExecutorName)
                    .map(_.toAgentDef)
                    .get
                )
                effectiveDef <- nebflow.agent.DelegateRevival.regrantPlugins(deps.resources, id, record, baseDef)
                // ②' 续跑交接单（无感恢复批 2026-11-07）：断点停在工具调用中途 ⇒ 模型
                //    收到那次调用的 tool_result（不是一条系统提示）；其余形态逐字回落
                //    既有文案。单点 = [[nebflow.core.ResumeHandoff]]。
                handoff = ResumeHandoff.plan(
                  transcript = transcript,
                  cause = ResumeHandoff.Cause.Continuation,
                  instruction = message,
                  legacyText = s"[system] A supplement arrived while no live actor was holding this session. " +
                    s"Your full history (${transcript.size} transcript messages) is loaded above — this is the SAME session " +
                    s"(id unchanged, the batch's chain nodes still belong to it). Treat the following as your next input; " +
                    s"do not re-plan from scratch and do not redo finished work.\n\n$message"
                )
                childWsSend = nebflow.core.tools.DelegateTool.routeWsSendFor(id, Some(parentSid), deps.wsSend)
                params = NodeRunner.SpawnParams(
                  agentDef = effectiveDef,
                  resources = deps.resources,
                  sessionId = id, // 🔴 原位：会话身份不变（转录/地址/链归属同一身份）
                  sessionName = title, // A 部分：显示名 = 剥离后的任务账 title（逐代稳定）
                  depth = 1,
                  parentRef = parentRef,
                  wsSend = childWsSend,
                  projectRoot = Some(record.cwd),
                  rootSessionId = if parentSid.nonEmpty then parentSid else id,
                  initialMessages = handoff.transcript
                )
                subagentRef <- deps.resources.spawnAgentActor(deps.system, params)
                _ <- cleanup.update(previous =>
                  deps.system.stop(subagentRef).attempt.void *>
                    deps.resources.agentRegistry.update(m =>
                      m.get(id)
                        .filter(_.ref.path == subagentRef.path)
                        .fold(m)(_ => m - id)
                    ) *> previous
                )
                adapterRef <- deps.resources.spawnSupervisedAdapter(
                  system = deps.system,
                  params = params,
                  childRef = subagentRef,
                  childName = id,
                  description = title,
                  agentName = nebflow.core.entity.BuiltinAgents.ExecutorName,
                  subagentId = id,
                  parentSessionId = parentSid,
                  initialPrompt = message,
                  source = "delegate",
                  extraMetadata = JsonObject("resumedInPlace" -> true.asJson),
                  wsSend = Some(childWsSend)
                )
                _ <- cleanup.update(previous => deps.system.stop(adapterRef).attempt.void *> previous)
                _ <- NodeRunner.registerAgent(
                  deps.resources,
                  id = id,
                  ref = subagentRef,
                  kind = AgentKind.Delegate,
                  rootSessionId = if parentSid.nonEmpty then parentSid else id,
                  parentRef = parentRef,
                  startedAt = System.currentTimeMillis(),
                  lastActivityMs = System.currentTimeMillis(),
                  supervisorRef = Some(adapterRef),
                  parentSessionId = parentSid
                )
                // ⑤ 作用域宿主同 id 重挂（幂等；重开同一张图 ⇒ 节点归属不变）。
                mounted <- DelegateFlowHosts
                  .mount(
                    delegateId = id,
                    project = record.project,
                    baseWorkspace = record.projectWorkspace.getOrElse(record.cwd),
                    engineCwd = record.cwd,
                    system = deps.system,
                    resources = deps.resources,
                    wsSend = Some(childWsSend)
                  )
                  .handleErrorWith(e =>
                    logger
                      .warn(s"[session-resume] flow-host remount failed for $id: ${e.getMessage}")
                      .as(None)
                  )
                // ⑥ 预算重挂（respawn 同款结构同位）。
                _ <- nebflow.agent.DelegateBudget.register(id)(adapterRef ! AgentEvent.Cancelled(id, "timeout"))
                // ⑦a 审计行（#1228 链视图可见；payload 自带位形词）。
                _ <- IO
                  .blocking(
                    ChainStore.appendSync(
                      ChainStore.keyFor(record.taskId, id),
                      "delegate-resume-in-place",
                      Json.obj(
                        "action" -> "resumed-in-place".asJson,
                        "delegateId" -> id.asJson,
                        "sessionId" -> id.asJson,
                        "displayTitle" -> title.asJson,
                        "transcriptMessages" -> transcript.size.asJson,
                        "parentSessionId" -> parentSid.asJson,
                        "parentLive" -> parentRef.isDefined.asJson,
                        "scopeMounted" -> mounted.isDefined.asJson,
                        "workspace" -> record.cwd.asJson
                      )
                    )
                  )
                  .handleErrorWith(e =>
                    logger.warn(s"[session-resume] ChainStore row failed for $id: ${e.getMessage}").void
                  )
                // ⑦b 重启打断旗摘除（恢复 = 打断结束；幂等零写）。
                _ <- IO
                  .blocking(DelegateRegistry.clearRestartInterrupted(id))
                  .handleErrorWith(e =>
                    logger.warn(s"[session-resume] restart-flag clear failed for $id: ${e.getMessage}").void
                  )
                // ⑦b' 待命槽清零（案A 唤醒语义对齐）：待命停靠的记录被 Mail 续醒 =
                // 唤醒轮——申报槽与停靠槽一并清（[[DelegateRegistry.clearStandby]]，
                // 与 live 注入腿同款判据；唤醒轮无新申报走矩阵未申报腿提醒补报）。
                _ <- IO.whenA(record.standbySince.isDefined)(
                  IO
                    .blocking(DelegateRegistry.clearStandby(id))
                    .handleErrorWith(e =>
                      logger.warn(s"[session-resume] standby clear failed for $id: ${e.getMessage}")
                    )
                    .void
                )
                // ⑦b'' 归档回流：活会话必须在在册窗可见（归档只加旗不删行——复活即摘旗，
                // 否则面板在历史窗里显示一个正在跑的会话）。
                _ <- IO.whenA(record.archivedAt.isDefined)(
                  IO
                    .blocking(DelegateRegistry.unarchive(id))
                    .handleErrorWith(e => logger.warn(s"[session-resume] unarchive failed for $id: ${e.getMessage}"))
                    .void
                )
                // ⑦c 输入注入：**转录在下、补充在上**——同一会话的下一轮。
                // 无感恢复批 2026-11-07：载荷由 [[ResumeHandoff]] 出（上方 ②'），本处只投递。
                _ <- IO(ResumeHandoff.logPlan(id, handoff, ResumeHandoff.Cause.Continuation))
                input = ResumeHandoff.userInput(handoff, replyTo = Some(adapterRef))
                withMedia = input.copy(blocks =
                  blocks
                    .map(media =>
                      handoff.injectBlocks.getOrElse(List(ContentBlock.Text(handoff.injectText))) ++
                        media.filterNot(_.isInstanceOf[ContentBlock.Text])
                    )
                    .orElse(input.blocks)
                )
                _ <- subagentRef ! withMedia
                _ <- logger.info(
                  s"[session-resume] resumed IN PLACE $id (transcript=${transcript.size} msgs, parentLive=${parentRef.isDefined}, " +
                    s"scopeMounted=${mounted.isDefined}, title='$title') — same session id, chain ownership untouched"
                )
              yield Right(
                InPlaceOutcome(
                  delegateId = id,
                  parentSessionId = parentSid,
                  transcriptMessages = transcript.size,
                  displayTitle = title,
                  parentLive = parentRef.isDefined,
                  scopeMounted = mounted.isDefined
                )
              )
        yield result

      val failed = cleanup.get.flatten *> DelegateFlowHosts.unmount(id) *>
        IO.blocking(
          DelegateRegistry.parkFrozen(
            id,
            DelegateFreeze.wireValue(DelegateFreeze.Reason.EngineFault),
            "Continuation startup failed; inspect saved context and recover manually"
          )
        ).attempt
          .void *>
        IO.blocking(DelegateRegistry.unarchive(id)).attempt.void *>
        deps.resources.subAgentTaskStore
          .findByTaskId(id)
          .flatMap {
            case Some(task) => deps.resources.subAgentTaskStore.updateStatus(task.parentSessionId, id, "frozen")
            case None =>
              deps.resources.subAgentTaskStore.recordTask(
                SubAgentTask(
                  taskId = id,
                  parentSessionId = record.parentSessionId,
                  agentName = record.executor,
                  prompt = message,
                  description = title,
                  status = "frozen",
                  retryCount = 0,
                  spawnedAt = record.createdAt,
                  completedAt = None,
                  lastError = Some("Continuation startup failed; manual recovery required"),
                  source = "delegate",
                  mode = record.mode
                )
              )
          }
          .attempt
          .void *>
        IO.blocking(WorkspaceLedger.default.settle(id, frozen = true))
      attempt
        .onError { case _: AdmissionRejected => IO.unit; case _ => failed }
        .onCancel(failed)
        .handleErrorWith(e =>
          logger
            .warn(s"[session-resume] in-place resume errored for $id: ${e.getMessage}")
            .as(Left(s"resume error: ${e.getMessage}"))
        )
    }
  end resumeAdmitted

  // ══════════════════════════════════════════════════════════════════════════
  // B''. 孤悬终点巡检（作者 16:51 ③：不得存在「节点 rootSessionId 指向终态会话
  //      且无接管记录」的孤立状态——巡检可判）
  // ══════════════════════════════════════════════════════════════════════════

  /** 一条孤悬疑点。 */
  final case class OrphanScope(
    delegateId: String,
    scopeDir: String,
    openNodes: Int,
    reason: String
  )

  /** 终态词表（这些状态的节点不算孤悬——它们已经不需要任何人收事件）。 */
  private val TerminalStatuses = Set("completed", "failed", "cancelled", "blocked")

  /** 作用域目录下**非终态**节点数（读 flow-map.json；缺文件 = 0）。 */
  def openNodeCount(scopeDir: os.Path): Int =
    try
      val p = scopeDir / "flow-map.json"
      if !os.exists(p) then 0
      else
        io.circe.parser.parse(os.read(p)).toOption match
          case None => 0
          case Some(json) =>
            json.hcursor
              .get[Map[String, Json]]("nodes")
              .toOption
              .map(_.values.count { n =>
                val st = n.hcursor.get[String]("status").toOption.map(_.toLowerCase).getOrElse("pending")
                !TerminalStatuses.contains(st)
              })
              .getOrElse(0)
    catch case _: Throwable => 0

  /** 该 delegate 是否已有归属接管记录（ChainStore `owner-migration` 行覆盖本案）。 */
  def hasOwnerMigration(delegateId: String, taskId: Option[String]): Boolean =
    val keys = List(ChainStore.keyFor(taskId, delegateId), delegateId).distinct
    keys.exists { k =>
      ChainStore
        .readSync(k)
        .exists(_.entries.exists { e =>
          e.kind == "owner-migration" &&
          (e.payload.hcursor.get[String]("to").toOption.contains(delegateId) ||
            e.payload.hcursor.get[String]("from").toOption.contains(delegateId))
        })
    }

  /**
   * 孤悬终点巡检（只读、可重入）：
   *   - 已取消/已归档的记录（= 明确不可续的原会话）名下仍有**非终态节点**，且
   *   - 链账本里没有指向该会话的 `owner-migration` 接管记录，
   * ⇒ 一条疑点。事件到达不了的形态由此机械可判（不是靠人眼比对面板）。
   */
  def orphanScopeSuspects(): IO[List[OrphanScope]] =
    IO.blocking {
      DelegateRegistry.list().flatMap { rec =>
        val base = rec.projectWorkspace.getOrElse(rec.cwd)
        val dir = nebflow.core.project.ScopeHandover.scopeDir(base, rec.id)
        val open = openNodeCount(dir)
        val terminalOwner = rec.cancelRequestedAt.isDefined || rec.archivedAt.isDefined
        if open > 0 && terminalOwner && !hasOwnerMigration(rec.id, rec.taskId) then
          val why =
            if rec.cancelRequestedAt.isDefined then "cancelled tombstone owner"
            else "archived owner"
          List(OrphanScope(rec.id, dir.toString, open, why))
        else Nil
      }
    }.handleErrorWith(e => logger.warn(s"[session-resume] orphan scope sweep failed: ${e.getMessage}").as(Nil))

end SessionResumption
