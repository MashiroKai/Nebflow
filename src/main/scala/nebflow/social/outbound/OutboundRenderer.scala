package nebflow.social.outbound

import cats.effect.IO

/**
 * Rendering-pipeline seam (design card sections 3 and 5.2).
 *
 * The definition layer owns the SHAPE of this seam, not an implementation: the
 * production implementation is a headless Chrome subprocess in the channel
 * adapter layer, and the offline spec injects a stub. Keeping the trait here is
 * what lets the planner be judged with zero browser, zero network.
 *
 * Contract: an implementation that cannot render returns the payload UNCHANGED.
 * It never fabricates an artifact and never silently drops one -- the caller
 * sees that the payload came back un-rendered and degrades explicitly.
 */
trait OutboundRenderer:
  /** Render a payload that needs pixels (a card). Identity when unavailable. */
  def render(p: OutboundPayload): IO[OutboundPayload]

  /** Whether this renderer can produce real pixels in THIS environment. The
    * planner consults this before asking for a render, so an unavailable
    * renderer degrades immediately instead of paying a failed subprocess. */
  def available: Boolean

object OutboundRenderer:
  /** Explicitly unavailable renderer -- for hosts without a browser. Degrading
    * through this is visible (`[未渲染]` reaches the body); it is never a
    * silent substitution. */
  val unavailable: OutboundRenderer = new OutboundRenderer:
    def render(p: OutboundPayload): IO[OutboundPayload] = IO.pure(p)
    def available: Boolean = false
