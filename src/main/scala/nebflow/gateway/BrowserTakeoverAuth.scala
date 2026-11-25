package nebflow.gateway

/** The session subject comes only from a validated ViaSession credential, never a client frame. */
private[gateway] object BrowserTakeoverAuth:
  def validOrigin(origin: Option[String], expectedAuthority: String, allowed: Set[String]): Boolean =
    origin.exists { value =>
      try
        val uri = new java.net.URI(value)
        val scheme = Option(uri.getScheme).getOrElse("").toLowerCase
        val valid = (scheme == "http" || scheme == "https") && uri.getHost != null &&
          uri.getUserInfo == null && (uri.getRawPath == null || uri.getRawPath.isEmpty) &&
          uri.getRawQuery == null && uri.getRawFragment == null
        val canonical = HealthRoutes.canonicalOrigin(value)
        valid && canonical.exists(o => allowed.contains(o) || HealthRoutes.canonicalOrigin(s"$scheme://$expectedAuthority").contains(o))
      catch case _: Exception => false
    }

  def allowed(sessionSubject: Option[String], ownerSubject: Option[String]): Boolean =
    (sessionSubject, ownerSubject) match
      case (Some(session), Some(owner)) => session.nonEmpty && owner.nonEmpty && session == owner
      case _ => false
