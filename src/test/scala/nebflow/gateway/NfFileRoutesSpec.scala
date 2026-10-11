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
 * Contract points: ticket auth; byte-identical serving; missing file 404;
 * missing path param 400. (2026-10-07 askuser-attach-align: the former
 * whitelist gate is retired — any credential-clean local file serves; the
 * credential-namespace / hard-link / issuer assertions live in
 * NfTicketRoutesSpec (A1–A17); this file stays the behaviour-equivalence
 * surface (A13).
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
    NfFileRoutes.nfFileRoutes(store, policy)(req).value.unsafeRunSync()

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

  test("serves non-whitelisted executable types too (sh) — the extension gate is RETIRED (2026-10-07 flip)") {
    withSeedFile("evil.sh", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8)) { sh =>
      IO {
        // askuser-attach-align (author 09:35/09:41 orders): the whitelist is no
        // longer a gate — the bytes of any credential-clean local file serve
        // (the Canvas open face renders registered types and downloads the
        // rest; this endpoint is the byte transport for both).
        val resp = get(s"/api/nf-file?path=${sh.toString}&ticket=${ticketFor(sh.toString)}").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(bodyOf(resp), "#!/bin/sh\n")
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

  test("serves the near-miss macro-enabled / executable types (docm, exe) — byte transport only (2026-10-07 flip)") {
    // `docm` was the deliberate near-miss of the old exact-suffix whitelist;
    // with the gate retired the endpoint transports the bytes of any
    // credential-clean file (rendering/download decisions live in the viewer
    // registry, not here — this route never parses what it serves).
    List("macro.docm", "evil.exe").foreach { name =>
      withSeedFile(name, Array[Byte](1, 2, 3)) { p =>
        IO {
          val resp = get(s"/api/nf-file?path=${p.toString}&ticket=${ticketFor(p.toString)}").get
          assertEquals(resp.status, Status.Ok, s"$name must be served (extension gate retired)")
          val served = resp.body.compile.toVector.unsafeRunSync().toArray
          assert(java.util.Arrays.equals(served, Array[Byte](1, 2, 3)), s"$name must be byte-identical")
        }
      }
    }
  }

  // ── 2026-10-06 video-canvas batch: media Content-Type + HTTP Range (206) ──
  //
  // Author order 2026-10-06 13:24: merged.mp4 opened in Canvas as an EMPTY
  // Monaco editor — the frontend viewer registry had no `video` entry, so
  // renderFile fell to the `code` fallback on binary metadata. The fix is
  // two-layered: (1) the frontend viewers/video.js registration; (2) THIS byte
  // leg must serve the media families with a correct Content-Type and HTTP
  // Range negotiation (206 Partial Content) — the <video> progress-bar drag
  // depends on 206 + Content-Range.
  //
  // The serving leg itself is UNCHANGED (`StaticFile.fromPath(_, Some(req))`
  // has always been handed the request, which is what turns Range negotiation
  // on). These tests exist so the behaviour is no longer incidental: an http4s
  // bump or a route refactor cannot silently drop Range or mis-type the
  // extensions the video viewer claims (viewers/video.js extensions mirror
  // FileTypeRegistry's video family verbatim).

  /**
   * A GET carrying a raw Range header — deliberately NOT the typed
   * `org.http4s.headers.Range` model, so the test stays decoupled from
   * http4s header-model API shapes across versions.
   */
  private def getRange(path: String, range: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
      .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("Range"), range))
    NfFileRoutes.nfFileRoutes(store, policy)(req).value.unsafeRunSync()

  private def headerOf(resp: org.http4s.Response[IO], name: String): Option[String] =
    resp.headers.get(org.typelevel.ci.CIString(name)).map(_.head.value)

  test("serves .mp4 with the video/mp4 Content-Type + Accept-Ranges (the video viewer's primary case)") {
    withSeedFile("clip.mp4", Array.fill[Byte](4096)(7)) { mp4 =>
      IO {
        val resp = get(s"/api/nf-file?path=${mp4.toString}&ticket=${ticketFor(mp4.toString)}").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(
          headerOf(resp, "Content-Type"),
          Some("video/mp4"),
          s"a full .mp4 response must be typed video/mp4, got ${headerOf(resp, "Content-Type")}"
        )
        assertEquals(
          headerOf(resp, "Accept-Ranges"),
          Some("bytes"),
          s"the capability the <video> progress bar relies on must be advertised, got ${headerOf(resp, "Accept-Ranges")}"
        )
      }
    }
  }

  test("answers Range: bytes=0-1023 with 206 Partial Content + Content-Range (video seeking)") {
    withSeedFile("clip.mp4", Array.fill[Byte](4096)(7)) { mp4 =>
      IO {
        val resp = getRange(s"/api/nf-file?path=${mp4.toString}&ticket=${ticketFor(mp4.toString)}", "bytes=0-1023").get
        assertEquals(resp.status, Status.PartialContent, "a single Range request must be answered 206, not 200")
        assertEquals(
          headerOf(resp, "Content-Range"),
          Some("bytes 0-1023/4096"),
          s"Content-Range must carry the slice AND the total, got ${headerOf(resp, "Content-Range")}"
        )
        val served = resp.body.compile.toVector.unsafeRunSync()
        assertEquals(served.length, 1024, "the 206 body must be exactly the requested slice")
      }
    }
  }

  test("answers an open-ended Range (bytes=1024-) with 206 and the rest of the file") {
    withSeedFile("clip.mp4", Array.fill[Byte](4096)(7)) { mp4 =>
      IO {
        val resp = getRange(s"/api/nf-file?path=${mp4.toString}&ticket=${ticketFor(mp4.toString)}", "bytes=1024-").get
        assertEquals(resp.status, Status.PartialContent)
        assertEquals(headerOf(resp, "Content-Range"), Some("bytes 1024-4095/4096"))
        val served = resp.body.compile.toVector.unsafeRunSync()
        assertEquals(served.length, 3072)
      }
    }
  }

  test("media family Content-Types: video claims mp4/webm/ogv/ogg/mov, audio spot-check") {
    // One seed per extension the FRONTEND video viewer claims (plus one audio
    // spot check); read what the serving leg derives and pin it. The printout
    // is the readable record (nfext-batch idiom); the assertEquals lines are
    // the weld. `.ogg` reads audio/ogg — that is the SHARED MimeDB verdict
    // (StaticFile's own MediaType.forExtension leg) and is left as-is on
    // purpose: a <video> element decodes by container content, not by the
    // wire label, and adding a route-side MIME override would be a second
    // table where one shared source exists.
    val seeds: List[(String, String)] = List(
      "clip.mp4" -> "video/mp4",
      "clip.webm" -> "video/webm",
      "clip.mov" -> "video/quicktime",
      "clip.ogv" -> "video/ogg",
      "clip.ogg" -> "audio/ogg", // MimeDB verdict; playable in <video> regardless
      "tone.mp3" -> "audio/mpeg"
    )
    seeds.foreach { case (name, expected) =>
      withSeedFile(name, Array[Byte](1, 2, 3)) { p =>
        IO {
          val resp = get(s"/api/nf-file?path=${p.toString}&ticket=${ticketFor(p.toString)}").get
          assertEquals(resp.status, Status.Ok, s"$name must be servable")
          val ct = headerOf(resp, "Content-Type")
          println(s"[video-canvas] $name Content-Type=$ct (expected=$expected)")
          assertEquals(ct, Some(expected), s"$name must be served as $expected")
        }
      }
    }
  }
end NfFileRoutesSpec
