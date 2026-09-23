package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.nio.file.*
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Per-connection Explorer watch subscriptions (explorer-rt · design card
 * chain-n-1981ce87, case C hybrid — push main path + existing refresh legs
 * as fallback).
 *
 * One instance lives for the lifetime of ONE WebSocket connection (created
 * in the `/ws` route alongside the outbound queue; `close()` is chained into
 * the receive-pipe finalizer, so a dropped connection clears the whole
 * subscription table — implementation hard requirement).
 *
 * Frame contract (three new cases, existing frame shapes untouched):
 *   - uplink   `watchSubscribe{sessionId, rootPath?, dirs?}`  — rootPath
 *     semantics identical to listDir (override wins, else session project
 *     root). `dirs` = explicit visible-dir set (explorer-relative paths);
 *     absent `dirs` = auto mode (root only, grows via noteListed on each
 *     successful listDir reply on this connection).
 *   - uplink   `watchUnsubscribe{rootPath?}`                  — tears the
 *     subscription for that rootPath echo value down (`sessionId` is accepted
 *     but NOT required — teardown must work with the session already gone).
 *   - downlink `fsChanged{rootPath, dirs, overflow}`          — dirs = deduped
 *     explorer-relative dirs needing a reload (debounced 500 ms); overflow =
 *     true carries an EMPTY dirs list (watch-service queue overflow — the
 *     frontend degrades to a full visible-tree reload).
 *
 * Cost shape: one WatchService per (connection, subscribed root); only the
 * frontend-declared VISIBLE expanded directories are registered — O(visible
 * set), never a full-tree recursion. On macOS the JDK WatchService is
 * polling-based (spike 2026-09-21, OpenJDK 23.0.2:
 * sun.nio.fs.PollingWatchService, flat ~1.6–1.7 s event latency at the
 * DEFAULT sensitivity, a 10-file burst delivered as one batch in the same
 * window), so registration count is the cost driver — hidden dirs are never
 * listed hence never registered, which kills the target/.git churn event
 * storm for free (HIDDEN_DIRS consistency by construction).
 *
 * Architecture iron law: this is pure cats-effect side-effect orchestration
 * (no actors). The only blocking primitives (WatchService.poll / Path
 * register / close) are wrapped in IO.blocking; per-event batching flows
 * through Refs; the debounce flush is a plain `IO.sleep *> flush` fiber.
 *
 * @param send     downlink emitter for this connection (offers to the
 *                 outbound frame queue)
 * @param live     false = the REST facade (`handleMessagePublic`) — watch
 *                 frames make no sense without a WS connection, so
 *                 subscribe/unsubscribe answer a fileOpError instead of
 *                 silently doing nothing (fail-closed).
 */
final class ExplorerWatchSession(send: Json => IO[Unit], log: NebflowLogger, live: Boolean = true):

  /**
   * Debounce window for merging dirty dirs into one fsChanged frame. Well
   * under the macOS 2 s polling cadence (events already arrive batched
   * there) — on Linux inotify it is what collapses per-file events.
   */
  private val debounce: FiniteDuration = 500.millis

  /**
   * Resource guards: a buggy client must not be able to inflate polling
   * cost (each registered dir is polled every 2 s on macOS).
   */
  private val maxRootsPerConn = 8
  private val maxDirsPerRoot = 2000

  /**
   * key = client-sent rootPath echo value ("" = session project root).
   * Re-subscribing the same key replaces the previous subscription
   * (idempotent reconnect / expand-set updates).
   */
  private val subs = new ConcurrentHashMap[String, ExplorerWatchSession.Sub]()

  // ── Uplink: subscribe ──────────────────────────────────────────────────

  /**
   * Subscribe one root. `root` must already be canonical+validated by the
   * caller (same resolution as listDir). `dirs` = explicit visible set
   * (None = auto mode). Fails (raised error, caller maps to fileOpError) on:
   * non-live session, root not a directory, invalid rel dir (absolute /
   * `..` escape), resource cap hit.
   *
   * Leak order: the sub is put into the table BEFORE any registration, so a
   * cancellation landing mid-subscribe still leaves it reachable by
   * `close()` (which marks it closed and releases the WatchService).
   */
  def subscribe(root: os.Path, echoRootPath: String, dirs: Option[List[String]]): IO[Unit] =
    if !live then IO.raiseError(new RuntimeException("file watch requires a WebSocket connection"))
    else if !os.isDir(root) then IO.raiseError(new RuntimeException(s"watch root not found or not a directory: $root"))
    else
      for
        service <- IO.blocking(FileSystems.getDefault.newWatchService())
        dirty <- Ref.of[IO, Set[String]](Set.empty)
        flushScheduled <- Ref.of[IO, Boolean](false)
        overflow <- Ref.of[IO, Boolean](false)
        sub = new ExplorerWatchSession.Sub(
          root = root,
          echoRootPath = echoRootPath,
          service = service,
          autoRegister = dirs.isEmpty
        )
        // Replace-on-resubscribe: tear the previous subscription for this key
        // down first (idempotent re-subscribes from reconnect / expand bursts).
        _ <- Option(subs.remove(echoRootPath)).traverse_(closeSub)
        _ <- IO.raiseWhen(subs.size() >= maxRootsPerConn)(
          new RuntimeException(s"too many watch subscriptions on this connection (max $maxRootsPerConn)")
        )
        _ <- IO(subs.put(echoRootPath, sub))
        // Register the root itself (rel ""), then the declared visible set.
        // Invalid rel dirs fail the WHOLE subscribe (fail-closed — R3: same
        // escape-judgment family as listDir's canonical guard).
        declared = dirs.getOrElse(Nil)
        // Registration failures must not leave a pump-less zombie in the
        // table (it would hold a live WatchService that nothing ever closes
        // before connection close): release the half-built subscription and
        // re-raise. The root registration is inside this guard too.
        _ <- (registerDir(sub, "").adaptError { case e =>
          new RuntimeException(s"cannot watch root $root: ${e.getMessage}")
        } *> IO.raiseWhen(declared.size > maxDirsPerRoot)(
          new RuntimeException(s"too many watch dirs (max $maxDirsPerRoot)")
        ) *> declared.traverse_(rel => validateRelDir(rel).flatMap(clean => registerDir(sub, clean)))).onError(_ =>
          closeSub(sub)
        )
        // Pump fiber: its finalizer closes the WatchService (unblocking poll)
        // and detaches the subscription on ANY exit path — including the
        // ClosedWatchServiceException that closeSub itself triggers.
        _ <- pump(sub)
          .guaranteeCase {
            // cats-effect 3 hands the handler an Outcome (there is no
            // CE2 ExitCase) — repo precedent: WebSocketRoutes.scala:130,
            // NeblinkSingleFlight.scala:55.
            case cats.effect.Outcome.Errored(e) =>
              closeSub(sub) *> log.warn(
                s"Explorer watch error (root=$echoRootPath): ${Option(e.getMessage).getOrElse(e.toString)}"
              ) *> send(fileOpError(s"Explorer watch error: ${e.getMessage}"))
            case _ => closeSub(sub)
          }
          .start
          .void
      yield ()

  // ── Uplink: unsubscribe ────────────────────────────────────────────────

  /**
   * Tear the subscription for `echoRootPath` down. Unknown key = no-op
   * (idempotent — double unsubscribe must not error).
   */
  def unsubscribe(echoRootPath: String): IO[Unit] =
    Option(subs.remove(echoRootPath)).traverse_(closeSub).void

  // ── listDir hook (auto-mode growth) ────────────────────────────────────

  /**
   * Called after every successful listDir reply on this connection. Only
   * grows AUTO-mode subscriptions (explicit `dirs` subscribers manage their
   * visible set themselves — keeps "only visible dirs are registered"
   * exact: external-drop conflict scans list non-visible dirs and must not
   * leak registrations). Zero-cost when there are no subscriptions.
   */
  def noteListed(absDir: os.Path): IO[Unit] =
    if subs.isEmpty then IO.unit
    else
      subs.values().asScala.toList.traverse_ { sub =>
        if sub.closed || !sub.autoRegister || !absDir.startsWith(sub.root) then IO.unit
        else
          val rel = if absDir == sub.root then "" else absDir.relativeTo(sub.root).toString
          registerDir(sub, rel)
      }

  // ── Connection finalizer + introspection (spec anchors) ───────────────

  /**
   * Connection dropped: clear the whole table (hard requirement — a dead
   * connection must never keep watchers alive).
   */
  def close(): IO[Unit] =
    subs.values().asScala.toList.traverse_(closeSub).void

  /**
   * Subscription table snapshot for spec assertions (R2: table empty after
   * connection close). (echoKey, registeredDirCount)
   */
  private[gateway] def tableSnapshot: List[(String, Int)] =
    subs.values().asScala.map(s => (s.echoRootPath, s.registered.size())).toList

  // ── Test hook (R4: OVERFLOW is not synthesizable through the public API
  //    of a real WatchService on demand — inject the flag exactly where the
  //    pump's OVERFLOW branch puts it and exercise the same flush path). ──
  private[gateway] def simulateOverflow(echoRootPath: String): IO[Unit] =
    Option(subs.get(echoRootPath)) match
      case Some(sub) => sub.overflow.set(true) *> scheduleFlush(sub)
      case None => IO.raiseError(new RuntimeException(s"no subscription for '$echoRootPath'"))

  // ── Internals ──────────────────────────────────────────────────────────

  /**
   * Rel-dir validation (R3 anchor): explorer-relative only — no absolute,
   * no traversal segments, no backslash. `""` is LEGAL — it is the root in
   * the explorer coordinate system and the real frontend always leads its
   * declared visible set with it (explorer.js `visibleWatchDirs()` =
   * `'' +: expandedDirs`). The root itself is registered BEFORE the
   * declared set is walked, so a declared `""` re-hits registerDir's
   * registration dedup map as a no-op. (Rejecting `""` here failed the
   * WHOLE subscribe fail-closed and killed the push main path in every
   * configuration — verify round-1 R12.) Returns the cleaned form.
   */
  private def validateRelDir(rel: String): IO[String] =
    IO.raiseWhen(rel.startsWith("/"))(new RuntimeException("absolute path not allowed")) *>
      IO.raiseWhen(rel.contains("\\"))(new RuntimeException("backslash not allowed")) *>
      IO.raiseWhen(rel.nonEmpty && rel.split('/').exists(seg => seg.isEmpty || seg == "." || seg == ".."))(
        new RuntimeException("path traversal segment")
      ).as(rel.stripSuffix("/"))

  /**
   * Register one visible directory with this subscription's WatchService.
   * Silently skips dirs that vanished between the frontend listing and the
   * register call (racy expand — the next expand reloads anyway).
   */
  private def registerDir(sub: ExplorerWatchSession.Sub, rel: String): IO[Unit] =
    IO.blocking {
      if !sub.closed && !sub.registered.containsKey(rel) then
        val abs = if rel.isEmpty then sub.root else os.Path(rel, sub.root)
        if os.isDir(abs) then
          val key = abs.toIO.toPath.register(
            sub.service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
          )
          sub.registered.put(rel, abs)
          ()
    }.void
      .handleErrorWith(e => log.debug(s"watch register skipped ($rel): ${e.getMessage}"))

  /**
   * Event pump: bounded poll (1 s) so fiber cancellation and service-close
   * are noticed quickly; every non-empty key batch merges its dirs into the
   * dirty set and (re)arms the debounce flush.
   *
   * ClosedWatchServiceException is a NORMAL shutdown here, not a failure:
   * closeSub (unsubscribe / connection close / re-subscribe) closes the
   * service while this fiber is parked in poll. Letting it escape would log a
   * phantom error AND push a fileOpError frame for every explorer-root switch,
   * i.e. a user-visible toast for a successful unsubscribe — so it exits
   * quietly, while genuine errors still reach the guaranteeCase above.
   */
  private def pump(sub: ExplorerWatchSession.Sub): IO[Unit] =
    if sub.closed then IO.unit
    else
      IO.blocking(Option(sub.service.poll(1, TimeUnit.SECONDS)))
        .flatMap {
          case None => pump(sub)
          case Some(key) => handleKey(sub, key) *> pump(sub)
        }
        .handleErrorWith { case _: java.nio.file.ClosedWatchServiceException => IO.unit }

  private def handleKey(sub: ExplorerWatchSession.Sub, key: WatchKey): IO[Unit] =
    IO.blocking {
      val watchDir = key.watchable().asInstanceOf[Path]
      val events = key.pollEvents()
      val _ = key.reset()
      (watchDir, events)
    }.flatMap { case (watchDir, events) =>
      if events.isEmpty then IO.unit
      else
        val watchDirOs = os.Path(watchDir)
        val relDir =
          if watchDirOs == sub.root then ""
          else watchDirOs.relativeTo(sub.root).toString
        events.asScala.toList.foldLeftM(()) { (_, ev) =>
          if ev.kind() == StandardWatchEventKinds.OVERFLOW then
            // Queue overflow: events were DROPPED server-side. Flag it — the
            // next flush degrades to overflow:true with empty dirs and the
            // frontend falls back to a full visible-tree reload.
            sub.overflow.set(true) *> scheduleFlush(sub)
          else sub.dirty.update(_ + relDir) *> scheduleFlush(sub)
        }
    }

  /** Arm the debounce flush (at most one pending flush per subscription). */
  private def scheduleFlush(sub: ExplorerWatchSession.Sub): IO[Unit] =
    sub.flushScheduled
      .getAndSet(true)
      .flatMap(wasScheduled => if wasScheduled then IO.unit else (IO.sleep(debounce) *> flush(sub)).start.void)

  /**
   * Swap the dirty set out and emit one fsChanged frame. Order matters:
   * clear the scheduled flag FIRST — a dir marked dirty after the swap gets
   * a fresh schedule instead of being silently swallowed by the window.
   */
  private def flush(sub: ExplorerWatchSession.Sub): IO[Unit] =
    for
      _ <- sub.flushScheduled.set(false)
      overflow <- sub.overflow.getAndSet(false)
      dirs <- sub.dirty.getAndSet(Set.empty)
      _ <-
        if sub.closed then IO.unit
        else if overflow then send(fsChanged(sub.echoRootPath, Nil, overflow = true))
        else if dirs.nonEmpty then send(fsChanged(sub.echoRootPath, dirs.toList.sorted, overflow = false))
        else IO.unit
    yield ()

  /**
   * Mark closed, detach from the table (only if still the live sub for the
   * key), release the WatchService. The pump fiber unwinds on its own: the
   * closed flag short-circuits the loop, and closing the service unblocks a
   * parked poll with ClosedWatchServiceException (finalizer path).
   */
  private def closeSub(sub: ExplorerWatchSession.Sub): IO[Unit] =
    IO {
      if !sub.closed then
        sub.closed = true
        subs.remove(sub.echoRootPath, sub)
        ()
    } *> IO.blocking(sub.service.close()).handleErrorWith(_ => IO.unit)

  private def fileOpError(message: String): Json =
    Json.obj("type" -> "fileOpError".asJson, "error" -> message.asJson)

  private def fsChanged(rootPath: String, dirs: List[String], overflow: Boolean): Json =
    Json.obj(
      "type" -> "fsChanged".asJson,
      "rootPath" -> rootPath.asJson,
      "dirs" -> dirs.asJson,
      "overflow" -> overflow.asJson
    )

end ExplorerWatchSession

object ExplorerWatchSession:

  /** One subscription (one watched root of one connection). */
  final class Sub(
    val root: os.Path,
    val echoRootPath: String,
    val service: WatchService,
    val autoRegister: Boolean
  ):
    @volatile var closed: Boolean = false

    /** rel dir → canonical abs dir (registration set = the visible set). */
    val registered: ConcurrentHashMap[String, os.Path] = new ConcurrentHashMap()
    val dirty: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val flushScheduled: Ref[IO, Boolean] = Ref.unsafe(false)
    val overflow: Ref[IO, Boolean] = Ref.unsafe(false)
  end Sub
end ExplorerWatchSession
