package nebflow.social

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*

/**
 * Offline regression spec for the weixin scan-bind manager (weixin-scanbind
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

  /**
   * Poll the status face until `pred` holds (bounded — a regression must fail
   * fast, not hang the suite).
   */
  private def waitFor(sb: WeixinIlinkScanBind, scanId: String)(pred: Json => Boolean): IO[Json] =
    (IO.sleep(50.millis) *> sb.status(scanId).map(_.getOrElse(Json.obj())))
      .iterateUntil(pred)
      .timeout(10.seconds)

  /**
   * The offline stand-in for the side-car control leg: emits the real QR
   * event through the options' own callback, blocks on `gate`, and — when
   * `lateEmit` is armed — re-emits a POLLING event after the gate (the
   * late-callback probe for WSB-4). POLLING is NEVER emitted up front: the
   * real side-car delivers it a poll interval later.
   */
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

  test(
    "WSB-2 done: save reuses the existing write path (_ref only, rw------- file), " +
      "activation fires once, verified reachable, the merge keeps sidecar fields"
  ) {
    posixOnly()
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      // pre-existing slots the scan-bind save must NOT disturb (per-field
      // merge). 🔴 The pre-save must SUCCEED for the merge reading to have
      // teeth — a rejected pre-save would leave nothing to preserve and the
      // assertions below would pass vacuously.
      pre <- IO(
        SocialChannels.save(
          root,
          "weixin-ilink",
          Json.obj(
            "enabled" -> false.asJson,
            "fields" -> Json.obj(
              "sidecar_url" -> "http://127.0.0.1:9999".asJson,
              "allowed_ilink_user_ids" -> "wxid_friend_a".asJson
            )
          )
        )
      )
      _ = assert(clue(pre).isRight, "the pre-save must land (a rejected save voids the merge pin)")
      sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone), activate = fired.update(_ + 1))
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      // release the fake immediately: this leg pins the COMPLETION path
      _ <- gate.complete(())
      st <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
      _ = assertEquals(st.hcursor.downField("botId").as[String].toOption, Some(BotId))
      _ = assert(!st.noSpaces.contains(Token), "done payload carries the bot id only")
      // config side: identifiers plaintext, secret as _ref, slots preserved
      fields = SocialChannels
        .readChannels(root)
        .hcursor
        .downField("channels")
        .downField("weixin-ilink")
        .downField("fields")
        .focus
        .getOrElse(Json.obj())
      _ = assertEquals(fields.hcursor.downField("ilink_bot_id").as[String].toOption, Some(BotId))
      _ = assertEquals(fields.hcursor.downField("ilink_user_id").as[String].toOption, Some(UserId))
      _ = assert(
        fields.hcursor.downField("bot_token_ref").as[String].toOption.exists(_.nonEmpty),
        "config keeps only the _ref path"
      )
      _ = assert(fields.hcursor.downField("bot_token").as[String].isLeft, "no plaintext secret key in config")
      _ = assertEquals(
        fields.hcursor.downField("sidecar_url").as[String].toOption,
        Some("http://127.0.0.1:9999"),
        "the per-field merge preserves the control slot"
      )
      _ = assertEquals(
        fields.hcursor.downField("allowed_ilink_user_ids").as[String].toOption,
        Some("wxid_friend_a"),
        "the per-field merge preserves the gate slot"
      )
      _ = assert(SocialChannels.isEnabled(root, "weixin-ilink"), "scan-bind flips enabled")
      // secret side: one file, exact plaintext, owner-only mode from creation
      secretFile = root / "secrets" / "social-weixin-bot-token"
      _ = assert(os.exists(secretFile), "credential file exists")
      _ = assertEquals(os.read(secretFile), Token)
      _ = assertEquals(
        PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(secretFile.toNIO)),
        "rw-------"
      )
      // activation leg: the ONE sync point fired exactly once
      count <- fired.get
      _ = assertEquals(count, 1, "the ONE activation point fires exactly once")
      _ = assert(SocialChannels.verified(root, "weixin-ilink"), "the scan-bind credential set must reach verified")
    yield ()
    end for
  }

  // ───────────────────────── single flight (⑧) ─────────────────────────

  test("WSB-3 single-flight: a newer begin supersedes the open session and severs its result path") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone), activate = fired.update(_ + 1))
      b1 <- sb.begin()
      id1 = scanIdOf(b1)
      _ <- waitFor(sb, id1)(stateOf(_) == Some("qr_ready"))
      b2 <- sb.begin()
      id2 = scanIdOf(b2)
      _ <- waitFor(sb, id2)(stateOf(_) == Some("qr_ready"))
      st1 <- waitFor(sb, id1)(stateOf(_) == Some("failed"))
      _ = assertEquals(st1.hcursor.downField("error").as[String].toOption, Some(WeixinIlinkScanBind.SupersededReason))
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
      sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone, lateEmit = lateEmit), activate = IO.unit)
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
      sb = new WeixinIlinkScanBind(
        root,
        loginFn = fakeGate(gate, WeixinIlinkScanBind.LoginResult.Failed("user denied")),
        activate = IO.unit
      )
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(())
      st <- waitFor(sb, scanId)(stateOf(_) == Some("failed"))
      _ = assertEquals(st.hcursor.downField("error").as[String].toOption, Some("user denied"))
    yield ()
  }

  test(
    "WSB-8 the production side-car leg wraps a transport refusal into " +
      "sidecar_unreachable (never a raw ConnectException)"
  ) {
    // Offline by construction: bind a loopback port, close it, dial it — the
    // refusal is a ConnectException on 127.0.0.1 and NO side-car, host or
    // iLink protocol is ever touched (nothing is there to answer; a real
    // login never starts). Regression pin for the live diagnosis 2026-10-05:
    // the absent side-car surfaced as "internal_error" because get() wrapped
    // only the non-2xx status, letting the raw send() IOException fall
    // through to loginFiber's generic arm — the card mislabelled exactly the
    // one failure (side-car not up) the vocabulary exists to name.
    val socket = new java.net.ServerSocket(0)
    val port = socket.getLocalPort
    socket.close()
    for
      root <- IO(tmpRoot())
      pre <- IO(
        SocialChannels.save(
          root,
          "weixin-ilink",
          Json.obj("enabled" -> false.asJson, "fields" -> Json.obj("sidecar_url" -> s"http://127.0.0.1:$port".asJson))
        )
      )
      _ = assert(clue(pre).isRight, "the sidecar_url pre-save must land")
      // the adapter alone: the thrown exception must already be in vocabulary
      thrown <- IO(WeixinIlinkScanBind.sidecarLogin(root)(WeixinIlinkScanBind.LoginOptions(emit = _ => ()))).attempt
      _ = thrown match
        case Left(_: WeixinIlinkScanBind.SidecarUnreachableException) => ()
        case Left(other) =>
          fail(s"expected SidecarUnreachableException, got ${other.getClass.getName}: ${other.getMessage}")
        case Right(_) => fail("dialing a closed port must throw")
      // end-to-end through the manager: the card's reason string follows
      sb = new WeixinIlinkScanBind(root, loginFn = WeixinIlinkScanBind.sidecarLogin(root), activate = IO.unit)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      st <- waitFor(sb, scanId)(stateOf(_) == Some("failed"))
      _ = assert(
        st.hcursor.downField("error").as[String].toOption.exists(_.startsWith("sidecar_unreachable")),
        clue(st.noSpaces)
      )
    yield ()
    end for
  }

  test("WSB-6 a save-side rejection (secrets path blocked) → Failed, zero credential residue") {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      fired <- Ref.of[IO, Int](0)
      // block the secrets directory: a FILE where the directory must be
      _ <- IO(os.write(root / "secrets", "not a directory"))
      sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone), activate = fired.update(_ + 1))
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(()) // release the fake: the rejected save path must still terminate
      st <- waitFor(sb, scanId)(stateOf(_) == Some("failed"))
      _ = assert(
        st.hcursor.downField("error").as[String].toOption.exists(_.startsWith("could not store")),
        clue(st.noSpaces)
      )
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

  // ───────────────── 绑定即通 legs (weixin bind-through batch, 2026-10-11) ─────────────────
  // The composed activate the gateway wiring hands over
  // ([[WeixinBindAutoWire.productionActivate]]): sync (None here — offline)
  // → defaultSessionId write → sidecar provision. Everything below is
  // offline: the provision runner is an injected recorder, zero real launchd.

  /** A fake engine run-dir carrying the installer at the repo-relative spot. */
  private def fakeRepo(): os.Path =
    val dir = os.temp.dir(prefix = "nb-bindthrough-repo-")
    os.makeDir.all(dir / "tools" / "weixin-sidecar")
    os.write(dir / "tools" / "weixin-sidecar" / "install-launchd.sh", "#!/bin/bash\n")
    dir

  private def recorder(
    recordings: Ref[IO, List[(os.Path, Map[String, String])]],
    outcome: Either[String, (Int, String)] = Right((0, "installed: /tmp/fake.plist\nstate: running"))
  ): WeixinBindAutoWire.Runner = (script, env) => recordings.update(_ :+ ((script, env))).as(outcome)

  private def metaOf(id: String, updatedAt: Long) =
    nebflow.shared.SessionMeta(id, id, updatedAt, updatedAt, hasUnread = false)

  test(
    "BT-1 done: the binding-time latest session lands on the weixin card's defaultSessionId " +
      "and the provision leg records the contract-v2 env"
  ) {
    posixOnly()
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      repo <- IO(fakeRepo())
      // a pre-existing control slot the binding save must not disturb; its
      // port is what WEIXIN_SIDECAR_PORT must carry (never a literal here)
      pre <- IO(
        SocialChannels.save(
          root,
          "weixin-ilink",
          Json.obj("enabled" -> false.asJson, "fields" -> Json.obj("sidecar_url" -> "http://127.0.0.1:19999".asJson))
        )
      )
      _ = assert(clue(pre).isRight)
      recordings <- Ref.of[IO, List[(os.Path, Map[String, String])]](Nil)
      now <- IO(System.currentTimeMillis())
      older = metaOf("sess-old", now)
      newer = metaOf("sess-new", now + 5000)
      activate = WeixinBindAutoWire.productionActivate(
        bridgeManager = None,
        root = root,
        listSessions = () => IO.pure(List(older, newer)),
        gatewayPort = 18097,
        runner = recorder(recordings),
        pwd = repo
      )
      sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone), activate = activate)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(())
      st <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
      _ = assertEquals(st.hcursor.downField("botId").as[String].toOption, Some(BotId))
      // leg ①: the NEWEST session (max updatedAt), on the weixin card only
      _ = assertEquals(SocialChannels.defaultSessionId(root, "weixin-ilink"), Some("sess-new"))
      _ = assertEquals(
        SocialChannels.defaultSessionId(root, "feishu"),
        None,
        "the write is keyed to the weixin-ilink card alone"
      )
      // leg ②: one recorded invocation, repo-relative script, v2 env matrix
      recs <- recordings.get
      _ = assertEquals(recs.size, 1, "the provision leg fires exactly once per binding")
      (script, env) = recs.head
      _ = assertEquals(script, repo / "tools" / "weixin-sidecar" / "install-launchd.sh")
      _ = assertEquals(env(WeixinBindAutoWire.KeySidecarPort), "19999", "port = the card sidecar_url's resolved port")
      _ = assertEquals(
        env(WeixinBindAutoWire.KeyIngressUrl),
        s"http://127.0.0.1:18097/api/social/channels/weixin-ilink/ingress",
        "ingress URL rides the gateway port"
      )
      _ = assertEquals(env(WeixinBindAutoWire.KeyMsgloopTokenFile), WeixinIngressToken.path(root).toString)
      _ = assertEquals(env(WeixinBindAutoWire.KeyNebflowHome), root.toString)
      // 🔴 forbidden zone: the shared-leg spellings are pinned EMPTY, and no
      // injected value ever names the outbound token file
      _ = assertEquals(env("WEIXIN_SEND_TOKEN_FILE"), "")
      _ = assertEquals(env("WEIXIN_SEND_TOKEN"), "")
      _ = assert(
        !env.values.exists(_.contains("weixin-ilink-send-token")),
        "no injected value may reference the outbound send token"
      )
    yield ()
    end for
  }

  test(
    "BT-2 no session at bind time: defaultSessionId stays unset (INFO, not a failure), " +
      "provision still fires, binding still Done"
  ) {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      repo <- IO(fakeRepo())
      recordings <- Ref.of[IO, List[(os.Path, Map[String, String])]](Nil)
      activate = WeixinBindAutoWire.productionActivate(
        bridgeManager = None,
        root = root,
        listSessions = () => IO.pure(Nil),
        gatewayPort = 18097,
        runner = recorder(recordings),
        pwd = repo
      )
      sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone), activate = activate)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(())
      st <- waitFor(sb, scanId)(stateOf(_) == Some("done"))
      _ = assertEquals(stateOf(st), Some("done"), "the empty-session leg must not fail the binding")
      _ = assertEquals(
        SocialChannels.defaultSessionId(root, "weixin-ilink"),
        None,
        "nothing is written when no session exists"
      )
      recs <- recordings.get
      _ = assertEquals(recs.size, 1, "the provision leg is independent of the session list")
    yield ()
  }

  test("BT-3 a failing provision (Left or nonzero exit) never flips Done off — WARN only") {
    def bindWith(outcome: Either[String, (Int, String)]): IO[Json] =
      for
        gate <- Deferred[IO, Unit]
        root <- IO(tmpRoot())
        repo <- IO(fakeRepo())
        recordings <- Ref.of[IO, List[(os.Path, Map[String, String])]](Nil)
        now <- IO(System.currentTimeMillis())
        activate = WeixinBindAutoWire.productionActivate(
          bridgeManager = None,
          root = root,
          listSessions = () => IO.pure(List(metaOf("sess-1", now))),
          gatewayPort = 18097,
          runner = recorder(recordings, outcome),
          pwd = repo
        )
        sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone), activate = activate)
        beginJson <- sb.begin()
        scanId = scanIdOf(beginJson)
        _ <- gate.complete(())
        st <- waitFor(sb, scanId)(s => stateOf(s) == Some("done") || stateOf(s) == Some("failed"))
        _ = assertEquals(
          stateOf(st),
          Some("done"),
          s"a provision outcome of $outcome must stay a WARN: the binding is Done"
        )
        _ = assertEquals(
          SocialChannels.defaultSessionId(root, "weixin-ilink"),
          Some("sess-1"),
          "the defaultSession leg lands independently of the provision outcome"
        )
      yield st

    for
      _ <- bindWith(Left("exec blew up"))
      _ <- bindWith(Right((1, "port 19999 held by a foreign listener")))
    yield ()
  }

  test(
    "BT-4 both legs are total: a raising session store AND a raising runner are contained — " +
      "the binding still lands Done with no defaultSessionId"
  ) {
    for
      gate <- Deferred[IO, Unit]
      root <- IO(tmpRoot())
      repo <- IO(fakeRepo())
      activate = WeixinBindAutoWire.productionActivate(
        bridgeManager = None,
        root = root,
        listSessions = () => IO.raiseError(new RuntimeException("store blew up")),
        gatewayPort = 18097,
        runner = (_, _) => IO.raiseError(new RuntimeException("proc blew up")),
        pwd = repo
      )
      sb = new WeixinIlinkScanBind(root, loginFn = fakeGate(gate, LoginDone), activate = activate)
      beginJson <- sb.begin()
      scanId = scanIdOf(beginJson)
      _ <- gate.complete(())
      st <- waitFor(sb, scanId)(s => stateOf(s) == Some("done") || stateOf(s) == Some("failed"))
      _ = assertEquals(stateOf(st), Some("done"), "leg failures are contained, never Failed")
      _ = assertEquals(SocialChannels.defaultSessionId(root, "weixin-ilink"), None)
    yield ()
  }

  test(
    "BT-5 the env matrix defaults: no card sidecar_url ⇒ the SidecarDefaultBase port; " +
      "forbidden keys always pinned empty"
  ) {
    for
      root <- IO(tmpRoot())
      env = WeixinBindAutoWire.envMatrix(root, gatewayPort = 18097)
      defaultPort = java.net.URI.create(WeixinIlinkScanBind.SidecarDefaultBase).getPort
      _ = assertEquals(
        env(WeixinBindAutoWire.KeySidecarPort),
        defaultPort.toString,
        "portless card falls back to the existing default constant's port"
      )
      _ = assertEquals(
        env(WeixinBindAutoWire.KeyIngressUrl),
        s"http://127.0.0.1:18097/api/social/channels/weixin-ilink/ingress"
      )
      _ = assertEquals(env(WeixinBindAutoWire.KeyMsgloopTokenFile), WeixinIngressToken.path(root).toString)
      _ = assertEquals(env(WeixinBindAutoWire.KeyNebflowHome), root.toString)
      _ = assertEquals(env("WEIXIN_SEND_TOKEN_FILE"), "")
      _ = assertEquals(env("WEIXIN_SEND_TOKEN"), "")
      // script resolution: present ⇒ Some at the repo-relative spot; absent ⇒ None
      repo <- IO(fakeRepo())
      _ = assertEquals(
        WeixinBindAutoWire.resolveScript(repo),
        Some(repo / "tools" / "weixin-sidecar" / "install-launchd.sh")
      )
      empty <- IO(os.temp.dir(prefix = "nb-bindthrough-empty-"))
      _ = assertEquals(
        WeixinBindAutoWire.resolveScript(empty),
        None,
        "no installer ⇒ the leg must skip (WARN), never guess a path"
      )
    yield ()
  }

  private def LoginDone =
    WeixinIlinkScanBind.LoginResult.Done(WeixinIlinkScanBind.WeixinCredentials(Token, BotId, UserId))

end WeixinIlinkScanBindSpec
