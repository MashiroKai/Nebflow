package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

/**
 * headless 登录三命令 —— `login`（统一登录·路径② 无头密码登录）/ `logout` /
 * `whoami`（台账 #924, 2026-10-26 统一登录批）。
 *
 * 统一注册路径（作者裁定 2026-10-26 飞书逐字）：登录成功 → 设备自动注册进账号设备
 * 列表 → 上线；两种登录方式、同一条注册汇聚点 ——
 *   · 路径①（有浏览器）：stand 开门 → 浏览器打开 Logto 托管页（既有 PKCE 链，
 *     `POST /neblink/auth/start`）→ 回调注册。本段不触碰。
 *   · 路径②（无头，本段）：交互提示输入账号+密码 → `POST /neblink/auth/password`
 *     （网关在**本机环回**上执行 Logto Experience 舞步：交互挂载 → 密码标识识别 →
 *     授权码 → token）→ 与路径①同一 `register` + persist 汇聚点。
 * 设备码流（RFC 8628 / `--device-code`）整体退役（2026-10-26）：CLI 面不再有该
 * 子命令、不再有轮询腿、不再有 H2 设备码提示；已发出的旧码自然过期即可。
 *
 * 🔴 **凭据面零触碰**：CLI 侧不写、不读、不解析凭据文件；落盘由网关进程内的
 * `DeviceCredentialStore` 既有 ACL 面完成（`completeDeviceEnrollment` →
 * `NeblinkEnrollment.persist`）。
 *
 * 🔴 **密码零落日志/零落盘**（安全自查, #924）：密码只在内存里走一次 —— 提示输入
 * （终端隐藏回显；无终端时明读一次并如实警告）→ 进入 POST 体 → 丢弃。它不出现在
 * argv、帮助、错误报文、任何打印面。
 */
object LoginCommand extends CliCommand:
  def name = "login"
  def description = LoginCopy.SignInDesc
  def subcommands = List(LoginRun)
  def examples = List("stand login")

  private object LoginRun extends CliSubcommand:
    def name = "run"
    def description = LoginCopy.SignInDesc

    def params = Nil

    def run(ctx: CliContext): IO[CliResult] = LoginFlow.signIn(ctx)
  end LoginRun

end LoginCommand

/** `stand logout` —— 退出登录（本地拆除 + 删凭据），走既有 `POST /api/neblink/logout`。 */
object LogoutCommand extends CliCommand:
  def name = "logout"
  def description = LoginCopy.SignOutDesc
  def subcommands = List(LogoutRun)
  def examples = List("stand logout")

  private object LogoutRun extends CliSubcommand:
    def name = "run"
    def description = LoginCopy.SignOutDesc
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] = LoginFlow.signOut(ctx)
  end LogoutRun

end LogoutCommand

/** `stand whoami` —— 现读登录态与设备面（既有 `GET /api/neblink/status`，只读）。 */
object WhoamiCommand extends CliCommand:
  def name = "whoami"
  def description = LoginCopy.WhoamiDesc
  def subcommands = List(WhoamiShow)
  def examples = List("stand whoami", "stand whoami --json")

  private object WhoamiShow extends CliSubcommand:
    def name = "show"
    def description = LoginCopy.WhoamiDesc
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] = LoginFlow.whoami(ctx)
  end WhoamiShow

end WhoamiCommand

/**
 * 全部用户面文案的**单一归属**（§16 文案闸：已批逐字只在此处出现，禁第二处副本）。
 *
 * 已批逐字 H1/H3/H4/H5 原样保留。设备码时代的 H2（三行提示）随该流程退役；
 * 交互提示三行是**本批新增文案**（超出 H1–H5，逐条在 #924 报告 §文案清单呈报，
 * 此处集中落位）。`.description` 位 = H 行去掉 `stand <cmd>` 前缀与对齐空格后的
 * 正文（`CliRouter.printHelp` 渲染口径，见 `CliRouter.scala:326/330`）。
 */
private[cli] object LoginCopy:

  // ── H1 / H4 / H5：三命令帮助行（已批逐字，含对齐空格） ──────────────────────
  val H1LoginHelp = s"stand login                        Sign in to your ${nebflow.shared.Branding.productName} account"
  val H4LogoutHelp = "stand logout                       Sign out and remove the stored device credential"
  val H5WhoamiHelp = "stand whoami                        Show the signed-in account and device status"

  // 描述位（命令注册表的 `description`；与上列 H 行的正文逐字同源）
  val SignInDesc = s"Sign in to your ${nebflow.shared.Branding.productName} account"
  val SignOutDesc = "Sign out and remove the stored device credential"
  val WhoamiDesc = "Show the signed-in account and device status"

  // ── 交互提示（#924 新增文案；stderr 通道，不进 stdout 逐字面） ──────────────
  val PromptAccount = "Account: "
  val PromptPassword = "Password: "

  /**
   * 无终端（管道/测试）时的如实降级警告：读得到输入但**关不掉回显**——不谎称隐藏。
   */
  val PromptNonInteractiveWarning =
    "[login] no terminal detected — password input will not be hidden (piped input)"

  // ── H3：登录成功 / 失败（已批逐字） ────────────────────────────────────────
  def signedInLine(account: String): String = s"Signed in as $account."

  /**
   * H3 失败行逐字（em dash U+2014，两侧各一空格）。`reason`/`code` 取自
   * [[SignInFailure]] 闭集 ⇒ 稳定码随件。
   */
  def signInFailedLine(reason: String, code: String): String =
    s"Sign-in failed: $reason (code: $code) — run 'stand login' to retry."

  // ── 既有零散文案 ────────────────────────────────────────────────────────
  val UnknownValue = "unknown"
  val SignedOut = "Signed out."
  val NotSignedIn = "Not signed in. Run 'stand login' to sign in."
  val GatewayRequired = "Gateway not running. Start with 'stand start'"

  def deviceLine(name: String, platform: String): String =
    val n = if name.nonEmpty then name else UnknownValue
    val p = if platform.nonEmpty then platform else UnknownValue
    s"Device: $n ($p)"

  def statusFailedLine(reason: String, code: String): String =
    s"Could not read the account status: $reason (code: $code)"

  def signOutFailedLine(reason: String, code: String): String =
    s"Could not sign out: $reason (code: $code)"

end LoginCopy

/**
 * 稳定码闭集：`(code, reason)` 成对 —— H3 的 `<分类原因>` 与 `<稳定码>` 同源同表。
 *
 * 设备码时代的 RFC 8628 原生错误名（`expired_token` / `access_denied`）与轮询码
 * （`flow_start_failed` / `poll_failed` / `timeout`）随该流程退役；新闭集 =
 * 无头密码登录的真实出口。`invalid_credentials` 逐字 = 部署租户的 provider 稳定码
 * `session.invalid_credentials` 的 CLI 面（anti-enumeration：不区分「无此账号」与
 * 「密码错」）。闭集外不新增码：任何未识别细节落调用面语境兜底码
 * （login → `login_failed`、whoami → `status_unreadable`、logout →
 * `sign_out_failed`），细节不丢在措辞里。
 */
private[cli] object SignInFailure:
  final case class Failure(code: String, reason: String)

  val InvalidCredentials = Failure("invalid_credentials", "wrong account or password")
  val RateLimited = Failure("rate_limited", "too many attempts — wait a bit and retry")
  val NetworkError = Failure("network_error", "the gateway is unreachable")
  val NeblinkUnavailable = Failure("neblink_unavailable", "NebLink is not enabled on the gateway")
  val LogtoUnavailable = Failure("logto_unavailable", "the hosted sign-in service is not configured")
  val TransportFailed = Failure("transport_failed", "could not reach the sign-in service")
  val ProtocolError = Failure("protocol_error", "the sign-in service answered unexpectedly")
  val RegisterFailed = Failure("register_failed", "the device could not be registered to the account")
  val NoInput = Failure("no_input", "no account or password was entered")
  val LoginFailed = Failure("login_failed", "the sign-in could not be completed")
  val StatusUnreadable = Failure("status_unreadable", "the gateway returned no usable account status")
  val SignOutFailed = Failure("sign_out_failed", "the gateway rejected the sign-out request")

  /** 闭集全量（供 spec 断言：码非空、无重复、reason 非空）。 */
  val all: List[Failure] = List(
    InvalidCredentials,
    RateLimited,
    NetworkError,
    NeblinkUnavailable,
    LogtoUnavailable,
    TransportFailed,
    ProtocolError,
    RegisterFailed,
    NoInput,
    LoginFailed,
    StatusUnreadable,
    SignOutFailed
  )
end SignInFailure

/** `GET /api/neblink/status` 既有响应里本命令消费的子集（其余字段不读不判）。 */
private[cli] case class AccountStatus(
  loggedIn: Boolean,
  email: String,
  displayName: String,
  deviceId: String,
  deviceName: String,
  platform: String
)

/**
 * 三命令的实现面。IO 只做「提示输入 + 调既有路由 + 按既有响应分支」；全部判定逻辑
 * 下沉为纯函数（`parseStatus` / `failureOfDetail` / `failureOfMessage` /
 * `accountLabel`），供 `LoginCommandSpec` 直测（不碰网络、不碰真实 home）。
 * `signInWith` 的输入/出站两条腿都注入（prompt 读数 + POST），spec 可整链直测。
 */
private[cli] object LoginFlow:

  // ── 既有路由（挂载面 = GatewayMain `"/api" -> …`，故路径自带 /api 前缀） ────
  val LoginPath = "/api/neblink/auth/password"
  val StatusPath = "/api/neblink/status"
  val LogoutPath = "/api/neblink/logout"

  // ── 纯面：解析与判定 ──────────────────────────────────────────────────────

  /**
   * 状态响应 → 读数子集。非对象、或缺 `loggedIn` 布尔位 ⇒ `Left(StatusUnreadable)`：
   * 宁报「读不到」也不把坏响应读成「未登录」—— 两者对用户的动作面完全不同。
   */
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

  end parseStatus

  /**
   * 账号标签降级链：`email` → `displayName` → `deviceId`。路径②走 Logto 后
   * id_token 的 email/name 进 status 面 ⇒ 标签通常就是邮箱（H3 占位语义不变）。
   */
  def accountLabel(st: AccountStatus): String =
    if st.email.nonEmpty then st.email
    else if st.displayName.nonEmpty then st.displayName
    else st.deviceId

  /**
   * `GatewayClient` 把非 2xx 包成 `RuntimeException("Gateway request failed (HTTP 400): <detail>")`
   * （`GatewayClient.requestError`）。本函数剥掉那层包装，取出网关/上游的原始 detail；
   * 报文不是该包装形（例如传输层直抛 `ConnectException`）⇒ `None`。
   */
  def gatewayDetail(message: String): Option[String] =
    val marker = "Gateway request failed (HTTP "
    val idx = message.indexOf("): ")
    if message.startsWith(marker) && idx > 0 then Some(message.substring(idx + 3).trim).filter(_.nonEmpty)
    else None

  /**
   * detail → 闭集分类。`unknown` = 该调用面的语境兜底码（未识别细节落它）。
   * detail 可能是 JSON 体（网关透传 `{error, code}` 的原文）也可能是裸串 ——
   * 两种形态都按**子串**匹配稳定码。
   */
  def failureOfDetail(
    detail: String,
    unknown: SignInFailure.Failure = SignInFailure.LoginFailed
  ): SignInFailure.Failure =
    val d = detail.toLowerCase
    if d.contains("invalid_credentials") then SignInFailure.InvalidCredentials
    else if d.contains("rate_limited") then SignInFailure.RateLimited
    else if d.contains("logto-not-configured") || d.contains("logto_not_configured") then
      SignInFailure.LogtoUnavailable
    else if d.contains("transport_failed") then SignInFailure.TransportFailed
    else if d.contains("protocol_error") then SignInFailure.ProtocolError
    else if d.contains("register_failed") || d.contains("device_bound_other_account") ||
      d.contains("device_already_logged_in")
    then SignInFailure.RegisterFailed
    else if d.contains("neblink not enabled") || d.contains("neblink service not initialized") then
      SignInFailure.NeblinkUnavailable
    else unknown

  /**
   * 异常 → 失败分类。有网关包装 ⇒ 按 detail 分类（未识别落 `contextFallback`）；
   * 无包装（传输层/连接层）⇒ `network_error`（网关不可达，与「网关答了个错」严格区分）。
   */
  def failureOfMessage(message: Option[String], contextFallback: SignInFailure.Failure): SignInFailure.Failure =
    message.filter(_.nonEmpty).flatMap(gatewayDetail) match
      case Some(detail) => failureOfDetail(detail, contextFallback)
      case None => SignInFailure.NetworkError

  // ── IO 面：输入提示（可注入） ────────────────────────────────────────────

  /**
   * 生产输入腿。账号 = stderr 提示 + stdin 行读；密码 = 终端 `readPassword`
   * （隐藏回显，char[] 用后清零）；无终端（管道/测试）= 如实警告 + 明读一次。
   * `None` = 输入流关闭（EOF/Ctrl+D）。🔴 密码不进任何打印面。
   */
  val readAccountInteractive: IO[Option[String]] = IO {
    Console.err.print(LoginCopy.PromptAccount)
    Option(scala.io.StdIn.readLine()).map(_.trim)
  }

  val readPasswordInteractive: IO[Option[String]] = IO {
    Option(System.console()) match
      case Some(c) =>
        val chars = c.readPassword("%s", LoginCopy.PromptPassword)
        val s = if chars == null then None else Some(new String(chars))
        if chars != null then java.util.Arrays.fill(chars, ' ') // 用后清零
        s.map(_.trim) // 空串语义（未输入）留给调用面判 NoInput
      case None =>
        Console.err.println(LoginCopy.PromptNonInteractiveWarning)
        Console.err.print(LoginCopy.PromptPassword)
        Option(scala.io.StdIn.readLine()).map(_.trim)
  }

  // ── IO 面：三命令 ────────────────────────────────────────────────────────

  /**
   * `stand login`：无头密码登录（提示 → POST 既有网关面 → H3 成功/失败）。
   * 等待腿不存在了（没有轮询、没有设备码）——登录要么当场成、要么当场败。
   */
  def signIn(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.Error(LoginCopy.GatewayRequired, 1))
      case Some(client) =>
        signInWith(ctx, (p, b) => client.post(p, b).attempt, readAccountInteractive, readPasswordInteractive)

  /**
   * 注入面（spec 直测）：`post` = 出站腿（路径 + 体 → 结果或异常）；
   * `readAccount` / `readPassword` = 输入腿。
   */
  def signInWith(
    ctx: CliContext,
    post: (String, Json) => IO[Either[Throwable, Json]],
    readAccount: IO[Option[String]],
    readPassword: IO[Option[String]]
  ): IO[CliResult] =
    readAccount.flatMap {
      case None => IO.pure(signInFailure(ctx, SignInFailure.NoInput))
      case Some(account) if account.isEmpty => IO.pure(signInFailure(ctx, SignInFailure.NoInput))
      case Some(account) =>
        readPassword.flatMap {
          case None => IO.pure(signInFailure(ctx, SignInFailure.NoInput))
          case Some(pw) if pw.isEmpty => IO.pure(signInFailure(ctx, SignInFailure.NoInput))
          case Some(pw) =>
            // post 已是 IO[Either[Throwable, Json]]（attempt 由生产装配/注入面决定）。
            post(LoginPath, Json.obj("account" -> account.asJson, "password" -> pw.asJson)).flatMap {
              case Right(json) if !json.hcursor.downField("error").succeeded => signInSuccess(ctx, post)
              case Right(json) =>
                // 2xx 但带 error 字段 = 防御性出口（旧 pollStep 同口径）。
                val detail = json.hcursor.downField("error").as[String].toOption.getOrElse("unexpected success-shape error")
                IO.pure(signInFailure(ctx, failureOfDetail(detail)))
              case Left(e) =>
                IO.pure(signInFailure(ctx, failureOfMessage(Option(e.getMessage), SignInFailure.LoginFailed)))
            }
        }
    }

  /**
   * 成功面：H3 成功行需要账号位 → 现读一次既有状态面（同一既有路由，零新端点）。
   * 状态读不到时**不降级为失败**（凭据已落盘，登录确实成功了）⇒ 账号位填
   * [[LoginCopy.UnknownValue]]，仍是 H3 模板逐字（不新造句式）。
   */
  private def signInSuccess(ctx: CliContext, post: (String, Json) => IO[Either[Throwable, Json]]): IO[CliResult] =
    readStatusVia(post).map {
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

  private def readStatusVia(post: (String, Json) => IO[Either[Throwable, Json]]): IO[Either[SignInFailure.Failure, AccountStatus]] =
    post(StatusPath, Json.obj()).flatMap {
      case Right(json) => IO.pure(parseStatus(json))
      case Left(e) => IO.pure(Left(failureOfMessage(Option(e.getMessage), SignInFailure.StatusUnreadable)))
    }

  /**
   * `stand logout`：既有 `POST /api/neblink/logout`（本地拆除 + 删凭据 + 关 NebLink）。
   * 幂等面由网关侧保证（`performLocalLogout` 每步都是「清空 / 置 false」，无凭据时照样
   * 200），CLI 侧不额外判存在性 —— 多一次读就多一个会与网关状态不一致的判据。
   */
  def signOut(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.Error(LoginCopy.GatewayRequired, 1))
      case Some(client) =>
        client.post(LogoutPath, Json.obj()).attempt.map {
          case Right(json) if !json.hcursor.downField("error").succeeded =>
            if ctx.json then CliResult.Json(Json.obj("signedIn" -> false.asJson))
            else CliResult.Text(List(LoginCopy.SignedOut))
          case Right(_) => signOutFailed(ctx, SignInFailure.SignOutFailed)
          case Left(e) => signOutFailed(ctx, failureOfMessage(Option(e.getMessage), SignInFailure.SignOutFailed))
        }

  private def signOutFailed(ctx: CliContext, f: SignInFailure.Failure): CliResult =
    if ctx.json then CliResult.Exit(1, Json.obj("error" -> f.reason.asJson, "code" -> f.code.asJson).spaces2)
    else CliResult.Error(LoginCopy.signOutFailedLine(f.reason, f.code), 1)

  /**
   * `stand whoami`：只读既有状态面。未登录 ⇒ **exit 1 + 可行动文案**（脚本面靠退出码
   * 判定，文案给不写脚本的人看）。
   */
  def whoami(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.Error(LoginCopy.GatewayRequired, 1))
      case Some(client) =>
        client.get(StatusPath).attempt.map {
          case Left(e) =>
            val f = failureOfMessage(Option(e.getMessage), SignInFailure.StatusUnreadable)
            if ctx.json then CliResult.Exit(1, Json.obj("error" -> f.reason.asJson, "code" -> f.code.asJson).spaces2)
            else CliResult.Error(LoginCopy.statusFailedLine(f.reason, f.code), 1)
          case Right(json) =>
            parseStatus(json) match
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
