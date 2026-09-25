/* 从 NodeEngine 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.entity.EntityLoader
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.{PluginMcpManager, PluginRegistry, PluginsConfig}
import nebflow.core.skill.SkillService
import nebflow.core.tools.BgTaskRegistry
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.shared.Message

import scala.concurrent.duration.*

private[project] trait NodeStarter:
  self: NodeEngine =>

  /**
   * 启动节点（§2.1 创建即运行：入口节点由 NodeEdit 调；下游由投递 barrier 归零调）。
   * resume（crash-recovery 批 2026-09-07 D3）：boot sweep 认领的崩溃残留节点续跑——
   * Some 时跳过 buildInput 直接用 resume prompt（水合消息经 spawn 链 initialMessages
   * 注入），CAS 翻转/deps 闸门/barrier 复核/三表登记全套照走——恢复不绕过任何启动
   * 纪律（resume 节点崩溃前已过同一闸门，deliveredTo/deps 随 flow-map 持久，重走恒真）。
   */
  /**
   * @param resume      崩溃续跑上下文（Some ⇒ 首轮输入 = resume prompt，跳过 buildInput）
   * @param loopRework  **回边返工段**（nrloop 二期 2026-09-14；缺省 None = 全部既有调用
   *                    点零行为变化）：追加在 `buildInput` 全文之后，把 verifier 的打回
   *                    意见送进目标的重跑首轮。只在非 resume 分支生效（resume 路径的输入
   *                    由 resume prompt 独占），且只由 [[reloopTo]] 传入。
   */
  def startNode(
    nodeId: String,
    resume: Option[NodeEngine.ResumeContext] = None,
    loopRework: Option[String] = None
  ): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case None => IO.unit
      case Some(node) =>
        node.status match
          // blocked 同样幂等跳过（§1.1：重跑同样 blocked；唯一出口 = NodeEdit 重激活）
          case NodeLifecycle.Running | NodeLifecycle.Completed | NodeLifecycle.Failed | NodeLifecycle.Cancelled |
              NodeLifecycle.Blocked =>
            IO.unit // 已终态/运行中，幂等跳过
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
                // （fix a 启动判定完整性，20260903）。
                // R4（取消静默死锁修复批）：带「待承接」标记的 barrier 不放行——引擎自动
                // 摘除 cancelled 上游后 in 已 prune，若不设此闸，barrier 会以「少一轨」的
                // 输入正常启动、静默产出缺轨结论（设计 §6-R4 选择方案 4 的核心理由）。
                // 分发器承接动作落地（NodeEdit 任意实际变更）即清空标记，此处自然放行。
                if node.in.exists(up => !node.deliveredTo.contains(up)) || node.pendingSuccession.nonEmpty then IO.unit
                else
                  // 防御：wiring 空节点（无 task 无 in 无 deps）不空跑（防浪费 token）。
                  // 裁定①后降级为纵深防御**：零连接主责在创建侧输入侧下限
                  // （`NodeTools.createNode` 校验五：task ∨ in；2026-09-12 裁定后 out 可空置，
                  // 零连接闸已在改接侧删除），此处只兜
                  // 历史遗留数据（校验生效前落盘的零连接旧节点）与校验层外瞬态。
                  if node.status == NodeLifecycle.Wiring && node.task.isEmpty && node.in.isEmpty && node.deps.isEmpty
                  then IO.unit
                  else
                    // 链快照单点（链级抽象 P2 §9.2 项 4/5/7 + 性能纪律）：spawn 前取
                    // 一次，同时喂首条消息链头（buildInput）与会话身份
                    // （ToolContext.flowChainId）——同一快照两处同源，链头文本与会话
                    // 身份恒等（不重复全量重算分量）。resume 路径跳过 buildInput（用
                    // resume prompt）但身份照旧注入：快照重取（spawn 时刻语义），非
                    // 崩溃前残值。
                    val pastVerdictGate: IO[Unit] = chainContextOf(node.id).flatMap { chain =>
                      // resume：Some → 首轮输入 = resume prompt（任务全文经 NodeList(detail)
                      // 指引自读，BackoffSupervisor continue-prompt 直系演化）；None → buildInput。
                      // loopRework（回边返工段）：追加在全文之后——设计 §3.7 选项 (i)
                      // 「每轮 cold start 全文重注 + 返工意见」，语义确定优先。
                      val inputIO = resume.fold(
                        buildInput(node, chain).map(b => loopRework.fold(b)(r => s"$b\n\n$r"))
                      )(ctx => IO.pure(ctx.resumePrompt))
                      inputIO.flatMap { input =>
                        // 翻转竞发败方终结（trigger-chain-fix CAS 守卫）：spawnAndRun 内
                        // 翻转 LostRace 败方抛 StartRaceLost——在此单点吞掉，对全部调用
                        // 方（forkStart/runDetached/直接调用）呈安静败方语义；其他异常
                        // 原样上抛（forkStart/runDetached 留痕）。
                        // LoopNode 路由（LoopNode 批 2026-09-06）：node.loop 启用 → 走
                        // spawnAndRunLoop（worker/verify 双会话迭代）；否则标准路径。
                        val runIO =
                          if node.loop.exists(_.enabled) then spawnAndRunLoop(node, input, resume, chain)
                          else spawnAndRun(node, input, resume, chain)
                        runIO.handleErrorWith { case _: NodeEngine.StartRaceLost =>
                          IO.unit
                        }
                      }
                    }
                    // verdict 闸收口（merge-verdict-gate 批 2026-09-12）——**单权威落点**：
                    // 全部启动入口（barrier 结算 settleTo / settleDeps 反结算 / 重激活补投
                    // redeliverInAndStart / D1 补投 / settleRunnableSweep 资格回扫 / crash-recovery
                    // 续跑 / 直接调用）都汇到本函数，闸在此处即对全部入口生效（与上方 deps/barrier
                    // 「单闸门自动保护全部启动入口」同款纪律）。命中 ⇒ 零动作返回：节点保持
                    // pending/wiring 可见、零副作用（见 mergeVerdictHoldersOf 注释）。
                    // mergefifo-engine 批 2026-09-13：**互斥闸**逐字接在同一收口点之后（先 verdict
                    // 后互斥，与设计件 §7.2 状态机同序；verdict 闸判据/留痕零改动）。
                    // engine-defects 批 #238（2026-09-15）：**闸面泛化**——判据不再按 merge 分叉
                    // ⇒ 非 merge 收口位（O-1 缝：`bpm-verify`(fail) → `bpm-report`(merge=False)）
                    // 同样被本收口点挡住（「不通过」结论不得作为正向交付补投下游）。旧形态
                    // （越闸启动）此后**结构性不可能**：若仍发生，[[logVerdictGateBreach]] 会
                    // 单发一行回退告警（不变式告警，与闸共用 staleVerdictUps 单点）。
                    mergeVerdictHoldersOf(node).flatMap { holders =>
                      if holders.nonEmpty then logVerdictGateHold("startNode", node, holders)
                      else
                        logVerdictGateBreach("startNode", node) *>
                          mergeMutexHoldersOf(node).flatMap { queued =>
                            if queued.nonEmpty then logMutexHold("startNode", node, queued)
                            else pastVerdictGate
                          }
                    }
            }
    }

  /**
   * 触发链 fork 化（根因报告 §6.1，trigger-chain-fix 批）：startNode 同步等到被
   * 启动节点整个会话终态（runWithAgent 内 IO.race(resultDeferred.get, …)），触发
   * 方在自身 fiber 上被下游会话质押——settleDeps 的 traverse_ 遍历因此把「同上游
   * N 依赖者」压成深度优先串行链（案例 A：队尾依赖者 pending 数小时，外观 pending
   * 永动），deliverOut/deliverFailed/deliverOutTo 则把上游完成 fiber 质押给下游
   * 全链（链深嵌套 SOE 风险面同源）。fork 后上游完成即同时唤醒全部依赖者；启动
   * 判定、幂等闸门、CAS 翻转守卫全在 startNode/spawnAndRun 内原样把关（同节点
   * 竞发由翻转守卫裁决，败方安静退出）。错误观测沿用 NodeTools.runDetached 形态
   * （handleErrorWith 留痕，含触发点上下文）。
   */
  private[project] def forkStart(what: String)(io: IO[Unit]): IO[Unit] =
    io
      .handleErrorWith(e =>
        logger.error(s"[$projectName] detached trigger '$what' failed: ${Option(e.getMessage).getOrElse(e.toString)}")
      )
      .start
      .void

  /**
   * 节点任务板头部块（TaskBoard 批 2 §3a）：三段式（①项目目标行 ②自己名下工单
   * 详情块 ③全板速览）——renderer.renderNodeInject 单点装配（上限/降级纪律在
   * renderer）。工单归属按 assignee=自身 node.id（§1d 权限矩阵的身份同源）；
   * ⚠node-done join 用 Flow Map 真实终态映射（store.snapshot 现读，nodeTerminalMap
   * 单点过滤）。无板 → ""（调用方不注空段）。
   */
  private def taskBoardNodeBlock(node: NodeDef): IO[String] =
    board match
      case None => IO.pure("")
      case Some(b) =>
        store.snapshot.flatMap { snap =>
          val terminal = TaskBoardStore.nodeTerminalMap(snap.nodes.values)
          IO.blocking {
            val entries = b.entriesSync()
            val mine = entries.filter(_.assignee.contains(node.id))
            TaskBoardRenderer.renderNodeInject(projectGoal, mine, entries, terminal)
          }
        }

  /**
   * 链快照单点（链级抽象 P2 · §9.2 性能纪律硬约束）：`FlowMapStore.topologicalChains`
   * 每次调用全量重算 `combinedNodes` + 弱连通分量（O(N+E)）——**只允许在节点 spawn
   * 时调用一次**，禁止进事件流/结果落盘/索引对账等热路径（逐节点调用放大为
   * O(N·E)，§9.2 :571）。
   * 本方法 = `chainIdOf` 判据单点的手工展开（同一次分量重算里同时取得 chainId /
   * title / memberCount 三值——若调 chainIdOf 再算一次分量即双重全量重算），口径
   * 与 chainIdOf 逐字同源（**chainmodel 批一 ① 起 = `chainIdIn`**：声明恒带、未声明者
   * 合并集派生分量成员数 ≥2 才带值，孤立单节点链 = 无链，与载荷 chainId 条件键恒同）。
   */
  private[project] def chainContextOf(nodeId: String): IO[Option[NodeEngine.NodeChainContext]] =
    store.combinedNodes.map { combined =>
      val chains = FlowMapStore.topologicalChains(combined.values)
      FlowMapStore
        .chainIdIn(combined, chains, nodeId)
        .map { cid =>
          val members = chains.find(_.id == cid).map(_.memberIds).getOrElse(Nil)
          NodeEngine.NodeChainContext(
            chainId = cid,
            title = FlowMapStore.chainTitle(members.flatMap(combined.get), cid),
            memberCount = members.size
          )
        }
    }

  /**
   * 构造节点输入：链上下文块（链头 + 文件名尾溯源提示段，仅本节点有链时注入）+ 自身
   * task 上下文 + 各上游 result（=== Node <name> === 头，§2.7）+ 终态申报协议脚注
   * （[[protocolFootnoteFor]]——**节点会话完整协议的单一权威面**，F8 收口
   * 2026-09-15：角色值域、blocked JSON 文法、verifier verdict 分支、未申报语义的
   * 唯一引擎承载文本；引擎编译、随任务输入组装面单点注入（本方法一个代码位覆盖
   * 全部节点 spawn 输入，含 loop 节点首轮）、零盘面依赖 ⇒ 抗 seed/运行面漂移。
   * 系统提示词面两处相关文本形态各异：agent 种子条件句（
   * `seed/agents/general/system.md:7-10`）自带完整规则文本（双角色值域/六类
   * blocked/ILLEGAL/verdict≠状态/8 拍提醒阶梯/未申报不终态化），且**无**指向本
   * 脚注的指针——这是 promptfix 批作者并存终态（「规则各留一份」，该条件句 =
   * 唯一 seed 面防线，已终态冻结），非缺陷；always-on belt 行（
   * `PromptSections.NodeSessionAlwaysOnSection`，段序 360、段长门 ≤400 B）=
   * belt+指针，指向工具 description 与本脚注。故「单一权威」的准确口径 =
   * **引擎贡献面**（引擎编译、随任务输入注入的提示词文本）唯一完整纪律，而
   * 非全局唯一——旧注释「system prompt 不含约定」的前提已被该结构取代（F8 判违：
   * 两处设计前提互相否证，本段即消解后的真实理由）。loop 侧同款：
   * `loopReworkInput` 复注同一 `ProtocolFootnote`（同会话返工轮再提醒，非第二份
   * 协议文本）。
   * 收敛裁定（作者 2026-09-07）：项目记忆=分发器配置知识——分发器建节点时把关键
   * 口径写进节点 task，节点侧不再注入记忆全文。节点上下文=task+上游结果+AGENTS.md；
   * 节点每 spawn 省一份记忆全文 token（多节点并行批次收益可观）；AGENTS.md 每 turn
   * 注入面不受影响。全局注入面（ContextRefresher）不含项目记忆——瘦身边界不变。
   * TaskBoard 批 2（§3a）：头部追加任务板块（在任务文本之前；无板/三段皆空 →
   * 不注空段）——「自身工单 id」是节点显式上报（决策 d）的使能器。
   * 链级抽象 P2（§9.2 项 7/8；提示段形态 = 2026-09-11 R-3 裁定后的文件名尾溯源）：
   * 链头 + 溯源提示段置于最前（首屏可见，agent 先
   * 看到链归属再读任务）；`chain` 参数由调用方（startNode）传入 spawn 时刻快照——
   * 无链（None）→ 整块不注入（不注空行/零占位）。
   */
  private[project] def buildInput(node: NodeDef, chain: Option[NodeEngine.NodeChainContext]): IO[String] =
    val ownTask = node.task.getOrElse("")
    // 链上下文块（有链才有）：链头一行 + 紧随的可复制文件名尾溯源提示段（文本尽量短）
    // ——两行同一块（块内不空行，「提示段紧随链头」字面口径），块间仍以空行分隔。
    val chainBlock: List[String] =
      chain.toList.map(c => NodeEngine.chainHeaderLine(c) + "\n" + NodeEngine.DocProvenanceBlock)
    taskBoardNodeBlock(node).flatMap { boardBlock =>
      node.in
        .traverse { upId =>
          store.findNode(upId).map {
            case Some(up) =>
              // P1（spec §2.2 #3）：signal 边不投载荷（deps 同款，下游输入 = 自身 task）。
              // 判定沿上游→本节点的全部边：存在 result 边 → 注入（result 意图优先）；
              // 全 signal → 抑制（含 failed 上游 result=错误文本不作输入投递的 signal
              // 场景）；无边（修复/悬空路径）→ 注入（现状兜底）。
              val toHere = up.out.filter(_.to == node.id)
              val suppressed = toHere.nonEmpty && toHere.forall(_.mode == OutEdge.Signal)
              if suppressed then None
              else up.result.map(res => s"=== Node ${up.name} ===\n$res")
            case None => None
          }
        }
        .map { upstream =>
          (chainBlock ++ List(boardBlock, ownTask).filter(_.nonEmpty) ++ upstream)
            .mkString("\n\n") + "\n\n" + NodeEngine.protocolFootnoteFor(Some(node.role))
        }
    }

  end buildInput

  private def spawnAndRun(
    node: NodeDef,
    inputText: String,
    resume: Option[NodeEngine.ResumeContext] = None,
    chain: Option[NodeEngine.NodeChainContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    for
      agentOpt <- EntityLoader.loadAgent(node.agent)
      _ <- agentOpt match
        case None =>
          failNode(nodeId, s"agent '${node.agent}' not found in global library")
        case Some(entry) =>
          // 阶段 2b Plugins（§B.4 第 4 步，spawn 前执行）：① 解析 node.plugins
          // （不存在/被封禁/装载非法 → failNode，错误消息列明原因——分配失败是
          // 节点级失败，不静默降级；内容变更**不**拒启动，见 prepareNodePlugins）；
          // ② skill 全文读出 + ${SKILL_DIR} 替换
          // （SkillService.loadSkill 单点复用）组装 <injected-plugins> 块；
          // ③ MCP server 启动 + 引用记账（PluginMcpManager，启动失败 → failNode）。
          // 三步全部发生在状态翻转（status=Running）之前——失败路径零 running 残留。
          // panelscheme 批（2026-09-21）：§E.3 node.preset 静态消费**废止**——
          // 节点无自有方案，worker 模型 = 分发器当前方案（entry.toAgentDef 经
          // SchemePolicy 对 general 动态继承 project-dispatcher，装载期现读）。
          // resume（crash-recovery 批 D2/D3）：插件/装配链逐行复用，仅两处
          // 差异——sessionId 复用 sessionRef 旧 id（transcript 单文件续写 + F2 队列
          // 重放白捡，BackoffSupervisor respawn 同款先例）+ initialMessages 水合。
          val baseDef = entry.toAgentDef
          prepareNodePlugins(node).flatMap {
            case Left(err) => failNode(nodeId, err)
            case Right(prepared) =>
              val sessionId = resume.fold(s"node-${java.util.UUID.randomUUID().toString.take(8)}")(_.sessionId)
              val inputWithPlugins =
                if prepared.injectedBlock.isEmpty then inputText
                else inputText + "\n\n" + prepared.injectedBlock
              resources.pluginMcp.acquire(sessionId, prepared.mcpPlugins).flatMap {
                case Left(err) => failNode(nodeId, err)
                case Right(grant) =>
                  // 回收兜底（§B.4 第 5 步）：completed/failed/cancelled/blocked 全
                  // 终态汇合点=runWithAgent 完成；异常中止路径由 guarantee 补位。
                  // release 幂等（PluginMcpManager 内 no-op 语义），双保险不重复卸载。
                  runWithAgent(node, baseDef, inputWithPlugins, sessionId, prepared, grant, resume, chain)
                    .guarantee(resources.pluginMcp.release(sessionId))
              }
          }
    yield ()

    end for

  end spawnAndRun

  /**
   * LoopNode spawn（LoopNode 批 2026-09-06）：与 spawnAndRun 同骨——加载 worker/
   * verify 两 agent、准备插件、acquire 各会话 MCP grant、翻转 Running、spawn 双会话、
   * 驱动 loop。终态（PASS/failed/cancelled/blocked/达 K）由 runLoopNode 落终态化，
   * 会话/MCP/running 清理在 guarantee 内（裁定 B 双销毁不残留）。
   */
  private def spawnAndRunLoop(
    node: NodeDef,
    inputText: String,
    resume: Option[NodeEngine.ResumeContext] = None,
    chain: Option[NodeEngine.NodeChainContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    val loopCfg = node.loop.get
    // crash-recovery 批裁定③：resume=Some 时双会话复用 sessionRef（worker）/
    // sessionRefVerify（verify）旧 id + 各自 transcript 水合，runLoopNode 从崩溃时
    // loopRound/loopPhase 断点续跑（worker→verify 迭代跨 kill -9 续接）。
    val workerSessionId = resume.fold(s"node-${java.util.UUID.randomUUID().toString.take(8)}")(_.sessionId)
    // verify 会话：恢复优先复用旧 id（D2）；无旧 ref（崩溃于 flip 前/历史数据）→ 新 id。
    val verifySessionId =
      resume.flatMap(_.verifySessionId).getOrElse(s"node-${java.util.UUID.randomUUID().toString.take(8)}")
    // projectRoot 解析（与 runWithAgent 同源单点：PathUtil.resolveNodeProjectRoot）
    val projectRoot =
      node.worktree match
        case Some(wt) if PathUtil.normalizeWorktree(wt).isLeft =>
          val pr = PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
          logger.warnSync(
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)"
          )
          pr
        case _ => PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
    for
      workerAgentOpt <- EntityLoader.loadAgent(node.agent)
      verifyAgentOpt <- EntityLoader.loadAgent(loopCfg.verify)
      _ <- (workerAgentOpt, verifyAgentOpt) match
        case (None, _) => failNode(nodeId, s"worker agent '${node.agent}' not found in global library (loop node)")
        case (_, None) => failNode(nodeId, s"verify agent '${loopCfg.verify}' not found in global library (loop node)")
        case (Some(wEntry), Some(vEntry)) =>
          // panelscheme 批（2026-09-21）：worker/verify 均经 SchemePolicy 名称策略
          // ——worker（general）动态继承 project-dispatcher 当前方案（节点无自有
          // 设置）；verify agent 同一策略（§E.3 node.preset 静态消费已废止）。
          val workerBase = wEntry.toAgentDef
          val verifyBase = vEntry.toAgentDef
          // worker/verify 均注入 node.plugins（§2.1 verify=通用 agent+plugins，验证域
          // 分配共享同域能力包）；各 session 独立 acquire MCP grant（引用记账分离）。
          prepareNodePlugins(node).flatMap {
            case Left(err) => failNode(nodeId, err)
            case Right(prepared) =>
              for
                wGrantE <- resources.pluginMcp.acquire(workerSessionId, prepared.mcpPlugins)
                vGrantE <- resources.pluginMcp.acquire(verifySessionId, prepared.mcpPlugins)
                _ <- (wGrantE, vGrantE) match
                  case (Left(err), _) => failNode(nodeId, err)
                  case (_, Left(err)) => failNode(nodeId, err)
                  case (Right(wGrant), Right(vGrant)) =>
                    for
                      cancelSig <- Deferred[IO, Unit]
                      _ <- flipToRunning(node, cancelSig, workerSessionId, nodeId, node.name, Some(verifySessionId))
                      worker <- spawnLoopSession(
                        workerBase,
                        prepared,
                        wGrant,
                        workerSessionId,
                        node.name,
                        projectRoot,
                        initialMessages = resume.fold(List.empty[Message])(_.recoveredMessages),
                        // TaskBoard 批 2（§1d）：loop worker/verify 会话同属该
                        // loop 节点——flowNodeId 身份与普通节点同源（权限矩阵
                        // 同面：仅自己名下任务 status+note）。
                        flowNodeId = Some(nodeId),
                        // nrloop 一期（§3.2 透传表）：loop 双会话角色 = 该 loop
                        // 节点自身 role（与普通节点同源口径，worker/verify 同值）。
                        flowNodeRole = Some(node.role),
                        // D6 批 F1（G9 路径 a）：节点名随路注入（AskUser 归因）。
                        flowNodeName = Some(node.name),
                        // 链级抽象 P2（§9.2 项 5）：worker/verify 同属该 loop
                        // 节点 → 同一条链的同一快照（startNode 单点算出）。
                        flowChainId = chain.map(_.chainId)
                      )
                      verify <- spawnLoopSession(
                        verifyBase,
                        prepared,
                        vGrant,
                        verifySessionId,
                        s"${node.name}-verify",
                        projectRoot,
                        initialMessages = resume.fold(List.empty[Message])(_.verifyMessages),
                        flowNodeId = Some(nodeId),
                        flowNodeRole = Some(node.role),
                        flowNodeName = Some(node.name),
                        flowChainId = chain.map(_.chainId)
                      )
                      _ <- runLoopNode(node, worker, verify, inputText, cancelSig, resume)
                        .guarantee(
                          destroyLoopSessions(nodeId, worker, verify) *>
                            resources.pluginMcp.release(workerSessionId) *>
                            resources.pluginMcp.release(verifySessionId) *>
                            running.update(_ - nodeId) *>
                            nodeSessions.update(_ - nodeId)
                        )
                    yield ()
              yield ()
          }
    yield ()
    end for
  end spawnAndRunLoop

  /**
   * panelscheme 批（2026-09-21）：§E.3 nodePresetDef（node.preset →
   * PresetResolver 静态消费）**整体移除**——节点侧静态覆盖废止，引擎解析不再
   * 读节点存储方案（NodeDef.preset 字段保留：存量数据零删除，仅显示/审计）。
   * 节点 worker/verify 模型 = 分发器当前方案，经 AgentEntry.toAgentDef 内
   * SchemePolicy 名称策略（general 动态继承 project-dispatcher）单点生效。
   */

  /**
   * node.plugins → 可分配能力（§B.4 第 4 步 ①②，feature flag §G.2 开关）：
   * flag off / 无分配 → 空 preparation（旧行为零变化）；解析失败 → Left
   * （failNode，错误含可行动指引）。
   *
   * **闸 B / C / E 的口径（2026-09-13 无审批批更新）**：
   * 本函数是 spawn / crash-recovery resume / loop 双会话的**装载门**，它判的**只能是
   * 内容面可用性**（`PluginRegistry.resolve` = 「存在 ∧ 装载合法 ∧ **未被封禁**」）。
   * 「在位即信任」后这里恒 `Right`——除非包不存在（`PLUGIN_NOT_FOUND`）或被封禁
   * （`PLUGIN_BLOCKED`）。**内容变更不再拒启动**（digest 漂移降级为非拦截可见性）。
   * **派发许可面（`PluginDispatchPolicy`）不在此判定** —— 按设计 S2/S3：派发许可的
   * 判定点是 NodeEdit 落库时刻，一次性；落库之后对该节点的开关变更无效。作者的关闭
   * 动作走派发面（`plugins.dispatch`），内容面不动 ⇒ 本函数对已派发节点**零影响**。
   * 要收回已派发的包，用封禁（`POST /api/plugins/:name/revoke`，会停用运行中 MCP）。
   * 闸 A（新派发）在 `NodeTools.dispatchFaceCheck`；闸 D 在 `PluginMcpManager.revalidate`。
   */
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
                Right(
                  NodeEngine.PluginPreparation(
                    injectedBlock =
                      if inner.isEmpty then ""
                      else s"<injected-plugins>\n${inner.mkString("\n\n")}\n</injected-plugins>",
                    mcpPlugins = defs.filter(_.mcpServers.nonEmpty),
                    builtinTools = defs.flatMap(_.toolsExtension).distinct
                  )
                )
              }
            end if
          }
    }

  /**
   * 逐 plugin 逐 skill 读 SKILL.md 全文（frontmatter 去除 + ${SKILL_DIR} 替换，
   * SkillService.loadSkill 单点复用——修复「模型自读拿不到替换」缺口，§B.4 ②）。
   * 文件不可读 → Left（节点级失败，不静默降级）。
   *
   * 数据根渲染（home 硬编码 → 运行时动态化批 2026-09-11）：注入块里的
   * `{{data_root}}` 在此渲染为实例数据根（PathUtil.substituteDataRoot，与
   * AgentCore.buildSystemPrompt / DispatcherContextCatalog.render 同一实现）——
   * 隔离实例的节点看到的是**本实例**的 home 路径，而非固定 `~/.nebflow`。
   */
  private def injectedPluginBlock(defn: PluginRegistry.PluginDef): IO[Either[String, String]] =
    if defn.skills.isEmpty then IO.pure(Right(""))
    else
      defn.skills
        .traverse { sk =>
          SkillService.loadSkill(sk.path).flatMap {
            case Some(content) =>
              IO.pure[Either[String, String]](Right(s"""<plugin name="${defn.name}" skill="${sk.name}">\n${PathUtil
                  .substituteDataRoot(content.content)}\n</plugin>"""))
            case None =>
              IO.pure[Either[String, String]](
                Left(
                  s"Plugin '${defn.name}' skill '${sk.id}' file unreadable: ${sk.path} — allocation refused (no silent degradation)"
                )
              )
          }
        }
        .map { blocks =>
          blocks.find(_.isLeft) match
            case Some(Left(err)) => Left(err)
            case _ => Right(blocks.collect { case Right(b) => b }.mkString("\n\n"))
        }

  /**
   * 内容面运行时重验（§B.5 信任联动，ProjectActor.TtlTick 30s 驱动）：停用**被封禁**
   * （deny-list）或已从注册表移除的运行中 plugin MCP + 对持有会话发系统提醒。
   * flag off → no-op（总闸 `plugins.enabled=false` 的既有停飞行为不回退）。
   * 内容变更（digest 漂移）**不停飞**（2026-09-13 无审批批，见
   * [[PluginMcpManager.revalidate]]）。可见性批（2026-09-10 P1 静默缩容）：重验即插件
   * 重扫完成点——同处聚合输出一次装载健康摘要（拒载/封禁清单 + 内容变更清单；干净场景
   * 零输出、同状态去重）。
   */
  def revalidatePluginTrust(): IO[Unit] =
    PluginsConfig.enabled.flatMap {
      case false => IO.unit
      case true =>
        resources.pluginMcp
          .revalidate(
            PluginRegistry.scan(),
            (sessionId, text) =>
              resources.agentRegistry.get.flatMap { reg =>
                reg.get(sessionId).map(_.ref) match
                  case Some(ref) =>
                    (ref ! AgentCommand.ImmediateInput(text, source = Some("system"), fromUser = false)).void
                  case None => IO.unit // 会话已终结——提醒无投递面，server 已停即足够
              }
          )
          .void *>
          PluginRegistry
            .logHealthSummary("rescan")
            .handleErrorWith(e => logger.warn(s"plugin health summary failed: ${e.getMessage}"))
    }

  private def runWithAgent(
    node: NodeDef,
    baseDef: nebflow.agent.AgentDef, // panelscheme 批：经 SchemePolicy 的 worker def（继承分发器当前方案）
    inputText: String,
    sessionId: String,
    prepared: NodeEngine.PluginPreparation,
    grant: PluginMcpManager.Grant,
    /**
     * crash-recovery 批 D3：Some = 崩溃恢复续跑（initialMessages=水合 transcript，
     * inputText=resume prompt）；None = 新鲜启动（行为与本批前逐字节一致）。
     */
    resume: Option[NodeEngine.ResumeContext] = None,
    /**
     * 链级抽象 P2（§9.2 项 4）：节点所属链快照（spawnAndRun 由 startNode 单点算好
     * 传入——与首条消息链头同源）。None = 无链（孤立单节点分量/不在双区）。
     */
    chain: Option[NodeEngine.NodeChainContext] = None
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
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)"
          )
          pr
        case _ => PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
    val nodeName = node.name
    // 清理硬化（僵尸收敛批 2026-09-06，根因报告漏洞①）：runSig 桥接 guarantee 与
    // for 内才创建的 cancelSig——finalizer 在任意退出路径（正常/崩溃/异常/cancel）
    // 读到 cancelSig 执行 cleanupRunTables 三表对称移除（防泄漏「假活 canary」）。
    // cancelSig 未创建（for 前崩溃）→ None → 无登记可清，no-op。
    val runSig = Ref.unsafe[IO, Option[Deferred[IO, Unit]]](None)
    (for
      // 在飞登记先行（清场 c-① liveness 误杀防护 20260903）：running 表先于
      // status=Running 翻转——NodeList liveness / abandon / NodeCancel 收殓判定
      // 以「running 表含此节点」为活会话信号，若先翻转后登记，spawn 窗口内的
      // 节点会被误判死会话（false-dead → 可被收殓 = 误杀）。
      // 条件注册（trigger-chain-fix fork 化硬化）：触发链并行化后，同节点多触发
      // 方（deliverOut/settleDeps/settleSweep）并发到达本点——盲目覆写会顶掉先到
      // 者的 cancelSig（NodeCancel 信号永久丢失）。只在空位时注册；翻转赢家在
      // 翻转成功后重装自己的 sig（FlipDone 分支），最终条目必属活会话 fiber。
      // liveness 判定只看键存在，不受值选择影响。
      cancelSig <- Deferred[IO, Unit]
      _ <- runSig.set(Some(cancelSig))
      _ <- running.modify { m =>
        if m.contains(nodeId) then (m, ())
        else (m + (nodeId -> cancelSig), ())
      }
      // NodeMessage 注入链登记（20260905 机制批）：与 running 表同点置位——
      // sendNodeMessage 经本映射定位会话 actor；清理见下方对称移除。
      _ <- nodeSessions.update(_ + (nodeId -> sessionId))
      // 状态 → running + startedAt（WS nodeUpdated，NodeList 同构 payload）。
      // 竞态修复（与终态化同族）：running 迁移落在事务内现读的 fresh 节点上——
      // getNode 快照经 EntityLoader 加载 agent 期间可能已落后，陈旧 copy 写回会
      // 覆盖该窗口内的接线变更；None = 节点已消失 → 中止 spawn（拒写复活垃圾行）。
      // barrier 事务性复核（fix a 启动判定完整性 20260903）：startNode 入口的
      // in ⊆ deliveredTo 检查沿 getNode 快照，快照与翻转之间并发接线（edit 追加
      // in）可增长 in——沿陈旧快照放行 = 以不完整 barrier 提前启动（未等齐）。
      // 复核与翻转并入同一事务：fresh barrier 未齐 → 拒翻转不 spawn（status 保持
      // 原态，等真正等齐时的投递/结算方重试 startNode）。
      // CAS 翻转守卫（trigger-chain-fix §6.1 前置硬化）：仅 Pending/Wiring 可翻
      // 转。fork 化后多触发方并发通过①②③闸门（都沿陈旧 Pending 快照），无守卫
      // 的第二个 mutate 会无视已是 Running 的事实照常覆写 → 双 spawn（第二个覆写
      // running 表与 startedAt，双会话双计费、cancel 信号错投）。守卫 +
      // mutateWithResult 事务内三态判定：Done=本 fiber 唯一翻转（继续 spawn）；
      // LostRace=败方（他者已翻转到 Running——fork 并行化的正常形态，安静回滚，
      // 不事件不抛错：上游每次完成都产生同节点竞发，事件化=刷屏）；Aborted=真异
      // 常（消失/barrier 未齐/终态竞合）→ start-aborted 事件落账（§6.3 异常信号
      // 源，不再只靠异常上抛）+ 错误上抛由调用方留痕。
      now <- IO(System.currentTimeMillis())
      (flipped, flipOutcome) <- store.mutateWithResult { s =>
        s.nodes.get(nodeId) match
          case Some(fresh)
              if (fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Wiring)
                && !fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            // sessionRef 与 startedAt 同事务落库（crash-recovery 批 D1）：崩溃后 boot
            // sweep 据此定位磁盘 transcript；终态不清除（审计价值）。
            // 未申报计时同事务清零（noderpt 批 A 段 2026-09-11）：翻转 = 新一轮/新会话
            // （首次启动 / reactivate 重激活 / boot resume / 挂起恢复），旧一轮的计时
            // 起点与拍数一律不继承（否则新会话一交棒就按旧起点直接跳到高拍）。
            // **销毁窗口同点撤销**（noderpt 批 B 段）：翻转 = 节点重新 Running ⇒ 旧的
            // `destroyAt` 窗口作废（节点在窗口内被重激活/重跑时，绝不按旧计划销毁在跑的
            // 进程）；禁 spawn 表项由翻转后的 `BgTaskRegistry.reopenSession` 同步解除。
            (
              s.copy(nodes =
                s.nodes.updated(
                  nodeId,
                  fresh.copy(
                    status = NodeLifecycle.Running,
                    startedAt = Some(now),
                    sessionRef = Some(sessionId),
                    reportPendingSince = None,
                    reportReminderCount = 0,
                    destroyAt = None
                  )
                )
              ),
              NodeEngine.FlipOutcome.Done
            )
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            (s, NodeEngine.FlipOutcome.LostRace)
          case Some(fresh) if fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (s, NodeEngine.FlipOutcome.Aborted("in-barrier not settled (concurrent rewiring)"))
          case Some(fresh) =>
            (s, NodeEngine.FlipOutcome.Aborted(s"state changed to '${fresh.status}' before flip (concurrent finalize)"))
          case None =>
            (s, NodeEngine.FlipOutcome.Aborted("node vanished"))
      }
      _ <- flipOutcome match
        case NodeEngine.FlipOutcome.Done =>
          // 赢家重装自己的 cancelSig：条件注册期间条目可能仍是竞发者的占位——
          // 最终条目必须属于本活会话 fiber（NodeCancel 信号才能到达本会话）。
          running.update(m => m + (nodeId -> cancelSig)) *>
            // 销毁窗口撤销的第二步（noderpt 批 B 段）：把**上一轮**的会话解出禁 spawn 表
            // （旧 sessionRef 可能正是窗口内被登记的那个；新 sessionId 是新 id，不受影响）。
            // 不清 ⇒ 重激活/重跑后旧 id 永久禁 spawn（虽无新调用方，仍是错误状态）。
            node.sessionRef.traverse_(BgTaskRegistry.reopenSession) *>
            flipped.nodes
              .get(nodeId)
              .traverse_(runningDef =>
                emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(runningDef, System.currentTimeMillis()))
              )
        case NodeEngine.FlipOutcome.LostRace =>
          // CAS 败方：按 sig 身份只回滚自己的登记（不得误删赢家的条目），抛
          // StartRaceLost 终止本 fiber 的 for 推导（否则败方继续 spawn = 双会话，
          // M1 demo 实证 6 路并发 6 会话）。异常由 startNode 的 handleErrorWith
          // 精准吞掉——对全部调用方呈安静败方语义。nodeSessions 对称移除
          // （NodeMessage 注入链，防泄漏僵尸映射）。
          running.modify {
            case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ())
            case m => (m, ())
          } *> nodeSessions.update(_ - nodeId) *>
            IO.raiseError(NodeEngine.StartRaceLost(nodeId))
        case NodeEngine.FlipOutcome.Aborted(reason) =>
          // 真异常中止：按身份回滚 + start-aborted 事件（原语义「回滚在飞登记并
          // 中止」保留，异常信号从「仅错误上抛」扩展为「事件 + 上抛」双通道）。
          // nodeSessions 对称移除（NodeMessage 注入链，防泄漏僵尸映射）。
          running.modify {
            case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ())
            case m => (m, ())
          } *> nodeSessions.update(_ - nodeId) *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "start-aborted", s"start aborted: $reason") *>
            IO.raiseError(new RuntimeException(s"Node '$nodeName' ($nodeId) start aborted — $reason"))
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
          // 同一可观测性标准）。project：agentStart 帧注入项目名（面板项目徽标）。
          wsSend = NodeRunner.routeSubagentWsSend(wsSendFn, rootSessionId, sessionId, Some(projectName)),
          projectRoot = Some(projectRoot),
          safetyMode = "confirm-edits",
          rootSessionId = rootSessionId,
          isFlowNode = true, // leaf 剥离（与 flow 节点一致：无 Node 工具/展示类）
          // TaskBoard 批 2（§1d）：节点引擎侧身份（flowNodeId=NodeDef.id）+ 项目
          // 上下文——AgentCore 透传 ToolContext 后 TaskBoard 权限矩阵据此判定
          // （仅自己名下任务、仅 status+note），project 缺省解析随之生效。
          flowNodeId = Some(nodeId),
          // nrloop 一期（设计 §3.2 透传表，B1 第一段）：节点角色随 spawn 注入 →
          // AgentCore 透传 ToolContext.flowNodeRole——node_report 值域分化
          // （verifier ⇒ pass/fail；task ⇒ finish）与 ProtocolFootnote 角色分支的
          // 引擎侧判据来源。task 也显式带值（判据侧 normalize 宽容，但显式 = 可审计）。
          flowNodeRole = Some(node.role),
          projectName = Some(projectName),
          // D6 批 F1（G9 路径 a）：节点人类可读名随 spawn 注入——AskUser payload
          // nodeName 字段来源（badge「project · nodeName」+ node-ask 留痕事件）。
          flowNodeName = Some(nodeName),
          // 链级抽象 P2（§9.2 项 4）：链身份随 spawn 注入（spawn 时刻快照，
          // startNode 经 chainContextOf 单点取值）——AgentCore 透传
          // ToolContext.flowChainId，节点产出据此在过程文档**文件名尾段**写链归属
          // `__<chainId>`（正文零元数据头；2026-09-11 作者裁定 R-3）。
          // None = 无链（孤立单节点分量/不在双区）——口径与载荷 chainId 恒同。
          flowChainId = chain.map(_.chainId),
          // 阶段 2a 沙箱（§A.6）：dev/修复节点 root=<workspace>/.nebflow/<wt>、
          // merge 节点 root=workspace——物理隔离，最小权限。
          sandboxEnabled = true,
          // 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：本 spawn 点 = 项目节点会话
          // ⇒ AGENTS.md 注入判据置位（与沙箱总闸解耦，避免拆围栏时连带关掉项目
          // 契约注入）。语义见 SessionContext.projectSession。
          projectSession = true,
          // [2026-09-05 21:05 作者裁定——worktree 节点继承项目沙箱]：沙箱根=
          // 项目工作区根（不收窄到 worktree 自身）——主仓 .git/worktrees/<name>/
          // 元数据在工作区内，git commit / worktree remove 直写不再 EPERM。
          // 独立信号传入（sessionRoot 显式 sandboxRoot 优先于 projectRoot），
          // 不按路径形态硬猜 workspace 布局；cwd/projectRoot 工具语义不动。
          sandboxRoot = Some(workspace),
          // B5 缺口②（作者 2026-09-17 M-1 裁定「会话启动即 `cd` 座椅」，选项①）：
          // 会话初始 cwd = 座椅（= 本节点的 projectRoot；worktree 节点 ⇒
          // `<ws>/.nebflow/worktrees/<name>`，由上方 resolveNodeProjectRoot 单点解析）。
          // 非 worktree 节点该值与沙箱根同源 ⇒ 行为逐字节不变。座椅目录缺失时
          // 不在此兜底——交 ShellSession.resolveCwdOrFail 显式失败（fail-closed）。
          sessionCwd = Some(projectRoot),
          // crash-recovery 批 D3：恢复续跑时水合磁盘 transcript（NodeRunner.
          // initialMessages 通道，BackoffSupervisor respawn 同款）；spawn 时
          // RecoverPersistedQueues 自动重放该会话崩溃前排队中的 F2 注入。
          initialMessages = resume.fold(List.empty[Message])(_.recoveredMessages)
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
      //
      // ═══ 节点完成闸（bgtask-completion-gate 批，作者 2026-09-05 18:29 裁定）═══
      // 问题：节点把编译/CI 等长命令跑后台后结束轮次，engine 按「turn 结束=完成」
      // 终态化节点并把结果传下游——后台任务实际没干完，完成通知注入死会话丢失。
      // 方案（桥单咽喉 hold）：Completed 分支先查 BgTaskRegistry.waitingFor
      // (sessionId)（该节点名下等待型后台任务，即「完成时会向本会话注入通知」的
      // 任务；persistent 服务型已被 registry 过滤）：
      //   非空 → hold：不 complete、桥保持存活、FlowMapEventLog 留痕（bg-wait，
      //   先例 held/blocked 语义族但不复用——held=人工 release 放行，bg 等待是
      //   自动续行）+ 武装兜底计时器（每等待期重臂）。后台任务完成回调经
      //   AgentCommand.ExternalEvent(source="background-task") 注入仍活着的 agent
      //   → 新轮次（AgentActor idle 唤醒轮对本桥 supervisorRef 发 Completed——
      //   粘性完成目标）→ 轮次终 → 又一次 Completed → 此处复检。
      //   清空 → drainFailures 检查终局记账（超时/停滞杀的 failed 注明，拒绝静默
      //   completed）→ complete resultDeferred（result=最后一轮文本，agent 已消化
      //   全部后台通知）。
      // 三态：无后台任务 → 立即放行（现状零变化）；等待型 → 拦截至全完；
      // persistent 服务型 → 不等待。兜底面（后台任务不能卡死节点）：
      //   ①等待期超 bgWaitCapMs → failed 注明（TaskStuckWatcher 对等待期 Idle
      //     节点不判卡死——Idle 是合法状态，故需自身兜底防通知链断裂悬挂）；
      //   ②bg 任务被超时/停滞看护杀 → registry 记账 → failed+注明。
      // bg 等待先于 hold/blocked 分流发生（本桥在 completeNode 之前）——hold 节点
      // 也是先等后台再 held，语义正确。
      bridgeRef <- system.spawn(
        Behaviors.setup[AgentEvent] { bctx =>
          bctx.watch(ref) *>
            IO {
              // hold 期状态：留痕单发标记 + 兜底计时器句柄（每等待期重臂）。
              val holdEmitted = Ref.unsafe[IO, Boolean](false)
              val waitCapFiber = Ref.unsafe[IO, Option[Fiber[IO, Throwable, Unit]]](None)
              def disarmCap: IO[Unit] =
                waitCapFiber.get.flatMap(_.traverse_(_.cancel).void)
              def armCap(waiting: List[BgTaskRegistry.ActiveTask]): IO[Unit] =
                disarmCap *> {
                  // 兜底计时器：等待期内无任何后台完成复检达 bgWaitCapMs →
                  // failed 注明。措辞注意：completeNode 以 message.contains
                  // ("cancelled") 分流 cancelNode，本文案不得含该词。
                  val capBody: IO[Unit] =
                    IO.sleep(bgWaitCapMs.millis) *>
                      FlowMapEventLog.append(
                        workspace,
                        projectName,
                        nodeId,
                        "bg-wait-timeout",
                        s"background wait cap (${bgWaitCapMs / 1000}s) hit with ${waiting.size} task(s) pending — finalizing failed"
                      ) *>
                      waitCapFiber.set(None) *>
                      // ⑤ 残留字段收口（noderpt 批 B 段实测缺陷：`n-0931699e` status=completed
                      // 而 `bgWait` 非空）：本出口此前只 complete deferred、**不写
                      // `setNodeBgWait(Nil)`** ⇒ 首个 hold 期置位的 bgWait 随节点进终态/归档
                      // 永久残留（与 `setNodeBgWait` 头注「flow-map.json 不残留过期 bgWait」
                      // 的契约相悖，也让前端把终态节点误标「等待后台任务」）。此处补写——
                      // fresh 守卫要求 status==Running，此刻节点仍 Running（终态化在后），
                      // 故写点有效；幂等（值未变不写不 event）。
                      setNodeBgWait(nodeId, Nil) *>
                      resultDeferred
                        .complete(
                          Left(
                            FailOutcome(
                              s"background task wait cap exceeded (${bgWaitCapMs / 1000}s): still waiting for " +
                                waiting.map(t => s"'${t.description}' (${t.jobId})").mkString(", ") +
                                " — node finalized as failed by the background-completion gate; " +
                                "the background job(s) keep running and their completion notification may arrive at a finalized session"
                            )
                          )
                        )
                        .attempt
                        .void
                  capBody.start.flatMap(f => waitCapFiber.set(Some(f)))
                }
              // 终局记账 → failed 注明文案（含 agent 消化失败通知后的最终输出，
              // 截断防串膨胀；杀因原文净化同上）。
              def bgFailureMessage(
                failures: List[BgTaskRegistry.FailedBgTask],
                msgs: List[Message]
              ): String =
                val causes = failures
                  .map { f =>
                    s"'${f.description}' (${f.jobId}): ${f.cause.replace("cancelled", "auto-stopped")}"
                  }
                  .mkString("\n  - ")
                val tail = extractLastAssistantText(msgs)
                val tailNote =
                  if tail.trim.nonEmpty then
                    s"\n\nNode agent final output (after consuming the failure notification):\n${tail.take(2000)}"
                  else ""
                s"background task(s) in the node wait set were killed by the background guard (idle/stall watchdog):\n  - $causes$tailNote"
              end bgFailureMessage
              lazy val bridge: Behavior[AgentEvent] = new Behavior[AgentEvent]:
                def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
                  event match
                    case AgentEvent.Completed(_, messages) =>
                      // 完成门（noderpt 批 A 段 2026-09-11 作者裁定）——同一闸门两条腿：
                      //   腿 1「后台任务存活」（`bgGateHoldEnabled`，默认 false = 封存）：
                      //     关 ⇒ **不查** BgTaskRegistry.waitingFor，直接用空等待集走放行
                      //     分支（hold/armCap 代码逐字保留，开 flag 即恢复逐字旧行为）；
                      //   腿 2「未申报 node_report」（`reportGateHoldEnabled`，默认 true）：
                      //     放行前非消费读申报槽——空 ⇒ **不终态化**（节点保持 Running、
                      //     会话存活）并起表（只置不重）；非空 ⇒ 清表后放行，终态点 drain
                      //     照常分流（blocked/pass/fail 三链零改动）。
                      val waitingIO: IO[List[BgTaskRegistry.ActiveTask]] =
                        if bgGateHoldEnabled then BgTaskRegistry.waitingFor(sessionId)
                        else IO.pure(Nil)
                      waitingIO.flatMap { waiting =>
                        if waiting.nonEmpty then
                          holdEmitted.get.flatMap { already =>
                            val emitOnce =
                              IO.whenA(!already)(
                                FlowMapEventLog.append(
                                  workspace,
                                  projectName,
                                  nodeId,
                                  "bg-wait",
                                  s"completion held: ${waiting.size} background task(s) pending: " +
                                    waiting.map(t => s"'${t.description}'").mkString(", ")
                                ) *>
                                  logger.info(
                                    s"Node '$nodeName' ($nodeId) turn completed with ${waiting.size} background " +
                                      "task(s) still running — holding completion until they finish"
                                  ) *>
                                  // bg-wait 标注写点（僵尸收敛批）：首个 hold 期置位
                                  // bgWait——NodePayload 条件字段随之带，前端可标
                                  // 「等待后台任务」（与真僵尸区分，作者首要缺口）。
                                  setNodeBgWait(nodeId, waiting)
                              ) *>
                                holdEmitted.set(true)
                            emitOnce *> armCap(waiting).as(bridge)
                          }
                        else
                          BgTaskRegistry.drainFailures(sessionId).flatMap { failures =>
                            if failures.isEmpty then
                              // 放行分支（腿 1 封存后 Completed 直达此处）。放行前的
                              // 腿 2 判定 = 未申报闸。
                              def releaseNow: IO[Behavior[AgentEvent]] =
                                holdEmitted.get.flatMap { held =>
                                  disarmCap *> holdEmitted.set(false) *>
                                    IO.whenA(held)(
                                      FlowMapEventLog.append(
                                        workspace,
                                        projectName,
                                        nodeId,
                                        "bg-released",
                                        "all background task(s) finished — completion released"
                                      ) *>
                                        setNodeBgWait(nodeId, Nil)
                                    ) *>
                                    resultDeferred.complete(Right(messages)).attempt.void.as(Behaviors.stopped)
                                }
                              // 文本锚定 BLOCKED 豁免（noderpt 批 B 段 · 作者代裁 ⑧-4 = (a)）：
                              // 节点 `Completed` ∧ 申报槽空 ∧ **输出文本可被 `BlockedReader`
                              // 判为 blocked** ⇒ **不走 hold、不进提醒阶梯**，照既有行为终态化
                              // 为 blocked（`completeNode` 的文本锚定分流逐字不变）。
                              // 理由（代裁逐字）：文本说 BLOCKED 本身就是明确的求助申报——
                              // hold 住等于丢掉唯一求助通道（忘申报会被提醒救回，求助被 hold
                              // 则无人知晓，风险不对称）；豁免范围**严格限定**为「能被
                              // `BlockedReader` 判定为 blocked 的文本」，不是「任意非空文本」，
                              // 其余静默结束一律仍走 hold + 提醒阶梯。
                              // 文本来源 = **与终态点完完全全同一份**：`messages` 是同一个
                              // bridge 事件载荷，`extractLastAssistantText(messages)` 与终态
                              // 分支 `completeNode(nodeId, text = extractLastAssistantText(messages))`
                              // 同函数同输入 ⇒ 判定输入零漂移（不引入第二个文本来源）。
                              // 失败方向（代裁③）：判定抛错/不可判 ⇒ 保守**回落 hold**（= 今日
                              // leg 2 行为），不因豁免判定故障放宽闸门。
                              val anchoredBlocked: Boolean =
                                try BlockedReader.parse(extractLastAssistantText(messages)).isDefined
                                catch case _: Throwable => false
                              NodeReportRegistry.peek(sessionId).flatMap {
                                case Some(_) =>
                                  // 已申报 ⇒ 清表（申报 ⇒ 清表，唯一清表条件）后放行；
                                  // 消费语义仍唯一保留在终态点 drain（peek 不消费，
                                  // 否则申报信息在 1810 处已被吃掉、只能走文本降级面）。
                                  clearReportPending(nodeId, Some(sessionId)) *> releaseNow
                                case None if reportGateHoldEnabled && !anchoredBlocked =>
                                  // 未申报且**无 BLOCKED 文本锚定** ⇒ hold：**不终态化、不投递、
                                  // 不杀会话**（节点保持 Running 等人工处置），起表后桥继续存活
                                  // ——下一轮 Completed（提醒轮 / NodeMessage 重入 / 后台通知
                                  // 唤醒）在此复检。
                                  // 只置不重：提醒轮自身的 Completed 不改起点（代裁 4）。
                                  markReportPendingIfAbsent(nodeId, sessionId).as(bridge)
                                case None =>
                                  // 两条出口：① 腿 2 关（封存形态）——未申报照常放行 = 本批
                                  // 之前的文本锚定降级面行为（行为零变化）；② 腿 2 开但**文本
                                  // 锚定 BLOCKED 命中**（代裁 ⑧-4 豁免）——放行后由终态点的
                                  // 既有 `BlockedReader.parse` 分流成 blocked（求助上报链
                                  // blocked → 分发器 → 人 完整保留）。
                                  IO.whenA(reportGateHoldEnabled && anchoredBlocked)(
                                    logger.info(
                                      s"Node '$nodeName' ($nodeId) finished without a node_report but its final " +
                                        "text anchors BLOCKED — leg-2 hold waived (⑧-4 exemption); finalizing via the " +
                                        "existing blocked chain"
                                    )
                                  ) *>
                                    releaseNow
                              }
                            else
                              disarmCap *> holdEmitted.set(false) *>
                                setNodeBgWait(nodeId, Nil) *>
                                resultDeferred
                                  .complete(Left(FailOutcome(bgFailureMessage(failures, messages))))
                                  .attempt
                                  .void
                                  .as(Behaviors.stopped)
                          }
                      }
                    case AgentEvent.Failed(_, err) =>
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred
                          .complete(Left(FailOutcome(Option(err.message).getOrElse("unknown error"))))
                          .void
                          .as(Behaviors.stopped)
                    case AgentEvent.Cancelled(_, reason) =>
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred.complete(Left(FailOutcome(s"cancelled: $reason"))).void.as(Behaviors.stopped)

                override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
                  signal match
                    case SystemSignal.Terminated(_) =>
                      logger.warn(
                        s"Node '$nodeName' ($nodeId) session '$sessionId' terminated WITHOUT a terminal event " +
                          "(crashed or stopped unexpectedly) — finalizing node as failed"
                      )
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred
                          .complete(
                            Left(
                              FailOutcome(
                                s"node session '$sessionId' terminated without a terminal event (session crashed or was stopped unexpectedly)"
                              )
                            )
                          )
                          .void
                          .handleErrorWith(_ => IO.unit) // 与正常终态事件竞争时败方静默
                          .as(Behaviors.stopped)
              bridge
            }
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
            supervisorRef = Some(bridgeRef),
            // 恢复路径项目徽标（activeAgents 快照 → activeAgentEntryJson）。
            project = Some(projectName),
            displayName = Some(nodeName)
          )
        )
      )
      _ <- (ref ! AgentCommand.UserInput(text = inputText, replyTo = Some(bridgeRef))).void
      // 等待完成——不设超时（硬约束）；唯一竞争事件 = NodeCancel 信号。
      outcome <- IO.race(resultDeferred.get, cancelSig.get)
      // P2（R-1=B）：挂起腿识别。桥送来的 Cancelled 若带 [[NodeEngine.SuspendReasonPrefix]]
      // 哨兵 ⇒ 本次中断是**挂起**（stuck 自动恢复的恢复腿），不是终态化：只停 actor，
      // 节点保持 Running，由 watcher 侧 CAS + resume 接手。
      suspended <- IO.pure(outcome match
        case Left(Left(fo)) => NodeEngine.isSuspendOutcome(fo.message)
        case _ => false)
      _ <- outcome match
        case Right(_) =>
          logger.info(s"Node '$nodeName' cancelled — stopping agent")
          (ref ! AgentCommand.Stop("Node cancelled")).void
        case Left(Left(fo)) =>
          // 孤儿后台任务收殓（D1 主钩子，原语义）——**noderpt 批 B 段改为「登记窗口」
          // 之前，此处只保留挂起腿的即时收殓**：
          //   挂起腿（`isSuspendOutcome`）= 中途换会话（stuck 自动恢复的恢复腿，非终态）
          //   ——保留进程无意义且与 resume 后的新会话重复 ⇒ **不登记窗口、即时收殓不变**
          //   （任务要求 ③.5 逐字），其 `bg-harvest` 带 cause 留痕（R-5）逐字保留。
          //   其它桥终态出口（failed/cancelled/zombie/bg-wait-cap）不再即时收割——按作者
          //   裁定「一律存活 30 分钟再销毁」移到终态写点之后的 `scheduleDestroy` 登记，
          //   到点由 `sweepDestroyWindows` 执行同一个 `reclaimSession`（收殓动作集合逐字
          //   相同，只改时点）。NodeCancel 路径（Right）由 `Stop→killSessionShellProcesses`
          //   覆盖（即时，见下方登记段的排除说明）。
          if suspended then
            (BgTaskRegistry.reclaimSession(Some(sessionId), wsSendFn, rootSessionId) *>
              FlowMapEventLog.append(
                workspace,
                projectName,
                nodeId,
                "bg-harvest",
                // R-5 / 类⑦（stuck 自动恢复批 P2 附加小项，本板 #98 同源）：**补 cause**。
                // 此前该行是「无原因的终态化留痕」——读者只能看到「被回收」而不知
                // 「因何终态」，正是 #98「重启后 bg 会话回收把在飞节点判 cancelled
                // （无 result/无通知）→ 下游 barrier 静默死锁」取证时的第一个盲区。
                // cause 词表 = 与本次终态化同一个判据（挂起 / 取消 / 归还能力失败），
                // 由桥消息文本单点派生（不改 AgentEvent 形态，与 CancelSource 同纪律）。
                s"node finalized (${NodeEngine.finalizeCause(fo.message)}) — reclaimed bg session '$sessionId'; " +
                  s"cause: ${fo.message.take(160)}"
              ))
              .handleErrorWith(e =>
                logger.warn(s"Node '$nodeName' ($nodeId) bg-harvest reclaim failed: ${e.getMessage}")
              )
              .void
              .start *> IO.unit
          else IO.unit
        case Left(Right(_)) => IO.unit
      eventResult = outcome match
        case Left(r) => r
        case Right(_) => Left(FailOutcome("cancelled by NodeCancel"))
      _ <- resources.agentRegistry.update(_ - sessionId)
      _ <- system.stop(ref).handleErrorWith(_ => IO.unit)
      _ <- system.stop(bridgeRef).handleErrorWith(_ => IO.unit)
      _ <- running.update(_ - nodeId)
      // NodeMessage 注入链对称移除（与 running 表同点清理——此后 sendNodeMessage
      // 查无映射即走「注入未达」兜底，不再向死会话投递）。
      _ <- nodeSessions.update(_ - nodeId)
      // P2（R-1=B）：**挂起腿不终态化**。会话已被 Stop（上方 system.stop(ref)，
      // 与既有取消路径同款），registry/running/nodeSessions 三表已清（同上），
      // 但节点**不写 status/result/ttl、不通知、不摘除、不跑 barrier 检查**——
      // 它保持 `Running`，由 watcher 侧「有界等待 + hardResumeNode CAS + resume」
      // 接手。这正是把「清场」与「终态化」解耦的那一刀（设计 §3.6 选项 B）。
      _ <-
        if suspended then IO(suspendLog(sessionId, nodeId))
        else
          eventResult match
            case Right(messages) =>
              val text = extractLastAssistantText(messages)
              // blocked 结构化信号批（20260909 spec §5.2 #5①②；同日作者裁定泛化
              // NodeReport 统一三语义）：会话完成时点 drain 登记表——工具申报（协议
              // 事实）优先于文本锚定（降级面，行为零变化）。时序上必在 bg 闸之后
              // （resultDeferred 完成即等待集已放行）；drain take-and-remove 单次消费，
              // cancelled/failed 路径不消费（残留由 guarantee 内 cleanupRunTables 的
              // remove 对称清理）。
              // engine-defects 批 #239①（2026-09-15）：drain 取走即移除，而终态写有多条
              // **拒写**路径 ⇒ 旧口径「拒写 = 申报永久丢失、零痕迹」。修法 = 消费点持
              // 终态写的**落地判词**（landed），未落地者把申报全文补偿写回审计流
              // （见 [[compensateUnconsumedReport]]）；take-and-remove 单次消费语义不动。
              // #239② ⑤-(2)：改经 consumeReport 包装 ⇒ 终态写**抛异常**时同一补偿点照常触发
              // （`landed=false` 方向），异常原样重抛（控制流零变化）。
              NodeReportRegistry.drain(sessionId).flatMap { declared =>
                consumeReport(nodeId, sessionId, declared)(completeNodeR(nodeId, text, declared)).void
              }
            case Left(fo) =>
              if fo.message.contains("cancelled") then
                // R2：原因文本从桥消息还原（此前只做 `contains("cancelled")` 布尔嗅探后
                // 丢弃）。R7：触发源按 [[CancelSource]] 两态分类（引擎看门狗 vs 人/Agent
                // 主动取消），由 reason 文本特征推导——**不改 AgentEvent 消息形态**。
                val reason = CancelSource.reasonFromBridgeMessage(fo.message)
                // R4 × R5 交互裁定（本批唯一延迟摘除点）：L3 硬恢复路径（bridge Cancelled
                // → 5s → resume）**延后**摘除——立即摘除会让 resume 成功后拓扑永久缺轨
                // （下游 in 已被 prune、永远拿不到该节点结果）；resume 成功则拓扑完整保留，
                // 失败则由 R5 方案 4 的 [[settleFailedHardResume]] 改判 failed（**不摘除**，
                // D5 零结算停等——摘除 failed 早在 R4 裁定里被永久拒绝，见 cancelNode 头注
                // 与 [[detachCancelledUpstream]]）。
                // 其余取消路径（L3 之外的 watcher giveUp / AgentControl / 面板 NodeCancel /
                // 父会话级联）一律立即摘除。
                val deferDetach = reason.contains("(L3 hard-recovery:")
                // R5 方案 4（2026-09-10 裁定）：同一条 L3 路径同样**推迟回流**——中间态
                // Cancelled 不是终局（5s 后 resume 定生死），故与 deferDetach 同参数
                // 联动（notify=false = 占位推迟，见 cancelNode 头注）。
                // 🔴 **硬不变量（判据 M8 / 作者三答 4）**：L3 硬恢复中间态
                // （`deferDetach = true`）⇒ 级联**强制 false**——见
                // [[NodeEngine.l3CascadeAllowed]]。本腿请求的级联旗标**由该函数显式驱动**
                // （不是字面量，故「去掉这条绑定」= 可失败变异）：本腿唯一终态写路径是本
                // 受守卫的单节点写入口（不经 `cancelNodes` 链级/级联腿），
                // 「请求级联 ∧ L3 中间态」= 越权 ⇒ 守卫立即抛出：
                // resume 复活时拓扑必须完整，提前连带取消下游 = 恢复路径被掐死。
                // 注意**两条腿共用本调用点**：L3（deferDetach=true）与 L3 之外的
                // watcher giveUp / AgentControl / 面板 NodeCancel / 父会话级联
                // （deferDetach=false ⇒ 该腿允许级联 ⇒ 不抛、走既有单节点写路径）。
                val cascadeRequested = NodeEngine.l3CascadeAllowed(deferDetach)
                cancelNodeGuarded(
                  nodeId,
                  reason,
                  CancelSource.classify(reason),
                  detach = !deferDetach,
                  notify = !deferDetach,
                  cascadeRequested = cascadeRequested,
                  l3Intermediate = deferDetach
                )
              else failNode(nodeId, fo.message)
      // ③ 终态对称收割（noderpt 批 B 段 2026-09-11 作者裁定：一律存活 30 分钟再销毁）：
      // 桥终态**四出口**（failed / cancelled / zombie(猝死) / completed）统一到这一个
      // **登记点**——终态写点之后只登记窗口（`destroyAt` 字段 + 禁 spawn 表 +
      // `node-destroy-scheduled` 事件），不杀进程；到点由 `sweepDestroyWindows` 收殓。
      // blocked 出口在 `blockedNode` 内同款登记（口径一致）。
      // **两条腿不登记**（各附理由）：
      //   · 挂起腿（`suspended`）：中途换会话、非终态，进程已在上方即时收殓（③.5）；
      //   · NodeCancel 腿（`outcome = Right(_)` = cancelSig 抢先）：人为/看门狗主动取消，
      //     上方 `AgentCommand.Stop` → `killSessionShellProcesses` **即时收殓**（既有语义），
      //     「窗口内进程仍在」对该腿不成立——登记会留下与事实不符的痕迹，故不登记
      //     （其痕迹由既有 `cancelled` 事件承担）。
      _ <- outcome match
        case Right(_) => IO.unit
        case _ if suspended => IO.unit
        case _ =>
          scheduleDestroy(
            nodeId,
            List(sessionId),
            eventResult match
              case Right(_) => "completed"
              case Left(fo) => NodeEngine.finalizeCause(fo.message)
          )
    yield ()).guarantee {
      // 任意退出路径（正常/崩溃/异常/cancel）三表对称移除——与既有清理段
      // （agentRegistry/running/nodeSessions :947-953）同点幂等（先到先清）。
      runSig.get.flatMap {
        case Some(cancelSig) => cleanupRunTables(nodeId, sessionId, cancelSig)
        case None => IO.unit // cancelSig 未创建 = 未登记任何表 → 无可清
      }
    }
  end runWithAgent
