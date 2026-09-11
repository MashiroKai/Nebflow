package nebflow.core.sandbox

import nebflow.core.NebflowLogger
import nebflow.core.tools.ToolContext

/**
 * per-task 容器生命周期**骨架**（沙箱统一底座设计方案 §2.3 / §4-T6，2026-09-11 实施批）。
 *
 * 权威源：
 *  - 状态机 = `~/.nebflow/docs/Nebflow/20260910_沙箱统一底座设计方案.md` §2.3（=
 *    `20260907_sandbox-capability-integration.md` §1.2 底座定义，v1 不变）。
 *  - 双 TTL 参数 = 同方案 §2.3 L3 + §3 D2（**D2 已按 Q4 裁定：结构上先行采纳 D2-b
 *    双 TTL（idle 15m + hard 2h），具体数值待 D2 拍板**）。
 *  - 零残留断言 = 同方案 §2.3 L9（「TTL 窗口外零残留」）。
 *
 * 本文件**只做骨架**，显式不做（后置批，见方案 §4「后置」表）：
 *  - `DockerBackend`（L1）、并发上限/LRU 驱逐（L5）、端口池（L6）、egress 三档（D7-a）、
 *    凭据外置代理（D6-a）、hooks/MCP 接缝（L11）、真实 sweeper 光纤（L4 的 5min 循环）。
 *  - **不接线**：本骨架不由 `BashTool` / `SandboxRuntime` 消费——接线属 P-M2/L4。
 *
 * 形态说明（架构铁律：Actor 管状态机、cats-effect 管副作用）：
 *  - 本骨架 = **纯逻辑 + 同步 Either**（无 IO、无 actor、无定时器）：状态机与 TTL 判据
 *    是纯函数式可测的核心；`sweep(nowMs)` 由调用方给时间（L4 的 5min 光纤后置）。
 *  - 副作用面只有 `ContainerBackend` 两个方法（create / destroy）——DockerBackend 后置，
 *    本批提供 `ContainerBackend.Fake`（内存实现，**单测无需 docker、本机不起任何容器**）。
 */

/** 容器生命周期状态机（方案 §2.3 原文四态）。
  *
  * `absent ──ensure──▶ active ──空闲/终局──▶ retained ──TTL 到期/显式──▶ destroy ──▶ absent`
  * `retained ──重激活（ensure/acquire）──▶ active`（复用暖容器，容器 id 不变）
  */
enum ContainerState:
  case Absent, Active, Retained, Destroy

/** 关闭原因（本骨架三档；并发上限驱逐属 L5 后置，**不预置占位**）。 */
enum DestroyReason:
  case IdleTtlExpired, HardTtlExpired, Explicit

/** 容器生命周期任务键。**唯一映射源 = `ToolContext.sessionId`**（方案 §2.3 状态图注：
  * `taskKey = ctx.sessionId`，与今日 `ShellSession` 会话键同源）。 */
final case class TaskKey(value: String)

object TaskKey:

  /** `ctx.sessionId` → taskKey。**无 sessionId ⇒ 显式失败**（`Left`）：
    * 不静默回落到「全局共享容器」——同款 U7 口径（宁显式失败，不隐式降级）。 */
  def fromSession(sessionId: Option[String]): Either[String, TaskKey] =
    sessionId.map(_.trim).filter(_.nonEmpty) match
      case Some(sid) => Right(TaskKey(sid))
      case None =>
        Left(
          "容器生命周期缺 taskKey：ToolContext.sessionId 为空（None/空白）⇒ 显式失败，不静默回落到全局容器（方案 §2.3：taskKey = ctx.sessionId）"
        )

/** 双 TTL 参数（**值待 D2 定值**——Q4 裁定：结构先按 D2-b 双闸落地）。
  *
  * 语义（方案 §2.3 L3，与业界 E2B【S8】/Modal【S9】/Daytona【S11】同型）：
  *  - `idleTtlMs`：空闲闸。命令流量/心跳**续期**（`acquire` / `heartbeat` 刷新活动时刻）；
  *  - `hardTtlMs`：墙钟闸。自创建起算，**无视状态**到点销毁；**续期只延 idle，不延 hard**。
  *
  * 若 D2 最终改判「单 TTL」（选项 a）：删掉与 hard 闸相关的一个计时器即可——两闸判据
  * 在本文件里各占一个独立条件（`sweep` 内 hard 分支 / idle 分支），互不嵌套。
  */
final case class ContainerTtl(
    idleTtlMs: Long = ContainerTtl.DefaultIdleTtlMs,
    hardTtlMs: Long = ContainerTtl.DefaultHardTtlMs
)

object ContainerTtl:
  /** **待 D2 定值**（方案 §3 D2-b 建议值：idle 15m / hard 2h）。 */
  val DefaultIdleTtlMs: Long = 15L * 60L * 1000L

  /** **待 D2 定值**（同上；D2 ④ 待作者按最长任务预期校准）。 */
  val DefaultHardTtlMs: Long = 2L * 60L * 60L * 1000L

/** 容器后端执行面（骨架期只有 create / destroy 两个副作用点）。
  *
  * `DockerBackend`（`docker run` / `docker rm -f`，见方案 §2.3 L1）属**后置批**——
  * 本批只落接缝 + 内存假后端。 */
trait ContainerBackend:
  def name: String

  /** 创建容器，`Right(containerId)`；`Left(可读原因)` = 显式失败（不静默回落宿主）。 */
  def create(taskKey: TaskKey): Either[String, String]

  /** 销毁容器（`docker rm -f` 等价：进程树全灭、零残留）。`Left` = 未能销毁。 */
  def destroy(containerId: String): Either[String, Unit]

object ContainerBackend:

  /** 内存假后端：**单测无需 docker、本机不起任何容器**（方案 §4 T6 目标③）。
    *
    * 记账两个计数 + 存活集合：供「TTL 窗口外零残留」断言（created == destroyed 且
    * `liveIds` 为空）——把 L9 的零残留口径钉在可观测读数上。 */
  final class Fake extends ContainerBackend:
    val name = "fake"

    private var nextSeq = 0
    private var createdCount = 0
    private var destroyedCount = 0
    private val live = scala.collection.mutable.LinkedHashMap.empty[String, TaskKey]

    def create(taskKey: TaskKey): Either[String, String] =
      nextSeq += 1
      val id = s"fake-${taskKey.value}-$nextSeq"
      live += (id -> taskKey)
      createdCount += 1
      Right(id)

    def destroy(containerId: String): Either[String, Unit] =
      if live.remove(containerId).isDefined then
        destroyedCount += 1
        Right(())
      else Left(s"容器 $containerId 不在本后端存活集合中（重复销毁或非本后端创建）")

    /** 存活容器 id（零残留断言用）。 */
    def liveIds: List[String] = live.keys.toList

    def created: Int = createdCount

    def destroyed: Int = destroyedCount

/** 生命周期对外形态（方案 §2.3 L2：`ensure/acquire/release/destroy` + taskKey 映射）。 */
trait ContainerLifecycle:

  def backendName: String

  def idleTtlMs: Long

  def hardTtlMs: Long

  /** 当前状态；无活动记录 = `Absent`（销毁后的稳态也是 `Absent`；`Destroy` 属**迁移态**，
    * 由 `stateTrail` 观测）。 */
  def stateOf(taskKey: TaskKey): Either[String, ContainerState]

  def snapshot(taskKey: TaskKey): Either[String, Option[ContainerRecord]]

  /** 状态迁移轨迹（含 `Absent` 起点与 `Destroy` 迁移态）——验收口径「覆盖
    * absent→active→retained→destroy 与重激活」的观测面。 */
  def stateTrail(taskKey: TaskKey): List[ContainerState]

  /** 确保容器可用：`absent` 创建（→`active`）/ `retained` 重激活复用暖容器（→`active`，
    * 容器 id 不变）/ `active` 幂等。 */
  def ensure(taskKey: TaskKey): Either[String, ContainerRecord]

  /** 取用（命令执行前）：`ensure` + 租约 +1 + 续期 idle 闸。 */
  def acquire(taskKey: TaskKey): Either[String, ContainerRecord]

  /** 归还（命令结束/任务终局）：租约 -1；归零 ⇒ `active`→`retained`（保留窗口倒计时起点）。 */
  def release(taskKey: TaskKey): Either[String, ContainerRecord]

  /** 心跳/命令续期：**只延 idle 闸**（`hard` 闸自创建起算，不受影响）。 */
  def heartbeat(taskKey: TaskKey): Either[String, ContainerRecord]

  /** 显式销毁 ⇒ `destroy`→`absent`（零残留）。 */
  def destroy(taskKey: TaskKey, reason: DestroyReason = DestroyReason.Explicit): Either[String, Unit]

  /** sweeper 纯逻辑一次性扫描（真实 5min 光纤属 L4 后置）：返回本次销毁的容器。
    *
    * 两闸独立判据：
    *  - hard 闸：`now - createdAtMs >= hardTtlMs` ⇒ 销毁（**无视状态**，active 照杀）；
    *  - idle 闸：`now - lastActivityMs >= idleTtlMs` ⇒ 销毁（命令/心跳续期可推迟）。
    */
  def sweep(nowMs: Long): List[ContainerDestroyed]

  /** 当前存活容器快照（观测面）。 */
  def live: List[ContainerRecord]

object ContainerLifecycle:

  /** `ctx.sessionId` → taskKey（方案 §2.3 映射；无可读会话 ⇒ 显式失败）。 */
  def taskKeyOf(ctx: ToolContext): Either[String, TaskKey] = TaskKey.fromSession(ctx.sessionId)

  /** 同名纯函数重载（不持有 ToolContext 的调用点用）。 */
  def taskKeyOf(sessionId: Option[String]): Either[String, TaskKey] = TaskKey.fromSession(sessionId)

  /** 缺省骨架实例（内存假后端 + 双 TTL 缺省值 + 系统时钟）。 */
  def inMemory(
      backend: ContainerBackend = new ContainerBackend.Fake,
      ttl: ContainerTtl = ContainerTtl(),
      clock: () => Long = () => System.currentTimeMillis()
  ): ContainerLifecycle = new InMemoryContainerLifecycle(backend, ttl, clock)

/** 生命周期快照（状态 + 双闸锚点 + 租约计数）。 */
final case class ContainerRecord(
    taskKey: TaskKey,
    containerId: String,
    state: ContainerState,
    createdAtMs: Long,
    lastActivityMs: Long,
    retainedAtMs: Option[Long],
    leases: Int
)

/** 一次销毁的事实记录（零残留断言与审计用）。 */
final case class ContainerDestroyed(
    taskKey: TaskKey,
    containerId: String,
    reason: DestroyReason,
    atMs: Long
)

/** 骨架实现：内存状态机（单线程语义；并发安全/上限驱逐属 L5 后置）。
  *
  * 副作用只经 `backend`；时间只经 `clock`（测试注入可控时钟 ⇒ 纯逻辑验红）。
  */
final class InMemoryContainerLifecycle(
    backend: ContainerBackend = new ContainerBackend.Fake,
    ttl: ContainerTtl = ContainerTtl(),
    clock: () => Long = () => System.currentTimeMillis()
) extends ContainerLifecycle:

  private val logger = NebflowLogger(getClass)

  private val records = scala.collection.mutable.LinkedHashMap.empty[TaskKey, ContainerRecord]
  private val trails = scala.collection.mutable.LinkedHashMap.empty[TaskKey, List[ContainerState]]

  val backendName: String = backend.name

  val idleTtlMs: Long = ttl.idleTtlMs

  val hardTtlMs: Long = ttl.hardTtlMs

  /** 迁移轨迹：首个迁移前先落 `Absent` 起点，销毁后补 `Absent`（稳态）。 */
  private def push(taskKey: TaskKey, st: ContainerState): Unit =
    val cur = trails.getOrElse(taskKey, List(ContainerState.Absent))
    trails.update(taskKey, cur :+ st)

  def stateOf(taskKey: TaskKey): Either[String, ContainerState] =
    Right(records.get(taskKey).map(_.state).getOrElse(ContainerState.Absent))

  def snapshot(taskKey: TaskKey): Either[String, Option[ContainerRecord]] = Right(records.get(taskKey))

  def stateTrail(taskKey: TaskKey): List[ContainerState] = trails.getOrElse(taskKey, Nil)

  def live: List[ContainerRecord] = records.values.toList

  def ensure(taskKey: TaskKey): Either[String, ContainerRecord] =
    records.get(taskKey) match
      case Some(rec) if rec.state == ContainerState.Active =>
        Right(rec) // 幂等：已 active 不重建（同 taskKey 复用暖容器）
      case Some(rec) =>
        // retained ⇒ 重激活：容器 id 不变（§2.3「重激活（复用，热容器）」）
        val now = clock()
        val reactivated = rec.copy(state = ContainerState.Active, retainedAtMs = None, lastActivityMs = now)
        records.update(taskKey, reactivated)
        push(taskKey, ContainerState.Active)
        Right(reactivated)
      case None =>
        backend.create(taskKey) match
          case Right(id) =>
            val now = clock()
            val fresh = ContainerRecord(taskKey, id, ContainerState.Active, now, now, None, 0)
            records.update(taskKey, fresh)
            push(taskKey, ContainerState.Active)
            Right(fresh)
          case Left(err) =>
            Left(s"容器创建失败（taskKey=${taskKey.value}）：$err")

  def acquire(taskKey: TaskKey): Either[String, ContainerRecord] =
    ensure(taskKey).flatMap { _ =>
      records.get(taskKey) match
        case Some(rec) =>
          val renewed = rec.copy(
            state = ContainerState.Active,
            retainedAtMs = None,
            lastActivityMs = clock(),
            leases = rec.leases + 1
          )
          records.update(taskKey, renewed)
          Right(renewed)
        case None => Left(s"acquire 失败：taskKey=${taskKey.value} 在 ensure 后仍无容器记录")
    }

  def release(taskKey: TaskKey): Either[String, ContainerRecord] =
    records.get(taskKey) match
      case None => Left(s"release 失败：taskKey=${taskKey.value} 无活动容器（absent）")
      case Some(rec) =>
        val now = clock()
        val leases = math.max(0, rec.leases - 1)
        val next =
          if leases == 0 then
            // 保留窗口起点 = 归还时刻（idle 闸从此刻起算）
            rec.copy(leases = 0, state = ContainerState.Retained, retainedAtMs = Some(now), lastActivityMs = now)
          else rec.copy(leases = leases, lastActivityMs = now)
        records.update(taskKey, next)
        if next.state == ContainerState.Retained then push(taskKey, ContainerState.Retained)
        Right(next)

  def heartbeat(taskKey: TaskKey): Either[String, ContainerRecord] =
    records.get(taskKey) match
      case None => Left(s"heartbeat 失败：taskKey=${taskKey.value} 无活动容器（absent）")
      case Some(rec) =>
        // 只刷新 lastActivityMs：createdAtMs 不动 ⇒ 只延 idle、不延 hard
        val renewed = rec.copy(lastActivityMs = clock())
        records.update(taskKey, renewed)
        Right(renewed)

  def destroy(taskKey: TaskKey, reason: DestroyReason = DestroyReason.Explicit): Either[String, Unit] =
    remove(taskKey, reason, clock()).map(_ => ())

  def sweep(nowMs: Long): List[ContainerDestroyed] =
    val due = records.values.toList.filter { rec =>
      nowMs - rec.createdAtMs >= hardTtlMs || nowMs - rec.lastActivityMs >= idleTtlMs
    }
    due.flatMap { rec =>
      val reason =
        if nowMs - rec.createdAtMs >= hardTtlMs then DestroyReason.HardTtlExpired
        else DestroyReason.IdleTtlExpired
      remove(rec.taskKey, reason, nowMs) match
        case Right(d) => Some(d)
        case Left(err) =>
          // 销毁失败 ⇒ 记录保留（不静默遗忘活容器），零残留断言因此会响
          logger.warnSync(err)
          None
    }

  /** 销毁并落轨迹：`Destroy`（迁移态）→ 记录移除 → 稳态 `Absent`。 */
  private def remove(taskKey: TaskKey, reason: DestroyReason, atMs: Long): Either[String, ContainerDestroyed] =
    records.get(taskKey) match
      case None => Left(s"destroy 失败：taskKey=${taskKey.value} 无活动容器（absent）")
      case Some(rec) =>
        backend.destroy(rec.containerId) match
          case Left(err) =>
            Left(s"容器销毁失败（taskKey=${taskKey.value}, id=${rec.containerId}）：$err —— 记录保留，不静默遗忘活容器")
          case Right(_) =>
            records.remove(taskKey)
            push(taskKey, ContainerState.Destroy)
            push(taskKey, ContainerState.Absent)
            Right(ContainerDestroyed(taskKey, rec.containerId, reason, atMs))
