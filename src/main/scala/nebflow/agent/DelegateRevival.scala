package nebflow.agent

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentDef, AgentEvent, AgentKind}
import nebflow.core.SessionStore
import nebflow.core.delegate.{ChainStore, DelegateRegistry}
import nebflow.core.executor.ExecutorRegistry
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.PluginRegistry
import nebflow.core.project.DelegateFlowHosts
import nebflow.shared.{Defaults, NebflowLogger, SubAgentTask}

/**
 * DelegateRevival —— delegate 监督链**跨重启复活腿**（监督链自动恢复批 2026-10-22，
 * 作者令；设计 `docs/design/delegate-supervision-revival-design.zh-CN.md`）。
 *
 * == 关闭的缺口（监督链 L3 层）==
 * 进程内，`BackoffSupervisor.respawn` 用磁盘 transcript 做断点续跑（recovered
 * messages + continue nudge + 预算重挂）；但**引擎进程本身**重启后监督链随进程消亡，
 * `SubAgentStartupRecovery` 启动清扫只做收殓（running → failed + 父通知）——在飞
 * delegate 的工作被迫整段重派。而 delegate 的四件恢复现场盘上样样俱全：任务行
 * （SubAgentTaskStore）、地址簿记录（DelegateRegistry：cwd/worktree/branch/plugins）、
 * 会话转录（SessionStore）、工作产物（座位目录）。本对象把它们重新接起来：**同 id
 * 复活**（session 身份 = transcript 身份 = `delegate:<id>` 地址身份），从 checkpoint
 * 续跑，父会话最终收**续作结果**而非失败通知。
 *
 * == 分工边界（防双头管理，设计 §2.5）==
 *  - 本对象只是 `SubAgentStartupRecovery` sweep 的**复活腿**：资格不满足/预算耗尽/
 *    复活失败 → 调用方落既有收殓链（逐字不改）。无第二个 sweep、无新 watchdog。
 *  - `ProjectCrashRecovery`（flow-map 节点轨 L1/L2）零触碰；`MergeQueueBootSweep`
 *    （ready-to-merge 合并轮）零交集——任务行状态不同域。
 *  - 复活重建的监督链此后归 `BackoffSupervisor` 管（respawn/终态/合并停靠全链复用）。
 *
 * == 复活判据（设计 §2.2，全部满足才复活）==
 * kill-switch 开 ∧ source=delegate ∧ registry 记录在（内置执行器、未归档）∧ 座位目录
 * 在 ∧ 父会话在 ∧ 任务级预算（revivalCount < max）∧ 年龄预算 ∧ boot 级预算（调用方
 * 按 spawnedAt 新者优先截取）。
 *
 * == 复活动作（设计 §2.3，镜像 `BackoffSupervisor.respawn` 的跨进程版）==
 * 任务行先行翻 running（复活半途再崩 = 下一 boot 看到诚实的 running 行）→ spawn
 * 同 id 会话（`initialMessages = transcript`）→ registerAgent 活体回写 → 作用域图
 * 宿主重挂（fail-soft）→ 插件 best-effort 重挂（fail-open）→ nudge 注入 →
 * ChainStore `delegate-revival` 审计行。
 */
object DelegateRevival:

  private val logger = NebflowLogger.forName("nebflow.agent.delegate-revival")

  /** 复活依赖（GatewayMain boot 装配点传入；None = 复活腿整体旁路——测试/无载体场）。 */
  final case class Deps(
    resources: SharedResources,
    system: ActorSystem,
    /** 子会话 WS 事件面（boot 链 = wsHub broadcast；None = 静默）。 */
    wsSend: Option[Json => IO[Unit]]
  )

  /** 座位目录存在性（java 判——record.cwd 可能是任意历史串，构造失败 = 缺席）。 */
  private def seatExists(cwd: String): Boolean =
    try java.nio.file.Files.isDirectory(java.nio.file.Path.of(cwd))
    catch case _: Throwable => false

  /**
   * 一条孤儿的复活资格判读。None = 可复活；Some(reason) = 不可复活（落收殓链）。
   * boot 级预算（cap）由调用方按 spawnedAt 新者优先截取——不在本判据内。
   */
  def eligibility(sessionStore: SessionStore, task: SubAgentTask): IO[Option[String]] =
    if !Defaults.DelegateRevivalEnabled then
      IO.pure(Some("kill-switch off (nebflow.delegate.revival.enabled=false)"))
    else if task.source != "delegate" then
      IO.pure(Some(s"source=${task.source} (only delegate tasks revive; subtasks are cheap to re-dispatch)"))
    else
      IO.blocking(DelegateRegistry.find(task.taskId)).flatMap {
        case None => IO.pure(Some("no DelegateRegistry record — cannot rebuild the seat"))
        case Some(rec) =>
          val budgetLeft = task.revivalCount.getOrElse(0) < Defaults.DelegateRevivalMaxPerTask
          val ageOk = java.lang.System.currentTimeMillis() - task.spawnedAt <= Defaults.DelegateRevivalMaxAgeMs
          val parentExistsIo = sessionStore.listSessions.map(_.exists(_.id == task.parentSessionId))
          parentExistsIo.map { parentExists =>
            if rec.executor != ExecutorRegistry.DefaultId then
              Some(s"external executor '${rec.executor}' (boot revival is internal-executor only; use the Mail continuation leg)")
            else if rec.archivedAt.isDefined then Some("record archived")
            else if !seatExists(rec.cwd) then Some(s"seat directory missing: ${rec.cwd}")
            else if !parentExists then Some(s"parent session '${task.parentSessionId}' no longer exists")
            else if !budgetLeft then
              Some(s"revival budget exhausted (${task.revivalCount.getOrElse(0)}/${Defaults.DelegateRevivalMaxPerTask})")
            else if !ageOk then
              Some(s"orphan older than revival age budget (${Defaults.DelegateRevivalMaxAgeMs}ms) — stale, salvage only")
            else None
          }
      }

  /**
   * 复活一条孤儿（资格已由调用方判过）。返回 Left(reason) = spawn 链失败
   * （调用方落收殓链）；Right(()) = 已复活（行已翻 running、活体已注册、nudge 已注入）。
   */
  def revive(deps: Deps, task: SubAgentTask): IO[Either[String, Unit]] =
    reviveImpl(deps, task)
      .handleErrorWith(e =>
        logger.warn(s"[delegate-revival] revival errored for ${task.taskId}: ${e.getMessage}").as(Left(s"revival error: ${e.getMessage}"))
      )

  /** spawn 链本体（镜像 `BackoffSupervisor.respawn` 的跨进程版；设计 §2.3 ①–⑨）。 */
  private def reviveImpl(deps: Deps, task: SubAgentTask): IO[Either[String, Unit]] =
    IO.blocking(DelegateRegistry.find(task.taskId)).flatMap {
      case None => IO.pure(Left("no DelegateRegistry record — cannot rebuild the seat"))
      case Some(record) =>
        val now = java.lang.System.currentTimeMillis()
        val nextCount = task.revivalCount.getOrElse(0) + 1
        val parentSid = task.parentSessionId
        for
          // ① 任务行先行：running + 预算记账 + 旧错清空（复活 = 新执行窗口）。
          //    先写行再 spawn——复活半途再崩，下一 boot 看到的是诚实的 running 行。
          _ <- deps.resources.subAgentTaskStore
            .markRevived(parentSid, task.taskId, nextCount, now)
            .handleErrorWith(e =>
              logger.warn(s"[delegate-revival] task-row update failed for ${task.taskId}: ${e.getMessage}")
            )
          // ② transcript checkpoint（respawn 同源：loadMessagesForSession）。
          transcript <- deps.resources.sessionStore
            .loadMessagesForSession(task.taskId)
            .handleErrorWith(e =>
              logger.warn(s"[delegate-revival] transcript load failed for ${task.taskId}: ${e.getMessage}").as(Nil)
            )
          // ③ 父会话活引用（Mail 续聊腿 resolveContinuationParentRef 同款判读）；
          //    不 live = None ⇒ 终态走监督者 F2 队列兜底（notifyParentAndStop 的
          //    parentRef=None 分支，本批加持久投递腿）。
          parentRef <- resolveParentRef(deps.resources, parentSid)
          // ④ agentDef：全部内置 delegate 同一 entry（spawnKernel 同源，确定性重建）；
          //    插件 best-effort 重挂（fail-open——重挂失败绝不阻塞复活）。
          baseDef <- IO.blocking(
            nebflow.core.entity.BuiltinAgents
              .delegateEntry(nebflow.core.entity.BuiltinAgents.ExecutorName)
              .map(_.toAgentDef)
              .get
          )
          effectiveDef <- regrantPlugins(deps.resources, task.taskId, record, baseDef)
          childWsSend = nebflow.core.tools.DelegateTool.routeWsSendFor(
            task.taskId,
            Some(parentSid),
            deps.wsSend
          )
          params = NodeRunner.SpawnParams(
            agentDef = effectiveDef,
            resources = deps.resources,
            sessionId = task.taskId,
            sessionName = task.description,
            depth = 1, // 原 depth 不在盘上；深度预算从复活窗重起算（设计 §2.6）
            parentRef = parentRef,
            wsSend = childWsSend,
            projectRoot = Some(record.cwd),
            rootSessionId = parentSid // 根归属以 parent 估计（registry 不存根 id，设计 §2.6）
          )
          // ⑤ spawn 双件套（spawnWithPlugins 同点）+ 活体回写。
          subagentRef <- deps.resources.spawnAgentActor(deps.system, params)
          adapterRef <- deps.resources.spawnSupervisedAdapter(
            system = deps.system,
            params = params,
            childRef = subagentRef,
            childName = task.taskId,
            description = task.description,
            agentName = task.agentName,
            subagentId = task.taskId,
            parentSessionId = parentSid,
            initialPrompt = task.prompt,
            source = "delegate",
            extraMetadata = JsonObject("revivedBy" -> "startup-recovery".asJson),
            wsSend = Some(childWsSend)
          )
          _ <- NodeRunner.registerAgent(
            deps.resources,
            id = task.taskId,
            ref = subagentRef,
            kind = AgentKind.Delegate,
            rootSessionId = parentSid,
            parentRef = parentRef,
            startedAt = now,
            lastActivityMs = now,
            supervisorRef = Some(adapterRef),
            parentSessionId = parentSid
          )
          // ⑥ 作用域图宿主重挂（spawn 腿同点；fail-soft——delegate 本体不等宿主）。
          _ <- DelegateFlowHosts
            .mount(
              delegateId = task.taskId,
              project = record.project,
              baseWorkspace = record.projectWorkspace.getOrElse(record.cwd),
              engineCwd = record.cwd,
              system = deps.system,
              resources = deps.resources,
              wsSend = Some(childWsSend)
            )
            .handleErrorWith(e =>
              logger
                .warn(
                  s"[delegate-revival] flow-host mount failed for ${task.taskId} (delegate continues without a scope map): ${e.getMessage}"
                )
                .as(None)
            )
          // ⑦ 预算重挂（respawn 同款——机制现值为 no-op，调用保结构同位，机制重挂时
          //    复活腿自动正确）。
          _ <- DelegateBudget.register(task.taskId)(adapterRef ! AgentEvent.Cancelled(task.taskId, "timeout"))
          // ⑧ ChainStore 审计行（delegate-spawn 行族同构；#1228 链视图数据源可见）。
          _ <- IO
            .blocking(
              ChainStore.appendSync(
                ChainStore.keyFor(record.taskId, task.taskId),
                "delegate-revival",
                Json.obj(
                  "action" -> "revived".asJson,
                  "delegateId" -> task.taskId.asJson,
                  "phase" -> "boot-recovery".asJson,
                  "revivalCount" -> nextCount.asJson,
                  "workspace" -> record.cwd.asJson,
                  "branch" -> record.branch.asJson,
                  "transcriptMessages" -> transcript.size.asJson
                )
              )
            )
            .handleErrorWith(e =>
              logger.warn(s"[delegate-revival] ChainStore row failed for ${task.taskId}: ${e.getMessage}").void
            )
          // ⑨ nudge 注入：转录非空 = checkpoint 续跑；空 = 原 prompt 重注（respawn
          //    fresh-start fallback 同款）。
          nudge =
            if transcript.nonEmpty then
              s"[system] The engine process restarted while your task was in flight. Your work so far (${transcript.size} transcript messages) is loaded below — continue the task from where you left off."
            else task.prompt
          _ <- subagentRef ! AgentCommand.UserInput(nudge, Some(adapterRef))
          _ <- logger.info(
            s"[delegate-revival] revived ${task.taskId} (agent=${task.agentName}, revival #$nextCount, " +
              s"seat=${record.cwd}, transcript=${transcript.size} msgs, parentLive=${parentRef.isDefined}) — " +
              "supervision chain rebuilt (BackoffSupervisor watching again)"
          )
        yield Right(())
    }

  /** 插件 best-effort 重挂：registry 记录的 plugins 集逐名 resolve → MCP 授予 +
    * builtin 工具并集 → effectiveDef。零分配/全失败 = 原 def（fail-open）。 */
  private def regrantPlugins(
    resources: SharedResources,
    sessionId: String,
    record: DelegateRegistry.Record,
    baseDef: AgentDef
  ): IO[AgentDef] =
    val names = record.plugins.getOrElse(Nil)
    if names.isEmpty then IO.pure(baseDef)
    else
      names
        .traverse(n => PluginRegistry.resolve(n).map(_.toOption))
        .flatMap { resolved =>
          val defs = resolved.flatten
          val missing = resolved.count(_.isEmpty)
          if missing > 0 then
            logger.warn(
              s"[delegate-revival] plugin re-grant: $missing of ${names.size} package(s) unresolvable for $sessionId (continuing without them)"
            )
          val mcpDefs = defs.filter(_.mcpServers.nonEmpty)
          val builtinTools = defs.flatMap(_.toolsExtension).distinct
          val grantIo: IO[Either[String, nebflow.core.plugin.PluginMcpManager.Grant]] =
            if mcpDefs.isEmpty then IO.pure(Right(nebflow.core.plugin.PluginMcpManager.Grant(Nil)))
            else resources.pluginMcp.acquire(sessionId, mcpDefs)
          grantIo
            .map {
              case Left(err) =>
                logger.warn(s"[delegate-revival] plugin MCP re-grant failed for $sessionId (continuing without): $err")
                baseDef
              case Right(grant) =>
                if grant.serverIds.isEmpty && builtinTools.isEmpty then baseDef
                else baseDef.copy(pluginMcpServers = grant.serverIds, pluginTools = builtinTools)
            }
            .handleErrorWith(e =>
              logger.warn(s"[delegate-revival] plugin re-grant error for $sessionId (continuing without): ${e.getMessage}").as(baseDef)
            )
        }

  /** 父会话活引用（Mail 续聊腿同款判读：统一注册表现查——重启后一律视为不 live）。 */
  private def resolveParentRef(resources: SharedResources, parentSessionId: String): IO[Option[ActorRef[AgentCommand]]] =
    if parentSessionId.isEmpty then IO.pure(None)
    else resources.agentRegistry.get.map(_.get(parentSessionId).map(_.ref))

  /**
   * 慢段复活执行器（sweep 调用）：信号量并发护栏 + 逐条 fork 并发复活 + **join 收口**
   * （护栏管批内吞吐；收口保证 sweep 返回时 revived 报告读数精确、复活腿的
   * registerAgent 已落定——后续 boot 的活体清退不再与本 fork 竞态，设计 §2.2「报告
   * 如实」）。spawn 链失败的单条经 `salvage` 落既有收殓链（诚实终态化 + 父通知——
   * 绝不静默悬挂）。
   */
  def reviveAll(deps: Deps, tasks: List[SubAgentTask], salvage: SubAgentTask => IO[Unit]): IO[Ref[IO, List[String]]] =
    Ref.of[IO, List[String]](Nil).flatMap { revivedRef =>
      Semaphore[IO](math.max(1, Defaults.DelegateRevivalConcurrency)).flatMap { sem =>
        tasks.traverse { t =>
          (sem.permit.use(_ =>
            revive(deps, t).flatMap {
              case Right(()) => revivedRef.update(_ :+ t.taskId)
              case Left(reason) =>
                logger.warn(
                  s"[delegate-revival] revival failed for ${t.taskId}: $reason — falling through to the salvage chain"
                ) *> salvage(t)
            }
          )).start
        }.flatMap(fibers => fibers.traverse_(_.join.void)) *> IO.pure(revivedRef)
      }
    }

end DelegateRevival
