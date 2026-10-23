package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.{HttpRoutes, Method, Request, Status, Uri}
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * #750（2026-10-23，nf-file 媒体面高载 503 硬失败批）：文件腿过载准入闸
 * （[[NfServeGate]]）的路由级测试。
 *
 * 作者实测（2026-10-22 18:52，五 JVM 并行构建压载）：文档里点图报
 * 「Failed to render: HTTP 503」——过载时上游先于网关放弃回裸 503。修后契约：
 *
 *   - 预算内：行为与修前逐字节一致（200/404/401/400 家族原样）；
 *   - 超预算：`429 + Retry-After + X-Nf-Reason: overloaded + no-store`，
 *     **永不裸 503**；
 *   - 预算的释放点 = 响应体终点（body 未 drain 的响应仍持有一个在飞位——
 *     预算约束的是并发字节推送，不是 handler 返回时刻）；
 *   - 400/401 参数快检不占预算（闸位在快检之后）；
 *   - 零 wall-clock：闸内没有任何时钟读数（形态测试 = 全部判定由响应状态
 *     驱动，无一处 sleep/deadline）。
 */
class NfServeGateSpec extends CatsEffectSuite:

  private val store = NfTicketStore.unsafeCreate(1800)

  /** Injected C1-5 policy: nothing under test lives in P1/P3 (NfFileRoutesSpec 同款). */
  private val policy = NfFilePolicy.NfPathPolicy(
    java.nio.file.Paths.get("/nonexistent-nebflow-data-root"),
    java.nio.file.Paths.get("/nonexistent-nebflow-workspace"),
    Set.empty
  )

  /** One seeded file in its own temp dir; cleaned up after `test`. */
  private def withSeedFile[A](name: String, bytes: Array[Byte])(test: Path => A): A =
    val tmp = Files.createTempDirectory("nebflow-nfservegate-test")
    val file = tmp.resolve(name)
    Files.write(file, bytes)
    try test(file)
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(tmp)

  /** A ticket bound to the realpath of an existing file (R4 = unlimited uses). */
  private def ticketFor(p: Path): String =
    store.issue("spec", p.toRealPath().toString).unsafeRunSync().token

  /** The request as a pure IO (concurrency-safe — no nested unsafeRunSync). */
  private def request(route: HttpRoutes[IO], target: String): IO[org.http4s.Response[IO]] =
    route(Request[IO](Method.GET, Uri.unsafeFromString(target))).value.flatMap {
      case None       => IO.raiseError(new IllegalStateException("nf-file route did not match"))
      case Some(resp) => IO.pure(resp)
    }

  private def get(route: HttpRoutes[IO], target: String): org.http4s.Response[IO] =
    request(route, target).unsafeRunSync()

  /** Run the response's body to completion — the permit's release point. */
  private def drain(resp: org.http4s.Response[IO]): String =
    resp.bodyText.compile.string.unsafeRunSync()

  private def header(resp: org.http4s.Response[IO], name: String): Option[String] =
    resp.headers.get(CIString(name)).map(_.head.value)

  private def fileUrl(f: Path): String =
    s"/api/nf-file?path=$f&ticket=${ticketFor(f)}"

  test("over-budget request answers 429 + Retry-After + X-Nf-Reason: overloaded (never a bare 503)") {
    withSeedFile("shot.png", Array[Byte](1, 2, 3)) { f =>
      val gate = NfServeGate(1)
      val route = NfFileRoutes.nfFileRoutes(store, policy, gate = gate)
      val url = fileUrl(f)
      // Hole #1 open (body undrained ⇒ permit still held — the budget covers
      // the byte push, not the handler return).
      val resp1 = get(route, url)
      assertEquals(resp1.status, Status.Ok)
      // Over budget → the structured refusal, exactly once shape.
      val resp2 = get(route, url)
      assertEquals(resp2.status, Status.TooManyRequests)
      assertEquals(header(resp2, "Retry-After"), Some(NfServeGate.RetryAfterSeconds))
      assertEquals(header(resp2, "X-Nf-Reason"), Some("overloaded"))
      assertEquals(header(resp2, "Cache-Control"), Some("no-store"))
      val body = drain(resp2)
      assert(body.contains("server busy"), s"refusal body must say why: $body")
      // Drain #1 → permit returns → the next request is admitted again.
      drain(resp1)
      val resp3 = get(route, url)
      assertEquals(resp3.status, Status.Ok)
      drain(resp3)
      assertEquals(gate.inFlightNow.unsafeRunSync(), 0, "every admitted permit must be back")
    }
  }

  test("concurrent burst over the budget: exactly `max` admissions, the rest 429, no leak after drain") {
    withSeedFile("shot.png", Array[Byte](1, 2, 3)) { f =>
      val gate = NfServeGate(2)
      val route = NfFileRoutes.nfFileRoutes(store, policy, gate = gate)
      val url = fileUrl(f)
      val burst = List.fill(5)(request(route, url)).parSequence.unsafeRunSync()
      assertEquals(burst.count(_.status == Status.Ok), 2, "exactly max admissions")
      assertEquals(burst.count(_.status == Status.TooManyRequests), 3, "the rest refused, never 503")
      assertEquals(burst.count(_.status == Status.ServiceUnavailable), 0)
      // Drain everything → the counter settles at 0 → admission works again.
      burst.foreach(r => if r.status == Status.Ok then drain(r))
      val after = get(route, url)
      assertEquals(after.status, Status.Ok)
      drain(after)
      assertEquals(gate.inFlightNow.unsafeRunSync(), 0)
    }
  }

  test("400 (missing path) and 401 (missing ticket) bypass the budget") {
    withSeedFile("shot.png", Array[Byte](1, 2, 3)) { f =>
      val gate = NfServeGate(1)
      val route = NfFileRoutes.nfFileRoutes(store, policy, gate = gate)
      // The one permit is HELD by this undrained response.
      val resp1 = get(route, fileUrl(f))
      assertEquals(resp1.status, Status.Ok)
      // Param quick-checks answer from OUTSIDE the gate even at full budget.
      assertEquals(get(route, "/api/nf-file").status, Status.BadRequest)
      assertEquals(get(route, s"/api/nf-file?path=$f").status, Status.Unauthorized)
      drain(resp1)
    }
  }

  test("permitted leg keeps its pre-#750 behaviour byte-for-byte (404 not-found, byte-identical 200)") {
    withSeedFile("shot.png", Array[Byte](1, 2, 3)) { f =>
      val route = NfFileRoutes.nfFileRoutes(store, policy, gate = NfServeGate(4))
      // Byte-identical serving under the gate.
      val ok = get(route, fileUrl(f))
      assertEquals(ok.status, Status.Ok)
      assertEquals(drain(ok), new String(Array[Byte](1, 2, 3), StandardCharsets.ISO_8859_1))
      // A minted-but-missing path keeps the not-found shape (NfFileRoutesSpec A13 家族).
      val missing = get(route, s"/api/nf-file?path=/tmp/definitely-missing-750.png&ticket=${ticketFor(f)}")
      assertEquals(missing.status, Status.NotFound)
      drain(missing)
    }
  }

  // ── Config parsing (pure) ────────────────────────────────────────────────

  test("parseMaxConcurrent: absent/malformed → None, valid → Some, non-positive filtered") {
    assertEquals(NfServeGate.parseMaxConcurrent("{}"), None)
    assertEquals(NfServeGate.parseMaxConcurrent("""{"nfFile":{"ticketTtlSeconds":60}}"""), None)
    assertEquals(NfServeGate.parseMaxConcurrent("""{"nfFile":{"serveConcurrency":4}}"""), Some(4))
    assertEquals(NfServeGate.parseMaxConcurrent("""{"nfFile":{"serveConcurrency":-2}}"""), None)
    assertEquals(NfServeGate.parseMaxConcurrent("""{"nfFile":{"serveConcurrency":"x"}}"""), None)
  }

  test("budget clamps: <1 floors at 1, >4096 caps at 4096, defaults intact") {
    assertEquals(NfServeGate(0).maxConcurrent, NfServeGate.MinMaxConcurrent)
    assertEquals(NfServeGate(99999).maxConcurrent, NfServeGate.MaxMaxConcurrent)
    assertEquals(NfServeGate.DefaultMaxConcurrent, 32)
  }

end NfServeGateSpec
