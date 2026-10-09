package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.FilePolicyPort

import java.nio.file.{Files, Path, Paths}

/**
 * Card tool — renders arbitrary HTML in a sandboxed iframe in the chat stream.
 *
 * Agents emit HTML directly. The frontend renders it in a sandboxed <iframe>
 * with theme variables injected, so dark mode works automatically.
 *
 * Local file references (src="...") pointing to image/video/audio/font/PDF files
 * on disk are converted to /api/nf-file?path=... URLs served by the HTTP endpoint.
 * The frontend injects the auth token into these URLs before rendering.
 * Only whitelisted media extensions are processed — arbitrary files are skipped.
 */
object CardTool extends Tool:

  /**
   * Card LLM content is always a compact summary — exempt from guard.
   * 注意（2026-09-05 恢复批核对）：2026-09-01 #38 起 ToolResultGuard 对
   * ∞ 声明不再豁免——math.min(declaredMax, Defaults.DefaultMaxResultSizeChars)
   * 将本声明 clamp 到系统级 50K 上限。本行保留仅表达"Card 结果设计上紧凑"
   * 的意图，实际由 guard 统一兜底（大 card 持久化+preview，frontendContent
   * 全文保留）。
   */
  override val maxResultSizeChars: Int = Int.MaxValue

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.tools.card")

  /**
   * The shared local-reference policy (failure enum, file probe, app-route
   *  exemption, warning/counter JSON shapes) lives in `FileRefs` — the same
   *  one Pop uses, so the two tools cannot drift apart. Card contributes only
   *  the resolution policy below (a Card has no containing directory).
   */
  import FileRefs.*

  /** Regex matching src= attributes with both single and double quotes. */
  private val SrcAttrRegex = """(?i)src\s*=\s*["']([^"']+)["']""".r

  /** Regex matching href= attributes — for <link> stylesheets and other references. */
  private val HrefAttrRegex = """(?i)href\s*=\s*["']([^"']+)["']""".r

  /**
   * `srcset=` attribute value.
   *
   *  2026-09-11 (toolfail batch): `srcset` was outside the scan face, so a
   *  responsive `<img srcset="…">` whose candidates all failed stayed silent.
   *  The lookbehind keeps `data-srcset=` out — the two legacy regexes above
   *  carry no such boundary (pre-existing over-match, registered as a
   *  follow-up rather than widened here).
   */
  private val SrcsetAttrRegex = """(?i)(?<![-\w])srcset\s*=\s*["']([^"']+)["']""".r

  /**
   * A CSS `url(...)` token, quoted or bare. One pattern covers `<style>`
   *  blocks, inline `style` attributes, `@import url(…)`,
   *  `image-set(url(…))` and `@font-face src:` — all reduce to this token.
   */
  private val CssUrlRegex = """(?i)url\(\s*(['"]?)([^'")]+)\1\s*\)""".r

  /** A bare `@import "…"` — the `url(…)` form is already covered above. */
  private val ImportBareRegex = """(?i)@import\s+(['"])([^'"]+)\1""".r

  /**
   * One card's local-file pass: rewritten HTML plus every rejected reference
   *  and the count of suppressed app-route exemptions.
   *
   *  `inlined` (2026-09-16 imgfix batch) counts the references rewritten to a
   *  `data:` URI instead of an `/api/nf-file` URL — see the inline policy in
   *  `FileRefs` (`MaxEmbedImageSize` / `isInlineImage`). `proxied` keeps its
   *  meaning as "reference rewritten to an `/api/nf-file` URL", so the two
   *  counters never overlap.
   *
   *  `deferred` (2026-09-16 img-ticket batch i, #687-C) counts the references
   *  that were ELIGIBLE for embedding (extension + ≤5MB) but did not fit the
   *  cumulative 40,000-character inline budget for this call, so they kept the
   *  `/api/nf-file` URL. They are also counted in `proxied` (that is what they
   *  are now); `deferred` is the extra bit of information — "the budget, not
   *  the file, is why this is a reference" — and it is why a rare over-budget
   *  card is not mistaken for a card that was never embeddable.
   */
  private[tools] case class EmbedOutcome(
    html: String,
    proxied: Int,
    inlined: Int,
    deferred: Int,
    rejects: List[RejectedRef],
    exempt: Int,
    /**
     * Disclosure lines, one per DISTINCT decoded-form hit (imgref batch
     *  2026-09-18): "this reference was spelled with URL escapes; it was
     *  resolved as the decoded form …". Never silent — 作者令要求命中时
     *  必须在回包/告警里说明用了哪一形态。
     */
    notes: List[String] = Nil
  )

  /**
   * Classify one reference value (an `src`/`href` attribute, a `srcset`
   * candidate URL, a CSS `url(...)` token or a bare `@import`): proxy it,
   * ignore it, exempt it, or reject it with a reason.
   *
   * Only ANCHORED references are ever proxied — `~`, a POSIX absolute path
   * (`/…`) and a Windows drive-letter absolute path (`C:/…`, `C:\…`; winpath
   * batch, 2026-09-17: the gate used to accept only the first two, so a
   * drive-letter reference was reported as a relative mistake — see
   * `FileRefs.WindowsDriveRoot` for why `PathUtil.isAbsolute` is not reused
   * verbatim here). Relative references stay unproxied and WARN instead of
   * silently doing nothing — that also keeps
   * `?path=images/logo.png` (resolved by the endpoint against the *server's*
   * working directory) out of the URL space, which the docs always promised.
   * A failing value that is simultaneously a gateway route (`/js/…`) is
   * exempted instead of reported — see `FileRefs.applyAppRouteExemption`.
   *
   * Returns the verdict PLUS the resolved path when the value named an existing
   * regular file. The embedding decision itself is NOT taken here any more
   * (2026-09-16 img-ticket batch i): it moved to the document-order pass in
   * [[embedLocalFiles]], because the cumulative inline budget
   * (`FileRefs.MaxInlinePayloadChars`) is order-sensitive and this method is
   * called once per SCAN PATTERN (all `src=` values, then all `href=` values,
   * …), which is not document order. Keeping the decision here would have made
   * "who gets the budget" depend on which pattern matched, i.e. on nothing the
   * user can see.
   *
   * RESOURCE vs NAVIGATION faces are decided by the call site, not here:
   * the resource faces (`src=`, `srcset` candidates, CSS `url(...)`) may embed
   * an inlineable image's bytes as a `data:` URI; the navigation faces
   * (`href=`, bare `@import`) never do — an `<a href="chart.png">` turned into
   * a `data:` URI stops being the local-file link the Canvas router handles,
   * and a 683 KB base64 attribute in an anchor helps nobody.
   *
   * The file-level probe itself is shared with Pop (`FileRefs.probeFile`); only
   * the resolution policy in this wrapper is Card-specific.
   */
  private def decideRef(rawValue: String): RefVerdict =
    val value = rawValue.trim
    if !isLocalFilePath(value) || !looksLikeFilePath(value) then RefVerdict(RefDecision.Ignore, None)
    else
      val verdict: RefVerdict =
        if isUncPath(value) then
          RefVerdict(
            unresolvable(
              value,
              "Windows UNC references (`\\\\server\\share\\…`) are not resolved — copy the file to a local " +
                "absolute path (`/Users/you/…`, `C:\\Users\\you\\…`) or under `~/…` and reference that"
            ),
            None
          )
        else if !isAnchoredRefPath(value) then
          RefVerdict(
            unresolvable(
              value,
              "relative references are never resolved — use an absolute path (`/Users/you/…`, `C:\\Users\\you\\…`) or `~/…`"
            ),
            None
          )
        else if hasTemplatePlaceholder(value) then
          RefVerdict(
            unresolvable(
              value,
              "the reference contains a template placeholder — resolve it to a real path before emitting the card"
            ),
            None
          )
        else
          // imgref batch (2026-09-18 作者令): a reference whose path is spelled
          // with URL escapes (`%20`) or a bare `+` does not name anything on
          // disk — the SHIPPED code looked for the literal string and reported
          // `not-found` (author's failure ①, nearest parent stopping at
          // `evidence`). Resolve through the candidate forms instead: raw first,
          // then the decoded form, hit-and-use, and say which form was used.
          val hit = FileRefs.resolveCandidates(
            value,
            FileRefs.resolvePath,
            v => unresolvable(v, "the reference is not a usable filesystem path")
          )
          // The path travels with the verdict: the inline pass needs it for
          // `FileRefs.embedImage` (extension + size + read) and re-resolving
          // it there would probe the filesystem twice. 返工 r2: it now also
          // travels for a refusal the INLINE leg may take over (an endpoint
          // namespace refusal of a non-credential file — see
          // `FileRefs.inlineMayTakeOver`), because that leg still has to embed
          // the bytes of exactly this file.
          val candidate = hit.decision match
            case _: RefDecision.Proxy => hit.path
            case RefDecision.Reject(rejected) =>
              hit.path.filter(p => FileRefs.inlineMayTakeOver(p, rejected))
            case _ => None
          RefVerdict(hit.decision, candidate, hit.note)
      val decision = applyAppRouteExemption(value, verdict.decision)
      decision match
        case RefDecision.Exempt(route) => logger.debug(s"Card: exempted app-route reference '$value' (route $route)")
        case _ => ()
      // An exempted reference is not servable as a file, so it carries no inline
      // candidate either (the verdict it was exempted from was already a failure).
      decision match
        case _: RefDecision.Exempt => RefVerdict(decision, None)
        case _ => verdict
    end if
  end decideRef

  /**
   * A verdict plus the resolved file it was computed from (`None` whenever the
   *  value did not resolve to an existing regular file, or the verdict is one the
   *  inline leg will not act on) plus, when the raw reference did not name the
   *  file but a decoded form did, the disclosure note (imgref batch 2026-09-18 —
   *  作者失败①).
   */
  private case class RefVerdict(decision: RefDecision, path: Option[Path], note: Option[String] = None)

  /**
   * One scanned reference: where it sits in the document, the verdict, the
   *  resolved file (carried for a `Proxy` **and** for a refusal the inline leg
   *  may take over — see [[embedLocalFiles]]) and whether its face may embed
   *  bytes.
   *
   *  `resourceFace` is the only difference between the five scan patterns
   *  inside [[embedLocalFiles]].
   */
  private case class ScannedRef(
    start: Int,
    end: Int,
    decision: RefDecision,
    path: Option[Path],
    resourceFace: Boolean,
    note: Option[String] = None
  )

  /**
   * Split a `srcset` value into its image candidates and locate each URL token
   * inside the original string, so descriptors (`2x`, `640w`) survive the
   * rewrite. Grammar: `URL [descriptor]` separated by commas (HTML spec).
   *
   * Registered boundary: an unescaped comma is legal inside a `data:` URL — the
   * one place a comma split is ambiguous — so a `srcset` value containing
   * `data:` is skipped whole (never rewritten, never warned) instead of
   * guessed at.
   */
  private def srcsetCandidates(m: scala.util.matching.Regex.Match): List[ScannedRef] =
    val raw = m.group(1)
    if raw.toLowerCase.contains("data:") then Nil
    else
      val base = m.start(1)
      val out = scala.collection.mutable.ListBuffer.empty[ScannedRef]
      var cursor = 0
      raw.split(",", -1).foreach { candidate =>
        val lead = candidate.indexWhere(ch => !ch.isWhitespace)
        if lead >= 0 then
          val rest = candidate.substring(lead)
          val urlLen = rest.indexWhere(ch => ch.isWhitespace) match
            case -1 => rest.length
            case i => i
          if urlLen > 0 then
            val start = base + cursor + lead
            val value = rest.substring(0, urlLen)
            val scanned = decideRef(value)
            // Each srcset candidate is its own RESOURCE face (an image the
            // browser may pick), evaluated independently in the inline pass.
            out += ScannedRef(start, start + urlLen, scanned.decision, scanned.path, resourceFace = true, scanned.note)
        cursor += candidate.length + 1
      }
      out.toList

    end if

  end srcsetCandidates

  /**
   * Drop (never merge) any span that an earlier accepted span already covers.
   *
   * Spans arrive in document order. Two scan patterns can genuinely overlap (a
   * `url(...)` nested inside an `src=` value that is itself proxyable), and
   * splicing overlapping ranges would either duplicate text or cut it in half —
   * so the later span is discarded and the earlier one wins.
   */
  private[tools] def nonOverlapping[A](spans: List[(Int, Int, A)]): List[(Int, Int, A)] =
    val out = scala.collection.mutable.ListBuffer.empty[(Int, Int, A)]
    var lastEnd = -1
    spans.foreach { span =>
      val (start, end, _) = span
      if start >= lastEnd then
        out += span
        lastEnd = end
    }
    out.toList

  /**
   * Scan HTML for local file references and replace the ones that can be
   * proxied with /api/nf-file?path=... URLs.
   *
   * Scan face — `src=`, `href=`, every `srcset` candidate URL, every CSS
   * `url(...)` token and a bare `@import "…"` (2026-09-11 toolfail batch
   * widened it from the first two; the forms that are deliberately still out
   * of face are registered in the batch report with their reasons).
   *
   * Rejected references keep their raw value (a broken reference must not take
   * the whole card down) but are returned in `EmbedOutcome.rejects` so the
   * caller can report them. Matches from all five patterns are merged in
   * document order — the original src-then-href concatenation spliced a
   * later-listed href before an earlier src and threw on such cards — and an
   * overlap guard drops (never merges) a range that an earlier accepted
   * replacement already covers, because splicing overlapping ranges would
   * corrupt the document.
   */
  private def embedLocalFiles(html: String): EmbedOutcome =
    val matches: List[ScannedRef] =
      (SrcAttrRegex
        .findAllMatchIn(html)
        .map(m =>
          val scanned = decideRef(m.group(1))
          ScannedRef(m.start(1), m.end(1), scanned.decision, scanned.path, resourceFace = true, scanned.note)
        ) ++
        HrefAttrRegex
          .findAllMatchIn(html)
          .map(m =>
            val scanned = decideRef(m.group(1))
            ScannedRef(m.start(1), m.end(1), scanned.decision, scanned.path, resourceFace = false, scanned.note)
          ) ++
        SrcsetAttrRegex.findAllMatchIn(html).flatMap(srcsetCandidates) ++
        CssUrlRegex
          .findAllMatchIn(html)
          .map(m =>
            val scanned = decideRef(m.group(2))
            ScannedRef(m.start(2), m.end(2), scanned.decision, scanned.path, resourceFace = true, scanned.note)
          ) ++
        ImportBareRegex
          .findAllMatchIn(html)
          .map(m =>
            val scanned = decideRef(m.group(2))
            ScannedRef(m.start(2), m.end(2), scanned.decision, scanned.path, resourceFace = false, scanned.note)
          )).toList
        .sortBy(_.start)

    val exempts = matches.count { case ScannedRef(_, _, RefDecision.Exempt(_), _, _, _) => true; case _ => false }
    // imgref batch: one disclosure line per DISTINCT decoded-form hit ("which
    // form was used"), deduped — a document that repeats one space-bearing
    // reference must not repeat the note N times.
    val notes = matches.collect { case ScannedRef(_, _, _, _, _, Some(n)) => n }.distinct

    // The refusals the scan produced. The FINAL `rejects` list is computed after
    // the inline pass: a refusal the inline leg takes over did not fail
    // (返工 r2 — see below).
    val rejectsAtProbe: List[RejectedRef] =
      matches.collect { case ScannedRef(_, _, RefDecision.Reject(rejected), _, _, _) => rejected }

    // The filter order is deliberate: `nonOverlapping` runs BEFORE the inline
    // pass, exactly as it used to run before the splice, so (a) the counters
    // keep their shipped meaning (they describe the spans that are actually
    // rewritten, not every candidate), and (b) a span the overlap guard drops
    // can no longer spend budget it does not use.
    //
    // 返工 r2 (2026-09-18, 复核位 F1): a span is a rewrite candidate when it got
    // a URL **or** when it is a refusal the inline leg may take over (endpoint
    // refusal on the namespace REACH layer of a non-credential file — the
    // shipped code inlined such files; only their /api/nf-file URL was
    // unretrievable). The second kind is embedded or nothing: it never gets a
    // URL, so it can never reappear in `proxied`.
    val rewriteSpans: List[(Int, Int, ScannedRef)] = nonOverlapping(
      matches.collect {
        case ref @ ScannedRef(_, _, _: RefDecision.Proxy, _, _, _) => (ref.start, ref.end, ref)
        case ref @ ScannedRef(_, _, RefDecision.Reject(rejected), Some(p), _, _)
            if ref.resourceFace && FileRefs.inlineMayTakeOver(p, rejected) =>
          (ref.start, ref.end, ref)
      }
    )

    // ── the inline pass (2026-09-16 img-ticket batch i, #687-C) ─────────────
    // ONE budget per tool call (a Card call produces one card payload), spent in
    // document order, first come first served: an eligible image is charged its
    // exact `data:` URI length when the remaining balance covers it, and left on
    // the reference leg — charged nothing — when it does not. See
    // `FileRefs.MaxInlinePayloadChars` for the rule and its user-visible
    // semantics; a refusal here is NOT a warning (the `/api/nf-file` URL still
    // renders), it only moves the reference from `inlined` to `deferred`.
    val budget = InlineBudget()
    var budgetDeferred = 0
    var inlined = 0
    var proxied = 0
    // Refusals the inline leg actually took over — they must NOT be reported as
    // failures (nothing failed: the bytes are in the payload).
    val takenOver = scala.collection.mutable.ListBuffer.empty[RejectedRef]
    val replacements: List[(Int, Int, String)] = rewriteSpans.flatMap { (start, end, ref) =>
      val attempt: Either[InlineSkip, String] =
        if !ref.resourceFace then Left(InlineSkip.NotEmbeddable)
        else
          ref.path match
            case None => Left(InlineSkip.NotEmbeddable)
            case Some(p) => embedImage(p, budget)
      attempt match
        case Right(dataUri) =>
          inlined += 1
          ref.decision match
            case RefDecision.Reject(rejected) => takenOver += rejected
            case _ => ()
          Some((start, end, dataUri))
        case Left(skip) =>
          ref.decision match
            // A real URL was emitted for a reference the endpoint agrees it can
            // serve (probeFile checked the whole ladder) — this is `proxied`.
            // Missing the inline budget is not a defect: the URL still renders.
            case RefDecision.Proxy(url) =>
              if skip == InlineSkip.OverBudget then budgetDeferred += 1
              proxied += 1
              Some((start, end, url))
            // Nothing was embedded and there is no URL to emit: the endpoint's
            // refusal stands, so the raw value stays in the markup and the
            // rejection is reported (`failed` + a `warnings` entry with a fix).
            case _ => None
      end match
    }
    // One entry per distinct rejected reference, minus the ones the inline leg
    // took over. (The counters keep their shipped split: `proxied` = "an
    // /api/nf-file URL was emitted (and the endpoint would serve it)", `inlined`
    // = "the bytes ride in the payload".)
    val rejects: List[RejectedRef] = rejectsAtProbe.filterNot(takenOver.contains)

    val rewritten =
      if replacements.isEmpty then html
      else
        val sb = new StringBuilder(html.length + replacements.size * 128)
        var cursor = 0
        for (start, end, replacement) <- replacements do
          sb.append(html.substring(cursor, start))
          sb.append(replacement)
          cursor = end
        sb.append(html.substring(cursor, html.length))
        sb.toString

    if rewritten != html then
      logger.debug(
        s"Embedded ${proxied} local file(s) via /api/nf-file, ${inlined} image(s) inline" +
          (if budgetDeferred > 0 then s", $budgetDeferred deferred by the ${budget.maxChars}-char inline budget"
           else "")
      )
    EmbedOutcome(rewritten, proxied, inlined, budgetDeferred, rejects, exempts, notes)
  end embedLocalFiles

  /**
   * The sentinel prefix the frontend splits the JSON payload on (cardRegistry.js
   *  `^___\w+_HTML___`); nothing may be appended after the JSON.
   */
  private val CardSentinel = "___CARD_HTML___"

  /**
   * One integer counter out of an already-built card result's `fileRefs`.
   *
   * 2026-09-16 (imgticket batch ii): reads **either** face — the raw card
   * payload (sentinel-prefixed, the frontend face) **or** the model-facing
   * summary this tool now produces (a plain JSON object that keeps `fileRefs`
   * and `warnings`). Without that, the chat header would silently lose its
   * "N file reference(s) NOT proxied" note the moment the model face stopped
   * being the payload (`AgentCore` computes the header from the model face).
   */
  private def fileRefCount(result: String, field: String): Int =
    val json =
      if result.startsWith(CardSentinel) then result.substring(CardSentinel.length)
      else result
    io.circe.parser
      .parse(json)
      .toOption
      .flatMap(_.asObject)
      .flatMap(_.apply("fileRefs"))
      .flatMap(_.asObject)
      .flatMap(_.apply(field))
      .flatMap(_.asNumber)
      .flatMap(_.toInt)
      .getOrElse(0)

  private def unresolvedFileRefs(result: String): Int = fileRefCount(result, "failed")

  /**
   * App-route references the exemption suppressed — surfaced in the chat
   *  header whenever it is non-zero, so a suppressed reference is never
   *  invisible (see `FileRefs.applyAppRouteExemption`).
   */
  private def exemptFileRefs(result: String): Int = fileRefCount(result, "exempt")

  val name = "Card"

  /**
   * Path to user-editable card design prompt — derived from the **data root**
   *  on every access, never from `sys.props("user.home")`.
   *
   * This was the last hardcoded-home path on the tool surface: an isolated
   *  instance (`--home`, tests, a second instance) read and auto-created the
   *  prompt in the REAL home while everything else followed the swapped root
   *  (`paths.scala:206-211`). `def`, not `val`: `PathUtil.dataRoot` may be
   *  re-rooted after this object is initialized (`setDataRoot` / `--home`), and
   *  a `val` would freeze the first root for the process lifetime.
   */
  private def designPromptPath: Path = nebflow.shared.PathUtil.dataRoot.toNIO.resolve("card-design-prompt.md")

  /**
   * Cached design prompt: (source path, mtime) → content.
   *
   * The path is part of the key on purpose — an mtime alone cannot tell two
   *  different data roots apart, so a root swap would keep serving the previous
   *  root's prompt whenever both files happened to share an mtime.
   */
  @volatile private var designPromptCache: (String, Long, String) = ("", 0L, "")

  /**
   * Default design guidelines — written to disk on first access if file doesn't exist.
   *
   * `def` + s-interpolation（home 硬编码 → 运行时动态化批 2026-09-11）：路径示例
   * 里的 `{{data_root}}` 由 PathUtil.dataRootRenderValue 插值——默认 home 渲染为
   * `~/.nebflow`（字节与旧字面一致），隔离实例渲染为本实例 home 的绝对路径。
   * `def` on purpose：dataRoot 可在对象初始化后被换根（--home / 测试）。
   */
  private def defaultDesignPrompt: String =
    s"""## Card Visual Design Guidelines

Follow these strictly. They override any conflicting defaults.

### Color: always use CSS variables

**Never hardcode hex colors.** Always use `var(--color-text)` for body text, `var(--color-bg)`/`var(--color-surface)` for backgrounds. These guarantee maximum contrast in both light and dark mode. Use `var(--color-primary/success/error/warning)` only for status indicators. `var(--color-text-muted)` is for captions only — too low contrast for body text.

### Generated images: two SVG embedding strategies

**Never embed raster images (PNG/JPG) via `<img>`** — they have hardcoded colors that cannot adapt to dark mode. Always generate SVG output instead.

There are two ways to embed SVG, each with trade-offs:

#### Strategy A: `<img src>` — simpler, more reliable (default choice)

```
dot -Tsvg -o /tmp/output.svg input.dot
```
```html
<img src="/tmp/output.svg" style="width:100%;height:auto;display:block" alt="Diagram"/>
```

- Browser handles SVG scaling natively — dimensions always correct.
- SVG is self-contained: graphviz/matplotlib already set correct text colors per node (dark bg = white text, light bg = dark text).
- Works reliably in sandboxed iframe / shadow DOM environments.
- Does NOT support dark-mode CSS overrides (SVG is rendered as an image, CSS variables don't penetrate). If dark mode is important, use Strategy B.

#### Strategy B: inline SVG — enables dark-mode CSS overrides

1. Generate output as **SVG format**
2. Read the SVG file, strip `<?xml?>` and `<!DOCTYPE>` declarations
3. Replace `width="..."` attribute with `width="100%"`, remove `height` attribute (let viewBox control aspect ratio)
4. **Paste the SVG directly into the HTML** — do NOT wrap in an outer `<svg>`
5. Add a `<style>` block with CSS overrides

**Warning:** Inline SVG may render at incorrect sizes in some sandboxed environments. If the card appears too small, switch to Strategy A.

**CSS override template — graphviz diagrams:**

```html
<div class="gv-diagram">
  <svg width="100%" viewBox="...">...</svg>
</div>
<style>
.gv-diagram svg { width: 100%; height: auto; display: block; }
/* Hide graphviz white background */
.gv-diagram svg > g > polygon { fill: transparent !important; }
/* Edge labels follow theme — do NOT override .node text, graphviz sets correct contrast per node */
.gv-diagram .edge text { fill: var(--color-text-muted) !important; }
/* Edges: no fill, muted stroke */
.gv-diagram .edge path { fill: none !important; stroke: var(--color-text-muted) !important; }
/* Arrowheads */
.gv-diagram .edge polygon { fill: var(--color-text-muted) !important; stroke: none !important; }
</style>
```

**CSS override template — matplotlib charts:**

```html
<div class="mpl-chart">
  <svg>...</svg>
</div>
<style>
.mpl-chart svg { width: 100%; height: auto; }
/* All text follows theme */
.mpl-chart text { fill: var(--color-text) !important; }
/* Override black axis lines, ticks, spines — data lines keep original colors */
.mpl-chart path[style*="#000000"] { stroke: var(--color-text-muted) !important; }
.mpl-chart use[style*="#000000"] { stroke: var(--color-text-muted) !important; }
/* Legend/patch borders */
.mpl-chart path[style*="fill: #ffffff"], .mpl-chart path[style*="fill: white"] {
  fill: var(--color-surface) !important;
}
</style>
```

**Key principle:** CSS `!important` in a `<style>` block overrides both SVG presentation attributes and inline `style="..."` on SVG elements — so the theme variables always win.

**When raster (PNG/JPG) is unavoidable** (photos, screenshots, complex renders): wrap in a container with `var(--color-bg)` background and add `border-radius`. Do NOT apply CSS filters (invert etc.) — they distort colors unpredictably.

### Visual defaults

- **No emoji.** Use typography and spacing for visual interest.
- **Rounded corners:** cards 16px, buttons 10px, inputs 8px, badges 9999px.
- **Font:** `-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Helvetica Neue", sans-serif`
- Body 13-14px / 400, headings 16-20px / 600, tight letter-spacing on headings.
- Animations only if they aid understanding (200-400ms, ease-out for entrance). Respect prefers-reduced-motion.

### Embedding external content

HTML must be self-contained (all styles/tags inline, no external CSS/JS).

Local file paths in `src`/`href` are proxied by the backend to `/api/nf-file`, so **you MUST use absolute paths** — `/Users/you/project/plot.png`, `/tmp/output.svg`, `C:\\Users\\you\\project\\plot.png` (a Windows drive path; either separator works — `C:/Users/you/project/plot.png` too), or `${nebflow.shared.PathUtil.dataRootRenderValue}/projects/<name>/reports/plot.svg`. `~` expands to the user's home directory, and project workspaces live under `${nebflow.shared.PathUtil.dataRootRenderValue}/projects/<name>/` — write that full path, not `~/projects/<name>/…`. Relative paths are never resolved — that includes a drive-relative `C:plot.png`; Windows UNC references (`\\\\server\\share\\…`) are not resolved either.

**Images are embedded, not referenced** (2026-09-16): a local `png`/`jpg`/`jpeg`/`gif`/`webp`/`svg`/`bmp` referenced by `src=`, a `srcset` candidate or a CSS `url(...)` is embedded in the card as a base64 `data:` URI when it is ≤5MB — it renders with no request at all, and keeps rendering on replay. Inline bytes are capped **per card in TOTAL**: at most 40,000 characters of `data:` URI (≈30 KB of source bytes) go inline in one card, counted in document order — once that budget is spent, further images of the same card keep the `/api/nf-file?path=…` reference instead (they still render, but need the ticket below). Everything else (larger images, video/audio/fonts/PDF/office/CSS/JS) is referenced as `/api/nf-file?path=…` and needs a per-path ticket the gateway mints at render time; the gateway serves the path only if its credential-namespace policy allows it — the data directory serves `${DataRootServedNamespacesText}` and the project `.nebflow/` serves `evidence*/**`. A >5MB image or a non-image asset in a location the gateway does not serve cannot be shown. To show such a file, put it under one of the served locations above — `projects/**` is the usual route, but not the only one: a path outside the data directory and the project `.nebflow/` stays servable where it is (an absolute `/tmp/output.svg` renders), as long as it is not credential-shaped. (Shrinking the image below 5MB also works.)

Every reference that could not be proxied is reported in this tool's result under `warnings` (`ref` → `resolvedPath` → `reason`: not-found / unresolvable / extension-not-allowed / size-exceeded / not-regular-file / not-readable / not-servable, plus `fileRefs` counts) and renders as a visible placeholder in the card instead of a silent blank box. Scanned: `src=`, `href=`, every `srcset` candidate, every CSS `url(...)`, a bare `@import "..."`. The app's own routes (`/js/`, `/css/`, `/assets/`, `/vendor/`, `/uploads/`, `/agents/`, `/voice-models/`, plus `/style.css` `/app.js` `/logo.svg` `/favicon.*`) are exempt — the app serves them, not the disk — and are counted in `fileRefs.exempt` instead of being reported. Read `warnings` and fix the references before finishing. A path containing spaces is fine and needs no special spelling: write it as it is on disk (the server reads a bare `+` in a URL's `path=` parameter as a space, and `%20` also works). If a reference you wrote used URL escapes or a `+` and the tool resolved it in the decoded form, `notes` in this result says so."""

  /**
   * Load the design prompt for the data root in force NOW (cached by
   *  path + mtime).
   *  Auto-creates with defaults on first access if the file doesn't exist.
   */
  private def loadDesignPrompt(): String =
    try
      val path = designPromptPath
      val key = path.toAbsolutePath.normalize.toString
      if !java.nio.file.Files.exists(path) then
        java.nio.file.Files.createDirectories(path.getParent)
        java.nio.file.Files
          .write(path, defaultDesignPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        designPromptCache = (key, java.nio.file.Files.getLastModifiedTime(path).toMillis, defaultDesignPrompt)
        defaultDesignPrompt
      else
        val mtime = java.nio.file.Files.getLastModifiedTime(path).toMillis
        if key != designPromptCache._1 || mtime != designPromptCache._2 then
          val content =
            new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
          designPromptCache = (key, mtime, content)
          content
        else designPromptCache._3
    catch case _: Exception => designPromptCache._3

  /**
   * Base description without user design prompt.
   *
   * `def` + s-interpolation（home 硬编码 → 运行时动态化批 2026-09-11）：路径示例
   * 走 PathUtil.dataRootRenderValue（默认 home ⇒ `~/.nebflow`，隔离实例 ⇒ 实例
   * 绝对路径）。`def` on purpose：dataRoot 可被换根，val 会在对象初始化时冻结。
   */
  private def baseDescription =
    s"""Renders an interactive HTML card embedded in the chat.

## Frontend / Design Change Protocol — MANDATORY

When a task involves **frontend or visual design changes** (CSS, UI layout, styling, color schemes, component design, icon changes), you MUST use Card to render a visual preview of the proposed result BEFORE writing the actual code changes. The workflow is:

1. Understand the design change requested
2. Use Card to create a visual mockup or preview showing the intended result
3. Wait for the user to confirm the design direction
4. Only then proceed with implementation

This applies to ALL UI changes, no matter how small (color tweaks, spacing adjustments, new buttons, layout changes, etc.). Never write frontend code without first showing the user what it will look like via Card.

## Why use cards

Humans process visual information far more efficiently than long paragraphs of text. A well-chosen diagram conveys in a glance what would take paragraphs to explain. Use cards to present relationships, structure, and data visually — when a visual makes your answer clearer than words alone. If words alone are already clear, words alone are the answer.

Card is for **presenting** results, not for drawing them. Always generate images with professional tools first, then embed with Card.

## Use cases and counter-examples

### Do NOT use Card when:
- **Prose-only content**: status updates, progress notes, results, decisions, task lists, plans, and answers are text — write them as text. A card whose content is only sentences is a downgrade: it costs a render, the text leaves the scrollback and can no longer be searched or quoted in place, and the reader must open it to read what they could have read inline.
- **Re-rendering**: never restate in a card something you have already written in the chat text.
- **No meaning-carrying visual**: if you cannot say which structure, which data, or which interaction the card adds, there is nothing to render — do not use Card.
Rule of thumb: **if the card would contain only sentences, do not use Card.** The five "Use Card when" cases below are the whole whitelist; anything outside them is text.

### Use Card when:
- **Spatial structure**: architecture diagrams, flowcharts, org charts, network topologies
- **Data visualization**: charts, plots, heatmaps, spectra, histograms
- **Interactive elements**: clickable prototypes, parameter sliders, step-through animations
- **Side-by-side comparison**: image galleries, before/after grids, annotated screenshots
- **Embedding generated media**: plots from matplotlib, diagrams from graphviz, 3D models

### Common mistakes — the right way vs the wrong way:
- **Charts, curves, spectra**: do NOT draw with ASCII art or hand-write SVG. Use matplotlib/gnuplot → output SVG → inline with CSS override.
- **Flowcharts, architecture diagrams**: do NOT approximate with text boxes and arrows. Use graphviz/mermaid → output SVG → inline with CSS override.
- **Circuit schematics, timing diagrams**: do NOT hand-draw with SVG `<path>`. Use schemdraw/wavedrom → output SVG → inline with CSS override.
- **Any content involving data, proportions, or precise shapes**: do NOT guess coordinates in SVG. Use a professional tool — always.
- **Prose**: do NOT render a status update, a result list or an explanation as a card. If it is sentences, it is text.

**Important:** Never embed diagrams/charts as PNG/JPG via `<img>` — they have hardcoded colors that break dark mode. Generate SVG output instead. You can embed SVG two ways: `<img src="/tmp/output.svg">` (simpler, more reliable sizing, no dark-mode CSS) or inline the SVG content (enables dark-mode CSS overrides). See the design guidelines below for details and CSS templates.

## Professional tool correspondence table

| Scenario | Recommended tool |
|----------|-----------------|
| **Charts & plots** (line, bar, scatter, heatmap) | matplotlib, gnuplot, ROOT, plotly |
| **Scientific plots** (contour, vector field, polar, 3D surface) | matplotlib, plotly |
| **Flowcharts & block diagrams** | graphviz (dot), mermaid-cli |
| **Architecture diagrams & network topologies** | graphviz, networkx + matplotlib |
| **UML (class / sequence / state)** | plantuml, mermaid |
| **Timing diagrams** | wavedrom |
| **Circuit schematics** | schemdraw (Python) |
| **PCB layouts & cross-sections** | KiCad/Eagle export, matplotlib patches |
| **Frequency spectra / Bode plots / eye diagrams** | matplotlib + scipy |
| **Smith charts** | matplotlib (smithplot) |
| **Molecular structures** | rdkit, OpenBabel |
| **Crystal structures** | pymatgen, ASE |
| **Maps & spatial distributions** | folium, cartopy + matplotlib |
| **3D models** | OpenSCAD CLI, matplotlib 3D |
| **Gantt charts / timelines** | matplotlib, plotly, mermaid |
| **Sankey diagrams / treemaps / radar charts** | plotly, squarify |

## Workflow

1. Have the kernel create the material you need — a simple one-off execution task like this is routed with `Mail(address="kernel", message=...)`: the brief must be fully self-contained, and the kernel produces files via its Bash tool face. Material that needs plugin-backed production still goes to a project with the matching capability.
2. Embed the material it returns into the Card you show the user.
3. Embed the SVG in the Card — two options:
   - **Simple:** `<img src="/tmp/output.svg" style="width:100%;height:auto">` (recommended default)
   - **Dark-mode CSS:** Read the SVG file, strip `<?xml?>`/`<!DOCTYPE>`, replace `width` with `width="100%"`, inline the SVG content, add `<style>` CSS overrides (see templates below)

## Persistence

Every card you render is also written to disk, as `${nebflow.shared.PathUtil.dataRootRenderValue}/cards/`, named `yyyyMMdd_HHmmss_<title>.html` (a second card in the same second gets a `-1`, `-2`, … suffix — nothing is ever overwritten). The bytes written are the **render source** exactly as supplied: what you passed as `html`, or the file's own content on the `filePath` leg — never the rewritten markup, so the saved file is the thing you would edit. The result carries a `persist` field: `{"ok": true, "path": "<absolute path>"}` on success, `{"ok": false, "reason": "…"}` when the write failed — a failed write never affects the rendered card. To change a card later, `Edit` its saved file and render that path with `filePath`.

## Rendering from a file

Pass `filePath` instead of `html` to render a local `.html`/`.htm` file (at most 2MB; `~` expands to the user's home directory). The file's content becomes the card's HTML and is then processed exactly like an `html` argument — same reference rewriting, same `warnings`/`fileRefs`. The two parameters are **mutually exclusive**: pass exactly one. The path must name a real regular `.html`/`.htm` file no larger than 2MB that lies inside one of the two trees Card reads card sources from — the data root and this project's `.nebflow/` directory — the whole of each tree, which is why a source saved to `<dataRoot>/cards/` can be read back. Credentials and credential-shaped paths are refused anywhere, by the endpoint's own credential judges; nothing outside those two trees is read. A refusal names the reason; the file's content is never echoed back.

## Parameters

- html (string): HTML with CSS and JS. Dark mode via var(--color-*). Mutually exclusive with `filePath`.
- filePath (string): absolute path to a local `.html`/`.htm` file (at most 2MB) whose content is rendered as the card — mutually exclusive with `html`. `~` expands to the user's home directory.
- title (string, optional): title above card. Also the base of the saved file's name.

Note: Local file paths in `src`/`href` are proxied by the backend to `/api/nf-file`, so **you MUST use absolute paths** — `/Users/you/project/plot.png`, `/tmp/output.svg`, `C:\\Users\\you\\project\\plot.png` (a Windows drive path; either separator works — `C:/Users/you/project/plot.png` too), or `${nebflow.shared.PathUtil.dataRootRenderValue}/projects/<name>/reports/plot.svg`. `~` expands to the user's home directory, and project workspaces live under `${nebflow.shared.PathUtil.dataRootRenderValue}/projects/<name>/` — write that full path, not `~/projects/<name>/…`. Relative paths are never resolved, on either leg — a card has no containing directory, so even when the HTML came from a file, a relative `src` is left alone and reported; that includes a drive-relative `C:plot.png`; Windows UNC references (`\\\\server\\share\\…`) are not resolved either. Local images ≤5MB (`png`/`jpg`/`jpeg`/`gif`/`webp`/`svg`/`bmp`) are embedded as base64 `data:` URIs, so they need no request — up to a TOTAL of 40,000 characters of `data:` URI per card, spent in document order (images past that total keep the `/api/nf-file?path=…` reference); every other reference needs a ticket the gateway mints only for paths its credential-namespace policy serves (data root: `${DataRootServedNamespacesText}`; project `.nebflow/`: `evidence*/`).

Every reference that could not be proxied is reported in this tool's result under `warnings` (`ref` → `resolvedPath` → `reason`: not-found / unresolvable / extension-not-allowed / size-exceeded / not-regular-file / not-readable / not-servable / other, plus `fileRefs` counts) and renders as a visible placeholder in the card instead of a silent blank box. Read `warnings` and fix the references before finishing. A path containing spaces is fine and needs no special spelling: write it as it is on disk (the server reads a bare `+` in a URL's `path=` parameter as a space, and `%20` also works). If a reference you wrote used URL escapes or a `+` and the tool resolved it in the decoded form, `notes` in this result says so.

Example (graphviz SVG via img — recommended default):
{"html":"<img src=\"/tmp/output.svg\" style=\"width:100%;height:auto;display:block\" alt=\"Architecture\"/>","title":"Architecture"}

Example (graphviz SVG inline with dark mode CSS):
{"html":"<div class=\"gv-diagram\"><svg width=\"100%\" viewBox=\"0 0 200 100\">...</svg></div><style>.gv-diagram svg{width:100%;height:auto;display:block}.gv-diagram .edge text{fill:var(--color-text-muted)!important}.gv-diagram .edge path{fill:none!important;stroke:var(--color-text-muted)!important}.gv-diagram .edge polygon{fill:var(--color-text-muted)!important;stroke:none!important}</style>","title":"Architecture"}

Example (matplotlib SVG inline):
{"html":"<div class=\"mpl-chart\"><svg viewBox=\"0 0 432 216\">...</svg></div><style>.mpl-chart svg{width:100%;height:auto}.mpl-chart text{fill:var(--color-text)!important}.mpl-chart path[style*=\"#000000\"]{stroke:var(--color-text-muted)!important}</style>","title":"My Plot"}

Example (SVG diagram):
{"html":"<div style=\"font-family:sans-serif;padding:16px\"><svg viewBox=\"0 0 600 200\" style=\"width:100%\"><rect x=\"10\" y=\"60\" width=\"120\" height=\"60\" rx=\"8\" fill=\"var(--color-primary)\"/><text x=\"70\" y=\"96\" text-anchor=\"middle\" fill=\"white\" font-size=\"16\">Client</text><rect x=\"180\" y=\"60\" width=\"120\" height=\"60\" rx=\"8\" fill=\"var(--color-primary)\"/><text x=\"240\" y=\"96\" text-anchor=\"middle\" fill=\"white\" font-size=\"16\">Server</text></svg></div>","title":"TCP"}

Example (interactive 3D with Three.js):
{"html":"<div style=\"padding:0\"><script src=\"https://cdn.jsdelivr.net/npm/three@latest/build/three.min.js\"></script><canvas id=\"c\" style=\"width:100%;height:400px;display:block\"></canvas><script>const s=new THREE.Scene();const c=document.getElementById('c');const r=new THREE.WebGLRenderer({canvas:c,antialias:true});r.setSize(c.clientWidth,400);const cam=new THREE.PerspectiveCamera(75,c.clientWidth/400,0.1,1000);cam.position.z=3;s.add(new THREE.Mesh(new THREE.SphereGeometry(1,32,32),new THREE.MeshNormalMaterial()));function f(){requestAnimationFrame(f);r.render(s,cam)}f()</script></div>","title":"3D Sphere"}

Example (render a card from a file on disk — including one you saved earlier and edited):
{"filePath": "/tmp/card-source.html", "title": "Architecture"}"""

  /** Dynamic description: base tool description + user design prompt (always present after auto-init). */
  def description: String =
    s"$baseDescription\n\n${loadDesignPrompt()}"

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "html" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "HTML content to render in the card. Mutually exclusive with `filePath`.".asJson
        ),
        "filePath" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> ("Absolute path to a local `.html`/`.htm` file (supports `~` expansion, at most 2MB) whose "
            + "content is rendered as the card. Mutually exclusive with `html`.").asJson
        ),
        "title" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional title shown above the card".asJson
        )
      ),
      // `html` is no longer statically required: a caller may name the source
      // with `filePath` instead. The mutual exclusion is enforced in `call`
      // (the provider surfaces carry no runtime schema evaluation), so a call
      // that gives neither is still refused there with an actionable message.
      "required" -> Json.arr()
    )
  )

  // ── persistence (rendering a card also writes its render source to disk) ──

  /** `cards/` under the data root — the same root everything else follows. */
  private def cardsDir: Path = nebflow.shared.PathUtil.dataRoot.toNIO.resolve("cards")

  private val CardFileStamp = java.time.format.DateTimeFormatter
    .ofPattern("yyyyMMdd_HHmmss")
    .withZone(java.time.ZoneId.systemDefault())

  /** Extension face for a file a card may be rendered FROM — Card's own. */
  private val RenderableCardExtensions: Set[String] = Set("html", "htm")

  /**
   * Read ceiling for the `filePath` leg (2MB) — the Pop text-file precedent
   *  (`PopTool.MaxTextSize`). Deliberately NOT `FileRefs.MaxFileSize` (200MB):
   *  that one bounds what the browser may FETCH by reference, not what a tool
   *  copies into its own payload.
   */
  private val MaxRenderSourceBytes: Long = 2L * 1024 * 1024

  /**
   * Filesystem name for one render source: `yyyyMMdd_HHmmss_<slug>[-<n>].html`.
   *
   * The slug comes from the card title (empty ⇒ `card`), keeps only
   *  `[A-Za-z0-9._-]` and is truncated to 60 characters. A same-second second
   *  render must produce a SECOND file, never overwrite the first: the `-n`
   *  suffix is added until the name is free (`Files.exists` probe).
   */
  private def cardFileName(stamp: String, title: String): String =
    val slug =
      val kept = title.filter(c => c.isLetterOrDigit && c < 128 || c == '.' || c == '_' || c == '-')
      if kept.isEmpty then "card" else kept.take(60)
    var n = 0
    var candidate = s"${stamp}_$slug.html"
    while java.nio.file.Files.exists(cardsDir.resolve(candidate)) do
      n += 1
      candidate = s"${stamp}_$slug-$n.html"
    candidate

  /**
   * Write the **render source** — the exact bytes the card was rendered from —
   *  to `<dataRoot>/cards/`, and report what happened as the payload's
   *  `persist` object.
   *
   * 硬门槛（A3）：verbatim, zero transformation. The persisted bytes are what
   * the caller supplied (the `html` parameter as received, BEFORE
   * [[embedLocalFiles]] rewrote any reference, or the file's own bytes on the
   * `filePath` leg). Persisting the REWRITTEN markup would hand the author a
   * machine string (proxy URLs, base64 blocks) instead of the thing they edit,
   * and re-rendering it would inline the already-inlined images a second time.
   *
   * 旁路副作用（A4）：every failure is caught. A card whose bookkeeping failed
   * must still render — the caller gets the ordinary payload plus
   * `"persist":{"ok":false,"reason":"…"}`, never a `Left`.
   */
  private def persistCardSource(source: String, title: String): Json =
    try
      java.nio.file.Files.createDirectories(cardsDir)
      val name = cardFileName(java.time.LocalDateTime.now().format(CardFileStamp), title)
      val path = cardsDir.resolve(name)
      java.nio.file.Files.write(path, source.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      Json.obj("ok" -> true.asJson, "path" -> path.toAbsolutePath.normalize.toString.asJson)
    catch
      case e: Throwable =>
        val reason = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
        logger.warn(s"Card: could not persist the render source to $cardsDir (${e.getClass.getSimpleName}: $reason)")
        Json.obj("ok" -> false.asJson, "reason" -> s"${e.getClass.getSimpleName}: $reason".asJson)

  /**
   * The directories this server reads a card source from: the **data root**
   *  and the **project `.nebflow` directory** — the two namespaces the file
   *  endpoint speaks about, taken from the filesystem rather than from its
   *  served-subtree allowlist (see [[readCardSource]] for why the read face is
   *  deliberately wider than the endpoint's served subset).
   */
  private def cardSourceRoots: List[Path] =
    List(nebflow.shared.PathUtil.dataRoot.toNIO, java.nio.file.Paths.get(os.pwd.toString).resolve(".nebflow"))
      .map(p =>
        try p.toRealPath()
        catch case _: Throwable => p.toAbsolutePath.normalize
      )

  /**
   * Read a card's render source from disk for the `filePath` leg.
   *
   * Five gates, in this order, each refusing with a reason the caller can act
   *  on (and 🔴 none of them ever echoes the file's content):
   *
   *  1. **Shape** — a non-empty string naming a real regular file.
   *  2. **Size** — ≤ [[MaxRenderSourceBytes]]. Checked BEFORE any read, so an
   *     oversized file is never loaded to find out that it is oversized.
   *  3. **Extension** — taken from the REAL path (a symlink cannot lend its
   *     name). Card's OWN face, defined here on purpose: the endpoint's table
   *     (`NfFileAllowedExt`) serves no `html`/`htm` at all, so borrowing it
   *     would refuse every legitimate card source.
   *  4. **Identity** — the endpoint's OWN judges, through the narrow
   *     `FilePolicyPort` (🔴 the same functions `/api/nf-file` uses — no second
   *     table, no mirrored whitelist, no copied inode snapshot), asked about
   *     the REAL path. Only the **credential identity** layers refuse:
   *     `Credential` (credential-shaped location or name) and `CredentialInode`
   *     (an inode belonging to a Nebflow credential file — a hard link to one,
   *     or a file inside the data root's credential subtrees, which is what
   *     that snapshot is built from). Its other two layers answer questions
   *     this leg does not ask — `Namespace` is the endpoint's REACH ("what may
   *     a browser fetch with a ticket"), and `FileType` is the endpoint's
   *     extension face, which gate 3 owns here.
   *
   *     Three questions, in this order, because the ladder's own order HIDES
   *     answers behind its reach branch: `endpointVerdictLayer` (the whole
   *     ladder) is asked first, but the ladder decides REACH before identity —
   *     any non-allowlisted subtree of the data root answers `Namespace` up
   *     front (`NfFilePolicy.nfCredentialDenyLayer`), so for such a path
   *     neither the credential tables nor the inode step below them are ever
   *     evaluated. That is the trap `FileRefs.scala:565-571` documents for the
   *     sibling inline caller, which compensates by re-asking. So for every
   *     outcome that is not already one of the two identity layers, this leg
   *     asks the path's OWN identity step (`credentialIdentityLayer` — the
   *     endpoint's own credential tables, `NfFilePolicy.nfCredentialIdentityLayer`)
   *     and then the inode step (`credentialInodeHit` — the endpoint's own
   *     `nfCredentialInode`). A `Namespace` answer from any of them is the
   *     endpoint's REACH and says nothing about the file's identity: this leg
   *     has its own reach rule (gate 5) and deliberately does not honour that
   *     layer. Asked BEFORE the location gate so that a credential path is
   *     refused for being a credential, not merely for being out of place.
   *  5. **Location** — the real path must lie inside the data root or the
   *     project `.nebflow` directory. This is the read face's own reach rule and
   *     it is deliberately WIDER than the endpoint's served subtrees
   *     (`NfDataRootAllowlist`): a card's saved source lives in
   *     `<dataRoot>/cards/`, which the endpoint does not serve and must not
   *     (the author kept `cards/` out of that allowlist) — a rule that refused
   *     it would make the "edit the saved file, render it again" loop
   *     impossible. A path outside both roots (say `/etc/…`) is refused.
   *
   * Fail-closed: a port that cannot be consulted (it is not wired, or the judge
   *  throws) refuses. Every message names the reason; the file's bytes and the
   *  credential path's own content are never echoed.
   */
  private def readCardSource(rawPath: String): Either[ToolError, String] =
    val supplied = rawPath.trim
    def refuse(reason: String): Either[ToolError, String] =
      Left(ToolError(s"Card: cannot render `filePath` — $reason. Supplied: `$supplied`"))
    if supplied.isEmpty then refuse("the path is empty")
    else
      val resolved =
        try Some(java.nio.file.Paths.get(nebflow.shared.PathUtil.expandTilde(supplied)).normalize())
        catch case _: Throwable => None
      resolved match
        case None => refuse("the path is not a usable filesystem path")
        case Some(path) =>
          try
            if !java.nio.file.Files.exists(path) then refuse(s"no file at ${path.toAbsolutePath.normalize}")
            else if !java.nio.file.Files.isRegularFile(path) then refuse("the path is not a regular file")
            else
              val size = java.nio.file.Files.size(path)
              if size > MaxRenderSourceBytes then
                refuse(s"$size bytes exceeds the ${MaxRenderSourceBytes / (1024 * 1024)}MB render-source limit")
              else
                val real = path.toRealPath()
                val ext = FileRefs.fileExtension(real.getFileName.toString)
                if !RenderableCardExtensions.contains(ext) then
                  refuse(
                    s"'.$ext' is not a renderable card source (only " +
                      RenderableCardExtensions.toList.sorted.map(e => s".$e").mkString(" / ") + ")"
                  )
                else
                  val identityRefusal: Option[String] =
                    try
                      // Layer 1 — the whole ladder, in its own order. Its
                      // IDENTITY outcomes (Credential / CredentialInode) refuse
                      // here; its other two layers (Namespace = the endpoint's
                      // reach, FileType = gate 3's own face here) answer
                      // questions this leg does not ask, so they fall through.
                      val fromLadder =
                        FilePolicyPort.port.endpointVerdictLayer(real) match
                          case Some((FilePolicyPort.NfDenyLayer.Credential, reason, message)) =>
                            Some(s"$reason: $message — ${FileRefs.servabilityHint(reason)}")
                          case Some((FilePolicyPort.NfDenyLayer.CredentialInode, reason, message)) =>
                            Some(s"$reason: $message — ${FileRefs.servabilityHint(reason)}")
                          case Some((_, _, _)) | None => None
                      fromLadder match
                        case some @ Some(_) => some
                        case None =>
                          // Layer 2 — the ladder decides REACH before identity:
                          // it short-circuits at `Namespace` and stops, so for a
                          // non-allowlisted data-root subtree neither its
                          // credential tables nor its inode step were reached.
                          // Ask both directly — the endpoint's OWN credential
                          // tables (`NfFilePolicy.nfCredentialIdentityLayer`,
                          // the ladder's identity branch, one function, no copy)
                          // and the endpoint's own inode helper. The repo
                          // documents this exact trap for the sibling caller at
                          // `FileRefs.scala:565-571`, which compensates the same
                          // way.
                          //
                          // Only the IDENTITY layers refuse here, exactly as in
                          // layer 1: a `Namespace` answer is the endpoint's
                          // REACH and must NOT be honoured — this leg's own
                          // reach rule (gate 5) admits subtrees the endpoint
                          // does not serve, `cards/` itself among them, so
                          // refusing on `Namespace` here would break the
                          // "edit the saved file, render it again" loop.
                          val fromIdentity =
                            FilePolicyPort.port.credentialIdentityLayer(real) match
                              case Some((FilePolicyPort.NfDenyLayer.Credential, reason)) =>
                                Some(s"$reason: ${FileRefs.servabilityHint(reason)}")
                              case Some((FilePolicyPort.NfDenyLayer.CredentialInode, reason)) =>
                                Some(s"$reason: ${FileRefs.servabilityHint(reason)}")
                              case Some(_) | None => None
                          fromIdentity match
                            case some @ Some(_) => some
                            case None =>
                              if FilePolicyPort.port.credentialInodeHit(real) then
                                Some(
                                  "credential-hardlink: this file's bytes live in an inode that is one " +
                                    "of this deployment's credential files — " +
                                    FileRefs.servabilityHint("credential-hardlink")
                                )
                              else None
                    catch
                      case e: Throwable =>
                        Some(
                          "the credential judge could not be consulted " +
                            s"(${e.getClass.getSimpleName}) — the file is treated as unreadable " +
                            "rather than assumed readable"
                        )
                  identityRefusal match
                    case Some(reason) => refuse(reason)
                    case None =>
                      if !cardSourceRoots.exists(root => real.startsWith(root)) then
                        refuse(
                          "the file lies outside the locations this server reads card sources from " +
                            s"(${cardSourceRoots.map(_.toString).mkString(" and ")})"
                        )
                      else
                        Right(
                          new String(
                            java.nio.file.Files.readAllBytes(real),
                            java.nio.charset.StandardCharsets.UTF_8
                          )
                        )
          catch
            case e: Throwable =>
              val message = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
              refuse(s"the source could not be read (${e.getClass.getSimpleName}: $message)")

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val suppliedHtml = input("html").flatMap(_.asString).filter(_.nonEmpty)
    val suppliedFilePath = input("filePath").flatMap(_.asString).filter(_.nonEmpty)
    (suppliedHtml, suppliedFilePath) match
      // Both parameters name a render source at once: neither wins silently —
      // picking one would hide a caller mistake behind a rendered card.
      case (Some(_), Some(_)) =>
        IO.pure(
          Left(
            ToolError(
              "Card: `html` and `filePath` are mutually exclusive — pass exactly one of them. " +
                s"Your input: {${input.toMap.keys.mkString(", ")}}"
            )
          )
        )
      case (None, Some(filePath)) =>
        val title = extractTitle(input)
        IO.blocking {
          readCardSource(filePath) match
            case Left(error) => Left(error)
            case Right(source) =>
              val outcome = embedLocalFiles(source)
              val persist = persistCardSource(source, title)
              Right(s"$CardSentinel${cardPayload(outcome, title, persist)}")
        }
      case _ =>
        extractHtml(input) match
          case Some(rawHtml) =>
            val title = extractTitle(input)
            IO.blocking {
              // A3: persist BEFORE `embedLocalFiles` rewrites a single byte.
              val persist = persistCardSource(rawHtml, title)
              val outcome = embedLocalFiles(rawHtml)
              val distinct = distinctRejections(outcome.rejects)
              val listed = distinct.take(MaxListedWarnings)
              if distinct.nonEmpty then
                logger.warn(
                  s"Card: ${outcome.rejects.size} local file reference(s) NOT proxied (" +
                    listed
                      .map { case (rejected, count) => s"${rejected.value} [${rejected.failure.code}]x$count" }
                      .mkString(", ") +
                    ")"
                )
              Right(s"$CardSentinel${cardPayload(outcome, title, persist)}")
            }

          case None =>
            val debug = input.toMap
              .map { (k, v) =>
                val preview = v.asString.getOrElse(v.noSpaces).take(80)
                s"$k: $preview"
              }
              .mkString(", ")
            IO.pure(
              Left(
                ToolError(
                  s"Card tool requires non-empty `html` parameter (or `filePath` naming a local `.html`/`.htm` file; the two are mutually exclusive). Your input: {$debug}. Example: {\"html\": \"<div>content</div>\", \"title\": \"optional\"}"
                )
              )
            )

  /**
   * The card payload object. Field order is a contract with the guard
   *  (fileRefs/warnings lead so a >50K card's 2048-char preview keeps the
   *  failure visible) — see the note at the `fileRefs` key.
   */
  private def cardPayload(outcome: EmbedOutcome, title: String, persist: Json): String =
    val distinct = distinctRejections(outcome.rejects)
    val listed = distinct.take(MaxListedWarnings)
    Json
      .obj(
        "fileRefs" -> fileRefsJson(
          outcome.proxied,
          distinct.size,
          distinct.size - listed.size,
          outcome.exempt,
          List("inlined" -> outcome.inlined, "deferred" -> outcome.deferred)
        ),
        "warnings" -> warningsJson(listed),
        "notes" -> Json.arr(outcome.notes.map(_.asJson)*),
        "html" -> outcome.html.asJson,
        "title" -> title.asJson,
        "persist" -> persist
      )
      .noSpaces

  /**
   * Try to extract valid HTML from the input with multi-level fallback:
   *   1. html field is a non-empty string → use directly
   *   2. html field is a non-string type → stringify and check if it looks like HTML
   *   3. html is empty/missing, but title contains HTML → use title as html
   */
  private def extractHtml(input: JsonObject): Option[String] =
    // Level 1: html field is a non-empty string
    input("html")
      .flatMap(_.asString)
      .filter(_.nonEmpty)
      // Level 2: html is non-string — try to stringify
      .orElse(
        input("html").filterNot(_.isString).map(_.noSpaces).filter(isHtmlLike)
      )
      // Level 3: html missing/empty but title looks like HTML
      .orElse(
        input("title").flatMap(_.asString).filter(isHtmlLike)
      )

  /** Extract title, accounting for the case where title was repurposed as html. */
  private def extractTitle(input: JsonObject): String =
    val htmlDirect = input("html").flatMap(_.asString).filter(_.nonEmpty)
    val titleValue = input("title").flatMap(_.asString).getOrElse("")
    // If html was empty/missing and title was repurposed as html, clear title
    if htmlDirect.isEmpty && isHtmlLike(titleValue) then ""
    else titleValue

  /** Rough check: does the string look like HTML (starts with < and contains >)? */
  private def isHtmlLike(s: String): Boolean =
    val t = s.trim
    t.startsWith("<") && t.contains(">")

  def summarize(input: JsonObject): String =
    val title = input("title").flatMap(_.asString).getOrElse("Card")
    s"Card\n  ($title)"

  def summarizeResult(input: JsonObject, result: String): String =
    val title = input("title").flatMap(_.asString).getOrElse("Card")
    val failed = unresolvedFileRefs(result)
    val exempt = exemptFileRefs(result)
    // Visibility (carderr batch): a card whose local references were dropped
    // used to look like a clean `Card rendered` in the chat header as well.
    // toolfail batch: a suppressed app-route exemption is surfaced too, so
    // `fileRefs.exempt` is not a silent counter.
    val note =
      if failed > 0 then s" — $failed file reference(s) NOT proxied"
      else if exempt > 0 then s" — $exempt app-route reference(s) exempt"
      else ""
    s"$title rendered$note"

  /**
   * The **model-facing projection** of a card result (imgticket batch ii,
   * 作者 #687-D 2026-09-16「做」）。
   *
   * 卡片载荷是给**浏览器**的：`html` 是整张卡片的标记，内联图还是 base64
   * （单图可达 ≈700 K 字符）。语言模型从那些字节里得不到任何信息，而载荷的体量
   * 恰恰是把结果推过 `Defaults.DefaultMaxResultSizeChars`（50,000）的那件事——
   * 过线之后 `ToolResultGuard` 把模型面换成「2,048 字符预览 + 磁盘全文副本」，
   * 模型看到的只是一段被切断的 JSON。
   *
   * 本投影因此**只保留模型能据以行动的事实**（与工具描述对模型的承诺逐条对齐）：
   *   - `card`      —— 卡片标识（标题；空标题回 `"Card"`）；
   *   - `fileRefs`  —— 计数器，与载荷内**同一对象逐字同源**；
   *   - `warnings`  —— 未代理引用的逐条原因，与载荷内**同一数组逐字同源**
   *                    （工具描述要求模型「读 warnings 并修好引用」）；
   *   - `notes`     —— 解码形态披露，与载荷内**同一数组逐字同源**；
   *   - `persist`   —— 落盘读数（本批新增）：`{"ok":true,"path":…}` / `{"ok":false,"reason":…}`，
   *                    与载荷内**同一对象逐字同源**。模型据此可自闭环「Edit 该文件 → 再以
   *                    `filePath` 渲染」。**唯一**会随「何时调用」变化的字段（文件名带时间戳）
   *                    —— 这也是它必须被点名的原因，见 `CardModelFaceSpec` C4。
   *   - `htmlChars` —— 卡片正文本体长度（句柄/可核事实）；
   *   - `note`      —— 一句话说明全文只在前端面，避免模型误以为卡片没渲染。
   *
   * 🔴 移出模型面的字段 = `html`（含内联 base64 图）与 `title` 正文；二者仍逐字
   * 留在用户面（`frontendContent` = [[call]] 的原样返回，由 `AgentCore` 的
   * ToolEnd 发射点投给前端与 `.ui.json`）。🔴 本方法**不新建任何通道**、不动
   * WS 帧形状、不动载荷构造 —— 它只回答「模型该看到什么」。
   *
   * 机械可核：投影长度与 `html` 体量**无关**（只随 `htmlChars` 的位数变化），
   * 且恒不含 HTML 标签序列与 `data:` URI（见 `CardModelFaceSpec`）。
   */
  override def modelFacingResult(result: String): String =
    val json =
      if result.startsWith(CardSentinel) then result.substring(CardSentinel.length)
      else result
    io.circe.parser.parse(json).toOption match
      case Some(payload) =>
        val title = payload.hcursor.get[String]("title").toOption.filter(_.nonEmpty).getOrElse("Card")
        val htmlChars = payload.hcursor.get[String]("html").toOption.map(_.length)
        Json
          .obj(
            "card" -> title.asJson,
            "fileRefs" -> payload.hcursor.downField("fileRefs").focus.getOrElse(Json.obj()),
            "warnings" -> payload.hcursor.downField("warnings").focus.getOrElse(Json.arr()),
            // imgref batch: the decoded-form disclosures ride into the model
            // face too — the model must be able to see that its escape-spelled
            // path was accepted in a different form (作者令：命中即须说明形态）。
            "notes" -> payload.hcursor.downField("notes").focus.getOrElse(Json.arr()),
            // The persistence reading travels to the model too: "Edit the saved
            // file, then render that path" is a loop the model can only close
            // itself if it is told the path it just wrote.
            "persist" -> payload.hcursor.downField("persist").focus.getOrElse(Json.obj()),
            "htmlChars" -> htmlChars.getOrElse(0).asJson,
            "note" -> ("The card HTML (including any inlined images) is rendered to the user and is not returned as "
              + "text; `fileRefs` and `warnings` above are the facts to act on.").asJson
          )
          .noSpaces
      // 载荷不可解析（构造上不该发生）：回一句固定的极小摘要 —— 🔴 绝不把 `result`
      // 原样回灌（那正是本批要关掉的行为）。
      case None =>
        Json
          .obj(
            "card" -> "Card".asJson,
            "note" -> "Card rendered; its payload was not summarizable for the model (the user still got the full card).".asJson
          )
          .noSpaces
    end match
  end modelFacingResult

end CardTool
