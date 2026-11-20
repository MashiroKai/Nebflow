package nebflow.gateway.auth

import nebflow.gateway.*

import cats.effect.IO
import com.comcast.ip4s.{IpAddress, SocketAddress}
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Uri}

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 细粒度单元（auth 模块）：环回腿判据 + PAT 的 JWS 形态/结构解析。
 *
 * 这两个面此前只在路由级 spec（`GatewayAuthSpec` / `PatAuthSpec`）里被**间接**
 * 覆盖：环回腿靠「local-ticket 会话能否落座」整链验证，JWS 解析靠「无效 PAT 被
 * 拒」。本 spec 直接把每个判据当单元钉：输入 → 单一输出，不起服务器、不落盘、
 * 不发网络。
 */
class AuthUnitSpec extends CatsEffectSuite:

  // ── LoopbackLeg：唯一可信信号 = socket 对端地址 ─────────────────────────

  private def req(remote: Option[SocketAddress[IpAddress]]): Request[IO] =
    SpecRequests.withRemote(Request[IO](Method.GET, Uri.unsafeFromString("/x")), remote)

  test("LoopbackLeg: v4 / v6 / v4-mapped loopback sockets are the loopback leg"):
    assert(LoopbackLeg.isLoopback(req(Some(SpecRequests.LoopbackV4))), "127.0.0.1")
    assert(LoopbackLeg.isLoopback(req(Some(SpecRequests.LoopbackV6))), "::1")
    assert(LoopbackLeg.isLoopback(req(Some(SpecRequests.LoopbackMapped))), "::ffff:127.0.0.1")

  test("LoopbackLeg: LAN / public / remote-less requests are NOT the loopback leg"):
    assert(!LoopbackLeg.isLoopback(req(Some(SpecRequests.Lan))), "RFC1918 peer")
    assert(!LoopbackLeg.isLoopback(req(Some(SpecRequests.Public))), "public peer")
    assert(!LoopbackLeg.isLoopback(req(None)), "tunnel bridge (no connection info) is not proven local")

  // ── PAT：JWS 形态预筛 ──────────────────────────────────────────────────

  private def b64url(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  private def seg(json: String): String = b64url(json.getBytes(StandardCharsets.UTF_8))

  private val Header = seg("""{"alg":"ES384","kid":"k1"}""")

  private val Payload = seg(
    """{"iss":"https://issuer.example","aud":"api://stand","scope":"read write",""" +
      """"sub":"user-1","jti":"j-1","exp":4102444800,"nbf":1}"""
  )
  private val Sig = b64url(Array[Byte](1, 2, 3, 4))
  private val Token = s"$Header.$Payload.$Sig"

  test("looksLikeJws: three non-empty dotted segments inside the length window"):
    assert(PatAuth.looksLikeJws(Token))
    assert(!PatAuth.looksLikeJws("opaque-file-token-abc123"), "track-1 local token has no dots")
    assert(!PatAuth.looksLikeJws(s"$Header.$Payload"), "two segments is not a JWS")
    assert(!PatAuth.looksLikeJws(s"$Token.extra"), "four segments is not a JWS")
    assert(!PatAuth.looksLikeJws(s"$Header..$Sig"), "empty payload segment is not a JWS")
    assert(!PatAuth.looksLikeJws("x" * 31), "too short to be a JWS")
    assert(!PatAuth.looksLikeJws("x" * 8193), "too long to be a JWS")

  test("parseJws: alg/kid/iss/aud/scope/sub/exp/nbf come out of the two JSON segments"):
    val parsed = PatAuth.parseJws(Token)
    assertEquals(parsed.map(_.alg), Some("ES384"))
    assertEquals(parsed.flatMap(_.kid), Some("k1"))
    assertEquals(parsed.map(_.iss), Some("https://issuer.example"))
    assertEquals(parsed.map(_.aud), Some(List("api://stand")))
    assertEquals(parsed.map(_.scopes), Some(Set("read", "write")))
    assertEquals(parsed.map(_.sub), Some("user-1"))
    assertEquals(parsed.flatMap(_.jti), Some("j-1"))
    assertEquals(parsed.map(_.exp), Some(4102444800L))
    assertEquals(parsed.flatMap(_.nbf), Some(1L))

  test("parseJws: signing input is the raw header.payload bytes (never re-encoded)"):
    val parsed = PatAuth.parseJws(Token).getOrElse(fail("token must parse"))
    assertEquals(new String(parsed.signingInput, StandardCharsets.UTF_8), s"$Header.$Payload")
    assertEquals(parsed.signature.toList, List[Byte](1, 2, 3, 4))

  test("parseJws: exp and iss are mandatory claims (D4: no never-expiring token)"):
    val noExp = s"$Header.${seg("""{"iss":"https://issuer.example"}""")}.$Sig"
    assertEquals(PatAuth.parseJws(noExp), None)
    val noIss = s"$Header.${seg("""{"exp":4102444800}""")}.$Sig"
    assertEquals(PatAuth.parseJws(noIss), None)

  test("parseJws: malformed input is None, never an exception"):
    assertEquals(PatAuth.parseJws(s"$Header.$Payload"), None, "wrong segment count")
    assertEquals(PatAuth.parseJws(s"not-base64.!!!.$Sig"), None, "undecodable header")
    assertEquals(PatAuth.parseJws(s"$Header.${b64url("{".getBytes)}.$Sig"), None, "payload is not JSON")

  test("parseJws: aud accepts an array and a missing scope yields an empty set"):
    val noKidHeader = seg("""{"alg":"ES384"}""")
    val payload = seg("""{"iss":"i","aud":["a","b"],"sub":"s","exp":1}""")
    val parsed = PatAuth.parseJws(s"$noKidHeader.$payload.$Sig").getOrElse(fail("must parse"))
    assertEquals(parsed.aud, List("a", "b"))
    assertEquals(parsed.scopes, Set.empty[String])
    assertEquals(parsed.kid, None)

  test("b64: url-safe unpadded input decodes; garbage is None"):
    assertEquals(PatAuth.b64("AQAB").map(_.toList), Some(List[Byte](1, 0, 1)))
    assertEquals(PatAuth.b64("!!!"), None)

end AuthUnitSpec
