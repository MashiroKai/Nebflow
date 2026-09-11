package nebflow.neblink

import nebflow.core.Branding

import java.net.URI

/**
  * Isolation guard for automatic enrollment (2026-09-11 作者裁定).
  *
  * Device ids are now machine-code derived (see `DeviceIdentity`), so a
  * redirected data root (`--home /tmp/qa-x`, `<PREFIX>_HOME=…`) deliberately
  * derives a *different* id than the author's main client — the two do not share
  * an identity. That alone, however, does not stop a throwaway instance from
  * registering a brand-new device row in the PRODUCTION network, which is how
  * `/tmp/nebflow-coldstart` polluted the production device table on 2026-09-11
  * (dup-device-verdict §1.2 类 2, P1).
  *
  * Policy (avoids any behaviour change on the default data root):
  *   (non-default data root) AND (target host is inside the production domain)
  *   AND (no explicit switch)  ⇒  refuse the *automatic* enroll/join, with a
  *   loud log — never silently.
  *
  * The switch is `NEBFLOW_ALLOW_PROD_ENROLL=1` (also `=true` / `=yes`), read via
  * [[Branding.env]] (brand prefix, with the hardcoded NEBFLOW_ prefix as the
  * fallback — same name today). With it set, behaviour is exactly as before.
  *
  * Local / custom hosts are unaffected (small blast radius): the gate only
  * fires for the production domain.
  */
object EnrollGuard:

  /** The one explicit escape hatch. Env var, no config surface (it is an
    * operator/test knob, not a user setting). */
  val AllowProdEnrollEnv: String = "NEBFLOW_ALLOW_PROD_ENROLL"

  /** Production domain; subdomains included. */
  private val ProdDomain: String = "nebflow.space"

  /** True when `url` points into the production domain (subdomains included).
    *
    * Host is taken from the URI; when the string is not URI-parseable (custom
    * schemes etc.) a literal containment check keeps the gate closed instead of
    * silently opening it. */
  private[neblink] def isProdHost(url: String): Boolean =
    val raw = Option(url).map(_.trim).getOrElse("")
    if raw.isEmpty then false
    else
      hostOf(raw) match
        case Some(h) => h == ProdDomain || h.endsWith("." + ProdDomain)
        case None    => raw.contains(ProdDomain)

  private[neblink] def hostOf(url: String): Option[String] =
    try
      val withScheme = if url.contains("://") then url else s"https://$url"
      Option(URI.create(withScheme).getHost).map(_.toLowerCase).filter(_.nonEmpty)
    catch case _: Exception => None

  /** Live decision — reads the current data root and the explicit switch. */
  def enrollRefusal(serverUrl: String): Option[String] =
    enrollRefusal(serverUrl, DeviceIdentity.isNonDefaultHome, explicitAllowEnv)

  /** Pure decision core (testable): `Some(reason)` = the automatic enroll must
    * be refused; `None` = proceed exactly as before. */
  def enrollRefusal(
    serverUrl: String,
    nonDefaultHome: Boolean,
    explicitAllow: Boolean
  ): Option[String] =
    if !nonDefaultHome then None
    else if explicitAllow then None
    else if !isProdHost(serverUrl) then None
    else
      Some(
        s"refused: this instance runs on an isolated data root, so it must not auto-register " +
          s"with the production NebLink server ($serverUrl). Set $AllowProdEnrollEnv=1 to allow " +
          s"it explicitly (same account as another instance ⇒ one live session, the older one is kicked)."
      )

  /** Parsed explicit switch: `1` / `true` / `yes` (case-insensitive).
    * `Branding.env` takes the bare suffix and prepends the brand prefix, so this
    * reads `NEBFLOW_ALLOW_PROD_ENROLL` today. */
  private[neblink] def explicitAllowEnv: Boolean =
    Branding
      .env(AllowProdEnrollEnv.stripPrefix("NEBFLOW_"))
      .map(_.trim.toLowerCase)
      .exists(v => v == "1" || v == "true" || v == "yes")

end EnrollGuard
