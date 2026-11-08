package nebflow.core.delegate

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import nebflow.core.AtomicJson

/** Shared durable authority for ownership and execution reservations. Reads fail
  * closed on corrupt data. The file lock also covers other application processes.
  */
final class WorkspaceLedger(val path: os.Path):
  import WorkspaceLedger.*

  private def transaction[A](f: State => (State, A)): A = WorkspaceLedger.synchronized {
    os.makeDir.all(path / os.up)
    val channel = java.nio.channels.FileChannel.open((path / os.up / s"${path.last}.lock").toNIO,
      java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
    try
      val lock = channel.lock()
      try
        val before = if os.exists(path) then io.circe.parser.decode[State](os.read(path))
          .fold(e => throw new IllegalStateException("Workspace ledger is unreadable", e), identity)
        else State()
        val (after, result) = f(before)
        if after != before then AtomicJson.writeSync(path, after.asJson.noSpaces)
        result
      finally lock.release()
    finally channel.close()
  }

  def snapshot: State = transaction(s => (s, s))

  def register(workspace: Workspace): Either[String, Workspace] = transaction { s =>
    s.workspaces.find(w => w.id == workspace.id || w.path == workspace.path) match
      case Some(existing) if existing == workspace => (s, Right(existing))
      case Some(_) => (s, Left("Workspace already has an owner"))
      case None => (s.copy(workspaces = s.workspaces :+ workspace), Right(workspace))
  }

  /** One identity for aliases of the same repository; never silently accept a task. */
  def merger(project: String, repository: String, candidateId: String): Merger = transaction { s =>
    s.mergers.find(m => m.repository == repository || m.project == project) match
      case Some(existing) if existing.repository != repository =>
        throw new IllegalStateException("Project repository changed; merger reconciliation required")
      case Some(existing) => (s, existing)
      case None =>
        val created = Merger(project, repository, candidateId)
        (s.copy(mergers = s.mergers :+ created), created)
  }

  def claimDevelopment(id: String, owner: String, revision: Long): Either[String, Workspace] =
    change(id, revision) { w =>
      if w.owner != owner then Left("Not the development owner")
      else if w.lifecycle != "open" || w.activity != "idle" then Left("Workspace is reserved, busy, or closed")
      else Right(w.copy(activity = "running"))
    }

  def releaseDevelopment(id: String, owner: String, revision: Long, frozen: Boolean): Either[String, Workspace] =
    change(id, revision) { w =>
      if w.owner != owner || w.lifecycle != "open" || w.activity != "running" then Left("No development claim")
      else Right(w.copy(activity = if frozen then "frozen" else "idle"))
    }

  /** All-or-nothing reservation: busy development never joins a merge queue. */
  def reserve(request: String, ids: List[String]): Either[String, List[Workspace]] = transaction { s =>
    val selected = ids.distinct.flatMap(id => s.workspaces.find(_.id == id))
    if selected.isEmpty || selected.size != ids.distinct.size then (s, Left("Unknown workspace"))
    else if selected.forall(w => w.reservation.contains(request)) then (s, Right(selected))
    else if selected.exists(w => w.lifecycle != "open" || w.activity != "idle") then
      (s, Left("Every selected workspace must be open and idle"))
    else
      val reserved = selected.map(w => w.copy(lifecycle = "reserved", reservation = Some(request), revision = w.revision + 1))
      val replacements = reserved.map(w => w.id -> w).toMap
      (s.copy(workspaces = s.workspaces.map(w => replacements.getOrElse(w.id, w))), Right(reserved))
  }

  private def change(id: String, revision: Long)(f: Workspace => Either[String, Workspace]): Either[String, Workspace] =
    transaction { s =>
      s.workspaces.find(_.id == id) match
        case None => (s, Left("Unknown workspace"))
        case Some(w) if w.revision != revision => (s, Left("Stale workspace revision"))
        case Some(w) => f(w) match
          case Left(error) => (s, Left(error))
          case Right(updated) =>
            val next = updated.copy(revision = w.revision + 1)
            (s.copy(workspaces = s.workspaces.map(x => if x.id == id then next else x)), Right(next))
    }

object WorkspaceLedger:
  final case class Workspace(
    id: String, project: String, repository: String, path: String,
    branch: String, baseBranch: String, baseCommit: String, owner: String, ownerMode: String,
    lifecycle: String = "open", activity: String = "idle",
    reservation: Option[String] = None, revision: Long = 0L
  )
  final case class Merger(project: String, repository: String, delegateId: String)
  final case class State(workspaces: List[Workspace] = Nil, mergers: List[Merger] = Nil)
  given io.circe.Codec[Workspace] = deriveCodec
  given io.circe.Codec[Merger] = deriveCodec
  given io.circe.Codec[State] = deriveCodec
