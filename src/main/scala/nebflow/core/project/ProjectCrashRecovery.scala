package nebflow.core.project

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.Defaults

/**
 * ProjectCrashRecovery —— boot-time 崩溃断点恢复 sweep（crash-recovery 批 2026-09-07，
 * 设计 .nebflow/Spec/20260907_boot-time-crash-recovery.md §3）。
 *
 * 挂载点（GatewayMain boot 链）：`startupMount *> projectCrashSweep *> projectTtlScanner`
 * ——快段（分类+认领）同步秒级完成、必须先于 TtlTick 首拍（+30s）：被认领节点即刻
 * 翻 Pending 脱离 watchdog（settleStaleRunningNodes 只看 Running）判死口径，竞速由
 * 挂载顺序结构性关闭；慢段（rehydrate spawn + LLM 续跑，分钟级）fork 异步，不阻塞
 * server listen。
 *
 * 两段分工：
 *  - 快段（每项目串行、同步）：snapshot → status==Running 候选 → 逐节点
 *    NodeEngine.bootRecoveryClaim 三分类认领（(a)/(b) 翻 Pending 入队 / (c) failNode）
 *    → 有动作则项目级汇总通知恰一条（裁定④：TriggerDispatcher 通道直发，幂等 =
 *    「每 boot 每项目至多一条」结构性保证——boot 只发生一次，单点发送无记账）。
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

  /** GatewayMain boot 链挂接入口：对全部已挂载项目跑快段 + fork 慢段。
    * 返回有恢复动作的项目数（>0 时调用方记 info）。项目级 handleErrorWith——单项目
    * 异常不拖垮其他项目，该项目残余 Running 由 watchdog 兜底（R4）。 */
  def recoverAll(concurrency: Int = Defaults.CrashRecoveryConcurrency): IO[Int] =
    ProjectRuntimeRegistry.all.flatMap { rts =>
      rts.traverse { rt =>
        recoverProject(rt, concurrency)
          .handleErrorWith(e =>
            logger.error(s"[boot-recovery] project '${rt.project.name}' sweep failed: ${Option(e.getMessage).getOrElse(e.toString)} — residual running nodes left to the dead-session watchdog")
              .as(0))
      }.map(_.count(_ > 0))
    }

  /** 单项目恢复：快段同步（认领 + 汇总通知）*> 慢段 fork。返回动作总数（认领 +
    * (c) 处置；0 = 无崩溃残留，零动作零事件零通知）。trigger 注入点供测试捕获，
    * 默认 = DispatchNotify.defaultTrigger 同款通道（TriggerDispatcher → spawn/注入
    * 分发器会话，裁定④）。 */
  def recoverProject(
    rt: ProjectRuntime,
    concurrency: Int = Defaults.CrashRecoveryConcurrency,
    trigger: Option[String => IO[Unit]] = None
  ): IO[Int] =
    val notify = trigger.getOrElse(DispatchNotify.defaultTrigger(rt.project.name, rt.engine.rootSessionId))
    for
      // ── 快段：逐节点分类认领（节点级 handleErrorWith——单节点异常不断项目）──
      snap <- rt.store.snapshot
      candidates = snap.nodes.values.filter(_.status == NodeLifecycle.Running).toList
      outcomes: List[(String, Option[Either[String, NodeEngine.ResumeContext]])] <- candidates.traverse { n =>
        rt.engine.bootRecoveryClaim(n).map(out => (n.id, out))
          .handleErrorWith(e =>
            logger.error(s"[boot-recovery] claim failed for node '${n.name}' (${n.id}): ${Option(e.getMessage).getOrElse(e.toString)} — left to the dead-session watchdog")
              .as((n.id, None)))
      }
      resumed = outcomes.collect { case (id, Some(Right(ctx))) => (id, ctx) }
      failedReasons: List[String] = outcomes.collect { case (_, Some(Left(reason))) => reason }
      actions = resumed.size + failedReasons.size
      _ <- if actions == 0 then IO.unit
      else
        // 项目级汇总通知（裁定④）：每 boot 每项目恰一条——本方法每 boot 只被调用
        // 一次（recoverAll ← GatewayMain boot 链），单点发送即结构性幂等；分发器会话
        // 忙则 TriggerDispatcher 注入排队（ActiveDispatcher 既有承载）。
        notify(summaryText(rt, resumed, failedReasons, snap)).handleErrorWith(e =>
          logger.warn(s"[boot-recovery] summary notify failed for project '${rt.project.name}': ${Option(e.getMessage).getOrElse(e.toString)}"))
      // ── 慢段：fork rehydrate（信号量并发上限；节点会话终态才释放槽位）──
      _ <- if resumed.isEmpty then IO.unit
      else
        Semaphore[IO](math.max(1, concurrency)).flatMap { sem =>
          resumed.traverse_ { case (nodeId, ctx) =>
            (sem.permit.use(_ =>
              rt.engine.bootRecoveryStart(nodeId, ctx)
                .handleErrorWith(e =>
                  logger.error(s"[boot-recovery] rehydrate failed for node $nodeId (session=${ctx.sessionId}): ${Option(e.getMessage).getOrElse(e.toString)} — node returned to the pending pool, settle sweep will fresh-start it as degraded fallback"))
            )).start.void
          }
        }
    yield actions

  /** 汇总通知文本（恢复清单 + NodeList 复核指引 +「无需回报」——对齐
    * DispatchNotify.notifyTaskText 文案族）。 */
  private def summaryText(
    rt: ProjectRuntime,
    resumed: List[(String, NodeEngine.ResumeContext)],
    failedReasons: List[String],
    snap: FlowMapState
  ): String =
    def nameOf(nodeId: String, sid: String): String =
      snap.nodes.values.find(_.id == nodeId).map(n => s"${n.name}($nodeId)")
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

end ProjectCrashRecovery
