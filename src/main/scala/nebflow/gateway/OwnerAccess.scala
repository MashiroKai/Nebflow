package nebflow.gateway

import cats.effect.IO
import org.http4s.Request

/**
 * A capability for owner-scoped operations. The constructor is private: callers
 * obtain an instance only by authenticating an HTTP request through
 * [[GatewayAuth]]. A successfully authenticated Bearer still has Unknown
 * ownership and therefore cannot create or access an owned resource.
 */
final class OwnerAccess private (private val credential: Credential, private val store: OwnerStore):

  /** The authenticated account, if this credential has a trusted owner. */
  def principal: GatewayOwnership.Principal = GatewayOwnership.principal(credential)

  /** Resolve an owner only from the server-side registry, then compare it to this caller. */
  def ownsResource(kind: String, resourceId: String): IO[Boolean] =
    store.resolveResource(kind, resourceId).map(GatewayOwnership.owns(credential, _))

  /** Create an owner row for this authenticated account; unknown owners fail closed. */
  def createResource(kind: String, resourceId: String): IO[Boolean] =
    store.createResource(kind, resourceId, credential)

  /** Copy an existing resource only when its stored owner matches this caller. */
  def copyResource(kind: String, sourceId: String, targetId: String): IO[Boolean] =
    store.copyResource(kind, sourceId, targetId, credential)

  /** Verify a session selector against the registry; the selector is never an owner claim. */
  def ownsSession(requestedSessionId: String): IO[Boolean] =
    store.ownsSession(credential, requestedSessionId)

object OwnerAccess:

  /** Mint from a credential already validated by GatewayAuth at a WS handshake. */
  private[gateway] def fromAuthenticatedSession(record: SessionRecord, store: OwnerStore): OwnerAccess =
    new OwnerAccess(Credential.ViaSession(record), store)

  /** REST/Static/AskUser convenience: use the shared request context and production owner registry. */
  def authenticate(ctx: RestApiCtx, req: Request[IO]): IO[Option[OwnerAccess]] =
    authenticate(ctx, req, GatewayAuth.Rails.ApiFull)

  /** Same capability boundary for Static / AskUser faces with their narrower auth rail. */
  def authenticate(ctx: RestApiCtx, req: Request[IO], rails: GatewayAuth.Rails): IO[Option[OwnerAccess]] =
    ctx.tokenRef.get.flatMap { token =>
      authenticate(
        req,
        token,
        ctx.sessions,
        OwnerStore.forDataRoot(nebflow.shared.PathUtil.dataRoot.toNIO),
        rails
      )
    }

  /** Authenticate with the supplied route rail and mint an access capability on a credential hit. */
  def authenticate(
    req: Request[IO],
    fileToken: String,
    sessions: GatewaySessionStore,
    store: OwnerStore,
    rails: GatewayAuth.Rails
  ): IO[Option[OwnerAccess]] =
    GatewayAuth.authenticate(req, fileToken, sessions, rails).map(_.map(new OwnerAccess(_, store)))

end OwnerAccess
