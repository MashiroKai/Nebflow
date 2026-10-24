package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.{Method, Request, Uri}
import org.typelevel.ci.CIString

import scala.util.matching.Regex

/**
 * 静态资源缓存击穿批（2026-10-25 作者令④）——版本化改写与指纹契约。
 *
 * 症状：修好的前端到不了作者手机 —— 旧字节按稳定 URL 被长效缓存钉死，服务端
 * 无失效手段。修复 = index 引用带内容指纹（`?v=<sha-256 前 16 hex>`）+ 带版本
 * 请求回 immutable（内容变 ⇒ URL 变 ⇒ 缓存自然失效）。
 *
 * Contract pinned here:
 *  - [[AssetVersions.versionOf]]：存在的 classpath 资源 → 16 hex 指纹，确定
 *    性（同字节同值）；不存在的资源 → None
 *  - [[StaticRoutes.versionedIndexHtml]]：stylesheet link（css/、vendor/）、
 *    defer vendor script、module 入口（js/）三种引用被改写；favicon 族（自带
 *    静态 digest、前缀不匹配）与未知形状逐字保留；单资源指纹失败 ⇒ 原 tag 回落
 *  - 幂等：已带 `?v=` 的引用不匹配形状类，二次改写零变化
 *  - 指纹随内容变：不同字节的资源 ⇒ 不同指纹（cache-busting 的本体）
 *  - dev 树 index 应答（indexResponse 的 DevTree 腿）：体里全部 css/vendor/js
 *    引用携带 `?v=<指纹>`，且该版本化 URL 实际可取回并回 immutable
 */
class AssetVersioningSpec extends FunSuite:

  private val Fp: Regex = """\?v=([0-9a-f]{16})""".r

  test("versionOf: existing resource yields a stable 16-hex fingerprint") {
    val v1 = AssetVersions.versionOf("web/css/base.css")
    assert(v1.isDefined, "web/css/base.css must exist on the test classpath")
    assertEquals(v1.get.length, AssetVersions.HexLen)
    assert(v1.get.forall(c => c.isDigit || ('a' to 'f').contains(c)), s"hex only, got ${v1.get}")
    // Deterministic: same bytes → same fingerprint across calls.
    assertEquals(AssetVersions.versionOf("web/css/base.css"), v1)
  }

  test("versionOf: missing resource → None (caller falls back to the raw tag)") {
    assertEquals(AssetVersions.versionOf("web/css/__no_such__.css"), None)
  }

  test("fingerprint tracks content: two different files hash differently") {
    val a = AssetVersions.versionOf("web/css/base.css")
    val b = AssetVersions.versionOf("web/css/chat.css")
    assert(a.isDefined && b.isDefined)
    assert(a != b, "different content must yield different fingerprints (cache-busting premise)")
  }

  test("versionedIndexHtml rewrites stylesheet, vendor script and module entry refs") {
    val html =
      """<link rel="stylesheet" href="css/base.css">
        |<link rel="icon" href="favicon-32.png?v=71a456e2d388b27e">
        |<script defer src="vendor/lucide.min.js"></script>
        |<script type="module" src="js/main.js"></script>
        |<script type="module" src="js/mobile/bootstrap.js"></script>""".stripMargin
    val out = StaticRoutes.versionedIndexHtml(html)
    // css link versioned
    val cssV = Fp.findFirstMatchIn(out).map(_.group(1))
    assert(cssV.isDefined, "css link must carry ?v=")
    assert(out.contains(s"""href="css/base.css?v=${cssV.get}""""), out)
    // favicon's own static digest untouched (prefix favicon-* never matches)
    assert(out.contains("""href="favicon-32.png?v=71a456e2d388b27e""""), out)
    // vendor defer script + both module entries versioned
    assert(!out.contains("""src="vendor/lucide.min.js"""") && out.contains("vendor/lucide.min.js?v="), out)
    assert(out.contains("js/main.js?v="), out)
    assert(out.contains("js/mobile/bootstrap.js?v="), out)
    // every emitted fingerprint is a real fingerprint of the referenced file
    val mainV = AssetVersions.versionOf("web/js/main.js").get
    assert(out.contains(s"js/main.js?v=$mainV"), "fingerprint must equal versionOf of the file")
  }

  test("versionedIndexHtml falls back to the raw tag when the file is missing") {
    val html = """<link rel="stylesheet" href="css/__missing__.css">"""
    assertEquals(StaticRoutes.versionedIndexHtml(html), html)
  }

  test("versionedIndexHtml is idempotent (already-versioned refs are untouched)") {
    val html = """<script type="module" src="js/main.js"></script>"""
    val once = StaticRoutes.versionedIndexHtml(html)
    val twice = StaticRoutes.versionedIndexHtml(once)
    assertEquals(twice, once)
  }

  test("unknown tag shapes pass through byte-identical") {
    val html =
      """<link rel="stylesheet" href="/brand/stand/kit.css">
        |<img src="/brand/stand/illustrator/svg/stand-symbol-black.svg">
        |<link rel="stylesheet" href="vendor/katex.min.css" media="(prefers-color-scheme: light)">""".stripMargin
    val out = StaticRoutes.versionedIndexHtml(html)
    assert(out.contains("""href="/brand/stand/kit.css">"""), out)
    assert(out.contains("""stand-symbol-black.svg">"""), out)
    // the katex link HAS the known shape — rewritten with the fingerprint AND
    // the trailing media attribute preserved verbatim
    val katexV = AssetVersions.versionOf("web/vendor/katex.min.css").get
    assert(out.contains(s"""href="vendor/katex.min.css?v=$katexV" media="(prefers-color-scheme: light)">"""), out)
  }

  test("dev-tree index response: every css/vendor/js reference carries a fingerprint") {
    // devIndexWithBrand = the DevTree leg of indexResponse, called directly so
    // the spec is independent of staticSource's disk-dist freshness race.
    val resp = StaticRoutes.devIndexWithBrand.unsafeRunSync()
    assertEquals(resp.status, org.http4s.Status.Ok)
    val body = resp.body.through(fs2.text.utf8.decode).compile.string.unsafeRunSync()
    // 正则尾容许可选 ?v=<hex>：完全版本化（=本批目标形态）的 body 也必须能
    // 收集到引用路径；group(1) 恒为裸路径，后断言逐条要求版本化孪生存在。
    val hrefPaths = """href="((?:css|vendor)/[^"?]+)(?:\?v=[0-9a-f]+)?"""".r.findAllMatchIn(body).map(_.group(1)).toList
    val srcPaths  = """src="((?:vendor|js)/[^"?]+)(?:\?v=[0-9a-f]+)?"""".r.findAllMatchIn(body).map(_.group(1)).toList
    val all = hrefPaths ++ srcPaths
    assert(all.nonEmpty, "index must reference assets")
    for p <- all do
      val v = AssetVersions.versionOf(s"web/$p")
      assert(v.isDefined, s"$p must exist on the classpath")
      assert(body.contains(s"""$p?v=${v.get}"""), s"index must reference $p?v=${v.get}")
    assert(!body.contains("""href="css/base.css">"""), "no bare css reference may survive")
    assert(!body.contains("""src="js/main.js">"""), "no bare module entry may survive")
  }

  test("versioned asset URL is served with the immutable policy end-to-end") {
    val v = AssetVersions.versionOf("web/js/main.js").get
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/js/main.js?v=$v"))
    val resp = StaticRoutes.jsRoutes(req).value.unsafeRunSync().get
    assertEquals(resp.status, org.http4s.Status.Ok)
    val cc = resp.headers.get(CIString("Cache-Control")).map(_.head.value)
    assertEquals(cc, Some(StaticRoutes.ShippedAssetCacheControl))
    assertEquals(cc, Some("public, max-age=31536000, immutable"))
  }
end AssetVersioningSpec
