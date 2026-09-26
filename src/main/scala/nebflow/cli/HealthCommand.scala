package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/**
 * `nebflow health` — 健康读数形态（三面：自身 / 网关 / 依赖）。
 *
 * 只读：只探端口、只发 GET、只读本机文件与进程表；不起停实例、不改任何状态。
 * 三面各自独立成节：任一面不可读只降级该节并显式标出，不隐藏其余读数
 * （fail-visible，不是 fail-silent ——「网关没起」本身就是要读出来的读数）。
 *
 * 退出码沿用 CliRouter 顶层已声明给脚本的既有约定
 * （`Exit codes: 0 ok · 1 error · 2 unreachable/timeout`，CliRouter.scala:327）：
 *   0 = ok        三面全绿
 *   1 = degraded  网关可达但某面降级（依赖 DOWN / 自身门槛不过）
 *   2 = down      网关 API 不可达（与既有 2 = unreachable 同义）
 */
object HealthCommand extends CliCommand:
  def name = "health"
  def description = "Show health readings (self / gateway / dependencies)"
  def subcommands = List(HealthShow)
  def examples = List("nebflow health", "nebflow health --json")

  // ── 读数值（纯函数，供 spec 直测；不碰 IO、不碰真实 home）────────────────

  private[cli] val StatusOk = "ok"
  private[cli] val StatusDegraded = "degraded"
  private[cli] val StatusDown = "down"

  /**
   * Java 运行时门槛 21 —— 与 `nebflow doctor`（SystemCommands.scala DoctorRun
   * 「--- 1. Java ---」节）同一常量、同一语义；本命令不新造第二套门槛。
   */
  private[cli] val MinJavaMajor = 21

  /** `"23.0.1"` → 23；`"1.8.0_301"` → 1（老版本号形态自然落选，与 doctor 同法）。 */
  private[cli] def javaMajor(version: String): Int =
    try version.takeWhile(_.isDigit).toInt
    catch case _: NumberFormatException => 0

  private[cli] def javaOk(version: String): Boolean = javaMajor(version) >= MinJavaMajor

  /**
   * provider 读数行：`/api/health` 的 `providers` 是 `{"<provider>/<model>":
   * "up" | "down: <reason>"}`（RestApiRoutes.scala:71-97）。只有逐字 `up` 算健康
   * —— 白名单式判定，新增状态不会被静默读成健康。
   */
  private[cli] def isProviderUp(state: String): Boolean = state.trim == "up"

  /**
   * 三面读数 → 总状态。判据显式列出，不隐式兜底：
   *   - 网关 API 不可达 ⇒ down（依赖面此时本就不可读）
   *   - 自身门槛不过 / 数据根不可写 / 任一 provider 非 up / search = down ⇒ degraded
   *   - 其余 ⇒ ok
   * 注：`search = unconfigured` **不算**降级（未配置是合法状态，不是故障）；
   *     网关版本与 CLI 版本不一致只登记读数、不参与判定（热更新窗口的正常形态）。
   */
  private[cli] def overallStatus(
    apiReachable: Boolean,
    selfOk: Boolean,
    homeWritable: Boolean,
    providersDown: Int,
    searchStatus: String
  ): String =
    if !apiReachable then StatusDown
    else if !selfOk || !homeWritable || providersDown > 0 || searchStatus == "down" then StatusDegraded
    else StatusOk

  private[cli] def exitCodeFor(status: String): Int = status match
    case StatusOk => 0
    case StatusDegraded => 1
    case _ => 2

  /**
   * `/api/health` 的 `providers` 对象 → 扁平 `Map[provider/model, state]`。
   * 缺该键 / 形态不符 ⇒ 空表（读数面不猜，宁可空）。
   */
  private[cli] def providerMap(health: Json): Map[String, String] =
    health.hcursor
      .downField("providers")
      .as[Json]
      .toOption
      .flatMap(_.asObject)
      .map(_.toMap.map { case (k, v) => k -> v.asString.getOrElse(v.noSpaces) })
      .getOrElse(Map.empty)

  private object HealthShow extends CliSubcommand:
    def name = "show"
    def description = "Show health readings (default)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      for
        port <- GatewayClient.readPort
        // 本命令按离线命令注册（CliRouter.OfflineCommands）：ctx.client 恒为 None，
        // 网关面由本命令自己探 —— 与 `update --device` 的 remoteUpdate 同款
        // （SystemCommands.scala:70 起），不新造客户端、不新造端口读取。
        clientOpt <- GatewayClient.create
        healthResp <- clientOpt match
          case Some(c) => c.get("/api/health").attempt
          case None => IO.pure(Left(new RuntimeException("gateway API not reachable")))
      yield
        val pidOpt = ProcessManager.readPid()
        val pidRunning = pidOpt.exists(ProcessManager.isRunning)

        val javaVer = sys.props.getOrElse("java.version", "unknown")
        val homeDir = ctx.configDir
        val homeExists = os.exists(homeDir)
        val homeWritable = homeExists && homeDir.toIO.canWrite()
        val selfOk = javaOk(javaVer)
        val cliVersion = nebflow.Version.string

        val apiReachable = healthResp.isRight
        val healthJson = healthResp.getOrElse(Json.Null)
        val providers = providerMap(healthJson)
        val providersDown = providers.values.count(s => !isProviderUp(s))
        val searchStatus =
          healthJson.hcursor.downField("search").downField("status").as[String].getOrElse("unreadable")
        val gwVersion = healthJson.hcursor.downField("version").as[String].toOption

        val status = overallStatus(apiReachable, selfOk, homeWritable, providersDown, searchStatus)

        val selfDetail =
          s"cli $cliVersion · java $javaVer" +
            (if selfOk then s" (>= $MinJavaMajor)" else s" (< $MinJavaMajor required)") +
            s" · home ${if homeExists then homeDir.toString else s"$homeDir (missing)"}" +
            (if homeWritable then "" else " (not writable)")

        val gatewayDetail =
          if apiReachable then
            val who = if pidRunning then s"pid ${pidOpt.get}" else "no pid file"
            val ver = gwVersion
              .map(v => s" · api $v" + (if v == cliVersion then "" else s" (cli $cliVersion)"))
              .getOrElse("")
            s"reachable ($who, port $port)$ver"
          else if pidRunning then s"pid file says running (pid ${pidOpt.get}, port $port) but the API is unreachable"
          else s"not running (port $port)"

        val depsDetail =
          if !apiReachable then "unreadable (gateway API not reachable)"
          else
            val up = providers.size - providersDown
            val downList =
              if providersDown == 0 then ""
              else
                val names = providers.filterNot((_, v) => isProviderUp(v)).keys.toList.sorted
                s" (${names.mkString(", ")})"
            s"providers $up up / $providersDown down$downList · search $searchStatus"

        if ctx.json then
          CliResult.Exit(
            exitCodeFor(status),
            Json
              .obj(
                "status" -> status.asJson,
                "self" -> Json.obj(
                  "ok" -> (selfOk && homeWritable).asJson,
                  "cli" -> cliVersion.asJson,
                  "java" -> javaVer.asJson,
                  "javaMajor" -> javaMajor(javaVer).asJson,
                  "javaOk" -> selfOk.asJson,
                  "home" -> homeDir.toString.asJson,
                  "homeExists" -> homeExists.asJson,
                  "homeWritable" -> homeWritable.asJson
                ),
                "gateway" -> Json.obj(
                  "apiReachable" -> apiReachable.asJson,
                  "pidRunning" -> pidRunning.asJson,
                  "pid" -> pidOpt.asJson,
                  "port" -> port.asJson,
                  "version" -> gwVersion.asJson,
                  "versionMatchesCli" -> gwVersion.map(_ == cliVersion).asJson
                ),
                "dependencies" -> Json.obj(
                  "readable" -> apiReachable.asJson,
                  "providersUp" -> (providers.size - providersDown).asJson,
                  "providersDown" -> providersDown.asJson,
                  "providers" -> providers.asJson,
                  "search" -> searchStatus.asJson
                )
              )
              .spaces2
          )
        else
          def mark(ok: Boolean): String = if ok then "✓" else "✗"
          val lines = List(
            s"nebflow $cliVersion — health: $status",
            s"  ${mark(selfOk && homeWritable)} self     $selfDetail",
            s"  ${mark(apiReachable)} gateway  $gatewayDetail",
            s"  ${mark(apiReachable && providersDown == 0 && searchStatus != "down")} deps     $depsDetail"
          )
          CliResult.Exit(exitCodeFor(status), lines.mkString("\n"))
        end if
    end run
  end HealthShow
end HealthCommand
