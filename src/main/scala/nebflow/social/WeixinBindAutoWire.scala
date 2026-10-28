package nebflow.social

import cats.effect.IO
import cats.syntax.all.*
import nebflow.bridge.BridgeManager
import nebflow.shared.{NebflowLogger, SessionMeta}

import scala.concurrent.duration.*

/**
 * 绑定即通 auto-wire (weixin bind-through batch, 2026-10-11; transactional
 * hardening batch, 2026-10-20) — the two follow-up legs the engine fires the
 * moment a weixin-ilink scan-bind lands, so a successful QR scan needs ZERO
 * manual edits afterwards:
 *
 *   ① defaultSession leg — write the binding-time latest active session onto
 *      the weixin-ilink card's `defaultSessionId` (the channel-entry sibling
 *      key the feishu card ships, [[SocialChannels.DefaultSessionKey]]; the
 *      write path is the SAME `SocialChannels.setDefaultSessionId` the REST
 *      face uses, so field position/semantics are identical by construction).
 *      No session exists yet ⇒ nothing is written and one INFO line records
 *      it (the REST face can backfill; the auto-bind arm is untouched).
 *   ② provision leg — regenerate/update the sidecar LaunchAgent through the
 *      REPO script `tools/weixin-sidecar/install-launchd.sh` (a bind-success
 *      restart is what makes the sidecar's msgloop re-read the host account
 *      directory and select the freshly bound account). The script/repo are
 *      located RELATIVE to the engine run directory — never an absolute path.
 *
 * 🔴 TRANSACTIONAL (bind-transactional 批, 2026-10-20 — the 2026-10-20
 * incident: the provision subprocess failed with launchd bootstrap exit 5 —
 * port 8787 still held by the previous shim the script had just killed — and
 * the OLD code logged one WARN and declared the binding Done: card carried
 * `defaultSessionId`, the bridge seam was up, the LaunchAgent was NOT loaded.
 * A half-bound state, and every reply to that session died silently on
 * `[no-return-address]`). The provision leg no longer gives up after one run:
 *
 *   - RETRY: a failed attempt (nonzero exit, transport `Left`, OR a failed
 *     liveness confirmation) waits for the sidecar port to actually RELEASE
 *     (`lsof` poll, [[PortFreeWait]] budget) plus an exponential backoff
 *     (1s/2s/4s), then re-runs the installer — [[MaxProvisionAttempts]]
 *     attempts in total (the first run + 3 retries).
 *   - LIVENESS: an installer exit 0 is NOT success until the sidecar proves
 *     alive — the port shows a LISTENer ([[PortProbe]]) AND answers
 *     `/healthz` ([[HealthProbe]]) within [[LivenessWait]] (launchd's
 *     ThrottleInterval is 10s; the first start can legitimately lag). "launchd
 *     says loaded but nothing listens" is an attempt failure, exactly the
 *     half-bind shape this batch exists to kill.
 *   - ROLLBACK: attempts exhausted ⇒ [[productionActivate]] rolls the binding
 *     back ([[rollbackBinding]]: card `defaultSessionId` cleared, bridge
 *     routing revoked via `BridgeManager.unregister`, the card's `bindError`
 *     set with the per-attempt failure steps) and ONLY THEN raises — the
 *     scan-bind state machine lands `Failed` with the same steps, so the panel
 *     shows an explicit error instead of a silent half-bind. 要么全绑上，要么
 *     明确告诉用户没绑上 — a half-bound state is unreachable.
 *   - SUCCESS clears any stale `bindError` from a previous failed attempt.
 *
 * The provision leg's environment matrix (contract v2, dispatcher ruling —
 * executed verbatim; every value optional script-side, provided ⇒ written
 * into the plist):
 *
 *   - `WEIXIN_SIDECAR_PORT`        the card `sidecar_url`'s port, else the
 *                                  port of [[WeixinIlinkScanBind.SidecarDefaultBase]]
 *                                  (the existing default constant — never a
 *                                  new literal);
 *   - `WEIXIN_MSGLOOP_INGRESS_URL` the engine ingress full URL
 *                                  (`http://127.0.0.1:<gatewayPort>/api/social/channels/weixin-ilink/ingress`);
 *   - `WEIXIN_MSGLOOP_TOKEN_FILE`  THIS instance's ingress token file
 *                                  ([[WeixinIngressToken.path]] — the
 *                                  msgloop-leg-specific spelling, still read
 *                                  shim-side);
 *   - `NEBFLOW_HOME`               the instance data root (the outbound
 *                                  `send.mjs` default token path
 *                                  `<NEBFLOW_HOME>/secrets/weixin-ilink-send-token`
 *                                  lands correctly with it).
 *
 * 🔴 Forbidden zone (author fact ①, 2026-10-08): `WEIXIN_SEND_TOKEN_FILE` /
 * `WEIXIN_SEND_TOKEN` are the SHARED leg face of msgloop and the outbound
 * `send.mjs` — if a value reached the plist under either spelling it would
 * hijack the OUTBOUND leg onto the ingress token ⇒ 403. The engine never
 * injects them with a value, and additionally pins BOTH to the empty string
 * so an inherited engine-process value cannot leak through the merged
 * environment into the plist (the script trims and treats empty as "not
 * provided"). The port of the sidecar is the ONLY value sourced from the
 * card; `OPENCLAW_STATE_DIR` is deliberately NOT injected (the sidecar
 * resolves the host account dir itself). The engine invokes the script
 * WITHOUT `--print-config` — the neutral config face exists for humans and
 * rehearsals; the install face is the engine's.
 */
object WeixinBindAutoWire:

  private val logger = NebflowLogger.forName("nebflow.social.weixin-bind-autowire")

  /** The repo-relative installer, resolved against the engine run directory. */
  val ScriptRelPath: os.RelPath = os.rel / "tools" / "weixin-sidecar" / "install-launchd.sh"

  // ── env key names (contract v2 spellings — the msgloop-leg names) ──
  val KeySidecarPort = "WEIXIN_SIDECAR_PORT"
  val KeyIngressUrl = "WEIXIN_MSGLOOP_INGRESS_URL"
  val KeyMsgloopTokenFile = "WEIXIN_MSGLOOP_TOKEN_FILE"
  val KeyNebflowHome = "NEBFLOW_HOME"

  /** The forbidden plist spellings (author fact ①) — always injected EMPTY so
    * the merged environment can never hand the script a value under them. */
  val ForbiddenKeys: List[String] = List("WEIXIN_SEND_TOKEN_FILE", "WEIXIN_SEND_TOKEN")

  /** The provision subprocess is bounded (launchd bootstrap + the script's own
    * two 1s sleeps settle well inside this; a hung subprocess must never pin
    * the binding fiber). */
  val ProcTimeout: FiniteDuration = 120.seconds

  // ───────────── bind-transactional 批 (2026-10-20): the retry budget ─────────────

  /** Total provision attempts: the first run + 3 retries (指数退避×3). */
  val MaxProvisionAttempts: Int = 4

  /** Exponential backoff base: attempt N's pre-retry wait is base × 2^(N-1)
    * (1s, 2s, 4s — the launchd-side kill/bootout races settle in seconds). */
  val BackoffBase: FiniteDuration = 1.second

  /** How long one retry waits for the sidecar port to actually release before
    * re-running the installer (lsof poll, [[PortPollInterval]] steps). */
  val PortFreeWait: FiniteDuration = 10.seconds

  /** The liveness confirmation budget after an installer exit 0 (launchd
    * ThrottleInterval = 10s; the first start can legitimately lag one notch). */
  val LivenessWait: FiniteDuration = 15.seconds

  /** The poll cadence of both bounded waits. */
  val PortPollInterval: FiniteDuration = 500.millis

  /** Deterministic step counts (budget ÷ cadence) — the loops are bounded by
    * ITERATIONS, never by wall-clock reads, so an injected zero-cost sleeper
    * stays fast and production gets its full budget back. */
  val PortFreePollSteps: Int = (PortFreeWait / PortPollInterval).toInt
  val LivenessPollSteps: Int = (LivenessWait / PortPollInterval).toInt

  /** `true` = something LISTENs on the port (production: `lsof -sTCP:LISTEN`).
    * The SAME probe reads both waits: port-free = `false`, liveness = `true`. */
  type PortProbe = Int => IO[Boolean]

  /** `true` = the sidecar answers `/healthz` (the shim's unauthenticated
    * liveness face — the installer's own probe suggestion). */
  type HealthProbe = Int => IO[Boolean]

  /** The wait seam (production `IO.sleep`; offline specs inject a recorder). */
  type Sleeper = FiniteDuration => IO[Unit]

  /**
   * The failure the scan-bind state machine sees when the provision attempts
   * are exhausted. The message carries the per-attempt failure steps — the
   * panel error must say WHERE it died, never a bare class name.
   */
  final class ProvisionFailedException(steps: String)
      extends RuntimeException(
        s"provision failed after $MaxProvisionAttempts attempts ($steps) — binding rolled back " +
          "(defaultSessionId cleared, bridge seam revoked)"
      )

  /**
   * The runner seam: `bash <script>` with the injected env, ONE bounded
   * attempt. `Left(reason)` = the subprocess could not be run to completion
   * (exec failure, timeout); `Right((exitCode, output))` = the script ran,
   * `exitCode` says whether it succeeded. Production = [[liveRunner]]; the
   * offline spec injects a recorder — zero real launchd in tests.
   */
  type Runner = (os.Path, Map[String, String]) => IO[Either[String, (Int, String)]]

  // ───────────────────────── pure helpers (spec-pinned) ─────────────────────────

  /** The sidecar control port: the card's `sidecar_url` port, else the port
    * of the existing [[WeixinIlinkScanBind.SidecarDefaultBase]] constant. A
    * portless/unparseable card URL falls back to the constant (the same URL
    * the scan-bind control leg dials). */
  def sidecarPort(root: os.Path): Int =
    parsePort(WeixinOutboundAdapter.sidecarBase(root))
      .orElse(parsePort(WeixinIlinkScanBind.SidecarDefaultBase))
      .getOrElse(-1)

  private def parsePort(url: String): Option[Int] =
    try
      val p = java.net.URI.create(url.trim).getPort
      if p > 0 then Some(p) else None
    catch case _: Exception => None

  /** The engine ingress full URL the shim POSTs to (the same port the gateway
    * itself listens on — `RestApiCtx.gatewayPort` upstream). */
  def ingressUrl(gatewayPort: Int): String =
    s"http://127.0.0.1:$gatewayPort/api/social/channels/${WeixinIlinkBridgePlugin.Name}/ingress"

  /**
   * The exact environment the provision subprocess runs under: the four
   * contract values, plus BOTH forbidden keys pinned to the empty string (the
   * inherited environment is propagated under this map — an engine-process
   * `WEIXIN_SEND_TOKEN[_FILE]` would otherwise ride through into the plist).
   */
  def envMatrix(root: os.Path, gatewayPort: Int): Map[String, String] =
    Map(
      KeySidecarPort -> sidecarPort(root).toString,
      KeyIngressUrl -> ingressUrl(gatewayPort),
      KeyMsgloopTokenFile -> WeixinIngressToken.path(root).toString,
      KeyNebflowHome -> root.toString
    ) ++ ForbiddenKeys.map(_ -> "")

  /** Resolve the installer relative to the engine run directory (never an
    * absolute hardcode). `None` ⇒ the caller degrades to the liveness arbiter. */
  def resolveScript(pwd: os.Path): Option[os.Path] =
    val candidate = pwd / ScriptRelPath
    if os.isFile(candidate) then Some(candidate) else None

  // ───────────────── the two bounded waits (spec-pinned shapes) ─────────────────

  /**
   * Liveness confirmation: LISTEN **and** `/healthz` within [[LivenessPollSteps]]
   * polls. Deterministic iteration bound — no wall-clock reads, so an injected
   * zero-cost sleeper is fast and production sleeps its real [[PortPollInterval]]
   * per step.
   */
  private[social] def awaitLiveness(
      port: Int,
      portProbe: PortProbe,
      healthProbe: HealthProbe,
      sleeper: Sleeper
  ): IO[Boolean] =
    List.range(0, LivenessPollSteps).foldLeft(IO.pure(false)) { (acc, _) =>
      acc.flatMap {
        case true => IO.pure(true)
        case false =>
          portProbe(port).flatMap {
            case false => sleeper(PortPollInterval).as(false)
            case true =>
              healthProbe(port).flatMap {
                case true => IO.pure(true)
                case false => sleeper(PortPollInterval).as(false)
              }
          }
      }
    }

  /**
   * The port-release wait between attempts: poll until NOTHING listens (the
   * just-killed previous shim's socket can linger past the script's 1s sleep
   * — the exact race behind bootstrap exit 5). `true` = released; `false` =
   * still occupied after [[PortFreePollSteps]] (the retry runs anyway — the
   * next installer run re-stops and re-bootstraps; giving up on the WAIT is
   * not giving up on the attempt).
   */
  private[social] def awaitPortFree(port: Int, portProbe: PortProbe, sleeper: Sleeper): IO[Boolean] =
    List.range(0, PortFreePollSteps).foldLeft(IO.pure(false)) { (acc, _) =>
      acc.flatMap {
        case true => IO.pure(true)
        case false => portProbe(port).flatMap(busy => if busy then sleeper(PortPollInterval).as(false) else IO.pure(true))
      }
    }

  // ───────────────────────── the two legs ─────────────────────────

  /**
   * Leg ① — defaultSessionId onto the weixin-ilink card. The value is the
   * binding-time latest active session ([[SessionMeta.updatedAt]] max — the
   * same time field `SessionStore.listSessions` orders by). TOTAL: a missing
   * session, a rejected write and a raising session store all collapse into
   * log lines; the list is a THUNK so a spec-harness null store costs nothing
   * until a binding actually lands.
   */
  def defaultSessionLeg(root: os.Path, listSessions: () => IO[List[SessionMeta]]): IO[Unit] =
    listSessions()
      .flatMap(metas => IO.pure(metas.maxByOption(_.updatedAt).map(_.id)))
      .flatMap {
        case None =>
          logger.info(
            "weixin bind-through: no active session at bind time — defaultSessionId not written " +
              "(the REST default-session face can set it later)")
        case Some(sid) =>
          IO.blocking(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some(sid)))
            .flatMap {
              case Right(_) =>
                logger.info(
                  s"weixin bind-through: defaultSessionId=$sid written to the ${WeixinIlinkBridgePlugin.Name} card " +
                    "(latest active session at bind time)")
              case Left(err) =>
                logger.warn(
                  s"weixin bind-through: defaultSessionId write failed (${renderFailure(err)}) — " +
                    "the card stays without a default session")
            }
      }
      .handleErrorWith(e =>
        logger.warn(
          s"weixin bind-through: defaultSession leg failed (${e.getClass.getSimpleName}: " +
            s"${Option(e.getMessage).getOrElse("")}) — contained"))

  /**
   * Leg ② — sidecar plist regenerate + restart through the repo installer,
   * TRANSACTIONAL (bind-transactional 批, 2026-10-20): up to
   * [[MaxProvisionAttempts]] attempts, a port-release wait + exponential
   * backoff between them, and a liveness confirmation ([[awaitLiveness]]) on
   * every installer success — an exit 0 with a dead sidecar is a FAILED
   * attempt, never a bound one.
   *
   * Returns `Right(())` = the sidecar is provisioned AND alive; `Left(steps)`
   * = every attempt's failure step (the exact strings the rollback writes to
   * the card's `bindError` and the scan-bind state surfaces). The leg itself
   * still never raises — the CALLER ([[productionActivate]]) owns the
   * raise-after-rollback order.
   *
   * The installer missing is NOT a blind failure: a previously-provisioned
   * sidecar keeps the binding alive, so the LIVENESS ARBITER decides — alive ⇒
   * the leg skips with a WARN (the old contained-skip semantics, kept for the
   * case where things actually work); dead ⇒ `Left` (there is no installer to
   * fix it and no sidecar serving: half-bind territory).
   */
  def provisionLeg(
      root: os.Path,
      gatewayPort: Int,
      runner: Runner = liveRunner,
      pwd: os.Path = os.pwd,
      portProbe: PortProbe = livePortProbe,
      healthProbe: HealthProbe = liveHealthProbe,
      sleeper: Sleeper = d => IO.sleep(d)
  ): IO[Either[String, Unit]] =
    val port = sidecarPort(root)
    resolveScript(pwd) match
      case None =>
        logger.warn(
          s"weixin bind-through: ${ScriptRelPath.toString} not found under $pwd — " +
            "arbitrating on sidecar liveness instead")
        awaitLiveness(port, portProbe, healthProbe, sleeper).flatMap {
          case true =>
            logger.warn(
              "weixin bind-through: installer missing but the sidecar answers /healthz — provision skipped, " +
                "the running sidecar stands in"
            ).as(Right(()))
          case false =>
            logger.warn(
              "weixin bind-through: installer missing AND no live sidecar — provision failed"
            ).as(Left("installer not found and no live sidecar (no LISTEN/healthz)"))
        }
      case Some(script) =>
        val env = envMatrix(root, gatewayPort)
        def oneAttempt(n: Int): IO[Option[String]] =
          runner(script, env)
            .flatMap {
              case Left(reason) => IO.pure(Some(s"attempt $n: could not run installer ($reason)"))
              case Right((0, output)) =>
                logger.info(
                  s"weixin bind-through: attempt $n installed via $script — confirming sidecar liveness"
                ) *>
                  awaitLiveness(port, portProbe, healthProbe, sleeper).flatMap {
                    case true =>
                      logger.info(
                        s"weixin bind-through: attempt $n sidecar alive (LISTEN + /healthz ok) — " +
                          s"${oneLine(output)}"
                      ).as(None)
                    case false =>
                      IO.pure(Some(
                        s"attempt $n: installer exit 0 but sidecar not alive " +
                          s"(no LISTEN+healthz within ${LivenessWait.toString})"
                      ))
                  }
              case Right((code, output)) =>
                IO.pure(Some(s"attempt $n: installer exit $code (${oneLine(output)})"))
            }
            .handleErrorWith(e =>
              IO.pure(Some(s"attempt $n: provision leg raised (${e.getClass.getSimpleName}: " +
                s"${Option(e.getMessage).getOrElse("")})")))
        def loop(n: Int, acc: List[String]): IO[Either[String, Unit]] =
          oneAttempt(n).flatMap {
            case None => IO.pure(Right(()))
            case Some(fail) =>
              if n >= MaxProvisionAttempts then IO.pure(Left((acc :+ fail).mkString("; ")))
              else
                awaitPortFree(port, portProbe, sleeper).flatMap { released =>
                  (if released then IO.unit
                   else
                     logger.warn(
                       s"weixin bind-through: port $port still occupied after ${PortFreeWait.toString} — retrying anyway"
                     )) *>
                    sleeper(BackoffBase * (1L << (n - 1))) *>
                    loop(n + 1, acc :+ fail)
                }
          }
        logger.info(
          s"weixin bind-through: provisioning sidecar via $script (up to $MaxProvisionAttempts attempts, " +
            s"liveness-gated)"
        ) *> loop(1, Nil)

  /**
   * The rollback arm — the bind-transactional 批's own words: 清卡片
   * defaultSessionId、桥路由表撤销、面板通道态显式报错. THREE writes, each
   * TOTAL (a rollback step's own failure is a WARN, never a raise — the
   * caller's raise lands either way; a partial rollback is logged, never
   * hidden). The card's `bindError` carries the per-attempt steps verbatim, so
   * the panel (`GET /api/social/channels` → `channels[].bindError`) and the
   * scan-bind `failed` reason both name the exact failed step.
   */
  def rollbackBinding(root: os.Path, bridgeManager: Option[BridgeManager], steps: String): IO[Unit] =
    val reason = s"provision failed after retries: $steps"
    // ① the card's defaultSessionId — the value leg ① wrote this bind
    IO.blocking(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, None))
      .flatMap {
        case Right(_) =>
          logger.warn("weixin bind-through rollback: defaultSessionId cleared from the card")
        case Left(err) =>
          logger.warn(s"weixin bind-through rollback: could not clear defaultSessionId (${renderFailure(err)})")
      }
      .handleErrorWith(e =>
        logger.warn(s"weixin bind-through rollback: defaultSessionId clear raised (${e.getClass.getSimpleName})")) *>
      // ② the bridge seam — unregister stops the plugin (routing table, pending
      // buffers, ask ledger all cleared) and removes it, byte-for-byte the
      // disabled-card exit path
      bridgeManager.fold(IO.unit)(m =>
        m.unregister(WeixinIlinkBridgePlugin.Name).handleErrorWith(e =>
          logger.warn(
            s"weixin bind-through rollback: bridge unregister failed (${e.getClass.getSimpleName}: " +
              s"${Option(e.getMessage).getOrElse("")})"))) *>
      // ③ the explicit panel error
      IO.blocking(SocialChannels.setBindError(root, WeixinIlinkBridgePlugin.Name, Some(reason)))
        .flatMap {
          case Right(_) =>
            logger.warn(s"weixin bind-through ROLLED BACK — bindError written to the card: $reason")
          case Left(err) =>
            logger.warn(s"weixin bind-through rollback: could not write bindError (${renderFailure(err)})")
        }
        .handleErrorWith(e =>
          logger.warn(s"weixin bind-through rollback: bindError write raised (${e.getClass.getSimpleName})"))

  /**
   * The activation chain the gateway wiring hands to [[WeixinIlinkScanBind]]:
   * the pre-existing sync leg FIRST (its raise still lands as a `Failed`
   * binding — the old semantics byte-for-byte), then the two new legs. The
   * shim writes the host account file BEFORE the login reports done, so by the
   * time this chain runs the account is on disk — the activate-then-provision
   * order is the safe one (the restart makes msgloop re-read it).
   *
   * 🔴 Transactional tail: a provision outcome of `Left` ROLLS THE BINDING
   * BACK first ([[rollbackBinding]] — card defaultSessionId cleared, bridge
   * routing revoked, `bindError` written) and only then raises, so the
   * scan-bind state lands `Failed` with the failure steps in its reason. The
   * success arm clears a stale `bindError` from a previous failed attempt (a
   * contained write — its own failure never un-does a finished binding).
   */
  def productionActivate(
      bridgeManager: Option[BridgeManager],
      root: os.Path,
      listSessions: () => IO[List[SessionMeta]],
      gatewayPort: Int,
      runner: Runner = liveRunner,
      pwd: os.Path = os.pwd,
      portProbe: PortProbe = livePortProbe,
      healthProbe: HealthProbe = liveHealthProbe,
      sleeper: Sleeper = d => IO.sleep(d)
  ): IO[Unit] =
    bridgeManager.fold(IO.unit)(m => WeixinIlinkBridgePlugin.sync(m, root)) *>
      defaultSessionLeg(root, listSessions) *>
      provisionLeg(root, gatewayPort, runner, pwd, portProbe, healthProbe, sleeper).flatMap {
        case Right(()) =>
          IO.blocking(SocialChannels.setBindError(root, WeixinIlinkBridgePlugin.Name, None)).flatMap {
            case Right(_) => IO.unit
            case Left(err) =>
              logger.warn(
                s"weixin bind-through: bound, but the stale bindError could not be cleared (${renderFailure(err)})")
          }
        case Left(steps) =>
          rollbackBinding(root, bridgeManager, steps) *> IO.raiseError(ProvisionFailedException(steps))
      }

  // ───────────────────────── internals ─────────────────────────

  /**
   * The production runner: `bash <script>` with the injected env MERGED over
   * the inherited environment (`propagateEnv = true` — the script needs the
   * inherited PATH for `node`/`lsof`/`launchctl`; the injected map's empty
   * forbidden keys override any inherited value). One bounded attempt; every
   * transport failure (including the timeout, which destroys the child) is a
   * `Left` reading — never a raise past the seam.
   */
  private val liveRunner: Runner = (script, env) =>
    IO.blocking {
      val res = os.proc("bash", script).call(
        env = env,
        propagateEnv = true,
        check = false,
        timeout = ProcTimeout.toMillis,
        stdout = os.Pipe,
        stderr = os.Pipe
      )
      Right((res.exitCode, s"${res.err.text().trim}\n${res.out.text().trim}".trim))
    }.handleError(e =>
      Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"))

  /**
   * The production port probe: `lsof -ti tcp:<port> -sTCP:LISTEN` — exit 0
   * with output = something listens. Contained: a missing/broken lsof reads as
   * "no listener" (the retry path is the safe direction on a blind probe).
   */
  val livePortProbe: PortProbe = port =>
    IO.blocking {
      val res = os.proc("lsof", "-ti", s"tcp:$port", "-sTCP:LISTEN").call(
        check = false,
        timeout = 5000,
        stdout = os.Pipe,
        stderr = os.Pipe
      )
      res.exitCode == 0 && res.out.text().trim.nonEmpty
    }.handleError(_ => false)

  /**
   * The production health probe: one `GET http://127.0.0.1:<port>/healthz`
   * (the shim's unauthenticated liveness face), 2s timeout — any 2xx counts.
   * Contained: every transport failure reads `false`.
   */
  val liveHealthProbe: HealthProbe = port =>
    IO.blocking {
      val client = java.net.http.HttpClient.newHttpClient()
      val req = java.net.http.HttpRequest
        .newBuilder(java.net.URI.create(s"http://127.0.0.1:$port/healthz"))
        .timeout(java.time.Duration.ofSeconds(2))
        .GET()
        .build()
      val code = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
      code / 100 == 2
    }.handleError(_ => false)

  /** One log line out of the script's multi-line output (bounded tail). */
  private def oneLine(output: String): String =
    val compact = output.trim.replaceAll("""\s+""", " ")
    if compact.length <= 400 then compact else compact.take(400) + "…"

  private def renderFailure(err: SocialChannels.Failure): String = err match
    case SocialChannels.Failure.InvalidField(f, r) => s"invalid field $f: $r"
    case SocialChannels.Failure.SecretMode(f, r)   => s"could not store $f: $r"
    case SocialChannels.Failure.Io(r)              => s"storage error: $r"
    case SocialChannels.Failure.UnknownChannel(id) => s"unknown channel $id"

end WeixinBindAutoWire
