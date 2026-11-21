package nebflow.gateway.static

import nebflow.gateway.*

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.*

/**
 * Route-level tests for StaticRoutes.assetsRoutes (P1 /assets wildcard, W1
 * source dispatch) and the W1 static-source resolution matrix.
 *
 * Fixtures: src/test/resources/web-dist/assets/ contains real classpath
 * resources for the BundledDist-leg specs. The resolution matrix feeds
 * resolveStaticSource with injected probes — it never depends on the
 * ambient machine state (whether the repo happens to carry a fresh
 * build/web-dist), so the suite is deterministic everywhere.
 */
class AssetsRoutesSpec extends CatsEffectSuite:

  private def get(routes: HttpRoutes[IO], path: String): IO[Option[Response[IO]]] =
    routes(Request[IO](Method.GET, Uri.unsafeFromString(path))).value

  private def cacheControl(resp: Response[IO]): Option[String] =
    resp.headers.get(org.typelevel.ci.CIString("Cache-Control")).map(_.head.value)

  // ── BundledDist leg (classpath web-dist tree) ─────────────────────────

  private val bundled: HttpRoutes[IO] = StaticRoutes.assetsRoutesFor(StaticRoutes.StaticSource.BundledDist)

  test("BundledDist: serves a bundled asset from web-dist/assets (single segment)") {
    get(bundled, "/assets/test-asset.js").flatMap {
      case Some(resp) =>
        for bytes <- resp.body.compile.toVector
        yield
          assertEquals(resp.status, Status.Ok)
          assertEquals(new String(bytes.toArray, "UTF-8"), "console.log(\"assets fixture entry\");\n")
          // W2: hashed names are content-addressed — immutable year-long cache
          // (index.html stays no-cache and is the sole freshness judge).
          assertEquals(cacheControl(resp), Some(StaticRoutes.HashedAssetCacheControl))
      case None => fail("route fell through for /assets/test-asset.js")
    }
  }

  test("BundledDist: serves nested chunks at any depth (web-dist/assets/chunks)") {
    get(bundled, "/assets/chunks/nested-1.js").map {
      case Some(resp) => assertEquals(resp.status, Status.Ok)
      case None => fail("route fell through for nested chunk")
    }
  }

  test("trailing slash (directory request) is rejected") {
    get(bundled, "/assets/chunks/").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("should have matched the /assets prefix")
    }
  }

  test("path traversal is rejected") {
    get(bundled, "/assets/..%2F..%2Fsecret.js").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("should have matched the /assets prefix")
    }
  }

  test("backslash in a segment is rejected") {
    get(bundled, "/assets/chunks%5Cevil.js").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("should have matched the /assets prefix")
    }
  }

  test("a missing dist asset 404s instead of falling through") {
    // /assets/app-deadbeef.js exists in no build — dev instances must return
    // a clean 404, and the route must NOT fall through to instance routes
    // (they would 404 too, but via the fileName whitelist — keep it local).
    get(bundled, "/assets/app-deadbeef.js").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("prefix-matched request must not fall through")
    }
  }

  test("non-assets paths do not match this route (fall through)") {
    get(bundled, "/js/main.js").map {
      case Some(_) => fail("assetsRoutes must not own /js paths")
      case None => () // HttpRoutes fallthrough semantics — correct here
    }
  }

  // ── DiskDist leg (W1: build/web-dist under the engine working dir) ────

  test("DiskDist: serves the disk tree with the immutable cache header") {
    val root = os.temp.dir(deleteOnExit = true)
    os.write(root / "assets" / "app-abc.js", "console.log('disk entry');", createFolders = true)
    os.write(root / "assets" / "chunks" / "lazy.js", "export {};", createFolders = true)
    val disk = StaticRoutes.assetsRoutesFor(StaticRoutes.StaticSource.DiskDist(root))
    get(disk, "/assets/app-abc.js").flatMap {
      case Some(resp) =>
        for bytes <- resp.body.compile.toVector
        yield
          assertEquals(resp.status, Status.Ok)
          assertEquals(new String(bytes.toArray, "UTF-8"), "console.log('disk entry');")
          assertEquals(cacheControl(resp), Some(StaticRoutes.HashedAssetCacheControl))
      case None => fail("disk leg must serve /assets from build/web-dist")
    }
  }

  test("DiskDist: nested depth + missing files behave like the classpath leg") {
    val root = os.temp.dir(deleteOnExit = true)
    os.write(root / "assets" / "chunks" / "lazy.js", "export {};", createFolders = true)
    val disk = StaticRoutes.assetsRoutesFor(StaticRoutes.StaticSource.DiskDist(root))
    for
      nested <- get(disk, "/assets/chunks/lazy.js")
      missing <- get(disk, "/assets/app-deadbeef.js")
      traversal <- get(disk, "/assets/..%2F..%2Fsecret.js")
    yield
      assertEquals(nested.map(_.status), Some(Status.Ok))
      assertEquals(missing.map(_.status), Some(Status.NotFound))
      assertEquals(traversal.map(_.status), Some(Status.NotFound))
  }

  test("DevTree: /assets 404s even when a bundled tree would be reachable") {
    // The dev index never references /assets — the mode gate must keep it
    // that way (probe-order safety: the fixture exists on THIS classpath).
    val dev = StaticRoutes.assetsRoutesFor(StaticRoutes.StaticSource.DevTree)
    get(dev, "/assets/test-asset.js").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("prefix-matched request must not fall through")
    }
  }

  // ── W1 resolution matrix (injected probes — ambient-state free) ───────

  // The freshness judge is injected (ensure = the build-web --ensure leg): the
  // matrix never spawns node. Default = "not fresh" so an unexpected call to the
  // rebuild leg can only land on the dev tree, never a false DiskDist.
  private def resolve(
    prop: Option[String] = None,
    classpath: Boolean = false,
    root: Option[os.Path] = None,
    ensure: os.Path => Boolean = _ => false
  ): StaticRoutes.StaticSource =
    StaticRoutes.resolveStaticSource("stand", prop, classpath, root, ensure)

  test("resolution: explicit dev flag wins over classpath and disk dist") {
    for flag <- Seq("dev", "0", "false", "DEV", " False ") do
      assertEquals(
        resolve(prop = Some(flag), classpath = true),
        StaticRoutes.StaticSource.DevTree,
        s"flag '$flag' must force the dev tree"
      )
  }

  test("resolution: classpath dist wins over a fresh disk dist") {
    val root = os.temp.dir(deleteOnExit = true)
    os.write(root / "build" / "web-dist" / "index.html", "<html></html>", createFolders = true)
    assertEquals(
      resolve(classpath = true, root = Some(root)),
      StaticRoutes.StaticSource.BundledDist
    )
  }

  test("resolution: fresh disk dist is served (sources dir absent = installed runtime)") {
    val root = os.temp.dir(deleteOnExit = true)
    os.write(root / "build" / "web-dist" / "index.html", "<html></html>", createFolders = true)
    assertEquals(
      resolve(root = Some(root)),
      StaticRoutes.StaticSource.DiskDist(root / "build" / "web-dist")
    )
  }

  test("resolution: a dist judged fresh by the ensure leg is served (no mtime involved)") {
    val root = os.temp.dir(deleteOnExit = true)
    os.write(root / "build" / "web-dist" / "index.html", "<html></html>", createFolders = true)
    os.write(root / "src" / "main" / "resources" / "web" / "js" / "old.js", "//", createFolders = true)
    assertEquals(
      resolve(root = Some(root), ensure = _ => true),
      StaticRoutes.StaticSource.DiskDist(root / "build" / "web-dist")
    )
  }

  test("resolution: a dist the ensure leg cannot refresh falls to the dev tree (no silent stale dist)") {
    val root = os.temp.dir(deleteOnExit = true)
    os.write(root / "build" / "web-dist" / "index.html", "<html></html>", createFolders = true)
    os.write(root / "src" / "main" / "resources" / "web" / "js" / "edited.js", "//", createFolders = true)
    assertEquals(resolve(root = Some(root), ensure = _ => false), StaticRoutes.StaticSource.DevTree)
  }

  test("resolution: ensure succeeds but no index emitted ⇒ dev tree (never a dangling DiskDist)") {
    val root = os.temp.dir(deleteOnExit = true)
    os.makeDir.all(root / "src" / "main" / "resources" / "web")
    assertEquals(resolve(root = Some(root), ensure = _ => true), StaticRoutes.StaticSource.DevTree)
  }

  test("resolution: explicit dev flag never invokes the rebuild leg") {
    val root = os.temp.dir(deleteOnExit = true)
    os.write(root / "build" / "web-dist" / "index.html", "<html></html>", createFolders = true)
    os.makeDir.all(root / "src" / "main" / "resources" / "web")
    var called = false
    val got = resolve(prop = Some("dev"), root = Some(root), ensure = _ => { called = true; true })
    assertEquals(got, StaticRoutes.StaticSource.DevTree)
    assert(!called, "webdist=dev must not trigger a rebuild")
  }

  test("resolution: no dist anywhere stays on the dev tree") {
    assertEquals(resolve(), StaticRoutes.StaticSource.DevTree)
    val root = os.temp.dir(deleteOnExit = true) // root without build/web-dist
    assertEquals(resolve(root = Some(root)), StaticRoutes.StaticSource.DevTree)
  }

  // ── ensureDist: the rebuild leg itself (fake build-web.mjs, real node) ──

  private def fakeScript(root: os.Path, body: String): Unit =
    os.write(root / "scripts" / "build-web.mjs", body, createFolders = true)

  test("ensureDist: absent build script ⇒ not fresh (never throws)") {
    assertEquals(StaticRoutes.ensureDist(os.temp.dir(deleteOnExit = true)), false)
  }

  test("ensureDist: runs the script with --ensure in the root and trusts exit 0") {
    val root = os.temp.dir(deleteOnExit = true)
    // ESM body: the fake is named build-web.mjs, so `require` is unavailable.
    fakeScript(root, "import { writeFileSync } from 'node:fs';\nwriteFileSync('ensure-args.txt', process.argv.slice(2).join(' '));\n")
    assertEquals(StaticRoutes.ensureDist(root), true)
    assertEquals(os.read(root / "ensure-args.txt").trim, "--ensure")
  }

  test("ensureDist: non-zero exit ⇒ not fresh (failure reason kept in build/web-ensure.log)") {
    val root = os.temp.dir(deleteOnExit = true)
    fakeScript(root, "console.log('boom-marker'); process.exit(3);\n")
    assertEquals(StaticRoutes.ensureDist(root), false)
    assert(os.read(root / "build" / "web-ensure.log").contains("boom-marker"))
  }

  // ── W1 index serving legs ──────────────────────────────────────────────

  test("indexWithBrandFile: brand injected, no-cache, Content-Length matches body") {
    val f = os.temp(suffix = ".html")
    os.write.over(f, "<html><head><title>t</title></head><body></body></html>")
    StaticRoutes.indexWithBrandFile(f).flatMap { resp =>
      for bytes <- resp.body.compile.toVector
      yield
        val body = new String(bytes.toArray, "UTF-8")
        assertEquals(resp.status, Status.Ok)
        assert(body.contains("<script>window.__BRAND__="), "brand script must be injected")
        assert(body.contains("</head>"), "head close must survive the injection")
        assertEquals(cacheControl(resp), Some("no-cache"))
        assertEquals(
          resp.headers.get(org.typelevel.ci.CIString("Content-Length")).map(_.head.value.toLong),
          Some(bytes.length.toLong)
        )
    }
  }

  test("indexWithBrandFile: missing file degrades to NotFound") {
    StaticRoutes
      .indexWithBrandFile(os.Path("/nonexistent/definitely-missing.html"))
      .map(resp => assertEquals(resp.status, Status.NotFound))
  }

end AssetsRoutesSpec
