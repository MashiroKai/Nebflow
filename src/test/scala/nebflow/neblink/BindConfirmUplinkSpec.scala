package nebflow.neblink

import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import munit.FunSuite

/**
 * D6 bind-confirm uplink frame (phase 3c plan §8 component ①) — the shape
 * face:
 *
 *  - the frame is relay CONTROL-PLANE JSON (the `ClientToServer` family on
 *    the neblink-server side, same discipline as `ack`), NOT a portal wire
 *    contract frame — the frozen 13-frame inventory and the v1.1 vectors are
 *    untouched by this batch, and this spec pins that separation;
 *  - the canonical bytes are the alignment artifact for the server
 *    implementer (the doc comment on `bindConfirmFrame` carries the matching
 *    `ClientToServer::BindConfirm` variant sketch);
 *  - `code` travels as a JSON STRING — a leading-zero code must survive.
 *
 * The socket write path mirrors `sendAck` verbatim (same three-state
 * `AckOutcome` honesty); its live-socket mechanics are covered by the
 * ack-family specs, so here only the definition points and the absent-tunnel
 * branch run.
 */
class BindConfirmUplinkSpec extends FunSuite:

  test("canonical bytes: type, code (string), ttlSec — exact key order, compact separators"):
    val frame = NeblinkRelayTunnel.bindConfirmFrame("012345", 900L)
    assertEquals(
      frame.noSpaces,
      """{"type":"bind_confirm","code":"012345","ttlSec":900}""",
      "leading-zero code must survive as a JSON string"
    )

  test("round trip: the canonical bytes parse back into the same three fields"):
    val json = parse(NeblinkRelayTunnel.bindConfirmFrame("482913", 900L).noSpaces)
    assert(json.isRight)
    val hc = json.getOrElse(io.circe.Json.Null).hcursor
    assertEquals(hc.downField("type").as[String], Right("bind_confirm"))
    assertEquals(hc.downField("code").as[String], Right("482913"))
    assertEquals(hc.downField("ttlSec").as[Long], Right(900L))

  test("the frame type is NOT in the frozen portal wire contract inventory (control plane, not contract)"):
    assert(
      !PortalWireCodec.FrameInventory.contains("bind_confirm"),
      "bind_confirm rides the relay control plane; adding it to the contract inventory would be a re-freeze"
    )

  test("sendBindConfirmLive with no tunnel registered => honest NoLiveSocket (never a swallowed success)"):
    val outcome = NeblinkRelayTunnel.sendBindConfirmLive(None, "123456", 900L).unsafeRunSync()
    assertEquals(outcome, NeblinkRelayTunnel.AckOutcome.NoLiveSocket)

end BindConfirmUplinkSpec
