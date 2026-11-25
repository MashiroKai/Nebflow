---
name: wiki-docs
description: Project document-vault conventions. Obsidian-compatible markup kept for what the terms need (wikilinks, block anchors, word entries), plus the reading face of every document — plain words, answer first, details folded, cards per section, captioned minimal figures. Use whenever a deliverable is a document that belongs to a project's knowledge base.
---

# Wiki Docs — project document vault conventions

Every markdown/HTML document written as a project deliverable (report, review,
design note, investigation record) belongs to the project's document vault:
write it into the vault, keep its terms citable vault-wide, and keep it
readable as a standalone paper.

Two faces must both hold, and they are NOT the same thing:

- **The term face** (词条) — the part this standard protects hardest. A word
  entry is a block-anchored definition the whole vault can cite by name;
  wikilinks, block anchors, first-use citation and abbreviation expansion all
  exist to serve it. Those requirements are kept in full.
- **The reading face** — a person has to get the answer in ten seconds. Plain
  words, the answer first, the argument folded away behind one click, one card
  per part, figures that show rather than explain. This is the writing-style-v2
  discipline (clauses ⑨⑩⑪⑫ below).

The nebflow wiki graph view still indexes the vault, but the graph is no longer
a product we build towards (author order 2026-11-05 12:09): graph-shape rules
that served nothing but link density were dropped or downgraded in this batch,
and nothing on the term face was touched.

## Mandatory clauses (checkable — a violation is a rework)

The thirteen clauses below are author mandates (2026-10-21; ④ added 2026-10-29,
term-citation batch; ⑤ added 2026-10-30, card-face batch; ⑥ added 2026-11-05,
images-live-with-the-doc batch — re-cut the same day with binding points ⑤⑥,
"figures do not show" batch; ⑦⑧ added 2026-11-05, typography-and-abbreviations
batch — author rulings 11:09 and 11:11; ⑨⑩⑪ added 2026-11-05, writing-style-v2
batch — author order 12:09; ⑫ added 2026-11-05, cite-terms-only batch; ⑬ added
2026-11-05 21:24, figure-first batch), not style
advice. All thirteen are machine-checkable
by the validator in `scripts/check_wiki_docs.py` (Python 3 stdlib only, zero
deps) — except clause ⑤'s fence existence, which stays convention-enforced
(skill + dispatch briefs) until the validator grows its existence rule
(design §7.4, still open), and ⑪'s figure *style* (colours, widths, text
budget), which a validator cannot judge — only its caption requirement is
machine-checked:
1. **Vault format, every document (Obsidian-compatible, kept for the terms)** —
   written inside the vault
   `<data_root>/projects/<project>/.stand/docs/` (the project's REGISTRATION
   dir, next to its `project.json` — NOT the project workspace repo; author
   ruling 2026-11-03), with a frontmatter block (`type:` / `status:`),
   references as wikilinks, attachments under `attachments/`. The
   Obsidian-compatible part is not decoration: `[[wikilink]]` + `#heading` +
   `^block-id` is the *only* syntax the term registry and the citation
   resolution read — drop it and 词条 stops working.
   → check rule `obsidian-format`.
2. **Cite what you build on (断链即缺陷；连线密度只是建议)** — when document B
   builds on document A, B cites A at the point of use. Broken references
   (missing target, missing `#heading` anchor, missing `^block-id`, an
   unregistered term short form) are defects. An orphan document with zero
   outgoing and zero incoming links is NOT a defect any more (writing-style-v2
   batch, author order 2026-11-05 12:09): that rule only ever served graph
   link-density, and the graph is no longer built. It is printed as an
   advisory instead. → check rules `broken-link` / `broken-anchor` /
   `broken-block`; `orphan-doc` is advisory. Also downgraded / removed in the
   same batch: the "A gains a See-also link back to B" reciprocity rule (gone),
   the "wire every new document to glossary **or index.md**" requirement (the
   glossary half survives — it is the term face; the index half is gone).
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
5. **Card face — a fenced ```human block is one card (card-face batch
   2026-10-30, re-cut 2026-11-05)** — the product renders every ```human
   fence as ONE bordered card (`.nf-human-card`) and folds everything outside
   the fences into a collapsed drawer (`.nf-human-details`). That is the whole
   card mechanism; there is no second one (see "Card face" below). The
   opening card carries four parts in plain words: **结论 → 关键数字 → 图 →
   待你决定的事** (no English heading, no jargon: "Impression" and its Chinese
   equivalents are on the blocklist, clause ⑨). Inside it: the conclusion in
   ≤3 lines, 3-7 key numbers, and **the caption sentence of the key chart** —
   the chart LINE itself goes **inside the card** — only the card body is on
   the first screen, everything outside the fences is folded into a collapsed
   drawer (`![[assets/<batch>/key-chart.png]]`, caption = the conclusion
   sentence, path **vault-relative with its directory**, never a bare file
   name). The renderer expands image embeds inside a closed ```human fence as
   of the card-image batch (2026-11-05): the fence is not code, it is the card
   body, and `utils.js _transformHumanFace` re-renders that body as markdown —
   so a figure written in the card is a real figure on the first screen, while
   a figure written outside the fence is folded away (clause ⑥ binding point
   ⑥, rule `fence-embed-vault-relative`). Agent jargon (spec pass counts, merge-queue
   speak, worktree, commit hashes, session/chain internals) stays OUT of the
   card — plain wording, or a registered-term short form `[[Name]]` for terms
   that must stay (hover gloss via the term registry). Content outside the
   fences is the machine face: full detail, code anchors, acceptance readouts
   — written as fully as ever, presented folded. One document uses as many
   cards as it has parts (clause ⑩).
   → no validator rule for fence existence — deferred with the design
   (§7.4 另议); enforced by this skill + dispatch briefs until then.
6. **PNG and LaTeX discipline** — embed figures as PNG in the body; axes, legends and captions must remain readable at phone width. PDF/SVG are optional download or enlarged-view links only. Use LaTeX for all formulae, units, subscripts, superscripts and Greek letters: inline `$...$`, display `$$...$$`. No Unicode subscripts/superscripts, formula images or plain-text approximations such as `cm^-2`; code, command output and quoted originals are exempt. The wiki checker reports `png-primary` and `latex-math`; old files are advisory, and PDF embedding is enforced for new files and advisory for legacy files; formula candidates are advisory by default, and `--math-strict` enforces them across the vault.

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
   moves. Two more binding points were added by the "figures do not show" batch
   (author report 2026-11-05, sample
   `agent-panel-three-row__20261105_201500.md`) — both are **renderer
   certainties**, not preferences: a document that breaks them shows a
   "image failed to load" placeholder or a line of literal text, no matter how
   correct the file location is.
   5. **The reference must be the path the renderer actually builds.** Write
   the path **including the batch sub-directory**:
   `![[assets/20261105_agent-panel-rows/after-states-dark.png]]`, never the
   bare file name `![[after-states-dark.png]]`. The renderer's
   `imageCandidates()` (`src/main/resources/web/js/viewers/markdown.js:965`)
   builds a FIXED candidate list — document directory, vault root,
   `<vault>/attachments/`, `<vault>/assets/`, `<docdir>/attachments/`,
   `<docdir>/assets/` — and **never recurses into sub-directories**. A bare
   name for a figure stored in `assets/<batch>/x.png` misses all six.
   → check rule `bare-name-embed` (R17): the violation prints the path you
   should have written.
   6. **Figure lines go INSIDE the ```human card, with a vault-relative path.**
   The card mechanism renders a closed fence body as markdown and folds
   everything **outside** the fences into a collapsed drawer — so a figure
   written outside the card is folded away (first screen shows none of it),
   while a figure written in the card body is on the first screen. As of the
   card-image batch (2026-11-05) `expandImageEmbeds()` expands embeds inside a
   closed ```human fence too (`same file`, `humanCardBodyLines()`): the fence
   is the card body, not code. The path in there must be **vault-relative with
   its directory** (`assets/<batch>/x.png`, `attachments/x.png`): that form
   resolves through the renderer's vault-root slot from ANY document
   directory, while a bare name only resolves from four of the six slots. → check
   rule `fence-embed-vault-relative` (R18). Generic code fences (```js and
   friends) still keep `![[…]]` literal — code is code.
   → check rules `image-outside-vault` (R6), `bare-name-embed` (R17),
   `fence-embed-vault-relative` (R18); per-occurrence escape hatch is the inline
   marker `<!-- wiki-img-exempt: reason -->` (see "Images live with the
   document" below).
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
9. **Plain words (平实语言, writing-style-v2 batch, author order 2026-11-05
   12:09)** — write so the reader never has to decode a word twice. Blocked:
   abstract-noun wrappers and personified jargon (the list is the marker block
   in "Plain language" below — `人面子`, `印象`, `抓手`, `赋能`, `闭环` …). The
   test is mechanical: **if a plain verb + noun can say it, an abstract noun
   must not wrap it**. Say "先写结论，再写细节", not "采用结论先行的叙事结构".
   Abbreviations still get expanded (clause ⑧); the two rules are different:
   ⑧ is about tokens, ⑨ is about words that are already Chinese and still
   opaque. → check rule `plain-language` (file:line + the suggested
   replacement from the blocklist).
10. **Answer first, details folded (结构三层, same batch)** — every document
   has three layers, in this order: **① the overall conclusion** (one
   paragraph at the top — the answer, not the background); **② a conclusion
   sentence at the head of each section** (one bold line); **③ the argument
   itself, inside a collapsed block** (`<details><summary>…</summary>`), so a
   reader who trusts layer ② never sees layer ③ unless they click. → check
   rules `doc-structure` (section head is not a bold one-line conclusion) and
   `detail-fold` (a section's open prose over 600 visible characters with no
   `<details>` block; threshold via `--fold-threshold`); templates in
   "Document skeleton" below.
11. **Figures show, captions speak (图简约 + 图注, same batch)** — a figure
   carries the shape of the data, not the explanation of it. Minimal style:
   one accent colour at most, grey ramp plus dash patterns for series, no
   grid, no in-figure paragraphs, legend labels ≤ one short line. Everything
   the figure cannot say in three words goes into the **caption** (`图 N …`,
   one sentence stating the conclusion) and the body text. Every figure has a
   caption; a caption over 120 characters is a body paragraph wearing a
   caption's clothes. → check rule `image-caption` (missing) and the advisory
   `image-caption-long`; parameters, positive example and the DOSTEL baseline
   in "Figures in a document" below.

12. **Cite terms only (只许引用词条, cite-terms-only batch 2026-11-05)** — a
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

13. **Figures are the document; words annotate them (以图为主, author ruling
   2026-11-05 21:24)** — a visual report is a **figure-led** document: the body
   IS the figures (Mermaid / data charts / screenshots) and prose exists only
   to annotate and conclude. Author wording: 「一定要以图为主……文字要尽量少，
   只说关键内容，然后不得不补充的细节，就放在可点击展开的块里」. Four binding
   points:
   (a) **Figure order = narrative order** — a reader scrolling the figures gets
   the whole argument without reading a paragraph.
   (b) **Words carry conclusions, decisions and readouts only** — process,
   derivation and item-by-item enumerations do not sit in the body.
   (c) **Whatever detail must stay goes into a collapsed block**, in the one
   compliant form `<details><summary>过程</summary>` + blank line + body + blank
   line + `</details>`; the summary is a short label with information in it
   (「过程」「取证读数」「复现步骤」), never a whole sentence.
   (d) **Exceptions are declared in the document**, never taken silently — a
   step-by-step derivation document states why it is unfigured / unfolded, with
   the marker `<!-- wiki-figure-exempt: 理由 -->` (reason non-empty) or
   `<!-- wiki-fold-exempt: 理由 -->`.
   Anti-patterns, spelled out because they are what this clause was written
   against: a wall of explanatory prose; a tutorial on "how to drive the tool
   that draws the chart"; evidence pasted line by line into the body; a caption
   that restates what the figure already says. → check rules `image-required`
   (R19: a document with no figure at all) and `stacked-detail` (R20: a run of
   item-by-item readout / step lines outside any `<details>`); the whole
   discipline and its thresholds are in "Figure first, words annotate" below.

Run before delivering any document batch:

```
python3 ${SKILL_DIR}/scripts/check_wiki_docs.py --project <project> --glossary glossary.md
```

- The validator has a regression fixture — `scripts/test_check_wiki_docs.py`
  (stdlib `unittest`, builds throwaway vaults, covers rules R1–R12 + R16 (the
  renumbered `detail-fold`), the
  cite-terms-only trio R13–R15 and the
  red→green exit codes, including the typography/abbreviation cases: not
  expanded ⇒ flags, expanded ⇒ passes, unit allowlisted ⇒ passes, inside a
  code fence ⇒ not scanned, colour over budget ⇒ flags, missing indent ⇒
  flags; and the writing-style-v2 cases: blocked word ⇒ flags with a
  suggestion, blocked word inside inline code ⇒ silent, section without a
  bold conclusion ⇒ flags, open prose over the fold threshold ⇒ flags, folded
  prose ⇒ passes, image without a caption ⇒ flags, over-long caption ⇒
  advisory only, orphan document ⇒ advisory only; and the two later batches:
  bare-name embed ⇒ flags **with the path to write**, in-vault sub-directory
  path ⇒ passes, embed inside a human fence ⇒ flags, embed outside it ⇒
  passes; document with no figure ⇒ flags, declared `wiki-figure-exempt` ⇒
  passes, 12+ un-folded detail lines ⇒ flags, the same lines inside
  `<details>` ⇒ passes). Run it in the same breath
  as any validator change:
  `cd scripts && python3 -m unittest test_check_wiki_docs -v`.
- **Scope of clauses ⑦⑧⑨⑩⑪⑫ (stock transition).** R7–R12, R16 and the cite-terms
  trio R13–R15 are enforced as
  violations for **new-format documents**: a stage document whose filename
  time block is ≥ `20261105_111100`, or any document that declares `style:`
  in its frontmatter (opt-in), or any document at all under
  `--strict-style` (the full-vault remediation sweep). Older stock is
  reported as **advisory** — printed with file:line and counted, but outside
  the violation list and the exit code — because 400 existing documents cannot
  be re-laid-out in one batch. Touch a legacy document and it comes into
  scope: fix what you touch. Flags: `--style-since <YYYYMMDD_HHMMSS>`,
  `--strict-style`, `--skip-style`, `--allowlist`, `--allowlist-extra`,
  `--plainwords`, `--plainwords-extra`, `--fold-threshold <chars>`,
  `--style-since-v2 <YYYYMMDD_HHMMSS>` (R10–R12 and R16 take effect from their own
  stamp — `20261105_130000`, this batch's delivery; the `style:` frontmatter
  opt-in belongs to R7–R9 and does not retro-apply).
- **Scope of clauses ⑥ ⑤⑥ and ⑬ (same stock transition, own stamps).**
  R17 `bare-name-embed` / R18 `fence-embed-vault-relative` are hard from
  `--image-since`, default `20261105_130000` (same reasoning as v2: documents
  written before the rule existed cannot be judged by it, so they print as
  advisory); R19 `image-required` / R20 `stacked-detail` are hard from
  `--figure-since`, default `20261105_212400` (the figure-first ruling's own
  moment). All four go hard for every document under `--strict-style`. The
  readout line `图能不能显示（R17-R18 …）` always prints both tiers, so a stock
  hit is visible even when it does not fail the run.
- The vault root defaults to `<data_root>/projects/<project>/.stand/docs/`
  (`STAND_HOME` / `NEBFLOW_HOME` override the data root); `--project` defaults
  to `stand`. `--root <path>` still overrides explicitly — use it to validate a
  not-yet-migrated workspace `docs/` vault.
- Deliverables must pass with zero NEW violations (exit 0). Pre-existing
  legacy violations in the vault are not a pass for this batch — fix what you
  touch.
- `--graph out.json` (optional, no document-side requirement) exports the same
  data the wiki index builds — `{nodes, edges}` — for offline analysis or a
  one-off visualisation; each doc node embeds its paragraph index
  (`paragraphs`: edge-form anchor / line range / ≤280-char excerpt) and its
  declared terms (`terms`: name / aliases / source `glossary|doc` / rel /
  anchor / line range / ≤280-char definition — isomorphic with
  WikiIndex.scala, the `GET /api/wiki/term` + `nodeJson.terms[]` consumption
  face, which is the term face and stays); `--json` machine-reads the
  violation list; exit codes: 0 clean, 1 violations, 2 usage error.
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
- **Sub-project vaults (#744-B, layout only)**: any `<sub>/docs/` directory
  inside the vault is its own level — a sub-project keeps the same vault
  convention one level down (`docs/<sub>/docs/`), so its own glossary and its
  own term names do not collide with the parent's. Cross-level citations use
  the target level's path form (`[[<sub>/docs/topic.md]]`) and are counted as
  cross-level links, never silently broken. The graph view used to render a
  parent node drilling into a child graph; that rendering is no longer a
  requirement — the layout rule and the per-level validation stay, because a
  term registry needs one definition source **per level**.
  Validate every level independently:
  `python3 ${SKILL_DIR}/scripts/check_wiki_docs.py --project <project> --include-subvaults`

## Document form (paper style)

- The document title is the first `# H1` heading. It is the document's name to
  the reader and the label any index or listing shows.
- Structure: the overall conclusion first (one paragraph), then one conclusion
  sentence per section with its argument folded underneath (clause ⑩), then the
  appendix.
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
5. **The path must be the one the renderer builds (write the sub-directory).**
   Batch figures live in `<vault>/assets/<batch>/`; the reference must say so:
   `![[assets/20261105_agent-panel-rows/after-states-dark.png]]`. The renderer
   (`web/js/viewers/markdown.js:965 imageCandidates()`) builds a **fixed**
   candidate list — document directory, vault root, `<vault>/attachments/`,
   `<vault>/assets/`, `<docdir>/attachments/`, `<docdir>/assets/` — and never
   recurses. A bare `![[after-states-dark.png]]` misses all six, and the page
   shows the "image failed to load" placeholder even though the file is sitting
   in the vault. → check rule `bare-name-embed` (R17); the violation message
   prints the exact path to write. Note the division of labour with R6: R6 asks
   "is the figure in the vault", R17 asks "can the renderer find it" — a
   document can pass R6 and still show a broken image, and that gap is exactly
   what R17 was added for.
6. **Figure lines go inside the human card, not outside it.** The card
   mechanism renders the fence body as markdown and folds everything outside
   the fences into a collapsed drawer; a figure written outside is folded away
   (first screen: none of it), a figure written inside is on the first screen.
   `expandImageEmbeds()` expands embeds inside a closed ```human fence as of the
   card-image batch (2026-11-05) — the fence is the card body, not code.
   Write the image line **inside the card** with a **vault-relative path that
   includes the directory** (`assets/<batch>/x.png`). → check rule
   `fence-embed-vault-relative` (R18). Generic code fences keep `![[…]]`
   literal — that part is unchanged.

The sample that started this batch (one defect left after the card-image batch,
where it used to be two):

````markdown
```human
图：
![[after-states-dark.png]]
```
````

Today it renders — but only as a bare-name lookup, which fails whenever the
figure sits in a sub-directory. One fix: write the path including
`assets/<batch>/` (R17 outside a fence, R18 inside it). The card keeps the
figure AND the caption sentence; moving the line below the closing fence would
now hide it in the drawer instead of fixing it.

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
skipped — **except the `human` fence**: it is a content face, so its images are
checked like body content (checked by R6/R17 for location and resolvability —
but note that an embed *inside* a human fence can never render, which is R18's
whole point: the checker still sees it, the renderer never draws it). `data:`
inline images are self-contained and pass. Migration of existing stock is the
author's call — the validator reports, it never moves or rewrites documents.

## Figure first, words annotate (以图为主, clause ⑬)

Author ruling 2026-11-05 21:24, verbatim: 「一定要以图为主，我们 visual report
很多内容都是关于如何使用专业的工具画出更好的图。文字要尽量少，只说关键内容，
然后不得不补充的细节，就放在可点击展开的块里。」

### The four binding points

1. **The document IS the figures.** Mermaid diagrams, data charts, screenshots —
   that is the body. Text annotates and concludes; it does not narrate. Read
   the figures top to bottom and you have the argument.
2. **Words: conclusions, decisions, readouts only.** Process, derivation, and
   item-by-item enumeration do not sit open in the body. If a sentence exists
   because "the reader might want to know how we got here", it belongs in a
   fold.
3. **Detail that must stay gets folded.** One compliant form:

   ````markdown
   <details><summary>过程</summary>

   …细节…

   </details>
   ````

   The summary is a **short label with information in it** — 「过程」「取证读数」
   「复现步骤」「七条判据读数」 — never a whole sentence, never a bare 「详情」.
   The blank lines around the body are required: without them the body stays raw
   text and markdown inside the fold stops working.
4. **Exceptions are declared in the document.** A derivation-heavy document,
   or one that genuinely needs no figure, says so inline —
   `<!-- wiki-figure-exempt: 推导型文档，公式逐步变形无法成图 -->` or
   `<!-- wiki-fold-exempt: 结论本身就是 12 行逐条读数，已是最简形态 -->`. The
   reason must be non-empty. A silent exception is a violation with a comment.

### The anti-pattern list (what this clause was written against)

- A wall of explanatory prose where a figure would have said it in one glance.
- A tutorial on "how to drive the tool that draws the chart" — the tool recipe
  belongs in a fold, or in the tool's own skill, not in the body.
- Evidence pasted line by line into the body (dozens of assertion lines, log
  excerpts, per-file diff lists).
- A caption that restates what the figure already says — the caption states the
  conclusion the figure proves, nothing more.

### Annotations are small: figure, caption, one sentence

````markdown
![[assets/<batch>/after-states-dark.png]]

图 1 八个状态同屏：状态位只剩图标，第三行是各自的当前动作。

&emsp;&emsp;**状态被画了四遍时，名字就该看不见了**——收成单表后一行只留一个图标。
````

### Machine checks and their thresholds (verbatim, so they can be reviewed)

| Rule | Criterion (exact) | Remedy the message gives |
|---|---|---|
| `image-required` (R19) | A `.md` document with **zero figures**: no image reference (`![[…]]` / `![](…)` / `<img src>`) and no ```` ```mermaid ```` fenced block | 补一张图（截图 / 数据图表 / mermaid），或写 `<!-- wiki-figure-exempt: 理由 -->` 明说为什么 |
| `stacked-detail` (R20) | Inside one `##` section with **no `<details>` block**, the longest **contiguous run of ≥12 "detail lines"**; a detail line = a list item (`- ` / `* ` / `+ ` / `1. `) or a line inside a code fence (```` ```mermaid ```` excluded — that is a figure; **table rows excluded** — a table is a compression form, same judgement as R16); lines already inside `<details>` are not counted; one report per section (the longest run) | 把这 N 行移进 `<details><summary>过程</summary>` + 空行 + 正文 + 空行 + `</details>`；正文只留结论、决策与读数 |
| `detail-fold` (R16, earlier) | Inside one `##` section, **>600 visible characters of open prose** (sentences, not tables/lists/code/captions) and no `<details>` | 把论证细节包进折叠块 |

R16 and R20 are complements, not duplicates: R16 counts un-folded **prose
volume**, R20 counts un-folded **enumerations** (a section made only of 40
bullet lines has almost no prose and would pass R16 alone). A section can trip
both; fix both by folding once.

Thresholds are flags: `--fold-run <n>` (R20 run length, default 12),
`--fold-threshold <chars>` (R16 prose volume, default 600). Both are hard from
`--figure-since 20261105_212400` (this ruling's own moment) and advisory for
older stock; `--strict-style` makes them hard for every document in the vault.

## Figures — minimal style, the caption says it (clause ⑪)

Author order 2026-11-05 12:09: *"对产出的学术类的图谱，要采用我们最近画的 dostel
的那几张图的风格，简约，突出重点，然后图里尽量少文字解释，文字解释放到文档里，
比如图的 caption"*.

### Baseline — the DOSTEL figures this clause is measured against

Repo `DOSTEL` (`~/Claude code/DOSTEL`), batch written 2026-11-03 (the
most recently produced academic figures):

| Role | Path (repo-relative) |
|---|---|
| spectrum (raw input) | `figures/20261103_cabin_neutron_energy_weight_flux/fig2_cabin_shidi_spectrum.png` · `.svg` |
| weighted contribution | `figures/20261103_cabin_neutron_energy_weight_flux/fig4_dose_contribution_differential.png` · `.svg` |
| cumulative share (the answer) | `figures/20261103_cabin_neutron_energy_weight_flux/fig5_dose_contribution_cumulative.png` · `.svg` |
| before/after column mapping | `figures/20261103_gcr_col_mapping/fig_col30_neutron_before_after.png` · `.svg` |
| style source of truth | `figures/20261103_neutron_energy_weight_flux/scripts/fig_style.py` (imported by the later batch through `fig_style_bridge.py`, so one file defines the look) |

### Measured style parameters (read from `fig_style.py`, not from memory)

| Knob | Value |
|---|---|
| font | DejaVu Sans — axis label 14 pt, ticks 12 pt, legend 11 pt |
| figure | 9.4 × 6.2 in at 330 dpi = 3102 px wide (the batch rule is ≥3000 px) |
| spines | all four visible, black, 1.2 pt |
| ticks | inward, on **all four** sides; major 5.5 pt / minor 3.0 pt; widths 1.0 / 0.8 |
| grid | **none** |
| in-figure title | **none** |
| colour | grey ramp only — `#000000` `#3F3F3F` `#6E6E6E` `#9A9A9A` `#C4C4C4`; series additionally differ by dash (`-` `--` `:` `-.`) so the figure survives greyscale printing |
| line width | 1.4–1.8 pt |
| legend | frameless, upper left, inside the axes |
| axes | one explicit axis spec per quantity family (ranges and ticks written out, never auto-generated) |
| white space | white background, no panel fill, no shadow, no 3-D |

### The three text rules this batch adds (the baseline had not won these yet)

Honest reading of the baseline: `fig2` / `fig4` / `fig5` still carry multi-line
legend entries and a grey explanatory footnote inside the axes. Borrow their
visual grammar — and tighten the text:

1. **No explanatory sentence inside the image.** Inside the frame: axis labels,
   tick labels, short legend labels, at most one short reference-line label.
   Everything else goes into the caption or the body.
2. **One short line per legend entry** (≈30 characters). A label that needs two
   lines is naming a *condition*, not a series — move the condition into the
   caption (`图 X：黑线实测，灰线为 15 / 25 g/cm² 铝壳仿真`) and keep the legend
   as `实测` / `仿真 15` / `仿真 25`.
3. **The caption states the conclusion, not the contents.** 「图 1 危害贡献的主体
   在 1–10 MeV 段」— not 「图 1 三条曲线的对比图」.

### How a figure is placed in a document (the standard block)

````markdown
![舱内中子谱：仿真比实测软](assets/cabin-neutron-spectrum.png)

图 1 舱内中子能谱的三条曲线，危害贡献的主体都落在 1–10 MeV 段。

&emsp;&emsp;正文在这里引用它：**1–10 MeV 段贡献了大部分剂量**（图 1），
低于 1 MeV 的部分可以忽略。
````

- **Caption sits directly under the image line** — `图 N …`, one sentence, ≤120
  visible characters (`--fold-threshold` is unrelated; the caption limit is
  `CAPTION_MAX_CHARS = 120`). Longer ⇒ advisory `image-caption-long`: the
  caption is a conclusion, the explanation belongs in the body.
- **The body cites the figure by number**（图 1），never by path — a bare path
  string is not a reference.
- **Alt text ≠ caption.** Alt text describes the image for a reader who cannot
  see it; the caption states what the figure proves. Both, not either.
- **The image lives in the vault** (`assets/` or `attachments/`, clause ⑥) and
  the copy is named by the document topic, not by the evidence folder.
- **One figure = one conclusion.** Two conclusions ⇒ two figures, or a table.

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

## Plain language — say it in words the reader does not decode twice (clause ⑨)

Author order 2026-11-05 12:09: *"我们的写作要求和词汇要求，不要用晦涩难懂的
词汇，比如人面子，印象"*. One test, applied sentence by sentence:

- **Could a plain verb + noun say it?** Then no abstract noun may wrap it.
  「先写结论，再写细节」 beats 「采用结论先行的叙事结构」 — same content, half
  the decoding work.
- **Does the reader have to translate the word back into an everyday act
  before acting on it?** Then it is blocked, however fashionable it sounds.
- **Personified or metaphor-only wording is blocked** too: it reads as insight
  and delivers none.

| Written | What the reader must do first | Write instead |
|---|---|---|
| `人面子` | guess which "face" this is | 报告开头的卡片 |
| `印象` | guess whether it's a feeling | 结论摘要 |
| 采用结论先行的叙事结构 | unpack three abstract nouns | 先写结论，再写细节 |
| 对口径做一次对齐 | translate into an action | 把两处数字改成一致 |
| 赋能下游 | guess who gave what to whom | 给下游提供 X |
| 形成闭环 | guess where the loop closes | 走完一轮，回到第一步 |

### The blocklist (living in this file, never in the script)

`词 => 建议替换`, one per line, `#` starts a group comment. The validator
reads this block (`--plainwords <path>` overrides it, `--plainwords-extra 词`
adds a word for a single run) and reports the **first** hit per word per
document with file:line plus the suggested replacement. Editing the list IS
editing the standard.

```text
<!-- wiki-plainwords:start -->
# 甲类 · 作者点名的词（字面就是反例）
人面子 => 报告开头的卡片（或直接说「结论卡」）
印象 => 结论摘要（或直接说「先看这里」）
# 乙类 · 抽象名词包裹（用动词+名词说清）
抓手 => 具体做法（写清谁做什么）
赋能 => 提供 …（写清提供了什么）
闭环 => 走完一轮（写清从哪回到哪）
颗粒度 => 细到什么程度
叙事 => 写法（或直接说「怎么说」）
心智 => 理解（或直接说「看法」）
布道 => 推广
沉淀 => 存下来（写清存成了什么）
对齐 => 改成一致（写清哪两处改成一致）
<!-- wiki-plainwords:end -->
```

Scope of the scan: body lines and card (`human`) lines; fenced code blocks and
inline code spans are NOT scanned — quoting a blocked word in backticks to
discuss the blocklist is exactly the legitimate use, and this section itself
does it.

## Document skeleton — answer first, details folded (clause ⑩)

Author order 2026-11-05 12:09: *"整体文档先有总体结论，然后再是各分点的结论，
具体的论证细节，放在点击展开后的内容"*. Three layers, in this order:

1. **Overall conclusion** — one paragraph right under the title: the answer,
   the scope, the precondition. Not "本文分三部分", not the background.
2. **Per-section conclusion** — the first line of every `##` section is one
   bold sentence (`**…**`, ≤80 visible characters).
3. **The argument** — inside a collapsed block, one click away. A reader who
   trusts layer ② never sees layer ③; a skeptical reader opens it and gets
   everything (data, code anchors, counter-examples).

### Rendering measurement (2026-11-05, engine's vendored marked v12.0.2)

| Form | Rendered | Verdict |
|---|---|---|
| `<details><summary>论证细节</summary>` + blank line + markdown body | raw HTML passes through verbatim, body still parsed (`<p>&emsp;&emsp;…</p>`) | **the only compliant fold** |
| `> [!note]- 论证细节` (Obsidian callout fold) | `<blockquote><p>[!note]- 论证细节…</p>` — the marker renders as literal text, nothing folds | rejected: Obsidian-only, degrades to a quote elsewhere |
| `<details><summary>…</summary>` with no blank line before the body | body lines stay raw text, markdown inside the fold stops working | blank line required |

`<details>` is the one form both faces render: Obsidian renders raw HTML
natively, and the engine's pipeline passes it through untouched (`utils.js`
`renderMarkdownWithMath` → marked, same vendor bundle).

### Template (copy this)

````markdown
# <topic>

&emsp;&emsp;**总体结论：一句话给出答案。** 第二句补范围与前提，总长 ≤3 句。

## 一、<part 1>

&emsp;&emsp;**<这一节的一句话结论>**

<details>
<summary>论证细节（点击展开）</summary>

&emsp;&emsp;推导 / 数据 / 代码锚点 / 反例。

</details>

## 二、<part 2>

&emsp;&emsp;**<这一节的一句话结论>**

<details>
<summary>论证细节（点击展开）</summary>

&emsp;&emsp;…

</details>
````

Machine checks: `doc-structure` (a `##` section whose first prose line is not
a bold one-liner, or is a bold paragraph over 80 characters) and `detail-fold`
(a section holding more than **600 visible characters** of open prose and no
`<details>` block — `--fold-threshold` retunes it). Tables, lists, code blocks
and caption lines are compression forms and are not counted as open prose.

**Exempt sections** (structure, not argument — they start with lists or
tables and have no conclusion to state): 词条 / 附录 / 参考文献 / 变更记录 /
目录 / glossary / references / index.

## Card face — one card per part (clause ⑤, re-cut in writing-style-v2)

Batch / investigation reports open with the four-part card inside a
```` ```human ```` fence (设计稿: docs/design/20261030_human-readable-
presentation.zh-CN.md, 附录 A; author ruling 2026-10-30: the card face is
MANDATORY — an agent that forgets the fence has not delivered), and **every
further part of the document gets its own card** (author order 2026-11-05
12:09: *"每部分内容可以像目前印象一样，渲染卡片分隔开"*).

````markdown
# <batch / investigation> report

```human
## 结论
<one-sentence answer: what was done + outcome + whether the author must
act; ≤3 lines>

**关键数字**：<files changed · assertions green · time · scale, 3-7>
```

![[assets/<batch>/key-chart.png]]

图 1 <the caption IS the conclusion sentence — one line, ≤120 chars>

```human
## 一、<part 1>
**<这一节的一句话结论>**
```

<details>
<summary>一、论证细节（点击展开）</summary>

&emsp;&emsp;推导、数据、代码锚点。

</details>

```human
## 二、<part 2>
**<这一节的一句话结论>**
```

<details>
<summary>二、论证细节（点击展开）</summary>

&emsp;&emsp;…

</details>
````

- **Which renderer this reuses (do not invent a second one).** `humanFace.css`
  (`.nf-human-card` = bordered content card; `.nf-human-details` = collapsed
  drawer) plus `utils.js _transformHumanFace` (utils.js:920-956): every closed
  ```human fence becomes `<div class="nf-human-card">` with its body rendered by
  the same markdown pipeline, and everything outside the fences is wrapped in
  `<details class="nf-human-details">`. Cards therefore cost nothing new — the
  fence is the card. The bordered box, radius, spacing and dark-theme flip come
  from the existing tokens.
- **Title line of a card**: `## <part name>` — a noun that names the part
  ("结论", "一、配色", "要你决定的事"), never a sentence and never jargon.
- **Inside a card**: the conclusion, the 3-7 key numbers, and **the key
  chart's own line** — the figure lives in the card body, on the first screen,
  with a vault-relative path (`![[assets/<batch>/key-chart.png]]`) and its
  caption sentence right under it. A figure line left OUTSIDE the fences is
  folded into the collapsed drawer: it exists, but nobody sees it on the first
  screen (clause ⑥ binding point ⑥, rule `fence-embed-vault-relative`). The
  argument goes into the `<details>` block right after the card (layer ③), so
  the card stays readable at a glance.
- One document may carry as many ```human fences as it has parts; they render
  in order as cards. Content left outside every fence folds into one
  "details (N lines)" drawer — with per-part cards plus `<details>`, that
  drawer is normally empty, which is the goal.
- Jargon ban (cards only): spec pass counts → "all tests pass" + one key-number
  line; merge / merge queue → "merged into main (queued)"; worktree →
  "working copy" or omit; chain / node / delegate session → "subtask"; commit
  hashes stay in the machine face. Retained terms that genuinely must stay are
  written as registered-term short forms `[[Name]]` — hover gloss via the term
  registry (section "Terms and short-form citations" below).
- The key-chart obligation is a CARD obligation (Tufte: one chart, one
  conclusion): ≥1 chart in the opening card, its caption stating the
  conclusion, the asset under `attachments/` or `assets/`. Tables stay
  chart-free — the chart duty does not propagate into the detail blocks.
- Product face (already shipped, all markdown faces share one pipeline):
  `utils.js renderMarkdownWithMath` renders the fence into `.nf-human-card` and
  wraps outside content in `<details class="nf-human-details">`; chat bubbles,
  canvas md viewer, wiki docs, flow cards and report cards all present the same
  way. Streaming safety: the transform engages only after every fence is
  closed — mid-stream snapshots render as plain code blocks, nothing crashes.
- Profile system retired (author order 2026-10-31 16:13, output-block batch;
  design doc §6 marked deprecated): the five presentation tiers and the seven
  per-tier parameters are gone from product AND storage — figure density and
  length are decided by the report content itself plus the long-term user
  profile, not by parameters. This card template is the surviving discipline
  and stays mandatory.
- `<output>…</output>` paired tags (same batch, revised to tag form at 16:16 to
  avoid markdown-syntax collision) mark user-visible content in chat-facing
  deliverables: the product shows ONLY tagged content by default — everything
  outside (reasoning, tool narration) is stripped until the user expands the
  badge, and the expanded view shows the raw full text with the tags verbatim;
  untagged legacy content renders fully as before. Write tagged content
  WeChat-style (short direct paragraphs, conclusion first); tone/length follow
  the user's communication-style profile (`~/.stand/User.md`), defaulting to
  concise direct Chinese. In vault documents the pipeline renders the tags
  transparently (content shown, tags invisible). Spec: design doc §11
  (docs/design/20261030_human-readable-presentation.zh-CN.md).

```markdown
# legacy heading kept for the record
## Human face (报告头, 2026-10-30 batch)
```

The section you are reading replaces that heading; documents written before
2026-11-05 keep their `Impression` heading and are not retro-edited.

## References (citations — the term face, not a graph quota)

Use Obsidian wikilinks. They render as clickable references, they resolve into
the term registry, and they are how a reader jumps from a claim to its source:

Since the cite-terms-only batch (clause ⑫, 2026-11-05) a reference must
point at a **term** — not at a document, not at a bare path:

- `[[词条名]]` / `[[词条名|显示名]]` — cite a registered term by name
  (the only whole-target form that is still legal without an anchor).
- `[[glossary#^retry-storm]]` — cite a concept definition block (preferred for
  concepts: the reader jumps to the exact definition and the target paragraph
  is highlighted).
- `[[auth-flow-review#Token lifetime]]` — positional reference into a section.
  Positional references stay legal because they resolve to a *place*, not to a
  document identity.
- `![[architecture.svg]]` — embed an attachment from `attachments/`.
- Not citations any more (rules R13/R14): `[text](topic.md)` (a
  markdown link to a document), a document short form `[[some-doc]]`,
  a bare path in prose (`见 design/foo.md`) and a plain-text image path
  (`attachments/a.png`). Cite the term, embed the image, or link it.

Citation discipline (what survived the graph cut, 2026-11-05):

- **A source you build on is cited at the point of use.** That is all a
  citation owes the reader — the reader can reach the source. It does NOT owe
  the graph a link pair.
- **A document with zero outgoing and zero incoming links** is printed as an
  advisory, not a defect: the link-density rule only ever served the graph,
  and a self-contained note is a perfectly good document. If the note does
  introduce terms, register them (clause ④) — that is the face that matters.
- **Glossary or bust:** concepts that other documents will cite belong in the
  glossary, not in one document's body. (The old "or wire it to `index.md`"
  requirement is gone with the graph; an index is a convenience, not a quota.)
- **A "See also" link back** from A to B is a nice courtesy — no longer
  required, and never retro-edited into existing documents.
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
name. Two declaration faces, both derived from the block-anchor paragraphs the
term registry indexes — never declared in frontmatter (a `terms:`
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
404 unregistered), and each document node's `terms[]` projection (hover
preview + deep links) — this is the term face's engine side, and it is
untouched by the graph cut. Scope v1: term definitions are a markdown face —
html documents get reference jumps with a document-level summary fallback,
no html definition blocks.

## What not to do

- Do not write reports outside the vault (chat-only delivery leaves the terms
  unciteable).
- Do not bury the answer — the opening card (clause ⑤) states it, every
  section head states its own (clause ⑩); argument never precedes answers.
- Do not write a section whose first line is a paragraph — one bold sentence
  first, argument folded underneath.
- Do not explain inside a figure — the frame holds labels; the caption holds
  the conclusion; the body holds the reasoning.
- Do not reach for an abstract noun when a verb is available (`对口径做一次
  对齐` → `把两处数字改成一致`).
- Do not duplicate a concept's definition in several documents — define once,
  reference everywhere else.
- Do not use bare file paths as references; a link or a term citation is the
  reference, a plain path string is not.
- Do not cite a **document**: no `[[some-doc]]` short form, no
  `[text](some-doc.md)`, no bare path in prose. Cite the term instead
  (clause ⑫, rules R13/R15).
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

Every vault document starts with a tiny frontmatter block:

```
---
type: research-log
status: open
---
```

- `type` names the document's role in the scenario pack (below); readers and
  tooling filter by it.
- `status` says where the document stands. The statuses that mark finished work
  (`solved` / `done` / `accepted` / `mastered` / `published`) read as "already
  resolved" at a glance when a listing shows them. (The graph used to draw a ✓
  ring on them; the field is unchanged and still worth filling — it is the only
  machine-readable progress signal a document carries.)
- `summary` carries the reader's abstract (**读者提要, R21 ruling 2026-11-19
  11:30**) — 40–100 counted units, where each Han character counts as one and
  each run of letters/digits counts as one; punctuation and whitespace count as
  nothing. **New documents write it in frontmatter `summary:` — that is the
  only new location.** The body marker `<!-- reader-summary -->` is kept for
  historical compatibility and still read exactly as before; do not add new
  ones. The validator accepts either location and its R21 check is unchanged.

## Scenario packs

Pick the pack that matches the engagement; do not invent a new one when these
fit.

### Research (科研)

A research engagement is a line of work, not a pile of files. Four document
roles carry the line:

- `research-log` (`status: open` while active): dated entries. Each entry links
  what it produced that day — experiments, notes, decisions — with wikilinks,
  so the log stays the reading order for the work.
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

Progress readout = the `status` fields: open problems, solved problems,
conclusions.

### Learning (学习)

- `learning-map`: the index document — one section per knowledge area, each
  knowledge point a wikilink to its own document. The map is the reading order.
- Knowledge-point documents: one concept each; link prerequisite points
  (`Prerequisites: [[point-a]], [[point-b]]`) so a reader can walk the
  dependency order.
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

A wikilink or embed pointing at a canvas-openable attachment (PDF, Office,
image, media) — `[[paper.pdf]]`, `![[figure.png]]` — opens it directly in
canvas. Cite the attachment, never a bare path string: the link is what makes
it reachable, and it also gives the term registry something to resolve against.
The graph view listing attachments as nodes is a side effect, not a goal.
