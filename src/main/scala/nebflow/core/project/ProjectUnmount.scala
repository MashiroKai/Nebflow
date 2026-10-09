package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import nebflow.actor.{AgentEvent, AgentKind, AgentRecord}
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.*

/**
 * Project unmount 三步序（N2 project-tool 批 2026-10-06 · S17「先停任务、文件保留」+
 * ⑩A 软硬分档：unmount＝硬卸载、语义严于 archive）。
 *
 * == 三步序（作者令定稿口径，停→移→零删，次序硬约束） ==
 *
 *  ① '''在飞任务先停（cancel＋排空）'''——四处取消腿，全部走**既有取消原语**（零新造
 *     取消语义）：
 *    - '''链'''：非终态成员链（`FlowMapStore.allChainIds` ∩ 「任一成员非终态」∩ 台账
 *      状态 ≠ cancelled）→ `NodeEngine.cancelChainAndRecord`（级联闭包/逐节点终态化/
 *      聚合通知/台账 cancelled 审计全套零重写；source=User，与 REST 链控 cancel 腿
 *      同源——unmount 是显式人工动作）；
 *    - '''节点'''：快照内 Running 节点——有在飞 fiber → `cancelNodeById`（cancel 信号
 *      → 停 agent）；无 fiber（死会话）→ `reapStaleRunning`（收殓为 cancelled；引擎
 *      既有误杀防护：活会话节点它自己 Left 拒绝）；
 *    - '''dispatcher 会话'''：agentRegistry 中 kind=Flow ∧ project 归属本项目 ∧
 *      `dispatcher-` 前缀 → supervisor（会话观察桥）`AgentEvent.Cancelled`——桥的
 *      Cancelled 分支清 registry＋停 agent（与 AgentControl cancel 同一原语）；
 *    - '''内部 delegate 会话'''：agentRegistry 中 kind=Delegate ∧ `DelegateRegistry`
 *      归属本项目 → supervisor（BackoffSupervisor）`AgentEvent.Cancelled`（同
 *      AgentControl cancel 的 supervisor 正路）。'''"在飞 delegate" 的可归属性边界''':
 *      delegate 归属读 `DelegateRegistry.Record.project`——**外部执行器 delegate**
 *      （`external-*`，无进程内会话/supervisor）不在 cancel 面（杀进程树是 AgentControl
 *      external 腿的职责，不在本批擅自扩权），本步只 cancel 有进程内会话与 supervisor
 *      通道的内部 delegate。
 *    - '''排空'''：节点面等 flow-map 快照无 Running（硬要求——超时 ⇒ Left 中止，
 *      挂载面零触碰）；dispatcher/delegate 会话面等 agentRegistry 行消失（硬要求，
 *      超时 ⇒ Left 中止并点名幸存者）。中止即「未停完不卸」——三步序次序由失败路径
 *      也可回归（见 ProjectUnmountSpec「stop-before-remove」断言）。
 *
 *  ② '''挂载面移除'''——单点收敛：`ProjectStore.markUnmounted`（project.json 手术式
 *    原位插键 `unmounted/unmountedAt`，镜像 archive 先例）⇒ `ProjectStore.list()` 源头
 *    过滤（面板 GET /projects 与 startupMount 同源）⇒ 启动自动挂载跳过；然后运行时
 *    拆除：`ProjectRuntimeRegistry.unregister` + ProjectActor `Shutdown`（Behaviors.
 *    stopped，TTL 定时器随 actor 停）。
 *
 *  ③ '''工作区文件零删（硬约束）'''——本对象**零** `os.remove`/`os.move`/写工作区
 *    路径：workspace 与 `<dataRoot>/projects/<name>/` 目录逐字节保持（project.json 仅
 *    经 markUnmounted 插两键；flow-map/AGENTS.md/.nebflow/ 全保）。零删除回归 =
 *    ProjectUnmountSpec 逐文件 sha 对账。
 *
 * == 与 archive 的语义分档（⑩） ==
 *  - archive（软下线档，ProjectsRoutes POST archive 现语义不动）：仅打标、不停任务、
 *    零删除、幂等、单程；运行中 ProjectActor/会话**不**强制拆除。
 *  - unmount（硬卸载档，本对象）：先停后卸（①→②）、零删除、幂等（已卸载再卸 =
 *    no-op 报告）、单程（重挂需手工删两键，Project 工具 create 面显式拒绝）。
 *
 * == WS 事件 ==
 *  成功腿经**既有** `wsHub.broadcast` 通道（ctx.wsSend）广播一帧 `projectUnmounted`
 *  （帧形单点 = [[ProjectActor.projectUnmountedFrame]]，载荷镜像 projectArchived §D-2
 *  三键）——🔴 不自建第二套推送面。
 *
 * == 边界（#1229 排除件锚点） ==
 *  项目管理/挂载面属**常轨能力**，不随 #1229 引擎族拆除（spec §2.3 #1229 排除件
 *  条款）——本对象即该面的停/卸实施件；核对锚点 = ProjectUnmountSpec 头注断言。
 */
object ProjectUnmount:

  private val logger = NebflowLogger.forName("nebflow.project.unmount")

  /** 节点面排空上界（ms）：cancel 信号 → 节点终态化是异步 fiber，界内取不到 ⇒ 中止。 */
  val NodeDrainDeadlineMs: Long = 15000L

  /** 会话面（dispatcher/delegate）排空上界（ms）。 */
  val SessionDrainDeadlineMs: Long = 10000L

  /** 排空轮询步长（ms）。 */
  val DrainPollMs: Long = 250L

  /** 取消原因（进台账审计/任务文件/父通知的可读留痕）。 */
  val UnmountReason: String =
    "project unmount (Project tool action=\"unmount\"): in-flight work stopped first; workspace files are kept"

  /** unmount 结果读数（工具结果句与 spec 断言共用）。 */
  final case class UnmountReport(
    project: String,
    chainsCancelled: List[String] = Nil,
    nodesCancelled: List[String] = Nil,
    sessionsCancelled: List[String] = Nil,
    delegatesCancelled: List[String] = Nil,
    unmountedAt: Long = 0L,
    /** 幂等重卸（定义带 unmounted 标记且未挂载）⇒ true：零副作用，只回读既有时刻。 */
    alreadyUnmounted: Boolean = false
  )

  /**
   * 三步序入口。`name` 大小写不敏感解析到 canonical 名（与 ProjectRuntimeRegistry.get
   * 的 equalsIgnoreCase 兜底同源）。返回 Left(可行动错误) ⇒ 挂载面零触碰。
   *
   * @param name           项目名（canonical 化在本方法内完成）
   * @param agentRegistry  统一会话登记表（stop 步 dispatcher/delegate 腿 + 会话排空）
   * @param broadcastFrame 既有广播通道的投递函数（无连接上下文 = no-op，与 emit 面
   *                       fold(IO.unit) 同款语义；帧形由调用方经 projectUnmountedFrame 生产）
   */
  def unmount(
    name: String,
    agentRegistry: Ref[IO, Map[String, AgentRecord]],
    broadcastFrame: Json => IO[Unit]
  ): IO[Either[String, UnmountReport]] =
    ProjectStore.listAll().flatMap { defs =>
      defs.find(d => d.name.equalsIgnoreCase(name)) match
        case None =>
          IO.pure(Left(s"Project '$name' not found — nothing to unmount"))
        case Some(pd) =>
          val canonical = pd.name
          if pd.unmounted.contains(true) then
            // 幂等（镜像 archive「已归档 → 不重写文件，返回既有时刻」）。
            val at = pd.unmountedAt.getOrElse(0L)
            IO.pure(Right(UnmountReport(project = canonical, unmountedAt = at, alreadyUnmounted = true)))
          else
            ProjectRuntimeRegistry.get(canonical).flatMap {
              case None =>
                IO.pure(
                  Left(
                    s"Project '$canonical' is not mounted — nothing to unmount " +
                      "(unmount is a mount-face operation; an unmounted-but-untagged definition is left alone)"
                  )
                )
              case Some(rt) =>
                stopInFlight(canonical, rt, agentRegistry).flatMap {
                  case Left(err) => IO.pure(Left(err))
                  case Right(counts) =>
                    removeMountFace(canonical, rt, broadcastFrame).map {
                      case Left(err) => Left(err)
                      case Right(at) =>
                        Right(
                          UnmountReport(
                            project = canonical,
                            chainsCancelled = counts.chainsCancelled,
                            nodesCancelled = counts.nodesCancelled,
                            sessionsCancelled = counts.sessionsCancelled,
                            delegatesCancelled = counts.delegatesCancelled,
                            unmountedAt = at
                          )
                        )
                    }
                }
            }
          end if
    }

  // ============================================================
  // ① 在飞任务先停（cancel＋排空）
  // ============================================================

  /** stop 步的计数快照（成功腿进入 UnmountReport）。 */
  private final case class StopCounts(
    chainsCancelled: List[String],
    nodesCancelled: List[String],
    sessionsCancelled: List[String],
    delegatesCancelled: List[String]
  )

  private def stopInFlight(
    canonical: String,
    rt: ProjectRuntime,
    agentRegistry: Ref[IO, Map[String, AgentRecord]]
  ): IO[Either[String, StopCounts]] =
    for
      // ── 链腿（先链后节点：链级级联一次闭包，节点腿只兜零散 Running）──
      chains <- activeChainsToCancel(rt)
      _ <- chains.traverse_(cid => logger.info(s"[$canonical] unmount stop: cancelling chain $cid"))
      cancelledChains <- chains
        .traverse { cid =>
          rt.engine
            .cancelChainAndRecord(cid, CancelSource.User, UnmountReason)
            .attempt
            .map {
              case Right(Right(_)) => Some(cid)
              case other =>
                // 单成员链（CHAIN_SINGLE_MEMBER）/ 竞态消失（CHAIN_NOT_FOUND）→ 节点腿兜底，
                // 不因单链失败中止卸载（中止判据只在排空步）。
                logger.warn(s"[$canonical] unmount stop: chain $cid cancel skipped: ${other.toString.take(160)}")
                None
            }
        }
        .map(_.flatten)
      // ── 节点腿（链级联后现读快照；死会话走 reap 收殓，活会话走 cancel 信号）──
      snap <- rt.store.snapshot
      running = snap.nodes.values.filter(_.status == NodeLifecycle.Running).toList
      _ <- running.traverse_ { n =>
        rt.engine.isRunning(n.id).flatMap {
          case true => rt.engine.cancelNodeById(n.id)
          case false =>
            rt.engine
              .reapStaleRunning(
                n.id,
                reason = s"project unmount: dead running session finalized during the stop step ($canonical)",
                source = CancelSource.User
              )
              .void
        }
      }
      // ── 会话腿：dispatcher + 内部 delegate（supervisor 通道 = AgentControl 同原语）──
      recs <- agentRegistry.get
      dispatcherSessions = recs.values
        .filter(r =>
          r.kind == AgentKind.Flow &&
            r.project.exists(_.equalsIgnoreCase(canonical)) &&
            r.sessionId.startsWith(ProjectActor.DispatcherSessionPrefix)
        )
        .toList
      projectDelegateIds <- IO.blocking {
        DelegateRegistry.list().iterator.filter(_.project.exists(_.equalsIgnoreCase(canonical))).map(_.id).toSet
      }
      delegateSessions = recs.values
        .filter(r => r.kind == AgentKind.Delegate && projectDelegateIds.contains(r.sessionId))
        .toList
      _ <- (dispatcherSessions ++ delegateSessions).traverse_ { r =>
        logger.info(
          s"[$canonical] unmount stop: cancelling ${
              if r.kind == AgentKind.Flow then "dispatcher" else "delegate"
            } session ${r.sessionId}"
        ) *> r.supervisorRef.traverse_(sup => sup ! AgentEvent.Cancelled(r.sessionId, UnmountReason))
      }
      // ── 排空：节点面（硬要求）→ 会话面（硬要求）──
      nodeDrain <- drainNodes(rt)
      sessionDrain <- nodeDrain match
        case Left(err) => IO.pure(Left(err))
        case Right(_) => drainSessions(agentRegistry, dispatcherSessions ++ delegateSessions)
    yield sessionDrain.map(_ =>
      StopCounts(
        chainsCancelled = cancelledChains,
        nodesCancelled = running.map(_.id),
        sessionsCancelled = dispatcherSessions.map(_.sessionId),
        delegatesCancelled = delegateSessions.map(_.sessionId)
      )
    )
  end stopInFlight

  /** 待取消链集合：≥2 成员 ∧ 任一成员非终态 ∧ 台账状态 ≠ cancelled（暂停链也停）。 */
  private def activeChainsToCancel(rt: ProjectRuntime): IO[List[String]] =
    rt.store.allChainIds.flatMap(_.toList.filterA { cid =>
      rt.store.chainMembersOf(cid).flatMap {
        case None => IO.pure(false)
        case Some(cm) =>
          val hasOpen = cm.members.exists(n => !NodeLifecycle.Terminal.contains(n.status))
          if !hasOpen then IO.pure(false)
          else
            rt.store.chainLedgerStore.chainControlOf(cid).attempt.map {
              case Right(ctl) => ctl.status != ChainLedger.StatusCancelled
              case Left(_) => false // 台账读不到 → 不猜，留给节点腿
            }
      }
    })

  /** 节点面排空：轮询 flow-map 快照直至零 Running；超时 ⇒ Left（点名幸存者）。 */
  private def drainNodes(rt: ProjectRuntime): IO[Either[String, Unit]] =
    pollUntil(NodeDrainDeadlineMs) {
      rt.store.snapshot.map { s =>
        val left = s.nodes.values.filter(_.status == NodeLifecycle.Running).toList
        if left.isEmpty then Right(())
        else
          Left(
            s"unmount aborted (step 1 drain): ${left.size} node(s) still running after ${NodeDrainDeadlineMs}ms " +
              s"[${left.map(n => s"${n.name}(${n.id})").mkString(", ")}] — the mount face is untouched; " +
              "retry Project(action=\"unmount\") once the cancel signals settle"
          )
      }
    }

  /** 会话面排空：轮询 agentRegistry 直至目标会话行消失；超时 ⇒ Left（点名幸存者）。 */
  private def drainSessions(
    agentRegistry: Ref[IO, Map[String, AgentRecord]],
    sessions: List[AgentRecord]
  ): IO[Either[String, Unit]] =
    if sessions.isEmpty then IO.pure(Right(()))
    else
      val ids = sessions.map(_.sessionId).toSet
      pollUntil(SessionDrainDeadlineMs) {
        agentRegistry.get.map { reg =>
          val left = ids.filter(reg.contains)
          if left.isEmpty then Right(())
          else
            Left(
              s"unmount aborted (step 1 drain): ${left.size} session(s) still registered after ${SessionDrainDeadlineMs}ms " +
                s"[${left.mkString(", ")}] — the mount face is untouched; " +
                "retry Project(action=\"unmount\") once the cancel signals settle"
            )
        }
      }

  private def pollUntil[A](deadlineMs: Long)(step: IO[Either[String, A]]): IO[Either[String, A]] =
    val deadline = System.currentTimeMillis() + deadlineMs
    def loop(): IO[Either[String, A]] =
      step.flatMap {
        case right @ Right(_) => IO.pure(right)
        case left @ Left(_) =>
          if System.currentTimeMillis() >= deadline then IO.pure(left)
          else IO.sleep(DrainPollMs.millis) *> loop()
      }
    loop()

  // ============================================================
  // ② 挂载面移除（先打标过滤面，再拆运行时）
  // ============================================================

  private def removeMountFace(
    canonical: String,
    rt: ProjectRuntime,
    broadcastFrame: Json => IO[Unit]
  ): IO[Either[String, Long]] =
    ProjectStore.markUnmounted(canonical).flatMap {
      case Left(err) => IO.pure(Left(s"unmount aborted (step 2): $err"))
      case Right(at) =>
        for
          _ <- ProjectRuntimeRegistry.unregister(canonical)
          // ProjectActor Shutdown → Behaviors.stopped（TTL 定时器/tick 源随 actor 停）。
          _ <- rt.actorRef.traverse_(ref => ref ! ProjectActor.ProjectCommand.Shutdown)
          _ <- broadcastFrame(ProjectActor.projectUnmountedFrame(canonical, at)).handleErrorWith(e =>
            logger.warn(s"[$canonical] unmount: projectUnmounted broadcast failed: ${e.getMessage}")
          )
        yield Right(at)
    }

end ProjectUnmount
