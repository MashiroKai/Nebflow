package nebflow.gateway

import cats.data.Kleisli
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.{Branding, NebflowLogger}
import org.http4s.HttpApp

import scala.concurrent.duration.*

/** Per-IP connection-face guard (修复批 R-1b · 2026-09-22 conn-guard 批).
  *
  * 背景（91min 失联法证 + R-1a 热修注释）：单一受信对端的 presence 拨号风暴
  * （~1 conn/s、会话半开不关）灌满 ember 受理表 → HTTP 面失聪。R-1a 已把
  * maxConnections 1024→4096、idle 1h→5min（容量+回收两层）。本件补 R-1b 两层：
  *
  *   1. per-IP 看护 —— WS 升级受理点做 per-IP 并发会话计数 + 上限 + 可见 429。
  *      ember 0.23.30 无连接级回调/准入（设计件 §B.4），应用层唯一能逐 IP 归因
  *      的缝 = 升级请求的 `req.remoteAddr`（仓内 4 处先例）。受信名单 = 环回
  *      （本地管理通道在风暴中必须始终可达）+ 环境变量追加。
  *   2. 连接面可观测 —— 每 snapshotSec 打一行快照 + 总量到 warnPct 预警 +
  *      `GET /api/health/conn` 只读读数面（设计件 §C④-1/2/4/5）。
  *
  * 防自灌纪律：守护自身的追踪表以 maxTrackedIps 封顶——表满时先淘汰陈旧
  * 零会话条目，仍无位的 IP 不再逐 IP 建档（其活跃会话计入 overflow 总数，
  * 仍受全局上限约束）。伪造源 IP 洪峰无法把守护撑到无界。
  *
  * 上限语义：per-IP 上限只对非受信 IP 生效；受信 IP（环回）只计数、永不拒
  * （作者本机管理通道不能被自己人锁死）。总量上限 = 真实并发 WS 会话数，
  * 超限拒绝同样只落在非受信 IP 上。
  */
final case class ConnGuardConfig(
  enabled: Boolean = true,
  wsPerIpCap: Int = 64,
  wsTotalCap: Int = 1024,
  warnPct: Int = 80,
  maxTrackedIps: Int = 1024,
  snapshotSec: Int = 60,
  extraTrustedIps: Set[String] = Set.empty,
):
  /** 环回恒在信任名单：本机 web UI / CLI / 健康探针的管理通道不能被限死。 */
  val trustedIps: Set[String] = ConnGuardConfig.DefaultTrustedIps ++ extraTrustedIps

object ConnGuardConfig:

  val DefaultTrustedIps: Set[String] = Set("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

  /** R-1a（2026-09-22 watchdog repair）落定的 idle 值，单源引用。 */
  val IdleTimeout: FiniteDuration = 5.minutes

  /** 库默认显式化（javap 钉死：http4s-server_3-0.23.30 `package$defaults$`
    * ShutdownTimeout bipush 30s；ember builder Defaults$ requestHeaderReceive
    * Timeout iconst_5 5s）。零行为变更，防上游默认漂移 + 摄取面可检索。
    * （设计件 §E.3「留给实施批钉」项，本批钉死。） */
  val HeaderReceiveTimeout: FiniteDuration = 5.seconds
  val ShutdownTimeout: FiniteDuration = 30.seconds

  /** Pure env 解析（测试可注入 lookup；非法值回落默认，不 crash）。 */
  def fromEnv(lookup: String => Option[String]): ConnGuardConfig =
    def intEnv(name: String, dflt: Int, min: Int): Int =
      lookup(name).flatMap(_.trim.toIntOption).map(v => math.max(min, v)).getOrElse(dflt)
    val enabled =
      lookup("GATEWAY_CONN_GUARD").map(v =>
        !(v.trim.equalsIgnoreCase("off") || v.trim == "0" || v.trim.equalsIgnoreCase("false"))
      ).getOrElse(true)
    val extra =
      lookup("GATEWAY_CONN_GUARD_TRUSTED_IPS")
        .map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet)
        .getOrElse(Set.empty)
    ConnGuardConfig(
      enabled = enabled,
      wsPerIpCap = intEnv("GATEWAY_CONN_GUARD_WS_PER_IP_CAP", 64, 1),
      wsTotalCap = intEnv("GATEWAY_CONN_GUARD_WS_TOTAL_CAP", 1024, 1),
      warnPct = intEnv("GATEWAY_CONN_GUARD_WARN_PCT", 80, 1),
      snapshotSec = intEnv("GATEWAY_CONN_GUARD_SNAPSHOT_SEC", 60, 15),
      extraTrustedIps = extra,
    )

  def load: ConnGuardConfig = fromEnv(Branding.env)
end ConnGuardConfig

object ConnGuard:

  sealed trait WsDecision
  object WsDecision:
    final case class Allowed(handle: WsHandle) extends WsDecision
    final case class Rejected(reason: String) extends WsDecision

  /** acquire 与 release 的配对凭据——release 据此回减正确的桶（逐 IP 档或
    * overflow 总数），杜绝计数泄漏。 */
  final case class WsHandle private[ConnGuard] (ip: String, tracked: Boolean)
  object WsHandle:
    private[ConnGuard] val empty: WsHandle = WsHandle("", tracked = false)

  final case class IpEntry(wsCount: Int, lastSeenMs: Long, reqTotal: Long = 0L)

  final case class Snapshot(
    enabled: Boolean,
    wsTotal: Int,
    trackedIps: Int,
    overflowConns: Int,
    acceptedTotal: Long,
    rejectedTotal: Long,
    evictedTotal: Long,
    reqTotal: Long,
    wsPerIpCap: Int,
    wsTotalCap: Int,
    warnPct: Int,
    topWs: Vector[(String, Int)],
    topReq: Vector[(String, Long)],
  )

  private[ConnGuard] final case class State(
    perIp: Map[String, IpEntry] = Map.empty,
    overflowConns: Int = 0,
    acceptedTotal: Long = 0,
    rejectedTotal: Long = 0,
    evictedTotal: Long = 0,
    reqTotal: Long = 0,
  ):
    def wsTotal: Int = perIp.values.map(_.wsCount).sum + overflowConns

  private val StaleEvictTtlMs: Long = 10.minutes.toMillis

  // ── 构造 ────────────────────────────────────────────────────────────────

  def forConfig(cfg: ConnGuardConfig, logger: NebflowLogger): IO[ConnGuard] =
    Ref.of[IO, State](State()).map(new ConnGuard(cfg, _, logger))

  /** 生产入口：读环境变量装配，并打一行启动读数（上限/信任名单可见）。 */
  def create(logger: NebflowLogger): IO[ConnGuard] =
    val cfg = ConnGuardConfig.load
    forConfig(cfg, logger).flatTap { _ =>
      logger.info(
        s"conn-guard enabled=${cfg.enabled} wsPerIpCap=${cfg.wsPerIpCap} " +
          s"wsTotalCap=${cfg.wsTotalCap} warnPct=${cfg.warnPct} snapshotSec=${cfg.snapshotSec} " +
          s"trusted=[${cfg.trustedIps.mkString(",")}]"
      )
    }

  /** 测试/缺省用的全放行实例（enabled=false ⇒ 全部短路）。 */
  lazy val disabled: ConnGuard = new ConnGuard(
    ConnGuardConfig(enabled = false),
    Ref.unsafe[IO, State](State()),
    NebflowLogger.forName("nebflow.conn-guard"),
  )

  // ── 工具 ────────────────────────────────────────────────────────────────

  /** ip4s SocketAddress.toString 形态 = "ip:port"（v4）/ "[v6]:port"（v6），
    * 也兼容带前导 "/" 的变体——归一成裸 IP 作 per-IP 键。 */
  def normalizeIpStr(raw: String): String =
    val noSlash = if raw.startsWith("/") then raw.substring(1) else raw
    if noSlash.startsWith("[") then
      noSlash.indexOf(']') match
        case i if i > 0 => noSlash.substring(1, i)
        case _          => noSlash
    else
      noSlash.lastIndexOf(':') match
        case i if i > 0 => noSlash.substring(0, i)
        case _          => noSlash

  /** http4s 0.23.30 `req.remoteAddr` 实型 = `Option[com.comcast.ip4s.IpAddress]`
    * （toString 即裸 IP）——归一仍走 normalizeIpStr（幂等，兼容其余形态）。 */
  def normalizeIp(remoteAddr: Option[com.comcast.ip4s.IpAddress]): String =
    remoteAddr.fold("unknown")(a => normalizeIpStr(a.toString))

  /** 快照一行读数（60s 一条，< 200KB/日，走既有轮转）。 */
  def snapshotLine(s: Snapshot): String =
    val top = s.topWs.map((ip, n) => s"$ip:$n").mkString(",")
    s"conn-guard snapshot ws=${s.wsTotal}/${s.wsTotalCap} perIpCap=${s.wsPerIpCap} " +
      s"trackedIps=${s.trackedIps} overflow=${s.overflowConns} accepted=${s.acceptedTotal} " +
      s"rejected=${s.rejectedTotal} reqTotal=${s.reqTotal} top=[$top]"

  /** 预警（§C④-5）：ws 总量达 warnPct ⇒ 提前处置窗口（不再是静默失联）。 */
  def warnLine(s: Snapshot): Option[String] =
    if !s.enabled || s.wsTotalCap <= 0 then None
    else
      val pct = s.wsTotal * 100L / s.wsTotalCap
      if pct >= s.warnPct then
        Some(
          s"conn-guard WARN ws usage ${pct}% of cap (${s.wsTotal}/${s.wsTotalCap}) — " +
            s"per-ip=[${s.topWs.map((ip, n) => s"$ip:$n").mkString(",")}]; dial-storm shape?"
        )
      else None

  /** 只读读数面载荷（§C④-4）：仅 IP + 计数 + 口径，零凭据面。 */
  def healthJson(s: Snapshot, fdCount: Long): Json =
    def topJson(v: Vector[(String, Long)]): Json =
      Json.arr(v.map((ip, n) => Json.obj("ip" -> ip.asJson, "count" -> n.asJson))*)
    Json.obj(
      "enabled" -> s.enabled.asJson,
      "caps" -> Json.obj(
        "wsPerIp" -> s.wsPerIpCap.asJson,
        "wsTotal" -> s.wsTotalCap.asJson,
        "warnPct" -> s.warnPct.asJson,
      ),
      "ws" -> Json.obj(
        "total" -> s.wsTotal.asJson,
        "trackedIps" -> s.trackedIps.asJson,
        "overflow" -> s.overflowConns.asJson,
        "acceptedTotal" -> s.acceptedTotal.asJson,
        "rejectedTotal" -> s.rejectedTotal.asJson,
        "top" -> topJson(s.topWs.map((ip, n) => (ip, n.toLong))),
      ),
      "requests" -> Json.obj(
        "total" -> s.reqTotal.asJson,
        "top" -> topJson(s.topReq),
      ),
      "ember" -> Json.obj(
        "idleTimeout" -> ConnGuardConfig.IdleTimeout.toString.asJson,
        "requestHeaderReceiveTimeout" -> ConnGuardConfig.HeaderReceiveTimeout.toString.asJson,
        "shutdownTimeout" -> ConnGuardConfig.ShutdownTimeout.toString.asJson,
      ),
      "fdCount" -> fdCount.asJson,
    )

  /** fd 总量代理（§C④-1）：JDK 自带 MXBean，饱和时这条读数路仍可用
    * （不经 HTTP 面）。不可得 ⇒ -1（调用方按无读数展示，不猜）。 */
  def fdCount(): Long =
    try java.lang.management.ManagementFactory.getOperatingSystemMXBean match
      case u: com.sun.management.UnixOperatingSystemMXBean => u.getOpenFileDescriptorCount
      case _                                               => -1L
    catch case _: Throwable => -1L

  /** 请求级 per-IP 观测（§C④-2）：包在 HttpApp 外层，只计数不拦截。 */
  def requestTap(guard: ConnGuard): HttpApp[IO] => HttpApp[IO] = app =>
    Kleisli(req => guard.observeRequest(normalizeIp(req.remoteAddr)) *> app(req))
end ConnGuard

/** 见 [[ConnGuard]] 与 [[ConnGuardConfig]] 的纪律说明。 */
class ConnGuard private (
  val config: ConnGuardConfig,
  stateRef: Ref[IO, ConnGuard.State],
  logger: NebflowLogger,
):

  import ConnGuard.*
  import ConnGuardConfig.*

  private def isTrusted(ip: String): Boolean = config.trustedIps.contains(ip)

  /** 升级前检查（路由侧在 token/trust 闸之后调用）。Some(reason) = 拒。
    * 只读判定 + 拒绝计数，不动追踪表（真正的入账在 [[acquireWs]]）。 */
  def checkWs(ip: String): IO[Option[String]] =
    if !config.enabled then IO.pure(None)
    else
      stateRef.get.flatMap { st =>
        val reject: Option[String] =
          if isTrusted(ip) then None
          else if st.wsTotal >= config.wsTotalCap then Some("total-cap")
          else if st.perIp.get(ip).exists(_.wsCount >= config.wsPerIpCap) then Some("per-ip-cap")
          else None
        reject match
          case Some(reason) =>
            stateRef.update(s => s.copy(rejectedTotal = s.rejectedTotal + 1)).as(Some(reason))
          case None => IO.pure(None)
      }

  /** 受理入账（配对凭据交给路由侧，随流 finalizer 回减）。满表先陈旧淘汰
    * （防自灌），仍无位则并桶 overflow——上限由 [[checkWs]] 把门。 */
  def acquireWs(ip: String): IO[WsHandle] =
    if !config.enabled then IO.pure(WsHandle.empty)
    else
      IO.realTime.map(_.toMillis).flatMap { now =>
        stateRef.modify { st =>
          val st1 = if st.perIp.size >= config.maxTrackedIps then evictStale(st, now) else st
          val known = st1.perIp.contains(ip)
          if isTrusted(ip) || known || st1.perIp.size < config.maxTrackedIps then
            val e = st1.perIp.getOrElse(ip, IpEntry(0, now))
            (
              st1.copy(
                perIp = st1.perIp.updated(ip, e.copy(wsCount = e.wsCount + 1, lastSeenMs = now)),
                acceptedTotal = st1.acceptedTotal + 1,
              ),
              WsHandle(ip, tracked = true),
            )
          else
            (
              st1.copy(overflowConns = st1.overflowConns + 1, acceptedTotal = st1.acceptedTotal + 1),
              WsHandle(ip, tracked = false),
            )
        }
      }

  /** 流关闭回减（按凭据回正确的桶；floor 0，幂等安全）。 */
  def releaseWs(handle: WsHandle): IO[Unit] =
    if !config.enabled || handle == WsHandle.empty then IO.unit
    else
      IO.realTime.map(_.toMillis).flatMap { now =>
        stateRef.update { st =>
          if handle.tracked then
            st.perIp.get(handle.ip) match
              case Some(e) =>
                st.copy(perIp =
                  st.perIp.updated(handle.ip, e.copy(wsCount = math.max(0, e.wsCount - 1), lastSeenMs = now))
                )
              case None => st
          else st.copy(overflowConns = math.max(0, st.overflowConns - 1))
        }
      }

  /** 请求级观测（[[ConnGuard.requestTap]] 消费；只计数不拦截）。 */
  def observeRequest(ip: String): IO[Unit] =
    if !config.enabled then IO.unit
    else
      IO.realTime.map(_.toMillis).flatMap { now =>
        stateRef.update { st =>
          val e = st.perIp.getOrElse(ip, IpEntry(0, now))
          st.copy(
            perIp = st.perIp.updated(ip, e.copy(lastSeenMs = now, reqTotal = e.reqTotal + 1)),
            reqTotal = st.reqTotal + 1,
          )
        }
      }

  def snapshot: IO[Snapshot] =
    stateRef.get.map { st =>
      val topWs = st.perIp.toVector
        .map((ip, e) => (ip, e.wsCount))
        .filter { case (_, n) => n > 0 }
        .sortBy { case (_, n) => -n }
        .take(10)
      val topReq = st.perIp.toVector
        .map((ip, e) => (ip, e.reqTotal))
        .filter { case (_, n) => n > 0 }
        .sortBy { case (_, n) => -n }
        .take(10)
      Snapshot(
        enabled = config.enabled,
        wsTotal = st.wsTotal,
        trackedIps = st.perIp.size,
        overflowConns = st.overflowConns,
        acceptedTotal = st.acceptedTotal,
        rejectedTotal = st.rejectedTotal,
        evictedTotal = st.evictedTotal,
        reqTotal = st.reqTotal,
        wsPerIpCap = config.wsPerIpCap,
        wsTotalCap = config.wsTotalCap,
        warnPct = config.warnPct,
        topWs = topWs,
        topReq = topReq,
      )
    }

  /** 60s 快照循环（GatewayMain 启动时 `.start`）：连接层被灌死时它仍照常
    * 运行——不经 HTTP 面，这正是失联取证需要的独立观察者。 */
  def snapshotLoop: IO[Unit] =
    Stream
      .awakeEvery[IO](config.snapshotSec.seconds)
      .evalMap { _ =>
        snapshot.flatMap { s =>
          logger.info(ConnGuard.snapshotLine(s)) *>
            ConnGuard.warnLine(s).traverse_(logger.warn)
        }
      }
      .compile
      .drain
      .handleErrorWith(e => logger.warn(s"conn-guard snapshot loop stopped: ${e.getMessage}"))

  /** 满表陈旧淘汰（纯函数）：只淘汰零会话且超时的条目——活跃会话永不淘汰。 */
  private def evictStale(st: State, nowMs: Long): State =
    val (keep, evict) =
      st.perIp.partition { case (_, e) => e.wsCount > 0 || nowMs - e.lastSeenMs < StaleEvictTtlMs }
    if evict.isEmpty then st else st.copy(perIp = keep, evictedTotal = st.evictedTotal + evict.size)
end ConnGuard
