package nebflow.core.flow

import cats.effect.{IO, Ref}
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.core.tools.ToolContext
import nebflow.shared.NebflowLogger

/**
 * Global registry mapping session IDs to their FlowTreeActor.
 *
 * 团队功能删除批（team-feature-deletion-20261104）阶段 2：
 *  - 「恢复等待腿」整体删除（原 restoreDone / restoreStarted /
 *    markRestoreStarted / signalRestoreComplete / awaitRestore /
 *    awaitRestoreUsing）—— 它的唯一消费者是 GET /api/teams/mounted（同批删除），
 *    生产者是 FlowTreeActor setup，删后无悬空；
 *  - 惰性建树入口 `getOrCreate` 仍在原位（阶段 2 不删——它的调用方 Load 工具的
 *    team 分支已摘，但类型本体与注册表一起留到阶段 3 处置）。
 * 判定与依据见 reports-evidence/team-feature-deletion/stage2/README.md §B。
 */
object FlowTreeRegistry:
  private val trees = Ref.unsafe[IO, Map[String, ActorRef[TreeCommand]]](Map.empty)
  private val logger = NebflowLogger.forName("nebflow.flow.registry")

  // `treesRef` 曾在此：整张 树 map 的裸访问器，阶段 2 的唯一消费者是
  // POST /api/team/rules 的写后热重载腿（已删），删后零调用点 ⇒ 一并移除。

  def register(sessionId: String, ref: ActorRef[TreeCommand]): IO[Unit] =
    trees.update(_ + (sessionId -> ref))

  def get(sessionId: String): IO[Option[ActorRef[TreeCommand]]] =
    trees.get.map(_.get(sessionId))

  def unregister(sessionId: String): IO[Unit] =
    trees.update(_ - sessionId)

  def clear: IO[Unit] = trees.set(Map.empty)

  /** Get an existing FlowTreeActor for the session, or create one if needed. */
  def getOrCreate(ctx: ToolContext): IO[ActorRef[TreeCommand]] =
    val sessionId = ctx.sessionId.getOrElse("default")

    get(sessionId).flatMap {
      case Some(ref) => IO.pure(ref)
      case None =>
        (ctx.actorSystem, ctx.sharedResources, ctx.agentActorRef) match
          case (Some(system), Some(resources), Some(parentRef)) =>
            // permshield S1（2026-09-13）：建树快照的档位走**唯一入口** = 应用级全局
            // 持久值（不再读 `store.getSafetyMode` 的盘上遗留值，也无会话覆盖面）；
            // `TreeConfig.safetyMode` 因此是**展示用快照**，非权威。
            val safetyModeIO = resources.effectiveSafetyMode
              .map(nebflow.core.SafetyMode.toString)

            safetyModeIO.flatMap { safetyMode =>
              // Compute projectsDir from folderId
              val projectsDir = (ctx.sessionStore, ctx.folderId) match
                case (Some(store), Some(fid)) =>
                  val folderName = store.getFolderName(fid).getOrElse(fid.take(8))
                  Some((nebflow.shared.PathUtil.dataRoot / "projects" / folderName).toString)
                case _ => None

              val config = FlowTreeActor.TreeConfig(
                parentAgentRef = parentRef,
                wsSend = ctx.wsSend,
                sessionId = ctx.sessionId,
                resources = resources,
                projectRoot = ctx.projectRoot,
                safetyMode = safetyMode,
                folderId = ctx.folderId,
                projectsDir = projectsDir
              )
              for
                ref <- system.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")
                _ <- register(sessionId, ref)
                _ <- logger.info(s"Created FlowTreeActor for session $sessionId")
              yield ref
            }
          case _ =>
            IO.raiseError(
              new RuntimeException(
                "FlowTreeRegistry.getOrCreate requires ActorSystem, SharedResources, and agentActorRef"
              )
            )
    }
  end getOrCreate

end FlowTreeRegistry
