package nebflow.core.tools

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Ref}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.AgentCommand
import nebflow.core.{NeblinkClientPort, NeblinkServicePort}
import nebflow.shared.{ContentBlock, NebflowLogger, PeerInfo}
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Pure decision surface for the P2P-vs-relay choice (A) and path memory (C4),
 * 2026-09-11 P2P 直连修复批.
 *
 * WHY pure: the two accepted criteria of the plan are behavioural
 * ("`directOnline=false` 且无负证据 ⇒ 尝试 P2P"; "地址变化 ⇒ 记忆失效"; "TTL 过期 ⇒
 * 重探"), and they were previously buried in IO/`@volatile` state where they could
 * only be observed by driving a real network. Extracting them makes each rule a
 * unit-testable function (`P2pPathDecisionSpec`) instead of a claim in a report.
 */
private[nebflow] object P2pPathDecision:

  /**
   * Path-memory TTL (C4). Lowered from the pre-fix 5 min to 60 s.
   *
   * WHY 60s: 5 min was long enough that a single relay success pinned the device
   * to relay for the rest of the window, and every further relay success renewed
   * it (`RemoteExecutor:514/:529`) — so a device whose P2P came back never
   * returned to P2P. 60s bounds the worst case to "one extra P2P probe per minute
   * per device" while keeping the memory long enough to spare the common
   * back-to-back write burst.
   */
  val PathMemoryTtlMs: Long = 60_000L

  /** Negative P2P evidence TTL (A): how long one real failure keeps P2P bypassed. */
  val NegativeCacheTtlMs: Long = 60_000L

  /** Short connect budget for a probe (A): unreachable addresses must fail fast. */
  val ProbeConnectTimeout: FiniteDuration = 1500.millis

  /**
   * Probe budget. `directOnline` (is there an active presence WS?) is **demoted
   * from decision basis to budget hint** — presence-not-connected means "the WS
   * dial did not succeed", not "the peer is unreachable" (different transport).
   */
  final case class ProbeBudget(maxRetries: Int, connectTimeout: FiniteDuration, shortConnect: Boolean)

  /**
   * `directOnline=true` ⇒ trust the peer, retry like before (3 retries, 3s
   * connect — the existing P0-1 posture). `false` ⇒ probe once with a 1.5s
   * connect budget.
   *
   * Worst-case cost before falling back to relay, which is the number the plan's
   * cost caveat is about:
   *   - `false`: ≤ 1.5s connect + one request (no retry, no backoff).
   *   - pre-fix behaviour this replaces: 3s connect × 4 attempts + 1+2+3s
   *     backoff ≈ 12-15s, i.e. the reason `skipP2p` existed at all.
   */
  def probeBudget(directOnline: Boolean): ProbeBudget =
    if directOnline then ProbeBudget(3, 3.seconds, false)
    else ProbeBudget(0, ProbeConnectTimeout, true)

  /**
   * A (负证据化): bypass P2P **only** when there is real recent P2P failure
   * evidence — a relay-route path memory, or a fresh dial/P2P failure
   * ([[NegativeCacheTtlMs]]). Previously `skipP2p = memory=="relay" || !directOnline`
   * turned "no presence WS" into "P2P impossible", which is how 9/9 dispatches
   * went to relay (方案 §1.2 (F)).
   *
   * `relayAvailable` is part of the conjunction: without a relay there is nothing
   * to bypass *to*, so the caller must still attempt P2P.
   */
  def shouldSkipP2pForRelay(
    memoryMethod: Option[String],
    recentP2pFailure: Boolean,
    relayAvailable: Boolean
  ): Boolean =
    relayAvailable && (memoryMethod.contains("relay") || recentP2pFailure)

  /**
   * Address-sensitivity key (C4): the whole endpoint set, sorted, so that **any**
   * change of address or endpoint set invalidates the memory (方案 §3.3 「如何更新」).
   * Sorted (not preference-ordered) deliberately — ordering churn alone must not
   * drop a valid memory, only an actual change of the reachable set may.
   */
  def addressKey(peer: PeerInfo): String =
    val list = if peer.endpoints.nonEmpty then peer.endpoints else List(peer.address)
    list.filter(_.nonEmpty).sorted.distinct.mkString("|")

  /** Path-memory entry: which transport last succeeded, when, and for which address set. */
  final case class PathMemory(method: String, atMs: Long, addressKey: String)

  def isFresh(mem: PathMemory, nowMs: Long): Boolean = nowMs - mem.atMs < PathMemoryTtlMs

  /** Memory usable for this dispatch: TTL fresh **and** same address set. */
  def reusable(mem: Option[PathMemory], peer: PeerInfo, nowMs: Long): Option[PathMemory] =
    mem.filter(m => m.addressKey == addressKey(peer) && isFresh(m, nowMs))

  /**
   * Write a memory entry. C4: **relay successes do not slide the window** — if a
   * fresh relay entry already exists for the same address, its timestamp is kept,
   * so the window always expires and P2P is re-probed at least once per TTL. P2P
   * successes *do* renew (staying on P2P is the desired state).
   */
  def record(existing: Option[PathMemory], method: String, peer: PeerInfo, nowMs: Long): PathMemory =
    val key = addressKey(peer)
    existing match
      case Some(m) if method == "relay" && m.method == "relay" && m.addressKey == key && isFresh(m, nowMs) =>
        m // no renewal — see scaladoc
      case _ => PathMemory(method, nowMs, key)

end P2pPathDecision

/**
 * A1（作者裁定 2026-09-16）：设备腿 `Read` 的「标记 → 既有回拉通道 → 视觉块」。
 *
 * WHY（`devread-forensic` 判词，两跳并发必要）：设备腿的结果域恒为 `String`——
 * 执行侧序列化（L2/L3）与调用侧解析（L4/L5）都只搬文本，**视觉块从未产生**；
 * 全仓唯一执行期提取点只在**本机腿**（`AgentCore.scala:1763` 取块 / `:1774` 置块），
 * 远端分支（`:1692-1700`）连字段都没填（L6）。
 *
 * 修法：远端返回体命中 `[image: <name> | <mime> | <KB>[ | note]]`
 * （`ReadTool.scala:144` 的产出形态，经 L2/L3→L4/L5 **恒等搬运**）⇒ 回拉目标由
 * 工具调用入参里的 `file_path`（**远端绝对路径**）确定，走**既有**
 * `FileTransfer direction=get`（零 wire 变更、零新 action）取回字节。
 *
 * 本对象只放**纯函数面**（可离线测）：标记解析 + 字节→块；IO 编排见
 * `RemoteExecutor#remoteReadImages`。
 */
private[nebflow] object RemoteImage:

  /** 标记前缀 —— 与 `ReadTool.scala:144`（产出）/`:228`（提取闸）/`:100`（摘要）同一形态。 */
  val MarkerPrefix = "[image:"

  /** 标记字段：`<name> | <mime> | <KB>[ | note]`（`sizeKb` 仅供诊断，**不作闸**）。 */
  final case class Marker(fileName: String, declaredMime: String, sizeKb: Long)

  /** 解析标记；非标记 / 形态不足 ⇒ None（调用侧据此**零动作**，不为文本结果白发回拉）。 */
  def parseMarker(result: String): Option[Marker] =
    val firstLine = result.linesIterator.nextOption().getOrElse("")
    if !firstLine.startsWith(MarkerPrefix) || !firstLine.endsWith("]") then None
    else
      val fields = firstLine.stripPrefix(MarkerPrefix).stripSuffix("]").split("\\|").map(_.trim)
      if fields.length < 2 || fields(0).isEmpty then None
      else
        val kb = fields(2).takeWhile(_.isDigit).toLongOption.getOrElse(0L)
        Some(Marker(fields(0), fields(1), kb))

  /**
   * 字节 → 视觉块。**逐行对齐本机腿** `ReadTool.extractImages`（`ReadTool.scala:233-244`）：
   * 同源 mime 解析（按文件名扩展名 → [[ImageInject.imageMimeType]]）、同源压缩/上限判据
   * （[[ImageInject.prepareImage]]，`TooLarge` ⇒ None、异常 ⇒ None）——**零自造阈值、
   * 零第二套机制**。
   *
   * 🔴 为什么不直接调 `ReadTool.extractImages`：它读的是**本机**文件系统
   * （`:235 Files.readAllBytes(filePath)`），远端路径在调用侧不存在 ⇒ 恒 None
   * （判词的「只修 L6 无效」正是此因）。故字节必须由回拉提供，仅复用其后的构块口径。
   */
  def buildBlocks(bytes: Array[Byte], fileName: String): Option[List[ContentBlock.Image]] =
    ImageInject.imageMimeType(fileName).flatMap { mediaType =>
      try
        ImageInject.prepareImage(bytes, mediaType, fileName) match
          case ImageInject.TooLarge(_) => None
          case ImageInject.Prepared(prepared, preparedMime, _) =>
            Some(List(ContentBlock.Image(java.util.Base64.getEncoder.encodeToString(prepared), preparedMime)))
      catch case _: Exception => None
    }

end RemoteImage

/**
 * Executes tool calls on remote devices via direct P2P over NebLink.
 *
 * When a tool call specifies device="desktop-v7eucht", this executor routes
 * the call to that device's gateway via HTTP (POST /api/neblink/remote-exec).
 *
 * NebLink provides the connectivity layer — no relay server needed.
 */
class RemoteExecutor(
  neblinkService: NeblinkServicePort,
  dispatcher: Dispatcher[IO],
  relayClient: Option[NeblinkClientPort] = None
):

  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  /**
   * The relay client ACTUALLY in use, resolved per dispatch (F1 sweep of the
   * 2026-09-10 隧道鉴权自愈批).
   *
   * `NeblinkService.relayClientOpt` is the hot-swap pointer: GatewayMain
   * registers the startup client and `NeblinkEnrollment.persist` re-points it
   * on every re-enrollment (logout clears it) — reading it live means a UI
   * re-login / account switch is followed instead of a constructor-time
   * snapshot. This class used to capture client₀ and kept dispatching through
   * a client whose session the server had kicked (the relay path is exactly the
   * "control the other computer" business leg of the incident).
   *
   * The constructor argument stays as a fallback for tests / non-gateway
   * wiring, where the service pointer is never set.
   */
  private def currentRelayClient: IO[Option[NeblinkClientPort]] =
    IO(neblinkService.relayClientOpt).map(_.orElse(relayClient))

  /** Timeout for synchronous remote calls without ToolContext (fallback path). */
  private val SyncTimeout = 120.seconds

  // （BgTimeout 已上移伴生：bgHttpWaitTimeout 判据与类内引用同源单点，禁两处漂移。）

  // ---- P0-3: Path memory — remember last successful transport per device ----

  /**
   * Map: deviceId → last successful transport.
   *
   * C4 (2026-09-11 P2P 直连修复批): entries now carry the address set they were
   * recorded for, so a changed address/endpoint set invalidates the memory
   * instead of silently pinning the device to a stale transport choice.
   */
  @volatile private var lastSuccessPath: Map[String, P2pPathDecision.PathMemory] = Map.empty

  /**
   * Negative P2P evidence (A): deviceId → timestamp of the last real P2P
   * failure. Only a fresh entry here (see [[P2pPathDecision.NegativeCacheTtlMs]])
   * justifies bypassing P2P — "no presence WS" no longer does.
   */
  @volatile private var p2pFailures: Map[String, Long] = Map.empty

  /**
   * Read the cached transport **only if** it is both TTL-fresh and still refers
   * to the peer's current address set; an expired or address-stale entry is
   * dropped (so the next call re-probes P2P).
   */
  private def getPathMemory(peer: PeerInfo): Option[String] =
    val now = System.currentTimeMillis()
    val usable = P2pPathDecision.reusable(lastSuccessPath.get(peer.deviceId), peer, now)
    if usable.isEmpty && lastSuccessPath.contains(peer.deviceId) then
      lastSuccessPath = lastSuccessPath.removed(peer.deviceId)
    usable.map(_.method)

  private def recordPathMemory(peer: PeerInfo, method: String): IO[Unit] = IO {
    lastSuccessPath = lastSuccessPath.updated(
      peer.deviceId,
      P2pPathDecision.record(lastSuccessPath.get(peer.deviceId), method, peer, System.currentTimeMillis())
    )
  }

  private def clearPathMemory(deviceId: String): IO[Unit] = IO {
    lastSuccessPath = lastSuccessPath.removed(deviceId)
  }

  /** Record real P2P failure evidence (A) — the ONLY thing that bypasses P2P. */
  private def recordP2pFailure(peer: PeerInfo): IO[Unit] = IO {
    p2pFailures = p2pFailures.updated(peer.deviceId, System.currentTimeMillis())
  }

  private def hasRecentP2pFailure(deviceId: String): Boolean =
    p2pFailures.get(deviceId).exists(ts => System.currentTimeMillis() - ts < P2pPathDecision.NegativeCacheTtlMs)

  /**
   * HTTP backend for a probe budget (A): the short-connect backend when the
   * budget is a fast-fail probe, otherwise the shared 3s-connect backend
   * (`NeblinkService.httpBackend`, the existing P0-1 posture).
   */
  private lazy val shortConnectBackend: sttp.client4.SyncBackend =
    sttp.client4.httpclient.HttpClientSyncBackend.usingClient(
      java.net.http.HttpClient
        .newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(P2pPathDecision.ProbeConnectTimeout.toMillis))
        .build()
    )

  private def backendFor(budget: P2pPathDecision.ProbeBudget): sttp.client4.SyncBackend =
    if budget.shortConnect then shortConnectBackend else neblinkService.httpBackend

  /** Read-only tools safe for parallel P2P + Relay racing (no side effects on cancellation). */
  private val ReadOnlyTools = Set("Read", "Glob", "Grep")

  /** Expose NeblinkService for system prompt generation (device list). */
  def neblinkServiceOpt: Option[NeblinkServicePort] = Some(neblinkService)

  def execute(
    deviceName: String,
    toolName: String,
    params: JsonObject,
    ctxOpt: Option[ToolContext] = None
  ): IO[Either[ToolError, String]] =
    val isBackground = params("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    // T4 审计用：projectRoot 随 ctx 传递（无 ctx 的 REST/兜底路径记空串）
    val projectRoot = ctxOpt.map(_.projectRoot).getOrElse("")

    def runOnPeer(peer: PeerInfo, p: JsonObject): IO[Either[ToolError, String]] =
      if peer.address.isEmpty then IO.pure(Left(ToolError(s"Device '${peer.deviceName}' has no address.")))
      else if isBackground && ctxOpt.isDefined then executeRemoteBackground(peer, toolName, p, ctxOpt.get)
      else if ctxOpt.isDefined then executeForegroundSync(peer, toolName, p, ctxOpt.get)
      else executeViaBestPath(peer, toolName, p, SyncTimeout, projectRoot)

    // xdev 批（2026-09-15）：单一下发包装 —— ① 画像惰性探测（只读、失败不
    // 阻塞）→ ⑤ 白名单等价改写（默认关）→ 下发 → ② NEBFLOW_PULL 回拉扫描。
    // 三步全部「尽力而为」：任何一步失败都不改变下发的成败语义。
    def runWithProfile(peer: PeerInfo): IO[Either[ToolError, String]] =
      for
        profOpt <- ensureProfile(peer)
        (effectiveParams, rewriteNote) = prepareBashParams(toolName, params, profOpt)
        _ <- rewriteNote match
          case Some(n) => XdevRewrite.logNote(peer.deviceName, n)
          case None => IO.unit
        raw <- runOnPeer(peer, effectiveParams)
        pulled <- raw match
          case Right(out) if toolName == "Bash" => pullCaptures(peer, out, isBackground)
          case other => IO.pure(other)
      yield pulled.map { out =>
        rewriteNote.fold(out)(n => out + s"\n[xdev-rewrite] $n")
      }

    neblinkService.peers.flatMap { peers =>
      RemoteExecutor.resolvePeer(deviceName, peers) match
        case Right(peer) => runWithProfile(peer)
        case Left(_) =>
          // Device not in peer list — the list might be stale. Trigger one immediate
          // discovery scan before giving up, so transient gaps don't cause false errors.
          logger.info(s"Device '$deviceName' not found in ${peers.size} peer(s), triggering discovery scan") *>
            neblinkService.scanNow.flatMap { refreshedPeers =>
              RemoteExecutor.resolvePeer(deviceName, refreshedPeers) match
                case Right(peer) => runWithProfile(peer)
                case Left(err) => IO.pure(Left(err))
            }
    }
  end execute

  // ---- xdev 批：画像 / 改写 / 回拉（2026-09-15） ----

  /** 内存画像缓存（避免每次下发读盘；store 与内存同步写）。 */
  private val profileMem =
    new java.util.concurrent.ConcurrentHashMap[String, DeviceProfileEntry]()

  /** 探测去重：同一 deviceId 同时只允许一个在飞探测（并发首触只发一条探针）。 */
  private val probeInFlight = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

  /**
   * 探测专用短超时（探针是单条只读命令，20s 网络层兜底足够；对端 BashTool
   * timeout 参数给 15s）。
   */
  private val ProbeTimeout = 20.seconds

  /**
   * ① 画像惰性探测（T1）。缓存新鲜 ⇒ 直接返回；缺失/stale/负条目静默期已过 ⇒
   * 发**一条只读 Bash 探针**（走 executeViaBestPath 直达，不经 execute ⇒ 无递归、
   * 不触发改写/回拉）。探测失败 ⇒ 负条目 + WARN，**绝不阻塞本次下发**（返回
   * None = 无画像，fail-closed 回现状行为）。
   */
  private def ensureProfile(peer: PeerInfo): IO[Option[DeviceProfileEntry]] =
    val now = System.currentTimeMillis()
    Option(profileMem.get(peer.deviceId)) match
      case Some(e) if !DeviceProfile.needsProbe(Some(e), peer, now) => IO.pure(Some(e))
      case _ =>
        if probeInFlight.putIfAbsent(peer.deviceId, java.lang.Boolean.TRUE) != null then
          IO.pure(Option(profileMem.get(peer.deviceId)))
        else
          val probeParams = JsonObject(
            "command" -> DeviceProfileProbe.Command.asJson,
            "timeout" -> 15000L.asJson,
            "description" -> "xdev read-only profile probe".asJson
          )
          executeViaBestPath(peer, "Bash", probeParams, ProbeTimeout, "", kind = "probe")
            .flatMap {
              case Right(out) =>
                val fields = DeviceProfileProbe.parse(out)
                if fields.isEmpty then
                  // 探针命令跑通但零可解析字段（对端 banner-only？）⇒ 按失败处理
                  DeviceProfile.recordProbeFailed(peer, System.currentTimeMillis()).map { eOpt =>
                    eOpt.foreach(e => profileMem.put(peer.deviceId, e))
                    None
                  }
                else
                  DeviceProfile
                    .recordProbe(peer, fields, peer.capabilities, System.currentTimeMillis())
                    .map { eOpt =>
                      eOpt.foreach(e => profileMem.put(peer.deviceId, e))
                      eOpt
                    }
              case Left(err) =>
                DeviceProfile.recordProbeFailed(peer, System.currentTimeMillis()).map { eOpt =>
                  eOpt.foreach(e => profileMem.put(peer.deviceId, e))
                  logger.warn(
                    s"[device-profile] probe failed for ${peer.deviceName}: ${err.message.take(100)} (negative entry, ${DeviceProfile.NegativeRetryMs / 60000}min backoff; original call proceeds)"
                  )
                  None
                }
            }
            .guarantee(IO(probeInFlight.remove(peer.deviceId)))

    end match

  end ensureProfile

  /** ⑤ 改写决策（下发前）。仅 Bash、仅画像确证 MSYS、仅白名单形态；默认关。 */
  private def prepareBashParams(
    toolName: String,
    params: JsonObject,
    profOpt: Option[DeviceProfileEntry]
  ): (JsonObject, Option[String]) =
    if toolName != "Bash" then (params, None)
    else
      params("command").flatMap(_.asString) match
        case Some(cmd) =>
          val (c2, note) = XdevRewrite(cmd, XdevRewrite.msysConfirmed(profOpt))
          (if note.nonEmpty then params.add("command", c2.asJson) else params, note)
        case None => (params, None)

  /**
   * ② NEBFLOW_PULL 回拉：扫描对端 Bash 输出里的标记行，逐个把对端文件经既有
   * FileTransfer direction=get 通道拉回本机 captures/。成功/失败都**追加可读行**
   * （🔴 禁静默：拉取失败显式留痕，模型可自纠）。后台腿 v1 不接（其真实输出
   * 走通知路径，占位符上无意义）。
   */
  private def pullCaptures(
    peer: PeerInfo,
    output: String,
    isBackground: Boolean
  ): IO[Either[ToolError, String]] =
    if isBackground then IO.pure(Right(output))
    else
      CapturePull.scanPullPaths(output) match
        case Nil => IO.pure(Right(output))
        case paths =>
          paths.foldLeft(IO.pure(Right(output): Either[ToolError, String])) { (acc, remotePath) =>
            acc.flatMap {
              case l @ Left(_) => IO.pure(l)
              case Right(out) =>
                transferGetToFile(peer, remotePath).map {
                  case Right((local, size)) =>
                    Right(out + s"\n[capture] pulled $remotePath -> $local ($size bytes)")
                  case Left(err) =>
                    logger.warn(s"[capture] pull failed for ${peer.deviceName}: $remotePath: ${err.take(120)}")
                    Right(out + s"\n[capture] pull FAILED for $remotePath: $err")
                }
            }
          }

  /**
   * 经既有 FileTransfer direction=get 通道拉单件：P2P remote-exec 优先，transient
   * 失败回落 relay（与 executeViaBestPath 同族判定）。两条腿的接收端都路由到
   * FileTransferAction.handle（RestApiRoutes / NeblinkRelayTunnel）——**零 wire
   * 变更、零新 action**。大小闸 [[CapturePull.MaxCaptureBytes]]。
   *
   * 2026-09-16（A1 批）：解析/取字节抽成 [[transferGetBytes]] 单点后本方法只剩
   * 「落盘」——行为逐字不变（同一闸、同一 dispatch、同一落位）。
   */
  private def transferGetToFile(
    peer: PeerInfo,
    remotePath: String
  ): IO[Either[String, (os.Path, Long)]] =
    transferGetBytes(peer, remotePath).flatMap {
      case Right(bytes) =>
        IO.blocking {
          val local = CapturePull.saveCapture(peer.deviceId, remotePath, bytes)
          Right((local, bytes.length.toLong)): Either[String, (os.Path, Long)]
        }
      case Left(err) => IO.pure(Left(err))
    }

  /**
   * 经既有 FileTransfer direction=get 通道取**字节**（不落盘；A1 批新增变体）。
   * dispatch（P2P → relay 回落）与解析闸（[[parseTransferBody]]）与
   * [[transferGetToFile]] **同一单点**——禁第二份实现。
   */
  private def transferGetBytes(
    peer: PeerInfo,
    remotePath: String
  ): IO[Either[String, Array[Byte]]] =
    val params = JsonObject(
      "direction" -> "get".asJson,
      "path" -> remotePath.asJson
    )

    p2pExecuteWithRetry(
      peer,
      "FileTransfer",
      params,
      60.seconds,
      "",
      P2pPathDecision.probeBudget(true),
      kind = "capture"
    )
      .flatMap {
        case Right(out) => IO.blocking(parseTransferBody(out))
        case Left(p2pErr) =>
          currentRelayClient.flatMap {
            case Some(client) =>
              relayExecAudited(client, peer, "FileTransfer", params, "", kind = "capture").flatMap {
                case Right(out) => IO.blocking(parseTransferBody(out))
                case Left(relayErr) =>
                  IO.pure(Left(s"p2p: ${p2pErr.message.take(80)}; relay: ${relayErr.take(80)}"))
              }
            case None => IO.pure(Left(s"p2p: ${p2pErr.message.take(120)} (no relay client)"))
          }
      }

  end transferGetBytes

  /**
   * `direction=get` 回执体解析（**唯一单点**，两条消费腿共用）：大小闸
   * [[CapturePull.MaxCaptureBytes]]（🔴 既有常量，零自造阈值）→ base64 解码。
   */
  private def parseTransferBody(output: String): Either[String, Array[Byte]] =
    decode[Json](output) match
      case Right(json) =>
        val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
        if size > CapturePull.MaxCaptureBytes then
          Left(s"capture too large: $size bytes > ${CapturePull.MaxCaptureBytes} limit")
        else
          val b64 = json.hcursor.downField("content").as[String].getOrElse("")
          if b64.isEmpty then Left("empty content")
          else Right(java.util.Base64.getDecoder.decode(b64))
      case Left(err) => Left(s"decode: ${err.getMessage}")

  // ---- v2.1 阶段 1：浏览面公开口（direction=list 元数据 + scope=get 单件） ----

  /**
   * 浏览面**元数据目录列举**（§C.1-3 无感面②）：对端 `FileTransfer direction=list,
   * scope=browse`——只拿元数据（name/type/size/mtime/broken），**零字节流动**。
   * 消费方 = 项目创建面的远端目录校验（G.13 序①）+ WS 文件浏览器 `device` 腿。
   * dispatch 与既有腿同族（P2P 优先 → relay 回落）。
   */
  def transferListScoped(peer: PeerInfo, dirPath: String): IO[Either[String, Json]] =
    val params = JsonObject(
      "direction" -> "list".asJson,
      "path" -> dirPath.asJson,
      "scope" -> "browse".asJson
    )
    dispatchBrowse(peer, "FileTransfer", params).flatMap {
      case Right(out) => IO.blocking(decode[Json](out).left.map(err => s"decode: ${err.getMessage}"))
      case Left(err) => IO.pure(Left(err))
    }

  /**
   * 浏览面**单件取件**（§C.2「看哪个下哪个」）：对端 `FileTransfer direction=get,
   * scope=browse`——出口白名单在对端闸（EgressWhitelist），大小闸在本侧
   * （[[MaxScopedFetchBytes]]，超限显式报错引导显式批量/dropbox 通道）。
   * 返回**字节**（落盘归调用方——pickups 单点）。
   */
  def transferGetScoped(peer: PeerInfo, remotePath: String): IO[Either[String, Array[Byte]]] =
    val params = JsonObject(
      "direction" -> "get".asJson,
      "path" -> remotePath.asJson,
      "scope" -> "browse".asJson
    )
    dispatchBrowse(peer, "FileTransfer", params).flatMap {
      case Right(out) =>
        IO.blocking {
          decode[Json](out) match
            case Right(json) =>
              val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
              if size > MaxScopedFetchBytes then
                Left(s"file too large for single fetch: $size bytes > $MaxScopedFetchBytes limit — use explicit batch transfer (dropbox) instead")
              else
                val b64 = json.hcursor.downField("content").as[String].getOrElse("")
                if b64.isEmpty then Left("empty content")
                else Right(java.util.Base64.getDecoder.decode(b64))
            case Left(err) => Left(s"decode: ${err.getMessage}")
        }
      case Left(err) => IO.pure(Left(err))
    }

  /**
   * 浏览面 dispatch 单点：P2P 优先 → relay 回落（与 [[transferGetBytes]] 同族判定，
   * 拒绝与既有 capture 腿共用同一 dispatch 核心语义；浏览面独用本入口——禁旁路）。
   */
  private def dispatchBrowse(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject
  ): IO[Either[String, String]] =
    p2pExecuteWithRetry(
      peer,
      toolName,
      params,
      60.seconds,
      "",
      P2pPathDecision.probeBudget(true),
      kind = "browse"
    ).flatMap {
      case Right(out) => IO.pure(Right(out))
      case Left(p2pErr) =>
        currentRelayClient.flatMap {
          case Some(client) =>
            relayExecAudited(client, peer, toolName, params, "", kind = "browse")
          case None => IO.pure(Left(s"p2p: ${p2pErr.message.take(120)} (no relay client)"))
        }
    }

  /** 浏览面单件大小闸（dropbox 大件通道引导线之上的硬闸；与 10MiB capture 闸分档）。
    * M2 起真源在伴生对象（BrowseGetLoop 的首响应闸与实例腿同参一处，禁两处漂移）。 */
  def MaxScopedFetchBytes: Long = RemoteExecutor.MaxScopedFetchBytes

  // ---- M2（2026-10-20）：浏览取件分块拉取腿（进度/取消/续传/逐块校验） ----

  final case class ScopedFetchResult(path: os.Path, size: Long, wholeSha256: String)

  /**
   * 浏览面**单件取件 · 分块拉取腿**（设计 v1 §7 M2-1）：改前 `transferGetScoped` 单请求
   * 整件 base64（无分块/无进度/无断点/无整件校验）；本方法改为 [[BrowseGetLoop]] 驱动的
   * 逐块拉取——4MiB 级块 + 逐块自算摘要校验 + 落盘断点续传（`PointerPickup` 落点幂等，
    * 重试从续传点继续）+ 整件 sha256 收口 + 每块进度回调 + 块间取消旗标。
   *
   * 大小闸不变（[[MaxScopedFetchBytes]]，首响应的整件读数把门）——超限显式报错引导
   * dropbox 通道，行为与改前同判。旧对端（无分块键响应）⇒ legacy 整件消费，零回归。
   *
   * 取消账：`BrowseFetchRegistry.register` 会话周期登记（guarantee 注销）；WS
   * `fileTransferCancel` 经 [[cancelScopedFetch]] 翻旗标。取消返回
   * `Left(BrowseGetCursor.Cancelled)`（区别于传输失败的终态哨兵）。
   *
   * @param onProgress (bytesReceived, totalBytes, lastChunkMs)——每块落盘后回调（权威
   *   读数 = 落盘长度，禁假进度）；`lastChunkMs` = 刚落那块的往返耗时（前端停滞阈依据）。
   */
  def transferGetScopedChunked(
    peer: PeerInfo,
    remotePath: String,
    onProgress: (Long, Long, Long) => IO[Unit]
  ): IO[Either[String, ScopedFetchResult]] =
    val target = PointerPickup.pickupPath(peer.deviceId, remotePath)
    val (flag, unregister) = BrowseFetchRegistry.register(peer.deviceId, remotePath)
    def fetchChunk(index: Int, chunkSize: Int, wantWholeSha: Boolean): IO[Either[String, BrowseGetCursor.ChunkReply]] =
      val params = JsonObject(
        "direction" -> "get".asJson,
        "path" -> remotePath.asJson,
        "scope" -> "browse".asJson,
        "chunkIndex" -> index.asJson,
        "chunkSize" -> chunkSize.asJson,
        "wantWholeSha256" -> wantWholeSha.asJson
      )
      dispatchBrowse(peer, "FileTransfer", params).flatMap {
        case Right(out) =>
          IO.blocking(decode[Json](out) match
            case Right(json) => BrowseGetCursor.parseReply(json)
            case Left(err)   => Left(s"decode: ${err.getMessage}")
          )
        case Left(err) => IO.pure(Left(err))
      }
    val progress: (Long, Long, Long) => IO[Unit] = (bytes, total, ms) =>
      if flag.get then IO.unit // 已叫停：不再外发进度帧（终态帧随后）
      else onProgress(bytes, total, ms)
    BrowseGetLoop
      .run(target, fetchChunk, progress, flag)
      .guarantee(unregister)
      .map {
        case Right((path, size, sha)) => Right(ScopedFetchResult(path, size, sha))
        case Left(err)                => Left(err)
      }

  end transferGetScopedChunked

  /**
   * 远端**单文件 Write**（v2.1 §A.5 远端创建脚手架腿的唯一落笔口）：走既有
   * remote-exec Write 工具通道（对端 createFolders 语义幂等建目录）；projectRoot
   * = 目标父目录（对端侧路径基座）。与工具级 remote-exec 六件的原语义同一通道，
   * 只是把「模型显式调用」换成「创建面的机械落笔」——零新通道。
   */
  def writeRemoteFileForScaffold(
    peer: PeerInfo,
    remotePath: String,
    content: String
  ): IO[Either[String, String]] =
    val parent = remotePath.replaceAll("""[/\\]+""", "/").split("/").dropRight(1).mkString("/")
    // 参数键 = Write 工具 schema（file_path/content，WriteTool.scala）。
    val params = JsonObject(
      "file_path" -> remotePath.asJson,
      "content" -> content.asJson
    )
    executeViaBestPath(peer, "Write", params, 60.seconds, parent, kind = "scaffold").map {
      case Right(out) => Right(out)
      case Left(err) => Left(err.message)
    }

  // ---- A1：设备腿读图的视觉块回填（调用侧，2026-09-16 作者裁定） ----

  /**
   * 设备腿 `Read` 的视觉块回填（A1 的 IO 编排面）。**唯一调用点** = `AgentCore` 的
   * 远端分支（与本地分支 `AgentCore.scala:1763` 取块 / `:1774` 置块的形态对齐）。
   *
   * 判据链（任一不成立 ⇒ `None`，**保留原文本标记、零抛错、零中断**）：
   *   ① 工具必须是 `Read`（其它工具零动作）；
   *   ② 结果必须是 `[image: …]` 标记（否则**零动作**——纯文本结果不发回拉）；
   *   ③ `params.file_path`（远端绝对路径）必须非空；
   *   ④ 对端可解析；⑤ 既有 `FileTransfer direction=get` 取字节成功；
   *   ⑥ 字节能构块（mime 可解析 + 未超既有上限）。
   *
   * 🔴 降级纪律（硬）：回拉失败（对端不可达 / 解码失败 / 非图片 mime / 超限）⇒
   * `None` + 一条 WARN（可 grep `[device-image]`）——**不得**让工具调用整体失败、
   * **不得**吞掉原输出（与本地腿 `ReadTool.extractImages:243` catch 兜底同口径）。
   * `handleErrorWith` 是兜底保险：本方法**永不**把异常抛给调用方。
   */
  def remoteReadImages(
    deviceName: String,
    toolName: String,
    params: JsonObject,
    result: String
  ): IO[Option[List[ContentBlock.Image]]] =
    if toolName != "Read" then IO.pure(None)
    else
      RemoteImage.parseMarker(result) match
        case None => IO.pure(None)
        case Some(marker) =>
          params("file_path").flatMap(_.asString).map(_.trim).filter(_.nonEmpty) match
            case None =>
              logger.warn(
                s"[device-image] ${marker.fileName}: result carried the image marker but the call had no file_path — no pull attempted (text marker kept)"
              )
              IO.pure(None)
            case Some(remotePath) =>
              (for
                peerOpt <- resolvePeerByName(deviceName)
                out <- peerOpt match
                  case None =>
                    logger
                      .warn(
                        s"[device-image] $deviceName not resolvable — pull of $remotePath skipped (text marker kept)"
                      )
                      .as(None)
                  case Some(peer) =>
                    transferGetBytes(peer, remotePath).map {
                      case Right(bytes) => RemoteImage.buildBlocks(bytes, marker.fileName)
                      case Left(err) =>
                        logger.warn(
                          s"[device-image] pull failed for ${peer.deviceName}:$remotePath (${err.take(120)}, declared ${marker.declaredMime}) — text marker kept, no vision block"
                        )
                        None
                    }
              yield out).handleErrorWith { e =>
                logger
                  .warn(
                    s"[device-image] ${marker.fileName}: pull crashed (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}) — text marker kept, no vision block"
                  )
                  .as(None)
              }

  /**
   * 按设备名解析 peer（与 `execute` 同一先例：名册可能陈旧 ⇒ 一次即时扫描重试）。
   * 解析不到 ⇒ None（调用方降级，不报错——回拉本就是尽力而为的附加面）。
   */
  private def resolvePeerByName(deviceName: String): IO[Option[PeerInfo]] =
    neblinkService.peers.flatMap { peers =>
      RemoteExecutor.resolvePeer(deviceName, peers) match
        case Right(peer) => IO.pure(Some(peer))
        case Left(_) =>
          neblinkService.scanNow.map(refreshed => RemoteExecutor.resolvePeer(deviceName, refreshed).toOption)
    }

  /**
   * 按 **deviceId** 解析 peer（跨设备项目挂载批 2026-10-25 新公开口）：项目
   * `origin.device` 存的是创建面写入的稳定 deviceId（非显示名），引擎本机写面
   * （agent.md 推回等）按 id 直查；名册可能陈旧 ⇒ 一次即时扫描重试（与
   * [[resolvePeerByName]] 同先例）。解析不到 ⇒ None（调用方降级，不报错）。
   */
  def peerById(deviceId: String): IO[Option[PeerInfo]] =
    neblinkService.peers.flatMap { peers =>
      peers.find(_.deviceId == deviceId) match
        case Some(p) => IO.pure(Some(p))
        case None => neblinkService.scanNow.map(_.find(_.deviceId == deviceId))
    }

  // ---- Remote background task: Mac manages lifecycle locally ----

  /**
   * When the LLM requests a remote background task, Mac handles the lifecycle:
   * 1. Strip `run_in_background` so the remote device executes synchronously
   * 2. Emit "running" indicator to frontend
   * 3. Start a detached fiber that does the synchronous HTTP call to the remote device
   * 4. Return "[Background job started]" to the LLM immediately
   * 5. When the HTTP call returns, notify agent (ExternalEvent) + frontend (WS)
   *
   * 多设备后台任务可见批（作者令 2026-10-25「多设备的后台任务我们也要可见」）：
   * 设备通道后台任务进统一账本（BgTaskRegistry）——① origin 类别 = 设备名
   * （[[BgTaskRegistry.deviceOrigin]]，「跑在哪」轴），注册与 WS 发射面同源；
   * ② `persistent=true`（服务型长驻，设备上的 dev server / watcher）同本地腿
   * 语义——不纳入节点等待集/热重启静默闸/bg-wait 屏障（三处消费面同判
   * ActiveTask.persistent），且 HTTP 等待豁免 1h 网络兜底
   * （[[RemoteExecutor.bgHttpWaitTimeout]]，对端 timeout 参数同值同源，防对端
   * 先杀）；③ `persistent` 是**本侧**生命周期旗标，与 `run_in_background` 同法
   * 在下发前摘除（[[RemoteExecutor.stripLocalBgFlags]]），不过线。
   */
  private def executeRemoteBackground(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val persistent = RemoteExecutor.parsePersistent(params)
    val waitTimeout = RemoteExecutor.bgHttpWaitTimeout(persistent)
    val remoteParams = ensureRemoteTimeout(RemoteExecutor.stripLocalBgFlags(params), waitTimeout)
    val commandStr = params("command").flatMap(_.asString).getOrElse(toolName)
    val firstLine = commandStr.split('\n').headOption.getOrElse(commandStr).take(80)
    val description = s"[${peer.deviceName}] ${params("description").flatMap(_.asString).getOrElse(firstLine)}"
    // 输出查看补全批（作者令 2026-10-25「远端任务看不到」）：从命令文本解析对端
    // 日志路径（LLM 惯例把远端长任务输出重定向到日志，实证 test5 的
    // `/c/Users/Kai/stand-win-test5.log`）——解析不到 = 空（面板降级注明原因，
    // 不猜路径）。startedAtMs 一并捕获（fillRemote 落账 origin 用）。
    val bgOutputPath = RemoteBgOutputLog.parseRedirect(commandStr).getOrElse("")
    val bgStartedAtMs = System.currentTimeMillis()

    // 来源标注（多设备后台任务可见批）：设备通道后台任务的 origin 恒为设备名
    // （deviceOrigin 单点）——不再借用注册会话的 nebula/dispatcher/node 类别
    // （那是「谁开启」的轴，设备任务的 chip 要回答的是「跑在哪」）。
    val (bgOrigin, bgOriginLabel) = BgTaskRegistry.deviceOrigin(peer.deviceName)
    for
      // 终态延迟销毁窗口的禁 spawn 守卫（noderpt 批 B 段 2026-09-11 作者裁定）：远程后台
      // 不经本地 ShellSession（无 executeBackground），故在**注册/发射之前**独立设点——
      // 拒绝时零 WS 帧、零 registry 项、零 HTTP fiber（真拦截，不是事后注销）。
      _ <- BgTaskRegistry.denySpawnIfFinalized(ctx.sessionId.getOrElse(""))
      jobId <- IO.randomUUID.map(_.toString.take(8))
      _ <- logger.info(
          s"Remote background task $jobId started on ${peer.deviceName}: $firstLine" +
            (if bgOutputPath.nonEmpty then s" (log: $bgOutputPath)" else "")
        )
      // 1. Emit "running" to frontend + register in global registry
      _ <- emitBgTaskStarted(ctx, jobId, description, bgOrigin, bgOriginLabel)
      _ <- BgTaskRegistry.register(
        jobId,
        ctx.sessionId.getOrElse(""),
        description,
        "remote",
        ctx.rootSessionId.orElse(ctx.sessionId).getOrElse(""),
        persistent,
        bgOrigin,
        bgOriginLabel,
        peer.deviceName,
        bgOutputPath
      )
      // 2. Start heartbeat so frontend shows progress (remote tasks have no process-level health)
      doneRef <- IO.ref(false)
      _ <- startRemoteHeartbeat(ctx, jobId, description, doneRef)
      // 3. Start detached fiber — synchronous HTTP to the remote device, then notify
      //    （等待上限随 persistent 豁免：服务型设备任务不被 1h 网络兜底杀。）
      _ <- IO(startRemoteBgFiber(peer, toolName, remoteParams, ctx, jobId, description, doneRef, waitTimeout, bgOutputPath, bgStartedAtMs))
    yield Right(
      s"[Background job started] Job ID: $jobId\nThe command is running in the background on ${peer.deviceName}. You will be automatically notified when it finishes — continue with other work or finish your turn."
    )
    end for

  end executeRemoteBackground

  /**
   * Execute a remote command in the foreground until it completes.
   *
   * #26（2026-08-30 用户裁定「Bash 工具不再自动转后台」）：删除 30s 自动转后台
   * ——远程前台调用一直等到 HTTP 返回（或 BgTimeout 网络超时兜底），返回真实
   * 输出，不产生「[Remote command moved to background]」占位。
   *
   * 远程执行无本地进程树、无进程级进展探测（HTTP 等待中无法区分「命令在跑」
   * 与「设备无响应」），因此：
   * - 进程侧活动心跳（每 30s touch **processActivityMs**，2026-09-10 换轴前写的是
   *   lastActivityMs）——「对端还在等」的旁证；**不再**参与卡死判据，远程长命令
   *   的看护改由工具相位判据承担（单个工具调用超 ToolPhaseStuckMs 且 turn 未完成
   *   → TaskStuckWatcher 分级恢复）
   * - 真挂起（设备无响应）由 BgTimeout（3600s 网络超时）兜底——远程固有局限：
   *   无法像本地 Bash 一样用输出/CPU 停滞检测，网络层超时是唯一防线
   */
  private def executeForegroundSync(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val remoteParams = ensureRemoteTimeout(RemoteExecutor.stripLocalBgFlags(params))
    val commandStr = params("command").flatMap(_.asString).getOrElse(toolName)
    val firstLine = commandStr.split('\n').headOption.getOrElse(commandStr).take(80)
    val description = s"[${peer.deviceName}] ${params("description").flatMap(_.asString).getOrElse(firstLine)}"

    for
      // 进程侧活动心跳（2026-09-10 换轴）：等待期间每 30s 刷新
      // **processActivityMs**（不再是 lastActivityMs——远程等待中的「进程/网络
      // 还活着」不等于 agent 侧有进展；旧写点与 BashTool 活动桥同属事故根因族）。
      // 本侧旗标（run_in_background/persistent）与后台腿同法摘除，不过线。
      hbFiber <- (IO.sleep(30.seconds) *> touchAgentActivity(ctx)).foreverM.start
      r <- executeViaBestPath(peer, toolName, remoteParams, RemoteExecutor.BgTimeout, ctx.projectRoot)
      _ <- hbFiber.cancel
    yield r
  end executeForegroundSync

  /**
   * 刷新 registry 的 **进程侧** 活动戳（远程等待期间保持「对端还在跑」的旁证）。
   * 2026-09-10 换轴：目标字段 = processActivityMs——本戳不参与卡死判据。
   */
  private def touchAgentActivity(ctx: ToolContext): IO[Unit] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) =>
        val now = System.currentTimeMillis()
        res.agentRegistry.modify { m =>
          m.get(sid) match
            case Some(rec) => (m.updated(sid, rec.copy(processActivityMs = now)), ())
            case None => (m, ())
        }
      case _ => IO.unit

  /** Launch the HTTP call in a detached fiber; notify agent + frontend when done.
    * `waitTimeout` = 本任务的 HTTP 等待上限（bgHttpWaitTimeout 判据：等待型 =
    * BgTimeout 1h 网络兜底；persistent 服务型 = 豁免长档）。 */
  private def startRemoteBgFiber(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    ctx: ToolContext,
    jobId: String,
    description: String,
    doneRef: Ref[IO, Boolean],
    waitTimeout: FiniteDuration,
    outputPath: String = "",
    startedAtMs: Long = 0L
  ): Unit =
    val completionIO =
      executeViaBestPath(peer, toolName, params, waitTimeout, ctx.projectRoot)
        .flatMap {
          case Right(output) => notifyRemoteBgResult(ctx, jobId, description, Right(output), peer.deviceName, outputPath, startedAtMs)
          case Left(err) => notifyRemoteBgResult(ctx, jobId, description, Left(err.message), peer.deviceName, outputPath, startedAtMs)
        }
        .flatMap(_ => doneRef.set(true))

    dispatcher.unsafeRunAndForget(
      completionIO.handleErrorWith(e =>
        logger.warn(s"Remote background fiber $jobId crashed: ${e.getMessage}") *>
          emitBgTaskFinished(ctx, jobId, description, "failed") *>
          doneRef.set(true)
      )
    )
  end startRemoteBgFiber

  // ---- WS helpers (match BashTool's backgroundTaskUpdate format) ----

  /**
   * Periodic heartbeat for remote background tasks. Unlike local Bash tasks
   * which have process-level health (alive, output lines), remote tasks only
   * know the HTTP call is still pending. We send runningMs so the frontend
   * duration timer and status dot work correctly.
   */
  private def startRemoteHeartbeat(
    ctx: ToolContext,
    jobId: String,
    description: String,
    doneRef: Ref[IO, Boolean]
  ): IO[Unit] =
    val baseSec = nebflow.shared.Defaults.BgHeartbeatIntervalSec
    val startedAtMs = System.currentTimeMillis()
    def loop: IO[Unit] =
      doneRef.get.flatMap {
        case true => IO.unit
        case false =>
          val now = System.currentTimeMillis()
          val runningMs = now - startedAtMs
          ctx.wsSend.fold(IO.unit) { send =>
            send(
              io.circe.Json.obj(
                "type" -> "backgroundTaskUpdate".asJson,
                "sessionId" -> ctx.sessionId.asJson,
                "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
                "taskId" -> jobId.asJson,
                "description" -> description.asJson,
                "status" -> "running".asJson,
                "heartbeat" -> io.circe.Json.obj(
                  "alive" -> true.asJson,
                  "outputLines" -> 0.asJson,
                  "idleMs" -> runningMs.asJson,
                  "runningMs" -> runningMs.asJson
                )
              )
            ).handleErrorWith(_ => IO.unit)
          } *> IO.sleep(baseSec.seconds) *> loop
      }
    loop.start.void
  end startRemoteHeartbeat

  private def emitBgTaskStarted(
    ctx: ToolContext,
    jobId: String,
    description: String,
    origin: String,
    originLabel: String
  ): IO[Unit] =
    // 来源字段（多设备后台任务可见批）：由注册面（executeRemoteBackground）传入
    // ——设备任务 = ("device", 设备名)，与 BgTaskRegistry.register 同源同值；
    // 快照路径（activeTasksJson）与实时信封两条路径的 chip 数据不再分叉。
    ctx.wsSend.fold(
      logger.debug(s"Cannot notify frontend for remote background job $jobId: no wsSend")
    )(send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> "running".asJson,
          "startedAt" -> System.currentTimeMillis().asJson,
          "kind" -> "remote".asJson,
          "origin" -> origin.asJson,
          "originLabel" -> originLabel.asJson
        )
      ).handleErrorWith(e => logger.warn(s"WS send failed for remote job $jobId: ${e.getMessage}"))
    )

  end emitBgTaskStarted

  private def emitBgTaskFinished(
    ctx: ToolContext,
    jobId: String,
    description: String,
    status: String,
    exitCode: Option[Int] = None,
    durationMs: Option[Long] = None
  ): IO[Unit] =
    ctx.wsSend.fold(IO.unit)(send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> status.asJson,
          // 通知详情批（2026-10-24）：终态帧只增键（本地腿同款）——面板行 exit
          // chip 与冻结时长；远程无进程退出码 ⇒ 恒 null，前端按缺读处理。
          "exitCode" -> exitCode.asJson,
          "durationMs" -> durationMs.asJson,
          "finishedAt" -> System.currentTimeMillis().asJson
        )
      ).handleErrorWith(_ => IO.unit)
    )

  /**
   * Notify agent (ExternalEvent) + frontend (WS) of a background task result.
   *
   * 输出查看补全批（作者令 2026-10-25）：终态时先把 HTTP 拿到的全量输出灌进
   * BgTaskOutputStore（[[BgTaskOutputStore.fillRemote]]——open + append +
   * finalize 同一时序），**再** unregister + 发 WS completed 帧 ⇒ 前端详情卡
   * 对 remote 任务的终态回看首拍即命中留存（不再是「暂不支持」/404）。exit
   * 码只认对端回执末尾的 `(exit N)` 标记（解析不到不虚报）。
   */
  private def notifyRemoteBgResult(
    ctx: ToolContext,
    jobId: String,
    description: String,
    result: Either[String, String],
    deviceName: String = "",
    outputPath: String = "",
    startedAtMs: Long = 0L
  ): IO[Unit] =
    // 通知详情批（作者报障 2026-10-24「后台任务通知很不详细甚至有截断」）：远程腿
    // 与本地腿同一负载单点（BgNotifyPayload）——耗时（输出查看批传入的 startedAtMs
    // → 终态墙钟差；缺席=无耗时段）+ 输出/错误尾段 + 零输出占位。远程退出码只认
    // 对端回执末尾的 `(exit N)` 标记（输出查看补全批 parseExitMarker；解析不到不
    // 虚报 ⇒ None——耗时段仍在）。存储灌装（fillRemote，输出查看批）照旧先于投递。
    val finishedAtMs = System.currentTimeMillis()
    val durationMs = if startedAtMs > 0L then Some((finishedAtMs - startedAtMs).max(0L)) else None
    val fillIo = result match
      case Right(output) =>
        BgTaskOutputStore.fillRemote(
          jobId,
          output,
          "completed",
          RemoteBgOutputLog.parseExitMarker(output),
          None,
          BgTaskOutputStore.Origin("remote", deviceName, outputPath, description, startedAtMs)
        )
      case Left(errMsg) =>
        BgTaskOutputStore.fillRemote(
          jobId,
          "",
          "failed",
          None,
          Some(errMsg),
          BgTaskOutputStore.Origin("remote", deviceName, outputPath, description, startedAtMs)
        )
    fillIo
      .handleErrorWith(e => logger.warn(s"Remote bg fill store failed for $jobId: ${e.getMessage}"))
      .guarantee(BgTaskRegistry.unregister(jobId))
      .flatMap { _ =>
        result match
          case Right(output) =>
            val exitCode = RemoteBgOutputLog.parseExitMarker(output)
            val built = BgNotifyPayload.completed(description, exitCode, durationMs, output, "", jobId)
            // 完成事件持久账本（修复批 2026-10-24「先落账本再投递」）：与 BashTool
            // 本地腿同语义——事件先落账再投递，投递失联由补投腿兜底，永不蒸发。
            // fail-soft：账本故障绝不阻断投递。
            BgTaskLedger
              .recordCompletion(ctx.sessionId.getOrElse(""), jobId, description, "completed", built.payload)
              .handleErrorWith(e => logger.warn(s"bg-ledger record failed for remote job $jobId: ${e.getMessage}"))
              .flatMap { _ =>
                ctx.agentActorRef.fold(IO.unit)(ref =>
                  ref ! AgentCommand.ExternalEvent(
                    source = "background-task",
                    eventType = "completed",
                    payload = built.payload,
                    metadata = built.metadata
                  )
                ) *>
                  emitBgTaskFinished(ctx, jobId, description, "completed", exitCode, durationMs) *>
                  logger.info(s"Remote background task $jobId completed${logTagSuffix(built.logTag)}")
              }
          case Left(errMsg) =>
            val built = BgNotifyPayload.failed(description, errMsg, durationMs, jobId)
            BgTaskLedger
              .recordCompletion(ctx.sessionId.getOrElse(""), jobId, description, "failed", built.payload)
              .handleErrorWith(e => logger.warn(s"bg-ledger record failed for remote job $jobId: ${e.getMessage}"))
              .flatMap { _ =>
                ctx.agentActorRef.fold(IO.unit)(ref =>
                  ref ! AgentCommand.ExternalEvent(
                    source = "background-task",
                    eventType = "failed",
                    payload = built.payload,
                    metadata = built.metadata
                  )
                ) *>
                  emitBgTaskFinished(ctx, jobId, description, "failed", None, durationMs) *>
                  logger.warn(s"Remote background task $jobId failed: $errMsg${logTagSuffix(built.logTag)}")
              }
      }

  end notifyRemoteBgResult

  private def logTagSuffix(tag: String): String =
    if tag.isEmpty then "" else s" ($tag)"

  /**
   * 对端日志尾窗拉取（输出查看补全批：详情面板 remote-output 路由的设备通道面）。
   *
   * 走**既有**通道面——对端 Bash 工具执行 `tail -c N`（[[RemoteBgOutputLog.tailCommand]]
   * 构造，路径单引号包裹），P2P/relay dispatch 与远端命令执行同一族
   * （executeViaBestPath），零新 wire、零新权限口子。短超时（[[RemoteBgOutputLog.TailRemoteTimeoutMs]]）
   * ——设备无响应快速失败，不占用 1h 网络兜底档。
   *
   * Left = 可读原因（executor 未初始化 / 设备解析不到 / 执行失败 / tail 非零退出），
   * 面板原样降级展示；Right = 尾窗文本（对端 Bash 回执原文，含 formatResult 的
   * `(cwd: …)` 头——展示面如实呈现）。
   */
  def readBgLogTail(deviceName: String, remotePath: String): IO[Either[String, String]] =
    resolvePeerByName(deviceName).flatMap {
      case None => IO.pure(Left(s"device '$deviceName' not reachable (offline or unknown)"))
      case Some(peer) =>
        val cmd = RemoteBgOutputLog.tailCommand(remotePath, RemoteBgOutputLog.TailMaxBytes)
        val params = JsonObject(
          "command" -> cmd.asJson,
          "description" -> "bg-log-tail".asJson,
          "timeout" -> RemoteBgOutputLog.TailRemoteTimeoutMs.asJson
        )
        executeViaBestPath(
          peer,
          "Bash",
          params,
          RemoteBgOutputLog.TailRemoteTimeoutMs.millis + 5.seconds,
          "",
          kind = "bglog"
        ).map {
          case Right(out) => Right(out)
          case Left(err) => Left(err.message.take(200))
        }
    }

  // ---- P2P Direct ----

  /**
   * Candidate endpoints for a P2P dispatch (C1). `peer.endpoints` is already
   * preference-ordered (Tailscale → 同网段 → 其余, `EndpointPreference`); peers
   * built without a server namelist (`endpoints = Nil`) fall back to the single
   * `address`, i.e. pre-C1 behaviour.
   */
  private def p2pCandidates(peer: PeerInfo): List[String] =
    val all = if peer.endpoints.nonEmpty then peer.endpoints else List(peer.address)
    all.filter(_.nonEmpty).distinct

  private def p2pExecute(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    budget: P2pPathDecision.ProbeBudget
  ): IO[Either[ToolError, String]] =
    for
      selfDeviceId <- neblinkService.identity.map(_.deviceId)
      // C1: 每个候选短超时串行短路，首个成功者即本次直连端点。
      result <- p2pExecuteCandidates(p2pCandidates(peer), peer, selfDeviceId, toolName, params, timeout, budget)
    yield result

  /**
   * Walk the candidate list, short-circuiting on the first success (C1 目标口径
   * §3.3). Only a *connection-level* failure moves on to the next candidate —
   * an HTTP 403/5xx proves the endpoint is up, so hopping would just burn the
   * budget on a peer that is answering.
   */
  private def p2pExecuteCandidates(
    candidates: List[String],
    peer: PeerInfo,
    selfDeviceId: String,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    budget: P2pPathDecision.ProbeBudget
  ): IO[Either[ToolError, String]] =
    candidates match
      case Nil =>
        IO.pure(Left(ToolError(s"Cannot reach ${peer.deviceName}: no usable endpoint address")))
      case endpoint :: rest =>
        p2pExecuteAt(peer, endpoint, selfDeviceId, toolName, params, timeout, budget).flatMap {
          case r @ Right(_) => IO.pure(r)
          case Left(err) if rest.nonEmpty && isTransientError(err) =>
            p2pExecuteCandidates(rest, peer, selfDeviceId, toolName, params, timeout, budget)
          case l => IO.pure(l)
        }

  /** One P2P HTTP dispatch against one concrete endpoint. */
  private def p2pExecuteAt(
    peer: PeerInfo,
    endpoint: String,
    selfDeviceId: String,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    budget: P2pPathDecision.ProbeBudget
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val apiPath = "/api/neblink/remote-exec"
      val bodyStr = io.circe.Json.obj(
        "action" -> toolName.asJson,
        "params" -> params.asJson
      ).noSpaces
      // v2.1 §A.4（2026-10-09 实施批）：配对设备对设备面请求**恒附** HMAC 头
      // （发送侧无条件签名；收端是否 enforce 由其 deviceAuth 档自主——双轨灰度的
      // 两端解耦）。未配对（devices.json 无本对端键）= 头集与改前逐字节相同，
      // 零回归。canonical = DeviceHmac 唯一拼装点，收端 verifyHeaders 同源。
      val hmacHeaders: List[sttp.model.Header] =
        nebflow.core.device.DeviceRegistry.secretForSync(peer.deviceId) match
          case Some(key) if key.nonEmpty =>
            val ts = (System.currentTimeMillis() / 1000).toString
            val nonce = nebflow.core.device.DeviceHmac.newNonce()
            val sig = nebflow.core.device.DeviceHmac.sign(
              key,
              nebflow.core.device.DeviceHmac.canonical(
                selfDeviceId,
                ts,
                nonce,
                "POST",
                apiPath,
                nebflow.core.device.DeviceHmac.bodySha256(bodyStr)
              )
            )
            List(
              sttp.model.Header(nebflow.core.device.DeviceHmac.HeaderTs, ts),
              sttp.model.Header(nebflow.core.device.DeviceHmac.HeaderNonce, nonce),
              sttp.model.Header(nebflow.core.device.DeviceHmac.HeaderSignature, sig)
            )
          case _ => Nil
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(s"$endpoint$apiPath"))
        .contentType("application/json")
        .header(nebflow.shared.DeviceHeader, selfDeviceId)
        .headers(hmacHeaders*)
        .body(bodyStr)
        .readTimeout(timeout)
        .response(asStringAlways)
        .send(backendFor(budget))

      if !resp.code.isSuccess then Left(ToolError(s"Remote device returned HTTP ${resp.code}: ${resp.body.take(200)}"))
      else
        decode[io.circe.Json](resp.body) match
          case Right(json) =>
            val output = json.hcursor.downField("output").as[String].getOrElse("")
            val error = json.hcursor.downField("error").as[String].getOrElse("")
            if error.nonEmpty then Left(ToolError(s"Remote error: $error"))
            else Right(output)
          case Left(err) =>
            Left(ToolError(s"Invalid response from remote: ${err.getMessage}"))
    }.handleErrorWith { e =>
      val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
      val lower = msg.toLowerCase
      if lower.contains("timeout") || lower.contains("timed out") then
        IO.pure(Left(ToolError(s"Command timed out on ${peer.deviceName} after ${timeout.toSeconds}s: $msg")))
      else IO.pure(Left(ToolError(s"Cannot reach ${peer.deviceName} at $endpoint: $msg")))
    }
  end p2pExecuteAt

  /**
   * Wraps p2pExecute with retry logic for transient network failures.
   * Retries up to `budget.maxRetries` times with 1s, 2s ... delays on connection
   * errors only. Does NOT retry on HTTP errors or remote tool execution errors —
   * those indicate the remote device is running but the request itself failed.
   *
   * A: the budget is the caller's (see [[P2pPathDecision.probeBudget]]) — a
   * `directOnline=false` dispatch probes once with a 1.5s connect timeout
   * instead of paying 4 attempts + backoff before the relay fallback.
   */
  private def p2pExecuteWithRetry(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    projectRoot: String,
    budget: P2pPathDecision.ProbeBudget,
    kind: String = ""
  ): IO[Either[ToolError, String]] =
    def attempt(n: Int): IO[Either[ToolError, String]] =
      p2pExecute(peer, toolName, params, timeout, budget).flatMap {
        case Right(result) => IO.pure(Right(result))
        case Left(err) if n < budget.maxRetries && isTransientError(err) =>
          val delay = (n + 1).seconds
          logger.info(s"Retrying ${peer.deviceName} in ${delay.toSeconds}s (attempt ${n + 1}/${budget.maxRetries})") *>
            IO.sleep(delay) *> attempt(n + 1)
        case Left(err) => IO.pure(Left(err))
      }
    // T4：一次逻辑下发的审计行（在第一次网络尝试前落——重试是同一行下发，
    // 不重复记行；下发本身失败也记，审计关心「试图驱动了哪台设备」）。
    auditDispatch(peer, toolName, params, projectRoot, "p2p", kind) *> attempt(0)

  end p2pExecuteWithRetry

  /**
   * Connection-level failures worth retrying (connection refused, DNS failure).
   * Does NOT match timeout errors — those mean the command is running but slow.
   * Does NOT match HTTP 403 — that is a trust rejection, not a transient
   * network blip, so retrying wastes time (trust state won't change in 1-2s).
   */
  private def isTransientError(err: ToolError): Boolean =
    val msg = err.message.toLowerCase
    msg.startsWith("cannot reach")

  /**
   * HTTP 403 "Not a trusted peer" rejection. The direct P2P path is blocked by
   * the remote's IP trust list (stale discovery, NIC filtering, or DHCP IP
   * change), but the relay authenticates against the NebLink Server with a
   * token — bypassing IP trust entirely — so this is relay-fallback eligible.
   */
  private def isTrustRejection(err: ToolError): Boolean =
    err.message.toLowerCase.contains("remote device returned http 403")

  /** Errors that justify a relay fallback (connection failure or trust rejection). */
  private def shouldRelayFallback(err: ToolError): Boolean =
    isTransientError(err) || isTrustRejection(err)

  /**
   * Best-path execution with performance optimizations:
   *
   * 1. **A — negative-evidence P2P bypass**: P2P is skipped **only** on real
   *    recent failure evidence (relay path memory, or a fresh P2P/dial failure),
   *    never merely because there is no presence WS. `directOnline` is demoted to
   *    a probe-budget hint ([[P2pPathDecision.probeBudget]]).
   * 2. **C4 — address-sensitive, re-probing path memory**: a memory is reusable
   *    only for the same address set and only within 60s, and relay successes do
   *    not slide the window — so P2P is re-probed at least once per TTL.
   * 3. **P1 Parallel race** (read-only tools only): send P2P and Relay concurrently,
   *    use whichever responds first. Safe because read-only tools have no side
   *    effects on cancellation.
   * 4. **Serial fallback** (write tools): P2P first, then Relay on failure.
   *
   * BUG 4 fix: relay fallback includes a cold-start retry (500ms delay + 2nd attempt)
   * to handle WS tunnel reconnection latency.
   */
  private def executeViaBestPath(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    projectRoot: String,
    kind: String = ""
  ): IO[Either[ToolError, String]] =
    // F1 sweep (2026-09-10 隧道鉴权自愈批): resolve the client per dispatch —
    // never dispatch through a constructor-time snapshot whose session the
    // server may have kicked.
    currentRelayClient.flatMap {
      case None =>
        // No relay available — P2P only. NOTE (A): the probe budget is
        // deliberately NOT downgraded here — `directOnline=false` does not mean
        // the P2P HTTP path is down (presence WS and remote-exec HTTP are
        // different transports), and with no relay there is nothing to fall
        // back to, so retries remain this path's only recovery.
        p2pExecuteWithRetry(peer, toolName, params, timeout, projectRoot, P2pPathDecision.probeBudget(true), kind)
          .flatMap {
            case r @ Right(_) => recordPathMemory(peer, "p2p").as(r)
            case l @ Left(_) => IO.pure(l)
          }

      case Some(client) =>
        val relayAvailable = neblinkService.relayTunnelOpt.exists(_.isAlive)
        // Presence-based signal — no longer a decision basis, only a budget hint.
        val directOnline = neblinkService.presenceServiceOpt.exists(_.isConnected(peer.deviceId))
        // A: 负证据驱动 —— 只有「有近期真实 P2P 失败证据」才旁路，否则尝试 P2P。
        val memoryOpt = getPathMemory(peer)
        val recentP2pFailure = hasRecentP2pFailure(peer.deviceId)
        val skipP2p = P2pPathDecision.shouldSkipP2pForRelay(memoryOpt, recentP2pFailure, relayAvailable)
        val budget = P2pPathDecision.probeBudget(directOnline)

        for result <-
            if skipP2p then
              // Relay only — with cold-start retry for tunnel reconnection latency
              relayWithColdStartRetry(client, peer, toolName, params, projectRoot, kind).flatMap {
                case Right(output) => recordPathMemory(peer, "relay").as(Right(output))
                case Left(err) => clearPathMemory(peer.deviceId).as(Left(ToolError(s"Relay failed: $err")))
              }
            else if ReadOnlyTools.contains(toolName) then
              // P1: parallel race for read-only tools (safe cancellation)
              raceP2PAndRelay(peer, toolName, params, timeout, client, projectRoot, budget, kind)
            else
              // Write tools: serial P2P → relay fallback, negative cache as兜底
              p2pExecuteWithRetry(peer, toolName, params, timeout, projectRoot, budget, kind).flatMap {
                case Right(output) => recordPathMemory(peer, "p2p").as(Right(output))
                case Left(err) if shouldRelayFallback(err) =>
                  // A: this failure IS the evidence that justifies bypassing P2P
                  // (and, because relay renewals do not slide, it can expire).
                  recordP2pFailure(peer) *>
                    logger.info(
                      s"P2P unavailable for ${peer.deviceName} (${err.message.take(80)}), falling back to relay"
                    ) *>
                    relayWithColdStartRetry(client, peer, toolName, params, projectRoot, kind).flatMap {
                      case Right(output) => recordPathMemory(peer, "relay").as(Right(output))
                      case Left(relayErr) =>
                        IO.pure(Left(ToolError(s"P2P failed: ${err.message}; Relay also failed: $relayErr")))
                    }
                case Left(err) => IO.pure(Left(err))
              }
        yield result
    }
  end executeViaBestPath

  // ---- T4 (2026-09-11): relay/P2P 远端执行审计 ----

  /**
   * 落一条远端执行审计行（见 [[RelayExecAudit]]）。**每次逻辑下发一次**，在首次
   * 网络尝试前调用（重试不重复记行）。审计失败绝不影响下发：[[RelayExecAudit.record]]
   * 自身吞异常 + WARN，这里再兜一层（取本机身份失败时也不能挡住下发）。
   *
   * 与 dangerLevel 无关——auto-all 下权限卡不出现，正是这一行承担可见性。
   */
  private def auditDispatch(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    projectRoot: String,
    via: String,
    kind: String = ""
  ): IO[Unit] =
    neblinkService.identity
      .map(_.deviceId)
      .flatMap(src =>
        RelayExecAudit.record(
          sourceDeviceId = src,
          targetDeviceId = peer.deviceId,
          via = via,
          action = toolName,
          command = RelayExecAudit.summarizeParams(toolName, params),
          projectRoot = projectRoot,
          cwd = Option(System.getProperty("user.dir")).getOrElse(""),
          kind = kind
        )
      )
      .handleErrorWith(e => logger.warn(s"relay-exec audit line skipped: ${e.getMessage}"))

  /** relay 下发 + 审计（唯一 relay 出口——避免某条分支漏记）。 */
  private def relayExecAudited(
    client: NeblinkClientPort,
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    projectRoot: String,
    kind: String = ""
  ): IO[Either[String, String]] =
    auditDispatch(peer, toolName, params, projectRoot, "relay", kind) *>
      client.relayExec(peer.deviceId, toolName, params)

  // ---- BUG 4: Relay cold-start retry ----

  /**
   * Relay execution with a single cold-start retry.
   *
   * After a relay tunnel reconnects, the first relay_request may time out because
   * the server's routing table hasn't registered the new WS connection yet.
   * A short 150ms delay + second attempt covers this window.
   */
  private def relayWithColdStartRetry(
    client: NeblinkClientPort,
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    projectRoot: String,
    kind: String = ""
  ): IO[Either[String, String]] =
    relayExecAudited(client, peer, toolName, params, projectRoot, kind).flatMap {
      case Right(output) => IO.pure(Right(output))
      case Left(err) =>
        // Cold start retry — relay tunnel might have just reconnected
        IO.sleep(150.millis) *> client.relayExec(peer.deviceId, toolName, params).map {
          case Right(output) => Right(output)
          case Left(err2) => Left(s"$err; $err2 (2 attempts)")
        }
    }

  // ---- P1: Parallel race for read-only tools ----

  /**
   * Race P2P and Relay concurrently for read-only tools. Whichever responds first
   * with a success wins; the loser fiber is cancelled. If the first to finish
   * fails, we wait for the second.
   *
   * Only safe for read-only tools (Read/Glob/Grep) because cancelling a write
   * tool mid-execution could leave the remote filesystem in an inconsistent state.
   */
  private def raceP2PAndRelay(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    client: NeblinkClientPort,
    projectRoot: String,
    budget: P2pPathDecision.ProbeBudget,
    kind: String = ""
  ): IO[Either[ToolError, String]] =
    val p2pIO: IO[Either[ToolError, String]] =
      p2pExecuteWithRetry(peer, toolName, params, timeout, projectRoot, budget, kind)
    val relayIO: IO[Either[ToolError, String]] =
      relayExecAudited(client, peer, toolName, params, projectRoot, kind).map {
        case Right(output) => Right(output)
        case Left(err) => Left(ToolError(s"Relay failed: $err"))
      }

    for
      p2pFiber <- p2pIO.start
      relayFiber <- relayIO.start
      // Race to see which fiber finishes first (success or failure).
      // IO.race cancels the losing *join* IO but NOT the underlying fiber.
      raceResult <- IO.race(p2pFiber.joinWithNever, relayFiber.joinWithNever)
      result <- raceResult match
        case Left(r @ Right(_)) =>
          // P2P won with success — cancel relay fiber, record memory
          relayFiber.cancel *> recordPathMemory(peer, "p2p").as(r)
        case Left(Left(_)) =>
          // P2P failed first — wait for relay
          relayFiber.joinWithNever.flatMap(r => recordPathMemory(peer, if r.isRight then "relay" else "p2p").as(r))
        case Right(r @ Right(_)) =>
          // Relay won with success — cancel P2P fiber, record memory
          p2pFiber.cancel *> recordPathMemory(peer, "relay").as(r)
        case Right(Left(_)) =>
          // Relay failed first — wait for P2P
          p2pFiber.joinWithNever.flatMap(r => recordPathMemory(peer, if r.isRight then "p2p" else "relay").as(r))
    yield result
    end for
  end raceP2PAndRelay

  // ---- Helpers ----

  /**
   * Ensure remote params have a timeout matching the HTTP timeout.
   * Without this, the remote BashTool uses its default 30s timeout,
   * causing premature timeout for long-running remote tasks.
   *
   * `waitTimeout` 与本侧 fiber 的等待上限同源同值（bgHttpWaitTimeout 判据）：
   * persistent 服务型设备任务两侧同豁免——缺省 = BgTimeout（前台腿现状零漂移）。
   */
  private def ensureRemoteTimeout(
    params: JsonObject,
    waitTimeout: FiniteDuration = RemoteExecutor.BgTimeout
  ): JsonObject =
    if params.contains("timeout") then params
    else params.add("timeout", waitTimeout.toMillis.asJson)

  // resolvePeer 已上移至伴生 object（xdev 批：纯函数 + 同包可测）——
  // 类内调用点经 `RemoteExecutor.resolvePeer` 限定，行为零变化。

end RemoteExecutor

object RemoteExecutor:
  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  @volatile private var instance: Option[RemoteExecutor] = None

  // ---- 多设备后台任务可见批（作者令 2026-10-25）判据单点（纯函数，spec 直测）----

  /**
   * 等待型远程调用（前台腿 + 后台 fiber）的 HTTP 等待上限：1h 网络兜底。
   * （原类内 `private val BgTimeout` 上移伴生——类内引用经限定名同值，禁漂移。）
   */
  val BgTimeout: FiniteDuration = 3600.seconds

  /**
   * persistent（服务型）远程后台任务的 HTTP 等待档：30 天「事实无界」。
   * dispatch 界面收 FiniteDuration（无 Inf 档），而设备上的 dev server / watcher
   * 是长驻语义——不该被 1h 网络兜底在等待中杀掉。语义对齐本地腿「persistent
   * 豁免 idle/硬超时/停滞守卫」；对端 Bash 的 `timeout` 参数经 ensureRemoteTimeout
   * 同值下发，防对端先杀（两侧同判据）。
   */
  val PersistentBgHttpTimeout: FiniteDuration = 30.days

  /**
   * persistent 豁免判据（唯一单点）：等待型 = BgTimeout（现状零漂移）；
   * persistent=true = 恒长档。executeRemoteBackground 的 fiber 等待与
   * ensureRemoteTimeout 的对端 timeout 同源消费本判据。
   */
  def bgHttpWaitTimeout(persistent: Boolean): FiniteDuration =
    if persistent then PersistentBgHttpTimeout else BgTimeout

  /**
   * 本侧生命周期旗标摘除（下发对端前）：`run_in_background`（execute 分流已消费）
   * 与 `persistent`（本侧登记/等待集豁免/HTTP 豁免记账）不过线——对端按前台同步
   * 执行，不识别也不该识别本侧的后台生命周期语义。
   */
  def stripLocalBgFlags(params: JsonObject): JsonObject =
    params.remove("run_in_background").remove("persistent")

  /**
   * `persistent` 旗标解析（缺省 false = 等待型；与本地 BashTool 同判：
   * true = 服务型长驻，不纳入等待集、HTTP 等待豁免）。
   */
  def parsePersistent(params: JsonObject): Boolean =
    params("persistent").flatMap(_.asBoolean).getOrElse(false)

  /** 浏览面单件大小闸（dropbox 大件通道引导线之上的硬闸；与 10MiB capture 闸分档）。
    * M2 起真源在伴生对象（BrowseGetLoop 首响应闸与实例腿同参一处，禁两处漂移）。 */
  val MaxScopedFetchBytes: Long = 50L * 1024 * 1024

  /** 浏览取件取消（WS `fileTransferCancel` 的单一入口）：翻在途旗标，取件腿在块间
    * 停止。true = 有一条在途取件被叫停（旗标表 = `BrowseFetchRegistry`，进程级，
    * 无需实例在场——实例未初始化时无在途取件，恒 false）。 */
  def cancelScopedFetch(deviceId: String, remotePath: String): Boolean =
    BrowseFetchRegistry.cancel(deviceId, remotePath)

  /** Wire the RemoteExecutor with a NeblinkService, Dispatcher, and optional relay client. Called on startup. */
  def initialize(
    neblinkService: NeblinkServicePort,
    dispatcher: Dispatcher[IO],
    relayClient: Option[NeblinkClientPort] = None
  ): Unit =
    instance = Some(new RemoteExecutor(neblinkService, dispatcher, relayClient))
    // ④ captures TTL 清扫（xdev 批 2026-09-15）：启动即清一次 + 每 6h 周期清。
    // 清扫失败只 WARN（CapturePull.sweepExpired 内已兜），绝不影响网关启动。
    // v2.1 阶段 1（2026-10-09）：pickups 落盘（PointerPickup，§C.2 按需取件的
    // 本地读数端）同 fiber 同周期——逐件留痕语义同族。
    dispatcher.unsafeRunAndForget(
      (CapturePull.sweepExpired().attempt *> PointerPickup.sweepExpired().attempt *> IO.sleep(6.hours)).foreverM.void
    )

  /** Get the current instance, or None if neblink is not initialized. */
  def current: Option[RemoteExecutor] = instance

  /**
   * 目标解析（xdev 批 2026-09-15 作者裁定「歧义即拒绝 A」）：
   * **分层匹配 + 多命中显式拒绝**。
   *   1. 精确层（equalsIgnoreCase）：恰一命中 ⇒ 用它；多命中（真同名设备）⇒ 拒绝。
   *   2. 模糊层（deviceId.startsWith ‖ deviceName.contains，仅精确层零命中时）：
   *      恰一命中 ⇒ 用它；多命中 ⇒ **显式报错 + 全部候选名单**（候选格式
   *      `deviceName(id前8位)`，可判读、可直接复制精确 id）。
   *   3. 零命中语义不变（原报错文案逐字保留，含 Available 名单）。
   *
   * 行为变更申报（判红①缓解）：旧代码 = `find` 取列表序首个命中（静默）。分层
   * 后「精确唯一命中」场景与旧代码常见路径一致；被拒的只有**真歧义**（精确多
   * 命中 / 模糊多命中）——旧代码在这种场景会静默选中任意一台，正是判红① 的
   * 「同名投错机器」失败形态。纯函数（不触实例状态）⇒ 放 object 供同包测试。
   * v2.1 阶段 1：可见域从 `private[tools]` 放宽到 `private[nebflow]`——项目
   * 创建面（ProjectCreateService 的远端创建腿）按同一解析单点选设备（禁第二份
   * 分层匹配实现），行为零变化。
   */
  private[nebflow] def resolvePeer(deviceName: String, peers: List[PeerInfo]): Either[ToolError, PeerInfo] =
    val exact = peers.filter(_.deviceName.equalsIgnoreCase(deviceName))
    val fuzzy = peers.filter(p =>
      p.deviceId.startsWith(deviceName) ||
        p.deviceName.toLowerCase.contains(deviceName.toLowerCase)
    )
    val candidates = if exact.nonEmpty then exact else fuzzy
    candidates match
      case single :: Nil => Right(single)
      case Nil =>
        val available = peers.map(_.deviceName)
        Left(
          ToolError(
            if peers.isEmpty then
              s"No peer devices discovered after scan. Check: (1) NebLink Server is configured on both machines, (2) ${nebflow.shared.Branding.productName} is running on '$deviceName', (3) both devices are on the same NebLink network."
            else s"Device '$deviceName' not found among ${peers.size} peer(s). Available: ${available.mkString(", ")}"
          )
        )
      case many =>
        val listed = many.map(p => s"${p.deviceName}(${p.deviceId.take(8)})").mkString(", ")
        Left(
          ToolError(
            s"Device '$deviceName' is ambiguous: ${many.size} peers match. Candidates: $listed. " +
              "Re-dispatch with the exact device name or the full deviceId of the intended target."
          )
        )
    end match
  end resolvePeer

  /**
   * Tools that support remote execution. Only these tools get the `device` parameter
   * in their schema. Other tools (Card, AskUser, Delegate, etc.) always run locally.
   */
  val remoteableTools: Set[String] = Set("Bash", "Read", "Write", "Edit", "Glob", "Grep")

  /**
   * Add `device` parameter to a tool's input schema if it's a remoteable tool.
   * Returns the modified schema, or the original if the tool is not remoteable.
   */
  def augmentSchema(toolName: String, schema: JsonObject): JsonObject =
    if !remoteableTools.contains(toolName) then schema
    else
      val props = schema("properties")
        .flatMap(_.asObject)
        .getOrElse(JsonObject.empty)
      props.add(
        "device",
        io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Target device name. Use the device's name from the available devices list. Defaults to local device if omitted.".asJson
        )
      ) match
        case newProps =>
          schema.add("properties", newProps.asJson)

end RemoteExecutor
