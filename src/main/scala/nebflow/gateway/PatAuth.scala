package nebflow.gateway

import io.circe.{HCursor, Json, parser}
import nebflow.core.PathUtil
import nebflow.neblink.NeblinkConfig

import java.math.BigInteger
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import java.security.spec.{
  ECGenParameterSpec,
  ECParameterSpec,
  ECPoint,
  ECPublicKeySpec,
  RSAPublicKeySpec
}
import java.security.{AlgorithmParameters, KeyFactory, PublicKey, Signature}
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** 网关接受面 · **轨 2** —— Logto 签发的账号级长期令牌（PAT）的**离线验签**。
  *
  * 信任模型（root 2026-09-20 定案，`decision-10-pat-token.md:14` 的 R3）：令牌是 **Logto
  * 签名的自包含 JWT**，本网关用 Logto 公布的 JWKS 公钥**完全离线**验签 —— 无内省、无每请求
  * 网络往返；JWKS 获取 = 缓存 + 有界刷新。簿记面（账本 / 一次性显示 / 前缀末四位 / 吊销登记）
  * 属**站点后端**，不在本文件面内：本文件**不做任何查表式校验**，验签依据**只有** JWKS。
  *
  * 硬约束（逐条）：
  *  - **离线**：验签全在本地；网络只用于 JWKS 文档（缓存 TTL 3600s；陌生 kid 触发的一次强制
  *    刷新受 30s 下限约束 ⇒ 任意数量的坏令牌都不能把这里变成取数放大器）。
  *  - **fail-closed**：取不到 / 解析失败 / 密钥不匹配 / 缺必需声明 / 配置缺席 ⇒ **拒**；入口对
  *    调用方只回 Boolean，且**任何**异常都被吞成 false。
  *  - **不对外区分**原因（防枚举）：调用方（`RestApiRoutes.checkAuth`）把 false 统一渲染成既有
  *    `Forbidden({"error":"Unauthorized"})`，与轨 1 的错误形态逐字相同。
  *  - **零密钥回显**：日志只出现粗粒度原因（HTTP 码 / 异常类名），永不出现令牌明文、签名材料
  *    或 JWKS 材料。
  *
  * 配置面（`<dataRoot>/pat.json`；**文件缺席 / 解析失败 / audience 或 scope 缺一 ⇒ 整面关闭**
  * ⇒ 网关行为与本批之前逐字一致，轨 1 零影响）：
  *
  *   { "audience": "<Logto API resource 标识>", "scope": "<首发单档 scope 字面量>",
  *     "issuer":   "<可选；缺省 = <logto.endpoint>/oidc>",
  *     "jwksUrl":  "<可选；缺省 = <issuer>/jwks>" }
  *
  * `audience` / `scope` 的**取值**来自 Logto 侧 API resource 定义（身份权威）——本仓不硬编码、
  * 也不自造权限档名；实现只做**对表断言**。
  */
object PatAuth:

  /** 接受面配置：三条声明断言（iss / aud / scope 单档）+ JWKS 位置。 */
  final case class Config(issuer: String, jwksUrl: String, audience: String, scope: String)

  /** 🔴 吊销强制点 —— **候作者裁：A 到期失效 / B 刷新族短访问 / C 吊销名单轮询 ≤1h**。
    *
    * 本批（root 定案「只落三案共同件」）**只留此接口面**：默认实现不强制（A/B 两案下确无额外
    * 机制；C 落地时把 [[noRevocation]] 换成 ≤1h 名单查询即可，调用点已就位）。🔴 **禁**在作者
    * 回字母前实现任一案（禁轮询、禁名单、禁调短 TTL 参数冒充）。
    */
  trait RevocationGate:
    /** true = 该令牌已被吊销（拒绝）。 */
    def revoked(sub: String, jti: Option[String]): Boolean

  /** 默认 = 不强制（A/B 共同位；C 待裁）。 */
  val noRevocation: RevocationGate = (_, _) => false

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.gateway.pat")

  /** alg 白名单：Logto 现代密钥池是 EC P-384（ES384），旧池是 RSA（RS256）。白名单同时排除
    * 算法混淆（HS256 永不可能走到这里）——先例 = neblink-server `jwks.rs:99-104`。 */
  private val AllowedAlgs = Set("RS256", "ES384")
  private val CacheTtlSec = 3600L
  private val MinRefreshGapSec = 30L
  private val FetchTimeout = Duration.ofSeconds(5)

  // ── 配置解析 ──────────────────────────────────────────────────────────────────

  /** def（非 val）：`PathUtil.dataRoot` 可重定向（测试 / 隔离实例），val 会把它冻在对象初始化。 */
  private def configPath = PathUtil.dataRoot / "pat.json"

  def config: Option[Config] =
    try
      if !os.exists(configPath) then None
      else
        parser.parse(os.read(configPath)).toOption.flatMap { j =>
          val c = j.hcursor
          for
            audience <- c.get[String]("audience").toOption.map(_.trim).filter(_.nonEmpty)
            scope <- c.get[String]("scope").toOption.map(_.trim).filter(_.nonEmpty)
            issuer <- c.get[String]("issuer").toOption.filter(_.trim.nonEmpty).orElse(defaultIssuer)
            jwks <- c
              .get[String]("jwksUrl")
              .toOption
              .filter(_.trim.nonEmpty)
              .orElse(Some(trimSlash(issuer) + "/jwks"))
          yield Config(trimSlash(issuer), trimSlash(jwks), trimSlash(audience), scope)
        }
    catch case NonFatal(_) => None

  /** 缺省 issuer = 本机 `<dataRoot>/neblink/config.json` 的 `logto.endpoint` + `/oidc`。
    *
    * 单源纪律（先例 = neblink-server `jwks.rs:74-86` 的「一个 URL 决定两者」；其测试把 iss 钉为
    * `<endpoint>/oidc`）：iss 与 JWKS 位置不可能互相漂移；自托管差异用 `pat.json` 的两个可选键覆盖。
    */
  private def defaultIssuer: Option[String] =
    loadNeblinkConfig
      .flatMap(_.effectiveLogto)
      .map(_.endpoint.trim)
      .filter(_.nonEmpty)
      .map(ep => trimSlash(ep) + "/oidc")

  private def loadNeblinkConfig: Option[NeblinkConfig] =
    try
      val p = PathUtil.dataRoot / "neblink" / "config.json"
      if os.exists(p) then parser.decode[NeblinkConfig](os.read(p)).toOption
      else Some(NeblinkConfig())
    catch case NonFatal(_) => Some(NeblinkConfig())

  private def trimSlash(s: String): String = s.trim.replaceAll("/+$", "")

  // ── 生产入口（`checkAuth` 的薄委调目标） ──────────────────────────────────────

  private val holder = new AtomicReference[Option[(Config, Verifier)]](None)

  /** 轨 2 入口：轨 1 判否后被调用。任何情况下都不抛（fail-closed）。 */
  def accepts(raw: String): Boolean =
    if !looksLikeJws(raw) then false
    else
      config match
        case None => false
        case Some(c) =>
          try verifierFor(c).accepts(raw)
          catch case NonFatal(_) => false

  /** JWKS 缓存随配置存活；配置变更（或 dataRoot 切换）即换新实例。 */
  private def verifierFor(c: Config): Verifier =
    holder
      .updateAndGet { cur =>
        cur match
          case Some((cc, v)) if cc == c => cur
          case _ =>
            Some((c, new Verifier(c, httpJwksFetch, () => System.currentTimeMillis() / 1000L, noRevocation)))
      }
      .get
      ._2

  /** 廉价预筛：非 JWS 形态的令牌（含轨 1 的本机令牌）**不触发任何磁盘读或网络行为**。 */
  private[gateway] def looksLikeJws(raw: String): Boolean =
    raw.length >= 32 && raw.length <= 8192 && raw.count(_ == '.') == 2 && raw.split('.').forall(_.nonEmpty)

  // ── JWKS 取数（本文件唯一的网络面） ─────────────────────────────────────────

  private val httpClient = HttpClient.newBuilder().connectTimeout(FetchTimeout).build()

  /** 显式 timeout（连接 + 请求各 5s）；失败只回粗粒度原因（**不含**响应体，防回显）。 */
  private[gateway] def httpJwksFetch(url: String): Either[String, String] =
    try
      val req = HttpRequest.newBuilder(URI.create(url)).timeout(FetchTimeout).GET().build()
      val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      if resp.statusCode() == 200 then Right(resp.body()) else Left(s"HTTP ${resp.statusCode()}")
    catch case NonFatal(e) => Left(e.getClass.getSimpleName)

  // ── JWS / JWKS 解析（纯函数，可独立单测） ────────────────────────────────────

  /** 已解析的令牌材料——**仅进程内验签用**，绝不外泄、绝不入日志。 */
  private[gateway] final case class Jws(
    alg: String,
    kid: Option[String],
    iss: String,
    aud: List[String],
    scopes: Set[String],
    sub: String,
    jti: Option[String],
    exp: Long,
    nbf: Option[Long],
    signingInput: Array[Byte],
    signature: Array[Byte]
  )

  /** 结构/声明解析。缺 `alg` / `iss` / `exp` 即 None（**exp 必填**：D4 禁永不过期）。 */
  private[gateway] def parseJws(raw: String): Option[Jws] =
    try
      val parts = raw.split('.')
      if parts.length != 3 then None
      else
        for
          hb <- b64(parts(0))
          pb <- b64(parts(1))
          sb <- b64(parts(2))
          hj <- parser.parse(new String(hb, StandardCharsets.UTF_8)).toOption
          pj <- parser.parse(new String(pb, StandardCharsets.UTF_8)).toOption
          alg <- hj.hcursor.get[String]("alg").toOption
          exp <- pj.hcursor.get[Long]("exp").toOption
          iss <- pj.hcursor.get[String]("iss").toOption
        yield Jws(
          alg = alg,
          kid = hj.hcursor.get[String]("kid").toOption,
          iss = iss,
          aud = audOf(pj),
          scopes = scopesOf(pj),
          sub = pj.hcursor.get[String]("sub").toOption.getOrElse(""),
          jti = pj.hcursor.get[String]("jti").toOption,
          exp = exp,
          nbf = pj.hcursor.get[Long]("nbf").toOption,
          // 签名覆盖的字节 = 原文的两段（禁重新编码：重编码会引入不可控差异）
          signingInput = s"${parts(0)}.${parts(1)}".getBytes(StandardCharsets.UTF_8),
          signature = sb
        )
    catch case NonFatal(_) => None

  private def audOf(pj: Json): List[String] =
    val c = pj.hcursor.downField("aud")
    c.as[String].toOption.map(List(_)).orElse(c.as[List[String]].toOption).getOrElse(Nil)

  /** scope 集合：Logto 的 API resource 令牌把 scope 放成空格分隔字符串。 */
  private def scopesOf(pj: Json): Set[String] =
    pj.hcursor
      .get[String]("scope")
      .toOption
      .map(_.split("\\s+").filter(_.nonEmpty).toSet)
      .getOrElse(Set.empty)

  private[gateway] def b64(s: String): Option[Array[Byte]] =
    try
      val t = s.trim
      val padded = t + ("=" * ((4 - t.length % 4) % 4))
      Some(Base64.getUrlDecoder.decode(padded))
    catch case NonFatal(_) => None

  /** JWKS 文档 → kid→公钥。任何不可用项被跳过；**可用键为空即 None**（调用方 fail-closed）。 */
  private[gateway] def parseKeys(body: String): Option[Map[String, PublicKey]] =
    for
      json <- parser.parse(body).toOption
      arr <- json.hcursor.downField("keys").as[List[Json]].toOption
      m = arr.flatMap(oneKey).toMap
      if m.nonEmpty
    yield m

  private def oneKey(k: Json): Option[(String, PublicKey)] =
    val c = k.hcursor
    for
      kid <- c.get[String]("kid").toOption
      kty <- c.get[String]("kty").toOption
      use = c.get[String]("use").toOption
      alg = c.get[String]("alg").toOption
      if use.forall(_ == "sig")
      key <- decodeKey(c, kty, alg)
    yield (kid, key)

  /** alg 键缺席时按 kty 推断（Logto 会带 alg；自托管文档可能省）；其余一律跳过。 */
  private def decodeKey(c: HCursor, kty: String, alg: Option[String]): Option[PublicKey] =
    kty match
      case "RSA" if alg.forall(_ == "RS256") => rsaKey(c)
      case "EC" if alg.forall(_ == "ES384") => ecKey(c)
      case _ => None

  private def rsaKey(c: HCursor): Option[PublicKey] =
    for
      n <- c.get[String]("n").toOption.flatMap(b64)
      e <- c.get[String]("e").toOption.flatMap(b64)
      k <- attempt(
        KeyFactory
          .getInstance("RSA")
          .generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)))
      )
    yield k

  private def ecKey(c: HCursor): Option[PublicKey] =
    for
      x <- c.get[String]("x").toOption.flatMap(b64)
      y <- c.get[String]("y").toOption.flatMap(b64)
      params <- attempt {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(new ECGenParameterSpec("secp384r1"))
        ap.getParameterSpec(classOf[ECParameterSpec])
      }
      k <- attempt(
        KeyFactory
          .getInstance("EC")
          .generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), params))
      )
    yield k

  private def attempt[A](a: => A): Option[A] =
    try Some(a)
    catch case NonFatal(_) => None

  /** JWS 的 ECDSA 签名是裸 r‖s 拼接，JCA 要 DER。长度不被两整除 ⇒ None（拒）。 */
  private[gateway] def derFromRawEcdsa(raw: Array[Byte]): Option[Array[Byte]] =
    if raw.length == 0 || raw.length % 2 != 0 then None
    else
      val half = raw.length / 2
      val r = derInteger(new BigInteger(1, raw.slice(0, half)))
      val s = derInteger(new BigInteger(1, raw.slice(half, raw.length)))
      val body = r ++ s
      Some(Array[Byte](0x30) ++ derLength(body.length) ++ body)

  private def derInteger(v: BigInteger): Array[Byte] =
    val b = v.toByteArray // 二补码，带符号位时自带前导 0x00
    Array[Byte](0x02) ++ derLength(b.length) ++ b

  private def derLength(n: Int): Array[Byte] =
    if n < 0x80 then Array(n.toByte) else Array(0x81.toByte, n.toByte)

  // ── 验签器 ────────────────────────────────────────────────────────────────────

  /** 单配置的验签器。JWKS 缓存 + 有界刷新都在这里；取数与时钟可注入（离线单测用）。 */
  final class Verifier(
    cfg: Config,
    fetchJwks: String => Either[String, String],
    nowSec: () => Long,
    gate: RevocationGate
  ):

    private val lock = new Object
    private var keys: Map[String, PublicKey] = Map.empty
    private var fetchedAtSec: Long = 0L
    private var lastAttemptSec: Long = 0L

    /** 全部断言逐条：alg 白名单 → kid → 签名 → iss/aud/scope/exp/nbf → 吊销挂点。 */
    def accepts(raw: String): Boolean =
      parseJws(raw) match
        case None => false
        case Some(j) =>
          AllowedAlgs.contains(j.alg) &&
          j.kid.exists(kid => keyFor(kid).exists(k => signatureOk(j, k))) &&
          claimsOk(j) &&
          !gate.revoked(j.sub, j.jti)

    private def claimsOk(j: Jws): Boolean =
      val now = nowSec()
      val issOk = trimSlash(j.iss) == cfg.issuer
      val audOk = j.aud.exists(a => trimSlash(a) == cfg.audience)
      // D3 首发单档：令牌 scope 集合必须**恰为**配置的那一档（多一档即拒 ⇒ 细档须后端先定义权限表）
      val scopeOk = j.scopes == Set(cfg.scope)
      val timeOk = j.exp > now && j.nbf.forall(_ <= now)
      issOk && audOk && scopeOk && timeOk

    private def signatureOk(j: Jws, k: PublicKey): Boolean =
      try
        (j.alg, k) match
          case ("RS256", _: RSAPublicKey) =>
            val s = Signature.getInstance("SHA256withRSA")
            s.initVerify(k)
            s.update(j.signingInput)
            s.verify(j.signature)
          case ("ES384", _: ECPublicKey) =>
            derFromRawEcdsa(j.signature) match
              case None => false
              case Some(der) =>
                val s = Signature.getInstance("SHA384withECDSA")
                s.initVerify(k)
                s.update(j.signingInput)
                s.verify(der)
          // alg 与密钥类型不匹配（算法混淆尝试）⇒ 拒
          case _ => false
      catch case NonFatal(_) => false

    private def keyFor(kid: String): Option[PublicKey] =
      cached(kid) match
        case Some(k) => Some(k)
        case None =>
          refresh()
          cached(kid)

    private def cached(kid: String): Option[PublicKey] =
      lock.synchronized {
        if fetchedAtSec > 0L && nowSec() - fetchedAtSec <= CacheTtlSec then keys.get(kid) else None
      }

    /** 有界刷新：一次调用最多取数一次；两次取数间隔 ≥ 30s（取数在锁内 ⇒ 并发退化为串行等待，
      * 上界 = 5s timeout）。失败**保留旧缓存**并把本次判定交给调用者 fail-closed。 */
    private def refresh(): Unit =
      lock.synchronized {
        val now = nowSec()
        if now - lastAttemptSec >= MinRefreshGapSec then
          lastAttemptSec = now
          fetchJwks(cfg.jwksUrl) match
            case Right(body) =>
              parseKeys(body) match
                case Some(m) =>
                  keys = m
                  fetchedAtSec = now
                case None => logger.warn("PAT JWKS document carries no usable signature key; fail-closed")
            case Left(reason) => logger.warn(s"PAT JWKS fetch failed ($reason); fail-closed")
      }
end PatAuth
