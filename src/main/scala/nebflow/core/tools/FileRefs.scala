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

  /**
   * Max file size for HTTP-served files (200 MB) — the documented
   * `/api/nf-file` proxy limit.
   */
  val MaxFileSize: Long = 200L * 1024 * 1024

  /**
   * Allowed extensions for /api/nf-file proxy — prevents reading arbitrary
   * files via src=.
   *
   * 2026-09-05 恢复批对齐（以现行端点为准）：与 NfFilePolicy.NfFileAllowedExt
   * （2026-09-03 Canvas interactive-HTML fix 版）逐项一致——删除了端点已不收的
   * avi/eot/wasm/obj/stl/gltf/glb（避免生成必然 400 的死链），补齐端点已放行的
   * docx/xlsx/xlsm/pptx/epub（此前 src 引用不会被转成代理 URL）。若端点白名单
   * 再演进，本表须同步（端点是权威，本表是前置过滤）。
   *
   * 2026-09-17 nfext 批（作者净增面裁定 (i)，**取代**上段而非静默追加）：
   * 端点侧补入 `doc` / `ppt` / `xls` 三型（理由与安全分层论证见端点侧
   * `NfFileAllowedExt` 的注释块；触发点 = `devattach-verify` 开放项①：「设备面
   * 下载键在、点了必然失败」），本表**同批同步**——否则 Card/Pop 侧对这三个后缀的
   * `src`/`href` 引用不会被转成代理 URL，端点放行而工具面仍教模型一条死链。
   * 两表相等由 `FileRefsWhitelistSpec` 的 A14 焊住，漂移必红。
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
    "doc",
    "docx",
    "xls",
    "xlsx",
    "xlsm",
    "ppt",
    "pptx",
    "epub",
    // web assets (scripts, styles, data)
    "js",
    "mjs",
    "css",
    "json"
  )

  /**
   * Tool-side MIRROR of the endpoint's data-root namespace allowlist
   * (`NfFilePolicy.NfDataRootAllowlist`, consumed by the pure judge
   * `nfCredentialDeny` — the namespace step of `nfVerdictForReal`; the tool's own
   * pre-flight gate calls that same function, never this table).
   *
   * Same job as [[AllowedExtensions]] one level up: the endpoint is
   * authoritative, this table mirrors it so the tool face can tell the model
   * which locations its `/api/nf-file` references can actually be served from
   * — and `FileRefsWhitelistSpec` welds the two by item-for-item equality, so
   * a future change to one side without the other fails there instead of
   * silently teaching the model a path shape that always 403s.
   *
   * 2026-09-16 (img-ticket batch i, #687-A, author ruling): `docs` joined the
   * endpoint list. The author's human-deliverable directory (the `docs` subtree
   * of the data root) was the one location the delivery convention told people
   * to write to AND this judge refused (`credential-path`) — the ruling
   * resolves that contradiction by widening the allowlist by EXACTLY this one
   * entry (it explicitly supersedes the 2026-09-11 "do not widen the
   * credential namespace" ruling). Nothing else moved: no other subtree, no
   * prefix/glob matching, the `head` exact-match semantics unchanged.
   *
   * 🔴 Do not add a second copy of this list anywhere (neither tool may inline
   * its own literal): read this constant. 🔴 Every addition here must cite an
   * author ruling — this table is a permission face, not a rendering knob.
   */
  val DataRootServedNamespaces: List[String] =
    List("projects", "uploads", "plots", "workspace-items", "voice-models", "docs")

  /**
   * [[DataRootServedNamespaces]] rendered the way the tool face prints it (each
   * entry followed by the recursive-glob suffix) — one string, so Card's prose
   * and Pop's prose cannot drift from the endpoint's table. Pop's description
   * is a plain (non-interpolated) literal, so it embeds the same text instead,
   * and a `FileRefsWhitelistSpec` assertion keeps the two welded.
   */
  val DataRootServedNamespacesText: String =
    DataRootServedNamespaces.map(_ + "/**").mkString(", ")

  /**
   * Why a local-looking file reference could not be turned into a servable URL.
   *
   * 2026-09-11 (carderr batch — author report 11:57): the Card tool used to
   * drop such references silently — the raw value stayed in the HTML, the
   * sandboxed iframe resolved it against about:srcdoc → 404 → an invisible
   * blank box, and the tool result said nothing at all. The author hit
   * exactly that with `<img src="~/projects/gamma-telescope/reports/…svg">`
   * (empirical chain: the carderr batch evidence set). Every
   * rejection is now reported in the tool result under `warnings`.
   *
   * NOTE — `out-of-proxy-root` is deliberately NOT a member of this enum:
   * GET /api/nf-file enforces a path-bound ticket + a credential namespace
   * (R1: default-deny + allowlist inside PathUtil.dataRoot and the project
   * `.nebflow`, pattern reject outside) + the extension whitelist. The
   * credential namespace is a DENY list on credential-shaped locations, not a
   * project root: `/tmp/...` and any absolute path outside the protected
   * namespaces stay readable, exactly as CardTool's "you MUST use absolute
   * paths" contract documents. So these tools still must not invent a
   * containment root the endpoint does not have — but they must keep the
   * extension whitelist (the endpoint is authoritative, this table mirrors
   * it).
   *
   * 2026-09-11 (C batch): the `token` leg was replaced by the ticket leg, so
   * a reference is only servable if the render-time caller mints a ticket for
   * the realpath. Tool-side URL strings are UNCHANGED (tickets are injected at
   * render time, never at tool time) — see CardToolFileRefSpec /
   * CardToolScanFaceSpec, which lock those exact strings.
   */
  enum FileRefFailure(val code: String, val what: String):
    /** 不存在 */
    case NotFound extends FileRefFailure("not-found", "the file does not exist")

    /** 不可解析 */
    case Unresolvable extends FileRefFailure("unresolvable", "the reference could not be resolved to a filesystem path")

    /** 扩展名不在白名单 */
    case ExtensionNotAllowed
        extends FileRefFailure("extension-not-allowed", "/api/nf-file does not serve this extension")

    /** 超过大小上限 */
    case SizeExceeded extends FileRefFailure("size-exceeded", "the file is larger than the proxy size limit")

    /** 非常规文件 */
    case NotRegularFile extends FileRefFailure("not-regular-file", "the path is not a regular file")

    /**
     * imgref 批（2026-09-18）：文件在、但**取不到字节**（0 字节 / 读不动 / realpath
     * 解不出）。浏览器同样渲染不出来，所以它跟「文件不存在」是两件事，得分开报。
     */
    case NotReadable extends FileRefFailure("not-readable", "the file is present but its bytes cannot be read")

    /**
     * imgref 批（2026-09-18）：文件在、可读，但 `/api/nf-file` 的**端点判据阶梯**
     * 不会为它铸票（credential namespace / R2 硬链接 inode / realpath 上的扩展名）
     * ⇒ 引用腿必然 401/403，`proxied` 不该为它计数（作者失败②的「计数绿而取回
     * 红」）。判据由端点自己的**同一个**函数给出，见 [[servableByEndpoint]]。
     */
    case NotServable extends FileRefFailure("not-servable", "the /api/nf-file endpoint cannot serve this location")

    /** 其它 */
    case Other extends FileRefFailure("other", "probing the file failed")

  end FileRefFailure

  /** One rejected reference, as reported in the tool result + payload. */
  case class RejectedRef(
    value: String,
    resolved: Option[String],
    failure: FileRefFailure,
    detail: String,
    /**
     * For a [[FileRefFailure.NotServable]] refusal: which layer of the
     * endpoint's ladder refused it (endpoint rework r2, 2026-09-18). Never
     * serialised into `warnings` — it is the tool's own bookkeeping, read by
     * [[inlineMayTakeOver]] so the inline leg can honour the identity layers
     * while not honouring the endpoint's reach layer.
     */
    layer: Option[nebflow.gateway.NfFilePolicy.NfDenyLayer] = None
  )

  /** What to do with one reference value. */
  enum RefDecision:
    /** rewrite the value to this URL */
    case Proxy(url: String)

    /** not a local file reference at all — nothing to do, nothing to report */
    case Ignore

    /**
     * a failing value that is simultaneously a route the gateway itself serves
     * (see `appRoute`) — neither proxied nor reported, only counted
     */
    case Exempt(route: String)

    /** unservable — reported in `warnings` */
    case Reject(rejected: RejectedRef)

  end RefDecision

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
   * A Windows drive-letter root with an EXPLICIT separator (`C:/…`, `C:\…`).
   *
   * winpath 批（2026-09-17）：Windows 实例把
   * `<img src="C:/Users/<user>/Downloads/…svg">` 判成**相对引用**
   * （`reason=unresolvable` + "relative references are never resolved"），同一文件
   * 用 `~/Downloads/…` 引用却内联成功——盘符绝对路径从未进入 probe，用户看到的是
   * 未解析的占位/告警文本。
   *
   * 为什么不直接复用 `nebflow.core.PathUtil.isAbsolute`（`core/paths.scala:21-24`，
   * 面内既有的跨平台判定，`WebSocketRoutes.scala:2584` 为同族修复先例）：它把
   * ① UNC（`\\server\share\…`，网络语义）与 ② 盘符相对形态（`C:x.png`）一并判为
   * 绝对。① 本批明示不扩面；② 是「相对服务器 cwd 解析」——正是
   * `CardTool.decideRef` 拒绝相对引用所要挡住的语义。本面因此只承认**带分隔符**的
   * 盘符形式，UNC 单列一条明确拒绝（见 `CardTool.decideRef`）。
   *
   * 纯字符串判定、**无平台分支**（不读 `os.name`）——所以 macOS / Windows 上判读
   * 一致，这也正是它能在 macOS 上被单测钉死的原因。
   */
  private val WindowsDriveRoot = """^[A-Za-z]:[\\/]""".r

  /** Windows drive-letter absolute path (`C:/…`, `C:\…`). */
  def isWindowsDriveAbsolute(s: String): Boolean = WindowsDriveRoot.findFirstIn(s).isDefined

  /**
   * The backslash UNC form (`\\server\share\…`). Recognized only in order to
   * REFUSE it with an accurate reason — never resolved (network semantics are
   * outside this leg's face). The forward-slash form `//host/path` is left
   * alone: that is `NonFileRefPrefixes`' protocol-relative URL, not a share.
   */
  def isUncPath(s: String): Boolean = s.startsWith("\\\\")

  /**
   * The anchors this leg resolves from: `~` (expanded to the user's home), a
   * POSIX absolute path (`/…`) and a Windows drive-letter absolute path
   * (`C:/…`, `C:\…`). Everything else is a relative reference and the caller
   * refuses it.
   */
  def isAnchoredRefPath(s: String): Boolean =
    s.startsWith("~") || s.startsWith("/") || isWindowsDriveAbsolute(s)

  /**
   * A reference "looks like a local file" when it is anchored (`~`/`/`/drive) or
   * ends in a file extension — the shapes the docs tell agents to use. Bare
   * extension-less strings are ignored: they are not path-shaped enough to
   * warn about (documented boundary, see the evidence file).
   */
  def looksLikeFilePath(s: String): Boolean =
    isAnchoredRefPath(s) || FileExtensionSuffix.findFirstIn(s).isDefined

  def fileExtension(path: String): String =
    path.lastIndexOf('.') match
      case -1 => ""
      case i => path.substring(i + 1).toLowerCase

  /** Resolve a path string (supports ~ expansion) to a normalized java.nio.file.Path. */
  def resolvePath(s: String): Option[Path] =
    try
      val expanded = if s.startsWith("~") then sys.props("user.home") + s.substring(1) else s
      val p = Paths.get(expanded).normalize()
      if p.toString.nonEmpty then Some(p) else None
    catch case _: Exception => None

  /**
   * Closest existing ancestor of `p` — the single most useful hint when a
   *  reference points at a path root that does not exist (author's case:
   *  `~/projects/…` while the project workspace lives under `~/.nebflow/`).
   */
  @annotation.tailrec
  def nearestExistingParent(p: Path, hops: Int = 0): Option[Path] =
    val parent = p.getParent
    if parent == null || hops >= 16 then None
    else if Files.exists(parent) then Some(parent)
    else nearestExistingParent(parent, hops + 1)

  def describe(path: Path): String = path.toAbsolutePath.normalize.toString

  def hasTemplatePlaceholder(s: String): Boolean =
    s.contains("${") || s.contains("{{")

  /**
   * `Reject` with the shared `unresolvable` code — used by both wrappers for
   *  "I could not turn this value into a filesystem path".
   */
  def unresolvable(value: String, detail: String): RefDecision.Reject =
    RefDecision.Reject(RejectedRef(value, None, FileRefFailure.Unresolvable, detail))

  // ── path ↔ URL query-parameter form (imgref batch, 2026-09-18 作者令) ───────
  //
  // 作者实证（2026-09-18，同一改面的两起失败）：路径含空格时 ① `%20` 编码的引用
  // 被按**字面**去找（`/Users/you/My%20Project/…` ⇒ not-found），② 原样空格引用
  // 的工具计数（`proxied`）是绿的、前端 `img` 却仍旧加载失败。
  //
  // 本组三个函数是本仓**唯一**的「路径 ⇄ URL 查询参数」真源，判据两条：
  //   · 产 URL 一侧：空格一律出 `%20`（`URLEncoder` 的 form 口径会把空格写成 `+`，
  //     而 `+` 只在 `application/x-www-form-urlencoded` 语境里才等于空格 —— 路径段
  //     里它是**字面加号**。既有先例 = `RestApiRoutes.encSeg:2314-2317`，注释同法）；
  //   · 解 URL 一侧：先折 **bare `+`**（旧载荷/replay 里 JVM form 编码留下的）再
  //     percent-decode，`%2B`（真加号）不动 —— 与前端唯一的解码纪律
  //     `nfTicket.js decodePathParam` 逐字同法（该件自称 “the ONE statement of this
  //     discipline in the repo”，本组把它在服务端/工具侧补齐，两侧成对一致）。
  //
  // 🔴 解码不参与任何权限判定：解码只挑「去问哪个字符串」，判据始终由
  // `probeFile`（扩展名/存在性/大小）与端点 `nfFileVerdict`（realpath + credential
  // namespace + inode）施加 —— 见 [[servableByEndpoint]] 的复用声明。

  /**
   * The ONE path → URL query-parameter encoder (space → `%20`). Delegates to
   *  [[nebflow.core.PathParamCodec]] — the shared single source the gateway legs
   *  read too, so the two layers cannot drift.
   */
  def encodePathParam(path: String): String = nebflow.core.PathParamCodec.encode(path)

  /**
   * The ONE URL query-parameter → path decoder (fold bare `+`, then
   *  percent-decode). `%2B` survives as a literal plus.
   */
  def decodePathParam(encoded: String): Option[String] = nebflow.core.PathParamCodec.decode(encoded)

  /**
   * Filesystem candidates named by ONE reference value, least-transformed first:
   * the raw value, then its percent-decoded form, then its bare-`+`-folded form.
   *
   * 作者失败①的修法即此：`<link href="/Users/you/My%20Project/…/x.css">` 的
   * **原样**串不是磁盘上的路径，解码形态才是。顺序是判据的一部分 —— 原样先试，
   * 只有原样**没有命中**才会走到变形形态，因此一个真的含 `+` 或 `%` 的文件名
   * 永远不会被变形形态顶掉。
   *
   * 变形只在串里**确实带** `%` 或 `+` 时产生（否则返回单元素表，零开销、零行为
   * 变化）。
   */
  def pathFormCandidates(value: String): List[String] = nebflow.core.PathParamCodec.candidates(value)

  /** One reference taken through its candidate forms (see [[pathFormCandidates]]). */
  final case class CandidateHit(
    /** the form that was actually probed (== the raw value when the raw form hit) */
    form: String,
    /** 0 = the raw value; >0 = a decoded form */
    formIndex: Int,
    /** the resolved path, when that form resolved to one */
    path: Option[Path],
    /** the verdict produced for `form` */
    decision: RefDecision,
    /**
     * present only when a decoded form hit — the disclosure the author's order
     * requires ("说明用了哪一形态")
     */
    note: Option[String]
  )

  /** The note that must accompany a decoded-form hit. Empty when the raw form hit. */
  def formNote(value: String, form: String): String = nebflow.core.PathParamCodec.formNote(value, form)

  /**
   * Resolve ONE reference through its candidate forms, least-transformed first,
   * returning the first form that yields anything other than "not found".
   *
   * 判据（作者失败①的修法）：**先试原样**；原样没有命中（`resolve` 解不出路径，或
   * `probeFile` 报 `not-found`）才试解码形态。命中即用，并把用了哪一形态写进
   * [[CandidateHit.note]]（回包/告警面必须显式说明）。
   *
   * 安全面：本函数只挑「去问哪个字符串」，它自己**不做任何准入**——每个候选形态都
   * 原样过 `probeFile`（扩展名/存在性/大小/真可读/可服务），真准入仍由端点
   * `nfFileVerdict`（realpath + credential namespace + inode）施加。变形形态因此
   * 不可能造出「原串判不住、变形后判得住」的穿透：判据作用在 realpath 上，与
   * 字符串形态无关。
   */
  def resolveCandidates(
    value: String,
    resolve: String => Option[Path],
    whenUnresolvable: String => RefDecision
  ): CandidateHit =
    val candidates = pathFormCandidates(value)
    var index = 0
    var hit: Option[CandidateHit] = None
    while hit.isEmpty && index < candidates.length do
      val form = candidates(index)
      resolve(form) match
        case None => index += 1
        case Some(p) =>
          val decision = probeFile(value, p)
          val notFound = decision match
            case RefDecision.Reject(rejected) => rejected.failure == FileRefFailure.NotFound
            case _ => false
          if notFound && index < candidates.length - 1 then index += 1
          else
            hit = Some(
              CandidateHit(
                form,
                index,
                Some(p),
                decision,
                Option(formNote(value, form)).filter(_.nonEmpty)
              )
            )
      end match
    end while
    hit.getOrElse(
      // Nothing resolved at all: report the raw form's own verdict (byte-identical
      // to the shipped behaviour for a value that never had a decodable sibling).
      CandidateHit(value, 0, None, whenUnresolvable(value), None)
    )
  end resolveCandidates

  // ── the tool-side "can the endpoint actually serve this?" gate (②) ─────────
  //
  // 任务书 7(a)：`proxied` 不得再由「URL 字符串已发出」满足。判据四段：
  //   ① 解析成功（`probeFile` 既有：存在 + regular + 扩展名 + ≤200MB）；
  //   ② servability = **与端点同一份判据** —— 直接调**端点完整阶梯**的同一函数
  //      `NfFilePolicy.nfVerdictForReal`（= `nfFileVerdict` 在 `toRealPath` 之后的
  //      全部步骤：credential namespace → R2 (dev,ino) 硬链接 → realpath 上的扩展名）
  //      + 同一份 `NfPathPolicy.memoized()`（同一 JVM、同一 data root、同一
  //      workspace），**零复制、零旁路**；判据抛异常时 fail-closed（当成不可服务），
  //      绝不 fail-open；
  //   ③ 真可读（HEAD 等价）：`isRegularFile` + `size` + 实读首字节（0 字节文件
  //      在浏览器里同样渲染不出来，与「文件在」是两件事）；
  //   ④ URL 往返校验：发出去的 URL 按**同一解码纪律**解回 ⇒ 必须与已解析路径逐字
  //      相等（形态漂移会让端点去找另一个路径 —— 这正是作者失败②）。
  //
  // 🔴 返工 r1（2026-09-18 复核位缺陷 B）：本闸此前只调端点的**纯 credential**
  // 判据 `nfCredentialDeny`，漏掉阶梯后面两步（R2 inode 与 realpath 扩展名）⇒ 硬
  // 链接到 credential 的真件、以及 realpath 扩展名不被服务的符号链接，都被计数成
  // 「绿」而端点必然拒（真取回腿 401）——「计数绿而取回红」在本批修好的树上仍可
  // 发生。现在两侧调同一个函数，缺一条腿都不可能：判据的形状只有一份。
  //
  // 依赖方向说明：`core` 引用 `gateway` 在本树有先例（`core/processor/
  // TaskStuckWatcher.scala:8` 引 `gateway.WsHub`；`core/scheduler/
  // ScheduledTaskActor.scala:10` 引 `gateway.SessionStore`），且任务书 7(a) 明文要求
  // 「与端点同一份判据（🔴 复用，禁复制）」。复制一份白名单/判据才是本批明令禁止
  // 的旁路，所以这里调同一个函数而不镜像它。

  // ── the two legs ask different questions (返工 r2, 2026-09-18 · 复核位 F1) ────
  //
  // 上闸原来坐落在 `probeFile`（**引用决策**）里，于是**内联腿**（把字节嵌成
  // `data:` URI、根本不问端点要东西）也被它管住 —— 一道「端点不可达」的判据被当成
  // 「不可内联」的判据用，把**基线本来能内联渲染**的本地件（数据根顶层的可读小图、
  // 项目 `.nebflow` 非 `evidence*` 子树……）变成 `failed` + 告警。那是权限/能力面的
  // 收紧，既非本批授权面，也与作者令「让本地件成功率高一点」反向。
  //
  // 现在：**URL 腿**（浏览器凭票据去 `/api/nf-file` 取）过端点**整条**阶梯；**内联腿**
  // 只认「文件是不是凭据」那两层（判据与理由见 [[inlineMayTakeOver]]）。两腿的差异
  // 是有意的，逐条落在 spec 里（`CardToolPathFormSpec` / `FileRefsServabilityScopeSpec`）。

  /**
   * `None` = the endpoint's own judge would serve this real path; `Some(reason,
   * message)` = it would refuse, in the endpoint's own words. Fail-closed: a
   * judge that cannot be consulted counts as a refusal, never as permission.
   *
   * Projection of [[servableByEndpointLayered]] (same call, same policy) — the
   * full ladder, i.e. the question the **URL leg** must pass. The policy is
   * `NfPathPolicy.current()`: the roots in force NOW, never a snapshot taken at
   * the first call of this JVM (that snapshot made the verdict a function of
   * process history — verifier F1 ②).
   */
  def servableByEndpoint(real: Path): Option[(String, String)] =
    servableByEndpointLayered(real).map((_, reason, message) => (reason, message))

  /**
   * [[servableByEndpoint]] with the refusing layer — see
   * [[nebflow.gateway.NfFilePolicy.NfDenyLayer]]. The URL leg reads the
   * two-tuple above; the one caller that must honour some layers and not others
   * (the inline leg) reads this.
   */
  def servableByEndpointLayered(
    real: Path
  ): Option[(nebflow.gateway.NfFilePolicy.NfDenyLayer, String, String)] =
    try
      nebflow.gateway.NfFilePolicy
        .nfVerdictForRealLayer(real, nebflow.gateway.NfFilePolicy.NfPathPolicy.current())
        .map((layer, denied) => (layer, denied.reason, denied.message))
    catch
      case e: Throwable =>
        Some(
          (
            nebflow.gateway.NfFilePolicy.NfDenyLayer.Namespace,
            "servability-judge-unavailable",
            s"the servability judge could not be consulted (${e.getClass.getSimpleName}) — " +
              "the reference is treated as unservable rather than assumed servable"
          )
        )

  /**
   * May the INLINE leg (`data:` embed) take over a refusal the endpoint made?
   *
   * 判据（一句话）：**只接管「端点可达性」那一层，不接管「文件身份」那几层。**
   *   · `Namespace` = 端点只从数据根 / 项目 `.nebflow` 的某些子树往外服务 —— 内联腿
   *     从不问端点要字节（它自己在工具侧把字节读出来嵌进载荷），这一层对它不成立；
   *   · `Credential` / `CredentialInode` = 这个文件**本身**是凭据（凭据形态 / 指向
   *     凭据 inode 的硬链接）—— 内联恰恰在把字节复制进载荷，正是这几层要拦的事，
   *     **一律不接管**；
   *   · `FileType` = 端点按 **real path** 的扩展名服务（符号链接借不到名字）—— 内联腿
   *     自己按图片扩展名判（`EmbeddableImageExtensions`），同样不接管。
   *
   * ⇒ `true` 当且仅当：拒绝来自 `Namespace` 层 **且** 该文件的 inode 不是凭据 inode。
   *
   * 🔴 为什么还要**独立复问 inode 层**：端点阶梯在 `Namespace` 层就短路，会掩盖后面
   * 的 inode 层（放在数据根顶层的、指向 `auth.json` 的硬链接，第一层报的就是
   * `Namespace`）。故接管前单独问一次 inode —— 读的是**端点策略自己的字段**
   * （`credentialInodes` + 同一个 `inodeKey`），零白名单复制、零旁路；
   * 数据根的 `secrets/` 子树下的每个文件都在该快照里，所以那条路同样进不来。
   *
   * 判据不可得（策略读不到 / realpath 解不出）⇒ `false`（fail-closed：宁可不接管）。
   */
  def inlineMayTakeOver(path: Path, rejected: RejectedRef): Boolean =
    rejected.failure == FileRefFailure.NotServable &&
      rejected.layer.contains(nebflow.gateway.NfFilePolicy.NfDenyLayer.Namespace) &&
      credentialInodeClean(path)

  /**
   * `true` = this file's inode is NOT one of the endpoint policy's credential
   * inodes (R2). Fail-closed: anything that cannot be established answers `false`,
   * i.e. "do not take the refusal over". Asks the endpoint's own helper
   * (`NfFilePolicy.nfCredentialInode`) with the endpoint's own policy — no
   * second inode scan, no copied snapshot.
   */
  def credentialInodeClean(path: Path): Boolean =
    try
      val policy = nebflow.gateway.NfFilePolicy.NfPathPolicy.current()
      !nebflow.gateway.NfFilePolicy.nfCredentialInode(path.toRealPath(), policy)
    catch case _: Throwable => false

  /**
   * The executable fix for one of the endpoint's own refusal reasons — a warning
   * that only says "no" costs the agent a second round trip; one that says what
   * to do instead does not. Keyed by the endpoint's `reason` (never by prose).
   */
  def servabilityHint(reason: String): String =
    reason match
      case "credential-hardlink" =>
        "copy the file's BYTES to a new file (a hard link shares the credential file's inode, " +
          "so /api/nf-file refuses it however the link is named)"
      case "file-type" =>
        "give the file a real extension /api/nf-file serves — the extension is read from the " +
          "REAL path, so a symlink cannot lend it its name"
      case _ =>
        "move or copy the file into a location /api/nf-file serves " +
          s"(data root: ${DataRootServedNamespacesText}; project .nebflow: evidence*/**) " +
          "and reference it from there"

  /**
   * HEAD-equivalent readability: regular file, non-zero size, first byte really
   * readable. `None` = readable, `Some(reason)` = not.
   */
  def readableFirstByte(path: Path): Option[String] =
    try
      if !Files.isRegularFile(path) then Some("the path is not a regular file")
      else
        val size = Files.size(path)
        if size == 0 then Some("the file is 0 bytes — a browser cannot render it")
        else
          val in = Files.newInputStream(path)
          try
            val b = in.read()
            if b < 0 then Some(s"the first byte of a ${size}-byte file could not be read")
            else None
          finally in.close()
    catch
      case e: Exception =>
        Some(s"the file could not be read (${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")})")

  // ── app-route exemption (item 3, 2026-09-11 toolfail batch) ────────────────

  /**
   * Every prefix the gateway serves itself (never from disk through
   * `/api/nf-file`): `StaticRoutes.jsRoutes:5218` (`/js/`), `assetsRoutes:5139`
   * (`/assets/`), `uploadsRoutes:5174`, `GET -> Root / "css" / file` (`/css/`),
   * the vendor cases `:719-755`, `agents:782`, `voice-models:805`.
   */
  val AppRoutePrefixes: List[String] =
    List("/js/", "/css/", "/assets/", "/vendor/", "/uploads/", "/voice-models/", "/agents/")

  /**
   * Root-level static files served by the `Root / fileName` case
   *  (`WebSocketRoutes.scala:714`). A card/HTML that references `/favicon-32.png`
   *  means the app's own asset, not a file at the filesystem root.
   */
  val AppRouteRootFiles: Set[String] =
    Set(
      "/style.css",
      "/app.js",
      "/favicon.ico",
      "/favicon-16.png",
      "/favicon-32.png",
      "/favicon-180.png",
      "/favicon-192.png",
      "/favicon-512.png"
    )

  /**
   * The gateway route a value addresses, if any. A relative value is read as
   *  web-root-relative (`js/app.js` → `/js/app.js`), which is how a browser
   *  inside a card/Canvas iframe resolves it.
   */
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
      case other => other

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
            // ── the "is this actually retrievable?" gate (imgref batch ②) ────
            // A URL string is not evidence that a browser can fetch the bytes.
            // Each of the four checks below is a way the shipped code counted a
            // reference as proxied while the render leg could not retrieve it.
            //
            // 返工 r2：这四段判据是**URL 腿**的判据，所以拒的时候连**拒在哪一层**
            // 一起记下来（[[RejectedRef.layer]]）—— 内联腿不在这一层让步，它按
            // [[inlineMayTakeOver]] 自己决定接不接管这条拒绝。
            val realTry =
              try Some(path.toRealPath())
              catch case e: Exception => None
            realTry match
              case None =>
                RefDecision.Reject(
                  RejectedRef(
                    value,
                    Some(describe(path)),
                    FileRefFailure.NotReadable,
                    s"${describe(path)} exists but its real path could not be resolved — " +
                      "the endpoint resolves the real path before it serves anything, so the reference " +
                      "would 404; re-create the file (a dangling symlink or a permission-denied parent " +
                      "directory is the usual cause)"
                  )
                )
              case Some(real) =>
                servableByEndpointLayered(real) match
                  case Some((layer, reason, message)) =>
                    RefDecision.Reject(
                      RejectedRef(
                        value,
                        Some(describe(path)),
                        FileRefFailure.NotServable,
                        s"$reason: $message — ${servabilityHint(reason)}",
                        layer = Some(layer)
                      )
                    )
                  case None =>
                    readableFirstByte(path) match
                      case Some(reason) =>
                        RefDecision.Reject(
                          RejectedRef(
                            value,
                            Some(describe(path)),
                            FileRefFailure.NotReadable,
                            s"$reason — the reference would render as a broken element; " +
                              "write a file with real content (or drop the reference)"
                          )
                        )
                      case None =>
                        val url = s"/api/nf-file?path=${encodePathParam(path.toString)}"
                        val param = url.substring(url.indexOf("path=") + "path=".length)
                        decodePathParam(param) match
                          case Some(back) if back == path.toString => RefDecision.Proxy(url)
                          case _ =>
                            RefDecision.Reject(
                              RejectedRef(
                                value,
                                Some(describe(path)),
                                FileRefFailure.Other,
                                s"the proxied URL for ${describe(path)} does not decode back to the resolved " +
                                  "path (encoder/decoder drift) — the endpoint would look for a different path"
                              )
                            )
            end match
          end if
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
    end if
  end probeFile

  // ── inline policy (shared by Card and Pop) ────────────────────────────────
  //
  // 2026-09-16 (imgfix batch): the bytes of a small local image are embedded
  // INTO the payload (a `data:` URI) instead of being referenced by an
  // `/api/nf-file?path=…` URL that the browser must fetch with a ticket.
  //
  // Why the reference leg is not enough (author case, both faces):
  //   - a ticket is minted only for paths the read endpoint's namespace judge
  //     allows (`NfFilePolicy.nfCredentialDeny`): the data root served ONLY
  //     `projects/uploads/plots/workspace-items/voice-models`, so a screenshot
  //     under `<dataRoot>/docs/**` was refused (`credential-path`) → no ticket
  //     → the credential-free URL answered 401 → placeholder / error panel.
  //     (2026-09-16 img-ticket batch i, #687-A: the author ruled `docs` INTO
  //     that allowlist, so that location mints and reads now — see
  //     [[DataRootServedNamespaces]]. The rarer shape this bullet describes is
  //     now a file in a location that is still refused: `secrets/`, `logs/`,
  //     `sessions/`, a non-`evidence*` project `.nebflow` subtree, `~/.ssh`-like
  //     trees.)
  //   - the URL carries its path percent-encoded by the TOOL (form encoding,
  //     `+` for space) while the render-time candidate scanner decoded with
  //     `decodeURIComponent` (no `+` folding) → the mint asked for a path that
  //     does not exist → no ticket → 401 again.
  // Embedding the bytes removes the request from the critical path for images
  // altogether, which is also what makes a card render on replay (the data URI
  // is persisted with the markup).
  //
  // 安全原则（2026-09-16 重述，判据不变、理由更新）：**权限面不得为绿灯让步**
  // —— 既不能把「让这张图渲染出来」当成放宽读取端点命名空间的理由，也不能在
  // 工具侧私开旁路（自造 URL、绕票据、复制一份白名单）。2026-09-11 的「禁放开
  // 命名空间」是**作者裁定**，其效力不因渲染缺陷而消解；2026-09-16 作者**同一
  // 权限面**上作出新裁定：将白名单**恰好扩一项 `docs`**（#687-A，明示取代 0911
  // 裁定）。两者共同的口径是——**改权限面只能出自作者裁定、且逐项明文**；工具
  // 侧修复永远不能自行扩面。本模块因此只读 [[DataRootServedNamespaces]]，并由
  // `FileRefsWhitelistSpec` 与端点表逐项焊死。
  //
  // The rule is ONE definition for both tools — Card's card-iframe face (every
  // resource face: `src=`, `srcset` candidates, CSS `url(...)`) and Pop's HTML
  // `<img src>` face plus Pop's directly-opened-image face.

  /**
   * Max single image size to embed as a base64 data URI (5 MB). Larger images
   * keep the `/api/nf-file` reference and are reported as `deferred`/`proxied`.
   *
   * This is the PER-ITEM gate and it is unchanged by the cumulative budget
   * below: `isInlineImage` still answers exactly what it used to.
   */
  val MaxEmbedImageSize: Long = 5L * 1024 * 1024

  /**
   * Cumulative inline budget for ONE tool call (40,000 characters of
   * `data:` URI text), 2026-09-16 img-ticket batch i / #687-C (author ruling).
   *
   * 判据（逐字）：**单次工具调用内所有内联项合计 ≤ 40,000 字符**。单图 5MB
   * 上限（[[MaxEmbedImageSize]]）不变，两道闸是「与」关系：一项被内联，当且
   * 仅当它自身过单图闸 **且** 其 `data:` URI 字数不超过**当时剩余**的累计预算。
   *
   * 确定性处置（可复算，见 [[embedImage]] / [[InlineBudget]]）：内联项按**扫描
   * 顺序**（Card = 文档顺序；Pop = `<img src>` 出现的顺序；直开图片腿 = 唯一一
   * 项）逐项累计，**先到先占**；某项当前剩余额度装不下 ⇒ 该项**不内联、零扣费、
   * 不阻断后续**（回落既有引用腿 `/api/nf-file`，Pop 计入 `deferred`、Card 计入
   * `deferred`），后续较小的项仍可占用剩余额度。同一份输入 ⇒ 同一组内联判定
   * （无随机、无 I/O 顺序依赖）：费用 = `data:` URI 的**精确字符数** =
   * `"data:" + mime + ";base64,"` 前缀长度 + `4 × ceil(size/3)`（JDK Base64 不折
   * 行、不省略补位），见 [[dataUriChars]]。
   *
   * 用户可见语义：超限项在卡片/画布里显示为 `/api/nf-file` 引用（渲染时铸票），
   * 而非内嵌字节 —— 于是它与所有引用腿一样依赖票据，路径不在可服务命名空间内
   * 时会显示占位/错误而非图片；重放时由既有铸票机制重新取票（`data:` 内嵌项则
   * 随标记持久化、无需请求）。
   */
  val MaxInlinePayloadChars: Int = 40000

  /** Why an image that passed the per-image gate was not embedded. */
  enum InlineSkip:
    /**
     * Not an embeddable image at all: extension outside
     * [[EmbeddableImageExtensions]] or larger than [[MaxEmbedImageSize]].
     */
    case NotEmbeddable

    /**
     * Inside the per-image gate, but the cumulative budget
     * ([[MaxInlinePayloadChars]]) could not cover its `data:` URI.
     */
    case OverBudget

    /**
     * Inside the per-image gate and covered by the budget, but the bytes could
     * not be read — the caller decides (Card falls back to the proxy URL, Pop
     * reports the same `other` rejection it used to).
     */
    case Unreadable(detail: String)
  end InlineSkip

  /** Image extensions that can be embedded as data URIs. */
  val EmbeddableImageExtensions: Set[String] = Set("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")

  /** Map an image extension to its MIME type (the inline set only). */
  def mimeFromExt(ext: String): String = ext.toLowerCase match
    case "png" => "image/png"
    case "jpg" | "jpeg" => "image/jpeg"
    case "gif" => "image/gif"
    case "webp" => "image/webp"
    case "svg" => "image/svg+xml"
    case "bmp" => "image/bmp"
    case _ => "application/octet-stream"

  /**
   * Pure size/extension verdict for the inline policy (no I/O side effect
   * beyond a `stat`; an unreadable path answers `false`).
   */
  def isInlineImage(path: Path): Boolean =
    val ext = fileExtension(path.toString)
    EmbeddableImageExtensions.contains(ext) && {
      try Files.size(path) <= MaxEmbedImageSize
      catch case _: Exception => false
    }

  /**
   * `Right("data:<mime>;base64,…")` for an inlineable image, `Left(why)` when
   * the bytes could not be read (the caller decides: Card falls back to the
   * proxy URL, Pop reports the same `other` rejection it used to).
   *
   * 🔴 This is the BYTES-level step only — it does NOT consult the per-image
   * gate or the cumulative budget. Callers go through [[embedImage]], which
   * consults both; this entry point stays for the specs that pin the encoding
   * itself.
   */
  def readAsDataUri(path: Path): Either[String, String] =
    try
      val ext = fileExtension(path.toString)
      val b64 = java.util.Base64.getEncoder.encodeToString(Files.readAllBytes(path))
      Right(s"data:${mimeFromExt(ext)};base64,$b64")
    catch case e: Exception => Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")

  /**
   * The EXACT character count of the `data:` URI [[readAsDataUri]] produces for
   * a file of `size` bytes with extension `ext`: the `"data:<mime>;base64,"`
   * prefix plus the JDK Base64 output length `4 × ceil(size/3)` (the encoder
   * neither wraps lines nor omits padding, so this is an identity, not an
   * estimate). Pure — that is what makes the budget rule recomputable by hand
   * from `(ordered paths, sizes)` alone.
   */
  def dataUriChars(size: Long, ext: String): Int =
    val prefix = s"data:${mimeFromExt(ext)};base64,"
    if size < 0L then Int.MaxValue
    else
      val b64 = 4L * ((size + 2L) / 3L)
      val total = prefix.length.toLong + b64
      if total > Int.MaxValue.toLong then Int.MaxValue else total.toInt

  /**
   * Order-sensitive accumulator for [[MaxInlinePayloadChars]] — ONE instance
   * per tool call (never a shared/static one: two calls must not spend each
   * other's budget).
   *
   * Semantics (the deterministic rule, see [[MaxInlinePayloadChars]]): an item
   * is charged its exact `data:` URI length when the REMAINING balance covers
   * it, and charged NOTHING when it does not. Refusing is therefore side-effect
   * free: a later, smaller item still fits ("first come, first served", not
   * "the first big item ends the pass"). [[release]] exists for the one path
   * that charges before it can fail (a read error) so the accounting stays
   * exact.
   */
  final class InlineBudget(val maxChars: Int = MaxInlinePayloadChars):
    private var used: Int = 0

    /** Characters already spent by embedded inlines. */
    def usedChars: Int = used

    /**
     * What is left — never negative (an item that cannot fit is refused
     * without charging).
     */
    def remainingChars: Int = math.max(0, maxChars - used)

    /** Charge `chars` when it fits, else refuse and leave the budget untouched. */
    def tryCharge(chars: Int): Boolean =
      if chars < 0 then false
      else if chars <= remainingChars then
        used += chars
        true
      else false

    /** Give back a charge that was never materialised (read failure only). */
    def release(chars: Int): Unit =
      used = math.max(0, used - math.max(0, chars))

  end InlineBudget

  /**
   * The ONE inline decision both tools use (2026-09-16 img-ticket batch i):
   * `Right(dataUri)` = embed these bytes; `Left(skip)` = leave the reference
   * leg alone.
   *
   * Order of the two gates is deliberate and user-visible: the PER-IMAGE gate
   * ([[isInlineImage]]) is asked first, so an oversize/wrong-format image is
   * reported as [[InlineSkip.NotEmbeddable]] and consumes no budget (its bytes
   * could not be embedded at any balance), while an image that is merely too
   * big for the REMAINING budget answers [[InlineSkip.OverBudget]]. The two
   * reasons are distinguishable in the counters, so a reader can tell "this
   * file can never be inline" from "this call already spent its budget".
   */
  def embedImage(path: Path, budget: InlineBudget): Either[InlineSkip, String] =
    if !isInlineImage(path) then Left(InlineSkip.NotEmbeddable)
    else
      val sizeTry =
        try Some(Files.size(path))
        catch case _: Exception => None
      sizeTry match
        // A 0-byte file has an "empty" data URI, which the browser renders as a
        // broken element — embedding it would make `inlined` count a reference
        // that cannot render, i.e. the very "失败伪装成成功" shape this batch is
        // about (返工 r2: the readable gate and the inline gate must agree).
        case Some(0L) =>
          Left(InlineSkip.Unreadable("the file is 0 bytes — a browser cannot render it"))
        case other =>
          val cost = other match
            case Some(size) => dataUriChars(size, fileExtension(path.toString))
            case None => Int.MaxValue
          if !budget.tryCharge(cost) then Left(InlineSkip.OverBudget)
          else
            readAsDataUri(path) match
              case Right(uri) => Right(uri)
              case Left(detail) =>
                budget.release(cost)
                Left(InlineSkip.Unreadable(detail))
      end match

  // ── warning + counter payload shapes (shared by Card and Pop) ─────────────

  /**
   * Distinct rejected references listed in a tool result; further ones are
   *  counted but not listed, so a pathological document cannot blow up the
   *  result.
   */
  val MaxListedWarnings = 20

  /** Group identical (value, reason) rejections, preserving first-seen order. */
  def distinctRejections(rejects: List[RejectedRef]): List[(RejectedRef, Int)] =
    val grouped = scala.collection.mutable.LinkedHashMap.empty[(String, String), (RejectedRef, Int)]
    rejects.foreach { rejected =>
      val key = (rejected.value, rejected.failure.code)
      grouped.get(key) match
        case Some((first, count)) => grouped.update(key, (first, count + 1))
        case None => grouped.update(key, (rejected, 1))
    }
    grouped.values.toList

  /**
   * The `warnings` array — one object per distinct rejected reference
   *  (原始串 → 解析后路径 → 失败原因 + count).
   */
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

  /**
   * The `fileRefs` counter object. Key order is stable — `proxied`, `failed`,
   *  `omitted` (as shipped by the carderr batch) then the additive `exempt` —
   *  so a reader can diff payload heads across the change. `extra` carries a
   *  tool-specific counter (`deferred` for Pop) after those.
   */
  def fileRefsJson(proxied: Int, failed: Int, omitted: Int, exempt: Int, extra: List[(String, Int)] = Nil): Json =
    Json.obj(
      (List("proxied" -> proxied, "failed" -> failed, "omitted" -> omitted, "exempt" -> exempt) ++ extra)
        .map((k, v) => k -> v.asJson)*
    )

  /**
   * Marker for tools whose result is free text (Pop): the counters ride on a
   *  single line `fileRefs: {…}` that `summarizeResult` can read back. It can
   *  never collide with Card's payload, where the key appears as
   *  `"fileRefs":{…}` inside one longer line.
   */
  val CountsMarker = "fileRefs: "

  def countsIn(result: String): Option[Json] =
    result.linesIterator
      .find(_.startsWith(CountsMarker))
      .flatMap(line => io.circe.parser.parse(line.substring(CountsMarker.length)).toOption)

  def failedIn(result: String): Int =
    countsIn(result).flatMap(_.hcursor.get[Int]("failed").toOption).getOrElse(0)

end FileRefs
