package nebflow.core.plugin

import cats.effect.IO
import io.circe.syntax.*
import io.circe.Json
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * PluginRemovePolicy —— 插件**卸载面**（2026-10-29 作者令「除了 toggle，我们应该还要
 * 多一个卸载」）。
 *
 * ==语义三分（勿混）==
 *  - **remove（本面）** = 移除出册：写 `nebflow.json → plugins.removed.<name> =
 *    {at, by, reason, deleteDir}`（独立命名空间，同 `revoked`/`dispatch` 族）＋ 默认
 *    **删目录**（`plugins/<name>` 整树；二次确认给「仅停用」腿区分）；另落插件存在
 *    台账 userRemoved 墓碑（[[nebflow.core.seed.SeedService]] — 调用方编排，见下），
 *    默认集包不被种子自愈装回。出册 = 注册表面整体消失（`PluginRegistry.snapshot`
 *    过滤 plugins ∧ rejected）：不进目录、`resolve` Left（闸 A/B/C/E 拒）、在飞 MCP
 *    由既有 revalidate「包从注册表消失 ⇒ 停」腿 ≤30s 停掉。
 *  - **revoke（[[PluginBlockPolicy]]）** = 封禁：可逆停用（deny-list），目录/注册册
 *    都保留，unblock 即回。面板不提供 revoke 入口（2026-09-14 收敛批既定），提供
 *    remove。
 *  - **reinstatement（复职）** = 卸载后重装：`PluginRegistry.installFrom` 落盘同名包
 *    时清 `plugins.removed.<name>`（显式重装 = 显式复职，零静默残留）；手动把目录放
 *    回（带外）不改写本表——出册态保持，须走重装或手删条目（条目位置在错误文案里
 *    指明，与 tombstone 台账同款口径）。
 *
 * ==为什么独立命名空间（硬约束，勿合并进 `plugins.trust`/`plugins.revoked`）==
 * 与 `PluginBlockPolicy` 头注同构：`trust` 表被任何自动 approve 整对象重写（种子
 * 自愈/镜像刷新），`revoked` 语义是「封禁·可逆」——卸载是「移除·出册」，面板/CLI/
 * REST 要能区分「确实卸载了」与「本来就不在」（零静默纪律：重复卸载 ⇒ Left 提示）。
 *
 * ==编排边界（core.plugin ⇎ core.seed 零环）==
 * 本面**不**触 SeedService（seed → plugin 单向既有；反向边会让两 object 互引）。
 * 台账墓碑（userRemoved）由**调用方**（PresenceRoutes REST 单点——CLI 面板同走
 * REST）在 remove 成功后调 `SeedService.markUserRemoved(name)` 落。
 *
 * ==审计==
 * append-only `logs/plugin-remove.jsonl`（与 plugin-block.jsonl / plugin-dispatch.jsonl
 * 同族）：removed / reinstated 两条事件流，best-effort（审计失败只 WARN）。
 */
object PluginRemovePolicy:

  private val logger = NebflowLogger.forName("nebflow.plugin.remove")

  /** 单包卸载记录（`plugins.removed.<name>`）。 */
  final case class Entry(at: Long, by: String, reason: String, deleteDir: Boolean)

  // ── 读面 ──────────────────────────────────────────────────────────

  /** 读 `plugins.removed` 表（热读；缺失/非法 → 空表 = 无卸载记录）。 */
  def readTable(): Map[String, Json] =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(configPath) then Map.empty
    else
      io.circe.parser
        .parse(os.read(configPath))
        .toOption
        .flatMap(_.hcursor.downField("plugins").downField("removed").as[Map[String, Json]].toOption)
        .getOrElse(Map.empty)

  def entryFor(name: String): Option[Entry] =
    readTable().get(name).flatMap { j =>
      j.asObject.map { _ =>
        Entry(
          at = j.hcursor.downField("at").as[Long].toOption.getOrElse(0L),
          by = j.hcursor.downField("by").as[String].toOption.getOrElse(""),
          reason = j.hcursor.downField("reason").as[String].toOption.getOrElse(""),
          deleteDir = j.hcursor.downField("deleteDir").as[Boolean].toOption.getOrElse(true)
        )
      }
    }

  def isRemoved(name: String): Boolean = entryFor(name).isDefined

  /** 已卸载包名集合（`PluginRegistry.snapshot` 出册过滤用）。 */
  def removedNames(): Set[String] = readTable().keySet

  // ── 写面（卸载 / 复职，各一次手术式改写 + 一条 append-only 审计）──────────

  /**
   * 卸载：写 `plugins.removed.<name>` ＋（deleteDir=true）删 `plugins/<name>` 整树。
   *
   * 前置守卫：包须在盘上（`plugins/<name>` 目录存在）——「卸载一个不在的东西」是
   * 调用方误用，Left 提示不静默成功（幂等性让位于零静默：重复卸载在写表后目录已
   * 不在 ⇒ 次次 Left，调用方面能看到确定态）。
   *
   * 删目录前的 digest 记账进审计（卸载了什么内容、多少文件——回溯面）。
   * 删目录失败：表已写 ⇒ 出册态仍在（fail-safe 方向正确——宁可多藏不可少藏），
   * 错误随 Left 上报，调用方面可见「已出册但删目录失败」。
   */
  def remove(name: String, reason: String, by: String, deleteDir: Boolean): IO[Either[String, String]] =
    val dir = PathUtil.dataRoot / "plugins" / name
    IO.blocking {
      if !os.isDir(dir) then Left(s"Plugin '$name' is not installed ($dir is absent) — nothing to remove")
      else
        val digest = PluginRegistry.computeDigest(dir).toOption.map(_._1).getOrElse("")
        val fileCount =
          try os.walk(dir).count(os.isFile)
          catch case _: Exception => 0
        val now = System.currentTimeMillis()
        mutateRemoved(name)(
          Some(Json.obj(
            "at" -> now.asJson,
            "by" -> by.asJson,
            "reason" -> reason.asJson,
            "deleteDir" -> deleteDir.asJson,
            "digestAtRemove" -> digest.asJson,
            "fileCount" -> fileCount.asJson
          ))
        ).flatMap { _ =>
          val deleted: Either[String, Unit] =
            if !deleteDir then Right(())
            else
              try
                os.remove.all(dir)
                Right(())
              catch
                case e: Exception => Left(s"its directory could not be deleted: ${e.getMessage}")
          deleted.map { _ =>
            s"Plugin '$name' removed from the registry" +
              (if deleteDir then s" and its directory deleted ($fileCount file(s), digest ${digest.take(12)}…)"
               else " (directory KEPT — 'disable-only' leg; the package stays on disk but out of the registry)") +
              ". The seed will not reinstall it. Reinstall with 'stand plugin add' to bring it back."
          }.left.map { err =>
            s"Plugin '$name' is REMOVED from the registry but $err — inspect $dir"
          }
        }
    }.flatMap {
      case Right(msg) =>
        PluginRegistry.invalidateCache()
        audit("removed", name, by, reason, deleteDir) *>
          IO(logger.infoSync(s"Plugin '$name' removed by $by (deleteDir=$deleteDir) — out of the registry")).as(
            Right(msg): Either[String, String]
          )
      case l @ Left(_) => IO.pure(l)
    }

  end remove

  /**
   * 复职：删 `plugins.removed.<name>`（`installFrom` 重装同名包时调用——显式重装 =
   * 显式复职）。无记录 ⇒ no-op（重装一个从未卸载的包是合法常态）。
   */
  def reinstate(name: String): Either[String, Unit] =
    if !isRemoved(name) then Right(())
    else
      val res = mutateRemoved(name)(None)
      res match
        case Right(()) =>
          PluginRegistry.invalidateCache()
          audit("reinstated", name, "install", "", deleteDir = false)
          logger.infoSync(s"Plugin '$name' reinstated — removed-marker cleared by a fresh install")
        case Left(_) => ()
      res

  // ── 内部：nebflow.json 手术式改写（复用 PluginBlockPolicy 的通用改写点，本包
  // 与 block 面同文件族、不同键族，互不覆盖）＋ append-only 审计。──

  private def mutateRemoved(name: String)(entry: Option[Json]): Either[String, Unit] =
    PluginBlockPolicy.mutatePluginsMap("removed", name)(entry)

  /**
   * append-only 审计（`logs/plugin-remove.jsonl`，与 plugin-block.jsonl 同族）：
   * removed / reinstated 各一条。best-effort（审计失败不影响判定，只 WARN）。
   */
  private def audit(event: String, name: String, by: String, reason: String, deleteDir: Boolean): IO[Unit] =
    IO.blocking {
      val fields = List(
        "ts" -> System.currentTimeMillis().toString,
        "event" -> event,
        "name" -> name,
        "by" -> by,
        "deleteDir" -> deleteDir.toString
      ) ++ (if reason.nonEmpty then List("reason" -> reason) else Nil)
      val line = fields
        .map { (k, v) => s""""$k":${Json.fromString(v).noSpaces}""" }
        .mkString("{", ",", "}")
      os.write.append(PathUtil.dataRoot / "logs" / "plugin-remove.jsonl", line + "\n", createFolders = true)
    }.void
      .handleErrorWith(e => IO(logger.warnSync(s"plugin remove audit append failed ($name/$event): ${e.getMessage}")))

end PluginRemovePolicy
