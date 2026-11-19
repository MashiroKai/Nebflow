package nebflow.core.tools

import cats.effect.IO
import cats.effect.kernel.Deferred
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.node.NodeRunner
import nebflow.shared.{Defaults, NebflowLogger, SubAgentTask}

import scala.concurrent.duration.*

/**
 * BlockingSubagent — single spawn point for blocking reconnaissance sessions.
 *
 * Explore 同型（外部 CLI agent 的 explore 型子代理）：spawn 一个 `subagent`
 * 内置会话（固定工具面 = `AgentCore.SubagentFixedTools`），**阻塞**
 * 到它完成，把最终文本作为工具结果返回。
 *
 * ==完成桥（wsSend 间谍）==
 * 子会话的流事件本来就要经 `wsSend` 上行（面板渲染面）——本点在路由包装外再包
 * 一层间谍：见到 `agentDone` / `agentEnd` 且归属本子会话（nodeSessionId 或
 * agentId 命中）即完成 Deferred。零新协议、零新 actor；超时（[[Timeout]]）
 * 是兜底而非主路径。
 *
 * ==活性判定窗（子代理等待三缺口批 2026-10-25，缺口①）==
 * spawn 后预算窗（[[Defaults.SubagentStartBudgetMs]]，默认 120s）内子会话
 * **零事件帧** = 判子死：工具以结构化失败 `subagent-never-started` 返回，
 * 父 agent 得以自行处置（重试/绕过），不再把「子会话首个 LLM 请求 provider-hang
 * 永不出声」的形态干等满 15min 硬顶（990bd848 卡死链实证）。哲学对齐 #692：
 * 有活性事实才继续等，等不到事实是「对端死亡判定」，不是超时杀。活性事实 =
 * 子会话经本间谍的**第一帧**（`agentStart` 领衔——turn 派发的第一拍即发，
 * LLM 流楔死形态之后零帧照常覆盖）；完成帧（agentDone/agentEnd）天然蕴含已
 * 活性，同样先落活性标记再走完成桥。零新协议、零新 actor——与完成桥同一间谍。
 *
 * ==叶子纪律==
 * subagent 是叶子：不注册 SupervisorAdapter（无后台续命），终态即回收；取消
 * 由 AgentControl 的 SubTask 通道（Stop fallback）覆盖。
 *
 * ==终态回收==
 * 「终态即回收」此前只是一句注释，没有任何实现：桥完成（或超时）后 registry
 * 条目（kind=SubTask）滞留 status=Idle、task store 恒 "running"、子 actor 永驻
 * idle——getActiveAgents 快照按「registry 有记录 = 任务在飞」的契约如实上报，
 * 子智能体面板随即渲染永不消失的「空闲」行（现场实证：
 * turn-complete 的 subagent-* 子会话挂面板 5 分钟+）。现补上 [[reap]]：镜像 BackoffSupervisor.notifyParentAndStop 的
 * 结算面（task store 终态化 → registry 移除 → ask 槽清理 → child Stop），减去
 * 父通知（父的结果 = 工具返回值本身，不重投）。delegate 编排模型零改动。
 */
object BlockingSubagent:
  private val logger = NebflowLogger(getClass)

  /**
   * 子会话原语（dispatcher 重设计 P0「抽件」）——单点 = [[SubagentPrimitives]]。
   * 复用面经此 re-export 原样暴露（既有 [[BlockingSubagent.awaitResult]] /
   * [[BlockingSubagent.startSpy]] / [[BlockingSubagent.startBudget]] /
   * [[BlockingSubagent.NeverStartedToken]] 等调用点与既有 spec 一字未改）；判据
   * （语义 / 阈值 / 时序）逐字保持，零行为变化。
   */
  export SubagentPrimitives.{Timeout, NeverStartedToken, startBudget, awaitResult, reap, startSpy, spy}

  /**
   * 阻塞运行一次子会话。返回子会话最终文本。
   *
   * @param defName spawn 目标的内置定义名：`subagent`（侦察面，默认）或
   *   `nebflow`（执行面——WorkflowTool 的步骤用这套，步骤要写要执行）。
   */
  def run(
    prompt: String,
    description: String,
    ctx: ToolContext,
    defName: String = nebflow.core.entity.BuiltinAgents.SubagentName
  ): IO[Either[ToolError, String]] =
    (ctx.actorSystem, ctx.sharedResources) match
      case (Some(system), Some(resources)) =>
        if ctx.depth >= DelegateTool.MaxDepth then
          IO.pure(Left(ToolError(s"Maximum sub-agent depth (${DelegateTool.MaxDepth}) reached.")))
        else
          val childId = s"subagent-${java.util.UUID.randomUUID().toString.take(8)}"
          val parentSid = ctx.sessionId
          val childDepth = ctx.depth + 1
          for
            // 消息身份牌消解（msgcard-agent-expand 批 2026-11-05）：子代理 prompt 也是
            // **面向 agent 的文本**——Subagent 的 prompt 与 Workflow 步骤的 task 都从
            // 这里进子会话，故投递前展开成完整原文块（映射缺失 / 格式残缺 ⇒ 显式标记）。
            // 单点覆盖两条腿（SubagentTool 直接调、WorkflowTool 以 defName 变体调）。
            resolvedPrompt <- resources.msgTokens.resolveForAgent(prompt)
            done <- IO.deferred[Option[String]]
            // 缺口①（子代理等待三缺口批 2026-10-25）：活性判定窗的完成源——子会话
            // 第一帧即落（见 startSpy）；预算窗内未落 = 判子死。
            started <- IO.deferred[Unit]
            childWsSend = SubagentPrimitives.startSpy(
              started,
              SubagentPrimitives.spy(done, childId, DelegateTool.routeWsSendFor(childId, parentSid, ctx.wsSend))
            )
            agentDef = nebflow.core.entity.BuiltinAgents.entry(defName).map(_.toAgentDef).get
            params = NodeRunner.SpawnParams(
              agentDef = agentDef,
              resources = resources,
              sessionId = childId,
              sessionName = description,
              depth = childDepth,
              parentRef = ctx.agentActorRef,
              wsSend = childWsSend,
              projectRoot = Some(ctx.projectRoot),
              sessionCwd = ctx.sessionCwd.orElse(Some(ctx.projectRoot)),
              rootSessionId = rootOf(ctx)
            )
            ref <- NodeRunner.spawnAgentActor(system, params)
            _ <- NodeRunner.registerAgent(
              resources,
              id = childId,
              ref = ref,
              kind = nebflow.actor.AgentKind.SubTask,
              rootSessionId = rootOf(ctx),
              parentRef = ctx.agentActorRef,
              startedAt = System.currentTimeMillis(),
              lastActivityMs = System.currentTimeMillis(),
              parentSessionId = parentSid.getOrElse("")
            )
            _ <- resources.subAgentTaskStore
              .recordTask(
                SubAgentTask(
                  taskId = childId,
                  parentSessionId = parentSid.getOrElse(""),
                  agentName = defName,
                  prompt = resolvedPrompt,
                  description = description,
                  status = "running",
                  retryCount = 0,
                  spawnedAt = System.currentTimeMillis(),
                  completedAt = None,
                  lastError = None,
                  source = "subagent"
                )
              )
              .handleErrorWith(e => logger.warn(s"subAgentTaskStore.recordTask failed: ${e.getMessage}"))
            // sleep-poll 禁令批 C 段：步/子会话首条消息注入等待纪律铁律
            // （Defaults.NoSleepPollRule 单点——delegate brief / 节点脚注同源）。
            // Subagent role policy overrides shared background advice: its shell
            // reconnaissance must finish in the foreground before the final answer.
            _ <- ref ! AgentCommand.UserInput(promptWithRule(resolvedPrompt), None)
            // ── 缺口① 两段等待（2026-10-25）——实现单点 [[awaitResult]]（spec 判红锚）
            result <- SubagentPrimitives.awaitResult(
              started = started,
              done = done,
              childId = childId,
              stopChild = (reason: String) => (ref ! AgentCommand.Stop(reason)).attempt.void,
              emitPanelDone = childWsSend(
                io.circe.Json.obj("type" -> "agentDone".asJson, "agentId" -> childId.asJson)
              ).attempt.void
            )
            // 叶子终态回收——桥已定局（成功或超时），
            // 结算 registry / task store / 子 actor。滞留即「空闲」僵尸行 + 永久
            // running 记录 + actor 泄漏（根因取证见类注释）。
            _ <- SubagentPrimitives.reap(
              resources = resources,
              parentSessionId = parentSid.getOrElse(""),
              childId = childId,
              childRef = ref,
              completed = result.isRight
            )
          yield result
          end for
      case _ =>
        IO.pure(Left(ToolError("Subagent requires ActorSystem and SharedResources")))

  private def rootOf(ctx: ToolContext): String =
    ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")

  /**
   * 步/子会话首条消息铁律包装（sleep-poll 禁令批 C 段单点 + output 块语法批
   * 追加；spec 判红锚）——铁律前缀 + output 块纪律 + 空行 + 原始任务文本
   * （子会话无历史，首条即全部上下文）。
   *
   * output 块总闸·提示词面（prompt-face 补批 2026-11-01，作者令「把 output 块
   * 相关的内容先关闭」）：`includeOutputRule=false` ⇒ 铁律后不追 output 块纪律，
   * 首条消息 = 铁律 + 空行 + 任务原文。生产缺省 = **组装期热读**
   * `nebflow.shared.OutputBlockGate.enabled()`（每次包装求值，翻闸后新派发即
   * 翻面）；离线 spec 注入常值与机器配置解耦。
   */
  private[tools] def promptWithRule(
    prompt: String,
    /** output 块总闸（prompt-face 补批）：true = 包装携带 OutputBlockRule。 */
    includeOutputRule: Boolean = nebflow.shared.OutputBlockGate.enabled()
  ): String =
    if includeOutputRule then s"${Defaults.NoSleepPollRule}\n\n${Defaults.OutputBlockRule}\n\n$prompt"
    else s"${Defaults.NoSleepPollRule}\n\n$prompt"
end BlockingSubagent
