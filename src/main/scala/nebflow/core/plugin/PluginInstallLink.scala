package nebflow.core.plugin

import cats.effect.IO
import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal

/** An install link is discovery only; it cannot confer trust. */
object PluginInstallLink:
  val CodeLinkUnparseable = "INSTALL_LINK_UNPARSEABLE"
  val CodeLinkNotInCatalog = "INSTALL_LINK_NOT_IN_CATALOG"
  val CodeLinkShortName = "INSTALL_LINK_SHORT_NAME"
  final case class InstallLink(id: String, version: String, sha256: String)

  final case class Match(
    catalog: PluginDistributionCatalog.VerifiedCatalog,
    resolved: PluginDistributionCatalog.Resolved
  )

  def parse(raw: String): Either[String, InstallLink] =
    def invalid = Left(s"invalid install link ($CodeLinkUnparseable)")
    try
      val uri = URI.create(raw)
      if uri.getScheme != "stand" || uri.getRawAuthority != "plugin" || uri.getRawPath != "/install" || uri.getRawFragment != null || uri.getRawQuery == null
      then invalid
      else
        val pairs = uri.getRawQuery.split("&", -1).toList.map { part =>
          val kv = part.split("=", 2)
          if kv.length != 2 then throw new IllegalArgumentException("invalid query")
          URLDecoder.decode(kv(0), UTF_8) -> URLDecoder.decode(kv(1), UTF_8)
        }
        val m = pairs.toMap
        if pairs.size != 3 || m.keySet != Set("id", "v", "s") || !m("id").matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+") ||
          m("v").isEmpty || m("v") != m("v").trim || !m("s").matches("[a-fA-F0-9]{64}")
        then invalid
        else Right(InstallLink(m("id"), m("v"), m("s").toLowerCase))
    catch case NonFatal(_) => invalid

    end try

  end parse

  def resolveLink(
    link: InstallLink,
    catalog: PluginDistributionCatalog.VerifiedCatalog
  ): Either[String, PluginDistributionCatalog.Resolved] =
    if !link.id.contains('.') then Left(s"full publisher identity required ($CodeLinkShortName)")
    else
      PluginDistributionCatalog
        .resolve(catalog.doc, link.id, link.version, link.sha256)
        .left
        .map(e => s"$e ($CodeLinkNotInCatalog)")

  def resolveAcrossConfigured(link: InstallLink, advance: Boolean = false): IO[Either[String, Match]] =
    def go(ids: List[String], errors: List[String], matches: List[Match]): IO[Either[String, Match]] = ids match
      case Nil =>
        if matches.size == 1 then IO.pure(Right(matches.head))
        else if matches.size > 1 then IO.pure(Left(s"ambiguous identity across catalogs ($CodeLinkNotInCatalog)"))
        else IO.pure(Left(errors.headOption.getOrElse(s"no verified catalog ($CodeLinkNotInCatalog)")))
      case id :: tail =>
        PluginDistributionCatalog.loadVerified(id, advance = false).flatMap {
          case Left(err) => go(tail, errors :+ err, matches)
          case Right(vc) =>
            resolveLink(link, vc) match
              case Left(err) => go(tail, errors :+ err, matches)
              case Right(r) => go(tail, errors, matches :+ Match(vc, r))
        }
    go(PluginDistributionCatalog.configuredSourceIds, Nil, Nil).flatMap {
      case Right(m) if advance =>
        PluginDistributionCatalog
          .loadVerified(m.catalog.sourceId, advance = true)
          .map(_.flatMap(vc => resolveLink(link, vc).map(r => Match(vc, r))))
      case other => IO.pure(other)
    }
  end resolveAcrossConfigured
end PluginInstallLink
