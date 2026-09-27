package nebflow.core.hotrestart

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.*
import nebflow.core.project.{NodeLifecycle, ProjectRuntimeRegistry}
import nebflow.core.tools.BgTaskRegistry
import nebflow.shared.{NebflowLogger, PathUtil}

import java.io.File

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * 触发器无关的热重启编排器（hot-restart 批，设计 hot-restart 设计件（内部留档））。
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
  /**
   * Phase 5 D 步窄能力注入(替代原 `resources: SharedResources` 整只定位器):
   * 在飞 sub-agent 任务读数(F2 域)。实现 = agent.SharedResources 混入
   * core.SubAgentTaskPort;接线 = 装配点按窄类型传入。
   */
  subAgentTasks: SubAgentTaskPort,
  /** D 步窄能力注入:统一注册表活跃读数(F3 域,原内联过滤迁 SharedResources 侧)。 */
  agentRegistry: AgentRegistryPort,
  /** D 步窄能力注入:会话落盘 flush([3] 状态落盘核验)。 */
  sessionStore: SessionStorePort,
  /** 优雅让渡闸([8];Deferred 是 cats 类型,以底层值直传,不经 agent 定位器)。 */
  gatewayShutdown: Deferred[IO, Unit],
  port: Int,
  host: String,
  /** 进度广播（GatewayMain 接 wsHub.broadcast——restartStatus 帧到所有 WS 连接）。 */
  broadcast: Json => IO[Unit],
  timing: HotRestart.Timing = HotRestart.Timing.fromSystemProps(),
  /** spawn 注入点（验收 4：注入 spawn 故障；测试注入 fake）。默认 = 真实 ProcessBuilder。 */
  spawn: HotRestartIntent => IO[Either[String, HotRestart.SpawnedProcess]] = HotRestart.defaultSpawn,
  /**
   * **端口探测注入点（hotupdate 批 2 G3 第三档）**：复用既有能力
   * `nebflow.cli.SingleInstanceGuard.connectProbeAccepted`（既有调用点
   * `GatewayMain.scala:150`）。core 层不得反向依赖 cli（`shared ← core ← cli/gateway`），
   * 故由**调用点**注入；None = 未装配 ⇒ 第三/四档报 `unverified`（大声、可核），
   * **绝不阻塞既有交接链**。
   */
  connectProbe: Option[Int => IO[Boolean]] = None,
  /**
   * **安装目录**（批 2 G4 的版本留存 + 指针所在）。必须由**调用点显式注入**
   * （生产 = `InstallPointer.defaultInstallDir()`；spec = 临时目录）。默认 None ⇒
   * 指针相关动作全部旁路：第四档退回「回执包路径」解析、健康失败**不动盘上指针**——
   * 绝不让测试或未装配实例去改一份真实安装的指针。
   */
  installDir: Option[os.Path] = None
):
  private val logger = NebflowLogger.forName("nebflow.hotrestart")
  private def intentFile: os.Path = SuccessorGate.intentPath(PathUtil.dataRoot)

  /**
   * 在途请求的「交接结果信号」（hotupdate 批 2 G0）：只有经窄入口
   * [[requestRestartAwaitingHandover]] 发起的请求会登记它——登记后，`begin` 在优雅
   * 让渡时收敛为 `Right(())`、任一中止路径收敛为 `Left(原因)`。普通
   * [[requestRestart]] 不登记（既有行为逐字节不变）。
   */
  private val pendingHandoverRef: Ref[IO, Option[HotRestart.HandoverSignal]] =
    Ref.unsafe[IO, Option[HotRestart.HandoverSignal]](None)

  private def signal(
    outcome: Option[HotRestart.HandoverSignal],
    result: Either[HotRestart.HandoverFailure, Unit]
  ): IO[Unit] =
    outcome.traverse_(_.complete(result).void) *>
      pendingHandoverRef.update(o => if o == outcome then None else o)

  // ===== [1] 在飞五域判定（设计 §3.4 / §1.5 权威源）=====

  /** 五域空闲判定。域读取失败 = 无法证明空闲 → 按忙处理（fail-safe，错误文本入明细）。 */
  def quiesceReport: IO[QuiesceReport] =
    val f1: IO[List[String]] = ProjectRuntimeRegistry.all
      .flatMap { rts =>
        rts
          .traverse { rt =>
            rt.store.snapshot
              .map(s =>
                s.nodes.values
                  .filter(_.status == NodeLifecycle.Running)
                  .map(n => s"${rt.project.name}/${n.name}")
                  .toList
              )
              .handleErrorWith(e => IO.pure(List(s"${rt.project.name}/<flow-map read error: ${e.getMessage}>")))
          }
          .map(_.flatten)
      }
      .handleErrorWith(e => IO.pure(List(s"<project registry read error: ${e.getMessage}>")))
    val f2: IO[List[String]] = subAgentTasks.findRunningTasks
      .map(_.map(t => s"${t.source}:${t.taskId}@${t.parentSessionId}"))
      .handleErrorWith(e => IO.pure(List(s"<subtask store read error: ${e.getMessage}>")))
    val f3: IO[List[String]] = agentRegistry.processingSessionIds
      .handleErrorWith(e => IO.pure(List(s"<agent registry read error: ${e.getMessage}>")))
    val f4: IO[Int] = LlmRuntimePort.inflightCount.handleErrorWith(_ => IO.pure(Int.MaxValue))
    val f5: IO[List[String]] = BgTaskRegistry.waitingTasks
      .map(_.map(t => s"${t.jobId}:${t.description.take(60)}"))
      .handleErrorWith(e => IO.pure(List(s"<bg registry read error: ${e.getMessage}>")))
    (f1, f2, f3, f4, f5).mapN(QuiesceReport.apply)
  end quiesceReport

  // ===== 请求入口（触发源解耦：Web UI / 后续 REST / 桌面菜单同走此门）=====

  /**
   * 请求热重启。同步拒绝（禁用/冷却/忙且 RejectIfBusy）→ Left（触发面立即回报）；
   * 受理 → Right 且编排 fiber fork（进度经 restartStatus 帧广播）。进行中请求幂等
   * 合并（R7 重启风暴护栏的第二半）。
   */
  def requestRestart(source: String, mode: RestartMode): IO[Either[String, Unit]] =
    requestRestartInternal(source, mode, None).map(_.left.map(_.reason))

  /**
   * **窄入口（hotupdate 批 2 G0）**：与 [[requestRestart]] 同一台机器、同一道闸、
   * 同一个排空环——唯一差别是**返回时机**：`Right(())` 表示「四档健康自检通过且已触发
   * 优雅让渡」，`Left(原因)` 表示中止（含自检失败）。更新编排器用它替代直接调用
   * `requestRestart`，从而不再把「交接已受理」当成「已完成」（批 1 verify §11② 的残余
   * 可见性收口）。既有 WS 重启命令走 [[requestRestart]]，行为零变。
   */
  def requestRestartAwaitingHandover(source: String, mode: RestartMode): IO[Either[HotRestart.HandoverFailure, Unit]] =
    cats.effect.Deferred[IO, Either[HotRestart.HandoverFailure, Unit]].flatMap { outcome =>
      requestRestartInternal(source, mode, Some(outcome))
        .flatMap(_.fold(err => IO.pure(Left(err): Either[HotRestart.HandoverFailure, Unit]), _ => outcome.get))
    }

  private def requestRestartInternal(
    source: String,
    mode: RestartMode,
    outcome: Option[HotRestart.HandoverSignal]
  ): IO[Either[HotRestart.HandoverFailure, Unit]] =
    if HotRestart.isDisabled then
      IO.pure(
        Left(
          HotRestart.HandoverFailure(
            HotRestart.FailureKind.Disabled,
            "hot restart is disabled (nebflow.hotRestart.enabled=false)"
          )
        )
      )
    else
      HotRestart.inProgressRef
        .modify { cur =>
          if cur then (cur, false) // 已有重启在途——幂等合并（占位不动）
          else (true, true)
        }
        .flatMap { claimed =>
          if !claimed then
            // 幂等合并：本请求不新起编排。窄入口要在这里**接上在途请求的结果信号**，
            // 绝不把「合并」当成「已完成」（无信号在途 ⇒ 沿用既有 Right(()) 语义）。
            outcome match
              case None => IO.pure(Right(()))
              case Some(_) =>
                pendingHandoverRef.get.flatMap {
                  case Some(inflight) => inflight.get
                  case None =>
                    // 在途请求不是窄入口发起的（无从观察）——如实记名，不假装观测到了交接
                    IO.pure(
                      Left(
                        HotRestart.HandoverFailure(
                          HotRestart.FailureKind.NotObserved,
                          "merged into an in-flight restart that carries no handover signal — outcome not observable from this request"
                        )
                      )
                    )
                }
          else
            pendingHandoverRef.set(outcome) *>
              HotRestart.lastCompletedAtRef.get.flatMap { lastCompleted =>
                val since = System.currentTimeMillis() - lastCompleted
                if lastCompleted > 0 && since < timing.cooldownMs then
                  // 冷却窗（R7）：放弃本次占位，准许后续请求
                  HotRestart.inProgressRef.set(false) *>
                    IO.pure(
                      Left(
                        HotRestart.HandoverFailure(
                          HotRestart.FailureKind.Cooldown,
                          s"restart cooldown: only ${(timing.cooldownMs - since) / 1000}s since the last restart (window ${timing.cooldownMs / 1000}s)"
                        )
                      )
                    )
                else
                  quiesceReport.flatMap { q =>
                    if q.isIdle then begin(source, outcome).start.as(Right(()))
                    else
                      mode match
                        case RestartMode.RejectIfBusy =>
                          // 放弃占位 + 五域明细立即回报（验收 2）
                          HotRestart.inProgressRef.set(false) *>
                            IO.pure(
                              Left(HotRestart.HandoverFailure(HotRestart.FailureKind.Busy, s"busy — ${q.detail}"))
                            )
                        case RestartMode.WaitIdle(timeoutMs) =>
                          // 等待期 draining 不置位（工作照常准入，每轮重判，设计 §3.4）
                          waitThenBegin(source, timeoutMs, q, outcome).start.as(Right(()))
                  }
                end if
              }
        }

  /**
   * WaitIdle：每 waitIdlePollMs 复查五域，上限 timeoutMs——超时放弃 + 上报明细 +
   * 释放占位（验收 3 第二 case）；转空闲 → begin。
   */
  private def waitThenBegin(
    source: String,
    timeoutMs: Long,
    initial: QuiesceReport,
    outcome: Option[HotRestart.HandoverSignal] = None
  ): IO[Unit] =
    val deadline = System.currentTimeMillis() + timeoutMs
    broadcast(
      statusFrame("quiesce", s"waiting for in-flight work to finish (up to ${timeoutMs / 1000}s): ${initial.detail}")
    ) *>
      logger.info(s"[hot-restart] WaitIdle queued (source=$source, timeout=${timeoutMs}ms): ${initial.detail}") *>
      pollIdle(deadline).flatMap {
        case Left(q) =>
          val reason = s"restart abandoned: still busy after ${timeoutMs / 1000}s — ${q.detail}"
          HotRestart.inProgressRef.set(false) *>
            logger.warn(s"[hot-restart] WaitIdle timeout after ${timeoutMs}ms — still busy: ${q.detail}") *>
            signal(outcome, Left(HotRestart.HandoverFailure(HotRestart.FailureKind.Busy, reason))) *>
            broadcast(statusFrame("failed", reason))
        case Right(()) => begin(source, outcome).void
      }

  end waitThenBegin

  private def pollIdle(deadline: Long): IO[Either[QuiesceReport, Unit]] =
    quiesceReport.flatMap {
      case q if q.isIdle => IO.pure(Right(()))
      case q if System.currentTimeMillis() > deadline => IO.pure(Left(q))
      case _ => IO.sleep(timing.waitIdlePollMs.millis) *> pollIdle(deadline)
    }

  // ===== 更新编排器（hotupdate 批 1）冻结相位入口——**复用本件既有机器，零复制** =====

  /**
   * 更新编排器的「冻结」相位入口：复用**同一道准入闸**（#3）与**同一个排空收敛环**
   * （#4，含 15 秒稳定观察窗），等待期准入闸不置位（与 `WaitIdle` 同口径：工作照常
   * 准入，每轮重判）。语义与热重启完全一致——失败/超时一律 **中止**（清闸、旧实例
   * 继续服务、零停机；不强制、不翻态、无强制档）。
   *
   * 不新造第二道闸、第二套排空环、第二套等待轮询：本方法只是把既有
   * [[pollIdle]]/[[drainLoop]]/`drainingRef` 组合成一个窄入口。
   * 落盘核验（八步第 3 步）不在此重复——重启相位的 [[begin]] 原样执行它。
   *
   * 失败类别走 [[HotRestart.DrainFailure]]（**不靠字符串猜**）：更新编排器据此映射到
   * 自己的冻结原因字面。
   *
   * 调用方：`nebflow.core.hotupdate.UpdateOrchestrator`（唯一调用点）。
   */
  def drainForUpdate(mode: RestartMode): IO[Either[HotRestart.DrainFailure, Unit]] =
    def closeAndDrain: IO[Either[HotRestart.DrainFailure, Unit]] =
      HotRestart.drainingRef.set(true) *> drainLoop.map {
        // 分类在本件内完成（drainLoop 的唯一 Left 形态 = 「drain deadline (…」两条消息，
        // 由本件产生）——调用方据类别映射到自己的冻结原因字面，不靠字符串猜。
        case Left(err) => Left(HotRestart.DrainFailure.DeadlineExceeded(err))
        case Right(()) => Right(())
      }
    quiesceReport.flatMap { q =>
      if q.isIdle then closeAndDrain
      else
        mode match
          case RestartMode.RejectIfBusy =>
            IO.pure(Left(HotRestart.DrainFailure.Busy(q.detail)))
          case RestartMode.WaitIdle(timeoutMs) =>
            val deadline = System.currentTimeMillis() + timeoutMs
            logger.info(
              s"[hot-restart] update freeze: waiting for in-flight work (up to ${timeoutMs / 1000}s): ${q.detail}"
            ) *>
              pollIdle(deadline).flatMap {
                case Left(stillBusy) =>
                  IO.pure(Left(HotRestart.DrainFailure.WaitLimitExceeded(stillBusy.detail, timeoutMs)))
                case Right(()) => closeAndDrain
              }
    }

  end drainForUpdate

  /**
   * 更新编排器中止腿的准入恢复（与 [[abort]] 的第 1 步同语义、同一 Ref；幂等）。
   * 不新造第二道闸——中止后新工作立即可准入。
   */
  def releaseDrain(): IO[Unit] =
    logger.warn("[hot-restart] admission reopened (drain released for an aborted update)") *>
      HotRestart.drainingRef.set(false)

  // ===== [2]-[8] 重启编排（begin 起在 fork fiber 上跑）=====

  private def begin(
    source: String,
    outcome: Option[HotRestart.HandoverSignal] = None
  ): IO[Either[String, Unit]] =
    broadcast(statusFrame("draining", "in-flight work settled — closing work admission")) *>
      logger.info(s"[hot-restart] draining on (source=$source) — work admission closed") *>
      HotRestart.drainingRef.set(true) *>
      drainLoop.flatMap {
        case Left(err) => abort(None, err, outcome).as(Left(err))
        case Right(()) =>
          // [3] 状态落盘核验：既有持久面 flush（2s debounce 尾巴清零）。
          //     flow-map/results/tasks/F2 队列/freeze skip/配置均为 write-through
          //     或变更即持久——零动作（设计 §3.4 落盘清单）。
          sessionStore.flushPendingUiWrites *>
            sessionStore.flushPendingMessages *>
            logger.info("[hot-restart] state flushed (dirty session UI + message writes drained)") *>
            // [4] intent write-ahead
            writeIntent(source).flatMap {
              case Left(err) => abort(None, err, outcome).as(Left(err))
              case Right(intent) =>
                broadcast(statusFrame("spawning", "successor process launching")) *>
                  // [5] spawn（env 主保险 + argv belt；失败零损失——C1 语义前移到 spawn 异常）
                  spawn(intent)
                    .handleErrorWith(e =>
                      IO.pure(Left(s"spawn exception: ${Option(e.getMessage).getOrElse(e.toString)}"))
                    )
                    .flatMap {
                      case Left(err) => abort(Some(intent), err, outcome).as(Left(err))
                      case Right(proc) =>
                        // [6] C1：spawn 存活闸（「才许死」）
                        IO.sleep(timing.c1GraceMs.millis) *>
                          proc.isAlive.flatMap { alive =>
                            if !alive then
                              abort(
                                Some(intent),
                                s"C1: successor (pid ${proc.pid}) died within ${timing.c1GraceMs}ms of spawn",
                                outcome
                              )
                                .as(Left("successor died immediately"))
                            else
                              logger.info(
                                s"[hot-restart] C1 ok (successor pid ${proc.pid} alive) — waiting for readyToBind"
                              ) *>
                                // [7] C2：后继到门口闸
                                pollReadyToBind(proc).flatMap {
                                  case Left(err) =>
                                    // C2 超时/后继中途死亡 → TERM 自家后继（未 bind 未持资源）
                                    proc.destroy.attempt.void *>
                                      abort(Some(intent), err, outcome).as(Left(err))
                                  case Right(()) =>
                                    // [7.5] ===== 四档新版本健康自检（hotupdate 批 2 G3 · 结构性唯一窗口）=====
                                    // 位置 = 「到门口确认」([7]) 与「优雅让渡」([8]) 之间：旧实例仍握着
                                    // 准入闸与端口、后继已在门口 ⇒ 此窗内自检失败**只**走既有中止路径，
                                    // 变砖窗口（旧已退出 ∧ 新起不来）不会被打开，回滚不需要任何新机制。
                                    // 档位/证据来源见 `HealthCheck`（③④ 探的是后继公告的 loopback 探针端点，
                                    // 不是共享端口——共享端口此刻仍属旧实例，直接探它会探到自己）。
                                    healthGate(proc, intent).flatMap { report =>
                                      if !report.isHealthy then
                                        val reason =
                                          s"health self-check failed: ${report.failed.map(_.detail).getOrElse("unknown")} [${report.detail}]"
                                        proc.destroy.attempt.void *>
                                          pointerRollbackOnHealthFailure(reason) *>
                                          abort(Some(intent), reason, outcome, HotRestart.FailureKind.HealthCheckFailed)
                                            .as(Left(reason))
                                      else
                                        // [8] 优雅让渡：complete Deferred → waitForQuit race 收敛
                                        //     → Ember 优雅 stop → .guarantee 链 → JVM 自然退出。
                                        //     draining 保持置位（旧实例不再收新工作，即将退出）。
                                        broadcast(
                                          statusFrame(
                                            "handing-over",
                                            s"successor at the port and health-verified (${report.detail}) — old instance handing over now"
                                          )
                                        ) *>
                                          logger.info(
                                            s"[hot-restart] handover: graceful shutdown triggered (successor pid ${proc.pid} ready; health ${report.detail}; old pid ${intent.oldPid} exiting via the graceful chain)"
                                          ) *>
                                          signal(outcome, Right(())) *>
                                          gatewayShutdown.complete(()).attempt.void *>
                                          IO.pure(Right(()))
                                    }
                                }
                          }
                    }
            }
      }

  /**
   * draining 收敛环：准入闸关闭后，等在飞工作自然终局 + 15s 稳定观察窗（restart.sh
   * observe_idle 经验移植——阻尼「判定期间新工作进来」的振荡；闸已关，新工作进不来，
   * 这里只等存量终局）。总期限 drainDeadlineMs（fail-safe：收敛失败 = 中止重启，
   * 旧实例继续服务——不静默等待、不强制，设计 §3.4）。
   */
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
                Left(
                  s"drain deadline (${timing.drainDeadlineMs / 1000}s) exceeded during observation window: ${q2.detail}"
                )
              )
            else loop
          }
        end if
      }
    loop

  end drainLoop

  /**
   * **[7.5] 四档新版本健康自检（hotupdate 批 2 G3）**——插在「到门口确认」（[7] C2）
   * 与「优雅让渡」（[8]）之间（**结构性唯一窗口**：此刻旧实例仍握着准入闸与端口、
   * 后继已在门口，故自检失败只走既有中止路径，变砖窗口不被打开）。
   *
   * 四档（逐档独立超时，见 [[HotRestart.Timing]] 的 `health*` 字段）：① 后继存活
   * ② 到门口回执（两档既有能力）③ 端口可服务（对后继公告的 loopback 探针端口做真连接 +
   * 真 HTTP + 真载荷）④ 版本核对（载荷 `version` vs [[HealthCheck.expectedVersion]] 的
   * 真值）。档位实现与「为什么不能探共享端口」见 `HealthCheck` / `ProbeEndpoint`。
   *
   * **失败 ⇒ 复用既有中止路径**（`abort`：清 draining、intent failure、旧实例继续服务），
   * **零第二套中止机**；`unverified` 档（探针端点未公告/未装配）在明细里如实记名。
   */
  private def healthGate(proc: HotRestart.SpawnedProcess, intent: HotRestartIntent): IO[HealthReport] =
    val form = intent.form
    val spawnCmd = Some(intent.spawnCmd)
    for
      pointer <- installDir.flatTraverse(InstallPointer.readCurrent)
      expected = HealthCheck.expectedVersion(pointer, spawnCmd)
      report <- HealthCheck.atDoor(
        isAlive = proc.isAlive,
        readIntent = SuccessorGate.readIntent(intentFile),
        expected = expected,
        t1HoldMs = timing.healthT1SuccessorAliveHoldMs,
        t2DeadlineMs = timing.healthT2DoorReceiptMs,
        t3DeadlineMs = timing.healthT3PortServingMs,
        t4DeadlineMs = timing.healthT4VersionMatchMs,
        pollMs = timing.healthPollMs,
        connectProbe = connectProbe
      )
      _ <- report.unverified.traverse_(u =>
        logger.warn(
          s"[hot-restart] health self-check tier UNVERIFIED (${u.tier.wire}): ${u.detail} — recorded, never reported as pass"
        )
      )
    yield report

    end for

  end healthGate

  /**
   * **G4 触发器①（健康自检失败 ⇒ 指针切回上一版）**——门口自检失败时的盘上回滚。
   *
   * 语义边界（逐条）：
   *  - 此刻**旧实例仍在本版本上服务**（自检在让渡之前），故回滚**只需切指针**：复起
   *    不需要（没有进程退出过），"旧实例继续服务" 与 "下次开机起上一版" 同时成立。
   *  - **只在真的有更新在演时切**：判据 = 安装指针的当前版本 ≠ 本进程版本。普通重启
   *    （指针 = 本版本）或开发布局（无指针）**不动指针**——否则一次普通重启的健康
   *    失败会把安装无端降级（既有重启面的回归）。
   *  - 指针是**承重件**：切完即「坏版出选取面」，下一次开机由启动器按指针起上一版
   *    （既有开机链，零新增恢复机制）。
   */
  private def pointerRollbackOnHealthFailure(reason: String): IO[Unit] =
    installDir match
      case None =>
        logger.warn(
          s"[hot-restart] health self-check failed but no install directory is wired into this instance — no on-disk pointer rollback (reason: $reason)"
        )
      case Some(dir) =>
        InstallPointer
          .readCurrent(dir)
          .flatMap {
            case Some(cur) if cur != nebflow.Version.string =>
              InstallPointer.flip(dir).flatMap {
                case Right((to, from)) =>
                  logger.warn(
                    s"[hot-restart] pointer rollback after health failure: current-version $from -> $to (bad version retained on disk but out of the selection face); reason: $reason"
                  )
                case Left(err) =>
                  logger.error(
                    s"[hot-restart] pointer rollback FAILED ($err) — the disk pointer still names the unhealthy version"
                  )
              }
            case Some(cur) =>
              logger.warn(
                s"[hot-restart] health self-check failed while current-version ($cur) == this instance's version — no update in play, pointer left untouched"
              )
            case None =>
              logger.warn(
                s"[hot-restart] health self-check failed but no install pointer under ${dir.toString} — nothing to roll back on disk (dev layout)"
              )
          }
          .handleErrorWith(e =>
            logger.error(s"[hot-restart] pointer rollback raised: ${Option(e.getMessage).getOrElse(e.toString)}")
          )

  /**
   * C2：轮询 intent phase=readyToBind（后继 Zone A 完成回执），c2DeadlineMs 上限。
   * 后继中途死亡也算超时分支（destroy 对尸体是 no-op）。
   */
  private def pollReadyToBind(proc: HotRestart.SpawnedProcess): IO[Either[String, Unit]] =
    val deadline = System.currentTimeMillis() + timing.c2DeadlineMs
    def loop: IO[Either[String, Unit]] =
      SuccessorGate.readIntent(intentFile).flatMap {
        case Some(i) if i.phase == "readyToBind" => IO.pure(Right(()))
        case _ =>
          proc.isAlive.flatMap { alive =>
            if !alive then IO.pure(Left("C2: successor died before readyToBind"))
            else if System.currentTimeMillis() > deadline then
              IO.pure(
                Left(s"C2: successor not readyToBind within ${timing.c2DeadlineMs}ms — TERM own successor and abort")
              )
            else IO.sleep(250.millis) *> loop
          }
      }
    loop

  end pollReadyToBind

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
      case Left(msg) => msg
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
    SuccessorGate
      .writeIntent(intentFile, intent)
      .as(Right(intent))
      .handleErrorWith(e => IO.pure(Left(s"intent write failed: ${e.getMessage}")))

  end writeIntent

  /**
   * 中止路径（设计原则 2「热重启永不把好实例变死」）：清 draining（新工作可准入，
   * 验收 4）+ intent failure 记录 + ERROR 日志 + failed 帧 + 释放 in-progress 占位。
   * 旧实例继续服务、端口不动、零停机。
   *
   * `outcome` = 窄入口（[[requestRestartAwaitingHandover]]）的交接结果信号：中止即
   * `Left(原因)`——使更新编排器能把「自检失败 / 中止」如实收敛为可观测失败态，而不是
   * 「已完成」（批 2 G0 收口）。
   */
  private def abort(
    intent: Option[HotRestartIntent],
    reason: String,
    outcome: Option[HotRestart.HandoverSignal] = None,
    kind: HotRestart.FailureKind = HotRestart.FailureKind.Aborted
  ): IO[Unit] =
    HotRestart.drainingRef.set(false) *>
      HotRestart.inProgressRef.set(false) *>
      intent.traverse(_ => SuccessorGate.markFailed(intentFile, reason).attempt.void) *>
      logger.error(
        s"[hot-restart] RESTART ABORTED: $reason — old instance keeps serving (draining cleared, zero downtime)"
      ) *>
      broadcast(statusFrame("failed", s"hot restart aborted: $reason — this instance keeps serving")) *>
      signal(outcome, Left(HotRestart.HandoverFailure(kind, reason)))

  private def statusFrame(phase: String, detail: String): Json =
    Json.obj("type" -> "restartStatus".asJson, "phase" -> phase.asJson, "detail" -> detail.asJson)
end HotRestart

/**
 * 在飞五域快照（设计 §3.4）：F1 running 节点 / F2 delegate-subtask / F3 活跃 turn /
 * F4 在飞 LLM / F5 等待型 bg 任务。isIdle = 五域全空；busy 时 detail 逐域列名（UI
 * 可展示「2 个节点在跑：A、B」）。域读取失败按忙处理（fail-safe，错误文本入明细）。
 */
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

  /**
   * draining 工作准入闸（设计 §3.3）：WebSocketRoutes（用户文本）与 ProjectActor
   * （TriggerDispatcher/Reenter）在无实例引用处读取——故放伴随对象全局 Ref。
   * 中止路径清零、成功路径保持置位至进程退出；测试经 resetForTest 复位。
   */
  private[hotrestart] val drainingRef: Ref[IO, Boolean] = Ref.unsafe(false)

  /** in-progress 占位：在途重启幂等合并（R7）；中止释放，成功保持（进程即将退出）。 */
  private[hotrestart] val inProgressRef: Ref[IO, Boolean] = Ref.unsafe(false)

  /** 冷却窗锚点（R7）：成功重启完成时刻（[s7] 后继侧 noteRestartCompleted 置位）。 */
  private[hotrestart] val lastCompletedAtRef: Ref[IO, Long] = Ref.unsafe(0L)

  def isDraining: IO[Boolean] = drainingRef.get

  /**
   * 工作准入闸（单一 choke 点语义）：Left = draining 中拒绝（503 + retryAfter 语义
   * 对 WS 帧）。
   */
  def admissionGate: IO[Either[String, Unit]] =
    drainingRef.get.map:
      case false => Right(())
      case true => Left("gateway is hot-restarting (draining) — new work temporarily refused, retry in a few seconds")

  /**
   * 回滚总开关（设计 §6）：system prop `nebflow.hotRestart.enabled`，默认 true；
   * false 时触发入口返回「未启用」，引擎机制全旁路（CrashRecoveryEnabled 同款）。
   */
  def isDisabled: Boolean =
    !sys.props.getOrElse("nebflow.hotRestart.enabled", "true").toBoolean

  /** [s7] 后继侧：握手完成（successor.json + intent 归档）后的冷却窗锚点置位。 */
  def noteRestartCompleted(): IO[Unit] =
    lastCompletedAtRef.set(System.currentTimeMillis())

  /**
   * 普通 boot（非 succeed）的握手文件卫生（验收 12）：>10min 未归档 intent → WARN
   * + 归档，不阻塞。GatewayMain entryGate 普通分支调用。
   */
  def archiveStaleIntentIfNeeded: IO[Unit] =
    SuccessorGate.archiveStaleIntent(PathUtil.dataRoot)

  /**
   * 测试/QA 复位全局编排状态（进程内 Ref——Spec 直接复位，替代重启进程）。
   * 可见面 `private[core]`：hotupdate 批的定向 spec（`nebflow.core.hotupdate.*`）也要
   * 复位同一批 Ref（测试串行、每例复位——否则上一个成功例的占位会合并下一例的请求）。
   */
  private[core] def resetForTest: IO[Unit] =
    drainingRef.set(false) *> inProgressRef.set(false) *> lastCompletedAtRef.set(0L)

  // ===== 窄入口的交接结果信号（hotupdate 批 2 G0）=====

  /**
   * 交接结果信号类型：窄入口（[[HotRestart.requestRestartAwaitingHandover]]）用它在
   * 「交接真的让渡了」与「哪一类中止」之间**类型化**收敛（不靠解析错误字符串，既有
   * `DrainFailure` 同款纪律）。
   */
  type HandoverSignal = cats.effect.Deferred[IO, Either[HandoverFailure, Unit]]

  /** 中止/拒绝的**类别**（不是自由文本）。 */
  enum FailureKind(val wire: String):
    /** 总开关关闭。 */
    case Disabled extends FailureKind("disabled")

    /** 冷却窗内（R7）。 */
    case Cooldown extends FailureKind("cooldown")

    /** 忙且「忙即拒绝」/ 排队超时。 */
    case Busy extends FailureKind("busy")

    /** 编排中中止（C1/C2/意图写失败等既有中止腿）。 */
    case Aborted extends FailureKind("aborted")

    /**
     * **四档健康自检失败**（G3；G0 的收口类别——必须呈现为可观测失败态，不是「已完成」）。
     */
    case HealthCheckFailed extends FailureKind("health-check-failed")

    /** 合并进在途请求但无从观察其结果（诚实记名，绝不假装观测到）。 */
    case NotObserved extends FailureKind("not-observed")
  end FailureKind

  final case class HandoverFailure(kind: FailureKind, reason: String)

  // ===== 更新编排器冻结相位的失败类别（hotupdate 批 1 窄面）=====

  /**
   * 排空/等待失败的**类别**（不是自由文本）：使更新编排器能把失败映射到自己的
   * 冻结原因字面，而不靠解析错误字符串。三类 = 裁定 4（中止不强制）口径下的全部
   * 中止形态；**没有强制档**（禁新增强制类别）。
   */
  enum DrainFailure:
    /** 忙且请求为「忙即拒绝」模式。 */
    case Busy(detail: String)

    /** 等待上限到达仍忙（更新场景默认 300s，裁定 7）。 */
    case WaitLimitExceeded(detail: String, timeoutMs: Long)

    /** 排空收敛期限到达仍忙（既有 `drainDeadlineMs`）。 */
    case DeadlineExceeded(detail: String)

    /** 诊断明细（人类可读；原因字面由调用方以自己的枚举承载）。 */
    def detailText: String = this match
      case Busy(d) => s"busy — $d"
      case WaitLimitExceeded(d, ms) => s"wait limit (${ms / 1000}s) exceeded, still busy: $d"
      case DeadlineExceeded(d) => d
  end DrainFailure

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
    cooldownMs: Long = 60000L,
    // ===== 健康自检四档逐档独立超时（batch 2 G3；上界合计 50s ≤ 设计建议 60s）=====
    /** 第一档：后继存活**持续**窗口。 */
    healthT1SuccessorAliveHoldMs: Long = 5000L,
    /** 第二档：到门口回执期限。 */
    healthT2DoorReceiptMs: Long = 10000L,
    /** 第三档：端口可服务（探针端点要求 200）期限。 */
    healthT3PortServingMs: Long = 30000L,
    /** 第四档：版本核对期限。 */
    healthT4VersionMatchMs: Long = 5000L,
    /** 自检轮询间隔。 */
    healthPollMs: Long = 250L
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
        cooldownMs = prop("cooldownMs", 60000L),
        // 健康自检四档（可注入可核：`nebflow.hotRestart.healthT*Ms` / `healthPollMs`）
        healthT1SuccessorAliveHoldMs = prop("healthT1SuccessorAliveHoldMs", 5000L),
        healthT2DoorReceiptMs = prop("healthT2DoorReceiptMs", 10000L),
        healthT3PortServingMs = prop("healthT3PortServingMs", 30000L),
        healthT4VersionMatchMs = prop("healthT4VersionMatchMs", 5000L),
        healthPollMs = prop("healthPollMs", 250L)
      )
    end fromSystemProps
  end Timing

  // ===== spawn 形态与命令构造（两形态共用握手协议 §4——form 仅定 spawn 命令）=====

  /**
   * 形态识别：classpath 内可信 jar 位于 jpackage .app/Contents → bundled，否则 jar；
   * sbt run 类路径解析不出可信 jar → unknown（spawn 大声报错，不硬闯）。
   * （AutoStartService.resolveRunJar 同款信任过滤。）
   */
  def detectForm: String =
    AutoStartService.resolveRunJar() match
      case Some(jar) if AutoStartService.isBundledApp(jar) => "bundled"
      case Some(_) => "jar"
      case None => "unknown"

  /**
   * 纯函数命令构造（测试直注 form/javaBin/jarPath 验证；验收 4 的 javaBin 注入点
   * 即参数层）。
   *  - bundled：直接 spawn bundle executable（jpackage launcher 透传追加参数 →
   *    实际 `--server …`；R3 透传存疑 = bundled 验收 DEFER，桌面批解冻后实测）。
   *  - jar：`java --add-opens … -jar <jar> start --home … --port … --succeed …
   *    --no-browser`。
   * 双保险（§3.5 规则 3）：env `NEBFLOW_GATEWAY_PORT` 主保险（defaultSpawn 显式
   * 注入）+ argv `--home/--port` belt（intent 记录值注入 argv——任何一层 fork/exec
   * 断链都不会回落 8080）。
   */
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
          case None => Left("bundled form detected but bundle executable not resolvable from jar path")
      case "jar" =>
        jarPath match
          case Some(jar) =>
            Right(
              List(javaBin, "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar", jar, "start") ++
                belt ++ List("--succeed", intentPath, "--no-browser")
            )
          case None => Left("cannot resolve run JAR (sbt run / dev classpath?) — hot restart unavailable")
      case other =>
        Left(s"cannot resolve run form '$other' (sbt run / dev classpath?) — hot restart unavailable")
    end match
  end buildCommand

  /**
   * javaBin 解析注入点（验收 4：javaBin 解析替换为不存在路径 → spawn 失败 → 中止）。
   * 默认 = RestartHelper 既有资产复用。
   */
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

  /**
   * 真实 spawn（设计 §3.5 规则 3）：env `NEBFLOW_GATEWAY_PORT` 主保险（fork-proof，
   * ProcessBuilder 显式注入 + 进程 env 继承双层）+ argv `--succeed/--no-browser`
   * belt（--home/--port 由 JVM 侧 parseGlobalFlags 从 intent 校验兜底）；stdout/
   * stderr 重定向 restart.log（RestartHelper :52-54 先例——防管道断连，旧退后后继
   * 被 launchd 收养）；working dir = user.home。spawn 异常（EPERM 沙箱等）→ Left，
   * 编排器走零损失中止（沙箱场景降级 = fail-safe 保持服务，绝不硬闯）。
   */
  def defaultSpawn(intent: HotRestartIntent): IO[Either[String, SpawnedProcess]] =
    defaultSpawnWithJar(intent, AutoStartService.resolveRunJar())

  /**
   * 同 [[defaultSpawn]]，但**显式指定包路径**（hotupdate 批 2 G4 回滚复起用：只替换包
   * 路径、其余逐字相同）。既有调用方（[[defaultSpawn]]）取「本进程运行时解析出的包」，
   * 行为逐字节不变。
   */
  def defaultSpawnWithJar(
    intent: HotRestartIntent,
    jarPath: Option[String]
  ): IO[Either[String, SpawnedProcess]] =
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
        }.attempt
          .map {
            case Left(NonFatal(e)) =>
              Left(s"spawn failed: ${e.getMessage} (sandbox? form=${intent.form} cmd=${parts.mkString(" ")})")
            case Left(e) => Left(s"spawn failed: ${e.getMessage}")
            case Right(r) => r
          }
    end match
  end defaultSpawnWithJar
end HotRestart

/** 重启模式（设计 §3.2：编排器与触发源解耦；§7 拍板项 2 建议 UI 默认 WaitIdle）。 */
enum RestartMode:
  /** 忙 → 拒绝并回报五域明细。 */
  case RejectIfBusy

  /** 忙 → 排队等空闲（draining 不置位，每轮重判），上限 timeoutMs。 */
  case WaitIdle(timeoutMs: Long)
