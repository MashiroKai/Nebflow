package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.project.{DelegateFlowHosts, FlowMapEventLog, FlowMapStore}
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.concurrent.duration.*

/**
 * DelegateFlowMapLifecycle —— **delegate 作用域图生命周期**（双模式批 2026-10-28 ·
 * 设计 §6「flowmap 生命周期」；第 4 步实施件）。
 *
 * == 动机 ==
 * 分发器模式 delegate 的作用域图（`<基工作区>/.nebflow/flow-maps/<会话id>/`）在
 * 终态后永久留存（unmount 零盘面动作先例）——闸关时代是 resume/审计面，分发器
 * 模式下面板/台账面已有权威读数，盘面目录滞留 = 无主垃圾。本件给 dispatcher 腿
 * 的作用域目录一个**有序退场**：
 *
 *   ① **摘要先行**（宿主仍在册时，unmount 之前）：`sweepCompletedChainsDetailed`
 *      出库 + `chain-archived` 审计事件（ProjectActor.TtlTick 同款三件的宿主版）
 *      ——链级事实在宿主存活窗内落定，之后才卸实例；
 *   ② unmount（既有单点，零盘面动作——不变）；
 *   ③ **宽限删除**：后台 fiber 睡 `graceMs`（缺省 10min；sys prop
 *      `nebflow.delegate.flowmap.graceMs`）后校验双闸——**未重挂**（isMounted=false，
 *      grace 窗内的复活/续聊腿优先）∧ **已归档**（archivedAt 在——终态 autoArchive
 *      的盘面证据）⇒ 删作用域目录。任一闸不满足 = 跳过（审计面/复活面优先于整洁）。
 *
 * == 触发判据（单点）==
 * `applies` = 双模式总闸开 ∧ 属主 mode=dispatcher（[[DualModeConfig.enabledNow]] ×
 * registry mode 读面）——kernel/Inert 腿零触碰（kernel 无编排图诉求；闸关 = 现行为
 * 逐字节：unmount 后目录永存）。
 *
 * fail-soft 纪律：本件全部腿 best-effort——失败只 WARN，绝不拖垮终态清理序列
 * （onTerminalCleanup 各环同款）。
 */
object DelegateFlowMapLifecycle:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.flowmap-lifecycle")

  /** 宽限窗 sys prop（测试/运维旋钮）：`nebflow.delegate.flowmap.graceMs`。 */
  val SysPropGraceMs: String = "nebflow.delegate.flowmap.graceMs"

  /** 缺省宽限 = 10min（grace 窗内的复活/续聊/查图诉求优先于目录整洁）。 */
  val DefaultGraceMs: Long = 600_000L

  def graceMs: Long =
    sys.props.get(SysPropGraceMs).flatMap(v => scala.util.Try(v.toLong).toOption).getOrElse(DefaultGraceMs)

  /** 触发判据单点：总闸开 ∧ 属主 mode=dispatcher（读面 = DualModeConfig × registry）。 */
  def applies(record: Option[DelegateRegistry.Record]): Boolean =
    DualModeConfig.enabledNow && record.flatMap(_.mode).contains(DualModeConfig.ModeDispatcher)

  /** 作用域目录读数（与 DelegateFlowRuntime.scopeDir 同式——单一算式两处消费）。 */
  def scopeDirOf(baseWorkspace: String, delegateId: String): os.Path =
    os.Path(baseWorkspace, PathUtil.dataRoot) / ".nebflow" / FlowMapStore.FlowMapsDirName / delegateId

  /** 存储锚（mount 时 baseWorkspace = workspace 参数）：worktree 腿 = 项目工作区；就地腿 = cwd。 */
  def baseWorkspaceOf(record: Option[DelegateRegistry.Record]): Option[String] =
    record.map(r => r.projectWorkspace.getOrElse(r.cwd))

  /**
   * ① 摘要先行（onTerminalCleanup 序列内、unmount 之前消费）：宿主在册 ⇒ 链级
   * sweep 出库（节点移除/WS 面由 sweep 既有链承接）+ 逐链 `chain-archived` 审计
   * 事件（TtlTick 同式：chainId 承载 + 结构化 summary）。未挂载 = 零动作（幂等）。
   */
  def flushBeforeUnmount(delegateId: String): IO[Unit] =
    DelegateFlowHosts.get(delegateId).flatMap {
      case None => IO.unit
      case Some(rt) =>
        rt.store.sweepCompletedChainsDetailed(System.currentTimeMillis()).flatMap { swept =>
          if swept.isEmpty then IO.unit
          else
            logger
              .info(
                s"[flowmap-lifecycle] pre-unmount flush for '$delegateId': ${swept.size} chain(s) archived (${swept.map(_.chainId).mkString(", ")})"
              )
              .as(()) *>
              swept.traverse_ { c =>
                FlowMapEventLog
                  .append(
                    rt.baseWorkspace,
                    rt.engine.projectName,
                    c.nodeId,
                    FlowMapEventLog.ChainArchivedType,
                    FlowMapEventLog.chainArchivedSummary(c.chainId, c.archivedAt, c.members),
                    Some(c.chainId)
                  )
                  .handleErrorWith(e =>
                    IO(logger.warn(s"[flowmap-lifecycle] chain-archived audit append failed (${c.chainId}): ${e.getMessage}"))
                  )
              }
        }
          .handleErrorWith(e =>
            IO(logger.warn(s"[flowmap-lifecycle] pre-unmount sweep failed for '$delegateId': ${e.getMessage}"))
          )
    }

  /**
   * ③ 宽限删除腿（后台 fiber 本体）：睡 graceMs → 双闸校验 → 删作用域目录。
   * 双闸：未重挂（grace 窗内的复活/续聊腿赢）∧ 已归档（终态盘面证据）。调用方
   * 负责判 `applies`（本件不重复判——fiber 启动点即触发判据点）。
   */
  def scheduleScopeRemoval(delegateId: String): IO[Unit] =
    IO.sleep(graceMs.millis) *>
      DelegateFlowHosts.isMounted(delegateId).flatMap {
        case true =>
          IO(
            logger.info(
              s"[flowmap-lifecycle] scope '$delegateId' is mounted again within the grace window — scope dir removal skipped"
            )
          ).void
        case false =>
          val rec = DelegateRegistry.find(delegateId)
          val baseOpt = baseWorkspaceOf(rec)
          (rec.flatMap(_.archivedAt), baseOpt) match
            case (Some(_), Some(base)) =>
              val dir = scopeDirOf(base, delegateId)
              IO.blocking(os.remove.all(dir)).attempt.flatMap {
                case Right(_) =>
                  IO(logger.info(s"[flowmap-lifecycle] scope dir removed after grace window: $dir"))
                case Left(e) =>
                  IO(logger.warn(s"[flowmap-lifecycle] scope dir removal failed for '$delegateId': ${e.getMessage}"))
              }
            case _ =>
              IO(
                logger.info(
                  s"[flowmap-lifecycle] scope '$delegateId' not safely removable (archived=${rec.flatMap(_.archivedAt).isDefined}, baseWorkspace known=${baseOpt.isDefined}) — removal skipped"
                )
              ).void
      }
end DelegateFlowMapLifecycle
