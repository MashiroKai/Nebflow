package nebflow.core

import io.circe.parser.parse
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * friends-seal latch (friendseal batch, 2026-09-25; DEFAULT FLIPPED by the
 * 功能开放批， author order 2026-10-30 18:22 verbatim: 「生图功能以及 消息 联系
 * 功能怎么还没开放出来？直接开放出来。」) — the backend side of the ONE existing
 * `features.friends` config key (three layers, same source):
 *
 *   1. frontend entry visibility — `featureFlags.js` (release re-seal via the
 *      opt-in `--strip-friends` build switch; runtime reads the same key via
 *      WS `configData`),
 *   2. tool registration face — `AgentCore.fixedToolsFor` filters `ListFriends`
 *      out of the Nebula delivery set when sealed (this latch),
 *   3. tool description/schema — `FriendMessageTool`'s two-state
 *      description/inputSchema and the fail-closed `FRIENDS_SEALED` call gate
 *      on its friend/group/local arms (this latch), plus the seal-aware tail of
 *      `MailTool.targetMissingMessage`.
 *
 * Semantics: STARTUP LATCH — `initFromDisk()` reads `<home>/nebflow.json` once
 * when the gateway boots; a config edit takes effect on RESTART (mirrors the
 * frontend's per-boot decision in `featureFlags.js`). POST-FLIP posture: the
 * feature is OPEN by default — ONLY the literal boolean `false` under
 * `features.friends` seals it; an absent key, `true`, a wrong-typed value, or
 * an unparseable file all leave it UNSEALED (a product feature must not die
 * silently on a config typo). Sealing is conditionalization only — zero
 * deletion: tool bodies, the static sets (`NebulaOrchestrationTools`,
 * `NebulaOrchestrationToolsExpectedSize`), the registry mappings and the REST
 * face are all untouched, and every locale key survives.
 *
 * Unseal = remove `"features": { "friends": false }` from nebflow.json (or set
 * it to true) + restart the instance (+ reload the page). Re-seal = set it to
 * `false` + restart.
 *
 * Test seams (specs only; production code never calls them):
 *   - `testUnseal()` / `testReseal()` — the ORIGINAL ref-counted lift pair
 *     (friendseal batch). After the flip they matter only when the latch was
 *     config-sealed; suites that lift the seal to exercise the friend/group
 *     legs keep using them unchanged.
 *   - `testForceSeal()` / `testForceUnseal()` — NEW ref-counted pair that
 *     forces the SEALED posture regardless of config (deterministic
 *     sealed-state specs under the flipped default). REF-COUNTED, because sbt
 *     runs suites in parallel and a plain boolean setter would let one suite's
 *     restore flip the latch under another still-running suite.
 * `initFromDisk()` is called exactly once at boot (`GatewayMain.runGateway`).
 */
object FriendsSeal:

  private val logger = NebflowLogger(getClass)

  /**
   * Base state from the boot-time config read. Default = UNSEALED (post-flip
   * posture; the value must be deterministic before init and on every spec
   * JVM — see class doc).
   */
  @volatile private var enabledBase: Boolean = true

  /** Spec-only unseal refcount (never touched by production code). */
  private val unsealRequests = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Spec-only force-seal refcount (功能开放批； never touched by production). */
  private val forceSealRequests = new java.util.concurrent.atomic.AtomicInteger(0)

  /**
   * true = the friends feature is sealed. (Named
   * `isSealed` because `sealed` is a Scala 3 soft keyword.)
   */
  def isSealed: Boolean =
    forceSealRequests.get() > 0 || (!enabledBase && unsealRequests.get() == 0)

  /**
   * Boot latch (GatewayMain.runGateway): read `<home>/nebflow.json` once and
   * latch `features.friends`. Absent key / true / wrong type => UNSEALED;
   * literal `false` => sealed; unreadable file => UNSEALED + WARN (post-flip
   * posture: a product feature must not silently die on a broken config read —
   * the WARN keeps the fault loud).
   * Same dual-read path as `ConfigService` (`PathUtil.configJsonReadPath`).
   */
  def initFromDisk(): Unit =
    val enabled =
      try
        val path = PathUtil.configJsonReadPath(PathUtil.dataRoot)
        if !os.exists(path) then true
        else parseEnabled(os.read(path))
      catch
        case e: Exception =>
          logger.warnSync(s"[friends-seal] nebflow.json unreadable (${e.getClass.getSimpleName}) — defaulting UNSEALED")
          true
    enabledBase = enabled
    val state = if enabled then "unsealed" else "sealed"
    logger.info(
      s"[friends-seal] features.friends latch: $state (startup latch — a config edit takes effect on restart)"
    )

  /**
   * Pure config-parse half of the latch (spec-visible; `initFromDisk` is its
   * only production caller): POST-FLIP semantics — ONLY the literal boolean
   * `false` seals; absent key, `true`, a wrong-typed value, or an unparseable
   * file all stay UNSEALED (the open posture never depends on input shape).
   */
  def parseEnabled(configJson: String): Boolean =
    parse(configJson).toOption
      .flatMap(_.hcursor.downField("features").downField("friends").as[Boolean].toOption)
      .getOrElse(true)

  /**
   * Spec seam: lift the seal (ref-counted; the original friendseal pair).
   * Every `testUnseal()` must be paired with `testReseal()` (guarantee/finally)
   * — see `FriendsSealKit` in test scope, the single wrapper specs should use.
   */
  def testUnseal(): Unit = unsealRequests.incrementAndGet()

  /** Spec seam: drop one unseal request (pairs with `testUnseal`). */
  def testReseal(): Unit = unsealRequests.decrementAndGet()

  /**
   * Spec seam (功能开放批): force the SEALED posture for the duration of a
   * spec region, regardless of the config-derived base state. Pair with
   * `testForceUnseal()` in guarantee/finally.
   */
  def testForceSeal(): Unit = forceSealRequests.incrementAndGet()

  /** Spec seam: drop one force-seal request (pairs with `testForceSeal`). */
  def testForceUnseal(): Unit = forceSealRequests.decrementAndGet()

end FriendsSeal
