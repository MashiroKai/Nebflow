package nebflow.gateway.rest

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.typelevel.ci.CIString

import java.nio.file.Files

/**
 * 可查询批（作者令 2026-11-18 21:31）· **本批的闸（REST 腿）**。
 *
 * 与 `nebflow.cli.SecretsQueryGateSpec` 同一条闸的另外一面：同一份哨兵库，让
 * 每一个能出字的 REST 通道都跑一遍 —— `GET /secrets`（含 `?q=` / `?tag=` 查询
 * 面）、`/secrets/catalog`、`/secrets/{name}/pointer`、`/secrets/{name}/meta`，
 * 以及 `PATCH /secrets/{name}`（说明 + 标签的写入面）。任何一处出现哨兵 ⇒ 红。
 *
 * 只拆文件、不拆体系：判据、哨兵串形态与不变式推导与 CLI 腿逐条同源（两腿都在
 * 既有 sbt spec 套件里，都跑隔离根，都只断言「不出值」）。
 *
 * 面板腿（浏览器面）不做成第二套 harness，而是加在**既有**的前端测试体系里
 * （`tests/secrets-query-chips.spec.mjs`，隔离静态服务器 + mock API），并同样以
 * 哨兵断言：mock 的 `/api/secrets` 响应里塞一个 `value` 哨兵键，面板渲染后
 * 整个 DOM 不得含哨兵。
 */
class SecretsQueryRoutesGateSpec extends FunSuite:

  private val Sentinel = "SENTINEL-DO-NOT-LEAK-8f3a41"
  private val token = "secrets-query-routes-gate-token"

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  private def withRoutes(f: HttpRoutes[IO] => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("secrets-query-routes-gate").toRealPath().toString)
    val previous = PathUtil.dataRoot
    val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try
      PathUtil.setDataRoot(root)
      val dir = root / "secrets"
      os.makeDir.all(dir)
      val store = new nebflow.core.secrets.SecretsStore(dir)
      assert(store.create("aliyun-oss", s"$Sentinel-a").unsafeRunSync().isRight)
      assert(store.create("vercel-token", s"$Sentinel-b").unsafeRunSync().isRight)
      assert(store.create("plain-unlabelled", s"$Sentinel-c").unsafeRunSync().isRight)
      assert(
        store
          .setMetadata("aliyun-oss", None, Some("OSS access key for the docs bucket"), List("cloud", "Deploy", "oss"))
          .unsafeRunSync()
          .isRight
      )
      assert(store.setMetadata("vercel-token", None, Some("Vercel deploy token"), List("deploy")).unsafeRunSync().isRight)

      val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      val inner = new RestApiRoutes(
        tokenRef = cats.effect.Ref.unsafe[IO, String](token),
        configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](cfg),
        sharedResources = SharedResources(
          llm = null,
          dispatcher = dispatcher,
          sessionStore = null,
          projectRoot = os.pwd,
          thinkingConfigRef = null,
          rateLimiter = null,
          fileChangeTracker = null,
          contextWindow = 100_000,
          agentLibrary = null,
          taskStore = null,
          historyArchiver = null,
          fileLockManager = null,
          sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
          providerRegistry = null,
          healthMonitor = null,
          actorSystem = null,
          voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
          daemonService = None
        ),
        sessionStore = null,
        wsRoutes = null
      )
      f(inner.routes)
    finally
      PathUtil.setDataRoot(previous)
      releaseDispatcher.unsafeRunSync()
      try os.remove.all(root)
      catch case _: Throwable => ()
    end try
  end withRoutes

  private def call(
    routes: HttpRoutes[IO],
    method: Method,
    path: String,
    body: Option[Json] = None
  ): (Status, Option[Json], String) =
    val base = Request[IO](method, Uri.unsafeFromString(path))
      .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
    val req = body.fold(base)(j => base.withEntity(j.noSpaces))
    val resp = routes(req).value.unsafeRunSync().getOrElse(fail(s"route fell through: $method $path"))
    val text = resp.body.compile.toVector.unsafeRunSync().map(_.toChar).mkString
    (resp.status, if text.isEmpty then None else parse(text).toOption, text)

  private def assertSentinelFree(label: String, text: String): Unit =
    assert(!text.contains(Sentinel), s"$label leaked the sentinel value: $text")
    assert(!text.contains("SENTINEL-DO-NOT-LEAK"), s"$label leaked a value fragment: $text")

  test("R1 · every read arm is sentinel-free and the list carries description/tags/path") {
    withRoutes { routes =>
      val paths = List(
        "/secrets",
        "/secrets?tag=deploy",
        "/secrets?tag=deploy,cloud",
        "/secrets?q=OSS",
        "/secrets?q=docs%20bucket",
        "/secrets/catalog",
        "/secrets/aliyun-oss/pointer",
        "/secrets/aliyun-oss/meta"
      )
      paths.foreach { p =>
        val (st, _, text) = call(routes, Method.GET, p)
        assertEquals(st.code, 200, s"$p ⇒ $st")
        assertSentinelFree(s"REST $p", text)
      }
    }
  }

  test("R2 · the query params narrow the SAME list, and no entry ever carries a value key") {
    withRoutes { routes =>
      def names(path: String): List[String] =
        val (st, body, _) = call(routes, Method.GET, path)
        assertEquals(st.code, 200)
        body
          .flatMap(_.hcursor.downField("secrets").values.map(_.toList))
          .getOrElse(Nil)
          .flatMap(_.hcursor.downField("name").as[String].toOption)
          .sorted

      assertEquals(names("/secrets"), List("aliyun-oss", "plain-unlabelled", "vercel-token"))
      assertEquals(names("/secrets?tag=deploy"), List("aliyun-oss", "vercel-token"))
      assertEquals(names("/secrets?tag=deploy,cloud"), List("aliyun-oss"))
      assertEquals(names("/secrets?q=OSS"), List("aliyun-oss"))
      assertEquals(names("/secrets?tag=nope"), Nil)

      val (_, body, _) = call(routes, Method.GET, "/secrets")
      val rows = body.flatMap(_.hcursor.downField("secrets").values.map(_.toList)).getOrElse(Nil)
      assertEquals(rows.size, 3)
      rows.foreach { r =>
        assert(r.asObject.exists(o => o("value").isEmpty), s"entry must not carry a value key: $r")
        assert(r.asObject.exists(o => o("value_ref").isEmpty && o("content").isEmpty), s"entry: $r")
        // 可查询批: the facet and the path ride every row.
        assert(r.hcursor.downField("tags").focus.isDefined, s"entry must carry tags: $r")
        assert(r.hcursor.downField("path").as[String].toOption.exists(_.contains("/secrets/")), s"entry path: $r")
      }
      val aliyun = rows.find(_.hcursor.downField("name").as[String].toOption.contains("aliyun-oss")).getOrElse(fail("row missing"))
      assertEquals(aliyun.hcursor.downField("tags").as[List[String]].toOption, Some(List("cloud", "deploy", "oss")))
      assertEquals(aliyun.hcursor.downField("description").as[String].toOption, Some("OSS access key for the docs bucket"))
    }
  }

  test("R3 · PATCH replaces description+tags (never a value) and the list reflects it") {
    withRoutes { routes =>
      val (st, body, text) = call(
        routes,
        Method.PATCH,
        "/secrets/plain-unlabelled",
        Some(json("""{"description":"labelled by the panel","tags":["From-Panel","cloud"]}"""))
      )
      assertEquals(st.code, 200, s"PATCH ⇒ $st ($body)")
      assertSentinelFree("PATCH response", text)
      val (_, list, listText) = call(routes, Method.GET, "/secrets?tag=from-panel,cloud")
      assertSentinelFree("filtered list", listText)
      val names = list.flatMap(_.hcursor.downField("secrets").values.map(_.toList)).getOrElse(Nil)
        .flatMap(_.hcursor.downField("name").as[String].toOption)
      assertEquals(names, List("plain-unlabelled"), "tags are folded lowercase and filter as an intersection")

      // An empty whole-replace body clears both fields again — the entry drops
      // out of the tag filter, while the VALUE file is untouched.
      val (st2, _, text2) = call(routes, Method.PATCH, "/secrets/plain-unlabelled", Some(json("""{}""")))
      assertEquals(st2.code, 200)
      assertSentinelFree("PATCH (clear)", text2)
      val (_, after, _) = call(routes, Method.GET, "/secrets/plain-unlabelled/meta")
      assert(after.flatMap(_.hcursor.downField("description").as[String].toOption).contains(""), after.toString)
      assert(after.flatMap(_.hcursor.downField("tags").as[List[String]].toOption).contains(Nil), after.toString)
      assert(after.flatMap(_.hcursor.downField("stored").as[Boolean].toOption).contains(true), after.toString)
    }
  }

  test("R4 · POST accepts description+tags at creation (the panel's create-with-metadata path)") {
    withRoutes { routes =>
      val (st, _, text) = call(
        routes,
        Method.POST,
        "/secrets/fresh-key",
        Some(json("""{"value":"SENTINEL-DO-NOT-LEAK-8f3a41-created","description":"Created with metadata","tags":"New,Cloud"}"""))
      )
      assertEquals(st.code, 200, s"POST ⇒ $st")
      assertSentinelFree("POST response", text)
      val (_, meta, metaText) = call(routes, Method.GET, "/secrets/fresh-key/meta")
      assertSentinelFree("meta after create", metaText)
      assertEquals(meta.flatMap(_.hcursor.downField("description").as[String].toOption), Some("Created with metadata"))
      assertEquals(meta.flatMap(_.hcursor.downField("tags").as[List[String]].toOption), Some(List("new", "cloud")))
    }
  }

  test("R5 · the rendered catalogue on disk carries the tag column and no value") {
    withRoutes { _ =>
      val catalog = os.read(PathUtil.dataRoot / "secrets-catalog.md")
      assert(!catalog.contains(Sentinel), s"catalogue leaked a value: $catalog")
      assert(catalog.contains("#cloud"), catalog)
      val sidecar = os.read(nebflow.core.secrets.SecretsCatalog.metaPath(PathUtil.dataRoot))
      assert(!sidecar.contains(Sentinel), s"sidecar leaked a value: $sidecar")
      assertEquals(parse(sidecar).toOption.flatMap(_.hcursor.downField("version").as[Int].toOption), Some(2))
    }
  }

end SecretsQueryRoutesGateSpec
