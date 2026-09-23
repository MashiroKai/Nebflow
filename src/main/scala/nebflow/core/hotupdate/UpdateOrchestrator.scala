package nebflow.core.hotupdate

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.core.hotrestart.{HotRestart, RestartMode}
import nebflow.neblink.RemoteUpdateAction

import java.util.concurrent.atomic.AtomicReference

/**
 * 统一更新编排器（hotupdate 批 1 · 设计 §4 设计 A）：所有入口只发「更新请求」，
 * 由它独占执行。本件是**串链件**——每一个相位都落到既有复用点上，零平行机制：
 *
 * | 相位 | 复用点 | 落点 |
 * |---|---|---|
 * | 检查 Checking | 版本比对逻辑（附录 B #29） | [[VersionCheck]]（与设置页检查按钮同源） |
 * | 准备 Preparing | 无（薄：受理后的版本上下文） | 本件 |
 * | 冻结 Freezing | 五域判定 #1 + 准入闸 #3 + 排空收敛环含 15s 观察窗 #4 | `HotRestart.drainForUpdate` |
 * | 更新 Updating | **安装动作本体 #26** | `RemoteUpdateAction.runInstallScript` |
 * | 重启 Restarting | 八步第 4–8 步（意图 #6 / 派生 #24 / 两段确认 #7 #8 / 优雅让渡 #9） | `HotRestart.requestRestart`（**一律委托，不复制**） |
 * | 恢复 Recovering | 后继进程自己走既有开机链 #16 #18（本件零新增） | — |
 * | 中止 Aborted | 中止路径 #10（旧实例继续服务、零停机） | `HotRestart.releaseDrain` |
 * | 已回滚 RolledBack | **批 2**（G3/G4）：本批只建态，绝不进入 | — |
 *
 * 幂等**双层**（设计 §4）：幂等键去重（同键重复到达 ⇒ 「已在途 + 当前相位」）+
 * 进程内占位（复用 #2 占位语义：在途即合并，**不排第二个**）。
 * 并发门：更新**排他、无队列** ⇒ 异键在途立即回「更新中」+ 相位 + 来源。
 *
 * 顺序正确性（设计 §4 论证）：编排器的派生目标是运行时解析出的包，而安装脚本启动器
 * 用数值排序取**最新**包（`release/install.sh:1024-1068`）⇒ 装完即指新版 ⇒
 * **派生逻辑零改动**（本件对 `HotRestart` 派生面的唯一新增是窄入口，见其注释）。
 *
 * 纪律：本件不触碰「真装 / 真重启」——安装动作与派生面都是既有件，验证批（批 4）才做
 * 真链；健康自检与回滚动作属批 2。
 */
class UpdateOrchestrator(
  /**
   * 既有热重启编排器（D1：**重启相位一律委托它**；排空/翻态/派生/握手一律不复制）。
   * None = 本实例未装配该能力 ⇒ 更新在中止相位收口（旧实例继续服务）。
   */
  hotRestart: Option[HotRestart],
  /** 进度广播（复用点 #11：既有广播通道的构造点 `GatewayMain.scala:689-694`）。 */
  broadcast: Json => IO[Unit],
  /** 当前版本（唯一来源 `nebflow.Version.string`，与设置页检查链同源）。 */
  currentVersion: () => String = () => nebflow.Version.string,
  /** 最新版本（复用点 #29 的指针读取，唯一实现 = [[VersionCheck]]）。 */
  latestVersion: () => IO[Option[String]] = () => VersionCheck.fetchLatestVersion(),
  /**
   * **安装动作本体（复用点 #26）**：默认 = `RemoteUpdateAction.runInstallScript`
   * （`neblink/RemoteUpdateAction.scala`，设备列表 / 中继 / 设置页三处共用同一动作）。
   * 🔴 禁另写第二套安装动作——本参数只用于测试注入（计数 / 良性命中）。
   * 经环境变量 `NEBFLOW_HOTUPDATE_INSTALL_COMMAND` 或系统属性
   * `nebflow.hotupdate.installCommand` 注入时，以该**良性命令**替换真实安装脚本
   * （验证用注入面，既有 `HotRestart.Timing.fromSystemProps` 同款口径；生产未设 ⇒ 真实脚本）。
   */
  install: UpdateChannel => IO[Either[String, String]] = UpdateOrchestrator.defaultInstall,
  /** 幂等键保留窗口（本批冻结值 300s，契约对齐点 ④）——见 [[UpdateDefaults]]。 */
  idempotencyWindowMs: Long = UpdateDefaults.idempotencyKeyWindowMs
):

  private val logger = NebflowLogger.forName("nebflow.hotupdate")

  /**
   * 进程内占位 + 幂等键锚点（复用 #2 的占位语义）。
   *
   * 放实例内（非伴随对象）：编排器随实例装配一次，无跨实例共享需求
   * （远端设备由**它自己的实例**守，设计 §4 并发门）。
   */
  private val activeRef: Ref[IO, Option[ActiveUpdate]] =
    Ref.unsafe[IO, Option[ActiveUpdate]](None)

  /** 现读编排器状态（读面，非端点）：None = 空闲。 */
  def status: IO[Option[ActiveUpdate]] = activeRef.get

  /**
   * 受理更新请求。判据顺序（与既有重启命令同构）：
   *   1. 缺确认位 ⇒ [[UpdateAdmission.Refused]]（可行动错误，**不占位、不执行**）；
   *   2. 同键在途 ⇒ [[UpdateAdmission.AlreadyInFlight]]（已在途 + 当前相位）；
   *   3. 异键在途 ⇒ [[UpdateAdmission.Busy]]（更新中 + 相位 + 来源，**不排队**）；
   *   4. 空闲 ⇒ [[UpdateAdmission.Accepted]] + 执行体 fork（进度经统一帧广播）。
   *
   * `onInstallOutcome` = 安装相位的回执钩子（调用方发**既有完成帧**保持向后兼容；
   * 默认 no-op）。
   */
  def request(
    req: UpdateRequest,
    onInstallOutcome: Either[String, String] => IO[Unit] = _ => IO.unit
  ): IO[UpdateAdmission] =
    if !req.confirm then
      IO.pure(
        UpdateAdmission.Refused(
          UpdateReason.ConfirmMissing,
          "update requires confirm=true — an update installs a new package on disk and then restarts this instance once current work finishes"
        )
      )
    else
      val key = req.effectiveKey
      val now = System.currentTimeMillis()
      activeRef
        .modify { cur =>
          cur match
            case Some(c) if c.key == key && c.heldAt(now, idempotencyWindowMs) =>
              (cur, (None: Option[ActiveUpdate], UpdateAdmission.AlreadyInFlight(key, c.phase)))
            case Some(c) if c.key == key =>
              // 窗口外：键已过期（成功终态后的保留窗口过去）⇒ 重新受理同一键
              val run = new ActiveUpdate(key, req.source, req.channel)
              (Some(run), (Some(run), UpdateAdmission.Accepted(key)))
            case Some(c) =>
              (cur, (None, UpdateAdmission.Busy(c.phase, c.source, c.key)))
            case None =>
              val run = new ActiveUpdate(key, req.source, req.channel)
              (Some(run), (Some(run), UpdateAdmission.Accepted(key)))
        }
        .flatMap { case (fresh, admission) =>
          fresh match
            case Some(run) =>
              runChain(run, req, onInstallOutcome)
                .handleErrorWith(e =>
                  // 兜底：执行体自身异常也必须收敛到中止相位（旧实例继续服务），
                  // 绝不留下「占位不释放 + 准入闸未清」的僵局。
                  abort(run, UpdateReason.UnexpectedError, s"unexpected orchestrator error: ${msg(e)}", None)
                )
                .start
                .as(admission)
            case None => IO.pure(admission)
        }

  // ===== 执行链（fork fiber 上跑；相位逐条广播统一进度帧）=====

  private def runChain(
    run: ActiveUpdate,
    req: UpdateRequest,
    onInstallOutcome: Either[String, String] => IO[Unit]
  ): IO[Unit] =
    for
      _ <- step(run, UpdatePhase.Checking, s"checking the release pointer (current version ${currentVersion()})", None)
      latest <- latestVersion()
      _ <- step(
        run,
        UpdatePhase.Preparing,
        latest.fold(
          "release pointer unreachable — proceeding to the install action (same fail-soft behaviour as before)"
        )(v => s"latest version on the release pointer: $v"),
        latest
      )
      // 冻结：先读一次在飞明细（五域判定 #1，只读），再委托既有排空环（#4）
      inFlight <- (hotRestart match
        case Some(hr) => hr.quiesceReport.map(q => if q.isIdle then "no in-flight work" else q.detail)
        case None => IO.pure("hot-restart orchestrator unavailable in this instance")
      )
      _ <- step(
        run,
        UpdatePhase.Freezing,
        s"freezing: closing work admission (mode=${modeToken(req.mode)}); in-flight now: $inFlight",
        latest
      )
      frozen <- (hotRestart match
        case Some(hr) => hr.drainForUpdate(req.mode)
        case None =>
          IO.pure(
            Left(HotRestart.DrainFailure.Busy("hot-restart orchestrator is unavailable in this instance")): Either[
              HotRestart.DrainFailure,
              Unit
            ]
          )
      )
      // 裁定 4：排空超时 / 忙 = **中止**（不强制、不翻态）——旧实例继续服务
      _ <- frozen.fold(
        failure => abort(run, reasonOf(failure), s"freeze: ${failure.detailText}", latest),
        _ => runInstallPhase(run, req, latest, onInstallOutcome)
      )
    yield ()

  private def runInstallPhase(
    run: ActiveUpdate,
    req: UpdateRequest,
    latest: Option[String],
    onInstallOutcome: Either[String, String] => IO[Unit]
  ): IO[Unit] =
    step(
      run,
      UpdatePhase.Updating,
      s"running the install action (channel=${req.channel.wire})",
      latest
    ) *>
      install(req.channel).attempt
        .map {
          case Right(result) => result
          case Left(e) => Left(s"install action raised: ${msg(e)}")
        }
        .flatMap {
          case Left(err) =>
            // 既有完成帧（向后兼容）：失败分支
            onInstallOutcome(Left(err)) *>
              abort(run, UpdateReason.InstallFailed, s"install failed: $err", latest)
          case Right(ok) =>
            // 既有完成帧（向后兼容）：成功分支（安装动作本体 #26 的文案）
            onInstallOutcome(Right(ok)) *> runRestartPhase(run, req, latest)
        }

  private def runRestartPhase(
    run: ActiveUpdate,
    req: UpdateRequest,
    latest: Option[String]
  ): IO[Unit] =
    step(
      run,
      UpdatePhase.Restarting,
      "package installed — delegating the restart phase to the existing hot-restart orchestrator " +
        "(drain / flip / spawn / handshake are not re-implemented here)",
      latest
    ) *>
      (hotRestart match
        case None =>
          abort(
            run,
            UpdateReason.OrchestratorUnavailable,
            "hot-restart orchestrator is unavailable in this instance",
            latest
          )
        case Some(hr) =>
          // G0 收口（批 2）：走**窄入口**（`requestRestartAwaitingHandover`）——它在
          // 「四档健康自检通过且已触发优雅让渡」时才回 Right；中止（含自检失败）回
          // 类型化失败。⇒ 本件不再把「交接已受理」当「已完成」（批 1 verify §11② 的
          // 残余可见性：显示已完成而实际未起）。既有 WS 重启命令走 `requestRestart`，
          // 语义零变；相位仍只有 `step` 一个改动点（帧契约逐字不变）。
          hr.requestRestartAwaitingHandover(source = s"update:${run.source.wire}", mode = req.mode).flatMap {
            case Left(err) =>
              val reason = err.kind match
                case HotRestart.FailureKind.HealthCheckFailed => UpdateReason.HealthCheckFailed
                case _ => UpdateReason.RestartRefused
              abort(run, reason, s"restart phase aborted [${err.kind.wire}]: ${err.reason}", latest)
            case Right(()) =>
              // 恢复相位零新增：后继进程自己走既有开机链（崩溃恢复 #16 + 开机重入 #18）。
              // 完成判定 = 健康自检已通过且交接已触发（旧进程即将优雅退出）。
              step(
                run,
                UpdatePhase.Recovering,
                "handover triggered after the new version passed the four-tier health self-check — the successor process runs the existing boot chain (crash recovery + dispatcher wake); zero new recovery machinery",
                latest
              ) *>
                step(
                  run,
                  UpdatePhase.Completed,
                  s"update completed: package on disk, the new version passed the health self-check and the handover is running (version ${latest.getOrElse("unknown")})",
                  latest,
                  Some(1.0)
                )
          })

  // ===== 相位推进 / 中止 =====

  /** 相位推进 + 日志 + 统一进度帧广播（单点：相位只能经此变更）。 */
  private def step(
    run: ActiveUpdate,
    phase: UpdatePhase,
    detail: String,
    latest: Option[String],
    progress: Option[Double] = None,
    reason: Option[UpdateReason] = None
  ): IO[Unit] =
    IO(run.moveTo(phase)) *>
      (if phase == UpdatePhase.Completed then run.markFinished(System.currentTimeMillis())
       else IO.unit) *>
      logger.info(s"[hotupdate] phase=${phase.wire} key=${run.key} source=${run.source.wire}: $detail") *>
      broadcast(progressFrame(run, phase, detail, latest, progress, reason))

  /**
   * 中止腿（复用中止路径 #10 的语义：清准入闸、**旧实例继续服务**、端口不动、零停机）
   * + 释放占位（中止后可重试）。不强制翻态、不写失败态——排空中断的任何工作由既有
   * 优雅中断钩子（#14 #15）翻「已中断」非终态，本件零接触。
   */
  private def abort(
    run: ActiveUpdate,
    reason: UpdateReason,
    detail: String,
    latest: Option[String]
  ): IO[Unit] =
    hotRestart.traverse_(_.releaseDrain()) *>
      releasePlaceholder(run) *>
      logger.error(
        s"[hotupdate] UPDATE ABORTED [${reason.wire}]: $detail — old instance keeps serving (admission reopened, zero downtime)"
      ) *>
      step(
        run,
        UpdatePhase.Aborted,
        s"update aborted: $detail — this instance keeps serving",
        latest,
        reason = Some(reason)
      )

  private def releasePlaceholder(run: ActiveUpdate): IO[Unit] =
    activeRef.update {
      case Some(cur) if cur eq run => None
      case other => other
    }

  /**
   * 统一进度帧（设计 §4）：相位 / 详情 / 来源 / 幂等键 / 当前版本 / 最新版本 / 可选进度
   * + `messageKey`（裁定 10 / D6：展示文案一律走文案键，帧内不携带展示文案）。
   * `detail` 是**诊断明细**（复用既有件产生的事实描述，既有 `restartStatus.detail` 同款），
   * 不是展示文案。
   */
  private def progressFrame(
    run: ActiveUpdate,
    phase: UpdatePhase,
    detail: String,
    latest: Option[String],
    progress: Option[Double],
    reason: Option[UpdateReason]
  ): Json =
    Json.obj(
      "type" -> "updateProgress".asJson,
      "phase" -> phase.wire.asJson,
      "messageKey" -> phase.messageKey.asJson,
      "state" -> phase.stateToken.asJson,
      // 原因字段（契约对齐点 ②：失败相位以原因承载，字面 = UpdateReason 冻结枚举；
      // 非失败相位为 null——不预设拼写、不用相位字段拼文案）
      "reason" -> reason.map(_.wire).asJson,
      "detail" -> detail.asJson,
      "source" -> run.source.wire.asJson,
      "channel" -> run.channel.wire.asJson,
      "idempotencyKey" -> run.key.asJson,
      "currentVersion" -> currentVersion().asJson,
      "latestVersion" -> latest.asJson,
      "progress" -> progress.asJson
    )

  /** 冻结原因映射（本件内单点）：既有编排器的排空失败类别 → 编排层原因字面。 */
  private def reasonOf(failure: HotRestart.DrainFailure): UpdateReason = failure match
    case HotRestart.DrainFailure.Busy(_) => UpdateReason.FreezeBusy
    case HotRestart.DrainFailure.WaitLimitExceeded(_, _) => UpdateReason.FreezeWaitLimitExceeded
    case HotRestart.DrainFailure.DeadlineExceeded(_) => UpdateReason.FreezeDrainDeadlineExceeded

  private def modeToken(mode: RestartMode): String = mode match
    case RestartMode.RejectIfBusy => "reject-if-busy"
    case RestartMode.WaitIdle(ms) => s"wait-idle(${ms / 1000}s)"

  private def msg(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)
end UpdateOrchestrator

/**
 * 在途更新（占位 + 幂等键锚点 + 现读相位）。相位存 AtomicReference（既有
 * `ShutdownState` 的 AtomicBoolean 先例）：使「同键重复到达 ⇒ 回当前相位」这一次读取
 * 是纯读，不需要在 `Ref.modify` 内做 IO。
 */
final class ActiveUpdate private[hotupdate] (
  val key: String,
  val source: UpdateSource,
  val channel: UpdateChannel
):
  private val phaseRef = new AtomicReference[UpdatePhase](UpdatePhase.Idle)

  /** 成功终态时刻（0 = 未终态）——幂等键保留窗口的起算点（契约对齐点 ④）。 */
  private val finishedAtRef = new AtomicReference[Long](0L)

  def phase: UpdatePhase = phaseRef.get()

  private[hotupdate] def moveTo(p: UpdatePhase): Unit = phaseRef.set(p)

  private[hotupdate] def markFinished(nowMs: Long): IO[Unit] =
    IO(finishedAtRef.set(nowMs)).void

  /**
   * 该键此刻是否仍被保留（占用 / 成功终态且在窗口内）。
   * 在途（finishedAt == 0）恒保留；成功终态后保留 idempotencyWindowMs；中止腿会直接
   * 释放占位（本方法不再被调用到）。
   */
  private[hotupdate] def heldAt(nowMs: Long, windowMs: Long): Boolean =
    val fin = finishedAtRef.get()
    fin == 0L || (nowMs - fin) <= windowMs

end ActiveUpdate

object UpdateOrchestrator:

  private val logger = NebflowLogger.forName("nebflow.hotupdate")

  /**
   * **安装动作（复用点 #26）**的默认实现 = 既有 `RemoteUpdateAction.runInstallScript`。
   *
   * 注入面（验证用）：环境变量 `NEBFLOW_HOTUPDATE_INSTALL_COMMAND` 或系统属性
   * `nebflow.hotupdate.installCommand` 非空 ⇒ 以该**良性命令**替换真实安装脚本，
   * 并大声记 WARN（生产未设 ⇒ 真实安装脚本，逐字节沿用既有面）。
   * 与 D1「禁另写第二套安装动作」不冲突：这里换的是**命令文本**，不是安装动作本体的
   * 第二实现——安装脚本的取值、msi 形态守卫、退出码语义仍在 `RemoteUpdateAction` 单一处。
   */
  def defaultInstall(channel: UpdateChannel): IO[Either[String, String]] =
    val overrideCmd =
      sys.props
        .get("nebflow.hotupdate.installCommand")
        .map(_.trim)
        .filter(_.nonEmpty)
        .orElse(sys.env.get("NEBFLOW_HOTUPDATE_INSTALL_COMMAND").map(_.trim).filter(_.nonEmpty))
    overrideCmd match
      case None => RemoteUpdateAction.runInstallScript(channel == UpdateChannel.Beta)
      case Some(cmd) =>
        logger.warn(
          s"[hotupdate] install command OVERRIDDEN by verification injection: $cmd — the real install script is NOT run"
        )
        IO.blocking {
          import sys.process.*
          Seq("/bin/sh", "-c", cmd).!
        }.map {
          case 0 => Right("verification install command succeeded")
          case code => Left(s"verification install command failed (exit code: $code)")
        }.handleErrorWith(e =>
          IO.pure(Left(s"verification install command error: ${Option(e.getMessage).getOrElse(e.toString)}"))
        )

    end match

  end defaultInstall

  /** 受理/合并/忙/拒绝的 WS 回执帧（单一来源；None = 受理 ⇒ 调用方只发既有开始帧）。 */
  def admissionFrame(admission: UpdateAdmission): Option[Json] =
    admission match
      case UpdateAdmission.Accepted(_) => None
      case UpdateAdmission.AlreadyInFlight(key, phase) =>
        Some(
          Json.obj(
            "type" -> "updateResult".asJson,
            "accepted" -> false.asJson,
            "status" -> "already-in-flight".asJson,
            "phase" -> phase.wire.asJson,
            "messageKey" -> phase.messageKey.asJson,
            "idempotencyKey" -> key.asJson,
            "detail" -> "an update with the same idempotency key is already in flight (merged, not queued)".asJson
          )
        )
      case UpdateAdmission.Busy(phase, source, key) =>
        Some(
          Json.obj(
            "type" -> "updateResult".asJson,
            "accepted" -> false.asJson,
            "status" -> "busy".asJson,
            "phase" -> phase.wire.asJson,
            "messageKey" -> phase.messageKey.asJson,
            "source" -> source.wire.asJson,
            "idempotencyKey" -> key.asJson,
            "detail" -> "an update is already running — updates are exclusive and never queued".asJson
          )
        )
      case UpdateAdmission.Refused(reason, detail) =>
        Some(
          Json.obj(
            "type" -> "updateResult".asJson,
            "accepted" -> false.asJson,
            "status" -> "refused".asJson,
            "reason" -> reason.wire.asJson,
            "error" -> detail.asJson
          )
        )
end UpdateOrchestrator
