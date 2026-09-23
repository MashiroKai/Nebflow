package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.PathUtil

object ConfigCommand extends CliCommand:
  def name = "config"
  def description = "Manage configuration"
  def subcommands = List(ConfigGet, ConfigSet, ConfigShow, ConfigEdit, ConfigPathSub, ConfigValidateSub)

  def examples = List(
    "nebflow config show",
    "nebflow config get workSchedule",
    "nebflow config set workSchedule.enabled false",
    "nebflow config path",
    "nebflow config validate"
  )

  private object ConfigGet extends CliSubcommand:
    def name = "get"
    def description = "Get a config value"
    def params = List(CliParam("key", None, "Config key (dot-separated path)", required = false))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getConfig".asJson)).map { resp =>
            val configStr = resp.hcursor.downField("config").as[String].getOrElse("{}")
            val key = ctx.positionalArgs.headOption.orElse(ctx.args.get("key"))
            key match
              case Some(k) =>
                // Navigate dot-separated path
                io.circe.parser.parse(configStr) match
                  case Right(json) =>
                    val value = k
                      .split("\\.")
                      .foldLeft(json)((j, segment) => j.hcursor.downField(segment).as[Json].getOrElse(Json.Null))
                    // C2: text mode prints the value, not pretty JSON (`config
                    // get workSchedule` answered a `{ "enabled" : false }`
                    // block where a scalar was asked for).
                    if ctx.json then CliResult.Json(value)
                    else CliResult.text(value.asString.getOrElse(value.noSpaces))
                  case Left(_) => CliResult.Error("Failed to parse config")
              case None =>
                // Whole-config dump — masked like `config show` (D6).
                val redacted = io.circe.parser.parse(configStr).map(redact).map(_.noSpaces)
                if ctx.json then
                  CliResult.Json(io.circe.parser.parse(redacted.getOrElse(configStr)).getOrElse(Json.Null))
                else CliResult.text(redacted.getOrElse(configStr))
            end match
          }

  end ConfigGet

  private object ConfigSet extends CliSubcommand:
    def name = "set"
    def description = "Set a config value"

    def params = List(
      CliParam("key", None, "Config key", required = true),
      CliParam("value", None, "Config value", required = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val key = ctx.positionalArgs.headOption.orElse(ctx.args.get("key"))
          val value = ctx.positionalArgs.lift(1).orElse(ctx.args.get("value")).getOrElse("")
          if key.isEmpty || value.isEmpty then IO.pure(CliResult.Error("Key and value required"))
          else
            // Build a nested JSON from dot-separated key
            val configJson = buildNestedJson(key.get.split("\\.").toList, value)
            client
              .command(
                Json.obj(
                  "type" -> "updateConfig".asJson,
                  "config" -> configJson.spaces2.asJson
                )
              )
              .flatMap { resp =>
                // 服务端校验拒绝回 {"type":"error","message":...}——必须浮出，
                // 无条件 "Config updated" 会把 no-op 静默成成功（qa #339 打回）
                (for
                  t <- resp.hcursor.downField("type").as[String].toOption if t == "error"
                  m <- resp.hcursor.downField("message").as[String].toOption
                yield m) match
                  case Some(err) => IO.pure(CliResult.Error(s"Config update rejected: $err"))
                  case None => IO.pure(CliResult.text(s"Config updated: ${key.get} = $value"))
              }
              .handleErrorWith(e => IO.pure(CliResult.Error(s"Config update failed: ${e.getMessage}")))
          end if

  end ConfigSet

  /**
   * dot 路径 → 嵌套 JSON。末段路径是键名（与 ConfigGet 的全段下钻对称）。
   * qa #339 打回：旧基例返回裸值、丢末段键——`set a.b.c true` 实发
   * {"b": true} 而非 {"a":{"b":{"c":true}}}，静默写错位置。private[cli]
   * 供 ConfigCommandSpec 直测。
   */
  private[cli] def buildNestedJson(path: List[String], value: String): Json =
    path match
      case Nil => Json.Null
      case last :: Nil =>
        // Value: try JSON parse (true/42/{...}), fallback to bare string
        Json.obj(last -> io.circe.parser.parse(value).getOrElse(Json.fromString(value)))
      case head :: tail =>
        Json.obj(head -> buildNestedJson(tail, value))

  /**
   * D6: mask credentials in a whole-config dump. The design document already
   * promised this ("`config show` = API key 脱敏", §5.2 drift table) while the
   * implementation printed the config verbatim, including provider apiKeys and
   * tokens. Values of secret-named keys become `***`; every other field is
   * passed through unchanged (structure and non-secret values preserved).
   * A targeted `config get <key>` is NOT masked — the user asked for that key
   * by name and a masked answer would be useless.
   *
   * The name test is deliberately narrower than a bare `contains("token")`:
   * `thinkingConfig.budgetTokens` / `*.maxTokens` (llm/config.scala:183) are
   * numeric QUOTA fields, not credentials, and masking them would corrupt a
   * non-secret field (the requirement is "secrets masked, non-secrets as-is").
   * Plural quota names are therefore exempt; singular `token` / `authToken` /
   * `accessToken` and any `apikey|secret|password|passwd|credential` name are
   * masked.
   */
  private[cli] def isSecretKey(key: String): Boolean =
    val k = key.toLowerCase.filter(_.isLetterOrDigit)
    val named =
      k.contains("apikey") || k.contains("secret") || k.contains("password") ||
        k.contains("passwd") || k.contains("credential")
    // "token" only when it is a singular credential-ish name, never a plural quota
    val tokenish = k.startsWith("token") || k.endsWith("token")
    named || tokenish

  private[cli] def redact(json: Json): Json =
    json.fold(
      Json.Null,
      b => Json.fromBoolean(b),
      n => Json.fromJsonNumber(n),
      s => Json.fromString(s),
      arr => Json.fromValues(arr.map(redact)),
      obj =>
        Json.fromFields(obj.toList.map { case (k, v) =>
          if isSecretKey(k) && !v.isNull then k -> (Json.fromString("***"): Json)
          else k -> redact(v)
        })
    )

  private object ConfigShow extends CliSubcommand:
    def name = "show"
    def description = "Show full configuration (credentials masked)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getConfig".asJson)).map { resp =>
            val configStr = resp.hcursor.downField("config").as[String].getOrElse("{}")
            CliResult.text(io.circe.parser.parse(configStr).map(redact).map(_.noSpaces).getOrElse(configStr))
          }

  // ── D-H5 案 ⒜：`config edit` 的无编辑器 / 非交互兜底（作者 2026-09-23 07:27 批）──
  // 缺口（计划卡 §一 config 行 缺口①）：`config edit` 在无 tty / 无 `$EDITOR` 的
  // 环境里照样 `inheritIO` 拉起编辑器 —— headless 下编辑器渲染不出来。基线实测：
  // env 未设时 `vi` 被拉起，往 stdout 吐 11KB 终端控制序列、stderr 两行「不是终端」
  // 警告，而 CLI 仍以 `CliResult.ok` 报成功（exit 0）；`EDITOR=` 形态则抛
  // `Cannot run program ""`。两者都是本批要治的「挂死 / 莫名失败」。
  // 兜底 = 明确报错 + exit 1 + 指路 `config set`；既有优先级 `$EDITOR` → `$VISUAL`
  // → `vi` 一处不动。

  /** H9（§16 文案闸：已批逐字，禁改写、禁加前缀/后缀）。 */
  private[cli] val NoEditorMessage =
    "No editor available (no interactive terminal / no $EDITOR). Use 'nebflow config set <key> <value>' instead."

  /**
   * 非交互判定 = 「无交互终端」。JDK < 22：`System.console()` 为 null 即 stdin/stdout
   * 被重定向（非 null 即终端）；JDK ≥ 22（本机运行面 = 23）`System.console()` 恒非
   * null，终端性只能问 `Console.isTerminal()`（JDK 22 新增）。该法不在本构建的
   * `-release:17` API 面内（build.sbt:105；`javac --release 17` 同报「找不到符号」），
   * 且在 JDK 17 运行面上根本不存在 —— 直接调用必 `NoSuchMethodError`，故按名反射
   * 取；取不到即回到「非 null 即终端」的旧 JDK 语义。实测（本机 JDK 23）：stdin 为
   * pty ⇒ true；stdin 为 /dev/null（无论 stdout 去向）⇒ false。
   */
  private[cli] def hasInteractiveTerminal: Boolean =
    val console = System.console()
    if console == null then false
    else
      try console.getClass.getMethod("isTerminal").invoke(console).asInstanceOf[Boolean]
      catch case _: Exception => true

  /**
   * `$EDITOR` → `$VISUAL`（既有优先级，一处不改）。空值/纯空白等同**未设**：
   * `EDITOR=` 会让 ProcessBuilder 抛 `Cannot run program ""`（见上方缺口实测）。
   */
  private[cli] def pickEditor(editor: Option[String], visual: Option[String]): Option[String] =
    editor.map(_.trim).filter(_.nonEmpty).orElse(visual.map(_.trim).filter(_.nonEmpty))

  /**
   * 兜底判据（纯函数，ConfigEditHeadlessSpec 直测）：**非交互 ∨ 编辑器不可得**。
   * 与 §一 题面逐肢对齐：「非交互（stdin 非 tty）」= 第一肢；「编辑器不可得
   * （`$EDITOR` / `$VISUAL` 均空，或 `vi` 不可执行）」= 第二肢 —— 字面析取下「均空」
   * 即已走兜底，`vi` 可执行与否不改变结论（该子肢被「均空」肢蕴含，故不另判）；
   * `vi` 作为优先级链末位保留在解析面（保留原链形，见 ConfigEdit.run）。
   */
  private[cli] def needsEditorFallback(hasTerminal: Boolean, envEditor: Option[String]): Boolean =
    !hasTerminal || envEditor.isEmpty

  private object ConfigEdit extends CliSubcommand:
    def name = "edit"
    def description = "Open config in $EDITOR"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      val envEditor = pickEditor(sys.env.get("EDITOR"), sys.env.get("VISUAL"))
      if needsEditorFallback(hasInteractiveTerminal, envEditor) then
        // 用 CliResult.Exit 而非 Error：验收要求「仅 H9 逐字、逐字节比对、无额外噪音
        // 行」，Exit 原样打印（Error 会加 `Error: ` 前缀 ⇒ 首个字节即不符）。
        IO.pure(CliResult.Exit(1, NoEditorMessage))
      else
        val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
        // 优先级链末位 `vi`（既有链形保留）：能走到这里 envEditor 必非空，按 D-H5
        // 口径该肢不再参战（env 均空已在上方走兜底）。
        val editor = envEditor.getOrElse("vi")
        IO.blocking {
          val pb = new ProcessBuilder((editor.split("\\s+").toList :+ configPath.toString)*)
          pb.inheritIO().start().waitFor()
        }.as(CliResult.ok)
    end run
  end ConfigEdit
end ConfigCommand
