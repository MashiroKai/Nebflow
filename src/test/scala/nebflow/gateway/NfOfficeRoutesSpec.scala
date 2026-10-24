package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Route-level tests for [[NfOfficeRoutes]] — `GET /api/nf-office`, the server-side
 * office→PDF/PNG conversion leg behind the pptx viewer's conversion-first flow
 * (canvas-pptx-convert batch, author order 2026-10-24 14:35).
 *
 * The converter itself is injected (fake legs) — this file pins the ROUTE
 * semantics: ticket enforcement (same chain as /api/nf-file), extension gate,
 * size gate, converter-absent 501, leg fallthrough, cache hit/miss (二次点击
 * 不重转：converter invoked ONCE across two requests), all-legs-failed 502.
 * The REAL soffice/qlmanage binaries are exercised live (frontend spec
 * canvas-office-convert + the batch report's live-instance evidence).
 */
class NfOfficeRoutesSpec extends CatsEffectSuite:

  private val store = NfTicketStore.unsafeCreate(1800)

  /** Injected policy: nothing under test lives in P1/P3 (mirrors NfFileRoutesSpec). */
  private val policy = NfFilePolicy.NfPathPolicy(
    java.nio.file.Paths.get("/nonexistent-nebflow-data-root"),
    java.nio.file.Paths.get("/nonexistent-nebflow-workspace"),
    Set.empty
  )

  /** A fake converter invocation ledger shared by the legs a test installs. */
  private final class Ledger:
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    def count: Int = calls.get()

  private def pdfLeg(ledger: Ledger, tag: String = "fake-pdf"): NfOfficeRoutes.Leg =
    NfOfficeRoutes.Leg(tag, (src, outDir) =>
      IO {
        ledger.calls.incrementAndGet()
        val out = outDir / "out.pdf"
        os.write(out, "%PDF-1.4 fake-bytes".getBytes(StandardCharsets.UTF_8))
        out
      }
    )

  private def pngLeg(ledger: Ledger, tag: String = "fake-png"): NfOfficeRoutes.Leg =
    NfOfficeRoutes.Leg(tag, (src, outDir) =>
      IO {
        ledger.calls.incrementAndGet()
        val out = outDir / "thumb.png"
        os.write(out, Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47))
        out
      }
    )

  private def failingLeg(ledger: Ledger, tag: String = "failing"): NfOfficeRoutes.Leg =
    NfOfficeRoutes.Leg(tag, (src, outDir) =>
      ledger.calls.incrementAndGet() *> IO.raiseError(new java.io.IOException("boom"))
    )

  private def legsOf(ls: List[NfOfficeRoutes.Leg]): NfOfficeRoutes.Legs = new NfOfficeRoutes.Legs:
    def probe: IO[List[NfOfficeRoutes.Leg]] = IO.pure(ls)

  private def noLegs: NfOfficeRoutes.Legs = new NfOfficeRoutes.Legs:
    def probe: IO[List[NfOfficeRoutes.Leg]] = IO.pure(Nil)

  /** One seeded source file; the suite hands its realpath-bound ticket to `test`. */
  private def withSeedFile[A](name: String, bytes: Array[Byte])(test: (Path, String) => IO[A]): A =
    val tmp   = Files.createTempDirectory("nf-office-test")
    val file  = tmp.resolve(name)
    Files.write(file, bytes)
    val ticket = store.issue("spec", file.toRealPath().toString).unsafeRunSync().token
    try test(file, ticket).unsafeRunSync()
    finally
      Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  private def routes(
    legs: NfOfficeRoutes.Legs,
    cacheDir: os.Path
  ): org.http4s.HttpRoutes[IO] =
    NfOfficeRoutes.officeRoutes(store, policy, legs = legs, cacheDir = cacheDir)

  private def get(rt: org.http4s.HttpRoutes[IO], path: String) =
    rt(Request[IO](Method.GET, Uri.unsafeFromString(path))).value.unsafeRunSync()

  private def headerOf(resp: org.http4s.Response[IO], name: String): String =
    resp.headers.get(org.typelevel.ci.CIString(name)).map(_.head.value).getOrElse("")

  test("missing path → 400") {
    withTempCache { cache =>
      assertEquals(get(routes(noLegs, cache), "/api/nf-office").get.status, Status.BadRequest)
    }
  }

  test("missing ticket → 401 with X-Nf-Reason") {
    withTempCache { cache =>
      withSeedFile("a.pptx", "PK".getBytes) { (f, _) =>
        val resp = get(routes(noLegs, cache), s"/api/nf-office?path=${f}").get
        assertEquals(resp.status, Status.Unauthorized)
        assertEquals(headerOf(resp, "X-Nf-Reason"), "missing-ticket")
      }
    }
  }

  test("verdict denial (nonexistent path) → 404 not-found") {
    withTempCache { cache =>
      val resp = get(routes(noLegs, cache), s"/api/nf-office?path=/nonexistent/x.pptx&ticket=t").get
      assertEquals(resp.status, Status.NotFound)
      assertEquals(headerOf(resp, "X-Nf-Reason"), "not-found")
    }
  }

  test("unsupported extension → 415 unsupported-type") {
    withTempCache { cache =>
      withSeedFile("notes.txt", "hi".getBytes) { (f, ticket) =>
        val resp = get(routes(noLegs, cache), s"/api/nf-office?path=${f}&ticket=$ticket").get
        assertEquals(resp.status, Status.UnsupportedMediaType)
        assertEquals(headerOf(resp, "X-Nf-Reason"), "unsupported-type")
      }
    }
  }

  test("converter absent → 501 converter-absent") {
    withTempCache { cache =>
      withSeedFile("deck.pptx", "PK".getBytes) { (f, ticket) =>
        val resp = get(routes(noLegs, cache), s"/api/nf-office?path=${f}&ticket=$ticket").get
        assertEquals(resp.status, Status.NotImplemented)
        assertEquals(headerOf(resp, "X-Nf-Reason"), "converter-absent")
      }
    }
  }

  test("conversion: 200 PDF, converter+cache headers, byte-faithful body") {
    withTempCache { cache =>
      withSeedFile("deck.pptx", "PK".getBytes) { (f, ticket) =>
        val ledger = Ledger()
        val rt = routes(legsOf(List(pdfLeg(ledger))), cache)
        val resp = get(rt, s"/api/nf-office?path=${f}&ticket=$ticket").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(headerOf(resp, "X-Nf-Office-Converter"), "fake-pdf")
        assertEquals(headerOf(resp, "X-Nf-Office-Cache"), "miss")
        assertEquals(resp.contentType.map(_.mediaType.subType), Some("pdf"))
        assertEquals(resp.bodyText.compile.string.unsafeRunSync(), "%PDF-1.4 fake-bytes")
        assertEquals(ledger.count, 1)
      }
    }
  }

  test("cache hit: second request does NOT re-convert (二次点击不重转)") {
    withTempCache { cache =>
      withSeedFile("deck.pptx", "PK".getBytes) { (f, ticket) =>
        val ledger = Ledger()
        val rt = routes(legsOf(List(pdfLeg(ledger))), cache)
        val r1 = get(rt, s"/api/nf-office?path=${f}&ticket=$ticket").get
        val r2 = get(rt, s"/api/nf-office?path=${f}&ticket=$ticket").get
        assertEquals(r1.status, Status.Ok)
        assertEquals(r2.status, Status.Ok)
        assertEquals(headerOf(r2, "X-Nf-Office-Cache"), "hit")
        assertEquals(ledger.count, 1) // the whole point of the cache
      }
    }
  }

  test("cache key follows source mtime (source edit → re-convert)") {
    withTempCache { cache =>
      withSeedFile("deck.pptx", "PK".getBytes) { (f, ticket) =>
        val ledger = Ledger()
        val rt = routes(legsOf(List(pdfLeg(ledger))), cache)
        assertEquals(get(rt, s"/api/nf-office?path=${f}&ticket=$ticket").get.status, Status.Ok)
        // bump mtime by a whole second (mtime resolution safety)
        val p = f.toFile
        p.setLastModified(p.lastModified() + 5000)
        val r2 = get(rt, s"/api/nf-office?path=${f}&ticket=$ticket").get
        assertEquals(headerOf(r2, "X-Nf-Office-Cache"), "miss")
        assertEquals(ledger.count, 2)
      }
    }
  }

  test("leg fallthrough: first leg fails → second leg's output served") {
    withTempCache { cache =>
      withSeedFile("deck.pptx", "PK".getBytes) { (f, ticket) =>
        val ledger = Ledger()
        val rt = routes(legsOf(List(failingLeg(ledger), pngLeg(ledger, "qlmanage"))), cache)
        val resp = get(rt, s"/api/nf-office?path=${f}&ticket=$ticket").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(headerOf(resp, "X-Nf-Office-Converter"), "qlmanage")
        assertEquals(resp.contentType.map(_.mediaType.subType), Some("png"))
        assertEquals(ledger.count, 2)
      }
    }
  }

  test("all legs fail → 502 conversion-failed") {
    withTempCache { cache =>
      withSeedFile("deck.pptx", "PK".getBytes) { (f, ticket) =>
        val ledger = Ledger()
        val rt = routes(legsOf(List(failingLeg(ledger), failingLeg(ledger, "failing2"))), cache)
        val resp = get(rt, s"/api/nf-office?path=${f}&ticket=$ticket").get
        assertEquals(resp.status, Status.BadGateway)
        assertEquals(headerOf(resp, "X-Nf-Reason"), "conversion-failed")
      }
    }
  }

  test("wrong-path ticket → 403 path-mismatch") {
    withTempCache { cache =>
      withSeedFile("deck.pptx", "PK".getBytes) { (f, _) =>
        val other = store.issue("spec", "/some/other/file.pptx").unsafeRunSync().token
        val resp = get(routes(noLegs, cache), s"/api/nf-office?path=${f}&ticket=$other").get
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(headerOf(resp, "X-Nf-Reason"), "path-mismatch")
      }
    }
  }

  test("pollFor returns the produced file and times out when nothing appears") {
    withTempDir { dir =>
      val out = os.Path(dir) / "out"
      os.makeDir.all(out)
      // produced-in-time path
      val leg = NfOfficeRoutes.Leg("poll", (src, outDir) =>
        IO { os.write(outDir / "late.pdf", "x".getBytes) } *>
          NfOfficeRoutes.Legs.pollFor(outDir, "pdf", 10.seconds)
      )
      val got = leg.run(os.Path(dir) / "s.pptx", out).unsafeRunSync()
      assertEquals(got.ext, "pdf")
      // empty-dir timeout path
      val empty = os.Path(dir) / "empty"
      os.makeDir.all(empty)
      val missing = NfOfficeRoutes.Legs.pollFor(empty, "pdf", 1.seconds).attempt.unsafeRunSync()
      assert(missing.isLeft, "pollFor must fail when nothing is produced")
    }
  }

  test("cacheKey follows mtime/size changes") {
    withTempDir { dir =>
      val f = os.Path(dir) / "k.pptx"
      os.write(f, "v1")
      val k1 = NfOfficeRoutes.cacheKey(f, "soffice")
      os.write.append(f, "more bytes")
      val f2 = f.toIO
      f2.setLastModified(f2.lastModified() + 5000)
      val k2 = NfOfficeRoutes.cacheKey(f, "soffice")
      assert(k1 != k2, "content change must move the cache key")
      assertEquals(NfOfficeRoutes.cacheKey(f, "soffice"), k2, "stable within unchanged source")
    }
  }

  private def withTempCache[A](test: os.Path => A): A =
    withTempDir(dir => test(os.Path(dir) / "cache"))

  private def withTempDir[A](test: Path => A): A =
    val tmp = Files.createTempDirectory("nf-office-cache-test")
    try test(tmp)
    finally
      Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

end NfOfficeRoutesSpec
