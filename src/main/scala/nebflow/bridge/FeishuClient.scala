package nebflow.bridge

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * HTTP client for Feishu Open Platform REST APIs.
 * Uses JDK 11+ HttpClient directly — no extra dependency needed.
 * Handles tenant_access_token lifecycle (cache + refresh).
 */
class FeishuClient(config: FeishuGlobalConfig):
  private val logger = NebflowLogger.forName("nebflow.bridge.client")
  private val baseUrl = "https://open.feishu.cn"

  private val httpClient: HttpClient =
    HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .build()

  // Cached token with expiry timestamp (epoch seconds)
  private val tokenRef: Ref[IO, Option[(String, Long)]] = Ref.unsafe(None)

  // ===== Token Management =====

  /** Get the current tenant_access_token (fetching/refreshing as needed). */
  def getToken: IO[String] = requestToken

  private def requestToken: IO[String] =
    for
      now <- IO(System.currentTimeMillis() / 1000)
      cached <- tokenRef.get
      hit <- cached match
        case Some((token, expiresAt)) if expiresAt > now + 60 => IO.pure(Some(token))
        case _ => IO.pure(None)
      token <- hit match
        case Some(t) => IO.pure(t)
        case None => fetchNewToken
    yield token

  private def fetchNewToken: IO[String] =
    val body = Json.obj(
      "app_id" -> config.appId.asJson,
      "app_secret" -> config.appSecret.asJson
    ).noSpaces
    for
      resp <- post(s"$baseUrl/open-apis/auth/v3/tenant_access_token/internal", body, None)
      parsed <- IO.fromEither(decode[Json](resp))
      code <- IO.fromOption(parsed.hcursor.downField("code").as[Int].toOption)(
        new RuntimeException(s"Feishu token response missing code: ${resp.take(200)}")
      )
      _ <- if code != 0 then
        IO.raiseError(new RuntimeException(s"Feishu token error (code=$code): ${resp.take(300)}"))
      else IO.unit
      token <- IO.fromEither(parsed.hcursor.downField("tenant_access_token").as[String])
      expire <- IO.fromEither(parsed.hcursor.downField("expire").as[Int].map(_.toLong))
      now <- IO(System.currentTimeMillis() / 1000)
      _ <- tokenRef.set(Some((token, now + expire)))
      _ <- logger.debug("Feishu tenant_access_token refreshed")
    yield token

  // ===== Send Messages =====

  /** Send a plain text message. Returns message_id. */
  def sendTextMessage(receiveId: String, receiveIdType: String, text: String): IO[String] =
    for
      token <- requestToken
      body = Json.obj(
        "receive_id" -> receiveId.asJson,
        "msg_type" -> "text".asJson,
        "content" -> Json.obj("text" -> text.asJson).asJson
      ).noSpaces
      resp <- post(s"$baseUrl/open-apis/im/v1/messages?receive_id_type=$receiveIdType", body, Some(token))
      parsed <- IO.fromEither(decode[Json](resp))
      msgId <- IO.fromEither(
        parsed.hcursor.downField("data").downField("message_id").as[String]
      )
    yield msgId

  /** Send an interactive card message. Returns message_id. */
  def sendCardMessage(receiveId: String, receiveIdType: String, card: Json): IO[String] =
    for
      token <- requestToken
      body = Json.obj(
        "receive_id" -> receiveId.asJson,
        "msg_type" -> "interactive".asJson,
        "content" -> card.asJson
      ).noSpaces
      resp <- post(s"$baseUrl/open-apis/im/v1/messages?receive_id_type=$receiveIdType", body, Some(token))
      parsed <- IO.fromEither(decode[Json](resp))
      msgId <- IO.fromEither(
        parsed.hcursor.downField("data").downField("message_id").as[String]
      )
    yield msgId

  /** Update (patch) an existing message's content. */
  def patchMessage(messageId: String, text: String): IO[Unit] =
    for
      token <- requestToken
      body = Json.obj(
        "content" -> Json.obj("text" -> text.asJson).asJson
      ).noSpaces
      _ <- patch(s"$baseUrl/open-apis/im/v1/messages/$messageId", body, token)
    yield ()

  // ===== Low-level HTTP =====

  private def post(url: String, body: String, bearerToken: Option[String]): IO[String] =
    IO.blocking {
      val builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
      bearerToken.foreach(t => builder.header("Authorization", s"Bearer $t"))
      val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      if response.statusCode() >= 200 && response.statusCode() < 300 then
        response.body()
      else
        throw new RuntimeException(s"Feishu API HTTP ${response.statusCode()}: ${response.body().take(300)}")
    }

  private def patch(url: String, body: String, bearerToken: String): IO[String] =
    IO.blocking {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer $bearerToken")
        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
        .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode() >= 200 && response.statusCode() < 300 then
        response.body()
      else
        throw new RuntimeException(s"Feishu API HTTP ${response.statusCode()}: ${response.body().take(300)}")
    }

end FeishuClient
