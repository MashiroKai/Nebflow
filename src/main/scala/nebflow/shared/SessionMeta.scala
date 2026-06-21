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
    if m.bridges.nonEmpty then withFolder.deepMerge(Json.obj("bridges" -> m.bridges.asJson)) else withFolder
  }

  given Decoder[SessionMeta] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      name <- c.downField("name").as[String]
      createdAt <- c.downField("createdAt").as[Option[Long]].map(_.orElse(
        c.downField("updatedAt").as[Option[Long]].toOption.flatten
      ).getOrElse(0L))
      updatedAt <- c.downField("updatedAt").as[Long]
      hasUnread <- c.downField("hasUnread").as[Option[Boolean]].map(_.getOrElse(false))
      agentName <- c.downField("agentName").as[Option[String]]
      modelRef <- c.downField("modelRef").as[Option[String]]
      folderId <- c.downField("folderId").as[Option[String]]
      bridges <- c.downField("bridges").as[Option[Map[String, Json]]].map(_.getOrElse(Map.empty))
    yield SessionMeta(id, name, createdAt, updatedAt, hasUnread, agentName, modelRef, bridges, folderId)
  }

end SessionMeta
