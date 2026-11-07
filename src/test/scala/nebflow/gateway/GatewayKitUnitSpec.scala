package nebflow.gateway

import cats.effect.IO
import munit.FunSuite
import org.http4s.{Method, Request, Uri}

/**
 * 细粒度单元（gateway 包根）：共享 test kit `SpecRequests` 的 origin 矩阵 +
 * 重排后的模块布局钉子。
 *
 * `SpecRequests` 被 12 个路由 spec 当作 origin 矩阵地基（stage C 闸），但此前
 * 没有自己的直接断言——它一错，路由 spec 会以「谜之 403」的形式集体漂。这里
 * 直接钉「构造 → remoteAddr → 腿判据」的逐臂读数，并给 8 个子包一个显式布局锁
 * （新增/改名子包必须同时改这条断言，等于一次有意识的裁定）。
 */
class GatewayKitUnitSpec extends FunSuite:

  private def base: Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString("/x"))

  test("withRemote(None): the request stays remote-less (tunnel-bridge shape)"):
    assertEquals(SpecRequests.withRemote(base, None).remoteAddr, None)

  test("withRemote(Some): fills the ConnectionInfo slot with the given peer"):
    def peer(a: com.comcast.ip4s.SocketAddress[com.comcast.ip4s.IpAddress]) =
      SpecRequests.withRemote(base, Some(a)).remoteAddr.map(_.toString)
    assertEquals(peer(SpecRequests.LoopbackV4), Some("127.0.0.1"))
    assertEquals(peer(SpecRequests.Lan), Some("192.168.1.50"))
    assertEquals(peer(SpecRequests.Public), Some("203.0.113.7"))

  test("origin matrix: only the loopback arms (incl. v4-mapped) seat the local leg"):
    assert(LoopbackLeg.isLoopback(SpecRequests.withRemote(base, Some(SpecRequests.LoopbackV4))))
    assert(LoopbackLeg.isLoopback(SpecRequests.withRemote(base, Some(SpecRequests.LoopbackV6))))
    assert(LoopbackLeg.isLoopback(SpecRequests.withRemote(base, Some(SpecRequests.LoopbackMapped))))
    assert(!LoopbackLeg.isLoopback(SpecRequests.withRemote(base, Some(SpecRequests.Lan))))
    assert(!LoopbackLeg.isLoopback(SpecRequests.withRemote(base, Some(SpecRequests.Public))))
    assert(!LoopbackLeg.isLoopback(SpecRequests.withRemote(base, None)))

  test("origin matrix: v4-mapped collapses to dotted v4 under the guard's normalizer"):
    val mapped = com.comcast.ip4s.IpAddress.fromString("::ffff:127.0.0.1").getOrElse(fail("bad literal"))
    assertEquals(ConnGuard.normalizeIp(Some(mapped)), "127.0.0.1")
    assertEquals(ConnGuard.normalizeIp(Some(com.comcast.ip4s.Ipv6Address.Loopback)), "::1")

  test("module layout: eight subpackages under nebflow.gateway, everything else at the root"):
    val root = os.pwd / "src" / "test" / "scala" / "nebflow" / "gateway"
    val dirs = os.list(root).filter(os.isDir).map(_.last).toSet
    assertEquals(dirs, Set("auth", "console", "ws", "static", "nf", "session", "rest", "infra"))
    val rootFiles = os.list(root).filter(os.isFile).map(_.last).toSet
    assert(rootFiles.contains("SpecRequests.scala"), "test kit stays at the package root")
    assert(rootFiles.contains("A1FanoutProbe.scala") && rootFiles.contains("A2A4HttpProbe.scala"))

end GatewayKitUnitSpec
