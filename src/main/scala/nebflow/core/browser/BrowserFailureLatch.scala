package nebflow.core.browser

/** Records failure even if it precedes resource registration. Callback must not block. */
private[nebflow] final class BrowserFailureLatch:
  private var failed = false
  private var registered: Option[() => Unit] = None
  private var claimed = false
  private def claim(): Option[() => Unit] =
    if failed && !claimed && registered.nonEmpty then { claimed = true; registered } else None
  def fail(): Unit =
    val action = synchronized { failed = true; claim() }
    action.foreach(_())
  def register(action: () => Unit): Unit =
    val ready = synchronized { registered = Some(action); claim() }
    ready.foreach(_())
  def finish(): Unit =
    val action = synchronized { failed = true; claim() }
    action.foreach(_())
