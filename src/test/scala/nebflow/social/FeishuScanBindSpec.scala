package nebflow.social

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import com.lark.oapi.scene.registration.{
  AccessDeniedException,
  ExpiredException,
  QRCodeInfo,
  RegisterAppException,
  RegisterAppOptions,
  RegisterAppResult,
  StatusChangeInfo,
  UserInfo
}
import io.circe.Json
import munit.CatsEffectSuite

import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*

/** Offline regression spec for the Feishu scan-bind manager (feiscanbind batch,
  * 2026-09-27; upstream plan card `20260927_090058_feishu-scan-bind-proposal`
  * §5). The zero-network discipline is absolute here — the batch's hard
  * constraint forbids any real registration — so the SDK boundary is the
  * constructor-injected [[FeishuScanBind.registerFn]] thunk: fakes drive the
  * REAL option hooks (`onQRCode` / `onStatusChange` with real SDK objects,
  * which all have public constructors) and return real results or throw the
  * real exception families. What is pinned:
  *
  *   - the state machine projection (`starting → qr_ready → polling → done |
  *     failed`) with the secret-free payload contract (appId/error only — the
  *     client_secret appears in exactly one place, the `SocialChannels.save`
  *     request body, and never in a status payload);
  *   - the persist leg reusing the EXISTING write path (plaintext →
  *     `secrets/social-feishu-app-secret` `rw-------`, config → `_ref` only,
  *     `enabled` flipped, region follows the scanned tenant brand ④) and the
  *     activation leg firing exactly once;
  *   - ⑤ reachability: a scan-bind-created app (no verification_token at all)
  *     passes `SocialChannels.verified` — the whole point of the required→
  *     optional relaxation;
  *   - ⑧ single-flight: a newer begin supersedes the open session AND severs
  *     its result path (the abandoned fiber never persists, and a late SDK
  *     callback can never resurrect a terminal state — pinned directly);
  *   - the failure families (`ExpiredException` / `AccessDeniedException` /
  *     `RegisterAppException` / plain errors) land in `Failed` with reasons
  *     built from our own vocabulary, and a save-side rejection (app_id
  *     pattern) leaves zero credential residue.
  */
class FeishuScanBindSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-feishu-scanbind-")

  private val QrUrl = "https://accounts.feishu.cn/open/verification?user_code=ABCD-1234&from=sdk"

  private def scanIdOf(beginJson: Json): String =
    beginJson.hcursor.downField("scanId").as[String].getOrElse("")

  private def stateOf(st: Json): Option[String] = st.hcursor.downField("state").as[String].toOption

  /** Poll the status face until `pred` holds (bounded — a regression must fail
    * fast, not hang the suite). */
  private def waitFor(sb: FeishuScanBind, scanId: String)(pred: Json => Boolean): IO[Json] =
    (IO.sleep(50.millis) *> sb.status(scanId).map(_.getOrElse(Json.obj())))
      .iterateUntil(pred)
      .timeout(10.seconds)

  /** The offline stand-in for `RegisterApp.register`: emits the real QR callback
    * through the options' own hook, blocks on `gate`, and — when `lateEmit` is
    * armed — re-emits a POLLING status callback after the gate (the
    * late-callback probe for SB-4). POLLING is NEVER emitted up front: the real
    * SDK delivers it a poll interval later, and emitting it synchronously here
    * would race `qr_ready` past any observer. */
  private def fakeGate(
      gate: Deferred[IO, Unit],
      result: RegisterAppResult,
      lateEmit: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](false),
      qrUrl: String = QrUrl
  ): FeishuScanBind.RegisterFn =
    opts => {
      val qr = opts.getOnQRCode
      if qr != null then qr.accept(new QRCodeInfo(qrUrl, 600))
      val sc = opts.getOnStatusChange
      gate.get.unsafeRunSync()
      val late = lateEmit.get.unsafeRunSync()
      if late && sc != null then sc.accept(new StatusChangeInfo(StatusChangeInfo.POLLING, 5))
      result
    }

  private def result(clientId: String, secret: String, tenantBrand: String): RegisterAppResult =
    new RegisterAppResult(clientId, secret, new UserInfo("ou_scanuser", tenantBrand))

  private def fakeThrow(e: Exception, qrUrl: String = QrUrl): FeishuScanBind.RegisterFn =
    opts => {
      val qr = opts.getOnQRCode
      if qr != null then qr.accept(new QRCodeInfo(qrUrl, 600))
      throw e
    }

  private def posixOnly(): Unit =
    assume(
      !nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName),
      "POSIX mode readback (the persisted credential must be rw------- on POSIX)"
    )

  // ───────────────────────── the projection ─────────────────────────

  test("SB-1 begin → qr_ready: url, user code, remaining seconds; never a secret") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      sb = new FeishuScanBind(root,
        registerFn = fakeGate(gate, result("cli_0123456789abcdef", "sec-sb1", "feishu")),
        activate = IO.unit)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ = assertEquals(beginJson.hcursor.downField("state").as[String].toOption, Some("starting"))
      st <- waitFor(sb, scanId)(stateOf(_) == Some("qr_ready"))
      _ = assertEquals(st.hcursor.downField("qrUrl").as[String].toOption, Some(QrUrl))
      _ = assertEquals(st.hcursor.downField("userCode").as[String].toOption, Some("ABCD-1234"))
      _ = assert(clue(st.hcursor.downField("remainSec").as[Long].toOption).exists(_ > 0))
      _ = assert(!st.noSpaces.contains("sec-sb1"), "status payload must never carry a secret")
      // finishing the gate lets the fiber wind down cleanly (suite hygiene)
      _ <- gate.complete(())
      _ <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
    yield ()
  }

  test("SB-2 done: save reuses the existing write path (_ref only, rw------- file, region=lark), " +
    "activation fires once, verified reachable with NO verification_token (⑤)") {
    posixOnly()
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      sb = new FeishuScanBind(root,
        registerFn = fakeGate(gate, result("cli_0123456789abcdef", "sec-sb2-plain", "lark")),
        activate = fired.update(_ + 1))
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      // release the fake immediately: this leg pins the COMPLETION path
      _ <- gate.complete(())
      st <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
      _ = assertEquals(st.hcursor.downField("appId").as[String].toOption,
        Some("cli_0123456789abcdef"))
      _ = assert(!st.noSpaces.contains("sec-sb2-plain"), "done payload carries appId only")
      // config side: app_id plaintext, secret as _ref, region follows brand (④)
      fields = SocialChannels.readChannels(root).hcursor
        .downField("channels").downField("feishu").downField("fields").focus.getOrElse(Json.obj())
      _ = assertEquals(fields.hcursor.downField("app_id").as[String].toOption,
        Some("cli_0123456789abcdef"))
      _ = assert(fields.hcursor.downField("app_secret_ref").as[String].toOption.exists(_.nonEmpty),
        "config keeps only the _ref path")
      _ = assert(fields.hcursor.downField("app_secret").as[String].isLeft,
        "no plaintext secret key in config")
      _ = assertEquals(fields.hcursor.downField("region").as[String].toOption, Some("lark"))
      _ = assert(SocialChannels.isEnabled(root, "feishu"), "scan-bind flips enabled")
      // secret side: one file, exact plaintext, owner-only mode from creation
      secretFile = root / "secrets" / "social-feishu-app-secret"
      _ = assert(os.exists(secretFile), "credential file exists")
      _ = assertEquals(os.read(secretFile), "sec-sb2-plain")
      _ = assertEquals(
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(secretFile.toNIO)),
        "rw-------")
      // activation leg: the ONE sync point fired exactly once
      count <- fired.get
      _ = assertEquals(count, 1, "the ONE activation point fires exactly once")
      // ⑤ the point of the relaxation: verified passes with no verification_token
      _ = assert(SocialChannels.verified(root, "feishu"),
        "scan-bind app (no verification_token) must reach verified")
    yield ()
  }

  // ───────────────────────── single flight (⑧) ─────────────────────────

  test("SB-3 single-flight: a newer begin supersedes the open session and severs its result path") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      sb = new FeishuScanBind(root,
        registerFn = fakeGate(gate, result("cli_0123456789abcdef", "sec-sb3", "feishu")),
        activate = fired.update(_ + 1))
      b1 <- sb.begin()
      id1 = scanIdOf(b1)
      _ <- waitFor(sb, id1)(stateOf(_) == Some("qr_ready"))
      b2 <- sb.begin()
      id2 = scanIdOf(b2)
      _ <- waitFor(sb, id2)(stateOf(_) == Some("qr_ready"))
      st1 <- waitFor(sb, id1)(stateOf(_) == Some("failed"))
      _ = assertEquals(st1.hcursor.downField("error").as[String].toOption,
        Some(FeishuScanBind.SupersededReason))
      // let both register() calls unwind — the superseded one must NOT persist
      _ <- gate.complete(())
      _ <- waitFor(sb, id2)(stateOf(_) == Some("done"))
      st1b <- sb.status(id1).map(_.getOrElse(Json.obj()))
      _ = assertEquals(stateOf(st1b), Some("failed"), "a superseded session never reaches done")
      count <- fired.get
      _ = assertEquals(count, 1, "only the surviving session persists + activates")
    yield ()
  }

  test("SB-4 a late SDK status callback cannot overwrite a terminal state (guarded advance)") {
    for
      gate <- Deferred[IO, Unit]
      lateEmit <- Ref.of[IO, Boolean](false)
      root <- IO(tmpRoot())
      sb = new FeishuScanBind(root,
        registerFn = fakeGate(gate, result("cli_0123456789abcdef", "sec-sb4", "feishu"),
          lateEmit = lateEmit),
        activate = IO.unit)
      b1 <- sb.begin()
      id1 = scanIdOf(b1)
      _ <- waitFor(sb, id1)(stateOf(_) == Some("qr_ready"))
      _ <- sb.begin() // supersede id1 → Failed (single-flight ⑧)
      _ <- waitFor(sb, id1)(stateOf(_) == Some("failed"))
      // the abandoned register() unwinds AFTER the supersession and emits one
      // more POLLING callback — red-by-construction if the state write is not
      // guarded (Polling would overwrite Failed)
      _ <- lateEmit.set(true)
      _ <- gate.complete(())
      _ <- IO.sleep(300.millis)
      st1 <- sb.status(id1).map(_.getOrElse(Json.obj()))
      _ = assertEquals(stateOf(st1), Some("failed"), "late callback must not resurrect the session")
    yield ()
  }

  // ───────────────────────── failure families ─────────────────────────

  test("SB-5 the SDK failure families land in Failed with clean, secret-free reasons") {
    val cases = List(
      ("expired", new ExpiredException("expired_token", "the QR code has expired")),
      ("access_denied", new AccessDeniedException("access_denied", "the user denied the request")),
      ("register_failed", new RegisterAppException("rate_limited", "too many attempts")),
      ("internal_error", new RuntimeException("boom"))
    )
    cases.traverse_ { (kind, e) =>
      for
        root <- IO(tmpRoot())
        sb = new FeishuScanBind(root, registerFn = fakeThrow(e), activate = IO.unit)
        beginJson <- sb.begin()
        scanId = scanIdOf(beginJson)
        st <- waitFor(sb, scanId)(stateOf(_) == Some("failed"))
        err = st.hcursor.downField("error").as[String].getOrElse("")
        _ = assert(clue(err).startsWith(kind), s"expected $kind prefix, got: $err")
        _ = assert(!st.noSpaces.contains("sec-"), "failure payload carries no secret")
      yield ()
    }
  }

  test("SB-6 a save-side rejection (app_id pattern) → Failed, zero credential residue") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      sb = new FeishuScanBind(root,
        registerFn = fakeGate(gate, result("not-a-cli-id", "sec-sb6", "feishu")),
        activate = fired.update(_ + 1))
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(()) // release the fake: the rejected save path must still terminate
      st <- waitFor(sb, scanId)(stateOf(_) == Some("failed"))
      _ = assert(st.hcursor.downField("error").as[String].toOption.exists(_.startsWith("invalid field app_id")),
        clue(st.noSpaces))
      _ = assert(!os.exists(root / "secrets" / "social-feishu-app-secret"),
        "a rejected save writes no credential file")
      count <- fired.get
      _ = assertEquals(count, 0, "no activation on a failed save")
    yield ()
  }

  // ───────────────────────── pure helpers ─────────────────────────

  test("SB-7 user_code extraction from the verification URL (plan card A3 ④)") {
    assertEquals(FeishuScanBind.extractUserCode(QrUrl), "ABCD-1234")
    assertEquals(FeishuScanBind.extractUserCode("https://accounts.feishu.cn/x?from=sdk&user_code=ZZZZ-9999"), "ZZZZ-9999")
    assertEquals(FeishuScanBind.extractUserCode("https://accounts.feishu.cn/x"), "")
    assertEquals(FeishuScanBind.extractUserCode("not a url"), "")
    assertEquals(FeishuScanBind.extractUserCode("https://x/?user_code="), "")
  }

  test("SB-8 default tenant brand writes region=feishu (④ dual-domain follow)") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      sb = new FeishuScanBind(root,
        registerFn = fakeGate(gate, result("cli_0123456789abcdef", "sec-sb8", "")),
        activate = IO.unit)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(()) // release the fake: straight to done
      _ <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
      fields = SocialChannels.readChannels(root).hcursor
        .downField("channels").downField("feishu").downField("fields").focus.getOrElse(Json.obj())
      _ = assertEquals(fields.hcursor.downField("region").as[String].toOption, Some("feishu"))
    yield ()
  }

  test("SB-9 the scan-bind completion leaves a SCHEMA-resolvable credential that wins over a legacy file " +
    "(the P0-1 incident, replayed at unit level)") {
    // The 2026-09-27 silent-message incident in miniature: the scan-bind leg
    // stores the fresh credential in the schema, a months-old legacy flat file
    // still sits beside it — the resolver (schema-first since this batch) must
    // pick the freshly stored app, never the stale one.
    posixOnly()
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      sb = new FeishuScanBind(root,
        registerFn = fakeGate(gate, result("cli_0123456789abcdef", "sec-sb9", "feishu")),
        activate = IO.unit)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(())
      _ <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
      // the stale legacy file appears AFTER the scan-bind save, as in the field
      _ <- IO(os.write(root / "feishu.json",
        """{"appId":"cli_legacyapp00000001","appSecret":"legacy-secret-value-32-chars"}""",
        perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
      resolved <- IO(FeishuCredentials.resolve(root))
      _ = assertEquals(resolved.map(_.appId), Right("cli_0123456789abcdef"),
        "the schema credential stored by scan-bind must win over the legacy flat file")
      _ = assert(resolved.map(_.source).toOption.exists(_.startsWith("schema:")))
    yield ()
  }

end FeishuScanBindSpec
