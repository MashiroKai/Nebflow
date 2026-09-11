package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.{Method, Request, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * 服务端断言组 A1–A16（票据腿：签发端点 + 读端点凭据命名空间）。
 *
 * 形态对齐 `NfFileRoutesSpec`：companion 纯路由直调 + 注入内存 store + 注入
 * `NfPathPolicy`（P1/P3 指向临时目录），零实例、零网络、零真实 `~/.nebflow`
 * 写操作。A13（`NfFileRoutesSpec` 既有 11 例）由该 spec 自身覆盖。
 */
class NfTicketRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"

  /** 隔离的 P1（dataRoot）/ P3（workspace）——临时目录，真测试里不碰真实家目录。
    *
    * `policy` is a lazy val on purpose: the R2 inode snapshot must be taken
    * AFTER `seed()` has created the credential files, otherwise the set is
    * empty and the hard-link guard silently has nothing to match. */
  private final class Env(
    val root: Path,
    val dataRoot: Path,
    val workspaceRoot: Path,
    val outside: Path
  ):
    lazy val policy: WebSocketRoutes.NfPathPolicy =
      WebSocketRoutes.NfPathPolicy(
        dataRoot,
        workspaceRoot,
        WebSocketRoutes.NfPathPolicy.scanCredentialInodes(dataRoot)
      )

    val store: NfTicketStore = NfTicketStore.unsafeCreate(1800)

    def ticketFor(p: Path): String =
      store.issue("spec", p.toRealPath().toString).unsafeRunSync().token

    def read(query: String) =
      val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-file?$query"))
      WebSocketRoutes
        .nfFileRoutes(gatewayToken, store, policy)(req)
        .value
        .unsafeRunSync()

    def issue(body: Json) =
      val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/nf-ticket?token=$gatewayToken"))
        .withEntity(body)
      WebSocketRoutes.nfTicketRoutes(gatewayToken, store, policy)(req).value.unsafeRunSync()

    /** Seed `dataRoot/projects/demo/reports/plot.svg` (allowlisted) + credential files. */
    def seed(): Unit =
      Files.createDirectories(dataRoot.resolve("projects/demo/reports"))
      Files.write(dataRoot.resolve("projects/demo/reports/plot.svg"), "<svg/>".getBytes(StandardCharsets.UTF_8))
      Files.createDirectories(dataRoot.resolve("secrets"))
      Files.write(dataRoot.resolve("secrets/x.txt"), "s".getBytes(StandardCharsets.UTF_8))
      Files.write(dataRoot.resolve("auth.json"), "\"token\"".getBytes(StandardCharsets.UTF_8))
      Files.write(dataRoot.resolve("nebflow.json"), "{}".getBytes(StandardCharsets.UTF_8))
      Files.write(outside.resolve("x.json"), "{\"ok\":true}".getBytes(StandardCharsets.UTF_8))
      Files.write(outside.resolve("vps.env"), "A=1".getBytes(StandardCharsets.UTF_8))

    def pathOf(rel: String): Path = dataRoot.resolve(rel)

  private def withEnv[A](test: Env => IO[A]): A =
    val root = Files.createTempDirectory("nebflow-nfticket-test").toRealPath()
    val dataRoot = Files.createDirectories(root.resolve("dataroot"))
    val workspaceRoot = Files.createDirectories(root.resolve("ws"))
    val outside = Files.createDirectories(root.resolve("outside"))
    val env = Env(root, dataRoot, workspaceRoot, outside)
    env.seed()
    try test(env).unsafeRunSync()
    finally
      Files
        .walk(root)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(p => Files.deleteIfExists(p))

  private def bodyOf(resp: org.http4s.Response[IO]): String =
    resp.bodyText.compile.string.unsafeRunSync()

  private def reasonOf(resp: org.http4s.Response[IO]): String =
    resp.headers.get(org.typelevel.ci.CIString("X-Nf-Reason")).map(_.head.value).getOrElse("")

  private def url(path: Path): String = java.net.URLEncoder.encode(path.toString, "UTF-8")

  // ── A1 ───────────────────────────────────────────────────────────────
  test("A1: a request without a ticket is 401 (ticket-only read leg, R5)") {
    withEnv { env =>
      IO {
        val resp = env.read(s"path=${url(env.pathOf("projects/demo/reports/plot.svg"))}").get
        assertEquals(resp.status, Status.Unauthorized)
        assertEquals(bodyOf(resp), "Missing 'ticket' parameter")
      }
    }
  }

  // ── A2 ───────────────────────────────────────────────────────────────
  test("A2: a ticket bound to another path is 403 path-mismatch") {
    withEnv { env =>
      IO {
        val ticket = env.ticketFor(env.outside.resolve("x.json"))
        val resp = env.read(s"path=${url(env.pathOf("projects/demo/reports/plot.svg"))}&ticket=$ticket").get
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(reasonOf(resp), "path-mismatch")
      }
    }
  }

  // ── A3 ───────────────────────────────────────────────────────────────
  test("A3: an expired ticket is 403 expired") {
    withEnv { env =>
      val shortStore = NfTicketStore.unsafeCreate(1)
      val real = env.outside.resolve("x.json").toRealPath()
      // Mint FIRST, then let the 1s TTL elapse — otherwise the ticket is
      // brand new after the sleep and the assertion proves nothing.
      val token = shortStore.issue("spec", real.toString).unsafeRunSync().token
      IO.blocking(Thread.sleep(1300)).flatMap { _ =>
        IO {
          val req = Request[IO](
            Method.GET,
            Uri.unsafeFromString(s"/api/nf-file?path=${url(env.outside.resolve("x.json"))}&ticket=$token")
          )
          val resp = WebSocketRoutes
            .nfFileRoutes(gatewayToken, shortStore, env.policy)(req)
            .value
            .unsafeRunSync()
            .get
          assertEquals(resp.status, Status.Forbidden)
          assertEquals(reasonOf(resp), "expired")
        }
      }
    }
  }

  // ── A4 ───────────────────────────────────────────────────────────────
  test("A4: R4 = unlimited inside the TTL — five reads on one ticket all 200") {
    withEnv { env =>
      IO {
        val file = env.outside.resolve("x.json")
        val ticket = env.ticketFor(file)
        (1 to 5).foreach { _ =>
          assertEquals(env.read(s"path=${url(file)}&ticket=$ticket").get.status, Status.Ok)
        }
        // The issuer advertises the budget as the Unlimited sentinel (-1), not 0.
        val issued = env.store.issue("spec", file.toRealPath().toString).unsafeRunSync()
        assertEquals(issued.remaining, NfTicketStore.Unlimited)
      }
    }
  }

  // ── A5 / A6 ──────────────────────────────────────────────────────────
  test("A5/A6: data-root credential paths are 403 credential-path even WITH a valid ticket") {
    withEnv { env =>
      IO {
        // The ticket is minted straight from the store — i.e. it deliberately
        // bypasses the issuer's own rejection (A11) to prove the READ leg
        // refuses independently. Defence in depth, not a single chokepoint.
        for rel <- List("auth.json", "nebflow.json", "secrets/x.txt") do
          val real = env.pathOf(rel).toRealPath()
          val resp = env.read(s"path=${url(real)}&ticket=${env.ticketFor(real)}").get
          assertEquals(resp.status, Status.Forbidden, s"$rel")
          assertEquals(reasonOf(resp), "credential-path", s"$rel")
      }
    }
  }

  // ── A7 ───────────────────────────────────────────────────────────────
  test("A7: a file outside the protected namespaces still serves (no over-blocking)") {
    withEnv { env =>
      IO {
        val file = env.outside.resolve("x.json")
        val resp = env.read(s"path=${url(file)}&ticket=${env.ticketFor(file)}").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(bodyOf(resp), "{\"ok\":true}")
      }
    }
  }

  // ── A8 ───────────────────────────────────────────────────────────────
  test("A8: extension gate still runs, and runs AFTER the credential gate (reason=file-type)") {
    withEnv { env =>
      IO {
        val file = env.outside.resolve("vps.env")
        val resp = env.read(s"path=${url(file)}&ticket=${env.ticketFor(file)}").get
        assertEquals(resp.status, Status.BadRequest)
        // reason=file-type proves the credential judge let it through (it is
        // outside the namespaces) and the extension judge is the one refusing.
        assertEquals(reasonOf(resp), "file-type")
      }
    }
  }

  // ── A9 ───────────────────────────────────────────────────────────────
  test("A9: a symlink into a credential file is refused (realpath normalization, C1-1/C1-2)") {
    withEnv { env =>
      IO {
        val link = env.outside.resolve("link.json")
        Files.createSymbolicLink(link, env.pathOf("auth.json"))
        val resp = env.read(s"path=${url(link)}&ticket=${env.ticketFor(link)}").get
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(reasonOf(resp), "credential-path")
      }
    }
  }

  // ── A10 ──────────────────────────────────────────────────────────────
  test("A10: a hard link to a credential file is refused ((dev,ino) guard, R2)") {
    withEnv { env =>
      IO {
        val hard = env.outside.resolve("hard.json")
        Files.createLink(hard, env.pathOf("auth.json"))
        // Sanity: the alias is NOT under the data root, so only the inode
        // guard can catch it (realpath sees a completely unrelated path).
        assertEquals(WebSocketRoutes.nfCredentialDeny(hard.toRealPath(), env.policy), None)
        val resp = env.read(s"path=${url(hard)}&ticket=${env.ticketFor(hard)}").get
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(reasonOf(resp), "credential-hardlink")
      }
    }
  }

  // ── A11 / A12 / A16 ──────────────────────────────────────────────────
  test("A11/A12/A16: the issuer rejects credential paths and bad extensions, and never signs them") {
    withEnv { env =>
      IO {
        val cred = env.pathOf("auth.json")
        val badExt = env.outside.resolve("vps.env")
        val good = env.outside.resolve("x.json")
        val resp = env
          .issue(
            Json.obj(
              "sessionId" -> Json.fromString("spec"),
              "paths" -> Json.arr(
                Json.fromString(cred.toString),
                Json.fromString(badExt.toString),
                Json.fromString(good.toString),
                Json.fromString("/nonexistent/definitely-missing.json")
              )
            )
          )
          .get
        assertEquals(resp.status, Status.Ok)
        val body = parse(bodyOf(resp)).toOption.getOrElse(Json.Null)
        // A16 — structure
        assert(body.hcursor.downField("tickets").succeeded)
        assert(body.hcursor.downField("rejected").succeeded)
        val tickets = body.hcursor.downField("tickets").keys.getOrElse(Nil).toList
        val rejected = body.hcursor.downField("rejected").values.getOrElse(Vector.empty).toList
        // A11 — the credential path got no ticket, and is reported
        assert(!tickets.contains(cred.toString))
        assert(rejected.exists(j => j.hcursor.downField("path").as[String].toOption.contains(cred.toString)))
        assert(
          rejected.exists(j =>
            j.hcursor.downField("path").as[String].toOption.contains(cred.toString) &&
              j.hcursor.downField("reason").as[String].toOption.contains("credential-path")
          )
        )
        // A12 — the non-whitelisted extension got no ticket
        assert(!tickets.contains(badExt.toString))
        assert(
          rejected.exists(j =>
            j.hcursor.downField("path").as[String].toOption.contains(badExt.toString) &&
              j.hcursor.downField("reason").as[String].toOption.contains("file-type")
          )
        )
        // A16 — the opaque value must not leak the path it unlocks
        val token = body.hcursor
          .downField("tickets")
          .downField(good.toString)
          .downField("t")
          .as[String]
          .toOption
          .getOrElse("")
        assert(token.length > 16, token)
        assert(!token.contains("x.json"))
        assert(!token.contains("/")) // base64url alphabet
      }
    }
  }

  test("issuer: requires the gateway token (403 without it)") {
    withEnv { env =>
      IO {
        val req = Request[IO](Method.POST, Uri.unsafeFromString("/api/nf-ticket"))
          .withEntity(Json.obj("sessionId" -> Json.fromString("s"), "paths" -> Json.arr()))
        val resp = WebSocketRoutes
          .nfTicketRoutes(gatewayToken, env.store, env.policy)(req)
          .value
          .unsafeRunSync()
          .get
        assertEquals(resp.status, Status.Forbidden)
      }
    }
  }

  // ── A15 ──────────────────────────────────────────────────────────────
  test("A15: GET /api/nf-authcheck is 204 with a valid credential, 403 otherwise, never touches disk") {
    withEnv { env =>
      IO {
        def probe(query: String) =
          val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-authcheck$query"))
          WebSocketRoutes.nfAuthcheckRoutes(gatewayToken)(req).value.unsafeRunSync().get
        assertEquals(probe(s"?token=$gatewayToken").status, Status.NoContent)
        assertEquals(probe("").status, Status.Forbidden)
        assertEquals(probe("?token=wrong").status, Status.Forbidden)
        // No disk involvement: a path that cannot exist is irrelevant — the
        // route never reads one (that is the whole point of R9 = O-A).
        assertEquals(
          probe(s"?token=$gatewayToken&path=/nonexistent/definitely-missing.json").status,
          Status.NoContent
        )
      }
    }
  }

  // ── A14 lives in nebflow.core.tools.FileRefsWhitelistSpec (FileRefs is
  //    private[tools]; the gateway package cannot see it).

  // ── R1 policy shape ──────────────────────────────────────────────────
  test("policy: P1 is PathUtil.dataRoot (never a hardcoded home directory)") {
    val policy = WebSocketRoutes.NfPathPolicy.memoized()
    assertEquals(policy.dataRoot, Paths.get(PathUtil.dataRoot.toString).toRealPath())
  }

  test("policy: a newly added data-root directory is denied by default (fail-closed)") {
    withEnv { env =>
      IO {
        Files.write(env.pathOf("future-thing.json"), "{}".getBytes(StandardCharsets.UTF_8))
        val real = env.pathOf("future-thing.json").toRealPath()
        val resp = env.read(s"path=${url(real)}&ticket=${env.ticketFor(real)}").get
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(reasonOf(resp), "credential-path")
      }
    }
  }
