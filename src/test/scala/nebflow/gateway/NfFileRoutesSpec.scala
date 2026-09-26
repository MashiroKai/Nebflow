package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Route-level tests for NfFileRoutes.nfFileRoutes — GET /api/nf-file,
 * the local-file endpoint behind the Canvas HTML viewer's resolveLocalFiles
 * rewrite (viewers/shared.js turns src/href attributes on opened HTML files
 * into /api/nf-file?path=... URLs).
 *
 * 2026-09-03 Canvas interactive-HTML fix: the whitelist must cover the text
 * asset types (js/css/json) that multi-file HTML deliverables reference.
 * Before the fix a companion `<script src="./snapshot-data.js">` 400'd, the
 * page boot script died on the missing global before binding any listeners,
 * and nothing in the Canvas tab was clickable (see the header of
 * NfFilePolicy.NfFileAllowedExt for the full chain).
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

  /**
   * Injected C1-5 policy: nothing under test lives in P1/P3, and the R2
   * inode set is empty — the subject here is the ticket leg, not the
   * credential namespace (that is NfTicketRoutesSpec's job).
   */
  private val policy = NfFilePolicy.NfPathPolicy(
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
      Files
        .walk(tmp)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(
          Files.deleteIfExists
        )

  end withTempFiles

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

  /**
   * A ticket for a path that need not exist (the read-leg 404 case: the
   * issuer would never mint one, so the store is called directly).
   */
  private def ticketForMissing(p: String): String =
    store.issue("spec", p).unsafeRunSync().token

  private def get(path: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
    NfFileRoutes.nfFileRoutes(gatewayToken, store, policy)(req).value.unsafeRunSync()

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
    assert(NfFilePolicy.NfFileAllowedExt.contains("js"))
    assert(NfFilePolicy.NfFileAllowedExt.contains("mjs"))
    assert(NfFilePolicy.NfFileAllowedExt.contains("css"))
    assert(NfFilePolicy.NfFileAllowedExt.contains("json"))
  }

  test("whitelist: pre-fix media types intact (no silent narrowing)") {
    val preFix = Set(
      "png",
      "jpg",
      "jpeg",
      "gif",
      "svg",
      "webp",
      "ico",
      "bmp",
      "avif",
      "tiff",
      "tif",
      "mp4",
      "webm",
      "ogg",
      "ogv",
      "mov",
      "mp3",
      "wav",
      "oga",
      "flac",
      "aac",
      "m4a",
      "woff",
      "woff2",
      "ttf",
      "otf",
      "pdf",
      "docx",
      "xlsx",
      "xlsm",
      "pptx",
      "epub"
    )
    assert(preFix.subsetOf(NfFilePolicy.NfFileAllowedExt))
  }

  test("whitelist: script/server-code extensions stay excluded") {
    val excluded = Set("sh", "bash", "exe", "bat", "py", "rb", "pl", "php", "html", "htm")
    assert(excluded.forall(ext => !NfFilePolicy.NfFileAllowedExt.contains(ext)))
  }

  // ── 2026-09-17 nfext batch: `.doc` / `.ppt` / `.xls` (legacy binary Office) ──
  //
  // Author ruling (2026-09-17, net-widening form (i)): the device face must
  // behave like the friend/group face. The friend/group attachment leg carries
  // no extension gate, so the three legacy types were the one place the device
  // face refused bytes the friend face delivers; `devattach-verify` open item ①
  // measured the false affordance that resulted (key bound, first click 400s).
  //
  // The assertions below are the pair the batch owes: the census (what changed
  // is EXACTLY three additions) and the route legs (the three are actually
  // servable through the ticket route, and a non-listed type still is not). The
  // pure-set assertions are no substitute for the route legs — a table entry the
  // verdict chain never consults would pass the set check and fail the route one.

  test("whitelist: legacy binary Office types joined (nfext batch)") {
    assert(NfFilePolicy.NfFileAllowedExt.contains("doc"))
    assert(NfFilePolicy.NfFileAllowedExt.contains("ppt"))
    assert(NfFilePolicy.NfFileAllowedExt.contains("xls"))
  }

  test("whitelist: census — the batch adds exactly doc/ppt/xls, deletes and renames nothing") {
    // The pre-change table, item for item (36 entries; captured from the branch
    // base tree as `.nebflow/evidence/20260917_nfext/before_ws_whitelist.txt`).
    // Equality in BOTH directions is the point: `++` alone would let a silent
    // deletion through, and `subsetOf` would let an unnoticed extra in.
    val atBranchBase = Set(
      "png",
      "jpg",
      "jpeg",
      "gif",
      "svg",
      "webp",
      "ico",
      "bmp",
      "avif",
      "tiff",
      "tif",
      "mp4",
      "webm",
      "ogg",
      "ogv",
      "mov",
      "mp3",
      "wav",
      "oga",
      "flac",
      "aac",
      "m4a",
      "woff",
      "woff2",
      "ttf",
      "otf",
      "pdf",
      "docx",
      "xlsx",
      "xlsm",
      "pptx",
      "epub",
      "js",
      "mjs",
      "css",
      "json"
    )
    assertEquals(atBranchBase.size, 36, "the base census must stay the documented 36 entries")
    assertEquals(
      NfFilePolicy.NfFileAllowedExt,
      atBranchBase ++ Set("doc", "ppt", "xls"),
      "the nfext batch changes this table by exactly three additions"
    )
  }

  test("serves legacy binary Office types through the ticket route (.doc/.ppt/.xls)") {
    // One seed per type; bytes are arbitrary but distinguishable, and the `.doc`
    // one carries the real CFB/OLE2 signature so the fixture states what the
    // batch is actually about (a legacy container, not an OOXML zip).
    val seeds: List[(String, Array[Byte])] = List(
      "old.doc" -> Array[Byte](0xd0.toByte, 0xcf.toByte, 0x11, 0xe0.toByte, 1),
      "old.ppt" -> Array[Byte](2, 3, 4),
      "old.xls" -> Array[Byte](5, 6, 7)
    )
    seeds.foreach { case (name, bytes) =>
      withSeedFile(name, bytes) { p =>
        IO {
          val resp = get(s"/api/nf-file?path=${p.toString}&ticket=${ticketFor(p.toString)}").get
          assertEquals(resp.status, Status.Ok, s"$name must be servable after the nfext batch")
          val served = resp.body.compile.toVector.unsafeRunSync().toArray
          assert(java.util.Arrays.equals(served, bytes), s"$name must be served byte-identically")
          // Reading (not hypothesising) the media type the serving leg derives
          // from the extension: `StaticFile.fromPath` is the same leg that serves
          // every pre-existing entry, so whatever it reports here is parity with
          // the rest of the table, not a new decision point.
          val ct = resp.headers.get(org.typelevel.ci.CIString("Content-Type")).map(_.head.value)
          println(s"[nfext-reading] $name Content-Type=$ct")
        }
      }
    }
  }

  test("still refuses the near-miss macro-enabled / executable types (docm, exe) with the file-type 400") {
    // `docm` is the deliberate near-miss: macro-enabled OOXML, same family as the
    // `docx` already served and the same `word/` part layout — it proves the
    // widening is by EXACT suffix, not by Office family.
    List("macro.docm", "evil.exe").foreach { name =>
      withSeedFile(name, Array[Byte](1, 2, 3)) { p =>
        IO {
          val resp = get(s"/api/nf-file?path=${p.toString}&ticket=${ticketFor(p.toString)}").get
          assertEquals(resp.status, Status.BadRequest, s"$name must stay refused")
          val body = bodyOf(resp)
          assert(body.contains("File type not allowed"), s"$name: unexpected body: $body")
          assert(body.startsWith("file-type:"), s"$name: the refusal must name the file-type reason: $body")
        }
      }
    }
  }
end NfFileRoutesSpec
