package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, ToolCall}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * 2026-08-15 FlowTrigger outage — actor-level regression test.
 *
 * Drives the REAL AgentActor through a full turn with only the LlmHandle
 * faked, reproducing the production failure mode: schema says FlowTrigger
 * exists (request.tools), executor says it doesn't (dropped call →
 * "Tool not available: FlowTrigger" injected as the ToolResult).
 *
 * Also pins the restart-restore lifecycle invariant: a session restored
 * with pre-restart history must rebuild systemStable from the CURRENT
 * AgentDef on its first turn (fresh actor = empty cachedSystemStable =
 * isLifecycleRebuild), so tool/flow sections never advertise a
 * pre-restart world.
 */
class FlowTriggerExecutionSpec extends CatsEffectSuite:

  /** Scripted LLM: records every LlmRequest, answers by request index. */
  private class RecordingLlm(responses: Vector[LlmRequest => Stream[IO, StreamChunk]]) extends LlmHandle[IO]:
    val requests: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        Stream.eval(requests.get.map(_.size - 1)).flatMap { idx =>
          responses(Math.min(idx, responses.size - 1))(req)
        }

  private def textAnswer(text: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(text), StreamChunk.Done(None, None))

  private def flowTriggerCall(flow: String): Stream[IO, StreamChunk] =
    Stream(
      StreamChunk.ToolCallChunk(ToolCall(
        "call-ft-1",
        "FlowTrigger",
        JsonObject("flow" -> flow.asJson, "prompt" -> "run the thing".asJson)
      )),
      StreamChunk.Done(Some("tool_use"), None)
    )

  private def toolResults(req: LlmRequest): List[String] =
    req.messages.flatMap(_.content.toOption.toList.flatten).collect {
      case ContentBlock.ToolResult(_, content, _) => content
    }

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** Temp dataRoot + fixture agents/flows; restores dataRoot and stops actors after. */
  private def withEnv[A](llm: LlmHandle[IO])(
    test: (SharedResources, ActorSystem, os.Path) => IO[A]
  ): IO[A] =
    val system = ActorSystem(s"ftf-exec-test-${UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val dataRoot = tmp / "data"
    IO.delay {
      os.makeDir.all(dataRoot)
      // Global agent "Restored" — declares a flow that exists on disk.
      os.makeDir.all(tmp / "agents" / "Restored")
      os.write(
        tmp / "agents" / "Restored" / "agent.json",
        """{"name":"Restored","description":"flows-declaring agent","tools":["Read"],"flows":["demo-flow"]}"""
      )
      // The declared flow — loadFlow must parse it for the Available Flows
      // catalog (FlowStructure requires >= 2 nodes: single agent = skill).
      os.makeDir.all(dataRoot / "flows")
      os.write(
        dataRoot / "flows" / "demo-flow.json",
        """{"name":"demo-flow","description":"Demo flow for catalog rendering","nodes":{"n1":{"agent":"Restored","input":"$task","onComplete":"n2"},"n2":{"agent":"Restored","input":"$n1.output","onComplete":"$return"}},"entry":"n1"}"""
      )
      PathUtil.setDataRoot(dataRoot)
    }.bracket { _ =>
      mkResources(system, tmp, llm).flatMap(test(_, system, tmp))
    } { _ =>
      IO.delay(PathUtil.setDataRoot(prevRoot)) *>
        system.stopAll.attempt.void *>
        IO.delay(if os.exists(tmp) then os.remove.all(tmp)).attempt.void
    }

  // ===== The outage repro: executor must accept what the schema advertised =====

  test("flows-declaring agent: FlowTrigger call executes, not dropped as 'Tool not available'"):
    val llm = new RecordingLlm(Vector(
      _ => flowTriggerCall("ghost-flow"), // turn 1: model calls FlowTrigger
      _ => textAnswer("done")             // turn 2: wrap up after the tool result
    ))
    withEnv(llm) { (resources, system, _) =>
      val memberDef = AgentDef(
        name = "Backend",
        description = "team member with flows",
        tools = List("Read"),
        systemPrompt = "",
        category = "team",
        flows = List("ghost-flow")
      )
      for
        ref <- system.spawn(
          AgentActor(
            agentDef = memberDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(s"ftf-exec-${UUID.randomUUID().toString.take(6)}"),
            sessionName = Some("Backend")
          ),
          "ftf-exec-member"
        )
        _ <- ref ! AgentCommand.UserInput("trigger the ghost flow")
        _ <- waitUntil(30.seconds)(llm.requests.get.map(_.size >= 2))
        reqs <- llm.requests.get.map(_.reverse)
        (schemaReq, toolReq) = (reqs.head, reqs(1))
        schemaTools = schemaReq.tools.toList.flatten
        results = toolResults(toolReq)
      yield
        // Layer 1 (schema): the LLM request must advertise FlowTrigger.
        assert(
          schemaTools.exists(_.name == "FlowTrigger"),
          s"request.tools must contain FlowTrigger (got: ${schemaTools.map(_.name)})"
        )
        // Layer 2 (execution): the call must reach FlowTriggerTool itself
        // ("Flow 'ghost-flow' not found") instead of being dropped by
        // buildAllowedToolSet ("Tool not available: FlowTrigger").
        assert(results.nonEmpty, "second request must carry the tool result")
        val joined = results.mkString("\n")
        assert(!joined.contains("Tool not available"), s"call was dropped by the executor: $joined")
        assert(joined.contains("not found"), s"FlowTriggerTool must have executed: $joined")
    }

  // ===== Mid-session flows whitelist edit takes effect on the running actor =====

  test("flows whitelist edit on disk gates FlowTrigger by the CURRENT def, not the actor snapshot"):
    val llm = new RecordingLlm(Vector(
      _ => flowTriggerCall("other-flow"), // turn 1: call a flow added AFTER spawn
      _ => textAnswer("done")             // turn 2: wrap up after the tool result
    ))
    withEnv(llm) { (resources, system, tmp) =>
      // Actor-startup snapshot: spawned with the ORIGINAL whitelist
      // (["demo-flow"]) — simulating an actor that predates the panel edit.
      val startupDef = AgentDef(
        name = "Restored",
        description = "flows-declaring agent",
        tools = List("Read"),
        systemPrompt = "",
        category = "standalone",
        flows = List("demo-flow")
      )
      for
        ref <- system.spawn(
          AgentActor(
            agentDef = startupDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(s"ftf-midedit-${UUID.randomUUID().toString.take(6)}"),
            sessionName = Some("Restored")
          ),
          "ftf-midedit-agent"
        )
        // Mid-session panel edit: extend the whitelist on disk (the running
        // actor is NOT restarted — 2026-08-15 live repro: flows 4→6 still
        // gated by the stale actor snapshot).
        _ <- IO.delay {
          os.write.over(
            tmp / "agents" / "Restored" / "agent.json",
            """{"name":"Restored","description":"flows-declaring agent","tools":["Read"],"flows":["demo-flow","other-flow"]}"""
          )
        }
        _ <- ref ! AgentCommand.UserInput("trigger the newly whitelisted flow")
        _ <- waitUntil(30.seconds)(llm.requests.get.map(_.size >= 2))
        reqs <- llm.requests.get.map(_.reverse)
        results = toolResults(reqs(1))
      yield
        // The executor gate must read the CURRENT def (ContextRefresher.
        // loadCurrentDef): "other-flow" is whitelisted on disk, so the call
        // reaches FlowTriggerTool itself ("not found" — no such flow json)
        // instead of being rejected by the stale snapshot ("not allowed …
        // Allowed: demo-flow").
        assert(results.nonEmpty, "second request must carry the tool result")
        val joined = results.mkString("\n")
        assert(!joined.contains("not allowed"), s"stale actor snapshot gated the call: $joined")
        assert(joined.contains("not found"), s"FlowTriggerTool must have executed: $joined")
    }

  // ===== Restart-restore lifecycle invariant =====

  test("restored session rebuilds systemStable from the CURRENT def on its first turn"):
    val llm = new RecordingLlm(Vector(_ => textAnswer("ok")))
    withEnv(llm) { (resources, system, tmp) =>
      // The real restart chain: ensureRootAgent → resolveAgentDef →
      // agentLibrary.get(name) — exercised directly here.
      for
        defnOpt <- resources.agentLibrary.get("Restored")
        defn = defnOpt.getOrElse(fail("Restored agent must load from the agents dir"))
        restoredHistory = List(
          Message(MessageRole.User, Left("question from before the restart")),
          Message(MessageRole.Assistant, Left("answer from before the restart"))
        )
        ref <- system.spawn(
          AgentActor(
            agentDef = defn,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(s"ftf-restore-${UUID.randomUUID().toString.take(6)}"),
            sessionName = Some("Restored"),
            initialMessages = restoredHistory
          ),
          "ftf-restore-agent"
        )
        _ <- ref ! AgentCommand.UserInput("continue after restart")
        _ <- waitUntil(30.seconds)(llm.requests.get.map(_.nonEmpty))
        req <- llm.requests.get.map(_.reverse.head)
        stable = req.systemStable.getOrElse("")
        historyKept = req.messages.take(2).map(_.role)
      yield
        // The restored history is folded into the working state…
        assertEquals(historyKept, List(MessageRole.User, MessageRole.Assistant))
        // …and the first turn is a lifecycle rebuild: systemStable reflects
        // the CURRENT def (flows → Available Flows catalog), not a cached
        // pre-restart string.
        assert(stable.contains("Available Flows"), s"systemStable must carry the flows catalog: ${stable.take(400)}")
        assert(stable.contains("demo-flow"), "the flow declared in agent.json must appear in the catalog")
        assert(
          req.tools.toList.flatten.exists(_.name == "FlowTrigger"),
          "the rebuilt tool list must contain FlowTrigger"
        )
    }

  // ===== Dynamic fanout + params: FlowTrigger → FlowDagRunner → executor (real chain) =====

  test("FlowTrigger with params drives a dynamic-fanout flow end-to-end (3 topics → 3 instances → aggregated join)"):
    val requestsRef: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)
    val answeredRef: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val llm = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        IO.raiseError(new RuntimeException("send not expected in this test"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval(requestsRef.update(req :: _)) >>
          Stream.eval(IO(req.sessionId)).flatMap { sid =>
            if sid.startsWith("dag-dynflow-") then
              // flow node session: dag-dynflow-<nodeId>-<ts>
              val nodeId = sid.split("-").apply(2)
              Stream
                .eval(
                  answeredRef.get.flatMap(a =>
                    if a(sid) then IO.pure(false) else answeredRef.update(_ + sid).as(true)
                  )
                )
                .flatMap {
                  case true if nodeId == "p" =>
                    // planner: one FlowReport carrying the topics array slot
                    Stream(
                      StreamChunk.ToolCallChunk(ToolCall(
                        "call-p1",
                        "FlowReport",
                        JsonObject.fromIterable(
                          List(
                            "verdict" -> Json.fromString("done"),
                            "output" -> Json.fromString("PLANNER"),
                            "slots" -> JsonObject.fromIterable(
                              List("topics" -> List(Json.fromString("A"), Json.fromString("B"), Json.fromString("C")).asJson)
                            ).asJson
                          )
                        )
                      )),
                      StreamChunk.Done(Some("tool_use"), None)
                    )
                  case true if nodeId.startsWith("researcher#") =>
                    textAnswer(s"OUT-${nodeId.split("#").last}")
                  case true if nodeId == "j" =>
                    textAnswer("JOIN-DONE")
                  case _ => textAnswer("done")
                }
            else
              // caller agent session: first request calls FlowTrigger with params
              Stream
                .eval(
                  answeredRef.get.flatMap(a =>
                    if a(sid) then IO.pure(false) else answeredRef.update(_ + sid).as(true)
                  )
                )
                .flatMap {
                  case true =>
                    Stream(
                      StreamChunk.ToolCallChunk(ToolCall(
                        "call-ft-1",
                        "FlowTrigger",
                        JsonObject.fromIterable(
                          List(
                            "flow" -> Json.fromString("dynflow"),
                            "prompt" -> Json.fromString("research three topics"),
                            "params" -> JsonObject.fromIterable(List("fanout" -> Json.fromInt(3))).asJson
                          )
                        )
                      )),
                      StreamChunk.Done(Some("tool_use"), None)
                    )
                  case false => textAnswer("ok")
                }
          }
    val system = ActorSystem(s"ftf-dyn-${UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val dataRoot = tmp / "data"
    IO.delay {
      os.makeDir.all(dataRoot)
      // Flow-node agents live under flows/<name>/agents/<agent> — the loader
      // infers category "flow" from the /flows/ path, which auto-injects
      // FlowReport into the node's tool set (a standalone agent referenced by
      // a flow would have FlowReport filtered → silent slot loss).
      os.makeDir.all(dataRoot / "flows" / "dynflow" / "agents" / "Restored")
      os.write(
        dataRoot / "flows" / "dynflow" / "agents" / "Restored" / "agent.json",
        """{"name":"Restored","description":"flow node agent","tools":["Read"],"flows":["dynflow"]}"""
      )
      os.makeDir.all(dataRoot / "flows")
      // The dynamic-fanout flow loaded from DISK — exercises the real
      // parseFlowJson → FlowStructure.validate path with the new syntax.
      os.write(
        dataRoot / "flows" / "dynflow.json",
        """{"name":"dynflow","description":"dynamic fanout smoke","entry":"p","maxFanout":4,
           |"params":{"fanout":{"type":"int","default":2,"min":1,"max":4,"description":"并行路数"}},
           |"nodes":{
           |  "p":{"agent":"Restored","input":"$task\n\nfanout=$params.fanout","outputs":{"topics":"array"},"onComplete":{"parallel":{"slots":"topics","template":"researcher"}}},
           |  "researcher":{"agent":"Restored","input":"Track {{index}}: {{item}}","onComplete":"j"},
           |  "j":{"agent":"Restored","input":"=== ALL ===\n$researcher.all.output","onComplete":"$return"}
           |}}""".stripMargin
      )
      PathUtil.setDataRoot(dataRoot)
    }.bracket { _ =>
      mkResources(system, tmp, llm).flatMap { resources =>
        val memberDef = AgentDef(
          name = "Restored",
          description = "flows-declaring agent",
          tools = List("Read"),
          systemPrompt = "",
          category = "standalone",
          flows = List("dynflow")
        )
        for
          ref <- system.spawn(
            AgentActor(
              agentDef = memberDef,
              resources = resources,
              wsSend = _ => IO.unit,
              depth = 0,
              sessionId = Some(s"ftf-dynsmoke-${UUID.randomUUID().toString.take(6)}"),
              sessionName = Some("Restored")
            ),
            "ftf-dynsmoke-agent"
          )
          _ <- ref ! AgentCommand.UserInput("trigger the dynamic flow")
          // Caller turn (2 requests) + flow nodes (6) + completion delivery (1):
          // wait until the caller saw the flow-completed ImmediateInput (its 3rd request).
          _ <- waitUntil(60.seconds)(requestsRef.get.map(_.size >= 9))
          reqs <- requestsRef.get.map(_.reverse)
          callerMsgs = reqs.filterNot(_.sessionId.startsWith("dag-dynflow-")).flatMap(r =>
            r.messages.flatMap(_.content.toOption.toList.flatten).collect { case ContentBlock.ToolResult(_, c, _) => c }
          )
          // The flow-completion ImmediateInput arrives as a USER message
          // ("[Flow '<name>' completed]…"), not a ToolResult.
          callerUserText = reqs
            .filterNot(_.sessionId.startsWith("dag-dynflow-"))
            .flatMap(r => r.messages.filter(_.role == MessageRole.User).flatMap(m => m.content.fold(t => List(t), bs => bs.collect { case ContentBlock.Text(t) => t })))
            .mkString("\n")
          flowReqs = reqs.filter(_.sessionId.startsWith("dag-dynflow-"))
          userTexts = (r: LlmRequest) =>
            r.messages
              .filter(_.role == MessageRole.User)
              .flatMap(m => m.content.fold(t => List(t), bs => bs.collect { case ContentBlock.Text(t) => t }))
              .mkString("\n")
          joinText = flowReqs.filter(_.sessionId.contains("-j-")).map(userTexts).mkString("\n")
          pText = flowReqs.filter(_.sessionId.contains("-p-")).map(userTexts).mkString("\n")
          instSessions = flowReqs.map(_.sessionId).filter(_.contains("researcher#")).map(_.split("-").apply(2)).toSet
        yield
          // FlowTrigger tool accepted the params and started the flow
          assert(callerMsgs.exists(_.contains("Flow 'dynflow' started")), s"tool result: $callerMsgs")
          // $params.fanout reached the planner input (validated + merged)
          assert(
            pText.contains("fanout=3"),
            s"planner saw params.fanout=3 — pText=[$pText] sessions=[${flowReqs.map(_.sessionId).mkString(" | ")}]"
          )
          // 3 instances ran (slots [A,B,C] → researcher#1..#3)
          assertEquals(instSessions, Set("researcher#1", "researcher#2", "researcher#3"), s"instance sessions: $instSessions")
          // join aggregated all 3 tracks in index order
          assert(joinText.contains("=== Track 1 ===") && joinText.contains("OUT-1"), s"track 1: $joinText")
          assert(joinText.contains("=== Track 2 ===") && joinText.contains("OUT-2"), s"track 2: $joinText")
          assert(joinText.contains("=== Track 3 ===") && joinText.contains("OUT-3"), s"track 3: $joinText")
          // completion delivered back to the caller (ImmediateInput → user message)
          assert(
            callerUserText.contains("[Flow 'dynflow' completed]"),
            s"completion delivered: [$callerUserText]"
          )
      }
    } { _ =>
      IO.delay(PathUtil.setDataRoot(prevRoot)) *>
        system.stopAll.attempt.void *>
        IO.delay(if os.exists(tmp) then os.remove.all(tmp)).attempt.void
    }
end FlowTriggerExecutionSpec
