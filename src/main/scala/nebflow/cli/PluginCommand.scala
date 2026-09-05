package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/**
 * `nebflow plugin list|add|approve|revoke`（阶段 2b §B.3 CLI 对等，H-14② 裁定：
 * 面板为主 + CLI 供脚本化，对齐 `claude plugin` 形态；协议符合度批补 add——
 * §B.3 外部导入：`plugin add <git-url|本地路径>` → 落为 untrusted 待审）。
 *
 * list/approve/revoke 经 gateway REST（/api/plugins*）——审批写入单点在
 * PluginRegistry（nebflow.json plugins.trust 手术式改写），CLI 不直接写配置
 * 避免与运行实例的内存态/并发写竞争。add 只做文件级安装（copy/clone 进
 * plugins/，不写 nebflow.json——注册表 mtime 缓存自感知新目录），同样经
 * PluginRegistry 单点。
 */
object PluginCommand extends CliCommand:
  def name = "plugin"
  def description = "Manage plugins (trust gate approval)"
  def subcommands = List(PluginList, PluginAdd, PluginApprove, PluginRevoke)
  def examples = List(
    "nebflow plugin list",
    "nebflow plugin add /path/to/my-plugin",
    "nebflow plugin approve web-research",
    "nebflow plugin revoke web-research"
  )

  private object PluginList extends CliSubcommand:
    def name = "list"
    def description = "List plugins with trust status (approval manifest)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.get("/api/plugins").map { resp =>
            if ctx.json then CliResult.Json(resp)
            else
              val plugins = resp.hcursor.downField("plugins").as[List[Json]].getOrElse(Nil)
              val rejected = resp.hcursor.downField("rejected").as[List[Json]].getOrElse(Nil)
              val pluginLines = plugins.map { p =>
                val name = p.hcursor.downField("name").as[String].getOrElse("?")
                val version = p.hcursor.downField("version").as[String].getOrElse("-")
                val trust = p.hcursor.downField("trust").downField("status").as[String].getOrElse("untrusted")
                val skills = p.hcursor.downField("skills").as[List[Json]].getOrElse(Nil).map(_.hcursor.downField("id").as[String].getOrElse("?"))
                val mcp = p.hcursor.downField("mcpServers").as[List[Json]].getOrElse(Nil).map(_.hcursor.downField("server").as[String].getOrElse("?"))
                s"  $name  (v$version, $trust)  skills: ${if skills.isEmpty then "-" else skills.mkString(",")}  mcp: ${if mcp.isEmpty then "-" else mcp.mkString(",")}"
              }
              val rejectedLines = rejected.map { r =>
                val name = r.hcursor.downField("name").as[String].getOrElse("?")
                val reason = r.hcursor.downField("reason").as[String].getOrElse("")
                s"  $name  REJECTED: $reason"
              }
              val all = (if pluginLines.nonEmpty then "Plugins:" :: pluginLines else Nil) ++
                (if rejectedLines.nonEmpty then "Rejected at load:" :: rejectedLines else Nil)
              if all.isEmpty then CliResult.text("No plugins installed (~/.nebflow/plugins/ is empty)")
              else CliResult.Text(all)
          }

  end PluginList

  private object PluginAdd extends CliSubcommand:
    def name = "add"
    def description = "Install a plugin from a git URL or local path (lands as untrusted, default-deny)"
    def params = List(CliParam("source", None, "Git URL or local plugin directory", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      val source = ctx.positionalArgs.headOption.getOrElse("")
      if source.isEmpty then IO.pure(CliResult.Error("Plugin source (git URL or local path) required"))
      else
        nebflow.core.plugin.PluginRegistry.installFrom(source).map {
          case Right(msg) => CliResult.text(msg)
          case Left(err)  => CliResult.Error(err)
        }

  end PluginAdd

  private object PluginApprove extends CliSubcommand:
    def name = "approve"
    def description = "Approve a plugin (record its directory digest into the trust table)"
    def params = List(CliParam("name", None, "Plugin name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      val name = ctx.positionalArgs.headOption.getOrElse("")
      if name.isEmpty then IO.pure(CliResult.Error("Plugin name required"))
      else
        ctx.client match
          case None => IO.pure(CliResult.Error("Gateway not running"))
          case Some(client) =>
            client.post("/api/plugins/" + name + "/approve", Json.obj()).map { resp =>
              val msg = resp.hcursor.downField("message").as[String].toOption
                .orElse(resp.hcursor.downField("error").as[String].toOption)
                .getOrElse(resp.noSpaces)
              if resp.hcursor.downField("ok").as[Boolean].toOption.contains(true) then CliResult.text(msg)
              else CliResult.Error(msg)
            }

  end PluginApprove

  private object PluginRevoke extends CliSubcommand:
    def name = "revoke"
    def description = "Revoke a plugin's approval (falls back to untrusted, default-deny)"
    def params = List(CliParam("name", None, "Plugin name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      val name = ctx.positionalArgs.headOption.getOrElse("")
      if name.isEmpty then IO.pure(CliResult.Error("Plugin name required"))
      else
        ctx.client match
          case None => IO.pure(CliResult.Error("Gateway not running"))
          case Some(client) =>
            client.post("/api/plugins/" + name + "/revoke", Json.obj()).map { resp =>
              val msg = resp.hcursor.downField("message").as[String].toOption
                .orElse(resp.hcursor.downField("error").as[String].toOption)
                .getOrElse(resp.noSpaces)
              if resp.hcursor.downField("ok").as[Boolean].toOption.contains(true) then CliResult.text(msg)
              else CliResult.Error(msg)
            }

  end PluginRevoke
end PluginCommand
