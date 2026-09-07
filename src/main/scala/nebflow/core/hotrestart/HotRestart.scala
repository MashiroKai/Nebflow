package nebflow.core.hotrestart

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.{AgentStatus, SharedResources}
import nebflow.core.project.{NodeLifecycle, ProjectRuntimeRegistry}
import nebflow.core.tools.BgTaskRegistry
import nebflow.core.{AutoStartService, NebflowLogger, PathUtil, RestartHelper}
import nebflow.llm.LlmInterface

import java.io.File
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** 触发器无关的热重启编排器（hot-restart 批，设计 .nebflow/Spec/20260907_hot-restart-design.md）。
  *
  * 一次热重启的编排（旧实例视角，设计 §3.1 [1]-[8]）：
  *  [1] 在飞五域判定（F1 running 节点 / F2 delegate-subtask / F3 活跃 turn / F4 在飞
  *      LLM / F5 等待型 bg 任务）——忙则拒绝（RejectIfBusy）或排队（WaitIdle）
  *  [2] draining 置位（工作准入闸关闭——WS 用户文本 / TriggerDispatcher 在单一
  *      choke 点拒绝，漏网兜底 = 崩溃恢复 sweep）
  *  [3] 状态落盘核验（既有持久面 flush：会话 UI/消息 2s debounce 清空；其余
  *      flow-map/results/tasks 均为 write-through 已持久，零动作）
  *  [4] 写 intent.json（唯一新盘上产物）
  *  [5] spawn 后继（env 主保险 + argv belt；spawn 异常零损失上报——C1 语义前置）
  *  [6] C1：2s grace 验后继存活——死则中止，旧实例继续服务（「先确认后继活着，
  *      自己才许死」，设计原则 2）
  *  [7] C2：轮询 intent phase=readyToBind（后继 Zone A 引导回执），deadline 默认 30s
  *      ——超时 TERM 自家未 bind 后继（唯一新增杀动作：自己 spawn、自己持
  *      ProcessHandle、未持任何资源的子进程，不属 StaleProcessGuard 管辖面，不违反
  *      「禁裸 kill 自身」），中止重启
  *  [8] 触发优雅关停：complete gatewayShutdown Deferred → GatewayMain 的 waitForQuit
  *      race 收敛 → use 块完成 → Ember 优雅 stop（对已建立连接发 FIN）→ .guarantee
  *      链（daemon/LLM abort/logout/mcp/release）→ JVM 自然退出——替代
  *      System.exit(0)（消除非优雅退 / TIME_WAIT 生成 / 固定 sleep 盲等三缺陷）。
  *
  * 全程任何一步失败 → 中止：清 draining、failed 上报（WS 帧 + ERROR 日志 + intent
  * failure 记录）、旧实例继续服务、零停机。成功路径后 draining 保持置位（旧实例
  * 即将退出，不再收新工作）；中止路径一律恢复准入。
  *
  * 进程内全局状态（伴随对象，BgTaskRegistry 先例）：draining 准入闸被 WebSocketRoutes
  * 与 ProjectActor 在无实例引用处读取，故放全局；cooldown / in-progress 合并同理。
  */
class HotRestart(
  resources: SharedResources,
  port: Int,
  host: String,
  /** 进度广播（GatewayMain 接 wsHub.broadcast——restartStatus 帧到所有 WS 连接）。 */
  broadcast: Json => IO[Unit],
  timing: HotRestart.Timing = HotRestart.Timing.fromSystemProps(),
  /** spawn 注入点（验收 4：注入 spawn 故障；测试注入 fake）。默认 = 真实 ProcessBuilder。 */
  spawn: HotRestartIntent => IO[Either[String, HotRestart.SpawnedProcess]] = HotRestart.defaultSpawn
):
  private val logger = NebflowLogger.forName("nebflow.hotrestart")
  private def intentFile: os.Path = SuccessorGate.intentPath(PathUtil.dataRoot)

  // ===== [1] 在飞五域判定（设计 §3.4 / §1.5 权威源）=====

  /** 五域空闲判定。域读取失败 = 无法证明空闲 → 按忙处理（fail-safe，错误文本入明细）。 */
  def quiesceReport: IO[QuiesceReport] =
    val f1: IO[List[String]] = ProjectRuntimeRegistry.all
      .flatMap { rts =>
        rts.traverse { rt =>
          rt.store.snapshot
            .map(s =>
              s.nodes.values
                .filter(_.status == NodeLifecycle.Running)
                .map(n => s"${rt.project.name}/${n.name}")
                .toList)
            .handleErrorWith(e => IO.pure(List(s"${rt.project.name}/<flow-map read error: ${e.getMessage}>")))
        }.map(_.flatten)
      }
      .handleErrorWith(e => IO.pure(List(s"<project registry read error: ${e.getMessage}>")))
    val f2: IO[List[String]] = resources.subAgentTaskStore.findRunningTasks
      .map(_.map(t => s"${t.source}:${t.taskId}@${t.parentSessionId}"))
      .handleErrorWith(e => IO.pure(List(s"<subtask store read error: ${e.getMessage}>")))
    val f3: IO[List[String]] = resources.agentRegistry.get
      .map(_.values.filter(_.status == AgentStatus.Processing).map(r => r.sessionId).toList)
      .handleErrorWith(e => IO.pure(List(s"<agent registry read error: ${e.getMessage}>")))
    val f4: IO[Int] = LlmInterface.inflightCount.handleErrorWith(_ => IO.pure(Int.MaxValue))
    val f5: IO[List[String]] = BgTaskRegistry.waitingTasks
      .map(_.map(t => s"${t.jobId}:${t.description.take(60)}"))
      .handleErrorWith(e => IO.pure(List(s"<bg registry read error: ${e.getMessage}>")))
    (f1, f2, f3, f4, f5).mapN(QuiesceReport.apply)

  // ===== 请求入口（触发源解耦：Web UI / 后续 REST / 桌面菜单同走此门）=====

  /** 请求热重启。同步拒绝（禁用/冷却/忙且 RejectIfBusy）→ Left（触发面立即回报）；
    * 受理 → Right 且编排 fiber fork（进度经 restartStatus 帧广播）。进行中请求幂等
    * 合并（R7 重启风暴护栏的第二半）。 */
  def requestRestart(source: String, mode: RestartMode): IO[Either[String, Unit]] =
    if HotRestart.isDisabled then
      IO.pure(Left("hot restart is disabled (nebflow.hotRestart.enabled=false)"))
    else
      HotRestart.inProgressRef.modify { cur =>
        if cur then (cur, false) // 已有重启在途——幂等合并（占位不动）
        else (true, true)
      }.flatMap { claimed =>
        if !claimed then IO.pure(Right(())) // 合并进在途重启
        else
          HotRestart.lastCompletedAtRef.get.flatMap { lastCompleted =>
            val since = System.currentTimeMillis() - lastCompleted
            if lastCompleted > 0 && since < timing.cooldownMs then
              // 冷却窗（R7）：放弃本次占位，准许后续请求
              HotRestart.inProgressRef.set(false) *>
                IO.pure(
                  Left(
                    s"restart cooldown: only ${(timing.cooldownMs - since) / 1000}s since the last restart (window ${timing.cooldownMs / 1000}s)"))
            else
              quiesceReport.flatMap { q =>
                if q.isIdle then begin(source).start.as(Right(()))
                else
                  mode match
                    case RestartMode.RejectIfBusy =>
                      // 放弃占位 + 五域明细立即回报（验收 2）
                      HotRestart.inProgressRef.set(false) *>
                        IO.pure(Left(s"busy — ${q.detail}"))
                    case RestartMode.WaitIdle(timeoutMs) =>
                      // 等待期 draining 不置位（工作照常准入，每轮重判，设计 §3.4）
                      waitThenBegin(source, timeoutMs, q).start.as(Right(()))
              }
          }
      }

  /** WaitIdle：每 waitIdlePollMs 复查五域，上限 timeoutMs——超时放弃 + 上报明细 +
    * 释放占位（验收 3 第二 case）；转空闲 → begin。 */
  private def waitThenBegin(source: String, timeoutMs: Long, initial: QuiesceReport): IO[Unit] =
    val deadline = System.currentTimeMillis() + timeoutMs
    broadcast(statusFrame(
      "quiesce",
      s"waiting for in-flight work to finish (up to ${timeoutMs / 1000}s): ${initial.detail}")) *>
      logger.info(s"[hot-restart] WaitIdle queued (source=$source, timeout=${timeoutMs}ms): ${initial.detail}") *>
      pollIdle(deadline).flatMap {
        case Left(q) =>
          HotRestart.inProgressRef.set(false) *>
            logger.warn(s"[hot-restart] WaitIdle timeout after ${timeoutMs}ms — still busy: ${q.detail}") *>
            broadcast(statusFrame("failed", s"restart abandoned: still busy after ${timeoutMs / 1000}s — ${q.detail}"))
        case Right(()) => begin(source).void
      }

  private def pollIdle(deadline: Long): IO[Either[QuiesceReport, Unit]] =
    quiesceReport.flatMap {
      case q if q.isIdle => IO.pure(Right(()))
      case q if System.currentTimeMillis() > deadline => IO.pure(Left(q))
      case _ => IO.sleep(timing.waitIdlePollMs.millis) *> pollIdle(deadline)
    }

  // ===== [2]-[8] 重启编排（begin 起在 fork fiber 上跑）=====

  private def begin(source: String): IO[Either[String, Unit]] =
    broadcast(statusFrame("draining", "in-flight work settled — closing work admission")) *>
      logger.info(s"[hot-restart] draining on (source=$source) — work admission closed") *>
      HotRestart.drainingRef.set(true) *>
      drainLoop.flatMap {
        case Left(err) => abort(None, err).as(Left(err))
        case Right(()) =>
          // [3] 状态落盘核验：既有持久面 flush（2s debounce 尾巴清零）。
          //     flow-map/results/tasks/F2 队列/freeze skip/配置均为 write-through
          //     或变更即持久——零动作（设计 §3.4 落盘清单）。
          resources.sessionStore.flushPendingUiWrites *>
            resources.sessionStore.flushPendingMessages *>
            logger.info("[hot-restart] state flushed (dirty session UI + message writes drained)") *>
            // [4] intent write-ahead
            writeIntent(source).flatMap {
              case Left(err) => abort(None, err).as(Left(err))
              case Right(intent) =>
                broadcast(statusFrame("spawning", "successor process launching")) *>
                  // [5] spawn（env 主保险 + argv belt；失败零损失——C1 语义前移到 spawn 异常）
                  spawn(intent)
                    .handleErrorWith(e => IO.pure(Left(s"spawn exception: ${Option(e.getMessage).getOrElse(e.toString)}")))
                    .flatMap {
                      case Left(err) => abort(Some(intent), err).as(Left(err))
                      case Right(proc) =>
                        // [6] C1：spawn 存活闸（「才许死」）
                        IO.sleep(timing.c1GraceMs.millis) *>
                          proc.isAlive.flatMap { alive =>
                            if !alive then
                              abort(Some(intent), s"C1: successor (pid ${proc.pid}) died within ${timing.c1GraceMs}ms of spawn")
                                .as(Left("successor died immediately"))
                            else
                              logger.info(
                                s"[hot-restart] C1 ok (successor pid ${proc.pid} alive) — waiting for readyToBind") *>
                                // [7] C2：后继到门口闸
                                pollReadyToBind(proc).flatMap {
                                  case Left(err) =>
                                    // C2 超时/后继中途死亡 → TERM 自家后继（未 bind 未持资源）
                                    proc.destroy.attempt.void *>
                                      abort(Some(intent), err).as(Left(err))
                                  case Right(()) =>
                                    // [8] 优雅让渡：complete Deferred → waitForQuit race 收敛
                                    //     → Ember 优雅 stop → .guarantee 链 → JVM 自然退出。
                                    //     draining 保持置位（旧实例不再收新工作，即将退出）。
                                    broadcast(statusFrame("handing-over",
                                      "successor at the port — old instance handing over now")) *>
                                      logger.info(
                                        s"[hot-restart] handover: graceful shutdown triggered (successor pid ${proc.pid} ready; old pid ${intent.oldPid} exiting via the graceful chain)") *>
                                      resources.gatewayShutdown.complete(()).attempt.void *>
                                      IO.pure(Right(()))
                                }
                          }
                    }
            }
      }

  /** draining 收敛环：准入闸关闭后，等在飞工作自然终局 + 15s 稳定观察窗（restart.sh
    * observe_idle 经验移植——阻尼「判定期间新工作进来」的振荡；闸已关，新工作进不来，
    * 这里只等存量终局）。总期限 drainDeadlineMs（fail-safe：收敛失败 = 中止重启，
    * 旧实例继续服务——不静默等待、不强制，设计 §3.4）。 */
  private def drainLoop: IO[Either[String, Unit]] =
    val deadline = System.currentTimeMillis() + timing.drainDeadlineMs
    def loop: IO[Either[String, Unit]] =
      quiesceReport.flatMap { q =>
        val now = System.currentTimeMillis()
        if !q.isIdle then
          if now > deadline then
            IO.pure(Left(s"drain deadline (${timing.drainDeadlineMs / 1000}s) exceeded, still busy: ${q.detail}"))
          else IO.sleep(timing.drainPollMs.millis) *> loop
        else
          IO.sleep(timing.observeWindowMs.millis) *> quiesceReport.flatMap { q2 =>
            val now2 = System.currentTimeMillis()
            if q2.isIdle then IO.pure(Right(()))
            else if now2 > deadline then
              IO.pure(
                Left(s"drain deadline (${timing.drainDeadlineMs / 1000}s) exceeded during observation window: ${q2.detail}"))
            else loop
          }
      }
    loop

  /** C2：轮询 intent phase=readyToBind（后继 Zone A 完成回执），c2DeadlineMs 上限。
    * 后继中途死亡也算超时分支（destroy 对尸体是 no-op）。 */
  private def pollReadyToBind(proc: HotRestart.SpawnedProcess): IO[Either[String, Unit]] =
    val deadline = System.currentTimeMillis() + timing.c2DeadlineMs
    def loop: IO[Either[String, Unit]] =
      SuccessorGate.readIntent(intentFile).flatMap {
        case Some(i) if i.phase == "readyToBind" => IO.pure(Right(()))
        case _ =>
          proc.isAlive.flatMap { alive =>
            if !alive then IO.pure(Left("C2: successor died before readyToBind"))
            else if System.currentTimeMillis() > deadline then
              IO.pure(Left(s"C2: successor not readyToBind within ${timing.c2DeadlineMs}ms — TERM own successor and abort"))
            else IO.sleep(250.millis) *> loop
          }
      }
    loop

  /** [4] intent 落盘。写失败 = 中止（fail-safe 保持服务——没有 intent 就没有后继）。 */
  private def writeIntent(source: String): IO[Either[String, HotRestartIntent]] =
    val now = System.currentTimeMillis()
    val form = HotRestart.detectForm
    val cmdPreview = HotRestart.buildCommand(
      form,
      HotRestart.javaBinResolver(),
      AutoStartService.resolveRunJar(),
      intentFile.toString,
      PathUtil.dataRoot.toString,
      port
    ) match
      case Right(cmd) => cmd.mkString(" ")
      case Left(msg)  => msg
    val intent = HotRestartIntent(
      generation = now,
      oldPid = ProcessHandle.current.pid,
      host = host,
      port = port,
      home = PathUtil.dataRoot.toString,
      form = form,
      spawnCmd = cmdPreview,
      triggerSource = source,
      phase = "spawned",
      ts = now
    )
    SuccessorGate.writeIntent(intentFile, intent)
      .as(Right(intent))
      .handleErrorWith(e => IO.pure(Left(s"intent write failed: ${e.getMessage}")))

  /** 中止路径（设计原则 2「热重启永不把好实例变死」）：清 draining（新工作可准入，
    * 验收 4）+ intent failure 记录 + ERROR 日志 + failed 帧 + 释放 in-progress 占位。
    * 旧实例继续服务、端口不动、零停机。 */
  private def abort(intent: Option[HotRestartIntent], reason: String): IO[Unit] =
    HotRestart.drainingRef.set(false) *>
      HotRestart.inProgressRef.set(false) *>
      intent.traverse(_ => SuccessorGate.markFailed(intentFile, reason).attempt.void) *>
      logger.error(
        s"[hot-restart] RESTART ABORTED: $reason — old instance keeps serving (draining cleared, zero downtime)") *>
      broadcast(statusFrame("failed", s"hot restart aborted: $reason — this instance keeps serving"))

  private def statusFrame(phase: String, detail: String): Json =
    Json.obj("type" -> "restartStatus".asJson, "phase" -> phase.asJson, "detail" -> detail.asJson)
end HotRestart

/** 在飞五域快照（设计 §3.4）：F1 running 节点 / F2 delegate-subtask / F3 活跃 turn /
  * F4 在飞 LLM / F5 等待型 bg 任务。isIdle = 五域全空；busy 时 detail 逐域列名（UI
  * 可展示「2 个节点在跑：A、B」）。域读取失败按忙处理（fail-safe，错误文本入明细）。 */
final case class QuiesceReport(
  runningNodes: List[String],
  subtasks: List[String],
  activeTurns: List[String],
  inflightLlm: Int,
  waitingBgTasks: List[String]
):
  def isIdle: Boolean =
    runningNodes.isEmpty && subtasks.isEmpty && activeTurns.isEmpty &&
      inflightLlm == 0 && waitingBgTasks.isEmpty

  def detail: String =
    val parts = List.newBuilder[String]
    if runningNodes.nonEmpty then parts += s"${runningNodes.size} node(s) running: ${runningNodes.mkString(", ")}"
    if subtasks.nonEmpty then parts += s"${subtasks.size} subtask(s) in flight: ${subtasks.mkString(", ")}"
    if activeTurns.nonEmpty then parts += s"${activeTurns.size} active turn(s): ${activeTurns.mkString(", ")}"
    if inflightLlm > 0 then parts += s"$inflightLlm in-flight LLM request(s)"
    if waitingBgTasks.nonEmpty then
      parts += s"${waitingBgTasks.size} waiting background task(s): ${waitingBgTasks.mkString(", ")}"
    parts.result().mkString("; ")
end QuiesceReport

object HotRestart:
  private val logger = NebflowLogger.forName("nebflow.hotrestart")

  // ===== 进程内全局编排状态（伴随单例，BgTaskRegistry 先例）=====

  /** draining 工作准入闸（设计 §3.3）：WebSocketRoutes（用户文本）与 ProjectActor
    * （TriggerDispatcher/Reenter）在无实例引用处读取——故放伴随对象全局 Ref。
    * 中止路径清零、成功路径保持置位至进程退出；测试经 resetForTest 复位。 */
  private[hotrestart] val drainingRef: Ref[IO, Boolean] = Ref.unsafe(false)

  /** in-progress 占位：在途重启幂等合并（R7）；中止释放，成功保持（进程即将退出）。 */
  private[hotrestart] val inProgressRef: Ref[IO, Boolean] = Ref.unsafe(false)

  /** 冷却窗锚点（R7）：成功重启完成时刻（[s7] 后继侧 noteRestartCompleted 置位）。 */
  private[hotrestart] val lastCompletedAtRef: Ref[IO, Long] = Ref.unsafe(0L)

  def isDraining: IO[Boolean] = drainingRef.get

  /** 工作准入闸（单一 choke 点语义）：Left = draining 中拒绝（503 + retryAfter 语义
    * 对 WS 帧）。 */
  def admissionGate: IO[Either[String, Unit]] =
    drainingRef.get.map:
      case false => Right(())
      case true  => Left("gateway is hot-restarting (draining) — new work temporarily refused, retry in a few seconds")

  /** 回滚总开关（设计 §6）：system prop `nebflow.hotRestart.enabled`，默认 true；
    * false 时触发入口返回「未启用」，引擎机制全旁路（CrashRecoveryEnabled 同款）。 */
  def isDisabled: Boolean =
    !sys.props.getOrElse("nebflow.hotRestart.enabled", "true").toBoolean

  /** [s7] 后继侧：握手完成（successor.json + intent 归档）后的冷却窗锚点置位。 */
  def noteRestartCompleted(): IO[Unit] =
    lastCompletedAtRef.set(System.currentTimeMillis())

  /** 普通 boot（非 succeed）的握手文件卫生（验收 12）：>10min 未归档 intent → WARN
    * + 归档，不阻塞。GatewayMain entryGate 普通分支调用。 */
  def archiveStaleIntentIfNeeded: IO[Unit] =
    SuccessorGate.archiveStaleIntent(PathUtil.dataRoot)

  /** 测试/QA 复位全局编排状态（进程内 Ref——Spec 直接复位，替代重启进程）。 */
  private[hotrestart] def resetForTest: IO[Unit] =
    drainingRef.set(false) *> inProgressRef.set(false) *> lastCompletedAtRef.set(0L)

  // ===== 时序参数（测试/QA 可经 system prop 调；默认对齐设计 §3.3/§3.4/§6 R7）=====

  final case class Timing(
    /** C1 spawn 存活 grace。 */
    c1GraceMs: Long = 2000L,
    /** C2 后继到门口 deadline。 */
    c2DeadlineMs: Long = 30000L,
    /** 空闲稳定观察窗（observe_idle 经验移植）。 */
    observeWindowMs: Long = 15000L,
    /** draining 收敛总期限（fail-safe 中止口径）。 */
    drainDeadlineMs: Long = 120000L,
    /** WaitIdle 复查间隔（设计 §3.4：每 10s）。 */
    waitIdlePollMs: Long = 10000L,
    /** draining 收敛轮询间隔。 */
    drainPollMs: Long = 1000L,
    /** 重启完成冷却窗（R7，建议 60s）。 */
    cooldownMs: Long = 60000L
  )

  object Timing:
    def fromSystemProps(): Timing =
      def prop(name: String, default: Long): Long =
        sys.props.getOrElse(s"nebflow.hotRestart.$name", default.toString).toLongOption.getOrElse(default)
      Timing(
        c1GraceMs = prop("c1GraceMs", 2000L),
        c2DeadlineMs = prop("c2DeadlineMs", 30000L),
        observeWindowMs = prop("observeWindowMs", 15000L),
        drainDeadlineMs = prop("drainDeadlineMs", 120000L),
        waitIdlePollMs = prop("waitIdlePollMs", 10000L),
        drainPollMs = prop("drainPollMs", 1000L),
        cooldownMs = prop("cooldownMs", 60000L)
      )

  // ===== spawn 形态与命令构造（两形态共用握手协议 §4——form 仅定 spawn 命令）=====

  /** 形态识别：classpath 内可信 jar 位于 jpackage .app/Contents → bundled，否则 jar；
    * sbt run 类路径解析不出可信 jar → unknown（spawn 大声报错，不硬闯）。
    * （AutoStartService.resolveRunJar 同款信任过滤。） */
  def detectForm: String =
    AutoStartService.resolveRunJar() match
      case Some(jar) if AutoStartService.isBundledApp(jar) => "bundled"
      case Some(_)                                         => "jar"
      case None                                            => "unknown"

  /** 纯函数命令构造（测试直注 form/javaBin/jarPath 验证；验收 4 的 javaBin 注入点
    * 即参数层）。
    *  - bundled：直接 spawn bundle executable（jpackage launcher 透传追加参数 →
    *    实际 `--server …`；R3 透传存疑 = bundled 验收 DEFER，桌面批解冻后实测）。
    *  - jar：`java --add-opens … -jar <jar> start --home … --port … --succeed …
    *    --no-browser`。
    * 双保险（§3.5 规则 3）：env `NEBFLOW_GATEWAY_PORT` 主保险（defaultSpawn 显式
    * 注入）+ argv `--home/--port` belt（intent 记录值注入 argv——任何一层 fork/exec
    * 断链都不会回落 8080）。 */
  def buildCommand(
    form: String,
    javaBin: String,
    jarPath: Option[String],
    intentPath: String,
    home: String,
    port: Int
  ): Either[String, List[String]] =
    val belt = List("--home", home, "--port", port.toString)
    form match
      case "bundled" =>
        jarPath.flatMap(AutoStartService.bundleExecutable) match
          case Some(exe) =>
            Right(List(exe) ++ belt ++ List("--succeed", intentPath, "--no-browser"))
          case None      => Left("bundled form detected but bundle executable not resolvable from jar path")
      case "jar" =>
        jarPath match
          case Some(jar) =>
            Right(
              List(javaBin, "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar", jar, "start") ++
                belt ++ List("--succeed", intentPath, "--no-browser"))
          case None => Left("cannot resolve run JAR (sbt run / dev classpath?) — hot restart unavailable")
      case other =>
        Left(s"cannot resolve run form '$other' (sbt run / dev classpath?) — hot restart unavailable")

  /** javaBin 解析注入点（验收 4：javaBin 解析替换为不存在路径 → spawn 失败 → 中止）。
    * 默认 = RestartHelper 既有资产复用。 */
  private[hotrestart] var javaBinResolver: () => String = () => RestartHelper.resolveJavaBin()

  /** spawn 出来的后继进程的最小操作面（测试注入 fake，不依赖 java.lang.Process）。 */
  trait SpawnedProcess:
    def pid: Long
    def isAlive: IO[Boolean]
    /** 中止路径 TERM 自家后继（C2 超时分支——唯一新增杀动作）。 */
    def destroy: IO[Unit]

  private final class LiveProcess(p: java.lang.Process) extends SpawnedProcess:
    def pid: Long = p.pid()
    def isAlive: IO[Boolean] = IO.blocking(p.isAlive())
    def destroy: IO[Unit] = IO.blocking(p.destroy()).attempt.void

  /** 真实 spawn（设计 §3.5 规则 3）：env `NEBFLOW_GATEWAY_PORT` 主保险（fork-proof，
    * ProcessBuilder 显式注入 + 进程 env 继承双层）+ argv `--succeed/--no-browser`
    * belt（--home/--port 由 JVM 侧 parseGlobalFlags 从 intent 校验兜底）；stdout/
    * stderr 重定向 restart.log（RestartHelper :52-54 先例——防管道断连，旧退后后继
    * 被 launchd 收养）；working dir = user.home。spawn 异常（EPERM 沙箱等）→ Left，
    * 编排器走零损失中止（沙箱场景降级 = fail-safe 保持服务，绝不硬闯）。 */
  def defaultSpawn(intent: HotRestartIntent): IO[Either[String, SpawnedProcess]] =
    val jarPath = AutoStartService.resolveRunJar()
    val cmd = buildCommand(
      intent.form,
      javaBinResolver(),
      jarPath,
      SuccessorGate.intentPath(PathUtil.dataRoot).toString,
      intent.home,
      intent.port
    )
    cmd match
      case Left(err) => IO.pure(Left(err))
      case Right(parts) =>
        IO.blocking {
          val pb = new ProcessBuilder(parts*)
          pb.directory(new File(sys.props("user.home")))
          // env 主保险：无论哪层 fork/exec 断了 argv，后继端口都不回落 8080（标准配方）
          pb.environment().put("NEBFLOW_GATEWAY_PORT", intent.port.toString)
          val logsDir = new File(PathUtil.dataRoot.toIO, "logs")
          if !logsDir.exists() then logsDir.mkdirs()
          val logFile = new File(logsDir, "restart.log")
          pb.redirectOutput(logFile)
          pb.redirectError(logFile)
          val p = pb.start()
          logger.infoSync(s"[hot-restart] successor spawned: pid ${p.pid()} cmd=${parts.mkString(" ")}")
          Right(new LiveProcess(p))
        }.attempt.map {
          case Left(NonFatal(e)) =>
            Left(s"spawn failed: ${e.getMessage} (sandbox? form=${intent.form} cmd=${parts.mkString(" ")})")
          case Left(e)  => Left(s"spawn failed: ${e.getMessage}")
          case Right(r) => r
        }
end HotRestart

/** 重启模式（设计 §3.2：编排器与触发源解耦；§7 拍板项 2 建议 UI 默认 WaitIdle）。 */
enum RestartMode:
  /** 忙 → 拒绝并回报五域明细。 */
  case RejectIfBusy
  /** 忙 → 排队等空闲（draining 不置位，每轮重判），上限 timeoutMs。 */
  case WaitIdle(timeoutMs: Long)
