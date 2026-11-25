package nebflow.core.browser

import java.util.concurrent.atomic.AtomicBoolean

/** Connection-local failure survives wakeup rejection; settlement is claimed once. */
private[nebflow] final class BrowserFailureSignal:
  private val failure = new AtomicBoolean(false)
  private val claimed = new AtomicBoolean(false)
  def fail(): Boolean = failure.compareAndSet(false, true)
  def failed: Boolean = failure.get()
  def claim(): Boolean = failed && claimed.compareAndSet(false, true)
