package nebflow.core.hotupdate

import cats.effect.{Fiber, IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.shared.{NebflowLogger, PathUtil}

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.*

/**
 * 全设备统一更新 · 编排（fleet coordinator）——**调度与可见性层**，安装语义零复制。
 *
 * 一条命令把「本机 + 名册里所有可达设备」收敛到同一版本，并全程给出可读状态：
 *
 * ```
 * GET  /api/update/fleet          计划 + 运行态快照（版本核对表 / 收口四句）
 * POST /api/update/fleet          发起（必带 confirm=true；audit 留痕）
 * POST /api/update/fleet/cancel   中断（作者可感知、可中断——绝不静默批量重启）
 * POST /api/update/fleet/retry    一键重试失败项（不可重试档自动跳过）
 * ```
 *
 * ## 复用面（禁双实现，逐条）
 * | 面 | 复用点 |
 * |---|---|
 * | 本机装包 + 重启 + 健康自检 + 回滚 | 既有 [[UpdateOrchestrator]] 七相位（一律 `request`，不复制相位逻辑） |
 * | 远端推送 | 既有 `RemoteUpdate` 动作（P2P `/api/neblink/update` → 中继 `relayUpdate`，入口由装配方注入） |
 * | 版本序 | [[VersionCheck.compareVersions]]（与 link-server `cmp_versions` 同构的单实现） |
 * | 版本 / 能力探测 | 既有 `/api/health` 载荷（本批在其中**加** `fleet` 键自报，见 HealthPayload） |
 * | 在线态 | 装配方按 `NeblinkService.isPeerOnline` 判好传入（本件不重算新鲜度窗口） |
 *
 * ## 分发（作者令四条分支，见 [[FleetDispatch]]）
 * 在线 ∧ 支持 ⇒ 推；在线 ∧ 不支持 ⇒ 不推 + 手动指引；离线 ∧ 支持 ⇒ 排队待上线；
 * 离线 ∧ 不支持 ⇒ 一起入「需手动更新」清单。
 *
 * ## 哨兵（作者令「离线设备上线时自动补推」）
 * [[onPeerOnline]] 是唯一哨兵入口，装配方挂在既有 peer 变更回调上（`NeblinkService
 * .addPeerChangeCallback`）。语义 = **上线即核版本 → 核过就推**：不是「无条件补推」，
 * 而是重跑一次能力闸（对端可能已手动更新过 ⇒ 已一致 ⇒ 只标记完成，不重复推送）。
 * 零新增定时器（离线队列只在既有 peer 事件/心跳腿上被消费，与既有收敛闭环同纪律）。
 *
 * ## 中断（作者令「不允许静默批量重启」「可感知、可中断」）
 * `cancel` 置位后**不再发起新推送**；已在飞的单机不可撤回（远端安装进程已经在跑），
 * 如实标注「已发起，不可撤回」。本机腿中断 = 不发起（既有编排器不受影响）。
 *
 * ## 单机失败隔离
 * 每台设备一个独立 fiber + `handleErrorWith` 收敛；任一失败只写自己那一行，
 * 不打断其他设备，也不把整轮判死（收口 = [[FleetJobStatus.Partial]]）。
 *
 * ## 断线重连 / 重启恢复
 * 状态是服务端内存 + 落盘（`<dataRoot>/hotupdate/fleet-job.json`，AtomicJson 原子写）。
 * 浏览器刷新/WS 重连 ⇒ 快照帧（`fleetUpdate`）+ `GET /api/update/fleet`；引擎重启 ⇒
 * 从落盘恢复上一次任务（`resumed: true`），已在飞的行标 [[FleetStage.DeviceLost]]
 * ——**绝不假装还在跑**。
 */
final class FleetUpdateCoordinator(
  /** 既有七相位编排器（本机腿唯一入口；None = 未装配 ⇒ 本机腿记不可用，不阻塞远端）。 */
  orchestrator: Option[UpdateOrchestrator],
  /** 既有广播通道（`wsHub.broadcast`）——与更新进度帧同一通道（复用点 #11）。 */
  broadcast: Json => IO[Unit],
  /** 设备来源（装配方给：本机 + neblink 名册；在线态在此判好）。 */
  listDevices: () => IO[List[FleetUpdateCoordinator.Peer]],
  /** 单机探测：`/api/health` 的 version + `fleet.hotUpdate` 自报（失败 ⇒ 全 None）。 */
  probe: FleetUpdateCoordinator.Peer => IO[FleetUpdateCoordinator.ProbeResult] = FleetUpdateCoordinator.probeHealth,
  /** 远端推送入口（装配方给；返回 `Left` = 拒/失败，`Right` = 对端已受理并重启）。 */
  pushRemote: (FleetUpdateCoordinator.Peer, Boolean, String) => IO[Either[String, String]] = (_, _, _) =>
    IO.pure(Left("remote push is not wired in this instance")),
  /** 最新版本（复用 [[VersionCheck.fetchLatestVersion]]；None ⇒ 目标版本未知）。 */
  latestVersion: () => IO[Option[String]] = () => VersionCheck.fetchLatestVersion(),
  /** 当前版本（唯一来源 `nebflow.Version.string`）。 */
  currentVersion: () => String = () => nebflow.Version.string,
  /** 本机通道（`nebflow.Version.channel`）。 */
  channel: () => String = () => nebflow.Version.channel,
  /** 远端推送受理后等待「版本变新」的窗（健康自检档）。 */
  verifyWindow: FiniteDuration = FleetUpdateCoordinator.DefaultVerifyWindow,
  /** 探测/自检轮询间隔。 */
  pollInterval: FiniteDuration = FleetUpdateCoordinator.DefaultPollInterval
):

  import FleetUpdateCoordinator.*

  private val logger = NebflowLogger.forName("nebflow.hotupdate.fleet")

  /** 当前态（`None` = 从没跑过；`job` 为 `None` 时只有计划相）。 */
  private val stateRef: Ref[IO, Option[FleetJob]] = Ref.unsafe(None)

  /** 上一轮计划（供快照与哨兵判定使用；`plan` 也现算，这只是缓存）。 */
  private val planRef: Ref[IO, Option[FleetPlan]] = Ref.unsafe(None)

  /** 中断位（作者可中断；一次任务一个位，任务收口后复位）。 */
  private val cancelRef = Ref.unsafe[IO, Boolean](false)

  /** 在飞 fiber（仅用于 cancel 后不再启动新的；不 `cancel` 已发起的远端推送）。 */
  private val inflightRef =
    Ref.unsafe[IO, Map[String, Fiber[IO, Throwable, Unit]]](Map.empty)

  private val seq = new java.util.concurrent.atomic.AtomicLong(0L)

  /**
   * 最近一次 `plan` 的**名册快照**（deviceId → 装配方给的对端条目）。
   * 为什么需要：计划行（[[FleetDeviceView]]）刻意不带地址（地址不是展示面），
   * 而推送腿要地址 ⇒ 执行时按 deviceId 从这里取，**不重新探测**（避免两次 plan
   * 之间名册抖动造成「计划说有、执行时找不到」的静默错位）。
   */
  private val rosterRef: Ref[IO, Map[String, Peer]] = Ref.unsafe(Map.empty)

  // ── 读面 ──────────────────────────────────────────────────────────────

  /** 计划（版本核对表 + 收口状态）：现算（探测走网络，装配方负责别每帧都调）。 */
  def plan(beta: Boolean = false): IO[FleetPlan] =
    for
      peers <- listDevices().handleErrorWith(_ => IO.pure(Nil))
      target <- latestVersion().handleErrorWith(_ => IO.pure(None))
      probed <- peers.parTraverse { p =>
        // 本机行不探网络（自己就在进程里）：版本现读、能力 = 本实例是否真能热更
        // （`orchestrator.isDefined`）。禁把「本机能热更」当作自报字段——那是
        // 「本进程确有编排器」的直接事实，与远端 `/api/health` 自报同义。
        val eff =
          if p.isLocal then IO.pure(ProbeResult(Some(currentVersion()), orchestrator.isDefined))
          else probe(p)
        eff.map(r => p -> r).handleErrorWith(_ => IO.pure(p -> ProbeResult(None, false)))
      }
      views = probed.map { case (p, r) =>
        val v = UpdateCapability.verdict(r.version.orElse(p.version), r.selfReported)
        (
          FleetDeviceView(
            deviceId = p.deviceId,
            deviceName = p.deviceName,
            platform = p.platform,
            isLocal = p.isLocal,
            online = p.online,
            version = r.version.orElse(p.version),
            capability = v,
            branch = FleetDispatch.Branch.Manual, // build() 会按在线态与能力重算
            reason = UpdateCapability.reasonOf(v, r.version.orElse(p.version))
          ),
          p.online
        )
      }
      built = FleetPlan.build(views, target)
      _ <- planRef.set(Some(built))
      _ <- rosterRef.set(peers.map(p => p.deviceId -> p).toMap)
    yield built

  /** 快照（HTTP + WS 首帧共用；`resumed` 标记 = 落盘恢复的上一轮任务）。 */
  def snapshot: IO[Json] =
    for
      job <- stateRef.get
      inflight <- inflightRef.get.map(_.keySet.toList.sorted)
    yield Json.obj(
      "type" -> "fleetUpdate".asJson,
      "minRemoteVersion" -> UpdateCapability.MinRemoteVersion.asJson,
      "protocol" -> UpdateCapability.Protocol.asJson,
      "currentVersion" -> currentVersion().asJson,
      "job" -> job.map(_.toJson).getOrElse(Json.Null),
      "inflight" -> inflight.asJson
    )

  // ── 发起 ──────────────────────────────────────────────────────────────

  /**
   * 发起一次全设备统一更新。
   *
   * 判据顺序（与既有编排器同构）：缺确认位 ⇒ `Left`（可行动错误，**不占位不执行**）；
   * 已有在飞任务 ⇒ `Left`（fleet 是排他动作，不排队）；否则受理并 fork 执行体。
   */
  def start(confirm: Boolean, source: String, beta: Boolean = false): IO[Either[String, FleetJob]] =
    if !confirm then
      IO.pure(
        Left(
          "fleet update requires confirm=true — it installs a new package on every reachable device " +
            "and restarts each one once its current work finishes"
        )
      )
    else
      stateRef.get.flatMap {
        case Some(j) if j.status == FleetJobStatus.Running =>
          IO.pure(Left(s"a fleet update is already running (job ${j.id}) — it is exclusive and never queued"))
        case _ =>
          for
            _ <- cancelRef.set(false)
            p <- plan(beta)
            now = System.currentTimeMillis()
            id = s"fleet-${now}-${seq.incrementAndGet()}"
            runs = p.devices.map(d => initialRun(d, now))
            job = FleetJob(
              id = id,
              startedAt = now,
              finishedAt = 0L,
              source = source,
              targetVersion = p.targetVersion,
              status = FleetJobStatus.Running,
              runs = runs,
              manual = p.devices.filter(_.branch == FleetDispatch.Branch.Manual),
              pending = p.devices.filter(_.branch == FleetDispatch.Branch.Queued)
            )
            _ <- stateRef.set(Some(job))
            _ <- persist(job)
            _ <- audit(
              AuditEntry(
                now,
                "fleet-start",
                id,
                source,
                s"devices=${p.devices.length} push=${p.summary.pushCount} manual=${p.summary.manualCount} " +
                  s"queued=${p.summary.pendingOnlineCount} target=${p.targetVersion.getOrElse("unknown")}"
              )
            )
            _ <- broadcastState()
            _ <- execute(job, p, beta).start.flatMap(f => inflightRef.update(_ + (id -> f)))
          yield Right(job)
      }

  /** 执行体（fork 上跑）：逐设备并发（每台独立 fiber + 独立终态），受中断位约束。 */
  private def execute(job: FleetJob, plan: FleetPlan, beta: Boolean): IO[Unit] =
    for
      _ <- plan.devices
        .filter(d => d.branch == FleetDispatch.Branch.Push)
        .parTraverse_ { d =>
          cancelRef.get.flatMap {
            case true => markStage(job.id, d.deviceId, FleetStage.Cancelled, None, "cancelled before dispatch")
            case false =>
              runOneDevice(job, d, beta)
                .handleErrorWith(e => markFailed(job.id, d.deviceId, FleetFailure.Unknown, msg(e)))
          }
        }
      _ <- finalize(job.id)
    yield ()

  /** 单机腿：推送 → 等版本变新 → 完成 / 失败（分类见 [[FleetFailure]]）。 */
  private def runOneDevice(
    job: FleetJob,
    d: FleetDeviceView,
    beta: Boolean,
    addressOverride: Option[String] = None
  ): IO[Unit] =
    // 地址只从最近一次 plan 的名册快照取（计划行不带地址——地址不是展示面）；
    // `addressOverride` = 调用侧刚做到的一次新鲜名册读数（哨兵/重试腿用）。
    rosterRef.get.flatMap { byId =>
      val peer = Peer(
        deviceId = d.deviceId,
        deviceName = d.deviceName,
        platform = d.platform,
        address = addressOverride.orElse(byId.get(d.deviceId).map(_.address)).getOrElse(""),
        online = true,
        version = d.version,
        isLocal = d.isLocal,
        localRequest = None
      )
      for
        // 🔴 已一致的设备**不推**（e2e 实测抓到过）：作者令的一致性目标是「全部收敛到
        // 同一版本」，重复按「全部更新」时已经同版的设备再装一次只会得到一个 5 分钟的
        // 假失败（装完版本号不变 ⇒ 自检窗里等不到版本变新 ⇒ 被判 healthcheck-failed）。
        // 判据复用纯逻辑层的 `versionState`（目标版本未知 ⇒ unknown ⇒ 照常推）。
        _ <-
          if d.versionState == "in-sync" then
            markStage(
              job.id,
              d.deviceId,
              FleetStage.Completed,
              Some(1.0),
              s"already on the target version ${d.version.getOrElse("?")} — nothing to install"
            )
          else
            markStage(
              job.id,
              d.deviceId,
              FleetStage.Preparing,
              None,
              if d.isLocal then "local instance — handing the request to the update orchestrator"
              else s"dispatching to ${d.deviceName} over the existing remote-update action"
            )
        _ <-
          if d.versionState == "in-sync" then IO.unit
          else if d.isLocal then runLocal(job, d, beta)
          else if peer.address.isEmpty then
            markFailed(
              job.id,
              d.deviceId,
              FleetFailure.DeviceLost,
              "device has no reachable address in the roster"
            )
          else runRemote(job, d, peer, beta)
      yield ()
      end for
    }

  /** 本机腿：交既有编排器（相位经 [[onLocalPhase]] 映射到本卡；此处只等结果）。 */
  private def runLocal(job: FleetJob, d: FleetDeviceView, beta: Boolean): IO[Unit] =
    orchestrator match
      case None =>
        markFailed(job.id, d.deviceId, FleetFailure.DeviceRefused, "no update orchestrator wired in this instance")
      case Some(orch) =>
        val req = UpdateRequest(
          source = UpdateSource.DeviceList,
          confirm = true,
          channel = if beta then UpdateChannel.Beta else UpdateChannel.Stable,
          idempotencyKey = Some(s"fleet:${job.id}:${d.deviceId}")
        )
        orch
          .request(
            req,
            onInstallOutcome = {
              case Right(_) => IO.unit
              // 安装相位的失败原因必须与远端腿走**同一个分类器**（`FleetFailureClassify`）：
              // 本机腿的取件校验失败（manifest sha256/尺寸不符）此前被一律记成
              // `download-failed`，与远端腿对同一事件的 `verify-failed` 分类不一致——
              // 作者令要求「失败分五类各给建议」，同一原因在两台设备上给出两种建议 =
              // 分类面失真。分类单点复用的判据见 `FleetFailureClassify` 文件头。
              case Left(err) => markFailed(job.id, d.deviceId, FleetFailureClassify.of(err), err)
            }
          )
          .flatMap {
            case UpdateAdmission.Refused(reason, detail) =>
              markFailed(
                job.id,
                d.deviceId,
                if reason == UpdateReason.InstallFailed then FleetFailureClassify.of(detail) else mapRefusal(reason),
                detail
              )
            case UpdateAdmission.Busy(phase, source, _) =>
              markFailed(
                job.id,
                d.deviceId,
                FleetFailure.DeviceRefused,
                s"another update is already running (phase=${phase.wire}, source=${source.wire})"
              )
            case UpdateAdmission.AlreadyInFlight(_, phase) =>
              markStage(job.id, d.deviceId, mapPhase(phase), None, s"already in flight (phase=${phase.wire})")
            case UpdateAdmission.Accepted(_) =>
              // 本机重启前不会自己走到 completed；接受即交棒——终态由后继进程的版本读数收口
              IO.unit
          }

  /** 远端腿：既有动作推送 → 等对端版本变新（健康自检档）→ 完成 / 分类失败。 */
  private def runRemote(job: FleetJob, d: FleetDeviceView, peer: Peer, beta: Boolean): IO[Unit] =
    val idemKey = s"fleet:${job.id}:${d.deviceId}"
    pushRemote(peer, beta, idemKey).flatMap {
      case Left(err) =>
        // 分类走纯逻辑层单点（`FleetFailureClassify`，可单测）——执行体里禁再写第二
        // 份 if-链（同构判据两处写 = 迟早漂移）。
        markFailed(job.id, d.deviceId, FleetFailureClassify.of(err), err)
      case Right(msg) =>
        for
          _ <- markStage(
            job.id,
            d.deviceId,
            FleetStage.Restarting,
            None,
            s"install action accepted on the device ($msg) — waiting for it to come back"
          )
          outcome <- awaitVersionBump(peer, d.version, beta)
          _ <- outcome match
            case Right(v) =>
              markStage(job.id, d.deviceId, FleetStage.Completed, Some(1.0), s"device is back on $v")
            case Left(f) =>
              markFailed(
                job.id,
                d.deviceId,
                f,
                if f == FleetFailure.DeviceLost then
                  "the device did not come back within the verification window (it installed and restarted, or went offline)"
                else
                  "the device came back but still reports a different version (install did not take effect / rolled back)"
              )
        yield ()
    }

  end runRemote

  /** 等对端版本变新（健康自检档）：轮询 `/api/health` 直至版本 ≠ 旧版本或在窗内失联。 */
  private def awaitVersionBump(
    peer: Peer,
    previous: Option[String],
    beta: Boolean
  ): IO[Either[FleetFailure, String]] =
    val deadline = verifyWindow.toMillis
    def loop(elapsed: Long): IO[Either[FleetFailure, String]] =
      probe(peer).flatMap { r =>
        r.version match
          case Some(v)
              if previous.forall(p => VersionCheck.compareVersions(v.stripPrefix("v"), p.stripPrefix("v")) != 0) =>
            IO.pure(Right(v))
          case _ =>
            if elapsed >= deadline then
              // 版本没变：设备没回来 ⇒ 失联；回来了但版本没变 ⇒ 自检没过（安装未生效/回滚）
              probe(peer).flatMap { again =>
                if again.version.isEmpty then IO.pure(Left(FleetFailure.DeviceLost))
                else IO.pure(Left(FleetFailure.HealthCheckFailed))
              }
            else IO.sleep(pollInterval) *> loop(elapsed + pollInterval.toMillis)
      }
    loop(0L)
  end awaitVersionBump

  // ── 中断 / 重试 ────────────────────────────────────────────────────────

  /** 中断：不再发起新推送（已在飞如实标注不可撤回）。 */
  def cancel(): IO[Either[String, Json]] =
    stateRef.get.flatMap {
      case None => IO.pure(Left("no fleet update to cancel"))
      case Some(j) if j.status != FleetJobStatus.Running =>
        IO.pure(Left(s"job ${j.id} already finished (${j.status.wire})"))
      case Some(j) =>
        for
          _ <- cancelRef.set(true)
          _ <- audit(
            AuditEntry(
              System.currentTimeMillis(),
              "fleet-cancel",
              j.id,
              "console",
              "cancel requested — no NEW dispatches will start; already-dispatched device installs cannot be recalled"
            )
          )
          _ <- markRemainingCancelled(j.id)
          _ <- finalize(j.id)
          snap <- snapshot
        yield Right(snap)
    }

  /** 一键重试失败项（不可重试档跳过并如实回报）。 */
  def retryFailed(): IO[Either[String, Json]] =
    stateRef.get.flatMap {
      case None => IO.pure(Left("no fleet update to retry"))
      case Some(j) if j.status == FleetJobStatus.Running =>
        IO.pure(Left(s"job ${j.id} is still running — wait for it to finish"))
      case Some(j) =>
        val failed = j.runs.filter(_.stage == FleetStage.Failed)
        val retryable = failed.filter(_.failure.forall(_.retryable))
        val skipped = failed.filterNot(_.failure.forall(_.retryable))
        if retryable.isEmpty then IO.pure(Left("no retryable failures (rolled-back devices need a manual update)"))
        else
          for
            _ <- cancelRef.set(false)
            now = System.currentTimeMillis()
            _ <- retryable.traverse_(r =>
              bumpAttempt(j.id, r.deviceId) *> markStage(j.id, r.deviceId, FleetStage.Preparing, None, "retrying")
            )
            _ <- audit(
              AuditEntry(
                now,
                "fleet-retry",
                j.id,
                "console",
                s"retrying=${retryable.map(_.deviceName).mkString(",")} skipped=${skipped.map(_.deviceName).mkString(",")}"
              )
            )
            _ <- reopen(j.id)
            _ <- restartFailed(retryable.map(_.deviceId).toSet, j, now)
            snap <- snapshot
          yield Right(snap)
        end if
    }

  private def restartFailed(ids: Set[String], job: FleetJob, now: Long): IO[Unit] =
    for
      peers <- listDevices().handleErrorWith(_ => IO.pure(Nil))
      planDevices <- planRef.get.map(_.map(_.devices).getOrElse(Nil))
      byId = peers.map(p => p.deviceId -> p).toMap
      byPlan = planDevices.map(d => d.deviceId -> d).toMap
      _ <- ids.toList.parTraverse_ { id =>
        byId.get(id) match
          case None => markFailed(job.id, id, FleetFailure.DeviceLost, "device is no longer in the roster")
          case Some(p) =>
            // 🔴 重试腿必须带上「上一次看到的版本」：名册条目（`Peer`）对远端**不带版本**
            // （版本只能靠探测拿），而 `awaitVersionBump(previous = None)` 的判据是
            // `previous.forall(...) = true` ⇒ 推送后第一拍探测到**任何**版本都算「变新」，
            // 一台根本没装成功的设备会被重试腿判成 completed（e2e ④ 实跑抓到）。
            // 判据来源 = 本轮计划的版本读数（`planRef`，与计划相同源），逐级回落到名册。
            val seenVersion = byPlan.get(id).flatMap(_.version).orElse(p.version)
            // 能力判据优先用本轮计划里那台设备的**同一次**读数（同一判据、同一实现）；
            // 计划里没有它（名册抖动）才退回版本门槛，保守侧永远是不支持。
            val cap = byPlan
              .get(id)
              .map(_.capability)
              .getOrElse(UpdateCapability.verdict(seenVersion, selfReported = false))
            val view = FleetDeviceView(
              p.deviceId,
              p.deviceName,
              p.platform,
              p.isLocal,
              p.online,
              seenVersion,
              cap,
              if p.online then FleetDispatch.Branch.Push else FleetDispatch.Branch.Queued,
              None,
              // 目标版本带上 ⇒ `versionState` 有真值：重试时若该设备已经在新版上
              // （上一轮其实装成功了、只是自检窗里没能自证），`runOneDevice` 会直接判
              // 完成而不是再装一次（重复安装 = 又一个假失败）。
              inSyncWith = job.targetVersion
            )
            if !p.online then
              markStage(
                job.id,
                id,
                FleetStage.WaitingOnline,
                None,
                "device is offline — queued for the online sentinel"
              )
            else
              runOneDevice(job, view, beta = false, addressOverride = Some(p.address).filter(_.nonEmpty))
                .handleErrorWith(e => markFailed(job.id, id, FleetFailure.Unknown, msg(e)))
      }
      _ <- finalize(job.id)
    yield ()

  // ── 哨兵（离线设备上线即补推）──────────────────────────────────────────

  /**
   * 上线哨兵（装配方挂在 `NeblinkService.addPeerChangeCallback` 上；也由设备来源的
   * `peerListChanged` 腿调用）。语义 = **上线即核版本 → 核过就推**：
   *   1. 读当前任务；无在飞任务或有任务但该设备不在待推送里 ⇒ 空操作（零开销）；
   *   2. 重新探测该设备版本与能力（对端可能已手动更新 ⇒ 已一致 ⇒ 只标完成）；
   *   3. 能力过闸 ⇒ 推到既有动作；不过闸 ⇒ 移入「需手动更新」清单。
   * 任何内部失败只记日志——**收敛闭环的失败绝不允许打断名册/心跳腿**（既有同纪律）。
   */
  def onPeerOnline(deviceId: String): IO[Unit] =
    stateRef.get
      .flatMap { cur =>
        // 入口留痕（peer 变更事件级，非周期）：修前这里对「无排队行」完全静默，
        // 一台设备上线却不补推时，日志里既没有「哨兵看过」也没有「为什么不动」。
        logger.info(
          s"[fleet] sentinel consulted for $deviceId (job pending: " +
            cur
              .map(j => if j.pending.isEmpty then "none" else j.pending.map(_.deviceId).mkString(","))
              .getOrElse("no job") +
            ")"
        )
        cur match
          case Some(job) if job.pending.exists(_.deviceId == deviceId) =>
            for
              _ <- logger.info(
                s"[fleet] sentinel: $deviceId is queued (job ${job.id}) — re-checking its version before any push"
              )
              peers <- listDevices().handleErrorWith(_ => IO.pure(Nil))
              target = job.targetVersion
              peerOpt = peers.find(_.deviceId == deviceId)
              _ <- peerOpt match
                case None =>
                  // 待上线行却不在名册里：如实留痕（否则「为什么没补推」只能靠猜）
                  logger.info(s"[fleet] sentinel: $deviceId is queued but absent from the roster — nothing to push")
                case Some(p) if !p.online =>
                  logger.info(s"[fleet] sentinel: $deviceId is queued but the roster still reports it offline")
                case Some(p) =>
                  probe(p).flatMap { r =>
                    val v = r.version.orElse(p.version)
                    val cap = UpdateCapability.verdict(v, r.selfReported)
                    if v.exists(x =>
                        target.exists(t => VersionCheck.compareVersions(x.stripPrefix("v"), t.stripPrefix("v")) == 0)
                      )
                    then
                      // 已经一致（对端自己更新过）⇒ 从待推送移出，标记完成（不重复推送）
                      for
                        _ <- removeFromPending(job.id, deviceId)
                        _ <- markStage(
                          job.id,
                          deviceId,
                          FleetStage.Completed,
                          Some(1.0),
                          s"device came online already at $v — no push needed"
                        )
                        _ <- audit(
                          AuditEntry(
                            System.currentTimeMillis(),
                            "fleet-sentinel",
                            job.id,
                            "sentinel",
                            s"$deviceId came online at $v (already consistent) — push skipped"
                          )
                        )
                        _ <- finalize(job.id)
                      yield ()
                    else if cap.supported then
                      for
                        _ <- audit(
                          AuditEntry(
                            System.currentTimeMillis(),
                            "fleet-sentinel",
                            job.id,
                            "sentinel",
                            s"$deviceId came online (version=${v.getOrElse("unknown")}) — pushing queued update"
                          )
                        )
                        _ <- reopen(job.id)
                        _ <- removeFromPending(job.id, deviceId)
                        view = FleetDeviceView(
                          p.deviceId,
                          p.deviceName,
                          p.platform,
                          p.isLocal,
                          true,
                          v,
                          cap,
                          FleetDispatch.Branch.Push,
                          None
                        )
                        _ <- runOneDevice(job, view, beta = false, addressOverride = Some(p.address).filter(_.nonEmpty))
                          .handleErrorWith(e => markFailed(job.id, deviceId, FleetFailure.Unknown, msg(e)))
                        _ <- finalize(job.id)
                      yield ()
                    else
                      // 能力不过闸 ⇒ 转「需手动更新」（离线排队转手动是作者令的明确分支）
                      for
                        _ <- audit(
                          AuditEntry(
                            System.currentTimeMillis(),
                            "fleet-sentinel",
                            job.id,
                            "sentinel",
                            s"$deviceId came online but is not remotely updatable (version=${v.getOrElse("unknown")}) — moved to manual"
                          )
                        )
                        _ <- moveToManual(job.id, view0(p, v, cap))
                        _ <- finalize(job.id)
                      yield ()
                    end if
                  }
            yield ()
          case _ => IO.unit
        end match
      }
      .handleErrorWith(e => logger.warn(s"[fleet] peer-online sentinel failed (ignored): ${msg(e)}"))

  private def view0(p: Peer, v: Option[String], cap: UpdateCapability.Verdict): FleetDeviceView =
    FleetDeviceView(
      p.deviceId,
      p.deviceName,
      p.platform,
      p.isLocal,
      p.online,
      v,
      cap,
      FleetDispatch.Branch.Manual,
      UpdateCapability.reasonOf(cap, v)
    )

  // ── 状态变更（单点；每次变更都广播 + 落盘）──────────────────────────────

  private def initialRun(d: FleetDeviceView, now: Long): FleetDeviceRun =
    val (stage, detail) = d.branch match
      case FleetDispatch.Branch.Manual =>
        (FleetStage.Manual, d.reason.getOrElse("device is not remotely updatable — manual update required"))
      case FleetDispatch.Branch.Queued =>
        (FleetStage.WaitingOnline, "device is offline — queued; it will be pushed when it comes online")
      case FleetDispatch.Branch.Push => (FleetStage.Queued, "queued for dispatch")
    FleetDeviceRun(d.deviceId, d.deviceName, d.isLocal, stage, None, detail, None, 0, now, now)

  private def markStage(
    jobId: String,
    deviceId: String,
    stage: FleetStage,
    progress: Option[Double],
    detail: String
  ): IO[Unit] =
    updateRun(jobId, deviceId) { r =>
      r.copy(
        stage = stage,
        progress = progress,
        detail = detail,
        updatedAt = System.currentTimeMillis(),
        failure = if stage == FleetStage.Failed then r.failure else None
      )
    }

  private def markFailed(jobId: String, deviceId: String, failure: FleetFailure, detail: String): IO[Unit] =
    updateRun(jobId, deviceId) { r =>
      r.copy(
        stage = FleetStage.Failed,
        failure = Some(failure),
        detail = detail,
        progress = None,
        updatedAt = System.currentTimeMillis()
      )
    } *> logger.warn(s"[fleet] device $deviceId failed [${failure.wire}]: $detail") *>
      // 失败也可能落在**收口点之外**（本机腿的安装失败回调 / 哨兵补推腿），所以失败
      // 本身再试一次收口（`finalize` 幂等：还有非终态行就什么都不做）。
      finalize(jobId)

  private def bumpAttempt(jobId: String, deviceId: String): IO[Unit] =
    updateRun(jobId, deviceId)(r => r.copy(attempts = r.attempts + 1))

  private def updateRun(jobId: String, deviceId: String)(f: FleetDeviceRun => FleetDeviceRun): IO[Unit] =
    // 🔴 原子读改写（`modify`）：多台设备并发推进同一任务，`get` + `set` 会**丢更新**
    // （A 的读与 B 的写交错 ⇒ 后写者用旧快照覆盖对方的行，实测表现为某台设备的
    // 「失败」被另一台的「排队」覆盖回去、任务永不收口）。
    mutate(jobId) { j =>
      j.copy(runs = j.runs.map(r => if r.deviceId == deviceId then f(r) else r))
    }

  /**
   * 任务级原子变更的**唯一**入口：`f` 在 `stateRef.modify` 内跑（无交错），
   *  只有真变了才落盘 + 广播。
   */
  private def mutate(jobId: String)(f: FleetJob => FleetJob): IO[Unit] =
    stateRef
      .modify { cur =>
        cur match
          case Some(j) if j.id == jobId =>
            val next = f(j)
            (Some(next), if next == j then None else Some(next))
          case other => (other, None)
      }
      .flatMap {
        case Some(next) => persist(next) *> broadcastState()
        case None => IO.unit
      }

  private def removeFromPending(jobId: String, deviceId: String): IO[Unit] =
    mutate(jobId)(j => j.copy(pending = j.pending.filterNot(_.deviceId == deviceId)))

  /**
   * 重新打开已收口的任务（哨兵补推用）：`WaitingOnline` 是**本轮**的终态，设备上线
   * 补推时任务要回到 running，否则补推结果不会进收口状态（卡上会一直显示上一轮结论）。
   * 只改状态与结束时间，不改任何单机行的语义。
   */
  private def reopen(jobId: String): IO[Unit] =
    mutate(jobId)(j =>
      if j.status != FleetJobStatus.Running then j.copy(status = FleetJobStatus.Running, finishedAt = 0L)
      else j
    )

  private def moveToManual(jobId: String, view: FleetDeviceView): IO[Unit] =
    mutate(jobId) { j =>
      j.copy(
        pending = j.pending.filterNot(_.deviceId == view.deviceId),
        manual = (j.manual.filterNot(_.deviceId == view.deviceId) :+ view),
        runs = j.runs.map(r =>
          if r.deviceId == view.deviceId then
            r.copy(
              stage = FleetStage.Manual,
              detail = view.reason.getOrElse("manual update required"),
              updatedAt = System.currentTimeMillis()
            )
          else r
        )
      )
    }

  private def markRemainingCancelled(jobId: String): IO[Unit] =
    mutate(jobId) { j =>
      val now = System.currentTimeMillis()
      j.copy(runs = j.runs.map { r =>
        if r.stage.terminal then r
        else r.copy(stage = FleetStage.Cancelled, detail = "cancelled by the operator", updatedAt = now)
      })
    }

  /**
   * 收口（幂等）：全部单机行到终态 ⇒ 定状态 + 时间 + 广播 + 审计一行。
   *
   * 🔴 判据与写入必须在**同一次 `modify`** 内（否则两台设备几乎同时到终态时，
   * 两边都用旧快照收口 ⇒ 状态丢失）。
   */
  private def finalize(jobId: String): IO[Unit] =
    val now = System.currentTimeMillis()
    stateRef
      .modify { cur =>
        cur match
          case Some(j) if j.id == jobId && j.status == FleetJobStatus.Running =>
            if j.runs.exists(r => !r.stage.terminal) then (cur, None)
            else
              val next = j.copy(status = closedStatus(j), finishedAt = now)
              (Some(next), Some(next))
          case other => (other, None)
      }
      .flatMap {
        case None => IO.unit
        case Some(done) =>
          val failed = done.runs.count(_.stage == FleetStage.Failed)
          persist(done) *>
            audit(
              AuditEntry(
                now,
                "fleet-finish",
                done.id,
                "coordinator",
                s"status=${done.status.wire} updated=${done.runs.count(_.stage == FleetStage.Completed)} " +
                  s"failed=$failed manual=${done.manual.length} pending=${done.pending.length}"
              )
            ) *>
            broadcastState()
      }

  end finalize

  /**
   * 收口状态判据（单点；`finalize` 与 `restore` 共用，禁两处各写一套）。
   *
   * 🔴 「全部做完」才叫 completed：**失败、需手动、待上线**任何一项非空 ⇒ `partial`。
   * 修前只看失败数 ⇒ 一台「离线排队待补推」的设备会让卡读成「已完成」（收口句写着
   * 「M 台待上线」而状态为 completed，自相矛盾）。作者令的收口三档（全部一致 / N 台
   * 待手动 / M 台待上线）里只有第一档能读成 completed。
   */
  private def closedStatus(job: FleetJob): FleetJobStatus =
    val cancelled = job.runs.count(_.stage == FleetStage.Cancelled)
    val failed = job.runs.count(_.stage == FleetStage.Failed)
    if cancelled > 0 then FleetJobStatus.Cancelled
    else if failed > 0 || job.manual.nonEmpty || job.pending.nonEmpty then FleetJobStatus.Partial
    else FleetJobStatus.Completed

  /** 相位映射（既有编排器七相位 → 本卡七阶段；本机腿用）。 */
  private def mapPhase(p: UpdatePhase): FleetStage = p match
    case UpdatePhase.Idle | UpdatePhase.Checking | UpdatePhase.Preparing => FleetStage.Preparing
    case UpdatePhase.Freezing => FleetStage.Preparing
    case UpdatePhase.Updating => FleetStage.Switching
    case UpdatePhase.Restarting => FleetStage.Restarting
    case UpdatePhase.Recovering => FleetStage.HealthCheck
    case UpdatePhase.Completed => FleetStage.Completed
    case UpdatePhase.Aborted | UpdatePhase.RolledBack => FleetStage.Failed

  private def mapRefusal(reason: UpdateReason): FleetFailure = reason match
    case UpdateReason.InstallFailed => FleetFailure.DownloadFailed
    case UpdateReason.HealthCheckFailed => FleetFailure.HealthCheckFailed
    case UpdateReason.RestartRefused => FleetFailure.DeviceRefused
    case _ => FleetFailure.DeviceRefused

  /**
   * 本机腿相位推进钩子（装配方接在 [[UpdateOrchestrator.onPhase]] 上）：
   * 既有编排器的相位**原样**映射到本卡七阶段——不新增相位逻辑，只做可见性翻译。
   */
  def onLocalPhase(phase: UpdatePhase, progress: Option[Double], detail: String): IO[Unit] =
    stateRef.get.flatMap {
      case Some(j) if j.status == FleetJobStatus.Running =>
        j.runs.find(r => r.isLocal && !r.stage.terminal) match
          case None => IO.unit
          case Some(r) =>
            // 🔴 本机腿在 `runLocal` 里「受理即交棒」（重启前不可能自己等到终态）⇒
            // `execute` 的收口点在那一刻看到的是**非终态行**，绝不会收口。真正把本机行
            // 推到终态的是本钩子，所以收口必须由钩子在**每次**相位推进后再试一次
            // （`finalize` 幂等：还有非终态行就什么都不做）。缺了这一步，本机腿走完的
            // 任务会永远停在 running（卡上一直转圈）——e2e 实测抓到过。
            markStage(j.id, r.deviceId, mapPhase(phase), progress, detail) *> finalize(j.id)
      case _ => IO.unit
    }

  private def broadcastState(): IO[Unit] =
    snapshot.flatMap(broadcast).handleErrorWith(e => logger.warn(s"[fleet] fleetUpdate broadcast failed: ${msg(e)}"))

  // ── 落盘 / 审计 ────────────────────────────────────────────────────────

  private def stateDir: os.Path = PathUtil.dataRoot / "hotupdate"

  private def jobPath: os.Path = stateDir / "fleet-job.json"

  private def auditPath: os.Path = stateDir / "fleet-audit.jsonl"

  private def persist(job: FleetJob): IO[Unit] =
    IO.blocking {
      os.makeDir.all(stateDir)
      nebflow.core.AtomicJson.writeSync(jobPath, job.toJson.noSpaces)
    }.handleErrorWith(e => logger.warn(s"[fleet] persist failed (ignored): ${msg(e)}"))

  /** 审计行（只增 JSONL；作者令「更新动作要有确认与审计」）。 */
  private def audit(entry: AuditEntry): IO[Unit] =
    IO.blocking {
      os.makeDir.all(stateDir)
      os.write.append(auditPath, entry.toJson.noSpaces + "\n", createFolders = true)
    }.handleErrorWith(e => logger.warn(s"[fleet] audit append failed: ${msg(e)}"))

  /** 恢复位（`true` = 已跑过恢复）。 */
  private val restoreGate = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** 引擎重启后的恢复：读落盘任务；在飞行标失联（**绝不假装还在跑**）。 */
  def restore(): IO[Unit] =
    IO.delay(restoreGate.getAndSet(true)).flatMap {
      case true => IO.unit // 幂等：只恢复一次（HTTP 快照与 WS 首帧都会碰它）
      case false =>
        IO.blocking {
          if os.exists(jobPath) then parser.decode[FleetJob](os.read(jobPath)).toOption
          else None
        }.flatMap {
          case None => IO.unit
          case Some(j) =>
            val now = System.currentTimeMillis()
            // 本机行在 `runLocal` 里「受理即交棒」——重启前它不可能自己到终态，
            // 所以「重启后接续」就落在这里：新进程起来后，若本机版本已等于本轮
            // 目标版本，那就是**更新成功**（如实记 completed，不写失联）；否则才
            // 按失联处理。远端在飞行无法从本进程自证 ⇒ 一概标失联（绝不假装在跑）。
            val here = currentVersion()
            def targetReached: Boolean =
              j.targetVersion.exists(t => VersionCheck.compareVersions(here.stripPrefix("v"), t.stripPrefix("v")) == 0)
            val resumed =
              if j.status == FleetJobStatus.Running then
                // 🔴 收口状态必须按**改写后**的 runs 判：用落盘时的旧值（当时本机行还在
                // `restarting`、一台都还没失败）判，会在失联行被标成 `failed` 之后仍然
                // 报 `completed` —— 正是本卡禁的「假装成功」。FleetUpdateSpec 的 restore
                // 用例抓到过。
                val rewritten: List[FleetDeviceRun] = j.runs.map { r =>
                  if r.stage.terminal then r
                  else if r.isLocal && targetReached then
                    r.copy(
                      stage = FleetStage.Completed,
                      progress = Some(1.0),
                      failure = None,
                      detail =
                        s"the restarted engine came back on $here (the target version) — update verified after restart",
                      updatedAt = now
                    )
                  else
                    r.copy(
                      stage = FleetStage.Failed,
                      failure = Some(FleetFailure.DeviceLost),
                      detail =
                        "the engine restarted while this device's update was in flight — its outcome is unverified",
                      updatedAt = now
                    )
                }
                j.copy(
                  runs = rewritten,
                  finishedAt = now,
                  status = closedStatus(j.copy(runs = rewritten))
                )
              else j
            stateRef.set(Some(resumed)) *>
              logger.info(
                s"[fleet] restored job ${resumed.id} from disk (status=${resumed.status.wire}); " +
                  "rows that were in flight are marked device-lost, not faked as running"
              )
        }
    }

  private def msg(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

end FleetUpdateCoordinator

object FleetUpdateCoordinator:

  /**
   * 默认健康自检窗（远端装包 + 重启 + 回来自证；经验值，可注入缩短）。
   *
   * 注入面 `-Dnebflow.fleet.verifyWindowSec=<秒>`：与既有 `nebflow.hotRestart.*`
   * 时序属性同款口径（e2e 要把 5 分钟窗缩短到十几秒，否则一次实测要等满整窗；
   * 生产不设该属性 ⇒ 默认值，零行为变化）。
   */
  val DefaultVerifyWindow: FiniteDuration =
    secondsProp("nebflow.fleet.verifyWindowSec", 300L)

  /** 默认轮询间隔（与心跳同量级；零额外定时器——窗口内轮询，窗口外停）。 */
  val DefaultPollInterval: FiniteDuration =
    millisProp("nebflow.fleet.pollIntervalMs", 5000L)

  private def secondsProp(name: String, default: Long): FiniteDuration =
    scala.concurrent.duration.Duration(
      sys.props.getOrElse(name, default.toString).trim.toLongOption.getOrElse(default),
      "seconds"
    )

  private def millisProp(name: String, default: Long): FiniteDuration =
    scala.concurrent.duration.Duration(
      sys.props.getOrElse(name, default.toString).trim.toLongOption.getOrElse(default),
      "millis"
    )

  /**
   * 设备条目（装配方填：本机 + presence 名册；`online` 由既有新鲜度判据算好）。
   * `localRequest` 只在本机行有意义（本机腿走编排器，不需要网络地址）。
   */
  final case class Peer(
    deviceId: String,
    deviceName: String,
    platform: String,
    address: String,
    online: Boolean,
    version: Option[String],
    isLocal: Boolean,
    localRequest: Option[String] = None
  )

  /** 探测结果（`version` = 对端 `/api/health` 报的版本；`selfReported` = `fleet.hotUpdate`）。 */
  final case class ProbeResult(version: Option[String], selfReported: Boolean)

  /** 审计行（只增 JSONL；`at` 毫秒时间戳）。 */
  final case class AuditEntry(at: Long, action: String, jobId: String, source: String, detail: String):

    def toJson: Json =
      Json.obj(
        "at" -> at.asJson,
        "action" -> action.asJson,
        "jobId" -> jobId.asJson,
        "source" -> source.asJson,
        "detail" -> detail.asJson
      )

  /**
   * 健康探测（`GET <address>/api/health`，2.5s 连接/读取超时）：读 `version` 与
   * `fleet.hotUpdate` 自报。**任何失败 ⇒ 全 None**（保守侧：推不推由能力闸判，
   * 探测失败 = 未知 = 不推 + 走手动清单）。
   */
  def probeHealth(peer: Peer): IO[ProbeResult] =
    if peer.address.isEmpty then IO.pure(ProbeResult(None, false))
    else
      IO.blocking {
        try
          val url = java.net.URI.create(s"${peer.address.stripSuffix("/")}/api/health").toURL
          val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
          conn.setConnectTimeout(2500)
          conn.setReadTimeout(2500)
          conn.setRequestMethod("GET")
          try
            if conn.getResponseCode != 200 then ProbeResult(None, false)
            else
              val raw = new String(conn.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              parser.parse(raw).toOption match
                case None => ProbeResult(None, false)
                case Some(j) =>
                  val version = j.hcursor.downField("version").as[String].toOption.filter(_.trim.nonEmpty)
                  val hot = j.hcursor.downField("fleet").downField("hotUpdate").as[Boolean].getOrElse(false)
                  ProbeResult(version, hot)
          finally conn.disconnect()
        catch case _: Throwable => ProbeResult(None, false)
      }

  /** 自报键（`/api/health` 的 `fleet` 键；引擎侧产物，见 HealthPayload 的 extra 面）。 */
  def selfReportJson(writable: Boolean): Json =
    Json.obj(
      "protocol" -> UpdateCapability.Protocol.asJson,
      "hotUpdate" -> writable.asJson,
      "minRemoteVersion" -> UpdateCapability.MinRemoteVersion.asJson
    )

  /**
   * 名册投影（纯函数；装配方在 `listDevices` 里调它）：本机固定一行（`isLocal = true`、
   * 在线恒真、无网络地址——本机腿走编排器，不需要端口），名册里本机自报的自身条目
   * **滤掉**（否则同一台机器会出现两行、其中一行永远推不到）。
   *
   * 在线态由装配方按既有新鲜度判据（`NeblinkService.isPeerOnline`）判好传进来——
   * 本件不重算新鲜度窗口（判据单点在既有服务）。
   */
  def roster(
    localId: String,
    localName: String,
    localPlatform: String,
    localVersion: Option[String],
    peers: List[nebflow.shared.PeerInfo],
    online: nebflow.shared.PeerInfo => Boolean
  ): List[Peer] =
    val local =
      Peer(localId, localName, localPlatform, "", online = true, localVersion, isLocal = true)
    val remote = peers
      .filterNot(_.deviceId == localId)
      .map(p => Peer(p.deviceId, p.deviceName, p.platform, p.address, online(p), None, isLocal = false))
    local :: remote

end FleetUpdateCoordinator
