package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Route-level tests for StaticRoutes.pluginAssetsRoutes (canvas-media Wave1,
 * 2026-10-05, OD-2=V-C): the tokenized static face for plugin EXECUTOR
 * assets at `/api/plugin-assets/<name>/<rel…>`.
 *
 * Contract points under test:
 *  - token REQUIRED (cookie `nebflow_token` first, `?token=` fallback — the
 *    uploadsRoutes dual channel) — reject otherwise with 403;
 *  - Trusted-only via the injected resolver: unknown/blocked names get the
 *    same uniform 404 as a missing file (no existence oracle);
 *  - manual segment parsing: trailing slash, `..` segments, empty rel parts
 *    and encoded-backslash attempts all 404 (joined segments stay encoded,
 *    same as the /agents/ precedent — nothing decodes into a separator);
 *  - final defense = realpath containment (PluginRegistry.containedUnder):
 *    a symlink inside the package pointing outside the plugin root 404s;
 *  - happy path serves bytes at any nesting depth.
 */
class PluginAssetsRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"

  /** Fixture plugin tree: nested assets + a symlink-escape attempt inside it. */
  private def pluginFixture(tmp: os.Path): os.Path =
    val pluginDir = tmp / "plugins" / "demo-viewer"
    os.write.over(pluginDir / "assets" / "renderer.js", "export const x = 1;", createFolders = true)
    os.write.over(pluginDir / "assets" / "sub" / "deep.txt", "deep", createFolders = true)
    val outside = tmp / "outside-secret.txt"
    os.write.over(outside, "secret")
    java.nio.file.Files.createSymbolicLink((pluginDir / "escape").toNIO, outside.toNIO)
    pluginDir

  /**
   * Isolated fixture tree. The route is registry-agnostic by design — the
   * injected resolver plays the Trusted-only registry face, so no
   * PluginRegistry / dataRoot state is involved here (GatewayMain wires the
   * real resolver: PluginRegistry.scan().find(name && trust.trusted)).
   */
  private def withTempPlugin[A](test: os.Path => IO[A]): A =
    val tmp = os.Path(Files.createTempDirectory("nebflow-plugin-assets-test"))
    val pluginDir = pluginFixture(tmp)
    try test(pluginDir).unsafeRunSync()
    finally
      if Files.exists(tmp.toNIO) then
        Files
          .walk(tmp.toNIO)
          .sorted(java.util.Comparator.reverseOrder())
          .iterator()
          .asScala
          .foreach(Files.deleteIfExists)
  end withTempPlugin

  /** Stub registry resolver: only the trusted package "good" resolves. */
  private def resolverFor(pluginDir: os.Path)(name: String): IO[Option[os.Path]] =
    if name == "good" then IO.some(pluginDir) else IO.none

  private def get(pluginDir: os.Path)(path: String, cookie: Option[(String, String)] = None) =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
    val req = cookie.fold(base) { case (n, v) => base.addCookie(n, v) }
    StaticRoutes.pluginAssetsRoutes(gatewayToken, resolverFor(pluginDir))(req).value.unsafeRunSync()

  test("rejects unauthenticated requests") {
    withTempPlugin { dir =>
      IO {
        assertEquals(get(dir)("/plugin-assets/good/assets/renderer.js").get.status, Status.Forbidden)
      }
    }
  }

  test("rejects a wrong token") {
    withTempPlugin { dir =>
      IO {
        assertEquals(get(dir)("/plugin-assets/good/assets/renderer.js?token=wrong").get.status, Status.Forbidden)
      }
    }
  }

  test("serves a file via ?token=") {
    withTempPlugin { dir =>
      IO {
        val resp = get(dir)(s"/plugin-assets/good/assets/renderer.js?token=$gatewayToken").get
        assertEquals(resp.status, Status.Ok)
        val body = resp.bodyText.compile.string.unsafeRunSync()
        assertEquals(body, "export const x = 1;")
      }
    }
  }

  test("serves a file via nebflow_token cookie") {
    withTempPlugin { dir =>
      IO {
        val resp = get(dir)(
          "/plugin-assets/good/assets/renderer.js",
          cookie = Some(("nebflow_token", gatewayToken))
        ).get
        assertEquals(resp.status, Status.Ok)
      }
    }
  }

  test("serves nested paths") {
    withTempPlugin { dir =>
      IO {
        val resp = get(dir)(s"/plugin-assets/good/assets/sub/deep.txt?token=$gatewayToken").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.bodyText.compile.string.unsafeRunSync(), "deep")
      }
    }
  }

  test("uniform 404 for an unknown (or blocked) plugin — no existence oracle") {
    withTempPlugin { dir =>
      IO {
        assertEquals(get(dir)(s"/plugin-assets/ghost/assets/renderer.js?token=$gatewayToken").get.status, Status.NotFound)
        assertEquals(
          get(dir)(s"/plugin-assets/blocked/assets/renderer.js?token=$gatewayToken").get.status,
          Status.NotFound
        )
      }
    }
  }

  test("404s for a missing file inside a known plugin") {
    withTempPlugin { dir =>
      IO {
        assertEquals(get(dir)(s"/plugin-assets/good/assets/nope.js?token=$gatewayToken").get.status, Status.NotFound)
      }
    }
  }

  test("rejects traversal segments") {
    withTempPlugin { dir =>
      IO {
        assertEquals(
          get(dir)(s"/plugin-assets/good/assets/../../outside-secret.txt?token=$gatewayToken").get.status,
          Status.NotFound
        )
        assertEquals(
          get(dir)(s"/plugin-assets/../good/assets/renderer.js?token=$gatewayToken").get.status,
          Status.NotFound
        )
      }
    }
  }

  test("encoded-backslash attempts never reach the filesystem (segments stay encoded, like /agents/)") {
    withTempPlugin { dir =>
      IO {
        assertEquals(
          get(dir)("/plugin-assets/good/assets/..%5C..%5Coutside-secret.txt?token=" + gatewayToken).get.status,
          Status.NotFound
        )
      }
    }
  }

  test("rejects wrong path shape: no rel segment / trailing slash") {
    withTempPlugin { dir =>
      IO {
        assertEquals(get(dir)(s"/plugin-assets/good?token=$gatewayToken").get.status, Status.NotFound)
        assertEquals(get(dir)(s"/plugin-assets/good/?token=$gatewayToken").get.status, Status.NotFound)
        assertEquals(get(dir)(s"/plugin-assets/good/assets/?token=$gatewayToken").get.status, Status.NotFound)
      }
    }
  }

  test("symlink escape inside the package is refused (realpath containment final defense)") {
    withTempPlugin { dir =>
      IO {
        assertEquals(get(dir)(s"/plugin-assets/good/escape?token=$gatewayToken").get.status, Status.NotFound)
      }
    }
  }

end PluginAssetsRoutesSpec
