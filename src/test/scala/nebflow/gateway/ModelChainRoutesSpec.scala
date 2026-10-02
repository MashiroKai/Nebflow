package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{NebflowServiceConfig, ServiceLlmConfig} // W1 shim: main had them in nebflow.llm; the PR moved config to shared
import nebflow.shared.ThinkingConfig // W1 shim: main had nebflow.llm.ThinkingConfig; PR moved it to shared
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.{Headers, HttpRoutes, Method, Request, Response, Status, Uri}
import org.http4s.server.websocket.WebSocketBuilder2
import java.nio.file.Files

/**
 * Chain-face REST contract spec: GET/PUT /api/agents/:name/model.
 *
 * Pins the request/response shapes the /model panel consumes:
 *   GET  -> {name, mode, chain, effectiveChain, resolvedFrom, current, settable}
 *   PUT  <- {"model": {preferred, fallbacks} | null} (bare chain body also accepted)
 *   PUT  -> {updated, mode, chain, effectiveChain, resolvedFrom}
 *
 * Write gate = the four roles (Nebula / project-dispatcher / kernel /
 * general); any other agent is refused with 400 and its stored data is left
 * untouched. A null or empty chain returns the agent to the follow state.
 */
class ModelChainRoutesSpec extends CatsEffectSuite:

  private val TestToken = "modelchain-routes-token"

  private def withFixture[A](test: () => IO[A]): A =
    val tmp = os.temp.dir(dir = os.Path(Files.createTempDirectory("nb-modelchain-spec").toString))
    val originalRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    try
      os.makeDir.all(tmp / "agents" / "Nebula")
      os.write(tmp / "agents" / "Nebula" / "agent.json",
        """{"name":"Nebula","description":"fixture nebula","model":{"preferred":"zhipu/GLM-5.3-Flash","fallbacks":["kimi/kimi-k3"]}}""")
      os.write(tmp / "agents" / "Nebula" / "system.md", "n")
      os.makeDir.all(tmp / "agents" / "kernel")
      os.write(tmp / "agents" / "kernel" / "agent.json",
        """{"name":"kernel","description":"fixture kernel"}""")
      os.write(tmp / "agents" / "kernel" / "system.md", "k")
      os.makeDir.all(tmp / "agents" / "general")
      os.write(tmp / "agents" / "general" / "agent.json",
        """{"name":"general","description":"fixture general"}""")
      os.write(tmp / "agents" / "general" / "system.md", "g")
      os.makeDir.all(tmp / "agents" / "Coder")
      os.write(tmp / "agents" / "Coder" / "agent.json",
        """{"name":"Coder","description":"non-role fixture","model":{"preferred":"zhipu/GLM-5.3-Flash","fallbacks":[]}}""")
      os.write(tmp / "agents" / "Coder" / "system.md", "c")
      test().unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)

  private val NullBackend = null.asInstanceOf[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]]

  /** Real SharedResources so the GET arm's candidate/health read faces run:
    * empty provider config => every chain ref resolves to nothing => current
    * = null, while the chain-face fields carry the assertions. */
  private def mkResources: nebflow.agent.SharedResources =
    val configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    )
    nebflow.agent.SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "modelchain-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = new nebflow.llm.ProviderRegistry(configRef, NullBackend),
      healthMonitor = nebflow.llm.ProviderHealthMonitor(null),
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private def mkRoutes: HttpRoutes[IO] =
    val inner = new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources,
      sessionStore = null,
      wsRoutes = null
    )
    val wsb = null.asInstanceOf[WebSocketBuilder2[IO]]
    inner.routes <+> inner.presenceWsRoutes(wsb)

  private def getJson(path: String): IO[Response[IO]] =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
    mkRoutes(req).value.map(_.getOrElse(fail(s"GET $path fell through")))

  private def putJson(path: String, body: io.circe.Json): IO[Response[IO]] =
    val req = Request[IO](Method.PUT, Uri.unsafeFromString(path))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
      .withEntity(body)
    mkRoutes(req).value.map(_.getOrElse(fail(s"PUT $path fell through")))

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  test("GET /agents/Nebula/model → explicit shape with the own chain and its resolution source"):
    withFixture { () =>
      getJson("/agents/Nebula/model").flatMap { resp =>
        resp.as[io.circe.Json].map { json =>
          assertEquals(resp.status, Status.Ok)
          val hc = json.hcursor
          assertEquals(hc.downField("name").as[String], Right("Nebula"))
          assertEquals(hc.downField("mode").as[String], Right("explicit"))
          assertEquals(hc.downField("chain").downField("preferred").as[String], Right("zhipu/GLM-5.3-Flash"))
          assertEquals(hc.downField("chain").downField("fallbacks").as[List[String]], Right(List("kimi/kimi-k3")))
          assertEquals(hc.downField("effectiveChain").downField("preferred").as[String], Right("zhipu/GLM-5.3-Flash"))
          assertEquals(hc.downField("resolvedFrom").as[String], Right("own-chain"))
          assertEquals(hc.downField("settable").as[Boolean], Right(true))
        }
      }
    }

  test("GET follower (kernel) → mode=follow, chain null, effectiveChain = Nebula's chain"):
    withFixture { () =>
      getJson("/agents/kernel/model").flatMap { resp =>
        resp.as[io.circe.Json].map { json =>
          val hc = json.hcursor
          assertEquals(hc.downField("mode").as[String], Right("follow"))
          assert(hc.downField("chain").focus.exists(_.isNull), s"chain must be null while following: $json")
          assertEquals(hc.downField("effectiveChain").downField("preferred").as[String], Right("zhipu/GLM-5.3-Flash"))
          assertEquals(hc.downField("resolvedFrom").as[String], Right("nebula-chain"))
          assertEquals(hc.downField("settable").as[Boolean], Right(true))
        }
      }
    }

  test("PUT /agents/kernel/model → fork; a second GET reports the fork; null returns to follow"):
    withFixture { () =>
      val fork = io.circe.Json.obj(
        "model" -> io.circe.Json.obj(
          "preferred" -> "kimi/kimi-k3".asJson,
          "fallbacks" -> List("deepseek/deepseek-flash").asJson
        )
      )
      for
        resp <- putJson("/agents/kernel/model", fork)
        putJson0 <- resp.as[io.circe.Json]
        _ = assertEquals(resp.status, Status.Ok)
        _ = assertEquals(putJson0.hcursor.downField("mode").as[String], Right("explicit"))
        _ = assertEquals(putJson0.hcursor.downField("resolvedFrom").as[String], Right("own-chain"))
        _ = assertEquals(putJson0.hcursor.downField("chain").downField("preferred").as[String], Right("kimi/kimi-k3"))
        after <- getJson("/agents/kernel/model")
        afterJson <- after.as[io.circe.Json]
        _ = assertEquals(afterJson.hcursor.downField("mode").as[String], Right("explicit"))
        _ = assert(os.read(PathUtil.dataRoot / "agents" / "kernel" / "agent.json").contains("kimi/kimi-k3"))
        // return to follow: null model removes the key
        back <- putJson("/agents/kernel/model", io.circe.Json.obj("model" -> io.circe.Json.Null))
        backJson <- back.as[io.circe.Json]
        _ = assertEquals(backJson.hcursor.downField("mode").as[String], Right("follow"))
        _ = assertEquals(backJson.hcursor.downField("resolvedFrom").as[String], Right("nebula-chain"))
        _ = assert(!os.read(PathUtil.dataRoot / "agents" / "kernel" / "agent.json").contains("kimi/kimi-k3"),
          "follow transition must remove the stored chain")
      yield ()
    }

  test("PUT accepts a bare chain body (no model wrapper) — same write"):
    withFixture { () =>
      val bare = io.circe.Json.obj(
        "preferred" -> "deepseek/deepseek-flash".asJson,
        "fallbacks" -> io.circe.Json.arr()
      )
      for
        resp <- putJson("/agents/general/model", bare)
        json <- resp.as[io.circe.Json]
      yield
        assertEquals(resp.status, Status.Ok)
        assertEquals(json.hcursor.downField("chain").downField("preferred").as[String], Right("deepseek/deepseek-flash"))
    }

  test("PUT empty chain ({preferred: null, fallbacks: []}) normalizes to follow"):
    withFixture { () =>
      for
        _ <- putJson("/agents/general/model", io.circe.Json.obj("preferred" -> io.circe.Json.Null.asJson, "fallbacks" -> io.circe.Json.arr()))
        _ <- IO {
          val raw = os.read(PathUtil.dataRoot / "agents" / "general" / "agent.json")
          assert(!raw.contains("\"model\""), s"an empty chain must not be written: $raw")
        }
        resp <- getJson("/agents/general/model")
        json <- resp.as[io.circe.Json]
      yield assertEquals(json.hcursor.downField("mode").as[String], Right("follow"))
    }
  test("write gate: a non-role agent is refused with 400 and its data stays untouched"):
    withFixture { () =>
      val before = os.read(PathUtil.dataRoot / "agents" / "Coder" / "agent.json")
      putJson("/agents/Coder/model", io.circe.Json.obj("model" -> io.circe.Json.obj("preferred" -> "x/y".asJson, "fallbacks" -> io.circe.Json.arr()))).flatMap { resp =>
        resp.as[io.circe.Json].map { json =>
          assertEquals(resp.status, Status.BadRequest)
          val err = json.hcursor.downField("error").as[String].getOrElse("")
          assert(err.contains("does not accept a model chain"), s"error must be actionable: $err")
          assertEquals(os.read(PathUtil.dataRoot / "agents" / "Coder" / "agent.json"), before,
            "refused write must leave the stored data untouched")
        }
      }
    }

  test("GET non-role agent → follow with the Nebula chain (stored refs are not resolution input)"):
    withFixture { () =>
      getJson("/agents/Coder/model").flatMap { resp =>
        resp.as[io.circe.Json].map { json =>
          val hc = json.hcursor
          assertEquals(hc.downField("settable").as[Boolean], Right(false))
          assertEquals(hc.downField("effectiveChain").downField("preferred").as[String], Right("zhipu/GLM-5.3-Flash"))
          assertEquals(hc.downField("resolvedFrom").as[String], Right("nebula-chain"))
        }
      }
    }

  test("PUT unconfigured Nebula chain → follow = seed-chain degradation, never a 500"):
    withFixture { () =>
      for
        resp <- putJson("/agents/Nebula/model", io.circe.Json.obj("model" -> io.circe.Json.Null))
        json <- resp.as[io.circe.Json]
      yield
        assertEquals(resp.status, Status.Ok)
        assertEquals(json.hcursor.downField("mode").as[String], Right("follow"))
        // Nebula follows nobody: the follow state degrades to the seed chain
        assertEquals(json.hcursor.downField("resolvedFrom").as[String], Right("seed"))
    }

  test("PUT a settable name with no agent dir → 404; a non-settable name → 400 gate first"):
    withFixture { () =>
      for
        missing <- putJson("/agents/project-dispatcher/model", io.circe.Json.obj("model" -> io.circe.Json.Null))
        _ = assertEquals(missing.status, Status.NotFound)
        stranger <- putJson("/agents/Nobody/model", io.circe.Json.obj("model" -> io.circe.Json.Null))
      yield assertEquals(stranger.status, Status.BadRequest,
        "the write gate refuses non-role names before any existence check")
    }
