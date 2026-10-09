package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.ActorSystem
import nebflow.core.AgentRuntimePort
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.concurrent.duration.*

/**
 * DelegateFlowHosts —— **delegate 作用域宿主注册表与生命周期**（N1 刀1 · 设计 v3 §6/A3：
 * 「NodeEngine 宿主从 ProjectActor 挂载链抽出 delegate 腿」）。
 *
 * == 一 delegate 一图（A1 作用域切换）==
 * 作用域键 = delegate 会话 id（`delegate-<agent>-<8hex>`；Mail 续聊复活腿 = 全新会话
 * id ⇒ 全新作用域，与「delegate:<id> 地址面 = 实例」的既有口径同构）。存储根 =
 * `<基工作区>/.nebflow/flow-maps/<scope>/`（[[FlowMapStore.openScope]]；归档 / results /
 * tasks / chain-ledger 同构随迁）。基工作区 = 挂载项目 workspace（判是腿的 worktree
 * 只作节点座位不作存储根——台账寿命必须长于 worktree）或默认 general 目录。
 *
 * == 生命周期（A3）==
 *  - spawn（[[nebflow.core.tools.DelegateTool]] 内置执行器腿，spawn 成功后）：[[mount]]
 *    ——FlowMapStore.openScope ＋ NodeEngine 实例（rootSessionId = delegate 自己＝本
 *    作用域的 root：节点 out=Nebula 投 delegate 会话，不 live 即滞留＝候裁⑷的物理
 *    背书「状态本来就在盘上」）。fail-soft：挂载失败只 WARN，绝不拖垮 delegate 本体
 *    （刀1 面 additive——NodeEdit 等工具面在刀2 才接入作用域）。
 *  - 终态（[[nebflow.core.delegate.DelegateTerminal.onTerminalCleanup]]）：[[unmount]]
 *    ——引擎实例灭；落盘状态存（write-through 保证，R1）。ready-to-merge 停靠腿
 *    （HoldForMerge 不走 notifyParentAndStop）宿主照常存活等合并轮。
 *  - 崩溃恢复（[[ProjectCrashRecovery.recoverDelegateScopes]]）：flow-maps/ 目录扫描，
 *    有 Running/Interrupted 残留的作用域重挂宿主 → 同判据认领（bootRecoveryClaim 零新
 *    机制）→ 慢段 rehydrate（信号量并发上限同款）。零分发器汇总通知（⑷「不旁路认账」
 *    ——认领留痕走 boot-recovery 审计事件）。
 *  - 周期驱动（[[launchScanner]]，GatewayMain boot 链 projectTtlScanner 邻位）：对每个
 *    在册宿主跑引擎腿（与 ProjectActor.TtlTick 同款 best-effort 纪律）＋ 孤儿收割——
 *    delegate 会话已不在 registry ∧ 图内无非终态节点 ⇒ 宿主实例灭（盘面状态存）。
 *
 * == 不碰面（v3 §10.2 防越界）==
 * ProjectActor 本体与分发器触发链零触碰；ProjectRuntimeRegistry 不混入 delegate 宿主
 * （项目 face 的 REST/工具解析语义不变）；外部执行器腿不建宿主（外部 CLI 不经
 * Nebflow 工具协议，无图可编辑——#1228 链视图消费的仍是其 ChainStore 行族）；协议帧
 * 零新增（复用 node* 既有帧形，路由戳 = delegate 会话既有 routeWsSend 同款）。
 */

/** 一个 delegate 作用域宿主：一张图 ＋ 一个引擎实例（实例可灭，盘面状态存）。 */
final case class DelegateFlowRuntime(
  /** 作用域键 = delegate 会话 id（= engine.rootSessionId）。 */
  delegateId: String,
  /** 挂载项目名；None = 默认 general 目录腿。 */
  project: Option[String],
  /** 存储根锚定工作区（项目 workspace / 默认 general 目录）——作用域目录在其 `.nebflow` 下。 */
  baseWorkspace: String,
  /** 节点座位（判是腿 = worktree；否则 = 基工作区）——引擎 spawn/解析用工作区。 */
  engineCwd: String,
  store: FlowMapStore,
  engine: NodeEngine
):

  /** 作用域目录读数（挂载/隔离证据锚点）：`<baseWorkspace>/.nebflow/flow-maps/<scope>/`。 */
  def scopeDir: os.Path =
    os.Path(baseWorkspace, PathUtil.dataRoot) / ".nebflow" / FlowMapStore.FlowMapsDirName / delegateId

  /**
   * N1 刀2（v3 §10.1 A4 授能）：delegate 作用域 → **Node 工具面 ProjectRuntime 视图**。
   *
   * 为什么是适配器而不是第二套工具逻辑：NodeEdit/NodeList/NodeCancel 的校验链
   * （存在性/环检/门集/载荷组装）全部以 `ProjectRuntime` 为参数面——作用域图经本
   * 适配器复用**同一套**工具逻辑（单一真相源，禁 fork 第二套）。字段映射：
   *   - `project.name`    = engine.projectName（挂载期归名：项目名 / general 腿座位名
   *     ——与引擎事件归名同源，载荷 meta.project 与事件流逐字一致）；
   *   - `project.workspace` = baseWorkspace（**基工作区**——事件锚点，与刀1 各腿
   *     `chainArchiveLeg` 等逐字同源：作用域目录族挂其 `.nebflow` 下，寿命长于座位）；
   *   - `store`/`engine`   = 作用域实例本体；
   *   - `actorRef`/`board` = None（ProjectActor 项目 face 专属——作用域图零板块头、
   *     零 actor 指令面，与刀1 mount 的 board=None 同口径）。
   * 纯视图（零注册）：**不进** ProjectRuntimeRegistry（项目 face 的 REST/解析语义
   * 不变——刀1 已钉的红线）。
   */
  def projectRuntime: ProjectRuntime = ProjectRuntime(
    project = ProjectDef(
      name = engine.projectName,
      workspace = baseWorkspace,
      agentFile = (os.Path(baseWorkspace, PathUtil.dataRoot) / "AGENTS.md").toString,
      createdAt = 0L
    ),
    store = store,
    engine = engine,
    system = engine.system,
    resources = engine.resources,
    actorRef = None,
    board = None
  )

end DelegateFlowRuntime

object DelegateFlowHosts:
  private val logger = NebflowLogger.forName("nebflow.delegate.flow-hosts")

  /** 进程内在册宿主（delegateId → runtime）。挂载/卸载都在本 Ref 单点。 */
  private val hosts = Ref.unsafe[IO, Map[String, DelegateFlowRuntime]](Map.empty)

  def get(delegateId: String): IO[Option[DelegateFlowRuntime]] = hosts.get.map(_.get(delegateId))

  def all: IO[List[DelegateFlowRuntime]] = hosts.get.map(_.values.toList)

  /** delegateId 是否已挂载（幂等挂载判据；挂载读数面）。 */
  def isMounted(delegateId: String): IO[Boolean] = hosts.get.map(_.contains(delegateId))

  /** 全清（ProjectRuntimeRegistry.clear 同款测试先例：spec 隔离防跨 suite 污染）。 */
  def clear: IO[Unit] = hosts.set(Map.empty)

  /**
   * 挂载 delegate 作用域宿主（spawn 成功腿调用；幂等——已挂载直接返回现挂）。失败
   * 语义 = **fail-soft**：非法作用域 id / open 失败 → WARN + None，调用方零处理继续
   * （delegate 本体不受影响；刀2 工具面对「查无作用域图」有显式报错形态）。
   *
   * `rootSessionId` 判据：delegate 作用域**自根**——恒传 delegateId 本身（节点
   * out=Nebula / 通知注入目标是 delegate 会话，不是 Nebula 顶层根；⑷「creator 不
   * live＝滞留」由既有 Parked/重投机制物理承接，零旁路）。S19①（N1 刀3 · v3 §7.1
   * ＋候裁 v3-⑨ 代裁照落）：本自根事实自本刀起升格为显式规则——引擎
   * notifyTargetKind = CreatorDelegate（[[nebflow.core.delegate.DelegateNotify]]
   * .NotifyRule.Target=creator-delegate 单点消费），root 通知每次 offer 落
   * `notify-redirected` 审计行（锚基工作区）；门集语法保留、缺省目标＝创建者
   * delegate、显式门集 Nebula 边＝重映射投创建者（S19① 不破例）、bare "Nebula"
   * ＝零投递标记照旧。测试接缝
   * （`reportGateHold` / `rootNotifyQuietMs` / `destroyWindowMs`）与 NodeEngine 构造
   * 器同款：None = 生产缺省，spec 显式注入避全局 prop（本工程测试 JVM prop 注入静默
   * 失效，BootCrashRecoverySpec 先例同款纪律）。
   */
  def mount(
    delegateId: String,
    project: Option[String],
    baseWorkspace: String,
    engineCwd: String,
    system: ActorSystem,
    resources: AgentRuntimePort,
    wsSend: Option[Json => IO[Unit]],
    reportGateHold: Option[Boolean] = None,
    rootNotifyQuietMs: Option[Long] = None,
    destroyWindowMs: Option[Long] = None
  ): IO[Option[DelegateFlowRuntime]] =
    if !FlowMapStore.validScopeId(delegateId) then
      logger
        .warn(
          s"delegate flow-host mount REFUSED: invalid scope id '$delegateId' (charset contract [a-zA-Z0-9._-], ≤128) — delegate continues without a scope map"
        )
        .as(None)
    else
      hosts.get.flatMap { cur =>
        cur.get(delegateId) match
          case Some(existing) => IO.pure(Some(existing)) // 幂等（并发 spawn 双挂防御）
          case None =>
            val projectName = project.getOrElse(os.Path(baseWorkspace).last) // 通知头/事件归名（general 腿 = 座位名）
            for
              store <- FlowMapStore.openScope(delegateId, projectName, baseWorkspace)
              engine = new NodeEngine(
                store,
                system,
                resources,
                wsSendFn = wsSend.getOrElse((_: Json) => IO.unit),
                workspace = engineCwd,
                rootSessionId = delegateId, // 自根作用域（见方法注）
                projectName = projectName,
                emitEvent = emitScopeEvent(projectName, wsSend),
                board = None, // 项目任务板是 per-project face；作用域图零板块头
                projectGoal = None, // 项目目标行同上
                reportGateHold = reportGateHold,
                rootNotifyQuietMs = rootNotifyQuietMs,
                destroyWindowMs = destroyWindowMs,
                // S19①（N1 刀3 · v3 §7.1 + 候裁 v3-⑨ 代裁照落）：作用域引擎的通知
                // 目标 = 创建者 delegate 本人（root=self 刀1 已使投递落点如此；本参
                // 把它升格为显式规则——root 通道每次 offer 落 notify-redirected 审计，
                // 锚基工作区）。缺省目标＝创建者 delegate；显式门集 Nebula 边 =
                // 重映射投创建者（S19① 不破例）；bare "Nebula" = 零投递标记照旧
                // （NodeTools.parseOutSegment R1-a 分支零触碰）。
                notifyTargetKind = NodeEngine.NotifyTargetKind.CreatorDelegate,
                notifyAuditWorkspace = Some(baseWorkspace),
                // jev-plugins 批 #1226（S9 扩面）：作用域引擎 = 分配触发点（链每步
                // spawn）——节点空声明走分配面（PluginInjection.forNode）；per-project
                // 引擎不传（缺省 false = 逐字节旧行为）。
                delegateScope = true
              )
              rt = DelegateFlowRuntime(delegateId, project, baseWorkspace, engineCwd, store, engine)
              _ <- hosts.update(_ + (delegateId -> rt))
              _ <- logger.info(
                s"[delegate-flow-host] mounted scope '$delegateId' (project=${project.getOrElse("-")}, " +
                  s"map=${rt.scopeDir}, seat=$engineCwd, root=self) — one delegate one map"
              )
            yield Some(rt)
            end for
      }

  /**
   * 卸载（delegate 终态腿 / 孤儿收割）：引擎实例灭 + 在册摘除。**零盘面动作**——
   * write-through 已持久（R1），盘上 `flow-maps/<scope>/` 原样留存（resume/审计面）。
   * 幂等：未挂载 = 零动作。外部执行器 delegate 终态也走本腿（未挂载 → no-op）。
   */
  def unmount(delegateId: String): IO[Unit] =
    hosts.get.flatMap { cur =>
      cur.get(delegateId) match
        case None => IO.unit
        case Some(rt) =>
          hosts.update(_ - delegateId) *>
            logger.info(
              s"[delegate-flow-host] unmounted scope '$delegateId' — engine instance gone, on-disk state kept at ${rt.scopeDir} (write-through)"
            )
    }

  // ── 作用域目录枚举（boot sweep 数据源；只读，无目录 = Nil）──────────────────

  /** 某工作区下的作用域目录清单（`<ws>/.nebflow/flow-maps/` 直下各作用域目录，按名稳定序）。 */
  def scopeDirs(baseWorkspace: String): IO[List[os.Path]] =
    IO.blocking {
      val root = os.Path(baseWorkspace, PathUtil.dataRoot) / ".nebflow" / FlowMapStore.FlowMapsDirName
      if os.exists(root) then os.list(root).filter(os.isDir).toList.sortBy(_.last) else Nil
    }.handleErrorWith(e =>
      logger.warn(s"[delegate-flow-host] scope dir listing failed for '$baseWorkspace': ${e.getMessage}").as(Nil)
    )

  /** sweep 根集：全部已挂载项目 workspace ＋ 默认 general 目录（去重保序）。 */
  def sweepRoots: IO[List[String]] =
    for
      ws <- ProjectRuntimeRegistry.all.map(_.map(_.project.workspace))
      general = (PathUtil.dataRoot / "general").toString
    yield (general :: ws).distinct

  // ── 周期驱动（GatewayMain projectTtlScanner 邻位）───────────────────────────

  /**
   * 宿主扫描器：周期对每个在册宿主跑引擎腿（TtlTick 同款 best-effort，失败仅 WARN
   * 不影响后续腿）＋ 孤儿收割。无宿主 = 空转（零成本短路）。零新调度原语（ttlScanner
   * 同款 sleep 循环形态）。
   */
  def launchScanner(interval: FiniteDuration): IO[Unit] =
    def loop: IO[Unit] =
      IO.sleep(interval) *>
        (all.flatMap(
          _.traverse_(h =>
            tickHost(h).handleErrorWith(e =>
              logger.warn(s"[delegate-flow-host] tick failed for scope '${h.delegateId}': ${e.getMessage}")
            )
          )
        ) *> reapOrphans).handleErrorWith(e =>
          logger.warn(s"[delegate-flow-host] scanner pass failed: ${e.getMessage}")
        ) *> IO.defer(loop)
    loop

  /**
   * 单宿主一拍（引擎腿选集 = ProjectActor.TtlTick 的**引擎侧**子集：分发器腿
   * （sweepDispatchers）与项目文档索引腿（DocIndexConsumer）是 ProjectActor/项目 face
   * 专属，delegate 作用域不适用；其余逐条对齐——判据本体零改动，禁在作用域腿重裁）。
   */
  private def tickHost(h: DelegateFlowRuntime): IO[Unit] =
    def warn(step: String)(e: Throwable): IO[Unit] =
      logger.warn(s"[delegate-flow-host] scope '${h.delegateId}' $step failed: ${e.getMessage}")
    h.engine.dispatchNotify.redeliver().handleErrorWith(e => warn("dispatch-notify redeliver")(e).void) *>
      h.engine
        .redeliverUnconsumedRootResults()
        .handleErrorWith(e => warn("root redeliver")(e).void)
        .void *>
      h.engine.settleRunnableSweep().handleErrorWith(e => warn("settle sweep")(e)) *>
      h.engine.settleStaleRunningNodes().handleErrorWith(e => warn("dead-session settle")(e)) *>
      h.engine.remindUnreportedNodes().handleErrorWith(e => warn("report reminder")(e)) *>
      h.engine.sweepDestroyWindows().handleErrorWith(e => warn("destroy-window sweep")(e)) *>
      h.engine.sweepLoopBudgets().handleErrorWith(e => warn("loop budget sweep")(e)) *>
      h.engine.applyDeferredWiring().handleErrorWith(e => warn("deferred wiring")(e)) *>
      h.engine.backfillAbandonedDetach().handleErrorWith(e => warn("abandon detach backfill")(e).as(Nil)).void *>
      chainArchiveLeg(h).handleErrorWith(e => warn("chain archive sweep")(e).void) *>
      chainSummaryLeg(h).handleErrorWith(e => warn("chain summary registration")(e).void) *>
      ledgerReconcileLeg(h).handleErrorWith(e => warn("chain ledger reconcile")(e).void)

  end tickHost

  /**
   * 链级即时归档腿（TtlTick 同款骨架，作用域化）：sweepCompletedChainsDetailed 出库 →
   * WS nodeRemoved 逐节点（engine.emitRemoved 既有面）→ chain-archived 审计事件落
   * **基工作区**事件流（`<baseWs>/.nebflow/flow-map-events.jsonl`——作用域存储族的
   * 事件锚点与作用域目录同 `.nebflow`，寿命长于 worktree 座位；per-scope 事件文件族
   * = §8 行2 改造面，随刀2/刀3 工具批落位）。审计失败只 WARN 不回滚（TtlTick ① 同款）。
   */
  private def chainArchiveLeg(h: DelegateFlowRuntime): IO[Unit] =
    h.store.sweepCompletedChainsDetailed(System.currentTimeMillis()).flatMap { swept =>
      val removals = swept.flatMap(_.nodeIds).traverse_(id => h.engine.emitRemoved(id))
      val audits = swept.traverse_ { c =>
        FlowMapEventLog
          .append(
            h.baseWorkspace,
            h.engine.projectName,
            c.nodeId,
            FlowMapEventLog.ChainArchivedType,
            FlowMapEventLog.chainArchivedSummary(c.chainId, c.archivedAt, c.members),
            Some(c.chainId)
          )
          .handleErrorWith(e =>
            logger.warn(s"[delegate-flow-host] chain-archived append failed (${c.chainId}): ${e.getMessage}")
          )
      }
      removals *> audits
    }

  /**
   * 链级摘要**降级登记**腿（「全部降级列表态」批生产语义的作用域化：零投递、照记
   * summarySentAt——delegate 作用域的摘要承载面与其归档面板同源，本腿只做台账收敛，
   * 防「已启用未投递」批无界滞留）。
   */
  private def chainSummaryLeg(h: DelegateFlowRuntime): IO[Unit] =
    h.store
      .chainSummaryBatch(FlowMapStore.ChainSummaryMaxPerRound)
      .flatMap { case (head, tail) =>
        val mark = (c: FlowMapStore.ChainSummary) => h.store.markChainSummarySent(c.chainId, System.currentTimeMillis())
        head.traverse_(mark) *> tail.traverse_(mark)
      }

  /** 链号台账 reconcile 腿（TtlTick ⑤ 同款拍点；改号留痕事件落基工作区事件流）。 */
  private def ledgerReconcileLeg(h: DelegateFlowRuntime): IO[Unit] =
    h.store.reconcileChainLedger(System.currentTimeMillis()).flatMap { ledgerChanges =>
      ledgerChanges.traverse_ { ch =>
        FlowMapEventLog
          .append(
            h.baseWorkspace,
            h.engine.projectName,
            ch.nodeId,
            FlowMapEventLog.ChainMembershipChangedType,
            FlowMapEventLog.chainMembershipChangedSummary(ch.from, ch.to, ch.reason),
            chainId = ch.to
          )
          .handleErrorWith(e =>
            logger
              .warn(s"[delegate-flow-host] chain-membership-changed append failed for ${ch.nodeId}: ${e.getMessage}")
          )
      }
    }

  /**
   * 孤儿收割：delegate 会话已不在 agentRegistry（终态清理 / 崩溃重启后再无续聊）
   * **且**图内无非终态节点（pending/wiring/running/blocked——barrier 等待与反馈重入
   * 都还需要引擎）⇒ 宿主实例灭。会话在 ⇒ 宿主在（A3「会话在→宿主在」）；有活图 ⇒
   * 宿主在（引擎要驱动它走完）。收割只灭实例，盘面零动作（write-through）。
   */
  private[project] def reapOrphans: IO[Int] =
    all
      .flatMap(_.traverse { h =>
        for
          live <- h.engine.resources.agentRegistry.get.map(_.contains(h.delegateId))
          snap <- h.store.snapshot
          active = snap.nodes.values.exists(n => !NodeLifecycle.Terminal.contains(n.status))
          _ <- if !live && !active then unmount(h.delegateId) else IO.unit
        yield if !live && !active then 1 else 0
      })
      .map(_.sum)

  /**
   * 作用域 WS 事件帧（ProjectActor.emitNodeEvent 同族帧形，**零新增键**；项目名 =
   * 作用域归名。wsSend 由调用方预路由——delegate 会话既有 routeWsSend 戳）。
   */
  private def emitScopeEvent(
    project: String,
    wsSend: Option[Json => IO[Unit]]
  )(eventType: String, nodeId: String, nodeJson: Json): IO[Unit] =
    wsSend.fold(IO.unit) { send =>
      send(
        Json.obj(
          "type" -> eventType.asJson,
          "project" -> project.asJson,
          "nodeId" -> nodeId.asJson,
          "node" -> nodeJson
        )
      )
    }

end DelegateFlowHosts
