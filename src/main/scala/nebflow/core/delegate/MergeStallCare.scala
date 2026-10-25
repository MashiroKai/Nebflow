package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AgentRuntimePort
import nebflow.core.tools.BgNotifyDelivery
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * MergeStallCare —— 合并队列**滞留告警 + 退避重试**看护（merge-queue 滞留两病灶
 * 修复批 2026-10-25，作者令腿②③）。
 *
 * 病灶（作者实证）：被拒 delegate 只获一次通知，此后队列静默滞留——`pendingRetry`
 * 条目无重试者无告警者，MergeQueue FIFO 不推进，作者肉眼才发现（reports/ 工件
 * 两次挡死队列两天多）。
 *
 * 三腿（作者令逐字）：
 *   - **逐次拒绝通知（谁在挡、挡在哪）**：停靠条目（`pendingRetry` ∧ 有失败计数）
 *     的每一次新拒绝 ⇒ 双通知——delegate 本人（持久注入，复活轮重放）：**具体脏
 *     路径清单**（基工作区现读）+ **谁在挡**（队首持窗者/领先条目）+ 引擎已代为
 *     处置的工件面 + 后续动作预告；父会话一行知会。correlationId 携失败计数——
 *     每次拒绝是独立事件，持久队列不吞后续通知。
 *   - **N 次退避重试**：队首停靠条目在「**无竞争合并**（窗空闲 ∧ 队列无领先活条
 *     目）∧ **工件已清理**（引擎先跑 [[MergeArtifactCare.hygiene]]——tracked 覆写
 *     还原 + 未跟踪工件收编，白名单外仍脏则本拍不烧 LLM 只记账）」条件下重发合并
 *     轮：通道腿（external/recovered 闭包）走 [[MergeQueue.retryInject]]；内部腿
 *     （会话已终态）走 [[RevivalPort]]（agent 层续起会话 + rekey——boot sweep
 *     internalRevival 同机制，GatewayMain boot 装配）。退避 = base × 2^k（cap 封顶）。
 *   - **升级告警根会话（不再静默）**：重试预算（`maxRetries`）耗尽仍滞留 ⇒ 升级
 *     事件投最终责任方（root 活投 / 持久收件箱补投，[[BgNotifyDelivery]] 同族），
 *     粘住只告警一次；条目留队（分支/工件绝不丢——收尾归人裁决）。
 *   - **障碍清除事件驱动唤醒（2026-10-25 补充令③——今日精确缺口「障碍清除后无人
 *     再唤醒 delegate」）**：树由脏转洁的真过渡（扫描拍上采样）⇒ 对队首被拒
 *     delegate **重发合并提醒**——退避闸旁路 + 升级粘住解除，恢复自动推进资格。
 *     这是 10-23 批撤销的「转洁代跑重试」的合规范替身：唤醒 = 提醒重发，合并仍由
 *     delegate 自己执行，平台零代跑。
 *
 * 消费点 = `TaskStuckWatcher.scan` 尾部（[[MergeWindowCare]] 邻位，搭既有扫描拍
 * 零新增定时器）；幂等——通知/升级/重试各由条目台账字段（stallNotified /
 * stallEscalated / lastAttemptAt）把门，重复扫描零重复动作。重启后进程内台账
 * 消亡：boot sweep 重挂的条目计数归零（correlationId 幂等挡住重复告警轰炸）。
 */
object MergeStallCare:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-stall-care")

  /** 注入事件行 source（审计 grep 锚）。 */
  val Source: String = "merge-stall-care"

  // ── 配置（nebflow.json `mergeStall.*`，热读；DelegateAutoMerge.Config 同形态） ──

  final case class Config(
      /** 总开关：false = 看护整体空转（一键回 10-25 前静默滞留语义）。缺省 true。 */
      enabled: Boolean,
      /** 重试预算：第 N 次失败重试后升级告警根会话。缺省 3。 */
      maxRetries: Int,
      /** 退避基长 ms：第 k 次重试等 base × 2^k（cap 封顶）。缺省 60s（1m→2m→4m）。 */
      backoffBaseMs: Long,
      /** 退避封顶 ms。缺省 10min。 */
      backoffCapMs: Long
  )

  object Config:
    val Default: Config = Config(enabled = true, maxRetries = 3, backoffBaseMs = 60_000L, backoffCapMs = 600_000L)

    /** 热读（每次现读盘，零缓存）；absent / 非法 / garbage ⇒ 缺省。 */
    def read: Config =
      try
        val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
        if !os.exists(configPath) then Default
        else
          io.circe.parser.parse(os.read(configPath)).toOption match
            case None => Default
            case Some(json) =>
              val cur = json.hcursor.downField("mergeStall")
              val enabled = cur.downField("enabled").as[Option[Boolean]].toOption.flatten.getOrElse(true)
              val maxRetries = cur.downField("maxRetries").as[Option[Int]].toOption.flatten.getOrElse(3)
              val base = cur.downField("backoffBaseMs").as[Option[Long]].toOption.flatten.getOrElse(60_000L)
              val cap = cur.downField("backoffCapMs").as[Option[Long]].toOption.flatten.getOrElse(600_000L)
              Config(
                enabled = enabled,
                maxRetries = math.max(1, maxRetries),
                backoffBaseMs = math.max(0L, base),
                backoffCapMs = math.max(base, cap)
              )
      catch case _: Throwable => Default
  end Config

  /** 第 k 次重试前的退避时长（base × 2^k，cap 封顶；spec 断言面纯函数）。 */
  def backoffDelayMs(cfg: Config, retryCount: Int): Long =
    val shift = math.min(math.max(retryCount, 0), 20)
    math.min(cfg.backoffCapMs, cfg.backoffBaseMs * (1L << shift))

  // ── 内部腿复活端口（M2 注册器形态：core 只见面，agent 侧装配实现） ────

  object RevivalPort:

    /**
     * 内部腿复活（合并轮被拒后会话已终态——重发须续起会话）。实现面 = boot sweep
     * internalRevival 同机制：续起基工作区座位的 continuation 会话执行合并 +
     * `MergeQueue.rekey` 换键。`Right(())` = 复活已送达（换键已落）；`Left` = 复活
     * 失败（条目留队，看护记账退避进位）。
     */
    trait Face:
      def revive(entry: MergeQueue.Entry): IO[Either[String, Unit]]
    end Face

    private val face = new java.util.concurrent.atomic.AtomicReference[Option[Face]](None)

    def install(f: Face): Unit = face.set(Some(f))

    def clear(): Unit = face.set(None)

    def get: Option[Face] = face.get
  end RevivalPort

  // ── 看护动作词表（审计 / spec 断言面） ────────────────────────────────

  final case class Finding(delegateId: String, action: String, detail: String)

  val ActionRefusalNotice: String = "stall-refusal-notice"
  val ActionRetryInjected: String = "stall-retry-injected"
  val ActionBlockedByDirt: String = "stall-blocked-by-dirt"
  val ActionEscalated: String = "stall-escalated"
  val ActionObstacleCleared: String = "stall-obstacle-cleared"

  /**
   * 障碍清除事件驱动的脏基线（进程内，projectKey → 上拍 dirty 读数；30s 扫描拍
   * 上采样——重启归零，首拍只记基线不触发）。10-23 批撤销的「转洁代跑重试」的
   * **合规范替身**（作者 2026-10-25 补充令③「障碍清除后无人再唤醒 delegate」）：
   * 树由脏转洁 ⇒ 只对队首被拒 delegate **重发合并提醒**（退避闸旁路 + 升级粘住
   * 解除），合并本身仍由 delegate 会话执行——平台零代跑。
   */
  private val dirtyBaseline = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

  // ── 一拍看护 ──────────────────────────────────────────────────────────

  /**
   * 一拍看护。通知面覆盖**全部**停靠条目（信息面无竞争）；重试/升级面只服务
   * 每 project 的**队首**停靠条目（单飞行纪律）。`cfgOverride` = 测试注入口
   * （缺省热读）。
   */
  def care(resources: AgentRuntimePort, cfgOverride: Option[Config] = None): IO[List[Finding]] =
    val cfg = cfgOverride.getOrElse(Config.read)
    val sweep: IO[List[Finding]] =
      MergeQueue.stallSnapshotIO.flatMap { all =>
        val parked = all.filter(_.pendingRetry)
        if parked.isEmpty then IO.pure(Nil)
        else
          // 通知面：全部停靠条目（逐次拒绝通知；幂等由 stallNotified 把门）。
          val notices: IO[List[Option[Finding]]] = parked.traverse(v => notifyIfNeeded(resources, v))
          // 重试/升级面：每 project 队首停靠条目（领先活条目在场 = pump 管，跳过）。
          val heads: List[MergeQueue.StallView] = parked
            .groupBy(_.projectKey)
            .toList
            .flatMap { (projectKey, views) =>
              val head = views.minBy(v => (v.readyAt, v.delegateId))
              val liveAhead = all.exists(v =>
                v.projectKey == projectKey && !v.pendingRetry &&
                  (v.readyAt, v.delegateId) < (head.readyAt, head.delegateId)
              )
              if liveAhead then Nil else List(head)
            }
          val attempts: IO[List[List[Finding]]] = heads.traverse { v =>
            val cleared = obstacleClearPulse(v)
            attemptOrEscalate(resources, cfg, v, cleared).map(fs =>
              if cleared then
                Finding(v.delegateId, ActionObstacleCleared, "tree dirty→clean — backoff bypassed, merge reminder re-fired") :: fs
              else fs
            )
          }
          notices.flatMap(ns => attempts.map(ats => ns.flatten ++ ats.flatten))
      }
    if cfg.enabled then sweep.handleErrorWith(e => logger.warn(s"[merge-stall-care] sweep failed: ${e.getMessage}").as(Nil))
    else IO.pure(Nil)
  end care

  /** 逐次拒绝通知（stallNotified 把门；查无条目 = 两拍间已出队，no-op）。 */
  private def notifyIfNeeded(resources: AgentRuntimePort, v: MergeQueue.StallView): IO[Option[Finding]] =
    if v.retryCount < 1 || v.stallNotified then IO.pure(None)
    else
      val snapshot = IO.blocking {
        val paths = MergeArtifactCare.dirtyPaths(os.Path(v.baseWs))
        val ahead = MergeQueue.stallSnapshot
          .filter(s => s.projectKey == v.projectKey && !s.pendingRetry && s.position < v.position)
          .map(_.delegateId)
        val holder = v.holder.filter(_ != v.delegateId)
        (paths, ahead, holder)
      }.handleErrorWith(_ => IO.pure((Nil, Nil, None)))
      snapshot.flatMap { case (paths, ahead, holder) =>
        val who =
          if ahead.nonEmpty then s"waiting behind: ${ahead.mkString(", ")}"
          else holder.map(h => s"merge window held by: $h").getOrElse("queue head (window free)")
        val where =
          if paths.isEmpty then "base workspace status unreadable"
          else s"blocking paths: ${paths.mkString(", ")}"
        val delegateEvent = AgentCommand.ExternalEvent(
          source = Source,
          eventType = "failed",
          payload =
            s"""[merge-queue] merge round refused (attempt ${v.retryCount}) — your branch '${v.branch}' is PARKED in the merge queue for '${v.baseBranch}'.
               |Who is blocking: $who
               |Where: $where
               |The engine auto-cleared what it can (tracked overwrites restored, untracked generated artifacts under evidence/ and reports/ absorbed into the merge batch); anything listed above outside those dirs is fail-closed and needs a human/owner decision.
               |Next: the queue re-fires your merge round automatically with backoff (up to the retry budget); after exhaustion it escalates to the root session. You may also retry the merge yourself on any turn.""",
          metadata = JsonObject(
            "delegateId" -> v.delegateId.asJson,
            "description" -> v.description.asJson,
            "branch" -> v.branch.asJson,
            "target" -> v.baseBranch.asJson,
            "dirtyPaths" -> paths.asJson,
            "retryCount" -> v.retryCount.asJson,
            "notifyTarget" -> "delegate".asJson,
            "escalatedBy" -> Source.asJson
          ),
          correlationId = Some(s"merge-queue-refused-${v.delegateId}-${v.retryCount}")
        )
        val parentEvent = AgentCommand.ExternalEvent(
          source = Source,
          eventType = "failed",
          payload =
            s"""[merge-queue] FYI: delegate "${v.description}" (${v.delegateId}) — merge round refused (attempt ${v.retryCount}); branch '${v.branch}' parked pending auto-retry. Blocking: ${paths.take(5).mkString(", ")}${extraCount(paths)}. The queue re-fires with backoff; no action needed unless it exhausts the retry budget.""",
          metadata = JsonObject(
            "delegateId" -> v.delegateId.asJson,
            "description" -> v.description.asJson,
            "retryCount" -> v.retryCount.asJson,
            "notifyTarget" -> "parent".asJson,
            "escalatedBy" -> Source.asJson
          ),
          correlationId = Some(s"merge-queue-refused-parent-${v.delegateId}-${v.retryCount}")
        )
        // 查无条目（两拍间已出队）= 不投也不盖章；查有 = 投递 + 盖章（投递故障由
        // 端口面自吞——章照盖，correlationId 同键幂等挡重，绝不轰炸）。
        IO.blocking(MergeQueue.find(v.delegateId)).flatMap {
          case None => IO.pure(None)
          case Some(_) =>
            MergeRefusalCare.EscalationPort
              .deliver(v.delegateId, v.parentSessionId, delegateEvent, parentEvent)
              .flatMap { _ =>
                IO.blocking(MergeQueue.markStallNotified(v.delegateId)).as(
                  Some(Finding(v.delegateId, ActionRefusalNotice, s"attempt ${v.retryCount}: $who; $where"))
                )
              }
        }
      }
  end notifyIfNeeded

  private def extraCount(paths: List[String]): String =
    if paths.size > 5 then s" +${paths.size - 5} more" else ""

  /**
   * 树脏→洁过渡检测（扫描拍上采样；真过渡 ⇒ 退避闸旁路 + 升级粘住解除——
   * 「障碍清除后有人唤醒」腿，作者 2026-10-25 补充令③）。首拍只记基线；
   * 非 dirty 状态之间的拍间抖动（洁→脏→洁 30s 内完成）不触发—— documented 限界。
   */
  private def obstacleClearPulse(v: MergeQueue.StallView): Boolean =
    try
      val nowDirty = MergeArtifactCare.dirty(os.Path(v.baseWs))
      val prev = dirtyBaseline.put(v.projectKey, java.lang.Boolean.valueOf(nowDirty))
      val fired = java.lang.Boolean.TRUE.equals(prev) && !nowDirty
      if fired then
        logger.info(
          s"[merge-stall-care] obstacle cleared for '${v.delegateId}' (${v.projectKey}) — tree dirty→clean: " +
            "backoff bypassed, the parked delegate is re-woken with a merge reminder (never a proxy merge)"
        )
        MergeQueue.markObstacleCleared(v.delegateId)
      fired
    catch case _: Throwable => false
  end obstacleClearPulse

  /** 重试/升级面：预算耗尽 ⇒ 升级告警（粘住）；退避未到（且无障碍清除事件）⇒ 等下拍；否则重试拍。 */
  private def attemptOrEscalate(
    resources: AgentRuntimePort,
    cfg: Config,
    v: MergeQueue.StallView,
    obstacleCleared: Boolean = false
  ): IO[List[Finding]] =
    if v.stallEscalated && !obstacleCleared then IO.pure(Nil) // 升级粘住（障碍真清除 = 解除，恢复推进资格）
    else if v.retryCount >= cfg.maxRetries && !obstacleCleared then
      escalate(resources, v).as(
        List(Finding(v.delegateId, ActionEscalated, s"retry budget (${cfg.maxRetries}) exhausted after ${v.retryCount} failed round(s)"))
      )
    else
      val now = System.currentTimeMillis()
      if !obstacleCleared && now - v.lastAttemptAt < backoffDelayMs(cfg, v.retryCount) then IO.pure(Nil)
      else attempt(resources, v)
  end attemptOrEscalate

  /**
   * 一次重试拍：先引擎清障（工件卫生——「工件已清理」条件），清不净 = 本拍不烧
   * LLM 只记账退避进位；清净 = 重发合并轮（通道腿 retryInject / 内部腿 RevivalPort）。
   */
  private def attempt(resources: AgentRuntimePort, v: MergeQueue.StallView): IO[List[Finding]] =
    IO.blocking(MergeArtifactCare.hygiene(os.Path(v.baseWs)))
      .handleErrorWith(e => IO.pure(MergeArtifactCare.Report(Nil, Nil, None, Nil, Some(e.getMessage))))
      .flatMap { report =>
        if report.remainingDirty.nonEmpty then
          val reason =
            s"base workspace still dirty after engine hygiene: ${report.remainingDirty.take(6).mkString(", ")}"
          IO.blocking(MergeQueue.bumpStallAttempt(v.delegateId, reason)).as(
            List(Finding(v.delegateId, ActionBlockedByDirt, reason))
          )
        else inject(resources, v)
      }
      .handleErrorWith(e =>
        logger.warn(s"[merge-stall-care] attempt failed for ${v.delegateId}: ${e.getMessage}").as(Nil)
      )
  end attempt

  /** 清障后的重发腿：通道腿走 MergeQueue.retryInject；内部腿走 RevivalPort。 */
  private def inject(resources: AgentRuntimePort, v: MergeQueue.StallView): IO[List[Finding]] =
    IO.blocking(MergeQueue.find(v.delegateId)).flatMap {
      case None => IO.pure(Nil) // 两拍间已出队（合并落地/收口）
      case Some(entry) =>
        val channelLeg = entry.recovered.isDefined || entry.external.isDefined
        if channelLeg then injectViaChannel(entry)
        else injectViaRevival(v, entry)
    }
  end inject

  private def injectViaChannel(entry: MergeQueue.Entry): IO[List[Finding]] =
    MergeQueue.retryInject(entry).map {
      case Right(()) =>
        List(Finding(entry.delegateId, ActionRetryInjected, "merge round re-fired via channel leg"))
      case Left(err) =>
        List(Finding(entry.delegateId, ActionBlockedByDirt, s"channel retry failed: ${err.take(200)}"))
    }
  end injectViaChannel

  private def injectViaRevival(v: MergeQueue.StallView, entry: MergeQueue.Entry): IO[List[Finding]] =
    RevivalPort.get match
      case None =>
        // 复活通道未装配：响亮 WARN、不计重试预算（绝不静默烧预算）
        logger
          .warn(
            s"[merge-stall-care] RevivalPort not installed — retry for '${v.delegateId}' skipped with this line " +
              "(production boot installs this at GatewayMain)"
          )
          .as(Nil)
      case Some(port) =>
        IO.blocking(MergeQueue.acquireWindow(v.projectKey)).flatMap {
          case false => IO.pure(Nil) // 窗竞态：下拍再来
          case true =>
            port.revive(entry)
              .handleErrorWith(e => IO.pure(Left(Option(e.getMessage).getOrElse(e.toString))))
              .flatMap {
                case Right(()) =>
                  IO.pure(List(Finding(v.delegateId, ActionRetryInjected, "merge round re-fired via revived session")))
                case Left(err) =>
                  IO {
                    MergeQueue.releaseWindow(v.projectKey)
                    MergeQueue.bumpStallAttempt(v.delegateId, s"revival failed: ${err.take(200)}")
                  }.as(List(Finding(v.delegateId, ActionBlockedByDirt, s"revival failed: ${err.take(200)}")))
              }
        }
  end injectViaRevival

  /**
   * 升级告警投最终责任方（delegate registry 行在 ⇒ 行上的 rootSessionId，不猜；
   * 无行 ⇒ 回落父会话。root 活 = 直投；不活 = 持久收件箱补投）。correlationId
   * 幂等（同条目只告警一次——stallEscalated 粘住之外再挡持久队列重放重）。
   */
  private def escalate(resources: AgentRuntimePort, v: MergeQueue.StallView): IO[Unit] =
    IO.blocking(MergeQueue.markStallEscalated(v.delegateId)).flatMap { _ =>
      BgNotifyDelivery
        .resolveRootSessionId(Some(resources.agentRegistry), v.delegateId, v.parentSessionId)
        .flatMap { rootId =>
          if rootId.trim.isEmpty then
            logger.warn(s"[merge-stall-care] no responsible session for '${v.delegateId}' — escalation dropped with this line")
            IO.unit
          else
            val paths = MergeArtifactCare.dirtyPaths(os.Path(v.baseWs)).take(8)
            val pathList = if paths.isEmpty then "<unreadable>" else paths.mkString(", ")
            val event = AgentCommand.ExternalEvent(
              source = Source,
              eventType = "failed",
              payload =
                s"""[merge-queue-stall] delegate "${v.description}" (${v.delegateId}) FAILED its merge round ${v.retryCount} time(s) — branch '${v.branch}' → '${v.baseBranch}' is still unmerged and parked. Blocking paths: $pathList. Auto-retries are exhausted; the entry stays queued (branch and absorbed artifacts preserved). MANUAL ACTION: clear the base workspace ('${v.baseWs}') or merge '${v.branch}' by hand.""",
              metadata = JsonObject(
                "delegateId" -> v.delegateId.asJson,
                "description" -> v.description.asJson,
                "branch" -> v.branch.asJson,
                "target" -> v.baseBranch.asJson,
                "retryCount" -> v.retryCount.asJson,
                "action" -> ActionEscalated.asJson,
                "escalatedBy" -> Source.asJson
              ),
              correlationId = Some(s"merge-queue-stall-${v.delegateId}")
            )
            BgNotifyDelivery
              .deliverToRootOrInbox(Some(resources.actorSystem), Some(resources.agentRegistry), rootId, event)
              .void
              .handleErrorWith(e => logger.warn(s"[merge-stall-care] escalation delivery failed for ${v.delegateId}: ${e.getMessage}"))
        }
    }.handleErrorWith(e => logger.warn(s"[merge-stall-care] escalation failed for ${v.delegateId}: ${e.getMessage}"))
  end escalate

  /**
   * 最后一次拒绝/滞留原因（ChainStore 扫描——API 状态面 `/api/merge-queue` 的
   * 「拒绝原因」列；fail-soft：账本任何故障 = 空映射）。全量单遍扫一次。
   */
  def lastRefusalReasons(delegateIds: Set[String]): Map[String, String] =
    if delegateIds.isEmpty then Map.empty
    else
      try
        ChainStore
          .listSync()
          .flatMap(_.entries)
          .filter(e => e.kind == MergeQueue.EntryKind || e.kind == DelegateAutoMerge.EntryKind)
          .flatMap { e =>
            val c = e.payload.hcursor
            for
              delegateId <- c.get[String]("delegateId").toOption if delegateIds.contains(delegateId)
              action <- c.get[String]("action").toOption
              reason <- c.get[String]("reason").toOption
            yield (delegateId, e.at, action, reason)
          }
          .groupBy(_._1)
          .map { (id, rows) =>
            val last = rows.maxBy(_._2)
            id -> s"${last._3}: ${last._4.take(240)}"
          }
      catch
        case e: Throwable =>
          logger.warn(s"[merge-stall-care] refusal-reason scan failed: ${e.getMessage}")
          Map.empty

end MergeStallCare
