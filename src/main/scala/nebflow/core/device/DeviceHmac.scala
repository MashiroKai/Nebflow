package nebflow.core.device

import nebflow.shared.PathUtil

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 设备腿 HMAC 签名（v2 §A.4 / v2.1 §A.4 全文有效，阶段 1 落地）。
 *
 * 四元组头（wire 形状冻结）：
 * ```
 * X-Neblink-Device: <deviceId>     // 既有 shared.DeviceHeader
 * X-Neblink-Ts: <unix秒>
 * X-Neblink-Nonce: <随机>
 * X-Neblink-Signature: hex(HMAC-SHA256(sharedKey, deviceId|ts|nonce|method|path|sha256(body)))
 * ```
 * 接收端判据三件：ts ±90s 窗口 + nonce 防重放（per-device LRU）+ 常数时间比对。
 * 失败 = 401（enforcement=required 档）；灰度档见 [[DeviceAuthMode]]。
 *
 * 纯函数面（sign/verify/tsFresh/canonical）可离线测；nonce 防重放 = 进程内
 * [[NonceCache]]（重启即清 = 防重放窗重启归零——诚实边界：窗口内重放防线是
 * ts±90s 本身，LRU 是窗口内的第二道闸）。
 *
 * 归属（v2.1 阶段 1 实施批 2026-10-09）：本件落在 `core.device` 而非 `neblink`——
 * 消费方三侧 = core（DeviceDelegate spawn 通道 / RemoteExecutor 发送侧签名）、
 * gateway（NeblinkRoutes.verifyPeerAccess 收端 enforcement）、neblink（经由
 * gateway）。分层门禁（check-scala-layers）core 不准 import neblink ⇒ 纯密码学核
 * （零依赖，仅 stdlib + shared.PathUtil）下沉 core；`neblink/devices.json` 存储
 * 路径不变（包归属与落盘位置解耦）。
 */
object DeviceHmac:

  val HeaderTs: String = "x-neblink-ts"
  val HeaderNonce: String = "x-neblink-nonce"
  val HeaderSignature: String = "x-neblink-signature"

  /** ts 窗口（v2 §A.4 定值：±90s）。 */
  val TsWindowMs: Long = 90_000L

  /**
   * 规范串（**签名与验签的唯一拼装点**，禁两处各拼一套）。
   * `path` = 请求的 URI path（如 `/api/neblink/remote-exec`，不含 query——P2P
   * 设备面 POST 均无 query，规范冻结从简）；`bodySha256` = 请求体的 SHA-256 hex
   * （空体 = 空串的 SHA-256，同样参与签名）。
   */
  def canonical(deviceId: String, ts: String, nonce: String, method: String, path: String, bodySha256: String): String =
    s"$deviceId|$ts|$nonce|$method|$path|$bodySha256"

  /** HMAC-SHA256 hex（sharedKey 为密钥）。 */
  def sign(sharedKey: String, canonicalStr: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(sharedKey.getBytes("UTF-8"), "HmacSHA256"))
    bytesToHex(mac.doFinal(canonicalStr.getBytes("UTF-8")))

  /** 常数时间比对（`MessageDigest.isEqual`——禁 `==`）。 */
  def verify(sharedKey: String, canonicalStr: String, signature: String): Boolean =
    if sharedKey.isEmpty || signature.isEmpty then false
    else
      val expected = sign(sharedKey, canonicalStr)
      java.security.MessageDigest.isEqual(
        expected.getBytes("UTF-8"),
        signature.trim.getBytes("UTF-8")
      )

  /** ts 判鲜（v2 §A.4：±90s 窗口；`ts` 为 unix**秒**）。 */
  def tsFresh(ts: String, nowMs: Long): Boolean =
    ts.trim.toLongOption match
      case Some(sec) => math.abs(nowMs - sec * 1000L) <= TsWindowMs
      case None => false

  def bodySha256(body: String): String =
    val d = java.security.MessageDigest.getInstance("SHA-256")
    bytesToHex(d.digest(body.getBytes("UTF-8")))

  private def bytesToHex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  /**
   * 一次请求头的完整验签（判据三件套单点，两侧消费同一实现）。
   *
   * @return `None` = 验签通过；`Some(原因)` = 拒绝理由（可读、可审计）。
   */
  def verifyHeaders(
    sharedKey: String,
    deviceId: String,
    ts: Option[String],
    nonce: Option[String],
    signature: Option[String],
    method: String,
    path: String,
    body: String,
    nowMs: Long = System.currentTimeMillis()
  ): Option[String] =
    (ts, nonce, signature) match
      case (None, _, _) => Some("missing x-neblink-ts header")
      case (_, None, _) => Some("missing x-neblink-nonce header")
      case (_, _, None) => Some("missing x-neblink-signature header")
      case (Some(t), Some(n), Some(s)) =>
        if !tsFresh(t, nowMs) then Some(s"stale x-neblink-ts ($t) outside ±${TsWindowMs / 1000}s window")
        else if n.trim.isEmpty then Some("empty nonce")
        else
          val canon = canonical(deviceId, t.trim, n.trim, method, path, bodySha256(body))
          if !verify(sharedKey, canon, s) then Some("signature mismatch")
          else None

  /** 签名载荷单点（新通道无存量兼容面 ⇒ 无 nonce 参数默认值；UUID 去连字符）。 */
  def newNonce(): String =
    java.util.UUID.randomUUID().toString.replace("-", "")

end DeviceHmac

/**
 * nonce 防重放（per-device 定容 LRU；进程内态，重启归零）。
 * `firstSeen(deviceId, nonce)` = true（首见，放行）| false（窗口内重放，拒）。
 */
final class NonceCache(capacityPerDevice: Int = 1024):

  private val seen =
    new java.util.concurrent.ConcurrentHashMap[String, java.util.LinkedHashSet[String]]()

  def firstSeen(deviceId: String, nonce: String): Boolean =
    synchronized {
      val set = seen.computeIfAbsent(deviceId, _ => new java.util.LinkedHashSet[String]())
      val fresh = set.add(nonce)
      if fresh && set.size > capacityPerDevice then
        val it = set.iterator()
        var drop = set.size - capacityPerDevice
        while drop > 0 && it.hasNext do
          it.next()
          it.remove()
          drop -= 1
      fresh
    }

  /** 测试隔离。 */
  def clear(): Unit = synchronized(seen.clear())
end NonceCache

/**
 * Enforcement 档位（v2 §A.4「双轨灰度收口」的配置面）：
 *   - `off`（**缺省**）= 现状逐字节（不验签、双轨不启动）；
 *   - `audit` = 灰度观察档：带有效 HMAC 头 = 信任升级（配对即信任，IP 列表不设限）；
 *     无头/坏签 = 维持既有 IP 判据 + 一条可 grep 的审计行（`[deviceAuth]`，**不拒**）；
 *   - `required` = 收紧档：无有效 HMAC 头一律 401（acceptance #5 的「收紧后」态）。
 *
 * 配置键 = nebflow.json 顶层 `deviceAuth`（字符串）；宽容解码（坏值/缺失 = off），
 * 仿 [[nebflow.core.executor.ExecutorRegistry.readSettings]] 纪律。**运行期热读**
 * （每次判定现读盘）⇒ 档位切换零重启——生效方式（交付报告申报项）。
 */
object DeviceAuthMode:
  val Off = "off"
  val Audit = "audit"
  val Required = "required"

  /** 热读（每次判定现读盘——与 ExecutorRegistry 热读同款；坏值/读败 = off）。 */
  def read: String =
    try
      val p = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(p) then Off
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.downField("deviceAuth").as[String].toOption)
          .map(_.trim.toLowerCase)
          .map {
            case Audit => Audit
            case Required => Required
            case _ => Off
          }
          .getOrElse(Off)
    catch case _: Throwable => Off
end DeviceAuthMode
