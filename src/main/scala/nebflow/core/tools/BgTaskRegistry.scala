package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.RootAgentIdentity
import nebflow.shared.{Defaults, NebflowLogger}

/**
 * Global registry of active background tasks (local Bash + remote).
 * Used by the WS reconnect handler to sync frontend state after disconnects.
 *
 * Tasks are registered when they start and removed when they complete.
 * The frontend queries this on WS reconnect to recover missed completion events.
 *
 * 节点完成闸：
 * 本 registry 同时是节点完成闸的等待集权威——NodeEngine 观察桥在 Completed
 * 时查 waitingFor(nodeSessionId)，非空则 hold（不终态化节点）直到全部等待型
 * 后台任务完成回调（ExternalEvent 唤醒续跑轮 → 又一次 Completed → 复检）。
 * - 纳入等待集 =「完成时会向会话返回通知」的任务：显式 run_in_background
 *   （BashTool emitBgTaskStarted）+ 远程后台（RemoteExecutor）。
 * - persistent=true（服务型 server）不纳入（waitingFor 过滤），但仍在册——
 *   前端 WS 重连快照与实时指示器照常可见。
 * - 超时/停滞杀的终局原因记账（markFailed/drainFailures）：节点终态化前检查，
 *   命中 → 节点 failed+注明，禁止静默 completed。
 */
object BgTaskRegistry:
  private val logger = NebflowLogger.forName("nebflow.bg-registry")

  case class ActiveTask(
    jobId: String,
    sessionId: String,
    description: String,
    startedAtMs: Long,
    kind: String, // "local" | "remote"
    /**
     * 顶层根会话 id（计数/列表分叉修复）：前端 backgroundTaskUpdate
     * 信封与 activeBgTasks 快照共用本键分桶。空 = 无 agent 上下文（REST 直调等）
     * 或旧调用方未传 → activeTasksJson 回退按 sessionId 分组。
     */
    rootSessionId: String = "",
    /**
     * 节点完成闸：true = 服务型（长驻 server），
     * 不纳入节点等待集（节点完成不等它）；false = 等待型，纳入。默认 false
     * = 等待——绝大多数后台任务是编译/测试/CI，「完成即通知」语义本就要求
     * 等完再交付；server 是少数派且可显式申报。
     */
    persistent: Boolean = false,
    /**
     * 来源类别（后台任务面板重设计）：每任务标注开启
     * 它的节点类别——"nebula" / "dispatcher" / "node"。由 originFor 从注册
     * 会话 id 前缀推导（node-<uuid8> 随机段不含 Flow Map nodeId，故标识取
     * 会话名=节点名，见 originLabel）。
     */
    origin: String = "nebula",
    /**
     * 来源显示名：Nebula / dispatcher/<project>（分发器 sessionName 即此
     * 形态）/ Flow Map 节点名（节点 sessionName=nodeName）。空 = 调用方未传
     * → 前端按 origin 类别兜底。
     */
    originLabel: String = "",
    /**
     * 输出查看补全批（作者令 2026-10-25「远端任务看不到」）：设备通道任务的对端
     * 设备名（kind=remote 时非空）——remote-output 路由按它经既有 resolvePeer
     * 面解析对端拉日志尾窗。空 = 本地任务 / 未登记。
     */
    deviceName: String = "",
    /**
     * 对端日志路径（remote 专属；RemoteBgOutputLog.parseRedirect 从命令文本
     * 解析——无可解析重定向 = 空 ⇒ 面板走「输出不可用 + 原因」降级，不猜路径）。
     */
    outputPath: String = "",
    /**
     * 终态轨行内实际态（P0 面板投影批 · 设计稿 20261030 §3.4）：快照帧 status
     * 改读本字段、不再硬编码字面量。P0 恒 "running"（在册即未终态；终态记账随
     * P2 取消/death-watch 批）；默认值兼容旧调用方（向后兼容铁律）。
     */
    status: String = "running",
    /**
     * 健康轨行内字段（设计稿 §3.1/§3.4 · 单一事实源）：健康枚举 = [[BgHealth.of]]
     * 从本三字段派生（禁第二状态源，与 MergeQueue.queueViewOf 同纪律）。
     *   - suspicionLevel = 嫌疑级数（0 = healthy，>0 = suspect）；
     *   - escalatedAtMs  = 升级时刻（>0 = escalated，优先于 suspect）；
     *   - lastActivityMs = 行内最近活动戳（P1 判据输入）。
     * P0 数据面默认全零（恒 healthy、零行为变化）；判据与写方 = P1
     * BgLifecycleReconciler，写缝 = [[BgHealth]] 词表经 updateHealth 翻译落行。
     */
    suspicionLevel: Int = 0,
    escalatedAtMs: Long = 0L,
    lastActivityMs: Long = 0L,
    /**
     * 任务模式标注批（2026-10-30 作者令「派发任务显示执行模式」）：开启本任务的
     * delegate 会话的**生效执行形态**（"kernel" | "dispatcher"），注册时经
     * DelegateRegistry.modeFaceOf 解析。空 = 非 delegate 会话 / 双模式总闸关
     * （Inert）/ 记录缺席 ⇒ 前端零渲染（徽标只在两态之一出现）。与 origin
     * （谁开启的类别轴）正交：dispatcher 形态 delegate 自己开的任务 origin 仍
     * 是 "nebula"。
     */
    ownerMode: String = "",
    /**
     * 后台任务归属批（作者令 2026-10-31「后台任务行标归属」）：开启本任务的
     * 会话的 delegate 批次短名（DelegateRegistry.Record.name，注册时经
     * DelegateRegistry.ownerNameOf 单点解析落行）。空 = 非 delegate / 无名旧
     * 记录 ⇒ 前端回退既有来源显示（零渲染）。与 ownerMode 同纪律：注册面解析
     * 一次、行内承载、快照（activeTasksJson）与实时信封（backgroundTaskUpdate）
     * 同源携带。
     */
    ownerName: String = ""
  )

  /**
   * 节点完成闸：等待集内任务被超时/停滞看护杀掉的终局记账。节点终态化前
   * drainFailures 检查，命中 → failed+注明（拒绝静默 completed）。
   */
  case class FailedBgTask(
    jobId: String,
    sessionId: String,
    rootSessionId: String,
    description: String,
    /** 杀因原文（TimeoutException message：idle 超时 / 硬超时+停滞）。 */
    cause: String,
    failedAtMs: Long
  )

  /**
   * bgHealth 徽标计数（设计稿 §3.2 BadgeSync · heldReceipts 同款帧载荷）：
   * suspect/escalated 由行内健康字段派生（BgHealth.of）；unread = P1 激活语义——
   * 「已升级但编排者通知未送达」的任务数（Escalate 执行面按投递路由记账，
   * 见 [[unreadEscalations]]）。
   */
  case class BgHealthCounts(
    unread: Int = 0,
    suspect: Int = 0,
    escalated: Int = 0
  )

  private val tasks: Ref[IO, Map[String, ActiveTask]] = Ref.unsafe(Map.empty)
  private val failures: Ref[IO, Map[String, FailedBgTask]] = Ref.unsafe(Map.empty)

  // ── 会话级后台任务创建速率闸（#753 面板刷屏熔断批）──────────────────────
  //
  // 实证：delegate-nebflow-59f171d8 八分钟造 88 个 waiter 型后台任务刷爆面板
  // （后台任务 81 条同行族）。闸语义（两档）：
  //   - 告警档（BgRateWarnAfter）：窗口内创建数达到即 logger.warn（引擎侧告警，
  //     不拒绝）；
  //   - 熔断档（BgRateBreakerAfter）：窗口内创建数达到即熔断——本会话
  //     BgRateBreakerCooldownMs 内的新后台任务创建一律拒绝（可读错误回执给
  //     LLM），熔断触发时同时向 wsSend 发 `bgRateAlert` 帧（前端 toast 告警）。
  //
  // 状态载体 = 单 Ref 双字段（SessionRateState）——判定与记账一次 modify 完成，
  // 并发创建无竞态双记。进程内存态（重启即清，与 registry 本体同生命周期）。
  private final case class SessionRateState(
    creations: Vector[Long] = Vector.empty,
    breakerUntilMs: Long = 0L
  )

  private val rateStates: Ref[IO, Map[String, SessionRateState]] = Ref.unsafe(Map.empty)

  /**
   * 熔断面板告警帧的进程外观察面（#753 验收「超阈值告警」）：熔断触发时
   * 记录最近一次事件（sessionId / 触发时刻 / 窗口计数）——spec 断言用，
   * 与 gateCreation 的 IO 面同点写入。
   */
  final case class RateAlert(sessionId: String, atMs: Long, windowCount: Int)

  private val lastAlert: Ref[IO, Option[RateAlert]] = Ref.unsafe(Option.empty)

  def lastRateAlert: IO[Option[RateAlert]] = lastAlert.get

  /**
   * 会话级后台任务创建闸（#753）：每次新后台任务创建（显式 run_in_background /
   * 前台预算转后台收编）前调用。allowed ⇒ 调用方照常建任务（本拍时间戳已入窗）；
   * !allowed ⇒ 拒绝（message = 可读错误文案，直接作为工具面错误回执）。判定与
   * 记账（本拍时间戳入窗、熔断到期翻转）一次 Ref.modify 完成。返回完整 Decision
   * ——breakerEngaged（触发沿）供调用方发告警帧，熔断激活期的重复拒绝不再重发
   * （防告警刷屏）。空 sessionId（REST 直调等无主任务）不设闸。
   */
  def gateCreation(sessionId: String): IO[BgRateGate.Decision] =
    val sid = sessionId.trim
    if sid.isEmpty then
      IO.pure(
        BgRateGate.Decision(
          allowed = true,
          breakerEngaged = false,
          warn = false,
          recentCount = 0,
          breakerUntilMs = None,
          message = None
        )
      )
    else
      IO.monotonic.map(_.toMillis).flatMap { _ =>
        IO.realTime.map(_.toMillis).flatMap { now =>
          val windowMs = Defaults.BgRateWindowMs
          rateStates
            .modify { m =>
              val st = m.getOrElse(sid, SessionRateState())
              val prior = st.creations.filter(_ > now - windowMs)
              val breakerActive = st.breakerUntilMs > now
              val decision = BgRateGate.decide(
                prior,
                if breakerActive then Some(st.breakerUntilMs) else None,
                now,
                windowMs,
                Defaults.BgRateWarnAfter,
                Defaults.BgRateBreakerAfter,
                Defaults.BgRateBreakerCooldownMs
              )
              val nextState =
                if decision.allowed then
                  st.copy(
                    creations = prior :+ now,
                    breakerUntilMs = decision.breakerUntilMs.getOrElse(0L)
                  )
                else st.copy(creations = prior, breakerUntilMs = decision.breakerUntilMs.getOrElse(st.breakerUntilMs))
              (m + (sid -> nextState), decision)
            }
            .flatTap { d =>
              // 告警面：warn 档与熔断档都留引擎侧告警日志；熔断同时记账
              // lastAlert（spec 观察面）。
              val logWarn =
                if d.breakerEngaged then
                  logger.warn(
                    s"[bg-rate-gate] breaker ENGAGED for session '$sid': ${d.recentCount} background tasks created within " +
                      s"${Defaults.BgRateWindowMs / 60000}min (cooldown ${Defaults.BgRateBreakerCooldownMs / 60000}min)"
                  )
                else if d.warn then
                  logger.warn(
                    s"[bg-rate-gate] warn for session '$sid': ${d.recentCount} background tasks created within " +
                      s"${Defaults.BgRateWindowMs / 60000}min (breaker at ${Defaults.BgRateBreakerAfter})"
                  )
                else IO.unit
              val recordAlert =
                if d.breakerEngaged then lastAlert.set(Some(RateAlert(sid, now, d.recentCount)))
                else IO.unit
              logWarn *> recordAlert
            }
            .map(identity)
        }
      }

    end if

  end gateCreation

  /**
   * 来源推导（后台任务面板重设计）：从注册会话 id 前缀 + 会话名
   * 推导 (origin 类别, originLabel 显示名)。node-<uuid8> 的随机段不含 Flow Map
   * nodeId（NodeEngine 生成），节点标识取 sessionName（NodeEngine 注入 = Flow Map
   * 节点名）；dispatcher 的 sessionName 恒为 "dispatcher/<project>"。其余一律
   * nebula（含空 sessionId 的 REST 直调）。
   */
  def originFor(sessionId: String, sessionName: Option[String]): (String, String) =
    val name = sessionName.getOrElse("")
    if sessionId.startsWith("node-") then ("node", if name.nonEmpty then name else sessionId)
    else if sessionId.startsWith("dispatcher-") then ("dispatcher", if name.nonEmpty then name else "dispatcher")
    else ("nebula", RootAgentIdentity.Name)

  /**
   * 设备来源类别（多设备后台任务可见批 · 作者令 2026-10-25「多设备的后台任务
   * 我们也要可见」）：设备通道后台任务（remoteable 六件带 `device` + 显式
   * `run_in_background`）的 origin 恒为设备名——「跑在哪台设备」与
   * nebula/dispatcher/node（谁开启）是**不同轴**，面板 chip 直接可辨
   * KAI / DESKTOP-…。单点构造：RemoteExecutor 注册面与 WS 发射面同源，禁第二份
   * 字面量；origin 类别值 = "device"（前端 chip 类 `.bg-origin-device`），
   * originLabel = 设备名原样（大小写保留）。
   */
  def deviceOrigin(deviceName: String): (String, String) = ("device", deviceName)

  def register(
    jobId: String,
    sessionId: String,
    description: String,
    kind: String,
    rootSessionId: String = "",
    persistent: Boolean = false,
    origin: String = "nebula",
    originLabel: String = "",
    deviceName: String = "",
    outputPath: String = "",
    ownerMode: String = "",
    ownerName: String = "",
    /**
     * 行龄起点（death-watch 豁免窗的输入；spec 回拨造例用——death-watch 误报修复批
     * ②⑤⑥ 的边界断言需行龄 > 幼龄窗）。缺省 = 注册时刻（生产行为逐字不变）。
     */
    startedAtMs: Long = System.currentTimeMillis()
  ): IO[Unit] =
    tasks.update(
      _ + (jobId -> ActiveTask(
        jobId,
        sessionId,
        description,
        startedAtMs,
        kind,
        rootSessionId,
        persistent,
        origin,
        originLabel,
        deviceName,
        outputPath,
        ownerMode = ownerMode,
        ownerName = ownerName
      ))
    )

  def unregister(jobId: String): IO[Unit] =
    tasks.update(_ - jobId) *> unreadEscalations.update(_ - jobId)

  /**
   * 全表快照（P1 BgLifecycleReconciler 扫描面；只读零副作用——每拍一次全表读，
   * 与 activeTasksJson 同 Ref 数据源）。
   */
  def allActiveTasks: IO[List[ActiveTask]] =
    tasks.get.map(_.values.toList)

  /**
   * 活动戳写缝（P1 reconciler · 面板快照帧 lastActivityMs 数据源）：仅当更新鲜
   * 才抬升（乱序/回退宽容）；行不存在 ⇒ false。远端任务由心跳面喂本缝
   * （RemoteExecutor.startRemoteHeartbeat），reconciler 把行戳增长视作进展信号。
   */
  def recordActivity(jobId: String, atMs: Long): IO[Boolean] =
    tasks.modify { m =>
      m.get(jobId) match
        case None => (m, false)
        case Some(t) =>
          if atMs > t.lastActivityMs then (m + (jobId -> t.copy(lastActivityMs = atMs)), true)
          else (m, false)
    }

  /**
   * 单任务查行（输出查看补全批：remote-output 路由按 jobId 取 deviceName/
   * outputPath；完成即注销 ⇒ 查无 = 已终态，路由 404 让前端转 /output 收口面）。
   */
  def find(jobId: String): IO[Option[ActiveTask]] =
    tasks.get.map(_.get(jobId))

  /**
   * 注销并返回被注销行（通知详情批 2026-10-24）：完成回调取 `startedAtMs` 算耗时
   * （通知负载的耗时段）——注销与读数一次原子完成，防 unregister+get 两拍竞态。
   * 行不存在（极早终态/重复回调）⇒ None，调用方耗时段落省略。
   */
  def unregisterReturning(jobId: String): IO[Option[ActiveTask]] =
    unreadEscalations.update(_ - jobId) *>
      tasks.modify { m =>
        val removed = m.get(jobId)
        (m - jobId, removed)
      }

  /**
   * #391 机制 E：移除某 session 的全部任务并返回它们——restart/Stop 联动时
   * 调用方用返回值发 WS backgroundTaskUpdate(status="cancelled") 通知前端
   * 指示器消失（agent 已死，正常的 completed 通知不会到来）。
   */
  def unregisterSession(sessionId: Option[String]): IO[List[ActiveTask]] =
    sessionId match
      case None => IO.pure(Nil)
      case Some(sid) =>
        tasks.modify { m =>
          val (removed, kept) = m.partition(_._2.sessionId == sid)
          (kept, removed.values.toList)
        }.flatTap { removed =>
          val ids = removed.map(_.jobId).toSet
          unreadEscalations.update(_ -- ids)
        }

  // ── 健康轨数据面（P0 面板投影批 · 设计稿 20261030_background-task-lifecycle §3.4）──
  //
  // 单一事实源 = 行内 suspicionLevel/escalatedAtMs/lastActivityMs 三字段；健康
  // 枚举（healthy/suspect/escalated）只是 BgHealth.of 的派生投影词表，禁第二
  // 状态源。P0 只做数据面 + UI 面：判据（何时升/降级）归 P1
  // BgLifecycleReconciler（启发式、零 wall-clock 判死），本对象只提供写缝
  // （updateHealth）、计数读面（healthCounts）与徽标对账广播（syncHealthBadge，
  // heldReceipts 同款：快照比对、计数变化才发帧）。

  /**
   * 健康轨写缝（P1 reconciler 的落点）：枚举值经 BgHealth.normalize 容错
   * （非法值一律回落 healthy——面板永不因脏数据崩）后翻译进行内字段。
   * 语义：suspect = 置嫌疑级；escalated = 置嫌疑级 + 记升级时刻（幂等：已
   * 升级的重复写入保留首次 escalatedAtMs）；healthy = 重排不判死——停滞计数
   * 清零、升级戳清零（任何进展信号即时降回，设计稿 §3.1）。
   * 行不存在 ⇒ false（极早终态/重复回调宽容）。
   */
  def updateHealth(jobId: String, health: String, nowMs: Long = System.currentTimeMillis()): IO[Boolean] =
    tasks.modify { m =>
      m.get(jobId) match
        case None => (m, false)
        case Some(t) =>
          val next = BgHealth.normalize(health) match
            case BgHealth.Escalated =>
              t.copy(
                suspicionLevel = math.max(t.suspicionLevel, 1),
                escalatedAtMs = if t.escalatedAtMs > 0 then t.escalatedAtMs else nowMs
              )
            case BgHealth.Suspect =>
              t.copy(suspicionLevel = math.max(t.suspicionLevel, 1))
            case _ =>
              t.copy(suspicionLevel = 0, escalatedAtMs = 0L)
          (m + (jobId -> next), true)
    }

  /**
   * 全表健康计数（bgHealth 徽标帧载荷数据源）：suspect/escalated 行内派生 +
   * unread 升级未送达记账归并。
   */
  def healthCounts: IO[BgHealthCounts] =
    for
      m <- tasks.get
      u <- unreadEscalations.get
    yield BgHealth.healthCounts(m.values, u)

  // ── unread 激活（P1 控制器批 · 设计稿 §3.2 BadgeSync 的 unread 格）────────
  //
  // P0 桩 unread=0 → P1 语义 = 「已升级但编排者通知未送达」（升级通知入持久收件箱
  // 补偿腿 / 目标不可达 / 投递失败）的任务数：Escalate 执行面按 BgNotifyDelivery
  // 投递路由记账（Delivered ⇒ read；Persist/Undeliverable/异常 ⇒ unread）。
  // 单一事实源纪律：unread 只是投递记账的投影，健康枚举仍唯一派生自行内字段；
  // 注销（含会话收殓）即清账。

  private val unreadEscalations: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)

  /** 升级通知未送达记账（Escalate 执行面 · Persist/Undeliverable/失败腿）。 */
  private[core] def markEscalationsUnread(jobIds: Set[String]): IO[Unit] =
    if jobIds.isEmpty then IO.unit else unreadEscalations.update(_ ++ jobIds)

  /** 升级通知已送达记账（直投 root 转录成功腿 / 进展降级消解）。 */
  private[core] def markEscalationsRead(jobIds: Set[String]): IO[Unit] =
    if jobIds.isEmpty then IO.unit else unreadEscalations.update(_ -- jobIds)

  /** unread 记账快照（spec 断言面 / 取证）。 */
  private[core] def unreadEscalationSnapshot: IO[Set[String]] = unreadEscalations.get

  /**
   * 最近一次已广播的 bgHealth 计数（徽标对账基准：计数变化才广播，heldReceipts
   * 同款）。初始 = 空表计数（全零）⇒ P0 全 healthy 期零广播（零行为变化）。
   */
  private val lastHealthBadge: Ref[IO, BgHealthCounts] = Ref.unsafe(BgHealthCounts())

  /**
   * bgHealth 徽标对账（heldReceipts BadgeSync 同款机制）：现值与上次广播基准
   * 不一致 ⇒ 广播 bgHealth 帧并更新基准；一致 ⇒ 静默。返回是否发出了帧。
   * P1 reconciler 每拍调用；P0 无生产调用方（写缝未激活，恒全零静默）——
   * 帧形状与对账语义本批钉死，spec 直测往返。
   */
  def syncHealthBadge(wsHub: nebflow.core.EventSink): IO[Boolean] =
    healthCounts.flatMap { now =>
      lastHealthBadge
        .modify { prev =>
          if now == prev then (prev, false) else (now, true)
        }
        .flatMap { changed =>
          IO.whenA(changed)(
            wsHub
              .broadcast(BgHealth.frame(now))
              .handleErrorWith(e => logger.warn(s"BgTaskRegistry: bgHealth badge broadcast failed: ${e.getMessage}"))
          ).as(changed)
        }
    }

  // ── 终态延迟销毁窗口的「禁 spawn」表──────
  //
  // 语义：节点终态后**一律存活 30 分钟再销毁**——窗口内进程/任务
  // 照跑、输出照写、允许读取取证，但**禁止新 spawn**（新后台任务 / 新会话）。本表是
  // 那条「禁 spawn」判据的唯一载体：`sessionId -> destroyAt(epoch ms)`，写入点 =
  // NodeEngine 终态登记（`scheduleDestroy`）/ 扫描腿表项自愈；读取点 =
  // `ShellSession.executeBackground`（新后台任务）与 `ShellSession.forSession` 的
  // **会话创建分支**（新会话）；清除点 = 节点重新翻转 Running（reactivate / 重跑，
  // `NodeEngine` 翻转处的 `reopenSession`）。
  //
  // 为什么放这里（而不是 NodeEngine 私有 Ref）：spawn 的物理落点在 `ShellSession`
  // （nebflow.core.tools），与 NodeEngine（core.project）之间只允许 core.project →
  // core.tools 的依赖方向；本对象已是两层的公共会话级账本（reclaimSession/waitingFor
  // 同键 sessionId），沿用它零新依赖、零新单例。
  //
  // 进程内存态（重启即空）：重启后由 `NodeEngine.sweepDestroyWindows` 按节点持久字段
  // `destroyAt` 逐节点重建（每 30s 一拍，重启后一拍内恢复）；重启后到点者直接销毁。
  private val finalizedSessions: Ref[IO, Map[String, Long]] = Ref.unsafe(Map.empty)

  /**
   * 登记「该会话已终态、处于延迟销毁窗口（或已销毁）」——窗口内禁新 spawn。
   * 幂等：同 sessionId 重复登记只覆盖 destroyAt。空 id no-op。
   */
  def markSessionFinalized(sessionId: String, destroyAt: Long): IO[Unit] =
    IO.whenA(sessionId.trim.nonEmpty)(finalizedSessions.update(_ + (sessionId -> destroyAt)))

  /** 撤销登记（节点重新翻转 Running = 新生命周期，窗口与禁 spawn 一并解除）。 */
  def reopenSession(sessionId: String): IO[Unit] =
    finalizedSessions.update(_ - sessionId)

  /** 该会话的销毁窗口登记（None = 未终态/未登记）。 */
  def finalizedAt(sessionId: String): IO[Option[Long]] =
    finalizedSessions.get.map(_.get(sessionId))

  /** 新 spawn 是否被拒（终态窗口内 = 拒）。 */
  def sessionFinalized(sessionId: String): IO[Boolean] =
    finalizedSessions.get.map(_.contains(sessionId))

  /** 表项快照（取证 / 测试断言用；键 = sessionId，值 = destroyAt）。 */
  def finalizedSessionsSnapshot: IO[Map[String, Long]] = finalizedSessions.get

  /**
   * 拒绝文案单一构造点（工具面错误消息 / 日志 / 事件同源，措辞禁含 "cancelled"
   * ——桥侧以 `contains("cancelled")` 分流 cancelNode，避免歧义）。
   */
  def spawnDeniedMessage(sessionId: String, destroyAt: Option[Long]): String =
    val remaining = destroyAt.map(at => math.max(0L, at - System.currentTimeMillis()) / 1000L)
    s"session '$sessionId' is finalized (terminal state) — read-only evidence window" +
      remaining.fold("")(r => s", destroy in ~${r}s") +
      ": new background tasks and new sessions are rejected; the session's processes stay " +
      "alive for forensics until the window expires (then reclaimed: processes killed + tasks finalized)"

  /**
   * 禁 spawn 守卫：会话已终态 ⇒ 抛错（调用方 `IO.raiseError` 语义，工具面把它渲染成
   * 给 LLM 的可读失败消息）。未登记 = `IO.unit`（零行为变化）。
   */
  def denySpawnIfFinalized(sessionId: String): IO[Unit] =
    finalizedSessions.get.flatMap { m =>
      m.get(sessionId) match
        case None => IO.unit
        case Some(at) => IO.raiseError(new RuntimeException(spawnDeniedMessage(sessionId, Some(at))))
    }

  /**
   * 会话级收殓（孤儿后台任务收割 D1 主钩子）：杀该会话全部 shell 进程树
   * （前台 + 后台 runProcess 注册的 OS 进程）+ 注销 BgTaskRegistry + WS
   * backgroundTaskUpdate(status="cancelled") 帧。
   *
   * 由 NodeEngine 的 failed/cancelled/zombie 终态出口与本引擎 Agent Stop 路径
   * 共用（抽公共函数——对齐 AgentActor.killSessionShellProcesses 的既有语义，
   * 避免两处重复；AgentActor 改调本函数）。
   *
   * 幂等：会话无进程/无任务时 no-op；已杀/已注销时二次调用无副作用。
   * 防误杀边界：只做技术收殓，不裁决进程/任务归属语义（persistent/detached 的
   * 杀否由调用方决策——本函数对在册任务一律注销）。
   *
   * sessionId 为 None 时 no-op（对齐 unregisterSession/killSessionProcesses）。
   */
  def reclaimSession(
    sessionId: Option[String],
    wsSend: Json => IO[Unit],
    rootSessionId: String
  ): IO[Unit] =
    ShellSession.killSessionProcesses(sessionId) *>
      unregisterSession(sessionId).flatMap { removed =>
        removed.traverse_ { t =>
          // 收割的挂起任务同为终态——输出留存区翻转
          // 为 cancelled，详情卡可回看收割时刻为止的输出。幂等 no-op 当已完成。
          nebflow.core.tools.BgTaskOutputStore
            .finalizeTask(t.jobId, "cancelled", None, Some("Session reclaimed"))
            .handleError(_ => IO.unit) *>
            wsSend(
              Json.obj(
                "type" -> "backgroundTaskUpdate".asJson,
                "sessionId" -> sessionId.asJson,
                // 权威分键（计数/列表分叉修复）：与 BashTool/RemoteExecutor
                // 发射点一致携带 rootSessionId，收割帧按根会话分桶直达归属视图
                // （前端已删 bgTaskRootFor 启发式逆向分键）。
                "rootSessionId" -> rootSessionId.asJson,
                "taskId" -> t.jobId.asJson,
                "description" -> t.description.asJson,
                "status" -> "cancelled".asJson,
                // 归属短名随收割帧携带（与注册帧同键；缺省空串，前端零渲染）。
                "ownerName" -> t.ownerName.asJson
              )
            ).handleErrorWith(_ => IO.unit)
        }
      }

  /**
   * 节点完成闸等待集查询：某会话名下的活动等待型任务 = 自有任务（sessionId
   * 相等）+ 以该会话为根的子代理任务（rootSessionId 相等，命名空间不相交：
   * node-<uuid> 只作为节点自身 sessionId 与其子代理的 rootSessionId 出现）。
   * persistent=true（服务型）过滤不等待。
   */
  def waitingFor(sessionId: String): IO[List[ActiveTask]] =
    tasks.get.map { m =>
      m.values
        .filter(t => !t.persistent && (t.sessionId == sessionId || t.rootSessionId == sessionId))
        .toList
    }

  /**
   * 热重启 quiesce：全部活动等待型（非 persistent）后台
   * 任务——等待型任务无持久面（进程内 Ref），重启即亡，故必须纳入空闲判定。
   */
  def waitingTasks: IO[List[ActiveTask]] =
    tasks.get.map(_.values.filter(!_.persistent).toList)

  /**
   * 节点完成闸终局记账：等待型任务被超时/停滞看护杀掉时登记（BashTool 完成回调
   * Left(TimeoutException) 分支调用；persistent 与显式取消不入账——前者不在等待集，
   * 后者是 agent 自主决策）。
   */
  def markFailed(
    jobId: String,
    sessionId: String,
    rootSessionId: String,
    description: String,
    cause: String
  ): IO[Unit] =
    failures.update(
      _ + (jobId -> FailedBgTask(jobId, sessionId, rootSessionId, description, cause, System.currentTimeMillis()))
    )

  /**
   * 节点终态化前检查（NodeEngine 桥）：取走并清空该会话名下的失败记账
   * （匹配规则同 waitingFor）。consume-on-read：节点恰好终态化一次。
   */
  def drainFailures(sessionId: String): IO[List[FailedBgTask]] =
    failures.modify { m =>
      val (hit, kept) = m.partition { case (_, f) => f.sessionId == sessionId || f.rootSessionId == sessionId }
      (kept, hit.values.toList)
    }

  /**
   * Returns active tasks grouped by root session id (rootSessionId, falling
   * back to sessionId when absent), as JSON for the frontend.
   */
  def activeTasksJson: IO[io.circe.Json] =
    for
      m <- tasks.get
      lineCounts <- BgTaskOutputStore.lineCounts
    yield m.values
      .groupBy(t => if t.rootSessionId.nonEmpty then t.rootSessionId else t.sessionId)
      .map { case (sid, taskSet) =>
        sid -> taskSet.map { t =>
          io.circe.Json.obj(
            "taskId" -> t.jobId.asJson,
            "description" -> t.description.asJson,
            // P0 面板投影批（设计稿 §3.4）：status 改读行内实际态、不再硬编码
            // "running"（P0 在册即未终态，行字段恒 "running"——形状先行，终态
            // 记账随 P2 批；向后兼容：旧调用方默认值填 "running"，输出逐字节不变）。
            "status" -> t.status.asJson,
            "startedAt" -> t.startedAtMs.asJson,
            // 后台任务面板重设计：快照补 kind（local/remote）与
            // 来源字段（origin 类别 + originLabel 显示名）——前端来源 chip
            // 与类别样式在刷新恢复路径同样可渲染。
            "kind" -> t.kind.asJson,
            "origin" -> t.origin.asJson,
            "originLabel" -> t.originLabel.asJson,
            // 任务模式标注批（2026-10-30）：delegate 会话生效执行形态随快照下发
            // （kernel/dispatcher；空 = 无徽标——旧行/非 delegate 恒空，零渲染）。
            "ownerMode" -> t.ownerMode.asJson,
            // 后台任务归属批（2026-10-31）：行归属短名随快照下发（delegate 批次
            // 短名；空 = 非 delegate/无名 ⇒ 前端回退既有来源显示，零渲染）。
            "ownerName" -> t.ownerName.asJson,
            // #753 行带已产出行数（刷新恢复路径）：实时行由心跳帧携带
            // outputLines，快照行此前无来源——面板行在页面刷新后行数 chip
            // 消失。本字段 = BgTaskOutputStore 的当前 totalLines（无缓冲
            // 任务 = 0，前端 falsy 不渲染）。remote 任务无缓冲，恒 0。
            "outputLines" -> lineCounts.getOrElse(t.jobId, 0L).asJson,
            // P0 面板投影批（设计稿 §3.4 · 单一事实源）：健康枚举由行内
            // suspicionLevel/escalatedAtMs 派生（BgHealth.of）——快照帧、
            // bgWaiting 实时载荷、bgHealth 徽标帧三处同源，禁第二状态源。
            // 非法行值经派生天然容错（escalatedAtMs<=0 且 suspicionLevel<=0
            // ⇒ healthy）；行字段默认值全零 ⇒ 旧行恒 healthy（纯增量零变化）。
            "health" -> BgHealth.of(t).asJson,
            "lastActivityMs" -> t.lastActivityMs.asJson
          )
        }.toList
      }
      .asJson

  /**
   * #752 空闲 delegate 在等什么（快照面数据源）：按**自有 sessionId** 分组的
   * 活动等待型任务（每会话一条 = 它自己名下在跑的后台任务）。一次全表扫描
   * 返回分组 Map——handleGetActiveAgents 快照组装单次调用（禁逐行查表）。
   * 判据 = t.sessionId（自有任务；不含子代理 root 挂靠——子代理任务由子代理
   * 自己的行承载，避免父行行数被子任务灌水）。
   */
  def waitingBySession: IO[Map[String, List[ActiveTask]]] =
    tasks.get.map { m =>
      m.values
        .filter(!_.persistent)
        .toList
        .groupBy(_.sessionId)
    }

  /**
   * 某会话名下任务行的**原始读数**（含 persistent——与 waitingBySession 的过滤面
   * 相反，无过滤）：取证/测试断言读面（多设备后台任务可见批：persistent 旗标
   * 与 origin 字段落行的 round-trip 断言源）。只读，零副作用。
   */
  def tasksOf(sessionId: String): IO[List[ActiveTask]] =
    tasks.get.map(_.values.filter(_.sessionId == sessionId).toList)
end BgTaskRegistry

/**
 * 后台任务健康轨词表与投影（设计稿 20261030_background-task-lifecycle §3.1/§3.4）：
 * healthy → suspect → escalated 正交健康轨的**投影面**。P0 只做数据面 + UI 面——
 * 判据（何时升/降级、基线、退避）归 P1 `BgLifecycleReconciler`（启发式、零
 * wall-clock 判死）；本对象只有四件纯面：词表归一（含非法值容错）、行→枚举派生
 * （单一事实源）、计数归并、徽标帧构造。重排不判死：healthy 写入即停滞计数清零。
 */
object BgHealth:

  val Healthy: String = "healthy"
  val Suspect: String = "suspect"
  val Escalated: String = "escalated"

  /** 徽标帧类型（前端 bgHealth 处理器注册名；heldReceipts 同款机制）。 */
  val FrameType: String = "bgHealth"

  /**
   * 值域容错（spec「枚举非法值容错」锚）：非 suspect/escalated 一律回落
   * healthy——面板/帧永不因脏枚举崩，坏值按最保守（无嫌疑）呈现。
   */
  def normalize(raw: String): String =
    raw match
      case Suspect => Suspect
      case Escalated => Escalated
      case _ => Healthy

  /** 行内字段 → 健康枚举（escalated 优先于 suspect；唯一派生点，禁复制）。 */
  def of(t: BgTaskRegistry.ActiveTask): String =
    if t.escalatedAtMs > 0 then Escalated
    else if t.suspicionLevel > 0 then Suspect
    else Healthy

  /**
   * 行表 → 徽标计数（unread 桩 = 0，P1 前恒零；单参重载保持 P0 调用方/spec
   * 逐字兼容）。P1 生产面走双参版（unread = 升级未送达记账投影）。
   */
  def healthCounts(tasks: Iterable[BgTaskRegistry.ActiveTask]): BgTaskRegistry.BgHealthCounts =
    healthCounts(tasks, Set.empty)

  /**
   * 行表 + unread 记账 → 徽标计数（P1 激活：unread = 升级未送达集合 ∩ 在册
   * escalated 行；escalated/suspect 仍行内派生——唯一归并点，禁第二状态源）。
   */
  def healthCounts(
    tasks: Iterable[BgTaskRegistry.ActiveTask],
    unreadJobIds: Set[String]
  ): BgTaskRegistry.BgHealthCounts =
    tasks.foldLeft(BgTaskRegistry.BgHealthCounts()) { (acc, t) =>
      of(t) match
        case Suspect => acc.copy(suspect = acc.suspect + 1)
        case Escalated =>
          acc.copy(
            escalated = acc.escalated + 1,
            unread = if unreadJobIds.contains(t.jobId) then acc.unread + 1 else acc.unread
          )
        case _ => acc
    }

  /** bgHealth 徽标帧构造：{type, unread, suspect, escalated}（无 sessionId——
   *  全表计数面，前端 GLOBAL 路由）。 */
  def frame(counts: BgTaskRegistry.BgHealthCounts): Json =
    Json.obj(
      "type" -> FrameType.asJson,
      "unread" -> counts.unread.asJson,
      "suspect" -> counts.suspect.asJson,
      "escalated" -> counts.escalated.asJson
    )

end BgHealth

/**
 * #753 会话级后台任务创建速率闸——纯判定面（零依赖，spec 直测）。
 *
 * 两档语义：
 *   - 熔断激活（breakerUntil > now）→ 拒绝，不动窗口计数（冷却到期自动解除）；
 *   - 窗口内已有创建数 ≥ breakerAfter → 触发熔断（breakerUntil = now+cooldown）
 *     并拒绝本拍；
 *   - 窗口内已有创建数 +1 ≥ warnAfter（未达熔断）→ 放行但告警；
 *   - 其余 → 放行。
 *
 * `recentCount` 语义 = 本拍（含）计入窗口后的创建数——告警/日志文案直接消费。
 */
object BgRateGate:

  final case class Decision(
    allowed: Boolean,
    breakerEngaged: Boolean,
    warn: Boolean,
    recentCount: Int,
    breakerUntilMs: Option[Long],
    message: Option[String]
  )

  end Decision

  private[tools] def rejectMessageActive(remainingSec: Long): String =
    s"[Background task rate limit] Circuit breaker active for this session — " +
      s"new background tasks are rejected for another ${math.max(1, remainingSec)}s. " +
      s"Wait for the existing background tasks to complete (you are notified automatically), " +
      s"or finish your turn instead of waiting."

  private[tools] def rejectMessageEngaged(count: Int, windowMin: Long, cooldownMin: Long): String =
    s"[Background task rate limit] This session has created $count background tasks within the last " +
      s"$windowMin min — circuit breaker engaged for $cooldownMin min. Do NOT spawn more waiters/pollers: " +
      s"wait for the running tasks to notify on completion, consolidate repeated polling into ONE background task, " +
      s"or finish your turn instead of waiting."

  def decide(
    priorCreations: Vector[Long],
    breakerUntilMs: Option[Long],
    nowMs: Long,
    windowMs: Long,
    warnAfter: Int,
    breakerAfter: Int,
    cooldownMs: Long
  ): Decision =
    breakerUntilMs.filter(_ > nowMs) match
      case Some(until) =>
        Decision(
          allowed = false,
          breakerEngaged = false,
          warn = false,
          recentCount = priorCreations.size,
          breakerUntilMs = Some(until),
          message = Some(rejectMessageActive((until - nowMs) / 1000L))
        )
      case None =>
        val recent = priorCreations.filter(_ > nowMs - windowMs)
        if recent.size >= breakerAfter then
          val until = nowMs + cooldownMs
          Decision(
            allowed = false,
            breakerEngaged = true,
            warn = false,
            recentCount = recent.size,
            breakerUntilMs = Some(until),
            message =
              Some(rejectMessageEngaged(recent.size, math.max(1, windowMs / 60000L), math.max(1, cooldownMs / 60000L)))
          )
        else
          val count = recent.size + 1
          Decision(
            allowed = true,
            breakerEngaged = false,
            warn = count >= warnAfter,
            recentCount = count,
            breakerUntilMs = None,
            message = None
          )
        end if
end BgRateGate
