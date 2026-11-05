package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.actor.*
import nebflow.core.AgentRuntimePort
import nebflow.shared.NebflowLogger

// 团队功能删除批（team-feature-deletion-20261104）阶段 3 · 第一步「先拆文件」：
// `object TeamSessionRegistry` 原先与 `FlowTreeActor` 同处 `FlowTreeActor.scala`
// （原 `:36-278`），两者职责纠缠（会话生命周期 × 团队挂载 → × 团队会话寻址底座）。
// 作者裁定「先拆文件，再删团队那半」——**本步是纯搬移**：对象字节内容、成员顺序、
// 方法体、注释一字未改（程序化逐行比对 = 242/242 全等），只换了宿主文件
// （同包 ⇒ 调用方零改动、无需改任何 import）。第二步「删团队那半」见
// reports-evidence/team-feature-deletion/stage3/README.md。
/**
 * Global registry for team session lookups (replaces FlowMembership).
 * Tracks (instance, agent) → sessionId and running actors.
 */
object TeamSessionRegistry:
  private val logger = NebflowLogger.forName("nebflow.flow.registry")
  // (instanceName, agentName) → sessionId
  private val sessionMap = Ref.unsafe[IO, Map[(String, String), String]](Map.empty)
  // sessionId → ActorRef
  private val actorMap = Ref.unsafe[IO, Map[String, ActorRef[AgentCommand]]](Map.empty)
  // instanceName → manager sessionId
  private val managerMap = Ref.unsafe[IO, Map[String, String]](Map.empty)
  // instanceName → parent sessionId
  private val parentSessionMap = Ref.unsafe[IO, Map[String, String]](Map.empty)
  // sessionId → parent ActorRef
  private val parentActorMap = Ref.unsafe[IO, Map[String, ActorRef[AgentCommand]]](Map.empty)
  // sessionId → busy state
  private val busyMap = Ref.unsafe[IO, Set[String]](Set.empty)

  def registerSession(instance: String, agent: String, sid: String): IO[Unit] =
    sessionMap.update(_ + ((instance, agent) -> sid))

  /** Reset all registries (tests). */
  def clear: IO[Unit] =
    sessionMap.set(Map.empty) *> actorMap.set(Map.empty) *> managerMap.set(Map.empty) *>
      parentSessionMap.set(Map.empty) *> parentActorMap.set(Map.empty) *> busyMap.set(Set.empty)

  def registerActor(sid: String, ref: ActorRef[AgentCommand]): IO[Unit] =
    actorMap.update(_ + (sid -> ref))

  /**
   * P1: dual-write into the unified AgentRegistry so interaction answers
   * (permissionAnswer/askUserAnswer) route to Mail-activated team agents.
   * rootSessionId anchors the permission-policy bucket (P2 inheritance chain).
   * P3 removes this overload and the actorMap itself.
   *
   * Block 0 registration chain (supervision trio §B2): also stamps
   * parentSessionId — a member's parent is its team Manager, the Manager's
   * parent is the mounting root (parentForRecord). Fallback = rootSessionId
   * so the chain is never empty for team records.
   */
  def registerActor(
    sid: String,
    ref: ActorRef[AgentCommand],
    resources: AgentRuntimePort,
    rootSessionId: String
  ): IO[Unit] =
    actorMap.update(_ + (sid -> ref)) *>
      parentForRecord(sid).flatMap { parentOpt =>
        resources.agentRegistry.update(
          _ + (sid ->
            AgentRecord(sid, ref, AgentKind.Team, rootSessionId, parentSessionId = parentOpt.getOrElse(rootSessionId)))
        )
      }

  def unregisterActor(sid: String): IO[Unit] =
    actorMap.update(_ - sid)

  /** P1: also remove from the unified AgentRegistry. */
  def unregisterActor(sid: String, resources: AgentRuntimePort): IO[Unit] =
    actorMap.update(_ - sid) *> resources.agentRegistry.update(_ - sid)

  def getRunningActor(sid: String): IO[Option[ActorRef[AgentCommand]]] =
    actorMap.get.map(_.get(sid))

  def registerManager(instance: String, sid: String): IO[Unit] =
    managerMap.update(_ + (instance -> sid))

  def isManager(sid: String): IO[Boolean] =
    managerMap.get.map(_.values.toSet.contains(sid))

  /**
   * Block 0 registration chain (supervision trio §B2): the Manager sessionId
   * for a team instance, if registered.
   */
  def managerOf(instance: String): IO[Option[String]] =
    managerMap.get.map(_.get(instance))

  /**
   * Block 0 registration chain: resolve the parent session for a team agent
   * record — a MEMBER's parent is its team Manager; the MANAGER's parent is
   * the mounting root session (parentSessionMap). Unknown session → None
   * (callers fall back to the activating/mounting session id).
   */
  def parentForRecord(sid: String): IO[Option[String]] =
    teamOfSession(sid).flatMap {
      case Some(inst) =>
        managerOf(inst).flatMap {
          case Some(mgr) if mgr != sid => IO.pure(Some(mgr))
          case _ => parentSessionOf(inst)
        }
      case None => IO.pure(None)
    }

  def registerParentSession(instance: String, sid: String): IO[Unit] =
    parentSessionMap.update(_ + (instance -> sid))

  def registerParentActor(sid: String, ref: ActorRef[AgentCommand]): IO[Unit] =
    parentActorMap.update(_ + (sid -> ref))

  def getParentActor(sid: String): IO[Option[ActorRef[AgentCommand]]] =
    parentActorMap.get.map(_.get(sid))

  def parentSessionOf(instance: String): IO[Option[String]] =
    parentSessionMap.get.map(_.get(instance))

  /**
   * Resolve a short agent name to a sessionId within a team instance.
   *
   * Address formats:
   *   - "team/agent" — exact scoped lookup (e.g. "nebflow-project/Backend").
   *   - "agent" — same-team first (when the sender belongs to a team), then a
   *     global lookup with ambiguity detection: if multiple teams have an
   *     agent with this short name, the call FAILS with the candidate list
   *     instead of silently picking one — Map iteration order is not
   *     deterministic, so collectFirst can hit any team.
   *
   * Returns:
   *   - Right(Some(sid)) — resolved
   *   - Right(None) — not found anywhere
   *   - Left(error) — ambiguous short name (candidates listed) or team not found
   */
  def resolveSessionId(
    senderSid: String,
    address: String,
    sessionStore: nebflow.core.SessionStorePort
  ): IO[Either[String, Option[String]]] =
    // "team/agent" scoped format — exact match, no ambiguity.
    val slashIdx = address.indexOf('/')
    if slashIdx > 0 then
      val (team, agent) = (address.substring(0, slashIdx), address.substring(slashIdx + 1))
      sessionMap.get.map { m =>
        m.get((team, agent)) match
          case Some(sid) => Right(Some(sid))
          case None =>
            // "team/Nebula" — Nebula is the top-level root agent, never a team
            // session; Right(None) lets the caller route it to Nebula directly.
            if agent == RootAgentIdentity.Name then Right(None)
            else if m.keys.exists(_._1 == team) then Right(None)
            else Left(s"Team '$team' not found or not mounted. Use Load(type: \"team\", name: \"$team\") first.")
      }
    else
      sessionMap.get.flatMap { m =>
        // Sender's own instance — resolve same-team agent first.
        val senderInstance = m.collectFirst { case ((inst, _), sid) if sid == senderSid => inst }
        senderInstance match
          case Some(inst) =>
            m.get((inst, address)) match
              case Some(sid) => IO.pure(Right(Some(sid)))
              case None =>
                // Same-team miss: fall back to any instance with that name
                // (e.g. a standalone session, or another team's manager),
                // with ambiguity detection.
                IO.pure(resolveGlobal(m, address))
          case None =>
            // Sender isn't in any team (Nebula root / standalone). Per the
            // routing rule, bare short names are NOT routable from outside a
            // team — a global pick is non-deterministic. Only team names
            // (handled by callers before reaching here), "Nebula", and
            // explicit "team/agent" are valid from outside.
            if address == RootAgentIdentity.Name then IO.pure(Right(None))
            else
              IO.pure(
                Left(
                  s"Agent '$address' cannot be mailed from outside a team. Mail a TEAM name (e.g. \"nebflow-project\") — the Manager dispatches to members. Team members use short names internally."
                )
              )
        end match
      }

    end if

  end resolveSessionId

  /** Global short-name lookup with ambiguity detection. */
  private def resolveGlobal(
    m: Map[(String, String), String],
    address: String
  ): Either[String, Option[String]] =
    m.toList.collect { case ((inst, `address`), sid) => (inst, sid) } match
      case Nil => Right(None)
      case (_, sid) :: Nil => Right(Some(sid))
      case many =>
        val teams = many.map(_._1).distinct.sorted.mkString(", ")
        Left(
          s"Agent '$address' is ambiguous: found in ${many.size} teams ($teams). " +
            s"Use \"team/agent\" format (e.g. \"${many.head._1}/$address\") to disambiguate."
        )

  /** Get the agent name for a session. */
  def agentOfSession(sid: String): IO[Option[String]] =
    sessionMap.get.map(_.collectFirst { case ((_, agent), s) if s == sid => agent })

  /** Get (instance, agent) for a session. */
  def instanceAndAgentOfSession(sid: String): IO[Option[(String, String)]] =
    sessionMap.get.flatMap { m =>
      m.collectFirst { case ((inst, agent), s) if s == sid => (inst, agent) } match
        case Some(t) => IO.pure(Some(t))
        case None => IO.pure(None)
    }

  /** Find the team/instance name for a session (replaces flowOfSession). */
  def teamOfSession(sid: String): IO[Option[String]] =
    sessionMap.get.map(_.collectFirst { case ((inst, _), s) if s == sid => inst })

  /** Find a specific agent's sessionId within a team instance. */
  def findTeamAgent(instance: String, agentName: String): IO[Option[String]] =
    sessionMap.get.map(_.get((instance, agentName)))

  /** List all sessionIds for an instance. */
  def sessionIdsOf(instance: String): IO[List[String]] =
    sessionMap.get.map(_.collect { case ((inst, _), sid) if inst == instance => sid }.toList)

  /** List all agents for an instance. */
  def agentsOfInstance(instance: String): IO[List[(String, String)]] =
    sessionMap.get.map(_.collect { case ((`instance`, agent), sid) => (agent, sid) }.toList)

  /** Remove all sessions for an instance. */
  def unregisterInstance(instance: String): IO[Unit] =
    for
      sids <- sessionIdsOf(instance)
      _ <- sessionMap.update(_.filterNot { case ((inst, _), _) => inst == instance })
      _ <- managerMap.update(_ - instance)
      _ <- parentSessionMap.update(_ - instance)
      _ <- parentActorMap.update(_ -- sids)
    yield ()

  /** Remove a specific agent from an instance. */
  def unregisterAgent(instance: String, agent: String, sid: String): IO[Unit] =
    sessionMap.update(_ - ((instance, agent))) *>
      parentActorMap.update(_ - sid)

  def markBusy(sid: String): IO[Unit] = busyMap.update(_ + sid)
  def markIdle(sid: String): IO[Unit] = busyMap.update(_ - sid)
  def isBusy(sid: String): IO[Boolean] = busyMap.get.map(_.contains(sid))

  /** List all mounted teams with their agents: instanceName → [(agentName, sessionId)]. */
  def listMountedTeams: IO[Map[String, List[(String, String)]]] =
    sessionMap.get.map { m =>
      m.groupBy { case ((inst, _), _) => inst }
        .view
        .mapValues(_.view.map { case ((_, agent), sid) => (agent, sid) }.toList)
        .toMap
    }

end TeamSessionRegistry
