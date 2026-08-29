package nebflow.gateway

import io.circe.Json

/**
 * #303 D1/D5 — resolve a unified Reference (spec 20260825_global-reference-spec
 * v1.1 §2.3 ③) into a backend injection block appended to the user message.
 *
 * Pointer semantics (D5): the block carries source + anchor ONLY — never the
 * referenced content (token budget ≤ ~100). The agent reads content on demand
 * with its tools, same as the existing [用户附加文件: path] pointer pattern.
 *
 * refType = "task" routes to the return flow (WebSocketRoutes.processTaskReturns,
 * 引用=打回 v1.1) and reuses Task.returnInjectionBlock — NOT resolved here.
 * Unknown refTypes / unresolvable refs yield None (fail-open: the message and
 * the other refs are never blocked).
 *
 * Block format (spec §2.3 ③):
 *   [引用: {类型} · {title}{anchor 摘要} · {来源}]
 *   e.g. [引用: 文档 · paper.pdf · p.3–4 · /abs/paper.pdf]
 */
object RefResolver:

  /** QC follow-up (#303): every client-provided string interpolated into the
    * block passes through sanitize — strip control chars (\n \r \t, C0/C1,
    * U+2028/U+2029) so a crafted title from an external source (web page
    * title, canvas doc) cannot forge extra injection lines such as a fake
    * [打回任务…] block. Spaces are preserved (legitimate in paths/titles). */
  def sanitize(s: String): String =
    s.filter(ch => ch >= ' ' && ch != '\u007F' && ch != '\u2028' && ch != '\u2029')

  /** Titles are the only free-text field with no length bound of its own —
    * cap at 80 chars to honor the ≤ ~100 token pointer budget (D5). */
  def cleanTitle(s: String): String =
    val t = sanitize(s)
    if t.length <= 80 then t else t.take(80)

  /** QC follow-up (#303): the same task referenced twice (double-pushed refs
    * or legacy+unified duplicate) must fire the return flow once — the second
    * `return` would hit IllegalStateException (task already in_progress) and
    * emit a spurious taskError frame. First occurrence wins, order stable. */
  def dedupeTaskRefs(refs: List[(String, String)]): List[(String, String)] =
    refs.distinct

  /** Build the [引用: …] injection block, or None when the ref cannot be
    * resolved (unknown refType / missing identity). Pure — no IO, no state. */
  def resolve(ref: Json): Option[String] =
    val c = ref.hcursor
    val refType = c.downField("refType").as[String].getOrElse("")
    val src = c.downField("source")
    val anchor = c.downField("anchor")
    refType match
      case "file" =>
        val path = sanitize(src.downField("path").as[String].getOrElse(""))
        if path.isEmpty then None
        else
          val title = cleanTitle(
            src.downField("title").as[String]
              .orElse(src.downField("fileName").as[String])
              .getOrElse(fileNameOf(path))
          )
          Some(s"[引用: 文件 · $title${anchorText(anchor)} · $path]")
      case "document" =>
        val path = sanitize(src.downField("path").as[String].getOrElse(""))
        if path.isEmpty then None
        else
          val title = cleanTitle(
            src.downField("title").as[String]
              .orElse(src.downField("fileName").as[String])
              .getOrElse(fileNameOf(path))
          )
          Some(s"[引用: 文档 · $title${anchorText(anchor)} · $path]")
      case "html-element" =>
        // B6-A9 seam fix: local-file canvas pages carry identity in source.path
        // (url empty) — mirror file/document instead of url-only. URL pages keep
        // the original behavior; only both-empty is unresolvable (fail-open).
        val url = sanitize(src.downField("url").as[String].getOrElse(""))
        val path = sanitize(src.downField("path").as[String].getOrElse(""))
        val loc = if url.nonEmpty then url else path
        if loc.isEmpty then None
        else
          val title = cleanTitle(
            src.downField("title").as[String]
              .orElse(src.downField("fileName").as[String])
              .getOrElse(if url.nonEmpty then url else fileNameOf(path))
          )
          Some(s"[引用: 页面元素 · $title${anchorText(anchor)} · $loc]")
      // #290 A2A (addendum §3.2): friend message forward - content-bearing
      // ref (unlike the pointer refs above): content.fullText rides the
      // payload (<=4000 chars, capped client-side; re-capped here) and is
      // injected verbatim so the agent sees the forwarded message text.
      // Pinned text-layer format (agent-side experience unchanged from the
      // legacy arch §8 text-prefix form):
      //   [引用 · 好友消息 | 来自 {friendName}({friendNeblinkId}) | {日期}]
      //   {fullText}
      case "friend-message" =>
        val name = sanitize(src.downField("friendName").as[String].getOrElse(""))
        val nl = sanitize(src.downField("friendNeblinkId").as[String].getOrElse(""))
        val date = sanitize(ref.hcursor.downField("meta").downField("date").as[String].getOrElse(""))
        // Body: keep newlines (legitimate message content) but strip other
        // control chars - same anti-forgery rationale as sanitize().
        val body = c.downField("content").downField("fullText").as[String].getOrElse("")
          .filter(ch => ch >= ' ' || ch == '\n').take(4000)
        if body.isEmpty then None
        else
          val datePart = if date.nonEmpty then s" | $date" else ""
          Some(s"[引用 · 好友消息 | 来自 $name($nl)$datePart]\n$body")
      case _ => None // "task" → return flow (processTaskReturns); unknown → skip

  /** Anchor summary " · p.3–4" / " · L12–45" / " · Sheet1!A1:D10" / " · <p>"
    * — empty for kind=none. Mirrors the frontend pageBadge rules (reference.js)
    * so both layers render the same badge. En dash (U+2013) matches the spec. */
  def anchorText(anchor: io.circe.ACursor): String =
    val kind = anchor.downField("kind").as[String].getOrElse("none")
    kind match
      case "page" =>
        anchor.downField("pageStart").as[Long].toOption match
          case Some(s) =>
            val end = anchor.downField("pageEnd").as[Long].toOption
            val suffix = end.filter(_ != s).map(e => s"–$e").getOrElse("")
            s" · p.$s$suffix"
          case None => ""
      case "range" =>
        anchor.downField("lineStart").as[Long].toOption match
          case Some(s) =>
            val end = anchor.downField("lineEnd").as[Long].toOption
            val suffix = end.filter(_ != s).map(e => s"–$e").getOrElse("")
            s" · L$s$suffix"
          case None => ""
      case "cell" =>
        val sheet = sanitize(anchor.downField("sheet").as[String].getOrElse(""))
        val rng = sanitize(anchor.downField("cellRange").as[String].getOrElse(""))
        if sheet.nonEmpty then s" · $sheet!$rng" else ""
      case "element" =>
        anchor.downField("selector").as[String].toOption
          .flatMap(tagOf)
          .map(t => s" · <$t>").getOrElse("")
      case _ => ""

  /** First tag of a CSS selector path: "html>body>div.c>p:nth-of-type(2)" → "p". */
  def tagOf(selector: String): Option[String] =
    val last = selector.split('>').lastOption.getOrElse(selector).trim
    val tag = last.takeWhile(ch => ch.isLetterOrDigit || ch == '-')
    Option(tag).filter(_.nonEmpty)

  private def fileNameOf(path: String): String =
    val clean = path.replace('\\', '/')
    clean.split('/').lastOption.getOrElse(path)

end RefResolver
