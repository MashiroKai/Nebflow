package nebflow.gateway

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.*
import org.http4s.syntax.literals.uri
import org.typelevel.ci.CIString

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files as JFiles, Path as JPath}
import java.util.zip.GZIPInputStream

/**
 * Route-level tests for GzipMiddleware (esbuild-design.md 决策 3 验收断言,
 * programmatic equivalents of the four curl assertions):
 *  1. Accept-Encoding: gzip -> Content-Encoding: gzip
 *  2. revalidation with the weakened ETag still yields 304 (+ Vary)
 *  plus the exclusion list (ws path, binaries, q=0) and representation
 *  correctness (Content-Length dropped, Vary on every compressible variant,
 *  body byte-identical after gunzip).
 */
class GzipMiddlewareSpec extends CatsEffectSuite:

  private val jsBody: String = ("function f() { return 42; }\n" * 2000) // ~46KB, compresses well

  private def fakeRoutes: HttpRoutes[IO] =
    GzipMiddleware(HttpRoutes.of[IO] {
      case GET -> Root / "app.js" =>
        Ok(jsBody, `Content-Type`(MediaType.application.javascript), ETag(EntityTag("v1")))
      case GET -> Root / "logo.png" =>
        Ok(Array[Byte](1, 2, 3, 4), `Content-Type`(MediaType.image.png))
      case GET -> Root / "file.css" =>
        Ok("body { color: red }", `Content-Type`(MediaType.text.css))
      case GET -> Root / "ws" =>
        Ok("upgrade-simulation")
    })

  private def get(path: String, headers: Header.ToRaw*): IO[Response[IO]] =
    fakeRoutes(Request[IO](Method.GET, Uri.unsafeFromString(path), headers = Headers(headers.toList)))
      .value
      .map(_.getOrElse(fail("route fell through")))

  private def gzipAccepted: `Accept-Encoding` = `Accept-Encoding`(ContentCoding.gzip)

  private def gunzipToString(bytes: Array[Byte]): String =
    val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
    try
      val out = new ByteArrayOutputStream()
      in.transferTo(out)
      out.toString("UTF-8")
    finally in.close()

  test("gzip negotiation: Content-Encoding + dropped Content-Length + Vary + stripped ETag") {
    for
      resp <- get("/app.js", gzipAccepted)
      bytes <- resp.body.compile.toVector
    yield
      assertEquals(resp.headers.get[`Content-Encoding`].map(_.contentCoding), Some(ContentCoding.gzip))
      // length changes under compression -> must not be advertised
      assertEquals(resp.headers.get[`Content-Length`], None)
      // the identity representation's strong validator is stripped: the
      // gzipped representation is not byte-identical, so it must not reuse it
      assertEquals(resp.headers.get[ETag], None)
      // every compressible response advertises Vary
      assertEquals(
        resp.headers.get(CIString("Vary")).map(_.head.value),
        Some("Accept-Encoding")
      )
      // body survives the round trip byte-identically
      assertEquals(gunzipToString(bytes.toArray), jsBody)
  }

  test("identity variant: no encoding, strong ETag kept, Vary still present") {
    for resp <- get("/app.js") // no Accept-Encoding
    yield
      assertEquals(resp.headers.get[`Content-Encoding`], None)
      assertEquals(resp.headers.get[ETag].map(_.tag.toString), Some("\"v1\""))
      assertEquals(resp.headers.get(CIString("Vary")).map(_.head.value), Some("Accept-Encoding"))
  }

  test("Accept-Encoding: gzip;q=0 is a refusal - no compression") {
    for
      resp <- get("/app.js", `Accept-Encoding`(ContentCoding.gzip.withQValue(QValue.Zero)))
      bytes <- resp.body.compile.toVector
    yield
      assertEquals(resp.headers.get[`Content-Encoding`], None)
      assertEquals(new String(bytes.toArray, "UTF-8"), jsBody) // plain body
  }

  test("already-compressed binary (png) is never compressed") {
    for
      resp <- get("/logo.png", gzipAccepted)
      bytes <- resp.body.compile.toVector
    yield
      assertEquals(resp.headers.get[`Content-Encoding`], None)
      assertEquals(bytes, Vector[Byte](1, 2, 3, 4))
  }

  test("/ws path is passed through untouched") {
    for resp <- get("/ws", gzipAccepted)
    yield
      assertEquals(resp.headers.get[`Content-Encoding`], None)
      assertEquals(resp.headers.get(CIString("Vary")), None)
  }

  test("304 NotModified gains Vary, body stays empty") {
    // Simulate the conditional hit that StaticFile produces internally
    val conditional = GzipMiddleware(HttpRoutes.of[IO] {
      case req @ GET -> Root / "app.js" if req.headers.get[`If-None-Match`].isDefined =>
        IO.pure(Response[IO](Status.NotModified))
      case GET -> Root / "app.js" => Ok(jsBody)
    })
    for
      resp <- conditional(
        Request[IO](Method.GET, uri"/app.js", headers = Headers(
          gzipAccepted,
          `If-None-Match`(EntityTag("v1", EntityTag.Weak)),
        ))
      ).value.map(_.getOrElse(fail("fell through")))
      bytes <- resp.body.compile.toVector
    yield
      assertEquals(resp.status, Status.NotModified)
      assertEquals(resp.headers.get(CIString("Vary")).map(_.head.value), Some("Accept-Encoding"))
      assert(bytes.isEmpty)
  }

  test("gzip payload is meaningfully smaller (measure-load micro equivalent)") {
    for
      plain <- get("/app.js").flatMap(_.body.compile.toVector)
      zipped <- get("/app.js", gzipAccepted).flatMap(_.body.compile.toVector)
    yield
      // 46KB of repeated JS text must compress far below half its size
      assert(zipped.length < plain.length / 4,
        s"gzipped ${zipped.length} not < quarter of ${plain.length}")
  }

  // --- Integration: real StaticFile.fromPath (strong ETag) through the middleware ---

  test("StaticFile integration: gzipped response drops ETag, revalidates to 304 via If-Modified-Since (curl assertion 2)") {
    Resource.make(
      IO {
        val dir = JFiles.createTempDirectory("gzip-mw-spec")
        val f = dir.resolve("integration.js")
        JFiles.write(f, jsBody.getBytes("UTF-8"))
        f
      }
    ) { (f: JPath) => IO(JFiles.delete(f)).guarantee(IO(JFiles.delete(f.getParent))) }.use { file =>
      val staticRoutes = GzipMiddleware(HttpRoutes.of[IO] {
        case req @ GET -> Root / "integration.js" =>
          StaticFile.fromPath(fs2.io.file.Path.fromNioPath(file), Some(req)).getOrElseF(NotFound())
      })
      def req(headers: Header.ToRaw*) =
        staticRoutes(
          Request[IO](Method.GET, uri"/integration.js", headers = Headers(headers.toList))
        ).value.map(_.getOrElse(fail("fell through")))

      for
        first <- req(gzipAccepted)
        // gzipped variant: compressed, ETag stripped, Last-Modified kept
        _ = assertEquals(first.headers.get[`Content-Encoding`].map(_.contentCoding), Some(ContentCoding.gzip))
        _ = assertEquals(first.headers.get[ETag], None, "gzipped variant must not reuse the identity ETag")
        lastModified = first.headers.get[`Last-Modified`].getOrElse(fail("StaticFile must send Last-Modified"))
        // revalidate exactly as a client without an ETag would: If-Modified-Since
        second <- req(gzipAccepted, `If-Modified-Since`(lastModified.date))
      yield assertEquals(second.status, Status.NotModified)
    }
  }

  test("StaticFile integration: no Accept-Encoding -> identity bytes intact") {
    Resource.make(
      IO {
        val dir = JFiles.createTempDirectory("gzip-mw-spec2")
        val f = dir.resolve("plain.js")
        JFiles.write(f, jsBody.getBytes("UTF-8"))
        f
      }
    ) { (f: JPath) => IO(JFiles.delete(f)).guarantee(IO(JFiles.delete(f.getParent))) }.use { file =>
      val staticRoutes = GzipMiddleware(HttpRoutes.of[IO] {
        case req @ GET -> Root / "plain.js" =>
          StaticFile.fromPath(fs2.io.file.Path.fromNioPath(file), Some(req)).getOrElseF(NotFound())
      })
      for
        resp <- staticRoutes(Request[IO](Method.GET, uri"/plain.js")).value
          .map(_.getOrElse(fail("fell through")))
        bytes <- resp.body.compile.toVector
      yield
        assertEquals(resp.headers.get[`Content-Encoding`], None)
        assertEquals(resp.headers.get[ETag].map(_.tag.weakness), Some(EntityTag.Strong))
        assertEquals(new String(bytes.toArray, "UTF-8"), jsBody)
    }
  }

end GzipMiddlewareSpec
