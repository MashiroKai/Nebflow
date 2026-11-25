package nebflow.core.browser

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** Process-local one-shot capabilities; never serialize or log them. */
private[nebflow] final class BrowserTakeoverTickets(now: () => Long, ttlMillis: Long):
  final case class Binding(request: String, agent: String, session: String, expires: Long)
  private val random = new SecureRandom
  private val entries = scala.collection.mutable.ArrayBuffer.empty[(Array[Byte], Binding)]

  def issue(request: String, agent: String, session: String): String = synchronized {
    val raw = new Array[Byte](32)
    random.nextBytes(raw)
    entries.filterInPlace(_._2.expires > now())
    entries += ((raw, Binding(request, agent, session, now() + ttlMillis)))
    Base64.getUrlEncoder.withoutPadding().encodeToString(raw)
  }

  def inspect(token: String): Option[Binding] = synchronized {
    val raw = try Base64.getUrlDecoder.decode(token) catch case _: IllegalArgumentException => Array.emptyByteArray
    entries.find { case (secret, binding) => MessageDigest.isEqual(secret, raw) && binding.expires > now() }.map(_._2)
  }

  def consume(token: String, allowed: Binding => Boolean): Option[Binding] = synchronized {
    val raw = try Base64.getUrlDecoder.decode(token) catch case _: IllegalArgumentException => Array.emptyByteArray
    val index = entries.indexWhere { case (secret, _) => MessageDigest.isEqual(secret, raw) }
    if index < 0 then None
    else
      val binding = entries(index)._2
      if binding.expires <= now() || !allowed(binding) then None
      else
        entries.remove(index)
        Some(binding)
  }
