package nebflow.gateway

/**
 * Trusted ownership primitives for owner-scoped gateway operations.
 *
 * A principal is derived only from a server-validated session record. Bearer
 * credentials (including PATs and file tokens) deliberately have no owner.
 * Resource callers must supply the server-side target record; client-provided
 * identifiers are selectors, never proof of ownership.
 */
object GatewayOwnership:

  sealed trait Principal extends Product with Serializable
  final case class Account(sub: String) extends Principal
  case object Unknown extends Principal

  private val UnknownLabels = Set("", "unknown", "null")

  def principal(credential: Credential): Principal = credential match
    case Credential.ViaSession(record) if isKnown(record.accountSub) => Account(record.accountSub)
    case Credential.ViaSession(_) => Unknown
    case Credential.ViaBearer(_) => Unknown

  /** Fail-closed equality check for a target whose owner came from trusted storage. */
  def owns(credential: Credential, targetOwnerSub: Option[String]): Boolean =
    (principal(credential), targetOwnerSub.filter(isKnown)) match
      case (Account(subject), Some(owner)) => subject == owner
      case _ => false

  /** Bind a client-selected session id to the already-resolved server record too. */
  def ownsSession(credential: Credential, requestedSessionId: String, target: SessionRecord): Boolean =
    requestedSessionId == target.sessionId && owns(credential, Some(target.accountSub))

  private def isKnown(subject: String): Boolean =
    subject != null && !UnknownLabels(subject.trim.toLowerCase(java.util.Locale.ROOT)) && subject == subject.trim

  private[gateway] def isKnownOwner(subject: String): Boolean = isKnown(subject)

end GatewayOwnership
