package nebflow.bridge.feishu

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import scala.collection.mutable

/**
 * Minimal protobuf codec for the Feishu pbbp2 WebSocket protocol.
 *
 * Frame schema (proto3-like):
 *   message Header { string key = 1; string value = 2; }
 *   message Frame {
 *     uint64 seq_id         = 1;
 *     uint64 log_id         = 2;
 *     int32  service        = 3;
 *     int32  method         = 4;
 *     repeated Header headers = 5;
 *     string payload_encoding = 6;
 *     string payload_type   = 7;
 *     bytes  payload        = 8;
 *     string log_id_new     = 9;
 *   }
 *
 * Method constants: 1 = CONTROL (ping/pong), 2 = DATA (event/card)
 * Payload types: "ping", "pong", "event", "card"
 */
object FeishuWire:

  // Must match the official Feishu SDK (com.lark.oapi.ws.enums.FrameType)
  val METHOD_CONTROL = 0
  val METHOD_DATA = 1

  // Header keys — same as official SDK (com.lark.oapi.ws.Constant)
  val HEADER_TYPE = "type"
  val HEADER_MESSAGE_ID = "message_id"
  val HEADER_SUM = "sum"
  val HEADER_SEQ = "seq"

  // Message types — sent in the "type" header
  val MSG_PING = "ping"
  val MSG_PONG = "pong"
  val MSG_EVENT = "event"
  val MSG_CARD = "card"

  case class Frame(
    seqId: Long = 0,
    logId: Long = 0,
    service: Int = 0,
    method: Int = 0,
    headers: List[(String, String)] = Nil,
    payloadEncoding: String = "",
    payloadType: String = "",
    payload: Array[Byte] = Array.empty,
    logIdNew: String = ""
  )

  // ===== Protobuf primitives =====

  private def encodeVarint(buf: ByteArrayOutputStream, value: Long): Unit =
    var v = value
    while (v & ~0x7fL) != 0 do
      buf.write((v & 0x7f).toInt | 0x80)
      v >>= 7
    buf.write(v.toInt)

  private def encodeTag(buf: ByteArrayOutputStream, field: Int, wireType: Int): Unit =
    encodeVarint(buf, (field.toLong << 3) | wireType.toLong)

  private def encodeBytesField(buf: ByteArrayOutputStream, field: Int, bytes: Array[Byte]): Unit =
    if bytes.nonEmpty then
      encodeTag(buf, field, 2)
      encodeVarint(buf, bytes.length.toLong)
      buf.write(bytes)

  private def encodeStringField(buf: ByteArrayOutputStream, field: Int, value: String): Unit =
    if value.nonEmpty then encodeBytesField(buf, field, value.getBytes("UTF-8"))

  private def encodeVarintField(buf: ByteArrayOutputStream, field: Int, value: Long): Unit =
    if value != 0 then
      encodeTag(buf, field, 0)
      encodeVarint(buf, value)

  /** Encode a Frame to protobuf bytes. */
  def encodeFrame(frame: Frame): Array[Byte] =
    val buf = new ByteArrayOutputStream(256)
    encodeVarintField(buf, 1, frame.seqId)
    encodeVarintField(buf, 2, frame.logId)
    encodeVarintField(buf, 3, frame.service.toLong)
    encodeVarintField(buf, 4, frame.method.toLong)
    // headers (field 5, repeated embedded message)
    for (k, v) <- frame.headers do
      val inner = new ByteArrayOutputStream(64)
      encodeStringField(inner, 1, k)
      encodeStringField(inner, 2, v)
      encodeTag(buf, 5, 2)
      encodeVarint(buf, inner.size.toLong)
      buf.write(inner.toByteArray)
    encodeStringField(buf, 6, frame.payloadEncoding)
    encodeStringField(buf, 7, frame.payloadType)
    encodeBytesField(buf, 8, frame.payload)
    encodeStringField(buf, 9, frame.logIdNew)
    buf.toByteArray

  /**
   * Build a client ping frame (same as official SDK's newPingFrame).
   *  Uses method=CONTROL(0) and header type="ping".
   */
  def pingFrame(serviceId: Int): Array[Byte] =
    encodeFrame(
      Frame(
        service = serviceId,
        method = METHOD_CONTROL,
        headers = List(HEADER_TYPE -> MSG_PING)
      )
    )

  /** Build a ping frame with default serviceId. */
  def pingFrame: Array[Byte] = pingFrame(0)

  /**
   * Build a data ACK frame (same as official SDK's response in handleDataFrame).
   *  Reuses original frame fields, replaces payload with JSON response.
   */
  def dataAckFrame(original: Frame): Array[Byte] =
    val respJson = """{"code":200}"""
    encodeFrame(
      Frame(
        seqId = original.seqId,
        logId = original.logId,
        service = original.service,
        method = METHOD_DATA,
        headers = original.headers :+ (HEADER_BIZ_RT -> "0"),
        payload = respJson.getBytes("UTF-8")
      )
    )

  private val HEADER_BIZ_RT = "biz_rt"

  // ===== Decoding =====

  private def readVarint(data: Array[Byte], offset: Int): (Long, Int) =
    var result = 0L
    var shift = 0
    var pos = offset
    var cont = true
    while cont && pos < data.length do
      val b = data(pos) & 0xff
      result |= ((b & 0x7fL) << shift)
      pos += 1
      if (b & 0x80) == 0 then cont = false else shift += 7
    (result, pos)

  /** Decode a Frame from protobuf bytes. */
  def decodeFrame(data: Array[Byte]): Frame =
    var pos = 0
    var seqId = 0L
    var logId = 0L
    var service = 0
    var method = 0
    val headers = mutable.ListBuffer.empty[(String, String)]
    var payloadEncoding = ""
    var payloadType = ""
    var payload = Array.emptyByteArray
    var logIdNew = ""

    while pos < data.length do
      val (tag, next) = readVarint(data, pos)
      pos = next
      val fieldNum = (tag >> 3).toInt
      val wireType = (tag & 0x7).toInt
      wireType match
        case 0 => // varint
          val (v, after) = readVarint(data, pos)
          pos = after
          fieldNum match
            case 1 => seqId = v
            case 2 => logId = v
            case 3 => service = v.toInt
            case 4 => method = v.toInt
            case _ =>
        case 2 => // length-delimited
          val (len, after) = readVarint(data, pos)
          pos = after
          val bytes = data.slice(pos, pos + len.toInt)
          pos += len.toInt
          fieldNum match
            case 5 => // Header (embedded message)
              var hpos = 0
              var hk = ""
              var hv = ""
              while hpos < bytes.length do
                val (htag, hnext) = readVarint(bytes, hpos)
                hpos = hnext
                val hf = (htag >> 3).toInt
                val hw = htag & 0x7
                hw match
                  case 2 =>
                    val (hl, ha) = readVarint(bytes, hpos)
                    hpos = ha
                    val hb = bytes.slice(hpos, hpos + hl.toInt)
                    hpos += hl.toInt
                    hf match
                      case 1 => hk = new String(hb, "UTF-8")
                      case 2 => hv = new String(hb, "UTF-8")
                      case _ =>
                  case _ =>
                    // skip unknown wire type in header
                    hpos = bytes.length // simplified: skip rest
              if hk.nonEmpty then headers += ((hk, hv))
            case 6 => payloadEncoding = new String(bytes, "UTF-8")
            case 7 => payloadType = new String(bytes, "UTF-8")
            case 8 => payload = bytes
            case 9 => logIdNew = new String(bytes, "UTF-8")
            case _ =>
          end match
        case _ =>
          // skip unknown wire type (shouldn't happen in practice)
          pos = data.length
      end match
    end while

    Frame(seqId, logId, service, method, headers.toList, payloadEncoding, payloadType, payload, logIdNew)
  end decodeFrame

end FeishuWire
