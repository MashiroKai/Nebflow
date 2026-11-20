package nebflow.gateway.nf

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.{AuthScheme, Credentials, MediaType, Method, Request, Status, Uri}

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*

/**
 * `GET /api/nf-obj/<cid>/<name>?w=<px>` 的服务端断言组（媒体加载慢批 2026-11-19 ·
 * 作者裁定真活②：「D5 候选 A —— 服务端缩略腿：/api/nf-obj/<cid>/<name> 支持 ?w=<px>，
 * 按 cid+尺寸二次内容寻址缓存」）。
 *
 * 形态与新出口的既有 spec（[[NfObjRoutesSpec]]）逐字同款：companion 纯路由直调 +
 * 注入内存 store + 注入临时 thumb 缓存目录（绝不写真实数据根）+ 零实例零网络。
 *
 * 钉死的合同：
 *   ① 位图缩略：源宽 > w ⇒ 200，产物宽 == w（等比、不放大），Content-Type 位图类，
 *      ETag = `"<thumbCid>"`（= sha256("<cid>:<w>") 前 32 hex）+ immutable；
 *   ② 条件请求：`If-None-Match` 命中 ⇒ 304 零体 + 策略头重申；
 *   ③ 二次内容寻址：同 (cid, w) ⇒ 同 thumbCid（可复用），异 w ⇒ 异 thumbCid；
 *   ④ 诚实降级（绝不硬失败，也绝不放大）：w ≥ 源宽 / 非位图 / w 非法 ⇒ **原图**腿，
 *      ETag 回 `"<cid>"`（内容身份不变）；
 *   ⑤ 缓存落盘：第二次请求命中 `<cacheDir>/<thumbCid>.<ext>`（不再重算）。
 */
class NfThumbLegSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](gatewayToken)
  private val Sessions = GatewaySessionStore.unsafeCreate()
  private val store = NfTicketStore.unsafeCreate(1800)
  private val objects = NfObjStore.unsafeCreate()

  private val policy = NfFilePolicy.NfPathPolicy(
    java.nio.file.Paths.get("/nonexistent-nebflow-data-root"),
    java.nio.file.Paths.get("/nonexistent-nebflow-workspace"),
    Set.empty
  )

  private val Immutable = "private, max-age=31536000, immutable"

  /** 独立的临时 thumb 缓存目录（测试绝不写真实 <dataRoot>/cache）。返回 IO（清理挂
   *  `guarantee`，随被测 IO 跑完才删）——与 [[withSeedFile]] 的运行语义配成一对。 */
  private def withCacheDir[A](test: os.Path => IO[A]): IO[A] =
    val p = os.Path(Files.createTempDirectory("nf-thumb-cache"))
    test(p).guarantee(IO { try os.remove.all(p) catch case _: Throwable => () })

  private def withSeedFile[A](name: String, bytes: Array[Byte])(test: Path => IO[A]): A =
    val tmp = Files.createTempDirectory("nf-thumb-src")
    val file = tmp.resolve(name)
    Files.write(file, bytes)
    try test(file).unsafeRunSync()
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(tmp)

  /** 真位图源：`w`×`h` 的 RGB PNG（ImageIO 可读；决定产物走 JPEG 分支）。 */
  private def pngBytes(w: Int, h: Int): Array[Byte] =
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try g.fillRect(0, 0, w, h)
    finally g.dispose()
    val bos = new java.io.ByteArrayOutputStream()
    ImageIO.write(img, "png", bos)
    bos.toByteArray

  private def bearer(r: Request[IO]): Request[IO] =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, gatewayToken)))

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
    val json = io.circe.parser.parse(resp.bodyText.compile.string.unsafeRunSync()).toOption.get
    val entry = json.hcursor.downField("tickets").downField(path)
    (entry.downField("t").as[String].getOrElse(""), entry.downField("cid").as[String].toOption)

  private def mintCid(path: String): String = mint(path)._2.getOrElse(sys.error(s"mint returned no cid for $path"))

  /** 直调路由（带注入的 thumb 缓存目录）。 */
  private def objGet(cacheDir: os.Path, cid: String, name: String, query: String = "", extra: Request[IO] => Request[IO] = identity) =
    val uri = Uri.unsafeFromString(s"/api/nf-obj/$cid/$name" + query)
    NfFileRoutes
      .nfObjRoutes(TokenRef, objects, Sessions, cacheDir)(extra(Request[IO](Method.GET, uri)))
      .value
      .unsafeRunSync()
      .get

  private def bodyBytes(r: org.http4s.Response[IO]): Array[Byte] =
    r.body.compile.toVector.unsafeRunSync().toArray
  private def header(r: org.http4s.Response[IO], name: String): String =
    r.headers.get(org.typelevel.ci.CIString(name)).map(_.head.value).getOrElse("")
  private def decodedSize(bytes: Array[Byte]): (Int, Int) =
    val img = ImageIO.read(new ByteArrayInputStream(bytes))
    (img.getWidth, img.getHeight)

  // ── ① 位图缩略 ────────────────────────────────────────────────────────
  test("T1: 源宽 > w ⇒ 200 且产物宽 == w（等比、不放大），ETag=thumbCid、immutable") {
    val src = pngBytes(800, 600)
    withSeedFile("shot.png", src) { f =>
      withCacheDir { cache =>
        IO {
          val cid = mintCid(f.toString)
          val tc = NfThumb.thumbCid(cid, 200)
          val resp = objGet(cache, cid, "shot.png", "?w=200", bearer)
          assertEquals(resp.status, Status.Ok)
          assertEquals(header(resp, "ETag"), "\"" + tc + "\"", "缩略图的 ETag = thumbCid（内容身份）")
          assertEquals(header(resp, "Cache-Control"), Immutable)
          val body = bodyBytes(resp)
          val (bw, bh) = decodedSize(body)
          assertEquals(bw, 200, "产物宽 = 目标宽")
          assertEquals(bh, 150, "等比：600 * 200 / 800")
          assert(body.length < src.length, s"缩略必须更小（${body.length} < ${src.length}）")
          assert(
            Set("image/jpeg", "image/png").contains(header(resp, "Content-Type").split(";")(0).trim),
            "产物是位图（JPEG/PNG）"
          )
        }
      }
    }
  }

  // ── ② 条件请求 ────────────────────────────────────────────────────────
  test("T2: If-None-Match 命中缩略 ETag ⇒ 304 零体 + 策略头重申") {
    withSeedFile("shot.png", pngBytes(640, 480)) { f =>
      withCacheDir { cache =>
        IO {
          val cid = mintCid(f.toString)
          val first = objGet(cache, cid, "shot.png", "?w=160", bearer)
          val etag = header(first, "ETag")
          val second = objGet(
            cache,
            cid,
            "shot.png",
            "?w=160",
            r => bearer(r).putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("If-None-Match"), etag))
          )
          assertEquals(second.status, Status.NotModified)
          assertEquals(bodyBytes(second).length, 0)
          assertEquals(header(second, "ETag"), etag)
          assertEquals(header(second, "Cache-Control"), Immutable)
        }
      }
    }
  }

  // ── ③ 二次内容寻址 ────────────────────────────────────────────────────
  test("T3: (cid,w) 决定性 —— 同参同 thumbCid，异 w 异 thumbCid，内容变则 cid 变则 thumbCid 变") {
    withSeedFile("shot.png", pngBytes(500, 500)) { f =>
      IO {
        val cid1 = mintCid(f.toString)
        assertEquals(NfThumb.thumbCid(cid1, 128), NfThumb.thumbCid(cid1, 128))
        assertNotEquals(NfThumb.thumbCid(cid1, 128), NfThumb.thumbCid(cid1, 256), "异 w ⇒ 异内容身份")
        assertEquals(NfThumb.thumbCid(cid1, 128).length, NfThumb.ThumbCidHexChars)
        assert(NfThumb.thumbCid(cid1, 128).matches(NfThumb.ThumbCidPattern))
        Files.write(f, pngBytes(500, 501))
        val cid2 = mintCid(f.toString)
        assertNotEquals(NfThumb.thumbCid(cid1, 128), NfThumb.thumbCid(cid2, 128))
      }
    }
  }

  // ── ④ 诚实降级：绝不放大 / 非位图 / w 非法 ⇒ 原图 ─────────────────────
  test("T4: w ≥ 源宽 ⇒ 服务原图（不放大），ETag 回 \"<cid>\"") {
    withSeedFile("shot.png", pngBytes(120, 90)) { f =>
      withCacheDir { cache =>
        IO {
          val cid = mintCid(f.toString)
          val resp = objGet(cache, cid, "shot.png", "?w=800", bearer)
          assertEquals(resp.status, Status.Ok)
          assertEquals(header(resp, "ETag"), "\"" + cid + "\"", "不可缩 ⇒ 原图腿（内容身份 = cid）")
          assertEquals(decodedSize(bodyBytes(resp)), (120, 90))
        }
      }
    }
  }

  test("T5: 非位图（ImageIO 读不出）⇒ 原图字节原样服务") {
    val svg = "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8)
    withSeedFile("pic.svg", svg) { f =>
      withCacheDir { cache =>
        IO {
          val cid = mintCid(f.toString)
          val resp = objGet(cache, cid, "pic.svg", "?w=64", bearer)
          assertEquals(resp.status, Status.Ok)
          assertEquals(header(resp, "ETag"), "\"" + cid + "\"")
          assert(java.util.Arrays.equals(bodyBytes(resp), svg), "SVG 原样透传（缩略腿不碰）")
        }
      }
    }
  }

  test("T6: w 非法（0 / 负 / 非数 / 超上限）⇒ 原图腿") {
    withSeedFile("shot.png", pngBytes(300, 200)) { f =>
      withCacheDir { cache =>
        IO {
          val cid = mintCid(f.toString)
          for q <- List("?w=0", "?w=-5", "?w=abc", "?w=999999") do
            val resp = objGet(cache, cid, "shot.png", q, bearer)
            assertEquals(resp.status, Status.Ok, s"$q ⇒ 原图不是错误")
            assertEquals(header(resp, "ETag"), "\"" + cid + "\"", s"$q ⇒ 原图腿")
        }
      }
    }
  }

  // ── ⑤ 缓存落盘 ────────────────────────────────────────────────────────
  test("T7: 第二次请求命中 <cacheDir>/<thumbCid>.<ext>（已落盘，不再重算）") {
    withSeedFile("shot.png", pngBytes(1000, 400)) { f =>
      withCacheDir { cache =>
        IO {
          val cid = mintCid(f.toString)
          val tc = NfThumb.thumbCid(cid, 250)
          assert(!os.exists(cache / s"$tc.jpg") && !os.exists(cache / s"$tc.png"), "首取前缓存为空")
          val first = objGet(cache, cid, "shot.png", "?w=250", bearer)
          assertEquals(first.status, Status.Ok)
          val hit = os.list(cache).find(_.last.startsWith(tc + "."))
          assert(hit.isDefined, s"缩略落盘为 <thumbCid>.<ext>（实际：${os.list(cache).map(_.last).mkString(",")}）")
          val second = objGet(cache, cid, "shot.png", "?w=250", bearer)
          assertEquals(second.status, Status.Ok)
          assert(java.util.Arrays.equals(bodyBytes(first), bodyBytes(second)), "缓存命中字节一致")
        }
      }
    }
  }

  // ── 零回归：不带 w 的原答腿逐字不变 ───────────────────────────────────
  test("T8: 不带 ?w= ⇒ 原答腿逐字不变（ETag = cid，字节 = 源件）") {
    val src = pngBytes(80, 60)
    withSeedFile("shot.png", src) { f =>
      withCacheDir { cache =>
        IO {
          val cid = mintCid(f.toString)
          val resp = objGet(cache, cid, "shot.png", "", bearer)
          assertEquals(resp.status, Status.Ok)
          assertEquals(header(resp, "ETag"), "\"" + cid + "\"")
          assertEquals(header(resp, "Cache-Control"), Immutable)
          assert(java.util.Arrays.equals(bodyBytes(resp), src))
          assert(os.list(cache).isEmpty, s"不带 w 绝不写缩略缓存")
        }
      }
    }
  }
