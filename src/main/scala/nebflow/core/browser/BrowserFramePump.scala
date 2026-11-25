package nebflow.core.browser

/** Single outstanding drain; bounded by the CDP screencast window. */
private[nebflow] final class BrowserFramePump:
  private val ids = scala.collection.mutable.ArrayDeque.empty[Int]
  private var latest: Option[String] = None
  private var draining = false
  def offer(id: Int, data: String): Boolean = synchronized {
    if ids.size >= 2 then throw new IllegalStateException("CDP frame window exceeded")
    ids.append(id)
    latest = Some(data)
    if draining then false else { draining = true; true }
  }
  def nextAck(): Option[Int] = synchronized {
    if ids.isEmpty then { draining = false; None } else Some(ids.removeHead())
  }
  def takeFrame(): Option[String] = synchronized { val result = latest; latest = None; result }
