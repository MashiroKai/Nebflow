package nebflow.core.project

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.all.*
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.{Defaults, NebflowLogger}

/**
 * ProjectCrashRecovery —— boot-time 崩溃断点恢复 sweep（crash-recovery 批 2026-09-07，
 * 设计 boot-time-crash-recovery 设计件 §3）。
 *
 * 挂载点（GatewayMain boot 链）：`startupMount *> projectCrashSweep *> projectTtlScanner`
 * ——快段（分类+认领）同步秒级完成、必须先于 TtlTick 首拍（+30s）：被认领节点即刻
 * 翻 Pending 脱离 watchdog（settleStaleRunningNodes 只看 Running）判死口径，竞速由
 * 挂载顺序结构性关闭；慢段（rehydrate spawn + LLM 续跑，分钟级）fork 异步，不阻塞
 * server listen。
 *
 * 两段分工：
 *  - 快段（每项目串行、同步）：snapshot → status==Running-**或 interrupted** 候选 →
 *    逐节点 NodeEngine.bootRecoveryClaim 三分类认领（(a)/(b) 翻 Pending 入队 /
 *    (c) failNode）→ 有动作则项目级汇总通知恰一条（裁定④：TriggerDispatcher 通道直发，
 *    幂等 =「每 boot 每项目至多一条」结构性保证——boot 只发生一次，单点发送无记账）。
 *    （interrupted = 优雅关机钩子翻态的非终态，中断恢复语义批 2026-09-13 spec §2.4
 *    ——与 kill -9 的 Running 残留同链续跑，零新机制。）
 *  - 慢段（fork）：(a)/(b) 节点逐个 NodeEngine.bootRecoveryStart（startNode(resume)
 *    复用 runWithAgent/runLoopNode 全链，R8 禁手写 spawn）；RecoveryConcurrency 信号
 *    量（默认 3，裁定④）——节点会话终态才释放槽位，大项目 N 节点恢复的 LLM 洪峰护栏。
 *
 * 降级链（R4「恢复优先、收殓兜底」）：sweep 节点级/项目级异常均不拖垮 boot——未认领
 * 残余 Running 由 watchdog（TtlTick settleStaleRunningNodes）收敛 failed；认领后慢段
 * 失败的节点回归正常 pending 池由资格回扫新鲜启动（恢复降级为重跑，不悬挂）。
 *
 * 回滚总开关：Defaults.CrashRecoveryEnabled（system prop `nebflow.crashRecovery.enabled`，
 * 默认 true）——false 时 GatewayMain 不挂本 sweep 且启动挂载恢复既有僵尸收殓（cancelled），
 * 完全回到本批前现状（设计 R1）。
 */
object ProjectCrashRecovery:
  private val logger = NebflowLogger.forName("nebflow.project.crash-recovery")

  /**
   * GatewayMain boot 链挂接入口：对全部已挂载项目跑快段 + fork 慢段。
   * 返回有恢复动作的项目数（>0 时调用方记 info）。项目级 handleErrorWith——单项目
   * 异常不拖垮其他项目，该项目残余 Running 由 watchdog 兜底（R4）。
   * 排除表命中（`Defaults.SweepExcludeProjects`，exclusivity 批 2026-10-15）的项目
   * 整体跳过（零认领零通知零 rehydrate）＋一行 `excluded-by-config` info 日志。
   * trigger 为测试接缝（recoverProject 同款纪律；生产调用点零传参）。
   */
  def recoverAll(
    concurrency: Int = Defaults.CrashRecoveryConcurrency,
    trigger: Option[String => IO[Unit]] = None
  ): IO[Int] =
    ProjectRuntimeRegistry.all.flatMap { rts =>
      // 排除表（exclusivity 批 2026-10-15，双活根治一）：热读一次、逐项目精确匹配——
      // 命中者整项目跳过（快段零认领零通知、慢段零 rehydrate），记一行
      // `excluded-by-config`（可观测；boot-wake 腿对同一张表同码零触碰跳过，log-only）。
      val excluded = Defaults.SweepExcludeProjects
      rts
        .traverse { rt =>
          if excluded.contains(rt.project.name) then
            logger
              .info(
                s"[boot-recovery] project '${rt.project.name}' skipped: excluded-by-config " +
                  s"(sweep exclusion table nebflow.sweep.excludeProjects — project owned by another instance)"
              )
              .as(0)
          else
            recoverProject(rt, concurrency, trigger)
              .handleErrorWith(e =>
                logger
                  .error(
                    s"[boot-recovery] project '${rt.project.name}' sweep failed: ${Option(e.getMessage).getOrElse(e.toString)} — residual running nodes left to the dead-session watchdog"
                  )
                  .as(0)
              )
          end if
        }
        .map(_.count(_ > 0))
    }

  /**
   * 停机成因标注（hostresume 批 2026-09-22，设计卡 §4 #8 可选增强件，本批实施；
   * D-5 裁定「仅台账/事件措辞」）：有 Running/Interrupted 残留（`residues > 0`，即本次
   * sweep 资格集命中）时——shutdown marker 在 ⇒ 上次优雅停机（kind=graceful）；不在 ⇒
   * 推断 unclean（断电/kill-9/崩溃，无钩子机会）。条目经
   * `BootDispatcherWake.appendPowerMarker` 进 boot-wake 台账（append-only、恒
   * blocking=false——与 kind=boot 条目共存，不触碰 duplicate-boot 幂等）。
   * `residues <= 0` ⇒ 零条目（fresh/干净 boot 不制造噪音）。fail-soft：本函数内
   * 读/写失败已逐层降级（readShutdownMarker/appendPowerMarker 各自 fail-soft），外层
   * 再兜一道 WARN——绝不阻断 sweep 认领链。
   */
  private[project] def annotateShutdownCause(pd: ProjectDef, residues: Int, atMs: Long): IO[Unit] =
    if residues <= 0 then IO.unit
    else
      BootDispatcherWake.readShutdownMarker
        .flatMap {
          case Some(m) =>
            BootDispatcherWake.appendPowerMarker(
              BootDispatcherWake.markerPath(pd),
              pd.name,
              BootDispatcherWake.MarkerEntry(
                bootId = BootDispatcherWake.instanceId,
                project = pd.name,
                at = atMs,
                result = "noted",
                reason = s"last shutdown graceful (marker at=${m.at} kind=${m.kind} cause=${m.cause})",
                blocking = false,
                nodes = residues,
                items = Nil,
                kind = BootDispatcherWake.KindGraceful,
                sleepAt = None,
                wakeAt = None
              )
            )
          case None =>
            BootDispatcherWake.appendPowerMarker(
              BootDispatcherWake.markerPath(pd),
              pd.name,
              BootDispatcherWake.MarkerEntry(
                bootId = BootDispatcherWake.instanceId,
                project = pd.name,
                at = atMs,
                result = "noted",
                reason =
                  "unclean shutdown inferred (no shutdown marker + Running/Interrupted residue: power-loss / kill-9 / crash)",
                blocking = false,
                nodes = residues,
                items = Nil,
                kind = BootDispatcherWake.KindUnclean,
                sleepAt = None,
                wakeAt = None
              )
            )
        }
        .handleErrorWith(e =>
          logger.warn(
            s"[boot-recovery] shutdown-cause annotation failed for project '${pd.name}': ${Option(e.getMessage).getOrElse(e.toString)}"
          )
        )

  /**
   * 单项目恢复：快段同步（认领 + 汇总通知）*> 慢段 fork。返回动作总数（认领 +
   * (c) 处置；0 = 无崩溃残留，零动作零事件零通知）。trigger 注入点供测试捕获，
   * 默认 = DispatchNotify.defaultTrigger 同款通道（TriggerDispatcher → spawn/注入
   * 分发器会话，裁定④）。
   */
  def recoverProject(
    rt: ProjectRuntime,
    concurrency: Int = Defaults.CrashRecoveryConcurrency,
    trigger: Option[String => IO[Unit]] = None
  ): IO[Int] =
    val notify = trigger.getOrElse(DispatchNotify.defaultTrigger(rt.project.name, rt.engine.rootSessionId))
    for
      // ── 快段：逐节点分类认领（节点级 handleErrorWith——单节点异常不断项目）──
      // 资格判据扩展（中断恢复语义批 2026-09-13，spec §2.4 #1）：Running ∪ Interrupted
      // ——kill -9 现场（Running 残留）+ 优雅关机现场（interrupted）同一认领链。
      // 回滚（spec §2.7）：`Defaults.ShutdownInterruptEnabled=false` ⇒ 资格集逐字回到
      // 本批前的「只认 Running」（残留 interrupted 由人工 NodeEdit 处置，见批 R2）。
      snap <- rt.store.snapshot
      candidates = snap.nodes.values
        .filter(n =>
          n.status == NodeLifecycle.Running ||
            (Defaults.ShutdownInterruptEnabled && n.status == NodeLifecycle.Interrupted)
        )
        .toList
      // 停机成因标注（hostresume 批 2026-09-22，设计卡 §4 #8，D-5 仅台账/措辞）：
      // 有 Running/Interrupted 残留（= 本次 sweep 资格集命中）才判读——marker 在 ⇒ 上次
      // 优雅停机；不在 ⇒ 推断 unclean（断电/kill-9/崩溃，无钩子机会）。条目进 boot-wake
      // 台账（kind=graceful/unclean，恒 blocking=false）；boot-wake 腿经
      // `BootDispatcherWake.bootShutdownCause` 消费同一事实源标注 dispatcher-wake 事件
      // 措辞。零残留 ⇒ 零条目（fresh/干净 boot 不制造噪音）。fail-soft：读/写失败只 WARN，
      // 绝不阻断 sweep（认领链零改动）。
      _ <- IO(System.currentTimeMillis()).flatMap(at => annotateShutdownCause(rt.project, candidates.size, at))
      outcomes: List[(String, Option[Either[String, NodeEngine.ResumeContext]])] <- candidates.traverse { n =>
        rt.engine
          .bootRecoveryClaim(n)
          .map(out => (n.id, out))
          .handleErrorWith(e =>
            logger
              .error(
                s"[boot-recovery] claim failed for node '${n.name}' (${n.id}): ${Option(e.getMessage).getOrElse(e.toString)} — left to the dead-session watchdog"
              )
              .as((n.id, None))
          )
      }
      resumed = outcomes.collect { case (id, Some(Right(ctx))) => (id, ctx) }
      failedReasons: List[String] = outcomes.collect { case (_, Some(Left(reason))) => reason }
      actions = resumed.size + failedReasons.size
      _ <-
        if actions == 0 then IO.unit
        else
          // 项目级汇总通知（裁定④）：每 boot 每项目恰一条——本方法每 boot 只被调用
          // 一次（recoverAll ← GatewayMain boot 链），单点发送即结构性幂等；分发器会话
          // 忙则 TriggerDispatcher 注入排队（ActiveDispatcher 既有承载）。
          notify(summaryText(rt, resumed, failedReasons, snap)).handleErrorWith(e =>
            logger.warn(
              s"[boot-recovery] summary notify failed for project '${rt.project.name}': ${Option(e.getMessage).getOrElse(e.toString)}"
            )
          )
      // ── 慢段：fork rehydrate（信号量并发上限；节点会话终态才释放槽位）──
      _ <-
        if resumed.isEmpty then IO.unit
        else
          Semaphore[IO](math.max(1, concurrency)).flatMap { sem =>
            resumed.traverse_ { case (nodeId, ctx) =>
              (sem.permit
                .use(_ =>
                  rt.engine
                    .bootRecoveryStart(nodeId, ctx)
                    .handleErrorWith(e =>
                      logger.error(
                        s"[boot-recovery] rehydrate failed for node $nodeId (session=${ctx.sessionId}): ${Option(e.getMessage).getOrElse(e.toString)} — node returned to the pending pool, settle sweep will fresh-start it as degraded fallback"
                      )
                    )
                ))
                .start
                .void
            }
          }
    yield actions

    end for

  end recoverProject

  // ── delegate 作用域腿（N1 刀1 · 设计 v3 §6/A2）────────────────────────────
  //
  // v3 加的 delegate 作用域 sweep：flow-maps/ 目录扫描（根集 = 已挂载项目 workspace ∪
  // 默认 general 目录，[[DelegateFlowHosts.sweepRoots]]），有 Running/Interrupted 残留
  // 的作用域重挂宿主 → **同判据认领**（bootRecoveryClaim 零新机制，(a)/(b) 翻 Pending
  // 入队 / (c) failNode）→ 慢段 rehydrate（信号量并发上限同款）。快慢两段/并发/回滚
  // 开关机制原样（A2「机制原样」）。
  //
  // 🔴 与项目 sweep 的三点差异（均候裁落定口径，非新机制）：
  //  ① **零汇总通知**——⑷「creator 不 live＝账本滞留不旁路认账」：delegate 会话重启后
  //     本就不在，认领事实走 boot-recovery 审计事件 + 日志（「禁止静默自愈」由事件面
  //     承担）；注入死会话/分发器都是旁路，禁。
  //  ② **座位/归名从 DelegateRegistry 持久记录现读**（cwd = 事故前座位；project = 归名）
  //     ——记录缺失时座位回落基工作区、归名回落目录名（fail-soft，认领链不因地址簿
  //     缺行而停）。
  //  ③ **无残留的作用域即开即卸**（探针式 open——与项目挂载「每 boot 全量重开」同构
  //     的幂等收敛，无残留不留宿主实例；盘面零动作语义不变）。
  //
  // **v3-⑧ 存量 per-project flow-map 归档不迁移（本刀落刀面）**：本 sweep 只扫
  // `flow-maps/` 作用域目录族——存量 per-project `flow-map.json`（旧路径族）零读取、
  // 零改写、零迁移，旧挂载面（ProjectActor.mount → FlowMapStore.open 旧路径）照常
  // 可开、只读留档；delegate 链自新作用域目录族起。

  /**
   * GatewayMain boot 链挂接入口（projectCrashSweep 邻位，同受 CrashRecoveryEnabled 闸）：
   * 对全部作用域目录跑快段 + fork 慢段。返回有恢复动作的作用域数（>0 时调用方记 info）。
   * 作用域级 handleErrorWith——单作用域异常不拖垮其他作用域与 boot（残余 Running 由
   * 宿主扫描器的 settle 腿兜底，[[DelegateFlowHosts.launchScanner]]）。
   */
  def recoverDelegateScopes(
    concurrency: Int = Defaults.CrashRecoveryConcurrency,
    // 测试接缝（recoverProject trigger 同款纪律；生产调用点零传参 = 引擎缺省行为）：
    // 本工程测试 JVM 下 sys.props 注入静默失效（BootCrashRecoverySpec 先例），重挂宿主
    // 的确定性腿经显式参数注入。
    reportGateHold: Option[Boolean] = None,
    rootNotifyQuietMs: Option[Long] = None
  ): IO[Int] =
    DelegateFlowHosts.sweepRoots.flatMap { roots =>
      roots
        .traverse { ws =>
          DelegateFlowHosts.scopeDirs(ws).flatMap { dirs =>
            dirs.traverse { dir =>
              recoverDelegateScope(ws, dir.last, concurrency, reportGateHold, rootNotifyQuietMs)
                .handleErrorWith(e =>
                  logger
                    .error(
                      s"[boot-recovery] delegate scope '${dir.last}' sweep failed: ${Option(e.getMessage).getOrElse(e.toString)} — residual running nodes left to the scope scanner's settle leg"
                    )
                    .as(0)
                )
            }
          }
        }
        .map(_.flatten.sum)
    }

  /** 单作用域恢复：重挂宿主 → 快段认领（同判据）→ 慢段 rehydrate（fork）。返回动作数。 */
  private def recoverDelegateScope(
    baseWorkspace: String,
    scope: String,
    concurrency: Int,
    reportGateHold: Option[Boolean],
    rootNotifyQuietMs: Option[Long]
  ): IO[Int] =
    DelegateRegistry.find(scope) match
      case None =>
        recoverDelegateScopeOn(
          baseWorkspace,
          scope,
          project = None,
          engineCwd = baseWorkspace,
          concurrency,
          reportGateHold,
          rootNotifyQuietMs
        )
      case Some(rec) =>
        recoverDelegateScopeOn(
          baseWorkspace,
          scope,
          project = rec.project,
          engineCwd = scala.util.Try(os.Path(rec.cwd)).map(_.toString).getOrElse(baseWorkspace),
          concurrency,
          reportGateHold,
          rootNotifyQuietMs
        )

  private def recoverDelegateScopeOn(
    baseWorkspace: String,
    scope: String,
    project: Option[String],
    engineCwd: String,
    concurrency: Int,
    reportGateHold: Option[Boolean],
    rootNotifyQuietMs: Option[Long]
  ): IO[Int] =
    // system/resources 载体：启动挂载后的任一项目 runtime（boot 链序 = startupMount 先行；
    // 零项目挂载 = 无 ActorSystem 载体 → WARN 跳过，宿主扫描器 +30s 兜底重试面不存在
    // ——重启窗口的 delegate 残留等下一次 boot；与项目 sweep「未挂载即不可恢复」同构）。
    runtimeCarrier.flatMap {
      case None =>
        logger
          .warn(s"[boot-recovery] delegate scope '$scope' skipped: no mounted project runtime carries an ActorSystem")
          .as(0)
      case Some((system, resources)) =>
        DelegateFlowHosts
          .mount(
            delegateId = scope,
            project = project,
            baseWorkspace = baseWorkspace,
            engineCwd = engineCwd,
            system = system,
            resources = resources,
            wsSend = None,
            reportGateHold = reportGateHold,
            rootNotifyQuietMs = rootNotifyQuietMs
          )
          .flatMap {
            case None => IO.pure(0) // 挂载失败（fail-soft 已 WARN）
            case Some(h) =>
              for
                snap <- h.store.snapshot
                // 资格判据与项目 sweep 逐字同源（Running ∪ Interrupted，回滚开关同读）。
                candidates = snap.nodes.values
                  .filter(n =>
                    n.status == NodeLifecycle.Running ||
                      (Defaults.ShutdownInterruptEnabled && n.status == NodeLifecycle.Interrupted)
                  )
                  .toList
                actions <-
                  if candidates.isEmpty then DelegateFlowHosts.unmount(scope).as(0) // 无残留：即开即卸
                  else
                    for
                      outcomes: List[(String, Option[Either[String, NodeEngine.ResumeContext]])] <- candidates
                        .traverse { n =>
                          h.engine
                            .bootRecoveryClaim(n)
                            .map(out => (n.id, out))
                            .handleErrorWith(e =>
                              logger
                                .error(
                                  s"[boot-recovery] claim failed for delegate-scope node '${n.name}' (${n.id}): ${Option(e.getMessage).getOrElse(e.toString)} — left to the scope scanner's settle leg"
                                )
                                .as((n.id, None))
                            )
                        }
                      resumed = outcomes.collect { case (id, Some(Right(ctx))) => (id, ctx) }
                      failed = outcomes.count(_._2.exists(_.isLeft))
                      _ = logger.info(
                        s"[boot-recovery] delegate scope '$scope': ${resumed.size} node(s) claimed for rehydrate, $failed failed (events in ${h.baseWorkspace}/.nebflow/flow-map-events.jsonl; no dispatcher notify — creator-delegate routing lands with N1 cut 3)"
                      )
                      // 慢段：fork rehydrate（信号量并发上限；节点会话终态才释放槽位）。
                      // 认领/续跑事件已由 bootRecoveryClaim 逐节点落审计（①零汇总通知）。
                      _ <-
                        if resumed.isEmpty then IO.unit
                        else
                          Semaphore[IO](math.max(1, concurrency)).flatMap { sem =>
                            resumed.traverse_ { case (nodeId, ctx) =>
                              (sem.permit
                                .use(_ =>
                                  h.engine
                                    .bootRecoveryStart(nodeId, ctx)
                                    .handleErrorWith(e =>
                                      logger.error(
                                        s"[boot-recovery] delegate-scope rehydrate failed for node $nodeId (session=${ctx.sessionId}): ${Option(e.getMessage).getOrElse(e.toString)} — node returned to the pending pool, scope scanner's settle leg will fresh-start it as degraded fallback"
                                      )
                                    )
                                ))
                                .start
                                .void
                            }
                          }
                    yield resumed.size + failed
              yield actions
          }
    }

  /** 任一已挂载项目 runtime 的 (ActorSystem, resources) 载体（boot 序 = startupMount 先行）。 */
  private def runtimeCarrier: IO[Option[(nebflow.actor.ActorSystem, nebflow.core.AgentRuntimePort)]] =
    ProjectRuntimeRegistry.all.map(_.headOption.map(rt => (rt.system, rt.resources)))

  /**
   * 汇总通知文本（恢复清单 + NodeList 复核指引 +「无需回报」——对齐
   * DispatchNotify.notifyTaskText 文案族）。
   */
  private def summaryText(
    rt: ProjectRuntime,
    resumed: List[(String, NodeEngine.ResumeContext)],
    failedReasons: List[String],
    snap: FlowMapState
  ): String =
    def nameOf(nodeId: String, sid: String): String =
      snap.nodes.values
        .find(_.id == nodeId)
        .map(n => s"${n.name}($nodeId)")
        .getOrElse(s"session=$sid")
    val resumeLines = resumed.map { case (nodeId, ctx) =>
      val loopNote = ctx.loopResumeRound match
        case 0 => ""
        case r => s" [loop round=$r phase=${ctx.loopResumePhase.getOrElse("-")}]"
      s"- rehydrate：${nameOf(nodeId, ctx.sessionId)}$loopNote"
    }
    val failLines = failedReasons.map(r => s"- failed：$r")
    s"""[boot-recovery] 项目「${rt.project.name}」崩溃恢复清单（Gateway 重启自动处置，共 ${resumed.size + failedReasons.size} 项）：
       |${(resumeLines ++ failLines).mkString("\n")}
       |rehydrate 节点已从磁盘 transcript 断点自动续跑（无需处理）；failed 节点请经 NodeList(project="${rt.project.name}") 复核，
       |按需处置（NodeEdit 换名新建 / 拓扑修补 / abandon / 上报）。无需回报——拓扑与状态已落 Flow Map。""".stripMargin
  end summaryText

end ProjectCrashRecovery
