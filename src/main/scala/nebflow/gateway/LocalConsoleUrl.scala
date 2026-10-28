package nebflow.gateway

import cats.effect.IO
import io.circe.parser.parse
import nebflow.cli.GatewayClient

import java.net.{HttpURLConnection, Proxy, URI, URLEncoder}
import java.nio.charset.StandardCharsets
import scala.util.Using

/** Establish a browser session using the existing local credential, without
  * placing that long-lived credential in a URL or contacting a remote host.
  */
object LocalConsoleUrl:
  private[gateway] def issue(localUrl: String, token: String): Option[String] =
    val base = URI.create(localUrl)
    require(base.getScheme == "http" && Set("localhost", "127.0.0.1", "[::1]").contains(base.getHost))
    require(base.getRawUserInfo == null && base.getRawQuery == null && base.getRawFragment == null)
    val conn = base.resolve("/api/auth/console-ticket").toURL
      .openConnection(Proxy.NO_PROXY).asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(1500)
    conn.setReadTimeout(1500)
    conn.setInstanceFollowRedirects(false)
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Authorization", s"Bearer $token")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setDoOutput(true)
    try
      Using.resource(conn.getOutputStream)(_.write("{}".getBytes(StandardCharsets.UTF_8)))
      if conn.getResponseCode != 200 then None
      else
        val body = Using.resource(scala.io.Source.fromInputStream(conn.getInputStream, "UTF-8"))(_.mkString)
        parse(body).toOption.flatMap(_.hcursor.get[String]("ticket").toOption)
          .filter(_.nonEmpty).map { ticket =>
            s"${base.resolve("/")}?ticket=${URLEncoder.encode(ticket, StandardCharsets.UTF_8)}"
          }
    finally conn.disconnect()

  def authenticated(localUrl: String): IO[String] =
    GatewayClient.readToken.flatMap {
      case Some(token) => IO.blocking(issue(localUrl, token)).map(_.getOrElse(localUrl))
      case None => IO.pure(localUrl)
    }.handleError(_ => localUrl)
end LocalConsoleUrl
