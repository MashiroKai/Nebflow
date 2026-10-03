package nebflow.social

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*

/** Offline regression spec for the weixin scan-bind manager (weixin-scanbind
  * batch, 2026-10-03). The zero-network discipline is absolute — the batch's
  * hard constraint forbids any real login — so the control seam is the
  * constructor-injected [[WeixinIlinkScanBind.loginFn]] thunk: fakes drive the
  * REAL emit callback and return real results or throw the real exception
  * families; the side-car, the host and the iLink protocol are never touched.
  * What is pinned (the feishu spec's SB-1…SB-6 shape, keyed to the weixin
  * contract):
  *
  *   - the state machine projection (`starting → qr_ready → polling → done |
  *     failed`) with the secret-free payload contract — `done` surfaces only
  *     the bot id; the `bot_token` appears in exactly one place, the
  *     `SocialChannels.save` request body, and never in a status payload;
  *   - the persist leg reusing the EXISTING write path (plaintext →
  *     `secrets/social-weixin-bot-token` `rw-------`, config → `_ref` only,
  *     `enabled` flipped, the per-field merge preserving `sidecar_url` and
  *     `allowed_ilink_user_ids`) and the activation leg
  *     ([[WeixinIlinkBridgePlugin.sync]]) firing exactly once;
  *   - reachability: the scan-bind-written credential set passes
  *     `SocialChannels.verified`;
  *   - single-flight: a newer begin supersedes the open session AND severs its
  *     result path; a late login event can never resurrect a terminal state;
  *   - the failure families (`SidecarUnreachableException` / 
  *     `LoginExpiredException` / `LoginResult.Failed` / plain errors) land in
  *     `Failed` with reasons from our own vocabulary, and a save-side
  *     rejection leaves zero credential residue.
  */
class WeixinIlinkScanBindSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-weixin-scanbind-")

  private val QrUrl = "https://work.weixin.qq.com/qr?user_code=ABCD-1234&from=sidecar"
  private val Token = "wx-plain-bot-token-value"
  private val BotId = "wxid_mock_bot"
  private val UserId = "wxid_mock_user"

  private def creds = WeixinIlinkScanBind.WeixinCredentials(Token, BotId, UserId)

  private def scanIdOf(beginJson: Json): String =
    beginJson.hcursor.downField("scanId").as[String].getOrElse("")

  private def stateOf(st: Json): Option[String] = st.hcursor.downField("state").as[String].toOption

  /** Poll the status face until `pred` holds (bounded — a regression must fail
    * fast, not hang the suite). */
  private def waitFor(sb: WeixinIlinkScanBind, scanId: String)(pred: Json => Boolean): IO[Json] =
    (IO.sleep(50.millis) *> sb.status(scanId).map(_.getOrElse(Json.obj())))
      .iterateUntil(pred)
      .timeout(10.seconds)

  /** The offline stand-in for the side-car control leg: emits the real QR
    * event through the options' own callback, blocks on `gate`, and — when
    * `lateEmit` is armed — re-emits a POLLING event after the gate (the
    * late-callback probe for WSB-4). POLLING is NEVER emitted up front: the
    * real side-car delivers it a poll interval later. */
  private def fakeGate(
      gate: Deferred[IO, Unit],
      result: WeixinIlinkScanBind.LoginResult,
      lateEmit: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](false)
  ): WeixinIlinkScanBind.LoginFn =
    opts =>
      val qr = opts.emit
      qr(WeixinIlinkScanBind.LoginEvent.Qr(QrUrl, "ABCD-1234", 600))
      gate.get.unsafeRunSync()
      val late = lateEmit.get.unsafeRunSync()
      if late then qr(WeixinIlinkScanBind.LoginEvent.Polling)
      result

  private def fakeThrow(e: Exception): WeixinIlinkScanBind.LoginFn =
    opts =>
      opts.emit(WeixinIlinkScanBind.LoginEvent.Qr(QrUrl, "ABCD-1234", 600))
      throw e

  private def posixOnly(): Unit =
    assume(
      !nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName),
      "POSIX mode readback (the persisted credential must be rw------- on POSIX)"
    )

  // ───────────────────────── the projection ─────────────────────────

  test("WSB-1 begin → qr_ready: url, user code, remaining seconds; never a secret") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone), activate = IO.unit)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ = assertEquals(beginJson.hcursor.downField("state").as[String].toOption, Some("starting"))
      st <- waitFor(sb, scanId)(stateOf(_) == Some("qr_ready"))
      _ = assertEquals(st.hcursor.downField("qrUrl").as[String].toOption, Some(QrUrl))
      _ = assertEquals(st.hcursor.downField("userCode").as[String].toOption, Some("ABCD-1234"))
      _ = assert(clue(st.hcursor.downField("remainSec").as[Long].toOption).exists(_ > 0))
      _ = assert(!st.noSpaces.contains(Token), "status payload must never carry a secret")
      // finishing the gate lets the fiber wind down cleanly (suite hygiene)
      _ <- gate.complete(())
      _ <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
    yield ()
  }

  test("WSB-2 done: save reuses the existing write path (_ref only, rw------- file), " +
    "activation fires once, verified reachable, the merge keeps sidecar fields") {
    posixOnly()
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      // pre-existing slots the scan-bind save must NOT disturb (per-field
      // merge). 🔴 The pre-save must SUCCEED for the merge reading to have
      // teeth — a rejected pre-save would leave nothing to preserve and the
      // assertions below would pass vacuously.
      pre <- IO(SocialChannels.save(root, "weixin-ilink", Json.obj(
        "enabled" -> false.asJson,
        "fields" -> Json.obj(
          "sidecar_url" -> "http://127.0.0.1:9999".asJson,
          "allowed_ilink_user_ids" -> "wxid_friend_a".asJson))))
      _ = assert(clue(pre).isRight, "the pre-save must land (a rejected save voids the merge pin)")
      sb = new WeixinIlinkScanBind(root,
        loginFn = fakeGate(gate, LoginDone),
        activate = fired.update(_ + 1))
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      // release the fake immediately: this leg pins the COMPLETION path
      _ <- gate.complete(())
      st <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
      _ = assertEquals(st.hcursor.downField("botId").as[String].toOption, Some(BotId))
      _ = assert(!st.noSpaces.contains(Token), "done payload carries the bot id only")
      // config side: identifiers plaintext, secret as _ref, slots preserved
      fields = SocialChannels.readChannels(root).hcursor
        .downField("channels").downField("weixin-ilink").downField("fields").focus.getOrElse(Json.obj())
      _ = assertEquals(fields.hcursor.downField("ilink_bot_id").as[String].toOption, Some(BotId))
      _ = assertEquals(fields.hcursor.downField("ilink_user_id").as[String].toOption, Some(UserId))
      _ = assert(fields.hcursor.downField("bot_token_ref").as[String].toOption.exists(_.nonEmpty),
        "config keeps only the _ref path")
      _ = assert(fields.hcursor.downField("bot_token").as[String].isLeft,
        "no plaintext secret key in config")
      _ = assertEquals(fields.hcursor.downField("sidecar_url").as[String].toOption,
        Some("http://127.0.0.1:9999"), "the per-field merge preserves the control slot")
      _ = assertEquals(fields.hcursor.downField("allowed_ilink_user_ids").as[String].toOption,
        Some("wxid_friend_a"), "the per-field merge preserves the gate slot")
      _ = assert(SocialChannels.isEnabled(root, "weixin-ilink"), "scan-bind flips enabled")
      // secret side: one file, exact plaintext, owner-only mode from creation
      secretFile = root / "secrets" / "social-weixin-bot-token"
      _ = assert(os.exists(secretFile), "credential file exists")
      _ = assertEquals(os.read(secretFile), Token)
      _ = assertEquals(
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(secretFile.toNIO)),
        "rw-------")
      // activation leg: the ONE sync point fired exactly once
      count <- fired.get
      _ = assertEquals(count, 1, "the ONE activation point fires exactly once")
      _ = assert(SocialChannels.verified(root, "weixin-ilink"),
        "the scan-bind credential set must reach verified")
    yield ()
  }

  // ───────────────────────── single flight (⑧) ─────────────────────────

  test("WSB-3 single-flight: a newer begin supersedes the open session and severs its result path") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      sb = new WeixinIlinkScanBind(root,
        loginFn = fakeGate(gate, LoginDone),
        activate = fired.update(_ + 1))
      b1 <- sb.begin()
      id1 = scanIdOf(b1)
      _ <- waitFor(sb, id1)(stateOf(_) == Some("qr_ready"))
      b2 <- sb.begin()
      id2 = scanIdOf(b2)
      _ <- waitFor(sb, id2)(stateOf(_) == Some("qr_ready"))
      st1 <- waitFor(sb, id1)(stateOf(_) == Some("failed"))
      _ = assertEquals(st1.hcursor.downField("error").as[String].toOption,
        Some(WeixinIlinkScanBind.SupersededReason))
      // let both login calls unwind — the superseded one must NOT persist
      _ <- gate.complete(())
      _ <- waitFor(sb, id2)(stateOf(_) == Some("done"))
      st1b <- sb.status(id1).map(_.getOrElse(Json.obj()))
      _ = assertEquals(stateOf(st1b), Some("failed"), "a superseded session never reaches done")
      count <- fired.get
      _ = assertEquals(count, 1, "only the surviving session persists + activates")
    yield ()
  }

  test("WSB-4 a late login event cannot overwrite a terminal state (guarded advance)") {
    for
      gate <- Deferred[IO, Unit]
      lateEmit <- Ref.of[IO, Boolean](false)
      root <- IO(tmpRoot())
      sb = new WeixinIlinkScanBind(root,
        loginFn = fakeGate(gate, LoginDone, lateEmit = lateEmit),
        activate = IO.unit)
      b1 <- sb.begin()
      id1 = scanIdOf(b1)
      _ <- waitFor(sb, id1)(stateOf(_) == Some("qr_ready"))
      _ <- sb.begin() // supersede id1 → Failed (single-flight ⑧)
      _ <- waitFor(sb, id1)(stateOf(_) == Some("failed"))
      // the abandoned login unwinds AFTER the supersession and emits one more
      // POLLING event — red-by-construction if the state write is not guarded
      // (Polling would overwrite Failed)
      _ <- lateEmit.set(true)
      _ <- gate.complete(())
      _ <- IO.sleep(300.millis)
      st1 <- sb.status(id1).map(_.getOrElse(Json.obj()))
      _ = assertEquals(stateOf(st1), Some("failed"), "late event must not resurrect the session")
    yield ()
  }

  // ───────────────────────── failure families ─────────────────────────

  test("WSB-5 the failure families land in Failed with clean, secret-free reasons") {
    val cases = List[(String, Exception)](
      ("expired", new WeixinIlinkScanBind.LoginExpiredException),
      ("sidecar_unreachable", new WeixinIlinkScanBind.SidecarUnreachableException(null)),
      ("internal_error", new RuntimeException("boom"))
    )
    cases.traverse_ { (kind, e) =>
      for
        root <- IO(tmpRoot())
        sb = new WeixinIlinkScanBind(root, loginFn = fakeThrow(e), activate = IO.unit)
        beginJson <- sb.begin()
        scanId = scanIdOf(beginJson)
        st <- waitFor(sb, scanId)(stateOf(_) == Some("failed"))
        err = st.hcursor.downField("error").as[String].getOrElse("")
        _ = assert(clue(err).startsWith(kind), s"expected $kind prefix, got: $err")
        _ = assert(!st.noSpaces.contains(Token), "failure payload carries no secret")
      yield ()
    }
  }

  test("WSB-5b a side-car-reported failure surfaces its own reason") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      sb = new WeixinIlinkScanBind(root,
        loginFn = fakeGate(gate, WeixinIlinkScanBind.LoginResult.Failed("user denied")),
        activate = IO.unit)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(())
      st <- waitFor(sb, scanId)(stateOf(_) == Some("failed"))
      _ = assertEquals(st.hcursor.downField("error").as[String].toOption, Some("user denied"))
    yield ()
  }

  test("WSB-6 a save-side rejection (secrets path blocked) → Failed, zero credential residue") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      // block the secrets directory: a FILE where the directory must be
      _ <- IO(os.write(root / "secrets", "not a directory"))
      sb = new WeixinIlinkScanBind(root,
        loginFn = fakeGate(gate, LoginDone),
        activate = fired.update(_ + 1))
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(()) // release the fake: the rejected save path must still terminate
      st <- waitFor(sb, scanId)(stateOf(_) == Some("failed"))
      _ = assert(st.hcursor.downField("error").as[String].toOption.exists(_.startsWith("could not store")),
        clue(st.noSpaces))
      count <- fired.get
      _ = assertEquals(count, 0, "no activation on a failed save")
    yield ()
  }

  // ───────────────────────── pure helpers ─────────────────────────

  test("WSB-7 user_code extraction from the verification URL") {
    assertEquals(WeixinIlinkScanBind.extractUserCode(QrUrl), "ABCD-1234")
    assertEquals(WeixinIlinkScanBind.extractUserCode("https://x/?from=sidecar&user_code=ZZZZ-9999"), "ZZZZ-9999")
    assertEquals(WeixinIlinkScanBind.extractUserCode("https://x/"), "")
    assertEquals(WeixinIlinkScanBind.extractUserCode("not a url"), "")
    assertEquals(WeixinIlinkScanBind.extractUserCode("https://x/?user_code="), "")
  }

  private def LoginDone = WeixinIlinkScanBind.LoginResult.Done(
    WeixinIlinkScanBind.WeixinCredentials(Token, BotId, UserId))

end WeixinIlinkScanBindSpec
