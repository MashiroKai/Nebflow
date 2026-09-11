package nebflow.neblink

import cats.effect.IO
import nebflow.core.NebflowLogger

/**
 * Boot wiring seam for the A2A friend domain (2026-09-11 boot-snapshot fix).
 *
 * WHY a seam: the 2026-09-11 incident was a WIRING decision, not a
 * business-logic one — `GatewayMain` derived the very EXISTENCE of the friend
 * domain from a boot-time client snapshot (`neblinkClient: Option[NeblinkClient]`,
 * `None` on a home without a NebLink config). A UI login (device-flow /
 * AC+PKCE) hot-swaps the client (`NeblinkEnrollment.persist` →
 * `NeblinkDiscovery.setClient`) but never re-creates the friend domain, so all
 * 16 `/api/friends*` REST sites kept answering 404 `NebLink not enabled` until
 * a process restart (evidence: `.nebflow/evidence/frienddiag/20260911_closeout-verdict.md`).
 *
 * `FriendService` itself was already per-call live (`currentClient`, F1 of the
 * 2026-09-10 batch) — only the "does it exist at all" judgement was snapshotted.
 * Extracting that judgement here makes it executable by a spec instead of
 * being untestable boot-time code buried inside one giant for-comprehension.
 */
object NeblinkWiring:

  private val logger = NebflowLogger.forName("nebflow.neblink.wiring")

  /** Build the boot-singleton `FriendService`.
    *
    * `clientProvider` is the authoritative LIVE client resolver
    * (`NeblinkDiscovery.currentClient`, F1): the service never captures a client
    * snapshot, so an enrollment hot-swap is picked up per call. One instance for
    * the whole process keeps `FriendMessagingGuard` state (unread cursors /
    * eventId dedupe set / rate-limit windows) intact — rebuilding the service on
    * every login would reset all three.
    */
  def friendService(
    clientProvider: IO[Option[NeblinkClient]],
    config: AgentMessagingConfig,
    guard: FriendMessagingGuard = new FriendMessagingGuard(),
    onFriendEvent: Option[FriendEvent => IO[Unit]] = None
  ): FriendService =
    new FriendService(clientProvider, config, guard, onFriendEvent)

  /** The value written into `SharedResources.friendService` (A 案, 2026-09-11).
    *
    * The slot is ALWAYS `Some`: a fresh home (boot snapshot = `None`) must still
    * get a live-resolving friend domain, otherwise a UI login can never bring it
    * into existence without a process restart — the defect this batch fixes
    * (16 `/api/friends*` sites stuck on `404 NebLink not enabled`). "Not logged
    * in" is expressed by the SERVICE (`FriendService.withClient` →
    * `Left("Not logged in")` → 401/404 by config criterion), never by the
    * absence of the service.
    *
    * `bootClient` is deliberately kept in the signature and *only* logged: the
    * boot snapshot stays visible at the call site (it is still what the startup
    * client / relay client are derived from), while no longer being part of the
    * existence judgement. Keeping the parameter also keeps the wiring line in
    * `FriendBootSnapshotRedlineSpec` identical before and after the fix — the
    * red-line spec's assertions flipped from red to green without a single
    * change to its wiring line or assertions.
    */
  def sharedResourcesSlot(
    bootClient: Option[NeblinkClient],
    friendService: FriendService
  ): Option[FriendService] =
    logger.debug(
      s"friend domain wired unconditionally (boot client present: ${bootClient.isDefined}; " +
        "existence no longer gated on the boot snapshot)"
    )
    Some(friendService)

end NeblinkWiring
