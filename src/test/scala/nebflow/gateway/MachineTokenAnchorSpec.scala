package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.nio.charset.StandardCharsets
import java.security.{KeyPair, KeyPairGenerator, Signature}
import java.util.Base64

/** 接受面 · **轨 1**（本机文件令牌）指纹 spec —— 本批的「逐字不变」机械钉。
  *
  * 钉死三件事：
  *  1. 信任锚语义未变：`Auth.validateToken` 只认以 `MessageDigest.isEqual` 比对的本机令牌；
  *     一个**真签名**的 Logto 式 PAT 与 `nbfl_` 令牌在这里都是 false（轨 1 **永不**接受 PAT）。
  *  2. 受保护端点（`GET /neblink/status`）在**无凭证**下仍是 403，在**本机令牌**下仍落到
  *     `withNeblink` 的未启用分支（404）——与 `patbackend-impl` 基线逐字一致。
  *  3. 轨 2 的引入不得改变以上两点（配合 `git diff <基线> -- gateway/auth.scala` 必空 +
  *     `checkAuth` 只多一行薄委调）。
  */
class MachineTokenAnchorSpec extends CatsEffectSuite:

  private val MachineToken = "machine-token-anchor-1234"

  private def b64u(b: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(b)

  /** 自造密钥对签一枚 Logto 式 PAT（真签名；轨 1 的锚对它必须回 false）。 */
  private def signedPat(): String =
    val g = KeyPairGenerator.getInstance("RSA")
    g.initialize(2048)
    val kp: KeyPair = g.generateKeyPair()
    val header = Json.obj("alg" -> "RS256".asJson, "kid" -> "kid-1".asJson, "typ" -> "JWT".asJson).noSpaces
    val claims = Json
      .obj(
        "iss" -> "https://auth.nebflow.space/oidc".asJson,
        "aud" -> "https://api.nebflow.space/pat".asJson,
        "scope" -> "account".asJson,
        "sub" -> "logto-user-1".asJson,
        "exp" -> (System.currentTimeMillis() / 1000L + 3600L).asJson
      )
      .noSpaces
    val input = s"${b64u(header.getBytes(StandardCharsets.UTF_8))}.${b64u(claims.getBytes(StandardCharsets.UTF_8))}"
    val s = Signature.getInstance("SHA256withRSA")
    s.initSign(kp.getPrivate)
    s.update(input.getBytes(StandardCharsets.UTF_8))
    s"$input.${b64u(s.sign())}"

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
      token = MachineToken,
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

  test("T1: 轨 1 信任锚只认本机令牌 —— 真签名的 Logto PAT / nbfl_ 令牌 / 空串均被锚拒") {
    val pat = signedPat()
    assertEquals(Auth.validateToken(MachineToken, MachineToken), true)
    assertEquals(Auth.validateToken(pat, MachineToken), false)
    assertEquals(Auth.validateToken("nbfl_" + "a" * 64, MachineToken), false)
    assertEquals(Auth.validateToken("", MachineToken), false)
  }

  test("T2: 受保护端点 —— 无凭证 ⇒ 403；本机令牌 ⇒ 404（未启用分支），与基线一致") {
    for
      none <- protectedStatus(None)
      machine <- protectedStatus(Some(MachineToken))
    yield
      assertEquals(none.code, 403)
      assertEquals(machine.code, 404)
  }
end MachineTokenAnchorSpec
