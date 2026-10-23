package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.shared.NebflowLogger

import java.util.concurrent.atomic.AtomicReference

/**
 * D1 后台任务通知投递可靠性（steward 批 2026-10-23；**作者补充裁定 2026-10-23
 * 09:46**「目标会话死了就激活发送，不用发给root」——改道投 root 的方案废除）：
 * 交付不再盲投「创建时焊死的会话 ActorRef」。旧形态 = `ref ! ExternalEvent` 直投 +
 * 失败 WARN 吞掉——但 `ref !` 是队列 offer，**从不失败**：目标会话 actor 已退出时
 * 消息进无人消费的队列静默蒸发（bg-wait cap 强制收尾后同样投尸）。本件把交付升级
 * 为**活性判定 + 激活目标会话 + 持久补投**三段：
 *   - 目标活 ⇒ 直投（既有行为，字节不变）；
 *   - 目标死 ⇒ **激活（复活）目标会话本身**，把完成通知投给它（[[BgSessionActivator]]
 *     M2 注册器；复活机制与引擎重启后的会话恢复同源——同一套持久化恢复路径：会话
 *     状态已持久化，激活 = 按 SessionStore 水合 transcript 重 spawn（WS connect 恢复
 *     同款），spawn 即 `RecoverPersistedQueues` 重放注入队，通知落在目标会话新回合）；
 *   - 激活不可用（重启窗口 / 该会话族的复活归监督者与 boot sweep 腿所有）⇒
 *     **持久「待补投」标记**：事件写入**目标会话自己**的持久注入队（[[BgInboxPort]]，
 *     correlationId 幂等）——目标会话复活 / 新回合时由既有 RecoverPersistedQueues
 *     重放补投给它（输出本体照旧由 `BgTaskOutputStore.finalizeTask` 落盘，标记只补
 *     通知）；
 *   - 「WARN 吞掉」静默路径消灭：激活 / 补投 / 不可投三态全部落审计（项目事件流
 *     `bg-notify-*` 行）+ 响亮日志。
 *
 * 分层纪律：激活与持久注入队的实现在 agent/gateway 层，core.tools 经
 * [[BgSessionActivator]] / [[BgInboxPort]] 两个 M2 注册器消费（DelegateJudgePort /
 * SendConfirmPort 同款形态）；注册点 = gateway.WebSocketRoutes 构造行；未注册兜底 =
 * 响亮 WARN + 持久补投腿（不炸调用链——投递兜底绝不拖垮任务终态三清）。
 */

/** 持久注入队 append 面（幂等；会话已删 = no-op——不产生无人读的垃圾文件）。 */
object BgInboxPort:

  trait Face:
    def append(sessionId: String, event: AgentCommand.ExternalEvent): IO[Unit]
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  def append(sessionId: String, event: AgentCommand.ExternalEvent): IO[Unit] =
    face.get match
      case Some(f) => f.append(sessionId, event)
      case None =>
        // 未装配（无 SharedResources 的测试 harness）：诚实出声，不静默。
        NebflowLogger
          .forName("nebflow.bg-notify")
          .warn(
            s"BgInboxPort not installed — pending-redelivery event for session '$sessionId' could not be persisted " +
              "(production boot installs this at SharedResources construction)"
          )
end BgInboxPort

/**
 * 目标会话激活面（作者裁定 2026-10-23 09:46 的机制落点）：把已死的目标会话经
 * **引擎重启后的会话恢复同源路径**（持久化恢复 spawn——水合 transcript +
 * `RecoverPersistedQueues` 注入队重放）复活，完成通知由此进入其新回合。
 *
 * 契约：
 *   - `Activated`   = 会话已激活且 `pendingEvent` **已由本面持久化**进该会话注入队
 *     （spawn 重放交付）——调用方不再重复落盘；
 *   - `Deferred(r)` = 该会话族的复活归监督者 / boot sweep 等既有恢复腿所有（如
 *     delegate 的 respawn / 重挂），本面不越权——调用方落持久标记等自然复活重放；
 *   - `UnknownSession` = 会话记录不存在（已删）——无处激活也无处落盘，调用方审计
 *     `bg-notify-undeliverable` 收口（绝不产生垃圾文件）。
 */
object BgSessionActivator:

  sealed trait ActivationResult extends Product with Serializable
  case object Activated extends ActivationResult
  final case class Deferred(reason: String) extends ActivationResult
  case object UnknownSession extends ActivationResult

  trait Face:
    def activate(sessionId: String, pendingEvent: AgentCommand.ExternalEvent): IO[ActivationResult]
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  def get: Option[Face] = face.get
end BgSessionActivator

object BgNotifyDelivery:

  private val logger = NebflowLogger.forName("nebflow.bg-notify")

  /** 交付路由裁决（纯函数，spec 判红锚）。 */
  sealed trait Route extends Product with Serializable

  object Route:
    /** 目标活：直投（既有行为）。 */
    case object Direct extends Route

    /** 目标死、激活面可用：激活目标会话本身，通知投给它（作者裁定 09:46）。 */
    case object Revive extends Route

    /** 目标死、激活面不可用（重启窗口）：持久「待补投」标记到目标会话注入队。 */
    case object Persist extends Route

    /** 会话记录不存在（已删）：不可投（审计 + 响亮 WARN，不落盘）。 */
    case object Undeliverable extends Route

    /** 升级面（D3-①）直投成功标签：事件已送达活 root。 */
    final case class Delivered(sessionId: String) extends Route
  end Route

  /**
   * 路由裁决。`sessionKnown` 缺省 true——线上流程里「会话已删」由激活面的
   * `UnknownSession` 判定回填（Undeliverable），纯函数层保留该参数供 spec 判红。
   * `system` 缺席（无 actor 系统 harness）= 无法判活 ⇒ 恒 Direct（旧行为，语义零漂移）。
   */
  def route(targetAlive: Boolean, activatorAvailable: Boolean, sessionKnown: Boolean = true): Route =
    if targetAlive then Route.Direct
    else if !sessionKnown then Route.Undeliverable
    else if activatorAvailable then Route.Revive
    else Route.Persist
  end route

  /** registry 行里的会话 actor 是否活（registry 缺行 / system 缺席 = 不活）。 */
  private def sessionAlive(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    sessionId: String
  ): IO[Option[ActorRef[AgentCommand]]] =
    (system, registry) match
      case (Some(sys), Some(reg)) if sessionId.trim.nonEmpty =>
        reg.get.map(_.get(sessionId)).flatMap {
          case None => IO.pure(None)
          case Some(rec) =>
            sys.isAlive(rec.ref.path).map(alive => if alive then Some(rec.ref) else None)
        }
      case _ => IO.pure(None)
  end sessionAlive

  /**
   * 后台任务完成通知的可靠交付（BashTool.makeNotifyCallback 唯一调用点）。
   * `targetRef` 缺席（REST 直调等无 agent 上下文）= 既有语义零动作。
   */
  def deliverAgentEvent(
    targetRef: Option[ActorRef[AgentCommand]],
    targetSessionId: Option[String],
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    projectName: Option[String],
    flowNodeId: Option[String],
    jobId: String,
    source: String,
    eventType: String,
    payload: String,
    metadata: JsonObject
  ): IO[Unit] =
    targetRef match
      case None => IO.unit
      case Some(ref) =>
        val targetAlive: IO[Boolean] = system match
          // 无 actor 系统 = 无法判活（测试 harness）——按旧侧行为直投，语义零漂移。
          case None => IO.pure(true)
          case Some(sys) => sys.isAlive(ref.path)
        targetAlive.flatMap { tAlive =>
          // 直投腿用 registry 上的**新鲜 ref**（捕获 ref 可能是同路径重生的前代——
          // `!` 投的是旧邮箱，消息蒸发），registry 缺行回落捕获 ref。
          sessionAlive(system, registry, targetSessionId.getOrElse("")).flatMap { freshRef =>
            val deliverableRef = freshRef.getOrElse(ref)
            route(tAlive, activatorAvailable = BgSessionActivator.get.isDefined) match
              case Route.Direct =>
                // ref ! 返回 IO[Unit]——勿包 IO()（双包即在构造期开火）。
                (deliverableRef ! AgentCommand.ExternalEvent(source, eventType, payload, metadata))
                  .handleErrorWith(e =>
                    logger.warn(s"Failed to notify agent for background job $jobId: ${e.getMessage}")
                  )
              case Route.Revive =>
                // 作者裁定：目标死了激活目标自己（不走 root）。会话已删由激活面判
                // UnknownSession 回填——先激活后落盘的次序由面内保证（Activated 契约
                // = 面已持久化 + 激活已发起，spawn 重放交付；见 BgSessionActivator）。
                val mark = markEvent(jobId, targetSessionId, source, eventType, payload, metadata)
                BgSessionActivator.get.fold(
                  persistMark(targetSessionId, mark) *> auditDeferred(
                    projectName,
                    flowNodeId,
                    targetSessionId,
                    jobId,
                    eventType,
                    "activator not wired"
                  )
                ) { face =>
                  face.activate(targetSessionId.getOrElse(""), mark).flatMap {
                    case BgSessionActivator.Activated =>
                      audit(
                        projectName,
                        flowNodeId,
                        targetSessionId,
                        jobId,
                        "bg-notify-activated",
                        s"target session '${targetSessionId.getOrElse("")}' was dead — session ACTIVATED via the persistence-recovery spawn and the completion notification ($eventType) will land in its new turn (jobId $jobId)"
                      )
                    case BgSessionActivator.Deferred(reason) =>
                      persistMark(targetSessionId, mark) *>
                        auditDeferred(projectName, flowNodeId, targetSessionId, jobId, eventType, reason)
                    case BgSessionActivator.UnknownSession =>
                      logger.warn(
                        s"[bg-notify-undelivered] background job $jobId reached '$eventType' but target session " +
                          s"'${targetSessionId.getOrElse("")}' no longer exists — nothing to activate, nothing persisted; " +
                          s"output retained in BgTaskOutputStore (jobId lookup)"
                      ) *>
                        audit(
                          projectName,
                          flowNodeId,
                          targetSessionId,
                          jobId,
                          "bg-notify-undelivered",
                          s"background job $jobId completion ($eventType): target session deleted — notification dropped with audit"
                        )
                  }
                }
              case Route.Persist =>
                // 重启窗口（激活面未装配）：标记落**目标会话自己**的注入队，等它
                // 复活/新回合时 RecoverPersistedQueues 重放补投给它。
                persistMark(targetSessionId, markEvent(jobId, targetSessionId, source, eventType, payload, metadata)) *>
                  auditDeferred(
                    projectName,
                    flowNodeId,
                    targetSessionId,
                    jobId,
                    eventType,
                    "restart window (no activator wired)"
                  )
              case Route.Undeliverable =>
                logger.warn(
                  s"[bg-notify-undelivered] background job $jobId reached '$eventType' but target session " +
                    s"'${targetSessionId.getOrElse("")}' no longer exists — nothing to activate, nothing persisted; " +
                    s"output retained in BgTaskOutputStore (jobId lookup)"
                ) *>
                  audit(
                    projectName,
                    flowNodeId,
                    targetSessionId,
                    jobId,
                    "bg-notify-undelivered",
                    s"background job $jobId completion ($eventType): target session deleted — notification dropped with audit"
                  )
              case Route.Delivered(_) => IO.unit // 仅升级面（deliverToRootOrInbox）返回标签——本交付面不可达
            end match
          }
        }
  end deliverAgentEvent

  // ── 升级投递面（D3-① 消费；作者裁定只改 D1 改道项，此面不动）────────────

  /**
   * 事件驱动升级投递（D3-①）：明确失败/死亡事件投给**最终责任方**——root 会话活
   * 则直投；root 不活（重启窗口 / 已删）则持久收件箱（复活补投）。返回实际走的腿。
   */
  def deliverToRootOrInbox(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    rootSessionId: String,
    event: AgentCommand.ExternalEvent
  ): IO[Route] =
    if rootSessionId.trim.isEmpty then
      logger.warn("[escalate] no root session id — escalation event dropped with this log line").as(Route.Undeliverable)
    else
      sessionAlive(system, registry, rootSessionId).flatMap {
        case Some(liveRef) =>
          (liveRef ! event)
            .handleErrorWith(e => logger.warn(s"[escalate] root delivery failed: ${e.getMessage}"))
            .as(Route.Delivered(rootSessionId))
        case None => BgInboxPort.append(rootSessionId, event).as(Route.Persist)
      }
  end deliverToRootOrInbox

  /**
   * 升级责任方解析：出事会话在 registry 有行 ⇒ 用行上的 rootSessionId（顶层根，
   * 不猜）；无行 ⇒ 回落调用方给的记录面父会话。空 = 无处可投。
   */
  def resolveRootSessionId(
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    subjectSessionId: String,
    fallbackSessionId: String
  ): IO[String] =
    registry match
      case None => IO.pure(fallbackSessionId)
      case Some(reg) =>
        reg.get.map { m =>
          m.get(subjectSessionId).map(_.rootSessionId).filter(_.trim.nonEmpty).getOrElse(fallbackSessionId)
        }
  end resolveRootSessionId

  // ── 内部 ─────────────────────────────────────────────────────────────

  /** 补投标记事件（消费方可辨「这条是目标会话复活补投」）。 */
  private def markEvent(
    jobId: String,
    targetSessionId: Option[String],
    source: String,
    eventType: String,
    payload: String,
    metadata: JsonObject
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = source,
      eventType = eventType,
      payload = payload,
      metadata = metadata.deepMerge(
        JsonObject(
          "bgTaskId" -> jobId.asJson,
          "bgNotifyRedelivered" -> true.asJson,
          "bgNotifyToSession" -> targetSessionId.asJson
        )
      ),
      correlationId = Some(s"bg-redelivery-$jobId")
    )

  /** 持久补投：correlationId 幂等键；落**目标会话**注入队（复活轮重放补投给它）。 */
  private def persistMark(targetSessionId: Option[String], mark: AgentCommand.ExternalEvent): IO[Unit] =
    BgInboxPort.append(targetSessionId.getOrElse(""), mark)

  private def auditDeferred(
    projectName: Option[String],
    flowNodeId: Option[String],
    targetSessionId: Option[String],
    jobId: String,
    eventType: String,
    reason: String
  ): IO[Unit] =
    logger.warn(
      s"[bg-notify-deferred] background job $jobId ($eventType): target session '${targetSessionId.getOrElse("")}' is dead and " +
        s"activation unavailable ($reason) — pending-redelivery marker persisted to the TARGET session's injection queue; " +
        s"replayed to it on revival (output retained in BgTaskOutputStore)"
    ) *>
      audit(
        projectName,
        flowNodeId,
        targetSessionId,
        jobId,
        "bg-notify-deferred",
        s"background job $jobId completion ($eventType): target session '${targetSessionId.getOrElse("")}' dead, activation unavailable ($reason) — redelivery marker persisted for its revival"
      )

  /**
   * 审计腿（`bg-notify-*` 项目事件流行，与 `bg-slow` 同族——只写事件、不改状态）。
   * 依赖方向：core.tools 经 FQN 引 core.project 判据面（auditBgSlow 同款，零新
   * import 边）；project 上下文缺席 / 解析不到 = 静默 no-op（审计缺失不拖交付）。
   */
  private def audit(
    projectName: Option[String],
    flowNodeId: Option[String],
    sessionId: Option[String],
    jobId: String,
    kind: String,
    summary: String
  ): IO[Unit] =
    val node = flowNodeId.map(_.trim).filter(_.nonEmpty)
    projectName.map(_.trim).filter(_.nonEmpty) match
      case None => IO.unit
      case Some(name) =>
        nebflow.core.project.ProjectRuntimeRegistry.get(name).flatMap {
          case None => IO.unit
          case Some(rt) =>
            nebflow.core.project.FlowMapEventLog
              .append(
                rt.project.workspace,
                rt.project.name,
                node.getOrElse(sessionId.getOrElse("")),
                kind,
                summary
              )
              .handleErrorWith(e => logger.warn(s"$kind audit append failed for job $jobId: ${e.getMessage}"))
        }
    end match
  end audit

end BgNotifyDelivery
