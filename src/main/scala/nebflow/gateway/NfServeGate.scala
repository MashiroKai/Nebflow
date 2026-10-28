package nebflow.gateway

import cats.effect.IO
import io.circe.parser.parse
import nebflow.shared.PathUtil

import java.util.concurrent.atomic.AtomicInteger

/**
 * #750（2026-10-23，nf-file 媒体面高载 503 硬失败批）：`/api/nf-file` 文件腿的
 * 过载准入闸——在飞服务预算。
 *
 * 作者实测（2026-10-22 18:52，五 JVM 并行构建压载，load 28）：文档里点图报
 * 「Failed to render: HTTP 503」。链路：网关算力被并行构建饿死 ⇒ 文件腿请求排队
 * 不前 ⇒ 上游（relay ingress）先于网关放弃，向浏览器回**裸 503**（无任何退避
 * 语义；同源旁证 18:42 relay uplink inbox full）。修前网关的形态是「无限接纳」：
 * 每个到达的文件腿请求都进 verdict/字节服务，越压越长，最后由上游代答 503。
 *
 * 本闸把形态改成**快速让路**：在飞文件腿超过预算 ⇒ 立即 `429 + Retry-After`
 * （响应经 relay 原样转发到浏览器），客户端按提示短退避后重试——过载从「挂死
 * 到 503」变成「一次短重试即恢复」（前端腿见 `nfFileCache.js` 的取数退避）。
 *
 * 🔴 红线（零超时设计）：本闸**没有任何 wall-clock 判据**——不读时钟、不设
 * deadline、不等待。`tryAcquire` 是一次原子比较（CAS 循环无锁无 park），过与
 * 不过当场出结果。唯一带"时间"的量是 429 响应携带的建议值 [[RetryAfterSeconds]]
 * （RFC 9110 §18.2 语义：给客户端的重试等待建议，不是服务端自己的超时）。
 *
 * 预算 = `nebflow.json` 的 `nfFile.serveConcurrency`（缺省
 * [[DefaultMaxConcurrent]]；与 `nfFile.ticketTtlSeconds` 同块同形态，启动读一次
 * fail-safe 回缺省）。本闸**不做**热重读：预算改动随下次网关重启生效——面窄，
 * 不值得再挂一条 mtime probe 路径（票 TTL 的热重读是它自己的既有面，不扩散）。
 *
 * 鉴权零让步：闸位在参数快检之后、verdict/`verifyAndConsume` 之前——被 429 的
 * 请求不铸票、不消耗票（R4 无限读语义不受影响）；获准请求照走同一条
 * ticket-only 判据链（C1-5 单一权威），既有鉴权路径逐字节不变。
 *
 * 纯计数器 + IO 包装，零类依赖（`NfFileRoutes` 同款独立可测形态：
 * [[nebflow.gateway.NfServeGateSpec]]）。
 */
final class NfServeGate private (inFlight: AtomicInteger, val maxConcurrent: Int):

  /**
   * Try to take one serving slot. NEVER waits: a CAS loop against the cap with
   * no park, no clock, no deadline — the answer (admitted / over budget) is
   * immediate by construction.
   */
  def tryAcquire: IO[Boolean] = IO {
    var acquired = false
    var settled = false
    while !settled && !acquired do
      val cur = inFlight.get
      if cur >= maxConcurrent then settled = true
      else if inFlight.compareAndSet(cur, cur + 1) then acquired = true
    acquired
  }

  /**
   * Hand the slot back. Paired with [[tryAcquire]] exactly once per acquisition
   * — the route releases on body-stream termination (`onFinalize`) or on
   * handler failure (`attempt`), never both.
   */
  private[gateway] def release: IO[Unit] = IO(inFlight.decrementAndGet())

  /** Diagnostic read (spec / future health face). */
  def inFlightNow: IO[Int] = IO(inFlight.get)

end NfServeGate

object NfServeGate:

  /**
   * 缺省预算：`nebflow.json` 无 `nfFile.serveConcurrency` 时。同源浏览器对单
   * origin 的并行连接本就 ≤6，32 给足余量；真正的挤压面（并行构建饿池）由
   * 429-重试语义收敛，不靠加大预算硬扛。
   */
  val DefaultMaxConcurrent: Int = 32

  /** Config block inside `nebflow.json` — the ticket TTL's own `nfFile` block. */
  val ConfigKey: String = "nfFile"

  /** Key inside the block. */
  val ConcurrencyKey: String = "serveConcurrency"

  /**
   * The `Retry-After` hint the 429 leg advertises (RFC 9110 §18.2, seconds).
   * Short by design: the overloaded leg drains in seconds, and the frontend
   * retry is capped at one.
   */
  val RetryAfterSeconds: String = "1"

  /**
   * Budget clamps: ≥1 (a 0 budget would refuse everything) and ≤4096 (a
   * runaway value would recreate the unbounded leg this gate exists to close).
   */
  val MinMaxConcurrent: Int = 1
  val MaxMaxConcurrent: Int = 4096

  def apply(maxConcurrent: Int): NfServeGate =
    val capped =
      if maxConcurrent < MinMaxConcurrent then MinMaxConcurrent
      else if maxConcurrent > MaxMaxConcurrent then MaxMaxConcurrent
      else maxConcurrent
    new NfServeGate(new AtomicInteger(0), capped)

  /**
   * Pure: pull `nfFile.serveConcurrency` out of a `nebflow.json` body.
   * Non-positive / non-integer / absent → None (caller keeps its value —
   * `NfTicketStore.parseTtl` 同款 fail-safe 形态).
   */
  def parseMaxConcurrent(configJson: String): Option[Int] =
    parse(configJson).toOption
      .flatMap(_.hcursor.downField(ConfigKey).downField(ConcurrencyKey).as[Int].toOption)
      .filter(_ > 0)

  /**
   * Read the configured budget from the data root's `nebflow.json` once
   * (startup path), fail-safe → [[DefaultMaxConcurrent]].
   */
  def loadMaxConcurrent(): Int =
    try
      val cfg = PathUtil.configJsonWritePath(PathUtil.dataRoot)
      if os.exists(cfg) then parseMaxConcurrent(os.read(cfg)).getOrElse(DefaultMaxConcurrent)
      else DefaultMaxConcurrent
    catch case _: Throwable => DefaultMaxConcurrent

  /**
   * The config READ is memoized per JVM (one disk probe per process — mirrors
   * `NfPathPolicy.memoized`; a budget change therefore takes effect on
   * restart, as documented above). The GATE itself is never shared:
   * [[fromConfig]] builds a fresh counter per route instance so specs (and
   * any future second composition) stay isolated.
   */
  private lazy val maxFromConfigMemo: Int = loadMaxConcurrent()

  /** Default-argument gate for `NfFileRoutes.nfFileRoutes` (fresh counter per call). */
  def fromConfig(): NfServeGate = apply(maxFromConfigMemo)

end NfServeGate
