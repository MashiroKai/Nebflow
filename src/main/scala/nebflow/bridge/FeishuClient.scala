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

end FeishuClient
