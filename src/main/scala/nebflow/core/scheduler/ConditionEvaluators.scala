package nebflow.core.scheduler

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.io.Source

// ============================================================
// 任务条件评估器（2026-10-29 主动性①条件触发批——从闹钟到哨兵）
//
// 接口：引擎侧条件评估的可插拔面。到点先评估、为真才注入——条件不真零注入
// （空闲安静红线），顺延策略由调用方（ScheduledTaskService.deferTask）统一执行。
//
// 条件串按前缀分发（TaskConditionEvaluator.dispatch）：
//   "git-tip:<repoPath>[#<branch>]"   → GitTipChangeEvaluator（有状态哨兵）
//   "task-terminal:<project>:<nodeId>" → TaskTerminalEvaluator（流程图终态哨兵）
//   其余（无前缀）                     → ShellProbeEvaluator（sh -c 探针，兜底）
//
// 有状态评估器的工作记忆存 ScheduledTask.conditionState（引擎侧、随任务持久化，
// 重启不丢基线）；无状态评估器恒返回 nextState = None。
// ============================================================

/** 评估判定。Met = 条件为真（注入）；Unmet = 未满足（静默顺延，零注入）。 */
sealed trait ConditionVerdict
object ConditionVerdict:
  case object Met extends ConditionVerdict
  case object Unmet extends ConditionVerdict

/** 评估结果：判定 + 可选的新评估器状态（Some ⇒ 调用方持久化到 conditionState）。 */
case class ConditionOutcome(verdict: ConditionVerdict, nextState: Option[String])

trait TaskConditionEvaluator:

  /** 是否受理该条件串（前缀分发判据）。 */
  def handles(condition: String): Boolean

  /**
   * 评估条件。实现纪律：任何内部失败（探针超时/命令失败/文件缺失）一律按
   * Unmet 处理并打日志——条件面宁可沉默不可误醒（空闲安静红线）；同时
   * nextState 返回 None（失败不得推进有状态哨兵的基线）。
   */
  def evaluate(task: ScheduledTask, condition: String): IO[ConditionOutcome]

end TaskConditionEvaluator

object TaskConditionEvaluator:

  /** 前缀评估器链（顺序即优先级；无命中 ⇒ ShellProbeEvaluator 兜底）。 */
  val prefixed: List[TaskConditionEvaluator] =
    List(GitTipChangeEvaluator, TaskTerminalEvaluator)

  /** 兜底评估器：无前缀条件串 = 原样 `sh -c` 探针（2026-10-24 批的原始语义）。 */
  val fallback: TaskConditionEvaluator = ShellProbeEvaluator

  /** 条件串 → 评估器（纯函数，spec 可钉分发面）。 */
  def dispatch(condition: String): TaskConditionEvaluator =
    prefixed.find(_.handles(condition)).getOrElse(fallback)

end TaskConditionEvaluator

// ------------------------------------------------------------
// 兜底：sh -c 探针（退出码 0 = 条件为真）
// ------------------------------------------------------------

object ShellProbeEvaluator extends TaskConditionEvaluator:

  private val logger = NebflowLogger.forName("nebflow.scheduler.condition.shell")

  /** 硬超时（=未满足）：探针必须快；挂死的条件不能挂死调度循环。 */
  val ProbeTimeoutSecs: Long = 60

  override def handles(condition: String): Boolean = true // 兜底：恒受理

  override def evaluate(task: ScheduledTask, condition: String): IO[ConditionOutcome] =
    IO.blocking {
      val pb = new ProcessBuilder("sh", "-c", condition)
      pb.redirectErrorStream(true)
      val p = pb.start()
      val finished = p.waitFor(ProbeTimeoutSecs, java.util.concurrent.TimeUnit.SECONDS)
      if !finished then
        p.destroyForcibly()
        logger.warnSync(s"Condition probe timed out (${ProbeTimeoutSecs}s) — treated as unmet: ${condition.take(80)}")
        false
      else p.exitValue() == 0
    }
      .handleErrorWith(e =>
        logger.warn(s"Condition probe failed — treated as unmet: ${e.getMessage}").as(false)
      )
      .map(met => ConditionOutcome(if met then ConditionVerdict.Met else ConditionVerdict.Unmet, None))

end ShellProbeEvaluator

// ------------------------------------------------------------
// git tip 变化哨兵（有状态）：「分支上有新东西才叫」
//
// 条件串：git-tip:<abs-repo-path>[#<branch>]（branch 缺省 = HEAD）
// 语义：首次评估只建基线（记录当前 tip，不触发）；此后 tip 与基线不同 ⇒ 触发
// 并把基线推进到新 tip。真正的哨兵语义——武装之后的变化才叫醒，武装时刻
// 之前的既有状态不叫。
// ------------------------------------------------------------

object GitTipChangeEvaluator extends TaskConditionEvaluator:

  private val logger = NebflowLogger.forName("nebflow.scheduler.condition.git-tip")

  val Prefix = "git-tip:"

  /** rev-parse 超时：git 探针必须快（远端引用悬空/网络 fs 卡死不能拖垮调度）。 */
  val GitTimeoutSecs: Long = 30

  override def handles(condition: String): Boolean = condition.trim.startsWith(Prefix)

  override def evaluate(task: ScheduledTask, condition: String): IO[ConditionOutcome] =
    val spec = condition.trim.stripPrefix(Prefix)
    val (repo, branch) = spec.split("#", 2) match
      case Array(p) => (p.trim, "HEAD")
      case Array(p, b) => (p.trim, b.trim)
    currentTip(repo, branch).flatMap {
      case Right(tip) =>
        task.conditionState match
          case None =>
            // 建基线：首次见到只记录、不触发（哨兵只对武装后的变化负责）
            IO.pure(ConditionOutcome(ConditionVerdict.Unmet, Some(tip)))
          case Some(prev) if prev == tip =>
            IO.pure(ConditionOutcome(ConditionVerdict.Unmet, Some(prev)))
          case Some(prev) =>
            logger.info(s"git-tip sentinel fired for task ${task.id}: ${prev.take(12)} -> ${tip.take(12)}")
            IO.pure(ConditionOutcome(ConditionVerdict.Met, Some(tip)))
      case Left(err) =>
        logger.warn(s"git-tip probe failed — treated as unmet: $err").as(
          ConditionOutcome(ConditionVerdict.Unmet, None) // 失败不推进基线
        )
    }

  private def currentTip(repo: String, branch: String): IO[Either[String, String]] = IO.blocking {
    val pb = new ProcessBuilder("git", "-C", repo, "rev-parse", branch)
    pb.redirectErrorStream(true)
    val p = pb.start()
    val out = Source.fromInputStream(p.getInputStream).mkString.trim
    val finished = p.waitFor(GitTimeoutSecs, java.util.concurrent.TimeUnit.SECONDS)
    if !finished then
      p.destroyForcibly()
      Left(s"git rev-parse timed out (${GitTimeoutSecs}s): $repo#$branch")
    else if p.exitValue() != 0 then Left(s"git rev-parse failed (${p.exitValue()}): ${out.take(160)}")
    else if out.isEmpty then Left(s"git rev-parse returned empty output: $repo#$branch")
    else Right(out)
  }.handleErrorWith(e => IO.pure(Left(s"git probe error: ${e.getMessage}")))

end GitTipChangeEvaluator

// ------------------------------------------------------------
// 流程图任务终态哨兵：「指定的 delegate/任务节点收尾了才叫」
//
// 条件串：task-terminal:<project>:<nodeId>
// 数据源：引擎侧 <dataRoot>/projects/<project>/.nebflow/flow-map.json 的活动区
// 节点（只读文件面，不挂钩 FlowMapStore 实例生命周期——调度评估是旁路观察者）。
// 语义：节点 status ∈ {completed, failed, cancelled, blocked}（NodeLifecycle.Terminal）
// ⇒ Met；其余（running/pending/wiring/interrupted/不存在）⇒ Unmet 继续等。
// ------------------------------------------------------------

object TaskTerminalEvaluator extends TaskConditionEvaluator:

  private val logger = NebflowLogger.forName("nebflow.scheduler.condition.task-terminal")

  val Prefix = "task-terminal:"

  /** 终态集（与 nebflow.core.project.NodeLifecycle.Terminal 同口径的本地镜像——
    * 引包会拉进 project 层依赖，调度面只读字符串，spec 钉死四值。）。 */
  val TerminalStatuses: Set[String] = Set("completed", "failed", "cancelled", "blocked")

  override def handles(condition: String): Boolean = condition.trim.startsWith(Prefix)

  override def evaluate(task: ScheduledTask, condition: String): IO[ConditionOutcome] =
    condition.trim.stripPrefix(Prefix).split(":", 2).toList match
      case project :: nodeId :: Nil if project.trim.nonEmpty && nodeId.trim.nonEmpty =>
        nodeStatus(project.trim, nodeId.trim).flatMap {
          case Right(status) if TerminalStatuses.contains(status) =>
            logger.info(s"task-terminal sentinel fired for task ${task.id}: $project/$nodeId = $status")
            IO.pure(ConditionOutcome(ConditionVerdict.Met, None))
          case Right(status) =>
            IO.pure(ConditionOutcome(ConditionVerdict.Unmet, None)) // 非终态，继续等
          case Left(err) =>
            logger.warn(s"task-terminal probe failed — treated as unmet: $err").as(
              ConditionOutcome(ConditionVerdict.Unmet, None)
            )
        }
      case _ =>
        // 形态不对：恒 Unmet（一次性任务 24h 观察窗兜底转 PAUSED，不会无限转）
        logger.warn(s"Malformed task-terminal condition \"${condition.take(80)}\" — treated as unmet").as(
          ConditionOutcome(ConditionVerdict.Unmet, None)
        )

  /** 读活动区 flow-map.json，返回目标节点 status。文件缺失/节点不存在 = Left。 */
  private def nodeStatus(project: String, nodeId: String): IO[Either[String, String]] = IO.blocking {
    val f = PathUtil.dataRoot / "projects" / project / ".nebflow" / "flow-map.json"
    if !os.exists(f) then Left(s"flow map not found: $f")
    else
      parse(os.read(f)) match
        case Left(err) => Left(s"flow map parse failed: ${err.getMessage}")
        case Right(json) =>
          val status = for
            nodesObj <- json.hcursor.downField("nodes").focus.flatMap(_.asObject)
            node <- nodesObj(nodeId)
            status <- node.hcursor.get[String]("status").toOption
          yield status
          status.toRight(s"node \"$nodeId\" not found in flow map of \"$project\"")
  }.handleErrorWith(e => IO.pure(Left(s"task-terminal probe error: ${e.getMessage}")))

end TaskTerminalEvaluator
