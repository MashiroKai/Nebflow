package nebflow.neblink

import io.circe.Json
import io.circe.syntax.*

/** 凭据面 / 登录腿的**失败分类**（缺陷 A：换号登录失败频发 + 报错只说文件名不说原因）。
  *
  * 上游只读侦察 `logdev-recon`（`.nebflow/reports/20260918_160923_logdev-recon.md`）
  * §7.2 判定的并列结构根因 **H0** = 错误面无分类：异常原文（在 Windows 上往往**只有路径**）
  * 从 `DeviceCredentialStore` 的读/写/删三条腿一路原样透到登录框。§4 逐段列出 5 处丢失点
  * （源头无原因语义 / 分类枚举缺项 / 分类→文案无映射 / 异常绕过 Either 通道 / UI 直透 +
  * 状态面静默）。本对象修的是**后四处**：把「异常」收敛成**稳定枚举 + 人话原因 + 下一步
  * 动作**（§8.1 目标契约），并作为**唯一文案源**（禁在调用点各写一份）。
  *
  * 三条硬纪律（逐条可机械判读）：
  *  1. **`code` 是稳定枚举**：可 grep、可断言（`/auth/state` 的 `code` 键、日志行前缀
  *     `[<code>]`、状态面 `credentialIssue.code` 三条面同源）。
  *  2. **用户可见串禁含文件系统路径或 Java 异常类名**（§8.1 / §10.2 G2+G3）：路径与类名
  *     只进 `[[Diagnostic.detail]]` ⇒ 日志（带归因）。判据正则在 [[ForbiddenInVisibleText]]，
  *     与判据 G2/G3 逐字同源，并有全表负控断言（`CredentialDiagnosticsSpec`）。
  *  3. **三段式模板**（§8.3）：`登录失败：<原因>。下一步：<动作>。诊断码：<code>` —— 由
  *     [[Diagnostic.message]] 单点拼装；调用点只能引用分类，不能自己拼文案。
  *
  * 🔴 自愈与分类是**两件事**：坏凭据的自愈（备份改名 + 当作无凭据继续）是作者 2026-09-18
  * 已裁的既定修法（见 `DeviceCredentialStore.loadDiagnosed`）；本对象只负责「失败怎么被
  * 说清楚」。分类**不**替代自愈，自愈**不**吞掉分类（自愈后仍如实登记分类读数）。
  */
enum CredentialFailure(val code: String):
  /** 文件不存在 —— 正常空态，仅在需要向用户解释「为什么没登录」时使用。 */
  case CredentialMissing extends CredentialFailure("credential-missing")
  /** `os.read` 抛权限/占用类异常（Windows 上的「属主自锁」形态）。 */
  case CredentialUnreadable extends CredentialFailure("credential-unreadable")
  /** `decode` 失败（半截 JSON / schema 漂移）。 */
  case CredentialUndecodable extends CredentialFailure("credential-undecodable")
  /** `os.write` 失败（权限/占用）—— 登录落盘的致命腿。 */
  case CredentialWriteDenied extends CredentialFailure("credential-write-denied")
  /** `restrict` 失败（权限未收窄）—— 非致命，但绝不静默。 */
  case CredentialAclNotApplied extends CredentialFailure("credential-acl-not-applied")
  /** 登出的 `os.remove` 失败。 */
  case CredentialDeleteDenied extends CredentialFailure("credential-delete-denied")
  /** 既有隔离护栏拒绝（`EnrollGuard`，案 C 语义：真因照实透出）。 */
  case EnrollRefusedIsolatedHome extends CredentialFailure("enroll-refused-isolated-home")
  /** 服务端没发回 `deviceToken`（既有 `Left`）。 */
  case ServerNoDeviceToken extends CredentialFailure("server-no-device-token")
  /** AC+PKCE 换码腿失败。 */
  case TokenExchangeFailed extends CredentialFailure("token-exchange-failed")
  /** 服务端 `/api/device/register` 腿失败。 */
  case DeviceRegisterFailed extends CredentialFailure("device-register-failed")
  /** 回调 state 不匹配 / 过期 / 无待决尝试。 */
  case CallbackStateInvalid extends CredentialFailure("callback-state-invalid")
  /** 服务方 `?error=` 回调（用户取消 / 托管页失败）。 */
  case ProviderError extends CredentialFailure("provider-error")
  /** 本进程内 NebLink 服务未装配。 */
  case ServiceUnavailable extends CredentialFailure("service-unavailable")
  /** logto 段缺 `pkceClientId` 等配置缺口。 */
  case LogtoNotConfigured extends CredentialFailure("logto-not-configured")
  /** 兜底：其余未分类失败（原文只进日志）。 */
  case Unclassified extends CredentialFailure("login-failed")

  /** 本地凭据文件类分类 —— 前端据此决定是否显示「清理并重登」入口
    * （只有本地文件可被本地清理；网络/服务端类故障点了也没用）。
    * 🔴 与前端 `neblink.js` 的 `LOCAL_FILE_CODES` 必须逐字同源，由
    * `CredentialDiagnosticsSpec` 的镜像漂移断言钉住。 */
  def isLocalFile: Boolean = code.startsWith("credential-")

object CredentialDiagnostics:

  /** 用户可见串的**禁项正则**（§10.2 G2/G3 判据逐字同源）。
    * 命中 = 泄露了文件系统路径或 Java 异常类名 —— 只许出现在日志里。 */
  val ForbiddenInVisibleText: String = "(java\\.|Exception|[A-Za-z]:\\\\|/\\.nebflow/|\\.nebflow)"

  private val Forbidden = ForbiddenInVisibleText.r

  /** 判据 G3 的机械读数：`true` = 该串**干净**（零命中）。 */
  def isCleanVisibleText(s: String): Boolean = !Forbidden.findFirstIn(s).isDefined

  /** 文本映射（**唯一文案源**）：`reason` = 人话原因，`action` = 下一步动作。
    * 禁项：不得含路径 / 异常类名 / `device.json` 之类文件名（那些进 `detail`）。 */
  private def reasonOf(f: CredentialFailure): String = f match
    case CredentialFailure.CredentialMissing => "本机还没有登录凭据"
    case CredentialFailure.CredentialUnreadable => "本机凭据文件打不开（权限或占用）"
    case CredentialFailure.CredentialUndecodable => "本机凭据文件已损坏（已备份留档）"
    case CredentialFailure.CredentialWriteDenied => "凭据写不进去（权限或占用）"
    case CredentialFailure.CredentialAclNotApplied => "凭据文件权限未收窄（安全隐患）"
    case CredentialFailure.CredentialDeleteDenied => "本机凭据清理不掉（权限或占用）"
    case CredentialFailure.EnrollRefusedIsolatedHome =>
      "本机为隔离数据根，入网被本地隔离护栏拒绝（非网络故障）"
    case CredentialFailure.ServerNoDeviceToken => "登录服务器没有发回设备凭据"
    case CredentialFailure.TokenExchangeFailed => "与登录服务器交换令牌失败"
    case CredentialFailure.DeviceRegisterFailed => "登录服务器设备注册失败"
    case CredentialFailure.CallbackStateInvalid => "登录回调校验失败（一次性校验码不匹配或已过期）"
    case CredentialFailure.ProviderError => "登录被服务方拒绝或中断"
    case CredentialFailure.ServiceUnavailable => "nebflow 服务未初始化"
    case CredentialFailure.LogtoNotConfigured => "登录服务未配置（缺少应用标识）"
    case CredentialFailure.Unclassified => "登录过程中出现未分类的错误"

  private def actionOf(f: CredentialFailure): String = f match
    case CredentialFailure.CredentialMissing => "点「登录」重新登录"
    case CredentialFailure.CredentialUnreadable =>
      "先退出其他 nebflow 实例再重试；仍失败则点「清理并重登」重建本机凭据"
    case CredentialFailure.CredentialUndecodable => "点「登录」重建本机凭据（坏件已备份留档）"
    case CredentialFailure.CredentialWriteDenied =>
      "不要再反复点；先退出其他实例再重试，仍失败则点「清理并重登」"
    case CredentialFailure.CredentialAclNotApplied => "共享机器上请检查该文件权限；日志已记录"
    case CredentialFailure.CredentialDeleteDenied =>
      "先退出其他实例再试；仍失败请在停机后手工改名该文件留档"
    case CredentialFailure.EnrollRefusedIsolatedHome =>
      "用默认数据根启动后重试；确需放行时改环境开关（见日志）后重试"
    case CredentialFailure.ServerNoDeviceToken => "点「重试」；仍失败则「使用其他账号登录」"
    case CredentialFailure.TokenExchangeFailed => "点「重试」；仍失败则「使用其他账号登录」"
    case CredentialFailure.DeviceRegisterFailed => "点「重试」；仍失败则「使用其他账号登录」"
    case CredentialFailure.CallbackStateInvalid => "回到 nebflow 重新点「登录」"
    case CredentialFailure.ProviderError => "重新点「登录」；必要时换一个账号"
    case CredentialFailure.ServiceUnavailable => "重启 nebflow 后重新登录"
    case CredentialFailure.LogtoNotConfigured => "检查 nebflow 的登录配置后重试"
    case CredentialFailure.Unclassified => "点「重试」；仍失败请附日志反馈"

  /** 三段式文案载荷（§8.3）。`detail` = **只进日志**的原始细节（路径 / 异常类名 /
    * 服务端原文），绝不出现在 `reason` / `action` / `message` 里。 */
  final case class Diagnostic(
    failure: CredentialFailure,
    reason: String,
    action: String,
    detail: String = ""
  ):
    def code: String = failure.code

    /** 用户可见三段式（唯一拼装点）。 */
    def message: String = s"登录失败：$reason。下一步：$action。诊断码：$code"

    /** 日志行（带分类码 + 归因上下文 + 原始细节）。 */
    def logLine(context: String): String =
      val tail = if detail.isEmpty then "" else s" — cause: $detail"
      s"[${failure.code}] $context$tail"

    /** 前端 `error` 字段的兼容形态 = 三段式（老消费方只读 `error` 也能看到原因+动作）。 */
    def toJson: Json = Json.obj(
      "error" -> message.asJson,
      "code" -> code.asJson,
      "reason" -> reason.asJson,
      "action" -> action.asJson
    )

  /** 构造：`detail` 非空时**只**进日志面（用户可见的 `reason` 保持不变）——唯一例外是
    * 隔离护栏拒绝（案 C：真因照实透出）。
    *
    * 🔴 **例外分支也带闸门**（缺陷 A 收口）：只有 `detail` 本身**干净**（[[isCleanVisibleText]]
    * 零命中）时才透出，否则退回模板原因、原文仍进 `detail` ⇒ 日志。若不加这道闸，判据 G2/G3
    * 就成了「靠每个调用点自律」的软约定：护栏文案将来一旦带上数据根路径，路径会长驱直入登录框。
    * 加闸后「用户可见串禁含路径 / 异常类名」是**系统不变量**，与调用点无关；案 C 语义不受损
    * （护栏现有文案本就干净，逐字照实透出 —— 由 `CredentialDiagnosticsSpec` R2b 钉住）。 */
  def diagnosticOf(f: CredentialFailure, detail: String = ""): Diagnostic =
    val visibleDetail =
      if f == CredentialFailure.EnrollRefusedIsolatedHome && isCleanVisibleText(detail) then detail
      else ""
    val reason =
      if visibleDetail.isEmpty then reasonOf(f) else s"${reasonOf(f)}：$visibleDetail"
    Diagnostic(f, reason, actionOf(f), detail)

  /** 全表（测试面：负控遍历 + 前端镜像比对）。 */
  val all: List[CredentialFailure] = CredentialFailure.values.toList

  /** 本地文件类分类码全表（逐字镜像到 `neblink.js` 的 `LOCAL_FILE_CODES`）。 */
  val localFileCodes: List[String] = all.filter(_.isLocalFile).map(_.code)

  /** 可见串反查分类（`Either[String, _]` 通道的 Left 是自由串，见 `NeblinkEnrollment`）：
    * 逐字符相等比对（纯函数，无启发式）⇒ 「分类 → 文案」仍是单点映射，反查不引入第二份文案。 */
  def byVisibleMessage(msg: String): Option[CredentialFailure] =
    all.find(f => diagnosticOf(f).message == msg)

  /** 异常 → 分类（按**操作面**分派，不靠异常类型猜面：Windows 上读写失败常常都只是
    * `AccessDeniedException`，只有调用点知道自己在干什么）。 */
  enum Op:
    case Read, Write, Delete, Acl

  def classify(op: Op): CredentialFailure = op match
    case Op.Read   => CredentialFailure.CredentialUnreadable
    case Op.Write  => CredentialFailure.CredentialWriteDenied
    case Op.Delete => CredentialFailure.CredentialDeleteDenied
    case Op.Acl    => CredentialFailure.CredentialAclNotApplied

  /** 异常的可日志描述（类全名 + message + 可选路径）——**只**进 `detail`。 */
  def describe(e: Throwable, path: String = ""): String =
    val cls = e.getClass.getName
    val msg = Option(e.getMessage).getOrElse("")
    val where = if path.isEmpty then "" else s" @ $path"
    List(cls, msg).filter(_.nonEmpty).mkString(": ") + where

  /** 落盘面抛出的**已分类**异常（`DeviceCredentialStore.save` 的唯一失败出口）。
    *
    * 为什么是异常而不是 `Either`：`save` 的既有签名 `IO[Unit]` 有 5 个测试面调用点，
    * 改签名要动既有 spec 基线；而「失败必带上分类」这件事由类型保证 —— 它是
    * [[CredentialStoreError]]，不是裸 `IOException`（`NeblinkEnrollment` 把它收敛到
    * `Left` 通道，与隔离护栏拒绝同通道、同文案层）。 */
  final class CredentialStoreError(val diagnostic: Diagnostic)
      extends RuntimeException(s"[${diagnostic.code}] ${diagnostic.detail}")

  /** 任意异常 → 分类诊断（写面缺省；`CredentialStoreError` 原样取回其分类）。 */
  def classifyFailure(e: Throwable, fallback: CredentialFailure = CredentialFailure.Unclassified): Diagnostic =
    e match
      case cse: CredentialStoreError => cse.diagnostic
      case other                     => diagnosticOf(fallback, describe(other))

  /** 本地凭据文件类码判定（前端入口的门控判据；JS 侧为 `LOCAL_FILE_CODES`）。 */
  def isLocalFileCode(code: String): Boolean =
    all.filter(_.isLocalFile).exists(_.code == code)

  private val log = nebflow.core.NebflowLogger.forName("nebflow.neblink.credential")

  /** 观测面（测试用）：各分类的可见三段式文案全表（负控遍历用）。 */
  def allMessages: List[String] = all.map(f => diagnosticOf(f, detailOf(f)).message)

  /** 分类的**样例 detail**（负控断言用「最脏」的形态：Windows 路径 + 异常类名）——
    * 判据要求：即便 detail 最脏，可见串仍零命中。 */
  private def detailOf(f: CredentialFailure): String = f match
    case CredentialFailure.EnrollRefusedIsolatedHome =>
      "refused: this instance runs on an isolated data root, so it must not auto-register " +
        "with the production NebLink server (https://nebflow.space)."
    case _ =>
      "java.nio.file.AccessDeniedException: C:\\Users\\you\\.nebflow\\neblink\\device.json"

  /** 一条 WARN 的发射点（分类 + 归因 + detail），供各腿复用，防各写一份格式。 */
  def warn(where: String, diagnostic: Diagnostic): cats.effect.IO[Unit] =
    log.warn(diagnostic.logLine(where), "code" -> diagnostic.code)

end CredentialDiagnostics
