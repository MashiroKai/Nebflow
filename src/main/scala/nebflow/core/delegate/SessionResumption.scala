package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorRef, ActorSystem, AgentEvent, AgentKind}
import nebflow.core.ResumeHandoff
import nebflow.core.executor.ExecutorRegistry
import nebflow.core.node.NodeRunner
import nebflow.core.project.DelegateFlowHosts
import nebflow.shared.{ContentBlock, NebflowLogger, SubAgentTask}

/**
 * Same-identity continuation for a completed or idle delegate with verified history.
 * Mail never creates a replacement identity. Cancellation, missing history and unknown
 * tool outcomes are explicit refusals; frozen and restart-interrupted sessions are held by
 * a gate ([[requiresManual]]) that a Mail supplement releases (`gateReleased`, Mail = wake).
 * Historical scope migrations remain readable for diagnostics.
 */
object SessionResumption:

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
  /**
   * 押闸判定（单点）：冻结 / 重启打断 / 待恢复 / 活体冻结 ⇒ 押闸。取消墓碑也计入（但取消
   * 永不释放，见 [[resumeInPlaceLocked]] 与 Mail 腿的前置拒绝）。
   * 释放口单点：Mail 补充腿（`MailTool.deliverToDelegate` → `releaseGateByMail`）是押闸的
   * 唯一释放来源——Mail 即唤醒（显式重试信号；确定性缺陷再撞仍按既有规则再冻结）。
   * 其余调用方（邮箱排干 / 押闸态注入门）只读本判定，不释放。
   */
  def requiresManual(
    record: Option[DelegateRegistry.Record],
    task: Option[nebflow.shared.SubAgentTask],
    live: Option[nebflow.actor.AgentRecord]
  ): Boolean =
    record.exists(r =>
      r.frozenSince.nonEmpty || r.frozenReason.nonEmpty || r.restartInterruptedAt.nonEmpty || r.cancelRequestedAt.nonEmpty
    ) ||
      task.exists(t => Set("pending-recovery", "frozen").contains(t.status)) ||
      live.exists(r => r.status == nebflow.actor.AgentStatus.Frozen || r.frozenReason.nonEmpty)

  def resumeInPlace(
    deps: Deps,
    record: DelegateRegistry.Record,
    message: String,
    blocks: Option[List[ContentBlock]] = None,
    gateReleased: Boolean = false
  ): IO[Either[String, InPlaceOutcome]] =
    SessionStartLease
      .exclusive(record.id)(resumeInPlaceLocked(deps, record, message, blocks, gateReleased))
      .handleError(e => Left(s"Continuation could not safely start: ${e.getMessage}"))

  /**
   * Caller already holds the same session-start lease as initialization and manual revival.
   *
   * @param gateReleased **押闸释放口**（作者令 2026-11-22 20:44「Mail 本身就是唤醒」）：
   *   `true` = 调用方（Mail 补充腿）已显式释放押闸——冻结 / 重启打断 / 待恢复的记录不再被
   *   [[requiresManual]] 拦下；同 id 原位续、转录重放、补充作为下一回合注入，成功后收口冻结槽
   *   （[[DelegateFreeze.onWoken]]）。取消墓碑与受保护执行**不因此释放**（两闸仍在前）。
   *   `false`（默认）= 押闸态照旧拒绝（非 Mail 调用方不得绕闸）。不写预算计数（零记账）。
   */
  private[nebflow] def resumeInPlaceLocked(
    deps: Deps,
    record: DelegateRegistry.Record,
    message: String,
    blocks: Option[List[ContentBlock]] = None,
    gateReleased: Boolean = false
  ): IO[Either[String, InPlaceOutcome]] =
    for
      latest <- IO.blocking(DelegateRegistry.find(record.id))
      task <- deps.resources.subAgentTaskStore.findByTaskId(record.id)
      live <- deps.resources.agentRegistry.get.map(_.get(record.id))
      protection <- deps.resources.executionProtection(record.id)
      result <- latest match
        case _ if protection != nebflow.shared.ExecutionProtection.Unprotected =>
          IO.pure(
            Left("Protected or unknown execution requires exact-ref recovery; legacy continuation is unavailable")
          )
        case None => IO.pure(Left("Delegate identity is missing; inspect the saved context"))
        case Some(current) if current.cancelRequestedAt.nonEmpty =>
          IO.pure(Left("Delegate was manually cancelled; create new work explicitly (DELEGATE_CANCELLED)"))
        case Some(current) if !gateReleased && requiresManual(Some(current), task, live) =>
          IO.pure(Left("Continuation is saved behind a manual recovery gate (MANUAL_RECOVERY_REQUIRED)"))
        case Some(current) =>
          live.fold(IO.pure(false))(entry => deps.system.isAlive(entry.ref.path)).flatMap {
            case true => IO.pure(Left("Session is already live; deliver the supplement to its existing actor"))
            case false =>
              // 守门拒绝 ⇒ 干净 Left（带原因），**不进 admitted 路径**、不 park 成引擎故障。
              pendingNotificationGate(task, gateReleased) match
                case PendingGate.Refuse(reason) => IO.pure(Left(reason))
                case PendingGate.Clear(row) =>
                  deps.resources.subAgentTaskStore
                    .clearStalePendingNotification(row.parentSessionId, row.taskId, row.runId.getOrElse(""))
                    .flatMap {
                      case Right(_) =>
                        val note =
                          s"[session-resume] cleared stale pending notification on terminal row ${row.taskId} " +
                            s"(status=${row.status}) before gate-released resume"
                        logger.info(note).flatMap(_ => resumeAdmitted(deps, current, message, blocks, gateReleased))
                      case Left(err) =>
                        IO.pure(Left(s"stale pending notification could not be cleared ($err); resume refused"))
                    }
                case PendingGate.Proceed => resumeAdmitted(deps, current, message, blocks, gateReleased)
          }
    yield result

  /** 待投递旗守门判定的结果（纯判定，见 [[pendingNotificationGate]]）。 */
  enum PendingGate:
    /** 无待投递旗（或旗为假）：照常进入 admitted。 */
    case Proceed

    /** 终态残留旗 ∧ 释放腿 ∧ 有 runId：清旗后照常释放。 */
    case Clear(row: SubAgentTask)

    /** 其它一律拒绝（非终态行的旗不碰；非释放腿不清旗）——干净 Left，不 park。 */
    case Refuse(reason: String)

  /**
   * 待投递旗守门（纯判定，单点）：
   *   - 旗为假/缺席 ⇒ Proceed；
   *   - 旗为真 ∧ 行终态（failed/cancelled）∧ 释放腿（gateReleased）∧ 有 runId ⇒ Clear
   *     （看门狗强终后的陈旧残留——挂起通知等不到投递，清旗是收拾尸体，不违反零通知令）；
   *   - 旗为真 ∧ 其余（非终态 / 非释放腿 / 无 runId）⇒ Refuse（非终态旗一律不碰）。
   */
  def pendingNotificationGate(task: Option[SubAgentTask], gateReleased: Boolean): PendingGate =
    task match
      case Some(row) if row.notificationPending.contains(true) =>
        val terminal = Set("failed", "cancelled").contains(row.status)
        if terminal && gateReleased && row.runId.nonEmpty then PendingGate.Clear(row)
        else
          PendingGate.Refuse(
            s"notification-pending: task ${row.taskId} (status=${row.status}) still carries an undelivered " +
              "terminal notification; the continuation guard refuses it (not an engine fault — " +
              "the row is left untouched)"
          )
      case _ => PendingGate.Proceed

  private def resumeAdmitted(
    deps: Deps,
    record: DelegateRegistry.Record,
    message: String,
    blocks: Option[List[ContentBlock]],
    gateReleased: Boolean
  ): IO[Either[String, InPlaceOutcome]] =
    val id = record.id
    val title = displayTitle(record)
    cats.effect.Ref.of[IO, IO[Unit]](IO.unit).flatMap { cleanup =>
      val admitted = cats.effect.Ref.unsafe[IO, Option[SubAgentTask]](None)
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
                // ① 行先翻 running（复活半途再崩 ⇒ 下一 boot 看到诚实的 running 行）。
                _ <- taskOpt.fold(IO.unit)(deps.resources.subAgentTaskStore.resumeObserved)
                _ <- deps.resources.subAgentTaskStore.findByTaskId(id).flatMap(admitted.set)
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
                // ⑦b'' 可见性回流（阶段②）：原来靠「复活即摘归档旗」把活会话拉回在册窗；
                // 归档写点退役后**零写盘即可复现**——可见性由状态派生现读（[[DelegateVisibility]]：
                // 轮次结算证据决定收口与否），活会话天然回活动窗。
                _ <- logger.info(
                  s"[session-resume] visibility is state-derived for $id (round evidence owns the window; " +
                    "no archive flag written)"
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
                // 押闸释放收口：冻结槽 / 注册表 / 台账行一次归位。不收口 = 冻结标记残留，
                // requiresManual 下一次仍判押闸（Mail 永远唤不醒）。仅押闸释放腿执行；
                // 普通续聊零写（不触碰已完成行）。fail-soft：收口失败不推翻已成的续起。
                _ <- IO.whenA(gateReleased)(
                  DelegateFreeze
                    .onWoken(deps.resources, id, "mail", deps.wsSend)
                    .handleErrorWith(e =>
                      logger.warn(s"[session-resume] gate-release wake close-out failed for $id: ${e.getMessage}").void
                    )
                )
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
        // 可见性：状态派生（阶段②）——续起失败的会话由读数自行落窗（原「unarchive
        // 摘旗」写点随存储式归档退役，零写盘等价）。
        IO.unit *>
        admitted.get
          .flatMap {
            case Some(task) if task.runId.nonEmpty =>
              deps.resources.subAgentTaskStore
                .completeRun(
                  task.parentSessionId,
                  id,
                  task.runId.get,
                  "failed",
                  reason = Some("Continuation startup failed; manual recovery required"),
                  incomplete = true,
                  notifyParent = false
                )
                .void
            case Some(task) => deps.resources.subAgentTaskStore.transitionObserved(task, "frozen")
            case None => IO.unit
          }
          .attempt
          .void
      attempt
        .onError { case _ => failed }
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
   *   - **状态派生收口**（取消墓碑 ∨ [[DelegateVisibility]] 判据收口）的记录名下仍有
   *     **非终态节点**，且
   *   - 链账本里没有指向该会话的 `owner-migration` 接管记录，
   * ⇒ 一条疑点。事件到达不了的形态由此机械可判（不是靠人眼比对面板）。
   * 阶段②：原「取消墓碑 ∨ 归档旗」的成对读法改为「取消墓碑 ∨ 状态派生收口」
   * （归档写点退役；判据同源单点，语义不变）。
   */
  def orphanScopeSuspects(): IO[List[OrphanScope]] =
    IO.blocking {
      DelegateRegistry.list().flatMap { rec =>
        val base = rec.projectWorkspace.getOrElse(rec.cwd)
        val dir = nebflow.core.project.ScopeHandover.scopeDir(base, rec.id)
        val open = openNodeCount(dir)
        val terminalOwner = rec.cancelRequestedAt.isDefined || DelegateVisibility.settledOf(rec)
        if open > 0 && terminalOwner && !hasOwnerMigration(rec.id, rec.taskId) then
          val why =
            if rec.cancelRequestedAt.isDefined then "cancelled tombstone owner"
            else "settled owner (state-derived)"
          List(OrphanScope(rec.id, dir.toString, open, why))
        else Nil
      }
    }.handleErrorWith(e => logger.warn(s"[session-resume] orphan scope sweep failed: ${e.getMessage}").as(Nil))

end SessionResumption
