package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger

/**
 * Global registry of active background tasks (local Bash + remote).
 * Used by the WS reconnect handler to sync frontend state after disconnects.
 *
 * Tasks are registered when they start and removed when they complete.
 * The frontend queries this on WS reconnect to recover missed completion events.
 *
 * 节点完成闸（bgtask-completion-gate 批，作者 2026-09-05 18:29 裁定）：
 * 本 registry 同时是节点完成闸的等待集权威——NodeEngine 观察桥在 Completed
 * 时查 waitingFor(nodeSessionId)，非空则 hold（不终态化节点）直到全部等待型
 * 后台任务完成回调（ExternalEvent 唤醒续跑轮 → 又一次 Completed → 复检）。
 * - 纳入等待集 =「完成时会向会话返回通知」的任务：显式 run_in_background
 *   （BashTool emitBgTaskStarted）+ 远程后台（RemoteExecutor）。
 * - persistent=true（服务型 server）不纳入（waitingFor 过滤），但仍在册——
 *   前端 WS 重连快照与实时指示器照常可见。
 * - 超时/停滞杀的终局原因记账（markFailed/drainFailures）：节点终态化前检查，
 *   命中 → 节点 failed+注明，禁止静默 completed。
 */
object BgTaskRegistry:
  private val logger = NebflowLogger.forName("nebflow.bg-registry")

  case class ActiveTask(
    jobId: String,
    sessionId: String,
    description: String,
    startedAtMs: Long,
    kind: String, // "local" | "remote"
    /** 顶层根会话 id（2026-09-05 计数/列表分叉修复）：前端 backgroundTaskUpdate
      * 信封与 activeBgTasks 快照共用本键分桶。空 = 无 agent 上下文（REST 直调等）
      * 或旧调用方未传 → activeTasksJson 回退按 sessionId 分组。 */
    rootSessionId: String = "",
    /** 节点完成闸（bgtask-completion-gate 批）：true = 服务型（长驻 server），
      * 不纳入节点等待集（节点完成不等它）；false = 等待型，纳入。默认 false
      * = 等待——绝大多数后台任务是编译/测试/CI，「完成即通知」语义本就要求
      * 等完再交付；server 是少数派且可显式申报。 */
    persistent: Boolean = false,
    /** 来源类别（2026-09-07 后台任务面板重设计，作者指令②）：每任务标注开启
      * 它的节点类别——"nebula" / "dispatcher" / "node"。由 originFor 从注册
      * 会话 id 前缀推导（node-<uuid8> 随机段不含 Flow Map nodeId，故标识取
      * 会话名=节点名，见 originLabel）。 */
    origin: String = "nebula",
    /** 来源显示名：Nebula / dispatcher/<project>（分发器 sessionName 即此
      * 形态）/ Flow Map 节点名（节点 sessionName=nodeName）。空 = 调用方未传
      * → 前端按 origin 类别兜底。 */
    originLabel: String = ""
  )

  /** 节点完成闸：等待集内任务被超时/停滞看护杀掉的终局记账。节点终态化前
    * drainFailures 检查，命中 → failed+注明（拒绝静默 completed）。 */
  case class FailedBgTask(
    jobId: String,
    sessionId: String,
    rootSessionId: String,
    description: String,
    /** 杀因原文（TimeoutException message：idle 超时 / 硬超时+停滞）。 */
    cause: String,
    failedAtMs: Long
  )

  private val tasks: Ref[IO, Map[String, ActiveTask]] = Ref.unsafe(Map.empty)
  private val failures: Ref[IO, Map[String, FailedBgTask]] = Ref.unsafe(Map.empty)

  /** 来源推导（2026-09-07 后台任务面板重设计）：从注册会话 id 前缀 + 会话名
    * 推导 (origin 类别, originLabel 显示名)。node-<uuid8> 的随机段不含 Flow Map
    * nodeId（NodeEngine 生成），节点标识取 sessionName（NodeEngine 注入 = Flow Map
    * 节点名）；dispatcher 的 sessionName 恒为 "dispatcher/<project>"。其余一律
    * nebula（含空 sessionId 的 REST 直调）。 */
  def originFor(sessionId: String, sessionName: Option[String]): (String, String) =
    val name = sessionName.getOrElse("")
    if sessionId.startsWith("node-") then
      ("node", if name.nonEmpty then name else sessionId)
    else if sessionId.startsWith("dispatcher-") then
      ("dispatcher", if name.nonEmpty then name else "dispatcher")
    else ("nebula", "Nebula")

  def register(
    jobId: String,
    sessionId: String,
    description: String,
    kind: String,
    rootSessionId: String = "",
    persistent: Boolean = false,
    origin: String = "nebula",
    originLabel: String = ""
  ): IO[Unit] =
    tasks.update(_ + (jobId -> ActiveTask(jobId, sessionId, description, System.currentTimeMillis(), kind, rootSessionId, persistent, origin, originLabel)))

  def unregister(jobId: String): IO[Unit] =
    tasks.update(_ - jobId)

  /** #391 机制 E：移除某 session 的全部任务并返回它们——restart/Stop 联动时
    * 调用方用返回值发 WS backgroundTaskUpdate(status="cancelled") 通知前端
    * 指示器消失（agent 已死，正常的 completed 通知不会到来）。 */
  def unregisterSession(sessionId: Option[String]): IO[List[ActiveTask]] =
    sessionId match
      case None => IO.pure(Nil)
      case Some(sid) =>
        tasks.modify { m =>
          val (removed, kept) = m.partition(_._2.sessionId == sid)
          (kept, removed.values.toList)
        }

  /** 会话级收殓（孤儿后台任务收割 D1 主钩子）：杀该会话全部 shell 进程树
    * （前台 + 后台 runProcess 注册的 OS 进程）+ 注销 BgTaskRegistry + WS
    * backgroundTaskUpdate(status="cancelled") 帧。
    *
    * 由 NodeEngine 的 failed/cancelled/zombie 终态出口与本引擎 Agent Stop 路径
    * 共用（抽公共函数——对齐 AgentActor.killSessionShellProcesses 的既有语义，
    * 避免两处重复；AgentActor 改调本函数）。
    *
    * 幂等：会话无进程/无任务时 no-op；已杀/已注销时二次调用无副作用。
    * 防误杀边界：只做技术收殓，不裁决进程/任务归属语义（persistent/detached 的
    * 杀否由调用方决策——本函数对在册任务一律注销）。
    *
    * sessionId 为 None 时 no-op（对齐 unregisterSession/killSessionProcesses）。
    */
  def reclaimSession(
    sessionId: Option[String],
    wsSend: Json => IO[Unit],
    rootSessionId: String
  ): IO[Unit] =
    ShellSession.killSessionProcesses(sessionId) *>
      unregisterSession(sessionId).flatMap { removed =>
        removed.traverse_ { t =>
          wsSend(
            Json.obj(
              "type" -> "backgroundTaskUpdate".asJson,
              "sessionId" -> sessionId.asJson,
              // 权威分键（2026-09-05 计数/列表分叉修复）：与 BashTool/RemoteExecutor
              // 发射点一致携带 rootSessionId，收割帧按根会话分桶直达归属视图
              // （前端已删 bgTaskRootFor 启发式逆向分键）。
              "rootSessionId" -> rootSessionId.asJson,
              "taskId" -> t.jobId.asJson,
              "description" -> t.description.asJson,
              "status" -> "cancelled".asJson
            )
          ).handleErrorWith(_ => IO.unit)
        }
      }

  /** 节点完成闸等待集查询：某会话名下的活动等待型任务 = 自有任务（sessionId
    * 相等）+ 以该会话为根的子代理任务（rootSessionId 相等，命名空间不相交：
    * node-<uuid> 只作为节点自身 sessionId 与其子代理的 rootSessionId 出现）。
    * persistent=true（服务型）过滤不等待。 */
  def waitingFor(sessionId: String): IO[List[ActiveTask]] =
    tasks.get.map { m =>
      m.values
        .filter(t => !t.persistent && (t.sessionId == sessionId || t.rootSessionId == sessionId))
        .toList
    }

  /** 热重启 quiesce（F5 域，hot-restart 批）：全部活动等待型（非 persistent）后台
    * 任务——等待型任务无持久面（进程内 Ref），重启即亡，故必须纳入空闲判定。 */
  def waitingTasks: IO[List[ActiveTask]] =
    tasks.get.map(_.values.filter(!_.persistent).toList)

  /** 节点完成闸终局记账：等待型任务被超时/停滞看护杀掉时登记（BashTool 完成回调
    * Left(TimeoutException) 分支调用；persistent 与显式取消不入账——前者不在等待集，
    * 后者是 agent 自主决策）。 */
  def markFailed(jobId: String, sessionId: String, rootSessionId: String, description: String, cause: String): IO[Unit] =
    failures.update(_ + (jobId -> FailedBgTask(jobId, sessionId, rootSessionId, description, cause, System.currentTimeMillis())))

  /** 节点终态化前检查（NodeEngine 桥）：取走并清空该会话名下的失败记账
    * （匹配规则同 waitingFor）。consume-on-read：节点恰好终态化一次。 */
  def drainFailures(sessionId: String): IO[List[FailedBgTask]] =
    failures.modify { m =>
      val (hit, kept) = m.partition { case (_, f) => f.sessionId == sessionId || f.rootSessionId == sessionId }
      (kept, hit.values.toList)
    }

  /** Returns active tasks grouped by root session id (rootSessionId, falling
    * back to sessionId when absent), as JSON for the frontend. */
  def activeTasksJson: IO[io.circe.Json] =
    tasks.get
      .map { m =>
        m.values
          .groupBy(t => if t.rootSessionId.nonEmpty then t.rootSessionId else t.sessionId)
          .map { case (sid, taskSet) =>
            sid -> taskSet.map { t =>
              io.circe.Json.obj(
                "taskId" -> t.jobId.asJson,
                "description" -> t.description.asJson,
                "status" -> "running".asJson,
                "startedAt" -> t.startedAtMs.asJson,
                // 2026-09-07 后台任务面板重设计：快照补 kind（local/remote）与
                // 来源字段（origin 类别 + originLabel 显示名）——前端来源 chip
                // 与类别样式在刷新恢复路径同样可渲染。
                "kind" -> t.kind.asJson,
                "origin" -> t.origin.asJson,
                "originLabel" -> t.originLabel.asJson
              )
            }.toList
          }
          .toMap
      }
      .map(_.asJson)
end BgTaskRegistry
