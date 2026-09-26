package nebflow.core

import io.circe.parser.parse

/**
 * friends-seal latch (friendseal batch, 2026-09-25) — the backend side of the
 * ONE existing `features.friends` config key (three layers, same source):
 *
 *   1. frontend entry visibility — `featureFlags.js` (compile-time strip in
 *      release bundles; dev reads the same key via WS `configData`),
 *   2. tool registration face — `AgentCore.fixedToolsFor` filters `ListFriends`
 *      out of the Nebula delivery set when sealed (this latch),
 *   3. tool description/schema — `FriendMessageTool`'s two-state
 *      description/inputSchema and the fail-closed `FRIENDS_SEALED` call gate
 *      on its friend/group/local arms (this latch), plus the seal-aware tail of
 *      `MailTool.targetMissingMessage`.
 *
 * Semantics: STARTUP LATCH — `initFromDisk()` reads `<home>/nebflow.json` once
 * when the gateway boots; a config edit takes effect on RESTART (mirrors the
 * frontend's per-boot decision in `featureFlags.js`). Absent key, `false`, or
 * an unreadable file => SEALED. `true` => unsealed, every face behaves exactly
 * as before this batch. The default is deliberately SEALED and is also the
 * pre-init value, so readers before `initFromDisk` (and every spec JVM) get a
 * deterministic answer that never depends on the host machine's config.
 *
 * Unseal = set `"features": { "friends": true }` in nebflow.json + restart the
 * instance (+ reload the page). Sealing is conditionalization only — zero
 * deletion: tool bodies, the static sets (`NebulaOrchestrationTools`,
 * `NebulaExclusiveTools`), the registry mappings and the REST face are all
 * untouched, and every locale key survives.
 *
 * Test seam: specs that exercise the friend/group legs lift the seal with
 * `testUnseal()` / `testReseal()` — REF-COUNTED, because sbt runs suites in
 * parallel and a plain boolean setter would let one suite's restore flip the
 * latch under another still-running suite. Production code never calls the
 * test seam; `initFromDisk()` is called exactly once at boot
 * (`GatewayMain.runGateway`).
 */
object FriendsSeal:

  private val logger = NebflowLogger(getClass)

  /** Base state from the boot-time config read. Default = sealed (see class
    * doc: the value must be deterministic before init and on every spec JVM). */
  @volatile private var enabledBase: Boolean = false

  /** Spec-only unseal refcount (never touched by production code). */
  private val unsealRequests = new java.util.concurrent.atomic.AtomicInteger(0)

  /** true = the friends feature is sealed (the default posture). (Named
    * `isSealed` because `sealed` is a Scala 3 soft keyword.) */
  def isSealed: Boolean = unsealRequests.get() == 0 && !enabledBase

  /** Boot latch (GatewayMain.runGateway): read `<home>/nebflow.json` once and
    * latch `features.friends`. Absent key / false / unparseable => sealed.
    * Same dual-read path as `ConfigService` (`PathUtil.configJsonReadPath`). */
  def initFromDisk(): Unit =
    val enabled =
      try
        val path = PathUtil.configJsonReadPath(PathUtil.dataRoot)
        if !os.exists(path) then false
        else parseEnabled(os.read(path))
      catch case _: Exception => false
    enabledBase = enabled
    val state = if enabled then "unsealed" else "sealed"
    logger.info(s"[friends-seal] features.friends latch: $state (startup latch — a config edit takes effect on restart)")

  /** Pure config-parse half of the latch (spec-visible; `initFromDisk` is its
    * only production caller): ONLY the literal boolean `true` unpins the seal —
    * absent key, `false`, a wrong-typed value, or an unparseable file all stay
    * sealed (the default posture never depends on input shape). */
  def parseEnabled(configJson: String): Boolean =
    parse(configJson).toOption
      .flatMap(_.hcursor.downField("features").downField("friends").as[Boolean].toOption)
      .getOrElse(false)

  /** Spec seam: lift the seal (ref-counted). Every `testUnseal()` must be
    * paired with `testReseal()` (guarantee/finally) — see `FriendsSealKit`
    * in test scope, the single wrapper specs should use. */
  def testUnseal(): Unit = unsealRequests.incrementAndGet()

  /** Spec seam: drop one unseal request (pairs with `testUnseal`). */
  def testReseal(): Unit = unsealRequests.decrementAndGet()

end FriendsSeal
