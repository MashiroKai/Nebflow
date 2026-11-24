package nebflow.core.hotupdate

import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*

/**
 * 全设备统一更新（fleet update）——**纯逻辑层**：能力门槛表 + 分发决策树 + 计划形状。
 *
 * 本文件零 IO、零状态：所有判定都是纯函数，可单测、可被文档逐条引用（批文档
 * `docs/20261101_*_fleet-unified-update*` 的能力门槛表与分发决策树就是本文件的逐条
 * 映射——**文档与本实现同源，禁两处各写一套判据**）。
 *
 * 与既有地基的关系（禁双实现）：
 *   · 「装包 + 重启 + 健康自检 + 回滚」复用既有热更新地基（[[UpdateOrchestrator]] 七相位
 *     / [[RemoteUpdateAction]] 安装动作 / link-server `RemoteUpdate` 隧道动作）——本件
 *     只做**调度与可见性**，一个字节的安装语义都不复制；
 *   · 版本序比较复用 [[VersionCheck.compareVersions]]（与 link-server `cmp_versions`
 *     同构的单实现）；
 *   · 设备在线态复用 `NeblinkService.isPeerOnline` 的新鲜度判据（`online` 由调用方按该
 *     判据算好传进来——本件不重算新鲜度窗口）。
 */
object UpdateCapability:

  /**
   * **云端热更能力门槛（冻结值）**：引擎版本 ≥ 该版本 ⇒ 具备「云端热更」能力
   * （manifest 取件校验 + 七相位编排 + 心跳收敛 + 远程更新动作）。
   *
   * 取值依据：统一更新系统 M3 ⑤（质量闸）落地的版本。更早的构建只能手动更新——安装
   * 脚本自身能跑，但本机编排器 / 收敛闭环缺失，远程触发会得到「装完不重启」或「无人
   * 应答」。
   *
   * 🔴 改动本值 = 改能力门槛表 = 必须同改批文档「能力门槛表」一节（判据单点在这里）。
   */
  val MinRemoteVersion: String = "2026.10.26"

  /** 能力自报协议版本（`/api/health` 的 `fleet` 键 `protocol` 字段）。 */
  val Protocol: Int = 1

  /**
   * 单台设备的能力判定结论。
   *
   * 判定顺序（**自报优先**——设计口径「接口能力自报 / 版本号判定」两条腿）：
   *   1. 对端 `/api/health` 的 `fleet.hotUpdate == true` ⇒ [[SelfReported]]
   *      （自报权威：新版本仍可自报不支持，例如运维关掉了更新面）；
   *   2. 版本已知且 ≥ [[MinRemoteVersion]] ⇒ [[VersionThreshold]]；
   *   3. 版本已知但更旧 ⇒ [[VersionTooOld]]（**推送拒绝**，给手动更新指引）；
   *   4. 版本未知（老端不报版本 / 探测不可达）⇒ [[Unknown]]——按**保守侧**处理
   *      （不推），进「需手动更新」清单并给可判别的原因字面。
   */
  enum Verdict(val wire: String):
    case SelfReported extends Verdict("self-reported")
    case VersionThreshold extends Verdict("version-threshold")
    case VersionTooOld extends Verdict("version-too-old")
    case Unknown extends Verdict("unknown")

    /** 是否具备云端热更能力（= 可被推送）。 */
    def supported: Boolean = this match
      case SelfReported | VersionThreshold => true
      case VersionTooOld | Unknown => false

    /** 面板文案键（前端按此解析；帧内不携带展示文案——`messageKey` 纪律）。 */
    def messageKey: String = s"fleet.capability.$wire"

  end Verdict

  /** 能力判定（纯函数；`selfReported` = 对端 `/api/health` 是否自报支持热更）。 */
  def verdict(
    version: Option[String],
    selfReported: Boolean,
    minVersion: String = MinRemoteVersion
  ): Verdict =
    if selfReported then Verdict.SelfReported
    else
      version.map(_.trim).filter(_.nonEmpty) match
        case Some(v) =>
          if VersionCheck.compareVersions(v.stripPrefix("v"), minVersion.stripPrefix("v")) >= 0 then
            Verdict.VersionThreshold
          else Verdict.VersionTooOld
        case None => Verdict.Unknown

  /** 判定原因的机器字面（进 `reason` 字段；人类可读诊断走 `messageKey` 解析）。 */
  def reasonOf(verdict: Verdict, version: Option[String], minVersion: String = MinRemoteVersion): Option[String] =
    verdict match
      case Verdict.SelfReported | Verdict.VersionThreshold => None
      case Verdict.VersionTooOld =>
        Some(s"version ${version.getOrElse("?")} is below the cloud hot-update floor $minVersion")
      case Verdict.Unknown => Some("device version not reported and the capability probe failed")

end UpdateCapability

/**
 * 分发决策树（作者令「分发策略」四条分支的**逐条实现**；纯函数）。
 *
 * | 在线 | 能力 | 分支 | 语义 |
 * |---|---|---|---|
 * | ✅ | ✅ | [[FleetDispatch.Branch.Push]] | 直接推送：远控执行 下载→校验→切换→重启→健康自检（失败回滚） |
 * | ✅ | ❌ | [[FleetDispatch.Branch.Manual]] | **不推**，进「需手动更新」清单 + 手动操作指引 |
 * | ❌ | ✅ | [[FleetDispatch.Branch.Queued]] | 记录为待推送，**上线即补推**（哨兵：上线核版本 → 推） |
 * | ❌ | ❌ | [[FleetDispatch.Branch.Manual]] | 同样入「需手动更新」清单（提示注明离线，需先上线再手动） |
 *
 * 🔴 只报「已发起」不算收口：本决策树是**计划相**，执行相必须给出
 * 「全部一致 / N 台待手动 / M 台待上线」的收口状态（见 [[FleetSummary]]）。
 */
object FleetDispatch:

  enum Branch(val wire: String):
    case Push extends Branch("push")
    case Manual extends Branch("manual")
    case Queued extends Branch("queued")

  def branch(online: Boolean, supported: Boolean): Branch =
    if supported then (if online then Branch.Push else Branch.Queued)
    else Branch.Manual

end FleetDispatch

import FleetDispatch.Branch

/** 计划里的一台设备（能力 + 分支 + 原因；不含运行态——运行态在 coordinator）。 */
final case class FleetDeviceView(
  deviceId: String,
  deviceName: String,
  platform: String,
  isLocal: Boolean,
  online: Boolean,
  version: Option[String],
  capability: UpdateCapability.Verdict,
  branch: Branch,
  reason: Option[String],
  /** 目标版本（一致性目标：一次操作后全可达设备同版）。 */
  inSyncWith: Option[String] = None
):

  def versionState: String =
    inSyncWith match
      case Some(target) =>
        version match
          case Some(v) if VersionCheck.compareVersions(v.stripPrefix("v"), target.stripPrefix("v")) == 0 => "in-sync"
          case Some(_) => "outdated"
          case None => "unknown"
      case None => "unknown"

  def toJson: Json =
    Json.obj(
      "deviceId" -> deviceId.asJson,
      "deviceName" -> deviceName.asJson,
      "platform" -> platform.asJson,
      "isLocal" -> isLocal.asJson,
      "online" -> online.asJson,
      "version" -> version.asJson,
      "capability" -> capability.wire.asJson,
      "capabilityKey" -> capability.messageKey.asJson,
      "remoteUpdatable" -> capability.supported.asJson,
      "branch" -> branch.wire.asJson,
      "reason" -> reason.asJson,
      "versionState" -> versionState.asJson
    )

end FleetDeviceView

object FleetDeviceView:
  given Encoder[FleetDeviceView] = Encoder.instance(v => v.toJson)

  given Decoder[FleetDeviceView] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      platform <- c.downField("platform").as[Option[String]].map(_.getOrElse(""))
      isLocal <- c.downField("isLocal").as[Option[Boolean]].map(_.getOrElse(false))
      online <- c.downField("online").as[Option[Boolean]].map(_.getOrElse(false))
      version <- c.downField("version").as[Option[String]]
      capWire <- c.downField("capability").as[Option[String]]
      branchWire <- c.downField("branch").as[Option[String]]
      reason <- c.downField("reason").as[Option[String]]
    yield FleetDeviceView(
      deviceId,
      deviceName,
      platform,
      isLocal,
      online,
      version,
      UpdateCapability.Verdict.values.find(_.wire == capWire.getOrElse("")).getOrElse(UpdateCapability.Verdict.Unknown),
      Branch.values.find(_.wire == branchWire.getOrElse("")).getOrElse(Branch.Manual),
      reason
    )
  }

end FleetDeviceView

/**
 * 收口状态（判定顺序冻结，先硬后软；判据单点在 [[FleetSummary.of]]）：
 *   1. 有待手动（[[Branch.Manual]]）⇒ `manual-required`（**不可能是「全部一致」**）；
 *   2. 有待上线排队（[[Branch.Queued]]）⇒ `pending-online`；
 *   3. 有版本落后/未知 ⇒ `out-of-sync`（可推 ≠ 已一致）；
 *   4. 其余（每行都已在目标版本）⇒ `all-consistent`。
 *
 * 三个数量字段在每个分支都同时给出（不许只说其一）：作者令「不许只报已发起不说结果」。
 */
final case class FleetSummary(
  allConsistent: Boolean,
  manualCount: Int,
  pendingOnlineCount: Int,
  pushCount: Int,
  totalDevices: Int,
  status: String
):

  def toJson: Json =
    Json.obj(
      "allConsistent" -> allConsistent.asJson,
      "manualCount" -> manualCount.asJson,
      "pendingOnlineCount" -> pendingOnlineCount.asJson,
      "pushCount" -> pushCount.asJson,
      "totalDevices" -> totalDevices.asJson,
      "status" -> status.asJson,
      "messageKey" -> s"fleet.summary.$status".asJson
    )

end FleetSummary

object FleetSummary:

  /**
   * 收口判定（判定顺序冻结，先硬后软）：
   *   0. **版本不一致**（任一行 `versionState != "in-sync"`，即「可达且可推但版本落后」
   *      ／版本未知）⇒ `out-of-sync`。🔴 这一档是 e2e 实跑抓到的缺陷修法：没有它，
   *      「一台落后但可推的设备」会让计划读成「全部一致 / pushCount=1」，面板在**动手
   *      之前**就说已经一致了 —— 正是作者令禁的「只报状态不说结果」的反向版本
   *      （报了一个不成立的好结果）。推送可达成 ≠ 已经一致：全一致只能由「每行都
   *      已在目标版本」得出。
   *   1. 有待手动（[[Branch.Manual]]）⇒ `manual-required`（**不可能是「全部一致」**）；
   *   2. 有待上线排队（[[Branch.Queued]]）⇒ `pending-online`；
   *   3. 其余（每行都已在目标版本）⇒ `all-consistent`（**唯一**能让 allConsistent 为真的
   *      档；`pushCount` 在此档恒为 0）。
   *
   * 三个数量字段在每个分支都同时给出（不许只说其一）：作者令「不许只报已发起不说结果」。
   */
  def of(devices: List[FleetDeviceView]): FleetSummary =
    val manual = devices.count(_.branch == Branch.Manual)
    val queued = devices.count(_.branch == Branch.Queued)
    // 🔴 `pushCount` = **这一轮真会被推送的台数**，不是「分支落在 Push 列的台数」：
    // 已在目标版本的设备分支照旧是 Push（四格分发表只看「在线 × 能力」），但执行体
    // （`FleetUpdateCoordinator#runOneDevice`）对 `versionState == in-sync` 的行**直接
    // 判完成、不推送**。按分支数计会把「已经一致的本机」也算进「将推送」，卡上读成
    // 「将推送 2 台」而实际只推 1 台（e2e ② 实跑抓到）；更要命的是本文件下方注释钉住
    // 的不变量「all-consistent 档 pushCount 恒为 0」——按分支数计时该档必然是 N
    // （全一致 ⇔ 全在目标版本 ⇔ 每行分支都可能是 Push），不变量自相矛盾。
    val push = devices.count(d => d.branch == Branch.Push && d.versionState != "in-sync")
    val status =
      if manual > 0 then "manual-required"
      else if queued > 0 then "pending-online"
      else if devices.exists(_.versionState != "in-sync") then "out-of-sync"
      else "all-consistent"
    FleetSummary(
      allConsistent = status == "all-consistent",
      manualCount = manual,
      pendingOnlineCount = queued,
      pushCount = push,
      totalDevices = devices.length,
      status = status
    )
  end of

end FleetSummary

/** 计划 = 设备视图 + 收口状态（只读投影；运行态由 coordinator 叠加）。 */
final case class FleetPlan(devices: List[FleetDeviceView], summary: FleetSummary, targetVersion: Option[String]):

  def toJson: Json =
    Json.obj(
      "targetVersion" -> targetVersion.asJson,
      "summary" -> summary.toJson,
      "devices" -> devices.map(_.toJson).asJson
    )

object FleetPlan:

  /**
   * 造计划（纯函数）。`targetVersion` = 本轮要收敛到的版本（官网 manifest / 版本指针的
   * 最新版；读不到 ⇒ None，`versionState` 全 `unknown`，面板照实显示「目标版本未知」，
   * **不猜**）。
   */
  def build(devices: List[(FleetDeviceView, Boolean)], targetVersion: Option[String]): FleetPlan =
    val views = devices.map { case (v, online) =>
      val branch = FleetDispatch.branch(online, v.capability.supported)
      v.copy(online = online, branch = branch, inSyncWith = targetVersion)
    }
    FleetPlan(views, FleetSummary.of(views), targetVersion)

end FleetPlan

// ═══════════════════════════════════════════════════════════════════════════
// 运行态（状态机相位 + 失败分类）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 单机相位（**作者点名的七阶段**逐条落地 + 分发相的非进度态）：
 *
 * 准备 Preparing → 下载中 Downloading → 校验 Verifying → 切换 Switching →
 * 正在重启 Restarting → 健康自检 HealthCheck → 完成 Completed。
 *
 * 非进度终态：[[Manual]]（不推，需手动）/ [[WaitingOnline]]（离线排队，上线补推）/
 * [[Failed]]（本机失败，带 [[FleetFailure]]）/ [[Cancelled]]（作者中断）。
 *
 * 🔴 远端设备的「下载 / 校验 / 切换」在**对端**发生，本机会话观察不到进度百分比 ——
 * 这三档照实出现在卡上，但 `progress = null` 且 `detail` 明说「在设备侧执行，进度不可见」
 * （**禁编造百分比**）。本地设备的下载进度经既有编排器帧可得。
 */
enum FleetStage(val wire: String):
  case Queued extends FleetStage("queued")
  case Preparing extends FleetStage("prepare")
  case Downloading extends FleetStage("download")
  case Verifying extends FleetStage("verify")
  case Switching extends FleetStage("switch")
  case Restarting extends FleetStage("restart")
  case HealthCheck extends FleetStage("healthcheck")
  case Completed extends FleetStage("done")
  case Failed extends FleetStage("failed")
  case Manual extends FleetStage("manual")
  case WaitingOnline extends FleetStage("waiting-online")
  case Cancelled extends FleetStage("cancelled")

  def messageKey: String = s"fleet.stage.$wire"

  /** 统一四态归属（`state` 字段，面板配色用）。 */
  def stateToken: String = this match
    case Queued | WaitingOnline => "pending"
    case Preparing | Downloading | Verifying | Switching | Restarting | HealthCheck => "in-progress"
    case Completed => "completed"
    case Failed => "failed"
    case Manual => "manual"
    case Cancelled => "cancelled"

  /**
   * 是否终态（终态不再被推进；重试 = 新的一次尝试）。
   *
   * 🔴 [[WaitingOnline]] **算终态**：它是「本轮到此为止」的稳定停点——作者令的收口
   * 状态本身就允许「M 台待上线」，若把它算成在飞，任务永远收不了口（卡上会一直显示
   * 进行中）。哨兵补推时会把任务重新打开（`FleetUpdateCoordinator#onPeerOnline` 的
   * reopen 腿），所以「终态」只表示**本轮**结束，不表示这台设备从此不管。
   */
  def terminal: Boolean = this match
    case Completed | Failed | Manual | Cancelled | WaitingOnline => true
    case _ => false

end FleetStage

/**
 * 失败分类（作者令：分清五类，每类给可操作建议）。
 *
 * `wire` 字面冻结（进帧 `failure` 字段与审计行）；建议文案由前端按
 * `fleet.failure.<wire>` + 动作键解析——**帧内不携带展示文案**（`messageKey` 纪律）。
 */
enum FleetFailure(val wire: String):
  /** 下载失败（取件 / 网络 / 对端拉包失败）。建议：重试。 */
  case DownloadFailed extends FleetFailure("download-failed")

  /** 校验不过（大小 / sha256 不符 / manifest 字段缺失）——**在役包未动**。建议：重试。 */
  case VerifyFailed extends FleetFailure("verify-failed")

  /** 切换回滚（安装后新包未站住，回滚到上一版）。建议：查看日志 + 手动更新。 */
  case SwitchRolledBack extends FleetFailure("switch-rolled-back")

  /** 自检没过（重启后健康自检失败）。建议：查看日志。 */
  case HealthCheckFailed extends FleetFailure("healthcheck-failed")

  /** 设备失联（推送后设备没回来 / 探测超时）。建议：确认设备在线后重试。 */
  case DeviceLost extends FleetFailure("device-lost")

  /** 设备拒绝（msi 形态 / manifest 门槛 / 编排器在途等）。建议：按返回原因处理。 */
  case DeviceRefused extends FleetFailure("device-refused")

  /** 未知失败（兜底——绝不静默）。 */
  case Unknown extends FleetFailure("unknown")

  def messageKey: String = s"fleet.failure.$wire"

  /** 是否可一键重试（回滚档需人工确认后手动更新，不自动重试）。 */
  def retryable: Boolean = this != SwitchRolledBack

end FleetFailure

/**
 * 远端拒/失败原因的**分类判据**（纯函数，单点）。
 *
 * 为什么单独成件：设备侧 `/api/neblink/update` 与中继腿的错误字面来自**三个**地方
 * （安装动作 `RemoteUpdateAction` 的拒装文案、设备路由的中继回落、隧道派发的
 * "not found / no address"），远端腿必须把它们翻译成作者点名的五类之一。判据的
 * 唯一实现放这里（可单测），执行侧只调用——禁在执行体里再写第二份 if-链。
 *
 * 判据顺序：先**具体**后**笼统**（`sha256` 的字面里也含 "install"，顺序错了会被
 * 归进下载失败）。
 */
object FleetFailureClassify:

  /** 关键字 → 分类（顺序即优先级；全部小写比较）。 */
  private val rules: List[(String, FleetFailure)] = List(
    // 校验不过：manifest 取件校验 / size / sha256（在役包未动）
    "sha256" -> FleetFailure.VerifyFailed,
    "size mismatch" -> FleetFailure.VerifyFailed,
    "verification failed" -> FleetFailure.VerifyFailed,
    // 开关/环境类拒装：msi 形态、低于最小兼容版本
    "msi" -> FleetFailure.DeviceRefused,
    "mincompatibleengine" -> FleetFailure.DeviceRefused,
    // 通道不通：失联（设备没回答）
    "not found" -> FleetFailure.DeviceLost,
    "no address" -> FleetFailure.DeviceLost,
    "offline" -> FleetFailure.DeviceLost,
    "no active session" -> FleetFailure.DeviceLost,
    "unreachable" -> FleetFailure.DeviceLost,
    "timed out" -> FleetFailure.DeviceLost,
    "connection refused" -> FleetFailure.DeviceLost,
    // 装包失败：下载 / 脚本
    "install script failed" -> FleetFailure.DownloadFailed,
    "install error" -> FleetFailure.DownloadFailed,
    "artifact fetch failed" -> FleetFailure.DownloadFailed,
    "download" -> FleetFailure.DownloadFailed,
    // 自检
    "health" -> FleetFailure.HealthCheckFailed
  )

  def of(error: String): FleetFailure =
    val e = Option(error).getOrElse("").toLowerCase
    rules
      .collectFirst { case (needle, failure) if e.contains(needle) => failure }
      .getOrElse(if e.isEmpty then FleetFailure.Unknown else FleetFailure.DeviceRefused)

end FleetFailureClassify

/** 单机运行态（进度行；卡上可展开看单机细节）。 */
final case class FleetDeviceRun(
  deviceId: String,
  deviceName: String,
  isLocal: Boolean,
  stage: FleetStage,
  progress: Option[Double] = None,
  detail: String = "",
  failure: Option[FleetFailure] = None,
  attempts: Int = 0,
  startedAt: Long = 0L,
  updatedAt: Long = 0L
):

  def toJson: Json =
    Json.obj(
      "deviceId" -> deviceId.asJson,
      "deviceName" -> deviceName.asJson,
      "isLocal" -> isLocal.asJson,
      "stage" -> stage.wire.asJson,
      "stageKey" -> stage.messageKey.asJson,
      "state" -> stage.stateToken.asJson,
      "progress" -> progress.asJson,
      "detail" -> detail.asJson,
      "failure" -> failure.map(_.wire).asJson,
      "failureKey" -> failure.map(_.messageKey).asJson,
      "retryable" -> failure.exists(_.retryable).asJson,
      "attempts" -> attempts.asJson,
      "startedAt" -> startedAt.asJson,
      "updatedAt" -> updatedAt.asJson
    )

end FleetDeviceRun

object FleetDeviceRun:
  given Encoder[FleetDeviceRun] = Encoder.instance(r => r.toJson)

  given Decoder[FleetDeviceRun] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      isLocal <- c.downField("isLocal").as[Option[Boolean]].map(_.getOrElse(false))
      stageWire <- c.downField("stage").as[String]
      progress <- c.downField("progress").as[Option[Double]]
      detail <- c.downField("detail").as[Option[String]].map(_.getOrElse(""))
      failureWire <- c.downField("failure").as[Option[String]]
      attempts <- c.downField("attempts").as[Option[Int]].map(_.getOrElse(0))
      startedAt <- c.downField("startedAt").as[Option[Long]].map(_.getOrElse(0L))
      updatedAt <- c.downField("updatedAt").as[Option[Long]].map(_.getOrElse(0L))
    yield FleetDeviceRun(
      deviceId,
      deviceName,
      isLocal,
      FleetStage.values.find(_.wire == stageWire).getOrElse(FleetStage.Failed),
      progress,
      detail,
      failureWire.flatMap(w => FleetFailure.values.find(_.wire == w)),
      attempts,
      startedAt,
      updatedAt
    )
  }

end FleetDeviceRun

/** 一次 fleet 更新的整体状态。 */
enum FleetJobStatus(val wire: String):
  case Running extends FleetJobStatus("running")
  case Completed extends FleetJobStatus("completed")
  case Partial extends FleetJobStatus("partial")
  case Cancelled extends FleetJobStatus("cancelled")

final case class FleetJob(
  id: String,
  startedAt: Long,
  finishedAt: Long,
  source: String,
  targetVersion: Option[String],
  status: FleetJobStatus,
  runs: List[FleetDeviceRun],
  manual: List[FleetDeviceView],
  pending: List[FleetDeviceView]
):

  /** 收口四句（作者令：X 台已更新、Y 台失败（原因）、Z 台需手动、M 台待上线）。 */
  def summaryJson: Json =
    val done = runs.count(_.stage == FleetStage.Completed)
    val failed = runs.filter(_.stage == FleetStage.Failed)
    Json.obj(
      "jobId" -> id.asJson,
      "status" -> status.wire.asJson,
      "statusKey" -> s"fleet.job.${status.wire}".asJson,
      "targetVersion" -> targetVersion.asJson,
      "startedAt" -> startedAt.asJson,
      "finishedAt" -> finishedAt.asJson,
      "updatedCount" -> done.asJson,
      "failedCount" -> failed.length.asJson,
      "failed" -> failed
        .map(r =>
          Json.obj(
            "deviceId" -> r.deviceId.asJson,
            "deviceName" -> r.deviceName.asJson,
            "failure" -> r.failure.map(_.wire).asJson,
            "failureKey" -> r.failure.map(_.messageKey).asJson
          )
        )
        .asJson,
      "manualCount" -> manual.length.asJson,
      "pendingOnlineCount" -> pending.length.asJson,
      "cancelledCount" -> runs.count(_.stage == FleetStage.Cancelled).asJson
    )

  end summaryJson

  def toJson: Json =
    Json.obj(
      "job" -> summaryJson,
      "runs" -> runs.map(_.toJson).asJson,
      "manual" -> manual.map(_.toJson).asJson,
      "pending" -> pending.map(_.toJson).asJson
    )

end FleetJob

object FleetJob:

  given Encoder[FleetJob] = Encoder.instance(j => j.toJson)

  given Decoder[FleetJob] = Decoder.instance { c =>
    for
      jobId <- c.downField("job").downField("jobId").as[String]
      startedAt <- c.downField("job").downField("startedAt").as[Option[Long]].map(_.getOrElse(0L))
      finishedAt <- c.downField("job").downField("finishedAt").as[Option[Long]].map(_.getOrElse(0L))
      statusWire <- c.downField("job").downField("status").as[String]
      targetVersion <- c.downField("job").downField("targetVersion").as[Option[String]]
      runs <- c.downField("runs").as[Option[List[FleetDeviceRun]]].map(_.getOrElse(Nil))
      manual <- c.downField("manual").as[Option[List[FleetDeviceView]]].map(_.getOrElse(Nil))
      pending <- c.downField("pending").as[Option[List[FleetDeviceView]]].map(_.getOrElse(Nil))
    yield FleetJob(
      jobId,
      startedAt,
      finishedAt,
      "", // source 不入持久面（审计行另有留痕）——空串即「历史任务」
      targetVersion,
      FleetJobStatus.values.find(_.wire == statusWire).getOrElse(FleetJobStatus.Partial),
      runs,
      manual,
      pending
    )
  }

end FleetJob
