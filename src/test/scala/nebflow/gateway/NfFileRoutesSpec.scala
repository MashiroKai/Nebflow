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
 * 2026-09-11 (C batch, R5 = ticket-only): the credential leg is a per-path
 * short-lived ticket, not the global gateway token. The 11 behavioural
 * contracts below are UNCHANGED in intent — same files, same statuses — but
 * the requests now carry `&ticket=` minted from an in-memory store, and the
 * two credential tests were re-pointed at the new model: "no credential at
 * all" → 401, and "a VALID gateway token with no ticket" → 401 (which is the
 * R5 contract, and is strictly stronger than the old "wrong token → 403").
 * The credential-namespace / hard-link / issuer assertions live in
 * NfTicketRoutesSpec (A1–A16); this file stays the behaviour-equivalence
 * surface (A13).
 *
 * Contract points: ticket auth; whitelist gate (js/css/json in; scripts and
 * unknown types out); byte-identical serving; missing file 404; missing
 * path param 400.
 */
class NfFileRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"

  private val store = NfTicketStore.unsafeCreate(1800)

  /** Injected C1-5 policy: nothing under test lives in P1/P3, and the R2
    * inode set is empty — the subject here is the ticket leg, not the
    * credential namespace (that is NfTicketRoutesSpec's job). */
  private val policy = WebSocketRoutes.NfPathPolicy(
    java.nio.file.Paths.get("/nonexistent-nebflow-data-root"),
    java.nio.file.Paths.get("/nonexistent-nebflow-workspace"),
    Set.empty
  )

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

  /** A ticket bound to the realpath of an existing file. */
  private def ticketFor(p: String): String =
    store.issue("spec", java.nio.file.Paths.get(p).toRealPath().toString).unsafeRunSync().token

  /** A ticket for a path that need not exist (the read-leg 404 case: the
    * issuer would never mint one, so the store is called directly). */
  private def ticketForMissing(p: String): String =
    store.issue("spec", p).unsafeRunSync().token

  private def get(path: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
    WebSocketRoutes.nfFileRoutes(gatewayToken, store, policy)(req).value.unsafeRunSync()

  private def bodyOf(resp: org.http4s.Response[IO]): String =
    resp.bodyText.compile.string.unsafeRunSync()

  test("rejects requests that present no credential at all") {
    withTempFiles { dir =>
      IO {
        assertEquals(
          get(s"/api/nf-file?path=${dir / "data.js"}").get.status,
          Status.Unauthorized
        )
      }
    }
  }

  test("rejects the global gateway token alone (R5: the token leg is gone)") {
    withTempFiles { dir =>
      IO {
        assertEquals(
          get(s"/api/nf-file?path=${dir / "data.js"}&token=$gatewayToken").get.status,
          Status.Unauthorized
        )
      }
    }
  }

  test("serves a companion .js module (Canvas interactive-HTML fix core)") {
    withSeedFile("snapshot-data.js", "window.FM_SNAPSHOT={active:[]};".getBytes(StandardCharsets.UTF_8)) { js =>
      IO {
        val resp = get(s"/api/nf-file?path=${js.toString}&ticket=${ticketFor(js.toString)}").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(bodyOf(resp), "window.FM_SNAPSHOT={active:[]};")
      }
    }
  }

  test("serves .css and .json companions") {
    withTempFiles { dir =>
      IO {
        assertEquals(
          get(s"/api/nf-file?path=${dir / "style.css"}&ticket=${ticketFor((dir / "style.css").toString)}").get.status,
          Status.Ok
        )
        assertEquals(
          get(s"/api/nf-file?path=${dir / "data.json"}&ticket=${ticketFor((dir / "data.json").toString)}").get.status,
          Status.Ok
        )
      }
    }
  }

  test("still serves whitelisted media (png regression guard)") {
    withSeedFile("shot.png", Array[Byte](1, 2, 3)) { png =>
      IO {
        val resp = get(s"/api/nf-file?path=${png.toString}&ticket=${ticketFor(png.toString)}").get
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
          get(s"/api/nf-file?path=${sh.toString}&ticket=${ticketFor(sh.toString)}").get.status,
          Status.BadRequest
        )
      }
    }
  }

  test("404s for a whitelisted but missing file") {
    withTempFiles { _ =>
      IO {
        assertEquals(
          get(
            s"/api/nf-file?path=/nonexistent/snapshot-data.js&ticket=${ticketForMissing("/nonexistent/snapshot-data.js")}"
          ).get.status,
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
