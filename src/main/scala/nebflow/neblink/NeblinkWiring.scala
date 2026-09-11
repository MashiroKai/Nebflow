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

  /** The value written into `SharedResources.friendService`.
    *
    * CURRENT (defective) semantics — mirrors `GatewayMain` verbatim: the slot is
    * derived from the boot client snapshot, so a fresh home can never
    * materialise a friend domain without a restart.
    */
  def sharedResourcesSlot(
    bootClient: Option[NeblinkClient],
    friendService: FriendService
  ): Option[FriendService] =
    logger.debug(s"friend domain slot resolved (boot client present: ${bootClient.isDefined})")
    bootClient.map(_ => friendService)

end NeblinkWiring
