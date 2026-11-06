package nebflow.core.search.chat

import io.circe.Json
import io.circe.parser.decode
import nebflow.core.search.{SearchIndexAdapter, SearchIndexService}
import nebflow.shared.{UiMessage, *}

import java.time.{Instant, LocalDate, ZoneId}

/**
 * ChatSearchAdapter —— 聊天检索面 adapter（datalayer-spec 批 2026-10-30，C1）。
 *
 * 检索源 = `<dataRoot>/sessions/<sid>.ui.json`（只读真源，绝不写——§4.1 纪律 1；
 * 目录先例 stand-recall 头注同款）。文档抽取口径与前端 chatSearch.js
 * normalizeMessage + popMediaIndex.indexMedia **同源对齐**：
 *
 *   - message 片：user（injected=false）/ai/agent/ask 行的可检索文本
 *     （user 行附 📎 附件名——§6.1 口径）；injected=true 的 user 行是
 *     「agent 产生」注入行 → agent 类（messageKind 单一判别点同款）。
 *   - tool 行**不入索引**（聊天记录搜索优化批 2026-10-30 作者令①：搜索结果只搜
 *     对话——tool call/tool result 的 summary/input/content 噪声不进索引不进
 *     结果）；但 tool 行的 Pop 载荷仍按原口径产出 media/file/link 条目（工件
 *     面，非「对话检索」，三个分类 tab 的数据源不动）。
 *   - extra 投影：message 片 = {role: user|agent|ai, agent?}（前端 messageKind
 *     同款词汇——浮窗 (sid,ord) 直定位的 typeMatches 快路靠它对齐）；tool 片 =
 *     {tool: 净名}（已随 tool 行退索引，仅旧库残留行可能携带）；media/file/link
 *     片 = popMediaIndex 条目形状（id/sessionId/msgOrd/ts/type/source/name/ext/
 *     size/ref/thumbRef），前端网格**免重抽**（C4 验收面）。Pop 载荷用 circe
 *     宽松解析（含非法 JSON 的容忍——getOrElse 退化）。
 *
 * 时间锚：tool/ask/agent 行无 ts → 取向前最近一条带 ts 的行（popMediaIndex
 * 同款「回复锚」语义，media/file/link 条目仍消费）；行级 ts=0 且无前锚 ⇒ 0
 * （排未日期尾部）。
 *
 * **会话范围（会话搜索范围收窄批 2026-11-01，作者 17:29 令）**：本 adapter 只收
 * **主会话**（[[nebflow.shared.SessionScope]] 单点判据）；后台 agent 会话
 * （delegate/subtask/subagent/workflow/node/dispatcher/external/dag）不抽取、不
 * 指纹、不入库，详见 [[excludedSidPrefixes]]。
 */
final class ChatSearchAdapter(sessionsDir: os.Path) extends SearchIndexAdapter:

  override val face: String = "chat"

  /**
   * 检索面会话范围（会话搜索范围收窄批 2026-11-01，作者 17:29 令「只搜主会话，
   * 后台 agent 产生的消息一条不出」）：后台 agent 会话族（delegate/subtask/
   * subagent/workflow/node/dispatcher/external/dag——判据单点 =
   * [[nebflow.shared.SessionScope]]）**不入索引**。
   *
   * 收口形态（不是前端事后剔除，是数据源层）：
   *   - `extractSession` ⇒ None（索引侧走「删旧行」分支 ⇒ 本批之前入库的后台
   *     会话文档随首次同步清零）；
   *   - `sessionFingerprint` / `sessionMtime` ⇒ None（`upToDate` 恒 false ⇒ 一定
   *     走到删旧分支，不被「指纹未变」快路卡住）；
   *   - 查询侧同前缀兜底见 [[SearchIndexService]] 的 `excludedSidPrefixes`。
   */
  override def excludedSidPrefixes: List[String] = SessionScope.BackgroundAgentPrefixes

  /** 检索面准入（主会话才入库；判据单点见 [[SessionScope]]）。 */
  private def inScope(sid: String): Boolean = SessionScope.isMainSession(sid)

  private def uiFile(sid: String): os.Path = sessionsDir / s"$sid.ui.json"

  def sessionMtime(sid: String): Option[Long] =
    if !inScope(sid) then None
    else
      val f = uiFile(sid)
      if os.exists(f) then Some(os.mtime(f)) else None

  override def sessionMtimeOpt(sid: String): Option[Long] = sessionMtime(sid)

  /** 月桶键（§4.2）：文件 mtime 落桶；当月 = "current"（开口增量桶）。 */
  override def monthKey(sid: String): String =
    if !inScope(sid) then ""
    else
      sessionMtime(sid) match
        case None => ""
        case Some(ms) =>
          val now = LocalDate.now()
          val d   = LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
          if d.getYear == now.getYear && d.getMonth == now.getMonth then "current"
          else f"${d.getYear}%04d${d.getMonthValue}%02d"

  /**
   * 会话指纹（对账键）：mtime + size。廉价且「漏重建方向安全」——mtime 前进
   * 必触发重建；时钟回拨最坏 = 本批次漏更，由下一次前进/惰性对账收敛（§4.3）。
   *
   * 检索面外的会话恒 None（见 [[excludedSidPrefixes]]）：指纹 None ⇒ 索引侧
   * `upToDate` 恒 false ⇒ 必走 `extractSession`（也恒 None）⇒ 旧行清零。
   */
  override def sessionFingerprint(sid: String): Option[String] =
    if !inScope(sid) then None
    else
      val f = uiFile(sid)
      if os.exists(f) then Some(s"${os.mtime(f)}:${os.size(f)}") else None

  override def extractSession(sid: String, forceFull: Boolean): Option[List[SearchIndexService.IndexDoc]] =
    if !inScope(sid) then None
    else
      val f = uiFile(sid)
      if !os.exists(f) then None
      else
        val msgs = decode[List[UiMessage]](os.read(f)).getOrElse(Nil)
        if msgs.isEmpty then None
        else Some(extractDocs(sid, msgs))

  // ── 抽取（与 chatSearch.js/popMediaIndex.js 同源口径）────────────────────

  private def extractDocs(sid: String, msgs: List[UiMessage]): List[SearchIndexService.IndexDoc] =
    val docs = scala.collection.mutable.ListBuffer.empty[SearchIndexService.IndexDoc]
    var anchor = 0L
    msgs.zipWithIndex.foreach { case (m, ord) =>
      val rowTs = msgTs(m)
      if rowTs > 0 then anchor = rowTs
      val ts = if rowTs > 0 then rowTs else anchor
      m match
        case u: UiMessage.User =>
          val injected = u.injected
          if !injected then
            val attNames = u.attachments.flatMap(attName)
            val attText  =
              if attNames.isEmpty then ""
              else "\n" + attNames.map(n => s"📎 $n").filter(_.trim.length > 2).mkString("\n")
            docs += SearchIndexService.IndexDoc(
              sid = sid, ord = ord, kind = "message", ts = u.timestamp,
              text = u.text + attText,
              extra = roleExtra("user", u.sender.getOrElse("")))
          else
            docs += SearchIndexService.IndexDoc(
              sid = sid, ord = ord, kind = "message", ts = u.timestamp,
              text = u.text, extra = roleExtra("agent", u.sender.getOrElse("")))
        case a: UiMessage.Ai =>
          docs += SearchIndexService.IndexDoc(
            sid = sid, ord = ord, kind = "message", ts = a.timestamp,
            text = a.text + a.thinking.fold("")(th => "\n" + th), extra = roleExtra("ai"))
        case g: UiMessage.Agent =>
          docs += SearchIndexService.IndexDoc(
            sid = sid, ord = ord, kind = "message", ts = ts, text = g.text, extra = roleExtra("ai"))
        case q: UiMessage.Ask =>
          docs += SearchIndexService.IndexDoc(
            sid = sid, ord = ord, kind = "message", ts = ts,
            text = List(q.question, q.answer).filter(_.nonEmpty).mkString("\n"), extra = roleExtra("ai"))
        // 聊天记录搜索优化批 2026-10-30 作者令①：tool call/result 行不入索引
        // （搜索结果只搜对话）。Pop 载荷/media 条目在下面 mediaEntriesOf 照产。
        case _: UiMessage.Tool => ()
        case _ => () // system/askUser/askPermission 不入索引（与前端口径一致）
      // media/file/link 条目（Pop 载荷 + user 附件——indexMedia 同源）
      mediaEntriesOf(sid, m, ord, ts).foreach { e =>
        docs += SearchIndexService.IndexDoc(
          sid = sid, ord = ord, kind = e.entryType, ts = e.ts, text = e.searchText, extra = e.json)
      }
    }
    docs.toList

  private def msgTs(m: UiMessage): Long = m match
    case u: UiMessage.User => u.timestamp
    case a: UiMessage.Ai   => a.timestamp
    case _                 => 0L

  /** message 片的角色投影：前端 messageKind 同款词汇（user|agent|ai——
   *  浮窗 (sid,ord) 直定位的 typeMatches 快路靠它对齐；injected user ⇒
   *  agent，ai/agent/ask 三类 ⇒ ai）。 */
  private def roleExtra(role: String, agent: String = ""): String =
    val base = io.circe.Json.obj("role" -> io.circe.Json.fromString(role))
    (if agent.nonEmpty then base.deepMerge(
      io.circe.Json.obj("agent" -> io.circe.Json.fromString(agent))) else base).noSpaces

  private def attName(a: Json): Option[String] =
    a.hcursor.downField("name").as[String].toOption.filter(_.nonEmpty)

  // ── 媒体条目（popMediaIndex.indexMedia 的同源移植——返回形状直接作 extra）──

  private final case class MediaEntry(
    entryType: String,   // media|file|link（doc.kind 用）
    ts: Long,
    searchText: String,  // name + ext + ref（entryMatchesKeyword 同款字段面）
    json: String         // popMediaIndex 条目形状（前端免重抽）
  )

  private def mediaEntriesOf(sid: String, m: UiMessage, ord: Int, ts: Long): List[MediaEntry] =
    m match
      case u: UiMessage.User =>
        u.attachments.flatMap { a =>
          val tpe  = a.hcursor.downField("type").as[String].getOrElse("")
          val name = a.hcursor.downField("name").as[String].getOrElse("")
          val path = a.hcursor.downField("path").as[String].getOrElse("")
          if tpe == "ref" || tpe == "taskRef" then None
          else if tpe == "image" || tpe == "video" then
            Some(mkEntry(sid, ord, ts, "media", "user", name, path, a, itemType = tpe))
          else if name.nonEmpty || path.nonEmpty then
            Some(mkEntry(sid, ord, ts, "file", "user", name, path, a, itemType = "file"))
          else None
        }
      case t: UiMessage.Tool =>
        popItems(t.content).flatMap { item =>
          val url = item.hcursor.downField("url").as[String].toOption.filter(_.nonEmpty)
          val itemType = item.hcursor.downField("itemType").as[String].getOrElse("")
          if itemType == "url" || url.isDefined then
            val u = url.getOrElse("")
            Some(mkEntry(sid, ord, ts, "link", "agent",
              item.hcursor.downField("title").as[String]
                .orElse(item.hcursor.downField("name").as[String]).getOrElse(baseName(u)),
              u, item, itemType = "link"))
          else
            val path = item.hcursor.downField("path").as[String].getOrElse("")
            if path.isEmpty then None
            else
              val kind = item.hcursor.downField("kind").as[String].getOrElse("")
              val tpe = if kind == "image" || kind == "video" then "media" else "file"
              Some(mkEntry(sid, ord, ts, tpe, "agent",
                item.hcursor.downField("name").as[String].getOrElse(baseName(path)),
                path, item, itemType = kind))
        }
      case _ => Nil

  private def mkEntry(
    sid: String, ord: Int, ts: Long, entryType: String, source: String,
    name: String, ref: String, raw: Json, itemType: String
  ): MediaEntry =
    val ext = extOf(name)
    val size = raw.hcursor.downField("size").as[Long].toOption
      .orElse(raw.hcursor.downField("size").as[Int].toOption.map(_.toLong)).getOrElse(0L)
    // thumbRef：data URI 内联缩略图原样引用（引用非拷贝——popMediaIndex 同款纪律）
    val thumbRef = raw.hcursor.downField("preview").as[String]
      .orElse(raw.hcursor.downField("src").as[String])
      .toOption.filter(_.startsWith("data:")).getOrElse("")
    val entryJson = Json.obj(
      "id"           -> Json.fromString(s"$sid:$ord"),
      "sessionId"    -> Json.fromString(sid),
      "msgOrd"       -> Json.fromInt(ord),
      "ts"           -> Json.fromLong(ts),
      "type"         -> Json.fromString(itemType),
      "source"       -> Json.fromString(source),
      "name"         -> Json.fromString(name),
      "ext"          -> Json.fromString(ext),
      "size"         -> Json.fromLong(size),
      "ref"          -> Json.fromString(ref),
      "thumbRef"     -> Json.fromString(thumbRef)
    ).noSpaces
    MediaEntry(
      entryType = entryType,
      ts = ts,
      searchText = Seq(name, ext, ref).filter(_.nonEmpty).mkString("\n"),
      json = entryJson
    )

  /** Pop 载荷解析：___POP_JSON___ 标记 + 尾段 JSON 数组（tolerant——与
   *  popArtifacts.isPopPayload/parsePopPayload 同口径的宽松版；非 Pop 行 ⇒ Nil）。 */
  private def popItems(content: String): List[Json] =
    if content == null || !content.startsWith("___") || !content.contains("_JSON___") then Nil
    else
      val idx = content.indexWhere(c => c == '[' || c == '{')
      if idx < 0 then Nil
      else decode[List[Json]](content.drop(idx)).getOrElse(Nil)

  private def extOf(name: String): String =
    val i = name.lastIndexOf('.')
    if i > 0 then name.substring(i + 1).toLowerCase else ""

  private def baseName(p: String): String =
    val i = p.lastIndexOf('/')
    if i >= 0 then p.substring(i + 1) else p

end ChatSearchAdapter
