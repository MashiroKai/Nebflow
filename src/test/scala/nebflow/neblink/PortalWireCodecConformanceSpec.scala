package nebflow.neblink

import io.circe.{HCursor, Json, JsonObject}
import io.circe.parser.parse
import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Arrays, Base64}

/**
 * Portal wire contract conformance — PRODUCTION codec face (v1.3 additive
 * re-freeze; the assertion structure is the phase 3a design section 4.2 one).
 *
 * The test-side model spec (`PortalWireContractV1Spec`) and this spec run the
 * SAME frozen vector file (`src/test/resources/neblink/portal-wire-vectors-v1.3.json`,
 * byte-identical to the link-server copy) with the SAME assertion
 * structure — model face and production face cross-checked on identical
 * verdicts, which is exactly what the phase 0.5 criterion demands ("both
 * sides agree on every verdict"). The codec under test here is the one the
 * relay tunnel actually dispatches through (`RelayWsListener.handlePortalFrame`
 * → [[PortalWireCodec]]).
 *
 * Assertions per vector (identical semantics to the model spec):
 *
 *  - positive: the `json` field-level object decodes cleanly; the canonical
 *    serialization is byte-identical to the frozen `wire`; the frozen `wire`
 *    round-trips to a frame whose re-encode is byte-identical (structural
 *    frame equality is deliberately avoided — the production model carries
 *    `Array[Byte]` payloads, and re-encode equality is the stronger,
 *    byte-level form anyway).
 *  - negative: the input is rejected and the mechanical faces of the
 *    rejection match the `expect` block exactly (`reject` code, offending
 *    `field` / `fields`, `expected` type, `allowed` enum values; the
 *    free-text `reason` is narrative and not asserted).
 */
class PortalWireCodecConformanceSpec extends FunSuite:

  private val SupportedProto = 1

  /** Contract "Frame inventory": all 14 frames, exactly (frozen literal, independent of the codec's own set). */
  private val FrameInventory: Set[String] = Set(
    "relay_request",
    "relay_response",
    "http_request",
    "http_response",
    "body_frame",
    "ws_open",
    "ws_open_result",
    "ws_frame",
    "ws_data_frame",
    "ws_close",
    "ConsoleTicketMint",
    "ConsoleTicketResult",
    "BindingControl",
    "MigrationChunk"
  )

  // ────────────────────────── vector harness ──────────────────────────

  private final case class VectorEntry(
    id: String,
    frame: String,
    polarity: String,
    json: Option[Json],
    wire: Option[String],
    raw: Option[String],
    expect: Option[JsonObject]
  )

  private lazy val vectorsDoc: Json =
    val res = "neblink/portal-wire-vectors-v1.3.json"
    val in = Option(getClass.getClassLoader.getResourceAsStream(res)).getOrElse(
      fail(s"classpath resource '$res' not found — frozen vector file missing from test classpath")
    )
    try
      parse(new String(in.readAllBytes(), UTF_8))
        .fold(e => fail(s"$res is not valid JSON: ${e.getMessage}"), identity)
    finally in.close()

  private def cursorString(c: HCursor, name: String): String =
    c.get[String](name).fold(e => fail(s"vector field '$name' unreadable: ${e.getMessage}"), identity)

  private lazy val entries: List[VectorEntry] =
    vectorsDoc.hcursor
      .downField("vectors")
      .as[List[Json]]
      .fold(e => fail(s"vector list unreadable: ${e.getMessage}"), identity)
      .map { j =>
        VectorEntry(
          id = cursorString(j.hcursor, "id"),
          frame = cursorString(j.hcursor, "frame"),
          polarity = cursorString(j.hcursor, "polarity"),
          json = j.hcursor.get[Json]("json").toOption,
          wire = j.hcursor.get[String]("wire").toOption,
          raw = j.hcursor.get[String]("raw").toOption,
          expect = j.asObject.flatMap(_.apply("expect")).flatMap(_.asObject)
        )
      }

  private final case class Expect(
    reject: String,
    field: Option[String],
    fields: Option[List[String]],
    expected: Option[String],
    allowed: Option[List[String]]
  )

  private def expectOf(v: VectorEntry): Expect =
    val o = v.expect.getOrElse(fail(s"negative vector '${v.id}' lacks an expect block"))
    def str(name: String): Option[String] = o(name).flatMap(_.asString)
    Expect(
      reject = str("reject").getOrElse(fail(s"vector '${v.id}' expect block lacks 'reject'")),
      field = str("field"),
      fields = o("fields").flatMap(_.asArray).map(_.flatMap(_.asString).toList),
      expected = str("expected"),
      allowed = o("allowed").flatMap(_.asArray).map(_.flatMap(_.asString).toList)
    )

  private def assertByteIdentical(actual: String, expected: String, clue: => String)(using
    munit.Location
  ): Unit =
    assert(
      Arrays.equals(actual.getBytes(UTF_8), expected.getBytes(UTF_8)),
      s"$clue\nexpected wire bytes: $expected\n  actual encode bytes: $actual"
    )

  // ────────────────────────── tests ──────────────────────────

  test("production codec pins the frozen contract identity (contract file + version v1.3)"):
    val c = vectorsDoc.hcursor
    assertEquals(cursorString(c, "contract"), "portal-wire-contract-v1.3.md")
    assertEquals(
      cursorString(c, "version"),
      "v1.3",
      "any contract change must land as a re-freeze (v1.x) in both repos, not a silent vector edit"
    )

  test("vector frame names are exactly the codec's 14-frame inventory (no drift between faces)"):
    val pos = entries.filter(_.polarity == "positive").map(_.frame).toSet
    assertEquals(
      PortalWireCodec.FrameInventory,
      FrameInventory,
      "the production codec must carry exactly the frozen 14-frame inventory"
    )
    assertEquals(
      pos,
      FrameInventory,
      s"positive-vector coverage must be exactly the 14-frame inventory; missing=${(FrameInventory -- pos).toList.sorted}"
    )
    // New contract frame names must not collide with the coexisting frames
    // (contract section 5) — the collision set is frozen there.
    val coexisting = Set("ping", "pong", "ack", "disconnect", "device_status_update", "friend_event", "agent_mail")
    assert(
      (FrameInventory intersect coexisting).isEmpty,
      s"contract frames must never collide with coexisting frame names: ${(FrameInventory intersect coexisting).mkString(", ")}"
    )

  test("vector ids are unique (each vector registers exactly one munit test)"):
    val ids = entries.map(_.id)
    assertEquals(ids.distinct.size, ids.size, s"duplicate vector ids: $ids")

  entries.foreach { v =>
    v.wire match
      case Some(wire) =>
        test(s"${v.id} [positive · production codec]: canonical encode is byte-identical to the frozen wire"):
          val json = v.json.getOrElse(fail(s"positive vector '${v.id}' lacks a json block"))
          PortalWireCodec
            .decode(json.noSpaces)
            .fold(
              rej =>
                fail(
                  s"positive vector '${v.id}' must decode cleanly, got rejection '${rej.code}' " +
                    s"(field=${rej.field.getOrElse("none")}); input=${json.noSpaces}"
                ),
              frame =>
                assertByteIdentical(
                  PortalWireCodec.encode(frame),
                  wire,
                  s"canonical serialization of '${v.id}' drifted from the frozen wire bytes " +
                    "(key order / separators / base64 form must match the contract's canonical encoding)"
                )
                // Round-trip of the frozen wire: decode → re-encode is byte-stable.
                PortalWireCodec
                  .decode(wire)
                  .fold(
                    rej => fail(s"frozen wire of '${v.id}' must decode, got '${rej.code}'"),
                    frame2 =>
                      assertByteIdentical(PortalWireCodec.encode(frame2), wire, s"round-trip of '${v.id}' drifted")
                  )
            )

      case None =>
        test(s"${v.id} [negative · production codec]: decode is rejected exactly as the expect block demands"):
          val input = v.raw
            .orElse(v.json.map(_.noSpaces))
            .getOrElse(fail(s"negative vector '${v.id}' has neither a json nor a raw input"))
          val exp = expectOf(v)
          PortalWireCodec.decode(input) match
            case Right(frame) =>
              fail(
                s"expected rejection '${exp.reject}' but the production codec decoded: ${frame.getClass.getSimpleName}"
              )
            case Left(rej) =>
              assertEquals(rej.code, exp.reject, s"rejection code mismatch for '${v.id}'")
              exp.field.foreach(want => assertEquals(rej.field, Some(want), s"offending field mismatch for '${v.id}'"))
              exp.fields.foreach(want =>
                assertEquals(rej.fields.map(_.toSet), Some(want.toSet), s"conflicting fields mismatch for '${v.id}'")
              )
              exp.expected.foreach(want =>
                assertEquals(rej.expected, Some(want), s"expected JSON type mismatch for '${v.id}'")
              )
              exp.allowed.foreach(want =>
                assertEquals(rej.allowed.map(_.toSet), Some(want.toSet), s"allowed enum values mismatch for '${v.id}'")
              )
          end match
  }

  // ────────── envelope behaviors without frozen vectors (contract section 1,
  // mirrored from the model spec so both faces stay aligned) ──────────

  test("proto above the supported version rejects with proto_unsupported (every frame family)"):
    val proto99 =
      """{"type":"relay_response","proto":99,"requestId":"0a1b2c3d-4e5f-4678-9abc-def012345678","output":"","error":""}"""
    assertEquals(PortalWireCodec.decode(proto99).swap.toOption.map(_.code), Some("proto_unsupported"))
    val proto99relayRequest =
      """{"type":"relay_request","proto":99,"requestId":"0a1b2c3d-4e5f-4678-9abc-def012345678","action":"Bash","params":{}}"""
    assertEquals(
      PortalWireCodec.decode(proto99relayRequest).swap.toOption.map(_.code),
      Some("proto_unsupported"),
      "proto rejection has NO frame-family exception (contract section 1)"
    )

  test("unknown extra keys are ignored (forward compatibility)"):
    val extra =
      """{"type":"ws_close","proto":1,"requestId":"0a1b2c3d-4e5f-4678-9abc-def012345678","code":1000,"reason":"x","futureField":{"a":[1]}}"""
    PortalWireCodec.decode(extra) match
      case Right(_: PortalWireCodec.WsClose) => // ok
      case other => fail(s"extra keys must not break decoding: $other")
    // And the canonical encode drops them (schema fields only).
    PortalWireCodec.decode(extra).foreach { f =>
      assertEquals(
        PortalWireCodec.encode(f),
        """{"type":"ws_close","proto":1,"requestId":"0a1b2c3d-4e5f-4678-9abc-def012345678","code":1000,"reason":"x"}"""
      )
    }

  test(
    "missing proto is missing_required_field on the strict codec face (runtime leniency is the tunnel's, not the codec's)"
  ):
    val noProto =
      """{"type":"http_request","requestId":"0a1b2c3d-4e5f-4678-9abc-def012345678","method":"GET","path":"/api/x","headers":{}}"""
    assertEquals(
      PortalWireCodec.decode(noProto).swap.toOption.map(r => (r.code, r.field)),
      Some(("missing_required_field", Some("proto")))
    )

  test("ConsoleTicketResult: expiresInSec without ticket is a field_conflict (iff on BOTH success fields)"):
    // No frozen vector covers this combo (contract section 6 gap); both the
    // production codec and the test-side model reject it identically so the
    // two faces cannot diverge on it.
    val frame =
      """{"type":"ConsoleTicketResult","proto":1,"requestId":"0a1b2c3d-4e5f-4678-9abc-def012345678",""" +
        """"mintId":"1f2e3d4c-5b6a-4978-8877-6a5b4c3d2e1f","expiresInSec":30}"""
    assertEquals(
      PortalWireCodec.decode(frame).swap.toOption.map(r => (r.code, r.fields)),
      Some(("field_conflict", Some(List("ticket", "expiresInSec"))))
    )

  test("encode always carries proto:1 on the envelope (outbound policy single point)"):
    val frame =
      PortalWireCodec.WsOpen("0a1b2c3d-4e5f-4678-9abc-def012345678", "/ws?token=abc", None, None)
    assertEquals(
      PortalWireCodec.encode(frame),
      """{"type":"ws_open","proto":1,"requestId":"0a1b2c3d-4e5f-4678-9abc-def012345678","path":"/ws?token=abc"}"""
    )

  test("base64 payloads are padded RFC 4648 section 4 (data survives byte-exactly)"):
    val payload = Array[Byte](0, 1, 2, 3, 4) // 5 bytes → padded tail "=="
    val frame = PortalWireCodec.BodyFrame("0a1b2c3d-4e5f-4678-9abc-def012345678", 0, more = false, payload)
    val encoded = PortalWireCodec.encode(frame)
    assert(encoded.contains("\"data\":\"AAECAwQ=\""))
    PortalWireCodec.decode(encoded).foreach {
      case f: PortalWireCodec.BodyFrame => assert(Arrays.equals(f.data, payload))
      case other => fail(s"unexpected frame: $other")
    }
    assertEquals(Base64.getEncoder.encodeToString(payload), "AAECAwQ=")

end PortalWireCodecConformanceSpec
