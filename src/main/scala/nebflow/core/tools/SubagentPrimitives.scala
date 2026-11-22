package nebflow.core.tools

import cats.effect.IO
import cats.effect.kernel.Deferred
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.*

/**
 * SubagentPrimitives — 子会话原语的**可复用单点**（dispatcher 重设计 P0「抽件」）。
 *
 * 本件自 [[BlockingSubagent]] 逐字搬出，**判据（语义 / 阈值 / 时序）零漂移**：
 * dispatcher 重设计（`dispatcher-redesign__20261119_043512` §6/§8）要把
 * 「完成桥 / 两段等待 / 终态回收 / 结构化失败 token」当作可复用原语接给
 * **引擎托管子会话**（P1 起）。P0 只做「抽件」——把复用面从 `BlockingSubagent`
 * 的私有成员升为单点对象；`BlockingSubagent` 经 re-export 原样保留同一批
 * 成员名，既有调用点（[[BlockingSubagent.run]]）与既有 spec 一字未改。
 *
 * 零行为变化证据：既有 `BlockingSubagentStartGuardSpec` /
 * `BlockingSubagentEndToEndSpec` / `SubagentCrashRecoverySpec` 抽取前后原样全绿；
 * 新行为闸 `SubagentPrimitiveSpec`（本条原语的四腿：帧匹配 / 预算耗尽 / 超时 /
 * `reap`）钉在本单点上。
 *
 * ==可见性（dispatcher 重设计 P1「放开原语可见性」）==
 * P0 的抽件把复用面收在 `nebflow.core.tools` 里（五个成员声明为 `private[tools]`），
 * 而 P1 的引擎侧读者落在 `nebflow.core.project` / `nebflow.core.delegate`——跨不出
 * `core.tools`（核稿 §6.1「抽件 = 放宽可见性或搬移」的另一半）。本件把那五个成员
 * （[[startBudget]] / [[awaitResult]] / [[reap]] / [[startSpy]] / [[spy]]）放宽为
 * `private[core]`：`core` 是同时覆盖这两个目标读者包的最小授予域，比 `nebflow` 或
 * 全公开更窄；[[Timeout]] / [[NeverStartedToken]] 保持公开不动。**纯可见性扩大，
 * 零运行时行为差异**；静态外壳闸 `scripts/check-subagent-primitive-visibility.mjs`。
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
 * ==终态回收==
 * 「终态即回收」此前只是一句注释，没有任何实现：桥完成（或超时）后 registry
 * 条目（kind=Delegate，source=subagent）滞留 status=Idle、task store 恒 "running"、子 actor 永驻
 * idle——getActiveAgents 快照按「registry 有记录 = 任务在飞」的契约如实上报，
 * 子智能体面板随即渲染永不消失的「空闲」行（现场实证：
 * turn-complete 的 subagent-* 子会话挂面板 5 分钟+）。[[reap]] 镜像
 * BackoffSupervisor.notifyParentAndStop 的结算面（task store 终态化 →
 * registry 移除 → ask 槽清理 → child Stop），减去父通知（父的结果 = 工具
 * 返回值本身，不重投）。delegate 编排模型零改动。
 */
object SubagentPrimitives:
  private val logger = NebflowLogger(getClass)

  /** 单次侦察硬超时（wall-clock；不含人机等待——本会话不等人）。 */
  val Timeout: FiniteDuration = 15.minutes

  /** 结构化失败 token（缺口①）：判子死的工具错误文本必带——父 agent 可据此分流（重试/绕过）。 */
  val NeverStartedToken: String = "subagent-never-started"

  /** 活性判定窗（缺口①）——[[Defaults.SubagentStartBudgetMs]] 的投影（测试注入缝）。 */
  private[core] def startBudget: FiniteDuration =
    nebflow.shared.Defaults.SubagentStartBudgetMs.millis

  /**
   * 缺口① 两段等待（子代理等待三缺口批 2026-10-25）——**实现单点 + spec 判红锚**
   * （真实计时，`StuckThresholdMs` 族的 kill-switch 可配）：
   *   - 段一（活性）：预算窗（[[startBudget]]，默认 120s）内等子会话第一帧。
   *     预算耗尽 = 判子死 → Stop + 补面板终态帧 + 结构化失败（[[NeverStartedToken]]
   *     领头——父 agent 可据此分流：重试/绕过）。
   *   - 段二（完成）：活性到场后与改造前逐字——15min 硬顶 + 完成桥读取
   *     finalText + 超时补帧腿。
   */
  private[core] def awaitResult(
    started: Deferred[IO, Unit],
    done: Deferred[IO, Option[String]],
    childId: String,
    stopChild: String => IO[Unit],
    emitPanelDone: IO[Unit]
  ): IO[Either[ToolError, String]] =
    started.get
      .timeout(startBudget)
      .attempt
      .flatMap {
        case Left(_) =>
          val budget = startBudget
          logger.warn(
            s"subagent $childId never started: zero event frames within $budget — judging the child dead ($NeverStartedToken)"
          ) *>
            stopChild(NeverStartedToken) *> emitPanelDone.as(
              Left(
                ToolError(
                  s"Subagent '$childId' spawned but never started: no event frame within $budget " +
                    s"(the child's first turn produced zero events — a provider hang on its first LLM " +
                    s"request is the typical cause). Judged dead and stopped; retry the call or continue " +
                    s"without it. ($NeverStartedToken)"
                )
              )
            )
        case Right(_) =>
          done.get
            .timeout(Timeout)
            .map {
              case Some(text) if text.trim.nonEmpty => Right(text.trim)
              case _ => Left(ToolError(s"Subagent '$childId' ended without a final text."))
            }
            .handleErrorWith { e =>
              logger.warn(s"subagent $childId timeout/crash: ${e.getMessage}")
              stopChild("subagent-timeout") *>
                // 超时腿子会话被 Stop 掐断，不再有自家 agentDone 帧——补一帧面板
                // 终态（成功腿子会话自家的 agentDone 已随间谍上行，无需补发）。
                emitPanelDone.as(
                  Left(
                    ToolError(s"Subagent did not finish within $Timeout — cancelled. Narrow the question and retry.")
                  )
                )
            }
      }

  /**
   * 叶子终态回收——镜像 [[BackoffSupervisor]]
   * `notifyParentAndStop` 的结算面，减去父通知（父的结果 = 工具返回值本身，
   * 已随桥交付，不重投）：
   *   ① task store 终态化（bridge 见到终态帧 = completed；超时兜底 = cancelled，
   *      与 AgentControl cancel 腿的 taskStatusOverride 同词表）；
   *   ② agentRegistry 移除——getActiveAgents 的契约「registry 有记录 = 任务
   *      在飞（完成即注销）」由此恢复（WebSocketRoutes.filterActiveAgents）；
   *   ③ InteractionHub ask 槽清理（僵尸审批卡防线，与监督者同款）；
   *   ④ child Stop——idle 永驻的子 actor 退场（AgentIdle Stop 分支：
   *      cancelTurn + 杀 shell + 生命周期钩子 + stopped）。
   * 全程 fail-soft：任何一腿失败只落日志，绝不改写工具结果。
   */
  private[core] def reap(
    resources: nebflow.core.AgentRuntimePort,
    parentSessionId: String,
    childId: String,
    childRef: nebflow.actor.ActorRef[nebflow.actor.AgentCommand],
    completed: Boolean
  ): IO[Unit] =
    val status = if completed then "completed" else "cancelled"
    def updateTask(attemptsLeft: Int): IO[Unit] =
      resources.subAgentTaskStore
        .updateStatus(parentSessionId, childId, status, completedAt = Some(System.currentTimeMillis()))
        .handleErrorWith { e =>
          if attemptsLeft > 1 then
            logger.warn(s"subagent $childId taskStore terminalize failed; retrying: ${e.getMessage}") *>
              updateTask(attemptsLeft - 1)
          else logger.warn(s"subagent $childId taskStore terminalize failed after retries: ${e.getMessage}")
        }
    val taskUpdate = updateTask(3)
    val registryRemove = resources.agentRegistry
      .update(_ - childId)
      .handleErrorWith(e => logger.warn(s"subagent $childId registry remove failed: ${e.getMessage}"))
    val askCleanup = resources.interactionHubRef.get
      .flatMap {
        case Some(hub) => (hub ! nebflow.actor.InteractionHubCommand.CleanupForSession(childId)).void
        case None => IO.unit
      }
      .handleErrorWith(e => logger.warn(s"subagent $childId ask-slot cleanup failed: ${e.getMessage}"))
    val stop = (childRef ! nebflow.actor.AgentCommand.Stop(s"subagent-terminal($status)"))
      .handleErrorWith(e => logger.warn(s"subagent $childId stop offer failed: ${e.getMessage}"))
    taskUpdate *> registryRemove *> askCleanup *> stop *>
      logger.info(s"subagent $childId reaped at terminal: status=$status, registry entry removed, actor stopped")

  end reap

  /**
   * 活性间谍（缺口①）：子会话经此上行的**第一帧**（任何类型——`agentStart`
   * 领衔，完成帧天然蕴含已活性）即落活性标记，然后原样交完成桥。零新协议——
   * 与完成桥同一间谍链，只是多读一个「有帧」事实。
   */
  private[core] def startSpy(
    started: Deferred[IO, Unit],
    base: io.circe.Json => IO[Unit]
  ): io.circe.Json => IO[Unit] = json => started.complete(()).attempt.void *> base(json)

  /** wsSend 间谍：agentDone/agentEnd 且 nodeSessionId 命中 ⇒ 完成桥。 */
  private[core] def spy(
    done: Deferred[IO, Option[String]],
    childId: String,
    base: io.circe.Json => IO[Unit]
  ): io.circe.Json => IO[Unit] = json =>
    base(json) *> {
      val obj = json.asObject
      val t = obj.flatMap(_("type")).flatMap(_.asString)
      val nsid =
        obj.flatMap(_("nodeSessionId")).flatMap(_.asString).orElse(obj.flatMap(_("agentId")).flatMap(_.asString))
      if (t == Some("agentDone") || t == Some("agentEnd")) && (nsid.isEmpty || nsid.contains(childId)) then
        obj
          .flatMap(_("finalText"))
          .flatMap(_.asString)
          .match
            case Some(text) => done.complete(Some(text)).attempt.void
            case None => done.complete(None).attempt.void
      else IO.unit
    }
end SubagentPrimitives
