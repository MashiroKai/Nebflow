package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{ActorContext, ActorRef, ActorSystem, Behavior, AgentCommand, AgentEvent}
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
 *     **这是观测不是执行**：队列永不跑 `git merge`，审计行以 `executor` 字段
 *     区分「谁改的账」（via）与「谁执行的合并」（detected-not-executed / owner-round）。
 *   - **属主宣告闸（本批核心）**：只有属主**明确宣告「完成且可合」**（[[MergeDeclaration]]，
 *     唯一写它的动作 = `delegate_report(category="merge")`）的分支才允许拿合并窗。
 *     「分支上有提交」「会话 idle 够久」「通道有镜像」都不是宣告——旧判据把这三者
 *     当成「已完成」，正是半成品分支被自动合进 main 的机制。属主不在场 = 没人能宣告
 *     ⇒ 失联成员结构上拿不到窗（成员资格保留 + 可见告警，绝不自动合）。
 *   - **合并前最小健康闸**（[[MergeHealthGate]]）：冲突标记残留 / 合不干净 / 关键
 *     测试红 ⇒ 不授窗 + 原因全文（面板 + 父通知）。
 *   - **净空闸（#943 起 fail-soft）**：基工作区先过 [[MergeArtifactCare.hygiene]]
 *     （还原/收编/判性：同 blob 复本清理、路径外无关件移隔离区）；余脏
 *     **只 WARN 放行**——delegate 的 `git merge` 诚实撞脏进 failed 可见关账，
 *     队列不再留队停靠；基分支非检出分支（方向闸）仍留队并**必通知**。
 *
 * #943 阻塞必通知：任何阻塞（[[requeueBlocked]]）/失败（[[closeFailed]]）路径经
 * [[BlockNotifyPort]] 向父会话发可行动通知（原因全文不截断；同因去重，状态变化
 * 重发，N 拍仍堵升级作者）；owner-gone 沿 [[OwnerGonePort]]（既有）。
 *
 * 成员回报驱动关账：合并轮终态（BackoffSupervisor / ExternalSessionRunner settle）
 * → [[onTerminal]]——机械验收通过 = merged；拒绝 = failed(原因)；回合外终态
 * （取消/超时/会话死亡）先账实核对再落 owner-gone。
 *
 * == write-ahead 通道（R5 重启丢队修复 · 报告二 §4.3 / 设计稿 §1 P5 行）==
 *
 * 账本持久但**投递通道是进程内运行态**（internal/external 闭包不落盘）——重启把
 * 未关账成员打成 owner-gone 人工处置；ready 之后、入账写盘之前被杀连账本条目都
 * 没有（B2 实证）。修法 = **write-ahead**：每次入账/状态迁移落内存与账本**之前**，
 * 成员镜像先写持久通道（[[MergeQueueChannel]]，DeviceResultOutbox 同型）；boot
 * 首次加载时重放通道（[[replayChannelLocked]]）——账本缺的成员从通道收养、账本
 * 落后于通道的迁移从通道并回（「ready 前被杀连账本条目都没有」的窗口被通道闭合）。
 * 已关账镜像由压缩腿批量移除（账本持终态审计，通道副本冗余）。
 *
 * **owner-gone 前提反转**：「通道丢失 ⇒ owner-gone」的判定以**通道无未关账记录**
 * 为前提（[[notifyTurn]] 无通道腿 + MergeQueueBootSweep 对账腿）——通道还有该成员
 * 的未关账镜像 = 成员资格在盘上，进程内存缺席（闭包随进程消亡）不再构成关账依据，
 * 成员保留留队 + 可行动通知（N 拍升级作者）。判定机/合并轮语义零改动（本批只加
 * 持久性；双模式兼容声明 = 设计稿 §8.3）。
 *
 * 分层纪律：core 不 import agent——属主失联升级走注册器端口
 * [[OwnerGonePort]]（生产装配 = agent.SharedResources 构造行）。
 *
 * == P2 QueueActor（2026-11-06）==
 *
 * 本件从「`object` 单例可变字段 + 一把监视器锁」改成「**一个 actor 拥有状态**」：
 *   - [[Engine]]（本文件内的私有类）持有全部可变状态（members / notifyMemos /
 *     needsHumanAnnounced / factCheckUnreadable），**零锁**——它只被 [[QueueActor]]
 *     的邮箱线程调用，串行由构造保证（P2 前是 27 处加锁块的自觉纪律）。
 *   - 门面（本文件下半段）**只转发不持状态**：20+ 同步调用点签名不变；写面把
 *     operation 投进邮箱并等结果（超时响亮失败），读面读 actor 每次操作后发布的
 *     **不可变快照**（[[Published]]）。
 *   - 提醒 / TTL 全部走 actor 自己的 Tick（`ctx.scheduleOnce` → [[QueueMsg.Tick]]，
 *     截止驱动、截止不变就不重臂）——本件内零睡眠轮询、零定时轮询。
 */
object MergeQueue:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.mergequeue")

  /** 注入源标记（UserInput.source）——UI 气泡归因 + 审计 grep 锚。 */
  val Source: String = "merge-queue"

  /** 审计事件行种类（ChainStore；账本文件是真源，行是审计面）。 */
  val EntryKind: String = "merge-queue"

  // ════════════════════════════════════════════════════════════════════
  // 数据面（类型/端口/纯函数）：**零状态**，spec 与调用方直接引用。
  // ════════════════════════════════════════════════════════════════════

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
     * R1（队头不再堵死全队批 2026-11-04）：连续未能授窗的拍数——每次
     * [[requeueBlocked]] +1，授窗送达/关账时清零。**落账本**（跨重启保留：
     * 重启后敏感度不归零，队列不会从头再撞同一堵墙一遍）。
     */
    blockedRounds: Int = 0,
    /**
     * R1 跳过窗（退避截止，ms）：此刻之前本成员**不参与放行轮转**
     * （[[pumpWith]] 挑候选时跳过）——队头退避期间，后面能走的人先走。
     * 指数退避 + 抖动，上界 [[BackoffMaxMs]]。
     */
    skipUntilMs: Option[Long] = None,
    /**
     * R1 退避上界后的**显式降级态**（"needs-human" + 原因全文）：非空 = 队列不再
     * 自动授窗（重试到上界，再撞也无意义），成员资格保留、careTick 每拍只在面板
     * 上标红；人工把分支并了之后，下一拍**账实核对**会自己关账 merged（R2）。
     *
     * 语义边界（作者 2026-11-03 18:14 四态矩阵）：这是**机制挂起**的显式面
     * （引擎自己放弃自动放行），不是「等作者裁定」——delegate 申报 standby 等
     * 作者回答的语义在 DelegateCompletionMatrix，与队列账本无关，两者绝不合并成
     * 一个状态名。
     */
    needsHuman: Option[String] = None,
    /**
     * **投递身份**（P1 投递身份化批 2026-11-05，作者裁定「投递不再靠进程内闭包」）：
     * 合并指令的收件会话 id = 本分支的属主会话（缺省 = `delegateId` 本身）。
     *
     * 反面前身：`internal: Option[InternalLeg]`（actor ref 闭包）+ `external:
     * Option[ExternalLeg]`（`send(brief)` 闭包）——两者都是**进程内运行态**，重启
     * 即蒸发，于是「重启后属主明明还在，队列却投不出去」（Deferred / owner-gone
     * 一整类误判）。本批把它们整体删掉：投递只认 `ownerSessionId` + 落盘邮箱
     * （MailboxProducer → 会话磁盘邮箱 = 真源），因此**重启后可以直接向持窗者
     * 投递**（身份是持久数据，不是闭包）。
     */
    ownerSessionId: String = "",
    /**
     * 属主宣告快照（[[MergeDeclaration]] 的账本投影；None = **从未宣告**）。
     *
     * 宣告闸批 2026-11-04（作者报障「半成品分支被自动合进 main」）：本字段是
     * 「这批完成没完成」在成员面上的唯一机械证据——它只由属主来源写入
     * （`delegate_report(category="merge")` / 终态重放同一自申报），引擎推断
     * （判定机 / queueFlip / 活跃守卫）**永不写**。放行闸缺它 = 不授窗。
     */
    declaredAt: Option[Long] = None,
    /** 宣告来源（[[MergeDeclaration.Source]]；审计回溯「谁说的话」）。 */
    declareSource: Option[String] = None,
    /** 宣告**当时**的分支 tip（可回溯记录：分支后来长没长，对账可判）。 */
    declaredTip: Option[String] = None,
    /** 授窗时的属主在场读数（true = 投递身份可征询；false = 失联——审计回溯用）。 */
    ownerPresentAtGrant: Option[Boolean] = None
  ):
    def open: Boolean = State.Open.contains(state)

    /** 投递收件会话（身份缺省 = 属主会话本身，即本成员的分支持有者）。 */
    def ownerSession: String = if ownerSessionId.trim.isEmpty then delegateId else ownerSessionId
  end Member

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
              logger.warn(
                s"[merge-queue] owner-gone escalation delivery failed for ${m.delegateId}: ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
              )
        case None =>
          logger.warn(
            s"[merge-queue] OwnerGonePort not installed — owner-gone escalation for '${m.delegateId}' is ledger-only " +
              "(production boot installs this at SharedResources construction)"
          )
  end OwnerGonePort

  /**
   * 阻塞/失败通知端口（台账 #943 · 2026-10-26 作者裁定「也不告知」的消除闸：
   * 「任何阻塞/失败/owner-gone 路径向父会话发可行动通知」——诊断坐实 #896 阻塞
   * 路径零调用）。M2 注册器形态（core 只见面，agent 侧装配实现，OwnerGonePort
   * 同款；实现复用既有 message/notify 通道 = resolveRootSessionId +
   * deliverToRootOrInbox）。**原因全文随行，永不截断**。
   *
   * kind 语义：
   *   - `blocked`：放行闸未过留队（净空/方向闸）——可行动原因 + 全文；
   *   - `failed`：合并回合被拒关账——原因全文 + 分支保留现场指引；
   *   - `escalate`：同因连续 N 拍仍堵（[[EscalateAfterBlockedRounds]]）——升级
   *     通知作者（每成员只升一次）。
   *
   * 去重与升级记账在本侧（[[noteBlockedOrFailed]]）：同 (kind, reason) 只发
   * 一次，状态/原因变化重发；blocked 每拍计数，跨过阈值发一次 escalate。
   */
  object BlockNotifyPort:

    trait Face:
      def deliver(m: Member, kind: String, reason: String): IO[Unit]
    end Face

    private val face = new java.util.concurrent.atomic.AtomicReference[Option[Face]](None)

    def install(f: Face): Unit = face.set(Some(f))

    def clear(): Unit = face.set(None)

    def deliver(m: Member, kind: String, reason: String): Unit =
      face.get match
        case Some(f) =>
          try f.deliver(m, kind, reason).unsafeRunSync()
          catch
            case e: Throwable =>
              logger.warn(
                s"[merge-queue] block-notify delivery failed for ${m.delegateId} ($kind): ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
              )
        case None =>
          logger.warn(
            s"[merge-queue] BlockNotifyPort not installed — '$kind' notification for '${m.delegateId}' is ledger-only " +
              "(production boot installs this at SharedResources construction)"
          )
  end BlockNotifyPort

  /**
   * 属主投递端口（**P1 投递身份化批 2026-11-05**）。
   *
   * 形态 = M2 注册器（与 [[BlockNotifyPort]] / [[OwnerGonePort]] 同款：core 只见
   * 接口、agent 侧装配实现——core 不 import agent，分层纪律不变）。生产实现落
   * `SharedResources`：`MailboxProducer.deliver` → **会话磁盘邮箱**（真源）+ 门铃
   * （actor 消息只是门铃，见 SessionMailbox 语义反转）。
   *
   * 两个方法的分工（身份化的全部内容）：
   *   - `deliver(ownerSessionId, kind, brief, identity)`：把合并指令写进属主会话的
   *     落盘邮箱。`Right(())` = 条目已在盘上（必达成立）；`Left(reason)` = 投不
   *     出去（会话已删 / 写失败）⇒ 调用方判 owner-gone。**没有任何进程内闭包参与**
   *     ——重启后同一份身份照样投得出去。
   *   - `present(ownerSessionId)`：属主**此刻能否被征询**（会话行存在 = 邮件照样
   *     收得下，随任何唤醒点被消费）。端口未装配 = `None`（**不判**，绝不凭空判死）。
   */
  object OwnerDeliveryPort:

    trait Face:
      def deliver(ownerSessionId: String, kind: String, brief: String, identity: String): IO[Either[String, Unit]]
      def present(ownerSessionId: String): IO[Boolean]
    end Face

    private val face = new java.util.concurrent.atomic.AtomicReference[Option[Face]](None)

    def install(f: Face): Unit = face.set(Some(f))
    def clear(): Unit = face.set(None)
    def installed: Boolean = face.get.isDefined

    /**
     * 同步投递（账本腿是同步的——与 [[BlockNotifyPort]] 同款 `unsafeRunSync` 收口）。
     * identity 固定为 `merge-queue-<kind>-<delegateId>`：同成员同 kind 在待消费期间
     * 幂等（`mailOnce` 语义），消费掉之后下一次提醒可再入一条。
     */
    def deliverSync(m: Member, brief: String, kind: String): Either[String, Unit] =
      face.get match
        case Some(f) =>
          try f.deliver(m.ownerSession, kind, brief, s"merge-queue-$kind-${m.delegateId}").unsafeRunSync()
          catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString).take(200))
        case None =>
          Left("owner delivery port not installed (production boot installs it at SharedResources construction)")

    /** 属主可征询读数：`None` = 端口未装配（不判）；探针故障 = fail-open 视为在场。 */
    def presentOf(ownerSessionId: String): Option[Boolean] =
      face.get.map { f =>
        try f.present(ownerSessionId).unsafeRunSync()
        catch case _: Throwable => true
      }
  end OwnerDeliveryPort

  /**
   * 同因连续 N 拍仍堵 ⇒ 升级通知作者（拍 = 放行尝试/扫描拍；零 wall-clock——
   * 计数是事件驱动的，设计零超时红线不受影响）。
   */
  val EscalateAfterBlockedRounds: Int = 10

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
    readyAt: Long,
    /** R1：连续被堵拍数（面板「为什么它一直不去合」的读数）。 */
    blockedRounds: Int = 0,
    /** R1：退避跳过窗截止（ms；None = 不在退避——本拍参与轮转）。 */
    skipUntilMs: Option[Long] = None,
    /** R1：显式降级态（非空 = 队列已停止自动授窗，请人工处理；面板标红）。 */
    needsHuman: Option[String] = None
  )

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
        logger.warn(
          s"MergeQueue ChainStore line failed ($action): ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
        )

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

  /** 合并道判读（[[classifyLane]] 的产物；spec 判红锚四态 + abort 决策面）。 */
  sealed trait LaneVerdict extends Product with Serializable

  object LaneVerdict:
    /** 道净（无 MERGE_HEAD）。 */
    case object Clear extends LaneVerdict

    /** 己方残留：MERGE_HEAD == 本成员分支 tip（自家上一次冲突合并的未收场态）。 */
    case object OwnLeftover extends LaneVerdict

    /** 道忙：占道者会话存活（他批合法占道解冲突）——等待，绝不 abort。 */
    final case class BusyLive(ownerId: String) extends LaneVerdict

    /** 占道者会话已死但未收殓（careTick 一拍内收口）——等待一拍。 */
    final case class BusyDeadHolder(ownerId: String) extends LaneVerdict

    /**
     * 外来占道：无法归属任何队列成员（人的手工合并/未知分支）——等待 + 通知，
     * **绝不 abort**（作者在解冲突时被引擎 abort = 事故）。
     */
    case object Foreign extends LaneVerdict
  end LaneVerdict

  /**
   * 纯判读（spec 判红锚，零 git 零锁）：MERGE_HEAD 缺席 = Clear；== 我分支 tip =
   * OwnLeftover；归属属主按活性分派 BusyLive / BusyDeadHolder；归属失败 = Foreign。
   * `myBranchTip` 缺席（ref 不可读）= fail-open 视作 Foreign（不误判己方残留）。
   */
  private[delegate] def classifyLane(
    mergeHead: Option[String],
    myBranchTip: Option[String],
    occupierOwner: Option[(String, Boolean)] // (delegateId, sessionAlive)
  ): LaneVerdict =
    mergeHead match
      case None => LaneVerdict.Clear
      case Some(mh) =>
        if myBranchTip.contains(mh) then LaneVerdict.OwnLeftover
        else
          occupierOwner match
            case Some((id, true)) => LaneVerdict.BusyLive(id)
            case Some((id, false)) => LaneVerdict.BusyDeadHolder(id)
            case None => LaneVerdict.Foreign
  end classifyLane

  /** 分支 tip == sha 判定（fail-soft：不可读 = 不命中）。 */
  private def branchTipIs(ws: Option[os.Path], branch: String, sha: String): Boolean =
    ws.flatMap(w => GitWorktrees.resolveRef(w, branch)).contains(sha)

  /** 道闸是否放行（grant 用）：Clear / OwnLeftover（自家残留由合并 brief 教学 abort+重试）放行。 */
  private def laneGrants(v: LaneVerdict): Boolean = v match
    case LaneVerdict.Clear | LaneVerdict.OwnLeftover => true
    case _ => false

  /** 道闸阻塞原因文本（#943 必通知的原因全文；🔴 模型/面板可见）。 */
  private[delegate] def laneBlockReason(v: LaneVerdict): String = v match
    case LaneVerdict.BusyLive(id) =>
      s"merge lane busy: another queue member's merge round is in progress (MERGE_HEAD belongs to '$id', session alive and resolving) — waiting for the lane to clear"
    case LaneVerdict.BusyDeadHolder(id) =>
      s"merge lane busy: holder '$id' session is dead with an unfinished merge (orphan MERGE_HEAD) — pending care-tick reap and abort"
    case LaneVerdict.Foreign =>
      s"merge lane busy: an unattributed merge is in progress (MERGE_HEAD matches no queue member — likely a manual/human merge) — waiting; the engine never aborts a merge it cannot attribute"
    case other =>
      s"merge lane blocked: $other"

  /** 宣告查询注入缝（spec 纯驱动；缺省 = 持久宣告账 [[MergeDeclaration]]）。 */
  private val declarationLookup =
    new java.util.concurrent.atomic.AtomicReference[Option[String => Option[MergeDeclaration.Decl]]](None)

  def installDeclarationLookup(f: String => Option[MergeDeclaration.Decl]): Unit = declarationLookup.set(Some(f))

  def clearDeclarationLookup(): Unit = declarationLookup.set(None)

  /** 退避首拍间隔（ms，默认 30s ≈ 一拍扫描；指数退避的基数）。 */
  def BackoffBaseMs: Long =
    sys.props.getOrElse("nebflow.mergequeue.backoff.baseMs", "30000").toLong

  /** 退避上界（ms，默认 10min——上界内一定还会再试一次，不是放弃）。 */
  def BackoffMaxMs: Long =
    sys.props.getOrElse("nebflow.mergequeue.backoff.maxMs", "600000").toLong

  /**
   * 连续被堵拍数上界（到顶降级 `needsHuman`：**不许无限重试**）——适用于**正在
   * 饿死别人**的死结（让位之后仍是它）；孤身死结走 [[SoloMaxBlockedRounds]]。
   */
  def MaxBlockedRounds: Int =
    sys.props.getOrElse("nebflow.mergequeue.backoff.maxRounds", "8").toInt

  /** 孤身死结的绝对封顶倍数（队列里没有别的可放行者 ⇒ 3× 上界才降级）。 */
  val SoloBlockedRoundFactor: Int = 3

  /** 孤身死结的绝对封顶（拍）：到顶照样降级 `needsHuman`——**有限**，只是跑道更长。 */
  def SoloMaxBlockedRounds: Int = MaxBlockedRounds * SoloBlockedRoundFactor

  /** 退避抖动幅度（±ratio；兄弟成员同拍被堵时不共振，避免再次集体同拍）。 */
  val BackoffJitterRatio: Double = 0.2

  /** 单拍内连续改授的最大深度（一次 pump 里让多少个被堵成员靠边站；防递归爆栈）。 */
  private val MaxGrantChainDepth: Int = 16

  /**
   * 退避间隔（纯函数，spec 判红锚）：`base × 2^(rounds-1)` 封顶 `maxMs`，再乘
   * `1 ± jitter`（`jitter ∈ [0,1)` 注入 ⇒ 生产传 `math.random()`，spec 传定值）。
   */
  private[delegate] def backoffDelayMs(rounds: Int, baseMs: Long, maxMs: Long, jitter: Double): Long =
    val exp = math.max(0, math.min(rounds - 1, 20))
    val raw = math.min(maxMs.toDouble, baseMs.toDouble * math.pow(2.0, exp.toDouble))
    val factor = 1.0 + (jitter * 2.0 - 1.0) * BackoffJitterRatio
    math.max(0L, math.round(raw * factor))

  /**
   * 合并轮通知（🔴 模型可见文案）。放行 = 通知：属主自己是唯一执行者。文本三段：
   *   - 账面：轮到你了（单持有人）+ base 当前 tip；
   *   - 账实核对预检（只读）：`merge-base --is-ancestor` 已 0 ⇒ 合并已落地——
   *     不碰 base 立即收尾（delegate 侧纪律 #6 的引擎配对闸；复活/续投实例的
   *     陈旧重放在此被只读预检挡下）；
   *   - 执行：单命令 `git -C <baseWs> merge --no-ff <branch>`；冲突 = **就地解决**
   *     （解完 `git add` + `git commit` 完成合并，含代码/测试文件须跑受影响面
   *     测试），禁止 `merge --abort` 收尾或「rebase 后再合」推回人工（合并轮提示词
   *     加固批 2026-10-31，Canvas 批撤摊事故反面教材）；真解不动才允许 abort 且
   *     必须显式声明「未完成合并」——failed 关账；非冲突拒绝 = STOP 原文上报
   *     （无重试——处置归 dispatcher）。
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
Special case — YOUR OWN leftover merge state: if the merge refuses with "you have unmerged files" / "cannot merge ... unresolved conflicts" AND `git -C ${m.baseWs} rev-parse MERGE_HEAD` equals YOUR branch tip (an earlier conflicted attempt of your own round), recover IN THIS ROUND: run `git -C ${m.baseWs} merge --abort`, then re-run the single merge command above once. Never abort when MERGE_HEAD points at someone else's branch. This abort only clears your own stale state — the merge itself must still COMPLETE in this round per the conflict rules below.
If the merge reports CONFLICTS, they are YOURS to resolve IN THIS ROUND — hard rules (no deferral, no bail-out):
  1. 冲突=必须就地解决：禁止以 merge --abort 收尾，也禁止「rebase 后再合」把冲突推回人工。逐个打开冲突文件完成融合（严禁整文件 ours/theirs 一刀切——两侧语义都必须保留），然后 git add 每个已解决文件并 git commit 完成合并，产出带双亲的 merge commit。
  2. 合并后必测：解冲突涉及代码/测试文件时，合并完成后必须跑受影响面测试（至少冲突文件的相邻套件）再报完成；纯文档/证据文件冲突须在报告中显式声明免测理由。
  3. 诚实申报闸：未真正合入（base 分支 HEAD 无第二亲本＝你的分支 tip）不得声称已合并——机械验收直接核对 git 实况，虚报＝整轮作废。
  反面教材（Canvas 批，10-31）：合并轮撞 4 件冲突后 merge --abort 撤摊、报「需 rebase 后再合」且申报完成——解冲突被推回人工，该批作废重来。
  Only if you genuinely cannot reconstruct the conflicting semantics: run `git -C ${m.baseWs} merge --abort`, and your final text MUST state 未完成合并 verbatim — you may not claim completion; the round closes as failed (no automatic retries).
If git refuses for a NON-conflict reason: STOP — change nothing; report the refusal verbatim in your final text, call delegate_report(category="finish", detail=<the refusal>), and end your turn (there is no blocked category — the refusal is your report's content, not a state; a lane-busy refusal re-queues your membership automatically — a genuine refusal closes it as failed, no automatic retries).
On success, call delegate_report(category="finish", detail=<what the merge landed>) and end with your final report; the engine re-judges the merge round (mechanical acceptance: the base HEAD must be a two-parent merge commit whose second parent is your branch tip). Your final report must LEAD with the work result (what the task produced, artifacts, REPORT.md paths) and keep the merge info to one line — the parent session reads this report as the task's result.
</system-reminder>"""
  end mergeBriefFor

  /** 「已落地」收尾通知（grant 时账实核对命中——属主立即 finish，不碰 base）。 */
  private def alreadyLandedBrief(m: Member): String =
    s"""<system-reminder>
Merge ledger close-out: your branch `${m.branch}` is ALREADY merged into `${m.baseBranch}` — your merge-queue membership was closed as merged at grant time (fact-check: `git merge-base --is-ancestor` true). Do NOT run the merge, do NOT touch the base workspace. Call delegate_report(category="finish", detail=<the branch was already merged; membership closed>) and end with your final report (lead with the work result, merge info one line).
</system-reminder>"""
  end alreadyLandedBrief

  // ════════════════════════════════════════════════════════════════════
  // 状态机（Engine）：队列的唯一持有者——只被 QueueActor 的邮箱串行调用。
  // ════════════════════════════════════════════════════════════════════

  private final class Engine:
    private var members: Map[String, Member] = Map.empty // delegateId → Member（含终态，审计/面板保留）
    private var loaded = false

    /** 自驱拍间隔（0 = 不武装；生产 `GatewayMain` 开机置 [[SelfTickMs]]，spec 缺省不动）。 */
    private var selfTickMs: Long = 0L

    /** 自驱拍的 projectKey 作用域（空 = 全部；spec 只驱动自己的用例，绝不泼到别人的 member）。 */
    private var selfTickScope: Set[String] = Set.empty

    private def inTickScope(projectKey: String): Boolean =
      selfTickScope.isEmpty || selfTickScope.contains(projectKey)

    /**
     * 操作尾收口（每个邮箱操作/自驱拍结束时调用，**唯一发布点**）：先补齐惰性加载，
     * 再把当前状态整体发布成读面快照。读面（[[snapshot]]/[[find]]/…）因此完全不进
     * 邮箱、也不存在「读到半个迁移」的形态（快照是不可变值的一次性替换）。
     */
    private[delegate] def settle(): Unit =
      ensureLoadedLocked()
      publish(members, loaded)

    /** 自驱配置（门面 [[MergeQueue.enableSelfTick]] 的落点）。 */
    private[delegate] def configureSelfTick(intervalMs: Long, scope: Set[String]): Unit =
      selfTickMs = 0L // Retired scheduler; preserve legacy ledger without running it.
      selfTickScope = scope
      logger.info(
        s"[merge-queue] self-tick ${
            if selfTickMs > 0
            then s"armed (${selfTickMs}ms${if scope.isEmpty then "" else s", scope=${scope.mkString(",")}"})"
            else "disarmed"
          }"
      )

    /**
     * **最早截止时刻**（actor 据此武装一次 `scheduleOnce`；None = 无需唤醒）。
     *
     * 两个来源，全是「有明确到点」的形态，没有一个是固定拍轮询：
     *   - 某开放成员的 `skipUntilMs` 退避窗（到点就该重新参与轮转）；
     *   - 自驱看护拍（`selfTickMs` > 0 且作用域里还有开放成员时：上一拍 + 间隔）。
     *
     * 截止时刻不变 ⇒ 调用方（QueueActor.armTick）不重臂 ⇒ 唤醒次数 = 截止变化次数。
     */
    private[delegate] def nextDeadline(nowMs: Long, lastTickAt: Long): Option[Long] =
      ensureLoadedLocked()
      val open = members.values.filter(m => m.open && inTickScope(m.projectKey)).toList
      val skips = open.flatMap(_.skipUntilMs).filter(_ > nowMs)
      val care =
        if selfTickMs > 0 && open.nonEmpty then Some(math.max(lastTickAt + selfTickMs, nowMs))
        else None
      (skips ++ care.toList).minOption

    /** 自驱一拍（QueueActor 的 Tick 落点）：只驱动作用域内的 member。 */
    private[delegate] def careTickOnActor(): Unit =
      careTickWith(openFactCheck = true, headSkip = true, scope = selfTickScope)

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
      // P1 投递身份化：收件身份是**持久数据**（重启后照样投得出去）。
      "ownerSessionId" -> m.ownerSessionId.asJson,
      "state" -> m.state.asJson,
      "grantedAt" -> m.grantedAt.asJson,
      "grantedBaseTip" -> m.grantedBaseTip.asJson,
      "closedAt" -> m.closedAt.asJson,
      "closeReason" -> m.closeReason.asJson,
      "lastBlockReason" -> m.lastBlockReason.asJson,
      "blockedRounds" -> m.blockedRounds.asJson,
      "skipUntilMs" -> m.skipUntilMs.asJson,
      "needsHuman" -> m.needsHuman.asJson,
      // 宣告闸批 2026-11-04：属主宣告快照随账本/通道跨重启（宣告是持久事实）。
      "declaredAt" -> m.declaredAt.asJson,
      "declareSource" -> m.declareSource.asJson,
      "declaredTip" -> m.declaredTip.asJson,
      "ownerPresentAtGrant" -> m.ownerPresentAtGrant.asJson
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
        // P1 投递身份化：旧账本缺键 = 空 ⇒ `ownerSession` 回落 delegateId（保守方向：
        // 属主 = 分支持有者本身，投递永不因为「老行没有新字段」而失效）。
        ownerSessionId = c.get[String]("ownerSessionId").toOption.getOrElse(""),
        state = c.get[String]("state").toOption.filter(State.All.contains).getOrElse(State.Waiting),
        grantedAt = c.get[Option[Long]]("grantedAt").toOption.flatten,
        grantedBaseTip = c.get[Option[String]]("grantedBaseTip").toOption.flatten,
        closedAt = c.get[Option[Long]]("closedAt").toOption.flatten,
        closeReason = c.get[Option[String]]("closeReason").toOption.flatten,
        lastBlockReason = c.get[Option[String]]("lastBlockReason").toOption.flatten,
        // R1 退避态（旧账本缺键 = 0/None：升级后存量成员从零开始计数，不误降级）。
        blockedRounds = c.get[Int]("blockedRounds").toOption.getOrElse(0),
        skipUntilMs = c.get[Option[Long]]("skipUntilMs").toOption.flatten,
        needsHuman = c.get[Option[String]]("needsHuman").toOption.flatten,
        // 宣告闸批 2026-11-04：旧账本缺键 = 从未宣告（**保守方向**——存量成员不会
        // 因为「账上有行」就重获自动授窗资格；属主再申报一次即恢复）。
        declaredAt = c.get[Option[Long]]("declaredAt").toOption.flatten,
        declareSource = c.get[Option[String]]("declareSource").toOption.flatten,
        declaredTip = c.get[Option[String]]("declaredTip").toOption.flatten,
        ownerPresentAtGrant = c.get[Option[Boolean]]("ownerPresentAtGrant").toOption.flatten
      )

      end for

    end memberFromJson

    private def persistLocked(): Unit =
      try
        val payload =
          Json.obj("version" -> 1.asJson, "members" -> Json.fromValues(members.values.map(memberJson).toList))
        val tmp = PathUtil.dataRoot / s"${ledgerPath.last}.tmp"
        os.write.over(tmp, payload.noSpaces, createFolders = true)
        os.move(tmp, ledgerPath, replaceExisting = true)
      catch
        case e: Throwable =>
          logger.warn(
            s"merge-ledger persist failed (memory state unaffected): ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
          )

    /** 惰性加载（首次访问；文件缺失/损坏 = 空账起步，坏行跳过不炸）。 */
    private def ensureLoadedLocked(): Unit =
      if !loaded then
        loaded = true
        try
          if os.exists(ledgerPath) then
            io.circe.parser
              .parse(os.read(ledgerPath))
              .toOption
              .flatMap(_.hcursor.downField("members").as[List[Json]].toOption) match
              case None => logger.warn(s"merge-ledger at $ledgerPath unreadable — starting an empty ledger")
              case Some(rows) =>
                val parsed = rows.flatMap(memberFromJson)
                members = parsed.map(m => m.delegateId -> m).toMap
                if parsed.nonEmpty then
                  logger.info(s"merge-ledger loaded: ${parsed.size} member(s) (${parsed.count(_.open)} open)")
        catch
          case e: Throwable =>
            logger.warn(
              s"merge-ledger load failed — starting an empty ledger: ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
            )
        end try
        replayChannelLocked()

    /**
     * write-ahead 通道重放（boot 首次加载尾，恰一次；R5 重启丢队修复的恢复半边）。
     * 通道是账本的**前像**（每次迁移先写通道后写账本）——领先半段并回账本：
     *   - **通道有、账本无**（「ready 前被杀」：通道写盘后、账本写盘前进程死亡）
     *     ⇒ 从通道收养成员入账（B2 形态的机制闭合——不再是「连账本条目都没有」）；
     *   - **账本开放、通道领先**（关账/占窗先落通道，账本写丢）⇒ 并回通道状态
     *     （进程内通道闭包若在则保留——运行期重放防御位）；
     *   - 账本已关账 / 两面一致 ⇒ 零动作（账本是终态权威，通道副本等压缩）。
     * 重放后账本回归唯一读面，通道退回纯 write-ahead 镜像。fail-soft：通道扫描
     * 失败 = 仅按账本起步（与修复前同水位）。
     */
    private def replayChannelLocked(): Unit =
      val entries = MergeQueueChannel.readAll()
      if entries.nonEmpty then
        var adopted = 0
        var ahead = 0
        entries.foreach { e =>
          memberFromJson(e.member).foreach { cm =>
            members.get(cm.delegateId) match
              case None =>
                members = members.updated(cm.delegateId, cm)
                persistLocked()
                record(cm, "channel-replayed", Json.obj("replay" -> "adopted".asJson, "state" -> cm.state.asJson))
                adopted += 1
              case Some(lm) =>
                val channelAhead =
                  (lm.open && State.isTerminal(cm.state)) ||
                    (lm.state == State.Waiting && cm.state == State.TurnGranted)
                if lm.open && channelAhead then
                  val merged = cm.copy(ownerSessionId =
                    if lm.ownerSessionId.nonEmpty then lm.ownerSessionId else cm.ownerSessionId
                  )
                  members = members.updated(cm.delegateId, merged)
                  persistLocked()
                  record(
                    merged,
                    "channel-replayed",
                    Json.obj(
                      "replay" -> "ledger-behind".asJson,
                      "ledgerState" -> lm.state.asJson,
                      "channelState" -> cm.state.asJson
                    )
                  )
                  ahead += 1
                else ()
                end if
          }
        }
        if adopted > 0 || ahead > 0 then
          logger.info(
            s"[merge-queue] write-ahead channel replayed: $adopted member(s) adopted from channel-only records, $ahead state(s) restored ahead of the ledger"
          )
      end if
    end replayChannelLocked

    /** 查询/审计用全量快照（readyAt 升序）。 */
    def snapshot: List[Member] =
      ensureLoadedLocked()
      members.values.toList.sortBy(m => (m.readyAt, m.delegateId))

    /** 开放成员快照（waiting + turn-granted）。 */
    def openSnapshot: List[Member] = snapshot.filter(_.open)

    def find(delegateId: String): Option[Member] =
      ensureLoadedLocked()
      members.get(delegateId)

    /**
     * 合并轮在飞判据（DelegateTerminal mergeRound 判定单点）：该 delegate 的成员
     * 处于 turn-granted（持窗、合并轮指令已注入）。仅 waiting（未放行）不算——
     * 工作轮终态与合并轮终态必须可分。
     */
    def isMergeRound(delegateId: String): Boolean =
      find(delegateId).exists(_.state == State.TurnGranted)

    /**
     * 活性读数（**P1 投递身份化**后的唯一在场判据）：属主会话能不能被征询——由
     * [[OwnerDeliveryPort]] 的身份探针回答（端口未装配 = `None`，**不判**，绝不凭空
     * 判死）。旧的 actor-ref 探针（`installLivenessProbe` / `childAlive`）随内部腿
     * 通道一起删除：队列不再持有任何 ref，也就无从探一个 ref 的死活。
     */
    private def childAlive(m: Member): Boolean =
      OwnerDeliveryPort.presentOf(m.ownerSession).getOrElse(true)

    /**
     * 冻结豁免（错误冻结批 2026-11-01 · 任务书「合并队列里批次冻结时的处置写明」）：
     * 成员属主的 registry 记录带**本批冻结线值**（`delegate-frozen:*`）⇒ 属主不是
     * 「死灭」而是**冻结待唤醒**——窗绝不能被判成 owner-gone 关账（关了就等于把
     * 冻结点上的工作判死，唤醒回来发现合并台账已销账）。冻结期的窗口处置：
     *   - 持窗成员：冻结拍已由 [[DelegateFreeze.requeueMergeMember]] 退回 waiting
     *     （FIFO 位保留）——本拍若仍看到 turn-granted（冻结拍与授窗拍竞态），同样
     *     退回 waiting 而非 owner-gone；
     *   - 在队等待成员：原地留队（唤醒后 [[DelegateFreeze.onWoken]] 的 careTick 自然
     *     重排）。
     * 判据只读 registry 记录（零进程态依赖、跨重启成立）；读失败 ⇒ 不豁免（保守）。
     */
    private def frozenAwaitingWake(delegateId: String): Boolean =
      try
        val rec = DelegateRegistry.find(delegateId)
        rec.exists(r => r.frozenSince.isDefined && DelegateFreeze.isFreezeWire(r.frozenReason.getOrElse("")))
      catch case _: Throwable => false

    // ── 属主失联升级端口（M2 注册器形态：core 只见面，agent 侧装配实现） ──

    /** 通知去重/升级记账（进程内；delegateId → 最近 key + blocked 拍数 + 已升级）。 */
    private final case class NotifyMemo(lastKey: String, rounds: Int, escalated: Boolean)
    private var notifyMemos: Map[String, NotifyMemo] = Map.empty

    /** R1 「已把 needs-human 升级摆到人面前」的成员集（进程内，每成员一次）。 */
    private var needsHumanAnnounced: Set[String] = Set.empty

    // engine 独占（actor 串行调用）
    private def clearNotifyMemoLocked(delegateId: String): Unit =
      notifyMemos = notifyMemos - delegateId

    // engine 独占（actor 串行调用）（R1 退避/降级记账清理单点：关账、授窗成功、人工/冻结路径复位）
    private def clearBackoffMemosLocked(delegateId: String): Unit =
      needsHumanAnnounced = needsHumanAnnounced - delegateId

    /**
     * 阻塞/失败通知入口（去重 + 升级单点）。同 (kind, reason) 只发一次；原因或
     * 状态变化重发；`blocked` 连续 [[EscalateAfterBlockedRounds]] 拍 ⇒ 恰一次
     * escalate（作者面）。放行成功清账（[[clearNotifyMemoLocked]]）。
     */
    private def noteBlockedOrFailed(m: Member, kind: String, reason: String): Unit =
      val key = s"$kind|${reason.take(2000)}"
      val action =
        notifyMemos.get(m.delegateId) match
          case Some(memo) if memo.lastKey == key =>
            if kind != "blocked" then None // 终态通知幂等（重放不重发）
            else
              val rounds = memo.rounds + 1
              if memo.escalated || rounds < EscalateAfterBlockedRounds then
                notifyMemos = notifyMemos.updated(m.delegateId, memo.copy(rounds = rounds))
                None
              else
                notifyMemos = notifyMemos.updated(m.delegateId, memo.copy(rounds = rounds, escalated = true))
                Some("escalate")
          case _ =>
            notifyMemos = notifyMemos.updated(m.delegateId, NotifyMemo(key, 1, escalated = false))
            Some(kind)
      action.foreach(actual => BlockNotifyPort.deliver(m, actual, reason))
    end noteBlockedOrFailed

    // ── 面板/行投影 ───────────────────────────────────────────────────────

    def queueViewOf(delegateId: String): Option[QueueView] =
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
              readyAt = me.readyAt,
              blockedRounds = me.blockedRounds,
              skipUntilMs = me.skipUntilMs,
              needsHuman = me.needsHuman
            )
          )
        end if
      }

    end queueViewOf

    // engine 独占（actor 串行调用）；本项目开放成员按 FIFO 序
    private def openSortedLocked(projectKey: String): List[Member] =
      members.values.toList.filter(m => m.projectKey == projectKey && m.open).sortBy(m => (m.readyAt, m.delegateId))

    // engine 独占（actor 串行调用）
    private def holderLocked(projectKey: String): Option[Member] =
      openSortedLocked(projectKey).find(_.state == State.TurnGranted)

    // ── 审计行（fail-soft：账本失败绝不阻断队列） ─────────────────────────

    // ── 合并道闸（投递可靠性批 2026-10-29 · 病灶三型收口）──────────────────
    //
    // 病灶（作者令 2026-10-29 23:44/23:55/23:57/00:13 四段范围）：
    //   ① 属主死在道中（孤儿 MERGE_HEAD）——后续批 merge 撞 exit 128；
    //   ② 健康占道被误关账——道忙（他批合法占道解冲突）被机械验收判 REFUSED 关账
    //     failed（今晚 a464fdc0 / b3020083 两例，健康分支逼人工重派）；
    //   ③ 窗口完成不销账——合并 commit 已落 main、属主死灭，窗仍 turn-granted。
    //
    // 收口（单一判据 = MERGE_HEAD 读数 × 属主活性）：
    //   - 授窗前（[[grant]]）：道闸 [[laneVerdictFor]]——占道即不授（等待者
    //     requeueBlocked，#943 必通知），绝不把合并轮放进被占的道；
    //   - 合并轮结算（DelegateTerminal.onCompleted × [[laneVerdictFor]]）：拒绝原
    //     因 = 道忙 ⇒ [[Outcome.RequeueForLane]]（保成员资格、会话停靠保活、道净
    //     后自动重排）——只有「己方残留/道净真拒绝」才关账 failed；
    //   - 属主死亡事件（[[markOwnerGone]]）：残留 MERGE_HEAD == 本成员分支 tip ⇒
    //     `git merge --abort`（孤儿收场，作者裁定 23:55「倾向=abort 回滚」）；
    //   - 每拍看护（[[careTick]]）：持窗成员先账实核对（合并 commit 已落 = 立即
    //     销账 + pump 下一位，不依赖属主下一拍主动还窗——病灶③），再持窗尸体判死
    //     （死 ⇒ owner-gone，abort 随 markOwnerGone 收场——病灶①）。

    /**
     * git × 账本读数面（grant 与合并轮结算共用单点）：读 baseWs MERGE_HEAD + 本成员
     * 分支 tip + 占道者归属（开放成员分支 tip 命中 MERGE_HEAD；活性 = 内部腿探针，
     * 无内部腿 = 不活——重启后通道丢失的诚实面）。读数任何一步不可得 = Foreign
     * （fail-open：不可确证绝不 abort，等待+通知）。
     */
    private[delegate] def laneVerdictFor(m: Member): LaneVerdict =
      val ws = Try(os.Path(m.baseWs)).toOption
      val mh = ws.filter(os.exists(_)).flatMap(GitWorktrees.mergeHead)
      val myTip = ws.flatMap(w => GitWorktrees.resolveRef(w, m.branch))
      val occupier: Option[(String, Boolean)] = mh.flatMap { head =>
        {
          ensureLoadedLocked()
          members.values.find(mm => mm.open && mm.delegateId != m.delegateId && branchTipIs(ws, mm.branch, head))
        }.map { occupier =>
          val alive = ownerPresent(occupier)
          (occupier.delegateId, alive)
        }
      }
      classifyLane(mh, myTip, occupier)
    end laneVerdictFor

    // ── 属主宣告闸 + 合并前健康闸（缺陷批 2026-11-04）──────────────────────
    //
    // 病灶（作者报障「合并队列会把『明确压着不合的半成品分支』自动合进 main」，
    // 交付实证两例）：本件的**授予判据**原来只有「账实（已并）/ 道闸 / 净空闸 /
    // 通道可达」——**没有一条问过「这批到底完成没完成」**。于是与完成无关的形态
    // 都能拿到合并窗：
    //   - 「分支上有提交」（[[MergeEnqueueWatch]] 的 ahead>0、[[DelegateTerminal]]
    //     的 queueFlip）；
    //   - 「write-ahead 通道有未关账镜像」（boot 重放收养 + [[preserveAfterRestart]]）；
    //   - 「属主失联」反而**抑制关账**（notifyTurn 的 Deferred：membership preserved
    //     (owner-gone suppressed)）——owner 不在场 ⇒ 没人能被征询 ⇒ 队列按自己的
    //     账把它继续当「可合」推进。
    //
    // 收口（作者语义，四条）：**只有属主明确宣告「完成且可合」的分支才允许拿合并窗**，
    // 判据 = 可机械检查的宣告账（[[MergeDeclaration]]；写它的唯一动作 = 属主的
    // `delegate_report(category="merge")`）——属主不在场就没人能宣告，失联成员因此
    // **结构上拿不到窗**（默认不与，保留成员资格 + 可见告警，绝不自动合）；
    // **合并前最小健康闸**（[[MergeHealthGate]]：半成品/关键测试红 = 不授窗并报原因）；
    // 每次授窗留**可回溯审计行**（判据 + 属主在场 + 宣告来源 + 分支 tip）。

    /** 拒绝类别（park 原因分类；面板 + 审计行 + 父通知一致）。 */
    object ParkKind:
      /** 无属主完成宣告（「有提交」被当成「已完成」的旧判据）。 */
      val Undeclared: String = "undeclared"

      /** 健康闸拒（冲突标记 / 合不干净 / 关键测试红）。 */
      val Health: String = "health"
    end ParkKind

    /** 某 delegate 的属主宣告（注入优先，缺省读持久宣告账；fail-soft = None）。 */
    private[delegate] def declarationOf(delegateId: String): Option[MergeDeclaration.Decl] =
      declarationLookup.get() match
        case Some(f) =>
          try f(delegateId)
          catch case _: Throwable => None
        case None => MergeDeclaration.find(delegateId)

    /** 成员面的宣告读数（宣告账优先；账本快照兜底——账本跨重启更早，账更新）。 */
    private def declaredOf(m: Member): Option[MergeDeclaration.Decl] =
      declarationOf(m.delegateId).orElse(
        m.declaredAt.map(at =>
          MergeDeclaration.Decl(
            m.delegateId,
            at,
            m.declareSource.getOrElse(MergeDeclaration.Source.ReportMerge),
            Some(m.branch),
            Some(m.baseBranch),
            m.declaredTip
          )
        )
      )

    /** 内容快照里的属主宣告（不读盘——供 [[reviveOnChannelLocked]] 等锁内腿用）。 */
    private def hasDeclarationLocked(m: Member): Boolean =
      m.declaredAt.isDefined || m.declareSource.isDefined

    /**
     * 属主宣告落账 + 成员面同步（**申报腿单点**）。调用方只有属主来源：
     * [[MergeEnqueue.ensurePending]]（`delegate_report(category="merge")`）与终态腿重放
     * 同一自申报。引擎推断来源由 [[MergeDeclaration.write]] 自己拒（返回 None）。
     *
     * 副作用顺序：写宣告账（持久事实）→ 成员快照刷新（declaredAt/source/tip）→
     * 清该成员的 park（新宣告 = 条件已变，立即恢复轮转）→ 审计行 `declared` →
     * 本拍放行尝试（[[pump]]）。
     */
    def declareOwnership(
      delegateId: String,
      source: String,
      nowMs: Long = System.currentTimeMillis()
    ): Option[MergeDeclaration.Decl] =
      val mOpt =
        ensureLoadedLocked()
        members.get(delegateId).filter(_.open)
      val tip = mOpt.flatMap(m => Try(os.Path(m.baseWs)).toOption.flatMap(ws => GitWorktrees.resolveRef(ws, m.branch)))
      val decl = MergeDeclaration.write(delegateId, source, mOpt.map(_.branch), mOpt.map(_.baseBranch), tip, nowMs)
      decl.foreach { d =>
        val stamped =
          members.get(delegateId).filter(_.open).map { cur =>
            val refreshed = cur.copy(
              declaredAt = Some(d.at),
              declareSource = Some(d.source),
              declaredTip = d.tip,
              blockedRounds = 0,
              skipUntilMs = None,
              needsHuman = None,
              lastBlockReason = None
            )
            clearBackoffMemosLocked(delegateId)
            MergeQueueChannel.sync(refreshed.delegateId, refreshed.state, memberJson(refreshed))
            members = members.updated(delegateId, refreshed)
            persistLocked()
            record(
              refreshed,
              "declared",
              Json.obj("source" -> d.source.asJson, "tip" -> d.tip.fold("".asJson)(_.take(12).asJson))
            )
            refreshed
          }
        stamped.foreach { m =>
          logger.info(
            s"[merge-queue] owner declaration recorded for '${m.delegateId}' (source=${d.source}) — the member may now be granted a merge turn"
          )
          pump(m.projectKey)
        }
      }
      decl
    end declareOwnership

    /**
     * 属主此刻**能否被征询**（授窗前读数；与 [[notifyTurn]] 同一读数面，不引入新猜测）：
     *   - 内部腿 actor 还活着 = 在场；
     *   - 外部腿闭包在（续聊/mailbox 通道）= 在场（通道本身可达属主）；
     *   - **两条腿都缺席**（重启后闭包随进程消亡的常态）/ 内部腿 actor 已死 = **不在场**。
     *
     * 不在场 ⇒ [[grant]] ② 直接 park（默认不与）：没人能被征询时，队列不许替人决定
     * 「这批可以合」——今天的事故正是反过来的（失联的被抑制关账后继续被放行）。
     */
    private[delegate] def ownerPresent(m: Member): Boolean =
      OwnerDeliveryPort.presentOf(m.ownerSession).getOrElse(false)

    /**
     * 授窗前**硬 park**（三闸共用出口）：窗让回 waiting + `needsHuman`（= 队列停止对该
     * 成员自动授窗；面板标红）+ 审计行 `grant-refused` + **必通知**（BlockNotifyPort，
     * kind = `<parkKind>-refused`，原因全文不截断）+ 让位续放（本拍把窗交给下一个可
     * 放行者；park 的成员已被 [[grantEligibleLocked]] 排除，不会同拍再被捞回）。
     *
     * 与 [[requeueBlocked]] 的边界：那条是「闸暂时过不去 ⇒ 逐拍重试」，本条是
     * **判据不成立 ⇒ 重试没有意义**——条件只能由属主/人改变（新宣告、修分支、人合并）。
     */
    private def parkRefused(
      reserved: Member,
      kind: String,
      reason: String,
      headSkip: Boolean,
      depth: Int,
      attempted: Set[String]
    ): Unit =
      val parked =
        members.get(reserved.delegateId) match
          case Some(cur) if cur.state == State.TurnGranted =>
            val p = cur.copy(
              state = State.Waiting,
              grantedAt = None,
              grantedBaseTip = None,
              lastBlockReason = Some(reason.take(2000)),
              needsHuman = Some(reason.take(2000)),
              skipUntilMs = None
            )
            MergeQueueChannel.sync(p.delegateId, p.state, memberJson(p)) // write-ahead（R5）
            members = members.updated(cur.delegateId, p)
            persistLocked()
            record(
              p,
              "grant-refused",
              Json.obj(
                "parkKind" -> kind.asJson,
                "reason" -> reason.take(2000).asJson,
                "declared" -> cur.declaredAt.isDefined.asJson,
                "declareSource" -> cur.declareSource.fold("".asJson)(_.asJson)
              )
            )
            Some(p)
          case _ => None
      parked.foreach { p =>
        noteBlockedOrFailed(p, s"$kind-refused", reason)
        logger.warn(s"[merge-queue] grant REFUSED for '${p.delegateId}' ($kind): ${reason.take(300)}")
        if headSkip && depth < MaxGrantChainDepth then pumpWith(p.projectKey, headSkip, depth + 1, attempted)
      }
    end parkRefused

    // ── 入队（waiting） ───────────────────────────────────────────────────

    /**
     * 幂等入队腿的合并面（[[enqueue]] / [[enqueuePendingIO]] 共用）：通道腿**新优先**
     * （复活后的 leg 必须留下），宣告快照**只增不减**（宣告是持久事实——重入队带不带
     * 宣告都不许把已有的宣告抹掉）。
     */
    private def mergeOnReenqueue(existing: Member, entry: Member): Member =
      existing.copy(
        // P1 投递身份化：新的收件身份优先（续活/重派后身份可能变），旧的保留兜底。
        ownerSessionId = if entry.ownerSessionId.trim.nonEmpty then entry.ownerSessionId else existing.ownerSessionId,
        declaredAt = existing.declaredAt.orElse(entry.declaredAt),
        declareSource = existing.declareSource.orElse(entry.declareSource),
        declaredTip = existing.declaredTip.orElse(entry.declaredTip)
      )

    /**
     * ready-to-merge 入账并尝试放行。FIFO 排序键 = `readyAt`（完成时间）——后到不得
     * 插队；排序在**本单点**（spec 判红锚）。锁定区只做账本状态机；放行动作
     * （git 核对 + 通知）在锁外执行。
     */
    def enqueue(entry: Member): Unit = {
      ensureLoadedLocked()
      members.get(entry.delegateId) match
        case Some(existing) if existing.open =>
          // 幂等：已在账的开放成员不重复入账（重复完成/钩子重放），只刷新投递身份。
          val channelMerged = mergeOnReenqueue(existing, entry)
          // R1 自愈腿：P1 身份化后**不存在「通道缺席」**（身份恒有——缺省 = 属主
          // 会话本身；投递走落盘邮箱），所以「通道重新出现」退化为「属主又发生了
          // 一次入队事实」= 条件已变的硬信号。降级/park 的解除仍由宣告闸把守。
          val (refreshed, revived) = reviveOnChannelLocked(existing, channelMerged, channelRestored = true)
          members = members.updated(entry.delegateId, refreshed)
          if revived then
            MergeQueueChannel.sync(refreshed.delegateId, refreshed.state, memberJson(refreshed))
            persistLocked()
            record(refreshed, "channel-restored-revive", Json.obj("state" -> refreshed.state.asJson))
          record(refreshed, "enqueued-duplicate", Json.obj("state" -> refreshed.state.asJson))
          if refreshed.state == State.Waiting then Some(refreshed) else None
        case _ =>
          // write-ahead（R5）：成员镜像先落持久通道——进程死在内存/账本写盘前，
          // boot 重放仍能从通道收养（「ready 前被杀连账本条目都没有」的窗口闭合）。
          MergeQueueChannel.sync(entry.delegateId, entry.state, memberJson(entry))
          members = members.updated(entry.delegateId, entry)
          persistLocked()
          record(
            entry,
            "enqueued",
            Json.obj("readyAt" -> entry.readyAt.asJson, "queueDepth" -> openSortedLocked(entry.projectKey).size.asJson)
          )
          Some(entry)
      end match
    }.foreach { m => if m.state == State.Waiting then pump(m.projectKey) }

    /**
     * 预记账入队（merge-fifo 批 2026-11-03 · 作者报障「两个会话都已经报了 merge，
     * 为什么还没收到通知」）：只记成员、**不 pump**。
     *
     * 调用点 = `delegate_report(category="merge")` 的申报腿（[[MergeEnqueue.ensurePending]]）
     * ——此刻属主回合**尚未收尾**，放行腿不归申报点：回合收尾的重入队
     * （[[enqueue]]，状态仍 waiting ⇒ 落 pump）与扫描拍（[[careTick]] 每项目补一拍
     * pump）负责把它推到放行。这样「终态腿没跑成」（重启打断 / 判定降级 / 提前收尾）
     * 最坏也只退化成「成员停在 waiting 等下一拍」，**绝不零台账行**。
     *
     * 幂等：已在账的开放成员只刷新通道（readyAt 不覆写——FIFO 位次不因重复申报
     * 而跳动），审计行 `enqueued-pending-duplicate`。
     */
    def enqueuePending(entry: Member): Unit =
      ensureLoadedLocked()
      members.get(entry.delegateId) match
        case Some(existing) if existing.open =>
          val channelMerged = mergeOnReenqueue(existing, entry)
          // R1 自愈腿（见 reviveOnChannelLocked）：申报腿带来一次新的入队事实 = 条件已变。
          val (refreshed, revived) = reviveOnChannelLocked(existing, channelMerged, channelRestored = true)
          members = members.updated(entry.delegateId, refreshed)
          if revived then
            MergeQueueChannel.sync(refreshed.delegateId, refreshed.state, memberJson(refreshed))
            persistLocked()
            record(refreshed, "channel-restored-revive", Json.obj("state" -> refreshed.state.asJson))
          record(refreshed, "enqueued-pending-duplicate", Json.obj("state" -> refreshed.state.asJson))
        case _ =>
          // write-ahead（R5）：成员镜像先落持久通道——进程死在内存/账本写盘前，
          // boot 重放仍能从通道收养。
          MergeQueueChannel.sync(entry.delegateId, entry.state, memberJson(entry))
          members = members.updated(entry.delegateId, entry)
          persistLocked()
          record(
            entry,
            "enqueued-pending",
            Json.obj("readyAt" -> entry.readyAt.asJson, "queueDepth" -> openSortedLocked(entry.projectKey).size.asJson)
          )

      end match

    end enqueuePending

    /**
     * 核实入队（merge-fifo 批 2026-11-03 · 入队强保证的终态腿）：enqueue（含 pump）
     * → 核账本 `find(id).exists(_.open)`；**false ⇒ `enqueue-failed` 必通知**
     * （[[BlockNotifyPort]]，父/根会话收到可行动文案：入队没落地 ⇒ 这条分支不会被排到，
     * 手工合并或重派）。**绝不抛**——入队腿的任何故障都不得拖垮终态序列（属主的 Release
     * 照常投递），故障只转成「核实失败 + 通知」。
     *
     * 两形 false 都在文案里点名（诚实面）：① 账本压根没落行（异常/进队路失败）；
     * ② 行落了但**开放态**不在（入队腿内被立即关账——merged / failed / owner-gone，
     * 该情形各自的关账通知由既有三端口承担，本条只是「没有开放条目」的补票）。
     */
    def enqueueVerified(entry: Member): Boolean =
      val ok =
        try
          enqueue(entry)
          try find(entry.delegateId).exists(_.open)
          catch case _: Throwable => false
        catch
          case e: Throwable =>
            logger.warn(
              s"[merge-queue] enqueue FAILED for '${entry.delegateId}': ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
            )
            false
      if !ok then
        // 判词分两形（诚实面）：① 账本压根没落行（异常/进队路失败）；② 行落了但**开放态
        // 不在**（入队腿内被立即关账——merged / failed / owner-gone）。后者今天被读成
        // 「入队没落地」，与事实相反（OWNER-GONE 误判批 2026-11-06）：入队后 **回读**
        // 一次账本，把真实状态与关账原因随行，判词不再靠猜。
        val readBack =
          try
            find(entry.delegateId)
              .map(m => s"state=${m.state}" + m.closeReason.fold("")(r => s"; closeReason=${r.take(200)}"))
              .getOrElse("no ledger row for this delegate")
          catch case _: Throwable => "ledger unreadable"
        BlockNotifyPort.deliver(
          entry,
          "enqueue-failed",
          s"merge-queue membership verification failed: no OPEN ledger entry for '${entry.delegateId}' after enqueue " +
            s"(branch '${entry.branch}' → '${entry.baseBranch}'). Ledger read-back: $readBack. " +
            "The merge turn for this delegate is NOT scheduled: merge the branch by hand or re-dispatch the task " +
            "(branch and worktree preserved)."
        )
      end if
      ok
    end enqueueVerified

    // ── R1 队头跳过 + 退避（队头不再堵死全队批 2026-11-04 · 审计 R1）──────
    //
    // 病灶（停顿审计 20261104_164800 §4-R1）：`Deferred`（无投递通道）与持续被拒
    // 的成员**照样占队头**——`requeueBlocked` 保 `readyAt`（FIFO 键不变）⇒ 下一拍
    // 还是它 ⇒ 整条队零放行（79c40e17 220 拍 / c102e6d0 同形态）。四处都读这条。
    //
    // 收口：**跳过 + 退避重试 + 上界降级**——被堵的成员让开队头（`skipUntilMs` 窗
    // 内不参与轮转），后面能走的先走；窗到期再试；连续 [[MaxBlockedRounds]] 拍都不
    // 行 ⇒ 置 `needsHuman`（显式「请人工处理」态，队列不再自动授窗，只等账实核对
    // 或人工）。**绝不无限重试**，也绝不静默：每拍一次 `grant-blocked` 审计行 +
    // 首次/变化必通知（既有 #943 面），降级时一条 escalate。
    //
    // **两条上界**（不足批实测修正）：**正在饿死别人**的死结 8 拍到顶即降级；**孤身
    // 死结**（队列里没有别的可放行者）走 [[SoloMaxBlockedRounds]]（3×）绝对封顶——
    // 给既有「N 拍同因升级作者」阶梯（[[EscalateAfterBlockedRounds]]）留完整跑道
    // （实测：把孤身腿也压到 8 拍会让 `MergeQueueSpec` ⑬ 的升级拍点漂移）。
    //
    // 参数现读 prop（`BgGateWaitTimeoutMs` 同款先例）⇒ spec 可即时翻转；生产无
    // 在线翻转路由（改值需重启宿主）。

    /** 本拍是否可参与放行轮转（R1 跳过判据）：非降级态 ∧ 退避窗已过。 */
    private def grantEligibleLocked(m: Member, nowMs: Long): Boolean =
      m.needsHuman.isEmpty && m.skipUntilMs.forall(_ <= nowMs)

    // engine 独占（actor 串行调用）：本项目里**除自己以外**还有可放行的等待成员（R1 让位的意义判据）。
    // 单成员队列里让位 = 空转一拍（谁也不走、升级阶梯还倒退），所以只有「后面真有人
    // 能走」时才让位——这正是审计 §4-R1「让后面能走的先走」的原文语义。
    private def otherEligibleWaitingLocked(projectKey: String, excludeId: String, nowMs: Long): Boolean =
      members.values.exists(m =>
        m.projectKey == projectKey && m.open && m.state == State.Waiting && m.delegateId != excludeId &&
          grantEligibleLocked(m, nowMs)
      )

    /**
     * **R1 自愈腿**（降级不是终态）——成员的投递通道**重新出现**（属主回合收尾后重入队
     * 带 leg / 监督者 respawn 换绑 / 续聊腿重新安装）⇒ 队列终于读到一个「条件已变」的
     * 硬信号 ⇒ 清退避窗与 `needsHuman` 降级，本拍即可重新轮转。
     *
     * 为什么必须有：`no-channel` 的上界降级是「别在死结上空转」，不是「永久封存」。
     * 没有这条自愈腿，`79c40e17` 形态会被自己的上界钉死——属主回合收尾、leg 回来了，
     * 队列却再也不授窗（只能等人工/账实核对），把「不许无限重试」偷换成「一次降级永不
     * 翻身」。通道是否在场是队列**能自己读到**的判据（[[notifyTurn]] 同一读数），
     * 所以这条自愈腿不给机制引入新的猜测面。
     *
     * @return 该成员是否被复活（调用方据此落审计行/写盘）。
     */
    private def reviveOnChannelLocked(previous: Member, merged: Member, channelRestored: Boolean): (Member, Boolean) =
      val degraded = previous.needsHuman.isDefined || previous.skipUntilMs.isDefined || previous.blockedRounds > 0
      // 宣告闸批（2026-11-04）：**没有属主宣告的成员，通道复活不解 park**——「没宣告」
      // 是判据不成立（重试无意义），不是通道问题；只有新宣告（[[declareOwnership]]）
      // 或人工处置能解。否则一条重入队带来的通道就会把 undeclared/health 的 park
      // 一键清掉，等于把闸绕过去。
      if !channelRestored || !degraded || !hasDeclarationLocked(previous) then (merged, false)
      else
        clearBackoffMemosLocked(previous.delegateId)
        // 以**合并后**的成员为底（新投递身份必须留在账上——复活后的第一拍就要用它投递）。
        (merged.copy(blockedRounds = 0, skipUntilMs = None, needsHuman = None), true)

    // ── 放行（waiting → turn-granted；通知即放行） ────────────────────────

    /**
     * 一拍放行尝试（本 projectKey）。窗被持（已有 turn-granted）⇒ 零动作；否则在
     * waiting 成员里按 FIFO 序挑**第一个可放行的**（R1：退避窗内 / 已降级 needs-human
     * 的成员被跳过——队头堵不动时后面能走的先走）：账实核对（已并 → 关账，永不
     * 通知合并）→ 道闸 → 净空闸 → 通知放行（通道失败 → owner-gone）。
     * 锁内只做账本迁移；git 面与通道注入全部锁外。
     */
    def pump(projectKey: String): Unit = pumpWith(projectKey, headSkip = true)

    /**
     * `headSkip = false` = **R1 之前的旧语义**（只挑 FIFO 队头、不看退避/降级）——
     * spec 反向验红缝（同一夹具下必须复现「队头堵死全队」），生产恒为 true。
     *
     * `attempted` = **本拍内已经试过的成员**（一次 pump 链的判重集）：每个成员在一拍
     * 里最多占窗**一次**——否则「让位后立刻续放」会在同拍里把这个刚被堵的成员再捞
     * 回来（退避窗极短时尤其明显）。跨拍不保留：下一拍是新局面。
     */
    private[delegate] def pumpWith(
      projectKey: String,
      headSkip: Boolean,
      depth: Int = 0,
      attempted: Set[String] = Set.empty
    ): Unit =
      () // Retired: only the project's authorized merger may integrate workspaces.

    /**
     * 已占窗成员的放行腿：账实核对 → 净空闸 → 通知（任一关账腿释放窗并续放下一位）。
     * `depth` = R1 本拍内改授链深度（`headSkip` 生效时被堵成员让位，同一拍把窗交给
     * 下一个可放行者；[[MaxGrantChainDepth]] 封顶）；`attempted` = 本拍已试成员。
     *
     * P1 身份化：原 `channelPremised`（占窗前的通道前提读数）参数**已删除**——它存在的
     * 前提是「投递靠进程内闭包」，闭包没了就没有「前提」可读。
     */
    private def grant(
      reserved: Member,
      headSkip: Boolean = true,
      depth: Int = 0,
      attempted: Set[String] = Set.empty
    ): Unit =
      // ① 账实核对（先于一切——已并成员直接关账，不再通知不再重试）。
      alreadyMerged(reserved) match
        case true =>
          closeMerged(
            reserved.delegateId,
            via = "fact-check",
            closeReason = Some(
              "branch already merged into base — OBSERVED at grant time by a read-only fact-check " +
                "(git merge-base --is-ancestor). The queue did not execute this merge (it never runs git merge); " +
                "the merge was made by the owner's round or by hand."
            ),
            cleanup = true
          )
          notifyAlreadyLanded(reserved) // 属主还 dock 着 → 立即收尾通知（delegate 纪律 #6）
          pump(reserved.projectKey)
          return
        case false => ()
      end match
      // ② 属主宣告闸（本批核心）：**只有属主明确宣告「完成且可合」的分支才允许拿合并窗**。
      // 「分支上有提交」「会话 idle 够久」「write-ahead 通道有镜像」都不是完成宣告。
      //
      // 与「属主失联 ⇒ 默认不与」的关系（作者令 2）：宣告只能由属主自己写，**属主不在场
      // 就没人能宣告** ⇒ 失联成员结构上拿不到窗（不需要单独判在场——那条判据会把「属主
      // 回合尚未收尾 = 暂时无 leg」的正当形态误判成失联，正是本批要消灭的「用代理信号代替
      // 判据」）。在场与否只作为**审计读数**记进 granted 行（作者令 4）。
      val declaration =
        MergeDeclaration.gate(reserved.delegateId, declaredOf(reserved), reserved.branch, reserved.baseBranch) match
          case Left(reason) =>
            parkRefused(reserved, ParkKind.Undeclared, reason, headSkip, depth, attempted)
            return
          case Right(d) => d
      // ③ 合并前最小健康闸（作者令 3）：半成品（冲突标记 / 合不干净 / 关键测试红）不授窗，
      // 原因随行（面板 + 父通知）。Skipped = 闸面读不到 ⇒ 放行（fail-soft，与队列既有
      // 纪律一致；只留一条审计行）。
      MergeHealthGate.check(reserved) match
        case MergeHealthGate.Verdict.Refused(reason) =>
          parkRefused(reserved, ParkKind.Health, reason, headSkip, depth, attempted)
          return
        case MergeHealthGate.Verdict.Skipped(reason) =>
          record(reserved, "health-skipped", Json.obj("reason" -> reason.take(300).asJson))
        case MergeHealthGate.Verdict.Ok => ()
      // ④ 合并道闸（病灶①②收口，2026-10-29）：MERGE_HEAD 在场 = 道被占——占道
      // 即不授（等待者 requeueBlocked 留队必通知，道净后 careTick 重排）；绝不把
      // 合并轮放进被占的道（今晚 a464fdc0/b3020083 撞 exit 128 被误关账的预防位）。
      // 自家残留（OwnLeftover）放行——合并 brief 教学 abort+重试，属内自愈。
      val lane = laneVerdictFor(reserved)
      if !laneGrants(lane) then
        requeueBlocked(reserved, laneBlockReason(lane), BlockClass.Gate, headSkip, depth, attempted)
        return
      // ⑤ 净空闸（引擎侧 base 净空维护——白名单工件还原/收编；非合并执行）。
      baseReady(reserved) match
        case Left(blockReason) =>
          requeueBlocked(reserved, blockReason, BlockClass.Gate, headSkip, depth, attempted)
          return
        case Right(baseTip) => ()
      end match
      // ⑥ 放行 = 通知（属主自己执行合并并回报）。
      notifyTurn(reserved) match
        case NotifyResult.Delivered =>
          val tip: Option[String] = baseTipOf(reserved)
          // 授窗后的落账（原「锁内尾段」）：不是一个裸块语句，而是显式取值——
          // 否则 `EOF调用` 紧邻 `{` 会被解析成第二个参数表。
          val stampedOk =
            clearNotifyMemoLocked(reserved.delegateId) // 放行成功：阻塞计数/去重账清零
            members.get(reserved.delegateId).foreach { cur =>
              val present = ownerPresent(reserved)
              val stamped = cur.copy(
                grantedBaseTip = tip,
                blockedRounds = 0,
                skipUntilMs = None,
                needsHuman = None,
                // 宣告闸批：宣告快照落到成员面（判据来源可回溯；重入队/重启后仍在账）。
                declaredAt = cur.declaredAt.orElse(Some(declaration.at)),
                declareSource = cur.declareSource.orElse(Some(declaration.source)),
                declaredTip = cur.declaredTip.orElse(declaration.tip),
                // 作者令 4：授窗时**属主是否在场**是审计事实（不在场 = 靠续聊/CLI resume
                // 通道投递；在场 = 活体 actor 注入）。
                ownerPresentAtGrant = Some(present)
              )
              MergeQueueChannel.sync(stamped.delegateId, stamped.state, memberJson(stamped)) // write-ahead（R5）
              members = members.updated(cur.delegateId, stamped)
              persistLocked()
              // 可回溯记录（作者令 4）：**哪条判据**放行的（属主宣告）、宣告来源与时刻、
              // 属主当时是否在场、放行时分支 tip 与宣告 tip（分支有没有在宣告后长过）。
              val branchTip =
                Try(os.Path(stamped.baseWs)).toOption.flatMap(ws => GitWorktrees.resolveRef(ws, stamped.branch))
              record(
                stamped,
                "granted",
                Json.obj(
                  "baseTip" -> tip.fold("".asJson)(_.take(12).asJson),
                  "viaChannel" -> reserved.channelKind.asJson,
                  "criterion" -> "owner-declaration".asJson,
                  "declaredAt" -> stamped.declaredAt.map(_.asJson).getOrElse("".asJson),
                  "declareSource" -> stamped.declareSource.fold("".asJson)(_.asJson),
                  "declaredTip" -> stamped.declaredTip.fold("".asJson)(_.take(12).asJson),
                  "branchTip" -> branchTip.fold("".asJson)(_.take(12).asJson),
                  "ownerPresent" -> present.asJson,
                  "observedAt" -> System.currentTimeMillis().asJson
                )
              )
            }
          end stampedOk
          logger.info(
            s"[merge-queue] turn granted to '${reserved.delegateId}' (branch '${reserved.branch}' → '${reserved.baseBranch}', window holder = single)"
          )
        case NotifyResult.Failed(err) =>
          markOwnerGone(reserved.delegateId, s"merge-turn notification undeliverable: ${err.take(200)}")
          pump(reserved.projectKey)
      end match
    end grant

    /** 账实核对单点：分支头已是基分支祖先 ⟺ 已并入（今晚 should-not-happen 的消除闸）。 */
    private def alreadyMerged(m: Member): Boolean = factCheck(m).contains(true)

    /**
     * 账实核对**三态**（R2 批 2026-11-04 引入；`alreadyMerged` 是它的保守投影）：
     *   - `Some(true)`  分支已是基分支祖先（`git merge-base --is-ancestor` exit 0）；
     *   - `Some(false)` 未并入（exit 1，可信的否定）；
     *   - `None`        **读数不可得**（基工作区不是 git 仓库 / git 报错 / ref 不可读）。
     *
     * 只读：一条 `merge-base --is-ancestor`，零写操作、零工作区触碰（红线：核对
     * 失败时**绝不**据此关账——见 [[factCheckOpenMembers]] 的 fail-soft 分支）。
     */
    private[delegate] def factCheck(m: Member): Option[Boolean] =
      Try(os.Path(m.baseWs)).toOption.filter(GitWorktrees.isGitRepo) match
        case None => None // 读数不可得 = 不判（fail-open，交给回合终态核对）
        case Some(ws) => GitWorktrees.isAncestor(ws, m.branch, m.baseBranch)

    /** base tip（放行通知携带；不可读 = None，brief 用 "unknown"）。 */
    private def baseTipOf(m: Member): Option[String] =
      Try(os.Path(m.baseWs)).toOption.flatMap(ws => GitWorktrees.resolveRef(ws, m.baseBranch))

    /**
     * 净空闸（#943 起 **fail-soft 回归**——「合并队列回纯排队+通知」）：
     *   - 基工作区不可读（非 git / 路径坏）⇒ 放行（历史行为——delegate 的合并回合
     *     会诚实拒绝并进 failed，面板可见）；
     *   - 树脏 ⇒ 先过 [[MergeArtifactCare.hygiene]]（还原/收编/判性：同 blob
     *     复本清理、路径外无关件移隔离区）——**余脏不再 fail-closed 拦截**：只
     *     WARN 放行，delegate 的 `git merge` 诚实撞脏进 failed（原因可见关账，
     *     队列不再留队停靠、不再静默）；
     *   - 基分支非检出分支 ⇒ Left（**方向闸保留**——绝不把合并轮放进错误座位；
     *     该腿走 [[requeueBlocked]] 留队并**必通知**，绝不静默）。
     * Right(baseTip) = 可放行。
     */
    private def baseReady(m: Member): Either[String, Option[String]] =
      Try(os.Path(m.baseWs)).toOption.filter(os.exists(_)) match
        case None => Right(baseTipOf(m))
        case Some(ws) if !GitWorktrees.isGitRepo(ws) => Right(baseTipOf(m))
        case Some(ws) =>
          try
            val ctx = Some(MergeArtifactCare.CareContext(m.delegateId, m.branch, m.baseBranch, m.projectKey))
            val report = MergeArtifactCare.hygiene(ws, ctx)
            if report.restored.nonEmpty || report.absorbed.nonEmpty || report.triagedRemoved.nonEmpty || report.triagedQuarantined.nonEmpty
            then
              record(
                m,
                "artifact-hygiene",
                Json.obj(
                  "restored" -> report.restored.asJson,
                  "absorbed" -> report.absorbed.asJson,
                  "absorbCommit" -> report.absorbCommit.map(_.take(12)).asJson,
                  "triagedRemoved" -> report.triagedRemoved.asJson,
                  "triagedQuarantined" -> report.triagedQuarantined.asJson,
                  "quarantineDir" -> report.quarantineDir.map(_.take(300)).asJson,
                  "remainingDirty" -> report.remainingDirty.asJson
                )
              )
            end if
            // 余脏只 WARN 不拦（fail-soft 回归）：面板/日志可见全文，合并回合诚实面。
            val remaining =
              try MergeArtifactCare.dirtyPaths(ws, cap = 20)
              catch case _: Throwable => List("unreadable")
            if remaining.nonEmpty then
              logger.warn(
                s"[merge-queue] base workspace still dirty after hygiene — GRANTING anyway (fail-soft; the merge round will honestly refuse if the dirt bites): ${remaining.mkString(", ")}"
              )
            val current =
              try GitWorktrees.currentBranch(ws)
              catch case _: Throwable => None
            if current.isDefined && current != Some(m.baseBranch) then
              Left(
                s"base branch '${m.baseBranch}' is not the checked-out branch ('${current.getOrElse("detached HEAD")}') of the base workspace"
              )
            else Right(baseTipOf(m))
          catch case e: Throwable => Right(baseTipOf(m)) // 闸面自身故障 = fail-open（不楔死队列）
    end baseReady

    /**
     * 阻塞类别（R1 面板/审计分类；**语义边界**：都是「机制挂起」，与 delegate
     * 自己申报的 standby（等作者裁定）毫无关系）。
     */
    object BlockClass:
      /**
       * 闸未过（道忙 / 净空 / 方向闸）——放行条件暂不满足。
       *
       * 前身 `NoChannel`（`"no-channel"`）**已删除**（P1 投递身份化 2026-11-05）：身份投递
       * 下不存在「无通道」形态——投不出去只有一种原因 = 收件会话真没了，而那是
       * `owner-gone` **终态**（放行腿当拍即收口），不是可重试的阻塞。保留一个永无生产者
       * 的分类值只会让面板出现没人能解释的状态；随之退役的还有它驱动的「成员自己的死结」
       * 退避窗 + 上界降级阶梯（见 [[requeueBlocked]]）。
       */
      val Gate: String = "gate"
    end BlockClass

    /**
     * 放行闸未过：窗让回（state → waiting）+ 记账 + 面板可见原因 + **必通知**
     * （#943：阻塞绝不静默——父会话收到可行动原因全文；同因去重，N 拍仍堵升级）；
     * 等下一拍。
     *
     * **P1 投递身份化（2026-11-05）后的唯一阻塞类 = 闸**（[[BlockClass]]）：
     *   - 逐拍重试、保 FIFO 位次（`readyAt` 不动）——闸的条件由**外部**改变（道净 /
     *     检出分支纠正 / 净空维护完成），队列侧读不到「条件已变」的信号，退避窗与
     *     降级只会把成员永久钉在 needs-human，换不来任何放行（实测：三个既有 spec 的
     *     升级拍点因此漂移）；
     *   - 同因连续 [[EscalateAfterBlockedRounds]] 拍 ⇒ 恰一次 `escalate`（作者面，
     *     见 [[noteBlockedOrFailed]]）——**绝不无限静默**；被准确升级之后仍逐拍重试
     *     （条件一变当拍就能走）。
     *
     * 旧 R1 的「成员自己的死结」退避窗（`skipUntilMs`）+ 上界降级（`needsHuman`）
     * 随 `BlockClass.NoChannel` 一起退役：身份投递下投不出去 = 收件会话已删 =
     * `owner-gone` **终态**（[[grant]] ⑥ 当拍收口），不是可重试的阻塞，因此不存在
     * 「无通道成员堵死整条队」这一形态可退避。
     */
    private def requeueBlocked(
      reserved: Member,
      reason: String,
      blockClass: String = BlockClass.Gate,
      headSkip: Boolean = true,
      depth: Int = 0,
      attempted: Set[String] = Set.empty
    ): Unit =
      val memoTarget =
        members.get(reserved.delegateId) match
          case Some(cur) if cur.state == State.TurnGranted =>
            val rounds = cur.blockedRounds + 1
            // P1：闸未过 = 逐拍重试（见上注释）——不设退避窗、不降级（`skipUntilMs` /
            // `needsHuman` 只由 parkRefused 与人工/自愈路径写）。
            val back = cur.copy(
              state = State.Waiting,
              grantedAt = None,
              lastBlockReason = Some(reason.take(2000)),
              blockedRounds = rounds,
              skipUntilMs = None
            )
            MergeQueueChannel.sync(back.delegateId, back.state, memberJson(back)) // write-ahead（R5）
            members = members.updated(cur.delegateId, back)
            persistLocked()
            record(
              back,
              "grant-blocked",
              Json.obj(
                "reason" -> reason.take(2000).asJson,
                "blockClass" -> blockClass.asJson,
                "blockedRounds" -> rounds.asJson
              )
            )
            Some(back)
          case _ => None // 两拍间已关账——不动
      memoTarget.foreach { m =>
        noteBlockedOrFailed(m, "blocked", reason)
        logger.warn(
          s"[merge-queue] grant blocked for '${reserved.delegateId}' ($blockClass, round ${m.blockedRounds}/$EscalateAfterBlockedRounds): $reason"
        )
      }
    end requeueBlocked

    /**
     * 放行通知两态（P1 身份化：`Deferred` 随通道闭包一起退役——身份投递不存在
     * 「闭包缺席」这一形态）。Delivered = 指令已写进属主会话磁盘邮箱（条目在盘 =
     * 必达，重启后照样成立）；Failed = 投不出去（会话已删/写失败）⇒ owner-gone。
     */
    private sealed trait NotifyResult

    private object NotifyResult:
      case object Delivered extends NotifyResult
      final case class Failed(reason: String) extends NotifyResult
    end NotifyResult

    /**
     * 放行通知（按通道分派；三态见 [[NotifyResult]]）。`channelPremised` = 占窗前
     * 通道已有未关账镜像（pump 预读，R5 前提本体——占窗自写镜像会让事后读数恒真）。
     */
    /**
     * 放行通知（**P1 投递身份化**：身份 → 落盘邮箱，三态见 [[NotifyResult]]）。
     *
     * 两条腿的旧语义映射（逐条对照，绝不改判据强度）：
     *   - `Delivered`：指令已写进属主会话的磁盘邮箱（条目在盘 = 必达，随任何唤醒点
     *     被消费；**重启后照样成立**——这正是本批要的）。
     *   - `Failed`：投不出去（会话已删 / 邮箱写失败）⇒ 调用方判 owner-gone。
     *   - `Deferred`（R5 前提反转面）**已废除**：它的存在理由是「投递靠进程内闭包，
     *     重启后闭包没了但成员资格还在盘上」——身份化之后没有任何「闭包缺席」这回事，
     *     投得出去就 Delivered、投不出去就是会话真没了（Failed）。留一个永不可达的
     *     第三态只会让面板出现没人能解释的状态值。
     */
    private def notifyTurn(m: Member): NotifyResult =
      val brief = mergeBriefFor(m)
      OwnerDeliveryPort.deliverSync(m, brief, "merge-turn") match
        case Right(()) => NotifyResult.Delivered
        case Left(err) => NotifyResult.Failed(err)
    end notifyTurn

    /**
     * 「已落地」收尾通知（grant 时账实核对命中 + 属主会话还 dock 着）：让属主
     * 立即 finish、不碰 base（delegate 侧纪律 #6 的引擎配对闸）。通知失败只 WARN
     * ——账已关（merged），会话超时链兜底。
     */
    private def notifyAlreadyLanded(m: Member): Unit =
      val brief = alreadyLandedBrief(m)
      OwnerDeliveryPort.deliverSync(m, brief, "already-landed") match
        case Right(()) => record(m, "already-landed-notice", Json.obj())
        case Left(err) =>
          logger.warn(s"[merge-queue] already-landed notice failed for '${m.delegateId}': ${err.take(200)}")
    end notifyAlreadyLanded

    // ── 关账（merged / owner-gone / failed；全部先账实核对再落终态） ──────

    /**
     * 关账 merged（幂等）。`via` = 审计来源（fact-check / round / boot-reconcile）。
     * `cleanup` = 合并后现场回收（PostMergeCleanup——零领先守卫 ＋ 在飞守卫，绝不丢工作）。
     * `ownerTerminal` = 本次关账是否由**属主自己的终态序列**驱动（merge-round /
     * fact-check-on-terminal）——该腿的现场回收跳过在飞守卫（「分支已合并」与「会话正在
     * 收尾」是同一件事；队列侧异步腿缺省走守卫，见 [[PostMergeCleanup.run]]）。
     * 关账前再跑一次账实核对：事实未落地但调用方声称 merged（机械验收已挡住常规
     * 路径；boot/手工路径兜底）⇒ 照关（调用方是裁决者），但 closeReason 如实标注。
     */
    def closeMerged(
      delegateId: String,
      via: String,
      closeReason: Option[String] = None,
      cleanup: Boolean = false,
      ownerTerminal: Boolean = false,
      notifyParent: Boolean = true
    ): Unit = {
      ensureLoadedLocked()
      members.get(delegateId) match
        case Some(m) if m.open =>
          val closed = m.copy(
            state = State.Merged,
            closedAt = Some(System.currentTimeMillis()),
            closeReason = closeReason.orElse(Some(s"merge landed (via $via)")),
            // R1 退避/降级账随关账清零（终态成员不参与轮转，残留只会污染面板）。
            blockedRounds = 0,
            skipUntilMs = None,
            needsHuman = None
          )
          clearBackoffMemosLocked(delegateId)
          MergeQueueChannel.sync(closed.delegateId, closed.state, memberJson(closed)) // write-ahead（R5）
          members = members.updated(delegateId, closed)
          persistLocked()
          // 收口语义消歧（缺陷批 2026-11-04 · 作者问「branch already merged into base /
          // merge landed (care-tick fact-check) 到底是检测到别人合了，还是我自己合的」）：
          // 账本行显式分开 **谁改的账**（`via`）与 **谁执行的合并**（`executor`）。
          // 队列**永不执行 git merge**（台账 #896 交通引导员制）——凡是 fact-check
          // 家族的 via 都是**观测**（executor=detected-not-executed）；只有
          // `merge-round` 是属主自己的合并轮回报（executor=owner-round）。人的手工
          // 合并同样落在 detected 面（队列只观测到祖先关系）。
          val executor = via match
            case "merge-round" => "owner-round"
            case _ => "detected-not-executed"
          record(
            closed,
            "merge-merged",
            Json.obj(
              "via" -> via.asJson,
              "executor" -> executor.asJson,
              "reason" -> closed.closeReason.fold("".asJson)(_.asJson)
            )
          )
          if cleanup then
            try
              DelegateRegistry
                .find(delegateId)
                .foreach(rec => PostMergeCleanup.run(Some(rec), ownerTerminal).unsafeRunSync())
            catch
              case e: Throwable =>
                logger.warn(
                  s"[merge-queue] post-merge cleanup failed for $delegateId: ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
                )
          Some(closed)
        case _ => None
      end match
    }.foreach { closed =>
      // merge-fifo 批 2026-11-03（作者报障②：队列侧合并是静默的——`care-tick
      // fact-check` 落了地从不宣布）：关账 merged 也是**状态变化**，父/根会话
      // 收到一条可行动通知（[[BlockNotifyPort]] kind=`merged`，同因去重）。
      // `notifyParent = false` 的两处用法：① 属主自己的终态腿（合并轮的 Release
      // 就是父通知本体——18:14 矩阵裁定「不许双通知」）；② 自身已投父的恢复腿
      // （BootSweep 的 restart-recovery 文案）。
      if notifyParent then
        noteBlockedOrFailed(closed, "merged", closed.closeReason.getOrElse(s"merge landed (via $via)"))
      pump(closed.projectKey)
    }

    /**
     * 关账 owner-gone（幂等）：属主失联——通知不可达 / 会话终态 / 重启后通道丢失。
     * 关账前**先账实核对**：期间被并（human/别的轮次）⇒ 关 merged 而非 owner-gone
     * （今晚「分支已落定仍在账上罚站」的消除闸）。
     * **孤儿合并收场（投递可靠性批 2026-10-29 · 病灶①，作者令 23:55 范围补充 A）**：
     * 属主已死 + 主工作区残留 MERGE_HEAD == 本成员分支 tip = 引擎所授合并轮的孤儿
     * 残留（死在解冲突/提交途中）⇒ `git merge --abort` 回滚 + 审计行（merge-lane
     * orphan-abort）——恢复语义非代并（作者裁定「倾向=abort 回滚 + 合并轮作废重排」）。
     * MERGE_HEAD 指向他人分支/不可归属 = 零触碰（人的手工合并绝不可 abort）。
     */
    def markOwnerGone(delegateId: String, reason: String): Unit =
      val mOpt =
        ensureLoadedLocked()
        members.get(delegateId).filter(_.open)
      mOpt.foreach { m =>
        if alreadyMerged(m) then
          closeMerged(
            delegateId,
            via = "fact-check-on-owner-gone",
            closeReason = Some("owner gone, but the branch was already merged — closed as merged"),
            cleanup = true
          )
        else
          abortOrphanMergeLeftover(m, via = s"owner-gone: $reason")
          val closed =
            members.get(delegateId) match
              case Some(cur) if cur.open =>
                val c = cur.copy(
                  state = State.OwnerGone,
                  closedAt = Some(System.currentTimeMillis()),
                  closeReason = Some(reason.take(300)),
                  blockedRounds = 0,
                  skipUntilMs = None,
                  needsHuman = None
                )
                clearBackoffMemosLocked(delegateId)
                MergeQueueChannel.sync(c.delegateId, c.state, memberJson(c)) // write-ahead（R5）
                members = members.updated(delegateId, c)
                persistLocked()
                record(c, "owner-gone", Json.obj("reason" -> reason.take(300).asJson))
                Some(c)
              case _ => None
          closed.foreach { c =>
            logger.warn(
              s"[merge-queue] OWNER-GONE '${c.delegateId}' (branch '${c.branch}'): $reason — needs dispatcher handling"
            )
            OwnerGonePort.deliver(c, reason)
            pump(c.projectKey)
          }
      }
    end markOwnerGone

    /**
     * 孤儿合并残留收场（[[markOwnerGone]] / careTick 持窗尸体判死共用）：仅当
     * MERGE_HEAD == 本成员分支 tip（死属主自己的未收场合并态）才 `git merge
     * --abort`；其余形态零触碰。全程审计（ChainStore `merge-queue` 行
     * action=orphan-abort / orphan-abort-skipped + WARN）。
     */
    private def abortOrphanMergeLeftover(m: Member, via: String): Unit =
      val ws = Try(os.Path(m.baseWs)).toOption
      val mh = ws.filter(os.exists(_)).flatMap(GitWorktrees.mergeHead)
      val mine = branchTipIs(ws, m.branch, mh.getOrElse(""))
      (mh, mine) match
        case (Some(head), true) =>
          val aborted = ws.exists(GitWorktrees.abortMerge)
          logger.warn(
            s"[merge-queue] orphan merge ${if aborted then "ABORTED" else "abort FAILED"} for '${m.delegateId}' " +
              s"(branch '${m.branch}', MERGE_HEAD=$head): $via — recovery semantics, not an engine merge"
          )
          record(
            m,
            "orphan-abort",
            Json.obj("mergeHead" -> head.take(12).asJson, "aborted" -> aborted.asJson, "via" -> via.take(300).asJson)
          )
        case (Some(head), false) =>
          // 残留指向他人/未知分支——零触碰（人的手工合并形态），只留痕。
          record(
            m,
            "orphan-abort-skipped",
            Json.obj(
              "mergeHead" -> head.take(12).asJson,
              "reason" -> "MERGE_HEAD does not belong to this member's branch — untouched".asJson
            )
          )
        case _ => () // 道净（无 MERGE_HEAD）= 无收场动作
      end match
    end abortOrphanMergeLeftover

    /**
     * 合并轮道忙重排（投递可靠性批 2026-10-29 · 病灶②，作者令 23:57 范围补充②）：
     * 合并轮结算发现拒绝原因是道忙（他批合法占道/孤儿未收殓/外来手工合并）⇒ 成员
     * **保留资格退回 waiting**（turn-granted → waiting，grantedAt 清空、阻塞原因
     * 入账、#943 必通知），会话由监督者停靠保活；道净后 careTick 放行腿自动重排
     * （重授 → 重新注入合并轮指令）。查无开放成员/已关账 = no-op（幂等）。
     */
    def requeueForLane(delegateId: String, reason: String): Unit =
      val memoTarget =
        ensureLoadedLocked()
        members.get(delegateId) match
          case Some(cur) if cur.state == State.TurnGranted =>
            val back = cur.copy(
              state = State.Waiting,
              grantedAt = None,
              grantedBaseTip = None,
              lastBlockReason = Some(reason.take(2000)),
              // R1：重排回 waiting = 新起点（退避/降级账清零——下一拍照常参与轮转）。
              blockedRounds = 0,
              skipUntilMs = None,
              needsHuman = None
            )
            clearBackoffMemosLocked(delegateId)
            members = members.updated(delegateId, back)
            persistLocked()
            record(back, "lane-requeued", Json.obj("reason" -> reason.take(2000).asJson))
            Some(back)
          case _ => None
        end match
      end memoTarget
      memoTarget.foreach { m =>
        logger.warn(s"[merge-queue] merge round re-queued for '${m.delegateId}' (lane busy, membership kept): $reason")
        noteBlockedOrFailed(m, "blocked", reason)
      }
    end requeueForLane

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
      val mOpt =
        ensureLoadedLocked()
        members.get(delegateId).filter(_.open)
      mOpt.foreach { m =>
        outcome match
          case Some(RoundOutcome.Merged) =>
            // merge-fifo 批 2026-11-03（18:14 矩阵裁定「不许双通知」）：本腿的父通知
            // 是**属主自己的终态 Release**（合并轮的工作结果 + 判词就在那条投递里）——
            // 队列侧不再叠一条 `merged` 通知（notifyParent = false）；队列侧**异步**
            // 关账腿（care-tick / 放行前账实核对）没有 Release 兜底，照常必通知。
            closeMerged(delegateId, via = "merge-round", cleanup = true, notifyParent = false)
          case Some(RoundOutcome.Refused(reason)) =>
            closeFailed(delegateId, reason)
          case None =>
            // 回合外终态（取消/超时/会话死亡）：本序列的终态投递照常报父，merged 细节
            // 不另发通知（同上：不许双通知）。
            if alreadyMerged(m) then
              closeMerged(
                delegateId,
                via = "fact-check-on-terminal",
                closeReason = Some("session ended; the branch was already merged"),
                cleanup = true,
                notifyParent = false
              )
            else
              markOwnerGone(
                delegateId,
                s"owner session ended without completing the merge round (state was '${m.state}')"
              )
      }
    end onTerminal

    /**
     * 关账 failed（合并回合被拒；原因落账 + 面板可见 + **父会话必通知**（#943，
     * 原因全文随行）；无自动重试）。
     */
    private def closeFailed(delegateId: String, reason: String): Unit = {
      ensureLoadedLocked()
      members.get(delegateId) match
        case Some(m) if m.open =>
          val closed = m.copy(
            state = State.Failed,
            closedAt = Some(System.currentTimeMillis()),
            closeReason = Some(reason.take(300)),
            blockedRounds = 0,
            skipUntilMs = None,
            needsHuman = None
          )
          clearBackoffMemosLocked(delegateId)
          MergeQueueChannel.sync(closed.delegateId, closed.state, memberJson(closed)) // write-ahead（R5）
          members = members.updated(delegateId, closed)
          persistLocked()
          record(closed, "merge-failed", Json.obj("reason" -> reason.take(300).asJson))
          Some(closed)
        case _ => None
      end match
    }.foreach { closed =>
      logger.warn(
        s"[merge-queue] merge round FAILED for '${closed.delegateId}' (branch '${closed.branch}'): $reason — closed, no auto-retry"
      )
      noteBlockedOrFailed(closed, "failed", reason) // 状态变化 ⇒ 必重发（原因全文）
      pump(closed.projectKey)
    }
    end closeFailed

    // ── 活性看护（扫描拍消费；TaskStuckWatcher.scan 尾部单点） ────────────

    /**
     * 一拍看护（事件判据，零时间判据）：
     *   - **R2（2026-11-04）全部开放成员只读账实核对**：不看状态、不等队头——分支
     *     已并入 base ⇒ 直接按 merged 关账（[[factCheckOpenMembers]]）；
     *   - 持窗成员（turn-granted）**先账实核对**（投递可靠性批 2026-10-29 · 病灶③，
     *     作者令 00:13「窗口释放必须绑定合并 commit 落 main 或属主终态事件自动销账，
     *     不依赖属主下一拍主动还窗」）：分支已是基分支祖先 = 合并真落地 ⇒ 立即关账
     *     merged + pump 下一位（属主死灭/迟报/漏报都不再扣窗）；
     *   - 持窗成员内部腿属主 actor 已死 ⇒ owner-gone（窗绝不许尸体持死；孤儿
     *     MERGE_HEAD 收场随 [[markOwnerGone]] 的 abort 腿——病灶①）；
     *   - 每个有开放成员的 project 补一拍放行尝试（净空转洁 / 阻碍消除 / 道净后
     *     重排成员的自然推进——病灶②的重排兑现腿）。
     * 幂等：动作过的成员已关账，重复扫描零重复动作。
     */
    def careTick(): Unit = careTickWith(openFactCheck = true)

    /**
     * `openFactCheck = false` / `headSkip = false` = **本批之前的旧语义**（只核对持窗
     * 成员 + 只看 FIFO 队头，等在前面的 waiting 成员永远轮不到核对）——spec 反向验
     * 红缝，生产恒为默认值。红/绿两条腿要一起关：R1 的让位会让队尾走到放行腿，而
     * 放行腿自带账实核对，会把 R2 的缺口遮住。
     */
    private[delegate] def careTickWith(
      openFactCheck: Boolean,
      headSkip: Boolean = true,
      scope: Set[String] = Set.empty
    ): Unit =
      () // Legacy records remain readable; no automatic grants or cleanup.
    end careTickWith

    /** R2：每次核对打印不可读读数的成员（进程内每成员一次——防每拍刷屏；读数恢复后清）。 */
    private var factCheckUnreadable: Set[String] = Set.empty

    /**
     * R2 —— 全部开放成员的**只读**账实核对（审计 20261104_164800 §4-R2）。
     *
     * 判据 = `git merge-base --is-ancestor <branch> <baseBranch>`（[[factCheck]]），
     * **只读**：不写分支、不写 base 工作区、不 checkout、不 merge。命中 `Some(true)`
     * ⇒ [[closeMerged]]（via = `fact-check-care-tick`）——与「人工兜底」配对：作者/
     * dispatcher 把分支手工并进 main 之后，队列**下一拍自己就知道**，不必再排一次队、
     * 也不必等属主还窗。
     *
     * 🔴 红线（fail-soft）：`None`（仓库不可读 / git 报错 / 非 git 目录）**绝不关账**
     * ——核对失败 ≠ 已并入；只留一条 WARN（每成员一次），成员照旧在队。
     */
    private[delegate] def factCheckOpenMembers(scope: Set[String] = Set.empty): Unit =
      val open = snapshot.filter(m => m.open && (scope.isEmpty || scope.contains(m.projectKey)))
      open.foreach { m =>
        factCheck(m) match
          case Some(true) =>
            logger.info(
              s"[merge-queue] care-tick open fact-check: '${m.delegateId}' branch '${m.branch}' is already an ancestor of '${m.baseBranch}' " +
                s"(state was '${m.state}') — closing the membership as merged without a merge window"
            )
            closeMerged(
              m.delegateId,
              via = "fact-check-care-tick",
              closeReason = Some(
                "branch already merged into base — OBSERVED by the read-only care-tick fact-check (no merge window needed; the queue did not execute this merge)"
              ),
              cleanup = true
            )
            factCheckUnreadable = factCheckUnreadable - m.delegateId
          case Some(false) =>
            factCheckUnreadable = factCheckUnreadable - m.delegateId
          case None =>
            val firstTime =
              val seen = factCheckUnreadable.contains(m.delegateId)
              factCheckUnreadable = factCheckUnreadable + m.delegateId
              !seen
            if firstTime then
              logger.warn(
                s"[merge-queue] care-tick open fact-check SKIPPED for '${m.delegateId}' (base '${m.baseWs}' unreadable or not a git repo) " +
                  "— fail-soft: the membership stays open, a failed fact-check never closes a member"
              )
      }
    end factCheckOpenMembers

    // ── 崩溃重启换绑（监督者 respawn——事件驱动的「复活」信号） ────────────

    /**
     * delegate 会话崩溃经监督者 respawn：P1 身份化后队列**不再持有任何 actor ref**
     * （投递只认 `ownerSessionId` + 落盘邮箱），因此本方法退化为「复活信号」——
     * 解除退避/降级（宣告闸仍然把守），并落审计行。已关账/查无 = no-op。
     */
    def reattach(delegateId: String): Unit =
      ensureLoadedLocked()
      members.get(delegateId).filter(_.open) match
        case None => ()
        case Some(m) =>
          // P1 身份化：投递不再持有任何 actor ref，所以「换绑」退化为**一次复活信号**
          // （属主会话被监督者重新拉起来了 = 条件已变）。降级解除仍由宣告闸把守。
          val degraded = m.needsHuman.isDefined || m.skipUntilMs.isDefined || m.blockedRounds > 0
          val refreshed =
            if degraded then
              clearBackoffMemosLocked(delegateId)
              m.copy(blockedRounds = 0, skipUntilMs = None, needsHuman = None)
            else m
          members = members.updated(delegateId, refreshed)
          if degraded then
            MergeQueueChannel.sync(refreshed.delegateId, refreshed.state, memberJson(refreshed))
            persistLocked()
            record(refreshed, "channel-restored-revive", Json.obj("phase" -> "respawn-reattach".asJson))
          // 投递身份是持久数据（不再有进程内闭包）——只落审计行。
          record(refreshed, "session-reattached", Json.obj("phase" -> "respawn-reattach".asJson))

      end match

    end reattach

    /**
     * 重启对账**保留面**（R5 owner-gone 前提反转的落账腿；调用方 = MergeQueueBootSweep，
     * 前提自检「通道有未关账镜像」在调用方）：对账确认「可并但属主随重启失联」的
     * 开放成员，窗让回（state → waiting，grantedAt 清）+ 原因上面板（lastBlockReason）
     * + **必通知**（[[noteBlockedOrFailed]]：同因去重、N 拍升级作者）——成员资格不因
     * 投递闭包随进程消亡而关账。自愈路径：属主被续活 ⇒ 下拍放行重投；分支被人工并
     * 入 ⇒ 下拍账实核对直接关账 merged（B2 人工归队形态从此自动收口）。
     * 幂等：已关账/查无 = no-op；终态成员不受影响。
     */
    def preserveAfterRestart(delegateId: String, reason: String): Unit =
      val target =
        ensureLoadedLocked()
        members.get(delegateId).filter(_.open).map { cur =>
          val back = cur.copy(
            state = State.Waiting,
            grantedAt = None,
            lastBlockReason = Some(reason.take(2000)),
            // R1：重启保留 = 新起点（退避窗不跨进程残留，重启后立刻可再试一次）。
            blockedRounds = 0,
            skipUntilMs = None,
            needsHuman = None
          )
          clearBackoffMemosLocked(delegateId)
          MergeQueueChannel.sync(back.delegateId, back.state, memberJson(back)) // write-ahead（R5）
          members = members.updated(delegateId, back)
          persistLocked()
          record(back, "restart-preserved", Json.obj("reason" -> reason.take(2000).asJson))
          back
        }
      end target
      target.foreach { m =>
        logger.warn(
          s"[merge-queue] membership PRESERVED across restart for '${m.delegateId}' (channel record open) — window returned, needs owner revival or manual merge: $reason"
        )
        noteBlockedOrFailed(m, "blocked", reason)
      }
    end preserveAfterRestart

    // ── 合并指令文本（放行通知本体） ─────────────────────────────────────

    /** 测试复位（生产无调用方）：清内存 + 清账本文件 + 清 write-ahead 通道。 */
    private[nebflow] def reset(): Unit =
      members = Map.empty
      notifyMemos = Map.empty
      needsHumanAnnounced = Set.empty
      factCheckUnreadable = Set.empty
      loaded = false
      try if os.exists(ledgerPath) then os.remove.all(ledgerPath)
      catch case _: Throwable => ()
      MergeQueueChannel.clear()

    /** 测试专用的「进程重启」模拟：只丢内存态（账本文件保留），下次访问自盘回载。 */
    private[nebflow] def dropMemoryStateForTest(): Unit =
      members = Map.empty
      loaded = false

    /** 测试专用：直接把成员写进持久账本（绕过入账泵——模拟重启前的盘上账面）。 */
    private[nebflow] def seedMemberForTest(m: Member): Unit =
      ensureLoadedLocked()
      members = members.updated(m.delegateId, m)
      persistLocked()

    /**
     * 测试专用：只写 write-ahead 通道镜像（账本/内存都不写——模拟「通道落盘后、
     * 账本写盘前」被杀的 ready-前被杀窗口形态）。
     */
    private[nebflow] def seedChannelOnlyForTest(m: Member): Unit =
      MergeQueueChannel.sync(m.delegateId, m.state, memberJson(m))
  end Engine

  // ════════════════════════════════════════════════════════════════════
  // 门面（P2 QueueActor 批 · 2026-11-06）：**只转发不持状态**
  //
  // 20+ 同步调用点（DelegateTerminal / MergeEnqueueWatch / BackoffSupervisor /
  // ChainsReadFace / DelegateFreeze / MergeQueueCare / MergeQueueBootSweep …）
  // 一个字都不用改：签名与返回值语义逐条不变，转发落点从「锁 + 单例可变字段」
  // 变成「QueueActor 的邮箱」。
  //
  // 读面（snapshot / find / openSnapshot / isMergeRound / queueViewOf）读的是
  // actor 每次操作后**发布的不可变快照**——零邮箱往返、零锁、无撕裂（快照是
  // 一次性替换的不可变值）。写面一律进邮箱串行执行。
  // ════════════════════════════════════════════════════════════════════

  /** 已发布的只读快照（actor 每次操作后整体替换：sorted 保 FIFO 读面，byId 供 find）。 */
  private final case class Published(sorted: List[Member], byId: Map[String, Member], loaded: Boolean)

  private val published =
    new java.util.concurrent.atomic.AtomicReference[Published](Published(Nil, Map.empty, false))

  private def publish(members: Map[String, Member], loaded: Boolean): Unit =
    published.set(Published(members.values.toList.sortBy(m => (m.readyAt, m.delegateId)), members, loaded))

  /** 门面 → actor 的一次串行操作（同线程重入 = 直接落引擎，绝不二次进邮箱自锁）。 */
  private def op[A](f: Engine => A): A = Runtime.op(f)

  /** 冷启动 / reset / dropMemoryStateForTest 之后快照未加载 ⇒ 先让 actor 加载一次账本。 */
  private def readySnapshot: Published =
    val p = published.get
    if p.loaded then p
    else
      op(_.settle())
      published.get

  def snapshot: List[Member] = readySnapshot.sorted

  def openSnapshot: List[Member] = readySnapshot.sorted.filter(_.open)

  def find(delegateId: String): Option[Member] = readySnapshot.byId.get(delegateId)

  def isMergeRound(delegateId: String): Boolean = false

  /**
   * 某 delegate 当下的排队槽位投影（行内「谁挡着我/我排第几」读面单点）。
   * 只对**开放成员**有值（终态成员 = 已出账，键缺席）。排序键 = 引擎自己的
   * FIFO 序（readyAt ↑ → delegateId ↑），行投影不二次派生次序（快照已按该序）。
   */
  def queueViewOf(delegateId: String): Option[QueueView] =
    val snap = snapshot
    snap.find(m => m.delegateId == delegateId && m.open).flatMap { me =>
      val open = snap.filter(m => m.projectKey == me.projectKey && m.open)
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
            readyAt = me.readyAt,
            blockedRounds = me.blockedRounds,
            skipUntilMs = me.skipUntilMs,
            needsHuman = me.needsHuman
          )
        )
      end if
    }
  end queueViewOf

  // ── 写面（一律进 actor 邮箱）────────────────────────────────────────

  def enqueue(entry: Member): Unit = op(_.enqueue(entry))

  def enqueueIO(entry: Member): IO[Unit] = IO.blocking(enqueue(entry))

  def enqueuePendingIO(entry: Member): IO[Unit] = IO.blocking(op(_.enqueuePending(entry)))

  def enqueueVerifiedIO(entry: Member): IO[Boolean] = IO.blocking(op(_.enqueueVerified(entry)))

  def declareOwnership(
    delegateId: String,
    source: String,
    nowMs: Long = System.currentTimeMillis()
  ): Option[MergeDeclaration.Decl] = op(_.declareOwnership(delegateId, source, nowMs))

  def pump(projectKey: String): Unit = op(_.pump(projectKey))

  private[delegate] def pumpWith(
    projectKey: String,
    headSkip: Boolean,
    depth: Int = 0,
    attempted: Set[String] = Set.empty
  ): Unit = op(_.pumpWith(projectKey, headSkip, depth, attempted))

  def closeMerged(
    delegateId: String,
    via: String,
    closeReason: Option[String] = None,
    cleanup: Boolean = false,
    ownerTerminal: Boolean = false,
    notifyParent: Boolean = true
  ): Unit = () // Retired: completion/cleanup belongs to WorkspaceLedger merger reports.

  def closeMergedIO(
    delegateId: String,
    via: String,
    closeReason: Option[String] = None,
    cleanup: Boolean = false,
    ownerTerminal: Boolean = false,
    notifyParent: Boolean = true
  ): IO[Unit] = IO.blocking(closeMerged(delegateId, via, closeReason, cleanup, ownerTerminal, notifyParent))

  def markOwnerGone(delegateId: String, reason: String): Unit = op(_.markOwnerGone(delegateId, reason))

  def markOwnerGoneIO(delegateId: String, reason: String): IO[Unit] = IO.blocking(markOwnerGone(delegateId, reason))

  def requeueForLane(delegateId: String, reason: String): Unit = op(_.requeueForLane(delegateId, reason))

  def onTerminal(delegateId: String, outcome: Option[RoundOutcome] = None): Unit = ()

  def onTerminalIO(delegateId: String, outcome: Option[RoundOutcome] = None): IO[Unit] =
    IO.blocking(onTerminal(delegateId, outcome))

  def careTick(): Unit = op(_.careTick())

  private[delegate] def careTickWith(openFactCheck: Boolean, headSkip: Boolean = true): Unit =
    op(_.careTickWith(openFactCheck, headSkip))

  def careTickIO: IO[Unit] = IO.blocking(careTick())

  def reattach(delegateId: String): Unit = op(_.reattach(delegateId))

  def reattachIO(delegateId: String): IO[Unit] = IO.blocking(reattach(delegateId))

  def preserveAfterRestart(delegateId: String, reason: String): Unit = op(_.preserveAfterRestart(delegateId, reason))

  private[delegate] def laneVerdictFor(m: Member): LaneVerdict = op(_.laneVerdictFor(m))

  // ── 自驱（P2）：提醒 / TTL 全部由 actor 自己的 Tick 驱动，队列内零睡眠轮询 ──

  /** 生产自驱拍（默认 60s；`-D nebflow.mergequeue.selftick.ms` 可覆）。 */
  def SelfTickMs: Long = sys.props.getOrElse("nebflow.mergequeue.selftick.ms", "60000").toLong

  /**
   * 打开 actor 自驱：每 `intervalMs` 一拍、或某个成员的 `skipUntilMs` 退避窗到点时，
   * actor 自己（`ctx.scheduleOnce` → [[QueueMsg.Tick]]）跑一拍 [[careTick]]——
   * **不用定时轮询**，唤醒次数 = 截止时刻变化的次数（P0 的刀一纪律原样继承）。
   *
   * `scope` 非空 = 只驱动这些 projectKey（spec 用：队列是进程级单例，验收用例
   * 不许把看护拍泼到别的用例的 member 上）。生产装配点 = `GatewayMain` boot。
   */
  def enableSelfTick(intervalMs: Long, scope: Set[String] = Set.empty): Unit =
    op(_.configureSelfTick(intervalMs, scope))

  def disableSelfTick(): Unit = op(_.configureSelfTick(0L, Set.empty))

  /** 验收读数（P2）：队列状态机的宿主 = 真 ActorSystem 里的 actor 路径。 */
  private[delegate] def actorPath: String = Runtime.actorPath

  // ── 测试复位（生产无调用方）────────────────────────────────────────

  private[nebflow] def reset(): Unit = op(_.reset())

  private[nebflow] def dropMemoryStateForTest(): Unit = op(_.dropMemoryStateForTest())

  private[nebflow] def seedMemberForTest(m: Member): Unit = op(_.seedMemberForTest(m))

  private[nebflow] def seedChannelOnlyForTest(m: Member): Unit = op(_.seedChannelOnlyForTest(m))

  // ════════════════════════════════════════════════════════════════════
  // QueueActor（P2 · 2026-11-06）：队列状态的**唯一所有权者**
  //
  // 改前：`object` 单例可变字段 + `new Object` 监视器锁（全文件 27 处加锁块）——
  // 谁都能改、每处都得自己记得加锁，且锁内还在跑 git / 落盘 / 端口投递。
  // 改后：状态机整件搬进 [[Engine]]（一份可变状态、零锁），只有本 actor 的
  // **邮箱线程**能碰它：一次操作 = 邮箱里的一条 `Op` 消息，串行由构造保证
  // （同 project 授窗单飞行不再依赖判断，而是「根本没有第二个线程能同时进
  // pump」）。
  //
  // 门面 ↔ actor 的桥（同步调用点必须保持同步）：
  //   - 调用方线程发 `Op` 后等一个 `CompletableFuture`（超时可配，默认 5 分钟）；
  //   - 引擎内部自己再调自己（grant → pump → closeMerged…）**不进邮箱**——
  //     那是同一个线程上的普通方法调用，绝不自锁；
  //   - 重入保护：端口投递回调里若再调门面，线程本地标记让它直接落引擎
  //     （同一个线程，串行性不变），避免「等自己」的死锁。
  // ════════════════════════════════════════════════════════════════════

  private sealed trait QueueMsg

  private object QueueMsg:
    /** 一次门面操作（一问一答）。`run` 只在队列 actor 的邮箱线程上执行。 */
    final case class Op(run: Engine => Any, done: java.util.concurrent.CompletableFuture[Any]) extends QueueMsg

    /**
     * Tick —— P0 刀一 `scheduleOnce` 的消费面：**到点**投一条消息给自己。
     * `arm` = 臂号（去陈旧单点）：只有与当前臂号相同的 Tick 才生效。
     */
    final case class Tick(arm: Long) extends QueueMsg

  private object Runtime:

    /** 队列 actor 自己的线程标记（端口回调里重入门面时不再进邮箱）。 */
    private val ownerThread = new ThreadLocal[java.lang.Boolean]

    def onActorThread: Boolean = java.lang.Boolean.TRUE == ownerThread.get()

    def markActorThread(v: Boolean): Unit =
      if v then ownerThread.set(java.lang.Boolean.TRUE) else ownerThread.remove()

    /** 门面等待上界（默认 5 分钟；`-D nebflow.mergequeue.opTimeoutMs` 可覆）。 */
    private def opTimeoutMs: Long =
      sys.props.getOrElse("nebflow.mergequeue.opTimeoutMs", "300000").toLong

    private final class Handle(val system: ActorSystem, val ref: ActorRef[QueueMsg], val engine: Engine):
      val path: String = ref.path.toString

    /**
     * 宿主 ActorSystem：**专供队列**的本地系统（懒建、进程级一个）。
     *
     * 不复用 gateway 的系统是刻意的：队列门面在 boot 早期（boot sweep）就要被调用，
     * 而宿主系统的生命周期由 gateway 掌握——把它绑上去会把「系统停了 ⇒ 队列门面
     * 永远等不到邮箱」变成一个新的死锁面。本系统的 actor 与进程同寿命。
     */
    private lazy val handle: Handle = boot()

    private def boot(): Handle =
      val system = ActorSystem("merge-queue")
      val engine = new Engine
      val ref = system.spawn(new QueueActor(engine), "mergequeue").unsafeRunSync()
      engine.settle()
      Handle(system, ref, engine)

    def engine: Engine = handle.engine

    def actorPath: String = handle.path

    /**
     * 门面 → 邮箱。同线程重入（端口回调）直接落引擎；否则发消息并阻塞等结果。
     * 超时/中途异常一律**响亮失败**（绝不静默降级成「操作没发生」）。
     */
    def op[A](f: Engine => A): A =
      if onActorThread then f(engine)
      else
        val done = new java.util.concurrent.CompletableFuture[Any]()
        val h = handle
        h.ref.!(QueueMsg.Op(f, done)).unsafeRunSync()
        try done.get(opTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).asInstanceOf[A]
        catch
          case e: java.util.concurrent.ExecutionException =>
            e.getCause match
              case c: RuntimeException => throw c
              case c: Throwable => throw new RuntimeException(c)
          case e: Throwable =>
            throw new IllegalStateException(
              s"[merge-queue] queue-actor op did not complete within ${opTimeoutMs}ms (mailbox not draining — actor '${h.path}' is stuck or stopped)",
              e
            )

  end Runtime

  /**
   * 队列 actor：**串行**消费邮箱。每条消息的处理体走 `IO.blocking`（git / 落盘 /
   * 端口投递都在里面），actor 的 fiber 在等待期间不占算力线程。
   */
  private final class QueueActor(engine: Engine) extends Behavior[QueueMsg]:

    /** 已武装的截止时刻（None = 没有待办截止）。 */
    private var armedFor: Option[Long] = None
    private var arm: Long = 0L
    private var lastTickAt: Long = System.currentTimeMillis()

    def receive(ctx: ActorContext[QueueMsg], msg: QueueMsg): IO[Behavior[QueueMsg]] =
      IO.blocking(step(msg)).flatMap(_ => armTick(ctx)).as(this)

    private def step(msg: QueueMsg): Unit = msg match
      case QueueMsg.Op(run, done) =>
        Runtime.markActorThread(true)
        try
          val out =
            try run(engine)
            catch
              case e: Throwable =>
                done.completeExceptionally(e)
                logger.warn(
                  s"[merge-queue] queue-actor op failed: ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
                )
                null
          if !done.isDone then
            engine.settle()
            done.complete(out)
        finally Runtime.markActorThread(false)
      case QueueMsg.Tick(a) =>
        if a == arm then
          armedFor = None
          lastTickAt = System.currentTimeMillis()
          Runtime.markActorThread(true)
          try
            engine.careTickOnActor()
            engine.settle()
          catch
            case e: Throwable =>
              logger.warn(
                s"[merge-queue] self-tick care failed: ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
              )
          finally Runtime.markActorThread(false)

    /**
     * 按引擎给出的**最早截止时刻**武装一次性定时消息（`scheduleOnce`）。
     * 截止时刻没变就不重臂（唤醒次数 = 截止变化次数，不是拍数）。
     */
    private def armTick(ctx: ActorContext[QueueMsg]): IO[Unit] =
      val now = System.currentTimeMillis()
      val next = engine.nextDeadline(now, lastTickAt)
      if next == armedFor then IO.unit
      else
        armedFor = next
        next match
          case None => IO.unit
          case Some(at) =>
            arm += 1
            ctx.scheduleOnce(
              scala.concurrent.duration
                .FiniteDuration(math.max(0L, at - now), java.util.concurrent.TimeUnit.MILLISECONDS),
              QueueMsg.Tick(arm)
            )
    end armTick
  end QueueActor
end MergeQueue
