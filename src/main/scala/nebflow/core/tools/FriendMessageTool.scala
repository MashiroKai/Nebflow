package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.PathUtil
import nebflow.dropbox.{AttachContract, DropboxService}
import nebflow.neblink.{FriendRoster, FriendService, FriendSummary, PeerInfo}

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * SendMessage tool — A2A 一期 agent 发消息能力（friends-messaging-arch §7.1
 * 冻结设计 + 20260828 方向补充 spec §2），2026-09-14 附件腿批扩为**统一目标模型**
 * （作者 2026-09-11 方案候选 (a) + 09-13 新数裁定）：
 *
 *   - `to = <好友解析串>` 或 `friend:<…>`：以用户身份发给 NebLink 好友（收方看到的
 *     是好友本人，冻结语义）。**文本 + 附件**（4b 腿 A，2026-09-14）：附件走服务端
 *     存储转发（E1 建会话 → E2 分块上传 → 随消息发 id 列表），下载由对端经**应用内
 *     鉴权路由**取字节（裁定②）。服务端能力**无自报字段**（裁定④）⇒ 上传前做一次
 *     路由存在性探测（A-5 三态）：不支持 / 不可判 ⇒ **明确拒绝**并回执原因，
 *     附件既不上传也不静默丢弃（§5.1-C 静默不达是本条唯一禁形）。
 *   - `to = device:<deviceName|deviceId>`：发给**同账号**的另一台设备（G5：设备面
 *     「文本+附件」消息面既有，本批工具化）。字节走 Dropbox 分块通道（P2P 主腿 +
 *     relay 兜底、每块校验、整件双侧 sha256、断点续传；单件 ≤100,000,000 B 十进制、
 *     单条 ≤9 件，超限 fail-fast 回显实际值）；接收端 auto-accept，落对端 Downloads、
 *     面板可见。**不套**好友限速/权限档（U-2），带附件时落一条审计行
 *     （`RelayExecAudit` 同族）。
 *   - `to = local`：本机搬运显式分支（R3=3b）——把 `attachments` 复制进 `targetDir`
 *     （零网络、零传输闸；件数上限与设备腿同源）。
 *
 * 职责边界（零重复实现）：好友支只做「to 解析 + 参数校验 + 错误转写」，发送一律走
 * FriendService.sendAsAgent（auto/ask/off 三档权限、双层限速与超限自动降级 ask
 * 全部既有，choke point 唯一，spec §2.2）；设备支只做「设备解析 + 闸位转写」，
 * 字节一律走 DropboxService.sendLocalFiles（闸位/分块/校验/续传单点，本工具零
 * 传输实现）。
 *
 * to 解析（好友支，2026-09-12 ⑦ 后的完整链）：**L0 备注** → L1 username 精确
 * （大小写不敏感，服务端唯一性口径一致）→ L2 昵称精确 → L3 昵称唯一前缀 →
 * **L4 邮箱 α**（前四级全未命中时走一次上游搜索回落，按命中卡 `userId` 精确回映射
 * 好友表）。多命中/零命中一律返回候选列表让模型自行纠错。解析 L0–L3 与候选文案的
 * **唯一实现点** = `nebflow.neblink.FriendRoster`（批 ⑩ 2026-09-12 收归）——本工具
 * 只委托；L4 是数据面回落（见 `lookupFriendBySearch`）。
 *
 * 设备解析（2026-09-14 自退役的 TransferFileTool 原样迁入）：deviceId 精确 →
 * deviceName 精确 → deviceId 前缀 → deviceName 前缀 → deviceName 包含，唯一候选
 * 才成功；零命中/多命中一律列可用设备与逐条命中依据（**禁静默首命中**）。
 *
 * 接线：GatewayMain 启动时 FriendMessageTool.initialize(friendService)
 * （RemoteExecutor.initialize 同款单例模式）+ 本工具按次把**会话靶**挂进
 * fiber-local（`SendConfirm.locally`），装配缝实现 `SendConfirm.production`
 * 在本次调用内读它并发确认卡（#147 接线段 2026-09-12）。**确认链只覆盖好友支**
 * ——设备支/本机支零治理（U-2 裁定：不套好友档位，闸位=大小/件数 + 审计行）。
 * 授权（阶段 2d，设计 D.1-11）：机制固定唯一——仅 Nebula 的静态集
 * NebulaOrchestrationTools 携带（2c 起从声明制迁机制固定）；agent.json tools
 * 声明不再授能（buildAllowedToolSet 对 base 一律剥离本工具名，"*" 亦然——the
 * tool name IS the permission boundary）。
 * （本工具扩面后，TransferFile 于 2026-09-14 同批退役——迁移指引见
 * `AgentCore.RetiredToolGuides`。） */
object FriendMessageTool extends Tool:

  private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
  private val MaxMessageLength = 4000

  @volatile private var service: Option[FriendService] = None

  /** Startup wiring (GatewayMain). No-op safe to call once. */
  def initialize(fs: FriendService): Unit = service = Some(fs)

  val name = "SendMessage"

  val description =
    """Send a message on the user's behalf, or move files. Three target kinds are selected by the prefix of `to`:
1. A NebLink friend (bare name, or `friend:<remark|username|email|displayName>`) — delivered as the user over established friend relationships; text and/or files. Files ride the server's attachment channel (create session → chunked upload with per-chunk checksum + whole-file SHA-256 → sent as attachment ids), and the receiver downloads them over an authenticated in-app route. Subject to permission tiers and rate limits, and (depending on configuration) a confirmation card. Before uploading, the client probes whether the server even has the attachment route (no capability self-report exists): unsupported or unverifiable ⇒ the send is refused outright with a readable reason — attachments are never dropped silently.
2. Another of the user's own devices (`device:<deviceName|deviceId>`) — message and/or files over the Dropbox device channel: files are chunked+streamed (per-chunk checksum, whole-file SHA-256 both sides, resume), never enter the LLM context, and land in the peer's Downloads (auto-accept, visible in their device panel). Not subject to the friend permission tiers/rate limits; size/count gated and audited. Requires an active peer roster — an unknown device fails with the available list (no silent fallback).
3. `local` — copy `attachments` into `targetDir` on this machine (no network, no message delivered).

## Parameters
- to (string, required): `device:<deviceName|deviceId>`, `local`, or a friend (bare remark/username/email/displayName, or explicit `friend:<…>`).
- message (string, required): text sent to friend/device targets, max 4000 characters, plain text. Ignored for `local`. May be EMPTY for a friend target **only when** `attachments` is non-empty (the server then generates the placeholder line the receiving client shows).
- attachments (array of string, optional): ABSOLUTE paths of files on this machine. Friend targets: max 9 files per message, each up to 100 MB (100,000,000 bytes, decimal) — the same authored limits as the device leg; the file is uploaded in 4 MiB chunks (per-chunk checksum, whole-file SHA-256) before the message is sent. If the server does not support attachments (or support cannot be verified) the whole send is refused with a readable reason and NOTHING is uploaded. Device targets: same limits, transfer over the device channel. `local`: required — these files are copied into `targetDir`.
- targetDir (string, optional): destination directory for `local` (created if missing). Device targets: optional — a request only, the receiver decides (it accepts only directories on its own allow-list; anything else is rejected with a structured code and nothing is written). Sent only after the peer confirms support; if the peer does not, the request stays off the wire and the files land in the peer's Downloads (the result says so).
- overwrite (boolean, optional, default false): `local` only — replace existing files in `targetDir`.

## Confirmation (ask tier, friend targets only)
When the user's agent-messaging mode is `ask` (or the auto rate limit was hit), the friend send first raises a confirmation card in the chat. The message is sent ONLY after the user approves it on that card; a decline, a cancel, or a timeout (60s) sends nothing and comes back as an error saying so. Device sends and local copies are not gated by this tier. Wait for the tool result — do not assume anything went out."""

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "to" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "`device:<deviceName|deviceId>` for another of the user's devices, `local` for a local copy, or a friend (bare remark/username/email/displayName, or `friend:<…>`).".asJson
      ),
      "message" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> s"Message text (max $MaxMessageLength characters) for friend/device targets; ignored for `local`. Empty is allowed for a friend target only when `attachments` is non-empty.".asJson
      ),
      "attachments" -> Json.obj(
        "type"  -> "array".asJson,
        "items" -> Json.obj("type" -> "string".asJson),
        "description" -> "Absolute local file paths. Friend and device: ≤9 files, each ≤100 MB (100,000,000 bytes, decimal); friend uploads go in 4 MiB chunks with per-chunk checksum + whole-file SHA-256. Friend sends are refused (nothing uploaded) when the server lacks the attachment route. Local: required (copied into targetDir).".asJson
      ),
      "targetDir" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Destination directory for `local` (created if missing). Device targets: a request the receiver decides on (only its own allow-listed directories; otherwise rejected with a structured code, nothing written); if the peer does not confirm support, the files land in its Downloads.".asJson
      ),
      "overwrite" -> Json.obj(
        "type"        -> "boolean".asJson,
        "description" -> "`local` only — replace existing files in targetDir. Default: false.".asJson
      )
    ),
    "required" -> List("to", "message").asJson
  )

  /** Three-level resolution (spec §2 task item 2). Pure — public for tests.
    *
    * 批 ⑩（2026-09-12，方案 `20260912_011320` §4.5 同步点 ③）：实现已收归唯一
    * 单点 `FriendRoster.resolve`（与 `ListFriends` 共用同一份候选文案 —— 成功路径
    * 与失败路径同一套词表）。本方法**只做委托**：对外文案、L1–L3 解析顺序与逐字
    * 输出零变更，参数名校验与 IO 组合全在 `call` 侧不变。
    *
    * 批 ⑦（同日）：`FriendRoster.resolve` 内部前置了 L0 备注层（⑦-D5）。
    */
  def resolveFriend(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary] =
    FriendRoster.resolve(query, friends)

  /** L4 邮箱层（α 方案，⑦-D4/⑦-D11，本批唯一邮箱路径）。
    *
    * `FriendRoster.resolve` 的 L0–L3 全未命中时，把 query 交给**既有**搜索端点
    * （`FriendService.searchUser` → `NeblinkClient.searchUser` → neblink-server
    * `GET /api/users/search`，username OR email 双键 NOCASE 精确）；命中卡自带
    * `userId`（服务端 `SearchUserCard.user_id`，`~/.nebflow/projects/neblink-server/src/model.rs:491-500`）
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
    * 确认链（#147 接线段，2026-09-12）：本工具是唯一持有 `ToolContext` 的调用侧
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
      // 附件腿（4b A-4）：回执里显式带件数，与 `summarize` 的入参摘要同形
      // （「成功但附件消失」是本批明令禁止的缺陷形态）。
      case Right(_) =>
        Right(
          if attachments.isEmpty then s"已发送给 ${recipientLabel(friend)}（${LocalTime.now().format(TimeFormat)}）"
          else
            s"已发送给 ${recipientLabel(friend)}（${LocalTime.now().format(TimeFormat)}）— ${attachments.size} 件附件已上传并随消息送达（分块 + 整件 sha256 由服务端校验）。"
        )
      case Left(err) => Left(ToolError(err))
    }

  /** `to` 的三分类（纯函数，public for tests）：显式前缀分派，不猜、不回落。
    * 裸串 = 好友（既有行为逐字节不变）；`friend:`/`device:` 显式前缀；`local` =
    * 本机搬运分支（R3=3b）。 */
  private[tools] sealed trait ToKind
  private[tools] object ToKind:
    case class Friend(q: String)   extends ToKind
    case class Device(q: String)   extends ToKind
    case object Local              extends ToKind

  private[tools] def parseToKind(raw: String): Either[String, ToKind] =
    val s = raw.trim
    if s.isEmpty then Left("'to' is empty.")
    else if s.equalsIgnoreCase("local") then Right(ToKind.Local)
    else
      val colon = s.indexOf(':')
      if colon > 0 then
        val scheme = s.take(colon).trim.toLowerCase
        val rest   = s.drop(colon + 1).trim
        scheme match
          case "friend" =>
            if rest.isEmpty then Left(s"'$s' is missing the friend after `friend:`.")
            else Right(ToKind.Friend(rest))
          case "device" =>
            if rest.isEmpty then Left(s"'$s' is missing the device name/id after `device:`.")
            else Right(ToKind.Device(rest))
          case _ => Right(ToKind.Friend(s)) // 好友备注/邮箱里可能合法出现冒号 ⇒ 原样按好友解析
      else Right(ToKind.Friend(s))

  // ===== 设备面（2026-09-14 自退役的 TransferFileTool 原样迁入，语义零变更）=====

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

  /** 设备支（文本 + 可选附件）。文本先行；文本不可达 ⇒ fail-fast（附件两腿同源，
    * 不烧超时、不产生半投递）。附件经 [[DropboxService.sendLocalFiles]]（闸位 +
    * 分块 + 校验 + 续传单点）。带附件时落一条审计行（U-2）。 */
  private def sendDevice(
    dbx: DropboxService,
    ns: nebflow.neblink.NeblinkService,
    peer: PeerInfo,
    message: String,
    attachments: List[os.Path],
    targetDir: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    dbx.sendText(peer.deviceId, message).flatMap { textDelivered =>
      if !textDelivered then
        IO.pure(
          Left(
            ToolError(
              s"Device '${peer.deviceName}' could not be reached on any channel (P2P WS and relay Notify both refused the frame) — nothing was sent."
            )
          )
        )
      else if attachments.isEmpty then
        IO.pure(Right(s"已发送到设备 ${peer.deviceName}（${LocalTime.now().format(TimeFormat)}）"))
      else
        // U-2：一条审计行（每次逻辑下发一次，首次网络尝试前；失败绝不影响发送）。
        auditAttachSend(ns, peer, attachments, ctx) *>
          dbx.sendLocalFiles(peer.deviceId, attachments, targetDir).map {
            case Left(err) =>
              Left(ToolError(s"Text was delivered, but the attachments were rejected: ${err.render}"))
            case Right(outcomes) =>
              val total = outcomes.map(_.fileSize).sum
              val failed = outcomes.filterNot(_.delivered)
              val note = targetDirEcho(outcomes)
              if failed.isEmpty then
                Right(
                  s"已发送到设备 ${peer.deviceName}（${LocalTime.now().format(TimeFormat)}）— 文本已送达，${outcomes.size} 件附件共 $total B 全部完成（分块传输，双侧 sha256 一致）。$note"
                )
              else
                val detail = failed.map(o => s"${o.fileName}: ${o.error.getOrElse("unknown error")}").mkString("; ")
                Left(
                  ToolError(
                    s"Text was delivered, but ${failed.size}/${outcomes.size} attachment(s) failed — $detail.$note " +
                      "Retrying reuses the chunked channel's resume (completed chunks are not re-sent)."
                  )
                )
          }
    }

  /** 🔴 §4.1 禁静默：发送端请求了 `targetDir`、但对端**未确认支持**（`file-response`
    * 未回带 `proto >= 2`）⇒ 该请求**未上 wire**，落点 = 对端缺省目录。此结果必须显式
    * 回显给调用方 —— 禁「发了但对方忽略」式的静默不达（`AttachContract` 的四条禁吞口径）。 */
  private def targetDirEcho(outcomes: List[DropboxService.LocalFileOutcome]): String =
    if outcomes.exists(_.targetDirDeferred) then
      " targetDir 请求未上 wire（对端未回带 proto >= 2）—— 对端不支持指定目录，已落对端 Downloads。"
    else ""

  /** U-2 审计行（`RelayExecAudit` 同族字段；零阻塞、失败只 WARN）。 */
  private def auditAttachSend(
    ns: nebflow.neblink.NeblinkService,
    peer: PeerInfo,
    attachments: List[os.Path],
    ctx: ToolContext
  ): IO[Unit] =
    ns.identity
      .flatMap(src =>
        RelayExecAudit.record(
          sourceDeviceId = src.deviceId,
          targetDeviceId = peer.deviceId,
          via = "dropbox-chunk",
          action = "SendMessage.attachments",
          command = s"→ device:${peer.deviceName}; files: ${attachments.map(p => s"${p.last}(${os.stat(p).size} B)").mkString(", ")}",
          projectRoot = ctx.projectRoot,
          cwd = Option(System.getProperty("user.dir")).getOrElse("")
        )
      )
      .handleErrorWith(e => IO.unit) // 审计失败不影响发送（吞异常属 RelayExecAudit 既有语义）

  /** 本机搬运分支（R3=3b）：`attachments` → `targetDir`。零网络、零传输闸；
    * 件数上限与设备腿同源（一条消息 = 一次调用）。 */
  private def copyLocal(
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
  end copyLocal

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val to          = input("to").flatMap(_.asString)
    val message     = input("message").flatMap(_.asString)
    val attachments = input("attachments").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).toList
    val targetDir   = input("targetDir").flatMap(_.asString)
    val overwrite   = input("overwrite").flatMap(_.asBoolean).getOrElse(false)

    def bad(msg: String): IO[Either[ToolError, String]] = IO.pure(Left(ToolError(msg)))

    to match
      case None => bad("Missing required parameter 'to' (friend, `device:<name|id>`, or `local`).")
      case Some(t) =>
        parseToKind(t) match
          case Left(reason) => bad(reason)
          case Right(ToKind.Local) => copyLocal(attachments, targetDir, overwrite)
          case Right(ToKind.Device(q)) =>
            message match
              case None => bad("Missing required parameter 'message'.")
              case Some(m) if m.isEmpty => bad("'message' is empty — nothing to send.")
              case Some(m) if m.length > MaxMessageLength =>
                bad(s"Message too long (${m.length} chars, max $MaxMessageLength).")
              case Some(m) =>
                // 设备腿 `targetDir`（契约升版批，2026-09-14）：**受控支持**。
                // 原先的显式拒绝（「…pending the author's call」）已被作者 16:20「现在升版」取代，
                // 该句自 spec 起标注 provisional/存档，不得再作为待拍板项。本工具只把请求透传到
                // 设备腿（`DropboxService.sendLocalFiles`）；**落点由接收端判定**
                // （`TargetDirGuard`，spec §③）—— 发送端无法指定任意目录。
                if attachments.exists(a => !java.nio.file.Paths.get(a.trim).isAbsolute) then
                  // 原始串闸（os.Path 构造会把相对段绝对化，构造后再判恒真）——
                  // description 契约「ABSOLUTE paths」在工具边界 enforcement。
                  val rel = attachments.filter(a => !java.nio.file.Paths.get(a.trim).isAbsolute)
                  bad(
                    "Attachment paths must be absolute, got: " + rel.map(a => s"'$a'").mkString(", ") +
                      ". Pass ABSOLUTE paths of files on this machine."
                  )
                else
                  val resources = for
                    ns  <- ctx.sharedResources.flatMap(_.neblinkService)
                    dbx <- ctx.sharedResources.flatMap(_.dropboxService)
                  yield (ns, dbx)
                  resources match
                    case None =>
                      bad("Device messaging is unavailable: NebLink/Dropbox services are not initialized (is NebLink enabled?).")
                    case Some((ns, dbx)) =>
                      ns.peers.flatMap(peers =>
                        resolveDevice(q, peers) match
                          case Left(err)      => IO.pure(Left(ToolError(s"${err.message}\n${deviceCandidates(peers)}")))
                          case Right(peer)    => sendDevice(dbx, ns, peer, m, attachments.map(p => os.Path(PathUtil.expandTilde(p.trim), os.pwd)), targetDir, ctx)
                      )
          case Right(ToKind.Friend(q)) =>
            // 4b 腿 A-4：附件**不再一律拒绝** —— 裁定①「能发就能带附件」（下载权限跟
            // send 闸）。逐件校验路径形态（与设备支同文案），件数/大小/能力/上传全在
            // FriendService.sendAsAgent 单点（`AttachContract` 闸 + A-5 能力探测 +
            // E1/E2 上传链）。🔴 能力探测不可判 ⇒ **明确拒绝**（§G.3 禁未探测即携带
            // `attachments` 发送），禁静默降级为纯文本。
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
                    case None => bad(s"Missing required parameter 'message'.")
                    case Some(m) if m.isEmpty && attachments.isEmpty =>
                      bad("'message' is empty — nothing to send.")
                    case Some(m) if m.length > MaxMessageLength =>
                      bad(s"Message too long (${m.length} chars, max $MaxMessageLength).")
                    case Some(m) =>
                      // refreshFriends() resolves to a snapshot directly (swallows
                      // upstream errors into an empty list — acceptable: resolution
                      // then reports "friend list is empty" with no candidates).
                      // 该出口已由 FriendService.applyRemarks 注入本地备注 ⇒ L0 层与候选
                      // 文案都读得到备注（⑦：工具侧零取数改动）。
                      val paths = attachments.map(a => os.Path(PathUtil.expandTilde(a.trim), os.pwd))
                      val prepared: IO[(Either[ToolError, FriendSummary], List[FriendSummary])] =
                        fs.refreshFriends().map(resp => resolveFriend(q, resp.friends) -> resp.friends)
                      prepared.flatMap {
                        case (Right(friend), _) => sendTo(fs, friend, m, ctx, paths)
                        case (Left(err), friends) =>
                          // L0–L3 全未命中 ⇒ 走 L4 邮箱 α（仅失败路径，+1 次上游往返）。
                          // 命中且能回映射成好友 ⇒ 发送；否则（miss / 上游故障 / 非好友）
                          // 回落**原样**的 not-found + 候选错误（不升格、不回显 query）。
                          lookupFriendBySearch(fs, t, friends).flatMap {
                            case Some(friend) => sendTo(fs, friend, m, ctx, paths)
                            case None         => IO.pure(Left(err))
                          }
                      }
    end match
  end call

  def summarize(input: JsonObject): String =
    val to = input("to").flatMap(_.asString).getOrElse("?")
    val n  = input("attachments").flatMap(_.asArray).map(_.size).getOrElse(0)
    if n > 0 then s"SendMessage(to=$to, attachments=$n)" else s"SendMessage(to=$to)"

  def summarizeResult(input: JsonObject, result: String): String = result
end FriendMessageTool
