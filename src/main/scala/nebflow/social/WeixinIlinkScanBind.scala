package nebflow.social

import cats.effect.{IO, Ref}
import cats.effect.kernel.Fiber
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

import java.util.UUID
import scala.collection.immutable.ListMap

/**
 * Weixin iLink scan-bind (weixin-scanbind batch, 2026-10-03) — the "connect by
 * scanning a QR code" main path for the `weixin-ilink` card, the same card
 * shape and wire contract the feishu card shipped ([[FeishuScanBind]]):
 * `begin` mints a scanId at once, a BACKGROUND fiber drives the login, and
 * `status` is a read-only projection of
 * `Starting → QrReady(qrUrl, userCode, expireAt) → Polling → Done | Failed`.
 *
 * 🔴 The QR leg is NOT this protocol. iLink login belongs to the official
 * ClawBot plugin side (the same side-car that posts ingest); this manager only
 * meets it at the CONTROL seam — a constructor-injected [[LoginFn]] whose
 * production implementation ([[WeixinIlinkScanBind.sidecarLogin]]) speaks HTTP
 * to the side-car's localhost control endpoint (`sidecar_url`, default
 * [[WeixinIlinkScanBind.SidecarDefaultBase]]; contract documented in the README
 * section "Weixin (iLink) channel"). No long polling of iLink, no auth headers,
 * no media decryption ever lands in this repo — the side-car owns all of it,
 * exactly as the ingest seam's Scaladoc states for the message leg.
 *
 * Shape (a deliberate structural mirror of [[FeishuScanBind]], so the frontend
 * tick code and the response contract stay uniform):
 *
 *   - `begin`  → scanId + background fiber; the response never blocks on the
 *     side-car. The fiber runs the BLOCKING [[LoginFn]] inside `IO.blocking`;
 *     the login reports progress through ONE emit callback (`LoginEvent`),
 *     bridged into IO in exactly one place (`runOnLoginThread`,
 *     `unsafeRunSync` — the same explicit-boundary shape as the feishu SDK
 *     thread and `FeishuBridgePlugin.intake`).
 *   - `status` → the registry projection. Responses NEVER carry a credential:
 *     `Done` surfaces only the bot id (an account identifier, the same
 *     displayable face as feishu's `appId`); `Failed` surfaces a reason string
 *     from our own vocabulary.
 *   - on success the fiber persists through the EXISTING write path —
 *     `SocialChannels.save` (the plaintext `bot_token` goes into the request
 *     body ONCE; the config keeps the `_ref` path) — then fires the ONE
 *     activation point (`WeixinIlinkBridgePlugin.sync` via the injected
 *     `activate` leg), so the ingest seam registers exactly as a card save
 *     would. The save is a per-field merge: a stored `allowed_ilink_user_ids`
 *     or `sidecar_url` survives the scan-bind write untouched.
 *
 * Concurrency: SINGLE-FLIGHT, identical to feishu ⑧ — a newer `begin`
 * supersedes the in-flight session (`Failed("superseded…")` + fiber cancel,
 * result path severed), and [[advance]] refuses to let a late login event
 * resurrect a terminal state.
 *
 * 🔴 Zero-secret discipline: `bot_token` exists in exactly two places — inside
 * the [[WeixinIlinkScanBind.WeixinCredentials]] value and inside the
 * `SocialChannels.save` request body. Never logged, never rendered, never
 * stored in the registry, never in any response.
 *
 * 🔴 Zero real login in tests: the seam is the injected [[LoginFn]]; fakes drive
 * the real emit callback and return real results or throw the real exception
 * families — offline, no side-car, no network.
 */
final class WeixinIlinkScanBind(
  /** Active data root (the same root `SocialChannels.save` persists under). */
  root: os.Path,
  /** The control seam. Production = [[WeixinIlinkScanBind.sidecarLogin]](root);
    * tests inject a fake. */
  loginFn: WeixinIlinkScanBind.LoginFn,
  /** The post-persist activation leg. Production wiring passes
    * `WeixinIlinkBridgePlugin.sync(manager, root)`; the offline spec injects a
    * recorder. */
  activate: IO[Unit] = IO.unit
):

  private val logger = NebflowLogger.forName("nebflow.social.weixin-scan-bind")

  import WeixinIlinkScanBind.*

  /** All known sessions, insertion-ordered (pruned to [[MaxSessions]]). */
  private val statesRef: Ref[IO, ListMap[String, State]] =
    Ref.unsafe[IO, ListMap[String, State]](ListMap.empty)

  /** The single-flight slot: the one in-flight `(scanId, fiber)`, if any. */
  private val activeRef: Ref[IO, Option[(String, Fiber[IO, Throwable, Unit])]] =
    Ref.unsafe[IO, Option[(String, Fiber[IO, Throwable, Unit])]](None)

  // ───────────────────────────── begin ─────────────────────────────
  /** Start a scan-bind session. Returns `{"scanId": …, "state": "starting"}`;
    * progress is observed through [[status]]. Never blocks on the side-car: the
    * login runs on a background fiber (`IO(blocking)`). */
  def begin(): IO[Json] =
    for
      _ <- supersede()
      scanId <- IO(UUID.randomUUID().toString)
      _ <- statesRef.update(m => prune(m.updated(scanId, State.Starting)))
      fiber <- loginFiber(scanId).start
      _ <- activeRef.set(Some((scanId, fiber)))
    yield Json.obj("scanId" -> scanId.asJson, "state" -> "starting".asJson)

  /** Single-flight: mark a still-open predecessor `Failed` and cancel its
    * fiber. The cancel runs on its own fiber — the abandoned blocking login
    * may sit in a poll sleep or a socket read that ignores interrupts; its
    * result path is severed either way (guarded state writes refuse a
    * cancelled session, and the cancelled fiber never reaches
    * persist/activate). */
  private def supersede(): IO[Unit] =
    activeRef.getAndSet(None).flatMap {
      case None => IO.unit
      case Some((oldId, fiber)) =>
        statesRef.update(m => m.updatedWith(oldId) {
          case Some(_: State.QrReady) | Some(_: State.Polling) =>
            Some(State.Failed(SupersededReason))
          case Some(State.Starting) => Some(State.Failed(SupersededReason))
          case other                => other
        }) *> fiber.cancel.start.void
    }

  /** Registry grows by one entry per begin — cap it (insertion-ordered drop of
    * the oldest entries). */
  private def prune(m: ListMap[String, State]): ListMap[String, State] =
    if m.size <= MaxSessions then m else m.drop(m.size - MaxSessions)

  private def loginFiber(scanId: String): IO[Unit] =
    IO.blocking(loginFn(LoginOptions(emit = ev => runOnLoginThread(onEvent(scanId, ev))))).attempt
      .flatMap {
        case Right(LoginResult.Done(creds)) => persistAndFinish(scanId, creds)
        case Right(LoginResult.Failed(reason)) => fail(scanId, reason)
        case Left(e: LoginExpiredException)      => fail(scanId, "expired", e)
        case Left(e: SidecarUnreachableException) => fail(scanId, "sidecar_unreachable", e)
        case Left(e: InterruptedException)       => IO.unit // cancelled mid-login
        case Left(e)                             => fail(scanId, "internal_error", e)
      }

  // ───────────────────────────── status ─────────────────────────────
  /** Read-only projection for `GET …/scan-bind/status`. `None` = unknown (or
    * pruned) scanId. The payload NEVER carries a credential — see the class
    * doc. */
  def status(scanId: String): IO[Option[Json]] =
    statesRef.get.map(_.get(scanId).map(s => stateJson(scanId, s)))

  private def stateJson(scanId: String, s: State): Json =
    val now = System.currentTimeMillis()
    def base(state: String): Json = Json.obj("scanId" -> scanId.asJson, "state" -> state.asJson)
    s match
      case State.Starting =>
        base("starting")
      case State.QrReady(url, code, expireAt) =>
        base("qr_ready").deepMerge(Json.obj(
          "qrUrl" -> url.asJson, "userCode" -> code.asJson,
          "remainSec" -> remainSec(expireAt, now).asJson))
      case State.Polling(url, code, expireAt) =>
        base("polling").deepMerge(Json.obj(
          "qrUrl" -> url.asJson, "userCode" -> code.asJson,
          "remainSec" -> remainSec(expireAt, now).asJson))
      case State.Done(botId) =>
        base("done").deepMerge(Json.obj("botId" -> botId.asJson))
      case State.Failed(reason) =>
        base("failed").deepMerge(Json.obj("error" -> reason.asJson))

  private def remainSec(expireAt: Long, now: Long): Long = math.max(0L, (expireAt - now) / 1000L)

  // ───────────────────────── login events ─────────────────────────
  /** The ONE login-thread → IO boundary (same explicit shape as the feishu SDK
    * thread): the callback fires inside the blocking login call,
    * `unsafeRunSync` keeps callback ordering strict. */
  private def runOnLoginThread(io: IO[Unit]): Unit =
    try io.unsafeRunSync()
    catch
      case _: InterruptedException => () // fibre cancelled — never break the login flow on the way out
      case e: Exception =>
        logger.warn(s"weixin scan-bind: registry update from login callback failed: ${e.getClass.getSimpleName}")

  /** Guarded state write for login callbacks: only OPEN sessions advance. A
    * superseded (`Failed`) or finished (`Done`) session can still receive late
    * events from the abandoned login — they must never resurrect or overwrite a
    * terminal state. */
  private def advance(scanId: String)(f: PartialFunction[State, State]): IO[Unit] =
    statesRef.modify { m =>
      val next = m.get(scanId) match
        case Some(s) if f.isDefinedAt(s) => prune(m.updated(scanId, f(s)))
        case _                           => m
      (next, ())
    }

  private def onEvent(scanId: String, ev: LoginEvent): IO[Unit] =
    ev match
      case LoginEvent.Qr(url, code, expireIn) =>
        val expireAt = System.currentTimeMillis() + expireIn * 1000L
        IO(logger.info(s"weixin scan-bind [$scanId]: qr ready (expireIn=$expireIn s)")) *>
          advance(scanId) {
            case State.Starting => State.QrReady(url, code, expireAt)
            case q: State.QrReady if q.qrUrl != url || q.userCode != code =>
              State.QrReady(url, code, expireAt) // a re-emitted QR with fresh content
          }
      case LoginEvent.Polling =>
        advance(scanId) { case q: State.QrReady => State.Polling(q.qrUrl, q.userCode, q.expireAt) }

  // ─────────────────────── completion: persist + activate ───────────────────────
  private def persistAndFinish(scanId: String, creds: WeixinCredentials): IO[Unit] =
    // The ONLY request in this class that ever carries the plaintext token;
    // SocialChannels.save funnels it through writeSecret (owner-only from the
    // first byte, narrowed before write, zero logs) and the config keeps a
    // path. The merge is per-field: sidecar_url / allowed_ilink_user_ids
    // survive untouched.
    val saveBody = Json.obj(
      "enabled" -> true.asJson,
      "fields" -> Json.obj(
        "bot_token" -> creds.botToken.asJson,
        "ilink_bot_id" -> creds.botId.asJson,
        "ilink_user_id" -> creds.userId.asJson
      )
    )
    IO.blocking(SocialChannels.save(root, WeixinIlinkBridgePlugin.Name, saveBody)).flatMap {
      case Right(_) =>
        activate.attempt.flatMap {
          case Right(_) =>
            IO(logger.info(s"weixin scan-bind [$scanId]: bot ${creds.botId} stored and bridge sync fired")) *>
              statesRef.update(m => m.updated(scanId, State.Done(creds.botId)))
          case Left(e) =>
            IO(logger.warn(s"weixin scan-bind [$scanId]: bridge sync failed: ${e.getClass.getSimpleName}")) *>
              statesRef.update(m => m.updated(scanId, State.Failed(s"bridge sync failed: ${e.getClass.getSimpleName}")))
        }
      case Left(err) =>
        val reason = renderSaveFailure(err)
        IO(logger.warn(s"weixin scan-bind [$scanId]: credential persistence failed ($reason)")) *>
          statesRef.update(m => m.updated(scanId, State.Failed(reason)))
    }

  /** Status-facing reason from a save failure. `SocialChannels.Failure`
    * messages are built by our own code and never embed the plaintext. */
  private def renderSaveFailure(err: SocialChannels.Failure): String = err match
    case SocialChannels.Failure.InvalidField(f, r) => s"invalid field $f: $r"
    case SocialChannels.Failure.SecretMode(f, r)   => s"could not store $f: $r"
    case SocialChannels.Failure.Io(r)              => s"storage error: $r"
    case SocialChannels.Failure.UnknownChannel(id) => s"unknown channel $id"

  private def fail(scanId: String, kind: String, e: Throwable): IO[Unit] =
    IO(logger.warn(s"weixin scan-bind [$scanId]: failed ($kind: ${e.getClass.getSimpleName})")) *>
      statesRef.update(m => m.updated(scanId, State.Failed(kind)))

  private def fail(scanId: String, reason: String): IO[Unit] =
    IO(logger.warn(s"weixin scan-bind [$scanId]: failed ($reason)")) *>
      statesRef.update(m => m.updated(scanId, State.Failed(reason)))

end WeixinIlinkScanBind

object WeixinIlinkScanBind:

  /** The control seam: ONE blocking call that drives the whole login —
    * begin, poll, terminal — reporting progress through the emit callback and
    * answering the outcome. Throwing [[LoginExpiredException]] /
    * [[SidecarUnreachableException]] maps onto the failure vocabulary. */
  type LoginFn = LoginOptions => LoginResult

  final case class LoginOptions(emit: LoginEvent => Unit)

  enum LoginEvent:
    case Qr(url: String, userCode: String, expireIn: Long)
    case Polling

  enum LoginResult:
    case Done(credentials: WeixinCredentials)
    case Failed(reason: String)

  /** The credential triple a completed login hands over. `botToken` exists only
    * between the seam and the save request body (zero-secret discipline). */
  final case class WeixinCredentials(botToken: String, botId: String, userId: String)

  /** Transport failures with our own vocabulary (never a side-car stack trace). */
  final class SidecarUnreachableException(cause: Throwable)
    extends RuntimeException("side-car control endpoint unreachable", cause)
  final class LoginExpiredException extends RuntimeException("the login session expired")

  /** Production control endpoint: the card's `sidecar_url`, else the default
    * localhost endpoint. */
  val SidecarDefaultBase = "http://127.0.0.1:8787"

  /** The side-car poll cadence and the overall login budget. */
  val PollIntervalMs = 2000L
  val MaxLoginMs = 300000L

  /** Production [[LoginFn]]: HTTP against the side-car control endpoint —
    * `GET {base}/login/begin` then `GET {base}/login/status` until a terminal
    * state (both legs are GET; the side-car serves no POST on this path).
    * Blocking by contract (the fiber runs it inside `IO.blocking`);
    * every transport failure lands in [[SidecarUnreachableException]], the
    * budget in [[LoginExpiredException]]. */
  def sidecarLogin(root: os.Path): LoginFn =
    opts => driveLogin(sidecarBase(root).stripSuffix("/"), opts)

  /** The blocking poll loop (own method so the terminal `return`s return the
    * [[LoginResult]], not the enclosing lambda). */
  private def driveLogin(base: String, opts: LoginOptions): LoginResult =
    val client = java.net.http.HttpClient.newHttpClient()
    def get(path: String): Json =
      val resp = client.send(
        java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
          .timeout(java.time.Duration.ofSeconds(5)).GET().build(),
        java.net.http.HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() / 100 != 2 then throw SidecarUnreachableException(null)
      io.circe.parser.parse(resp.body()).getOrElse(Json.obj())
    // begin: the side-car may answer with the first QR already.
    val begin = get("/login/begin")
    strField(begin, "qrUrl", "qr_url").foreach { url =>
      opts.emit(LoginEvent.Qr(url,
        strField(begin, "userCode", "user_code").getOrElse(extractUserCode(url)),
        longField(begin, "expireIn", "expire_in").getOrElse(0L)))
    }
    val deadline = System.currentTimeMillis() + MaxLoginMs
    var lastUrl = ""
    while true do
      val st = get("/login/status")
      val state = strField(st, "state").getOrElse("")
      strField(st, "qrUrl", "qr_url").filter(_ != lastUrl).foreach { url =>
        lastUrl = url
        opts.emit(LoginEvent.Qr(url,
          strField(st, "userCode", "user_code").getOrElse(extractUserCode(url)),
          longField(st, "expireIn", "expire_in").getOrElse(0L)))
      }
      if state == "polling" then opts.emit(LoginEvent.Polling)
      if state == "done" then
        credentials(st) match
          case Some(c) => return LoginResult.Done(c)
          case None    => return LoginResult.Failed("done-without-credentials")
      if state == "failed" then
        return LoginResult.Failed(strField(st, "error").filter(_.nonEmpty).getOrElse("login_failed"))
      if System.currentTimeMillis() > deadline then throw LoginExpiredException()
      Thread.sleep(PollIntervalMs)
    // unreachable: the loop above only exits through a return or a throw
    LoginResult.Failed("internal_error")

  /** The control base: the card's stored `sidecar_url`, else the default. */
  private def sidecarBase(root: os.Path): String =
    val fields = SocialChannels.readChannels(root).hcursor
      .downField("channels").downField(WeixinIlinkBridgePlugin.Name).downField("fields").focus
      .getOrElse(Json.obj())
    strField(fields, "sidecar_url").filter(_.nonEmpty).getOrElse(SidecarDefaultBase)

  private def strField(j: Json, keys: String*): Option[String] =
    keys.toList.flatMap(k => j.hcursor.downField(k).as[String].toOption).headOption.map(_.trim)

  private def longField(j: Json, keys: String*): Option[Long] =
    keys.toList.flatMap(k => j.hcursor.downField(k).as[Long].toOption).headOption

  /** The credential triple off a `done` status. Any missing member = no
    * credentials (the login cannot complete half-handed). */
  private def credentials(st: Json): Option[WeixinCredentials] =
    for
      c <- st.hcursor.downField("credentials").focus
      token <- strField(c, "botToken", "bot_token").filter(_.nonEmpty)
      botId <- strField(c, "botId", "ilink_bot_id").filter(_.nonEmpty)
      userId <- strField(c, "userId", "ilink_user_id").filter(_.nonEmpty)
    yield WeixinCredentials(token, botId, userId)

  /** Registry cap (each begin adds one entry; old entries are pruned). */
  val MaxSessions = 24

  /** Reason surfaced when a newer begin supersedes an open session (⑧). */
  val SupersededReason = "superseded by a newer scan-bind session"

  /** The short code a phone shows after scanning, when the side-car carries it
    * only inside the verification URL (same form as the feishu extraction:
    * `user_code=XXXX-XXXX` in the query). Empty string when absent. */
  def extractUserCode(url: String): String =
    try
      val q = url.indexOf('?')
      if q < 0 then ""
      else
        url.substring(q + 1).split('&').toList
          .map(p => p.split("=", 2).toList)
          .collectFirst { case k :: v :: _ if k == "user_code" || k == "userCode" =>
            java.net.URLDecoder.decode(v, "UTF-8")
          }
          .getOrElse("")
    catch case _: Exception => ""

  /** Session state machine (same projection the feishu card ships):
    * `Starting → QrReady(qrUrl, userCode, expireAt) → Polling → Done(botId) |
    * Failed(reason)`. */
  enum State:
    case Starting
    case QrReady(qrUrl: String, userCode: String, expireAt: Long)
    case Polling(qrUrl: String, userCode: String, expireAt: Long)
    case Done(botId: String)
    case Failed(reason: String)

end WeixinIlinkScanBind
