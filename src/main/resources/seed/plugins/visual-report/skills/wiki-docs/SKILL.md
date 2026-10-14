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

## Vault layout

- Vault root: `<workspace>/docs/`. Create it on first write (idempotent).
- Attachments (images, exports, data files referenced by documents):
  `<workspace>/docs/attachments/`. Never scatter attachments next to documents
  or in ad-hoc folders — every asset referenced by a document lives under
  `attachments/` and is referenced by its vault-relative path.
- One document = one topic. The file name is the document identity; prefer
  short kebab-case names (`auth-flow-review.md`), because other documents cite
  the file name.

## Document form (paper style)

- The document title is the first `# H1` heading. The graph uses it as the
  node label.
- Structure: conclusion first, then evidence and reasoning, then appendix.
- Every new concept must be understandable at first mention: either define it
  inline in one or two sentences at the point of first use, or link the
  definition to the document/section that defines it (see references below).
  Never leave a term that only a companion document explains without the link.
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

## What not to do

- Do not write reports outside the vault (chat-only delivery loses the graph).
- Do not duplicate a concept's definition in several documents — define once,
  reference everywhere else.
- Do not use bare file paths as references; the link IS the graph edge, a
  plain path string is not.
- Do not rename documents that are already cited; fix the citing links in the
  same change instead.
