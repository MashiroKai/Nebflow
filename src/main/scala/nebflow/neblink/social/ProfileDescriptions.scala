package nebflow.neblink.social

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.shared.PathUtil

/**
 * 「双描述」本地持久化面（件一，作者令 2026-11-02：好友/群要有微信式 Profile，两个
 * 描述 = 我自己写的 + 我给对方写的备注）。
 *
 * 定案契约（`reports/20261102_profile-settings-recon.md` §6.1，本文件是其落地点）：
 *
 * | 主体 | 字段             | 真源        | 本仓职责         | 落盘落点（本文件）  |
 * |------|------------------|-------------|------------------|---------------------|
 * | 好友 | `bio`            | **上游**    | 本地缓存 + 回显  | `friendBio` 映射    |
 * | 好友 | `remark`         | 本仓本地    | 唯一真源         | **不在本文件**（既有 `FriendRemarkStore`，`friend-remarks.json`，零改动） |
 * | 群   | `description`    | **上游**    | 本地缓存 + 回显  | `groupDescription` 映射 |
 * | 群   | `remark`         | 本仓本地    | 唯一真源         | `groupRemark` 映射  |
 * | 自己 | `bio`            | 本仓本地    | 唯一真源         | `selfBio` 字符串    |
 * | 好友 | `muted`          | 本仓本地    | 唯一真源         | `friendMuted` 映射（三点入口批 2026-11-05） |
 * | 好友 | `pinned`         | 本仓本地    | 唯一真源         | `friendPinned` 映射（同上） |
 *
 * 🔴 同步口径（**已知缺口，非缺陷——同族先例 S1 定案**）：四条字段**全部零跨设备同步、
 * 零上游协议变更**（先例逐字：`FriendRemarkStore` 头注「零上游协议变更、零跨设备同步」、
 * `NeblinkModel` 设备描述「descriptions are purely local, never exchanged between
 * devices」）。其中 `friendBio` / `groupDescription` 的**语义真源在上游**，但上游
 * `neblink-server` 当前**没有**这两个字段（`model.rs` 的 `FriendPublic` /
 * `GroupSummary` 均无）⇒ 本仓这份只是**本地缓存**：UI 必须显式标「本地缓存·未同步」，
 * 禁把本地写读当成「已同步到上游」（详见本批交付报告的同步卡点节）。
 *
 * 写者纪律：与 `FriendRemarkStore` / `PeerDescriptionStore` **同族单写者** —— 只有网关
 * 进程内的 `FriendService` 改它，`os.write.over` 整文件覆盖（无 append、无并发写者）。
 * 键 = `userId` / `groupId`（稳定 id，不用可变的 username / 群名）。
 */
case class ProfileDescriptions(
  friendBio: Map[String, String] = Map.empty,
  groupRemark: Map[String, String] = Map.empty,
  groupDescription: Map[String, String] = Map.empty,
  selfBio: String = "",
  /** 消息免打扰（三点入口批 2026-11-05）。键 = userId，值恒 `true`（关闭 = 删键）。 */
  friendMuted: Map[String, Boolean] = Map.empty,
  /** 顶置（同上）。键 = userId，值恒 `true`（取消顶置 = 删键）。 */
  friendPinned: Map[String, Boolean] = Map.empty
)

object ProfileDescriptions:

  val empty: ProfileDescriptions = ProfileDescriptions()

  given Encoder[ProfileDescriptions] = Encoder.instance { p =>
    Json.obj(
      "friendBio" -> p.friendBio.asJson,
      "groupRemark" -> p.groupRemark.asJson,
      "groupDescription" -> p.groupDescription.asJson,
      "selfBio" -> p.selfBio.asJson,
      "friendMuted" -> p.friendMuted.asJson,
      "friendPinned" -> p.friendPinned.asJson
    )
  }

  /**
   * 手写 Decoder（**逐键折叠缺省**，理由与 `DeviceIdentity.Decoder` 同源）：
   * 手写/降级版的文件可能只带其中一段（设置路径独立），deriveDecoder 会把缺键当成
   * 必填 ⇒ 整份解密失败 ⇒ 四条描述全部丢失。这里每段独立 `getOrElse` 缺省。
   */
  given Decoder[ProfileDescriptions] = Decoder.instance { c =>
    for
      bio <- c.downField("friendBio").as[Option[Map[String, String]]].map(_.getOrElse(Map.empty))
      gr <- c.downField("groupRemark").as[Option[Map[String, String]]].map(_.getOrElse(Map.empty))
      gd <- c.downField("groupDescription").as[Option[Map[String, String]]].map(_.getOrElse(Map.empty))
      self <- c.downField("selfBio").as[Option[String]].map(_.getOrElse(""))
      // 加性两段（2026-11-05）：旧文件无这两键 ⇒ 空映射（**不得**整份失败，否则
      // 升级后用户已有的备注/描述会被静默清空）。
      muted <- c.downField("friendMuted").as[Option[Map[String, Boolean]]].map(_.getOrElse(Map.empty))
      pinned <- c.downField("friendPinned").as[Option[Map[String, Boolean]]].map(_.getOrElse(Map.empty))
    yield ProfileDescriptions(bio, gr, gd, self, muted, pinned)
  }

end ProfileDescriptions

/**
 * 「双描述」存储：`<dataRoot>/profile-descriptions.json`（`ProfileDescriptions` 一份）。
 *
 * `def path` 而非 `val`（f1cd3709 规则）：`PathUtil.dataRoot` 可被 `setDataRoot` 重定向，
 * `val` 会在 object-init 冻结路径 ⇒ per-test data root 失效、写进真实 `~/.stand`。
 * 同族先例逐字：`FriendRemarkStore` / `PeerDescriptionStore`（均 `def path`）。
 */
object ProfileDescriptionStore:

  private def path = PathUtil.dataRoot / "profile-descriptions.json"

  def load: IO[ProfileDescriptions] =
    IO.blocking {
      if os.exists(path) then decode[ProfileDescriptions](os.read(path)).getOrElse(ProfileDescriptions.empty)
      else ProfileDescriptions.empty
    }

  def save(descriptions: ProfileDescriptions): IO[Unit] =
    IO.blocking {
      os.write.over(path, descriptions.asJson.spaces2, createFolders = true)
    }

end ProfileDescriptionStore
