package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Route-level tests for WebSocketRoutes.nfFileRoutes — GET /api/nf-file,
 * the local-file endpoint behind the Canvas HTML viewer's resolveLocalFiles
 * rewrite (viewers/shared.js turns src/href attributes on opened HTML files
 * into /api/nf-file?path=... URLs).
 *
 * 2026-09-03 Canvas interactive-HTML fix: the whitelist must cover the text
 * asset types (js/css/json) that multi-file HTML deliverables reference.
 * Before the fix a companion `<script src="./snapshot-data.js">` 400'd, the
 * page boot script died on the missing global before binding any listeners,
 * and nothing in the Canvas tab was clickable (see the header of
 * WebSocketRoutes.NfFileAllowedExt for the full chain).
 *
 * Contract points: token auth; whitelist gate (js/css/json in; scripts and
 * unknown types out); byte-identical serving; missing file 404; missing
 * path param 400.
 */
class NfFileRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"

  /** Temp dir with one seeded file per extension; absolute paths feed ?path=. */
  private def withTempFiles[A](test: os.Path => IO[A]): A =
    val tmp = Files.createTempDirectory("nebflow-nffile-test")
    Files.write(tmp.resolve("data.js"), "window.FM_SNAPSHOT={active:[]};".getBytes(StandardCharsets.UTF_8))
    Files.write(tmp.resolve("style.css"), "body{color:red}".getBytes(StandardCharsets.UTF_8))
    Files.write(tmp.resolve("data.json"), "{\"ok\":true}".getBytes(StandardCharsets.UTF_8))
    Files.write(tmp.resolve("shot.png"), Array[Byte](1, 2, 3))
    Files.write(tmp.resolve("evil.sh"), "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8))
    try test(os.Path(tmp)).unsafeRunSync()
    finally
      Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(
        Files.deleteIfExists
      )

  /** One seeded file in its own temp dir; cleaned up after `test`. */
  private def withSeedFile[A](name: String, bytes: Array[Byte])(test: Path => IO[A]): A =
    val tmp = Files.createTempDirectory("nebflow-nffile-test")
    val file = tmp.resolve(name)
    Files.write(file, bytes)
    try test(file).unsafeRunSync()
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(tmp)

  private def get(path: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
    WebSocketRoutes.nfFileRoutes(gatewayToken)(req).value.unsafeRunSync()

  private def bodyOf(resp: org.http4s.Response[IO]): String =
    resp.bodyText.compile.string.unsafeRunSync()

  test("rejects unauthenticated requests") {
    withTempFiles { dir =>
      IO {
        assertEquals(
          get(s"/api/nf-file?path=${dir / "data.js"}").get.status,
          Status.Forbidden
        )
      }
    }
  }

  test("rejects a wrong token") {
    withTempFiles { dir =>
      IO {
        assertEquals(
          get(s"/api/nf-file?path=${dir / "data.js"}&token=wrong").get.status,
          Status.Forbidden
        )
      }
    }
  }

  test("serves a companion .js module (Canvas interactive-HTML fix core)") {
    withSeedFile("snapshot-data.js", "window.FM_SNAPSHOT={active:[]};".getBytes(StandardCharsets.UTF_8)) { js =>
      IO {
        val resp = get(s"/api/nf-file?path=${js.toString}&token=$gatewayToken").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(bodyOf(resp), "window.FM_SNAPSHOT={active:[]};")
      }
    }
  }

  test("serves .css and .json companions") {
    withTempFiles { dir =>
      IO {
        assertEquals(get(s"/api/nf-file?path=${dir / "style.css"}&token=$gatewayToken").get.status, Status.Ok)
        assertEquals(get(s"/api/nf-file?path=${dir / "data.json"}&token=$gatewayToken").get.status, Status.Ok)
      }
    }
  }

  test("still serves whitelisted media (png regression guard)") {
    withSeedFile("shot.png", Array[Byte](1, 2, 3)) { png =>
      IO {
        val resp = get(s"/api/nf-file?path=${png.toString}&token=$gatewayToken").get
        assertEquals(resp.status, Status.Ok)
        val bytes = resp.body.compile.toVector.unsafeRunSync().toArray
        assert(java.util.Arrays.equals(bytes, Array[Byte](1, 2, 3)))
      }
    }
  }

  test("rejects non-whitelisted executable types (sh)") {
    withSeedFile("evil.sh", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8)) { sh =>
      IO {
        assertEquals(
          get(s"/api/nf-file?path=${sh.toString}&token=$gatewayToken").get.status,
          Status.BadRequest
        )
      }
    }
  }

  test("404s for a whitelisted but missing file") {
    withTempFiles { _ =>
      IO {
        assertEquals(
          get(s"/api/nf-file?path=/nonexistent/snapshot-data.js&token=$gatewayToken").get.status,
          Status.NotFound
        )
      }
    }
  }

  test("400s when the path parameter is missing") {
    withTempFiles { _ =>
      IO {
        assertEquals(
          get(s"/api/nf-file?token=$gatewayToken").get.status,
          Status.BadRequest
        )
      }
    }
  }

  test("whitelist: js/css/json present (the fix)") {
    assert(WebSocketRoutes.NfFileAllowedExt.contains("js"))
    assert(WebSocketRoutes.NfFileAllowedExt.contains("mjs"))
    assert(WebSocketRoutes.NfFileAllowedExt.contains("css"))
    assert(WebSocketRoutes.NfFileAllowedExt.contains("json"))
  }

  test("whitelist: pre-fix media types intact (no silent narrowing)") {
    val preFix = Set(
      "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "bmp", "avif", "tiff", "tif",
      "mp4", "webm", "ogg", "ogv", "mov", "mp3", "wav", "oga", "flac", "aac", "m4a",
      "woff", "woff2", "ttf", "otf", "pdf", "docx", "xlsx", "xlsm", "pptx", "epub"
    )
    assert(preFix.subsetOf(WebSocketRoutes.NfFileAllowedExt))
  }

  test("whitelist: script/server-code extensions stay excluded") {
    val excluded = Set("sh", "bash", "exe", "bat", "py", "rb", "pl", "php", "html", "htm")
    assert(excluded.forall(ext => !WebSocketRoutes.NfFileAllowedExt.contains(ext)))
  }
