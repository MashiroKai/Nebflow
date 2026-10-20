/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorSystem as RootActorSystem, folderId}
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}
import nebflow.core.mcp.McpManager
import nebflow.core.project.{CancelSource as ChainCancelSource, *}
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.{NfCacheInvalidator, ToolContext, ToolRegistry}
import nebflow.gateway.NfFilePolicy.*
import nebflow.gateway.WsDispatch.{inboundEnvelope, parsedJson}
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*
import scala.io.Source

/** 文件操作域(文件操作):文件树、watch、读写删移、文本流(textWindow/Index/Search)。 */
private[gateway] object WsFileOpsHandlers:

  private[gateway] val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "listDir" -> handleListDir,
    "watchSubscribe" -> handleWatchSubscribe,
    "watchUnsubscribe" -> handleWatchUnsubscribe,
    "readFile" -> handleReadFile,
    "pop.readFile" -> handlePopReadFile,
    "fileTransferCancel" -> handleFileTransferCancel,
    "textWindow" -> handleTextWindow,
    "textIndex" -> handleTextIndex,
    "textSearch" -> handleTextSearch,
    "textCancel" -> handleTextCancel,
    "createFile" -> handleCreateFile,
    "createDir" -> handleCreateDir,
    "deletePath" -> handleDeletePath,
    "movePath" -> handleMovePath,
    "deletePaths" -> handleDeletePaths,
    "writeFile" -> handleWriteFile,
    "browsePath" -> handleBrowsePath
  )

  private def handleListDir(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Explorer (File Tree) =====

    val json = parsedJson(text)
    val hc = json.hcursor
    val exSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val subPath = hc.downField("path").as[String].getOrElse("")
    // v2.1 阶段 1 §C.1-3（2026-10-09 实施批）：可选 `device` 键——缺省/缺键 =
    // 本地腿逐字节（存量零回归）；对端设备 = scope=browse 元数据列举（**零字节
    // 流动**，出口白名单在对端闸），帧形与本地腿同构 + additive `device` 标注。
    val exDevice = hc.downField("device").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    if exSessionId.nonEmpty && exDevice.isDefined then listRemoteDir(ctx, exDevice.get, subPath, wsSend)
    else if exSessionId.nonEmpty then
      val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
      (for
        pr <- resolveExplorerBaseRoot(exSessionId, overrideRoot)
        basePath = if subPath.isEmpty then os.Path(pr) else PathUtil.resolvePath(subPath, os.Path(pr))
        canonicalBase = basePath.toIO.getCanonicalPath
        canonicalRoot = os.Path(pr).toIO.getCanonicalPath
        _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
          new RuntimeException("path outside project root")
        )
        entries <- IO.blocking(FsOps.listDirEntries(basePath))
      yield (basePath, entries))
        .flatMap { case (basePath, entries) =>
          // explorer-rt: a listed dir is a visible dir — auto-mode
          // watch subscriptions on this connection grow here (the
          // frontend's explicit-dirs subscribers manage their own set;
          // this is a no-op for them and when nothing is subscribed).
          watchSession.noteListed(basePath) *>
            wsSend(
              io.circe.Json.obj(
                "type" -> "dirListing".asJson,
                "path" -> subPath.asJson,
                "resolvedPath" -> basePath.toString.asJson,
                "entries" -> entries.asJson
              )
            )
        }
        .handleErrorWith { e =>
          logger.warn(s"listDir failed: ${e.getMessage}")
            *> wsSend(io.circe.Json.obj("type" -> "dirListing".asJson, "error" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleListDir

  /**
   * `device` 参数的目标解析单点（listDir / readFile 两腿共用；v2.1 阶段 1 §C）：
   *   - `Right(None)` = 目标本机（名字/id 与本机身份相符）——调用方走既有本地腿
   *     （避免本机↔本机的无谓网络环回）；
   *   - `Right(Some(peer))` = 对端设备（resolvePeer 分层匹配 + 一次现扫重试——
   *     RemoteExecutor.execute 同族序）；
   *   - `Left` = **显式失败**（NebLink 未启用 / 设备不可解析——G.12 同款
   *     fail-closed：设备不在线就是错误帧，绝不静默落本机）。
   *
   * ui-device-faces 批（2026-10-10）起 `wsBrowse.list` 远端腿（面A 远端只读路径
   * 选择器，WsTasksWorkspaceHandlers）也经本单点解析——同语义、零第二实现。
   */
  private[gateway] def resolveBrowseTarget(device: String)(using
    ctx: WsDispatchCtx
  ): IO[Either[String, Option[PeerInfo]]] =
    import ctx.*
    sharedResources.neblinkService match
      case None => IO.pure(Left("NebLink is not enabled on this host (no device network)"))
      case Some(ns) =>
        ns.identity.flatMap { id =>
          if device.equalsIgnoreCase(id.deviceId) || device.equalsIgnoreCase(id.deviceName) then
            IO.pure(Right(None))
          else
            ns.peers.flatMap { peers =>
              nebflow.core.tools.RemoteExecutor.resolvePeer(device, peers) match
                case Right(peer) => IO.pure(Right(Some(peer)))
                case Left(_) =>
                  ns.scanNow.flatMap { refreshed =>
                    nebflow.core.tools.RemoteExecutor.resolvePeer(device, refreshed) match
                      case Right(peer) => IO.pure(Right(Some(peer)))
                      case Left(err) => IO.pure(Left(err.message))
                  }
            }
        }

  /**
   * `listDir` 对端腿（v2.1 阶段 1 §C.1-3 无感面②）：scope=browse 元数据列举，
   * 帧形与本地腿同构（`dirListing` + `entries[{name,type,size,mtime,broken}]`；
   * `resolvedPath` = 对端权威路径），additive `device` 标注来源。任何失败 =
   * 显式 error 帧（可读原因，不静默）。
   */
  private def listRemoteDir(
    ctx: WsDispatchCtx,
    device: String,
    remoteDir: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    import ctx.*
    resolveBrowseTarget(device)(using ctx).flatMap {
      case Left(err) =>
        wsSend(io.circe.Json.obj("type" -> "dirListing".asJson, "error" -> err.asJson, "device" -> device.asJson))
      case Right(None) =>
        // 防御分支（正常不可达：self 目标在 handleListDir 已走本地腿）。
        wsSend(
          io.circe.Json.obj(
            "type" -> "dirListing".asJson,
            "error" -> s"'$device' resolved to this device — re-send without the device key".asJson,
            "device" -> device.asJson
          )
        )
      case Right(Some(peer)) =>
        nebflow.core.tools.RemoteExecutor.current match
          case None =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "dirListing".asJson,
                "error" -> "remote executor not initialized (NebLink disabled?)".asJson,
                "device" -> device.asJson
              )
            )
          case Some(re) =>
            re.transferListScoped(peer, remoteDir).flatMap {
              case Right(json) =>
                val entries = json.hcursor.downField("entries").as[Vector[io.circe.Json]].getOrElse(Vector.empty)
                val resolved = json.hcursor.downField("path").as[String].getOrElse(remoteDir)
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "dirListing".asJson,
                    "path" -> remoteDir.asJson,
                    "resolvedPath" -> resolved.asJson,
                    "entries" -> io.circe.Json.fromValues(entries),
                    "device" -> device.asJson
                  )
                )
              case Left(err) =>
                logger.warn(s"remote listDir failed for ${peer.deviceName}: $err")
                wsSend(
                  io.circe.Json.obj("type" -> "dirListing".asJson, "error" -> err.asJson, "device" -> device.asJson)
                )
            }
    }.handleErrorWith { e =>
      logger.warn(s"remote listDir failed: ${e.getMessage}")
      wsSend(io.circe.Json.obj("type" -> "dirListing".asJson, "error" -> e.getMessage.asJson, "device" -> device.asJson))
    }

  private def handleWatchSubscribe(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Explorer real-time watch (explorer-rt · chain-n-1981ce87) =====
    // Case C hybrid: push as main path, existing refresh legs untouched
    // as fallback. Frame contract in ExplorerWatchSession's doc — three
    // new cases, zero changes to existing frame shapes.

    val json = parsedJson(text)
    val hc = json.hcursor
    val wtSessionId = hc.downField("sessionId").as[String].getOrElse("")
    if wtSessionId.nonEmpty then
      val rootOverride = hc.downField("rootPath").as[Option[String]].toOption.flatten
      val dirs = hc.downField("dirs").as[Option[List[String]]].toOption.flatten
      (for
        pr <- resolveExplorerBaseRoot(wtSessionId, rootOverride)
        canonicalRoot = os.Path(pr).toIO.getCanonicalPath
        _ <- watchSession.subscribe(
          os.Path(canonicalRoot),
          rootOverride.getOrElse(""),
          dirs
        )
      yield ())
        .handleErrorWith { e =>
          logger.warn(s"watchSubscribe failed: ${e.getMessage}")
            *> wsSend(
              io.circe.Json.obj(
                "type" -> "fileOpError".asJson,
                "error" -> s"Explorer watch subscribe failed: ${e.getMessage}".asJson
              )
            )
        }
    else IO.unit
    end if
  end handleWatchSubscribe

  private def handleWatchUnsubscribe(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // No session gate on purpose: teardown is keyed by the client-sent
    // rootPath echo value alone (no root resolution, cannot fail), and
    // it must still land once the session is gone — the frontend drops
    // its subscription on session close while the connection stays
    // open; a sessionId-gated no-op there would keep a WatchService
    // polling the old root until the socket dies. Unknown key is an
    // idempotent no-op server-side.
    val json = parsedJson(text)
    val hc = json.hcursor
    val rootOverride = hc.downField("rootPath").as[Option[String]].toOption.flatten
    watchSession.unsubscribe(rootOverride.getOrElse("")).handleErrorWith { e =>
      logger.warn(s"watchUnsubscribe failed: ${e.getMessage}")
        *> wsSend(
          io.circe.Json.obj(
            "type" -> "fileOpError".asJson,
            "error" -> s"Explorer watch unsubscribe failed: ${e.getMessage}".asJson
          )
        )
    }
  end handleWatchUnsubscribe

  private def handleReadFile(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val rdSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val filePath = hc.downField("path").as[String].getOrElse("")
    // v2.1 阶段 1 §C.2（2026-10-09 实施批）：可选 `device` 键——对端单件取件
    // （scope=browse，出口白名单在对端闸；落盘 = PointerPickup 单点，absPath =
    // 本地 pickup——uploads 白名单子树，二进制腿 /api/nf-file 可取回）。缺键 =
    // 本地腿逐字节（存量零回归）。
    val rdDevice = hc.downField("device").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    if rdSessionId.nonEmpty && filePath.nonEmpty && rdDevice.isDefined then
      readRemoteFile(ctx, rdDevice.get, filePath, wsSend)
    else if rdSessionId.nonEmpty && filePath.nonEmpty then
      val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten.filter(_.nonEmpty)
      (for
        pr <- overrideRoot match
          case Some(root) => IO.pure(root)
          case None =>
            for
              metaOpt <- sessionStore.getSessionMeta(rdSessionId)
              folderId = metaOpt.flatMap(_.folderId)
              prOpt <- sessionStore.resolveProjectRoot(folderId)
            yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
        basePath <-
          if pr.nonEmpty && os.Path(pr, os.pwd).segments.nonEmpty then
            IO.blocking { PathUtil.resolvePath(filePath, os.Path(pr, os.pwd)) }
          else IO.blocking { PathUtil.resolvePath(filePath, PathUtil.dataRoot / "projects") }
        canonicalBase = basePath.toIO.getCanonicalPath
        canonicalRoot = os.Path(pr, os.pwd).toIO.getCanonicalPath
        _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
          new RuntimeException("path outside project root")
        )
        fileSize <- IO.blocking(os.size(basePath))
        readExt = filePath.split('.').lastOption.getOrElse("").toLowerCase
        readIsBinary = nebflow.core.workspace.FileTypeRegistry.detect(readExt).binary
        // 流式态（卡 §三.3，`readFile` 腿同判）：文本件 > 阈值即不读字节。
        // 改前这里对所有件无条件读（二进制读到的 content 在帧里被丢弃），
        // 现在二进制与流式态都跳过读取。
        readIsStream = TextStream.isStream(readIsBinary, fileSize)
        readMtimeMs <- IO.blocking(os.mtime(basePath))
        content <- IO.blocking {
          if readIsBinary || readIsStream then ""
          else if fileSize > 2 * 1024 * 1024 then
            os.read(basePath, offset = 0, count = 2 * 1024 * 1024) + "\n\n[... file truncated at 2MB]"
          else os.read(basePath)
        }
      yield (content, basePath.toString, fileSize, readMtimeMs, readIsStream))
        .flatMap { case (content, absPath, fileSize, mtimeMs, isStream) =>
          val ext = filePath.split('.').lastOption.getOrElse("").toLowerCase
          val entry = nebflow.core.workspace.FileTypeRegistry.detect(ext)
          val itemType = entry.itemType
          val isBinary = entry.binary
          if isBinary then
            // Binary files: don't send content via WS — frontend fetches via /api/nf-file
            wsSend(
              io.circe.Json.obj(
                "type" -> "fileContent".asJson,
                "path" -> filePath.asJson,
                "absPath" -> absPath.asJson,
                "itemType" -> itemType.asJson,
                "fileName" -> filePath.split('/').last.asJson,
                "size" -> fileSize.asJson
              )
            )
          else if isStream then
            // 流式描述符（同 `pop.readFile` 腿）：无 `content` + `stream` +
            // `mtimeMs`；`itemType` 非空是硬要求（坑 ①）。
            wsSend(
              io.circe.Json.obj(
                "type" -> "fileContent".asJson,
                "path" -> filePath.asJson,
                "absPath" -> absPath.asJson,
                "itemType" -> itemType.asJson,
                "fileName" -> filePath.split('/').last.asJson,
                "size" -> fileSize.asJson,
                "mtimeMs" -> mtimeMs.asJson,
                "stream" -> io.circe.Json.obj("v" -> io.circe.Json.fromInt(1), "kind" -> "text".asJson)
              )
            )
          else
            wsSend(
              io.circe.Json.obj(
                "type" -> "fileContent".asJson,
                "path" -> filePath.asJson,
                "absPath" -> absPath.asJson,
                "content" -> content.asJson,
                "itemType" -> itemType.asJson,
                "fileName" -> filePath.split('/').last.asJson,
                "size" -> fileSize.asJson
              )
            )
          end if
        }
        .handleErrorWith { e =>
          logger.warn(s"readFile failed: ${e.getMessage}")
            *> wsSend(
              io.circe.Json
                .obj("type" -> "fileContent".asJson, "error" -> e.getMessage.asJson, "path" -> filePath.asJson)
            )
        }
    else IO.unit
    end if
  end handleReadFile

  /**
   * `readFile` 对端腿（v2.1 阶段 1 §C.2「按需取件」）：scope=browse 单件取件 →
   * PointerPickup 落盘 → 与本地腿同构的 `fileContent` 帧（additive `device`）。
   *   - 文本腿：content 内联（本地 2MB 截断规则同规）；
   *   - 二进制腿：零 content 元数据帧，`absPath` = 本地 pickup（`uploads` 白名单
   *     子树 ⇒ 前端既有 `/api/nf-file` 取回路径可用，零策略改动）。
   * 任何失败 = 显式 error 帧（**绝不静默**——离线/未配对/白名单外都带可读原因）。
   *
   * M2（2026-10-20 · 设计 v1 §7 M2-1/2）：取件改走**分块拉取腿**
   * （`RemoteExecutor.transferGetScopedChunked`——逐块校验 + 断点续传 + 整件收口），
   * 每块落盘后外发一帧 `fileProgress`（权威读数 = 落盘长度，禁假进度）供前端
   * 「大小/速度/可取消」进度面；`fileTransferCancel` 帧翻取消旗标 ⇒ 拉取在块间停止，
   * 回 `fileContent` 帧 + `cancelled:true`（用户主动取消 ≠ 传输失败，前端出「已取消」
   * 而非错误态）。取消信令 = 本地旗标（拉取协议下对端从不自发推字节，块间即停——
   * Q2 开放点在拉取形态下的推荐默认，零新对端消息族）。
   */
  private def readRemoteFile(
    ctx: WsDispatchCtx,
    device: String,
    remotePath: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    import ctx.*
    def errorFrame(msg: String, cancelled: Boolean = false): io.circe.Json =
      val obj = io.circe.JsonObject(
        "type" -> io.circe.Json.fromString("fileContent"),
        "error" -> io.circe.Json.fromString(msg),
        "path" -> io.circe.Json.fromString(remotePath),
        "device" -> io.circe.Json.fromString(device)
      )
      io.circe.Json.fromJsonObject(if cancelled then obj.add("cancelled", io.circe.Json.True) else obj)
    def progressFrame(bytes: Long, total: Long, chunkMs: Long): io.circe.Json =
      io.circe.Json.obj(
        "type" -> "fileProgress".asJson,
        "path" -> remotePath.asJson,
        "device" -> device.asJson,
        "bytesReceived" -> bytes.asJson,
        "totalBytes" -> total.asJson,
        "chunkMs" -> chunkMs.asJson
      )
    resolveBrowseTarget(device)(using ctx).flatMap {
      case Left(err) => wsSend(errorFrame(err))
      case Right(None) =>
        // 防御分支（正常不可达：self 目标在 handleReadFile 已走本地腿）。
        wsSend(errorFrame(s"'$device' resolved to this device — re-send without the device key"))
      case Right(Some(peer)) =>
        nebflow.core.tools.RemoteExecutor.current match
          case None => wsSend(errorFrame("remote executor not initialized (NebLink disabled?)"))
          case Some(re) =>
            re.transferGetScopedChunked(peer, remotePath, (b, t, ms) => wsSend(progressFrame(b, t, ms))).flatMap {
              case Left(err) =>
                logger.warn(s"remote readFile failed for ${peer.deviceName}: $err")
                wsSend(errorFrame(err, cancelled = err == nebflow.core.tools.BrowseGetCursor.Cancelled))
              case Right(res) =>
                val ext = remotePath.split('.').lastOption.getOrElse("").toLowerCase
                val entry = nebflow.core.workspace.FileTypeRegistry.detect(ext)
                val fileName = remotePath.replaceAll("""[/\\]""", "/").split("/").lastOption.getOrElse(remotePath)
                val mtimeMs = scala.util.Try(os.mtime(res.path)).getOrElse(0L)
                // 分块腿整件 hash 的**免费副产品**（v1 §6.3）：下行帧带 `wholeSha256`
                // 前 16 hex，前端据此入缓存账本副键（多设备同文件去重）。
                val hash16 = res.wholeSha256.take(16)
                if entry.binary then
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "fileContent".asJson,
                      "path" -> remotePath.asJson,
                      "absPath" -> res.path.toString.asJson,
                      "itemType" -> entry.itemType.asJson,
                      "fileName" -> fileName.asJson,
                      "size" -> res.size.asJson,
                      "mtimeMs" -> mtimeMs.asJson,
                      "wholeSha256" -> hash16.asJson,
                      "device" -> device.asJson
                    )
                  )
                else
                  // 文本腿：与本地 readFile 的 2MB 截断规则同规（字节面截断 + 同款标记行）。
                  val bytes = os.read.bytes(res.path)
                  val content =
                    if bytes.length > 2 * 1024 * 1024 then
                      new String(bytes, 0, 2 * 1024 * 1024, "UTF-8") + "\n\n[... file truncated at 2MB]"
                    else new String(bytes, "UTF-8")
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "fileContent".asJson,
                      "path" -> remotePath.asJson,
                      "absPath" -> res.path.toString.asJson,
                      "content" -> content.asJson,
                      "itemType" -> entry.itemType.asJson,
                      "fileName" -> fileName.asJson,
                      "size" -> bytes.length.asJson,
                      "mtimeMs" -> mtimeMs.asJson,
                      "wholeSha256" -> hash16.asJson,
                      "device" -> device.asJson
                    )
                  )
            }
    }.handleErrorWith { e =>
      logger.warn(s"remote readFile failed: ${e.getMessage}")
      wsSend(errorFrame(e.getMessage))
    }

  /**
   * `fileTransferCancel`（M2 · 设计 v1 §4.4 取消契约 + Q2 推荐默认）：浏览器侧取消
   * 在途的设备取件。判据 = (device, path) 在途旗标（`BrowseFetchRegistry`，进程内
   * 会话周期账）；无在途 = 幂等 no-op（回执帧带 `active:false`，前端据此静默清理）。
   */
  private def handleFileTransferCancel(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val cancelPath = hc.downField("path").as[String].getOrElse("")
    val cancelDevice = hc.downField("device").as[String].toOption.map(_.trim).filter(_.nonEmpty).getOrElse("")
    if cancelPath.isEmpty || cancelDevice.isEmpty then IO.unit
    else
      val active = nebflow.core.tools.RemoteExecutor.cancelScopedFetch(cancelDevice, cancelPath)
      logger.info(s"[pickup-cancel] device=$cancelDevice path=$cancelPath active=$active")
      wsSend(
        io.circe.Json.obj(
          "type" -> "fileTransferCancelResult".asJson,
          "path" -> cancelPath.asJson,
          "device" -> cancelDevice.asJson,
          "active" -> active.asJson
        )
      )

  end handleFileTransferCancel

  private def handlePopReadFile(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Pop re-open: reads any absolute path without project root restriction.
    // Agent-created files (e.g. /tmp/output.svg) should always be readable.
    val json = parsedJson(text)
    val hc = json.hcursor
    val popFilePathRaw = hc.downField("path").as[String].getOrElse("")
    // Expand a leading `~` (home shorthand: `~`, `~/`, `~\`) to the
    // absolute home dir — historical Pop records may store `~/...`;
    // only the server (which knows user.home) can resolve it to an
    // absolute path. PathUtil also normalizes separators on Windows.
    val popFilePath = PathUtil.expandTilde(popFilePathRaw)
    if popFilePath.nonEmpty then
      (for
        // Cross-platform absolute check: `startsWith("/")` rejected
        // every Windows path (C:\..., C:/..., UNC) with "path must be
        // absolute" — the file-browser Windows-path bug (diag-win-paths).
        // PathUtil.isAbsolute accepts POSIX, drive-letter and UNC forms.
        _ <- IO.raiseUnless(PathUtil.isAbsolute(popFilePath))(
          new RuntimeException("path must be absolute")
        )
        // resolvePath (not bare os.Path): survives cross-drive paths
        // (pwd on C:, target on D:) via the java.nio fallback.
        basePath = PathUtil.resolvePath(popFilePath)
        _ <- IO.raiseUnless(os.exists(basePath))(
          new RuntimeException(s"file not found: $popFilePath")
        )
        _ <- IO.raiseUnless(os.isFile(basePath))(
          new RuntimeException("path is not a regular file")
        )
        fileSize = os.size(basePath)
        _ <- IO.raiseWhen(fileSize > TextStreamSearch.MaxPopReadFileBytes)(
          new RuntimeException(
            s"file exceeds ${TextStreamSearch.MaxPopReadFileBytes / (1024 * 1024)}MB limit"
          )
        )
        // 🔴 二进制腿不读字节（2026-09-20 打开闸批）：本 case 只回元数据，
        // 字节由前端经 `/api/nf-file` 流式取回（`StaticFile` ⇒
        // `fs2.io.file.Files.readRange`，支持 Range）。改前这里对所有件
        // 无条件 `os.read` 再丢弃二进制的 content —— 闸提到 100MB 后那等于
        // 为一次 PDF 打开把 100MB 读进堆里再扔。
        popExt = popFilePath.split('.').lastOption.getOrElse("").toLowerCase
        popIsBinary = nebflow.core.workspace.FileTypeRegistry.detect(popExt).binary
        // 流式态（卡 §三.3）：> TextStream.ThresholdBytes 的文本件**不读字节** ——
        // 100MiB 整件读出 = 单帧 106.4M 字符 / 峰值 +1.07GB。改为回元数据 +
        // `stream` 描述符，字节由 `textWindow` 按需拉（一窗一取 = 流控本体）。
        popIsStream = TextStream.isStream(popIsBinary, fileSize)
        popMtimeMs <- IO.blocking(os.mtime(basePath))
        content <- if popIsBinary || popIsStream then IO.pure("") else IO.blocking { os.read(basePath) }
      yield (content, basePath.toString, fileSize, popFilePath, popMtimeMs, popIsStream))
        .flatMap { case (content, absPath, fileSize, origPath, mtimeMs, isStream) =>
          val ext = popFilePath.split('.').lastOption.getOrElse("").toLowerCase
          val entry = nebflow.core.workspace.FileTypeRegistry.detect(ext)
          val itemType = entry.itemType
          val isBinary = entry.binary
          if isBinary then
            wsSend(
              io.circe.Json.obj(
                "type" -> "fileContent".asJson,
                "path" -> origPath.asJson,
                "absPath" -> absPath.asJson,
                "itemType" -> itemType.asJson,
                "fileName" -> origPath.split('/').last.asJson,
                "size" -> fileSize.asJson
              )
            )
          else if isStream then
            // 流式描述符：无 `content`（与二进制腿同形），+ `stream` + `mtimeMs`。
            // 🔴 `itemType` 必须非空（卡 §三.3 坑 ①）：空 itemType 会命中
            // canvas.js 的「空内容再取一次」分支 ⇒ 自激循环。FileTypeRegistry
            // 对未知扩展名回落 `code`，故非空 —— TextStreamSpec 钉住该不变量。
            wsSend(
              io.circe.Json.obj(
                "type" -> "fileContent".asJson,
                "path" -> origPath.asJson,
                "absPath" -> absPath.asJson,
                "itemType" -> itemType.asJson,
                "fileName" -> origPath.split('/').last.asJson,
                "size" -> fileSize.asJson,
                "mtimeMs" -> mtimeMs.asJson,
                "stream" -> io.circe.Json.obj("v" -> io.circe.Json.fromInt(1), "kind" -> "text".asJson)
              )
            )
          else
            wsSend(
              io.circe.Json.obj(
                "type" -> "fileContent".asJson,
                "path" -> origPath.asJson,
                "absPath" -> absPath.asJson,
                "content" -> content.asJson,
                "itemType" -> itemType.asJson,
                "fileName" -> origPath.split('/').last.asJson,
                "size" -> fileSize.asJson
              )
            )
          end if
        }
        .handleErrorWith { e =>
          logger.warn(s"pop.readFile failed: ${e.getMessage}")
            *> wsSend(
              io.circe.Json
                .obj(
                  "type" -> "fileContent".asJson,
                  "error" -> e.getMessage.asJson,
                  "path" -> popFilePath.asJson
                )
            )
        }
    else IO.unit
    end if
  end handlePopReadFile

  private def handleTextWindow(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ── Text-stream legs (design card §三.4: frame shapes are the contract) ──
    // Pull protocol: the client asks one window / the index / one scan at a
    // time, so flow control is structural (per tab ≤1 window in flight) and
    // the server never queues a file's worth of bytes (the `Queue.unbounded`
    // outbound trap, §二.3 ②).

    val thc = parsedJson(text).hcursor
    val twReqId = thc.downField("reqId").as[String].getOrElse("")
    val twTabId = thc.downField("tabId").as[String].getOrElse("")
    val twPath = PathUtil.expandTilde(thc.downField("path").as[String].getOrElse(""))
    val twMtime = thc.downField("mtimeMs").as[Long].getOrElse(0L)
    val twStart = thc.downField("startByte").as[Long].getOrElse(-1L)
    val twEnd = thc.downField("endByte").as[Long].getOrElse(-1L)
    if twReqId.nonEmpty && twPath.nonEmpty then
      (for
        twFile <- textSearch.resolveStreamPath(twPath)
        twSize <- IO.blocking(os.size(twFile))
        twMtimeNow <- IO.blocking(os.mtime(twFile))
        _ <- IO.raiseWhen(twMtime > 0 && twMtime != twMtimeNow)(
          new RuntimeException("file changed since the index was built (mtime drift) — re-index")
        )
        _ <- IO.raiseUnless(twStart >= 0 && twEnd > twStart)(
          new RuntimeException(s"invalid window range [$twStart, $twEnd)")
        )
        _ <- IO.raiseWhen(twEnd - twStart > TextStream.MaxWindowBytes)(
          new RuntimeException(s"window request exceeds ${TextStream.MaxWindowBytes} bytes")
        )
        _ <- IO.raiseUnless(twStart < twSize)(new RuntimeException("window starts beyond end of file"))
        twIndex <- textSearch.textIndexFor(twFile, twSize, twMtimeNow, twReqId)
        twReadEnd = math.min(twEnd, twSize)
        twBytes <- textSearch.readRangeBytes(twFile, twStart, (twReadEnd - twStart).toInt)
        twFirstLine <- textSearch.textFirstLine(twFile, twIndex, twStart)
      yield (twBytes, twFirstLine, twSize, twMtimeNow))
        .timeoutTo(
          TextStream.RequestTimeout,
          IO.raiseError(new RuntimeException(s"text window timed out after ${TextStream.RequestTimeout}"))
        )
        .flatMap { case (twBytes, twFirstLine, twSize, twMtimeNow) =>
          val realEnd = twStart + twBytes.length
          val twText = TextStream.decode(twBytes, twBytes.length)
          wsSend(
            io.circe.Json.obj(
              "type" -> "textWindow".asJson,
              "reqId" -> twReqId.asJson,
              "tabId" -> twTabId.asJson,
              "path" -> twPath.asJson,
              "mtimeMs" -> twMtimeNow.asJson,
              "startByte" -> twStart.asJson,
              "endByte" -> realEnd.asJson,
              "text" -> twText.asJson,
              "firstLine" -> twFirstLine.asJson,
              "lineCount" -> TextStream.lineCountOf(twText).asJson,
              "eof" -> (realEnd >= twSize).asJson
            )
          )
        }
        .handleErrorWith { e =>
          logger.warn(s"textWindow failed: ${e.getMessage}")
            *> wsSend(
              io.circe.Json.obj(
                "type" -> "textWindow".asJson,
                "reqId" -> twReqId.asJson,
                "tabId" -> twTabId.asJson,
                "path" -> twPath.asJson,
                "error" -> e.getMessage.asJson
              )
            )
        }
    else IO.unit
    end if
  end handleTextWindow

  private def handleTextIndex(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val thc = parsedJson(text).hcursor
    val tiReqId = thc.downField("reqId").as[String].getOrElse("")
    val tiTabId = thc.downField("tabId").as[String].getOrElse("")
    val tiPath = PathUtil.expandTilde(thc.downField("path").as[String].getOrElse(""))
    val tiMtime = thc.downField("mtimeMs").as[Long].getOrElse(0L)
    if tiReqId.nonEmpty && tiPath.nonEmpty then
      (for
        tiFile <- textSearch.resolveStreamPath(tiPath)
        tiSize <- IO.blocking(os.size(tiFile))
        tiMtimeNow <- IO.blocking(os.mtime(tiFile))
        _ <- IO.raiseWhen(tiMtime > 0 && tiMtime != tiMtimeNow)(
          new RuntimeException("file changed since the index was built (mtime drift) — re-index")
        )
        tiIndex <- textSearch.textIndexFor(tiFile, tiSize, tiMtimeNow, tiReqId)
      yield (tiSize, tiMtimeNow, tiIndex))
        .timeoutTo(
          TextStream.RequestTimeout,
          IO.raiseError(new RuntimeException(s"text index timed out after ${TextStream.RequestTimeout}"))
        )
        .flatMap { case (tiSize, tiMtimeNow, tiIndex) =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "textIndex".asJson,
              "reqId" -> tiReqId.asJson,
              "tabId" -> tiTabId.asJson,
              "path" -> tiPath.asJson,
              "size" -> tiSize.asJson,
              "mtimeMs" -> tiMtimeNow.asJson,
              "totalLines" -> tiIndex.totalLines.asJson,
              "stride" -> tiIndex.stride.asJson,
              "lineStarts" -> io.circe.Json.arr(tiIndex.lineStarts.map(io.circe.Json.fromLong)*)
            )
          )
        }
        .handleErrorWith { e =>
          logger.warn(s"textIndex failed: ${e.getMessage}")
            *> wsSend(
              io.circe.Json.obj(
                "type" -> "textIndex".asJson,
                "reqId" -> tiReqId.asJson,
                "tabId" -> tiTabId.asJson,
                "path" -> tiPath.asJson,
                "error" -> e.getMessage.asJson
              )
            )
        }
    else IO.unit
    end if
  end handleTextIndex

  private def handleTextSearch(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val thc = parsedJson(text).hcursor
    val tsReqId = thc.downField("reqId").as[String].getOrElse("")
    val tsTabId = thc.downField("tabId").as[String].getOrElse("")
    val tsPath = PathUtil.expandTilde(thc.downField("path").as[String].getOrElse(""))
    val tsMtime = thc.downField("mtimeMs").as[Long].getOrElse(0L)
    val tsQuery = thc.downField("query").as[String].getOrElse("")
    val tsCase = thc.downField("caseSensitive").as[Boolean].getOrElse(false)
    val tsMaxHitsRaw = thc.downField("maxHits").as[Long].getOrElse(TextStream.DefaultMaxHits.toLong)
    val tsMaxHits =
      if tsMaxHitsRaw <= 0 then TextStream.DefaultMaxHits
      else math.min(tsMaxHitsRaw, TextStream.MaxAllowedMaxHits.toLong).toInt
    if tsReqId.nonEmpty && tsPath.nonEmpty && tsQuery.nonEmpty then
      (for
        tsFile <- textSearch.resolveStreamPath(tsPath)
        tsSize <- IO.blocking(os.size(tsFile))
        tsMtimeNow <- IO.blocking(os.mtime(tsFile))
        _ <- IO.raiseWhen(tsMtime > 0 && tsMtime != tsMtimeNow)(
          new RuntimeException("file changed since the index was built (mtime drift) — re-index")
        )
        tsSlot <- textSearch.acquireScanSlot
        _ <- IO.raiseUnless(tsSlot)(
          new RuntimeException(s"too many concurrent text searches (limit ${TextStream.MaxConcurrentScans})")
        )
        tsScanner = new TextStream.LiteralScanner(tsQuery, tsCase, tsMaxHits)
        tsFlag <- textSearch.registerCancel(tsReqId)
        tsOutcome <- textSearch
          .runTextSearch(wsSend, tsReqId, tsTabId, tsFile, tsSize, tsScanner, tsFlag)
          .guarantee(textSearch.releaseScanSlot *> textSearch.unregisterCancel(tsReqId))
      yield tsOutcome)
        .timeoutTo(
          TextStream.RequestTimeout,
          IO.raiseError(new RuntimeException(s"text search timed out after ${TextStream.RequestTimeout}"))
        )
        .flatMap { tsOutcome =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "textSearchDone".asJson,
              "reqId" -> tsReqId.asJson,
              "tabId" -> tsTabId.asJson,
              "path" -> tsPath.asJson,
              "scannedBytes" -> tsOutcome.scannedBytes.asJson,
              "hits" -> tsOutcome.totalHits.asJson,
              "truncated" -> tsOutcome.truncated.asJson
            )
          )
        }
        .handleErrorWith { e =>
          logger.warn(s"textSearch failed: ${e.getMessage}")
            *> wsSend(
              io.circe.Json.obj(
                "type" -> "textSearch".asJson,
                "reqId" -> tsReqId.asJson,
                "tabId" -> tsTabId.asJson,
                "path" -> tsPath.asJson,
                "error" -> e.getMessage.asJson
              )
            )
        }
    else IO.unit
    end if
  end handleTextSearch

  private def handleTextCancel(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // tab 关闭 / 卸载 ⇒ 释放服务端 per-stream 状态并中断在跑扫描（卡 §三.5：
    // 不做跨会话续传，重开 = 重建索引 + 重取可见窗，成本有界）。
    val thc = parsedJson(text).hcursor
    val tcReqId = thc.downField("reqId").as[String].getOrElse("")
    if tcReqId.nonEmpty then textSearch.cancel(tcReqId)
    else IO.unit
    end if

  private def handleCreateFile(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val cfSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val cfPath = hc.downField("path").as[String].getOrElse("")
    if cfSessionId.nonEmpty && cfPath.nonEmpty then
      val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
      (for
        pr <- overrideRoot match
          case Some(root) => IO.pure(root)
          case None =>
            for
              metaOpt <- sessionStore.getSessionMeta(cfSessionId)
              folderId = metaOpt.flatMap(_.folderId)
              prOpt <- sessionStore.resolveProjectRoot(folderId)
            yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
        basePath = PathUtil.resolvePath(cfPath, os.Path(pr))
        canonicalBase = basePath.toIO.getCanonicalPath
        canonicalRoot = os.Path(pr).toIO.getCanonicalPath
        _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
          new RuntimeException("path outside project root")
        )
        _ <- IO.blocking {
          val parent = basePath / os.up
          if !os.exists(parent) then os.makeDir.all(parent)
          if !os.exists(basePath) then os.write.over(basePath, "")
        }
      yield cfPath)
        .flatMap { p =>
          // M2 缓存一致性（v1 §6.1）：UI 通道的文件事实变更也广播失效（与 Write 工具
          // 同一面；新建空文件对「内容缓存」无实义，但覆盖写场景 `writeFile` 在下）。
          NfCacheInvalidator.invalidated(p) *> wsSend(io.circe.Json.obj("type" -> "fileCreated".asJson, "path" -> p.asJson))
        }
        .handleErrorWith { e =>
          logger.warn(s"createFile failed: ${e.getMessage}")
          wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleCreateFile

  private def handleCreateDir(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val cdSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val cdPath = hc.downField("path").as[String].getOrElse("")
    if cdSessionId.nonEmpty && cdPath.nonEmpty then
      val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
      (for
        pr <- overrideRoot match
          case Some(root) => IO.pure(root)
          case None =>
            for
              metaOpt <- sessionStore.getSessionMeta(cdSessionId)
              folderId = metaOpt.flatMap(_.folderId)
              prOpt <- sessionStore.resolveProjectRoot(folderId)
            yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
        basePath = PathUtil.resolvePath(cdPath, os.Path(pr))
        canonicalBase = basePath.toIO.getCanonicalPath
        canonicalRoot = os.Path(pr).toIO.getCanonicalPath
        _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
          new RuntimeException("path outside project root")
        )
        _ <- IO.blocking { os.makeDir.all(basePath) }
      yield cdPath)
        .flatMap { p =>
          wsSend(io.circe.Json.obj("type" -> "dirCreated".asJson, "path" -> p.asJson))
        }
        .handleErrorWith { e =>
          logger.warn(s"createDir failed: ${e.getMessage}")
          wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleCreateDir

  private def handleDeletePath(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val dpSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val dpPath = hc.downField("path").as[String].getOrElse("")
    if dpSessionId.nonEmpty && dpPath.nonEmpty then
      val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
      (for
        pr <- overrideRoot match
          case Some(root) => IO.pure(root)
          case None =>
            for
              metaOpt <- sessionStore.getSessionMeta(dpSessionId)
              folderId = metaOpt.flatMap(_.folderId)
              prOpt <- sessionStore.resolveProjectRoot(folderId)
            yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
        basePath = PathUtil.resolvePath(dpPath, os.Path(pr))
        canonicalBase = basePath.toIO.getCanonicalPath
        canonicalRoot = os.Path(pr).toIO.getCanonicalPath
        _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
          new RuntimeException("path outside project root")
        )
        // Prevent deleting the project root itself
        _ <- IO.raiseWhen(canonicalBase == canonicalRoot)(new RuntimeException("cannot delete project root"))
        // remove.all: single delete must cover non-empty directories too
        // (VS Code parity — a right-click delete on a folder takes the
        // whole subtree; previously os.remove failed on non-empty dirs
        // and only the batch deletePaths channel could remove them).
        // On a plain file remove.all behaves exactly like remove.
        wasDir <- IO.blocking { os.isDir(basePath) }
        existed <- IO.blocking { os.exists(basePath) }
        _ <- IO.blocking { if existed then os.remove.all(basePath) }
        // ── 补盲区：删除**成功分支**留痕（#159/#176 wtsurv 批，2026-09-14）────
        // 取证件 `20260913_100008_worktree-vanish-forensics.md` §1.4 第 1 条 /
        // §6.1 第 2 条：WS `deletePath` 是**非网关通道**（UI 触发，不是 agent
        // 工具调用），递归删目录用 `os.remove.all` ⇒ **不碰** `.git/worktrees/
        // <name>` 注册 ⇒ 精确制造「目录消失 + 注册残留 = prunable」签名；而成功
        // 路径**零日志**（失败才 `logger.warn`）⇒ 该类路径在取证面不存在。
        // 本行把该通道纳入可审计面：记录**被删路径**（canonical，与包含校验同一
        // 参照系）与**来源会话**。语义边界（硬）：只证「本通道删过 X」，
        // **不指认**任何历史事件的责任人（责任者未证；见取证件 §1.4）。
        // ⚠ 只在路径**实存**时写（`existed`）：对已被删掉的路径，单删语义是
        // 「no-op 成功」，写「removed」会**误报**（审计面第一条纪律：不写没发生的事）。
        _ <-
          if existed then logger.info(FsOps.deleteAuditLine("deletePath", canonicalBase, dpSessionId, wasDir))
          else IO.unit
      yield dpPath)
        .flatMap { p =>
          // M2 缓存一致性：删除 ⇒ 广播失效（账本残留条目指向已删件，立即淘汰）。
          NfCacheInvalidator.invalidated(p) *> wsSend(io.circe.Json.obj("type" -> "pathDeleted".asJson, "path" -> p.asJson))
        }
        .handleErrorWith { e =>
          logger.warn(s"deletePath failed: ${e.getMessage}")
          wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleDeletePath

  private def handleMovePath(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // F1 file-explorer single-item move: relocate a file/dir into a
    // target directory within the same project root (rename semantics).
    val json = parsedJson(text)
    val hc = json.hcursor
    val mpSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val mpPath = hc.downField("path").as[String].getOrElse("")
    val mpTargetDir = hc.downField("targetDir").as[String].getOrElse("")
    // targetDir may be empty: dropping onto the tree's blank root area
    // moves the item to the project root (frontend contract).
    if mpSessionId.nonEmpty && mpPath.nonEmpty then
      val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
      (for
        pr <- overrideRoot match
          case Some(root) => IO.pure(root)
          case None =>
            for
              metaOpt <- sessionStore.getSessionMeta(mpSessionId)
              folderId = metaOpt.flatMap(_.folderId)
              prOpt <- sessionStore.resolveProjectRoot(folderId)
            yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
        rootPath = os.Path(pr)
        newPath <- IO
          .blocking(FsOps.movePathSafely(mpPath, mpTargetDir, rootPath))
          .flatMap {
            case Right(np) => IO.pure(np)
            case Left(err) => IO.raiseError(new RuntimeException(err))
          }
      yield (mpPath, newPath))
        .flatMap { case (oldPath, newPath) =>
          // M2 缓存一致性：移动 = 旧路径失效 + 新路径入账待刷新（两侧都广播）。
          NfCacheInvalidator.invalidated(oldPath) *> NfCacheInvalidator.invalidated(newPath) *>
            wsSend(
              io.circe.Json.obj(
                "type" -> "pathMoved".asJson,
                "oldPath" -> oldPath.asJson,
                "newPath" -> newPath.asJson
              )
            )
        }
        .handleErrorWith { e =>
          logger.warn(s"movePath failed: ${e.getMessage}")
          wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleMovePath

  private def handleDeletePaths(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // F1 file-explorer multi-select: batch delete in ONE round trip.
    // Per-path guards identical to deletePath; failures are aggregated
    // per item so a partial failure never blocks the rest.
    val json = parsedJson(text)
    val hc = json.hcursor
    val dpsSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val dpsPaths = hc.downField("paths").as[List[String]].getOrElse(Nil)
    if dpsSessionId.nonEmpty && dpsPaths.nonEmpty then
      val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
      (for
        pr <- overrideRoot match
          case Some(root) => IO.pure(root)
          case None =>
            for
              metaOpt <- sessionStore.getSessionMeta(dpsSessionId)
              folderId = metaOpt.flatMap(_.folderId)
              prOpt <- sessionStore.resolveProjectRoot(folderId)
            yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
        (deleted, failed) <- FsOps.deletePathsSafely(dpsPaths, os.Path(pr), dpsSessionId)
      yield (deleted, failed))
        .flatMap { case (deleted, failed) =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "pathsDeleted".asJson,
              "deleted" -> deleted.asJson,
              "failed" -> failed.map { case (p, err) =>
                io.circe.Json.obj("path" -> p.asJson, "error" -> err.asJson)
              }.asJson
            )
          )
        }
        .handleErrorWith { e =>
          logger.warn(s"deletePaths failed: ${e.getMessage}")
          wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleDeletePaths

  private def handleWriteFile(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val wrSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val wrFilePath = hc.downField("path").as[String].getOrElse("")
    val wrContent = hc.downField("content").as[String].getOrElse("")
    // Optional `encoding:"base64"` — byte-preserving writes for binary
    // payloads (external file drag-in). Absent = plain text, the
    // editor-save path, unchanged.
    val wrEncoding = hc.downField("encoding").as[String].getOrElse("")
    if wrSessionId.nonEmpty && wrFilePath.nonEmpty then
      val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
      (for
        pr <- overrideRoot match
          case Some(root) => IO.pure(root)
          case None =>
            for
              metaOpt <- sessionStore.getSessionMeta(wrSessionId)
              folderId = metaOpt.flatMap(_.folderId)
              prOpt <- sessionStore.resolveProjectRoot(folderId)
            yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
        basePath = PathUtil.resolvePath(wrFilePath, os.Path(pr))
        canonicalBase = basePath.toIO.getCanonicalPath
        canonicalRoot = os.Path(pr).toIO.getCanonicalPath
        _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
          new RuntimeException("path outside project root")
        )
        _ <- IO.blocking {
          // Create parent dirs on demand so a drop-imported folder tree
          // lands recursively (no separate mkdir round trip). No-op when
          // the parent exists (editor-save path).
          if !os.exists(basePath / os.up) then os.makeDir.all(basePath / os.up)
          if wrEncoding == "base64" then os.write.over(basePath, java.util.Base64.getDecoder.decode(wrContent))
          else os.write.over(basePath, wrContent)
        }
      yield basePath.toString)
        .flatMap { absPath =>
          val desc =
            if wrEncoding == "base64" then s"${wrContent.length} b64 chars"
            else s"${wrContent.length} chars"
          logger.info(s"File saved: $absPath ($desc)")
          // M2 缓存一致性（v1 §6.1）：编辑器保存 = 最典型的「本机文件改写」⇒ 广播失效。
          NfCacheInvalidator.invalidated(absPath) *>
            wsSend(
              io.circe.Json.obj(
                "type" -> "fileSaved".asJson,
                "path" -> wrFilePath.asJson
              )
            )
        }
        .handleErrorWith { e =>
          logger.warn(s"writeFile failed: ${e.getMessage}")
          wsSend(
            io.circe.Json.obj(
              "type" -> "fileSaveError".asJson,
              "path" -> wrFilePath.asJson,
              "error" -> e.getMessage.asJson
            )
          )
        }
    else IO.unit
    end if
  end handleWriteFile

  private def handleBrowsePath(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Directory browser for project root selection.
    // picker-trunc (2026-09-22 author ruling, A+B+C): the old body ended in
    // a silent `entries.take(200)` with no truncation word — the author
    // could not find `~/Downloads` and got no hint that anything was cut.
    // Now: real cap ([[browseEntryCap]]) + explicit `truncated`/`total`
    // (A), server-side `query` filtering before the cap (B), and
    // `~`/`~/` expansion via the shared expandTilde with a typed inline
    // error on an unusable path (C).
    val json = parsedJson(text)
    val rawPath = json.hcursor.downField("path").as[String].getOrElse("~")
    val query = json.hcursor.downField("query").as[String].getOrElse("")
    IO.blocking {
      // C: `~` forms resolve through the shared expandTilde (same helper
      // wsBrowse.list / wsBrowse.mkdir use), so `~` and `~/...` agree.
      val expanded = expandTilde(rawPath.trim)
      scala.util.Try(os.Path(expanded, os.pwd)).toOption match
        case None =>
          (None, browseErrorFrame(rawPath, "invalid-path", s"Invalid path: $rawPath", query))
        case Some(dir) if !os.exists(dir) =>
          (None, browseErrorFrame(rawPath, "invalid-path", s"No such directory: $rawPath", query))
        case Some(dir) if !os.isDir(dir) =>
          (None, browseErrorFrame(rawPath, "not-a-directory", s"Not a directory: $rawPath", query))
        case Some(dir) =>
          // The frame reports the RESOLVED path (the frontend breadcrumb
          // has always consumed `path`); the raw `~` form stays in the
          // error arm only.
          (Some(dir), browseResultFrame(dir, dir.toString, query, browseEntryCap))
    }.flatMap { case (dirOpt, frame) =>
      dirOpt match
        case Some(dir) =>
          logger.info(
            s"browsePath: path=$rawPath query=$query cap=$browseEntryCap " +
              s"total=${frame.hcursor.get[Int]("total").getOrElse(-1)} " +
              s"truncated=${frame.hcursor.get[Boolean]("truncated").getOrElse(false)} resolved=${dir.toString}"
          )
        case None => ()
      wsSend(frame)
    }.handleErrorWith { e =>
      // An unreadable directory (permissions, I/O error) is reported with
      // the same typed frame — never silently an empty list.
      logger.warn(s"browsePath failed: path=$rawPath query=$query ${e.getMessage}")
      wsSend(browseErrorFrame(rawPath, "unreadable", s"${e.getClass.getSimpleName}: ${e.getMessage}", query))
    }
  end handleBrowsePath

end WsFileOpsHandlers
