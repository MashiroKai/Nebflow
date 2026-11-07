package nebflow.gateway.rest

import nebflow.gateway.*

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
import nebflow.shared.ProviderConfig
import nebflow.shared.{
  NebflowServiceConfig,
  ServiceLlmConfig
} // W1 shim: main had them in nebflow.llm; the PR moved config to shared
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
      os.write(
        tmp / "agents" / "Nebula" / "agent.json",
        """{"name":"Nebula","description":"fixture nebula","model":{"preferred":"zhipu/GLM-5.3-Flash","fallbacks":["kimi/kimi-k3"]}}"""
      )
      os.write(tmp / "agents" / "Nebula" / "system.md", "n")
      os.makeDir.all(tmp / "agents" / "project-dispatcher")
      os.write(tmp / "agents" / "project-dispatcher" / "agent.json", """{"name":"project-dispatcher","description":"fixture dispatcher","model":{"preferred":"test/dispatcher-model","fallbacks":[]}}""")
      os.makeDir.all(tmp / "agents" / "kernel")
      os.write(tmp / "agents" / "kernel" / "agent.json", """{"name":"kernel","description":"fixture kernel"}""")
      os.write(tmp / "agents" / "kernel" / "system.md", "k")
      os.makeDir.all(tmp / "agents" / "general")
      os.write(tmp / "agents" / "general" / "agent.json", """{"name":"general","description":"fixture general"}""")
      os.write(tmp / "agents" / "general" / "system.md", "g")
      os.makeDir.all(tmp / "agents" / "Coder")
      os.write(
        tmp / "agents" / "Coder" / "agent.json",
        """{"name":"Coder","description":"non-role fixture","model":{"preferred":"zhipu/GLM-5.3-Flash","fallbacks":[]}}"""
      )
      os.write(tmp / "agents" / "Coder" / "system.md", "c")
      test().unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
    end try
  end withFixture

  private val NullBackend = null.asInstanceOf[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]]

  /**
   * Real SharedResources so the GET arm's candidate/health read faces run:
   * empty provider config => every chain ref resolves to nothing => current
   * = null, while the chain-face fields carry the assertions.
   *
   * `seededOverrides`（模型链热应用批 2026-10-31）：预置的会话驻留 pin
   * （sessionModelOverrides）——PUT 热应用清除腿的判床。
   */
  private def mkResources(
    seededOverrides: Map[String, ModelCandidate] = Map.empty
  ): nebflow.agent.SharedResources =
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
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](seededOverrides),
      providerRegistry = new nebflow.llm.ProviderRegistry(configRef, NullBackend),
      healthMonitor = nebflow.llm.ProviderHealthMonitor(null),
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  end mkResources

  private def mkRoutes(res: nebflow.agent.SharedResources = mkResources()): HttpRoutes[IO] =
    val inner = new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = res,
      sessionStore = null,
      wsRoutes = null
    )
    val wsb = null.asInstanceOf[WebSocketBuilder2[IO]]
    inner.routes <+> inner.presenceWsRoutes(wsb)

  private def getJson(path: String): IO[Response[IO]] =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
    mkRoutes()(req).value.map(_.getOrElse(fail(s"GET $path fell through")))

  private def putJson(path: String, body: io.circe.Json): IO[Response[IO]] =
    val req = Request[IO](Method.PUT, Uri.unsafeFromString(path))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
      .withEntity(body)
    mkRoutes()(req).value.map(_.getOrElse(fail(s"PUT $path fell through")))

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
        _ = assert(
          !os.read(PathUtil.dataRoot / "agents" / "kernel" / "agent.json").contains("kimi/kimi-k3"),
          "follow transition must remove the stored chain"
        )
      yield ()
      end for
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
        assertEquals(
          json.hcursor.downField("chain").downField("preferred").as[String],
          Right("deepseek/deepseek-flash")
        )
    }

  test("PUT hot-applies: class change retains matching pins, purges out-of-chain pins; fallback is not a member"):
    // 模型粒度批：slot PUT 是类级更新。链内 pin 判据只接受 preferred（fallback 已废除），
    // 因而同类所有 session 的 matching pins 保留，链外 pin 清除。
    withFixture { () =>
      // null provider cast：清除判据只读 (providerId, model)——与 NullBackend 同款
      // spec-only 形态，provider 对象永不解引用。
      val nul = null.asInstanceOf[ProviderConfig]
      val seeded = Map(
        "delegate-a" -> ModelCandidate("zhipu", nul, "GLM-5.3-Flash"),
        "delegate-b" -> ModelCandidate("zhipu", nul, "GLM-5.3-Flash"),
        "delegate-c" -> ModelCandidate("kimi", nul, "kimi-k3"),
        "session-unrelated" -> ModelCandidate("anon", nul, "anon-gone")
      )
      val resources = mkResources(seeded)
      val routes = mkRoutes(resources)
      def put(body: io.circe.Json): IO[Response[IO]] =
        val req = Request[IO](Method.PUT, Uri.unsafeFromString("/agents/Nebula/model"))
          .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
          .withEntity(body)
        routes(req).value.map(_.getOrElse(fail("PUT fell through")))
      val newChain = io.circe.Json.obj(
        "model" -> io.circe.Json.obj(
          "preferred" -> "zhipu/GLM-5.3-Flash".asJson,
          "fallbacks" -> List("kimi/kimi-k3").asJson
        )
      )
      for
        resp <- put(newChain)
        _ = assertEquals(resp.status, Status.Ok)
        overrides <- resources.sessionModelOverrides.get
      yield
        assert(!overrides.contains("session-unrelated"), s"out-of-chain pin must be purged: ${overrides.keys}")
        assert(overrides.contains("delegate-a"), s"matching pin on one class session survives: ${overrides.keys}")
        assert(overrides.contains("delegate-b"), s"matching pin on another class session survives: ${overrides.keys}")
        assert(!overrides.contains("delegate-c"), s"fallback-only ref is outside preferred-only chain: ${overrides.keys}")
      end for
    }

  test("all five class slots are writable and a class-chain update is visible to every session in that slot"):
    withFixture { () =>
      val slots = List(
        "Nebula" -> "main-model",
        "kernel" -> "kernel-model",
        "project-dispatcher" -> "dispatcher-model",
        "node" -> "node-model",
        "subagent" -> "subagent-model"
      )
      slots.traverse_ { case (slot, model) =>
        putJson(
          s"/agents/$slot/model",
          io.circe.Json.obj("model" -> io.circe.Json.obj("preferred" -> s"test/$model".asJson, "fallbacks" -> io.circe.Json.arr()))
        ).flatMap { resp =>
          resp.as[io.circe.Json].map { json =>
            assertEquals(resp.status, Status.Ok, s"slot $slot accepts class-level writes")
            assertEquals(json.hcursor.downField("mode").as[String], Right("explicit"))
          }
        }
      }.map { _ =>
        val sessions = List(
          "main" -> List("main-session-a", "main-session-b"),
          // 生产 id 形状（逐条锚见 ModelRoles 头注「分类判据」）：kernel 会话 = delegate-kernel-<8hex>。
          "kernel" -> List("delegate-kernel-a1b2c3d4", "delegate-kernel-e5f6a7b8"),
          "dispatcher" -> List("dispatcher-1a2b3c4d", "dispatcher-5e6f7a8b"),
          "node" -> List("dag-orders-1-123456", "dag-orders-2-123457"),
          "subagent" -> List("delegate-a", "delegate-b")
        )
        sessions.foreach { case (slot, ids) =>
          val sidecarSlot = nebflow.core.ModelRoles.Slots.find(_._1 == slot).get._3
          val configured = nebflow.core.ModelRoles.ownChainOfSlot(sidecarSlot).flatMap(_.preferred)
          val expectedModel = slot match
            case "main" => "main-model"
            case "kernel" => "kernel-model"
            case "dispatcher" => "dispatcher-model"
            case "node" => "node-model"
            case _ => "subagent-model"
          assertEquals(configured, Some(s"test/$expectedModel"))
          if slot != "main" then
            ids.foreach { id =>
              // 归槽返回值 = 槽目录名（slots 表第三列），不是 wire 名——dispatcher 的
              // wire 名在 agents/ 下没有目录（首轮实跑把它直接喂 ownChainOf ⇒ 类切换静默失效）。
              assertEquals(nebflow.core.ModelRoles.slotOfSessionId(Some(id)), Some(sidecarSlot))
              assertEquals(nebflow.core.ModelRoles.slotChainOf(Some(id)).flatMap(_.preferred), configured)
            }
        }
      }
    }

  test("wire spellings (main / dispatcher) are normalized to the slot sidecar — a class write never lands in an unread sidecar") {
    // 模型粒度批回归：`ModelRoles.isSlot` 允许 wire 拼写（main/dispatcher），但只有
    // 槽目录名对应 agents/<dir>/agent.json。不归一时 `PUT /agents/main/model` 会就地
    // 建 agents/main/agent.json，而引擎只读 agents/Nebula/ ⇒ 静默空写（五类粒度
    // 「可配置」在该拼写下失效）。本用例钉住归一：写落 Nebula 槽、读走 project-dispatcher 槽。
    withFixture { () =>
      for
        wrote <- putJson(
          "/agents/main/model",
          io.circe.Json.obj("model" -> io.circe.Json.obj("preferred" -> "test/main-model".asJson, "fallbacks" -> io.circe.Json.arr()))
        )
        wroteJson <- wrote.as[io.circe.Json]
        dispatcher <- getJson("/agents/dispatcher/model")
        dispatcherJson <- dispatcher.as[io.circe.Json]
      yield
        assertEquals(wrote.status, Status.Ok)
        assertEquals(wroteJson.hcursor.downField("mode").as[String], Right("explicit"))
        assert(
          os.read(PathUtil.dataRoot / "agents" / "Nebula" / "agent.json").contains("test/main-model"),
          "the wire spelling 'main' must land in the Nebula slot sidecar (the only one the engine reads)"
        )
        assert(
          !os.exists(PathUtil.dataRoot / "agents" / "main"),
          "no phantom agents/main/ sidecar may be created"
        )
        // 读面同归一：dispatcher（wire）→ project-dispatcher（槽）的既有链。
        assertEquals(dispatcherJson.hcursor.downField("mode").as[String], Right("explicit"))
        assertEquals(
          dispatcherJson.hcursor.downField("chain").downField("preferred").as[String],
          Right("test/dispatcher-model"),
          "the wire spelling 'dispatcher' must read the project-dispatcher slot sidecar"
        )
    }
  }

  test("session-level switch is isolated to that session; it does not mutate class slots or sibling sessions"):
    val overrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty)
    val nul = null.asInstanceOf[ProviderConfig]
    val target = ModelCandidate("private", nul, "one-session-only")
    val siblingBefore = ModelCandidate("private", nul, "sibling-default")
    val slotChain = nebflow.shared.AgentModelConfig(Some("class/model"), Nil)
    for
      _ <- overrides.update(_ + ("delegate-session-a" -> target))
      after <- overrides.get
    yield
      assertEquals(after.get("delegate-session-a"), Some(target))
      assertEquals(after.get("delegate-session-b"), None, "session switch does not pin a sibling")
      assertEquals(siblingBefore.model, "sibling-default")
      assertEquals(nebflow.core.ModelRoles.slotChainOf(Some("delegate-session-b")), None, "no class sidecar is changed by a session override")
      assertEquals(slotChain.preferred, Some("class/model"))

  test("PUT empty chain ({preferred: null, fallbacks: []}) normalizes to follow"):
    withFixture { () =>
      for
        _ <- putJson(
          "/agents/general/model",
          io.circe.Json.obj("preferred" -> io.circe.Json.Null.asJson, "fallbacks" -> io.circe.Json.arr())
        )
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
      putJson(
        "/agents/Coder/model",
        io.circe.Json.obj("model" -> io.circe.Json.obj("preferred" -> "x/y".asJson, "fallbacks" -> io.circe.Json.arr()))
      ).flatMap { resp =>
        resp.as[io.circe.Json].map { json =>
          assertEquals(resp.status, Status.BadRequest)
          val err = json.hcursor.downField("error").as[String].getOrElse("")
          assert(err.contains("does not accept a model chain"), s"error must be actionable: $err")
          assertEquals(
            os.read(PathUtil.dataRoot / "agents" / "Coder" / "agent.json"),
            before,
            "refused write must leave the stored data untouched"
          )
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

  test("PUT a settable name with no agent dir → in-place sidecar + 200 (builtin-def); a non-settable name → 400 gate first"):
    withFixture { () =>
      // 本用例的前提是「该角色在盘上没有 agent 目录」——fixture 为五类槽用例
      // 预置了 project-dispatcher 的 sidecar（模型粒度批），这里先摘掉它还原前提
      // （否则读到既有 {"name","description",...} 而不是就地新建的 {"name"}）。
      os.remove.all(PathUtil.dataRoot / "agents" / "project-dispatcher")
      for
        fresh <- putJson("/agents/project-dispatcher/model", io.circe.Json.obj("model" -> io.circe.Json.Null))
        json <- fresh.as[io.circe.Json]
        stranger <- putJson("/agents/Nobody/model", io.circe.Json.obj("model" -> io.circe.Json.Null))
      yield
        // builtin-def batch: a settable role has no agent dir on a fresh home,
        // so the route creates the minimal {"name": ...} sidecar in place and
        // proceeds — a settable name can no longer 404 (the model chain is a
        // user setting, writable on every home shape).
        assertEquals(fresh.status, Status.Ok, "settable + no dir must end in an in-place sidecar write, not a 404")
        assertEquals(json.hcursor.downField("updated").as[Boolean], Right(true))
        assertEquals(json.hcursor.downField("mode").as[String], Right("follow"))
        val sidecar = io.circe.parser
          .parse(os.read(PathUtil.dataRoot / "agents" / "project-dispatcher" / "agent.json")) match
          case Right(j) => j
          case Left(e)  => fail(s"the created sidecar must be JSON: $e")
        assertEquals(
          sidecar.asObject.map(_.keys.toSet),
          Some(Set("name")),
          "a null chain leaves the sidecar bare: no model key may appear"
        )
        assertEquals(sidecar.hcursor.downField("name").as[String], Right("project-dispatcher"))
        assertEquals(
          stranger.status,
          Status.BadRequest,
          "the write gate refuses non-role names before any existence check"
        )
    }
end ModelChainRoutesSpec
