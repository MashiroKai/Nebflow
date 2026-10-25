package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{ActorRef, AgentCommand, AgentEvent}
import nebflow.core.GitWorktrees
import nebflow.shared.{NebflowLogger, PathUtil}
import scala.util.Try

/**
 * MergeQueue —— delegate 合并队列：**交通引导员**（台账 #896 · 2026-10-26 作者裁定
 * 「主要是你的重试和复活的逻辑写的不好……我们的合并排队系统 应该就是一个交通引导员的
 * 角色 通知他们一个一个合并就行了 然后手里有个账就行了 它不需要负责合并」）.
 *
 * 职责（三条，逐字对应裁定）：
 *   - **只持账（账本）**：每条候合并成员一条记录（分支、属主会话、状态机
 *     `waiting → turn-granted → merged / owner-gone / failed`），**落盘持久**
 *     （`<dataRoot>/merge-ledger.json`，逐迁移写穿）——重启不清零、面板可查
 *     （`/api/merge-queue` + `ChainsReadFace` 行投影）。
 *   - **只发通知（放行 = 通知）**：base 净空且轮到该成员时，向**属主 delegate 会话**
 *     注入合并轮指令（带 base 当前 tip）；delegate 自己执行 `git merge` 并回报。
 *     同一时刻**至多一个持有人**（per projectKey 单飞行——`turn-granted` 即窗本体）。
 *   - **永不自己执行 git merge**：引擎侧自动合并执行与自动重试合并轮已整体拆除
 *     （DelegateAutoMerge / MergeStallCare 退役）——「refused×N / parked」语义消失。
 *
 * 放行前置闸（每次授窗前逐项核对，单点在 [[pumpLocked]] → [[grant]]）：
 *   - **账实核对（先于一切）**：`git merge-base --is-ancestor <branch> <baseBranch>`
 *     —— 已并入的成员**直接关账**（state=merged），不再通知、不再重试（今晚
 *     e300e71c「已落定仍 attempt 5 refused/parked」类 should-not-happen 消失）；
 *     属主会话还活着 docks 时收到「已落地」通知立即收尾（delegate 侧纪律 #6）。
 *   - **净空闸**：基工作区先过 [[MergeArtifactCare.hygiene]]（白名单工件还原/收编
 *     ——引擎侧 base 净空维护，非合并执行），余脏非空或基分支非检出分支 ⇒ 本拍
 *     不放行（state 留 waiting + `grant-blocked` 记账，扫描拍再试）。
 *   - **属主活性**：属主会话已死 / 通知通道不可达 ⇒ **owner-gone** 关账 + 面板显式
 *     「需 dispatcher 处置」+ 持久通知升级（绝不静默 park）。
 *
 * 成员回报驱动关账：合并轮终态（BackoffSupervisor / ExternalSessionRunner settle）
 * → [[onTerminal]]——机械验收通过 = merged；拒绝 = failed(原因)；回合外终态
 * （取消/超时/会话死亡）先账实核对再落 owner-gone。
 *
 * 分层纪律：core 不 import agent——属主失联升级走注册器端口
 * [[OwnerGonePort]]（生产装配 = agent.SharedResources 构造行）。
 */
object MergeQueue:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.mergequeue")

  /** 注入源标记（UserInput.source）——UI 气泡归因 + 审计 grep 锚。 */
  val Source: String = "merge-queue"

  /** 审计事件行种类（ChainStore；账本文件是真源，行是审计面）。 */
  val EntryKind: String = "merge-queue"

  // ── 状态机（账本；JSON 直存字符串——跨重启持久形状） ──────────────────

  object State:
    /** 排队中（等窗 / 等净空 / 等通知）。 */
    val Waiting: String = "waiting"
    /** 已放行：属主持合并窗、合并轮在飞（唯一持有人）。 */
    val TurnGranted: String = "turn-granted"
    /** 关账：合并已落地（账实核对或属主回合确认）。 */
    val Merged: String = "merged"
    /** 关账：属主失联（会话终态/通知不可达）——需 dispatcher 处置。 */
    val OwnerGone: String = "owner-gone"
    /** 关账：合并回合被拒（冲突/脏树/机械验收拒绝）——需 dispatcher 处置。 */
    val Failed: String = "failed"

    val Open: Set[String] = Set(Waiting, TurnGranted)
    val Terminal: Set[String] = Set(Merged, OwnerGone, Failed)
    val All: Set[String] = Open ++ Terminal

    def isTerminal(s: String): Boolean = Terminal.contains(s)
  end State

  /** 合并回合结论（属主回报；onTerminal 的裁决输入）。 */
  sealed trait RoundOutcome

  object RoundOutcome:
    /** 机械验收通过——合并真落地。 */
    case object Merged extends RoundOutcome

    /** 回合被拒（冲突 / 脏树 / 机械验收拒绝）——原因随行关账 failed。 */
    final case class Refused(reason: String) extends RoundOutcome
  end RoundOutcome

  /**
   * 一条候合并成员（账本记录）。`readyAt` = ready-to-merge 终态（完成）时间——
   * **唯一排序键**（完成序 FIFO；同刻并列按 delegateId 字典序，保决定性）。
   * `baseWs` = 合并执行的工作区（worktree 腿 = 基工作区——worktree 里 `git merge`
   * 会并进自己的分支，方向错）；`baseBranch` = spawn 时基工作区的当前分支。
   */
  final case class Member(
    delegateId: String,
    branch: String,
    baseWs: String,
    baseBranch: String,
    worktree: Boolean,
    projectKey: String,
    taskId: Option[String],
    parentSessionId: String,
    description: String,
    readyAt: Long,
    /** 通道类别："internal"（actor 注入）| "external"（续聊通道）。 */
    channelKind: String,
    /** 状态机：waiting → turn-granted → merged / owner-gone / failed。 */
    state: String = State.Waiting,
    /** 放行时刻 + 放行时 base tip（通知携带，审计可对账）。 */
    grantedAt: Option[Long] = None,
    grantedBaseTip: Option[String] = None,
    /** 关账时刻 + 原因（merged / owner-gone / failed 三终态必有）。 */
    closedAt: Option[Long] = None,
    closeReason: Option[String] = None,
    /** 最近一次未放行原因（净空闸/分支闸；面板可见——waiting 成员的「为什么还没轮到」）。 */
    lastBlockReason: Option[String] = None,
    /**
     * 内部腿通道（进程内运行态，**不持久**）：child = 注入目标 actor，supervisor =
     * 合并轮终态回路的 replyTo。持久账本回载后为 None——重启后的开放成员在 boot
     * reconcile 走 owner-gone（会话随进程消亡，诚实关账）。
     */
    internal: Option[InternalLeg] = None,
    /**
     * 外部腿通道（进程内闭包，**不持久**）：`send(brief)` = 续聊通道注入
     * （mailbox / CLI resume）。`Left` = 通道失败 ⇒ owner-gone。
     */
    external: Option[ExternalLeg] = None
  ):
    def open: Boolean = State.Open.contains(state)
  end Member

  /** 内部腿通道（actor 注入单点）。 */
  final case class InternalLeg(childRef: ActorRef[AgentCommand], supervisorRef: ActorRef[AgentEvent])

  /**
   * 外部腿通道：`send(brief)` = 把合并指令送进外部 delegate 的续聊通道
   * （live → mailbox 排队；终态 → CLI resume）。`Right(())` = 送进成功；
   * `Left` = 通道失败（owner-gone，绝不楔死队列）。
   */
  final case class ExternalLeg(send: String => IO[Either[String, Unit]])

  // ── 进程内态 + 持久账本 ───────────────────────────────────────────────

  private val lock = new Object
  private var members: Map[String, Member] = Map.empty // delegateId → Member（含终态，审计/面板保留）
  private var loaded = false

  /** 账本文件（`<dataRoot>/merge-ledger.json`；逐迁移写穿，原子替换）。 */
  private def ledgerPath: os.Path = PathUtil.dataRoot / "merge-ledger.json"

  /** 持久化形状（只存数据字段——通道闭包/actor ref 是进程内运行态，永不落盘）。 */
  private def memberJson(m: Member): Json = Json.obj(
    "delegateId" -> m.delegateId.asJson,
    "branch" -> m.branch.asJson,
    "baseWs" -> m.baseWs.asJson,
    "baseBranch" -> m.baseBranch.asJson,
    "worktree" -> m.worktree.asJson,
    "projectKey" -> m.projectKey.asJson,
    "taskId" -> m.taskId.asJson,
    "parentSessionId" -> m.parentSessionId.asJson,
    "description" -> m.description.asJson,
    "readyAt" -> m.readyAt.asJson,
    "channelKind" -> m.channelKind.asJson,
    "state" -> m.state.asJson,
    "grantedAt" -> m.grantedAt.asJson,
    "grantedBaseTip" -> m.grantedBaseTip.asJson,
    "closedAt" -> m.closedAt.asJson,
    "closeReason" -> m.closeReason.asJson,
    "lastBlockReason" -> m.lastBlockReason.asJson
  )

  private def memberFromJson(j: Json): Option[Member] =
    val c = j.hcursor
    for
      delegateId <- c.get[String]("delegateId").toOption
      branch <- c.get[String]("branch").toOption
      baseWs <- c.get[String]("baseWs").toOption
      baseBranch <- c.get[String]("baseBranch").toOption
      projectKey <- c.get[String]("projectKey").toOption
      readyAt <- c.get[Long]("readyAt").toOption
    yield Member(
      delegateId = delegateId,
      branch = branch,
      baseWs = baseWs,
      baseBranch = baseBranch,
      worktree = c.get[Boolean]("worktree").toOption.getOrElse(false),
      projectKey = projectKey,
      taskId = c.get[Option[String]]("taskId").toOption.flatten,
      parentSessionId = c.get[String]("parentSessionId").toOption.getOrElse(""),
      description = c.get[String]("description").toOption.getOrElse(""),
      readyAt = readyAt,
      channelKind = c.get[String]("channelKind").toOption.getOrElse("internal"),
      state = c.get[String]("state").toOption.filter(State.All.contains).getOrElse(State.Waiting),
      grantedAt = c.get[Option[Long]]("grantedAt").toOption.flatten,
      grantedBaseTip = c.get[Option[String]]("grantedBaseTip").toOption.flatten,
      closedAt = c.get[Option[Long]]("closedAt").toOption.flatten,
      closeReason = c.get[Option[String]]("closeReason").toOption.flatten,
      lastBlockReason = c.get[Option[String]]("lastBlockReason").toOption.flatten
    )

  private def persistLocked(): Unit =
    try
      val payload = Json.obj("version" -> 1.asJson, "members" -> Json.fromValues(members.values.map(memberJson).toList))
      val tmp = PathUtil.dataRoot / s"${ledgerPath.last}.tmp"
      os.write.over(tmp, payload.noSpaces, createFolders = true)
      os.move(tmp, ledgerPath, replaceExisting = true)
    catch
      case e: Throwable =>
        logger.warn(s"merge-ledger persist failed (memory state unaffected): ${Option(e.getMessage).getOrElse(e.toString).take(200)}")

  /** 惰性加载（首次访问；文件缺失/损坏 = 空账起步，坏行跳过不炸）。 */
  private def ensureLoadedLocked(): Unit =
    if !loaded then
      loaded = true
      try
        if os.exists(ledgerPath) then
          io.circe.parser.parse(os.read(ledgerPath)).toOption.flatMap(_.hcursor.downField("members").as[List[Json]].toOption) match
            case None => logger.warn(s"merge-ledger at $ledgerPath unreadable — starting an empty ledger")
            case Some(rows) =>
              val parsed = rows.flatMap(memberFromJson)
              members = parsed.map(m => m.delegateId -> m).toMap
              if parsed.nonEmpty then
                logger.info(s"merge-ledger loaded: ${parsed.size} member(s) (${parsed.count(_.open)} open)")
      catch
        case e: Throwable =>
          logger.warn(s"merge-ledger load failed — starting an empty ledger: ${Option(e.getMessage).getOrElse(e.toString).take(200)}")

  /** 查询/审计用全量快照（readyAt 升序）。 */
  def snapshot: List[Member] = lock.synchronized {
    ensureLoadedLocked()
    members.values.toList.sortBy(m => (m.readyAt, m.delegateId))
  }

  /** 开放成员快照（waiting + turn-granted）。 */
  def openSnapshot: List[Member] = snapshot.filter(_.open)

  def find(delegateId: String): Option[Member] = lock.synchronized {
    ensureLoadedLocked()
    members.get(delegateId)
  }

  /**
   * 合并轮在飞判据（DelegateTerminal mergeRound 判定单点）：该 delegate 的成员
   * 处于 turn-granted（持窗、合并轮指令已注入）。仅 waiting（未放行）不算——
   * 工作轮终态与合并轮终态必须可分。
   */
  def isMergeRound(delegateId: String): Boolean =
    find(delegateId).exists(_.state == State.TurnGranted)

  /** 活性探针（注入前探测属主 actor 是否活——不盲投死 ref）。生产 boot 装配；未装配 = 恒活。 */
  private val livenessProbe: java.util.concurrent.atomic.AtomicReference[ActorRef[?] => IO[Boolean]] =
    new java.util.concurrent.atomic.AtomicReference(_ => IO.pure(true))

  def installLivenessProbe(probe: ActorRef[?] => IO[Boolean]): Unit = livenessProbe.set(probe)

  /** 探针求值（fail-open：探针自身故障 = 不判死）。 */
  private def childAlive(ref: ActorRef[?]): Boolean =
    try livenessProbe.get.apply(ref).unsafeRunSync()
    catch case _: Throwable => true

  // ── 属主失联升级端口（M2 注册器形态：core 只见面，agent 侧装配实现） ──

  object OwnerGonePort:

    /**
     * owner-gone 关账的升级通知面（持久注入父/根 + 面板警示的数据源）。
     * 实现面负责目标解析与投递路由（deliverToRootOrInbox 同族）；fail-soft。
     */
    trait Face:
      def deliver(m: Member, reason: String): IO[Unit]
    end Face

    private val face = new java.util.concurrent.atomic.AtomicReference[Option[Face]](None)

    def install(f: Face): Unit = face.set(Some(f))

    def clear(): Unit = face.set(None)

    def deliver(m: Member, reason: String): Unit =
      face.get match
        case Some(f) =>
          try f.deliver(m, reason).unsafeRunSync()
          catch
            case e: Throwable =>
              logger.warn(s"[merge-queue] owner-gone escalation delivery failed for ${m.delegateId}: ${Option(e.getMessage).getOrElse(e.toString).take(200)}")
        case None =>
          logger.warn(
            s"[merge-queue] OwnerGonePort not installed — owner-gone escalation for '${m.delegateId}' is ledger-only " +
              "(production boot installs this at SharedResources construction)"
          )
  end OwnerGonePort

  // ── 面板/行投影 ───────────────────────────────────────────────────────

  /**
   * 某 delegate 当下的排队槽位投影（行内「谁挡着我/我排第几」读面单点）。
   * 只对**开放成员**有值（终态成员 = 已出账，键缺席）。排序键 = 引擎自己的
   * FIFO 序（readyAt ↑ → delegateId ↑），行投影不二次派生次序。
   */
  final case class QueueView(
    delegateId: String,
    projectKey: String,
    branch: String,
    baseBranch: String,
    /** 1 起位次（引擎 FIFO 序；队头 = 1）。 */
    position: Int,
    total: Int,
    /** 严格排在本位之前的 delegateId（引擎序）。队头 = Nil。 */
    ahead: List[String],
    /** 合并窗持有者（turn-granted 成员 id）；None = 窗空闲（纯排队）。 */
    holder: Option[String],
    /** 本条状态（waiting / turn-granted）。 */
    state: String,
    readyAt: Long
  )

  def queueViewOf(delegateId: String): Option[QueueView] = lock.synchronized {
    ensureLoadedLocked()
    members.get(delegateId).filter(_.open).flatMap { me =>
      val open = openSortedLocked(me.projectKey)
      val pos = open.indexWhere(_.delegateId == delegateId)
      if pos < 0 then None
      else
        Some(
          QueueView(
            delegateId = delegateId,
            projectKey = me.projectKey,
            branch = me.branch,
            baseBranch = me.baseBranch,
            position = pos + 1,
            total = open.size,
            ahead = open.take(pos).map(_.delegateId),
            holder = open.find(_.state == State.TurnGranted).map(_.delegateId),
            state = me.state,
            readyAt = me.readyAt
          )
        )
    }
  }

  // lock held；本项目开放成员按 FIFO 序
  private def openSortedLocked(projectKey: String): List[Member] =
    members.values.toList.filter(m => m.projectKey == projectKey && m.open).sortBy(m => (m.readyAt, m.delegateId))

  // lock held
  private def holderLocked(projectKey: String): Option[Member] =
    openSortedLocked(projectKey).find(_.state == State.TurnGranted)

  // ── 审计行（fail-soft：账本失败绝不阻断队列） ─────────────────────────

  private def record(m: Member, action: String, extra: Json): Unit =
    try
      ChainStore.appendSync(
        ChainStore.keyFor(m.taskId, m.delegateId),
        EntryKind,
        Json
          .obj(
            "action" -> action.asJson,
            "delegateId" -> m.delegateId.asJson,
            "branch" -> m.branch.asJson,
            "projectKey" -> m.projectKey.asJson,
            "state" -> m.state.asJson,
            "readyAt" -> m.readyAt.asJson
          )
          .deepMerge(extra)
      )
    catch
      case e: Throwable =>
        logger.warn(s"MergeQueue ChainStore line failed ($action): ${Option(e.getMessage).getOrElse(e.toString).take(200)}")

  /** 最近一次关账/拒绝原因（ChainStore 扫描——API 状态面的原因列；fail-soft 空映射）。 */
  def lastReasons(delegateIds: Set[String]): Map[String, String] =
    if delegateIds.isEmpty then Map.empty
    else
      try
        ChainStore
          .listSync()
          .flatMap(_.entries)
          .filter(_.kind == EntryKind)
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
          logger.warn(s"[merge-queue] reason scan failed: ${e.getMessage}")
          Map.empty

  // ── 入队（waiting） ───────────────────────────────────────────────────

  /**
   * ready-to-merge 入账并尝试放行。FIFO 排序键 = `readyAt`（完成时间）——后到不得
   * 插队；排序在**本单点**（spec 判红锚）。锁定区只做账本状态机；放行动作
   * （git 核对 + 通知）在锁外执行。
   */
  def enqueue(entry: Member): Unit =
    lock
      .synchronized {
        ensureLoadedLocked()
        members.get(entry.delegateId) match
          case Some(existing) if existing.open =>
            // 幂等：已在账的开放成员不重复入账（重复完成/钩子重放），只刷新通道。
            val refreshed = existing.copy(internal = entry.internal.orElse(existing.internal), external = entry.external.orElse(existing.external))
            members = members.updated(entry.delegateId, refreshed)
            record(refreshed, "enqueued-duplicate", Json.obj("state" -> refreshed.state.asJson))
            if refreshed.state == State.Waiting then Some(refreshed) else None
          case _ =>
            members = members.updated(entry.delegateId, entry)
            persistLocked()
            record(entry, "enqueued", Json.obj("readyAt" -> entry.readyAt.asJson, "queueDepth" -> openSortedLocked(entry.projectKey).size.asJson))
            Some(entry)
      }
      .foreach { m => if m.state == State.Waiting then pump(m.projectKey) }

  /** IO 包装（BackoffSupervisor / settle 路径是 IO 上下文）。 */
  def enqueueIO(entry: Member): IO[Unit] = IO.blocking(enqueue(entry))

  // ── 放行（waiting → turn-granted；通知即放行） ────────────────────────

  /**
   * 一拍放行尝试（本 projectKey）。窗被持（已有 turn-granted）⇒ 零动作；
   * 队头 waiting 成员按闸序核对：账实核对（已并 → 关账，永不通知合并）→
   * 净空闸（脏/分支错位 → 留队记账）→ 通知放行（通道失败 → owner-gone）。
   * 锁内只做账本迁移；git 面与通道注入全部锁外。
   */
  def pump(projectKey: String): Unit =
    val candidate = lock.synchronized {
      ensureLoadedLocked()
      if holderLocked(projectKey).isDefined then None // 单飞行：窗被持
      else
        openSortedLocked(projectKey).find(_.state == State.Waiting).map { head =>
          // 先占窗（占位即迁移）——通知在锁外，绝不双授。
          val reserved = head.copy(state = State.TurnGranted, grantedAt = Some(System.currentTimeMillis()))
          members = members.updated(head.delegateId, reserved)
          persistLocked()
          reserved
        }
    }
    candidate.foreach(grant)

  /** 已占窗成员的放行腿：账实核对 → 净空闸 → 通知（任一关账腿释放窗并续放下一位）。 */
  private def grant(reserved: Member): Unit =
    // ① 账实核对（先于一切——已并成员直接关账，不再通知不再重试）。
    alreadyMerged(reserved) match
      case true =>
        closeMerged(reserved.delegateId, via = "fact-check", closeReason = Some("branch already merged into base — closed at grant time"), cleanup = true)
        notifyAlreadyLanded(reserved) // 属主还 dock 着 → 立即收尾通知（delegate 纪律 #6）
        pump(reserved.projectKey)
        return
      case false => ()
    end match
    // ② 净空闸（引擎侧 base 净空维护——白名单工件还原/收编；非合并执行）。
    baseReady(reserved) match
      case Left(blockReason) =>
        requeueBlocked(reserved, blockReason)
        return
      case Right(baseTip) => ()
    end match
    // ③ 放行 = 通知（属主自己执行合并并回报）。
    notifyTurn(reserved) match
      case Right(()) =>
        val tip = baseTipOf(reserved)
        lock.synchronized {
          members.get(reserved.delegateId).foreach { cur =>
            val stamped = cur.copy(grantedBaseTip = tip)
            members = members.updated(cur.delegateId, stamped)
            persistLocked()
            record(stamped, "granted", Json.obj("baseTip" -> tip.fold("".asJson)(_.take(12).asJson), "viaChannel" -> reserved.channelKind.asJson))
          }
        }
        logger.info(
          s"[merge-queue] turn granted to '${reserved.delegateId}' (branch '${reserved.branch}' → '${reserved.baseBranch}', window holder = single)"
        )
      case Left(err) =>
        markOwnerGone(reserved.delegateId, s"merge-turn notification undeliverable: ${err.take(200)}")
        pump(reserved.projectKey)
  end grant

  /** 账实核对单点：分支头已是基分支祖先 ⟺ 已并入（今晚 should-not-happen 的消除闸）。 */
  private def alreadyMerged(m: Member): Boolean =
    Try(os.Path(m.baseWs)).toOption.filter(GitWorktrees.isGitRepo) match
      case None => false // 读数不可得 = 不判（fail-open，交给回合终态核对）
      case Some(ws) => GitWorktrees.isAncestor(ws, m.branch, m.baseBranch).contains(true)

  /** base tip（放行通知携带；不可读 = None，brief 用 "unknown"）。 */
  private def baseTipOf(m: Member): Option[String] =
    Try(os.Path(m.baseWs)).toOption.flatMap(ws => GitWorktrees.resolveRef(ws, m.baseBranch))

  /**
   * 净空闸（fail-closed on verified dirt；fail-open on unreadable）：
   *   - 基工作区不可读（非 git / 路径坏）⇒ 放行（历史行为——delegate 的合并回合
   *     会诚实拒绝并进 failed，面板可见）；
   *   - 树脏 ⇒ 先过 [[MergeArtifactCare.hygiene]]（白名单工件还原/收编）；余脏
   *     非空 ⇒ Left（留队，`grant-blocked` 记账）；
   *   - 基分支非检出分支 ⇒ Left（方向闸——绝不把合并轮放进错误座位）。
   * Right(baseTip) = 可放行。
   */
  private def baseReady(m: Member): Either[String, Option[String]] =
    Try(os.Path(m.baseWs)).toOption.filter(os.exists(_)) match
      case None => Right(baseTipOf(m))
      case Some(ws) if !GitWorktrees.isGitRepo(ws) => Right(baseTipOf(m))
      case Some(ws) =>
        try
          val report = MergeArtifactCare.hygiene(ws)
          if report.restored.nonEmpty || report.absorbed.nonEmpty then
            record(
              m,
              "artifact-hygiene",
              Json.obj(
                "restored" -> report.restored.asJson,
                "absorbed" -> report.absorbed.asJson,
                "absorbCommit" -> report.absorbCommit.map(_.take(12)).asJson,
                "remainingDirty" -> report.remainingDirty.asJson
              )
            )
          val remaining = try MergeArtifactCare.dirtyPaths(ws) catch case _: Throwable => List("unreadable")
          if remaining.nonEmpty then
            Left(s"base workspace still dirty after hygiene: ${remaining.take(6).mkString(", ")}")
          else
            val current = try GitWorktrees.currentBranch(ws) catch case _: Throwable => None
            if current.isDefined && current != Some(m.baseBranch) then
              Left(s"base branch '${m.baseBranch}' is not the checked-out branch ('${current.getOrElse("detached HEAD")}') of the base workspace")
            else Right(baseTipOf(m))
        catch
          case e: Throwable => Right(baseTipOf(m)) // 闸面自身故障 = fail-open（不楔死队列）
  end baseReady

  /** 净空闸未过：窗让回（state → waiting）+ 记账 + 面板可见原因；等下一拍。 */
  private def requeueBlocked(reserved: Member, reason: String): Unit =
    lock.synchronized {
      members.get(reserved.delegateId) match
        case Some(cur) if cur.state == State.TurnGranted =>
          val back = cur.copy(state = State.Waiting, grantedAt = None, lastBlockReason = Some(reason.take(300)))
          members = members.updated(cur.delegateId, back)
          persistLocked()
          record(back, "grant-blocked", Json.obj("reason" -> reason.take(300).asJson))
        case _ => () // 两拍间已关账——不动
    }
    logger.info(s"[merge-queue] grant blocked for '${reserved.delegateId}': $reason")

  /** 放行通知（按通道分派；Right = 已送达属主会话）。 */
  private def notifyTurn(m: Member): Either[String, Unit] =
    val brief = mergeBriefFor(m)
    m.external match
      case Some(ext) =>
        try ext.send(brief).attempt.unsafeRunSync() match
          case Right(r) => r
          case Left(e) => Left(Option(e.getMessage).getOrElse(e.toString))
        catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString))
      case None =>
        m.internal match
          case Some(leg) if childAlive(leg.childRef) =>
            try
              (leg.childRef ! AgentCommand.UserInput(
                text = brief,
                replyTo = Some(leg.supervisorRef),
                source = Some(Source),
                eventType = Some("merge-turn")
              )).unsafeRunSync()
              Right(())
            catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString))
          case Some(_) => Left("owner session is no longer live at grant time")
          case None => Left("no delivery channel on record (channel lost across restart)")
  end notifyTurn

  /**
   * 「已落地」收尾通知（grant 时账实核对命中 + 属主会话还 dock 着）：让属主
   * 立即 finish、不碰 base（delegate 侧纪律 #6 的引擎配对闸）。通知失败只 WARN
   * ——账已关（merged），会话超时链兜底。
   */
  private def notifyAlreadyLanded(m: Member): Unit =
    val brief = alreadyLandedBrief(m)
    val deliver: Either[String, Unit] = m.external match
      case Some(ext) =>
        try ext.send(brief).attempt.unsafeRunSync() match
          case Right(r) => r
          case Left(e) => Left(Option(e.getMessage).getOrElse(e.toString))
        catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString))
      case None =>
        m.internal match
          case Some(leg) if childAlive(leg.childRef) =>
            try
              (leg.childRef ! AgentCommand.UserInput(
                text = brief,
                replyTo = Some(leg.supervisorRef),
                source = Some(Source),
                eventType = Some("merge-turn")
              )).unsafeRunSync()
              Right(())
            catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString))
          case _ => Right(()) // 会话已死 = 无可通知，账已关，零动作
    deliver match
      case Right(()) => record(m, "already-landed-notice", Json.obj())
      case Left(err) => logger.warn(s"[merge-queue] already-landed notice failed for '${m.delegateId}': ${err.take(200)}")
  end notifyAlreadyLanded

  // ── 关账（merged / owner-gone / failed；全部先账实核对再落终态） ──────

  /**
   * 关账 merged（幂等）。`via` = 审计来源（fact-check / round / boot-reconcile）。
   * `cleanup` = 合并后现场回收（PostMergeCleanup——零领先守卫，绝不丢工作）。
   * 关账前再跑一次账实核对：事实未落地但调用方声称 merged（机械验收已挡住常规
   * 路径；boot/手工路径兜底）⇒ 照关（调用方是裁决者），但 closeReason 如实标注。
   */
  def closeMerged(delegateId: String, via: String, closeReason: Option[String] = None, cleanup: Boolean = false): Unit =
    lock
      .synchronized {
        ensureLoadedLocked()
        members.get(delegateId) match
          case Some(m) if m.open =>
            val closed = m.copy(
              state = State.Merged,
              closedAt = Some(System.currentTimeMillis()),
              closeReason = closeReason.orElse(Some(s"merge landed (via $via)"))
            )
            members = members.updated(delegateId, closed)
            persistLocked()
            record(closed, "merge-merged", Json.obj("via" -> via.asJson, "reason" -> closed.closeReason.fold("".asJson)(_.asJson)))
            if cleanup then
              try
                DelegateRegistry.find(delegateId).foreach(rec => PostMergeCleanup.run(Some(rec)).unsafeRunSync())
              catch
                case e: Throwable =>
                  logger.warn(s"[merge-queue] post-merge cleanup failed for $delegateId: ${Option(e.getMessage).getOrElse(e.toString).take(200)}")
            Some(closed)
          case _ => None
      }
      .foreach { closed => pump(closed.projectKey) }

  /** IO 包装（boot reconcile 路径）。 */
  def closeMergedIO(delegateId: String, via: String, closeReason: Option[String] = None, cleanup: Boolean = false): IO[Unit] =
    IO.blocking(closeMerged(delegateId, via, closeReason, cleanup))

  /**
   * 关账 owner-gone（幂等）：属主失联——通知不可达 / 会话终态 / 重启后通道丢失。
   * 关账前**先账实核对**：期间被并（human/别的轮次）⇒ 关 merged 而非 owner-gone
   * （今晚「分支已落定仍在账上罚站」的消除闸）。升级通知经 [[OwnerGonePort]]
   * （面板「需 dispatcher 处置」的数据源；绝不静默）。
   */
  def markOwnerGone(delegateId: String, reason: String): Unit =
    val mOpt = lock.synchronized {
      ensureLoadedLocked()
      members.get(delegateId).filter(_.open)
    }
    mOpt.foreach { m =>
      if alreadyMerged(m) then
        closeMerged(delegateId, via = "fact-check-on-owner-gone", closeReason = Some("owner gone, but the branch was already merged — closed as merged"), cleanup = true)
      else
        val closed = lock.synchronized {
          members.get(delegateId) match
            case Some(cur) if cur.open =>
              val c = cur.copy(state = State.OwnerGone, closedAt = Some(System.currentTimeMillis()), closeReason = Some(reason.take(300)))
              members = members.updated(delegateId, c)
              persistLocked()
              record(c, "owner-gone", Json.obj("reason" -> reason.take(300).asJson))
              Some(c)
            case _ => None
        }
        closed.foreach { c =>
          logger.warn(s"[merge-queue] OWNER-GONE '${c.delegateId}' (branch '${c.branch}'): $reason — needs dispatcher handling")
          OwnerGonePort.deliver(c, reason)
          pump(c.projectKey)
        }
    }
  end markOwnerGone

  /** IO 包装。 */
  def markOwnerGoneIO(delegateId: String, reason: String): IO[Unit] = IO.blocking(markOwnerGone(delegateId, reason))

  // ── 成员终态（合并轮回报 / 会话终态） ─────────────────────────────────

  /**
   * 属主会话终态钩子（BackoffSupervisor / ExternalSessionRunner settle / 停靠看护）。
   *   - `Some(Merged)`：合并轮机械验收通过 → 关账 merged；
   *   - `Some(Refused(r))`：合并轮被拒 → 关账 failed(原因)——**无重试、无 park**；
   *   - `None`（回合外终态：取消/超时/会话死亡）：先账实核对（期间被并 ⇒ merged），
   *     否则 owner-gone。
   * 幂等（查无/已关账 = no-op）；关账即释放窗并轮转下一位。
   */
  def onTerminal(delegateId: String, outcome: Option[RoundOutcome] = None): Unit =
    val mOpt = lock.synchronized {
      ensureLoadedLocked()
      members.get(delegateId).filter(_.open)
    }
    mOpt.foreach { m =>
      outcome match
        case Some(RoundOutcome.Merged) =>
          closeMerged(delegateId, via = "merge-round", cleanup = true)
        case Some(RoundOutcome.Refused(reason)) =>
          closeFailed(delegateId, reason)
        case None =>
          if alreadyMerged(m) then
            closeMerged(delegateId, via = "fact-check-on-terminal", closeReason = Some("session ended; the branch was already merged"), cleanup = true)
          else
            markOwnerGone(delegateId, s"owner session ended without completing the merge round (state was '${m.state}')")
    }
  end onTerminal

  /** IO 包装。 */
  def onTerminalIO(delegateId: String, outcome: Option[RoundOutcome] = None): IO[Unit] =
    IO.blocking(onTerminal(delegateId, outcome))

  /** 关账 failed（合并回合被拒；原因落账 + 面板可见；无自动重试）。 */
  private def closeFailed(delegateId: String, reason: String): Unit =
    lock
      .synchronized {
        ensureLoadedLocked()
        members.get(delegateId) match
          case Some(m) if m.open =>
            val closed = m.copy(state = State.Failed, closedAt = Some(System.currentTimeMillis()), closeReason = Some(reason.take(300)))
            members = members.updated(delegateId, closed)
            persistLocked()
            record(closed, "merge-failed", Json.obj("reason" -> reason.take(300).asJson))
            Some(closed)
          case _ => None
      }
      .foreach { closed =>
        logger.warn(s"[merge-queue] merge round FAILED for '${closed.delegateId}' (branch '${closed.branch}'): ${reason.take(200)} — closed, no auto-retry")
        pump(closed.projectKey)
      }
  end closeFailed

  // ── 活性看护（扫描拍消费；TaskStuckWatcher.scan 尾部单点） ────────────

  /**
   * 一拍看护（事件判据，零时间判据）：
   *   - 持窗成员（turn-granted）内部腿属主 actor 已死 ⇒ owner-gone（窗绝不许尸体持死）；
   *   - 每个有开放成员的 project 补一拍放行尝试（净空转洁 / 阻碍消除后自然推进）。
   * 幂等：动作过的成员已关账，重复扫描零重复动作。
   */
  def careTick(): Unit =
    val granted = snapshot.filter(m => m.state == State.TurnGranted)
    granted.foreach { m =>
      m.internal match
        case Some(leg) if !childAlive(leg.childRef) =>
          markOwnerGone(m.delegateId, "merge-round holder session is no longer live — needs dispatcher handling")
        case _ => ()
    }
    snapshot.map(_.projectKey).distinct.foreach(pump)

  /** IO 包装（扫描腿是 IO 上下文）。 */
  def careTickIO: IO[Unit] = IO.blocking(careTick())

  // ── 崩溃重启换绑（监督者 respawn 换新子 actor——事件驱动） ────────────

  /**
   * delegate 会话崩溃经监督者 respawn 换新子 actor：开放成员的内部腿通道原地换绑
   * （进程内运行态；账本数据面零变化）。已关账/查无 = no-op。
   */
  def reattach(delegateId: String, newChildRef: ActorRef[AgentCommand]): Unit =
    lock.synchronized {
      ensureLoadedLocked()
      members.get(delegateId).filter(_.open) match
        case None => ()
        case Some(m) =>
          val refreshed = m.copy(internal = m.internal.map(_.copy(childRef = newChildRef)))
          members = members.updated(delegateId, refreshed)
          // 通道闭包不落盘（进程内运行态）——只落审计行。
          record(refreshed, "session-reattached", Json.obj("phase" -> "respawn-reattach".asJson))
    }

  /** IO 包装（BackoffSupervisor respawn 路径是 IO 上下文）。 */
  def reattachIO(delegateId: String, newChildRef: ActorRef[AgentCommand]): IO[Unit] =
    IO.blocking(reattach(delegateId, newChildRef))

  // ── 合并指令文本（放行通知本体） ─────────────────────────────────────

  /**
   * 合并轮通知（🔴 模型可见文案）。放行 = 通知：属主自己是唯一执行者。文本三段：
   *   - 账面：轮到你了（单持有人）+ base 当前 tip；
   *   - 账实核对预检（只读）：`merge-base --is-ancestor` 已 0 ⇒ 合并已落地——
   *     不碰 base 立即收尾（delegate 侧纪律 #6 的引擎配对闸；复活/续投实例的
   *     陈旧重放在此被只读预检挡下）；
   *   - 执行：单命令 `git -C <baseWs> merge --no-ff <branch>`；拒绝/冲突 = STOP
   *     原文上报（无重试——failed 关账，处置归 dispatcher）。
   */
  def mergeBriefFor(m: Member): String =
    val tip = m.grantedBaseTip.map(_.take(12)).getOrElse("unknown")
    s"""<system-reminder>
Merge turn (FIFO): your branch `${m.branch}` is ready-to-merge and it is now YOUR turn — you are the single merge-window holder for `${m.baseWs}` (no one else may merge while you hold it, so start now).
Base branch `${m.baseBranch}` tip now: $tip
Read-only pre-check FIRST (zero writes): run `git -C ${m.baseWs} merge-base --is-ancestor ${m.branch} ${m.baseBranch}` — if it exits 0, the merge has ALREADY landed (an earlier round or another operator landed it): do NOT run the merge, do NOT touch the base workspace; report finish immediately stating it was already merged.
Otherwise execute the merge EXACTLY like this (single command, no flags beyond these):
  git -C ${m.baseWs} merge --no-ff ${m.branch}
This merges your branch into the base branch `${m.baseBranch}`. Do NOT merge anywhere else, do NOT force-push, do NOT rebase, do NOT touch other branches or other sessions' work.
If git refuses or reports conflicts: STOP — change nothing, resolve nothing; report the refusal/conflict verbatim in your final text, call delegate_report(category="finish", detail=<the refusal/conflict>), and end your turn (there is no blocked category — the refusal is your report's content, not a state; the queue closes your membership as failed — no automatic retries).
On success, call delegate_report(category="finish", detail=<what the merge landed>) and end with your final report; the engine re-judges the merge round. Your final report must LEAD with the work result (what the task produced, artifacts, REPORT.md paths) and keep the merge info to one line — the parent session reads this report as the task's result.
</system-reminder>"""
  end mergeBriefFor

  /** 「已落地」收尾通知（grant 时账实核对命中——属主立即 finish，不碰 base）。 */
  private def alreadyLandedBrief(m: Member): String =
    s"""<system-reminder>
Merge ledger close-out: your branch `${m.branch}` is ALREADY merged into `${m.baseBranch}` — your merge-queue membership was closed as merged at grant time (fact-check: `git merge-base --is-ancestor` true). Do NOT run the merge, do NOT touch the base workspace. Call delegate_report(category="finish", detail=<the branch was already merged; membership closed>) and end with your final report (lead with the work result, merge info one line).
</system-reminder>"""
  end alreadyLandedBrief

  /** 测试复位（生产无调用方）：清内存 + 清账本文件。 */
  private[nebflow] def reset(): Unit = lock.synchronized {
    members = Map.empty
    loaded = false
    try if os.exists(ledgerPath) then os.remove.all(ledgerPath)
    catch case _: Throwable => ()
  }

  /** 测试专用的「进程重启」模拟：只丢内存态（账本文件保留），下次访问自盘回载。 */
  private[nebflow] def dropMemoryStateForTest(): Unit = lock.synchronized {
    members = Map.empty
    loaded = false
  }

  /** 测试专用：直接把成员写进持久账本（绕过入账泵——模拟重启前的盘上账面）。 */
  private[nebflow] def seedMemberForTest(m: Member): Unit = lock.synchronized {
    ensureLoadedLocked()
    members = members.updated(m.delegateId, m)
    persistLocked()
  }
end MergeQueue
