package nebflow.core.project

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SpecResources
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}
import scala.concurrent.duration.*

/** E01's scripted, concurrent two-upstream/one-downstream fixture, adapted as an executable test. */
class NodeCompletionReplayIntegrationSpec extends CatsEffectSuite:
  override def munitIOTimeout: FiniteDuration = 90.seconds
  private val originalRoot = PathUtil.dataRoot
  private val root = os.pwd / "target" / "test-replay-integration"
  override def beforeAll(): Unit =
    os.makeDir.all(root / "agents" / "general")
    os.write.over(root / "agents" / "general" / "agent.json", """{"name":"general","description":"fixture","tools":[],"category":"standalone"}""")
    os.write.over(root / "agents" / "general" / "system.md", "fixture")
    PathUtil.setDataRoot(root)
  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  test("E01: concurrent upstreams deliver once and replay preserves downstream artifact") {
    val ws = root / java.util.UUID.randomUUID().toString
    os.makeDir.all(ws)
    val system = ActorSystem("completion-replay-e01")
    Resource.make(IO.pure(system))(_.stopAll).use { _ =>
      for
        entered <- Ref.of[IO, Set[String]](Set.empty)
        both <- Deferred[IO, Unit]
        completed <- Deferred[IO, Unit]
        events <- Ref.of[IO, Vector[(String, String)]](Vector.empty)
        inputs <- Ref.of[IO, Vector[String]](Vector.empty)
        llm = new LlmHandle[IO]:
          def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new Exception("unexpected send"))
          def sendStream(req: LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
            val input = req.messages.map(_.textContent).mkString("\n")
            val parent = input.contains("FIXTURE-PARENT")
            val admission = inputs.update(_ :+ input) *> (if parent then IO.unit else
              entered.modify { ids => val next = ids + req.sessionId; (next, next.size == 2) }
                .flatMap(full => if full then both.complete(()).void else IO.unit) *> both.get)
            val output = if parent then "A-MARKER B-MARKER" else if input.linesIterator.exists(_ == "FIXTURE-A") then "A-MARKER" else "B-MARKER"
            Stream.eval(admission).drain ++ Stream(StreamChunk.TextDelta(output), StreamChunk.Done(None, None))
        res <- SpecResources.mkResources(system, root, llm)
        store <- FlowMapStore.open("e01", ws.toString)
        engine = new NodeEngine(store, system, res, (_: Json) => IO.unit, ws.toString, "root", "e01",
          (kind, id, _) => events.update(_ :+ (kind -> id)) *>
            (if kind == "nodeCompleted" && id == "parent" then completed.complete(()).void else IO.unit),
          reportGateHold = Some(false))
        _ <- store.mutate(s => s.copy(nodes = Map(
          "a" -> NodeDef(id = "a", name = "a", agent = "general", createdAt = 0L, task = Some("FIXTURE-A"), out = List(OutEdge("parent"))),
          "b" -> NodeDef(id = "b", name = "b", agent = "general", createdAt = 0L, task = Some("FIXTURE-B"), out = List(OutEdge("parent"))),
          "parent" -> NodeDef(id = "parent", name = "parent", agent = "general", createdAt = 0L, task = Some("FIXTURE-PARENT"), in = List("a", "b"))
        )))
        _ <- (engine.startNode("a"), engine.startNode("b")).parTupled.timeout(30.seconds)
        _ <- completed.get.timeout(30.seconds)
        before <- store.getNode("parent")
        beforeA <- store.getNode("a")
        accepted <- engine.completeNodeR("a", "CORRUPTED REPLAY")
        after <- store.getNode("parent")
        afterA <- store.getNode("a")
        ev <- events.get
        ins <- inputs.get
        count <- entered.get
        _ = assertEquals(count.size, 2)
        _ = assert(ins.exists(s => s.contains("FIXTURE-PARENT") && s.contains("A-MARKER") && s.contains("B-MARKER")))
        _ = assertEquals(accepted, false)
        _ = assertEquals(after, before)
        _ = assertEquals(afterA, beforeA)
        _ = assertEquals(after.flatMap(_.result), Some("A-MARKER B-MARKER"))
        _ = assertEquals(ev.count(_ == ("nodeCompleted" -> "a")), 1)
      yield ()
    }
  }
