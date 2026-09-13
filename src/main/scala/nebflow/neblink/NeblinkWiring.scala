package nebflow.neblink

import cats.effect.{IO, Ref}
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
    *
    * `remarks`（2026-09-12 ⑦）：启动期由调用方（`GatewayMain`）从
    * `FriendRemarkStore.load` 读入并透传 —— 工厂保持**纯函数**（不自己跑 IO），
    * 与 `NeblinkService.createInternal` 的 `PeerDescriptionStore.load → Ref.of`
    * 同族形态；测试可注入任意初始 map。默认空 map ⇒ 既有调用点零改动。
    *
    * `askConfirm`（#147 接线段，2026-09-12）：ask 档确认链的**装配缝接线点**。
    * 此参数此前**不存在** ⇒ `FriendService.askConfirm` 恒为默认 `None` ⇒ `ask`
    * 档一调即 `Left("ask mode requires a confirmation callback (not wired)")`
    * （作者 2026-09-11 裁定 U-5 的事实锚）。默认 `None` 保留「未接线」这一显式
    * 条件（既有调用点/测试零改动）。
    *
    * 生产装配（`GatewayMain`）传的值 = `nebflow.agent.SendConfirm.production`
    * —— **运行时真正执行的就是它**（不是桩、没有第二条实现）：它会向确认卡
    * 请求链发出 AskUser 并据此决定投递与否。会话靶（谁在问）不由本缝决定——
    * 缝在 boot 期，不知道任何会话；靶由唯一持 `ToolContext` 的调用侧
    * （`FriendMessageTool`）按次以 `SendConfirm.locally` 挂进 fiber-local，
    * `production` 在本次调用内读它；无靶（REST 直调/harness）⇒ 显式 fail-closed。
    * 理由与代码锚见 `nebflow.agent.SendConfirm` 文件头。
    */
  def friendService(
    clientProvider: IO[Option[NeblinkClient]],
    config: AgentMessagingConfig,
    guard: FriendMessagingGuard = new FriendMessagingGuard(),
    onFriendEvent: Option[FriendEvent => IO[Unit]] = None,
    askConfirm: Option[String => IO[Boolean]] = None,
    remarks: Map[String, String] = Map.empty,
    /** D-B（2026-09-13 好友推送修复批）：送达确证发送面（帧形状冻结见
      * `NeblinkRelayTunnel.sendAck`）。缺省 `None` = 未接线（既有调用点/测试零改动），
      * 生产由 `GatewayMain` 接 `neblinkService.relayTunnelOpt` 的 **live** 读取。 */
    ackSender: Option[String => IO[Unit]] = None
  ): FriendService =
    new FriendService(
      clientProvider,
      config,
      guard,
      onFriendEvent,
      askConfirm,
      remarkRef = Ref.unsafe[IO, Map[String, String]](remarks),
      ackSender = ackSender
    )

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
