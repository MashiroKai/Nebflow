package nebflow.core.tools

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentRecord}
import nebflow.shared.NebflowLogger

import java.util.concurrent.atomic.AtomicReference

/**
 * D1 后台任务通知投递可靠性（steward 批 2026-10-23）：交付不再盲投「创建时焊死的
 * 会话 ActorRef」。旧形态 = `ref ! ExternalEvent` 直投 + 失败 WARN 吞掉——但
 * `ref !` 是队列 offer，**从不失败**：目标会话 actor 已退出时消息进无人消费的
 * 队列静默蒸发（bg-wait cap 强制收尾后同样投尸）。本件把交付升级为**活性判定 +
 * 改道 + 持久补投**三段：
 *   - 目标活 ⇒ 直投（既有行为，字节不变）；
 *   - 目标死、root 活 ⇒ **改道投 root**（活的最终责任方），metadata 标注改道事实；
 *   - root 也不活（重启窗口 / 已删）⇒ **持久「待补投」标记**：事件写入 root 会话的
 *     持久注入队（[[BgInboxPort]]，correlationId 幂等）——会话复活 / 新回合时由
 *     既有 RecoverPersistedQueues 重放（BackoffSupervisor f2DeliveryBackstop 同
 *     机制）；输出本体照旧由 `BgTaskOutputStore.finalizeTask` 落盘，标记只补通知。
 *   - 「WARN 吞掉」静默路径消灭：改道/补投/不可投三态全部落审计（项目事件流
 *     `bg-notify-*` 行）+ 响亮日志。
 *
 * 分层纪律：持久注入队的实现在 agent 层（CompactionQueueStore），core.tools 经
 * [[BgInboxPort]] M2 注册器消费（DelegateJudgePort / SendConfirmPort 同款形态）；
 * 注册点 = agent.SharedResources 构造行，未注册兜底 = 响亮 WARN + no-op（不炸
 * 调用链——投递兜底绝不拖垮任务终态三清）。
 */
object BgInboxPort:

  /** 持久注入队 append 面（幂等；会话已删 = no-op——不产生无人读的垃圾文件）。 */
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

object BgNotifyDelivery:

  private val logger = NebflowLogger.forName("nebflow.bg-notify")

  /** 交付路由裁决（纯函数，spec 判红锚）。 */
  sealed trait Route extends Product with Serializable
  object Route:
    /** 目标活：直投（既有行为）。 */
    case object Direct extends Route

    /** 目标死、root 活：改道投 root。 */
    final case class Reroot(rootSessionId: String) extends Route

    /** 目标死、root 也不活：持久补投（root 注入队文件）。 */
    final case class Persist(rootSessionId: String) extends Route

    /** 无 root 可归（无会话上下文）：不可投（审计 + 响亮 WARN）。 */
    case object Undeliverable extends Route
  end Route

  /**
   * 路由裁决。`rootAlive` 只在 root≠target 时才参与（root==target 的死会话没有
   * 「改道投自己」的语义 ⇒ 直落持久补投）。`system` 缺席（无 actor 系统 harness）
   * = 无法判活 ⇒ 恒 Direct（旧行为，测试语义零漂移）。
   */
  def route(
    targetAlive: Boolean,
    targetSessionId: Option[String],
    rootSessionId: Option[String],
    rootAlive: Boolean
  ): Route =
    if targetAlive then Route.Direct
    else
      val root = rootSessionId.map(_.trim).filter(_.nonEmpty)
      val sameAsTarget = root.exists(r => targetSessionId.exists(_.trim == r))
      root match
        case Some(r) if !sameAsTarget && rootAlive => Route.Reroot(r)
        case Some(r) => Route.Persist(r)
        case None => Route.Undeliverable
  end route

  /** registry 行里的会话 actor 是否活（registry 缺行 / system 缺席 = 不活）。 */
  private def sessionAlive(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    sessionId: String
  ): IO[Boolean] =
    (system, registry) match
      case (Some(sys), Some(reg)) if sessionId.trim.nonEmpty =>
        reg.get.map(_.get(sessionId)).flatMap {
          case None => IO.pure(false)
          case Some(rec) => sys.isAlive(rec.ref.path)
        }
      case _ => IO.pure(false)
  end sessionAlive

  /**
   * 后台任务完成通知的可靠交付（BashTool.makeNotifyCallback 唯一调用点）。
   * `targetRef` 缺席（REST 直调等无 agent 上下文）= 既有语义零动作。
   */
  def deliverAgentEvent(
    targetRef: Option[ActorRef[AgentCommand]],
    targetSessionId: Option[String],
    rootSessionId: Option[String],
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
          val rootId = rootSessionId.map(_.trim).filter(_.nonEmpty).orElse(
            targetSessionId.map(_.trim).filter(_.nonEmpty)
          )
          sessionAlive(system, registry, rootId.getOrElse("")).flatMap { rAlive =>
            route(tAlive, targetSessionId, rootId, rAlive) match
              case Route.Direct =>
                // ref ! 返回 IO[Unit]——勿包 IO()（双包即在构造期开火）。
                (ref ! AgentCommand.ExternalEvent(source, eventType, payload, metadata))
                  .handleErrorWith(e => logger.warn(s"Failed to notify agent for background job $jobId: ${e.getMessage}"))
              case Route.Reroot(root) =>
                deliverToSession(system, registry, root, rerootEvent(jobId, targetSessionId, source, eventType, payload, metadata))
                  .flatMap {
                    case true =>
                      audit(projectName, flowNodeId, targetSessionId, jobId, "bg-notify-rerouted",
                        s"target session '${targetSessionId.getOrElse("")}' dead — completion notification rerouted to live root session '$root'")
                    case false =>
                      persistMark(root, jobId, targetSessionId, source, eventType, payload, metadata) *>
                        audit(projectName, flowNodeId, targetSessionId, jobId, "bg-notify-deferred",
                          s"target dead and root '$root' vanished between checks — notification persisted for redelivery (output retained in BgTaskOutputStore)")
                  }
              case Route.Persist(root) =>
                persistMark(root, jobId, targetSessionId, source, eventType, payload, metadata) *>
                  audit(projectName, flowNodeId, targetSessionId, jobId, "bg-notify-deferred",
                    s"target session '${targetSessionId.getOrElse("")}' dead and root '$root' not live — notification persisted for redelivery on revival (output retained in BgTaskOutputStore)")
              case Route.Undeliverable =>
                logger.warn(
                  s"[bg-notify-undelivered] background job $jobId reached '$eventType' but has NO deliverable session " +
                    s"(target '${targetSessionId.getOrElse("")}' dead, no root session context) — audit event emitted; " +
                    s"output retained in BgTaskOutputStore (jobId lookup)"
                ) *>
                  audit(projectName, flowNodeId, targetSessionId, jobId, "bg-notify-undelivered",
                    s"background job $jobId completion ($eventType) had no deliverable session — output retained, notification dropped with audit")
            }
        }
  end deliverAgentEvent

  // ── 升级投递面（D3-① 消费）──────────────────────────────────────────

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
        case true =>
          registry match
            case Some(reg) =>
              reg.get.map(_.get(rootSessionId)).flatMap {
                case Some(rec) =>
                  (rec.ref ! event)
                    .handleErrorWith(e => logger.warn(s"[escalate] root delivery failed: ${e.getMessage}"))
                    .as(Route.Reroot(rootSessionId))
                case None => BgInboxPort.append(rootSessionId, event).as(Route.Persist(rootSessionId))
              }
            case None => BgInboxPort.append(rootSessionId, event).as(Route.Persist(rootSessionId))
        case false => BgInboxPort.append(rootSessionId, event).as(Route.Persist(rootSessionId))
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

  /** 改道事件的标注（消费方可辨「这条是改道来的」）。 */
  private def rerootEvent(
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
          "bgNotifyRerouted" -> true.asJson,
          "bgNotifyFromSession" -> targetSessionId.asJson
        )
      )
    )

  /** 持久补投标记：correlationId 幂等键 + 补投标注（复活轮 RecoverPersistedQueues 重放）。 */
  private def persistMark(
    rootSessionId: String,
    jobId: String,
    targetSessionId: Option[String],
    source: String,
    eventType: String,
    payload: String,
    metadata: JsonObject
  ): IO[Unit] =
    BgInboxPort.append(
      rootSessionId,
      AgentCommand.ExternalEvent(
        source = source,
        eventType = eventType,
        payload = payload,
        metadata = metadata.deepMerge(
          JsonObject(
            "bgTaskId" -> jobId.asJson,
            "bgNotifyDeferred" -> true.asJson,
            "bgNotifyFromSession" -> targetSessionId.asJson
          )
        ),
        correlationId = Some(s"bg-redelivery-$jobId")
      )
    )

  private def deliverToSession(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    sessionId: String,
    event: AgentCommand.ExternalEvent
  ): IO[Boolean] =
    sessionAlive(system, registry, sessionId).flatMap {
      case false => IO.pure(false)
      case true =>
        registry match
          case Some(reg) =>
            reg.get.map(_.get(sessionId)).flatMap {
              case None => IO.pure(false)
              case Some(rec) => (rec.ref ! event).as(true)
            }
          case None => IO.pure(false)
    }

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
  end audit

end BgNotifyDelivery
