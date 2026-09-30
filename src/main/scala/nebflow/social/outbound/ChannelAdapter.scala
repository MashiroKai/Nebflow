package nebflow.social.outbound

import cats.effect.IO
import io.circe.Json

/**
 * Channel adapter seam (design card section 2).
 *
 * Feishu is an IMPLEMENTATION of this trait, not the place the split criteria
 * live; WeChat reuses the same layer later. Dependency direction is one-way:
 * `outbound` (this package) knows nothing about any SDK, and the channel package
 * (`nebflow.social`) imports this one -- never the reverse.
 *
 * The trait is intentionally narrow: it names what a channel must be able to
 * answer, and every answer is either a reading or an explicit degradation.
 */
trait ChannelAdapter:

  /** "feishu" / "wechat". */
  def channelId: String

  /** Classify an event payload. Implementations call [[ContentClass.classify]]
    * with the extension table they can reach -- they do not re-implement it. */
  def classify(event: Json): ContentClass

  /** Render a payload that needs pixels. Returning the payload unchanged means
    * "I cannot render this"; it never means "drop it". */
  def render(p: OutboundPayload): IO[OutboundPayload]

  /** Execute the calls. One [[ChannelCallResult]] per call -- count conservation.
    * There is deliberately no arm that discards a call. */
  def dispatch(calls: List[ChannelCall]): IO[List[ChannelCallResult]]

  /** The single degradation entry. `reason` is never empty; the produced body
    * carries the `[未渲染]` / `[未知类型]` prefix so the fallback is visible on
    * the wire instead of being swallowed. */
  def degrade(p: OutboundPayload, reason: String): OutboundPayload.DegradedText

object ChannelAdapter:

  /** Shared degradation body builder so every channel words the fallback the
    * same way. `bytes` is what the original artifact weighed, when known. */
  def degradedBody(reason: String, originalPath: Option[os.Path], bytes: Long): String =
    originalPath match
      case Some(p) => OutboundText.unrendered(reason, p, bytes)
      case None    => s"[未渲染] $reason"
