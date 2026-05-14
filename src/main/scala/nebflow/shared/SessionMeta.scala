package nebflow.shared

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

case class SessionMeta(
  id: String,
  name: String,
  createdAt: Long,
  updatedAt: Long,
  hasUnread: Boolean,
  agentName: Option[String] = None,
  modelRef: Option[String] = None,
  bridges: Map[String, Json] = Map.empty,
  folderId: Option[String] = None
)

object SessionMeta:

  given Encoder[SessionMeta] = Encoder.instance { m =>
    val base = Json.obj(
      "id" -> m.id.asJson,
      "name" -> m.name.asJson,
      "createdAt" -> m.createdAt.asJson,
      "updatedAt" -> m.updatedAt.asJson,
      "hasUnread" -> m.hasUnread.asJson
    )
    val withAgent = m.agentName.fold(base)(n => base.deepMerge(Json.obj("agentName" -> n.asJson)))
    val withModel = m.modelRef.fold(withAgent)(r => withAgent.deepMerge(Json.obj("modelRef" -> r.asJson)))
    val withFolder = m.folderId.fold(withModel)(f => withModel.deepMerge(Json.obj("folderId" -> f.asJson)))
    // Legacy: read/write "feishu" field as bridges("feishu") for backward compat
    val withBridges =
      if m.bridges.nonEmpty then withFolder.deepMerge(Json.obj("bridges" -> m.bridges.asJson)) else withFolder
    val feishuLegacy = m.bridges.get("feishu").fold(withBridges)(f => withBridges.deepMerge(Json.obj("feishu" -> f)))
    feishuLegacy
  }

  given Decoder[SessionMeta] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      name <- c.downField("name").as[String]
      createdAt <- c.downField("createdAt").as[Long]
      updatedAt <- c.downField("updatedAt").as[Long]
      hasUnread <- c.downField("hasUnread").as[Option[Boolean]].map(_.getOrElse(false))
      agentName <- c.downField("agentName").as[Option[String]]
      modelRef <- c.downField("modelRef").as[Option[String]]
      folderId <- c.downField("folderId").as[Option[String]]
      bridges <- c.downField("bridges").as[Option[Map[String, Json]]].map(_.getOrElse(Map.empty))
      // Legacy: if "bridges" is empty but "feishu" exists, migrate it
      feishuLegacy <- c.downField("feishu").as[Option[Json]]
    yield
      val migrated = if bridges.isEmpty && feishuLegacy.nonEmpty then Map("feishu" -> feishuLegacy.get) else bridges
      SessionMeta(id, name, createdAt, updatedAt, hasUnread, agentName, modelRef, migrated, folderId)
  }

end SessionMeta
