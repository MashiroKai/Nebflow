package nebflow.bridge

import cats.effect.IO
import cats.syntax.all.*
import com.lark.oapi.Client
import com.lark.oapi.service.im.v1.model.*
import io.circe.*
import io.circe.syntax.*
import nebflow.core.NebflowLogger

/**
 * Feishu API client using the official Java SDK.
 *
 * Replaces hand-rolled JDK HttpClient calls with com.lark.oapi.Client,
 * which handles token lifecycle, retries, and serialization internally.
 */
class FeishuClient(config: FeishuGlobalConfig):
  private val logger = NebflowLogger.forName("nebflow.bridge.client")

  private val client: Client = Client.newBuilder(config.appId, config.appSecret).build()

  /** Send a plain text message. Returns message_id. */
  def sendTextMessage(receiveId: String, receiveIdType: String, text: String): IO[String] =
    IO.blocking {
      val content = Json.obj("text" -> text.asJson).noSpaces
      val req = CreateMessageReq
        .newBuilder()
        .receiveIdType(receiveIdType)
        .createMessageReqBody(
          CreateMessageReqBody
            .newBuilder()
            .receiveId(receiveId)
            .msgType("text")
            .content(content)
            .build()
        )
        .build()
      client.im().message().create(req)
    }.flatMap { resp =>
      if resp.getCode() != 0 then
        IO.raiseError(new RuntimeException(s"Feishu create message error (code=${resp.getCode()}): ${resp.getMsg()}"))
      else IO.pure(resp.getData.getMessageId)
    }

  /** Send an interactive card message. Returns message_id. */
  def sendCardMessage(receiveId: String, receiveIdType: String, card: Json): IO[String] =
    IO.blocking {
      val req = CreateMessageReq
        .newBuilder()
        .receiveIdType(receiveIdType)
        .createMessageReqBody(
          CreateMessageReqBody
            .newBuilder()
            .receiveId(receiveId)
            .msgType("interactive")
            .content(card.noSpaces)
            .build()
        )
        .build()
      client.im().message().create(req)
    }.flatMap { resp =>
      if resp.getCode() != 0 then
        IO.raiseError(new RuntimeException(s"Feishu create card error (code=${resp.getCode()}): ${resp.getMsg()}"))
      else IO.pure(resp.getData.getMessageId)
    }

  /** Send a markdown card (agent response). Returns message_id. */
  def sendMarkdownCard(receiveId: String, receiveIdType: String, markdown: String): IO[String] =
    val card = Json.obj(
      "config" -> Json.obj("wide_screen_mode" -> true.asJson),
      "elements" -> List(
        Json.obj(
          "tag" -> "markdown".asJson,
          "content" -> markdown.asJson
        )
      ).asJson
    )
    sendCardMessage(receiveId, receiveIdType, card)

  /** Build a reply card with markdown content and a "停止" interrupt button. */
  private def buildReplyCard(markdown: String): Json =
    Json.obj(
      "config" -> Json.obj("wide_screen_mode" -> true.asJson),
      "elements" -> List(
        Json.obj(
          "tag" -> "markdown".asJson,
          "content" -> markdown.asJson
        ),
        Json.obj(
          "tag" -> "action".asJson,
          "actions" -> List(
            Json.obj(
              "tag" -> "button".asJson,
              "text" -> Json.obj("tag" -> "plain_text".asJson, "content" -> "⏹ 停止".asJson),
              "type" -> "danger".asJson,
              "value" -> Json.obj("action" -> "interrupt".asJson)
            )
          ).asJson
        )
      ).asJson
    )

  /** Send a reply card with markdown + interrupt button. Returns message_id. */
  def sendReplyCard(receiveId: String, receiveIdType: String, markdown: String): IO[String] =
    sendCardMessage(receiveId, receiveIdType, buildReplyCard(markdown))

  /** Patch a reply card, keeping the interrupt button. */
  def patchReplyCard(messageId: String, markdown: String): IO[Unit] =
    IO.blocking {
      val card = buildReplyCard(markdown)
      val req = PatchMessageReq
        .newBuilder()
        .messageId(messageId)
        .patchMessageReqBody(
          PatchMessageReqBody
            .newBuilder()
            .content(card.noSpaces)
            .build()
        )
        .build()
      client.im().message().patch(req)
    }.flatMap { resp =>
      if resp.getCode() != 0 then
        IO.raiseError(new RuntimeException(s"Feishu patch card error (code=${resp.getCode()}): ${resp.getMsg()}"))
      else IO.unit
    }

  /** Patch an existing card message with new markdown content. */
  def patchCard(messageId: String, markdown: String): IO[Unit] =
    IO.blocking {
      val card = Json.obj(
        "config" -> Json.obj("wide_screen_mode" -> true.asJson),
        "elements" -> List(
          Json.obj(
            "tag" -> "markdown".asJson,
            "content" -> markdown.asJson
          )
        ).asJson
      )
      val req = PatchMessageReq
        .newBuilder()
        .messageId(messageId)
        .patchMessageReqBody(
          PatchMessageReqBody
            .newBuilder()
            .content(card.noSpaces)
            .build()
        )
        .build()
      client.im().message().patch(req)
    }.flatMap { resp =>
      if resp.getCode() != 0 then
        IO.raiseError(new RuntimeException(s"Feishu patch card error (code=${resp.getCode()}): ${resp.getMsg()}"))
      else IO.unit
    }

  /** Update (patch) an existing message's content. */
  def patchMessage(messageId: String, text: String): IO[Unit] =
    IO.blocking {
      val content = Json.obj("text" -> text.asJson).noSpaces
      val req = PatchMessageReq
        .newBuilder()
        .messageId(messageId)
        .patchMessageReqBody(
          PatchMessageReqBody
            .newBuilder()
            .content(content)
            .build()
        )
        .build()
      client.im().message().patch(req)
    }.flatMap { resp =>
      if resp.getCode() != 0 then
        IO.raiseError(new RuntimeException(s"Feishu patch message error (code=${resp.getCode()}): ${resp.getMsg()}"))
      else IO.unit
    }

  /** Add an emoji reaction to a message. Returns reaction_id. */
  def addReaction(messageId: String, emoji: String): IO[String] =
    IO.blocking {
      val req = CreateMessageReactionReq
        .newBuilder()
        .messageId(messageId)
        .createMessageReactionReqBody(
          CreateMessageReactionReqBody
            .newBuilder()
            .reactionType(
              Emoji.newBuilder().emojiType(emoji).build()
            )
            .build()
        )
        .build()
      client.im().messageReaction().create(req)
    }.flatMap { resp =>
      if resp.getCode() != 0 then
        IO.raiseError(
          new RuntimeException(s"Feishu add reaction error (code=${resp.getCode()}): ${resp.getMsg()}")
        )
      else IO.pure(resp.getData.getReactionId)
    }

  /** Remove an emoji reaction from a message. */
  def deleteReaction(messageId: String, reactionId: String): IO[Unit] =
    IO.blocking {
      val req = DeleteMessageReactionReq
        .newBuilder()
        .messageId(messageId)
        .reactionId(reactionId)
        .build()
      client.im().messageReaction().delete(req)
    }.flatMap { resp =>
      if resp.getCode() != 0 then
        IO.raiseError(
          new RuntimeException(s"Feishu delete reaction error (code=${resp.getCode()}): ${resp.getMsg()}")
        )
      else IO.unit
    }

end FeishuClient
