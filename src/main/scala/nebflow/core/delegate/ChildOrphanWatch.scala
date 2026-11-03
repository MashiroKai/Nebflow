package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, AgentEvent, AgentKind, AgentRecord}
import nebflow.core.AgentRuntimePort
import nebflow.shared.NebflowLogger

/**
 * ChildOrphanWatch —— 孤儿子会话检测与处置（引擎会话治理专项 · 缺陷七 ②，与缺陷六
 * 「孤儿链」同族：都是**父子账本不一致**的形态）。
 *
 * 判据（在案事实，不做推断）：父会话已有**取消墓碑**（`DelegateRegistry.cancelRequestedAt`）
 * 或**归档旗**（`archivedAt`），而子会话（`parentSessionId == 父`）仍在统一注册表里
 * 活着 ⇒ 孤儿。作者报障的实证形态 = AgentControl cancel 父之后子仍 Processing 继续
 * 往工作树写东西。
 *
 * 处置（配置 `nebflow.session.orphan.action`）：
 *   - `cancel`（默认，与作者「子会话也应该一并关闭」逐字一致）：墓碑 + supervisor
 *     `Cancelled`（无 supervisor ⇒ 降级 Stop + 注册表移除）+ 任务行终态化 ——
 *     面板不再显示「运行中」（「孤儿态」的落地形态：活体行消失，审计面留
 *     `session-orphan` 行可查）。
 *   - `report`：只写审计行 + 通知根会话（保留现场供人工判断——用于正在排查的实例；
 *     默认关闭）。
 *
 * 调度：挂既有 [[nebflow.core.processor.TaskStuckWatcher.scan]] 拍尾（零新增定时器
 * 纪律）。扫描只对「父墓碑/归档在案」的子记录动手，绝不按「父不在注册表」判孤儿
 * ——后者在启动窗口与跨域根会话上是误报源（会把正常子会话误杀）。
 *
 * 测试面：[[Port]] 把注册表/任务行/通知三件事收窄成端口，机械断言用假端口驱动，
 * 不碰真 ActorSystem。
 */
object ChildOrphanWatch:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.child-orphan-watch")

  /** ChainStore 行种类（审计面）。 */
  val EntryKind: String = "session-orphan"

  /** 发起方标签（墓碑 cancelRequestedBy 的观测值）。 */
  val By: String = "child-orphan-watch"

  /** 处置动作：`cancel`（默认）| `report`。 */
  def action: String =
    sys.props.getOrElse("nebflow.session.orphan.action", "cancel").trim.toLowerCase match
      case "report" => "report"
      case _        => "cancel"

  /** 端口（生产 = [[fromResources]]；测试 = 假端口）。 */
  trait Port:
    def registry: IO[Map[String, AgentRecord]]
    def tombstonedParents: IO[Set[String]]
    def cancelChild(rec: AgentRecord, reason: String): IO[Unit]
    def settleTaskRow(rec: AgentRecord, reason: String): IO[Unit]
    def notifyRoot(rec: AgentRecord, text: String): IO[Unit]
    /** 审计行（生产写 ChainStore；测试可空实现）。 */
    def audit(values: Map[String, String]): IO[Unit] = IO.unit

  /** 生产端口（AgentRuntimePort 装配；与既有 registry/task store/Notify 通道同源）。 */
  def fromResources(resources: AgentRuntimePort): Port = new Port:
    def registry = resources.agentRegistry.get
    def tombstonedParents =
      IO.blocking(CancelCascade.terminalParents(DelegateRegistry.list()))
        .handleErrorWith(e => logger.warn(s"child-orphan-watch: delegate registry read failed: ${e.getMessage}").as(Set.empty[String]))
    def cancelChild(rec: AgentRecord, reason: String) =
      IO.blocking(DelegateRegistry.markCancelRequested(rec.sessionId, By)).attempt.void *>
        (rec.supervisorRef match
          case Some(sup) => (sup ! AgentEvent.Cancelled(rec.sessionId, reason)).void
          case None =>
            resources.agentRegistry.update(_ - rec.sessionId) *> (rec.ref ! AgentCommand.Stop(reason))
        )
    def settleTaskRow(rec: AgentRecord, reason: String) =
      if rec.kind != AgentKind.Ephemeral && rec.parentSessionId.nonEmpty then
        resources.subAgentTaskStore
          .updateStatus(
            rec.parentSessionId,
            rec.sessionId,
            "cancelled",
            completedAt = Some(System.currentTimeMillis()),
            lastError = Some(reason.take(200))
          )
          .handleErrorWith(e => logger.warn(s"child-orphan-watch: task row settle failed: ${e.getMessage}"))
      else IO.unit
    def notifyRoot(rec: AgentRecord, text: String) =
      if rec.rootSessionId.isEmpty then IO.unit
      else
        DelegateNotify
          .inject(resources.agentRegistry, rec.rootSessionId, text)
          .flatMap {
            case true  => IO.unit
            case false => logger.info(s"child-orphan-watch: root '${rec.rootSessionId}' not live — notice not injected")
          }
          .handleErrorWith(e => logger.warn(s"child-orphan-watch: root notify failed: ${e.getMessage}"))
    override def audit(values: Map[String, String]) =
      IO.blocking(
        ChainStore.appendSync(
          ChainStore.keyFor(None, values.getOrElse("sessionId", "orphan")),
          EntryKind,
          Json.fromFields(values.toList.map((k, v) => k -> v.asJson))
        )
      ).handleErrorWith(e => logger.warn(s"child-orphan-watch: audit row failed: ${e.getMessage}"))

  /** 生产入口（挂 TaskStuckWatcher 拍尾）。 */
  def scan(resources: AgentRuntimePort): IO[List[CancelCascade.Step]] = scan(fromResources(resources))

  /** 单拍：检出孤儿并按配置处置；返回逐条处置结果（空 = 无孤儿）。 */
  def scan(port: Port): IO[List[CancelCascade.Step]] =
    for
      reg <- port.registry
      parents <- port.tombstonedParents
      orphans = CancelCascade.detectOrphans(reg, parents)
      _ <-
        if orphans.isEmpty then IO.unit
        else
          logger.warn(
            s"child-orphan-watch: ${orphans.size} orphan child session(s) — " +
              orphans.map(o => s"${o.child.sessionId}(parent=${o.parentId})").mkString(", ") +
              s" [action=${action}]"
          )
      out <- orphans.traverse(handle(port, _))
    yield out

  /** 单条处置（幂等：墓碑零重写；注册表已无该行 = already-terminal）。 */
  def handle(port: Port, orphan: CancelCascade.Orphan): IO[CancelCascade.Step] =
    val child = orphan.child
    val reason = s"orphaned: ${orphan.cause}"
    port.registry.flatMap { reg =>
      if !reg.contains(child.sessionId) then
        IO.pure(
          CancelCascade.Step(
            child.sessionId,
            child.kind.toString,
            child.status.toString,
            CancelCascade.Outcome.AlreadyTerminal,
            "registry entry already gone"
          )
        )
      else
        val auditRow = port.audit(
          Map(
            "sessionId" -> child.sessionId,
            "parentSessionId" -> orphan.parentId,
            "kind" -> child.kind.toString,
            "status" -> child.status.toString,
            "cause" -> orphan.cause,
            "action" -> action
          )
        )
        auditRow *> port
          .notifyRoot(
            child,
            s"""[orphan-child] session '${child.sessionId}' (${child.kind}) is still running although its parent session '${orphan.parentId}' is terminal — ${orphan.cause}. Disposition: ${if action == "report" then "reported only (nebflow.session.orphan.action=report)" else "cancelled by the engine (tombstone + stop, task row settled)"}."""
          )
          .handleErrorWith(e => logger.warn(s"child-orphan-watch: root notify failed: ${e.getMessage}")) *>
          (if action == "report" then
             IO.pure(
               CancelCascade.Step(child.sessionId, child.kind.toString, child.status.toString, "reported", reason)
             )
           else
             (port.cancelChild(child, reason) *> port.settleTaskRow(child, reason)).as(
               CancelCascade.Step(child.sessionId, child.kind.toString, child.status.toString, CancelCascade.Outcome.Killed, reason.take(200))
             )
          ).handleErrorWith(e =>
            IO.pure(
              CancelCascade.Step(
                child.sessionId,
                child.kind.toString,
                child.status.toString,
                CancelCascade.Outcome.Refused,
                Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(200)
              )
            )
          )
    }

end ChildOrphanWatch
