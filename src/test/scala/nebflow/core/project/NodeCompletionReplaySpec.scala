package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SpecResources
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

class NodeCompletionReplaySpec extends CatsEffectSuite:
  private val root = os.pwd / "target" / "test-completion-replay"
  private val originalRoot = PathUtil.dataRoot

  override def beforeAll(): Unit =
    os.makeDir.all(root)
    PathUtil.setDataRoot(root)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private val llm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("unexpected LLM"))
    def sendStream(req: LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("unexpected LLM"))

  private def fixture(name: String)(run: (FlowMapStore, NodeEngine, Ref[IO, List[String]]) => IO[Unit]): IO[Unit] =
    val ws = root / s"$name-${java.util.UUID.randomUUID()}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"replay-$name")
    for
      res <- SpecResources.mkResources(system, root, llm)
      store <- FlowMapStore.open(s"replay-$name", ws.toString)
      events <- Ref.of[IO, List[String]](Nil)
      engine = new NodeEngine(store, system, res, wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString, rootSessionId = "root", projectName = s"replay-$name",
        emitEvent = (t, _, _) => events.update(_ :+ t), reportGateHold = Some(false))
      _ <- run(store, engine, events)
    yield ()

  private def node(status: String, session: String = "run-1", started: Long = 1L): NodeDef =
    NodeDef(id = "n", name = "n", agent = "general", task = Some("task"), status = status,
      createdAt = 1L, sessionRef = Some(session), startedAt = Some(started), result = Some("original"))

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes.updated(n.id, n))).void

  for status <- List(NodeLifecycle.Completed, NodeLifecycle.Cancelled) do
    test(s"$status completion replay is a no-op") {
      fixture(s"terminal-$status") { (store, engine, events) =>
        val original = node(status)
        for
          _ <- seed(store, original)
          accepted <- engine.completeNodeR("n", "replacement")
          after <- store.getNode("n")
          emitted <- events.get
          _ = assertEquals(accepted, false)
          _ = assertEquals(after, Some(original))
          _ = assertEquals(emitted, Nil)
        yield ()
      }
    }

  test("concurrent same-run completion settles once") {
    fixture("concurrent") { (store, engine, events) =>
      for
        _ <- seed(store, node(NodeLifecycle.Running))
        results <- List.fill(2)(engine.completeNodeR("n", "result")).parSequence
        after <- store.getNode("n")
        emitted <- events.get
        _ = assertEquals(results.count(identity), 1)
        _ = assertEquals(after.flatMap(_.result), Some("result"))
        _ = assertEquals(emitted.count(_ == "nodeCompleted"), 1)
      yield ()
    }
  }

  test("concurrent verifier PASS has one atomic verdict and completion") {
    fixture("verifier-pass") { (store, engine, events) =>
      val verifier = node(NodeLifecycle.Running).copy(role = NodeRoles.Verifier)
      for
        _ <- seed(store, verifier)
        results <- List.fill(2)(engine.completeNodeR("n", "VERDICT: PASS")).parSequence
        after <- store.getNode("n")
        emitted <- events.get
        _ = assertEquals(results.count(identity), 1)
        _ = assertEquals(after.flatMap(_.lastVerdict), Some("pass"))
        _ = assertEquals(emitted.count(_ == "nodeCompleted"), 1)
        _ = assertEquals(emitted.count(_ == "nodeUpdated"), 0)
      yield ()
    }
  }

  test("concurrent verifier FAIL without a route records and audits once") {
    fixture("verifier-fail") { (store, engine, events) =>
      val verifier = node(NodeLifecycle.Running).copy(role = NodeRoles.Verifier)
      for
        _ <- seed(store, verifier)
        results <- List.fill(2)(engine.completeNodeR("n", "VERDICT: FAIL\n{\"issues\":[\"bad\"],\"requirements\":\"fix\"}")).parSequence
        after <- store.getNode("n")
        emitted <- events.get
        _ = assertEquals(results.count(identity), 1)
        _ = assertEquals(after.flatMap(_.lastVerdict), Some("fail"))
        _ = assertEquals(emitted.count(_ == "nodeCompleted"), 1)
        audit <- IO.blocking(os.read(os.Path(store.baseWorkspace) / ".nebflow" / FlowMapEventLog.FileName))
        _ = assertEquals(audit.linesIterator.count(line => io.circe.parser.parse(line).toOption.exists(_.hcursor.get[String]("type").contains(FlowMapEventLog.VerifierRouteLostType))), 1)
      yield ()
    }
  }

  test("old verifier generation cannot mutate a newer verifier verdict") {
    fixture("verifier-generation") { (store, engine, events) =>
      val current = node(NodeLifecycle.Running, "run-2", 2L).copy(role = NodeRoles.Verifier)
      for
        _ <- seed(store, current)
        accepted <- engine.completeNodeR("n", "VERDICT: PASS", Some((Some("run-1"), Some(1L))))
        after <- store.getNode("n")
        emitted <- events.get
        _ = assertEquals(accepted, false)
        _ = assertEquals(after, Some(current))
        _ = assertEquals(emitted, Nil)
      yield ()
    }
  }

  test("old generation cannot complete a new running generation") {
    fixture("generation") { (store, engine, events) =>
      val current = node(NodeLifecycle.Running, "run-2", 2L)
      for
        _ <- seed(store, current)
        accepted <- engine.completeNodeR("n", "old result", Some((Some("run-1"), Some(1L))))
        after <- store.getNode("n")
        emitted <- events.get
        _ = assertEquals(accepted, false)
        _ = assertEquals(after, Some(current))
        _ = assertEquals(emitted, Nil)
        landed <- engine.completeNodeR("n", "new result", Some((Some("run-2"), Some(2L))))
        _ = assert(landed)
      yield ()
    }
  }
