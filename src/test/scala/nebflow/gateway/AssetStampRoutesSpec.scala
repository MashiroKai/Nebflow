package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.nio.file.Files

/**
 * 热更新地基 stamp 单点（台账 #919，2026-10-27）三钉：
 *
 *  1. DiskDist 指纹 = 文件字节指纹（SHA-256 前 16 hex），**字节变 ⇒ stamp 变**
 *     （mtime 缓存键；(path,mtime)→hash 与 AssetVersions 同形）；
 *  2. 三静态面 source 标签映射（dev-tree / bundled-dist / disk-dist）与缺文件回落
 *     （DevTree 面在测试 classpath 可解析 = Some；BundledDist 面开发检出无 jar dist = None，
 *     诚实回落——路由腿给 404，前端按 unavailable 静默空转）；
 *  3. 真实路由腿：GET /api/nf-asset-stamp（经 RestApiRoutes 级联）→ 200 载荷
 *     {stamp,source,version}，version 与 `nebflow.Version.string` 同单源（热重启链互认面）。
 *
 * 🔴 只读运行：数据根隔离 temp（PathUtil.setDataRoot），零端口零 HOME 写。
 */
class AssetStampRoutesSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-asset-stamp"))
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  private val Token = "test-token-asset-stamp"
  private val bearer = Headers("Authorization" -> s"Bearer $Token")

  private def run(req: Request[IO]): IO[(Status, Json)] =
    new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](Token),
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null
    ).routes(req).value.map(_.getOrElse(fail("route fell through"))).flatMap { resp =>
      resp.as[Json].map(json => (resp.status, json))
    }

  private val Hex16 = "^[0-9a-f]{16}$".r

  test("DiskDist stamp = byte fingerprint; rewritten bytes ⇒ new stamp (mtime-keyed cache re-hashes)") {
    val dist = tempRoot / "dist-a"
    os.makeDir.all(dist)
    val index = dist / "index.html"
    os.write.over(index, "<html>v1</html>")
    val s1 = AssetStampRoutes.diskIndexStamp(index)
    assert(s1.isDefined, "existing index must fingerprint")
    assertEquals(Hex16.findFirstMatchIn(s1.get).isDefined, true, s"stamp must be 16 hex, got: $s1")
    Thread.sleep(10) // mtime 粒度让位（APFS ns 足够，这里只兜测试时钟）
    os.write.over(index, "<html>v2-much-longer-body</html>")
    val s2 = AssetStampRoutes.diskIndexStamp(index)
    assert(s2.isDefined)
    assert(s1.get != s2.get, s"byte change must move the stamp (v1=$s1 v2=$s2)")
    // 同字节重读 = 缓存命中恒同（幂等）
    assertEquals(AssetStampRoutes.diskIndexStamp(index), s2)
    // 缺文件 = None（诚实回落；路由腿 404）
    assertEquals(AssetStampRoutes.diskIndexStamp(dist / "missing.html"), None)
  }

  test("source labels map per static face; missing classpath dist = None (honest fallback)") {
    // DevTree 面：测试 classpath 含主资源树 ⇒ web/index.html 可解析
    AssetStampRoutes.assetStamp(StaticRoutes.StaticSource.DevTree) match
      case None => fail("dev-tree index.html must resolve on the test classpath")
      case Some((label, stamp)) =>
        assertEquals(label, "dev-tree")
        assertEquals(Hex16.findFirstMatchIn(stamp).isDefined, true, s"16 hex expected, got: $stamp")
    // BundledDist 面：开发检出的 classpath 无 web-dist jar 资源 ⇒ None（不冒认）
    assertEquals(
      AssetStampRoutes.assetStamp(StaticRoutes.StaticSource.BundledDist),
      None,
      "dev checkout has no bundled dist on the classpath — honest None"
    )
    // DiskDist 面：标签随字节指纹（缺文件 None）
    val dist = tempRoot / "dist-b"
    os.makeDir.all(dist)
    os.write.over(dist / "index.html", "<html>d</html>")
    AssetStampRoutes.assetStamp(StaticRoutes.StaticSource.DiskDist(dist)) match
      case None => fail("existing disk index must fingerprint")
      case Some((label, stamp)) =>
        assertEquals(label, "disk-dist")
        assertEquals(
          stamp,
          AssetStampRoutes.diskIndexStamp(dist / "index.html").get,
          "route fingerprint == direct file fingerprint (single source)"
        )
    assertEquals(
      AssetStampRoutes.assetStamp(StaticRoutes.StaticSource.DiskDist(tempRoot / "nope")),
      None,
      "missing disk dist = None"
    )
  }

  test("route leg: GET /api/nf-asset-stamp serves {stamp,source,version} with the engine version single-source") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/nf-asset-stamp")).withHeaders(bearer)).map {
      case (status, body) =>
        assertEquals(status, Status.Ok)
        val stamp = body.hcursor.downField("stamp").as[String].toOption
        assert(stamp.exists(s => Hex16.findFirstMatchIn(s).isDefined), s"stamp must be 16 hex, got: $body")
        val source = body.hcursor.downField("source").as[String].toOption.getOrElse("")
        assert(
          Set("dev-tree", "bundled-dist", "disk-dist")(source),
          s"source must name the static face, got: $source"
        )
        // 与热重启链 HealthPayload 同单源（前端可诊断「stamp 没变但 version 变了」）
        assertEquals(
          body.hcursor.downField("version").as[String].toOption,
          Some(nebflow.Version.string)
        )
    }
  }

end AssetStampRoutesSpec
