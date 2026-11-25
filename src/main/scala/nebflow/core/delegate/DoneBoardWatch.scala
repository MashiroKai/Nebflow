package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{AgentRuntimePort, GitWorktrees}
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * DoneBoardWatch — engine-side aging watch over finished delegate leftovers
 * (SCM blueprint P0 item 3, "done-board mechanization").
 *
 * Two stale shapes are detected per delegate record that has a branch and a
 * base branch and whose session is no longer live:
 *
 *   - (a) **done tree lingering**: the worktree still exists, the tree is
 *     clean (zero uncommitted paths, zero commits ahead of the base branch),
 *     and the last activity (max(record.createdAt, HEAD commit time)) is
 *     older than [[AgeMs]] (24h).
 *   - (b) **merged branch whose worktree is already gone**: the worktree
 *     directory no longer exists, the branch still exists locally, its tip is
 *     fully merged into the base branch (tip == base tip, or an ancestor of
 *     it), and the condition has been observed continuously for longer than
 *     [[AgeMs]] (24h, anchored at the first observation in the state file —
 *     a removed tree leaves no activity timestamp to read).
 *
 * Disposition is **report-only**: the watch injects one aggregated notice per
 * dispatching root session (each item at most once per [[NotifyWindowMs]])
 * through [[DelegateNotify.inject]]. It performs **zero git writes** — no
 * branch deletion, no worktree removal, no ledger writes. Branches are kept
 * by default; disposal stays a human / merger decision.
 *
 * Scheduling rides the existing TaskStuckWatcher scan tick (no new timer).
 * A full sweep costs a few git reads per candidate record, so it is
 * internally throttled to at most one sweep per [[MinScanIntervalMs]].
 *
 * State lives at `<dataRoot>/done-board-watch.json` (v1 JSON: last-sweep
 * stamp plus one entry per observed leftover) so observation ages and notice
 * throttles survive restarts. Entries whose condition no longer holds are
 * pruned on the next sweep (zero residue growth).
 */
object DoneBoardWatch:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.done-board-watch")

  /** A leftover must age past this before it is reported. */
  val AgeMs: Long = 24L * 60L * 60L * 1000L

  /** One leftover item is re-reported at most once per this window. */
  val NotifyWindowMs: Long = 24L * 60L * 60L * 1000L

  /** Full-sweep throttle (the host scan tick fires every 30s). */
  val MinScanIntervalMs: Long = 10L * 60L * 1000L

  /** At most this many item lines are listed in one root notice. */
  val MaxItemsPerNotice: Int = 20

  /** State file version. */
  val StateVersion: Int = 1

  private def statePath: os.Path = PathUtil.dataRoot / "done-board-watch.json"

  /** State file read-modify-write lock (short critical sections, blocking pool). */
  private def withStateLock[A](f: => A): A = synchronized(f)

  /** One observed leftover (detection keyed by `<kind>:<recordId>`). */
  final case class Entry(
    kind: String, // "tree" | "branch"
    recordId: String,
    branch: String,
    baseBranch: String,
    title: String,
    rootSessionId: String,
    detail: String,
    firstSeenMs: Long,
    lastNotifyMs: Long = 0L
  ):

    def toJson: Json =
      Json.obj(
        "kind" -> kind.asJson,
        "recordId" -> recordId.asJson,
        "branch" -> branch.asJson,
        "baseBranch" -> baseBranch.asJson,
        "title" -> title.asJson,
        "rootSessionId" -> rootSessionId.asJson,
        "detail" -> detail.asJson,
        "firstSeenMs" -> firstSeenMs.asJson,
        "lastNotifyMs" -> lastNotifyMs.asJson
      )

  end Entry

  object Entry:

    def fromJson(j: Json): Option[Entry] =
      val c = j.hcursor
      for
        recordId <- c.get[String]("recordId").toOption
        branch <- c.get[String]("branch").toOption
      yield Entry(
        kind = c.get[String]("kind").toOption.getOrElse("branch"),
        recordId = recordId,
        branch = branch,
        baseBranch = c.get[String]("baseBranch").toOption.getOrElse(""),
        title = c.get[String]("title").toOption.getOrElse(""),
        rootSessionId = c.get[String]("rootSessionId").toOption.getOrElse(""),
        detail = c.get[String]("detail").toOption.getOrElse(""),
        firstSeenMs = c.get[Long]("firstSeenMs").toOption.getOrElse(0L),
        lastNotifyMs = c.get[Long]("lastNotifyMs").toOption.getOrElse(0L)
      )
    end fromJson
  end Entry

  final case class State(version: Int, lastScanMs: Long, entries: Map[String, Entry]):

    def toJson: Json =
      Json.obj(
        "version" -> version.asJson,
        "lastScanMs" -> lastScanMs.asJson,
        "entries" -> Json.fromJsonObject(
          JsonObject.fromMap(entries.toList.sortBy(_._1).map((k, e) => k -> e.toJson).toMap)
        )
      )

  object State:
    val empty: State = State(StateVersion, 0L, Map.empty)

    def fromJson(j: Json): Option[State] =
      val c = j.hcursor
      c.get[Long]("lastScanMs").toOption.map { lastScanMs =>
        val entries = c
          .downField("entries")
          .as[Map[String, Json]]
          .toOption
          .getOrElse(Map.empty)
          .flatMap { case (k, e) => Entry.fromJson(e).map(k -> _) }
        State(
          c.get[Int]("version").toOption.getOrElse(StateVersion),
          lastScanMs,
          entries
        )
      }
    end fromJson
  end State

  /** Clean-tree readout for a still-present worktree (all readings fail-soft). */
  final case class TreeRead(dirtyCount: Int, aheadOfBase: Int, lastActiveMs: Long)

  /** Port (production = [[fromResources]]; tests inject fake readouts). */
  trait Port:
    def delegates: IO[List[DelegateRegistry.Record]]
    def liveSessionIds: IO[Set[String]]

    /** Clean-tree readout for a still-present worktree; None = unreadable (skip, never guess). */
    def treeRead(worktreePath: String, baseBranch: String, branch: String): Option[TreeRead]
    def worktreePresent(worktreePath: String): Boolean

    /** Fully-merged check of `branch` against `baseBranch`; None = unreadable. */
    def branchMerged(workspace: String, baseBranch: String, branch: String): Option[Boolean]
    def notifyRoot(sessionId: String, text: String): IO[Boolean]
    def nowMs: IO[Long] = IO.realTime.map(_.toMillis)
    def loadState: IO[State] = IO.blocking(DoneBoardWatch.readState())
    def saveState(state: State): IO[Unit] = IO.blocking(DoneBoardWatch.writeState(state))
  end Port

  /** Production port (AgentRuntimePort assembly; git readings via GitWorktrees). */
  def fromResources(resources: AgentRuntimePort): Port = new Port:
    def delegates =
      IO.blocking(DelegateRegistry.list())
        .handleErrorWith(e => logger.warn(s"[done-board-watch] registry read failed: ${e.getMessage}").as(Nil))
    def liveSessionIds = resources.agentRegistry.get.map(_.keySet)
    def worktreePresent(p: String) =
      scala.util.Try(os.isDir(os.Path(p))).toOption.getOrElse(false)
    def treeRead(worktreePath: String, baseBranch: String, branch: String) =
      scala.util
        .Try {
          val p = os.Path(worktreePath)
          if !os.isDir(p) then None
          else
            GitWorktrees.dirtyPaths(p).map { dirty =>
              TreeRead(
                dirtyCount = dirty.size,
                aheadOfBase = GitWorktrees.commitsAhead(p, baseBranch, branch),
                lastActiveMs = GitWorktrees.headCommitEpoch(p).map(_ * 1000L).getOrElse(0L)
              )
            }
        }
        .toOption
        .flatten
    def branchMerged(workspace: String, baseBranch: String, branch: String) =
      scala.util
        .Try {
          val ws = os.Path(workspace)
          for
            tip <- GitWorktrees.headShaOfBranch(ws, branch)
            baseTip <- GitWorktrees.headShaOfBranch(ws, baseBranch)
          yield tip == baseTip || GitWorktrees.isAncestor(ws, tip, baseTip).contains(true)
        }
        .toOption
        .flatten
    def notifyRoot(sessionId: String, text: String) =
      DelegateNotify
        .inject(resources.agentRegistry, sessionId, text)
        .handleErrorWith { e =>
          logger.warn(s"[done-board-watch] root notify failed for '$sessionId': ${e.getMessage}").as(false)
        }

  /** Records in scope: delegates that actually created a branch (git-backed runs). */
  private def trackable(recs: List[DelegateRegistry.Record]): List[DelegateRegistry.Record] =
    recs.filter(r => r.branch.isDefined && r.baseBranch.isDefined)

  /**
   * One detection pass over the records. Port git readings are synchronous and
   * fail-soft: any unreadable record is skipped, never guessed. Returns
   * `key -> (entry, stale)` for every observed leftover; entries absent from
   * this pass are pruned by the caller. First-observation and notify stamps
   * carried over from `prev` survive as long as the condition keeps holding.
   */
  def detect(
    port: Port,
    recs: List[DelegateRegistry.Record],
    live: Set[String],
    now: Long,
    prev: Map[String, Entry]
  ): Map[String, (Entry, Boolean)] =
    trackable(recs).flatMap { r =>
      if live.contains(r.id) then None
      else
        val branch = r.branch.getOrElse("")
        val base = r.baseBranch.getOrElse("")
        // (a) done tree lingering: worktree present, clean, activity older than AgeMs.
        val treeItem = for
          wt <- r.worktree
          if port.worktreePresent(wt)
          read <- port.treeRead(wt, base, branch)
          if read.dirtyCount == 0 && read.aheadOfBase == 0
          lastActive = math.max(r.createdAt, read.lastActiveMs)
          if now - lastActive > AgeMs
        yield
          val key = s"tree:${r.id}"
          val prior = prev.get(key)
          val ageH = (now - lastActive) / 3600000L
          key -> ((
            Entry(
              kind = "tree",
              recordId = r.id,
              branch = branch,
              baseBranch = base,
              title = r.title,
              rootSessionId = r.parentSessionId,
              detail = s"worktree ${os.Path(wt).last} clean, 0 commits ahead of $base, idle ${ageH}h",
              firstSeenMs = prior.map(_.firstSeenMs).getOrElse(now),
              lastNotifyMs = prior.map(_.lastNotifyMs).getOrElse(0L)
            ),
            true
          ))
        // (b) merged branch whose worktree is already gone (branch kept by policy).
        val branchItem: Option[(String, (Entry, Boolean))] =
          if treeItem.isDefined then None
          else
            val treeGone = r.worktree.forall(wt => !port.worktreePresent(wt))
            val merged =
              if treeGone then port.branchMerged(r.projectWorkspace.getOrElse(r.cwd), base, branch).getOrElse(false)
              else false
            if !treeGone || !merged then None
            else
              val key = s"branch:${r.id}"
              val prior = prev.get(key)
              val firstSeen = prior.map(_.firstSeenMs).getOrElse(now)
              val ageH = (now - firstSeen) / 3600000L
              Some(
                key -> ((
                  Entry(
                    kind = "branch",
                    recordId = r.id,
                    branch = branch,
                    baseBranch = base,
                    title = r.title,
                    rootSessionId = r.parentSessionId,
                    detail = s"fully merged into $base, worktree removed, observed lingering ${ageH}h",
                    firstSeenMs = firstSeen,
                    lastNotifyMs = prior.map(_.lastNotifyMs).getOrElse(0L)
                  ),
                  now - firstSeen > AgeMs
                ))
              )
            end if
        treeItem.orElse(branchItem)
    }.toMap

  /** Root notice ([done-board] prefix = grep anchor; one aggregated message). */
  def message(items: List[Entry]): String =
    val sorted = items.sortBy(e => (e.kind, e.recordId))
    val shown = sorted.take(MaxItemsPerNotice)
    val extra = sorted.size - shown.size
    val lines = shown
      .map(e =>
        s"  - [${e.kind}] ${e.branch} — ${e.detail}${
            if e.title.isEmpty then "" else s" — task \"${e.title.take(80)}\""
          }"
      )
      .mkString("\n")
    val extraPart = if extra > 0 then s"\n  … (+$extra more)" else ""
    s"""[done-board] ${sorted.size} finished delegate item(s) have been lingering for more than ${AgeMs / 3600000L}h:$lines$extraPart
       |Report only — the engine took no disposal action (branches are kept by default). For each item, either dispose of it explicitly (delete the merged branch / remove the worktree) or dismiss this notice; if something here still needs work, resume that delegate instead.""".stripMargin

  /** Production entry point (wired at the TaskStuckWatcher scan tail). */
  def scan(resources: AgentRuntimePort): IO[List[String]] = scan(fromResources(resources))

  /** One sweep (throttled): detect, record, notify eligible roots, persist state. */
  def scan(port: Port): IO[List[String]] =
    port.loadState.flatMap { prev =>
      port.nowMs.flatMap { now =>
        val throttled = prev.lastScanMs > 0L && now - prev.lastScanMs < MinScanIntervalMs
        if throttled then IO.pure(Nil)
        else
          for
            recs <- port.delegates
            live <- port.liveSessionIds
            detected = detect(port, recs, live, now, prev.entries)
            entries = detected.map { case (k, (e, _)) => k -> e }
            eligible = detected.collect {
              case (k, (e, true)) if e.lastNotifyMs == 0L || now - e.lastNotifyMs >= NotifyWindowMs => k -> e
            }
            notices <-
              if eligible.isEmpty then IO.pure(List.empty[String])
              else
                eligible
                  .groupMap(_._2.rootSessionId)(_._2)
                  .toList
                  .traverse { case (root, items) =>
                    val list = items.toList
                    port.notifyRoot(root, message(list)).map {
                      case true => Some(s"$root: ${list.size} lingering finished item(s)")
                      case false => None
                    }
                  }
                  .map(_.flatten)
            notifiedKeys = eligible.map(_._1).toSet
            marked = entries.map { case (k, e) =>
              if notifiedKeys.contains(k) then k -> e.copy(lastNotifyMs = now) else k -> e
            }
            next = State(StateVersion, now, marked)
            _ <- port
              .saveState(next)
              .whenA(next != prev)
              .handleErrorWith(e => logger.warn(s"[done-board-watch] state save failed: ${e.getMessage}").void)
          yield notices
        end if
      }
    }

  // ── State file (v1 JSON; unreadable file = fresh empty state, fail-soft) ────

  private def readState(): State =
    if !os.exists(statePath) then State.empty
    else
      val raw = scala.util.Try(os.read(statePath)).toOption.getOrElse("").trim
      if raw.isEmpty then State.empty
      else parse(raw).toOption.flatMap(State.fromJson).getOrElse(State.empty)

  private def writeState(state: State): Unit =
    withStateLock {
      try
        os.makeDir.all(statePath / os.up)
        os.write.over(statePath, state.toJson.spaces2)
      catch case e: Throwable => logger.warn(s"[done-board-watch] state write failed: ${e.getMessage}")
    }

end DoneBoardWatch
