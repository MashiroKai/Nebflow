package nebflow.core.project

import cats.effect.{Deferred, ExitCode, IO, IOApp, Ref, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.db.DbHost
import nebflow.llm.ModelCandidate
import nebflow.shared.*

import scala.concurrent.duration.*

/** Disposable integration driver; never loads gateway configuration or host state. */
object E2eFixtureMain extends IOApp:
  private val markerA = "E01-MARKER-A"
  private val markerB = "E01-MARKER-B"

  private def requireFixture(condition: Boolean, message: String): IO[Unit] =
    IO.raiseUnless(condition)(new IllegalStateException(message))

  private def resources(root: os.Path, system: ActorSystem, llm: LlmHandle[IO]): Resource[IO, SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO]
      limiter <- Resource.eval(RateLimiter.create())
      tracker <- Resource.eval(FileChangeTracker.create(root.toString))
      locks <- Resource.eval(FileLockManager.create)
      thinking <- Resource.eval(Ref.of[IO, ThinkingConfig](ThinkingConfig()))
      overrides <- Resource.eval(Ref.of[IO, Map[String, ModelCandidate]](Map.empty))
      muted <- Resource.eval(Ref.of[IO, Boolean](false))
    yield SharedResources(
      llm = llm, dispatcher = dispatcher,
      sessionStore = SessionStore(root / "sessions", root / "tasks"), projectRoot = root,
      thinkingConfigRef = thinking, rateLimiter = limiter, fileChangeTracker = tracker,
      contextWindow = 100000, agentLibrary = new AgentLibrary(root / "agents"), taskStore = FileTaskStore,
      historyArchiver = null, fileLockManager = locks, sessionModelOverrides = overrides,
      providerRegistry = null, healthMonitor = null, actorSystem = system, voiceMutedRef = muted
    )

  private def e01(request: Json, root: os.Path, evidence: os.Path): IO[Json] =
    for
      entered <- Ref.of[IO, Set[String]](Set.empty)
      bothEntered <- Deferred[IO, Unit]
      parentCompleted <- Deferred[IO, Unit]
      observed <- Ref.of[IO, Vector[(String, String)]](Vector.empty)
      requests <- Ref.of[IO, Vector[String]](Vector.empty)
      started <- IO.monotonic
      system = ActorSystem("e2e-fixture")
      llm = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new IllegalStateException("streaming required"))
        def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
          val input = req.messages.map(_.textContent).mkString("\n")
          val isParent = input.contains("FIXTURE-TASK-PARENT")
          val admission = IO.blocking(os.write.over(evidence / s"fixture-input-${req.sessionId}.txt", input)) *> requests.update(_ :+ req.sessionId) *>
            (if isParent then requireFixture(input.contains(markerA) && input.contains(markerB), "parent missing upstream markers")
             else entered.modify { ids =>
               val next = ids + req.sessionId
               (next, next.size == 2)
             }.flatMap(full => if full then bothEntered.complete(()).void else IO.unit) *> bothEntered.get)
          val output = if isParent then s"$markerA\n$markerB" else if input.linesIterator.exists(_ == "FIXTURE-TASK-A") then markerA else markerB
          Stream.eval(admission).drain ++ Stream(StreamChunk.TextDelta(output), StreamChunk.Done(None, None))
      result <- Resource.make(IO.pure(system))(_.stopAll).flatMap(s => resources(root, s, llm)).use { res =>
        for
          store <- FlowMapStore.open("e2e-fixture", root.toString)
          engine = new NodeEngine(store, system, res, _ => IO.unit, root.toString, "fixture-root", "e2e-fixture",
            (kind, id, _) => observed.update(_ :+ (kind -> id)) *>
              (if kind == "nodeCompleted" && id == "parent" then parentCompleted.complete(()).void else IO.unit),
            destroyWindowMs = Some(0L), rootNotifyQuietMs = Some(0L))
          _ <- store.mutate { state =>
            val a = NodeDef(id = "a", name = "a", agent = "general", createdAt = 0L, task = Some("FIXTURE-TASK-A"), out = List(OutEdge("parent")))
            val b = NodeDef(id = "b", name = "b", agent = "general", createdAt = 0L, task = Some("FIXTURE-TASK-B"), out = List(OutEdge("parent")))
            val parent = NodeDef(id = "parent", name = "parent", agent = "general", createdAt = 0L, task = Some("FIXTURE-TASK-PARENT"), in = List("a", "b"))
            state.copy(nodes = Map("a" -> a, "b" -> b, "parent" -> parent))
          }
          _ <- (engine.startNode("a"), engine.startNode("b")).parTupled.timeout(30.seconds)
          _ <- parentCompleted.get.timeout(30.seconds)
          // Completion event precedes node finalizers; drain detached start fibers,
          // including run-table and MCP finalizers, before inspecting resources.
          _ <- engine.awaitDetachedStarts.timeout(30.seconds)
          before <- observed.get
          replayAccepted <- engine.completeNodeR("a", markerA)
          after <- observed.get
          reloaded <- FlowMapStore.open("e2e-fixture", root.toString)
          nodes <- reloaded.combinedNodes
          ids <- requests.get
          concurrent <- entered.get
          artifact = nodes.get("parent").flatMap(_.result).getOrElse("")
          _ <- IO.blocking(os.write.over(evidence / "parent-artifact.txt", artifact))
          artifactHash <- IO.blocking(java.security.MessageDigest.getInstance("SHA-256")
            .digest(artifact.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString)
          assertions = List(
            nodes.get("a").exists(_.status == NodeLifecycle.Completed) && nodes.get("b").exists(_.status == NodeLifecycle.Completed) && concurrent.size == 2,
            artifact.contains(markerA) && artifact.contains(markerB),
            !replayAccepted && after.count(_ == ("nodeCompleted" -> "a")) == 1
          )
          checks = request.hcursor.downField("scenario").get[List[String]]("expected").toOption.get.zip(assertions)
          _ <- IO.blocking(os.write.over(evidence / "observations.json", Json.obj(
            "concurrent_child_count" -> concurrent.size.asJson, "request_ids" -> ids.asJson,
            "durable_statuses" -> nodes.view.mapValues(_.status).toMap.asJson,
            "events_before_replay" -> before.asJson, "events_after_replay" -> after.asJson,
            "duplicate_accepted" -> replayAccepted.asJson, "parent_artifact_sha256" -> artifactHash.asJson
          ).spaces2))
        yield (checks, ids)
      }
      (checks, ids) = result
      alive <- ids.distinct.traverse(id => system.isAlive(nebflow.actor.ActorPath(system.localDevice, id)))
      dbWasOpen <- IO.blocking(DbHost.isOpen(root.toNIO))
      dbRelease <- IO.blocking(DbHost.release(root.toNIO)).attempt
      dbStillOpen <- IO.blocking(DbHost.isOpen(root.toNIO))
      // Evidence is outside the private data root; delete only this fixture-owned tree.
      rootDelete <- (if dbRelease.isRight && !dbStillOpen then IO.blocking(os.remove.all(root)).attempt
                     else IO.pure(Left(new IllegalStateException("database not safely released"))))
      rootStillExists <- IO.blocking(os.exists(root))
      finished <- IO.monotonic
      _ <- requireFixture(!alive.contains(true), "fixture actor remains after release")
      pass = checks.forall(_._2)
    yield Json.obj(
      "scenario_id" -> "E01".asJson, "status" -> (if pass then "PASS" else "FAIL").asJson,
      "assertions" -> checks.map { case (expected, passed) => Json.obj("expected" -> expected.asJson, "passed" -> passed.asJson) }.asJson,
      // Listener and OS-handle inventories are not implemented; unknown is not zero.
      "resources_remaining" -> Json.Null,
      "telemetry" -> Json.obj("model_mode" -> "scripted-no-billing-counters".asJson,
        "e2e_latency_ms" -> (finished - started).toMillis.asJson,
        "tasks_total" -> 2.asJson, "tasks_completed" -> (if checks.take(2).forall(_._2) then 2 else 0).asJson,
        "input_tokens" -> Json.Null, "output_tokens" -> Json.Null, "cached_tokens" -> Json.Null,
        "cost_usd" -> Json.Null, "tool_calls" -> Json.Null, "tool_errors" -> Json.Null),
      "cleanup" -> Json.obj("actor_system_closed" -> true.asJson, "dispatcher_released" -> true.asJson,
        "detached_starts_drained" -> true.asJson, "db_was_open" -> dbWasOpen.asJson,
        "db_still_registered" -> dbStillOpen.asJson,
        "data_root_created" -> true.asJson,
        "data_root_exists" -> rootStillExists.asJson,
        "data_root_delete_error" -> rootDelete.swap.toOption.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}").asJson,
        "db_release_error" -> dbRelease.swap.toOption.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}").asJson,
        "known_actor_count" -> ids.distinct.size.asJson)
    )

  private def armWatchdog(): IO[Unit] = IO {
    val budget = sys.env.get("E2E_WATCHDOG_MS").flatMap(_.toLongOption)
      .filter(ms => ms > 0 && ms <= 900000)
      .getOrElse(throw new IllegalStateException("valid E2E_WATCHDOG_MS required"))
    val watchdog = new Thread(() => {
      val timer = new java.util.concurrent.CountDownLatch(1)
      if !timer.await(budget, java.util.concurrent.TimeUnit.MILLISECONDS) then
        System.err.println("E2E_WATCHDOG_EXPIRED")
        System.exit(124)
    }, "e2e-self-watchdog")
    watchdog.setDaemon(true)
    watchdog.start()
  }

  def run(args: List[String]): IO[ExitCode] =
    val run = for
      _ <- armWatchdog()
      _ <- if args == List("--watchdog-self-test") then IO.never[Unit] else IO.unit
      _ <- requireFixture(args.size == 2 && args.head == "--request", "expected --request PATH")
      request <- IO.blocking(parse(os.read(os.Path(args(1)))).toOption.get)
      root <- IO.fromOption(sys.env.get("STAND_HOME"))(new IllegalStateException("private STAND_HOME required")).map(os.Path(_))
      privateHome = os.Path(System.getProperty("user.home"))
      _ <- requireFixture(root.startsWith(privateHome) && root != privateHome, "data root must be below private JVM home")
      _ <- requireFixture(!os.exists(root), "fresh fixture root required")
      _ <- requireFixture(request.hcursor.get[String]("suite").contains("functional"), "performance fixture not implemented")
      _ <- requireFixture(request.hcursor.downField("scenario").get[String]("id").contains("E01"), "scenario not implemented")
      resultPath = os.Path(request.hcursor.get[String]("result").toOption.get)
      evidence = resultPath / os.up
      _ <- IO.blocking {
        PathUtil.setDataRoot(root)
        os.makeDir.all(root / "agents" / "general")
        os.write.over(root / Branding.configFileName, "{}")
        os.write.over(root / "agents" / "general" / "agent.json", """{"name":"general","description":"fixture","tools":[],"category":"standalone"}""")
        os.write.over(root / "agents" / "general" / "system.md", "Return the public fixture marker.")
      }
      result <- e01(request, root, evidence)
      _ <- IO.blocking(os.write.over(resultPath, result.spaces2))
    yield if result.hcursor.get[String]("status").contains("PASS") then ExitCode.Success else ExitCode.Error
    run.handleErrorWith { error => IO.println(s"E2E FIXTURE FAILED: ${error.getClass.getSimpleName}: ${error.getMessage}").as(ExitCode.Error) }
