package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.neblink.{FriendSummary, NeblinkClient, NeblinkService, PeerInfo}
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Transfers a file between NebLink-connected devices without routing file content
 * through the LLM context.
 *
 * Two prefixed target kinds are recognized — `sourceDevice` and `targetDevice` both
 * carry a prefix (工具面收敛批①，2026-09-10；旧四模式已收敛为以下两模式):
 *   - `device:<deviceName|deviceId>` — another NebLink device; the only mode that
 *     actually moves bytes (device → device).
 *   - `friend:<username|displayName>` — a NebLink friend; the prefix is parsed and
 *     the friend resolved against the roster, but the cross-account file channel
 *     does not exist yet ⇒ file transfer to/from a friend fails with an explicit
 *     error (pending batch ②/4b).
 *
 * `local` is NOT supported: this tool performs no local copies any more — local copies
 * go through a project node (Bash `cp`/`rsync`). A missing prefix, `local:`, an unknown
 * prefix, a bare name and a bare absolute path are hard errors carrying (1) the reason
 * + the offending input, (2) the correct usage, (3) the available targets
 * (devices/friends). No default value, no silent fallback, no guessed prefix.
 */
object TransferFileTool extends Tool:
  val name = "TransferFile"

  val description =
    """Transfer a file between two NebLink-connected devices without routing file content through the LLM context.
       Only two prefixes are accepted, and both sourceDevice and targetDevice must carry one: `device:<deviceName|deviceId>` (device → device — the only mode that moves bytes) and `friend:<username|displayName>` (recognized, but the friend file channel does not exist yet, so it is rejected with an explicit error).
       `local` is NOT supported — this tool performs no local copies any more; use a project node (Bash) for local copies.
       A missing prefix, `local:`, a bare device name or a bare absolute path is rejected with an error that lists the available devices and friends."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "sourceDevice" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Required. Source target, prefixed — `device:<deviceName|deviceId>` or `friend:<username|displayName>`. `local` is not supported: local copies go through a project node.".asJson
        ),
        "sourcePath" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Path to the file on the source device, relative to the project root.".asJson
        ),
        "targetDevice" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Required. Target device, prefixed — `device:<deviceName|deviceId>` or `friend:<username|displayName>`. `local` is not supported: local copies go through a project node.".asJson
        ),
        "targetPath" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Path for the file on the target device. Defaults to sourcePath if omitted.".asJson
        ),
        "overwrite" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Overwrite the target file if it already exists. Default: false.".asJson
        )
      ),
      "required" -> Json.arr("sourceDevice".asJson, "sourcePath".asJson, "targetDevice".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    // 批①：无默认值——缺参/非法值原样回显（`local` 兜底已删），便于排错。
    val src = input("sourceDevice").flatMap(_.asString).filter(_.nonEmpty).getOrElse("?")
    val srcPath = input("sourcePath").flatMap(_.asString).getOrElse("?")
    val tgt = input("targetDevice").flatMap(_.asString).filter(_.nonEmpty).getOrElse("?")
    val tgtPath = input("targetPath").flatMap(_.asString).getOrElse(srcPath)
    s"TransferFile($src:$srcPath → $tgt:$tgtPath)"

  def summarizeResult(input: JsonObject, result: String): String =
    val preview = if result.length > 120 then result.take(117) + "..." else result
    preview

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sourceDeviceRaw = input("sourceDevice").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val sourcePath = input("sourcePath").flatMap(_.asString).getOrElse("")
    val targetDeviceRaw = input("targetDevice").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val targetPath = input("targetPath").flatMap(_.asString).filter(_.nonEmpty).getOrElse(sourcePath)
    val overwrite = input("overwrite").flatMap(_.asBoolean).getOrElse(false)

    // 前缀解析（纯）：缺前缀 / `local:` / 裸名 / 裸绝对路径在这里拦下——绝不回落默认设备。
    val sourceParsed: Either[String, TargetRef] = sourceDeviceRaw match
      case None => Left("sourceDevice is required (it must carry a prefix too).")
      case Some(raw) =>
        parseTarget(raw) match
          case Right(ref)   => Right(ref)
          case Left(reason) => Left(s"Invalid sourceDevice: $reason")
    val targetParsed: Either[String, TargetRef] = targetDeviceRaw match
      case None => Left("targetDevice is required (it must carry a prefix too).")
      case Some(raw) =>
        parseTarget(raw) match
          case Right(ref)   => Right(ref)
          case Left(reason) => Left(s"Invalid targetDevice: $reason")

    if sourcePath.isEmpty then IO.pure(Left(ToolError("sourcePath is required")))
    else if targetPath.isEmpty then IO.pure(Left(ToolError("targetPath cannot be empty after resolving defaults")))
    else
      (sourceParsed, targetParsed) match
        case (Right(srcRef), Right(tgtRef)) =>
          runTransfer(srcRef, tgtRef, sourcePath, targetPath, overwrite, ctx)
        case _ =>
          val problems = List(sourceParsed.left.toOption, targetParsed.left.toOption).flatten
          gather(ctx, needFriends = true).map { roster =>
            Left(ToolError(errorBody(problems.mkString("\n"), roster.peers, roster.friends)))
          }
    end if

  end call

  // ===== 目标解析：前缀 + 显式错误（批①） =====

  /** 目标引用：本批只认两个前缀（`local` 已剔除）。 */
  private[tools] sealed trait TargetRef
  private[tools] object TargetRef:
    final case class Device(query: String) extends TargetRef
    final case class Friend(query: String) extends TargetRef

  /** 解析后的引用（设备 → PeerInfo；好友 → FriendSummary）。 */
  private[tools] sealed trait ResolvedRef
  private[tools] object ResolvedRef:
    final case class Device(peer: PeerInfo) extends ResolvedRef
    final case class Friend(friend: FriendSummary) extends ResolvedRef

  /** 搬运计划：本批只有一种可执行模式（设备 → 设备）。 */
  private[tools] sealed trait TransferPlan
  private[tools] object TransferPlan:
    final case class DeviceToDevice(source: PeerInfo, target: PeerInfo) extends TransferPlan

  /** NebLink 服务 + 候选名册（错误文案三要素之③「可用目标候选」的来源）。 */
  private[tools] case class TargetRoster(
    ns: Option[NeblinkService],
    peers: List[PeerInfo],
    friends: List[FriendSummary]
  )

  /** 正确用法（三要素之②）——与被删掉的 `local` 默认值同时出现，报文里必带。 */
  private[tools] val UsageHint: String =
    "Correct usage — `sourceDevice` and `targetDevice` must each be prefixed: " +
      "`device:<deviceName|deviceId>` for another NebLink device, `friend:<username|displayName>` for a NebLink friend. " +
      "`local` is not supported: local copies go through a project node (Bash)."

  /** 前缀解析（纯函数，public for tests）。只认 `device:` / `friend:`；其余一律显式
    * 报错——缺前缀、`local:`、未知前缀、裸名、裸绝对路径（含 `~`/`./` 与 Windows
    * 盘符路径）。不猜前缀、不做任何默认目标回落。 */
  private[tools] def parseTarget(raw: String): Either[String, TargetRef] =
    val s = raw.trim
    val colon = s.indexOf(':')
    if s.isEmpty then Left("value is empty (a `device:` or `friend:` prefix is required).")
    else if colon < 0 then
      if looksLikeLocalPath(s) then
        Left(
          s"'$s' looks like a local filesystem path — this tool no longer copies files on the calling machine (local copies go through a project node)."
        )
      else Left(s"'$s' has no prefix — every target must be prefixed with `device:` or `friend:`.")
    else
      val scheme = s.take(colon).trim.toLowerCase
      val rest = s.drop(colon + 1).trim
      scheme match
        case "device" =>
          if rest.isEmpty then Left(s"'$s' is missing the device name/id after `device:`.")
          else Right(TargetRef.Device(rest))
        case "friend" =>
          if rest.isEmpty then Left(s"'$s' is missing the username/displayName after `friend:`.")
          else Right(TargetRef.Friend(rest))
        case "local" =>
          Left(
            s"'$s' uses the removed `local` prefix — this tool never copies files on the calling machine any more (local copies go through a project node)."
          )
        case other =>
          val looksLikeDrive =
            other.length == 1 && s.length > colon + 1 && (s.charAt(colon + 1) == '\\' || s.charAt(colon + 1) == '/')
          if looksLikeDrive then
            Left(
              s"'$s' looks like a Windows drive path (a local path) — this tool no longer copies files on the calling machine (local copies go through a project node)."
            )
          else Left(s"'$s' uses the unsupported prefix `$other:` — only `device:` and `friend:` are accepted.")

  /** 裸绝对路径 / `~` / 相对点路径判定（用于给出比「无前缀」更准确的原因）。 */
  private[tools] def looksLikeLocalPath(s: String): Boolean =
    s.startsWith("/") || s.startsWith("~") || s.startsWith("./") || s.startsWith("../")

  /** 引用回显（报错时让模型看见自己传了什么）。 */
  private[tools] def refText(ref: TargetRef): String = ref match
    case TargetRef.Device(q) => s"device:$q"
    case TargetRef.Friend(q) => s"friend:$q"

  /** 三要素报文组装：① 原因（含回显）② 正确用法 ③ 可用目标候选。 */
  private[tools] def errorBody(message: String, peers: List[PeerInfo], friends: List[FriendSummary]): String =
    s"$message\n$UsageHint\n${deviceCandidates(peers)}\n${friendCandidates(friends)}"

  private[tools] def deviceCandidates(peers: List[PeerInfo]): String =
    if peers.isEmpty then "Available devices: none (no NebLink peer discovered)."
    else s"Available devices: ${peers.map(p => s"${p.deviceName} [deviceId ${p.deviceId}]").mkString(", ")}."

  private[tools] def friendCandidates(friends: List[FriendSummary]): String =
    if friends.isEmpty then "Available friends: none (friend list empty or the NebLink friends service is unavailable)."
    else s"Available friends: ${friends.map(f => s"${f.displayName} [username ${f.username}]").mkString(", ")}."

  /** 好友解析（纯函数，public for tests）：沿用好友消息支的三级口径——username
    * 精确 → displayName 精确 → displayName 唯一前缀；多命中/零命中一律带候选列表。
    *
    * 刻意**内联**而非复用 `FriendMessageTool.resolveFriend`：并行改名支（A1）会改该
    * 工具的符号名，跨支引用会在合并时炸；批① 的约束是「各自内联、不抽公共 helper」。 */
  private[tools] def resolveFriend(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary] =
    val q = query.trim
    val hint = friendCandidates(friends)
    if q.isEmpty then Left(ToolError(s"friend target is empty (prefix present but no username/displayName). $hint"))
    else
      val byUsername = friends.filter(_.username.equalsIgnoreCase(q))
      byUsername match
        case single :: Nil => Right(single)
        case _ =>
          val byName = friends.filter(_.displayName.equalsIgnoreCase(q))
          byName match
            case single :: Nil => Right(single)
            case many =>
              val byPrefix = friends.filter(_.displayName.toLowerCase.startsWith(q.toLowerCase))
              (many ++ byPrefix).distinct match
                case single :: Nil => Right(single)
                case hits =>
                  Left(
                    ToolError(
                      if hits.isEmpty then s"Friend '$q' not found. $hint"
                      else
                        s"Friend '$q' is ambiguous (${hits.size} matches). Candidates: ${hits.map(f => s"${f.displayName} (${f.username})").mkString(", ")} — use the exact username."
                    )
                  )

  /** 路由规划（纯函数，public for tests）：两个解析后的引用 → 搬运计划。
    *
    * 任一侧是 `friend:` ⇒ **显式 unsupported**（好友文件面今天无通路——`SendMessageBody`
    * 无附件字段、服务端 `attachment|file_id|media_id|mime` 全仓零命中 ⇒ 字节通路不
    * 存在，属跨项目批 4b）：不静默降级、不悄悄落到设备面。 */
  private[tools] def planTransfer(
    sourceRef: TargetRef,
    targetRef: TargetRef,
    peers: List[PeerInfo],
    friends: List[FriendSummary]
  ): Either[ToolError, TransferPlan] =
    for
      src <- resolveRef("sourceDevice", sourceRef, peers, friends)
      tgt <- resolveRef("targetDevice", targetRef, peers, friends)
      plan <- (src, tgt) match
        case (ResolvedRef.Device(sp), ResolvedRef.Device(tp)) => Right(TransferPlan.DeviceToDevice(sp, tp))
        case _ =>
          Left(
            ToolError(
              errorBody(
                s"File transfer to/from a NebLink friend is not supported: '${refText(sourceRef)}' → '${refText(targetRef)}'. " +
                  "`friend:` targets carry messages only — the cross-account file channel does not exist yet (pending batch ②/4b). " +
                  "Use `device:<deviceName|deviceId>` for device-to-device transfers.",
                peers,
                friends
              )
            )
          )
    yield plan

  /** 单个引用 → 已解析目标；失败一律补全三要素（此处即「未知目标」类报错）。 */
  private def resolveRef(
    param: String,
    ref: TargetRef,
    peers: List[PeerInfo],
    friends: List[FriendSummary]
  ): Either[ToolError, ResolvedRef] =
    ref match
      case TargetRef.Device(q) =>
        resolveDevice(q, peers) match
          case Right(peer) => Right(ResolvedRef.Device(peer))
          case Left(err)   => Left(ToolError(errorBody(s"Invalid $param 'device:$q': ${err.message}", peers, friends)))
      case TargetRef.Friend(q) =>
        resolveFriend(q, friends) match
          case Right(friend) => Right(ResolvedRef.Friend(friend))
          case Left(err)     => Left(ToolError(errorBody(s"Invalid $param 'friend:$q': ${err.message}", peers, friends)))

  private def isFriend(ref: TargetRef): Boolean = ref match
    case TargetRef.Friend(_) => true
    case _                   => false

  /** NebLink 服务 + 候选名册。好友名册只在目标含 `friend:`（需解析）或要组装报错候选
    * 时才拉取——设备 → 设备路径不新增任何外部依赖。 */
  private def gather(ctx: ToolContext, needFriends: Boolean): IO[TargetRoster] =
    ctx.sharedResources.flatMap(_.neblinkService) match
      case None => IO.pure(TargetRoster(None, Nil, Nil))
      case Some(ns) =>
        for
          peers <- ns.peers
          friends <- if needFriends then friendRoster(ctx) else IO.pure(Nil)
        yield TargetRoster(Some(ns), peers, friends)

  /** 好友名册（best-effort）：好友服务缺席或上游失败 → 空表（候选文案如实写「none」）。 */
  private def friendRoster(ctx: ToolContext): IO[List[FriendSummary]] =
    ctx.sharedResources.flatMap(_.friendService) match
      case None     => IO.pure(Nil)
      case Some(fs) => fs.refreshFriends().map(_.friends).handleErrorWith(_ => IO.pure(Nil))

  private def runTransfer(
    srcRef: TargetRef,
    tgtRef: TargetRef,
    sourcePath: String,
    targetPath: String,
    overwrite: Boolean,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    gather(ctx, needFriends = isFriend(srcRef) || isFriend(tgtRef)).flatMap { roster =>
      roster.ns match
        case None =>
          IO.pure(
            Left(ToolError("NebLink not available — start nebflow on both devices and ensure they are connected"))
          )
        case Some(ns) =>
          planTransfer(srcRef, tgtRef, roster.peers, roster.friends) match
            case Left(err) => IO.pure(Left(err))
            case Right(TransferPlan.DeviceToDevice(srcPeer, tgtPeer)) =>
              transferRemoteToRemote(sourcePath, targetPath, overwrite, srcPeer, tgtPeer, ns)
    }

  // ===== Transfer modes =====

  private def transferLocalToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    p2pTransferLocalToRemote(srcPath, tgtPath, overwrite, peer, ns).flatMap {
      case r @ Right(_) => IO.pure(r)
      case Left(p2pErr) =>
        ns.relayClientOpt match
          case Some(client) =>
            relayTransferLocalToRemote(srcPath, tgtPath, overwrite, peer, client)
          case None => IO.pure(Left(p2pErr))
    }

  private def p2pTransferLocalToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val src = os.pwd / os.RelPath(srcPath)
      if !os.exists(src) then Left(ToolError(s"Source file not found on local device: $srcPath"))
      else if !os.isFile(src) then Left(ToolError(s"Not a file: $srcPath"))
      else
        val content = os.read.bytes(src)
        val b64 = java.util.Base64.getEncoder.encodeToString(content)
        val size = content.length

        val body = Json.obj(
          "path" -> tgtPath.asJson,
          "content" -> b64.asJson,
          "overwrite" -> overwrite.asJson
        )

        val resp = basicRequest
          .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/transfer"))
          .contentType("application/json")
          .body(body.noSpaces)
          .readTimeout(60.seconds)
          .response(asStringAlways)
          .send(ns.httpBackend)

        if !resp.code.isSuccess then
          Left(ToolError(s"Remote device returned HTTP ${resp.code}: ${resp.body.take(200)}"))
        else
          io.circe.parser.parse(resp.body) match
            case Right(json) =>
              val ok = json.hcursor.downField("ok").as[Boolean].getOrElse(false)
              if ok then
                val rmtSize = json.hcursor.downField("size").as[Long].getOrElse(size.toLong)
                Right(s"Transferred $srcPath ($rmtSize bytes) → ${peer.deviceName}:$tgtPath")
              else
                val err = json.hcursor.downField("error").as[String].getOrElse("unknown error")
                Left(ToolError(s"Remote transfer failed: $err"))
            case Left(err) =>
              Left(ToolError(s"Invalid response from remote: ${err.getMessage}"))
      end if
    }.handleErrorWith { e =>
      IO.pure(Left(ToolError(s"Cannot transfer to ${peer.deviceName}: ${e.getMessage}")))
    }

  private def transferRemoteToLocal(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    p2pTransferRemoteToLocal(srcPath, tgtPath, overwrite, peer, ns).flatMap {
      case r @ Right(_) => IO.pure(r)
      case Left(p2pErr) =>
        ns.relayClientOpt match
          case Some(client) =>
            relayTransferRemoteToLocal(srcPath, tgtPath, overwrite, peer, client)
          case None => IO.pure(Left(p2pErr))
    }

  private def p2pTransferRemoteToLocal(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val encodedPath = java.net.URLEncoder.encode(srcPath, "UTF-8")
      val resp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/transfer?path=$encodedPath"))
        .readTimeout(60.seconds)
        .response(asStringAlways)
        .send(ns.httpBackend)

      if resp.code.isSuccess then
        io.circe.parser.parse(resp.body) match
          case Right(json) =>
            val contentB64 = json.hcursor.downField("content").as[String].getOrElse("")
            val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
            if contentB64.isEmpty then Left(ToolError(s"Empty response from ${peer.deviceName} for $srcPath"))
            else
              val content = java.util.Base64.getDecoder.decode(contentB64)
              val tgt = os.pwd / os.RelPath(tgtPath)
              if !overwrite && os.exists(tgt) then
                Left(ToolError(s"Target file already exists: $tgtPath (use overwrite=true to replace)"))
              else
                os.write(tgt, content, createFolders = true)
                Right(s"Transferred ${peer.deviceName}:$srcPath ($size bytes) → $tgtPath")
          case Left(err) =>
            Left(ToolError(s"Invalid response from ${peer.deviceName}: ${err.getMessage}"))
      else if resp.code.code == 404 then Left(ToolError(s"File not found on ${peer.deviceName}: $srcPath"))
      else Left(ToolError(s"Remote device returned HTTP ${resp.code}: ${resp.body.take(200)}"))
      end if
    }.handleErrorWith { e =>
      IO.pure(Left(ToolError(s"Cannot fetch from ${peer.deviceName}: ${e.getMessage}")))
    }

  private def transferRemoteToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    srcPeer: PeerInfo,
    tgtPeer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    p2pTransferRemoteToRemote(srcPath, tgtPath, overwrite, srcPeer, tgtPeer, ns).flatMap {
      case r @ Right(_) => IO.pure(r)
      case Left(p2pErr) =>
        ns.relayClientOpt match
          case Some(client) =>
            relayTransferRemoteToRemote(srcPath, tgtPath, overwrite, srcPeer, tgtPeer, client)
          case None => IO.pure(Left(p2pErr))
    }

  private def p2pTransferRemoteToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    srcPeer: PeerInfo,
    tgtPeer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val encodedSrc = java.net.URLEncoder.encode(srcPath, "UTF-8")
      // 1. Pull from source
      val getResp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${srcPeer.address}/api/neblink/transfer?path=$encodedSrc"))
        .readTimeout(60.seconds)
        .response(asStringAlways)
        .send(ns.httpBackend)

      if !getResp.code.isSuccess then
        Left(ToolError(s"Source device returned HTTP ${getResp.code}: ${getResp.body.take(200)}"))
      else
        val json = io.circe.parser.parse(getResp.body).toOption.getOrElse(Json.Null)
        val contentB64 = json.hcursor.downField("content").as[String].getOrElse("")
        if contentB64.isEmpty then Left(ToolError(s"Empty response from source ${srcPeer.deviceName}"))
        else
          // 2. Push to target
          val pushBody = Json.obj(
            "path" -> tgtPath.asJson,
            "content" -> contentB64.asJson,
            "overwrite" -> overwrite.asJson
          )

          val pushResp = basicRequest
            .post(sttp.model.Uri.unsafeParse(s"${tgtPeer.address}/api/neblink/transfer"))
            .contentType("application/json")
            .body(pushBody.noSpaces)
            .readTimeout(60.seconds)
            .response(asStringAlways)
            .send(ns.httpBackend)

          if !pushResp.code.isSuccess then
            Left(ToolError(s"Target device returned HTTP ${pushResp.code}: ${pushResp.body.take(200)}"))
          else
            val pushJson = io.circe.parser.parse(pushResp.body).toOption.getOrElse(Json.Null)
            val ok = pushJson.hcursor.downField("ok").as[Boolean].getOrElse(false)
            if ok then
              val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
              Right(s"Transferred ${srcPeer.deviceName}:$srcPath ($size bytes) → ${tgtPeer.deviceName}:$tgtPath")
            else
              val err = pushJson.hcursor.downField("error").as[String].getOrElse("unknown error")
              Left(ToolError(s"Target transfer failed: $err"))
        end if
      end if
    }.handleErrorWith { e =>
      IO.pure(Left(ToolError(s"Remote-to-remote transfer failed: ${e.getMessage}")))
    }

  // ===== Relay fallback methods =====

  private def relayTransferLocalToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    client: NeblinkClient
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val src = os.pwd / os.RelPath(srcPath)
      if !os.exists(src) then Left(ToolError(s"Source file not found: $srcPath"))
      else
        val content = os.read.bytes(src)
        val b64 = java.util.Base64.getEncoder.encodeToString(content)
        Right((b64, content.length))
    }.flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right((b64, size)) =>
        client.relayTransferPut(peer.deviceId, tgtPath, b64, overwrite).map {
          case Right(remoteSize) =>
            Right(s"Transferred $srcPath ($remoteSize bytes) → ${peer.deviceName}:$tgtPath (via relay)")
          case Left(err) =>
            Left(ToolError(s"P2P and relay both failed. Relay: $err"))
        }
    }

  private def relayTransferRemoteToLocal(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    client: NeblinkClient
  ): IO[Either[ToolError, String]] =
    client.relayTransferGet(peer.deviceId, srcPath).flatMap {
      case Right((contentB64, size)) =>
        IO.blocking {
          val content = java.util.Base64.getDecoder.decode(contentB64)
          val tgt = os.pwd / os.RelPath(tgtPath)
          if !overwrite && os.exists(tgt) then
            Left(ToolError(s"Target file already exists: $tgtPath (use overwrite=true to replace)"))
          else
            os.write(tgt, content, createFolders = true)
            Right(s"Transferred ${peer.deviceName}:$srcPath ($size bytes) → $tgtPath (via relay)")
        }.handleErrorWith(e => IO.pure(Left(ToolError(s"Relay write failed: ${e.getMessage}"))))
      case Left(err) => IO.pure(Left(ToolError(s"P2P and relay both failed. Relay: $err")))
    }

  private def relayTransferRemoteToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    srcPeer: PeerInfo,
    tgtPeer: PeerInfo,
    client: NeblinkClient
  ): IO[Either[ToolError, String]] =
    client.relayTransferGet(srcPeer.deviceId, srcPath).flatMap {
      case Right((contentB64, size)) =>
        client.relayTransferPut(tgtPeer.deviceId, tgtPath, contentB64, overwrite).map {
          case Right(remoteSize) =>
            Right(
              s"Transferred ${srcPeer.deviceName}:$srcPath ($size bytes) → ${tgtPeer.deviceName}:$tgtPath (via relay)"
            )
          case Left(err) =>
            Left(ToolError(s"Relay put to target failed: $err"))
        }
      case Left(err) => IO.pure(Left(ToolError(s"Relay get from source failed: $err")))
    }

  // ===== Helpers =====

  /** 设备解析（批① B8：模糊首命中 → 歧义候选列表）。
    *
    * 匹配分档（大小写不敏感）：deviceId 精确 → deviceName 精确 → deviceId 前缀 →
    * deviceName 前缀 → deviceName 包含。**唯一候选**才解析成功；≥2 候选一律报错并
    * 逐条列出候选与命中依据（静默首命中正是本批要收紧的既有风险）；零候选保持既有
    * 「not found + Available」文案。 */
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
                s"No peer devices discovered. Ensure NebLink Server is configured on both machines and both Nebflow instances are connected."
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

  /** 候选 + 命中依据（纯函数，public for tests）——歧义报错逐条列出「区分依据」。 */
  private[tools] def deviceMatches(query: String, peers: List[PeerInfo]): List[(PeerInfo, String)] =
    val q = query.trim
    val ql = q.toLowerCase
    peers.flatMap { p =>
      if p.deviceId.equalsIgnoreCase(q) then Some(p -> "exact deviceId")
      else if p.deviceName.equalsIgnoreCase(q) then Some(p -> "exact deviceName")
      else if p.deviceId.toLowerCase.startsWith(ql) then Some(p -> s"deviceId prefix '$q'")
      else if p.deviceName.toLowerCase.startsWith(ql) then Some(p -> s"deviceName prefix '$q'")
      else if p.deviceName.toLowerCase.contains(ql) then Some(p -> s"deviceName contains '$q'")
      else None
    }

end TransferFileTool
