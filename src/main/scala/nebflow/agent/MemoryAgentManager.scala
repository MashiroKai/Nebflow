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
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}

import java.util.concurrent.atomic.AtomicLong

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

/**
 * Manages the Dream session for memory consolidation and pattern extraction.
 *
 * Event-driven design:
 *   - WriteMemory calls directly send entries to the Dream actor mailbox.
 *   - 24-hour full cycle uses a single-shot timer (rescheduled after each cycle).
 *   - No polling, no staging file, no periodic scanning.
 *
 * The scheduler state machine is delegated to [[DreamScheduler]] for testability.
 * This class provides the hooks (trigger, stop, lifecycle) that the scheduler calls.
 *
 * Mac sleep / network offline: messages wait in the Pekko mailbox until the
 * actor processes them. No repeated work, no wasted CPU.
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
  @volatile private var _schedulerRef: ActorRef[DreamCommand] = uninitialized

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

  // Debounce: wait this long after the first entry before processing the batch
  private val DebounceDelay = 2.minutes

  // Full cycle interval
  private val FullCycleInterval = 24.hours

  // Safety timeout: if Dream agent doesn't signal completion within this duration,
  // force-proceed to avoid blocking forever (agent may have crashed or hung).
  private val DreamTimeoutDuration = 5.minutes

  // Current agent name for file listing
  private def currentAgentName: String =
    try
      val agents = dispatcher.unsafeRunSync(resources.agentLibrary.loadAll())
      agents.keys.headOption.getOrElse("Nebula")
    catch
      case _: Exception =>
        logger.warnSync("Failed to load agent list, defaulting to Nebula")
        "Nebula"

  // ============================================================
  // DreamScheduler.Hooks implementation
  // ============================================================

  private val hooks = new DreamScheduler.Hooks:
    def trigger(entries: List[DreamCommand.ProcessEntry], isFullCycle: Boolean): Boolean =
      triggerDream(entries, isFullCycle)

    def stopDreamAgent(): Unit =
      if _dreamRef != null then _dreamRef ! Stop("cycle-complete")
      _dreamRef = null

    def touchLastDreamTime(): Unit =
      lastDreamTime = System.currentTimeMillis()

  // Spawn Dream scheduler on construction
  locally {
    _schedulerRef = actorSystem.systemActorOf(
      DreamScheduler(hooks, DebounceDelay, FullCycleInterval, DreamTimeoutDuration),
      "memory-dream-scheduler"
    )
  }

  // ============================================================
  // Public API
  // ============================================================

  /** ActorRef of the Dream scheduler — WriteMemoryTool sends entries here. */
  def dreamSchedulerRef: ActorRef[DreamCommand] = _schedulerRef

  /** Stop Dream actor and scheduler. */
  def shutdownAll(): IO[Unit] =
    IO {
      if _dreamRef != null then _dreamRef ! Stop("shutdown")
      if _schedulerRef != null then _schedulerRef ! DreamCommand.Shutdown
      logger.infoSync("Memory Agent Manager: shutdown complete")
    }

  // ============================================================
  // Trigger Dream — spawn agent and send payload
  // ============================================================

  private def triggerDream(entries: List[DreamCommand.ProcessEntry], isFullCycle: Boolean): Boolean =
    // Daily backup check (independent of Dream, but piggyback on full cycle)
    if isFullCycle then
      try
        if NebflowBackup.isNeeded then
          val result = dispatcher.unsafeRunSync(NebflowBackup.run())
          result.foreach { dir =>
            logger.infoSync(s"Daily backup created at ${dir.last}")
          }
      catch
        case e: Exception =>
          logger.warnSync(s"Daily backup failed: ${e.getMessage}")

    try
      val allFiles = collectAllFiles()
      val userInputs =
        if isFullCycle then dispatcher.unsafeRunSync(collectRecentUserInputs)
        else ""
      val payload = buildDreamPayload(entries, allFiles, userInputs, isFullCycle)

      val ref = dispatcher.unsafeRunSync(createDreamAgent())
      _dreamRef = ref
      dispatcher.unsafeRunSync(recordUserMessage(DreamSessionId, payload, injected = true))
      ref ! ExternalEvent(
        source = "memory-manager",
        eventType = "dream",
        payload = payload
      )
      logger.infoSync(
        s"Dream triggered (${if isFullCycle then "full" else "entries"}): ${entries.size} entries, ${allFiles.size} files"
      )
      true
    catch
      case e: Exception =>
        logger.warnSync(s"Dream trigger failed: ${e.getMessage}")
        false
    end try
  end triggerDream

  // ============================================================
  // Agent lifecycle
  // ============================================================

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
      // Intercept Done event to signal Dream completion back to the scheduler.
      // This is how the scheduler knows the Dream agent finished processing
      // and it's safe to start a new cycle (or process buffered entries).
      schedulerRef = _schedulerRef
      wsSendWithDone = (json: Json) =>
        if json.hcursor.downField("type").as[String].toOption.contains("done") then
          schedulerRef ! DreamCommand.DreamComplete
        recorder(json)
      ref <- IO {
        actorSystem.systemActorOf(
          AgentActor(
            agentDef,
            resources,
            wsSend = wsSendWithDone,
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
  // Dream payload builder
  // ============================================================

  private case class FileInfo(path: os.Path, scope: String, changed: Boolean)

  private def buildDreamPayload(
    entries: List[DreamCommand.ProcessEntry],
    files: Seq[FileInfo],
    userInputs: String,
    fullCycle: Boolean
  ): String =
    val fileList = files
      .map { f =>
        val marker = if f.changed then " [CHANGED]" else ""
        s"- ${f.scope}: ${f.path}$marker"
      }
      .mkString("\n")

    val entriesSection =
      if entries.nonEmpty then
        val entryLines = entries.map { e =>
          val hash = e.detail.filter(_.trim.nonEmpty).map(_ => MemoryStore.contentHash(e.content))
          val detailNote = hash.map(h => s" (detail →$h)").getOrElse("")
          s"""{"scope":"${e.scope}","content":"${e.content
              .replace("\"", "\\\"")
              .take(200)}","detail":${e.detail.isDefined},"hash":${hash.getOrElse(
              "null"
            )},"source":"${e.source}","folder":${e.folderId.getOrElse("null")}}$detailNote"""
        }
        s"""|
           |## New observations to process
           |
           |Each entry has: scope, content, detail, hash, source, folder.
           |If hash is present, write the detail to ~/.nebflow/memory/{{hash}}.md.
           |
           |${entryLines.mkString("\n")}""".stripMargin
      else ""

    val inputsSection =
      if fullCycle && userInputs.nonEmpty then s"""|
         |## Recent user inputs (past 24 hours)
         |
         |$userInputs""".stripMargin
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
       |$entriesSection
       |$inputsSection
       |$consolidationSection
       |
       |Task:
       |1. Process ALL new observations: write to correct memory files under ## headings with *(YYYY-MM-DD)* timestamps. Add →hash references if detail exists.
       |2. ${
        if fullCycle then "Run full consolidation and pattern extraction as described above."
        else "Quick cycle — skip consolidation, only process observations."
      }
       |3. Respond DONE when finished.""".stripMargin
  end buildDreamPayload

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
  // User input collection for Dream (full cycle only)
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
