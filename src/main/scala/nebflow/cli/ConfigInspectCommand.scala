package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.PathUtil

/**
 * `nebflow config path` + `nebflow config validate` —— **只读**两条子命令。
 *
 * 归属说明：两条都挂 `config` 名下（`nebflow config path|validate`），但实现放在
 * 本新件里，`ConfigCommand.scala` 只在其 `subcommands` / `examples` 两行做追加
 * —— 既有 `get/set/show/edit` 分支与脱敏面（`isSecretKey` / `redact`）一字未动，
 * 符合「新件优先、既有件只做必要最小改动」。
 *
 * 无写面：本件不 `os.write` / 不 `os.move` / 不建目录；损坏配置的修复面仍归
 * `nebflow doctor --fix`（SystemCommands.scala DoctorRun 的 Config 节）。
 */

/**
 * `nebflow config path` —— 打印生效的配置文件路径。
 *
 * 打印的是**读路径**（`PathUtil.configJsonReadPath`，与 `ConfigEdit` 同一实现
 * —— 即本实例实际会读到的那个文件；品牌改名窗口下它可能就是 legacy 名）。
 * 文本模式只回一行，便于 `cat "$(nebflow config path)"` 这类管道用法；
 * 其余读数在 `--json` 面。
 */
object ConfigPathSub extends CliSubcommand:
  def name = "path"
  def description = "Print the config file path"

  def params = Nil

  def run(ctx: CliContext): IO[CliResult] =
    IO.blocking {
      val readPath = PathUtil.configJsonReadPath(ctx.configDir)
      if ctx.json then
        CliResult.Json(
          Json.obj(
            "path" -> readPath.toString.asJson,
            "exists" -> os.exists(readPath).asJson,
            "writePath" -> PathUtil.configJsonWritePath(ctx.configDir).toString.asJson,
            "dataRoot" -> ctx.configDir.toString.asJson
          )
        )
      else CliResult.text(readPath.toString)
    }

end ConfigPathSub

/**
 * `nebflow config validate` —— 校验配置文件「存在 / 可读 / 可解析 / 是 JSON 对象」。
 *
 * 三条只读判据，逐条给读数；不修不写。退出码：0 = 合法 · 1 = 不合法（与
 * `health` 同一套「读数即退出码」的脚本消费约定）。
 *
 * D6：解析失败时**只回位置，不回内容**。circe 的 parse 错误信息里会带出错 token
 * 的原文（`expected json value got '...'`），而配置文件里可能含 apiKey/token ——
 * 把这段原文打到 stdout 就是一次密钥回显。本命令因此只提取 `(line N, column M)`
 * 尾巴，token 文本一律丢弃（见 [[sanitizeParseError]]）。
 */
object ConfigValidateSub extends CliSubcommand:
  def name = "validate"
  def description = "Validate the config file (read-only)"

  def params = Nil

  private[cli] val ExitValid = 0
  private[cli] val ExitInvalid = 1

  def run(ctx: CliContext): IO[CliResult] =
    IO.blocking {
      val path = PathUtil.configJsonReadPath(ctx.configDir)
      val exists = os.exists(path)
      val (valid, reason) =
        if !exists then (false, s"not found: $path")
        else
          val content =
            try Right(os.read(path))
            catch case e: Exception => Left(s"unreadable: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
          content match
            case Left(err) => (false, err)
            case Right(text) =>
              io.circe.parser.parse(text) match
                case Left(err) => (false, s"invalid JSON ${sanitizeParseError(err.message)}")
                case Right(json) =>
                  if json.isObject then (true, "valid JSON object")
                  // json.name 是 circe 的形态名（Object/Array/String/Number/Boolean/
                  // Null），不含文件内容 —— 与 doctor 的 Config 节同款措辞。
                  else (false, s"expected a JSON object, got ${json.name}")
      val code = if valid then ExitValid else ExitInvalid
      if ctx.json then
        CliResult.Exit(
          code,
          Json
            .obj(
              "valid" -> valid.asJson,
              "path" -> path.toString.asJson,
              "exists" -> exists.asJson,
              "reason" -> reason.asJson
            )
            .spaces2
        )
      else
        val mark = if valid then "✓" else "✗"
        val hint = if valid then "" else "\n     → repair with 'nebflow doctor --fix' (this command never writes)"
        CliResult.Exit(code, s"  $mark $path: $reason$hint")
      end if
    }
  end run

  /**
   * 把 circe/jawn 的解析失败信息压成「只说错在哪、不说出错内容」。
   *
   * 实测模板（2026-09-20 用**基线 jar 的 doctor 面**复读，见报告 D6 一节）：
   * `expected <what> got '<token>' (line N, column M)` —— `<token>` 是出错处的
   * **原文**，配置文件里就可能正是密钥：`{"apiKey": sk-SECRET}` 的原文信息是
   * `expected json value got 'sk-SEC...' (line 1, column 12)`（jawn 自己还会把
   * token 截成 6 字 + `...`，截断也拦不住回显）。故：
   *   · 位置（`(line …)` 起）与静态期望段（`got` 之前，jawn 的字面模板）留下；
   *   · `got` 段（出错 token 原文）一律丢弃；
   *   · 无位置且无单引号的短信息（如 `exhausted input`，截断类报错）原样留 ——
   *     这类信息不含出错 token 形态；
   *   · 其余（有引号却切不出期望段）⇒ 只回位置，连期望段也不回。
   */
  private[cli] val ParseErrorWithheld =
    "at an unknown position (details withheld: config content may hold credentials)"

  private[cli] def sanitizeParseError(msg: String): String =
    val cleaned = msg.trim
    val parenIdx = cleaned.indexOf("(line")
    val head = cleaned.take(if parenIdx >= 0 then parenIdx else cleaned.length).trim
    val position =
      if parenIdx >= 0 then cleaned.substring(parenIdx).trim.stripPrefix("(").stripSuffix(")") else ""
    val expectation = head.split(" got ", 2).toList.headOption.getOrElse("").trim
    val keep = if head.contains("'") then expectation else head
    if keep.nonEmpty && position.nonEmpty then s"$keep at $position"
    else if keep.nonEmpty then keep
    else if position.nonEmpty then s"at $position"
    else ParseErrorWithheld
end ConfigValidateSub
