package nebflow.social

import cats.effect.IO
import nebflow.bridge.BridgeManager
import nebflow.shared.{NebflowLogger, SessionMeta}

import scala.concurrent.duration.*

/**
 * 绑定即通 auto-wire (weixin bind-through batch, 2026-10-11) — the two
 * follow-up legs the engine fires the moment a weixin-ilink scan-bind lands,
 * so a successful QR scan needs ZERO manual edits afterwards:
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
 *      located RELATIVE to the engine run directory — never an absolute path;
 *      not found ⇒ one WARN, the leg skips.
 *
 * Composition: [[productionActivate]] is the `activate` chain
 * [[WeixinIlinkScanBind]] receives from the gateway wiring — the pre-existing
 * sync leg FIRST (`WeixinIlinkBridgePlugin.sync`, its raise still flips the
 * binding to `Failed`, byte-for-byte the old semantics), then the two new
 * legs. 🔴 EACH new leg is TOTAL (every failure collapses into one WARN line
 * inside the leg): a broken leg can never flip a finished binding off `Done`,
 * and can never fail the activate chain the sync leg owns.
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
    * absolute hardcode). `None` ⇒ the caller WARNs and skips. */
  def resolveScript(pwd: os.Path): Option[os.Path] =
    val candidate = pwd / ScriptRelPath
    if os.isFile(candidate) then Some(candidate) else None

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
   * Leg ② — sidecar plist regenerate + restart through the repo installer.
   * TOTAL by the same rule: a missing script, a nonzero exit, a runner raise
   * and a timeout are all single WARN lines. Success logs the script's own
   * output (plist path + agent state — the script prints both; it prints no
   * secret material on the install face).
   */
  def provisionLeg(
      root: os.Path,
      gatewayPort: Int,
      runner: Runner = liveRunner,
      pwd: os.Path = os.pwd
  ): IO[Unit] =
    resolveScript(pwd) match
      case None =>
        logger.warn(
          s"weixin bind-through: ${ScriptRelPath.toString} not found under $pwd — sidecar provision skipped")
      case Some(script) =>
        val env = envMatrix(root, gatewayPort)
        runner(script, env)
          .flatMap {
            case Left(reason) =>
              logger.warn(s"weixin bind-through: sidecar provision could not run ($reason) — contained")
            case Right((code, output)) =>
              if code == 0 then
                logger.info(
                  s"weixin bind-through: sidecar provisioned via $script (restart issued) — ${oneLine(output)}")
              else
                logger.warn(
                  s"weixin bind-through: sidecar provision failed (exit $code) — ${oneLine(output)}")
          }
          .handleErrorWith(e =>
            logger.warn(
              s"weixin bind-through: sidecar provision leg failed (${e.getClass.getSimpleName}: " +
                s"${Option(e.getMessage).getOrElse("")}) — contained"))

  /**
   * The activation chain the gateway wiring hands to [[WeixinIlinkScanBind]]:
   * the pre-existing sync leg FIRST (its raise still lands as a `Failed`
   * binding — the old semantics byte-for-byte), then the two new legs, each
   * total. The shim writes the host account file BEFORE the login reports
   * done, so by the time this chain runs the account is on disk — the
   * activate-then-provision order is the safe one (the restart makes msgloop
   * re-read it).
   */
  def productionActivate(
      bridgeManager: Option[BridgeManager],
      root: os.Path,
      listSessions: () => IO[List[SessionMeta]],
      gatewayPort: Int,
      runner: Runner = liveRunner,
      pwd: os.Path = os.pwd
  ): IO[Unit] =
    bridgeManager.fold(IO.unit)(m => WeixinIlinkBridgePlugin.sync(m, root)) *>
      defaultSessionLeg(root, listSessions) *>
      provisionLeg(root, gatewayPort, runner, pwd)

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
