package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.AgentCommand
import nebflow.shared.{Message, ContentBlock, MessageRole, NebflowLogger}

/**
 * 子代理等待三缺口批（2026-10-25，缺口②）：**checkpoint 恢复的在飞工具调用终结面**。
 *
 * 990bd848 卡死链实证：delegate 在 Subagent 工具相位被 Stop → supervisor respawn
 * → transcript 断点里那条**没有 tool_result 的 tool_use**（在飞工具调用）原样
 * 进了恢复会话——模型看不见任何失败事实，只能盲目重发同一调用（自毒根因）；
 * 同时 registry 的工具相位字段跨 respawn 残留，watcher 追着幽灵相位把恢复会话
 * 健康的续跑轮当卡死反复掐杀，2 次重启配额烧尽、批失败。
 *
 * 本单点给恢复点的在飞工具调用三件明确处置（禁止原样重进等待）：
 *   ① **transcript 面**（[[terminateDanglingToolCalls]]，纯函数）：每条无
 *      result 的 tool_use 补一条 `recovered-after-crash` 错误 result——恢复
 *      会话的 LLM 看见失败事实，自行决定重试该步或绕行（Anthropic/OpenAI
 *      格式本就要求每条 tool_use 配对 result——这是补全，不是改写）。
 *   ② **子会话面**（[[disposeOrphanChildren]]）：崩溃会话名下仍 running 的
 *      Subagent/SubTask 子会话此时全是孤儿（唯一等它的人——崩溃前的工具
 *      fiber——已死，结果永远无人收）→ Stop + 结算（registry 移除 + task
 *      终态化 + ask 槽清理），镜像 [[BlockingSubagent]].reap 的结算面。
 *   ③ **registry 面**（[[staleToolPhaseCleared]]，纯函数）：恢复记录的工具
 *      相位清零——与 `AgentRegistryEmit.touchRegistryActivity`「离开 Processing
 *      一律清工具相位」的既有不变式对齐；respawn 直接 `agentRegistry.update`
 *      改状态曾绕过该不变式，幽灵 `tool 'Subagent' running Ns` 由此而来。
 *
 * fail-soft 纪律：恢复链任何一腿失败只落日志，绝不阻断 respawn 本体。
 */
object SubagentCrashRecovery:

  private val logger = NebflowLogger.forName("nebflow.agent.subagent-crash-recovery")

  /** 结构化失败 token（spec 判红锚 + 恢复会话 LLM 可见的事实标签）。 */
  val RecoveredAfterCrashToken: String = "recovered-after-crash"

  /** 补写 result 的固定文本（token 领头——机器可查；正文给模型的重试指引）。 */
  def crashResultText(toolName: String, toolUseId: String): String =
    s"[$RecoveredAfterCrashToken] The in-flight '$toolName' tool call (id $toolUseId) never completed: " +
      "the session crashed mid-call and the checkpoint was restored after the crash. " +
      "Its target side is no longer alive — no result will ever arrive for this call. " +
      "Retry this step if it is still needed (fresh call, fresh attempt); otherwise continue without it."

  /**
   * 缺口② ① transcript 面：给恢复点里每条**未配对** tool_use 补 `recovered-after-crash`
   * 错误 result（纯函数，可独立单测）。
   *
   * 判定 = 全量配对扫描：凡 id 未出现在其后任何 user 消息的 tool_result 中的
   * tool_use，即断点处的在飞调用（K-1/K-2 纪律保证完整批次才会落盘——transcript
   * 里出现悬挂 tool_use 当且仅当崩溃发生在批次中途）。补写形态与
   * `AgentProcessing.batchMessages` 的批次结算同构：紧随该 assistant 消息插一条
   * user 消息，逐条 tool_result（isError=true）。
   *
   * @return (修复后消息列表, 被终结的调用 (toolName, toolUseId) 列表——空列表 = 无悬挂，恒等)
   */
  def terminateDanglingToolCalls(messages: List[Message]): (List[Message], List[(String, String)]) =
    // 全量已配对 tool_use id 集（其后任何 user 消息里的 tool_result 都算配对）。
    val answered: Set[String] = messages
      .collect { case Message(MessageRole.User, Right(blocks), _, _) =>
        blocks.collect { case ContentBlock.ToolResult(id, _, _) => id }
      }
      .flatten
      .toSet
    val builder = List.newBuilder[Message]
    val terminated = List.newBuilder[(String, String)]
    messages.foreach { msg =>
      builder += msg
      msg match
        case Message(MessageRole.Assistant, Right(blocks), _, _) =>
          val dangling = blocks.collect {
            case u @ ContentBlock.ToolUse(id, name, _) if !answered.contains(id) => (u, name)
          }
          if dangling.nonEmpty then
            dangling.foreach { case (u, name) => terminated += ((name, u.id)) }
            builder += Message(
              MessageRole.User,
              Right(dangling.map { case (u, name) =>
                ContentBlock.ToolResult(u.id, crashResultText(name, u.id), Some(true))
              }),
              source = Some("crash-recovery")
            )
        case _ => ()
      end match
    }
    (builder.result(), terminated.result())

  end terminateDanglingToolCalls

  /**
   * 缺口② ② 子会话面：把崩溃会话名下仍 running 的子任务（SubAgentTask 行）
   * 全部按孤儿处置——Stop + 结算（task 终态化 cancelled + registry 移除 +
   * ask 槽清理）。唯一等它的人已随崩溃的 turn fiber 死亡，结果永远无人收。
   * 镜像 [[BlockingSubagent]].reap 的结算面（减父通知——父就是本会话，正 respawn）。
   *
   * @return 被处置的子会话 id 列表（空 = 无孤儿）。fail-soft。
   */
  def disposeOrphanChildren(resources: SharedResources, parentSessionId: String): IO[List[String]] =
    resources.subAgentTaskStore
      .loadTasks(parentSessionId)
      .map(_.filter(t => t.status == "running").map(_.taskId))
      .flatMap { orphanIds =>
        orphanIds.traverse { childId =>
          val taskUpdate = resources.subAgentTaskStore
            .updateStatus(parentSessionId, childId, "cancelled", completedAt = Some(System.currentTimeMillis()))
            .handleErrorWith(e => logger.warn(s"orphan $childId taskStore terminalize failed: ${e.getMessage}"))
          val askCleanup = resources.interactionHubRef.get
            .flatMap {
              case Some(hub) => (hub ! nebflow.actor.InteractionHubCommand.CleanupForSession(childId)).void
              case None => IO.unit
            }
            .handleErrorWith(e => logger.warn(s"orphan $childId ask-slot cleanup failed: ${e.getMessage}"))
          // ref 随 registry 记录取用，先取后移（移除后 watcher/面板不再见该行——reap 同款）。
          val settle = resources.agentRegistry.get
            .map(_.get(childId))
            .flatMap {
              case Some(rec) =>
                resources.agentRegistry
                  .update(_ - childId)
                  .handleErrorWith(e => logger.warn(s"orphan $childId registry remove failed: ${e.getMessage}")) *>
                  (rec.ref ! AgentCommand.Stop("orphaned-by-parent-crash"))
                    .handleErrorWith(e => logger.warn(s"orphan $childId stop offer failed: ${e.getMessage}"))
              case None => IO.unit
            }
          taskUpdate *> askCleanup *> settle *> IO.pure(childId)
        }
      }
      .flatTap {
        case Nil => IO.unit
        case ids =>
          logger.info(
            s"disposed ${ids.size} orphaned child session(s) of crashed '$parentSessionId': ${ids.mkString(", ")}"
          )
      }
      .handleErrorWith { e =>
        logger.warn(s"orphan disposal for '$parentSessionId' failed (respawn continues): ${e.getMessage}").as(Nil)
      }

  /**
   * 缺口② ③ registry 面（纯函数）：恢复记录的工具相位清零——「离开 Processing
   * 一律清工具相位」不变式的恢复点同款（respawn 落 Idle 必须同步清相位，否则
   * watcher 拿幽灵 `currentToolStartedAt` 反复判死，掐杀恢复会话健康的续跑轮）。
   */
  def staleToolPhaseCleared(rec: nebflow.actor.AgentRecord): nebflow.actor.AgentRecord =
    if rec.currentToolStartedAt == 0L && rec.currentToolName.isEmpty && rec.currentToolDeadlineMs == 0L then rec
    else rec.copy(currentToolName = None, currentToolStartedAt = 0L, currentToolDeadlineMs = 0L)

end SubagentCrashRecovery
