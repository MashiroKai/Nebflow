package nebflow.core.plugin

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

/**
 * 插件**派发许可面**（令 1「插件开关语义」单一策略点，2026-09-12 作者 14:14 原话：
 * 「插件的开关，应该只影响任务分发器对未来节点的派发，而不能影响目前的」）。
 *
 * ==根因（设计件 §0/§1.2，本实现只解这一条）==
 * 现状把「**内容信任**」与「**派发许可**」压在同一布尔面 `plugins.trust.<name>` 的
 * 存在性上，而该面在**五个时刻**被判定：A 建点校验（`NodeTools`）/ B spawn 装载门
 * （`NodeEngine.prepareNodePlugins`）/ C crash-recovery resume / E loop 双会话 /
 * D 运行期 30s 重验（`PluginMcpManager.revalidate`）。于是「关闭插件」这一**派发
 * 决策**会穿透到 B/C/E/D ⇒ 已在飞/已派发的节点被拒启动或被抽走工具面。
 *
 * ==拆面（本实现的形态，与设计 R1 推荐 C 一致）==
 * - **内容信任面** = `plugins.trust.<name>`（形状零改动，仍默认拒绝、仍「改内容即
 *   重审」）⇒ 它继续喂 B/C/E/D（`PluginRegistry.resolve` / `revalidate`），语义不变；
 * - **派发许可面** = `plugins.dispatch.<name>`（本文件，**新增**）：只喂 **闸 A**
 *   （`NodeTools` 建点/编辑校验 = 新派发决策点）。
 *
 * 不变量：
 *  1. 有效派发许可 `dispatchAllowed = trusted && (authorEnabled || transitionActive)`
 *     —— 内容面**永远在最前**（安全不降级：未批准/摘要漂移的包任何路径都拿不到）；
 *  2. **过渡授权只能放宽、不能收紧**：`transition` 永远不能把 `authorEnabled=true`
 *     变成 false，也永远不能绕过 `trusted`（满足设计 S7/R8 的幂等性）；
 *  3. **兼容默认零迁移**：无 `dispatch` 记录 ⇒ `authorEnabled` 缺省 true ⇒ 有效值
 *     逐字等于今天（`trusted ⇒ 可派发`）。16 个现存包一个字节都不用改。
 *
 * ==与旧动作的边界（设计 R9-A）==
 * `approve` / `revoke` **保留原义** = 内容信任的授予 / 撤回（`revoke` 会停用在飞
 * MCP —— 那是「收回已授予的内容」的正当表达）。**作者的「关闭插件」动作改走本面**
 * （面板开关 / `POST /api/plugins/:name/enable|disable` / CLI `plugin enable|disable`）
 * ⇒ 关闭只挡住未来派发，在飞节点零影响。两个动作在面板上必须视觉可分（设计 R1 代价①）。
 */
object PluginDispatchPolicy:

  private val logger = NebflowLogger.forName("nebflow.plugin.dispatch")

  /** 过渡授权记录（设计 §3.1：`transition` 层——带出处、可失效、**只放宽**）。 */
  final case class Transition(
    enabled: Boolean,
    grantedBy: Option[String],
    grantedAt: Option[Long],
    /** TTL 兜底（`None` = 永不过期，须显式 clear）。 */
    expiresAt: Option[Long],
    /** 在飞引用集（先到先失效；本批只落数据，refs 归零的自动失效判据属 R8 未做项）。 */
    refs: List[String],
    reason: Option[String],
    cleared: Boolean = false
  )

  /** 单包派发面记录（`authorDecided` = 作者是否显式表态过；缺省 = 从未表态）。 */
  final case class Entry(
    authorEnabled: Boolean,
    authorDecidedAt: Option[Long],
    authorDecidedBy: Option[String],
    transition: Option[Transition],
    /** 表里是否存在该键（缺省 = 跟随内容信任面，零迁移）。 */
    present: Boolean
  )

  // ── 读面 ──────────────────────────────────────────────────────────

  /** 读 `plugins.dispatch` 表（热读 nebflow.json；缺失/非法 → 空表，fail-open 到
    * 「跟随 trust」的兼容默认）。 */
  def readTable(): Map[String, Json] =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(configPath) then Map.empty
    else
      io.circe.parser
        .parse(os.read(configPath))
        .toOption
        .flatMap(_.hcursor.downField("plugins").downField("dispatch").as[Map[String, Json]].toOption)
        .getOrElse(Map.empty)

  def entryFor(name: String): Option[Entry] =
    readTable().get(name).flatMap(decode)

  private def decode(j: Json): Option[Entry] =
    j.asObject.map { o =>
      val c = j.hcursor
      val author = c.downField("authorEnabled").as[Boolean].toOption
      val tr = c.downField("transition").focus.flatMap(_.asObject).map { t =>
        val tc = t.toJson.hcursor
        Transition(
          enabled = tc.downField("enabled").as[Boolean].toOption.getOrElse(true),
          grantedBy = tc.downField("grantedBy").as[String].toOption,
          grantedAt = tc.downField("grantedAt").as[Long].toOption,
          expiresAt = tc.downField("expiresAt").as[Long].toOption,
          refs = tc.downField("refs").as[List[String]].toOption.getOrElse(Nil),
          reason = tc.downField("reason").as[String].toOption,
          cleared = false
        )
      }
      Entry(
        // 兼容默认：无表 / 无该键 / 无 authorEnabled 字段 ⇒ true（跟随 trust）
        authorEnabled = author.getOrElse(true),
        authorDecidedAt = c.downField("authorDecidedAt").as[Long].toOption,
        authorDecidedBy = c.downField("authorDecidedBy").as[String].toOption,
        transition = tr,
        present = o.nonEmpty
      )
    }

  /** 作者意图层（durable）。无记录 ⇒ true（= 跟随内容信任面，零迁移）。 */
  def authorEnabled(name: String): Boolean =
    entryFor(name).forall(_.authorEnabled)

  /** 过渡层是否仍有约束力（`expiresAt` 到期即失效 ⇒ 有效值**自动**回落作者意图）。 */
  def transitionActive(name: String, now: Long): Boolean =
    entryFor(name).flatMap(_.transition) match
      case Some(t) =>
        t.enabled && !t.cleared && t.expiresAt.forall(_ > now)
      case None => false

  /** 单点纯函数（设计 §3.1）：有效派发许可 = trusted ∧ (authorEnabled ∨ transition)。
    * `trusted` 由调用方从内容信任面（`PluginRegistry`）取——两面的**唯一耦合点**。 */
  def effective(name: String, trusted: Boolean, now: Long = System.currentTimeMillis()): Boolean =
    trusted && (authorEnabled(name) || transitionActive(name, now))

  /** 面板/清单展示用（不参与判定；判定只用 [[effective]]）。 */
  def disabledNames(trustedNames: Set[String]): Set[String] =
    trustedNames.filterNot(effective(_, trusted = true))

  // ── 写面（两条独立通路，互不覆盖；每次写入落一条 append-only 审计）─────────

  /** 作者开关（durable 意图层）：面板 / REST `enable|disable` / CLI 的唯一写点。
    * 只动 `authorEnabled` 一族键——`transition` **原样保留**（写侧互不覆盖）。 */
  def setAuthorEnabled(name: String, enabled: Boolean, by: String): IO[Either[String, Unit]] =
    val now = System.currentTimeMillis()
    IO.blocking {
      mutateDispatch(name) { existing =>
        val base = existing.asObject.getOrElse(JsonObject.empty)
        Json.fromJsonObject(
          base
            .add("authorEnabled", enabled.asJson)
            .add("authorDecidedAt", now.asJson)
            .add("authorDecidedBy", by.asJson)
        )
      }
    }.flatMap { res =>
      (res match
        case Right(_) =>
          audit("authorEnabled", name, enabled = Some(enabled), by = Some(by), extra = Map.empty) *>
            IO(logger.infoSync(
              s"Plugin '$name' dispatch ${if enabled then "enabled" else "disabled"} by $by — " +
                s"affects FUTURE dispatches only (in-flight nodes keep their plugin grant)"))
        case Left(e) => IO(logger.warnSync(s"Plugin '$name' dispatch switch refused: $e"))).as(res)
    }

  /** 过渡授权（**只放宽**）：带 TTL 兜底；到期自动失效、零人工复关。 */
  def grantTransition(
    name: String,
    ttlSecs: Long,
    refs: List[String],
    reason: String,
    by: String
  ): IO[Either[String, Unit]] =
    val now = System.currentTimeMillis()
    val expiresAt = if ttlSecs > 0 then Some(now + ttlSecs * 1000L) else None
    IO.blocking {
      mutateDispatch(name) { existing =>
        val base = existing.asObject.getOrElse(JsonObject.empty)
        val tr = Json.fromJsonObject(
          JsonObject.empty
            .add("enabled", true.asJson)
            .add("grantedBy", by.asJson)
            .add("grantedAt", now.asJson)
            .add("expiresAt", expiresAt.map(_.asJson).getOrElse(Json.Null))
            .add("refs", refs.asJson)
            .add("reason", reason.asJson)
        )
        Json.fromJsonObject(base.add("transition", tr))
      }
    }.flatMap { res =>
      (res match
        case Right(_) =>
          audit("transition-granted", name, enabled = Some(true), by = Some(by),
            extra = Map("expiresAt" -> expiresAt.map(_.toString).getOrElse("none"), "refs" -> refs.mkString(",")))
        case Left(e) => IO(logger.warnSync(s"Plugin '$name' transition grant refused: $e"))).as(res)
    }

  /** 显式结束过渡（幂等 no-op 语义：重复调用只是再写一次同样的「已清」态）。 */
  def clearTransition(name: String, by: String): IO[Either[String, Unit]] =
    val now = System.currentTimeMillis()
    IO.blocking {
      mutateDispatch(name) { existing =>
        val base = existing.asObject.getOrElse(JsonObject.empty)
        val tr = Json.fromJsonObject(
          JsonObject.empty
            .add("enabled", false.asJson)
            .add("grantedBy", by.asJson)
            .add("grantedAt", now.asJson)
            .add("refs", Json.arr())
        )
        Json.fromJsonObject(base.add("transition", tr))
      }
    }.flatMap { res =>
      (res match
        case Right(_) =>
          audit("transition-cleared", name, enabled = Some(false), by = Some(by), extra = Map.empty)
        case Left(e) => IO(logger.warnSync(s"Plugin '$name' transition clear refused: $e"))).as(res)
    }

  // ── 内部：nebflow.json 手术式改写（保留全部其他键；`plugins.dispatch` 独立命名空间）
  // 口径与 `PluginRegistry.mutateNebflowJson` 同款（同文件、不同键族，互不覆盖）。 ──

  private def mutateDispatch(name: String)(transform: Json => Json): Either[String, Unit] =
    val configPath = PathUtil.configJsonWritePath(PathUtil.dataRoot)
    if !os.exists(configPath) then
      val fresh = Json.obj("plugins" -> Json.obj("dispatch" -> Json.obj(name -> transform(Json.obj()))))
      AtomicJson.writeSync(configPath, fresh.noSpaces)
      Right(())
    else
      io.circe.parser.parse(os.read(configPath)) match
        case Left(err) =>
          Left(s"nebflow.json unparseable — refusing to rewrite for dispatch update: ${err.message}")
        case Right(root) =>
          val plugins = root.hcursor.downField("plugins").focus.getOrElse(Json.obj())
          val dispatch = plugins.hcursor.downField("dispatch").focus.getOrElse(Json.obj())
          val existing = dispatch.hcursor.downField(name).focus.getOrElse(Json.obj())
          val newDispatch = Json.fromJsonObject(
            dispatch.asObject.getOrElse(JsonObject.empty).add(name, transform(existing))
          )
          val newPlugins = Json.fromJsonObject(
            plugins.asObject.getOrElse(JsonObject.empty).add("dispatch", newDispatch)
          )
          val out = Json.fromJsonObject(
            root.asObject.getOrElse(JsonObject.empty).add("plugins", newPlugins)
          )
          AtomicJson.writeSync(configPath, out.noSpaces)
          Right(())

  /** append-only 审计（设计 R8 推荐 C 的落盘面）：每次**写侧**动作一条，含谁/何时/
  * 何因/是否过渡 —— 「过渡期装载集 vs 作者意图集」可直接从本文件重放。
  * 路径 `<dataRoot>/logs/plugin-dispatch.jsonl`（与宿主日志同目录，append-only、grep 友好）。 */
  private def audit(
    event: String,
    name: String,
    enabled: Option[Boolean],
    by: Option[String],
    extra: Map[String, String]
  ): IO[Unit] =
    IO.blocking {
      val base = List(
        "ts" -> System.currentTimeMillis().toString,
        "event" -> event,
        "name" -> name,
        "enabled" -> enabled.map(_.toString).getOrElse("-"),
        "by" -> by.getOrElse("-")
      )
      val line = (base ++ extra.toList.sortBy(_._1))
        .map { case (k, v) => s""""$k":${Json.fromString(v).noSpaces}""" }
        .mkString("{", ",", "}")
      os.write.append(PathUtil.dataRoot / "logs" / "plugin-dispatch.jsonl", line + "\n", createFolders = true)
    }.void.handleErrorWith(e =>
      IO(logger.warnSync(s"plugin dispatch audit append failed ($name/$event): ${e.getMessage}")))

end PluginDispatchPolicy
