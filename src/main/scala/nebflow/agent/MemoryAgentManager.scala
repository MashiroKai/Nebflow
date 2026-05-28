package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.AgentCommand.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{FileHistory, ReadTracker}
import nebflow.gateway.{SessionRecorder, SessionStore, WsHub}
import nebflow.service.{MemoryStore, NebflowBackup}
import nebflow.shared.UiMessage
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}

import java.util.concurrent.atomic.AtomicLong

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

/**
 * Manages the Dream session for periodic memory consolidation
 * and pattern extraction across all memory files.
 *
 * Dream trigger: any memory file has mtime > lastDreamTime, or recent user inputs exist.
 * Dream scope: lists all files with [CHANGED] markers, agent decides what to read.
 */
class MemoryAgentManager(
  actorSystem: ActorSystem[?],
  dispatcher: Dispatcher[IO],
  sessionStore: SessionStore
):

  private val logger = NebflowLogger.forName("nebflow.memory-manager")
  private val counter = new AtomicLong()
  @volatile private var lastDreamTime: Long = System.currentTimeMillis()
  @volatile private var _resources: SharedResources = uninitialized
  @volatile private var _wsHub: WsHub = uninitialized
  @volatile private var _dreamRef: ActorRef[AgentCommand] = uninitialized

  /** Set SharedResources after construction (breaks circular dependency). */
  def setSharedResources(resources: SharedResources): Unit =
    _resources = resources

  /** Set WsHub after construction (created later in GatewayMain). */
  def setWsHub(hub: WsHub): Unit =
    _wsHub = hub

  private def resources: SharedResources =
    if _resources == null then throw new IllegalStateException("MemoryAgentManager: SharedResources not set yet")
    _resources

  private def wsHub: WsHub =
    if _wsHub == null then throw new IllegalStateException("MemoryAgentManager: WsHub not set yet")
    _wsHub

  // Fixed session ID
  private val DreamSessionId = "memory-agent-dream"
  private val DreamSessionName = "Dream / 梦境"

  // Current agent name for file listing
  private def currentAgentName: String =
    try
      val agents = dispatcher.unsafeRunSync(resources.agentLibrary.loadAll())
      agents.keys.headOption.getOrElse("Nebula")
    catch
      case _: Exception =>
        logger.warnSync("Failed to load agent list, defaulting to Nebula")
        "Nebula"

  // Spawn Dream scheduler on construction
  locally {
    actorSystem.systemActorOf(dreamScheduler(), "memory-dream-scheduler")
  }

  // ============================================================
  // Public API
  // ============================================================

  /** Stop Dream actor. */
  def shutdownAll(): IO[Unit] =
    IO {
      if _dreamRef != null then _dreamRef ! Stop("shutdown")
      logger.infoSync("Memory Agent Manager: shutdown complete")
    }

  // ============================================================
  // Agent lifecycle
  // ============================================================

  private def getOrCreateDreamAgent(): IO[ActorRef[AgentCommand]] =
    if _dreamRef != null then IO.pure(_dreamRef)
    else
      createDreamAgent().map { ref =>
        _dreamRef = ref
        ref
      }

  private def createDreamAgent(): IO[ActorRef[AgentCommand]] =
    for
      agentDefOpt <- resources.agentLibrary.get("MemoryAgent")
      agentDef <- IO.fromOption(agentDefOpt)(
        new RuntimeException("MemoryAgent not found in AgentLibrary")
      )
      _ <- sessionStore.getOrCreateSession(
        DreamSessionId,
        DreamSessionName,
        agentName = Some("MemoryAgent")
      )
      // Fresh session each Dream cycle — don't load old history
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      recorder = SessionRecorder(DreamSessionId, sessionStore, wsHub.broadcast)
      ref <- IO {
        actorSystem.systemActorOf(
          AgentActor(
            agentDef,
            resources,
            wsSend = json => recorder(json),
            depth = 0,
            parentRef = None,
            sessionId = Some(DreamSessionId),
            sessionName = Some(DreamSessionName),
            initialMessages = Nil,
            readTracker = Some(readTracker),
            fileHistory = Some(fileHistory),
            contextWindow = resources.contextWindow,
            projectRoot = Some((os.home / ".nebflow").toString),
            folderId = None
          ),
          s"$DreamSessionId-${counter.incrementAndGet()}"
        )
      }
      _ = logger.infoSync("Spawned Dream MemoryAgent actor")
    yield ref

  // ============================================================
  // User message recording for injected events
  // ============================================================

  private def recordUserMessage(sessionId: String, text: String, injected: Boolean): IO[Unit] =
    val uiMsg = UiMessage.User(text, injected = injected)
    val wsEvent = Json.obj(
      "type" -> "user".asJson,
      "sessionId" -> sessionId.asJson,
      "text" -> text.asJson,
      "injected" -> injected.asJson
    )
    sessionStore.appendUiMessages(sessionId, List(uiMsg)) *> wsHub.broadcast(wsEvent)

  // ============================================================
  // Dream payload
  // ============================================================

  private case class FileInfo(path: os.Path, scope: String, changed: Boolean)

  private def buildDreamPayload(files: Seq[FileInfo], userInputs: String, staging: String, fullCycle: Boolean): String =
    val fileList = files
      .map { f =>
        val marker = if f.changed then " [CHANGED]" else ""
        s"- ${f.scope}: ${f.path}$marker"
      }
      .mkString("\n")

    val inputsSection =
      if fullCycle && userInputs.nonEmpty then s"""|
         |## Recent user inputs (past 24 hours)
         |
         |$userInputs""".stripMargin
      else ""

    val stagingSection =
      if staging.nonEmpty then s"""|
         |## Staging area (new observations to process)
         |
         |Each line is JSON with: ts, scope, content, detail, hash, source, folder.
         |If hash is non-null, write the detail to ~/.nebflow/memory/{{hash}}.md.
         |
         |$staging""".stripMargin
      else ""

    val consolidationSection =
      if fullCycle then """|
        |## Full cycle tasks (24h)
        |- Read [CHANGED] files and consolidate: merge duplicates, prune stale entries, compress verbose entries.
        |- Extract patterns from user inputs (only patterns seen 2-3+ times).
        |""".stripMargin
      else ""

    s"""Dream cycle triggered. Memory files:
       |
       |$fileList
       |$stagingSection
       |$inputsSection
       |$consolidationSection
       |
       |Task:
       |1. Process ALL staging entries: write to correct memory files under ## headings with *(YYYY-MM-DD)* timestamps. Add →hash references if detail exists.
       |2. ${
        if fullCycle then "Run full consolidation and pattern extraction as described above."
        else "Quick cycle — skip consolidation, only process staging."
      }
       |3. After processing, call ClearStaging with confirm=true to delete the staging file.
       |4. Respond DONE when finished.""".stripMargin
  end buildDreamPayload

  // ============================================================
  // Dream scheduler — ticks every 30 min, triggers all-layer Dream
  // ============================================================

  private sealed trait DreamTick
  private case object Tick extends DreamTick

  private def dreamScheduler(): Behavior[DreamTick] =
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(Tick, 5.minutes)
      Behaviors.receiveMessage { case Tick =>
        // Daily backup check (independent of Dream)
        try
          if NebflowBackup.isNeeded then
            val result = dispatcher.unsafeRunSync(NebflowBackup.run())
            result.foreach { dir =>
              logger.infoSync(s"Daily backup created at ${dir.last}")
            }
        catch
          case e: Exception =>
            logger.warnSync(s"Daily backup failed: ${e.getMessage}")

        // Dream cycle — check every tick (5 min)
        val now = System.currentTimeMillis()
        val hasStaging =
          val s = dispatcher.unsafeRunSync(MemoryStore.loadStaging)
          s.nonEmpty
        // Trigger if: staging has entries (process immediately), or 24h passed for consolidation
        val shouldTrigger = hasStaging || now - lastDreamTime >= 24.hours.toMillis
        if shouldTrigger then
          try
            val allFiles = collectAllFiles()
            val changedFiles = allFiles.filter(_.changed)
            val userInputs = dispatcher.unsafeRunSync(collectRecentUserInputs)
            val staging = dispatcher.unsafeRunSync(MemoryStore.loadStaging)
            val fullCycle = now - lastDreamTime >= 24.hours.toMillis

            if changedFiles.nonEmpty || userInputs.nonEmpty || staging.nonEmpty then
              val payload = buildDreamPayload(allFiles, userInputs, staging, fullCycle)
              // Stop previous Dream actor before creating a new one
              val oldRef = _dreamRef
              if oldRef != null then oldRef ! Stop("new-cycle")
              _dreamRef = null
              val ref = dispatcher.unsafeRunSync(getOrCreateDreamAgent())
              dispatcher.unsafeRunSync(recordUserMessage(DreamSessionId, payload, injected = true))
              ref ! ExternalEvent(
                source = "memory-manager",
                eventType = "dream",
                payload = payload
              )
              // Only update lastDreamTime on full cycles (24h)
              if fullCycle then lastDreamTime = now
              logger.infoSync(
                s"Dream triggered (${if fullCycle then "full" else "staging-only"}): ${changedFiles.size} changed, ${staging.linesIterator.size} staged, ${allFiles.size} total"
              )
            else logger.infoSync("Dream skipped — no changed files, no staging, no recent user inputs")
            end if
          catch
            case e: Exception =>
              logger.warnSync(s"Dream failed: ${e.getMessage}")
        end if
        Behaviors.same
      }
    }

  // ============================================================
  // File collection
  // ============================================================

  private def collectAllFiles(): Seq[FileInfo] =
    val agentName = currentAgentName
    val all = Seq(
      FileInfo(MemoryStore.userMemoryPath, "User", false),
      FileInfo(MemoryStore.agentMemoryPath(agentName), "Agent", false)
    ) ++ MemoryStore.allFolderMemoryPaths.map(p => FileInfo(p, "Folder", false))

    all.map { f =>
      val changed = os.exists(f.path) && {
        val mtime = os.stat(f.path).mtime.toMillis
        mtime > lastDreamTime
      }
      f.copy(changed = changed)
    }

  // ============================================================
  // User input collection for Dream
  // ============================================================

  private val inputHistoryPath: os.Path = os.home / ".nebflow" / "input_history.jsonl"

  private def collectRecentUserInputs: IO[String] = IO.blocking {
    if !os.exists(inputHistoryPath) then ""
    else
      import java.time.*
      import java.time.format.DateTimeFormatter
      val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
      val cutoff = LocalDateTime.now().minusHours(24)
      val lines = os.read.lines(inputHistoryPath).toList
      val recent = lines.flatMap { line =>
        io.circe.parser.parse(line).toOption.flatMap { json =>
          val tsStr = json.hcursor.downField("ts").as[String].toOption.getOrElse("")
          val text = json.hcursor.downField("text").as[String].toOption.getOrElse("")
          val inputType = json.hcursor.downField("type").as[String].toOption.getOrElse("input")
          val ts =
            try Some(LocalDateTime.parse(tsStr, fmt))
            catch case _: Exception => None
          ts.filter(_.isAfter(cutoff)).map(_ => s"[$inputType] $text")
        }
      }
      if recent.isEmpty then ""
      else recent.mkString("\n")
  }

end MemoryAgentManager
