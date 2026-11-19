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

/**
 * friendseal batch (2026-09-25) + 功能开放批 (2026-10-30, author order: open the
 * friends feature — the latch default flipped to UNSEALED; only a literal
 * `features.friends: false` seals) — the seal's own contract (mechanical face
 * of the author's rulings):
 *
 *  - OPEN default (post-flip): `SendMessage` exposes the full four-kind face;
 *    friend/group/local calls run their normal paths; `ListFriends` rides the
 *    Nebula delivery face.
 *  - the device leg is never sealed: its own validation errors still surface.
 *  - forced seal (the spec seam pins the sealed posture deterministically):
 *    `SendMessage` exposes the device-only face; friend/group/local calls fail
 *    closed with `FRIENDS_SEALED` before any resolution or send work;
 *    `ListFriends` is stripped from the Nebula delivery face (the static set
 *    itself stays untouched — registry-full-set semantics; size = constant − 1
 *    via the single kit helper).
 *  - the config-parse half of the latch: POST-FLIP only the literal boolean
 *    `false` seals; absent / true / wrong type / garbage => unsealed.
 */
class FriendsSealSpec extends CatsEffectSuite:

  private def ctx = ToolContext(projectRoot = "/tmp")

  private def defNebula = AgentDef(name = "Nebula", description = "", tools = Nil)

  private def call(to: String, extra: (String, Json)*): IO[Either[ToolError, String]] =
    val base: Map[String, Json] = Map("to" -> to.asJson, "message" -> "hi".asJson) ++ extra.toMap
    FriendMessageTool.call(JsonObject.fromMap(base), ctx)

  // ══════════ open default (功能开放批 2026-10-30) — the full face ══════════

  test("open default: description is the full four-kind face (no seal wording)"):
    val d = FriendMessageTool.description
    assert(d.contains("Four target kinds"), "the four-kind face is present")
    assert(d.contains("`friend:<remark|username|email|displayName>`"), "friend prefix doc")
    assert(d.contains("`group:<groupName|groupId>`"), "group prefix doc")
    assert(d.contains("## Scope (author ruling 2026-10-31)"), "the SendMessage/Pop boundary scope section is present")
    assert(d.contains("NEVER go through SendMessage"), "the channel-delivery ban is spelled out")
    assert(!d.contains("FRIENDS_SEALED"), "no seal wording in the open face")
    assert(!d.contains("use `Mail` with the `device` parameter"), "stale Mail-device pointer stays gone in both states")
    assert(!FriendsSeal.isSealed, "the flipped latch is open by default (no config in spec JVMs)")

  test("open default: schema carries the full prefix docs and the overwrite parameter"):
    val schema = FriendMessageTool.inputSchema
    val to = schema("properties").flatMap(_.asObject).flatMap(_("to")).flatMap(_.asObject)
    assert(to.isDefined)
    val toDesc = to.flatMap(_("description")).flatMap(_.asString).getOrElse("")
    assert(toDesc.contains("`friend:"), "friend prefix doc")
    assert(toDesc.contains("`group:"), "group prefix doc")
    assert(toDesc.contains("`device:<deviceName|deviceId>`"), "device kind documented")
    val keys = schema("properties").flatMap(_.asObject) match
      case Some(o) => o.keys.toList.sorted
      case None => Nil
    assertEquals(
      keys,
      List("attachments", "message", "overwrite", "targetDir", "to"),
      "overwrite (local-copy-only parameter) is back on the open face"
    )
    assertEquals(schema("required"), Some(List("to", "message").asJson), "required face unchanged")

  test("open default: the friend/group/local arms pass the gate and fail loud on their own paths"):
    // 🔴 construction-inside-the-region: `FriendMessageTool.call` is an EAGER
    // def — building the calls must itself happen inside IO.defer where the
    // latch state matters (see the forced-seal variant below).
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
            s"to=$to must pass the gate under the open default (no seal code), got: ${err.message}"
          )
          if to == "local" then
            assert(
              err.message.contains("requires `attachments`"),
              s"to=local expected its own required-field error, got: ${err.message}"
            )
        case Right(r) => fail(s"to=$to expected an arm error, got: $r")
      }
    }
    checks.foldLeft(IO.unit)(_ *> _).void

  test("open default: ListFriends rides the Nebula delivery face; size = constant-derived"):
    val delivered = AgentCore.fixedToolsFor(defNebula)
    assert(delivered.contains("ListFriends"), "the roster tool's name reaches the LLM")
    assert(delivered.contains("SendMessage"), "SendMessage stays")
    assertEquals(
      delivered.size,
      FriendsSealKit.expectedNebulaSize(delivered),
      "flag-aware size via the single derivation point (never a bare number)"
    )
    assertEquals(
      AgentCore.RootOrchestrationTools.size,
      AgentCore.RootOrchestrationToolsExpectedSize,
      "the STATIC set is untouched (registry-full-set semantics)"
    )

  // ══════════ forced seal — description / schema face ══════════

  test("forced seal: description is the device-only face (friend/group/local wording gone, seal named)"):
    FriendsSealKit.withForceSealedSync {
      val d = FriendMessageTool.description
      assert(d.contains("`device:<deviceName|deviceId>`"), "the device kind is documented")
      assert(!d.contains("Four target kinds"), "the four-kind face is gone")
      assert(!d.contains("`friend:<remark"), "no friend prefix doc")
      assert(!d.contains("`group:<groupName"), "no group prefix doc")
      assert(
        d.contains("FRIENDS_SEALED"),
        "the seal is named (a model remembering the wider face understands the refusal)"
      )
      assert(d.contains("## Scope (author ruling 2026-10-31)"), "the boundary scope section rides the sealed face too")
      assert(d.contains("never go through SendMessage"), "the channel-delivery ban is spelled out on the sealed face")
      assert(!d.contains("use `Mail` with the `device` parameter"), "stale Mail-device pointer stays gone in both states")
    }

  test("forced seal: schema narrows (no friend/group/local prefix docs, overwrite dropped, required intact)"):
    FriendsSealKit.withForceSealedSync {
      val schema = FriendMessageTool.inputSchema
      val to = schema("properties").flatMap(_.asObject).flatMap(_("to")).flatMap(_.asObject)
      assert(to.isDefined)
      val toDesc = to.flatMap(_("description")).flatMap(_.asString).getOrElse("")
      assert(!toDesc.contains("`friend:"), "no friend prefix doc")
      assert(!toDesc.contains("`group:"), "no group prefix doc")
      assert(toDesc.contains("`device:<deviceName|deviceId>`"), "device kind documented")
      val keys = schema("properties").flatMap(_.asObject) match
        case Some(o) => o.keys.toList.sorted
        case None => Nil
      assertEquals(
        keys,
        List("attachments", "message", "targetDir", "to"),
        "overwrite (local-copy-only parameter) is dropped from the sealed face"
      )
      assertEquals(schema("required"), Some(List("to", "message").asJson), "required face unchanged")
    }

  // ══════════ forced seal — the fail-closed call gate ══════════

  test("forced seal: friend target => FRIENDS_SEALED, nothing sent"):
    FriendsSealKit.withForceSealed(IO.defer {
      call("friend:someone").map {
        case Left(err) =>
          assert(err.message.contains(FriendMessageTool.ErrFriendsSealed), s"got: ${err.message}")
          assert(err.message.contains("nothing was sent"), "explicit non-delivery is stated")
          assert(err.message.contains("device:"), "the exit path names the surviving leg")
        case Right(r) => fail(s"expected the seal error, got: $r")
      }
    })

  test("forced seal: bare name (legacy friend form) and group/local all refuse"):
    FriendsSealKit.withForceSealed(IO.defer {
      val checks = List("王选", "group:grp-1", "local").map { to =>
        call(to).map {
          case Left(err) =>
            assert(err.message.contains(FriendMessageTool.ErrFriendsSealed), s"to=$to got: ${err.message}")
          case Right(r) => fail(s"to=$to expected the seal error, got: $r")
        }
      }
      checks.foldLeft(IO.unit)(_ *> _).void
    })

  test("forced seal: the DEVICE arm is never sealed (its own validation still surfaces)"):
    FriendsSealKit.withForceSealed(IO.defer {
      call("device:nonexistent-peer").map {
        case Left(err) =>
          assert(
            !err.message.contains(FriendMessageTool.ErrFriendsSealed),
            s"device arm must not hit the seal: ${err.message}"
          )
          assert(
            err.message.contains("Device messaging is unavailable") || err.message.contains("No peer devices"),
            s"the device arm's own fail-loud path ran: ${err.message}"
          )
        case Right(r) => fail(s"expected a device-leg error, got: $r")
      }
    })

  // ══════════ forced seal — the Nebula delivery face ══════════

  test("forced seal: ListFriends stripped from the delivery face; size = constant − 1 (single helper)"):
    FriendsSealKit.withForceSealedSync {
      val delivered = AgentCore.fixedToolsFor(defNebula)
      assert(!delivered.contains("ListFriends"), "the roster tool's name never reaches the LLM")
      assert(delivered.contains("SendMessage"), "SendMessage stays (device leg)")
      assertEquals(
        delivered.size,
        FriendsSealKit.expectedNebulaSize(delivered),
        "flag-aware size via the single derivation point (never a bare number)"
      )
      assertEquals(
        AgentCore.RootOrchestrationTools.size,
        AgentCore.RootOrchestrationToolsExpectedSize,
        "the STATIC set is untouched (registry-full-set semantics)"
      )
    }

  // ══════════ the latch's config parse (post-flip contract) ══════════

  test("config parse: only the literal boolean false seals — absent/true/wrong-type/garbage => unsealed (功能开放批)"):
    assertEquals(FriendsSeal.parseEnabled("""{"features":{"friends":true}}"""), true)
    assertEquals(FriendsSeal.parseEnabled("""{"features":{"friends":false}}"""), false)
    assertEquals(FriendsSeal.parseEnabled("""{"features":{}}"""), true)
    assertEquals(FriendsSeal.parseEnabled("""{}"""), true)
    assertEquals(FriendsSeal.parseEnabled("""{"features":{"friends":"no"}}"""), true, "wrong type never seals")
    assertEquals(FriendsSeal.parseEnabled("""not json at all"""), true)
end FriendsSealSpec
