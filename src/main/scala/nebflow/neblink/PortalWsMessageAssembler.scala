package nebflow.neblink

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The JDK listener delivers partial messages. The relay browser pump sends
 * whole messages, so only complete UTF-8/binary payloads may cross that leg.
 * One bounded message per connection; no partial JSON ever reaches a browser.
 */
private[neblink] final class PortalWsMessageAssembler(
  maxBytes: Int = PortalTunnelBridge.MaxWsMessageBytes
):
  require(maxBytes > 0)
  private var kind: Option[String] = None
  private var chars = new java.lang.StringBuilder()
  private var bytes = new java.io.ByteArrayOutputStream()

  def reset(): Unit =
    kind = None
    // Replace rather than clear: release backing storage after a large message.
    chars = new java.lang.StringBuilder()
    bytes = new java.io.ByteArrayOutputStream()

  private def fail(reason: String): Left[String, Nothing] =
    reset()
    Left(reason)

  def text(data: CharSequence, last: Boolean): Either[String, Option[Array[Byte]]] =
    if kind.exists(_ != "text") then fail("mixed-message-fragments")
    else if chars.length().toLong + data.length() > maxBytes then fail("message-too-large")
    else
      kind = Some("text")
      chars.append(data)
      if !last then Right(None)
      else
        // Encode once, after joining: a surrogate pair may cross callbacks.
        val out = chars.toString.getBytes(UTF_8)
        reset()
        if out.length > maxBytes then Left("message-too-large") else Right(Some(out))

  def binary(data: ByteBuffer, last: Boolean): Either[String, Option[Array[Byte]]] =
    if kind.exists(_ != "binary") then fail("mixed-message-fragments")
    else if bytes.size().toLong + data.remaining() > maxBytes then fail("message-too-large")
    else
      kind = Some("binary")
      val fragment = new Array[Byte](data.remaining())
      data.get(fragment)
      bytes.write(fragment)
      if !last then Right(None)
      else
        val out = bytes.toByteArray
        reset()
        Right(Some(out))
end PortalWsMessageAssembler
