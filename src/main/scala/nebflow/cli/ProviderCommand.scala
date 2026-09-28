package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

object ProviderCommand extends CliCommand:
  def name = "provider"
  def description = "Manage providers"
  def subcommands = List(ProviderList, ProviderAdd, ProviderRemove, ProviderTest)

  // H6（作者 2026-09-23 07:27 批 · 逐字，含第二行密钥三案说明）。挂在 examples
  // 面是因为子命令帮助的渲染器在 CliRouter（禁改面）——它只印 examples，逐行
  // 加两空格缩进；本类的文案 bytes 与已批文案逐字节一致。
  private[cli] val HelpUsageLine =
    "nebflow provider add <name> --base-url <url> --protocol <anthropic|openai> [--model <id>]..."

  private[cli] val HelpKeyLine =
    "API key: --api-key-stdin (recommended) | --api-key-file <path> | env NEBFLOW_PROVIDER_API_KEY"

  def examples = List(
    "nebflow provider list",
    "nebflow provider test anthropic",
    HelpUsageLine,
    HelpKeyLine
  )

  private object ProviderList extends CliSubcommand:
    def name = "list"
    def description = "List configured providers"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getConfig".asJson)).map { resp =>
            val configStr = resp.hcursor.downField("config").as[String].getOrElse("{}")
            io.circe.parser.parse(configStr) match
              case Right(json) =>
                val providers =
                  json.hcursor.downField("llm").downField("providers").as[Map[String, Json]].getOrElse(Map.empty)
                if ctx.json then CliResult.Json(io.circe.Json.fromFields(providers))
                else
                  val lines = providers.map { case (name, pJson) =>
                    val baseUrl = pJson.hcursor.downField("baseUrl").as[String].getOrElse("?")
                    val protocol = pJson.hcursor.downField("protocol").as[String].getOrElse("?")
                    val modelCount =
                      pJson.hcursor.downField("models").as[List[Json]].toOption.map(_.length).getOrElse(0)
                    s"  $name ($protocol) $baseUrl [$modelCount models]"
                  }.toList
                  if lines.isEmpty then CliResult.text("No providers configured")
                  else CliResult.Text("Providers:" :: lines)
              case Left(_) => CliResult.Error("Failed to parse config")
            end match
          }

  end ProviderList

  /**
   * `provider add <name> --base-url <url> --protocol <p> [--model <id>] [--api-key-stdin |
   * --api-key-file <path> | env NEBFLOW_PROVIDER_API_KEY]`
   *
   * 写 = 既有 `updateConfig` 单帧（同 ConfigCommand.ConfigSet），落点 `llm.providers.<name>`
   * ——服务端 ConfigService.updateConfig 深合并（ConfigService.scala:130 `mergeConfig`）：
   * 同名重复 add 即幂等更新，不会重复追加。
   *
   * 🔴 密钥零回显（D6 族）：本类只把密钥放进 updateConfig 载荷（写路径），
   * 从不写进任何 CliResult / 异常报文；不进 argv（无 `--api-key <字面量>` 形式）。
   * 通道优先级 = `--api-key-stdin` > `--api-key-file` > 环境变量。
   *
   * 注：`--model` 重复出现的累加受限于 CliRouter.parseArgs 的 Map 具名参（禁改面）
   * ⇒ 重复 `--model` 只留最后一个值；多模型走「具名值 + 名称之后的位置参数」。
   */
  private object ProviderAdd extends CliSubcommand:
    def name = "add"
    def description = "Add or update a provider"

    def params = List(
      CliParam("name", None, "Provider name", required = true),
      CliParam("base-url", None, "Provider base URL (http/https)", required = true),
      CliParam("protocol", None, "Wire protocol: anthropic or openai", required = true),
      CliParam("model", None, "Model id", required = false),
      CliParam("api-key-stdin", None, "Read the API key from stdin", required = false, isFlag = true),
      CliParam("api-key-file", None, "Read the API key from a file", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val name = firstPositional(ctx).orElse(ctx.args.get("name")).map(_.trim).getOrElse("")
          if name.isEmpty then IO.pure(CliResult.Error("Provider name required"))
          else
            readApiKey(ctx).flatMap {
              case Left(err) => IO.pure(err)
              case Right(apiKey) =>
                val baseUrl = ctx.args.get("base-url").map(_.trim).getOrElse("")
                val protocol = ctx.args.get("protocol").map(_.trim).getOrElse("")
                val models = modelIds(ctx)
                val payload = providerConfigJson(name, baseUrl, protocol, apiKey, models)
                client
                  .command(
                    Json.obj(
                      "type" -> "updateConfig".asJson,
                      "config" -> payload.spaces2.asJson
                    )
                  )
                  .map { resp => applyConfigResult(resp, s"Provider '$name' saved.") }
                  // 上浮不静默：非 2xx（GatewayClient.parseJson 抛带 HTTP 码的报文）与
                  // 网络异常都必须成为可行动报文（失败面文案沿用 ConfigCommand 既有措辞）。
                  .handleErrorWith(e => IO.pure(CliResult.Error(s"Config update failed: ${msgOf(e)}")))
            }
          end if
  end ProviderAdd

  /**
   * `provider remove <name>` —— 走既有 `updateConfig` 的删除标记（`<name>: null`，
   * ConfigService.scala:238 「null values mean explicit deletion」），并触发既有
   * 引用清理（deletedProviderNames → scrubAgentModelRefs/scrubPresetRefs）。
   *
   * 先读一次配置判存在性（同 `provider list` 的既有读面），不存在 ⇒ 明确报文 + 非零
   * 退出码（不是静默幂等：mergeConfig 对不存在的键是 no-op，只发删除帧会把「名字打错」
   * 静默成成功）。
   */
  private object ProviderRemove extends CliSubcommand:
    def name = "remove"
    def description = "Remove a provider"
    def params = List(CliParam("name", None, "Provider name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val name = firstPositional(ctx).orElse(ctx.args.get("name")).map(_.trim).getOrElse("")
          if name.isEmpty then IO.pure(CliResult.Error("Provider name required"))
          else
            client.command(Json.obj("type" -> "getConfig".asJson)).flatMap { resp =>
              providersOf(resp).get(name) match
                case None => IO.pure(CliResult.Error(s"Provider '$name' not found."))
                case Some(_) =>
                  client
                    .command(
                      Json.obj(
                        "type" -> "updateConfig".asJson,
                        "config" -> removePayload(name).spaces2.asJson
                      )
                    )
                    .map { r => applyConfigResult(r, s"Provider '$name' removed.") }
                    .handleErrorWith(e => IO.pure(CliResult.Error(s"Config update failed: ${msgOf(e)}")))
            }
          end if
  end ProviderRemove

  /**
   * `provider test <name>` —— D-H4 ⒜：真连通。复用既有 REST
   * `POST /api/provider/models`（RestApiRoutes.scala:3288；失败 502 + 报文；掩码
   * `***` 回填在 `:3310`）。body 不带密钥 ⇒ 网关用该 provider 已存密钥探模型面
   * （`RestApiRoutes.scala:3307-3311`）⇒ CLI 侧零密钥接触。
   *
   * H8 逐字（成功/失败两形态）；失败取 id 2（CLI 帮助页已载明「2 unreachable/timeout」）。
   */
  private object ProviderTest extends CliSubcommand:
    def name = "test"
    def description = "Test provider connectivity"
    def params = List(CliParam("name", None, "Provider name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val providerName = firstPositional(ctx).orElse(ctx.args.get("name")).map(_.trim).getOrElse("")
          if providerName.isEmpty then IO.pure(CliResult.Error("Provider name required"))
          else
            client.command(Json.obj("type" -> "getConfig".asJson)).flatMap { resp =>
              providersOf(resp).get(providerName) match
                case None => IO.pure(CliResult.Error(s"Provider '$providerName' not configured."))
                case Some(p) =>
                  val baseUrl = p.hcursor.downField("baseUrl").as[String].getOrElse("").trim
                  val protocol = p.hcursor.downField("protocol").as[String].getOrElse("").trim
                  client
                    .post(
                      "/api/provider/models",
                      Json.obj(
                        "name" -> providerName.asJson,
                        "baseUrl" -> baseUrl.asJson,
                        "protocol" -> protocol.asJson
                      )
                    )
                    .map { r =>
                      val n = r.hcursor.downField("models").as[List[Json]].toOption.map(_.length).getOrElse(0)
                      CliResult.text(reachableMsg(providerName, n))
                    }
                    .handleErrorWith(e => IO.pure(CliResult.Exit(2, unreachableMsg(providerName, e))))
            }
          end if
  end ProviderTest

  // ===== shared helpers =====

  /** 已批文案 H7（密钥缺失/非法），逐字。 */
  private[cli] val MissingKeyMessage =
    "No API key provided. Use --api-key-stdin, --api-key-file <path>, or set NEBFLOW_PROVIDER_API_KEY."

  /** 已批文案 H8 成功形态，逐字。 */
  private[cli] def reachableMsg(name: String, models: Int): String =
    s"Provider '$name' reachable — $models model(s) listed."

  /**
   * 已批文案 H8 失败形态：`Provider '<name>' unreachable: HTTP <code> <报文>`
   * （HTTP 码/报文从 GatewayClient.requestError 的既有报文体里取，
   * GatewayClient.scala:165-181；取不到 HTTP 码时退回原报文，不伪造码位）。
   */
  private[cli] def unreachableMsg(name: String, err: Throwable): String =
    val raw = Option(err.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName)
    HttpErrorPattern.findFirstMatchIn(raw) match
      case Some(m) => s"Provider '$name' unreachable: HTTP ${m.group(1)} ${m.group(2).trim}"
      case None => s"Provider '$name' unreachable: $raw"

  private val HttpErrorPattern = """HTTP (\d+)\)?:\s*(.*)""".r

  private def msgOf(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  private def firstPositional(ctx: CliContext): Option[String] = ctx.positionalArgs.headOption

  /**
   * `llm.providers.<name>` 嵌套（复用既有 ConfigCommand.buildNestedJson，只读：
   * 末段是键名，值按 JSON 解析 ⇒ 传 provider 对象的 JSON 文本即得对象叶子）。
   */
  private[cli] def providerConfigJson(
    name: String,
    baseUrl: String,
    protocol: String,
    apiKey: String,
    modelIds: List[String]
  ): Json =
    val fields = List(
      "baseUrl" -> Json.fromString(baseUrl),
      "protocol" -> Json.fromString(protocol),
      "apiKey" -> Json.fromString(apiKey)
    ) ++ (if modelIds.isEmpty then Nil
          else List("models" -> Json.fromValues(modelIds.distinct.map(m => Json.obj("id" -> Json.fromString(m))))))
    ConfigCommand.buildNestedJson(List("llm", "providers", name), Json.fromFields(fields).spaces2)

  /** 删除载荷：`{"llm":{"providers":{"<name>":null}}}`（null = 删除标记）。 */
  private[cli] def removePayload(name: String): Json =
    ConfigCommand.buildNestedJson(List("llm", "providers", name), "null")

  /**
   * 模型 id：`--model` 的值 + 名称之后的位置参数（兼容 H6 的 `[--model <id>]...` 多值面；
   * 重复 `--model` 的累加受 CliRouter.parseArgs 的 Map 限制，见 ProviderAdd 注）。
   */
  private def modelIds(ctx: CliContext): List[String] =
    (ctx.args.get("model").toList ++ ctx.positionalArgs.drop(1)).map(_.trim).filter(_.nonEmpty)

  /** 配置读取 + 落点下钻（与 ProviderList 同一读面）。 */
  private def providersOf(resp: Json): Map[String, Json] =
    val configStr = resp.hcursor.downField("config").as[String].getOrElse("{}")
    io.circe.parser
      .parse(configStr)
      .toOption
      .flatMap(_.hcursor.downField("llm").downField("providers").as[Map[String, Json]].toOption)
      .getOrElse(Map.empty)

  /**
   * 服务端 updateConfig 的两态出口（镜像 ConfigCommand.ConfigSet 既有口径：
   * `{"type":"error","message":…}` 必须上浮，否则 no-op 会静默成成功）。
   */
  private def applyConfigResult(resp: Json, okMsg: String): CliResult =
    val serverError = for
      t <- resp.hcursor.downField("type").as[String].toOption if t == "error"
      m <- resp.hcursor.downField("message").as[String].toOption
    yield m
    serverError match
      case Some(err) => CliResult.Error(s"Config update rejected: $err")
      case None => CliResult.text(okMsg)

  private[cli] val ApiKeyEnvVar = "NEBFLOW_PROVIDER_API_KEY"

  /**
   * 密钥三案读取（D-H3）。返回 Left = 已定型的可行动失败（含 H7）。
   * 任何分支都不把密钥写进日志/报文；`--api-key-file` 只报路径、不做默认路径。
   */
  private def readApiKey(ctx: CliContext): IO[Either[CliResult, String]] =
    val fileOpt = ctx.args.get("api-key-file").map(_.trim).filter(_.nonEmpty)
    if ctx.args.contains("api-key-stdin") then readApiKeyFromStdin
    else
      fileOpt match
        case Some(path) => readApiKeyFromFile(path)
        case None =>
          IO.pure(
            sys.env
              .get(ApiKeyEnvVar)
              .map(_.trim)
              .filter(_.nonEmpty)
              .map(Right(_))
              .getOrElse(Left(CliResult.Error(MissingKeyMessage)))
          )

  end readApiKey

  /**
   * stdin 案：交互终端走 `Console.readPassword`（不回显、不进终端 scrollback），
   * 管道/重定向（无 console）回落逐行读。空值 = H7。
   */
  private def readApiKeyFromStdin: IO[Either[CliResult, String]] =
    IO.blocking {
      val console = System.console()
      val line =
        if console != null then Option(console.readPassword()).map(_.mkString).getOrElse("")
        else Option(scala.io.StdIn.readLine()).getOrElse("")
      line.trim
    }.map(k => if k.isEmpty then Left(CliResult.Error(MissingKeyMessage)) else Right(k))

  /** 文件案：读后不回显内容；权限过宽（group/other 可读）只警告路径 + 模式位。 */
  private def readApiKeyFromFile(path: String): IO[Either[CliResult, String]] =
    IO.blocking {
      val p = os.Path(path, os.pwd)
      if !os.exists(p) || !os.isFile(p) then Left(CliResult.Error(s"API key file not found: $path"))
      else
        val content = scala.util.Try(os.read(p)).toOption.map(_.trim).getOrElse("")
        if content.isEmpty then Left(CliResult.Error(MissingKeyMessage))
        else
          posixMode(p).foreach { (mode, wide) =>
            if wide then
              Console.err.println(
                s"Warning: $path is group/other readable (mode $mode); chmod 600 $path recommended."
              )
          }
          Right(content)
    }

  /** 权限位（八进制三位）+ 是否 group/other 可及；非 POSIX 或不可读 ⇒ None（不猜、不警告）。 */
  private def posixMode(p: os.Path): Option[(String, Boolean)] =
    scala.util
      .Try(java.nio.file.Files.getPosixFilePermissions(p.toNIO))
      .toOption
      .map { perms =>
        import java.nio.file.attribute.PosixFilePermission as Perm
        def digit(ps: List[(Perm, Int)]): Int =
          ps.foldLeft(0)((acc, f) => if perms.contains(f._1) then acc + f._2 else acc)
        val owner = digit(List(Perm.OWNER_READ -> 4, Perm.OWNER_WRITE -> 2, Perm.OWNER_EXECUTE -> 1))
        val group = digit(List(Perm.GROUP_READ -> 4, Perm.GROUP_WRITE -> 2, Perm.GROUP_EXECUTE -> 1))
        val other = digit(List(Perm.OTHERS_READ -> 4, Perm.OTHERS_WRITE -> 2, Perm.OTHERS_EXECUTE -> 1))
        (s"$owner$group$other", group > 0 || other > 0)
      }

end ProviderCommand
