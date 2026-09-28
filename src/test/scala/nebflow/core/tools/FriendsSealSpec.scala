package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.FriendsSealKit
import nebflow.agent.AgentCore
import nebflow.actor.AgentDef // W1 shim: main had nebflow.agent.AgentDef; the merge moved it to actor
import nebflow.core.FriendsSeal

/** friendseal batch (2026-09-25) — the seal's own contract (mechanical face of
  * the author's five rulings):
  *
  *  - sealed default (no config key): `SendMessage` exposes the device-only
  *    face; friend/group/local calls fail closed with `FRIENDS_SEALED` before
  *    any resolution or send work; `ListFriends` is stripped from the Nebula
  *    delivery face (the static set itself stays untouched — registry-full-set
  *    semantics; size = constant − 1 via the single kit helper).
  *  - the device leg is never sealed: its own validation errors still surface.
  *  - unseal (config `features.friends: true` + restart — the spec seam lifts
  *    the same latch): every face restores verbatim; the sealed gate stops
  *    matching and the arms run their existing fail-loud paths.
  *  - the config-parse half of the latch: only the literal boolean `true`
  *    unseals; absent / false / wrong type / garbage => sealed.
  */
class FriendsSealSpec extends CatsEffectSuite:

  private def ctx = ToolContext(projectRoot = "/tmp")

  private def defNebula = AgentDef(name = "Nebula", description = "", tools = Nil)

  private def call(to: String, extra: (String, Json)*): IO[Either[ToolError, String]] = {
    val base: Map[String, Json] = Map("to" -> to.asJson, "message" -> "hi".asJson) ++ extra.toMap
    FriendMessageTool.call(JsonObject.fromMap(base), ctx)
  }

  // ══════════ sealed default — description / schema face ══════════

  test("sealed default: description is the device-only face (friend/group/local wording gone, seal named)"):
    val d = FriendMessageTool.description
    assert(d.contains("`device:<deviceName|deviceId>`"), "the device kind is documented")
    assert(!d.contains("Four target kinds"), "the four-kind face is gone")
    assert(!d.contains("`friend:<remark"), "no friend prefix doc")
    assert(!d.contains("`group:<groupName"), "no group prefix doc")
    assert(d.contains("FRIENDS_SEALED"), "the seal is named (a model remembering the wider face understands the refusal)")
    assert(!d.contains("use `Mail` with the `device` parameter"), "stale Mail-device pointer stays gone in both states")

  test("sealed default: schema narrows (no friend/group/local prefix docs, overwrite dropped, required intact)"):
    val schema = FriendMessageTool.inputSchema
    val to = schema("properties").flatMap(_.asObject).flatMap(_("to")).flatMap(_.asObject)
    assert(to.isDefined)
    val toDesc = to.flatMap(_("description")).flatMap(_.asString).getOrElse("")
    assert(!toDesc.contains("`friend:"), "no friend prefix doc")
    assert(!toDesc.contains("`group:"), "no group prefix doc")
    assert(toDesc.contains("`device:<deviceName|deviceId>`"), "device kind documented")
    val keys = schema("properties").flatMap(_.asObject) match
      case Some(o) => o.keys.toList.sorted
      case None    => Nil
    assertEquals(keys, List("attachments", "message", "targetDir", "to"),
      "overwrite (local-copy-only parameter) is dropped from the sealed face")
    assertEquals(schema("required"), Some(List("to", "message").asJson), "required face unchanged")

  // ══════════ sealed default — the fail-closed call gate ══════════

  test("sealed default: friend target => FRIENDS_SEALED, nothing sent"):
    call("friend:someone").map {
      case Left(err) =>
        assert(err.message.contains(FriendMessageTool.ErrFriendsSealed), s"got: ${err.message}")
        assert(err.message.contains("nothing was sent"), "explicit non-delivery is stated")
        assert(err.message.contains("device:"), "the exit path names the surviving leg")
      case Right(r) => fail(s"expected the seal error, got: $r")
    }

  test("sealed default: bare name (legacy friend form) and group/local all refuse"):
    val checks = List("王选", "group:grp-1", "local").map { to =>
      call(to).map {
        case Left(err) =>
          assert(err.message.contains(FriendMessageTool.ErrFriendsSealed), s"to=$to got: ${err.message}")
        case Right(r) => fail(s"to=$to expected the seal error, got: $r")
      }
    }
    checks.foldLeft(IO.unit)(_ *> _).void

  test("sealed default: the DEVICE arm is never sealed (its own validation still surfaces)"):
    call("device:nonexistent-peer").map {
      case Left(err) =>
        assert(!err.message.contains(FriendMessageTool.ErrFriendsSealed), s"device arm must not hit the seal: ${err.message}")
        assert(err.message.contains("Device messaging is unavailable") || err.message.contains("No peer devices"),
          s"the device arm's own fail-loud path ran: ${err.message}")
      case Right(r) => fail(s"expected a device-leg error, got: $r")
    }

  // ══════════ sealed default — the Nebula delivery face ══════════

  test("sealed default: ListFriends stripped from the delivery face; size = constant − 1 (single helper)"):
    val delivered = AgentCore.fixedToolsFor(defNebula)
    assert(!delivered.contains("ListFriends"), "the roster tool's name never reaches the LLM")
    assert(delivered.contains("SendMessage"), "SendMessage stays (device leg)")
    assertEquals(delivered.size, FriendsSealKit.expectedNebulaSize(delivered),
      "flag-aware size via the single derivation point (never a bare number)")
    assertEquals(AgentCore.RootOrchestrationTools.size, AgentCore.RootOrchestrationToolsExpectedSize,
      "the STATIC set is untouched (registry-full-set semantics)")

  // ══════════ unseal — every face restores ══════════

  test("unseal: description/schema restore the full face"):
    FriendsSealKit.withUnsealedSync {
      val d = FriendMessageTool.description
      assert(d.contains("Four target kinds"), "the four-kind face is back")
      assert(d.contains("`friend:<remark|username|email|displayName>`"), "friend prefix doc restored")
      assert(d.contains("`group:<groupName|groupId>`"), "group prefix doc restored")
      assert(!d.contains("FRIENDS_SEALED"), "no seal wording in the full face")
      assert(!d.contains("use `Mail` when the peer's agent must know"), "the stale Mail-device pointer stays gone (mailmodel retired that leg)")
      val hasOverwrite = FriendMessageTool.inputSchema("properties").flatMap(_.asObject) match
        case Some(o) => o.keys.exists(_ == "overwrite")
        case None    => false
      assert(hasOverwrite, "the local parameter is back")
    }

  test("unseal: the friend/group/local arms pass the gate and run their existing fail-loud paths"):
    // 🔴 construction-inside-the-region: `FriendMessageTool.call` is an EAGER
    // def (the guard and the error construction run in the def body, before
    // the returned IO is ever run) — so building the calls must itself happen
    // AFTER testUnseal, i.e. inside IO.defer. Production is unaffected (call()
    // runs at the actual tool-call moment; the latch is a boot-time constant).
    //
    // The friend/group arms' SERVICE-dependent wording is deliberately NOT pinned
    // here: `FriendMessageTool.service` is the global assembly seam, so a co-resident
    // suite may or may not have initialized it by the time this suite runs (service
    // absent => "unavailable"; present with an empty roster => "not found" + candidates).
    // THIS spec owns the GATE contract only: after unseal the seal stops matching and
    // the arm still fails LOUD and explicit (never the seal code, never a fake success).
    // The arm-specific wording faces are pinned by their own suites
    // (FriendMessageToolSpec / GroupSendMessageSpec) under the same lifted latch.
    FriendsSealKit.withUnsealed(IO.defer {
      val checks = List(
        "friend:someone",
        "group:grp-1",
        "local" // local: pre-service validation — deterministic, pinned exactly
      ).map { to =>
        call(to, "attachments" -> List.empty[String].asJson).map {
          case Left(err) =>
            assert(err.message.nonEmpty, s"to=$to must fail loud with a readable error")
            assert(
              !err.message.contains(FriendMessageTool.ErrFriendsSealed),
              s"to=$to must pass the gate after unseal (no seal code), got: ${err.message}"
            )
            if to == "local" then
              assert(err.message.contains("requires `attachments`"), s"to=local expected its own required-field error, got: ${err.message}")
          case Right(r) => fail(s"to=$to expected an arm error, got: $r")
        }
      }
      checks.foldLeft(IO.unit)(_ *> _).void
    })

  test("unseal: ListFriends returns to the delivery face; size = constant"):
    FriendsSealKit.withUnsealedSync {
      val delivered = AgentCore.fixedToolsFor(defNebula)
      assert(delivered.contains("ListFriends"), "the roster tool is back on the face")
      assertEquals(delivered.size, AgentCore.RootOrchestrationToolsExpectedSize)
    }

  // ══════════ the latch's config parse (unseal path contract) ══════════

  test("config parse: only the literal boolean true unseals — absent/false/wrong-type/garbage => sealed"):
    assertEquals(FriendsSeal.parseEnabled("""{"features":{"friends":true}}"""), true)
    assertEquals(FriendsSeal.parseEnabled("""{"features":{"friends":false}}"""), false)
    assertEquals(FriendsSeal.parseEnabled("""{"features":{}}"""), false)
    assertEquals(FriendsSeal.parseEnabled("""{}"""), false)
    assertEquals(FriendsSeal.parseEnabled("""{"features":{"friends":"yes"}}"""), false, "wrong type never unseals")
    assertEquals(FriendsSeal.parseEnabled("""not json at all"""), false)
end FriendsSealSpec
