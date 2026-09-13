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
  folderId: Option[String] = None,
  // ⚠ **非权威字段**（2026-09-13 permshield S1；2026-09-12 全局单一权威源批已降级）：
  // 本键（`sessions/_index.json` 的逐会话 `safetyMode`）**不被任何权威读取点消费**
  // ——有效档位 = 应用级全局持久档位（`SharedResources.effectiveSafetyMode` ⇒
  // `nebflow.json` 的 `safety.defaultMode`），已无会话覆盖面。
  // 存量字节一字不动（读时忽略），但**不得**把它接回权威读取点：
  // meta 取任何值都不改变有效档位（`SafetyModeGlobalOnlySpec` 承重钉住）。
  //
  // 启动默认 = 全部放行（2026-09-12 作者令）历史沿革：未显式传 mode 的构造点
  // （createDefaultSession / migrateFromLegacy / ensureAgentSession）曾把顶档写进
  // 新建会话。新模型下新建通路**不再写 meta**（SessionService / REST POST
  // /sessions 去 safetyMode 参数），此默认值只作为构造缺省保留；Decoder 侧的
  // 缺省（`absent ⇒ confirm-edits`）也**不再有权威意义**（历史会话的盘上值照旧）。
  safetyMode: String = "auto-all",
  gitBranch: Option[String] = None,
  flowName: Option[String] = None
)

object SessionMeta:

  /** 会话列表**出口**的权威 overlay（公共 helper 本体）；调用方经
    * `SharedResources.overlaySessionList` 取全局档位后落到这里，全仓只此一处
    * 构造这个 JSON。逐会话 `safetyMode` **显式写出**（三档值在 wire 上恒存在，
    * 不再依赖 Encoder「= confirm-edits 时省略键」的隐式契约），取值 = 有效档位
    * = **应用级全局值**（permshield S1：已无会话覆盖面，故无 per-session 入参）。
    *
    * ⚠ 线上出口专用，**禁**用于 `SessionStore.saveIndex` 的落盘序列化（盘上零改动）。 */
  def withEffectiveSafetyModes(
    sessions: List[SessionMeta],
    global: nebflow.core.SafetyMode
  ): Json =
    import io.circe.syntax.*
    val modeJson = nebflow.core.SafetyMode.toString(global).asJson
    sessions
      .map(s => s.asJson.deepMerge(Json.obj("safetyMode" -> modeJson)))
      .asJson

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
    val withSafety =
      if m.safetyMode != "confirm-edits" then withFolder.deepMerge(Json.obj("safetyMode" -> m.safetyMode.asJson))
      else withFolder
    val withGit = m.gitBranch.fold(withSafety)(b => withSafety.deepMerge(Json.obj("gitBranch" -> b.asJson)))
    val withFlow = m.flowName.fold(withGit)(f => withGit.deepMerge(Json.obj("flowName" -> f.asJson)))
    if m.bridges.nonEmpty then withFlow.deepMerge(Json.obj("bridges" -> m.bridges.asJson)) else withFlow
  }

  given Decoder[SessionMeta] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      name <- c.downField("name").as[String]
      createdAt <- c
        .downField("createdAt")
        .as[Option[Long]]
        .map(
          _.orElse(
            c.downField("updatedAt").as[Option[Long]].toOption.flatten
          ).getOrElse(0L)
        )
      updatedAt <- c.downField("updatedAt").as[Long]
      hasUnread <- c.downField("hasUnread").as[Option[Boolean]].map(_.getOrElse(false))
      agentName <- c.downField("agentName").as[Option[String]]
      modelRef <- c.downField("modelRef").as[Option[String]]
      folderId <- c.downField("folderId").as[Option[String]]
      bridges <- c.downField("bridges").as[Option[Map[String, Json]]].map(_.getOrElse(Map.empty))
      // Migrate: bypass=true → safetyMode=auto-all, bypass=false → confirm-edits
      legacyBypass <- c.downField("bypass").as[Option[Boolean]]
      safetyMode <- c.downField("safetyMode").as[Option[String]]
      gitBranch <- c.downField("gitBranch").as[Option[String]]
      flowName <- c.downField("flowName").as[Option[String]]
    yield
      val mode = safetyMode.getOrElse(if legacyBypass.getOrElse(false) then "auto-all" else "confirm-edits")
      SessionMeta(
        id,
        name,
        createdAt,
        updatedAt,
        hasUnread,
        agentName,
        modelRef,
        bridges,
        folderId,
        mode,
        gitBranch,
        flowName
      )
  }

end SessionMeta
