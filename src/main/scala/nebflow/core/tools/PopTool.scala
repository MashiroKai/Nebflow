package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.RootAgentIdentity

import java.nio.file.{Files, Path, Paths}

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-F):单点已下沉 actor
/**
 * Pop tool — shows finished artifacts to the user (pop-upgrade batch, 2026-10-03).
 *
 * == 2026-10-03 author ruling (pop-upgrade batch) ==
 *
 * The author ruled («去掉card工具,然后对Pop工具进行升级») two face changes and
 * one batch addition (「Pop要支持一次工具调用多个Pop文件」):
 *
 *  - **Media (image / video / audio) renders IN THE CHAT** — no Canvas tab. Multiple
 *    items in one call arrive as a WeChat-style stacked card (front card +
 *    peeking layers, click / swipe to switch, 「展开 N」 flattens to one card
 *    per row). A single image renders flat, no stack.
 *  - **Files / documents / HTML animations render as FILE CARDS** at the bottom
 *    of the agent's message — filename + a forward button whose hover text is
 *    「在 Canvas 打开」. The card is NOT swallowed by the turn-process collapse
 *    (turnGroup tucks only `.row.tool` / thinking rows; the artifact row is
 *    neither) — it stays visible after the ✻ header appears.
 *  - **One call may carry many files**: `filePath` accepts a string OR an
 *    array of strings (≤ [[MaxPopFiles]]). The frontend groups the items of
 *    one call into one stacked card / one card group.
 *
 * The Canvas tab face survives for exactly one input kind: an HTTP/HTTPS URL
 * (single-string form only) still opens an embedded iframe tab, as shipped.
 *
 * == Transport ==
 *
 * The artifact payload rides the TOOL RESULT (the `___POP_JSON___` sentinel +
 * one JSON object), exactly like Card's `___CARD_HTML___` face: AgentCore
 * forwards the verbatim result as `frontendContent` (ToolEnd frame + .ui.json
 * history), so live rendering and history replay read the same bytes, and
 * [[modelFacingResult]] keeps the model face a small projection (the payload's
 * data URIs are browser food, not model food). SessionStore stores the payload
 * whole: `___POP_JSON___` matches its card-content detector (starts with `___`
 * and carries `_JSON___`), so a large payload is never preview-truncated in the
 * UI replay file.
 *
 * File CARDS need no bytes at all — the forward button dispatches
 * `workspace-open-item` and the Canvas fetches content itself (readFile), the
 * same open path the shipped Pop card used. MEDIA items either embed their
 * bytes (`data:` URI, the shared `FileRefs` inline policy: ≤5MB per image,
 * 40,000 chars per call, document order) or carry the absolute path and let the
 * frontend mint an `/api/nf-file` ticket — the SAME two legs the Canvas image
 * viewer had. No URL is built tool-side any more: the frontend's
 * `nfTicket.ticketUrl(path)` is the single URL builder (the tool-side
 * `%20`-form encoder retired with the Canvas direct-open face).
 *
 * 2026-09-10 author ruling (unchanged): Pop = Nebula 专属工具——除 Nebula 本体
 * 根会话外，任何 agent / 节点会话 / 子 agent 一律不得调用。收口两层（缺一即不
 * 完整）：①定义/授能层（AgentCore.NebulaExclusiveTools 携带 Pop …）；②分发层
 * （本文件）：call 最前的身份闸——先于任何副作用。
 */
object PopTool extends Tool:

  /**
   * Shared local-reference policy (failure enum, file probe, inline policy,
   * warning/counter JSON shapes) — the same module Card used before its
   * retirement; the media legs below are its only remaining consumer.
   */
  import FileRefs.*

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.tools.pop")

  /**
   * Upper bound for ONE call's file list (pop-upgrade batch). A batch larger
   * than this is refused with an actionable message rather than silently
   * truncated — the model should split the call.
   */
  val MaxPopFiles: Int = 20

  /**
   * Video extensions eligible for the in-chat media face. They are all served
   * by `/api/nf-file` (`NfFilePolicy.NfFileAllowedExt` carries mp4/webm/ogg/
   * ogv/mov), and none of them is ever inlineable — a video item always rides
   * the ticket leg. Kept here (not in `FileTypeRegistry`) on purpose: the
   * registry's table is the CANVAS viewer's face and its behavior is frozen;
   * an `.mp4` opened in Canvas keeps its pre-batch itemType there.
   */
  val VideoExtensions: Set[String] = Set("mp4", "webm", "mov", "m4v", "ogv", "ogg")

  /**
   * Audio extensions eligible for the in-chat media face (canvas-media Wave2,
   * 2026-10-05 author ruling OD-1 = (b): audio joins the chat inline face where
   * video already lives). Same single-source discipline as [[VideoExtensions]]:
   * the set mirrors the whitelist's audio family exactly (`FileRefs.
   * AllowedExtensions` / `NfFilePolicy.NfFileAllowedExt`: mp3 wav oga flac
   * aac m4a), every member is served by `/api/nf-file`, and an audio item is
   * never inlineable — it always rides the ticket leg, like video. Kept here
   * (not in `FileTypeRegistry`) on purpose: the registry's table is the CANVAS
   * viewer's face and its behavior is frozen. `ogg` stays video-side only (the
   * whitelist's own comment blocks file it under video; `oga` is the
   * audio-side container), so no extension is claimed by both faces.
   */
  val AudioExtensions: Set[String] = Set("mp3", "wav", "oga", "flac", "aac", "m4a")

  /** The sentinel the frontend splits the payload on (`chat.js` / `persistence.js`). */
  val Sentinel = "___POP_JSON___"

  /** Extract hostname from a URL string. */
  private def extractHostname(url: String): String =
    try java.net.URI.create(url).getHost
    catch case _: Exception => url

  /** Check if a string is an HTTP/HTTPS URL. */
  private def isHttpUrl(s: String): Boolean =
    s.startsWith("http://") || s.startsWith("https://")

  val name = "Pop"

  /**
   * `def`, not `val`, on purpose (DataRootPlaceholderSpec contract): the
   * description interpolates `PathUtil.dataRootRenderValue` at CALL time — a
   * `val` would freeze whichever data root happened to be in force at object
   * initialization (an isolated `--home` instance would teach the model the
   * wrong workspace path).
   */
  def description: String =
    s"""Shows finished artifacts to the user. One call accepts ONE path or a LIST of paths — batch the files of one deliverable into one call so they render as one group.

## Nebula-only (2026-09-10 author ruling)

Pop is Nebula-exclusive: only the Nebula root session may call it. Every other agent, node session, or sub-agent call is rejected with POP_NEBULA_ONLY. Nodes do not Pop — they hand the deliverable to the chain end / Nebula along the out edge, and Nebula decides whether it is shown. Pop cannot be granted back to any other identity (it left the builtin tool whitelist).

## What happens to each item (2026-10-03 pop-upgrade ruling)

- **Image / video / audio** (`png jpg jpeg gif webp bmp svg`, `mp4 webm mov m4v ogv ogg`, `mp3 wav oga flac aac m4a`) — rendered DIRECTLY IN THE CHAT, no Canvas tab. Several items in one call render as one stacked card (front card + peeking layers; click / swipe switches; 「展开 N」 flattens to one card per row). A single image renders flat.
- **Every other file** (documents, PDF, Office, markdown, code, HTML animations…) — rendered as a FILE CARD at the bottom of your message: filename + a forward button (hover: 「在 Canvas 打开」) that opens it in Canvas on click. The card survives the turn-process collapse. No Canvas tab opens by itself.
- **HTTP/HTTPS URL** — opens a Canvas tab with an embedded iframe, as before. A URL may be the single string form or a lone array element; an array MIXING a URL with file paths is refused — pop URLs one call at a time, files may be batched.

## When to use

- You decide a finished result should be shown to the user.
- The user asks to "open" or "show" files/photos/videos.
- A node handed over a deliverable along the out edge and showing it is warranted.

**Call Pop AFTER your final text** for the deliverable — the cards land at the bottom of that message. One call per deliverable group: the photos of one reply go in ONE call (an array), not three calls.

## Media bytes: inline vs ticket

A local image ≤5MB in an embeddable format is embedded as a base64 `data:` URI when this call's cumulative inline budget still covers it — at most ${FileRefs.MaxInlinePayloadChars} characters of `data:` URI (≈30 KB of source bytes) per call, spent in the order the paths appear; anything past that keeps its path reference. Referenced images, every video and every audio file are fetched by the frontend through `/api/nf-file` with a per-path ticket the gateway mints at render time; the gateway serves the path only if its credential-namespace policy allows it — the data directory serves ${DataRootServedNamespacesText} and the project `.nebflow/` serves `evidence*/**`. A path outside the data directory and the project `.nebflow/` stays servable where it is (an absolute `/tmp/shot.png` renders), as long as it is not credential-shaped. Project workspaces live under ${nebflow.shared.PathUtil.dataRootRenderValue}/projects/<name>/ — write that full path (not `~/projects/<name>/…`), and put deliverables under one of the served locations when you can.

Every path that could NOT be shown is reported in this tool's result under `warnings` (`ref` → `resolvedPath` → `reason`: not-found / unresolvable / extension-not-allowed / size-exceeded / not-regular-file / not-readable / not-servable / other) plus a `fileRefs` counter line (`failed`). Read `warnings` and fix the paths before finishing. A path containing spaces is fine and needs no special spelling: write it as it is on disk (`%20` also works); a `notes` array reports any path that was resolved in its decoded form.

## Parameters

- filePath (string OR array of strings, required): Absolute path(s) to show (supports `~` expansion), or a single HTTP/HTTPS URL. At most $MaxPopFiles paths per call.
- title (string, optional): Custom Canvas tab title — only meaningful for the single-URL form.

Example (one photo): {"filePath": "/tmp/output.svg"}
Example (a batch — the usual shape): {"filePath": ["~/projects/shot1.png", "~/projects/shot2.png", "~/projects/clip.mp4"]}
Example (documents): {"filePath": ["~/projects/report.pdf", "~/projects/demo.html"]}
Example (URL): {"filePath": "https://example.com"}"""

  /**
   * Model-visible schema. zcode-484: the `filePath` declaration now mirrors what
   * [[requestedPaths]] actually accepts — the array branch carries `items`
   * (`string`), `minItems` (1) and `maxItems` ([[MaxPopFiles]]), because an
   * unconstrained `"type": ["string","array"]` promised arrays of ANYTHING while
   * the tool silently dropped every non-string element (`[1,"a"]` → `["a"]`).
   * Declaration and behaviour are asserted isomorphic by `PopToolSpec`.
   *
   * The union `type` form itself is kept (rather than the `oneOf` dual branch the
   * PLAN §3.3-2 recommends): it is one of only two union-type sites in the repo
   * (`ScheduleTool` is the other), so switching the FORM is a model-visible change
   * across batches and stays an open author decision (OD-3). Consistency does not
   * need it — `items` is what the array branch was missing.
   */
  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "filePath" -> Json.obj(
          "type" -> Json.arr("string".asJson, "array".asJson),
          "items" -> Json.obj("type" -> "string".asJson),
          "minItems" -> 1.asJson,
          "maxItems" -> MaxPopFiles.asJson,
          "description" -> "Absolute path to show (supports ~ expansion), an array of such paths (batch, up to 20), or a single HTTP/HTTPS URL".asJson
        ),
        "title" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Custom Canvas tab title (single-URL form only; defaults to hostname)".asJson
        )
      ),
      "required" -> Json.arr("filePath".asJson)
    )
  )

  /**
   * 拒答文案（2026-09-10 作者裁定，作者原话逐字保留）：前置结构化前缀 + 错误码
   * （仓内 ToolError 惯例：`<Tool>: ... (CODE)`）。
   */
  private val RootOnlyError: ToolError = ToolError(
    "Pop: permission denied — Pop 已收归 Nebula 专属；交付物请沿 out 边交给链末端 / Nebula，由 Nebula 决定是否展示 (POP_NEBULA_ONLY)"
  )

  /**
   * 身份闸判据（2026-09-10 作者裁定）：两层同真才放行。
   *
   *  1. `ctx.agentDef.exists(_.name == "Nebula")` —— 身份来源 = ctx.agentDef；
   *  2. `ctx.depth == 0` —— 「Nebula 本体根会话」判据（depth==0 排除
   *     NodeDef.agent="Nebula" 的节点会话，它们的 depth=1）。
   *
   * `ctx.agentDef == None`（REST 直调 / spec harness）→ **fail-closed**。
   * 谓词本体 = 全仓唯一单点 [[nebflow.agent.AgentCore.isRootAgent]]（本方法退化为
   * 纯委托——不得在此重写 `name=="Nebula" && depth==0`，可判红：
   * `AskUserDualModeSpec` 的 grep 级静态断言）。
   */
  private def isRootAgentSession(ctx: ToolContext): Boolean =
    RootAgentIdentity.isRootAgent(ctx.agentDef, ctx.depth)

  // ── input shape ───────────────────────────────────────────────────────────

  /**
   * The reserved marker key the provider adapters inject when a tool call's
   * arguments JSON could not be parsed (`ToolInputJson.RawArgsKey`). Duplicated
   * as a literal on purpose: `nebflow.core` must not import `nebflow.llm`
   * (dependency direction, `JevGateSpec` states the same rule), and this module
   * only needs the KEY, not the parser. `PopToolSpec` pins the parity against
   * `ToolInputJson.RawArgsKey`, so the copy cannot drift silently.
   */
  private val RawArgsKey = "__nebflow_raw_args__"

  /** JSON type name of a `filePath` element, for the readable shape errors. */
  private def jsonType(v: Json): String =
    if v.isString then "string"
    else if v.isArray then "array"
    else if v.isObject then "object"
    else if v.isBoolean then "boolean"
    else if v.isNull then "null"
    else "number"

  /**
   * A `filePath` STRING that is really a serialized JSON array literal —
   * `"[\"a\",\"b\"]"` — was accepted as ONE path by the old code, so the call
   * went on to fail as "not found" instead of as a shape error. The zcode-484
   * report describes exactly this class of silent degradation; it is named here
   * even though the root cause is undetermined tool-side (PLAN §3.0: no code
   * path in this repo serializes an array into one string). Only a string whose
   * trimmed form parses as a JSON ARRAY is flagged — a path that merely starts
   * with `[` is not.
   */
  private def looksLikeJsonArrayString(s: String): Boolean =
    val t = s.trim
    t.startsWith("[") && t.endsWith("]") &&
      io.circe.parser.parse(t).toOption.exists(_.isArray)

  /**
   * `filePath` accepts the shipped single string OR an array of strings
   * (pop-upgrade batch). Anything else (number/object/absent) is a usage error
   * the model can act on.
   *
   * zcode-484: the acceptance is now STRUCTURE-AWARE. The old walk used
   * `asArray.map(_.flatMap(_.asString))`, which silently dropped every non-string
   * element (`[1,"a"]` → `["a"]`, `[{}]` → `[]`) and then reported the generic
   * "non-empty filePath" error — an unreadable failure and the exact
   * silent-degradation shape the batch forbids. Each malformed form now names
   * the offending element (index + actual JSON type) or the offending string, and
   * a marker object from `ToolInputJson` (unparseable arguments) is reported as
   * such instead of as a missing `filePath`.
   */
  private def requestedPaths(input: JsonObject): Either[ToolError, List[String]] =
    // Unparseable arguments: every field was dropped tool-side. Report the parse
    // failure itself (with the raw preview), never "filePath is missing" — the
    // misleading error issue #18 exists to prevent (PLAN §3.3-3).
    input(RawArgsKey).flatMap(_.asString) match
      case Some(raw) =>
        Left(
          ToolError(
            "Pop: the tool-call arguments JSON could not be parsed, so no `filePath` reached the tool. " +
              "Raw arguments: " + raw.take(400)
          )
        )
      case None =>
        input("filePath") match
          case None =>
            Left(ToolError("Pop tool requires a `filePath` parameter (string or array of strings)."))
          case Some(v) =>
            v.asString match
              case Some(s) =>
                if s.trim.isEmpty then Left(EmptyShapeError)
                else if looksLikeJsonArrayString(s) then
                  Left(
                    ToolError(
                      "Pop: `filePath` looks like a JSON array literal — pass a real JSON array, " +
                        "not a quoted array string (got a string starting with `[`)."
                    )
                  )
                else Right(List(s))
              case None =>
                v.asArray match
                  case Some(elems) =>
                    val offending = elems.zipWithIndex.collectFirst {
                      case (e, i) if !e.isString => i -> jsonType(e)
                    }
                    offending match
                      case Some((i, t)) =>
                        Left(
                          ToolError(
                            s"Pop: filePath[$i] must be a plain path string (got $t) — " +
                              "pass an array of strings, or one path per call."
                          )
                        )
                      case None =>
                        val paths = elems.flatMap(_.asString).filter(_.trim.nonEmpty).toList
                        if paths.isEmpty then
                          Left(
                            ToolError(
                              s"Pop: filePath array carried no usable path strings " +
                                s"(${elems.length} entries) — pass plain path strings."
                            )
                          )
                        else if paths.length > MaxPopFiles then
                          Left(
                            ToolError(
                              s"Pop accepts at most $MaxPopFiles paths per call (got ${paths.length}) — split the batch across calls."
                            )
                          )
                        else
                          val arrayLiteral = paths.find(looksLikeJsonArrayString)
                          arrayLiteral match
                            case Some(_) =>
                              Left(
                                ToolError(
                                  "Pop: `filePath` looks like a JSON array literal — pass a real JSON array, " +
                                    "not a quoted array string (got a string starting with `[`)."
                                )
                              )
                            case None => Right(paths)
                  case None =>
                    Left(
                      ToolError(
                        s"Pop: `filePath` must be a path string or an array of path strings (got ${jsonType(v)})."
                      )
                    )
  end requestedPaths

  /** The unreadable-batch error for a non-empty-looking input that yielded no
   *  path at all (a blank string, or an array of blanks/whitespace). */
  private val EmptyShapeError: ToolError =
    ToolError(
      "Pop: `filePath` was empty (no path string carried content) — pass a non-blank path or an array of paths."
    )

  // ── per-item probing ──────────────────────────────────────────────────────

  /** One payload item: media (image/video/audio) or a file card. */
  private case class PopItem(
    kind: String, // "image" | "video" | "audio" | "file"
    name: String,
    path: String,
    size: Option[Long] = None,
    ext: Option[String] = None,
    itemType: Option[String] = None,
    /** Inlined bytes (`data:` URI) — present only for embedded images. */
    src: Option[String] = None
  )

  private def itemJson(i: PopItem): Json = Json.fromFields(
    List(
      "kind" -> i.kind.asJson,
      "name" -> i.name.asJson,
      "path" -> i.path.asJson
    ) ++
      i.size.map(s => "size" -> s.asJson).toList ++
      i.ext.map(e => "ext" -> e.asJson).toList ++
      i.itemType.map(t => "itemType" -> t.asJson).toList ++
      i.src.map(u => "src" -> u.asJson).toList
  )

  /**
   * Resolve + classify ONE requested path into an item, or a rejection, plus
   * the decoded-form disclosure note when one applies. Pure filesystem work —
   * runs inside the caller's `IO.blocking`.
   *
   * Resolution goes through `FileRefs.resolveCandidates` (least-transformed
   * first: the raw spelling, then its decoded form), so a `%20`-spelled path
   * still finds the file and the note discloses which form won — the imgref
   * batch (2026-09-18 作者令) contract, byte-for-byte. Media legs reuse the
   * SHARED probes (`FileRefs.probeFile` for the ticket leg's servability,
   * `FileRefs.embedImage` for the inline decision with its per-image gate +
   * cumulative budget, `FileRefs.inlineMayTakeOver` for the takeover rule) —
   * the same ladder Card used, so the two faces never drift. File-card legs
   * probe only what the card needs (exists / regular file / size): their bytes
   * are fetched by Canvas `readFile` on open, not by this tool.
   */
  private def buildItem(raw: String, budget: InlineBudget): (Either[RejectedRef, PopItem], Option[String]) =
    val value = raw.trim
    val ext = fileExtension(value)
    val isVideo = VideoExtensions.contains(ext)
    val isAudio = AudioExtensions.contains(ext)
    val isImage = EmbeddableImageExtensions.contains(ext)
    val hit = FileRefs.resolveCandidates(
      value,
      FileRefs.resolvePath,
      v => unresolvable(v, "the path could not be resolved to a filesystem path")
    )
    (hit.path, hit.decision) match
      case (None, RefDecision.Reject(rejected)) => (Left(rejected), hit.note)
      case (None, _) =>
        (
          Left(RejectedRef(value, None, FileRefFailure.Unresolvable, "the path could not be resolved")),
          hit.note
        )
      case (Some(path), decision) =>
        val name = path.getFileName.toString
        val body: Either[RejectedRef, PopItem] =
          if isImage || isVideo || isAudio then
            mediaItem(value, path, ext, name, decision, budget, isVideo, isAudio)
          else fileCardItem(value, path, ext, name)
        (body, hit.note)
  end buildItem

  /**
   * Media leg from the ALREADY-COMPUTED probe verdict (resolveCandidates
   * ran `probeFile` internally — never probe twice): the bytes either ride
   * inline (images only) or the path rides for the ticket leg (video / audio —
   * both are always referenced, never inlined).
   */
  private def mediaItem(
    value: String,
    path: Path,
    ext: String,
    name: String,
    decision: RefDecision,
    budget: InlineBudget,
    isVideo: Boolean,
    isAudio: Boolean
  ): Either[RejectedRef, PopItem] =
    decision match
      case RefDecision.Proxy(_) =>
        if isVideo then referencedVideo(value, path, ext, name)
        else if isAudio then referencedAudio(value, path, ext, name)
        else
          embedImage(path, budget) match
            case Right(dataUri) => inlineImage(value, path, ext, name, dataUri)
            case Left(InlineSkip.Unreadable(detail)) =>
              Left(RejectedRef(value, Some(describe(path)), FileRefFailure.Other, detail))
            case Left(_) =>
              // Per-image gate miss or over-budget: the ticket leg still renders
              // it (the probe already proved the endpoint serves it) — never a
              // warning (a 5MB+ PNG that renders fine is not a defect).
              referencedImage(value, path, ext, name)
      case RefDecision.Reject(rejected) if !isVideo && !isAudio && inlineMayTakeOver(path, rejected) =>
        // The endpoint refuses this reference for its REACH layer only, and the
        // file's identity is not a credential — embed the bytes (the shipped
        // behaviour: such a file never had a working reference leg, only
        // working bytes). 返工 r2 discipline, unchanged. Image-only: audio has
        // no inline leg to take over with — its refusal stands.
        embedImage(path, budget) match
          case Right(dataUri) => inlineImage(value, path, ext, name, dataUri)
          case Left(_) =>
            // Nothing can render it: the endpoint's refusal stands.
            Left(rejected)
      case RefDecision.Reject(rejected) => Left(rejected)
      case other =>
        Left(RejectedRef(value, Some(describe(path)), FileRefFailure.Other, s"unexpected probe verdict: $other"))
  end mediaItem

  private def inlineImage(value: String, path: Path, ext: String, name: String, dataUri: String): Either[RejectedRef, PopItem] =
    sizeOf(path).map { size =>
      Right(PopItem("image", name, describe(path), size = Some(size), ext = Some(ext), src = Some(dataUri)))
    }.getOrElse(Left(notReadable(value, path)))

  private def referencedImage(value: String, path: Path, ext: String, name: String): Either[RejectedRef, PopItem] =
    sizeOf(path).map { size =>
      Right(PopItem("image", name, describe(path), size = Some(size), ext = Some(ext)))
    }.getOrElse(Left(notReadable(value, path)))

  private def referencedVideo(value: String, path: Path, ext: String, name: String): Either[RejectedRef, PopItem] =
    sizeOf(path).map { size =>
      Right(PopItem("video", name, describe(path), size = Some(size), ext = Some(ext)))
    }.getOrElse(Left(notReadable(value, path)))

  private def referencedAudio(value: String, path: Path, ext: String, name: String): Either[RejectedRef, PopItem] =
    sizeOf(path).map { size =>
      Right(PopItem("audio", name, describe(path), size = Some(size), ext = Some(ext)))
    }.getOrElse(Left(notReadable(value, path)))

  /** File-card leg: exists + regular + size — Canvas readFile fetches the bytes on open. */
  private def fileCardItem(value: String, path: Path, ext: String, name: String): Either[RejectedRef, PopItem] =
    try
      if !Files.exists(path) then
        val hint = nearestExistingParent(path)
          .map(parent => s"; the nearest existing parent directory is ${describe(parent)}")
          .getOrElse("")
        Left(RejectedRef(value, Some(describe(path)), FileRefFailure.NotFound, s"no file at ${describe(path)}$hint"))
      else if !Files.isRegularFile(path) then
        Left(
          RejectedRef(value, Some(describe(path)), FileRefFailure.NotRegularFile,
            s"${describe(path)} is a directory or another non-regular file")
        )
      else
        val size = Files.size(path)
        Right(
          PopItem(
            "file",
            name,
            describe(path),
            size = Some(size),
            ext = Some(ext),
            itemType = Some(nebflow.core.workspace.FileTypeRegistry.detect(ext).itemType)
          )
        )
    catch
      case e: Exception =>
        Left(RejectedRef(value, Some(describe(path)), FileRefFailure.Other,
          s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"))

  private def sizeOf(path: Path): Option[Long] =
    try Some(Files.size(path))
    catch case _: Exception => None

  private def notReadable(value: String, path: Path): RejectedRef =
    RejectedRef(value, Some(describe(path)), FileRefFailure.NotReadable,
      s"${describe(path)} is present but its size could not be read")

  // ── payload ───────────────────────────────────────────────────────────────

  /**
   * The frontend payload: one JSON object after [[Sentinel]]. Field order
   * leads with the counters/warnings so a truncated preview still shows the
   * actionable head (the same contract as Card's payload).
   */
  private def payloadJson(
    items: List[PopItem],
    rejects: List[RejectedRef],
    notes: List[String]
  ): Json =
    val distinct = distinctRejections(rejects)
    val listed = distinct.take(MaxListedWarnings)
    val inlined = items.count(_.src.isDefined)
    val referenced = items.count(i => (i.kind == "image" || i.kind == "video" || i.kind == "audio") && i.src.isEmpty)
    Json.obj(
      "fileRefs" -> Json.obj(
        "inlined" -> inlined.asJson,
        "referenced" -> referenced.asJson,
        "failed" -> distinct.size.asJson,
        "omitted" -> (distinct.size - listed.size).asJson
      ),
      "warnings" -> warningsJson(listed),
      "notes" -> Json.arr(notes.map(_.asJson)*),
      "items" -> Json.arr(items.map(itemJson)*)
    )

  /**
   * The **model-facing projection** (Card's `modelFacingResult` precedent):
   * the payload's data URIs are browser food, and their size is exactly what
   * would push the raw result past the guard — so the model face is a small,
   * actionable projection carrying the SAME counters / warnings / notes
   * objects verbatim. Never contains the sentinel, never any `data:` URI, and
   * its length is independent of the media sizes.
   */
  override def modelFacingResult(result: String): String =
    payloadOf(result) match
      case Some(p) =>
        val fileRefs = p.hcursor.downField("fileRefs").focus.getOrElse(Json.obj())
        val media = p.hcursor.downField("items").focus.getOrElse(Json.arr()).asArray
          .map(_.count(i => Set("image", "video", "audio").contains(i.hcursor.get[String]("kind").toOption.getOrElse(""))))
          .getOrElse(0)
        val files = p.hcursor.downField("items").focus.getOrElse(Json.arr()).asArray
          .map(_.count(i => i.hcursor.get[String]("kind").toOption.contains("file")))
          .getOrElse(0)
        Json
          .obj(
            "pop" -> "displayed".asJson,
            "media" -> media.asJson,
            "files" -> files.asJson,
            "fileRefs" -> fileRefs,
            "warnings" -> p.hcursor.downField("warnings").focus.getOrElse(Json.arr()),
            "notes" -> p.hcursor.downField("notes").focus.getOrElse(Json.arr()),
            "note" -> ("The media/files are rendered to the user from the payload and are not returned as text; "
              + "`fileRefs.failed` and `warnings` above are the facts to act on.").asJson
          )
          .noSpaces
      case None =>
        // URL leg (plain text) or an unparseable payload: identity is safe — the
        // raw result is already small.
        result
    end match
  end modelFacingResult

  /** Parse the payload out of a raw result (`None` for the URL leg / a truncated preview). */
  private def payloadOf(result: String): Option[Json] =
    if !result.startsWith(Sentinel) then None
    else io.circe.parser.parse(result.substring(Sentinel.length)).toOption

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // 身份闸最前——先于任何副作用（filePath 解析 / 文件读 / 内联 / WS 发送）。
    if !isRootAgentSession(ctx) then IO.pure(Left(RootOnlyError))
    else doCall(input, ctx)

  /** Nebula 本体根会话的 Pop 实现（身份已过闸）。 */
  private def doCall(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    requestedPaths(input) match
      case Left(err) => IO.pure(Left(err))
      case Right(paths) =>
        paths match
          // Single HTTP/HTTPS URL: the Canvas iframe tab, exactly as shipped.
          case List(single) if isHttpUrl(single.trim) =>
            val url = single.trim
            val customTitle = input("title").flatMap(_.asString).getOrElse("")
            val tabTitle = if customTitle.nonEmpty then customTitle else extractHostname(url)
            val msg = Json.obj(
              "type" -> "popFile".asJson,
              "item" -> Json.obj(
                "id" -> s"url:$url".asJson,
                "itemType" -> "url".asJson,
                "title" -> tabTitle.asJson,
                "url" -> url.asJson,
                "pinned" -> true.asJson
              )
            )
            val sendIO = ctx.wsSend.getOrElse((_: Json) => IO.unit)
            sendIO(msg) >> IO.pure(Right(s"Opened $tabTitle in Canvas."))
          case _ =>
            val urlItems = paths.filter(p => isHttpUrl(p.trim))
            if urlItems.nonEmpty then
              IO.pure(
                Left(
                  ToolError(
                    "Pop: an HTTP/HTTPS URL cannot be batched with file paths — pop the URL alone " +
                      "(single-string filePath), files may be batched as an array."
                  )
                )
              )
            else
              IO.blocking {
                // ONE budget per call (the FileRefs rule): the items of one
                // batch share it in the order the paths appear.
                val budget = InlineBudget()
                val notes = scala.collection.mutable.ListBuffer.empty[String]
                val rejects = scala.collection.mutable.ListBuffer.empty[RejectedRef]
                val items = scala.collection.mutable.ListBuffer.empty[PopItem]
                paths.foreach { raw =>
                  val (item, note) = buildItem(raw, budget)
                  item match
                    case Right(ok) =>
                      items += ok
                      note.foreach(n => if !notes.contains(n) then notes += n)
                    case Left(rejected) =>
                      // A decoded-form hit is DISCLOSED even when the item went
                      // on to fail for another reason (imgref batch: the result
                      // must say which form of the path was used).
                      note.foreach(n => if !notes.contains(n) then notes += n)
                      rejects += rejected
                }
                val payload = payloadJson(items.toList, rejects.toList, notes.toList.distinct)
                s"$Sentinel${payload.noSpaces}"
              }.map(Right(_))
    end match
  end doCall

  /**
   * Disclosure note for a path that only named a file through its decoded form
   * (imgref batch, 2026-09-18 作者令) now rides `FileRefs.resolveCandidates`'
   * own `CandidateHit.note` — no second copy of the rule here.
   */

  def summarize(input: JsonObject): String =
    val filePath = input("filePath")
    val title = input("title").flatMap(_.asString).getOrElse("")
    val label = filePath.flatMap(_.asString) match
      case Some(single) =>
        if title.nonEmpty then title
        else if isHttpUrl(single) then extractHostname(single)
        else single.split('/').lastOption.getOrElse(single)
      case None =>
        filePath.flatMap(_.asArray).map(_.flatMap(_.asString).filter(_.trim.nonEmpty)) match
          case Some(paths) if paths.length > 1 =>
            val first = paths.head.split('/').lastOption.getOrElse(paths.head)
            s"$first +${paths.length - 1}"
          case _ => "?"
    s"Pop\n  ($label)"

  def summarizeResult(input: JsonObject, result: String): String =
    result match
      case r if r.startsWith("Opened ") => r // URL leg, unchanged face
      case _ =>
        payloadOf(result) match
          case Some(p) =>
            val items = p.hcursor.downField("items").as[List[Json]].getOrElse(Nil)
            val media = items.count(i => i.hcursor.get[String]("kind").toOption.exists(k => k == "image" || k == "video" || k == "audio"))
            val files = items.count(i => i.hcursor.get[String]("kind").toOption.contains("file"))
            val failed = p.hcursor.downField("fileRefs").get[Int]("failed").toOption.getOrElse(0)
            // Visibility (toolfail-batch discipline): a Pop whose items were
            // dropped must not look clean.
            val note = if failed > 0 then s" — $failed path(s) NOT shown" else ""
            s"Pop: $media media + $files file card(s) shown in chat$note"
          case None => result

end PopTool
