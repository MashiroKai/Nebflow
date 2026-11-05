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

The nine clauses below are author mandates (2026-10-21; ④ added 2026-10-29,
term-citation batch; ⑤ added 2026-10-30, human-face batch; ⑥ added 2026-11-05,
images-live-with-the-doc batch; ⑦⑧ added 2026-11-05, typography-and-abbreviations
batch — author rulings 11:09 and 11:11; ⑨ added 2026-11-05, cite-terms-only
batch), not style advice. All nine are
machine-checkable by the validator in `scripts/check_wiki_docs.py` (Python 3
stdlib only, zero deps) — except clause ⑤'s fence existence, which stays
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
6. **Images live with the document (图随文档走, images-live-with-the-doc
   batch, author order 2026-11-05 09:24)** — an image a document shows is a
   real file INSIDE the same vault (`<vault>/attachments/` or `<vault>/assets/`,
   i.e. under `<dataRoot>/projects/<project>/.stand/docs/`), referenced by a
   vault-relative path — `![[key-chart.png]]` or `![](assets/key-chart.png)`.
   Never an absolute path, a `~` home path, a `file://` URL, a `../` path that
   escapes the vault, or an http(s) image host / CDN. The referenced file must
   EXIST. Rationale: the vault travels (other machines, fresh clones, cleaned
   worktrees), the repo evidence tree / worktree / cache does not — a document
   whose figures live elsewhere is a page of dead references the moment it
   moves. → check rule `image-outside-vault`; per-occurrence escape hatch is
   the inline marker `<!-- wiki-img-exempt: reason -->` (see "Images live with
   the document" below).
7. **Typography: paragraph indent + restrained colour (排版批, author ruling
   2026-11-05 11:09)** — every body paragraph starts with `&emsp;&emsp;`
   (two HTML entities = a two-character first-line indent; plain spaces either
   collapse to nothing or turn the line into a code block), and colour — which
   Markdown cannot produce, only inline HTML can — is capped at **4 spots per
   document** (3 recommended), only on **key conclusions and risk/warnings**,
   using exactly three fixed semantic values. Emphasis in general means
   **bold**, never colour. → check rules `doc-indent` / `doc-color`; full
   table, positive and negative examples in "Document typography" below.
8. **Every abbreviation expanded at first use (缩写批, author ruling
   2026-11-05 11:11)** — domain abbreviations (GCR / SCD / DPC / MC / NIEL…)
   and organisation abbreviations (ICRP / IAEA / CERN / NIST…) must carry
   their full name at the **document's first occurrence**, either inline
   (`full name（GCR）`, `GCR（full name）`) or through a term link
   (`[[GCR]]（full name）`). Units and universal technical marks are exempt
   through the allowlist **written into this file** (not hidden in the
   script). → check rule `abbrev-expansion`; see "Abbreviations" below.

9. **Cite terms only (只许引用词条, cite-terms-only batch 2026-11-05)** — a
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
   → check rules `doc-reference` (R13) / `bare-image-path` (R14) /
   `dangling-term` (R15). The cite-terms batch first took R7/R8/R9; it yielded
   those numbers to the typography rules (R7 `doc-color` / R8 `doc-indent` /
   R9 `abbrev-expansion`, landed earlier and already in force) and moved up to
   R13–R15 — the judgement logic is unchanged, only the numbers.

Run before delivering any document batch:

```
python3 ${SKILL_DIR}/scripts/check_wiki_docs.py --project <project> --glossary glossary.md
```

- The validator has a regression fixture — `scripts/test_check_wiki_docs.py`
  (stdlib `unittest`, builds throwaway vaults, covers rules R1–R9 plus the
  cite-terms-only trio R13–R15 and the
  red→green exit codes, including the typography/abbreviation cases: not
  expanded ⇒ flags, expanded ⇒ passes, unit allowlisted ⇒ passes, inside a
  code fence ⇒ not scanned, colour over budget ⇒ flags, missing indent ⇒
  flags). Run it in the same breath as any validator change:
  `cd scripts && python3 -m unittest test_check_wiki_docs -v`.
- **Scope of clauses ⑦/⑧ (stock transition).** R7/R8/R9 are enforced as
  violations for **new-format documents**: a stage document whose filename
  time block is ≥ `20261105_111100`, or any document that declares `style:`
  in its frontmatter (opt-in), or any document at all under
  `--strict-style` (the full-vault remediation sweep). Older stock is
  reported as **advisory** — printed with file:line and counted, but outside
  the violation list and the exit code — because 400 existing documents cannot
  be re-laid-out in one batch. Touch a legacy document and it comes into
  scope: fix what you touch. Flags: `--style-since <YYYYMMDD_HHMMSS>`,
  `--strict-style`, `--skip-style`, `--allowlist`, `--allowlist-extra`.
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
  `<vault>/attachments/`, or `<vault>/assets/` (the newer batch convention —
  both are in-vault and both are acceptable). Never scatter attachments next
  to documents or in ad-hoc folders, and never point at a file outside the
  vault: every asset referenced by a document lives inside the vault and is
  referenced by its vault-relative path (clause ⑥).
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

## Images live with the document (图随文档走, clause ⑥)

Author order 2026-11-05 09:24: *"对 visual report 做要求，就是文档相关的图片要
放文档里"*. The rule governs the PHYSICAL LOCATION of figures only — the
explain-at-first-occurrence discipline (clause ③) stays exactly as it was.

**Scope (surface split, do not mix):** this clause governs images inside VAULT
DOCUMENTS (the `.stand/docs/` markdown/HTML knowledge base, rendered by the
vault's own resolver). Chart embedding in delivery text / chat cards is a
different surface with a different resolver — there `skills/core/SKILL.md` §2
(absolute `~/...` paths inside the served whitelist) still applies. One
artifact can serve both: generate into the whitelist, then copy the figure
into the vault and reference the copy relatively from the document.

Four binding points, all machine-checked as `image-outside-vault`:

1. **The file lives in the vault.** Any image a document shows sits under
   `<dataRoot>/projects/<project>/.stand/docs/` — by convention
   `<vault>/attachments/` or `<vault>/assets/`; generated figures are copied
   into the vault in the same batch that writes the document.
2. **The reference is vault-relative.** `![[key-chart.png]]`,
   `![](assets/key-chart.png)`, `![](../assets/key-chart.png)` (from a
   sub-directory) — or `<img src="assets/key-chart.png">` in HTML. NOT
   absolute paths, NOT `~` home paths, NOT `file://` URLs (an absolute path
   breaks the moment the data root moves, even when it happens to point
   inside today's vault).
3. **Never a volatile location.** No `reports-evidence/`, no worktree path
   (`.nebflow/worktrees/<w>/…`), no `/tmp`, no `~/.stand/uploads/`, no
   `~/.stand/evidence/` — these get cleaned, archived or moved, and the
   document then holds a dead reference. Copy the figure into the vault
   FIRST, then reference the copy. (Naming note: the same three rules apply
   to naming — the copy is named by the document-topic convention, not by
   the evidence folder it came from.)
4. **Offline first — no remote image hosts.** http(s) images, CDNs and remote
   icon/image services are violations; if the only source is remote, download
   one local copy into the vault, reference that copy, and name the origin in
   the caption or the surrounding text.

Correct (compliant):

```markdown
![Layer-2 · chat 1440 · 差异 9,762px（0.75%）](assets/20261105_design-spec-layer2-chat-1440.png)
```

Wrong (the 2026-11-05 counter-example, sanitised): a design-spec document in
the vault whose 24 screenshots were left in the repository evidence tree and
referenced from outside it —

```markdown
![](../../reports-evidence/20261105_design-spec-value-convergence/shots-layer2-post/chat-1440-dark.png)
![[reports-evidence/20261105_design-spec-value-convergence/shots-layer2-post/chat-1440-dark.png]]
```

Both forms are `image-outside-vault`. The document travels with the vault; the
screenshots die with the worktree — the reader gets four broken images where
the evidence should be. The fix is one copy step in the writing batch
(`cp -R reports-evidence/<batch>/shots <vault>/assets/<batch>/`), not a
document rewrite.

**Per-occurrence exemption (legacy stock only).** A historical document that
genuinely cannot be migrated yet may carry an inline marker on the same line as
the reference (or on the line immediately above it):

```markdown
<!-- wiki-img-exempt: 历史取证件仍在仓内证据树，待作者批准迁移 -->
![[reports-evidence/old-batch/flow.png]]
```

Rules of the escape hatch: the reason must be non-empty (an empty reason grants
nothing), it covers exactly that one occurrence (there is no whole-file and no
whole-vault waiver), and every exemption is counted and printed by the
validator — `图片纪律（R6 …）：图片引用 N 处 · 违规 M 条 · 行内豁免 K 条`,
followed by one line per exemption. Markers placed where no image reference
follows are printed as dangling (`悬空豁免标注`) so a mis-placed marker never
silently waives anything.

**Validator readout** (`scripts/check_wiki_docs.py`, rule R6, exit code 1 on a
violation):

```
── R6 image-outside-vault（1）──
  design/foo.html:256  `file:///Users/…/.stand/uploads/x.jpg` → 绝对路径且落在 vault 外——图片必须搬进 vault，正文用 vault 相对路径引用（命中易失位置：~/.stand/uploads（上传缓存，会被清理））
```

Scan scope per document: markdown `![alt](path)`, wikilink embeds `![[path]]`
with an image extension, and HTML `<img src="…">`. Code fences and inline-code
spans are syntax samples, not references (they do not render as images) and are
skipped — **except the `human` fence**: it is a content face (clause ⑤'s key
chart lives inside it), so its images are checked like body content. `data:`
inline images are self-contained and pass. Migration of existing stock is the
author's call — the validator reports, it never moves or rewrites documents.

## Document typography — indent + colour (clause ⑦)

Author ruling 2026-11-05 11:09: *"对 visual report 产出的文档也是，适当使用彩色
字体，以及缩进"*. Two mechanical requirements, one section, because they fail
for the same reason: markdown has no layout and no colour, so both must be
expressed as inline HTML — and only a few of those forms survive a real
renderer.

### Indent — `&emsp;&emsp;`, and nothing else

Measured on this deployment's renderer (marked v12.0.2, the engine's own
`src/main/resources/web/vendor/marked.min.js`; Obsidian decodes the same HTML
entities, so one form serves both faces):

| Form | Rendered | Verdict |
|---|---|---|
| `&emsp;&emsp;正文` | `<p>&emsp;&emsp;正文</p>` — two full-width spaces stay | **the only compliant form** |
| `  正文` (two spaces) | `<p>  正文</p>` — HTML collapses the whitespace | collapsed, no indent (violation) |
| `    正文` (four spaces) | `<pre><code>正文</code></pre>` — becomes a **code block** | not an indent at all (violation) |
| `> 正文` | blockquote — left bar, semantic "quotation" | only when you really quote |

Rules:

- **Paragraph first line only.** Continuation lines are never indented — the
  indent marks paragraph starts, not every line.
- **Exempt:** headings, list items, table rows, blockquotes, fenced code or
  diagrams, image-only lines, HTML blocks, and anything shorter than **40
  visible characters** (label lines, one-line conclusions, checklist items).
- **Exempt:** everything inside a ```` ```human ```` fence — the human face is
  a card with its own layout.

Correct:

```markdown
&emsp;&emsp;词条是块锚段落里的一条显式声明，全库可以按名引用。

&emsp;&emsp;缩写第一次出现时不给全称，读者到第二段就跟丢了。
```

Wrong:

```markdown
    四空格起行的正文（渲染成代码块，读者看到等宽字体和灰底）

  两个半角空格的正文（渲染前被折叠，视觉上毫无缩进）
```

### Colour — three semantic values, ≤4 spots

Markdown has no colour; the only channel is inline HTML. Free colour turns
into noise, so the semantics are fixed and the budget is small:

| Colour | Meaning | Value (light / dark contrast) |
|---|---|---|
| Blue | **conclusion / verdict** | `#5e6ad2` (4.70:1 · 3.85:1) |
| Red | **risk / warning / failure** | `#d0342c` (4.99:1 · 3.62:1) |
| Green | **verified / passed / gain** | `#12874a` (4.57:1 · 3.95:1) |

Hard constraints:

1. **≤4 coloured spots per document** (3 recommended). The 5th is a defect.
2. **Only key conclusions and risk/warnings.** Ordinary emphasis is `**bold**`.
   Colour that decorates is colour nobody notices when it matters.
3. **One spot ≤120 characters.** Wrapping a whole paragraph in `<span>` is
   both too much colour and no emphasis at all.

Correct:

```markdown
&emsp;&emsp;结论：<span style="color:#5e6ad2">缩进由独立兜底状态承载，不再挂节点</span>。

&emsp;&emsp;<span style="color:#d0342c">风险</span>：存量文档仍用四空格缩进，重排后会被渲染成代码块。
```

Wrong:

```markdown
&emsp;&emsp;<span style="color:#ff0000">红色</span>用来强调，<span style="color:#00b050">绿色</span>表示通过，
<span style="color:#7030a0">紫色</span>表示补充，<span style="color:#ffc000">黄色</span>表示待定，
<span style="color:#1f9ed4">蓝色</span>表示结论——一段话五处颜色，读者一个都记不住。

&emsp;&emsp;<span style="color:#d0342c">这一整段都被刷成了红色，于是整段等于没有重点，
而且它占了预算里的全部额度。</span>
```

HTML deliverables (`.md`-sibling `.html` report pages) carry their own CSS and
are out of these two checks (R7/R8 scan markdown only).

## Abbreviations — expanded at first use (clause ⑧)

Author ruling 2026-11-05 11:11: *"然后补充词条包含的范围，GCR，ICRP 这种缩写，
机构缩写，也需要用词条解释"*. The glossary used to cover concept nouns only; it
now covers **abbreviations**, and an abbreviation is exactly the thing a reader
cannot decode from context.

Mandate: at a document's **first occurrence** of an abbreviation, satisfy one of

1. **Inline full name** — `全球宇宙线（GCR）` · `GCR（Galactic Cosmic Rays，银河宇宙线）` ·
   `Galactic Cosmic Rays（GCR）` · `GCR — Galactic Cosmic Rays`;
2. **Term link** — `[[GCR]]（Galactic Cosmic Rays，银河宇宙线）` (the wikilink
   renders as the abbreviation itself, so the sentence still reads naturally),
   or a citation of the document that defines it (`[[glossary#^gcr]]`).

Scope — what the validator scans:

- **All-caps tokens of 2–6 letters**: `GCR` `ICRP` `IAEA` `CERN` `NIST` `SCD`
  `DPC` `MC` `NIEL` `TID` `RBE` `LET` `HZE` `DOSTEL` `PTC` `ESA` … — domain
  and organisation abbreviations are the main target.
- **Dotted proper abbreviations**: `E.C.` `U.S.` `R.B.E.` (all-lowercase
  `e.g.` / `i.e.` never match — they are not abbreviations of a name).
- Mixed-case technical forms (`MeV` `keV` `SiPM` `GHz` `mSv`) never match the
  all-caps pattern, so units need no special case in the rule itself.
- **Not scanned:** fenced code blocks (Mermaid / HTML / SVG samples included),
  inline code spans, HTML tag attributes, URLs, file names (`SKILL.md`,
  `README.md`), heading lines, and the content of ```` ```human ```` fences
  (the human face has its own jargon ban).
- **Not scanned:** a token that also appears in the same document in a
  lowercase form (`ALL`/`all`, `PASS`/`pass`, `SKILL`/`skill`) is an English
  word used for emphasis, not an abbreviation.

### The allowlist (living in this file, never in the script)

The allowlist is the fenced block below, between the two markers. The
validator parses it out of this file (`--allowlist <path>` overrides,
`--allowlist-extra TOKEN` adds one token for a single run). Editing the list IS
editing the standard.

Admission rule — only three families, and nothing else:

- **甲 units and quantities** readers never expand;
- **乙 format / protocol / platform names** (a document that writes `JSON`
  needs no `JavaScript Object Notation`);
- **丙 process marks** (log levels, acceptance readouts, HTTP verbs) — they are
  marks, not concepts; expanding `OK` makes the text worse.

**Never admitted: domain abbreviations, organisation abbreviations, or a
project's own coinages** — `GCR`, `ICRP`, `IAEA`, `CERN`, `NIST`, `SCD`,
`DPC`, `MC`, `NIEL` must be expanded or registered as terms. Putting one of
those in the allowlist deletes the rule; that is the abuse this section exists
to prevent.

```text
<!-- wiki-abbrev-allowlist:start -->
# 甲类 · 单位与量纲（读者无需展开）
MB KB GB TB PB DPI PPI FPS PX HZ KHZ MHZ GHZ
# 乙类 · 格式 / 协议 / 平台名（跨领域无歧义）
API URL URI HTTP HTTPS TCP UDP TLS SSH IP DNS JSON JSONL YAML XML HTML CSS JS SVG
PNG JPG JPEG GIF PDF CSV TSV SQL UUID ASCII UTF SHA CDN CI UI UX
# 丙类 · 流程记号（日志级别 / 验收读数 / HTTP 动词）
PASS FAIL WARN ERROR INFO DEBUG EXIT SKIP OK TODO FIXME GET POST PUT PATCH DELETE HEAD
<!-- wiki-abbrev-allowlist:end -->
```

Prefer expansion over allowlisting whenever a reader outside the field could
stall on the token; the list is for the handful of marks that are universal
already.

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
relationship graph. Since the cite-terms-only batch (clause ⑨, 2026-11-05) a
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

Not references any more (rule R13/R14): `[[auth-flow-review]]` (a document
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
4. still ambiguous/unhit = unresolved (a dangling term in R15's report; a hit
on step 1/2 with no term behind it is an R13 doc-reference).

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
  (clause ⑨, rules R13/R15).
- Do not write an **image path as text**: no `attachments/a.png` in prose, no
  `../x.jpg`, no absolute path. Embed the image (`![[a.png]]`) or make it
  clickable (`[说明](attachments/a.png)`); rule R14.
- Do not rename documents that are already cited; fix the citing links in the
  same change instead.

### Cite terms, not documents — the hard rule with examples

The rule (author, 2026-11-05): 「以后我们就只允许引用词条，不允许引用文档」
「不允许引用文档和图片路径，文档就用词条，图片要么直接嵌入，要么就可以点击跳转」.
Every line below is checked by R13/R14/R15; inline code and fenced code are the
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
见 design/foo.md。                          → R13 doc-reference（正文裸 .md 路径）
细节见 [缓存设计](cache-design.md)。          → R13 doc-reference（markdown 链接指向文档）
参考 [[cache-design]]。                      → R13 doc-reference（无锚短形解析到文档而非词条）
截图在 attachments/stampede-chart.png 里。   → R14 bare-image-path（图片路径是纯文本）
见 [[尚未注册的词条名]]。                     → R15 dangling-term（文档与词条双双解析不到）
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
