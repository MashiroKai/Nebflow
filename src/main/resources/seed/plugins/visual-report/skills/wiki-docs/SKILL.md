---
name: wiki-docs
description: Project wiki delivery conventions. Obsidian-compatible document vaults, cross-document references, and paper-style concept hygiene for every markdown/HTML report written inside a project workspace. Use whenever a deliverable is a document that belongs to a project's knowledge base.
---

# Wiki Docs — project document vault conventions

Every markdown/HTML document written as a project deliverable (report, review,
design note, investigation record) is part of the project's knowledge graph.
Write it into the vault, wire it into the graph, and keep it readable as a
standalone paper. The graph face (nebflow wiki graph view) indexes everything
below automatically.

## Mandatory clauses (checkable — a violation is a rework)

The six clauses below are author mandates (2026-10-21; ④ added 2026-10-29,
term-citation batch; ⑤ added 2026-10-30, human-face batch; ⑥ added 2026-11-05,
cite-terms-only batch), not style advice.
Clauses ①–④ and ⑥ are machine-checkable by the validator in
`scripts/check_wiki_docs.py` (Python 3 stdlib only, zero deps); clause ⑤ is
convention-enforced (skill + dispatch briefs) until the validator grows its
existence rule (design §7.4, still open):

1. **Obsidian format, every document** — written inside the vault
   `<data_root>/projects/<project>/.stand/docs/` (the project's REGISTRATION
   dir, next to its `project.json` — NOT the project workspace repo; author
   ruling 2026-11-03), with a frontmatter block (`type:` / `status:`),
   references as wikilinks, attachments under `attachments/`.
   → check rule `obsidian-format`.
2. **Paper-style citation graph** — when document B builds on document A, B
   cites A at the point of use. Broken references (missing target, missing
   `#heading` anchor, missing `^block-id`, an unregistered term short form)
   are defects; so is an orphan document with zero outgoing and zero incoming
   links (index/glossary exempt). → check rules `broken-link` / `broken-anchor`
   / `broken-block` / `orphan-doc`.
3. **Every concept: explained or cited at first occurrence** — at a term's
   first mention there must be an inline one-sentence definition (dash /
   colon / parenthesis form), or a `[[glossary#^block-id]]` citation to the
   document that already defines it (a registered-term short form `[[Name]]`
   that resolves into the glossary counts as the citation). Multiple documents
   must never invent their own definition of the same concept — the glossary
   is the single definition source. → check rules `concept-first-use` /
   `duplicate-definition`.
4. **Term registry hygiene (词条引用批)** — a term is an explicitly declared,
   block-anchored definition: every glossary block entry and every `^term-*`
   declaration block must yield a term name via the ` —`/`：` separator
   convention, and within one document a term name is declared at most once.
   Short-form citations `[[Name]]` resolve through the term registry
   (glossary hit wins over doc-intro hits); a short form that is neither a
   document nor a registered term is a broken link. → check rules
   `term-format` / `term-duplicate` (R4; the citation face is R2 `broken-link`).
5. **Human face (人面子, human-face batch 2026-10-30)** — every report
   opens with a human-face header inside a fenced ```human block, four
   parts: **Impression → key numbers → Findings pointer → open items**
   (template in the "Human face" section below). Inside the fence:
   BLUF conclusion ≤3 lines, 3-7 key numbers, ≥1 key chart
   (`![[key-chart.png]]`, caption = the conclusion sentence). Agent
   jargon (spec pass counts, merge-queue speak, worktree, commit
   hashes, session/chain internals) stays OUT of the human face —
   plain wording, or a registered-term short form `[[Name]]` for the
   terms that must stay (hover gloss via the term registry). Content
   outside the fence is the machine face: full detail, code anchors,
   acceptance readouts — written as fully as ever, presented folded
   (the product renders the fence as a card and folds the rest into a
   details drawer; plain-text channels degrade to one marker line).
   → no validator rule yet — existence check deferred with the design
   (§7.4 另议); enforced by this skill + dispatch briefs until then.

6. **Cite terms only (只许引用词条, cite-terms-only batch 2026-11-05)** — a
   reference points at a **term**, never at a document, never at a bare file
   path. Author wording: 「以后我们就只允许引用词条，不允许引用文档」「不允许
   引用文档和图片路径，文档就用词条，图片要么直接嵌入，要么就可以点击跳转」.
   Allowed forms: registered-term short form `[[词条名]]` /
   `[[词条名|显示名]]`; positional references `[[doc#^term-slug]]` /
   `[[doc#Heading]]`; image **directly embedded** (`![[a.png]]`, `![](…)`,
   `<img src=…>`); image **clickable** (`[说明](attachments/a.png)`,
   `[[attachments/a.png|说明]]`). Defects: a bare `.md` path in prose
   (`见 design/foo.md`), a markdown link to a document (`[x](foo.md)`,
   `[x](foo.md#sec)`), a no-anchor `[[X]]` that resolves to a document but
   not to a registered term, a plain-text image path (`attachments/a.png`,
   `../a.jpg`, `/abs/a.png`), and a no-anchor `[[Name]]` that resolves to
   neither a document nor a registered term. Inline code and fenced code are
   exempt (a documented syntax example is not a citation). An inline
   `<!-- wiki-term-exempt: 理由 -->` (same line or the line directly above,
   non-empty reason) exempts that line — it never fails the run, but it is
   always printed as a count, and so is a stale marker on a clean line.
   → check rules `doc-reference` (R7) / `bare-image-path` (R8) /
   `dangling-term` (R9).

Run before delivering any document batch:

```
python3 ${SKILL_DIR}/scripts/check_wiki_docs.py --project <project> --glossary glossary.md
```

- The vault root defaults to `<data_root>/projects/<project>/.stand/docs/`
  (`STAND_HOME` / `NEBFLOW_HOME` override the data root); `--project` defaults
  to `stand`. `--root <path>` still overrides explicitly — use it to validate a
  not-yet-migrated workspace `docs/` vault.

- Deliverables must pass with zero NEW violations (exit 0). Pre-existing
  legacy violations in the vault are not a pass for this batch — fix what you
  touch.
- `--graph out.json` exports a WikiIndex-semantics `{nodes, edges}` JSON
  (graph samples / offline analysis); each doc node embeds its paragraph
  index (`paragraphs`: edge-form anchor / line range / ≤280-char excerpt —
  the wiki-graph hover-snapshot surface) and its declared terms
  (`terms`: name / aliases / source `glossary|doc` / rel / anchor / line
  range / ≤280-char definition — isomorphic with WikiIndex.scala, the
  `GET /api/wiki/term` + `nodeJson.terms[]` consumption face);
  `--json` machine-reads the violation list; exit codes: 0 clean,
  1 violations, 2 usage error.
- Paragraph-level references use the two in-vault anchor forms (validated by
  the same broken-anchor/broken-block checks): `[[doc#^block-id]]` addresses
  ONE paragraph (the glossary-entry face — noun explanations), `[[doc#heading]]`
  addresses a section. No `§` syntax exists — that glyph is a hover-overlay
  marker only.
- Syntax examples with literal double brackets must live inside fenced code
  blocks — a bare `[[...]]` in prose is parsed as a real link (same semantics
  as the engine's WikiIndex parser).

## Vault layout

- Vault root: `<data_root>/projects/<project>/.stand/docs/` — created at project
  creation (idempotent); fall back to the workspace `docs/` only for a
  not-yet-migrated project (read-side compatibility, never for new writes).
- Attachments (images, exports, data files referenced by documents):
  `<vault>/attachments/`. Never scatter attachments next to documents
  or in ad-hoc folders — every asset referenced by a document lives under
  `attachments/` and is referenced by its vault-relative path.
- One document = one topic. The file name is the document identity; prefer
  short kebab-case names (`auth-flow-review.md`), because other documents cite
  the file name.
- **Document naming — key information first, time last (author ruling
  2026-11-04 18:12; this bullet is the only naming rule for produced docs).**
  What the document IS leads the file name; time is a tail attribute. Stage
  documents (one-off diagnostics / audits / review snapshots) are named
  `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md`: `topic`, then `__`, then the
  second-precision local time block (`YYYYMMDD_HHMMSS`, `_` inside the block; a
  same-second clash appends `-<n>` to the time block), then `__<chainId>` only
  when the producing chain is real — never invented. Living documents stay
  date-less (`<topic>.md`, `__<chainId>` tail only when attributed). `topic` =
  lowercase kebab (English / pinyin) or verbatim CJK, one phrase that says what
  the document is; a bland word alone (`report`, `doc`, `notes`) is not a topic,
  and documents of one series share one topic prefix. Scope = produced documents
  and evidence (vault knowledge docs, `reports-evidence/`, `evidence/`); code,
  tests, specs and configs are out of scope. Existing documents keep their
  names — no rename, no move, no retro-edit (the date-first form survives only
  on that existing stock).
- **Sub-project vaults (multi-project graph, #744-B)**: any `<sub>/docs/`
  directory inside the vault is its own sub-graph — a sub-project keeps the
  same vault convention one level down (`docs/<sub>/docs/`). Sub-vault
  documents are ISOLATED per vault (the parent graph shows them as one
  aggregate node that drills into the child graph), so:
  - keep each sub-vault self-contained (its own glossary/index when it grows
    past a handful of documents);
  - cite across vaults by the path form the target vault uses
    (`[[<sub>/docs/topic.md]]`) — cross-vault references are counted as
    cross-vault links, never silently broken.
  Validate every level independently:
  `python3 ${SKILL_DIR}/scripts/check_wiki_docs.py --project <project> --include-subvaults`

## Document form (paper style)

- The document title is the first `# H1` heading. The graph uses it as the
  node label.
- Structure: conclusion first, then evidence and reasoning, then appendix.
- Every new concept must be understandable at first mention: either define it
  inline in one or two sentences at the point of first use, or link the
  definition to the document/section that defines it (see references below).
  Never leave a term that only a companion document explains without the link.
  (This is mandatory clause ③ — the validator above enforces it.)
- Concepts that recur across documents belong in a glossary document
  (`glossary.md` at the vault root): one block per concept, each block ending
  with a block id, e.g.

  ```
  Retry storm — a feedback loop where failures trigger retries that multiply
  load and produce more failures. ^retry-storm
  ```

## Human face (报告头, human-face batch 2026-10-30)

Batch / investigation reports open with the four-part human-face header
inside a ```` ```human ```` fence (设计稿: docs/design/20261030_human-readable-
presentation.zh-CN.md, 附录 A; author ruling 2026-10-30: the human face is
MANDATORY — an agent that forgets the fence has not delivered):

````markdown
# <batch / investigation> report

```human
## Impression
<one-sentence conclusion: what was done + outcome + whether the author
must act; ≤3 lines>

**Key numbers**: <files changed · assertions green · time · scale, 3-7>
![[key-chart.png]]   <!-- ≥1 chart; the caption IS the conclusion sentence -->
```

## Findings
<machine face: full detail, code anchors, acceptance readouts — written
in full; presented folded since the human-face batch>

## Open items & deviations
<open items, deviation declarations>
````

- Impression = the human face (BLUF, zero jargon, ≤3 lines). Findings =
  the machine face (complete detail, jargon fine). Key numbers = the
  hinge between the two layers: 3-7 quantified readouts (files changed,
  assertions green, wall time, scale) — the author's basis for deciding
  whether to open the drawer.
- One document may carry several ```human fences (a conclusion card, a
  chart card); they render in order as cards and everything outside
  folds into a "details (N lines)" drawer. The fence is the agent's
  anchor for "what is written for humans" — without it the discipline
  has no landing point.
- Jargon ban (human face only): spec pass counts → "all tests pass" +
  one key-numbers line; merge / merge queue → "merged into main
  (queued)"; worktree → "working copy" or omit; chain / node / delegate
  session → "subtask"; commit hashes stay in the machine face. Retained
  terms that genuinely must stay are written as registered-term short
  forms `[[Name]]` — hover gloss via the term registry (section "Terms
  and short-form citations" above).
- The key-chart obligation is a HUMAN-FACE obligation (Tufte: one chart,
  one conclusion): ≥1 chart inside the fence, its caption stating the
  conclusion, the asset under `attachments/`. Findings tables stay
  chart-free — the chart duty does not propagate into the machine face.
- Product face (already shipped, all markdown faces share one pipeline):
  `utils.js renderMarkdownWithMath` renders the fence into
  `.nf-human-card` and wraps outside content in `<details
  class="nf-human-details">`; chat bubbles, canvas md viewer, wiki
  docs, flow cards and report cards all present the same way. Streaming
  safety: the transform engages only after every fence is closed —
  mid-stream snapshots render as plain code blocks, nothing crashes.
- Profile system retired (author order 2026-10-31 16:13, output-block
  batch; design doc §6 marked deprecated): the five presentation tiers
  and the seven per-tier parameters are gone from product AND storage —
  figure density and length are decided by the report content itself
  plus the long-term user profile, not by parameters. This human-face
  template is the surviving discipline and stays mandatory.
- `<output>…</output>` paired tags (same batch, revised to tag form at
  16:16 to avoid markdown-syntax collision) mark user-visible content
  in chat-facing deliverables: the product shows ONLY tagged content by
  default — everything outside (reasoning, tool narration) is stripped
  until the user expands the badge, and the expanded view shows the raw
  full text with the tags verbatim; untagged legacy content renders
  fully as before. Write tagged content WeChat-style (short direct
  paragraphs, conclusion first); tone/length follow the user's
  communication-style profile (`~/.stand/User.md`), defaulting to
  concise direct Chinese. In vault documents the pipeline renders the
  tags transparently (content shown, tags invisible). Spec: design doc
  §11 (docs/design/20261030_human-readable-presentation.zh-CN.md).

## References (the graph's edges)

Use Obsidian wikilinks. They render as clickable references and build the
relationship graph. Since the cite-terms-only batch (clause ⑥, 2026-11-05) a
reference must point at a **term** — not at a document, not at a bare path:

- `[[词条名]]` / `[[词条名|显示名]]` — cite a registered term by name
  (the only whole-target form that is still legal without an anchor).
- `[[glossary#^retry-storm]]` — cite a concept definition block (preferred for
  concepts: the reader jumps to the exact definition and the target paragraph
  is highlighted).
- `[[auth-flow-review#Token lifetime]]` — positional reference into a section.
  Positional references stay legal because they resolve to a *place*, not to a
  document identity.
- `![[architecture.svg]]` — embed an attachment from `attachments/`.
- `[说明](attachments/a.png)` / `[[attachments/a.png|说明]]` — a clickable
  image: an image is either embedded or clickable, never a plain path.

Not references any more (rule R7/R8): `[[auth-flow-review]]` (a document
short form), `[text](topic.md)` (a markdown link to a document), and any
plain-text path such as `见 design/foo.md` or `attachments/a.png`.

Citation discipline:

- A document with zero outgoing links and zero incoming links is a defect
  unless it is the first document in a new domain — wire new documents to at
  least one registered term.
- When document B builds on document A, B cites **the terms A defines**
  (`[[term-name]]` or `[[A#^term-slug]]`) at the point of use, and A gains a
  "See also" reference back to B when A is revised next. The pair of edges is
  what makes the graph navigable in both directions.
- Anchors must exist: `#heading` must match a heading in the target document;
  `^block-id` must match a block id. A reference that resolves to nothing is
  the document-level equivalent of a broken build.
- Need to point at something that has no term yet? Declare the term
  (`^term-<slug>` block) and cite the name — do not fall back to a document
  link or a path.

## Concept-reference format (agent-authored reports)

When the reader of a report will meet an unfamiliar term, write the first
mention as a definition or a concept reference, not as bare jargon:

- Inline: "…the retry budget (the maximum share of requests that may fail and
  retry before the circuit opens)…"
- Reference: "…the retry budget ([[glossary#^retry-budget]])…"

HTML deliverables follow the same discipline with plain anchors to the
companion markdown documents in the vault.

## Terms and short-form citations (词条, term-citation batch 2026-10-29)

A term is a named, block-anchored definition the whole vault can cite by
name. Two declaration faces, both derived from the block-anchor paragraphs
the graph already indexes — never declared in frontmatter (a `terms:`
frontmatter list would be a second source of truth that drifts from the
body anchors):

- **Glossary terms** (cross-document shared concepts): every block-anchored
  entry of a glossary document (file whose name contains `glossary`) is a
  term. This is the single definition source (mandatory clause ③).
- **Document-intro terms** (a document introducing its own topic, or its
  citable plans/attribution findings): declare them in a `## 词条` section
  near the top of the document. ONE entry per paragraph — a blank line
  between entries — each ending with a `^term-<slug>` block id:

  ```
  ## 词条

  - DCL（DOMContentLoaded 事件） — the browser's first interactive moment. #proj/stand #topic/term-index ^term-dcl

  - 归因结论 — this incident was caused by config drift, not code. #proj/stand #topic/incident ^term-attribution
  ```

Declaration shape (machine-checked, rule `term-format`):

- The name is the text before the first ` —` (em/en dash) or `：`/`:`
  separator; no separator means the block is NOT a term and R4 flags it.
- A trailing parenthetical group derives lookup aliases:
  `DCL（DOMContentLoaded 事件）` is citeable as `[[DCL]]`, `[[银河宇宙线]]`-style
  inner names likewise. Do not invent other alias syntaxes — the pipe is the
  wikilink reference alias (`[[target|display]]`), not a declaration face.
- Within one document a term name is unique (rule `term-duplicate`).
- The same term name MAY live in several documents' intro blocks (each
  document self-introduces) — but `[[Name]]` then stays unresolved: the
  engine never guesses between same-tier hits.

### Term tags (词条标签, term-system v2)

Every term declaration carries a tag tail so the index can group terms by
project and topic without a second source of truth. **One line, fixed order:**

```
- 词条名（别名1，别名2） — 一句话定义。 #proj/<project> #topic/<topic> [更多 #topic/…] [更多 #<facet>/<value>] ^term-<slug>
```

- **Exactly one** `#proj/<project-slug>` and **at least one**
  `#topic/<topic-slug>`. Every other `#<facet>/<value>` is an extra facet and
  is free-form (`#ctx/browser`, `#level/deep`, …). Facets are `facet/value`
  pairs — one slash; `#ctx/a/b` only registers `ctx/a`.
- **Position**: the tags sit AFTER the definition text and BEFORE the
  `^term-<slug>` block id. The block id must be the last thing on its line.
  The tag window is exactly "after the ` —`/`：` separator → before the block
  id", so a tag written before the separator, or after the block id, is not
  read at all.
- Tags are **decoration, never identity**: they add no aliases and change no
  citation resolution. `[[Name]]` still resolves by name/alias only.
- `` `#x` `` inside an inline-code span is documentation, not a tag (write
  examples backticked); fenced code never enters a paragraph at all.
- Buckets are deduplicated and sorted by the engine, so the same declaration
  always yields the same `{project, topic, extra}` JSON in every consumer.

Valid (both buckets present, tags before the block id):

```
- DCL（DOMContentLoaded 事件） — 浏览器首次可交互的时间点。 #proj/stand #topic/term-index ^term-dcl

- 归因结论 — 本批缺陷由配置漂移引入，不是代码回归。 #proj/stand #topic/incident #ctx/release ^term-attribution
```

**Invalid** — each one fails the check with the reason marked:

```
- GCR — 银河宇宙线通量。 #topic/cosmology ^gcr
```
✗ **missing `#proj/<slug>`** — only a topic tag was written; a term with no
project facet is unfilterable ⇒ `term-tag-missing`.

```
- 重试风暴 — 短时高频重试打满下游配额。 #proj/stand ^term-retry-storm
```
✗ **missing `#topic/<slug>`** — the project tag never satisfies the topic
requirement (`#topic/` is at least one, `#proj/` exactly one) ⇒ `term-tag-missing`.

```
- 举证窗口 — 规则允许补证的时长。 ^term-evidence-window #proj/stand #topic/compliance
```
✗ **tags after the block id** — `^term-evidence-window` is no longer at line end,
so Obsidian does not treat it as a block anchor, the paragraph is not a term,
and the tags are invisible ⇒ `term-tag-missing`.

Violation code `term-tag-missing` (R4): a term declaration whose tag set has
no `#proj/<slug>` or no `#topic/<slug>` — including the "tags after the block
id" form, which silently loses both. Fix the declaration line; the only escape
hatch is an inline exemption `<!-- wiki-term-exempt: 理由 -->` on the same or
the immediately preceding line with a non-empty reason (counted separately in
the report, never a whole-file waiver).

Citation resolution for `[[Name]]` (no anchor) — fixed priority:

1. exact vault-relative path → 2. unique basename document →
3. **term registry** (a unique glossary hit beats doc-intro hits) →
4. still ambiguous/unhit = unresolved (a dangling term in R9's report; a hit
on step 1/2 with no term behind it is an R7 doc-reference).

Practical discipline: cite `[[Name]]` when the name is unique vault-wide or
glossary-defined; when you mean a SPECIFIC document's term (its plan, its
attribution), cite `[[doc#^term-slug]]` explicitly. `[[Name|display]]`
aliases ride the same resolution.

Engine faces that consume the registry (no extra work for the writer):
`GET /api/wiki/term?name=…&project=…` (single lookup:
`{found, resolved, term}` / `{resolved:false, ambiguous, candidates}` /
404 unregistered), and each graph node's `terms[]` projection (hover
preview + deep links). Scope v1: term definitions are a markdown face —
html documents get reference jumps with a document-level summary fallback,
no html definition blocks.

## What not to do

- Do not write reports outside the vault (chat-only delivery loses the graph).
- Do not bury the conclusion in the middle of a report — the human-face
  header (clause ⑤) is the presentation default; Findings detail never
  precedes it.
- Do not duplicate a concept's definition in several documents — define once,
  reference everywhere else.
- Do not cite a **document**: no `[[some-doc]]` short form, no
  `[text](some-doc.md)`, no bare path in prose. Cite the term instead
  (clause ⑥, rules R7/R9).
- Do not write an **image path as text**: no `attachments/a.png` in prose, no
  `../x.jpg`, no absolute path. Embed the image (`![[a.png]]`) or make it
  clickable (`[说明](attachments/a.png)`); rule R8.
- Do not rename documents that are already cited; fix the citing links in the
  same change instead.

### Cite terms, not documents — the hard rule with examples

The rule (author, 2026-11-05): 「以后我们就只允许引用词条，不允许引用文档」
「不允许引用文档和图片路径，文档就用词条，图片要么直接嵌入，要么就可以点击跳转」.
Every line below is checked by R7/R8/R9; inline code and fenced code are the
only places where a path-looking string is documentation rather than a
citation.

**OK — a term, a positional reference, an embed or a clickable image:**

```
缓存击穿（cache stampede） — 同一热点键过期瞬间被并发打穿。 #proj/stand #topic/cache ^term-stampede
见 [[缓存击穿]]；实现细节见 [[缓存设计__20261105_101500#^term-stampede]]。
键表看 [[缓存设计__20261105_101500#Key table]]（整节定位）。
![[stampede-chart.png]] 是这次事故的尖峰曲线。
[stampede-chart.png](attachments/stampede-chart.png) 可点击打开原图。
语法示例写在反引号里：`见 design/foo.md`。
```

**Not OK — each line fails, with the reason:**

```
见 design/foo.md。                          → R7 doc-reference（正文裸 .md 路径）
细节见 [缓存设计](cache-design.md)。          → R7 doc-reference（markdown 链接指向文档）
参考 [[cache-design]]。                      → R7 doc-reference（无锚短形解析到文档而非词条）
截图在 attachments/stampede-chart.png 里。   → R8 bare-image-path（图片路径是纯文本）
见 [[尚未注册的词条名]]。                     → R9 dangling-term（文档与词条双双解析不到）
```

Fixing them: declare the term (`^term-<slug>`) and cite its name; point at a
place with `[[doc#^term-slug]]` / `[[doc#Heading]]`; embed or link the image.
While a legacy document is being migrated in another batch, put
`<!-- wiki-term-exempt: 理由 -->` on the offending line (or the line directly
above) — the run stays green, but the exemption is always printed as a counted
line in the report, and a marker on a clean line shows up as a dangling
annotation. There is no whole-file waiver.

## Frontmatter (scenario metadata)

Every vault document starts with a tiny frontmatter block — the graph reads it
for its type/status filters:

```
---
type: research-log
status: open
---
```

- `type` names the document's role in the scenario pack (below).
- `status` tracks where it stands. Statuses that mark finished work
  (`solved` / `done` / `accepted` / `mastered` / `published`) get a ✓ ring in
  the graph, so "what is already resolved" reads at a glance.

## Scenario packs

Pick the pack that matches the engagement; do not invent a new one when these
fit.

### Research (科研)

A research engagement is a line of work, not a pile of files. Four document
roles carry the line:

- `research-log` (`status: open` while active): dated entries. Each entry links
  what it produced that day — experiments, notes, decisions — with wikilinks,
  so the log is the spine of the graph.
- `problem` documents: one open question each (`status: open`). When it is
  resolved, flip to `status: solved` and link the evidence (result document,
  experiment, literature note) at the resolution point. A solved problem that
  cites nothing is not closed.
- `literature-note`: one per paper (title = paper short title). **Download the
  original PDF into `attachments/`** (use the `paper-download` skill of
  browser-use; filename convention `firstauthor-year-shorttitle.pdf`) and link
  it from the note — `![[firstauthor-year-shorttitle.pdf]]` — so the reader
  opens the original in canvas from the note. Remote-only links (URLs without
  a local copy) are a defect: literature research must produce the original
  file. Cite claims as `[[literature-note#^claim-id]]` block references.
- `conclusion` documents: one settled finding each, citing the problem
  documents and literature notes that support it.

Progress readout = the graph filtered by status: open problems, solved
problems (✓), conclusions.

### Learning (学习)

- `learning-map`: the index document — one section per knowledge area, each
  knowledge point a wikilink to its own document. The map is the spine.
- Knowledge-point documents: one concept each; link prerequisite points
  (`Prerequisites: [[point-a]], [[point-b]]`) so the graph shows the
  dependency structure.
- Study materials (course PDFs, slides, screenshots) go to `attachments/` and
  are cited from the point they belong to — same download-and-link discipline
  as research literature.
- Progress = `status` on each point: `to-learn` / `learning` / `mastered`.

### Engineering (工程决策)

- `adr` (architecture decision record): one decision each,
  `status: proposed | accepted | superseded`; a superseded ADR links its
  successor. Incident/postmortem documents cite the ADRs they constrain.
- Experiment/verification records cite the ADR or problem that motivated them.

### Writing (写作)

- `manuscript` (draft chapters), `source-note` (one per source, original
  downloaded to `attachments/` where licensable), `outline`. Sources cite
  claims with block references; the manuscript cites its sources.

### Team knowledge base (团队知识库)

- `runbook` (operational procedures, kept current — stale runbooks get
  `status: superseded` with a link to the replacement), `faq` (one question
  per document), `onboarding` (index linking the reading path).

## Attachments are first-class references

The graph indexes every canvas-openable attachment (PDF, Office, image,
media) as a node, and a wikilink or embed pointing at it
(`[[paper.pdf]]`, `![[figure.png]]`) opens it directly in canvas. Cite the
attachment, never a bare path string: the link IS the edge that puts the
literature on the graph.
