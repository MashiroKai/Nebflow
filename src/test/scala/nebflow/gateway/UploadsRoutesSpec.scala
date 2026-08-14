package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import org.http4s.{Method, Request, Status, Uri}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Route-level tests for WebSocketRoutes.uploadsRoutes (G1): serving
 * ~/.nebflow/uploads/<sid>/<file> so restored session history can render
 * image attachments.
 *
 * Covers the three contract points: auth (cookie-first, ?token= fallback,
 * reject otherwise), normal serving, and path-traversal rejection.
 */
class UploadsRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"

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
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(
          Files.deleteIfExists
        )

  private def get(path: String, cookie: Option[(String, String)] = None) =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
    val req = cookie.fold(base) { case (n, v) => base.addCookie(n, v) }
    WebSocketRoutes.uploadsRoutes(gatewayToken)(req).value.unsafeRunSync()

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
