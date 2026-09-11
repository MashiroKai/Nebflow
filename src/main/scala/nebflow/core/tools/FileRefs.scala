package nebflow.core.tools

import io.circe.Json
import io.circe.syntax.*

import java.nio.file.{Files, Path, Paths}

/**
 * Shared policy for local file references inside HTML that a tool hands to a
 * browser context (Card → chat iframe, Pop → Canvas iframe).
 *
 * 2026-09-11 (toolfail batch): both tools used to drop an unservable local
 * reference silently — the raw value stayed in the markup, the sandboxed frame
 * resolved it against about:srcdoc (or against the app origin), the fetch
 * 404'd, and the user saw an empty box with no explanation. The carderr batch
 * (merge 52e2f58f) pinned the mechanism on the Card leg; this module is that
 * mechanism lifted to one place so the Pop leg reuses the same verdicts, the
 * same warning object and the same counters instead of growing a second copy.
 *
 * What is shared: the file-level probe (`probeFile`), the failure enum, the
 * app-route exemption (`applyAppRouteExemption`) and the JSON shapes.
 * What is per-tool: only the thin wrapper that owns its own resolution policy
 * (a Card has no containing directory, a Pop'd HTML file does; Card proxies,
 * Pop inlines). Neither wrapper re-implements the probe.
 */
private[tools] object FileRefs:

  /** Max file size for HTTP-served files (200 MB) — the documented
    * `/api/nf-file` proxy limit. */
  val MaxFileSize: Long = 200L * 1024 * 1024

  /**
   * Allowed extensions for /api/nf-file proxy — prevents reading arbitrary
   * files via src=.
   *
   * 2026-09-05 恢复批对齐（以现行端点为准）：与 WebSocketRoutes.NfFileAllowedExt
   * （2026-09-03 Canvas interactive-HTML fix 版）逐项一致——删除了端点已不收的
   * avi/eot/wasm/obj/stl/gltf/glb（避免生成必然 400 的死链），补齐端点已放行的
   * docx/xlsx/xlsm/pptx/epub（此前 src 引用不会被转成代理 URL）。若端点白名单
   * 再演进，本表须同步（端点是权威，本表是前置过滤）。
   */
  val AllowedExtensions: Set[String] = Set(
    // images
    "png",
    "jpg",
    "jpeg",
    "gif",
    "svg",
    "webp",
    "ico",
    "bmp",
    "avif",
    "tiff",
    "tif",
    // video
    "mp4",
    "webm",
    "ogg",
    "ogv",
    "mov",
    // audio
    "mp3",
    "wav",
    "oga",
    "flac",
    "aac",
    "m4a",
    // fonts
    "woff",
    "woff2",
    "ttf",
    "otf",
    // documents
    "pdf",
    "docx",
    "xlsx",
    "xlsm",
    "pptx",
    "epub",
    // web assets (scripts, styles, data)
    "js",
    "mjs",
    "css",
    "json"
  )

  /**
   * Why a local-looking file reference could not be turned into a servable URL.
   *
   * 2026-09-11 (carderr batch — author report 11:57): the Card tool used to
   * drop such references silently — the raw value stayed in the HTML, the
   * sandboxed iframe resolved it against about:srcdoc → 404 → an invisible
   * blank box, and the tool result said nothing at all. The author hit
   * exactly that with `<img src="~/projects/gamma-telescope/reports/…svg">`
   * (empirical chain: `.nebflow/evidence/20260911_carderr-impl/`). Every
   * rejection is now reported in the tool result under `warnings`.
   *
   * NOTE — `out-of-proxy-root` is deliberately NOT a member of this enum:
   * GET /api/nf-file (WebSocketRoutes.scala:4848-4868) enforces token +
   * extension whitelist only and confines no root, so these tools must not
   * invent a root the endpoint does not have.
   */
  enum FileRefFailure(val code: String, val what: String):
    /** 不存在 */
    case NotFound extends FileRefFailure("not-found", "the file does not exist")
    /** 不可解析 */
    case Unresolvable
        extends FileRefFailure("unresolvable", "the reference could not be resolved to a filesystem path")
    /** 扩展名不在白名单 */
    case ExtensionNotAllowed
        extends FileRefFailure("extension-not-allowed", "/api/nf-file does not serve this extension")
    /** 超过大小上限 */
    case SizeExceeded extends FileRefFailure("size-exceeded", "the file is larger than the proxy size limit")
    /** 非常规文件 */
    case NotRegularFile extends FileRefFailure("not-regular-file", "the path is not a regular file")
    /** 其它 */
    case Other extends FileRefFailure("other", "probing the file failed")

  /** One rejected reference, as reported in the tool result + payload. */
  case class RejectedRef(
      value: String,
      resolved: Option[String],
      failure: FileRefFailure,
      detail: String
  )

  /** What to do with one reference value. */
  enum RefDecision:
    /** rewrite the value to this URL */
    case Proxy(url: String)
    /** not a local file reference at all — nothing to do, nothing to report */
    case Ignore
    /** a failing value that is simultaneously a route the gateway itself serves
      * (see `appRoute`) — neither proxied nor reported, only counted */
    case Exempt(route: String)
    /** unservable — reported in `warnings` */
    case Reject(rejected: RejectedRef)

  /**
   * Reference kinds that are never local disk files: inline data, remote URLs,
   * in-document anchors, script URLs, mail/uuid-ish schemes, and the app's own
   * API surface — `/api/` is excluded so an already-proxied
   * `/api/nf-file?path=…` URL is never re-reported as a broken reference.
   */
  val NonFileRefPrefixes: List[String] = List(
    "data:",
    "http://",
    "https://",
    "//",
    "#",
    "javascript:",
    "mailto:",
    "tel:",
    "blob:",
    "about:",
    "/api/"
  )

  def isLocalFilePath(s: String): Boolean =
    s.nonEmpty && !NonFileRefPrefixes.exists(prefix => s.toLowerCase.startsWith(prefix))

  /** A file extension at the very end of the value (`~`, `/`, or `foo.png` shapes). */
  private val FileExtensionSuffix = """\.[A-Za-z0-9]{1,6}$""".r

  /**
   * A reference "looks like a local file" when it is `~`/`/` anchored or ends
   * in a file extension — the shapes the docs tell agents to use. Bare
   * extension-less strings are ignored: they are not path-shaped enough to
   * warn about (documented boundary, see the evidence file).
   */
  def looksLikeFilePath(s: String): Boolean =
    s.startsWith("~") || s.startsWith("/") || FileExtensionSuffix.findFirstIn(s).isDefined

  def fileExtension(path: String): String =
    path.lastIndexOf('.') match
      case -1 => ""
      case i  => path.substring(i + 1).toLowerCase

  /** Resolve a path string (supports ~ expansion) to a normalized java.nio.file.Path. */
  def resolvePath(s: String): Option[Path] =
    try
      val expanded = if s.startsWith("~") then sys.props("user.home") + s.substring(1) else s
      val p = Paths.get(expanded).normalize()
      if p.toString.nonEmpty then Some(p) else None
    catch case _: Exception => None

  /** Closest existing ancestor of `p` — the single most useful hint when a
    *  reference points at a path root that does not exist (author's case:
    *  `~/projects/…` while the project workspace lives under `~/.nebflow/`). */
  @annotation.tailrec
  def nearestExistingParent(p: Path, hops: Int = 0): Option[Path] =
    val parent = p.getParent
    if parent == null || hops >= 16 then None
    else if Files.exists(parent) then Some(parent)
    else nearestExistingParent(parent, hops + 1)

  def describe(path: Path): String = path.toAbsolutePath.normalize.toString

  def hasTemplatePlaceholder(s: String): Boolean =
    s.contains("${") || s.contains("{{")

  /** `Reject` with the shared `unresolvable` code — used by both wrappers for
    *  "I could not turn this value into a filesystem path". */
  def unresolvable(value: String, detail: String): RefDecision.Reject =
    RefDecision.Reject(RejectedRef(value, None, FileRefFailure.Unresolvable, detail))

  // ── app-route exemption (item 3, 2026-09-11 toolfail batch) ────────────────

  /**
   * Every prefix the gateway serves itself (never from disk through
   * `/api/nf-file`): `WebSocketRoutes.jsRoutes:5218` (`/js/`), `assetsRoutes:5139`
   * (`/assets/`), `uploadsRoutes:5174`, `GET -> Root / "css" / file` (`/css/`),
   * the vendor cases `:719-755`, `agents:782`, `voice-models:805`.
   */
  val AppRoutePrefixes: List[String] =
    List("/js/", "/css/", "/assets/", "/vendor/", "/uploads/", "/voice-models/", "/agents/")

  /** Root-level static files served by the `Root / fileName` case
    *  (`WebSocketRoutes.scala:714`). A card/HTML that references `/logo.svg`
    *  means the app's own asset, not a file at the filesystem root. */
  val AppRouteRootFiles: Set[String] =
    Set("/style.css", "/app.js", "/favicon.svg", "/favicon.ico", "/favicon-16.png", "/favicon-32.png", "/logo.svg")

  /** The gateway route a value addresses, if any. A relative value is read as
    *  web-root-relative (`js/app.js` → `/js/app.js`), which is how a browser
    *  inside a card/Canvas iframe resolves it. */
  def appRoute(value: String): Option[String] =
    val v = if value.startsWith("/") then value else "/" + value
    if v.startsWith("//") || v.contains(":") then None
    else AppRoutePrefixes.find(prefix => v.startsWith(prefix)).orElse(Some(v).filter(AppRouteRootFiles.contains))

  /**
   * Suppress a `Reject` for a value that is also a valid gateway route.
   *
   * 判据 — the exemption only ever fires on a verdict that already failed:
   * a reference under `/js/…` that names a real file on disk is still probed
   * and still proxied. Only the "the local file is missing" verdict for a
   * value that is simultaneously an app route is downgraded to `Exempt`, so no
   * working reference is ever suppressed.
   *
   * 代价 — a genuine miss inside the exempt face is no longer listed in
   * `warnings`. It stays discoverable three ways: the `fileRefs.exempt`
   * counter is non-zero exactly when references were suppressed; the 404 still
   * happens in the browser and the media fallback placeholder
   * (`cardRegistry.js buildMediaFallbackScript`) turns a failed
   * `<img>/<video>/<audio>` into a visible box naming the src; and the raw
   * value is left untouched in the HTML.
   */
  def applyAppRouteExemption(value: String, verdict: RefDecision): RefDecision =
    verdict match
      case reject: RefDecision.Reject => appRoute(value).fold(verdict)(RefDecision.Exempt(_))
      case other                      => other

  /**
   * Probe an already-resolved absolute path — the shared core both tools use.
   *
   * `value` is the verbatim reference (reported back in the warning), `path`
   * the resolved filesystem path. Returns `Proxy(url)` when the value can be
   * served through `/api/nf-file`, `Reject` otherwise.
   */
  def probeFile(value: String, path: Path): RefDecision =
    val ext = fileExtension(value)
    if !AllowedExtensions.contains(ext) then
      RefDecision.Reject(
        RejectedRef(
          value,
          Some(describe(path)),
          FileRefFailure.ExtensionNotAllowed,
          s"'.$ext' is not in the proxied extension whitelist (images / video / audio / fonts / pdf / office / js / css / json)"
        )
      )
    else
      try
        if !Files.exists(path) then
          val hint = nearestExistingParent(path)
            .map(parent => s"; the nearest existing parent directory is ${describe(parent)}")
            .getOrElse("")
          RefDecision.Reject(
            RejectedRef(
              value,
              Some(describe(path)),
              FileRefFailure.NotFound,
              s"no file at ${describe(path)}$hint"
            )
          )
        else if !Files.isRegularFile(path) then
          RefDecision.Reject(
            RejectedRef(
              value,
              Some(describe(path)),
              FileRefFailure.NotRegularFile,
              s"${describe(path)} is a directory or another non-regular file"
            )
          )
        else
          val size = Files.size(path)
          if size > MaxFileSize then
            RefDecision.Reject(
              RejectedRef(
                value,
                Some(describe(path)),
                FileRefFailure.SizeExceeded,
                s"$size bytes exceeds the ${MaxFileSize / (1024 * 1024)}MB proxy limit"
              )
            )
          else
            val encoded = java.net.URLEncoder.encode(path.toString, "UTF-8")
            RefDecision.Proxy(s"/api/nf-file?path=$encoded")
      catch
        case e: Exception =>
          RefDecision.Reject(
            RejectedRef(
              value,
              Some(describe(path)),
              FileRefFailure.Other,
              s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
            )
          )

  // ── warning + counter payload shapes (shared by Card and Pop) ─────────────

  /** Distinct rejected references listed in a tool result; further ones are
    *  counted but not listed, so a pathological document cannot blow up the
    *  result. */
  val MaxListedWarnings = 20

  /** Group identical (value, reason) rejections, preserving first-seen order. */
  def distinctRejections(rejects: List[RejectedRef]): List[(RejectedRef, Int)] =
    val grouped = scala.collection.mutable.LinkedHashMap.empty[(String, String), (RejectedRef, Int)]
    rejects.foreach { rejected =>
      val key = (rejected.value, rejected.failure.code)
      grouped.get(key) match
        case Some((first, count)) => grouped.update(key, (first, count + 1))
        case None                 => grouped.update(key, (rejected, 1))
    }
    grouped.values.toList

  /** The `warnings` array — one object per distinct rejected reference
    *  (原始串 → 解析后路径 → 失败原因 + count). */
  def warningsJson(listed: List[(RejectedRef, Int)]): Json =
    Json.arr(
      listed.map { case (rejected, count) =>
        Json.obj(
          "ref" -> rejected.value.asJson,
          "resolvedPath" -> rejected.resolved.fold(Json.Null: Json)(path => path.asJson),
          "reason" -> rejected.failure.code.asJson,
          "detail" -> rejected.detail.asJson,
          "count" -> count.asJson
        )
      }*
    )

  /** The `fileRefs` counter object. Key order is stable — `proxied`, `failed`,
    *  `omitted` (as shipped by the carderr batch) then the additive `exempt` —
    *  so a reader can diff payload heads across the change. `extra` carries a
    *  tool-specific counter (`deferred` for Pop) after those. */
  def fileRefsJson(proxied: Int, failed: Int, omitted: Int, exempt: Int, extra: List[(String, Int)] = Nil): Json =
    Json.obj(
      (List("proxied" -> proxied, "failed" -> failed, "omitted" -> omitted, "exempt" -> exempt) ++ extra)
        .map((k, v) => k -> v.asJson)*
    )

  /** Marker for tools whose result is free text (Pop): the counters ride on a
    *  single line `fileRefs: {…}` that `summarizeResult` can read back. It can
    *  never collide with Card's payload, where the key appears as
    *  `"fileRefs":{…}` inside one longer line. */
  val CountsMarker = "fileRefs: "

  def countsIn(result: String): Option[Json] =
    result.linesIterator
      .find(_.startsWith(CountsMarker))
      .flatMap(line => io.circe.parser.parse(line.substring(CountsMarker.length)).toOption)

  def failedIn(result: String): Int =
    countsIn(result).flatMap(_.hcursor.get[Int]("failed").toOption).getOrElse(0)

end FileRefs
