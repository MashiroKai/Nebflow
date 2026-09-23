package nebflow.core.plugin

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

/**
 * 插件**封禁面**（deny-list，2026-09-13 作者令「装了就是信任」）——default-allow +
 * explicit deny。
 *
 * ==为什么独立命名空间（硬约束，勿合并进 `plugins.trust`）==
 * 装载判定改「在位即信任」（`PluginRegistry.loadPlugin`）之后，审批记录
 * `plugins.trust.<name>` **不再决定装载**，但仍有两个用途：① 审计；② seed 覆盖的
 * 仲裁基准（`PluginRegistry.trustRecordDigest` ⇒ `SeedService.reconcilePlugin` 的
 * 「干净快照」判据）。而 `PluginRegistry.writeTrustEntry` 是**整对象替换**
 * （`trust.asObject.add(name, entry)`）——任何 approve（种子自愈安装 / 种子镜像刷新 /
 * REST / CLI）都会重写该键。⇒ **封禁标记若放进 `plugins.trust.<name>` 内，会被任一
 * 自动 approve 静默抹掉**（种子自愈路径 = 用户完全无感的「封禁自动解除」）。
 * 故本面独立成 `nebflow.json → plugins.revoked.<name> = {at, by, reason}`，与
 * `plugins.dispatch` 同族（`PluginDispatchPolicy` 先例：独立表 + 热读 + 手术式改写
 * + append-only 审计）。
 *
 * ==判定顺序（`PluginRegistry.loadPlugin`）==
 * **先查封禁（deny）⇒ 再默认受信（allow）**：封禁 ⇒ `TrustStatus.Blocked`
 * ⇒ `resolve` Left（闸 A/B/C/E 拒）/ 不进目录 / 闸 D 停飞。
 *
 * ==热读与缓存==
 * 读面每次现读 `nebflow.json`（`PluginDispatchPolicy.readTable` 同款）。写面成功后
 * 必须让注册表扫描缓存失效（`PluginRegistry.invalidateCache`）——扫描缓存键是
 * `plugins/` 目录树 mtime 签名，`nebflow.json` 的变化不在其中，否则封禁/解封在
 * ≤30s 重验 tick 之前不可见。
 */
object PluginBlockPolicy:

  private val logger = NebflowLogger.forName("nebflow.plugin.block")

  /** 单包封禁记录（`plugins.revoked.<name>`）。 */
  final case class Entry(at: Long, by: String, reason: String)

  // ── 读面 ──────────────────────────────────────────────────────────

  /**
   * 读 `plugins.revoked` 表（热读；缺失/非法 → 空表 = 全部未封禁，fail-open 到
   * 「在位即信任」的默认语义）。
   */
  def readTable(): Map[String, Json] =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(configPath) then Map.empty
    else
      io.circe.parser
        .parse(os.read(configPath))
        .toOption
        .flatMap(_.hcursor.downField("plugins").downField("revoked").as[Map[String, Json]].toOption)
        .getOrElse(Map.empty)

  def entryFor(name: String): Option[Entry] =
    readTable().get(name).flatMap { j =>
      j.asObject.map { o =>
        Entry(
          at = j.hcursor.downField("at").as[Long].toOption.getOrElse(0L),
          by = j.hcursor.downField("by").as[String].toOption.getOrElse(""),
          reason = j.hcursor.downField("reason").as[String].toOption.getOrElse("")
        )
      }
    }

  def isBlocked(name: String): Boolean = entryFor(name).isDefined

  /** 已封禁包名集合（面板/清单聚合用）。 */
  def blockedNames(): Set[String] = readTable().keySet

  /** 封禁理由的可读渲染（错误消息 / 面板文案单点，避免两处各造一句）。 */
  def describe(e: Entry): String =
    val who = if e.by.nonEmpty then s" by ${e.by}" else ""
    val why = if e.reason.nonEmpty then s": ${e.reason}" else ""
    s"blocked (deny-list${who})${why}"

  // ── 写面（封禁 / 解封，各一次手术式改写 + 一条 append-only 审计）──────────

  /** 封禁：写 `plugins.revoked.<name> = {at, by, reason}`。幂等（重复封禁只刷新记录）。 */
  def block(name: String, reason: String, by: String): IO[Either[String, Unit]] =
    val now = System.currentTimeMillis()
    IO.blocking {
      mutateRevoked(name) { _ =>
        Json.obj("at" -> now.asJson, "by" -> by.asJson, "reason" -> reason.asJson)
      }
    }.flatMap { res =>
      (res match
        case Right(_) =>
          PluginRegistry.invalidateCache()
          audit("blocked", name, by, reason) *>
            IO(
              logger.infoSync(
                s"Plugin '$name' blocked by $by${if reason.nonEmpty then s" ($reason)" else ""} — " +
                  "removed from the catalog, refused at dispatch/load gates, in-flight MCP servers stopped at the next trust revalidation"
              )
            )
        case Left(e) => IO(logger.warnSync(s"Plugin '$name' block refused: $e"))
      ).as(res)
    }

  end block

  /**
   * 解封：删除 `plugins.revoked.<name>`。幂等（无记录 ⇒ Left 提示，不静默成功——
   * 「零静默」纪律：面板/CLI 需要能区分「确实解封了」与「本来就没封」）。
   */
  def unblock(name: String, by: String): IO[Either[String, Unit]] =
    IO.blocking {
      if !isBlocked(name) then Left(s"Plugin '$name' is not blocked — nothing to unblock")
      else removeRevoked(name)
    }.flatMap { res =>
      (res match
        case Right(_) =>
          PluginRegistry.invalidateCache()
          audit("unblocked", name, by, "") *>
            IO(logger.infoSync(s"Plugin '$name' unblocked by $by — falls back to presence trust (trusted on disk)"))
        case Left(e) => IO(logger.warnSync(s"Plugin '$name' unblock refused: $e"))
      ).as(res)
    }

  /** 面板/CLI 展示用的封禁审计条目（本面读面单点；空 reason 不出字段）。 */
  def entryJson(name: String): Option[Json] =
    entryFor(name).map(e => Json.obj("at" -> e.at.asJson, "by" -> e.by.asJson, "reason" -> e.reason.asJson))

  // ── 内部：nebflow.json 手术式改写（保留全部其他键；`plugins.revoked` 独立命名空间）
  // 口径与 `PluginRegistry.mutateNebflowJson` / `PluginDispatchPolicy.mutateDispatch` 同款
  // （同文件、不同键族，互不覆盖）。 ──

  private def mutateRevoked(name: String)(entry: Json => Json): Either[String, Unit] =
    mutatePluginsMap("revoked", name)(Some(entry(Json.obj())))

  private def removeRevoked(name: String): Either[String, Unit] =
    mutatePluginsMap("revoked", name)(None)

  /**
   * `plugins.<table>.<name>` 的通用手术式改写：`Some(v)` = 置值，`None` = 删键。
   * 表为空且删键 ⇒ 保留空对象（不删表本身：写面语义恒定，读面 absent 与 `{}` 等价）。
   */
  private def mutatePluginsMap(table: String, name: String)(value: Option[Json]): Either[String, Unit] =
    val configPath = PathUtil.configJsonWritePath(PathUtil.dataRoot)
    if !os.exists(configPath) then
      val tableJson = value match
        case Some(v) => Json.obj(name -> v)
        case None => Json.obj()
      AtomicJson.writeSync(configPath, Json.obj("plugins" -> Json.obj(table -> tableJson)).noSpaces)
      Right(())
    else
      io.circe.parser.parse(os.read(configPath)) match
        case Left(err) =>
          Left(s"nebflow.json unparseable — refusing to rewrite for plugin block update: ${err.message}")
        case Right(root) =>
          val plugins = root.hcursor.downField("plugins").focus.getOrElse(Json.obj())
          val current = plugins.hcursor.downField(table).focus.getOrElse(Json.obj())
          val next = value match
            case Some(v) => Json.fromJsonObject(current.asObject.getOrElse(JsonObject.empty).add(name, v))
            case None => Json.fromJsonObject(current.asObject.getOrElse(JsonObject.empty).remove(name))
          val newPlugins = Json.fromJsonObject(
            plugins.asObject.getOrElse(JsonObject.empty).add(table, next)
          )
          val out = Json.fromJsonObject(
            root.asObject.getOrElse(JsonObject.empty).add("plugins", newPlugins)
          )
          AtomicJson.writeSync(configPath, out.noSpaces)
          Right(())

    end if

  end mutatePluginsMap

  /**
   * append-only 审计（与 `logs/plugin-dispatch.jsonl` 同族）：每次写侧动作一条——
   * 「谁/何时/因何封禁」可从本文件重放。best-effort（审计失败不影响判定，只 WARN）。
   */
  private def audit(event: String, name: String, by: String, reason: String): IO[Unit] =
    IO.blocking {
      val fields = List(
        "ts" -> System.currentTimeMillis().toString,
        "event" -> event,
        "name" -> name,
        "by" -> by
      ) ++ (if reason.nonEmpty then List("reason" -> reason) else Nil)
      val line = fields
        .map { case (k, v) => s""""$k":${Json.fromString(v).noSpaces}""" }
        .mkString("{", ",", "}")
      os.write.append(PathUtil.dataRoot / "logs" / "plugin-block.jsonl", line + "\n", createFolders = true)
    }.void
      .handleErrorWith(e => IO(logger.warnSync(s"plugin block audit append failed ($name/$event): ${e.getMessage}")))

end PluginBlockPolicy
