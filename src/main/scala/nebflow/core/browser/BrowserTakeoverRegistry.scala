package nebflow.core.browser

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Process-local session ownership and leases. Lock order: journal, then one session. */
final class BrowserTakeoverRegistry:
  private final class State:
    var owner: Option[String] = None
    var lease: Option[String] = None
    var inputEnabled = false
  private val states = new ConcurrentHashMap[String, State]()
  private def state(session: String): State = states.computeIfAbsent(session, _ => new State)

  def withVerb[A](session: String)(body: => A): A =
    val s = state(session)
    s.synchronized {
      if s.lease.nonEmpty then throw new IllegalStateException("作者正在接管")
      body
    }

  def created(session: String, owner: String): Unit =
    val s = state(session)
    s.synchronized { if s.owner.isEmpty then s.owner = Some(owner) }

  def begin(session: String, owner: String, request: String): Either[String, Unit] =
    val s = state(session)
    s.synchronized {
      s.owner match
        case None => Left("该浏览器会话没有归属记录")
        case Some(existing) if existing != owner => Left("浏览器会话归属不匹配")
        case _ if s.lease.nonEmpty => Left("浏览器会话已有接管")
        case _ => s.lease = Some(request); s.inputEnabled = true; Right(())
    }

  def withInput[A](session: String, request: String)(body: => A): Either[String, A] =
    val s = state(session)
    s.synchronized {
      if !s.lease.contains(request) || !s.inputEnabled then Left("接管输入未授权")
      else Right(body)
    }

  def holds(session: String, request: String): Boolean =
    val s = state(session)
    s.synchronized { s.lease.contains(request) }

  def ownedBy(session: String, owner: String): Boolean =
    val s = state(session)
    s.synchronized { s.owner.contains(owner) }

  def isTakingOver(session: String): Boolean =
    val s = state(session)
    s.synchronized { s.lease.nonEmpty }

  def remove(session: String): Unit =
    val s = state(session)
    s.synchronized { s.owner = None; s.lease = None }

  def disableInput(request: String): Unit =
    states.values().asScala.foreach { s =>
      s.synchronized { if s.lease.contains(request) then s.inputEnabled = false }
    }

  def finish(request: String): Unit =
    states.values().asScala.foreach { s =>
      s.synchronized { if s.lease.contains(request) then { s.inputEnabled = false; s.lease = None } }
    }
