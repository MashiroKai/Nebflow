package nebflow.gateway

/** The session subject comes only from a validated ViaSession credential, never a client frame. */
private[gateway] object BrowserTakeoverAuth:
  def allowed(sessionSubject: Option[String], ownerSubject: Option[String]): Boolean =
    (sessionSubject, ownerSubject) match
      case (Some(session), Some(owner)) => session.nonEmpty && owner.nonEmpty && session == owner
      case _ => false
