package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig}
import org.http4s.*
import org.http4s.dsl.io.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, PrivateKey, Signature}
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** 接受面 · **轨 2**（Logto 签发 PAT 的 JWKS 离线验签）的验收 spec。
  *
  * 证据构造（🔴 不依赖站点后端、不依赖真实 Logto）：自造密钥对 + 本机回环 mock JWKS
  * （`com.sun.net.httpserver`，先例 `Mvp2DeviceRoutesSpec`）+ 自签 JWT。
  *
  * 覆盖：
  *  A. 合法 PAT（RS256 / ES384）⇒ 放行
  *  B. 四变异负控：错签 / 过期 / 错 audience / **JWKS 不可达 ⇒ fail-closed 必拒**
  *  C. 断言面：iss / scope 单档 / nbf / 缺 exp / 缺 kid / alg 混淆（HS256）
  *  D. 离线硬约束：JWKS 取数**不是**每请求一次（缓存命中 ⇒ 取数计数不变）+ 非 JWS 令牌零取数
  *  E. 受保护端点级读数：`GET /neblink/status` —— 合法 PAT ⇒ 认证通过（404 = 落到 withNeblink
  *     的未启用分支），面关闭（无 pat.json）/ JWKS 不可达 / 错 aud ⇒ 403（与轨 1 形态逐字相同）
  */
class PatAuthSpec extends CatsEffectSuite:

  private val Audience = "https://api.nebflow.space/pat"
  private val Scope = "account"
  private val Sub = "logto-user-1"

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null
  private var server: HttpServer = null
  private var up: Boolean = true

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-patauth"))
    PathUtil.setDataRoot(tmp)
    up = true

  override def afterEach(context: AfterEach): Unit =
    stopJwks()
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def respond(ex: HttpExchange, status: Int, body: String): Unit =
    ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
    ex.getRequestBody.close()
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()

  /** 本机回环 JWKS 桩（`:0` 随机端口）。`doc` 为 by-name ⇒ 支持轮换；`up=false` ⇒ 503。 */
  private def startJwks(doc: => String): String =
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/oidc/jwks",
      ex =>
        if up then respond(ex, 200, doc)
        else respond(ex, 503, """{"error":"down"}""")
    )
    server.start()
    s"http://127.0.0.1:${server.getAddress.getPort}"

  private def stopJwks(): Unit =
    if server != null then
      server.stop(0)
      server = null

  /** 面配置落盘：`<dataRoot>/pat.json` + `<dataRoot>/neblink/config.json` 的 logto.endpoint。
    * iss 与 JWKS 位置**都**从这一个 endpoint 派生（单源纪律，顺带被本 spec 钉住）。 */
  private def writeFace(base: String): Unit =
    os.write.over(
      tmp / "pat.json",
      Json.obj("audience" -> Audience.asJson, "scope" -> Scope.asJson).noSpaces,
      createFolders = true
    )
    os.write.over(
      tmp / "neblink" / "config.json",
      Json.obj("logto" -> Json.obj("endpoint" -> base.asJson)).noSpaces,
      createFolders = true
    )

  // ── 密钥 / JWK / JWT 构造 ───────────────────────────────────────────────────

  private def rsaPair(): KeyPair =
    val g = KeyPairGenerator.getInstance("RSA")
    g.initialize(2048)
    g.generateKeyPair()

  private def ecPair(): KeyPair =
    val g = KeyPairGenerator.getInstance("EC")
    g.initialize(new ECGenParameterSpec("secp384r1"))
    g.generateKeyPair()

  private def b64u(b: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(b)

  private def unsigned(b: java.math.BigInteger): Array[Byte] =
    val a = b.toByteArray
    if a.length > 1 && a(0) == 0 then a.tail else a

  private def fixed(b: java.math.BigInteger, size: Int): Array[Byte] =
    val a = unsigned(b)
    Array.fill[Byte](size - a.length)(0) ++ a

  private def rsaJwk(kid: String, pub: RSAPublicKey): String =
    Json
      .obj(
        "kty" -> "RSA".asJson,
        "kid" -> kid.asJson,
        "alg" -> "RS256".asJson,
        "use" -> "sig".asJson,
        "n" -> b64u(unsigned(pub.getModulus)).asJson,
        "e" -> b64u(unsigned(pub.getPublicExponent)).asJson
      )
      .noSpaces

  private def ecJwk(kid: String, pub: ECPublicKey): String =
    Json
      .obj(
        "kty" -> "EC".asJson,
        "kid" -> kid.asJson,
        "alg" -> "ES384".asJson,
        "use" -> "sig".asJson,
        "crv" -> "P-384".asJson,
        "x" -> b64u(fixed(pub.getW.getAffineX, 48)).asJson,
        "y" -> b64u(fixed(pub.getW.getAffineY, 48)).asJson
      )
      .noSpaces

  private def jwksDoc(keys: String*): String =
    Json.obj("keys" -> Json.fromValues(keys.map(k => io.circe.parser.parse(k).toOption.get))).noSpaces

  private def jcaSign(alg: String, priv: PrivateKey, input: String): Array[Byte] =
    val s = Signature.getInstance(alg)
    s.initSign(priv)
    s.update(input.getBytes(StandardCharsets.UTF_8))
    s.sign()

  /** JCA 的 ECDSA 签名是 DER；JWS 要裸 r‖s（本 spec 侧与被测侧互逆换算）。 */
  private def derToRaw(der: Array[Byte], size: Int): Array[Byte] =
    def readInt(start: Int): (Array[Byte], Int) =
      require(der(start) == 0x02, "DER INTEGER expected")
      val l0 = der(start + 1) & 0xff
      val (len, hdr) = if l0 < 0x80 then (l0, 2) else (der(start + 2) & 0xff, 3)
      val v = der.slice(start + hdr, start + hdr + len).dropWhile(_ == 0)
      (Array.fill[Byte](size - v.length)(0) ++ v, start + hdr + len)
    require(der(0) == 0x30, "DER SEQUENCE expected")
    val l0 = der(1) & 0xff
    val bodyStart = if l0 < 0x80 then 2 else 3
    val (r, afterR) = readInt(bodyStart)
    val (s, _) = readInt(afterR)
    r ++ s

  private def mint(alg: String, kid: String, priv: PrivateKey, claims: Json): String =
    val header = Json.obj("alg" -> alg.asJson, "kid" -> kid.asJson, "typ" -> "JWT".asJson).noSpaces
    val input =
      s"${b64u(header.getBytes(StandardCharsets.UTF_8))}.${b64u(claims.noSpaces.getBytes(StandardCharsets.UTF_8))}"
    val sig = alg match
      case "RS256" => jcaSign("SHA256withRSA", priv, input)
      case "ES384" => derToRaw(jcaSign("SHA384withECDSA", priv, input), 48)
      case "HS256" =>
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(new javax.crypto.spec.SecretKeySpec("public-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        mac.doFinal(input.getBytes(StandardCharsets.UTF_8))
      case other => throw new IllegalArgumentException(other)
    s"$input.${b64u(sig)}"

  private def claimsOf(
    iss: String,
    aud: String = Audience,
    scope: String = Scope,
    exp: Long = System.currentTimeMillis() / 1000L + 3600,
    nbf: Option[Long] = None,
    sub: String = Sub
  ): Json =
    val core = Json.obj(
      "iss" -> iss.asJson,
      "aud" -> aud.asJson,
      "scope" -> scope.asJson,
      "sub" -> sub.asJson,
      "jti" -> "pat-1".asJson,
      "iat" -> (System.currentTimeMillis() / 1000L).asJson,
      "exp" -> exp.asJson
    )
    nbf.fold(core)(n => core.deepMerge(Json.obj("nbf" -> n.asJson)))

  // ── Verifier 级读数（离线：取数函数注入，`http://127.0.0.1:1` 为死端口不产流量） ──

  private val DeadBase = "http://127.0.0.1:1"

  private def cfgFor(base: String): PatAuth.Config =
    PatAuth.Config(issuer = s"$base/oidc", jwksUrl = s"$base/oidc/jwks", audience = Audience, scope = Scope)

  private def fetchCounting(doc: => String, hit: AtomicInteger): String => Either[String, String] =
    _ =>
      hit.incrementAndGet()
      Right(doc)

  private def verifierWith(base: String, doc: => String, hit: AtomicInteger): PatAuth.Verifier =
    new PatAuth.Verifier(
      cfgFor(base),
      fetchCounting(doc, hit),
      () => System.currentTimeMillis() / 1000L,
      PatAuth.noRevocation
    )

  private def rsaDoc(kid: String, kp: KeyPair): String =
    jwksDoc(rsaJwk(kid, kp.getPublic.asInstanceOf[RSAPublicKey]))

  test("A1: 合法 RS256 PAT ⇒ 放行") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc"))
    assertEquals(v.accepts(pat), true)
  }

  test("A2: 合法 ES384 PAT ⇒ 放行（Logto 现代密钥池 = EC P-384）") {
    val kp = ecPair()
    val v = verifierWith(
      DeadBase,
      jwksDoc(ecJwk("kid-ec", kp.getPublic.asInstanceOf[ECPublicKey])),
      new AtomicInteger(0)
    )
    val pat = mint("ES384", "kid-ec", kp.getPrivate, claimsOf(s"$DeadBase/oidc"))
    assertEquals(v.accepts(pat), true)
  }

  test("B1 变异负控·错签（换密钥签，kid 指向已公布的另一把键）⇒ 必拒") {
    val good = rsaPair()
    val evil = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", good), new AtomicInteger(0))
    val pat = mint("RS256", "kid-1", evil.getPrivate, claimsOf(s"$DeadBase/oidc"))
    assertEquals(v.accepts(pat), false)
  }

  test("B2 变异负控·过期（exp 已过）⇒ 必拒") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val expired = System.currentTimeMillis() / 1000L - 3600L
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc", exp = expired))
    assertEquals(v.accepts(pat), false)
  }

  test("B3 变异负控·错 audience ⇒ 必拒") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc", aud = "https://evil.example/pat"))
    assertEquals(v.accepts(pat), false)
  }

  test("B4 变异负控·JWKS 取数失败 ⇒ fail-closed 必拒（禁放行）") {
    val kp = rsaPair()
    val v = new PatAuth.Verifier(
      cfgFor(DeadBase),
      _ => Left("ConnectException"),
      () => System.currentTimeMillis() / 1000L,
      PatAuth.noRevocation
    )
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc"))
    assertEquals(v.accepts(pat), false)
  }

  test("C1: 错 issuer ⇒ 拒") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf("https://evil.example/oidc"))
    assertEquals(v.accepts(pat), false)
  }

  test("C2: scope 非单档（多一档）⇒ 拒（D3 首发单档）") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc", scope = s"$Scope admin"))
    assertEquals(v.accepts(pat), false)
  }

  test("C3: nbf 未到 ⇒ 拒") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val future = System.currentTimeMillis() / 1000L + 600L
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc", nbf = Some(future)))
    assertEquals(v.accepts(pat), false)
  }

  test("C4: 缺 exp（禁永不过期）⇒ 拒") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val noExp = Json.obj(
      "iss" -> s"$DeadBase/oidc".asJson,
      "aud" -> Audience.asJson,
      "scope" -> Scope.asJson,
      "sub" -> Sub.asJson
    )
    val pat = mint("RS256", "kid-1", kp.getPrivate, noExp)
    assertEquals(v.accepts(pat), false)
  }

  test("C5: alg=HS256（算法混淆）⇒ 拒") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val pat = mint("HS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc"))
    assertEquals(v.accepts(pat), false)
  }

  test("C6: 缺 kid ⇒ 拒") {
    val kp = rsaPair()
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), new AtomicInteger(0))
    val header = Json.obj("alg" -> "RS256".asJson, "typ" -> "JWT".asJson).noSpaces
    val input =
      s"${b64u(header.getBytes(StandardCharsets.UTF_8))}.${b64u(claimsOf(s"$DeadBase/oidc").noSpaces.getBytes(StandardCharsets.UTF_8))}"
    val pat = s"$input.${b64u(jcaSign("SHA256withRSA", kp.getPrivate, input))}"
    assertEquals(v.accepts(pat), false)
  }

  test("C7: 段数不对 / 空串 / 非 base64 ⇒ 拒（不抛）") {
    val v = verifierWith(DeadBase, jwksDoc(), new AtomicInteger(0))
    assertEquals(v.accepts(""), false)
    assertEquals(v.accepts("not-a-jwt"), false)
    assertEquals(v.accepts("aaa.bbb"), false)
    assertEquals(v.accepts("!!!.???.###"), false)
  }

  test("D1: 密钥轮换 —— 陌生 kid 触发一次有界刷新后放行") {
    val kp1 = rsaPair()
    val kp2 = rsaPair()
    val hit = new AtomicInteger(0)
    var doc = rsaDoc("kid-1", kp1)
    val v = verifierWith(DeadBase, doc, hit)
    val t1 = mint("RS256", "kid-1", kp1.getPrivate, claimsOf(s"$DeadBase/oidc"))
    assertEquals(v.accepts(t1), true)
    val before = hit.get()
    doc = jwksDoc(
      rsaJwk("kid-1", kp1.getPublic.asInstanceOf[RSAPublicKey]),
      rsaJwk("kid-2", kp2.getPublic.asInstanceOf[RSAPublicKey])
    )
    val t2 = mint("RS256", "kid-2", kp2.getPrivate, claimsOf(s"$DeadBase/oidc"))
    assertEquals(v.accepts(t2), true)
    assertEquals(hit.get() - before, 1)
  }

  test("D2: 离线硬约束 —— 缓存命中后连续验签不再取数（无每请求 Logto 调用）") {
    val kp = rsaPair()
    val hit = new AtomicInteger(0)
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp), hit)
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc"))
    for _ <- 1 to 5 do assertEquals(v.accepts(pat), true)
    assertEquals(hit.get(), 1)
  }

  test("D3: 非 JWS 形态（本机令牌样式 / nbfl_ 前缀）⇒ 拒且零取数") {
    val hit = new AtomicInteger(0)
    val v = verifierWith(DeadBase, jwksDoc(), hit)
    assertEquals(v.accepts("ZGVhZGJlZWYtMzJiLXJhbmRvbS1tYWNoaW5lLXRva2VuLXZhbHVl"), false)
    assertEquals(v.accepts("nbfl_" + "a" * 64), false)
    assertEquals(hit.get(), 0)
  }

  test("D4: JWKS 文档不可用（坏 JSON / keys 空 / 无可用键）⇒ 拒") {
    val kp = rsaPair()
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$DeadBase/oidc"))
    assertEquals(verifierWith(DeadBase, "<html>oops</html>", new AtomicInteger(0)).accepts(pat), false)
    assertEquals(verifierWith(DeadBase, """{"keys":[]}""", new AtomicInteger(0)).accepts(pat), false)
    assertEquals(
      verifierWith(DeadBase, jwksDoc("""{"kty":"oct","kid":"kid-1","k":"AAAA"}"""), new AtomicInteger(0)).accepts(pat),
      false
    )
  }

  test("D5: 有界刷新 —— 30s 下限内重复陌生 kid 不再取数（不可变成取数放大器）") {
    val kp1 = rsaPair()
    val kp2 = rsaPair()
    val hit = new AtomicInteger(0)
    val v = verifierWith(DeadBase, rsaDoc("kid-1", kp1), hit)
    val unknown = mint("RS256", "kid-2", kp2.getPrivate, claimsOf(s"$DeadBase/oidc"))
    for _ <- 1 to 5 do assertEquals(v.accepts(unknown), false)
    assertEquals(hit.get(), 1)
  }

  // ── 受保护端点级读数（真 HTTP 回环，面由 dataRoot 配置驱动） ────────────────

  private def resources: SharedResources = SharedResources(
    llm = null,
    dispatcher = null,
    sessionStore = null,
    projectRoot = os.pwd,
    thinkingConfigRef = null,
    rateLimiter = null,
    fileChangeTracker = null,
    contextWindow = 100_000,
    agentLibrary = null,
    taskStore = null,
    historyArchiver = null,
    fileLockManager = null,
    sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
    providerRegistry = null,
    healthMonitor = null,
    actorSystem = null,
    voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false)
  )

  private def routes: RestApiRoutes =
    new RestApiRoutes(
      token = "machine-token-xyz",
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = resources,
      sessionStore = null,
      wsRoutes = null
    )

  private def protectedStatus(bearer: Option[String]): IO[Status] =
    val req = Request[IO](Method.GET, Uri.unsafeFromString("/neblink/status"))
    val req2 = bearer.fold(req)(t => req.withHeaders(Headers("Authorization" -> s"Bearer $t")))
    routes.routes(req2).value.map(_.getOrElse(fail("route fell through")).status)

  test("E1: 受保护端点 —— 合法 PAT ⇒ 认证通过（404 = 落到 withNeblink 未启用分支，非 403）") {
    val kp = rsaPair()
    var doc = ""
    val base = startJwks(doc)
    doc = rsaDoc("kid-1", kp)
    writeFace(base)
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$base/oidc"))
    protectedStatus(Some(pat)).map(s => assertEquals(s.code, 404))
  }

  test("E2: 受保护端点 —— JWKS 不可达（桩 503）⇒ fail-closed 403（与轨 1 形态逐字相同）") {
    val kp = rsaPair()
    var doc = ""
    val base = startJwks(doc)
    doc = rsaDoc("kid-1", kp)
    writeFace(base)
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$base/oidc"))
    up = false
    protectedStatus(Some(pat)).map(s => assertEquals(s.code, 403))
  }

  test("E3: 受保护端点 —— 面关闭（无 pat.json）⇒ 403，行为与本批之前一致") {
    val kp = rsaPair()
    val base = startJwks(rsaDoc("kid-1", kp))
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$base/oidc"))
    protectedStatus(Some(pat)).map(s => assertEquals(s.code, 403))
  }

  test("E4: 受保护端点 —— 合法 PAT + 错 audience ⇒ 403") {
    val kp = rsaPair()
    var doc = ""
    val base = startJwks(doc)
    doc = rsaDoc("kid-1", kp)
    writeFace(base)
    val pat = mint("RS256", "kid-1", kp.getPrivate, claimsOf(s"$base/oidc", aud = "https://evil.example/pat"))
    protectedStatus(Some(pat)).map(s => assertEquals(s.code, 403))
  }
