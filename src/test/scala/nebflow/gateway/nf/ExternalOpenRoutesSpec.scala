package nebflow.gateway.nf

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.gateway.*
import nebflow.shared.PathUtil
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Method, Request, Status, Uri}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * `POST /api/nf-external/open` 的合同断言（external-open 批，2026-11-19）。
 *
 * 形态对齐 NfObjRoutesSpec：companion 纯路由直调 + 注入 `NfPathPolicy` + 注入
 * `fetchImpl`（URL 腿的对端换成内存桩，零外网），落盘根用 `PathUtil.setDataRoot`
 * 指向临时目录（零真实数据根写操作）。
 *
 * 本 spec 钉死的四条 = 报告里的四条闸：
 *   ① 凭据路径被拒（403 credential-path，且响应体不含该路径的凭据内容）；
 *   ② 私网 URL 被拒（403 url-private-address —— 桩把判据面原样透出）；
 *   ③ 超限被拒（413 too-large）；
 *   ④ 正常外部文件能开、正常公网 URL 能拉（200 + absPath + 内容地址目录）。
 */
class ExternalOpenRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](gatewayToken)
  private val Sessions = GatewaySessionStore.unsafeCreate()

  private def bearer(r: Request[IO]): Request[IO] =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, gatewayToken)))

  private def post(
    routes: org.http4s.HttpRoutes[IO],
    body: Json,
    auth: Boolean = true
  ): IO[org.http4s.Response[IO]] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString("/api/nf-external/open")).withEntity(body)
    routes.run(if auth then bearer(base) else base).value.map(_.getOrElse(fail("route did not match")))

  private def reasonOf(resp: org.http4s.Response[IO]): String =
    resp.headers.get(org.typelevel.ci.CIString("X-Nf-Reason")).map(_.head.value).getOrElse("")

  private def bodyText(resp: org.http4s.Response[IO]): String =
    new String(resp.body.compile.to(Array).unsafeRunSync(), StandardCharsets.UTF_8)

  private def bodyJson(resp: org.http4s.Response[IO]): Json =
    parse(bodyText(resp)).getOrElse(fail("response body is not JSON"))

  private def routesWith(
    policy: NfFilePolicy.NfPathPolicy,
    fetchImpl: (String, Path) => IO[Either[ExternalOpenPolicy.Refusal, ExternalFetch.Outcome]] =
      (_, _) => IO.pure(Left(ExternalOpenPolicy.Refusal(500, "unused", "stub")))
  ): org.http4s.HttpRoutes[IO] =
    ExternalOpenRoutes.externalOpenRoutes(TokenRef, Sessions, policy, fetchImpl)

  private def tempPolicy(): (Path, NfFilePolicy.NfPathPolicy) =
    // 判据比的是 realpath（macOS 的 /var → /private/var），所以策略根也必须
    // 规范化——生产里 `NfPathPolicy.standard()` 就是这么做的（canonicalOrSelf）。
    val root = Files.createTempDirectory("nf-ext-routes").toRealPath()
    (root, NfFilePolicy.NfPathPolicy(root, root.resolve("ws").resolve(".nebflow"), Set.empty))

  // ── 鉴权 ────────────────────────────────────────────────────────────────

  test("unauthenticated request is refused with credential-required"):
    val (_, policy) = tempPolicy()
    post(routesWith(policy), Json.obj("path" -> Json.fromString("/tmp/x")), auth = false).map { resp =>
      assertEquals(resp.status, Status.Forbidden)
      assertEquals(reasonOf(resp), "credential-required")
    }

  test("exactly one of path / url is required"):
    val (_, policy) = tempPolicy()
    for
      both <- post(routesWith(policy), Json.obj("path" -> Json.fromString("/tmp/a"), "url" -> Json.fromString("https://x.example/a")))
      neither <- post(routesWith(policy), Json.obj())
    yield
      assertEquals(both.status, Status.BadRequest)
      assertEquals(neither.status, Status.BadRequest)

  // ── ① 凭据路径被拒（负控必红）──────────────────────────────────────────

  test("a credential path is refused (negative control ①)"):
    val (root, policy) = tempPolicy()
    val secretsFile = root.resolve("secrets").resolve("session-token.txt")
    Files.createDirectories(secretsFile.getParent)
    Files.writeString(secretsFile, "SUPER-SECRET-VALUE")
    val authJson = root.resolve("auth.json")
    Files.writeString(authJson, "{\"token\":\"SUPER-SECRET-VALUE\"}")
    for
      r1 <- post(routesWith(policy), Json.obj("path" -> Json.fromString(secretsFile.toString)))
      r2 <- post(routesWith(policy), Json.obj("path" -> Json.fromString(authJson.toString)))
    yield
      assertEquals(r1.status, Status.Forbidden)
      assertEquals(reasonOf(r1), "credential-path")
      assertEquals(r2.status, Status.Forbidden)
      assertEquals(reasonOf(r2), "credential-path")
      // 响应体只有拒因，没有凭据内容（红线：绝不把凭据送进 canvas / 日志 / 报告）
      val body = bodyText(r1)
      assert(!body.contains("SUPER-SECRET-VALUE"), body)

  test("the deny log line never carries a credential path"):
    val line = ExternalOpenRoutes.denyLogLine(403, "credential-path", "/Users/x/.ssh/id_rsa", "127.0.0.1")
    assert(line.contains("target=<redacted>"), line)
    assert(!line.contains(".ssh"), line)

  // ── ④ 正常外部文件能开（正控）──────────────────────────────────────────

  test("an ordinary file OUTSIDE any project root opens (positive control)"):
    val (_, policy) = tempPolicy()
    val outside = Files.createTempDirectory("nf-ext-outside")
    val file = outside.resolve("notes.md")
    Files.writeString(file, "# hello")
    post(routesWith(policy), Json.obj("path" -> Json.fromString(file.toString))).map { resp =>
      assertEquals(resp.status, Status.Ok)
      val j = bodyJson(resp)
      assertEquals(j.hcursor.get[String]("absPath").toOption, Some(file.toRealPath().toString))
      assertEquals(j.hcursor.get[String]("itemType").toOption, Some("markdown"))
      assertEquals(j.hcursor.get[Boolean]("binary").toOption, Some(false))
      assertEquals(j.hcursor.get[String]("kind").toOption, Some("local"))
      Files.deleteIfExists(file)
      Files.deleteIfExists(outside)
    }

  test("a missing path is 404 and a relative path is 400"):
    val (_, policy) = tempPolicy()
    for
      missing <- post(routesWith(policy), Json.obj("path" -> Json.fromString("/nonexistent/nope.txt")))
      relative <- post(routesWith(policy), Json.obj("path" -> Json.fromString("relative/path.txt")))
    yield
      assertEquals(missing.status, Status.NotFound)
      assertEquals(reasonOf(missing), "not-found")
      assertEquals(relative.status, Status.BadRequest)
      assertEquals(reasonOf(relative), "not-absolute")

  // ── ③ 超限被拒（负控必红）──────────────────────────────────────────────

  test("a file over the text leg size gate is 413 (negative control ③)"):
    val (_, policy) = tempPolicy()
    val dir = Files.createTempDirectory("nf-ext-big")
    val big = dir.resolve("huge.txt")
    // 稀疏文件：长度越过 100MB 文本闸，不真写 100MB 字节
    val ch = Files.newByteChannel(big, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    ch.position(TextStreamSearch.MaxPopReadFileBytes + 1)
    ch.write(ByteBuffer.wrap(Array[Byte](1)))
    ch.close()
    post(routesWith(policy), Json.obj("path" -> Json.fromString(big.toString))).map { resp =>
      assertEquals(resp.status, Status.PayloadTooLarge)
      assertEquals(reasonOf(resp), "too-large")
      Files.deleteIfExists(big)
      Files.deleteIfExists(dir)
    }

  // ── ② 私网 URL 被拒（负控必红）──────────────────────────────────────────

  test("a private-address URL refusal is surfaced verbatim (negative control ②)"):
    val (_, policy) = tempPolicy()
    val stub: (String, Path) => IO[Either[ExternalOpenPolicy.Refusal, ExternalFetch.Outcome]] =
      (_, _) => IO.pure(Left(ExternalOpenPolicy.Refusal(403, "url-private-address", "'x' resolves to 127.0.0.1")))
    post(routesWith(policy, stub), Json.obj("url" -> Json.fromString("http://localhost/x"))).map { resp =>
      assertEquals(resp.status, Status.Forbidden)
      assertEquals(reasonOf(resp), "url-private-address")
    }

  // ── ④ URL 腿：落盘到内容地址目录 + TTL 清账（正控）─────────────────────

  test("a fetched URL lands under external-fetch/<sha16>/ and sweeps expired dirs"):
    // 🔴 换根必须发生在 **IO 内部**：`try/finally` 包 IO 会在 IO 还没跑时就把根
    // 还原（第一次写这个 spec 就踩了 —— 请求其实打在真实数据根上）。
    val previous = PathUtil.dataRoot
    val root = Files.createTempDirectory("nf-ext-urlroot").toRealPath()
    val body = Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ++
      "rest".getBytes(StandardCharsets.UTF_8)
    val sha = ExternalOpenPolicy.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))
    val stub: (String, Path) => IO[Either[ExternalOpenPolicy.Refusal, ExternalFetch.Outcome]] =
      (_, tmp) =>
        IO.blocking {
          Files.createDirectories(tmp.getParent)
          Files.write(tmp, body)
          Right(ExternalFetch.Outcome(new java.net.URI("https://example.com/a/pic.png"), sha, body.length.toLong, body, "image/png"))
        }
    val policy = NfFilePolicy.NfPathPolicy(root, root.resolve("ws").resolve(".nebflow"), Set.empty)
    val prog =
      for
        _ <- IO.blocking(PathUtil.setDataRoot(os.Path(root)))
        fetchRoot = PathUtil.dataRoot.toNIO.resolve(ExternalOpenPolicy.StorageDirName)
        stale <- IO.blocking {
          val dir = fetchRoot.resolve("0000000000000000")
          Files.createDirectories(dir)
          Files.writeString(dir.resolve("old.png"), "old")
          Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 72L * 3600 * 1000))
          dir
        }
        resp <- post(routesWith(policy, stub), Json.obj("url" -> Json.fromString("https://example.com/a/pic.png")))
        _ <- IO {
          assertEquals(resp.status, Status.Ok)
          val j = bodyJson(resp)
          val absPath = j.hcursor.get[String]("absPath").toOption.getOrElse(fail("no absPath"))
          assert(absPath.contains("/external-fetch/" + sha.take(16) + "/"), absPath)
          assert(!absPath.contains("/external-fetch/external-fetch/"), s"double nesting: $absPath")
          assert(absPath.startsWith(root.toRealPath().toString), s"$absPath must live under the redirected root $root")
          assert(Files.exists(java.nio.file.Paths.get(absPath)), "the stored file must exist")
          assertEquals(j.hcursor.get[String]("fileName").toOption, Some("pic.png"))
          assertEquals(j.hcursor.get[String]("sha256").toOption, Some(sha))
          assertEquals(j.hcursor.get[String]("kind").toOption, Some("url"))
          assert(!Files.exists(stale), "the expired fetch dir must be swept")
        }
      yield ()
    prog.guarantee(IO.blocking(PathUtil.setDataRoot(previous)))

end ExternalOpenRoutesSpec
