package nebflow.core.ask

import cats.effect.{Deferred, IO, Resource}
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentDef}
import nebflow.agent.{AgentActor, SpecResources}
import nebflow.core.tools.ShellSession
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/** Exercises the same binding lookup and normal tool pipeline used by gateway Ask sessions. */
final class AskBashExecutionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 45.seconds

  private def fixture: Resource[IO, os.Path] =
    Resource.make(IO.blocking {
      val originalRoot = PathUtil.dataRoot
      val root = os.Path(os.temp.dir(prefix = "ask-bash-").toNIO.toRealPath())
      os.makeDir.all(root / "agents")
      os.write(root / nebflow.shared.Branding.configFileName, "{}")
      PathUtil.setDataRoot(root)
      (root, originalRoot)
    }) { case (root, originalRoot) =>
      IO.blocking {
        PathUtil.setDataRoot(originalRoot)
        os.remove.all(root)
      }
    }.map(_._1)

  test("Ask cwd uses only its registered file directory") {
    fixture.use { root =>
      val registry = new AskSessionRegistry(root)
      val directory = root / "documents"
      val file = directory / "notes.md"
      for
        _ <- IO.blocking {
          os.makeDir.all(directory)
          os.write(file, "Ask fixture")
        }
        binding <- registry.bind(file.toString, file.last)
        cwd <- AskSessions.sessionCwd(binding.id, Some(registry))
      yield assertEquals(cwd, Some(directory.toString))
    }
  }

  test("Ask cwd rejects missing services, missing bindings and invalid bound files") {
    fixture.use { root =>
      val registry = new AskSessionRegistry(root)
      for
        unavailable <- AskSessions.sessionCwd("ask-unavailable", None).attempt
        unbound <- AskSessions.sessionCwd("ask-unbound", Some(registry)).attempt
        missing <- registry.bind((root / "missing.md").toString, "missing.md")
        missingResult <- AskSessions.sessionCwd(missing.id, Some(registry)).attempt
        _ <- IO.blocking(os.write(root / "invalid.txt", "not askable"))
        invalid <- registry.bind((root / "invalid.txt").toString, "invalid.txt")
        invalidResult <- AskSessions.sessionCwd(invalid.id, Some(registry)).attempt
      yield
        assert(unavailable.left.exists(_.getMessage.contains("services unavailable")))
        assert(unbound.left.exists(_.getMessage.contains("no file binding")))
        assert(missingResult.isLeft)
        assert(invalidResult.isLeft)
    }
  }

  test("non-Ask cwd selection remains unchanged without Ask services") {
    AskSessions.sessionCwd("ordinary-session", None).map(cwd => assertEquals(cwd, None))
  }

  test("Ask executes foreground Bash in the registered file directory and returns its output") {
    fixture.use { root =>
      val system = ActorSystem("ask-bash-execution")
      val registry = new AskSessionRegistry(root)
      val directory = root / "documents"
      val file = directory / "notes.md"
      val sessionId = AskSessions.sessionIdFor(file.toString)
      val toolId = "ask-printf"
      val marker = "ASK-BASH-EVIDENCE"
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)

      def awaitShellCleanup(attempts: Int): IO[Unit] =
        ShellSession.peekSession(sessionId).flatMap {
          case None => IO.unit
          case Some(_) if attempts > 0 => IO.sleep(50.millis) *> awaitShellCleanup(attempts - 1)
          case Some(_) => IO.raiseError(new AssertionError("Ask session shell was not released"))
        }

      (for
        _ <- IO.blocking {
          os.makeDir.all(directory)
          os.write(file, "Ask fixture")
        }
        binding <- registry.bind(file.toString, file.last)
        cwd <- AskSessions.sessionCwd(binding.id, Some(registry))
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        done <- Deferred[IO, Unit]
        llm = new LlmHandle[IO]:
          def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new IllegalStateException("stream only"))
          def sendStream(
            req: LlmRequest,
            onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
          ): Stream[IO, StreamChunk] =
            Stream.eval(requests.update(_ :+ req) *> IO(calls.incrementAndGet())).flatMap { n =>
              if n == 1 then
                Stream(
                  StreamChunk.ToolCallChunk(ToolCall(
                    toolId,
                    "Bash",
                    JsonObject(
                      "command" -> "printf '%s\\n' \"$PWD\" 'ASK-BASH-EVIDENCE'".asJson,
                      "timeout" -> 5000.asJson,
                      "run_in_background" -> false.asJson
                    )
                  )),
                  StreamChunk.Done(None, None)
                )
              else
                val result = req.messages.flatMap(_.content.toOption.toList.flatten).collectFirst {
                  case ContentBlock.ToolResult(`toolId`, content, _) => content
                }.getOrElse("missing Bash result")
                Stream(StreamChunk.TextDelta(s"Ask shell result: $result"), StreamChunk.Done(None, None))
            }
        resources <- SpecResources.mkResources(system, root, llm)
        actor <- system.spawn(
          AgentActor(
            agentDef = AgentDef(name = "ask", description = "Ask shell fixture", tools = List("*")),
            resources = resources,
            wsSend = json => events.update(_ :+ json) *>
              (if json.hcursor.get[String]("type").toOption.contains("done") then done.complete(()).void else IO.unit),
            depth = 0,
            sessionId = Some(sessionId),
            projectRoot = Some(root.toString),
            sessionCwd = cwd
          ),
          sessionId
        )
        _ <- actor ! AgentCommand.ImmediateInput("Report the directory and shell evidence.", fromUser = true)
        _ <- done.get.timeout(20.seconds)
        captured <- requests.get
        frames <- events.get
        _ <- IO {
          assertEquals(captured.size, 2, "one Bash call followed by one answer")
          captured.foreach { request =>
            assertEquals(request.tools.toList.flatten.map(_.name).toSet, AskSessions.AllowedTools)
          }
          val result = captured.last.messages.flatMap(_.content.toOption.toList.flatten).collectFirst {
            case ContentBlock.ToolResult(`toolId`, content, error) => (content, error)
          }.getOrElse(fail("Ask must receive the actual Bash result"))
          assert(!result._2.contains(true), result._1)
          assert(result._1.linesIterator.exists(_.trim == directory.toString), result._1)
          assert(result._1.contains(marker), result._1)
          val answer = frames.flatMap(_.hcursor.get[String]("delta").toOption).mkString
          assert(answer.contains(result._1), answer)
        }
        shell <- ShellSession.peekSession(sessionId)
        _ <- IO(assert(shell.isDefined, "the command must create the normal session shell"))
        _ <- actor ! AgentCommand.Stop("ask-bash-test-complete")
        _ <- awaitShellCleanup(100)
      yield ()).guarantee(system.stopAll.guarantee(ShellSession.destroySession(sessionId)))
    }
  }
end AskBashExecutionSpec
