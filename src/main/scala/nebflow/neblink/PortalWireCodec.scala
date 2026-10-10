package nebflow.neblink

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*

import java.util.Base64

/**
 * Production codec for the portal wire contract v1 (phase 0.5 freeze,
 * 2026-10-10). Single source: `src/test/resources/neblink/portal-wire-contract-v1.md`
 * and its companion vector file `portal-wire-vectors-v1.json`; the
 * neblink-server repo holds byte-identical copies and runs its own Rust
 * conformance suite against the same vectors.
 *
 * Scope (phase 3a design section 3.1): the tunnel's outbound frames for the
 * NEW eleven contract frames are encoded here (canonical key order single
 * point — the same discipline as `NeblinkRelayTunnel.ackFrame`) and always
 * carry `proto: 1`. The pre-v1 legacy `relay_request` / `relay_response`
 * construction paths stay byte-for-byte unchanged (no `proto` key); rolling
 * those two frames onto `proto` belongs to the deployment batch, not here.
 *
 * Judgment parity: the test-side model (`PortalWireContractV1Spec`) and this
 * production codec run the SAME frozen 28-vector file with the same
 * assertions — model face and production face cross-checked on identical
 * verdicts. The decoding rules below therefore mirror the frozen contract
 * mechanically:
 *
 *  - envelope: `type` must be in the 13-frame inventory (`unknown_type`
 *    otherwise); `proto` is required (`missing_required_field`) and must not
 *    exceed the supported version (`proto_unsupported`);
 *  - unknown extra keys are ignored (forward compatibility);
 *  - `data` fields are RFC 4648 section 4 standard alphabet, padded base64;
 *  - UUID fields are lowercase hyphenated 8-4-4-4-12 with RFC 4122
 *    version/variant nibbles;
 *  - `ConsoleTicketResult` success and failure shapes are mutually exclusive
 *    (`field_conflict`).
 *
 * Runtime rejection semantics (contract section 1): the nine codes are a
 * conformance vocabulary, NOT wire error frames — the caller drops the frame
 * with a structured diagnostic and never partially processes it.
 */
object PortalWireCodec:

  val SupportedProto: Int = 1

  /** Contract "Frame inventory": all 13 frames, exactly. */
  val FrameInventory: Set[String] = Set(
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

  private val WsFrameKinds     = List("text", "binary")
  private val ReqOrigins       = List("portal")
  private val TicketMintErrors = List("rate_limited", "quiesced", "unbound")
  private val BindingKinds     = List("revoked", "stand-down", "quiesce", "quiesce-renew", "quiesce-lift")

  /** `headers` keeps insertion order so canonical encoding stays byte-stable. */
  type Headers = Vector[(String, String)]

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

  final case class HttpResponse(requestId: String, status: Int, headers: Headers) extends PortalFrame

  final case class BodyFrame(requestId: String, seq: Int, more: Boolean, data: Array[Byte])
      extends PortalFrame

  final case class WsOpen(requestId: String, path: String, subprotocol: Option[String]) extends PortalFrame

  final case class WsOpenResult(requestId: String, accepted: Boolean, status: Int) extends PortalFrame

  final case class WsFrame(requestId: String, kind: String, data: Array[Byte], fin: Boolean)
      extends PortalFrame

  final case class WsClose(requestId: String, code: Int, reason: String) extends PortalFrame

  final case class ConsoleTicketMint(requestId: String, mintId: String, accountSub: String, reqOrigin: String)
      extends PortalFrame

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
    data: Array[Byte]
  ) extends PortalFrame

  /**
   * Structured rejection, mirroring the conformance vocabulary of contract
   * section 1 (`unknown_type`, `unparseable_json`, `proto_unsupported`,
   * `missing_required_field`, `type_mismatch`, `invalid_enum`, `invalid_format`,
   * `invalid_base64`, `field_conflict`).
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
  def decode(text: String): Either[Rejection, PortalFrame] =
    parse(text).left.map(_ => Rejection("unparseable_json")).flatMap(decodeJson)

  /** Decode an already-parsed JSON message (the tunnel parses once, dispatches twice). */
  def decodeJson(json: Json): Either[Rejection, PortalFrame] =
    for
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
        yield HttpResponse(requestId, status, headers)

      case "body_frame" =>
        for
          requestId <- reqUuid(o, "requestId")
          seq <- reqInt(o, "seq")
          more <- reqBool(o, "more")
          data <- req(o, "data")(jsonBase64(_, "data"))
        yield BodyFrame(requestId, seq, more, data)

      case "ws_open" =>
        for
          requestId <- reqUuid(o, "requestId")
          path <- req(o, "path")(jsonAbsolutePath(_, "path"))
          subprotocol <- optString(o, "subprotocol")
        yield WsOpen(requestId, path, subprotocol)

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
          // Contract 2.11: the success shape (ticket + expiresInSec, no error)
          // and the failure shape (error only) are mutually exclusive. The
          // conflict is checked before the iff-completeness checks so a frame
          // carrying both sides reports field_conflict (the frozen negative
          // vector's verdict). BOTH members of the success pair are "present
          // if and only if the mint succeeded": expiresInSec without ticket
          // claims success while ticket's absence claims failure — the two
          // fields contradict each other, so that combo is also a
          // field_conflict (no frozen vector covers it; the model-side spec
          // decodes identically so both faces keep agreeing).
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
   * Canonical serialization: `type` + `proto` envelope first, then schema
   * fields in contract order; absent optionals are omitted (never emitted as
   * null); compact separators.
   */
  def encodeJson(frame: PortalFrame): Json =
    envelopeOf(frame)

  /** Canonical wire text (one relay WS message). */
  def encode(frame: PortalFrame): String = encodeJson(frame).noSpaces

  private def envelope(tpe: String, fields: (String, Option[Json])*): Json =
    val all =
      ("type", Some(Json.fromString(tpe)))
        :: ("proto", Some(Json.fromInt(SupportedProto)))
        :: fields.toList
    Json.fromJsonObject(JsonObject.fromIterable(all.collect { case (k, Some(v)) => (k, v) }))

  private def headersJson(headers: Headers): Json =
    Json.fromJsonObject(JsonObject.fromIterable(headers.map((k, v) => k -> Json.fromString(v))))

  private def b64Encode(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)

  private def envelopeOf(frame: PortalFrame): Json =
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
          "headers" -> Some(headersJson(fr.headers))
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
          "subprotocol" -> fr.subprotocol.map(Json.fromString)
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
      case None    => Right(None)
      case Some(j) => decode(j).map(Some(_))

  private def reqString(o: JsonObject, name: String): Either[Rejection, String] = req(o, name)(jsonString(_, name))
  private def reqInt(o: JsonObject, name: String): Either[Rejection, Int]       = req(o, name)(jsonInt(_, name))
  private def reqBool(o: JsonObject, name: String): Either[Rejection, Boolean]  = req(o, name)(jsonBool(_, name))
  private def reqUuid(o: JsonObject, name: String): Either[Rejection, String]   = req(o, name)(jsonUuid(_, name))
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
   * Contract section 1: base64, RFC 4648 section 4 standard alphabet, padded.
   * The canonical re-encode check pins both the alphabet (java.util.Base64
   * rejects stray characters) and the mandatory padding (an unpadded input
   * would not survive re-encoding unchanged).
   */
  private def jsonBase64(j: Json, name: String): Either[Rejection, Array[Byte]] =
    jsonString(j, name).flatMap { s =>
      try
        val bytes = Base64.getDecoder.decode(s)
        if Base64.getEncoder.encodeToString(bytes) == s then Right(bytes)
        else Left(Rejection("invalid_base64", field = Some(name)))
      catch case _: IllegalArgumentException => Left(Rejection("invalid_base64", field = Some(name)))
    }

  private def jsonHeaders(j: Json, name: String): Either[Rejection, Headers] =
    jsonObject(j, name).flatMap { ho =>
      ho.toIterable.foldLeft[Either[Rejection, Headers]](Right(Vector.empty)) { (acc, kv) =>
        acc.flatMap(vs => jsonString(kv._2, name).map(v => vs :+ (kv._1 -> v)))
      }
    }

end PortalWireCodec
