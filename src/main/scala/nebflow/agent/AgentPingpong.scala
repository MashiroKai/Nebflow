/* 乒乓压缩（主会话无感上下文压缩一期，2026-10-09）——影子腿与换入执行体。
 *
 * 权威规格 = proto-pingpong/20261009_102847_pingpong-compaction-design-spec.md。
 * 结构：触发点 T fork 只读快照 C₀（Layer B 预处理后的 C₀′）交给进程内影子 fiber
 * 异步压缩（一次 LLM 调用，零写面），主腿不停车；S 就绪后经命令消息回投主
 * actor，在下一个轮边界（finishTurn 落盘后、下一件输入 dispatch 前）以
 * 「构建 → 持久化(durable) → 指针切换」三步原子换入（spec §D3）。
 *
 * 范围铁律（spec §G1）：全部行为差异收在触发门
 * `RootAgentIdentity.isRootAgent(agentDef, depth) && PingpongConfig.enabled`
 * 之后——谓词假的一切会话走原路径逐字节不变；门判定复用全仓唯一单点
 * RootAgentIdentity（禁第二份同表达式）。
 */
package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.*
import nebflow.actor.AgentCommand.*
import nebflow.core.compact.*
import nebflow.shared.*

private[agent] object AgentPingpong:
  import nebflow.agent.AgentActor.*

  // ============================================================
  // 触发门（§G1 / §A3）
  // ============================================================

  /**
   * 乒乓门 = 根会话谓词 × 配置开关（缺一不可）。纯函数便于 spec 静态验证：
   * dispatcher / node / delegate / kernel / team / social / standalone 非 Nebula
   * 根会话的一切 depth / 名字组合恒 false（行为零漂移）。
   */
  def gateOpen(agentDef: AgentDef, depth: Int, switchOn: Boolean): Boolean =
    switchOn && RootAgentIdentity.isRootAgent(Some(agentDef), depth)

  /**
   * maybeAutoCompact 触发分支的乒乓入口（§A3 判定点之一）：门开 → fork 影子后
   * 主腿照常续跑本派发；门关 → 老同步路径**逐字**（startDirectCompaction）。
   */
  def forkOrLegacyAuto(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    forkOrLegacy(
      agentDef,
      resources,
      depth,
      state,
      legacy = startDirectCompaction(agentDef, resources, depth, parentRef, state, replyTo, processing, "full"),
      // fork 后主腿照常续跑本派发（pipeLlmCall 六参形态；processing fn 仅老路径需要）
      onForked = st => pipeLlmCall(agentDef, resources, depth, parentRef, st, replyTo)
    )

  /**
   * UpdateContextWindow 模型切换顺手压缩点的乒乓入口（§A3 判定点之二）：门开 →
   * fork 后回 idle（主腿本就闲）；门关 → 老路径逐字（handleTriggerCompaction
   * 原调用形态，resumeAfterCompact=false 保持）。
   */
  def forkOrLegacyModelSwitch(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    forkOrLegacy(
      agentDef,
      resources,
      depth,
      state,
      legacy =
        handleTriggerCompaction(agentDef, resources, depth, parentRef, state, "full", None, resumeAfterCompact = false),
      onForked = st => IO.pure(idle(agentDef, resources, depth, parentRef, st))
    )

  private def forkOrLegacy(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    state: AgentState,
    legacy: => IO[Behavior[AgentCommand]],
    onForked: AgentState => IO[Behavior[AgentCommand]]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    PingpongConfig.enabled.flatMap { switchOn =>
      if !gateOpen(agentDef, depth, switchOn) then legacy
      else startShadow(agentDef, resources, depth, state).flatMap(onForked)
    }

  /** §A4 排队位日志（两处触发点共用）。 */
  def logQueued(
    agentDef: AgentDef,
    depth: Int,
    state: AgentState,
    detail: String
  )(using ctx: ActorContext[AgentCommand]): Unit =
    logAgentEvent(
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "pp-queued",
      s"$detail shadow=${state.pingpong.shadowPending.map(_.shadowId).getOrElse("-")}"
    )

  // ============================================================
  // fork（§B：C₀ 快照 + 影子 fiber）
  // ============================================================

  /**
   * B1 影子腿的最小 system 框：一句角色声明。主腿的系统提示全文（工具定义、
   * flows、记忆块）不进影子腿（§B1「不含」清单）。
   */
  private val ShadowSystemFrame =
    "You are a session summarizer. Follow the compaction instructions in the final user message exactly. TEXT ONLY — no tool calls."

  /**
   * fork：C₀ 只读快照 + Layer B 预处理 + 起影子 fiber + 置单飞行位。
   *
   * C₀′（prepareCompactionInput）与文件恢复原料、projectRoot 全部在**主 actor
   * IO 链内**取定后以不可变值交给 fiber —— 影子 fiber 代码路径零写（§B2-3 四重
   * 只读之写面级：消息历史结构共享零拷贝；影子不注册、无 sessionId、唯一产出物
   * S 经命令消息交回）。
   *
   * fiber 用 `.start`（**非** ctx.forkTurn）起：影子不是主腿的 turn，
   * cancelCurrentTurn（Interrupt 面）不得牵连它；其完成经 PingpongSwapReady /
   * PingpongShadowFailed 命令回投，会话死后投递自动失效（stale 校验丢弃）。
   * 无 wall-clock 强杀（§B3 资源上限 1-4：输出帽/输入界/失败退避/活性观测）。
   */
  private[agent] def startShadow(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    state: AgentState,
    queuedNote: Boolean = false
  )(using ctx: ActorContext[AgentCommand]): IO[AgentState] =
    state.sessionId match
      case None => IO.pure(state) // 无会话面（防御；根会话恒有）——无事可 fork，原样续跑
      case Some(sid) =>
        val forkMsgCount = state.messages.size
        val shadowId = s"ppc-$sid-$forkMsgCount" // §B2-2 逻辑 id，仅日志/档案关联
        val forkedAt = System.currentTimeMillis()
        val job = PingpongShadowJob(shadowId, forkMsgCount, forkedAt)
        for
          // B1-2：C₀′ = 同一 Layer B 预处理（超大 ToolResult → 持久化占位）。
          // 在主 actor IO 链执行：prep 会补盘 tool-results 草稿面，影子 fiber 保持零写。
          c0Prime <- CompactUtils.prepareCompactionInput(state.messages, sid)
          // B1-5/6：文件恢复原料 + projectRoot（fork 时刻只读投影；与现行压缩轮同款取法）
          readPaths <- state.readTracker
            .map(_.recentFiles(CompactConfig().postCompactMaxFiles).map(_.map(_.toString)))
            .getOrElse(IO.pure(Nil))
          projectRoot <- resolveProjectRoot(resources, state)
          _ <- IO(
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "pp-fork",
              s"T=$forkMsgCount est=${TokenEstimator.estimate(state.messages)} " +
                s"threshold=${state.compactThresholdTokens} shadow=$shadowId queued=$queuedNote"
            )
          )
          // §F2：影子状态文件 phase=compacting（§E-7 重启恢复依据；崩溃于 fiber 起飞
          // 前后都落到「作废重触发」格，一致）
          _ <- PingpongStateFile.save(
            sid,
            PingpongStateFile
              .Record(
                PingpongStateFile.Compacting,
                shadowId,
                forkMsgCount,
                forkedAt,
                None,
                0,
                state.pingpong.ppFailures
              )
          )
          _ <- shadowFiber(agentDef, resources, depth, state, sid, job, c0Prime, readPaths, projectRoot).start
        // forkQueued 随 fork 置位：fork 自身即一次越阈观测（§A2）；换入完成时的
        // 第二拍复查按 estimated(S+Δ) > threshold 裁决，位本身不直接触发。
        yield state.withShadowPending(Some(job)).withForkQueued(true)

        end for

  /**
   * 影子 fiber：一次 provider LLM 调用（resources.llm —— 与现行压缩同一模型调用
   * 资产/回退链，§B3 默认同主腿模型）+ pingpong 模式解析 + 命令回投。全程零写。
   */
  private def shadowFiber(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    state: AgentState,
    sid: String,
    job: PingpongShadowJob,
    c0Prime: List[Message],
    readPaths: List[String],
    projectRoot: String
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    // §B1-3：一期暂借 RootCompactReminder 文案（buildCompactReminder(0) → Root 模板，
    // 含 CompactPreamble 的 LANGUAGE RULE / TAIL FIDELITY RULE）；二期固化
    // PingpongSummaryReminder（§H2-1），段落兼容。
    val reminder = CompactService.buildCompactReminder(depth = 0, isLead = false, sessionId = Some(sid))
    val request = LlmRequest(
      messages = c0Prime :+ reminder,
      sessionId = job.shadowId, // 逻辑 id（路由/日志关联），非会话面
      agentId = agentDef.name,
      tools = None, // §B1：影子腿无工具
      thinking = None,
      systemStable = Some(ShadowSystemFrame),
      agentModel = agentDef.model, // §B3：默认与主腿同模型；pingpong.shadowModel 二期（§H2）
      // 与现行压缩轮同款 opt-out：server 端搜索永不进摘要请求（WebSearch P0 语义）
      searchAllowed = false
    )
    resources.llm
      .send(request) // 一次性调用：输出流吸收为文本，不外发 WS（§B3，主腿零感知）
      .attempt
      .flatMap {
        case Right(resp) =>
          // §C：S 解析——pingpong 分支（标签必需 / 无尾部保留 / 硬帽校验）
          FullCompact.parsePingpongResponse(
            resp.reply,
            c0Prime,
            projectRoot,
            readPaths,
            forkSeq = job.forkMsgCount
          ) match
            case Right(outcome) =>
              val sMessages = outcome.messages // 恒 [sMessage]（pingpong 无尾部保留）
              val est = TokenEstimator.estimate(sMessages)
              (ctx.self ! PingpongSwapReady(job.shadowId, sMessages, job.forkMsgCount, job.forkedAt, est))
                .handleErrorWith(_ => IO.unit)
            case Left(err) =>
              (ctx.self ! PingpongShadowFailed(job.shadowId, err)).handleErrorWith(_ => IO.unit)
        case Left(err) =>
          (ctx.self ! PingpongShadowFailed(job.shadowId, err.getMessage)).handleErrorWith(_ => IO.unit)
      }
      .handleErrorWith(e => (ctx.self ! PingpongShadowFailed(job.shadowId, e.getMessage)).handleErrorWith(_ => IO.unit))

  end shadowFiber

  private def resolveProjectRoot(resources: SharedResources, state: AgentState): IO[String] =
    state.folderId match
      case Some(fid) =>
        resources.sessionStore.resolveProjectRoot(Some(fid)).map(_.getOrElse(resources.projectRoot.toString))
      case None => IO.pure(resources.projectRoot.toString)

  // ============================================================
  // S 就绪处置（§D2：idle 立即换入 / processing 只置位）
  // ============================================================

  /** §D2-1：SwapReady 到达时主腿 idle → 立即执行换入。返回 idle 应持的状态。 */
  def handleSwapReadyIdle(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    state: AgentState,
    cmd: PingpongSwapReady
  )(using ctx: ActorContext[AgentCommand]): IO[AgentState] =
    validateSwapReady(agentDef, depth, state, cmd) match
      case None => IO.pure(state)
      case Some(ready) =>
        state.sessionId.fold(IO.pure(state)) { sid =>
          recordSReady(agentDef, depth, state, sid, ready)
            .flatMap(st => executeSwap(agentDef, resources, depth, st, sid, ready).map(_.state))
        }

  /**
   * §D2-2：SwapReady 到达时主腿 processing/frozen → 只置 sReadyPending + 落
   * state.json（s-ready，崩溃后 S 续用的依据），换入挂在本轮 finishTurn→idle
   * 边界（本轮提交与换入之间不插入任何 LLM 调用）。
   */
  def handleSwapReadyRecorded(
    agentDef: AgentDef,
    depth: Int,
    state: AgentState,
    cmd: PingpongSwapReady
  )(using ctx: ActorContext[AgentCommand]): IO[AgentState] =
    validateSwapReady(agentDef, depth, state, cmd) match
      case None => IO.pure(state)
      case Some(ready) =>
        state.sessionId.fold(IO.pure(state))(sid => recordSReady(agentDef, depth, state, sid, ready))

  /**
   * 影子完成校验：shadowPending 与 shadowId 对得上才算数（reset / 重复投递 /
   * 僵尸 fiber 的迟到件一律丢弃——§E-10 的收口机制）。
   */
  private def validateSwapReady(
    agentDef: AgentDef,
    depth: Int,
    state: AgentState,
    cmd: PingpongSwapReady
  )(using ctx: ActorContext[AgentCommand]): Option[PingpongSReady] =
    if state.pingpong.shadowPending.exists(_.shadowId == cmd.shadowId) then
      Some(
        PingpongSReady(
          cmd.shadowId,
          cmd.sMessages,
          cmd.forkMsgCount,
          cmd.forkedAt,
          cmd.sTokensEst,
          latencyMs = System.currentTimeMillis() - cmd.forkedAt
        )
      )
    else
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "pp-stale-swap",
        s"shadow=${cmd.shadowId} pending=${state.pingpong.shadowPending.map(_.shadowId).getOrElse("-")} — discarded"
      )
      None

  /** pp-s-ready 观测（§F1）+ state.json phase=s-ready（§F2 / §E-8 恢复依据）。 */
  private def recordSReady(
    agentDef: AgentDef,
    depth: Int,
    state: AgentState,
    sid: String,
    ready: PingpongSReady
  )(using ctx: ActorContext[AgentCommand]): IO[AgentState] =
    val sText = ready.sMessages.headOption.flatMap(_.content.left.toOption).getOrElse("")
    val deltaTurns = countRoundStarts(state.messages.drop(ready.forkMsgCount))
    for
      _ <- PingpongStateFile.save(
        sid,
        PingpongStateFile
          .Record(
            PingpongStateFile.SReady,
            ready.shadowId,
            ready.forkMsgCount,
            ready.forkedAt,
            Some(sText),
            ready.sTokensEst,
            state.pingpong.ppFailures
          )
      )
      _ <- IO(
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "pp-s-ready",
          s"latency=${ready.latencyMs}ms sTokens=${ready.sTokensEst} deltaTurns=$deltaTurns sPath=-"
        )
      )
    yield state.withSReadyPending(Some(ready))
    end for
  end recordSReady

  // ============================================================
  // 影子失败（§E-1/2/3）
  // ============================================================

  /**
   * 失败态：ppFailures+1、清全部乒乓位（退避后下个判定点自然重新 fork）；连续
   * circuitBreakerMax(3) 次 → 下个触发回落老同步路径一拍（触发点的 ppFailures
   * 检查）。僵尸失败件（shadowPending 不匹配）丢弃。
   */
  def handleShadowFailed(
    agentDef: AgentDef,
    depth: Int,
    state: AgentState,
    cmd: PingpongShadowFailed
  )(using ctx: ActorContext[AgentCommand]): IO[AgentState] =
    if !state.pingpong.shadowPending.exists(_.shadowId == cmd.shadowId) then
      IO(
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "pp-stale-failure",
          s"shadow=${cmd.shadowId} — discarded"
        )
      ).as(state)
    else
      val failures = state.pingpong.ppFailures + 1
      val cfg = CompactConfig()
      val willFallback = failures >= cfg.circuitBreakerMax
      for
        _ <- state.sessionId.fold(IO.unit)(sid => PingpongStateFile.clear(sid))
        _ <- IO(
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "pp-failed",
            s"err=${cmd.err.take(160)} failures=$failures/${cfg.circuitBreakerMax}" +
              s"${if willFallback then " → next trigger falls back to legacy sync" else ""}"
          )
        )
      yield state
        .withShadowPending(None)
        .withSReadyPending(None)
        .withForkQueued(false)
        .withPpFailures(failures, System.currentTimeMillis())
      end for

  // ============================================================
  // 三步原子换入（§D3）
  // ============================================================

  final case class SwapOutcome(landed: Boolean, state: AgentState)

  /**
   * §D3 原子换入三步（崩溃一致序：构建 → 持久化 → 指针切换）：
   *   ① 构建   newList = sMessage :: state.messages.drop(forkMsgCount)
   *           （Δ = 下标 (forkMsgCount, now] 的视图截取，零复制；forkMsgCount >
   *           size ⇒ 状态错乱，放弃换入走 §E-6）
   *   ② 持久化 SessionStore.durableSwapMessages（tmp fsync + ATOMIC_MOVE + 在队
   *           debounce 项撤销——读者见旧或见新，绝无半态）
   *   ③ 切换   withMessages(newList) + 清全部乒乓位 + withLatestUsage(None)
   *           （与现行换入同款：陈旧超标读数会误触硬守卫）+ 缓存 v2 失效
   *
   * ②失败（磁盘满等）→ 磁盘旧文件原样未动、内存不动，作废影子清全部位（§E-5），
   * 下个判定点重新 fork。换入成功 → 清 state.json、档案 + pp-swap 观测、排队位
   * 第二拍复查（§A2/A4）。
   */
  def executeSwap(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    state: AgentState,
    sid: String,
    ready: PingpongSReady
  )(using ctx: ActorContext[AgentCommand]): IO[SwapOutcome] =
    if ready.forkMsgCount > state.messages.size then
      // §E-6：换入构建断言失败（如手动压缩在飞期间替换过历史）——内部错误格：
      // 放弃换入、作废影子、照常继续旧上下文（一致）。
      for
        _ <- PingpongStateFile.clear(sid)
        _ <- IO(
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "pp-swap-invalid",
            s"forkMsgCount=${ready.forkMsgCount} size=${state.messages.size} shadow=${ready.shadowId}"
          )
        )
      yield SwapOutcome(landed = false, state = state.withPingpongCleared)
    else
      val sMessage = ready.sMessages.head // parse 保证 ≥1（CompactOutcome 恒含摘要消息）
      val delta = state.messages.drop(ready.forkMsgCount)
      val newList = sMessage :: delta // ① 构建：Δ 零丢失（逐条原位）+ S 打头
      val deltaTurns = countRoundStarts(delta)
      for
        persisted <- resources.sessionStore.durableSwapMessages(sid, newList).attempt // ② 持久化
        outcome <- persisted match
          case Left(e) =>
            // §E-5：持久化失败——磁盘旧文件原样（AtomicJson 失败即目标未动）、内存不动
            for
              _ <- PingpongStateFile.clear(sid)
              _ <- IO(
                logAgentEvent(
                  agentDef,
                  depth,
                  state.sessionId,
                  state.sessionName,
                  "pp-swap-failed",
                  s"err=${e.getMessage} shadow=${ready.shadowId} — swap abandoned, re-fork at next threshold crossing"
                )
              )
            yield SwapOutcome(landed = false, state = state.withPingpongCleared)
          case Right(_) =>
            // ③ 指针切换（与现行 CompactionComplete 换入态清理同形）
            val swapped = state
              .withMessages(newList)
              .withShadowPending(None)
              .withSReadyPending(None)
              .withForkQueued(false)
              .withLatestUsage(None)
              .invalidateSystemStableCache
            for
              _ <- PingpongStateFile.clear(sid)
              _ <- ctx.forkTurn(swapObservability(agentDef, resources, depth, state, sid, swapped, ready, deltaTurns))
              second <- secondBeat(agentDef, resources, depth, swapped)
            yield SwapOutcome(landed = true, state = second)
      yield outcome

      end for

  /**
   * §F1/F2：pp-swap 日志 + 档案（HistoryArchiver 原样复用，before = 被替换的
   * C₀+Δ 全列表，after = S+Δ）。forkTurn 异步：观测失败绝不阻断换入本身。
   */
  private def swapObservability(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    state: AgentState,
    sid: String,
    swapped: AgentState,
    ready: PingpongSReady,
    deltaTurns: Int
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    val estBefore = TokenEstimator.estimate(state.messages)
    val estAfter = TokenEstimator.estimate(swapped.messages)
    resources.historyArchiver
      .archiveCompaction(
        sessionId = sid,
        sessionName = state.sessionName,
        agentName = agentDef.name,
        before = state.messages,
        after = swapped.messages,
        mode = FullCompact.PingpongMode,
        extra = Map(
          "forkSeq" -> ready.forkMsgCount.toString,
          "deltaTurns" -> deltaTurns.toString,
          "preservedRounds" -> "0"
        )
      )
      .void
      .handleErrorWith(_ => IO.unit) *>
      IO(
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "pp-swap",
          s"before=${state.messages.size}msg after=${swapped.messages.size}msg deltaTurns=$deltaTurns " +
            s"forkSeq=${ready.forkMsgCount} estBefore=$estBefore estAfter=$estAfter"
        )
      )

  end swapObservability

  /**
   * §A2 换入后立即复查 / §A4 排队位解锁：forkQueued 在位 ⇒ estimated(S+Δ) 仍过
   * 阈值才乒乓第二拍（立即重新 fork——C₀ 自动包含压缩期间积累的 Δ，覆盖面更新
   * 鲜）；阈值已清 ⇒ 只清位，等下个自然触发。不需特殊逻辑，复查即裁决。
   */
  private def secondBeat(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    swapped: AgentState
  )(using ctx: ActorContext[AgentCommand]): IO[AgentState] =
    if !swapped.pingpong.forkQueued then IO.pure(swapped)
    else if TokenEstimator.estimate(swapped.messages) <= swapped.compactThresholdTokens then
      IO.pure(swapped.withForkQueued(false))
    else startShadow(agentDef, resources, depth, swapped, queuedNote = true)

  // ============================================================
  // 重启恢复（§E-7/8/9）
  // ============================================================

  /**
   * spawn 自投的恢复入口（idle 态处理）。三格：
   *   无 state.json（§E-9）→ 原样启动，阈值判定自然再触发；
   *   phase=compacting（§E-7）→ 作废重触发：清 state.json 清位；Δ 无损（每轮已
   *     落盘），下个判定点重新 fork（重 fork 的 C₀ 自动包含崩溃前 Δ）；
   *   phase=s-ready（§E-8）→ S 续用换入（S 是已产出纯数据，不重算）；幂等闸：
   *     崩溃若发生在换入持久化之后、清档之前，磁盘头部已是本 forkSeq 的 pingpong
   *     摘要 ⇒ 只清档不重换（重换会截掉 Δ）。
   * 换入语义与开关解耦（§E-12）：恢复换入不读开关。非根会话恒 no-op（谓词闸）。
   */
  def recoverAtIdle(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): IO[AgentState] =
    if !RootAgentIdentity.isRootAgent(Some(agentDef), depth) then IO.pure(state)
    else
      state.sessionId.fold(IO.pure(state)) { sid =>
        PingpongStateFile.load(sid).flatMap {
          case None => IO.pure(state)
          case Some(rec) if rec.phase == PingpongStateFile.Compacting =>
            for
              _ <- PingpongStateFile.clear(sid)
              _ <- IO(
                logAgentEvent(
                  agentDef,
                  depth,
                  state.sessionId,
                  state.sessionName,
                  "pp-recover",
                  s"phase=compacting shadow=${rec.shadowId} → discarded; re-fork at next threshold crossing (Δ intact)"
                )
              )
            yield state
          case Some(rec) if rec.phase == PingpongStateFile.SReady =>
            PingpongStateFile.sMessageOf(rec) match
              case None =>
                for
                  _ <- PingpongStateFile.clear(sid)
                  _ <- IO(
                    logAgentEvent(
                      agentDef,
                      depth,
                      state.sessionId,
                      state.sessionName,
                      "pp-recover",
                      s"phase=s-ready shadow=${rec.shadowId} → sText empty, discarded"
                    )
                  )
                yield state
              case Some(sMsg) =>
                val alreadySwapped =
                  state.messages.headOption.exists(h => FullCompact.isPingpongSummaryWithForkSeq(h, rec.forkMsgCount))
                if alreadySwapped then
                  for
                    _ <- PingpongStateFile.clear(sid)
                    _ <- IO(
                      logAgentEvent(
                        agentDef,
                        depth,
                        state.sessionId,
                        state.sessionName,
                        "pp-recover",
                        s"phase=s-ready forkSeq=${rec.forkMsgCount} → already swapped, sidecar cleared"
                      )
                    )
                  yield state
                else if rec.forkMsgCount > state.messages.size then
                  for
                    _ <- PingpongStateFile.clear(sid)
                    _ <- IO(
                      logAgentEvent(
                        agentDef,
                        depth,
                        state.sessionId,
                        state.sessionName,
                        "pp-swap-invalid",
                        s"recover forkMsgCount=${rec.forkMsgCount} size=${state.messages.size} — discarded"
                      )
                    )
                  yield state
                else
                  val ready =
                    PingpongSReady(rec.shadowId, List(sMsg), rec.forkMsgCount, rec.forkedAt, rec.sTokensEst, 0L)
                  for
                    _ <- IO(
                      logAgentEvent(
                        agentDef,
                        depth,
                        state.sessionId,
                        state.sessionName,
                        "pp-recover",
                        s"phase=s-ready forkSeq=${rec.forkMsgCount} → reusing S (no re-compute), swap at this idle boundary"
                      )
                    )
                    outcome <- executeSwap(agentDef, resources, depth, state, sid, ready)
                  yield outcome.state
                end if
          case Some(_) => IO.pure(state)
        }
      }

  // ============================================================
  // 观测辅助
  // ============================================================

  /**
   * Δ 区轮数（pp-s-ready / pp-swap 的 deltaTurns，§F1）。观测口径与
   * FullCompact.isRoundStart 对齐（round start = 无 tool_result 块、带非空文本的
   * User 消息）；该函数私有，此处保留一份仅入日志的观测投影——不进任何判定面。
   */
  private def countRoundStarts(msgs: List[Message]): Int =
    msgs.count { m =>
      m.role == MessageRole.User && {
        m.content match
          case Left(text) => text.trim.nonEmpty
          case Right(blocks) =>
            blocks.exists { case ContentBlock.Text(t) => t.trim.nonEmpty; case _ => false } &&
            !blocks.exists(_.isInstanceOf[ContentBlock.ToolResult])
      }
    }

end AgentPingpong
