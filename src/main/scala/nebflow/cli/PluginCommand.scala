package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/**
 * `stand plugin list|add|approve|revoke|unblock|enable|disable`（阶段 2b §B.3 CLI
 * 对等，H-14② 裁定：面板为主 + CLI 供脚本化，对齐 `claude plugin` 形态；协议符合度批
 * 补 add——§B.3 外部导入：`plugin add <git-url|本地路径>` → 落盘即生效）。
 *
 * **无审批批 2026-09-13（作者令「装了就是信任」）语义**：
 * - 装载判定 = **在位即信任**（`~/.nebflow/plugins/<name>/` 有目录即可用）——`approve`
 *   **不再是必经动作**，仅作审计记录 + seed 覆盖仲裁基准（兼容保留）。
 * - `revoke` = **封禁**（deny-list，写 `plugins.revoked.<name>`，独立命名空间 ⇒ 不会被
 *   任何 approve 抹掉）；`unblock` = 解封。封禁会让包出目录、拒装载、在飞 MCP 30s 内停。
 * - `enable|disable` 仍是**派发开关**（令 1，2026-09-12）：只影响**未来派发**，
 *   在飞/已派发节点零影响。两族语义不同，勿混用。
 *
 * list/approve/revoke/unblock 经 gateway REST（/api/plugins*）——写入单点在
 * PluginRegistry / PluginBlockPolicy（nebflow.json 手术式改写），CLI 不直接写配置
 * 避免与运行实例的内存态/并发写竞争。add 只做文件级安装（copy/clone 进 plugins/，
 * 不写 nebflow.json——注册表 mtime 缓存自感知新目录），同样经 PluginRegistry 单点。
 */
object PluginCommand extends CliCommand:
  def name = "plugin"
  def description = "Manage plugins (installed = trusted; block/unblock a package)"
  def subcommands = List(PluginList, PluginAdd, PluginSources, PluginUpdates, PluginUpdate, PluginApprove, PluginRevoke, PluginUnblock, PluginRemove, PluginEnable, PluginDisable)

  def examples = List(
    "stand plugin list",
    "stand plugin add /path/to/my-plugin",
    "stand plugin add visual-report",
    "stand plugin add visual-report@1.6.0",
    "stand plugin add https://github.com/o/r.git#v1.2.0",
    "stand plugin add --from /path/to/source-spec.json",
    "stand plugin sources",
    "stand plugin updates",
    "stand plugin update stand-dev",
    "stand plugin revoke web-research",
    "stand plugin unblock web-research",
    "stand plugin remove web-research",
    "stand plugin disable web-research",
    "stand plugin enable web-research"
  )

  private object PluginList extends CliSubcommand:
    def name = "list"
    def description = "List plugins with content-face status (manifest: trusted / blocked / contentChanged)"
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
                val trust = p.hcursor.downField("trust").downField("status").as[String].getOrElse("blocked")
                // 无审批批（2026-09-13）：blocked / contentChanged 为独立维度，逐项打标
                val marks = List(
                  Option.when(p.hcursor.downField("blocked").as[Boolean].getOrElse(false))("BLOCKED"),
                  Option.when(p.hcursor.downField("contentChanged").as[Boolean].getOrElse(false))("content-changed")
                ).flatten
                val mark = if marks.isEmpty then "" else s", ${marks.mkString(", ")}"
                val skills = p.hcursor
                  .downField("skills")
                  .as[List[Json]]
                  .getOrElse(Nil)
                  .map(_.hcursor.downField("id").as[String].getOrElse("?"))
                val mcp = p.hcursor
                  .downField("mcpServers")
                  .as[List[Json]]
                  .getOrElse(Nil)
                  .map(_.hcursor.downField("server").as[String].getOrElse("?"))
                s"  $name  (v$version, $trust$mark)  skills: ${
                    if skills.isEmpty then "-" else skills.mkString(",")
                  }  mcp: ${if mcp.isEmpty then "-" else mcp.mkString(",")}"
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
    def description =
      "Install a plugin: official id (name[@version]) | git URL (#tag required — floating HEAD is forbidden) | " +
        "local path | --from <spec.json>. Active on the next scan — no approval step."
    def params = List(
      CliParam("source", None, "Official plugin id (name[@version]) | git URL (#tag) | local plugin directory"),
      CliParam("from", None, "Path to a JSON source spec file ({kind: official | git | https, ...})")
    )

    def run(ctx: CliContext): IO[CliResult] =
      val source = ctx.positionalArgs.headOption.getOrElse("")
      val from = ctx.args.get("from").map(_.trim).filter(_.nonEmpty).filterNot(_ == "true")
      (from, source.isEmpty) match
        case (Some(specPath), _) =>
          IO.blocking(os.read(os.Path(specPath, os.pwd))).attempt.flatMap {
            case Left(err) =>
              IO.pure(CliResult.Error(s"cannot read source spec file '$specPath' (${err.getClass.getSimpleName}: ${err.getMessage})"))
            case Right(raw) =>
              nebflow.core.plugin.PluginRegistry.installFromSpecJson(raw).map {
                case Right(msg) => CliResult.text(msg)
                case Left(err)  => CliResult.Error(err)
              }
          }
        case (None, false) =>
          nebflow.core.plugin.PluginRegistry.installFrom(source).map {
            case Right(msg) => CliResult.text(msg)
            case Left(err)  => CliResult.Error(err)
          }
        case (None, true) =>
          IO.pure(CliResult.Error(
            "Plugin source required — official id (name[@version]), git URL ('<url>#<tag>'), local plugin directory, " +
              "or --from <spec.json>"
          ))
      end match
    end run

  end PluginAdd

  private object PluginSources extends CliSubcommand:
    def name = "sources"
    def description = "Show per-plugin install provenance (source ledger: via / kind / digest — where each plugin came from)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.get("/api/plugins/sources").map { resp =>
            if ctx.json then CliResult.Json(resp)
            else
              val sources = resp.hcursor.downField("sources").as[List[Json]].getOrElse(Nil)
              val unknown = resp.hcursor.downField("unknownSources").as[List[Json]].getOrElse(Nil)
              val lines = sources.map { s =>
                val name = s.hcursor.downField("name").as[String].getOrElse("?")
                val version = s.hcursor.downField("version").as[String].getOrElse("-")
                val via = s.hcursor.downField("via").as[String].getOrElse("?")
                val kind = s.hcursor.downField("kind").as[String].getOrElse("?")
                val digest = s.hcursor.downField("digest").as[String].map(_.take(12) + "…").getOrElse("-")
                val url = s.hcursor.downField("url").as[String].toOption
                s"  $name  (v$version, via=$via kind=$kind, digest $digest)${url.fold("")(u => s"  ← $u")}"
              } ++ unknown.map { u =>
                val name = u.hcursor.downField("name").as[String].getOrElse("?")
                s"  $name  (unknown-source: provenance not recorded or unreadable)"
              }
              if lines.isEmpty then CliResult.text("No plugins installed (~/.nebflow/plugins/ is empty)")
              else CliResult.Text("Plugin sources:" :: lines)
          }

  end PluginSources

  /**
   * `stand plugin updates` —— 源化插件的更新面（作者令 2026-11-01 22:55「运行时插件不是应该
   * 要能热更新吗，立即使用我们最新的版本」）。逐包给出源 / 已装版本 / 远端版本 / 状态；
   * 有新版的行带 `[update]` 提示。**用户驱动**：本命令就是触发点（每包一次 ls-remote +
   * 浅克隆读版本号），无后台轮询。远端不可达 ⇒ `unreachable` + 原因，包照常可用。
   */
  private object PluginUpdates extends CliSubcommand:
    def name = "updates"
    def description = "Check the declared plugin sources for newer versions (source-managed plugins only; zero background polling)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.get("/api/plugins/updates").map { resp =>
            if ctx.json then CliResult.Json(resp)
            else
              val sources = resp.hcursor.downField("sources").as[List[Json]].getOrElse(Nil)
              val updatable = resp.hcursor.downField("updatable").as[Int].toOption.getOrElse(0)
              val lines = sources.map { s =>
                val name = s.hcursor.downField("name").as[String].getOrElse("?")
                val state = s.hcursor.downField("state").as[String].getOrElse("?")
                val src = s.hcursor.downField("source").as[String].getOrElse("")
                val inst = s.hcursor.downField("installedVersion").as[String].toOption.getOrElse("")
                val remote = s.hcursor.downField("remoteVersion").as[String].toOption.getOrElse("")
                val mark = if s.hcursor.downField("updateAvailable").as[Boolean].toOption.getOrElse(false) then "  [update]" else ""
                val ver =
                  if remote.nonEmpty then s"v${if inst.isEmpty then "?" else inst} → v$remote"
                  else s"v$inst"
                s"  $name  ($ver, $state)$mark  ← $src"
              }
              if lines.isEmpty then CliResult.text("No source-managed plugins declared (nebflow.json → plugins.sources)")
              else CliResult.Text(s"Plugin sources ($updatable update(s) available):" :: lines)
          }

  end PluginUpdates

  /**
   * `stand plugin update <name> [--commit <40-hex>]` —— 拉取更新并生效。
   * 落盘语义 = 暂存拉取 → 校验 → 备份旧版 → 原子换装 → 账本落 pin → 审计行；
   * **零重启**（装载读面是 mtime 缓存，换装后下一条读命令即见新内容）。
   * 失败 ⇒ Error + 人读原因，在位版本零变化（远端不可达不是事故，是「保持本地版本」）。
   */
  private object PluginUpdate extends CliSubcommand:
    def name = "update"
    def description =
      "Pull the latest version of a source-managed plugin and activate it (zero restart; keeps the old version if the source is unreachable)"
    def params = List(
      CliParam("name", None, "Plugin name (must have a source declaration)", required = true),
      CliParam("commit", None, "Pin the update to a specific 40-hex commit instead of the branch tip")
    )

    def run(ctx: CliContext): IO[CliResult] =
      val name = ctx.positionalArgs.headOption.getOrElse("")
      val commit = ctx.args.get("commit").map(_.trim).filter(_.nonEmpty).filterNot(_ == "true")
      if name.isEmpty then IO.pure(CliResult.Error("Plugin name required — stand plugin update <name>"))
      else
        ctx.client match
          case None => IO.pure(CliResult.Error("Gateway not running"))
          case Some(client) =>
            val body = commit.fold(Json.obj())(c => Json.obj("commit" -> Json.fromString(c)))
            client.post("/api/plugins/" + name + "/source/update", body).map { resp =>
              val msg = resp.hcursor
                .downField("message")
                .as[String]
                .toOption
                .orElse(resp.hcursor.downField("error").as[String].toOption)
                .getOrElse(resp.noSpaces)
              if resp.hcursor.downField("ok").as[Boolean].toOption.contains(true) then CliResult.text(msg)
              else CliResult.Error(msg)
            }
    end run

  end PluginUpdate

  private object PluginApprove extends CliSubcommand:
    def name = "approve"

    def description =
      "Record a plugin's current directory digest (audit + seed-reconcile baseline; not a gate any more)"
    def params = List(CliParam("name", None, "Plugin name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      val name = ctx.positionalArgs.headOption.getOrElse("")
      if name.isEmpty then IO.pure(CliResult.Error("Plugin name required"))
      else
        ctx.client match
          case None => IO.pure(CliResult.Error("Gateway not running"))
          case Some(client) =>
            client.post("/api/plugins/" + name + "/approve", Json.obj()).map { resp =>
              val msg = resp.hcursor
                .downField("message")
                .as[String]
                .toOption
                .orElse(resp.hcursor.downField("error").as[String].toOption)
                .getOrElse(resp.noSpaces)
              if resp.hcursor.downField("ok").as[Boolean].toOption.contains(true) then CliResult.text(msg)
              else CliResult.Error(msg)
            }
      end if
    end run

  end PluginApprove

  private object PluginRevoke extends CliSubcommand:
    def name = "revoke"

    def description =
      "Block a plugin (deny-list): leaves the catalog, refused at dispatch/load, in-flight MCP stopped within 30s"
    def params = List(CliParam("name", None, "Plugin name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      postPluginAction(ctx, "/api/plugins/" + ctx.positionalArgs.headOption.getOrElse("") + "/revoke")

  end PluginRevoke

  private object PluginUnblock extends CliSubcommand:
    def name = "unblock"
    def description = "Unblock a plugin — back to presence trust (catalog + new dispatches restored)"
    def params = List(CliParam("name", None, "Plugin name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      postPluginAction(ctx, "/api/plugins/" + ctx.positionalArgs.headOption.getOrElse("") + "/unblock")

  end PluginUnblock

  private object PluginRemove extends CliSubcommand:
    def name = "remove"

    def description =
      "Uninstall a plugin: out of the registry (plugins.removed) + delete its directory by default; " +
        "--keep-dir keeps the directory (disable-only leg). The seed will not reinstall it"
    def params = List(
      CliParam("name", None, "Plugin name", required = true),
      CliParam("keep-dir", None, "Keep the plugin directory (remove from the registry only)", isFlag = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      val name = ctx.positionalArgs.headOption.getOrElse("")
      if name.isEmpty then IO.pure(CliResult.Error("Plugin name required"))
      else
        ctx.client match
          case None => IO.pure(CliResult.Error("Gateway not running"))
          case Some(client) =>
            val keepDir = ctx.args.get("keep-dir").exists(_.equalsIgnoreCase("true"))
            val body = Json.obj("deleteDir" -> (!keepDir).asJson)
            client.post("/api/plugins/" + name + "/remove", body).map { resp =>
              val msg = resp.hcursor
                .downField("message")
                .as[String]
                .toOption
                .orElse(resp.hcursor.downField("error").as[String].toOption)
                .getOrElse(resp.noSpaces)
              if resp.hcursor.downField("ok").as[Boolean].toOption.contains(true) then CliResult.text(msg)
              else CliResult.Error(msg)
            }
      end if
    end run

  end PluginRemove

  /** revoke/unblock 的 REST 调用单点（两端点同形：{ok, message|error}）。 */
  private def postPluginAction(ctx: CliContext, path: String): IO[CliResult] =
    val name = ctx.positionalArgs.headOption.getOrElse("")
    if name.isEmpty then IO.pure(CliResult.Error("Plugin name required"))
    else
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.post(path, Json.obj()).map { resp =>
            val msg = resp.hcursor
              .downField("message")
              .as[String]
              .toOption
              .orElse(resp.hcursor.downField("error").as[String].toOption)
              .getOrElse(resp.noSpaces)
            if resp.hcursor.downField("ok").as[Boolean].toOption.contains(true) then CliResult.text(msg)
            else CliResult.Error(msg)
          }

    end if

  end postPluginAction

  /**
   * 令 1 派发开关（2026-09-12）：关闭 = 不再把该能力派给新节点（闸 A），
   * **不影响**已在飞/已派发节点（闸 B/C/E/D 只判内容面）。
   */
  private object PluginDisable extends CliSubcommand:
    def name = "disable"
    def description = "Disable dispatch of a plugin to NEW nodes (in-flight nodes are unaffected)"
    def params = List(CliParam("name", None, "Plugin name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      dispatchSwitch(ctx, "disable", enable = false)

  end PluginDisable

  private object PluginEnable extends CliSubcommand:
    def name = "enable"
    def description = "Re-enable dispatch of a plugin to new nodes"
    def params = List(CliParam("name", None, "Plugin name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      dispatchSwitch(ctx, "enable", enable = true)

  end PluginEnable

  /** enable/disable 的 REST 调用单点（两端点同形，差别只在路径）。 */
  private def dispatchSwitch(ctx: CliContext, endpoint: String, enable: Boolean): IO[CliResult] =
    val name = ctx.positionalArgs.headOption.getOrElse("")
    if name.isEmpty then IO.pure(CliResult.Error("Plugin name required"))
    else
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.post("/api/plugins/" + name + "/" + endpoint, Json.obj()).map { resp =>
            val msg = resp.hcursor
              .downField("message")
              .as[String]
              .toOption
              .orElse(resp.hcursor.downField("error").as[String].toOption)
              .getOrElse(resp.noSpaces)
            if resp.hcursor.downField("ok").as[Boolean].toOption.contains(true) then CliResult.text(msg)
            else CliResult.Error(msg)
          }
    end if
  end dispatchSwitch
end PluginCommand
