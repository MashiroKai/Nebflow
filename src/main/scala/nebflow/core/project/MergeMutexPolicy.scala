package nebflow.core.project

import cats.effect.IO
import nebflow.core.NebflowLogger

import java.util.concurrent.ConcurrentHashMap

import scala.math.Ordering.Implicits.infixOrderingOps

/**
 * 合并窗 FIFO 互斥策略（**mergefifo-engine 批** 2026-09-13，作者 A-4 裁决收窄落地）。
 *
 * 真源（设计与规格的关系，逐字沿设计件 §11 A-8）：
 *   - 设计件（人类可读、决策依据）= mergefifo 设计件（内部留档）；
 *   - 规格件（引擎运行期消费 / 派发引用的唯一真源）= `.nebflow/Spec/20260913_merge-window-fifo.md`。
 *
 * 作者原话（逐字）：「**每个项目 git 目录下，只能同时有一个合并节点在工作。**」
 *
 * 本 object = 该语义的**纯判据 home**（与 [[MergeNodePolicy]] 同风格：纯函数，便于
 * 单测），IO/落痕/告警全部在 [[NodeEngine]] 的闸落点（三个，与既有 verdict 闸同构）。
 *
 * ── 语义（SEM-1…SEM-6 的可实现面）──────────────────────────────
 *   · SEM-1 单一临界区：同一**键**内任一刻至多一个 `merge=true` 且 `status=running`
 *     的节点（持有者 = 状态派生，无锁文件、无 TTL、无孤儿；终态写点自动释放）。
 *   · SEM-2 FIFO：授予序 = **到达序**（rank = `(readyAt, createdAt, id)` 升序）。
 *     「到达」= 已具备临界区资格（`in ∪ deps` 全终态）——**不是** createdAt 序，
 *     也不是完成序（#438 纪律：不得从「谁先完成」反推到达序）。
 *   · SEM-5 键与并行：键 = `realpath(git rev-parse --git-common-dir)`；**worktree 与
 *     主仓同键**（实测）；异键不互斥 ⇒ 跨项目/跨 git 目录必须可并行。
 *   · SEM-6 可见性：闸的停等与告警复用既有 `mount-stalled` 文案 + `merge-queue`
 *     事件（零新事件机制）。
 *
 * ── 键的口径（§3.1，唯一判据命令）────────────────────────────
 *   `KEY = realpath "$(git -C "<项目工作区>" rev-parse --git-common-dir)"`
 * 本实现取等价形态：git ≥ 2.31 用 `--path-format=absolute` 一步到位；输出为相对串
 * 时以 workspace 为基准解析；`realpath` 用 JVM 侧 `File.getCanonicalPath`（symlink
 * 解析 + 归一，且对不存在路径亦可用——不依赖外部 `realpath` 二进制）。非 git 目录 /
 * git 缺失 / 命令失败 ⇒ **键回落 workspace 本体**（确定性、可判红；不同工作区仍异键，
 * 不会把无关项目误判为同键 ⇒ 只会「自等」，不制造假互斥）。
 *
 * ── 与设计件 §7.2 的一处**显式收窄**（必须登记，禁默认一致）────────────
 * 设计 §7.2 的 `holders` = 「同键 merge 节点中的更高优先者（`running` 者恒优先；
 * 其余按 rank 严格小于者）」。本实现把「更高优先者」再加一条**准入过滤**：候选自身
 * 必须过 verdict 闸（其 `in ∪ deps` 的 verifier 上游当下全 `pass`）。理由三条：
 *   ① 设计的状态机（§7.2）把 verdict 闸排在互斥闸**之前**（`[verdict 闸] ──► [同一性闸]`）
 *      ⇒ 互斥闸只见「已过前闸」的候选；
 *   ② 否则一个被 verdict 判 fail 的队头会**永久堵住整条队列**（该上游不改判则后面
 *      全饿死）——那是本批新引入的死锁面，禁留；
 *   ③ O-2（`deps` 参与 verdict 闸）落地后两条闸的上游集完全同源，准入过滤零额外口径。
 * 其余逐字沿设计：rank 升序授予、`running` 恒优先、无竞争时零行为变化。
 */
object MergeMutexPolicy:

  private val logger = NebflowLogger.forName("nebflow.project.merge-mutex")

  /**
   * 键缓存：键是「工作区 → git 共同目录」的进程内不变量（一次 git 调用即可，且
   * 键的判定在每个 TtlTick（30 s）都会走到——不缓存会把 git 调用放进热路径）。
   * key 为工作区原值（调用方传参口径），value 为归一后的绝对键。
   */
  private val keyCache = new ConcurrentHashMap[String, String]()

  /**
   * 键判据（SEM-5）：`realpath(git-common-dir)`；见 object 头注的回落与缓存口径。
   * `runner` 可注入（spec stub；生产零传参 = [[CompletionGate.defaultRunner]] 的
   * 只读 `--no-optional-locks` 形态）。
   */
  def keyOf(
    workspace: String,
    runner: CompletionGate.GitRunner = CompletionGate.defaultRunner
  ): IO[String] =
    Option(keyCache.get(workspace)) match
      case Some(k) => IO.pure(k)
      case None =>
        runner(workspace, List("rev-parse", "--path-format=absolute", "--git-common-dir"))
          .map { res =>
            val raw = res match
              case Right(out) if out.trim.nonEmpty => resolveAgainst(workspace, out.trim)
              case _ => workspace
            val k = canonical(raw)
            keyCache.put(workspace, k)
            k
          }
          .handleErrorWith { e =>
            logger.warn(
              s"merge-mutex key resolution failed for workspace '$workspace': ${Option(e.getMessage).getOrElse(e.toString)} " +
                "— falling back to the workspace path itself"
            )
            val k = canonical(workspace)
            keyCache.put(workspace, k)
            IO.pure(k)
          }

  /** 相对串以 workspace 为基准解析（`--path-format=absolute` 不可用时的旧形态兜底）。 */
  private def resolveAgainst(workspace: String, raw: String): String =
    val f = new java.io.File(raw)
    if f.isAbsolute then raw else new java.io.File(workspace, raw).getPath

  /** realpath 等价（symlink 解析 + 归一；路径不存在时回落绝对归一形——键只需稳定与可比）。 */
  def canonical(path: String): String =
    val f = new java.io.File(path)
    try f.getCanonicalPath
    catch case _: Throwable => f.getAbsolutePath

  /**
   * 节点上游集（**in ∪ deps**，去重）：与 O-2 后的 verdict 闸上游集同源（单点）。
   *
   * ③（chainmodel 批一 2026-09-19）：`deps` 里的 `chain:<id>` 跨链引用（**纯调度闸**）
   * 展开为目标链**成员集**后再取 NodeDef —— 「整链完成才到达」因此进得了 `readyAt`
   * （到达时刻 = 目标链最晚成员终态时刻）与 `holders`（挡住我的候选）。
   *
   * 口径（登记）：本闸是**活动区单区**判据（`all` = 活动区节点表，与既有口径逐字一致），
   * 链引用也按该表解析 ⇒ 目标链的号在活动区视角下找不到时（该链最早成员已归档的
   * 「跨区续做」形态）本闸取不到成员、`readyAt` 会偏小；**启动面不受影响**——权威闸是
   * `NodeEngine.depsSatisfied`（`findNode` **双区**口径，链引用按合并集解析，未全终态恒
   * false）。该跨区口径差 = 既有 `upsOf`（单区）与 `depsSatisfied`（双区）差异的同一族，
   * 根治面 = 批二的链号台账（区无关的链号 → 成员表）。零链引用时逐字等于改造前行为。
   */
  def upsOf(n: NodeDef, all: Map[String, NodeDef]): List[NodeDef] =
    (n.in ++ FlowMapStore.resolveDepTargets(n.deps, all).ids).distinct.flatMap(all.get)

  /**
   * 到达时刻（SEM-2）：上游全终态 ⇒ `max(completedAt)`；否则 `Long.MaxValue`
   * （= 未到达，排在所有已到达者之后——让位不是阻断）。入口节点（无上游）= `createdAt`。
   * 上游终态但 `completedAt` 缺失（存量数据）⇒ 亦 `MaxValue`：宁可不授予，不插队。
   */
  def readyAt(n: NodeDef, ups: List[NodeDef]): Long =
    if ups.isEmpty then n.createdAt
    else if ups.exists(u => !NodeLifecycle.Terminal.contains(u.status)) then Long.MaxValue
    else ups.flatMap(_.completedAt).maxOption.getOrElse(Long.MaxValue)

  /** FIFO 次序键（SEM-2 平局键确定性）：`(readyAt, createdAt, id)` 升序。 */
  def rankOf(n: NodeDef, all: Map[String, NodeDef]): (Long, Long, String) =
    (readyAt(n, upsOf(n, all)), n.createdAt, n.id)

  /** 是否处于「可能进入临界区」的开态（终态 = 已释放 = 不再持有）。 */
  def isOpen(n: NodeDef): Boolean =
    n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring

  // ── 排队**位次**（queuepos 批 2026-09-15；作者现场报「所有节点前面还有 1 个」）──────
  //
  // 与 [[holders]] 的**分工**（🔴 两个量互不替代，禁混用）：
  //   · [[holders]] / `ahead` = **闸**的判据：挡住本节点的阻塞集合（`running` 者恒在内、
  //     开态先到者在内、再看 verdict 准入过滤）——它是「谁挡着我」，不是「我排第几」。
  //   · [[queuePositionOf]] = **队列序**：本节点在整个竞争集合里按 SEM-2 rank 的 1-based
  //     序数——**每一位互异**，与真实授予序（`(readyAt, createdAt, id)` 升序）逐点一致。
  // 真凶（考古件 `.nebflow/reports/20260915_queuepos-archaeo.md` §A②）：旧显示面把
  // `ahead`（阻塞集合的势）当位次渲染 ⇒ 同刻只有一个 `running` 时**全体排队者同显 1**
  // （「前面还有 1 个」看起来一样，排不出先后）；且载荷里**根本没有本节点位次与队列长度**。

  /**
   * 同队竞争者：仍可能进入临界区的 merge 节点（开态 `pending/wiring` ∪ `running`）。
   *
   * 判据三条（各排一类假竞争者）：
   *   - 终态（completed/failed/cancelled）⇒ 已释放，不再竞争；
   *   - `blocked` ⇒ 不自行启动（等人裁决），不参与 FIFO 竞争；
   *   - 非 merge 节点 ⇒ 与合并窗无关（闸是 merge-only）。
   * 未到达者（上游未全终态）**在内**：rank 首键 = `MaxValue` ⇒ 序数天然排在全队列之后
   * ——「位次」必须对全队列可读，否则未到达者又回到「无信息」（对齐交付件推荐口径：
   * 位次 = 全量同队 merge 节点按 rank 排序，并列暴露到达态 `arrived`）。
   */
  def isContender(n: NodeDef): Boolean =
    n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring ||
      n.status == NodeLifecycle.Running

  /**
   * 合并窗**队列名**（稳定 token；前端只翻译不派生）。队列身份的真源 = 本 object 的
   * 判据面（同键 = 同 git 目录；本项目内的竞争集合由 [[isContender]] 单点给出）。
   */
  val QueueName: String = "merge-window"

  /**
   * 队列**最少竞争者数**：少于该数 ⇒ 不构成队列。单飞 merge 节点不显示「排队中」
   * （「排队中」必须意味着「有同队竞争者」，否则是假陈述——正是 (e) 零显示态的反面）。
   */
  val QueueMinContenders: Int = 2

  /**
   * 排队位次槽（载荷 `mergeQueuePos` 每项；纯派生量，见 [[queuePosOf]]）。
   *
   * @param position 1-based 位次（在竞争集合中按 rank 升序的序数；已进入临界区者占首位
   *                 ⇒ 排队者的最小位次可为 2）
   * @param total    竞争集合大小（含已进入临界区者）
   * @param arrived  是否已到达（`readyAt` 有限 = `in ∪ deps` 全终态；未到达者排在队尾）
   * @param rankAt   rank 首键原文（`readyAt`；位次**可复算**的取证面）
   * @param createdAt rank 平局键之一（另一为 `id`，见载荷 `rank` 元数据）
   */
  final case class QueuePos(
    node: NodeDef,
    position: Int,
    total: Int,
    arrived: Boolean,
    rankAt: Long,
    createdAt: Long
  )

  /**
   * 排队位次（纯判据；`position` = 1 + 同队 rank 严格更小者数，`total` = 同队总数）。
   *
   * 真源 = [[rankOf]]（SEM-2：`(readyAt, createdAt, id)` 升序）——**唯一**次序真源，
   * 与闸的授予序同源；🔴 **不**读文件票层、**不**从事件流回放（票层是过渡期旧层、
   * 事件流是审计面，两者都不是次序真源）。
   *
   * @return `None` = 非 merge / 非开态（`running` 在临界区、终态已释放、`blocked` 不参与）
   *         / 竞争者不足 [[QueueMinContenders]]（不构成队列 ⇒ 禁显示「排队中」）。
   */
  def queuePositionOf(n: NodeDef, all: Map[String, NodeDef]): Option[(Int, Int)] =
    if !MergeNodePolicy.isMerge(n) || !isOpen(n) then None
    else
      val cs = all.valuesIterator.filter(o => MergeNodePolicy.isMerge(o) && isContender(o)).toList
      if cs.length < QueueMinContenders then None
      else
        val mine = rankOf(n, all)
        Some((cs.count(o => o.id != n.id && rankOf(o, all) < mine) + 1, cs.length))

  /** 位次 → 显示槽（逐项富化；`all` 与闸同源）。零副作用、零持久字段、**零闸改写**。 */
  def queuePosOf(n: NodeDef, all: Map[String, NodeDef]): Option[QueuePos] =
    queuePositionOf(n, all).map { case (position, total) =>
      val ra = readyAt(n, upsOf(n, all))
      QueuePos(n, position, total, arrived = ra != Long.MaxValue, rankAt = ra, createdAt = n.createdAt)
    }

  /**
   * 挡住本 merge 启动的**同键更高优先者**清单（纯判据；空 = 放行）。
   *
   * `all` = 本项目全部节点（同一项目 = 同一键：键按项目工作区派生，merge 节点不配
   * worktree）。候选判定两档：
   *   - `status=running` ⇒ **恒**为持有者（临界区正在被占）；
   *   - 开态（pending/wiring）且 rank 严格小于本节点 ⇒ 先到者优先（FIFO 不许插队）。
   * 未到达（上游未全终态）者 rank = `MaxValue` ⇒ 天然让位（不阻断）。
   * **准入过滤（verdict 闸）不在本函数**：由 [[NodeEngine]] 用既有 `mergeVerdictHolders`
   * 单点过滤（见 object 头注「显式收窄」）。
   *
   * 非 merge 节点恒空（闸是 merge-only——非 merge 节点行为零变化）。
   */
  def holders(n: NodeDef, all: Map[String, NodeDef]): List[NodeDef] =
    if !MergeNodePolicy.isMerge(n) then Nil
    else
      val mine = rankOf(n, all)
      all.valuesIterator
        .filter(o =>
          o.id != n.id && MergeNodePolicy.isMerge(o) &&
            (o.status == NodeLifecycle.Running || (isOpen(o) && rankOf(o, all) < mine))
        )
        .toList

  /**
   * 排队位次**显示槽**（engine-defects 批 #2/#227 「排队位次可见性」补件 2026-09-15）：
   * 纯派生量、**零持久字段**——载荷 `mergeQueue` 的每一项。
   *
   * 动因（真身 `.nebflow/flow-map-events.jsonl:5581`）：旧载荷只给 `{id,name,status}`，
   * 消费方需自行把 `status==running` 读成「在临界区」、把 `wiring|pending` 读成「在排队」；
   * 而引擎自己的停等文案对**开态**持有者也写「hold the critical section … (mechanism
   * guarantee, not a stall)」——同一份数据两种读法 ⇒ 作者 00:30 亲历「为什么现在没有
   * 节点在跑」无从判断。本槽把该判据**显式化**（判据持有方仍是引擎，禁消费方复刻）。
   *
   * @param rankAt   rank 首键 `readyAt`（= `max(上游 completedAt)`；未到达 = `MaxValue`）
   * @param createdAt rank 平局键之一（另一为 `id`，见载荷 `rank` 元数据）
   * @param inSection 是否**真的在临界区**（`status==running`）
   * @param notStartedReason 为何未点火：`in-critical-section` | `awaiting-handover`
   *        （R4「待承接」槽位非空 ⇒ **不会自行启动**） | `barrier-incomplete`
   *        （`in` 仍有未投递） | `queued`（仅 FIFO 顺位未到）
   */
  final case class QueueSlot(
    node: NodeDef,
    rankAt: Long,
    createdAt: Long,
    inSection: Boolean,
    notStartedReason: String
  )

  /**
   * 单个持有者为何尚未点火（**显示面与裁决面同源**的纯判据；零副作用）。
   * 优先级：真在临界区 > R4 待承接 > barrier 未齐 > 仅排队。
   */
  def notStartedReason(o: NodeDef): String =
    if o.status == NodeLifecycle.Running then "in-critical-section"
    else if o.pendingSuccession.nonEmpty then "awaiting-handover"
    else if o.in.exists(up => !o.deliveredTo.contains(up)) then "barrier-incomplete"
    else "queued"

  /**
   * 单个持有者 → 显示槽（纯映射；`all` 与闸同源）。
   *
   * 🔴 纪律：本函数只做「NodeDef → 槽」的**逐项富化**，**不得**自行出持有者集合——
   * 持有者集合的唯一真源是 [[MergeMutexPolicy.holders]] 经 [[NodeEngine.mergeQueueHolders]]
   * 的 verdict 准入过滤（闸与显示面同一判据，禁第二判据）。
   */
  def slotOf(o: NodeDef, all: Map[String, NodeDef]): QueueSlot =
    QueueSlot(o, readyAt(o, upsOf(o, all)), o.createdAt, o.status == NodeLifecycle.Running, notStartedReason(o))

  /** rank 次序键的**元数据**（载荷 `mergeQueue.rank`；对外冻结字面量，前端只读不派生）。 */
  val RankPrimary: String = "readyAt"
  val RankTiebreaks: List[String] = List("createdAt", "id")

  /**
   * 同键多项目告警判据（**O-1 已知缺口**的检测面，纯函数部分）：给定「本项目的键」
   * 与「其他项目的（名, 键）」对，返回与本项目同键的他项目名清单（升序去重）。
   * 语义：引擎侧持有者派生自**本项目 store** ⇒ 两项目共用同一 git 目录时**漏互斥**；
   * 本批不实现 claim/抢占（作者令：与「每个项目 git 目录」的字面范围外，要做须单列），
   * 只做「发生即告警」。
   */
  def sameKeyForeignProjects(mine: String, others: List[(String, String)]): List[String] =
    others.filter((_, k) => k == mine).map((name, _) => name).distinct.sorted
end MergeMutexPolicy
