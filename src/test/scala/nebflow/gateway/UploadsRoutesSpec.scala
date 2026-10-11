package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil
import org.http4s.{Method, Request, Status, Uri}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Route-level tests for StaticRoutes.uploadsRoutes (G1): serving
 * ~/.nebflow/uploads/<sid>/<file> so restored session history can render
 * image attachments.
 *
 * Covers the three contract points: auth (cookie-first, ?token= fallback,
 * reject otherwise), normal serving, and path-traversal rejection.
 */
class UploadsRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"

  // Phase 3b: the criterion is Ref-based + needs a session store (the session
  // rail reads it; these specs exercise the legacy rails, so an empty store).
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](gatewayToken)
  private val Sessions = GatewaySessionStore.unsafeCreate()

  /** Isolated dataRoot with a seeded uploads/sess-1/shot.png; restored after. */
  private def withTempUploads[A](test: => IO[A]): A =
    val tmp = Files.createTempDirectory("nebflow-uploads-test")
    val originalRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(os.Path(tmp))
    os.write.over(
      os.Path(tmp) / "uploads" / "sess-1" / "shot.png",
      Array[Byte](1, 2, 3),
      createFolders = true
    )
    try test.unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      if Files.exists(tmp) then
        Files
          .walk(tmp)
          .sorted(java.util.Comparator.reverseOrder())
          .iterator()
          .asScala
          .foreach(
            Files.deleteIfExists
          )

  end withTempUploads

  private def get(path: String, cookie: Option[(String, String)] = None) =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
    val req = cookie.fold(base) { case (n, v) => base.addCookie(n, v) }
    StaticRoutes.uploadsRoutes(TokenRef, Sessions)(req).value.unsafeRunSync()

  test("rejects unauthenticated requests") {
    withTempUploads {
      IO {
        assertEquals(get("/uploads/sess-1/shot.png").get.status, Status.Forbidden)
      }
    }
  }

  test("rejects a wrong token") {
    withTempUploads {
      IO {
        assertEquals(get("/uploads/sess-1/shot.png?token=wrong").get.status, Status.Forbidden)
      }
    }
  }

  test("serves a file via ?token=") {
    withTempUploads {
      IO {
        val resp = get(s"/uploads/sess-1/shot.png?token=$gatewayToken").get
        assertEquals(resp.status, Status.Ok)
        val bytes = resp.body.compile.toVector.unsafeRunSync().toArray
        assert(java.util.Arrays.equals(bytes, Array[Byte](1, 2, 3)))
      }
    }
  }

  test("serves a file via nebflow_token cookie") {
    withTempUploads {
      IO {
        val resp =
          get("/uploads/sess-1/shot.png", cookie = Some(("nebflow_token", gatewayToken))).get
        assertEquals(resp.status, Status.Ok)
      }
    }
  }

  test("rejects traversal segments") {
    withTempUploads {
      IO {
        // Three segments — passes the shape check, must die on the ".." guard.
        assertEquals(get(s"/uploads/../shot.png?token=$gatewayToken").get.status, Status.NotFound)
      }
    }
  }

  test("rejects wrong path shape") {
    withTempUploads {
      IO {
        assertEquals(get(s"/uploads/sess-1?token=$gatewayToken").get.status, Status.NotFound)
        assertEquals(get(s"/uploads/a/b/c?token=$gatewayToken").get.status, Status.NotFound)
      }
    }
  }

  test("404s for a missing file") {
    withTempUploads {
      IO {
        assertEquals(get(s"/uploads/sess-1/nope.png?token=$gatewayToken").get.status, Status.NotFound)
      }
    }
  }

  // ── refresh-image fix (2026-10-09): serve the DECODED filename ─────────────
  // Regression: segments were matched on their percent-ENCODED wire form, so
  // every upload whose on-disk name carries non-ASCII characters or spaces —
  // every macOS screenshot (「截屏2026-10-09 07.10.22.jpg」) — 404'd on the
  // restored-history <img> fetch. The live row hid it: before a refresh the
  // attachment rides the inline data: preview; only after a reload do both
  // replay legs re-fetch from this route (both cache legs strip the preview).
  // Frontend byte-leg contract: persistence.buildUserAttachmentRows →
  // hydrateMedia → attachmentImageUrl(path) — the URL must fetch 200.

  /**
   * Mirrors the frontend `encodeURIComponent` (space → %20, `+` → %2B — NOT
   * form-encoding's space-to-plus): that is the exact spelling
   * attachmentImageUrl puts on the wire.
   */
  private def encodeURIComponent(s: String): String =
    val unreserved =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    val sb = new StringBuilder
    s.foreach { c =>
      if unreserved.indexOf(c) >= 0 then sb.append(c)
      else
        String
          .valueOf(c)
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
          .foreach(b => sb.append(String.format("%%%02X", java.lang.Integer.valueOf(b & 0xff))))
    }
    sb.result()

  end encodeURIComponent

  test("serves a non-ASCII + space filename via its encoded wire form (the refresh regression)") {
    withTempUploads {
      IO {
        val name = "截屏2026-10-09 07.10.22.png"
        os.write.over(
          PathUtil.dataRoot / "uploads" / "sess-1" / name,
          Array[Byte](9, 8, 7),
          createFolders = true
        )
        val resp = get(s"/uploads/sess-1/${encodeURIComponent(name)}?token=$gatewayToken").get
        assertEquals(resp.status, Status.Ok)
        val bytes = resp.body.compile.toVector.unsafeRunSync().toArray
        assert(java.util.Arrays.equals(bytes, Array[Byte](9, 8, 7)))
      }
    }
  }

  test("keeps a literal plus a plus on the path (no query-form semantics)") {
    withTempUploads {
      IO {
        val name = "a+b.png"
        os.write.over(
          PathUtil.dataRoot / "uploads" / "sess-1" / name,
          Array[Byte](1),
          createFolders = true
        )
        // plusIsSpace=false is the path default: `a+b.png` must not be decoded
        // into `a b.png` (which would 404).
        val resp = get(s"/uploads/sess-1/${encodeURIComponent(name)}?token=$gatewayToken").get
        assertEquals(resp.status, Status.Ok)
      }
    }
  }

  test("404s an encoded traversal — the decode must land INSIDE the guards") {
    withTempUploads {
      IO {
        // %2E%2E decodes to ".." before the guard sees it — same verdict as the
        // literal form.
        assertEquals(get(s"/uploads/%2E%2E/shot.png?token=$gatewayToken").get.status, Status.NotFound)
        // %2F-decoded `..` inside a segment would otherwise re-enter the base
        // through os.RelPath up-segments (sess-1/../uploads/shot.png resolves
        // back under uploads/): the decoded `/` clause is load-bearing.
        assertEquals(
          get(s"/uploads/sess-1/..%2Fuploads%2Fshot.png?token=$gatewayToken").get.status,
          Status.NotFound
        )
        // %00 would make java.nio throw InvalidPathException (→ 500) — the
        // decoded-NUL clause keeps the old uniform 404.
        assertEquals(get(s"/uploads/sess-1/x%00.png?token=$gatewayToken").get.status, Status.NotFound)
      }
    }
  }
end UploadsRoutesSpec
