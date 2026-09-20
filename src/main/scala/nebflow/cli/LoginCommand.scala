package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.duration.*

/** headless 登录三命令 —— `login`（设备码流）/ `logout` / `whoami`（D-H7 案 ⒝）。
  *
  * 三件同处一个文件的理由（不是风格选择）：本段写面只授权**一个新件**，三个命令各自
  * 独立成件会超出授权面。文件内分三节落位：① 三个 `CliCommand` 壳（注册面）②
  * [[LoginCopy]] 文案单一归属 + [[SignInFailure]] 稳定码闭集 + 两个读数模型 ③
  * [[LoginFlow]] 实现面（IO）与其内的**纯判定函数**（供 `LoginCommandSpec` 直测）。
  *
  * 🔴 **零新端点**：本段只调用既有 4 条路由 —— `POST /api/neblink/device-flow/start`、
  * `POST /api/neblink/device-flow/poll`、`GET /api/neblink/status`、
  * `POST /api/neblink/logout`（均在 `RestApiRoutes.scala`，挂在 `GatewayMain` 的
  * `"/api" -> …` 之下）。地址一律经 `ctx.client`（既有 `GatewayClient`，端口来自
  * 既有 `--port` / `GATEWAY_PORT` 解析面）⇒ 隔离寻址不需要任何新机制。
  *
  * 🔴 **凭据面零触碰**：CLI 侧不写、不读、不解析凭据文件；落盘由网关进程内的
  * `DeviceCredentialStore` 既有 ACL 面完成（`completeDeviceEnrollment` →
  * `NeblinkEnrollment.persist`）。本段既不扩 ACL 面也不改其形态。
  *
  * 🔴 **零回显**：`deviceCode` 只进 poll 的请求体（不进 argv ⇒ `ps` 面零命中；不进
  * 帮助、错误报文、日志）；`userCode` 只作为 H2 第二行打印一次（用户必须看见），
  * 不落日志。
  */
object LoginCommand extends CliCommand:
  def name = "login"
  def description = LoginCopy.SignInDesc
  def subcommands = List(LoginRun)
  def examples = List("nebflow login", "nebflow login --device-code")

  private object LoginRun extends CliSubcommand:
    def name = "run"
    def description = LoginCopy.SignInDesc
    def params = List(
      CliParam(
        "device-code",
        description = "Sign in with the device-code flow (no browser required)",
        isFlag = true
      )
    )

    def run(ctx: CliContext): IO[CliResult] = LoginFlow.signIn(ctx)
  end LoginRun

end LoginCommand

/** `nebflow logout` —— 退出登录（本地拆除 + 删凭据），走既有 `POST /api/neblink/logout`。 */
object LogoutCommand extends CliCommand:
  def name = "logout"
  def description = LoginCopy.SignOutDesc
  def subcommands = List(LogoutRun)
  def examples = List("nebflow logout")

  private object LogoutRun extends CliSubcommand:
    def name = "run"
    def description = LoginCopy.SignOutDesc
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] = LoginFlow.signOut(ctx)
  end LogoutRun

end LogoutCommand

/** `nebflow whoami` —— 现读登录态与设备面（既有 `GET /api/neblink/status`，只读）。 */
object WhoamiCommand extends CliCommand:
  def name = "whoami"
  def description = LoginCopy.WhoamiDesc
  def subcommands = List(WhoamiShow)
  def examples = List("nebflow whoami", "nebflow whoami --json")

  private object WhoamiShow extends CliSubcommand:
    def name = "show"
    def description = LoginCopy.WhoamiDesc
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] = LoginFlow.whoami(ctx)
  end WhoamiShow

end WhoamiCommand

/** 全部用户面文案的**单一归属**（§16 文案闸：已批逐字只在此处出现，禁第二处副本）。
  *
  * 已批逐字 H1–H5 原样落为常量。`.description` 位 = H 行去掉 `nebflow <cmd>` 前缀与
  * 对齐空格后的正文 —— 现 CLI 的命令表由 `CliRouter.printHelp` 渲染成
  * `"  " + name.padTo(16, ' ') + description`（`CliRouter.scala:326/330`），前缀与列宽
  * 由该件决定，而该件属 CLI 批 resident 面、本段禁改 ⇒ 可落地的逐字面 = 描述位；H 整行
  * 常量保留为**逐字节比对锚**（由 `LoginCommandSpec` 断言，属本段写面内）。
  */
private[cli] object LoginCopy:

  // ── H1 / H4 / H5：三命令帮助行（已批逐字，含对齐空格） ──────────────────────
  //
  // 对齐口径现读自作者批件原文（`.nebflow/tasks/n-4dddaea8.md:16/19/20` 与本次任务书
  // §二逐字同源）：H1 = `nebflow ` + `login` + 24 空格（描述起始列 37）；H4 = `nebflow `
  // + `logout` + 23 空格（同列 37）；H5 = `nebflow ` + `whoami` + 24 空格（列 38 —— 批件
  // 原文如此）。🔴 本段按「逐字含空格对齐」纪律**原样保留、不自行校正**；H5 比 H1/H4
  // 多一空格这一处，在报告 §16 单列呈报（不擅自改成列 37）。
  val H1LoginHelp = "nebflow login                        Sign in to your Nebflow account"
  val H4LogoutHelp = "nebflow logout                       Sign out and remove the stored device credential"
  val H5WhoamiHelp = "nebflow whoami                        Show the signed-in account and device status"

  // 描述位（命令注册表的 `description`；与上列 H 行的正文逐字同源）
  val SignInDesc = "Sign in to your Nebflow account"
  val SignOutDesc = "Sign out and remove the stored device credential"
  val WhoamiDesc = "Show the signed-in account and device status"

  // ── H2：设备码提示（三行；地址位 = 静态品牌域，非响应值） ────────────────────
  //
  // 作者 07:45 全品牌裁定：地址位逐字 == 已批文案（原「打印值 == 响应 verificationUri」
  // 判据作废）⇒ 此处硬编码品牌域。响应里的 `verificationUri` 仍被解析（见
  // DeviceFlowHandle），但**刻意不打印** —— 保留它只为让「读到了响应值却按裁定选静态
  // 文案」在代码层可核（不是漏读）。
  val DevicePromptAddress =
    "Open https://nebflow.space/device on any device with a browser, then enter the code below."
  val DevicePromptCodePrefix = "Code: "
  val DevicePromptWaiting = "Waiting for approval... (Ctrl+C to cancel)"

  /** H2 三行（逐字；用户码是唯一变量）。 */
  def promptLines(userCode: String): List[String] =
    List(DevicePromptAddress, DevicePromptCodePrefix + userCode, DevicePromptWaiting)

  // ── H3：登录成功 / 失败（已批逐字） ────────────────────────────────────────
  def signedInLine(account: String): String = s"Signed in as $account."

  /** H3 失败行逐字（em dash U+2014，两侧各一空格）。`reason`/`code` 取自
    * [[SignInFailure]] 闭集 ⇒ 稳定码随件。 */
  def signInFailedLine(reason: String, code: String): String =
    s"Sign-in failed: $reason (code: $code) — run 'nebflow login' to retry."

  // ── 本段新增文案（超出 H1–H5；逐条在报告 §16 清单呈报，此处集中落位） ──────
  val UnknownValue = "unknown"
  val SignedOut = "Signed out."
  val NotSignedIn = "Not signed in. Run 'nebflow login' to sign in."
  val GatewayRequired = "Gateway not running. Start with 'nebflow start'"

  def deviceLine(name: String, platform: String): String =
    val n = if name.nonEmpty then name else UnknownValue
    val p = if platform.nonEmpty then platform else UnknownValue
    s"Device: $n ($p)"

  /** 轮询进度面（stderr，非 stdout；粒度 = 轮询间隔 ≤30s）。 */
  def heartbeatLine(elapsedSec: Long, limitSec: Long): String =
    s"[login] waiting for approval — ${elapsedSec}s / ${limitSec}s"

  def statusFailedLine(reason: String, code: String): String =
    s"Could not read the account status: $reason (code: $code)"

  def signOutFailedLine(reason: String, code: String): String =
    s"Could not sign out: $reason (code: $code)"

end LoginCopy

/** 稳定码闭集：`(code, reason)` 成对 —— H3 的 `<分类原因>` 与 `<稳定码>` 同源同表。
  *
  * 两个码刻意取 RFC 8628 的原生错误名（`expired_token` / `access_denied`）：它们是服务端
  * 真实会回的串，用户拿去搜索/上报能对上。闭集外不新增码：任何未识别细节一律落该调用面
  * 的语境兜底码（start → `flow_start_failed`、poll → `poll_failed`、whoami →
  * `status_unreadable`、logout → `sign_out_failed`），细节不丢在措辞里。
  */
private[cli] object SignInFailure:
  final case class Failure(code: String, reason: String)

  val ExpiredToken = Failure("expired_token", "the code expired before it was approved")
  val AccessDenied = Failure("access_denied", "approval was denied")
  val NetworkError = Failure("network_error", "the gateway is unreachable")
  val NeblinkUnavailable = Failure("neblink_unavailable", "NebLink is not enabled on the gateway")
  val FlowStartFailed = Failure("flow_start_failed", "the device code could not be requested")
  val PollFailed = Failure("poll_failed", "the gateway rejected the device-code poll")
  val Timeout = Failure("timeout", "waiting for approval timed out")
  val StatusUnreadable = Failure("status_unreadable", "the gateway returned no usable account status")
  val SignOutFailed = Failure("sign_out_failed", "the gateway rejected the sign-out request")

  /** 闭集全量（供 spec 断言：码非空、无重复、reason 非空）。 */
  val all: List[Failure] = List(
    ExpiredToken,
    AccessDenied,
    NetworkError,
    NeblinkUnavailable,
    FlowStartFailed,
    PollFailed,
    Timeout,
    StatusUnreadable,
    SignOutFailed
  )
end SignInFailure

/** 设备码流句柄（`POST /api/neblink/device-flow/start` 的既有响应契约）。 */
private[cli] case class DeviceFlowHandle(
  deviceCode: String,
  userCode: String,
  /** 响应里的校验地址。🔴 刻意**不打印**（H2 用静态品牌域，作者 07:45 裁定）；保留字段
    * = 「读到了但按裁定不用」在代码层可核。 */
  verificationUri: String,
  intervalSec: Int,
  expiresInSec: Int
)

/** `GET /api/neblink/status` 既有响应里本命令消费的子集（其余字段不读不判）。 */
private[cli] case class AccountStatus(
  loggedIn: Boolean,
  email: String,
  displayName: String,
  deviceId: String,
  deviceName: String,
  platform: String
)

/** 一次 poll 的判定（三出口：批准 / 继续等 / 终态失败）。 */
private[cli] sealed trait PollStep

private[cli] object PollStep:
  case object Approved extends PollStep
  case object Pending extends PollStep
  final case class Failed(failure: SignInFailure.Failure) extends PollStep
end PollStep

/** 三命令的实现面。IO 只做「调既有路由 + 按既有响应分支」；全部判定逻辑下沉为纯函数
  * （`parseStart` / `parseStatus` / `pollStep` / `failureOfDetail` / `accountLabel`），
  * 供 `LoginCommandSpec` 直测（不碰网络、不碰真实 home）。
  */
private[cli] object LoginFlow:

  // ── 既有路由（挂载面 = GatewayMain `"/api" -> …`，故路径自带 /api 前缀） ────
  val StartPath = "/api/neblink/device-flow/start"
  val PollPath = "/api/neblink/device-flow/poll"
  val StatusPath = "/api/neblink/status"
  val LogoutPath = "/api/neblink/logout"

  /** 轮询腿硬墙钟上限 = 响应的 `expiresIn`（网关契约默认 900s）。上下夹紧只为防退化响应
    * （0 / 负数 / 天文数），不放宽「以 expiresIn 为硬上限」的口径。 */
  val MinExpiresInSec = 1
  val MaxExpiresInSec = 3600
  val DefaultExpiresInSec = 900

  /** 轮询间隔（网关契约默认 5s），夹在 [1s, 60s]：0/负数会让轮询退化成忙循环。 */
  val MinIntervalSec = 1
  val MaxIntervalSec = 60
  val DefaultIntervalSec = 5

  // ── 纯面：解析与判定 ──────────────────────────────────────────────────────

  def clampExpiresIn(sec: Int): Int =
    if sec <= 0 then DefaultExpiresInSec else math.min(sec, MaxExpiresInSec)

  def clampInterval(sec: Int): Int =
    if sec <= 0 then DefaultIntervalSec else math.max(MinIntervalSec, math.min(sec, MaxIntervalSec))

  /** 启动响应 → 句柄。两个必填字段（`deviceCode` / `userCode`）任一缺失或空即 `None`
    * ——缺 `deviceCode` 则轮询无意义，缺 `userCode` 则 H2 打不全、用户无从输码。 */
  def parseStart(json: Json): Option[DeviceFlowHandle] =
    val c = json.hcursor
    for
      deviceCode <- c.downField("deviceCode").as[String].toOption.filter(_.nonEmpty)
      userCode <- c.downField("userCode").as[String].toOption.filter(_.nonEmpty)
    yield DeviceFlowHandle(
      deviceCode = deviceCode,
      userCode = userCode,
      verificationUri = c.downField("verificationUri").as[String].getOrElse(""),
      intervalSec = clampInterval(c.downField("interval").as[Int].getOrElse(DefaultIntervalSec)),
      expiresInSec = clampExpiresIn(c.downField("expiresIn").as[Int].getOrElse(DefaultExpiresInSec))
    )

  /** 状态响应 → 读数子集。非对象、或缺 `loggedIn` 布尔位 ⇒ `Left(StatusUnreadable)`：
    * 宁报「读不到」也不把坏响应读成「未登录」—— 两者对用户的动作面完全不同。 */
  def parseStatus(json: Json): Either[SignInFailure.Failure, AccountStatus] =
    val c = json.hcursor
    c.downField("loggedIn").as[Boolean].toOption match
      case None => Left(SignInFailure.StatusUnreadable)
      case Some(loggedIn) =>
        val d = c.downField("device")
        Right(
          AccountStatus(
            loggedIn = loggedIn,
            email = d.downField("email").as[String].getOrElse(""),
            displayName = d.downField("displayName").as[String].getOrElse(""),
            deviceId = d.downField("id").as[String].getOrElse(""),
            deviceName = d.downField("name").as[String].getOrElse(""),
            platform = d.downField("platform").as[String].getOrElse("")
          )
        )

  /** 账号标签降级链：`email` → `displayName` → `deviceId`。
    *
    * H3 的 `<email>` 是**占位**，按其「账号标识」语义填值。自建（非 Logto）设备码通路
    * **没有 id_token** ⇒ 网关侧 `device.email`/`displayName` 双双为空（身份面只剩
    * `deviceId`）——此时填 `deviceId` 是唯一真话，且不新造句式（仍是 H3 模板）。 */
  def accountLabel(st: AccountStatus): String =
    if st.email.nonEmpty then st.email
    else if st.displayName.nonEmpty then st.displayName
    else st.deviceId

  /** `GatewayClient` 把非 2xx 包成 `RuntimeException("Gateway request failed (HTTP 400): <detail>")`
    * （`GatewayClient.requestError`）。本函数剥掉那层包装，取出网关/上游的原始 detail；
    * 报文不是该包装形（例如传输层直抛 `ConnectException`）⇒ `None`。 */
  def gatewayDetail(message: String): Option[String] =
    val marker = "Gateway request failed (HTTP "
    val idx = message.indexOf("): ")
    if message.startsWith(marker) && idx > 0 then Some(message.substring(idx + 3).trim).filter(_.nonEmpty)
    else None

  /** detail → 闭集分类。`unknown` = 该调用面的语境兜底码（未识别细节落它）。 */
  def failureOfDetail(detail: String, unknown: SignInFailure.Failure = SignInFailure.PollFailed): SignInFailure.Failure =
    val d = detail.toLowerCase
    if d.contains("expired_token") then SignInFailure.ExpiredToken
    else if d.contains("access_denied") then SignInFailure.AccessDenied
    else if d.contains("neblink not enabled") || d.contains("neblink service not initialized") then
      SignInFailure.NeblinkUnavailable
    else unknown

  /** 异常 → 失败分类。有网关包装 ⇒ 按 detail 分类（未识别落 `contextFallback`）；
    * 无包装（传输层/连接层）⇒ `network_error`（网关不可达，与「网关答了个错」严格区分）。 */
  def failureOfMessage(message: Option[String], contextFallback: SignInFailure.Failure): SignInFailure.Failure =
    message.filter(_.nonEmpty).flatMap(gatewayDetail) match
      case Some(detail) => failureOfDetail(detail, contextFallback)
      case None         => SignInFailure.NetworkError

  /** poll 结果 → 出口。成功 = 2xx（`GatewayClient` 已把非 2xx 抛成异常 ⇒ `Right` 即成功）；
    * 防御性地把带 `error` 字段的 2xx 也算失败。pending 只认 `authorization_pending` /
    * `slow_down`（后者被网关侧归一成前者，此处兼容直连上游的形态）。 */
  def pollStep(result: Either[Throwable, Json]): PollStep =
    result match
      case Right(json) =>
        if json.hcursor.downField("error").succeeded then PollStep.Failed(SignInFailure.PollFailed)
        else PollStep.Approved
      case Left(e) =>
        val msg = Option(e.getMessage).getOrElse("")
        gatewayDetail(msg) match
          case None => PollStep.Failed(SignInFailure.NetworkError)
          case Some(detail) =>
            val d = detail.toLowerCase
            if d.contains("authorization_pending") || d.contains("slow_down") then PollStep.Pending
            else PollStep.Failed(failureOfDetail(detail))

  // ── IO 面：三命令 ────────────────────────────────────────────────────────

  /** `nebflow login`：设备码流（H2 提示 → 轮询 → H3 成功/失败）。
    *
    * 等待腿三件套（纪律 §三.2）：硬墙钟上限 = `expiresIn`；完轮即退（success / expired /
    * network-error 三出口，禁无限轮询）；心跳每轮一行的进度面走 **stderr** —— stdout 留给
    * H2/H3 的逐字面，进度行不许污染逐字比对。
    */
  def signIn(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.Error(LoginCopy.GatewayRequired, 1))
      case Some(client) =>
        client.post(StartPath, Json.obj()).attempt.flatMap {
          case Left(e) =>
            IO.pure(signInFailure(ctx, failureOfMessage(Option(e.getMessage), SignInFailure.FlowStartFailed)))
          case Right(start) =>
            parseStart(start) match
              case None => IO.pure(signInFailure(ctx, SignInFailure.FlowStartFailed))
              case Some(handle) => emitPrompt(ctx, handle.userCode) *> pollLoop(ctx, client, handle)
        }

  private def pollLoop(ctx: CliContext, client: GatewayClient, h: DeviceFlowHandle): IO[CliResult] =
    val startedAt = System.currentTimeMillis()
    val limitMs = h.expiresInSec.toLong * 1000L
    val deadline = startedAt + limitMs

    def step(): IO[CliResult] =
      if System.currentTimeMillis() >= deadline then IO.pure(signInFailure(ctx, SignInFailure.Timeout))
      else
        client.post(PollPath, Json.obj("deviceCode" -> h.deviceCode.asJson)).attempt.flatMap { res =>
          pollStep(res) match
            case PollStep.Approved => signInSuccess(ctx, client)
            case PollStep.Failed(f) => IO.pure(signInFailure(ctx, f))
            case PollStep.Pending =>
              val now = System.currentTimeMillis()
              heartbeat(ctx, (now - startedAt) / 1000, limitMs / 1000)
              val remain = deadline - now
              if remain <= 0L then IO.pure(signInFailure(ctx, SignInFailure.Timeout))
              else IO.sleep(math.min(h.intervalSec.toLong * 1000L, remain).millis) *> step()
        }

    step()
  end pollLoop

  /** 成功面：H3 成功行需要账号位 → 现读一次既有状态面（同一既有路由，零新端点）。
    * 状态读不到时**不降级为失败**（凭据已落盘，登录确实成功了）⇒ 账号位填
    * [[LoginCopy.UnknownValue]]，仍是 H3 模板逐字（不新造句式）。 */
  private def signInSuccess(ctx: CliContext, client: GatewayClient): IO[CliResult] =
    readStatus(client).map {
      case Right(st) =>
        if ctx.json then CliResult.Json(Json.obj("signedIn" -> true.asJson, "account" -> accountLabel(st).asJson))
        else CliResult.Text(List(LoginCopy.signedInLine(accountLabel(st))))
      case Left(_) =>
        if ctx.json then CliResult.Json(Json.obj("signedIn" -> true.asJson))
        else CliResult.Text(List(LoginCopy.signedInLine(LoginCopy.UnknownValue)))
    }

  private def signInFailure(ctx: CliContext, f: SignInFailure.Failure): CliResult =
    if ctx.json then CliResult.Exit(1, Json.obj("error" -> f.reason.asJson, "code" -> f.code.asJson).spaces2)
    else CliResult.Error(LoginCopy.signInFailedLine(f.reason, f.code), 1)

  /** H2 三行：只在人读面打印（`--json`/`--quiet` 下不出声 —— 机器面不许被提示行污染）。 */
  private def emitPrompt(ctx: CliContext, userCode: String): IO[Unit] =
    if ctx.quiet || ctx.json then IO.unit
    else LoginCopy.promptLines(userCode).traverse_(IO.println)

  /** 进度心跳（stderr；`--quiet` 下静默）。粒度 = 轮询间隔（≤30s，纪律 §三.2）。 */
  private def heartbeat(ctx: CliContext, elapsedSec: Long, limitSec: Long): Unit =
    if !ctx.quiet then Console.err.println(LoginCopy.heartbeatLine(elapsedSec, limitSec))

  private def readStatus(client: GatewayClient): IO[Either[SignInFailure.Failure, AccountStatus]] =
    client.get(StatusPath).attempt.map {
      case Right(json) => parseStatus(json)
      case Left(e)     => Left(failureOfMessage(Option(e.getMessage), SignInFailure.StatusUnreadable))
    }

  /** `nebflow logout`：既有 `POST /api/neblink/logout`（本地拆除 + 删凭据 + 关 NebLink）。
    * 幂等面由网关侧保证（`performLocalLogout` 每步都是「清空 / 置 false」，无凭据时照样
    * 200），CLI 侧不额外判存在性 —— 多一次读就多一个会与网关状态不一致的判据。 */
  def signOut(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.Error(LoginCopy.GatewayRequired, 1))
      case Some(client) =>
        client.post(LogoutPath, Json.obj()).attempt.map {
          case Right(json) if !json.hcursor.downField("error").succeeded =>
            if ctx.json then CliResult.Json(Json.obj("signedIn" -> false.asJson))
            else CliResult.Text(List(LoginCopy.SignedOut))
          case Right(_) => signOutFailed(ctx, SignInFailure.SignOutFailed)
          case Left(e)  => signOutFailed(ctx, failureOfMessage(Option(e.getMessage), SignInFailure.SignOutFailed))
        }

  private def signOutFailed(ctx: CliContext, f: SignInFailure.Failure): CliResult =
    if ctx.json then CliResult.Exit(1, Json.obj("error" -> f.reason.asJson, "code" -> f.code.asJson).spaces2)
    else CliResult.Error(LoginCopy.signOutFailedLine(f.reason, f.code), 1)

  /** `nebflow whoami`：只读既有状态面。未登录 ⇒ **exit 1 + 可行动文案**（脚本面靠退出码
    * 判定，文案给不写脚本的人看）。 */
  def whoami(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.Error(LoginCopy.GatewayRequired, 1))
      case Some(client) =>
        readStatus(client).map {
          case Left(f) =>
            if ctx.json then CliResult.Exit(1, Json.obj("error" -> f.reason.asJson, "code" -> f.code.asJson).spaces2)
            else CliResult.Error(LoginCopy.statusFailedLine(f.reason, f.code), 1)
          case Right(st) if !st.loggedIn =>
            if ctx.json then CliResult.Exit(1, Json.obj("loggedIn" -> false.asJson).spaces2)
            else CliResult.Error(LoginCopy.NotSignedIn, 1)
          case Right(st) =>
            if ctx.json then
              CliResult.Json(
                Json.obj(
                  "loggedIn" -> true.asJson,
                  "account" -> accountLabel(st).asJson,
                  "device" -> Json.obj(
                    "id" -> st.deviceId.asJson,
                    "name" -> st.deviceName.asJson,
                    "platform" -> st.platform.asJson
                  )
                )
              )
            else
              CliResult.Text(
                List(LoginCopy.signedInLine(accountLabel(st)), LoginCopy.deviceLine(st.deviceName, st.platform))
              )
        }
  end whoami

end LoginFlow
