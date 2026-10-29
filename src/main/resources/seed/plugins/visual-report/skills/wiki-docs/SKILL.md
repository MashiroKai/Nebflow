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

The four clauses below are author mandates (2026-10-21; ④ added 2026-10-29,
term-citation batch), not style advice. Every clause is machine-checkable by
the validator in `scripts/check_wiki_docs.py` (Python 3 stdlib only, zero deps):

1. **Obsidian format, every document** — written inside the vault
   `<workspace>/docs/`, with a frontmatter block (`type:` / `status:`),
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

Run before delivering any document batch:

```
python3 ${SKILL_DIR}/scripts/check_wiki_docs.py --root <workspace>/docs --glossary glossary.md
```

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

- Vault root: `<workspace>/docs/`. Create it on first write (idempotent).
- Attachments (images, exports, data files referenced by documents):
  `<workspace>/docs/attachments/`. Never scatter attachments next to documents
  or in ad-hoc folders — every asset referenced by a document lives under
  `attachments/` and is referenced by its vault-relative path.
- One document = one topic. The file name is the document identity; prefer
  short kebab-case names (`auth-flow-review.md`), because other documents cite
  the file name.
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
  `python3 ${SKILL_DIR}/scripts/check_wiki_docs.py --root <workspace>/docs --include-subvaults`

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
  (`docs/glossary.md`): one block per concept, each block ending with a block
  id, e.g.

  ```
  Retry storm — a feedback loop where failures trigger retries that multiply
  load and produce more failures. ^retry-storm
  ```

## References (the graph's edges)

Use Obsidian wikilinks. They render as clickable references and build the
relationship graph:

- `[[auth-flow-review]]` — cite a whole document.
- `[[auth-flow-review#Token lifetime]]` — cite a section.
- `[[glossary#^retry-storm]]` — cite a concept definition block (preferred for
  concepts: the reader jumps to the exact definition and the target paragraph
  is highlighted).
- `![[architecture.svg]]` — embed an attachment from `attachments/`.
- Standard markdown links to vault files (`[text](topic.md)`) are also graph
  edges; use them when portability outside Obsidian matters.

Citation discipline:

- A document with zero outgoing links and zero incoming links is a defect
  unless it is the first document in a new domain — wire new documents to at
  least the glossary or the project's index document (`docs/index.md`).
- When document B builds on document A, B cites A at the point of use, and A
  gains a "See also" link back to B when A is revised next. The pair of edges
  is what makes the graph navigable in both directions.
- Anchors must exist: `#heading` must match a heading in the target document;
  `^block-id` must match a block id. A reference that resolves to nothing is
  the document-level equivalent of a broken build.

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

  - DCL（DOMContentLoaded 事件） — the browser's first interactive moment. ^term-dcl

  - 归因结论 — this incident was caused by config drift, not code. ^term-attribution
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

Citation resolution for `[[Name]]` (no anchor) — fixed priority:

1. exact vault-relative path → 2. unique basename document →
3. **term registry** (a unique glossary hit beats doc-intro hits) →
4. still ambiguous/unhit = unresolved (a broken link in R2's report).

Practical discipline: cite `[[Name]]` when the name is unique vault-wide or
glossary-defined; when you mean a SPECIFIC document's term (its plan, its
attribution), cite `[[doc#^term-slug]]` explicitly. `[[Name|display]]`
aliases and `![[Name]]` embeds ride the same resolution.

Engine faces that consume the registry (no extra work for the writer):
`GET /api/wiki/term?name=…&project=…` (single lookup:
`{found, resolved, term}` / `{resolved:false, ambiguous, candidates}` /
404 unregistered), and each graph node's `terms[]` projection (hover
preview + deep links). Scope v1: term definitions are a markdown face —
html documents get reference jumps with a document-level summary fallback,
no html definition blocks.

## What not to do

- Do not write reports outside the vault (chat-only delivery loses the graph).
- Do not duplicate a concept's definition in several documents — define once,
  reference everywhere else.
- Do not use bare file paths as references; the link IS the graph edge, a
  plain path string is not.
- Do not rename documents that are already cited; fix the citing links in the
  same change instead.

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
