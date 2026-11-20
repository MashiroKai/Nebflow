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
    Resource
      .make(IO.blocking {
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
      }
      .map(_._1)

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

  test("file-bound Ask Bash refuses write commands before execution (canvas-ask-restricted-edit red face)") {
    fixture.use { root =>
      val system = ActorSystem("ask-bash-refusal")
      val registry = new AskSessionRegistry(root)
      val directory = root / "documents"
      val file = directory / "notes.md"
      val sessionId = AskSessions.sessionIdFor(file.toString)
      // 每类危险命令一名受害者：拒收后受害者必须在场（拒绝发生在执行前）。
      val victims = List("victim-exec.md", "victim-cmdsub.md", "victim-semi.md")
      val unsafe = List(
        "rm victim-rm.md",
        "find . -name victim-exec.md -exec rm {} +",
        "rg --pre cat MARKER notes.md",
        "grep MARKER notes.md > leak.txt",
        "echo $(rm victim-cmdsub.md)",
        "ls; rm victim-semi.md"
      )
      val green = List("grep -n ASK-BASH-EVIDENCE notes.md", "cat notes.md | grep ASK-BASH-EVIDENCE")
      val all = unsafe.map(cmd => (cmd, true)) ++ green.map(cmd => (cmd, false))

      (for
        _ <- IO.blocking {
          os.makeDir.all(directory)
          os.write(file, "Ask fixture ASK-BASH-EVIDENCE line\nplain line\n")
          (victims :+ "victim-rm.md").foreach(v => os.write(directory / v, "victim\n"))
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
            Stream.eval(requests.update(_ :+ req)).flatMap { _ =>
              val n = requestsGetCount(req)
              if n <= all.length then
                val idx = n - 1
                val (cmd, _) = all(idx)
                Stream(
                  StreamChunk.ToolCallChunk(
                    ToolCall(
                      s"c$idx",
                      "Bash",
                      JsonObject("command" -> cmd.asJson, "run_in_background" -> false.asJson)
                    )
                  ),
                  StreamChunk.Done(None, None)
                )
              else Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
            }
          // 请求序号靠 messages 里工具结果计数（无并发竞态：单轮顺序流）。
          private def requestsGetCount(req: LlmRequest): Int =
            req.messages.flatMap(_.content.toOption.toList.flatten).count {
              case ContentBlock.ToolResult(_, _, _) => true
              case _ => false
            } + 1
        resources <- SpecResources.mkResources(system, root, llm)
        actor <- system.spawn(
          AgentActor(
            agentDef = AgentDef(name = "ask", description = "ask refusal fixture", tools = List("*")),
            resources = resources,
            wsSend = json =>
              events.update(_ :+ json) *>
                (if json.hcursor.get[String]("type").toOption.contains("done") then done.complete(()).void
                 else IO.unit),
            depth = 0,
            sessionId = Some(sessionId),
            projectRoot = Some(root.toString),
            sessionCwd = cwd
          ),
          sessionId
        )
        _ <- actor ! AgentCommand.ImmediateInput("Run the commands.", fromUser = true)
        _ <- done.get.timeout(30.seconds)
        captured <- requests.get
        _ <- IO {
          val results = captured.last.messages
            .flatMap(_.content.toOption.toList.flatten)
            .collect { case ContentBlock.ToolResult(id, content, error) => (id, content, error) }
          assertEquals(results.size, all.length, s"one result per command: ${results.map(_._1)}")
          all.zipWithIndex.foreach { case ((cmd, expectRefused), i) =>
            val (id, content, error) = results(i)
            assertEquals(id, s"c$i", "results arrive in call order")
            if expectRefused then
              assert(error.contains(true), s"[$cmd] must be refused, got: ${content.take(160)}")
              assert(
                content.toLowerCase.contains("refus"),
                s"[$cmd] refusal must be explicit, got: ${content.take(160)}"
              )
            else
              assert(!error.contains(true), s"[$cmd] must run, got: ${content.take(160)}")
              assert(
                content.contains("ASK-BASH-EVIDENCE"),
                s"[$cmd] must return real output, got: ${content.take(160)}"
              )
          }
          // 拒绝发生在执行前：受害者与泄漏文件全部在场。
          (victims :+ "victim-rm.md").foreach(v =>
            assert(os.exists(directory / v), s"[$v] must survive — refusal must precede execution")
          )
          assert(!os.exists(directory / "leak.txt"), "redirection must not create files")
        }
        _ <- actor ! AgentCommand.Stop("ask-bash-refusal-complete")
      yield ()).guarantee(system.stopAll.guarantee(ShellSession.destroySession(sessionId)))
    }
  }

  test("Ask executes query-only Bash in the registered file directory and returns its output") {
    fixture.use { root =>
      val system = ActorSystem("ask-bash-execution")
      val registry = new AskSessionRegistry(root)
      val directory = root / "documents"
      val file = directory / "notes.md"
      val sessionId = AskSessions.sessionIdFor(file.toString)
      val toolId = "ask-grep"
      val marker = "ASK-BASH-EVIDENCE"

      (for
        _ <- IO.blocking {
          os.makeDir.all(directory)
          os.write(file, s"Ask fixture $marker line\nplain line\n")
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
            Stream.eval(requests.update(_ :+ req)).flatMap { _ =>
              val n = req.messages.flatMap(_.content.toOption.toList.flatten).count {
                case ContentBlock.ToolResult(_, _, _) => true
                case _ => false
              } + 1
              if n == 1 then
                Stream(
                  StreamChunk.ToolCallChunk(
                    ToolCall(
                      toolId,
                      "Bash",
                      JsonObject(
                        // cwd = 绑定文件目录：-r 让输出回显 ./notes.md 路径——
                        // 裸相对名命中即 cwd 证据（查询面绿样本）。
                        "command" -> s"grep -rn $marker .".asJson,
                        "run_in_background" -> false.asJson
                      )
                    )
                  ),
                  StreamChunk.Done(None, None)
                )
              else if n == 2 then
                Stream(
                  StreamChunk.ToolCallChunk(
                    ToolCall(
                      "ask-printf",
                      "Bash",
                      JsonObject("command" -> "printf '%s' PWD".asJson, "run_in_background" -> false.asJson)
                    )
                  ),
                  StreamChunk.Done(None, None)
                )
              else
                val result = req.messages
                  .flatMap(_.content.toOption.toList.flatten)
                  .collectFirst { case ContentBlock.ToolResult(`toolId`, content, _) =>
                    content
                  }
                  .getOrElse("missing Bash result")
                Stream(StreamChunk.TextDelta(s"Ask query result: $result"), StreamChunk.Done(None, None))
              end if
            }
        resources <- SpecResources.mkResources(system, root, llm)
        actor <- system.spawn(
          AgentActor(
            agentDef = AgentDef(name = "ask", description = "Ask shell fixture", tools = List("*")),
            resources = resources,
            wsSend = json =>
              events.update(_ :+ json) *>
                (if json.hcursor.get[String]("type").toOption.contains("done") then done.complete(()).void
                 else IO.unit),
            depth = 0,
            sessionId = Some(sessionId),
            projectRoot = Some(root.toString),
            sessionCwd = cwd
          ),
          sessionId
        )
        _ <- actor ! AgentCommand.ImmediateInput("Report the directory and query evidence.", fromUser = true)
        _ <- done.get.timeout(20.seconds)
        captured <- requests.get
        frames <- events.get
        _ <- IO {
          assertEquals(captured.size, 3, "two Bash calls followed by one answer")
          captured.foreach { request =>
            assertEquals(request.tools.toList.flatten.map(_.name).toSet, AskSessions.AllowedTools)
          }
          val results = captured.last.messages
            .flatMap(_.content.toOption.toList.flatten)
            .collect { case ContentBlock.ToolResult(id, content, error) => (id, content, error) }
          val (_, out1, err1) = results
            .collectFirst { case (i, c, e) if i == toolId => (i, c, e) }
            .getOrElse(fail("Ask must receive the allowlisted Bash result"))
          assert(!err1.contains(true), out1)
          assert(out1.contains(marker), out1)
          assert(out1.contains("notes.md"), s"cwd must be the bound file directory: $out1")
          val (_, out2, err2) = results
            .collectFirst { case (i, c, e) if i == "ask-printf" => (i, c, e) }
            .getOrElse(fail("Ask must receive the printf result"))
          assert(err2.contains(true), s"printf must be refused on the query-only face: $out2")
          assert(out2.toLowerCase.contains("refus"), out2)
          val answer = frames.flatMap(_.hcursor.get[String]("delta").toOption).mkString
          assert(answer.contains(marker), answer)
        }
        shell <- ShellSession.peekSession(sessionId)
        _ <- IO(assert(shell.isEmpty, "the query-only face must not create the persistent session shell"))
        _ <- actor ! AgentCommand.Stop("ask-bash-test-complete")
      yield ()).guarantee(system.stopAll.guarantee(ShellSession.destroySession(sessionId)))
    }
  }
end AskBashExecutionSpec
