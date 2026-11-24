package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.project.*
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
 *      ——链级事实在宿主存活窗内落定，之后才卸实例；随后**在飞链收口**（R3 批
 *      2026-10-29 · 审计二 P3/P10 根因修复）：终态时仍有非终态成员的链在台账上
 *      落 `cancelled` 处置（与 NodeCanceller 链级取消同一个写原语）+ `chain-cancelled`
 *      审计事件——「取消链未走归档清扫→账实不一致」的机制封口：退役图内每条链
 *      都有确定性归宿（archived ∨ cancelled），台账不再滞留 `active` 谎报；
 *      **blocked 永不自动处置**（含 blocked 成员的链零触碰——人工裁定域）；
 *      ② unmount（既有单点，零盘面动作——不变）；
 *   ③ **宽限退役（迁移而非删除）**：后台 fiber 睡 `graceMs`（缺省 10min；sys prop
 *      `nebflow.delegate.flowmap.graceMs`）后校验——**未重挂**（isMounted=false，
 *      grace 窗内的复活/续聊腿优先）∧ **终态证据**（终态腿 = archivedAt 在 ∧
 *      盘面静默复核；孤儿腿 = 盘面静默复核——两腿同过 `scopeQuietRecheck` 双保险）
 *      ⇒ 复用既有 `flow-map-archive/` 归档通道把迟到完成扫进档、**目录保留**
 *      （不整目录删除）。任一闸不满足 = 跳过（审计面/复活面优先于整洁）。
 *
 * == 触发判据（单点）==
 * `applies` = 属主 mode=dispatcher（registry mode 读面）——kernel/存量腿零触碰
 * （kernel 无编排图诉求）。双模式总闸已于 delegate-param-decisions 批（2026-11-04）
 * 退役：模式由 `Delegate` 工具参数决定。
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

  /**
   * 触发判据单点：属主 mode=dispatcher（读面 = registry）。双模式总闸已于
   * delegate-param-decisions 批（2026-11-04）退役——模式由 `Delegate` 工具参数
   * 决定，不再有闸门条件。
   */
  def applies(record: Option[DelegateRegistry.Record]): Boolean =
    record.flatMap(_.mode).contains(DualModeConfig.ModeDispatcher)

  /** 作用域目录读数（与 DelegateFlowRuntime.scopeDir 同式——单一算式两处消费）。 */
  def scopeDirOf(baseWorkspace: String, delegateId: String): os.Path =
    os.Path(baseWorkspace, PathUtil.dataRoot) / ".nebflow" / FlowMapStore.FlowMapsDirName / delegateId

  /** 存储锚（mount 时 baseWorkspace = workspace 参数）：worktree 腿 = 项目工作区；就地腿 = cwd。 */
  def baseWorkspaceOf(record: Option[DelegateRegistry.Record]): Option[String] =
    record.map(r => r.projectWorkspace.getOrElse(r.cwd))

  /**
   * ① 摘要先行（onTerminalCleanup 序列内、unmount 之前消费；孤儿收割卸载腿同源复用）：
   * 宿主在册 ⇒ 三段收口，段序即语义（出库 → 在飞链处置 → 台账收敛），全部完成才轮到
   * unmount——「图谱可考古，图没了账还在」的落地面。未挂载 = 零动作（幂等）。
   *
   *   - **a. 链级出库**：`sweepCompletedChainsDetailed` 出库（节点移除/WS 面由 sweep
   *     既有链承接；台账 Archived 翻转在 sweep 内同刻绑定）+ 逐链 `chain-archived`
   *     审计事件（TtlTick 同式：chainId 承载 + 结构化 summary）。
   *   - **b. 在飞链收口**（R3 批 · 取消链同一收口路径）：终态时仍有非终态成员的链，
   *     台账落 `cancelled` 处置（[[ChainLedgerStore.setChainControl]]——链级取消的
   *     **同一个**写原语）+ `chain-cancelled` 审计事件落基工作区；**节点盘面零触碰**
   *     （复活判据「座位/盘面在场」优先——boot sweep 认领面不因退役收口翻状态）。
   *     **blocked 永不自动处置**：含 blocked 成员的链整链跳过（人工裁定域，与 sweep
   *     的「blocked 永不自动归档」同一条纪律的退役侧镜像）。
   *   - **c. 台账收敛**：`reconcileChainLedger` 收尾一拍（既有 TtlTick ⑤ 同款）——
   *     改号/退役/压缩在退役窗内走完，台账热态以自洽态随目录退役。
   *
   * @param cancelAuthorized 取消授权（父会话终态收口单点 2026-11-12 · 误回收修复批）：
   *   **只有明确取消墓碑（`cancelRequestedAt` 在案）才构成对仍在飞链的 cancelled
   *   收口授权**。自然完成/普通归档不是取消授权——在飞链不再被台账假取消（台账
   *   改写 ≠ 底层 actor 死亡；假取消记录会切断后代的可信归属/待结算证据）。缺省
   *   `true` = 既有调用面（孤儿收割腿——宿主已不活，收口是结算记录而非假杀）逐字节
   *   不变；DelegateTerminal 终态收口按墓碑在案与否传值。
   */
  def flushBeforeUnmount(delegateId: String, cancelAuthorized: Boolean = true): IO[Unit] =
    DelegateFlowHosts.get(delegateId).flatMap {
      case None => IO.unit
      case Some(rt) =>
        rt.store
          .sweepCompletedChainsDetailed(System.currentTimeMillis())
          .flatMap { swept =>
            val sweepAudit =
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
                        logger.warn(
                          s"[flowmap-lifecycle] chain-archived audit append failed (${c.chainId}): ${e.getMessage}"
                        )
                      )
                  }
            sweepAudit *> finalizeAbandonedChains(delegateId, cancelAuthorized) *> ledgerConverge(delegateId)
          }
          .handleErrorWith(e =>
            logger.warn(s"[flowmap-lifecycle] pre-unmount sweep failed for '$delegateId': ${e.getMessage}")
          )
    }

  /**
   * ①-b 在飞链收口（R3 批 2026-10-29 · 审计二 §3.4-②「4 条 active vs 0 节点」的
   * 机制根因修复）：退役图内「非终态成员仍在 ∧ 无 blocked 成员」的链，台账控制态
   * 落 `cancelled` + `chain-cancelled` 审计事件。盘面节点零触碰（复活窗安全）；
   * sweep 已出库的链不再入选（活动区已无其成员）。
   *
   * 终态收口单点批（2026-11-12 · 误回收修复）：收口授权 = `cancelAuthorized`（父
   * 会话明确取消墓碑在案）。自然完成/普通归档不是取消授权 ⇒ 在飞链零收口——台账
   * 保持真实状态（仍在飞 = 仍 active），不写 chain-cancelled 审计（台账改写 ≠
   * 底层 actor 死亡；假取消会切断后代的可信归属/待结算证据）。
   */
  private[delegate] def finalizeAbandonedChains(delegateId: String, cancelAuthorized: Boolean): IO[Unit] =
    if !cancelAuthorized then IO.unit
    else
      DelegateFlowHosts.get(delegateId).flatMap {
        case None => IO.unit
        case Some(rt) =>
          for
            s <- rt.store.snapshot
            a <- rt.store.archiveSnapshot
            now = System.currentTimeMillis()
            chains = FlowMapStore.topologicalChains(s.nodes.values ++ a.nodes.values)
            abandoned = chains.filter { c =>
              val activeMembers = c.memberIds.flatMap(s.nodes.get(_))
              activeMembers.exists(m => NodeLifecycle.ChainCancelScope.contains(m.status)) &&
              !activeMembers.exists(_.status == NodeLifecycle.Blocked) // blocked 永不自动处置
            }
            _ <- abandoned.traverse_ { c =>
              val members = c.memberIds.toList.sorted
              val openCount =
                members.count(id => s.nodes.get(id).exists(n => NodeLifecycle.ChainCancelScope.contains(n.status)))
              val reason =
                "delegate terminal closure (flowmap retirement): chain still in flight when the dispatcher session ended"
              rt.store.chainLedgerStore
                .setChainControl(c.id, ChainLedger.StatusCancelled, now)
                .attempt
                .void
                .handleErrorWith(e =>
                  logger.warn(s"[flowmap-lifecycle] retire cancel-mark failed (${c.id}): ${e.getMessage}")
                ) *>
                FlowMapEventLog
                  .append(
                    rt.baseWorkspace,
                    rt.engine.projectName,
                    c.memberIds.headOption.getOrElse(""),
                    FlowMapEventLog.ChainCancelledType,
                    FlowMapEventLog.chainCancelledSummary(
                      c.id,
                      CancelSource.Engine,
                      reason,
                      cancelled = openCount,
                      preserved = members.size - openCount,
                      skipped = 0,
                      memberIds = members
                    ),
                    Some(c.id)
                  )
                  .handleErrorWith(e =>
                    logger.warn(s"[flowmap-lifecycle] chain-cancelled audit append failed (${c.id}): ${e.getMessage}")
                  )
            }
            _ <-
              if abandoned.isEmpty then IO.unit
              else
                logger.info(
                  s"[flowmap-lifecycle] retire cancel-closure for '$delegateId': ${abandoned.size} in-flight chain(s) marked cancelled " +
                    s"(${abandoned.map(_.id).mkString(", ")})"
                )
          yield ()
      }

  /** ①-c 台账收敛拍（TtlTick ⑤ 同款；退役窗内最后一拍——失败只 WARN 不回滚）。 */
  private def ledgerConverge(delegateId: String): IO[Unit] =
    DelegateFlowHosts.get(delegateId).flatMap {
      case None => IO.unit
      case Some(rt) =>
        rt.store
          .reconcileChainLedger(System.currentTimeMillis())
          .handleErrorWith(e =>
            logger.warn(s"[flowmap-lifecycle] retire ledger reconcile failed for '$delegateId': ${e.getMessage}")
          )
          .void
    }

  /**
   * ③ 宽限退役腿（后台 fiber 本体）：睡 graceMs → 三闸校验 → **迁移式退役**。
   * 闸序：
   *   ① 未重挂（grace 窗内的复活/续聊腿赢）；
   *   ② 终态证据——**两条腿都过盘面静默复核**（终态腿 = **状态派生收口读数**
   *      [[DelegateVisibility.Reading.identityClosed]]（轮次结算 ∨ 存量归档旗；阶段②
   *      起归档写点退役）∧ `scopeQuietRecheck`；孤儿腿 = `scopeQuietRecheck`）：复核不过
   *      （复活窗重挂 / 盘面仍有非终态 / blocked 在场）= 跳过——审计面/复活面优先于整洁；
   *   ③ 迁移而非删除：复用既有 `flow-map-archive/` 归档通道（openScope →
   *      sweepCompletedChainsDetailed——迟到的已完成链出库进归档分文件），
   *      **作用域目录保留**（健康退役目录的形态：flow-map.json + 台账 + journal +
   *      flow-map-archive/ + results/）。退役窗内到点的迟到完成由此进档可考，
   *      绝不再整目录删除。
   * 调用方负责判 `applies`（本件不重复判——fiber 启动点即触发判据点）。
   */
  def scheduleScopeRemoval(delegateId: String, orphanLeg: Boolean = false): IO[Unit] =
    IO.sleep(graceMs.millis) *>
      DelegateFlowHosts.isMounted(delegateId).flatMap {
        case true =>
          logger.info(
            s"[flowmap-lifecycle] scope '$delegateId' is mounted again within the grace window — scope retirement skipped"
          )
        case false =>
          val rec = DelegateRegistry.find(delegateId)
          val baseOpt = baseWorkspaceOf(rec)
          // Both legs pass the on-disk quiet recheck: the terminal leg keeps its
          // state-derived settlement evidence (阶段②：收口证据单点 = DelegateVisibility；
          // 归档旗写点已退役——「已归档」这一事实由轮次结算 ∨ 存量归档旗现读复算)
          // AND gains the same double-check the orphan leg has (defense in depth —
          // the completion gate owns the primary judgment).
          val identityClosed = DelegateVisibility.readOf(delegateId).identityClosed
          val terminalEvidence: IO[Boolean] =
            if orphanLeg then scopeQuietRecheck(baseOpt, delegateId)
            else
              IO.pure(identityClosed).flatMap {
                case false => IO.pure(false)
                case true => scopeQuietRecheck(baseOpt, delegateId)
              }
          terminalEvidence.flatMap {
            case true if baseOpt.isDefined =>
              migrateScopeDir(baseOpt.get, delegateId, rec.flatMap(_.project))
            case _ =>
              logger.info(
                s"[flowmap-lifecycle] scope '$delegateId' not safely retirable (orphanLeg=$orphanLeg, " +
                  s"identityClosed=$identityClosed, baseWorkspace known=${baseOpt.isDefined}) — " +
                  "retirement skipped, scope dir kept"
              )
          }
      }

  /**
   * 迁移式退役（替换原 `os.remove.all` 整删）：以挂载同式打开作用域存储，把迟到的
   * 已完成链扫进既有 `flow-map-archive/` 归档分文件（含台账 Archived 翻转——
   * sweep 内同刻绑定）+ `chain-archived` 审计事件，目录与全部历史件**原样保留**。
   * 任何失败只 WARN（fail-soft，绝不拖垮也绝不破坏盘面）。
   */
  private def migrateScopeDir(base: String, delegateId: String, project: Option[String]): IO[Unit] =
    val projectName = project.getOrElse(os.Path(base).last) // 挂载同式（general 腿 = 座位名）
    FlowMapStore
      .openScope(delegateId, projectName, base)
      .flatMap { store =>
        store.sweepCompletedChainsDetailed(System.currentTimeMillis()).flatMap { swept =>
          swept.traverse_ { c =>
            FlowMapEventLog
              .append(
                base,
                projectName,
                c.nodeId,
                FlowMapEventLog.ChainArchivedType,
                FlowMapEventLog.chainArchivedSummary(c.chainId, c.archivedAt, c.members),
                Some(c.chainId)
              )
              .handleErrorWith(e =>
                logger.warn(
                  s"[flowmap-lifecycle] retire chain-archived audit append failed (${c.chainId}): ${e.getMessage}"
                )
              )
          } *>
            logger.info(
              s"[flowmap-lifecycle] scope '$delegateId' retired by MIGRATION (dir kept): " +
                s"${swept.size} late chain(s) archived into flow-map-archive/; on-disk history preserved"
            )
        }
      }
      .handleErrorWith(e =>
        logger.warn(s"[flowmap-lifecycle] scope migration failed for '$delegateId' (dir kept as-is): ${e.getMessage}")
      )

  end migrateScopeDir

  /**
   * 孤儿腿的盘面静默复核（删除前的最后闸）：目录在 ⇒ 探针式只读 open（ChainsReadFace
   * 同款守卫——防读面建目录副作用）⇒ 活动区无非终态（pending/wiring/running/
   * interrupted——blocked 是人工裁定域，同样拦删）⇒ 真。目录已不在（并发清理/从未建）
   * = 真空态，删除无事可做 ⇒ 真（os.remove.all 幂等）；读失败 = 保守假（宁留勿删）。
   */
  private def scopeQuietRecheck(baseOpt: Option[String], delegateId: String): IO[Boolean] =
    baseOpt.fold(IO.pure(true)) { base =>
      IO.blocking {
        val dir = scopeDirOf(base, delegateId)
        if os.exists(dir) && os.isDir(dir) then Some(dir) else None
      }.flatMap {
        case None => IO.pure(true)
        case Some(_) =>
          FlowMapStore
            .openScope(delegateId, delegateId, base)
            .flatMap(
              _.snapshot.map(s => !s.nodes.values.exists(n => NodeLifecycle.ChainCancelScope.contains(n.status)))
            )
            .handleErrorWith(e =>
              logger
                .warn(
                  s"[flowmap-lifecycle] orphan recheck failed for '$delegateId' — keeping the dir: ${e.getMessage}"
                )
                .as(false)
            )
      }
    }
end DelegateFlowMapLifecycle
