package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import scala.concurrent.duration.*

/**
 * Discovers Nebflow peers via the NebLink Server.
 *
 * Each cycle logs in (or heartbeats) to the NebLink Server to get the peer list,
 * then syncs presence connections via NeblinkPresenceService.
 *
 * Known-peer liveness is maintained entirely by WS connections (heartbeat + TCP RST).
 * The periodic discovery cycle only discovers NEW devices — it does not poll known peers.
 *
 * NebLink Server is the trust boundary: only devices on the same network can reach each other.
 */
final class NeblinkDiscovery(
  neblinkService: NeblinkService,
  serverPort: Int,
  presenceService: NeblinkPresenceService,
  initialClient: Option[NeblinkClient] = None
):
  private val logger = NebflowLogger.forName("nebflow.neblink.discovery")

  /** Consecutive heartbeat/discovery failures — drives exponential backoff. */
  private val failCount: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

  /** Current backoff delay based on consecutive failures. */
  def currentDelay: IO[FiniteDuration] =
    failCount.get.map(delayForFailures)

  /** Pure backoff function — testable without IO. 实现收归伴生对象
    * （`NeblinkDiscovery.delayForFailures`，口径与参数依据见那边），本方法只转发
    * ——避免「spec 复制一份阈值来测副本」的形态（既有 spec 曾如此，等于没钉住）。 */
  def delayForFailures(n: Int): FiniteDuration =
    NeblinkDiscovery.delayForFailures(n)

  /** Reset failure counter (called on success). */
  private def resetFailCount: IO[Unit] = failCount.set(0)

  /** Increment failure counter (called on failure). */
  private def incFailCount: IO[Unit] = failCount.update(_ + 1)

  // The client is held in a Ref so it can be hot-swapped at runtime (e.g. after
  // device-flow enrollment completes, without restarting the gateway).
  private val clientRef: Ref[IO, Option[NeblinkClient]] =
    Ref.unsafe[IO, Option[NeblinkClient]](initialClient)

  /** Hot-swap the NebLink client (used after device-flow enrollment). */
  def setClient(client: Option[NeblinkClient]): IO[Unit] =
    clientRef.set(client) *> logger.info("NebLink client hot-swapped")

  /**
   * The authoritative live client. Enrollment hot-swaps create a NEW
   * NeblinkClient here without touching NeblinkService.relayClientOpt, so
   * consumers that need "the client actually in use" (e.g. logout notify)
   * must read this, not the relay client reference.
   */
  def currentClient: IO[Option[NeblinkClient]] = clientRef.get

  /** Discovery cycle — use NebLink Server if configured. */
  def discoverCycle: IO[Unit] =
    clientRef.get.flatMap {
      case Some(client) => discoverViaServer(client)
      case None => logger.warn("NebLink Server is not configured — discovery skipped")
    }

  /**
   * Heartbeat cycle — send a heartbeat to the current client (if any).
   * Used by the periodic heartbeat loop. Returns immediately if no client.
   */
  def heartbeatCycle: IO[Unit] =
    clientRef.get.flatMap {
      case Some(client) => doHeartbeat(client)
      case None => IO.unit
    }

  private def doHeartbeat(client: NeblinkClient): IO[Unit] =
    client.heartbeat.flatMap {
      case Right(serverPeers) =>
        // 卡②：心跳腿注入 self deviceId ⇒ 名册回传本机自身时不进设备面
        // （与 NeblinkService.handleAnnounce 的 self 过滤同判据；作用域内无 identity
        // ⇒ 取一次再注入，identity 取不到 = 不过滤，保持向后兼容）。
        neblinkService.identity.map(id => Some(id.deviceId)).handleError(_ => None).flatMap { selfId =>
          val neblinkPeers = client.toNeblinkPeers(serverPeers, selfId)
          val peerIps = client.peerAddresses(serverPeers)
          // syncPeers (not bare upsert): the heartbeat path must CONVERGE the
          // local peer list — peers that vanished from the server response are
          // removed, not just refreshed. Upsert-only growth was the root cause
          // of permanent ghost entries (logged-out devices staying "online").
          presenceService.syncPeers(neblinkPeers) *>
            neblinkService.updateTrustedIps(peerIps) *>
            neblinkService.sendSync(nebflow.neblink.SyncCommand.PeerDiscovered) *>
            resetFailCount
        }
      case Left(err) =>
        // Heartbeat failed — fall back to full discovery (auto re-login if needed).
        logger.debug(s"Heartbeat failed ($err), falling back to discovery...") *>
          discoverViaServer(client).handleErrorWith(e =>
            logger.debug(s"Discovery fallback error: ${e.getMessage}") *> incFailCount
          )
    }

  /** Discovery via NebLink Server: login/heartbeat → upsert peers → sync presence. */
  private def discoverViaServer(client: NeblinkClient): IO[Unit] =
    for
      identity <- neblinkService.identity
      result <- client.discover(identity.deviceId, identity.deviceName, identity.platform, Nil)
      _ <- result match
        case Right(serverPeers) =>
          // 卡②：发现腿同样注入 self deviceId（identity 已在 :108 作用域内）。
          val neblinkPeers = client.toNeblinkPeers(serverPeers, Some(identity.deviceId))
          val peerIps = client.peerAddresses(serverPeers)
          for
            _ <- neblinkPeers.traverse_(p => neblinkService.upsertPeer(p))
            _ <- neblinkService.updateTrustedIps(peerIps)
            _ <- presenceService.syncPeers(neblinkPeers)
            _ <- logger.debug(s"NebLink Server discovery: ${neblinkPeers.size} peer(s)")
            _ <- resetFailCount
          yield ()
        case Left(err) =>
          logger.warn(s"NebLink Server discovery failed: $err") *> incFailCount
    yield ()

  /** Diagnostic scan — returns NebLink Server discovery status for debugging. */
  def diagnosticScan: IO[Json] =
    for
      currentPeers <- neblinkService.peers
      currentClient <- clientRef.get
      clientStatus <- currentClient match
        case Some(client) =>
          for
            identity <- neblinkService.identity
            result <- client.discover(identity.deviceId, identity.deviceName, identity.platform, Nil)
          yield result match
            case Right(serverPeers) =>
              Json.obj(
                "configured" -> true.asJson,
                "loggedIn" -> true.asJson,
                "serverPeerCount" -> serverPeers.size.asJson
              )
            case Left(err) =>
              Json.obj(
                "configured" -> true.asJson,
                "loggedIn" -> false.asJson,
                "error" -> err.asJson
              )
        case None =>
          IO.pure(Json.obj("configured" -> false.asJson))
    yield Json.obj(
      Protocol.neblinkServerField -> clientStatus,
      "currentPeers" -> currentPeers.map(_.deviceName).asJson
    )

end NeblinkDiscovery

object NeblinkDiscovery:

  /**
   * 心跳/发现退避的**封顶**（2026-09-13 RC-3d 客户端半边）。
   *
   * 🔴 由 **120 s 收紧到 45 s**，依据是服务端（跨仓 neblink-server，只读引用，
   * 本批不改其码、不发单）**会话 TTL 与隧道逐帧校验**的两条现场事实：
   *
   *  1. 服务端清扫：`DEVICE_LIVENESS_TTL = 90 s`（`src/store.rs:378`），`main.rs:66-71`
   *     每 30 s 调 `purge_stale`（`src/store.rs:1825`：**内存 Map 移除 + `DELETE FROM
   *     sessions`**）⇒ 会话行在「last_seen 过期 90 s + 至多 30 s 清扫延迟」后被删。
   *  2. 扇出源与隧道校验读的都是**被清扫的那张表 / 那个 Map**：
   *     · 好友订阅推送的候选设备 = `devices_for_user(user)`（`SELECT DISTINCT
   *       device_id, network_id FROM sessions WHERE user_id = ?1`，`src/store.rs:3130-3134`）
   *       ⇒ 会话行被删 ⇒ 候选为空 ⇒ `push_to_user` **静默 continue**（`src/friends.rs:159-161`）。
   *     · relay 隧道循环在**每一帧**（含 `FriendEvent`）前校验
   *       `session_alive_by_hash(&token_hash)` = `devices.contains_key`（`src/relay.rs:553`
   *       / `:588`、`src/store.rs:1617-1619`）⇒ 会话被清扫 ⇒ 该帧**不投递**并直接
   *       `break` 关闭隧道（日志 `relay: session revoked, closing tunnel <device>`）。
   *  3. 客户端这边维持会话存活的手段就是本循环里的 HTTP 心跳（服务端在
   *     `device_by_token`/heartbeat 里刷 `sessions.last_seen`，`src/store.rs:1484-1509`）。
   *     ⇒ **心跳周期必须小于该 TTL**，否则「连续失败 → 退避到 120 s」这一段会把一台
   *     活着的设备挤出扇出表：120 s > 90 s TTL，且清扫每 30 s 跑一次 ⇒ 退避一开始
   *     就注定被清扫；此后的每一条好友推送都会在服务端**丢帧并顺手拆隧道**
   *     （这正是取证正本 §7.1 RC-S3 的放大器，也是日志里 20 次断连的候选诱因）。
   *
   * 取 45 s 的理由：45 s = 服务端自己文档化的心跳节奏（`src/routes.rs:777`
   * 「heartbeat interval is 45s — only a 2x margin」，即 TTL/2），兼容一次丢拍
   * 而不掉出扇出表；60 s 中间档（n ≤ 5）**保持不动**（仍在 TTL 之下）。
   * 代价：长时故障期请求量 ≤0.5 次/分钟 → ≈1.3 次/分钟（可忽略；且这是唯一
   * 「故障期反而更该保持会话」的场景）。
   *
   * 判红（本参数的验收判据，见实施报告 §判红）：
   *  · 客户端日志：`Heartbeat failed …` 连续 ≥6 次之后，下一次心跳间隔 ≤45 s
   *    （修前 = 120 s）；
   *  · 服务端日志：不得再出现「同一设备 `Purged stale devices` / `session revoked,
   *    closing tunnel` 紧随一条 friend 推送」的配对；出现即判红。
   */
  val HeartbeatBackoffCap: FiniteDuration = 45.seconds

  /** 退避曲线（纯函数，可独立单测，不必构造整个 discovery）。 */
  def delayForFailures(n: Int): FiniteDuration =
    if n <= 2 then 30.seconds // first few retries at normal interval
    else if n <= 5 then 60.seconds // repeated failures → slow down
    else HeartbeatBackoffCap // cap: 必须 < 服务端会话 TTL(90s)，见上

end NeblinkDiscovery
