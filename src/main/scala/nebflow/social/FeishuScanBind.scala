package nebflow.social

import cats.effect.{IO, Ref}
import cats.effect.kernel.Fiber
import cats.effect.unsafe.implicits.global
import com.lark.oapi.scene.registration.{
  AccessDeniedException,
  AppPreset,
  ExpiredException,
  QRCodeInfo,
  RegisterApp,
  RegisterAppException,
  RegisterAppOptions,
  RegisterAppResult,
  StatusChangeInfo
}
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

import java.util.UUID
import scala.collection.immutable.ListMap

/**
 * Feishu scan-bind (feiscanbind batch, 2026-09-27) — the "create the app by
 * scanning a QR code" main path, on top of the official one-click registration
 * capability that the pinned oapi-sdk 2.8.5 already ships
 * (`com.lark.oapi.scene.registration.RegisterApp`, RFC 8628 device-authorization
 * flow; upstream plan card = `20260927_090058_feishu-scan-bind-proposal`).
 *
 * Shape (plan card §5.2, unchanged):
 *
 *   - `begin`  → a scanId is minted and a BACKGROUND fiber is started; the HTTP
 *     response carries only the scanId and never blocks on the SDK.
 *   - the fiber runs the SDK's BLOCKING `register(RegisterAppOptions)` inside
 *     `IO.blocking` (never on an HTTP thread). The SDK reports progress through
 *     two callbacks (`onQRCode` / `onStatusChange`) which fire on the calling
 *     thread DURING `register()`; each bridges into IO in exactly one place
 *     (`runOnSdkThread` below, `unsafeRunSync` — the same explicit-boundary
 *     shape as `FeishuBridgePlugin.intake`).
 *   - `status` → a read-only projection of the registry:
 *     `Starting → QrReady(qrUrl, userCode, expireAt) → Polling → Done(appId) |
 *     Failed(reason)`. Responses never carry a secret: `Done` surfaces only the
 *     `appId` (cli_ prefix, displayable), `Failed` surfaces a reason string
 *     built from our own failure vocabulary (never a credential).
 *   - on success the fiber persists the credential pair through the EXISTING
 *     write path — `SocialChannels.save` (the plaintext goes in the request
 *     body ONCE; `app_secret` lands in `secrets/` via `writeSecret`, the config
 *     keeps only the `_ref` path) — then fires the ONE activation point
 *     (`FeishuBridgePlugin.sync` via the injected `activate` leg), so the long
 *     connection comes up and the panel's `adapterRegistered` flips true.
 *     tenant_brand=lark ⇒ `region=lark` is written in the same save (approved
 *     pending-decision ④; the schema `^(feishu|lark)$` already accepts it).
 *
 * Concurrency (approved pending-decision ⑧): SINGLE-FLIGHT. A new `begin`
 * supersedes the in-flight session — its registry entry is marked
 * `Failed("superseded by a newer scan-bind session")` and its fiber is
 * cancelled. `Fiber.cancel` cannot interrupt a blocked socket read, so the
 * abandoned SDK call may still run to completion server-side; its result path
 * is severed (cats-effect resumes the cancelled fiber at the next boundary and
 * the persist/activate leg never runs for a superseded session).
 *
 * 🔴 Zero-secret discipline: `client_secret` exists in exactly two places —
 * inside the SDK result object and inside the `SocialChannels.save` request
 * body. It is never logged, never rendered, never stored in the registry, and
 * never appears in any response. `open_id` (the scanning user) is not persisted
 * at all.
 *
 * 🔴 Zero real registration in tests: the SDK boundary is the constructor-
 * injected [[registerFn]] thunk (the SDK's result/exception types all have
 * public constructors, so fakes drive the real code paths offline). Production
 * wiring ([[FeishuScanBind.sdkRegister]]) is the ONLY place that touches the
 * static `RegisterApp.register`.
 */
final class FeishuScanBind(
  /** Active data root (the same root `SocialChannels.save` persists under). */
  root: os.Path,
  /** SDK boundary seam. Production = [[FeishuScanBind.sdkRegister]]; tests
    * inject a fake — zero network, zero registration. */
  registerFn: FeishuScanBind.RegisterFn = FeishuScanBind.sdkRegister,
  /** The post-persist activation leg. Production wiring passes
    * `FeishuBridgePlugin.sync(manager, root)` (the ONE activation point); the
    * offline spec injects a recorder. */
  activate: IO[Unit] = IO.unit
):

  private val logger = NebflowLogger.forName("nebflow.social.feishu-scan-bind")

  import FeishuScanBind.*

  /** All known sessions, insertion-ordered (pruned to [[MaxSessions]]). */
  private val statesRef: Ref[IO, ListMap[String, State]] =
    Ref.unsafe[IO, ListMap[String, State]](ListMap.empty)

  /** The single-flight slot: the one in-flight `(scanId, fiber)`, if any. */
  private val activeRef: Ref[IO, Option[(String, Fiber[IO, Throwable, Unit])]] =
    Ref.unsafe[IO, Option[(String, Fiber[IO, Throwable, Unit])]](None)

  // ───────────────────────────── begin ─────────────────────────────
  /** Start a scan-bind session. Returns `{"scanId": …, "state": "starting"}`;
    * progress is observed through [[status]]. Never blocks on the SDK: the
    * register call runs on a background fiber (`IO(blocking)`). */
  def begin(): IO[Json] =
    for
      _ <- supersede()
      scanId <- IO(UUID.randomUUID().toString)
      _ <- statesRef.update(m => prune(m.updated(scanId, State.Starting)))
      fiber <- registerFiber(scanId).start
      _ <- activeRef.set(Some((scanId, fiber)))
    yield Json.obj("scanId" -> scanId.asJson, "state" -> "starting".asJson)

  /** Single-flight: mark a still-open predecessor `Failed` and cancel its
    * fiber. Terminal states (Done/Failed) are left untouched. The cancel runs
    * on its own fiber: `Fiber.cancel` waits for finalization, and the abandoned
    * `register()` may sit in a socket read that ignores interrupts — `begin`
    * must never block on that unwind (its result path is severed either way:
    * the guarded state writes refuse a cancelled session, and the cancelled
    * fiber never reaches persist/activate). */
  private def supersede(): IO[Unit] =
    activeRef.getAndSet(None).flatMap {
      case None => IO.unit
      case Some((oldId, fiber)) =>
        // `Starting` is a value case (no payload type) — matched by value; the
        // payload cases carry real types and match by type.
        statesRef.update(m => m.updatedWith(oldId) {
          case Some(_: State.QrReady) | Some(_: State.Polling) =>
            Some(State.Failed(SupersededReason))
          case Some(State.Starting) => Some(State.Failed(SupersededReason))
          case other                => other
        }) *> fiber.cancel.start.void
    }

  /** Registry grows by one entry per begin — cap it (insertion-ordered drop of
    * the oldest entries); a status reader only ever looks at recent sessions. */
  private def prune(m: ListMap[String, State]): ListMap[String, State] =
    if m.size <= MaxSessions then m else m.drop(m.size - MaxSessions)

  private def registerFiber(scanId: String): IO[Unit] =
    IO.blocking(registerFn(buildOptions(scanId))).attempt.flatMap {
      case Right(result) => persistAndFinish(scanId, result)
      case Left(e: ExpiredException)      => fail(scanId, "expired", e)
      case Left(e: AccessDeniedException) => fail(scanId, "access_denied", e)
      case Left(e: RegisterAppException)  => fail(scanId, "register_failed", e)
      case Left(e: InterruptedException)  => IO.unit // cancelled mid-call; state already superseded
      case Left(e)                        => fail(scanId, "internal_error", e)
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
      case State.Done(appId) =>
        base("done").deepMerge(Json.obj("appId" -> appId.asJson))
      case State.Failed(reason) =>
        base("failed").deepMerge(Json.obj("error" -> reason.asJson))

  private def remainSec(expireAt: Long, now: Long): Long = math.max(0L, (expireAt - now) / 1000L)

  // ─────────────────────── SDK option assembly ───────────────────────
  private def buildOptions(scanId: String): RegisterAppOptions =
    RegisterAppOptions.newBuilder()
      .source(SourceTag)
      // Approved pending-decision ③: the ZCode-style preset copy. The platform
      // supports a {user} placeholder in the name; the shipped copy stays the
      // minimal constant (collected in the §16 outpiece).
      .appPreset(AppPreset.newBuilder().name(AppPresetName).build())
      // ② default agent template: no addons override ⇒ the platform default
      // (Bot + full tenant permissions + the WebSocket long-connection events).
      // ⑦ "reuse an existing app" is NOT shipped: no appId is ever set here.
      .onQRCode(qr => runOnSdkThread(onQrCode(scanId, qr)))
      .onStatusChange(s => runOnSdkThread(onStatusChange(scanId, s)))
      .build()

  /** The ONE SDK-thread → IO boundary (same shape as `FeishuBridgePlugin.intake`
    * on the SDK receive thread): the callback runs inside the blocking
    * `register()` call, `unsafeRunSync` keeps callback ordering strict. */
  private def runOnSdkThread(io: IO[Unit]): Unit =
    try io.unsafeRunSync()
    catch
      case _: InterruptedException => () // fibre cancelled — never break the SDK flow on the way out
      case e: Exception =>
        logger.warn(s"feishu scan-bind: registry update from SDK callback failed: ${e.getClass.getSimpleName}")

  /** Guarded state write for SDK callbacks: only OPEN sessions advance. A
    * superseded (`Failed`) or finished (`Done`) session can still receive late
    * callbacks from the abandoned SDK call (cancel cannot interrupt a blocked
    * socket read) — they must never resurrect or overwrite a terminal state. */
  private def advance(scanId: String)(f: PartialFunction[State, State]): IO[Unit] =
    statesRef.modify { m =>
      val next = m.get(scanId) match
        case Some(s) if f.isDefinedAt(s) => prune(m.updated(scanId, f(s)))
        case _                           => m
      (next, ())
    }

  private def onQrCode(scanId: String, qr: QRCodeInfo): IO[Unit] =
    val expireAt = System.currentTimeMillis() + qr.getExpireIn * 1000L
    val url = Option(qr.getUrl).getOrElse("")
    val code = FeishuScanBind.extractUserCode(url)
    IO(logger.info(s"feishu scan-bind [$scanId]: qr ready (expireIn=${qr.getExpireIn}s)")) *>
      advance(scanId) {
        case State.Starting => State.QrReady(url, code, expireAt)
        case q: State.QrReady if q.qrUrl != url || q.userCode != code =>
          State.QrReady(url, code, expireAt) // a re-emitted QR with fresh content
      }

  private def onStatusChange(scanId: String, s: StatusChangeInfo): IO[Unit] =
    if s.getStatus == StatusChangeInfo.POLLING then
      advance(scanId) { case q: State.QrReady => State.Polling(q.qrUrl, q.userCode, q.expireAt) }
    else IO.unit // SLOW_DOWN (interval, SDK-internal) / DOMAIN_SWITCHED (region lands at Done)

  // ─────────────────────── completion: persist + activate ───────────────────────
  private def persistAndFinish(scanId: String, result: RegisterAppResult): IO[Unit] =
    val appId = Option(result.getClientId).getOrElse("")
    val tenantBrand = Option(result.getUserInfo).map(_.getTenantBrand).getOrElse("")
    // ④ dual-domain follow: a lark tenant writes region=lark in the same save.
    val region = if tenantBrand.equalsIgnoreCase("lark") then "lark" else "feishu"
    // The ONLY request in this class that ever carries the plaintext secret;
    // SocialChannels.save funnels it through writeSecret (owner-only from the
    // first byte, narrowed before write, zero logs) and the config keeps a path.
    val saveBody = Json.obj(
      "enabled" -> true.asJson,
      "fields" -> Json.obj(
        "app_id" -> appId.asJson,
        "app_secret" -> Option(result.getClientSecret).getOrElse("").asJson,
        "region" -> region.asJson
      )
    )
    IO.blocking(SocialChannels.save(root, FeishuBridgePlugin.Name, saveBody)).flatMap {
      case Right(_) =>
        activate.attempt.flatMap {
          case Right(_) =>
            IO(logger.info(s"feishu scan-bind [$scanId]: app $appId stored and bridge sync fired")) *>
              statesRef.update(m => m.updated(scanId, State.Done(appId)))
          case Left(e) =>
            IO(logger.warn(s"feishu scan-bind [$scanId]: bridge sync failed: ${e.getClass.getSimpleName}")) *>
              statesRef.update(m => m.updated(scanId, State.Failed(s"bridge sync failed: ${e.getClass.getSimpleName}")))
        }
      case Left(err) =>
        val reason = renderSaveFailure(err)
        IO(logger.warn(s"feishu scan-bind [$scanId]: credential persistence failed ($reason)")) *>
          statesRef.update(m => m.updated(scanId, State.Failed(reason)))
    }

  /** Status-facing reason from a save failure. `SocialChannels.Failure`
    * messages are built by our own code (field names, paths, exception class
    * names) and never embed the plaintext, so the reason is safe to surface. */
  private def renderSaveFailure(err: SocialChannels.Failure): String = err match
    case SocialChannels.Failure.InvalidField(f, r) => s"invalid field $f: $r"
    case SocialChannels.Failure.SecretMode(f, r)   => s"could not store $f: $r"
    case SocialChannels.Failure.Io(r)              => s"storage error: $r"
    case SocialChannels.Failure.UnknownChannel(id) => s"unknown channel $id"

  private def fail(scanId: String, kind: String, e: Throwable): IO[Unit] =
    // RegisterAppException.getDescription is the platform's own user-facing
    // phrase (e.g. "user denied"); getCode is its short code. Neither carries
    // a credential.
    val detail = e match
      case r: RegisterAppException =>
        val d = Option(r.getDescription).getOrElse("")
        if d.isEmpty then kind else s"$kind: $d"
      case _ => kind
    IO(logger.warn(s"feishu scan-bind [$scanId]: failed ($kind: ${e.getClass.getSimpleName})")) *>
      statesRef.update(m => m.updated(scanId, State.Failed(detail)))

end FeishuScanBind

object FeishuScanBind:

  /** The SDK boundary: production is the static BLOCKING register call. */
  type RegisterFn = RegisterAppOptions => RegisterAppResult

  /** Production thunk — the ONLY touch point of the SDK static. */
  def sdkRegister: RegisterFn = opts => RegisterApp.register(opts)

  /** Identifies this integration to the platform (free-text, plan card §5.2). */
  val SourceTag = "nebflow"

  /** ③ preset copy shown on the phone confirmation page (§16 outpiece). */
  val AppPresetName = "Nebflow 机器人"

  /** Registry cap (each begin adds one entry; old entries are pruned). */
  val MaxSessions = 24

  /** Reason surfaced when a newer begin supersedes an open session (⑧). */
  val SupersededReason = "superseded by a newer scan-bind session"

  /** The short code a phone shows after scanning lives in the verification URL
    * (plan card A3 ④: the SDK's `QRCodeInfo` carries only url+expireIn, so the
    * code is extracted from the URL query; the platform form is
    * `user_code=XXXX-XXXX`). Empty string when absent — the QR alone remains
    * sufficient. */
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

  /** Session state machine (plan card §5.2):
    * `Starting → QrReady(qrUrl, userCode, expireAt) → Polling → Done(appId) |
    * Failed(reason)`. `Starting` is the pre-QR window (the SDK only delivers
    * the QR after its init round-trip); superseded open sessions land in
    * `Failed`. */
  enum State:
    case Starting
    case QrReady(qrUrl: String, userCode: String, expireAt: Long)
    case Polling(qrUrl: String, userCode: String, expireAt: Long)
    case Done(appId: String)
    case Failed(reason: String)

end FeishuScanBind
