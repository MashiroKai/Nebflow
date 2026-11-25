package nebflow.core.browser

/** In-memory ownership and exclusive takeover leases. No browser content is stored here. */
final class BrowserTakeoverRegistry:
  private val owners = scala.collection.mutable.Map.empty[String, String]
  private val leases = scala.collection.mutable.Map.empty[String, String]

  def withVerb[A](session: String)(body: => A): A = synchronized {
    if leases.contains(session) then throw new IllegalStateException("作者正在接管")
    body
  }

  def created(session: String, owner: String): Unit = synchronized {
    owners.getOrElseUpdate(session, owner)
    ()
  }

  def begin(session: String, owner: String, request: String): Either[String, Unit] = synchronized {
    owners.get(session) match
      case None => Left("该浏览器会话没有归属记录")
      case Some(existing) if existing != owner => Left("浏览器会话归属不匹配")
      case _ if leases.contains(session) || leases.values.exists(_ == request) => Left("浏览器会话已有接管")
      case _ =>
        leases.put(session, request)
        Right(())
  }

  def isTakingOver(session: String): Boolean = synchronized { leases.contains(session) }

  def remove(session: String): Unit = synchronized {
    owners.remove(session)
    leases.remove(session)
    ()
  }

  def finish(request: String): Unit = synchronized {
    leases.filterInPlace((_, value) => value != request)
    ()
  }
