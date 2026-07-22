package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger

// ============================================================
// FlowTreeActor — session-level supervisor for pipeline actors
// ============================================================
// Responsibilities:
//   1. Mount/unmount: read YAML → spawn PipelineActor
//   2. Supervise: child crash → restart (pipelines are persistent)
//   3. Restore: on startup, recreate pipelines from PipelineStore
//   4. Hot reload: YAML changes → recreate pipeline actor
//   5. Trigger: forward trigger requests to PipelineActor
//
// Does NOT hold any pipeline run state — each PipelineActor owns its own.

object FlowTreeActor:
  private val logger = NebflowLogger(getClass)

  // ============================================================
  // Config
  // ============================================================

  case class TreeConfig(
    parentAgentRef: ActorRef[AgentCommand],
    wsSend: Option[Json => IO[Unit]],
    sessionId: Option[String],
    resources: SharedResources,
    projectRoot: String,
    safetyMode: String,
    gatewayPort: Int = 8080,
    disableFileWatcher: Boolean = false
  )

  // ============================================================
  // Factory
  // ============================================================

  def apply(config: TreeConfig): Behavior[TreeCommand] =
    Behaviors.setup { ctx =>
      given ActorContext[TreeCommand] = ctx

      val pipelinesRef = Ref.unsafe[IO, Map[String, ActorRef[PipelineActor.PipelineCommand]]](Map.empty)
      val flowNamesRef = Ref.unsafe[IO, Map[String, String]](Map.empty) // instanceName → flowName

      for
        _ <- logger.info(s"FlowTreeActor started for session ${config.sessionId}")
        _ <- restorePipelines(ctx, pipelinesRef, flowNamesRef, config)
        _ <- if !config.disableFileWatcher then startFileWatcher(ctx, config) else IO.unit
      yield running(ctx, pipelinesRef, flowNamesRef, config)
    }

  // ============================================================
  // Running behavior
  // ============================================================

  private def running(
    ctx: ActorContext[TreeCommand],
    pipelinesRef: Ref[IO, Map[String, ActorRef[PipelineActor.PipelineCommand]]],
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig
  ): Behavior[TreeCommand] =

    new Behavior[TreeCommand]:
      override def onError(ctx: ActorContext[TreeCommand], err: Throwable): IO[Behavior[TreeCommand]] =
        logger
          .error(
            s"FlowTreeActor error: ${err.getMessage}\n${err.getStackTrace.take(10).map(_.toString).mkString("\n")}"
          )
          .as(this)

      def receive(ctx: ActorContext[TreeCommand], msg: TreeCommand): IO[Behavior[TreeCommand]] =
        given ActorContext[TreeCommand] = ctx
        msg match
          case TreeCommand.MountBranch(defn, instanceName, replyTo) =>
            handleMount(ctx, pipelinesRef, flowNamesRef, cfg, defn, instanceName, replyTo).as(this)

          case TreeCommand.UnmountBranch(name) =>
            handleUnmount(ctx, pipelinesRef, flowNamesRef, cfg, name).as(this)

          case TreeCommand.TriggerPipeline(name, input, replyTo) =>
            handleTrigger(ctx, pipelinesRef, cfg, name, input, replyTo).as(this)

          case TreeCommand.ReloadDefinition(flowName) =>
            handleReload(ctx, pipelinesRef, flowNamesRef, cfg, flowName).as(this)

          case TreeCommand.Shutdown =>
            for
              pipelines <- pipelinesRef.get
              _ <- pipelines.values.toList.traverse_(ref => ctx.system.stop(ref))
              _ <- logger.info("FlowTreeActor shutdown complete")
            yield Behaviors.stopped[TreeCommand]
        end match

      end receive

      override def onStop(ctx: ActorContext[TreeCommand]): IO[Unit] =
        for
          pipelines <- pipelinesRef.get
          _ <- pipelines.values.toList.traverse_(ref =>
            ctx.system.stop(ref) *> FlowMembership.leaveAll(ref.path.toString)
          )
        yield ()
  end running

  // ============================================================
  // Mount / Unmount
  // ============================================================

  private def handleMount(
    ctx: ActorContext[TreeCommand],
    pipelinesRef: Ref[IO, Map[String, ActorRef[PipelineActor.PipelineCommand]]],
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    defn: FlowDef,
    instanceName: Option[String],
    replyTo: Option[ActorRef[MountResult]]
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      pipelines <- pipelinesRef.get
      baseName = instanceName.getOrElse(defn.name)
      name = makeUniqueName(pipelines.keys.toSet, baseName)
      memory <- FlowMemoryStore.load(defn.name)
      pipeConfig = PipelineActor.PipelineConfig(
        name = name,
        flowName = defn.name,
        flowDef = defn,
        parentAgentRef = cfg.parentAgentRef,
        wsSend = cfg.wsSend,
        sessionId = cfg.sessionId,
        resources = cfg.resources,
        projectRoot = cfg.projectRoot,
        safetyMode = cfg.safetyMode,
        flowMemory = memory,
        gatewayPort = cfg.gatewayPort
      )
      ref <- ctx.system.spawn(
        PipelineActor(pipeConfig),
        s"pipeline-$name-${java.util.UUID.randomUUID().toString.take(8)}"
      )
      _ <- pipelinesRef.update(_ + (name -> ref))
      _ <- flowNamesRef.update(_ + (name -> defn.name))
      _ <- FlowMembership.join(cfg.parentAgentRef.path.toString, name)
      _ <- persistPipelines(pipelinesRef, flowNamesRef, cfg)
      _ <- emit(cfg, "treeBranchMounted", "name" -> name.asJson, "type" -> "pipeline".asJson)
      _ <- replyTo.traverse_(_ ! MountResult.Mounted(name, ref.path.toString))
      _ <- logger.info(s"Pipeline '$name' mounted (flow: ${defn.name})")
    yield ()

  private def handleUnmount(
    ctx: ActorContext[TreeCommand],
    pipelinesRef: Ref[IO, Map[String, ActorRef[PipelineActor.PipelineCommand]]],
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    name: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      pipelines <- pipelinesRef.get
      _ <- pipelines.get(name) match
        case Some(ref) =>
          for
            _ <- ctx.system.stop(ref)
            _ <- FlowMembership.leaveAll(ref.path.toString)
            _ <- pipelinesRef.update(_ - name)
            _ <- flowNamesRef.update(_ - name)
            _ <- FlowMembership.leave(cfg.parentAgentRef.path.toString, name)
            _ <- persistPipelines(pipelinesRef, flowNamesRef, cfg)
            _ <- cfg.sessionId.traverse_(sid => PipelineStateStore.delete(sid, name))
            _ <- emit(cfg, "treeBranchUnmounted", "name" -> name.asJson)
            _ <- logger.info(s"Pipeline '$name' unmounted")
          yield ()
        case None =>
          logger.warn(s"Cannot unmount '$name': not found")
    yield ()

  // ============================================================
  // Trigger — forward to PipelineActor
  // ============================================================

  private def handleTrigger(
    ctx: ActorContext[TreeCommand],
    pipelinesRef: Ref[IO, Map[String, ActorRef[PipelineActor.PipelineCommand]]],
    cfg: TreeConfig,
    name: String,
    input: String,
    replyTo: Option[ActorRef[PipelineActor.PipelineEvent]]
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      pipelines <- pipelinesRef.get
      _ <- pipelines.get(name) match
        case Some(ref) =>
          for
            _ <- ref ! PipelineActor.PipelineCommand.Trigger(input, replyTo)
            _ <- logger.info(s"Trigger forwarded to pipeline '$name'")
          yield ()
        case None =>
          for
            _ <- replyTo.traverse_(_ ! PipelineActor.PipelineEvent.Failed(s"Pipeline '$name' not found"))
            _ <- logger.warn(s"Cannot trigger '$name': not found")
          yield ()
    yield ()

  // ============================================================
  // Restore from PipelineStore on startup
  // ============================================================

  private def restorePipelines(
    ctx: ActorContext[TreeCommand],
    pipelinesRef: Ref[IO, Map[String, ActorRef[PipelineActor.PipelineCommand]]],
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig
  ): IO[Unit] =
    cfg.sessionId match
      case Some(sid) if sid.nonEmpty =>
        for
          entries <- PipelineStore.load(sid)
          _ <- entries.traverse_ { entry =>
            FlowDefLoader.load(entry.flowName).flatMap {
              case Some(defn) =>
                for
                  memory <- FlowMemoryStore.load(entry.flowName)
                  pipeConfig = PipelineActor.PipelineConfig(
                    name = entry.name,
                    flowName = entry.flowName,
                    flowDef = defn,
                    parentAgentRef = cfg.parentAgentRef,
                    wsSend = cfg.wsSend,
                    sessionId = cfg.sessionId,
                    resources = cfg.resources,
                    projectRoot = cfg.projectRoot,
                    safetyMode = cfg.safetyMode,
                    flowMemory = memory,
                    gatewayPort = cfg.gatewayPort
                  )
                  ref <- ctx.system.spawn(
                    PipelineActor(pipeConfig),
                    s"pipeline-${entry.name}-${java.util.UUID.randomUUID().toString.take(8)}"
                  )
                  _ <- pipelinesRef.update(_ + (entry.name -> ref))
                  _ <- flowNamesRef.update(_ + (entry.name -> entry.flowName))
                  _ <- FlowMembership.join(cfg.parentAgentRef.path.toString, entry.name)
                  _ <- logger.info(s"Restored pipeline '${entry.name}' (flow: ${entry.flowName})")
                yield ()
              case None =>
                logger.warn(s"Restore: flow '${entry.flowName}' not found for pipeline '${entry.name}'")
            }
          }
          _ <- if entries.nonEmpty then logger.info(s"Restored ${entries.size} pipeline(s)") else IO.unit
        yield ()
      case _ => IO.unit

  // ============================================================
  // Hot reload — file watcher
  // ============================================================

  private def startFileWatcher(ctx: ActorContext[TreeCommand], cfg: TreeConfig): IO[Unit] =
    val flowsDir = (nebflow.core.PathUtil.dataRoot / "flows").toIO.toPath
    ctx.forkTurn(
      IO.blocking {
        if !java.nio.file.Files.exists(flowsDir) then java.nio.file.Files.createDirectories(flowsDir)
        val watcher = java.nio.file.FileSystems.getDefault.newWatchService()
        flowsDir.register(
          watcher,
          java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
          java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
        )
        while true do
          val key = watcher.take()
          val events = key.pollEvents()
          events.forEach { event =>
            val fileName = event.context().toString
            if fileName.endsWith(".yaml") then
              val flowName = fileName.stripSuffix(".yaml")
              ctx.self ! TreeCommand.ReloadDefinition(flowName)
            else if fileName.endsWith(".memory.md") then
              val flowName = fileName.stripSuffix(".memory.md")
              ctx.self ! TreeCommand.ReloadDefinition(flowName)
          }
          key.reset()
      }.void
        .handleErrorWith(e => logger.warn(s"File watcher error: ${e.getMessage}").void)
    )

  end startFileWatcher

  private def handleReload(
    ctx: ActorContext[TreeCommand],
    pipelinesRef: Ref[IO, Map[String, ActorRef[PipelineActor.PipelineCommand]]],
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    flowName: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      newDefOpt <- FlowDefLoader.load(flowName)
      _ <- newDefOpt match
        case None => logger.warn(s"Hot reload: '$flowName' not found or invalid")
        case Some(newDef) =>
          for
            names <- flowNamesRef.get.map(_.collect { case (name, fn) if fn == flowName => name }.toList)
            _ <- names.traverse_ { name =>
              for
                oldRef <- pipelinesRef.get.map(_(name))
                _ <- ctx.system.stop(oldRef)
                _ <- FlowMembership.leaveAll(oldRef.path.toString)
                memory <- FlowMemoryStore.load(flowName)
                pipeConfig = PipelineActor.PipelineConfig(
                  name = name,
                  flowName = flowName,
                  flowDef = newDef,
                  parentAgentRef = cfg.parentAgentRef,
                  wsSend = cfg.wsSend,
                  sessionId = cfg.sessionId,
                  resources = cfg.resources,
                  projectRoot = cfg.projectRoot,
                  safetyMode = cfg.safetyMode,
                  flowMemory = memory,
                  gatewayPort = cfg.gatewayPort
                )
                newRef <- ctx.system.spawn(
                  PipelineActor(pipeConfig),
                  s"pipeline-$name-${java.util.UUID.randomUUID().toString.take(8)}"
                )
                _ <- pipelinesRef.update(_ + (name -> newRef))
                _ <- emit(cfg, "treeBranchUpdated", "name" -> name.asJson, "flowName" -> flowName.asJson)
                _ <- logger.info(s"Hot reload: '$name' reloaded from '$flowName'")
              yield ()
            }
          yield ()
    yield ()

  // ============================================================
  // Helpers
  // ============================================================

  private def persistPipelines(
    pipelinesRef: Ref[IO, Map[String, ActorRef[PipelineActor.PipelineCommand]]],
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig
  ): IO[Unit] =
    cfg.sessionId match
      case Some(sid) if sid.nonEmpty =>
        for
          flowNames <- flowNamesRef.get
          entries = flowNames.map { case (name, flowName) =>
            PipelineStore.PipelineEntry(name, flowName)
          }.toList
          _ <- PipelineStore.save(sid, entries)
        yield ()
      case _ => IO.unit

  private def makeUniqueName(existing: Set[String], base: String): String =
    if !existing.contains(base) then base
    else
      var i = 2
      while existing.contains(s"$base-$i") do i += 1
      s"$base-$i"

  private def emit(
    cfg: TreeConfig,
    eventName: String,
    fields: (String, Json)*
  )(using ctx: ActorContext[?]): IO[Unit] =
    ctx.forkTurn(
      cfg.wsSend match
        case Some(send) =>
          val all = ("type", eventName.asJson) :: ("sessionId", cfg.sessionId.getOrElse("").asJson) :: fields.toList
          val json = Json.fromJsonObject(JsonObject.fromIterable(all))
          send(json)
        case None => IO.unit
    )

end FlowTreeActor
