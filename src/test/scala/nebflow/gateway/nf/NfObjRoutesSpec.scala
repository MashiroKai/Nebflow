package nebflow.gateway.nf

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.{AuthScheme, Credentials, MediaType, Method, Request, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `GET /api/nf-obj/<cid>/<name>` 的服务端断言组（按对象复用批 2026-11-18·第 1 件）。
 *
 * 形态对齐 NfFileRoutesSpec / NfTicketRoutesSpec：companion 纯路由直调 + 注入内存
 * store + 注入 `NfPathPolicy`（P1/P3 指向不存在的根，本 spec 的对象都在临时目录
 * 外），零实例、零网络、零真实数据根写操作。
 *
 * 本 spec 钉死的是**新出口的合同**：
 *   ① 正答形态：cid 可解析 ⇒ 200，ETag = `"<cid>"`（强校验符），
 *      `Cache-Control` = `private, max-age=31536000, immutable`；
 *   ② 条件请求：`If-None-Match` 命中 ⇒ 304（策略头重申）；
 *   ③ 凭据面：URL 里**没有**凭据；未出示 ⇒ 401、出示但无效 ⇒ 403；
 *      票据参数**不**能凭据化本路由；
 *   ④ 内容身份：cid = 整件 sha256 前 32 hex，内容不变则恒同、内容变则必变；
 *   ⑤ 过渡期双读：`/api/nf-file?path&ticket` 逐字不变，且与 cid 腿取同一对象。
 */
class NfObjRoutesSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](gatewayToken)
  private val Sessions = GatewaySessionStore.unsafeCreate()
  private val store = NfTicketStore.unsafeCreate(1800)
  private val objects = NfObjStore.unsafeCreate()

  /** 与 NfFileRoutesSpec 同款：注入一个「什么都不在自己家里」的 policy。 */
  private val policy = NfFilePolicy.NfPathPolicy(
    java.nio.file.Paths.get("/nonexistent-nebflow-data-root"),
    java.nio.file.Paths.get("/nonexistent-nebflow-workspace"),
    Set.empty
  )

  private def withSeedFile[A](name: String, bytes: Array[Byte])(test: Path => IO[A]): A =
    val tmp = Files.createTempDirectory("nebflow-nfobj-test")
    val file = tmp.resolve(name)
    Files.write(file, bytes)
    try test(file).unsafeRunSync()
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(tmp)

  private def bearer(r: Request[IO]): Request[IO] =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, gatewayToken)))

  /** 铸一张票（正答），返回 (ticket, cidOption)。 */
  private def mint(path: String): (String, Option[String]) =
    val req = SpecRequests
      .withRemote(
        bearer(Request[IO](Method.POST, Uri.unsafeFromString("/api/nf-ticket"))),
        Some(SpecRequests.LoopbackV4)
      )
      .withEntity(Json.obj("sessionId" -> Json.fromString("spec"), "paths" -> Json.arr(Json.fromString(path))))
      .putHeaders(`Content-Type`(MediaType.application.json))
    val resp = NfFileRoutes
      .nfTicketRoutes(TokenRef, store, policy, Sessions, objects = objects)(req)
      .value
      .unsafeRunSync()
      .get
    assertEquals(resp.status, Status.Ok)
    val json = parse(resp.bodyText.compile.string.unsafeRunSync()).toOption.get
    val entry = json.hcursor.downField("tickets").downField(path)
    (entry.downField("t").as[String].getOrElse(""), entry.downField("cid").as[String].toOption)

  end mint

  private def mintCid(path: String): String =
    mint(path)._2.getOrElse(sys.error(s"mint returned no cid for $path"))

  private def objGet(cid: String, name: String, extra: Request[IO] => Request[IO] = identity) =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-obj/$cid/$name"))
    NfFileRoutes
      .nfObjRoutes(TokenRef, objects, Sessions)(extra(base))
      .value
      .unsafeRunSync()
      .get

  private def ticketGet(path: String, ticket: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-file?path=${enc(path)}&ticket=$ticket"))
    NfFileRoutes.nfFileRoutes(store, policy)(req).value.unsafeRunSync().get

  private def enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

  private def bodyBytes(r: org.http4s.Response[IO]): Array[Byte] =
    r.body.compile.toVector.unsafeRunSync().toArray

  private def header(r: org.http4s.Response[IO], name: String): String =
    r.headers.get(org.typelevel.ci.CIString(name)).map(_.head.value).getOrElse("")

  private val Immutable = "private, max-age=31536000, immutable"

  // ── ① 正答形态 ────────────────────────────────────────────────────────
  test("O1: 铸票注册 cid；按 cid 取字节 200，且 ETag/immutable 缓存头齐备") {
    val bytes = "PNG-BYTES-0001".getBytes(StandardCharsets.UTF_8)
    withSeedFile("shot.png", bytes) { f =>
      IO {
        val cid = mintCid(f.toString)
        assertEquals(cid.length, NfObjStore.CidHexChars)
        val expected = NfObjStore.hashFile(f).take(NfObjStore.CidHexChars)
        assertEquals(cid, expected, "cid = sha256 hex prefix (server-computed, never client-supplied)")

        val resp = objGet(cid, "shot.png", bearer)
        assertEquals(resp.status, Status.Ok)
        assertEquals(header(resp, "ETag"), "\"" + cid + "\"", "strong validator = the content id")
        assertEquals(header(resp, "Cache-Control"), Immutable, "cid 即内容身份 ⇒ immutable 一年是安全的")
        assert(header(resp, "Last-Modified").nonEmpty, "Last-Modified present")
        assert(java.util.Arrays.equals(bodyBytes(resp), bytes), "bytes identical")
      }
    }
  }

  // ── ② 条件请求 ────────────────────────────────────────────────────────
  test("O2: If-None-Match 命中强 ETag ⇒ 304，且策略头重申") {
    withSeedFile("shot.png", "abc".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val cid = mintCid(f.toString)
        val first = objGet(cid, "shot.png", bearer)
        val etag = header(first, "ETag")
        val second = objGet(
          cid,
          "shot.png",
          r =>
            bearer(r).putHeaders(
              org.http4s.Header.Raw(org.typelevel.ci.CIString("If-None-Match"), etag)
            )
        )
        assertEquals(second.status, Status.NotModified)
        assertEquals(bodyBytes(second).length, 0, "304 carries no body")
        assertEquals(header(second, "ETag"), etag)
        assertEquals(header(second, "Cache-Control"), Immutable)
      }
    }
  }

  // ── ③ 凭据面（拒绝路径实测状态码）────────────────────────────────────
  test("O3: 未出示任何凭据 ⇒ 401（reason=credential-required）；?ticket= 不构成本路由的凭据") {
    withSeedFile("shot.png", "abc".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val cid = mintCid(f.toString)
        val resp = objGet(cid, "shot.png") // 无 cookie、无 Authorization
        assertEquals(resp.status, Status.Unauthorized)
        assertEquals(header(resp, "X-Nf-Reason"), "credential-required")
        val (t, _) = mint(f.toString)
        val withTicket = objGet(
          cid,
          "shot.png",
          _ => Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-obj/$cid/shot.png?ticket=$t"))
        )
        assertEquals(withTicket.status, Status.Unauthorized, "?ticket= 不是本路由的凭据")
      }
    }
  }

  test("O4: 出示无效的会话 cookie ⇒ 403") {
    withSeedFile("shot.png", "abc".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val cid = mintCid(f.toString)
        val resp = objGet(cid, "shot.png", r => r.addCookie(GatewaySessionStore.CookieName, "deadbeef-not-a-session"))
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(header(resp, "X-Nf-Reason"), "credential-required")
      }
    }
  }

  test("O5: 出示错误 Bearer ⇒ 403；出示正确 Bearer ⇒ 200") {
    withSeedFile("shot.png", "abc".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val cid = mintCid(f.toString)
        val bad =
          objGet(cid, "shot.png", r => r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "wrong-token"))))
        assertEquals(bad.status, Status.Forbidden)
        assertEquals(objGet(cid, "shot.png", bearer).status, Status.Ok)
      }
    }
  }

  test("O6: 有效会话 cookie ⇒ 200（会话轨可用，与铸票面同判据）") {
    withSeedFile("shot.png", "abc".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val cid = mintCid(f.toString)
        val sid = Sessions.create("logto|u", GatewaySessionStore.OriginRelayMint, "net").unsafeRunSync().sessionId
        val resp = objGet(cid, "shot.png", r => r.addCookie(GatewaySessionStore.CookieName, sid))
        assertEquals(resp.status, Status.Ok)
        assert(java.util.Arrays.equals(bodyBytes(resp), "abc".getBytes(StandardCharsets.UTF_8)))
      }
    }
  }

  // ── ④ 内容身份 ────────────────────────────────────────────────────────
  test("O7: 内容不变 ⇒ cid 恒同（两次铸票同 cid）；内容变 ⇒ cid 必变") {
    withSeedFile("shot.png", "version-A".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val cid1 = mintCid(f.toString)
        val cid2 = mintCid(f.toString)
        assertEquals(cid1, cid2, "unchanged bytes ⇒ same content id (URL is a function of content)")

        Files.write(f, "version-B-longer".getBytes(StandardCharsets.UTF_8))
        val cid3 = mintCid(f.toString)
        assertNotEquals(cid1, cid3, "changed bytes ⇒ different content id (immutable cache can never go stale)")
      }
    }
  }

  test("O8: cid 形态不合法 ⇒ 404（统一，无存在性 oracle）") {
    IO {
      assertEquals(objGet("not-a-cid", "shot.png", bearer).status, Status.NotFound)
      assertEquals(objGet("ABCDEF0123456789ABCDEF0123456789", "x.png", bearer).status, Status.NotFound)
      assertEquals(objGet("0123456789abcdef", "x.png", bearer).status, Status.NotFound)
    }
  }

  test("O9: 未知（形态合法）cid ⇒ 404") {
    IO {
      val resp = objGet("0123456789abcdef0123456789abcdef", "x.png", bearer)
      assertEquals(resp.status, Status.NotFound)
      assertEquals(bodyBytes(resp).length, 0)
    }
  }

  test("O10: 注册后文件内容变了（size+mtime 不符）⇒ 404（绝不发陈旧字节）") {
    withSeedFile("shot.png", "aaa".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val cid = mintCid(f.toString)
        Files.write(f, "bbbbbbbbbb".getBytes(StandardCharsets.UTF_8))
        assertEquals(objGet(cid, "shot.png", bearer).status, Status.NotFound)
      }
    }
  }

  test("O11: 索引条目过期 ⇒ lookup 落空") {
    val short = NfObjStore.unsafeCreate(1)
    withSeedFile("shot.png", "abc".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val cid = short
          .register(f.toString, Files.size(f), Files.getLastModifiedTime(f).toMillis)
          .unsafeRunSync()
          .get
        Thread.sleep(15)
        assertEquals(short.lookup(cid).unsafeRunSync(), None)
      }
    }
  }

  test("O12: 超上限（> 64 MiB）或空件不内容寻址 ⇒ cid = None") {
    IO {
      assertEquals(objects.register("/nonexistent/big.bin", NfObjStore.MaxCidBytes + 1, 1L).unsafeRunSync(), None)
      assertEquals(objects.register("/nonexistent/empty.bin", 0L, 1L).unsafeRunSync(), None)
    }
  }

  // ── ⑤ 过渡期双读 ──────────────────────────────────────────────────────
  test("O13: 过渡期双读 —— /api/nf-file?path&ticket 逐字不变，且与 cid 腿取同一对象") {
    val bytes = "SAME-OBJECT-BYTES".getBytes(StandardCharsets.UTF_8)
    withSeedFile("shot.png", bytes) { f =>
      IO {
        val (ticket, cidOpt) = mint(f.toString)
        val cid = cidOpt.getOrElse(sys.error("no cid"))
        val legacy = ticketGet(f.toString, ticket)
        assertEquals(legacy.status, Status.Ok, "旧票据路由必须在场（双读期）")
        assertEquals(header(legacy, "Cache-Control"), "private, max-age=300")
        val byCid = objGet(cid, "shot.png", bearer)
        assertEquals(byCid.status, Status.Ok)
        assert(
          java.util.Arrays.equals(bodyBytes(legacy), bodyBytes(byCid)),
          "两条腿必须取到同一个对象（同一字节）"
        )
      }
    }
  }

  test("O14: 旧路由缺票 ⇒ 401（票据鉴权未被放宽）") {
    withSeedFile("shot.png", "abc".getBytes(StandardCharsets.UTF_8)) { f =>
      IO {
        val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-file?path=${enc(f.toString)}"))
        val resp = NfFileRoutes.nfFileRoutes(store, policy)(req).value.unsafeRunSync().get
        assertEquals(resp.status, Status.Unauthorized)
      }
    }
  }
end NfObjRoutesSpec
