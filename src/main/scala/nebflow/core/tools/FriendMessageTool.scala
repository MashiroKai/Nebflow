package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.PathUtil
import nebflow.dropbox.{AttachContract, DropboxService}
import nebflow.neblink.{FriendRoster, FriendService, FriendSummary, GroupSummary, PeerInfo}

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The former `SendMessage` tool's legs, re-homed as the **single implementation
 * point** for the targets that the unified `Mail` tool absorbed.
 *
 * mailunify-full 批（2026-09-23 作者裁定）：存活名 = `Mail`；`SendMessage`
 * **退役**（并入 `Mail`），`Mail` 参数合并为单 `to`。本对象**不再是工具**
 * （不再 `extends Tool`，`name` / `description` / `inputSchema` / `call` 四个工具面
 * 成员一并消失）——它现在是 `MailTool` 的**腿实现单点**，与
 * [[nebflow.core.tools.MailTool]] 的地址腿并列：
 *
 *   - `friend:` / `group:` → [[deliverToFriend]] / [[deliverToGroup]]
 *     （零重复实现：好友解析走 `FriendRoster.resolve`，发送走
 *     `FriendService.sendAsAgent` / `sendGroupAsAgent` 的既有 choke point）；
 *   - `local` → [[copyLocalTo]]（本机搬运，零网络）；
 *   - `device:` 解析单点 → [[resolveDevice]]（`Mail` 的设备腿与 `FriendMessageTool`
 *     的候选文案**同源**，禁第二份字面量）。
 *
 * 🔴 **随本批退役的腿（能力如实登记，不静默）**：本对象原 `to = device:<…>` 的
 * **纯传输腿**（`sendDevice` = Dropbox 文本 + 文件搬运，对端 agent **不知情**）整体
 * 退役 —— 设备面统一到 `Mail` 的 `device:` 腿（**语义反转**：投进对端助手会话、
 * 对端 agent 直收）。其**文件能力**由 `Mail` 设备腿的既有设备文件通道承载；
 * **丧失**的仅是 `targetDir`（对端目录请求），作者已裁「接受丧失」。
 *
 * to 解析（好友支，2026-09-12 ⑦ 后的完整链）：**L0 备注** → L1 username 精确
 * （大小写不敏感，服务端唯一性口径一致）→ L2 昵称精确 → L3 昵称唯一前缀 →
 * **L4 邮箱 α**（前四级全未命中时走一次上游搜索回落，按命中卡 `userId` 精确回映射
 * 好友表）。多命中/零命中一律返回候选列表让模型自行纠错。解析 L0–L3 与候选文案的
 * **唯一实现点** = `nebflow.neblink.FriendRoster`（批 ⑩ 2026-09-12 收归）——本对象
 * 只委托；L4 是数据面回落（见 `lookupFriendBySearch`）。
 *
 * 设备解析（2026-09-14 自退役的**跨设备文件搬运工具**原样迁入）：deviceId 精确 →
 * deviceName 精确 → deviceId 前缀 → deviceName 前缀 → deviceName 包含，唯一候选
 * 才成功；零命中/多命中一律列可用设备与逐条命中依据（**禁静默首命中**）。
 *
 * 接线：GatewayMain 启动时 FriendMessageTool.initialize(friendService)
 * （RemoteExecutor.initialize 同款单例模式）+ 本对象按次把**会话靶**挂进
 * fiber-local（`SendConfirm.locally`），装配缝实现 `SendConfirm.production`
 * 在本次调用内读它并发确认卡（#147 接线段 2026-09-12）。**确认链只覆盖好友支与
 * 群支** —— 本机支零治理（U-2 裁定：不套好友档位，闸位 = 大小/件数）。
 *
 * 授权（mailunify-full 批后）：本对象**零授权面** —— 授权由 `Mail` 的角色域判据
 * （`to` 的角色闸）单点承担；`friend:` / `group:` / `local` 三条腿对非 root 身份
 * **显式越界报错**（fail-closed，与退役前 `SendMessage` 仅 root 持件的可达面同强）。
 */
object FriendMessageTool:

  private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

  /** 好友/群腿的正文上限（服务端同源契约：4000 字符）。 */
  val MaxMessageLength = 4000

  @volatile private var service: Option[FriendService] = None

  /** Startup wiring (GatewayMain). No-op safe to call once. */
  def initialize(fs: FriendService): Unit = service = Some(fs)

  /** 已接线的好友服务（`None` ⇒ 本机未启用 NebLink 好友面）。 */
  private[tools] def friendService: Option[FriendService] = service

  /** L4 邮箱层（α 方案，⑦-D4/⑦-D11，本批唯一邮箱路径）。
    *
    * `FriendRoster.resolve` 的 L0–L3 全未命中时，把 query 交给**既有**搜索端点
    * （`FriendService.searchUser` → `NeblinkClient.searchUser` → neblink-server
    * `GET /api/users/search`，username OR email 双键 NOCASE 精确）；命中卡自带
    * `userId`（服务端 `SearchUserCard.user_id`，
    * `~/.nebflow/projects/neblink-server/src/model.rs:491-500`）
    * ⇒ 按 `userId` **精确回映射好友表**，映射成功才发送（搜索能命中非好友，而只有
    * 好友可被发消息）。
    *
    * 边界（全部硬口径）：
    *  - **仅失败路径** +1 次上游往返；命中卡不带 email 字段也无需它（只用 userId）。
    *  - **计入同一限流桶**：本调用是 `/api/users/search` 的唯一客户端通路 ⇒ 服务端
    *    `{uid}:search` 20/min 桶（`src/friends.rs:279`）自然会计入（⑦-D11：不新开
    *    桶语义、失败路径不得成为绕过限流的探测面）。
    *  - **上游失败（429/5xx/网络）⇒ `None`**：调用方回落「原样 not-found + 候选」，
    *    **不得**把上游故障升格成「好友不存在」。
    *  - **禁回显**（R3）：本函数不把 query 写进任何错误文案 / 日志（备注与邮箱都是
    *    用户私人数据，且错误文案会进模型上下文并随会话落盘）。空 query 直接短路
    *    （上游对空白输入会消耗一个配额单位，`src/friends.rs:279-283`）。
    */
  private def lookupFriendBySearch(
    fs: FriendService,
    query: String,
    friends: List[FriendSummary]
  ): IO[Option[FriendSummary]] =
    val q = query.trim
    if q.isEmpty then IO.pure(None)
    else
      fs.searchUser(q).map {
        case Left(_) => None
        case Right(json) =>
          val found = json.hcursor.get[Boolean]("found").getOrElse(false)
          if !found then None
          else
            json.hcursor
              .downField("user")
              .get[String]("userId")
              .toOption
              .flatMap(uid => friends.find(_.userId == uid))
      }

  /** Three-level resolution (spec §2 task item 2). Pure — public for tests.
    *
    * 批 ⑩（2026-09-12，方案 `20260912_011320` §4.5 同步点 ③）：实现已收归唯一
    * 单点 `FriendRoster.resolve`（与 `ListFriends` 共用同一份候选文案 —— 成功路径
    * 与失败路径同一套词表）。本方法**只做委托**：对外文案、L1–L3 解析顺序与逐字
    * 输出零变更，参数名校验与 IO 组合全在调用侧不变。
    *
    * 批 ⑦（同日）：`FriendRoster.resolve` 内部前置了 L0 备注层（⑦-D5）。
    */
  def resolveFriend(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary] =
    FriendRoster.resolve(query, friends)

  /** 发送后回执/确认文案里对「打到的是谁」的称呼（⑦-D6 口径，**唯一实现点**：
    * 回执与确认卡都读它，防两处各写一套）。
    *
    * 形态：`备注（username）`——备注缺席退回 displayName；username 缺席（存量
    * 账号未设 NL 号，Decoder 折叠空串）省略括号，不渲染空壳。 */
  private def recipientLabel(friend: FriendSummary): String =
    val label = friend.remark.filter(_.nonEmpty).getOrElse(friend.displayName)
    val idPart = if friend.username.nonEmpty then s"（${friend.username}）" else ""
    s"$label$idPart"

  /** Send after resolution — extracted so the IO composition stays flat
    * (Scala 3: multi-line matches inside nested flatMap braces are fragile).
    *
    * 确认链（#147 接线段，2026-09-12）：本对象是唯一持有 `ToolContext` 的调用侧
    * ⇒ 由它把**会话靶**按次挂进 fiber-local（`SendConfirm.locally`），
    * `sendAsAgent` 侧的装配缝实现（`SendConfirm.production`）在本次调用内读它并
    * 发确认卡。`ask` 档与 auto 超限降级档都经此路；ctx 无交互面（REST 直调 /
    * spec harness）⇒ `production` 读到「无靶」显式 fail-closed（绝不静默直发）。 */
  private def sendTo(
    fs: FriendService,
    friend: FriendSummary,
    message: String,
    ctx: ToolContext,
    attachments: List[os.Path] = Nil
  ): IO[Either[ToolError, String]] =
    nebflow.agent.SendConfirm.locally(
      nebflow.agent.SendConfirm.targetFor(ctx, recipientLabel(friend))
    )(fs.sendAsAgent(friend.userId, message, attachments)).map {
      // 回执形态（⑦-D6）：`备注（username）`——让用户/模型能确认「打到的是谁」。
      // 附件腿（4b A-4）：回执里显式带件数（「成功但附件消失」是本批明令禁止的缺陷形态）。
      case Right(_) =>
        Right(
          if attachments.isEmpty then s"已发送给 ${recipientLabel(friend)}（${LocalTime.now().format(TimeFormat)}）"
          else
            s"已发送给 ${recipientLabel(friend)}（${LocalTime.now().format(TimeFormat)}）— ${attachments.size} 件附件已上传并随消息送达（分块 + 整件 sha256 由服务端校验）。"
        )
      case Left(err) => Left(ToolError(err))
    }

  /** 群目标解析的委托（与 `resolveFriend` **完全同形**）：本对象零实现——唯一实现点
    * `FriendRoster.resolveGroup`，成功路径与失败路径**同一套候选词表**（工具面差异
    * 纪律：能力落在 schema/描述层与单点解析层，本对象不另写一套群匹配）。
    *
    * 群表由调用方注入 ⇒ 本方法是**纯函数**，可直接单测（对齐 `resolveFriend` 的既有测法）。
    */
  def resolveGroupTarget(query: String, groups: List[GroupSummary]): Either[ToolError, GroupSummary] =
    FriendRoster.resolveGroup(query, groups)

  /** 确认卡/回执里对「打到哪个群」的称呼（**唯一实现点**，对齐 `recipientLabel` 的
    * 「回执与确认卡都读它，防两处各写一套」纪律）。
    *
    * 形态 = **群名**（不含群 id）：群名是用户在会话列表里认得出的那个串，而
    * `grp-<uuid>` 人不可读（确认卡是给用户看的）；且寻址歧义在**解析层**就已消解
    * （重名/前缀多命中一律先报候选、不发送）⇒ 回执无需再拿 id 兜歧义。 */
  private def groupLabel(group: GroupSummary): String = group.title

  /** 群支发送（自 `call` 抽出，理由同 `sendTo`：IO 组合保持扁平）。
    *
    * 确认链与好友支**共用同一接线段**：会话靶（本次提问的会话 + 目标名）由本对象按次
    * 挂进 fiber-local（`SendConfirm.locally`），`SendConfirm.production` 在本次调用内
    * 读它并发确认卡；`ask` 档与 auto 超限降级档都经此路，`off` 档在服务层直拒。
    * ctx 无交互面 ⇒ 显式 fail-closed（绝不静默直发）——与好友支逐字同款。
    */
  private def sendToGroup(
    fs: FriendService,
    group: GroupSummary,
    message: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    nebflow.agent.SendConfirm.locally(
      nebflow.agent.SendConfirm.targetFor(ctx, groupLabel(group))
    )(fs.sendGroupAsAgent(group.groupId, message)).map {
      // 回执形态（与好友支同族）：显式带群名，让用户/模型能确认「打到的是哪个群」。
      case Right(_)  => Right(s"已发送到群「${groupLabel(group)}」（${LocalTime.now().format(TimeFormat)}）")
      case Left(err) => Left(ToolError(err))
    }

  // ===== 设备解析面（2026-09-14 自退役的跨设备文件搬运工具原样迁入，语义零变更）=====

  /** 设备候选 + 命中依据（纯函数）：歧义报错逐条列出「区分依据」，**禁静默首命中**。 */
  private[tools] def deviceMatches(query: String, peers: List[PeerInfo]): List[(PeerInfo, String)] =
    val q  = query.trim
    val ql = q.toLowerCase
    peers.flatMap { p =>
      if p.deviceId.equalsIgnoreCase(q) then Some(p -> "exact deviceId")
      else if p.deviceName.equalsIgnoreCase(q) then Some(p -> "exact deviceName")
      else if p.deviceId.toLowerCase.startsWith(ql) then Some(p -> s"deviceId prefix '$q'")
      else if p.deviceName.toLowerCase.startsWith(ql) then Some(p -> s"deviceName prefix '$q'")
      else if p.deviceName.toLowerCase.contains(ql) then Some(p -> s"deviceName contains '$q'")
      else None
    }

  private[tools] def deviceCandidates(peers: List[PeerInfo]): String =
    if peers.isEmpty then "Available devices: none (no NebLink peer discovered)."
    else s"Available devices: ${peers.map(p => s"${p.deviceName} [deviceId ${p.deviceId}]").mkString(", ")}."

  private[tools] def resolveDevice(deviceName: String, peers: List[PeerInfo]): Either[ToolError, PeerInfo] =
    val q = deviceName.trim
    if q.isEmpty then Left(ToolError("device target is empty (prefix present but no name/id)."))
    else
      deviceMatches(q, peers) match
        case Nil =>
          val available = peers.map(_.deviceName)
          Left(
            ToolError(
              if peers.isEmpty then
                "No peer devices discovered. Ensure NebLink Server is configured on both machines and both Nebflow instances are connected."
              else s"Device '$q' not found among ${peers.size} peer(s). Available: ${available.mkString(", ")}"
            )
          )
        case (single, _) :: Nil => Right(single)
        case many =>
          Left(
            ToolError(
              s"Device '$q' is ambiguous (${many.size} matches): " +
                many.map { case (p, why) => s"${p.deviceName} [deviceId ${p.deviceId}] — matched by $why" }.mkString("; ") +
                ". Use the exact deviceId to disambiguate."
            )
          )

  // ===== 本机搬运腿（`to = local`，R3=3b）=====

  /** 本机搬运分支：`attachments` → `targetDir`。零网络、零传输闸；
    * 件数上限与设备腿同源（一条消息 = 一次调用）。
    *
    * mailunify-full 批：本腿**整条保留**（原 `SendMessage` 的 `local` 腿并入 `Mail`
    * 后语义零变更）；它是 `targetDir` / `overwrite` 两个参数**唯一**的适用面。 */
  private[tools] def copyLocalTo(
    attachments: List[String],
    targetDir: Option[String],
    overwrite: Boolean
  ): IO[Either[ToolError, String]] =
    if attachments.isEmpty then
      IO.pure(Left(ToolError("target `local` requires `attachments` (the files to copy into `targetDir`).")))
    else
      AttachContract.checkAttachmentCount(attachments.size) match
        case Left(err) => IO.pure(Left(ToolError(err.render)))
        case Right(_) =>
          targetDir match
            case None =>
              IO.pure(Left(ToolError("target `local` requires `targetDir` (the directory to copy the attachments into).")))
            case Some(rawDir) =>
              val dir = os.Path(PathUtil.expandTilde(rawDir.trim), os.pwd)
              IO.blocking(os.makeDir.all(dir)).attempt.flatMap {
                case Left(e) =>
                  IO.pure(Left(ToolError(s"Cannot create targetDir '$rawDir': ${e.getMessage}")))
                case Right(_) =>
                  // 逐件复制：显式递归（foldLeftM 在此形状下类型推断会塌成
                  // Either[Any,Any]，2026-09-14 编译教训——弃用）。
                  def loop(rest: List[String], copied: List[String]): IO[Either[String, List[String]]] =
                    rest match
                      case Nil => IO.pure(Right(copied))
                      case raw :: tail =>
                        val p      = os.Path(PathUtil.expandTilde(raw.trim), os.pwd)
                        val target = dir / p.last
                        val check: Either[String, Unit] =
                          // 原始串判（os.Path 构造即绝对化，构造后再判恒真）
                          if !java.nio.file.Paths.get(raw.trim).isAbsolute then
                            Left(s"Attachment path must be absolute, got: '$raw'.")
                          else if !os.exists(p) then Left(s"Attachment does not exist: $raw.")
                          else if os.isDir(p) then Left(s"Attachment is a directory, not a file: $raw.")
                          else if os.exists(target) && !overwrite then
                            Left(s"Target already exists (pass overwrite=true to replace): $target")
                          else Right(())
                        check match
                          case Left(err) => IO.pure(Left(err))
                          case Right(_) =>
                            IO.blocking(os.copy(p, target, replaceExisting = overwrite))
                              .attempt
                              .flatMap {
                                case Left(e)  => IO.pure(Left(s"copy failed: ${e.getMessage}"))
                                case Right(_) => loop(tail, copied :+ target.toString)
                              }
                  loop(attachments, Nil).flatMap {
                    case Left(err) => IO.pure(Left(ToolError(s"Local copy failed: $err")))
                    case Right(copied) =>
                      IO.pure(Right(s"已复制 ${copied.size} 件到 $dir（${LocalTime.now().format(TimeFormat)}）— ${copied.mkString(", ")}"))
                  }
              }
  end copyLocalTo

  // ============================================================
  // 腿入口（`Mail` 的唯一委托面）
  // ============================================================

  /** 好友腿（`to = friend:<…>` 或裸好友名）：解析 → 发送。
    *
    * 4b 腿 A-4：附件**不再一律拒绝** —— 裁定①「能发就能带附件」（下载权限跟
    * send 闸）。逐件校验路径形态在本层（与设备支同文案），件数/大小/能力/上传全在
    * `FriendService.sendAsAgent` 单点（`AttachContract` 闸 + A-5 能力探测 +
    * E1/E2 上传链）。🔴 能力探测不可判 ⇒ **明确拒绝**（§G.3 禁未探测即携带
    * `attachments` 发送），禁静默降级为纯文本。 */
  private[tools] def deliverToFriend(
    query: String,
    message: String,
    attachments: List[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    def bad(msg: String): IO[Either[ToolError, String]] = IO.pure(Left(ToolError(msg)))
    val relPaths = attachments.filter(a => !java.nio.file.Paths.get(a.trim).isAbsolute)
    if relPaths.nonEmpty then
      bad(
        "Attachment paths must be absolute, got: " + relPaths.map(a => s"'$a'").mkString(", ") +
          ". Pass ABSOLUTE paths of files on this machine."
      )
    else
      service match
        case None =>
          bad("Friend messaging is unavailable: NebLink friends service is not initialized.")
        case Some(fs) =>
          // §B.4：正文可为空 —— **仅当**有附件时（服务端生成占位正文）。
          // 无附件时空正文仍按旧语义拒绝（逐字节不变）。
          message match
            case m if m.isEmpty && attachments.isEmpty =>
              bad("'message' is empty — nothing to send.")
            case m if m.length > MaxMessageLength =>
              bad(s"Message too long (${m.length} chars, max $MaxMessageLength).")
            case m =>
              // refreshFriends() resolves to a snapshot directly (swallows
              // upstream errors into an empty list — acceptable: resolution
              // then reports "friend list is empty" with no candidates).
              // 该出口已由 FriendService.applyRemarks 注入本地备注 ⇒ L0 层与候选
              // 文案都读得到备注（⑦：工具侧零取数改动）。
              val paths = attachments.map(a => os.Path(PathUtil.expandTilde(a.trim), os.pwd))
              val prepared: IO[(Either[ToolError, FriendSummary], List[FriendSummary])] =
                fs.refreshFriends().map(resp => resolveFriend(query, resp.friends) -> resp.friends)
              prepared.flatMap {
                case (Right(friend), _) => sendTo(fs, friend, m, ctx, paths)
                case (Left(err), friends) =>
                  // L0–L3 全未命中 ⇒ 走 L4 邮箱 α（仅失败路径，+1 次上游往返）。
                  // 命中且能回映射成好友 ⇒ 发送；否则（miss / 上游故障 / 非好友）
                  // 回落**原样**的 not-found + 候选错误（不升格、不回显 query）。
                  lookupFriendBySearch(fs, query, friends).flatMap {
                    case Some(friend) => sendTo(fs, friend, m, ctx, paths)
                    case None         => IO.pure(Left(err))
                  }
              }

  /** 群腿（`to = group:<…>`）：解析 → 发送。一期**纯文本**。
    *
    * 与好友支**同层**：只做「目标解析 + 错误转写」；发送一律走
    * `FriendService.sendGroupAsAgent`（三档权限 / 双层限速 / 确认链，以及
    * `origin="agent"` 的**唯一**群写点全在那边）。带 `attachments` 的群发由调用方
    * （`Mail`）**显式拒绝**，禁静默丢弃 —— 与 `images` 的显式拒绝同族。 */
  private[tools] def deliverToGroup(
    query: String,
    message: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    def bad(msg: String): IO[Either[ToolError, String]] = IO.pure(Left(ToolError(msg)))
    message match
      case m if m.isEmpty =>
        bad("'message' is empty — nothing to send (a group message always carries text).")
      case m if m.length > MaxMessageLength =>
        bad(s"Message too long (${m.length} chars, max $MaxMessageLength).")
      case m =>
        service match
          case None =>
            bad("Group messaging is unavailable: NebLink friends service is not initialized.")
          case Some(fs) =>
            // 群表取数：`Left` **不折叠成空表**（「读不到群」≠「你没有群」）。
            fs.listGroups.flatMap {
              case Left(err)   => bad(s"Cannot resolve the group target — the group list could not be loaded: $err")
              case Right(gs) =>
                resolveGroupTarget(query, gs) match
                  case Left(err) => IO.pure(Left(err))
                  case Right(g)  => sendToGroup(fs, g, m, ctx)
            }

end FriendMessageTool
