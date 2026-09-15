package nebflow.agent

import cats.effect.{Deferred, IO}
import io.circe.Json
import nebflow.actor.*
import nebflow.core.node.NodeRunner
import nebflow.core.tools.{MemoryHistory, MemoryQueue}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.project.{ProjectMemory, ProjectStore}
import nebflow.service.{MemoryBudget, MemorySnapshot}
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
 * **降级（三档同一路径，fail-open + fail-closed 前置闸）**：**前置闸**（只读、fail-closed，
 * 2026-09-13 缺失自愈批）= 引擎侧 dry-run 计划 → 预算闸（超硬顶即停）→ 落地前快照闸；
 * 任一不过 ⇒ 零 spawn、零文件写、零结局写，条目保持 pending + 响亮告警。**运行期**失败 /
 * 超时 / hang ⇒ 对本轮**仍无终态结局**的待办逐条写 `outcome(result=notrun|timeout)`
 * ——**infra 失败一律不写 `rejected`**（那会让「引擎没跑」伪装成「消费者判定不可落」，
 * 作者令）；`notrun`/`timeout`/`rejected`/`blocked` 都在可重试集合内 ⇒ 条目**仍 pending**
 * ⇒ 下一次压缩自然重试（spec §5 R3 档 1 的「队列条目保留」这才真正成立）。另有
 * [[MemoryTrackSignal]] 一次性引线 + `memory-track-failed` / `memory-track-timeout` /
 * `memory-track-refused` 事件 + **前端告警**（`memoryQueueAlert`，由调用方 AgentActor 落）。
 * 照常装机（旧记忆）。**否决任何无 timeout 的 join**（真 hang 会永久 hold 注入）。
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
 *
 * ═══════════════════════════════════════════════════════════════════════
 * **2026-09-15 B 腿（压缩管线三件批）——轨内会话接前端：面板可见**
 * ═══════════════════════════════════════════════════════════════════════
 *
 * **作者规格（2026-09-15 逐字）**：「我的期待是 subagent 直接根据队列中的记忆来编辑
 * 记忆文件，压缩好了之后，正好合成注入新的上下文记忆」——以及现场疑问「我在 subagent
 * 面板里并没有看到这样一个 agent 在跑」。
 *
 * **考古结论（现取，非推断）**：本轨**本来就是**真 subagent（`spawn` 起独立
 * `AgentActor` + 注册 `AgentKind.Ephemeral`），不是 Nebula 内联自干；但 `spawn` 把
 * `wsSend` 传成恒 `IO.unit`（旧注：「轨内事件不进前端（噪音面）」）⇒ 会话在注册表里
 * **有记录、无活帧**。而 subagent 面板的行**由 `agentStart` 活帧创建**
 * （`main.js` `onMessage('agentStart', …)`），`getActiveAgents` 快照只在 WS 建连 /
 * 重连时重拉一次 ⇒ 正常会话里这一行**从不出现**（现场实证：本机 2026-09-15 当天
 * 6 次 `memory-track-completed`，面板零行）。
 *
 * **改动（唯一改动面 = 事件接线）**：`spawn` 的 `wsSend` 改走
 * [[NodeRunner.routeSubagentWsSend]]——全部子代理 spawn 路径的既有契约（Delegate /
 * SubTask / 节点 / 分发器同款）：`agentStart` 建行、`agentDone` 收行
 * （`AgentActor.finishTurn` 的 subagent 分支同步发 `Done`）。父 wsSend 不可得
 * （headless / 无 WS 会话）⇒ 回落恒 `IO.unit`（与改动前同参，零行为漂移）。
 *
 * **本批零改动面**：闸 0–4（暂停标记 / dry-run 计划 / 预算闸 / 快照闸 / 写前台账）、
 * `shouldRun` 触发谓词、硬软超时、降级与超时对账、`brief` 简报、变更史、三层记忆文件
 * 本体、`queue.jsonl` 读写口径、[[nebflow.service.MemoryBudget]] /
 * [[nebflow.service.MemoryWriteGate]] 判据（**未动，禁自造旁路**）。
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

  /** 只读干跑模式（2026-09-13 缺失自愈批 / 方案 E-D1）：
    * `-Dnebflow.memory.track.dryRun=true` ⇒ 本轮只算 [[MemoryQueue.plan]] 并把它写进
    * 日志，**不 spawn agent、不写任何 outcome、不改任何文件**（落地前置闸的观测形态）。 */
  def dryRunMode: Boolean =
    sys.props.get("nebflow.memory.track.dryRun").exists(v => v == "true" || v == "1")

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

  // ── 暂停标记（#440 ①，引擎面） ──────────────────────────────────

  /** 暂停标记文件名。 */
  val PauseMarkerFileName: String = "consolidation-paused"

  /** 暂停标记路径 = `<dataRoot>/memory/consolidation-paused`（生产 = `~/.nebflow/…`）。
    *
    * 存在 ⇒ [[run]] **跳过本轮**。载体缺口（#440）：作者的「先修后落」令**只承载在
    * Nebula 的调度任务里**，而引擎触发的轮次（`trigger=compaction`）**结构上不可能知道
    * 它** ⇒ 需要一个引擎侧可执行载体。切换 = `touch` / `rm` 该文件；删标记即恢复，
    * **禁**另造开关/环境变量旁路（作者令）。
    *
    * `def` 非 `val`：`PathUtil.dataRoot` 可被测试换根（同 [[MemoryQueue.queuePath]]）。 */
  def pauseMarkerPath: os.Path = PathUtil.dataRoot / "memory" / PauseMarkerFileName

  /** 暂停态读数（只看存在位，不解析内容）。 */
  def isPaused: Boolean = os.exists(pauseMarkerPath)

  // ── 结果模型 ───────────────────────────────────────────────────

  enum Status:
    case Paused, Skipped, Completed, Failed, Timeout, DryRun, Refused

  /** `detail` = 整理 agent 的最终报告（截断）或失败原因；`pendingAtStart` = 起跑时待办
    * 条数；`outcomesWritten` = 降级路径代写的结局条数（含 ④ 对账标的终态）；`changed` =
    * 变更史落下的文件数；`alert` = 非空 ⇒ 调用方把它当**告警**推进前端 + 生命周期日志
    * （方案 D「响亮失败」：引擎 infra 失败不再只躺在日志里等着被 grep）。
    *
    * `reconciled`（C 批 ④，2026-09-13）：超时对账判为**已了结**而由引擎标终态的条数
    * （⊆ `outcomesWritten`）——生命周期事件与日志用它与「照旧写 timeout 的条数」区分，
    * 使「谁判的」在事件行上也可读。默认 0 ⇒ 既有构造点不受影响。
    * `reconcileDrift` = 写前固化集与写后实际写入集的差集条数（**正常恒 0**；非 0 = 引擎在
    * 判定与写入之间被绕过或写失败，见 [[DegradeReport]] —— 写后只对账、不重推）。 */
  final case class Result(
    status: Status,
    detail: String,
    pendingAtStart: Int,
    outcomesWritten: Int = 0,
    changed: Int = 0,
    alert: Option[String] = None,
    reconciled: Int = 0,
    reconcileDrift: Int = 0
  )

  /** 降级执行报告（C 批 ④）：**写前固化集** + **写后实际写入集** + 两者差集。
    *
    * 作者纪律（2026-09-13 立册）「**禁从写后状态反推写入集**」的落地点：
    *   - `frozen` = 写前固化的对账判据集（来自 `finish` 里**判定那一刻**的状态读数，
    *     与写入无关）——这就是「写前确定写入集」，并随报告**留存读数**；
    *   - `judgedWritten` = 实际写成的对账终态 ref 集（写后读回，**不重新推导**）；
    *   - `drift` = `frozen -- judgedWritten`（冻结了却没写成）+ `judgedWritten -- frozen`
    *     （写成的不在冻结集里，按构造恒空）。**恒空 = 断言 `run_set == expected` 成立**；
    *     非空 ⇒ 响亮 WARN（绝不静默重推）。 */
  final case class DegradeReport(written: Int, judgedWritten: List[String], drift: List[String])

  object Result:
    val Skipped: Result = Result(Status.Skipped, "no trigger (empty queue, under soft lines, no signal)", 0)

    /** 暂停轮读数（#440 ①）。`detail` 以 `reason=paused` 起头 ⇒ 调用方的生命周期事件行
      * （`memory-track-skipped`）从 detail 直接带出该字样。
      *
      * `pendingAtStart = 0` 是**本轮未读队列**的真实读数（不是投影）：暂停轮在 [[run]]
      * 起点短路，对 pending 计数不作承诺，也不改其状态。 */
    def paused: Result =
      Result(
        Status.Paused,
        s"reason=paused: marker ${pauseMarkerPath} exists — this round is skipped " +
          "(zero writes: no memory file, no outcome line, no spawn)",
        0
      )

  // ── 主入口 ─────────────────────────────────────────────────────

  /** 跑一轮记忆轨。**本方法自身不成败整个压缩**：一切失败都转成 [[Result]]。
    *
    * 2026-09-13 缺失自愈批（方案 E-D1 + §6）：起跑线之后先过**三道只读前置闸**
    * （全部 fail-closed，闸不过 = 零 spawn、零文件写、零结局写，条目保持 pending）：
    *   1. **引擎侧 dry-run 计划**（[[MemoryQueue.plan]]）：分桶 would-apply /
    *      would-obsolete（终态族）/ would-retry（目标缺失族，可重试）/ would-defer + 逐文件
    *      投影；计划与日志落地（四段结构化文本）。
    *   2. **预算闸**：`plan.refusal` 非空（超硬顶即停 ⇒ 授权集为空）⇒ 拒绝本轮落地。
    *   3. **落地前快照闸**（[[MemorySnapshot.snapshotGate]]）：三层记忆文件 + 队列 +
    *      变更史逐文件备份 + sha256 断言表；失败 ⇒ 拒绝落地（「无快照不落笔」的机制化）。
    *   4. **写前台账**（①，C 批 2026-09-13）：spawn 之前落**一条聚合行**（授权 ref 全集 +
    *      每目标起始 sha256/bytes + 快照目录）——超时后 ④ 的对账基准（可审计、可复算）。
    * 只读干跑模式（[[dryRunMode]]）在第 1 道闸后即返回，零 spawn。
    *
    * **超时对账（④，C 批 2026-09-13）**：硬超时截断后**先对账再降级**——
    * [[MemoryQueue.reconcile]] 用跑后文件内容判「效果是否已在盘上」（逐字行命中 / 定位键
    * 消失两支高精度判据），确凿者由引擎标终态（独立字样，见
    * [[MemoryQueue.ResultAppliedByReconcile]]），其余照旧写 `timeout` 留 pending 重试。
    * 这一条是「已落地却全留 pending ⇒ 整批重投造重复行」的机械闭合面（取证件 §0-2/§3.2）。
    *
    * **入口闸 0（暂停标记，#440 ①，2026-09-13 作者裁定）**：[[run]] 起点的第一件事是读
    * [[pauseMarkerPath]]；存在 ⇒ 直接返回 [[Result.paused]]，闸 1–4 与 spawn 全部不进
    * （见 [[run]] 内注释）。 */
  def run(
    resources: SharedResources,
    parentSessionId: Option[String],
    parentDepth: Int = 0,
    trigger: String = MemoryQueue.TriggerCompaction,
    parentWsSend: Option[Json => IO[Unit]] = None
  ): IO[Result] =
    // ── 入口闸 0（暂停标记，#440 ①）：在**任何队列读 / 盘读之前**短路 ⇒ 该轮零写入
    //    （不写三层记忆文件、不写 outcome 行、不置位重试引线、不 spawn）、不把任何
    //    pending 打成终态词。pending 原样留待删标记后由既有谓词链消费（同口径见
    //    `mempipe-init-plan` §3.1/§3.4/§3.7）。删除标记即恢复；**禁临时机制**。
    IO.blocking(isPaused).flatMap { paused =>
      if paused then IO.pure(Result.paused)
      else runUnpaused(resources, parentSessionId, parentDepth, trigger, parentWsSend)
    }

  /** [[run]] 的未暂停体（暂停短路已在 [[run]] 起点，本方法不再重复判暂停）。 */
  private def runUnpaused(
    resources: SharedResources,
    parentSessionId: Option[String],
    parentDepth: Int,
    trigger: String,
    parentWsSend: Option[Json => IO[Unit]]
  ): IO[Result] =
    val state0       = MemoryQueue.readState()
    val notesAtStart = state0.pending
    val signal       = MemoryTrackSignal.peek()
    val (userBytes, agentBytes) = memoryBytes()
    if !shouldRun(userBytes, agentBytes, notesAtStart.size, signal.isDefined) then IO.pure(Result.Skipped)
    else
      for
        files <- memoryFilesOf(notesAtStart)
        before <- readAll(files)
        // ── 闸 1（只读）：dry-run 计划。计划文本进日志＝可复算的落地前观测面 ──
        input <- planInput(before)
        plan = MemoryQueue.plan(state0, input)
        _    <- logger.info(s"[memory-track] gate-1 plan (read-only)\n${plan.render()}")
        // 目标缺失族（缺文件 / 缺节 / 定位不到条目）：**响亮告警**（A′ 三件之三）。
        // 不是 WARN-and-continue 的客气话：这一族的条目本轮**不可落且不得新建目标文件**，
        // 只写一行日志就没人会去修 ⇒ 同一行里给出「谁该做什么」。
        _ <- IO.whenA(plan.retryable.nonEmpty)(logger.warn(
          s"[memory-track] MISSING TARGET (retryable, no file created): ${plan.retryable.size} note(s) cannot be located — " +
            s"${plan.retryable.take(10).mkString(", ")}${if plan.retryable.size > 10 then s" …(+${plan.retryable.size - 10} more)" else ""}. " +
            "They stay pending (never marked obsolete) and will be retried once the target exists; " +
            "the target file must be created OUTSIDE this track (project-memory initialisation is not this track's job)."
        ))
        attempt <- if dryRunMode then IO.pure(Attempt(Status.DryRun, plan.render(40), "", None))
          else plan.refusal match
            case Some(reason) =>
              // ── 闸 2（fail-closed）：超硬顶即停 ⇒ 授权集为空 ⇒ 本轮不落地 ──
              IO.pure(Attempt(Status.Refused, reason, "", Some(alertOf(s"budget fail-closed: $reason"))))
            case None =>
              // ── 闸 3（fail-closed）：落地前快照闸（三层记忆 + 队列 + 变更史 + sha 断言）
              MemorySnapshot.snapshotGate(snapshotTargets(files), "pre-consolidation-track") match
                case Left(err) =>
                  IO.pure(Attempt(
                    Status.Refused,
                    s"preflight snapshot gate failed: $err — no file was written (无快照不落笔)",
                    "",
                    Some(alertOf(s"snapshot gate failed: $err"))
                  ))
                case Right(set) =>
                  val authorized = authorizedNotes(state0, plan)
                  logger.info(
                    s"[memory-track] gate-3 snapshot ok: ${set.files.size} file(s) → ${set.dir.toString} (sha256 assertion table written)"
                  ) *>
                    // ── 闸 4 = 写前台账（①，C 批 2026-09-13）：**spawn 之前**先落一条聚合行
                    //    （授权 ref 全集 + 每目标起始 sha256/bytes + 快照目录）。超时后 ④ 的
                    //    判定因此可审计、可复算，不依赖 agent 自快照。落盘失败只 WARN：台账是
                    //    审计面而非落地屏障（它本身不阻止重投；阻止重投靠 ④）。
                    writePreflightLedger(authorized, files, before, state0.pendingCount, set, trigger) *>
                    attemptRun(resources, parentSessionId, parentDepth, trigger, authorized, plan, parentWsSend)
                      .timeoutTo(hardTimeoutMs.millis, IO.pure(Attempt(Status.Timeout, s"hard timeout after ${hardTimeoutMs}ms", "", None)))
                      .handleErrorWith(e => IO.pure(Attempt(Status.Failed, s"${e.getClass.getSimpleName}: ${e.getMessage}", "", None)))
        _ = MemoryTrackSignal.take() // 本轮已跑：信号消费（无论成败——重试引线由降级路径重新置位）
        result <- finish(files, before, notesAtStart, attempt, trigger)
      yield result

  /** dry-run 的输入面：`readAll` 的 (path, label, content) → 计划用的 label → 只读快照。
    *
    * 2026-09-13 r3 批（缺陷① A′）：**同时带上存在位**。改动前只传 `content`，而
    * [[readAll]] 对「文件不存在」与「空文件」都返回 `""` ⇒ 引擎对不存在的项目记忆文件的
    * append 判 `would-apply`（投影 `0 → N B`）⇒ 消费侧据此**新建了文件**（生产实证
    * 2026-09-13 18:25 `project:neblink-server`）。`private[agent]`：spec 直测面。 */
  private[agent] def planInput(before: Vector[(os.Path, String, String)]): IO[Map[String, MemoryQueue.TargetFile]] =
    IO.blocking(
      before
        .map((p, label, content) =>
          label -> MemoryQueue.TargetFile(p.toString, content, exists = os.exists(p) && os.isFile(p)))
        .toMap
    )

  /** 快照闸的目标面：三层记忆文件（仅本轮点名到的）+ 队列 + 变更史。 */
  private def snapshotTargets(files: Vector[FileTarget]): Vector[os.Path] =
    (files.map(_._1) :+ MemoryQueue.queuePath :+ MemoryHistory.historyPath).distinct

  /** 闸 2 通过后简报允许消费的条目：would-apply（落笔）+ would-obsolete / would-retry
    * （零文件写、只回写裁决；后者是**可重试**口径 ⇒ 只许 `rejected`，不许 `obsolete`）。 */
  private def authorizedNotes(state: MemoryQueue.State, plan: MemoryQueue.Plan): Vector[MemoryQueue.Note] =
    val ok = plan.authorized.toSet
    state.pending.filter(n => ok.contains(n.id))

  private def alertOf(reason: String): String =
    s"Memory queue is NOT being consumed: $reason — every pending note stays pending (no note was marked rejected; nothing was applied). Fix the consumption chain and it will be retried on the next compaction."

  // ── 内部：写前台账（C 批 ①） ──────────────────────────────────

  private def sha256Hex(s: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  /** **写前台账**（C 批 ①，2026-09-13）：整理轨 spawn **之前**落**一条聚合行**到
    * [[MemoryHistory]]（同款轮转阈值）——授权 ref 全集 + 每目标起始 sha256/bytes +
    * 落地前快照目录。**禁逐条 273 行**（避免把 20000 行阈值烧穿）。
    *
    * 为什么：超时截断后，「哪条该闭合」这件事此前只能靠 agent 自快照或事后推断；有了这条
    * 基准，④ 的对账是**可复算**的（`refs` 全集 + 起跑 sha 都在一行里），且它与 gate-3
    * 的 sha256 断言表**独立同值**（两条独立路径算同一份跑前状态）。
    *
    * sha/bytes 由引擎按**跑前内容**（`before`，即 gate-3 之前那次读取）自算；记忆文件是
    * 引擎自写的 UTF-8 文本，故与 [[MemorySnapshot.snapshotGate]] 的字节级 sha 一致。
    *
    * 失败只 WARN（**不阻断本轮**）：台账是审计面，不是落地屏障。 */
  private[agent] def writePreflightLedger(
    authorized: Vector[MemoryQueue.Note],
    files: Vector[FileTarget],
    before: Vector[(os.Path, String, String)],
    pendingTotal: Int,
    gate: MemorySnapshot.GateSet,
    trigger: String
  ): IO[Unit] =
    IO.blocking {
      val byPath = before.map((p, _, c) => p.toString -> c).toMap
      val targets = files.map { (p, label) =>
        val content = byPath.getOrElse(p.toString, "")
        MemoryHistory.PreflightTarget(
          label = label,
          path = p.toString,
          sha256 = sha256Hex(content),
          bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
        )
      }.toList
      MemoryHistory.appendPreflight(
        atMs = System.currentTimeMillis(),
        actor = AgentName,
        trigger = trigger,
        authorizedRefs = authorized.map(_.id).toList,
        pendingTotal = pendingTotal,
        targets = targets,
        snapshotDir = gate.dir.toString,
        snapshotLabel = gate.label
      ) match
        case Right(_) =>
          logger.infoSync(
            s"[memory-track] gate-4 pre-write ledger written: ${authorized.size}/$pendingTotal authorized ref(s), ${targets.size} target file(s) with starting sha256/bytes"
          )
        case Left(e) =>
          logger.warnSync(
            s"[memory-track] pre-write ledger append failed: $e — the round proceeds (the audit anchor for reconcile is missing)"
          )
    }.handleErrorWith(e =>
      logger.warn(s"[memory-track] pre-write ledger failed: ${e.getClass.getSimpleName}: ${e.getMessage} — the round proceeds"))

  // ── 内部：快照 / 变更史 ─────────────────────────────────────────

  private type FileTarget = (os.Path, String) // (path, target label)

  /** 本轨可能触及的文件：user / agent 两层 + 待办点名的 project 层（经注册表解析）。
    * `private[agent]`：spec 直测面（真实输入面复算，见 `MemoryTargetRetryDomainSpec`）。 */
  private[agent] def memoryFilesOf(notes: Vector[MemoryQueue.Note]): IO[Vector[FileTarget]] =
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

  /** `private[agent]`：spec 直测面（真实输入面复算）。 */
  private[agent] def readAll(files: Vector[FileTarget]): IO[Vector[(os.Path, String, String)]] =
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

  /** 单轮 attempt 的结局（`private[agent]`：spec 直测面 —— 超时分支的
    * `finish(Timeout)` 夹具直接构造它）。 */
  private[agent] final case class Attempt(
      status: Status,
      detail: String,
      report: String,
      alert: Option[String] = None
  )

  private final case class Handle(
    agentRef: ActorRef[AgentCommand],
    bridgeRef: ActorRef[AgentEvent],
    sessionId: String,
    /** 本会话的面板事件面（[[panelWsSend]] 的产物）——[[cleanup]] 用它发收行帧。 */
    panelSend: Json => IO[Unit]
  )

  private def attemptRun(
    resources: SharedResources,
    parentSessionId: Option[String],
    parentDepth: Int,
    trigger: String,
    notes: Vector[MemoryQueue.Note],
    plan: MemoryQueue.Plan,
    parentWsSend: Option[Json => IO[Unit]]
  ): IO[Attempt] =
    resources.agentLibrary.get(AgentName).flatMap {
      case None =>
        IO.pure(Attempt(
          Status.Failed,
          s"agent definition '$AgentName' not found — expected ${PathUtil.dataRootRenderValue}/agents/$AgentName/{agent.json,system.md} " +
            s"(seeded from src/main/resources/seed/agents/$AgentName/). Nothing was applied; the queue is untouched.",
          "",
          Some(alertOf(s"agent definition '$AgentName' is missing (seeded from src/main/resources/seed/agents/$AgentName/) — the consumer never ran"))
        ))
      case Some(defn) =>
        for
          started <- IO.monotonic
          spawned <- spawn(resources, defn, parentSessionId, parentDepth, trigger, notes, plan, parentWsSend)
          outcome <- spawned match
            case Left(err) =>
              IO.pure(Attempt(Status.Failed, err, "", Some(alertOf(s"spawn failed: $err"))))
            case Right((handle, deferred)) =>
              deferred.get
                .map {
                  case Right(messages) => Attempt(Status.Completed, "consolidation run completed", lastAssistant(messages))
                  case Left(err)       => Attempt(Status.Failed, err, "", Some(alertOf(s"the consolidation session failed: $err")))
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

  /** 轨内会话的**面板事件面**（B 腿 2026-09-15；`private[agent]` = spec 直测面）。
    *
    * 判据只有一条：**父 wsSend 可得 ⇒ 与全部子代理 spawn 路径同契约**
    * （[[NodeRunner.routeSubagentWsSend]] 注入 `rootSessionId` / `sessionId` /
    * `nodeSessionId` 三键，前端 `sessionBgAgents` 按 `rootSessionId` 归桶、行键 = `agentId`
    * = 本会话 id）。父 wsSend 不可得（headless / 无 WS 会话 / 测试夹具不传）⇒ 回落恒
    * `IO.unit`，与改动前逐字同参（**零行为漂移**：不传参的调用方不看面板面）。
    *
    * **会话级生命周期帧被滤掉**（[[SessionScopedPanelTypes]]）：本轨的子会话**不是一个
    * 会话**，而是一行面板条目——它产出 `done` / `sessionBusy` 是**分类错误**（那两帧的
    * 语义是「会话 X 的回合结束了」，而本会话无视图、无输入条、无队列）。现取实测（本批
    * spec 的整帧集读数）：不过滤时前端会为一个永不存在的会话写下
    * `state.lastTerminalAt` / `state.sessionModelInfo` 条目（后者**持久化进 localStorage**）
    * 并触发一次空队列 drain——全是纯噪声。行自己的终帧是 `agentDone`（见 [[cleanup]]）。
    *
    * 为什么不是「只发 agentStart / agentDone 两帧」：面板行的**可点开性**与其它子代理同源
    * ——popup 读的是本会话自己的流事件；只发首尾两帧会造出一行「能点开但里面是空的」的
    * 半成品，比不发更坏（同族先例：`NodeRunner.emitSubagentPanelDone` 的注释已把
    * 「帧与活体事件契约全同构」立为纪律）。故整条流照原样路由（会话级两帧除外）。 */
  private[agent] def panelWsSend(
    parentWsSend: Option[Json => IO[Unit]],
    rootSessionId: String,
    sessionId: String
  ): Json => IO[Unit] =
    parentWsSend match
      case Some(base) =>
        val routed = NodeRunner.routeSubagentWsSend(base, rootSessionId, sessionId)
        json => if isSessionScopedLifecycle(json) then IO.unit else routed(json)
      case None => (_: Json) => IO.unit

  /** 会话级生命周期帧类型（[[panelWsSend]] 的过滤集；键名随 `AgentStreamEvent.toJson`）。
    *
    * `done` = 会话回合终帧（`isSubagent=false` 分支，`AgentActor.finishTurn`：
    * `isSubagent = parentRef.isDefined`，而本轨 spawn 时 `parentRef = None` ⇒ 落会话级）；
    * `sessionBusy` = 输入条 busy 闸。两者的路由键都是**本会话自身** id（现取实测：
    * `{"type":"done","sessionId":"memconsolidate-…"}`），与会话级语义绑定 ⇒ 对本轨无意义。 */
  private[agent] val SessionScopedPanelTypes: Set[String] = Set("done", "sessionBusy")

  private[agent] def isSessionScopedLifecycle(json: Json): Boolean =
    json.hcursor.get[String]("type").toOption.exists(SessionScopedPanelTypes.contains)

  private def spawn(
    resources: SharedResources,
    defn: AgentDef,
    parentSessionId: Option[String],
    parentDepth: Int,
    trigger: String,
    notes: Vector[MemoryQueue.Note],
    plan: MemoryQueue.Plan,
    parentWsSend: Option[Json => IO[Unit]]
  ): IO[Either[String, (Handle, Deferred[IO, Either[String, List[Message]]])]] =
    val sessionId = s"memconsolidate-${UUID.randomUUID().toString.take(8)}"
    val root = parentSessionId.getOrElse(sessionId)
    // 面板事件面：一次构造、两处使用（AgentActor 的 wsSend + [[cleanup]] 的收行帧），
    // 保证建行帧与收行帧落在同一键空间（[[panelWsSend]] 的判据单点）。
    val panelSend = panelWsSend(parentWsSend, root, sessionId)
    for
      workRoot <- IO.blocking(java.nio.file.Files.createTempDirectory("nb-memory-").toString)
      deferred <- Deferred[IO, Either[String, List[Message]]]
      agentRef <- resources.actorSystem.spawn(
        AgentActor(
          agentDef = defn,
          resources = resources,
          // 面板接线（B 腿 2026-09-15）：改动前恒 `IO.unit`（轨内事件不进前端，
          // 理由「噪音面」）⇒ 注册表有条目、前端零活帧 ⇒ subagent 面板永不建行
          // （行由 `agentStart` 活帧创建；`getActiveAgents` 快照只在 WS 建连时重拉）。
          // 现走 [[panelWsSend]]——全部子代理 spawn 路径的既有契约
          // （Delegate / SubTask / 节点 / 分发器同款），`agentStart` 建行、
          // `agentDone` 收行，会话级生命周期帧被滤掉。
          wsSend = panelSend,
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
        // actor 名 == sessionId（**契约，非风格**）：快照面的行键是 sessionId
        // （`WebSocketRoutes.activeAgentEntryJson` 注释「Contract: agentId == sessionId」），
        // 而活帧的 agentId = `ctx.self.path.name`（`protocol.scala:803`）——两者不一致
        // 即同一会话落两个键，`agentDone` 清不掉快照行（幽灵行，同 Mail 路径旧缺陷）。
        // `NodeRunner.spawnAgentActor:99` 对全部子代理 spawn 路径即此规则；本轨绕过它
        // 直接 spawn，故在此显式对齐（2026-09-13 面板可见性取证 C-2 判红；
        // `MemoryTrackActorIdContractSpec` 现场读数钉死）。
        sessionId
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
      _ <- (agentRef ! AgentCommand.UserInput(text = brief(workRoot, trigger, notes, plan), replyTo = Some(bridgeRef))).void
    yield Right((Handle(agentRef, bridgeRef, sessionId, panelSend), deferred))

  /** 轨内简报：自包含（agent 无历史消息）+ 绝对数据根（文件工具只吃绝对路径）。
    *
    * 2026-09-13 缺失自愈批两处修订：① 待办口径与 `MemoryQueue.pending` **同源**（旧文写
    * 的「无 outcome 的 note + result ∈ {rejected, timeout} 的重试项」与实现不符——那正是
    * 「重试引线是死的」这句错话的载体）；② 简报只发**本轨授权集**（would-apply +
    * would-obsolete），被预算闸截断的条目**不进简报**（超硬顶即停、剩余留 pending）。
    *
    * C 批 ⑥（2026-09-13）：补一行 `already-present` 桶（= `plan` 里 `detail` 以
    * `already-present` 开头的 would-obsolete 条目）——消费者**不必自己重推**（grep 文件
    * 比对逐行）就知道哪些 append 是重复的，直接记 `deduped`。
    *
    * 2026-09-13 r3 批（缺陷① A′ + 值域口径三处一致）：**目标缺失族**（`would-retry`：
    * 目标文件不存在 / 目标节不存在 / 该节内定位不到条目 / apply-miss）整族的指引一并写明
    * ——改动前只对 `target-missing` 子集有指引，且指引写的是**终态词**（「记 `obsolete` 并
    * 附一行原因」）⇒ 引擎自己的终态词 + 消费侧照办 = 内容永久丢失（= 作者裁定明确否掉的 A）。
    * 现口径 = **可重试族**：回写 `rejected`（本引擎的可重试词 ⇒ 条目保持 pending）、
    * **禁写 `obsolete`/`deduped`**、**禁新建目标文件**。三处（引擎 / `system.md` / 本简报）
    * 同口径，由 `MemoryTargetRetryDomainSpec` 逐处断言。`private[agent]`：spec 直测面。 */
  private[agent] def brief(workRoot: String, trigger: String, notes: Vector[MemoryQueue.Note], plan: MemoryQueue.Plan): String =
    val abs = PathUtil.dataRoot.toString
    val refs = notes.map(_.id).take(40).mkString(", ")
    val more = if notes.size > 40 then s" …(+${notes.size - 40} more)" else ""
    val deferred =
      if plan.deferred.isEmpty then ""
      else s"- ${plan.deferred.size} pending note(s) are NOT authorized this round (budget fail-closed: they would push a file over its hard cap) — leave them; they stay pending.\n"
    // 目标缺失族（would-retry）的简报指引——2026-09-13 r3 批（缺陷① A′ + 值域口径三处一致）。
    val retryItems = plan.items.filter(_.bucket == MemoryQueue.Bucket.WouldRetry)
    def refsOf(prefix: String): Vector[String] = retryItems.filter(_.detail.startsWith(prefix)).map(_.ref)
    val missFile   = refsOf("target-missing")
    val missLoc    = refsOf("locate-miss")
    val missApply  = refsOf("apply-miss")
    def show(v: Vector[String]): String = if v.isEmpty then "(none)" else v.take(20).mkString(", ")
    val noTargetLine =
      if retryItems.isEmpty then ""
      else
        s"""- 🔴 **目标缺失族（${retryItems.size} 条，可重试——不得打终态词）**：
- 目标文件不存在（${show(missFile)}）：🔴 **禁止新建该文件**（项目记忆文件的初始化不由本轨负责）；不动任何文件，逐条回写 `outcome(result="rejected", detail="<ABS 目标路径> 不存在")`。
- 目标节不存在（${show(missLoc)}）：不动任何文件，逐条回写 `result="rejected"`（可重试），detail 写明缺哪个节 + 该文件现有节名。
- 节内定位不到条目（同上族）：逐条回写 `result="rejected"`，detail 带上定位线索（该节条目前缀）。
- apply-miss（${show(missApply)}）：同上，`result="rejected"`。
- 🔴 这一族**一律不得写 `obsolete`/`deduped`**：`obsolete` 是终态词 ⇒ 条目永不再 pending ⇒ 内容永久丢失（「本次定位不到」被伪装成「含义已消失」）。`rejected` 在本引擎是可重试词 ⇒ 条目保持 pending，条件改变后自动再落。
"""
    // ⑥ 简报补桶：把 plan 的 already-present 桶直接告诉消费者（免它自己 grep 逐行比对）
    val alreadyPresent = plan.items
      .filter(i => i.bucket == MemoryQueue.Bucket.WouldObsolete && i.detail.startsWith("already-present"))
      .map(_.ref)
    val alreadyPresentLine =
      if alreadyPresent.isEmpty then ""
      else
        val head = alreadyPresent.take(20).mkString(", ")
        val more = if alreadyPresent.size > 20 then s" …(+${alreadyPresent.size - 20} more)" else ""
        s"- 这些 ref 的**目标文件里已有同一行**（already-present，逐字相同）：$head$more —— **不要动文件**（再落一次只会造重复行），直接记 `deduped`；也不要写 rejected（`rejected` 可重试 ⇒ 会无限复现）。\n"
    s"""[记忆整理轨] 触发=$trigger，本轮授权待办 ${notes.size} 条（队列 pending 总数见计划行）。
- 数据根（绝对路径）：$abs —— 文件工具只接受绝对路径，直接用这个前缀。
- 队列：$abs/memory/queue.jsonl。待办口径 = `MemoryQueue.pending`（有 note ∧ **未被任何 drop 行的 refs 引用** ∧ 末条结局**非终态**：终态 = applied / modified / obsolete / deduped / applied-by-reconcile，其余值——含本系统还不认识的值——一律仍 pending）——与引擎折叠谓词同源，不要另立口径。
- 本轨授权 ref 清单：${if refs.isEmpty then "(none)" else refs + more}
$deferred$noTargetLine$alreadyPresentLine- 步骤与输出契约严格按本会话系统提示词：动笔前快照三处记忆文件 → 逐条执行 → 逐条回写 outcome → 报告结构化计数。
- 只允许改 4 个目标路径（$abs/User.md、$abs/agents/Nebula/memory.md、涉及项目的 <workspace>/.nebflow/memory.md、队列）；别的文件一律不碰；禁 git 写操作。
- 一次性会话工作根：$workRoot（临时目录，用完即弃）。"""
      .stripMargin

  private def cleanup(resources: SharedResources, handle: Handle): IO[Unit] =
    for
      // ── 面板收行帧（B 腿 2026-09-15）：**唯一**终态出口 ──────────────────
      // 行由 `agentStart` 建（见 [[spawn]] 的接线），而本轨 spawn 时
      // `parentRef = None` ⇒ `AgentActor.finishTurn` 的终帧走**会话级** `done`
      // （`isSubagent = parentRef.isDefined`），前端 `done` 清行分支只认 `node-` /
      // `dispatcher-` 前缀 ⇒ 那一帧清不掉本行（幽灵行）。故收行帧由本轨自己发：
      // 形状与 [[NodeRunner.emitSubagentPanelDone]] 逐字同构（`agentId` = 行键），
      // 位置在 `attemptRun` 的 `.guarantee(cleanup)` ⇒ **覆盖全部出口**
      // （Completed / Failed / Timeout / cancel / 外层硬超时），不依赖 agent 是否
      // 来得及把回合跑完。投递失败只吞（面板面不是落地屏障）。
      _ <- handle
        .panelSend(Json.obj("type" -> Json.fromString("agentDone"), "agentId" -> Json.fromString(handle.sessionId)))
        .handleErrorWith(_ => IO.unit)
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

  /** 写前固化集 vs 写后实际集的差集描述（**纯函数**，两个方向都报；空 = `run_set == expected`）。
    * 作者纪律「禁从写后状态反推写入集」的可测面：本函数只**比较**两个集合，不从任何状态重推。 */
  private[agent] def writeSetDrift(frozen: Set[String], acted: Set[String]): List[String] =
    val notWritten = (frozen -- acted).toList.sorted.map(r => s"$r: frozen before the write set but no outcome was written")
    val notFrozen = (acted -- frozen).toList.sorted.map(r => s"$r: written but absent from the frozen set")
    notWritten ++ notFrozen

  /** 降级（失败 / 超时同一路径，spec §5 R3 档 1/2/3；2026-09-13 缺失自愈批按作者令重写）。
    *
    * **改动前的缺陷（取证件 §0-3）**：失败/超时对本轮待办逐条写
    * `outcome(result=rejected)`，而 `rejected` 既是消费者裁决的值域、又（旧折叠谓词下）
    * 让条目永不再 pending ⇒ 6 轮 infra 失败把 149 条积压烧成「消费者判定不可落」，
    * 同时把 spec「队列条目保留 ⇒ 下次压缩重试」变成空头承诺。
    *
    * **改动后**：infra 失败**一律不写 `rejected`**——
    *  - `notrun`（定义缺失 / spawn 失败 / 引擎前置不满足）与 `timeout`（硬超时截断）
    *    ——两者都在 [[MemoryQueue.RetryableResults]] 内 ⇒ 条目**仍是 pending**；
    *  - 同一 ref 的 infra 结局写入达 [[MemoryQueue.MaxInfraOutcomesPerRef]] ⇒ 只补一条
    *    `blocked`（仍 pending，但引擎不再逐轮追加，防队列膨胀），之后闭嘴。
    * `rejected` 从此只可能由消费者自己写（确实跑过并判定不可落）。
    *
    * 已有终态结局的条目不动（agent 可能在被截断前已回写了部分结局）。返回实际写入条数。
    *
    * **C 批 ④（2026-09-13）**：`reconciled`（纯对账报告，见 [[MemoryQueue.reconcile]]）里的
    * 条目**先判终态**（独立字样 `deduped` / `applied-by-reconcile`，`by` + detail 前缀可辨
    * 「引擎判的」），其余才走上面的 infra 档。对账只在**超时**路径生效（`isTimeout`）——
    * 失败路径（定义缺失 / spawn 失败）本轮根本没动过文件，不该替消费者下裁决。
    *
    * **纪律落地点（作者 2026-09-13 立册）**：判据集在**写前**固化（`frozen`，来自
    * `finish` 判定那一刻的状态，与写入无关），写后只做**对账**（`writeSetDrift` 比较
    * frozen 与实际写入集），**绝不从写后状态反推写入集**；差集非空 ⇒ 响亮 WARN。
    *
    * `private[agent]`：spec 直测面。 */
  private[agent] def degradeOutcomes(
    isTimeout: Boolean,
    detail: String,
    reconciled: MemoryQueue.ReconcileReport = MemoryQueue.ReconcileReport.empty
  ): IO[Int] = degradeOutcomesReport(isTimeout, detail, reconciled).map(_.written)

  /** [[degradeOutcomes]] 的报告形态（写前固化集 / 写后实际集 / 差集读数）。生产路径用它
    * （`finish` 需要把 drift 带进 [[Result]]），兼容入口 [[degradeOutcomes]] 只取计数值。 */
  private[agent] def degradeOutcomesReport(
    isTimeout: Boolean,
    detail: String,
    reconciled: MemoryQueue.ReconcileReport = MemoryQueue.ReconcileReport.empty
  ): IO[DegradeReport] =
    IO.blocking {
      val state = MemoryQueue.readState()
      // ── 写前固化（作者纪律）：判据集 = 判定那一刻的对账报告；对账只在超时路径生效 ──
      val frozen: Set[String] = if isTimeout then reconciled.refs else Set.empty[String]
      val judged = if isTimeout then reconciled.byRef else Map.empty[String, MemoryQueue.ReconcileVerdict]
      // 写前后备读数：写时仍 pending 的 ref 集（只作对账基准，不参与「写入集」推导）
      val pendingAtWrite = state.pending.map(_.id).toSet
      val writtenJudged  = scala.collection.mutable.LinkedHashSet.empty[String]
      var written        = 0
      state.pending.foreach { n =>
        judged.get(n.id) match
          case Some(v) =>
            // ④ 超时对账：效果已在盘上 ⇒ 引擎标终态（判据只取高精度两支，宁漏不误）
            if MemoryQueue.recordOutcome(n.id, v.result, AgentName, v.detail, AgentName).isRight then
              written += 1
              writtenJudged += n.id
          case None =>
            val infraCount = state.outcomes.count(o =>
              o.ref == n.id && MemoryQueue.EngineInfraResults.contains(o.result))
            val alreadyBlocked = state.lastOutcomeByRef.get(n.id).exists(_.result == MemoryQueue.ResultBlocked)
            val result =
              if infraCount >= MemoryQueue.MaxInfraOutcomesPerRef then MemoryQueue.ResultBlocked
              else if isTimeout then MemoryQueue.ResultTimeout
              else MemoryQueue.ResultNotRun
            val skip = result == MemoryQueue.ResultBlocked && alreadyBlocked
            if !skip && MemoryQueue.recordOutcome(n.id, result, AgentName, detail, AgentName).isRight then written += 1
      }
      // ── 写后对账（不重新推导）：冻结集 == 实际写入集 ? ──
      val actual = writtenJudged.toSet
      val judgedNotPending = frozen -- pendingAtWrite
      val drift = writeSetDrift(frozen, actual)
      if judgedNotPending.nonEmpty then
        logger.warnSync(
          s"[memory-track] reconcile write-set NOT frozen-confirmed: ${judgedNotPending.size} note(s) were frozen as judged but were already gone from pending at write time (queue touched between judging and writing): ${judgedNotPending.toList.sorted.take(10).mkString(", ")}")
      if drift.nonEmpty then
        logger.warnSync(
          s"[memory-track] reconcile write-set DRIFT (frozen != written, no re-derivation): ${drift.size} difference(s) — ${drift.take(10).mkString("; ")}")
      // 置位判据用**写后**的 pending 计数（C 批 ④ 口径修正）：本轮把该闭合的都闭合了 ⇒
      // 无可重试对象 ⇒ 不置位（不制造空转重试）；还有剩 ⇒ 照旧置位。
      val remaining = MemoryQueue.readState().pendingCount
      if remaining > 0 then
        MemoryTrackSignal.mark(
          s"previous memory-track run ${if isTimeout then "timeout" else "failed"} ($remaining note(s) not applied)")
      DegradeReport(written, actual.toList.sorted, drift)
    }

  private[agent] def finish(
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
      // ── ④ 超时对账（C 批 2026-09-13）：**先对账再降级**。跑后文件内容 `after` 已在手
      //    （内存里 `readAll(files)` 就是它），对账本身是纯函数（零写入）。
      //    只在 Timeout 分支算：失败分支（定义缺失 / spawn 失败）本轮没动过文件。
      //    `stateAtFinish` 是**写前**读数 ⇒ 判据集在写前就固化（作者纪律：写后不重推）。
      stateAtFinish <- IO.blocking(MemoryQueue.readState())
      // 跑后输入面与 dry-run 同源（[[planInput]] 带存在位，读盘故为 IO）：只在 Timeout 分支取。
      postFiles <-
        if attempt.status == Status.Timeout then planInput(after)
        else IO.pure(Map.empty[String, MemoryQueue.TargetFile])
      reconcile = if attempt.status == Status.Timeout then MemoryQueue.reconcile(stateAtFinish, postFiles)
        else MemoryQueue.ReconcileReport.empty
      degrade <- attempt.status match
        case Status.Timeout => degradeOutcomesReport(isTimeout = true, attempt.detail, reconcile)
        case Status.Failed  => degradeOutcomesReport(isTimeout = false, attempt.detail)
        case _              => IO.pure(DegradeReport(0, Nil, Nil))
      outcomesWritten = degrade.written
      _ <- attempt.status match
        case Status.Refused =>
          IO(MemoryTrackSignal.mark(s"previous memory-track round refused by a fail-closed preflight gate: ${attempt.detail.take(160)}"))
        case _ => IO.unit
      _ <- IO.pure(
        attempt.status match
          case Status.Completed =>
            logger.info(s"[memory-track] completed: ${notes.size} pending note(s) attempted, $changed file(s) changed")
          case Status.Failed =>
            logger.warn(s"[memory-track] FAILED (infra — no note marked rejected): ${attempt.detail} — memory falls back to the current files (install proceeds), queue entries stay pending, retry armed for the next compaction")
          case Status.Timeout =>
            logger.warn(
              s"[memory-track] TIMEOUT after ${hardTimeoutMs}ms (infra — no note marked rejected): " +
                s"reconcile closed ${reconcile.count} already-landed note(s) [${reconcile.render}], " +
                s"${outcomesWritten - reconcile.count} timeout outcome(s) written for the rest, " +
                s"write-set drift ${degrade.drift.size} (frozen ${reconcile.count} vs written ${degrade.judgedWritten.size}), " +
                s"entries stay pending, retry armed for the next compaction")
          case Status.DryRun =>
            logger.info(s"[memory-track] DRY-RUN (read-only): no agent spawned, no outcome written, no file touched")
          case Status.Refused =>
            logger.warn(s"[memory-track] REFUSED (fail-closed preflight gate): ${attempt.detail} — no agent spawned, no outcome written, no file touched")
          case Status.Skipped => ()
          // 暂停轮在 [[run]] 起点即返回、不进 [[finish]]（构造上不可达；显式列出只为
          // 穷尽匹配——`-Xfatal-warnings` 下新增枚举值必须在此交代）。
          case Status.Paused => ()
      )
    yield
      attempt.status match
        case Status.Completed => Result(Status.Completed, attempt.report, notes.size, 0, changed, attempt.alert)
        case Status.Failed    => Result(Status.Failed, attempt.detail, notes.size, outcomesWritten, changed, attempt.alert)
        case Status.Timeout =>
          Result(Status.Timeout, attempt.detail, notes.size, outcomesWritten, changed, attempt.alert,
            reconciled = reconcile.count, reconcileDrift = degrade.drift.size)
        case Status.DryRun    => Result(Status.DryRun, attempt.report, notes.size, 0, changed, None)
        case Status.Refused   => Result(Status.Refused, attempt.detail, notes.size, 0, changed, attempt.alert)
        case Status.Skipped   => Result.Skipped
        // 同 [[finish]] 的日志匹配：暂停轮不落 attempt（不可达，穷尽匹配用）。
        case Status.Paused    => Result.paused

end MemoryTrack
