package nebflow.core.project

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.{PluginMcpManager, PluginRegistry, PluginsConfig}
import nebflow.core.presets.PresetStore
import nebflow.core.skill.SkillService
import nebflow.core.tools.PresetResolver
import nebflow.shared.Message

/**
 * NodeEngine —— 节点执行内核（#28 阶段 0，方案 §2.7 + §3.1）。
 *
 * 职责：
 * 1. spawn 节点 agent（NodeRunner 共享内核；sessionId = "node-<uuid>"，干净上下文、
 *    leaf、无记忆无持久会话；projectRoot = worktree 或工作区）
 * 2. 结果捕获（bridge actor + Deferred）：节点 agent 完成 → 其最终输出文本即结果
 *    （无 FlowReport/verdict/slots）→ 写节点 result（持久化）+ status=completed +
 *    ttlExpireAt=+24h → 沿 out 投递
 * 3. 投递（§2.7）：out=节点 → 下游 deliveredTo 记录 + barrier 归零启动下游（输入 =
 *    task 上下文 + 各上游 result 带 === Node <name> === 头）；out=Nebula →
 *    ImmediateInput("[Node '<name>' completed]\n<result>", source="node") 投根会话；
 *    out=null → 结果保留（接线后从活动/归档自动投递）；failed →
 *    ImmediateInput("[Node '<name>' failed]\n<err>")
 * 4. NodeCancel：运行中节点 → cancel 信号 → 停 agent + status=cancelled（不投递）
 * 5. blocked 终态（20260902 反馈重入设计 §1/§2）：completeNode 入口经 BlockedReader
 *    解析——最终输出以 BLOCKED 锚定 + JSON 体 → status=blocked（不结算下游）+
 *    FeedbackRouter 重入/升级路由；非 BLOCKED 开头 → completed 原路径无损降级
 *
 * 硬约束：不设运行超时（TaskStuckWatcher 10min 零活动兜底）；节点无 Mail 身份。
 */
class NodeEngine(
  val store: FlowMapStore,
  system: ActorSystem,
  resources: SharedResources,
  val wsSendFn: Json => IO[Unit],
  workspace: String,
  val rootSessionId: String,
  /** 项目名：Node 完成通知蓝气泡 header 第二段（NODE · <项目名> · <节点名> · <状态>）。 */
  val projectName: String,
  /** blocked 反馈档位（设计 §7.1）：auto（默认）| escalate-only。挂载时读 project.json。 */
  val feedbackMode: String = FeedbackRouter.ModeAuto,
  /** WS 事件推送（type → payload 载体）。 */
  emitEvent: (String, String, Json) => IO[Unit],
  /** 产物完整性闸门的 git 执行器（audit 20260905）：默认真实 git 只读命令；
    * spec 注 stub。闸门本体与 kill-switch 见 CompletionGate。 */
  gateRunner: CompletionGate.GitRunner = CompletionGate.defaultRunner
):
  private val logger = NebflowLogger.forName("nebflow.node.engine")

  /** blocked 反馈路由器（§2.2/§2.3）：档位决策 + 项目级频率保护 + 重入/升级执行。
    * escalate 通道 = 本引擎的 deliverToNebula（eventType="blocked"，前端 label 自动 BLOCKED）。 */
  private[project] val feedbackRouter: FeedbackRouter = new FeedbackRouter(
    projectName = projectName,
    workspace = workspace,
    feedbackMode = feedbackMode,
    escalate = (text, nodeName) => deliverToNebula(text, nodeName, NodeLifecycle.Blocked)
  )

  /** 运行中节点 → cancel 信号（NodeCancel 用）。 */
  private val running: Ref[IO, Map[String, Deferred[IO, Unit]]] = Ref.unsafe[IO, Map[String, Deferred[IO, Unit]]](Map.empty)

  /** 缺口4（2026-09-04 重复投递去重）：同 (identity, status) 60s 窗口内重复
    * Nebula 通知抑制——进程内时间窗 map（固定窗，顺路淘汰过期条目）。与 V8
    * nebulaDeliveredAt 账本**正交**：账本管跨重启 at-least-once（持久化，管
    * 「结果是否到达过 Nebula」），本表管秒级抖动（内存，管「同一通知短窗内
    * 重复轰炸」——09-03 ProjectCreate 合并回报三连投实证）。窗口内重复被抑制
    * 时仍 markNebulaDelivered，账本一致性不破坏。identity = nodeId（无 nodeId
    * 的 fire-and-forget 通道用 nodeName——held/escalate 各节点独立窗口）。 */
  private[project] val recentNebulaDeliveries: Ref[IO, Map[(String, String), Long]] =
    Ref.unsafe[IO, Map[(String, String), Long]](Map.empty)

  /** 节点是否正在运行（ProjectActor 状态查询用）。 */
  def isRunning(nodeId: String): IO[Boolean] = running.get.map(_.contains(nodeId))

  /** 取消运行中节点（NodeCancel/ProjectActor 转发）：触发 cancel 信号 → 停 agent。 */
  def cancelNodeById(nodeId: String): IO[Unit] =
    running.get.map(_.get(nodeId)).flatMap {
      case Some(sig) => sig.complete(()).void
      case None => IO.unit
    }

  /** 死会话 running 节点收殓（清场 c-③，20260903 03:04 清场误杀事故复盘）：
    * status=running 但无在飞执行 fiber（running 表无此节点——会话死于传输中断 /
    * 实例重启后内存态清空）→ 直接终态化 cancelled + 显示 TTL（cancelNode 全链）
    * + 审计事件，修复 NodeCancel「cancel signal sent」但状态不落终态的假成功。
    * 误杀防护（硬约束）：有在飞 fiber = 活会话（取消信号可达）→ Left 拒绝，
    * 本方法绝不触碰活会话节点。幂等：非 running → Left（不重复终态化）。 */
  def reapStaleRunning(nodeId: String): IO[Either[String, String]] =
    store.getNode(nodeId).flatMap {
      case None => IO.pure(Left(s"Node '$nodeId' not found"))
      case Some(n) if n.status != NodeLifecycle.Running =>
        IO.pure(Left(s"Node '${n.name}' is not running (status=${n.status}) — nothing to reap"))
      case Some(n) =>
        isRunning(nodeId).flatMap {
          case true =>
            IO.pure(Left(s"Node '${n.name}' has a live execution fiber — use the normal cancel signal, not reap"))
          case false =>
            logger.warn(s"Node '${n.name}' ($nodeId) reaped: status=running but no live execution fiber (dead session / instance restart)")
            FlowMapEventLog.append(workspace, projectName, nodeId, "reaped",
              "dead running session finalized as cancelled (no live execution fiber; NodeCancel reap)") *>
              cancelNode(nodeId).as(Right(s"Node '${n.name}' reaped — dead running session finalized as cancelled (display TTL)"))
        }
    }

  /** WS nodeRemoved（TTL 移除/归档后通知前端移除卡片）。payload = 节点最终态
    * （NodeList 同构——归档区兜底查得，ttlLeftSec=0），不再发空对象。 */
  def emitRemoved(nodeId: String): IO[Unit] =
    store.findNode(nodeId).flatMap {
      case Some(n) => emitEvent("nodeRemoved", nodeId, NodePayload.buildNodeJson(n, System.currentTimeMillis()))
      case None    => emitEvent("nodeRemoved", nodeId, Json.obj("id" -> nodeId.asJson))
    }

  /** WS nodeCreated（NodeEdit 创建后）。payload 与 NodeList 同构。 */
  def emitCreated(node: NodeDef): IO[Unit] =
    emitEvent("nodeCreated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /** WS nodeUpdated（NodeEdit 编辑/wiring 变更后）。payload 与 NodeList 同构，
    * 调用方须传 store 最终态（wiring 变更走 NodeTools.emitWiringUpdates）。 */
  def emitUpdated(node: NodeDef): IO[Unit] =
    emitEvent("nodeUpdated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /** 显式投递（改接投递 §2.3：已完成节点结果 → 指定目标）。V8: Nebula 分支
    * 同样记账——人工改接重投后刷新账本，避免周期扫描对同一结果再补投。
    * 缺口3（2026-09-04）：人工改接重投通道同样过夹具信封排除——名字 ∧ 载荷
    * 双确认 → WARN + 不投（结果滞留节点不删，真实工作不受影响）。 */
  def deliverOutTo(node: NodeDef, target: String, resultText: String): IO[Unit] =
    target match
      case "Nebula" =>
        if NodeEngine.isFixtureEnvelope(node) then
          logger.warn(
            s"[fixture-guard] excluded fixture envelope from manual redelivery: node '${node.name}' (${node.id}) status=${node.status} — name matches fixture family and task carries fixture marker")
        else deliverToNebula(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed", Some(node.id))
      case t =>
        for
          targetOpt <- store.findNode(t)
          _ <- targetOpt match
            case None => IO.unit
            case Some(_) =>
              store.mutate { s =>
                val tn = s.nodes.get(t)
                tn match
                  case Some(x) if !x.deliveredTo.contains(node.id) =>
                    s.copy(nodes = s.nodes.updated(t, x.copy(deliveredTo = x.deliveredTo :+ node.id)))
                  case _ => s
              }.flatMap { s =>
                val allArrived = s.nodes.get(t).exists(tn2 => tn2.in.forall(upId => tn2.deliveredTo.contains(upId)))
                if allArrived then startNode(t) else IO.unit
              }
        yield ()

  /** deps 满足判定（deps 设计 §1.3）：声明式状态查询（幂等、零记账），非 deliveredTo
    * 式事件累计。findNode 归档兜底——TTL 归档不影响「完成」事实（归档上游可触发，
    * 与 D1「归档 completed 上游是唯一投递入口」先例一致）。deps 为空 → 恒满足。 */
  def depsSatisfied(node: NodeDef): IO[Boolean] =
    node.deps.traverse(store.findNode)
      .map(_.forall(_.exists(_.status == NodeLifecycle.Completed)))

  /** 启动节点（§2.1 创建即运行：入口节点由 NodeEdit 调；下游由投递 barrier 归零调）。 */
  def startNode(nodeId: String): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case None => IO.unit
      case Some(node) =>
        node.status match
          // blocked 同样幂等跳过（§1.1：重跑同样 blocked；唯一出口 = NodeEdit 重激活）；
          // held 防御性跳过（20260903 暂停/人在回路设计 §2.2）：held 不应有任何启动
          // 路径可达（自身完成已发生过、上游投递因 deliverOut 未跑而永不满足 barrier、
          // deps 闸门因 held ∉ completed 而天然拦），闸门单点多挡一道=纵深防御风格。
          case NodeLifecycle.Running | NodeLifecycle.Completed | NodeLifecycle.Failed | NodeLifecycle.Cancelled | NodeLifecycle.Blocked | NodeLifecycle.Held =>
            IO.unit // 已终态/运行中/held，幂等跳过
          case _ =>
            // deps 闸门（deps 设计 §1.3，单权威）：全部 deps 上游 completed 才放行。
            // 单闸门自动保护全部启动入口（deliverOut/deliverOutTo/D1 补投递/重激活链/
            // 入口启动/settleDeps）——任何绕过 deps 的启动都被拦在最后一关。
            // failed/cancelled/blocked ∉ completed → 不触发，下游保持 pending/wiring 可见。
            depsSatisfied(node).flatMap {
              case false => IO.unit
              case true =>
                // in barrier 归零检查（deps 设计 §1.3「in 未归零都会静默返回」）：新调用方
                // settleDeps 直调 startNode 不再经 deliverOut 的 barrier 归零前置——闸门内
                // 补上这层，混合同持 in+deps 的下游只在两种等待都满足时才启动。既有全部
                // 调用方（deliverOut/deliverOutTo/deliverFailed/D1 链/入口启动）本就在
                // barrier 归零后才调（入口 in=Nil 平凡归零），检查对它们恒真、零行为变化。
                // 本检查沿快照；并发接线窗口的事务性复核在 spawnAndRun 的翻转 mutate 内
                //（fix a 启动判定完整性，20260903）。
                if node.in.exists(up => !node.deliveredTo.contains(up)) then IO.unit
                else
                  // 防御：wiring 空节点（无 task 无 in 无 deps）不空跑（防浪费 token）。
                  // 裁定①后降级为纵深防御：零连接主责在 NodeEdit 校验五/六，此处只兜
                  // 历史遗留数据（校验生效前落盘的零连接旧节点）与校验层外瞬态。
                  if node.status == NodeLifecycle.Wiring && node.task.isEmpty && node.in.isEmpty && node.deps.isEmpty then IO.unit
                  else buildInput(node).flatMap { input =>
                    spawnAndRun(node, input)
                  }
            }
        }

  /** 构造节点输入：自身 task 上下文 + 各上游 result（=== Node <name> === 头，§2.7）+
    * blocked 声明协议脚注（设计 §1.5 原文，单点注入覆盖所有节点——节点 agent 是通用
    * 全局 agent，system prompt 不含约定，必须随输入注入）。 */
  private def buildInput(node: NodeDef): IO[String] =
    val ownTask = node.task.getOrElse("")
    node.in.traverse { upId =>
      store.findNode(upId).map {
        case Some(up) =>
          up.result.map(res => s"=== Node ${up.name} ===\n$res")
        case None => None
      }
    }.map(_.flatten).map { upstream =>
      (List(ownTask).filter(_.nonEmpty) ++ upstream).mkString("\n\n") + "\n\n" + NodeEngine.ProtocolFootnote
    }

  private def spawnAndRun(node: NodeDef, inputText: String): IO[Unit] =
    val nodeId = node.id
    for
      agentOpt <- EntityLoader.loadAgent(node.agent)
      _ <- agentOpt match
        case None =>
          failNode(nodeId, s"agent '${node.agent}' not found in global library")
        case Some(entry) =>
          // 阶段 2b Plugins（§B.4 第 4 步，spawn 前执行）：① 解析 node.plugins
          // （untrusted/不存在/装载非法 → failNode，错误消息列明原因——分配失败是
          // 节点级失败，不静默降级）；② skill 全文读出 + ${SKILL_DIR} 替换
          // （SkillService.loadSkill 单点复用）组装 <injected-plugins> 块；
          // ③ MCP server 启动 + 引用记账（PluginMcpManager，启动失败 → failNode）。
          // 三步全部发生在状态翻转（status=Running）之前——失败路径零 running 残留。
          // §E.3 preset 消费（协议符合度批接通，2b 遗留）：同样翻转前 failNode。
          nodePresetDef(node, entry).flatMap {
            case Left(err) => failNode(nodeId, err)
            case Right(baseDef) =>
              prepareNodePlugins(node).flatMap {
                case Left(err) => failNode(nodeId, err)
                case Right(prepared) =>
                  val sessionId = s"node-${java.util.UUID.randomUUID().toString.take(8)}"
                  val inputWithPlugins =
                    if prepared.injectedBlock.isEmpty then inputText
                    else inputText + "\n\n" + prepared.injectedBlock
                  resources.pluginMcp.acquire(sessionId, prepared.mcpPlugins).flatMap {
                    case Left(err) => failNode(nodeId, err)
                    case Right(grant) =>
                      // 回收兜底（§B.4 第 5 步）：completed/failed/cancelled/blocked 全
                      // 终态汇合点=runWithAgent 完成；异常中止路径由 guarantee 补位。
                      // release 幂等（PluginMcpManager 内 no-op 语义），双保险不重复卸载。
                      runWithAgent(node, baseDef, inputWithPlugins, sessionId, prepared, grant)
                        .guarantee(resources.pluginMcp.release(sessionId))
                  }
              }
          }
    yield ()

  /** §E.3 preset 消费接通（协议符合度批补齐，2b 遗留）：node.preset →
    * PresetResolver 单点解析（Delegate/SubTask #291 先例同款）——预设链写入
    * AgentDef.model/preset/modelOverride，modelOverride 经 ContextRefresher
    * 每 turn 热重载保活。解析失败（不存在/空链，错误含可用预设清单）= 节点级
    * 失败，状态翻转前 failNode（与插件准备同纪律：失败路径零 running 残留）。
    * node.preset 缺省 → 原 AgentDef 原样透传（旧行为零变化）。 */
  private def nodePresetDef(node: NodeDef, entry: nebflow.core.entity.AgentEntry): IO[Either[String, nebflow.agent.AgentDef]] =
    IO.blocking {
      PresetResolver.applyPreset(PresetStore(), entry.toAgentDef, node.preset).left.map { err =>
        s"Node '${node.name}' preset '${node.preset.getOrElse("")}' unresolved: $err " +
          "(§E.3 node.preset consumption — presets live in model-presets.json)"
      }
    }

  /** node.plugins → 可分配能力（§B.4 第 4 步 ①②，feature flag §G.2 开关）：
    * flag off / 无分配 → 空 preparation（旧行为零变化）；解析失败 → Left
    * （failNode，错误含审批指引）。 */
  private def prepareNodePlugins(node: NodeDef): IO[Either[String, NodeEngine.PluginPreparation]] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure(Right(NodeEngine.PluginPreparation.empty))
      case true =>
        if node.plugins.isEmpty then IO.pure(Right(NodeEngine.PluginPreparation.empty))
        else
          node.plugins.traverse(PluginRegistry.resolve).flatMap { resolved =>
            val errors = resolved.collect { case Left(e) => e }
            if errors.nonEmpty then IO.pure(Left(errors.mkString(" | ")))
            else
              val defs = resolved.collect { case Right(d) => d }
              defs.traverse(injectedPluginBlock).map { blocks =>
                val inner = blocks.collect { case Right(b) if b.nonEmpty => b }
                Right(NodeEngine.PluginPreparation(
                  injectedBlock =
                    if inner.isEmpty then ""
                    else s"<injected-plugins>\n${inner.mkString("\n\n")}\n</injected-plugins>",
                  mcpPlugins = defs.filter(_.mcpServers.nonEmpty),
                  builtinTools = defs.flatMap(_.toolsExtension).distinct
                ))
              }
          }
    }

  /** 逐 plugin 逐 skill 读 SKILL.md 全文（frontmatter 去除 + ${SKILL_DIR} 替换，
    * SkillService.loadSkill 单点复用——修复「模型自读拿不到替换」缺口，§B.4 ②）。
    * 文件不可读 → Left（节点级失败，不静默降级）。 */
  private def injectedPluginBlock(defn: PluginRegistry.PluginDef): IO[Either[String, String]] =
    if defn.skills.isEmpty then IO.pure(Right(""))
    else
      defn.skills.traverse { sk =>
        SkillService.loadSkill(sk.path).flatMap {
          case Some(content) =>
            IO.pure[Either[String, String]](Right(
              s"""<plugin name="${defn.name}" skill="${sk.name}">
                 |${content.content}
                 |</plugin>""".stripMargin))
          case None =>
            IO.pure[Either[String, String]](Left(
              s"Plugin '${defn.name}' skill '${sk.id}' file unreadable: ${sk.path} — allocation refused (no silent degradation)"))
        }
      }.map { blocks =>
        blocks.find(_.isLeft) match
          case Some(Left(err)) => Left(err)
          case _ => Right(blocks.collect { case Right(b) => b }.mkString("\n\n"))
      }

  /** 信任门运行时重验（§B.5 信任联动，ProjectActor.TtlTick 30s 驱动）：停用
    * digest 失效的运行中 plugin MCP + 对持有会话发系统提醒。flag off → no-op。 */
  def revalidatePluginTrust(): IO[Unit] =
    PluginsConfig.enabled.flatMap {
      case false => IO.unit
      case true =>
        resources.pluginMcp.revalidate(
          PluginRegistry.scan(),
          (sessionId, text) =>
            resources.agentRegistry.get.flatMap { reg =>
              reg.get(sessionId).map(_.ref) match
                case Some(ref) =>
                  (ref ! AgentCommand.ImmediateInput(text, source = Some("system"))).void
                case None => IO.unit // 会话已终结——提醒无投递面，server 已停即足够
            }
        ).void
    }

  private def runWithAgent(
    node: NodeDef,
    baseDef: nebflow.agent.AgentDef, // §E.3 preset 消费：已过 PresetResolver 的 AgentDef（协议符合度批）
    inputText: String,
    sessionId: String,
    prepared: NodeEngine.PluginPreparation,
    grant: PluginMcpManager.Grant
  ): IO[Unit] =
    val nodeId = node.id
    // sessionId 由 spawnAndRun 生成传入（阶段 2b：plugin MCP acquire 引用记账
    // 需在 spawn 前拿到同一 id——终态回收 release(sessionId) 靠它对账）。
    // projectRoot 解析（20260903 worktree 参数修复）：归一化 + worktrees/ 权威
    // 位置优先、顶层存量 fallback 的双查单点在 PathUtil.resolveNodeProjectRoot
    // （与 NodeEdit 校验同源，参照系唯一）。顶层命中与旧公式
    // `(os.Path(workspace) / ".nebflow" / wt).toString` 逐字节一致（回归红线）；
    // 两处均不存在 → 旧公式路径；损坏存储值 → workspace 兜底（不再 InvalidSegment
    // 炸 spawn）并 warnSync 留痕（QC P2：fail-safe 必须可排查）。
    val projectRoot =
      node.worktree match
        case Some(wt) if PathUtil.normalizeWorktree(wt).isLeft =>
          val pr = PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
          logger.warnSync(
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)")
          pr
        case _ => PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
    val nodeName = node.name
    for
      // 在飞登记先行（清场 c-① liveness 误杀防护 20260903）：running 表先于
      // status=Running 翻转——NodeList liveness / abandon / NodeCancel 收殓判定
      // 以「running 表含此节点」为活会话信号，若先翻转后登记，spawn 窗口内的
      // 节点会被误判死会话（false-dead → 可被收殓 = 误杀）。
      cancelSig <- Deferred[IO, Unit]
      _ <- running.update(_ + (nodeId -> cancelSig))
      // 状态 → running + startedAt（WS nodeUpdated，NodeList 同构 payload）。
      // 竞态修复（与终态化同族）：running 迁移落在事务内现读的 fresh 节点上——
      // getNode 快照经 EntityLoader 加载 agent 期间可能已落后，陈旧 copy 写回会
      // 覆盖该窗口内的接线变更；None = 节点已消失 → 中止 spawn（拒写复活垃圾行）。
      // barrier 事务性复核（fix a 启动判定完整性 20260903）：startNode 入口的
      // in ⊆ deliveredTo 检查沿 getNode 快照，快照与翻转之间并发接线（edit 追加
      // in）可增长 in——沿陈旧快照放行 = 以不完整 barrier 提前启动（未等齐）。
      // 复核与翻转并入同一 mutate 事务：fresh barrier 未齐 → 拒翻转不 spawn
      //（status 保持原态，等真正等齐时的投递/结算方重试 startNode）。
      flipped <- store.mutate { s =>
        s.nodes.get(nodeId) match
          case Some(fresh) if !fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            s.copy(nodes = s.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Running,
              startedAt = Some(System.currentTimeMillis()))))
          case _ => s
      }
      _ <- flipped.nodes.get(nodeId) match
        case Some(runningDef) if runningDef.status == NodeLifecycle.Running =>
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(runningDef, System.currentTimeMillis()))
        case _ =>
          // 回滚在飞登记并中止：节点已消失（原语义）或 barrier 未齐（fix a 新增
          // ——并发接线增长被事务复核拦下；错误由 runDetached 等调用方日志承载）。
          running.update(_ - nodeId) *>
            IO.raiseError(new RuntimeException(
              s"Node '$nodeName' ($nodeId) start aborted — node vanished or in-barrier not settled (concurrent rewiring)"))
      resultDeferred <- Deferred[IO, Either[FailOutcome, List[Message]]]
      // 阶段 2b Plugins（§B.4 第 4 步 ③）：plugin MCP 前缀来源 + 内建工具授予
      // 进该会话 allowedSet（buildAllowedToolSet 扩展消费；仅运行时 AgentDef，
      // 不落 agent.json）。bridge actor 已由投递可靠性批次迁移至 spawnAgentActor
      // 之后（升级 ctx.watch 会话死亡兜底）——旧内联位置删除，本行仅保留 2b 的
      // agentDef 扩展语义。
      agentDef = baseDef.copy(
        pluginMcpServers = grant.serverIds,
        pluginTools = prepared.builtinTools
      )
      ref <- NodeRunner.spawnAgentActor(
        system,
        NodeRunner.SpawnParams(
          agentDef = agentDef,
          resources = resources,
          sessionId = sessionId,
          sessionName = nodeName,
          depth = 1,
          parentRef = None, // 节点无 Mail 身份（§硬约束）
          // #28 可观测接线：节点事件必须经路由包装注入 rootSessionId（前端
          // sessionBgAgents 归桶键）+ nodeSessionId（popup/历史路由）——否则
          // 子 agent 事件无法在 subagent 面板归到根会话（与 Delegate/SubTask
          // 同一可观测性标准）。
          wsSend = NodeRunner.routeSubagentWsSend(wsSendFn, rootSessionId, sessionId),
          projectRoot = Some(projectRoot),
          safetyMode = "confirm-edits",
          rootSessionId = rootSessionId,
          isFlowNode = true, // leaf 剥离（与 flow 节点一致：无 Node 工具/展示类）
          // 阶段 2a 沙箱（§A.6）：dev/修复节点 root=<workspace>/.nebflow/<wt>、
          // merge 节点 root=workspace——物理隔离，最小权限。
          sandboxEnabled = true
        )
      )
      // Bridge actor：捕获 Completed/Failed/Cancelled → Deferred（同步 complete，
      // 防 Behaviors.stopped 竞态——同 FlowDagExecutor 教训）。
      // 缺口1（2026-09-04 会话死亡假尸修复，EphemeralAgentRunner 先例同款）：
      // bridge ctx.watch(agentRef)——会话无终态事件死亡（processing.onError 静默
      // 回 idle 后被 Stop、裸崩溃退出 loop、外部 fiber cancel）时 Terminated 在此
      // 兜底完成 deferred，engine fiber 不再永久挂在 race 上、节点不再滞留 running
      // 成假尸。Terminated-without-event 只可能是异常死亡：全部合法取消路径
      // （NodeCancel→cancelSig、AgentControl/TaskStuckWatcher giveUp→
      // AgentEvent.Cancelled）都先于死亡到达 bridge——故按 **failed** 语义终态化
      // （failNode 既有链：WARN + deliverFailed；下游 barrier/deps 按既有 failed
      // 语义零改动，不伪造结果投递）。WARN 含 sessionId+nodeId+失败原因。
      bridgeRef <- system.spawn(
        Behaviors.setup[AgentEvent] { bctx =>
          bctx.watch(ref) *>
            IO.pure(new Behavior[AgentEvent]:
              def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
                event match
                  case AgentEvent.Completed(_, messages) =>
                    resultDeferred.complete(Right(messages)).void.as(Behaviors.stopped)
                  case AgentEvent.Failed(_, err) =>
                    resultDeferred
                      .complete(Left(FailOutcome(Option(err.message).getOrElse("unknown error"))))
                      .void
                      .as(Behaviors.stopped)
                  case AgentEvent.Cancelled(_, reason) =>
                    resultDeferred.complete(Left(FailOutcome(s"cancelled: $reason"))).void.as(Behaviors.stopped)

              override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
                signal match
                  case SystemSignal.Terminated(_) =>
                    logger.warn(
                      s"Node '$nodeName' ($nodeId) session '$sessionId' terminated WITHOUT a terminal event " +
                        "(crashed or stopped unexpectedly) — finalizing node as failed")
                    resultDeferred
                      .complete(Left(FailOutcome(
                        s"node session '$sessionId' terminated without a terminal event (session crashed or was stopped unexpectedly)")))
                      .void
                      .handleErrorWith(_ => IO.unit) // 与正常终态事件竞争时败方静默
                      .as(Behaviors.stopped)
            )
        },
        s"nodebridge-${sessionId.take(8)}"
      )
      // 卡死处置接线（与 ProjectActor 分发器注册同款）：supervisorRef=bridgeRef
      // ——bridge 的 Cancelled 分支（resultDeferred.complete(Left(cancelled))）
      // 成为节点的取消通道，AgentControl cancel / 面板 cancelAgent /
      // TaskStuckWatcher giveUp 共用。cancelled 走 cancelNode（status=cancelled
      // + TTL + 停 agent + running 清理）= NodeCancel 同语义，不会像 raw Stop
      // 那样把 engine fiber 永远挂在 resultDeferred 上。
      // startedAt/lastActivityMs=now：list 的 up/idle 列 + watcher 从出生可见。
      _ <- resources.agentRegistry.update(
        _ + (
          sessionId -> AgentRecord(
            sessionId,
            ref,
            AgentKind.Flow,
            rootSessionId,
            startedAt = System.currentTimeMillis(),
            lastActivityMs = System.currentTimeMillis(),
            supervisorRef = Some(bridgeRef)
          )
        )
      )
      _ <- (ref ! AgentCommand.UserInput(text = inputText, replyTo = Some(bridgeRef))).void
      // 等待完成——不设超时（硬约束）；唯一竞争事件 = NodeCancel 信号。
      outcome <- IO.race(resultDeferred.get, cancelSig.get)
      _ <- outcome match
        case Right(_) =>
          logger.info(s"Node '$nodeName' cancelled — stopping agent")
          (ref ! AgentCommand.Stop("Node cancelled")).void
        case Left(_) => IO.unit
      eventResult = outcome match
        case Left(r) => r
        case Right(_) => Left(FailOutcome("cancelled by NodeCancel"))
      _ <- resources.agentRegistry.update(_ - sessionId)
      _ <- system.stop(ref).handleErrorWith(_ => IO.unit)
      _ <- system.stop(bridgeRef).handleErrorWith(_ => IO.unit)
      _ <- running.update(_ - nodeId)
      // 终态处理：写 result/status/ttl + 投递（§2.7）——传 nodeId，终态化内部
      // 事务内现读 fresh 节点（不沿本 fiber 启动时捕获的陈旧快照）
      _ <- eventResult match
        case Right(messages) =>
          val text = extractLastAssistantText(messages)
          completeNode(nodeId, text)
        case Left(fo) =>
          if fo.message.contains("cancelled") then cancelNode(nodeId)
          else failNode(nodeId, fo.message)
    yield ()

  // ── 终态化（§2.7）─────────────────────────────────────────
  //
  // 竞态根因修复（barrier 投递 bug 主因）：终态字段（status/result/completedAt/
  // ttlExpireAt）一律落在 mutate 事务内现读的 fresh 节点上，绝不在启动时捕获的
  // 陈旧快照上 copy 写回。运行期间 NodeTools.setOut 的接线改写（out/in 反向一致）
  // 与 deliverOut 的 deliveredTo 增量都在 fresh 上原样保留；投递沿 fresh.out。
  // 实证：n-95271231 两上游运行中被改接线 → 完成时陈旧副本回写 out=null →
  // deliverOut 沿空 out 悬空 → 下游永久 wiring；n-ab4a884f 同理回写覆盖成 Nebula。
  // 节点已不在活动区（被移除）→ 拒写拒投（陈旧写回会把它复活成垃圾行）。

  private def completeNode(nodeId: String, resultText: String): IO[Unit] =
    // blocked 分流（设计 §1.3/§2.1）：最终输出以 BLOCKED 锚定 → blockedNode；
    // 非 BLOCKED 开头 → 继续分流。分流顺序（20260903 暂停/人在回路设计 §2.2，
    // 语义优先级）：**blocked 优先于 hold**——节点申告「无法继续」是比「暂停等
    // 放行」更强的信号，BLOCKED 输出即使在 hold 节点上也走 blockedNode +
    // FeedbackRouter（重入），不得被 held 吞掉。
    BlockedReader.parse(resultText) match
      case Some(feedback) => blockedNode(nodeId, feedback)
      case None =>
        // hold 分流（20260903 暂停/人在回路设计 §2.2）：hold=true 节点完成 →
        // heldNode（结果落库 + 通知 Nebula，不投递不结算，等 NodeEdit release）。
        // 分流判定沿 fresh 快照；heldNode 事务内二次守卫（纵深防御，同 blockedNode
        // R2 纪律）。非 hold / 状态已变 → completed 原路径（语义零改动）。
        store.getNode(nodeId).flatMap {
          case Some(fresh) if fresh.status == NodeLifecycle.Running && fresh.hold =>
            heldNode(nodeId, resultText)
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            // 产物完整性闸门（audit 20260905 机制建议）：completed 出口三合法态
            // 校验（a 已提交+b commit-ready 申报+c 零改动；脏且未申报 → Reject）。
            // 闸门后置于 blocked/hold 分流（自报 blocked 优先保持既有语义，作者
            // 口径「gate 后置」）；Reject → blockedNode 转 blocked（复用 BLOCKED
            // 反馈协议：不走 out 投递、结果不丢弃，重入协议处置）——堵「节点自报
            // completed 但产物滞留」静默丢失。fail-open 见 CompletionGate.check。
            CompletionGate.check(workspace, fresh.worktree, resultText, gateRunner).flatMap {
              case CompletionGate.Pass(reason) =>
                logger.debug(s"Node '${fresh.name}' completion gate pass: $reason")
                completedNode(nodeId, resultText)
              case CompletionGate.Reject(reason, diag) =>
                logger.warn(s"Node '${fresh.name}' completion gate reject: $reason")
                blockedNode(nodeId, CompletionGate.feedback(diag))
            }
          case _ => completedNode(nodeId, resultText)
        }

  /** completed 原路径（20260903 hold 分流提取自 completeNode，语义零改动）：
    * 落库 completed + TTL → emitEvent nodeCompleted → deliverOut → settleDeps。 */
  private def completedNode(nodeId: String, resultText: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Completed,
              result = Some(resultText),
              completedAt = Some(now),
              ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(completed) =>
          emitEvent("nodeCompleted", nodeId, NodePayload.buildNodeJson(completed, now)) *>
            logger.info(s"Node '${completed.name}' completed (result ${resultText.length} chars)") *>
            deliverOut(completed, resultText) *>
            // deps 反向结算（deps 设计 §1.3）：与 deliverOut 同一完成 fiber 顺序推进
            //（现状 deliverOut 同款语义）。blocked 分流在 completeNode 入口已与
            // completed 分叉，deps 结算只挂 completed 分支尾部——blocked ∉ completed
            // 不触发；failed（failNode）/cancelled（cancelNode）不挂 settleDeps，
            // 下游保持 pending 可见（裁定③差异语义，NodeDepsSpec T5 锁定）。
            settleDeps(completed)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before completion — result not persisted")
    yield ()

  /** deps 完成信号 → 触发依赖者（deps 设计 §1.3）：反向扫描活动区，对每个
    * deps 含本次完成节点、且自身非 running/终态的依赖者调 startNode——闸门在
    * startNode 内（deps 未全满足 / in 未归零都会静默返回）。满足判定是声明式
    * 状态查询（幂等、零记账）：同一上游既 in 又 deps 时，in 路径（deliverOut
    * barrier 归零）与 deps 路径（settleDeps）汇合同一个 startNode 入口，第二次
    * 调用被幂等跳过，冗余无害不重复 spawn。归档上游可触发——TTL 归档不影响
    * 「完成」事实（本函数由 completeNode 完成 fiber 调用时上游必在活动区；
    * 归档变体由 NodeEdit D1-deps 补触发路径覆盖，findNode 兜底）。 */
  private def settleDeps(completed: NodeDef): IO[Unit] =
    store.snapshot.flatMap { s =>
      s.nodes.values
        .filter(d => d.deps.contains(completed.id)
          && d.status != NodeLifecycle.Running
          && !NodeLifecycle.Terminal.contains(d.status))
        .toList
        .traverse_(d => startNode(d.id))
    }

  /** blocked 终态化（设计 §2.1 四条动作序列，与 completeNode 同构）：
    * ① 事务内现读 fresh（R2 纪律）→ status=Blocked / result=渲染串 / blockedFeedback /
    *    blockCount+1 / completedAt=now / ttlExpireAt=None（永不过期=待办语义 §1.4）；
    *    节点已消失/状态已变（NodeEdit 重激活竞态）→ 拒写。
    * ② emitEvent nodeUpdated（复用现有 WS 类型 + NodePayload 同构载荷，不加新事件类型）。
    * ③ 不结算下游（out=节点不做 barrier 占位投递——传播停止是特性；下游 pending
    *    由重入分发器 NodeList 可见并处置）。
    * ④ 调 FeedbackRouter（重入 / 升级 / 频率保护决策）。 */
  private def blockedNode(nodeId: String, feedback: BlockedFeedback): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Blocked,
              result = Some(BlockedReader.render(feedback)),
              blockedFeedback = Some(feedback),
              blockCount = fresh.blockCount + 1,
              completedAt = Some(now),
              ttlExpireAt = None)))
          case _ => st // 节点已消失 / 状态已变 → 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(nodeId) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val summary = s"round ${bn.blockCount}: [${feedback.category}] ${feedback.detail.take(160)}"
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(bn, now)) *>
            logger.warn(s"Node '${bn.name}' blocked — $summary") *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "blocked", summary) *>
            feedbackRouter.route(bn, feedback)
        case Some(other) =>
          logger.info(s"Node '$nodeId' state changed to '${other.status}' before blocked finalize — refused (fresh-read discipline)")
        case None =>
          logger.warn(s"Node '$nodeId' vanished before blocked finalize — feedback not persisted")
    yield ()

  /** held 挂起（20260903 暂停/人在回路设计 §2.2 四动作序列，与 blockedNode 同构）：
    * ① 事务内现读 fresh（R2 纪律，拒写已消失/状态已变）→ status=Held /
    *    result=全文 / completedAt=now / ttlExpireAt=None（永不过期 = blocked
    *    「待办语义」先例；主图保留，整链归档判定天然排除——held ∉ Terminal）。
    * ② emitEvent nodeUpdated（NodePayload 同构载荷，不加新 WS 事件类型）。
    * ③ 不结算下游：deliverOut / settleDeps 都不调（与 blocked「传播停止」同款；
    *    差异：blocked 是异常申告走 FeedbackRouter 重入，held 是主动闸门等
    *    NodeEdit release 放行）。
    * ④ deliverToNebula held 全文通知（设计 §6 决策②：全文对齐 out=Nebula 完成
    *    投递先例，Nebula 需全文才能与用户讨论）+ FlowMapEventLog "held" 摘要。
    *    通知为 fire-and-forget（不传 nodeId 不进 V8 记账）：held 通知是状态通报
    *    而非结果投递本体——结果滞留节点上，release → deliverOut 才是投递（彼时
    *    走 completed 路径的 V8 at-least-once 记账）；且 held ∉ {Completed, Failed}
    *    重投扫描本就不覆盖，不记账可避免对 out=Nebula 后续放行的记账污染。 */
  private def heldNode(nodeId: String, resultText: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Held,
              result = Some(resultText),
              completedAt = Some(now),
              ttlExpireAt = None)))
          case _ => st // 节点已消失 / 状态已变 → 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(nodeId) match
        case Some(hn) if hn.status == NodeLifecycle.Held =>
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(hn, now)) *>
            logger.info(s"Node '${hn.name}' held — awaiting release (result ${resultText.length} chars)") *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "held",
              s"node completed but held (hold=true) — result ${resultText.length} chars, awaiting NodeEdit release") *>
            deliverToNebula(s"[Node '${hn.name}' completed — held, awaiting release]\n$resultText", hn.name, NodeLifecycle.Held)
        case Some(other) =>
          logger.info(s"Node '$nodeId' state changed to '${other.status}' before held finalize — refused (fresh-read discipline)")
        case None =>
          logger.warn(s"Node '$nodeId' vanished before held finalize — result not persisted")
    yield ()

  /** release 放行（20260903 暂停/人在回路设计 §2.3）：held → completed 的唯一出口。
    * 1. fresh-read 守卫：节点存在且 status==Held，否则报错。
    * 2. 单事务 mutate（同一 FlowMapState 同时改两节点，原子）：
    *    a. 本节点 Held→Completed，ttlExpireAt=now+TtlDisplayMs（completedAt 保留
    *       held 时刻——那是工作完成的时刻；显示倒计时从放行起算）；
    *    b. note 注入（note 非空时）：out 目标（NodeEdit 校验①保证建 hold 时为节点
    *       id；held 期改接 out=Nebula 的边角 → note 无落点）且 status ∈ {wiring,
    *       pending} → target.task += 用户补充段（目标不可能已运行：其 in 含本节点
    *       而本节点从未投递 → barrier 永不归零；守卫仅纵深防御，若目标异常运行则
    *       note 不注入并在返回文本说明）。
    * 3. 事务后（既有完成路径复用）：emitUpdated 同步 → deliverOut(completed) →
    *    settleDeps(completed) **fork 到独立 fiber**——startNode 同步等下游终态，
    *    直接调用会把 NodeEdit 工具 fiber 卡到下游链条跑完（runDetached 教训同款）；
    *    放行后下游启动前的全部闸门（目标自身 deps、barrier 复核）由 startNode
    *    原样把关。
    * 4. FlowMapEventLog "released" + note 摘要。 */
  def releaseNode(nodeId: String, note: Option[String]): IO[Either[String, String]] =
    val noteText = note.map(_.trim).filter(_.nonEmpty)
    store.getNode(nodeId).flatMap {
      case None =>
        IO.pure(Left(s"Node '$nodeId' not found — release only applies to held nodes"))
      case Some(n) if n.status != NodeLifecycle.Held =>
        IO.pure(Left(s"Node '${n.name}' is not held (status=${n.status}) — release only applies to held nodes"))
      case Some(node) =>
        // 产物完整性闸门（audit 20260905）：held → completed 亦是 Completed 转移
        // 路径——release 不复用 completeNode（下方直接 mutate，非单一咽喉，实读
        // :681 一带实证），闸门必须两处接线。拒绝 → 节点保持 held + Left 诊断
        // （NodeEdit 工具结果对 Nebula 可见）；不走 blockedNode——其 fresh 守卫
        // 要求 status==Running，对 Held 节点会静默拒写（零效果），故此处用返回值
        // 报错而非状态转移。放行路径：先落地产物（commit）或确认申报标记，重试
        // release（result 文本在 held 时刻已定型，申报标记以落库 result 为准）。
        CompletionGate.check(workspace, node.worktree, node.result.getOrElse(""), gateRunner).flatMap {
          case CompletionGate.Reject(_, diag) =>
            val fb = CompletionGate.feedback(diag)
            IO.pure(Left(s"Node '${node.name}' release rejected — ${fb.detail} 建议: ${fb.suggestion} (node stays held)"))
          case CompletionGate.Pass(_) =>
            releaseHeld(nodeId, noteText)
        }
    }

  /** held → completed 原释放体（自 releaseNode 提出：gate 接线所需的纯提取，
    * 内部逐行原样、语义零改动；docstring 见 releaseNode）。 */
  private def releaseHeld(nodeId: String, noteText: Option[String]): IO[Either[String, String]] =
    val now = System.currentTimeMillis()
    store.mutate { st =>
      st.nodes.get(nodeId) match
        case Some(fresh) if fresh.status == NodeLifecycle.Held =>
          val withReleased = st.nodes.updated(nodeId, fresh.copy(
            status = NodeLifecycle.Completed,
            ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs)))
          // note 注入（纵深防御守卫，与 fresh 同一事务原子）
          val nodesFinal = noteText match
            case Some(txt) =>
              fresh.out match
                case Some(t) if t != "Nebula" =>
                  withReleased.get(t) match
                    case Some(tn) if tn.status == NodeLifecycle.Wiring || tn.status == NodeLifecycle.Pending =>
                      withReleased.updated(t, tn.copy(task = Some(tn.task.getOrElse("") + s"\n\n${NodeEngine.ReleaseNoteMarker}\n$txt")))
                    case _ => withReleased // 目标缺失/异常运行 → 不注入（返回文本说明）
                case _ => withReleased // out=Nebula/None → note 无节点落点
            case None => withReleased
          st.copy(nodes = nodesFinal)
        case _ => st // 状态已变（并发 abandon/release）→ 拒写（下方状态复核捕获）
    }.flatMap { s =>
      s.nodes.get(nodeId) match
        case Some(released) if released.status == NodeLifecycle.Completed =>
          val noteDelivered = noteText match
            case Some(_) =>
              released.out match
                case Some(t) if t != "Nebula" =>
                  s.nodes.get(t).exists(tn => tn.task.exists(_.contains(NodeEngine.ReleaseNoteMarker)))
                case _ => false
            case None => true
          val noteSummary = noteText.map(t => s"; note → ${released.out.getOrElse("?")}: ${t.take(80)}").getOrElse("")
          emitUpdated(released) *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "released",
              s"held node released → completed (display TTL restarted; completedAt kept at held moment)$noteSummary") *>
            // 传播链 fork（设计 §2.3 第 3 步）：deliverOut → settleDeps 后台推进，
            // 工具调用立即返回（阻塞教训见方法注释）。result 理论恒 Some（heldNode
            // 落全文）；防御 None → 跳过投递仅结算 deps。
            ((released.result match
              case Some(res) => deliverOut(released, res) *> settleDeps(released)
              case None => settleDeps(released)
            ).handleErrorWith(e =>
              logger.error(s"[$projectName] release propagation for '${released.name}' failed: ${Option(e.getMessage).getOrElse(e.toString)}")
            ).start.void) *>
            IO.pure(Right(
              s"Node '${released.name}' released — held → completed (display TTL restarted from release)" +
                (noteText match
                  case Some(_) if noteDelivered => "; user note injected into out target's task"
                  case Some(_) => "; WARNING: user note NOT injected (out target missing or not in wiring/pending state)"
                  case None => "")
            ))
        case Some(other) =>
          IO.pure(Left(s"Node '${other.name}' changed state before release finalize (status=${other.status}) — concurrent edit?"))
        case None =>
          IO.pure(Left(s"Node '$nodeId' vanished before release finalize"))
    }


  private def failNode(nodeId: String, err: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Failed,
              result = Some(err),
              completedAt = Some(now),
              ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(failed) =>
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(failed, now)) *>
            logger.warn(s"Node '${failed.name}' failed: ${err.take(200)}") *>
            // 失败投递（§2.7）：out=Nebula → failed 消息；out=节点 → collect 结算（占位符）。
            deliverFailed(failed, err)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before failure finalize — error not persisted")
    yield ()

  private def cancelNode(nodeId: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Cancelled,
              completedAt = Some(now),
              ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(cancelled) =>
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(cancelled, now)) *>
            logger.info(s"Node '${cancelled.name}' cancelled")
        case None =>
          logger.warn(s"Node '$nodeId' vanished before cancel finalize — skipped")
    yield ()

  // ── 投递（§2.7）─────────────────────────────────────────

  /** out=节点：barrier 归零 → 启动下游。dedup 用 deliveredTo（防改接重投）。 */
  private def deliverOut(node: NodeDef, resultText: String): IO[Unit] =
    node.out match
      case None => IO.unit // 悬空：结果保留在 result（持久化），接线后自动投递
      case Some("Nebula") =>
        deliverToNebula(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed", Some(node.id))
      case Some(targetId) =>
        for
          targetOpt <- store.findNode(targetId)
          _ <- targetOpt match
            case None =>
              logger.warn(s"Node '${node.name}' out target '$targetId' not found — result retained")
            case Some(target) =>
              // 原子：target.deliveredTo += node.id（dedup）+ 更新
              store.mutate { s =>
                val t = s.nodes.get(targetId)
                t match
                  case Some(tn) if !tn.deliveredTo.contains(node.id) =>
                    s.copy(nodes = s.nodes.updated(targetId, tn.copy(deliveredTo = tn.deliveredTo :+ node.id)))
                  case _ => s
              }.flatMap { s =>
                val tn = s.nodes.get(targetId)
                val allArrived = tn.exists(tn2 => tn2.in.forall(upId => tn2.deliveredTo.contains(upId)))
                if allArrived && tn.exists(_.status != NodeLifecycle.Running) then startNode(targetId)
                else IO.unit
              }
        yield ()

  private def deliverFailed(node: NodeDef, err: String): IO[Unit] =
    node.out match
      case None => IO.unit
      case Some("Nebula") =>
        deliverToNebula(s"[Node '${node.name}' failed]\n$err", node.name, "failed", Some(node.id))
      case Some(targetId) =>
        // collect 结算：占位符标记 + 计数归零继续（§2.4 默认 collect）。
        // 合并节点例外（merge-node 批 20260905 §触发语义，MergeNodePolicy 单点）：
        // 占位结算会让合并在不完整输入上启动 → 改转 blocked 可见终态不悬挂。
        for
          targetOpt <- store.findNode(targetId)
          _ <- targetOpt match
            case Some(target) if MergeNodePolicy.haltsOnFailure(target) =>
              mergeBlockedByUpstreamFailure(target, node, err)
            case Some(target) =>
              store.mutate { s =>
                val t = s.nodes.get(targetId)
                t match
                  case Some(tn) if !tn.deliveredTo.contains(node.id) =>
                    s.copy(nodes = s.nodes.updated(targetId, tn.copy(deliveredTo = tn.deliveredTo :+ node.id)))
                  case _ => s
              }.flatMap { s =>
                val tn = s.nodes.get(targetId)
                val allArrived = tn.exists(tn2 => tn2.in.forall(upId => tn2.deliveredTo.contains(upId)))
                if allArrived && tn.exists(_.status != NodeLifecycle.Running) then startNode(targetId)
                else IO.unit
              }
            case None => IO.unit
        yield ()

  /** 合并节点因上游失败转 blocked（merge-node 批 §触发语义②；与 blockedNode 同构
    * 但**不经 FeedbackRouter**）：① 事务内现读 fresh（R2 纪律，haltsOnFailure 只认
    * wiring/pending）→ status=Blocked / result=渲染串 / blockedFeedback=合成反馈 /
    * completedAt=now / ttlExpireAt=None（待办语义）。blockCount 不增（非节点自报
    * 轮次，重入主责在失败上游侧，MergeNodePolicy.upstreamFailureFeedback）。
    * ② emitEvent nodeUpdated + FlowMapEventLog "merge-blocked" + deliverToNebula
    * 通报（无 nodeId=fire-and-forget，对齐 escalate 通道：状态通报不是结果投递）。
    * 语义收益：不触发合并（占位结算被旁路）+ 不永久 pending 悬挂（blocked 可见
    * 终态，分发器 NodeList 可巡检）+ 已完成上游的结算保留（修复上游 → 重激活合并
    * 节点 → 既有重激活重投链自动补齐 barrier）。 */
  private def mergeBlockedByUpstreamFailure(target: NodeDef, failed: NodeDef, err: String): IO[Unit] =
    val feedback = MergeNodePolicy.upstreamFailureFeedback(failed, err)
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(target.id) match
          case Some(fresh) if MergeNodePolicy.haltsOnFailure(fresh) =>
            st.copy(nodes = st.nodes.updated(target.id, fresh.copy(
              status = NodeLifecycle.Blocked,
              result = Some(MergeNodePolicy.renderBlocked(feedback)),
              blockedFeedback = Some(feedback),
              completedAt = Some(now),
              ttlExpireAt = None)))
          case _ => st // 状态已变（并发终态化）→ 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(target.id) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val summary = s"merge node blocked: upstream '${failed.name}' (${failed.id}) failed — ${err.take(140)}"
          emitEvent("nodeUpdated", target.id, NodePayload.buildNodeJson(bn, now)) *>
            logger.warn(s"Node '${bn.name}' $summary") *>
            FlowMapEventLog.append(workspace, projectName, target.id, "merge-blocked", summary) *>
            deliverToNebula(
              s"[Node '${bn.name}' blocked — 上游 '${failed.name}' failed，合并未执行]\n${err.take(800)}",
              bn.name, NodeLifecycle.Blocked)
        case _ => IO.unit
    yield ()

  /** out=Nebula：ImmediateInput 投 Nebula 根会话（source="node"，复用 flow 气泡语义）。
    * 气泡 header 契约（前端 chat.js injectedSourceLabel node 分支）：
    *   source = "node" → 显示段 NODE
    *   eventType = status（"completed" | "failed"）→ 显示段 COMPLETED / FAILED
    *   sender = "<projectName>/<nodeName>" → 显示段 <项目名> · <节点名>
    * 整条 header = NODE · <项目名> · <节点名> · <状态>。
    *
    * V8 (2026-09-03)：带 nodeId 的调用（deliverOut/deliverFailed/deliverOutTo/重投
    * 扫描）在 offer 后写 nebulaDeliveredAt 记账（at-least-once：offer 与记账之间
    * 崩溃 → 重投扫描会再投一次，宁重复不丢失）。nodeId=None（FeedbackRouter
    * escalate 复用通道）保持 fire-and-forget——blocked 反馈本体已持久化在节点上，
    * 升级消息不进重投扫描（避免对已处置的 blocked 再升级）。根 ref 缺失时不丢弃：
    * 不记账 → 周期重投扫描在根会话可用后补投。 */
  private def deliverToNebula(text: String, nodeName: String, status: String, nodeId: Option[String] = None): IO[Unit] =
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        // 缺口4：同 (identity, status) 60s 窗口去重——抑制重复 offer（首投已入
        // 根会话队列），仍刷新账本（账本与通知解耦：本表只压秒级抖动，V8
        // at-least-once 语义不变）。不同 status 天然独立窗口（held→released、
        // running→completed 互不挡）。
        dedupeNebulaDelivery(nodeId.getOrElse(nodeName), status).flatMap {
          case true =>
            logger.warn(
              s"[dedup] suppressed duplicate Nebula delivery (identity=${nodeId.getOrElse(nodeName)}, status=$status, window=${NodeEngine.NebulaDedupWindowMs}ms, rootSession=$rootSessionId)") *>
              nodeId.traverse_(id => markNebulaDelivered(id)).void
          case false =>
            (ref ! AgentCommand.ImmediateInput(
              text,
              source = Some("node"),
              eventType = Some(status),
              sender = Some(s"$projectName/$nodeName")
            )) *> nodeId.traverse_(id => markNebulaDelivered(id)).void
        }
      case None =>
        logger.warn(s"Root session '$rootSessionId' not found — node result parked for redelivery scan (nodeName=$nodeName)")
        IO.unit
    }

  /** 缺口4：去重判定+登记（固定窗：首投时间戳起算 60s，不滑动；过期条目顺路
    * 淘汰=时间窗淘汰）。true = 窗口内重复（应抑制 offer）。 */
  private def dedupeNebulaDelivery(identity: String, status: String): IO[Boolean] =
    IO(System.currentTimeMillis()).flatMap { now =>
      recentNebulaDeliveries.modify { m =>
        val live = m.view.filter { case (_, ts) => now - ts < NodeEngine.NebulaDedupWindowMs }.toMap
        live.get((identity, status)) match
          case Some(_) => (live, true)
          case None    => (live.updated((identity, status), now), false)
      }
    }

  /** V8: 写 nebulaDeliveredAt 记账（活动区优先，归档区兜底——TTL 归档的
    * 未投递节点同样要记账，否则扫描每次重启都重投）。 */
  private def markNebulaDelivered(nodeId: String): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case Some(_) =>
        store.mutate { s =>
          s.nodes.get(nodeId) match
            case Some(n) => s.copy(nodes = s.nodes.updated(nodeId, n.copy(nebulaDeliveredAt = Some(System.currentTimeMillis()))))
            case None    => s
        }.void
      case None =>
        store.mutateArchive { a =>
          a.nodes.get(nodeId) match
            case Some(n) => a.copy(nodes = a.nodes.updated(nodeId, n.copy(nebulaDeliveredAt = Some(System.currentTimeMillis()))))
            case None    => a
        }.void
    }

  /**
   * V8 (2026-09-03): 重启重投扫描——扫活动区+归档区全部「终态（completed/failed）
   * + out=Nebula + 有 result + 未记账」的节点，补投并记账。挂载时立即跑一次
   * （ProjectRuntimeRegistry.mount），此后挂载在 TtlTick 周期（GatewayMain 启动的
   * projectTtlScanner，30s）——启动期根会话 actor 通常尚未 spawn（懒加载），周期
   * 扫描保证根会话可用后的 30s 内补投。根 ref 缺失时静默跳过（结果滞留 map，
   * 不消费不记账，下个 tick 再试）。返回本次补投的节点数（>0 时 actor 记 info）。
   */
  def redeliverUnconsumedNebulaResults(): IO[Int] =
    resources.agentRegistry.get.flatMap { registry =>
      if !registry.contains(rootSessionId) then IO.pure(0)
      else
        for
          active <- store.snapshot
          arch <- store.archiveSnapshot
          now <- IO(System.currentTimeMillis())
          unpaid = (active.nodes.values ++ arch.nodes.values)
            .filter(n =>
              (n.status == NodeLifecycle.Completed || n.status == NodeLifecycle.Failed) &&
                n.out.contains("Nebula") &&
                n.result.exists(_.trim.nonEmpty) &&
                n.nebulaDeliveredAt.isEmpty
            )
          // 缺口3（2026-09-04 夹具信封排除）：名字命中夹具家族 ∧ 任务载荷带夹具
          // 自证标记 → 排除投递 + WARN（09-03 实证 cancel-test-* 夹具家族轰炸根
          // 会话）。排除后照常记账（nebulaDeliveredAt 置位=通知通道对该信封关闭）
          // ——否则 30s 周期扫描每轮重复 WARN。结果不删不改，滞留节点可查。
          fixtures = unpaid.filter(NodeEngine.isFixtureEnvelope).toList
          _ <- fixtures.traverse_(n =>
            logger.warn(
              s"[fixture-guard] excluded fixture envelope from redelivery scan: node '${n.name}' (${n.id}) status=${n.status} — name matches fixture family and task carries fixture marker")
              *> markNebulaDelivered(n.id))
          pending = unpaid.filterNot(NodeEngine.isFixtureEnvelope).toList
          // 缺口2（2026-09-04 补投新鲜度门控）：completedAt 距今 ≤24h（与节点显示
          // TTL 同口径）= 新鲜欠账 → 逐条投（既有形态零改动）；>24h = 历史欠账 →
          // 同批合并单条汇总通知（结果照投不丢：每节点 id+状态+摘要入汇总并逐节点
          // 记账）。completedAt 缺失 = 无法判旧 → 按新鲜逐条（宁投勿丢）。首次实时
          // 投递（completeNode/deliverOut）不走此路径，零改动。
          // MUTATION-2 已恢复：新鲜度门控（缺口2）——completedAt 距今 ≤24h 逐条、>24h 合并
          (fresh, stale) = pending.partition(n => n.completedAt.forall(c => now - c <= NodeEngine.StaleRedeliveryMs))
          _ <- fresh.traverse_(n =>
            deliverToNebula(s"[Node '${n.name}' ${n.status}]\n${n.result.get}", n.name, n.status, Some(n.id)))
          _ <- if stale.nonEmpty then deliverStaleSummary(stale) else IO.unit
          _ <- if pending.nonEmpty then
            logger.info(s"Node redelivery scan: re-delivered ${pending.size} unconsumed out=Nebula result(s) to root '$rootSessionId' (fresh=${fresh.size} stale-merged=${stale.size} fixture-excluded=${fixtures.size})")
          else IO.unit
        yield pending.size
    }

  /** 缺口2：历史欠账（completedAt >24h）同批合并单条汇总通知（形态 (a) 取舍：
    * 纯后端立即生效，不依赖前端改动——形态 (b) 载荷打标记需后续前端批才可见）。
    * 一条文本含 N 条 nodeId+状态+结果摘要（160 字/条）。eventType：混含 failed →
    * "failed"（强提醒），全 completed → "completed"。**绕过缺口4 去重直投**——
    * 汇总文本每批唯一，若走 deliverToNebula 会与 60s 前一批汇总同 key 相撞被
    * 误抑制（节点已被记账=通知丢失）。offer 后逐节点记账（at-least-once：offer
    * 与记账间崩溃 → 下轮扫描重汇总，宁重复不丢失）。 */
  private def deliverStaleSummary(stale: List[NodeDef]): IO[Unit] =
    val eventType = if stale.exists(_.status == NodeLifecycle.Failed) then NodeLifecycle.Failed else NodeLifecycle.Completed
    val lines = stale.zipWithIndex
      .map((n, i) => s"${i + 1}. [${n.status}] ${n.name} (${n.id}): ${n.result.get.trim.take(NodeEngine.StaleSummaryPerNodeChars)}")
      .mkString("\n")
    val text = s"[Node 历史欠账汇总补投 ×${stale.size}（>${NodeEngine.StaleRedeliveryMs / 3600000}h 未消费，项目 $projectName）]\n$lines"
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        (ref ! AgentCommand.ImmediateInput(
          text,
          source = Some("node"),
          eventType = Some(eventType),
          sender = Some(s"$projectName/stale-redelivery-summary")
        )) *> stale.traverse_(n => markNebulaDelivered(n.id))
      case None => IO.unit // 根不可达 → 不记账不丢账，下轮扫描重汇总
    }

  /** 分发器会话最终输出投递（2026-09-05 作者裁定「任务分发器的结果没有任何人看见，
    * 把这个接线给 nebula」）：分发器 turn 终态时由 ProjectActor 观察桥调用——取本
    * turn 最终 assistant 文本（extractLastAssistantText），注入 Nebula 根会话。
    * 空文本（无 assistant 文本或纯空白）不投（防御性判空，debug 级留痕）。
    *
    * 格式（对照节点投递家族）：text 头部标注行 [Dispatcher '<项目名>' · task:
    * <触发任务摘要>] + 换行 + 最终输出全文，对照 "[Node '<name>' completed]"
    * 头部行家族；蓝气泡 source="dispatcher"（DispatcherSourceMarker）走前端
    * injectedSourceLabel 通用分支 → "Dispatcher · <项目名> · Completed"，
    * 与 NODE/MAIL 同族蓝气泡标注体系，零前端改动。
    *
    * 与节点投递（deliverToNebula）的关系——独立新投递种类，刻意绕过节点投递的
    * 全部账本机制（绕过去重直投有 deliverStaleSummary 先例）：
    *  - 不进 V8 nebulaDeliveredAt 账本/重投扫描：分发器输出非节点结果、无持久化
    *    载体可补投，单次会话单次投递，at-least-once 无对象；
    *  - 不进 60s 去重窗口：同项目短窗内多次触发任务是各自独立的合法投递（每次
    *    触发一个新 turn），按 (identity,status) 去重会吞掉合法的第二次；
    *  - 不走夹具信封排除：分发器输出不是节点结果，夹具家族语义不适用。
    * 忙时排队自动继承：ImmediateInput 在根会话忙 turn 时入 pendingImmediateInputs
    * （AgentActor processing 态排队、turn 边界串行 drain），不打断不丢。
    * 根会话 ref 缺失 → WARN 丢弃（无账本无重投，Flow Map 拓扑仍是事实来源）；
    * 占位/跳过类极简输出照常投递（无特殊抑制——分发器一条短行也是有效反馈）。 */
  def deliverDispatcherOutputToNebula(messages: List[Message], taskSummary: Option[String]): IO[Unit] =
    extractLastAssistantText(messages) match
      case "" =>
        logger.debug(s"Dispatcher turn produced no text output (project=$projectName) — nothing to deliver")
      case finalText =>
        resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
          case Some(ref) =>
            val header = taskSummary.map(_.trim).filter(_.nonEmpty) match
              case Some(s) => s"[Dispatcher '$projectName' · task: $s]"
              case None    => s"[Dispatcher '$projectName']"
            (ref ! AgentCommand.ImmediateInput(
              s"$header\n$finalText",
              source = Some(NodeEngine.DispatcherSourceMarker),
              eventType = Some(NodeLifecycle.Completed),
              sender = Some(projectName)
            )).void
          case None =>
            logger.warn(
              s"Root session '$rootSessionId' not found — dispatcher final output not deliverable (project=$projectName, ${finalText.length} chars dropped; no ledger/no redelivery by design)")
        }

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case m if m.role == nebflow.shared.MessageRole.Assistant => m.textContent
      }
      .filter(_.trim.nonEmpty)
      .getOrElse("")

  private case class FailOutcome(message: String)

object NodeEngine:
  /** 终态节点显示 TTL（24h——2026-09-02 作者裁定；测试档可缩短——ProjectActor 注入）。 */
  val TtlDisplayMs: Long = 24 * 60 * 60 * 1000L

  // ── 投递可靠性批次常量（2026-09-04 四缺口）──────────────────

  /** 缺口2：补投/重投新鲜度阈值（24h，取 TtlDisplayMs 同口径——超过一个显示
    * 周期未被消费的欠账即「历史欠账」，合并汇总降噪；结果照投不丢）。 */
  val StaleRedeliveryMs: Long = TtlDisplayMs

  /** 缺口2：汇总通知每节点结果摘要截断长度。 */
  val StaleSummaryPerNodeChars: Int = 160

  /** 缺口3：测试夹具信封家族（宁窄勿宽——09-03 实证宿主真实数据唯一可确证
    * 夹具家族：cancel-test 取消语义验证节点，见 phd-notebook 归档 cancel-test、
    * cancel-test-3..11；精确锚定全名，真实节点名巧合含 test 不命中）。 */
  val FixtureFamilyRegex = "^(cancel-test|cancel-test-\\d+)$".r

  /** 缺口3：夹具载荷自证标记（09-03 实证夹具任务正文均带此前缀）。双条件
    * 缺一不可：名字 ∧ 载荷双确认才排除——真实节点名巧合命中家族但载荷是
    * 真实工作 → 照常投递。 */
  val FixtureTaskMarker = "取消验证节点"

  /** 缺口3：夹具信封判定（纯函数便于单测）。 */
  def isFixtureEnvelope(node: NodeDef): Boolean =
    FixtureFamilyRegex.matches(node.name) && node.task.exists(_.contains(FixtureTaskMarker))

  /** 缺口4：同 (identity, status) 重复通知去重窗口（60s——秒级抖动口径：挂载
    * 扫描与周期扫描竞态、ProjectCreate 合并回报三连投实证 09-03）。进程内
    * 内存窗，与 V8 nebulaDeliveredAt 持久账本正交（账本管跨重启 at-least-once）。 */
  val NebulaDedupWindowMs: Long = 60_000L

  /** 分发器最终输出投递的 source 标记（2026-09-05 接线）：ImmediateInput source
    * 值。前端 injectedSourceLabel 通用分支对未知 source 首字母大写 → 蓝气泡
    * "Dispatcher · <项目名> · Completed"（与 NODE/MAIL 同族，零前端改动）。 */
  val DispatcherSourceMarker = "dispatcher"

  /** 分发器投递任务摘要截断长度（触发任务首行、单行空白折叠，超出截断加省略号）。 */
  val DispatcherTaskSummaryChars: Int = 100

  /** 阶段 2b Plugins：spawn 前解析完成的分配物（§B.4 第 4 步）。
    * empty = flag 关 / 节点无分配 / 无可注入内容——旧行为零变化。 */
  final case class PluginPreparation(
    /** <injected-plugins> 全文块（逐 plugin 逐 skill，frontmatter 已去除 +
      * ${SKILL_DIR} 已替换）；"" = 无注入。 */
    injectedBlock: String,
    /** 含 MCP server 的 plugin（acquire 输入；serverId = plugin_<p>_<s>）。 */
    mcpPlugins: List[PluginRegistry.PluginDef],
    /** org.nebflow/tools 授予的 builtin 工具名（白名单已在装载层校验）。 */
    builtinTools: List[String]
  )
  object PluginPreparation:
    val empty: PluginPreparation = PluginPreparation("", Nil, Nil)

  /** release note 注入段标记（20260903 暂停/人在回路设计 §2.4）：releaseNode 把
    * 用户补充以本标记为头追加进 out 目标 task，buildInput 天然携带进下游输入。 */
  val ReleaseNoteMarker: String = "== 用户补充（放行时注入） =="

  /** 节点 id 前缀（sessionId = "node-<uuid>"）。 */
  val SessionPrefix = "node-"

  /** 节点级 blocked 重入上限（设计 §3.1/§7.2）：blockCount 1/2 → 重入调整；
    * count=3（> 2）→ 升级 Nebula 不再重入。 */
  val MaxBlockRoundsPerNode: Int = 2

  /** blocked 声明协议脚注（设计 §1.5 文案原样，buildInput 末尾单点注入）。 */
  val ProtocolFootnote: String =
    """── 节点协议 ──
      |若你判定任务无法完成（上游依赖未就绪/任务定义不完整/能力不匹配/缺外部条件），
      |不要硬造结果：把最终输出的第一行写为 BLOCKED，随后给出 JSON：
      |{"category":"…","detail":"…","suggestion":"…"}
      |category ∈ upstream-incomplete | task-underspecified | agent-mismatch |
      |external-dependency | needs-split | other。可完成时正常输出结果，勿以 BLOCKED 开头。""".stripMargin

/** blocked 声明解析器（设计 §1.3 文法）。独立 object 便于单测。
  *
  * - 锚定：trim 后以 BLOCKED 开头（后跟 `:` 或换行/空白/串尾）——只查开头，正文含词不误判。
  * - JSON 体：首个 `{` 到末个 `}` 之间尝试 circe 解析；category 不在枚举 → other。
  * - JSON 缺失/畸形 → BlockedFeedback("other", 其余全文截断, "")。
  * - 非 BLOCKED 开头 → None（调用方走 completed 原路径无损降级）。 */
object BlockedReader:
  val Categories: Set[String] =
    Set("upstream-incomplete", "task-underspecified", "agent-mismatch", "external-dependency", "needs-split", "other")

  private val Marker = "BLOCKED"
  private val DetailCap = 500

  /** 解析节点最终输出：命中 blocked → Some(BlockedFeedback)；否则 None。 */
  def parse(resultText: String): Option[BlockedFeedback] =
    val trimmed = resultText.trim
    if !anchored(trimmed) then None
    else
      jsonBody(trimmed) match
        case Some(body) =>
          io.circe.parser.parse(body) match
            case Right(json) =>
              val cursor = json.hcursor
              val category = cursor.get[String]("category").toOption.filter(Categories.contains).getOrElse("other")
              val detail = cursor.get[String]("detail").toOption.getOrElse("")
              val suggestion = cursor.get[String]("suggestion").toOption.getOrElse("")
              Some(BlockedFeedback(category, detail, suggestion))
            case Left(_) => Some(fallback(trimmed))
        case None => Some(fallback(trimmed))

  /** 落库渲染串（设计 §1.3：result 字段人类可读形态，NodeList 摘要与详情窗共用）。 */
  def render(f: BlockedFeedback): String =
    s"[blocked:${f.category}] ${f.detail} — 建议: ${f.suggestion}"

  /** 锚定判定：BLOCKED 开头且后跟 `:` / 空白 / 串尾（"BLOCKEDxx" 不算）。 */
  private def anchored(trimmed: String): Boolean =
    trimmed.startsWith(Marker) && {
      val rest = trimmed.substring(Marker.length)
      rest.isEmpty || rest.head == ':' || rest.head.isWhitespace
    }

  /** 首个 `{` 到末个 `}` 之间（可嵌于说明文字之后）。 */
  private def jsonBody(trimmed: String): Option[String] =
    val i = trimmed.indexOf('{')
    val j = trimmed.lastIndexOf('}')
    if i >= 0 && j > i then Some(trimmed.substring(i, j + 1)) else None

  /** JSON 缺失/畸形降级：去掉 BLOCKED 标记行后的其余全文截断为 detail。 */
  private def fallback(trimmed: String): BlockedFeedback =
    val rest = trimmed.replaceFirst("^BLOCKED\\s*:?\\s*", "").trim
    val detail = if rest.length > DetailCap then rest.take(DetailCap) + "…" else rest
    BlockedFeedback("other", detail, "")
