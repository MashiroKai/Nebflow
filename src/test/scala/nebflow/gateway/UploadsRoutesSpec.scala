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
 * Covers the three contract points: auth (the HttpOnly session cookie is the
 * one credential — stage D retired the legacy file-token rails, and those
 * refusals are pinned too), normal serving, and path-traversal rejection.
 */
class UploadsRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"

  // Phase 3b: the criterion is Ref-based + needs a session store. Stage D
  // retired the file-token rails, so the positive faces below authenticate
  // through the session rail (the production shape for these routes).
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](gatewayToken)
  private val Sessions = GatewaySessionStore.unsafeCreate()

  /** A live session credential for the session rail. */
  private def sessionCred: IO[(String, String)] =
    Sessions
      .create("uploads-spec", GatewaySessionStore.OriginRelayMint, "net-1")
      .map(rec => (GatewaySessionStore.CookieName, rec.sessionId))

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
    // Stage C re-anchor: in-process requests carry no remote (= tunnel shape),
    // so the builder pins the pre-stage-C default — a direct loopback client.
    val base = SpecRequests.withRemote(
      Request[IO](Method.GET, Uri.unsafeFromString(path)),
      Some(SpecRequests.LoopbackV4)
    )
    val req = cookie.fold(base) { case (n, v) => base.addCookie(n, v) }
    StaticRoutes.uploadsRoutes(TokenRef, Sessions)(req).value.unsafeRunSync()

  test("rejects unauthenticated requests") {
    withTempUploads {
      IO {
        assertEquals(get("/uploads/sess-1/shot.png").get.status, Status.Forbidden)
      }
    }
  }

  test("rejects a token query param (the rail is retired — any token value)") {
    withTempUploads {
      IO {
        assertEquals(get("/uploads/sess-1/shot.png?token=wrong").get.status, Status.Forbidden)
      }
    }
  }

  test("the retired token query rail is refused even with the LIVE file token (stage D)") {
    withTempUploads {
      IO {
        assertEquals(get(s"/uploads/sess-1/shot.png?token=$gatewayToken").get.status, Status.Forbidden)
      }
    }
  }

  test("the retired legacy nebflow_token cookie rail is refused even with the LIVE file token (stage D)") {
    withTempUploads {
      IO {
        assertEquals(
          get("/uploads/sess-1/shot.png", cookie = Some(("nebflow_token", gatewayToken))).get.status,
          Status.Forbidden
        )
      }
    }
  }

  test("serves a file via the session cookie") {
    withTempUploads {
      for
        cred <- sessionCred
        resp = get("/uploads/sess-1/shot.png", cookie = Some(cred)).get
        _ = assertEquals(resp.status, Status.Ok)
        bytes = resp.body.compile.toVector.unsafeRunSync().toArray
        _ = assert(java.util.Arrays.equals(bytes, Array[Byte](1, 2, 3)))
      yield ()
    }
  }

  // ── 二次加载清剿批（2026-10-24）：#538 同款确定性浏览器缓存 ─────────────────
  // 修前形态：本路由带 ETag+Last-Modified（StaticFile）并应答 304，但从未发
  // Cache-Control ⇒ 重复消费按启发式新鲜度（截图类新文件 ≈ 秒级窗），媒体脸每次
  // 重渲都把同一体再拉一遍。`private, max-age=300`：5 分钟内零字节往返，其后一次
  // 条件往返 304（304 必须重申策略）；404 腿不带（不可缓存面不染色）。
  test("200 carries the deterministic private cache policy (#538 family)") {
    withTempUploads {
      for
        cred <- sessionCred
        resp = get("/uploads/sess-1/shot.png", cookie = Some(cred)).get
        _ = assertEquals(resp.status, Status.Ok)
        _ = assertEquals(
          resp.headers.get[org.http4s.headers.`Cache-Control`].map(_.values.toList.mkString(", ")),
          Some("private, max-age=300")
        )
      yield ()
    }
  }

  test("304 revalidation restates the cache policy; 404 stays uncolored") {
    withTempUploads {
      for
        cred <- sessionCred
        ok = get("/uploads/sess-1/shot.png", cookie = Some(cred)).get
        _ = assertEquals(ok.status, Status.Ok)
        etag = ok.headers.get[org.http4s.headers.ETag].map(_.tag.toString).getOrElse(fail("ETag missing"))
        // Same request shape with If-None-Match added on the wire:
        _ = {
          val req = StaticRoutes
            .uploadsRoutes(TokenRef, Sessions)(
              SpecRequests.withRemote(
                Request[IO](Method.GET, Uri.unsafeFromString("/uploads/sess-1/shot.png"))
                  .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("If-None-Match"), etag)),
                Some(SpecRequests.LoopbackV4)
              ).addCookie(GatewaySessionStore.CookieName, cred(1))
            )
            .value
            .unsafeRunSync()
            .get
          assertEquals(req.status, Status.NotModified)
          assertEquals(
            req.headers.get[org.http4s.headers.`Cache-Control`].map(_.values.toList.mkString(", ")),
            Some("private, max-age=300")
          )
        }
        missing = get("/uploads/sess-1/nope.png", cookie = Some(cred)).get
        _ = assertEquals(missing.status, Status.NotFound)
        _ = assertEquals(missing.headers.get[org.http4s.headers.`Cache-Control`], None)
      yield ()
    }
  }

  test("rejects traversal segments") {
    withTempUploads {
      for
        cred <- sessionCred
        // Three segments — passes the shape check, must die on the ".." guard.
        _ = assertEquals(get(s"/uploads/../shot.png", cookie = Some(cred)).get.status, Status.NotFound)
      yield ()
    }
  }

  test("rejects wrong path shape") {
    withTempUploads {
      for
        cred <- sessionCred
        _ = assertEquals(get(s"/uploads/sess-1", cookie = Some(cred)).get.status, Status.NotFound)
        _ = assertEquals(get(s"/uploads/a/b/c", cookie = Some(cred)).get.status, Status.NotFound)
      yield ()
    }
  }

  test("404s for a missing file") {
    withTempUploads {
      for
        cred <- sessionCred
        _ = assertEquals(get(s"/uploads/sess-1/nope.png", cookie = Some(cred)).get.status, Status.NotFound)
      yield ()
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
      for
        cred <- sessionCred
        name = "截屏2026-10-09 07.10.22.png"
        _ = os.write.over(
          PathUtil.dataRoot / "uploads" / "sess-1" / name,
          Array[Byte](9, 8, 7),
          createFolders = true
        )
        resp = get(s"/uploads/sess-1/${encodeURIComponent(name)}", cookie = Some(cred)).get
        _ = assertEquals(resp.status, Status.Ok)
        bytes = resp.body.compile.toVector.unsafeRunSync().toArray
        _ = assert(java.util.Arrays.equals(bytes, Array[Byte](9, 8, 7)))
      yield ()
    }
  }

  test("keeps a literal plus a plus on the path (no query-form semantics)") {
    withTempUploads {
      for
        cred <- sessionCred
        name = "a+b.png"
        _ = os.write.over(
          PathUtil.dataRoot / "uploads" / "sess-1" / name,
          Array[Byte](1),
          createFolders = true
        )
        // plusIsSpace=false is the path default: `a+b.png` must not be decoded
        // into `a b.png` (which would 404).
        resp = get(s"/uploads/sess-1/${encodeURIComponent(name)}", cookie = Some(cred)).get
        _ = assertEquals(resp.status, Status.Ok)
      yield ()
    }
  }

  test("404s an encoded traversal — the decode must land INSIDE the guards") {
    withTempUploads {
      for
        cred <- sessionCred
        // %2E%2E decodes to ".." before the guard sees it — same verdict as the
        // literal form.
        _ = assertEquals(
          get(s"/uploads/%2E%2E/shot.png", cookie = Some(cred)).get.status,
          Status.NotFound
        )
        // %2F-decoded `..` inside a segment would otherwise re-enter the base
        // through os.RelPath up-segments (sess-1/../uploads/shot.png resolves
        // back under uploads/): the decoded `/` clause is load-bearing.
        _ = assertEquals(
          get(s"/uploads/sess-1/..%2Fuploads%2Fshot.png", cookie = Some(cred)).get.status,
          Status.NotFound
        )
        // %00 would make java.nio throw InvalidPathException (→ 500) — the
        // decoded-NUL clause keeps the old uniform 404.
        _ = assertEquals(get(s"/uploads/sess-1/x%00.png", cookie = Some(cred)).get.status, Status.NotFound)
      yield ()
    }
  }
end UploadsRoutesSpec
