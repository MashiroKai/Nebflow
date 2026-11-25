package nebflow.core.browser

/** Bounded memory: every accepted CDP frame is acknowledged, only the newest is retained. */
private[browser] final class BrowserFrameBuffer(acknowledge: Int => Unit):
  private var latest: Option[String] = None
  def receive(id: Int, data: String): Unit = synchronized {
    latest = Some(data)
    acknowledge(id)
  }
  def take(): Option[String] = synchronized {
    val result = latest
    latest = None
    result
  }
