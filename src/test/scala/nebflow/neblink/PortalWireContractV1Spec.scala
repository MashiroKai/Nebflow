package nebflow.neblink

import io.circe.{HCursor, Json, JsonObject}
import io.circe.parser.parse
import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Arrays, Base64}

/**
 * Portal wire contract v1.1 conformance spec (phase 3c re-freeze, main-repo half).
 *
 * Source of truth: `src/test/resources/neblink/portal-wire-contract-v1.1.md` (13-frame wire
 * surface plus the v1.1 optional fields) and its companion vector file
 * `src/test/resources/neblink/portal-wire-vectors-v1.1.json`. The neblink-server repo holds
 * byte-identical copies of both and runs its own conformance suite against the same vectors;
 * both sides must agree on every verdict.
 *
 * This spec models the frozen schema on the test side with circe, kept structurally identical
 * to the production codec (`PortalWireCodec`) so the two faces cannot diverge. For every
 * vector in the frozen file:
 *
 *  - positive: the `json` field-level object decodes cleanly into the frame model; the
 *    canonical serialization produced by the model (schema key order, compact separators,
 *    RFC 4648 section 4 padded base64) is byte-identical to the frozen `wire` string; and the
 *    frozen `wire` string round-trips to the same decoded frame.
 *  - negative: the input is rejected, and the mechanical faces of the rejection match the
 *    vector's `expect` block exactly: `reject` code, offending `field` / `fields`, the
 *    `expected` JSON type on type mismatches, and the `allowed` enum values on enum
 *    mismatches. The free-text `reason` key is narrative commentary and is deliberately not
 *    asserted (contract section 6 names only the rejection code and the offending field).
 *
 * Envelope behaviors from contract section 1: `proto` above the supported version rejects
 * with `proto_unsupported`, and unknown extra keys are ignored (forward compatibility) — the
 * exact mechanism behind the v1.1 zero-breakage discipline, pinned by the vector-preservation
 * test below.
 */
class PortalWireContractV1Spec extends FunSuite:

  // ────────────────────────── contract constants ──────────────────────────

  private val SupportedProto = 1

  /** Contract "Frame inventory": all 13 frames, exactly. */
  private val FrameInventory: Set[String] = Set(
    "relay_request",
    "relay_response",
    "http_request",
    "http_response",
    "body_frame",
    "ws_open",
    "ws_open_result",
    "ws_frame",
    "ws_close",
    "ConsoleTicketMint",
    "ConsoleTicketResult",
    "BindingControl",
    "MigrationChunk"
  )

  private val WsFrameKinds      = List("text", "binary")
  private val ReqOrigins        = List("portal")
  private val TicketMintErrors  = List("rate_limited", "quiesced", "unbound")
  private val BindingKinds      = List("revoked", "stand-down", "quiesce", "quiesce-renew", "quiesce-lift")

  // ────────────────────────── frame model ──────────────────────────

  /** `headers` keeps insertion order so canonical encoding stays byte-stable. */
  private type Headers = Vector[(String, String)]

  sealed trait PortalFrame
  final case class RelayRequest(
    requestId: String,
    action: String,
    params: JsonObject,
    projectRoot: Option[String]
  ) extends PortalFrame
  final case class RelayResponse(requestId: String, output: String, error: String) extends PortalFrame
  final case class HttpRequest(requestId: String, method: String, path: String, headers: Headers)
      extends PortalFrame
  final case class HttpResponse(requestId: String, status: Int, headers: Headers, setCookie: Vector[String])
      extends PortalFrame
  final case class BodyFrame(requestId: String, seq: Int, more: Boolean, data: Vector[Byte])
      extends PortalFrame
  final case class WsOpen(
    requestId: String,
    path: String,
    subprotocol: Option[String],
    headers: Option[Headers]
  ) extends PortalFrame
  final case class WsOpenResult(requestId: String, accepted: Boolean, status: Int) extends PortalFrame
  final case class WsFrame(requestId: String, kind: String, data: Vector[Byte], fin: Boolean)
      extends PortalFrame
  final case class WsClose(requestId: String, code: Int, reason: String) extends PortalFrame
  final case class ConsoleTicketMint(
    requestId: String,
    mintId: String,
    accountSub: String,
    reqOrigin: String
  ) extends PortalFrame
  final case class ConsoleTicketResult(
    requestId: String,
    mintId: String,
    ticket: Option[String],
    expiresInSec: Option[Int],
    error: Option[String]
  ) extends PortalFrame
  final case class BindingControl(kind: String, migrationId: Option[String], ttlSec: Option[Int])
      extends PortalFrame
  final case class MigrationChunk(
    migrationId: String,
    manifestPath: String,
    chunkIndex: Int,
    sha256: String,
    data: Vector[Byte]
  ) extends PortalFrame

  /**
   * Structured rejection, mirroring the conformance vocabulary of contract section 1
   * (`unknown_type`, `unparseable_json`, `proto_unsupported`, `missing_required_field`,
   * `type_mismatch`, `invalid_enum`, `invalid_format`, `invalid_base64`, `field_conflict`).
   */
  final case class Rejection(
    code: String,
    field: Option[String] = None,
    fields: Option[List[String]] = None,
    expected: Option[String] = None,
    allowed: Option[List[String]] = None
  )

  // ────────────────────────── decoding ──────────────────────────

  /** Parses one relay WS message text into a frame, or rejects with a contract code. */
  private def decodeFrame(text: String): Either[Rejection, PortalFrame] =
    for
      json <- parse(text).left.map(_ => Rejection("unparseable_json"))
      obj <- json.asObject.toRight(Rejection("unparseable_json"))
      tpe <- reqString(obj, "type")
      _ <- Either.cond(FrameInventory.contains(tpe), (), Rejection("unknown_type"))
      _ <- checkProto(obj)
      frame <- decodeBody(tpe, obj)
    yield frame

  /** Receiver duty, contract section 1: reject any proto above the supported version. */
  private def checkProto(obj: JsonObject): Either[Rejection, Unit] =
    req(obj, "proto")(jsonInt(_, "proto")).flatMap { p =>
      if p > SupportedProto then Left(Rejection("proto_unsupported"))
      else Right(())
    }

  private def decodeBody(tpe: String, o: JsonObject): Either[Rejection, PortalFrame] =
    tpe match
      case "relay_request" =>
        for
          requestId <- reqUuid(o, "requestId")
          action <- reqString(o, "action")
          params <- req(o, "params")(jsonObject(_, "params"))
          projectRoot <- optString(o, "projectRoot")
        yield RelayRequest(requestId, action, params, projectRoot)

      case "relay_response" =>
        for
          requestId <- reqUuid(o, "requestId")
          output <- reqString(o, "output")
          error <- reqString(o, "error")
        yield RelayResponse(requestId, output, error)

      case "http_request" =>
        for
          requestId <- reqUuid(o, "requestId")
          method <- req(o, "method")(jsonMethod(_, "method"))
          path <- reqString(o, "path")
          headers <- req(o, "headers")(jsonHeaders(_, "headers"))
        yield HttpRequest(requestId, method, path, headers)

      case "http_response" =>
        for
          requestId <- reqUuid(o, "requestId")
          status <- reqInt(o, "status")
          headers <- req(o, "headers")(jsonHeaders(_, "headers"))
          setCookie <- opt(o, "setCookie")(jsonStringArray(_, "setCookie"))
          // Contract 2.4 (v1.1 D8): set-cookie rides the setCookie array only;
          // both carriers at once is a field_conflict. A lone legacy
          // `set-cookie` entry in headers stays acceptable (loss-free single
          // value) — kept identical to the production codec.
          _ <- Either.cond(
            !(headers.exists(_._1 == "set-cookie") && setCookie.nonEmpty),
            (),
            Rejection("field_conflict", fields = Some(List("headers", "setCookie")))
          )
        yield HttpResponse(requestId, status, headers, setCookie.getOrElse(Vector.empty))

      case "body_frame" =>
        for
          requestId <- reqUuid(o, "requestId")
          seq <- reqInt(o, "seq")
          // Contract 2.5: a negative seq is rejected PER FRAME (invalid_format);
          // kept identical to the production codec.
          _ <- Either.cond(seq >= 0, (), Rejection("invalid_format", field = Some("seq")))
          more <- reqBool(o, "more")
          data <- req(o, "data")(jsonBase64(_, "data"))
        yield BodyFrame(requestId, seq, more, data)

      case "ws_open" =>
        for
          requestId <- reqUuid(o, "requestId")
          path <- req(o, "path")(jsonAbsolutePath(_, "path"))
          subprotocol <- optString(o, "subprotocol")
          headers <- opt(o, "headers")(jsonHeaders(_, "headers"))
        yield WsOpen(requestId, path, subprotocol, headers)

      case "ws_open_result" =>
        for
          requestId <- reqUuid(o, "requestId")
          accepted <- reqBool(o, "accepted")
          status <- reqInt(o, "status")
        yield WsOpenResult(requestId, accepted, status)

      case "ws_frame" =>
        for
          requestId <- reqUuid(o, "requestId")
          kind <- reqEnum(o, "kind", WsFrameKinds)
          data <- req(o, "data")(jsonBase64(_, "data"))
          fin <- reqBool(o, "fin")
        yield WsFrame(requestId, kind, data, fin)

      case "ws_close" =>
        for
          requestId <- reqUuid(o, "requestId")
          code <- reqInt(o, "code")
          reason <- reqString(o, "reason")
        yield WsClose(requestId, code, reason)

      case "ConsoleTicketMint" =>
        for
          requestId <- reqUuid(o, "requestId")
          mintId <- reqUuid(o, "mintId")
          accountSub <- reqString(o, "accountSub")
          reqOrigin <- reqEnum(o, "reqOrigin", ReqOrigins)
        yield ConsoleTicketMint(requestId, mintId, accountSub, reqOrigin)

      case "ConsoleTicketResult" =>
        for
          requestId <- reqUuid(o, "requestId")
          mintId <- reqUuid(o, "mintId")
          ticket <- optString(o, "ticket")
          expiresInSec <- optInt(o, "expiresInSec")
          error <- optEnum(o, "error", TicketMintErrors)
          // Contract 2.11: the success shape (ticket + expiresInSec, no error) and the
          // failure shape (error only) are mutually exclusive. The conflict is checked
          // before the iff-completeness checks so a frame carrying both sides reports
          // field_conflict (the frozen negative vector's verdict). expiresInSec without
          // ticket contradicts the "present if and only if the mint succeeded" iff on
          // both fields, so that combo is also a field_conflict — kept identical to the
          // production codec (PortalWireCodec) so both faces agree on every input.
          _ <- (ticket.isDefined, expiresInSec.isDefined, error.isDefined) match
            case (true, _, true) | (_, true, true) =>
              Left(Rejection("field_conflict", fields = Some(List("ticket", "error"))))
            case (false, true, false) =>
              Left(Rejection("field_conflict", fields = Some(List("ticket", "expiresInSec"))))
            case (true, false, false) =>
              Left(Rejection("missing_required_field", field = Some("expiresInSec")))
            case (false, false, false) =>
              Left(Rejection("missing_required_field", field = Some("ticket")))
            case _ => Right(())
        yield ConsoleTicketResult(requestId, mintId, ticket, expiresInSec, error)

      case "BindingControl" =>
        for
          kind <- reqEnum(o, "kind", BindingKinds)
          migrationId <- optUuid(o, "migrationId")
          ttlSec <- optInt(o, "ttlSec")
        yield BindingControl(kind, migrationId, ttlSec)

      case "MigrationChunk" =>
        for
          migrationId <- reqUuid(o, "migrationId")
          manifestPath <- reqString(o, "manifestPath")
          chunkIndex <- reqInt(o, "chunkIndex")
          sha256 <- req(o, "sha256")(jsonSha256(_, "sha256"))
          data <- req(o, "data")(jsonBase64(_, "data"))
        yield MigrationChunk(migrationId, manifestPath, chunkIndex, sha256, data)

      case other =>
        Left(Rejection("unknown_type"))

  // ────────────────────────── encoding ──────────────────────────

  /**
   * Canonical serialization face: `type` + `proto` envelope first, then schema fields in
   * contract order; absent optionals are omitted (never emitted as null); compact separators.
   */
  private def envelope(tpe: String, fields: (String, Option[Json])*): Json =
    val all =
      ("type", Some(Json.fromString(tpe)))
        :: ("proto", Some(Json.fromInt(SupportedProto)))
        :: fields.toList
    Json.fromJsonObject(JsonObject.fromIterable(all.collect { case (k, Some(v)) => (k, v) }))

  private def headersJson(headers: Headers): Json =
    Json.fromJsonObject(JsonObject.fromIterable(headers.map((k, v) => k -> Json.fromString(v))))

  private def b64Encode(bytes: Vector[Byte]): String =
    val arr = new Array[Byte](bytes.length)
    bytes.copyToArray(arr)
    Base64.getEncoder.encodeToString(arr)

  private def encode(frame: PortalFrame): Json =
    frame match
      case fr: RelayRequest =>
        envelope(
          "relay_request",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "action" -> Some(Json.fromString(fr.action)),
          "params" -> Some(Json.fromJsonObject(fr.params)),
          "projectRoot" -> fr.projectRoot.map(Json.fromString)
        )
      case fr: RelayResponse =>
        envelope(
          "relay_response",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "output" -> Some(Json.fromString(fr.output)),
          "error" -> Some(Json.fromString(fr.error))
        )
      case fr: HttpRequest =>
        envelope(
          "http_request",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "method" -> Some(Json.fromString(fr.method)),
          "path" -> Some(Json.fromString(fr.path)),
          "headers" -> Some(headersJson(fr.headers))
        )
      case fr: HttpResponse =>
        envelope(
          "http_response",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "status" -> Some(Json.fromInt(fr.status)),
          "headers" -> Some(headersJson(fr.headers)),
          // v1.1 D8: optional, absent omitted (an empty vector is never emitted).
          "setCookie" -> Option.when(fr.setCookie.nonEmpty)(
            Json.fromValues(fr.setCookie.map(Json.fromString))
          )
        )
      case fr: BodyFrame =>
        envelope(
          "body_frame",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "seq" -> Some(Json.fromInt(fr.seq)),
          "more" -> Some(Json.fromBoolean(fr.more)),
          "data" -> Some(Json.fromString(b64Encode(fr.data)))
        )
      case fr: WsOpen =>
        envelope(
          "ws_open",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "path" -> Some(Json.fromString(fr.path)),
          "subprotocol" -> fr.subprotocol.map(Json.fromString),
          // v1.1 D9: schema order places `headers` after `subprotocol`; absent
          // is encoded by omission (old frames keep their exact bytes).
          "headers" -> fr.headers.map(headersJson)
        )
      case fr: WsOpenResult =>
        envelope(
          "ws_open_result",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "accepted" -> Some(Json.fromBoolean(fr.accepted)),
          "status" -> Some(Json.fromInt(fr.status))
        )
      case fr: WsFrame =>
        envelope(
          "ws_frame",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "kind" -> Some(Json.fromString(fr.kind)),
          "data" -> Some(Json.fromString(b64Encode(fr.data))),
          "fin" -> Some(Json.fromBoolean(fr.fin))
        )
      case fr: WsClose =>
        envelope(
          "ws_close",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "code" -> Some(Json.fromInt(fr.code)),
          "reason" -> Some(Json.fromString(fr.reason))
        )
      case fr: ConsoleTicketMint =>
        envelope(
          "ConsoleTicketMint",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "mintId" -> Some(Json.fromString(fr.mintId)),
          "accountSub" -> Some(Json.fromString(fr.accountSub)),
          "reqOrigin" -> Some(Json.fromString(fr.reqOrigin))
        )
      case fr: ConsoleTicketResult =>
        envelope(
          "ConsoleTicketResult",
          "requestId" -> Some(Json.fromString(fr.requestId)),
          "mintId" -> Some(Json.fromString(fr.mintId)),
          "ticket" -> fr.ticket.map(Json.fromString),
          "expiresInSec" -> fr.expiresInSec.map(Json.fromInt),
          "error" -> fr.error.map(Json.fromString)
        )
      case fr: BindingControl =>
        envelope(
          "BindingControl",
          "kind" -> Some(Json.fromString(fr.kind)),
          "migrationId" -> fr.migrationId.map(Json.fromString),
          "ttlSec" -> fr.ttlSec.map(Json.fromInt)
        )
      case fr: MigrationChunk =>
        envelope(
          "MigrationChunk",
          "migrationId" -> Some(Json.fromString(fr.migrationId)),
          "manifestPath" -> Some(Json.fromString(fr.manifestPath)),
          "chunkIndex" -> Some(Json.fromInt(fr.chunkIndex)),
          "sha256" -> Some(Json.fromString(fr.sha256)),
          "data" -> Some(Json.fromString(b64Encode(fr.data)))
        )

  // ────────────────────────── field decoders ──────────────────────────

  private def req[A](o: JsonObject, name: String)(decode: Json => Either[Rejection, A]): Either[Rejection, A] =
    o(name).toRight(Rejection("missing_required_field", field = Some(name))).flatMap(decode)

  private def opt[A](o: JsonObject, name: String)(decode: Json => Either[Rejection, A]): Either[Rejection, Option[A]] =
    o(name) match
      case None      => Right(None)
      case Some(j)   => decode(j).map(Some(_))

  private def reqString(o: JsonObject, name: String): Either[Rejection, String] = req(o, name)(jsonString(_, name))
  private def reqInt(o: JsonObject, name: String): Either[Rejection, Int] = req(o, name)(jsonInt(_, name))
  private def reqBool(o: JsonObject, name: String): Either[Rejection, Boolean] = req(o, name)(jsonBool(_, name))
  private def reqUuid(o: JsonObject, name: String): Either[Rejection, String] = req(o, name)(jsonUuid(_, name))
  private def reqEnum(o: JsonObject, name: String, allowed: List[String]): Either[Rejection, String] =
    req(o, name)(jsonEnum(_, name, allowed))

  private def optString(o: JsonObject, name: String): Either[Rejection, Option[String]] =
    opt(o, name)(jsonString(_, name))
  private def optInt(o: JsonObject, name: String): Either[Rejection, Option[Int]] =
    opt(o, name)(jsonInt(_, name))
  private def optUuid(o: JsonObject, name: String): Either[Rejection, Option[String]] =
    opt(o, name)(jsonUuid(_, name))
  private def optEnum(o: JsonObject, name: String, allowed: List[String]): Either[Rejection, Option[String]] =
    opt(o, name)(jsonEnum(_, name, allowed))

  private def jsonString(j: Json, name: String): Either[Rejection, String] =
    j.asString.toRight(Rejection("type_mismatch", field = Some(name), expected = Some("string")))

  private def jsonInt(j: Json, name: String): Either[Rejection, Int] =
    j.asNumber.flatMap(_.toInt).toRight(Rejection("type_mismatch", field = Some(name), expected = Some("int")))

  private def jsonBool(j: Json, name: String): Either[Rejection, Boolean] =
    j.asBoolean.toRight(Rejection("type_mismatch", field = Some(name), expected = Some("bool")))

  private def jsonObject(j: Json, name: String): Either[Rejection, JsonObject] =
    j.asObject.toRight(Rejection("type_mismatch", field = Some(name), expected = Some("object")))

  /** Contract section 1: lowercase hyphenated 8-4-4-4-12 with RFC 4122 version/variant nibbles. */
  private def jsonUuid(j: Json, name: String): Either[Rejection, String] =
    jsonString(j, name).flatMap { s =>
      val wellFormed =
        s.length == 36 && s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
      Either.cond(wellFormed, s, Rejection("invalid_format", field = Some(name)))
    }

  private def jsonEnum(j: Json, name: String, allowed: List[String]): Either[Rejection, String] =
    jsonString(j, name).flatMap { s =>
      Either.cond(
        allowed.contains(s),
        s,
        Rejection("invalid_enum", field = Some(name), allowed = Some(allowed))
      )
    }

  /** Contract 2.3: `method` is the uppercase HTTP method. */
  private def jsonMethod(j: Json, name: String): Either[Rejection, String] =
    jsonString(j, name).flatMap { m =>
      val upper = m.nonEmpty && m == m.toUpperCase(java.util.Locale.ROOT)
      Either.cond(upper, m, Rejection("invalid_format", field = Some(name)))
    }

  /** Contract 2.6: `path` is absolute (leading '/'). */
  private def jsonAbsolutePath(j: Json, name: String): Either[Rejection, String] =
    jsonString(j, name).flatMap(p =>
      Either.cond(p.startsWith("/"), p, Rejection("invalid_format", field = Some(name)))
    )

  /** Contract 2.13: `sha256` is 64 lowercase hex chars. */
  private def jsonSha256(j: Json, name: String): Either[Rejection, String] =
    jsonString(j, name).flatMap(s =>
      Either.cond(s.matches("[0-9a-f]{64}"), s, Rejection("invalid_format", field = Some(name)))
    )

  /**
   * Contract section 1: base64, RFC 4648 section 4 standard alphabet, padded. The canonical
   * re-encode check pins both the alphabet (java.util.Base64 rejects stray characters) and
   * the mandatory padding (an unpadded input would not survive re-encoding unchanged).
   */
  private def jsonBase64(j: Json, name: String): Either[Rejection, Vector[Byte]] =
    jsonString(j, name).flatMap { s =>
      try
        val bytes = Base64.getDecoder.decode(s)
        if Base64.getEncoder.encodeToString(bytes) == s then Right(bytes.toVector)
        else Left(Rejection("invalid_base64", field = Some(name)))
      catch case _: IllegalArgumentException => Left(Rejection("invalid_base64", field = Some(name)))
    }

  private def jsonHeaders(j: Json, name: String): Either[Rejection, Headers] =
    jsonObject(j, name).flatMap { ho =>
      ho.toIterable.foldLeft[Either[Rejection, Headers]](Right(Vector.empty)) { (acc, kv) =>
        acc.flatMap(vs => jsonString(kv._2, name).map(v => vs :+ (kv._1 -> v)))
      }
    }

  /** Contract 2.4 (v1.1 D8): `setCookie` is an array of strings, one per occurrence. */
  private def jsonStringArray(j: Json, name: String): Either[Rejection, Vector[String]] =
    j.asArray
      .toRight(Rejection("type_mismatch", field = Some(name), expected = Some("array")))
      .flatMap(_.foldLeft[Either[Rejection, Vector[String]]](Right(Vector.empty)) { (acc, el) =>
        acc.flatMap(vs => jsonString(el, name).map(v => vs :+ v))
      })

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

  private def parseResource(res: String): Json =
    val in = Option(getClass.getClassLoader.getResourceAsStream(res)).getOrElse(
      fail(s"classpath resource '$res' not found — frozen vector file missing from test classpath")
    )
    try
      parse(new String(in.readAllBytes(), UTF_8))
        .fold(e => fail(s"$res is not valid JSON: ${e.getMessage}"), identity)
    finally in.close()

  private lazy val vectorsDoc: Json = parseResource("neblink/portal-wire-vectors-v1.1.json")

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

  /** The mechanical faces of a negative vector's `expect` block (the free-text `reason` is not one). */
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

  test("vector file pins the frozen contract identity (contract file + version v1.1)"):
    val c = vectorsDoc.hcursor
    assertEquals(
      cursorString(c, "contract"),
      "portal-wire-contract-v1.1.md",
      "the vector file must be the companion of the frozen contract copy"
    )
    assertEquals(
      cursorString(c, "version"),
      "v1.1",
      "any contract change must land as a re-freeze (v1.x) in both repos, not a silent vector edit"
    )

  test("the 28 frozen v1 vectors are preserved byte-for-byte at the head of the v1.1 file"):
    // Contract section 6: the v1 set survives the re-freeze unchanged; v1.1
    // only APPENDS vectors for the new optional fields and pinned
    // clarifications (40 total). Comparing canonical per-entry serializations
    // of both frozen files pins that mechanically.
    val v1Doc = parseResource("neblink/portal-wire-vectors-v1.json")
    val v1 = v1Doc.hcursor.downField("vectors").as[List[Json]].fold(e => fail(s"v1 vectors unreadable: $e"), identity)
    assertEquals(v1.length, 28, "the frozen v1 set is 28 vectors")
    val v11 = vectorsDoc.hcursor.downField("vectors").as[List[Json]].fold(e => fail(s"v1.1 vectors unreadable: $e"), identity)
    assertEquals(
      v11.take(v1.length).map(_.noSpaces),
      v1.map(_.noSpaces),
      "the v1.1 re-freeze must preserve the v1 vectors byte-for-byte, in order"
    )

  test("every frame in the inventory has at least one positive and one negative vector"):
    val pos = entries.filter(_.polarity == "positive").map(_.frame).toSet
    val neg = entries.filter(_.polarity == "negative").map(_.frame).toSet
    assertEquals(
      pos,
      FrameInventory,
      s"positive-vector coverage must be exactly the 13-frame inventory; unexpected=${{
        (pos -- FrameInventory).toList.sorted
      }} missing=${{ (FrameInventory -- pos).toList.sorted }}"
    )
    assert(
      FrameInventory.forall(neg.contains),
      s"frames without a negative vector: ${(FrameInventory -- neg).toList.sorted.mkString(", ")} " +
        "(contract section 6: every frame carries at least one positive and one negative vector)"
    )

  test("vector ids are unique (each vector registers exactly one munit test)"):
    val ids = entries.map(_.id)
    assertEquals(ids.distinct.size, ids.size, s"duplicate vector ids: $ids")

  test("ConsoleTicketResult: expiresInSec without ticket is a field_conflict (iff on BOTH success fields)"):
    // Contract 2.11: ticket and expiresInSec are each "present if and only if
    // the mint succeeded" — one present and the other absent contradicts
    // itself. No frozen vector covers the combo; this model-side pin keeps the
    // model and the production codec (PortalWireCodec) provably aligned.
    val frame =
      """{"type":"ConsoleTicketResult","proto":1,"requestId":"0a1b2c3d-4e5f-4678-9abc-def012345678",""" +
        """"mintId":"1f2e3d4c-5b6a-4978-8877-6a5b4c3d2e1f","expiresInSec":30}"""
    assertEquals(
      decodeFrame(frame).swap.toOption.map(r => (r.code, r.fields)),
      Some(("field_conflict", Some(List("ticket", "expiresInSec"))))
    )

  entries.foreach { v =>
    v.wire match
      case Some(wire) =>
        test(s"${v.id} [positive]: canonical encode is byte-identical to the frozen wire"):
          val json = v.json.getOrElse(fail(s"positive vector '${v.id}' lacks a json block"))
          decodeFrame(json.noSpaces).fold(
            rej =>
              fail(
                s"positive vector '${v.id}' must decode cleanly, got rejection '${rej.code}' " +
                  s"(field=${rej.field.getOrElse("none")}); input=${json.noSpaces}"
              ),
            frame =>
              assertByteIdentical(
                encode(frame).noSpaces,
                wire,
                s"canonical serialization of '${v.id}' drifted from the frozen wire bytes " +
                  "(key order / separators / base64 form must match the contract's canonical encoding)"
              )
              assertEquals(
                decodeFrame(wire),
                Right(frame),
                s"frozen wire of '${v.id}' must round-trip to the same decoded frame"
              )
          )

      case None =>
        test(s"${v.id} [negative]: decode is rejected exactly as the expect block demands"):
          val input = v.raw
            .orElse(v.json.map(_.noSpaces))
            .getOrElse(fail(s"negative vector '${v.id}' has neither a json nor a raw input"))
          val exp = expectOf(v)
          decodeFrame(input) match
            case Right(frame) =>
              fail(s"expected rejection '${exp.reject}' but the frame decoded cleanly: $frame")
            case Left(rej) =>
              assertEquals(rej.code, exp.reject, s"rejection code mismatch for '${v.id}'")
              exp.field.foreach(want =>
                assertEquals(rej.field, Some(want), s"offending field mismatch for '${v.id}'")
              )
              exp.fields.foreach(want =>
                assertEquals(
                  rej.fields.map(_.toSet),
                  Some(want.toSet),
                  s"conflicting fields mismatch for '${v.id}'"
                )
              )
              exp.expected.foreach(want =>
                assertEquals(rej.expected, Some(want), s"expected JSON type mismatch for '${v.id}'")
              )
              exp.allowed.foreach(want =>
                assertEquals(
                  rej.allowed.map(_.toSet),
                  Some(want.toSet),
                  s"allowed enum values mismatch for '${v.id}'"
                )
              )
  }

end PortalWireContractV1Spec
