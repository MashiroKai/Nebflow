package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.shared.PathUtil
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Method, Request, Status, Uri}

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

  // Phase 3b: the criterion is Ref-based + needs a session store (the route
  // constructors take one; stage D retired the token query rail, so the
  // positive faces here authenticate via the file-token Bearer).
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](gatewayToken)
  private val Sessions = GatewaySessionStore.unsafeCreate()

  /**
   * 隔离的 P1（dataRoot）/ P3（workspace）——临时目录，真测试里不碰真实家目录。
   *
   * `policy` is a lazy val on purpose: the R2 inode snapshot must be taken
   * AFTER `seed()` has created the credential files, otherwise the set is
   * empty and the hard-link guard silently has nothing to match.
   */
  private final class Env(
    val root: Path,
    val dataRoot: Path,
    val workspaceRoot: Path,
    val outside: Path
  ):

    lazy val policy: NfFilePolicy.NfPathPolicy =
      NfFilePolicy.NfPathPolicy(
        dataRoot,
        workspaceRoot,
        NfFilePolicy.NfPathPolicy.scanCredentialInodes(dataRoot)
      )

    val store: NfTicketStore = NfTicketStore.unsafeCreate(1800)

    def ticketFor(p: Path): String =
      store.issue("spec", p.toRealPath().toString).unsafeRunSync().token

    def read(query: String) =
      val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-file?$query"))
      NfFileRoutes
        .nfFileRoutes(store, policy)(req)
        .value
        .unsafeRunSync()

    def issue(body: Json) =
      // Stage D re-anchor: the token query rail is retired, so the mint
      // request authenticates through the surviving WsAuth Bearer rail (the
      // file token) — the same credential a non-browser caller uses today.
      // Stage C pin kept: a direct loopback client.
      val req = SpecRequests
        .withRemote(
          Request[IO](Method.POST, Uri.unsafeFromString("/api/nf-ticket"))
            .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, gatewayToken))),
          Some(SpecRequests.LoopbackV4)
        )
        .withEntity(body)
      NfFileRoutes.nfTicketRoutes(TokenRef, store, policy, Sessions)(req).value.unsafeRunSync()

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

  end Env

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

  end withEnv

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
          val resp = NfFileRoutes
            .nfFileRoutes(shortStore, env.policy)(req)
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
  test("A5/A6: credential paths are 403 even WITH a valid ticket (identity pattern + R2 inode)") {
    withEnv { env =>
      IO {
        // The ticket is minted straight from the store — i.e. it deliberately
        // bypasses the issuer's own rejection (A11) to prove the READ leg
        // refuses independently. Defence in depth, not a single chokepoint.
        // 2026-10-07 askuser-attach-align flip: the namespace reach layer is
        // gone; the refusing layer is the IDENTITY face — the basename tables
        // (auth.json / nebflow.json) and the LIVE root-anchored seed
        // (auth.json / nebflow.json / the secrets subtree inside the data root;
        // the R2 inode snapshot remains the alias guard on top).
        for (rel, want) <- List(
            ("auth.json", "credential-path"),
            ("nebflow.json", "credential-path"),
            ("secrets/x.txt", "credential-path")
          )
        do
          val real = env.pathOf(rel).toRealPath()
          val resp = env.read(s"path=${url(real)}&ticket=${env.ticketFor(real)}").get
          assertEquals(resp.status, Status.Forbidden, s"$rel")
          assertEquals(reasonOf(resp), want, s"$rel")
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
  test("A8: the extension gate is RETIRED — a non-whitelisted, credential-clean file serves (2026-10-07 flip)") {
    withEnv { env =>
      IO {
        // `vps.env` was the A8 fixture precisely because the basename is
        // credential-SHAPED... wait, no: `.env` files match the pattern only
        // as a BARE `.env` name; `vps.env` never matched — the old refusal
        // here came from the extension judge. With that judge retired, the
        // file serves like any other credential-clean local file.
        val file = env.outside.resolve("vps.env")
        val resp = env.read(s"path=${url(file)}&ticket=${env.ticketFor(file)}").get
        assertEquals(resp.status, Status.Ok)
        assertEquals(bodyOf(resp), "A=1")
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
        assertEquals(NfFilePolicy.nfCredentialDeny(hard.toRealPath(), env.policy), None)
        val resp = env.read(s"path=${url(hard)}&ticket=${env.ticketFor(hard)}").get
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(reasonOf(resp), "credential-hardlink")
      }
    }
  }

  // ── A11 / A12 / A16 ──────────────────────────────────────────────────
  test("A11/A16: the issuer rejects credential paths and never signs them; non-credential paths all sign") {
    withEnv { env =>
      IO {
        val cred = env.pathOf("auth.json")
        val formerBadExt = env.outside.resolve("vps.env") // A12 retired with the extension gate
        val good = env.outside.resolve("x.json")
        val resp = env
          .issue(
            Json.obj(
              "sessionId" -> Json.fromString("spec"),
              "paths" -> Json.arr(
                Json.fromString(cred.toString),
                Json.fromString(formerBadExt.toString),
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
        assert(
          rejected.exists(j =>
            j.hcursor.downField("path").as[String].toOption.contains(cred.toString) &&
              j.hcursor.downField("reason").as[String].toOption.contains("credential-path")
          )
        )
        // A12 (retired 2026-10-07): the extension face no longer rejects —
        // the formerly "bad extension" file is SIGNED like any other
        // credential-clean local file.
        assert(tickets.contains(formerBadExt.toString), "the extension gate is retired: vps.env must sign")
        assert(!rejected.exists(j => j.hcursor.downField("path").as[String].toOption.contains(formerBadExt.toString)))
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
        val resp = NfFileRoutes
          .nfTicketRoutes(TokenRef, env.store, env.policy, Sessions)(req)
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
        def probe(query: String, bearer: Option[String] = None) =
          // Stage C re-anchor: pin the pre-stage-C default — a direct loopback client.
          val base = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-authcheck$query"))
          val req = SpecRequests.withRemote(
            bearer.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t)))),
            Some(SpecRequests.LoopbackV4)
          )
          NfFileRoutes.nfAuthcheckRoutes(TokenRef, Sessions)(req).value.unsafeRunSync().get
        // Stage D: the valid credential is the file-token Bearer (the token
        // query rail is retired and must refuse even the LIVE token).
        assertEquals(probe("", bearer = Some(gatewayToken)).status, Status.NoContent)
        assertEquals(probe("").status, Status.Forbidden)
        assertEquals(probe("?token=wrong").status, Status.Forbidden)
        assertEquals(
          probe(s"?token=$gatewayToken").status,
          Status.Forbidden,
          "stage D: the token query rail is retired — the live token in the query no longer authenticates"
        )
        // No disk involvement: a path that cannot exist is irrelevant — the
        // route never reads one (that is the whole point of R9 = O-A).
        assertEquals(
          probe("?path=/nonexistent/definitely-missing.json", bearer = Some(gatewayToken)).status,
          Status.NoContent
        )
      }
    }
  }

  // ── Session TTL batch (2026-10-21): the authcheck 204 slides the browser
  //    cookie on a SESSION-rail hit (same id, fresh Max-Age = absolute TTL);
  //    Bearer hits emit nothing; #522 secure-per-origin on the slide too.
  test("A15-slide: a session-rail authcheck re-emits the cookie with a fresh Max-Age; Bearer hits emit nothing") {
    withEnv { _ =>
      IO {
        def probe(uri: String, cookie: Option[(String, String)] = None, bearer: Option[String] = None) =
          val base = Request[IO](Method.GET, Uri.unsafeFromString(uri))
          val authed = cookie.fold(base)(c => base.addCookie(c._1, c._2))
          val withBearer = bearer.fold(authed)(t => authed.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))
          NfFileRoutes
            .nfAuthcheckRoutes(TokenRef, Sessions)(
              SpecRequests.withRemote(withBearer, Some(SpecRequests.LoopbackV4))
            )
            .value
            .unsafeRunSync()
            .get

        val sid = Sessions.create("logto|u", GatewaySessionStore.OriginRelayMint, "net").unsafeRunSync().sessionId
        val name = GatewaySessionStore.CookieName

        // Session rail, plain-http direct leg: slid cookie present, Secure omitted (#522).
        val plain = probe("/api/nf-authcheck", cookie = Some((name, sid)))
        assertEquals(plain.status, Status.NoContent)
        val plainCookie = plain.cookies.find(_.name == name)
        assert(plainCookie.isDefined, "a session-rail hit must slide the browser cookie")
        val pc = plainCookie.get
        assertEquals(pc.content, sid, "the slide re-emits the SAME session id — the value never changes")
        assertEquals(pc.maxAge, Some(GatewaySessionStore.AbsoluteTtlSeconds), "fresh Max-Age = 30d absolute TTL")
        assert(pc.httpOnly && pc.sameSite.contains(org.http4s.SameSite.Lax), "frozen attrs unchanged")
        assert(!pc.secure, "#522: plain-http direct leg slide omits Secure")
        assertEquals(pc.domain, None, "still host-only")
        assertEquals(pc.path, Some("/"), "still Path=/")

        // Session rail, https leg: the slide carries Secure.
        val https = probe("https://console.example.test/api/nf-authcheck", cookie = Some((name, sid)))
        val hc = https.cookies.find(_.name == name)
        assert(hc.exists(_.secure), "https leg slide keeps Secure")

        // Bearer rail: NO cookie slide (there is no session record behind it).
        val bearerHit = probe("/api/nf-authcheck", bearer = Some(gatewayToken))
        assertEquals(bearerHit.status, Status.NoContent)
        assertEquals(bearerHit.cookies.find(_.name == name), None, "Bearer hits must not emit a cookie")
      }
    }
  }

  // ── A14 lives in nebflow.core.tools.FileRefsWhitelistSpec (FileRefs is
  //    private[tools]; the gateway package cannot see it).

  // ── A17 (img-ticket batch i, 2026-09-16 · #687-A): `<dataRoot>/docs/**` ──
  //
  // 作者裁定：`~/.nebflow/docs/**`（人交付落位）加入 A1 数据根命名空间白名单。
  // 2026-10-07 askuser-attach-align 批（作者 09:35/09:41 两令）后：白名单整体退役
  // （A17 的 200 由「白名单成员」变成「翻转后的普遍语义」——用例保留作回归锚），
  // 唯一保留的拒绝维 = 凭据身份 + R2 inode（见 A17-counter 的翻转后读数）。
  //  A17  —— 走**既有链**铸票并读取（🔴 无新旁路、无绕票据）：铸票成功 + 读取 200。
  test("A17: `<dataRoot>/docs/**` mints a ticket and reads 200 (author ruling #687-A, existing chain only)") {
    withEnv { env =>
      IO {
        val doc = env.pathOf("docs/x.svg")
        Files.createDirectories(doc.getParent)
        Files.write(doc, "<svg>doc</svg>".getBytes(StandardCharsets.UTF_8))
        val real = doc.toRealPath()

        // ① mint through the real issuer (POST /api/nf-ticket → nfFileVerdict)
        val resp = env
          .issue(
            Json.obj(
              "sessionId" -> Json.fromString("spec"),
              "paths" -> Json.arr(Json.fromString(doc.toString))
            )
          )
          .get
        assertEquals(resp.status, Status.Ok)
        val body = parse(bodyOf(resp)).toOption.getOrElse(Json.Null)
        val rejected = body.hcursor.downField("rejected").values.getOrElse(Vector.empty).toList
        assertEquals(rejected, Nil, s"docs/** must not be rejected any more: $rejected")
        val token = body.hcursor
          .downField("tickets")
          .downField(doc.toString)
          .downField("t")
          .as[String]
          .toOption
          .getOrElse("")
        assert(token.nonEmpty, "docs/** must get a ticket")

        // ② read it back through the SAME chain (path-bound ticket + namespace judge)
        val read = env.read(s"path=${url(real)}&ticket=$token").get
        assertEquals(read.status, Status.Ok)
        assertEquals(bodyOf(read), "<svg>doc</svg>")
      }
    }
  }

  test(
    "A17-counter (2026-10-07 flip): former namespace refusals serve; ONLY credential identity/inode stay refused"
  ) {
    withEnv { env =>
      IO {
        val logs = env.pathOf("logs/x.svg")
        Files.createDirectories(logs.getParent)
        Files.write(logs, "<svg/>".getBytes(StandardCharsets.UTF_8))
        // A data-root entry that is NOT on the (retired) allowlist — served now.
        Files.write(env.pathOf("future-thing.json"), "{}".getBytes(StandardCharsets.UTF_8))
        val sshDir = Files.createDirectories(env.root.resolve(".ssh"))
        Files.write(sshDir.resolve("id_ed25519"), "key".getBytes(StandardCharsets.UTF_8))
        // Lexically normalized by the verdict chain → <root>/.ssh/id_ed25519: the
        // credential DIRECTORY-SEGMENT table (not any namespace rule) catches it.
        val traversal = env.pathOf("projects/../../.ssh/id_ed25519")

        // ① mint face: the credential cases stay unsigned + reported; the
        //    former namespace cases SIGN.
        val credCases: List[(Path, String)] = List(
          (env.pathOf("auth.json"), "credential-path"),
          (env.pathOf("secrets/x.txt"), "credential-path"),
          (traversal, "credential-path")
        )
        val serveCases: List[Path] = List(logs, env.pathOf("future-thing.json"))
        val mint = env
          .issue(
            Json.obj(
              "sessionId" -> Json.fromString("spec"),
              "paths" -> Json.arr(
                (credCases.map((p, _) => Json.fromString(p.toString)) ++ serveCases
                  .map(p => Json.fromString(p.toString)))*
              )
            )
          )
          .get
        assertEquals(mint.status, Status.Ok)
        val mintBody = parse(bodyOf(mint)).toOption.getOrElse(Json.Null)
        val tickets = mintBody.hcursor.downField("tickets").keys.getOrElse(Nil).toList
        val rejected = mintBody.hcursor.downField("rejected").values.getOrElse(Vector.empty).toList
        for (p, want) <- credCases do
          assert(!tickets.contains(p.toString), s"$p must not be signed")
          assert(
            rejected.exists(j =>
              j.hcursor.downField("path").as[String].toOption.contains(p.toString) &&
                j.hcursor.downField("reason").as[String].toOption.contains(want)
            ),
            s"$p must be rejected with $want — got $rejected"
          )
        for p <- serveCases do
          assert(tickets.contains(p.toString), s"$p must be signed after the flip (namespace refusal retired)")

        // ② read face: credentials refused even WITH a valid ticket (defence in
        //    depth — the store is called directly to bypass the issuer)
        for (p, want) <- credCases do
          val read = env.read(s"path=${url(p)}&ticket=${env.ticketFor(p)}").get
          assertEquals(read.status, Status.Forbidden, s"$p")
          assertEquals(reasonOf(read), want, s"$p")
      }
    }
  }

  // ── R1 policy shape ──────────────────────────────────────────────────
  test("policy: P1 is PathUtil.dataRoot (never a hardcoded home directory)") {
    // 2026-09-11 full-suite flake fix: the previous form was
    //   `memoized()` + `Paths.get(PathUtil.dataRoot.toString).toRealPath()`
    // and it is wrong twice over in a shared JVM — (a) the process-wide lazy
    // memo may hold a data root captured by an *earlier* suite, and (b)
    // `PathUtil.dataRoot` is a mutable global that other suites swap to a temp
    // dir and then delete, so `toRealPath()` throws NoSuchFileException
    // (`delete-session-cleanup-test…`). Assert the *derivation* instead, which
    // holds whether or not the directory currently exists: the policy root is
    // `canonicalOrSelf` of the live data root.
    val live = Paths.get(PathUtil.dataRoot.toString)
    val policy = NfFilePolicy.NfPathPolicy.standard()
    assertEquals(policy.dataRoot, NfFilePolicy.NfPathPolicy.canonicalOrSelf(live))
    // "never a hardcoded home directory": when the live root is not the default
    // `~/.nebflow`, the policy must NOT have fallen back to it.
    val hardcoded =
      Paths.get(sys.props.getOrElse("user.home", "/")).resolve(".nebflow").toAbsolutePath.normalize()
    if live.toAbsolutePath.normalize() != hardcoded then
      assertNotEquals(
        policy.dataRoot,
        hardcoded,
        "P1 must follow PathUtil.dataRoot (NEBFLOW_HOME_DIR), not a literal home path"
      )
  }

  test("policy: a newly added data-root directory SERVES after the 2026-10-07 flip (namespace default-deny retired)") {
    withEnv { env =>
      IO {
        Files.write(env.pathOf("future-thing.json"), "{}".getBytes(StandardCharsets.UTF_8))
        val real = env.pathOf("future-thing.json").toRealPath()
        val resp = env.read(s"path=${url(real)}&ticket=${env.ticketFor(real)}").get
        assertEquals(resp.status, Status.Ok, "the namespace reach layer is retired; only credentials refuse")
        assertEquals(bodyOf(resp), "{}")
      }
    }
  }

  test(
    "policy: P3 namespace is RETIRED (2026-10-07 flip) — repo files, project .nebflow and evidence alike serve; credentials still refuse"
  ) {
    // The 2026-09-11 regression this block used to catch (rooting P3 at the
    // repo root 403'd every repo file) is structurally impossible after the
    // flip: there is no namespace reach layer left to mis-root. The pure-judge
    // pins below now assert the FLIP: only the credential identity tables
    // refuse. (R2 inode remains the guard for data-root auth/secrets files —
    // see A5/A6/A17-counter.)
    val ws = Files.createTempDirectory("nebflow-nfp3-test").toRealPath()
    try
      val projNf = Files.createDirectories(ws.resolve(".nebflow"))
      val repoFile = Files.write(ws.resolve("README.md"), "hi".getBytes(StandardCharsets.UTF_8))
      val projNfFile = Files.write(projNf.resolve("memory.md"), "- a".getBytes(StandardCharsets.UTF_8))
      val evidenceDir = Files.createDirectories(projNf.resolve("evidence"))
      val evidence =
        Files.write(evidenceDir.resolve("plot.svg"), "<svg/>".getBytes(StandardCharsets.UTF_8))
      val policy = NfFilePolicy.NfPathPolicy(
        Files.createDirectories(ws.resolve("data-root")),
        projNf,
        Set.empty
      )
      assertEquals(NfFilePolicy.nfCredentialDeny(repoFile.toRealPath(), policy), None)
      assertEquals(
        NfFilePolicy.nfCredentialDeny(projNfFile.toRealPath(), policy),
        None,
        "the project .nebflow file serves after the flip (namespace refusal retired)"
      )
      assertEquals(NfFilePolicy.nfCredentialDeny(evidence.toRealPath(), policy), None)
    finally
      Files
        .walk(ws)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(p => Files.deleteIfExists(p))
    end try
  }

  test("policy: production P3 root is <cwd>/.nebflow (R1), not the workspace root") {
    val cwd = Paths.get(System.getProperty("user.dir"))
    val expected = NfFilePolicy.NfPathPolicy.canonicalOrSelf(cwd.resolve(".nebflow"))
    val actual = NfFilePolicy.NfPathPolicy.standard().workspaceRoot
    assertEquals(actual, expected)
    assertNotEquals(actual, NfFilePolicy.NfPathPolicy.canonicalOrSelf(cwd))
  }
end NfTicketRoutesSpec
